package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.535.0/HF77明示数値指示] `AptFairPolish.toleratedBetter`（fair/apt研磨で対象家族以外のSOFT悪化を
 * 研磨開始時点比+6%まで容認する）の単体検証。実盤面(MagiState)を組むと重みの掛け算を狙って作るのが
 * 難しいため、`ViolationReport`を直接構成して比較の算術だけを固定する（判定・重みは既存のMirrorKeysから
 * 読むだけで新規に増やさない）。
 */
class AptFairPolishToleranceTest {

    private fun rep(fair: Int, apt: Int, low: Int): ViolationReport {
        val bd = mapOf("fair" to fair, "apt" to apt, "low" to low)
        val weighted = fair * MirrorKeys.weights.getValue("fair") +
            apt * MirrorKeys.weights.getValue("apt") +
            low * MirrorKeys.weights.getValue("low")
        return ViolationReport(
            violations = emptyMap(), needViolations = emptyMap(), countViolations = emptyMap(),
            breakdown = bd, total = fair + apt + low, hard = 0, soft = fair + apt + low, weightedScore = weighted,
        )
    }

    @Test fun disabledFallsBackToPlainBetterReport() {
        val before = rep(fair = 10, apt = 0, low = 1)
        val bestRep = before
        val candidate = rep(fair = 9, apt = 2, low = 1)   // apt+2*4=+8 > fair-1*2=-2 の純悪化
        assertFalse("OFF時はbetterReportと同じ判定", AptFairPolish.toleratedBetter(candidate, bestRep, before, "fair", enabled = false))
        assertEquals(betterReport(candidate, bestRep), AptFairPolish.toleratedBetter(candidate, bestRep, before, "fair", enabled = false))
    }

    @Test fun withinBudgetTradeIsAcceptedEvenThoughRawScoreWorsens() {
        // baseline: 対象家族(fair)以外のSOFT合計 = low(1)*120 = 120 → 予算 = 120*0.06 = 7.2
        val before = rep(fair = 10, apt = 0, low = 1)
        val bestRep = before   // weightedScore = 20 + 0 + 120 = 140
        // candidate: fairが1改善(-2)する代わりにaptが2悪化(+8) → 生スコアは+6悪化(betterReportなら却下)
        val candidate = rep(fair = 9, apt = 2, low = 1)   // weightedScore = 18 + 8 + 120 = 146
        assertFalse("素のbetterReportは却下する（生スコアが悪化）", betterReport(candidate, bestRep))
        assertTrue("6%予算内(+8 <= 7.2)なので容認して採用する",
            AptFairPolish.toleratedBetter(candidate, bestRep, before, "fair", enabled = true))
    }

    @Test fun exceedingBudgetTradeIsStillRejected() {
        val before = rep(fair = 10, apt = 0, low = 1)   // baseline non-fair soft = 120, 予算 = 7.2
        val bestRep = before
        // candidate: fairが1改善(-2)する代わりにlowが1悪化(+120) → +120 は予算7.2を遥かに超える
        val candidate = rep(fair = 9, apt = 0, low = 2)   // weightedScore = 18 + 0 + 240 = 258
        assertFalse("生スコアの悪化(+118)を予算7.2ぶんしか許さないため、なお却下される",
            AptFairPolish.toleratedBetter(candidate, bestRep, before, "fair", enabled = true))
    }

    @Test fun cumulativeBudgetShrinksAsItIsSpent() {
        val before = rep(fair = 10, apt = 0, low = 1)   // baseline non-fair soft = 120, 予算 = 7.2
        // bestRep が既に apt+1(=4)ぶん予算を使った状態からスタート（残り予算 = 7.2 - 4 = 3.2）。
        val bestRep = rep(fair = 9, apt = 1, low = 1)   // weightedScore = 18 + 4 + 120 = 142
        // 追加でapt+1(=4)悪化する手は、残り予算3.2を超える（forgiven=3.2、未容認分0.8が残る）。
        // fairは変化なしなので、この0.8を相殺する改善が無く却下される。
        val candidateNoFairGain = rep(fair = 9, apt = 2, low = 1)   // weightedScore = 18 + 8 + 120 = 146
        assertFalse("残り予算(3.2)を超え、かつfairの改善が無いため却下",
            AptFairPolish.toleratedBetter(candidateNoFairGain, bestRep, before, "fair", enabled = true))
    }
}
