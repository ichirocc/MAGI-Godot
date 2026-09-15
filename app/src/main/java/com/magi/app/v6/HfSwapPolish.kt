package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random

/**
 * HF67(職員間直接交換)/HF66(職員内再配分)/HF70(異常検知)の3パスと専用ヘルパー。
 * [V6HotfixPasses] から抽出（責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。
 * ロジックは一切変更していない。
 *
 * - [applyHF67InterStaffSwap]：被覆不足/過剰を職員間の直接交換で解消（希望固定は不変）。
 * - [applyHF66IntraStaffRedistribution]：職員内の担当替えで低/高(個人回数)を再配分。
 * - [detectHF70Anomalies]：post-optimization 後の異常（担当外セル等）を検知するだけの診断。
 * - [SwapCandidate]/[MoveCandidate]/[trySwapShiftBetweenStaff]/[localPairwiseStaffSwap]：
 *   HF67/HF66 専用の候補生成ヘルパー。
 * - [invalidAssignmentCount]：detectHF70Anomalies 専用の集計ヘルパー。
 *
 * `localBestImprovement`（HF80の戦略的振動が使う）と `effectiveHi`（DayAssignmentPolish.kt とも
 * 共有）は [V6HotfixPasses] 側に残置し、ここからは完全修飾で参照する（写しを作らず単一ソースを維持）。
 */
internal object HfSwapPolish {
    fun applyHF67InterStaffSwap(
        state: MagiState, schedule: Array<IntArray>, maxSwaps: Int = 30, shouldStop: () -> Boolean = { false }, deadlineMs: Long = Long.MAX_VALUE,
        quantitativeRangeEval: Boolean = false,
    ): HF67Result {
        val p = cachedProblem(state, quantitativeRangeEval)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var current = before
        var swaps = 0
        var shortage = 0
        var capacity = 0
        var rollback = 0
        // [3.282.0/新領域ログ監査] 兄弟の HF66 は専用上限(deadlineMs)＋内側スキャンの outOfTime 確認
        //   （2.65.0/3.161.0 の確立方針）を持つのに、HF67 だけ手ごとの shouldStop のみ＝候補ごとフル check の
        //   内側スキャン（k×lows×highs）とフォールバック（全ペア×全日の総当たり=実機で rollback=264 の正体）が
        //   締切後も走り切る非対称だった。同型の締切確認を追加（keep-best のため途中中断でも退化なし）。
        fun outOfTime() = shouldStop() || EngineClock.nowMs() >= deadlineMs

        while (swaps < maxSwaps) {
            if (outOfTime()) break
            val counts = countMatrix(p, work)
            var best: SwapCandidate? = null
            var bestReport: ViolationReport? = null
            scan@ for (k in 0 until p.K) {
                if (outOfTime()) break@scan
                val lows = ArrayList<Int>()
                val highs = ArrayList<Int>()
                for (i in 0 until p.S) {
                    if (p.canDo(i, k) && p.rangeLo[i][k] != Int.MIN_VALUE && counts[i][k] < p.rangeLo[i][k]) lows.add(i)
                    if (counts[i][k] > V6HotfixPasses.effectiveHi(p, i, k)) highs.add(i)
                }
                for (to in lows) {
                    if (outOfTime()) break@scan
                    for (from in highs) {
                        if (to == from) continue
                        val cand = trySwapShiftBetweenStaff(p, work, from, to, k) ?: continue
                        val rep = UnifiedViolationChecker.check(state, cand.first, quantitativeRangeEval)
                        val ref = bestReport ?: current
                        // [厳密ピン保護/3.522.0] 職員間交換はfrom/toの2者の回数を同時に変えうるため他パスと同じガード。
                        if (betterReport(rep, ref) && !exactPinRegression(p, work, cand.first)) {
                            best = cand.second
                            bestReport = rep
                        }
                    }
                }
            }
            if (best == null || bestReport == null) break
            val b = best
            val next = work.copy2D()
            val tmp = next[b.fromStaff][b.fromDay]
            next[b.fromStaff][b.fromDay] = next[b.toStaff][b.toDay]
            next[b.toStaff][b.toDay] = tmp
            work = next
            current = bestReport
            swaps++
            shortage++
            if (current.soft < before.soft) capacity++
        }
        if (swaps == 0 && !outOfTime()) {
            val improved = localPairwiseStaffSwap(state, p, work, maxSwaps, { outOfTime() }, quantitativeRangeEval)
            work = improved.first
            swaps = improved.second
            rollback = improved.third
            current = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
            capacity = swaps
        }
        val logs = listOf(MirrorLog(tag = "HF67", message = "inter-staff swap applied=$swaps rollback=$rollback total ${before.total}->${current.total}"))
        return HF67Result(work, before.total, current.total, swaps, shortage, capacity, rollback, logs)
    }


