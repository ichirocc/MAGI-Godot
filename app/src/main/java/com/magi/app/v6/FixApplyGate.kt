package com.magi.app.v6

import com.magi.app.model.MagiState

/** 改善提案（`FixSuggestion.ops`）を本盤面へ反映する直前の安全ゲート。提案は計算時の盤面に対する差分なので、仮盤面へ適用して
 *  正式チェッカーで再評価し、辞書式で改善し厳密ピン・希望固定を崩さないときだけ適用後の盤面を返す（入力の盤面は変えない）。 */
object FixApplyGate {
    sealed class Outcome {
        abstract val before: ViolationReport
        data class Applied(val schedule: Array<IntArray>, override val before: ViolationReport, val after: ViolationReport) : Outcome()
        data class Rejected(val reason: String, override val before: ViolationReport, val after: ViolationReport?) : Outcome()
    }

    fun apply(state: MagiState, schedule: Array<IntArray>, ops: List<FixCell>): Outcome {
        val p = Problem(state)
        val before = UnifiedViolationChecker.check(state, schedule)
        if (ops.isEmpty()) return Outcome.Rejected("変更がありません", before, null)
        val work = schedule.copy2D()
        for (op in ops) {
            if (op.staff !in work.indices || op.day !in work[op.staff].indices || op.toShift !in 0 until p.K)
                return Outcome.Rejected("提案の範囲が今の勤務表と合いません", before, null)
            if (p.wishLocked(op.staff, op.day) && p.wish[op.staff][op.day] != op.toShift)
                return Outcome.Rejected("希望で固定されたセルを変える提案です", before, null)
            work[op.staff][op.day] = op.toShift
        }
        val after = UnifiedViolationChecker.check(state, work)
        if (!betterReport(after, before)) return Outcome.Rejected("今の勤務表では改善になりません", before, after)
        if (exactPinRegression(p, schedule, work)) return Outcome.Rejected("回数固定（下限＝上限）を崩す提案です", before, after)
        return Outcome.Applied(work, before, after)
    }
}
