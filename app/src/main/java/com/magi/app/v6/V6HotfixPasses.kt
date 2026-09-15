package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Native replacements for the Web-only post-optimization hotfix modules.
 *
 * The Web V6 calls HF80 -> HF67 -> HF66 -> HF70 after each optimizer run from
 * inside App.handleOptimize().  Android does not have window.HFxx modules, so the
 * passes live here as pure Kotlin and can be called from ViewModel/tests.
 */
data class HF80Result(
    val newSchedule: Array<IntArray>,
    val beforeHard: Int,
    val afterHard: Int,
    val beforeScore: Double,
    val afterScore: Double,
    val cycles: Int,
    val applied: Boolean,
    val reason: String,
    val logs: List<MirrorLog>,
)

data class HF67Result(
    val newSchedule: Array<IntArray>,
    val beforeTotal: Int,
    val afterTotal: Int,
    val swapsApplied: Int,
    val shortageSwaps: Int,
    val capacitySwaps: Int,
    val swapsRollback: Int,
    val logs: List<MirrorLog>,
)

data class HF66Result(
    val newSchedule: Array<IntArray>,
    val beforeTotal: Int,
    val afterTotal: Int,
    val movesApplied: Int,
    val shortageMoves: Int,
    val capacityMoves: Int,
    val movesRollback: Int,
    val logs: List<MirrorLog>,
)

data class HF70Result(
    val anomalies: Int,
    val message: String,
    val advice: String,
    val logs: List<MirrorLog>,
)

data class V6PostOptimizationResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    val hf80: HF80Result,
    val hf67: HF67Result,
    val hf66: HF66Result,
    val hf70: HF70Result,
    val logs: List<MirrorLog>,
    /** [3.322.0] 窓の要件(c1)が最後まで残った理由の構造化診断（残存なしなら null）。 */
    val c1Plateau: C1PlateauDiagnosis? = null,
    /**
     * [3.323.0] 厳密ピン(lo==hi)だけが却下した候補の**計測できた試行数**。
     * これらは `isBetter` が採用を認めた手で、ピンのガードだけが止めている。
     *
     * **正確な読み方（3.324.0/外部レビューで是正）**:
     *  - 「手の数」ではなく「試行の回数」。巡回研磨は最大4巡するので、同じ手が複数の巡で
     *    数えられうる（重複排除していない）。
     *  - **全パス横断ではない**。[3.349.0 で 9→18パスへ訂正 → 3.350.0 で最終LNS 2本を追加 →
     *    **3.409.9 で広域ビームの合流漏れを修正＝21パス**]
     *    `V6HotfixPasses` の19パスに加え、`C1JointLnsPolish` と `PersonalBalanceJointLnsPolish` を計測する。
     *    （広域ビームは `PinBlockAttribution` を作って返すのに `runPostOptimization` 側の merge だけが
     *    無く、**この1つだけが終端集計から抜けていた**。他20サイトは元から merge 済み。）
     *    後者2本は却下するだけで一切数えておらず、実データ real_state で **1,898件**（V6HotfixPasses 側の
     *    計測値の30倍以上）が UI から丸ごと抜けていた。配線後の実測は総数 181→**1,617**で、
     *    上位対象も入れ替わる（モニカ/休 が新たに可視化）。
     *    残る計測外は `EliteIntegrationPolish`(4)・`C1TemporalFlowPolish`(1)・`CombinatorialRepair`(2)・
     *    `C1RepairAnalysis`(1) の計8箇所と、ピン保護を持たない探索本体(SA/ALNS/LAHC)。
     *  - よって「N 件の手が緩和で通る」ではなく「**少なくとも N 回、回数固定だけが却下の理由だった**」
     *    が言えることの上限。0 でも「緩めても何も変わらない」の証明にはならない（未計測分がある）。
     */
    val observedPinBlockedAttempts: Int = 0,
    /** [3.326.0] どのピン(職員,シフト)が何回止めたか。緩和対象の提示に使う。 */
    val pinBlocks: PinBlockAttribution? = null,
)

/**
 * [後処理研磨のユーザー設定ゲート] UI トグル → エンジン内部フラグの受け渡し。
 * `NativeGate`（ネイティブ加速／Kotlin照合）と同じ形で、呼び出し鎖に引数を通さずに設定を届ける。
 * セッション内のみ（state には保存しない＝勤務表データに影響しない実行時の調整）。
 */
object PolishGate {
    /**
     * [c3n 回避の範囲拡張, 3.303.0] 禁止連続を崩しに行く日を j±1 固定から「パターンがまたぐ全日」へ
     * 広げる。3連（`Dﾃ→休→A4`）の先頭 j-2 に届くようになる**正しい**一般化だが、
     * **実データ3件で利得が一貫しなかったため既定 OFF**（golden=中立 / real=weighted −1674 だが
     * covU 2件を c3n 2件へ付け替え・c1 +14 / user=weighted +73 悪化）。
     *
     * 個々の手は keep-best なので退化しないが、候補が増えると探索の経路が変わり、着地する局所解が
     * データによって良くも悪くもなる（2.55.0 の戦略的振動・3.94.0 の in-loop レバーと同じ結論＝
     * 「安全であること」と「有益であること」は別）。計測が支持しない既定変更はしない。
     */
    @Volatile
    var wideC3nBreakDays: Boolean = false

    // [3.409.21/ユーザー選択「両方削除」] adaptiveEscapeControl（停滞脱出の適応制御・3.306.0）と
    //   portfolioRoleParallelSa/portfolioRoleChains（ロール内並列SA・3.371.0）は削除した。
    //   単体 A/B（1プロセス=1実行・各15ペア・基準は測定前に固定「12/15 で採否」）の結果:
    //   parallelSa = ON7/OFF8（中立。しかも ON は反復数中央値が2/3データセットで**低い**＝
    //   チェーン分割が希釈になっていた: blocked 45M vs 57M・sample 53M vs 60M）、
    //   escape = ON5/OFF10（中立〜OFF寄り。3.306.0 の n=24 と合わせ2度目の中立）。
    //   hard 中央値はどちらも全データセットで不変。docs/algorithm_portfolio.md「廃止・統合済み」参照。

    /**
     * ブロック巡回交換で、禁止連続(c3n)が正味増える候補を**候補生成の段階で**捨てるか。既定 **true**
     * （3.518.0で確定。ON/OFFで採用結果は変わらないため新規A/Bは不要＝既存測定を適用）。
     *
     * c3n は HARD なので増える候補は最終的に `isBetter` が必ず却下する＝ON/OFF で**採用結果は変わらない**
     * （3.296.0 の A/B 実測で最終盤面・採用数が完全一致することを確認済み）。ON にすると構造的に詰んだ
     * 候補へフル checker を呼ばなくなり、評価枠を soft 判定まで進める候補へ回せる
     * （実測: 正式評価 48→14〜38 件）。
     */
    @Volatile var filterC3nIncrease: Boolean = true

    /**
     * [3.422.0/ユーザー報告「停滞の早期終了が実質効いていない」への対応・Part B]
     * `V6FinalPort` の停滞ウォッチドッグ「通常」分岐（HARD が構造床にまだ届いていない＝
     * 解ける可能性がある局面）の停滞閾値の割合。既定 **0.9** ＝旧来の固定値 `9/10` と厳密に同一。
     *
     * [3.424.0で意味論を是正] 適用は `V6FinalPort.normalStallMs`＝**予算×この割合**が基本で、
     * その値が探索区間内で一度も発火し得ない帯（実測60秒帯）だけ**探索区間×この割合**へ
     * フォールバックする（3.422.0 初版の無条件 `searchWindowMs×割合` は到達可能な帯まで無計測で
     * 厳格化していたため復元）。値は `normalStallMs` 側の require で **(0,1) 排他・有限のみ**＝
     * 1.0 以上は「閾値>=探索区間」という Part A が直した到達不能バグの再現、NaN は 20秒床への
     * 暗黙の崩落になるため、丸めず落とす。**UI トグルは無し**＝コード/計測ハーネスからのみ設定
     * （`filterC3nIncrease` 等と違い設定タブには出していない）。
     *
     * **なぜ `STALL_OVERRIDE_FACTOR`（上書き倍率）でなく、この割合自体を対象にしたか**:
     * ユーザーが選択した AskUserQuestion の選択肢は文面上「上書き倍率を予算内に収まる値へ改める」
     * だったが、実装前に算術で検算したところ**この経路は数学的にほぼ無力**と判明した。
     * `stallMs = 0.9 × 区間` に対し、上書きが区間内に収まるには `factor < 1/0.9 ≈ 1.111` が必要。
     * しかし factor が 1.0 に近いほど上書きは基準閾値と重複するだけ
     * （上書きは本来「フェーズ猶予が満たせない場合の保険」＝基準より緩い＝より長く待つ側であるべき）で、
     * factor が 1.111 に近いほど上書き閾値は区間の `≈0.999` 倍（探索締切と実質同じ）＝早期終了の
     * 意味を失う。**有効な範囲(1.0〜1.111)のどこを取っても、通常分岐が「実質早く終わる」効果は出ない**
     * （選択肢の副文言「または残り予算の一定割合」がこの袋小路を示唆していたため、そちらの精神＝
     * 「基準閾値そのものを対象にする」を採った）。
     *
     * **歴史的後悔との関係**: 旧 `stallMs=budgetMs/6`（300s予算で50s）は HARD=1（まだ解ける可能性がある）
     * を早すぎるタイミングで諦め、実機ログで残り250sを無駄にした（`V6FinalPort` の [5分強化] コメント参照）。
     * この割合を下げすぎると同じ後悔を再現しうる＝**A/B で実データにより支持された値のみを既定にする**
     * （2.55.0/2.56.0/3.310.1/3.341.1 の規律）。
     */
    @Volatile var normalStallFraction: Double = 0.9

