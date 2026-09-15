#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MAGI 最適化器の「等価ベンチ」(CRINN 流・実行計測スカラー報酬による A/B 評価).

目的: Kotlin エンジン(サンドボックスでは実行不可)で入れた脱出機構が「本当に効くか」を、
      同型の合成 NSP 上で実測比較する。CRINN の QPS-Recall AUC に相当する報酬で順位付けする。

機構は Kotlin と等価(byte 一致ではなく同じ仕組み):
  - 戦略的振動: 深い停滞 & ON窓 & HARD族が停滞(HF63ゲート) & excursion上限 のとき受理層で hard を割引
  - GLS penalty + aging(減衰)
  - 非線形 restart 摂動 (序盤強→終盤弱)
全機構とも globalBest は生スコアで管理 → 解は退化しない(Kotlin と同じ安全性)。

報酬: best_total を反復チェックポイントで平均(=AUC 風。早く良い解へ届くほど高評価)。低いほど良い。
"""
import random, math, statistics, json

# ---------------- 合成 NSP インスタンス ----------------
def make_instance(S, T, K, seed, tight):
    rng = random.Random(seed)
    canDo = [[True] * K for _ in range(S)]
    for i in range(S):
        for k in range(1, K):
            if rng.random() < 0.25:
                canDo[i][k] = False           # 担当不可で「壁」を作る
    need = [[0] * T for _ in range(K)]
    for k in range(1, K):
        for j in range(T):
            need[k][j] = 1 + (1 if rng.random() < 0.25 + tight * 0.5 else 0)
    lo = [[0] * K for _ in range(S)]
    hi = [[T] * K for _ in range(S)]
    for i in range(S):
        for k in range(1, K):
            if canDo[i][k] and rng.random() < 0.3:
                lo[i][k] = rng.randint(2, 5)
                hi[i][k] = lo[i][k] + rng.randint(0, 3)
    apt = [[-1] * K for _ in range(S)]   # 合成では apt なし
    sgrp = [i % 3 for i in range(S)]     # 3群に割当(c41 群レンジ検証用)
    # c41: (群, シフト, 日次下限, 日次上限)。各日 群のシフト人数を [lo,hi] に収める制約(重み1)。
    c41 = []
    for g in range(3):
        for k in range(1, K):
            if rng.random() < 0.15:
                c41.append((g, k, 0, 1))   # 日次 群人数 <=1 等の上限制約
    return dict(S=S, T=T, K=K, canDo=canDo, need=need, need_hi=need, use2=False, lo=lo, hi=hi, apt=apt, sgrp=sgrp, c41=c41)

def load_golden(path):
    """実 state(golden_state.json)を bench inst へ忠実に読み込む。need=shifts[k].need1(定数),
    canDo=groupShift[group][k]==1, lo/hi=staffRange, apt=groupShiftApt[group][k] を staffRange でクランプ。"""
    d = json.load(open(path))
    shifts = d['shifts']; staff = d['staff']; gshift = d['groupShift']; gapt = d['groupShiftApt']
    K = len(shifts); S = len(staff)
    import datetime
    sd = datetime.date.fromisoformat(d['startDate']); ed = datetime.date.fromisoformat(d['endDate'])
    T = (ed - sd).days + 1
    def need_of(s, key):
        v = s.get(key, '')
        if isinstance(v, int): return v
        v = (v or '').strip()
        return int(v) if v.lstrip('-').isdigit() else -1
    need = [[need_of(shifts[k], 'need1')] * T for k in range(K)]
    need_hi = [[need_of(shifts[k], 'need2')] * T for k in range(K)]
    canDo = [[gshift[staff[i]['groupIdx']][k] == 1 for k in range(K)] for i in range(S)]
    lo = [[0] * K for _ in range(S)]; hi = [[T] * K for _ in range(S)]
    for key, r in d['staffRange'].items():
        i, k = map(int, key.split(','))
        if isinstance(r.get('lo'), int) or (str(r.get('lo', '')).strip().lstrip('-').isdigit()):
            lo[i][k] = int(r['lo'])
        if isinstance(r.get('hi'), int) or (str(r.get('hi', '')).strip().lstrip('-').isdigit()):
            hi[i][k] = int(r['hi'])
    # apt: 群目標を canDo かつ staffRange[lo,hi] でクランプ(Problem と同じ)
    apt = [[-1] * K for _ in range(S)]
    for i in range(S):
        g = staff[i]['groupIdx']; row = gapt[g] if g < len(gapt) else []
        for k in range(K):
            v = row[k] if k < len(row) else ''
            t = int(v) if str(v).strip().lstrip('-').isdigit() else -1
            if t < 0 or not canDo[i][k]: continue
            if lo[i][k] and t < lo[i][k]: t = lo[i][k]
            if hi[i][k] != T and t > hi[i][k]: t = hi[i][k]
            apt[i][k] = t
    sgrp = [staff[i]['groupIdx'] for i in range(S)]
    # cons41: グループ別 日次レンジ。kigou→index, l/u を解く。
    gkig = {g.get('kigou', g.get('name', str(idx))): idx for idx, g in enumerate(d.get('groups', []))}
    skig = {shifts[k].get('kigou', ''): k for k in range(K)}
    c41 = []
    for c in d.get('cons41', []):
        gi = gkig.get(c.get('groupKigou')); ki = skig.get(c.get('shiftKigou'))
        lv = str(c.get('l', '')).strip(); uv = str(c.get('u', '')).strip()
        if gi is None or ki is None: continue
        lo_ = int(lv) if lv.lstrip('-').isdigit() else 0
        hi_ = int(uv) if uv.lstrip('-').isdigit() else 10**9
        c41.append((gi, ki, lo_, hi_))
    return dict(S=S, T=T, K=K, canDo=canDo, need=need, need_hi=need_hi, use2=d.get('use2Patterns', False), lo=lo, hi=hi, apt=apt, sgrp=sgrp, c41=c41)

def allowed(inst, i):
    return [0] + [k for k in range(1, inst['K']) if inst['canDo'][i][k]]

def staff_pen(inst, i, k, n):
    """per-staff soft: low(×90)/high(×45)/apt(|n-t|×1)。Kotlin Evaluator/checker と同一式。"""
    lo, hi, apt = inst['lo'], inst['hi'], inst['apt']
    p = 0
    if lo[i][k] and n < lo[i][k]: p += (lo[i][k] - n) * 90
    if n > hi[i][k]: p += (n - hi[i][k]) * 45
    if apt[i][k] >= 0: p += abs(n - apt[i][k])
    return p

def score(inst, sched):
    S, T, K, need = inst['S'], inst['T'], inst['K'], inst['need']
    use2, need_hi = inst.get('use2', False), inst.get('need_hi', need)
    hard = 0; soft = 0
    for k in range(1, K):
        for j in range(T):
            cnt = sum(1 for i in range(S) if sched[i][j] == k)
            lo_need = need[k][j]
            hi_need = need_hi[k][j] if (use2 and need_hi[k][j] >= 0) else lo_need
            if lo_need >= 0 and cnt < lo_need: hard += lo_need - cnt        # covU (HARD)
            elif hi_need >= 0 and cnt > hi_need: soft += cnt - hi_need      # covO (soft, 重み1)
    cnt = [[0] * K for _ in range(S)]
    for i in range(S):
        for j in range(T):
            cnt[i][sched[i][j]] += 1
    for i in range(S):
        for k in range(1, K):
            soft += staff_pen(inst, i, k, cnt[i][k])
    # c41: グループ別 日次レンジ(重み1)
    c41 = inst.get('c41'); sgrp = inst.get('sgrp')
    if c41 and sgrp:
        for (g, k, lo_, hi_) in c41:
            for j in range(T):
                z = sum(1 for i in range(S) if sgrp[i] == g and sched[i][j] == k)
                if z < lo_: soft += lo_ - z
                elif z > hi_: soft += z - hi_
    return hard, soft

def raw(h, s):
    return h * 1_000_000 + s

# ---------------- ALNS destroy-repair(実機の核) ----------------
def repair_day(inst, sched, j, rng):
    """日 j を貪欲修復: 需要を満たすよう、その日 休(0) の担当可能者を各シフトへ割当てる。"""
    S, K, need = inst['S'], inst['K'], inst['need']
    avail = [i for i in range(S) if sched[i][j] == 0]
    rng.shuffle(avail)
    order = list(range(1, K)); rng.shuffle(order)
    for k in order:
        want = need[k][j]
        have = sum(1 for i in range(S) if sched[i][j] == k)
        idx = 0
        while have < want and idx < len(avail):
            i = avail[idx]; idx += 1
            if sched[i][j] == 0 and inst['canDo'][i][k]:
                sched[i][j] = k; have += 1
        avail = avail[idx:]

def repair_day_smart(inst, sched, j, rng, cnt, covo=False, c41aware=False):
    """[soft-aware 修復] 需要穴を埋める際、割当の marginal soft(下限不足の解消 / 上限超過の回避)が
    最小の担当可能者を選ぶ。cnt[i][k]=現状の総回数(呼出側が当日クリア後に算出)。
    covo=True で need2 まで marginal<0 のみ追加。c41aware=True で群レンジ(c41)の日次 marginal も加味。"""
    S, K, need = inst['S'], inst['K'], inst['need']
    need_hi, use2 = inst['need_hi'], inst['use2']
    c41, sgrp = (inst.get('c41') or []), inst.get('sgrp')
    def c41marg(i, k):
        if not c41aware or not c41 or sgrp is None: return 0
        g = sgrp[i]; d = 0
        for (cg, ck, lo_, hi_) in c41:
            if cg != g or ck != k: continue
            z = sum(1 for x in range(S) if sgrp[x] == g and sched[x][j] == k)
            before = (lo_ - z if z < lo_ else 0) + (z - hi_ if z > hi_ else 0)
            z1 = z + 1
            after = (lo_ - z1 if z1 < lo_ else 0) + (z1 - hi_ if z1 > hi_ else 0)
            d += after - before
        return d
    def marg(i, k):
        return staff_pen(inst, i, k, cnt[i][k] + 1) - staff_pen(inst, i, k, cnt[i][k]) + c41marg(i, k)
    order = list(range(1, K)); rng.shuffle(order)
    for k in order:
        want = need[k][j]
        have = sum(1 for i in range(S) if sched[i][j] == k)
        while have < want:
            best_i = -1; best = None
            for i in range(S):
                if sched[i][j] == 0 and inst['canDo'][i][k]:
                    m = marg(i, k)
                    if best is None or m < best:
                        best = m; best_i = i
            if best_i < 0:
                break
            sched[best_i][j] = k; cnt[best_i][k] += 1; have += 1
        if covo:
            # need2 まで、marginal<0(厳密に soft が下がる=下限割れ職員)だけ追加(covO は出さない)
            cap = need_hi[k][j] if (use2 and need_hi[k][j] >= 0) else want
            while have < cap:
                best_i = -1; best = 0   # marg<0 のみ採用
                for i in range(S):
                    if sched[i][j] == 0 and inst['canDo'][i][k]:
                        m = marg(i, k)
                        if m < best:
                            best = m; best_i = i
                if best_i < 0:
                    break
                sched[best_i][j] = k; cnt[best_i][k] += 1; have += 1

def destroy_repair_day(inst, sched, rng, smart=False, covo=False, c41aware=False):
    """ランダムな1日を破壊(全員休に)→貪欲修復。スライス(列)を snapshot して呼出側が revert 可能に。"""
    T = inst['T']; j = rng.randrange(T)
    col = [sched[i][j] for i in range(inst['S'])]
    for i in range(inst['S']):
        sched[i][j] = 0
    if smart:
        cnt = [[0] * inst['K'] for _ in range(inst['S'])]
        for i in range(inst['S']):
            for jj in range(T):
                cnt[i][sched[i][jj]] += 1
        repair_day_smart(inst, sched, j, rng, cnt, covo=covo, c41aware=c41aware)
    else:
        repair_day(inst, sched, j, rng)
    return ('day', j, col)

def destroy_repair_staff(inst, sched, rng, smart=False):
    """ランダムな1職員の全日を破壊→各日 個別に貪欲修復(被覆の穴を埋める)。行を snapshot。
    smart=True で、穴を埋める際に複数候補シフトから marginal soft 最小を選ぶ(soft-aware)。"""
    S, T, K = inst['S'], inst['T'], inst['K']; i = rng.randrange(S)
    row = sched[i][:]
    for j in range(T):
        sched[i][j] = 0
    # i は全日休にしたので cnt_i[k>=1]=0(休=T)。割当てるごとに cnt_i を進めて marginal 評価。
    cnt_i = [0] * K
    def marg(k, n):
        return staff_pen(inst, i, k, n + 1) - staff_pen(inst, i, k, n)
    for j in range(T):
        # この日に i が埋められる被覆穴のあるシフト集合
        cands = [k for k in range(1, K) if inst['canDo'][i][k] and
                 sum(1 for x in range(S) if sched[x][j] == k) < inst['need'][k][j]]
        if not cands:
            continue
        if smart:
            k = min(cands, key=lambda kk: marg(kk, cnt_i[kk]))
        else:
            k = cands[0]
        sched[i][j] = k; cnt_i[k] += 1
    return ('staff', i, row)

def revert_move(sched, mv):
    kind = mv[0]
    if kind == 'multi':
        revert_move(sched, mv[2]); revert_move(sched, mv[1]); return   # 後勝ち→逆順で revert
    if kind == 'day':
        _, j, col = mv
        for i in range(len(col)):
            sched[i][j] = col[i]
    elif kind == 'staff':
        _, i, row = mv
        sched[i] = row[:]

# ---------------- SA(機構トグル付き) ----------------
def optimize(inst, seed, iters, feats):
    rng = random.Random(seed * 0x9E3779B1 & 0xFFFFFFFF)
    S, T, K = inst['S'], inst['T'], inst['K']
    alw = [allowed(inst, i) for i in range(S)]
    restarts = 3
    best = [[rng.choice(alw[i]) for _ in range(T)] for i in range(S)]
    bh, bs = score(inst, best); bestRaw = raw(bh, bs)
    checkpoints = []
    pen = {}                       # GLS penalty: (i,j,k)->int
    kicks = 0
    total_iters = 0
    per = max(1, iters // restarts)
    # 可変パラメータ(#2 実測チューニング用。既定は Kotlin の現行値)
    gls_lambda = feats.get('gls_lambda', 200.0)
    gls_keep = feats.get('gls_keep', 80)
    gls_decay_every = feats.get('gls_decay_every', 256)
    nr_base = feats.get('nr_base', 0.18); nr_lo = feats.get('nr_lo', 0.6); nr_hi = feats.get('nr_hi_add', 1.2)
    dr_day = feats.get('dr_day', 0.20); dr_staff = feats.get('dr_staff', 0.10)   # ALNS destroy-repair 比率
    for r in range(restarts):
        # --- 非線形 restart 摂動 ---
        if r == 0:
            cur = [row[:] for row in best]
        else:
            cur = [row[:] for row in best]
            if feats.get('nonlinear_restart'):
                frac = r / (restarts - 1)
                strength = (nr_base * (nr_lo + nr_hi * (1 - frac) ** 2))
            else:
                strength = nr_base
            for i in range(S):
                for j in range(T):
                    if rng.random() < strength:
                        cur[i][j] = rng.choice(alw[i])
        ch, cs = score(inst, cur); curRaw = raw(ch, cs)
        lastImprove = total_iters
        lastHardImprove = total_iters          # HF63 ゲート相当: HARD が改善した最後の反復
        prevHard = ch
        # 振動の窓は予算に比例(Kotlin は数百万iterなので固定600/1200/800が極小割合。等価にするため per スケール)
        oscTrig = max(50, per // 10)
        oscPer = max(100, per // 5)
        oscOn = oscPer * 2 // 3
        for it in range(per):
            total_iters += 1
            # --- 候補手の選択: ALNS destroy-repair(day/staff) or 1セル変更 ---
            r_mv = rng.random(); mv = None; ci = cj = -1; old = -1
            if r_mv < dr_day:
                if feats.get("multiday", False):
                    # [multi-day destroy] 2日を同時に破壊修復(より大きな近傍)。両日のスナップショットを束ねる。
                    m1 = destroy_repair_day(inst, cur, rng, smart=feats.get("smart_repair", False), covo=feats.get("covo_aware", False))
                    m2 = destroy_repair_day(inst, cur, rng, smart=feats.get("smart_repair", False), covo=feats.get("covo_aware", False))
                    mv = ('multi', m1, m2)
                else:
                    mv = destroy_repair_day(inst, cur, rng, smart=feats.get("smart_repair", False), covo=feats.get("covo_aware", False), c41aware=feats.get("c41aware", False))
            elif r_mv < dr_day + dr_staff:
                mv = destroy_repair_staff(inst, cur, rng, smart=feats.get('smart_staff', False))
            else:
                i = rng.randrange(S); j = rng.randrange(T)
                if feats.get('smart_cell', False) and cur[i][j] != 0:
                    # [violations 相当] soft 違反セル(休以外)を soft 最小のシフトへ再割当(その職員の現状回数で評価)
                    cnt_i = [0] * K
                    for jj in range(T): cnt_i[cur[i][jj]] += 1
                    nw = min(alw[i], key=lambda k: staff_pen(inst, i, k, cnt_i[k] + 1) - staff_pen(inst, i, k, cnt_i[k]))
                else:
                    nw = rng.choice(alw[i])
                old = cur[i][j]; nw = nw
                if nw == old:
                    checkpoints.append(bestRaw); continue
                cur[i][j] = nw; ci, cj = i, j
            nh, ns = score(inst, cur); nsRaw = raw(nh, ns)
            temp = max(0.05, 1.0 - it / per)
            # --- GLS augment(1セル手のみ) ---
            aug = 0.0
            if feats.get('gls') and ci >= 0:
                aug = gls_lambda * (pen.get((ci, cj, cur[ci][cj]), 0) - pen.get((ci, cj, old), 0))
            # --- 戦略的振動(受理層 hard 割引・研究用トグル。Kotlin では 2.55 で revert 済) ---
            relax = 0.0
            if feats.get('oscillation'):
                stall = total_iters - lastImprove
                hardStuck = (ch > 0) and (total_iters - lastHardImprove) > oscTrig
                inWindow = (it % oscPer) < oscOn
                excursionOk = ch <= (bestRaw // 1_000_000) + 2
                if stall > oscTrig and inWindow and hardStuck and excursionOk:
                    relax = 0.9999
            def _revert():
                if mv is not None: revert_move(cur, mv)
                else: cur[ci][cj] = old
            # per-step 上限(±2 hard)
            if nsRaw > curRaw + 2_000_000:
                _revert(); checkpoints.append(bestRaw); continue
            if relax > 0:
                delta = (nh - ch) * (1 - relax) * 1_000_000 + (ns - cs) + aug
            else:
                delta = (nsRaw - curRaw) + aug
            accept = delta <= 0 or rng.random() < math.exp(-min(60, max(0.0, delta) / (200 * temp + 1e-9)))
            if accept:
                curRaw = nsRaw; ch, cs = nh, ns
                if ch < prevHard:
                    prevHard = ch; lastHardImprove = total_iters   # HARD 改善 → HF63 停滞カウンタをリセット
                if nsRaw < bestRaw:
                    bestRaw = nsRaw; best = [row[:] for row in cur]; lastImprove = total_iters
            else:
                _revert()
            # --- GLS penalize + aging(1セル手の停滞時) ---
            if feats.get('gls') and ci >= 0 and (total_iters - lastImprove) > 200 and it % 50 == 0 and ch > 0:
                pen[(ci, cj, cur[ci][cj])] = pen.get((ci, cj, cur[ci][cj]), 0) + 1
                kicks += 1
                if feats.get('gls_decay') and kicks % gls_decay_every == 0:
                    for key in list(pen.keys()):
                        v = pen[key] * gls_keep // 100
                        if v <= 0: del pen[key]
                        else: pen[key] = v
            checkpoints.append(bestRaw)
    auc = statistics.mean(checkpoints) if checkpoints else bestRaw
    return bestRaw, bh if False else (bestRaw // 1_000_000), auc

# ---------------- A/B ランナー ----------------
def run_variant(name, feats, instances, seeds, iters):
    finals = []; hards = []; aucs = []
    for inst in instances:
        for sd in seeds:
            fr, hh, auc = optimize(inst, sd, iters, feats)
            finals.append(fr % 1_000_000 if fr // 1_000_000 == 0 else fr)  # feasible は soft, infeasible は raw
            hards.append(fr // 1_000_000)
            aucs.append(auc)
    return name, statistics.mean(finals), statistics.mean(hards), statistics.mean(aucs)

def main():
    import sys
    if "--real" in sys.argv:
        inst = load_golden("app/src/test/resources/golden_state.json")
        rangeN = sum(1 for i in range(inst['S']) for k in range(inst['K']) if inst['lo'][i][k] or inst['hi'][i][k] != inst['T'])
        aptN = sum(1 for i in range(inst['S']) for k in range(inst['K']) if inst['apt'][i][k] >= 0)
        print(f"=== REAL golden_state (S={inst['S']} T={inst['T']} K={inst['K']}, staffRange={rangeN} apt={aptN} use2={inst['use2']}, +ALNS destroy-repair) ===")
        print(f"  ※ final(最終品質)=製品の主指標 / AUC(速度)=参考。base=+repair(day, 2.57実装済) 比。")
        seeds = list(range(8)); iters = 10000
        # 全機構を final 品質で網羅再評価(repair の上での寄与)。R=repair(day)
        R = {"smart_repair": True}
        rv = [
            ("random repair", {}),
            ("R: repair(day) [2.57]", R),
            ("R+staff", {**R, "smart_staff": True}),
            ("R+viol", {**R, "smart_cell": True}),
            ("R+staff+viol", {**R, "smart_staff": True, "smart_cell": True}),
            ("R+sv+multiday", {**R, "smart_staff": True, "smart_cell": True, "multiday": True}),
        ]
        rows = [run_variant(n, f, [inst], seeds, iters) for n, f in rv]
        ref_final = rows[1][1]   # +repair(day) を基準
        print(f"{'variant':<24} {'final(soft)':>12} {'vs R':>8} {'hard':>6} {'AUC':>10}")
        for n, mf, mh, auc in rows:
            d = (mf - ref_final) / ref_final * 100 if ref_final else 0
            print(f"{n:<24} {mf:>12.1f} {d:>+7.1f}% {mh:>6.2f} {auc:>10.0f}")
        return
    S, T, K = 8, 21, 5
    # borderline(tight=0.35: 越える価値のある壁) と over(tight=0.7: 過拘束) の2系
    inst_border = [make_instance(S, T, K, sd, 0.35) for sd in range(4)]
    inst_over = [make_instance(S, T, K, sd + 100, 0.7) for sd in range(4)]
    seeds = list(range(6))
    iters = 6000
    variants = [
        ("baseline(all random)", {}),
        ("+repair(day)", {"smart_repair": True}),
        ("+repair+staff", {"smart_repair": True, "smart_staff": True}),
        ("+repair+viol", {"smart_repair": True, "smart_cell": True}),
        ("+repair+staff+viol", {"smart_repair": True, "smart_staff": True, "smart_cell": True}),
        ("+rsv+c41aware", {"smart_repair": True, "smart_staff": True, "smart_cell": True, "c41aware": True}),
    ]
    # 真に過拘束で destroy-repair でも hard>0 が残る tier(=脱出が効くなら効く領域)
    inst_hard = [make_instance(S, T, K, sd + 200, 1.0) for sd in range(4)]
    for label, insts in [("BORDERLINE(tight=0.35)", inst_border),
                         ("OVER-CONSTRAINED(tight=0.7)", inst_over),
                         ("HARD-INFEASIBLE(tight=1.0)", inst_hard)]:
        print(f"\n=== {label}  (S={S} T={T} K={K}, {len(insts)}inst x {len(seeds)}seed x {iters}iter, +ALNS destroy-repair) ===")
        print(f"{'variant':<20} {'mean_final':>12} {'mean_hard':>10} {'mean_AUC':>12}   (低いほど良い)")
        rows = [run_variant(n, f, insts, seeds, iters) for n, f in variants]
        base_auc = rows[0][3]
        for n, mf, mh, auc in rows:
            d = (auc - base_auc) / base_auc * 100 if base_auc else 0
            print(f"{n:<20} {mf:>12.1f} {mh:>10.2f} {auc:>12.0f}   ({d:+.1f}% vs base)")

    # ---- #2 実測パラメータスイープ(GLS/nonlinear_restart を hard tier で実測。--sweep で実行) ----
    import sys
    if "--sweep" not in sys.argv:
        return
    print(f"\n=== PARAM SWEEP on HARD-INFEASIBLE (3seed x 4000iter) ===")
    print(f"{'config':<28} {'mean_final':>12} {'mean_hard':>10} {'mean_AUC':>12}   (低いほど良い)")
    sw_seeds = list(range(3)); sw_iters = 4000
    sweep = [
        ("baseline", {}),
        ("gls keep70", {"gls": True, "gls_decay": True, "gls_keep": 70}),
        ("gls keep90", {"gls": True, "gls_decay": True, "gls_keep": 90}),
        ("gls decay128", {"gls": True, "gls_decay": True, "gls_decay_every": 128}),
        ("gls lambda100", {"gls": True, "gls_decay": True, "gls_lambda": 100.0}),
        ("nr hi0.8", {"nonlinear_restart": True, "nr_hi_add": 0.8}),
        ("nr hi2.0", {"nonlinear_restart": True, "nr_hi_add": 2.0}),
        ("nr base0.30", {"nonlinear_restart": True, "nr_base": 0.30}),
    ]
    base_auc = None
    for n, f in sweep:
        _, mf, mh, auc = run_variant(n, f, inst_hard, sw_seeds, sw_iters)
        if base_auc is None: base_auc = auc
        d = (auc - base_auc) / base_auc * 100 if base_auc else 0
        print(f"{n:<28} {mf:>12.1f} {mh:>10.2f} {auc:>12.0f}   ({d:+.1f}% vs base)")

if __name__ == "__main__":
    main()
