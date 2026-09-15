package com.magi.app.ui

import android.app.Application
import com.magi.app.model.MojibakeRepair
import com.magi.app.model.StateParser
import com.magi.app.v6.ScheduleCsvBridge
import com.magi.app.v6.toIntArray2D
import kotlinx.coroutines.flow.update

/**
 * [MagiViewModel] の入出力（エクスポート: JSON/勤務表CSV/職員・希望・制約CSV/ログ、
 * インポート: 勤務表テンプレ/ユニット列/コンポーネント別CSV）。本体ファイルから extension 関数
 * として抽出（責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切
 * 変更していない。
 *
 * importCsv（既存データへ勤務表だけを重ねる取込）だけは job/beginBoardJob/pushUndo/autoSave/
 * pushReport と state/currentSchedule/resultSchedule への**書き込み**を伴う実行状態機械のため
 * 本体へ残置（importCsvSmart から呼ぶ）。本ファイルの取込は「解析→ゲート
 * （load/applyStructureWithMessage）へ渡す」薄い層のみで、状態への直接書き込みを持たない。
 *
 * 触るメンバ（いずれも internal＝モジュール内限定）: state/currentSchedule/resultSchedule/
 * originalJson/opLog/rawDiagLogs/lastDiag系/lastRun系（読み取りのみ・var は private set）・
 * _ui（メッセージ表示等の update。UiState 更新は従来どおり copy ベースの単方向フローのみ）・
 * logOp・load/loadAsync・applyStructureWithMessage。
 */
/** Current JSON to export. ws1 edits -> full serialize; constraint edits -> overwrite cons; else schedule only. */
fun MagiViewModel.exportJson(): String? {
    val sched = currentSchedule ?: resultSchedule ?: return null
    val st = state
    if (_ui.value.structureEdited && st != null) return StateParser.serialize(st, sched)
    val orig = originalJson ?: return null
    return if (_ui.value.constraintsEdited && st != null) StateParser.exportWithEdits(orig, st, sched)
    else StateParser.exportWithSchedule(orig, sched)
}

fun MagiViewModel.exportCsv(): String? {
    val st = state ?: return null
    val sched = currentSchedule ?: return null
    return ScheduleCsvBridge.build(st, sched)
}

/** コンポーネント別エクスポート（取込種別と対。出力→編集→取込で往復可）。 */
fun MagiViewModel.exportStaffCsv(): String? = state?.let { com.magi.app.v6.StaffCsvIO.build(it) }
fun MagiViewModel.exportWishesCsv(): String? = state?.let { com.magi.app.v6.WishesCsvIO.build(it) }
fun MagiViewModel.exportConstraintsCsv(): String? = state?.let { com.magi.app.v6.ConstraintsCsvIO.build(it) }
/** [3.547.0] シフト種別の色のみ出力（違反色は対象外、`ShiftColorsCsvIO`参照）。 */
fun MagiViewModel.exportShiftColorsCsv(): String? = state?.let { com.magi.app.v6.ShiftColorsCsvIO.build(it) }

/**
 * [3.360.0] 書き出したログが「どの版・どの端末で走ったか」を1行で残す。
 *
 * 旧ヘッダは出力時刻とデータ規模だけで、**受け取った側がビルドを特定できなかった**
 * （本セッションでも、アップロードされたログがどの版のものか判定できず解析が止まった。
 * 外部レポートが古い `.so` を現行ソースの不具合と誤読した件も同根）。
 *
 * CPU コア数を出すのは、[HypothesisPlanning.clampWorkersToCores]（3.224.0）が
 * 並列ワーカー設定を**黙ってコア数まで切り下げる**ため（設定8でも4本しか走らない実測あり）。
 * 設定値だけを見ても実際の並列度が読めない。
 */
private fun MagiViewModel.environmentLine(): String {
    val ctx = getApplication<Application>()
    val ver = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        // versionName はプラットフォーム型（String!）。マニフェスト由来で実運用では常に入るが、
        // null をそのまま補間すると「版: null (526)」という無意味な行になるため落とす。
        "${pi.versionName ?: "?"} (${pi.longVersionCode})"
    }.getOrDefault("不明")
    val cores = Runtime.getRuntime().availableProcessors()
    val nat = if (com.magi.app.v6.NativeBridge.available) {
        "有効(ABI${com.magi.app.v6.NativeBridge.ABI_VERSION})"
    } else "無効(.so未ロード)"
    return "版: $ver ・ ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" +
        " ・ Android ${android.os.Build.VERSION.RELEASE}(SDK ${android.os.Build.VERSION.SDK_INT})" +
        " ・ CPU ${cores}コア(いまの並列ワーカー設定=${_ui.value.workers}) ・ ネイティブ=$nat"
}

