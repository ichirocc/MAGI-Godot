package com.magi.app.v6

import kotlin.math.max
import kotlin.math.min

/**
 * 仮説数・並列度の計画/クランプ/診断ヘルパー。[V6NativeOptimizer] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * 全メンバが**共有可変状態を一切参照しない純粋な計算/判定関数**（対照的に [V6NativeOptimizer] 本体は
 * @Volatile フィールド・Atomic系・RunSlotのコルーチンコンテキスト連携で並行実行状態を密結合する
 * 「統括状態機械」の性格が強く、機械的な抽出は危険＝本ファイルはその中の安全な塊だけを切り出す）。
 *
 * - [rsiHf63EffortIters]/[MAX_HYPOTHESES]/[hypothesisCount]/[perHypothesisWorkers]/
 *   [clampWorkersToCores]/[portfolioWorkerCount]/[hypothesisChainPlan]/[hypothesisSpawnPlan]：
 *   仮説数・ワーカー内部並列度の計画/クランプ計算。
 * - [observedOuterParallelism]/[isEarlyWorkerExit]/[epochOverrunLog]：適応ポートフォリオの
 *   並列度・離脱理由・エポック超過を実機ログへ出すための診断ヘルパー。
 *
 * 呼び出し側は全て`V6NativeOptimizer.<name>`の完全修飾で参照していたため、抽出時に
 * `HypothesisPlanning.<name>`へ一括置換した（本体内部からの無修飾自己呼出は元々無い）。
 */
internal object HypothesisPlanning {
    internal fun rsiHf63EffortIters(rounds: Int, reserveRounds: Int = 2): Int {
        val attemptsTarget = max(2, (max(0, rounds - reserveRounds) + 1) / 2)
        return (Hf63Infeasibility.INFEAS_STALL_ITERS + attemptsTarget - 1) / attemptsTarget
    }

    /** [仮説数上限撤廃・ユーザー指示] かつて仕様§2.2の仮説数固定上限(5)だった定数。optimize() の
     *  仮説数計算（[hypothesisCount] 参照）はこの値を上限として使わなくなった＝ワーカー設定まで仮説を
     *  増やす（下限2）。現在は ①ExtraRefine(微小予算5〜25sの追加精製)専用の意図的な小さいキャップ
     *  （仮説内多チェーンの固定費が小予算を侵食するのを避ける、V6FinalPort参照）②hypothesisChainPlan の
     *  デフォルト引数、の2用途にのみ残置（名前は歴史的経緯・値の意味は「旧上限」から「小予算時の安全キャップ」
     *  へ転用）。 */

    const val MAX_HYPOTHESES = 5

    /** [仮説数上限撤廃・ユーザー指示「仮説数は最低2最大設定値」] 仮説数(w)の実効値。旧 optimize() は
     *  `options.workers.coerceIn(1, MAX_HYPOTHESES)` で workers>5 分を仮説内並列度へ配分していたが、
     *  ユーザー指示によりこの固定上限を撤廃し**多様性(仮説数)を優先**する。下限2（workers=1でも最低2仮説の
     *  多様探索を保証・diversity目的で意図的にworkersを1オーバーサブスクライブする）・上限は無し
     *  （options.workers自体が上限）。optimize() 本体と V6FinalPort の診断表示(effHypotheses)が両方
     *  本関数から導出＝独立再計算によるUI/ログの乖離を防ぐ（3.212.0 と同じ設計原則）。 */

    internal fun hypothesisCount(workers: Int): Int = max(2, workers)

    /** [余剰ワーカー活用] 仮説数(hypotheses)に対し、設定workersのうち何本を各仮説の
     *  内部並列度（SAチェーン数・ALNS多チェーン）へ均等配分するか。workers<=hypothesesなら1(旧来どおり
     *  単一チェーン)。余りは切り捨て（例: workers=8,hypotheses=5 → 1本/仮説・workers=16,hypotheses=5 → 3本/仮説）。
     *  ※均等床の計算のみ。実際の配分は hypothesisChainPlan（余り配分＋コア数クランプ）を使う。
     *  [仮説数上限撤廃後] 本体の w=hypothesisCount(workers) は workers>=2 で hypotheses==workers となるため
     *  実運用では常に1（内部並列は事実上不使用）。本関数は ExtraRefine 等 hypotheses<workers な呼出のために残置。 */

