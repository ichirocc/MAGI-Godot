package com.magi.app.v6

import java.util.Random
import com.magi.app.model.MagiState

/**
 * RSI仮説生成（focus別ディスパッチ）と focus 専用 free-repair オペレータ群。[V6NativeOptimizer] から
 * 抽出（責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * **共有可変状態を一切参照しない**（[V6NativeOptimizer] 本体は @Volatile フィールド・Atomic系・
 * RunSlotのコルーチンコンテキスト連携で並行実行状態を密結合する「統括状態機械」の性格が強く、
 * 機械的な抽出は危険＝本ファイルはその中の安全な塊だけを切り出す）。参照するのは
 * `cachedProblem`/[UnifiedViolationChecker]（MirrorCore.kt）・`findCovUChain`/
 * `tryFixForbiddenRunViaAdjacentDay`（V6SearchOperators.kt のトップレベル関数）・
 * [CandidateCommit.commitBestMove]・[HardRepairCore.hf67HardRepair]・[DestroyRepairOperators]・
 * [DestroyRepairMarginalCost.destroyRepairStaffReps]（いずれも抽出済み internal object）のみで、
 * いずれも並行実行状態ではない。sched/out の in-place 変更は引数として渡された盤面配列への
 * 正当な副作用（[DestroyRepairOperators]/[HardRepairCore] と同型）。
 *
 * - [rsiGenerateHypothesis]：RSIラウンドの focus 別 destroy-repair ディスパッチ（3.101.0 c3n→
 *   violations経路・3.169.0/3.170.0 apt/weekly/fair 合流・3.204.0/3.209.0/3.233.0 free系配線・
 *   3.240.0 摂動量の動的化・3.241.0 順序バグ修正・3.313.0 締切伝播）。
 * - [applyCovUChains]：全covUセルの多人数玉突き連鎖充填（E11/3.155.0）。
 * - [applyCovOFree]：人員過剰の「動かせる」在勤者を実際に動かす専用repair（3.204.0・隣接日調整＝
 *   3.226.0・commitBestMove移行＝3.253.0）。
 * - [applyC41Free]：群/スキル群レンジ(c41/c41s)の超過・不足を直接解消（3.209.0/3.216.0）。
 * - [applyC42Free]：群/スキル群ペア禁止(c42/c42s)の違反ペアの片側を動かして崩す（3.233.0）。
 *
 * 呼び出し側（[V6NativeOptimizer] の runRsi/adaptiveEpochStart/optimizeエピローグとテスト）は全て
 * `RsiHypothesisOperators.<name>` の完全修飾へ一括置換した（applyCovUChains は本抽出で private から
 * internal へ昇格＝エピローグの保険パスがファイル外から呼ぶため。クラスタ内の自己呼出は同一object内
 * に移るため無修飾のまま）。
 */
