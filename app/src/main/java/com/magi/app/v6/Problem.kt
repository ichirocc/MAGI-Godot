package com.magi.app.v6

import com.magi.app.model.MagiState

/** Resolved constraint rows in index form (kigou -> indices). */
class C1(@JvmField val day1: Int, @JvmField val shiftIdx: Int, @JvmField val day2: Int)
class C2(@JvmField val shiftIdx: Int, @JvmField val count: Int)
class C3(@JvmField val seq: IntArray)
class C41(@JvmField val groupIdx: Int, @JvmField val shiftIdx: Int, @JvmField val l: Int, @JvmField val u: Int)
class C42(@JvmField val g1: Int, @JvmField val s1: Int, @JvmField val g2: Int, @JvmField val s2: Int)
/** 希望で固定した [wishIdx] の前日に [prevIdx] を禁止（cons3w, HARD）。 */
class C3w(@JvmField val wishIdx: Int, @JvmField val prevIdx: Int)

/**
 * Immutable, index-resolved view of a [MagiState] ready for fast evaluation.
 * Faithfully mirrors the Web worker's prelude + resolveConstraints().
 */
/** [backlog #12(a)・実験段階] c2/c41/c41sを二値(違反ルール件数)でなく不足量/距離量で評価するモード。
 *  既定falseは全既存呼出元と挙動不変。trueは`c2Amount`/`rangeDistance`（Evaluator.kt）を使う。 */
class Problem(val state: MagiState, val quantitativeRangeEval: Boolean = false) {
    val S = state.staffCount
    val T = state.dayCount
    val K = state.shiftCount
    val G = state.groupCount
    val use2 = state.use2Patterns

    /** [3.410.0/P-06] `groupIdx` が群の範囲外だった職員 index（表示は `V6SanityPort` の検査2k）。
     *  旧: `sgrp = IntArray(S) { state.staff[it].groupIdx }` は無検証で、外部 JSON 由来の範囲外値が
     *  そのまま `bucket[sgrp[i]]` / `grpCnt[sgrp[i]*K+k]` の添字になり **Kotlin 側で AIOOBE** になった。
     *  3.327.0 は同じクラスの `skillIdx` に検査2i を入れたが `groupIdx` は取り残しだった。
     *  C++ 側は 3.171.0 が `nativeCreateProblem` で拒否するが、拒否＝ハンドル0＝**Kotlin へ退化**なので
     *  救いにならない（結局 Kotlin で落ちる）。先頭群へ寄せる（`Ws1Ops.removeGroup` が所属者を先頭群へ
     *  移すのと同じ規約）＋**必ず知らせる**（黙って寄せると別の群のルールが静かに掛かる）。 */
    private val _outOfRangeGroupStaff = mutableListOf<Int>()
    val outOfRangeGroupStaff: List<Int> get() = _outOfRangeGroupStaff

    val sgrp = IntArray(S) { i ->
        val g = state.staff[i].groupIdx
        if (g in 0 until G) g else { _outOfRangeGroupStaff.add(i); 0 }
    }

    /** 休シフトの index（記号"休"解決、無ければ0）。曜日平準化(weekly)で「勤務日か休か」を判定。 */
    val restIdx: Int = restShiftIndex(state)

    /** startDate の曜日オフセット（%7）。weekday(j)=(dow0+j)%7。曜日平準化(weekly)の曜日バケットに使う。
     *  絶対曜日ラベルは重要でなく、day j と day j+7 が同一バケットに落ちることのみが必要。
     *  V6HotfixPasses.dayOfWeekVariance / V6PortAnalyzer.startDow と同式。 */
    val dow0: Int = runCatching { java.time.LocalDate.parse(state.startDate).dayOfWeek.value % 7 }.getOrDefault(0)

    /** Allowed shift indices per group (groupShift[g][k]==1). */
    val bucket: Array<IntArray> = Array(G) { g ->
        val row = state.groupShift.getOrNull(g) ?: emptyList()
        (0 until K).filter { k -> row.getOrNull(k) == 1 }.toIntArray()
    }