    /**
     * [3.514.0/UIトグル化] `combineAndApply`のexhaustPairs（経緯: history 3.512.6）。既定OFFで確定
     * （3.519.0、iter24＝170ペア。速度が平均-5.6%/中央値-26.3%と遅くなるのに品質改善は中央値0%、
     * かつ large/infeasible の1ペアで必須件数が119→120へ増加＝退行ゼロを満たさず不合格。
     * 詳細は `docs/algorithm_portfolio.md`「実装済みだが既定OFF」参照）。
     */
    @Volatile var combineExhaustPairs: Boolean = false

    /**
     * [3.514.0/UIトグル化] C1共同LNS・個人共同LNSのlnsAdaptive（経緯: history 3.510.2 iter9）。
     * 既定 **true**（3.518.0で確定。根拠・数値は `docs/algorithm_portfolio.md`「既定ONへ昇格」参照）。
     */
    @Volatile var lnsAdaptive: Boolean = true

    /**
     * [3.517.0] AdaptivePortfolio の新ロール `PERSON_SWAP_ILS`（全月入替→再最適化のILS摂動）を
     * 役割ローテーションに含めるか。既定 **true**（3.519.0で確定。実データ4件×5seedで必須退行0件・
     * 全4件で品質が同等以上。根拠は `docs/algorithm_portfolio.md`「既定ONへ昇格」参照）。
     */
    @Volatile var personSwapKick: Boolean = true

    /**
     * [3.535.0/HF77明示数値指示] `AptFairPolish.applyAptPolish`/`applyFairPolish`の採否で、対象家族
     * (apt/fair)以外のSOFT合計比+6%まで悪化を容認する（累積予算、`AptFairPolish.toleratedBetter`参照。
     * HARDの不増加・keep-bestの根幹は不変）。既定 **false**（詳細は`docs/algorithm_portfolio.md`参照）。
     */
    @Volatile var aptFairSoftTolerance: Boolean = false

    /** [3.540.0] 回数連鎖研磨（`CountChainPolish`）を後処理に入れる。既定 **false**（A/B 138 ペアで新2/同等135/旧1＝ゲート不合格、docs/algorithm_portfolio.md）。 */
    @Volatile var countChainPolish: Boolean = false
}

/**
 * [3.356.0/ユーザー指示「オプションを減らせるようにログ強化する」] 設定タブ→詳細設定の調整トグルが
 * **その実行で実際に何をしたか**を数える。旧: トグルは6つあるのに、ログを見ても「ONにした意味が
 * あったか」が読めず、減らす判断ができなかった（`禁止連続の崩し範囲`・`立て直し方` に至っては
 * 実行の痕跡が一切出ない）。数回まわして毎回「観測なし」なら、そのトグルは消してよい、と言える。
 * [3.409.21] この計測が実際に判断を支えた＝立て直し方(adaptiveEscapeControl)とロール内並列SA
 * (portfolioRoleParallelSa)は単体 A/B の中立を根拠に削除（PolishGate 冒頭の記録参照）。
 *
 * 読み取り専用の計数のみ＝探索・採否・スコアには一切影響しない。`optimize()` 入口で reset する。
 */
object TuningTelemetry {
    // [3.360.1/敵対検証] 旧実装は `@Volatile var Int` に `++`＝read-modify-write で、**8並列ワーカーから
    //   加算されるため取りこぼしていた**（parityChecks は SA/LAHC/ALNS/研磨の4経路×全ワーカーから毎チャンク）。
    //   ログは「1240回」と断定するので、下限を実数として出していたことになる。AtomicInteger へ。
    //   加算は最も多い wideC3nCalls でも実行あたり1万回弱＝checker 1回より桁違いに安く、速度への影響はない。
    //   ※「この実行では観測なし(==0)」の判定は旧実装でも健全だった（真の回数が1以上なら必ず1は書かれる）。
    //     壊れていたのは大きさだけ。3.356.0 の「0ならトグルを消してよい」という判断根拠は無傷。
    /** 禁止連続の事前フィルタが checker を呼ばずに落とした候補数。 */
    val c3nFilterSkipped = java.util.concurrent.atomic.AtomicInteger(0)
    /** 禁止連続の崩し範囲が既定(前後1日)と違う候補日を返した回数（広がる／狭まるの両方）。 */
    val wideC3nDiffered = java.util.concurrent.atomic.AtomicInteger(0)
    /** 同・呼ばれた回数（広がらなかった分も含む）。 */
    val wideC3nCalls = java.util.concurrent.atomic.AtomicInteger(0)
    /** 仕上げ最適化により PhaseB(LAHC) へ切り替わった回数。 */
    val lahcEntered = java.util.concurrent.atomic.AtomicInteger(0)
    /** Kotlin照合を実施した回数（ネイティブ結果を採用する直前の再評価）。 */
    val parityChecks = java.util.concurrent.atomic.AtomicInteger(0)
    /** [3.535.0] apt/fair研磨の6%許容が、素のbetterReportなら却下される手を採用に転じさせた回数。 */
    val aptFairToleranceUsed = java.util.concurrent.atomic.AtomicInteger(0)
    /** [3.540.0] 回数連鎖研磨が採用した連鎖数。 */
    val countChainApplied = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 実行ごとに 0 へ戻す（`optimize()` 入口）。
     *
     * **既知の限界（意図的に残す）**: これは実行をまたぐ static なので、実行が重なると
     * （WorkManager の REPLACE で旧 Worker が協調キャンセルを待つ間など）後発の reset が
     * 先行実行の計数を消し、両者が同じ箱へ加算する。3.335.0 は同型の問題を `RunSlot`
     * （コルーチンのコンテキストで実行ごとの箱を運ぶ）で解いたが、加算元の
     * [breakableDaysFor] などは非 suspend の純関数でコンテキストを読めないため同じ手が使えない。
     * 影響は**片方のログの診断値がずれる**だけで、勤務表・採否・スコアには一切触れない。
     */
    fun reset() {
        c3nFilterSkipped.set(0); wideC3nDiffered.set(0); wideC3nCalls.set(0)
        lahcEntered.set(0); parityChecks.set(0); aptFairToleranceUsed.set(0); countChainApplied.set(0)
    }

    /** 各トグルの ON/OFF と、その実行で観測できた効果を1行にまとめる。 */
    fun summary(nativeOn: Boolean, parityOn: Boolean, softPolishOn: Boolean): String {
        fun eff(on: Boolean, n: Int, unit: String): String =
            if (!on) "OFF" else if (n > 0) "ON($n$unit)" else "ON(この実行では観測なし)"
        // 同一の値を2回読むと表示内で食い違う（別スレッドが加算しうる）ため、判定も表示も1回の読みで済ませる。
        val calls = wideC3nCalls.get()
        val differed = wideC3nDiffered.get()
        val wide = when {
            !PolishGate.wideC3nBreakDays -> "OFF"
            calls == 0 -> "ON(この実行では出番なし)"
            differed == 0 -> "ON(${calls}回呼ばれたが既定(前後1日)と同じ範囲＝OFFと差なし)"
            else -> "ON(${calls}回中${differed}回は既定(前後1日)と違う範囲を探索)"
        }
        // タグ（MirrorLog tag="設定の効き"）が同じ語を出すため、ここに前置きを付けると
        // 実機ログで「設定の効き: 設定の効き: …」と二重になる（3.409.16 で実機ログにより発覚）。本文だけを返す。
        return "ネイティブ加速=" + (if (nativeOn) "ON" else "OFF") +
            " / Kotlin照合=" + eff(parityOn, parityChecks.get(), "回") +
            " / 禁止連続の事前フィルタ=" + eff(PolishGate.filterC3nIncrease, c3nFilterSkipped.get(), "件の無駄な検査を省略・勤務表は不変") +
            " / 禁止連続の崩し範囲=" + wide +
            " / 仕上げ最適化=" + eff(softPolishOn, lahcEntered.get(), "回LAHCへ切替") +
            " / 結合探索を粘り強く=" + (if (PolishGate.combineExhaustPairs) "ON" else "OFF") +
            " / 一括見直しの自動調整=" + (if (PolishGate.lnsAdaptive) "ON" else "OFF") +
            " / 公平化/適切回数の他ソフト許容(6%)=" + eff(PolishGate.aptFairSoftTolerance, aptFairToleranceUsed.get(), "回、却下されるはずの手を採用") +
            " / 回数連鎖研磨=" + eff(PolishGate.countChainPolish, countChainApplied.get(), "連鎖を採用")
    }
}

