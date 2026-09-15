package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 選択日ペア交換: 単日交換では越えられない禁止連の局所障壁を 2 日同時交換で越える。seed 決定性・予算 0 の no-op。 */
class C3PairMaskPolishTest {
    private fun toy() = MagiState(
        startDate = "2026-06-01", endDate = "2026-06-05",
        shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "", "")), groups = listOf(Group("G", "G")),
        staff = listOf(Staff("甲", 0), Staff("乙", 0)), use2Patterns = true,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        schedule = listOf(listOf(0, 0, 1, 1, 0), listOf(0, 1, 0, 0, 1)), wishes = emptyMap(), staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = listOf(C3Row(listOf("休", "休")), C3Row(listOf("A", "A"))),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )
    private fun sched(st: MagiState) = Array(st.staffCount) { st.schedule[it].toIntArray() }

    @Test fun crossesTheSingleDayBarrierWithATwoDaySwap() {
        val st = toy()
        assertEquals(3, UnifiedViolationChecker.check(st, sched(st)).breakdown["c3n"])
        val r = C3PairMaskPolish.apply(st, sched(st), maxEvaluations = 200, seed = 1L)
        val after = UnifiedViolationChecker.check(st, r.newSchedule)
        assertEquals(1, after.breakdown["c3n"])
        assertTrue(r.applied >= 1)
        assertTrue(r.logs[0].message.contains("2日1") || r.logs[0].message.contains("採用"))
    }

    @Test fun deterministicForASeedAndNoOpWithZeroBudget() {
        val st = toy()
        val a = C3PairMaskPolish.apply(st, sched(st), maxEvaluations = 200, seed = 7L)
        val b = C3PairMaskPolish.apply(st, sched(st), maxEvaluations = 200, seed = 7L)
        assertArrayEquals(a.newSchedule, b.newSchedule)
        val z = C3PairMaskPolish.apply(st, sched(st), maxEvaluations = 0, seed = 7L)
        assertEquals(0, z.applied); assertArrayEquals(sched(st), z.newSchedule)
    }

    @Test fun masksIncludeAnAnchorAndAreOrderedOneTwoThreeDays() {
        val m2 = C3PairMaskPolish.masksOf(intArrayOf(3), 2, 2, 10).toList()
        assertEquals(listOf(listOf(2, 3), listOf(3, 4), listOf(1, 3), listOf(3, 5)), m2.map { it.toList() })
        val m3 = C3PairMaskPolish.masksOf(intArrayOf(0), 3, 3, 4).toList()
        assertTrue(m3.all { it.contains(0) && it.size == 3 && it.toSet().size == 3 })
    }
}
