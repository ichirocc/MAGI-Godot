package com.magi.app.v6

/**
 * 共同 LNS の中間ノードに許す一時負債を、件数でなく重みで測る（backlog #15(f)、設計 v2 §9）。
 * クレジット＝root から減った族の 重み×減少量（HARD 族も含む）、負債＝root から増えた SOFT 族の 重み×増加量。
 * HARD の増加は件数予算（hardDebt）が別に抑える。
 */
internal object WeightDebt {
    class CreditDebt(val credit: Double, val debt: Double)

    fun of(root: ViolationReport, node: ViolationReport): CreditDebt {
        var credit = 0.0; var debt = 0.0
        for ((family, delta) in ChangeSummary.familyDeltas(root, node)) {
            val w = MirrorKeys.weightOf(family)
            if (delta < 0) credit -= delta * w
            else if (family !in MirrorKeys.hard) debt += delta * w
        }
        return CreditDebt(credit, debt)
    }

    /** 負債 ≤ クレジット×[factor] なら中間ノードとして残す。 */
    fun within(root: ViolationReport, node: ViolationReport, factor: Double): Boolean {
        val cd = of(root, node)
        return cd.debt <= cd.credit * factor
    }
}
