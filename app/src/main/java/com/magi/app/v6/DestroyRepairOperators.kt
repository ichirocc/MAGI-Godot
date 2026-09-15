package com.magi.app.v6

import java.util.Random
import com.magi.app.model.MagiState
import kotlin.math.max
import kotlin.math.min

/**
 * destroy-repair系の探索オペレータ群（RSI/ALNSの仮説生成・摂動の中核）。[V6NativeOptimizer] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * **共有可変状態を一切参照しない**（[V6NativeOptimizer] 本体は @Volatile フィールド・Atomic系・
 * RunSlotのコルーチンコンテキスト連携で並行実行状態を密結合する「統括状態機械」の性格が強く、
 * 機械的な抽出は危険＝本ファイルはその中の安全な塊だけを切り出す）。参照するのは
 * `cachedProblem`/`restShiftIndex`（MirrorCore.kt のトップレベル関数）・
 * [DestroyRepairMarginalCost]（marginal cost計算・Round3抽出済み）・
 * [HypothesisDiversityPolicy.takeReservoirTie]（同点抽選）のみで、いずれも並行実行状態ではない。
 * schedule/out の in-place 変更は引数として渡された盤面配列への正当な副作用
 *（[CandidateCommit.commitBestMove]/[HardRepairCore.hf67HardRepair] と同型）。
 *
 * - [destroyRepairDay]/[destroyRepairDayAt]：日単位の soft-aware destroy-repair（2.57.0/2.59.0、
 *   c41-aware・weekly/fair marginal 統合＝3.267.0・need2単独定義対応＝3.379.0）。
 * - [destroyRepairStaff]/[destroyRepairStaffAt]：職員単位の同上（2.58.0）。
 * - [destroyRepairViolations]：違反セルhint駆動の再割当（2.58.0/3.267.0）。
 * - [randomAllowedCell]：一様ランダム1セル再割当（violations が空のときの受け皿・perturb の部品。
 *   クラスタ外からの呼出は無いため private のまま）。
 * - [perturb]：restart境界の一様摂動（strength比例）。
 *
 * 呼び出し側（[V6NativeOptimizer] の rsiGenerateHypothesis/hypothesisStartFor/forceDiverseKick/
 * adaptiveEpochStart/runAlnsSingle/runAlns とテスト）は全て `DestroyRepairOperators.<name>` の
 * 完全修飾へ一括置換した（destroyRepairDay/destroyRepairStaff/destroyRepairViolations/perturb は
 * 本抽出で private から internal へ昇格）。
 */
internal object DestroyRepairOperators {
    internal fun destroyRepairDay(state: MagiState, schedule: Array<IntArray>, rng: Random, quantitativeRangeEval: Boolean = false) {
        val p = cachedProblem(state, quantitativeRangeEval)
        if (p.T == 0) return
        destroyRepairDayAt(state, schedule, rng.nextInt(p.T), rng, quantitativeRangeEval)
    }


