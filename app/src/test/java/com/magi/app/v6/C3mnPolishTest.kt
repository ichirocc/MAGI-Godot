package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [C3mnPolish・玉突き連鎖の横展開] grilling(2026-07-19)で確定した仕様の検証。
 * 金沢勇輝の実例（Dﾃ4連続、cons3n禁止で直接候補が全滅）を最小盤面で再現する:
 * A(職員)が cons3mn "X,X" 回避パターンに触れている。A自身がその日を別シフトへ動かすだけでは
 * Xの被覆(need1=1)が欠けるため、直接の自己修正は成立しない。B(在勤中のZ、需要なし=いつでも
 * 動かせる)がXへ玉突きで補充することで初めて解消する局面。
 */
class C3mnPolishTest {

    private fun chainState(): MagiState {
        // shift: 0=休(need無) 1=X(need1=1) 2=Y(need無) 3=Z(need無)
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("X", "X", "1", ""),
            Shift("Y", "Y", "", ""),
            Shift("Z", "Z", "", ""),
        )
        val groups = listOf(Group("GA", "GA"), Group("GB", "GB"))
        // GA(A)=休,X,Y / GB(B)=休,X,Z
        val groupShift = listOf(
            listOf(1, 1, 1, 0),
            listOf(1, 1, 0, 1),
        )
        val staff = listOf(Staff("A", 0), Staff("B", 1))
        val schedule = listOf(
            listOf(1, 1), // A = X, X （cons3mn "X,X" にヒット）
            listOf(3, 3), // B = Z, Z （需要なし=いつでも動かせる）
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-02",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift,
            groupShiftApt = List(2) { List(4) { "" } },
            schedule = schedule,
            wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(),
            cons3mn = listOf(C3Row(listOf("X", "X"))),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun c3mnPolishResolvesViaChainWhenDirectSelfChangeWouldCreateCovU() {
        val st = chainState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("初期はc3mn違反があること", (before.breakdown["c3mn"] ?: 0) > 0)
        assertEquals("初期はHARD=0(covU無し、AがXを単独充足)", 0, before.hard)

        val result = C3FamilyPolish.applyC3mnPolish(st, sched, seed = 1L)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("玉突き適用後はc3mn=0", 0, after.breakdown["c3mn"] ?: -1)
        assertEquals("HARDは悪化しない(0のまま)", 0, after.hard)
        assertEquals("Xの被覆(covU)は悪化しない", 0, after.breakdown["covU"] ?: -1)
        assertTrue("実際に手が採用されている", result.applied > 0)
    }

    @Test
    fun c3mnPolishIsNoOpWhenNoCons3mn() {
        val st = chainState().copy(cons3mn = emptyList())
        val sched = st.schedule.toIntArray2D()
        val result = C3FamilyPolish.applyC3mnPolish(st, sched, seed = 1L)
        assertEquals(0, result.applied)
    }
}
