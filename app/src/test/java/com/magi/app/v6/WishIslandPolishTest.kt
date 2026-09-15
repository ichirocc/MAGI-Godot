package com.magi.app.v6

import com.magi.app.model.C3Row
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
 * [3.496.0] 希望島研磨の回帰テスト。
 *  1. 希望日の隣の違反（B の上限0超過）を、同日交換で直す。希望セルは不変。
 *  2. 希望の周辺に違反が無ければ島は起動せず何もしない。
 *  3. 全体は改善するが希望周辺が改善しない手は採らない（通常時の二重条件）。
 *  [3.498.0] 4. 前の島の採用で周辺の違反が消えた島は評価枠を使わない。
 *           5. 境界: 予算 0・負のパラメータ・1 日だけの期間・職員 0 名でも落ちず、盤面を変えない。
 */
class WishIslandPolishTest {
    // 0=休 1=A 2=B（被覆なし）
    private fun base(schedule: List<List<Int>>, wishes: Map<String, Int>, staffRange: Map<String, Range>) = MagiState(
        startDate = "2026-06-01", endDate = "2026-06-06",
        shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "", ""), Shift("B", "B", "", "")),
        groups = listOf(Group("G", "G")),
        staff = listOf(Staff("甲", 0), Staff("乙", 0), Staff("丙", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)),
        groupShiftApt = listOf(listOf("", "", "")),
        schedule = schedule, wishes = wishes, staffRange = staffRange,
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test
    fun sameDaySwapNextToAWishFixesTheViolationAndKeepsTheWish() {
        // 甲: A B A* 休 休 休（3日目=希望A、2日目の B が上限0超過） / 乙: 休 A 休 休 休 休 / 丙: 休 休 休 休 休 休
        val s = base(listOf(listOf(1, 2, 1, 0, 0, 0), listOf(0, 1, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0, 0)),
            mapOf("0,2" to 1), mapOf("0,2" to Range("0", "0")))
        val before = UnifiedViolationChecker.check(s, s.schedule.toIntArray2D())
        assertEquals(1, before.breakdown["high"])
        val r = WishIslandPolish.applyWishIslandPolish(s, s.schedule.toIntArray2D())
        val after = UnifiedViolationChecker.check(s, r.newSchedule)
        assertTrue("採用が1回以上: ${r.logs.first().message}", r.applied >= 1)
        assertEquals(0, after.breakdown["high"] ?: 0)
        assertEquals("希望セルは不変", 1, r.newSchedule[0][2])
        assertTrue(betterReport(after, before))
        assertTrue(r.logs.first().message.contains("同日"))
    }

    @Test
    fun islandsWithoutNearbyViolationsDoNotActivate() {
        val s = base(listOf(listOf(1, 1, 1, 0, 0, 0), listOf(0, 1, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0, 0)),
            mapOf("0,2" to 1), emptyMap())
        val r = WishIslandPolish.applyWishIslandPolish(s, s.schedule.toIntArray2D())
        assertEquals(0, r.applied)
        assertTrue(r.logs.first().message.contains("起動0件"))
    }

    @Test
    fun movesThatDoNotImproveTheWishNeighbourhoodAreNotTaken() {
        // 甲の希望は3日目。違反は丙の B 上限0超過（6日目、甲の島の影響範囲外）だが、甲の島は「甲の回数違反」が無いので
        //   起動しない＝丙の違反は本パスの対象外（他パスの仕事）。何も変えないこと。
        val s = base(listOf(listOf(1, 1, 1, 0, 0, 0), listOf(0, 1, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0, 2)),
            mapOf("0,2" to 1), mapOf("2,2" to Range("0", "0")))
        val r = WishIslandPolish.applyWishIslandPolish(s, s.schedule.toIntArray2D())
        assertEquals(0, r.applied)
        assertEquals(2, r.newSchedule[2][5])
    }

    @Test
    fun islandWhoseViolationWasFixedByAnEarlierIslandDoesNotSpendEvaluations() {
        // 甲: 希望A(3日目)、2日目の B が上限0超過。乙: 希望A(5日目)、B の下限1未達。
        // 甲(2日目 B)↔乙(2日目 休) の同日交換で両方の違反が消える → 乙の島は起動していたが、甲の採用後に評価しない。
        val s = base(listOf(listOf(1, 2, 1, 0, 0, 0), listOf(0, 0, 0, 0, 1, 0), listOf(0, 0, 0, 0, 0, 0)),
            mapOf("0,2" to 1, "1,4" to 1), mapOf("0,2" to Range("0", "0"), "1,2" to Range("1", "")))
        val r = WishIslandPolish.applyWishIslandPolish(s, s.schedule.toIntArray2D(),
            WishIslandPolish.Params(maxPasses = 1, maxEvaluations = 100, minIslandBudget = 50))
        val after = UnifiedViolationChecker.check(s, r.newSchedule)
        assertEquals(1, r.applied)
        assertEquals(0, (after.breakdown["high"] ?: 0) + (after.breakdown["low"] ?: 0))
        val evaluated = Regex("正式評価(\\d+)").find(r.logs.first().message)!!.groupValues[1].toInt()
        // 甲の島だけが評価枠を使う。乙の島（起動 2 件のうち後ろ）が評価されていれば 2 島分＝2 倍近い数になる。
        val alone = WishIslandPolish.applyWishIslandPolish(s.copy(wishes = mapOf("0,2" to 1), staffRange = mapOf("0,2" to Range("0", "0"))),
            s.schedule.toIntArray2D(), WishIslandPolish.Params(maxPasses = 1, maxEvaluations = 100, minIslandBudget = 50))
        val evaluatedAlone = Regex("正式評価(\\d+)").find(alone.logs.first().message)!!.groupValues[1].toInt()
        assertTrue("乙の島は評価されない: $evaluated vs 甲だけ $evaluatedAlone", evaluated <= evaluatedAlone + 1)
        assertTrue(r.logs.first().message.contains("起動2件"))
    }

    @Test
    fun movesThatReduceForbiddenRunsAreNotPrunedEvenWhenTheChangedCellStaysInsideOne() {
        // [3.501.0] 禁止 [A,B],[B,A],[A,休]。甲: A B A* 休(希望3日目) → 禁止 3 件(AB, BA, A休)。乙(2日目=休)との同日交換で
        //   甲: A 休 A 休 → 禁止 2 件(A休×2)。旧の枝刈りは「変更セル(甲,2日目)=休 が禁止の並び A休 の中にある」だけで落としていた
        //   ＝3→2 に減らす手が正式評価へ届かなかった。
        val s = base(listOf(listOf(1, 2, 1, 0, 0, 0), listOf(0, 0, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0, 0)),
            mapOf("0,2" to 1), emptyMap()).copy(cons3n = listOf(C3Row(listOf("A", "B")), C3Row(listOf("B", "A")), C3Row(listOf("A", "休"))))
        val before = UnifiedViolationChecker.check(s, s.schedule.toIntArray2D())
        assertEquals(3, before.breakdown["c3n"])
        val r = WishIslandPolish.applyWishIslandPolish(s, s.schedule.toIntArray2D())
        val after = UnifiedViolationChecker.check(s, r.newSchedule)
        assertTrue("3→2 に減らす手が採用される: ${r.logs.first().message}", r.applied >= 1)
        assertEquals(2, after.breakdown["c3n"])
        assertEquals("希望セルは不変", 1, r.newSchedule[0][2])
    }

    @Test
    fun rotate3KeepsAQuarterOfTheIslandBudgetWhenOrdinaryMovesFindNothing() {
        // 通常候補が何も採れない盤面で、島の枠 8 のうち 2 が巡回に残る＝正式評価が枠いっぱいまで進む（旧: 同日候補だけで枠を使い切った）。
        val s = base(listOf(listOf(1, 1, 1, 0, 0, 0), listOf(0, 1, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0, 2)),
            mapOf("0,2" to 1), mapOf("0,1" to Range("0", "0")))   // 甲の A 上限0 超過＝島は起動するが同日交換では直らない
        val r = WishIslandPolish.applyWishIslandPolish(s, s.schedule.toIntArray2D(), WishIslandPolish.Params(maxPasses = 1, maxEvaluations = 8, minIslandBudget = 8))
        val msg = r.logs.first().message
        assertTrue(msg, msg.contains("起動1件"))
        val evaluated = Regex("正式評価(\\d+)").find(msg)!!.groupValues[1].toInt()
        assertTrue("枠 8 を使い切る（通常 6 ＋ 巡回 2）: $msg", evaluated == 8 || r.applied >= 1)
    }

    @Test
    fun zeroBudgetAndNegativeParamsAndTinyPeriodsDoNothingAndDoNotCrash() {
        val s = base(listOf(listOf(1, 2, 1, 0, 0, 0), listOf(0, 1, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0, 0)),
            mapOf("0,2" to 1), mapOf("0,2" to Range("0", "0")))
        val zero = WishIslandPolish.applyWishIslandPolish(s, s.schedule.toIntArray2D(), WishIslandPolish.Params(maxEvaluations = 0))
        assertEquals(0, zero.applied)
        assertTrue(zero.newSchedule.contentDeepEquals(s.schedule.toIntArray2D()))
        val negative = WishIslandPolish.applyWishIslandPolish(s, s.schedule.toIntArray2D(),
            WishIslandPolish.Params(maxPasses = -1, maxEvaluations = -5, beamWidth = 0, beamDepth = -1, minIslandBudget = 0, beamBranchFactor = 0))
        assertEquals(0, negative.applied)
        // 1 日だけの期間＝影響半径は 1 に丸められ、島は当月内に切り詰められる。
        val oneDay = s.copy(endDate = "2026-06-01", schedule = listOf(listOf(2), listOf(0), listOf(0)), wishes = mapOf("0,0" to 2))
        val r1 = WishIslandPolish.applyWishIslandPolish(oneDay, oneDay.schedule.toIntArray2D())
        assertEquals(0, r1.applied)
        assertTrue(r1.logs.first().message.contains("影響半径1日"))
        // 職員 0 名
        val nobody = s.copy(staff = emptyList(), schedule = emptyList(), wishes = emptyMap(), staffRange = emptyMap(), groupShift = listOf(listOf(1, 1, 1)))
        val r0 = WishIslandPolish.applyWishIslandPolish(nobody, emptyArray())
        assertEquals(0, r0.applied)
        assertFalse(r0.logs.first().message.contains("採用1"))
    }

    /** ビーム 1 段の保持数は幅×分岐を残り予算で頭打ちにし、0 以下の入力は 1 に丸める（3.504.0）。 */
    @Test
    fun beamCandidateLimitIsCappedByRemainingBudgetAndNeverBelowOne() {
        assertEquals(12, WishIslandPolish.beamCandidateLimit(2, 6, 400))
        assertEquals(5, WishIslandPolish.beamCandidateLimit(2, 6, 5))
        assertEquals(1, WishIslandPolish.beamCandidateLimit(2, 6, 0))
        assertEquals(1, WishIslandPolish.beamCandidateLimit(0, 0, -3))
    }

    /** 月初の希望日には左翼が無いので両翼交換は生成されず、全候補の日は 0..T-1 に収まる（月跨ぎなし）。 */
    @Test
    fun wishOnTheFirstDayGeneratesNoWingMovesAndStaysInsideTheMonth() {
        val s = base(listOf(listOf(1, 1, 1, 1, 1, 1), listOf(0, 0, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0, 0)), mapOf("0,0" to 1), emptyMap())
        val moves = WishIslandPolish.enumerateMovesForTest(s, s.schedule.toIntArray2D())
        assertTrue("同日か窓の候補はある", moves.isNotEmpty())
        assertTrue("両翼なし", moves.none { it.first == "両翼" })
        assertTrue("日が 0..5 に収まる", moves.all { (_, cells) -> (1 until cells.size step 3).all { cells[it] in 0..5 } })
        assertTrue("希望セル(0,0)は触らない", moves.none { (_, cells) -> (0 until cells.size step 3).any { cells[it] == 0 && cells[it + 1] == 0 } })
    }

    /** 月末の希望日には右翼が無いので両翼交換は生成されない。 */
    @Test
    fun wishOnTheLastDayGeneratesNoWingMoves() {
        val s = base(listOf(listOf(1, 1, 1, 1, 1, 1), listOf(0, 0, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0, 0)), mapOf("0,5" to 1), emptyMap())
        val moves = WishIslandPolish.enumerateMovesForTest(s, s.schedule.toIntArray2D())
        assertTrue(moves.isNotEmpty())
        assertTrue("両翼なし", moves.none { it.first == "両翼" })
        assertTrue(moves.all { (_, cells) -> (1 until cells.size step 3).all { cells[it] in 0..5 } })
        assertTrue(moves.none { (_, cells) -> (0 until cells.size step 3).any { cells[it] == 0 && cells[it + 1] == 5 } })
    }

    /** 対照: 月の中の希望日なら両翼交換が生成される（境界の 2 テストが「両翼が常に無い」ことで通っていないことの確認）。 */
    @Test
    fun wishInTheMiddleGeneratesWingMoves() {
        val s = base(listOf(listOf(1, 1, 1, 1, 1, 1), listOf(0, 0, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0, 0)), mapOf("0,2" to 1), emptyMap())
        val moves = WishIslandPolish.enumerateMovesForTest(s, s.schedule.toIntArray2D())
        assertTrue("両翼あり", moves.any { it.first == "両翼" })
        assertTrue(moves.all { (_, cells) -> (1 until cells.size step 3).all { cells[it] in 0..5 } })
    }
}
