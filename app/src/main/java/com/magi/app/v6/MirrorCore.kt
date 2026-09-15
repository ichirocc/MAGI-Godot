package com.magi.app.v6

import com.magi.app.model.MagiState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random
import kotlin.math.exp
import kotlin.math.max

/**
 * Kotlin port of magi_python_mirror.py.
 *
 * The high-speed native SA remains the main optimizer, but this module brings the
 * mirror app's operational layer into the Android app: unified violation breakdown,
 * greedy/simple schedule creation, light local search, and CSV round-trip helpers.
 */
data class MirrorLog(
    val ts: Long = System.currentTimeMillis(),
    val iter: Long = 0,
    val level: String = "I",
    val tag: String,
    val message: String,
)

data class ViolationReport(
    val violations: Map<String, String>,
    val needViolations: Map<String, String>,
    val countViolations: Map<String, String>,
    // [Set化] セル("i,j")に重なった全違反クラスを重み降順で保持（violations は最重1クラス＝後方互換のまま）。
    //   タップ時の全列挙と E7 フィルタの整合（最重族がOFFでも表示中の族があれば枠を出す）に使う。表示のみ。
    val cellFamilies: Map<String, List<String>> = emptyMap(),
    /**
     * [3.353.0] 回数キー("i,k")に重なった全違反クラスを重み降順で保持（`countViolations` は最重1クラス＝
     * 後方互換のまま）。`cellFamilies` の回数空間版。低い重みの族（apt・c2）が重い族（low 90/high 25）と
     * 同じ (職員,シフト) に重なると `countViolations` から消え、診断に一切現れなかった
     * （実機ログ: 内訳 c2=1 なのに詳細行が無く、apt=29 に対し表示は7箇所ぶんしか無い）。表示のみ。
     */
    val countFamilies: Map<String, List<String>> = emptyMap(),
    /**
     * [/code-review, 3.111.0/3.353.0と同根の第3キー空間] 被覆キー("k,j")に重なった全違反クラスを
     * 重み降順で保持（`needViolations` は最重1クラス＝後方互換のまま）。`cellFamilies`/`countFamilies`の
     * 被覆空間版。covU(重み8000)と同じ(シフト,日)に c41/c41s(重み1)が重なると、covUが表示を独占し
     * `needViolations` からc41/c41sが消えていた（`breakdownLocations`の「群のレンジ」タップ→場所一覧が
     * 内訳件数より少なく見える）。表示のみ。
     */
    val needFamilies: Map<String, List<String>> = emptyMap(),
    val breakdown: Map<String, Int>,
    val total: Int,
    val hard: Int,
    val soft: Int,
    val weightedScore: Double,
    // [場所表示] fair/weekly はセル単位でなく職員/群×シフト単位の偏りのため violations(mark) に出せない。
    //   内訳パネルの場所表示専用に、職員単位の偏り箇所を構造化して持つ（グリッドには出さない＝飽和回避）。
    //   "weekly" -> [[staffIdx, dev], ...] / "fair" -> [[staffIdx, shiftIdx, dev], ...]（dev降順）。表示のみ・スコア不変。
    val distLocations: Map<String, List<List<Int>>> = emptyMap(),
    val logs: List<MirrorLog> = emptyList(),
)

/**
 * [3.287.0 keep-best統一＝改善の質] 全 Kotlin keep-best 比較器の単一ソース。
 * 順序は hard → weightedScore → total（旧: hard → total → weightedScore）。
 * 根拠: SA/ALNS/C++ の評価器 soft は元々「重み付き和」＝探索本体は weighted を最適化しているのに、
 * Kotlin 側の keep-best だけが total(重み無視の生カウント)優先で、low90/high45 の厳密ピンを
 * 軽い族(c3=3等)の件数と交換する「total改善・weighted悪化」の採用を許していた（実機 2026-12 で
 * 吉江/桒澤の休 lo==hi=10 が割れた実例）。第2キーを weightedScore に揃えることで、keep-best が
 * チェッカーの重み階層（=業務優先度）と評価器の目的関数の両方に一致する。total は決定性のための
 * 第3タイブレークに降格。C++ 側は元から weighted のため変更不要（パリティ不変）。
 */
/**
 * keep-best の辞書式順序そのもの（hard → weightedScore → total）。**この1つが唯一の定義**で、
 * `betterReport` も並べ替えもここへ委譲する。
 *
 * 手で同じ3キーを書き写すと、順序を変えたときに写した側だけ取り残される（3.287.0 で第2キーを
 * total→weightedScore へ統一したのに `V6LateOperators.gateW`(3.309.0)・C1広域ビーム(3.336.0/3.340.0)・
 * `AdaptiveEliteArchive`・「他の案」の並べ替えが順に取り残された実績がある）。比較を足すときは
 * 写さずにこれを使う。
 */
val reportComparator: Comparator<ViolationReport> = Comparator { a, b ->
    when {
        a.hard != b.hard -> a.hard.compareTo(b.hard)
        a.weightedScore != b.weightedScore -> a.weightedScore.compareTo(b.weightedScore)
        else -> a.total.compareTo(b.total)
    }
}

fun betterReport(a: ViolationReport, b: ViolationReport): Boolean = reportComparator.compare(a, b) < 0

data class ScheduleRunResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    /** CSV取込で氏名が一致したスタッフ行数。最適化系の結果では未使用(-1)。 */
    val matched: Int = -1,
    /**
     * [3.410.0/I-01] CSV取込で**シフト一覧に無い記号**だったセルの数と、その記号（多い順・上位）。
     * 旧: 未知記号は黙って読み飛ばし（既存セル維持）、勤務表CSVの生成経路では初期値の「休」のまま
     * 残っていた＝誤字や凡例漏れが**休として静かに混入**した。3.329.0 が希望・制約CSVで潰した
     * 「読めない行を捨てたまま取り込む」族の、勤務表CSV側の残り。呼出側が件数を必ず出す。
     */
    val unknownCells: Int = 0,
    val unknownSymbols: List<String> = emptyList(),
    /**
     * [3.413.0/I-08] 引用符が閉じないまま入力が終わった＝開いた引用符以降が1セルへ吸い込まれ
     * **残りの行が丸ごと消えた**。呼出側からは「一致した氏名が少ないCSV」と区別が付かず
     * 部分的な成功に見えるため、旗として持ち上げて必ず知らせる。
     */
    val unclosedQuote: Boolean = false,
)

