package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * [3.495.0/ユーザー提示の設計] ブロック交換の窓の扱い。
 * - [PARTIAL_MOVABLE_DAYS]: 従来（3.291.0）。希望固定・担当不可の日は窓から据え置き、動く日だけ交換する。
 * - [STRICT_WHOLE_WINDOW]: 窓内の全セルを一括交換する。1日でも交換不能なら窓全体を不成立にする＝厳密な「連の交換」。
 *   違反アンカー（セル違反・回数超過/不足・連続規則・週偏り）に接する可変長の窓だけを生成する。
 */
enum class WindowMode { PARTIAL_MOVABLE_DAYS, STRICT_WHOLE_WINDOW }

/** 回数違反の向き（超過＝対象シフトを減らしたい／不足＝増やしたい）。 */
enum class CountDirection { HIGH, LOW }

/**
 * [3.495.0] 違反アンカー。窓の生成起点。
 * - セル違反: [day]＝違反セルの日（連続規則は違反区間の両端も別アンカーとして積む）。
 * - 回数違反: [shift]＋[direction]。超過は対象職員が [shift] に入っている日、不足は「他職員が [shift]、対象職員は
 *   [shift] 以外」の交換可能な日を逆引きして [day] に置く。
 * - 週偏り: [day]=null（長さ7の窓を全開始位置で生成）。
 */
data class ViolationAnchor(
    val staff: Int,
    val day: Int?,
    val shift: Int?,
    val direction: CountDirection?,
    val families: Set<String>,
)

/**
 * 可変長ブロック丸ごと交換（適応ポートフォリオ）。
 *
 * 2 つのモードを持つ（[WindowMode]）。どちらも「同じ日付の値を職員間で入れ替える」だけなので日別のシフト多重集合＝
 * 全体の被覆量は保存し、最終採否は [UnifiedViolationChecker] と [betterReport] の正式順（HARD → weightedScore → total）
 * だけで決める（探索順にかかわらず退化しない）。正式評価の回数は pass ごとに `maxEvaluations` で上限を置く
 * （[3.327.0/外部レビュー] 呼び出し全体では `maxPasses × maxEvaluations`。呼び出し1回ぶんの予算へ変える版は
 * pass 境界での候補再生成が減って real の採用手が 2→1 に落ちたので採らない）。
 *
 * [3.499.0 自律レビュー] 2 モードで二重に持っていた「候補を順に当てて最良1手を選ぶ」「不採用理由の分類」「職員違反圧力」
 * 「回数ペナルティの見積り」を [KeepBest]／[RejectStats]／[staffPressure]／[personalPenalty] に一本化し、
 * 各モードの探索本体を [CyclicSession]／[StrictSession] に分けた。優先度の尺度は [Priority]、探索幅は
 * [CyclicParams]／[StrictParams] に集約。既定値・採否・ログ文言は不変（4 実データ×6 条件の盤面ハッシュで照合）。
 */
internal object AdaptiveBlockSwapPolish {
    /**
     * 長期ブロック交換の候補長。月次勤務表で「局所交換では越えにくい」谷を越えるための非等間隔ポートフォリオで、
     * 短い方から順に 11/13/17/19/23/28 日を試す。28 は 2月（28日）で「1か月まるごと」の交換を確保するための長さ
     * （素数列ではない点は意図的）。
     */
    private val adaptiveBlockLengths = intArrayOf(11, 13, 17, 19, 23, 28)

    /**
     * 巡回交換（[WindowMode.PARTIAL_MOVABLE_DAYS]）の探索幅。
     * - [maxEvaluations] は **pass ごと**の正式評価枠。[maxCycleVisits] も全体予算でなく (ブロック長, 開始日) ごとの
     *   DFS 分岐上限（共通予算にすると後ろのブロックが一切探索されない。実測で DFS は 88万件/77ms＝ボトルネックでない）。
     * - [maxCycle] は 2..8 に丸める（巡回の署名を 8bit×人数で Long に詰めるため。既定 5＝到達しない防御、3.469.0）。
     * - [filterC3nIncrease]: 禁止連続(c3n)が正味増える候補を候補生成の段階で捨てるか。既定は
     *   [PolishGate.filterC3nIncrease]（設定タブの詳細トグル・既定 **true**、3.518.0）。c3n は HARD なので増える候補は
     *   最終的に `isBetter` が必ず却下する＝true/false で採用結果は変わらず、true は詰んだ候補へ checker を呼ばない節約だけ。
     * - [stageOneWidthFactor]: 見積りキーだけで選別する第1段プールの幅＝`candidatesPerLength × この係数`。
     *   見積り上位が実候補化で落ちる（交換成立日が1日以下）ことは巡回人数が増えるほど起きやすく、幅が狭いと
     *   その下の成立候補まで失うため段②より広く取る（3.291.0 実測で 8）。
     * - [maxPinSlots]: ピン保存交換で扱う厳密ピン（参加者×シフト）の上限。超えたら相殺をあきらめ従来どおり全日で候補化。
     */
    data class CyclicParams(
        val blockLens: IntArray = adaptiveBlockLengths,
        val maxPasses: Int = 2,
        val candidatesPerLength: Int = 8,
        val maxEvaluations: Int = 48,
        val maxFocusStaff: Int = 16,
        val maxCycle: Int = 5,
        val maxCycleVisits: Int = 50_000,
        val filterC3nIncrease: Boolean = PolishGate.filterC3nIncrease,
        val stageOneWidthFactor: Int = 8,
        val maxPinSlots: Int = 31,
        /** [3.511.0/測定中] true なら [blockLens] の前に、違反窓長(c1)・禁止連長(c3n)・希望島半径・当月日数から
         *  導出した長さ（[dynamicBlockLengths]）を差し込む（重複除去、先頭優先＝評価枠を先取り）。既定 OFF＝固定長のみ（挙動不変）。
         *  backlog #14(c): 固定 28 は 2 月以外（T=29〜31）では一度も「当月まるごと」に届かない穴を、動的候補の `p.T` が塞ぐ。 */
        val useDynamicBlockLens: Boolean = false,
        /** [3.511.0/測定中] 動的長を足したあとの候補長総数の上限（bucketCount＝候補長×巡回人数幅の膨張を抑える）。 */
        val maxTotalBlockLens: Int = adaptiveBlockLengths.size + 3,
    )

    /**
     * [3.511.0/測定中] 動的長の候補（backlog #14(c)）。StrictSession（[WindowMode.STRICT_WHOLE_WINDOW]）は既に
     * `ruleLens`＋`1..maxLen`＋長連続違反時の `longLen` で動的に窓長を出しており対象外＝これは CyclicSession 用。
     * 局所（既定 7 日以下＝ [WishIslandPolish] や StrictSession の既定 `maxLen` と同じ境目）は演算子の役割が重複するため除く。
     */
    private fun dynamicBlockLengths(p: Problem): List<Int> {
        val localCutoff = 7
        val out = LinkedHashSet<Int>()
        for (c in p.cons1) if (c.day1 > localCutoff) out.add(c.day1)
        for (c in p.cons3n) if (c.seq.size > localCutoff) out.add(c.seq.size)
        // 希望島半径の式は WishIslandPolish.Session.computeReach() と同一（c1.day1・c3系.seq.size の max−1）。
        var reach = 1
        for (c in p.cons1) reach = maxOf(reach, c.day1 - 1)
        for (list in listOf(p.cons3, p.cons3n, p.cons3m, p.cons3mn)) for (c in list) reach = maxOf(reach, c.seq.size - 1)
        val span = 2 * reach + 1
        if (span > localCutoff) out.add(span)
        out.add(p.T)   // 「当月まるごと」＝固定28は2月以外(T=29〜31)では一度も一致しない
        return out.filter { it in (localCutoff + 1)..p.T }.sorted()
    }

