package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * 違反連結成分修復（自律改善ループ Iteration 2 の第一弾。設計はユーザー提示）。
 *
 * 各研磨パスが単独では不採用にした候補（[CombinatorialRepair.Candidate]）を後処理チェーン全体で共有し、
 * 違反（セル・回数・人数）を起点に「その違反を触る候補（主）＋主と職員か日を共有する候補（助）」の集合を作り、
 * その中で 2〜[Params.maxK] 手のトランザクションをビームで探す（職員か日の共有だけで連結すると実データでは全候補が
 * 1 つの成分になるので、起点＝違反で範囲を切る）。途中の手は一時悪化を許し（[DeltaEvaluator] の推定値で枝を絞り、
 * 厳密ピンを新たに崩す枝は推定段階で落とす）、commit は正式チェッカーの [betterReport]（HARD→weighted→total）と
 * 厳密ピン検査で決める＝推定の誤差で退化しない。例外時は試行手を必ず巻き戻す。
 */
object ViolationComponentRepair {

    /** 計測専用の品質ベクトル（採用判定には使わない。採用基準の変更は独立した A/B 項目）。 */
    data class QualityVector(
        val hardCount: Int,
        val hardWeighted: Double,
        val softWeighted: Double,
        val wishMisses: Int,
        val changedCells: Int,
    ) : Comparable<QualityVector> {
        override fun compareTo(other: QualityVector): Int {
            if (hardCount != other.hardCount) return hardCount.compareTo(other.hardCount)
            if (hardWeighted != other.hardWeighted) return hardWeighted.compareTo(other.hardWeighted)
            if (softWeighted != other.softWeighted) return softWeighted.compareTo(other.softWeighted)
            if (wishMisses != other.wishMisses) return wishMisses.compareTo(other.wishMisses)
            return changedCells.compareTo(other.changedCells)
        }

        companion object {
            fun of(report: ViolationReport, changedCells: Int): QualityVector {
                var hardW = 0.0; var softW = 0.0
                for ((k, v) in report.breakdown) {
                    val c = v * MirrorKeys.weightOf(k)
                    if (k in MirrorKeys.hard) hardW += c else softW += c
                }
                return QualityVector(report.hard, hardW, softW, report.breakdown["pref"] ?: 0, changedCells)
            }
        }
    }

    data class Params(
        /** 1 トランザクションに束ねる候補数の上限。 */
        val maxK: Int = 4,
        /** ビームの幅（推定値で残す部分トランザクションの数）。 */
        val beamWidth: Int = 8,
        /** 正式チェッカー呼出の上限（1 回の呼出全体）。 */
        val maxEvaluations: Int = 64,
        /** 推定（DeltaEvaluator）の上限。 */
        val maxEstimates: Int = 6_000,
        /** 起点にする違反の上限（HARD→回数→人数の順）。 */
        val maxAnchors: Int = 24,
        /** 1 起点あたり探索に入れる候補数（主候補を先に、助候補を後に詰める）。 */
        val maxPatchesPerAnchor: Int = 40,
        /** [Iteration 4] 起点から直接候補を作る（拒否候補に依存しない）。起点ごと・全体の上限。 */
        val generateFromAnchors: Boolean = true,
        val maxGeneratedPerAnchor: Int = 40,
        val maxGenerated: Int = 400,
        /** [Iteration 5] セル違反の起点で作る同長区間交換の最大長（制約の窓長から決めた半径をこれで頭打ち）。 */
        val maxWindowLength: Int = 7,
        /** [3.510.5/測定中] 設計 v3: 起点の族の 重み×現在量×係数 を SOFT 一時負債の予算にし、HARD 悪化と予算超えの枝をビームから外す（[ConstraintRepairInference]）。既定 OFF。 */
        val debtExploration: Boolean = false,
        val temporaryDebtFactor: Double = 2.0,
        /** [測定中/二車線ビーム] debtExploration 時、ビーム幅のうち何枠を負債候補(est>baseEst)専用に予約するか。
         *  0=予約なし（従来どおり est 昇順の単一トリムで、負債候補は非負債候補に埋もれて落選し得る）。
         *  iter11(ConstraintRepairInference)が無差別だった一因として、explorable()は予算内かどうかしか見ず、
         *  その後のビームトリムは est 昇順の単一ソートのため「悪化しない候補が8件以上あれば負債候補は
         *  一切生き残れない」という構造上の飢餓が起きうる（未検証の仮説。debtExploration=false では無効）。 */
        val debtLaneSlots: Int = 0,
        /** [測定中] セグメント内（解けるHARD／回数+SOFTセル／blocked）を 件数×重み×族の成功率 で降順に並べ替える。既定 OFF。 */
        val familyPriorityScoring: Boolean = false,
        /** [測定中/bestOfK] 1 周あたり起点を最大何件 search() して結果を比較するか。既定 1=従来どおり
         *  「最初に成功した起点をすぐ採用して次周へ」（起点の並び順に結果が依存する）。2 以上にすると、
         *  複数の起点候補を実際に search() し、betterReport で客観的に最良の一つだけを採用する
         *  （順序非依存になる代わり search() 呼出が増える）。search() は差分を当てて戻すだけで
         *  盤面(work)/delta を変更しない（副作用なし）ので複数回呼んでも安全。
         *  iter17(familyPriorityScore) が無差別だった一因＝「並べ替えても greedy な早期採用のままでは
         *  順序依存のノイズに埋もれる」という未検証の仮説の検証用（familyPriorityScoring=false でも独立して使える）。 */
        val bestOfK: Int = 1,
    )

