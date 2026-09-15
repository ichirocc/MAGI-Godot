package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 負数の個人回数は未設定扱い（D9 の「個人設定あり」にも数えない）。Sanity 2h が負数を案内する。 */
class NegativeRangeTest {
    private fun state(lo: String, hi: String) = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-05",
        shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "", "")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("s0", 0)), use2Patterns = false,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "2")),
        schedule = listOf(listOf(1, 1, 1, 0, 0)), wishes = emptyMap(), staffRange = mapOf("0,1" to Range(lo, hi)),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun negativeBoundsAreUnsetAndDoNotDisableTheGroupTarget() {
        val p = Problem(state("-1", "-1"))
        assertEquals(Int.MIN_VALUE, p.rangeLo[0][1]); assertEquals(Int.MAX_VALUE, p.rangeHi[0][1])
        assertEquals(2, p.apt[0][1])
        val rep = UnifiedViolationChecker.check(state("-1", "-1"))
        assertEquals(0, rep.breakdown["high"] ?: 0)   // 上限 -1 で全回数が違反になることはない
        assertEquals(1, rep.breakdown["apt"] ?: 0)    // |3 - 2|
    }

    @Test fun sanityReportsNegativeBounds() {
        val issues = V6SanityPort.buildGuidance(state("-1", ""))
        assertTrue(issues.any { it.where.contains("個人の回数「s0 A」") && it.problem.contains("負の値") })
    }
}