    /**
     * 厳密窓交換（[WindowMode.STRICT_WHOLE_WINDOW]）の探索幅。
     * [maxLen] は通常の最大窓長、[longLen] は長い連続違反（連長 > maxLen）があるときだけ許す上限。
     */
    data class StrictParams(
        val maxPasses: Int = 2,
        val maxEvaluations: Int = 48,
        val maxLen: Int = 7,
        val longLen: Int = 14,
    )

    /**
     * 安価な優先度の尺度（候補の順位付け専用。採否は正式 checker）。上位の桁ほど強い辞書式にしたいので 10 のべき乗で
     * 段を分ける。巡回: `回数改善×BALANCE + 職員圧力×PRESSURE + 変更セル数`。厳密窓: `HARD改善×HARD +
     * アンカー改善×ANCHOR + 回数改善×COUNT + 職員圧力×PRESSURE − 変更セル数×CHANGED_COST − 群違い×CROSS_GROUP`。
     */
    private object Priority {
        const val BALANCE = 1_000_000L
        const val HARD = 1_000_000L
        const val ANCHOR = 100_000L
        const val COUNT = 10_000L
        const val PRESSURE = 16L
        const val CHANGED_COST = 10L
        const val CROSS_GROUP = 20_000L
    }

    /** 巡回の署名（参加者 index を 8bit ずつ詰めた Long）に入る最大人数＝8。それ以上は詰めた値が折り返す。 */
    private const val MAX_PACKED_CYCLE = 8

    /** 参加者 index が 8bit に収まる focus 人数の上限。 */
    private const val MAX_PACKABLE_FOCUS = 255

