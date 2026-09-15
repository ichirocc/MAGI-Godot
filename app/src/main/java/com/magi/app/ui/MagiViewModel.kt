package com.magi.app.ui

import com.magi.app.toHankakuKigou
import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.magi.app.v6.betterReport
import com.magi.app.v6.Problem
import com.magi.app.v6.ScheduleCsvBridge
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.cachedProblem
import com.magi.app.v6.V6PortAnalyzer
import com.magi.app.v6.SettingIssue
import com.magi.app.v6.SettingFixAction
import com.magi.app.v6.IssueKind
import com.magi.app.v6.FixSuggester
import com.magi.app.v6.FixSuggestion
import com.magi.app.v6.V6PortReport
import com.magi.app.v6.CoverageDiagnosis
import com.magi.app.v6.ForbiddenRunDiagnosis
import com.magi.app.v6.C1PlateauDiagnosis
import com.magi.app.v6.PinBlockAttribution
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.V6NativeOptimizer
import com.magi.app.v6.V6SanityPort
import com.magi.app.v6.V6SanityReport
import com.magi.app.v6.Hf63Infeasibility
import com.magi.app.v6.Ws1Ops
import com.magi.app.v6.Ws1Result
import com.magi.app.v6.canDoShiftsForStaff
import com.magi.app.v6.canDo
import com.magi.app.v6.mayPlace
import com.magi.app.v6.copy2D
import com.magi.app.v6.toIntArray2D
import com.magi.app.v6.withSchedule
import com.magi.app.v6.restShiftIndex
import com.magi.app.v6.wishLocked
import com.magi.app.work.OptimizationRepository
import com.magi.app.work.OptimizationWorker
import com.magi.app.work.writeFileAtomically
import com.magi.app.model.Range
import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.model.C2Row
import com.magi.app.model.C3Row
import com.magi.app.model.C41Row
import com.magi.app.model.C42Row
import com.magi.app.model.MagiState
import com.magi.app.model.MojibakeRepair
import com.magi.app.model.StateParser
import com.magi.app.v6.ShiftAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 勤務表最適化のタイムアウト上限（秒）。高精度を保ったまま5分(300s)以内に圧縮。
 * 唯一の真実源はエンジン層の [com.magi.app.v6.MAX_OPTIMIZE_SEC]。UI 側はそれを参照し、
 * UI 設定の上限とエンジンの頭打ちが乖離しないようにする。
 */
const val MAX_BUDGET_SEC = com.magi.app.v6.MAX_OPTIMIZE_SEC

/**
 * [saveNow メインスレッドI/O] onStop/onPause は「プロセスがこの直後に破棄されうる」区間なので、
 * saveNow() の同期書込は**意図的**（Dispatchers.IO へ逃がすと、ディスパッチされたコルーチンが走る前に
 * プロセスが死にうる＝saveNow が存在する動機そのものを壊す。onSaveInstanceState も同じ理由で同期）。
 * データは業務上限（最大30名×31日, CLAUDE.md参照）で小さく通常は数msで終わる想定＝この閾値超過だけを
 * 異常として記録する（起きていないことは静かなまま・起きたら操作ログに残る）。
 */
private const val SAVE_NOW_SLOW_MS = 100L


class MagiViewModel(app: Application) : AndroidViewModel(app) {

    // [分割V3] MagiViewModelIo.kt 等の extension ファイルが参照するため internal 化（var の書き込みは本体のみ＝private set）。
    //   _ui は update も extension 側から呼ぶ（UiState は copy ベースの単方向フローのみ＝書き手の規約は不変）。
    internal val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    internal var originalJson: String? = null
        private set
    // [分割V1] extension ファイル（MagiViewModelConstraints.kt 等）が読むため internal 化。
    //   書き込みは従来どおり本体の入口（loadAsync/applyStructure/mutateConstraints/undo系）だけ＝private set。
    internal var state: MagiState? = null
        private set
    // [分割V2] extension ファイル（MagiViewModelWs1.kt 等）が読むため internal 化。書き込みは従来どおり本体のみ＝private set。
    internal var currentSchedule: Array<IntArray>? = null
        private set
    internal var resultSchedule: Array<IntArray>? = null
        private set
    private var job: Job? = null
    private var checkJob: Job? = null
    private var fixJob: Job? = null   // [競合解消] 改善提案探索。連続タップ時に前探索をキャンセルし古い結果で上書きしない
    private var checkSeq = 0L
    /** [3.392.0] 直し方の探索も seq で世代管理する（cancel() が非同期なため。詳細は findFixSuggestions）。 */
    private var fixSeq = 0L

    /**
     * [3.328.0/外部レビュー・最重要] **長い最適化が動いているか**。
     *
     * `UiState.running` は「短い違反チェック」と「数十〜300秒の最適化」を1つの旗で兼ねていた。
     * `refreshCheck()` は完了時に必ず `running = false` を立て、`checkJob?.cancel()` は checkJob しか
     * 止めないので、**最適化の最中に設定を編集すると、その編集が起こす違反チェックの完了で
     * 実行中フラグが落ち、以降 `!ui.running` を見ている全てのガード（セル編集=3.161.0・一括シート=
     * 3.127.0・回数の緩和=3.326.0）が素通りになる**。旗を分けて、検査の完了では最適化の実行中表示を
     * 解除しないようにする。
     */
    /**
     * [3.404.0] いま**「完了時に勤務表と設定を丸ごと差し替える前景ジョブ」**が走っているか＝その名前
     * （走っていなければ null）。旧名 `optimizeActive` は「最適化」としか読めず、**同じ性質を持つ
     * 読み込み・CSV取込・初期解生成の3つが旗を立て忘れていた**（この名前そのものが取り残しの原因）。
     * その3つは `running = true`（画面は全部ロック）にしながら `optimizeInFlight()` は false のままで、
     * ガード側だけが全開という**逆転**が起きていた——たとえば初期解生成の最中にセルを編集すると
     * `setCell` のガードを素通りして盤面へ書き込まれ、完了時の `currentSchedule = res.schedule.copy2D()`
     * が**それを無言で上書きする**（3.161.0 が最適化について塞いだ穴の、他の3ジョブぶんの取り残し）。
     * 名前で「最適化に限らない」と分かるようにし、画面のメッセージもこの名前で言い分ける。
     */
    @Volatile private var boardJobLabel: String? = null

    /**
     * 旗の持ち主を識別する通し番号。`finally` で**自分が立てた旗のときだけ**下ろす
     * （`checkSeq`/`fixSeq` と同じ手＝後から始まったジョブの旗を、先に終わった側が下ろして
     * ロックを早く解いてしまう事故を防ぐ。3.333.0 の `releasedByMe` と同趣旨）。
     */
    private var boardJobToken = 0

    /**
     * [3.408.0] エンジン実行の通し番号。操作ログ（履歴）と診断ログ（直近1回）を突き合わせるための唯一の鍵。
     * `activeRunSerial` は「いま実行中の番号」（0＝実行外）で、`logOp` がこれを各行へ刻む。
     */
    private var runSerial = 0

    @Volatile private var activeRunSerial = 0

    private fun beginBoardJob(label: String, engineRun: Boolean = false): Int {
        boardJobLabel = label
        if (engineRun) {
            runSerial++
            activeRunSerial = runSerial
        }
        return ++boardJobToken
    }

    private fun endBoardJob(token: Int) {
        if (token == boardJobToken) {
            boardJobLabel = null
            activeRunSerial = 0
        }
    }

    /** 画面のメッセージで「何の実行中か」を言うための名前。背景 Worker には名前が無いので既定を返す。 */
    internal fun busyWhat(): String = boardJobLabel ?: "バックグラウンド最適化"

    /**
     * [3.328.0 → 3.336.0/外部レビュー P1] **編集・実行の可否はここだけを見る**。`ui.running` は
     * 画面へ出すための写しで、初期化時の WorkManager 問い合わせが失敗すれば false のまま残る
     * （＝背景で走っているのにガードが全部開く）。3.336.0 で早期 return するガード14箇所を
     * こちらへ寄せ、`ui.running` は表示専用へ降格した。
     * [3.404.0] 対象は最適化に限らない（[boardJobLabel] 参照）＝関数名は据え置くが意味は
     * 「盤面を丸ごと差し替えるジョブが走っている」。
     */
    internal fun optimizeInFlight(): Boolean =
        boardJobLabel != null || OptimizationRepository.running.value

    // ===== [v2.22] 自動保存・復元（端末内）と「元に戻す」 =====
    private val autosaveFile get() = getApplication<Application>().filesDir.resolve("magi_autosave.json")
    // [判断設計監査 #3] 「データを開く」直前の状態を1世代だけ退避（開く=取消不能な置換だった穴を塞ぐ）。
    private val prevBackupFile get() = getApplication<Application>().filesDir.resolve("magi_prev_before_open.json")
    private var hydrated = false           // 復元完了前の自動保存を抑止（Web HF514 と同思想）
    private var saveJob: Job? = null
    // [3.485.0] 保存の世代（main で採番）と、古い世代の書き込みを捨てるゲート（SaveGate の KDoc 参照）。
    private var saveGen = 0
    private val saveGate = com.magi.app.work.SaveGate()
    // [3.529.0/外部仕様書取り入れ] label は「この snap の後に行われた操作」の名前＝undo() が拾って
    //   「元に戻す: <label>」に使う。redo() へ渡すときも同じ label を引き継ぐ（同じ操作を指すため）。
    private data class UndoSnap(val st: MagiState, val sched: Array<IntArray>, val label: String? = null)
    private val undoStack = ArrayDeque<UndoSnap>()
    private val redoStack = ArrayDeque<UndoSnap>()   // [Web反映] undo で退避→redo で復元（手動修正ループ）
    private fun snapNow(label: String? = null): UndoSnap? {
        val st = state ?: return null; val sc = currentSchedule ?: return null
        return UndoSnap(st, Array(sc.size) { sc[it].clone() }, label)
    }

    // ===== プロセス強制終了の耐性: 実行中マーカー（中断検知 / 仕様書 §3.4 補完） =====
    // 実行開始時にマーカーを書き、正常終了で消す。プロセスがkillされるとマーカーが残るので、
    // 次回起動時に「前回の計算は中断された（入力は自動保存済み）」と気づかせ、再実行へ導く。
    private val runMarkerFile get() = getApplication<Application>().filesDir.resolve("magi_run_marker.json")
    private fun writeRunMarker(mode: String) {
        runCatching {
            val o = org.json.JSONObject()
            o.put("startedAt", System.currentTimeMillis())
            o.put("mode", mode) // "fg" | "bg"
            o.put("budgetSec", _ui.value.budgetSec)
            o.put("workers", _ui.value.workers)
            o.put("algorithm", _ui.value.v6Algorithm.name)
            runMarkerFile.writeText(o.toString())
        }
    }
    private fun clearRunMarker() { runCatching { if (runMarkerFile.exists()) runMarkerFile.delete() } }
    /**
     * [3.428.0/#14] 背景実行の共有ファイルを消し、**消し残った名前を必ず記録する**。
     *
     * 3.410.0/B-06 で `RunFiles.clear` が消し残りを返すようにしたのに、その返り値を読んでいたのは
     * 「背景計算の開始直前」の1箇所だけで、**残り9箇所は捨てていた**（自分で書いた契約の取り残し）。
     * 消し残ると次回起動が入力・途中最良・マーカーを掴んで「中断されました・再開できます」と
     * **失敗や停止を中断として誤案内**するのに、痕跡がどこにも残らない。消せないこと自体はここでは
     * 直せないので、せめて後から読めるようにする。
     *
     * @param where どの経路の掃除か（ログを読むときに原因を切り分けるため）。
     */
    private fun clearBgFiles(where: String, keepRunId: Boolean = false) {
        val stuck = runCatching {
            OptimizationWorker.clearFiles(getApplication<Application>(), keepRunId)
        }.getOrElse { e ->
            logOp("W", "$where: 途中状態ファイルの削除に失敗しました（${e.javaClass.simpleName}）")
            return
        }
        if (stuck.isNotEmpty()) {
            logOp("W", "$where: 途中状態ファイルを削除できませんでした: ${stuck.joinToString("・")}" +
                "（次回起動が古い状態を「中断」として掴む可能性があります）")
        }
    }

    fun dismissInterrupted() {
        _ui.update { it.copy(interruptedRun = false, interruptedInfo = null) }
        clearBgFiles("中断の破棄")   // [C1] 破棄で途中状態ファイルを削除
    }

    // ===== 操作ログ（監査）: 追記式・新しい順・時刻/レベル付き =====
    /**
     * [3.408.0] `run` = そのとき走っていたエンジン実行の通し番号（0＝実行外）。
     *
     * 操作ログは**複数回の実行にまたがる履歴**なのに、診断ログは**直近1回ぶん**しか無い。書き出しでは
     * この2つが同じファイルに連結されるため、実行#1 の「グローバル最良更新」と実行#2 の「全体最良更新=0回」が
     * **同一実行の自己矛盾**として読めてしまう（実機ログ 2026-08-19 16:09/16:14 の2回実行で実際に起きた。
     * `globalImproves` 自体は正しい＝ホストJVMで「メッセージ3回＝サマリ3回」を実測して確認済み）。
     * 番号を持たせて「どの行がどの実行のものか」を機械的に分けられるようにする。
     */
    data class OpLogEntry(val timeMs: Long, val level: String, val message: String, val run: Int = 0)
    internal val opLog = ArrayDeque<OpLogEntry>()
    // 診断ログの「非圧縮・全文」を保持（画面表示は compressDiagLogs で圧縮、出力はこちらの全文を使う）。
    internal var rawDiagLogs: List<String> = emptyList()
        private set
    /**
     * [3.379.0/実機ログ起因] **最後に実行したエンジンの診断ログ**（ラベル・時刻つき）。
     *
     * `rawDiagLogs` は `pushReport` のたびに丸ごと差し替わる。ところが `refreshCheck` も pushReport を通るので、
     * **最適化のあとにセルや希望を1つ触るだけで TIME/TimeBudget/スコア収支/Watchdog/残存分析/AdaptivePortfolio/
     * POST が全部消える**（実機ログ 3.378.0: 14:04 に最適化 → 14:16-14:17 に希望を編集 → 20:50 に書き出し、で
     * 診断67件が違反チェックの分だけ＝最適化の診断がゼロ）。「作る → 見る → 直す」という実際の使い方では
     * ほぼ必ずこうなるので、書き出したログで最適化を追えないという致命的な穴だった。
     * エンジン実行のときだけここへ退避し、書き出しでは現在の診断と**別セクション**で併記する。
     */
    /** [3.408.0] `rawDiagLogs` を作った実行の通し番号（0＝実行外の違反チェック等）。書き出しの帰属に使う。 */
    internal var lastDiagSerial = 0
        private set

    /** [3.408.0] `lastRunDiagLogs` を作ったエンジン実行の通し番号。 */
    internal var lastRunDiagSerial = 0
        private set

