package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [マトリックス一括] 群×シフト担当可否マトリクスの行ヘッダ（群名）／列ヘッダ（シフト名）タップが呼ぶ
 * [Ws1Ops.setGroupShiftRow] / [Ws1Ops.setGroupShiftColumn] の検証。
 *
 * 一番大事な不変条件は **「休を全群から外せない／行OFFでも休は残る」**。担当可能シフトが1つも無い群は
 * validate が拒否し（「groupShift[g] に担当可能シフトがありません」）、その群の職員は行ごと groupViol(HARD)
 * になる（3.418.0/3.442.0 の再発防止）。Windows 版 `Ws1OpsTest.SetGroupShiftRow_*`/`SetGroupShiftColumn_*` と同値。
 */
class Ws1OpsMatrixTest {

    // 休=index0 / A / B の3シフト、G0/G1 の2群。
    private fun state() = MagiState(
        startDate = "2026-07-01", endDate = "2026-07-02",
        shifts = listOf(Shift("休み", "休", "", ""), Shift("A", "A", "", ""), Shift("B", "B", "", "")),
        groups = listOf(Group("G0", "G0"), Group("G1", "G1")),
        staff = listOf(Staff("s1", 0), Staff("s2", 1)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 0, 1), listOf(1, 1, 0)),
        groupShiftApt = listOf(listOf("", "", ""), listOf("", "", "")),
        schedule = listOf(listOf(0, 0), listOf(0, 0)),
        wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun rowBulkTurnsWholeRowOnAndKeepsRestWhenTurningOff() {
        val st = state()
        val on = Ws1Ops.setGroupShiftRow(st, 0, true)
        assertEquals(listOf(1, 1, 1), on.groupShift[0])
        assertEquals("他の群は不変", listOf(1, 1, 0), on.groupShift[1])

        // OFF でも休(index0)は残る＝担当可能シフトの無い群を作らない。
        val off = Ws1Ops.setGroupShiftRow(on, 0, false)
        assertEquals(listOf(1, 0, 0), off.groupShift[0])
        assertSame("範囲外は何もしない", st, Ws1Ops.setGroupShiftRow(st, 5, true))
    }

    @Test fun singleCellRefusesTurningRestOff() {
        // [3.484.0] 行/列一括だけが休を守り、単一セルは素通しだった（Windows 版レビュー指摘の兄弟バグ）。
        val st = state()
        assertSame(st, Ws1Ops.setGroupShift(st, 0, 0, false))   // 休(index0) を OFF → 同じ state（拒否）
        assertSame(st, Ws1Ops.setGroupShift(st, 1, 0, false))
        val on = Ws1Ops.setGroupShift(st, 0, 1, true)            // 休以外は従来どおり
        assertEquals(1, on.groupShift[0][1])
        val off = Ws1Ops.setGroupShift(on, 0, 2, false)
        assertEquals(0, off.groupShift[0][2])
        assertEquals(1, Ws1Ops.setGroupShift(st, 0, 0, true).groupShift[0][0])   // 休を ON は常に可
    }

    @Test fun columnBulkAppliesToAllGroupsAndRefusesTurningRestOff() {
        val st = state()
        val on = Ws1Ops.setGroupShiftColumn(st, 2, true)
        assertEquals(1, on.groupShift[0][2])
        assertEquals(1, on.groupShift[1][2])
        val off = Ws1Ops.setGroupShiftColumn(on, 1, false)
        assertEquals(0, off.groupShift[0][1])
        assertEquals(0, off.groupShift[1][1])

        // 休の列を OFF にする操作は同じ state を返す（ViewModel は === で拒否を検知して案内する）。
        assertSame(st, Ws1Ops.setGroupShiftColumn(st, 0, false))
        assertSame("範囲外は何もしない", st, Ws1Ops.setGroupShiftColumn(st, 9, true))
    }

    @Test fun rowOffKeepsRestEvenWhenRestIsNotIndexZero() {
        // 休が先頭でないデータ（3.416.0 以降は記号で解決する）。
        val st = state().copy(
            shifts = listOf(Shift("A", "A", "", ""), Shift("休み", "休", "", ""), Shift("B", "B", "", "")),
            groupShift = listOf(listOf(1, 1, 1), listOf(1, 1, 1)),
        )
        val off = Ws1Ops.setGroupShiftRow(st, 1, false)
        assertEquals(listOf(0, 1, 0), off.groupShift[1])
        assertSame("休の列(index1)は全群から外せない", st, Ws1Ops.setGroupShiftColumn(st, 1, false))
    }
}
