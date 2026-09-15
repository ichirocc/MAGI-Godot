package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random

internal fun inDeficientC1Window(p: Problem, work: Array<IntArray>, i: Int, x: Int, d: Int, n: Int, j: Int): Boolean {
    if (d <= 0) return false
    var w = maxOf(0, j - d + 1)
    val wEnd = minOf(j, p.T - d)
    while (w <= wEnd) {
        var z = 0
        for (l in 0 until d) if (work[i][w + l] == x) z++
        if (z < n) return true
        w++
    }
    return false
}

/** 職員 i の c1 fire 総数（全 cons1・canDo ガード。checker/MirrorCore と同一のスライド窓意味論）。 */

internal fun c1RowFires(p: Problem, work: Array<IntArray>, i: Int): Int {
    var fires = 0
    for (c in p.cons1) {
        val x = c.shiftIdx; val d = c.day1; val n = c.day2
        if (x !in 0 until p.K || d <= 0 || !p.canDo(i, x)) continue
        var w = 0
        while (w <= p.T - d) {
            var z = 0
            for (l in 0 until d) if (work[i][w + l] == x) z++
            if (z < n) fires++
            w++
        }
    }
    return fires
}


/**
 * C1（窓の要件）専用の修復・研磨パス群。[V6HotfixPasses] から抽出（責務別の物理分割＝
 * AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * - [applyC1ExactWindowRepair] / [applyC1IndexChainRepair]：呼出元は [C1RepairOperators] のみ。
 * - [applyC1WindowPolish]：手A/R1/R2/R3/B（同日交換・鏡像長方形・自己2日swap・全ペア再配置・
 *   直接移動+玉突き）を束ねる本体パス。
 * - [applyC1BeamPolish]：多職員協調ビームサーチ（C1JointLNS 等では届かない候補を拾う）。
 *
 * [V6HotfixPasses.CyclicSwapResult] を戻り値型として使う（元のオブジェクトに残置・完全修飾で参照）。
 */
