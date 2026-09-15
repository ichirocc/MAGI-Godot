package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random

/**
 * c3系制約(cons3mn/cons3n/cons3+単一シフト連/cons3+複数シフトMUST・Wantパターン)専用の研磨4パス。
 * [V6HotfixPasses] から抽出（責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。
 * ロジックは一切変更していない。
 *
 * - [applyC3mnPolish]：回避パターン(cons3mn, SOFT)専用。1セル付け替え＋玉突き連鎖。
 * - [applyC3nPolish]：禁止連続(cons3n, HARD)専用。パターンがまたぐ全日を候補にし、
 *   `C3nRowScan` の枝刈りで checker 呼出前に正味 fire 減を確認する。
 * - [applyC3RunPolish]：単一シフト連(run-deficit)の不足専用。
 * - [applyC3PatternPolish]：複数シフトのMUST/Wantパターン専用。
 *
 * いずれも [C3RotationPolish]（本ファイルとは別技法の3者回転パス）とは独立。`CyclicSwapResult`は
 * [V6HotfixPasses] に残置される共有返り型のため、ここからは完全修飾で構築する。
 */
internal object C3FamilyPolish {
    fun applyC3mnPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, seed: Long = 0xC3AL, quantitativeRangeEval: Boolean = false, combineExhaustPairs: Boolean = false): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        if (p.cons3mn.isEmpty()) {
            return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, 0,
                listOf(MirrorLog(tag = "C3mnPolish", message = "cons3mnなし=スキップ")))
        }
        val rng = Random(seed)
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        // [汎用玉突き結合フレームワーク, 3.249.0] 単独では不採用だった候補を蓄積し末尾で束ねる。
        val combinable = ArrayList<CombinatorialRepair.Candidate>()
        val rejectCulprits = RejectCulpritStats()
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
            val anchors = ArrayList<Pair<Int, Int>>()
            for ((key, fams) in rep0.cellFamilies) {
                if ("vio-c3mn" !in fams) continue
                val parts = key.split(",")
                val i = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
                anchors.add(i to j)
            }
            if (anchors.isEmpty()) break
            for ((i, j) in anchors) {
                if (shouldStop()) break
                if (!movable(i, j)) continue
                val curK = work[i][j]
                if (curK !in 0 until p.K) continue
                var done = false
                for (alt in p.allowedShiftsForStaff(i)) {
                    if (done || shouldStop()) break
                    if (alt == curK) continue
                    if (p.makesForbiddenRun(work, i, j, alt)) continue
                    var cnt = 0
                    for (s in 0 until p.S) if (work[s][j] == curK) cnt++
                    val needsChain = p.covUCell(curK, j, cnt - 1) > p.covUCell(curK, j, cnt)
                    // [監査で発見・3.270.0] isBetter は hard→weightedScore→total の辞書式のため、raw
                    //   total 改善だけでweightedScoreが悪化する組合せ(厳密ピン破り)がありうる。同型の
                    //   全パスに既に適用済みの exactPinRegression ガードをここにも追加（3.256.0 retrofit漏れ）。
                    val workBeforeMove = work.copy2D()
                    work[i][j] = alt
                    if (!needsChain) {
                        val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                        val pinBad = exactPinRegression(p, workBeforeMove, work)
                        if (pinBad && betterReport(rep, bestRep)) pinBlocks.record(p, workBeforeMove, work)
                        if (betterReport(rep, bestRep) && !pinBad) { bestRep = rep; applied++; improved = true; done = true }
                        else {
                            rejectCulprits.record(rep, bestRep, pinBad)
                            val hint = "${state.staff.getOrNull(i)?.name ?: "#$i"}(${state.shifts.getOrNull(curK)?.kigou ?: curK})"
                            combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j, alt)), "C3mnAlt", hint))
                            work[i][j] = curK
                        }
                        continue
                    }
                    // [玉突き連鎖] i の離脱で curK の被覆が悪化する → 玉突きで埋め直す（盤面不変・巻き戻し可能）。
                    val chain = findCovUChain(p, work, curK, j, rng, exclude = i,
                        rangeAvoid = { st, fk -> exceedsOwnRangeHi(p, work, st, fk) })
                    if (chain == null) { work[i][j] = curK; continue }
                    val oldVals = IntArray(chain.size) { work[chain[it][0]][chain[it][1]] }
                    chain.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
                    val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                    val pinBad = exactPinRegression(p, workBeforeMove, work)
                    if (pinBad && betterReport(rep, bestRep)) pinBlocks.record(p, workBeforeMove, work)
                    if (betterReport(rep, bestRep) && !pinBad) { bestRep = rep; applied++; improved = true; done = true }
                    else {
                        rejectCulprits.record(rep, bestRep, pinBad)
                        for (idx in chain.indices) work[chain[idx][0]][chain[idx][1]] = oldVals[idx]
                        work[i][j] = curK
                        val hint = "${state.staff.getOrNull(i)?.name ?: "#$i"}(${state.shifts.getOrNull(curK)?.kigou ?: curK})"
                        combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j, alt)) + chain, "C3mnAlt", hint))
                    }
                }
            }
            pass++
            if (!improved) break
        }
        // [汎用玉突き結合フレームワーク, 3.249.0] stuckNames より前に実行し、結合で解消した箇所が
        //   「残存」に残らないようにする。
        val rejectedOut = ArrayList<CombinatorialRepair.Candidate>()
        val c3mnCombStats = CombinatorialRepair.Stats()
        bestRep = CombinatorialRepair.combineAndApply(
            state, work, bestRep, combinable.asReversed(), ::betterReport, shouldStop = shouldStop, stats = c3mnCombStats, p = p, leftover = rejectedOut,
            exhaustPairs = combineExhaustPairs,
        )
        applied += c3mnCombStats.combosAccepted
        val stuckNames = stuckStaffNames(state, bestRep.cellFamilies, "vio-c3mn")
        val c3mnCombSummary = c3mnCombStats.summary()
        val logs = listOf(MirrorLog(tag = "C3mnPolish",
            message = "回避パターン(c3mn)研磨: c3mn ${before.breakdown["c3mn"] ?: 0}->${bestRep.breakdown["c3mn"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                (if (applied == 0 && (before.breakdown["c3mn"] ?: 0) > 0) " [頭打ち=改善手なし]" else "") +
                rejectCulprits.summary() +
                (if (stuckNames.isNotEmpty()) " 残存: ${stuckNames.joinToString(", ")}" else "") +
                (if (c3mnCombSummary.isNotEmpty()) " / $c3mnCombSummary" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks, rejectedCandidates = rejectedOut)
    }


    /**
     * [C3nPolish・禁止連続(c3n, HARD重み7000)専用の研磨パス] ユーザー指示「C3nは前後日と当日も他の勤務
     * シフトに変更できるようにアルゴリズムを賢く昇華する」（3.303.0・AskUserQuestion で「両方＝範囲拡張＋
     * 当日も可変」を選択）。
     *
     * C3mnPolish(3.214.0) と同型だが、決定的に違うのが**候補セルの取り方**:
     * - C3mnPolish は違反セル (i,j) **その1セルだけ**を別シフトへ変える。
     * - 本パスは違反パターンが**またぐ全日**（`Dﾃ→休→A4` なら3日ぶん全部＝前日・当日・翌日）を候補にする。
     *   禁止連続は「並び」なので、どの1日を崩してもパターンは壊れる。にもかかわらず既存機構は
     *   当日1セルか隣接1日しか触っておらず、3連の先頭に構造的に届いていなかった。
     *
     * 候補数は (パターン長 × 担当可能シフト数) 倍に増えるため、フル checker を呼ぶ前に
     * `C3nRowScan` で「その手で c3n の正味 fire が実際に減るか」を先に判定して枝刈りする。
     * 64日以内は popcount、長期日程は同じ意味のスカラー走査へ自動退避する。
     * 最終採否は checker + isBetter + exactPinRegression が担保する。
     * 崩した先で被覆が悪化するなら `findCovUChain` の玉突き連鎖で埋め直すのは既存パスと同じ。
     */
    fun applyC3nPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, seed: Long = 0xC3EL, quantitativeRangeEval: Boolean = false, combineExhaustPairs: Boolean = false): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        if (p.cons3n.isEmpty()) {
            return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, 0,
                listOf(MirrorLog(tag = "C3nPolish", message = "cons3nなし=スキップ")))
        }
        val rng = Random(seed)
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        val combinable = ArrayList<CombinatorialRepair.Candidate>()
        val rejectCulprits = RejectCulpritStats()
        var screened = 0          // C3n枝刈りで checker を呼ばずに落とした候補数
        var evaluated = 0         // 実際に checker を呼んだ候補数
        var patternDays = 0       // 候補にしたセルの延べ数（当日1セルに留まらないことの実測）
        // [3.356.0/実機ログ起因] 「候補日延べ4 正式評価0 C3n枝刈り0」だけでは、なぜ1件も評価まで
        //   進まなかったのかが読めなかった（実データではアリフの2セルとも本人希望で固定されていた）。
        //   候補日から外れた理由を数える。
        var blockedWish = 0       // 希望で固定されていて動かせなかった日
        var blockedCell = 0       // 割当が範囲外（-1 等）で対象外だった日
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
            // アンカー = c3n 違反セル。cellFamilies を使うのは violations(最重1クラス)だと同一セルに
            //   より重い族が乗ったとき取りこぼすため（3.205.0 の anchor-shadowing と同じ理由）。
            val anchors = ArrayList<Pair<Int, Int>>()
            for ((key, fams) in rep0.cellFamilies) {
                if ("vio-c3n" !in fams) continue
                val parts = key.split(",")
                val i = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
                anchors.add(i to j)
            }
            if (anchors.isEmpty()) break
            for ((i, j) in anchors) {
                if (shouldStop()) break
                if (i !in 0 until p.S || j !in 0 until p.T) continue
                var done = false
                // [当日も可変＋範囲拡張] 違反パターンがまたぐ全日を候補にする（j 自身を含む）。
                val c3nScan = C3nRowScan(p, work[i])
                val firesNow = c3nScan.fires()
                if (firesNow == 0) continue
                val candidateDays = c3nScan.coveringDays(j)
                val days = ArrayList<Int>(candidateDays.size)
                for (day in candidateDays) days.add(day)
                if (days.isEmpty()) days.add(j)
                days.sortBy { kotlin.math.abs(it - j) }   // 当日に近い日から（波及が小さい順）
                patternDays += days.size
                for (j2 in days) {
                    if (done || shouldStop()) break
                    if (!movable(i, j2)) { blockedWish++; continue }
                    val curK = work[i][j2]
                    if (curK !in 0 until p.K) { blockedCell++; continue }
                    for (alt in p.allowedShiftsForStaff(i)) {
                        if (done || shouldStop()) break
                        if (alt == curK) continue
                        // [C3n枝刈り] この1手で c3n の正味 fire が減らないなら checker を呼ばない。
                        //   減らない手は hard が下がらず、この HARD 族専用パスとしては意味がない。
                        if (c3nScan.firesAfterSet(j2, alt) >= firesNow) { screened++; continue }
                        var cnt = 0
                        for (s in 0 until p.S) if (work[s][j2] == curK) cnt++
                        val needsChain = p.covUCell(curK, j2, cnt - 1) > p.covUCell(curK, j2, cnt)
                        val workBeforeMove = work.copy2D()
                        work[i][j2] = alt
                        val hint = "${state.staff.getOrNull(i)?.name ?: "#$i"}(${state.shifts.getOrNull(curK)?.kigou ?: curK})"
                        if (!needsChain) {
                            evaluated++
                            val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                            val pinBad = exactPinRegression(p, workBeforeMove, work)
                            if (pinBad && betterReport(rep, bestRep)) pinBlocks.record(p, workBeforeMove, work)
                            if (betterReport(rep, bestRep) && !pinBad) {
                                bestRep = rep; applied++; improved = true; done = true
                            } else {
                                rejectCulprits.record(rep, bestRep, pinBad)
                                combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j2, alt)), "C3nAlt", hint))
                                work[i][j2] = curK
                            }
                            continue
                        }
                        // [玉突き連鎖] 崩した側の被覆が欠けるなら埋め直す（盤面不変・巻き戻し可能）。
                        val chain = findCovUChain(p, work, curK, j2, rng, exclude = i,
                            rangeAvoid = { st, fk -> exceedsOwnRangeHi(p, work, st, fk) })
                        if (chain == null) { work[i][j2] = curK; continue }
                        val oldVals = IntArray(chain.size) { work[chain[it][0]][chain[it][1]] }
                        chain.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
                        evaluated++
                        val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                        val pinBad = exactPinRegression(p, workBeforeMove, work)
                        if (pinBad && betterReport(rep, bestRep)) pinBlocks.record(p, workBeforeMove, work)
                        if (betterReport(rep, bestRep) && !pinBad) {
                            bestRep = rep; applied++; improved = true; done = true
                        } else {
                            rejectCulprits.record(rep, bestRep, pinBad)
                            for (idx in chain.indices) work[chain[idx][0]][chain[idx][1]] = oldVals[idx]
                            work[i][j2] = curK
                            combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j2, alt)) + chain, "C3nAlt", hint))
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val rejectedOut = ArrayList<CombinatorialRepair.Candidate>()
        val c3nCombStats = CombinatorialRepair.Stats()
        bestRep = CombinatorialRepair.combineAndApply(
            state, work, bestRep, combinable.asReversed(), ::betterReport, shouldStop = shouldStop, stats = c3nCombStats, p = p, leftover = rejectedOut,
            exhaustPairs = combineExhaustPairs,
        )
        applied += c3nCombStats.combosAccepted
        val stuckNames = stuckStaffNames(state, bestRep.cellFamilies, "vio-c3n")
        val c3nCombSummary = c3nCombStats.summary()
        val logs = listOf(MirrorLog(tag = "C3nPolish",
            message = "禁止連続(c3n)研磨: c3n ${before.breakdown["c3n"] ?: 0}->${bestRep.breakdown["c3n"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                " 候補日延べ$patternDays(パターン全域・当日含む) 正式評価$evaluated C3n枝刈り$screened" +
                (if (blockedWish > 0) " 希望固定で候補外${blockedWish}日" else "") +
                (if (blockedCell > 0) " 割当が範囲外${blockedCell}日" else "") +
                (if (applied == 0 && (before.breakdown["c3n"] ?: 0) > 0) " [頭打ち=改善手なし]" else "") +
                rejectCulprits.summary() +
                (if (stuckNames.isNotEmpty()) " 残存: ${stuckNames.joinToString(", ")}" else "") +
                (if (c3nCombSummary.isNotEmpty()) " / $c3nCombSummary" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks, rejectedCandidates = rejectedOut)
    }


    /**
     * [C3RunPolish・玉突き連鎖の横展開その3] cons3/cons3m のうち単一シフト連(run-deficit モデル,
     * HF507/C3Run.rowDeficit)専用の研磨パス。C3mnPolish(3.214.0)/RangePolish(3.215.0)と同じ監査
     * （ユーザー指摘「他の制約は大丈夫ですか?」）で発見: 既存のC3Polish(2者ブロック交換)/C3Rotate
     * (3者回転)は「相手が現在の自分のシフトを担当可能」という相互条件を要求し、単一シフト連の
     * run不足（既存runを隣接日へ伸ばせば直る局面）に対しては交換相手が構造的に存在しないと解消できない。
     *
     * スコープ限定（安全側）: 対象は`C3Run.isSingleShiftSeq`が真の規則のみ（cons3/cons3mの大半を占める
     * 典型ケース）。複数シフトのMUST/Wantパターン(非single-shift)は既存のC3Polish/C3Rotateのまま
     * 対象外＝挙動不変（cellFamiliesの"vio-c3"/"vio-c3m"キーは両方のサブケースで共有されるため、
     * アンカー自体は両方拾うが、対応するルールが見つからない/runが既に規定長以上のセルは単に
     * スキップされ何もしない）。
     *
     * アンカー: `report.cellFamilies`から"vio-c3"/"vio-c3m"を含むセル。run-deficitモデルはrun先頭
     * セルをマークするため、そこから実際の run 境界(runStart..runEnd)を再走査し、隣接日(runStart-1
     * または runEnd+1)を該当シフトへ拡張する。拡張元シフトの被覆が悪化する場合は`findCovUChain`
     * （C1Polish/C3mnPolish/RangePolishと同一パターン）で玉突き修復。採否はisBetter keep-best＝退化不能。
     */
    fun applyC3RunPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, seed: Long = 0xC3A2L, quantitativeRangeEval: Boolean = false): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        data class RunRule(val k: Int, val len: Int)
        val rules = ArrayList<RunRule>()
        for (c in p.cons3) if (C3Run.isSingleShiftSeq(c.seq)) rules.add(RunRule(c.seq[0], c.seq.size))
        for (c in p.cons3m) if (C3Run.isSingleShiftSeq(c.seq)) rules.add(RunRule(c.seq[0], c.seq.size))
        if (rules.isEmpty()) {
            return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, 0,
                listOf(MirrorLog(tag = "C3RunPolish", message = "対象規則(単一シフト連)なし=スキップ")))
        }
        val rng = Random(seed)
        val rejectCulprits = RejectCulpritStats()
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)

        fun tryExtend(i: Int, extDay: Int, fromK: Int, toK: Int): Boolean {
            if (!movable(i, extDay) || p.makesForbiddenRun(work, i, extDay, toK)) return false
            var cnt = 0
            for (s in 0 until p.S) if (work[s][extDay] == fromK) cnt++
            val needsChain = p.covUCell(fromK, extDay, cnt - 1) > p.covUCell(fromK, extDay, cnt)
            // [厳密ピン保護] i の fromK→toK 直接付替え(+チェーン)は自身の回数を変える唯一の手のため、
            //   staffRange厳密ピン(lo==hi)を崩す候補は不採用にする（keep-best/重みは不変）。
            val workBeforeExtend = work.copy2D()
            work[i][extDay] = toK
            if (!needsChain) {
                val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                val pinBad = exactPinRegression(p, workBeforeExtend, work)
                if (pinBad && betterReport(rep, bestRep)) pinBlocks.record(p, workBeforeExtend, work)
                if (betterReport(rep, bestRep) && !pinBad) { bestRep = rep; applied++; return true }
                rejectCulprits.record(rep, bestRep, pinBad)
                work[i][extDay] = fromK
                return false
            }
            val chain = findCovUChain(p, work, fromK, extDay, rng, exclude = i,
                rangeAvoid = { st, fk -> exceedsOwnRangeHi(p, work, st, fk) })
            if (chain == null) { work[i][extDay] = fromK; return false }
            val oldVals = IntArray(chain.size) { work[chain[it][0]][chain[it][1]] }
            chain.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
            val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
            val pinBad = exactPinRegression(p, workBeforeExtend, work)
            if (pinBad && betterReport(rep, bestRep)) pinBlocks.record(p, workBeforeExtend, work)
            if (betterReport(rep, bestRep) && !pinBad) { bestRep = rep; applied++; return true }
            rejectCulprits.record(rep, bestRep, pinBad)
            for (idx in chain.indices) work[chain[idx][0]][chain[idx][1]] = oldVals[idx]
            work[i][extDay] = fromK
            return false
        }

        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
            val anchors = ArrayList<Pair<Int, Int>>()
            for ((key, fams) in rep0.cellFamilies) {
                if ("vio-c3" !in fams && "vio-c3m" !in fams) continue
                val parts = key.split(",")
                val i = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
                anchors.add(i to j)
            }
            if (anchors.isEmpty()) break
            for ((i, j) in anchors) {
                if (shouldStop()) break
                val k = work[i][j]
                if (k !in 0 until p.K) continue
                val rule = rules.firstOrNull { it.k == k } ?: continue
                var s0 = j
                while (s0 - 1 >= 0 && work[i][s0 - 1] == k) s0--
                var e0 = j
                while (e0 + 1 < p.T && work[i][e0 + 1] == k) e0++
                if (e0 - s0 + 1 >= rule.len) continue   // 既に規定長以上=スキップ(古いアンカー)
                var done = false
                for (extDay in listOfNotNull((s0 - 1).takeIf { it >= 0 }, (e0 + 1).takeIf { it < p.T })) {
                    if (done || shouldStop()) break
                    val oldK = work[i][extDay]
                    if (oldK == k || oldK !in 0 until p.K) continue
                    if (tryExtend(i, extDay, oldK, k)) { improved = true; done = true }
                }
            }
            pass++
            if (!improved) break
        }
        val stuckNames = (stuckStaffNames(state, bestRep.cellFamilies, "vio-c3") +
            stuckStaffNames(state, bestRep.cellFamilies, "vio-c3m")).distinct()
        val logs = listOf(MirrorLog(tag = "C3RunPolish",
            message = "連続規則(c3/c3m単一シフト連)玉突き研磨: c3 ${before.breakdown["c3"] ?: 0}->${bestRep.breakdown["c3"] ?: 0} / c3m ${before.breakdown["c3m"] ?: 0}->${bestRep.breakdown["c3m"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                (if (applied == 0 && ((before.breakdown["c3"] ?: 0) + (before.breakdown["c3m"] ?: 0)) > 0) " [頭打ち=改善手なし]" else "") +
                rejectCulprits.summary() +
                (if (stuckNames.isNotEmpty()) " 残存: ${stuckNames.joinToString(", ")}" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks)
    }


    /**
     * [C3PatternPolish・玉突き連鎖の横展開その4] cons3/cons3m のうち複数シフトMUST/Wantパターン
     * （非single-shift、`C3Run.isSingleShiftSeq`が偽の規則）専用の研磨パス。ユーザー指示
     * 「c42/c42s以外にも『動かせるか』専用オペレータの欠如が無いか棚卸しする」で発見（棚卸し結果は
     * ユーザー承認済み）。3.216.0(C3RunPolish)は単一シフト連(run-deficitモデル)のみを対象とし、
     * 複数シフトパターンは「既存機構(2者ブロック交換/3者回転)のまま対象外（安全側・挙動不変）」と
     * 明記して見送っていた。既存の2-3者交換/回転は「相手が対になるパターンを持つ」という相互条件を
     * 要求し、交換相手が構造的に存在しない（誰も対になる並びを持たない）局面では解消できない、
     * c41/c42/covO/apt/fair と同型の穴。
     *
     * `MirrorCore.checkC3Family` の非forbidden複数シフト分岐は「schedule[i][j]==seq[0] かつ
     * 残り(d-1)日が全部一致しない(z<d-1)」を1件の違反として窓先頭セル(i,j)へ計上する。このモデル
     * では「日jのseq[0]を別シフトへ変え、パターンの起点自体を崩す」だけで当該違反インスタンスが
     * 消える（残り日が完成するよう複数日を同時に組み替える方向＝パターン完成は、複数日の依存関係が
     * 絡み正しさの保証が難しいため意図的にスコープ外＝既存の2-3者交換/回転パスに委ねる。見送っても
     * 既存機構が担当を続けるだけ＝安全側）。C3mnPolish(3.214.0)と同一の「1セル付け替え＋
     * findCovUChain玉突き」パターンをそのまま適用する。採否はisBetter(hard→weighted→total)
     * keep-best＝退化不能。
     */
    fun applyC3PatternPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, seed: Long = 0xC3B4L, quantitativeRangeEval: Boolean = false): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        val rules = ArrayList<C3>()
        for (c in p.cons3) if (c.seq.size > 1 && !C3Run.isSingleShiftSeq(c.seq)) rules.add(c)
        for (c in p.cons3m) if (c.seq.size > 1 && !C3Run.isSingleShiftSeq(c.seq)) rules.add(c)
        if (rules.isEmpty()) {
            return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, 0,
                listOf(MirrorLog(tag = "C3PatternPolish", message = "複数シフトc3/c3mパターンなし=スキップ")))
        }
        val rng = Random(seed)
        val rejectCulprits = RejectCulpritStats()
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)

        // アンカー: 各規則(seq,d)で「schedule[i][j]==seq[0]かつ残りd-1日が全一致しない(z<d-1)」窓の
        //   先頭(i,j,seq[0])。checkC3Familyの非forbidden複数シフト分岐と同一の意味論。
        fun collectAnchors(): List<Triple<Int, Int, Int>> {
            val out = ArrayList<Triple<Int, Int, Int>>()
            for (c in rules) {
                val seq = c.seq; val d = seq.size
                if (d > p.T) continue
                for (i in 0 until p.S) {
                    var j = 0
                    while (j <= p.T - d) {
                        if (work[i][j] == seq[0]) {
                            var z = 0
                            for (l in 1 until d) if (work[i][j + l] == seq[l]) z++
                            if (z < d - 1) out.add(Triple(i, j, seq[0]))
                        }
                        j++
                    }
                }
            }
            return out
        }
        val initialCount = collectAnchors().size

        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            val anchors = collectAnchors()
            if (anchors.isEmpty()) break
            for ((i, j, curK) in anchors) {
                if (shouldStop()) break
                if (!movable(i, j) || work[i][j] != curK) continue
                var done = false
                for (alt in p.allowedShiftsForStaff(i)) {
                    if (done || shouldStop()) break
                    if (alt == curK) continue
                    if (p.makesForbiddenRun(work, i, j, alt)) continue
                    var cnt = 0
                    for (s in 0 until p.S) if (work[s][j] == curK) cnt++
                    val needsChain = p.covUCell(curK, j, cnt - 1) > p.covUCell(curK, j, cnt)
                    // [厳密ピン保護] i(・玉突き相手)の回数変更がstaffRange厳密ピン(lo==hi)を新たに崩す
                    //   候補は不採用にする（keep-best/重みは不変・追加ガードのみ）。
                    val workBeforePattern = work.copy2D()
                    work[i][j] = alt
                    if (!needsChain) {
                        val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                        if (adoptionGate(p, workBeforePattern, work, rep, bestRep, pinBlocks).accepted) { bestRep = rep; applied++; improved = true; done = true }
                        else work[i][j] = curK
                        continue
                    }
                    // [玉突き連鎖] i の離脱で curK の被覆が悪化する → 玉突きで埋め直す（盤面不変・巻き戻し可能）。
                    val chain = findCovUChain(p, work, curK, j, rng, exclude = i,
                        rangeAvoid = { st, fk -> exceedsOwnRangeHi(p, work, st, fk) })
                    if (chain == null) { work[i][j] = curK; continue }
                    val oldVals = IntArray(chain.size) { work[chain[it][0]][chain[it][1]] }
                    chain.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
                    val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                    val pinBad = exactPinRegression(p, workBeforePattern, work)
                    if (pinBad && betterReport(rep, bestRep)) pinBlocks.record(p, workBeforePattern, work)
                    if (betterReport(rep, bestRep) && !pinBad) { bestRep = rep; applied++; improved = true; done = true }
                    else {
                        rejectCulprits.record(rep, bestRep, pinBad)
                        for (idx in chain.indices) work[chain[idx][0]][chain[idx][1]] = oldVals[idx]
                        work[i][j] = curK
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val remaining = collectAnchors()
        val stuckNames = remaining.map { (i, _, _) -> state.staff.getOrNull(i)?.name ?: "#$i" }.distinct()
        val logs = listOf(MirrorLog(tag = "C3PatternPolish",
            message = "連続規則(c3/c3m複数シフトパターン)玉突き研磨: 窓不成立 ${initialCount}->${remaining.size}" +
                " / c3 ${before.breakdown["c3"] ?: 0}->${bestRep.breakdown["c3"] ?: 0}" +
                " / c3m ${before.breakdown["c3m"] ?: 0}->${bestRep.breakdown["c3m"] ?: 0}" +
                " / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                (if (applied == 0 && initialCount > 0) " [頭打ち=改善手なし]" else "") +
                rejectCulprits.summary() +
                (if (stuckNames.isNotEmpty()) " 残存: ${stuckNames.joinToString(", ")}" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks)
    }


}
