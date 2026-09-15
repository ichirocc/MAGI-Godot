package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random

/**
 * 被覆保存の同日/曜日間セル交換2パス。[V6HotfixPasses] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * - [applyCyclicSwapPolish]：同日内の k=2 スワップ／k=3 ローテーションで被覆を保ったまま
 *   連続規則(c3/c3m)等を研磨する（日内Hungarianが触れない領域を狙う）。
 * - [applyWeeklyRebalancePolish]：2職員×2日の長方形交換で曜日間の偏り(weekly, L1偏差)を
 *   研磨する（同日交換だけでは動かせない「どの曜日に何が入るか」を動かす）。
 *
 * `CyclicSwapResult` は [V6HotfixPasses] に残置される共有返り型のため、ここからは完全修飾で
 * 構築・参照する。
 */
internal object CyclicSwapWeeklyPolish {
    /**
     * [ソフト研磨・T2] 被覆を保つ循環交換（k=2,3）研磨。各日の (日,シフト) 人数を保ったまま、職員の
     * シフトを **2職員スワップ / 3職員ローテーション** で組み替える。被覆は不変＝HARD充足を維持し、
     * 連続規則(c3/c3m) や希望・回数の相互作用を**実目的関数(UnifiedViolationChecker)で評価**して
     * 改善時のみ採用（keep-best＝退化なし）。日内Hungarian(range/apt最適)が触れない c3 を狙う。
     * 注: 提案サイクルは必ず実チェックで検証してから採用するため、サイクル生成が不完全でも悪化しない。
     */
    fun applyCyclicSwapPolish(
        state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 4,
        /** [3.511.2/測定中] k=3 の先の循環人数の上限。既定 3=k=2,3 の全列挙のみ（挙動不変）。4/5 にすると
         *  k=4,5 の循環を全列挙でなくランダム試行（[kTrialsPerDay]）で追加する（backlog #12(b)/#13(e)）。
         *  全列挙は S=30 で k=5 なら 1 日あたり C(30,5)=142,506 通り×4 方向と組合せ爆発するため避ける。 */
        maxK: Int = 3,
        /** [3.511.2/測定中] maxK>=4 のときだけ使う、1 日・1 k 値あたりのランダム試行数上限。 */
        kTrialsPerDay: Int = 20,
        seed: Long = 0L,
        shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        // [監査で発見・3.270.0] p.wish[i][j]<0 は「希望が一切ない」判定で、実現不能な希望
        //   (canDo(i,wish)==false)まで動かせないと誤判定していた（3.183.0 LightMirrorOptimizer と
        //   同型のバグ）。実現不能な希望はpref計上上も定数=動かして良い＝canDoガード込みの
        //   wishLocked が正しい判定。安全側（isBetter/checkerが最終ゲート）で候補が広がるのみ。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        val rng = if (maxK >= 4) Random(seed) else null
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            for (j in 0 until p.T) {
                if (shouldStop()) break
                // --- k=2: 2職員スワップ（同日・被覆不変）---
                for (a in 0 until p.S) {
                    // [監査(未レビュー領域再監査)] HF66(2.65.0)/BlockRotationPolish(3.84.0)と同型の予算超過対策。
                    //   旧: 日(j)ループ先頭のみで確認していたため、1日分のO(S^2)スキャンが締切後も走り切っていた。
                    if (shouldStop()) break
                    if (!movable(a, j)) continue
                    for (b in a + 1 until p.S) {
                        if (!movable(b, j)) continue
                        val sa = work[a][j]; val sb = work[b][j]
                        if (sa == sb || !p.mayPlace(a, sb) || !p.mayPlace(b, sa)) continue
                        // [厳密ピン保護] 異なるシフト同士の同日交換はa/bの自身のシフト回数を変えるため、
                        //   staffRange厳密ピン(lo==hi)を新たに崩す候補は不採用にする（keep-best/重み不変）。
                        val workBeforeSwap2 = work.copy2D()
                        work[a][j] = sb; work[b][j] = sa
                        val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                        if (adoptionGate(p, workBeforeSwap2, work, rep, bestRep, pinBlocks).accepted) { bestRep = rep; applied++; improved = true }
                        else { work[a][j] = sa; work[b][j] = sb }
                    }
                }
                // --- k=3: 3職員ローテーション（同日・被覆不変）---
                for (a in 0 until p.S) {
                    if (shouldStop()) break
                    if (!movable(a, j)) continue
                    for (b in a + 1 until p.S) {
                        if (!movable(b, j)) continue
                        for (c in b + 1 until p.S) {
                            if (!movable(c, j)) continue
                            if (shouldStop()) break
                            val sa = work[a][j]; val sb = work[b][j]; val sc = work[c][j]
                            if (sa == sb && sb == sc) continue
                            // a←sb, b←sc, c←sa（feasibleなら適用→評価→不採用なら巻き戻し）
                            if (p.mayPlace(a, sb) && p.mayPlace(b, sc) && p.mayPlace(c, sa)) {
                                val workBeforeRotate3 = work.copy2D()
                                work[a][j] = sb; work[b][j] = sc; work[c][j] = sa
                                val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                                if (adoptionGate(p, workBeforeRotate3, work, rep, bestRep, pinBlocks).accepted) { bestRep = rep; applied++; improved = true; continue }
                                work[a][j] = sa; work[b][j] = sb; work[c][j] = sc
                            }
                        }
                    }
                }
                // --- k=4,5: N職員ローテーション（同日・被覆不変、全列挙は組合せ爆発するのでランダム試行） ---
                if (rng != null) for (k in 4..maxK) {
                    if (shouldStop()) break
                    var trial = 0
                    while (trial < kTrialsPerDay) {
                        trial++
                        if (shouldStop()) break
                        val idx = IntArray(k) { rng.nextInt(p.S) }
                        if (idx.toHashSet().size < k || idx.any { !movable(it, j) }) continue
                        val vals = IntArray(k) { work[idx[it]][j] }
                        if (vals.toHashSet().size < k) continue
                        if ((0 until k).any { !p.mayPlace(idx[it], vals[(it + 1) % k]) }) continue
                        val workBeforeRotateN = work.copy2D()
                        for (t in 0 until k) work[idx[t]][j] = vals[(t + 1) % k]
                        val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                        if (adoptionGate(p, workBeforeRotateN, work, rep, bestRep, pinBlocks).accepted) { bestRep = rep; applied++; improved = true }
                        else for (t in 0 until k) work[idx[t]][j] = vals[t]
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "CyclicSwap",
            message = "循環交換(k=2,3${if (maxK >= 4) ",4.." + maxK else ""})研磨: total ${before.total}->${bestRep.total} 採用${applied}回"))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }


    // [3.317.0] 分散指標ベースの平準化2パス（applyGroupShiftEqualizePolish / applyWeeklyEqualizePolish）は
    //   ここにあったが撤去した。目的関数の fair/weekly は 3.72.0 以降 **L1偏差**で評価されるのに、この2パスは
    //   **分散**を下げる手を採っており、指標が目的関数と一致していなかった（3.84.0 で「目的関数外の整え＝冗長」
    //   と記録したまま未計測だった）。実データ3件で ablation を取り、採用0回・分散指標も1ミリも動かず・
    //   最終盤面も変わらないことを確認して撤去。L1 ベースの後継が役割を完全に代替している:
    //   fair → `applyFairPolish`(3.235.0) ／ weekly → `applyWeeklyRebalancePolish`(3.197.0 長方形交換)＋
    //   `applyAlternatingSoftPolish`(3.198.0 が weekly の限界費用を Hungarian の費用に含む)。


    /**
     * [ソフト研磨・weekly（7日周期のシフト平準化）＝長方形交換] weekly は「職員が特定の曜日にばかり同じシフトに
     * 入る」偏りで、L1偏差（`weeklyDevOfBucket`＝そのシフトの曜日別回数の round(回数/7) からの偏差和）で評価される。
     * **同日2者スワップ（CyclicSwap / equalize 系）は同じ日の中で入れ替えるだけなので、どの曜日に何が入るかを
     * 動かせない**。これが「weekly の研磨ができていない」実害の根本（実機ログで weekly＝SOFT 残差の最大級）。
     *
     * そこで **被覆保存の 2職員×2日 長方形交換** を導入する: 職員 i がシフト x について「過剰曜日の日 j1 で x・
     * 過少曜日の日 j2 で別のシフト y」、相手 i' が「j1 で別のシフト z・j2 で x」のとき、両者の j1/j2 を丸ごと
     * 入替える（i: j1→z / j2→x、i': j1→x / j2→y）。各日の各シフト人数は保存される（j1 の x は i→i'、j2 の x は
     * i'→i へ移るだけ）ため covU/covO・群レンジ・pref は不変で、i の x が過剰曜日→過少曜日へ移動して weekly が
     * 下がる。fair（群内シフト回数）や low/high/apt/c2 など per-staff 族も副次的に動く。
     * [3.345.0] 休を通常のシフト種として扱う定義に合わせ、x/y/z を勤務・休で区別しない（旧: x=勤務・y=z=休 の
     * 特殊形のみ＝休だけを「空き」とみなしていた）。旧形は新形の部分集合なので探索範囲は広がるだけ。
     * **採否は実目的関数 isBetter のみ**（hard→weighted→total、total は weekly/fair を含む）＝退化なし（keep-best）。
     * dev>0 の (職員,シフト) のみ起点＋first-improvement で空探索は即終了。変更セルは wish 固定なら不動
     * （4セルとも movable ガード）。covO/c42/c2 など per-day 族は同日 CyclicSwap（isBetter）が既に最適に研磨済みの
     * ため本パスの対象外（2.49.0 の「専用パスは冗長」の結論を踏襲）。
     */
    fun applyWeeklyRebalancePolish(
        state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 2, shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        fun weekdayOf(j: Int) = (p.dow0 + j) % 7
        // [3.345.0] 職員×シフト×曜日のカウント（休も1シフト）。
        fun wdBucket(i: Int): Array<IntArray> {
            val wd = Array(p.K) { IntArray(7) }
            for (j in 0 until p.T) { val k = work[i][j]; if (k in 0 until p.K) wd[k][weekdayOf(j)]++ }
            return wd
        }
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            for (i in 0 until p.S) {
                if (shouldStop()) break
                val wdAll = wdBucket(i)
                // [3.475.0/論理監査] 旧: `if (improved || shouldStop()) break`＝pass 全体の旗で抜けていたため、
                //   最初の採用のあと**残りの職員の x ループが全部飛ばされ**、パスあたり最大1手しか採らなかった
                //   （maxPasses=2 で計2手。3.345.0 の消去実験「採用1回」はこの上限の現れ）。wdAll が古くなるのは
                //   採用した職員 i だけなので、抜けるのは**その職員の x ループだけ**にする。
                var staffImproved = false
                for (x in 0 until p.K) {
                    if (staffImproved || shouldStop()) break
                    val wd = wdAll[x]
                    if (weeklyDevOfBucket(wd) == 0) continue
                    var sum = 0; for (w in wd) sum += w
                    val tgt = Math.round(sum.toDouble() / 7.0).toInt()
                    // シフト x が最も過剰な曜日と最も過少な曜日を1つずつ狙う。
                    var wOver = -1; var wUnder = -1; var maxOver = 0; var maxUnder = 0
                    for (w in 0 until 7) {
                        if (wd[w] - tgt > maxOver) { maxOver = wd[w] - tgt; wOver = w }
                        if (tgt - wd[w] > maxUnder) { maxUnder = tgt - wd[w]; wUnder = w }
                    }
                    if (wOver < 0 || wUnder < 0) continue
                    // i が過剰曜日に x に入っている日 / 過少曜日に x 以外に入っている日（どちらも movable）。
                    val overDays = (0 until p.T).filter { weekdayOf(it) == wOver && movable(i, it) && work[i][it] == x }
                    val underDays = (0 until p.T).filter { weekdayOf(it) == wUnder && movable(i, it) && work[i][it] != x && work[i][it] in 0 until p.K }
                    var done = false
                    for (j1 in overDays) {
                        if (done || shouldStop()) break
                        for (j2 in underDays) {
                            // [レビュー#6 3.213.0] 内側ループにも締切確認（各候補がフル check を伴うため、
                            //   キャンセル後のバーストを1候補以内に抑える。HF66=2.65.0/BlockRotation=3.84.0 と同方針）。
                            if (done || shouldStop()) break
                            val y = work[i][j2]
                            for (ip in 0 until p.S) {
                                if (done || shouldStop()) break
                                if (ip == i) continue
                                // 相手 i' は j1 で x 以外(z)・j2 で x、両日 movable。被覆保存には i←z(j1), i'←y(j2) が担当可であること。
                                if (!movable(ip, j1) || !movable(ip, j2)) continue
                                if (work[ip][j2] != x) continue
                                val z = work[ip][j1]
                                if (z == x || z !in 0 until p.K) continue
                                if (!p.mayPlace(i, z) || !p.mayPlace(ip, y)) continue
                                // 長方形交換を適用（被覆保存）→ フル評価 → 改善時のみ採用、不採用なら完全巻き戻し。
                                // [監査で発見・3.270.0] isBetter は hard→weightedScore→total の辞書式のため、
                                //   raw total が改善してもweightedScoreが悪化する組合せ(重い厳密ピン破りを軽い
                                //   weekly改善が数の上で上回る)がありうる。同型の全パスに既に適用済みの
                                //   exactPinRegression ガードをここにも追加（3.256.0の retrofit 漏れ）。
                                val workBeforeRect = work.copy2D()
                                work[i][j1] = z; work[i][j2] = x; work[ip][j1] = x; work[ip][j2] = y
                                val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                                if (adoptionGate(p, workBeforeRect, work, rep, bestRep, pinBlocks).accepted) { bestRep = rep; applied++; improved = true; staffImproved = true; done = true; break }
                                work[i][j1] = x; work[i][j2] = y; work[ip][j1] = z; work[ip][j2] = x
                            }
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "WeeklyRebalance",
            message = "曜日平準化(長方形交換): total ${before.total}->${bestRep.total} 採用${applied}回"))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }


}
