package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Test

/** Web版「グループ別 適切回数(groupShiftApt)」エディタ移植のコア操作 [Ws1Ops.setGroupApt] の検証。 */
class Ws1OpsAptTest {

    private fun state(apt: List<List<String>> = emptyList()) = MagiState(
        startDate = "2026-07-01", endDate = "2026-07-02",
        shifts = listOf(Shift("日勤", "日", "", ""), Shift("休み", "休", "", "")),
        groups = listOf(Group("A", "A"), Group("B", "B")),
        staff = listOf(Staff("s1", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1), listOf(1, 1)),
        groupShiftApt = apt,
        schedule = listOf(listOf(0, 1)),
        wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun normalizesEmptyAptGridAndSetsCell() {
        val s = Ws1Ops.setGroupApt(state(), 0, 0, "10")
        // 未初期化(空)でも G×K に正規化される
        assertEquals(2, s.groupShiftApt.size)
        assertEquals(2, s.groupShiftApt[0].size)
        assertEquals("10", s.groupShiftApt[0][0])
        assertEquals("", s.groupShiftApt[0][1])
        assertEquals("", s.groupShiftApt[1][0])
    }

    @Test fun trimsValueAndIsOutOfRangeSafe() {
        val s = Ws1Ops.setGroupApt(state(), 1, 1, "  3 ")
        assertEquals("3", s.groupShiftApt[1][1])
        // 範囲外は無変更
        val orig = state()
        assertEquals(orig, Ws1Ops.setGroupApt(orig, 9, 0, "5"))
        assertEquals(orig, Ws1Ops.setGroupApt(orig, 0, 9, "5"))
    }

    @Test fun clearingWithBlankKeepsGridShape() {
        val seeded = state(listOf(listOf("10", "2"), listOf("4", "")))
        val s = Ws1Ops.setGroupApt(seeded, 0, 0, "")
        assertEquals("", s.groupShiftApt[0][0])
        assertEquals("2", s.groupShiftApt[0][1])
        assertEquals("4", s.groupShiftApt[1][0])
    }
}