    internal fun destroyRepairDayAt(state: MagiState, schedule: Array<IntArray>, j: Int, rng: Random, quantitativeRangeEval: Boolean = false) {
        val p = cachedProblem(state, quantitativeRangeEval)
        if (p.T == 0) return
        // [soft-aware destroy-repair / 実測検証 tools/nsp_bench.py] 従来はランダム順で穴を埋めるだけ(soft無視)で、
        //   等価ベンチでは soft-aware 修復が AUC -24%〜-34% と唯一の大幅改善だった。ここで同じレバーを適用:
        //   非希望セルを休へ destroy → 各需要を「割当の marginal soft が最小の休スタッフ」で repair。
        //   休→k のみ移すため被覆穴を新たに作らない。希望固定は保持。受理(SA/isBetter)が最終採否=安全。
        val rest = restShiftIndex(state)   // [監査#2] 休はindex0固定でなく記号から解決（Level Zero: 全シフト同等・番号非依存）
        val cnt = Array(p.S) { IntArray(p.K) }
        for (i in 0 until p.S) for (jj in 0 until p.T) { val k = schedule[i][jj]; if (k in 0 until p.K) cnt[i][k]++ }
        // destroy: 非希望セルを休へ。休を担当できない職員は対象外（群外割当を作らない）。cnt も同期。
        for (i in 0 until p.S) {
            if (p.wishLocked(i, j) || !p.mayPlace(i, rest)) continue
            val old = schedule[i][j]
            if (old != rest && old in 0 until p.K) { schedule[i][j] = rest; cnt[i][old]--; cnt[i][rest]++ }
        }
        val covJ = IntArray(p.K)
        for (i in 0 until p.S) { val k = schedule[i][j]; if (k in 0 until p.K) covJ[k]++ }
        // [c41-aware / 実測 tools/nsp_bench.py: 群レンジ(cons41)があると小幅改善・無ければゼロ overhead で無害]
        //   群の「日次人数レンジ(cons41)」も marginal に加味し、群レンジ(上下限)も同時に研磨する。
        val hasC41 = p.cons41.isNotEmpty()
        val grpCnt = if (hasC41) Array(p.G) { IntArray(p.K) } else emptyArray()
        if (hasC41) for (i in 0 until p.S) { val k = schedule[i][j]; if (k in 0 until p.K) grpCnt[p.sgrp[i]][k]++ }
        fun c41DayMarg(g: Int, k: Int): Long {
            if (!hasC41) return 0L
            var d = 0L
            for (c in p.cons41) {
                if (c.groupIdx != g || c.shiftIdx != k) continue
                val z = grpCnt[g][k]; val z1 = z + 1
                val before = (if (z < c.l) c.l - z else 0) + (if (z > c.u) z - c.u else 0)
                val after = (if (z1 < c.l) c.l - z1 else 0) + (if (z1 > c.u) z1 - c.u else 0)
                d += (after - before).toLong()
            }
            return d
        }
        // [3.267.0/weekly統合] 職員別曜日バケット(weekly)を一度だけ構築（destroy後のschedule基準＝c41の
        // grpCntと同じ順序）。day j は固定のため bucket は全候補共通。
        val wd = Array(p.S) { s ->
            Array(p.K) { IntArray(7) }.also { a ->
                for (jj in 0 until p.T) { val k2 = schedule[s][jj]; if (k2 in 0 until p.K) a[k2][(p.dow0 + jj) % 7]++ }
            }
        }
        val bucket = (p.dow0 + j) % 7
        // repair: 各勤務シフトの需要を soft(個人 low/high/apt/weekly/fair ＋ 群レンジ c41)最小の休スタッフで満たす。
        for (k in 0 until p.K) {
            if (k == rest) continue   // [監査#2] 休以外の全シフトを対象（旧: k in 1..K-1 の「0=休」前提）
            // [3.379.0/need1直参照の第4世代] 旧: `p.need1[k][j] <= 0 → continue` で
            //   **need2 単独定義の需要を丸ごと素通り**していた（need1 未設定は -1）。3.173.0
            //   (CoverageDiagnosis)・3.309.0(isBalanceable)・3.369.0(初期解生成2つ+findCovOFix)で
            //   同じ穴を潰したのに、**RSI/ALNS 修復の中核であるこの2関数が取り残されていた**＝
            //   そのデータでは covU(HARD, 重み8000) を修復オペレータが原理的に埋められない。
            //   source of truth の `covUCell`（片方定義=その値）へ委譲する。
            var miss = p.covUCell(k, j, covJ[k])
            if (miss <= 0) continue
            while (miss > 0) {
                var bestI = -1; var bestDelta = Long.MAX_VALUE; var tied = 0
                for (i in 0 until p.S) {
                    if (schedule[i][j] != rest || p.wishLocked(i, j) || !p.mayPlace(i, k)) continue
                    val delta = DestroyRepairMarginalCost.staffCountPenaltyAt(p, i, k, cnt[i][k] + 1) - DestroyRepairMarginalCost.staffCountPenaltyAt(p, i, k, cnt[i][k]) +
                        c41DayMarg(p.sgrp[i], k) +
                        DestroyRepairMarginalCost.weeklyMarginalAt(wd[i], bucket, rest, k) +
                        DestroyRepairMarginalCost.fairMarginalAt(p, i, rest, -1, cnt) +
                        DestroyRepairMarginalCost.fairMarginalAt(p, i, k, 1, cnt)
                    if (delta < bestDelta) {
                        bestDelta = delta; bestI = i; tied = 1
                    } else if (delta == bestDelta) {
                        tied++
                        if (HypothesisDiversityPolicy.takeReservoirTie(tied, rng)) bestI = i
                    }
                }
                if (bestI < 0) break
                schedule[bestI][j] = k; cnt[bestI][k]++; cnt[bestI][rest]--; covJ[k]++; miss--
                if (hasC41) grpCnt[p.sgrp[bestI]][k]++
                wd[bestI][rest][bucket]--; wd[bestI][k][bucket]++
            }
        }
    }


