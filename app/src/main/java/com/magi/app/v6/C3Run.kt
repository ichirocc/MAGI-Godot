package com.magi.app.v6

/**
 * [HF507] c3 run-mode helpers — faithful port of the Web `_isSingleShiftSeq` / `_c3RunDeficit`
 * (magi_v6_web.html). For a non-forbidden single-shift c3 sequence (e.g. D-D-D meaning "want a
 * run of L consecutive days of shift k"), the penalty is the run deficit `sum over runs of
 * max(0, L - r)` rather than the per-window count. This matches the authoritative Web fullEval
 * (run-mode default ON) and yields the "true" deficit instead of the span-multiplied count.
 */
object C3Run {

    /** True iff [seq] is a non-empty run of the same shift index (D-D-D / 休-休 ...). */
    fun isSingleShiftSeq(seq: IntArray): Boolean {
        if (seq.isEmpty()) return false
        for (l in 1 until seq.size) if (seq[l] != seq[0]) return false
        return true
    }

    /**
     * Run deficit for staff [i]'s row over shift [k] wanting runs of length [L]:
     * scan consecutive assigned days; each run of length r (1 <= r < L) adds (L - r).
     * Identical scan to the Web `_c3RunDeficit` (bitmask there; row scan here).
     */
    fun rowDeficit(a: Array<IntArray>, i: Int, k: Int, L: Int): Long {
        val row = a[i]
        val t = row.size
        var sub = 0L
        var r = 0
        var j = 0
        while (j <= t) {
            val on = j < t && row[j] == k
            if (on) {
                r++
            } else if (r > 0) {
                val d = L - r
                if (d > 0) sub += d.toLong()
                r = 0
            }
            j++
        }
        return sub
    }
}
