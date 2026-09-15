package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlin.math.max
import kotlin.math.min

/**
 * 希望島研磨（ユーザー提示の確定仕様・3.496.0、構造の見直し 3.498.0）。
 *
 * - 実現可能な希望シフト日（`wishLocked`）を固定アンカーにし、希望セル自体は変更しない。
 * - 制約の影響半径 R（c1 の窓長・c3 系パターン長から決まる）が重なる希望を同じ職員の**希望島**に統合し、
 *   島の影響範囲（前後 R 日）または本人の回数に違反がある島だけ起動する。
 * - 手は 同日交換 → 可変長窓交換 → 両翼交換 → 必要時のみ3職員巡回。同じ所属・技能群の相手を先に試す。
 * - 採否は正式チェッカーで HARD → weightedScore → total（`betterReport`）。通常は希望周辺（局所スコア）も全体も
 *   改善する手だけ採用し、1 pass で採用 0 のときだけ短いビーム探索（途中は中立手を許す）を行う。
 * - keep-best: 開始盤面より改善しなければ開始盤面を返す。月跨ぎは扱わず `0 until T` で完結する。
 */
internal object WishIslandPolish {

    /**
     * 探索予算と打ち切りの設定。値は呼出側（`V6HotfixPasses.runPostOptimization`）が予算に合わせて渡す。
     * 既定値は 3.496.0 の実測（10 職員・31 日）で 1 pass あたり 0.1 秒台に収まる量。
     */
    data class Params(
        /** 島を一巡する回数の上限。採用 0 の pass はビームへ進み、ビームでも改善しなければ終了する。 */
        val maxPasses: Int = 3,
        /** 正式評価（`UnifiedViolationChecker.check`）の総数の上限。研磨全体の時間予算に相当する。 */
        val maxEvaluations: Int = 120,
        /** 停滞時ビームの幅と深さ。深さ 3 は「両翼＋同日」程度の複合手を 1 本で表せる最小値。 */
        val beamWidth: Int = 4,
        val beamDepth: Int = 3,
        /** 起動した島 1 つに保証する評価数。同日交換の候補を数手は試せる量として 8。 */
        val minIslandBudget: Int = 8,
        /** ビーム 1 段で保持する中立手の上限（幅の倍率。残り予算で頭打ち＝`beamCandidateLimit`）。中立手は無数にあるので打ち切りが要る。 */
        val beamBranchFactor: Int = 6,
        /** ログに名前を出す残存職員の上限。 */
        val stuckNamesShown: Int = 8,
    )

    /** テスト用: 各島の通常候補（同日・窓・両翼）を (種類, セル列) で列挙する。月初・月末で両翼が出ないこと等を固定する。 */
    internal fun enumerateMovesForTest(state: MagiState, schedule: Array<IntArray>): List<Pair<String, IntArray>> =
        Session(state, schedule, Params(), { false }).movesForTest()

