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
 * [C3PatternPolish] ユーザー指示「c42/c42s以外にも『動かせるか』専用オペレータの欠如が無いか
 * 棚卸しする」で発見。cons3/cons3mの複数シフトMUST/Wantパターン(非single-shift、C3Run.isSingleShiftSeq
 * が偽)は3.216.0で「既存機構(2者ブロック交換/3者回転)のまま対象外」と明記されスコープ外にされたまま
 * だった。C3mnPolishTest(3.214.0)と同型の最小盤面（need1で唯一の担当者に絞り、直接の自己修正だけでは
 * 被覆が欠ける局面）で検証する。
 */
class C3PatternPolishTest {

    private fun chainState(): MagiState {
        // shift: 0=休(need無) 1=X(need1=1) 2=Y(need無) 3=Z(need無)
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("X", "X", "1", ""),
            Shift("Y", "Y", "", ""),
            Shift("Z", "Z", "", ""),
        )
        val groups = listOf(Group("GA", "GA"), Group("GB", "GB"))
        val groupShift = listOf(
            listOf(1, 1, 1, 0), // GA(A)=休,X,Y
            listOf(1, 1, 1, 1), // GB(B)=休,X,Y,Z（Yも担当可＝玉突きでXを埋めても新規発火しないように）
        )
        val staff = listOf(Staff("A", 0), Staff("B", 1))
        val schedule = listOf(
            listOf(1, 1), // A = X,X（「X→Y」必須パターン: day0=Xの後day1がYでない=未完成で発火）
            listOf(3, 2), // B = Z,Y（day1が既にY＝玉突きでday0をXへ埋めても「X→Y」が完成し新規発火しない）
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
            cons3 = listOf(C3Row(listOf("X", "Y"))),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun c3PatternPolishResolvesViaChainWhenDirectSelfChangeWouldCreateCovU() {
        val st = chainState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("初期はc3違反(未完成パターン)があること", (before.breakdown["c3"] ?: 0) > 0)
        assertEquals("初期はHARD=0(covU無し、AがXを単独充足)", 0, before.hard)

        val result = C3FamilyPolish.applyC3PatternPolish(st, sched, seed = 1L)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("玉突き適用後はc3=0", 0, after.breakdown["c3"] ?: -1)
        assertEquals("HARDは悪化しない(0のまま)", 0, after.hard)
        assertEquals("Xの被覆(covU)は悪化しない", 0, after.breakdown["covU"] ?: -1)
        assertTrue("実際に手が採用されている", result.applied > 0)
    }

    @Test
    fun c3PatternPolishIsNoOpWhenNoMultiShiftRules() {
        val st = chainState().copy(cons3 = emptyList())
        val sched = st.schedule.toIntArray2D()
        val result = C3FamilyPolish.applyC3PatternPolish(st, sched, seed = 1L)
        assertEquals(0, result.applied)
    }

    @Test
    fun c3PatternPolishSkipsSingleShiftSequencesAsOutOfScope() {
        // 単一シフト連(run-deficitモデル)はC3RunPolish(3.215.0)の担当。本パスは何もしない(対象外)。
        val st = chainState().copy(cons3 = listOf(C3Row(listOf("X", "X", "X"))))
        val sched = st.schedule.toIntArray2D()
        val result = C3FamilyPolish.applyC3PatternPolish(st, sched, seed = 1L)
        assertEquals(0, result.applied)
    }
}
