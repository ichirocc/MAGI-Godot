package com.magi.app.v6

/**
 * 違反連結成分修復の一時負債予算（設計 v3 §9〜10）。起点の族 f の現在量 V_f × 重み W_f × 係数 F を、
 * ビームに残してよい SOFT 悪化の上限にする。HARD 族の起点には予算を与えない。乱数・外部依存なし＝同じ入力なら同じ値。
 */
internal object ConstraintRepairInference {
    fun temporarySoftDebtAllowance(
        report: ViolationReport, family: String,
        weightOf: (String) -> Double = MirrorKeys::weightOf, temporaryDebtFactor: Double = 2.0,
    ): Long {
        if (family in MirrorKeys.hard) return 0L
        val v = report.breakdown[family] ?: 0
        val w = weightOf(family)
        if (v <= 0 || !w.isFinite() || w <= 0.0 || !temporaryDebtFactor.isFinite() || temporaryDebtFactor <= 0.0) return 0L
        val raw = v.toDouble() * w * temporaryDebtFactor
        return if (raw >= SCORE_HARD_UNIT) SCORE_HARD_UNIT - 1 else raw.toLong()
    }

    /** 推定スコア（HARD 桁 × [SCORE_HARD_UNIT] + SOFT）の候補をビームに残してよいか: HARD 改善＝可、HARD 悪化＝不可、同じなら SOFT 悪化が予算内。 */
    fun mayExplore(baseScore: Long, candidateScore: Long, softDebtAllowance: Long): Boolean {
        if (candidateScore == Long.MAX_VALUE || candidateScore < 0 || baseScore < 0) return false
        val baseHard = baseScore / SCORE_HARD_UNIT
        val candHard = candidateScore / SCORE_HARD_UNIT
        if (candHard != baseHard) return candHard < baseHard
        return candidateScore - baseScore <= maxOf(0L, softDebtAllowance)
    }

    /** 族 [family] の起点をどれだけ先に試すか: 現在量 × 重み × その族の直近成功率（Laplace 平滑化。乱数・外部依存なし）。 */
    internal fun familyPriorityScore(
        report: ViolationReport, family: String,
        tried: Int, committed: Int,
        weightOf: (String) -> Double = MirrorKeys::weightOf,
    ): Double {
        val count = (report.breakdown[family] ?: 0).toDouble()
        if (count <= 0.0) return 0.0
        val w = weightOf(family)
        val successRate = (committed + 1.0) / (tried + 1.0)
        return count * w * successRate
    }
}