internal object C1WindowPolish {
    /**
     * [A1+A2+A3, 3.273.0] C1厳密窓修復パス。C1RepairAnalysis の窓スコープ厳密探索（coverage保存
     * permutation の分枝限定）で、局所/ビーム系が届かない多日多職員連動手を拾う。
     *  - A1 解析駆動ディスパッチ: 「exhaustive で min==baseline」と証明されたスパンは、その (焦点職員,
     *    シフト, スパン内容ハッシュ) を memo し、内容が変わらない限り二度と厳密探索しない（死に候補の刈込）。
     *  - 採否は必ず本物の checker + isBetter + exactPinRegression（keep-best＝退化不能）。厳密探索が返す
     *    patch はあくまで候補（node予算超過時は best-effort＝多様化として安全）。
     */
    fun applyC1ExactWindowRepair(
        state: MagiState,
        schedule: Array<IntArray>,
        cfg: C1RepairAnalysis.Config = C1RepairAnalysis.Config(),
        shouldStop: () -> Boolean = { false },
        // [C1 重複窓の連結成分化/測定中] 既定 false = 旧経路そのまま（1件の違反を起点にパディング）。
        //   true にすると analyze() の代わりに components() で近接・重複窓を束ね、solveComponent へ渡す。
        useComponents: Boolean = false,
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        var solved = 0
        var provenWalls = 0
        if (p.cons1.isEmpty() || (before.breakdown["c1"] ?: 0) == 0) {
            return V6HotfixPasses.CyclicSwapResult(work, before.total, before.total, 0,
                listOf(MirrorLog(tag = "C1ExactRepair", message = "c1対象なし=スキップ")))
        }
        // [A1] 証明済み「解消不能スパン」のmemo（キー=焦点職員,シフト,スパン内容ハッシュ）。
        val deadSpans = HashSet<String>()
        val rejectCulprits = RejectCulpritStats()
        fun spanKey(staff: Int, shift: Int, days: List<Int>): String {
            val sb = StringBuilder().append(staff).append('|').append(shift).append('|')
            for (d in days) for (i in 0 until p.S) sb.append(work[i][d]).append(',')
            return sb.toString()
        }
        // [C1 重複窓の連結成分化/測定中] components() 経路用（単一 shift を持たないため comp.start/end で束ねる）。
        fun compSpanKey(staff: Int, start: Int, end: Int, days: List<Int>): String {
            val sb = StringBuilder().append(staff).append('|').append(start).append('|').append(end).append('|')
            for (d in days) for (i in 0 until p.S) sb.append(work[i][d]).append(',')
            return sb.toString()
        }
        // 焦点ごとに1回だけ厳密探索する。
        // [3.314.0] キーを (職員, シフト) → **(職員, シフト, スパン開始)** へ。旧実装は同一職員・同一
        //   シフトなら最初の1窓しか探索せず、コメントの「多数窓は1スパンに束ねられる」はスパン幅
        //   （maxWindowDays）に収まる窓にしか当てはまらない。**それより離れた別の C1 塊が同一対象と
        //   みなされ、探索されないままスキップ**されていた。同一スパンの重複は下の deadSpans（スパン
        //   内容ハッシュ）が引き続き弾き、走査全体は先頭の shouldStop() で予算内に収まる。
        val seenFocus = HashSet<String>()
        // [C1 重複窓の連結成分化/測定中] 探索結果(patch)の採否だけを共通化する（analyze/components どちら
        //   経由でも adopt-or-reject の意味論を完全に揃える＝分岐で挙動が分かれるのはループの起点だけ）。
        fun applyResult(res: C1RepairAnalysis.ExactResult, key: String) {
            solved++
            if (res.patch == null) {
                // 改善候補なし。exhaustive なら「coverage保存では解消不能」と証明済み＝memo。
                if (res.exhaustive) { deadSpans.add(key); provenWalls++ }
                return
            }
            val workBefore = work.copy2D()
            for (op in res.patch) work[op[0]][op[1]] = op[2]
            val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
            // [3.321.0] このパスだけ却下理由をまったく残しておらず、ログは applied==0 のとき
            //   一律「頭打ち=改善手なし」としか言えなかった（patch が出て却下されても同じ文言）。
            //   他の研磨パスと同じ RejectCulpritStats で分類する。
            val pinBad = exactPinRegression(p, workBefore, work)
            if (pinBad && betterReport(rep, bestRep)) pinBlocks.record(p, workBefore, work)
            if (betterReport(rep, bestRep) && !pinBad) {
                bestRep = rep; applied++
            } else {
                rejectCulprits.record(rep, bestRep, pinBad)
                for (mi in work.indices) work[mi] = workBefore[mi]
            }
        }
        if (useComponents) {
            // [C1 重複窓の連結成分化/測定中] 重複除去キーは comp.staff/comp.start/comp.end（analyze 経路の
            //   staff/shift/startD の代わり）。components() は同一 staff 内で重ならない区間しか作らないため
            //   本来は不要な保険だが、他パスと同じ「起点ごとに1回」の形を揃えておく。
            val seenComp = HashSet<String>()
            for (comp in C1RepairAnalysis.components(p, work, cfg)) {
                if (shouldStop()) break
                if (!seenComp.add("${comp.staff}|${comp.start}|${comp.end}")) continue
                val span = minOf(cfg.maxWindowDays, p.T)
                val startD = comp.start.coerceAtMost(p.T - span).coerceAtLeast(0)
                val days = (startD until startD + span).toList()
                val key = compSpanKey(comp.staff, comp.start, comp.end, days)
                if (key in deadSpans) continue
                applyResult(C1RepairAnalysis.solveComponent(p, work, comp, cfg), key)
            }
        } else {
            for (v in C1RepairAnalysis.analyze(p, work)) {
                if (shouldStop()) break
                val span = minOf(cfg.maxWindowDays, p.T)
                val startD = v.start.coerceAtMost(p.T - span).coerceAtLeast(0)
                if (!seenFocus.add("${v.staff}|${v.shift}|$startD")) continue
                val days = (startD until startD + span).toList()
                val key = spanKey(v.staff, v.shift, days)
                if (key in deadSpans) continue
                applyResult(C1RepairAnalysis.solveWindow(p, work, v, cfg), key)
            }
        }
        val c1b = before.breakdown["c1"] ?: 0
        val c1a = bestRep.breakdown["c1"] ?: 0
        val logs = listOf(MirrorLog(tag = "C1ExactRepair",
            message = "期間要件(c1)厳密窓修復: c1 $c1b->$c1a / total ${before.total}->${bestRep.total} " +
                "HARD ${before.hard}->${bestRep.hard} 採用${applied}回 探索${solved}回 証明済み壁${provenWalls}件" +
                rejectCulprits.summary() +
                // [3.321.0] 旧: applied==0 を一律「改善手なし」としていたが、patch が出て却下された場合と
                //   patch がそもそも出ない場合を区別できなかった。前者は上の内訳が語るのでここは後者だけ。
                (if (applied == 0 && c1b > 0 && rejectCulprits.rejected == 0) " [頭打ち=候補が出ない]" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks)
    }


    /**
     * [C1 Index-driven Chain Repair / 3.276.0] C1RepairIndex/C1DeltaPrefilter を実際に駆動する新規C1修復
     * オペレータ（図の Index→Prefilter→Operators→checker 経路を end-to-end に通す）。
     *
     *  1. `C1RepairIndex.build` で不足窓を索引化（不足の重い窓から処理）。
     *  2. 窓内の候補日を `C1DeltaPrefilter.c1Delta`（exact net c1 delta）昇順で並べ、`screenCell` が NEUTRAL の候補だけ試す
     *     （無変化/groupViol/pref破り/c3n は checker が確実に却下＝事前に落とす）。
     *  3. 候補日を不足シフトへ直接移動。旧シフトを抜いて covU 穴が空くなら `findCovUChain`（exclude=本人）の
     *     玉突き連鎖で埋め直す（手B と同型）。
     *  4. 採否は必ず本物の `UnifiedViolationChecker` + `isBetter`（hard→weighted→total）+ `exactPinRegression`
     *     （3.256.0の厳密ピン保護）＝keep-best・退化不能。
     *
     * [位置づけ・正直な限界] 生成する手は既存の手B/beam/exact と重複する（keep-best で無害）。本オペレータの
     *   主眼は「index駆動の候補生成＋prefilter選別」という図の経路を load-bearing にすること。実C1削減の
     *   純増は限定的（残差は3.263.0で確認した構造的壁が支配的）。既存オペレータには一切触れない＝退化不能。
     */
    fun applyC1IndexChainRepair(
        state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 2,
        shouldStop: () -> Boolean = { false }, seed: Long = 0x1C1D2L,
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        if (p.cons1.isEmpty() || (before.breakdown["c1"] ?: 0) == 0) {
            return V6HotfixPasses.CyclicSwapResult(work, before.total, before.total, 0,
                listOf(MirrorLog(tag = "C1IndexRepair", message = "c1対象なし=スキップ")))
        }
        val rng = Random(seed)
        var bestRep = before
        var applied = 0
        var chainUsed = 0
        var screened = 0
        // [3.279.0/外部レビューC1-08] 旧: pass 開始時の index を採用後も走査し続け、解消済み窓の再処理と
        //   「採用で新たに生じた窓は次 pass まで不可視」の両方が起きていた（maxPasses=2 の有限 pass で
        //   改善を取りこぼす）。1手採用するたびに窓ループを抜け、最新盤面から Index を再構築する。
        //   終了保証は isBetter の厳密改善（hard→weighted→total 辞書式で単調減少）＋採用上限の安全弁。
        val maxAdoptions = maxPasses * 32
        var capHit = false
        while (!shouldStop()) {
            // [3.279.1/レビューnit] 採用上限は安全弁＝到達を黙って打ち切らずログへ明示する（silent cap 禁止）。
            if (applied >= maxAdoptions) { capHit = true; break }
            val index = C1RepairIndex.build(p, work)
            if (!index.hasActionable) break
            var adopted = false
            windowLoop@ for (w in index.windows.sortedByDescending { it.deficit }) {
                if (shouldStop()) break
                val staff = w.staff; val shift = w.shift
                val cands = (w.start until w.start + w.windowDays)
                    .filter { d ->
                        // [厳密ピン保護/3.522.0] 希望固定日は候補から除外。screenCellのdelta合算は
                        // pref+1が他族-1と相殺してNEUTRALになり得る＝checker委任のadoptionGateも
                        // wishLockedを見ないため、生成側でここを塞がないと希望固定セルが動きうる。
                        if (p.wishLocked(staff, d)) { if (work[staff][d] != shift) screened++; return@filter false }
                        val neutral = C1DeltaPrefilter.screenCell(p, work, staff, d, shift) == C1DeltaPrefilter.Verdict.NEUTRAL
                        if (!neutral && work[staff][d] != shift) screened++
                        neutral
                    }
                    // [3.277.0] 順位付けを exact net c1 delta へ（旧: index.expectedGain=gainのみの近似）。
                    //   c1Delta は旧シフト除去で別窓を割る loss も勘定＝自己破壊候補を後回しにする賢い順序。
                    //   負=改善なので昇順（最も改善する候補を先に試す）。順位のみ＝keep-best採否は不変。
                    .sortedBy { d -> C1DeltaPrefilter.c1Delta(p, work, staff, d, shift) }
                for (d in cands) {
                    if (shouldStop()) break
                    val old = work[staff][d]
                    val trial = work.copy2D()
                    trial[staff][d] = shift
                    // (a) 直接移動のみで改善（旧シフトに余裕がある場合）。
                    val repDirect = UnifiedViolationChecker.check(state, trial, quantitativeRangeEval)
                    if (adoptionGate(p, work, trial, repDirect, bestRep, pinBlocks).accepted) {
                        work = trial; bestRep = repDirect; applied++; adopted = true; break@windowLoop
                    }
                    // (b) 旧シフトを抜いて covU 穴が空くなら玉突き連鎖で埋め直す（exclude=本人で自己選択防止）。
                    val cntOldAfter = (0 until p.S).count { trial[it][d] == old }
                    if (old in 0 until p.K && p.covUCell(old, d, cntOldAfter) > 0) {
                        val chain = findCovUChain(p, trial, old, d, rng, exclude = staff)
                        if (chain != null) {
                            for (mv in chain) trial[mv[0]][mv[1]] = mv[2]
                            val repChain = UnifiedViolationChecker.check(state, trial, quantitativeRangeEval)
                            if (adoptionGate(p, work, trial, repChain, bestRep, pinBlocks).accepted) {
                                work = trial; bestRep = repChain; applied++; chainUsed++; adopted = true; break@windowLoop
                            }
                        }
                    }
                }
            }
            if (!adopted) break
        }
        val c1After = bestRep.breakdown["c1"] ?: 0
        return V6HotfixPasses.CyclicSwapResult(
            work, before.total, bestRep.total, applied,
            // [3.279.1] screened は Index 再構築のたび同一候補を再判定し得る＝「延べ」件数（重複計上あり）。
            listOf(MirrorLog(tag = "C1IndexRepair",
                message = "index駆動C1修復: c1 ${before.breakdown["c1"] ?: 0}->$c1After 採用$applied(連鎖$chainUsed) " +
                    "prefilter除外(延べ)$screened" + (if (capHit) " 採用上限${maxAdoptions}到達=打ち切り" else ""))),
            observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks,
        )
    }


    /**
     * [ソフト研磨・C1] 期間要件 cons1（D日窓にシフトXをN回以上・職員ごと）の研磨。
     * c1不足の (職員 i, 窓) を見つけ、その窓内で i が X でない日 j に対し、その日に X をしている提供者 i' と
     * **同日スワップ**（i←X, i'←iの旧シフト＝被覆不変・HARD維持）して i の X を増やす。実目的関数で評価し
     * 改善時のみ採用（keep-best＝退化なし）。汎用循環交換と違い**c1不足の窓に的を絞る**ので c1 を効率的に削る。
     * [E11/多人数ブロック移動を反映] 同日スワップの直接相手 i' が見つからない/不採用のときは諦めず、
     * i を X へ直接動かし、空いた旧シフト a の穴を `findCovUChain`（covU の玉突き連鎖）と同じ機構で
     * 埋め直す（a に need1 が無い/余裕があるなら連鎖不要でそのまま採用判定）。i の移動＋連鎖手をまとめて
     * 1候補として実目的関数で評価し、改善時のみ採用（不採用時は連鎖手も含め正しく全巻き戻し）。
     *
     * [C1研磨アルゴリズムの再設計/回数保存移設の追加] 手A(同日スワップ)/手B(直接移動+連鎖)はどちらも
     * 「i の X 回数を+1する」count-changing 手しか生成できない。golden_state の残差解剖(Python実測)では
     * c1=115 fires のうち relocation-only=48（休 fires の80%が個人別回数の下限=上限で固定された職員由来）
     * は、X追加が low/high(90/45)>c1(30×窓数)で必ず isBetter に棄却され、**i自身のXを余剰位置→不足窓へ
     * 移す回数保存の移設**だけが唯一の改善手と判明（行内2日swapの貪欲シムで c1 115→62, -46%）。
     * [2026-09-10] high 45→25(HF77) で単窓局面(窓数=1)では high(25)<c1(30) となり上記の前提は成立しなく
     * なった（詳細・経緯は docs/history/3.4xx.md 3.516.0）。手R1/手R2 自体のロジックは無変更。
     * 現行手A/Bにこの移設プリミティブが無い欠落を埋めるため、手A(同日交換)の直後・手B(直接移動)の前に
     * 保存性の強い順で2手を追加する:
     *   手R1=鏡像長方形（i=[X@j1,b@j]↔i'=[b@j1,X@j]の4セル交換）: 両職員の回数と日別人数が両方保存
     *        （groupViol/pref/low/high/apt/c2/covU/covO/c41系まで構造的不変）＝isBetterはc1/c3系/weekly
     *        だけの勝負になり採用されやすい最も安全な移設。
     *   手R2=自己2日swap（i の X@j1 ↔ b@j）: i の回数は保存（low/high/apt/c2/pref/groupViol不変）だが
     *        日別人数が変わるため、離脱側2箇所を p.covUCell（source of truth）で事前除外してから適用。
     * どちらも c3n(HARD) は p.makesForbiddenRun で事前枝刈り（見逃しても isBetter が最終拒否＝安全側）。
     * 採否は既存と同じ betterReport(hard→weighted→total) の keep-best のみ＝退化不能・HF77非該当（重み不変）。
     * add-fixable（追加が唯一の解の局面）は既存手A/Bの担当のまま＝手クラスが互いに素で冗長を作らない。
     */
    fun applyC1WindowPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, seed: Long = 0x1C1L, quantitativeRangeEval: Boolean = false, combineExhaustPairs: Boolean = false): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        var aRect = 0; var aSelf = 0
        if (p.cons1.isEmpty()) {
            return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, 0,
                listOf(MirrorLog(tag = "C1Polish", message = "cons1なし=スキップ")))
        }
        val rng = Random(seed)
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        // [C1研磨・手B強化] staff i2 が shift x2 について day を含むいずれかの窓で不足しているか（全cons1横断）。
        //   手B(findCovUChain の玉突き連鎖)の候補選定に c1Pref として渡し、「連鎖に組み込む相手が、たまたま
        //   その相手自身のc1不足も一緒に解消する」候補を優先させる（並べ替えのみ・安全条件は不変・探索の
        //   正しさは常に isBetter が最終担保）。
        fun c1Deficient(i2: Int, x2: Int, day: Int): Boolean {
            if (day !in 0 until p.T) return false
            for (c2 in p.cons1) {
                if (c2.shiftIdx != x2 || c2.day1 <= 0) continue
                if (inDeficientC1Window(p, work, i2, x2, c2.day1, c2.day2, day)) return true
            }
            return false
        }
        // [頭打ちの理由を可視化/RangePolish=3.222.0と同型] 手A/R1/R2いずれも成立しなかった最終フォール
        //   バック(手B=直接移動+玉突き)の結果を(staff,shift)ごとに集計。「候補なし」=findCovUChainが
        //   埋め戻し相手を1人も見つけられなかった／「不採用」=候補は見つかったが実目的関数(isBetter)が
        //   総合的に拒否した、の2分類（RangePolishと同じ粒度）。休の窓ルールが解消しない理由を
        //   ユーザーがログから直接読めるようにする。
        // [3.326.0] キーに**規則index**を含める。旧: (職員,シフト) だけだったため、同じシフトに複数の
        //   期間の決まり（例「休 5日で1回以上」と「休 15日で4回以上」）があると別の規則で却下された理由が
        //   混ざって並んだ。規則ごとに分ければ「どの決まりで詰まったか」が読める。
        //   同一規則の複数の窓は依然まとめて数える（1日が複数の不足窓に属しうるため代表窓を選べない）。
        val blockStats = HashMap<Triple<Int, Int, Int>, MutableMap<String, Int>>()
        // [不採用の主因, 3.302.0] 「不採用」だけでは何に負けたか読めないため、拒否した候補が重み付きで
        //   最も増やした族を併記する（実機ログの c1 残存が「不採用×65 / 候補なし×4」＝ほぼ全部が拒否で、
        //   次に何を緩めるべきかが読めなかった）。AdaptiveBlockSwap と同じ worstWorsenedFamily を共用。
        val culpritStats = HashMap<Triple<Int, Int, Int>, MutableMap<String, Int>>()
        fun recordBlock(i: Int, x: Int, ri: Int, reason: String, after: ViolationReport? = null, before: ViolationReport? = null) {
            val key = Triple(i, x, ri)
            blockStats.getOrPut(key) { HashMap() }.merge(reason, 1, Int::plus)
            if (after != null && before != null) {
                worstWorsenedFamily(after, before)?.let { culpritStats.getOrPut(key) { HashMap() }.merge(it, 1, Int::plus) }
            }
        }
        // [汎用玉突き結合フレームワーク, 3.249.0] 手B/手R3が単独では isBetter に不採用だった候補
        //   （chain/repackとも構築自体は成功したもの）を蓄積し、末尾で複数を束ねて再挑戦する。
        val combinable = ArrayList<CombinatorialRepair.Candidate>()
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            // [違反セル指向] c1で違反している職員のみを起点に絞る。c1は職員ごと→改善手は必ず違反職員を
            //   含む＝ロスレス。空なら即終了でコスト0。
            // [実機ログ起因/実バグ修正] 旧実装は rep0.violations（1セル=最重1クラスのみ）を見ていたため、
            //   c1違反セルが同じセルでc3n(HARD,重み7000)等の更に重い違反も起こしている場合、そのセルの
            //   c1マークが violations 上では上書きされて消え、該当職員のc1違反自体が研磨の起点候補から
            //   漏れうる潜在バグだった（他のc1違反セルで既に起点に入っていれば実害なしだが、全run-startが
            //   重い違反と同居する職員では研磨が一度も試みられない）。cellFamilies（3.111.0で追加された
            //   1セル=重み降順の全クラスリスト、weight-priorityで discard しない）に切替えれば漏れなく検出
            //   できる。起点集合が広がるだけ(既存の起点は cellFamilies にも必ず含まれる=violationsの
            //   最重クラスはcellFamiliesの先頭要素と同一)なので後方互換・退化なし。
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
            val anchorStaff = HashSet<Int>()
            for ((key, fams) in rep0.cellFamilies) {
                if ("vio-c1" in fams) anchorStaff.add(key.substringBefore(",").toIntOrNull() ?: continue)
            }
            if (anchorStaff.isEmpty()) break
            for ((ri, c) in p.cons1.withIndex()) {
                val x = c.shiftIdx; val d = c.day1; val n = c.day2
                if (x !in 0 until p.K || d <= 0) continue
                for (i in 0 until p.S) {
                    if (shouldStop()) break
                    if (i !in anchorStaff) continue
                    if (!p.canDo(i, x)) continue
                    // [移設ドナー] i 自身の X 保有日のうち「抜いても this ルールの窓が新規に不足化しない」余剰位置。
                    //   盤面が変わるたび(i,x)単位で無効化し次の j で再構築する（遅延キャッシュ）。
                    var donorsCache: List<Int>? = null
                    fun donors(): List<Int> = donorsCache ?: (0 until p.T).filter { j1 ->
                        work[i][j1] == x && movable(i, j1) && run {
                            var wStart = maxOf(0, j1 - d + 1); val wEnd = minOf(j1, p.T - d)
                            var surplus = true
                            while (wStart <= wEnd) {
                                var z = 0
                                for (l in 0 until d) if (work[i][wStart + l] == x) z++
                                if (z <= n) { surplus = false; break }   // 閾値ちょうど以下=抜くと新規fire→保守的に除外
                                wStart++
                            }
                            surplus
                        }
                    }.also { donorsCache = it }
                    for (j in 0 until p.T) {
                        // [監査(未レビュー領域再監査)] このjループはi2走査に加えfindCovUChainのBFSも伴い重い
                        //   （HF66/BlockRotationPolishと同型の予算超過対策として日ごとにも確認）。
                        if (shouldStop()) break
                        if (work[i][j] == x || !movable(i, j)) continue
                        if (!inDeficientC1Window(p, work, i, x, d, n, j)) continue
                        val a = work[i][j]                                  // i の旧シフト
                        // [厳密ピン保護] 手A/手B は i(・i2)の自身のシフト回数を実際に変える(x+1/a-1)唯一の
                        //   手（手R1/R2/R3は同一職員内の日入替のみで回数は代数的に保存される＝対象外）。
                        //   staffRangeが下限=上限で完全固定("厳密ピン")の職員をこの手で崩さないよう、
                        //   swap前の盤面を基準にexactPinRegressionで追加ガードする（keep-best/重みは不変）。
                        val workBeforeDay = work.copy2D()
                        var done = false
                        for (i2 in 0 until p.S) {
                            if (i2 == i || work[i2][j] != x || !movable(i2, j) || !p.mayPlace(i2, a)) continue
                            work[i][j] = x; work[i2][j] = a                 // 同日スワップ（被覆不変）
                            val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                            val pinBadA = exactPinRegression(p, workBeforeDay, work)
                            if (pinBadA && betterReport(rep, bestRep)) pinBlocks.record(p, workBeforeDay, work)
                            if (betterReport(rep, bestRep) && !pinBadA) {
                                bestRep = rep; applied++; improved = true; done = true; break
                            }
                            // [3.324.0/外部レビュー] 旧実装は手Aのピン却下を黙って巻き戻すだけで数えておらず、
                            //   C1 の「ピン破り」件数が手B(玉突き)だけの部分集計になっていた。手Aは回数を実際に
                            //   変える手（x+1/a-1）＝ピンの当たり判定があるので、ここも記録する。
                            // [3.347.0/敵対検証] 手Aは**ピン却下だけ**を数えており、同じ手が採点で落ちた
                            //   ときは何も残していなかった。手B(1590行)は両方残すので、同じ (職員,シフト,決まり)
                            //   の集計でピン側だけが厚くなり、`causeOf` が「回数固定で却下」へ寄る。
                            //   どちらも i2 ごと＝同じ粒度なので、対称に数える。
                            if (betterReport(rep, bestRep) && pinBadA) recordBlock(i, x, ri, C1PlateauDiagnosis.REASON_PIN)
                            else recordBlock(i, x, ri, C1PlateauDiagnosis.REASON_SCORE, after = rep, before = bestRep)
                            work[i][j] = a; work[i2][j] = x                 // 巻き戻し
                        }
                        if (done) { donorsCache = null; continue }

                        // [手R1] 鏡像長方形: i=[X@j1,a@j] ↔ i2=[a@j1,X@j]。回数・日別人数とも完全保存
                        //   （i2 は既に保有しているシフトしか持たない＝canDo自動成立だが規律として明示検査する）。
                        val fires0 = c1RowFires(p, work, i)
                        for (j1 in donors()) {
                            if (done || shouldStop()) break
                            if (j1 == j) continue
                            work[i][j1] = a; work[i][j] = x
                            val gain = fires0 - c1RowFires(p, work, i)
                            work[i][j1] = x; work[i][j] = a                 // 判定用の一時変更は必ず復元
                            if (gain <= 0) continue
                            for (i2 in 0 until p.S) {
                                if (done || shouldStop()) break
                                if (i2 == i) continue
                                if (work[i2][j1] != a || work[i2][j] != x) continue      // 完全鏡像の相手のみ
                                if (!movable(i2, j1) || !movable(i2, j)) continue
                                if (!p.mayPlace(i, x) || !p.mayPlace(i2, a)) continue           // 構造上恒真・規律として明示
                                work[i][j1] = a; work[i][j] = x; work[i2][j1] = x; work[i2][j] = a
                                val bad3n = p.makesForbiddenRun(work, i, j1, a) || p.makesForbiddenRun(work, i, j, x) ||
                                    p.makesForbiddenRun(work, i2, j1, x) || p.makesForbiddenRun(work, i2, j, a)
                                if (!bad3n) {
                                    val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                                    if (betterReport(rep, bestRep)) {
                                        bestRep = rep; applied++; aRect++; improved = true; done = true
                                        donorsCache = null
                                        break
                                    }
                                }
                                if (!done) { work[i][j1] = x; work[i][j] = a; work[i2][j1] = a; work[i2][j] = x }
                            }
                        }
                        if (done) continue

                        // [手R2] 自己2日swap: i の X@j1 ↔ a@j（回数保存＝low/high/apt/c2不変。日別人数が
                        //   変わるため離脱側2箇所を covUCell(source of truth)で事前除外してから適用）。
                        //   a が normalizeSchedule 由来の -1(範囲外/未割当) なら「本物のシフト」ではないため
                        //   R2(自己内の付け替え)の対象外とする（work[i][j1] へ -1 を書き込む不正な手を防ぐ。
                        //   手A/手Bは a=-1 でも canDo(-1)=false / findCovUChain の範囲ガードで元々安全なので
                        //   この場合も手Bへは進める＝ここは continue でなく R2 ブロックだけを囲む）。
                        if (a in 0 until p.K) {
                            for (j1 in donors()) {
                                if (done || shouldStop()) break
                                if (j1 == j) continue
                                work[i][j1] = a; work[i][j] = x
                                val gain = fires0 - c1RowFires(p, work, i)
                                work[i][j1] = x; work[i][j] = a
                                if (gain <= 0) continue
                                var cx = 0; var ca = 0
                                for (s in 0 until p.S) { if (work[s][j1] == x) cx++; if (work[s][j] == a) ca++ }
                                if (p.covUCell(x, j1, cx - 1) > p.covUCell(x, j1, cx)) continue   // X の j1 離脱で covU 悪化
                                if (p.covUCell(a, j, ca - 1) > p.covUCell(a, j, ca)) continue      // a の j 離脱で covU 悪化
                                work[i][j1] = a; work[i][j] = x
                                val bad3n = p.makesForbiddenRun(work, i, j1, a) || p.makesForbiddenRun(work, i, j, x)
                                if (!bad3n) {
                                    val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                                    if (betterReport(rep, bestRep)) {
                                        bestRep = rep; applied++; aSelf++; improved = true; done = true; donorsCache = null
                                    }
                                }
                                if (!done) { work[i][j1] = x; work[i][j] = a }
                            }
                        }
                        if (done) continue

                        // [E11反映] 直接の交換相手が見つからない/不採用 → i を X へ動かし、空いた a の穴を
                        //   玉突き連鎖で埋め直す（findCovUChain は盤面を変えないため元値を保存して巻き戻せるようにする）。
                        work[i][j] = x
                        // exclude=i: i は既に x へ動かした本人なので、a を埋め戻す候補から除外
                        //   （除外しないと「i が a に戻る」= i の移動そのものを打ち消す退行手をBFSが選びうる）。
                        // c1Pref=c1Deficient: 連鎖の相手選びを「その相手自身のc1不足も一緒に解消するか」で
                        //   優先付け（並べ替えのみ・見つからなければ従来どおり）。
                        val chain = findCovUChain(p, work, a, j, rng, exclude = i,
                            c1Pref = { s2, sh, dy -> c1Deficient(s2, sh, dy) })
                        val oldVals = chain?.let { ch -> IntArray(ch.size) { work[ch[it][0]][ch[it][1]] } }
                        chain?.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
                        val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                        if (adoptionGate(p, workBeforeDay, work, rep, bestRep, pinBlocks).accepted) {
                            bestRep = rep; applied++; improved = true
                            donorsCache = null
                        } else {
                            if (chain != null && oldVals != null) {
                                for (idx in chain.indices) work[chain[idx][0]][chain[idx][1]] = oldVals[idx]
                                val hint = "${state.staff.getOrNull(i)?.name ?: "#$i"}(${state.shifts.getOrNull(x)?.kigou ?: x})"
                                combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j, x)) + chain, "手B", hint))
                            }
                            work[i][j] = a
                            // [不採用の主因, 3.302.0] ピン破り（厳密ピンを崩すため却下）は違反自体が
                            //   悪化していないので主因族を持たない＝別ラベルにして混同を避ける。
                            when {
                                chain == null -> recordBlock(i, x, ri, C1PlateauDiagnosis.REASON_NO_CANDIDATE)
                                betterReport(rep, bestRep) -> recordBlock(i, x, ri, C1PlateauDiagnosis.REASON_PIN)
                                else -> recordBlock(i, x, ri, C1PlateauDiagnosis.REASON_SCORE, after = rep, before = bestRep)
                            }
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        // [手R3・局所探索の強化=ユーザー指示「賢く深く網羅的に」] 手A/R1/R2/手Bを尽くしてもなお不足
        //   しているルールに対し、アンカーセルに限定しない全ペア網羅(2-opt完全探索)を1回だけ試す。
        //   grilling確定: 真に壁がある職員（例: 休の個人上限が窓ルール最低必要回数を下回る）でも、
        //   休の「配置の仕方」次第で窓違反件数は変動しうる。既存の手A/R1/R2/手Bはいずれも「現在違反
        //   しているセルj」をアンカーに限定した局所改善のみで、その職員の休配置パターン全体を作り直す
        //   大きな手を一度も試していなかった。DP等の厳密最適化は3.200.0で「正しさのリスクが実装前から
        //   顕在化」として不採用済みのため、既存アーキテクチャに忠実な局所探索強化（手R2の一般化＝
        //   アンカー限定とdonors(改善見込みの事前判定)の両方の制約を外した全ペア評価）を採用。
        //   xの保有movable日×非保有movable日の全ペアを評価し、職員全体のfires(全cons1横断合計)が
        //   最も改善するペアを採用する(best-improvement)。安全性は手R2と同一の被覆ガード(covUCell)＋
        //   makesForbiddenRun事前枝刈り＋isBetter最終ゲート。真に壁がある場合はgain<=0のまま全ペアが
        //   尽き、安全に諦める（退化不能）。対象は残存c1違反のある全職員（壁の有無を問わない＝
        //   壁でない職員も既存の狭い近傍だけでは見つからない改善を拾える）。
        var aRepack = 0
        for (i in 0 until p.S) {
            if (shouldStop()) break
            for ((ri, c) in p.cons1.withIndex()) {
                if (shouldStop()) break
                val x = c.shiftIdx; val d = c.day1; val n = c.day2
                if (x !in 0 until p.K || d <= 0 || !p.canDo(i, x)) continue
                val stillDeficient0 = (0..p.T - d).any { j -> inDeficientC1Window(p, work, i, x, d, n, j) }
                if (!stillDeficient0) continue
                val hx = (0 until p.T).filter { work[i][it] == x && movable(i, it) }
                // [3.475.0/論理監査] 手R2 と同じ -1 ガード。normalizeSchedule 由来の -1（範囲外/未割当）が
                //   `ho` に入ると下の covUCell(a=-1, …) が need1[-1] で ArrayIndexOutOfBounds になり、
                //   後処理全体が例外で巻き戻って**最適化結果ごと入力へ戻っていた**（R3 だけガードが無かった）。
                val ho = (0 until p.T).filter { work[i][it] in 0 until p.K && work[i][it] != x && movable(i, it) }
                if (hx.isEmpty() || ho.isEmpty()) { recordBlock(i, x, ri, C1PlateauDiagnosis.REASON_NO_REPACK); continue }
                val fires0 = c1RowFires(p, work, i)
                var bestGain = 0; var bestJx = -1; var bestJo = -1
                for (jx in hx) {
                    if (shouldStop()) break
                    for (jo in ho) {
                        val a = work[i][jo]
                        var cx = 0; var ca = 0
                        for (s in 0 until p.S) { if (work[s][jx] == x) cx++; if (work[s][jo] == a) ca++ }
                        if (p.covUCell(x, jx, cx - 1) > p.covUCell(x, jx, cx)) continue
                        if (p.covUCell(a, jo, ca - 1) > p.covUCell(a, jo, ca)) continue
                        work[i][jx] = a; work[i][jo] = x
                        val bad3n = p.makesForbiddenRun(work, i, jx, a) || p.makesForbiddenRun(work, i, jo, x)
                        if (!bad3n) {
                            val fires1 = c1RowFires(p, work, i)
                            val gain = fires0 - fires1
                            if (gain > bestGain) { bestGain = gain; bestJx = jx; bestJo = jo }
                        }
                        work[i][jx] = x; work[i][jo] = a
                    }
                }
                if (bestGain > 0) {
                    val a = work[i][bestJo]
                    work[i][bestJx] = a; work[i][bestJo] = x
                    val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                    if (betterReport(rep, bestRep)) {
                        bestRep = rep; applied++; aRepack++
                    } else {
                        work[i][bestJx] = x; work[i][bestJo] = a
                        val hint = "${state.staff.getOrNull(i)?.name ?: "#$i"}(${state.shifts.getOrNull(x)?.kigou ?: x})"
                        combinable.add(CombinatorialRepair.Candidate(
                            listOf(intArrayOf(i, bestJx, a), intArrayOf(i, bestJo, x)), "手R3", hint))
                        recordBlock(i, x, ri, C1PlateauDiagnosis.REASON_SCORE, after = rep, before = bestRep)
                    }
                } else {
                    recordBlock(i, x, ri, C1PlateauDiagnosis.REASON_NO_REPACK)
                }
            }
        }
        // [汎用玉突き結合フレームワーク, 3.249.0] 単独では不採用だった候補群を2〜4件束ねて再挑戦
        //   （grilling確定・c1/range/c3mn/apt/fair横断の共通ヘルパ）。stuckNames より前に実行し、
        //   結合で解消した箇所が「残存」に残らないようにする。
        val rejectedOut = ArrayList<CombinatorialRepair.Candidate>()
        val c1CombStats = CombinatorialRepair.Stats()
        bestRep = CombinatorialRepair.combineAndApply(
            state, work, bestRep, combinable.asReversed(), ::betterReport, shouldStop = shouldStop, stats = c1CombStats, p = p, leftover = rejectedOut,
            exhaustPairs = combineExhaustPairs,
        )
        applied += c1CombStats.combosAccepted
        // [頭打ちの理由を可視化/RangePolish=3.222.0と同型] 手B(直接移動+玉突き)が最終的に失敗した
        //   (staff,ルールのシフト)のうち、最終盤面でなお当該窓が不足しているものだけを「残存」として表示
        //   （途中で別の手/別のjで解消済みなら除外）。「候補なし」=玉突き相手が1人も見つからない構造的
        //   ブロック／「不採用」=候補は見つかったが総合的に isBetter が拒否（他族とのトレードオフで負け）。
        val stuckNames = blockStats.entries.mapNotNull { (key, reasons) ->
            val (i, x, ri) = key
            val rule = p.cons1.getOrNull(ri)
            val stillDeficient = rule != null && rule.shiftIdx == x && rule.day1 > 0 &&
                (0..p.T - rule.day1).any { j -> inDeficientC1Window(p, work, i, x, rule.day1, rule.day2, j) }
            if (!stillDeficient) return@mapNotNull null
            val lbl = "${state.staff.getOrNull(i)?.name ?: "#$i"} ${state.shifts.getOrNull(x)?.kigou ?: x.toString()}" +
                "(${rule!!.day1}日${rule.day2}回)"
            val top = reasons.maxByOrNull { it.value } ?: return@mapNotNull lbl
            // [不採用の主因, 3.302.0] 「不採用」のときだけ、拒否された候補が重み付きで最も壊した族を
            //   上位2件まで併記する（何を緩めれば通るのかがログから直接読める）。
            val culprits = if (top.key != "不採用") "" else
                culpritStats[key]?.entries?.sortedByDescending { it.value }?.take(2)
                    ?.joinToString(" ") { "${it.key}:${it.value}" }
                    ?.let { if (it.isEmpty()) "" else " 主因 $it" } ?: ""
            "$lbl(${top.key}×${top.value}$culprits)"
        }.distinct()
        // [構造化診断, 3.322.0] 上の「残存:」はログ文字列だが、同じ材料を構造化して UI まで運ぶ
        //   （文字列を後から解析させない）。判定は最終盤面で不足が残っている (職員,シフト) だけ。
        val plateau = C1PlateauDiagnosis.build(
            remainingC1 = bestRep.breakdown["c1"] ?: 0,
            blockStats = blockStats,
            culpritStats = culpritStats,
            staffName = { state.staff.getOrNull(it)?.name ?: "#$it" },
            shiftKigou = { state.shifts.getOrNull(it)?.kigou ?: it.toString() },
            ruleLabel = { ri -> p.cons1.getOrNull(ri)?.let { "${it.day1}日で${it.day2}回以上" } ?: "?" },
            // [3.326.0] 規則単位で判定する。旧: 同じシフトの**どれか**の決まりが残っていれば全部残す
            //   （別の決まりで却下された理由が、解消済みの決まりの理由として並びうる）。
            stillDeficient = { i, x, ri ->
                val c = p.cons1.getOrNull(ri)
                c != null && c.shiftIdx == x && c.day1 > 0 &&
                    (0..p.T - c.day1).any { j -> inDeficientC1Window(p, work, i, x, c.day1, c.day2, j) }
            },
        )
        val c1CombSummary = c1CombStats.summary()
        val logs = listOf(MirrorLog(tag = "C1Polish",
            message = "期間要件(c1)研磨: c1 ${before.breakdown["c1"] ?: 0}->${bestRep.breakdown["c1"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回(鏡像:$aRect 自己:$aSelf 再配置:$aRepack)" +
                (if (applied == 0 && (before.breakdown["c1"] ?: 0) > 0) " [頭打ち=改善手なし]" else "") +
                (if (stuckNames.isNotEmpty()) " 残存: ${stuckNames.joinToString(", ")}" else "") +
                (if (c1CombSummary.isNotEmpty()) " / $c1CombSummary" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, plateau, pinBlocks.attempts, pinBlocks, rejectedCandidates = rejectedOut)
    }

    /**
     * [C3mnPolish・玉突き連鎖の横展開] cons3mn(回避パターン, SOFT重み30)専用の研磨パス。
     * grilling(2026-07-19)で確定: 対象はc3mnのみ(c3nはHARDで既存のRSI focus優先/keep-bestが担当済み・
     * 同一パスに混ぜると役割が重複し測定しづらくなる)。既存の`findCovUChain`(玉突き連鎖BFS、深さ5まで)を
     * そのまま再利用し、C1Polish(3.158.0)の「手B/E11」ブロックと同型の構成にする。
     *
     * 動機（金沢勇輝の実例, 実機ログ2026-07-19）: cons3n(HARD)がDﾃ直後の主要シフトを軒並み禁止するため、
     * Dﾃを複数回持つ職員はDﾃを連続させるのが安全側になりやすく、cons3mnの「N連続回避」パターンに
     * ヒットしたまま残ることがある。休を追加すればhigh違反(weight90)の方が高くつく局面では崩せないため、
     * 「その職員自身のDﾃ/休の回数を変えずに、そのセルだけ他シフトへ動かす」手が必要——これはまさに
     * findCovUChainが対応する「直接候補が全員(希望固定/禁止連続/被覆)でブロックされる」局面と同型。
     *
     * アンカー: [レビュー3.111.0系]と同じ理由でcellFamilies(1セル=重み降順の全クラス)から"vio-c3mn"を含む
     * セルを起点にする（violations単一クラスマップだと、より重い違反が同居するセルで見落としうるため）。
     * 各アンカーセル(i,j)について、i の担当可能シフトへ付け替える(c3n新規発生はmakesForbiddenRunで事前枝刈り)。
     * 付け替えで元シフトの被覆が悪化するなら`findCovUChain`で玉突き連鎖を試す(C1Polish手Bと同一パターン)。
     * 採否は既存のisBetter(hard→weighted→total)keep-best＝退化不能。完了条件はユニットテストのみ(grilling決定)。
     */

    /**
     * [C1研磨・複数職員時空間ビーム版, 外部パッチ受領→2箇所修正のうえ適用] applyC1WindowPolish/
     * BeamC1PolishV2 と並存する第3のc1研磨。単一路の同日greedyでなく、各ステップで残っている
     * 不足(staff,day)ターゲットに最小単位の手（同日swap優先、だめならc1Pref付きchain）を足し、
     * HARD悪化のみを絶対条件に生成した候補群を(hard,weightedScore,total)の真の目的関数順で
     * 上位beamWidth本まで残して反復する（デフォルトmaxSteps=60）。
     *
     * **受領コードからの修正2点**（そのまま採用せずレビュー・実データ検証で発見）:
     * ①ビーム剪定の内部ランキングが受領コードでは(hard,c1件数,weightedScore)という**c1専用の
     * 近似指標**だった。golden_state.json実測でこれが致命的と判明: c1を91→63まで下げる候補を
     * 選ぶが、それと引き換えにlow/high/apt/weekly等の他族が軒並み悪化しtotal 291→349・
     * weightedScore 1939→3722（ほぼ倍）という**真の目的関数では大幅な退化**を招いていた
     * （このコードベース全体の規約=hard→weightedScore→totalで、c1だけを見て他族への
     * 転嫁を検出できない近似だったため）。ランキングを(hard,weightedScore,total)の真の目的
     * 関数へ修正した結果、golden_state.json/sample_state_v6.jsonの両方・全15シードで
     * 一貫してtotalが真に改善する（golden: 291→274-287, sample_v6: 236→227-229、HARDは
     * 両方とも不変）ことを確認。
     * ②受領コードは検索結果を無条件に返しており、既存の全パスに共通する「root(入力)と比較し
     * 勝てなければroot自身を返す」keep-best安全網が無かった（ビームはrootが必ずしも生き残ら
     * ないためroot自身が最終候補に一度も入らない可能性がある）。`isBetter`によるroot比較＋
     * フォールバックを追加し退化不能にした。
     *
     * 検証はホストJVM(Gradle同梱のkotlin-compiler-embeddable 2.0.21)でandroid非依存の
     * v6/modelパッケージを実コンパイルし、golden_state.json/sample_state_v6.jsonの実データで
     * 実測（このセッション内で実施）。
     *
     * **[3.340.0] 最良保持(elitism)と停滞打ち切り**。3.339.0 のパス別テレメトリでこのパスが後処理の
     * 27〜42% を占めると判明したため、何にその時間を使っているかを実データ3件で計測した:
     *  - 8回の呼出**すべてが maxSteps を完走**し、1回あたり約30,000回のフル checker（時間の82〜88%）。
     *  - しかし**最後に最良が更新されてから 32〜60 ステップが空回り**（8回中4回は根を一度も超えない）。
     *  - さらに `beam` は最良を保持せず**最終ビームの最小しか返さない**ため、探索途中で見つけた
     *    より良い盤面を捨てていた（golden の1回目は s15 の候補が根に勝っていたのに最終ビームは
     *    根より悪く、丸ごと破棄されていた）。
     * → ①各ステップのビーム先頭を `bestEver` として保持し最終候補にする（最終ビームは観測列に
     *   含まれるので**この手だけでは絶対に退化しない**）②最良が `patience` ステップ更新されなければ
     *   打ち切る。実測の改善間隔の最大は 15 ステップなので既定 20 は観測済みの改善をすべて残す。
     * 実データ3件で**最終盤面は現行とバイト一致**（golden 2469/306/c1 104・user 33159/162/c1 54・
     * real 49223/170/c1 58）、本パスの所要は golden 7.0→3.2s・user 14.6→7.7s・real 8.1→6.9s。
     * 浮いた時間はクラスタ締切(`clusterStop`)配下の後段（共同LNS等）へ回る。
     * なお ablation（このパスを丸ごと外す）では user が 33159/162 → 33232/165 と悪化＝
     * **このパスは実データで実際に効いている**ので、打ち切りはするが撤去はしない。
     */
    fun applyC1BeamPolish(
        state: MagiState, schedule: Array<IntArray>, beamWidth: Int = 16, maxSteps: Int = 60,
        shouldStop: () -> Boolean = { false }, seed: Long = 0x1CBEAL, patience: Int = 20,
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult {
        // [3.375.0/ユーザー指示「停滞脱出のログにイテ回数と時間を出す」] 停滞打ち切りの所要時間。
        //   旧: 「steps=22/最良が20手更新されず打ち切り」と手数だけで、その空振りが一瞬なのか
        //   秒単位なのかが読めず、patience の妥当性を実機ログから判断できなかった。
        val beamT0 = EngineClock.nowMs()
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work0 = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work0, quantitativeRangeEval)
        if (p.cons1.isEmpty()) {
            return V6HotfixPasses.CyclicSwapResult(work0, before.total, before.total, 0,
                listOf(MirrorLog(tag = "C1BeamPolish", message = "cons1なし=スキップ")))
        }
        val rng = Random(seed)
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        fun c1Deficient(work: Array<IntArray>, i2: Int, x: Int, day: Int): Boolean {
            if (day !in 0 until p.T) return false
            for (c in p.cons1) {
                if (c.shiftIdx != x || c.day1 <= 0) continue
                if (inDeficientC1Window(p, work, i2, x, c.day1, c.day2, day)) return true
            }
            return false
        }

        data class Beam(val work: Array<IntArray>, val rep: com.magi.app.v6.ViolationReport, val applied: Int)

        fun rebuildTargets(work: Array<IntArray>): List<Triple<Int, Int, Int>> {
            val out = ArrayList<Triple<Int, Int, Int>>()
            for ((ci, c) in p.cons1.withIndex()) {
                val x = c.shiftIdx; val d = c.day1; val n = c.day2
                if (x !in 0 until p.K || d <= 0) continue
                for (i in 0 until p.S) {
                    if (!p.canDo(i, x)) continue
                    for (j in 0 until p.T) {
                        if (work[i][j] == x || !movable(i, j)) continue
                        if (inDeficientC1Window(p, work, i, x, d, n, j)) out.add(Triple(ci, i, j))
                    }
                }
            }
            return out
        }
        fun tryOneMove(base: Array<IntArray>, i: Int, j: Int, x: Int): Array<IntArray>? {
            val w = Array(base.size) { base[it].copyOf() }
            val a0 = w[i][j]
            for (i2 in 0 until p.S) {
                if (i2 == i || w[i2][j] != x || !movable(i2, j) || !p.mayPlace(i2, a0)) continue
                w[i][j] = x; w[i2][j] = a0
                return w
            }
            w[i][j] = x
            val chain = findCovUChain(p, w, a0, j, rng, exclude = i,
                c1Pref = { s2, sh, dy -> c1Deficient(w, s2, sh, dy) })
            if (chain == null) return w
            chain.forEach { mv -> w[mv[0]][mv[1]] = mv[2] }
            return w
        }

        var beam = listOf(Beam(work0, before, 0))
        // [3.340.0] 探索中に見つけた最良を保持する（最終ビームは観測列に含まれるので退化不能）。
        var bestEver: Beam? = null
        var stagnant = 0
        var step = 0
        while (step < maxSteps) {
            if (shouldStop()) break
            var anyExpanded = false
            val nextCandidates = ArrayList<Beam>()
            for (b in beam) {
                val targets = rebuildTargets(b.work)
                if (targets.isEmpty()) { nextCandidates.add(b); continue }
                val tryList = if (targets.size <= beamWidth * 2) targets else targets.shuffled(rng).take(beamWidth * 2)
                for ((ci, i, j) in tryList) {
                    if (shouldStop()) break
                    val x = p.cons1[ci].shiftIdx
                    val w2 = tryOneMove(b.work, i, j, x) ?: continue
                    val rep2 = UnifiedViolationChecker.check(state, w2, quantitativeRangeEval)
                    if (rep2.hard > before.hard) continue
                    nextCandidates.add(Beam(w2, rep2, b.applied + 1))
                    anyExpanded = true
                }
            }
            if (!anyExpanded) break
            beam = nextCandidates
                .distinctBy { cand -> BoardKey(cand.work) }
                .sortedWith(compareBy(reportComparator) { it.rep })
                .take(beamWidth)
            // sortedWith 済みなので先頭がこのステップの最小。最良を更新できなければ停滞を数える。
            val top = beam.firstOrNull()
            if (top != null) {
                val be = bestEver
                val improved = be == null || betterReport(top.rep, be.rep)
                // [3.409.24] **厳密ピンを崩す盤面は最良として保持しない**。保持しても最終ゲートで
                //   root へ落ちるだけでなく、**それより前に見つけたピン安全な改善を追い出してしまう**
                //   ＝3.340.0 が入れた「ステップを増やすほど良くなる」保証が壊れる。実際 c1/c3mn の重みを
                //   30 へ上げた直後に `moreStepsNeverProduceAWorseResult` が落ちて発覚した
                //   （maxSteps=8 は 4520/421 を返すのに 12 は root 4999/437 へ落ちていた）。
                //   root に勝てない候補はそもそも最終ゲートを通らないので、ピン判定は root 改善時だけ行う。
                val blocked = improved && betterReport(top.rep, before) &&
                    pinBlocks.blocksImproving(p, work0, top.work)
                if (improved && !blocked) bestEver = top
                // [3.409.27] 停滞カウンタは**旧実装と厳密に同じ**（目的関数で最良を更新したら 0）。
                //   ピンで弾いた回を停滞に数えると patience が早く発火し、その先にあったかもしれない
                //   ピン安全な改善を取り逃す＝「探索を短くする」別の退化を持ち込む。ここで直したいのは
                //   **保持するもの**であって**探索の長さ**ではないので、探索長は1ステップも変えない。
                if (improved) stagnant = 0 else stagnant++
            }
            step++
            if (stagnant >= patience) break
        }
        // [keep-best安全網] ビーム探索は root 自身を無条件に温存しない（targets 非空の初回展開で
        //   root は子に置き換わり消える）ため、全展開が真の目的関数的には根より悪化する可能性が
        //   ある。既存の全パスが isBetter で keep-best するのに合わせ、root と厳密に比較し、
        //   勝てない場合は必ず未変更の root へフォールバックする（退化不能）。
        val candidate = bestEver
            ?: beam.minWithOrNull(compareBy(reportComparator) { it.rep })
            ?: Beam(work0, before, 0)
        // [厳密ピン保護] ビーム探索の手A/玉突きも i の自身のシフト回数を変えうるため、根(work0)と比較し
        //   staffRange厳密ピン(lo==hi)を崩す最終候補は不採用にする（keep-best/重みは不変・追加ガードのみ）。
        val best = if (adoptionGate(p, work0, candidate.work, candidate.rep, before, pinBlocks).accepted) candidate else Beam(work0, before, 0)
        val logs = listOf(MirrorLog(tag = "C1BeamPolish",
            message = "期間要件(c1)研磨[ビーム K=$beamWidth steps=$step/${EngineClock.nowMs() - beamT0}ms" +
                (if (stagnant >= patience) "/最良が${patience}手更新されず打ち切り" else "") + "]: " +
                // weighted を併記する: keep-best は hard→weightedScore→total（3.287.0）なので、
                // c1/total が増える採用も weighted の改善なら正しい取引。実機ログ（3.409.14）で
                // 「c1 107->112 / total 425->431」だけが出て退行に見えた＝数字の根拠を同じ行に出す。
                "c1 ${before.breakdown["c1"] ?: 0}->${best.rep.breakdown["c1"] ?: 0} / total ${before.total}->${best.rep.total} score ${before.weightedScore.toLong()}->${best.rep.weightedScore.toLong()} HARD ${before.hard}->${best.rep.hard} 手数${best.applied}" +
                (if (best.applied == 0 && candidate !== best && candidate.applied > 0) " [探索結果が根に勝てず破棄]" else "")))
        return V6HotfixPasses.CyclicSwapResult(best.work, before.total, best.rep.total, best.applied, logs, pinBlocks = pinBlocks)
    }


}