    /** groupMembers[g] = 群gに属する staff index。グループ内公平化(fair)で群メンバー間の回数偏差を均すのに使う。 */
    val groupMembers: Array<IntArray> = Array(G) { g -> (0 until S).filter { sgrp[it] == g }.toIntArray() }

    /** Staff indices the optimizer may give a shift to (used by block-fill moves). [3.507.0] 置ける職員＝`placeable`
     *  （担当可かつ個人上限 0 でない）。群の解決は必ず `sgrp` を通す＝単一ソース（3.410.0/P-06）。init で range 解決後に埋める。 */
    val staffForShift: Array<IntArray>

    /** [3.507.0] 最適化器が置いてよいシフト（職員別）＝担当可（bucket）から個人上限 0（休を除く）を外したもの。評価・表示は canDo のまま
     *  （候補生成・入口 hf66・最終番兵の基準だけが見る。希望固定は wishLocked が優先。経緯は history 3.507.0）。 */
    val placeable: Array<IntArray>
    val placeableHas: Array<BooleanArray>

    /** wish[i][j] = desired shift index, or -1. */
    val wish: Array<IntArray> = Array(S) { IntArray(T) { -1 } }

    /** need1[k][j] / need2[k][j] = required count, or -1 (= no requirement). */
    val need1: Array<IntArray>
    val need2: Array<IntArray>

    /** rangeLo/Hi[i][k] = LimMin/LimMax, or Int.MIN/MAX_VALUE when unset. */
    val rangeLo: Array<IntArray>
    val rangeHi: Array<IntArray>

    /** apt[i][k] = 適切回数（群単位の双方向目標 groupShiftApt[群][シフト]）, or -1 when unset.
     *  担当可能(canDo=bucket)なシフトのみ展開し、解消不能な幻のapt偏差を作らない（c1 と同じ方針）。 */
    val apt: Array<IntArray>
    /** [3.508.0] 群目標の設定値（到達範囲クランプ前。個人設定がある組は [apt] と同じく -1）。設定ミス診断が
     *  「設定した目標が構造的に届かない」ことを言い続けるために読む。評価・最適化は [apt]（実効目標）だけを見る。 */
    val aptRaw: Array<IntArray>

    val cons1: List<C1>
    val cons2: List<C2>
    val cons3: List<C3>
    val cons3n: List<C3>
    val cons3m: List<C3>
    val cons3mn: List<C3>
    // [監査#9] 期間より長い連続パターンはパース段階で除外し、(族, パターン表示) をここに記録する。
    //   供給源を一元化することで、評価器/Δ/チェッカー/修復手のどれもが L>T 行を見ない状態を保証する
    //   （従来: チェッカーのみ d>T skip、評価器の run-mode は罰し続ける乖離があった）。
    private val _c3OverT = mutableListOf<Pair<String, String>>()
    val c3OverT: List<Pair<String, String>> get() = _c3OverT

    // [3.309.0] 存在しないシフト記号を含む連続パターン行。旧実装は resolveC3 で
    //   `if (si < 0) return@mapNotNull null` と**無言で捨てて**おり、シフトを改名・削除すると
    //   その行を参照する連続パターン（禁止=HARD を含む）が評価対象から消えるのに、画面にも
    //   ログにも何も出なかった。すぐ下の L>期間 のケースは _c3OverT に記録して Sanity が案内する
    //   のに、この分岐だけ同じ扱いになっていなかった非対称。fam と、行の記号列（未定義記号を
    //   〈〉で囲んだもの）を記録する。読み取り専用＝評価・重みは一切変えない。
    private val _c3UnknownShift = mutableListOf<Pair<String, String>>()
    val c3UnknownShift: List<Pair<String, String>> get() = _c3UnknownShift

    // [3.412.0/P-04] 期間より長い窓の要件(cons1)。`MirrorCore.checkC1Family` は `c.day1 > p.T` を
    //   `continue` で**無言で飛ばす**ため、31日の月に「休を35日で4回以上」と入れると評価もされず
    //   画面にも何も出ない。連続パターンは同じ状況を `_c3OverT` に記録して検査2d が案内するのに、
    //   窓の要件だけ取り残されていた（3.320.0 が6族へ広げた `_unresolvedRows` は「行が解決できない」
    //   ケースで、こちらは「行は解決できるが窓が期間を超える」＝別の穴）。読み取り専用＝評価不変。
    private val _c1OverT = mutableListOf<String>()
    val c1OverT: List<String> get() = _c1OverT

