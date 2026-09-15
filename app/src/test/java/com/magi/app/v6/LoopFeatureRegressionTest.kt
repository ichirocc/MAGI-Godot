package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.C3Row
import com.magi.app.model.C41Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自律改善ループ仕様 §4「機能同等性」の回帰スイート（14 機能）。各機能を最小盤面で作り、後処理チェーンを旧腕（成分修復 OFF）と
 *  新腕（ON）の両方で走らせて不変条件を検査する＝§7 報告の「機能同等性 14/14」の根拠。盤面は小さく、締切は 4 秒。 */
class LoopFeatureRegressionTest {
    private fun st(
        shifts: List<Shift>, staff: List<Staff>, groupShift: List<List<Int>>, schedule: List<List<Int>>,
        groups: List<Group> = listOf(Group("G", "G")), wishes: Map<String, Int> = emptyMap(), staffRange: Map<String, Range> = emptyMap(),
        cons1: List<C1Row> = emptyList(), cons3: List<C3Row> = emptyList(), cons3n: List<C3Row> = emptyList(), cons3mn: List<C3Row> = emptyList(),
        cons41: List<C41Row> = emptyList(), skillGroups: List<Group> = emptyList(), cons41s: List<C41Row> = emptyList(),
    ): MagiState {
        val t = schedule[0].size
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-%02d".format(t),
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = groups.map { shifts.map { "" } },
            schedule = schedule, wishes = wishes, staffRange = staffRange, needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = cons1, cons2 = emptyList(), cons3 = cons3, cons3n = cons3n, cons3m = emptyList(), cons3mn = cons3mn,
            cons41 = cons41, cons42 = emptyList(), skillGroups = skillGroups, cons41s = cons41s,
        )
    }
    private val rest = Shift("休", "休", "", "")
    private fun sh(k: String, need: String = "") = Shift(k, k, need, "")
    private fun sched(s: MagiState) = s.schedule.map { it.toIntArray() }.toTypedArray()

    /** 両腕で後処理を走らせ、各結果に [check] を当てる。旧腕＝成分修復 OFF、新腕＝ON。 */
    private fun both(s: MagiState, shouldStop: () -> Boolean = { false }, check: (String, V6PostOptimizationResult) -> Unit) {
        for ((arm, on) in listOf("旧" to false, "新" to true)) {
            val r = V6HotfixPasses.runPostOptimization(s, sched(s), "feature", seed = 11L, shouldStop = shouldStop,
                deadlineMs = EngineClock.nowMs() + 4000L, params = V6HotfixPasses.PostOptimizationParams(componentRepairEnabled = on))
            check(arm, r)
        }
    }
    private fun bd(r: V6PostOptimizationResult, key: String) = r.report.breakdown[key] ?: 0

    @Test fun f01_coverageShortageAndSurplusAreRepaired() {
        val shortage = st(listOf(rest, sh("A", "1")), listOf(Staff("X", 0), Staff("Y", 0)), listOf(listOf(1, 1)), listOf(listOf(1, 0), listOf(0, 0)))
        both(shortage) { arm, r -> assertEquals("$arm 人員不足", 0, r.report.hard) }
        val surplus = st(listOf(rest, sh("A", "1")), listOf(Staff("X", 0), Staff("Y", 0)), listOf(listOf(1, 1)), listOf(listOf(1, 1), listOf(1, 0)))
        both(surplus) { arm, r -> assertEquals("$arm 人員過剰", 0, bd(r, "covO")); assertEquals(0, r.report.hard) }
    }

    @Test fun f02_personalCountBoundsAreRestored() {
        val s = st(listOf(rest, sh("A", "1")), listOf(Staff("X", 0), Staff("Y", 0)), listOf(listOf(1, 1)),
            listOf(listOf(1, 1, 0), listOf(0, 0, 1)), staffRange = mapOf("0,1" to Range("1", "1")))
        both(s) { arm, r -> assertEquals("$arm 上限", 0, bd(r, "high")); assertEquals("$arm 下限", 0, bd(r, "low")); assertEquals(0, r.report.hard) }
    }

    @Test fun f03_groupCountConstraintIsSatisfied() {
        // 群 G0 は A を 0 回（c41 0〜0）。X(G0) が A・Y(G1) が休で A は毎日 1 名必要＝同日交換で直る（fair は 1 名群なので無関係）。
        val s = st(listOf(rest, sh("A", "1")), listOf(Staff("X", 0), Staff("Y", 1)), listOf(listOf(1, 1), listOf(1, 1)), listOf(listOf(1, 0), listOf(0, 1)),
            groups = listOf(Group("G0", "G0"), Group("G1", "G1")), cons41 = listOf(C41Row("G0", "A", "0", "0")))
        both(s) { arm, r -> assertEquals("$arm 群回数", 0, bd(r, "c41")); assertEquals(0, r.report.hard) }
    }

    @Test fun f04_forbiddenRunIsRemovedAndNoneCreated() {
        val s = st(listOf(rest, sh("A"), sh("B")), listOf(Staff("X", 0), Staff("Y", 0)), listOf(listOf(1, 1, 1)),
            listOf(listOf(1, 2, 0), listOf(0, 0, 0)), cons3n = listOf(C3Row(listOf("A", "B", "", "", ""))))
        both(s) { arm, r -> assertEquals("$arm 禁止連", 0, bd(r, "c3n")); assertEquals(0, r.report.hard) }
    }

    @Test fun f05_wishCellsStayPinned() {
        val s = st(listOf(rest, sh("A", "1")), listOf(Staff("X", 0), Staff("Y", 0)), listOf(listOf(1, 1)),
            listOf(listOf(0, 1, 0), listOf(1, 0, 1)), wishes = mapOf("0,1" to 1, "1,0" to 1))
        both(s) { arm, r -> assertEquals("$arm 希望 X2", 1, r.schedule[0][1]); assertEquals("$arm 希望 Y1", 1, r.schedule[1][0]); assertEquals(0, bd(r, "pref")) }
    }

    @Test fun f06_neighboursOfWishDaysArePolished() {
        val s = st(listOf(rest, sh("A"), sh("B")), listOf(Staff("X", 0), Staff("Y", 0)), listOf(listOf(1, 1, 1)),
            listOf(listOf(1, 2, 0), listOf(0, 0, 0)), wishes = mapOf("0,1" to 2), cons3mn = listOf(C3Row(listOf("A", "B", "", "", ""))))
        both(s) { arm, r -> assertEquals("$arm 希望は残る", 2, r.schedule[0][1]); assertEquals("$arm 回避連", 0, bd(r, "c3mn")) }
    }

    @Test fun f07_sameLengthSegmentExchangeFixesWindows() {
        val s = st(listOf(rest, sh("A", "1")), listOf(Staff("X", 0), Staff("Y", 0)), listOf(listOf(1, 1)),
            listOf(listOf(1, 1, 0, 0), listOf(0, 0, 1, 1)), cons1 = listOf(C1Row("2", "休", "1")))
        both(s) { arm, r -> assertEquals("$arm 窓", 0, bd(r, "c1")); assertEquals(0, r.report.hard) }
    }

    @Test fun f08_multiStaffCyclicExchangeResolvesConflicts() {
        val s = st(listOf(rest, sh("A", "1"), sh("B", "1")), listOf(Staff("X", 0), Staff("Y", 0), Staff("Z", 0)), listOf(listOf(1, 1, 1)),
            listOf(listOf(1, 1), listOf(2, 2), listOf(0, 0)), cons3n = listOf(C3Row(listOf("A", "A", "", "", ""))))
        both(s) { arm, r -> assertEquals("$arm 循環交換", 0, r.report.hard) }
    }

    @Test fun f09_onlyAssignableShiftsAreUsed() {
        val s = st(listOf(rest, sh("A", "1"), sh("B", "1")), listOf(Staff("X", 0), Staff("Y", 1)), listOf(listOf(1, 1, 0), listOf(1, 0, 1)),
            listOf(listOf(0, 1), listOf(2, 2)), groups = listOf(Group("G0", "G0"), Group("G1", "G1")))
        both(s) { arm, r ->
            val p = cachedProblem(s)
            for (i in 0 until p.S) for (j in 0 until p.T) assertTrue("$arm 担当外 ($i,$j)", p.canDo(i, r.schedule[i][j]))
            assertEquals("$arm 被覆", 0, r.report.hard)
        }
    }

    @Test fun f10_skillGroupConstraintIsSatisfied() {
        // スキル群 S0 は A を 0 回（c41s 0〜0）。X(S0) が A・Y(S1) が休で A は毎日 1 名必要＝同日交換で直る。単位群は別（1 名群＝fair 対象外）。
        val s = st(listOf(rest, sh("A", "1")), listOf(Staff("X", 0, 0), Staff("Y", 1, 1)), listOf(listOf(1, 1), listOf(1, 1)), listOf(listOf(1, 0), listOf(0, 1)),
            groups = listOf(Group("G0", "G0"), Group("G1", "G1")), skillGroups = listOf(Group("S0", "S0"), Group("S1", "S1")), cons41s = listOf(C41Row("S0", "A", "0", "0")))
        both(s) { arm, r -> assertEquals("$arm スキル群", 0, bd(r, "c41s")); assertEquals(0, r.report.hard) }
    }

    @Test fun f11_inputIsLeftUntouchedForUndo() {
        val s = st(listOf(rest, sh("A", "1")), listOf(Staff("X", 0), Staff("Y", 0)), listOf(listOf(1, 1)), listOf(listOf(1, 0), listOf(0, 0)))
        for (on in listOf(false, true)) {
            val input = sched(s); val copy = input.copy2D()
            val r = V6HotfixPasses.runPostOptimization(s, input, "feature", seed = 11L, deadlineMs = EngineClock.nowMs() + 4000L,
                params = V6HotfixPasses.PostOptimizationParams(componentRepairEnabled = on))
            assertTrue("入力は変えない（元に戻す用）", input.contentDeepEquals(copy))
            assertTrue("結果は別配列", r.schedule !== input)
        }
    }

    @Test fun f12_stopReturnsPromptlyWithoutWorsening() {
        val s = st(listOf(rest, sh("A", "1")), listOf(Staff("X", 0), Staff("Y", 0)), listOf(listOf(1, 1)), listOf(listOf(1, 0), listOf(0, 0)))
        val before = UnifiedViolationChecker.check(s, sched(s))
        val t0 = EngineClock.nowMs()
        both(s, shouldStop = { true }) { arm, r -> assertTrue("$arm 停止後も悪化しない", r.report.hard <= before.hard) }
        assertTrue("停止は速やか", EngineClock.nowMs() - t0 < 5000L)
    }

    @Test fun f13_monthBoundariesAreProtected() {
        for (t in 1..2) {
            val rows = listOf(List(t) { 1 }, List(t) { 0 })
            val s = st(listOf(rest, sh("A", "1")), listOf(Staff("X", 0), Staff("Y", 0)), listOf(listOf(1, 1)), rows,
                cons1 = listOf(C1Row("7", "休", "2")), cons3n = listOf(C3Row(listOf("A", "A", "A", "", ""))))
            val before = UnifiedViolationChecker.check(s, sched(s))
            both(s) { arm, r ->
                assertEquals("$arm T=$t 形", t, r.schedule[0].size)
                assertTrue("$arm T=$t 悪化しない", r.report.hard <= before.hard)
            }
        }
    }

    @Test fun f14_resultStaysWithinTheMonth() {
        val s = st(listOf(rest, sh("A", "1")), listOf(Staff("X", 0), Staff("Y", 0)), listOf(listOf(1, 1)), listOf(listOf(1, 0, 1), listOf(0, 0, 0)))
        both(s) { arm, r ->
            assertEquals("$arm 行数", 2, r.schedule.size)
            for (row in r.schedule) { assertEquals("$arm 列数", 3, row.size); for (k in row) assertTrue("$arm セル値", k in 0..1) }
            for (key in r.report.needViolations.keys) assertTrue("$arm 月内 $key", key.substringAfter(',').toInt() in 0..2)
        }
    }
}