/** Operator log as a plain-text file (mirrors the Web "ログ出力"). */
/** [3.408.0] 実行の帰属表示。0＝実行外（違反チェック等）。 */
private fun runTag(serial: Int): String = if (serial > 0) "実行#$serial" else "実行外"

fun MagiViewModel.exportLogs(): String? {
    val ops = _ui.value.opLog
    val runsInLog = opLog.map { it.run }.filter { it > 0 }.distinct().sorted()
    val runSpan = if (runsInLog.isEmpty()) "" else "・実行#${runsInLog.first()}〜#${runsInLog.last()}"
    // 出力は全文（非圧縮）。画面表示は圧縮版だが、監査用にはロスレスの rawDiagLogs を使う。
    val logs = rawDiagLogs.ifEmpty { _ui.value.logs }
    if (ops.isEmpty() && logs.isEmpty()) return null
    val ts = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date())
    return buildString {
        append("MAGI ログ (Native)  出力: ").append(ts).append('\n')
        append(environmentLine()).append('\n')
        append("状態: ${_ui.value.staff}名/${_ui.value.days}日 ・ 必須=${_ui.value.bestHard} 合計=${_ui.value.totalViolations}\n")
        append("\n==== 操作ログ（新しい順 ${ops.size}件$runSpan）====\n")
        ops.forEach { append(it).append('\n') }
        append("\n==== 診断ログ（${runTag(lastDiagSerial)}の全文 ${logs.size}件）====\n")
        // [3.408.0] 操作ログは履歴・診断ログは直近1回ぶん。実行が2回以上あるとき、両者を続けて読むと
        //   前の実行の「グローバル最良更新」と直近の「全体最良更新=0回」が同一実行の矛盾に見える。
        //   どの行がどの実行かは行頭の #N で分かる、と明示する。
        if (runsInLog.size > 1) {
            append("※操作ログは複数回の実行を含みます（行頭 #N）。この診断ログは ${runTag(lastDiagSerial)} のものだけです。\n")
        }
        logs.forEach { append(it).append('\n') }
        // [3.379.0] 最適化のあとに1回でも編集/再チェックすると診断が丸ごと入れ替わるため、
        //   直近のエンジン実行ぶんを別セクションで必ず残す（同一なら重複させない）。
        val run = lastRunDiagLogs
        if (run.isNotEmpty() && run !== logs && run != logs) {
            val at = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)
                .format(java.util.Date(lastRunDiagAtMs))
            append("\n==== 直近の$lastRunDiagLabel の診断ログ（${runTag(lastRunDiagSerial)}・$at 時点・全文 ${run.size}件）====\n")
            append("※上の診断ログはその後の編集/再チェックで作り直された最新版です。こちらは実行時のもの。\n")
            run.forEach { append(it).append('\n') }
        }
    }
}