data class LightOptimizeResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    val iterations: Long,
    val accepts: Long,
    val elapsedMs: Long,
)

object MirrorKeys {
    val hard = listOf("groupViol", "c3n", "covU", "pref", "c3w")
    val soft = listOf("c1", "c2", "c3", "c3m", "c3mn", "c41", "c42", "c41s", "c42s", "covO", "low", "high", "apt", "fair", "weekly")
    // [3.542.0] c3w は末尾＝C++ kBreakdownNames / 言語跨ぎ期待値ファイルの添字を既存19族から動かさない。
    val all = listOf("c1", "c2", "c3", "c3n", "c3m", "c3mn", "c41", "c42", "c41s", "c42s", "covU", "covO", "pref", "low", "high", "groupViol", "apt", "fair", "weekly", "c3w")
    // [N2/⛏11] weightedScore の重み（単一の真実）。UI の重み表もこのマップを描画して
    //   最適化器とのドリフトを防ぐ。挿入順 = weightedScore の加算順（Double 結果を不変に保つ）。
    // [HF77明示数値指示・全面見直し 3.522.0] tools/loop 34ケース×10seedのbaseline対比ベンチマークで決定
    //   （経緯・数値の詳細は docs/history/3.4xx.md）。high(25)は3.520.0以来不変。
    // **ここを変えたら `Evaluator.fullEvalParts` のリテラルと C++ も同時に変える**。
    //   Kotlin 側のずれは `ObjectiveParityTest`、C++ 側は native-parity CI が捕まえる。
    val weights: Map<String, Double> = linkedMapOf(
        "groupViol" to 11000.0, "covU" to 10000.0, "c3n" to 9000.0, "c3w" to 9000.0, "pref" to 8000.0,
        "low" to 120.0, "c3mn" to 90.0, "c1" to 50.0, "high" to 25.0, "covO" to 10.0,
        "c3" to 15.0, "c3m" to 10.0,
        "c41" to 1.0, "c42" to 1.0, "c41s" to 6.0, "c42s" to 6.0,
        "c2" to 4.0, "apt" to 4.0, "fair" to 2.0, "weekly" to 2.0,
    )

    // [表示優先度/HF77明示指示 2026-07-20] aptLow/aptHigh は apt の表示専用サブクラス（重み表(WeightTableCard)には
    //   出さない＝weights map 自体には追加しない）。markCount/cellFamilies の重み優先比較では実体である apt の
    //   重み(1.0)をそのまま使う（旧: weights にキーが無く 0.0 扱い＝常に最下位に劣後していた。ユーザー指示により
    //   「aptLow/aptHighは重み1.0扱いにする」＝c2/c41/c42/c41s/c42s/fair/weekly と同格の重み1.0で競わせる）。
    /**
     * [3.395.0/高速化] `all` の族名 → 添字。`check()` の `inc` はここで引いた添字で `IntArray` を
     * 加算する（旧: `breakdown[key] = (breakdown[key] ?: 0) + amount` ＝ハッシュ探索2回＋Int のボクシング。
     * 実測でこの1関数が `check()` の自己時間の 7.4% を占めていた）。
     */
    val index: Map<String, Int> = all.withIndex().associate { (i, k) -> k to i }

    fun weightOf(family: String): Double = when (family) {
        "aptLow", "aptHigh" -> weights["apt"] ?: 0.0
        else -> weights[family] ?: 0.0
    }
}

object UnifiedViolationChecker {
    /**
     * [3.395.0/高速化] mark 系の重み優先比較のための事前表。
     *
     * 旧: `MirrorKeys.weightOf(prev.removePrefix("vio-"))` ＝**マークが重なるたびに String を1個作る**
     * うえ、`weightOf` は String の `when`（ハッシュ分岐＋equals）。実測で `mark` が `check()` の
     * 自己時間の 14.1%、`weightOf` が 2.7% を占めていた。クラス名から直接引けば割り当てゼロで済む。
     * 値は `weightOf` から作るので**重みの定義は `MirrorKeys` の1箇所のまま**（ドリフトしない）。
     */
    private val classWeight: Map<String, Double> by lazy { vioClass.entries.associate { it.value to MirrorKeys.weightOf(it.key) } }

    private val vioClass = mapOf(
        "c1" to "vio-c1", "c2" to "vio-c2", "c3" to "vio-c3", "c3n" to "vio-c3n", "c3w" to "vio-c3w",
        "c3m" to "vio-c3m", "c3mn" to "vio-c3mn", "c41" to "vio-c41", "c42" to "vio-c42",
        "c41s" to "vio-c41s", "c42s" to "vio-c42s",
        "covU" to "vio-covU", "covO" to "vio-covO", "pref" to "vio-pref",
        "low" to "vio-low", "high" to "vio-high", "groupViol" to "vio-groupViol",
        // 適切回数(双方向目標): 不足=赤 / 超過=橙（range と同色だが家族は別。TallyCard/内訳で個別解決可能にする）。
        "aptLow" to "vio-aptLow", "aptHigh" to "vio-aptHigh",
    )