    /**
     * 従来シグネチャ（既存の呼び出し元・テストはこちら）。[mode] で [CyclicParams]／[StrictParams] のどちらに束ねるかが決まる。
     * [strictMaxLen]/[strictLongLen] は STRICT 専用（巡回では無視）、[blockLens]/[candidatesPerLength]/[maxFocusStaff]/
     * [maxCycle]/[maxCycleVisits]/[filterC3nIncrease] は巡回専用（STRICT では無視）＝従来どおり。
     */
    fun applyAdaptiveBlockSwapPolish(
        state: MagiState,
        schedule: Array<IntArray>,
        blockLens: IntArray = adaptiveBlockLengths,
        maxPasses: Int = 2,
        candidatesPerLength: Int = 8,
        maxEvaluations: Int = 48,
        maxFocusStaff: Int = 16,
        maxCycle: Int = 5,
        maxCycleVisits: Int = 50_000,
        filterC3nIncrease: Boolean = PolishGate.filterC3nIncrease,
        shouldStop: () -> Boolean = { false },
        mode: WindowMode = WindowMode.PARTIAL_MOVABLE_DAYS,
        strictMaxLen: Int = 7,
        strictLongLen: Int = 14,
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult = when (mode) {
        WindowMode.STRICT_WHOLE_WINDOW -> applyStrictWholeWindow(
            state, schedule, StrictParams(maxPasses, maxEvaluations, strictMaxLen, strictLongLen), shouldStop, quantitativeRangeEval,
        )
        WindowMode.PARTIAL_MOVABLE_DAYS -> applyAdaptiveBlockSwapPolish(
            state, schedule,
            CyclicParams(blockLens, maxPasses, candidatesPerLength, maxEvaluations, maxFocusStaff, maxCycle, maxCycleVisits, filterC3nIncrease),
            shouldStop, quantitativeRangeEval,
        )
    }

    /** 可変長ブロック×可変長巡回交換（2者〜N者）。詳細は [CyclicSession]。 */
    fun applyAdaptiveBlockSwapPolish(
        state: MagiState,
        schedule: Array<IntArray>,
        params: CyclicParams,
        shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult = CyclicSession(state, schedule, params, shouldStop, quantitativeRangeEval).run()

    /** 違反アンカー型・可変長窓の一括交換。詳細は [StrictSession]。 */
    fun applyStrictWholeWindow(
        state: MagiState,
        schedule: Array<IntArray>,
        params: StrictParams,
        shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): V6HotfixPasses.CyclicSwapResult = StrictSession(state, schedule, params, shouldStop, quantitativeRangeEval).run()

    // ===== 両モード共通の部品 =====

    /**
     * range/apt の見積り（候補の順位付け専用・採否は正式 checker）。重みは [MirrorKeys] の low/high（90/25）を引く＝
     * 重みを変えたときにここだけ古い値で残る事故を防ぐ（apt は L1 偏差×1）。
     */
    private class PersonalPenalty(private val p: Problem) {
        private val lowW = MirrorKeys.weightOf("low").toLong()
        private val highW = MirrorKeys.weightOf("high").toLong()

        fun of(staff: Int, shift: Int, count: Int): Long {
            var out = 0L
            val lo = p.rangeLo[staff][shift]
            val hi = p.rangeHi[staff][shift]
            if (lo != Int.MIN_VALUE && count < lo) out += (lo - count).toLong() * lowW
            if (hi != Int.MAX_VALUE && count > hi) out += (count - hi).toLong() * highW
            val apt = p.apt[staff][shift]
            if (apt >= 0) out += abs(count - apt).toLong()
            return out
        }

        /** 回数が [delta] だけ動いたときの改善量（正＝良くなる）。 */
        fun gain(staff: Int, shift: Int, count: Int, delta: Int): Long = of(staff, shift, count) - of(staff, shift, count + delta)
    }

    /** 職員ごとの違反関与度（セル・回数・分布違反の族重みの和）。候補の順位付けと参加者の絞り込みに使う。 */
    private fun staffPressure(p: Problem, report: ViolationReport): LongArray {
        val out = LongArray(p.S)
        fun add(key: String, cls: String) {
            val i = key.substringBefore(',').toIntOrNull() ?: return
            if (i !in 0 until p.S) return
            out[i] += MirrorKeys.weightOf(cls.removePrefix("vio-")).toLong().coerceAtLeast(1L)
        }
        report.violations.forEach { (key, cls) -> add(key, cls) }
        report.countViolations.forEach { (key, cls) -> add(key, cls) }
        report.distLocations.forEach { (family, rows) ->
            val weight = MirrorKeys.weightOf(family).toLong().coerceAtLeast(1L)
            for (row in rows) {
                val i = row.firstOrNull() ?: continue
                if (i in 0 until p.S) out[i] += weight
            }
        }
        return out
    }

    /**
     * 不採用の理由別件数（採用0のとき「何に負けたか」がログから読めるように。RangePolish 3.222.0・C1Polish 3.236.0 と同じ趣旨）。
     * 分類は isBetter の判定順（HARD → weightedScore → total）と厳密に一致させる。
     */
    private class RejectStats {
        private val reasons = LinkedHashMap<String, Int>()
        private val culprits = LinkedHashMap<String, Int>()

        fun record(report: ViolationReport, bestRep: ViolationReport, pinRegression: Boolean) {
            val why = when {
                pinRegression -> "ピン破り"
                report.hard > bestRep.hard -> "必須増"
                report.hard < bestRep.hard -> "採用手に劣後"   // bestRep には勝つが同パスの別候補に負けた
                report.weightedScore > bestRep.weightedScore -> "重み悪化"
                report.weightedScore < bestRep.weightedScore -> "採用手に劣後"
                report.total < bestRep.total -> "採用手に劣後"
                report.total > bestRep.total -> "件数悪化"
                else -> "同値"
            }
            reasons[why] = (reasons[why] ?: 0) + 1
            if (why == "重み悪化" || why == "必須増") {
                // 重み付きで最も増えた族＝この手が壊した本体（共通ヘルパー worstWorsenedFamily）。
                worstWorsenedFamily(report, bestRep)?.let { culprits[it] = (culprits[it] ?: 0) + 1 }
            }
        }

        fun logSuffix(): String =
            (if (reasons.isEmpty()) "" else " 不採用内訳: " +
                reasons.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}${it.value}" }) +
            (if (culprits.isEmpty()) "" else " (悪化の主因 " +
                culprits.entries.sortedByDescending { it.value }.take(4).joinToString(" ") { "${it.key}:${it.value}" } + ")")
    }

    /**
     * pass 内の最良1手の選択。候補を順に「適用→フル checker→巻き戻し」し、厳密ピンを破らず [bestRep] に勝つ候補のうち
     * 最良のものを返す。正式評価は pass ごとに [maxEvaluations] 回まで。ピンだけが止めた候補は [pinBlocks] に記録する
     * （[3.326.0] 盤面を戻す前に呼ぶ＝record は after 盤面を読む）。
     */
    private class KeepBest(
        private val state: MagiState,
        private val p: Problem,
        private val work: Array<IntArray>,
        private val maxEvaluations: Int,
        private val shouldStop: () -> Boolean,
        private val pinBlocks: PinBlockAttribution,
        private val rejects: RejectStats,
        private val quantitativeRangeEval: Boolean = false,
    ) {
        var evaluated = 0
            private set

        fun <C> pick(candidates: List<C>, bestRep: ViolationReport, apply: (C) -> Unit, revert: (C) -> Unit): Pair<C, ViolationReport>? {
            val base = work.copy2D()
            var chosen: C? = null
            var chosenRep: ViolationReport? = null
            var checkedThisPass = 0
            for (candidate in candidates) {
                if (shouldStop() || checkedThisPass >= maxEvaluations) break
                apply(candidate)
                val report: ViolationReport
                val pinRegression: Boolean
                try {
                    report = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
                    pinRegression = exactPinRegression(p, base, work)
                    if (pinRegression && betterReport(report, bestRep)) pinBlocks.record(p, base, work)
                } finally {
                    revert(candidate)   // 正式評価中に例外が起きても、試行中の交換を呼出元の盤面へ残さない。
                }
                checkedThisPass++
                evaluated++
                val currentBest = chosenRep
                if (!pinRegression && betterReport(report, bestRep) && (currentBest == null || betterReport(report, currentBest))) {
                    chosen = candidate
                    chosenRep = report
                } else {
                    rejects.record(report, bestRep, pinRegression)
                }
            }
            val c = chosen ?: return null
            val r = chosenRep ?: return null
            return c to r
        }
    }

    private fun staffName(state: MagiState, i: Int): String = state.staff.getOrNull(i)?.name ?: "#$i"

    // ===== 巡回交換（PARTIAL_MOVABLE_DAYS） =====

    /**
     * 指定した長さの勤務ブロックを、他職員と丸ごと交換／巡回交換する適応ポートフォリオ演算子。
     *
     * 旧 applyBlockSwapPolish（3.300.0 で削除）は「同一担当グループ × 15日 × 2者」に固定されていた。本演算子は
     * 11/13/17/19/23/28日を独立した候補プールとして持ち、各長さから有望候補を必ず残す。これにより、短い窓では
     * 途中退化して届かない個人回数・apt・週偏り・連続規則の同時改善を、期間ごとのまとまりとして探索できる。
     *
     * **可変長の巡回交換（2者交換〜N者巡回, [CyclicParams.maxCycle]）**: 2者交換だけでは「A の X を B へ渡したいが、
     * B の持ち札は A に不要」という局面で成立しない。3者以上の巡回（A←B←C←A）ならこの非対称な譲り合いが閉じる。
     *
     * 候補生成は全列挙でなく **改善グラフ（cyclic exchange / VLSN）**:
     *  1. ブロック (start, length) ごとに、有向辺 u→v の重み＝「u が v のブロックを受け取ったときの u 個人の回数ペナルティ
     *     改善量」を [PersonalPenalty] で見積もる。各参加者が「自分の札を出して直前者の札を受け取る」ぶんだけで決まるため
     *     辺ごとに分解でき、巡回全体の見積り改善量は辺重みの単純和になる。
     *  2. 最小番号アンカー＋深さ maxCycle の DFS で、見積りキー（改善量×[Priority.BALANCE]＋圧力×[Priority.PRESSURE]）で
     *     (ブロック長 × 巡回人数) ごとの固定幅プールに入れる（実候補は作らない＝O(1)/巡回）。**見積り0の巡回も捨てない**
     *     （本命は c1/連続規則/曜日偏りの同時改善で、個人回数が動かない手が採用されることがある。3.291.0 実測）。
     *  3. 各プールの上位だけを [candidateFor] で実候補にし（実際の交換日集合＝全参加者が movable かつ各辺 canDo）、
     *     ラウンドロビンで並べて [KeepBest] に渡す。長い28日案が11日案に、5者案が2者案に押し出されることはない。
     *
     * 安全性: 同日の値を巡回させるだけなので被覆量は保存。異なる担当グループ間でも受け手が担当可能な日だけ候補化。
     * 希望で固定されたセル・担当不可の日は**据え置き**（3.291.0）。厳密回数ピンは [balancePinnedDays] で保存し、
     * 破る候補は exactPinRegression で除外。
     */
    private class CyclicSession(
        private val state: MagiState,
        schedule: Array<IntArray>,
        private val params: CyclicParams,
        private val shouldStop: () -> Boolean,
        private val quantitativeRangeEval: Boolean = false,
    ) {
        private val p = Problem(state, quantitativeRangeEval)
        private val work = normalizeSchedule(schedule, p)
        private val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        private val penalty = PersonalPenalty(p)
        private val pinBlocks = PinBlockAttribution()
        private val rejects = RejectStats()
        private val keepBest = KeepBest(state, p, work, params.maxEvaluations, shouldStop, pinBlocks, rejects, quantitativeRangeEval)
        private val lengths = run {
            val dyn = if (params.useDynamicBlockLens) dynamicBlockLengths(p) else emptyList()
            val fixed = params.blockLens.asSequence().filter { it in 1..p.T }.distinct().sorted().toList()
            // 動的候補を先頭に置く＝buildRanked のラウンドロビンで評価枠を先取りさせる（「動的候補を優先、固定長は
            //   fallback」の実装はここだけで足りる。優先度スコア[Priority]をいじる必要は無い）。
            (dyn.filterNot { it in fixed } + fixed).distinct().take(params.maxTotalBlockLens)
        }
        private val cycleCap = params.maxCycle.coerceAtLeast(2).coerceAtMost(MAX_PACKED_CYCLE)
        /** プールは (ブロック長 × 巡回人数 2..cycleCap) ごと。 */
        private val bucketW = (cycleCap - 1).coerceAtLeast(1)
        private val bucketCount = lengths.size * bucketW
        private val stageOneWidth = params.candidatesPerLength * params.stageOneWidthFactor

        private var bestRep = before
        private var applied = 0
        private var generated = 0            // DFS が列挙した巡回の数（見積りキーだけで選別する安価な段）
        private var builtCandidates = 0      // 実候補まで組み立てた数（プール上位のみ）
        private val builtByCycleSize = sortedMapOf<Int, Int>()   // 巡回人数別の実候補数（多者交換が実際に出ているかの診断）
        private var cycleHits = 0            // 3者以上の巡回として採用した手数（2者交換と区別してログに出す）
        private val selectedLabels = ArrayList<String>()

        /**
         * 巡回交換の1候補。[cycle] は巡回順で、`cycle[t]` は `cycle[(t+1) % n]` のシフトを受け取る
         * （n=2 なら通常の2者交換と同一）。[days] は据え置き分を除いた実際の交換日。
         */
        private class Candidate(
            val cycle: IntArray,
            val start: Int,
            val length: Int,
            val priority: Long,
            val differences: Int,
            val days: IntArray,
        )

        /** 見積りキーだけで巡回を溜める固定幅プール（満杯後の却下は O(1)、採用時のみ O(幅) で最小を取り直す）。 */
        private class StageOnePool(bucketCount: Int, private val width: Int) {
            private val keys = LongArray(bucketCount * width)
            private val nodes = LongArray(bucketCount * width)
            private val starts = IntArray(bucketCount * width)
            private val sizes = IntArray(bucketCount)
            private val minKeys = LongArray(bucketCount)

            fun size(bucket: Int) = sizes[bucket]
            fun nodesAt(bucket: Int, t: Int) = nodes[bucket * width + t]
            fun startAt(bucket: Int, t: Int) = starts[bucket * width + t]

            fun record(bucket: Int, key: Long, packedNodes: Long, start: Int) {
                val base = bucket * width
                val size = sizes[bucket]
                if (size < width) {
                    keys[base + size] = key; nodes[base + size] = packedNodes; starts[base + size] = start
                    sizes[bucket] = size + 1
                    if (size == 0 || key < minKeys[bucket]) minKeys[bucket] = key
                    return
                }
                if (key <= minKeys[bucket]) return
                var worst = 0
                for (t in 1 until width) if (keys[base + t] < keys[base + worst]) worst = t
                keys[base + worst] = key; nodes[base + worst] = packedNodes; starts[base + worst] = start
                var mn = keys[base]
                for (t in 1 until width) if (keys[base + t] < mn) mn = keys[base + t]
                minKeys[bucket] = mn
            }
        }

        fun run(): V6HotfixPasses.CyclicSwapResult {
            if (p.S < 2 || lengths.isEmpty() || params.maxPasses <= 0 || params.candidatesPerLength <= 0 ||
                params.maxEvaluations <= 0 || params.maxFocusStaff <= 0
            ) {
                return V6HotfixPasses.CyclicSwapResult(work, before.total, before.total, 0,
                    listOf(MirrorLog(tag = "AdaptiveBlockSwap", message = "対象長または職員ペアなし=スキップ")))
            }
            var pass = 0
            while (pass < params.maxPasses && !shouldStop()) {
                if (!runPass()) break
                pass++
            }
            return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, listOf(summaryLog()), pinBlocks = pinBlocks)
        }

        /** 1 pass＝候補生成→正式評価→最良1手の適用。手が無ければ false。 */
        private fun runPass(): Boolean {
            val counts = countMatrix(p, work)
            val pressure = staffPressure(p, bestRep)
            // 参加者の母集団は違反関与度の高い順に絞る（DFS の分岐を p.S でなく maxFocusStaff で抑える）。
            val focus = (0 until p.S)
                .sortedWith(compareByDescending<Int> { pressure[it] }.thenBy { it })
                .take(min(params.maxFocusStaff, p.S))
                .sorted()
            if (focus.size < 2) return false
            val pool = enumerateCycles(focus, counts, pressure)
            val ranked = buildRanked(pool, focus, counts, pressure)
            if (ranked.isEmpty()) return false

            val (accepted, rep) = keepBest.pick(ranked, bestRep, { rotate(it, forward = true) }, { rotate(it, forward = false) }) ?: return false
            rotate(accepted, forward = true)
            bestRep = rep
            applied++
            if (accepted.cycle.size >= 3) cycleHits++
            val who = accepted.cycle.joinToString("←") { staffName(state, it) } + "←" + staffName(state, accepted.cycle[0])
            selectedLabels.add("${accepted.length}日${accepted.cycle.size}者:$who ${accepted.start + 1}〜${accepted.start + accepted.length}日(${accepted.differences}セル)")
            return true
        }

        /** 巡回を1つ回す。[forward] が真なら `cycle[t] <- cycle[t+1]`、偽なら逆回し（順回しの厳密な逆変換＝巻き戻しに使う）。 */
        private fun rotate(candidate: Candidate, forward: Boolean) {
            val cycle = candidate.cycle
            val n = cycle.size
            val vals = IntArray(n)
            for (j in candidate.days) {
                for (t in 0 until n) vals[t] = work[cycle[t]][j]
                for (t in 0 until n) {
                    val src = if (forward) (t + 1) % n else (t + n - 1) % n
                    work[cycle[t]][j] = vals[src]
                }
            }
        }

        private fun movable(i: Int, j: Int) = !p.wishLocked(i, j)

        /**
         * 段①: ブロック (start, length) ごとに改善グラフの辺重みを作り、最小番号アンカーの DFS で巡回を列挙して
         * 見積りキーでプールへ入れる。巡回は focus 添字を 8bit ずつ詰めた Long で持ち回す（focus ≤ 255 のときだけ）。
         */
        private fun enumerateCycles(focus: List<Int>, counts: Array<IntArray>, pressure: LongArray): StageOnePool {
            val nf = focus.size
            val pool = StageOnePool(bucketCount, stageOneWidth)
            val edge = Array(nf) { LongArray(nf) }        // 改善グラフ: focus 添字 u→v の見積り改善量
            val edgeOk = Array(nf) { BooleanArray(nf) }   // その辺で実際に動く日が1日でもあるか
            val delta = IntArray(p.K)
            val used = BooleanArray(nf)
            val packable = nf <= MAX_PACKABLE_FOCUS
            for ((li, length) in lengths.withIndex()) {
                if (shouldStop() || !packable) break
                for (start in 0..(p.T - length)) {
                    if (shouldStop()) break
                    buildEdges(focus, counts, start, length, edge, edgeOk, delta)
                    var visits = 0
                    fun dfs(anchor: Int, depth: Int, last: Int, sum: Long, pres: Long, nodes: Long) {
                        if (depth >= 2 && edgeOk[last][anchor]) {
                            val key = (sum + edge[last][anchor]) * Priority.BALANCE + pres * Priority.PRESSURE
                            generated++
                            pool.record(li * bucketW + (depth - 2), key, nodes, start)
                        }
                        if (depth >= cycleCap) return
                        for (ni in anchor + 1 until nf) {
                            if (used[ni] || !edgeOk[last][ni]) continue
                            if (++visits > params.maxCycleVisits) return
                            used[ni] = true
                            dfs(anchor, depth + 1, ni, sum + edge[last][ni], pres + pressure[focus[ni]], nodes or (ni.toLong() shl (8 * depth)))
                            used[ni] = false
                            if (visits > params.maxCycleVisits) return
                        }
                    }
                    for (ai in 0 until nf) {
                        if (visits > params.maxCycleVisits) break
                        used[ai] = true
                        dfs(ai, 1, ai, 0L, pressure[focus[ai]], ai.toLong())
                        used[ai] = false
                    }
                }
            }
            return pool
        }

        /** 辺重み（u が v のブロックを受け取ったときの u 個人の改善見積り）。 */
        private fun buildEdges(
            focus: List<Int>, counts: Array<IntArray>, start: Int, length: Int,
            edge: Array<LongArray>, edgeOk: Array<BooleanArray>, delta: IntArray,
        ) {
            val nf = focus.size
            for (ui in 0 until nf) {
                val u = focus[ui]
                for (vi in 0 until nf) {
                    edge[ui][vi] = 0L; edgeOk[ui][vi] = false
                    if (ui == vi) continue
                    val v = focus[vi]
                    java.util.Arrays.fill(delta, 0)
                    var any = false
                    for (j in start until start + length) {
                        if (!movable(u, j) || !movable(v, j)) continue
                        val a = work[u][j]
                        val b = work[v][j]
                        if (a == b || a !in 0 until p.K || b !in 0 until p.K) continue
                        if (!p.mayPlace(u, b)) continue
                        delta[a]--; delta[b]++; any = true
                    }
                    if (!any) continue
                    var gain = 0L
                    for (k in 0 until p.K) if (delta[k] != 0) gain += penalty.gain(u, k, counts[u][k], delta[k])
                    edge[ui][vi] = gain; edgeOk[ui][vi] = true
                }
            }
        }

        /** 段②: 各プールの上位だけを実候補にし、(ブロック長 × 巡回人数) からラウンドロビンで取り出す。 */
        private fun buildRanked(pool: StageOnePool, focus: List<Int>, counts: Array<IntArray>, pressure: LongArray): List<Candidate> {
            val ordered = ArrayList<List<Candidate>>(bucketCount)
            for (b in 0 until bucketCount) {
                val size = pool.size(b)
                if (size == 0) continue
                val n = (b % bucketW) + 2
                val length = lengths[b / bucketW]
                val built = ArrayList<Candidate>(size)
                for (t in 0 until size) {
                    val packed = pool.nodesAt(b, t)
                    val cyc = IntArray(n) { focus[((packed ushr (8 * it)) and 0xFFL).toInt()] }
                    candidateFor(cyc, pool.startAt(b, t), length, counts, pressure)?.let { built.add(it) }
                }
                if (built.isEmpty()) continue
                builtCandidates += built.size
                builtByCycleSize[n] = (builtByCycleSize[n] ?: 0) + built.size
                ordered.add(built.sortedWith(
                    compareByDescending<Candidate> { it.priority }
                        .thenByDescending { it.differences }
                        .thenBy { it.start }
                        .thenBy { it.cycle[0] },
                ).take(params.candidatesPerLength))
            }
            val ranked = ArrayList<Candidate>()
            var rank = 0
            while (ordered.any { rank < it.size }) {
                for (list in ordered) list.getOrNull(rank)?.let { ranked.add(it) }
                rank++
            }
            return ranked
        }

        /**
         * 巡回 [cycle] をブロック (start, length) に適用する正式な候補を作る。
         * 交換日は「全参加者が movable」「その日の受け渡しが全辺 canDo」「実際に値が動く」を満たす日だけ。
         */
        private fun candidateFor(cycle: IntArray, start: Int, length: Int, counts: Array<IntArray>, pressure: LongArray): Candidate? {
            val n = cycle.size
            val vals = IntArray(n)
            val swapDays = ArrayList<Int>(length)
            for (j in start until start + length) {
                var ok = true
                for (t in 0 until n) {
                    val i = cycle[t]
                    // [3.291.0 候補生成の緩和] 希望固定・担当不可の日はブロックごと棄却せず据え置く。
                    if (!movable(i, j)) { ok = false; break }
                    val v = work[i][j]
                    if (v !in 0 until p.K) { ok = false; break }
                    vals[t] = v
                }
                if (!ok) continue
                var changes = false
                for (t in 0 until n) {
                    val incoming = vals[(t + 1) % n]
                    if (incoming != vals[t]) changes = true
                    if (!p.mayPlace(cycle[t], incoming)) { ok = false; break }
                }
                if (!ok || !changes) continue
                swapDays.add(j)
            }
            // 1日だけの交換は既存の同日交換/同日3者回転(CyclicSwap)と同一＝「期間をまとめて入れ替える」手にならないので除外。
            if (swapDays.size < 2) return null
            // [3.294.0 ピン保存交換] 3.293.0 の不採用内訳で、採用0の55〜80%が exactPinRegression のピン破りと判明した
            //   （実データは10名中9名の「休」が厳密ピン＝長いブロックを丸ごと交換すると必ず回数が動く）。
            if (!balancePinnedDays(cycle, swapDays, counts)) return null
            if (params.filterC3nIncrease && p.cons3n.isNotEmpty() && c3nFiresIncrease(cycle, swapDays)) {
                TuningTelemetry.c3nFilterSkipped.incrementAndGet()
                return null
            }
            val differences = swapDays.size
            if (differences < 2) return null

            var balanceGain = 0L
            var pressureSum = 0L
            val delta = IntArray(p.K)
            for (t in 0 until n) {
                val self = cycle[t]
                val giver = cycle[(t + 1) % n]
                java.util.Arrays.fill(delta, 0)
                for (j in swapDays) { delta[work[self][j]]--; delta[work[giver][j]]++ }
                for (k in 0 until p.K) if (delta[k] != 0) balanceGain += penalty.gain(self, k, counts[self][k], delta[k])
                pressureSum += pressure[self]
            }
            // 大きい推定改善を優先しつつ、違反に関与する職員と実際に変わるセル数をタイブレークに使う。
            val priority = balanceGain * Priority.BALANCE + pressureSum * Priority.PRESSURE + differences.toLong()
            return Candidate(cycle.copyOf(), start, length, priority, differences, swapDays.toIntArray())
        }

        /**
         * [3.295.0 境界c3nの事前フィルタ / 3.296.0 で既定OFF] この巡回交換では covU/covO は同日置換で不変・groupViol は
         * canDo・pref は movable で不変なので、変化しうる HARD は c3n だけ。c3n は職員行ローカルなので、参加者の行に交換を
         * 当てた fire 数を数えれば近似でなく厳密に判定できる。`firesAfter > firesBefore` の候補だけを落とす。
         */
        private fun c3nFiresIncrease(cycle: IntArray, swapDays: List<Int>): Boolean {
            val n = cycle.size
            var firesBefore = 0
            var firesAfter = 0
            for (t in 0 until n) {
                val self = cycle[t]
                val giver = cycle[(t + 1) % n]
                val row = work[self].copyOf()
                firesBefore += C1DeltaPrefilter.staffC3nFires(p, row)
                for (j in swapDays) row[j] = work[giver][j]
                firesAfter += C1DeltaPrefilter.staffC3nFires(p, row)
            }
            return firesAfter > firesBefore
        }

        /**
         * [ピン保存交換] 交換日 [swapDays] を「厳密ピン(lo==hi)のシフト回数が1つも動かない」部分集合へ絞る。
         * 絞れた場合だけ true（[swapDays] を破壊的に更新）。
         *
         * 対象は**いま満たされている**厳密ピンだけ（`counts == lo == hi`）。すでに外れているピンは動かして直せる余地が
         * あるため拘束しない（悪化は従来どおり `exactPinRegression` が弾く）。
         *
         * 各日 j について、参加者 t のピン付きシフト k の増減 `d = [直前者が k] - [自分が k]`（∈ {-1,0,+1}）を並べた
         * **符号ベクトル**を作る。交換日集合の総和がゼロベクトルならピンは1つも動かない。ゼロベクトルの日は常に採り、
         * 非ゼロの日は**符号が正反対の日と対にして**採る（打ち消し合う）。3日以上での相殺は拾わないが安価で安全側
         * （採れなかった日を落とすだけ＝退化しない）。
         */
        private fun balancePinnedDays(cycle: IntArray, swapDays: ArrayList<Int>, counts: Array<IntArray>): Boolean {
            val n = cycle.size
            val maxSlots = params.maxPinSlots
            var slots = 0
            val slotStaff = IntArray(maxSlots + 1)
            val slotShift = IntArray(maxSlots + 1)
            for (t in 0 until n) {
                val i = cycle[t]
                for (k in 0 until p.K) {
                    val lo = p.rangeLo[i][k]
                    if (lo == Int.MIN_VALUE || lo != p.rangeHi[i][k] || counts[i][k] != lo) continue
                    if (slots >= maxSlots) return true   // 対象が多すぎる＝この安価な相殺では扱えない（従来どおり）
                    slotStaff[slots] = t; slotShift[slots] = k; slots++
                }
            }
            if (slots == 0) return true   // ピン無し＝制約なし（コストゼロで従来と同一）

            fun signatureOf(j: Int): Long {
                var sig = 0L
                for (s in 0 until slots) {
                    val t = slotStaff[s]
                    val k = slotShift[s]
                    val mine = if (work[cycle[t]][j] == k) 1 else 0
                    val incoming = if (work[cycle[(t + 1) % n]][j] == k) 1 else 0
                    val d = incoming - mine
                    if (d > 0) sig = sig or (1L shl (2 * s))
                    else if (d < 0) sig = sig or (2L shl (2 * s))
                }
                return sig
            }
            // 符号の反転（+1↔-1 のビットを入れ替える）。
            fun negate(sig: Long): Long = ((sig and 0x5555_5555_5555_5555L) shl 1) or ((sig ushr 1) and 0x5555_5555_5555_5555L)

            val bySig = LinkedHashMap<Long, ArrayList<Int>>()
            for (j in swapDays) bySig.getOrPut(signatureOf(j)) { ArrayList() }.add(j)

            val kept = ArrayList<Int>(swapDays.size)
            bySig[0L]?.let { kept.addAll(it) }
            val done = HashSet<Long>()
            done.add(0L)
            for ((sig, days) in bySig) {
                if (sig in done) continue
                done.add(sig)
                val opposite = negate(sig)
                done.add(opposite)
                val other = bySig[opposite] ?: continue
                val pairs = min(days.size, other.size)
                for (t in 0 until pairs) { kept.add(days[t]); kept.add(other[t]) }
            }
            if (kept.size < 2) return false
            kept.sort()
            swapDays.clear()
            swapDays.addAll(kept)
            return true
        }

        private fun summaryLog(): MirrorLog = MirrorLog(tag = "AdaptiveBlockSwap",
            message = "可変長ブロック巡回交換[${lengths.joinToString("/")}日・最大${cycleCap}者]: total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard}" +
                " score ${before.weightedScore.toLong()}->${bestRep.weightedScore.toLong()} 採用${applied}回(うち3者以上${cycleHits}回)" +
                " 巡回${generated}件(実候補${builtCandidates}件" +
                (if (builtByCycleSize.isEmpty()) "" else " 内訳 " + builtByCycleSize.entries.joinToString(" ") { "${it.key}者:${it.value}" }) +
                ")/正式評価${keepBest.evaluated}件" +
                (if (applied == 0) " [頭打ち=改善手なし]" else "") +
                rejects.logSuffix() +
                (if (selectedLabels.isNotEmpty()) " 対象: ${selectedLabels.joinToString(", ")}" else ""))
    }

    // ===== 厳密窓交換（STRICT_WHOLE_WINDOW） =====

    /**
     * [3.495.0/ユーザー提示の設計「違反アンカー型・可変長ウィンドウ交換」] STRICT_WHOLE_WINDOW の本体。
     *
     * 基本操作＝職員 a, b・開始日 s・長さ L について**同じ日付範囲**を丸ごと交換する（`a[s..s+L) ↔ b[s..s+L)`）。
     * 同じ日付どうしなので日別のシフト人数は完全に保存される。所属群が違うと群別人数は変わるため、同一 sgrp/ssk の
     * 相手を第1層、それ以外を第2層（[Priority.CROSS_GROUP] のペナルティ）にする。
     *
     * 1. 違反アンカー（[collectAnchors]）: セル違反の日／回数超過は対象シフトに入っている全日／回数不足は「他職員が k、
     *    対象職員が k 以外」の交換可能な日を逆引き／連続・パターン違反は違反セルに加え違反区間の両端／週偏りは長さ7の窓を
     *    全開始位置で。
     * 2. 窓（[windowLengths]／[tryWindow]）: アンカー日 j を含む窓（start ∈ (j-L+1)..j）と、j に接する窓（末尾が j-1／
     *    先頭が j+1）。L は 1..Lmax（=min(maxLen,T)）＋同一シフト連の実長＋違反パターン長＋7＋c1 の窓長。長い連続違反
     *    （連長 > Lmax）があるときだけ最大 longLen へ拡張。
     * 3. 窓全体の成立条件: 全日について両者とも希望固定でなく、相互に担当可、無効値を含まない、窓が完全一致でない。
     *    さらに満たされている厳密回数固定（lo==hi）が動かない（シフト別ヒストグラム delta で判定）。同じ候補は
     *    正規化キー (min(a,b), max(a,b), start, L) で1回だけ。
     * 4. 安価な優先度（[Priority]）: HARD改善見積り（両行の c3n fire 差）・アンカー違反改善・回数違反改善（改善する
     *    (職員,シフト) 数 − 悪化する数）・職員違反圧力・変更セル数・群不一致。
     * 5. 正式採否は [KeepBest]（HARD→weighted→total・厳密ピン保護）。**pass ごとに最良の1手だけ採用**し、採用後に
     *    アンカーと回数を再計算する。
     */
    private class StrictSession(
        private val state: MagiState,
        schedule: Array<IntArray>,
        private val params: StrictParams,
        private val shouldStop: () -> Boolean,
        private val quantitativeRangeEval: Boolean = false,
    ) {
        private val p = Problem(state, quantitativeRangeEval)
        private val work = normalizeSchedule(schedule, p)
        private val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        private val penalty = PersonalPenalty(p)
        private val pinBlocks = PinBlockAttribution()
        private val rejects = RejectStats()
        private val keepBest = KeepBest(state, p, work, params.maxEvaluations, shouldStop, pinBlocks, rejects, quantitativeRangeEval)
        private val lMax = min(params.maxLen.coerceAtLeast(1), p.T)
        private val lLong = min(params.longLen.coerceAtLeast(lMax), p.T)
        /** 規則由来の窓長: c1 の窓長・連続規則のパターン長・7（週）。 */
        private val ruleLens: Set<Int> = HashSet<Int>().also { lens ->
            for (c in p.cons1) if (c.day1 in 1..lLong) lens.add(c.day1)
            for (list in listOf(p.cons3, p.cons3n, p.cons3m, p.cons3mn)) for (c in list) if (c.seq.size in 1..lLong) lens.add(c.seq.size)
            if (7 <= p.T) lens.add(7)
        }

        private var bestRep = before
        private var applied = 0
        private var windowsTried = 0
        private var built = 0
        private var pinDropped = 0
        private var anchorsTotal = 0
        private val selectedLabels = ArrayList<String>()

        private class Candidate(val a: Int, val b: Int, val start: Int, val length: Int, val priority: Long, val changed: Int, val sameGroup: Boolean)

        /** 1 pass ぶんの候補生成の作業域（正規化キーの重複排除・シフト別ヒストグラム）。 */
        private inner class PassContext(val counts: Array<IntArray>, val pressure: LongArray) {
            val seen = HashSet<Long>()
            val candidates = ArrayList<Candidate>()
            val delta = IntArray(p.K)
        }

        fun run(): V6HotfixPasses.CyclicSwapResult {
            if (p.S < 2 || p.T < 1 || params.maxPasses <= 0 || params.maxEvaluations <= 0) {
                return V6HotfixPasses.CyclicSwapResult(work, before.total, before.total, 0,
                    listOf(MirrorLog(tag = "AnchoredWindowSwap", message = "違反アンカー窓交換: 職員ペアなし=スキップ")))
            }
            var pass = 0
            while (pass < params.maxPasses && !shouldStop()) {
                if (!runPass()) break
                pass++
            }
            return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, listOf(summaryLog()), pinBlocks = pinBlocks)
        }

        private fun runPass(): Boolean {
            val anchors = collectAnchors(bestRep)
            if (anchors.isEmpty()) return false
            anchorsTotal += anchors.size
            val ctx = PassContext(countMatrix(p, work), staffPressure(p, bestRep))
            for (anc in anchors) {
                if (shouldStop()) break
                for (l in windowLengths(anc)) {
                    if (l < 1 || l > p.T) continue
                    val j = anc.day
                    if (j == null) { for (st in 0..(p.T - l)) tryWindow(ctx, anc, st, l); continue }
                    for (st in (j - l + 1)..j) tryWindow(ctx, anc, st, l)
                    tryWindow(ctx, anc, j - l, l)      // 末尾が j-1（アンカーに接する連）
                    tryWindow(ctx, anc, j + 1, l)      // 先頭が j+1
                }
            }
            val candidates = ctx.candidates
            if (candidates.isEmpty()) return false
            candidates.sortWith(compareByDescending<Candidate> { it.priority }.thenByDescending { it.changed }.thenBy { it.start }.thenBy { it.a }.thenBy { it.b })

            val (accepted, rep) = keepBest.pick(candidates, bestRep, ::swap, ::swap) ?: return false
            swap(accepted)
            bestRep = rep
            applied++
            selectedLabels.add("${accepted.length}日:${staffName(state, accepted.a)}↔${staffName(state, accepted.b)} ${accepted.start + 1}〜${accepted.start + accepted.length}日(${accepted.changed}セル${if (accepted.sameGroup) "" else "・群違い"})")
            return true
        }

        /** 同じ日付範囲の一括交換（自己逆変換＝巻き戻しにも使う）。 */
        private fun swap(c: Candidate) {
            for (d in c.start until c.start + c.length) { val t = work[c.a][d]; work[c.a][d] = work[c.b][d]; work[c.b][d] = t }
        }

        /** 職員 i の日 j を含む同一シフト連の範囲。 */
        private fun runOf(i: Int, j: Int): IntRange {
            val n = work[i][j]; var a = j; var b = j
            while (a > 0 && work[i][a - 1] == n) a--
            while (b < p.T - 1 && work[i][b + 1] == n) b++
            return a..b
        }

        private fun sameGroup(a: Int, b: Int) = p.sgrp[a] == p.sgrp[b] && p.ssk[a] == p.ssk[b]

        private fun collectAnchors(rep: ViolationReport): List<ViolationAnchor> {
            val anchors = ArrayList<ViolationAnchor>()
            for ((key, fams) in rep.cellFamilies) {
                val i = key.substringBefore(',').toIntOrNull() ?: continue
                val j = key.substringAfter(',').toIntOrNull() ?: continue
                if (i !in 0 until p.S || j !in 0 until p.T) continue
                val fs = fams.toSet()
                anchors.add(ViolationAnchor(i, j, null, null, fs))
                // 連続・パターン違反は違反区間（同一シフト連）の両端も起点にする。
                if (fs.any { it.startsWith("vio-c3") || it == "vio-c1" } && work[i][j] in 0 until p.K) {
                    val r = runOf(i, j)
                    if (r.first != j) anchors.add(ViolationAnchor(i, r.first, null, null, fs))
                    if (r.last != j) anchors.add(ViolationAnchor(i, r.last, null, null, fs))
                }
            }
            for ((key, cls) in rep.countViolations) {
                val i = key.substringBefore(',').toIntOrNull() ?: continue
                val k = key.substringAfter(',').toIntOrNull() ?: continue
                if (i !in 0 until p.S || k !in 0 until p.K) continue
                val dir = when (cls) { "vio-high", "vio-aptHigh" -> CountDirection.HIGH; "vio-low", "vio-aptLow" -> CountDirection.LOW; else -> null } ?: continue
                val fs = setOf(cls)
                if (dir == CountDirection.HIGH) {
                    for (d in 0 until p.T) if (work[i][d] == k) anchors.add(ViolationAnchor(i, d, k, dir, fs))
                    continue
                }
                // 不足: 他職員が k を持ち、対象職員は k 以外で希望固定でない日を逆引き。
                for (d in 0 until p.T) {
                    if (work[i][d] == k || p.wishLocked(i, d)) continue
                    if ((0 until p.S).any { b -> b != i && work[b][d] == k && !p.wishLocked(b, d) }) anchors.add(ViolationAnchor(i, d, k, dir, fs))
                }
            }
            rep.distLocations["weekly"]?.forEach { row ->
                val i = row.firstOrNull() ?: return@forEach
                if (i in 0 until p.S && 7 <= p.T) anchors.add(ViolationAnchor(i, null, null, null, setOf("weekly")))
            }
            return anchors
        }

        /** アンカーごとの窓長の集合（週偏りは 7 のみ）。 */
        private fun windowLengths(anc: ViolationAnchor): Set<Int> {
            val lens = HashSet<Int>()
            val day = anc.day ?: return lens.also { it.add(7) }
            for (l in 1..lMax) lens.add(l)
            lens.addAll(ruleLens)
            if (work[anc.staff][day] in 0 until p.K) {
                val rl = runOf(anc.staff, day).count()
                if (rl <= lLong) lens.add(rl)   // 長い連続違反があるときだけ longLen まで拡張
            }
            return lens
        }

        /** 窓 (start, length) について相手 b を全職員から探し、成立する組を候補に積む。 */
        private fun tryWindow(ctx: PassContext, anc: ViolationAnchor, start: Int, length: Int) {
            if (start < 0 || length < 1 || start + length > p.T) return
            val a = anc.staff
            windowsTried++
            for (b in 0 until p.S) {
                if (b == a) continue
                val key = (min(a, b).toLong() shl 48) or (max(a, b).toLong() shl 32) or (start.toLong() shl 16) or length.toLong()
                if (key in ctx.seen) continue
                // [3.499.0] 方向フィルタはアンカーごとに違うので、重複排除は**通過した窓だけ**に掛ける（旧: 先に seen へ入れて
                //   いたため、回数超過アンカーが「k が減らない相手」として捨てた窓を、同じ窓を要る別アンカーが二度と作れなかった）。
                if (!directionAllows(anc, a, b, start, length)) continue
                ctx.seen.add(key)
                buildCandidate(ctx, anc, a, b, start, length)?.let { ctx.candidates.add(it); built++ }
            }
        }

        /** 回数違反の方向付け: 超過なら窓内で k が減る相手、不足なら増える相手だけ。回数アンカー以外は常に許す。 */
        private fun directionAllows(anc: ViolationAnchor, a: Int, b: Int, start: Int, length: Int): Boolean {
            val k = anc.shift ?: return true
            val dir = anc.direction ?: return true
            var ca = 0; var cb = 0
            for (d in start until start + length) { if (work[a][d] == k) ca++; if (work[b][d] == k) cb++ }
            return if (dir == CountDirection.HIGH) cb < ca else cb > ca
        }

        private fun buildCandidate(ctx: PassContext, anc: ViolationAnchor, a: Int, b: Int, start: Int, length: Int): Candidate? {
            val k = anc.shift
            val end = start + length
            // 窓全体の成立条件（1日でも不成立なら窓ごと棄却＝部分交換しない）。
            val delta = ctx.delta
            java.util.Arrays.fill(delta, 0)
            var changed = 0
            for (d in start until end) {
                val ka = work[a][d]; val kb = work[b][d]
                if (ka !in 0 until p.K || kb !in 0 until p.K) return null
                if (p.wishLocked(a, d) || p.wishLocked(b, d)) return null
                if (!p.mayPlace(a, kb) || !p.mayPlace(b, ka)) return null
                if (ka != kb) { changed++; delta[kb]++; delta[ka]-- }
            }
            if (changed == 0) return null
            val counts = ctx.counts
            if (movesSatisfiedExactPin(a, b, delta, counts)) { pinDropped++; return null }

            var hardGain = 0L
            if (p.cons3n.isNotEmpty()) {
                val ra = work[a].copyOf(); val rb = work[b].copyOf()
                val beforeF = C1DeltaPrefilter.staffC3nFires(p, ra) + C1DeltaPrefilter.staffC3nFires(p, rb)
                for (d in start until end) { val t = ra[d]; ra[d] = rb[d]; rb[d] = t }
                val afterF = C1DeltaPrefilter.staffC3nFires(p, ra) + C1DeltaPrefilter.staffC3nFires(p, rb)
                hardGain = (beforeF - afterF).toLong()
            }
            var anchorGain = 0L
            if (k != null && anc.direction != null) {
                anchorGain = java.lang.Long.signum(penalty.gain(a, k, counts[a][k], delta[k])).toLong()
            } else if (anc.day != null && anc.day in start until end) {
                anchorGain = if (work[a][anc.day] != work[b][anc.day]) 1L else 0L
            }
            var countGain = 0L
            for (kk in 0 until p.K) {
                if (delta[kk] == 0) continue
                countGain += java.lang.Long.signum(penalty.gain(a, kk, counts[a][kk], delta[kk])) +
                    java.lang.Long.signum(penalty.gain(b, kk, counts[b][kk], -delta[kk]))
            }
            val same = sameGroup(a, b)
            val priority = hardGain * Priority.HARD + anchorGain * Priority.ANCHOR + countGain * Priority.COUNT +
                (ctx.pressure[a] + ctx.pressure[b]) * Priority.PRESSURE - changed * Priority.CHANGED_COST - (if (same) 0L else Priority.CROSS_GROUP)
            return Candidate(a, b, start, length, priority, changed, same)
        }

        /** 満たされている厳密回数固定 (lo==hi==count) が窓交換で動くか（ヒストグラム判定）。 */
        private fun movesSatisfiedExactPin(a: Int, b: Int, delta: IntArray, counts: Array<IntArray>): Boolean {
            for (kk in 0 until p.K) {
                if (delta[kk] == 0) continue
                for (st in intArrayOf(a, b)) {
                    val lo = p.rangeLo[st][kk]
                    if (lo != Int.MIN_VALUE && lo == p.rangeHi[st][kk] && counts[st][kk] == lo) return true
                }
            }
            return false
        }

        private fun summaryLog(): MirrorLog = MirrorLog(tag = "AnchoredWindowSwap",
            message = "違反アンカー窓交換[窓1〜${lMax}日(連続違反は〜${lLong})・窓全体を一括]: total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard}" +
                " score ${before.weightedScore.toLong()}->${bestRep.weightedScore.toLong()} 採用${applied}回" +
                " アンカー${anchorsTotal}件 窓${windowsTried}件 実候補${built}件(厳密固定で除外${pinDropped})/正式評価${keepBest.evaluated}件" +
                (if (applied == 0 && anchorsTotal > 0) " [頭打ち=改善手なし]" else "") +
                rejects.logSuffix() +
                (if (selectedLabels.isNotEmpty()) " 対象: ${selectedLabels.joinToString(", ")}" else ""))
    }
}