internal object RsiHypothesisOperators {
    internal fun rsiGenerateHypothesis(
        state: MagiState, base: Array<IntArray>, report: ViolationReport, focus: String, rng: Random,
        // [3.313.0] free repair 群へ締切を通す。既定 `{ false }` ＝既存の直接呼出・テストは挙動不変。
        shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): Array<IntArray> {
        val out = base.copy2D()
        val p = cachedProblem(state, quantitativeRangeEval)
        when (focus) {
            // [E11] covU は「勤務→勤務」の多人数連鎖で充填（既存 destroyRepairDay は休→勤務のみ＝
            //   候補が過剰シフト/連鎖からしか引けない局面を踏めない）。仮説はラウンド better() でゲート＝退化なし。
            // [3.241.0/実機ログ起因=専用オペレータの改善がdestroyRepairDayで相殺される順序バグ修正]
            //   旧実装は「専用free関数→destroyRepairDay×6」の順で、destroyRepairDayのdestroy段階
            //   （非希望セルを休へ変える）がneed<=0のシフト（covOの主対象＝休等）へは一切repair
            //   （need>0のシフトのみ埋め戻す設計）が働かないため、直後の専用オペレータの改善を
            //   ランダムに(31日中6日=無視できない確率で)打ち消してしまっていた（8/26の休過剰1が
            //   covO focusのラウンドでも解消されなかった実例）。covU/c41/c41sの不足側はrepair段階
            //   （need>0のシフトを埋める設計）で自動的に再修復されるため実害は薄いが、covO/c42/c42s
            //   の過剰・違反ペア解消はrepairの対象外で影響が直接的。順序を「destroyRepairDay×6→
            //   専用free関数」へ統一し、hypothesisの最終状態に専用オペレータの改善が必ず残るようにする。
            "covU" -> { repeat(6) { DestroyRepairOperators.destroyRepairDay(state, out, rng, quantitativeRangeEval) }; applyCovUChains(state, out, rng, shouldStop, quantitativeRangeEval) }
            // [3.209.0/covOと同型の穴=c41/c41sがfocusされてもdestroyRepairDayのc41DayMargは副次効果でしか
            //   効かない] markNeed系(needViolations)にしか載らずGLSキック/destroyRepairViolationsのヒントを
            //   一切持てない点がcovOと同じ。applyC41Freeで群レンジの超過/不足を直接動かす専用オペレータへ。
            "c41" -> { repeat(6) { DestroyRepairOperators.destroyRepairDay(state, out, rng, quantitativeRangeEval) }; applyC41Free(state, out, rng, skill = false, shouldStop = shouldStop, quantitativeRangeEval = quantitativeRangeEval) }
            "c41s" -> { repeat(6) { DestroyRepairOperators.destroyRepairDay(state, out, rng, quantitativeRangeEval) }; applyC41Free(state, out, rng, skill = true, shouldStop = shouldStop, quantitativeRangeEval = quantitativeRangeEval) }
            // [3.233.0/c41,c41sと同型の穴] c42/c42sも「動かせるか」を判定する専用オペレータが無く
            // destroyRepairViolationsの汎用ランダム再割当頼みだった。applyC42Freeで違反ペアの
            // 片側を直接動かす専用オペレータへ。
            "c42" -> { repeat(6) { DestroyRepairOperators.destroyRepairDay(state, out, rng, quantitativeRangeEval) }; applyC42Free(state, out, rng, skill = false, shouldStop = shouldStop, quantitativeRangeEval = quantitativeRangeEval) }
            "c42s" -> { repeat(6) { DestroyRepairOperators.destroyRepairDay(state, out, rng, quantitativeRangeEval) }; applyC42Free(state, out, rng, skill = true, shouldStop = shouldStop, quantitativeRangeEval = quantitativeRangeEval) }
            // [実機ログ起因=apt未focus] apt(適切回数)は maxViolatedFamily の order に無く探索中は一度も focus
            //   されなかった（post-processing の applyDayAssignmentPolish 頼み）。destroyRepairStaff の marginal
            //   cost(DestroyRepairMarginalCost.staffCountPenaltyAt)は既にaptを織込み済み(重み1)のため、low/high/c2と同じ経路へ合流するだけで
            //   apt専用の新規オペレータ不要。ラウンド better() keep-best でゲート＝退化なし。
            // [同根の穴=weekly/fair] 同じ理由で weekly/fair も order に無く一度も focus されていなかった
            //   （実データ検証: weekly L1偏差合計65(aptの37より大きい)・fair合計11）。DestroyRepairMarginalCost.staffCountPenaltyAt は
            //   weekly/fair 未対応(曜日バケット・群平均を持たない)のため厳密な cost-aware 研磨ではないが、
            //   destroyRepairStaff は職員1人の月全体を破壊再構築する汎用オペレータであり、weekly/fair が
            //   支配的なときに専用ラウンドを割り当てるだけでも「total」の無指向な空振りより改善機会が増える。
            //   ラウンド better() keep-best でゲート＝退化なし（厳密な cost 統合は将来の拡張候補）。
            // [3.240.0/実機ログ起因=5ラウンド完全停滞の修正] destroyRepairStaff は「1人を丸ごと非希望日
            //   全休化→被覆穴のみ埋め直す」という1回で最大T(日数)セルを変える大きな摂動。covU focus の
            //   destroyRepairDay(1回で最大S(職員数)セル、repeat(6))と揃えるはずが、固定repeat(8)のまま
            //   だったため、S<T のデータ（実機=10職員/31日）では1ラウンドの総攪乱セル数が数倍に膨らみ、
            //   60秒/ラウンドのSA/ALNSでは破壊前の解に匹敵する状態まで回復しきれず、5ラウンド全て
            //   total不変のまま予算を使い切っていた（runV5の入力比番兵はhypothesis自体との比較のため
            //   この過大摂動を防げない）。destroyRepairDay基準(6回×S人ぶんのセル変化)に総攪乱セル数を
            //   揃えるよう reps を動的計算する（S>=T のデータでは reps>=6 相当まで許容＝挙動退化なし）。
            "low", "high", "c2", "apt", "weekly", "fair" -> {
                repeat(DestroyRepairMarginalCost.destroyRepairStaffReps(p.S, p.T)) { DestroyRepairOperators.destroyRepairStaff(state, out, rng, quantitativeRangeEval) }
            }
            // [3.204.0/実機ログ起因=covOがfocusされても直せなかった] covO は markNeed(k,j) で needViolations に
            //   載り、report.violations(セル"i,j"マップ)には現れないため、他の focus 未対応族の else 分岐が
            //   使う destroyRepairViolations(report.violations.keys 基準)では covO 専用のヒントが1つも無く、
            //   focus が回っても実質ランダムな空振りになっていた。covO診断(V6PortAnalyzer.diagnoseCoverage)と
            //   同じ「動かせるか」判定(希望固定/禁止連続を避け・移動先でcovOを悪化させない)をその場で「実行」する
            //   専用オペレータ applyCovOFree を新設し、covU chain(applyCovUChains)と対称に配線する。
            //   [3.241.0] destroyRepairDayを先に(順序バグ修正、上記covUコメント参照)＝covOは特にneed<=0
            //   シフト(休等)の過剰が主対象でrepair段階の恩恵が皆無のため、この順序修正の効果が最も直接的。
            "covO" -> { repeat(6) { DestroyRepairOperators.destroyRepairDay(state, out, rng, quantitativeRangeEval) }; applyCovOFree(state, out, rng, shouldStop, quantitativeRangeEval) }
            // [実機ログ起因] groupViol/pref は hf67 の作用対象(hf66DataHardening=群外修正・希望反映)だが、
            //   c3n(禁止連続=HARD)は hf67 が一切作用しない(被覆/希望/下限のみ)＝c3n focus のラウンドが no-op 仮説で
            //   空転していた(実機3実行×計10ラウンドで c3n=1 不変→HF63 が c3n を誤 infeasible 判定)。c3n のセルは
            //   violations マップに載る(両端2セル)ので、違反セルを直接再割当する destroyRepairViolations(else)へ回す。
            //   仮説はラウンド単位 better() keep-best でゲート済＝退化なし。
            "groupViol", "pref" -> {
                val fixed = HardRepairCore.hf67HardRepair(state, out, rng).schedule
                for (i in 0 until p.S) for (j in 0 until p.T) out[i][j] = fixed[i][j]
            }
            else -> repeat(12) { DestroyRepairOperators.destroyRepairViolations(state, out, report, rng, quantitativeRangeEval) }
        }
        return out
    }

