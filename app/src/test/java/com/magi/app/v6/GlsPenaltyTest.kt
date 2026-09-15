package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GLS（Guided Local Search）ペナルティ移植の検証：拡張コスト・最大util強化・範囲安全。 */
class GlsPenaltyTest {

    private val sched = arrayOf(intArrayOf(0, 1, 2), intArrayOf(3, 0, 1))   // 2人×3日, K=4

    @Test fun augmentIsZeroUntilPenalized() {
        val gls = GlsPenalty(2, 3, 4, lambda = 10.0)
        assertEquals(0.0, gls.augment(sched), 1e-9)
    }

    @Test fun penalizesLeastPenalizedViolatingCellThenRotates() {
        val gls = GlsPenalty(2, 3, 4, lambda = 10.0)
        val cells = listOf(0 to 1, 1 to 0)   // 割当 k=1 と k=3
        // 1回目: 両方 penalty=0 → util同点で先頭(0,1)を強化
        assertTrue(gls.penalizeWorst(sched, cells))
        assertEquals(1, gls.penaltyOf(0, 1, 1))
        assertEquals(10.0, gls.augment(sched), 1e-9)   // lambda*1
        // 2回目: (0,1)は penalty1→util0.5, (1,0)は0→util1.0 → (1,0)を強化（util最大へローテート）
        assertTrue(gls.penalizeWorst(sched, cells))
        assertEquals(1, gls.penaltyOf(1, 0, 3))
        assertEquals(20.0, gls.augment(sched), 1e-9)   // lambda*(1+1)
        assertEquals(2, gls.kickCount())
    }

    @Test fun noCandidateReturnsFalseAndIsRangeSafe() {
        val gls = GlsPenalty(2, 3, 4, lambda = 10.0)
        assertFalse(gls.penalizeWorst(sched, emptyList()))
        assertFalse(gls.penalizeWorst(sched, listOf(5 to 5, 9 to 0)))   // 範囲外
        assertEquals(0, gls.kickCount())
    }

    @Test fun severityBiasesSelection() {
        val gls = GlsPenalty(2, 3, 4, lambda = 1.0)
        // (1,0) の severity を高く → penalty同点でも (1,0) が選ばれる
        gls.penalizeWorst(sched, listOf(0 to 1, 1 to 0)) { i, _ -> if (i == 1) 5.0 else 1.0 }
        assertEquals(1, gls.penaltyOf(1, 0, 3))
        assertEquals(0, gls.penaltyOf(0, 1, 1))
    }

    @Test fun decayShrinksPenaltyAndAugment() {
        val gls = GlsPenalty(2, 3, 4, lambda = 10.0)
        repeat(10) { gls.penalizeWorst(sched, listOf(0 to 1)) }   // (0,1)割当 k=1 を penalty=10 まで強化
        assertEquals(10, gls.penaltyOf(0, 1, 1))
        assertEquals(100.0, gls.augment(sched), 1e-9)             // lambda*10
        gls.decay(80)                                             // 10*80/100 = 8（整数床）
        assertEquals(8, gls.penaltyOf(0, 1, 1))
        assertEquals(80.0, gls.augment(sched), 1e-9)             // lambda*8
    }

    @Test fun decayRemovesEntriesReachingZero() {
        val gls = GlsPenalty(2, 3, 4, lambda = 10.0)
        gls.penalizeWorst(sched, listOf(0 to 1))   // penalty=1
        assertEquals(1, gls.penaltyOf(0, 1, 1))
        assertEquals(0, gls.decay(50))             // 1*50/100=0 → 除去 → 残り0項目
        assertEquals(0, gls.penaltyOf(0, 1, 1))
        assertEquals(0.0, gls.augment(sched), 1e-9)
    }
    // [レビュー#8 3.213.0] decay の値域契約（100超=増幅・負値は無意味）を固定。
    @Test fun decayRejectsOutOfRangeKeepPercent() {
        val gls = GlsPenalty(2, 3, 4, lambda = 10.0)
        try { gls.decay(101); org.junit.Assert.fail("expected IAE for 101") } catch (_: IllegalArgumentException) {}
        try { gls.decay(-1); org.junit.Assert.fail("expected IAE for -1") } catch (_: IllegalArgumentException) {}
        assertEquals(0, gls.decay(80))   // 有効値は従来どおり（空 penalty → 0 件）
    }
}