/** 操作ログ・診断ログ・現在の違反サマリを構造化JSONで書き出す（監査用）。 */
fun MagiViewModel.exportLogsJson(): String? {
    if (_ui.value.opLog.isEmpty() && _ui.value.logs.isEmpty()) return null
    val o = org.json.JSONObject()
    o.put("exportedAt", System.currentTimeMillis())
    o.put("environment", environmentLine())   // [3.360.0] 版・端末・コア数・ネイティブ可否（テキスト版と同一）
    o.put("staff", _ui.value.staff); o.put("days", _ui.value.days); o.put("shifts", _ui.value.shifts)
    o.put("hard", _ui.value.bestHard); o.put("soft", _ui.value.bestSoft); o.put("total", _ui.value.totalViolations)
    o.put("satisfaction", _ui.value.satisfaction)
    // [N6] satisfaction は 0-100 の進捗スコア（違反減少度: hard>0 で×55系）であり希望充足率ではない。
    //   外部AI/人間の誤読が実際に発生したため意味を同梱する。
    o.put("satisfactionMeaning", "0-100の進捗スコア（必須・合計違反の減少度）。希望充足率ではありません")
    o.put("opLog", org.json.JSONArray().apply { _ui.value.opLog.forEach { put(it) } })
    o.put("diagLog", org.json.JSONArray().apply { rawDiagLogs.ifEmpty { _ui.value.logs }.forEach { put(it) } })
    // [3.408.0] 帰属の鍵。opLog の行頭 #N と対応する。これが無いと、複数回実行したあとの書き出しで
    //   前の実行の「グローバル最良更新」と直近の「全体最良更新=0回」が同一実行の矛盾に見える。
    o.put("diagRun", lastDiagSerial)
    o.put("runsInOpLog", org.json.JSONArray().apply { opLog.map { it.run }.filter { it > 0 }.distinct().sorted().forEach { put(it) } })
    // [3.379.0] テキスト版と同じ理由＝最適化後の編集で diagLog は作り直されるため実行時のぶんも残す。
    if (lastRunDiagLogs.isNotEmpty()) {
        o.put("lastRunLabel", lastRunDiagLabel)
        o.put("lastRunSerial", lastRunDiagSerial)
        o.put("lastRunAt", lastRunDiagAtMs)
        o.put("lastRunDiagLog", org.json.JSONArray().apply { lastRunDiagLogs.forEach { put(it) } })
    }
    o.put("breakdown", org.json.JSONObject().apply { _ui.value.breakdown.forEach { (k, v) -> put(k, v) } })
    return o.toString(2)
}

/**
 * CSV取込の振り分け。病院などの「勤務表テンプレCSV」(ユニット/スタッフ/凡例を含む完全な1ヶ月表) は
 * 新規データセットとして丸ごと取り込む（[RosterCsvImport]）。それ以外は、既存データへ勤務表だけを
 * 重ねる従来の取込([importCsv])に回す（既存データが無ければ案内のみ）。
 */
/**
 * [3.414.0/I-02] CSV取込は**期間を推定して黙って確定していた**（`RosterCsvImport` はタイトルに
 * 年月が無ければ当年1月、`FlatRosterCsvImport` は曜日行から当年で最初に一致する月・曜日行が
 * 無ければ当年1月）。期間は勤務表の根幹で、間違っていれば曜日の平準化も日付表示もずれるのに、
 * 画面には「N名 / M日」しか出ず**推定したことすら伝わらなかった**。何日からとして取り込んだかを
 * 必ず出す。挙動は不変＝知らせるだけで、違っていれば設定タブで直せる。
 */
private fun periodNote(startDate: String) =
    "｜期間は「$startDate」から として取り込みました（CSVに年月が無い場合は推定です。設定タブで直せます）"

/**
 * [3.475.0/論理監査] テンプレCSVの凡例に無い記号を名指しする。旧: そのセルは黙って休（希望取込では
 * 希望なし）になり、件数も記号も画面・ログに出なかった（`ScheduleCsvBridge.parse` は 3.410.0/I-01 で
 * 出すようになっていたが、新規取込の経路だけ抜けていた）。
 */
private fun MagiViewModel.rosterUnknownNote(unknown: Map<String, Int>): String {
    if (unknown.isEmpty()) return ""
    val cells = unknown.values.sum()
    val top = unknown.entries.sortedByDescending { it.value }.take(5).joinToString("・") { "${it.key}(${it.value})" }
    logOp("W", "勤務表CSV取込 凡例に無い記号 ${cells}セル: $top（休として取り込みました）")
    return "｜⚠ 凡例に無い記号 ${cells}セル（$top）は休として取り込みました"
}

