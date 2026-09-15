package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * c3系連続規則(c3/c3m/c3mn/c3n)専用のブロック交換研磨。[V6HotfixPasses] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * - [applyC3SequencePolish]：2職員×連日(2-3日)のブロックを丸ごと交換する研磨。
 * - [applyBlockRotationPolish]：3職員×連日のブロックを「回転」させる研磨（c1系からも呼ばれる汎用版）。
 * - [StaffObjective]/[staffObjective]/[c3FamCount]：上記2パス専用の差分前フィルタ
 *   （フル checker を呼ぶ前の安価な近似判定。既知の限界は本ファイル内KDoc参照）。
 */
internal object C3RotationPolish {
    /**
     * [ソフト研磨・連続規則] c3(必須の並び)・c3m(推奨)・c3mn(回避)・c3n(禁止=HARD) はいずれも職員の
     * 連続日の並びで決まる。同日スワップ(循環交換)では1日しか変えられず多日パターンに届かないため、
     * 2職員 i,i' が 連続 W 日(W=2,3)を丸ごと交換する（各日の被覆＝人数が不変＝HARD維持）。両者の W日
     * パターンが入れ替わり、2〜3日にわたる並びを直せる。実目的関数で評価し改善時のみ採用（keep-best＝
     * 退化なし）。isBetter は HARD を最優先するため、c3n(禁止=HARD) の解消も同時に拾う。
     */
    fun applyC3SequencePolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, quantitativeRangeEval: Boolean = false): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        var skipped = 0     // [#5] 前フィルタでフル評価を省いた手数
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        val windows = intArrayOf(2, 3)   // 連続2日・3日（c3は最大5連日だが2-3日窓でほぼ捕捉）
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            // [違反セル指向] c3系で違反している職員のみを起点に絞る。c3は職員ごと→2者交換で改善する手は
            //   必ず違反職員を含む＝取りこぼし無し(ロスレス)。空なら即終了でコスト0。
            // [実バグ修正/applyC1WindowPolishと同根] rep0.violations（1セル=最重1クラスのみ）だと、
            //   c3系のマーク位置に c3n(HARD) 等の更に重い違反も同居する場合、そのセルの分類が上書きされ
            //   "vio-c3/c3m/c3mn"が消える。該当職員の全マーク位置が同様にシャドーイングされていると
            //   anchorStaffから丸ごと漏れ、一度も研磨が試されない。cellFamilies（1セルの全クラス保持）
            //   に切替え、上書きされても検出できるようにする。起点が広がるだけの後方互換な修正。
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
            val anchorStaff = HashSet<Int>()
            for ((key, fams) in rep0.cellFamilies) {
                if (fams.any { it == "vio-c3" || it == "vio-c3m" || it == "vio-c3mn" }) anchorStaff.add(key.substringBefore(",").toIntOrNull() ?: continue)
            }
            if (anchorStaff.isEmpty()) break
            for (w in windows) {
                if (p.T < w) continue
                for (j in 0..p.T - w) {
                    if (shouldStop()) break
                    for (i in 0 until p.S) {
                        // [監査(未レビュー領域再監査)] O(S^2)内側スキャンにも締切確認を追加（HF66/BlockRotationPolishと同型）。
                        if (shouldStop()) break
                        if ((0 until w).any { !movable(i, j + it) }) continue
                        for (i2 in i + 1 until p.S) {
                            if (i !in anchorStaff && i2 !in anchorStaff) continue   // 違反職員を含む対のみ
                            if ((0 until w).any { !movable(i2, j + it) }) continue
                            var feasible = true; var same = true
                            for (t in 0 until w) {
                                if (!p.mayPlace(i, work[i2][j + t]) || !p.mayPlace(i2, work[i][j + t])) { feasible = false; break }
                                if (work[i][j + t] != work[i2][j + t]) same = false
                            }
                            if (!feasible || same) continue
                            // [#5 差分前フィルタ] 同 sgrp かつ同 ssk の2者ブロック交換のみ前判定。
                            val canPre = p.sgrp[i] == p.sgrp[i2] && p.ssk[i] == p.ssk[i2]
                            val preObjective = if (canPre) staffObjective(p, work, i) + staffObjective(p, work, i2) else null
                            // [厳密ピン保護] ブロック交換はwindow内の日ごとにi/i2の自身のシフト回数を変えうる
                            //   （2者間で異なるシフトが混在する日がある限り）。staffRange厳密ピン(lo==hi)を
                            //   崩す候補は不採用にする（keep-best/重みは不変・追加ガードのみ）。
                            val workBeforeBlock = work.copy2D()
                            for (t in 0 until w) { val tmp = work[i][j + t]; work[i][j + t] = work[i2][j + t]; work[i2][j + t] = tmp }
                            if (canPre) {
                                val postObjective = staffObjective(p, work, i) + staffObjective(p, work, i2)
                                if (preObjective != null && !postObjective.isBetterThan(preObjective)) { for (t in 0 until w) { val tmp = work[i][j + t]; work[i][j + t] = work[i2][j + t]; work[i2][j + t] = tmp }; skipped++; continue }
                            }
                            val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                            if (adoptionGate(p, workBeforeBlock, work, rep, bestRep, pinBlocks).accepted) { bestRep = rep; applied++; improved = true }
                            else for (t in 0 until w) { val tmp = work[i][j + t]; work[i][j + t] = work[i2][j + t]; work[i2][j + t] = tmp }   // 巻き戻し
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "C3Polish",
            message = "連続規則c3系研磨(2者ブロック): c3 ${before.breakdown["c3"] ?: 0}->${bestRep.breakdown["c3"] ?: 0}" +
                " / c3m ${before.breakdown["c3m"] ?: 0}->${bestRep.breakdown["c3m"] ?: 0}" +
                " / c3mn ${before.breakdown["c3mn"] ?: 0}->${bestRep.breakdown["c3mn"] ?: 0}" +
                " / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回 (差分前フィルタで省略${skipped}手)"))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }


    /**
     * [ソフト研磨・c3系強化] c3/c3m/c3mn(連続規則)で違反しているセルを起点に、3職員×連日(2-3日)の
     * ブロック「回転」を試す。2者ブロック入替や同日k=3巡回では到達できない3者×窓の組替えを、各日の
     * (日,シフト)人数を保ったまま（=被覆/HARD不変）行い、実目的(UnifiedViolationChecker)で改善時のみ
     * 採用（keep-best＝退化なし）。重み・パラメータは不変。違反セル指向なので低コスト。
     * 2回の2者交換に分解すると中間で悪化するため山登りでは越えられない局面を、回転1手で跨ぐのが狙い。
     */
    /**
     * [ソフト研磨・3者回転] 指定クラス(anchorClasses)で違反しているセルを起点に、3職員×連日(2-3日)の
     * ブロック「回転」を試す。2者ブロック入替/同日k=3巡回では到達できない3者×窓の組替えを、各日の
     * (日,シフト)人数を保ったまま（=被覆/HARD不変）行い、実目的(UnifiedViolationChecker)で改善時のみ
     * 採用（keep-best＝退化なし）。c1・c3系どちらの違反起点にも使える汎用版。重み・パラメータ不変。
     * 2回の2者交換に分解すると中間で悪化するため山登りでは越えられない局面を、回転1手で跨ぐのが狙い。
     */
    fun applyBlockRotationPolish(state: MagiState, schedule: Array<IntArray>, anchorClasses: Set<String>, tag: String, maxPasses: Int = 2, shouldStop: () -> Boolean = { false }, quantitativeRangeEval: Boolean = false): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        var bestRep = before
        var applied = 0
        var skipped = 0     // [#5] 前フィルタでフル評価を省いた手数(有効性ログ用)
        // [監査で発見・3.270.0] p.wish[i][j]<0 は「希望が一切ない」判定で、実現不能な希望
        //   (canDo(i,wish)==false)まで動かせないと誤判定していた（3.183.0 LightMirrorOptimizer と
        //   同型のバグ）。実現不能な希望はpref計上上も定数=動かして良い＝canDoガード込みの
        //   wishLocked が正しい判定。安全側（isBetter/checkerが最終ゲート）で候補が広がるのみ。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        val windows = intArrayOf(2, 3)
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            // 指定クラスで違反している職員(=回転の起点)を収集。無ければ即終了（コスト0）。
            // [実バグ修正/applyC1WindowPolishと同根] rep0.violations（1セル=最重1クラスのみ）だと、
            //   anchorClassesのマーク位置に更に重い他族が同居する場合そのセルの分類が上書きされ検出漏れ
            //   になる。cellFamilies（1セルの全クラス保持）に切替え、上書きされても検出できるようにする。
            //   起点が広がるだけの後方互換な修正（C1Rotate/C3Rotate 両呼出に共通して適用される）。
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
            val anchorStaff = HashSet<Int>()
            for ((key, fams) in rep0.cellFamilies) {
                if (fams.any { it in anchorClasses }) anchorStaff.add(key.substringBefore(",").toIntOrNull() ?: continue)
            }
            if (anchorStaff.isEmpty()) break
            var improved = false
            for (w in windows) {
                if (p.T < w) continue
                for (j in 0..p.T - w) {
                    if (shouldStop()) break
                    // この窓で全日movableな職員のみ回転対象（同一3名を各日で回す＝日内人数不変）。
                    val cand = (0 until p.S).filter { i -> (0 until w).all { movable(i, j + it) } }
                    if (cand.size < 3) continue
                    for (ai in cand) {
                        if (shouldStop()) break   // [予算ガード] 締切後は O(cand^3) の全候補フル評価を走り切らせない(HF66=2.65.0と同方針)。
                        if (ai !in anchorStaff) continue
                        for (bi in cand) {
                            if (shouldStop()) break   // [予算ガード] 内側スキャンでも締切確認しバーストを O(cand) 以内に抑える。
                            if (bi == ai) continue
                            for (ci in cand) {
                                if (ci == ai || ci == bi) continue
                                // 回転 ai<-bi, bi<-ci, ci<-ai が各日で担当可能か。
                                var feasible = true
                                for (t in 0 until w) {
                                    if (!p.mayPlace(ai, work[bi][j + t]) || !p.mayPlace(bi, work[ci][j + t]) || !p.mayPlace(ci, work[ai][j + t])) { feasible = false; break }
                                }
                                if (!feasible) continue
                                val sa = IntArray(w) { work[ai][j + it] }
                                val sb = IntArray(w) { work[bi][j + it] }
                                val sc = IntArray(w) { work[ci][j + it] }
                                // [#5 差分前フィルタ] 同 sgrp かつ同 ssk の手のみ前判定(群/スキル群/被覆/pref不変
                                //   →関与3名の局所目的が改善しなければ全体目的も改善しえない)。採用はフル評価が担う=安全。
                                val canPre = p.sgrp[ai] == p.sgrp[bi] && p.sgrp[bi] == p.sgrp[ci] &&
                                    p.ssk[ai] == p.ssk[bi] && p.ssk[bi] == p.ssk[ci]
                                val preObjective = if (canPre) {
                                    staffObjective(p, work, ai) + staffObjective(p, work, bi) + staffObjective(p, work, ci)
                                } else null
                                // [厳密ピン保護] 3者回転もwindow内で各職員の自身のシフト回数を変えうるため、
                                //   staffRange厳密ピン(lo==hi)を崩す候補は不採用にする（keep-best/重みは不変）。
                                val workBeforeRotate = work.copy2D()
                                for (t in 0 until w) { work[ai][j + t] = sb[t]; work[bi][j + t] = sc[t]; work[ci][j + t] = sa[t] }
                                if (canPre) {
                                    val postObjective = staffObjective(p, work, ai) + staffObjective(p, work, bi) + staffObjective(p, work, ci)
                                    if (preObjective != null && !postObjective.isBetterThan(preObjective)) { for (t in 0 until w) { work[ai][j + t] = sa[t]; work[bi][j + t] = sb[t]; work[ci][j + t] = sc[t] }; skipped++; continue }
                                }
                                val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                                if (adoptionGate(p, workBeforeRotate, work, rep, bestRep, pinBlocks).accepted) { bestRep = rep; applied++; improved = true }
                                else for (t in 0 until w) { work[ai][j + t] = sa[t]; work[bi][j + t] = sb[t]; work[ci][j + t] = sc[t] }   // 巻き戻し
                            }
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = tag,
            message = "$tag 3者回転研磨: c1 ${before.breakdown["c1"] ?: 0}->${bestRep.breakdown["c1"] ?: 0}" +
                " / c3 ${before.breakdown["c3"] ?: 0}->${bestRep.breakdown["c3"] ?: 0}" +
                " / c3m ${before.breakdown["c3m"] ?: 0}->${bestRep.breakdown["c3m"] ?: 0}" +
                " / c3mn ${before.breakdown["c3mn"] ?: 0}->${bestRep.breakdown["c3mn"] ?: 0}" +
                " / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回 (差分前フィルタで省略${skipped}手)"))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }

    /**
     * C3 系ブロック研磨の低コストな局所目的。公式の [betterReport] と同じ
     * HARD → weightedScore → total 順で比較する。
     *
     * apt/fair/weekly はここでは数えないため、この前フィルタは改善手を取りこぼし得るが、
     * 最終採否を誤ることはない。重みは数値を複製せず [MirrorKeys] を単一ソースにする。
     */
    internal data class StaffObjective(
        val hard: Long,
        val weighted: Double,
        val total: Long,
    ) {
        operator fun plus(other: StaffObjective): StaffObjective = StaffObjective(
            hard + other.hard,
            weighted + other.weighted,
            total + other.total,
        )

        internal fun isBetterThan(other: StaffObjective): Boolean = when {
            hard != other.hard -> hard < other.hard
            weighted != other.weighted -> weighted < other.weighted
            else -> total < other.total
        }
    }


    /**
     * ブロック交換・3者回転の**差分前フィルタ**。同 sgrp かつ同 ssk の参加者だけで使い、
     * 「その職員たちの部分目的が改善しないなら、フル checker を呼ばずに捨てる」ための近似。
     *
     * **既知の近似2つ**（3.84.0 から「報告のみ」で残っていた項目）:
     *  - c3/c3m を **窓の#fire** で数える。チェッカーは単一シフト連を `C3Run.rowDeficit`
     *    （run-deficit）で評価するので、単一シフト連のルールではモデルが違う。
     *  - apt/fair/weekly を集計しない（群平均・曜日バケットが要るため）。それらだけが改善する手はこぼす。
     *
     * [3.349.1/実測] どちらも **このデータでは一度も良い候補を落としていない**。捨てた候補すべてに
     * フル checker を当てて「本来なら採用されたか」を数えたところ、**golden 235件・user 899件・
     * real 896件の skip に対し採用相当は 0件**。捨てるのは checker も却下する候補ばかりで、
     * 近似は inert。よってモデルを揃える改修はしない（測れる利得が無い＝3.290.0/3.310.1 と同じ判断）。
     * 落としても keep-best は無関係なので**正しさには元から影響しない**（機会損失だけが論点だった）。
     */
    private fun staffObjective(p: Problem, sched: Array<IntArray>, i: Int): StaffObjective {
        var total = 0L; var weighted = 0.0
        val cnt = IntArray(p.K)                                   // 期間内シフト回数(c2/low/high 用)
        for (j in 0 until p.T) { val k = sched[i][j]; if (k in 0 until p.K) cnt[k]++ }
        for (c in p.cons1) {                                      // c1: d日窓で shiftIdx が day2 回未満
            if (!p.canDo(i, c.shiftIdx)) continue
            var j = 0
            while (j <= p.T - c.day1) {
                var z = 0
                for (l in 0 until c.day1) if (sched[i][j + l] == c.shiftIdx) z++
                if (z < c.day2) { total++; weighted += MirrorKeys.weightOf("c1") }
                j++
            }
        }
        for (c in p.cons2) if (p.canDo(i, c.shiftIdx) && cnt[c.shiftIdx] < c.count) { total++; weighted += MirrorKeys.weightOf("c2") } // c2
        for (k in 0 until p.K) {                                  // low/high: 回数レンジ(不足/超過「量」を加算)
            val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]; val n = cnt[k]
            if (lo != Int.MIN_VALUE && lo != 0 && p.canDo(i, k) && n < lo) { val d = (lo - n).toLong(); total += d; weighted += d * MirrorKeys.weightOf("low") }
            if (hi != Int.MAX_VALUE && n > hi) { val d = (n - hi).toLong(); total += d; weighted += d * MirrorKeys.weightOf("high") }
        }
        val c3nC = c3FamCount(p, sched, i, p.cons3n, true)        // c3n は HARD
        val c3C = c3FamCount(p, sched, i, p.cons3, false)
        val c3mC = c3FamCount(p, sched, i, p.cons3m, false)
        val c3mnC = c3FamCount(p, sched, i, p.cons3mn, true)
        total += c3nC + c3C + c3mC + c3mnC
        weighted += c3nC.toDouble() * MirrorKeys.weightOf("c3n") +
            c3C.toDouble() * MirrorKeys.weightOf("c3") +
            c3mC.toDouble() * MirrorKeys.weightOf("c3m") +
            c3mnC.toDouble() * MirrorKeys.weightOf("c3mn")
        return StaffObjective(c3nC, weighted, total)
    }


    private fun c3FamCount(p: Problem, sched: Array<IntArray>, i: Int, list: List<C3>, forbidden: Boolean): Long {
        var c = 0L
        for (con in list) {
            val seq = con.seq; val d = seq.size
            if (d == 0 || d > p.T) continue
            var j = 0
            while (j <= p.T - d) {
                if (sched[i][j] == seq[0]) {
                    var z = 0
                    for (l in 1 until d) if (sched[i][j + l] == seq[l]) z++
                    val fire = if (forbidden) z == d - 1 else z < d - 1
                    if (fire) c++
                }
                j++
            }
        }
        return c
    }

}
