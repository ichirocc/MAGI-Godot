package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [3.541.0] fair 達成率モード v2 の 3 つの決定を固定する: (1) 帯の外はクランプ（範囲外の個人が他メンバーを罰しない）、
 * (2) 担当不可（上限 0）で 0 回の職員は母集団から外す、(3) 基準達成率は幅を重みにした中央値（帯の端にいる 1 人に引きずられない）。
 * 数値は docs/history 3.541.0 の実データ手計算と同じ構図を 1 群に縮めたもの。
 */
class FairAchievementV2Test {
    /** 1 群 × シフト X。staffRange/apt/回数を与えて `fairDevOfBucket` を直接評価する。 */
    private fun fairOf(n: Int, ranges: Map<Int, Range>, aptX: String, counts: IntArray, hiZero: Set<Int> = emptySet()): Int {
        val shifts = listOf(Shift("休み", "休", "", ""), Shift("X", "X", "", ""))
        val t = 31
        val schedule = List(n) { i -> List(t) { j -> if (j < counts[i]) 1 else 0 } }
        val sr = HashMap<String, Range>()
        for ((i, r) in ranges) sr["$i,1"] = r
        for (i in hiZero) sr["$i,1"] = Range("0", "0")
        val st = MagiState(
            startDate = "2026-10-01", endDate = "2026-10-31",
            shifts = shifts, groups = listOf(Group("G", "G")), staff = List(n) { Staff("S$it", 0) }, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", aptX)),
            schedule = schedule, wishes = emptyMap(), staffRange = sr,
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = Problem(st)
        val res = p.fairDevOfBucket(0, 1) { counts[it] }
        val rest = p.fairDevOfBucket(0, 0) { t - counts[it] }
        assertEquals("チェッカーの fair ＝ 休バケット＋X バケット", res.total + rest.total, UnifiedViolationChecker.check(st, st.schedule.toIntArray2D()).breakdown["fair"] ?: 0)
        return res.total
    }

    @Test
    fun outOfBandMemberIsClampedAndDoesNotPunishOthers() {
        // A: 範囲 0〜1 で 6 回（達成率は 6.0 でなくクランプで 1.0）、B: 目標 10 で 9 回、C: 10〜10 ピン。
        val fair = fairOf(3, mapOf(0 to Range("0", "1"), 2 to Range("10", "10")), "10", intArrayOf(6, 9, 10))
        // 重み付き中央値＝B の −0.1（幅 10 が総幅 10.5 の半分を超える）→ A: round(1.1×0.5)=1、B: 0、C: 0。
        assertEquals(1, fair)
    }

    @Test
    fun unplaceableZeroCountMemberIsExcludedFromLegacyPopulation() {
        // 基準なし（apt 空欄）→ 旧式。C は上限 0 で 0 回＝除外 → [9,3] の round(平均)=6 → 3+3。
        assertEquals(6, fairOf(3, emptyMap(), "", intArrayOf(9, 3, 0), hiZero = setOf(2)))
    }

    @Test
    fun weightedMedianIgnoresTheMemberAtTheEdgeOfHisOwnBand() {
        // A: 範囲 1〜2 で 3 回（クランプ 2＝達成率 1.0、幅 0.5）、B/C/D: 目標 10 で 7/4/4 回。
        // 中央値は −0.6（B..D の幅 10 が支配）→ A: round(1.6×0.5)=1、B: round(0.3×10)=3、C/D: 0 → 4。単純平均なら 13。
        assertEquals(4, fairOf(4, mapOf(0 to Range("1", "2")), "10", intArrayOf(3, 7, 4, 4)))
    }

    @Test
    fun singleOrNoScalePopulationIsZero() {
        // 全員ピン＝幅 0 の合計 0 → 偏差 0（旧: 幅 0 の人は |回数−基準| を件数化していた）。
        assertEquals(0, fairOf(2, mapOf(0 to Range("5", "5"), 1 to Range("5", "5")), "", intArrayOf(5, 7)))
    }
}
