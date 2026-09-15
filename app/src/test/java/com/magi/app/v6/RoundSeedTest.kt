package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [頭打ち調査・「なぜゼロにならないのか」] runPostOptimizationのフィックスポイント巡回は
 * C1Polish/C3mnPolish/RangePolish/C3RunPolishを毎ラウンド再呼出するが、旧実装はseed引数を
 * 渡さず既定値固定のままだった＝ある(staff,shift)ペアがラウンドNで頭打ちすると、盤面の当該箇所が
 * 変わらない限りラウンドN+1以降も同じrng列を再生するだけで永久に頭打ちのままだった。
 * `V6HotfixPasses.roundSeed(base, tag, round)` はラウンドごとに異なるseedを与えて再挑戦のたびに
 * 違う候補順を試せるようにする（isBetterのkeep-best採否は不変・単なる探索の多様化）。
 */
class RoundSeedTest {

    @Test
    fun roundSeedProducesDistinctValuesAcrossRounds() {
        val values = (0..3).map { V6HotfixPasses.roundSeed(base = 1L, tag = 0x8A9EL, round = it) }
        assertEquals("4ラウンド分がすべて異なること", values.toSet().size, values.size)
    }

    @Test
    fun roundSeedIsDeterministic() {
        val a = V6HotfixPasses.roundSeed(base = 42L, tag = 0xC3AL, round = 2)
        val b = V6HotfixPasses.roundSeed(base = 42L, tag = 0xC3AL, round = 2)
        assertEquals("同じ引数なら同じ値(再現性)", a, b)
    }

    @Test
    fun roundSeedDiffersAcrossDistinctTagsForSameRound() {
        // C1/C3mn/Range/C3Runの各パスは同一round・異なるtag定数でも互いに衝突しないこと。
        val tags = listOf(0x1C1L, 0xC3AL, 0x8A9EL, 0xC3A2L)
        val values = tags.map { V6HotfixPasses.roundSeed(base = 7L, tag = it, round = 1) }
        assertTrue("異なるtagは異なるseedを生むこと", values.toSet().size == values.size)
    }
}