    internal fun destroyRepairStaff(state: MagiState, schedule: Array<IntArray>, rng: Random, quantitativeRangeEval: Boolean = false) {
        val p = cachedProblem(state, quantitativeRangeEval)
        if (p.S == 0) return
        destroyRepairStaffAt(state, schedule, rng.nextInt(p.S), rng, quantitativeRangeEval)
    }


    internal fun destroyRepairStaffAt(state: MagiState, schedule: Array<IntArray>, i: Int, rng: Random, quantitativeRangeEval: Boolean = false) {
        val p = cachedProblem(state, quantitativeRangeEval)
        val allowed = p.allowedShiftsForStaff(i)
        if (allowed.isEmpty()) return
        val rest = restShiftIndex(state)   // [監査#2] 休の記号解決
        if (!p.mayPlace(i, rest)) return      // 休を担当できない職員は破壊修復の対象外（群外割当を作らない）
        // [soft-aware staff-DR / 実測 tools/nsp_bench.py --real: staff+viol で実データ final -49.5%]
        //   非希望セルを休へ destroy → 各日の被覆穴を「staff i の marginal soft 最小のシフト」で repair。
        //   被覆穴のみ埋める(過剰=covO を作らない)。希望固定は保持。スコアリング不変=Δ×フル無関係。
        // [3.267.0/weekly+fair統合] fair(群内公平化)は群メンバー全員の各回数が要るため、counts は
        // 全職員S×Kで構築する（cntI は counts[i] の別名＝同一配列参照、以降どちらの名前で更新しても
        // 他方に反映される）。wd(staff iの曜日別非休日数, 7要素)も一度だけ構築。
        val counts = Array(p.S) { s ->
            IntArray(p.K).also { a -> for (jj in 0 until p.T) { val k = schedule[s][jj]; if (k in 0 until p.K) a[k]++ } }
        }
        val cntI = counts[i]
        val wd = Array(p.K) { IntArray(7) }
        for (jj in 0 until p.T) { val k2 = schedule[i][jj]; if (k2 in 0 until p.K) wd[k2][(p.dow0 + jj) % 7]++ }
        for (j in 0 until p.T) {
            if (p.wishLocked(i, j)) continue
            val old = schedule[i][j]
            if (old != rest && old in 0 until p.K) {
                schedule[i][j] = rest
                cntI[old]--; cntI[rest]++
                wd[old][(p.dow0 + j) % 7]--; wd[rest][(p.dow0 + j) % 7]++
            }
        }
        // [高速化] 旧: 日×シフトごとに被覆を全職員走査(O(T×K×S))。盤面のうち本関数中に変わるのは staff i の行
        //   だけなので、被覆を一度だけ数え(O(S×T))、割当のたびに差分更新する(O(T×K))。挙動は再カウントと同一。
        val cov = Array(p.T) { IntArray(p.K) }
        for (x in 0 until p.S) for (j in 0 until p.T) { val k2 = schedule[x][j]; if (k2 in 0 until p.K) cov[j][k2]++ }
        for (j in 0 until p.T) {
            if (p.wishLocked(i, j) || schedule[i][j] != rest) continue
            val bucket = (p.dow0 + j) % 7
            var bestK = -1; var bestDelta = Long.MAX_VALUE; var tied = 0
            for (k in 0 until p.K) {
                if (k == rest || !p.mayPlace(i, k)) continue
                // [3.379.0/同上] need2 単独定義の穴を塞ぐ。`covUCell<=0` は「需要なし」と
                //   「既に足りている」の両方を同時に表すので、旧2条件をこれ1つで置き換えられる。
                if (p.covUCell(k, j, cov[j][k]) <= 0) continue
                val delta = DestroyRepairMarginalCost.staffCountPenaltyAt(p, i, k, cntI[k] + 1) - DestroyRepairMarginalCost.staffCountPenaltyAt(p, i, k, cntI[k]) +
                    DestroyRepairMarginalCost.weeklyMarginalAt(wd, bucket, rest, k) +
                    DestroyRepairMarginalCost.fairMarginalAt(p, i, rest, -1, counts) +
                    DestroyRepairMarginalCost.fairMarginalAt(p, i, k, 1, counts)
                if (delta < bestDelta) {
                    bestDelta = delta; bestK = k; tied = 1
                } else if (delta == bestDelta) {
                    tied++
                    if (HypothesisDiversityPolicy.takeReservoirTie(tied, rng)) bestK = k
                }
            }
            if (bestK >= 0) {
                schedule[i][j] = bestK
                cntI[bestK]++; cntI[rest]--
                wd[rest][bucket]--; wd[bestK][bucket]++
                cov[j][bestK]++; cov[j][rest]--
            }
        }
    }


