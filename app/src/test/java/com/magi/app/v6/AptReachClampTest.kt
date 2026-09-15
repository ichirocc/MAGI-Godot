package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Test

/** 群目標（apt）の適用規則（決定 D9）: 個人の下限または上限が入っている組には群目標を適用しない。
 *  適用される組は「構造的に到達できる範囲」へ収める（3.508.0）。 */
class AptReachClampTest {
    private val REST = 0; private val A = 1; private val B = 2

    private fun state(ranges: Map<String, Range>, aptA: String, wishes: Map<String, Int> = emptyMap(), groupShiftA: Int = 1, staff2: Boolean = false) = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-05",
        shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "", ""), Shift("B", "B", "", "")),
        groups = listOf(Group("G", "G")), staff = if (staff2) listOf(Staff("s0", 0), Staff("s1", 0)) else listOf(Staff("s0", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, groupShiftA, 1)), groupShiftApt = listOf(listOf("", aptA, "")),
        schedule = List(if (staff2) 2 else 1) { List(5) { REST } }, wishes = wishes, staffRange = ranges,
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    private fun aptA(st: MagiState, i: Int = 0) = Problem(st).apt[i][A]

    @Test fun noPersonalSettingAppliesTheGroupTarget() {
        assertEquals(2, aptA(state(emptyMap(), "2")))
    }

    @Test fun anyPersonalBoundDisablesTheGroupTarget() {
        assertEquals(-1, aptA(state(mapOf("0,$A" to Range("1", "")), "2")))      // 下限のみ
        assertEquals(-1, aptA(state(mapOf("0,$A" to Range("", "4")), "2")))      // 上限のみ
        assertEquals(-1, aptA(state(mapOf("0,$A" to Range("1", "4")), "2")))     // 下限・上限
        val fixed = state(mapOf("0,$A" to Range("1", "1")), "2")                  // 固定値
        assertEquals(-1, aptA(fixed))
        // 固定値の職員が A を 3 回持つと high 違反だけが出て apt は出ない
        val rep = UnifiedViolationChecker.check(fixed, arrayOf(intArrayOf(A, A, A, REST, REST)))
        assertEquals(2, rep.breakdown["high"] ?: 0); assertEquals(0, rep.breakdown["apt"] ?: 0)
    }

    @Test fun blankOnlyPersonalKeyKeepsTheGroupTarget() {
        assertEquals(2, aptA(state(mapOf("0,$A" to Range("", "")), "2")))
    }

    @Test fun personalSettingActsOnlyOnItsOwnStaffAndShift() {
        val other = state(mapOf("1,$A" to Range("1", "4")), "2", staff2 = true)   // 別職員
        assertEquals(2, aptA(other, 0)); assertEquals(-1, aptA(other, 1))
        assertEquals(2, aptA(state(mapOf("0,$B" to Range("1", "4")), "2")))       // 別シフト
    }

    @Test fun notAssignableOrNegativeTargetsAreNotApplied() {
        assertEquals(-1, aptA(state(emptyMap(), "2", groupShiftA = 0)))           // 担当不可
        assertEquals(-1, aptA(state(emptyMap(), "-1")))                           // 負の群目標
    }

    @Test fun appliedTargetIsClampedToTheReachableRange() {
        // 休 2〜2・B 0〜0（A 自身には設定なし）→ 5 日のうち 3 日は A にしか置けない。群目標 1 は 3 へ。
        assertEquals(3, aptA(state(mapOf("0,$REST" to Range("2", "2"), "0,$B" to Range("0", "0")), "1")))
        // 他シフトが無制限なら不変
        assertEquals(1, aptA(state(mapOf("0,$REST" to Range("2", "2")), "1")))
        // 休の希望固定 3 日 → A に使える日は最大 2。群目標 4 は 2 へ。
        assertEquals(2, aptA(state(emptyMap(), "4", wishes = mapOf("0,0" to REST, "0,1" to REST, "0,2" to REST))))
    }
}
