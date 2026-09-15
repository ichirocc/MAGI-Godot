package com.magi.app.godot

// [Godot移行] Godot(GDScript)側から dispatch() で呼べる操作の許可リスト。
// ここに無い op 名は MagiBridge.dispatch が問答無用で拒否する（既存 ViewModel の任意メソッド呼び出しを防ぐ境界）。
// 純Kotlin（Android/ViewModel非依存）＝ tools/host/hosttest.sh でホストJVMのままテストできる。
object MagiOpWhitelist {

    /** op名 -> argsJson に必須のキー（型検証はしない。存在チェックのみ。空リスト=引数不要）。 */
    private val ops: Map<String, List<String>> = mapOf(
        // ホーム: 新規/読込/生成/最適化/調整/停止/書出
        "initBlankState" to emptyList(),
        "load" to listOf("json"),
        "generateSmartInitial" to emptyList(),
        "runV6FullOptimize" to emptyList(),
        "runSoftPolish" to emptyList(),
        "runInBackground" to emptyList(),
        "stop" to emptyList(),
        "saveNow" to emptyList(),
        "undo" to emptyList(),
        "redo" to emptyList(),
        "dismissInterrupted" to emptyList(),
        "restorePreviousData" to emptyList(),
        "clearMessage" to emptyList(),
        // 勤務表セル編集
        "setCell" to listOf("i", "j", "shift"),
        "setCells" to listOf("cells", "shift"),
        // 希望/回数
        "applyWishes" to listOf("includeOutOfScope"),
        "clearOutOfScopeWishes" to emptyList(),
        "applyAlternative" to listOf("index"),
        // 分析: 修正提案・設定ミス誘導・禁止連続の緩和・見直しメモ
        "findFixSuggestions" to emptyList(),
        "applyFixSuggestion" to listOf("suggestionIndex"),
        "applySettingFix" to listOf("issueIndex"),
        "relaxForbiddenRule" to listOf("seqLabel"),
        "addReviewMemo" to listOf("text"),
        "removeReviewMemo" to listOf("index"),
        // 取込
        "importCsv" to listOf("csv"),
        // 設定タブ
        "setWorkers" to listOf("n"),
        "setBudget" to listOf("sec"),
        "setNativeAccel" to listOf("on"),
        "setNativeParity" to listOf("on"),
        "setBlockSwapC3nFilter" to listOf("on"),
        "setWideC3nBreak" to listOf("on"),
        "setCombineExhaustPairs" to listOf("on"),
        "setLnsAdaptive" to listOf("on"),
        "setCountChainPolish" to listOf("on"),
        "setAptFairSoftTolerance" to listOf("on"),
        "setSoftPolish" to listOf("on"),
        "setV6Algorithm" to listOf("algorithm"),
    )

    /** 盤面編集中でも常に受理する op（最適化実行中の停止コマンド）。トークン鮮度チェックの例外。 */
    val staleTokenExempt: Set<String> = setOf("stop", "dismissInterrupted")

    fun isAllowed(op: String): Boolean = ops.containsKey(op)

    fun requiredArgs(op: String): List<String> = ops[op] ?: emptyList()

    /** argsJson(JSONObject相当のキー集合)が必須キーを満たすか。 */
    fun hasRequiredArgs(op: String, presentKeys: Set<String>): Boolean =
        requiredArgs(op).all { it in presentKeys }

    fun allOps(): Set<String> = ops.keys
}