fun MagiViewModel.importCsvSmart(rawText: String) {
    val text = MojibakeRepair.repair(rawText)
    if (com.magi.app.v6.RosterCsvImport.detect(text)) {
        val unknownSym = LinkedHashMap<String, Int>()
        val st = runCatching { com.magi.app.v6.RosterCsvImport.parse(text, unknownOut = unknownSym) }.getOrNull()
        if (st != null) {
            // 凡例(記号一覧)が無いとシフトが「休」1種のみになり全セルが公休化する。
            // 取り込まず原因をオペレーターに表示する（Excel保存で凡例が消えるケース）。
            if (st.shiftCount <= 1) {
                _ui.update { it.copy(messageIsError = true, message = "CSV取込失敗: シフト記号（凡例）が見つかりません。テンプレCSV末尾の『記号 / 時刻 …』一覧が削除されていないかご確認ください（Excelで開いて保存すると消える場合があります）。元のファイルをそのまま取り込んでください。") }
                logOp("W", "勤務表CSV取込 中止: 凡例なし（シフト${st.shiftCount}種のみ→全公休化を防止）")
                return
            }
            // [3.414.0/I-02] 期間はCSVから読めないことがあり、**推定して黙って確定していた**
            //   （RosterCsvImport はタイトルに年月が無ければ当年1月、FlatRosterCsvImport は
            //   曜日行から当年で最初に一致する月／曜日行が無ければ当年1月）。期間は勤務表の根幹で、
            //   間違っていれば曜日の平準化も日付表示もずれる。**何日から取り込んだかを必ず出す**
            //   （挙動は不変＝知らせるだけ。違っていれば設定タブで直せる）。
            logOp("I", "勤務表CSVを新規取込: ${st.staffCount}名 / ${st.dayCount}日 / ${st.shiftCount}シフト / ${st.groupCount}ユニット / 期間${st.startDate}〜${st.endDate}")
            load(StateParser.serialize(st, st.schedule.toIntArray2D()), periodNote(st.startDate) + rosterUnknownNote(unknownSym))
            return
        }
        // テンプレらしいが解析不能 → 既存取込にフォールバック（または案内）。
    }
    // ユニット列形式（凡例なし: ユニット,No,役職,氏名,1,2,…）の勤務表CSV → 新規データセットとして取込。
    if (com.magi.app.v6.FlatRosterCsvImport.detect(text)) {
        val st = runCatching { com.magi.app.v6.FlatRosterCsvImport.parse(text) }.getOrNull()
        if (st != null) {
            // [3.414.0/I-02] この形式は**必ず**期間を推定する（曜日行から当年で最初に一致する月・
            //   曜日行が無ければ当年1月）。何日から取り込んだかを必ず出す（挙動は不変）。
            logOp("I", "勤務表CSV(ユニット列形式)を新規取込: ${st.staffCount}名 / ${st.dayCount}日 / ${st.shiftCount}シフト / ${st.groupCount}ユニット / 期間${st.startDate}〜${st.endDate}（推定）")
            load(StateParser.serialize(st, st.schedule.toIntArray2D()), periodNote(st.startDate))
            return
        }
        _ui.update { it.copy(messageIsError = true, message = "CSV取込失敗: ユニット列形式と判定しましたが解析できませんでした。ヘッダ行（ユニット, No, 役職, 氏名, 1, 2, …）と氏名列をご確認ください。") }
        logOp("W", "勤務表CSV(ユニット列形式)取込 失敗: 解析不能")
        return
    }
    if (state == null) {
        _ui.update { it.copy(messageIsError = true, message = "このCSVを読み込めませんでした。先に『データを開く』で基本データを読み込むか、勤務表テンプレCSVをご利用ください。") }
        return
    }
    // [3.282.0] 修復済みテキストをそのまま渡す（旧: rawText を渡し importCsv 内で二重に repair＝
    //   結果は同一だが無駄な再修復と非対称があった）。
    importCsv(text)
}

/**
 * 勤務表テンプレCSVを、利用者の選択（勤務表 or 希望シフト）で新規データとして取り込む。
 *  - asWishes=false: 本表を初期割り当て(勤務表)として読み込む。
 *  - asWishes=true : 本表をスタッフの希望として読み込み、勤務表は空(全公休)で開始（最適化で尊重）。
 */