    /**
     * [E11/多人数ブロック移動] 現盤面の全 covU セルを、同日・多人数の玉突き連鎖（findCovUChain）で
     * 充填する。sched を in-place 変更し、適用手数を返す。連鎖は同日内交換＝被覆総量保存で、canDo/非wishLocked/
     * c3n枝刈り済み。最終採否は呼び出し側の keep-best（ラウンド better() or エピローグの checker 照合）が担保。
     * ユーザー実例（2026-08）: 8/11 モニカ B4→Cｵ（深さ1）／8/17 上條 Cｵ→Cｱ・山本 →Cｵ（深さ2）。
     */
    // [3.313.0] 締切/キャンセル確認。これらは違反セル × 候補職員の二重ループの内側で
    //   フル checker（commitBestMove）と findCovUChain(BFS) を呼ぶ高コストパスで、旧実装は
    //   停止確認を一切持たなかった（3.161.0 で V6HotfixPasses の研磨パスへ入れた「内側ループ
    //   でも締切を見る」の対象漏れ）。既定 `{ false }` なので既存の直接呼出＝挙動不変。
    internal fun applyCovUChains(
        state: MagiState, sched: Array<IntArray>, rng: Random,
        shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): Int {
        val p = cachedProblem(state, quantitativeRangeEval)
        if (p.S == 0 || p.T == 0) return 0
        var applied = 0
        val cnt = IntArray(p.K)
        for (j in 0 until p.T) {
            if (shouldStop()) return applied
            for (k in 0 until p.K) cnt[k] = 0
            for (i in 0 until p.S) { val kk = sched[i][j]; if (kk in 0 until p.K) cnt[kk]++ }
            for (k in 0 until p.K) {
                if (p.covUCell(k, j, cnt[k]) <= 0) continue
                val chain = findCovUChain(p, sched, k, j, rng) ?: continue
                for (mv in chain) sched[mv[0]][mv[1]] = mv[2]
                applied++
                // 同日に複数 covU があり得るので当日カウントを再計算。
                for (kk in 0 until p.K) cnt[kk] = 0
                for (i in 0 until p.S) { val kk = sched[i][j]; if (kk in 0 until p.K) cnt[kk]++ }
            }
        }
        return applied
    }

