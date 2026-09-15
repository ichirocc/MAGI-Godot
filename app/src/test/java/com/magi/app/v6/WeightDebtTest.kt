package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightDebtTest {
    private fun report(vararg families: Pair<String, Int>): ViolationReport {
        val b = families.toMap()
        val hard = b.filterKeys { it in MirrorKeys.hard }.values.sum()
        return ViolationReport(emptyMap(), emptyMap(), emptyMap(), breakdown = b, total = b.values.sum(), hard = hard, soft = b.values.sum() - hard,
            weightedScore = b.entries.sumOf { it.value * MirrorKeys.weightOf(it.key) }, logs = emptyList())
    }

    @Test fun creditCountsEveryFamilyAndDebtOnlySoftOnes() {
        val root = report("c1" to 3, "covU" to 1, "weekly" to 2)
        val node = report("c1" to 2, "covU" to 2, "weekly" to 5, "c3" to 1)   // c1 -1 (credit 50), covU +1 (HARD=件数予算の担当), weekly +3 (debt 6), c3 +1 (debt 15)
        val cd = WeightDebt.of(root, node)
        assertEquals(50.0, cd.credit, 0.0)
        assertEquals(21.0, cd.debt, 0.0)
        assertTrue(WeightDebt.within(root, node, 2.0))
        assertFalse(WeightDebt.within(root, node, 0.1))
    }

    @Test fun debtWithoutCreditIsNeverWithin() {
        val root = report("c1" to 3)
        assertFalse(WeightDebt.within(root, report("c1" to 3, "weekly" to 1), 2.0))
        assertTrue(WeightDebt.within(root, root, 2.0))
    }
}
