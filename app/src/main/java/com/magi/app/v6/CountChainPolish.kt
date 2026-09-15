package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random

/**
 * [3.540.0/測定済み・既定OFF] 回数連鎖研磨: 個人上限/群目標の超過を起点に、同日の被覆保存巡回交換(k=2/3)を複数日で束ね、
 * 回数族＋連続系の負債が正味 ≤0 の連鎖だけを UnifiedViolationChecker で keep-best 採点する（採用基準は増やさない）。
 * 1 日ずつ採否を見る既存パスが取れない「相手に渡して別の日で返す」迂回を狙う（根拠＝厳密解との比較、docs/history 3.540.0）。
 */
internal object CountChainPolish {
    data class Config(
        val maxPasses: Int = 8,
        /** 連鎖の最大日数（＝巡回交換の数）。 */
        val maxDepth: Int = 6,
        /** 途中で許す回数族(low/high/apt)の負債（重み付き、起点比）。超える枝は展開しない。 */
        val maxDebt: Double = 60.0,
        /** 返済中の枝に限って許す負債の上限（休の厳密ピン 120 ＋ 相手の上限超過 25 程度を通す）。 */
        val maxLoanDebt: Double = 300.0,
        /** 1 ノードで展開する子（巡回交換）の上限。open が減る順に採り、同点は seed で並べる。 */
        val maxChildren: Int = 32,
        val maxEvaluations: Int = 3000,
        val maxNodes: Int = 150_000,
        val maxMillis: Long = 8_000L,
        val maxTargets: Int = 12,
    )

    /** (k, j) に必要数の指定が無い（need1/need2 とも空欄）＝1 セル増減しても covU/covO が動かない。 */
    private fun Problem.freeCoverage(k: Int, j: Int): Boolean = need1[k][j] < 0 && (!use2 || need2[k][j] < 0)

    /** 同日 j の巡回交換。staff[t] が oldShift[t] → newShift[t] へ変わる（被覆は不変）。 */
    private class Rotation(val day: Int, val staff: IntArray, val oldShift: IntArray, val newShift: IntArray)

    /** 起点。day>=0 なら人員過剰(covO)セル＝その日に staff が shift を手放す手から始める（回数超過の起点は day=-1）。 */
    private class Target(val staff: Int, val shift: Int, val excess: Int, val weight: Double, val day: Int = -1)

