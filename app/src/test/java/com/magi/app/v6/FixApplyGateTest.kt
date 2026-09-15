package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提案の適用直前に仮盤面で再評価し、改善しない・固定を崩す提案は入力の盤面を変えずに拒否する。 */
class FixApplyGateTest {
    private val REST = 0; private val A = 1

    private fun state(wishes: Map<String, Int> = emptyMap(), ranges: Map<String, Range> = emptyMap()) = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-02",
        shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "1", "1")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("s0", 0), Staff("s1", 0)), use2Patterns = true,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        // 1 日目は A が 0 人（不足 1）、2 日目は A が 2 人（過剰 1）
        schedule = listOf(listOf(REST, A), listOf(REST, A)), wishes = wishes, staffRange = ranges,
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )
    private fun sched(st: MagiState) = Array(st.staffCount) { st.schedule[it].toIntArray() }

    @Test fun improvingOpsAreAppliedToACopy() {
        val st = state(); val s = sched(st)
        val r = FixApplyGate.apply(st, s, listOf(FixCell(0, 0, A)))
        assertTrue(r is FixApplyGate.Outcome.Applied)
        r as FixApplyGate.Outcome.Applied
        assertTrue(r.after.hard < r.before.hard)
        assertEquals(A, r.schedule[0][0]); assertEquals(REST, s[0][0])   // 入力は不変
    }

    @Test fun nonImprovingAndPinBreakingOpsAreRejected() {
        val st = state(); val s = sched(st)
        // 2 日目の過剰(covO, SOFT)を減らす手は hard 同値・重み減＝改善として通る
        assertTrue(FixApplyGate.apply(st, s, listOf(FixCell(0, 1, REST))) is FixApplyGate.Outcome.Applied)
        // 既に反映済みの手を再提案しても盤面は同じ＝改善なしで拒否
        val s2 = sched(st); s2[0][0] = A
        val same = FixApplyGate.apply(st, s2, listOf(FixCell(0, 0, A)))
        assertTrue(same is FixApplyGate.Outcome.Rejected)
        assertArrayEquals(intArrayOf(A, A), s2[0])
        // 希望固定セルを変える提案は拒否
        val locked = state(wishes = mapOf("0,0" to REST))
        assertTrue(FixApplyGate.apply(locked, sched(locked), listOf(FixCell(0, 0, A))) is FixApplyGate.Outcome.Rejected)
        // 厳密ピン（s0 の A は 1〜1）を 2 にする提案は拒否（hard は減るが固定を崩す）
        val pinned = state(ranges = mapOf("0,$A" to Range("1", "1")))
        val pr = FixApplyGate.apply(pinned, sched(pinned), listOf(FixCell(0, 0, A)))
        assertTrue(pr is FixApplyGate.Outcome.Rejected)
    }
}