    /**
     * [3.204.0/covO専用repair] 人員過剰(covO)セルの在勤者のうち、他シフトへ移しても新たな違反を生まない
     * （希望固定でない・移すと禁止連続(c3n)を作らない・移動先で covO が悪化しない＝受け皿あり）候補を1人
     * 見つけて実際に移す。V6PortAnalyzer.diagnoseCoverage の covO 診断（動かせる/玉突き必要/希望固定/
     * 禁止連続の4分類）と同じ判定を「実行」する版（診断＝読取専用、こちらは探索オペレータ）。
     * 被覆総量は保存しない（過剰シフトから1人引くだけ＝covOのみ改善方向）。動かせる候補が尽きたセルは
     * そのまま残す（希望固定/または隣接日調整(下記)を含め本当に動かせない、または玉突きが要る＝本
     * オペレータの対象外）。sched を in-place 変更し、適用手数を返す。最終採否は呼び出し側の
     * keep-best が担保＝退化なし。
     *
     * [3.226.0/禁止連続の回避=隣接日調整] 移動先が全て禁止連続(c3n)で塞がる場合、即諦めず
     * `tryFixForbiddenRunViaAdjacentDay`（findCovUChainのcovU側と共通のヘルパー）で隣接日(j-1/j+1)の
     * 本人の割当を変えてパターンを崩せないか試す。空くシフトのcovU悪化はfindCovUChainで玉突き埋め直し
     * 済み（ヘルパー内部で完結）。ここでは追加で「隣接日の変更後、移動先mでcovOが悪化しないか」を
     * 確認し、悪化するなら隣接日側の変更ごと巻き戻して次の候補へ（実際に適用したcov[j2]/schedは
     * 必ず復元してから次を試す）。
     */

