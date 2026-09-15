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
 * [3.511.2/測定中] circulr交換の k=4,5 拡張（[CyclicSwapWeeklyPolish.applyCyclicSwapPolish] の `maxK`）。
 * 4 職員が「P/Q」「Q/R」「R/S」「S/P」の隣接シフトだけ担当可能な環（AdaptiveBlockSwapPolishTest の
 * fourWayCycleState と同型のリング構造）を作り、単日で全員が「隣の担当可能シフト」を欲しがる盤面にすると、
 * 2 職員スワップ・3 職員ローテーションのどちらも到達できず 4 職員循環だけが全 4 件の下限割れを同時に解消する。
 */
class CyclicSwapNTest {

    private fun ringState(): MagiState {
        val shifts = listOf(
            Shift("休み", "休", "", ""),
            Shift("P", "P", "1", "1"),
            Shift("Q", "Q", "1", "1"),
            Shift("R", "R", "1", "1"),
            Shift("S", "S", "1", "1"),
        )
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"), Group("G2", "G2"), Group("G3", "G3"))
        return MagiState(
            startDate = "2026-02-01", endDate = "2026-02-01",
            shifts = shifts, groups = groups,
            staff = listOf(Staff("A", 0), Staff("B", 1), Staff("C", 2), Staff("D", 3)),
            use2Patterns = false,
            groupShift = listOf(
                listOf(1, 1, 1, 0, 0),   // A: 休/P/Q
                listOf(1, 0, 1, 1, 0),   // B: 休/Q/R
                listOf(1, 0, 0, 1, 1),   // C: 休/R/S
                listOf(1, 1, 0, 0, 1),   // D: 休/S/P
            ),
            groupShiftApt = List(4) { List(5) { "" } },
            schedule = listOf(
                listOf(2),   // A: Q（欲しいのは P）
                listOf(3),   // B: R（欲しいのは Q）
                listOf(4),   // C: S（欲しいのは R）
                listOf(1),   // D: P（欲しいのは S）
            ),
            wishes = emptyMap(),
            staffRange = mapOf(
                "0,1" to Range("1", "1"),   // A に P を 1 回
                "1,2" to Range("1", "1"),   // B に Q を 1 回
                "2,3" to Range("1", "1"),   // C に R を 1 回
                "3,4" to Range("1", "1"),   // D に S を 1 回
            ),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun maxKThreeCannotReachTheFourWayCycle() {
        val st = ringState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals("初期は下限割れ4件", 4, before.breakdown["low"] ?: 0)
        val res = CyclicSwapWeeklyPolish.applyCyclicSwapPolish(st, sched.copy2D(), maxK = 3)
        val after = UnifiedViolationChecker.check(st, res.newSchedule)
        assertEquals("k=2,3 だけでは環に到達できず下限割れは残る", 4, after.breakdown["low"] ?: 0)
        assertEquals("採用0回", 0, res.applied)
    }

    @Test
    fun maxKFiveResolvesTheFourWayCycleAndNeverWorsensTheBoard() {
        val st = ringState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        val res = CyclicSwapWeeklyPolish.applyCyclicSwapPolish(
            st, sched.copy2D(), maxPasses = 2, maxK = 5, kTrialsPerDay = 3_000, seed = 12345L,
        )
        val after = UnifiedViolationChecker.check(st, res.newSchedule)
        assertEquals("4職員循環で下限割れが解消", 0, after.breakdown["low"] ?: 0)
        assertTrue("採用された", res.applied > 0)
        assertTrue("HARD は不変(=0)", after.hard <= before.hard)
        // 被覆保存: シフトの多重集合が不変（4人が同じ4シフトを入れ替えただけ）。
        assertEquals(setOf(1, 2, 3, 4), (0 until 4).map { res.newSchedule[it][0] }.toSet())
    }

    @Test
    fun defaultMaxKThreeMatchesLegacyBehaviorExactly() {
        val st = ringState()
        val sched = st.schedule.toIntArray2D()
        val legacy = CyclicSwapWeeklyPolish.applyCyclicSwapPolish(st, sched.copy2D())
        val explicit = CyclicSwapWeeklyPolish.applyCyclicSwapPolish(st, sched.copy2D(), maxK = 3)
        assertTrue(legacy.newSchedule.contentDeepEquals(explicit.newSchedule))
        assertEquals(legacy.applied, explicit.applied)
    }
}