object V6HotfixPasses {
    /**
     * [頭打ち調査・「なぜゼロにならないのか」] C1Polish/C3mnPolish/RangePolish/C3RunPolish は
     * `runPostOptimization`のフィックスポイント巡回(最大maxRounds=4)から**ラウンドごとに再呼出**
     * されるが、旧実装はseed引数を渡さず既定値固定のままだった。findCovUChainの候補順はrng由来
     * なので、ある(staff,shift)ペアがラウンドNで頭打ち(候補が構造的に全滅/isBetterに拒否)すると、
     * 盤面の当該箇所が変化しない限りラウンドN+1以降も**全く同じrng列＝同じ結果**を再生するだけで、
     * 永久に頭打ちのまま抜け出せなかった（桒澤美幸のAｱ超過が段階的にしか縮まらない実例で発覚）。
     * ラウンドごとに異なるseedを与え、再挑戦のたびに違う候補順を試せるようにする（isBetterによる
     * keep-best採否は不変＝退化不能。単なる探索の多様化）。
     */
    internal fun roundSeed(base: Long, tag: Long, round: Int) = base xor tag xor (round.toLong() * -0x61c8864680b583ebL)

    /**
     * 後処理チェーンの探索幅と予算（3.500.0 で集約。既定値はすべて従来の手書き値＝挙動不変）。
     * - HF67/HF66 の上限（[hf67CapMs]/[hf66CapMs]）は「残予算の半分・絶対上限」の保険（3.282.0）。実機は数十 ms＝通常は無影響で、
     *   大規模データでのフォールバック総当たり暴走だけを防ぐ。
     * - [jointLnsReserveMaxMs]: 巡回研磨クラスタの前に共同 LNS 2 本（既定 8s+6s）のための残予算の半分を確保する（3.271.0、
     *   実機ログ 2 本で「クラスタが予約枠を使い切り LNS が毎回 上限0 でスキップ」の飢餓を実証）。
     * - [maxRounds]: クラスタのフィックスポイント巡回。1 巡で 1 手も採用されなければ早期終了。
     * - [c1LnsMaxMs]/[personalLnsMaxMs]: 最終 LNS 2 本の既定上限。残予算は既定比 8:6 で按分し、[remainingClampMs] で
     *   乗算オーバーフローを避ける（3.255.0 の予算按分・境界 14,000ms で双方が既定を過不足なく得る）。
     * - 各 `*Passes`/`*Evaluations` は各パスへそのまま渡す探索幅。
     */
    data class PostOptimizationParams(
        val hf80MaxCycles: Int = 3,
        val hf67MaxSwaps: Int = 30,
        val hf67CapMs: Long = 3_000L,
        val hf66MaxMoves: Int = 30,
        val hf66CapMs: Long = 6_000L,
        val jointLnsReserveMaxMs: Long = 14_000L,
        val maxRounds: Int = 4,
        val cyclicSwapPasses: Int = 4,
        /** [3.511.2/測定中] 循環交換(CyclicSwapPolish)の最大人数。既定 3=既存 k=2,3 の全列挙のみ（挙動不変）。
         *  4/5 にすると k=4,5 の循環をランダム試行で追加する（backlog #12(b)/#13(e)）。既定 OFF。 */
        val cyclicSwapMaxK: Int = 3,
        val cyclicSwapKTrialsPerDay: Int = 20,
        /** [3.511.3/測定中] 個人合計(c2)専用研磨（backlog #12(b)）。既定 OFF。 */
        val c2PolishEnabled: Boolean = false,
        val c2Passes: Int = 3,
        /** [3.511.5/測定中] 群/日レンジ(c41/c41s)専用の min-cost-flow 研磨（backlog #12(b)）。既定 OFF。 */
        val c41FlowPolishEnabled: Boolean = false,
        val c41FlowPasses: Int = 3,
        /** [3.511.7/測定中] 群ペア禁止(c42/c42s)専用の min-cost-flow 研磨（backlog #12(b)）。既定 OFF。 */
        val c42FlowPolishEnabled: Boolean = false,
        val c42FlowPasses: Int = 3,
        val c1WindowPasses: Int = 3,
        val c1FlowPasses: Int = 2,
        val c1FlowRelocations: Int = 4,
        val c1FlowTrials: Int = 4,
        val c3SequencePasses: Int = 3,
        val c3RotatePasses: Int = 2,
        val c3mnPasses: Int = 3,
        val c3nPasses: Int = 3,
        val rangePasses: Int = 3,
        val c3RunPasses: Int = 3,
        val c3PatternPasses: Int = 3,
        val anchorWindowPasses: Int = 3,
        val anchorWindowEvaluations: Int = 48,
        val wishIslandPasses: Int = 3,
        val wishIslandEvaluations: Int = 120,
        val blockSwapPasses: Int = 2,
        val blockSwapCandidatesPerLength: Int = 8,
        val blockSwapEvaluations: Int = 48,
        val aptPasses: Int = 3,
        val fairPasses: Int = 3,
        val weeklyRebalancePasses: Int = 2,
        val alternatingSweeps: Int = 4,
        val c1LnsMaxMs: Long = 8_000L,
        val personalLnsMaxMs: Long = 6_000L,
        val remainingClampMs: Long = 100_000L,
        val passLogTopN: Int = 8,
        /** [Iteration 2] 各パスの拒否候補を巡の末尾で違反起点のトランザクションに束ねる（ViolationComponentRepair）。3.505.1 でハイブリッド併用＝既定 ON。 */
        val componentRepairEnabled: Boolean = true,
        val componentRepair: ViolationComponentRepair.Params = ViolationComponentRepair.Params(),
        /** 起点生成つきの修復は共同 LNS の**後**に 1 回だけ（巡の中で単セル covU 修正を採ると LNS の余地を先に使う＝3.505.4 で HARD 退行を実測）。 */
        val componentRepairFinal: Boolean = true,
        /** [Iteration 7] 決定的モード＝時間（ms キャップ・締切・残り時間の判定）でなく回数で止める。同じ入力・seed なら同じ盤面。
         *  ベンチと再現性の検証用（実機は既定 false＝予算を使い切る）。外部の shouldStop は常に尊重する。 */
        val deterministic: Boolean = false,
        val c1LnsMaxEvaluations: Int = 90_000,
        val personalLnsMaxEvaluations: Int = 60_000,
        /** [3.510.0/測定中] 最終段の「連続規則 選択日ペア交換」（C3PairMaskPolish）。採否は tools/loop のペア比較で決める＝既定 OFF。 */
        val c3PairMaskEnabled: Boolean = false,
        val c3PairMaskEvaluations: Int = 3_000,
        /** [測定中] 最終段の「c3n(禁止連続) 前後余白込みLNS」（C3nMarginLnsPolish）。採否は tools/loop のペア比較で決める＝既定 OFF。 */
        val c3nMarginLnsEnabled: Boolean = false,
        val c3nMarginLnsMarginDays: Int = 2,
        val c3nMarginLnsEvaluations: Int = 3_000,
        /** [3.510.2/測定中] 共同 LNS を「短い試行→採用があったときだけ本予算で続行」にする（backlog #14(a)）。既定 OFF。 */
        val lnsAdaptive: Boolean = false,
        val c1LnsFirstEvaluations: Int = 20_000,
        val personalLnsFirstEvaluations: Int = 15_000,
        val c1LnsFirstMs: Long = 1_500L,
        val personalLnsFirstMs: Long = 1_500L,
        /** [3.510.4/測定中] 共同 LNS の中間ノードの一時負債を件数でなく重み（負債 ≤ クレジット×係数）で絞る（backlog #15(f)）。既定 OFF。 */
        val lnsWeightDebt: Boolean = false,
        val lnsDebtFactor: Double = 2.0,
        /** [3.511.0/測定中] 長期ブロック交換の候補長を固定 11/13/17/19/23/28 日だけでなく、違反窓長・禁止連長・
         *  希望島半径・当月日数からも導出する（backlog #14(c)）。既定 OFF。 */
        val useDynamicBlockLens: Boolean = false,
        /** [3.511.1/測定中] 巡回研磨クラスタが1巡も採用0（停滞）だったときだけ、共同LNS2本のstage1が採用0の場合に
         *  もう1回だけ幅（対象人数/goal数）を広げて試し、成分修復の最終段も窓長・起点数を広げる（backlog #12(b)/#13(a)）。
         *  巡回研磨クラスタの round loop 自体（LNS前）は変えない＝3.505.4で否決済みの領域（巡の中の起点生成拡大）は再度触らない。既定 OFF。 */
        val stallEscalation: StallEscalationConfig = StallEscalationConfig(),
        /** [C1 重複窓の連結成分化/測定中] 厳密窓修復(C1ExactRepair)の起点を、1件の違反でなく近接・重複窓を
         *  束ねた連結成分にする（backlog「C1 重複窓の連結成分化」）。既定 OFF＝挙動不変。 */
        val c1ComponentRepair: Boolean = false,
        /** [backlog #12(a)・実験段階] c2/c41/c41sを二値でなく不足量/距離量で評価する（既定false=挙動不変）。
         *  全Polishパス・checker呼出へ明示伝播（Stage2）。 */
        val quantitativeRangeEval: Boolean = false,
        /** [測定中/3.512.6] `CombinatorialRepair.combineAndApply` の2人組(k=2)探索を既定の連続不採用200回より
         *  多くても打ち切らず試す（経緯: history 3.512.6）。既定false=挙動不変。 */
        val combineExhaustPairs: Boolean = false,
        /** [3.535.0/HF77明示数値指示] AptPolish/FairPolishの採否で、研磨開始時点の対象家族(apt/fair)
         *  以外のSOFT合計比+6%まで悪化を容認する（累積予算・keep-bestのHARD不増加は不変）。既定false。 */
        val aptFairSoftTolerance: Boolean = false,
        /** [3.540.0/測定中] 回数連鎖研磨（`CountChainPolish`）。既定 OFF。 */
        val countChainEnabled: Boolean = false,
    )