    fun check(
        state: MagiState,
        schedule: Array<IntArray> = state.schedule.toIntArray2D(),
        quantitativeRangeEval: Boolean = false,
    ): ViolationReport {
        val t0 = System.nanoTime()
        val p = cachedProblem(state, quantitativeRangeEval)
        val s = normalizeSchedule(schedule, p)
        // [3.395.0/高速化] 集計は添字加算の IntArray で行い、最後に `MirrorKeys.all` の順で Map へ起こす
        //   （返り値の中身と順序は従来と完全に同じ）。`inc` に渡すキーは全て `MirrorKeys.all` にある
        //   ことを確認済み（c3系は `checkC3Family` が受け取った族名をそのまま返す）。
        val bd = IntArray(MirrorKeys.all.size)

        fun inc(key: String, amount: Int = 1) { bd[MirrorKeys.index.getValue(key)] += amount }
        // [判読性/レビュー指摘] 同一セルに複数族が重なる場合、従来は「後にマークした族」が無条件上書きで、
        //   評価順の最後(c3系)が pref/groupViol(必須)のマークを潰し、実線枠が角マーク(軽ソフト)へ降格し得た
        //   （重大度の逆転）。MirrorKeys.weights を表示優先度として使い、常に最重の族のマークを保持する。
        //   1セル1クラスの型は維持（複数違反の全保持=Set化は別段の改修）。inc/breakdown は従来どおり全件計上
        //   ＝スコアリング不変・表示のみ。
        // [Set化] 重なった全クラスは cellFams("i,j"→クラス列)にも蓄積（重複なし・後で重み降順に整列）。
        //   violations は従来どおり最重1クラス＝既存読者は不変。
        val cellFams = linkedMapOf<String, MutableList<String>>()
        val countFams = linkedMapOf<String, MutableList<String>>()
        val needFams = linkedMapOf<String, MutableList<String>>()
        // [3.395.0/高速化] 「最重1クラス」を毎回ここで決めるのをやめ、末尾で `cellFams` の**整列済み先頭**
        //   から起こす。両者は定義上いつも同じ値になる：整列は重み降順の**安定ソート**なので先頭＝最初に
        //   マークされた最大重みのクラス、旧ロジックの「厳密に重いものだけが置き換える」も同じものを残す
        //   （このファイルの `cellFamilies` の注記が元から「先頭は violations[key] と常に一致」と書いている）。
        //   挿入順も同じ（どちらも最初のマークで生える LinkedHashMap）。これで1マークあたり
        //   ハッシュ探索1回＋重み比較2回が消える（実測で `mark` が `check()` 自己時間の 20% だった）。
        fun mark(i: Int, j: Int, family: String) {
            val cls = vioClass[family] ?: family
            val fams = cellFams.getOrPut("$i,$j") { ArrayList(2) }
            if (cls !in fams) fams.add(cls)
        }
        // [判読性] mark() と同じ重み優先。旧: 後勝ちで軽い族(旧 covO=0.5 等)が重い族(c41 等)のマークを上書きし得た。
        // [/code-review] 重なった全クラスを needFams へ蓄積（重複なし・後で重み降順に整列）。
        // [3.395.0] mark() と同じ理由で「最重1クラス」は末尾で先頭から起こす。
        fun markNeed(k: Int, j: Int, family: String) {
            val cls0 = vioClass[family] ?: family
            val fams = needFams.getOrPut("$k,$j") { ArrayList(2) }
            if (cls0 !in fams) fams.add(cls0)
        }
        // [防御的統一/敵対的監査で確認] mark()/markNeed() と同じ重み優先へ統一。旧: 無条件上書き
        //   (last-write-wins)は、現在の呼出順(c2→low→high→apt)と apt呼出側の手動 containsKey ガードが
        //   偶然噛み合っているだけで安全が成立していた（低い重みの族が後から呼ばれると高い重みの族の
        //   マークを消し得る潜在的な地雷）。今回は実害の確認された不具合ではないが、mark/markNeed と
        //   同じ規律に揃えて将来の族追加に対して頑健にする。
        //   [3.243.0, HF77明示指示] aptLow/aptHigh は `MirrorKeys.weightOf` により apt 本体と同じ重み1.0で
        //   解決する（旧: weights にキーが無く 0.0 扱い＝c2/low/high 等の全実族に対し常に劣後していた）。
        //   同重み同士は先勝ち(mark順)＝c2(先に呼ばれる)が apt(後に呼ばれる)より引き続き優先される。
        //   表示のみ・スコアリング(weightedScore/breakdown/inc)は不変。
        // [3.353.0] 重なった全クラスを countFams へ蓄積（重複なし・後で重み降順に整列）。
        // [3.395.0] mark() と同じ理由で「最重1クラス」は末尾で先頭から起こす。
        fun markCount(i: Int, k: Int, family: String) {
            val cls0 = vioClass[family] ?: family
            val fams = countFams.getOrPut("$i,$k") { ArrayList(2) }
            if (cls0 !in fams) fams.add(cls0)
        }
        fun cellIs(i: Int, j: Int, k: Int): Boolean = i in 0 until p.S && j in 0 until p.T && s[i][j] == k

        for (c in p.cons1) {
            for (i in 0 until p.S) {
                if (!p.canDo(i, c.shiftIdx)) continue
                var j = 0
                // [視認性] scoring(inc)は各違反窓ごとに従来どおり計上（不変）。表示(mark)だけは
                //   窓幅ぶんの塗り広げを止め、違反窓ランの先頭1セルにアンカーする。スライド窓が重複して
                //   持続不足で行全体を破線で埋めていた（1論理違反≒窓幅×重複数セル）のを 1不足領域=1マーカーへ。
                var prevViol = false
                // [3.395.0/高速化] 旧: 窓の開始位置ごとに day1 個を数え直す O(T×day1)。窓は1日ずつ滑るので
                //   「出た日を引き、入った日を足す」だけで同じ数になる＝O(T)。`j` は `0..T-day1`・`l < day1`
                //   なので `j+l <= T-1`＝常に範囲内で、`cellIs` の境界検査も外せる（`s` は S×T に正規化済み）。
                //   数える値が同じなので結果は1ビットも変わらない。
                if (c.day1 > p.T) continue
                val row = s[i]
                var z = 0
                for (l in 0 until c.day1) if (row[l] == c.shiftIdx) z++
                while (j <= p.T - c.day1) {
                    if (j > 0) {
                        if (row[j - 1] == c.shiftIdx) z--
                        if (row[j + c.day1 - 1] == c.shiftIdx) z++
                    }
                    val viol = z < c.day2
                    if (viol) {
                        inc("c1")
                        if (!prevViol) mark(i, j, "c1")
                    }
                    prevViol = viol
                    j++
                }
            }
        }

        val counts = countMatrix(p, s)
        for (c in p.cons2) {
            for (i in 0 until p.S) {
                if (!p.canDo(i, c.shiftIdx)) continue
                if (p.quantitativeRangeEval) {
                    val amt = c2Amount(counts[i][c.shiftIdx], c.count)
                    if (amt > 0) { inc("c2", amt.toInt()); markCount(i, c.shiftIdx, "c2") }
                } else if (counts[i][c.shiftIdx] < c.count) {
                    inc("c2")
                    markCount(i, c.shiftIdx, "c2")
                }
            }
        }

        for (c in p.cons41) {
            for (j in 0 until p.T) {
                var z = 0
                for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && cellIs(i, j, c.shiftIdx)) z++
                if (p.quantitativeRangeEval) {
                    val amt = rangeDistance(z, c.l, c.u)
                    if (amt > 0) { inc("c41", amt.toInt()); markNeed(c.shiftIdx, j, "c41") }
                } else if (z < c.l || z > c.u) {
                    inc("c41")
                    markNeed(c.shiftIdx, j, "c41")
                }
            }
        }