    // [3.320.0] 3.309.0 は連続パターン(cons3系)だけを直したが、**同じ無言除外が残り6族にあった**:
    //   cons1(窓ルール)・cons2(個人合計)・cons41/cons42(群)・cons41s/cons42s(スキル群)は、記号が
    //   解決できない行や数値が不正な行を `mapNotNull { ... else null }` でやはり黙って捨てる。
    //   シフトや群を改名・削除すると、それを参照する窓ルール(重み30)や群ペア禁止が警告なく評価から
    //   消える。(族ラベル, 行の表示) を記録し、Sanity が cons3 系と同じ形で案内する。読み取り専用。
    private val _unresolvedRows = mutableListOf<Pair<String, String>>()
    val unresolvedRows: List<Pair<String, String>> get() = _unresolvedRows

    val cons41: List<C41>
    val cons42: List<C42>
    // [スキルグループ新設] スキル群の C41/C42 相当（ssk = staff のスキル群index。既存 sgrp とは独立）。
    val skillG = state.skillGroupCount
    val ssk = IntArray(S) { state.staff[it].skillIdx }
    val cons41s: List<C41>
    val cons42s: List<C42>
    val cons3w: List<C3w>
    /** [3.542.0] c3wBan[i][j][k] = 翌日 j+1 が希望固定（wishLocked）の X で、cons3w に (X→k) がある＝セル (i,j) に k を置くと違反。
     *  希望は探索中に動かないので盤面非依存の静的表。cons3w が空なら null（評価・Δの分岐を無料にする）。 */
    val c3wBan: Array<Array<BooleanArray>>?

    /** 記号が解決できたかを `〈〉` で示す表示（cons3 系の記録と同じ書式）。 */
    private fun mark(kigou: String, resolved: Boolean): String = if (resolved) kigou else "〈$kigou〉"

