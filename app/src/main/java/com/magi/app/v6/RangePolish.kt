package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random

/**
 * [頭打ち調査・findCovUChainのrangeAvoid用] 候補(staff)がfillShiftを1つ得ると自身のstaffRange上限
 * (rangeHi)を新たに超えるか。桒澤美幸のAｱ超過が研磨後も残る実例を追跡した結果、findCovUChainの
 * 候補選定がコスト無視（構造的に妥当な最初の1件で確定）なため、「別の職員の新規high違反」で
 * 相殺され isBetter に却下される手を引き続けて頭打ちになるケースを確認。C3mnPolish/RangePolish/
 * C3RunPolishの3箇所で findCovUChain 呼出に渡し、そのような候補を後回し（除外はしない）にする。
 */
internal fun exceedsOwnRangeHi(p: Problem, work: Array<IntArray>, staff: Int, fillShift: Int): Boolean {
    val hi = p.rangeHi[staff][fillShift]
    if (hi == Int.MAX_VALUE) return false
    var c = 0
    for (jj in 0 until p.T) if (work[staff][jj] == fillShift) c++
    return c + 1 > hi
}


/** [ログから職員が分かるように] cellFamiliesに famKey を含むセルの職員名を重複なく列挙（登場順）。 */
internal fun stuckStaffNames(state: MagiState, cellFamilies: Map<String, List<String>>, famKey: String): List<String> {
    val out = LinkedHashSet<String>()
    for ((key, fams) in cellFamilies) {
        if (famKey !in fams) continue
        val i = key.split(",").getOrNull(0)?.toIntOrNull() ?: continue
        out.add(state.staff.getOrNull(i)?.name ?: "#$i")
    }
    return out.toList()
}


/**
 * 個人回数（staffRange の低/高）専用の修復・研磨パス。[V6HotfixPasses] から抽出（責務別の物理分割＝
 * AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * - [applyRangePolish]：手M（日単位最小費用完全割当）・手F（柔軟日フロー）・玉突き連鎖・
 *   結合探索を束ねる本体パス（このコードベース最大級の単一関数）。
 * - [minCostPerfectAssignment]：手M専用のHungarian法（最小費用二部マッチング）ソルバ。
 *
 * [exceedsOwnRangeHi]/[stuckStaffNames] は本ファイルの外（[V6HotfixPasses] 内の
 * applyC3mnPolish/applyC3nPolish/applyC3RunPolish/applyC3PatternPolish）からも呼ばれるため
 * top-level internal fun として本ファイルに置く（[C1WindowPolish] の inDeficientC1Window と同型）。
 */
internal object RangePolish {
    private const val DAY_MATCH_INF = 1_000_000_000_000L