    internal var lastRunDiagLogs: List<String> = emptyList()
        private set
    internal var lastRunDiagLabel: String = ""
        private set
    internal var lastRunDiagAtMs: Long = 0L
        private set
    private val opLogFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)

    /**
     * 操作ログに1件追記し、UIへ反映（新しい順、リングの上限は下の `while` のとおり **1000件**）。
     * [3.378.0/HF77=コメント≠実装] 旧 KDoc は「最大300件」と書いていたが実装は 1000。
     * 「自分の行がリングから押し出されたのか」を判断する材料なので実装値へ訂正する。
     */
    internal fun logOp(level: String, message: String) {
        opLog.addFirst(OpLogEntry(System.currentTimeMillis(), level, message, activeRunSerial))
        while (opLog.size > 1000) opLog.removeLast()
        _ui.update { it.copy(opLog = opLog.map { formatOpLine(it) }) }
    }

    /** [3.408.0] 実行中の行だけ `#N` を付ける（実行外＝0 は従来どおり無印）。 */
    private fun formatOpLine(e: OpLogEntry): String {
        val run = if (e.run > 0) "#${e.run} " else ""
        return "${opLogFmt.format(java.util.Date(e.timeMs))} [${e.level}] $run${e.message}"
    }

    // 操作再現用デコード（現stateを参照。staff/shift一覧は操作中に不変）。
    internal fun opNm(i: Int): String = state?.staff?.getOrNull(i)?.name ?: "#$i"
    internal fun opSy(k: Int): String = state?.shifts?.getOrNull(k)?.kigou?.let { toHankakuKigou(it) } ?: "#$k"
    internal fun opDays(days: List<Int>): String = if (days.size <= 10) days.joinToString(",") { "${it + 1}日" } else "${days.size}日分"

    init {
        // 起動時: 前回の自動保存があれば復元（無ければ何もしない）
        viewModelScope.launch {
            // [並行I/O] 独立した3つのファイル読み込み（自動保存・中断マーカー・完了結果）は互いに依存しない
            //   ＝async で待ち時間を重ね合わせ起動レイテンシを縮める（snapTxt/bgActive は resultTxt に依存
            //   するため後段で逐次に読む）。marker=中断検知（loadAsync より先にフラグを立て base 経由で保持）、
            //   resultTxt=[C1] bg最適化の完了結果（UI不在でも完走済み＝最優先で採用）。
            val (txt, marker, resultTxt) = coroutineScope {
                val a = async(Dispatchers.IO) { runCatching { autosaveFile.takeIf { it.exists() }?.readText() }.getOrNull() }
                val b = async(Dispatchers.IO) { runCatching { runMarkerFile.takeIf { it.exists() }?.readText() }.getOrNull() }
                val c = async(Dispatchers.IO) { runCatching { OptimizationWorker.resultFile(getApplication<Application>()).takeIf { it.exists() }?.readText() }.getOrNull() }
                Triple(a.await(), b.await(), c.await())
            }
            // [判断設計監査 #3] 前回「データを開く」時の退避があれば復元導線（設定タブ）を有効化。
            val hasPrev = withContext(Dispatchers.IO) { runCatching { prevBackupFile.exists() }.getOrDefault(false) }
            if (hasPrev) _ui.update { it.copy(prevBackupAvailable = true) }
            // [3.406.0/B-02] **読めることを確かめてから**共有ファイルを消す。旧: 解析前に clearFiles して
            //   いたため、完了結果が壊れていると復元に使えたはずの入力・途中最良・マーカーまで同時に失い、
            //   利用者は何も取り戻せなかった。解析できなければ結果だけ捨てて、下の中断/途中結果の経路へ落とす。
            // [3.491.0] 解析できるだけでなく**読込の検証（validate）も通る**ことを確かめる。旧: parse 成功
            //   だけで共有ファイルを消していたため、3.488.0 で validate が厳格化（startDate 不正の拒否）
            //   されて以降、「読めるが validate で落ちる」結果を消してから loadAsync が失敗し、
            //   入力・途中最良まで失う経路が復活していた（3.406.0/B-02 の再発）。
            val resultUsable = !resultTxt.isNullOrBlank() &&
                withContext(Dispatchers.Default) {
                    runCatching { validate(Ws1Ops.normalizeEndDate(StateParser.parse(resultTxt))) == null }.getOrDefault(false)
                }
            if (!resultTxt.isNullOrBlank() && !resultUsable) {
                logOp("W", "前回のバックグラウンド最適化の完了結果が壊れていて読めませんでした（入力と途中結果は残してあります）")
                runCatching { OptimizationWorker.resultFile(getApplication()).delete() }
            }
            if (resultUsable) {
                clearRunMarker()
                clearBgFiles("前回の完了結果を反映")
                if (state == null) loadAsync(resultTxt, markResult = true, fromRestore = true)   // initialAssignment が state.schedule を返すため結果が復元される
                logOp("I", "前回のバックグラウンド最適化の結果を反映しました")
            } else {
                // [#4/C1] 中断時、途中最良解のスナップショットがあれば「途中結果から再開」する。
                val snapTxt = withContext(Dispatchers.IO) {
                    runCatching { OptimizationWorker.snapshotFile(getApplication<Application>()).takeIf { it.exists() }?.readText() }.getOrNull()
                }
                // [監査A4] WorkManagerが実行継続中なら「中断」でなく継続中と案内（marker/snapshotは温存し、
                //   結果到着(applyBgResult)側で回収）。従来は再起動時に誤って中断扱い＋marker消去していた。
                val bgActive = withContext(Dispatchers.IO) {
                    runCatching {
                        androidx.work.WorkManager.getInstance(getApplication())
                            .getWorkInfosForUniqueWork(OptimizationWorker.UNIQUE).get()
                            .any { !it.state.isFinished }
                    }.getOrDefault(false)
                }
                if (bgActive) {
                    _ui.update { it.copy(messageIsError = false, running = true, message = "バックグラウンド最適化を継続中…（完了時に自動反映）") }
                    if (state == null && !txt.isNullOrBlank()) loadAsync(txt, fromRestore = true)
                    logOp("I", "バックグラウンド最適化の継続を検知（進捗を購読）")
                } else {
                if (marker != null) {
                    val hasSnap = !snapTxt.isNullOrBlank()
                    val info = if (hasSnap)
                        "前回の最適化は中断されましたが、途中までの最良の勤務表から再開できます。『もう一度実行』で仕上げられます。"
                    else runCatching {
                        val o = org.json.JSONObject(marker)
                        val modeJp = if (o.optString("mode") == "bg") "バックグラウンド" else ""
                        "前回の${modeJp}最適化は完了前に中断されました。入力は自動保存済みです。もう一度実行できます。"
                    }.getOrNull() ?: "前回の最適化は完了前に中断されました。入力は自動保存済みです。"
                    _ui.update { it.copy(interruptedRun = true, interruptedInfo = info) }
                    clearRunMarker()
                    logOp("W", if (hasSnap) "前回の中断を検知（途中結果あり＝再開可）" else "前回の最適化の中断を検知しました（入力は復元済み）")
                }
                if (state == null) {
                    // 途中最良解を優先して復元（無ければ自動保存の入力）。
                    val resumeTxt = snapTxt?.takeIf { it.isNotBlank() } ?: txt
                    if (!resumeTxt.isNullOrBlank()) loadAsync(resumeTxt, fromRestore = true)
                    if (!snapTxt.isNullOrBlank()) clearBgFiles("途中結果の復元後")   // 消費後は掃除
                }
                }
            }
            hydrated = true
        }
        // バックグラウンド最適化（WorkManager）の進捗・結果を購読して画面へ反映（仕様書 §6.3）
        viewModelScope.launch {
            OptimizationRepository.progress.collect { p ->
                if (p != null && _ui.value.running) {
                    // [3.400.0] 旧: `message = "バックグラウンド ${p.phase}"`。前景側（runV6FullOptimize の
                    //   進捗コールバック、同型の [3.400.0] コメント参照）と同じ理由で外す＝
                    //   3.399.0 で message が Snackbar になったため、背景実行中もフェーズが変わるたびに
                    //   Snackbar が出続けてしまう。実行中であることは上部バッジと進捗行が示す（状態）。
                    _ui.update { it.copy(
                        bestHard = p.hard.toLong(), bestSoft = p.soft.toLong(),
                        totalViolations = p.total, elapsedMs = p.elapsedMs,
                    ) }
                }
            }
        }
        viewModelScope.launch {
            OptimizationRepository.result.collect { r -> if (r != null) applyBgResult(r) }
        }
        // [3.385.0/外部レビュー High3] Worker が握り潰していた耐久保証（kill 耐性）の失敗を操作ログへ。
        //   旧: 入力・途中最良・完了結果の書き込みが全て runCatching で無言＝失敗しても書き出したログに
        //   1行も残らず、「5分回した実行が消えた」理由を後から追えなかった。
        viewModelScope.launch {
            OptimizationRepository.notes.collect { (level, msg) -> logOp(level, msg) }
        }
        // [3.409.13/レビュー#7] `ui.running`（表示の写し）が背景実行で stale-false になる経路を**源で**塞ぐ。
        //   3.336.0 は「init 時の WorkManager 問い合わせが失敗すると背景で走っているのに写しが false のまま」
        //   としてガード14箇所を optimizeInFlight() へ寄せたが、3.409.12 で足した `enabled = !ui.running` は
        //   写しを読む＝その stale 経路ではボタンが生きたまま「入力し終えてから拒否」が再発する。
        //   Worker は開始時に必ず OptimizationRepository.setRunning(true) を流すので、その StateFlow を
        //   写しへ反映すればプロセスが生きている限り写しは正になる（問い合わせの成否に依存しない）。
        //   下げる側は **true を見たあとの遷移だけ**＝購読開始時の初期値 false が、init 復元の
        //   「バックグラウンド計算を継続中…」（WorkManager 問い合わせ由来の running=true）を踏み消さないため。
        //   下げるときも前景ジョブ（boardJobLabel）や違反チェック（checkJob）が生きていれば触らない。
        viewModelScope.launch {
            var sawBgRunning = false
            OptimizationRepository.running.collect { bg ->
                if (bg) {
                    sawBgRunning = true
                    _ui.update { it.copy(running = true) }
                } else if (sawBgRunning) {
                    sawBgRunning = false
                    if (boardJobLabel == null && checkJob?.isActive != true) {
                        _ui.update { it.copy(running = false) }
                    }
                }
            }
        }
    }

    /** バックグラウンド（WorkManager / Expedited）で最適化を開始。完了時に通知＋画面反映。 */
    fun runInBackground() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (runBlockedByInFlight("バックグラウンド最適化の開始")) return
        if (!ensureValidForRun(st0, sched0)) return
        pushUndo("バックグラウンド最適化")
        OptimizationRepository.clear()
        // [3.327.0/外部レビュー High3] この実行の識別子を先に確定する。ファイル名は固定なので、これが無いと
        //   置き換えられた旧実行が新実行の入力を消したり、別データの結果を書き残したりできてしまう。
        //   ミリ秒だと同一ミリ秒での二重 enqueue が衝突しうるので、下位に乱数を混ぜて一意にする。
        val runId = System.currentTimeMillis() * 1000L + (0..999).random()
        // [3.410.0/U-02] **順序を入れ替えた**。旧: `clearFiles()` → runId 生成 → `beginRun()` で、
        //   掃除と所有権の確立のあいだ `activeRunId()` が 0 に落ちる窓があった。そこで `beginRun` が
        //   失敗すると（容量不足など）、**旧実行の復元手段を消しただけで新しい実行も始まらない**。
        //   先に所有権を立てれば、まだ走っている旧実行はその時点で `owns()` が偽になって書き込みを止め、
        //   掃除は「自分が所有者になったあと」に行える＝どちらの実行の復元手段も宙に浮かない。
        // [3.406.0/B-01] マーカーと入力を**両方保存できたときだけ**投入する。旧: どちらも握り潰しで、
        //   書けなくても Work を投入していた。マーカーが無いと Worker は所有権なしと判定して何もせず、
        //   画面だけ「開始しました」＝実行中が永久に残る無言の失敗になる（容量不足・I/O 失敗で再現）。
        val markerOk = OptimizationWorker.beginRun(getApplication(), runId)
        // 所有権を確立してから旧途中状態を掃除する（Worker が開始時に再保存する）。
        //   [3.410.0/B-06] 消し残りは黙って捨てず記録する（残ると次回起動が古い状態を掴む）。
        if (markerOk) {
            clearBgFiles("背景最適化の開始（旧途中状態の掃除）", keepRunId = true)
        }
        // [外部レビュー P1-01] 旧: 素の `writeText`＝非原子。書き込み途中でプロセスが kill されると
        //   `magi_bg_input.json` が壊れた JSON のまま残り、旧ファイルは開始前に既に消してあるため
        //   復元の当てが無い（Worker 自身は doWork() 冒頭で同じファイルを原子的に書き直す＝
        //   OptimizationWorker.kt:118-119 の「入力退避」だが、それが走る**前**に落ちれば意味が無い）。
        //   ここも同じ原子書き込みへ揃える。
        val inputOk = markerOk && runCatching {
            OptimizationWorker.files(getApplication()).writeAtomically(
                OptimizationWorker.inputFile(getApplication()), StateParser.serialize(st0, sched0),
            )
        }.getOrDefault(false)
        if (!markerOk || !inputOk) {
            clearBgFiles("背景最適化の開始に失敗")
            notify("バックグラウンド最適化を開始できませんでした（端末の空き容量をご確認ください）", "W")
            return
        }
        // [3.328.0] この結果を後で当ててよいかを判断するための入力の指紋。
        bgStateKey = stateKey(st0)
        bgRunId = runId
        // [3.475.0/論理監査] keep-best の比較先は「この実行に渡した入力」。旧: resultSchedule（前回の結果）と
        //   比較していたため、前回結果のあとに手編集した盤面で背景実行すると、入力より悪化さえしていない
        //   その編集が「前回の結果を維持」の名目で黙って巻き戻された（前景 runV6FullOptimize は元から入力比較）。
        bgInput = sched0.copy2D()
        OptimizationRepository.request = st0 to sched0.copy2D()
        OptimizationRepository.seconds = _ui.value.budgetSec
        OptimizationRepository.workers = _ui.value.workers
        val work = androidx.work.OneTimeWorkRequestBuilder<OptimizationWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            // [P2修正/レビュー指摘] 予算秒数・並列数を WorkManager の inputData に永続化。プロセス再起動後の
            //   再実行でも開始時の条件（例: 300秒/8並列）が保たれる（旧: インメモリのみで既定60秒/4並列に化けた）。
            .setInputData(androidx.work.workDataOf(
                OptimizationWorker.KEY_SECONDS to _ui.value.budgetSec,
                OptimizationWorker.KEY_WORKERS to _ui.value.workers,
                OptimizationWorker.KEY_RUN_ID to runId,
            ))
            .build()
        // [外部レビュー P1-02] 旧: enqueue が例外を投げると、直前に立てたマーカー・入力ファイル・
        //   所有権が残ったまま関数を抜けていた（次回起動が「中断されました」と誤案内しうる）。
        //   保存の成否と同じ扱いへ揃える＝失敗したら所有権を確認したうえで片付け、開始失敗を通知する。
        //   enqueue が成功したときだけ「開始しました」を表示する。
        val enqueued = runCatching {
            androidx.work.WorkManager.getInstance(getApplication())
                .enqueueUniqueWork(OptimizationWorker.UNIQUE, androidx.work.ExistingWorkPolicy.REPLACE, work)
        }.isSuccess
        if (!enqueued) {
            OptimizationRepository.request = null
            bgStateKey = 0L
            bgRunId = 0L
            clearBgFiles("バックグラウンド最適化の投入に失敗")
            notify("バックグラウンド最適化を開始できませんでした（端末の状態をご確認ください）", "W")
            return
        }
        _ui.update { it.copy(messageIsError = false, running = true, hasResult = false, interruptedRun = false, interruptedInfo = null, message = "バックグラウンドで最適化を開始しました（完了時に通知）") }
        writeRunMarker("bg")
        logOp("I", "バックグラウンド最適化 開始 (予算${_ui.value.budgetSec}s, 並列${_ui.value.workers})")
    }

    /** [3.475.0] 破棄する背景結果の後始末を一箇所に。旧: 「入力が変わった」分岐だけこれを一切せず、
     *  結果ファイルが残って次回起動時に無関係なデータへ復元されうる穴があった。 */
    private fun discardBgResult(reason: String) {
        clearRunMarker()
        clearBgFiles(reason)
        OptimizationRepository.request = null
        OptimizationRepository.publishResult(null)
    }

    private suspend fun applyBgResult(r: OptimizationRepository.BgResult) {
        val st0 = state ?: return
        // [3.328.0/外部レビュー] 背景の結果は「開始時の入力」に対して計算されたもの。実行中に別のデータを
        //   開く・取り込むなどで入力が変わっていたら、その結果は今の入力の答えではないので捨てる
        //   （旧: 現在の state へ無条件に当てていた）。指紋が未記録(0)の経路＝プロセス再起動後の
        //   ファイル復元は、結果ファイルが state ごと持つので自己整合＝従来どおり通す。
        // [3.410.0/U-01] 実行の識別子で先に弾く。入力の指紋は**入力が同じなら別の実行でも一致する**ので、
        //   置き換えられた古い実行が完了間際に publish した結果を通してしまう。r.runId==0 は識別子を
        //   持たない経路（プロセス再起動後のファイル復元）＝従来どおり通す。
        if (bgRunId != 0L && r.runId != 0L && r.runId != bgRunId) {
            logOp("W", "バックグラウンド最適化の結果を破棄しました（置き換えられた古い実行の結果）")
            return
        }
        // [3.475.0/論理監査] `bgStateKey`（インメモリ）はプロセス再起動で 0 に戻り、以後は runId/指紋の両方が
        //   照合されないまま**いま開いている別データへ**結果が当たり得た。Worker が入力から詰めた
        //   `r.stateKey`（0=未対応の旧経路）があれば、bgStateKey が 0 でも常にこれと照合する。
        val mismatch = (bgStateKey != 0L && bgStateKey != stateKey(st0)) ||
            (bgStateKey == 0L && r.stateKey != 0L && r.stateKey != stateKey(st0))
        if (mismatch) {
            bgStateKey = 0L; bgRunId = 0L; bgInput = null
            logOp("W", "バックグラウンド最適化の結果を破棄しました（最適化中に設定またはデータが変わったため）")
            _ui.update { it.copy(messageIsError = false, running = false, message = "最適化中に設定が変わったため、結果は反映しませんでした。もう一度つくってください。") }
            // [3.475.0] 旧: この分岐だけファイル/公開結果を片付けず、次回起動が古い結果を復元しうる穴だった。
            discardBgResult("背景結果: 入力が変わったため破棄")
            return
        }
        bgStateKey = 0L
        bgRunId = 0L
        // [3.475.0/論理監査] keep-best の比較先は「この実行の入力」（bgInput）。旧: 前回の結果
        //   (resultSchedule) と比較していたため、前回結果のあとに手編集した盤面で背景実行すると、
        //   入力より悪化していないその編集が「前回の結果を維持」の名目で黙って巻き戻された。
        //   bgInput が無い（プロセス再起動後の復元経路）ときだけ、従来どおり前回の結果と比較する。
        val prev = bgInput ?: resultSchedule
        bgInput = null
        if (prev != null) {
            val prevReport = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(st0, prev) }
            val newHard = r.report.hard.toLong(); val newTotal = r.report.total
            // [3.287.0 keep-best統一 → 3.289.0 で単一ソースへ委譲] 手書きの3節複製をやめ betterReport
            //   （hard→weightedScore→total）に一本化。将来の順序変更でここだけ取り残される事故を防ぐ。
            val worse = betterReport(prevReport, r.report)
            if (worse) {
                val kept = prev.copy2D()
                currentSchedule = kept
                resultSchedule = kept
                state = st0.withSchedule(kept)
                autoSave()
                // [3.475.0/論理監査] runLabel を付ける。旧: 背景実行の診断は「実行外」に分類され、
                //   1回でも手編集すると rawDiagLogs が上書きされて書き出しログから消えていた。
                pushReport(state ?: st0, kept, prevReport, runLabel = "バックグラウンド最適化") { it.copy(
                    messageIsError = false,
                    running = false, hasResult = true, engineRan = true,
                    message = "今回(必須$newHard/合計$newTotal)は前回(必須${prevReport.hard}/合計${prevReport.total})より改善せず。前回の結果を維持しました。",
                ) }
                logOp("I", "バックグラウンド: 今回 必須$newHard/合計$newTotal は前回 以下に改善せず → 前回を維持")
                discardBgResult("背景結果: 前回を維持")
                return
            }
        }
        val sched = r.schedule.copy2D()
        currentSchedule = sched
        resultSchedule = sched
        state = st0.withSchedule(sched)
        autoSave()
        pushReport(state ?: st0, sched, r.report, runLabel = "バックグラウンド最適化") { it.copy(
            messageIsError = false,
            running = false, hasResult = true, engineRan = true,
            runSummary = prev?.let { runSummaryOf(com.magi.app.v6.ChangeSummary.of(st0, it, sched, r.report)) },
            message = "バックグラウンド最適化 完了: 必須=${r.report.hard} 合計=${r.report.total}",
        ) }
        logOp("I", "バックグラウンド最適化 完了 必須=${r.report.hard} 合計=${r.report.total}")
        lastResultHard = r.report.hard.toLong()
        clearRunMarker()
        clearBgFiles("背景最適化 完了")   // [C1] 完了で途中状態ファイルを削除
        // 消費したらクリア（再生成時の二重適用を防ぐ）
        OptimizationRepository.request = null
        OptimizationRepository.publishResult(null)
    }

    /** 1.2秒デバウンスで状態をアプリ専用領域に保存。失敗は黙殺（次回操作で再試行）。 */
    /**
     * [3.410.0/U-03・B-03] 自動保存を**原子書き込み**にし、失敗を**一度だけ**知らせる。
     *
     * 旧: 素の `writeText` を `runCatching` で握り潰していた。実害2つ——
     * ①書き込み中にプロセスが落ちると**壊れた JSON が自動保存に残る**（起動時にそれを読む＝
     *   編集が丸ごと失われるうえ理由も分からない。3.336.0 が bg 結果に対して直したのと同じ形）
     * ②容量不足などで保存できていないのに画面もログも無反応＝利用者は保存されたと信じて編集を続ける。
     * 1.2秒ごとに走るので毎回は出さず、**成功→失敗へ変わった瞬間**だけ出す。
     */
    private fun autoSave() {
        if (!hydrated) return
        _ui.update { it.copy(saveState = SaveState.Dirty) }   // [3.529.0] SaveState の KDoc 参照
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1200)
            val gen = ++saveGen   // main で採番＝exportJson の時点の状態順
            val json = exportJson() ?: return@launch
            _ui.update { it.copy(saveState = SaveState.Saving) }
            val ok = withContext(Dispatchers.IO) {
                saveGate.writeIfLatest(gen) {
                    runCatching {
                        com.magi.app.work.writeFileAtomically(autosaveFile, json, onNonAtomic = { nonAtomicSaveSeen = true })
                    }.getOrDefault(false)
                }
            } ?: return@launch   // より新しい世代が先に書かれた＝この世代は捨てる（通知しない・状態も上書きしない）
            _ui.update { it.copy(saveState = if (ok) SaveState.Saved else SaveState.Failed) }
            reportNonAtomicSave()   // [3.428.0/#7] 記録は **main へ戻ってから**（下の KDoc 参照）
            reportAutoSave(ok)
        }
    }

    /** 直前の自動保存が成功したか。失敗を連続で通知しないための状態。 */
    private var autoSaveOk = true

    /**
     * [3.428.0/#7] 原子置換を諦めた（rename 不能）ことを書き込み側から受け取る旗。
     * **`Dispatchers.IO` から立つ**ので `@Volatile`。ここでは旗を立てるだけにして、記録は
     * [reportNonAtomicSave] が main へ戻ってから行う——`logOp` は `opLog`(ArrayDeque) を変更し
     * `_ui.update` を呼ぶので、背景スレッドから呼ぶと 3.176.0 で決めた
     * 「共有可変と `_ui` への書き込みはメインスレッドの単一ライタに限る」を破る。
     */
    @Volatile private var nonAtomicSaveSeen = false

    /** 一度だけ記録したか（1.2秒ごとに走るので毎回は出さない）。main からのみ触る。 */
    private var nonAtomicSaveLogged = false

    /**
     * rename が使えず**原子性を諦めて直接書いた**ことを記録する。書き込みは成功しうるので失敗としては
     * 扱わないが、この経路で書いている最中にプロセスが落ちると**壊れた自動保存が残る**（原子置換を
     * 入れた動機そのもの）。旧: 黙ってフォールバックしており、後から読める痕跡が無かった。
     */
    private fun reportNonAtomicSave() {
        if (nonAtomicSaveLogged || !nonAtomicSaveSeen) return
        nonAtomicSaveLogged = true
        logOp("W", "自動保存で原子置換（一時ファイルの差し替え）が使えず直接書き込みました" +
            "（書き込み中にアプリが強制終了すると自動保存が壊れる可能性があります）")
    }

    private fun reportAutoSave(ok: Boolean) {
        if (ok == autoSaveOk) return
        autoSaveOk = ok
        if (ok) logOp("I", "自動保存が復旧しました")
        else notify("自動保存に失敗しています（端末の空き容量をご確認ください）。「データを保存」で書き出してください", "W")
    }

    /**
     * 即時保存（デバウンスなし・同期書込）。バックグラウンド遷移(onStop/onPause)から呼び、
     * 保留中の編集を確実に永続化する。autoSave の1200msデバウンス中に
     * プロセスが破棄されても編集が失われないようにするための保険。
     */
    fun saveNow() {
        if (!hydrated) return
        saveJob?.cancel()
        val t0 = System.nanoTime()
        val gen = ++saveGen
        val json = exportJson() ?: return
        _ui.update { it.copy(saveState = SaveState.Saving) }
        // [3.410.0/U-03] 即時保存も同じ扱い（原子書き込み＋失敗の通知）。
        // saveNow は同期（main）なので旗を立てたその場で記録して構わない。
        // [3.485.0] 走行中の自動保存とはゲートのロックで直列化（同期呼出しの世代は常に最新＝捨てられない）。
        val ok = saveGate.writeIfLatest(gen) {
            runCatching {
                com.magi.app.work.writeFileAtomically(autosaveFile, json, onNonAtomic = { nonAtomicSaveSeen = true })
            }.getOrDefault(false)
        } ?: true
        _ui.update { it.copy(saveState = if (ok) SaveState.Saved else SaveState.Failed) }
        reportNonAtomicSave()
        reportAutoSave(ok)
        // [賢い修正・saveNowメインスレッドI/O] 意図的な同期I/O（上記 SAVE_NOW_SLOW_MS の KDoc参照）を
        // 前提のまま残すが、想定外に長く main を塞いだ回だけ観測できるようにする（表示・エンジンは不変）。
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms >= SAVE_NOW_SLOW_MS) logOp("W", "即時保存に${ms}ms（想定より遅い。端末のストレージ負荷をご確認ください）")
    }

    private fun pushUndo(label: String? = null) {
        val snap = snapNow(label) ?: return
        undoStack.addLast(snap)
        while (undoStack.size > 30) undoStack.removeFirst()
        redoStack.clear()   // 新しい操作は redo 履歴を無効化（標準的な undo/redo 挙動）
        // [3.475.0/論理監査] 盤面/設定が変わる操作は必ずここを通る＝改善提案（別の盤面で計算した差分）を
        //   その場で無効化する。旧: 提案は findFixSuggestions と applyFixSuggestion でしか書き換えられず、
        //   セル編集・取込・職員削除のあとも古い提案が表示され、適用時に別セル/別職員へ書いていた。
        // [3.529.0/外部仕様書取り入れ] 「他の案」も同根で無効化する（旧: fixSuggestions だけ外していた）。
        alternativeScheds = emptyList()
        _ui.update { it.copy(canUndo = true, canRedo = false, fixSuggestions = emptyList(), alternatives = emptyList()) }
    }

    private fun clearUndo() {
        undoStack.clear()
        redoStack.clear()
        _ui.update { it.copy(canUndo = false, canRedo = false) }
    }

    /** 直前の編集・取込・計算開始前の状態へ戻す（最大30段）。現在状態は redo へ退避。 */
    fun undo() {
        if (job?.isActive == true || optimizeInFlight()) return   // [3.328.0] 背景の最適化中も抑止（job は前景のみ）
        val snap = undoStack.removeLastOrNull() ?: return
        snapNow(snap.label)?.let { redoStack.addLast(it) }   // [Web反映] 現在をやり直し用に退避（同じ操作名を引き継ぐ）
        state = snap.st
        currentSchedule = Array(snap.sched.size) { snap.sched[it].clone() }
        // [3.500.1/外部レビュー] 元に戻すは手操作＝「計算済み」ではない。前の結果盤面と改善提案はこの盤面とは別の実体なので外す
        //   （提案は 3.475.0 の指紋照合でも弾かれるが、画面に古い候補を残さない）。
        resultSchedule = null
        alternativeScheds = emptyList()
        val label = snap.label
        _ui.update { it.copy(messageIsError = false, structureEdited = true, canUndo = undoStack.isNotEmpty(), canRedo = true,
            engineRan = false, fixSuggestions = emptyList(), alternatives = emptyList(),
            message = if (label != null) "元に戻す: $label" else "1つ前に戻しました") }
        logOp("I", "元に戻す" + (label?.let { ": $it" } ?: ""))
        refreshCheck()
        autoSave()
    }

    /** [Web反映] 元に戻した操作をやり直す。手動修正のループ（修正→戻す→やり直し）を支える。 */
    fun redo() {
        if (job?.isActive == true || optimizeInFlight()) return   // [3.328.0] 背景の最適化中も抑止（job は前景のみ）
        val snap = redoStack.removeLastOrNull() ?: return
        snapNow(snap.label)?.let { undoStack.addLast(it) }
        state = snap.st
        currentSchedule = Array(snap.sched.size) { snap.sched[it].clone() }
        resultSchedule = null   // [3.500.1] undo() と同じ理由
        alternativeScheds = emptyList()
        val label = snap.label
        _ui.update { it.copy(messageIsError = false, structureEdited = true, canUndo = true, canRedo = redoStack.isNotEmpty(),
            engineRan = false, fixSuggestions = emptyList(), alternatives = emptyList(),
            message = if (label != null) "やり直す: $label" else "やり直しました") }
        logOp("I", "やり直し" + (label?.let { ": $it" } ?: ""))
        refreshCheck()
        autoSave()
    }

    fun load(json: String, note: String = "") = loadAsync(json, note = note)

    /**
     * [⛏6] ゼロから作る起点。最小の有効データ(1シフト/1グループ/1スタッフ/31日)を
     * 既存の load() 経路(StateParser→validate→Problem→makeUi)にそのまま流す。サンプル
     * (assets/sample_state_v6.json)と同じ構造を最小化したものなので、専用の初期化ロジックを
     * 持たず実行時の不整合リスクを抑える。読み込み後はユーザーが編集タブ(年次マスター)で
     * シフト/グループ/スタッフを一括追加して育てる想定。
     */
    fun initBlankState() {
        val days = 31
        val sched = (0 until days).joinToString(",") { "0" }
        val seed = """
            {"startDate":"2026-01-01","endDate":"2026-01-31",
            "shifts":[{"name":"休み","kigou":"休","need1":"","need2":""}],
            "groups":[{"name":"グループA","kigou":"A"}],
            "staff":[{"name":"職員1","groupIdx":0}],
            "use2Patterns":true,
            "groupShift":[[1]],"groupShiftApt":[[""]],
            "cons1":[],"cons2":[],"cons3":[],"cons3n":[],"cons3m":[],"cons3mn":[],"cons41":[],"cons42":[],
            "wishes":{},"staffRange":{},"needDay1":{},"needDay2":{},
            "schedule":[[$sched]]}
        """.trimIndent()
        load(seed)
    }

    /**
     * [3.409.0] 入口ガードを追加。3.404.0 は「完了時に `currentSchedule` と `state` を丸ごと差し替える」
     * ジョブを3つ（読み込み・CSV取込・初期解生成）名指ししたが、**ここだけガードが無かった**。
     * `job?.cancel()` は前景ジョブしか止めず（背景の最適化は `OptimizationRepository` 側で走り続ける）、
     * さらに走行中の最適化の `NonCancellable` な keep-best ハンドラと競合する。頼れるのは
     * `ui.running` ではない——3.404.0 自身がそれを表示専用へ降格させたため。
     *
     * [fromRestore] は**起動時の復元だけ**が渡す。背景実行の最中にアプリが起動して state を復元するのは
     * 正常な経路なので、ここを塞ぐと退行になる（結果は完了時に `applyBgResult` が別途適用する）。
     */
    /**
     * @param note [3.414.0/I-02] 読込完了メッセージの末尾へ足す一言。CSV取込のように**期間を推定して
     *   いる**経路が、その事実を利用者へ届けるための唯一の口（旧: 呼出側が `_ui.update` で出しても
     *   この関数の「読込完了: …」が必ず上書きしていた）。既定は空＝JSON 読込などは従来どおり。
     */
    fun loadAsync(rawJson: String, markResult: Boolean = false, fromRestore: Boolean = false, note: String = "") {
        if (!fromRestore && runBlockedByInFlight("読み込み")) return
        val json = MojibakeRepair.repair(rawJson)
        // [3.282.0/新領域ログ監査] 旧: 参照比較(`!==`)のため BOM 除去だけの健全なファイルでも毎回
        //   「文字化けを自動修復」と誤警告していた。実際に二重エンコードを復号したときだけ警告し、
        //   元ファイル自体は直らない（再取込のたび修復が走る）ことも案内する。
        val repaired = MojibakeRepair.wasDecoded(rawJson, json)
        job?.cancel()
        _ui.update { it.copy(messageIsError = false, running = true, message = "読込中…") }
        // [3.404.0] 読み込みも「完了時に state と勤務表を丸ごと差し替える」ジョブ＝その間の編集を止める。
        val boardToken = beginBoardJob("読み込み")
        job = viewModelScope.launch {
            try {
                if (repaired) logOp("W", "文字化け（二重エンコード）を自動修復して読み込みました。元のファイル自体は修復されません（「データを保存」で保存し直すと次回からこの警告は出ません）")
                var endDateFixedFrom: String? = null   // [3.486.0] endDate を日数に合わせて補正したときの旧値
                var normalizedOnLoad = false          // [3.491.0] 読込時の正規化で state が差し替わったか
                val loaded = withContext(Dispatchers.Default) {
                    val parsed = StateParser.parse(json)
                    // [3.486.0] endDate と日数の食い違いは検証を通り抜けていた＝日数を正として endDate を揃える。
                    val st0 = Ws1Ops.normalizeEndDate(parsed)
                    if (st0.endDate != parsed.endDate) endDateFixedFrom = parsed.endDate
                    validate(st0)?.let { return@withContext Result.failure<LoadedProblem>(IllegalArgumentException(it)) }
                    // [3.488.0] 検証を通ったあとで groupShiftApt を G×K に揃える（空配列・行不足は空欄＝目標なし）。
                    val st = Ws1Ops.normalizeGroupShiftApt(st0)
                    normalizedOnLoad = st !== parsed
                    val p = Problem(st)
                    val init = p.initialAssignment()
                    val report = UnifiedViolationChecker.check(st, init)
                    Result.success(LoadedProblem(st, init, report))
                }
                loaded.fold(
                    onSuccess = { lp ->
                        // [判断設計監査 #3] 置換直前の状態を1世代退避。「開く前のデータに戻す」
                        //   （restorePreviousData）で往復できる（戻す操作自体も退避を挟む＝スワップ）。
                        // [3.289.0/外部レビューMedium] 旧: fire-and-forget の launch で退避していたため、
                        //   ①「戻す」を連続で押すと退避ファイルの更新前の内容を読み、同じデータが2回出る
                        //   （＝「もう一度押すと入れ替わる」というUI契約が破れる）②状態切替後・書込前の
                        //   プロセス終了で退避が消えるのに復元可能表示だけ残る、の2つが起こり得た。
                        //   状態を切り替える前に **同期で・一時ファイル経由の原子的置換** で書き、
                        //   書込に成功したときだけ復元可能フラグを立てる。
                        // [外部レビュー P2-01] 旧: この置換をここだけ手書きしており、固定名の一時ファイル
                        //   （`RunFiles.writeAtomically` は呼出ごとに一意な名前＝3.410.0/B-05 が直した
                        //   競合を、ここだけ再現していた）。共通ヘルパーへ統一する。
                        val prevJson = if (state != null) exportJson() else null
                        val prevSaved = if (prevJson == null) false else withContext(Dispatchers.IO) {
                            runCatching { writeFileAtomically(prevBackupFile, prevJson) }.getOrDefault(false)
                        }
                        // [3.491.0/自己見直し] 旧: 正規化（endDate 補正・groupShiftApt の G×K 化）をしても
                        //   originalJson は**生のファイル**のままで、structureEdited=false の exportJson は
                        //   その生 JSON に schedule だけ差し込んで返す＝直後の autoSave も「データを保存」も
                        //   補正前の endDate を書き戻し、警告文の「保存し直すと次回から出ません」が嘘だった
                        //   （構造編集をしない限り毎回の起動で同じ警告）。正規化したときだけ、正規化後の
                        //   state を serialize したものを原本にする（extras は serialize が写す）。
                        originalJson = if (normalizedOnLoad) StateParser.serialize(lp.state, lp.schedule) else json
                        state = lp.state.withSchedule(lp.schedule)
                        currentSchedule = lp.schedule.copy2D()
                        // [bg復元] markResult=true は「バックグラウンド最適化の結果 JSON」の読込。schedule が
                        //   結果そのものなので resultSchedule/hasResult を立て、上位バーの「未計算」を防ぐ。
                        resultSchedule = if (markResult) lp.schedule.copy2D() else null
                        clearUndo()
                        // [3.475.0/論理監査] コパイロットの「前回と同じ設定」ヒントは前回のデータの記憶。
                        //   旧: 別データを開いてもリセットされず、初回の実行なのに「前回と同じ設定です」と
                        //   出たり、別データの必須違反族を名指ししたりしていた。
                        lastSettingsSig = null
                        lastResultHard = -1L
                        lastTopHardFamily = null
                        autoSave()
                        endDateFixedFrom?.let {
                            logOp("W", "期間の終了日（endDate）が日数と合っていなかったため補正しました（$it → ${lp.state.endDate}）。「データを保存」で保存し直すと次回からこの警告は出ません")
                        }
                        pushReport(lp.state, lp.schedule, lp.report) {
                            it.copy(
                                messageIsError = false,
                                loaded = true,
                                running = false,
                                hasResult = markResult,
                                engineRan = markResult,   // [3.475.0] bg結果の復元だけ「計算済み」を引き継ぐ
                                constraintsEdited = false,
                                structureEdited = false,
                                staff = lp.state.staffCount,
                                days = lp.state.dayCount,
                                shifts = lp.state.shiftCount,
                                groups = lp.state.groupCount,
                                use2 = lp.state.use2Patterns,
                                // [3.393.0] 3.313.0 が initSoft に施したのと同じ単位合わせ。旧: Evaluator の
                                //   hard を入れていたが、比較相手の bestHard は checker の report.hard。
                                //   3.318.0 で groupViol が Evaluator の hard にも入り両者は一致するように
                                //   なったが、**別々の計算から取る理由はもう無い**ので checker へ寄せる
                                //   （進捗行の「最初は N」がこの値を使う）。
                                initHard = lp.report.hard.toLong(),
                                // [3.313.0] 単位を checker 基準へ揃える。旧: Evaluator の**重み付き**soft を
                                //   入れていたが、`makeUi`（中央のUI構築）が書く bestSoft は
                                //   `report.soft`＝**生件数**なので、完了後の「改善 N%」が
                                //   重み付き→生件数の引き算になり大幅に水増しされていた。
                                initSoft = lp.report.soft.toLong(),
                                elapsedMs = 0,
                                // [3.289.0] 書込が実際に成功したときだけ立てる（既存の退避があれば維持）。
                                prevBackupAvailable = prevSaved || it.prevBackupAvailable,
                                message = "読込完了: ${lp.state.staffCount}名 / ${lp.state.dayCount}日 / ${lp.state.shiftCount}シフト$note",
                            )
                        }
                        logOp("I", "読込 ${lp.state.staffCount}名/${lp.state.dayCount}日/${lp.state.shiftCount}シフト")
                    },
                    onFailure = { err ->
                        // [3.400.0] 旧: `onFailure = { _ui.update { it.copy(message = "読込失敗: ${it.message}") } }`
                        //   内側の `it` は **UiState** を指すので、出ていたのは直前の文言＝「読込失敗: 読込中…」。
                        //   すぐ下の catch は `e.message` で正しく、ここだけ取り違えていた。引数に名前を付けて塞ぐ。
                        logOp("W", "読込失敗: ${err.javaClass.simpleName}: ${err.message}")
                        _ui.update { it.copy(running = false, message = "読み込めませんでした（${err.javaClass.simpleName}）。ファイルの中身を確認してください", messageIsError = true) }
                    },
                )
            } catch (e: CancellationException) {
                _ui.update { it.copy(messageIsError = false, running = false, message = "読み込みを中止しました") }   // [3.404.0] 停止は失敗ではない
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(running = false, message = "読み込めませんでした（${e.javaClass.simpleName}）。ファイルの中身を確認してください", messageIsError = true) }
            } finally {
                endBoardJob(boardToken)
            }
        }
    }

    /** [判断設計監査 #3] 「データを開く」直前に退避した1世代前の状態へ戻す。loadAsync 経由のため
     *  現在のデータが再び退避される＝もう一度押すと元へ戻る（スワップ）。 */
    fun restorePreviousData() {
        if (optimizeInFlight()) { _ui.update { it.copy(messageIsError = true, message = "${busyWhat()}の実行中は操作できません") }; return }
        viewModelScope.launch {
            val txt = withContext(Dispatchers.IO) {
                runCatching { prevBackupFile.takeIf { it.exists() }?.readText() }.getOrNull()
            }
            if (txt.isNullOrBlank()) {
                _ui.update { it.copy(messageIsError = false, message = "開く前のデータの退避がありません") }
                return@launch
            }
            logOp("I", "開く前のデータに戻します（もう一度押すと入れ替わります）")
            loadAsync(txt)
        }
    }

    /** Returns a human-readable error message if the state is structurally invalid, else null. */
    private fun validate(st: MagiState): String? {
        if (st.staffCount == 0) return "staff が空です"
        // [3.488.0] 読めない startDate を受理すると Problem.dow0 が黙って日曜へ落ちる（Ws1Ops.startDateError の KDoc）。
        Ws1Ops.startDateError(st)?.let { return it }
        if (st.dayCount == 0) return "schedule が空です"
        if (st.shiftCount == 0) return "shifts が空です"
        if (st.groupCount == 0) return "groups が空です"
        if (st.schedule.size != st.staffCount) return "schedule の行数が staff 数と一致しません"
        if (st.groupShift.size < st.groupCount) return "groupShift の行数が groups より少ないです"
        st.groupShift.forEachIndexed { g, row ->
            if (row.size < st.shiftCount) return "groupShift[$g] の列数が shifts より少ないです"
            if (row.take(st.shiftCount).none { it == 1 }) return "groupShift[$g] に担当可能シフトがありません"
        }
        st.groupShiftApt.forEachIndexed { g, row ->
            if (g < st.groupCount && row.isNotEmpty() && row.size < st.shiftCount) return "groupShiftApt[$g] の列数が shifts より少ないです"
        }
        st.staff.forEachIndexed { i, s ->
            if (s.groupIdx !in 0 until st.groupCount) return "staff[$i].groupIdx が範囲外です (${s.groupIdx})"
        }
        st.schedule.forEachIndexed { i, row ->
            if (row.size != st.dayCount) return "schedule[$i] の日数が不揃いです"
            row.forEachIndexed { j, k ->
                if (k != -1 && k !in 0 until st.shiftCount) return "schedule[$i][$j] のシフト番号が範囲外です ($k)"
            }
        }
        return null
    }

    /**
     * [native堅牢化] 最適化・生成の実行前に構造を検証する。期間/スタッフ/シフトの不整合や
     * 未割当グループ・範囲外シフト等があれば、クラッシュさせず理由を表示して中止する
     * （添付資料 doc#5/#6/#7 起因の事故をネイティブ側でも明示的に防止）。
     */
    private fun ensureValidForRun(st: MagiState, sched: Array<IntArray>): Boolean {
        val err = validate(st.withSchedule(sched)) ?: return true
        _ui.update { it.copy(messageIsError = true, running = false, message = "実行できません: $err。編集内容を確認してください") }
        return false
    }

    fun setWorkers(n: Int) { val v = n.coerceIn(1, 16); _ui.update { it.copy(workers = v) }; logOp("I", "設定変更: 並列数 → $v") }
    // タイムアウト上限は5分(300s)。エンジンは budgetMs を全フェーズで厳守し、超過しない（停滞時はさらに早期終了）。
    /** [ネイティブ加速 Stage4] ユーザートグル。OFF=C++チャンク不使用（番兵ゲートとは独立の意思表示）。 */
    fun setNativeAccel(on: Boolean) {
        com.magi.app.v6.NativeGate.userEnabled = on
        _ui.update { it.copy(nativeAccel = on) }
        logOp("I", "設定変更: ネイティブ加速 → ${if (on) "ON" else "OFF"}")
    }

    /** [照合トグル] Kotlinパリティ照合の ON/OFF。OFF=純ネイティブ（Kotlin照合を行わず C++結果を信頼＝
     *  検証/ベンチ用・誤結果の可能性）。C++内部の自己整合(status)番兵は独立に常時有効。 */
    fun setNativeParity(on: Boolean) {
        com.magi.app.v6.NativeGate.parityCheckEnabled = on
        _ui.update { it.copy(nativeParity = on) }
        logOp(if (on) "I" else "W", "設定変更: Kotlinパリティ照合 → ${if (on) "ON" else "OFF（純ネイティブ・誤結果の可能性）"}")
    }

    /**
     * [3.298.0 配線] ブロック巡回交換の c3n 事前フィルタ ON/OFF（既定 **ON**、3.518.0）。
     * c3n は HARD なので増える候補は `isBetter` が必ず却下する＝**採用結果は ON/OFF で変わらない**
     * （3.296.0 の A/B 実測で最終盤面・採用数の完全一致を確認済み）。ON は「詰んだ候補へフル checker を
     * 呼ばない」ぶんの節約だけで、評価枠を soft 判定まで進める候補へ回せる。
     */
    fun setBlockSwapC3nFilter(on: Boolean) {
        com.magi.app.v6.PolishGate.filterC3nIncrease = on
        _ui.update { it.copy(blockSwapC3nFilter = on) }
        logOp("I", "設定変更: 禁止連続の事前フィルタ → ${if (on) "ON" else "OFF"}")
    }

    /**
     * [3.304.0] 禁止連続を崩しに行く日を j±1 から「違反パターンがまたぐ全日」へ広げる。
     * 3連（`Dﾃ→休→A4`）の先頭に届くようになる一般化だが、実データ3件で利得が一貫しなかったため既定 OFF
     * （詳細は `PolishGate.wideC3nBreakDays` の docstring）。検証用に切り替えられるようにしてある。
     */
    fun setWideC3nBreak(on: Boolean) {
        com.magi.app.v6.PolishGate.wideC3nBreakDays = on
        _ui.update { it.copy(wideC3nBreak = on) }
        logOp("I", "設定変更: 禁止連続の崩し範囲 → ${if (on) "パターン全域" else "前後1日"}")
    }

    // [3.409.21] setAdaptiveEscape / setPortfolioRoleParallelSa は削除（単体 A/B 中立＝機構ごと撤去。
    //   PolishGate 冒頭の記録参照）。

    /**
     * [3.514.0] 職員2人の同日シフト交換探索を、既定の連続不採用200回で打ち切らず、
     * 2人組の全組合せぶん（`CombinatorialRepair.combineAndApply`のexhaustPairs、上限5000通り）まで試す。
     * isBetterゲートは不変のため退化はしない＝安全。効果は未計測（`tools/loop`で別途測定中、既定OFF）。
     */
    fun setCombineExhaustPairs(on: Boolean) {
        com.magi.app.v6.PolishGate.combineExhaustPairs = on
        _ui.update { it.copy(combineExhaustPairs = on) }
        logOp("I", "設定変更: 結合探索を粘り強く → ${if (on) "ON" else "OFF"}")
    }

    /**
     * [3.514.0] 個人回数・期間の一括見直し（共同LNS）を「短時間試行→改善時だけ拡張」にする。
     * iter9実測: 退行0・品質±0・速度はデータにより変わる（平均+10%/実データ−23%）。
     */
    fun setLnsAdaptive(on: Boolean) {
        com.magi.app.v6.PolishGate.lnsAdaptive = on
        _ui.update { it.copy(lnsAdaptive = on) }
        logOp("I", "設定変更: 一括見直しの自動調整 → ${if (on) "ON" else "OFF"}")
    }

    /**
     * [3.535.0/HF77明示数値指示] 公平化(fair)/適切回数(apt)研磨で、対象家族以外のSOFT違反の悪化を
     * 研磨開始時点の合計比+6%まで容認する（累積予算、`AptFairPolish.toleratedBetter`）。HARDの不増加・
     * keep-bestの根幹（`betterReport`）は不変。既定OFF（採否はtools/loopのA/Bで別途測定）。
     */
    fun setCountChainPolish(on: Boolean) {
        com.magi.app.v6.PolishGate.countChainPolish = on
        _ui.update { it.copy(countChainPolish = on) }
    }

    fun setAptFairSoftTolerance(on: Boolean) {
        com.magi.app.v6.PolishGate.aptFairSoftTolerance = on
        _ui.update { it.copy(aptFairSoftTolerance = on) }
        logOp("I", "設定変更: 公平化/適切回数研磨の他ソフト許容(6%) → ${if (on) "ON" else "OFF"}")
    }

    fun setBudget(sec: Int) { val v = sec.coerceIn(10, MAX_BUDGET_SEC); _ui.update { it.copy(budgetSec = v) }; logOp("I", "設定変更: 予算 → ${v}秒") }
    fun setSoftPolish(b: Boolean) { _ui.update { it.copy(softPolish = b) }; logOp("I", "設定変更: ソフト研磨 → ${if (b) "ON" else "OFF"}") }
    fun setV6Algorithm(a: V6Algorithm) { _ui.update { it.copy(v6Algorithm = a) }; logOp("I", "設定変更: 方式 → $a") }

    fun refreshCheck() {
        val st = state ?: return
        val sched = currentSchedule?.copy2D() ?: return
        val seq = ++checkSeq
        checkJob?.cancel()
        _ui.update { it.copy(messageIsError = false, running = true, message = "違反チェック中…") }
        checkJob = viewModelScope.launch {
            try {
                val res = V6FinalPort.handleCheck(st, sched)
                if (seq != checkSeq) return@launch   // [review #6] a newer check started; drop stale result
                pushReport(st, res.schedule, res.report) { it.copy(
                    messageIsError = false,
                    // [3.328.0] 最適化が動いていれば実行中のまま。旧: 無条件に false で、
                    //   最適化中の設定編集→検査完了で全ガードが素通りになっていた。
                    running = optimizeInFlight(),
                    message = "違反チェック完了: 必須=${res.report.hard} 合計=${res.report.total}",
                ) }
                logOp("I", "違反チェック 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: CancellationException) {
                // [3.284.0/外部レビューHigh③] 停止時の running 固着を解消。新しいチェックによるキャンセル
                //   （seq != checkSeq＝後続が直後に running=true を立て直す）では触らず、stop() による
                //   キャンセルのときだけ実行中表示を戻す。
                if (seq == checkSeq) _ui.update { it.copy(messageIsError = false, running = optimizeInFlight(), message = "違反チェックを停止しました") }
                throw e
            } catch (e: Throwable) {
                // [3.392.0] Error(OOM等)まで拾う。旧: Exception だけで、Error だと running=true が固着し
                //   3.328.0 で running を根拠にした14個の編集ガードが全て閉じたまま＝アプリが読取専用になった。
                // [3.400.0] 3.382.0 は長い実行4経路へ終端ログを入れたが、毎回のセル編集で走る refreshCheck は
                //   対象外だった＝画面の文言が消えると死因が何も残らない。痕跡を必ず残す。
                logOp("W", "違反チェック 失敗: ${e.javaClass.simpleName}: ${e.message}")
                if (seq == checkSeq) _ui.update { it.copy(running = optimizeInFlight(), message = "違反チェックに失敗しました（${e.javaClass.simpleName}）", messageIsError = true) }
            }
        }
    }

    /**
     * [初期解生成(賢い版)] 希望シフト→C1(窓の要件)→日別必要人数→個人下限→残り埋め の順で
     * 初期解を組み立てる。本最適化(SA/ALNS)へは続けない（続けて最適化したい場合は
     * この後に別途「勤務表をつくる」を押す）。ホームの「勤務表をつくる」の隣に新設。
     */
    fun generateSmartInitial() {
        val st = state ?: return
        val sched = currentSchedule ?: return
        // [3.271.0, 実機ログ起因] 実行中ガード。旧: ガード無しのため「勤務表をつくる」の直後に隣接する
        //   本ボタンを連続タップすると、走行中の最適化と初期解生成が併走し、job 参照の上書き
        //   （走行中jobが stop() 不能のゾンビ化）と currentSchedule の同時書き換え（3.161.0 と同じ
        //   別名共有クラス）が起きていた（実機ログ 19:56:41 最適化開始→19:56:48 初期解生成完了 で実証）。
        //   runV6FullOptimize/start/runSoftPolish と同じガードに統一（3.161.0 のセル編集ガードと同方針）。
        if (optimizeInFlight()) {
            _ui.update { it.copy(messageIsError = false, message = "最適化の実行中は下書きをつくれません（完了または「やめる」の後にどうぞ）") }
            return
        }
        if (!ensureValidForRun(st, sched)) return
        pushUndo("下書き作成")
        _ui.update { it.copy(messageIsError = false, running = true, hasResult = false, message = "下書きをつくっています…") }
        // [3.404.0] 完了時に currentSchedule/state を丸ごと差し替えるので、その間の編集を止める旗を立てる。
        //   旧: `running=true`（画面は全ロック）なのに `optimizeInFlight()` は false のままで、
        //   `setCell` のガードだけ素通り＝編集が完了時に無言で消えていた。
        val boardToken = beginBoardJob("下書きづくり", engineRun = true)
        job = viewModelScope.launch {
            try {
                val res = V6FinalPort.handleSmartInitial(st.withSchedule(sched), allowImpossible = true)
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st.withSchedule(res.schedule)
                pushReport(state ?: st, res.schedule, res.report, runLabel = "下書きづくり") { it.copy(
                    messageIsError = false,
                    running = false,
                    hasResult = true,
                    engineRan = true,
                    elapsedMs = 0,
                    message = "下書きをつくりました: 必須違反=${res.report.hard} 合計=${res.report.total}",
                ) }
                logOp("I", "初期解生成 完了 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: CancellationException) {
                // [3.404.0] 停止・ジョブ上書きを「失敗」と呼ばない（兄弟の refreshCheck 等は分離済みで
                //   ここだけ取り残されていた＝停止するたび「初期解生成失敗」という誤った文言が出ていた）。
                logOp("I", "初期解生成 停止")
                _ui.update { it.copy(messageIsError = false, running = false, message = "下書きづくりを停止しました") }
                throw e
            } catch (e: Throwable) {
                // [3.271.0] 失敗を操作ログにも残す（旧: message のみ＝書き出したログから消えた実行が
                //   追跡不能だった。実機ログ解析で「開始したのに完了も停止も無い実行」の死因特定を阻んだ）。
                logOp("W", "初期解生成 失敗: ${e.javaClass.simpleName}: ${e.message}")
                _ui.update { it.copy(running = false, message = "下書きをつくれませんでした（${e.javaClass.simpleName}）", messageIsError = true) }
            } finally {
                endBoardJob(boardToken)
            }
        }
    }

    // 操作コパイロット用: 直前の実行設定と結果（ガチャ操作検知に使用）
    private var lastSettingsSig: String? = null
    private var lastResultHard: Long = -1L
    private var lastTopHardFamily: String? = null

    /**
     * [3.322.0] 直近の最適化で「窓の要件(c1)がなぜ直せなかったか」の構造化診断。
     * 研磨が候補を作って却下した記録が唯一の根拠＝盤面から再計算できないため保持する
     * （CoverageDiag/ForbiddenDiag が毎回作り直せるのとはここが違う）。
     */
    private var lastC1Plateau: C1PlateauDiagnosis? = null

    /**
     * [3.323.0] 直近の最適化で、厳密ピン(lo==hi)だけが止めた手の**計測できた**試行数。
     * `isBetter` が採用を認めた手をピンのガードだけが却下した回数。ただし全件ではない
     * （[com.magi.app.v6.V6PostOptimizationResult.observedPinBlockedAttempts] の注記参照）。
     */
    private var lastObservedPinAttempts: Int = 0

    /** [3.326.0] どのピン(職員,シフト)が何回止めたか。緩和対象の提示に使う。 */
    private var lastPinBlocks: PinBlockAttribution? = null

    /**
     * [3.324.0/外部レビュー] 上の2つは「その盤面で研磨が却下した記録」であって盤面から再計算できない。
     * よって**どの盤面に対する観測か**を指紋で持ち、盤面が変わったら自動的に黙る。
     * 手編集・元に戻す・データ読込・CSV取込・初期解生成…と変更サイトごとにフックを足す方式は
     * 必ずどこかを漏らすので、makeUi 側で毎回突き合わせる自己無効化にする。
     */
    private var lastDiagBoardKey: Long = 0L

    /** [3.327.0→3.328.0] 診断を取ったときの入力の指紋。設定が編集されたら診断は失効する。 */
    private var lastDiagStateKey: Long = 0L

    /** [3.328.0] 背景の最適化を開始したときの入力の指紋。結果を当てる前に一致を確かめる。 */
    private var bgStateKey: Long = 0L

    /** [3.410.0/U-01] いま待っている背景実行の ID。0=待っていない／プロセス再起動後の復元経路。 */
    private var bgRunId: Long = 0L

    /** [3.475.0] この実行に渡した入力盤面（keep-best の比較元）。プロセス再起動を跨ぐと null（復元経路）。 */
    private var bgInput: Array<IntArray>? = null

    /** 盤面の内容から決まる指紋。S×T が小さい（30×31）ので毎回の計算コストは無視できる。 */
    private fun boardKey(schedule: Array<IntArray>): Long {
        var h = 1125899906842597L
        for (row in schedule) for (v in row) h = h * 31L + v
        return h
    }

    /**
     * [3.328.0/外部レビュー → 3.330.0 で v6 へ移動] 勤務表の意味を決める入力すべての指紋。
     * 実体は [com.magi.app.v6.StateFingerprint]（Android 非依存＝ホストでテストできる）。
     */
    private fun stateKey(st: MagiState): Long = com.magi.app.v6.StateFingerprint.of(st)

    /** 研磨診断を「この盤面のもの」として保存する。null/0 は診断なし。 */
    private fun setPolishDiagnostics(
        plateau: C1PlateauDiagnosis?,
        observedPinBlockedAttempts: Int,
        forSchedule: Array<IntArray>,
        pinBlocks: PinBlockAttribution? = null,
    ) {
        lastC1Plateau = plateau
        lastObservedPinAttempts = observedPinBlockedAttempts
        lastPinBlocks = pinBlocks
        val fresh = plateau != null || observedPinBlockedAttempts > 0
        lastDiagBoardKey = if (fresh) boardKey(forSchedule) else 0L
        lastDiagStateKey = if (fresh) state?.let { stateKey(it) } ?: 0L else 0L
    }

    private fun hardFamilyJp(key: String): String = when (key) {
        "covU" -> "人員不足（必要人数）"
        "c3n" -> "禁止の並び（連勤など）"
        "c3w" -> "希望前日の禁止"
        "pref" -> "希望シフト"
        "groupViol" -> "担当外シフト"
        "low" -> "個人の回数下限"
        "high" -> "個人の回数上限"
        else -> key
    }

    private fun topHardFamilyJp(breakdown: Map<String, Int>): String? {
        val keys = listOf("covU", "c3n", "c3w", "pref", "groupViol", "low", "high")
        val top = keys.maxByOrNull { breakdown[it] ?: 0 } ?: return null
        return if ((breakdown[top] ?: 0) > 0) hardFamilyJp(top) else null
    }

    fun runV6FullOptimize() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (runBlockedByInFlight("勤務表の作成")) return
        if (!ensureValidForRun(st0, sched0)) return
        pushUndo("勤務表の作成")
        val sig = "${_ui.value.budgetSec}|${_ui.value.workers}|${_ui.value.v6Algorithm}|${_ui.value.softPolish}"
        val hint = if (sig == lastSettingsSig && lastResultHard > 0L)
            "前回と同じ設定での再実行です。いちばん多い必須違反は『${lastTopHardFamily ?: "不明"}』。編集タブでこれを1つ緩めると改善の可能性が高いです。"
        else null
        lastSettingsSig = sig
        _ui.update { it.copy(messageIsError = false, running = true, hasResult = false, copilotHint = hint, alternatives = emptyList(), liveSchedule = emptyList(), interruptedRun = false, interruptedInfo = null, message = "勤務表をつくり始めました") }
        logOp("I", "最適化 開始 (予算${_ui.value.budgetSec}s, 並列${_ui.value.workers}, 方式${_ui.value.v6Algorithm})")
        writeRunMarker("fg")
        clearBgFiles("前景実行の開始")   // [C1] fg実行ではbg途中状態は無関係＝掃除
        val startMs = System.currentTimeMillis()
        // HF63: 探索の改善ストリームを追跡し、構造的に充足困難な制約族を検出（重み系は非改変＝安全）。
        val hf63 = Hf63Infeasibility()
        // 最適化中ログ強化用のスロットル状態（操作ログへマイルストーンだけを残しスパムを防ぐ）。
        var liveHard = Long.MAX_VALUE
        var livePhase = ""
        // [3.393.0/ちらつき対策] UI へ押した最後の時刻と、間引きで捨てた回を埋める最新の検査結果。
        var lastUiPushMs = Long.MIN_VALUE / 4
        var lastLiveReport: ViolationReport? = null
        val runWall0 = System.currentTimeMillis()   // [N6] 経過表示は壁時計基準（onProgressのelapsedは仮説ローカルで巻き戻る）
        var lastPhaseLogMs = -10_000L
        val phaseNameLastLogMs = HashMap<String, Long>()   // [3.283.0] 同名フェーズの再ログ抑制（60s窓・スパム対策）
        var lastHardLogMs = -10_000L
        val boardToken = beginBoardJob("勤務表づくり", engineRun = true)   // [3.328.0/3.404.0]
        job = viewModelScope.launch {
            // [3.372.0/実機ログ起因] 終端ログ（完了/停止/失敗）を必ず1行残す保証。実機ログ(2026-08-15)で
            //   「最適化 開始」だけあって終端行が無い実行が2件あり、死因を判別できなかった。全経路が
            //   logOp を持つはずなのに欠けうるのは、停止分岐の logOp が pushReport（診断を回す＝例外を
            //   投げうる）の**後ろ**にあるため。そこが落ちると finally は何も残さず実行が消える。
            //   3.271.0 の「サイレント死の防止」と同じ狙いを、原因に依存しない形で閉じる。
            var terminalLogged = false
            try {
                // [再実行 keep-best] 実行開始時の入力解(sched0)の違反を評価し、完了時の採用判定の基準にする。
                //   sched0 はデータ編集直後なら新データの初期解なので、編集をまたいでも公平な基準になる。
                val baseReport = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(st0, sched0) }
                // [一括修正] 「必須違反 残りN件 に改善」の基準を入力盤面の必須数でシード。旧: Long.MAX_VALUE 始まりの
                //   ため、探索シードが入力より悪い局面(例: 入力1→シード2)でも最初の報告を「改善」と表示していた。
                liveHard = baseReport.hard.toLong()
                // [3.394.0/外部レビュー] 進捗行の基準（「改善◯% (初期→現在)」「最初は N」）を**この実行の
                //   入力盤面**にする。旧: initHard/initSoft は `loadAsync` でしか書かれず、CSV 取込・編集・
                //   再実行のあとも最後に JSON を読んだときの値を指していた＝別のデータや別の実行の基準で
                //   進み具合を出していた。keep-best の判定に使う baseReport がまさにこの実行の入力なので、
                //   同じものを基準にする（満足度 satisfaction も initHard+initSoft から出るので一緒に直る）。
                _ui.update { it.copy(initHard = baseReport.hard.toLong(), initSoft = baseReport.soft.toLong()) }
                val res = V6FinalPort.handleOptimize(
                    state = st0,
                    schedule = sched0.copy2D(),
                    secondsRaw = _ui.value.budgetSec,
                    workers = _ui.value.workers,
                    softPolish = _ui.value.softPolish,
                    requestedAlgorithm = _ui.value.v6Algorithm,
                    allowImpossible = true,
                ) { phase, report, _, _ ->
                    val rep = report
                    // [3.93.1と同クラスの補正 / 実機ログ起因] 旧: 累積iter(数千万)を渡すと閾値5000が「約20msの無改善」
                    //   相当になり、違反>0の族がほぼ全て即 infeasible 判定＝9族ノイズ警告になっていた。経過時間ベース
                    //   (100単位/秒)に補正し、閾値5000＝「50秒無改善」で発火させる(class は Web 忠実移植のまま)。
                    //   [監査(6)] callback の elapsed はフェーズ境界で巻き戻るローカル時計＝長いフェーズ後に族が永久に
                    //   フラグ不能になるため、最適化開始からの単調な壁時計(startMs基準)を使う。
                    if (rep != null) hf63.updateFromBreakdown(rep.breakdown, ((System.currentTimeMillis() - startMs) / 10L).toInt())
                    // [3.393.0] 壁時計とフェーズ名を前倒しで出す（UI 押し出しの間引き判定と操作ログの
                    //   スロットル判定が同じ値を見るように。旧はログ側だけがここより後で計算していた）。
                    val wallElapsed = System.currentTimeMillis() - runWall0
                    val base = phase.substringAfter("/ ").trim().ifEmpty { phase }
                    // 間引きで捨てた回のぶんを埋めるため、最後に届いた非nullの検査結果を持ち回す
                    //   （report は毎回付くとは限らず、押す回だけ見ると breakdown が飛ぶ）。
                    if (rep != null) lastLiveReport = rep
                    // [3.394.0/外部レビューで判明・3.393.0 の欠陥] 旧実装は「フェーズが変わったら窓を飛ばす」
                    //   抜け道を持っていたが、フェーズ名は**ワーカーごと**に流れる。既定の長時間経路
                    //   （AUTO 211秒以上＝PORTFOLIO・並列8）では実測 **35,559回の押し出しのうち 35,518回
                    //   （99.9%）がこの抜け道**で、窓は事実上無効だった（1,174.7回/秒 → 785.0回/秒）。
                    //   抜け道は「必須違反が減った瞬間」だけに絞る＝これは単調減少なので回数が
                    //   入力の必須件数で上限される。実測 4.3回/秒。フェーズ名の更新は最大 200ms 遅れるだけ。
                    val uiDue = (rep != null && rep.hard.toLong() < liveHard) ||
                        wallElapsed - lastUiPushMs >= OptimizationRepository.PROGRESS_PUSH_MS
                    if (uiDue) {
                        lastUiPushMs = wallElapsed
                        val shown = lastLiveReport
                        _ui.update { it.copy(
                            bestHard = shown?.hard?.toLong() ?: it.bestHard,
                            bestSoft = shown?.soft?.toLong() ?: it.bestSoft,
                            totalViolations = shown?.total ?: it.totalViolations,
                            // 実行中も breakdown をライブ更新（export時に hard と breakdown が食い違う不整合を防ぐ）
                            breakdown = if (shown != null) emptyBreakdown + shown.breakdown else it.breakdown,
                            // [実機報告「残り時間表示が5分から何度も巡回する」修正] onProgressのelapsedは
                            //   フェーズ境界で巻き戻るローカル時計（本関数冒頭の runWall0＝[N6] コメントと同じ既知の性質）。
                            //   progressSummary の「残り」表示はこれを budgetSec から引くため、V5→ALNS→RSI
                            //   ラウンド等の頻繁なフェーズ遷移のたびに残り時間が予算近くまで跳ね戻って見えていた。
                            //   HF63の改善ストリーム追跡（本関数内 hf63.updateFromBreakdown 呼出）と同じ単調な
                            //   壁時計(startMs基準)に統一する。
                            elapsedMs = System.currentTimeMillis() - startMs,
                            // [DefragLiveView] 計算中の最良盤面をライブ表示用に反映（節目で更新される）。
                            liveSchedule = V6NativeOptimizer.liveBest ?: it.liveSchedule,
                            // [3.400.0] 旧: `message = "V6 $phase 実行中…"`。3.399.0 で message が Snackbar に
                            //   なったため、フェーズが変わるたびに（実行中ずっと）Snackbar が出続ける副作用に
                            //   なっていた。これは**イベントでなく状態**で、実行中であることは上部バッジと
                            //   進捗行が既に示している＝3.399.0 自身が定めた役割分担に従って流さない。
                            //   フェーズの遷移は操作ログ（下のスロットル付き logOp）に残るので追える。
                        ) }
                    }
                    // ---- 最適化中ログ強化（スロットル付き）----
                    // フェーズ遷移と「必須違反が減った瞬間」だけを操作ログへ。頻度上限を設けてスパムを防ぐ。
                    // [ログ欠落バグ修正] スロットル判定に onProgress の仮説ローカル elapsed をそのまま使うと、
                    //   フェーズ境界で elapsed が巻き戻る（N6コメント参照）ため「elapsed - lastXxxMs」が新フェーズ
                    //   開始直後に大きく負になり得た。lastXxxMs は前フェーズ終盤の(大きい)値のまま残るため、新フェーズの
                    //   持続時間がその残存値+閾値に届かない場合、遷移ログが1件も出ないまま丸ごと欠落していた
                    //   （実機ログでRSI++のALNS Refineフェーズ(約90秒)が操作ログから完全に消えていたのはこれが原因）。
                    //   表示に既に使っている壁時計(runWall0基準)へスロットル判定・保持値とも統一する。
                    // [3.283.0] 最良更新・改善は情報価値が高いので同名60秒窓の対象外。
                    // [3.378.0/実機ログ起因] **一律2.5秒ゲートの対象からも外す**。旧実装は「窓の対象外」と
                    //   言いながら `lastPhaseLogMs`（全フェーズ行で共有）で先に弾いており、実機ログでは
                    //   `全体最良更新=17回` に対し操作ログに残ったのは**7行だけ**＝改善の10回が消えていた。
                    //   これは「探索が序盤で止まったのか終盤まで刻んだのか」を読むための唯一の軌跡で、
                    //   3.283.0 が守ろうとした「重要イベントを押し出さない」意図そのもの。300秒で17行＝スパムにならない。
                    val important = base.contains("最良更新") || base.contains("改善")
                    if (base != livePhase && (important || wallElapsed - lastPhaseLogMs >= 2_500)) {
                        // [3.283.0/スパムログ対策] 適応ポートフォリオは V5 SA→RSI→ALNS を数秒周期で循環し、
                        //   遷移ごとにログすると操作ログが探索フェーズ行で埋まる（実機ログ: 68件中約60件が
                        //   フェーズ行＝読込/完了/改善などの重要イベントがリングから押し出される）。
                        //   同名フェーズ（数字を # に正規化: "ALNS restart 1/2" と "2/2" は同一視）は60秒に
                        //   1回まで。最良更新・改善を含む行は情報価値が高いため窓の対象外（従来どおり）。
                        val nameKey = base.replace(Regex("[0-9]+"), "#")
                        // [3.283.1/実機ログで発覚した自己回帰の修正] 旧: 未出フェーズの番兵に Long.MIN_VALUE を
                        //   使い `wallElapsed - Long.MIN_VALUE` が**負へオーバーフロー**＝初出判定が恒偽で
                        //   通常フェーズ行が1件も出なくなっていた（意図は同名60秒窓＝20行台、実機は0行）。
                        //   null 判定へ是正（初出は常にログ・以後は60秒窓）。
                        val lastForName = phaseNameLastLogMs[nameKey]
                        if (important || lastForName == null || wallElapsed - lastForName >= 60_000) {
                            // [3.378.0] 最良更新の行に**その時点の値**を載せる。旧: 「いつ改善したか」は
                            //   出るのに「いくつになったか」が無く、改善の軌跡（例 366→…→294）がログから
                            //   一切追えなかった（スコアの数字は最初の必須改善行と最後の完了行の2点のみ）。
                            // [3.379.0/実機ログ起因] **重みも出す**。3.378.0 で必須/合計だけを載せたが、
                            //   実機ログの軌跡が 540→522→482→430→**467**→452 と途中で合計が増えて見え、
                            //   「最良更新なのに悪化している」という読めない行になった。keep-best は
                            //   hard→weightedScore→total（3.287.0）なので合計の増加は weighted の改善と
                            //   引き換えの正しい取引だが、重みが無いとそれが確かめられない（スコア収支と同じ理由）。
                            val score = if (important && rep != null)
                                "・必須${rep.hard} 合計${rep.total} 重み${rep.weightedScore.toLong()}" else ""
                            logOp("I", "探索フェーズ: $base（経過${wallElapsed / 1000}秒$score）")
                            phaseNameLastLogMs[nameKey] = wallElapsed
                            lastPhaseLogMs = wallElapsed
                        }
                        livePhase = base
                    }
                    if (rep != null && rep.hard.toLong() < liveHard) {
                        if (rep.hard == 0 || wallElapsed - lastHardLogMs >= 1_500) {
                            logOp("I", "必須違反 残り${rep.hard}件 に改善（経過${wallElapsed / 1000}秒・合計${rep.total}）")
                            lastHardLogMs = wallElapsed
                        }
                        liveHard = rep.hard.toLong()
                    }
                }
                // [再実行 keep-best] 完了結果が入力より悪化なら、入力解を維持して通知する。
                //   「もう一度つくる」を繰り返したとき、稀に多様化フェーズ等で入力より悪い解が返り、それを採用して
                //   良い結果(例 HARD=1)を捨てる事象があった(実機ログで確認)。入力以上の結果のみ採用する。
                // [3.289.0/自己監査で発見・runSoftPolish と同型] 判定を betterReport（hard→weightedScore→total）へ
                //   統一。旧: (必須, 合計) のみで weightedScore を見ておらず、3.287.0 で keep-best が正しく採用する
                //   ようになった「HARD同値・weighted改善・total増」の結果を**メイン最適化経路のこの保険が捨てて
                //   入力へ戻していた**（外部レビューが指摘した runSoftPolish より影響が大きい経路）。
                val newHard = res.report.hard.toLong(); val newTotal = res.report.total
                val baseHard = baseReport.hard.toLong(); val baseTotal = baseReport.total
                val worseThanInput = betterReport(baseReport, res.report)
                if (worseThanInput) {
                    val kept = sched0.copy2D()
                    // [3.324.0/外部レビュー] 採用しなかった盤面の診断は保存しない。旧実装は分岐に関わらず
                    //   res.post を無条件に保存しており、「捨てた盤面で直せなかった理由」を「いま表示中の
                    //   勤務表の理由」として見せうる（維持した勤務表は別の探索の産物）。
                    setPolishDiagnostics(null, 0, kept)
                    currentSchedule = kept
                    autoSave()
                    resultSchedule = kept
                    state = st0.withSchedule(kept)
                    pushReport(state ?: st0, kept, baseReport) { it.copy(
                        messageIsError = false,
                        running = false,
                        hasResult = true,
                        engineRan = true,
                        message = "今回(必須$newHard/合計$newTotal)は前回(必須$baseHard/合計$baseTotal)より改善しませんでした。前回の結果を維持します。",
                    ) }
                    logOp("I", "再実行: 今回 必須$newHard/合計$newTotal は前回 必須$baseHard/合計$baseTotal 以下に改善せず → 前回を維持")
                    lastResultHard = baseHard
                } else {
                    // [3.324.0/外部レビュー] pushReport(=makeUi の唯一の経路)より**前**に保存する。
                    //   旧実装は pushReport のあとに代入していたため、その回の画面には診断が入らず
                    //   次の再チェックでようやく（しかも古い盤面基準で）出るという順序の逆転だった。
                    setPolishDiagnostics(res.post?.c1Plateau, res.post?.observedPinBlockedAttempts ?: 0, res.schedule, res.post?.pinBlocks)
                    currentSchedule = res.schedule.copy2D()
                    autoSave()
                    resultSchedule = res.schedule.copy2D()
                    state = st0.withSchedule(res.schedule)
                    // [design-review] 旧「最適化（${res.phase}）完了: …」は res.phase="optimize:PORTFOLIO" 等の
                    //   生の内部識別子（label.tech）をそのまま画面へ出していた（operator_ux.md §2「英字符号を
                    //   画面に一切出さない」・3.400.0 が背景進捗の同型漏れを既に除去した先例の取りこぼし）。
                    //   併せて開始メッセージ「勤務表をつくり始めました」と語彙を揃える（同じ操作は同じ形＝3.397.0）。
                    //   詳細な方式は診断ログ(直下logOp)と設定タブ(3.192.0)で確認可能。
                    pushReport(state ?: st0, res.schedule, res.report, runLabel = "最適化") { it.copy(
                        messageIsError = false,
                        running = false,
                        hasResult = true,
                        engineRan = true,
                        runSummary = runSummaryOf(com.magi.app.v6.ChangeSummary.of(st0, sched0, res.schedule, res.report, baseReport)),
                        message = "勤務表ができました: 必須=${res.report.hard} 合計=${res.report.total} (${System.currentTimeMillis() - startMs}ms)",
                    ) }
                    lastResultHard = newHard
                }
                lastC1Plateau?.logLines()?.take(4)?.forEach { logOp("W", it.removePrefix("[W] ")) }
                // [3.475.0/論理監査] 採用した盤面の族を覚える。旧: 入力維持の分岐でも捨てた盤面（res.report）
                //   から取っていたため、次回のヒントが「いま表示している勤務表」に無い族を名指しした。
                val adoptedReport = if (worseThanInput) baseReport else res.report
                lastTopHardFamily = if (adoptedReport.hard > 0) topHardFamilyJp(adoptedReport.breakdown) else null
                logOp(if (res.report.hard == 0) "I" else "W", "最適化 完了 必須=${res.report.hard} 合計=${res.report.total} (${res.phase})")
                // [3.409.17/実機ログ 3.409.14] 予算超過の実行は内訳が診断ログ（次の実行で消える）にしか
                //   残らず特定不能だった（13実行中5回が474〜959sまで超過したのに、残った診断は最後の
                //   1回ぶんだけ）。超過時は TIME/エポック超過/後処理パス別 を操作ログへ写して生き残らせる。
                if (res.logs.any { it.tag == "TIME" && it.level == "W" }) {
                    res.logs.firstOrNull { it.tag == "TIME" }?.let { logOp("W", "予算超過: ${it.message}") }
                    res.logs.firstOrNull { it.tag == "エポック超過" }?.let { logOp("W", it.message) }
                    res.logs.lastOrNull { it.tag == "POST" && it.message.startsWith("後処理パス別") }
                        ?.let { logOp("W", "予算超過の内訳(後処理): ${it.message}") }
                }
                terminalLogged = true
                // HF63 検出: 50秒改善のない制約族＝データ上満たせない可能性が高い（業務担当者へ提示）。
                // [実機ログ起因] 探索中の一時盤面でしか違反が無かった族（最終盤面で0）は「充足できている」ので
                //   警告から除外する（旧: 破棄された探索トラックの covO/LimMax まで列挙され誤解を招いた）。
                val staleKeys = hf63.infeasibleBreakdownKeys().filter { (res.report.breakdown[it] ?: 0) > 0 }
                if (staleKeys.isNotEmpty()) {
                    val names = staleKeys.mapNotNull { k -> Hf63Infeasibility.KEY_TO_INDEX[k]?.let { Hf63Infeasibility.CNAMES[it] } }
                    logOp("W", "構造的に充足が難しい制約を検出: ${names.joinToString(", ")}（データの見直しを推奨）")
                }
                captureAlternatives(res.alternatives)
            } catch (e: CancellationException) {
                // [停止 keep-best] 中断時は実行中の(未採用の)途中盤面ではなく、直前に確定していた
                //   入力解(sched0)をそのまま保持し、表示の違反数も実際の盤面に合わせる。これにより
                //   「必須=0だったのに停止したら必須が増えて見える」不整合を防ぐ（完了時のkeep-bestと同じ思想）。
                // [3.381.0/実機ログで原因特定] **ハンドラ全体**を NonCancellable で包む。旧実装は
                //   `withContext(NonCancellable + Default)` を checker にだけ掛けており、**その直後に
                //   外側の（既にキャンセル済みの）コンテキストへ再開する時点で CancellationException が
                //   新たに投げられ**、続く `pushReport` と `logOp("停止: …")` が丸ごと飛んでいた。
                //   実害は2つ: ①停止の終端ログが残らない（実機ログ 3.378.0 で 11実行中4件が
                //   `最適化 終了: …いずれも記録されませんでした` になった＝3.372.0 のフォールバックが
                //   拾った正体） ②`_ui` に keep-best の結果が届かず、**画面は探索中の途中盤面の数字のまま**
                //   （データは kept に戻っているのに表示だけ食い違う＝このコメントが防ぐと謳っている
                //   まさにその不整合）。実機ログで裏取り: 11:48:38開始→11:48:57停止のあと、次の
                //   違反チェックが 必須=69 なのに停止直前の表示は 必須=3 だった。
                withContext(NonCancellable) {
                    val kept = sched0.copy2D()
                    val keptReport = withContext(Dispatchers.Default) {
                        UnifiedViolationChecker.check(st0, kept)
                    }
                    currentSchedule = kept
                    resultSchedule = kept
                    state = st0.withSchedule(kept)
                    // 診断(analyzeParallel)が落ちても終端ログだけは必ず残す（原因に依存しない保証）。
                    runCatching {
                        pushReport(state ?: st0, kept, keptReport, nonCancellable = true) { it.copy(
                            messageIsError = false,
                            running = false,
                            hasResult = true,
                            engineRan = true,
                            message = "停止しました。直前の勤務表（必須=${keptReport.hard} 合計=${keptReport.total}）を保持しています。",
                        ) }
                    }.onFailure { t ->
                        _ui.update { it.copy(running = false, hasResult = true, engineRan = true,
                            messageIsError = false,
                            message = "停止しました。直前の勤務表（必須=${keptReport.hard} 合計=${keptReport.total}）を保持しています。") }
                        logOp("W", "停止時の診断に失敗: ${t.javaClass.simpleName}: ${t.message}")
                    }
                    logOp("I", "停止: 直前の勤務表 必須=${keptReport.hard}/合計=${keptReport.total} を保持")
                    terminalLogged = true
                }
                throw e
            } catch (e: Throwable) {
                // [3.271.0, 実機ログ起因] 失敗を操作ログにも残す。旧: message のみのため、書き出した
                //   ログに「最適化 開始」だけあって完了も停止も無い実行が現れても死因（例外で静かに
                //   落ちたのか）を判別できなかった（実機ログ 19:56:41 の消えた実行の解析を阻んだ実例）。
                // [3.382.0] `Exception` → `Throwable`。旧は **`Error`（OutOfMemoryError/StackOverflowError 等）を
                //   1つも拾わず**、8ワーカー×300秒という重い経路でメモリ不足が起きると終端ログすら残らずに
                //   消えていた（3.381.0 で CancellationException 経路を塞いだあと、残っていた最後の穴）。
                //   **Error を再送出しない**のは意図的なトレードオフ: `viewModelScope.launch` の未捕捉例外は
                //   既定ハンドラでプロセスを落とすため、**その死因を説明する操作ログ（メモリ上のリング）ごと
                //   失われる**。ここで捕まえれば `OutOfMemoryError` と名指しした行が残り、書き出せる。
                //   状態の一貫性は保たれる（ViewModel の盤面は handleOptimize が値を返した**後**にしか
                //   書き換えず、この時点では未変更＝入力盤面のまま）。代償は「プロセス状態が不明なまま
                //   継続しうる」ことで、これは業務判断として受け入れる（利用者は続行/再起動を選べる）。
                val kind = if (e is Error) "重大なエラー(${e.javaClass.simpleName})" else e.javaClass.simpleName
                logOp("W", "最適化 失敗: $kind: ${e.message}")
                terminalLogged = true
                // [3.400.0] 画面には失敗の種類と次の一手だけ。内部名「V6」と生の例外文は直上の logOp へ
                //   （3.147.0/3.191.0 の「英字符号・内部名を画面に出さない」方針の取り残し）。
                _ui.update { it.copy(running = false, message = "勤務表をつくれませんでした（$kind）。もう一度お試しください（詳しくは設定＞詳細設定＞ログ）", messageIsError = true) }
            } finally {
                // [3.404.0] 途中経過の盤面を捨てる。旧: 完了時に消さないので、あとで編集して違反チェックが
                //   走ると（`ui.running` が再び真になり）**前の実行の古い途中経過が現在のものとして出た**。
                if (_ui.value.liveSchedule.isNotEmpty()) _ui.update { it.copy(liveSchedule = emptyList()) }
                clearRunMarker()  // 正常終了・停止・失敗いずれでもマーカーを消す（中断のみ残す）
                if (!terminalLogged) logOp("W", "最適化 終了: 完了・停止・失敗のいずれも記録されませんでした（想定外の経路。停止処理自体の失敗が疑われます）")
                // [3.409.0] endBoardJob は**終端ログより後**。旧: 先頭にあったため `activeRunSerial` が
                //   先に 0 へ戻り、この行だけ「実行外」と刻まれていた＝実行IDを最も必要とする診断
                //   （原因不明の終了）が、どの実行のものか分からない形で残っていた。
                endBoardJob(boardToken)   // [3.328.0/3.404.0] 盤面を差し替えるジョブの終了（正常・停止・失敗すべて）
                if (_ui.value.running) _ui.update { it.copy(running = false) }
            }
        }
    }

    /**
     * [ソフト研磨のみ] 現在の勤務表をHARDガード付きで局所研磨し、SOFT違反だけを削る。
     *   「もう一度つくる」と違い破壊/多様化を行わないため必須が一時的に増えることはなく、
     *   keep-best により入力より悪い結果は採用しない（HARD=0 を壊さない）。
     */
    fun runSoftPolish() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (runBlockedByInFlight("仕上げ最適化の開始")) return
        if (!ensureValidForRun(st0, sched0)) return
        pushUndo("仕上げ最適化")
        writeRunMarker("fg")   // [監査A8]
        _ui.update { it.copy(messageIsError = false, running = true, hasResult = false, liveSchedule = emptyList(), message = "自動で整えています…") }
        logOp("I", "ソフト研磨 開始 (予算${_ui.value.budgetSec}s)")
        val startMs = System.currentTimeMillis()
        val boardToken = beginBoardJob("仕上げ最適化", engineRun = true)   // [3.328.0/3.404.0]
        job = viewModelScope.launch {
            var terminalLogged = false   // [3.372.0] 終端ログの保証（runV6FullOptimize と同じ理由）
            try {
                val baseReport = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(st0, sched0) }
                val polished = withContext(Dispatchers.Default) {
                    V6NativeOptimizer.softPolishOnly(st0, sched0.copy2D(), _ui.value.budgetSec)
                }
                val polishedReport = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(st0, polished) }
                // softPolishOnly は退化防止済みだが、VM側でも入力以上のみ採用（保険）。
                // [3.289.0/外部レビューHigh] 判定を betterReport（hard→weightedScore→total）へ統一。
                //   旧: (hard, total) のみを見ており weightedScore を一切参照しなかったため、3.287.0 で
                //   keep-best が正しく採用するようになった「HARD同値・weighted改善・total増」の結果を
                //   この保険だけが「悪化」と誤判定して入力へ戻し、ソフト研磨に限り目的関数の統一を打ち消していた。
                val worse = betterReport(baseReport, polishedReport)
                val finalSched = if (worse) sched0.copy2D() else polished.copy2D()
                val finalReport = if (worse) baseReport else polishedReport
                currentSchedule = finalSched
                autoSave()
                resultSchedule = finalSched
                state = st0.withSchedule(finalSched)
                val gain = baseReport.total - finalReport.total
                // [3.475.0/論理監査] 文言は「採用したか（!worse）」で決める。旧: total の増減だけを見ていたため、
                //   3.287.0 の keep-best が正しく採用する「重み改善・total 増」の盤面を採用しながら
                //   「これ以上は整いませんでした／増減なし」と語っていた（盤面は変わっている）。
                val adopted = !worse
                // [design-review] この関数冒頭の開始メッセージは「自動で整えています…」なのに、完了だけ内部語
                //   「ソフト研磨」に戻っていた（同じ操作の中で語彙が食い違う＝3.397.0と同型）。
                //   operator_ux.md §2「最適化する/RunMAGI → 勤務表をつくる/いい感じに整える」に合わせる。
                pushReport(state ?: st0, finalSched, finalReport, runLabel = "仕上げ最適化") { it.copy(
                    messageIsError = false,
                    running = false,
                    hasResult = true,
                    engineRan = true,
                    runSummary = runSummaryOf(com.magi.app.v6.ChangeSummary.of(st0, sched0, finalSched, finalReport, baseReport)),
                    message = if (adopted)
                        "整えました: 合計 ${baseReport.total} → ${finalReport.total}（${if (gain >= 0) "-$gain" else "+${-gain}"}・重み ${baseReport.weightedScore.toInt()} → ${finalReport.weightedScore.toInt()}）必須=${finalReport.hard} (${System.currentTimeMillis() - startMs}ms)"
                    else
                        "これ以上は整いませんでした（合計=${finalReport.total} 必須=${finalReport.hard}）。残りは構造的要因の可能性。",
                ) }
                logOp("I", "ソフト研磨 完了 必須=${finalReport.hard} 合計=${finalReport.total}（${if (adopted) "採用: 合計${if (gain >= 0) "-$gain" else "+${-gain}"}・重み${baseReport.weightedScore.toInt()}→${finalReport.weightedScore.toInt()}" else "増減なし"}）")
                terminalLogged = true
            } catch (e: CancellationException) {
                // [停止 keep-best] 中断時は直前の確定盤面を保持し表示も整合させる。
                // [3.381.0] 最適化側と同型の修正＝ハンドラ全体を NonCancellable で包む（理由は同関数のコメント参照）。
                withContext(NonCancellable) {
                    val kept = sched0.copy2D()
                    val keptReport = withContext(Dispatchers.Default) {
                        UnifiedViolationChecker.check(st0, kept)
                    }
                    currentSchedule = kept
                    resultSchedule = kept
                    state = st0.withSchedule(kept)
                    runCatching {
                        pushReport(state ?: st0, kept, keptReport, nonCancellable = true) { it.copy(
                            messageIsError = false,
                            running = false,
                            hasResult = true,
                            engineRan = true,
                            message = "停止しました。直前の勤務表（必須=${keptReport.hard} 合計=${keptReport.total}）を保持しています。",
                        ) }
                    }.onFailure { t ->
                        _ui.update { it.copy(running = false, hasResult = true, engineRan = true,
                            messageIsError = false,
                            message = "停止しました。直前の勤務表（必須=${keptReport.hard} 合計=${keptReport.total}）を保持しています。") }
                        logOp("W", "停止時の診断に失敗: ${t.javaClass.simpleName}: ${t.message}")
                    }
                    // [3.372.0] 旧: この分岐だけ logOp が1つも無く、停止すると終端行がゼロになっていた
                    //   （最適化側の「停止: …を保持」に対する対象漏れ）。
                    logOp("I", "ソフト研磨 停止: 直前の勤務表 必須=${keptReport.hard}/合計=${keptReport.total} を保持")
                    terminalLogged = true
                }
                throw e
            } catch (e: Throwable) {
                // [3.382.0] 最適化側と同型＝`Error` も拾って終端ログを残す（理由は runV6FullOptimize のコメント）。
                val kind = if (e is Error) "重大なエラー(${e.javaClass.simpleName})" else e.javaClass.simpleName
                logOp("W", "ソフト研磨 失敗: $kind: ${e.message}")   // [3.271.0] 操作ログにも残す
                terminalLogged = true
                // [design-review] 生の例外文(e.message)を画面へ出していた（他の失敗ハンドラは全て
                //   $kind だけで、詳細は直上のlogOpへ＝3.400.0「画面には失敗の種類と次の一手だけ」の
                //   対象漏れ。operator_ux.md §6「生の例外文… を出していないか」）。
                _ui.update { it.copy(messageIsError = true, running = false, message = "整えられませんでした（$kind）。もう一度お試しください（詳しくは設定＞詳細設定＞ログ）") }
            } finally {
                if (_ui.value.liveSchedule.isNotEmpty()) _ui.update { it.copy(liveSchedule = emptyList()) }   // [3.404.0]
                clearRunMarker()   // [監査A8]
                if (!terminalLogged) logOp("W", "ソフト研磨 終了: 完了・停止・失敗のいずれも記録されませんでした（想定外の経路。停止処理自体の失敗が疑われます）")
                endBoardJob(boardToken)   // [3.328.0/3.404.0] 終端ログより後（実行IDを刻むため・3.409.0）
                if (_ui.value.running) _ui.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        job?.cancel(); checkJob?.cancel(); fixJob?.cancel()
        // [監査A2] バックグラウンド実行(WorkManager)も停止する。従来は前景jobのみで、bg中は
        //   停止ボタンが実質無効・runningが結果到着まで固着していた。
        val bgWasRunning = OptimizationRepository.running.value
        runCatching { androidx.work.WorkManager.getInstance(getApplication()).cancelUniqueWork(OptimizationWorker.UNIQUE) }
        if (bgWasRunning) {
            OptimizationRepository.clear()
            // [3.442.0/C1 の押した側] `clear()` は progress/result だけを落とし **running は落とさない**。
            //   Worker 側の解除（`releasedByMe`）は 3.441.0 時点で入っているが、`cancelUniqueWork()` は
            //   非同期で Worker が気づくまで時間がかかる＝その間 `OptimizationRepository.running` は true の
            //   ままなので `optimizeInFlight()`（編集ガード14箇所の根拠・3.328.0）が閉じ続ける。
            //   押した側でも降ろして窓を閉じる。Worker 側の解除と冪等（両方 false にするだけ）。
            //   置き換えで打ち切られた旧実行はここを通らない（`stop()` はユーザー操作のみ）＝
            //   新しい実行の running を落とす経路にはならない。
            OptimizationRepository.setRunning(false)
            clearBgFiles("停止（背景最適化の中断）")
            _ui.update { it.copy(messageIsError = false, running = false, message = "停止しました（バックグラウンド最適化を中断）") }
            logOp("I", "バックグラウンド最適化を停止")
        } else if (_ui.value.running || _ui.value.fixSearching) {
            // [3.284.0/外部レビューHigh③] 前景の違反チェック(checkJob)/改善探索(fixJob)を停止した場合、
            //   それらのコルーチンは running/fixSearching を戻す機会がなく実行中表示が固着していた。
            //   最適化ジョブ(job)自身は CancellationException 側で keep-best と running=false を行うため、
            //   ここでの即時リセットは冪等（後からジョブ側の確定メッセージが上書きする）。
            val wasFixSearching = _ui.value.fixSearching   // リセット前に取る（下の対象判定用）
            _ui.update { it.copy(messageIsError = false, running = false, fixSearching = false, message = "停止しました") }
            // [3.383.0/ユーザー指示「検証できないと見送った項目をログ強化」] **前景の停止だけログが無かった**
            //   （背景は「バックグラウンド最適化を停止」を出していた＝非対称）。3.381.0 で「4件の異常終了は
            //   停止を押した実行」と結論できたのは、直後にユーザーが編集を始めている、という**状況証拠から
            //   推論した**からで、ログには押した事実が1行も無かった。以後は直接読める。
            //   何を止めたかも区別する（最適化なのか、違反チェック/改善探索だけなのかで意味が全く違う）。
            val what = buildList {
                boardJobLabel?.let { add(it) }   // [3.404.0] 何のジョブかを名前で言う（旧: 一律「計算」）
                if (wasFixSearching) add("改善探索")
                if (isEmpty()) add("違反チェック")
            }.joinToString("・")
            logOp("I", "停止を押しました（対象: $what）")
        }
        clearRunMarker()
    }

    /** Shift indices a staff member may take (for the cell-edit bottom sheet). */
    // [メインスレッド負荷削減] cachedProblem を使用（兄弟の staffCellLimits/needCellLimits と統一）。
    //   本アクセサは StaffingRealityCard の `for i: allowedShiftsFor(i)` ループや ScheduleGrid の
    //   canDo ラムダ等、Compose の合成/再合成から O(職員数) 回呼ばれる。旧実装は呼び出し毎に
    //   Problem(st) を新規構築し（canDo/range/apt/wish 行列を毎回再割当）メインスレッドを浪費していた。
    //   Problem は state の純粋関数のため、state 参照で識別する ProblemCache のヒットに置換して等価かつ
    //   スコアリング不変（canDoShiftsForStaff は bucket を返す読み取り専用）。
    fun allowedShiftsFor(i: Int): IntArray {
        val st = state ?: return IntArray(0)
        return cachedProblem(st).canDoShiftsForStaff(i)   // [3.507.0] UI は担当可否そのもの（上限 0 は最適化器だけが除外）
    }

    /** 入力ガイド（月次/年次の入力手順）用の各項目の件数。 */
    data class SetupCounts(
        val days: Int, val staff: Int, val shifts: Int, val groups: Int,
        val wishes: Int, val needDay: Int, val constraints: Int, val ranges: Int, val use2: Boolean,
    )
    fun setupCounts(): SetupCounts {
        val st = state ?: return SetupCounts(0, 0, 0, 0, 0, 0, 0, 0, false)
        val cons = st.cons1.size + st.cons2.size + st.cons3.size + st.cons3n.size +
            st.cons3m.size + st.cons3mn.size + st.cons41.size + st.cons42.size + st.cons3w.size
        return SetupCounts(
            st.dayCount, st.staffCount, st.shiftCount, st.groupCount,
            st.wishes.size, st.needDay1.size + st.needDay2.size, cons, st.staffRange.size, st.use2Patterns,
        )
    }

    /** 担当外（そのスタッフのグループで担当不可）な希望の件数。希望で上書き時の確認に使う。 */
    fun wishOutOfScopeCount(): Int {
        val st = state ?: return 0
        val p = Problem(st)
        var n = 0
        for ((key, k) in st.wishes) {
            val i = key.split(',').getOrNull(0)?.toIntOrNull() ?: continue
            if (i in 0 until p.S && k in 0 until p.K && !p.canDo(i, k)) n++
        }
        return n
    }

    /**
     * 希望シフトを勤務表へ上書き反映（Web版の「希望で上書き」相当）。担当外の希望は
     * [includeOutOfScope]=true のときのみ反映。Undo・操作ログ付き。
     */
    fun applyWishes(includeOutOfScope: Boolean) {
        val st = state ?: return
        // [外部レビューH2] setCell/setCells/applyFixSuggestion と同じ理由(1617行のコメント参照)で、
        //   ここも currentSchedule を直接書き換える＝running中は最適化ジョブの sched0 と同一参照のため
        //   良化採用時に上書き消失しうる。3.328.0/3.161.0 の「編集は必ず4入口を通る」の対象漏れだった。
        if (optimizeInFlight()) { _ui.update { it.copy(message = busyEditMessage(), messageIsError = true) }; return }
        val sched = currentSchedule ?: return
        val p = Problem(st)
        pushUndo("希望を反映")
        var applied = 0
        var oos = 0
        for ((key, k) in st.wishes) {
            val parts = key.split(',')
            val i = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
            if (i !in 0 until p.S || j !in 0 until p.T || k !in 0 until p.K) continue
            val can = p.canDo(i, k)
            if (!can && !includeOutOfScope) continue
            if (i in sched.indices && j in sched[i].indices && sched[i][j] != k) {
                sched[i][j] = k
                applied++
                if (!can) oos++
            }
        }
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        val note = if (oos > 0) "（担当外 ${oos}件含む）" else ""
        logOp(if (oos > 0) "W" else "I", "希望を勤務表へ反映 ${applied}件$note")
        _ui.update { it.copy(
            messageIsError = false,
            hasResult = true,
            engineRan = false,   // [3.475.0] 手操作＝「計算済み」ではない
            schedule = sched.map { it.toList() },
            message = "希望を反映: ${applied}件$note",
        ) }
        refreshCheck()
    }

    private var alternativeScheds: List<Array<IntArray>> = emptyList()
    // [3.529.0/外部仕様書取り入れ] 「他の案」を計算したときの盤面/設定の指紋。fixBoardKey/fixStateKey と
    //   同じ手法（適用時に照合し、その間の手動編集による上書きを防ぐ）。旧: 次元（職員数×期間）しか
    //   見ておらず、同じ次元のままセルだけ編集した直後に別案を適用すると無警告でその編集を消していた。
    private var altBoardKey = 0L
    private var altStateKey = 0L

    /** 直近の並列最適化で得た「他の案」を取り込み、サマリをUIへ反映。 */
    /** [3.335.0/外部レビュー P1] 「他の案」は可変 static でなく `handleOptimize` の返り値から受け取る
     *  （実行が重なると static は新しい実行の値に置き換わり、別の実行の候補を掴み得た）。 */
    private suspend fun captureAlternatives(source: List<Array<IntArray>>) {
        val st = state ?: return
        val alts = source.map { it.copy2D() }
        alternativeScheds = alts
        altBoardKey = currentSchedule?.let { boardKey(it) } ?: 0L
        altStateKey = stateKey(st)
        // [Main負荷回避] 他案（最大3件）の違反チェックは同期CPU → Default で実行してから反映。
        val summaries = withContext(Dispatchers.Default) {
            alts.mapIndexed { idx, sch ->
                val rep = UnifiedViolationChecker.check(st, sch)
                "案${idx + 1}: 必須=${rep.hard} 合計=${rep.total}"
            }
        }
        _ui.update { it.copy(alternatives = summaries) }
    }

    /** 「他の案」を勤務表へ適用（Undo・操作ログ付き）。 */
    fun applyAlternative(i: Int) {
        val st = state ?: return
        // [外部レビューH2] applyWishes と同根＝currentSchedule/state を running 中に直接差し替えると
        //   最適化ジョブの完了時上書きと衝突しうる。
        if (optimizeInFlight()) { _ui.update { it.copy(message = busyEditMessage(), messageIsError = true) }; return }
        val sch = alternativeScheds.getOrNull(i)?.copy2D() ?: return
        // [3.475.0/論理監査] 案は計算時のデータの次元（職員数×期間）を持つ。旧: 別データの読込や職員の
        //   追加/削除のあとも案が残り、採用すると行数の違う盤面が state に入り自動保存され、次回起動で
        //   validate() に落ちて自動保存が開けなくなった。次元が合わなければ捨てる。
        if (sch.size != st.staffCount || sch.any { it.size != st.dayCount }) {
            alternativeScheds = emptyList()
            _ui.update { it.copy(alternatives = emptyList(), messageIsError = true,
                message = "この案は今のデータ（職員数・期間）と合わないため適用できません。もう一度つくってください") }
            logOp("W", "他の案 ${i + 1}: 職員数/期間が今のデータと違うため適用せず")
            return
        }
        // [3.529.0/外部仕様書取り入れ] 次元は合っていても、案を計算した後に盤面/設定が変わっていれば拒否。
        val curSched = currentSchedule
        if (altBoardKey != 0L && curSched != null && (altBoardKey != boardKey(curSched) || altStateKey != stateKey(st))) {
            alternativeScheds = emptyList()
            _ui.update { it.copy(alternatives = emptyList(), messageIsError = true,
                message = "その後に勤務表か設定が変わったため、この案は適用できません。もう一度計算してください") }
            logOp("W", "他の案 ${i + 1}: 案の計算後に盤面/設定が変わったため適用せず")
            return
        }
        // [3.529.0/外部仕様書取り入れ] pushUndo() が alternativeScheds/ui.alternatives を丸ごと外す
        //   （残りの案も今適用した盤面に対しては古いため。fixSuggestions と同じ形）。
        pushUndo("他の案 ${i + 1} を適用")
        currentSchedule = sch
        resultSchedule = sch
        state = st.withSchedule(sch)
        autoSave()
        // [3.475.0/論理監査] 再検査は checkJob/checkSeq に乗せる。旧: 独立した launch で順序保証が無く、
        //   案1→案2 と続けて押すと先の案の報告が後から届いて画面と currentSchedule が食い違った。
        val seq = ++checkSeq
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            // [3.392.0] 盤面は launch の前に既に差し替わっている。ここが例外で落ちると報告だけ届かず
            //   「盤面は変わったのに違反数は前の案のまま」になるので、必ず理由を残す。
            try {
                val rep = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(state ?: st, sch) }
                if (seq != checkSeq) return@launch
                pushReport(state ?: st, sch, rep) { it.copy(messageIsError = false, hasResult = true, engineRan = true, message = "他の案 ${i + 1} を適用") }
                logOp("I", "他の案 ${i + 1} を適用 必須=${rep.hard} 合計=${rep.total}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logOp("W", "他の案 ${i + 1} の適用後の再チェックに失敗: ${e.javaClass.simpleName}（盤面は適用済み・違反数は古い可能性）")
                _ui.update { it.copy(messageIsError = true, message = "他の案 ${i + 1} を適用（違反数の再計算に失敗）") }
            }
        }
    }

    /** Set a specific shift in a cell (bottom-sheet picker). */
    /**
     * [3.500.2/外部レビュー] 盤面に書けるシフト index か（-1＝未割当は可、それ以外は 0 until shiftCount）。setCell/setCells の共通ガード。
     * 旧: 上限を見ておらず、古い UI イベントからの範囲外 index が自動保存まで通り、再検査で初めて失敗していた。
     */
    private fun rejectUnknownShift(st: MagiState, shift: Int): Boolean {
        if (shift >= -1 && shift < st.shiftCount) return false
        _ui.update { it.copy(messageIsError = true, message = "選択したシフトは現在の設定に存在しません") }
        return true
    }

    fun setCell(i: Int, j: Int, shift: Int) {
        val st = state ?: return
        // [監査(未レビュー領域再監査) 実バグ修正] running中は currentSchedule が最適化ジョブの sched0 と
        //   同一の配列参照＝ここで in-place 変更すると、完了時の baseReport(旧盤面基準)と食い違うか、
        //   良化採用時に編集が無言で上書き消失する。ジョブ完了まで編集を拒否する（他の直接変異API=
        //   setCells/applyFixSuggestion も同根のため同じガードを持つ）。
        if (optimizeInFlight()) { _ui.update { it.copy(message = busyEditMessage(), messageIsError = true) }; return }
        val sched = currentSchedule ?: return
        if (i !in sched.indices || j !in sched[i].indices) return
        if (rejectUnknownShift(st, shift)) return
        if (sched[i][j] == shift) return
        pushUndo("セル編集")
        sched[i][j] = shift
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.update { it.copy(
            messageIsError = false,
            hasResult = true,
            engineRan = false,   // [3.475.0] 手操作＝「計算済み」ではない
            schedule = sched.map { it.toList() },
            message = "${st.staff.getOrNull(i)?.name ?: i} / ${j + 1}日 を ${st.shifts.getOrNull(shift)?.kigou ?: shift} に変更",
        ) }
        logOp("I", "編集: ${opNm(i)} ${j + 1}日 → ${opSy(shift)}")
        refreshCheck()
    }

    /** [プロ一括編集] 複数セル(i,j)を1シフトへ一括設定。Undoは1回・再チェックも1回（keep-best互換）。 */
    fun setCells(cells: Collection<Pair<Int, Int>>, shift: Int) {
        val st = state ?: return
        if (optimizeInFlight()) { _ui.update { it.copy(message = busyEditMessage(), messageIsError = true) }; return }
        val sched = currentSchedule ?: return
        if (rejectUnknownShift(st, shift)) return
        var changed = 0
        var first = true
        for ((i, j) in cells) {
            if (i !in sched.indices || j !in sched[i].indices) continue
            if (sched[i][j] == shift) continue
            if (first) { pushUndo("複数セルの一括編集"); first = false }
            sched[i][j] = shift
            changed++
        }
        if (changed == 0) return
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.update { it.copy(
            messageIsError = false,
            hasResult = true,
            engineRan = false,   // [3.475.0] 手操作＝「計算済み」ではない
            schedule = sched.map { it.toList() },
            message = "${changed}マスを ${st.shifts.getOrNull(shift)?.kigou ?: shift} に一括変更",
        ) }
        logOp("I", "一括編集: ${changed}マス → ${opSy(shift)}")
        refreshCheck()
    }

    /** [operator_ux §5] 「なおすのを手伝って」用：ある不足枠(日×シフト)に1タップで入れられる候補職員。 */
    data class FixCandidate(val staffIndex: Int, val name: String, val groupSymbol: String, val fromRest: Boolean)
    fun shortageFixCandidates(dayIndex: Int, shiftIndex: Int): List<FixCandidate> {
        val st = state ?: return emptyList()
        val sched = currentSchedule ?: return emptyList()
        val p = Problem(st)
        if (shiftIndex !in 0 until p.K || dayIndex !in 0 until p.T) return emptyList()
        val rest = restShiftIndex(st)   // [監査A5] 休は記号解決（raw"休"比較は「公」職場で全滅していた）
        val out = ArrayList<FixCandidate>()
        for (i in 0 until p.S) {
            if (i !in sched.indices || dayIndex !in sched[i].indices) continue
            // 担当できない・上限 0 のシフトは出さない（最適化器・FixSuggester・Sanity 代用要員と同じ mayPlace 基準）。
            if (!p.mayPlace(i, shiftIndex)) continue
            if (sched[i][dayIndex] == shiftIndex) continue   // すでにそのシフト
            // [監査A5] 実現可能な希望のみ固定扱い（#11①整合: 不可能希望のセルはエンジン同様に可動）。
            if (p.wishLocked(i, dayIndex) && p.wish[i][dayIndex] != shiftIndex) continue
            // [3.401.0] ここまでは「担当できる・希望で固定されていない」だけの判定で、押しても必須違反が
            //   減らない候補が混ざっていた。CoverageDiagnosis(3.156.0) が「空き番」と数えるのと同じ2条件を
            //   足して、**実際に動かせる人だけ**を出す。
            //   ① 移すと禁止連続(c3n)になる人は出さない。
            if (p.makesForbiddenRun(sched, i, dayIndex, shiftIndex)) continue
            //   ② 抜けると元のシフトに穴が空く人は「動かせる人」ではない（玉突きが要る＝この画面の手には余る）。
            val from = sched[i][dayIndex]
            if (from in 0 until p.K) {
                val cnt = (0 until p.S).count { it in sched.indices && dayIndex in sched[it].indices && sched[it][dayIndex] == from }
                if (p.covUCell(from, dayIndex, cnt - 1) > p.covUCell(from, dayIndex, cnt)) continue
            }
            val g = st.staff.getOrNull(i)?.groupIdx ?: -1
            out.add(FixCandidate(i, st.staff.getOrNull(i)?.name ?: "#$i", st.groups.getOrNull(g)?.kigou ?: "", sched[i][dayIndex] == rest))
        }
        // 休みの人（動かしやすい）を先頭に。
        out.sortBy { if (it.fromRest) 0 else 1 }
        return out
    }

    // [D7撤去] hintReadOnly（読取モードの案内）は読取モード撤去に伴い削除（UI 参照ゼロ）。

    // ---- constraint editing (ws3-5) -------------------------------------------

    /** A constraint family with its rows rendered for display (key used for add/remove).
     *  [3.427.0] 旧 `subs`（行ごとの読み下し文）は撤去: ペア禁止系の行タイトル自体を読める形
     *  （「吉の休 ✕ 古の休」）にしたため、行＋文の二重表示（3.409.18）が冗長になった。 */
    data class ConstraintFamilyView(val key: String, val title: String, val rows: List<String>)

    fun shiftKigouList(): List<String> = state?.shifts?.map { it.kigou } ?: emptyList()

    // ---- [見直し候補] 月次の修正から「基本ルールの見直し候補」を積む軽量メモ（セッション内のみ・state 非保存） ----
    fun addReviewMemo(text: String) {
        if (text.isBlank()) return
        _ui.update { it.copy(messageIsError = false, reviewMemos = it.reviewMemos + text.trim(), message = "見直し候補に追加しました") }
    }
    fun removeReviewMemo(index: Int) {
        _ui.update { val l = it.reviewMemos; if (index !in l.indices) it else it.copy(reviewMemos = l.filterIndexed { j, _ -> j != index }) }
    }

    /** Apply an edited state (constraints changed), then re-run the unified check on the current table. */
    /**
     * [3.328.0/外部レビュー・実行中編集] 意味論を変える編集が最適化の最中に入るのを止める。
     *
     * 個々の編集画面へ `!ui.running` を配るやり方は、これまで何度も取りこぼしてきた
     * （3.161.0＝セル編集・3.127.0＝一括シート）。編集は最終的に必ず
     * [applyStructure] / [applyStructureWithMessage] / [mutateConstraints] のどれかを通るので、
     * **その3つの入口だけ**を塞ぐ。呼び出し元が先にログを出していることがあるので、
     * 取り消したことも記録して読み手が混乱しないようにする。
     */
    /**
     * [3.383.0/ユーザー指示「検証できないと見送った項目をログ強化」] 実行中に別の実行を頼まれて
     * **黙って無視した**ことを記録する。旧: 5つの入口が `if (optimizeInFlight()) return` で無言に落ちており、
     * 書き出したログには**そのボタンを押した痕跡が一切残らなかった**（利用者から見れば「押したのに何も
     * 起きない」、解析する側から見れば「実行が重なったのか単に押していないのか区別できない」）。
     * 兄弟の `structuralEditBlocked`（3.328.0）は同じ状況を既にログしており、こちらが対象漏れだった。
     */
    private fun runBlockedByInFlight(what: String): Boolean {
        if (!optimizeInFlight()) return false
        logOp("W", "$what を取り消しました（${busyWhat()}が実行中）")
        _ui.update { it.copy(messageIsError = true, message = "${busyWhat()}の実行中です。終わるか「やめる」を押してからにしてください。") }
        return true
    }

    /**
     * [3.405.0] 盤面セルを編集できない状態なら、理由を出して true を返す。**画面がシートを開く前に
     * 同じ判定を使う**ためのもの（`setCell` 等が使う文言と1文字も違わないよう同じ定数を読む）。
     * 旧: セルはいつでもタップでき、シートは「タップで割当を即変更。」と言い切ってから拒否していた＝
     * **形が約束したことを守れていなかった**。開かなければ約束は嘘にならない。
     */
    fun editBlockedNow(): Boolean {
        if (!optimizeInFlight()) return false
        _ui.update { it.copy(message = busyEditMessage(), messageIsError = true) }
        return true
    }

    private fun busyEditMessage(): String = "${busyWhat()}の実行中は編集できません（完了後にもう一度お試しください）"

    private fun structuralEditBlocked(): Boolean {
        if (!optimizeInFlight()) return false
        logOp("W", "${busyWhat()}の実行中のため設定変更を取り消しました（終わってから、または「やめる」の後にどうぞ）")
        _ui.update { it.copy(messageIsError = true, message = "${busyWhat()}の実行中は設定を変更できません。終わるか「やめる」を押してからにしてください。") }
        return true
    }

    internal fun mutateConstraints(newState: MagiState?) {
        val ns = newState ?: return
        if (structuralEditBlocked()) return
        pushUndo("制約の変更")
        state = ns
        // [3.222.0, 実機バグ修正「回避の並びなどが削除できない」] constraintsEdited が既に true だと
        //   copy が同値でStateFlowがemitせず（3.185.0/3.189.0と同型）、ConstraintsCard/SkillConstraintsCard
        //   を包む key(ui.editRev) が再構成されず一覧が更新されなかった。editRev を必ず増やして
        //   distinct な UiState を emit する（3.185.0のapplyStructureと同一パターン）。
        _ui.update { it.copy(constraintsEdited = true, editRev = it.editRev + 1) }
        refreshCheck()
        autoSave()
    }

    // ---- ws1 initial setup ----------------------------------------------------

    /** Snapshot of the ws1 (初期設定) data for the editor. Recomputed per call (cheap). */
    data class Ws1View(
        val startDate: String, val endDate: String, val days: Int, val use2: Boolean,
        val shifts: List<Shift>, val groups: List<Group>, val staff: List<Staff>,
        val groupShift: List<List<Int>>,
        val groupShiftApt: List<List<String>>,
    )

    fun ws1(): Ws1View? {
        val st = state ?: return null
        val days = currentSchedule?.firstOrNull()?.size ?: st.dayCount
        return Ws1View(st.startDate, st.endDate, days, st.use2Patterns, st.shifts, st.groups, st.staff, st.groupShift, st.groupShiftApt)
    }

    internal fun applyStructure(ns: MagiState) {
        if (structuralEditBlocked()) return
        pushUndo("設定の変更")
        state = ns
        // [再構成保証] editRev を必ず増やして distinct な UiState を emit（structureEdited 既true時の非emit＋
        //   currentSchedule=null 時の refreshCheck 早期return で編集画面が再構成されない「+/-で数字が変わらない」修正）。
        _ui.update { it.copy(structureEdited = true, editRev = it.editRev + 1) }
        refreshCheck()
        autoSave()
    }

    /**
     * 構造変更(ns)を適用し、再チェック後に独自の完了メッセージを表示（コンポーネント別取込・apt全リセット等で使用）。
     * [ドッグフーディング/3.466.0] 兄弟の `applyStructure(ns: MagiState)` は editRev を必ず増やす（3.189.0の
     * 「+/-で数字が変わらない」修正＝`key(ui.editRev)` で包まれた CollapsibleSection 配下は editRev の増分だけが
     * 再構成を確実に伝える）が、この関数だけ取り残されていた。呼出元 `ws1ResetGroupApt`（`AptSection`＝
     * `CountsCard`＝`key(ui.editRev)` 配下）が対象＝同じ穴を踏む。
     */
    internal fun applyStructureWithMessage(ns: MagiState, doneMessage: String) {
        if (structuralEditBlocked()) return
        pushUndo(doneMessage)
        state = ns
        autoSave()
        val sched = currentSchedule?.copy2D()
        if (sched == null) { _ui.update { it.copy(messageIsError = false, structureEdited = true, editRev = it.editRev + 1, message = doneMessage) }; return }
        val seq = ++checkSeq
        checkJob?.cancel()
        _ui.update { it.copy(messageIsError = false, running = true, structureEdited = true, editRev = it.editRev + 1, message = "$doneMessage（違反チェック中…）") }
        checkJob = viewModelScope.launch {
            try {
                val r = V6FinalPort.handleCheck(ns, sched)
                if (seq != checkSeq) return@launch
                pushReport(ns, r.schedule, r.report) { it.copy(messageIsError = false, running = optimizeInFlight(), message = "$doneMessage｜必須=${r.report.hard} 合計=${r.report.total}") }
            } catch (e: CancellationException) {
                // [3.284.0/外部レビューHigh③] stop() によるキャンセル時の running 固着を解消（refreshCheck と同型）。
                if (seq == checkSeq) _ui.update { it.copy(messageIsError = false, running = optimizeInFlight(), message = "$doneMessage（チェックを停止）") }
                throw e
            } catch (e: Throwable) {
                // [3.392.0] refreshCheck と同型。Error でも running を戻す（固着でアプリが読取専用になるため）。
                if (seq == checkSeq) _ui.update { it.copy(messageIsError = true, running = optimizeInFlight(), message = "$doneMessage（チェック失敗: ${e.javaClass.simpleName}）") }
            }
        }
    }

    /**
     * [ワンタップ修正] 設定の見直しカードの1ボタンで、該当する設定ミスをその場で直す。
     * 画面遷移・スクロール・行探し不要。applyStructure 経由なので Undo 可・自動再診断・自動保存される。
     */
    /**
     * [壁になっている禁止の並びを緩める] ForbiddenDiag が「崩せない」と判定した禁止連続(c3n)ルールを、
     * その場で削除する。制約画面まで行って該当行を探す必要をなくすための導線。
     *
     * データを変えるのは**利用者の明示操作**（HF77 に抵触しない）。`applyStructure` 経由なので
     * Undo 可・自動再診断・自動保存される。同じ並びが重複登録されている場合は全件まとめて消す
     * （1件だけ残ると壁が解消しないため。cons3n の重複は既知＝設定ミス診断でも指摘される）。
     *
     * キーは `Problem.resolveC3` と同じ意味論（**最初の空白まで**を本体とする）で作る。
     * `SettingFixAction.DELETE_DUP_SEQ` の空白除去とは意味が違うので流用しない。
     */
    /**
     * [目標の検算] シフトごとの「適切回数(apt)の合計 vs それを受け止められる上限」。
     *
     * `V6SanityPort.aptBalances` をそのまま返す＝設定ミス診断（検査6-C）と**同じ単一ソース**。
     * 盤面を参照しないので、勤務表を作る前（未計算）でも目標を触るたびに正しい値が出る
     * （`settingIssues` は `refreshCheck` 経由＝盤面が無いと更新されないため、設定中は届かない）。
     */
    fun aptBalances(): List<V6SanityPort.AptBalance> {
        val st = state ?: return emptyList()
        return runCatching { V6SanityPort.aptBalances(st) }.getOrDefault(emptyList())
    }

    fun relaxForbiddenRule(seqLabel: String) {
        if (optimizeInFlight()) { _ui.update { it.copy(messageIsError = true, message = "${busyWhat()}の実行中は設定を変更できません（完了後にもう一度お試しください）") }; return }
        val s = state ?: return
        fun key(row: C3Row): String {
            val end = row.pattern.indexOfFirst { it.isBlank() }
            val body = if (end >= 0) row.pattern.subList(0, end) else row.pattern
            return body.joinToString("→")
        }
        val remain = s.cons3n.filter { key(it) != seqLabel }
        val removed = s.cons3n.size - remain.size
        if (removed == 0) {
            _ui.update { it.copy(messageIsError = true, message = "禁止の並び「$seqLabel」は見つかりませんでした") }
            return
        }
        logOp("I", "禁止の並びを削除: $seqLabel（${removed}件）")
        applyStructureWithMessage(s.copy(cons3n = remain), "禁止の並び「$seqLabel」を削除しました（${removed}件・元に戻せます）")
    }

    fun applySettingFix(issue: SettingIssue) {
        val s = state ?: return
        val ns: MagiState? = when (issue.action) {
            SettingFixAction.REMOVE_WISH -> {
                val key = issue.wishKey ?: return
                if (!s.wishes.containsKey(key)) return
                s.copy(wishes = s.wishes - key)
            }
            SettingFixAction.DELETE_DUP_SEQ -> {
                val fam = issue.seqFamily ?: return
                val key = issue.seqKey ?: return
                fun delOne(rows: List<C3Row>): List<C3Row> {
                    var done = false
                    val res = ArrayList<C3Row>(rows.size)
                    for (row in rows) {
                        val joined = row.pattern.filter { it.isNotBlank() }.joinToString("→")
                        if (!done && joined == key) { done = true; continue }
                        res.add(row)
                    }
                    return res
                }
                when (fam) {
                    "c3" -> s.copy(cons3 = delOne(s.cons3))
                    "c3n" -> s.copy(cons3n = delOne(s.cons3n))
                    "c3m" -> s.copy(cons3m = delOne(s.cons3m))
                    "c3mn" -> s.copy(cons3mn = delOne(s.cons3mn))
                    else -> return
                }
            }
            SettingFixAction.ZERO_RANGE_LO, SettingFixAction.CLAMP_RANGE_LO -> {
                val key = issue.rangeKey ?: return
                val cur = s.staffRange[key] ?: Range("", "")
                s.copy(staffRange = s.staffRange + (key to Range(issue.newLo ?: cur.lo, cur.hi)))
            }
            SettingFixAction.CLAMP_GROUP_RANGE_LO -> {
                // 行は List なので index でなく**内容一致**で指す（DELETE_DUP_SEQ と同じ理由＝診断から
                //   タップまでに並びが変わっても別の行を壊さない）。同じ内容が複数あるときは先頭1件だけ直す。
                val row = issue.groupRangeRow ?: return
                val lo = issue.newLo ?: return
                fun clampOne(rows: List<C41Row>): List<C41Row> {
                    val i = rows.indexOf(row)
                    if (i < 0) return rows
                    return rows.toMutableList().also { it[i] = row.copy(l = lo) }
                }
                when (issue.groupRangeFamily) {
                    "c41" -> s.copy(cons41 = clampOne(s.cons41))
                    "c41s" -> s.copy(cons41s = clampOne(s.cons41s))
                    else -> return
                }
            }
            SettingFixAction.CAP_DEMAND -> {
                val k = issue.demandShiftIdx ?: return
                val cap = issue.demandCap ?: return
                val sh = s.shifts.getOrNull(k) ?: return
                val j = issue.demandDayIdx
                if (j != null) {
                    // [3.475.0/論理監査] 需要が日別例外(needDay1/2)由来のときはその日の例外を丸める。
                    //   旧: 常にシフト既定だけを丸めていたため、例外由来の診断はボタンを押しても消えなかった
                    //   （既定が cap 以下なら no-op で return、例外は残るので同じ項目が出続けていた）。
                    val key1 = "$k,$j"; val key2 = "$k,$j"
                    val ov1 = s.needDay1[key1]?.trim()?.toIntOrNull()
                    val ov2 = s.needDay2[key2]?.trim()?.toIntOrNull()
                    var changed = false
                    val nd1 = if (ov1 != null && ov1 > cap) { changed = true; s.needDay1 + (key1 to cap.toString()) } else s.needDay1
                    val nd2 = if (ov2 != null && ov2 > cap) { changed = true; s.needDay2 + (key2 to cap.toString()) } else s.needDay2
                    // 例外が無ければ既定を丸める（従来どおり）。
                    val n1 = sh.need1.trim().toIntOrNull()
                    val n2 = sh.need2.trim().toIntOrNull()
                    if (ov1 == null && n1 != null && n1 > cap) { changed = true }
                    if (ov2 == null && n2 != null && n2 > cap) { changed = true }
                    if (!changed) return
                    val newN1 = if (ov1 == null && n1 != null && n1 > cap) cap.toString() else sh.need1
                    val newN2 = if (ov2 == null && n2 != null && n2 > cap) cap.toString() else sh.need2
                    val list = s.shifts.toMutableList()
                    list[k] = sh.copy(need1 = newN1, need2 = newN2)
                    s.copy(shifts = list, needDay1 = nd1, needDay2 = nd2)
                } else {
                    val n1 = sh.need1.trim().toIntOrNull()
                    val n2 = sh.need2.trim().toIntOrNull()
                    val newN1 = if (n1 != null && n1 > cap) cap.toString() else sh.need1
                    val newN2 = if (n2 != null && n2 > cap) cap.toString() else sh.need2
                    if (newN1 == sh.need1 && newN2 == sh.need2) return
                    val list = s.shifts.toMutableList()
                    list[k] = sh.copy(need1 = newN1, need2 = newN2)
                    s.copy(shifts = list)
                }
            }
            SettingFixAction.NONE -> null
        }
        if (ns != null) {
            logOp("I", "設定ミスの修正を適用: ${issue.action} @ ${issue.where}")
            applyStructure(ns)
        }
    }

    /**
     * [3.480.0 ホームAIリデザイン] 「担当外の希望」を一括クリア。SettingIssuesCard の一括ボタン用。
     * [IssueKind.WISH かつ REMOVE_WISH] の行だけを対象にする（他種別は行ごとにデータの形が違い一括の
     * 意味が薄いため対象外＝ユーザー提示案の「担当外の希望を一括クリア」という限定範囲に忠実）。
     * applySettingFix を件数ぶん繰り返し呼ぶと undo が件数ぶん積み・再検査(refreshCheck)も件数ぶん
     * 走ってしまうため、1回の state 差し替え・1回の undo・1回の再検査へまとめる。
     */
    fun clearOutOfScopeWishes() {
        val s = state ?: return
        // [3.502.0/バックログ#9(d)] 対象はいまの設定で担当できないシフトへの希望（Problem.canDo＝wishOutOfScopeCount と同じ判定）。
        //   旧: 非同期の診断(settingIssues)に残るキーを消していた＝設定変更直後は古い診断で、いまは担当可能な希望まで消し得た。
        val p = Problem(s)
        val keys = s.wishes.entries.filter { (key, k) ->
            val i = key.substringBefore(",").toIntOrNull() ?: return@filter false
            i in 0 until p.S && k in 0 until p.K && !p.canDo(i, k)
        }.map { it.key }.toSet()
        if (keys.isEmpty()) return
        logOp("I", "担当外の希望を一括クリア: ${keys.size}件")
        applyStructure(s.copy(wishes = s.wishes - keys))
    }

    /**
     * [改善提案] 違反を減らす「1手（変更/交換）」を探索して UI に提示する。
     * focusStaff != null のときはそのスタッフが関わる手だけに絞る（違反タップ起点）。重い処理のため非同期。
     */
    /** [3.475.0/論理監査] 改善提案を計算した盤面/設定の指紋（適用時に照合する。0=未計算）。 */
    private var fixBoardKey = 0L
    private var fixStateKey = 0L

    fun findFixSuggestions(focusStaff: Int? = null, focusShift: Int? = null) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        val focusName = focusStaff?.let { st.staff.getOrNull(it)?.name } ?: ""
        val snap = sched.copy2D()
        fixBoardKey = boardKey(snap)
        fixStateKey = stateKey(st)
        // [3.392.0] 旧実装は catch が1つも無く、探索が例外で終わると `fixSearching=true` が**永久に残った**
        //   （「直し方を探す」が探索中のまま戻らない）。3.382.0 が長い4経路で潰した「旗を立てて確実に戻さない」
        //   型の残り。`seq` を持つのは refreshCheck と同じ理由＝`cancel()` は非同期なので、後続の探索が
        //   `fixSearching=true` を立てた**後**に古いジョブの後始末が走ると、新しい探索の旗を消してしまう。
        val seq = ++fixSeq
        fixJob?.cancel()   // 連続タップ時の前探索を破棄（古い結果で UI を上書きしない）
        _ui.update { it.copy(fixSearching = true, fixFocusName = focusName) }
        fixJob = viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.Default) {
                    FixSuggester.suggest(st, snap, focusStaff = focusStaff, focusShift = focusShift, maxResults = 8)
                }
                if (seq != fixSeq) return@launch   // 後続の探索が始まっている＝古い結果で上書きしない
                _ui.update { it.copy(fixSuggestions = list, fixSearching = false, fixFocusName = focusName) }
            } catch (e: CancellationException) {
                if (seq == fixSeq) _ui.update { it.copy(fixSearching = false) }
                throw e
            } catch (e: Throwable) {
                logOp("W", "直し方の探索に失敗: ${e.javaClass.simpleName}: ${e.message}")
                if (seq == fixSeq) _ui.update { it.copy(messageIsError = false, fixSearching = false, message = "直し方を探せませんでした") }
            }
        }
    }

    /** [改善提案] 改善手を1タップで適用（ops のセル代入を一括反映）。Undo 可・自動再診断・自動保存。 */
    fun applyFixSuggestion(s: FixSuggestion) {
        val st = state ?: return
        if (optimizeInFlight()) { _ui.update { it.copy(message = busyEditMessage(), messageIsError = true) }; return }
        val sched = currentSchedule ?: return
        if (s.ops.isEmpty()) return
        // [3.475.0/論理監査] 提案は計算時の盤面/設定に対する差分。旧: 指紋を持たず、その後のセル編集・
        //   元に戻す・別データ読込・職員削除のあとでも同じ ops をそのまま書き込んでいた（staff/day/toShift が
        //   別の実体を指す）。一致しなければ適用せず再探索を促す。toShift の上限（K）も未検査だった。
        if (fixBoardKey != 0L && (fixBoardKey != boardKey(sched) || fixStateKey != stateKey(st))) {
            _ui.update { it.copy(messageIsError = true, fixSuggestions = emptyList(),
                message = "勤務表か設定が変わったため、この提案は適用できません。「直し方を探す」をもう一度押してください") }
            return
        }
        // [3.509.4/自動化方針] 適用直前に仮盤面で完全再評価し、辞書式で改善しない・固定を崩す提案は反映しない。
        val gate = com.magi.app.v6.FixApplyGate.apply(st, sched, s.ops)
        if (gate !is com.magi.app.v6.FixApplyGate.Outcome.Applied) {
            val why = (gate as com.magi.app.v6.FixApplyGate.Outcome.Rejected).reason
            logOp("W", "改善手を見送り: ${s.label}（$why）")
            _ui.update { it.copy(messageIsError = true, fixSuggestions = emptyList(),
                message = "この提案は見送りました（$why）。「直し方を探す」で探し直してください") }
            return
        }
        pushUndo(s.label)
        val applied = gate.schedule
        currentSchedule = applied
        state = st.withSchedule(applied)
        autoSave()
        _ui.update { it.copy(
            messageIsError = false,
            hasResult = true,
            engineRan = false,   // [3.475.0] 提案の適用は手操作扱い（局所1手のみ、フルの計算ではない）
            schedule = applied.map { it.toList() },
            fixSuggestions = emptyList(),   // 適用後は候補をクリア（盤面が変わるため再探索を促す）
            message = "改善手を適用: ${s.label}（必須 ${gate.before.hard}→${gate.after.hard}・合計 ${gate.before.total}→${gate.after.total}）",
        ) }
        refreshCheck()
    }

    internal fun applyStructure(r: Ws1Result) {
        if (structuralEditBlocked()) return
        pushUndo("編集タブの変更")
        state = r.state
        // [review 4b] Ws1Result の schedule を防御コピーして取り込む。Undo は pushUndo() の
        // 事前クローンで保護されるが、currentSchedule を以降 in-place 編集する経路があるため、
        // 全 schedule 取り込み口を copy2D() で統一して別名共有を断つ。
        currentSchedule = r.schedule.copy2D()
        _ui.update { it.copy(structureEdited = true, editRev = it.editRev + 1) }
        refreshCheck()
        autoSave()
    }

    /** Ws1Result(状態+勤務表)を適用し、再チェック後に独自メッセージを表示（スタッフ新規追加など行数変化を伴う取込）。 */
    internal fun applyStructureWithMessage(r: Ws1Result, doneMessage: String) {
        // [3.404.0] 3.328.0 は「編集は必ずこの4入口を通るのでその4つだけを塞ぐ」としたが、**ここだけ
        //   ガードが無かった**。通るのは apt全リセットと職員一覧CSV取込で、後者は `currentSchedule` ごと
        //   差し替えるため、最適化中に到達すると 3.161.0 の「別名共有で編集が消える」クラスに触れる。
        if (structuralEditBlocked()) return
        pushUndo(doneMessage)
        state = r.state
        val sched = r.schedule.copy2D()
        currentSchedule = sched
        autoSave()
        val seq = ++checkSeq
        checkJob?.cancel()
        // [ドッグフーディング/3.466.0] 兄弟の `applyStructure(r: Ws1Result)` と同じく editRev を増やす（理由は
        //   上の (ns: MagiState) 版のコメント参照）。
        _ui.update { it.copy(messageIsError = false, running = true, structureEdited = true, editRev = it.editRev + 1, message = "$doneMessage（違反チェック中…）") }
        checkJob = viewModelScope.launch {
            try {
                val rep = V6FinalPort.handleCheck(r.state, sched)
                if (seq != checkSeq) return@launch
                pushReport(r.state, rep.schedule, rep.report) { it.copy(messageIsError = false, running = optimizeInFlight(), message = "$doneMessage｜必須=${rep.report.hard} 合計=${rep.report.total}") }
            } catch (e: CancellationException) {
                // [3.284.0/外部レビューHigh③] stop() によるキャンセル時の running 固着を解消（refreshCheck と同型）。
                if (seq == checkSeq) _ui.update { it.copy(messageIsError = false, running = optimizeInFlight(), message = "$doneMessage（チェックを停止）") }
                throw e
            } catch (e: Throwable) {
                // [3.392.0] refreshCheck と同型。Error でも running を戻す（固着でアプリが読取専用になるため）。
                if (seq == checkSeq) _ui.update { it.copy(messageIsError = true, running = optimizeInFlight(), message = "$doneMessage（チェック失敗: ${e.javaClass.simpleName}）") }
            }
        }
    }

    /** [窓ハイライト③] セル(i,j)の違反が c1/c3/c3m のとき、その違反が指す窓/連の範囲(開始日..終了日)を返す。
     *  c1=最初に不足している窓 / c3・c3m=複数シフト窓なら未完成パターンの窓、単一シフト連なら連の実範囲。
     *  該当なし・他族は null（読み取り専用・表示のみ）。 */
    fun violationRange(i: Int, j: Int): Pair<Int, Int>? {
        val st = state ?: return null
        val sched = currentSchedule ?: return null
        val cls = _ui.value.violationCells["$i,$j"] ?: return null
        val p = com.magi.app.v6.cachedProblem(st)
        if (i !in 0 until p.S || j !in 0 until p.T) return null
        when (cls) {
            "vio-c1" -> for (c in p.cons1) {
                if (!p.canDo(i, c.shiftIdx) || j + c.day1 > p.T) continue
                var z = 0
                for (l in 0 until c.day1) if (sched[i][j + l] == c.shiftIdx) z++
                if (z < c.day2) return j to (j + c.day1 - 1)
            }
            "vio-c3", "vio-c3m" -> {
                val k0 = sched[i][j]
                val lists = if (cls == "vio-c3") p.cons3 else p.cons3m
                for (c in lists) {
                    val seq = c.seq
                    if (seq.size < 2 || seq[0] != k0) continue
                    // [3.475.0/論理監査] チェッカー（checkC3Family）と同じ分岐: 単一シフト連は run-deficit
                    //   （連の先頭に印）なので**連の実範囲**を返す。旧: 単一連でも先に「窓マッチ」で
                    //   パターン長の窓を返していたため、連に続く無関係なセルまで強調され、しかも月末で窓が
                    //   期間を超えるときだけ連の範囲になる＝同じ違反で強調範囲が変わっていた。
                    if (com.magi.app.v6.C3Run.isSingleShiftSeq(seq)) {
                        var end = j
                        while (end + 1 < p.T && sched[i][end + 1] == k0) end++
                        return j to end
                    }
                    if (j + seq.size > p.T) continue
                    var ok = true
                    for (l in 1 until seq.size) if (sched[i][j + l] != seq[l]) { ok = false; break }
                    if (!ok) return j to (j + seq.size - 1)   // 未完成パターン=この窓が違反
                }
                var end = j
                while (end + 1 < p.T && sched[i][end + 1] == k0) end++   // 単一シフト連の実範囲
                if (end > j) return j to end
            }
        }
        return null
    }

    fun importCsv(rawText: String) {
        // [3.404.0] 旧: 入口ガードが無く、`job = viewModelScope.launch` が走行中の最適化の参照を
        //   **キャンセルせずに上書き**していた＝その最適化は「やめる」で止められないゾンビになる
        //   （3.271.0 が generateSmartInitial で直したのと同型の取り残し）。
        if (runBlockedByInFlight("CSV取込")) return
        val st = state ?: return
        val sched = currentSchedule ?: return
        val text = MojibakeRepair.repair(rawText)
        _ui.update { it.copy(messageIsError = false, running = true, message = "CSV取込中…") }
        val boardToken = beginBoardJob("CSV取込")
        job = viewModelScope.launch {
            try {
                // [3.282.0] JSON 側(loadAsync)と同じ是正: BOM 除去だけの健全な CSV で誤警告しない。
                if (MojibakeRepair.wasDecoded(rawText, text)) logOp("W", "文字化け（二重エンコード）を自動修復してCSVを取り込みました。元のファイル自体は修復されません")
                val res = withContext(Dispatchers.Default) { ScheduleCsvBridge.parse(text, st, sched) }
                // 取込失敗の明示: 氏名が1件も一致しなければ適用せず、オペレーターに原因を表示する。
                if (res.matched == 0) {
                    // [3.475.0/論理監査] 未閉引用符で残りの行が1セルに吸い込まれたときも matched=0 になるが、
                    //   旧: quoteWarn は成功経路でしか組み立てず、ここでは「氏名不一致」とだけ案内していた。
                    val why = if (res.unclosedQuote)
                        "CSV取込失敗: 引用符（\"）が閉じていない行があり、そこから後ろが1つのセルに吸い込まれています。書式を直してから取り込んでください。"
                    else
                        "CSV取込失敗: 一致する職員名がありませんでした（0名）。CSVの1列目の氏名が現在のデータと一致しているか、列レイアウト（氏名, 1日目, 2日目, …）をご確認ください。"
                    _ui.update { it.copy(messageIsError = true, running = false, message = why) }
                    logOp("W", if (res.unclosedQuote) "CSV取込 失敗: 引用符が閉じていないため取込を中止しました"
                        else "CSV取込 失敗: 職員名が0件一致のため取込を中止しました（氏名/列レイアウトを確認）")
                    return@launch
                }
                pushUndo("CSV取込")
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st.withSchedule(res.schedule)
                val total = st.staff.size
                // [3.410.0/I-01] シフト一覧に無い記号は取り込めない。旧: 黙って読み飛ばしていたため、
                //   誤字や凡例漏れが「休のまま」「元のまま」として静かに混入した。件数と記号を必ず出す。
                // [3.413.0/I-08] 引用符が閉じないCSVは残りの行が丸ごと消える＝「氏名不一致でスキップ」と
                //   区別が付かず部分的な成功に見える。必ず名指しする。
                val quoteWarn = if (res.unclosedQuote)
                    "｜⚠ 引用符（\"）が閉じていません。ここから後ろの行は読めていません" else ""
                val unk = if (res.unknownCells > 0)
                    "｜読めない記号 ${res.unknownCells}セル(${res.unknownSymbols.joinToString("・")})は取り込めませんでした"
                else ""
                val msg = if (res.matched in 1 until total)
                    "CSV取込完了: ${res.matched}/${total}名を更新（${total - res.matched}名は氏名不一致でスキップ）｜必須=${res.report.hard} 合計=${res.report.total}$unk$quoteWarn"
                else
                    "CSV取込完了: ${res.matched}名を更新｜必須=${res.report.hard} 合計=${res.report.total}$unk$quoteWarn"
                pushReport(state ?: st, res.schedule, res.report) { it.copy(
                    messageIsError = res.unknownCells > 0 || res.unclosedQuote,
                    running = false,
                    hasResult = true,
                    engineRan = false,   // [3.475.0] CSV取込は手操作扱い
                    message = msg,
                ) }
                if (res.matched in 1 until total) {
                    logOp("W", "CSV取込 一部のみ反映: ${res.matched}/${total}名一致（${total - res.matched}名は氏名不一致）")
                }
                if (res.unknownCells > 0) {
                    logOp("W", "CSV取込 読めない記号 ${res.unknownCells}セル: ${res.unknownSymbols.joinToString("・")}（シフト一覧に無い記号）")
                }
                logOp("I", "CSV取込 完了 ${res.matched}名一致 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: CancellationException) {
                _ui.update { it.copy(messageIsError = false, running = false, message = "CSV取込を中止しました") }   // [3.404.0]
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(running = false, message = "CSVを取り込めませんでした（${e.javaClass.simpleName}）", messageIsError = true) }
            } finally {
                endBoardJob(boardToken)
            }
        }
    }

    /**
     * 画面へ1行の返事を出す（Snackbar）。ファイルの読み書きのように **ViewModel の外で完結する操作**が
     * 結果を返すための入口。`level` は操作ログの水準（既定 I。失敗は W）。
     * [3.400.0] 旧: ファイル入出力7経路は成功も失敗も画面にもログにも何も出さず、
     *   「保存」を押しても画面が1ミリも変わらなかった（0バイトのファイルだけ残る事故も起こりうる）。
     */
    fun notify(text: String, level: String = "I") {
        logOp(level, text)
        _ui.update { it.copy(message = text, messageIsError = level == "W") }
    }

    /**
     * ファイル書き込みの結果を1行で返す。**成功も必ず返す**のが肝で、旧実装は成功時も無反応だったため
     * 「保存できたのか」を画面で確かめる手段が無かった。
     */
    fun notifySave(result: Result<*>, what: String) {
        result.fold(
            onSuccess = { notify("${what}を保存しました") },
            onFailure = { e -> notify("${what}を保存できませんでした（${ioReason(e)}）", "W") },
        )
    }

    /** ファイル読み込みの失敗を1行で返す（成功時は呼ばない＝読み込めた事実は中身の表示が示す）。 */
    fun notifyOpenFailure(result: Result<*>, what: String) {
        notify("${what}を開けませんでした（${ioReason(result.exceptionOrNull())}）", "W")
    }

    /**
     * 例外を利用者の言葉へ。**生の例外文を画面へ出さない**（3.147.0/3.191.0 の方針）が、
     * 詳しい原因は notify が logOp へ流すので書き出したログには残る。
     */
    private fun ioReason(e: Throwable?): String = when {
        e == null -> "内容が空でした"
        e is SecurityException -> "アクセスが許可されていません"
        e is java.io.FileNotFoundException -> "ファイルが見つからないか、書き込みが許可されていません"
        e.message?.contains("space", ignoreCase = true) == true -> "保存先の空き容量が足りません"
        else -> e.javaClass.simpleName
    }

    /**
     * 直近メッセージを消す。`shown` を渡すと**それがまだ表示中のときだけ**消す（compare-and-clear）。
     * Snackbar を出し終えたあとに素で消すと、その間に届いた新しいメッセージまで消してしまうため。
     */
    fun clearMessage(shown: String? = null) {
        _ui.update { if (shown == null || it.message == shown) it.copy(message = null, messageIsError = false) else it }
    }

    /**
     * 診断ログのスパム抑制。RSI/ALNS の各ラウンド・各リスタート・EarlyChain などで同種の行が大量に
     * 出るため、(1) 連続する重複行を「×N」に畳み、(2) それでも上限を超える場合は頭7割＋尾3割に圧縮する。
     * 全文が必要な場合は「ログ出力（テキスト/JSON）」で取得する想定。
     */
    private fun compressDiagLogs(lines: List<String>, cap: Int = 200): List<String> {
        if (lines.size <= 1) return lines
        val collapsed = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            var j = i + 1
            while (j < lines.size && lines[j] == lines[i]) j++
            val n = j - i
            collapsed.add(if (n > 1) "${lines[i]}  ×$n" else lines[i])
            i = j
        }
        if (collapsed.size <= cap) return collapsed
        val head = cap * 7 / 10
        val tail = cap - head
        val out = ArrayList<String>(cap + 1)
        out.addAll(collapsed.subList(0, head))
        out.add("… 中略 ${collapsed.size - head - tail} 行省略（全文は「ログ出力」で取得） …")
        out.addAll(collapsed.subList(collapsed.size - tail, collapsed.size))
        return out
    }

    /**
     * makeUi の重い解析4パスの出力を束ねる不変ホルダ。純関数の出力のみを保持するため、
     * どのスレッドで生成しても安全（背景スレッドで作りメインへ受け渡せる）。
     */
    private data class Analysis(
        val v6: V6PortReport,
        val sanity: V6SanityReport,
        val coverageDiag: CoverageDiagnosis?,
        val forbiddenDiag: ForbiddenRunDiagnosis?,
        val v6Logs: List<String>,
        val rawDiagLogs: List<String>,
    )

    /**
     * makeUi が必要とする4つの重い解析を Dispatchers.Default 上で「並列」に実行する。
     * 4パス（analyze / build / diagnoseCoverage / buildViolationDebug）は同じ不変入力にのみ依存し
     * 相互参照しない純関数なので、別コアで同時実行でき、壁時計時間が sum(パス) → max(パス) に短縮される
     * （最重量は全制約走査の buildViolationDebug）。coroutineScope により、いずれかの失敗・呼び出し元の
     * キャンセルは兄弟 async へ確実に伝播する（構造化並行性）。
     */
    private suspend fun analyzeParallel(
        st: MagiState,
        schedule: Array<IntArray>,
        report: ViolationReport,
    ): Analysis = coroutineScope {
        val v6D       = async(Dispatchers.Default) { V6PortAnalyzer.analyze(st, schedule, report) }
        val sanityD   = async(Dispatchers.Default) { V6SanityPort.build(st, schedule) }
        // 人員不足(covU)または人員過剰(covO)が残る場合のみ原因診断（どの日/シフトが「充足不可」か
        // 「未到達」か／過剰がなぜ動かせないか）を算出しログに残す。
        // [3.515.1] covOの深追い(FixSuggester)はここでは行わない＝pushReportが完了後に別ジョブで行う
        // （3.515.0のregression: ここに混ぜるとedit-lock解除と経過時間表示が深追い終了まで遅れていた）。
        val coverageD = async(Dispatchers.Default) {
            V6PortAnalyzer.diagnoseCoverage(st, schedule, report).takeIf { it.hasShortage || it.hasSurplus }
        }
        // [3.280.0] 禁止連続(c3n)が残る場合のみ「なぜ崩せないか」診断（CoverageDiag の c3n 版）。
        val forbiddenD = async(Dispatchers.Default) {
            if ((report.breakdown["c3n"] ?: 0) > 0)
                V6PortAnalyzer.diagnoseForbiddenRuns(st, schedule).takeIf { it.hasRuns }
            else null
        }
        // [デバッグ] 制約違反を家族ごとに「場所＋実値(必要/現状, 回数/下限上限, 誰/何日/シフト)」で出力。
        val vioDebugD = async(Dispatchers.Default) { V6SanityPort.buildViolationDebug(st, schedule, report) }

        // v6Logs は sanity/coverageDiag/forbiddenDiag に依存 → 依存先だけ先に await（依存グラフを尊重）
        val sanity = sanityD.await()
        val coverageDiag = coverageD.await()
        val forbiddenDiag = forbiddenD.await()
        val v6Logs = listOf("[I] LoadDataBit: ${sanity.loadDataBitSummary}") + sanity.warns.map { "[W] SanityCheck: $it" } + sanity.notes.map { "[I] V6Port: $it" } + sanity.duplicateSeqConstraints.take(4).map { "[W] DuplicateSeq: $it" } + sanity.guidance.take(12).map { "[W] 設定ミス: ${it.where} — ${it.problem} → ${it.fix}" } + (coverageDiag?.logLines() ?: emptyList()) + (forbiddenDiag?.logLines() ?: emptyList())
        val mappedDiag = report.logs.map { "[${it.level}] ${it.tag}: ${it.message}" }
        Analysis(
            v6 = v6D.await(),
            sanity = sanity,
            coverageDiag = coverageDiag,
            forbiddenDiag = forbiddenDiag,
            v6Logs = v6Logs,
            rawDiagLogs = v6Logs + mappedDiag + vioDebugD.await(),   // 出力用の全文（非圧縮）。表示は圧縮版を使う。
        )
    }

    /**
     * 重い解析(analyzeParallel)を Default で並列実行し、その結果を StateFlow へ反映する共通経路。
     * 共有可変(rawDiagLogs)の書き込みと _ui.update はメインスレッドで行い、背景スレッドからは書かない
     * （＝レース不能・単一ライタ）。全 makeUi 呼び出しをこの1経路へ集約する。
     * @param nonCancellable 停止(keep-best)経路から呼ぶ場合 true＝スコープキャンセル後も解析を完了させる。
     */
    private suspend fun pushReport(
        st: MagiState,
        schedule: Array<IntArray>,
        report: ViolationReport,
        nonCancellable: Boolean = false,
        /** [3.379.0] エンジン実行の結果を押すときだけ非 null（"最適化" 等）。診断を退避する印。 */
        runLabel: String? = null,
        transform: (UiState) -> UiState = { it },
    ) {
        val analysis =
            if (nonCancellable) withContext(NonCancellable) { analyzeParallel(st, schedule, report) }
            else analyzeParallel(st, schedule, report)
        rawDiagLogs = analysis.rawDiagLogs
        lastDiagSerial = activeRunSerial
        if (runLabel != null) {
            lastRunDiagLogs = analysis.rawDiagLogs
            lastRunDiagLabel = runLabel
            lastRunDiagAtMs = System.currentTimeMillis()
            lastRunDiagSerial = activeRunSerial
        }
        _ui.update { base -> makeUi(st, schedule, report, analysis, transform(base)) }
        deepCovODiagFollowUp(st, schedule, report, runLabel)
    }

    /** [3.515.1] covOの深追い(FixSuggester)をpushReport本体を待たせない別ジョブで行う（経緯: history
     *  3.515.1）。盤面/設定が変わっていれば結果は捨てる（[boardKey]/[stateKey]）。rawDiagLogsは更新しない。 */
    private fun deepCovODiagFollowUp(st: MagiState, schedule: Array<IntArray>, report: ViolationReport, runLabel: String?) {
        if (runLabel == null || (report.breakdown["covO"] ?: 0) <= 0) return
        val bKey = boardKey(schedule); val sKey = stateKey(st)
        viewModelScope.launch(Dispatchers.Default) {
            val deep = runCatching { V6PortAnalyzer.diagnoseCoverage(st, schedule, report, deepSurplus = true) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (currentSchedule?.let { boardKey(it) } == bKey && state?.let { stateKey(it) } == sKey) {
                    _ui.update { it.copy(coverageDiag = deep.takeIf { d -> d.hasShortage || d.hasSurplus }) }
                }
            }
        }
    }

    private fun makeUi(st: MagiState, schedule: Array<IntArray>, report: ViolationReport, analysis: Analysis, base: UiState): UiState {
        val groupSymbols = st.staff.map { staff -> st.groups.getOrNull(staff.groupIdx)?.kigou ?: "" }
        val v6 = analysis.v6
        val sanity = analysis.sanity
        val coverageDiag = analysis.coverageDiag
        val v6Logs = analysis.v6Logs
        // [3.324.0] 研磨診断は観測した盤面のものか（盤面が変わっていれば出さない）。
        // [3.327.0] 盤面**と**制約の両方が観測時と一致するときだけ診断を出す。
        val diagFresh = lastDiagBoardKey != 0L && lastDiagBoardKey == boardKey(schedule) &&
            lastDiagStateKey == stateKey(st)
        val mappedDiag = report.logs.map { "[${it.level}] ${it.tag}: ${it.message}" }
        // rawDiagLogs は pushReport がメインスレッドで設定済み（背景スレッドからは書かない＝レース回避）。
        // 満足度(0-100): 初期からの違反削減率。HARD未解決の間は上限を抑える。
        val initTotal = (base.initHard + base.initSoft).coerceAtLeast(1L)
        val ratio = (1.0 - report.total.toDouble() / initTotal).coerceIn(0.0, 1.0)
        val sat = if (report.hard > 0) (ratio * 55).toInt() else (40 + (ratio * 60).toInt()).coerceIn(0, 100)
        // [backlog#1] この検査対象が結果(ws6)そのものか（＝report が結果専用マップの最新値か）。
        return base.copy(
            staff = st.staffCount,
            days = st.dayCount,
            shifts = st.shiftCount,
            groups = st.groupCount,
            use2 = st.use2Patterns,
            bestHard = report.hard.toLong(),
            bestSoft = report.soft.toLong(),
            totalViolations = report.total,
            weightedScore = report.weightedScore,
            breakdown = emptyBreakdown + report.breakdown,
            checkRev = base.checkRev + 1,
            violationCells = report.violations,
            needViolations = report.needViolations,
            countViolations = report.countViolations,
            violationCellFamilies = report.cellFamilies,
            countFamilies = report.countFamilies,
            needFamilies = report.needFamilies,
            distLocations = report.distLocations,
            logs = v6Logs + compressDiagLogs(mappedDiag),
            staffNames = st.staff.map { it.name },
            staffGroupSymbols = groupSymbols.map { toHankakuKigou(it) },
            shiftSymbols = st.shifts.map { toHankakuKigou(it.kigou) },
            shiftColorHex = st.shifts.mapIndexed { i, sh -> ShiftAppearance.resolveShiftColor(st.shiftColors[sh.kigou], i) },
            shiftTextHex = st.shifts.mapIndexed { i, sh -> ShiftAppearance.pickTextColor(ShiftAppearance.resolveShiftColor(st.shiftColors[sh.kigou], i)) },
            violationColorHex = st.shiftColors["__vio__"] ?: "",
            violationSoftColorHex = st.shiftColors["__vioSoft__"] ?: "",
            violationFamilyColorHex = st.shiftColors.entries
                .filter { it.key.startsWith("__vioFam_") && it.key.endsWith("__") }
                .associate { it.key.removePrefix("__vioFam_").removeSuffix("__") to it.value },
            schedule = schedule.map { it.toList() },
            wishes = st.wishes,
            v6 = v6,
            satisfaction = sat,
            // 研磨の限界: 必須は解決済みだが微調整が残る → 手修正の検討を促す
            polishExhausted = report.hard == 0 && report.total > 0,
            // 解決したらガチャ助言は消す
            copilotHint = if (report.hard == 0) null else base.copilotHint,
            // 担当外など実現不能な希望（Web版の担当外希望警告に相当）
            impossibleWishCount = sanity.impossibleWishes.size,
            // 人員不足(covU)の原因診断（充足不可/充足可能の切り分け）。不足が無ければ null。
            coverageDiag = coverageDiag,
            // [3.280.0] 禁止連続(c3n)の「なぜ崩せないか」診断。c3n=0 なら null。
            forbiddenDiag = analysis.forbiddenDiag,
            // [3.322.0] c1 頭打ちの構造化診断。再計算できない（研磨中の却下記録が唯一の根拠）ので
            //   ViewModel が保持し、いま c1 が残っているときだけ見せる（解消済みなら黙る）。
            // [3.324.0/外部レビュー] 診断は観測した盤面のものだけ出す。盤面が変わっていれば黙る
            //   （手編集・元に戻す・読込・初期解生成など、あらゆる変更で自動的に外れる）。
            c1Plateau = if (diagFresh) lastC1Plateau?.takeIf { (report.breakdown["c1"] ?: 0) > 0 } else null,
            observedPinBlockedAttempts = if (diagFresh) lastObservedPinAttempts else 0,
            // [3.326.0] 緩和の対象候補。どのピンが何回止めたかを名前つきで渡す（多い順）。
            pinTargets = if (!diagFresh) emptyList() else {
                val pr = cachedProblem(st)
                lastPinBlocks?.byTarget()?.mapNotNull { (i, k, n) ->
                    val lo = pr.rangeLo.getOrNull(i)?.getOrNull(k) ?: return@mapNotNull null
                    val hi = pr.rangeHi.getOrNull(i)?.getOrNull(k) ?: return@mapNotNull null
                    // [3.327.0/外部レビュー High2] **いま固定されているものだけ**出す。緩めたあと
                    //   (lo != hi) も「N回に固定」と表示し続けるのは事実に反する。
                    if (lo == Int.MIN_VALUE || hi == Int.MAX_VALUE || lo != hi) return@mapNotNull null
                    PinTargetView(
                        staff = i, shift = k,
                        staffName = st.staff.getOrNull(i)?.name ?: "#$i",
                        shiftKigou = st.shifts.getOrNull(k)?.kigou ?: "$k",
                        pinnedCount = lo, attempts = n,
                    )
                } ?: emptyList()
            },
            settingIssues = sanity.guidance,
            startDate = st.startDate,
        )
    }

    private data class LoadedProblem(
        val state: MagiState,
        val schedule: Array<IntArray>,
        val report: ViolationReport,
    )
}

private fun Int.floorMod(m: Int): Int = ((this % m) + m) % m

/** 完了カードの前後比較 2 行（変更量／族別の改善・悪化）。 */
private fun runSummaryOf(s: com.magi.app.v6.ChangeSummary): String = s.line() + "\n" + s.familyLine { breakdownLabels[it] ?: it }
