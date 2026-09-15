package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.495.0] 違反アンカー型・可変長ウィンドウ交換（`WindowMode.STRICT_WHOLE_WINDOW`）の回帰テスト。
 *  1. 挟まれセル（前日=N の連、翌日=希望固定 E、禁止 N→E、休の上限0）: アンカー日を含む窓を同じ日付範囲で
 *     他職員と一括交換し high が消える（希望固定は窓に含められない＝窓は希望日の手前で閉じる）。
 *  2. 回数不足（休 下限2、禁止の並び無し）: 「他職員が休を持つ日」からの逆引きで窓を作り、交換だけで low が消える。
 *  3. 窓の部分交換をしない: 窓内に希望固定があれば窓ごと不成立（交換されない）。
 */
class AnchoredWindowSwapTest {
    private fun base(schedule: List<List<Int>>, wishes: Map<String, Int>, staffRange: Map<String, Range>, cons3n: List<C3Row>) = MagiState(
        startDate = "2026-06-01", endDate = "2026-06-06",
        shifts = listOf(Shift("休", "休", "", ""), Shift("N", "N", "", ""), Shift("E", "E", "", "")),
        groups = listOf(Group("A", "A")),
        staff = listOf(Staff("甲", 0), Staff("乙", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)),
        groupShiftApt = listOf(listOf("", "", "")),
        schedule = schedule, wishes = wishes, staffRange = staffRange,
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = cons3n,
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )
    private fun run(s: MagiState) = AdaptiveBlockSwapPolish.applyAdaptiveBlockSwapPolish(
        s, s.schedule.toIntArray2D(), mode = WindowMode.STRICT_WHOLE_WINDOW, maxPasses = 3, maxEvaluations = 48)

    @Test
    fun wholeWindowExchangeFreesTheSandwichedDay() {
        // 甲: N N 休 E* E E（3日目が挟まれセル・休は上限0） / 乙: E E E E N N
        val s = base(listOf(listOf(1, 1, 0, 2, 2, 2), listOf(2, 2, 2, 2, 1, 1)), mapOf("0,3" to 2), mapOf("0,0" to Range("0", "0")),
            listOf(C3Row(listOf("N", "E", "", "", ""))))
        val before = UnifiedViolationChecker.check(s, s.schedule.toIntArray2D())
        assertEquals(1, before.breakdown["high"])
        val r = run(s)
        val after = UnifiedViolationChecker.check(s, r.newSchedule)
        assertTrue("採用が1回以上: ${r.logs.first().message}", r.applied >= 1)
        assertEquals(0, after.breakdown["high"] ?: 0)
        assertEquals(0, after.breakdown["c3n"] ?: 0)
        assertEquals("希望は固定のまま", 2, r.newSchedule[0][3])
        assertTrue(betterReport(after, before))
    }

    @Test
    fun reverseLookupWindowFixesACountShortfallWithoutAnyForbiddenRule() {
        // 甲: N N E E E E（休0回、下限2） / 乙: 休 休 N N E E → 1〜2日目の窓交換で甲の休が2回に。
        val s = base(listOf(listOf(1, 1, 2, 2, 2, 2), listOf(0, 0, 1, 1, 2, 2)), emptyMap(), mapOf("0,0" to Range("2", "")), emptyList())
        val before = UnifiedViolationChecker.check(s, s.schedule.toIntArray2D())
        assertEquals(2, before.breakdown["low"])
        val r = run(s)
        val after = UnifiedViolationChecker.check(s, r.newSchedule)
        assertTrue("採用が1回以上: ${r.logs.first().message}", r.applied >= 1)
        assertEquals(0, after.breakdown["low"] ?: 0)
        assertEquals(2, r.newSchedule[0].count { it == 0 })
        assertTrue(betterReport(after, before))
    }

    @Test
    fun windowsContainingAWishAreRejectedAsAWhole() {
        // 2. と同じだが乙の1日目の休が希望固定＝1〜2日目の窓は不成立。2日目だけの窓(長さ1)は成立し得るので
        //    休が1回増える手（low 2→1）は採れる＝部分交換でなく「別の窓」で直ることを確認。
        val s = base(listOf(listOf(1, 1, 2, 2, 2, 2), listOf(0, 0, 1, 1, 2, 2)), mapOf("1,0" to 0), mapOf("0,0" to Range("2", "")), emptyList())
        val r = run(s)
        assertEquals("希望固定セルは動かない", 0, r.newSchedule[1][0])
        assertEquals("甲の1日目は希望固定の窓に含まれないので変わらない", 1, r.newSchedule[0][0])
    }
}
