package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.272.0] ConstraintMus（Constraint IR + deletion-based MUS）の検証。
 * 各テストは「極小コアの正確な構成」まで固定する（極小性: どの1件を外しても証明が崩れる構成を
 * 手計算で設計済み＝コアは一意）。
 */
class ConstraintMusTest {

    private fun state(
        days: Int,
        shifts: List<Shift>,
        wishes: Map<String, Int> = emptyMap(),
        staffRange: Map<String, Range> = emptyMap(),
        cons1: List<C1Row> = emptyList(),
        staffCount: Int = 1,
    ): MagiState {
        val end = "2026-01-" + days.toString().padStart(2, '0')
        return MagiState(
            startDate = "2026-01-01", endDate = end,
            shifts = shifts,
            groups = listOf(Group("G", "G")),
            staff = List(staffCount) { Staff("s$it", 0) },
            use2Patterns = false,
            groupShift = listOf(List(shifts.size) { 1 }),
            groupShiftApt = listOf(List(shifts.size) { "" }),
            schedule = List(staffCount) { List(days) { 0 } },
            wishes = wishes,
            staffRange = staffRange,
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = cons1, cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    private fun List<ConstraintMus.Item>.countOf(cls: Class<*>) = count { cls.isInstance(it) }

    @Test
    fun staffMusCapVersusPinnedWishes() {
        // X上限1に対しXへの固定希望が2件 → {上限, 希望, 希望} の3件が極小コア。
        val st = state(
            days = 5,
            shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", "")),
            wishes = mapOf("0,0" to 1, "0,2" to 1),
            staffRange = mapOf("0,1" to Range("", "1")),
        )
        val res = ConstraintMus.analyzeStaffConflicts(Problem(st))
        assertEquals(1, res.size)
        val core = res[0].core
        assertEquals(3, core.size)
        assertEquals(1, core.countOf(ConstraintMus.RangeCap::class.java))
        assertEquals(2, core.countOf(ConstraintMus.WishPin::class.java))
    }

    @Test
    fun staffMusFloorPlusWishesPigeonhole() {
        // T=5でX下限3＋休への固定希望3件 → 需要合計6>5（鳩の巣）。どの1件を外しても5以下=コアは4件で一意。
        val st = state(
            days = 5,
            shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", "")),
            wishes = mapOf("0,0" to 0, "0,1" to 0, "0,2" to 0),
            staffRange = mapOf("0,1" to Range("3", "")),
        )
        val res = ConstraintMus.analyzeStaffConflicts(Problem(st))
        assertEquals(1, res.size)
        val core = res[0].core
        assertEquals(4, core.size)
        assertEquals(1, core.countOf(ConstraintMus.RangeFloor::class.java))
        assertEquals(3, core.countOf(ConstraintMus.WishPin::class.java))
    }

    @Test
    fun staffMusWindowRulePlusWishes() {
        // 窓ルール「X 5日で1回以上」(最小1日) ＋ 全5日が休への固定希望 → 1+5=6>5（鳩の巣）。
        val st = state(
            days = 5,
            shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", "")),
            wishes = mapOf("0,0" to 0, "0,1" to 0, "0,2" to 0, "0,3" to 0, "0,4" to 0),
            cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "1")),
        )
        val res = ConstraintMus.analyzeStaffConflicts(Problem(st))
        assertEquals(1, res.size)
        val core = res[0].core
        assertEquals(6, core.size)
        assertEquals(1, core.countOf(ConstraintMus.WindowRule::class.java))
        assertEquals(5, core.countOf(ConstraintMus.WishPin::class.java))
    }

    @Test
    fun dayMusCoverageBlockedByWishes() {
        // 日0: X必要1人・担当可能な2人とも休への固定希望 → {必要人数, 希望, 希望} の3件が極小コア。
        // 日1は希望なしで充足可能=矛盾なし。
        val st = state(
            days = 2,
            shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "1", "")),
            wishes = mapOf("0,0" to 0, "1,0" to 0),
            staffCount = 2,
        )
        val res = ConstraintMus.analyzeDayConflicts(Problem(st))
        assertEquals(1, res.size)
        assertEquals(0, res[0].day)
        val core = res[0].core
        assertEquals(3, core.size)
        assertEquals(1, core.countOf(ConstraintMus.DayNeed::class.java))
        assertEquals(2, core.countOf(ConstraintMus.WishPin::class.java))
    }

    @Test
    fun engineFindsWishFreeConflictButGuidanceSuppressesIt() {
        // 2b-3系（上限×窓・希望なし）: エンジンは {上限, 窓ルール} の2件コアを見つけるが、
        // buildGuidance 検査9は既存検査との重複回避のため「希望を含むコアのみ」を出す。
        val st = state(
            days = 10,
            shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", "")),
            staffRange = mapOf("0,1" to Range("", "1")),
            cons1 = listOf(C1Row(day1 = "5", shiftKigou = "X", day2 = "2")),
        )
        val res = ConstraintMus.analyzeStaffConflicts(Problem(st))
        assertEquals(1, res.size)
        assertEquals(2, res[0].core.size)
        assertEquals(1, res[0].core.countOf(ConstraintMus.RangeCap::class.java))
        assertEquals(1, res[0].core.countOf(ConstraintMus.WindowRule::class.java))
        val issues = V6SanityPort.buildGuidance(st)
        assertTrue("既存の2b-3が担当することの確認", issues.any { it.problem.contains("この人だけではどう配置しても") })
        assertFalse("希望なしコアは検査9から出さない（重複ゼロ）", issues.any { it.problem.contains("同時に成立しません") })
    }

    @Test
    fun guidanceEmitsDayConflictWithWishLabels() {
        val st = state(
            days = 2,
            shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "1", "")),
            wishes = mapOf("0,0" to 0, "1,0" to 0),
            staffCount = 2,
        )
        val issues = V6SanityPort.buildGuidance(st)
        val hit = issues.filter { it.where.contains("必要人数と固定希望の衝突") }
        assertEquals(1, hit.size)
        assertTrue(hit[0].problem.contains("同時に成立しません"))
        assertTrue("コアの希望が名前つきで列挙される", hit[0].problem.contains("希望「"))
        assertTrue("緩和候補として希望調整を提案する", hit[0].fix.contains("希望を1件調整"))
    }

    @Test
    fun noConflictYieldsNothing() {
        val st = state(
            days = 5,
            shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "1", "")),
            wishes = mapOf("0,0" to 1),
            staffRange = mapOf("0,1" to Range("1", "5")),
            staffCount = 2,
        )
        assertTrue(ConstraintMus.analyzeStaffConflicts(Problem(st)).isEmpty())
        assertTrue(ConstraintMus.analyzeDayConflicts(Problem(st)).isEmpty())
    }
}
