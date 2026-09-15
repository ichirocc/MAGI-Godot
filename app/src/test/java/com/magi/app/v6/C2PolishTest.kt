package com.magi.app.v6

import com.magi.app.model.C2Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.511.3/測定中] C2Polish の検証。c2 は二値フラグ（不足量 2 以上でも 1 件の違反）のため、1 セルずつの
 * 判定では中間手が同点で却下される＝deficit 分をまとめて一括適用しないと解消できないことを固定する。
 * T は実運用に近い1か月（31日）を使う: 短すぎる期間だと weekly（シフトごとの曜日別 L1 偏差）が
 * 相対的に過敏になり、c2 を直す手が weekly の悪化で相殺されて `betterReport` に却下される
 * （小さい合成盤面で確かめようとして踏んだ罠。以後の同種テストへの教訓）。
 */
class C2PolishTest {
    private fun monthState(wishes: Map<String, Int> = emptyMap()): MagiState {
        val shifts = listOf(Shift("休み", "休", "", ""), Shift("P", "P", "", ""), Shift("Q", "Q", "", ""))
        val groups = listOf(Group("GA", "GA"))
        val t = 31
        // P は7日おき(同じ曜日)に5回、残りはQ。cons2でP>=10を要求＝不足5。
        val schedule = listOf((0 until t).map { if (it % 7 == 0) 1 else 2 })
        return MagiState(
            startDate = "2026-02-01", endDate = "2026-03-03",
            shifts = shifts, groups = groups, staff = listOf(Staff("A", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1)), groupShiftApt = listOf(listOf("", "", "")),
            schedule = schedule,
            wishes = wishes, staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = listOf(C2Row("P", "10")), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun deficitOfFiveIsFilledInOneBatchWhenEnoughSafeDaysExist() {
        val st = monthState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals("初期は c2 違反1件", 1, before.breakdown["c2"] ?: 0)
        val res = C2Polish.applyC2Polish(st, sched.copy2D())
        val after = UnifiedViolationChecker.check(st, res.newSchedule)
        assertEquals("c2 解消", 0, after.breakdown["c2"] ?: 0)
        assertTrue("採用された", res.applied > 0)
        assertTrue("HARD は不変(=0)", after.hard <= before.hard)
        assertTrue("重み付きスコアが増えない", after.weightedScore <= before.weightedScore)
    }

    @Test
    fun insufficientSafeDaysLeavesTheStaffUntouched() {
        // day5以降(かつ7日おきのP日を除く)を希望固定にして、安全な変換先をday1..4の4日だけに絞る＝
        // 不足5に届かず全く触らない（一部だけ変換する中途半端な適用はしない設計の確認）。
        val wishes = (5 until 31).filter { it % 7 != 0 }.associate { "0,$it" to 2 }
        val st = monthState(wishes)
        val sched = st.schedule.toIntArray2D()
        val res = C2Polish.applyC2Polish(st, sched.copy2D())
        assertEquals("割当は一切変化しない", st.schedule[0], res.newSchedule[0].toList())
        assertEquals(0, res.applied)
    }

    @Test
    fun enablingInTheFullChainResolvesC2WithoutWorseningTheBoard() {
        val st = monthState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        val on = V6HotfixPasses.runPostOptimization(st, sched.copy2D(), "t", seed = 7L, params = V6HotfixPasses.PostOptimizationParams(deterministic = true, c2PolishEnabled = true))
        assertEquals(0, on.report.breakdown["c2"] ?: -1)
        assertTrue("HARD が増えない", on.report.hard <= before.hard)
        assertTrue("重み付きスコアが増えない", on.report.weightedScore <= before.weightedScore)
    }
}