    init {
        for ((key, v) in state.wishes) {
            val p = key.split(',')
            val i = p.getOrNull(0)?.toIntOrNull() ?: continue
            val j = p.getOrNull(1)?.toIntOrNull() ?: continue
            if (i in 0 until S && j in 0 until T) wish[i][j] = v
        }

        need1 = Array(K) { IntArray(T) { -1 } }
        need2 = Array(K) { IntArray(T) { -1 } }
        for (k in 0 until K) for (j in 0 until T) {
            need1[k][j] = needAt(k, j, false)
            need2[k][j] = needAt(k, j, true)
        }

        rangeLo = Array(S) { IntArray(K) { Int.MIN_VALUE } }
        rangeHi = Array(S) { IntArray(K) { Int.MAX_VALUE } }
        for ((key, r) in state.staffRange) {
            val p = key.split(',')
            val i = p.getOrNull(0)?.toIntOrNull() ?: continue
            val k = p.getOrNull(1)?.toIntOrNull() ?: continue
            if (i in 0 until S && k in 0 until K) {
                // [3.509.1] 負数は未設定扱い（下限 -1 は無制限、上限 -1 は全回数が違反＝どちらも意図ではありえず、
                //   D9 の「個人設定あり」にも数えない。Sanity 2h が案内する）。
                r.lo.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { rangeLo[i][k] = it }
                r.hi.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { rangeHi[i][k] = it }
            }
        }

        placeableHas = Array(S) { i ->
            val b = bucket.getOrNull(sgrp[i])
            BooleanArray(K) { k -> b?.contains(k) == true && !(rangeHi[i][k] == 0 && k != restIdx) }
        }
        placeable = Array(S) { i -> (0 until K).filter { placeableHas[i][it] }.toIntArray() }
        staffForShift = Array(K) { k -> (0 until S).filter { i -> placeableHas[i][k] }.toIntArray() }

        // 適切回数（双方向目標）: state.groupShiftApt[群][シフト] を個人別 apt[i][k] へ展開（群単位＝同群全員に同一目標）。
        // 担当ONシフトのみ（bucket=canDo）有効化し、担当不可シフトの幻のapt偏差を除外する。
        apt = Array(S) { IntArray(K) { -1 } }
        aptRaw = Array(S) { IntArray(K) { -1 } }
        // [3.508.0] 職員×シフトの実効下限/上限（希望固定込み）。下限 = max(lo, 希望固定数)。上限 = 置けるなら
        //   (hi か T)、置けない（担当外・上限 0）なら希望固定数だけ。他シフトの合計から「このシフトに必ず来る日数」
        //   ＝到達下限 T−Σ他の上限 と、到達上限 T−Σ他の下限 が決まる。
        val wishCnt = Array(S) { i -> IntArray(K).also { c -> for (j in 0 until T) { val w = wish[i][j]; if (w in 0 until K && canDo(i, w)) c[w]++ } } }
        fun effLo(i: Int, k: Int): Int = maxOf(if (rangeLo[i][k] == Int.MIN_VALUE) 0 else rangeLo[i][k], wishCnt[i][k])
        fun effHi(i: Int, k: Int): Int = maxOf(if (placeableHas[i][k]) (if (rangeHi[i][k] == Int.MAX_VALUE) T else rangeHi[i][k]) else 0, wishCnt[i][k])
        for (i in 0 until S) {
            val g = sgrp[i]
            val row = state.groupShiftApt.getOrNull(g) ?: continue
            val canK = bucket.getOrNull(g)
            for (k in 0 until K) {
                var t = row.getOrNull(k)?.trim()?.toIntOrNull() ?: continue
                if (t < 0 || canK?.contains(k) != true) continue
                // [3.509.0/決定 D9] 個人の下限または上限が入っている組には群目標を適用しない（空欄だけのキーは未設定と同じ）。
                if (rangeLo[i][k] != Int.MIN_VALUE || rangeHi[i][k] != Int.MAX_VALUE) continue
                aptRaw[i][k] = t
                // [3.508.0] 到達範囲へ収める（例: 休の希望固定 15 日の職員に 休 目標 10 は届かない → 15）。
                var sumHi = 0; var sumLo = 0
                for (k2 in 0 until K) if (k2 != k) { sumHi += effHi(i, k2); sumLo += effLo(i, k2) }
                val reachLo = maxOf(T - sumHi, wishCnt[i][k])
                val reachHi = T - sumLo
                if (reachLo <= reachHi) { if (t < reachLo) t = reachLo; if (t > reachHi) t = reachHi }
                apt[i][k] = t
            }
        }

        cons1 = state.cons1.mapNotNull {
            // [3.475.0/論理監査] 数値は trim してから解決する。旧: `" 2 "` のような前後空白つきの値は
            //   「非空だが解決不能」となり 0/∞ へ黙って倒れていたが、設定ミス検査(badNum)は trim して
            //   「正常」と見なす＝エンジンと診断が同じ入力を別の値として読んでいた（staffRange/need は
            //   もともと trim 済み。cons1/cons2/cons41(s) だけ非対称）。
            val d1 = it.day1.trim().toIntOrNull() ?: 0
            val si = shiftIdxOf(it.shiftKigou)
            val d2 = it.day2.trim().toIntOrNull() ?: 0
            if (d1 > 0 && si >= 0 && d2 > 0) {
                // [3.412.0/P-04] 行としては解決できるが窓が期間を超える＝チェッカーが無言で飛ばす。
                //   行は残す（評価の挙動は完全に不変）が、記録して Sanity が理由を案内する。
                if (d1 > T) _c1OverT.add("${it.shiftKigou} を${d1}日で${d2}回以上")
                C1(d1, si, d2)
            } else { _unresolvedRows.add("期間の制約" to "${mark(it.shiftKigou, si >= 0)} を${it.day1}日で${it.day2}回以上"); null }
        }
        cons2 = state.cons2.mapNotNull {
            val si = shiftIdxOf(it.shiftKigou)
            val c = it.count.trim().toIntOrNull() ?: 0
            if (si >= 0 && c > 0) C2(si, c)
            else { _unresolvedRows.add("個人の合計" to "${mark(it.shiftKigou, si >= 0)} を${it.count}回以上"); null }
        }
        cons3w = state.cons3w.mapNotNull {
            val x = shiftIdxOf(it.wishKigou); val y = shiftIdxOf(it.prevKigou)
            if (x >= 0 && y >= 0) C3w(x, y)
            else { _unresolvedRows.add("希望の前日に禁止" to "${mark(it.wishKigou, x >= 0)} の希望の前日は ${mark(it.prevKigou, y >= 0)}"); null }
        }
        c3wBan = if (cons3w.isEmpty()) null else Array(S) { i ->
            Array(T) { j ->
                BooleanArray(K).also { ban ->
                    if (j + 1 < T) {
                        val w = wish[i][j + 1]
                        if (w >= 0 && canDo(i, w)) for (c in cons3w) if (c.wishIdx == w) ban[c.prevIdx] = true
                    }
                }
            }
        }
        cons3 = resolveC3(state.cons3, "c3")
        cons3n = resolveC3(state.cons3n, "c3n")
        cons3m = resolveC3(state.cons3m, "c3m")
        cons3mn = resolveC3(state.cons3mn, "c3mn")
        cons41 = state.cons41.mapNotNull {
            val gi = groupIdxOf(it.groupKigou)
            val si = shiftIdxOf(it.shiftKigou)
            val hasLo = it.l.isNotBlank()
            val hasHi = it.u.isNotBlank()
            val lo = if (hasLo) it.l.trim().toIntOrNull() ?: 0 else 0
            val hi = if (hasHi) it.u.trim().toIntOrNull() ?: Int.MAX_VALUE else Int.MAX_VALUE
            if (gi >= 0 && si >= 0 && (hasLo || hasHi)) C41(gi, si, lo, hi)
            else {
                _unresolvedRows.add("群のレンジ" to
                    "${mark(it.groupKigou, gi >= 0)} の ${mark(it.shiftKigou, si >= 0)}（${it.l}〜${it.u}）")
                null
            }
        }
        cons42 = state.cons42.mapNotNull {
            val g1 = groupIdxOf(it.g1Kigou); val g2 = groupIdxOf(it.g2Kigou)
            val s1 = shiftIdxOf(it.s1Kigou); val s2 = shiftIdxOf(it.s2Kigou)
            if (g1 >= 0 && g2 >= 0 && s1 >= 0 && s2 >= 0) C42(g1, s1, g2, s2)
            else {
                _unresolvedRows.add("群ペア禁止" to
                    "${mark(it.g1Kigou, g1 >= 0)}/${mark(it.s1Kigou, s1 >= 0)} × " +
                        "${mark(it.g2Kigou, g2 >= 0)}/${mark(it.s2Kigou, s2 >= 0)}")
                null
            }
        }
        cons41s = state.cons41s.mapNotNull {
            val gi = skillGroupIdxOf(it.groupKigou)
            val si = shiftIdxOf(it.shiftKigou)
            val hasLo = it.l.isNotBlank(); val hasHi = it.u.isNotBlank()
            val lo = if (hasLo) it.l.trim().toIntOrNull() ?: 0 else 0
            val hi = if (hasHi) it.u.trim().toIntOrNull() ?: Int.MAX_VALUE else Int.MAX_VALUE
            if (gi >= 0 && si >= 0 && (hasLo || hasHi)) C41(gi, si, lo, hi)
            else {
                _unresolvedRows.add("スキル群のレンジ" to
                    "${mark(it.groupKigou, gi >= 0)} の ${mark(it.shiftKigou, si >= 0)}（${it.l}〜${it.u}）")
                null
            }
        }
        cons42s = state.cons42s.mapNotNull {
            val g1 = skillGroupIdxOf(it.g1Kigou); val g2 = skillGroupIdxOf(it.g2Kigou)
            val s1 = shiftIdxOf(it.s1Kigou); val s2 = shiftIdxOf(it.s2Kigou)
            if (g1 >= 0 && g2 >= 0 && s1 >= 0 && s2 >= 0) C42(g1, s1, g2, s2)
            else {
                _unresolvedRows.add("スキル群ペア禁止" to
                    "${mark(it.g1Kigou, g1 >= 0)}/${mark(it.s1Kigou, s1 >= 0)} × " +
                        "${mark(it.g2Kigou, g2 >= 0)}/${mark(it.s2Kigou, s2 >= 0)}")
                null
            }
        }
    }

