package com.magi.app.v6

/**
 * Web版 ALNS の Guided Local Search (GLS) ペナルティ（Voudouris & Tsang）。移植。
 *
 * `penalty[i][j][k]` を保持し、受理判定の拡張コストに `lambda * Σ penalty(セルの割当)` を加える。
 * 停滞時に「違反に寄与している割当」を util = severity/(1+penalty) が最大のものから penalty+1 して、
 * 探索をその局所最適から遠ざける。グローバル最良は生スコアで別管理する前提（本クラスは受理バイアスのみ）。
 */
class GlsPenalty(
    private val staff: Int,
    private val days: Int,
    private val shifts: Int,
    val lambda: Double = 200.0,
) {
    private val pen = HashMap<Int, Int>()
    private var kicks = 0

    private fun key(i: Int, j: Int, k: Int) = (i * days + j) * shifts + k

    fun penaltyOf(i: Int, j: Int, k: Int): Int = pen[key(i, j, k)] ?: 0
    fun kickCount(): Int = kicks

    /** 受理判定に加える拡張コスト寄与 = lambda * Σ penalty(i, j, schedule[i][j])。 */
    fun augment(schedule: Array<IntArray>): Double {
        if (pen.isEmpty()) return 0.0
        var sum = 0L
        for (i in 0 until staff) {
            val row = schedule.getOrNull(i) ?: continue
            for (j in 0 until days) {
                val k = row.getOrNull(j) ?: continue
                if (k in 0 until shifts) pen[key(i, j, k)]?.let { sum += it }
            }
        }
        return lambda * sum
    }

    /**
     * 違反セル集合のうち util = severity/(1+penalty) が最大の割当を1つ選び penalty+1。
     * @return 強化したら true（候補が無ければ false）。
     */
    fun penalizeWorst(
        schedule: Array<IntArray>,
        cells: Collection<Pair<Int, Int>>,
        severity: (Int, Int) -> Double = { _, _ -> 1.0 },
    ): Boolean {
        var bestKey = -1
        var bestUtil = -1.0
        for ((i, j) in cells) {
            if (i !in 0 until staff || j !in 0 until days) continue
            val k = schedule.getOrNull(i)?.getOrNull(j) ?: continue
            if (k !in 0 until shifts) continue
            val util = severity(i, j) / (1.0 + penaltyOf(i, j, k))
            if (util > bestUtil) { bestUtil = util; bestKey = key(i, j, k) }
        }
        if (bestKey < 0) return false
        pen[bestKey] = (pen[bestKey] ?: 0) + 1
        kicks++
        return true
    }

    /**
     * [GLS aging] 全 penalty を keepPercent%（整数床）へ減衰する。長期停滞で penalty が肥大化し、受理バイアスが
     * 過度に固着して脱出が逆に難しくなるのを防ぐ（Voudouris & Tsang の penalty aging 相当）。0 になった項目は除去。
     * グローバル最良は生スコア管理のため、減衰は探索の受理動学のみに作用し解の質を退化させない。
     * @return 減衰後に残った非ゼロ項目数。
     */
    fun decay(keepPercent: Int = 80): Int {
        // [レビュー#8 3.213.0] 値域契約の明示。100超は aging でなくペナルティ増幅になる（現行呼出は固定80のみ）。
        require(keepPercent in 0..100) { "keepPercent must be in 0..100: $keepPercent" }
        val it = pen.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val nv = e.value * keepPercent / 100
            if (nv <= 0) it.remove() else e.setValue(nv)
        }
        return pen.size
    }
}