    internal fun perHypothesisWorkers(workers: Int, hypotheses: Int): Int =
        max(1, workers / max(1, hypotheses))

    /** [敵対的レビュー修正・#6] V5(高速計算)は仮説の概念を使わず options.workers をそのまま
     *  SaParams.workers(=SAチェーン数)へ渡していたため、hypothesisChainPlan のコア数クランプの
     *  恩恵を受けず、コア数を超えるCPU-boundコルーチンを壁時計締切下で希釈しうる（例: 8コア機に
     *  workers=16設定でV5選択→16並列SAチェーンが8コアを奪い合う）。V5専用に総並列度をコア数以内へ
     *  クランプする（hypothesisChainPlan と異なりV5は「最低1仮説」のような競合する下限が無いため、
     *  単純にコア数でクランプするだけで良い）。 */

    internal fun clampWorkersToCores(workers: Int, cores: Int = Runtime.getRuntime().availableProcessors()): Int =
        max(1, workers).coerceAtMost(max(1, cores))

    /** [3.410.0/E-02] 適応ポートフォリオの実spawn数。**PORTFOLIO だけがコア数クランプを持っていなかった**。
     *  V5 は [clampWorkersToCores]、runMultiWorker 経路は [hypothesisSpawnPlan] が実コア数まで落とすのに、
     *  `runAdaptivePortfolio` は `w = hypothesisCount(workers) = max(2, workers)` をそのまま
     *  `Array(workers){ async(...) }` へ渡していた。設定タブの並列ワーカーは **16 まで上げられる**ので、
     *  8コア機で16ワーカー＝各エポックが壁時計の量子内で半分しか進まない希釈が**設定画面から作れた**。
     *  （Dispatchers.Default のスレッド数はコア数で頭打ちなのでスレッド爆発は起きない。害は希釈のみ。）
     *
     *  ただし 3.224.0 の「workers まで仮説を増やす」という明示決定と、3.224.0/3.371.0 の「コア数を超えて
     *  希釈しない」という決定はここで衝突する。PORTFOLIO のロールは常に workers=1（3.409.21 で
     *  ロール内並列SAを削除済）＝**余剰の行き先が無い**ので、runMultiWorker のような「チェーン深さへ
     *  回す」再配分ができない。よって希釈側を採る。**既定設定では no-op**（既定 workers=コア数）で、
     *  効くのは利用者が手でコア数超へ上げたときだけ。多様性の下限2（3.224.0）は割らない。 */

    internal fun portfolioWorkerCount(w: Int, cores: Int = Runtime.getRuntime().availableProcessors()): Int =
        max(1, minOf(max(1, w), max(2, cores)))

    /** [敵対的レビュー3.212.0/単一ソース] 仮説ごとのチェーン本数プラン。レビューで確定した2欠陥を修正:
     *  ①旧 perW=床のみ配分は workers 6〜9（既定上限8＝動機の実機ログ当該ケース）で余り1〜4本を黙って
     *    廃棄しながらUI/docsが「無駄にならない」と虚偽主張（HF77: コメント≠実装）→ 余りを先頭仮説から
     *    +1ずつ配分し、主張どおり「5を超えた分は実際に使われる」ようにする。
     *  ②コア数クランプ無しで workers=16/8コア端末が15 CPU-boundコルーチンを壁時計締切下で希釈し
     *    「浅い3本のkeep-best < 深い1本」の品質逆行リスク（2.55/2.56のA/B実測原則にも反する）→
     *    配分総量を min(workers, cores) にクランプ（コア数以内なら挙動は配分の名のとおり）。
     *  UI注記・診断ログ・エンジン本体が全て本関数から導出＝表示と実挙動の乖離を構造的に防ぐ。
     *  返り値: 長さ hypotheses の各仮説チェーン本数（各要素>=1・合計=max(hypotheses, min(workers, cores))）。 */