    /** 既存の呼出側・テストとの互換入口。 */
    fun applyWishIslandPolish(
        state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, maxEvaluations: Int = 120,
        beamWidth: Int = 4, beamDepth: Int = 3, shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult = applyWishIslandPolish(
        state, schedule, Params(maxPasses = maxPasses, maxEvaluations = maxEvaluations, beamWidth = beamWidth, beamDepth = beamDepth), shouldStop,
        quantitativeRangeEval,
    )

    fun applyWishIslandPolish(
        state: MagiState, schedule: Array<IntArray>, params: Params, shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult = Session(state, schedule, params, shouldStop, quantitativeRangeEval).run()

    /** ビーム 1 段で走査する中立手の上限＝保持数の何倍か（[3.502.0]）。評価予算はこれとは別に `maxEvaluations` で頭打ち。 */
    private const val BEAM_SCAN_FACTOR = 2

    /** ビーム 1 段で保持する候補数＝幅×分岐を残り予算で頭打ち（いずれも 1 以上に丸める）。 */
    internal fun beamCandidateLimit(width: Int, branchFactor: Int, remainingEvaluations: Int): Int =
        min(max(width, 1).toLong() * max(branchFactor, 1), max(remainingEvaluations, 1).toLong()).toInt()

    private enum class MoveKind(val label: String) { SAME_DAY("同日"), WINDOW("窓"), WINGS("両翼"), ROTATE3("巡回") }

    /** 1手＝(職員, 日, 新しい値) の三つ組の並び。適用と巻き戻しが同じ形でできる。 */
    private class Move(val kind: MoveKind, val cells: IntArray, val sameGroup: Boolean)

    /** 職員 [staff] の希望日 [wishDays] と、その影響範囲 [zoneFrom]..[zoneTo]（当月内に切り詰め済み）。 */
    private class Island(val staff: Int, val wishDays: IntArray, val zoneFrom: Int, val zoneTo: Int) {
        /** 局所スコア用に事前計算したセルキー（毎評価の文字列生成を避ける）。 */
        val zoneKeys: Array<String> = Array(zoneTo - zoneFrom + 1) { "$staff,${zoneFrom + it}" }
        val countPrefix: String = "$staff,"
    }

    private class Node(val board: Array<IntArray>, val rep: ViolationReport)

    private class Session(
        private val state: MagiState, private val input: Array<IntArray>, params: Params, private val shouldStop: () -> Boolean,
        private val quantitativeRangeEval: Boolean = false,
    ) {
        /** 不正な設定でも研磨パスが落ちないように下限へ丸める（負の予算＝何もしない、幅 0 のビーム＝幅 1）。 */
        private val prm = params.copy(
            maxPasses = params.maxPasses.coerceAtLeast(0), maxEvaluations = params.maxEvaluations.coerceAtLeast(0),
            beamWidth = params.beamWidth.coerceAtLeast(1), beamDepth = params.beamDepth.coerceAtLeast(0),
            minIslandBudget = params.minIslandBudget.coerceAtLeast(1), beamBranchFactor = params.beamBranchFactor.coerceAtLeast(1),
            stuckNamesShown = params.stuckNamesShown.coerceAtLeast(0),
        )
        private val p = Problem(state, quantitativeRangeEval)
        private val work = normalizeSchedule(input, p)
        private val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        private var bestRep = before
        private val T = p.T; private val S = p.S; private val K = p.K
        private val reach = computeReach()
        /** 島は希望（固定）だけで決まり盤面に依らないので 1 回だけ作る。 */
        private val islands: List<Island> = buildIslands()
        private val pinBlocks = PinBlockAttribution()
        private val rejectCulprits = RejectCulpritStats()
        private val stuck = LinkedHashSet<String>()
        private val byKind = LinkedHashMap<String, Int>()
        private var applied = 0; private var evaluated = 0; private var beamEvaluated = 0
        private var beamRuns = 0; private var beamApplied = 0; private var prunedC3n = 0
        private var activeCount = 0
        /** 今の島で使った評価数（通常候補と巡回候補で 1 つの枠を分け合う）。 */
        private var islandUsed = 0

        /** 影響半径: c1 の窓長・c3 系パターン長の最大 −1（最低 1、最大 T−1）。前後この日数が希望日の「周辺」。 */
        private fun computeReach(): Int {
            var r = 1
            for (c in p.cons1) r = max(r, c.day1 - 1)
            for (list in listOf(p.cons3, p.cons3n, p.cons3m, p.cons3mn)) for (c in list) r = max(r, c.seq.size - 1)
            return min(r, max(1, T - 1))
        }

        private fun locked(i: Int, d: Int) = p.wishLocked(i, d)
        private fun sameGroup(a: Int, b: Int) = p.sgrp[a] == p.sgrp[b] && p.ssk[a] == p.ssk[b]
        private fun name(i: Int) = state.staff.getOrNull(i)?.name ?: "#$i"
        private fun budgetLeft() = !shouldStop() && evaluated < prm.maxEvaluations

        // ---- 希望島 ----
        private fun buildIslands(): List<Island> {
            val out = ArrayList<Island>()
            for (i in 0 until S) {
                val days = (0 until T).filter { locked(i, it) }
                if (days.isNotEmpty()) mergeIntoIslands(i, days, out)
            }
            return out
        }

        /** 希望日をソート順に見て、影響範囲（±reach）が重なる限り同じ島へ入れる。 */
        private fun mergeIntoIslands(i: Int, days: List<Int>, out: MutableList<Island>) {
            var from = days[0]; var to = days[0]
            val cur = ArrayList<Int>().apply { add(days[0]) }
            fun flush() { out.add(Island(i, cur.toIntArray(), max(0, from - reach), min(T - 1, to + reach))); cur.clear() }
            for (t in 1 until days.size) {
                val d = days[t]
                if (d - reach > to + reach) { flush(); from = d }
                to = d; cur.add(d)
            }
            flush()
        }

        /** 島の周辺の違反重み（希望周辺の改善判定に使う）。セル違反＝影響範囲内、回数違反＝当該職員の全部。 */
        private fun localScore(rep: ViolationReport, isl: Island): Long {
            var s = 0L
            for (key in isl.zoneKeys) {
                val fams = rep.cellFamilies[key] ?: continue
                for (f in fams) s += weightOfClass(f)
            }
            for ((key, cls) in rep.countViolations) if (key.startsWith(isl.countPrefix)) s += weightOfClass(cls)
            return s
        }

        /** 重みは最低 1（重み 0 の族も「違反がある」事実として数える）。 */
        private fun weightOfClass(cls: String): Long = MirrorKeys.weightOf(cls.removePrefix("vio-")).toLong().coerceAtLeast(1L)

        // ---- 手の適用・巻き戻し・事前枝刈り ----
        private fun apply(m: Move): IntArray {
            val old = IntArray(m.cells.size / 3)
            var t = 0
            while (t < m.cells.size) {
                val i = m.cells[t]; val d = m.cells[t + 1]
                old[t / 3] = work[i][d]; work[i][d] = m.cells[t + 2]; t += 3
            }
            return old
        }

        private fun undo(m: Move, old: IntArray) {
            var t = 0
            while (t < m.cells.size) { work[m.cells[t]][m.cells[t + 1]] = old[t / 3]; t += 3 }
        }

        /**
         * 変更する職員の禁止の並び（cons3n）の件数が増えるなら正式評価の前に落とす（チェッカーが最終判定＝見逃しは無害）。
         * [3.501.0] 旧: 変更セルに禁止の並びが 1 つでも残れば落としていた＝「2件→1件」に減らす手まで正式評価へ届かなかった。
         * `AdaptiveBlockSwapPolish.c3nFiresIncrease` と同じ増分判定（`C1DeltaPrefilter.staffC3nFires`＝チェッカーと同一意味論）。
         */
        private fun increasesForbidden(m: Move): Boolean {
            if (p.cons3n.isEmpty()) return false
            var before = 0; var after = 0
            var t = 0
            while (t < m.cells.size) {
                val i = m.cells[t]
                var seen = false
                var u = 0
                while (u < t) { if (m.cells[u] == i) { seen = true; break }; u += 3 }
                if (!seen) before += C1DeltaPrefilter.staffC3nFires(p, work[i])
                t += 3
            }
            val old = apply(m)
            try {
                t = 0
                while (t < m.cells.size) {
                    val i = m.cells[t]
                    var seen = false
                    var u = 0
                    while (u < t) { if (m.cells[u] == i) { seen = true; break }; u += 3 }
                    if (!seen) after += C1DeltaPrefilter.staffC3nFires(p, work[i])
                    t += 3
                }
                return after > before
            } finally { undo(m, old) }
        }

        // ---- 候補生成（遅延・評価順＝手の種類 → 同じ所属 → 小さい手） ----
        private fun swappable(a: Int, b: Int, d: Int): Boolean {
            val ka = work[a][d]; val kb = work[b][d]
            if (ka !in 0 until K || kb !in 0 until K) return false
            if (locked(a, d) || locked(b, d)) return false
            return p.mayPlace(a, kb) && p.mayPlace(b, ka)
        }

        /** 窓 [s0..s1] を a と b で丸ごと交換できて、かつ何かが変わるとき真。 */
        private fun windowOk(a: Int, b: Int, s0: Int, s1: Int): Boolean {
            var changes = false
            for (d in s0..s1) {
                if (!swappable(a, b, d)) return false
                if (work[a][d] != work[b][d]) changes = true
            }
            return changes
        }

        private fun windowCells(a: Int, b: Int, s0: Int, s1: Int, into: MutableList<Int>) {
            for (d in s0..s1) { into.add(a); into.add(d); into.add(work[b][d]); into.add(b); into.add(d); into.add(work[a][d]) }
        }

        private fun partners(a: Int, sg: Boolean): Sequence<Int> = (0 until S).asSequence().filter { it != a && sameGroup(a, it) == sg }

        private fun sameDayMoves(isl: Island, sg: Boolean): Sequence<Move> = sequence {
            val a = isl.staff
            for (d in isl.zoneFrom..isl.zoneTo) {
                if (locked(a, d) || work[a][d] !in 0 until K) continue
                for (b in partners(a, sg)) {
                    if (work[b][d] == work[a][d] || !swappable(a, b, d)) continue
                    yield(Move(MoveKind.SAME_DAY, intArrayOf(a, d, work[b][d], b, d, work[a][d]), sg))
                }
            }
        }

        private fun windowMoves(isl: Island, sg: Boolean, len: Int): Sequence<Move> = sequence {
            val a = isl.staff
            for (b in partners(a, sg)) for (s0 in isl.zoneFrom..(isl.zoneTo - len + 1)) {
                val s1 = s0 + len - 1
                if (!windowOk(a, b, s0, s1)) continue
                val cells = ArrayList<Int>(len * 6); windowCells(a, b, s0, s1, cells)
                yield(Move(MoveKind.WINDOW, cells.toIntArray(), sg))
            }
        }

        private fun windowMoves(isl: Island): Sequence<Move> = sequence {
            val zl = isl.zoneTo - isl.zoneFrom + 1
            for (sg in booleanArrayOf(true, false)) for (len in 2..zl) yieldAll(windowMoves(isl, sg, len))
        }

        /** 両翼＝島の前の窓 [l0..first-1] と後の窓 [last+1..r1] を同じ相手と同時に交換。合計長 [total] のものだけ生成。 */
        private fun wingMoves(isl: Island, sg: Boolean, total: Int): Sequence<Move> = sequence {
            val a = isl.staff
            val first = isl.wishDays.first(); val last = isl.wishDays.last()
            for (b in partners(a, sg)) for (l0 in isl.zoneFrom until first) {
                val r1 = last + (total - (first - l0))
                if (r1 !in (last + 1)..isl.zoneTo) continue
                if (!windowOk(a, b, l0, first - 1) || !windowOk(a, b, last + 1, r1)) continue
                val cells = ArrayList<Int>(total * 6); windowCells(a, b, l0, first - 1, cells); windowCells(a, b, last + 1, r1, cells)
                yield(Move(MoveKind.WINGS, cells.toIntArray(), sg))
            }
        }

        private fun wingMoves(isl: Island): Sequence<Move> = sequence {
            val first = isl.wishDays.first(); val last = isl.wishDays.last()
            if (first <= isl.zoneFrom || last >= isl.zoneTo) return@sequence   // 月初・月末で片翼が無い＝両翼交換なし
            val maxTotal = (first - isl.zoneFrom) + (isl.zoneTo - last)
            for (sg in booleanArrayOf(true, false)) for (total in 2..maxTotal) yieldAll(wingMoves(isl, sg, total))
        }

        private fun rotate3Moves(isl: Island, sg: Boolean): Sequence<Move> = sequence {
            val a = isl.staff
            for (d in isl.zoneFrom..isl.zoneTo) {
                if (locked(a, d) || work[a][d] !in 0 until K) continue
                yieldAll(rotate3At(isl, d, sg))
            }
        }

        private fun rotate3At(isl: Island, d: Int, sg: Boolean): Sequence<Move> = sequence {
            val a = isl.staff; val ka = work[a][d]
            for (b in 0 until S) {
                if (b == a || locked(b, d)) continue
                val kb = work[b][d]; if (kb !in 0 until K || !p.mayPlace(a, kb)) continue
                for (c in 0 until S) {
                    if (c == a || c == b || locked(c, d)) continue
                    val kc = work[c][d]; if (kc !in 0 until K || !p.mayPlace(b, kc) || !p.mayPlace(c, ka)) continue
                    if (ka == kb && kb == kc) continue
                    if ((sameGroup(a, b) && sameGroup(b, c)) != sg) continue
                    yield(Move(MoveKind.ROTATE3, intArrayOf(a, d, kb, b, d, kc, c, d, ka), sg))
                }
            }
        }

        private fun sameDayMoves(isl: Island): Sequence<Move> = sequence { for (sg in booleanArrayOf(true, false)) yieldAll(sameDayMoves(isl, sg)) }
        private fun rotate3Moves(isl: Island): Sequence<Move> = sequence { for (sg in booleanArrayOf(true, false)) yieldAll(rotate3Moves(isl, sg)) }

        /**
         * 通常 pass の候補: 同日・窓・両翼を 1 手ずつ交互に（巡回は採用 0 のときだけ別途）。
         * [3.501.0] 旧: 同日 → 窓 → 両翼の連結で、島の枠を同日候補（30名×範囲日数）が先に使い切り、窓・両翼が評価されなかった。
         */
        private fun islandMoves(isl: Island): Sequence<Move> = interleave(sameDayMoves(isl), windowMoves(isl), wingMoves(isl))

        private fun interleave(vararg seqs: Sequence<Move>): Sequence<Move> = sequence {
            val its = seqs.map { it.iterator() }.toMutableList()
            while (its.isNotEmpty()) {
                val cursor = its.iterator()
                while (cursor.hasNext()) { val x = cursor.next(); if (x.hasNext()) yield(x.next()) else cursor.remove() }
            }
        }

        /**
         * ビームの候補: 島ごとに（同日・窓・両翼）を 1 手ずつ交互に並べ、さらに島どうしも交互に巡回する
         * （連結順だと先頭の島と同日候補が走査枠を独占し両翼が出ない。計測は docs/history 3.504.0）。
         */
        private fun beamMoves(active: List<Island>): Sequence<Move> = interleave(*active.map { islandMoves(it) }.toTypedArray())

        fun movesForTest(): List<Pair<String, IntArray>> = islands.flatMap { isl -> islandMoves(isl).map { it.kind.label to it.cells }.toList() }

        // ---- 評価 ----
        private class Chosen(val move: Move, val rep: ViolationReport)

        /** [moves] を順に正式評価し、全体も希望周辺も改善する手のうち最良のものを返す（島の枠 [budget] 手まで）。 */
        private fun pickBest(isl: Island, moves: Sequence<Move>, budget: Int, localBefore: Long): Chosen? {
            var chosen: Chosen? = null
            val base = work.copy2D()
            for (m in moves) {
                if (!budgetLeft() || islandUsed >= budget) break
                if (increasesForbidden(m)) { prunedC3n++; continue }
                islandUsed++; evaluated++
                val old = apply(m)
                val rep: ViolationReport
                val pinBad: Boolean
                val accept: Boolean
                try {
                    rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                    val improves = betterReport(rep, bestRep)
                    pinBad = improves && exactPinRegression(p, base, work)
                    if (pinBad) pinBlocks.record(p, base, work)
                    accept = improves && !pinBad && localScore(rep, isl) < localBefore
                } finally { undo(m, old) }   // 評価器・ピン検査のどこで例外になっても試行手を盤面に残さない。
                if (!accept) { rejectCulprits.record(rep, bestRep, pinBad); continue }
                val cur = chosen
                if (cur == null || betterReport(rep, cur.rep)) chosen = Chosen(m, rep)
            }
            return chosen
        }

        /** 1 pass: 起動中の島を順に見て、島ごとに最良の 1 手を採用する。戻り値は採用数。 */
        private fun runPass(active: List<Island>): Int {
            var passApplied = 0
            val islandBudget = max(prm.minIslandBudget, (prm.maxEvaluations - evaluated) / active.size)
            for (isl in active) {
                if (!budgetLeft()) break
                // 前の島の採用で周辺の違反が消えた島は、どの手も「希望周辺の改善」を満たせないので評価しない（枠の無駄）。
                val localBefore = localScore(bestRep, isl)
                if (localBefore == 0L) continue
                islandUsed = 0
                // [3.501.0] 島の枠の 25% を 3 職員巡回に確保する（通常候補は 75% まで）。巡回は採用 0 のときだけ（残り枠を全部使える）。
                val mainBudget = islandBudget - islandBudget / 4
                var chosen = pickBest(isl, islandMoves(isl), mainBudget, localBefore)
                if (chosen == null) chosen = pickBest(isl, rotate3Moves(isl), islandBudget, localBefore)
                if (chosen == null) { stuck.add(name(isl.staff)); continue }
                apply(chosen.move); bestRep = chosen.rep; applied++; passApplied++
                byKind.merge(chosen.move.kind.label, 1, Int::plus)
            }
            return passApplied
        }

        /** 停滞時の短いビーム。途中は中立手（悪化しない手）を許し、最終盤面が全体で改善したときだけ採用する。 */
        private fun runBeam(): Boolean {
            beamRuns++
            val baseline = work.copy2D()
            var frontier = listOf(Node(work.copy2D(), bestRep))
            var bestNode: Node? = null
            for (depth in 0 until prm.beamDepth) {
                if (!budgetLeft()) break
                val next = ArrayList<Node>()
                // [3.504.0] 段の保持数は残り予算で頭打ちにし、走査枠は先頭ノードだけでなく frontier の各ノードへ均等に配る。
                val remaining = max(prm.maxEvaluations - evaluated, 0)
                val depthLimit = beamCandidateLimit(prm.beamWidth, prm.beamBranchFactor, remaining)
                val perNodeLimit = max(1, depthLimit / frontier.size)
                val seenBoards = HashSet<BoardKey>()   // 別の交換列から同じ盤面へ着いた候補で幅と予算を重複消費しない
                for (node in frontier) { if (!budgetLeft()) break; expandNode(node, next, perNodeLimit, depthLimit, seenBoards) }
                if (next.isEmpty()) break
                next.sortWith { x, y -> reportComparator.compare(x.rep, y.rep) }
                frontier = next.take(prm.beamWidth)
                val top = frontier.first()
                val cur = bestNode
                if (betterReport(top.rep, bestRep) && (cur == null || betterReport(top.rep, cur.rep))) bestNode = top
            }
            restore(baseline)
            val bn = bestNode ?: return false
            if (exactPinRegression(p, baseline, bn.board)) return false
            restore(bn.board); bestRep = bn.rep; applied++; beamApplied++
            return true
        }

        /**
         * 1 ノードの展開: `nodeLimit × BEAM_SCAN_FACTOR` 手まで正式評価し（枝刈りした手は数えない）、段全体で共有する [next] に
         * 良い順で `depthLimit` 件だけ保持する（3.502.0: 列挙順の先頭で打ち切らない／3.504.0: 走査枠はノードごと、保持数は段ごと）。
         */
        private fun expandNode(node: Node, next: MutableList<Node>, nodeLimit: Int, depthLimit: Int, seenBoards: MutableSet<BoardKey>) {
            restore(node.board)
            val active = islands.filter { localScore(node.rep, it) > 0L }
            val scanLimit = nodeLimit * BEAM_SCAN_FACTOR
            var scanned = 0
            for (m in beamMoves(active)) {
                if (!budgetLeft() || scanned >= scanLimit) break
                if (increasesForbidden(m)) { prunedC3n++; continue }
                val old = apply(m)
                try {
                    val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                    evaluated++; beamEvaluated++; scanned++
                    val neutral = !betterReport(node.rep, rep) && !exactPinRegression(p, node.board, work)
                    if (neutral) { val key = BoardKey(work.copy2D()); if (seenBoards.add(key)) keepBest(next, Node(key.work, rep), depthLimit) }
                } finally { undo(m, old) }
            }
        }

        /** [next] を良い順に保ったまま [node] を挿入し、[limit] 件を超えた末尾（最も悪い手）を落とす。 */
        private fun keepBest(next: MutableList<Node>, node: Node, limit: Int) {
            var pos = next.size
            while (pos > 0 && reportComparator.compare(node.rep, next[pos - 1].rep) < 0) pos--
            if (pos >= limit) return
            next.add(pos, node)
            if (next.size > limit) next.removeAt(next.size - 1)
        }

        private fun restore(board: Array<IntArray>) { for (s in 0 until S) System.arraycopy(board[s], 0, work[s], 0, T) }

        fun run(): V6HotfixPasses.CyclicSwapResult {
            var pass = 0
            while (pass < prm.maxPasses && budgetLeft()) {
                val active = islands.filter { localScore(bestRep, it) > 0L }
                activeCount = active.size
                if (active.isEmpty()) break
                if (runPass(active) == 0 && !runBeam()) break   // 通常もビームも改善なし＝停滞で終了
                pass++
            }
            val improved = betterReport(bestRep, before)
            val finalSched = if (improved) work else normalizeSchedule(input, p)
            val finalRep = if (improved) bestRep else before
            return V6HotfixPasses.CyclicSwapResult(
                finalSched, before.total, finalRep.total, applied, listOf(MirrorLog(tag = "WishIslandPolish", message = summary(finalRep))),
                observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks,
            )
        }

        private fun summary(finalRep: ViolationReport): String {
            val wishCount = islands.sumOf { it.wishDays.size }
            val sb = StringBuilder()
            sb.append("希望島研磨: 希望${wishCount}件→島${islands.size}件(起動${activeCount}件・影響半径${reach}日) 正式評価${evaluated}")
            if (beamEvaluated > 0) sb.append("(うちビーム${beamEvaluated})")
            sb.append(" / total ${before.total}->${finalRep.total} HARD ${before.hard}->${finalRep.hard} 採用${applied}回")
            if (byKind.isNotEmpty()) sb.append("(").append(byKind.entries.joinToString(" ") { "${it.key}:${it.value}" }).append(")")
            if (beamRuns > 0) sb.append(" ビーム${beamRuns}回(採用${beamApplied})")
            if (prunedC3n > 0) sb.append(" 禁止の並びで枝刈り${prunedC3n}")
            if (applied == 0 && activeCount > 0) sb.append(" [頭打ち=改善手なし]")
            sb.append(rejectCulprits.summary())
            if (stuck.isNotEmpty()) {
                sb.append(" 残存: ").append(stuck.take(prm.stuckNamesShown).joinToString(", "))
                if (stuck.size > prm.stuckNamesShown) sb.append(" ほか${stuck.size - prm.stuckNamesShown}名")
            }
            return sb.toString()
        }
    }
}