    // [3.313.0] 締切/キャンセル確認。これらは違反セル × 候補職員の二重ループの内側で
    //   フル checker（commitBestMove）と findCovUChain(BFS) を呼ぶ高コストパスで、旧実装は
    //   停止確認を一切持たなかった（3.161.0 で V6HotfixPasses の研磨パスへ入れた「内側ループ
    //   でも締切を見る」の対象漏れ）。既定 `{ false }` なので既存の直接呼出＝挙動不変。
    internal fun applyCovOFree(
        state: MagiState, sched: Array<IntArray>, rng: Random,
        shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): Int {
        val p = cachedProblem(state, quantitativeRangeEval)
        if (p.S == 0 || p.T == 0) return 0
        var applied = 0
        for (j in 0 until p.T) {
            if (shouldStop()) return applied
            for (k in 0 until p.K) {
                while (true) {
                    if (shouldStop()) return applied
                    val cov = IntArray(p.K)
                    for (i in 0 until p.S) { val kk = sched[i][j]; if (kk in 0 until p.K) cov[kk]++ }
                    if (p.covOCell(k, j, cov[k]) <= 0) break
                    val baseline = UnifiedViolationChecker.check(state, sched, quantitativeRangeEval = quantitativeRangeEval)
                    val staffOnK = (0 until p.S).filter { sched[it][j] == k }
                    val candidates = ArrayList<List<IntArray>>()
                    for (i in staffOnK) {
                        // [3.391.0] 生の `wish==k` は**実現不能な希望**（担当できないシフトへの希望）まで
                        //   固定扱いにしていた。pref は実現可能な希望しか数えない（MirrorCore）ので、
                        //   その場合ここを動かしても pref は増えず、逆に担当外セル＝groupViol(10000) が消える
                        //   ＝**必須違反が厳密に減る手を丸ごと捨てていた**。規約の wishLocked へ統一（3.351.0 と同型）。
                        if (p.wishLocked(i, j) && p.wish[i][j] == k) continue   // 実現可能な本人希望＝動かすとpref未充足化
                        for (m in p.allowedShiftsForStaff(i).filter { it != k }) {
                            if (p.makesForbiddenRun(sched, i, j, m)) {
                                val fix = tryFixForbiddenRunViaAdjacentDay(p, sched, i, j, m, rng) ?: continue
                                candidates.add(fix + listOf(intArrayOf(i, j, m)))
                            } else {
                                candidates.add(listOf(intArrayOf(i, j, m)))
                            }
                        }
                    }
                    if (candidates.isEmpty()) break
                    if (CandidateCommit.commitBestMove(state, sched, baseline, candidates, quantitativeRangeEval) == null) break
                    applied++
                }
            }
        }
        return applied
    }