    /** getNeed(k,j,p2): per-day override, falling back to shift default; -1 == none. */
    private fun needAt(k: Int, j: Int, p2: Boolean): Int {
        val map = if (p2) state.needDay2 else state.needDay1
        val v = map["$k,$j"]
        if (!v.isNullOrBlank()) v.trim().toIntOrNull()?.let { return it }
        val def = if (p2) state.shifts[k].need2 else state.shifts[k].need1
        return if (def.isBlank()) -1 else (def.trim().toIntOrNull() ?: -1)
    }

    private fun shiftIdxOf(kigou: String): Int = state.shifts.indexOfFirst { it.kigou == kigou }
    private fun groupIdxOf(kigou: String): Int = state.groups.indexOfFirst { it.kigou == kigou }
    private fun skillGroupIdxOf(kigou: String): Int = state.skillGroups.indexOfFirst { it.kigou == kigou }

    /**
     * resolveC3: truncate the pattern at the first blank symbol; drop the whole row if
     * an interior symbol is unresolvable (mirrors the Web doc7#4 fix — never compact).
     */
    private fun resolveC3(rows: List<com.magi.app.model.C3Row>, fam: String): List<C3> = rows.mapNotNull { row ->
        val raw = row.pattern
        val end = raw.indexOfFirst { it.isBlank() }
        val body = if (end >= 0) raw.subList(0, end) else raw
        if (body.isEmpty()) return@mapNotNull null
        val seq = IntArray(body.size)
        for (idx in body.indices) {
            val si = shiftIdxOf(body[idx])
            if (si < 0) {
                // [3.309.0] 無言で捨てず記録する（Sanity が「この行は効いていない」と案内する）。
                _c3UnknownShift.add(
                    fam to body.joinToString("") { t -> if (shiftIdxOf(t) < 0) "〈$t〉" else t },
                )
                return@mapNotNull null
            }
            seq[idx] = si
        }
        if (seq.size > T) {   // [監査#9] L>期間はどの族でも判定不能/無意味 → 除外して記録（Sanityが案内）
            _c3OverT.add(fam to body.joinToString(""))
            return@mapNotNull null
        }
        C3(seq)
    }

