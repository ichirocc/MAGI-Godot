package com.magi.app.godot

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.magi.app.ui.MagiViewModel
import com.magi.app.ui.SaveState
import com.magi.app.ui.UiState
// ws1系/制約系は MagiViewModelWs1.kt / MagiViewModelConstraints.kt の拡張関数＝別パッケージからは明示 import が要る
import com.magi.app.ui.addCons1
import com.magi.app.ui.addCons2
import com.magi.app.ui.addCons3
import com.magi.app.ui.addCons3w
import com.magi.app.ui.addCons41
import com.magi.app.ui.addCons41s
import com.magi.app.ui.addCons42
import com.magi.app.ui.addCons42s
import com.magi.app.ui.addSkillGroup
import com.magi.app.ui.editSkillGroup
import com.magi.app.ui.removeConstraint
import com.magi.app.ui.removeSkillGroup
import com.magi.app.ui.setStaffSkill
import com.magi.app.ui.updateConstraint
import com.magi.app.ui.ws1AddGroup
import com.magi.app.ui.ws1AddShift
import com.magi.app.ui.ws1AddStaff
import com.magi.app.ui.ws1EditGroup
import com.magi.app.ui.ws1EditShift
import com.magi.app.ui.ws1EditStaff
import com.magi.app.ui.ws1MoveGroupTo
import com.magi.app.ui.ws1MoveShiftTo
import com.magi.app.ui.ws1MoveStaffTo
import com.magi.app.ui.ws1RemoveGroup
import com.magi.app.ui.ws1RemoveShift
import com.magi.app.ui.ws1RemoveStaff
import com.magi.app.ui.ws1ResetGroupApt
import com.magi.app.ui.ws1SetGroupApt
import com.magi.app.ui.ws1SetGroupShift
import com.magi.app.ui.ws1SetGroupShiftColumn
import com.magi.app.ui.ws1SetGroupShiftRow
import com.magi.app.v6.V6Algorithm
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * [Godot移行] Godot(GDScript)からJNI経由で呼ばれる公開API。
 * MagiViewModel（状態・操作のハブ）を変更せず、そのStateFlowを読み・
 * 許可された操作(MagiOpWhitelist)だけを合流させる薄い境界層。
 *
 * - snapshot(): 現在のUiStateを不変JSON文字列として返す（可変オブジェクトそのものは渡さない）。
 * - dispatch(): 許可リストにある操作のみ、メインスレッドへ移送してViewModelのメソッドを呼ぶ。
 *
 * Godot側の呼び出しはGodotの描画/スクリプトスレッドから来る想定のため、ここでは
 * Handler(mainLooper) + CountDownLatch でメインスレッドへ同期的に移送する
 * （ViewModelのStateFlow/内部状態はメインスレッド専有が前提）。
 */
class MagiBridge(private val viewModel: MagiViewModel) {

    private val mainHandler = Handler(Looper.getMainLooper())
    // [トークン] dispatch が本文を実際に変えたときだけ増える。snapshot の再取得や no-op の操作では進めない＝
    // プレビューで得たトークンは、別画面が refresh しただけでは死なず、実際に盤面が変わった時だけ失効する。
    // 変更なしで uiState が入れ替わる経路（背景最適化の結果到着など）は本文ハッシュ側が検出する。
    // 初期値は生成ごとの乱数: Activity 再生成で新しい MagiBridge が同じ本文・同じ世代から始まると、
    // 旧 Activity で発行したトークンがそのまま通ってしまうため。
    private val revision = AtomicLong(SecureRandom().nextLong() and 0x3FFF_FFFF_FFFF_FFFFL)

    /** 現在のUiStateをJSON文字列で返す。呼び出しスレッドは問わない（メインスレッドへ移送して読む）。 */
    fun snapshot(): String = try {
        runOnMain {
            val ui = viewModel.ui.value
            val rev = revision.get()
            val json = uiStateToJson(ui)
            val token = MagiBridgeToken.compute(canonicalBody(json), rev)
            json.put("_token", token)
            json.put("_rev", rev)
            json.toString()
        }
    } catch (e: MainThreadTimeoutException) {
        errorJson(e.message ?: "main thread timeout")
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
        val token = args.optString("_token", "")
        return try {
            runOnMain {
                // 鮮度検査は実行と同じメインスレッド区間で行う。別スレッドで検査してから post すると、
                // その隙に盤面が変わっても通ってしまう（検査と実行の間に穴が開く）。添字ベースの op
                // （setCell / ws1Move* / ws1Remove* 等）は staffNames・structure の並びが本文ハッシュに
                // 入るため、並び替え後に古い添字で来た操作はここで弾かれる。stop 等の緊急操作は例外。
                val before = canonicalBody(uiStateToJson(viewModel.ui.value))
                if (op !in MagiOpWhitelist.staleTokenExempt) {
                    if (!MagiBridgeToken.verify(token, before, revision.get())) {
                        return@runOnMain errorJson("stale token: snapshot changed since this token was issued")
                    }
                }
                try {
                    invokeOp(op, args)
                    val json = uiStateToJson(viewModel.ui.value)
                    val body = canonicalBody(json)
                    // ViewModel 側が黙って return した no-op（範囲外の添字、同じ値の再設定など）は ok だが
                    // changed=false。世代も進めないので、直前に発行したトークンはそのまま有効。
                    val changed = body != before
                    val rev = if (changed) revision.incrementAndGet() else revision.get()
                    val newToken = MagiBridgeToken.compute(body, rev)
                    JSONObject().apply {
                        put("ok", true)
                        put("changed", changed)
                        put("snapshot", json.toString())
                        put("token", newToken)
                    }.toString()
                } catch (e: Exception) {
                    errorJson("dispatch failed for $op: ${e.message}")
                }
            }
        } catch (e: MainThreadTimeoutException) {
            errorJson(e.message ?: "main thread timeout")
        }
    }

