package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [3.507.0] 個人上限 0 のシフトは最適化器が置かない（`Problem.mayPlace`）。評価・表示は不変。
 *  盤面: 休/A、A は毎日 1 名必要。X は A 上限 0、Y/Z は制限なし。入力は X が全日 A（上限超過 4 だが被覆は満たす）。 */
class ZeroCapExclusionTest {
    private fun state(wishes: Map<String, Int> = emptyMap(), extraRange: Map<String, Range> = emptyMap()): MagiState {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "1", ""))
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("X", 0), Staff("Y", 0), Staff("Z", 0))
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-04",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(1, 1, 1, 1), listOf(0, 0, 0, 0), listOf(0, 0, 0, 0)), wishes = wishes,
            staffRange = mapOf("0,1" to Range("0", "0")) + extraRange,
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    private fun countA(sched: Array<IntArray>, i: Int) = sched[i].count { it == 1 }

    @Test
    fun mayPlaceExcludesCappedShiftButCanDoAndUiListsAreUnchanged() {
        val p = cachedProblem(state(extraRange = mapOf("1,0" to Range("0", "0"))))   // Y の休も上限 0（休は除外しない）
        assertTrue(p.canDo(0, 1)); assertFalse(p.mayPlace(0, 1))
        assertTrue(p.mayPlace(0, 0)); assertTrue(p.mayPlace(1, 1))
        assertTrue("休は上限 0 でも置ける", p.mayPlace(1, 0))
        assertEquals(listOf(0), p.allowedShiftsForStaff(0).toList())
        assertEquals(listOf(0, 1), p.canDoShiftsForStaff(0).toList())
        assertEquals(listOf(1, 2), p.staffForShift[1].toList())
        assertEquals(listOf(0, 1, 2), p.staffForShift[0].toList())
    }

    @Test
    fun checkerStillCountsCappedCellsAsHighNotHard() {
        val s = state()
        val r = UnifiedViolationChecker.check(s, s.schedule.map { it.toIntArray() }.toTypedArray())
        assertEquals(0, r.hard)
        assertEquals(4, r.breakdown["high"])
        assertEquals(0, r.breakdown["groupViol"] ?: 0)
    }

    @Test
    fun entryHardeningClearsCappedCellsUnlessWishLockedToThatShift() {
        val s = state(wishes = mapOf("0,2" to 1))   // X の 3 日目は A を希望（希望が優先＝残す）
        val input = s.schedule.map { it.toIntArray() }.toTypedArray()
        val out = HardRepairCore.hf66DataHardening(s, input, "test")
        assertEquals(listOf(0, 0, 1, 0), out[0].toList())
        val (cleared, n) = HardRepairCore.clearCappedCells(s, input)
        assertEquals(3, n)
        assertEquals(listOf(0, 0, 1, 0), cleared[0].toList())
        assertEquals("入力は変えない", listOf(1, 1, 1, 1), input[0].toList())
    }

    @Test
    fun optimizerMovesCoverageOffTheCappedStaff() = runBlocking {
        val s = state()
        val r = V6NativeOptimizer.optimize(s, options = V6OptimizerOptions(algorithm = V6Algorithm.V5, totalBudgetSec = 2, workers = 1,
            softPolish = false, restarts = 0, seed = 7L, postPolish = false))
        assertEquals(0, countA(r.schedule, 0))
        assertEquals("被覆は Y/Z が引き受ける", 0, UnifiedViolationChecker.check(s, r.schedule).hard)
    }

    @Test
    fun handleOptimizeReturnsZeroCappedCellsAndDoesNotRevertToInput() = runBlocking {
        val s = state()
        val res = V6FinalPort.handleOptimize(s, secondsRaw = 2, workers = 1, requestedAlgorithm = V6Algorithm.V5, allowImpossible = true)
        assertEquals(0, countA(res.schedule, 0))
        assertEquals(0, res.report.hard)
        assertTrue("入口で外した件数をログに出す", res.logs.any { it.tag == "CapZero" && it.message.contains("4 件") })
        assertFalse("外した入力を基準にするので番兵は発火しない", res.logs.any { it.tag == "Sentinel" })
    }

    /** [3.512.1/回帰] `handleOptimize` は本番2箇所（MagiViewModel/OptimizationWorker）から
     *  `onProgress` を**末尾の名前なしトレーリングラムダ**で渡される。`quantitativeRangeEval`
     *  （backlog #12(a)）を `onProgress` の**後**に追加していたため、トレーリングラムダが
     *  `quantitativeRangeEval`（Boolean）へ誤束縛され Android ビルドだけが壊れていた
     *  （host JVM は UI 層を含まないため検出不能だった＝v6-engine-check で発覚）。
     *  同じ呼出形をホストで固定し、次に同じ順序ミスが起きたらここでコンパイルが落ちる。 */
    @Test
    fun handleOptimizeAcceptsOnProgressAsTrailingLambdaLikeProductionCallers() = runBlocking {
        val s = state()
        var progressCalls = 0
        val res = V6FinalPort.handleOptimize(s, secondsRaw = 1, workers = 1, requestedAlgorithm = V6Algorithm.V5, allowImpossible = true) { phase, _, _, _ ->
            if (phase.isNotEmpty()) progressCalls++
        }
        assertTrue("onProgressがString/ViolationReport?/Long/Longとして正しく呼ばれる", progressCalls > 0)
        assertEquals(0, res.report.hard)
    }

    @Test
    fun wishForCappedShiftStaysPinnedAndIsTheOnlyPlacement() = runBlocking {
        val s = state(wishes = mapOf("0,1" to 1))
        val r = V6NativeOptimizer.optimize(s, options = V6OptimizerOptions(algorithm = V6Algorithm.V5, totalBudgetSec = 2, workers = 1,
            softPolish = false, restarts = 0, seed = 7L, postPolish = false))
        assertEquals(1, r.schedule[0][1])
        assertEquals(1, countA(r.schedule, 0))
    }
}