    /**
     * Initial assignment from state.schedule, overwriting a cell with its wish only when
     * the wished shift is actually in the staff's allowed bucket (Web HF143 capability guard).
     */
    // [監査#4b] 被覆セル評価（per-cell OR/AND）— Evaluator/Δ/Checker の単一ソース（VBA本家=Web HF574と三面統一）。
    //   U: 両定義=min(u1,u2)・片方定義=その値（P2単独定義セルも評価）。
    //   O: 両定義=両超過時のみmin(o1,o2)・片方定義=その超過。U>0とO>0は同一セルで両立しない。
    //   実データ(P1=P2)では u1=u2/o1=o2 のため旧・総量min式とビット単位で同値。
    fun covUCell(k: Int, j: Int, got: Int): Int {
        val lo1 = need1[k][j]
        val lo2 = if (use2) need2[k][j] else -1
        val u1 = if (lo1 >= 0) (lo1 - got).coerceAtLeast(0) else -1
        val u2 = if (lo2 >= 0) (lo2 - got).coerceAtLeast(0) else -1
        return when {
            u1 >= 0 && u2 >= 0 -> minOf(u1, u2)
            u1 >= 0 -> u1
            u2 >= 0 -> u2
            else -> 0
        }
    }
    fun covOCell(k: Int, j: Int, got: Int): Int {
        val lo1 = need1[k][j]
        val lo2 = if (use2) need2[k][j] else -1
        val o1 = if (lo1 >= 0) (got - lo1).coerceAtLeast(0) else -1
        val o2 = if (lo2 >= 0) (got - lo2).coerceAtLeast(0) else -1
        return when {
            o1 >= 0 && o2 >= 0 -> minOf(o1, o2)
            o1 >= 0 -> o1
            o2 >= 0 -> o2
            else -> 0
        }
    }

