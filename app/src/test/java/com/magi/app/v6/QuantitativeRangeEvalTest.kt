package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [backlog #12(a)・実験段階] c2(不足量)/c41・c41s(距離)の量的評価モード（`Problem.quantitativeRangeEval`）。
 * 既定(false)は全既存呼出元と挙動不変、true のときだけチェッカー・評価器・Δ評価器が
 * [c2Amount]/[rangeDistance] を使う。3者の一致と、真の値が二値カウントとは異なることを固定する。
 */
class QuantitativeRangeEvalTest {

    @Test
    fun c2AmountIsShortfallOnly() {
        assertEquals(0L, c2Amount(5, 3))   // 超過は0（c2は下限のみ）
        assertEquals(0L, c2Amount(3, 3))   // ちょうど= 0
        assertEquals(1L, c2Amount(2, 3))
        assertEquals(3L, c2Amount(0, 3))
    }

    @Test
    fun rangeDistanceIsZeroInsideRangeAndDistanceOutside() {
        assertEquals(0L, rangeDistance(2, 1, 3))
        assertEquals(0L, rangeDistance(1, 1, 3))
        assertEquals(0L, rangeDistance(3, 1, 3))
        assertEquals(1L, rangeDistance(0, 1, 3))   // lo側に1不足
        assertEquals(3L, rangeDistance(6, 1, 3))   // hi側に3超過
    }

    private fun buildState(schedule: List<List<Int>>): MagiState {
        val shifts = listOf(
            Shift("休", "休", "", ""), Shift("A", "A", "", ""), Shift("B", "B", "", ""),
        )
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("s0", 0), Staff("s1", 0), Staff("s2", 0), Staff("s3", 0))
        val groupShift = listOf(listOf(1, 1, 1))
        return MagiState(
            startDate = "2025-01-01", endDate = "2025-01-0${schedule[0].size}",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = listOf(listOf("", "", "")), schedule = schedule,
            wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = listOf(C2Row("A", "3")), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = listOf(C41Row("G0", "B", "2", "2")), cons42 = emptyList(),
        )
    }

    /**
     * 全員 A が0回・B(=[2,2])が0人 → c2不足=3(目標)×4人、c41距離=2×日数（不足のみ）。
     * fullEvalParts の合計には weekly 等の無関係な族も乗るため、量的/二値の**差分**（=このモードだけが
     * 動かす分）を重み付きで検証する: 差分 = (c2の生カウント差12-4)×重み4 + (c41の生カウント差10-5)×重み1
     * = 8*4 + 5*1 = 37（[3.522.0] c2重み1→4で13→37）。
     */
    @Test
    fun evaluatorQuantitativeSumMatchesHandComputedAmount() {
        val days = 5
        val schedule = List(4) { List(days) { 0 } }   // 全員全日休
        val p = Problem(buildState(schedule), quantitativeRangeEval = true)
        val ev = Evaluator(p)
        val parts = ev.fullEvalParts(p.initialAssignment())

        val pBin = Problem(buildState(schedule))   // 既定=false
        assertFalse(pBin.quantitativeRangeEval)
        val evBin = Evaluator(pBin)
        val partsBin = evBin.fullEvalParts(pBin.initialAssignment())

        assertEquals(37L, parts[1] - partsBin[1])
    }

    @Test
    fun checkerQuantitativeBreakdownMatchesEvaluator() {
        val days = 5
        val schedule = List(4) { List(days) { 0 } }
        val state = buildState(schedule)
        val p = Problem(state, quantitativeRangeEval = true)
        val ev = Evaluator(p)
        val parts = ev.fullEvalParts(p.initialAssignment())

        val report = UnifiedViolationChecker.check(state, p.initialAssignment(), quantitativeRangeEval = true)
        assertEquals(12, report.breakdown["c2"])
        assertEquals(10, report.breakdown["c41"])
        // [3.522.0] report.soft は total-hard の生カウントで重み非依存＝c2(重み4)導入後は parts[1]
        //   (Evaluatorの重み付きsoft)と一致しなくなった（旧c2=1のとき生カウント=重み付き値で偶然一致）。
        //   このfixtureはHARD=0なので report.weightedScore と比較する。
        assertEquals(parts[1], report.weightedScore.toLong())

        // 既定(false)は従来どおり二値件数のまま
        val reportBin = UnifiedViolationChecker.check(state, p.initialAssignment())
        assertEquals(4, reportBin.breakdown["c2"])
        assertEquals(5, reportBin.breakdown["c41"])
    }

    @Test
    fun deltaMatchesFullEvalUnderQuantitativeMode() {
        val days = 6
        val schedule = List(4) { List(days) { 0 } }
        val p = Problem(buildState(schedule), quantitativeRangeEval = true)
        val ev = Evaluator(p)
        val de = DeltaEvaluator(p)
        assertEquals(ev.fullEval(p.initialAssignment()), de.score())
        val rng = Random(999)
        repeat(3_000) {
            val i = rng.nextInt(p.S); val j = rng.nextInt(p.T)
            val old = de.at(i, j)
            val nw = rng.nextInt(p.K)
            de.apply(i, j, nw)
            assertEquals(ev.fullEval(de.snapshot()), de.score())
            if (rng.nextBoolean()) {
                de.apply(i, j, old)
                assertEquals(ev.fullEval(de.snapshot()), de.score())
            }
        }
    }

    /** [デフォルト不変条件] quantitativeRangeEval を指定しない全既存経路は二値のまま＝挙動不変。 */
    @Test
    fun defaultProblemIsBinaryMode() {
        val schedule = List(4) { List(5) { 0 } }
        val p = Problem(buildState(schedule))
        assertFalse(p.quantitativeRangeEval)
        assertTrue(cachedProblem(buildState(schedule)).quantitativeRangeEval == false)
    }
}