    /**
     * [3.209.0/c41・c41s専用repair] c41/c41s（群×日×シフトの人数レンジ[l,u]違反）は covO/covU と同じく
     * markNeed(needViolations)にしか載らず report.violations（職員×日マップ）には現れないため、
     * GLSキック・destroyRepairViolations が一切ヒントを持てず、RSI focus されても
     * applyCovUChains+destroyRepairDay（covU=シフト単位の不足専用）では群レンジの上限超過・下限割れの
     * どちらも直接には狙えない（destroyRepairDayAt の c41DayMarg は covU 充填の副次効果でしか働かない）。
     * covO診断/applyCovOFreeと同じ「動かせるか」判定をこの群レンジにも適用し、超過なら群内在籍者を
     * 他シフトへ・不足なら群内の他シフト在籍者を引き入れて実際に解消する。skill=false は cons41(sgrp)、
     * skill=true は cons41s(ssk) を対象にする（DRY化）。希望固定でない・禁止連続(c3n)を作らない・
     * 移動元/移動先で covU/covO を悪化させない候補のみ動かす。sched を in-place 変更し適用手数を返す。
     * 最終採否は呼び出し側の keep-best が担保＝退化不能。
     */
    // [3.313.0] 締切/キャンセル確認。これらは違反セル × 候補職員の二重ループの内側で
    //   フル checker（commitBestMove）と findCovUChain(BFS) を呼ぶ高コストパスで、旧実装は
    //   停止確認を一切持たなかった（3.161.0 で V6HotfixPasses の研磨パスへ入れた「内側ループ
    //   でも締切を見る」の対象漏れ）。既定 `{ false }` なので既存の直接呼出＝挙動不変。
    internal fun applyC41Free(
        state: MagiState, sched: Array<IntArray>, rng: Random, skill: Boolean,
        shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): Int {
        val p = cachedProblem(state, quantitativeRangeEval)
        if (p.S == 0 || p.T == 0) return 0
        val rules = if (skill) p.cons41s else p.cons41
        if (rules.isEmpty()) return 0
        val grp = if (skill) p.ssk else p.sgrp
        var applied = 0
        fun groupCount(c: C41, j: Int): Int {
            var z = 0
            for (i in 0 until p.S) if (grp[i] == c.groupIdx && sched[i][j] == c.shiftIdx) z++
            return z
        }
        // [3.253.0, commitBestMoveへ全面移行] 旧実装は「離脱元/到着先のcovU/covOが非悪化」を満たす
        //   最初の候補（見つからなければ玉突き連鎖の最初の候補）で即採用しており、動かす本人自身の
        //   staffRange/apt/c1/c2/weekly/fair等への影響を一切見ていなかった（実データ検証でcovO/c42の
        //   同型実装が大半の試行でtotalを悪化させることを確認、詳細はcommitBestMoveのdoc参照）。
        //   ここでは構造的に安全（希望非固定・禁止連続なし）な候補を直接移動・玉突き連鎖の両方で
        //   網羅的に集め、commitBestMoveが実チェッカーで全体評価して真に改善する最良の1件だけを選ぶ。
        for (c in rules) {
            if (shouldStop()) return applied
            for (j in 0 until p.T) {
                if (shouldStop()) return applied
                // 超過(z>u): 群在籍者を他シフトへ移す。
                while (groupCount(c, j) > c.u) {
                    val baseline = UnifiedViolationChecker.check(state, sched, quantitativeRangeEval = quantitativeRangeEval)
                    val onShift = (0 until p.S).filter { grp[it] == c.groupIdx && sched[it][j] == c.shiftIdx }
                    val candidates = ArrayList<List<IntArray>>()
                    for (i in onShift) {
                        // [3.391.0] 実現不能な希望は固定しない（wishLocked へ統一）。上の applyCovOFree と同型。
                        if (p.wishLocked(i, j) && p.wish[i][j] == c.shiftIdx) continue   // 実現可能な本人希望＝対象外
                        for (m in p.allowedShiftsForStaff(i).filter { it != c.shiftIdx }) {
                            if (p.makesForbiddenRun(sched, i, j, m)) continue
                            candidates.add(listOf(intArrayOf(i, j, m)))
                            // 玉突き連鎖版（離脱先を先に適用してから探索＝本人がまだ在籍中に見える誤判定を防ぐ既定の作法）。
                            val oldK = sched[i][j]
                            sched[i][j] = m
                            val chain = findCovUChain(p, sched, c.shiftIdx, j, rng, exclude = i)
                            sched[i][j] = oldK
                            if (chain != null) candidates.add(listOf(intArrayOf(i, j, m)) + chain)
                        }
                    }
                    if (candidates.isEmpty()) break
                    if (CandidateCommit.commitBestMove(state, sched, baseline, candidates, quantitativeRangeEval) == null) break
                    applied++
                }
                // 不足(z<l): 群内の他シフト在籍者を引き入れる。
                while (groupCount(c, j) < c.l) {
                    val baseline = UnifiedViolationChecker.check(state, sched, quantitativeRangeEval = quantitativeRangeEval)
                    val offShift = (0 until p.S).filter { grp[it] == c.groupIdx && sched[it][j] != c.shiftIdx && p.mayPlace(it, c.shiftIdx) }
                    val candidates = ArrayList<List<IntArray>>()
                    for (i in offShift) {
                        val old = sched[i][j]
                        // [3.391.0] 実現不能な希望は固定しない（wishLocked へ統一）。
                        if (old !in 0 until p.K || (p.wishLocked(i, j) && p.wish[i][j] == old)) continue   // 現シフトが実現可能な本人希望＝対象外
                        if (p.makesForbiddenRun(sched, i, j, c.shiftIdx)) continue
                        candidates.add(listOf(intArrayOf(i, j, c.shiftIdx)))
                        sched[i][j] = c.shiftIdx
                        val chain = findCovUChain(p, sched, old, j, rng, exclude = i)
                        sched[i][j] = old
                        if (chain != null) candidates.add(listOf(intArrayOf(i, j, c.shiftIdx)) + chain)
                    }
                    if (candidates.isEmpty()) break
                    if (CandidateCommit.commitBestMove(state, sched, baseline, candidates, quantitativeRangeEval) == null) break
                    applied++
                }
            }
        }
        return applied
    }