    fun applyCountChainPolish(
        state: MagiState,
        schedule: Array<IntArray>,
        config: Config = Config(),
        shouldStop: () -> Boolean = { false },
        seed: Long = 0xC0C4L,
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult {
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        var evaluations = 0
        var nodes = 0
        val deadline = System.nanoTime() + config.maxMillis * 1_000_000L
        val rng = Random(seed)
        val allowed = Array(p.S) { i -> BooleanArray(p.K).also { a -> for (k in p.allowedShiftsForStaff(i)) if (k in 0 until p.K) a[k] = true } }
        fun stopped() = shouldStop() || System.nanoTime() >= deadline || evaluations >= config.maxEvaluations || nodes >= config.maxNodes
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)

        fun targetsOf(counts: Array<IntArray>): List<Target> {
            val out = ArrayList<Target>()
            for (i in 0 until p.S) for (k in 0 until p.K) {
                val c = counts[i][k]
                val hi = p.rangeHi[i][k]
                val apt = p.apt[i][k]
                if (hi != Int.MAX_VALUE && c > hi) out.add(Target(i, k, c - hi, MirrorKeys.weightOf("high") * (c - hi)))
                else if (apt >= 0 && c > apt) out.add(Target(i, k, c - apt, MirrorKeys.weightOf("apt") * (c - apt)))
            }
            // 人員過剰(covO)セル: 過剰の日にそのシフトを持つ人（非希望）ごとに起点を立てる。
            val wCovO = MirrorKeys.weightOf("covO")
            for (j in 0 until p.T) for (k in 0 until p.K) {
                var got = 0
                for (i in 0 until p.S) if (work[i][j] == k) got++
                val over = p.covOCell(k, j, got)
                if (over <= 0) continue
                for (i in 0 until p.S) if (work[i][j] == k && movable(i, j)) out.add(Target(i, k, over, wCovO * over, day = j))
            }
            return out.sortedByDescending { it.weight }.take(config.maxTargets)
        }

        val wLow = MirrorKeys.weightOf("low"); val wHigh = MirrorKeys.weightOf("high"); val wApt = MirrorKeys.weightOf("apt")
        /** (s,k) の回数が c のときの回数族(low/high/apt)の重み付き罰則。連鎖の途中経過を採点する「回数の負債」の単位。 */
        fun pen(s: Int, k: Int, c: Int): Double {
            var v = 0.0
            if (p.rangeLo[s][k] != Int.MIN_VALUE && c < p.rangeLo[s][k]) v += wLow * (p.rangeLo[s][k] - c)
            if (p.rangeHi[s][k] != Int.MAX_VALUE && c > p.rangeHi[s][k]) v += wHigh * (c - p.rangeHi[s][k])
            if (p.apt[s][k] >= 0) v += wApt * kotlin.math.abs(c - p.apt[s][k])
            return v
        }

        val wC1 = MirrorKeys.weightOf("c1"); val wC3 = MirrorKeys.weightOf("c3"); val wC3m = MirrorKeys.weightOf("c3m"); val wC3mn = MirrorKeys.weightOf("c3mn")
        /** 職員 s の行だけで決まる連続系(c1/c3/c3m/c3mn)の重み付き罰則。`UnifiedViolationChecker` と同じ数え方（窓・run-deficit）。 */
        fun seqPenRow(s: Int, row: IntArray): Double {
            var v = 0.0
            for (c in p.cons1) {
                if (!p.canDo(s, c.shiftIdx) || c.day1 > p.T) continue
                var z = 0
                for (l in 0 until c.day1) if (row[l] == c.shiftIdx) z++
                var j = 0
                while (j <= p.T - c.day1) {
                    if (j > 0) { if (row[j - 1] == c.shiftIdx) z--; if (row[j + c.day1 - 1] == c.shiftIdx) z++ }
                    if (z < c.day2) v += wC1
                    j++
                }
            }
            fun fam(list: List<C3>, forbidden: Boolean, w: Double) {
                for (c in list) {
                    val seq = c.seq; val d = seq.size
                    if (d == 0 || d > p.T) continue
                    if (!forbidden && C3Run.isSingleShiftSeq(seq)) {
                        var r = 0; var j = 0
                        while (j <= p.T) {
                            val on = j < p.T && row[j] == seq[0]
                            if (on) r++ else if (r > 0) { if (d - r > 0) v += w * (d - r); r = 0 }
                            j++
                        }
                        continue
                    }
                    var j = 0
                    while (j <= p.T - d) {
                        if (row[j] == seq[0]) {
                            var z = 0
                            for (l in 1 until d) if (row[j + l] == seq[l]) z++
                            if (if (forbidden) z == d - 1 else z < d - 1) v += w
                        }
                        j++
                    }
                }
            }
            fam(p.cons3, false, wC3); fam(p.cons3m, false, wC3m); fam(p.cons3mn, true, wC3mn)
            return v
        }

        /** 起点 target について連鎖を探索し、見つかった最良盤面を返す（無ければ null）。 */
        fun searchChain(target: Target, counts: Array<IntArray>): Pair<Array<IntArray>, ViolationReport>? {
            val cur = work.copy2D()
            val delta = HashMap<Int, Int>()
            val usedDay = BooleanArray(p.T)
            var bestBoard: Array<IntArray>? = null
            var bestChainRep = bestRep
            fun key(s: Int, k: Int) = s * p.K + k
            val seqBase = DoubleArray(p.S) { seqPenRow(it, cur[it]) }
            val seqCur = seqBase.copyOf()
            fun entryDebt(s: Int, k: Int, v: Int) = pen(s, k, counts[s][k] + v) - pen(s, k, counts[s][k])
            /** 回数族の負債 ＋ 連続系の負債（行単位で厳密）＋ covO 起点なら過剰セルを外した分の利得。fair/weekly/c42 系は最終採点で決まる。 */
            fun debt(): Double {
                var d = 0.0
                for ((kk, v) in delta) if (v != 0) d += entryDebt(kk / p.K, kk % p.K, v)
                for (s in 0 until p.S) d += seqCur[s] - seqBase[s]
                if (target.day >= 0 && cur[target.staff][target.day] != target.shift) d -= MirrorKeys.weightOf("covO")
                return d
            }
            /** 次に直す (s,k)＝負債が最大の項。起点がまだ動いていなければ起点。 */
            fun worstOpen(): Pair<Int, Int>? {
                if ((delta[key(target.staff, target.shift)] ?: 0) == 0) return target.staff to target.shift
                var best: Pair<Int, Int>? = null; var bestD = 0.0
                for ((kk, v) in delta) { if (v == 0) continue; val d = entryDebt(kk / p.K, kk % p.K, v); if (d > bestD) { bestD = d; best = kk / p.K to kk % p.K } }
                return best
            }
            fun apply(r: Rotation, sign: Int) {
                for (t in r.staff.indices) {
                    val s = r.staff[t]
                    val from = if (sign > 0) r.oldShift[t] else r.newShift[t]
                    val to = if (sign > 0) r.newShift[t] else r.oldShift[t]
                    cur[s][r.day] = to
                    delta[key(s, from)] = (delta[key(s, from)] ?: 0) - 1
                    delta[key(s, to)] = (delta[key(s, to)] ?: 0) + 1
                }
                usedDay[r.day] = sign > 0
                for (s in r.staff) seqCur[s] = seqPenRow(s, cur[s])
            }
            /** (s,k) の負債を減らす同日巡回交換を列挙する。give=true なら s が k を手放す、false なら受け取る。 */
            fun children(s: Int, k: Int, give: Boolean, onlyDay: Int = -1): List<Rotation> {
                val out = ArrayList<Rotation>()
                for (j in 0 until p.T) {
                    if (onlyDay >= 0 && j != onlyDay) continue
                    if (usedDay[j] || !movable(s, j)) continue
                    val sk = cur[s][j]
                    if (give != (sk == k)) continue
                    // 被覆に必要数が無いシフト（B4/有など need 空欄の日）へは相手なしの 1 セル変換で手放せる／受け取れる。
                    for (k2 in 0 until p.K) {
                        if (k2 == sk || !allowed[s][k2]) continue
                        val to = if (give) k2 else k
                        val from = sk
                        if (give && !p.freeCoverage(to, j)) continue
                        if (!give && !p.freeCoverage(from, j)) continue
                        if (!give && to != k) continue
                        if (p.makesForbiddenRun(cur, s, j, to)) continue
                        out.add(Rotation(j, intArrayOf(s), intArrayOf(from), intArrayOf(to)))
                        if (!give) break
                    }
                    for (b in 0 until p.S) {
                        if (b == s || !movable(b, j)) continue
                        val bk = cur[b][j]
                        if (bk == sk) continue
                        if (!give && bk != k) continue
                        if (allowed[s][bk] && allowed[b][sk] && !p.makesForbiddenRun(cur, s, j, bk) && !p.makesForbiddenRun(cur, b, j, sk))
                            out.add(Rotation(j, intArrayOf(s, b), intArrayOf(sk, bk), intArrayOf(bk, sk)))
                        if (!allowed[s][bk] || p.makesForbiddenRun(cur, s, j, bk)) continue
                        for (c in 0 until p.S) {
                            if (c == s || c == b || !movable(c, j)) continue
                            val ck = cur[c][j]
                            if (ck == sk || ck == bk) continue
                            if (!allowed[b][ck] || !allowed[c][sk]) continue
                            if (p.makesForbiddenRun(cur, b, j, ck) || p.makesForbiddenRun(cur, c, j, sk)) continue
                            out.add(Rotation(j, intArrayOf(s, b, c), intArrayOf(sk, bk, ck), intArrayOf(bk, ck, sk)))
                        }
                    }
                }
                return out
            }
            val evalCap = evaluations + maxOf(200, config.maxEvaluations / maxOf(1, config.maxTargets))
            fun dfs(depth: Int, maxDepth: Int) {
                if (stopped() || evaluations >= evalCap) return
                nodes++
                val d = debt()
                // 回数族が正味で悪化していない連鎖だけ正式採点する（他族は checker が決める）。
                if (depth > 0 && d <= 0.0) {
                    evaluations++
                    val rep = UnifiedViolationChecker.check(state, cur, quantitativeRangeEval)
                    if (betterReport(rep, bestChainRep)) {
                        if (exactPinRegression(p, work, cur)) pinBlocks.record(p, work, cur)
                        else { bestChainRep = rep; bestBoard = cur.copy2D() }
                    }
                }
                if (depth >= maxDepth) return
                val (s, k) = worstOpen() ?: return
                val give = (delta[key(s, k)] ?: 0) >= 0
                val atRoot = s == target.staff && k == target.shift && (delta[key(s, k)] ?: 0) == 0
                val cands = children(s, k, give, onlyDay = if (atRoot) target.day else -1)
                if (cands.isEmpty()) return
                // 負債が小さくなる順、同点は乱択（seed で決定的）。
                val scored = cands.map { r -> apply(r, +1); val nd = debt(); apply(r, -1); Triple(nd, rng.nextInt(), r) }
                    .sortedWith(compareBy({ it.first }, { it.second }))
                var taken = 0
                for ((nd, _, r) in scored) {
                    if (taken >= config.maxChildren || stopped() || evaluations >= evalCap) break
                    // 負債の上限。超えていても「返済中」（負債が減る枝）だけは maxLoanDebt まで追う＝厳密ピン（休 lo=hi）の
                    //   日付け替えのように、一度大きく借りてすぐ返す 2 手を通すため。
                    if (nd > config.maxLoanDebt || (nd > config.maxDebt && nd >= d)) continue
                    taken++
                    apply(r, +1)
                    dfs(depth + 1, maxDepth)
                    apply(r, -1)
                }
            }
            // 反復深化: 浅い連鎖から順に探し、見つかった深さで止める（深さ優先が深い枝で予算を使い切るのを防ぐ）。
            for (dmax in 1..config.maxDepth) {
                dfs(0, dmax)
                if (bestBoard != null || stopped() || evaluations >= evalCap) break
            }
            val b = bestBoard ?: return null
            return b to bestChainRep
        }

        // 1 連鎖を採用するたびに回数が変わるので起点を取り直す。maxPasses は採用連鎖数の上限。
        while (applied < config.maxPasses && !stopped()) {
            val counts = countMatrix(p, work)
            var improved = false
            for (t in targetsOf(counts)) {
                if (stopped()) break
                val found = searchChain(t, counts) ?: continue
                for (i in 0 until p.S) System.arraycopy(found.first[i], 0, work[i], 0, p.T)
                bestRep = found.second
                applied++
                improved = true
                break
            }
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "CountChainPolish", message = "回数連鎖研磨: total ${before.total}->${bestRep.total} 採用${applied}連鎖 " +
            "high ${before.breakdown["high"] ?: 0}->${bestRep.breakdown["high"] ?: 0} apt ${before.breakdown["apt"] ?: 0}->${bestRep.breakdown["apt"] ?: 0} " +
            "評価${evaluations} ノード${nodes}"))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }
}
