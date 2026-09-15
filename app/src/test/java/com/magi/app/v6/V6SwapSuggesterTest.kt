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
 * [重複排除の頑健化] `FixSuggester.suggest` が実質同一の盤面変化を複数回返さないことを検証する。
 * 旧署名(kind名+ops列挙順)は①SWAP_XDAYが起点(i1,j1)/(i2,j2)どちらから見るかでopsが逆順生成され別署名化
 * ②Phase5(SWAP_XDAY)がj2==j1(同日)を除外していないためPhase2(SWAP)と同じ盤面変化を別kindで重複生成、
 * の2種で同一の手を複数回表示していた。両方の違反(low/high)を1回のスワップで同時解消できる最小盤面を
 * 用意し、その唯一の解が重複なく1件だけ返ることを確認する。
 */
class V6SwapSuggesterTest {

    private fun shifts() = listOf(Shift("Y", "Y", "", ""), Shift("X", "X", "", ""))

    @Test
    fun suggestDoesNotDuplicateSameBoardChangeAcrossKinds() {
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("s0", 0), Staff("s1", 0))
        val schedule = listOf(
            listOf(0, 0),   // s0: Y,Y（Xのlo=1を満たさない＝low違反）
            listOf(1, 0),   // s1: X,Y（Xのhi=0を超える＝high違反）
        )
        val st = MagiState(
            startDate = "2026-01-01", endDate = "2026-01-02",
            shifts = shifts(), groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = List(1) { List(2) { "" } },
            schedule = schedule,
            wishes = emptyMap(),
            staffRange = mapOf(
                "0,1" to Range(lo = "1", hi = ""),
                "1,1" to Range(lo = "", hi = "0"),
            ),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals("初期 HARD=0", 0, before.hard)
        assertTrue("初期 low+high 違反あり", (before.breakdown["low"] ?: 0) + (before.breakdown["high"] ?: 0) > 0)

        val results = FixSuggester.suggest(st, sched, maxResults = 20, deadlineMs = 8000L)

        // day を含めた正規化署名（本体のsigとは独立に、テスト側で盤面変化の実体を数える）。
        fun normSig(sug: FixSuggestion): String {
            val real = sug.ops.filter { it.toShift != sched[it.staff][it.day] }
            return real.sortedWith(compareBy({ it.staff }, { it.day })).joinToString("|") { "${it.staff}.${it.day}.${it.toShift}" }
        }
        val sigs = results.map { normSig(it) }
        assertEquals("同一の盤面変化が重複して返らないこと", sigs.size, sigs.toSet().size)

        // 低/高を同時に解消する「同日スワップ」に相当する提案が、kind(SWAP/SWAP_XDAY)やops順に依らず
        // 重複なくちょうど1件だけ含まれること。
        val fullFix = results.filter { sug ->
            val real = sug.ops.filter { it.toShift != sched[it.staff][it.day] }
            real.size == 2 &&
                real.any { it.staff == 0 && it.day == 0 && it.toShift == 1 } &&
                real.any { it.staff == 1 && it.day == 0 && it.toShift == 0 }
        }
        assertEquals("低/高を同時に解消する同日スワップは重複なく1件のみ", 1, fullFix.size)
    }

    /** [3.507.4] 下限割れ（lo=1）でも上限 0 のシフトは連鎖でも置かない＝最適化器（mayPlace）と同じ。単一変更は allowedShiftsForStaff で元から除外。 */
    @Test
    fun chainDoesNotPlaceShiftsWithZeroCap() {
        val st = com.magi.app.model.MagiState(
            startDate = "2026-08-01", endDate = "2026-08-03",
            shifts = listOf(com.magi.app.model.Shift("休", "休", "", ""), com.magi.app.model.Shift("A", "A", "", "")),
            groups = listOf(com.magi.app.model.Group("G", "G")), staff = listOf(com.magi.app.model.Staff("X", 0), com.magi.app.model.Staff("Y", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(0, 0, 0), listOf(1, 1, 0)), wishes = emptyMap(),
            staffRange = mapOf("0,1" to com.magi.app.model.Range("2", "0")),   // X の A: 下限 2・上限 0（矛盾した設定）
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.map { it.toIntArray() }.toTypedArray()
        assertTrue(UnifiedViolationChecker.check(st, sched).countViolations.containsKey("0,1"))
        val results = FixSuggester.suggest(st, sched, maxResults = 20, deadlineMs = 4000L)
        assertTrue(results.map { it.label }.toString(), results.none { r -> r.ops.any { it.staff == 0 && it.toShift == 1 } })
    }
}