    /**
     * [三連/五連など任意長への配慮] 職員 i の j 列を newK に変えたとき、cons3n（禁止連続, HARD）を
     * 新たに作るかを判定する。旧: 呼び出し側3箇所（V6SearchOperators.findCovUChain の枝刈り／
     * V6LateOperators の ChainSwap3/4 前後チェック／CoverageDiagnosis の内訳分類）がいずれも
     * 「長さ2の禁止連続のみ」を仮定した専用コードを個別に持っていた（コメントで明記された既知の狭さ）。
     * cons3n ルールは MirrorCore.checkC3Family の forbidden 分岐と同じ意味論で**任意長**（三連・五連等）
     * を表現できるため、ここで単一ソースとして一般化する: 各ルール(長さd)について j をカバーする
     * 開始位置 s の窓を全て調べ、位置jだけ newK に差し替えて残りは現在の schedule のまま完全一致
     * (z==d)するかを見る。他セルは変えない=1手の影響範囲チェックとして正しい。
     * これは探索の**枝刈り**（成功率向上のヒント）用途で、最終的な正しさは常に UnifiedViolationChecker
     * （source of truth）が担保する＝本関数の判定が仮に見逃しても結果は不変（安全側）。
     */
    fun makesForbiddenRun(schedule: Array<IntArray>, i: Int, j: Int, newK: Int): Boolean {
        if (c3wBanned(i, j, newK)) return true   // [3.542.0] 希望の前日に禁止（cons3w, HARD）も同じ枝刈りで避ける
        for (c in cons3n) {
            val seq = c.seq
            val d = seq.size
            if (d == 0 || d > T) continue
            val sLo = (j - d + 1).coerceAtLeast(0)
            val sHi = j.coerceAtMost(T - d)
            var s = sLo
            while (s <= sHi) {
                var z = 0
                for (l in 0 until d) {
                    val v = if (s + l == j) newK else schedule[i][s + l]
                    if (v == seq[l]) z++
                }
                if (z == d) return true
                s++
            }
        }
        return false
    }

    /** [3.542.0] セル (i,j) にシフト k を置くと「希望の前日に禁止」(cons3w) に当たるか。範囲外は false。 */
    fun c3wBanned(i: Int, j: Int, k: Int): Boolean {
        val b = c3wBan ?: return false
        if (i !in 0 until S || j !in 0 until T || k !in 0 until K) return false
        return b[i][j][k]
    }

    fun initialAssignment(): Array<IntArray> = Array(S) { i ->
        val b = placeable[i]   // [3.507.0] 穴埋めは置けるシフトから。希望の反映は canDo 基準（wishLocked と同じ）
        IntArray(T) { j ->
            // [3.419.0] 旧: 欠損セル（行が短い・行が無い）を `?: 0` でハードコードの index 0 にしていた。
            //   0 は合法値なので下の範囲チェックを素通りし、**勤務シフトへ黙って化ける**。3.410.0 が
            //   範囲外セルについて直したのと同じ取り違えが、その1行上に残っていた。-1 へ倒して
            //   下の穴埋めに合流させる。
            var k = state.schedule.getOrNull(i)?.getOrNull(j) ?: -1
            val w = wish[i][j]
            if (w >= 0 && canDo(i, w)) k = w
            // [3.410.0/P-01] 旧: 範囲外セルをハードコードの 0 へ寄せていた。0 が休とは限らない
            //   （休が先頭でないデータでは**勤務シフトへ化ける**）。
            // [3.419.0] さらに、寄せ先の `restIdx` を**この職員が担当できるか見ていなかった**＝
            //   休を担当できない群では、範囲外という入力の不備だけを理由に groupViol(HARD) を作っていた。
            //   共通規則 `fillShiftIndex` へ委譲する（Ws1Ops の3経路と同じ判断）。
            //   なお `MirrorCore.normalizeSchedule` は同じセルを -1（センチネル）にするが、ここは
            //   **探索へ渡す初期盤面**なので合法値が要る（-1 を入れると `DeltaEvaluator.rebuild` の
            //   `cntSS[i][k]++` が飛ぶ）＝非対称は意図的。
            if (k < 0 || k >= K) k = fillShiftIndex(b, restIdx)
            k
        }
    }
}