    internal fun hypothesisChainPlan(
        workers: Int,
        hypotheses: Int = MAX_HYPOTHESES,
        cores: Int = Runtime.getRuntime().availableProcessors(),
    ): IntArray {
        val h = max(1, hypotheses)
        val distributable = max(h, kotlin.math.min(max(1, workers), max(1, cores)))
        val basePer = distributable / h
        val remainder = distributable % h
        return IntArray(h) { i -> basePer + if (i < remainder) 1 else 0 }
    }

    /** [3.371.0/並列SA本格再有効化] `runMultiWorker` が実際に spawn する仮説コルーチン数と、各仮説の
     *  内部チェーン本数プランを、診断ログ側とも共有する単一ソース（3.212.0/3.225.0と同じ「表示は実挙動
     *  から導出」原則）。
     *
     *  背景: `w=hypothesisCount(workers)` は workers>=2 のとき常に `w==workers` になる（3.224.0 の
     *  多様性優先化）。これを [hypothesisChainPlan] の hypotheses へそのまま渡すと
     *  `distributable=max(w, min(workers,cores))=w` に構造的に一致し、内部チェーン本数（並列SA/ALNS）が
     *  **コア数に関わらず恒久的に1本**に収束していた（3.211.0/3.212.0で作った「余剰ワーカーを内部並列へ
     *  配分」する仕組みが 3.224.0 以降、実質死んでいた）。
     *
     *  workers<=cores（大半の端末・既定の並列ワーカー設定）ではこの関数は無変更の挙動を返す
     *  （hSpawn==w のため下記 if に入らず、旧来と完全に同一の spawn 数・plan）。
     *  workers>cores（端末のコア数を超える設定）のときだけ、spawn する仮説コルーチン数を実コア数まで
     *  落とし（cores<w の希釈を避ける、V5用 [clampWorkersToCores] と同じ発想）、その分の予算(workers)を
     *  各仮説の内部チェーン数へ回す（[hypothesisChainPlan] の cores 引数へ options.workers を渡し、
     *  既定のコア数クランプを迂回して「予算workers・仮説hSpawn本」を素直に配る）。
     *  workers 予算の合計は不変（コア数を超えてコルーチンを増やさない＝オーバーサブスクライブの新規発生
     *  なし。3.224.0 で固定された `hypothesisChainPlan(5,5,8)==[1,1,1,1,1]` 等の既存契約は無変更）。
     *  返り値: (spawn する仮説コルーチン数, 各仮説のチェーン本数プラン=長さそのhSpawn)。 */

    internal fun hypothesisSpawnPlan(
        workers: Int,
        w: Int,
        cores: Int = Runtime.getRuntime().availableProcessors(),
    ): Pair<Int, IntArray> {
        // [3.372.0/レビュー修正] 旧実装は `max(2, min(w, cores))` で、w<2 のとき hSpawn(=2) が w(=1) を
        //   上回り、plan を w で組んでいたため `plan.size(1) < hSpawn(2)` ＝ runMultiWorker が index する
        //   不変条件 `hSpawn == plan.size` を破っていた（plan[1] で AIOOBE）。本番の3呼出は全て
        //   w=hypothesisCount(workers)=max(2,workers)>=2 のため到達しないが、本関数は internal で
        //   テスト/将来の呼出から届く＝潜在バグ。①hSpawn が w を超えないようにし ②plan を必ず hSpawn で
        //   組む（hypothesisChainPlan は IntArray(max(1,hypotheses)) を返す＝不変条件が構造的に成立）。
        //   多様性の下限2は「w>=2 のときだけ意味を持つ」ので min(w, ...) の内側に置く。
        val hSpawn = max(1, min(w, max(2, cores)))
        // [テスト容易性] else 分岐も明示的に cores を渡す（渡さないと hypothesisChainPlan の既定引数＝
        //   実デバイスのコア数へ暗黙フォールバックし、この関数のテストが実行環境依存になる）。
        //   hSpawn==w のときは hypothesisChainPlan 自体が h==hypotheses に一致し distributable も
        //   常に h と一致するため（本関数のKDoc参照）、cores を明示的に渡しても渡さなくても結果は同一。
        val plan = if (hSpawn < w) hypothesisChainPlan(workers, hSpawn, cores = workers)
            else hypothesisChainPlan(workers, hSpawn, cores = cores)
        return hSpawn to plan
    }


