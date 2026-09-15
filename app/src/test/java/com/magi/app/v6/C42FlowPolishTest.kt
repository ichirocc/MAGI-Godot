package com.magi.app.v6

import com.magi.app.model.C42Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C42FlowPolish の検証。群ペア禁止(c42)違反を、片側固定のヤコビ近似(g1側/g2側の対称2試行)で
 * min-cost-flow 再配分して解消する。
 *
 * テスト設計の教訓（C41FlowPolishTest/C2PolishTest と同型）: 群の人数が奇数だと、可動メンバーを寄せる手が
 * 群内の`fair`（公平化）を新規に悪化させ相殺されて却下される。両群とも人数=2で回避した。
 */
class C42FlowPolishTest {
    private fun state(wishes: Map<String, Int> = emptyMap()): MagiState {
        val shifts = listOf(Shift("休み", "休", "", ""), Shift("P", "P", "", ""), Shift("Q", "Q", "", ""))
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"))
        return MagiState(
            startDate = "2026-02-01", endDate = "2026-02-01",
            shifts = shifts, groups = groups,
            staff = listOf(Staff("A", 0), Staff("B", 0), Staff("C", 1), Staff("D", 1)),
            use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1), listOf(1, 1, 1)),
            groupShiftApt = listOf(listOf("", "", ""), listOf("", "", "")),
            // A=P、B=Q（G0側）／ C=Q、D=休（G1側）。G0のPとG1のQが同日併存＝禁止ペア1件(n1=1,n2=1)。
            schedule = listOf(listOf(1), listOf(2), listOf(2), listOf(0)),
            wishes = wishes, staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(),
            cons42 = listOf(C42Row(g1Kigou = "G0", g2Kigou = "G1", s1Kigou = "P", s2Kigou = "Q")),
        )
    }

    @Test
    fun resolvesTheGroupPairViolationByReassigningWithinTheGroups() {
        val st = state()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals("初期は c42 違反1件(G0/P=1人 x G1/Q=1人)", 1, before.breakdown["c42"] ?: 0)
        val res = C42FlowPolish.applyC42FlowPolish(st, sched.copy2D())
        val after = UnifiedViolationChecker.check(st, res.newSchedule)
        assertEquals("c42 解消", 0, after.breakdown["c42"] ?: 0)
        assertTrue("採用された", res.applied > 0)
        assertTrue("HARD は不変(=0)", after.hard <= before.hard)
        assertTrue("重み付きスコアが増えない", after.weightedScore <= before.weightedScore)
    }

    @Test
    fun noMovableMemberLeavesTheBoardUntouched() {
        // 全員希望固定＝両側とも可動メンバーが0人。何も変えない。
        val st = state(wishes = mapOf("0,0" to 1, "1,0" to 2, "2,0" to 2, "3,0" to 0))
        val sched = st.schedule.toIntArray2D()
        val res = C42FlowPolish.applyC42FlowPolish(st, sched.copy2D())
        assertEquals(0, res.applied)
        for (i in 0 until 4) assertEquals(st.schedule[i], res.newSchedule[i].toList())
    }

    @Test
    fun enablingInTheFullChainResolvesC42WithoutWorseningTheBoard() {
        val st = state()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        val on = V6HotfixPasses.runPostOptimization(st, sched.copy2D(), "t", seed = 7L, params = V6HotfixPasses.PostOptimizationParams(deterministic = true, c42FlowPolishEnabled = true))
        assertEquals(0, on.report.breakdown["c42"] ?: -1)
        assertTrue("HARD が増えない", on.report.hard <= before.hard)
        assertTrue("重み付きスコアが増えない", on.report.weightedScore <= before.weightedScore)
    }
}