    /** [3.511.1/測定中] 停滞時（巡回研磨クラスタが1巡も採用0）の探索幅拡大トグル。backlog #12(b)/#13(a)。 */
    data class StallEscalationConfig(
        val enabled: Boolean = false,
        /** [ViolationComponentRepair.Params.maxWindowLength] への倍率（内部で p.T-1 に自動クランプ済み）。 */
        val radiusFactor: Int = 2,
        /** 成分修復の [ViolationComponentRepair.Params.maxAnchors]/[maxPatchesPerAnchor]、両共同LNSの
         *  goal数/restart数（個人側はさらに対象職員数）への倍率。 */
        val scopeFactor: Int = 2,
    )

    /** 巡ごとの乱数列を分けるためのパス別タグ（[roundSeed]）。値は 3.499.0 以前の手書き値と同じ＝乱数列不変。 */
    private object SeedTag {
        const val HF80 = 0x80L
        const val C1_WINDOW = 0x1C1L
        const val C1_INDEX = 0x1C1D2L
        const val C1_FLOW = 0xC1F10L
        const val C1_BEAM = 0xC1BEAL
        const val C3MN = 0xC3AL
        const val C3N = 0xC3EL
        const val RANGE = 0x8A9EL
        const val C3RUN = 0xC3A2L
        const val C3PATTERN = 0xC3B4L
        const val APT = 0xA97L
        const val FAIR = 0xFA12L
        const val C3PAIR = 0xC3AA1L
        const val CYCLIC_N = 0xC1C54L
        const val C3N_MARGIN = 0xC3E9L
    }

    /** SoftPolishVerify の「採用内訳」の並び（ログ文言の順序を固定する）。 */
    private val adoptionKeys = listOf(
        "循環", "c1", "c3", "c3回転", "c3mn玉突き", "c3n", "range玉突き", "c3run玉突き", "c3pattern玉突き",
        "アンカー窓交換", "希望島", "ブロック交換", "c2玉突き", "c41フロー", "c42フロー", "apt玉突き", "fair玉突き", "成分修復",
    )

    /** SoftPolishVerify で「対象」に数える族（3.278.0 で CyclicSwap の対象族、3.475.0 で c3n を追加）。 */
    private val softTargetFamilies = listOf(
        "c1", "c3", "c3m", "c3mn", "c3n", "low", "high", "apt", "fair", "c2", "c41", "c42", "c41s", "c42s", "covO",
    )

    /**
     * 後処理チェーンの作業域＝盤面・ログ・パス別所要・ピン帰属の合流点。
     * 各パスは必ず [adopt] を通す＝「pinBlocks の合流を書き忘れる」（3.350.0・3.409.9 で実際に起きた）を構造的に防ぐ。
     */
    private class PostChain(private val onPhase: (String) -> Unit, schedule: Array<IntArray>) {
        var work: Array<IntArray> = schedule.copy2D()
            private set
        val logs = ArrayList<MirrorLog>()
        val passMs = LinkedHashMap<String, Long>()
        val pinBlocksAll = PinBlockAttribution()
        /** [Iteration 2] 巡の中で各パスが残した拒否候補。巡の末尾で違反連結成分修復へ渡して空にする。 */
        val rejectedPool = ArrayList<CombinatorialRepair.Candidate>()

        /** フェーズ名を UI へ通知し、所要 ms を [key] に累算しながら [block] を実行する。 */
        fun <R> timed(phase: String, key: String, block: (Array<IntArray>) -> R): R {
            onPhase(phase)
            val t = EngineClock.nowMs()
            val r = block(work)
            passMs.merge(key, EngineClock.nowMs() - t) { a, b -> a + b }
            return r
        }

        /** 結果を盤面へ反映し、ピン帰属を合流させ、[keepLogs] のときだけログを積む。採用数を返す。 */
        fun adopt(r: CyclicSwapResult, keepLogs: Boolean = true): Int {
            r.pinBlocks?.let { pinBlocksAll.merge(it) }
            rejectedPool.addAll(r.rejectedCandidates)
            work = r.newSchedule.copy2D()
            if (keepLogs) logs.addAll(r.logs)
            return r.applied
        }

        fun adopt(r: DayAssignmentPolish.DayAssignResult) {
            r.pinBlocks?.let { pinBlocksAll.merge(it) }
            work = r.newSchedule.copy2D()
            logs.addAll(r.logs)
        }

        fun replaceBoard(newSchedule: Array<IntArray>, passLogs: List<MirrorLog>) {
            work = newSchedule.copy2D()
            logs.addAll(passLogs)
        }
    }