        // [3.395.0/高速化] 旧: (規則×日) ごとに ArrayList を2個作っていた。違反が出るのは稀なので
        //   大半は「片側が空」で捨てられる＝割り当てが丸ごと無駄だった（実測で L267/268/273 が
        //   `check()` の 16.3%）。使い回しの IntArray ＋ 件数で同じ走査をする（結果は同じ）。
        val pairL = IntArray(p.S)
        val pairR = IntArray(p.S)
        for (c in p.cons42) {
            for (j in 0 until p.T) {
                var nL = 0
                var nR = 0
                for (i in 0 until p.S) {
                    if (p.sgrp[i] == c.g1 && cellIs(i, j, c.s1)) pairL[nL++] = i
                    if (p.sgrp[i] == c.g2 && cellIs(i, j, c.s2)) pairR[nR++] = i
                }
                if (nL == 0 || nR == 0) continue
                // [3.318.0] 自己ペア／同一集合の順序重複を数えない（`c42PairCount` と同じ意味論）。
                //   left と right が同じ集合になるのは g1==g2 かつ s1==s2 のときだけ。
                val sameSet = c.g1 == c.g2 && c.s1 == c.s2
                for (a in 0 until nL) for (b in 0 until nR) {
                    val i = pairL[a]
                    val i2 = pairR[b]
                    if (i == i2) continue
                    if (sameSet && i2 < i) continue
                    inc("c42")
                    mark(i, j, "c42")
                    mark(i2, j, "c42")
                }
            }
        }

        // [スキルグループ新設] スキル群の C41/C42 相当（ssk を参照・既存ユニットの sgrp とは独立）。
        for (c in p.cons41s) {
            for (j in 0 until p.T) {
                var z = 0
                for (i in 0 until p.S) if (p.ssk[i] == c.groupIdx && cellIs(i, j, c.shiftIdx)) z++
                if (p.quantitativeRangeEval) {
                    val amt = rangeDistance(z, c.l, c.u)
                    if (amt > 0) { inc("c41s", amt.toInt()); markNeed(c.shiftIdx, j, "c41s") }
                } else if (z < c.l || z > c.u) { inc("c41s"); markNeed(c.shiftIdx, j, "c41s") }
            }
        }
        for (c in p.cons42s) {
            for (j in 0 until p.T) {
                var nL = 0
                var nR = 0
                for (i in 0 until p.S) {
                    if (p.ssk[i] == c.g1 && cellIs(i, j, c.s1)) pairL[nL++] = i
                    if (p.ssk[i] == c.g2 && cellIs(i, j, c.s2)) pairR[nR++] = i
                }
                if (nL == 0 || nR == 0) continue
                val sameSet = c.g1 == c.g2 && c.s1 == c.s2   // [3.318.0] c42 と同じ（自己ペア／順序重複を除く）
                for (a in 0 until nL) for (b in 0 until nR) {
                    val i = pairL[a]
                    val i2 = pairR[b]
                    if (i == i2) continue
                    if (sameSet && i2 < i) continue
                    inc("c42s"); mark(i, j, "c42s"); mark(i2, j, "c42s")
                }
            }
        }

        checkC3Family(p, s, p.cons3, "c3", forbidden = false, { key, amt -> inc(key, amt) }, ::mark)
        checkC3Family(p, s, p.cons3n, "c3n", forbidden = true, { key, amt -> inc(key, amt) }, ::mark)
        checkC3Family(p, s, p.cons3m, "c3m", forbidden = false, { key, amt -> inc(key, amt) }, ::mark)
        checkC3Family(p, s, p.cons3mn, "c3mn", forbidden = true, { key, amt -> inc(key, amt) }, ::mark)

        // [3.542.0] 希望の前日に禁止(c3w, HARD)。前日側のセル（動かせる側）を違反箇所にする。
        if (p.c3wBan != null) for (i in 0 until p.S) for (j in 0 until p.T) {
            if (p.c3wBanned(i, j, s[i][j])) { inc("c3w"); mark(i, j, "c3w") }
        }

        for (i in 0 until p.S) for (j in 0 until p.T) {
            val w = p.wish[i][j]
            // [監査#11②] 実現可能な希望の未充足のみ HARD(pref) 計上・着色。担当不可の不可能希望は
            //   充足しようがなく「配布可(HARD=0)」を恒久不能にしていたため計数から対称除外する。
            //   可視性は impossibleWishCount と Sanity の不可能希望案内が担う。
            if (w in 0 until p.K && p.canDo(i, w) && s[i][j] != w) {
                inc("pref")
                mark(i, j, "pref")
            }
        }

