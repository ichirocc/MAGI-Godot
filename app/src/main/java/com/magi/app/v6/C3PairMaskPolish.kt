package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random

/** 連続規則（c3 系）違反の職員と相手のあいだで、**連続でなくてよい 1〜3 日**の割当を同日交換する研磨（日別人数は不変）。
 *  候補は違反日を含み 1→2→3 日の順に対ごとのラウンドロビンで評価。採用は c3 系加重が減り共通ゲートを通るときだけ。 */
internal object C3PairMaskPolish {
    private val c3Fams = listOf("c3", "c3n", "c3m", "c3mn")
    private val c3Marks = setOf("vio-c3", "vio-c3n", "vio-c3m", "vio-c3mn")

    private fun c3Weighted(r: ViolationReport): Double = c3Fams.sumOf { (r.breakdown[it] ?: 0) * MirrorKeys.weightOf(it) }

    fun apply(
        state: MagiState, schedule: Array<IntArray>, maxEvaluations: Int = 3_000, window: Int = 7,
        shouldStop: () -> Boolean = { false }, seed: Long = 0xC3AA1L, quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult {
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        val tag = "C3PairMask"
        if (p.cons3.isEmpty() && p.cons3n.isEmpty() && p.cons3m.isEmpty() && p.cons3mn.isEmpty() || p.S < 2) {
            return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, 0, listOf(MirrorLog(tag = tag, message = "c3系なし=スキップ")))
        }
        val rng = Random(seed)
        val rejects = RejectCulpritStats()
        var evaluated = 0
        var screened = 0   // c3n 枝刈りで正式評価を省いた候補
        val adoptedBySize = IntArray(4)
        val tried = HashSet<Long>()
        fun key(a: Int, b: Int, mask: Long): Long = (minOf(a, b).toLong() shl 56) or (maxOf(a, b).toLong() shl 48) or mask
        fun swappable(i: Int, i2: Int, j: Int): Boolean {
            val a = work[i][j]; val b = work[i2][j]
            return a != b && a in 0 until p.K && b in 0 until p.K && !p.wishLocked(i, j) && !p.wishLocked(i2, j) && p.mayPlace(i, b) && p.mayPlace(i2, a)
        }
        fun swap(i: Int, i2: Int, days: IntArray) { for (j in days) { val t = work[i][j]; work[i][j] = work[i2][j]; work[i2][j] = t } }

        var rounds = 0
        while (!shouldStop() && evaluated < maxEvaluations && rounds < 8) {
            rounds++
            // アンカー: c3 系違反セル（職員 → 日の集合）
            val anchorDays = HashMap<Int, java.util.TreeSet<Int>>()
            for ((k, fams) in bestRep.cellFamilies) {
                if (fams.none { it in c3Marks }) continue
                val parts = k.split(","); val i = parts.getOrNull(0)?.toIntOrNull() ?: continue; val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
                if (i in 0 until p.S && j in 0 until p.T) anchorDays.getOrPut(i) { java.util.TreeSet() }.add(j)
            }
            if (anchorDays.isEmpty()) break
            // 違反職員×相手のラウンドロビン。相手の順は seed で乱択（同型解の多様化）。
            val partners = IntArray(p.S) { it }
            for (x in p.S - 1 downTo 1) { val y = rng.nextInt(x + 1); val t = partners[x]; partners[x] = partners[y]; partners[y] = t }
            data class Pair2(val i: Int, val i2: Int, val anchors: IntArray)
            val pairs = ArrayList<Pair2>()
            for ((i, ds) in anchorDays.entries.sortedBy { it.key }) for (i2 in partners) if (i2 != i) pairs.add(Pair2(i, i2, ds.toIntArray()))
            var improved = false
            for (size in 1..3) {
                if (improved || shouldStop() || evaluated >= maxEvaluations) break
                // 各対の候補列（アンカー日を含む size 日の集合、窓 ±window 内、非連続可）を遅延生成
                val iters = pairs.map { pr -> masksOf(pr.anchors, size, window, p.T).iterator() to pr }.toMutableList()
                while (iters.isNotEmpty() && !improved && !shouldStop() && evaluated < maxEvaluations) {
                    val it2 = iters.iterator()
                    while (it2.hasNext() && !improved && !shouldStop() && evaluated < maxEvaluations) {
                        val (masks, pr) = it2.next()
                        if (!masks.hasNext()) { it2.remove(); continue }
                        val days = masks.next()
                        var bits = 0L; for (d in days) bits = bits or (1L shl d)
                        if (!tried.add(key(pr.i, pr.i2, bits))) continue
                        if (days.any { !swappable(pr.i, pr.i2, it) }) continue
                        // [c3n 枝刈り] 同日交換で動く HARD 族は c3n だけ（担当可・希望固定・被覆は不変）。両行の発火数の合計が
                        //   増える候補は必ず却下されるので、正式評価の前に行走査で落とす。
                        val firesBefore = C3nRowScan(p, work[pr.i]).fires() + C3nRowScan(p, work[pr.i2]).fires()
                        swap(pr.i, pr.i2, days)
                        if (C3nRowScan(p, work[pr.i]).fires() + C3nRowScan(p, work[pr.i2]).fires() > firesBefore) { swap(pr.i, pr.i2, days); screened++; continue }
                        swap(pr.i, pr.i2, days)
                        val workBefore = work.copy2D()
                        swap(pr.i, pr.i2, days)
                        evaluated++
                        val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                        val gate = if (c3Weighted(rep) < c3Weighted(bestRep)) adoptionGate(p, workBefore, work, rep, bestRep, pinBlocks) else Adoption(false, false)
                        if (gate.accepted) {
                            bestRep = rep; applied++; adoptedBySize[size]++; improved = true
                        } else {
                            rejects.record(rep, bestRep, gate.pinBad)
                            swap(pr.i, pr.i2, days)
                        }
                    }
                }
            }
            if (!improved) break
        }
        val c3b = c3Weighted(before).toLong(); val c3a = c3Weighted(bestRep).toLong()
        val logs = listOf(MirrorLog(tag = tag, message =
            "連続規則(c3系)選択日ペア交換: c3系加重 $c3b->$c3a / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard}" +
                " 正式評価$evaluated c3n枝刈り$screened 採用$applied(1日${adoptedBySize[1]}/2日${adoptedBySize[2]}/3日${adoptedBySize[3]})" +
                (if (applied == 0 && c3b > 0) " [頭打ち=現在の探索範囲では改善手なし]" else "") + rejects.summary()))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks)
    }

    /** アンカー日を必ず 1 つ含む size 日の集合（昇順・重複なし・窓 ±window 内）を、アンカーに近い順で列挙する。 */
    internal fun masksOf(anchors: IntArray, size: Int, window: Int, T: Int): Sequence<IntArray> = sequence {
        val seen = HashSet<Long>()
        for (a in anchors) {
            val lo = maxOf(0, a - window); val hi = minOf(T - 1, a + window)
            val others = (lo..hi).filter { it != a }.sortedBy { kotlin.math.abs(it - a) }
            when (size) {
                1 -> yieldIfNew(seen, intArrayOf(a))
                2 -> for (e in others) yieldIfNew(seen, intArrayOf(a, e).sortedArray())
                else -> for (x in others.indices) for (y in x + 1 until others.size) yieldIfNew(seen, intArrayOf(a, others[x], others[y]).sortedArray())
            }
        }
    }

    private suspend fun SequenceScope<IntArray>.yieldIfNew(seen: HashSet<Long>, days: IntArray) {
        var bits = 0L; for (d in days) bits = bits or (1L shl d)
        if (seen.add(bits)) yield(days)
    }
}
