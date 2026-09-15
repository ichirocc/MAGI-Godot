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
 * [C3nMarginLnsPolish] `C3FamilyPolish.applyC3nPolish` はパターンがまたぐ日だけを1セルずつ独立に
 * 付け替えるため、単独では正味fireが減らず余白日を含めた複数セル同時変更でしか解けない局面には届かない。
 */
class C3nMarginLnsPolishTest {

    // shift index: 0=休 1=A 2=B（3種のみ＝A の後に安全な逃げ先(Z等)を用意しない構成が本パスの前提）
    private val kigou = listOf("休", "A", "B")
    private val rest = 0
    private val aShift = 1
    private val bShift = 2

    private fun state(
        schedule: List<List<Int>>,
        cons3n: List<C3Row>,
        wishes: Map<String, Int> = emptyMap(),
    ): MagiState = MagiState(
        startDate = "2026-12-01",
        endDate = "",
        shifts = kigou.map { Shift(it, it, "", "") },
        groups = listOf(Group("G", "G")),
        staff = List(schedule.size) { Staff("s$it", 0) },
        use2Patterns = false,
        groupShift = listOf(List(kigou.size) { 1 }),
        groupShiftApt = listOf(List(kigou.size) { "" }),
        schedule = schedule,
        wishes = wishes,
        staffRange = emptyMap(),
        needDay1 = emptyMap(),
        needDay2 = emptyMap(),
        cons1 = emptyList(),
        cons2 = emptyList(),
        cons3 = emptyList(),
        cons3n = cons3n,
        cons3m = emptyList(),
        cons3mn = emptyList(),
        cons41 = emptyList(),
        cons42 = emptyList(),
    )

    /**
     * 禁止連続2本[A,B]/[A,休]を隣接させた行(day4=A day5=A day6=B day7=休)。窓(5,6)のみ違反。
     * day5/day6のどちらか1日だけ変えても隣接窓が別の禁止連続にはまり直すため単独では解消できない
     * （A の直後は A 以外に進めない連鎖）。余白日 day4/day7 まで含めた同時変更で初めて 0 になる。
     */
    private fun chainFixture(): MagiState {
        val days = 12
        val row = MutableList(days) { rest }
        row[4] = aShift; row[5] = aShift; row[6] = bShift; row[7] = rest
        return state(
            schedule = listOf(row),
            cons3n = listOf(C3Row(listOf("A", "B")), C3Row(listOf("A", "休"))),
        )
    }

    @Test
    fun singleCellPolishCannotResolveButMarginLnsDoes() {
        val st = chainFixture()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals("前提: 禁止連続がちょうど1件成立", 1, before.breakdown["c3n"] ?: 0)
        assertEquals("前提: HARD", 1, before.hard)

        // 既存の1セル研磨は構造的に頭打ち(適用0・c3n不変)であることを固定する。
        val single = C3FamilyPolish.applyC3nPolish(st, sched.copy2D(), maxPasses = 3, seed = 7L)
        val afterSingle = UnifiedViolationChecker.check(st, single.newSchedule)
        assertEquals("1セル研磨は適用0(頭打ち)", 0, single.applied)
        assertEquals("1セル研磨ではc3nが残る", 1, afterSingle.breakdown["c3n"] ?: 0)

        val result = C3nMarginLnsPolish.apply(st, sched.copy2D(), marginDays = 1, maxPasses = 3, seed = 0xC3E9L)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)
        assertEquals("前後余白込みLNSは適用あり", true, result.applied > 0)
        assertEquals("禁止連続が解消", 0, after.breakdown["c3n"] ?: -1)
        assertEquals("HARDも0へ", 0, after.hard)
        assertTrue("keep-best: total は改善または同値", after.total <= before.total)
    }

    @Test
    fun isNoOpWhenNoForbiddenRuleExists() {
        val st = chainFixture().copy(cons3n = emptyList())
        val sched = st.schedule.toIntArray2D()
        val result = C3nMarginLnsPolish.apply(st, sched.copy2D())
        assertEquals(0, result.applied)
        assertEquals(sched.map { it.toList() }, result.newSchedule.map { it.toList() })
    }

    @Test
    fun doesNotMoveWishLockedCells() {
        // day4(パターン先頭に隣接する余白日)を希望固定 → destroy集合から除外され不変のまま。
        val base = chainFixture()
        val st = base.copy(wishes = mapOf("0,4" to aShift))
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)

        val result = C3nMarginLnsPolish.apply(st, sched.copy2D(), marginDays = 1, maxPasses = 3, seed = 0xC3E9L)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("希望固定日は不変", aShift, result.newSchedule[0][4])
        assertTrue("keep-best: HARDは増えない", after.hard <= before.hard)
    }

    @Test
    fun doesNotRegressWithDefaultParams() {
        val st = chainFixture()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)

        val result = C3nMarginLnsPolish.apply(st, sched.copy2D())
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertTrue("keep-best: HARDは増えない(既定パラメータでも)", after.hard <= before.hard)
        assertTrue("keep-best: totalは増えない", after.total <= before.total)
    }

    @Test
    fun isDeterministic() {
        val st = chainFixture()
        val sched = st.schedule.toIntArray2D()
        val r1 = C3nMarginLnsPolish.apply(st, sched.copy2D(), marginDays = 1, maxPasses = 3, seed = 0xC3E9L)
        val r2 = C3nMarginLnsPolish.apply(st, sched.copy2D(), marginDays = 1, maxPasses = 3, seed = 0xC3E9L)
        assertEquals(r1.applied, r2.applied)
        assertEquals(r1.newSchedule.map { it.toList() }, r2.newSchedule.map { it.toList() })
    }
}
