package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.500.0 自律レビュー] `runPostOptimization` の Params 集約と PostChain ランナー化が採否を変えていないこと、
 * 退化した探索幅（巡回 0・各パス 0）でも落ちず keep-best で悪化しないことを固定する。
 */
class V6PostOptimizationParamsTest {

    /** 3 職員×11 日・厳密ピンと下限割れを持つ盤面（AdaptiveBlockSwapPolishTest の pinnedRestState と同形）。 */
    private fun pinnedState(): MagiState {
        val shifts = listOf(Shift("休み", "休", "1", "1"), Shift("X", "X", "1", "1"), Shift("Y", "Y", "1", "1"))
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"), Group("G2", "G2"))
        return MagiState(
            startDate = "2026-02-01", endDate = "2026-02-11",
            shifts = shifts, groups = groups,
            staff = listOf(Staff("A", 0), Staff("B", 1), Staff("C", 2)),
            use2Patterns = false,
            groupShift = List(3) { listOf(1, 1, 1) },
            groupShiftApt = List(3) { List(3) { "" } },
            schedule = listOf(
                listOf(0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1),
                listOf(1, 1, 1, 1, 0, 0, 2, 2, 2, 2, 2),
                listOf(2, 2, 2, 2, 2, 2, 0, 0, 0, 0, 0),
            ),
            wishes = emptyMap(),
            staffRange = mapOf("0,0" to Range("4", "4"), "0,2" to Range("2", "")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    /** 時間だけが変わる行（所要 ms・時間上限つき LNS の統計）を除いたログ本文。 */
    private fun stableLogs(r: V6PostOptimizationResult): List<String> =
        r.logs.map { it.message }.filterNot { m -> m.contains("ms") || m.contains("共同LNS") }

    @Test
    fun defaultParamsMatchTheLegacyCallExactly() {
        val st = pinnedState()
        val sched = st.schedule.toIntArray2D()
        val legacy = V6HotfixPasses.runPostOptimization(st, sched.copy2D(), "t", seed = 7L)
        val withParams = V6HotfixPasses.runPostOptimization(st, sched.copy2D(), "t", seed = 7L, params = V6HotfixPasses.PostOptimizationParams())
        assertTrue("盤面一致", legacy.schedule.contentDeepEquals(withParams.schedule))
        assertEquals("HARD 一致", legacy.report.hard, withParams.report.hard)
        assertEquals("total 一致", legacy.report.total, withParams.report.total)
        assertEquals("ログ一致（時間行を除く）", stableLogs(legacy), stableLogs(withParams))
    }

    @Test
    fun degenerateParamsDoNotCrashAndNeverWorsenTheBoard() {
        val st = pinnedState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        val params = V6HotfixPasses.PostOptimizationParams(
            hf80MaxCycles = 0, hf67MaxSwaps = 0, hf66MaxMoves = 0, maxRounds = 0, cyclicSwapPasses = 0,
            weeklyRebalancePasses = 0, alternatingSweeps = 0, c1LnsMaxMs = 0L, personalLnsMaxMs = 0L, passLogTopN = 0,
        )
        val r = V6HotfixPasses.runPostOptimization(st, sched.copy2D(), "t", seed = 7L, params = params)
        assertTrue("HARD が増えない", r.report.hard <= before.hard)
        assertTrue("重み付きスコアが増えない", r.report.weightedScore <= before.weightedScore)
        assertTrue("SoftPolishVerify 行が出る（0 巡でも集約行は必ず出す）", r.logs.any { it.tag == "SoftPolishVerify" })
        assertTrue("タイミング行が出る", r.logs.any { it.tag == "POST" })
    }

    /** [3.511.1] maxRounds=0＝巡回研磨クラスタが必ず停滞（totalApplied=0）。停滞拡大が既定 OFF なら legacy と一致し、
     *  ON でも落ちず keep-best（既存 3 パスが内部で betterReport を通す構造は不変＝広げても悪化しない）ことを固定する。 */
    @Test
    fun stallEscalationOnlyWidensWhenTheClusterStalledAndNeverWorsensTheBoard() {
        val st = pinnedState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        val base = V6HotfixPasses.PostOptimizationParams(deterministic = true, maxRounds = 0, lnsAdaptive = true)
        val off = V6HotfixPasses.runPostOptimization(st, sched.copy2D(), "t", seed = 7L, params = base)
        val on = V6HotfixPasses.runPostOptimization(
            st, sched.copy2D(), "t", seed = 7L,
            params = base.copy(stallEscalation = V6HotfixPasses.StallEscalationConfig(enabled = true)),
        )
        assertTrue("既定 OFF は legacy と一致", off.schedule.contentDeepEquals(
            V6HotfixPasses.runPostOptimization(st, sched.copy2D(), "t", seed = 7L, params = base.copy(stallEscalation = V6HotfixPasses.StallEscalationConfig(enabled = false))).schedule,
        ))
        for (r in listOf(off, on)) {
            assertTrue("HARD が増えない", r.report.hard <= before.hard)
            assertTrue("重み付きスコアが増えない", r.report.weightedScore <= before.weightedScore)
        }
    }
}