    /**
     * [review: budget] 後処理チェーン HF80 -> HF67 -> HF66 -> 厳密日割当 -> 巡回研磨クラスタ（最大 maxRounds 巡）->
     * 曜日/交互研磨 -> 共同 LNS 2 本 -> HF70。全パス keep-best（正式チェッカーの HARD→weighted→total）なので
     * 順序・巡回数・予算配分は「時間の使い方」だけを変え、退化はしない。
     * @param shouldStop true を返した時点で各パスの反復を打ち切る。全体予算(deadline)超過と coroutine キャンセルの両方を
     *        呼び出し側でこのラムダに束ねる。HF70(異常検知=安価)は診断のため常に実行する。
     * @param onPhase 各パス開始時に呼ばれ、UI 進捗を後処理中も更新できる(ハング誤認の防止)。
     */
    fun runPostOptimization(
        state: MagiState,
        schedule: Array<IntArray>,
        algoName: String,
        seed: Long = System.nanoTime(),
        shouldStop: () -> Boolean = { false },
        onPhase: (String) -> Unit = {},
        deadlineMs: Long = Long.MAX_VALUE,
        params: PostOptimizationParams = PostOptimizationParams(),
    ): V6PostOptimizationResult {
        val chain = PostChain(onPhase, schedule)
        val t0 = EngineClock.nowMs()
        val report0 = UnifiedViolationChecker.check(state, schedule, quantitativeRangeEval = params.quantitativeRangeEval)

        val r80 = chain.timed("後処理 HF80 戦略的振動", "HF80StrategicOscillation") { work ->
            applyHF80StrategicOscillation(state, work, maxCycles = params.hf80MaxCycles, seed = seed xor SeedTag.HF80, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
        }
        chain.replaceBoard(r80.newSchedule, r80.logs)

        val t67 = EngineClock.nowMs()
        val r67 = chain.timed("後処理 HF67 職員間スワップ", "HF67InterStaffSwap") { work ->
            val cap = (EngineClock.remainingMs(deadlineMs, t67) / 2).coerceAtMost(params.hf67CapMs)
            HfSwapPolish.applyHF67InterStaffSwap(state, work, maxSwaps = params.hf67MaxSwaps, shouldStop = shouldStop, deadlineMs = if (params.deterministic) Long.MAX_VALUE else t67 + cap, quantitativeRangeEval = params.quantitativeRangeEval)
        }
        chain.replaceBoard(r67.newSchedule, r67.logs)

        val t66 = EngineClock.nowMs()
        val r66 = chain.timed("後処理 HF66 職員内再配分", "HF66IntraStaffRedistribution") { work ->
            // HF66 は手ごとに全候補をフル check する高コストパス＝残予算の半分（後段の研磨群へ残り半分）で打ち切る。
            val cap = (EngineClock.remainingMs(deadlineMs, t66) / 2).coerceAtMost(params.hf66CapMs)
            HfSwapPolish.applyHF66IntraStaffRedistribution(state, work, maxMoves = params.hf66MaxMoves, shouldStop = shouldStop, deadlineMs = if (params.deterministic) Long.MAX_VALUE else t66 + cap, quantitativeRangeEval = params.quantitativeRangeEval)
        }
        chain.replaceBoard(r66.newSchedule, r66.logs)
        val t66Done = EngineClock.nowMs()

        // 巡回研磨クラスタは自身の締切を持たないため、共同 LNS 2 本の取り分を先に確保して clusterStop に畳む（3.271.0）。
        val jointLnsReserve = if (deadlineMs == Long.MAX_VALUE || params.deterministic) 0L
            else ((deadlineMs - t66Done).coerceAtLeast(0L) / 2).coerceAtMost(params.jointLnsReserveMaxMs)
        val clusterDeadline = if (deadlineMs == Long.MAX_VALUE || params.deterministic) Long.MAX_VALUE else deadlineMs - jointLnsReserve
        val clusterStop: () -> Boolean = { shouldStop() || EngineClock.nowMs() >= clusterDeadline }

        chain.adopt(chain.timed("後処理 厳密日割当", "DayAssignmentPolish") { work ->
            DayAssignmentPolish.applyDayAssignmentPolish(state, work, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval)
        })

        // ソフト研磨クラスタの前後を測る基準（SoftPolishVerify）。
        val preSoftRep = UnifiedViolationChecker.check(state, chain.work, quantitativeRangeEval = params.quantitativeRangeEval)
        val cluster = runPolishCluster(state, chain, params, seed, clusterStop, preSoftRep)
        // [3.511.1/測定中] 巡回研磨クラスタが1巡も採用0＝停滞。下流(共同LNS2本・成分修復の最終段)へ広げる合図だけを渡す
        //   （巡の中の起点生成拡大は3.505.4で否決済み＝round loop自体は変えない）。
        val stalled = params.stallEscalation.enabled && cluster.totalApplied == 0

        // weekly は同日 2 者スワップでは動かない（曜日別の勤務/休が不変）→ 被覆保存の 2 職員×2 日 長方形交換。
        chain.adopt(chain.timed("後処理 曜日平準化(長方形交換)", "WeeklyRebalancePolish") { work ->
            CyclicSwapWeeklyPolish.applyWeeklyRebalancePolish(state, work, maxPasses = params.weeklyRebalancePasses, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval)
        })
        // 長方形交換（クロス日）が届かない同日内の割当先を Hungarian で再配置＝相補的なので両方走らせる。
        chain.adopt(chain.timed("後処理 交互最適化(日ブロック割当)", "AlternatingSoftPolish") { work ->
            DayAssignmentPolish.applyAlternatingSoftPolish(state, work, maxSweeps = params.alternatingSweeps, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval)
        })

        // 最終 LNS 2 本（高コストなので巡回ループでなく最終 1 回）。残予算は既定比 8:6 で按分（3.255.0）。
        val tC1Lns = EngineClock.nowMs()
        val lnsTotal = params.c1LnsMaxMs + params.personalLnsMaxMs
        chain.adopt(chain.timed("後処理 期間要件(c1)共同LNS", "C1共同LNS") { work ->
            val remaining = EngineClock.remainingMs(deadlineMs, tC1Lns).coerceAtMost(params.remainingClampMs)
            val cap = if (lnsTotal <= 0L) 0L else (remaining * params.c1LnsMaxMs / lnsTotal).coerceAtMost(params.c1LnsMaxMs)
            val debtFactor = if (params.lnsWeightDebt) params.lnsDebtFactor else 0.0
            val cfg = if (params.deterministic) C1JointLnsPolish.Config(maxMillis = 60_000L, patienceMs = 0L, maxEvaluations = params.c1LnsMaxEvaluations, debtFactor = debtFactor)
                else C1JointLnsPolish.Config(maxMillis = cap, debtFactor = debtFactor)
            if (!params.lnsAdaptive) C1RepairOperators.jointLns(state, work, config = cfg, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
            else {
                // [3.510.2/測定中] 短い試行で採用が無ければそこで止める（ログでは共同 LNS が後処理時間の大半を使って採用 0 が多い）。
                val first = if (params.deterministic) cfg.copy(maxEvaluations = params.c1LnsFirstEvaluations) else cfg.copy(maxMillis = minOf(cap, params.c1LnsFirstMs))
                val r1 = C1RepairOperators.jointLns(state, work, config = first, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
                if (r1.applied == 0) {
                    // [3.511.1/測定中] クラスタが停滞していたときだけ、幅(goal数/restart数)を広げてもう1回だけ試す。
                    if (stalled) {
                        val f = params.stallEscalation.scopeFactor
                        val widened = first.copy(maxGoals = first.maxGoals * f, maxRestarts = first.maxRestarts * f)
                        C1RepairOperators.jointLns(state, work, config = widened, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
                    } else r1
                } else {
                    val r2 = C1RepairOperators.jointLns(state, r1.newSchedule, config = cfg, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
                    r2.copy(beforeTotal = r1.beforeTotal, applied = r1.applied + r2.applied, logs = r1.logs + r2.logs)
                }
            }
        })
        val tPersonalLns = EngineClock.nowMs()
        chain.adopt(chain.timed("後処理 個人回数/適切回数 共同LNS", "個人回数共同LNS") { work ->
            val cap = EngineClock.remainingMs(deadlineMs, tPersonalLns).coerceAtMost(params.personalLnsMaxMs)
            val debtFactor = if (params.lnsWeightDebt) params.lnsDebtFactor else 0.0
            val cfg = if (params.deterministic) PersonalBalanceJointLnsPolish.Config(maxMillis = 60_000L, maxEvaluations = params.personalLnsMaxEvaluations, debtFactor = debtFactor)
                else PersonalBalanceJointLnsPolish.Config(maxMillis = cap, debtFactor = debtFactor)
            if (!params.lnsAdaptive) PersonalBalanceJointLnsPolish.apply(state, work, config = cfg, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
            else {
                val first = if (params.deterministic) cfg.copy(maxEvaluations = params.personalLnsFirstEvaluations) else cfg.copy(maxMillis = minOf(cap, params.personalLnsFirstMs))
                val r1 = PersonalBalanceJointLnsPolish.apply(state, work, config = first, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
                if (r1.applied == 0) {
                    if (stalled) {
                        val f = params.stallEscalation.scopeFactor
                        val widened = first.copy(maxFocusStaff = first.maxFocusStaff * f, maxGoals = first.maxGoals * f, maxRestarts = first.maxRestarts * f)
                        PersonalBalanceJointLnsPolish.apply(state, work, config = widened, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
                    } else r1
                } else {
                    val r2 = PersonalBalanceJointLnsPolish.apply(state, r1.newSchedule, config = cfg, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
                    r2.copy(beforeTotal = r1.beforeTotal, applied = r1.applied + r2.applied, logs = r1.logs + r2.logs)
                }
            }
        })

        if (params.c3PairMaskEnabled && !shouldStop()) {
            // [3.510.0/測定中] 共同 LNS の後・成分修復の前。連続でない 1〜3 日の同日交換で c3 系の取り残しを拾う。
            val pairStop: () -> Boolean = if (params.deterministic) shouldStop else ({ shouldStop() || EngineClock.remainingMs(deadlineMs) <= 0L })
            chain.adopt(chain.timed("後処理 連続規則(c3系)選択日ペア交換(最終)", "C3PairMask") { work ->
                C3PairMaskPolish.apply(state, work, maxEvaluations = params.c3PairMaskEvaluations, shouldStop = pairStop, seed = seed xor SeedTag.C3PAIR, quantitativeRangeEval = params.quantitativeRangeEval)
            })
        }

        if (params.c3nMarginLnsEnabled && !shouldStop()) {
            // [測定中] 共同 LNS の後・成分修復の前。c3n(禁止連続)のパターン日+前後余白を複数セル同時に
            //   destroy-rebuildして、1セル付け替え(C3nPolish)が構造的に届かない局面を拾う。
            val marginStop: () -> Boolean = if (params.deterministic) shouldStop else ({ shouldStop() || EngineClock.remainingMs(deadlineMs) <= 0L })
            chain.adopt(chain.timed("後処理 c3n禁止連続(前後余白込みLNS・最終)", "C3nMarginLNS") { work ->
                C3nMarginLnsPolish.apply(state, work, marginDays = params.c3nMarginLnsMarginDays, maxEvaluations = params.c3nMarginLnsEvaluations, shouldStop = marginStop, seed = seed xor SeedTag.C3N_MARGIN, quantitativeRangeEval = params.quantitativeRangeEval)
            })
        }

        if (params.componentRepairEnabled && params.componentRepairFinal && !shouldStop()) {
            // [Iteration 5] 最終段の予算は残り時間に応じて拡張（2 秒以上残っていれば推定 4 倍・正式評価 2.5 倍）。締切は stop に畳む。
            val remainingFinal = EngineClock.remainingMs(deadlineMs)
            val base = params.componentRepair
            val timeWidened = if (params.deterministic || remainingFinal >= 2_000L) base.copy(maxEstimates = base.maxEstimates * 4, maxEvaluations = base.maxEvaluations * 5 / 2) else base
            // [3.511.1/測定中] 時間残量ベースの拡大(Iteration5)とは独立に併用＝停滞していれば窓長・起点数もさらに広げる。
            val finalParams = if (stalled) timeWidened.copy(
                maxWindowLength = timeWidened.maxWindowLength * params.stallEscalation.radiusFactor,
                maxAnchors = timeWidened.maxAnchors * params.stallEscalation.scopeFactor,
                maxPatchesPerAnchor = timeWidened.maxPatchesPerAnchor * params.stallEscalation.scopeFactor,
            ) else timeWidened
            val finalStop: () -> Boolean = if (params.deterministic) shouldStop else ({ shouldStop() || EngineClock.remainingMs(deadlineMs) <= 0L })
            chain.adopt(chain.timed("後処理 違反起点修復(最終)", "ComponentRepair") { work ->
                ViolationComponentRepair.repair(state, work, chain.rejectedPool.toList(), finalParams, shouldStop = finalStop, quantitativeRangeEval = params.quantitativeRangeEval)
            })
            chain.rejectedPool.clear()
        }

        val tHf = EngineClock.nowMs()
        if (shouldStop()) {
            chain.logs.add(MirrorLog(level = "W", tag = "POST", message = "予算超過のため後処理は締切で短縮されました(各パスは内部で打ち切り済み・以降は最終検査のみ)"))
        }

        onPhase("後処理 HF70 異常検知")
        val work = chain.work
        val report = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = params.quantitativeRangeEval)
        val r70 = HfSwapPolish.detectHF70Anomalies(state, work, algoName, report, quantitativeRangeEval = params.quantitativeRangeEval)
        chain.logs.addAll(r70.logs)

        val tEnd = EngineClock.nowMs()
        chain.logs.add(MirrorLog(level = "I", tag = "POST",
            message = "後処理タイミング 総${tEnd - t0}ms: HF80=${t67 - t0}ms HF67=${t66 - t67}ms HF66=${t66Done - t66}ms" +
                " 巡回研磨(厳密日割当+c1/c3/range/apt/fair+曜日/交互)=${tC1Lns - t66Done}ms" +
                " C1共同LNS=${tPersonalLns - tC1Lns}ms 個人共同LNS=${tHf - tPersonalLns}ms" +
                " 最終検査+HF70=${tEnd - tHf}ms"))
        // パスごとの内訳（多い順・上位 N）。「時間を食っているのに採用0」のパスが各パス自身の行と突き合わせられる（3.339.0）。
        if (chain.passMs.isNotEmpty()) {
            val sum = chain.passMs.values.sum().coerceAtLeast(1L)
            chain.logs.add(MirrorLog(level = "I", tag = "POST",
                message = "後処理パス別 計${sum}ms: " + chain.passMs.entries.sortedByDescending { it.value }
                    .take(params.passLogTopN).joinToString(" ") { "${it.key}=${it.value}ms(${it.value * 100 / sum}%)" }))
        }

        chain.logs.add(MirrorLog(level = "I", tag = "POST", message = "後処理 収支: " + ChangeSummary.familyLine(ChangeSummary.familyDeltas(report0, report))))

        val plateauOut = finalC1Plateau(state, work, report, cluster.c1Plateau, params.quantitativeRangeEval)
        val allLogs = ArrayList<MirrorLog>(chain.logs)
        allLogs.addAll(report.logs)
        return V6PostOptimizationResult(
            work, report.copy(logs = allLogs), r80, r67, r66, r70, chain.logs, plateauOut,
            chain.pinBlocksAll.attempts, chain.pinBlocksAll,
        )
    }

    private class ClusterOutcome(val c1Plateau: C1PlateauDiagnosis?, val totalApplied: Int)

    /**
     * 巡回研磨クラスタ（循環交換〜fair 玉突き）を「1 巡で 1 手も採用されなくなるまで」最大 maxRounds 巡繰り返す。
     * 各パスは内部で自己収束するが、別パスの変更が他パスの改善を再び開く（例: c3 の組替えで新たな c1 充足余地が出る）。
     * 各パスの個別ログは巡 1 だけ積み（4 巡ぶんのスパム防止）、SoftPolishVerify の集約行は全巡合計を出す。
     */
    private fun runPolishCluster(
        state: MagiState,
        chain: PostChain,
        params: PostOptimizationParams,
        seed: Long,
        clusterStop: () -> Boolean,
        preSoftRep: ViolationReport,
    ): ClusterOutcome {
        val adopted = LinkedHashMap<String, Int>().also { m -> for (k in adoptionKeys) m[k] = 0 }
        val c3Anchor = setOf("vio-c3", "vio-c3m", "vio-c3mn")
        val pC1 = cachedProblem(state, params.quantitativeRangeEval)   // state の純関数＝巡回間で不変（C1DeltaPrefilter のゲート用）
        var c1Plateau: C1PlateauDiagnosis? = null
        var round = 0
        while (round < params.maxRounds && !clusterStop()) {
            val first = round == 0
            val tag = " [巡${round + 1}]"
            var roundApplied = 0
            fun take(key: String, r: CyclicSwapResult) {
                val n = chain.adopt(r, keepLogs = first)
                adopted[key] = (adopted[key] ?: 0) + n
                roundApplied += n
            }

            take("循環", chain.timed("後処理 循環交換(k=2,3)$tag", "CyclicSwapPolish") { work ->
                CyclicSwapWeeklyPolish.applyCyclicSwapPolish(
                    state, work, maxPasses = params.cyclicSwapPasses, maxK = params.cyclicSwapMaxK,
                    kTrialsPerDay = params.cyclicSwapKTrialsPerDay, seed = roundSeed(seed, SeedTag.CYCLIC_N, round),
                    shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval,
                )
            })

            // c1 違反セルに厳密アンカーする 2 op は、不足窓が無ければ必ず no-op＝C1DeltaPrefilter で 1 回判定して飛ばす（3.275.0/3.276.0）。
            if (C1DeltaPrefilter.hasActionableC1(C1RepairIndex.build(pC1, chain.work))) {
                val rC1 = chain.timed("後処理 期間要件(c1)研磨$tag", "C1同日交換") { work ->
                    C1RepairOperators.selfRelocateAndSameDaySwap(state, work, maxPasses = params.c1WindowPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C1_WINDOW, round), quantitativeRangeEval = params.quantitativeRangeEval, combineExhaustPairs = params.combineExhaustPairs)
                }
                take("c1", rC1)
                // 構造化診断は巡ごとに合算（3.331.0。最後の巡だけだと観測が減る）。末尾で最終盤面に対して再フィルタする。
                rC1.plateau?.let { fresh -> c1Plateau = c1Plateau?.mergedWith(fresh) ?: fresh }
                take("c1", chain.timed("後処理 期間要件(c1)index駆動修復$tag", "C1索引修復") { work ->
                    C1RepairOperators.indexChainRepair(state, work, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C1_INDEX, round), quantitativeRangeEval = params.quantitativeRangeEval)
                })
            }
            // 時系列 DP＋同日ジョイント再割当（3.254.0 の ablation で一本化）。広域ビームより前に置く（逆順は golden で劣化を実測）。
            take("c1", chain.timed("後処理 期間要件(c1)時系列DP+ジョイント再割当研磨$tag", "C1時系列フロー") { work ->
                C1RepairOperators.temporalFlow(
                    state, work, maxPasses = params.c1FlowPasses, maxRelocations = params.c1FlowRelocations, trials = params.c1FlowTrials,
                    shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C1_FLOW, round), quantitativeRangeEval = params.quantitativeRangeEval,
                )
            })
            take("c1", chain.timed("後処理 期間要件(c1)広域ビーム研磨$tag", "C1広域ビーム") { work ->
                C1RepairOperators.wideBeam(state, work, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C1_BEAM, round), quantitativeRangeEval = params.quantitativeRangeEval)
            })
            // 別日で連動して初めて解ける多職員手を、窓スコープの被覆保存 permutation 厳密探索で拾う。
            take("c1", chain.timed("後処理 期間要件(c1)厳密窓修復$tag", "C1厳密窓") { work ->
                C1RepairOperators.exactWindow(state, work, shouldStop = clusterStop, useComponents = params.c1ComponentRepair, quantitativeRangeEval = params.quantitativeRangeEval)
            })

            val rC3 = chain.timed("後処理 連続規則(c3系)研磨$tag", "C3SequencePolish") { work ->
                C3RotationPolish.applyC3SequencePolish(state, work, maxPasses = params.c3SequencePasses, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval)
            }
            take("c3", rC3)
            // 3 者回転は O(候補^3) で通常時の寄与ゼロ（3.300.0 ablation）＝主手が詰まった巡と最終巡だけの脱出手。
            if (rC3.applied == 0 || round == params.maxRounds - 1) {
                take("c3回転", chain.timed("後処理 連続規則(c3系)3者回転研磨$tag", "BlockRotationPolish") { work ->
                    C3RotationPolish.applyBlockRotationPolish(state, work, c3Anchor, "C3Rotate", maxPasses = params.c3RotatePasses, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval)
                })
            }
            take("c3mn玉突き", chain.timed("後処理 回避パターン(c3mn)玉突き研磨$tag", "C3mnPolish") { work ->
                C3FamilyPolish.applyC3mnPolish(state, work, maxPasses = params.c3mnPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C3MN, round), quantitativeRangeEval = params.quantitativeRangeEval, combineExhaustPairs = params.combineExhaustPairs)
            })
            take("c3n", chain.timed("後処理 禁止連続(c3n)研磨$tag", "C3nPolish") { work ->
                C3FamilyPolish.applyC3nPolish(state, work, maxPasses = params.c3nPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C3N, round), quantitativeRangeEval = params.quantitativeRangeEval, combineExhaustPairs = params.combineExhaustPairs)
            })
            take("range玉突き", chain.timed("後処理 個人回数(low/high)玉突き研磨$tag", "RangePolish") { work ->
                RangePolish.applyRangePolish(state, work, maxPasses = params.rangePasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.RANGE, round), quantitativeRangeEval = params.quantitativeRangeEval, combineExhaustPairs = params.combineExhaustPairs)
            })
            if (params.c41FlowPolishEnabled) {
                take("c41フロー", chain.timed("後処理 群/日レンジ(c41/c41s)フロー研磨$tag", "C41FlowPolish") { work ->
                    C41FlowPolish.applyC41FlowPolish(state, work, maxPasses = params.c41FlowPasses, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval)
                })
            }
            take("c3run玉突き", chain.timed("後処理 連続規則(c3/c3m単一シフト連)玉突き研磨$tag", "C3RunPolish") { work ->
                C3FamilyPolish.applyC3RunPolish(state, work, maxPasses = params.c3RunPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C3RUN, round), quantitativeRangeEval = params.quantitativeRangeEval)
            })
            take("c3pattern玉突き", chain.timed("後処理 連続規則(c3/c3m複数シフトパターン)玉突き研磨$tag", "C3PatternPolish") { work ->
                C3FamilyPolish.applyC3PatternPolish(state, work, maxPasses = params.c3PatternPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C3PATTERN, round), quantitativeRangeEval = params.quantitativeRangeEval)
            })
            // 違反アンカー型・可変長窓の一括交換（3.495.0、ユーザー提示の設計）。
            take("アンカー窓交換", chain.timed("後処理 違反アンカー窓交換$tag", "AnchoredWindowSwap") { work ->
                AdaptiveBlockSwapPolish.applyAdaptiveBlockSwapPolish(
                    state, work, mode = WindowMode.STRICT_WHOLE_WINDOW, maxPasses = params.anchorWindowPasses,
                    maxEvaluations = params.anchorWindowEvaluations, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval,
                )
            })
            // 希望島研磨（3.496.0、ユーザー提示の確定仕様）。
            take("希望島", chain.timed("後処理 希望島研磨$tag", "WishIslandPolish") { work ->
                WishIslandPolish.applyWishIslandPolish(state, work, maxPasses = params.wishIslandPasses, maxEvaluations = params.wishIslandEvaluations, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval)
            })
            take("ブロック交換", chain.timed("後処理 長期ブロック丸ごと交換$tag", "AdaptiveBlockSwapPolish") { work ->
                AdaptiveBlockSwapPolish.applyAdaptiveBlockSwapPolish(
                    state, work,
                    AdaptiveBlockSwapPolish.CyclicParams(
                        maxPasses = params.blockSwapPasses, candidatesPerLength = params.blockSwapCandidatesPerLength,
                        maxEvaluations = params.blockSwapEvaluations, useDynamicBlockLens = params.useDynamicBlockLens,
                    ),
                    shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval,
                )
            })
            if (params.c2PolishEnabled) {
                take("c2玉突き", chain.timed("後処理 個人合計(c2)研磨$tag", "C2Polish") { work ->
                    C2Polish.applyC2Polish(state, work, maxPasses = params.c2Passes, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval)
                })
            }
            if (params.countChainEnabled) {
                take("回数連鎖", chain.timed("後処理 回数連鎖研磨$tag", "CountChainPolish") { work ->
                    CountChainPolish.applyCountChainPolish(state, work, config = if (params.deterministic) CountChainPolish.Config(maxMillis = 60_000L) else CountChainPolish.Config(),
                        shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.RANGE, round), quantitativeRangeEval = params.quantitativeRangeEval)
                        .also { TuningTelemetry.countChainApplied.addAndGet(it.applied) }
                })
            }
            if (params.c42FlowPolishEnabled) {
                take("c42フロー", chain.timed("後処理 群ペア禁止(c42/c42s)フロー研磨$tag", "C42FlowPolish") { work ->
                    C42FlowPolish.applyC42FlowPolish(state, work, maxPasses = params.c42FlowPasses, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval)
                })
            }
            take("apt玉突き", chain.timed("後処理 適切回数(apt)研磨$tag", "AptPolish") { work ->
                AptFairPolish.applyAptPolish(state, work, maxPasses = params.aptPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.APT, round), quantitativeRangeEval = params.quantitativeRangeEval, combineExhaustPairs = params.combineExhaustPairs, aptFairSoftTolerance = params.aptFairSoftTolerance)
            })
            take("fair玉突き", chain.timed("後処理 グループ内公平化(fair)玉突き研磨$tag", "FairPolish") { work ->
                AptFairPolish.applyFairPolish(state, work, maxPasses = params.fairPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.FAIR, round), quantitativeRangeEval = params.quantitativeRangeEval, combineExhaustPairs = params.combineExhaustPairs, aptFairSoftTolerance = params.aptFairSoftTolerance)
            })
            // [Iteration 2] 巡の中で各パスが単独では不採用にした候補を、違反連結成分ごとにトランザクション結合する。
            val pool = chain.rejectedPool.toList(); chain.rejectedPool.clear()
            if (params.componentRepairEnabled && pool.size >= 2) {
                take("成分修復", chain.timed("後処理 違反連結成分修復$tag", "ComponentRepair") { work ->
                    ViolationComponentRepair.repair(state, work, pool, params.componentRepair.copy(generateFromAnchors = false), shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval)
                })
            }

            round++
            if (roundApplied == 0) break   // この巡で 1 手も採用なし＝joint 局所最適に到達
        }

        chain.logs.add(softPolishVerifyLog(state, chain.work, preSoftRep, round, adopted, params.quantitativeRangeEval))
        return ClusterOutcome(c1Plateau, adopted.values.sum())
    }

    /** 研磨可否の検証ログ。採用 0 かつ対象 > 0 なら「頭打ち（正常）」、対象 0 なら「対象なし」と明示する。 */
    private fun softPolishVerifyLog(
        state: MagiState, work: Array<IntArray>, preSoftRep: ViolationReport, rounds: Int, adopted: Map<String, Int>,
        quantitativeRangeEval: Boolean = false,
    ): MirrorLog {
        val softAfter = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        fun bd(r: ViolationReport, k: String) = r.breakdown[k] ?: 0
        val adoptedTotal = adopted.values.sum()
        val targets = softTargetFamilies.sumOf { bd(preSoftRep, it) }
        val verdict = when {
            adoptedTotal > 0 -> "有効(採用${adoptedTotal}手)"
            targets == 0 -> "対象なし"
            else -> "頭打ち(採用0=現在の探索範囲では改善手なし)"
        }
        val hardNote = if (softAfter.hard == preSoftRep.hard) "不変" else "変化${preSoftRep.hard}->${softAfter.hard}!"
        return MirrorLog(tag = "SoftPolishVerify", message =
            "ソフトc1/c3系研磨 可否=$verdict (${rounds}巡・各パス行は巡1のみ表示/本行は全巡合計) | c1 ${bd(preSoftRep, "c1")}->${bd(softAfter, "c1")}" +
                " / c3 ${bd(preSoftRep, "c3")}->${bd(softAfter, "c3")}" +
                " / c3m ${bd(preSoftRep, "c3m")}->${bd(softAfter, "c3m")}" +
                " / c3mn ${bd(preSoftRep, "c3mn")}->${bd(softAfter, "c3mn")}" +
                " / low ${bd(preSoftRep, "low")}->${bd(softAfter, "low")}" +
                " / high ${bd(preSoftRep, "high")}->${bd(softAfter, "high")}" +
                " / apt ${bd(preSoftRep, "apt")}->${bd(softAfter, "apt")}" +
                " / fair ${bd(preSoftRep, "fair")}->${bd(softAfter, "fair")}" +
                " | HARD $hardNote / total ${preSoftRep.total}->${softAfter.total}" +
                " (採用内訳 " + adopted.entries.joinToString(" ") { "${it.key}:${it.value}" } + ")")
    }

    /**
     * C1 研磨の時点で作った構造化診断（3.322.0）を最終盤面に合わせ直す（共同 LNS 等が直した箇所を「直せなかった」と見せない）。
     * c1 が残っているなら観測が 1 件も無くても診断を返す＝UI が「原因未確定」と出す（3.325.0）。
     */
    private fun finalC1Plateau(
        state: MagiState, work: Array<IntArray>, report: ViolationReport, plateau: C1PlateauDiagnosis?,
        quantitativeRangeEval: Boolean = false,
    ): C1PlateauDiagnosis? {
        val c1Left = report.breakdown["c1"] ?: 0
        val refreshed = plateau?.let { d ->
            val pFin = cachedProblem(state, quantitativeRangeEval)
            d.refreshedAgainst(c1Left) { i, x, ri ->
                val c = pFin.cons1.getOrNull(ri)
                c != null && c.shiftIdx == x && c.day1 > 0 &&
                    (0..pFin.T - c.day1).any { j -> inDeficientC1Window(pFin, work, i, x, c.day1, c.day2, j) }
            }
        }
        return refreshed?.takeIf { it.hasEntries || it.causeUnknown }
            ?: (if (c1Left > 0) C1PlateauDiagnosis(c1Left, emptyList()) else null)
    }

    fun applyHF80StrategicOscillation(
        state: MagiState,
        schedule: Array<IntArray>,
        maxCycles: Int = 3,
        seed: Long = System.nanoTime(),
        shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): HF80Result {
        val p = Problem(state, quantitativeRangeEval)
        val ev = Evaluator(p)   // 内側探索用（サイクルごとに作り直さない）
        val rng = Random(seed)
        val before = UnifiedViolationChecker.check(state, schedule, quantitativeRangeEval = quantitativeRangeEval)
        var best = normalizeSchedule(schedule, p)
        val original = best   // [厳密ピン保護] 全サイクル共通の基準盤面（exactPinRegressionの比較元）
        var bestReport = before
        var applied = false
        var usedCycles = 0
        val cycleMax = max(0, maxCycles)
        var cycle = 0
        while (cycle < cycleMax) {
            if (shouldStop()) break
            val cand = best.copy2D()
            val strength = max(1, (p.S * p.T * (0.03 + cycle * 0.02)).toInt())
            var t = 0
            while (t < strength) {
                if (p.S > 0 && p.T > 0) {
                    val i = rng.nextInt(p.S)
                    val j = rng.nextInt(p.T)
                    // [3.311.0] 3.270.0 の wishLocked 統一の取り残し。生の `wish < 0` だと
                    //   **実現不能な希望**（担当できないシフトへの希望）のセルまで摂動対象から
                    //   外れ、そこに座礁した groupViol セルが永久に動かせなくなる。
                    if (!p.wishLocked(i, j)) {
                        val allowed = p.allowedShiftsForStaff(i)
                        if (allowed.isNotEmpty()) cand[i][j] = allowed[rng.nextInt(allowed.size)]
                    }
                }
                t++
            }
            val polished = localBestImprovement(p, ev, cand, 250 + cycle * 120, rng, shouldStop)
            val rep = UnifiedViolationChecker.check(state, polished, quantitativeRangeEval = quantitativeRangeEval)
            usedCycles = cycle + 1
            // [厳密ピン保護/3.522.0] 摂動+再研磨は複数職員の回数を同時に変えうるため、他パス（RangePolish等）
            //   と同じ exactPinRegression ガードを追加。旧実装はこのパスだけ欠けており、SOFT重みの相対関係が
            //   十分ずれると（例: c1/covU等の重み引き上げ）weightedScore改善とのトレードで
            //   staffRange厳密ピン(lo==hi)が崩れうる不具合があった（PinInvariantTest, docs/history/3.4xx.md）。
            if (isBetter(rep, bestReport) && !exactPinRegression(p, original, polished)) {
                best = polished
                bestReport = rep
                applied = true
            }
            cycle++
        }
        val reason = if (applied) "strategic oscillation accepted" else "no improving oscillation"
        val logs = listOf(MirrorLog(tag = "HF80", message = "SO applied=$applied HARD ${before.hard}->${bestReport.hard} score ${before.weightedScore.toLong()}->${bestReport.weightedScore.toLong()} cycles=$usedCycles"))
        return HF80Result(best, before.hard, bestReport.hard, before.weightedScore, bestReport.weightedScore, usedCycles, applied, reason, logs)
    }

    data class CyclicSwapResult(
        val newSchedule: Array<IntArray>,
        val beforeTotal: Int,
        val afterTotal: Int,
        val applied: Int,
        val logs: List<MirrorLog>,
        /**
         * [C1 頭打ちの構造化診断, 3.322.0] `applyC1WindowPolish` だけが設定する。
         * 他パスは null のまま（既定値つき＝既存の構築サイトは非破壊）。
         */
        val plateau: C1PlateauDiagnosis? = null,
        /**
         * [3.323.0] 厳密ピン(lo==hi)を崩すため却下した候補の数。
         * これらは **`isBetter` が採用を認めた**手で、ピンのガードだけが止めている＝
         * 「回数固定を緩めれば通ったはずの手」の実測値（推測ではない）。
         */
        val observedPinBlockedAttempts: Int = 0,
        /** [3.326.0] どのピン(職員,シフト)が何回止めたか。緩和対象の提示に使う。 */
        val pinBlocks: PinBlockAttribution? = null,
        /** [Iteration 2] このパスが単独では不採用にし、結合にも使わなかった候補（違反連結成分修復の材料）。 */
        val rejectedCandidates: List<CombinatorialRepair.Candidate> = emptyList(),
    )

    /**
     * [3.451.0/largeHeap-OOMの根本修正] `UnifiedViolationChecker.check()` は Map/List フィールド7つを
     * 持つ重い `ViolationReport` を毎回新規アロケートする（3.450.0で確認: HF80戦略的振動の呼出元
     * `applyHF80StrategicOscillation` 経由でこの内側ループが1回のパスにつき1,000回超呼び、既定
     * ヒープ256MiBを使い切ってOutOfMemoryErrorを起こした実機ログを確認済み）。
     *
     * 内側の探索は**候補生成の当落判定**であって最終採否ではない——呼出元
     * `applyHF80StrategicOscillation` は本関数が返す `polished` 盤面を必ず
     * `UnifiedViolationChecker.check()` + `isBetter`(=betterReport, 3.287.0の単一ソース) で再評価してから
     * `best`/`bestReport` へ採否するため（外側ゲートは1サイクルにつき1回のみ・cycle≤3回）、内側の当落基準を
     * 変えても**最終的にHF80Resultへ採用される盤面の品質は退化しない**（3.290.0系の「候補生成は近似でよい・
     * 最終採否は必ずchecker+isBetter」という本コードベース全体の確立済み契約と同型）。
     *
     * `V6NativeOptimizer.runV5` が SA(native)の内側探索に `Evaluator.fullEval`（Mapを一切作らない
     * packed Long＝hard×SCORE_HARD_UNIT+soft）を使い、最終結果だけ checker で再検証するのと**同じ
     * 二層構成**をここへ持ち込む。`Evaluator.hard`/`soft` と Checker の `hard`/`weightedScore` の数値一致は
     * `ObjectiveParityTest`(3.337.0) が既に保証済み。唯一の差は「同一hard件数のときの内訳tie-break」
     * （旧: weightedScore=hard族の重み付き寄与を含む / 新: packed比較はhard件数で確定同点ならsoftのみで
     * 決める）で、これは内側探索の経路のみに影響し外側ゲートの正しさには無関係。
     *
     * 実測（HF80単体, 実データ相当の10職員×31日）: 1回のパスあたりの重い ViolationReport アロケートが
     * 最大1,470回超 → 最大4回（`before`1回＋cycle毎の外側`rep`1回×3）まで減少。
     *
     * **A/B実測（教訓#30: revert を scratch へ作り旧実装と突合）**: `runPostOptimization` の決定的ベンチ
     * （固定seed=12345）を3データセット全てで旧(checker毎回)実装と新(Evaluator)実装の両方で実行し、
     * hard/total/weightedScore/c1 が**すべてバイト一致**することを確認（golden 0/420/4258.0/c1 96・
     * sample_v6 9/336/73828.0/c1 4・blocked_covu 4/311/34149.0/c1 52＝いずれも既存の記録済みベースラインと
     * 一致）。tie-break差は理論上の懸念に留まり、この3データセットでは実際の探索経路に一切影響しなかった。
     */
    private fun localBestImprovement(p: Problem, ev: Evaluator, schedule: Array<IntArray>, tries: Int, rng: Random, shouldStop: () -> Boolean = { false }): Array<IntArray> {
        // 1 セルをその場で書き換えて評価し、改善しなければ戻す（盤面のコピーは入口の 1 回だけ）。
        //   同じ値への書き換えは評価しても同点＝不採用なので飛ばす。乱数の消費順は変えない。
        val best = schedule.copy2D()
        var bestScore = ev.fullEval(best)
        var t = 0
        val maxTry = max(0, tries)
        while (t < maxTry) {
            if (shouldStop()) break
            if (p.S > 0 && p.T > 0) {
                val i = rng.nextInt(p.S)
                val j = rng.nextInt(p.T)
                if (!p.wishLocked(i, j)) {
                    val allowed = p.allowedShiftsForStaff(i)
                    if (allowed.isNotEmpty()) {
                        val nw = allowed[rng.nextInt(allowed.size)]
                        val old = best[i][j]
                        if (nw != old) {
                            best[i][j] = nw
                            val score = ev.fullEval(best)
                            if (score < bestScore) bestScore = score else best[i][j] = old
                        }
                    }
                }
            }
            t++
        }
        return best
    }

    // [責務別分割] DayAssignmentPolish.kt からも参照されるため internal 化（写しを作らず単一ソースを共有）。
    internal fun effectiveHi(p: Problem, i: Int, k: Int): Int {
        val hi = p.rangeHi[i][k]
        return if (hi == Int.MAX_VALUE) Int.MAX_VALUE / 4 else hi
    }

    // [3.287.0 keep-best統一] hard→weightedScore→total（単一ソース betterReport へ委譲。MirrorCore.kt 参照）。
    private fun isBetter(a: ViolationReport, b: ViolationReport): Boolean = betterReport(a, b)

}
