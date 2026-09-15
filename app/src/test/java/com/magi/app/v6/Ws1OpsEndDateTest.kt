package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** [3.486.0] 読込時に endDate を startDate + 日数 - 1 へ揃える（検証を通り抜けていた期間の矛盾）。 */
class Ws1OpsEndDateTest {
    private fun state(start: String, end: String, days: Int) = MagiState(
        startDate = start, endDate = end,
        shifts = listOf(Shift("休み", "休", "", ""), Shift("A", "A", "", "")),
        groups = listOf(Group("G0", "G0")),
        staff = listOf(Staff("s1", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)),
        groupShiftApt = listOf(listOf("", "")),
        schedule = listOf(List(days) { 0 }),
        wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun mismatchedEndDateIsRecomputedFromDayCount() {
        val st = state("2026-07-01", "2026-07-31", days = 3)   // 旧形式: 月末のまま、日数は3
        assertEquals("2026-07-03", Ws1Ops.normalizeEndDate(st).endDate)
        assertEquals("2026-08-31", Ws1Ops.normalizeEndDate(state("2026-08-01", "", 31)).endDate)
    }

    @Test fun startDateErrorRejectsUnparsableDates() {
        // [3.488.0] 旧: 受理して Problem.dow0 が黙って日曜（0）へ落ちていた。
        assertEquals(null, Ws1Ops.startDateError(state("2026-07-01", "2026-07-03", 3)))
        for (bad in listOf("invalid", "", "2026/07/01", "2026-13-01")) {
            assertEquals("受理してはいけない: '$bad'", true, Ws1Ops.startDateError(state(bad, "", 3)) != null)
        }
    }

    @Test fun groupShiftAptIsNormalizedToGroupsByShifts() {
        // [3.488.0] 空配列・行不足（旧形式）を G×K の空欄へ。既に G×K なら同じ state。
        val st = state("2026-07-01", "2026-07-03", 3)   // 1群 × 2シフト
        val empty = st.copy(groupShiftApt = emptyList())
        assertEquals(listOf(listOf("", "")), Ws1Ops.normalizeGroupShiftApt(empty).groupShiftApt)
        val longer = st.copy(groupShiftApt = listOf(listOf("1", "2", "9")))
        assertEquals("余分な列は落とす", listOf(listOf("1", "2")), Ws1Ops.normalizeGroupShiftApt(longer).groupShiftApt)
        val exact = st.copy(groupShiftApt = listOf(listOf("", "4")))
        assertSame(exact, Ws1Ops.normalizeGroupShiftApt(exact))
    }

    @Test fun consistentOrUnparsableStateIsReturnedAsIs() {
        val ok = state("2026-07-01", "2026-07-03", days = 3)
        assertSame(ok, Ws1Ops.normalizeEndDate(ok))
        val bad = state("not-a-date", "whatever", days = 3)
        assertSame("startDate が読めなければ触らない", bad, Ws1Ops.normalizeEndDate(bad))
        val empty = state("2026-07-01", "x", days = 0)
        assertSame("日数0は検証側が拒否するので触らない", empty, Ws1Ops.normalizeEndDate(empty))
    }
}
