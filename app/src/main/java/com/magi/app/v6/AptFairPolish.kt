package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random

/**
 * 適切回数(apt, 重み1)/グループ内公平化(fair, 重み1)専用の研磨2パス。[V6HotfixPasses] から
 * 抽出（責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * 両パスとも同型の3段構成（ファイル自身の履歴コメントが対称性を明記）:
 * 手①自己振替（他人に影響しない最安全な手）→ 手②相互交換（同一グループ内・同日swap・被覆総量保存）→
 * 手③玉突きチェーン（`findCovUChain`、残りをavoid述語つきで解消）。
 *
 * - [applyAptPolish]：個人の適切回数(apt)偏差を解消する。
 * - [applyFairPolish]：グループ内の回数公平性(fair)偏差を解消する。
 *
 * `CyclicSwapResult` は [V6HotfixPasses] に残置される共有返り型のため、ここからは完全修飾で
 * 構築・参照する。
 */
internal object AptFairPolish {
    /** [3.535.0/HF77明示数値指示] 研磨開始時点の対象家族(apt/fair)以外のSOFT合計の6%を上限に、
     *  その悪化を容認する累積予算。既定OFF（[PolishGate.aptFairSoftTolerance]）。 */
    internal const val SOFT_TOLERANCE_FRACTION = 0.06

    /** internal＝`AptFairPolishToleranceTest` から直接検証するため（private だとファイル外から呼べない）。 */
    internal fun nonFamilySoftTotal(rep: ViolationReport, excludeFamily: String): Double =
        MirrorKeys.soft.filter { it != excludeFamily }.sumOf { (rep.breakdown[it] ?: 0) * MirrorKeys.weights.getValue(it) }

    /**
     * [3.535.0] OFF時はbetterReport(keep-best)のまま。ON時はHARD不増加は変えず、対象家族以外のSOFT
     * 悪化分を研磨開始時点(before)比+6%の累積予算まで差し引いてweightedScoreを比較する（詳細は3.535.0節）。
     */
    internal fun toleratedBetter(rep: ViolationReport, bestRep: ViolationReport, before: ViolationReport, family: String, enabled: Boolean): Boolean {
        if (!enabled) return betterReport(rep, bestRep)
        if (rep.hard != bestRep.hard) return rep.hard < bestRep.hard
        val baseline = nonFamilySoftTotal(before, family)
        val budget = baseline * SOFT_TOLERANCE_FRACTION
        val usedByBest = (nonFamilySoftTotal(bestRep, family) - baseline).coerceAtLeast(0.0)
        val remaining = (budget - usedByBest).coerceAtLeast(0.0)
        val increase = (nonFamilySoftTotal(rep, family) - nonFamilySoftTotal(bestRep, family)).coerceAtLeast(0.0)
        val forgiven = minOf(increase, remaining)
        val rawDelta = rep.weightedScore - bestRep.weightedScore
        val effectiveDelta = rawDelta - forgiven
        val accepted = effectiveDelta < 0.0 || (effectiveDelta == 0.0 && rep.total < bestRep.total)
        // [3.535.0/実機ログで発覚] TuningTelemetry.summaryの「設定の効き」に本トグルの行が無く、
        // ONでも実行の痕跡が一切見えなかった（他トグルは効果カウンタつきで表示済み）。素のbetterReport
        // （＝rawDeltaだけで同じ判定）なら却下されるはずの手を、容認で採用に転じさせた回数だけを数える。
        if (accepted && forgiven > 0.0) {
            val rawAccepted = rawDelta < 0.0 || (rawDelta == 0.0 && rep.total < bestRep.total)
            if (!rawAccepted) TuningTelemetry.aptFairToleranceUsed.incrementAndGet()
        }
        return accepted
    }

