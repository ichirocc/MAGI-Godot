package com.magi.app.godot

import android.os.Handler
import android.os.Looper
import com.magi.app.ui.MagiViewModel
import com.magi.app.ui.SaveState
import com.magi.app.ui.UiState
import com.magi.app.ui.V6Algorithm
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * [Godot移行] Godot(GDScript)からJNI経由で呼ばれる公開API。
 * 既存 MagiViewModel（Compose UIが使うもの）を一切変更せず、そのStateFlowを読み・
 * 許可された操作(MagiOpWhitelist)だけを合流させる薄い境界層。
 *
 * - snapshot(): 現在のUiStateを不変JSON文字列として返す（可変オブジェクトそのものは渡さない）。
 * - dispatch(): 許可リストにある操作のみ、メインスレッドへ移送してViewModelのメソッドを呼ぶ。
 *
 * Godot側の呼び出しはGodotの描画/スクリプトスレッドから来る想定のため、ここでは
 * Handler(mainLooper) + CountDownLatch でメインスレッドへ同期的に移送する
 * （ViewModelのStateFlow/内部状態はメインスレッド専有が前提＝既存Compose側と同じ制約）。
 */
class MagiBridge(private val viewModel: MagiViewModel) {

    private val mainHandler = Handler(Looper.getMainLooper())
    // [トークン] snapshotを返すたびに増える。dispatchは「そのsnapshotへの操作か」をこれで確認する。
    private val revision = AtomicLong(0)

    /** 現在のUiStateをJSON文字列で返す。呼び出しスレッドは問わない（メインスレッドへ移送して読む）。 */
    fun snapshot(): String = runOnMain {
        val ui = viewModel.uiState.value
        val rev = revision.incrementAndGet()
        val json = uiStateToJson(ui)
        val token = MagiBridgeToken.compute(canonicalBody(json), rev)
        json.put("_token", token)
        json.put("_rev", rev)
        json.toString()
    }

