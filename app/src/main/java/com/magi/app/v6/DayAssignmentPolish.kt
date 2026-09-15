package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * 日ブロック単位のHungarian最小費用割当による研磨2パス。[V6HotfixPasses] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * - [applyDayAssignmentPolish]：range(個人回数)/apt(適切回数)の限界費用で日ブロックを単発の厳密再割当。
 * - [applyAlternatingSoftPolish]：上記を一般化し weekly(曜日平準化)も費用に含め、座標降下で
 *   不動点まで収束させる版。
 * - [DayAssignResult]：両パス共通の返り型。
 *
 * `effectiveHi` は HF67/HF66 パスとも共有するため [V6HotfixPasses] 側に internal のまま残置し、
 * ここからは完全修飾で呼ぶ（写しを作らず単一ソースを共有）。
 */
internal object DayAssignmentPolish {
    data class DayAssignResult(
        val newSchedule: Array<IntArray>,
        val beforeTotal: Int,
        val afterTotal: Int,
        val appliedDays: Int,
        val logs: List<MirrorLog>,
        /** [3.326.0] 回数固定だけが却下した候補試行（対象別）。 */
        val pinBlocks: PinBlockAttribution? = null,
    )


    /**
     * [ソフト研磨・厳密] 日ごと最小費用割当による研磨。各日の (日,シフト) 人数（=HARD充足）を固定したまま、
     * 希望未固定(wish<0)の職員を、その日の同一シフト集合へ「個人別回数(range)・適切回数(apt)の逸脱が最小」に
     * **厳密再割当**（Hungarian）。乱択でなく日内最適の候補を作り、全体が改善した日だけ採用（keep-best＝退化なし）。
     * 連続規則・希望・平準化など列横断の相互作用は採用判定(UnifiedViolationChecker)で担保する。
     */
    fun applyDayAssignmentPolish(
        state: MagiState, schedule: Array<IntArray>, shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): DayAssignResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        // [3.509.2] 適切回数(apt)目標は Problem.apt（実効目標＝担当可ゲート・到達クランプ・D9 の個人設定除外込み）。
        //   生の群目標を読むと、チェッカーが持たない目標へ向けた費用行列になる。
        fun aptTarget(i: Int, k: Int): Int? = p.apt[i][k].takeIf { it >= 0 }
        fun cnt(): Array<IntArray> = countMatrix(p, work)
        var counts = cnt()
        for (j in 0 until p.T) {
            if (shouldStop()) break
            val free = (0 until p.S).filter { i -> !p.wishLocked(i, j) }
            if (free.size < 2) continue
            val slots = free.map { work[it][j] }                       // 当日の同一シフト多重集合（人数固定）
            val n = free.size
            val costM = Array(n) { r ->
                val i = free[r]
                LongArray(n) { c ->
                    val k = slots[c]
                    if (k !in 0 until p.K || !p.mayPlace(i, k)) MinCostAssignment.INF
                    else {
                        val x0 = counts[i][k] - (if (work[i][j] == k) 1 else 0)   // この日を除いた現状カウント
                        val x1 = x0 + 1                                            // k を割当てた後
                        val lo = p.rangeLo[i][k]
                        val hi = V6HotfixPasses.effectiveHi(p, i, k)
                        // [ソフト研磨・候補生成の重み整合] proxy は目的関数(Evaluator / staffCountPenaltyAt /
                        //   UnifiedViolationChecker)と同一の重みに整合させ、生成候補を真の目的へ寄せる（CLAUDE.md既知）。
                        //   [3.522.0] low 90→120, apt 1→4。採否は従来どおり keep-best(isBetter)が担うため退化なし。
                        fun rangePen(x: Int) = (if (lo != Int.MIN_VALUE) 120L * maxOf(0, lo - x) else 0L) + 25L * maxOf(0, x - hi)
                        var cost = rangePen(x1) - rangePen(x0)                     // range の限界費用
                        val t = aptTarget(i, k)
                        if (t != null) cost += (kotlin.math.abs(x1 - t) - kotlin.math.abs(x0 - t)).toLong() * 4L  // apt の限界費用
                        cost
                    }
                }
            }
            // [3.278.0] 全INF行(担当可否ゼロの職員等)は実行可能な完全割当が無い＝nullでその日をスキップ。
            val assign = MinCostAssignment.solve(costM) ?: continue
            val cand = work.copy2D()
            var changed = false
            for (r in free.indices) {
                val i = free[r]; val k = slots[assign[r]]
                if (cand[i][j] != k) { cand[i][j] = k; changed = true }
            }
            if (!changed) continue
            val rep = UnifiedViolationChecker.check(state, cand, quantitativeRangeEval)
            // [厳密ピン保護] 日ブロック内Hungarian再割当は複数職員の回数を同時に変えうるため、
            //   staffRange厳密ピン(lo==hi)を新たに崩す日案は不採用にする（keep-best/重みは不変）。
            if (adoptionGate(p, work, cand, rep, bestRep, pinBlocks).accepted) { work = cand; bestRep = rep; counts = cnt(); applied++ }
        }
        val logs = listOf(MirrorLog(tag = "DayAssign",
            message = "日ごと厳密割当: total ${before.total}->${bestRep.total} 採用${applied}日"))
        return DayAssignResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }


    /**
     * [ソフト研磨・交互最適化(Alternating Optimization / 交代最適化)] 全変数を同時に解かず「1ブロックずつ順に最適化
     * して巡回する」座標降下法（block coordinate descent）をソフト制約研磨に導入する新アルゴリズム。ブロック＝各日(列):
     * その日の (シフト人数=被覆) を固定したまま、希望未固定(wish<0)の職員を「個人別回数(range 120/25)・適切回数(apt 4)・
     * **曜日平準化(weekly 2)**」の限界費用が最小になるよう **最小費用割当(Hungarian＝割当LP＝凸最適化)** で最適再配置し、
     * 日 j を 0..T-1 と巡回して 1スイープで1日も変化しなくなるまで（＝座標降下の不動点）反復する。
     *
     * 既存 `applyDayAssignmentPolish`（range/apt のみ・単発）を、①weekly を費用に含め ②反復収束（交互）まで一般化した
     * もの。weekly を費用に入れる意味＝その日の「休スロット」を誰に割り当てるかで各職員の曜日別勤務数が変わる（被覆は不変）。
     * 「その曜日に働き過ぎの職員へ休を、少なすぎる職員へ勤務を」割り当てる候補を Hungarian が同日内で**同時最適**に生成し、
     * 曜日偏りを直す。同日内の最適再配置＝rectangle（3.197.0, クロス日の2職員×2日）とは別種の被覆保存手＝相補的。
     * 採否は実目的関数 isBetter（hard→weighted→total, keep-best）＝退化なし。fair 等の他 soft は isBetter が担保する
     * （費用に無い族も採用判定で悪化しないことを保証）。純 Kotlin 後処理＝ネイティブ hot-path 非干渉（parity 影響なし）。
     */
    fun applyAlternatingSoftPolish(
        state: MagiState, schedule: Array<IntArray>, maxSweeps: Int = 4, shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): DayAssignResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        fun aptTarget(i: Int, k: Int): Int? = p.apt[i][k].takeIf { it >= 0 }   // [3.509.2] 上と同じく実効目標
        // [3.345.0] weekly の wd バケットは職員×**シフト**×曜日（休も1シフト＝特別扱いしない）。
        //   目標は weeklyDevOfBucket が内部で round(そのシフトの回数/7) として持つ。被覆保存の再配置ごとに更新。
        fun wdOf(i: Int): Array<IntArray> {
            val wd = Array(p.K) { IntArray(7) }
            for (j in 0 until p.T) { val k = work[i][j]; if (k in 0 until p.K) wd[k][(p.dow0 + j) % 7]++ }
            return wd
        }
        var wd = Array(p.S) { wdOf(it) }
        fun cnt(): Array<IntArray> = countMatrix(p, work)
        var counts = cnt()
        var sweep = 0
        var lastSweep = 0
        while (sweep < maxSweeps) {
            if (shouldStop()) break
            var changedInSweep = false
            for (j in 0 until p.T) {
                if (shouldStop()) break
                val free = (0 until p.S).filter { i -> !p.wishLocked(i, j) }
                if (free.size < 2) continue
                val slots = free.map { work[it][j] }
                val n = free.size
                val wdj = (p.dow0 + j) % 7
                val costM = Array(n) { r ->
                    val i = free[r]
                    LongArray(n) { c ->
                        val k = slots[c]
                        if (k !in 0 until p.K || !p.mayPlace(i, k)) MinCostAssignment.INF
                        else {
                            val x0 = counts[i][k] - (if (work[i][j] == k) 1 else 0)   // この日を除いた現状カウント
                            val x1 = x0 + 1
                            val lo = p.rangeLo[i][k]
                            val hi = V6HotfixPasses.effectiveHi(p, i, k)
                            // range/apt は applyDayAssignmentPolish と同一の目的関数整合 proxy。[3.522.0] low/apt/weekly 90/1/1→120/4/2。
                            fun rangePen(x: Int) = (if (lo != Int.MIN_VALUE) 120L * maxOf(0, lo - x) else 0L) + 25L * maxOf(0, x - hi)
                            var cost = rangePen(x1) - rangePen(x0)
                            val t = aptTarget(i, k)
                            if (t != null) cost += (kotlin.math.abs(x1 - t) - kotlin.math.abs(x0 - t)).toLong() * 4L
                            // [3.345.0] weekly 限界費用: 当日を k にしたときの、職員 i の**シフト k の**曜日
                            //   バケットの L1 偏差変化。当日の元シフトを失う項は行(i)ごとの定数＝
                            //   割当の argmin を変えないため省く（列ごとに効く項だけを費用に入れる）。
                            run {
                                val b = wd[i][k]
                                val had = if (work[i][j] == k) 1 else 0
                                b[wdj] -= had                                  // 当日を除いた状態
                                val devBefore = weeklyDevOfBucket(b)
                                b[wdj] += 1
                                val devAfter = weeklyDevOfBucket(b)
                                b[wdj] += had - 1                              // 復元
                                cost += (devAfter - devBefore).toLong() * 2L
                            }
                            cost
                        }
                    }
                }
                // [3.278.0] 全INF行(担当可否ゼロの職員等)は実行可能な完全割当が無い＝nullでその日をスキップ。
                val assign = MinCostAssignment.solve(costM) ?: continue
                val cand = work.copy2D()
                var changed = false
                for (r in free.indices) {
                    val i = free[r]; val k = slots[assign[r]]
                    if (cand[i][j] != k) { cand[i][j] = k; changed = true }
                }
                if (!changed) continue
                val rep = UnifiedViolationChecker.check(state, cand, quantitativeRangeEval)
                // [厳密ピン保護] 日ブロック内Hungarian再割当は複数職員の回数を同時に変えうるため、
                //   staffRange厳密ピン(lo==hi)を新たに崩す日案は不採用にする（keep-best/重みは不変）。
                if (adoptionGate(p, work, cand, rep, bestRep, pinBlocks).accepted) {
                    work = cand; bestRep = rep; counts = cnt()
                    wd = Array(p.S) { wdOf(it) }
                    applied++; changedInSweep = true
                }
            }
            sweep++; lastSweep = sweep
            if (!changedInSweep) break   // 座標降下の不動点＝この巡回で1日も改善しない
        }
        val logs = listOf(MirrorLog(tag = "AltOptPolish",
            message = "交互最適化(日ブロック・weekly込み割当): total ${before.total}->${bestRep.total} 採用${applied}日 (${lastSweep}スイープ)"))
        return DayAssignResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }


}