    /** 盤面差分。`ops` は [職員, 日, 新シフト] の並び（[CombinatorialRepair.Candidate.ops] と同じ形）。 */
    class Patch(val ops: List<IntArray>, val mechanism: String, val hint: String) {
        val staff: IntArray = ops.map { it[0] }.distinct().sorted().toIntArray()
        val days: IntArray = ops.map { it[1] }.distinct().sorted().toIntArray()
        val cellKeys: LongArray = ops.map { it[0] * 100_000L + it[1] }.distinct().sorted().toLongArray()
        fun overlaps(o: Patch): Boolean {
            var a = 0; var b = 0
            while (a < cellKeys.size && b < o.cellKeys.size) {
                val d = cellKeys[a].compareTo(o.cellKeys[b])
                if (d == 0) return true
                if (d < 0) a++ else b++
            }
            return false
        }
        val signature: String = ops.joinToString(";") { "${it[0]},${it[1]},${it[2]}" }
    }

    /** 探索フロンティアのノード（候補 [ids] の組合せと推定値 [est]）。 */
    internal class Node(val ids: IntArray, val est: Long)
    internal val nodeOrder = Comparator<Node> { a, b ->
        val c = a.est.compareTo(b.est); if (c != 0) return@Comparator c
        val n = minOf(a.ids.size, b.ids.size)
        for (t in 0 until n) { val d = a.ids[t].compareTo(b.ids[t]); if (d != 0) return@Comparator d }
        a.ids.size.compareTo(b.ids.size)
    }

    /** [測定中/二車線ビーム] debtExploration && debtLaneSlots>0 のときだけ、負債候補(est>baseEst)を
     *  非負債候補と別枠でトリムする（互いを競わせない）。それ以外は従来どおりの単一トリム。
     *  負債枠は「予約」であって「専有」ではない＝負債候補が debtCap 未満（0 件含む）のときは、
     *  余った枠を非負債候補へ返す（先に負債候補を選び、残り容量で非負債候補を選ぶ）。
     *  戻り値は最後に nodeOrder で再整列する＝負債候補が混ざっても展開順（est 昇順）は従来と同じ意味を保つ
     *  （search() の展開ループは評価予算切れで frontier の先頭から順に打ち切るため、順序は結果に影響する）。 */
    internal fun trimFrontier(candidates: List<Node>, baseEst: Long, params: Params): List<Node> {
        if (!params.debtExploration || params.debtLaneSlots <= 0) return candidates.sortedWith(nodeOrder).take(params.beamWidth)
        val (debtNodes, normalNodes) = candidates.partition { it.est > baseEst }
        val debtCap = params.debtLaneSlots.coerceAtMost(params.beamWidth)
        val selectedDebt = debtNodes.sortedWith(nodeOrder).take(debtCap)
        val selectedNormal = normalNodes.sortedWith(nodeOrder).take(params.beamWidth - selectedDebt.size)
        return (selectedNormal + selectedDebt).sortedWith(nodeOrder)
    }