        for (i in 0 until p.S) {
            for (k in 0 until p.K) {
                val lo = p.rangeLo[i][k]
                val hi = p.rangeHi[i][k]
                val n = counts[i][k]
                if (lo != Int.MIN_VALUE && lo != 0 && p.canDo(i, k) && n < lo) {
                    inc("low", lo - n)
                    markCount(i, k, "low")
                }
                if (hi != Int.MAX_VALUE && n > hi) {
                    inc("high", n - hi)
                    markCount(i, k, "high")
                }
                // [統一apt] 適切回数(群単位の双方向目標)。SOFT・重み1・L1偏差|n-t|。担当可シフトのみ(apt 構築時に canDo ガード済)。
                // セル着色は range(low/high, 重み90/25)を優先し、markCount の重み優先ガードにより低優先の
                // apt 色(不足=赤/超過=橙)は既存マークを上書きしない（手動 containsKey ガードは markCount 側の
                // 重み優先に統合済みのため撤去）。
                val t = p.apt[i][k]
                if (t >= 0 && n != t) {
                    inc("apt", kotlin.math.abs(n - t))
                    markCount(i, k, if (n > t) "aptHigh" else "aptLow")
                }
            }
        }

        // [統一fair/3.538.0] グループ内公平化: 群×担当ONシフトごと、`Problem.fairDevOfBucket`（達成率モード、
        // 全員に基準が無ければ従来の生回数round(平均)方式）からのL1偏差和。SOFT・重み2。最適化器(Evaluator/Delta)
        // と同一指標。内訳チップ(UI)には出さず weightedScore/total に算入。
        // [場所表示] 偏っているメンバー(x,k,dev)を収集（内訳パネル用・グリッドには出さない）。
        val fairLocs = ArrayList<List<Int>>()
        for (g in 0 until p.G) {
            val mem = p.groupMembers[g]
            if (mem.size < 2) continue
            for (k in p.bucket[g]) {
                val res = p.fairDevOfBucket(g, k) { x -> counts[x][k] }
                for ((x, dx) in res.perMember) fairLocs.add(listOf(x, k, dx))
                if (res.total > 0) inc("fair", res.total)
            }
        }

        // [統一weekly] 7日周期のシフト平準化: 職員ごと、**シフトごと**に、そのシフトが入る日の曜日別カウントの
        // round(そのシフトの回数/7) からの L1 偏差和。SOFT・重み1。最適化器(Evaluator/Delta)と同一指標。
        // [3.345.0] 休は通常のシフト種の一つとして扱う＝勤務/休の二値でなくシフト別に均す。旧定義（勤務日=非休の
        //   曜日カウント）は「毎週おなじ曜日に働く偏り」しか見ておらず、「夜勤が毎週水曜」「休みが毎週月曜」を
        //   区別できなかった。回数0のシフトは偏差0で無害（対象から外す必要はない）。
        // [場所表示] 偏っている(職員,シフト,dev)を収集（内訳パネル用・グリッドには出さない）。
        val weeklyLocs = ArrayList<List<Int>>()
        for (i in 0 until p.S) {
            val wd = Array(p.K) { IntArray(7) }
            for (j in 0 until p.T) { val k = s[i][j]; if (k in 0 until p.K) wd[k][(p.dow0 + j) % 7]++ }
            for (k in 0 until p.K) {
                val d = weeklyDevOfBucket(wd[k])
                if (d > 0) { inc("weekly", d); weeklyLocs.add(listOf(i, k, d)) }
            }
        }
        val distLocations = mapOf(
            "weekly" to weeklyLocs.sortedByDescending { it[2] },
            "fair" to fairLocs.sortedByDescending { it[2] },
        )

        val cov = coverage(p, s)
        // [監査#4b] 被覆は per-cell OR/AND（VBA本家=Web HF574 と三面統一）。件数=Σセル寄与、
        //   着色=そのセルのU/Oが正のときのみ（「P2で救済されるP1不足は光らない」を自然に内包）。
        //   U>0とO>0は同一セルで両立しないため旧else-if遮蔽は不要。共有ヘルパで最適化器と同式。
        for (j in 0 until p.T) {
            for (k in 0 until p.K) {
                val got = cov[j][k]
                val u = p.covUCell(k, j, got)
                if (u > 0) { inc("covU", u); markNeed(k, j, "covU") }
                val o = p.covOCell(k, j, got)
                if (o > 0) { inc("covO", o); markNeed(k, j, "covO") }
            }
        }

        for (i in 0 until p.S) for (j in 0 until p.T) {
            val k = s[i][j]
            if (k in 0 until p.K && !p.canDo(i, k)) {
                inc("groupViol")
                mark(i, j, "groupViol")
            }
        }

        // [3.395.0] 集計 IntArray を `MirrorKeys.all` の順で Map へ起こす（内容も順序も旧実装と同じ）。
        val breakdown = linkedMapOf<String, Int>()
        for ((bi, bk) in MirrorKeys.all.withIndex()) breakdown[bk] = bd[bi]

