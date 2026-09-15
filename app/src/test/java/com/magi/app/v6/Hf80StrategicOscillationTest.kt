package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HF80 戦略的振動: seed で決定的、keep-best（入力より悪くならない）、希望固定と置けないシフトを動かさない。 */
class Hf80StrategicOscillationTest {
    private val REST = 0; private val A = 1; private val B = 2

    private fun state(): MagiState = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-06",
        shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "1", "1"), Shift("B", "B", "1", "1")),
        groups = listOf(Group("G", "G")),
        staff = listOf(Staff("s0", 0), Staff("s1", 0), Staff("s2", 0)),
        use2Patterns = true,
        groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
        // 毎日 A も B もほぼ 0 人＝人員不足だらけの盤面から始める（希望固定セルは入力で希望どおり置いておく）
        schedule = listOf(List(6) { REST }, listOf(REST, REST, REST, A, REST, REST), List(6) { REST }),
        wishes = mapOf("0,0" to REST, "1,3" to A),
        staffRange = mapOf("2,$B" to Range("0", "0")),   // s2 は B を置けない
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    private fun sched(st: MagiState) = Array(st.staffCount) { st.schedule[it].toIntArray() }

    @Test fun sameSeedGivesSameBoardAndNeverWorsensTheInput() {
        val st = state()
        val r1 = V6HotfixPasses.applyHF80StrategicOscillation(st, sched(st), maxCycles = 3, seed = 7L)
        val r2 = V6HotfixPasses.applyHF80StrategicOscillation(st, sched(st), maxCycles = 3, seed = 7L)
        assertArrayEquals(r1.newSchedule, r2.newSchedule)
        assertEquals(3, r1.cycles)
        val before = UnifiedViolationChecker.check(st, sched(st))
        val after = UnifiedViolationChecker.check(st, r1.newSchedule)
        assertFalse("keep-best: 入力より悪い盤面は返さない", betterReport(before, after))
        assertTrue("人員不足だらけの入力は 3 サイクルで改善する", r1.applied && after.hard < before.hard)
    }

    @Test fun pinnedWishesAndCappedShiftsAreNeverTouched() {
        val st = state()
        for (seed in 1L..5L) {
            val r = V6HotfixPasses.applyHF80StrategicOscillation(st, sched(st), maxCycles = 3, seed = seed)
            assertEquals(REST, r.newSchedule[0][0])
            assertEquals(A, r.newSchedule[1][3])
            assertTrue("上限 0 の B は s2 に置かれない", r.newSchedule[2].none { it == B })
        }
    }

    @Test fun zeroCyclesIsANoOp() {
        val st = state()
        val r = V6HotfixPasses.applyHF80StrategicOscillation(st, sched(st), maxCycles = 0, seed = 1L)
        assertFalse(r.applied); assertEquals(0, r.cycles)
        assertArrayEquals(sched(st), r.newSchedule)
    }
}
