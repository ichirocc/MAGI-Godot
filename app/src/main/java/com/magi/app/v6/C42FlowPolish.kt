package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * [測定中] c42/c42s（群ペア禁止, SOFT, 重み1）専用の決定的 min-cost-flow 研磨パス（backlog #12(b) 残課題）。
 * [FlexibleDayFlow] を群内サブセットへ適用する点は [C41FlowPolish] と同じだが、c42 は2つの (群,シフト) ペアが
 * 同時に絡む（`c42PairCount(sameSet,n1,n2)`）ため片方の群だけを流すと相手側の目的値も変わる。片側固定の
 * ヤコビ近似＋対称2試行（sameSet時は同じ変数なので1試行）で扱う＝**真の相互最適ではない**が、最終採否は必ず
 * [UnifiedViolationChecker] のフル評価＋[adoptionGate]（keep-best）で決まるため誘導が近似でも正しさは揺るがない。
 *
 * c42 の真の評価 `c42PairCount` は真に凸（sameSet時 n1*(n1-1)/2、非sameSet時は n2 固定なら n1 について線形）
 * なので、c41 で要った非凸回避のガイド費用置換（`rangeGuideCost`）は不要——真の限界費用の差分をそのまま渡せる。
 */
internal object C42FlowPolish {
    fun applyC42FlowPolish(
        state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult {
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        fun dayPenalty(k: Int, j: Int, q: Int): Long = p.covUCell(k, j, q).toLong() * 10000L + p.covOCell(k, j, q).toLong() * 10L  // [3.522.0]

        /** 片側(g1側 or g2側)の候補解。`board`はその日(j)だけ書き換えた盤面全体（未採用ならそのまま捨てる）。 */
        class Candidate(val board: Array<IntArray>, val rep: ViolationReport)

        /** [groupOf] は c42=[Problem.sgrp]／c42s=[Problem.ssk] を渡す（判定式は同型、群の出所だけが違う）。
         *  `members`側を`target`シフトへ寄せる誘導を1本作る。`otherCount`は固定側の人数(スナップショット)、
         *  `sameSet`のときは`target`シフト内の人数(z)のみで完結し`otherCount`は使わない。 */
        fun tryMove(j: Int, members: List<Int>, target: Int, otherCount: Int, sameSet: Boolean): Candidate? {
            val movableMembers = members.filter { movable(it, j) }
            if (movableMembers.isEmpty()) return null
            // シフトごとの「この試行で動かす面子(movableMembers)以外」のベースカウント(全体・c41系と同じ形)。
            val baseCount = IntArray(p.K)
            for (k in 0 until p.K) {
                var cnt = 0
                for (i in 0 until p.S) if (work[i][j] == k && i !in movableMembers) cnt++
                baseCount[k] = cnt
            }
            val oldDay = IntArray(movableMembers.size) { work[movableMembers[it]][j] }
            val staffCost = Array(movableMembers.size) { idx ->
                val i = movableMembers[idx]
                val oldK = oldDay[idx]
                LongArray(p.K) { newK ->
                    when {
                        newK == oldK -> 0L
                        !p.mayPlace(i, newK) || p.makesForbiddenRun(work, i, j, newK) -> FlexibleDayFlow.INF
                        else -> 1L
                    }
                }
            }
            val marginal = Array(p.K) { k ->
                LongArray(movableMembers.size) { q0 ->
                    val q = q0 + 1
                    var m = dayPenalty(k, j, baseCount[k] + q) - dayPenalty(k, j, baseCount[k] + q - 1)
                    if (k == target) {
                        val z0 = baseCount[k] + q - 1; val z1 = baseCount[k] + q
                        val pc = if (sameSet) c42PairCount(true, z1, z1) - c42PairCount(true, z0, z0)
                        else c42PairCount(false, z1, otherCount) - c42PairCount(false, z0, otherCount)
                        m += pc * 1000L
                    }
                    m * 1024L
                }
            }
            val solved = FlexibleDayFlow.solve(staffCost, marginal) ?: return null
            if (solved.assignment.indices.all { solved.assignment[it] == oldDay[it] }) return null
            val board = work.copy2D()
            for (idx in movableMembers.indices) board[movableMembers[idx]][j] = solved.assignment[idx]
            return Candidate(board, UnifiedViolationChecker.check(state, board, quantitativeRangeEval))
        }

        fun tryFamily(cons: List<C42>, groupOf: (Int) -> Int): Boolean {
            var improvedAny = false
            for (c in cons) {
                if (shouldStop()) return improvedAny
                val sameSet = c.g1 == c.g2 && c.s1 == c.s2
                for (j in 0 until p.T) {
                    if (shouldStop()) return improvedAny
                    var n1 = 0; var n2 = 0
                    for (i in 0 until p.S) {
                        if (groupOf(i) == c.g1 && work[i][j] == c.s1) n1++
                        if (groupOf(i) == c.g2 && work[i][j] == c.s2) n2++
                    }
                    if (c42PairCount(sameSet, n1, n2) == 0L) continue
                    val members1 = (0 until p.S).filter { groupOf(it) == c.g1 }
                    // 対称2試行: g1側を流す間はn2固定、g2側を流す間はn1固定（sameSetは同じ変数なので1試行のみ）。
                    val candidates = if (sameSet) {
                        listOfNotNull(tryMove(j, members1, c.s1, 0, true))
                    } else {
                        val members2 = (0 until p.S).filter { groupOf(it) == c.g2 }
                        listOfNotNull(
                            tryMove(j, members1, c.s1, n2, false),
                            tryMove(j, members2, c.s2, n1, false),
                        )
                    }
                    var winner: Candidate? = null
                    for (cand in candidates) {
                        val gate = adoptionGate(p, work, cand.board, cand.rep, bestRep, pinBlocks)
                        if (gate.accepted && (winner == null || betterReport(cand.rep, winner.rep))) winner = cand
                    }
                    if (winner != null) {
                        for (i in 0 until p.S) work[i][j] = winner.board[i][j]
                        bestRep = winner.rep; applied++; improvedAny = true
                    }
                }
            }
            return improvedAny
        }

        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            val a = tryFamily(p.cons42) { i -> p.sgrp[i] }
            val b = tryFamily(p.cons42s) { i -> p.ssk[i] }
            pass++
            if (!a && !b) break
        }
        val logs = listOf(MirrorLog(tag = "C42FlowPolish", message = "群ペア禁止(c42/c42s)フロー研磨: total ${before.total}->${bestRep.total} 採用${applied}回"))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }
}
