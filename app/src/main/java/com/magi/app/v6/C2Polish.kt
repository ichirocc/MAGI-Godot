package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * [3.511.3/測定中] 個人合計(c2, SOFT, 重み1)専用の研磨パス。[V6HotfixPasses] から抽出せず新規（backlog #12(b)）。
 *
 * c2 は「シフト単位のグローバル下限」（`Problem.cons2`、`count[i][shiftIdx] >= c.count` を canDo 全職員に一律要求）で、
 * 判定は不足量でなく**違反有無の二値フラグ**（`Evaluator`/`MirrorCore`/`DeltaEvaluator` すべて `if (count < c.count) soft += 1`）。
 * [AptFairPolish] と違い apt/fair は L1 偏差＝1 セル移動ごとに目的関数が単調に動くため 1 手ずつの keep-best 判定と相性が良いが、
 * c2 は不足量が 2 以上の職員に「1 日ずつ変換→毎回判定」を適用すると、しきい値に届くまでの中間手が違反件数を 1 件も減らさず
 * （他制約への副作用が無ければ weightedScore/total が同点）`betterReport`（同点は却下）に毎回却下される＝実質ノーオペになる。
 * このパスは**不足分の日をまとめて集め、一括で適用してから 1 回だけ判定**する（`AptFairPolish.tryMutualSwap` の
 * 「複数セル同時適用→1 回判定」を N セルへ一般化したもの）。
 *
 * 自己変換のみ（本人の他シフトの日を shiftIdx へ振り替える）を対象にし、他職員から玉突きで借りる拡張は対象外
 * （まず最小差分で測る＝過剰実装を避ける。要るかは `tools/loop` の結果を見てから）。
 */
internal object C2Polish {
    fun applyC2Polish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, quantitativeRangeEval: Boolean = false): V6HotfixPasses.CyclicSwapResult {
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        // [3.270.0 と同型] wishLocked は canDo ガード込みで「動かせるか」を正しく判定する。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)

        // [厳密ピン保護] 職員 i の shiftIdx への不足 [deficit] 分を、被覆非悪化・禁止連続なしの日から
        //   まとめて集め、集まった分だけ一括適用して 1 回だけ正式判定する。届かなければ全く触らない
        //   （中途半端な一部適用は c2 の二値性のため無意味＝必ず全部埋まる分だけを試す）。
        fun tryFillDeficit(i: Int, shiftIdx: Int, deficit: Int): Boolean {
            if (!p.mayPlace(i, shiftIdx)) return false
            val days = ArrayList<Int>()
            for (j in 0 until p.T) {
                if (days.size >= deficit) break
                if (shouldStop()) return false
                val fromK = work[i][j]
                if (fromK == shiftIdx || !movable(i, j)) continue
                if (p.makesForbiddenRun(work, i, j, shiftIdx)) continue
                var cntFrom = 0; var cntTo = 0
                for (s in 0 until p.S) { if (work[s][j] == fromK) cntFrom++; if (work[s][j] == shiftIdx) cntTo++ }
                if (p.covUCell(fromK, j, cntFrom - 1) > p.covUCell(fromK, j, cntFrom)) continue
                if (p.covUCell(shiftIdx, j, cntTo + 1) > p.covUCell(shiftIdx, j, cntTo)) continue
                days.add(j)
            }
            if (days.size < deficit) return false
            val workBefore = work.copy2D()
            for (j in days) work[i][j] = shiftIdx
            val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
            val pinBad = exactPinRegression(p, workBefore, work)
            if (pinBad && betterReport(rep, bestRep)) pinBlocks.record(p, workBefore, work)
            if (betterReport(rep, bestRep) && !pinBad) { bestRep = rep; applied++; return true }
            for (j in days) work[i][j] = workBefore[i][j]
            return false
        }

        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            val counts = countMatrix(p, work)
            for (c in p.cons2) {
                if (shouldStop()) break
                for (i in 0 until p.S) {
                    if (shouldStop()) break
                    if (!p.canDo(i, c.shiftIdx)) continue
                    val deficit = c.count - counts[i][c.shiftIdx]
                    if (deficit <= 0) continue
                    if (tryFillDeficit(i, c.shiftIdx, deficit)) improved = true
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "C2Polish", message = "個人合計(c2)研磨: total ${before.total}->${bestRep.total} 採用${applied}回"))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }
}
