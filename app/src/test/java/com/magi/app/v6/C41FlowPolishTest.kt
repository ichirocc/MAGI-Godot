package com.magi.app.v6

import com.magi.app.model.C41Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.511.5/測定中] C41FlowPolish の検証。群/日レンジ(c41)違反を、群内の可動メンバーの min-cost-flow 再配分で解消する。
 *
 * c41 の真の評価は二値（[l,u]を外れたら+1）で幅に依らない＝非凸。誘導コストへ生の二値差分をそのまま使うと、
 * MCMFの並列辺トリック（限界費用は非減少=凸でないと「q番目の枠」の意味が壊れる）が「2人目の恩恵」を
 * 「1人だけの状態」に誤帰属し、実装時に一度も採用されなかった（RangePolish型の「幅に応じた誘導コスト」へ
 * 置き換えて解消。history 参照）。
 *
 * テスト設計の教訓（C2PolishTest と同型）: 群の人数が奇数（3人）だと、l=u=2 を満たすために2人だけPへ寄せる手が
 * 群内の`fair`（公平化）を新規に悪化させ相殺されて却下される。群人数=2（全員がP化で揃う）にして回避した。
 */
class C41FlowPolishTest {
    private fun state(wishes: Map<String, Int> = emptyMap()): MagiState {
        val shifts = listOf(Shift("休み", "休", "", ""), Shift("P", "P", "", ""), Shift("Q", "Q", "", ""))
        val groups = listOf(Group("G0", "G0"))
        return MagiState(
            startDate = "2026-02-01", endDate = "2026-02-01",
            shifts = shifts, groups = groups,
            staff = listOf(Staff("A", 0), Staff("B", 0)),
            use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1)),
            groupShiftApt = listOf(listOf("", "", "")),
            schedule = listOf(listOf(1), listOf(2)),   // A=P, B=Q（P人数1、群目標は正確に2）
            wishes = wishes, staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = listOf(C41Row("G0", "P", "2", "2")), cons42 = emptyList(),
        )
    }

    @Test
    fun resolvesTheGroupDayRangeViolationByReassigningWithinTheGroup() {
        val st = state()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals("初期は c41 違反1件（P人数1、目標2）", 1, before.breakdown["c41"] ?: 0)
        val res = C41FlowPolish.applyC41FlowPolish(st, sched.copy2D())
        val after = UnifiedViolationChecker.check(st, res.newSchedule)
        assertEquals("c41 解消", 0, after.breakdown["c41"] ?: 0)
        assertTrue("採用された", res.applied > 0)
        assertTrue("HARD は不変(=0)", after.hard <= before.hard)
        assertTrue("重み付きスコアが増えない", after.weightedScore <= before.weightedScore)
        assertEquals("両者ともP", listOf(1, 1), (0 until 2).map { res.newSchedule[it][0] })
    }

    @Test
    fun noMovableMemberLeavesTheBoardUntouched() {
        // 全員希望固定＝可動メンバーが0人。何も変えない。
        val st = state(wishes = mapOf("0,0" to 1, "1,0" to 2))
        val sched = st.schedule.toIntArray2D()
        val res = C41FlowPolish.applyC41FlowPolish(st, sched.copy2D())
        assertEquals(0, res.applied)
        for (i in 0 until 2) assertEquals(st.schedule[i], res.newSchedule[i].toList())
    }

    @Test
    fun enablingInTheFullChainResolvesC41WithoutWorseningTheBoard() {
        val st = state()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        val on = V6HotfixPasses.runPostOptimization(st, sched.copy2D(), "t", seed = 7L, params = V6HotfixPasses.PostOptimizationParams(deterministic = true, c41FlowPolishEnabled = true))
        assertEquals(0, on.report.breakdown["c41"] ?: -1)
        assertTrue("HARD が増えない", on.report.hard <= before.hard)
        assertTrue("重み付きスコアが増えない", on.report.weightedScore <= before.weightedScore)
    }
}