    /**
     * [3.409.4] PORTFOLIO の**外側ワーカー**が壁時計上でどれだけ並行していたかの観測値
     * （役割別worker秒の合計 ÷ ポートフォリオ本体の経過）。**CPU 使用率でも、仮説内チェーンを
     * 含む使用コア数でもない。**
     *
     * 目的は「8仮説の設定なのに実質1本」で走る片肺化を、入力・端末を跨いだログで一目で見ること。
     * 実機ログで実際に判別できることを確認済み: 3.402.0 の 2,189s/275.007s = **7.96**（健全）に対し、
     * 3.370.0 の 74s/79.593s = **0.93**＝同じログの離脱行が
     * `ワーカー離脱=8/8本が締切前(勝者確定7本@0s…)` で、**3.376.0 で撤廃した「HARD=0 到達時に
     * 残りを即キャンセルする」機構**そのものだった。つまりこの指標は当時なら即座に検出できた。
     * そのバグは既に直っているので、前向きの用途は**回帰検出**である。
     */
    internal fun observedOuterParallelism(totalWorkerMs: Long, wallElapsedMs: Long): Double =
        if (totalWorkerMs <= 0L || wallElapsedMs <= 0L) 0.0
        else totalWorkerMs.toDouble() / wallElapsedMs.toDouble()


    /**
     * [3.409.16] ワーカーの離脱理由が「締切前の早期離脱」か。
     * 「締切」=自分の while ループの deadline 到達／「探索締切」=同じ締切（またはキャンセル）が
     * stopIsFinal() の stop シグナル経由で届いた正常終了＝どちらも早期離脱ではない。
     * 旧判定（!= "締切" のみ）は、全ワーカーが予算を使い切った正常な実行を
     * 「ワーカー離脱=8/8本が締切前(探索締切8本@275s)」と自己矛盾で報告していた（3.409.14 実機ログで発覚）。
     * 早期離脱として数えるのは「停滞シグナル」（confirmStop の確認窓を通った本物の停滞）と「例外」。
     */
    internal fun isEarlyWorkerExit(exitReason: String): Boolean =
        exitReason != "締切" && exitReason != "探索締切"


    /**
     * [3.409.17/実機ログ 3.409.14] エポック超過（ロールが roleDeadline を5秒超えて走った記録）の
     * 集約行。空なら null（＝通常の実行ではログを増やさない）。実機で予算300sの実行が474〜959sまで
     * 超過したのに、どの役割が塞いだかを後から特定できなかった穴を埋める（証拠は W4 epoch3 の
     * グローバル最良更新が経過474sに出たこと＝ロールが stopRole を数百秒無視した）。
     * 検出側（nowMs() - roleDeadline > 5s）は epoch ループ内のインライン算術で、遅いロールを
     * 注入しないと踏めないため単体テストは整形のみ＝検出は次回の実機ログで確認する。
     */
    internal fun epochOverrunLog(notes: List<String>): MirrorLog? {
        if (notes.isEmpty()) return null
        return MirrorLog(
            level = "W", tag = "エポック超過",
            message = "ロールが停止確認(stopRole)を大きく超過: " + notes.take(8).joinToString(",") +
                (if (notes.size > 8) " ほか${notes.size - 8}件" else "") +
                "（量子q秒のロールが実N秒走った＝内部で締切を見ない経路がある。役割名から特定する）",
        )
    }


}