fun MagiViewModel.importRosterAs(rawText: String, asWishes: Boolean) {
    val text = MojibakeRepair.repair(rawText)
    val unknownSym = LinkedHashMap<String, Int>()
    val st = runCatching { com.magi.app.v6.RosterCsvImport.parse(text, asWishes, unknownOut = unknownSym) }.getOrNull()
    if (st == null) {
        val quote = if (com.magi.app.v6.csvHasUnclosedQuote(text)) "引用符（\"）が閉じていない行があります。" else ""
        _ui.update { it.copy(messageIsError = true, message = "このCSVを読み込めませんでした。${quote}形式をご確認ください。") }
        return
    }
    if (st.shiftCount <= 1) {
        _ui.update { it.copy(messageIsError = true, message = "CSV取込失敗: シフト記号（凡例）が見つかりません。テンプレCSV末尾の『記号 / 時刻 …』一覧が削除されていないかご確認ください（Excelで保存すると消える場合があります）。") }
        logOp("W", "${if (asWishes) "希望シフト" else "勤務表"}CSV取込 中止: 凡例なし（シフト${st.shiftCount}種のみ）")
        return
    }
    val kind = if (asWishes) "希望シフト" else "勤務表"
    // [3.414.0/I-02] 期間はタイトルの年月から読むが、無ければ当年1月へ黙って落ちていた。
    //   何日から取り込んだかを必ず出す（挙動は不変＝知らせるだけ）。
    logOp("I", "${kind}として新規取込: ${st.staffCount}名 / ${st.dayCount}日 / ${st.shiftCount}シフト / ${st.groupCount}ユニット / 期間${st.startDate}〜${st.endDate}" +
        if (asWishes) "（希望${st.wishes.size}件）" else "")
    load(StateParser.serialize(st, st.schedule.toIntArray2D()), periodNote(st.startDate) + rosterUnknownNote(unknownSym))
}

/** 取込種別を取り違えた可能性の判定: 勤務表(スケジュール)CSVらしいか。 */
private fun looksLikeScheduleCsv(t: String): Boolean {
    val lines = t.split('\n')
    if (lines.isEmpty()) return false
    // ScheduleCsvBridge.build のヘッダ「スタッフ \ 日付,…」、または集計ブロック「集計,…」。
    val head = lines[0].trim()
    if (head.startsWith("スタッフ") && head.contains("日付")) return true
    return lines.any { it.trimStart().startsWith("集計,") }
}

/** 希望/制約の取込が0件のとき、別形式CSVの取り違えを推定して利用者向けヒントを返す（無ければ空）。 */
private fun componentImportMismatchHint(repairedText: String): String = when {
    // [3.475.0/論理監査] 未閉引用符は「取り込める行が0件」ではなく書式の誤り（3.413.0/I-08 が断る理由）。
    //   旧: 種類別取込3経路とも氏名/記号の不一致を疑わせる案内しか出せなかった。
    com.magi.app.v6.csvHasUnclosedQuote(repairedText) ->
        "引用符（\"）が閉じていない行があります。開いた引用符から後ろが1つのセルに吸い込まれるため、書式を直してから取り込んでください。"
    com.magi.app.v6.RosterCsvImport.detect(repairedText) ||
        com.magi.app.v6.FlatRosterCsvImport.detect(repairedText) ->
        "これは勤務表全体（テンプレ/ユニット列形式）のCSVのようです。取込種別で『データ全体（新規）』を選んでください。"
    looksLikeScheduleCsv(repairedText) ->
        "これは勤務表（スケジュール）CSVのようで、希望・制約は含まれていません。専用CSVを、出力タブの『希望』『制約』ボタンで出して取り込んでください。"
    else -> ""
}