    /**
     * 正方コスト行列の最小費用完全割当（Hungarian法、O(n^3)）。
     * 戻り値[row] = 採用した column。到達不能辺は [DAY_MATCH_INF]。
     *
     * RangePolishの日単位再割当で、現在日のシフト多重集合を一切変えずに、
     * 「誰がどのシフトを担当するか」だけを全員同時に最適化するために使う。
     */
    private fun minCostPerfectAssignment(
        cost: Array<LongArray>,
        inf: Long = DAY_MATCH_INF,
    ): IntArray? {
        val n = cost.size
        if (n == 0 || cost.any { it.size != n }) return null
        val u = LongArray(n + 1)
        val v = LongArray(n + 1)
        val p = IntArray(n + 1)
        val way = IntArray(n + 1)

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = LongArray(n + 1) { inf }
            val used = BooleanArray(n + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = inf
                var j1 = -1
                for (j in 1..n) {
                    if (used[j]) continue
                    val raw = cost[i0 - 1][j - 1]
                    if (raw < inf / 2) {
                        val cur = raw - u[i0] - v[j]
                        if (cur < minv[j]) {
                            minv[j] = cur
                            way[j] = j0
                        }
                    }
                    // raw が到達不能でも、別の交互木ノードから既に入った minv は比較対象。
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
                if (j1 < 0 || delta >= inf / 2) return null
                for (j in 0..n) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else if (j > 0 && minv[j] < inf / 2) {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)

            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        val out = IntArray(n) { -1 }
        for (j in 1..n) if (p[j] > 0) out[p[j] - 1] = j - 1
        if (out.any { it < 0 }) return null
        for (i in 0 until n) if (cost[i][out[i]] >= inf / 2) return null
        return out
    }


    /**
     * [RangePolish・玉突き連鎖の横展開その2] 個人別回数(staffRange low/high, SOFT重み90/25)専用の研磨パス。
     * 動機（桒澤美幸の実例, 実機ログ2026-07-19）: 担当可能シフトが「休/Aｱ/B1」のみで、休=10/10固定・
     * Aｱ上限2の職員が、実際にはAｱ=6(超過4)・休=12(超過2)のまま残っていた。彼女はB1担当が全職員中唯一
     * のため、既存のCyclicSwap/HF67(同日に相手シフトを持つ相手との交換が前提)では交換相手が構造的に
     * 存在せず、「相手を必要としない一方的な付け替え(休/Aｱ→B1)＋被覆が要る側だけ玉突きで埋め直す」手が
     * 必要——C3mnPolish(3.214.0)と同型の穴。
     *
     * [3.244.0 日単位最小費用完全割当]
     * 既存の「1セル付替え＋最初に見つかった玉突き連鎖」は、同日の直接交換が不可能なとき、
     * ランダム順で最初に完成した1本しか評価しない。そのため、桒澤美幸Aｱを代用可能な一般職員へ
     * 渡したくても、相手の現在シフトを美幸が担当できない局面では「候補なし」を繰り返しやすい。
     *
     * 新しい手Mは、対象日の現在シフト多重集合をtokenとして固定し、全職員への再割当をHungarian法で
     * 厳密に解く。2人交換に限定せず、3人・4人・任意長の循環を1回で発見する。日別の各シフト人数は
     * 完全保存されるためcovU/covOは構造的に不変。canDo・希望固定・禁止連続を辺の実行可能条件、
     * staffRange low/high・apt・変更人数を費用とし、最後はUnifiedViolationChecker＋isBetterで採否する。
     *
     * 代用候補はlow違反者だけに限定しない。target shiftを担当可能で上限余力のある全員を対象にし、
     * ①同shiftのlow、②担当可能シフト数が多い一般代用者、③上限余力、④現在回数が少ない順で試す。
     * 実データでは9シフト担当可能な8名が第1層、4シフト限定の専門職員は第2層となり、名前のハードコード
     * なしで「古泉・山本・福澤・佐藤・上條・金沢・モニカ・アリフ」を先に評価できる。
     *
     * アンカー: `report.countViolations`（"i,k"→"vio-low"/"vio-high"、3.210.0で重み優先解決済）から
     * 違反している(staff,shift)ペアを列挙。HIGH(超過)は当該シフトの保有日を他の担当可能シフトへ、
     * LOW(不足)は保有していない日のうち担当可能な1日をそのシフトへ、それぞれ付け替える。付け替えで
     * 空く/埋まる側の被覆(covUCell)が悪化する場合は`findCovUChain`で玉突き修復する（C1Polish手B/
     * C3mnPolishと同一パターン）。採否はisBetter(hard→weighted→total)keep-best＝退化不能。
     */
    fun applyRangePolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, seed: Long = 0x8A9EL, quantitativeRangeEval: Boolean = false, combineExhaustPairs: Boolean = false): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        val rng = Random(seed)
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        // [ログから職員が分かるように] 対象(staff,shift)の表示名。
        fun label(i: Int, k: Int) = "${state.staff.getOrNull(i)?.name ?: "#$i"} ${state.shifts.getOrNull(k)?.kigou ?: k.toString()}"
        val fixedNames = ArrayList<String>()
        var dayMatchingApplied = 0
        var flexibleDayApplied = 0

        // [頭打ちの理由を可視化] 対象(staff,shift)ごとに、何が原因で付け替えが不成立だったかを集計。
        //   希望固定=movableで即除外・禁止連続=makesForbiddenRunで即除外・候補なし=findCovUChainがnull・
        //   range後回し=findCovUChainは成立したが使った候補がrangeAvoid該当(=自身の新規high違反を招く)
        //   だった・不採用=chainは成立したがisBetterに拒否された、の5分類。最も多い理由を「残存:」へ表示。
        val blockStats = HashMap<Pair<Int, Int>, MutableMap<String, Int>>()
        // [不採用の主因, 3.302.0] C1Polish と同じく、拒否された候補が重み付きで最も壊した族を併記する。
        val culpritStats = HashMap<Pair<Int, Int>, MutableMap<String, Int>>()
        // [3.358.0/実機ログ起因] 「希望固定×16」「禁止連続×9」は**どの日か**が出ず、直しに行けなかった
        //   （ForbiddenDiag は同じ理由で日付を名指ししている＝そちらだけ行動につながる形だった）。
        //   日で決まる2理由だけ実日付を集める。件数は延べ・日は重複なし。
        val blockDays = HashMap<Pair<Int, Int>, MutableMap<String, MutableSet<Int>>>()
        // [3.358.0] 日番号を「M/D」へ。startDate が読めなければ「N日目」で妥協する（ログ専用）。
        val start0 = runCatching { java.time.LocalDate.parse(state.startDate) }.getOrNull()
        fun dayLabel(j: Int): String =
            start0?.plusDays(j.toLong())?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: "${j + 1}日目"
        fun recordBlock(
            target: Pair<Int, Int>, reason: String,
            after: ViolationReport? = null, before: ViolationReport? = null, day: Int? = null,
        ) {
            blockStats.getOrPut(target) { HashMap() }.merge(reason, 1, Int::plus)
            if (day != null) blockDays.getOrPut(target) { HashMap() }.getOrPut(reason) { LinkedHashSet() }.add(day)
            if (after != null && before != null) {
                worstWorsenedFamily(after, before)?.let { culpritStats.getOrPut(target) { HashMap() }.merge(it, 1, Int::plus) }
            }
        }
        // [汎用玉突き結合フレームワーク, 3.249.0] tryRelocate が単独では不採用だった候補を蓄積し
        //   末尾で束ねる（手M/手Fは既にそれ自体が多職員同時最適化のため対象外＝スコープ限定）。
        val combinable = ArrayList<CombinatorialRepair.Candidate>()

        // [玉突き連鎖つき1セル付け替え] day j の staff i を fromK から toK へ動かす。fromK 側の被覆が
        //   悪化するなら findCovUChain で埋め直す。採用ならtrue（bestRep/appliedは呼び出し側で更新済み）。
        fun tryRelocate(target: Pair<Int, Int>, i: Int, j: Int, fromK: Int, toK: Int): Boolean {
            if (!movable(i, j)) { recordBlock(target, "希望固定", day = j); return false }
            if (p.makesForbiddenRun(work, i, j, toK)) { recordBlock(target, "禁止連続", day = j); return false }
            var cnt = 0
            for (s in 0 until p.S) if (work[s][j] == fromK) cnt++
            val needsChain = p.covUCell(fromK, j, cnt - 1) > p.covUCell(fromK, j, cnt)
            // [厳密ピン保護] i(・玉突き相手)の回数変更がstaffRange厳密ピン(lo==hi)を新たに崩す候補は
            //   不採用にする（keep-best/重みは不変・追加ガードのみ）。
            val workBeforeRelocate = work.copy2D()
            work[i][j] = toK
            if (!needsChain) {
                val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                if (adoptionGate(p, workBeforeRelocate, work, rep, bestRep, pinBlocks).accepted) { bestRep = rep; applied++; return true }
                work[i][j] = fromK
                combinable.add(CombinatorialRepair.Candidate(
                    listOf(intArrayOf(i, j, toK)), "tryRelocate", label(target.first, target.second)))
                if (betterReport(rep, bestRep)) recordBlock(target, C1PlateauDiagnosis.REASON_PIN)
                else recordBlock(target, "不採用", after = rep, before = bestRep)
                return false
            }
            val chain = findCovUChain(p, work, fromK, j, rng, exclude = i,
                rangeAvoid = { st, fk -> exceedsOwnRangeHi(p, work, st, fk) })
            if (chain == null) { work[i][j] = fromK; recordBlock(target, "候補なし"); return false }
            val usedAvoided = chain.any { mv -> exceedsOwnRangeHi(p, work, mv[0], mv[2]) }
            val oldVals = IntArray(chain.size) { work[chain[it][0]][chain[it][1]] }
            chain.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
            val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
            if (adoptionGate(p, workBeforeRelocate, work, rep, bestRep, pinBlocks).accepted) { bestRep = rep; applied++; return true }
            for (idx in chain.indices) work[chain[idx][0]][chain[idx][1]] = oldVals[idx]
            work[i][j] = fromK
            combinable.add(CombinatorialRepair.Candidate(
                listOf(intArrayOf(i, j, toK)) + chain, "tryRelocate", label(target.first, target.second)))
            when {
                usedAvoided -> recordBlock(target, "range後回し")
                betterReport(rep, bestRep) -> recordBlock(target, C1PlateauDiagnosis.REASON_PIN)
                else -> recordBlock(target, "不採用", after = rep, before = bestRep)
            }
            return false
        }

        // [複数ターゲット同時解決=ユーザー指示「賢く深く網羅的に」・grilling確定] 同一シフトkについて
        //   high(超過)のhiとlow(不足)のloが両方存在する場合、findCovUChainの玉突き探索を経由せず、
        //   直接のペアスワップ(hiのk保有日を1日、loへ振替え・loの元シフトをhiが引き受ける)を最優先で
        //   試す。被覆(covU/covO)は完全保存(同日2者の役割入替のみ)のため、玉突き連鎖が構造的に見つから
        //   ない(=「候補なし」)局面でも確実に解決できる（桒澤美幸のAｱ超過×他職員のAｱ不足のような、
        //   同一シフトの過不足ペアに直接効く。RangePolishの`findCovUChain`頭打ちを回避する第2の経路）。
        fun tryPairSwap(hi: Int, k: Int, lo: Int): Boolean {
            for (j in 0 until p.T) {
                if (shouldStop()) return false
                if (work[hi][j] != k || !movable(hi, j) || !movable(lo, j)) continue
                val loK = work[lo][j]
                if (loK == k || loK !in 0 until p.K) continue
                if (!p.mayPlace(hi, loK) || !p.mayPlace(lo, k)) continue
                if (p.makesForbiddenRun(work, hi, j, loK) || p.makesForbiddenRun(work, lo, j, k)) continue
                val workBeforeSwap = work.copy2D()
                work[hi][j] = loK; work[lo][j] = k
                val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                if (adoptionGate(p, workBeforeSwap, work, rep, bestRep, pinBlocks).accepted) { bestRep = rep; applied++; return true }
                work[hi][j] = k; work[lo][j] = loK
            }
            return false
        }

        /**
         * 手M: 対象日の全職員を最小費用完全割当で同時に組み替える。
         *
         * - `hi` は当該日の k を必ず手放す。
         * - `receiver` は当該日の k を必ず受け取る。
         * - その日のシフトtokenは並べ替えるだけなので日別人数は完全保存。
         * - receiverごとに完全割当を解き、full checkerで最良の1案だけ採用。
         */
        fun tryExactDayMatching(target: Pair<Int, Int>, hi: Int, k: Int): Boolean {
            if (p.S <= 1 || p.T <= 0) return false

            val counts = Array(p.S) { IntArray(p.K) }
            for (i in 0 until p.S) for (j in 0 until p.T) {
                val kk = work[i][j]
                if (kk in 0 until p.K) counts[i][kk]++
            }
            val flex = IntArray(p.S) { p.allowedShiftsForStaff(it).size }

            fun rangePenalty(i: Int, kk: Int, count: Int): Long {
                var out = 0L
                val lo = p.rangeLo[i][kk]
                val hiLim = p.rangeHi[i][kk]
                // [3.522.0] low 90→120。
                if (lo != Int.MIN_VALUE && count < lo) out += (lo - count).toLong() * 120L
                if (hiLim != Int.MAX_VALUE && count > hiLim) out += (count - hiLim).toLong() * 25L
                return out
            }

            fun rowCost(i: Int, oldK: Int, newK: Int): Long {
                var out = 0L
                for (kk in 0 until p.K) {
                    var c = counts[i][kk]
                    if (newK != oldK) {
                        if (kk == oldK) c--
                        if (kk == newK) c++
                    }
                    out += rangePenalty(i, kk, c)
                    val apt = p.apt[i][kk]
                    if (apt >= 0) out += (if (c >= apt) c - apt else apt - c).toLong() * 4L  // [3.522.0] apt 1→4
                }
                // 同品質なら短い循環を優先し、不要な大規模入替えを避ける。
                if (newK != oldK) out += 2L
                // target shiftの偏在を軽く抑える。明示rangeが無い一般代用者同士のtie-break。
                if (newK == k) out += counts[i][k].toLong()
                return out
            }

            fun receiverRoom(i: Int): Int {
                val hiLim = p.rangeHi[i][k]
                return if (hiLim == Int.MAX_VALUE) 10_000 else hiLim - counts[i][k]
            }

            data class DayPlan(
                val day: Int,
                val shifts: IntArray,
                val report: ViolationReport,
                val changed: Int,
                val heuristic: Long,
            )

            var bestPlan: DayPlan? = null
            var trials = 0
            // 実データ10名×31日では全候補を網羅。大規模データでも後処理予算を食い潰さない上限。
            val maxTrials = 128

            for (j in 0 until p.T) {
                if (shouldStop() || trials >= maxTrials) break
                if (work[hi][j] != k || !movable(hi, j)) continue
                val tokens = IntArray(p.S) { work[it][j] }
                // [3.278.0/監査修正] -1(正規化センチネル)トークンを含む日は当該列が全行INF＝Hungarianが必ず
                //   null になるのに、旧実装は receiver 1件ごとに trials(上限128)を浪費していた。日ごと事前スキップ。
                if (tokens.any { it !in 0 until p.K }) continue

                val rawReceivers = (0 until p.S).filter { r ->
                    r != hi &&
                        work[r][j] != k &&
                        movable(r, j) &&
                        p.mayPlace(r, k) &&
                        receiverRoom(r) > 0
                }
                if (rawReceivers.isEmpty()) continue
                val maxFlex = rawReceivers.maxOf { flex[it] }
                val receivers = rawReceivers.sortedWith(
                    compareByDescending<Int> { if (bestRep.countViolations["$it,$k"] == "vio-low") 1 else 0 }
                        .thenByDescending { if (flex[it] >= maxFlex - 1) 1 else 0 }
                        .thenByDescending { receiverRoom(it) }
                        .thenBy { counts[it][k] }
                        .thenBy { it },
                )

                for (receiver in receivers) {
                    if (shouldStop() || trials++ >= maxTrials) break
                    val cost = Array(p.S) { LongArray(p.S) { DAY_MATCH_INF } }
                    for (i in 0 until p.S) {
                        val oldK = work[i][j]
                        for (tokenIdx in 0 until p.S) {
                            val newK = tokens[tokenIdx]
                            if (newK !in 0 until p.K) continue
                            if (i == hi && newK == k) continue
                            if (i == receiver && newK != k) continue
                            if (newK != oldK) {
                                // [3.417.0] 旧: 記号が「希」のシフトを割当先から外していた（3.278.0）。撤去の根拠3点。
                                //   ①**主張が実装されていない**: コメントは「最適化が自由生成しない」と書いていたが、
                                //     このガードは研磨3箇所にしかなく、探索本体（SA/ALNS の randomAllowedCell・
                                //     destroyRepair・findTargetedFix 等）は `allowedShiftsForStaff` から選ぶので素通り
                                //     ＝方針として機能していなかった（HF77: コメント≠実装）。
                                //   ②**実測で中立**: 「希」を含む唯一の実データ blocked_covu_state（希望10件＝盤面10セル）で
                                //     ガードは 1686 回発火するが、外すと後処理研磨の結果は hard=4/total=311/weighted=34149 と
                                //     **バイト一致**。弾いていた候補は目的関数側でも全て負けていた。フル30秒でも希望外の
                                //     「希」生成は 0 件（この職場では休が lo==hi の厳密ピンで9/10名固定＋勤務側に需要があり、
                                //     「希望外の希」はデータ側の制約が既に禁じている＝中立な仕組みが機能している）。
                                //   ③**別の職場では黙って効かない**: 記号が「希望」「W」等なら同じ意図でも一切適用されない。
                                if (!movable(i, j) || !p.mayPlace(i, newK)) continue
                                work[i][j] = newK
                                val badRun = p.makesForbiddenRun(work, i, j, newK)
                                work[i][j] = oldK
                                if (badRun) continue
                            }
                            cost[i][tokenIdx] = rowCost(i, oldK, newK)
                        }
                    }

                    val assignment = minCostPerfectAssignment(cost) ?: continue
                    val newDay = IntArray(p.S) { i -> tokens[assignment[i]] }
                    if (newDay[hi] == k || newDay[receiver] != k) continue
                    var changed = 0
                    var heuristic = 0L
                    // [厳密ピン保護] 完全割当は当日のトークンを全職員で並べ替えるため、複数職員の回数を
                    //   同時に変えうる。staffRange厳密ピン(lo==hi)を新たに崩す日案は不採用にする。
                    val workBeforeDayMatch = work.copy2D()
                    for (i in 0 until p.S) {
                        if (newDay[i] != tokens[i]) changed++
                        heuristic += cost[i][assignment[i]]
                        work[i][j] = newDay[i]
                    }
                    val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                    // [3.475.0/論理監査] 素の exactPinRegression では「ピンだけが理由で却下した改善手」が
                    //   PinBlockAttribution に計上されず、UI の「少なくともN回」が過少だった（同ファイルの
                    //   tryRelocate/tryPairSwap は blocksImproving 経由で計上済み＝手M/手F だけ非対称）。
                    val gateM = adoptionGate(p, workBeforeDayMatch, work, rep, bestRep, pinBlocks)
                    val improving = gateM.better; val pinBad = gateM.pinBad
                    for (i in 0 until p.S) work[i][j] = tokens[i]

                    if (!improving || pinBad) continue
                    val oldBest = bestPlan
                    val betterPlan = oldBest == null ||
                        betterReport(rep, oldBest.report) ||
                        (rep.hard == oldBest.report.hard &&
                            rep.total == oldBest.report.total &&
                            kotlin.math.abs(rep.weightedScore - oldBest.report.weightedScore) <= 1e-6 &&
                            (heuristic < oldBest.heuristic ||
                                (heuristic == oldBest.heuristic && changed < oldBest.changed)))
                    if (betterPlan) bestPlan = DayPlan(j, newDay, rep, changed, heuristic)
                }
            }

            val plan = bestPlan
            if (plan == null) {
                recordBlock(target, "日割当候補なし")
                return false
            }
            for (i in 0 until p.S) work[i][plan.day] = plan.shifts[i]
            bestRep = plan.report
            applied++
            dayMatchingApplied++
            return true
        }

        /**
         * 手F: 日別シフト多重集合も変えられる最小費用フロー。
         *
         * 手Mは「その日に既に存在するシフトtokenの並替え」なので、美幸Aｱ→B1のように
         * その日にB1 tokenが存在しないケースを表現できない。手Fは各職員から担当可能シフトへ辺を張り、
         * シフト側の1人目・2人目…にcovU/covOの限界費用を与える。これにより
         *   美幸 Aｱ→B1 ＋ 別職員 休/C系→Aｱ
         * のような、日別人数を変える置換を1回の厳密最適化で作る。
         *
         * - 希望/管理者固定セルは現在シフト以外へ移動不可。
         * - 変更先はcanDo必須。希望休「希」は新規生成しない。
         * - c3nはmakesForbiddenRunで辺を除外。ただし直接は禁止連続でも、隣接日(j±1)を本人が
         *   調整すれば崩せる場合は`tryFixForbiddenRunViaAdjacentDay`(3.163.0)で救済し、辺を生かす
         *   （3.246.0・「隣接日連動型」拡張。受取職員自身の隣接日にも同じ救済が及ぶ＝対称）。
         * - staffRange low/high、apt、変更セル数を職員辺費用へ入れる。
         * - covU/covOは人数qに対する凸罰則の限界費用としてシフト→sink辺へ入れる。
         * - 最終採否は必ずUnifiedViolationChecker＋isBetter。近似費用だけでは採用しない
         *   （隣接日の追加手・玉突きも含めた盤面全体で1回評価）。
         */
        fun tryFlexibleDayFlow(
            target: Pair<Int, Int>,
            victim: Int,
            forbiddenK: Int,
            candidateDays: IntArray,
        ): Boolean {
            if (p.S <= 0 || p.K <= 0) return false
            val counts = Array(p.S) { IntArray(p.K) }
            for (i in 0 until p.S) for (j in 0 until p.T) {
                val kk = work[i][j]
                if (kk in 0 until p.K) counts[i][kk]++
            }

            fun rangeAndAptCost(i: Int, oldK: Int, newK: Int): Long {
                var out = 0L
                for (kk in 0 until p.K) {
                    var c = counts[i][kk]
                    if (newK != oldK) {
                        if (kk == oldK) c--
                        if (kk == newK) c++
                    }
                    val lo = p.rangeLo[i][kk]
                    val hi = p.rangeHi[i][kk]
                    // [3.522.0] low 90→120, apt 1→4。
                    if (lo != Int.MIN_VALUE && c < lo) out += (lo - c).toLong() * 120L
                    if (hi != Int.MAX_VALUE && c > hi) out += (c - hi).toLong() * 25L
                    val a = p.apt[i][kk]
                    if (a >= 0) out += kotlin.math.abs(c - a).toLong() * 4L
                }
                if (newK != oldK) out += 2L
                return out
            }

            // [3.522.0] covU 8000→10000, covO 5→10。MirrorKeys の重み階層と整合させた限界費用のため同時に変更。
            fun dayPenalty(k: Int, j: Int, q: Int): Long =
                p.covUCell(k, j, q).toLong() * 10000L + p.covOCell(k, j, q).toLong() * 10L

            data class FlowPlan(
                val day: Int,
                val assignment: IntArray,
                val report: ViolationReport,
                val changed: Int,
                val flowCost: Long,
                val extras: List<IntArray>,
            )

            var bestPlan: FlowPlan? = null
            val days = candidateDays.filter { it in 0 until p.T }.distinct()
            for (j in days) {
                if (shouldStop()) break
                if (work[victim][j] != forbiddenK || !movable(victim, j)) continue
                val oldDay = IntArray(p.S) { work[it][j] }
                // [3.246.0 隣接日連動] (i,newK)ペア単位で「直接は禁止連続だが隣接日調整で救済できるか」を
                // メモ化。j±1は本ループの間ずっと不変(day-jのtrialは他日を触らない)なので日jの間は再利用可。
                val adjacentFix = HashMap<Pair<Int, Int>, List<IntArray>>()

                // primary costを1024倍し、下位10bitだけを決定的tie-breakに使う。
                // 8試行してc42/c1等の非分離制約に対する代替案もfull checkerへ渡す。
                for (trial in 0 until 8) {
                    if (shouldStop()) break
                    val staffCost = Array(p.S) { LongArray(p.K) { FlexibleDayFlow.INF } }
                    for (i in 0 until p.S) {
                        val oldK = oldDay[i]
                        for (newK in 0 until p.K) {
                            if (i == victim && newK == forbiddenK) continue
                            val changed = newK != oldK
                            if (changed) {
                                // [3.417.0] 記号「希」を割当先から外すガードを撤去（根拠は手M側の同種箇所に記載）。
                                if (!movable(i, j) || !p.mayPlace(i, newK)) continue
                                work[i][j] = newK
                                val badRun = p.makesForbiddenRun(work, i, j, newK)
                                work[i][j] = oldK
                                if (badRun) {
                                    val key = i to newK
                                    val fix = adjacentFix.getOrPut(key) {
                                        tryFixForbiddenRunViaAdjacentDay(p, work, i, j, newK, rng) ?: emptyList()
                                    }
                                    if (fix.isEmpty()) continue
                                }
                            } else if (i == victim && !p.mayPlace(i, newK)) {
                                // groupViol対象をそのまま残す辺は禁止。他職員の固定済み不正セルは
                                // この1手の実行可能性を壊さないため現状維持だけ許す。
                                continue
                            }
                            val primary = rangeAndAptCost(i, oldK, newK)
                            val tie = ((i * 131 + newK * 31 + trial * 17) and 1023).toLong()
                            staffCost[i][newK] = primary * 1024L + tie
                        }
                    }

                    val marginal = Array(p.K) { k ->
                        LongArray(p.S) { q0 ->
                            val q = q0 + 1
                            (dayPenalty(k, j, q) - dayPenalty(k, j, q - 1)) * 1024L
                        }
                    }
                    val solved = FlexibleDayFlow.solve(staffCost, marginal) ?: continue
                    if (solved.assignment[victim] == forbiddenK) continue

                    // 選ばれた(i,newK)のうち禁止連続の隣接日救済が要ったものを1件の候補として合流。
                    val extras = ArrayList<IntArray>()
                    for (i in 0 until p.S) {
                        val newK = solved.assignment[i]
                        if (newK == oldDay[i]) continue
                        adjacentFix[i to newK]?.let { extras.addAll(it) }
                    }

                    var changedCount = 0
                    // [厳密ピン保護] 柔軟日フローも当日の人数構成と隣接日調整(extras)を同時に変えるため、
                    //   複数職員の回数を同時に変えうる。staffRange厳密ピン(lo==hi)を新たに崩す案は不採用。
                    val workBeforeFlow = work.copy2D()
                    for (i in 0 until p.S) {
                        if (solved.assignment[i] != oldDay[i]) changedCount++
                        work[i][j] = solved.assignment[i]
                    }
                    val extraOld = IntArray(extras.size) { work[extras[it][0]][extras[it][1]] }
                    extras.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
                    val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                    // [3.475.0/論理監査] 手M と同じ理由で blocksImproving 経由に揃える（計上漏れの解消）。
                    val gateF = adoptionGate(p, workBeforeFlow, work, rep, bestRep, pinBlocks)
                    val improving = gateF.better; val pinBad = gateF.pinBad
                    for (idx in extras.indices) work[extras[idx][0]][extras[idx][1]] = extraOld[idx]
                    for (i in 0 until p.S) work[i][j] = oldDay[i]
                    if (!improving || pinBad) continue

                    val oldBest = bestPlan
                    val betterPlan = oldBest == null ||
                        betterReport(rep, oldBest.report) ||
                        (rep.hard == oldBest.report.hard &&
                            rep.total == oldBest.report.total &&
                            kotlin.math.abs(rep.weightedScore - oldBest.report.weightedScore) <= 1e-6 &&
                            (changedCount < oldBest.changed ||
                                (changedCount == oldBest.changed && solved.cost < oldBest.flowCost)))
                    if (betterPlan) bestPlan = FlowPlan(j, solved.assignment, rep, changedCount, solved.cost, extras)
                }
            }

            val plan = bestPlan
            if (plan == null) {
                recordBlock(target, "柔軟日割当候補なし")
                return false
            }
            for (i in 0 until p.S) work[i][plan.day] = plan.assignment[i]
            plan.extras.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
            bestRep = plan.report
            applied++
            flexibleDayApplied++
            return true
        }

        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false

            // [手F/groupViol] staffRangeのhigh表示に依存せず、担当不可セルを直接対象にする。
            // 添付データの美幸AｱはstaffRange[3,4]が無くてもgroupShift上で担当不可なのでここで5日全て拾う。
            val groupTargets = ArrayList<Triple<Int, Int, Int>>()
            for (i in 0 until p.S) for (j in 0 until p.T) {
                val k = work[i][j]
                if (k in 0 until p.K && !p.mayPlace(i, k)) groupTargets.add(Triple(i, j, k))
            }
            for ((i, j, k) in groupTargets) {
                if (shouldStop()) break
                if (work[i][j] != k || p.mayPlace(i, k)) continue
                val target = i to k
                if (!movable(i, j)) {
                    recordBlock(target, "担当不可セルが希望/管理者固定")
                    continue
                }
                if (tryFlexibleDayFlow(target, i, k, intArrayOf(j))) {
                    improved = true
                    fixedNames.add("${label(i, k)} ${j + 1}日")
                }
            }

            // [3.278.0/監査修正] pass 0 でも直前の groupTargets ループ(手F)が盤面を変更済み(improved)なら
            //   before は陳腐＝解消済みターゲットへの空振り・新規違反の見落としを防ぐため再検査する。
            val rep0 = if (pass == 0 && !improved) before else UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
            val highTargets = ArrayList<Pair<Int, Int>>()
            val lowTargets = ArrayList<Pair<Int, Int>>()
            for ((key, cls) in rep0.countViolations) {
                val parts = key.split(",")
                val i = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val k = parts.getOrNull(1)?.toIntOrNull() ?: continue
                when (cls) {
                    "vio-high" -> highTargets.add(i to k)
                    "vio-low" -> lowTargets.add(i to k)
                }
            }
            if (highTargets.isEmpty() && lowTargets.isEmpty()) break

            // HIGH(超過): shift k の保有日を他の担当可能シフトへ動かす。
            for ((i, k) in highTargets) {
                if (shouldStop()) break
                val target = i to k
                var done = false
                // [手M→手F] まず日別人数を保存する完全割当。無ければ日別人数も最適化するフローへ拡張。
                // 同じ(i,k)が上限を複数回超えていても、この1パス内で上限まで反復して落とす。
                val hiLim = p.rangeHi[i][k]
                var guard = 0
                while (hiLim != Int.MAX_VALUE && work[i].count { it == k } > hiLim && guard++ < p.T) {
                    val fixedOne = tryExactDayMatching(target, i, k) ||
                        tryFlexibleDayFlow(
                            target, i, k,
                            (0 until p.T).filter { j -> work[i][j] == k && movable(i, j) }.toIntArray(),
                        )
                    if (!fixedOne) break
                    improved = true
                    done = true
                    fixedNames.add(label(i, k))
                }
                if (done) continue
                // [複数ターゲット同時解決] まず同一シフトkのlow(不足)職員との直接ペアスワップを試す
                //   （findCovUChain経由の玉突きより優先＝被覆完全保存で確実に解決できる）。
                for ((lo, lk) in lowTargets) {
                    if (done || shouldStop()) break
                    if (lk != k || lo == i) continue
                    if (tryPairSwap(i, k, lo)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                }
                if (done) continue
                for (j in 0 until p.T) {
                    if (done || shouldStop()) break
                    if (work[i][j] != k) continue
                    for (alt in p.allowedShiftsForStaff(i)) {
                        if (done || shouldStop()) break
                        if (alt == k) continue
                        if (tryRelocate(target, i, j, k, alt)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                    }
                }
            }
            // LOW(不足): shift k を保有していない日のうち1日をshift kへ動かす。
            for ((i, k) in lowTargets) {
                if (shouldStop()) break
                if (!p.mayPlace(i, k)) continue
                val target = i to k
                var done = false
                // [複数ターゲット同時解決] まず同一シフトkのhigh(超過)職員との直接ペアスワップを試す
                //   （HIGHループで既に解決済みのペアはtryPairSwap内でその日を再訪しても無害＝重複コスト
                //   のみ）。
                for ((hi, hk) in highTargets) {
                    if (done || shouldStop()) break
                    if (hk != k || hi == i) continue
                    if (tryPairSwap(hi, k, i)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                }
                if (done) continue
                for (j in 0 until p.T) {
                    if (done || shouldStop()) break
                    val oldK = work[i][j]
                    if (oldK == k || oldK !in 0 until p.K) continue
                    if (tryRelocate(target, i, j, oldK, k)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                }
            }
            pass++
            if (!improved) break
        }
        // [汎用玉突き結合フレームワーク, 3.249.0] stuckNames より前に実行し、結合で解消した箇所が
        //   「残存」に残らないようにする。
        val rejectedOut = ArrayList<CombinatorialRepair.Candidate>()
        val rangeCombStats = CombinatorialRepair.Stats()
        bestRep = CombinatorialRepair.combineAndApply(
            state, work, bestRep, combinable.asReversed(), ::betterReport, shouldStop = shouldStop, stats = rangeCombStats, p = p, leftover = rejectedOut,
            exhaustPairs = combineExhaustPairs,
        )
        applied += rangeCombStats.combosAccepted
        // [ログから職員が分かるように・頭打ちの理由を可視化] 研磨後もなお残っている(staff,shift)を、
        //   最も多かった頭打ち理由(希望固定/禁止連続/候補なし/range後回し/不採用)付きで列挙。
        val stuckNames = bestRep.countViolations.entries
            .filter { it.value == "vio-high" || it.value == "vio-low" }
            .mapNotNull { (key, _) ->
                val parts = key.split(",")
                val i = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val k = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                val reasons = blockStats[i to k]
                val top = reasons?.maxByOrNull { it.value } ?: return@mapNotNull label(i, k)
                // [不採用の主因, 3.302.0] C1Polish と同型。「不採用」があれば主因族を上位2件併記。
                // [3.491.0/実機ログ起因] 旧: 主因は top が「不採用」のときだけ出していた。実データ（2026-10）で
                //   「古泉 健一 休(希望固定×30: …)」とだけ出て、**本当の壁＝不採用18手の主因 c1（窓の要件）**が
                //   ログから読めなかった（希望固定は 5日×6シフト=30 の延べ数で最多になっただけ）。
                //   最多理由の後ろに残りの理由も件数つきで並べ、不採用の主因は top に関わらず出す。
                val culprits = culpritStats[i to k]?.entries?.sortedByDescending { it.value }?.take(2)
                    ?.joinToString(" ") { "${it.key}:${it.value}" }
                    ?.let { if (it.isEmpty()) "" else " 主因 $it" } ?: ""
                // [3.358.0] 日で決まる理由（希望固定・禁止連続）は実日付を出す＝そのまま直しに行ける。
                val days = blockDays[i to k]?.get(top.key)?.sorted().orEmpty()
                val dayTxt = if (days.isEmpty()) "" else
                    ": " + days.take(6).joinToString("・") { dayLabel(it) } +
                        (if (days.size > 6) "ほか${days.size - 6}日" else "")
                val rest = reasons.entries.filter { it.key != top.key }.sortedByDescending { it.value }
                    .joinToString("") { r ->
                        "・${r.key}×${r.value}" + (if (r.key == "不採用") culprits else "")
                    }
                val topCulprit = if (top.key == "不採用") culprits else ""
                "${label(i, k)}(${top.key}×${top.value}$dayTxt$topCulprit$rest)"
            }
        val rangeCombSummary = rangeCombStats.summary()
        val logs = listOf(MirrorLog(tag = "RangePolish",
            message = "個人回数(low/high)玉突き研磨: low ${before.breakdown["low"] ?: 0}->${bestRep.breakdown["low"] ?: 0} / high ${before.breakdown["high"] ?: 0}->${bestRep.breakdown["high"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                "（日割当:$dayMatchingApplied / 柔軟日割当:$flexibleDayApplied）" +
                (if (applied == 0 && ((before.breakdown["low"] ?: 0) + (before.breakdown["high"] ?: 0)) > 0) " [頭打ち=改善手なし]" else "") +
                (if (fixedNames.isNotEmpty()) " 対象: ${fixedNames.joinToString(", ")}" else "") +
                (if (stuckNames.isNotEmpty()) " 残存: ${stuckNames.joinToString(", ")}" else "") +
                (if (rangeCombSummary.isNotEmpty()) " / $rangeCombSummary" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks, rejectedCandidates = rejectedOut)
    }


}
