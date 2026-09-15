package com.magi.app.v6

import java.util.Random
import com.magi.app.model.MagiState

/**
 * 入口のHARD修復（担当外セルの正規化・希望反映・被覆/個人下限の充填）。[V6NativeOptimizer] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * **共有可変状態を一切参照しない純粋な計算関数**（[V6NativeOptimizer] 本体は @Volatile フィールド・
 * Atomic系・RunSlotのコルーチンコンテキスト連携で並行実行状態を密結合する「統括状態機械」の性格が
 * 強く、機械的な抽出は危険＝本ファイルはその中の安全な塊だけを切り出す）。`cachedProblem`/
 * `normalizeSchedule`/`coverage`/`countMatrix`/`fillShiftIndex`（MirrorCore.kt のトップレベル関数）
 * への参照は通常の同一パッケージ関数呼出であり、[V6NativeOptimizer] 自身の並行実行状態ではない。
 *
 * - [hf66DataHardening]：担当外(canDo=false)・範囲外のセルを [fillShiftIndex] の規則で正規化する。
 * - [RepairResult]：[hf67HardRepair] の戻り値（修復後盤面＋診断ログ）。
 * - [hf67HardRepair]：[hf66DataHardening] を前段に、①実現可能な希望を強制適用 ②被覆(covU)の
 *   充填（[CoverageRepairScoring.bestStaffForCoverage] でドナー選定） ③個人下限(range low)の
 *   充填、の順にHARD違反を修復する。
 *
 * 呼び出し側は全て`V6NativeOptimizer.<name>`の完全修飾で参照していたため、抽出時に
 * `HardRepairCore.<name>`へ一括置換した（[hf66DataHardening] は3.428.0で既にinternal化済み・
 * [RepairResult] と [hf67HardRepair] は本抽出でprivateからinternalへ昇格）。
 */
internal object HardRepairCore {
    /** [3.428.0/#30] 埋めシフト規則の委譲を直接固定するため internal（本番の可視性要件は private のまま）。 */
    internal fun hf66DataHardening(state: MagiState, schedule: Array<IntArray>, tag: String, quantitativeRangeEval: Boolean = false): Array<IntArray> {
        val p = cachedProblem(state, quantitativeRangeEval)
        val out = normalizeSchedule(schedule, p)
        for (i in 0 until p.S) {
            val allowed = p.allowedShiftsForStaff(i)
            // [3.428.0/#30] 「担当外セルを何で埋めるか」の規則は `fillShiftIndex` の1箇所に置く
            //   （3.419.0 で構造編集の3経路を統一したときの取り残し＝ここだけ独自の `?: 0` だった）。
            //   旧実装との違いは2つ: ①休が担当可なら休を選ぶ（旧は index 最小＝休が先頭でないデータでは
            //   勤務シフトへ倒れる）②担当可能が空なら 0 でなく休へ倒す。実データ3件は restIdx=0 かつ
            //   全群が休を担当できるので**挙動は完全に不変**（測って確認済み）。
            val fallback = fillShiftIndex(allowed, p.restIdx)
            for (j in 0 until p.T) {
                val k = out[i][j]
                // [3.507.0] 個人上限 0 のセル（希望でそのシフトに固定されたものは除く）も入口で外す＝探索は置き直しから始める。
                val capped = k in 0 until p.K && !p.mayPlace(i, k) && !(p.wishLocked(i, j) && p.wish[i][j] == k)
                if (k !in 0 until p.K || !p.canDo(i, k) || capped) out[i][j] = fallback
            }
        }
        return out
    }


    /** [3.507.0] 個人上限 0 のセル（希望固定を除く）だけを置けるシフトへ戻した盤面と、その件数。最終番兵の「入力」基準に使う
     *  （群外セルは触らない＝従来の基準のまま）。 */
    internal fun clearCappedCells(state: MagiState, schedule: Array<IntArray>, quantitativeRangeEval: Boolean = false): Pair<Array<IntArray>, Int> {
        val p = cachedProblem(state, quantitativeRangeEval)
        val out = schedule.copy2D()
        var n = 0
        for (i in 0 until p.S) {
            val fallback = fillShiftIndex(p.allowedShiftsForStaff(i), p.restIdx)
            for (j in 0 until p.T) {
                val k = out[i][j]
                if (k in 0 until p.K && p.canDo(i, k) && !p.mayPlace(i, k) && !(p.wishLocked(i, j) && p.wish[i][j] == k)) { out[i][j] = fallback; n++ }
            }
        }
        return out to n
    }

    internal data class RepairResult(val schedule: Array<IntArray>, val logs: List<MirrorLog>)


    internal fun hf67HardRepair(state: MagiState, schedule: Array<IntArray>, rng: Random, quantitativeRangeEval: Boolean = false): RepairResult {
        val p = cachedProblem(state, quantitativeRangeEval)
        val out = hf66DataHardening(state, schedule, "hf67", quantitativeRangeEval)
        val logs = ArrayList<MirrorLog>()
        var changed = 0

        // Apply feasible wishes first; infeasible wishes are logged by Sanity, not forced.
        for (i in 0 until p.S) for (j in 0 until p.T) {
            val w = p.wish[i][j]
            if (w in 0 until p.K && p.canDo(i, w) && out[i][j] != w) {
                out[i][j] = w
                changed++
            }
        }

        repeat(3) {
            val cov = coverage(p, out)
            val counts = countMatrix(p, out)
            for (j in 0 until p.T) for (k in 0 until p.K) {
                // [N1a] 充填量は per-cell 実需要（#4b: OR/AND）。旧 need1 のみ基準では P2 で救済済みの
                //   セル（休日体制など P1>P2）まで埋めに行き、既良盤面を壊していた。
                var miss = p.covUCell(k, j, cov[j][k])
                while (miss > 0) {
                    val i = CoverageRepairScoring.bestStaffForCoverage(p, out, counts, j, k)
                    if (i < 0) break
                    val old = out[i][j]
                    if (old == k) break
                    out[i][j] = k
                    cov[j][k]++
                    if (old in 0 until p.K) cov[j][old]--
                    changed++
                    miss--
                }
            }
        }

        // Range lower bounds: fill shortage where possible without touching locked wishes.
        val counts = countMatrix(p, out)
        for (i in 0 until p.S) for (k in 0 until p.K) {
            val lo = p.rangeLo[i][k]
            if (lo == Int.MIN_VALUE || !p.mayPlace(i, k)) continue
            var need = lo - counts[i][k]
            var guard = 0
            while (need > 0 && guard++ < p.T) {
                var bestJ = -1
                var bestScore = Int.MAX_VALUE
                for (jj in 0 until p.T) {
                    if (p.wishLocked(i, jj) || out[i][jj] == k) continue
                    val score = CoverageRepairScoring.coverageShortageCost(p, out, jj, out[i][jj]) + rng.nextInt(3)
                    if (score < bestScore) {
                        bestScore = score
                        bestJ = jj
                    }
                }
                if (bestJ < 0) break
                val j = bestJ
                val old = out[i][j]
                out[i][j] = k
                if (old in 0 until p.K) counts[i][old]--
                counts[i][k]++
                changed++
                need--
            }
        }
        if (changed > 0) logs.add(MirrorLog(tag = "HF67", message = "HardRepair changed=$changed"))
        return RepairResult(out, logs)
    }


}