    /**
     * [3.233.0/ドッグフーディングで発見・covO(3.204.0)/c41,c41s(3.209.0)と同型の専用repair欠如]
     * c42(群ペア禁止: 群g1のs1×群g2のs2が同日に同時発生禁止)は`mark(i,j,"c42")`で
     * report.violations(セルマップ)には載るため destroyRepairViolations の汎用ランダム再割当は
     * 一応届くが、covU/covO/c41のような「動かせるか(希望固定/禁止連続/被覆悪化を避ける)」を判定して
     * 実際に動かす専用オペレータが無かった。違反ペア(left∈g1×s1, right∈g2×s2)のどちらか一方を
     * 実際に他シフトへ動かして崩す。移動先でcovOが悪化しない候補を探し、離脱元でcovUが悪化するなら
     * findCovUChainで玉突きフォールバック（c41Free(3.209.0)で判明済みの罠=「離脱を先にschedへ適用して
     * からfindCovUChainを呼ぶ」順序を踏襲。逆順だと本人がまだ在籍中に見え常にnullが返る）。
     * skill=false は cons42(sgrp)、skill=true は cons42s(ssk) を対象にする（DRY化）。
     * sched を in-place 変更し適用手数を返す。最終採否は呼び出し側のkeep-best（ラウンドbetter()）が
     * 担保＝退化不能。
     */
    // [3.313.0] 締切/キャンセル確認。これらは違反セル × 候補職員の二重ループの内側で
    //   フル checker（commitBestMove）と findCovUChain(BFS) を呼ぶ高コストパスで、旧実装は
    //   停止確認を一切持たなかった（3.161.0 で V6HotfixPasses の研磨パスへ入れた「内側ループ
    //   でも締切を見る」の対象漏れ）。既定 `{ false }` なので既存の直接呼出＝挙動不変。
    internal fun applyC42Free(
        state: MagiState, sched: Array<IntArray>, rng: Random, skill: Boolean,
        shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): Int {
        val p = cachedProblem(state, quantitativeRangeEval)
        if (p.S == 0 || p.T == 0) return 0
        val rules = if (skill) p.cons42s else p.cons42
        if (rules.isEmpty()) return 0
        val grp = if (skill) p.ssk else p.sgrp
        var applied = 0
        // [3.253.0, commitBestMoveへ全面移行] 詳細はcommitBestMove/applyC41Freeのdoc参照。
        //   違反ペアの片側(left=g1×s1 / right=g2×s2)それぞれについて、構造的に安全な直接移動・
        //   玉突き連鎖の両方の候補を集め、commitBestMoveが実チェッカーで全体評価する。
        fun gatherSide(candidates: List<Int>, j: Int, fromShift: Int, out: ArrayList<List<IntArray>>) {
            for (i in candidates) {
                // [3.391.0] 実現不能な希望は固定しない（wishLocked へ統一）。
                if (p.wishLocked(i, j) && p.wish[i][j] == fromShift) continue   // 実現可能な本人希望＝対象外
                for (m in p.allowedShiftsForStaff(i).filter { it != fromShift }) {
                    if (p.makesForbiddenRun(sched, i, j, m)) continue
                    out.add(listOf(intArrayOf(i, j, m)))
                    val oldK = sched[i][j]
                    sched[i][j] = m
                    val chain = findCovUChain(p, sched, fromShift, j, rng, exclude = i)
                    sched[i][j] = oldK
                    if (chain != null) out.add(listOf(intArrayOf(i, j, m)) + chain)
                }
            }
        }
        for (c in rules) {
            if (shouldStop()) return applied
            for (j in 0 until p.T) {
                if (shouldStop()) return applied
                while (true) {
                    val left = (0 until p.S).filter { grp[it] == c.g1 && sched[it][j] == c.s1 }
                    val right = (0 until p.S).filter { grp[it] == c.g2 && sched[it][j] == c.s2 }
                    if (left.isEmpty() || right.isEmpty()) break   // ペアが存在しない＝この日は解消済み
                    val baseline = UnifiedViolationChecker.check(state, sched, quantitativeRangeEval = quantitativeRangeEval)
                    val candidates = ArrayList<List<IntArray>>()
                    gatherSide(left, j, c.s1, candidates)
                    gatherSide(right, j, c.s2, candidates)
                    if (candidates.isEmpty()) break
                    if (CandidateCommit.commitBestMove(state, sched, baseline, candidates, quantitativeRangeEval) == null) break
                    applied++
                }
            }
        }
        return applied
    }

}