    fun applyHF66IntraStaffRedistribution(
        state: MagiState, schedule: Array<IntArray>, maxMoves: Int = 30, shouldStop: () -> Boolean = { false }, deadlineMs: Long = Long.MAX_VALUE,
        quantitativeRangeEval: Boolean = false,
    ): HF66Result {
        val p = cachedProblem(state, quantitativeRangeEval)
        var work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var current = before
        var moves = 0
        var shortageMoves = 0
        var capacityMoves = 0
        var rollback = 0
        // [残予算ガード] shouldStop(全体締切)に加え HF66 専用の時間上限(deadlineMs)も尊重する。1手の全候補
        //   スキャンは候補ごとにフル check するため高コスト。手ごとだけでなく内側ループでも締切を確認し、
        //   締切後に1手分のスキャンを走り切って後段の研磨パスを押し出す(=予算超過で打ち切らせる)のを防ぐ。
        //   keep-best のため途中中断しても解は退化しない(採用は isBetter な bestMove のみ)。
        fun outOfTime() = shouldStop() || EngineClock.nowMs() >= deadlineMs

        while (moves < maxMoves) {
            if (outOfTime()) break
            val counts = countMatrix(p, work)
            var bestMove: MoveCandidate? = null
            var bestReport: ViolationReport? = null
            scan@ for (i in 0 until p.S) {
                if (outOfTime()) break@scan
                val lows = ArrayList<Int>()
                val highs = ArrayList<Int>()
                for (k in 0 until p.K) {
                    if (p.canDo(i, k) && p.rangeLo[i][k] != Int.MIN_VALUE && counts[i][k] < p.rangeLo[i][k]) lows.add(k)
                    if (counts[i][k] > V6HotfixPasses.effectiveHi(p, i, k)) highs.add(k)
                }
                for (want in lows) for (give in highs) {
                    if (outOfTime()) break@scan
                    for (j in 0 until p.T) {
                        if (work[i][j] != give || p.wishLocked(i, j)) continue
                        val cand = work.copy2D()
                        cand[i][j] = want
                        val rep = UnifiedViolationChecker.check(state, cand, quantitativeRangeEval)
                        // [厳密ピン保護/3.522.0] 職員内の担当替えも自身の回数を変えるため他パスと同じガード。
                        if (betterReport(rep, bestReport ?: current) && !exactPinRegression(p, work, cand)) {
                            bestMove = MoveCandidate(i, j, give, want)
                            bestReport = rep
                        }
                    }
                }
            }
            val mv = bestMove ?: break
            work[mv.staff][mv.day] = mv.toShift
            current = bestReport ?: UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
            moves++
            shortageMoves++
            if (current.soft < before.soft) capacityMoves++
        }
        if (moves == 0 && !outOfTime()) {
            val rng = Random(0x66L)
            var t = 0
            while (t < maxMoves) {
                if (outOfTime()) break
                if (p.S > 0 && p.T > 0) {
                    val cand = work.copy2D()
                    val i = rng.nextInt(p.S)
                    val j = rng.nextInt(p.T)
                    if (!p.wishLocked(i, j)) {
                        val allowed = p.allowedShiftsForStaff(i)
                        if (allowed.isNotEmpty()) {
                            val old = cand[i][j]
                            cand[i][j] = allowed[rng.nextInt(allowed.size)]
                            if (cand[i][j] != old) {
                                val rep = UnifiedViolationChecker.check(state, cand, quantitativeRangeEval)
                                if (betterReport(rep, current) && !exactPinRegression(p, work, cand)) {
                                    work = cand
                                    current = rep
                                    moves++
                                    capacityMoves++
                                } else {
                                    rollback++
                                }
                            }
                        }
                    }
                }
                t++
            }
        }
        val logs = listOf(MirrorLog(tag = "HF66", message = "intra-staff redistribution applied=$moves rollback=$rollback total ${before.total}->${current.total}"))
        return HF66Result(work, before.total, current.total, moves, shortageMoves, capacityMoves, rollback, logs)
    }


