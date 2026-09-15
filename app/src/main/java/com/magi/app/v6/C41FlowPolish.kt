package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * [3.511.5/測定中] c41/c41s（群/日レンジ, SOFT, 重み1）専用の決定的 min-cost-flow 研磨パス（backlog #12(b)）。
 * 違反している (群, シフト, 日) ごとに、その日のその群の可動メンバーだけを [FlexibleDayFlow]（RangePolish が
 * low/high/apt/covU/covO 用に使う汎用ソルバー、無改造で再利用）へ渡し、群内の再配分で [l,u] へ近づける誘導コストを与える。
 * c41 は二値しきい値（z<l または z>u で+1）のため限界費用列は単調でない（真に凸ではない）＝MCMF は厳密解を保証しない
 * ヒューリスティックな誘導。採否は必ず [UnifiedViolationChecker] のフル評価＋[adoptionGate]（keep-best）で決まるため、
 * 誘導が最適でなくても悪化した候補は機械的に弾かれる（[RangePolish.applyRangePolish] の手Fと同じ安全網）。
 *
 * c42/c42s（群ペア）は片側を固定するヤコビ近似＋対称2試行が要り実装・検証の面が倍になるため、まず c41/c41s だけを
 * 独立に測る（backlog #12(b) 残課題として c42/c42s は別途）。
 */
internal object C41FlowPolish {
    fun applyC41FlowPolish(
        state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult {
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        fun dayPenalty(k: Int, j: Int, q: Int): Long = p.covUCell(k, j, q).toLong() * 10000L + p.covOCell(k, j, q).toLong() * 10L  // [3.522.0]
        // [発見] c41 の真の評価は二値（[l,u]を外れたら+1、幅に依らない）＝非凸。MCMFの並列辺トリックは
        //   限界費用が非減少（凸）でないと「q番目の枠」の意味が壊れ、達成前に達成後の得点を先取りする
        //   （実測: 生の二値差分をそのまま使うと2人目の恩恵を1人だけの状態に誤帰属し、常に採用0になった）。
        //   RangePolish の low/high と同じ「幅に応じた誘導コスト」（真に凸）へ置き換える＝最終採否は
        //   常に UnifiedViolationChecker のフル評価で決まるため、誘導が近似でも正しさは揺るがない。
        fun rangeGuideCost(z: Int, lo: Int, hi: Int): Long = maxOf(lo - z, 0).toLong() + maxOf(z - hi, 0).toLong()

        /** [groupOf] は c41=[Problem.sgrp]／c41s=[Problem.ssk] を渡す（判定式は同型、群の出所だけが違う）。 */
        fun tryFamily(cons: List<C41>, groupOf: (Int) -> Int): Boolean {
            var improvedAny = false
            for (c in cons) {
                if (shouldStop()) return improvedAny
                for (j in 0 until p.T) {
                    if (shouldStop()) return improvedAny
                    val members = (0 until p.S).filter { groupOf(it) == c.groupIdx }
                    var z = 0
                    for (i in members) if (work[i][j] == c.shiftIdx) z++
                    if (z in c.l..c.u) continue
                    val movableMembers = members.filter { movable(it, j) }
                    if (movableMembers.isEmpty()) continue
                    // シフトごとの「この群の可動メンバー以外」のベースカウント（フローが動かす分=qを足すと全体カウント）。
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
                                else -> 1L   // 無償で動かす手を過剰生成しない程度の定常コスト
                            }
                        }
                    }
                    val marginal = Array(p.K) { k ->
                        LongArray(movableMembers.size) { q0 ->
                            val q = q0 + 1
                            var m = dayPenalty(k, j, baseCount[k] + q) - dayPenalty(k, j, baseCount[k] + q - 1)
                            if (k == c.shiftIdx) m += (rangeGuideCost(baseCount[k] + q, c.l, c.u) - rangeGuideCost(baseCount[k] + q - 1, c.l, c.u)) * 1000L
                            m * 1024L
                        }
                    }
                    val solved = FlexibleDayFlow.solve(staffCost, marginal) ?: continue
                    if (solved.assignment.indices.all { solved.assignment[it] == oldDay[it] }) continue
                    val workBefore = work.copy2D()
                    for (idx in movableMembers.indices) work[movableMembers[idx]][j] = solved.assignment[idx]
                    val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                    val gate = adoptionGate(p, workBefore, work, rep, bestRep, pinBlocks)
                    if (gate.accepted) { bestRep = rep; applied++; improvedAny = true }
                    else for (idx in movableMembers.indices) work[movableMembers[idx]][j] = oldDay[idx]
                }
            }
            return improvedAny
        }

        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            val a = tryFamily(p.cons41) { i -> p.sgrp[i] }
            val b = tryFamily(p.cons41s) { i -> p.ssk[i] }
            pass++
            if (!a && !b) break
        }
        val logs = listOf(MirrorLog(tag = "C41FlowPolish", message = "群/日レンジ(c41/c41s)フロー研磨: total ${before.total}->${bestRep.total} 採用${applied}回"))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }
}
