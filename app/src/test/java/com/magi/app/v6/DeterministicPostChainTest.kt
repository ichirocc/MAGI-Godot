package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Iteration 7] 決定的モード（`PostOptimizationParams.deterministic`）＝時間でなく回数で止める。同じ入力・seed なら同じ盤面。 */
class DeterministicPostChainTest {
    private fun state(): MagiState {
        val t = 8
        val rows = listOf(listOf(1, 1, 1, 0, 1, 1, 1, 0), listOf(0, 0, 1, 1, 0, 0, 1, 1), listOf(1, 0, 0, 1, 1, 0, 0, 1))
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-08",
            shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "2", "")), groups = listOf(Group("G", "G")),
            staff = listOf(Staff("X", 0), Staff("Y", 0), Staff("Z", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = rows, wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row("3", "休", "1")), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        ).also { check(it.schedule[0].size == t) }
    }

    private fun run(params: V6HotfixPasses.PostOptimizationParams): V6PostOptimizationResult {
        val s = state()
        return V6HotfixPasses.runPostOptimization(s, s.schedule.map { it.toIntArray() }.toTypedArray(), "det", seed = 5L,
            deadlineMs = EngineClock.nowMs() + 10_000L, params = params)
    }

    @Test
    fun twoRunsProduceTheSameBoard() {
        val p = V6HotfixPasses.PostOptimizationParams(deterministic = true)
        val a = run(p); val b = run(p)
        assertTrue(a.schedule.contentDeepEquals(b.schedule))
        assertEquals(a.report.weightedScore, b.report.weightedScore, 0.0)
    }

    @Test
    fun jointLnsStopsByEvaluationCount() {
        // チェーン内では前段の巡回研磨が c1 を 0 にして LNS が「対象なし」になるので、入力盤面（c1 違反あり）に直接当てる。
        val s = state()
        val r = C1RepairOperators.jointLns(s, s.schedule.map { it.toIntArray() }.toTypedArray(), config = C1JointLnsPolish.Config(maxEvaluations = 3, patienceMs = 0L))
        val msg = r.logs.first().message
        assertTrue(msg, msg.contains("評価回数上限3"))
    }

    @Test
    fun evaluationCapIsIgnoredWhenZero() {
        val cfg = C1JointLnsPolish.Config(maxEvaluations = 0, maxMillis = 500L, patienceMs = 0L)
        val s = state()
        val r = C1RepairOperators.jointLns(s, s.schedule.map { it.toIntArray() }.toTypedArray(), config = cfg)
        assertTrue(r.logs.first().message, !r.logs.first().message.contains("評価回数上限"))
    }
}