    fun detectHF70Anomalies(
        state: MagiState,
        schedule: Array<IntArray>,
        algoName: String,
        report: ViolationReport = UnifiedViolationChecker.check(state, schedule),
        quantitativeRangeEval: Boolean = false,
    ): HF70Result {
        val invalid = invalidAssignmentCount(state, schedule, quantitativeRangeEval)
        val impossible = V6SanityPort.detectImpossibleWishes(state).size
        val hardCore = report.hard - (report.breakdown["pref"] ?: 0)
        val issues = ArrayList<String>()
        if (invalid > 0) issues.add("担当不可/範囲外配置 $invalid 件")
        if (impossible > 0) issues.add("不可能希望 $impossible 件")
        if (hardCore > 0) issues.add("希望以外HARD $hardCore 件")
        val msg = if (issues.isEmpty()) "HF70: $algoName 異常なし" else "HF70: ${issues.joinToString(" / ")}"
        val advice = if (issues.isEmpty()) "" else "設定(担当範囲), 希望, 必要人数, 連勤禁止条件を確認してください"
        val level = if (issues.isEmpty()) "I" else "W"
        val logs = listOf(MirrorLog(level = level, tag = "HF70", message = msg + if (advice.isNotBlank()) " — $advice" else ""))
        return HF70Result(issues.size, msg, advice, logs)
    }


    private data class SwapCandidate(val fromStaff: Int, val fromDay: Int, val toStaff: Int, val toDay: Int)

    private data class MoveCandidate(val staff: Int, val day: Int, val fromShift: Int, val toShift: Int)


    private fun trySwapShiftBetweenStaff(p: Problem, schedule: Array<IntArray>, from: Int, to: Int, shift: Int): Pair<Array<IntArray>, SwapCandidate>? {
        val fromDays = ArrayList<Int>()
        val toDays = ArrayList<Int>()
        for (j in 0 until p.T) {
            if (schedule[from][j] == shift && !p.wishLocked(from, j)) fromDays.add(j)
            if (schedule[to][j] != shift && !p.wishLocked(to, j) && p.mayPlace(to, shift) && p.mayPlace(from, schedule[to][j])) toDays.add(j)
        }
        for (jf in fromDays) for (jt in toDays) {
            val cand = schedule.copy2D()
            val tmp = cand[from][jf]
            cand[from][jf] = cand[to][jt]
            cand[to][jt] = tmp
            return Pair(cand, SwapCandidate(from, jf, to, jt))
        }
        return null
    }


    private fun localPairwiseStaffSwap(
        state: MagiState, p: Problem, schedule: Array<IntArray>, maxSwaps: Int, shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): Triple<Array<IntArray>, Int, Int> {
        var work = schedule.copy2D()
        var current = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var applied = 0
        var rollback = 0
        loop@ for (i in 0 until p.S) for (i2 in i + 1 until p.S) for (j in 0 until p.T) {
            if (applied >= maxSwaps || shouldStop()) break@loop
            if (p.wishLocked(i, j) || p.wishLocked(i2, j)) continue
            val a = work[i][j]
            val b = work[i2][j]
            if (a == b || !p.mayPlace(i, b) || !p.mayPlace(i2, a)) continue
            val cand = work.copy2D()
            cand[i][j] = b
            cand[i2][j] = a
            val rep = UnifiedViolationChecker.check(state, cand, quantitativeRangeEval)
            if (betterReport(rep, current) && !exactPinRegression(p, work, cand)) {
                work = cand
                current = rep
                applied++
            } else {
                rollback++
            }
        }
        return Triple(work, applied, rollback)
    }


    private fun invalidAssignmentCount(state: MagiState, schedule: Array<IntArray>, quantitativeRangeEval: Boolean = false): Int {
        val p = cachedProblem(state, quantitativeRangeEval)
        val s = normalizeSchedule(schedule, p)
        var n = 0
        for (i in 0 until p.S) for (j in 0 until p.T) {
            val k = s[i][j]
            if (k !in 0 until p.K || !p.canDo(i, k)) n++
        }
        return n
    }
}