/** [コンポーネント別取込] スタッフ一覧CSV（氏名,グループ,スキル）。既存は所属群/スキルを更新、未知の氏名は新規追加（勤務表に休の行を追加）。 */
fun MagiViewModel.importStaffCsv(rawText: String) {
    val st = state ?: run { _ui.update { it.copy(messageIsError = false, message = "先にデータを開いてください（職員一覧は既存データに追加/更新します）") }; return }
    val sched = currentSchedule ?: run { _ui.update { it.copy(messageIsError = false, message = "先にデータを開いてください（職員一覧は既存データに追加/更新します）") }; return }
    val text = MojibakeRepair.repair(rawText)
    val res = runCatching { com.magi.app.v6.StaffCsvIO.parseUpsert(text, st, sched) }.getOrNull()
    if (res == null) {
        val hint = componentImportMismatchHint(text)
        val tail = if (hint.isEmpty()) "形式『氏名,グループ,スキル』（1行=1名）をご確認ください。" else hint
        _ui.update { it.copy(messageIsError = true, message = "職員一覧の取込失敗（追加0・更新0）。$tail") }
        logOp("W", "職員一覧CSV取込 失敗: 0件")
        return
    }
    val parts = buildList {
        if (res.added > 0) add("${res.added}名を新規追加")
        if (res.updated > 0) add("${res.updated}名を更新")
    }
    // [3.413.0/I-07] 空でないのに解決できなかった群/スキル記号を必ず知らせる。旧: 新規は先頭
    //   グループ、既存は現状維持へ黙って落ち、**空欄と誤記が見分けられなかった**。所属グループは
    //   担当できるシフトを決めるので、誤記が通ると説明のつかない盤面になる。
    val badG = res.unknownGroups
    val badS = res.unknownSkills
    val warn = buildList {
        if (badG.isNotEmpty()) add("グループ記号 ${badG.entries.take(3).joinToString("・") { "「${it.key}」${it.value}件" }}${if (badG.size > 3) "ほか" else ""}")
        if (badS.isNotEmpty()) add("スキル記号 ${badS.entries.take(3).joinToString("・") { "「${it.key}」${it.value}件" }}${if (badS.size > 3) "ほか" else ""}")
    }
    val tailWarn = if (warn.isEmpty()) "" else
        "。⚠ 見つからない${warn.joinToString("／")}（新規は先頭グループ・既存は元のまま。記号をご確認ください）"
    val msg = "職員一覧を取込: " + parts.joinToString("・") + tailWarn
    logOp(if (warn.isEmpty()) "I" else "W",
        "職員一覧CSV取込: 追加${res.added} 更新${res.updated}" +
            (if (badG.isNotEmpty()) " 未知グループ${badG.values.sum()}件" else "") +
            (if (badS.isNotEmpty()) " 未知スキル${badS.values.sum()}件" else ""))
    applyStructureWithMessage(com.magi.app.v6.Ws1Result(res.state, res.schedule), msg)
}

/** [コンポーネント別取込] 希望シフトCSV（氏名,日,希望シフト）。氏名一致で希望を全置換。 */
fun MagiViewModel.importWishesCsv(rawText: String) {
    val st = state ?: run { _ui.update { it.copy(messageIsError = false, message = "先にデータを開いてください（希望シフトは既存データに重ねます）") }; return }
    val text = MojibakeRepair.repair(rawText)
    val res = runCatching { com.magi.app.v6.WishesCsvIO.parse(text, st) }.getOrNull()
    if (res == null) {
        val hint = componentImportMismatchHint(text)
        val tail = if (hint.isEmpty()) "形式は『氏名,日,希望シフト』（例: 古泉 健一,5,休）です。氏名・シフト記号が一致しているかご確認ください。" else hint
        _ui.update { it.copy(messageIsError = true, message = "希望シフトの取込失敗（取り込める行が0件）。$tail") }
        logOp("W", "希望シフトCSV取込 失敗: 0件${if (hint.isEmpty()) "" else "（別形式CSVの取り違えの可能性）"}")
        return
    }
    // [3.329.0/外部レビュー H-02] この取込は既存の希望を**全置換**する。中身のある行を1つでも
    //   解釈できなかったら置換しない（旧: 誤記の行を黙って捨て、1行でも有効なら残りの希望を消していた）。
    if (res.rejected > 0) {
        // [2026-09-02, 外部レビュー#76] 例を最大3件まで列挙（旧: 最初の1件だけ）。原因が複数種類
        //   混在するCSVでも、1回の取込結果から複数の直し所が分かるようにする。
        val examples = res.samples.joinToString(" ／ ")
        _ui.update { it.copy(messageIsError = false, message = "希望シフトの取込を中止しました（読めない行が${res.rejected}件）。" +
            "この取込は既存の希望を置き換えるため、全部読めたときだけ実行します。例: $examples") }
        logOp("W", "希望シフトCSV取込 中止: 読めない行${res.rejected}件（取込可${res.accepted}件）例: $examples")
        return
    }
    logOp("I", "希望シフトCSV取込: ${res.accepted}件を反映（全置換）")
    applyStructureWithMessage(res.state, "希望シフトを取込: ${res.accepted}件を反映（既存の希望は置換）")
}

