package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstraintRepairInferenceTest {
    private fun report(vararg families: Pair<String, Int>): ViolationReport {
        val b = families.toMap()
        val hard = b.filterKeys { it in MirrorKeys.hard }.values.sum()
        return ViolationReport(emptyMap(), emptyMap(), emptyMap(), breakdown = b, total = b.values.sum(), hard = hard, soft = b.values.sum() - hard,
            weightedScore = b.entries.sumOf { it.value * MirrorKeys.weightOf(it.key) }, logs = emptyList())
    }

    @Test fun allowanceFollowsTheWeightNotAConstant() {
        val rep = report("c1" to 3)
        assertEquals(300L, ConstraintRepairInference.temporarySoftDebtAllowance(rep, "c1"))   // [3.522.0] c1=50 × 3 × 2.0
        assertEquals(240L, ConstraintRepairInference.temporarySoftDebtAllowance(rep, "c1", weightOf = { 40.0 }))
        assertEquals(0L, ConstraintRepairInference.temporarySoftDebtAllowance(report("covU" to 3), "covU"))   // HARD 族に予算なし
        assertEquals(0L, ConstraintRepairInference.temporarySoftDebtAllowance(rep, "c1", weightOf = { Double.NaN }))
        assertEquals(0L, ConstraintRepairInference.temporarySoftDebtAllowance(rep, "c1", temporaryDebtFactor = -1.0))
        assertEquals(SCORE_HARD_UNIT - 1, ConstraintRepairInference.temporarySoftDebtAllowance(rep, "c1", weightOf = { 1e12 }))
    }

    @Test fun softDebtWithinAllowanceIsExplorable() {
        assertTrue(ConstraintRepairInference.mayExplore(100L, 190L, 90L))
        assertFalse(ConstraintRepairInference.mayExplore(100L, 191L, 90L))
        assertTrue(ConstraintRepairInference.mayExplore(100L, 40L, 0L))
    }

    @Test fun hardWorseningIsNeverExplorable() {
        val base = 2 * SCORE_HARD_UNIT + 100L
        assertFalse(ConstraintRepairInference.mayExplore(base, 3 * SCORE_HARD_UNIT, Long.MAX_VALUE))
        assertTrue(ConstraintRepairInference.mayExplore(base, 1 * SCORE_HARD_UNIT + 999_999L, 0L))   // HARD 改善は SOFT 悪化があっても可
        assertFalse(ConstraintRepairInference.mayExplore(base, Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
