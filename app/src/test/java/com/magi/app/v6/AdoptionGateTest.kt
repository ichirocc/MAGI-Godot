package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 研磨パス共通の採用ゲート: 改善しなければピン判定を呼ばず、改善したときだけピンを見る（試行の記録は改善時のみ）。 */
class AdoptionGateTest {
    private val REST = 0; private val A = 1

    private fun state(ranges: Map<String, Range> = emptyMap()) = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-02",
        shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "1", "1")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("s0", 0)), use2Patterns = true,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        schedule = listOf(listOf(REST, A)), wishes = emptyMap(), staffRange = ranges,
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun acceptsImprovementAndSkipsPinCheckWhenNotBetter() {
        val st = state(); val p = Problem(st)
        val before = arrayOf(intArrayOf(REST, A)); val after = arrayOf(intArrayOf(A, A))
        val rb = UnifiedViolationChecker.check(st, before); val ra = UnifiedViolationChecker.check(st, after)
        val pin = PinBlockAttribution()
        assertTrue(adoptionGate(p, before, after, ra, rb, pin).accepted)
        assertEquals(0, pin.attempts)
        // 逆向き（悪化）は better=false・pinBad=false で、ピンの試行は記録されない
        val g = adoptionGate(p, after, before, rb, ra, pin)
        assertFalse(g.better); assertFalse(g.pinBad); assertEquals(0, pin.attempts)
    }

    @Test fun improvingButPinBreakingIsRecordedAndRejected() {
        val st = state(mapOf("0,$A" to Range("1", "1"))); val p = Problem(st)
        val before = arrayOf(intArrayOf(REST, A)); val after = arrayOf(intArrayOf(A, A))   // hard は減るが A の固定 1 を 2 に
        val rb = UnifiedViolationChecker.check(st, before); val ra = UnifiedViolationChecker.check(st, after)
        val pin = PinBlockAttribution()
        val g = adoptionGate(p, before, after, ra, rb, pin)
        assertTrue(g.better); assertTrue(g.pinBad); assertFalse(g.accepted)
        assertEquals(1, pin.attempts)
        assertFalse(adoptionGate(p, before, after, ra, rb).accepted)   // pinBlocks 無しでも同じ判定
    }
}