        var total = 0
        for (v in breakdown.values) total += v
        var hard = 0
        for (key0 in MirrorKeys.hard) hard += breakdown[key0] ?: 0
        val soft = total - hard
        val elapsedMs = ((System.nanoTime() - t0) / 1_000_000L)
        val hardParts = ArrayList<String>()
        for (key0 in MirrorKeys.hard) hardParts.add("${key0}=${breakdown[key0] ?: 0}")
        val hardStr = hardParts.joinToString(" ")
        val softParts = ArrayList<String>()
        for (key0 in MirrorKeys.soft) {
            val n = breakdown[key0] ?: 0
            if (n > 0) softParts.add("${key0}=${n}")
        }
        val softStr = softParts.joinToString(" ")
        val msg = if (total == 0) {
            "違反なし"
        } else {
            "合計=$total | HARD=$hard [$hardStr]" + if (soft > 0) " | SOFT=$soft [$softStr]" else ""
        }
        val level = if (total == 0) "I" else "W"
        // [Set化] クラス列を重み降順に整列（安定ソート＝同重みはマーク順維持 → 先頭は violations[key] と常に一致）。
        val cellFamilies = LinkedHashMap<String, List<String>>(cellFams.size)
        val violations = LinkedHashMap<String, String>(cellFams.size)
        for ((ck, cv) in cellFams) {
            val sorted = if (cv.size <= 1) cv else cv.sortedByDescending { classWeight[it] ?: 0.0 }
            cellFamilies[ck] = sorted
            violations[ck] = sorted[0]   // [3.395.0] 最重1クラス＝整列済み先頭（旧 mark() と同値）
        }
        val countFamilies = LinkedHashMap<String, List<String>>(countFams.size)
        val countViolations = LinkedHashMap<String, String>(countFams.size)
        for ((ck, cv) in countFams) {
            val sorted = if (cv.size <= 1) cv else cv.sortedByDescending { classWeight[it] ?: 0.0 }
            countFamilies[ck] = sorted
            countViolations[ck] = sorted[0]
        }
        val needFamilies = LinkedHashMap<String, List<String>>(needFams.size)
        val needViolations = LinkedHashMap<String, String>(needFams.size)
        for ((ck, cv) in needFams) {
            val sorted = if (cv.size <= 1) cv else cv.sortedByDescending { classWeight[it] ?: 0.0 }
            needFamilies[ck] = sorted
            needViolations[ck] = sorted[0]
        }
        return ViolationReport(
            violations = violations,
            needViolations = needViolations,
            countViolations = countViolations,
            cellFamilies = cellFamilies,
            countFamilies = countFamilies,
            needFamilies = needFamilies,
            breakdown = breakdown,
            total = total,
            hard = hard,
            soft = soft,
            weightedScore = weightedScore(breakdown),
            distLocations = distLocations,
            logs = listOf(MirrorLog(iter = 0, level = level, tag = "UnifiedCheck", message = "$msg (${elapsedMs}ms)")),
        )
    }

    private fun checkC3Family(
        p: Problem,
        schedule: Array<IntArray>,
        list: List<C3>,
        key: String,
        forbidden: Boolean,
        inc: (String, Int) -> Unit,
        mark: (Int, Int, String) -> Unit,
    ) {
        for (c in list) {
            val seq = c.seq
            val d = seq.size
            if (d == 0 || d > p.T) continue
            // [統一: 最適化器 Evaluator の HF507 と一致] 非forbidden の単一シフト連は run-deficit で評価する。
            // 長さ r(<d) の run ごとに (d-r) を加算し、その run のセルを強調。素の窓マッチとは違反の「方向」が
            // 異なる（窓=未完成窓数 / run=不足ぶん）ため、最適化器と表示・提案が食い違わないよう統一する。
            if (!forbidden && C3Run.isSingleShiftSeq(seq)) {
                val first = seq[0]
                for (i in 0 until p.S) {
                    val row = schedule[i]
                    val t = row.size
                    var runStart = -1
                    var r = 0
                    var j = 0
                    while (j <= t) {
                        val on = j < t && row[j] == first
                        if (on) {
                            if (r == 0) runStart = j
                            r++
                        } else if (r > 0) {
                            val deficit = d - r
                            if (deficit > 0) {
                                inc(key, deficit)
                                // [視認性] 不足run全塗り→run先頭1セルへアンカー（scoring不変: incは不足ぶん従来どおり）。
                                mark(i, runStart, key)
                            }
                            r = 0; runStart = -1
                        }
                        j++
                    }
                }
                continue
            }
            for (i in 0 until p.S) {
                var j = 0
                while (j <= p.T - d) {
                    if (schedule[i][j] == seq[0]) {
                        var z = 0
                        for (l in 1 until d) if (schedule[i][j + l] == seq[l]) z++
                        val fire = if (forbidden) z == d - 1 else z < d - 1
                        if (fire) {
                            inc(key, 1)
                            // [視認性] SOFT want窓は先頭1セルへアンカー。forbidden(c3n=HARD/c3mn)は禁止パターン
                            //   全体を表示（短く、致命は「どの並びが禁止か」を示す方が有益）。scoring(inc)は不変。
                            if (forbidden) { for (l in 0 until d) mark(i, j + l, key) } else mark(i, j, key)
                        }
                    }
                    j++
                }
            }
        }
    }


    private fun weightedScore(b: Map<String, Int>): Double {
        // [N2/⛏11] 重みは MirrorKeys.weights を単一の真実として参照。挿入順を保持しているため
        //   加算順は従来と同一＝Double 結果は不変。UI の重み表も同マップを描画する。
        var out = 0.0
        for ((key, weight) in MirrorKeys.weights) out += (b[key] ?: 0).toDouble() * weight
        return out
    }

}

fun List<List<Int>>.toIntArray2D(): Array<IntArray> {
    return Array(size) { i -> this[i].toIntArray() }
}
fun Array<IntArray>.copy2D(): Array<IntArray> {
    return Array(size) { i -> this[i].copyOf() }
}

fun Problem.canDo(staffI: Int, shiftK: Int): Boolean {
    if (staffI !in 0 until S || shiftK !in 0 until K) return false
    val g = sgrp[staffI]
    return bucket.getOrNull(g)?.contains(shiftK) == true
}

/** [監査#11①] セル(i,j)の希望を「不可侵（凍結）」として扱うか。
 *  実現可能な希望のみ凍結する。担当不可（bucket外）の不可能希望は凍結しない＝セルを
 *  被覆等の最適化へ復帰させ、入口fallback値のまま座礁するのを防ぐ。
 *  正当性: 不可能希望の pref 寄与は「w を割当て不能」ゆえ割当値に依存しない定数1。
 *  可動化しても pref は増減せず、他目的の最適化余地だけが広がる。
 *  pref の計数自体（不可能希望も違反として表示・カウント）は不変更（#11②は別裁定）。 */
fun Problem.wishLocked(i: Int, j: Int): Boolean {
    val w = wish[i][j]
    return w >= 0 && canDo(i, w)
}

/** [3.507.0] 最適化器が (i,k) を置いてよいか＝担当可かつ個人上限 0 でない（休は除外しない）。評価・表示は canDo。 */
fun Problem.mayPlace(staffI: Int, shiftK: Int): Boolean {
    if (staffI !in 0 until S || shiftK !in 0 until K) return false
    return placeableHas[staffI][shiftK]
}