    /** 違反の起点。セル違反は (staff, day)、回数違反は staff、人数違反は day を範囲に持つ。 */
    internal class Anchor(val hard: Boolean, val family: String, val staff: Int, val day: Int, val shift: Int = -1) {
        fun touches(pt: Patch): Boolean = when {
            staff >= 0 && day >= 0 -> pt.cellKeys.contains(staff * 100_000L + day)
            staff >= 0 -> pt.staff.contains(staff)
            else -> pt.days.contains(day)
        }
        val label: String get() = family + (if (staff >= 0) " 職員$staff" else "") + (if (day >= 0) " ${day + 1}日" else "")
    }

    private fun parseKey(key: String): Pair<Int, Int>? {
        val c = key.indexOf(','); if (c <= 0) return null
        val a = key.substring(0, c).toIntOrNull() ?: return null
        val b = key.substring(c + 1).toIntOrNull() ?: return null
        return a to b
    }

    /**
     * 起点の並び: HARD のセル・人数違反 → 回数違反 → SOFT のセル・人数違反（同種はキー順で決定的）。
     * 構造的に埋められない人員不足 [infeasible] は末尾＝起点の上限（maxAnchors）を「解ける HARD」に使う。
     * [familyScore] を渡すとセグメント内（solvable/counts/soft/blocked それぞれの内部）だけを降順に並べ替える
     * （セグメントの境界＝「解ける HARD 優先→SOFT→blocked」は死守。同点は元のキー順で安定）。
     */
    internal fun anchors(
        report: ViolationReport, infeasible: Set<Long> = emptySet(),
        familyScore: ((Anchor) -> Double)? = null,
    ): List<Anchor> {
        fun fam(cls: String) = cls.removePrefix("vio-")
        val cells = report.violations.entries.sortedBy { it.key }.mapNotNull { (k, cls) -> parseKey(k)?.let { (i, j) -> Anchor(fam(cls) in MirrorKeys.hard, fam(cls), i, j) } }
        val needs = report.needViolations.entries.sortedBy { it.key }.mapNotNull { (k, cls) -> parseKey(k)?.let { (sh, j) -> Anchor(fam(cls) in MirrorKeys.hard, fam(cls), -1, j, sh) } }
        val counts = report.countViolations.entries.sortedBy { it.key }.mapNotNull { (k, cls) -> parseKey(k)?.let { (i, sh) -> Anchor(false, fam(cls), i, -1, sh) } }
        val hardOnes = (cells + needs).filter { it.hard }
        val (blocked, solvable) = hardOnes.partition { it.family == "covU" && (it.shift * 1000L + it.day) in infeasible }
        fun ordered(list: List<Anchor>): List<Anchor> {
            val f = familyScore ?: return list
            return list.withIndex()
                .sortedWith(compareByDescending<IndexedValue<Anchor>> { f(it.value) }.thenBy { it.index })
                .map { it.value }
        }
        return ordered(solvable) + ordered(counts) + ordered((cells + needs).filter { !it.hard }) + ordered(blocked)
    }

    /** 起点ごとの探索集合＝主候補（起点を触る）＋助候補（主と職員か日を共有）。主候補が無い起点は除く。 */
    internal fun anchorSets(anchors: List<Anchor>, patches: List<Patch>, cap: Int): List<Pair<Anchor, List<Int>>> {
        val out = ArrayList<Pair<Anchor, List<Int>>>()
        for (a in anchors) {
            val primary = patches.indices.filter { a.touches(patches[it]) }
            if (primary.isEmpty()) continue
            val staffSet = HashSet<Int>(); val daySet = HashSet<Int>()
            for (idx in primary) { staffSet.addAll(patches[idx].staff.toList()); daySet.addAll(patches[idx].days.toList()) }
            val helpers = patches.indices.filter { idx -> idx !in primary && (patches[idx].staff.any { it in staffSet } || patches[idx].days.any { it in daySet }) }
            out.add(a to (primary + helpers).take(cap))
        }
        return out
    }

