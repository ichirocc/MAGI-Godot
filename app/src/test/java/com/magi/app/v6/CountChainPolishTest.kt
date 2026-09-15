package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [3.540.0/測定中] CountChainPolish の検証。
 * - 実データ相当（golden）で HARD 不変・weightedScore 非増加・決定的（同 seed で同盤面）を固定する。
 * - 合成盤面: 職員 A の X 上限超過を、同日の 2 人交換 1 手では相手 B の上限超過に変わるだけ（同点＝却下）だが、
 *   別の日でもう 1 手交換して B が X を C（上限なし）へ渡せば連鎖全体で改善する＝多日の束でしか取れない手を採用することを確認する。
 */
class CountChainPolishTest {
    private fun golden(): MagiState {
        val f = listOf("src/test/resources/golden_state.json", "app/src/test/resources/golden_state.json").map(::File).first { it.exists() }
        return StateParser.parse(f.readText())!!
    }

    @Test
    fun goldenNeverWorsensAndIsDeterministic() {
        val st = golden()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        val r1 = CountChainPolish.applyCountChainPolish(st, sched.copy2D(), config = CountChainPolish.Config(maxMillis = 20_000L))
        val r2 = CountChainPolish.applyCountChainPolish(st, sched.copy2D(), config = CountChainPolish.Config(maxMillis = 20_000L))
        val after = UnifiedViolationChecker.check(st, r1.newSchedule)
        assertTrue("HARD 不増加", after.hard <= before.hard)
        assertTrue("weightedScore 非増加", after.weightedScore <= before.weightedScore)
        assertTrue("同 seed で同じ盤面", r1.newSchedule.contentDeepEquals(r2.newSchedule))
        assertEquals("報告と再検査が一致", after.weightedScore, r1.let { UnifiedViolationChecker.check(st, it.newSchedule).weightedScore }, 0.0)
    }

    /** 3 職員 × 31 日。X は毎日 1 人（lo=hi=1）、残りは Y/Z。A は X 上限 2 で 3 回持つ。B も X 上限 2 で 2 回。C は上限なし。 */
    private fun chainState(): MagiState {
        val shifts = listOf(Shift("休み", "休", "", ""), Shift("X", "X", "1", ""), Shift("Y", "Y", "", ""))
        val groups = listOf(Group("G", "G"))
        val t = 31
        val a = IntArray(t) { 2 }; val b = IntArray(t) { 2 }; val c = IntArray(t) { 2 }
        // X の担当: A が day 0,10,20、B が day 5,15、C が残り。
        for (j in 0 until t) { c[j] = 1 }
        for (j in listOf(0, 10, 20)) { a[j] = 1; c[j] = 2 }
        for (j in listOf(5, 15)) { b[j] = 1; c[j] = 2 }
        return MagiState(
            startDate = "2026-10-01", endDate = "2026-10-31",
            shifts = shifts, groups = groups, staff = listOf(Staff("A", 0), Staff("B", 0), Staff("C", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
            schedule = listOf(a.toList(), b.toList(), c.toList()),
            wishes = emptyMap(), staffRange = mapOf("0,1" to Range("", "2"), "1,1" to Range("", "2")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun excessIsRemovedByMultiDayChain() {
        val st = chainState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals("初期は A の X 上限超過 1 件", 1, before.breakdown["high"] ?: 0)
        val res = CountChainPolish.applyCountChainPolish(st, sched.copy2D())
        val after = UnifiedViolationChecker.check(st, res.newSchedule)
        assertEquals("上限超過が消える", 0, after.breakdown["high"] ?: 0)
        assertTrue("採用あり", res.applied > 0)
        assertTrue("HARD 不変", after.hard <= before.hard)
        assertTrue("weightedScore 減少", after.weightedScore < before.weightedScore)
        for (j in 0 until 31) assertEquals("X は毎日 1 人のまま", 1, (0 until 3).count { res.newSchedule[it][j] == 1 })
    }
}