    /**
     * 許可リストの操作のみ実行する。
     * @param op 操作名（MagiOpWhitelist参照）
     * @param argsJson 引数JSON文字列（例: {"i":0,"j":1,"shift":2,"_token":"..."}）
     * @return {"ok":true,"result":...,"snapshot":"...","token":"..."} または {"ok":false,"error":"..."}
     */
    fun dispatch(op: String, argsJson: String): String {
        if (!MagiOpWhitelist.isAllowed(op)) {
            return errorJson("op not allowed: $op")
        }
        val args = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            return errorJson("invalid argsJson: ${e.message}")
        }
        val presentKeys = args.keys().asSequence().toSet()
        if (!MagiOpWhitelist.hasRequiredArgs(op, presentKeys)) {
            return errorJson("missing required args for $op: need ${MagiOpWhitelist.requiredArgs(op)}")
        }
        // トークン鮮度チェック（stop等の緊急操作は例外＝実行中でも受理）。
        val token = args.optString("_token", "")
        if (op !in MagiOpWhitelist.staleTokenExempt) {
            val cur = revision.get()
            val curBody = canonicalBody(uiStateToJson(viewModel.uiState.value))
            if (!MagiBridgeToken.verify(token, curBody, cur)) {
                return errorJson("stale token: snapshot changed since this token was issued")
            }
        }
        return runOnMain {
            try {
                invokeOp(op, args)
                val rev = revision.incrementAndGet()
                val json = uiStateToJson(viewModel.uiState.value)
                val body = canonicalBody(json)
                val newToken = MagiBridgeToken.compute(body, rev)
                JSONObject().apply {
                    put("ok", true)
                    put("snapshot", json.toString())
                    put("token", newToken)
                }.toString()
            } catch (e: Exception) {
                errorJson("dispatch failed for $op: ${e.message}")
            }
        }
    }

    private fun errorJson(msg: String): String =
        JSONObject().apply { put("ok", false); put("error", msg) }.toString()

    /** token計算対象の正規化本文（_token/_rev自身は対象から除く＝自己参照を避ける）。 */
    private fun canonicalBody(json: JSONObject): String {
        val copy = JSONObject(json.toString())
        copy.remove("_token")
        copy.remove("_rev")
        return copy.toString()
    }

    private fun <T> runOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun invokeOp(op: String, args: JSONObject) {
        when (op) {
            "initBlankState" -> viewModel.initBlankState()
            "load" -> viewModel.load(args.getString("json"))
            "generateSmartInitial" -> viewModel.generateSmartInitial()
            "runV6FullOptimize" -> viewModel.runV6FullOptimize()
            "runSoftPolish" -> viewModel.runSoftPolish()
            "runInBackground" -> viewModel.runInBackground()
            "stop" -> viewModel.stop()
            "saveNow" -> viewModel.saveNow()
            "undo" -> viewModel.undo()
            "redo" -> viewModel.redo()
            "dismissInterrupted" -> viewModel.dismissInterrupted()
            "restorePreviousData" -> viewModel.restorePreviousData()
            "clearMessage" -> viewModel.clearMessage()
            "setCell" -> viewModel.setCell(args.getInt("i"), args.getInt("j"), args.getInt("shift"))
            "setCells" -> {
                val arr = args.getJSONArray("cells")
                val cells = (0 until arr.length()).map { idx ->
                    val pair = arr.getJSONArray(idx)
                    pair.getInt(0) to pair.getInt(1)
                }
                viewModel.setCells(cells, args.getInt("shift"))
            }
            "applyWishes" -> viewModel.applyWishes(args.getBoolean("includeOutOfScope"))
            "clearOutOfScopeWishes" -> viewModel.clearOutOfScopeWishes()
            "applyAlternative" -> viewModel.applyAlternative(args.getInt("index"))
            "findFixSuggestions" -> viewModel.findFixSuggestions()
            "applyFixSuggestion" -> {
                val idx = args.getInt("suggestionIndex")
                viewModel.uiState.value.fixSuggestions.getOrNull(idx)?.let { viewModel.applyFixSuggestion(it) }
                    ?: throw IllegalArgumentException("suggestionIndex out of range: $idx")
            }
            "applySettingFix" -> {
                val idx = args.getInt("issueIndex")
                viewModel.uiState.value.settingIssues.getOrNull(idx)?.let { viewModel.applySettingFix(it) }
                    ?: throw IllegalArgumentException("issueIndex out of range: $idx")
            }
            "relaxForbiddenRule" -> viewModel.relaxForbiddenRule(args.getString("seqLabel"))
            "addReviewMemo" -> viewModel.addReviewMemo(args.getString("text"))
            "removeReviewMemo" -> viewModel.removeReviewMemo(args.getInt("index"))
            "importCsv" -> viewModel.importCsv(args.getString("csv"))
            "setWorkers" -> viewModel.setWorkers(args.getInt("n"))
            "setBudget" -> viewModel.setBudget(args.getInt("sec"))
            "setNativeAccel" -> viewModel.setNativeAccel(args.getBoolean("on"))
            "setNativeParity" -> viewModel.setNativeParity(args.getBoolean("on"))
            "setBlockSwapC3nFilter" -> viewModel.setBlockSwapC3nFilter(args.getBoolean("on"))
            "setWideC3nBreak" -> viewModel.setWideC3nBreak(args.getBoolean("on"))
            "setCombineExhaustPairs" -> viewModel.setCombineExhaustPairs(args.getBoolean("on"))
            "setLnsAdaptive" -> viewModel.setLnsAdaptive(args.getBoolean("on"))
            "setCountChainPolish" -> viewModel.setCountChainPolish(args.getBoolean("on"))
            "setAptFairSoftTolerance" -> viewModel.setAptFairSoftTolerance(args.getBoolean("on"))
            "setSoftPolish" -> viewModel.setSoftPolish(args.getBoolean("on"))
            "setV6Algorithm" -> viewModel.setV6Algorithm(V6Algorithm.valueOf(args.getString("algorithm")))
            else -> throw IllegalStateException("unhandled allowed op: $op")
        }
    }

    private fun uiStateToJson(ui: UiState): JSONObject = JSONObject().apply {
        put("loaded", ui.loaded)
        put("canUndo", ui.canUndo)
        put("canRedo", ui.canRedo)
        put("staff", ui.staff)
        put("days", ui.days)
        put("shifts", ui.shifts)
        put("groups", ui.groups)
        put("running", ui.running)
        put("hasResult", ui.hasResult)
        put("engineRan", ui.engineRan)
        put("checkRev", ui.checkRev)
        put("bestHard", ui.bestHard)
        put("bestSoft", ui.bestSoft)
        put("runSummary", ui.runSummary)
        put("totalViolations", ui.totalViolations)
        put("weightedScore", ui.weightedScore)
        put("breakdown", JSONObject(ui.breakdown))
        put("violationCells", JSONObject(ui.violationCells))
        put("startDate", ui.startDate)
        put("staffNames", JSONArray(ui.staffNames))
        put("staffGroupSymbols", JSONArray(ui.staffGroupSymbols))
        put("shiftSymbols", JSONArray(ui.shiftSymbols))
        put("shiftColorHex", JSONArray(ui.shiftColorHex))
        put("shiftTextHex", JSONArray(ui.shiftTextHex))
        put("schedule", JSONArray(ui.schedule.map { JSONArray(it) }))
        put("liveSchedule", JSONArray(ui.liveSchedule.map { JSONArray(it) }))
        val wishes = JSONObject(); ui.wishes.forEach { (k, v) -> wishes.put(k, v) }
        put("wishes", wishes)
        put("workers", ui.workers)
        put("budgetSec", ui.budgetSec)
        put("nativeAccel", ui.nativeAccel)
        put("nativeParity", ui.nativeParity)
        put("softPolish", ui.softPolish)
        put("v6Algorithm", ui.v6Algorithm.name)
        put("message", ui.message)
        put("messageIsError", ui.messageIsError)
        put("satisfaction", ui.satisfaction)
        put("polishExhausted", ui.polishExhausted)
        put("copilotHint", ui.copilotHint)
        put("impossibleWishCount", ui.impossibleWishCount)
        put("opLog", JSONArray(ui.opLog))
        put("alternatives", JSONArray(ui.alternatives))
        put("interruptedRun", ui.interruptedRun)
        put("interruptedInfo", ui.interruptedInfo)
        put("saveState", ui.saveState.name)
        put("fixSuggestionCount", ui.fixSuggestions.size)
        put("settingIssueCount", ui.settingIssues.size)
    }
}
