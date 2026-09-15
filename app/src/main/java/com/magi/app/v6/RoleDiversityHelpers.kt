package com.magi.app.v6

/**
 * 並列仮説の役割多様化ヘルパー + Great Deluge水位 + focus足跡圧縮。[V6NativeOptimizer] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * 全メンバが**共有可変状態を一切参照しない純粋な計算/判定関数**（[V6NativeOptimizer] 本体は
 * @Volatile フィールド・Atomic系・RunSlotのコルーチンコンテキスト連携で並行実行状態を密結合する
 * 「統括状態機械」の性格が強く、機械的な抽出は危険＝本ファイルはその中の安全な塊だけを切り出す）。
 *
 * - [roleExploreFor]/[roleAcceptFor]/[roleOpSelectFor]：並列仮説ごとの探索倍率/受理方式/
 *   演算子選択方式の多様化（HF290役割分担移植）。
 * - [scheduleDistance]：2盤面の変更セル数（[AdaptiveEliteArchive.scheduleDistance] への委譲）。
 * - [greatDelugeLevel]：時間予定型 Great Deluge の水位計算。
 * - [compressFocusTrail]：RSI focus 遷移ログの連続圧縮表示。
 *
 * 同じ理由で抽出しなかったもの（[V6NativeOptimizer] に残置）: `hypothesisStartFor`/
 * `forceDiverseKick`（探索本体と密結合＝Round6 で [DestroyRepairOperators] へ抽出済みの
 * オペレータ群を跨いで束ねる薄い入口）、`RunSlot`一式・`lastAlternatives`等
 * （実行間状態分離の共有可変状態そのもの）。
 *
 * `AcceptMode`/`OpSelectMode` は [V6NativeOptimizer] と同一ファイル冒頭のトップレベルenum
 * （同一パッケージのため修飾不要）。呼び出し側は全て`V6NativeOptimizer.<name>`の完全修飾で
 * 参照していたため、抽出時に`RoleDiversityHelpers.<name>`へ一括置換した
 * （本体内部からの無修飾自己呼出は個別に完全修飾へ書き換え済み）。
 */
internal object RoleDiversityHelpers {
    /** [HF290 役割分担移植] 並列仮説の探索/精製プロファイル（温度・摂動の倍率）。
     *  W0=1.0(ベースライン=退化防止)、以降は探索(>1)/精製(<1)を交互に割当てて portfolio を多様化。
     *  [仮説数上限撤廃(3.225.0)後のドッグフーディングで発見・3.228.0で修正] この配列は5要素固定のため
     *  i>=5（3.225.0でworkers設定まで仮説数が増えたことで実際に生成されうる）は全て else 節の
     *  既定値=roleExploreFor(0)と同値に縮退し、役割分担が完全に無効化されていた（仮説5,6,7…は
     *  種(seed)以外ベースラインと区別できないクローン＝「多様性を優先する」という3.225.0自身の
     *  狙いを裏切っていた）。i<5 の既存値は一切変更せず(既存テスト・チューニング結果を保持)、
     *  i>=5 だけ黄金比の低食い違い列(golden-ratio low-discrepancy sequence)で [0.35, 2.4] へ
     *  決定的かつ非周期的に写像する（配列を単に延長・循環させるとi=5%5=0で結局ベースラインに
     *  戻るクローン問題を繰り返すため、周期を持たない生成式を採用）。 */
    private val ROLE_EXPLORE = doubleArrayOf(1.0, 2.0, 0.5, 1.6, 0.6)

    internal fun roleExploreFor(i: Int): Double {
        if (i in ROLE_EXPLORE.indices) return ROLE_EXPLORE[i]
        val frac = (i * 0.6180339887498949) % 1.0
        return 0.35 + frac * (2.4 - 0.35)
    }


    /** [論文活用] 並列仮説で受理戦略を多様化（W0,W1=SA基準 / W2,W4=Great Deluge / W3=Lam適応冷却）。
     *  W0 は常に SA でベースライン保持＝退化防止。
     *  [3.228.0] i<=4 の既存分岐は不変。i>=5 は else節で一律SAに縮退していた（roleExploreFor と同じ
     *  クローン問題）ため、GD/LAM/SAを i%3 で巡回させ実際に多様化する。 */
    internal fun roleAcceptFor(i: Int): AcceptMode = when (i) {
        2, 4 -> AcceptMode.GREAT_DELUGE
        3 -> AcceptMode.LAM_ADAPTIVE
        0, 1 -> AcceptMode.SA
        else -> when (i % 3) {
            0 -> AcceptMode.GREAT_DELUGE
            1 -> AcceptMode.LAM_ADAPTIVE
            else -> AcceptMode.SA
        }
    }


    /** [論文活用] 並列仮説で演算子選択を多様化（W1=Thompson sampling / 他=roulette）。
     *  W0 は常に roulette でベースライン保持＝退化防止。
     *  [3.228.0] i<=4 の既存分岐は不変。i>=5 は一律rouletteに縮退していたため、偶奇でTHOMPSON/ROULETTEを
     *  交互に割当てて多様化する。 */
    internal fun roleOpSelectFor(i: Int): OpSelectMode = when {
        i == 1 -> OpSelectMode.THOMPSON
        i in 0..4 -> OpSelectMode.ROULETTE
        i % 2 == 1 -> OpSelectMode.THOMPSON
        else -> OpSelectMode.ROULETTE
    }


    /** [3.266.0/hypothesis basin diversity] 変更セル数。診断ログとdiversity判定の両方に使う。
     *  実体は AdaptiveEliteArchive.scheduleDistance（唯一の実装）への委譲。両クラスから同じ距離定義を
     *  共有し、アルゴリズムの二重実装（DRY違反）を避ける。 */
    internal fun scheduleDistance(a: Array<IntArray>, b: Array<IntArray>): Int =
        AdaptiveEliteArchive.scheduleDistance(a, b)


    /**
     * 時間予定型 Great Deluge の水位（Burke, Bykov, Newall & Petrovic 2004）。
     * frac=1(序盤)で initial、frac=0(終盤)で best へ線形降下。候補スコア ≤ 水位 なら受理。
     */
    internal fun greatDelugeLevel(initial: Double, best: Double, frac: Double): Double =
        best + (initial - best) * frac.coerceIn(0.0, 1.0)

    /** [3.288.0/ログ強化=回数軸] focus 足跡の連続圧縮（"c3n,c3n,c1" → "c3n×2→c1"）。マーカー([..])はそのまま挟む。 */
    internal fun compressFocusTrail(trail: List<String>): String {
        val out = StringBuilder(); var i = 0
        while (i < trail.size) {
            val t = trail[i]
            if (t.startsWith("[")) { if (out.isNotEmpty()) out.append("→"); out.append(t); i++; continue }
            var j = i + 1
            while (j < trail.size && trail[j] == t) j++
            if (out.isNotEmpty()) out.append("→")
            out.append(t); if (j - i > 1) out.append("×${j - i}")
            i = j
        }
        return out.toString()
    }

}
