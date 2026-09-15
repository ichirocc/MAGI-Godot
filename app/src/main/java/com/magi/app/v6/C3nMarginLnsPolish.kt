package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random

/**
 * [C3n(禁止連続)の前後余白込みLNS] `C3FamilyPolish.applyC3nPolish` は違反パターンがまたぐ日だけを
 * 1セルずつ独立に付け替えるため、余白日（前後の非違反日）を含めた複数セル同時destroy-rebuildでしか
 * 解けない局面に構造的に届かない。評価式・重みは一切変更しない＝新しい探索候補生成パスのみで、
 * 最終採否は既存の `UnifiedViolationChecker` + `adoptionGate`（keep-best）に委ねる。
 */
internal object C3nMarginLnsPolish {
    fun apply(
        state: MagiState, schedule: Array<IntArray>,
        marginDays: Int = 2,
        maxRestartsPerAnchor: Int = 6,
        maxEvaluations: Int = 3_000,
        maxPasses: Int = 3,
        shouldStop: () -> Boolean = { false },
        seed: Long = 0xC3E9L,
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult {
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        val tag = "C3nMarginLNS"
        if (p.cons3n.isEmpty()) {
            return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, 0,
                listOf(MirrorLog(tag = tag, message = "cons3nなし=スキップ")))
        }
        val rng = Random(seed)
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        val rejectCulprits = RejectCulpritStats()
        var evaluated = 0
        var skippedSmall = 0   // destroy集合が2未満(=1セル経路と重複)でスキップした回数
        var pass = 0
        while (pass < maxPasses && evaluated < maxEvaluations) {
            if (shouldStop()) break
            var improved = false
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
            val anchors = ArrayList<Pair<Int, Int>>()
            for ((key, fams) in rep0.cellFamilies) {
                if ("vio-c3n" !in fams) continue
                val parts = key.split(",")
                val i = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
                anchors.add(i to j)
            }
            if (anchors.isEmpty()) break
            for ((i, j) in anchors) {
                if (shouldStop() || evaluated >= maxEvaluations) break
                if (i !in 0 until p.S || j !in 0 until p.T) continue
                val alts = p.allowedShiftsForStaff(i)
                if (alts.isEmpty()) continue
                val patternDays = C3nRowScan(p, work[i]).coveringDays(j)
                if (patternDays.isEmpty()) continue
                val destroySet = sortedSetOf<Int>()
                for (d in patternDays) {
                    val lo = (d - marginDays).coerceAtLeast(0)
                    val hi = (d + marginDays).coerceAtMost(p.T - 1)
                    for (dd in lo..hi) destroySet.add(dd)
                }
                destroySet.retainAll { movable(i, it) }
                if (destroySet.size < 2) { skippedSmall++; continue }
                val destroyDays = destroySet.toIntArray()

                var acceptedHere = false
                for (restart in 0 until maxRestartsPerAnchor) {
                    if (shouldStop() || evaluated >= maxEvaluations || acceptedHere) break
                    val order = destroyDays.copyOf()
                    for (x in order.size - 1 downTo 1) { val y = rng.nextInt(x + 1); val t = order[x]; order[x] = order[y]; order[y] = t }
                    // [貪欲再構築] 未確定日はまだ現在値のまま、確定済みの日は前段の選択を反映した行で
                    //   firesAfterSet を最小化する代替を選ぶ。現在値も候補に含め、同点は rng でタイブレーク。
                    val tentative = work[i].copyOf()
                    for (day in order) {
                        val scan = C3nRowScan(p, tentative)
                        var bestFires = Int.MAX_VALUE
                        val bestAlts = ArrayList<Int>()
                        val curAtDay = tentative[day]
                        for (alt in alts) {
                            val f = scan.firesAfterSet(day, alt)
                            if (f < bestFires) { bestFires = f; bestAlts.clear(); bestAlts.add(alt) }
                            else if (f == bestFires) bestAlts.add(alt)
                        }
                        val f0 = scan.firesAfterSet(day, curAtDay)
                        if (f0 < bestFires) { bestFires = f0; bestAlts.clear(); bestAlts.add(curAtDay) }
                        else if (f0 == bestFires && curAtDay !in bestAlts) bestAlts.add(curAtDay)
                        tentative[day] = bestAlts[rng.nextInt(bestAlts.size)]
                    }

                    // 被覆判定は行変更を確定させる前(i がまだ旧シフトに残っている状態)で行う
                    //   （既存パスの needsChain 判定と同じ規約: cnt は i を含む現在人数）。
                    val needsChainDay = BooleanArray(destroyDays.size)
                    for (idx in destroyDays.indices) {
                        val day = destroyDays[idx]
                        val oldK = work[i][day]
                        val newK = tentative[day]
                        if (oldK == newK || oldK !in 0 until p.K) continue
                        var cnt = 0
                        for (s in 0 until p.S) if (work[s][day] == oldK) cnt++
                        needsChainDay[idx] = p.covUCell(oldK, day, cnt - 1) > p.covUCell(oldK, day, cnt)
                    }
                    val workBefore = work.copy2D()
                    for (idx in destroyDays.indices) work[i][destroyDays[idx]] = tentative[destroyDays[idx]]
                    var chainOk = true
                    for (idx in destroyDays.indices) {
                        if (!needsChainDay[idx]) continue
                        val day = destroyDays[idx]
                        val oldK = workBefore[i][day]
                        val chain = findCovUChain(p, work, oldK, day, rng, exclude = i,
                            rangeAvoid = { st, fk -> exceedsOwnRangeHi(p, work, st, fk) })
                        if (chain == null) { chainOk = false; break }
                        chain.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
                    }
                    if (!chainOk) {
                        for (s in 0 until p.S) work[s] = workBefore[s].copyOf()
                        continue
                    }
                    evaluated++
                    val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                    val gate = adoptionGate(p, workBefore, work, rep, bestRep, pinBlocks)
                    if (gate.accepted) {
                        bestRep = rep; applied++; improved = true; acceptedHere = true
                    } else {
                        rejectCulprits.record(rep, bestRep, gate.pinBad)
                        for (s in 0 until p.S) work[s] = workBefore[s].copyOf()
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val stuckNames = stuckStaffNames(state, bestRep.cellFamilies, "vio-c3n")
        val logs = listOf(MirrorLog(tag = tag,
            message = "c3n禁止連続(前後余白込みLNS)研磨: c3n ${before.breakdown["c3n"] ?: 0}->${bestRep.breakdown["c3n"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                " 正式評価$evaluated destroy集合2未満で対象外$skippedSmall" +
                (if (applied == 0 && (before.breakdown["c3n"] ?: 0) > 0) " [頭打ち=改善手なし]" else "") +
                rejectCulprits.summary() +
                (if (stuckNames.isNotEmpty()) " 残存: ${stuckNames.joinToString(", ")}" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks)
    }
}