/** [コンポーネント別取込] 各制約CSV（種別タグ付き）。制約一式＋個人レンジを置換。 */
fun MagiViewModel.importConstraintsCsv(rawText: String) {
    val st = state ?: run { _ui.update { it.copy(messageIsError = false, message = "先にデータを開いてください（各制約は既存データに重ねます）") }; return }
    val text = MojibakeRepair.repair(rawText)
    val res = runCatching { com.magi.app.v6.ConstraintsCsvIO.parse(text, st) }.getOrNull()
    if (res == null) {
        val hint = componentImportMismatchHint(text)
        val tail = if (hint.isEmpty()) "1列目の種別（連勤/禁止連続/群組合せ禁止/個人レンジ 等）をご確認ください。例: 連勤,5,休,14 ／ 個人レンジ,古泉 健一,A4,6,8" else hint
        _ui.update { it.copy(messageIsError = true, message = "各制約の取込失敗（取り込める行が0件）。$tail") }
        logOp("W", "各制約CSV取込 失敗: 0件${if (hint.isEmpty()) "" else "（別形式CSVの取り違えの可能性）"}")
        return
    }
    // [3.329.0/外部レビュー H-02] 制約一式と個人レンジを**全置換**するので、希望と同じ扱いにする。
    if (res.rejected > 0) {
        // [2026-09-02, 外部レビュー#76] 例を最大3件まで列挙（旧: 最初の1件だけ、上のimportWishesCsvと同じ理由）。
        val examples = res.samples.joinToString(" ／ ")
        _ui.update { it.copy(messageIsError = false, message = "各制約の取込を中止しました（読めない行が${res.rejected}件）。" +
            "この取込は既存の制約・個人レンジを置き換えるため、全部読めたときだけ実行します。例: $examples") }
        logOp("W", "各制約CSV取込 中止: 読めない行${res.rejected}件（取込可${res.accepted}件）例: $examples")
        return
    }
    logOp("I", "各制約CSV取込: ${res.accepted}件を反映（制約一式を置換）")
    applyStructureWithMessage(res.state, "各制約を取込: ${res.accepted}件を反映（既存の制約・個人レンジは置換）")
}

/** [コンポーネント別取込/3.547.0] シフト種別の色CSV（記号,色）。掲載された記号だけ upsert で更新、
 *  掲載されない記号の既存カスタム色は変更しない（グリルで確認済み: 3.547.0「載っている分だけ上書き」）。 */
fun MagiViewModel.importShiftColorsCsv(rawText: String) {
    val st = state ?: run { _ui.update { it.copy(messageIsError = false, message = "先にデータを開いてください（シフト色は既存データに重ねます）") }; return }
    val text = MojibakeRepair.repair(rawText)
    val res = runCatching { com.magi.app.v6.ShiftColorsCsvIO.parseUpsert(text, st) }.getOrNull()
    if (res == null) {
        val hint = componentImportMismatchHint(text)
        val tail = if (hint.isEmpty()) "形式『記号,色』（例: 休,#ced1d5）をご確認ください。" else hint
        _ui.update { it.copy(messageIsError = true, message = "シフト色の取込失敗（形式を読めません）。$tail") }
        logOp("W", "シフト色CSV取込 失敗: 解析不能")
        return
    }
    val badK = res.unknownKigou
    val badH = res.invalidHex
    val warn = buildList {
        if (badK.isNotEmpty()) add("未知の記号 ${badK.entries.take(3).joinToString("・") { "「${it.key}」${it.value}件" }}${if (badK.size > 3) "ほか" else ""}")
        if (badH.isNotEmpty()) add("不正な色 ${badH.entries.take(3).joinToString("・") { "「${it.key}」${it.value}件" }}${if (badH.size > 3) "ほか" else ""}")
    }
    if (res.updated == 0) {
        val tailWarn = if (warn.isEmpty()) "形式『記号,色』（例: 休,#ced1d5）をご確認ください。" else "⚠ ${warn.joinToString("／")}"
        _ui.update { it.copy(messageIsError = true, message = "シフト色の取込失敗（反映0件）。$tailWarn") }
        logOp("W", "シフト色CSV取込 失敗: 0件" + (if (warn.isEmpty()) "" else " (${warn.joinToString("／")})"))
        return
    }
    val tailWarn = if (warn.isEmpty()) "" else "。⚠ ${warn.joinToString("／")}（該当行は反映せず現状維持）"
    logOp("I", "シフト色CSV取込: ${res.updated}件を反映" + (if (warn.isEmpty()) "" else " (${warn.joinToString("／")})"))
    applyStructureWithMessage(res.state, "シフト色を取込: ${res.updated}件を反映（掲載されない記号の色は現状維持）$tailWarn")
}
