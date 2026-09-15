package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.255.0, 受領・検証のうえ適用] PersonalBalanceJointLnsPolish単体の検証。実データ(golden_state.json/
 * sample_state_v6.json、ホストJVM実行)で、既存パイプライン適用後にも追加で改善を見つけること
 * (sample_state_v6.jsonでpersonal 34->31・total 196->195)を確認済み。
 */
class PersonalBalanceJointLnsPolishTest {
    @Test
    fun resolvesSimpleLowDeficiencyWithoutFairSideEffect() {
        // 2職員を別々の単独群(G0/G1)にする＝fair(群内公平化)の巻き添えを避ける
        // （2人共有群だとこの規模ではfairが同時に動いてtotalが改善しない中立トレードになる）。
        val shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"))
        val staff = listOf(Staff("a", 0), Staff("b", 1))
        val a = listOf(1, 1, 0, 0, 0)
        val b = listOf(0, 0, 0, 0, 0)
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-05",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1), listOf(1, 1)),
            groupShiftApt = listOf(listOf("", ""), listOf("", "")),
            schedule = listOf(a, b), wishes = emptyMap(),
            staffRange = mapOf("0,1" to Range("4", "")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals(2, before.breakdown["low"] ?: 0)
        assertEquals(0, before.hard)

        val out = PersonalBalanceJointLnsPolish.apply(
            st, sched,
            PersonalBalanceJointLnsPolish.Config(maxMillis = 2000L, maxRestarts = 2, maxDepth = 3),
        )
        val after = UnifiedViolationChecker.check(st, out.newSchedule)
        assertEquals(0, after.breakdown["low"] ?: -1)
        assertEquals(0, after.hard)
        assertTrue("何らかの手が採用されている", out.applied > 0)
        assertTrue("totalが真に改善する", after.total < before.total)
    }

    @Test
    fun isNoOpWhenNoRangeOrAptConfigured() {
        val shifts = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))
        val groups = listOf(Group("G", "G"))
        val staff = listOf(Staff("a", 0))
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-03",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(0, 1, 0)), wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val out = PersonalBalanceJointLnsPolish.apply(st, sched)
        assertEquals(0, out.applied)
    }
}