/** 最適化器の候補生成用＝置けるシフト（`placeable`）。空＝置けるシフトなし。全呼び出し側は空配列をガード済み。
 *  [3.507.0] 旧: 群 bucket そのもの。担当可否そのもの（UI の選択肢・診断）は `canDoShiftsForStaff`。 */
fun Problem.allowedShiftsForStaff(staffI: Int): IntArray = placeable.getOrNull(staffI) ?: IntArray(0)

/** 担当できるシフト（群 bucket＝canDo と同値）。UI の選択肢と設定診断が使う。 */
fun Problem.canDoShiftsForStaff(staffI: Int): IntArray = bucket.getOrNull(sgrp.getOrNull(staffI) ?: -1) ?: IntArray(0)

fun normalizeSchedule(schedule: Array<IntArray>, p: Problem): Array<IntArray> = Array(p.S) { i ->
    IntArray(p.T) { j ->
        // [3.475.0/論理監査] 欠損セル（行が短い／行が無い）は -1（未割当センチネル）。旧: `?: 0` で
        //   先頭シフト index0 に写しており、休が index0 でないデータでは欠損が**勤務シフトとして**
        //   被覆・回数に計上された（Problem.kt の「normalizeSchedule は -1 にする」という前提と不一致）。
        //   範囲外の値と同じ扱い＝checker/countMatrix は -1 を無視し、initialAssignment は休で埋める。
        val k = schedule.getOrNull(i)?.getOrNull(j) ?: -1
        if (k in 0 until p.K) k else -1
    }
}

/** [統一weekly/3.526.0] 曜日バケット(size 7)の平準化偏差 = 厳密目標(合計/7)からの偏差を7倍スケールの
 *  整数のまま扱う(dev=Σ|7×回数−合計|÷7)。旧: round(平均)は合計が少ないと目標0に丸まり配置非依存の
 *  定数になる死角があった（HF77指示、経緯はdocs/history/3.4xx.md）。`weeklyFloorOfCount`は最良配置での
 *  値が旧式と一致するため据え置き。Evaluator / DeltaEvaluator / UnifiedViolationChecker の共通ソース。 */
/**
 * [3.355.0] 回数 c を7曜日へどう配っても消せない weekly 偏差の下限。
 *
 * `weeklyDevOfBucket` の目標は `round(c/7)`。全バケットを目標値にすると合計は `7*round(c/7)` なので、
 * 実際の合計 c との差だけは必ず偏差として残る（余りを1ずつ散らす／削るのが最小）。`|c − 7*round(c/7)| <= 3`。
 * 曜日ごとの日数上限（31日なら曜日により4回か5回）は考慮しないので、**真の下限以下**＝過大に見積もらない。
 */
fun weeklyFloorOfCount(c: Int): Int {
    if (c <= 0) return 0
    val tgt = Math.round(c.toDouble() / 7.0).toInt()
    return kotlin.math.abs(c - 7 * tgt)
}

fun weeklyDevOfBucket(wd: IntArray): Int {
    var sum = 0
    for (w in wd) sum += w
    var d = 0
    for (w in wd) d += kotlin.math.abs(7 * w - sum)
    return d / 7
}

/** [3.538.0] `fairDevOfBucket` の1件分の結果。[perMember] は偏差>0の(職員index, 偏差件数)のみ（場所表示用）。 */
class FairDevResult(val total: Int, val perMember: List<Pair<Int, Int>>)

/**
 * [3.541.0/ユーザー指示・案E] 公平化（達成率モード v2）: 各職員を「自分の基準に対する達成率」へ写してから群内で比べる。
 * 基準＝範囲 lo/hi（両方有限、回数は帯へクランプ＝帯の外は high/low の担当）優先、無ければ実効 apt 目標（中心 t・幅 t）。
 * 母集団＝mayPlace または回数>0。基準達成率＝幅を重みにした中央値（Σ幅×|達成率−基準| の最小点＝帯の端の 1 人に引きずられない）。
 * 偏差＝round(|達成率−基準|×自分の幅)。基準が 1 人でも無ければ同じ母集団で生回数 round(平均) の L1 へ。式の根拠と実データの
 * 数値は docs/business-logic.md / docs/history 3.541.0。**C++ `magi_native.cpp` の `fairDevOfBucket` と同じコミットで揃える。**
 */
fun Problem.fairDevOfBucket(g: Int, k: Int, count: (Int) -> Int): FairDevResult {
    val all = groupMembers[g]
    val mem = IntArray(all.size); var n = 0
    for (x in all) if (mayPlace(x, k) || count(x) > 0) mem[n++] = x
    if (n < 2) return FairDevResult(0, emptyList())
    val ach = DoubleArray(n); val scale = DoubleArray(n)
    for (idx in 0 until n) {
        val x = mem[idx]
        val lo = rangeLo[x][k]; val hi = rangeHi[x][k]; val c = count(x)
        if (lo != Int.MIN_VALUE && hi != Int.MAX_VALUE) {
            // lo>hi（設定ミス）は幅 0 扱い。coerceIn は lo>hi で例外を投げるので手でクランプする（C++ と同じ式）。
            val e = if (c < lo) lo else if (c > hi) hi else c
            val s = if (hi > lo) (hi - lo) / 2.0 else 0.0
            scale[idx] = s; ach[idx] = if (s > 0.0) (e - (lo + hi) / 2.0) / s else 0.0
        } else if (apt[x][k] >= 0) {
            val t = apt[x][k]
            scale[idx] = t.toDouble(); ach[idx] = if (t > 0) (c - t).toDouble() / t else 0.0
        } else return legacyFairDevOfBucket(mem, n, count)
    }
    var w = 0.0
    for (idx in 0 until n) w += scale[idx]
    if (w <= 0.0) return FairDevResult(0, emptyList())
    val order = (0 until n).sortedBy { ach[it] }
    var acc = 0.0; var tgt = ach[order[n - 1]]
    for (idx in order) { acc += scale[idx]; if (acc * 2 >= w) { tgt = ach[idx]; break } }
    var total = 0
    val perMember = ArrayList<Pair<Int, Int>>()
    for (idx in 0 until n) {
        val dx = Math.round(kotlin.math.abs(ach[idx] - tgt) * scale[idx]).toInt()
        total += dx
        if (dx > 0) perMember.add(mem[idx] to dx)
    }
    return FairDevResult(total, perMember)
}