    companion object {
        // メインスレッドが詰まっている（ANR相当）ときに Godot 側まで道連れで固まらないための上限。
        private const val MAIN_THREAD_TIMEOUT_SEC = 10L

        internal fun errorJson(msg: String): String =
            JSONObject().apply { put("ok", false); put("error", msg) }.toString()

        internal fun unavailableJson(): String = errorJson("bridge unavailable: MagiGodotActivity not created")
    }

    private class MainThreadTimeoutException(msg: String) : RuntimeException(msg)

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
        if (!latch.await(MAIN_THREAD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            throw MainThreadTimeoutException("main thread did not respond within ${MAIN_THREAD_TIMEOUT_SEC}s")
        }
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
                viewModel.ui.value.fixSuggestions.getOrNull(idx)?.let { viewModel.applyFixSuggestion(it) }
                    ?: throw IllegalArgumentException("suggestionIndex out of range: $idx")
            }
            "applySettingFix" -> {
                val idx = args.getInt("issueIndex")
                viewModel.ui.value.settingIssues.getOrNull(idx)?.let { viewModel.applySettingFix(it) }
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
            // [B1] 職員/シフト/群 管理
            "ws1AddShift" -> viewModel.ws1AddShift(args.getString("name"), args.getString("kigou"), args.getString("need1"), args.getString("need2"))
            "ws1EditShift" -> viewModel.ws1EditShift(args.getInt("k"), args.getString("name"), args.getString("kigou"), args.getString("need1"), args.getString("need2"))
            "ws1RemoveShift" -> viewModel.ws1RemoveShift(args.getInt("k"))
            "ws1MoveShiftTo" -> viewModel.ws1MoveShiftTo(args.getInt("from"), args.getInt("to"))
            "ws1AddGroup" -> viewModel.ws1AddGroup(args.getString("name"), args.getString("kigou"))
            "ws1EditGroup" -> viewModel.ws1EditGroup(args.getInt("g"), args.getString("name"), args.getString("kigou"))
            "ws1RemoveGroup" -> viewModel.ws1RemoveGroup(args.getInt("g"))
            "ws1MoveGroupTo" -> viewModel.ws1MoveGroupTo(args.getInt("from"), args.getInt("to"))
            "ws1AddStaff" -> viewModel.ws1AddStaff(args.getString("name"), args.getInt("groupIdx"))
            "ws1EditStaff" -> viewModel.ws1EditStaff(args.getInt("i"), args.getString("name"), args.getInt("groupIdx"))
            "ws1RemoveStaff" -> viewModel.ws1RemoveStaff(args.getInt("i"))
            "ws1MoveStaffTo" -> viewModel.ws1MoveStaffTo(args.getInt("from"), args.getInt("to"))
            // [B2] 担当とスキル
            "ws1SetGroupShift" -> viewModel.ws1SetGroupShift(args.getInt("g"), args.getInt("k"), args.getBoolean("allowed"))
            "ws1SetGroupShiftRow" -> viewModel.ws1SetGroupShiftRow(args.getInt("g"), args.getBoolean("allowed"))
            "ws1SetGroupShiftColumn" -> viewModel.ws1SetGroupShiftColumn(args.getInt("k"), args.getBoolean("allowed"))
            "ws1SetGroupApt" -> viewModel.ws1SetGroupApt(args.getInt("g"), args.getInt("k"), args.getString("value"))
            "ws1ResetGroupApt" -> viewModel.ws1ResetGroupApt()
            "addSkillGroup" -> viewModel.addSkillGroup(args.getString("name"), args.getString("kigou"))
            "editSkillGroup" -> viewModel.editSkillGroup(args.getInt("g"), args.getString("name"), args.getString("kigou"))
            "removeSkillGroup" -> viewModel.removeSkillGroup(args.getInt("g"))
            "setStaffSkill" -> viewModel.setStaffSkill(args.getInt("i"), args.getInt("skillIdx"))
            // [B3] 制約編集
            "addCons1" -> viewModel.addCons1(args.getString("day1"), args.getString("shiftKigou"), args.getString("day2"))
            "addCons2" -> viewModel.addCons2(args.getString("shiftKigou"), args.getString("count"))
            "addCons3" -> viewModel.addCons3(args.getString("family"), jsonArrayToStrings(args.getJSONArray("pattern")))
            "addCons41" -> viewModel.addCons41(args.getString("groupKigou"), args.getString("shiftKigou"), args.getString("l"), args.getString("u"))
            "addCons42" -> viewModel.addCons42(args.getString("g1"), args.getString("g2"), args.getString("s1"), args.getString("s2"))
            "addCons41s" -> viewModel.addCons41s(args.getString("groupKigou"), args.getString("shiftKigou"), args.getString("l"), args.getString("u"))
            "addCons42s" -> viewModel.addCons42s(args.getString("g1"), args.getString("g2"), args.getString("s1"), args.getString("s2"))
            "addCons3w" -> viewModel.addCons3w(args.getString("wishKigou"), args.getString("prevKigou"))
            "updateConstraint" -> viewModel.updateConstraint(args.getString("family"), args.getInt("index"), jsonArrayToStrings(args.getJSONArray("values")))
            "removeConstraint" -> viewModel.removeConstraint(args.getString("family"), args.getInt("index"))
            else -> throw IllegalStateException("unhandled allowed op: $op")
        }
    }

    // Godot 側のホーム画面に出す版表示（実機のスクリーンショットでどの APK かを判別する）。
    private val appVersion: String = runCatching {
        val app = viewModel.getApplication<Application>()
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("?")

    private fun uiStateToJson(ui: UiState): JSONObject = JSONObject().apply {
        put("appVersion", appVersion)
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
        // [B1/B2/B3] 構造編集画面（職員/シフト/群・担当スキル・制約11族）は生値の一覧表示が要る。
        // 既存フィールドの寄せ集めでは表現できないため viewModel.state（internal=モジュール内可視）を
        // そのまま直列化する。可変オブジェクトそのものではなくJSONのコピーなので単一真実源は崩れない。
        viewModel.state?.let { st -> put("structure", structureToJson(st)) }
    }

    private fun structureToJson(st: com.magi.app.model.MagiState): JSONObject = JSONObject().apply {
        put("shifts", JSONArray(st.shifts.map { s ->
            JSONObject().put("name", s.name).put("kigou", s.kigou).put("need1", s.need1).put("need2", s.need2)
        }))
        put("groups", JSONArray(st.groups.map { g -> JSONObject().put("name", g.name).put("kigou", g.kigou) }))
        put("staff", JSONArray(st.staff.map { s ->
            JSONObject().put("name", s.name).put("groupIdx", s.groupIdx).put("skillIdx", s.skillIdx)
        }))
        put("skillGroups", JSONArray(st.skillGroups.map { g -> JSONObject().put("name", g.name).put("kigou", g.kigou) }))
        put("groupShift", JSONArray(st.groupShift.map { row -> JSONArray(row) }))
        put("groupShiftApt", JSONArray(st.groupShiftApt.map { row -> JSONArray(row) }))
        put("cons1", JSONArray(st.cons1.map { JSONObject().put("day1", it.day1).put("shiftKigou", it.shiftKigou).put("day2", it.day2) }))
        put("cons2", JSONArray(st.cons2.map { JSONObject().put("shiftKigou", it.shiftKigou).put("count", it.count) }))
        put("cons3", JSONArray(st.cons3.map { JSONArray(it.pattern) }))
        put("cons3n", JSONArray(st.cons3n.map { JSONArray(it.pattern) }))
        put("cons3m", JSONArray(st.cons3m.map { JSONArray(it.pattern) }))
        put("cons3mn", JSONArray(st.cons3mn.map { JSONArray(it.pattern) }))
        put("cons3w", JSONArray(st.cons3w.map { JSONObject().put("wishKigou", it.wishKigou).put("prevKigou", it.prevKigou) }))
        fun c41(rows: List<com.magi.app.model.C41Row>) = JSONArray(rows.map {
            JSONObject().put("groupKigou", it.groupKigou).put("shiftKigou", it.shiftKigou).put("l", it.l).put("u", it.u)
        })
        fun c42(rows: List<com.magi.app.model.C42Row>) = JSONArray(rows.map {
            JSONObject().put("g1Kigou", it.g1Kigou).put("s1Kigou", it.s1Kigou).put("g2Kigou", it.g2Kigou).put("s2Kigou", it.s2Kigou)
        })
        put("cons41", c41(st.cons41))
        put("cons42", c42(st.cons42))
        put("cons41s", c41(st.cons41s))
        put("cons42s", c42(st.cons42s))
    }

    private fun jsonArrayToStrings(arr: JSONArray): List<String> = (0 until arr.length()).map { arr.getString(it) }
}