    /**
     * [AptPolish・適切回数(apt, 重み1)専用の研磨パス] ユーザー指示「専用の研磨パスAptPolish的なものを
     * 賢く深く網羅的に作る」（grillingで確定: ①自己振替最優先 ②同一グループ内の相互交換(同日1対1・
     * 被覆総量保存で安全) ③RangePolish型の玉突きチェーン、の順で試す）。
     *
     * 動機（大島愛の実例）: 群目標(groupShiftApt)に対しaptHigh(超過)とaptLow(不足)が同一職員内に同時に
     * 存在するケース（休=超過・Pｼ=不足）は、本人内で1日分を振替えるだけで両方が同時に改善する「タダの
     * 交換」のはずだが、apt(重み1)はRSI探索中のfocus選択で軽視されやすく(3.169.0)、専用研磨が無いまま
     * 残っていた。
     *
     * アンカー: `report.countViolations`（"i,k"→"vio-aptHigh"/"vio-aptLow"、markCountの重み優先解決済）
     * から違反している(staff,shift)ペアを列挙。
     * 手①自己振替: 同一職員が別のシフトでaptLow(逆方向)を持つ場合、その2シフト間で1日を直接付け替える
     *   （他人に一切影響しない最安全な手）。付け替え元/先双方の被覆(covUCell)を悪化させない日のみ候補
     *   にする（悪化するならチェーンを使わず単に見送り＝真に無償の手のみを対象にする）。
     * 手②相互交換: 同一グループ(canDo完全一致)内に、同じシフトで逆方向のapt不均衡を持つ相手がいれば、
     *   同日の2人の割当をまるごと入替える（同日swap＝被覆総量保存＝構造的に安全、BlockSwapPolishと
     *   同型の安全性。相手のcanDoは同一グループのため保証済み）。
     * 手③玉突きチェーン: 上記いずれでも解消しない残りは、RangePolishと同型のfindCovUChain（候補が
     *   自身の新規apt違反を招くなら後回しにするavoid述語つき）で任意の担当可能シフトへ移す。
     * 採否はisBetter(hard→weighted→total)keep-best＝退化不能。全手とも希望固定(movable)・禁止連続
     * (makesForbiddenRun)を事前ガード。
     */
    fun applyAptPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, seed: Long = 0xA97L, quantitativeRangeEval: Boolean = false, combineExhaustPairs: Boolean = false, aptFairSoftTolerance: Boolean = false): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        val rng = Random(seed)
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        fun label(i: Int, k: Int) = "${state.staff.getOrNull(i)?.name ?: "#$i"} ${state.shifts.getOrNull(k)?.kigou ?: k.toString()}"
        val fixedNames = ArrayList<String>()
        // [汎用玉突き結合フレームワーク, 3.249.0] tryChainRelocate(手③)が単独では不採用だった候補を
        //   蓄積し末尾で束ねる。
        val combinable = ArrayList<CombinatorialRepair.Candidate>()
        val rejectCulprits = RejectCulpritStats()

        // [玉突きチェーンのavoid述語] 候補がfillShiftを1つ得ると自身のapt目標からちょうど新規に
        //   乖離するか（既に乖離済みなら「まだ動いていない」ので中立扱い＝対象外）。
        fun worsensOwnApt(staff: Int, fillShift: Int): Boolean {
            val t = p.apt[staff][fillShift]
            if (t < 0) return false
            var c = 0
            for (jj in 0 until p.T) if (work[staff][jj] == fillShift) c++
            return c == t
        }

        // [厳密ピン保護] 本パスの全手は i(・相手)の回数を直接変える(apt/fair研磨の本質)ため、staffRange
        //   厳密ピン(lo==hi)を新たに崩す候補だけは不採用にする（keep-best/重みは不変・追加ガードのみ）。
        fun applyAndCheck(i: Int, j: Int, fromK: Int, toK: Int): Boolean {
            val workBefore = work.copy2D()
            work[i][j] = toK
            val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
            val pinBad = exactPinRegression(p, workBefore, work)
            if (pinBad && toleratedBetter(rep, bestRep, before, "apt", aptFairSoftTolerance)) pinBlocks.record(p, workBefore, work)
            if (toleratedBetter(rep, bestRep, before, "apt", aptFairSoftTolerance) && !pinBad) { bestRep = rep; applied++; return true }
            rejectCulprits.record(rep, bestRep, pinBad)
            work[i][j] = fromK
            return false
        }

        // 手①: 自身の中でfromK(過多)→toK(過少)への1日付け替え。被覆非悪化の日のみ候補にする。
        fun trySelfSwap(i: Int, fromK: Int, toK: Int): Boolean {
            for (j in 0 until p.T) {
                if (shouldStop()) return false
                if (work[i][j] != fromK || !movable(i, j)) continue
                if (p.makesForbiddenRun(work, i, j, toK)) continue
                var cntFrom = 0; var cntTo = 0
                for (s in 0 until p.S) { if (work[s][j] == fromK) cntFrom++; if (work[s][j] == toK) cntTo++ }
                if (p.covUCell(fromK, j, cntFrom - 1) > p.covUCell(fromK, j, cntFrom)) continue
                if (p.covUCell(toK, j, cntTo + 1) > p.covUCell(toK, j, cntTo)) continue
                if (applyAndCheck(i, j, fromK, toK)) return true
            }
            return false
        }

        // 手②: 同一グループ内で同日の2人の割当をまるごと入替（被覆総量保存＝安全）。
        fun tryMutualSwap(i: Int, i2: Int, sharedK: Int): Boolean {
            for (j in 0 until p.T) {
                if (shouldStop()) return false
                val a = work[i][j]; val b = work[i2][j]
                if (a != sharedK || b == sharedK) continue
                if (!movable(i, j) || !movable(i2, j)) continue
                if (p.makesForbiddenRun(work, i, j, b) || p.makesForbiddenRun(work, i2, j, a)) continue
                val workBefore = work.copy2D()
                work[i][j] = b; work[i2][j] = a
                val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                val pinBad = exactPinRegression(p, workBefore, work)
                if (pinBad && toleratedBetter(rep, bestRep, before, "apt", aptFairSoftTolerance)) pinBlocks.record(p, workBefore, work)
                if (toleratedBetter(rep, bestRep, before, "apt", aptFairSoftTolerance) && !pinBad) { bestRep = rep; applied++; return true }
                rejectCulprits.record(rep, bestRep, pinBad)
                work[i][j] = a; work[i2][j] = b
            }
            return false
        }

        // 手③: RangePolish型の玉突きチェーン。
        fun tryChainRelocate(i: Int, j: Int, fromK: Int, toK: Int): Boolean {
            if (!movable(i, j) || p.makesForbiddenRun(work, i, j, toK)) return false
            var cnt = 0
            for (s in 0 until p.S) if (work[s][j] == fromK) cnt++
            val needsChain = p.covUCell(fromK, j, cnt - 1) > p.covUCell(fromK, j, cnt)
            val workBeforeRelocate = work.copy2D()
            work[i][j] = toK
            if (!needsChain) {
                val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                val pinBad = exactPinRegression(p, workBeforeRelocate, work)
                if (pinBad && toleratedBetter(rep, bestRep, before, "apt", aptFairSoftTolerance)) pinBlocks.record(p, workBeforeRelocate, work)
                if (toleratedBetter(rep, bestRep, before, "apt", aptFairSoftTolerance) && !pinBad) { bestRep = rep; applied++; return true }
                rejectCulprits.record(rep, bestRep, pinBad)
                work[i][j] = fromK
                combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j, toK)), "AptChain", label(i, fromK)))
                return false
            }
            val chain = findCovUChain(p, work, fromK, j, rng, exclude = i,
                rangeAvoid = { st, fk -> worsensOwnApt(st, fk) })
            if (chain == null) { work[i][j] = fromK; return false }
            val oldVals = IntArray(chain.size) { work[chain[it][0]][chain[it][1]] }
            chain.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
            val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
            val pinBad = exactPinRegression(p, workBeforeRelocate, work)
            if (pinBad && toleratedBetter(rep, bestRep, before, "apt", aptFairSoftTolerance)) pinBlocks.record(p, workBeforeRelocate, work)
            if (toleratedBetter(rep, bestRep, before, "apt", aptFairSoftTolerance) && !pinBad) { bestRep = rep; applied++; return true }
            rejectCulprits.record(rep, bestRep, pinBad)
            for (idx in chain.indices) work[chain[idx][0]][chain[idx][1]] = oldVals[idx]
            work[i][j] = fromK
            combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j, toK)) + chain, "AptChain", label(i, fromK)))
            return false
        }

        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
            val highTargets = ArrayList<Pair<Int, Int>>()
            val lowTargets = ArrayList<Pair<Int, Int>>()
            for ((key, cls) in rep0.countViolations) {
                val parts = key.split(",")
                val i = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val k = parts.getOrNull(1)?.toIntOrNull() ?: continue
                when (cls) {
                    "vio-aptHigh" -> highTargets.add(i to k)
                    "vio-aptLow" -> lowTargets.add(i to k)
                }
            }
            if (highTargets.isEmpty() && lowTargets.isEmpty()) break

            for ((i, k) in highTargets) {
                if (shouldStop()) break
                var done = false
                // 手①: 自身の別シフトでaptLowのものへ振替（同一(fromK,toK)ペアで解消するまで反復＝
                //   RangePolishの「上限まで反復して落とす」と同型に統一。他者に一切影響しない自己完結の
                //   手のためisBetterが認める限り繰り返して安全。旧実装は1回成功したら次のhighTargetsへ
                //   移っており、excess/deficitが複数単位ある職員は1パスにつき1単位しか解消できず、
                //   予算超過で後続パスが打ち切られると大きな乖離が残存し続けていた）。
                for (k2 in 0 until p.K) {
                    if (shouldStop()) break
                    if (k2 == k || !p.mayPlace(i, k2)) continue
                    if (lowTargets.none { it.first == i && it.second == k2 }) continue
                    while (trySelfSwap(i, k, k2)) { improved = true; done = true }
                }
                if (done) fixedNames.add(label(i, k))
                // 手②: 同一グループで逆方向(aptLow)の相手と相互交換。
                if (!done) {
                    for (i2 in 0 until p.S) {
                        if (done || shouldStop()) break
                        if (i2 == i || p.sgrp[i2] != p.sgrp[i]) continue
                        if (lowTargets.none { it.first == i2 && it.second == k }) continue
                        if (tryMutualSwap(i, i2, k)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                    }
                }
                // 手③: 玉突きチェーンで任意の担当可能シフトへ。
                if (!done) {
                    for (j in 0 until p.T) {
                        if (done || shouldStop()) break
                        if (work[i][j] != k) continue
                        for (alt in p.allowedShiftsForStaff(i)) {
                            if (done || shouldStop()) break
                            if (alt == k) continue
                            if (tryChainRelocate(i, j, k, alt)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                        }
                    }
                }
            }
            // 単独aptLow(自己振替/相互交換で解消しなかった残り)を玉突きチェーンで埋める。
            for ((i, k) in lowTargets) {
                if (shouldStop()) break
                if (!p.mayPlace(i, k)) continue
                var done = false
                for (j in 0 until p.T) {
                    if (done || shouldStop()) break
                    val oldK = work[i][j]
                    if (oldK == k || oldK !in 0 until p.K) continue
                    if (tryChainRelocate(i, j, oldK, k)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                }
            }
            pass++
            if (!improved) break
        }
        // [汎用玉突き結合フレームワーク, 3.249.0] stuckNames より前に実行し、結合で解消した箇所が
        //   「残存」に残らないようにする。
        val rejectedOut = ArrayList<CombinatorialRepair.Candidate>()
        val aptCombStats = CombinatorialRepair.Stats()
        bestRep = CombinatorialRepair.combineAndApply(
            state, work, bestRep, combinable.asReversed(),
            { a, b -> toleratedBetter(a, b, before, "apt", aptFairSoftTolerance) },
            shouldStop = shouldStop, stats = aptCombStats, p = p, leftover = rejectedOut,
            exhaustPairs = combineExhaustPairs,
        )
        applied += aptCombStats.combosAccepted
        val stuckNames = bestRep.countViolations.entries
            .filter { it.value == "vio-aptHigh" || it.value == "vio-aptLow" }
            .mapNotNull { (key, _) ->
                val parts = key.split(",")
                val i = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val k = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                label(i, k)
            }
        val aptCombSummary = aptCombStats.summary()
        val logs = listOf(MirrorLog(tag = "AptPolish",
            message = "適切回数(apt)研磨: apt ${before.breakdown["apt"] ?: 0}->${bestRep.breakdown["apt"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                (if (applied == 0 && (before.breakdown["apt"] ?: 0) > 0) " [頭打ち=改善手なし]" else "") +
                (if (fixedNames.isNotEmpty()) " 対象: ${fixedNames.joinToString(", ")}" else "") +
                rejectCulprits.summary() +
                (if (stuckNames.isNotEmpty()) " 残存: ${stuckNames.joinToString(", ")}" else "") +
                (if (aptCombSummary.isNotEmpty()) " / $aptCombSummary" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks, rejectedCandidates = rejectedOut)
    }


    /**
     * [FairPolish・グループ内公平化(fair, 重み1)専用の研磨パス] ユーザー指示「c42/c42s以外にも
     * 『動かせるか』専用オペレータの欠如が無いか棚卸しする」で発見（棚卸し結果はユーザー承認済み）。
     * fair は群×担当ONシフトごとにメンバー回数の round(平均)からのL1偏差和で、apt(3.223.0)と
     * ほぼ同型の違反構造。しかし当時の平準化パス（同日2者スワップ＋**分散**指標での山登り）はチェーン救済が
     * 無く、交換相手が構造的に不在（希望固定/禁止連続/候補不足）だと頭打ちする、covO/c41/c41s/c42/c42s/apt と
     * 同型の穴だった（その平準化パス自体は 3.317.0 で実測寄与ゼロを確認して撤去済み）。AptPolish(3.223.0)と同一の3段構成
     * （①自己振替 ②同一グループ内相互交換 ③玉突きチェーン）をfair向けに移植する。
     *
     * fair の目標(tgt)は「その時点のグループ合計の round(平均)」で apt の固定目標と異なり、1日の
     * 付け替えごとに動く。手①②③はいずれも候補選定のスナップショット近似（各手を試す時点で
     * counts/tgt を再計算）でよく、最終的な採否は常に betterReport(実目的関数)が担うため、tgt の近似が
     * ズレても安全性は損なわれない（見逃しても isBetter が拒否するだけ・過大選定しても isBetter が
     * 拒否するだけ）。採否はisBetter(hard→weighted→total)keep-best＝退化不能。全手とも希望固定
     * (movable)・禁止連続(makesForbiddenRun)を事前ガード。
     */
    fun applyFairPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, seed: Long = 0xFA12L, quantitativeRangeEval: Boolean = false, combineExhaustPairs: Boolean = false, aptFairSoftTolerance: Boolean = false): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        val rng = Random(seed)
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        fun label(i: Int, k: Int) = "${state.staff.getOrNull(i)?.name ?: "#$i"} ${state.shifts.getOrNull(k)?.kigou ?: k.toString()}"
        val fixedNames = ArrayList<String>()
        // [汎用玉突き結合フレームワーク, 3.249.0] tryChainRelocate(手③)が単独では不採用だった候補を
        //   蓄積し末尾で束ねる。
        val combinable = ArrayList<CombinatorialRepair.Candidate>()
        val rejectCulprits = RejectCulpritStats()

        fun fairTarget(g: Int, k: Int, counts: Array<IntArray>): Int {
            val mem = p.groupMembers.getOrNull(g) ?: return 0
            if (mem.isEmpty()) return 0
            var sum = 0
            for (x in mem) sum += counts[x][k]
            return Math.round(sum.toDouble() / mem.size).toInt()
        }

        // [玉突きチェーンのavoid述語] 候補がfillShiftを1つ得ると、候補自身の群目標(スナップショット近似)
        //   からちょうど新規に乖離するか（既に乖離済みなら中立扱い＝対象外）。
        fun worsensOwnFair(staff: Int, fillShift: Int): Boolean {
            val g = p.sgrp.getOrNull(staff) ?: return false
            if (g !in p.bucket.indices || fillShift !in p.bucket[g]) return false
            val counts = countMatrix(p, work)
            val tgt = fairTarget(g, fillShift, counts)
            return counts[staff][fillShift] == tgt
        }

        // [厳密ピン保護] 本パスの全手は i(・相手)の回数を直接変える(apt/fair研磨の本質)ため、staffRange
        //   厳密ピン(lo==hi)を新たに崩す候補だけは不採用にする（keep-best/重みは不変・追加ガードのみ）。
        fun applyAndCheck(i: Int, j: Int, fromK: Int, toK: Int): Boolean {
            val workBefore = work.copy2D()
            work[i][j] = toK
            val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
            val pinBad = exactPinRegression(p, workBefore, work)
            if (pinBad && toleratedBetter(rep, bestRep, before, "fair", aptFairSoftTolerance)) pinBlocks.record(p, workBefore, work)
            if (toleratedBetter(rep, bestRep, before, "fair", aptFairSoftTolerance) && !pinBad) { bestRep = rep; applied++; return true }
            rejectCulprits.record(rep, bestRep, pinBad)
            work[i][j] = fromK
            return false
        }

        // 手①: 自身の中でfromK(過多)→toK(過少)への1日付け替え。被覆非悪化の日のみ候補にする。
        fun trySelfSwap(i: Int, fromK: Int, toK: Int): Boolean {
            for (j in 0 until p.T) {
                if (shouldStop()) return false
                if (work[i][j] != fromK || !movable(i, j)) continue
                if (p.makesForbiddenRun(work, i, j, toK)) continue
                var cntFrom = 0; var cntTo = 0
                for (s in 0 until p.S) { if (work[s][j] == fromK) cntFrom++; if (work[s][j] == toK) cntTo++ }
                if (p.covUCell(fromK, j, cntFrom - 1) > p.covUCell(fromK, j, cntFrom)) continue
                if (p.covUCell(toK, j, cntTo + 1) > p.covUCell(toK, j, cntTo)) continue
                if (applyAndCheck(i, j, fromK, toK)) return true
            }
            return false
        }

        // 手②: 同一グループ内で同日の2人の割当をまるごと入替（被覆総量保存＝安全）。
        fun tryMutualSwap(i: Int, i2: Int, sharedK: Int): Boolean {
            for (j in 0 until p.T) {
                if (shouldStop()) return false
                val a = work[i][j]; val b = work[i2][j]
                if (a != sharedK || b == sharedK) continue
                if (!movable(i, j) || !movable(i2, j)) continue
                if (!p.mayPlace(i, b) || !p.mayPlace(i2, a)) continue
                if (p.makesForbiddenRun(work, i, j, b) || p.makesForbiddenRun(work, i2, j, a)) continue
                val workBefore = work.copy2D()
                work[i][j] = b; work[i2][j] = a
                val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                val pinBad = exactPinRegression(p, workBefore, work)
                if (pinBad && toleratedBetter(rep, bestRep, before, "fair", aptFairSoftTolerance)) pinBlocks.record(p, workBefore, work)
                if (toleratedBetter(rep, bestRep, before, "fair", aptFairSoftTolerance) && !pinBad) { bestRep = rep; applied++; return true }
                rejectCulprits.record(rep, bestRep, pinBad)
                work[i][j] = a; work[i2][j] = b
            }
            return false
        }

        // 手③: RangePolish/AptPolish型の玉突きチェーン。
        fun tryChainRelocate(i: Int, j: Int, fromK: Int, toK: Int): Boolean {
            if (!movable(i, j) || p.makesForbiddenRun(work, i, j, toK)) return false
            var cnt = 0
            for (s in 0 until p.S) if (work[s][j] == fromK) cnt++
            val needsChain = p.covUCell(fromK, j, cnt - 1) > p.covUCell(fromK, j, cnt)
            val workBeforeRelocate = work.copy2D()
            work[i][j] = toK
            if (!needsChain) {
                val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                val pinBad = exactPinRegression(p, workBeforeRelocate, work)
                if (pinBad && toleratedBetter(rep, bestRep, before, "fair", aptFairSoftTolerance)) pinBlocks.record(p, workBeforeRelocate, work)
                if (toleratedBetter(rep, bestRep, before, "fair", aptFairSoftTolerance) && !pinBad) { bestRep = rep; applied++; return true }
                rejectCulprits.record(rep, bestRep, pinBad)
                work[i][j] = fromK
                combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j, toK)), "FairChain", label(i, fromK)))
                return false
            }
            val chain = findCovUChain(p, work, fromK, j, rng, exclude = i,
                rangeAvoid = { st, fk -> worsensOwnFair(st, fk) })
            if (chain == null) { work[i][j] = fromK; return false }
            val oldVals = IntArray(chain.size) { work[chain[it][0]][chain[it][1]] }
            chain.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
            val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
            val pinBad = exactPinRegression(p, workBeforeRelocate, work)
            if (pinBad && toleratedBetter(rep, bestRep, before, "fair", aptFairSoftTolerance)) pinBlocks.record(p, workBeforeRelocate, work)
            if (toleratedBetter(rep, bestRep, before, "fair", aptFairSoftTolerance) && !pinBad) { bestRep = rep; applied++; return true }
            rejectCulprits.record(rep, bestRep, pinBad)
            for (idx in chain.indices) work[chain[idx][0]][chain[idx][1]] = oldVals[idx]
            work[i][j] = fromK
            combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j, toK)) + chain, "FairChain", label(i, fromK)))
            return false
        }

        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
            val locs = rep0.distLocations["fair"].orEmpty()
            if (locs.isEmpty()) break
            val counts = countMatrix(p, work)
            val highTargets = ArrayList<Pair<Int, Int>>()   // (staff, shift) 過多
            val lowTargets = ArrayList<Pair<Int, Int>>()    // (staff, shift) 過少
            for (loc in locs) {
                val x = loc.getOrNull(0) ?: continue
                val k = loc.getOrNull(1) ?: continue
                if (x !in 0 until p.S || k !in 0 until p.K) continue
                val g = p.sgrp.getOrNull(x) ?: continue
                if (g !in p.bucket.indices) continue
                val tgt = fairTarget(g, k, counts)
                when {
                    counts[x][k] > tgt -> highTargets.add(x to k)
                    counts[x][k] < tgt -> lowTargets.add(x to k)
                }
            }
            if (highTargets.isEmpty() && lowTargets.isEmpty()) break

            for ((i, k) in highTargets) {
                if (shouldStop()) break
                var done = false
                // 手①: 自身の別シフトでfairLow(逆方向)のものへ振替（AptPolishと同型に統一。同一
                //   (fromK,toK)ペアで解消するまで反復。isBetterが認める限り繰り返して安全）。
                for (k2 in 0 until p.K) {
                    if (shouldStop()) break
                    if (k2 == k || !p.mayPlace(i, k2)) continue
                    if (lowTargets.none { it.first == i && it.second == k2 }) continue
                    while (trySelfSwap(i, k, k2)) { improved = true; done = true }
                }
                if (done) fixedNames.add(label(i, k))
                // 手②: 同一グループで逆方向(fairLow)の相手と相互交換。
                if (!done) {
                    for (i2 in 0 until p.S) {
                        if (done || shouldStop()) break
                        if (i2 == i || p.sgrp.getOrNull(i2) != p.sgrp.getOrNull(i)) continue
                        if (lowTargets.none { it.first == i2 && it.second == k }) continue
                        if (tryMutualSwap(i, i2, k)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                    }
                }
                // 手③: 玉突きチェーンで任意の担当可能シフトへ。
                if (!done) {
                    for (j in 0 until p.T) {
                        if (done || shouldStop()) break
                        if (work[i][j] != k) continue
                        for (alt in p.allowedShiftsForStaff(i)) {
                            if (done || shouldStop()) break
                            if (alt == k) continue
                            if (tryChainRelocate(i, j, k, alt)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                        }
                    }
                }
            }
            // 単独fairLow(自己振替/相互交換で解消しなかった残り)を玉突きチェーンで埋める。
            for ((i, k) in lowTargets) {
                if (shouldStop()) break
                if (!p.mayPlace(i, k)) continue
                var done = false
                for (j in 0 until p.T) {
                    if (done || shouldStop()) break
                    val oldK = work[i][j]
                    if (oldK == k || oldK !in 0 until p.K) continue
                    if (tryChainRelocate(i, j, oldK, k)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                }
            }
            pass++
            if (!improved) break
        }
        // [汎用玉突き結合フレームワーク, 3.249.0] stuckNames(distLocations由来)より前に実行する。
        //   結合でwork/bestRepが変わってもdistLocationsはbestRep自身から再取得するため自動整合。
        val rejectedOut = ArrayList<CombinatorialRepair.Candidate>()
        val fairCombStats = CombinatorialRepair.Stats()
        bestRep = CombinatorialRepair.combineAndApply(
            state, work, bestRep, combinable.asReversed(),
            { a, b -> toleratedBetter(a, b, before, "fair", aptFairSoftTolerance) },
            shouldStop = shouldStop, stats = fairCombStats, p = p, leftover = rejectedOut,
            exhaustPairs = combineExhaustPairs,
        )
        applied += fairCombStats.combosAccepted
        // [AptPolishと同型] work は毎手の成功時のみコミットしbestRepと同期を保つ（失敗時は必ず巻き戻し）
        //   ため、bestRep.distLocations がそのまま最終盤面の残存箇所＝再チェック不要。
        val stuckNames = bestRep.distLocations["fair"].orEmpty().mapNotNull { loc ->
            val i = loc.getOrNull(0) ?: return@mapNotNull null
            val k = loc.getOrNull(1) ?: return@mapNotNull null
            label(i, k)
        }
        val fairCombSummary = fairCombStats.summary()
        val logs = listOf(MirrorLog(tag = "FairPolish",
            message = "グループ内公平化(fair)研磨: fair ${before.breakdown["fair"] ?: 0}->${bestRep.breakdown["fair"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                (if (applied == 0 && (before.breakdown["fair"] ?: 0) > 0) " [頭打ち=改善手なし]" else "") +
                (if (fixedNames.isNotEmpty()) " 対象: ${fixedNames.joinToString(", ")}" else "") +
                rejectCulprits.summary() +
                (if (stuckNames.isNotEmpty()) " 残存: ${stuckNames.joinToString(", ")}" else "") +
                (if (fairCombSummary.isNotEmpty()) " / $fairCombSummary" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks, rejectedCandidates = rejectedOut)
    }


}