    fun repair(
        state: MagiState,
        schedule: Array<IntArray>,
        pool: List<CombinatorialRepair.Candidate>,
        params: Params = Params(),
        shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult {
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        var bestRep = before
        val pinBlocks = PinBlockAttribution()
        var applied = 0
        fun done(message: String) = V6HotfixPasses.CyclicSwapResult(
            work, before.total, bestRep.total, applied, listOf(MirrorLog(tag = "ComponentRepair", message = "違反連結成分修復: $message")),
            observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks,
        )
        if (pool.size < 2 && !params.generateFromAnchors) return done("候補${pool.size}件=スキップ")
        if (work.any { row -> row.any { it !in 0 until p.K } }) return done("未割当セルあり=スキップ")

        // 候補→差分。現盤面で no-op のもの・範囲外のもの・同一差分は落とす。
        val seen = HashSet<String>()
        val patches = ArrayList<Patch>()
        for (c in pool) {
            if (c.ops.isEmpty() || c.ops.any { it.size < 3 || it[0] !in 0 until p.S || it[1] !in 0 until p.T || it[2] !in 0 until p.K }) continue
            if (c.ops.all { work[it[0]][it[1]] == it[2] }) continue
            if (c.ops.any { p.wishLocked(it[0], it[1]) }) continue   // 希望固定セルは動かさない（規約）
            val pt = Patch(c.ops, c.mechanism, c.hint)
            if (seen.add(pt.signature)) patches.add(pt)
        }
        val poolCount = patches.size
        if (poolCount < 2 && !params.generateFromAnchors) return done("有効候補${poolCount}件=スキップ")

        val delta = DeltaEvaluator(p)
        delta.reset(work)
        // [Iteration 3] 構造的に埋められない人員不足の枠（担当できる人数 < 必要数）。起点の順位を下げるだけで、候補は除かない。
        val infeasibleSlots: Set<Long> = runCatching {
            V6PortAnalyzer.diagnoseCoverage(state, work, bestRep, includeSurplus = false).shortfalls
                .filter { it.verdict == CoverageVerdict.INFEASIBLE }.map { it.shiftIndex * 1000L + it.dayIndex }.toSet()
        }.getOrDefault(emptySet())
        // 厳密ピン（lo==hi）の (職員, シフト)。推定段階で「新たに崩す」枝を落とすために使う（exactPinRegression と同じ判定）。
        val pinned = Array(p.S) { i -> (0 until p.K).filter { k -> val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]; lo != Int.MIN_VALUE && hi != Int.MAX_VALUE && lo == hi }.toIntArray() }
        var estimates = 0; var evaluations = 0; var anchorsTried = 0; var prunedPin = 0; var prunedDebt = 0
        val prunedLone = HashSet<Int>()   // 起点集合ごとに判定するので、同じ候補は 1 回だけ数える

        /** 候補が (職員, シフト) の回数をどれだけ動かすか（現盤面基準）。ピンを崩す候補と、それを戻せる相方の判定に使う。 */
        fun countDelta(pt: Patch): Map<Long, Int> {
            val d = HashMap<Long, Int>()
            for (op in pt.ops) {
                val old = work[op[0]][op[1]]
                if (old == op[2]) continue
                d.merge(op[0] * 1000L + old, -1, Int::plus)
                d.merge(op[0] * 1000L + op[2], 1, Int::plus)
            }
            return d
        }
        var deltas = patches.map { countDelta(it) }
        /** 候補が単独で新たに崩す厳密ピンの鍵（崩さなければ空）。 */
        fun brokenPins(idx: Int): List<Long> {
            val out = ArrayList<Long>()
            for ((key, dv) in deltas[idx]) {
                val i = (key / 1000L).toInt(); val k = (key % 1000L).toInt()
                if (k !in pinned[i]) continue
                val lo = p.rangeLo[i][k]; val before = delta.countForStaff(i, k)
                if (kotlin.math.abs(before + dv - lo) > kotlin.math.abs(before - lo)) out.add(key)
            }
            return out
        }
        /** 単独で厳密ピンを崩す候補は、同じ集合に逆向きの相方（セルが重ならない）が無ければ外す＝相方が居れば束ねて戻せるので残す。 */
        fun dropLonePinBreakers(ids: List<Int>): List<Int> = ids.filter { idx ->
            val broken = brokenPins(idx)
            broken.isEmpty() || broken.all { key ->
                val sign = deltas[idx][key] ?: 0
                ids.any { other -> other != idx && (deltas[other][key] ?: 0) * sign < 0 && !patches[other].overlaps(patches[idx]) }
            }.also { keep -> if (!keep) prunedLone.add(idx) }
        }
        val acceptedLabels = ArrayList<String>()
        val rejectReasons = LinkedHashMap<String, Int>()

        /** 候補 [ids] をまとめて当てたときの推定値（DeltaEvaluator）。厳密ピンを新たに崩す枝は Long.MAX_VALUE。必ず元へ戻す。 */
        fun estimate(ids: IntArray): Long {
            estimates++
            val undo = ArrayList<IntArray>()
            val touched = HashSet<Int>()
            val beforeCnt = HashMap<Long, Int>()
            try {
                for (id in ids) for (i in patches[id].staff) if (touched.add(i)) for (k in pinned[i]) beforeCnt[i * 1000L + k] = delta.countForStaff(i, k)
                for (id in ids) for (op in patches[id].ops) {
                    val old = delta.at(op[0], op[1])
                    if (old != op[2]) { delta.apply(op[0], op[1], op[2]); undo.add(intArrayOf(op[0], op[1], old)) }
                }
                for (i in touched) for (k in pinned[i]) {
                    val lo = p.rangeLo[i][k]
                    val before = beforeCnt[i * 1000L + k] ?: continue
                    if (kotlin.math.abs(delta.countForStaff(i, k) - lo) > kotlin.math.abs(before - lo)) { prunedPin++; return Long.MAX_VALUE }
                }
                return delta.score()
            } finally {
                for (r in undo.asReversed()) delta.apply(r[0], r[1], r[2])
            }
        }

        fun overlapsAny(ids: IntArray, j: Int): Boolean = ids.any { patches[it].overlaps(patches[j]) }

        fun trimFrontier(candidates: List<Node>, baseEst: Long): List<Node> = trimFrontier(candidates, baseEst, params)

        /** 成分 [remaining] の中で最浅の深さで見つかる「正式評価で改善する」トランザクション。無ければ null。 */
        fun search(anchor: Anchor, remaining: List<Int>): Pair<IntArray, ViolationReport>? {
            val baseEst = delta.score()
            val base = work.copy2D()
            val allowance = if (!params.debtExploration) Long.MAX_VALUE
                else ConstraintRepairInference.temporarySoftDebtAllowance(bestRep, anchor.family, temporaryDebtFactor = params.temporaryDebtFactor)
            fun explorable(est: Long): Boolean {
                if (est == Long.MAX_VALUE) return false
                if (!params.debtExploration) return true
                return ConstraintRepairInference.mayExplore(baseEst, est, allowance).also { if (!it) prunedDebt++ }
            }
            var frontier = trimFrontier(remaining.map { Node(intArrayOf(it), estimate(intArrayOf(it))) }.filter { explorable(it.est) }, baseEst)
            var depth = 1
            while (frontier.isNotEmpty()) {
                var best: Pair<IntArray, ViolationReport>? = null
                for (node in frontier) {
                    if (shouldStop() || evaluations >= params.maxEvaluations) break
                    if (node.est > baseEst) continue
                    if (depth == 1 && node.est == baseEst) continue   // 単独候補は各パスで既に正式評価済み＝推定が改善のときだけ再評価
                    evaluations++
                    val ops = node.ids.flatMap { patches[it].ops }
                    val saved = IntArray(ops.size) { work[ops[it][0]][ops[it][1]] }
                    val rep: ViolationReport
                    val pinBad: Boolean
                    try {
                        for (op in ops) work[op[0]][op[1]] = op[2]
                        rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                        val improves = betterReport(rep, bestRep)
                        pinBad = improves && exactPinRegression(p, base, work)
                        if (pinBad) pinBlocks.record(p, base, work)
                    } finally {
                        for ((t, op) in ops.withIndex()) work[op[0]][op[1]] = saved[t]
                    }
                    if (!pinBad && betterReport(rep, bestRep)) {
                        val cur = best
                        if (cur == null || betterReport(rep, cur.second)) best = node.ids to rep
                    } else {
                        val why = when {
                            pinBad -> "ピン破り"
                            rep.hard > bestRep.hard -> "必須増"
                            rep.weightedScore > bestRep.weightedScore -> "重み悪化"
                            rep.total > bestRep.total -> "件数悪化"
                            else -> "同値"
                        }
                        rejectReasons.merge(why, 1, Int::plus)
                    }
                }
                if (best != null) return best
                if (depth >= params.maxK || shouldStop() || evaluations >= params.maxEvaluations || estimates >= params.maxEstimates) return null
                val next = ArrayList<Node>()
                for (node in frontier) {
                    val last = node.ids.last()
                    for (j in remaining) {
                        if (j <= last || overlapsAny(node.ids, j)) continue
                        if (estimates >= params.maxEstimates) break
                        val ids = node.ids + j
                        val est = estimate(ids)
                        if (explorable(est)) next.add(Node(ids, est))
                    }
                }
                frontier = trimFrontier(next, baseEst)
                depth++
            }
            return null
        }

        /** 起点から直接作る候補（半径 1）: セル違反＝別シフトへの変更と同日 2 者交換、人数不足/過剰＝その日の単セル変更、回数違反＝その職員の日で足す/休へ戻す。 */
        // 影響半径: c1 の窓長・c3 系パターン長の最大 −1（最低 1）。区間交換の最大長はこれを maxWindowLength で頭打ち。
        val reach = run {
            var r = 1
            for (c in p.cons1) r = maxOf(r, c.day1 - 1)
            for (list in listOf(p.cons3, p.cons3n, p.cons3m, p.cons3mn)) for (c in list) r = maxOf(r, c.seq.size - 1)
            minOf(r, params.maxWindowLength, maxOf(1, p.T - 1))
        }
        fun generateFor(a: Anchor, sink: MutableList<Patch>, seenSig: MutableSet<String>) {
            var made = 0
            fun add(ops: List<IntArray>, hint: String) {
                if (made >= params.maxGeneratedPerAnchor) return
                val pt = Patch(ops, "起点生成", hint)
                if (seenSig.add(pt.signature)) { sink.add(pt); made++ }
            }
            fun staffName(i: Int) = state.staff.getOrNull(i)?.name ?: "#$i"
            fun kig(k: Int) = state.shifts.getOrNull(k)?.kigou ?: "#$k"
            // [Iteration 6] 厳密ピン（lo==hi）を単独で崩す候補は推定で必ず落ちる（Iteration 5 の実測: 推定 3959 回中ピン枝刈り 1665）。
            //   生成の時点で「同じ職員の別の日で回数を戻す」相方を付けた形にし、無駄弾を出さない。
            fun breaksPin(i: Int, from: Int, to: Int): Boolean {
                for (k in pinned[i]) {
                    val dv = (if (k == to) 1 else 0) - (if (k == from) 1 else 0)
                    if (dv == 0) continue
                    val lo = p.rangeLo[i][k]; val before = delta.countForStaff(i, k)
                    if (kotlin.math.abs(before + dv - lo) > kotlin.math.abs(before - lo)) return true
                }
                return false
            }
            /** j に近い日から順に走査する（c1 の窓・c3 の並びに効く局所の手を優先）。 */
            fun daysNear(j: Int): Sequence<Int> = sequence { for (r in 1 until p.T) { if (j - r >= 0) yield(j - r); if (j + r < p.T) yield(j + r) } }
            fun single(i: Int, j: Int, k2: Int) {
                if (k2 !in 0 until p.K || k2 == work[i][j] || p.wishLocked(i, j) || !p.mayPlace(i, k2)) return
                val old = work[i][j]
                if (!breaksPin(i, old, k2)) { add(listOf(intArrayOf(i, j, k2)), "${staffName(i)} ${j + 1}日→${kig(k2)}"); return }
                // 行内の入替（j を k2 に、別の日 d の k2 を old に）＝職員 i の回数は不変。近い日から最大 3 本。
                var made2 = 0
                for (d in daysNear(j)) {
                    if (made2 >= 3) break
                    if (work[i][d] != k2 || p.wishLocked(i, d) || !p.mayPlace(i, old)) continue
                    add(listOf(intArrayOf(i, j, k2), intArrayOf(i, d, old)), "${staffName(i)} ${j + 1}日⇄${d + 1}日")
                    made2++
                }
            }
            fun swap(x: Int, y: Int, j: Int) {
                if (x == y) return
                val kx = work[x][j]; val ky = work[y][j]
                if (kx == ky || p.wishLocked(x, j) || p.wishLocked(y, j) || !p.mayPlace(x, ky) || !p.mayPlace(y, kx)) return
                if (!breaksPin(x, kx, ky) && !breaksPin(y, ky, kx)) { add(listOf(intArrayOf(x, j, ky), intArrayOf(y, j, kx)), "${staffName(x)}↔${staffName(y)} ${j + 1}日"); return }
                // 2 日の交換（j で入れ替え、逆の並びの日 d で戻す）＝両者の回数は不変。近い日から最大 2 本。
                var made2 = 0
                for (d in daysNear(j)) {
                    if (made2 >= 2) break
                    if (work[x][d] != ky || work[y][d] != kx || p.wishLocked(x, d) || p.wishLocked(y, d)) continue
                    add(listOf(intArrayOf(x, j, ky), intArrayOf(y, j, kx), intArrayOf(x, d, kx), intArrayOf(y, d, ky)), "${staffName(x)}↔${staffName(y)} ${j + 1}日/${d + 1}日")
                    made2++
                }
            }
            fun window(x: Int, y: Int, s0: Int, s1: Int) {
                if (x == y) return
                var changes = false
                for (d in s0..s1) {
                    val kx = work[x][d]; val ky = work[y][d]
                    if (p.wishLocked(x, d) || p.wishLocked(y, d) || !p.mayPlace(x, ky) || !p.mayPlace(y, kx)) return
                    if (kx != ky) changes = true
                }
                if (!changes) return
                val ops = ArrayList<IntArray>(2 * (s1 - s0 + 1))
                for (d in s0..s1) { ops.add(intArrayOf(x, d, work[y][d])); ops.add(intArrayOf(y, d, work[x][d])) }
                add(ops, "${staffName(x)}↔${staffName(y)} ${s0 + 1}〜${s1 + 1}日")
            }
            fun rotate3(x: Int, y: Int, z: Int, j: Int) {
                if (x == y || y == z || x == z) return
                val kx = work[x][j]; val ky = work[y][j]; val kz = work[z][j]
                if (kx == ky || ky == kz || kx == kz) return
                if (p.wishLocked(x, j) || p.wishLocked(y, j) || p.wishLocked(z, j)) return
                if (!p.mayPlace(x, ky) || !p.mayPlace(y, kz) || !p.mayPlace(z, kx)) return
                add(listOf(intArrayOf(x, j, ky), intArrayOf(y, j, kz), intArrayOf(z, j, kx)), "${staffName(x)}→${staffName(y)}→${staffName(z)} ${j + 1}日")
            }
            when {
                a.staff >= 0 && a.day >= 0 -> {
                    for (k2 in p.allowedShiftsForStaff(a.staff)) single(a.staff, a.day, k2)
                    for (b in 0 until p.S) swap(a.staff, b, a.day)
                    // [Iteration 5] 半径 2 以上: 起点の日を含む同長区間交換（長さは制約の窓長まで）と、同日の 3 職員巡回。
                    for (len in 2..minOf(reach, p.T)) for (b in 0 until p.S) for (s0 in maxOf(0, a.day - len + 1)..minOf(p.T - len, a.day)) window(a.staff, b, s0, s0 + len - 1)
                    for (b in 0 until p.S) for (c in 0 until p.S) rotate3(a.staff, b, c, a.day)
                }
                a.staff < 0 -> when (a.family) {
                    "covU" -> for (i in 0 until p.S) single(i, a.day, a.shift)
                    "covO" -> for (i in 0 until p.S) if (work[i][a.day] == a.shift) single(i, a.day, p.restIdx)
                }
                else -> when {
                    a.family.endsWith("low", ignoreCase = true) -> for (j in 0 until p.T) single(a.staff, j, a.shift)
                    a.family.endsWith("high", ignoreCase = true) -> for (j in 0 until p.T) if (work[a.staff][j] == a.shift) single(a.staff, j, p.restIdx)
                }
            }
        }

        // 起点（違反）ごとに探索し、採用があれば盤面が変わるので起点（と起点生成の候補）を作り直す。1 周して採用が無ければ終わり。
        val used = HashSet<Int>()
        var anchorCount = 0; var maxSet = 0; var generatedTotal = 0
        // familyPriorityScoring 用: 1 回の repair() 呼出に閉じたスコープ（呼出をまたいだ永続化はしない）。
        val triedByFamily = HashMap<String, Int>()
        val committedByFamily = HashMap<String, Int>()
        val familyScoreFn: ((Anchor) -> Double)? = if (!params.familyPriorityScoring) null
            else { a -> ConstraintRepairInference.familyPriorityScore(bestRep, a.family, triedByFamily[a.family] ?: 0, committedByFamily[a.family] ?: 0) }
        class Found(val anchor: Anchor, val chosen: IntArray, val rep: ViolationReport)
        outer@ while (!shouldStop() && evaluations < params.maxEvaluations && estimates < params.maxEstimates) {
            val currentAnchors = anchors(bestRep, infeasibleSlots, familyScoreFn)
            while (patches.size > poolCount) patches.removeAt(patches.size - 1)
            if (params.generateFromAnchors) {
                val sig = HashSet<String>(); for (pt in patches) sig.add(pt.signature)
                for (a in currentAnchors.take(params.maxAnchors)) { if (patches.size - poolCount >= params.maxGenerated) break; generateFor(a, patches, sig) }
                generatedTotal = maxOf(generatedTotal, patches.size - poolCount)
                deltas = patches.map { countDelta(it) }
            }
            if (patches.size < 1) break
            val sets = anchorSets(currentAnchors, patches, params.maxPatchesPerAnchor)
                .map { (a, ids) -> a to dropLonePinBreakers(ids.filter { it !in used }) }.filter { it.second.isNotEmpty() }
            anchorCount = sets.size
            var committed = false
            // bestOfK<=1: 従来どおり最初に成功した起点で即採用。bestOfK>=2: 最大 bestOfK 件を実際に search() し、
            // betterReport で客観的に最良の一つだけを採用する（順序非依存。search() は副作用なし＝複数回呼んでも安全）。
            val found = ArrayList<Found>()
            for ((anchor, ids) in sets.take(params.maxAnchors)) {
                if (shouldStop() || evaluations >= params.maxEvaluations || estimates >= params.maxEstimates) break
                anchorsTried++; maxSet = maxOf(maxSet, ids.size)
                triedByFamily.merge(anchor.family, 1, Int::plus)
                val (chosen, rep) = search(anchor, ids) ?: continue
                found.add(Found(anchor, chosen, rep))
                if (found.size >= maxOf(1, params.bestOfK)) break
            }
            if (found.isNotEmpty()) {
                val best = found.reduce { a, b -> if (betterReport(b.rep, a.rep)) b else a }
                committedByFamily.merge(best.anchor.family, 1, Int::plus)
                for (id in best.chosen) for (op in patches[id].ops) {
                    if (work[op[0]][op[1]] != op[2]) { work[op[0]][op[1]] = op[2]; delta.apply(op[0], op[1], op[2]) }
                }
                bestRep = best.rep; applied++; used.addAll(best.chosen.filter { it < poolCount })   // 起点生成の候補は毎周作り直す
                acceptedLabels.add(best.anchor.label + ": " + best.chosen.joinToString("+") { patches[it].hint.ifBlank { patches[it].mechanism } } + "(k=${best.chosen.size})")
                committed = true
                continue@outer
            }
            if (!committed) break
        }

        val mech = LinkedHashMap<String, Int>()
        for (pt in patches.take(poolCount)) mech.merge(pt.mechanism, 1, Int::plus)
        return done(
            "候補${poolCount}件(" + mech.entries.joinToString(" ") { "${it.key}×${it.value}" } + ")+起点生成${generatedTotal}件 起点${anchorCount}件(探索${anchorsTried}件・最大${maxSet}候補)" +
                " 推定${estimates}回(ピン枝刈り${prunedPin}・相方なし除外${prunedLone.size}${if (params.debtExploration) "・包絡外$prunedDebt" else ""}) 正式評価${evaluations}回 採用${applied}件" +
                (if (acceptedLabels.isNotEmpty()) "[" + acceptedLabels.joinToString(", ") + "]" else "") +
                (if (rejectReasons.isNotEmpty()) " 不採用(" + rejectReasons.entries.joinToString(" ") { "${it.key}:${it.value}" } + ")" else "") +
                " / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} score ${before.weightedScore.toLong()}->${bestRep.weightedScore.toLong()}",
        )
    }
}