/** `fairDevOfBucket` のフォールバック先＝生回数の round(平均) からの L1 偏差（母集団 mem[0 until n]）。 */
private fun legacyFairDevOfBucket(mem: IntArray, n: Int, count: (Int) -> Int): FairDevResult {
    var sum = 0
    for (idx in 0 until n) sum += count(mem[idx])
    val tgt = Math.round(sum.toDouble() / n).toInt()
    var total = 0
    val perMember = ArrayList<Pair<Int, Int>>()
    for (idx in 0 until n) {
        val dx = kotlin.math.abs(count(mem[idx]) - tgt)
        total += dx
        if (dx > 0) perMember.add(mem[idx] to dx)
    }
    return FairDevResult(total, perMember)
}

fun countMatrix(p: Problem, schedule: Array<IntArray>): Array<IntArray> {
    val out = Array(p.S) { IntArray(p.K) }
    for (i in 0 until p.S) for (j in 0 until p.T) {
        val k = schedule[i][j]
        if (k in 0 until p.K) out[i][k]++
    }
    return out
}

/**
 * 同一 state 参照に対する Problem の単一エントリ・メモ化（性能）。
 * Problem の構築は全制約解決(cons3族/c41/c42/bucket/need…)で高コスト。最適化中は state 参照が
 * 一定なので、ここでキャッシュすると毎反復の再構築（1反復あたり破壊/修復＋hf67＋check で約3回）を排除できる。
 * Problem は実質イミュータブルで、これらの用途は schedule 非依存のため、5ワーカー間の共有読取も安全。
 * 参照比較(===)のみ。別 state が来れば作り直すので陳腐化しない。
 *
 * スレッド安全性: key と value を 1 つの不変 Entry にまとめ、@Volatile 参照を 1 回だけ読む。
 * 旧実装は key/value を別々の Volatile に持ち非アトミックに書いていたため、別スレッドが
 * 「新しい key だが古い value」を読み、要求 state と次元(S/T/K)の異なる Problem を返し得た
 * （fg の refreshCheck と bg 最適化が同一プロセスで重なると到達 → 誤スコア/AIOOBE）。
 */
private object ProblemCache {
    // [backlog #12(a)・実験段階] quantitativeRangeEval は Problem の内容を変えるので、
    //   キーへ含めないと同一state・別フラグの2要求が誤って同じインスタンスを共有してしまう。
    private class Entry(val key: MagiState, val flag: Boolean, val value: Problem)
    @Volatile private var entry: Entry? = null
    fun get(state: MagiState, quantitativeRangeEval: Boolean): Problem {
        val e = entry
        if (e != null && e.key === state && e.flag == quantitativeRangeEval) return e.value
        val np = Problem(state, quantitativeRangeEval)
        entry = Entry(state, quantitativeRangeEval, np)   // 単一参照の公開はアトミック。race時の重複生成は等価で無害。
        return np
    }
}
fun cachedProblem(state: MagiState, quantitativeRangeEval: Boolean = false): Problem = ProblemCache.get(state, quantitativeRangeEval)

fun coverage(p: Problem, schedule: Array<IntArray>): Array<IntArray> {
    val out = Array(p.T) { IntArray(p.K) }
    for (i in 0 until p.S) for (j in 0 until p.T) {
        val k = schedule[i][j]
        if (k in 0 until p.K) out[j][k]++
    }
    return out
}

// [レビュー#4 3.213.0] lockedMatrix(canDo 無視の全希望ロック)は撤去。唯一の呼出元 LightMirrorOptimizer が
//   wishLocked（実現可能希望のみ凍結）へ統一されたため呼出0のデッドコード＝削除。

fun restShiftIndex(state: MagiState): Int = state.shifts.indexOfFirst { it.kigou == "休" }.takeIf { it >= 0 } ?: 0

/**
 * 空きマス（新職員の行・伸ばした日・消したシフトのマス・範囲外や欠損の値）を埋めるシフト index。
 *
 * [3.419.0] 埋める側は「休」を既定にしてきたが、**その職員がそのシフトを担当できるかを見ていなかった**。
 * 担当可否から休を外した群（UI の担当可否チップで実際にできる操作）では、埋めたマスが丸ごと
 * groupViol（HARD・重み10000）になる＝入力が不正なだけなのに、こちらが**存在しない違反を作っていた**。
 *
 * 規則はこの1箇所だけに置く（3.418.0 で `Ws1Ops` の3経路を直したとき同じ判断を写しかけた＝写すと必ず
 * 取り残される）。休を担当できるならそのまま休（需要が無く「まだ決めていない」を表すのに最も無難で、
 * 実データ3件は全群が休を担当できるため**挙動は変わらない**）。できなければ担当できる先頭のシフト。
 * 担当できるシフトが1つも無ければ休へ倒す＝**ここで例外を投げると、その不整合を直しに来た編集操作
 * そのものがクラッシュする**（検査2k/2l が別途その状態を指摘する）。
 */
fun fillShiftIndex(allowed: IntArray, rest: Int): Int =
    if (allowed.contains(rest)) rest else allowed.firstOrNull() ?: rest

fun formatDay(startDate: String, offset: Int): String {
    return try {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val d = fmt.parse(startDate) ?: return "${offset + 1}日"
        val cal = java.util.Calendar.getInstance(Locale.JAPAN)
        cal.time = d
        cal.add(java.util.Calendar.DATE, offset)
        val wd = "日月火水木金土"[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}($wd)"
    } catch (_: Exception) {
        "${offset + 1}日"
    }
}

fun MagiState.withSchedule(schedule: Array<IntArray>): MagiState {
    val rows = ArrayList<List<Int>>(schedule.size)
    for (row in schedule) rows.add(row.toList())
    return copy(schedule = rows)
}
