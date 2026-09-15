package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [3.515.3] 職員／シフト種別、[3.515.6] グループの並び替え（[Ws1Ops.moveStaff] / [Ws1Ops.moveShift] /
 * [Ws1Ops.moveGroup]）。不変条件は「index で保存しているもの（勤務表・希望・個人の回数・日別必要人数・
 * 担当可否・群目標・職員の所属）が全部追従し、記号で参照するもの（制約行・表示色）は触らない」こと。
 * 端の並び替えは同じ state を返す。
 */
class Ws1OpsMoveTest {

    // 休=0 / A=1 / B=2 の3シフト、s0(G0)・s1(G1)・s2(G0) の3職員、2日。
    private fun state() = MagiState(
        startDate = "2026-07-01", endDate = "2026-07-02",
        shifts = listOf(Shift("休み", "休", "", ""), Shift("A", "A", "1", ""), Shift("B", "B", "2", "")),
        groups = listOf(Group("G0", "G0"), Group("G1", "G1")),
        staff = listOf(Staff("s0", 0, 1), Staff("s1", 1, -1), Staff("s2", 0, 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 0), listOf(1, 0, 1)),
        groupShiftApt = listOf(listOf("", "3", ""), listOf("", "", "4")),
        schedule = listOf(listOf(1, 0), listOf(2, 2), listOf(0, 1)),
        wishes = mapOf("0,0" to 1, "1,1" to 2),
        staffRange = mapOf("0,1" to Range("1", "2"), "1,2" to Range("", "5")),
        needDay1 = mapOf("1,0" to "9"), needDay2 = mapOf("2,1" to "8"),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = listOf(com.magi.app.model.C3Row(listOf("A", "B"))),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        shiftColors = mapOf("A" to "#112233"),
    )

    private fun grid(st: MagiState) = st.schedule.map { it.toIntArray() }.toTypedArray()

    @Test fun moveStaffSwapsRowAndEveryStaffIndexedMap() {
        val st = state()
        val r = Ws1Ops.moveStaff(st, grid(st), 0, +1)
        assertEquals(listOf("s1", "s0", "s2"), r.state.staff.map { it.name })
        assertEquals("skillIdx は職員と一緒に動く", listOf(-1, 1, 0), r.state.staff.map { it.skillIdx })
        assertEquals(listOf(listOf(2, 2), listOf(1, 0), listOf(0, 1)), r.state.schedule)
        assertEquals(listOf(listOf(2, 2), listOf(1, 0), listOf(0, 1)), r.schedule.map { it.toList() })
        assertEquals(mapOf("1,0" to 1, "0,1" to 2), r.state.wishes)
        assertEquals(mapOf("1,1" to Range("1", "2"), "0,2" to Range("", "5")), r.state.staffRange)
        assertEquals("シフト軸のものは不変", st.needDay1, r.state.needDay1)
        // 上へ戻すと元どおり
        val back = Ws1Ops.moveStaff(r.state, r.schedule, 1, -1)
        assertEquals(st.staff, back.state.staff)
        assertEquals(st.wishes, back.state.wishes)
        assertEquals(st.schedule, back.state.schedule)
    }

    @Test fun moveStaffAtTheEdgeIsANoOp() {
        val st = state()
        val g = grid(st)
        assertSame(st, Ws1Ops.moveStaff(st, g, 0, -1).state)
        assertSame(st, Ws1Ops.moveStaff(st, g, 2, +1).state)
        assertSame(st, Ws1Ops.moveStaff(st, g, 7, +1).state)
    }

    @Test fun moveShiftSwapsColumnsValuesAndShiftIndexedKeysButNotSymbols() {
        val st = state()
        val r = Ws1Ops.moveShift(st, grid(st), 1, +1)   // A(1) <-> B(2)
        assertEquals(listOf("休", "B", "A"), r.state.shifts.map { it.kigou })
        assertEquals(listOf(listOf(1, 0, 1), listOf(1, 1, 0)), r.state.groupShift)
        assertEquals(listOf(listOf("", "", "3"), listOf("", "4", "")), r.state.groupShiftApt)
        assertEquals(listOf(listOf(2, 0), listOf(1, 1), listOf(0, 2)), r.state.schedule)
        assertEquals(mapOf("0,0" to 2, "1,1" to 1), r.state.wishes)
        assertEquals(mapOf("0,2" to Range("1", "2"), "1,1" to Range("", "5")), r.state.staffRange)
        assertEquals(mapOf("2,0" to "9"), r.state.needDay1)
        assertEquals(mapOf("1,1" to "8"), r.state.needDay2)
        assertEquals("記号参照は不変", st.cons3n, r.state.cons3n)
        assertEquals(st.shiftColors, r.state.shiftColors)
        assertEquals("休の解決は記号なので位置に依らない", 0, restShiftIndex(r.state))
        val back = Ws1Ops.moveShift(r.state, r.schedule, 2, -1)
        assertEquals(st, back.state)
    }

    @Test fun moveShiftAtTheEdgeIsANoOp() {
        val st = state()
        val g = grid(st)
        assertSame(st, Ws1Ops.moveShift(st, g, 0, -1).state)
        assertSame(st, Ws1Ops.moveShift(st, g, 2, +1).state)
    }

    @Test fun moveGroupSwapsRowsAndStaffGroupIdxButNotScheduleOrWishes() {
        val st = state()
        val ns = Ws1Ops.moveGroup(st, 0, +1)   // G0(0) <-> G1(1)
        assertEquals(listOf("G1", "G0"), ns.groups.map { it.name })
        assertEquals(listOf(listOf(1, 0, 1), listOf(1, 1, 0)), ns.groupShift)
        assertEquals(listOf(listOf("", "", "4"), listOf("", "3", "")), ns.groupShiftApt)
        assertEquals("所属していた群番号が追従", listOf(1, 0, 1), ns.staff.map { it.groupIdx })
        assertEquals("skillIdx は無関係", listOf(1, -1, 0), ns.staff.map { it.skillIdx })
        assertEquals("勤務表・希望は職員行基準のため無変化", st.schedule, ns.schedule)
        assertEquals(st.wishes, ns.wishes)
        assertEquals(st.staffRange, ns.staffRange)
        assertEquals("記号参照は不変", st.cons3n, ns.cons3n)
        val back = Ws1Ops.moveGroup(ns, 1, -1)
        assertEquals(st, back)
    }

    @Test fun moveGroupAtTheEdgeIsANoOp() {
        val st = state()
        assertSame(st, Ws1Ops.moveGroup(st, 0, -1))
        assertSame(st, Ws1Ops.moveGroup(st, 1, +1))
    }
}
