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
 * [C3RunPolish・玉突き連鎖の横展開その3] grilling不要(C3mnPolish/RangePolishと同型、ユーザー承認2026-07-19)で
 * 実装したcons3/cons3m(単一シフト連=run-deficitモデル)専用研磨の検証。
 * A(職員)がshift Xの2連続(cons3 "X,X")を1件しか持たず(run長1<L=2)deficit。Yは全日need1=1で
 * day0=B・day1=Aがそれぞれ単独充足しているため、Aが隣接日(day1)を自身でXへ拡張するだけでは
 * day1のYの被覆が欠け、直接の自己修正は成立しない。B(day1はZに在勤=需要なし)が玉突きで
 * day1のYを補充することで初めて解消する局面。
 */
class C3RunPolishTest {

    private fun chainState(): MagiState {
        // shift: 0=休(need無) 1=X(need無、連続させたい対象) 2=Y(need1=1、全日担保が必要) 3=Z(need無)
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("X", "X", "", ""),
            Shift("Y", "Y", "1", ""),
            Shift("Z", "Z", "", ""),
        )
        val groups = listOf(Group("GA", "GA"), Group("GB", "GB"))
        val groupShift = listOf(
            listOf(1, 1, 1, 0), // GA(A)=休,X,Y
            listOf(1, 0, 1, 1), // GB(B)=休,Y,Z
        )
        val staff = listOf(Staff("A", 0), Staff("B", 1))
        val schedule = listOf(
            listOf(1, 2), // A = X, Y （Xの連続が1日のみ＝L=2に対しdeficit=1。day1のYはAが単独充足）
            listOf(2, 3), // B = Y, Z （day0のYはBが単独充足＝両日ともYの被覆はちょうど満たされている）
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-02",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift,
            groupShiftApt = List(2) { List(4) { "" } },
            schedule = schedule,
            wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(),
            cons3 = listOf(C3Row(listOf("X", "X"))),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun c3RunPolishResolvesViaChainWhenDirectSelfExtensionWouldCreateCovU() {
        val st = chainState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("初期はc3(run-deficit)違反があること", (before.breakdown["c3"] ?: 0) > 0)
        assertEquals("初期はHARD=0(covU無し、AがYを単独充足)", 0, before.hard)

        val result = C3FamilyPolish.applyC3RunPolish(st, sched, seed = 1L)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("玉突き適用後はc3=0", 0, after.breakdown["c3"] ?: -1)
        assertEquals("HARDは悪化しない(0のまま)", 0, after.hard)
        assertEquals("Yの被覆(covU)は悪化しない", 0, after.breakdown["covU"] ?: -1)
        assertTrue("実際に手が採用されている", result.applied > 0)
    }

    @Test
    fun c3RunPolishIsNoOpWhenNoSingleShiftRules() {
        val st = chainState().copy(cons3 = emptyList())
        val sched = st.schedule.toIntArray2D()
        val result = C3FamilyPolish.applyC3RunPolish(st, sched, seed = 1L)
        assertEquals(0, result.applied)
    }
}