    internal fun destroyRepairViolations(state: MagiState, schedule: Array<IntArray>, report: ViolationReport, rng: Random, quantitativeRangeEval: Boolean = false) {
        val p = cachedProblem(state, quantitativeRangeEval)
        val keys = report.violations.keys.toList()
        if (keys.isEmpty()) { randomAllowedCell(state, schedule, rng, quantitativeRangeEval); return }
        repeat(min(8, keys.size)) {
            val key = keys[rng.nextInt(keys.size)]
            val i = key.substringBefore(',').toIntOrNull() ?: return@repeat
            val j = key.substringAfter(',').toIntOrNull() ?: return@repeat
            if (i !in 0 until p.S || j !in 0 until p.T || p.wishLocked(i, j)) return@repeat
            val allowed = p.allowedShiftsForStaff(i)
            if (allowed.isEmpty()) return@repeat
            // [soft-aware violations / 実測で実データ final -22.6%] 違反セルを、staff i の現状回数で
            //   marginal soft(old→k)最小のシフトへ再割当(従来はランダム)。スコアリング不変=Δ×フル無関係。
            val cntI = IntArray(p.K)
            for (jj in 0 until p.T) { val k = schedule[i][jj]; if (k in 0 until p.K) cntI[k]++ }
            // [3.267.0/weekly+fair統合] この手専用にwd(staff iの曜日別非休日数)とcounts(全職員S×K, fairの
            // 達成率モードに要る)を構築。件数は最大8回(repeat)に限られ盤面規模も小さいため、毎回の再走査を許容する。
            val wd = Array(p.K) { IntArray(7) }
            for (jj in 0 until p.T) { val k2 = schedule[i][jj]; if (k2 in 0 until p.K) wd[k2][(p.dow0 + jj) % 7]++ }
            val counts = Array(p.S) { s ->
                IntArray(p.K).also { a -> for (jj in 0 until p.T) { val k = schedule[s][jj]; if (k in 0 until p.K) a[k]++ } }
            }
            val bucket = (p.dow0 + j) % 7
            val old = schedule[i][j]
            var bestK = old; var bestDelta = Long.MAX_VALUE; var tied = 0
            for (k in allowed) {
                if (k == old) continue
                val dOld = if (old in 0 until p.K) DestroyRepairMarginalCost.staffCountPenaltyAt(p, i, old, cntI[old] - 1) - DestroyRepairMarginalCost.staffCountPenaltyAt(p, i, old, cntI[old]) else 0L
                val dK = DestroyRepairMarginalCost.staffCountPenaltyAt(p, i, k, cntI[k] + 1) - DestroyRepairMarginalCost.staffCountPenaltyAt(p, i, k, cntI[k])
                val dWeekly = DestroyRepairMarginalCost.weeklyMarginalAt(wd, bucket, old, k)
                val dFair = (if (old in 0 until p.K) DestroyRepairMarginalCost.fairMarginalAt(p, i, old, -1, counts) else 0L) +
                    DestroyRepairMarginalCost.fairMarginalAt(p, i, k, 1, counts)
                val delta = dOld + dK + dWeekly + dFair
                if (delta < bestDelta) {
                    bestDelta = delta; bestK = k; tied = 1
                } else if (delta == bestDelta) {
                    tied++
                    if (HypothesisDiversityPolicy.takeReservoirTie(tied, rng)) bestK = k
                }
            }
            if (bestK != old) schedule[i][j] = bestK
        }
    }


    private fun randomAllowedCell(state: MagiState, schedule: Array<IntArray>, rng: Random, quantitativeRangeEval: Boolean = false) {
        val p = cachedProblem(state, quantitativeRangeEval)
        if (p.S == 0 || p.T == 0) return
        val i = rng.nextInt(p.S)
        val j = rng.nextInt(p.T)
        if (p.wishLocked(i, j)) return
        val allowed = p.allowedShiftsForStaff(i)
        if (allowed.isNotEmpty()) schedule[i][j] = allowed[rng.nextInt(allowed.size)]
    }


    internal fun perturb(state: MagiState, base: Array<IntArray>, rng: Random, strength: Double, quantitativeRangeEval: Boolean = false): Array<IntArray> {
        val p = cachedProblem(state, quantitativeRangeEval)
        val out = base.copy2D()
        val n = max(1, (p.S * p.T * strength).toInt())
        repeat(n) { randomAllowedCell(state, out, rng, quantitativeRangeEval) }
        return out
    }


}
