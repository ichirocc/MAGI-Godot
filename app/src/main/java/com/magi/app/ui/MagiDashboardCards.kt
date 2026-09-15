package com.magi.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import java.time.LocalDate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magi.app.v6.V6PortReport
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.CoverageVerdict
import com.magi.app.v6.MirrorKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

/**
 * CSVのバイト列を文字列へ復号する。妥当な UTF-8 ならそれを採用し、そうでなければ日本の Excel CSV で
 * 一般的な CP932(Shift-JIS) とみなす。先頭の BOM は除去する。これにより Shift-JIS の勤務表CSVが
 * 文字化けせず取り込める（UTF-8 として bytes を読むと壊れていた）。
 */

@Composable
internal fun GuidedFixDialog(ui: UiState, vm: MagiViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shortfalls = ui.coverageDiag?.shortfalls ?: emptyList()
    // [3.401.0] 旧: target を `verdict == FIXABLE && miss > 0` だけで選び、無条件に「この日に動かせる人が
    //   います」と断言していた。しかし `verdict` は「担当できる人数 >= 必要数」という**静的判定**で、
    //   いまの希望・盤面では埋められない枠(blockedNow)も FIXABLE のまま残る（3.344.0 の意図的な区別）。
    //   その結果、**同じホーム画面の CoverageDiagnosisCard が「いまの希望のままでは埋められません」と
    //   言っている枠に対して、この画面だけが「動かせる人がいます」と正反対の約束をしていた**。
    //   押しても必須違反は減らず、何度押しても同じ日が出続ける。→ blockedNow は target にしない。
    val target = shortfalls.firstOrNull { it.verdict == CoverageVerdict.FIXABLE && it.miss > 0 && !it.blockedNow }
    val blocked = shortfalls.filter { it.miss > 0 && it.blockedNow && it.verdict != CoverageVerdict.INFEASIBLE }
    val infeasible = shortfalls.filter { it.verdict == CoverageVerdict.INFEASIBLE }
    // blocked を数えないと「直し終わりました！」と言ってしまう（旧より悪い嘘になる）。
    val allDone = target == null && blocked.isEmpty() && infeasible.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (allDone) "直し終わりました！" else "なおすのを手伝います") },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    target != null -> {
                        Text("${target.dayLabel} の「${target.shiftSymbol}」が ${target.miss}人 足りません。",
                            fontWeight = FontWeight.Bold)
                        Text("この日に動かせる人がいます。だれかを「${target.shiftSymbol}」に入れますか？",
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        val cands = remember(target.dayIndex, target.shiftIndex, ui.coverageDiag) {
                            vm.shortageFixCandidates(target.dayIndex, target.shiftIndex)
                        }
                        // [3.475.0/論理監査] 1回押したら再検査（refreshCheck は非同期）が盤面に追いつくまで全候補を
                        //   無効化する。旧: 候補は押す前の盤面で「抜けても穴が空かない」と判定したものなので、
                        //   連打すると2人目が既に満たした枠へ入り covO と、抜けた側の covU を同時に作れた。
                        // [3.502.0/バックログ#10] 解除の合図は schedule の変化でなく検査世代（ui.checkRev）。旧: remember(ui.schedule) は
                        //   setCell 直後の schedule 変化でリセットされ、coverageDiag が古いまま候補が再有効化されていた。
                        val pressedRev = remember { androidx.compose.runtime.mutableStateOf(-1L) }
                        val pending = pressedRev.value >= 0L && ui.checkRev <= pressedRev.value
                        if (cands.isEmpty()) {
                            // [3.401.0] 汎用の文言でなく、この枠についての診断そのものを出す
                            //   （なぜ動かせないかは CoverageDiagnosis が既に調べて書いている）。
                            Text(target.reason, color = cs.error, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            cands.take(8).forEach { c ->
                                Button(
                                    onClick = { pressedRev.value = ui.checkRev; vm.setCell(c.staffIndex, target.dayIndex, target.shiftIndex) },
                                    enabled = !pending,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 2.dp),
                                ) {
                                    val tail = if (c.fromRest) "（休み）" else ""
                                    // 長い氏名でも切れないよう2行まで折り返し（文字欠け防止）。
                                    Text("${c.name}$tail を「${target.shiftSymbol}」に入れる",
                                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text(if (pending) "再検査中…（結果が反映されるまで候補は押せません）" else "入れたら「元に戻す」でいつでも取り消せます。",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    infeasible.isNotEmpty() -> {
                        Text("これ以上は自動で埋められません。", fontWeight = FontWeight.Bold)
                        infeasible.take(4).forEach {
                            Text("・${it.dayLabel}「${it.shiftSymbol}」：${it.reason}",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Text("人を増やすか、担当できるシフトや希望を見直すと直せます。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    blocked.isNotEmpty() -> {
                        // [3.401.0] 「動かせる人がいる」枠が無くなったが、埋まっていない枠は残っている状態。
                        //   ここで「直し終わりました」と言うのが旧実装の嘘だった。診断が調べた理由をそのまま出す。
                        Text("いまの希望・担当のままでは埋められない日が残っています。", fontWeight = FontWeight.Bold)
                        blocked.take(4).forEach {
                            Text("・${it.dayLabel}「${it.shiftSymbol}」：${it.reason}",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Text("もう一度つくっても、この日は同じ結果になります。希望を1件調整するか、担当できるシフトを増やしてください（編集タブ＞月次条件）。",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    else -> {
                        Text("人手が足りない日はなくなりました。仕上げにもう一度つくると全体が整います。")
                    }
                }
            }
        },
        // [3.480.0 ホームAIリデザイン] 「もう一度つくる」はここにも出していたが、常設の固定フッター
        // (BottomCommandBar)と重複する導線だった（grilling決定#3=フッターに一本化）。ここは常に「閉じる」のみ。
        confirmButton = { DialogDismissButton(onClick = onDismiss, text = "閉じる") },
    )
}

/** [operator_ux §3] 思考誘導カードの1状態分のプラン（文言・色・大ボタン・補助）。 */

internal class OpNextPlan(
    val container: Color, val fg: Color, val headline: String,
    val bigLabel: String, val bigAction: () -> Unit, val bigEnabled: Boolean,
    val helperLabel: String?, val helperAction: () -> Unit,
)

/**
 * [operator_ux §3] 思考誘導ホームの「次にやること」カード。
 * IT中学生レベルのオペレーター向け：専門用語ゼロ・大ボタン1つ・色で意味（緑=できた/黄=もう少し/赤=気をつけて）。
 * いまの状態（未作成／組立中／配れる／もう少し／埋められない）で文言と主ボタンが自動で変わる。
 */

@Composable
internal fun OperatorNextActionCard(
    ui: UiState,
    onMake: () -> Unit,      // 勤務表をつくる（最適化）
    // [3.126.0 撤去] onDraft（下書きをつくる=簡易作成）はユーザー判断で撤去。主導線は「勤務表をつくる」1本。
    onSmartInitial: () -> Unit,  // [新設] 初期解を作る（希望→C1優先の下書き。本最適化は続けない）
    onStop: () -> Unit,      // やめる（停止）
    onExport: () -> Unit,    // 印刷・書き出し / そのまま配る（CSV書き出し）
    onSchedule: () -> Unit,  // 中身を見る（勤務表へ）
    onFix: () -> Unit,       // なおすのを手伝って（勤務表で手直し）
    onSetup: () -> Unit,     // データを見直す（編集へ）
) {
    val cs = MaterialTheme.colorScheme
    val infeasible = ui.coverageDiag?.allInfeasible == true
    val shortDays = ui.coverageDiag?.shortfalls?.map { it.dayIndex }?.distinct()?.size ?: 0
    val worstDay = ui.coverageDiag?.shortfalls?.firstOrNull()?.dayLabel

    // [M3] 成功=tertiary / 注意=error / 主操作=primary はテーマロール。警告のみ独自トークンに集約。
    val (amber, onAmber) = magiWarnColors()

    val plan = when {
        ui.running -> {
            // [校正] 「やめる」は下部コマンドバーに常設済み。カード側の補助ボタンは重複のため出さない。
            // [スクショ指摘/撤去] 見出し文（コンピューターが組んでいます…あと約N分…）は下の進捗行
            //   （残り時間/改善率/回数 = progressSummary）と重複のため出さない（ユーザー赤囲い指示）。
            OpNextPlan(cs.primaryContainer, cs.onPrimaryContainer, "", "", {}, false, null, onStop)
        }
        !ui.hasResult -> OpNextPlan(cs.primaryContainer, cs.onPrimaryContainer,
            "② ボタンひとつで、勤務表を作ります。",
            "勤務表をつくる", onMake, true, "下書きをつくる（希望と期間の制約を先に埋める）", onSmartInitial)
        ui.bestHard == 0L -> OpNextPlan(cs.tertiaryContainer, cs.onTertiaryContainer,
            // [3.509.4/自動化方針] 完了カードに前後比較（変更人数・セル数・希望充足・個人回数）を 1 行足す。
            "③ 完成しました。そのまま配れます。" + (ui.runSummary?.let { "\n$it" } ?: ""),
            "印刷・書き出し", onExport, true, "中身を見る", onSchedule)
        infeasible -> OpNextPlan(cs.errorContainer, cs.onErrorContainer,
            "このデータでは、ここは埋められません。" + (worstDay?.let { "（例：$it）" } ?: ""),
            "データを見直す", onSetup, true, "未充足のまま書き出す", onExport)
        else -> OpNextPlan(amber, onAmber,
            // [監査#1] 人手不足ゼロでも必須違反(希望/禁止連続/群)で此処に来る。不足が無いのに
            //   「人手が足りない」と告げる誤診断を排し、実態（必須違反の残数）を言う。
            (worstDay?.let { "$it が人手不足です。" }
                ?: "必須違反が ${ui.bestHard}件 残っています。"),
            // [3.480.0 ホームAIリデザイン] 補助ボタン「もう一度つくる」は固定フッターと重複のため撤去
            // （grilling決定#3）。この状態の主導線は「なおすのを手伝って」1つに絞る。
            // [3.483.0 H-1] 人手不足が無い狩猟では、この大ボタンは分析タブへ飛んで同じ探索を起動するだけ
            //   ＝直下の「AIの解決提案」と同じ結果を別画面で見せる冗長。不足があるとき（GuidedFix＝
            //   代用要員のピッカーという別機能）だけ出す。
            "なおすのを手伝って", onFix, !ui.coverageDiag?.shortfalls.isNullOrEmpty(), null, {})
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = plan.container)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // [HUD段2] フェーズ名バッジ（探索/完成/未完成）。既存の状態分岐に名前を与えるだけ。
            //   未最適化→探索 / HARD=0→完成 / HARD>0(infeasible含む)→未完成。実行中は非表示（カードが別表示）。
            //   [UX改善/ユーザー指示「ゲーム要素廃止」] 旧「狩猟」はRPG風の演出語のため、完成の対語である
            //   平易な語へ変更（docs/screen_spec.mdが既に記録する「TapGame等の非採用」の徹底）。
            if (!ui.running) {
                val (phName, phColor) = when {
                    !ui.hasResult -> "探索" to MagiAccent.blue
                    ui.bestHard == 0L -> "完成" to MagiAccent.green
                    else -> "未完成" to MagiAccent.orange
                }
                Box(Modifier.background(phColor, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    // [コントラスト] 白文字は淡い原色(緑/橙)で2.2:1と不足するため WCAG 保証（不足時のみ黒へ）。
                    Text(phName, style = MaterialTheme.typography.labelMedium, color = ensureReadable(phColor, Color.White), fontWeight = FontWeight.Bold)
                }
            }
            if (plan.headline.isNotBlank()) Text(plan.headline, style = MaterialTheme.typography.titleLarge, color = plan.fg, fontWeight = FontWeight.Bold)
            // [3.480.0 ホームAIリデザイン] 旧: 「できあがり度：N%」の数字1行＋その意味を説明する注記1行を
            // 常時2行表示していた。grilling決定#1のとおり文言（正直さ）は変えず、①前向きな言い回し
            // 「解消度：N%（残りM件）」＋バーへ統合 ②注記は既定折りたたみ（ConstraintHelpExpander と
            // 同じ開閉パターン）にして、常時見えるのは進捗バー1本だけにする。
            // [3.483.0 H-2] 旧: 必須0なら一律「解消済み」だが、解消度は調整（ソフト）違反が残ると 100% に
            //   ならない（40+比率×60）＝「78%（解消済み）」という自己矛盾。必須0のときは残りの単位を調整件数へ。
            val remainingLabel = when {
                ui.bestHard > 0L -> "必須 残り${ui.bestHard}件"
                shortDays > 0 -> "残り${shortDays}日"
                ui.bestSoft > 0L -> "必須は解消・調整 ${ui.bestSoft}件"
                else -> "解消済み"
            }
            // [3.483.0 H-5] 実行中は直下の進捗行（progressSummary）が同じ残数を出すため、解消度の行とバーは出さない。
            if (!ui.running) {
                Text(
                    "解消度：${ui.satisfaction}%（${remainingLabel}）",
                    style = MaterialTheme.typography.bodyMedium, color = plan.fg, fontWeight = FontWeight.Bold,
                )
                LinearProgressIndicator(
                    progress = { ui.satisfaction.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp),
                    color = plan.fg, trackColor = plan.fg.copy(alpha = 0.2f),
                )
            }
            if (!ui.running && ui.hasResult) {
                var detailOpen by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable { detailOpen = !detailOpen },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        if (detailOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null, tint = plan.fg.copy(alpha = 0.8f), modifier = Modifier.size(16.dp),
                    )
                    Text(
                        if (detailOpen) "ⓘ 詳しい説明を閉じる" else "ⓘ 解消度の意味",
                        style = MaterialTheme.typography.bodySmall, color = plan.fg.copy(alpha = 0.8f),
                    )
                }
                // [判断設計監査 #1/#2] 数字の根拠（できあがり度の意味）と結果採用の意味（承認ステップの
                //   不在を補う注記: 反映済み・取消可・確定は書き出し時）を1文字も変えず折りたたみへ収納。
                if (detailOpen) {
                    Text(
                        "※解消度＝最初からの違反の減り具合（必須違反が残る間は最大55%）。" +
                            "結果は下書きに反映済み・「元に戻す」で取消可・確定は書き出し時です。",
                        style = MaterialTheme.typography.bodySmall, color = plan.fg.copy(alpha = 0.8f),
                    )
                }
            }
            if (ui.running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = plan.fg)
                    // [進捗の見える化] 「組み立て中…」だけでなく 改善率/残り時間/探索数 を出す。
                    Text(progressSummary(ui), color = plan.fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            if (plan.bigEnabled) {
                Button(onClick = plan.bigAction, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(plan.bigLabel, style = MaterialTheme.typography.titleMedium)
                }
            }
            plan.helperLabel?.let { hl ->
                // [校正] 補助操作もテキストリンクではなく外枠ボタンに（カード地色でも見えるよう枠色=前景色）。
                OutlinedButton(
                    onClick = plan.helperAction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = plan.fg),
                    border = BorderStroke(1.dp, plan.fg.copy(alpha = 0.5f)),
                ) { Text(hl) }
            }
            // [3.261.0, ユーザー指摘「初期解生成が何度も出来ない」] !ui.hasResult状態の補助ボタンは
            // hasResult=true化後（=初期解生成の完了直後を含む全ての完成/狩猟/未充足状態）に消えるため、
            // 一度使うと二度と初期解生成へ戻れなかった。小さな常設リンクとして独立させ、実行中以外は
            // 常に再生成できるようにする（元に戻すで取消可能・破壊的でない）。
            if (!ui.running && ui.hasResult) {
                // [3.483.0 H-4] 押すと今の結果を捨てて初期解へ戻る操作なので、その結果と戻し方を文言に含める。
                TextButton(onClick = onSmartInitial, modifier = Modifier.fillMaxWidth()) {
                    Text("下書きから作り直す（今の結果は「元に戻す」で戻せます）")
                }
            }
        }
    }
}

/**
 * [3.480.0 ホームAIリデザイン] 診断カードの「エンジン内部の観測メトリクス」（却下件数の内訳・
 * 計測範囲の注記など）を既定で折りたたむ開閉行。ConstraintEditor.kt の ConstraintHelpExpander と
 * 同じ開閉パターン＝**文言は一切変えず、隠す場所を変えるだけ**（過去の教訓＝断定的な言い換えは危険、
 * を踏まない。3.263.0/3.322.0/3.401.0）。
 */
@Composable
private fun DiagDetailToggle(
    open: Boolean,
    onToggle: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    closedText: String = "ⓘ 詳細（内部の記録）",
    openText: String = "ⓘ 詳細を閉じる",
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null, tint = tint, modifier = Modifier.size(16.dp),
        )
        Text(if (open) openText else closedText, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/**
 * [3.480.0 ホームAIリデザイン] 「スマートアクション」＝AIが先回りして最有力の1手を提示するカード。
 * grilling決定#2どおり新規ロジックは作らず、分析タブと同じ改善提案エンジン（FixSuggester／
 * `ui.fixSuggestions`／`vm.applyFixSuggestion`）の先頭候補（=最も効果の大きい1手）を使う。
 * 必須違反が残っている間だけ表示し、まだ探索していなければ自動で1回探す（`findFixSuggestions` は
 * 副作用が「候補リストの計算」だけで盤面は変えないため、自動起動しても安全＝GuidedFixの狭いスコープ
 * [人員不足のみ] と違い、c3n/c1 等どの族の違反でも先頭候補が出せる）。
 */
@Composable
internal fun SmartActionCard(ui: UiState, vm: MagiViewModel) {
    if (ui.running || !ui.hasResult || ui.bestHard <= 0L) return
    val cs = MaterialTheme.colorScheme
    // 既にある候補が別スタッフに絞った探索(fixFocusName!="")の結果なら、ホームでは全体探索へ差し替える。
    LaunchedEffect(ui.schedule, ui.bestHard) {
        if (!ui.fixSearching && (ui.fixSuggestions.isEmpty() || ui.fixFocusName.isNotBlank())) vm.findFixSuggestions()
    }
    val top = ui.fixSuggestions.firstOrNull()
    if (ui.fixFocusName.isNotBlank() && !ui.fixSearching) return // 探索待ちのフレームだけ描画をスキップ
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AIの解決提案", style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer)
            when {
                ui.fixSearching -> Text("いちばん効果のある直し方を探しています…",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
                top == null -> Text("1手で直せる候補は見つかりませんでした。下の詳細をご確認ください。",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
                else -> {
                    val (tag, tagColor) = fixKindTag(top.kind)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MagiTagChip(text = tag, color = tagColor)
                        Text(top.label, style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f))
                    }
                    val diffTxt = top.diff.joinToString("・") { (k, d) -> "${breakdownLabels[k] ?: k} ${if (d < 0) "−${-d}" else "+$d"}" }
                    val totalTxt = if (top.deltaTotal <= 0) "−${-top.deltaTotal}" else "+${top.deltaTotal}"
                    Text("違反 $totalTxt" + if (diffTxt.isNotBlank()) "（$diffTxt）" else "",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer.copy(alpha = 0.85f))
                    Button(
                        onClick = { vm.applyFixSuggestion(top) },
                        enabled = !ui.running,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("この手を適用（推奨）") }
                    if (ui.fixSuggestions.size > 1) {
                        Text("ほかに ${ui.fixSuggestions.size - 1} 案あります（分析タブで比較できます）。",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

/** [対象月の選択] 勤務表を作る月を前月/翌月/今月で選ぶ。変更でその月の日数に合わせて表を作り直す。 */

@Composable
internal fun CopilotCard(ui: UiState, onGoEdit: () -> Unit, onSoftPolish: () -> Unit = {}) {
    // [冗長性削減] できあがり度・進捗は OperatorNextActionCard が表示するため、ここは助言/警告だけに専念。
    val cs = MaterialTheme.colorScheme
    val show = ui.impossibleWishCount > 0 || ui.copilotHint != null || (ui.polishExhausted && !ui.running)
    if (!show) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 担当外など実現不能な希望の警告（Web版の担当外希望警告に相当）
            if (ui.impossibleWishCount > 0) {
                Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("⚠ 実現できない希望が ${ui.impossibleWishCount} 件（担当外シフトなど）。配布前に見直しを。",
                            color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onGoEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("希望シフトを編集") }
                    }
                }
            }
            // ガチャ操作の助言＋修正導線（NextActionBar相当）
            ui.copilotHint?.let {
                Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("💡 $it", color = cs.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onGoEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("編集タブで見直す") }
                    }
                }
            }
            if (ui.polishExhausted && !ui.running) {
                Surface(color = cs.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✓ 必須は満たしています。残りの調整は自動で減らせます（必須は壊しません）。",
                            color = cs.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSoftPolish, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("自動で整える") }
                            OutlinedButton(onClick = onGoEdit, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("手修正") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 人員不足(covU)が残るときだけ表示する原因診断カード。
 * 各不足枠を「充足不可（データ上どう割り当てても埋まらない）」か
 * 「充足可能（枠は足りる＝最適化が未到達）」に切り分けて、配布前の判断材料にする。
 */

@Composable
internal fun CoverageDiagnosisCard(ui: UiState, onCancelWish: (Int, Int) -> Unit = { _, _ -> }) {
    val diag = ui.coverageDiag ?: return
    if (!diag.hasShortage && !diag.hasSurplus) return
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (diag.hasShortage) {
                Text("人員不足の原因", style = MaterialTheme.typography.titleMedium)
                // [3.344.0] 「充足可能N枠」と言いながら各枠の説明が「いまの希望のままでは解消できません」
                //   という矛盾を解消する。枠の数（静的に足りているか）と、いま実際に埋められるかは別軸。
                val headline = when {
                    diag.allInfeasible -> "不足 ${diag.totalShortfall} 人は全て充足不可。今のデータでは満たせません（想定内）。"
                    diag.allBlockedNow -> "不足 ${diag.totalShortfall} 人は、いまの希望・担当のままでは埋められません。" +
                        "希望を1件調整するか、担当を追加してください。"
                    diag.blockedNowSlots > 0 -> "不足 ${diag.totalShortfall} 人 — うち ${diag.blockedNowSlots} 枠は" +
                        "いまの希望のままでは埋められません（残りは再実行で解消し得ます）。"
                    diag.infeasibleSlots == 0 -> "不足 ${diag.totalShortfall} 人は枠が足りています。再実行や設定の見直しで解消し得ます。"
                    else -> "不足 ${diag.totalShortfall} 人 — 充足不可 ${diag.infeasibleSlots} 枠 / 充足可能 ${diag.fixableSlots} 枠。"
                }
                Text(headline, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                for (s in diag.shortfalls.take(6)) {
                    val infeasible = s.verdict == CoverageVerdict.INFEASIBLE
                    val container = if (infeasible) cs.errorContainer else cs.secondaryContainer
                    val onContainer = if (infeasible) cs.onErrorContainer else cs.onSecondaryContainer
                    Surface(color = container, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${s.dayLabel}  ${s.shiftSymbol}  必要${s.need}/現状${s.got}（不足${s.miss}）",
                                    color = onContainer, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                MagiTagChip(
                                    text = when {
                                        infeasible -> "充足不可"
                                        s.blockedNow -> "今は不能"
                                        else -> "充足可能"
                                    },
                                    color = when {
                                        infeasible -> MagiAccent.red
                                        s.blockedNow -> MagiAccent.orange
                                        else -> MagiAccent.blue
                                    },
                                )
                            }
                            Text(s.reason, color = onContainer, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (diag.shortfalls.size > 6) {
                    Text("ほか ${diag.shortfalls.size - 6} 枠（詳細はログ出力を参照）",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (diag.relaxations.isNotEmpty()) {
                    Surface(color = cs.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("解けるようにするには（担当追加の案）", color = cs.onTertiaryContainer, style = MaterialTheme.typography.titleSmall)
                            for (r in diag.relaxations.take(4)) {
                                Text("・$r", color = cs.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("※ 担当追加の提案です。設定変更は行いません（採否はご判断ください）。",
                                color = cs.onTertiaryContainer.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (diag.hasSurplus) {
                Text("人員過剰がなぜ減らないか", style = MaterialTheme.typography.titleMedium)
                Text("過剰 ${diag.totalSurplus} 人 — 在勤者を他シフトへ動かせば消えるはずが、動かない理由を枠ごとに示します。",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                for (s in diag.surpluses.take(6)) {
                    Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${s.dayLabel}  ${s.shiftSymbol}  必要${s.need}/現状${s.got}（過剰${s.excess}）",
                                color = cs.onSecondaryContainer, style = MaterialTheme.typography.titleSmall)
                            // [3.406.0] 主因は画面では日本語で出す（ログは生キー＝C1Plateau と同じ規約）。
                            val famJp = s.blockedFamily?.let { breakdownLabels[it] ?: it }
                            Text(s.reason + (famJp?.let { "（主因: $it）" } ?: ""),
                                color = cs.onSecondaryContainer, style = MaterialTheme.typography.bodySmall)
                            // [3.492.0] 「希望固定N人」の中身を名指しし、その場で希望を取り消せる（データ修正の導線）。
                            for (i in s.pinnedStaff) {
                                OutlinedButton(
                                    onClick = { onCancelWish(i, s.dayIndex) },
                                    enabled = !ui.running,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) { Text("${ui.staffNames.getOrNull(i) ?: "#$i"} の希望（${s.shiftSymbol}）を取り消す", color = cs.error) }
                            }
                        }
                    }
                }
                if (diag.surpluses.size > 6) {
                    Text("ほか ${diag.surpluses.size - 6} 枠（詳細はログ出力を参照）",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}


/**
 * [3.280.0] 禁止連続(c3n)の「なぜ崩せないか」診断カード。CoverageDiagnosisCard（人員不足/過剰の原因）の
 * c3n 版＝同じ作り。必須違反が残っているのに探索が進まないとき、「構造的に不能（希望調整が必要）」か
 * 「多段手が必要（再実行で解消し得る）」かを違反 run ごとに示す。読取専用・スコア不変。
 */
@Composable
internal fun ForbiddenRunDiagnosisCard(ui: UiState, onRelaxRule: (String) -> Unit = {}) {
    val diag = ui.forbiddenDiag ?: return
    if (!diag.hasRuns) return
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("禁止の並びがなぜ崩せないか", style = MaterialTheme.typography.titleMedium)
            val headline = if (diag.allBlocked) {
                "残り ${diag.totalRuns} 件は全て塞がっています。今の希望・担当のままでは崩せません。"
            } else {
                "残り ${diag.totalRuns} 件 — 崩す手が残っている並びがあります。"
            }
            Text(headline, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            for (r in diag.runs.take(6)) {
                val blocked = !r.escapable
                val container = if (blocked) cs.errorContainer else cs.secondaryContainer
                val onContainer = if (blocked) cs.onErrorContainer else cs.onSecondaryContainer
                Surface(color = container, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${r.staffName}  ${r.seqLabel}",
                                color = onContainer, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            MagiTagChip(
                                text = if (blocked) "崩せない" else "崩す手あり",
                                color = if (blocked) MagiAccent.red else MagiAccent.blue,
                            )
                        }
                        val cellsTxt = r.cells.joinToString(" ・ ") { c ->
                            val tag = when (c.escape) {
                                com.magi.app.v6.ForbiddenCellEscape.FREE -> "崩せる"
                                com.magi.app.v6.ForbiddenCellEscape.CHAIN -> "玉突きで崩せる"
                                com.magi.app.v6.ForbiddenCellEscape.ADJACENT -> "隣接日調整で崩せる"
                                com.magi.app.v6.ForbiddenCellEscape.PINNED -> "希望固定"
                                com.magi.app.v6.ForbiddenCellEscape.BLOCKED -> "塞がり"
                            }
                            "${c.dayLabel} ${c.shiftSymbol}=$tag"
                        }
                        Text(cellsTxt, color = onContainer, style = MaterialTheme.typography.bodySmall)
                        Text(r.hint, color = onContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (diag.runs.size > 6) {
                Text("ほか ${diag.runs.size - 6} 件（詳細はログ出力を参照）",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }

            // [壁の名指しと緩和] 「崩せない」判定の run を並び（ルール）ごとに集約し、そのルールを
            //   その場で削除できるようにする。制約画面へ行って行を探す往復を省く導線。
            //   崩す手が残っている並びは探索で解けうるので出さない（先にルールを消させない）。
            val walls = diag.runs.filter { !it.escapable }
                .groupBy { it.seqLabel }
                .toList()
                .sortedByDescending { it.second.size }
            if (walls.isNotEmpty()) {
                HorizontalDivider()
                Text("崩せない原因の並び", style = MaterialTheme.typography.titleSmall)
                Text("この並びを禁止しているかぎり、必須違反は残ります。ルールを外すとその場で再チェックします（元に戻せます）。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                for ((seqLabel, rows) in walls) {
                    val who = rows.map { it.staffName }.distinct()
                    val whoTxt = who.take(3).joinToString("・") + if (who.size > 3) " ほか${who.size - 3}名" else ""
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(seqLabel, style = MaterialTheme.typography.titleSmall)
                            Text("${rows.size}件（$whoTxt）", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        TextButton(onClick = { onRelaxRule(seqLabel) }, enabled = !ui.running) {
                            Text("この並びの禁止をやめる")
                        }
                    }
                }
            }
        }
    }
}


/**
 * [窓の要件が直せなかった理由 / 3.322.0] 直近の最適化で c1 研磨が候補を却下した記録を、
 * 職員×シフトごとに理由つきで見せる。CoverageDiag（人員不足）・ForbiddenDiag（禁止連続）に続く3枚目。
 *
 * この2枚と違い**盤面から再計算できない**（根拠が「研磨が実際に候補を作って却下した」観測のため）。
 * したがって「構造的に不能」とは言わない — 言えるのは「いまの設定で、**試した**手が却下された」までで、
 * 試していない手の存在は否定しない。回数の幅を見直せば通る可能性が残る、という含みを文言で明示する。
 *
 * [3.325.0] 回数固定の横断集計（研磨パス全体の観測）は c1 固有の話ではないので
 * [PinFixedImpactCard] へ分離した。このカードは c1 の理由だけを扱う。
 */
@Composable
internal fun C1PlateauCard(ui: UiState, onGoEdit: () -> Unit = {}) {
    val diag = ui.c1Plateau ?: return
    val cs = MaterialTheme.colorScheme
    // [3.325.0] c1 が残っているのに却下の観測が1件も無い場合。研磨が起点を取れなかった／後続パスが
    //   別の窓を直して観測分だけ消えた、などで起こる。ここで理由を語ると観測していないことを語ることに
    //   なるので、「原因未確定」と明示して次の一手だけ示す。
    if (diag.causeUnknown) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("期間の制約が残っています（原因未確定）", style = MaterialTheme.typography.titleMedium)
                Text("残り ${diag.remainingC1} 件。今回の整えでは、この残りについて直し方を試した記録が" +
                    "残っていません。原因は特定できていません。もう一度つくると記録が取れる場合があります。",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
        return
    }
    if (!diag.hasEntries) return
    var detailOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("期間の制約がなぜ直せなかったか", style = MaterialTheme.typography.titleMedium)
            // [3.480.0 ホームAIリデザイン] 旧: この注記は常時表示だった。grilling決定#1どおり
            // 文言は変えず、既定折りたたみへ移す（内容は下の DiagDetailToggle 展開時のみ表示）。
            if (detailOpen) {
                // [3.324.0→3.328.0] 断定を外す。3.326.0 で内訳は**決まりごと**に分けたので、
                //   「まとめて数えている」という 3.324.0 当時の注記はもう実態と合わない。
                Text("※ 直近の最適化で試した直し方の記録です。期間の制約は職員・シフト・決まりごとに分けています" +
                    "（同じ決まりの中に複数の期間がある場合はまとめて数えています）。",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            for (e in diag.entries.take(6)) {
                val pin = e.cause == com.magi.app.v6.C1PlateauCause.PIN_CONSTRAINED
                val container = if (pin) cs.errorContainer else cs.secondaryContainer
                val onContainer = if (pin) cs.onErrorContainer else cs.onSecondaryContainer
                Surface(color = container, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(e.label, color = onContainer,
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            MagiTagChip(
                                text = when (e.cause) {
                                    com.magi.app.v6.C1PlateauCause.PIN_CONSTRAINED -> "回数固定で却下"
                                    com.magi.app.v6.C1PlateauCause.SCORE_TRADEOFF -> "他とのトレードオフ"
                                    com.magi.app.v6.C1PlateauCause.NO_CANDIDATE -> "この直し方では候補なし"
                                },
                                color = if (pin) MagiAccent.red else MagiAccent.blue,
                            )
                        }
                        // 根拠の内訳。「試した手が何件あって、何で落ちたか」を数で示す（推測でなく観測）。
                        // [3.480.0] エンジン内部の観測メトリクスのため既定は折りたたみ（DiagDetailToggle）。
                        if (detailOpen) {
                            val parts = ArrayList<String>()
                            if (e.rejectedByPin > 0) parts.add("回数固定で却下 ${e.rejectedByPin}件")
                            if (e.rejectedByScore > 0) parts.add("総合評価で却下 ${e.rejectedByScore}件")
                            if (e.noCandidate > 0) parts.add("候補なし ${e.noCandidate}件")
                            Text(parts.joinToString(" ・ "), color = onContainer, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(e.recommendedAction { fam -> breakdownLabels[fam] ?: fam },
                            color = onContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (diag.entries.size > 6) {
                Text("ほか ${diag.entries.size - 6} 件（詳細はログ出力を参照）",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            DiagDetailToggle(detailOpen, onToggle = { detailOpen = !detailOpen })
            if (diag.pinConstrained > 0) {
                TextButton(onClick = onGoEdit, enabled = !ui.running) { Text("個人の回数を見直す") }
            }
        }
    }
}


/**
 * [回数固定の影響 / 3.325.0] 「回数を固定している（下限＝上限）ことだけが理由で却下された候補試行」の
 * 横断集計。C1PlateauCard から分離した理由は2つ:
 *  - この観測は c1 固有ではない（実データでは適切回数・公平化・連続パターンの研磨が大半を占める）。
 *    c1 が 0 でも回数固定の影響はあり得るので、c1 の診断に従属させると出せなくなる。
 *  - c1 の内訳（職員×シフト）と横断集計（研磨の試行回数）は粒度も母集団も違う。同じカードに混ぜると
 *    どちらの数字なのか読めない。
 *
 * **数字の読み方 [3.327.0 で範囲を訂正]**: 全手数でも改善予測でもない。計測しているのは後処理研磨のうち
 * `V6HotfixPasses` の19パス＋最終LNS 2本だけで、`EliteIntegration`/
 * `C1TemporalFlow`/`CombinatorialRepair` の10箇所と、ピン保護を持たない探索本体(SA/ALNS/LAHC)は計測外。
 * 最大4巡を重複排除せず加算した「計測済みの候補試行数」で、言えるのは
 * 「少なくとも N 回、回数固定だけが却下の理由だった」まで。
 * 0 は「緩めても変わらない」の証明にはならない（計測外の経路がある）。
 */
@Composable
internal fun PinFixedImpactCard(
    ui: UiState,
    onGoEdit: () -> Unit = {},
    onRelax: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
) {
    val attempts = ui.observedPinBlockedAttempts
    if (attempts <= 0) return
    val cs = MaterialTheme.colorScheme
    var detailOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("回数の固定が最適化に与えた影響", style = MaterialTheme.typography.titleMedium)
            Text("回数を固定していることだけが理由で見送られた試行が、少なくとも $attempts 回ありました。" +
                "これらは他の条件では採用できる手でした。",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            // [3.480.0 ホームAIリデザイン] 計測範囲の注記は文言を変えず既定折りたたみへ（grilling決定#1）。
            DiagDetailToggle(detailOpen, onToggle = { detailOpen = !detailOpen })
            if (detailOpen) {
                // [3.324.0/外部レビュー] 幅は決め打ちしない（実測で ±1 が良い月と ±3 が良い月があり優劣が
                //   逆転した）。件数の性質も正直に添える。
                Text("※ 全部の手数ではなく、仕上げの整えのうち計測できた範囲の試行回数です（同じ手を複数回数えている" +
                    "場合があります）。この数が 0 でも、緩めて変わらないとは限りません。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Text("試すときは、対象の職員とシフト、そして緩める幅を決めて、変更する前と後を見比べてください。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            // [3.326.0] どのピンが止めたかを対象別に出し、その場で1段だけ緩められるようにする。
            //   幅は決め打ちせず下限側・上限側を別々に選ばせる（実測で ±1 と ±3 の優劣が逆転したため）。
            //   押すと設定が変わるので「元に戻す」で戻せることを添える。
            if (ui.pinTargets.isNotEmpty()) {
                HorizontalDivider()
                Text("止めていた回数固定", style = MaterialTheme.typography.titleSmall)
                for (t in ui.pinTargets.take(5)) {
                    Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${t.staffName} ${t.shiftKigou}：${t.pinnedCount}回に固定（${t.attempts}回の試行を止めました）",
                                color = cs.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // [3.328.0/外部レビュー] 0回に固定されている行では「下限を1下げる」が
                                //   0 でクランプされて無操作になる（押しても何も起きないボタンだった）。
                                //   下げられるときだけ出す。
                                if (t.pinnedCount > 0) {
                                    TextButton(onClick = { onRelax(t.staff, t.shift, -1, 0) }, enabled = !ui.running) {
                                        Text("下限を1下げる（${t.pinnedCount - 1}〜${t.pinnedCount}）")
                                    }
                                }
                                TextButton(onClick = { onRelax(t.staff, t.shift, 0, 1) }, enabled = !ui.running) {
                                    Text("上限を1上げる（${t.pinnedCount}〜${t.pinnedCount + 1}）")
                                }
                            }
                        }
                    }
                }
                if (ui.pinTargets.size > 5) {
                    Text("ほか ${ui.pinTargets.size - 5} 件（詳細はログ出力を参照）",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Text("押すと設定が変わります。「元に戻す」で戻せます。効果はもう一度つくると分かります。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            TextButton(onClick = onGoEdit, enabled = !ui.running) { Text("個人の回数を見直す") }
        }
    }
}


/**
 * [設定ミスの誘導修正] 制約・希望シフトの入力間違いを「どこが・なぜ・どう直すか」で具体的に提示する。
 * CoverageDiagnosisCard（人員不足の原因）と同じ作りで、配布前に設定を直せるようにするのが目的。
 */
@Composable
internal fun SettingIssuesCard(
    ui: UiState,
    onFix: (com.magi.app.v6.SettingIssue) -> Unit,
    onGoEdit: () -> Unit,
    onClearWishes: () -> Unit = {},
) {
    val issues = ui.settingIssues
    if (issues.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    // [3.480.0 ホームAIリデザイン] 旧: N件を常時カード表示で縦に並べ、6件超は「ほかN件」表記のみだった
    // （既にtake(6)で頭打ちだったが、それでも6枚のカードが常時スクロールを占領していた）。
    // ①「担当外の希望」は同型行がまとまりやすいので一括クリアを先頭に置く ②一覧は既定折りたたみ
    // （DiagDetailToggle・付随事項として扱う）へ。
    var detailOpen by remember { mutableStateOf(false) }
    val wishClearCount = issues.count {
        it.kind == com.magi.app.v6.IssueKind.WISH && it.action == com.magi.app.v6.SettingFixAction.REMOVE_WISH
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("設定の見直し（${issues.size}件）", style = MaterialTheme.typography.titleMedium)
            if (wishClearCount > 1) {
                Button(onClick = onClearWishes, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("担当外の希望を一括クリア（${wishClearCount}件）")
                }
            }
            DiagDetailToggle(
                detailOpen, onToggle = { detailOpen = !detailOpen },
                closedText = "ⓘ 一覧を見る（${issues.size}件）", openText = "ⓘ 一覧を閉じる",
            )
            if (detailOpen) {
                for (s in issues.take(6)) {
                    val label: String
                    val tagColor: androidx.compose.ui.graphics.Color
                    when (s.kind) {
                        com.magi.app.v6.IssueKind.WISH -> { label = "希望"; tagColor = MagiAccent.blue }
                        com.magi.app.v6.IssueKind.CONSTRAINT -> { label = "制約"; tagColor = MagiAccent.red }
                        com.magi.app.v6.IssueKind.DEMAND -> { label = "必要人数"; tagColor = MagiAccent.red }
                        com.magi.app.v6.IssueKind.RANGE -> { label = "回数"; tagColor = MagiAccent.orange }
                    }
                    Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MagiTagChip(text = label, color = tagColor)
                                Text(s.where, color = cs.onErrorContainer, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            }
                            Text(s.problem, color = cs.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                            Text("→ ${s.fix}", color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                            if (s.actionLabel.isNotEmpty()) {
                                Button(onClick = { onFix(s) }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                                    Text(s.actionLabel)
                                }
                            }
                        }
                    }
                }
                if (issues.size > 6) {
                    // [誘導] 重要な順に整列済み。届かない「ログ出力」ではなく、上から直せば解消する旨を案内。
                    Text("ほか ${issues.size - 6} 件（重要な順に表示中。まず上から直してください）", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = onGoEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("設定・希望を編集する") }
        }
    }
}

@Composable
internal fun V6DashboardCard(v6: V6PortReport?) {
    if (v6 == null) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("V6 1ヶ月俯瞰", fontWeight = FontWeight.Bold)
            Text(
                "人員の穴・負荷の偏り・入力ミスを勤務表から直接集計します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            // §5.4 上部: 充足率(coverage%)を大数値ゲージで（必要人数のうち満たせた割合）
            v6.coveragePct?.let { pct ->
                val tint = if (pct >= 100) MaterialTheme.colorScheme.tertiary else if (pct >= 90) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                MagiScoreGauge(
                    score = pct,
                    max = 100,
                    label = "人員充足率",
                    sub = "必要人数 ${v6.demand} のうち満たせた割合",
                    accent = tint,
                )
                // [D3-full案A] 「解消度(全体の完成度)」と「人員充足率(人員の一側面)」は別指標。
                //   役割の違いを明示し、片方を他方の内訳と誤認させない(架空分解を避ける)。
                Text(
                    "※全体の完成度は「解消度」（ホーム）で確認。ここは人員の充足のみ。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                BigStat("HARD Core", v6.hardCore.toString(), Modifier.weight(1f))
                BigStat("Guard", v6.hardGuard.toString(), Modifier.weight(1f))
                BigStat("充足", v6.coveragePct?.let { "$it%" } ?: "-", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (v6.topRiskShortage > 0) "最優先: ${v6.topRiskLabel} に不足 ${v6.topRiskShortage} 枠" else "最優先: 人員不足なし",
                color = if (v6.topRiskShortage > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            // [3.286.0 冗長性C] 日別リスクチップ列（dayRisks）は AttentionCardsSection（日別リスト＝全件＋
            //   要確認のみトグル＋タップ修復）が上位互換のため撤去（3.195.0 で保留した次点候補の実施）。
            Spacer(Modifier.height(10.dp))
            Text(
                "Apt=${"%.2f".format(v6.aptPenalty)} / Equalize=${"%.2f".format(v6.equPenalty)} / Demand=${v6.demand} / covU=${v6.covU}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (v6.sanityWarnings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                v6.sanityWarnings.take(3).forEach {
                    Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            // [3.286.0 冗長性C] 負荷プロフィール（staffProfiles top5）は AttentionCardsSection（人別リスト）が
            //   上位互換のため撤去。固有の生指標（充足率/HARD Core/Guard/Apt/Equalize/sanity警告）のみ残す。
        }
    }
}


/**
 * [N2/⛏11] 重み表カード。weightedScore の内部重み（MirrorKeys.weights）をそのまま描画し、
 * 「できあがり度/スコア」の根拠（どの違反が何倍効くか）を上級者が逆算できるようにする。
 * 重みは最適化器と同じマップを参照＝表示と最適化器が常に一致（統一思想の延長）。プロ表示時のみ。
 */
@Composable
internal fun WeightTableCard() {
    fun fmt(w: Double): String = if (w == w.toLong().toDouble()) w.toLong().toString() else w.toString()
    val sorted = MirrorKeys.weights.entries.sortedByDescending { it.value }
    val hard = sorted.filter { it.value >= 1000.0 }
    val soft = sorted.filter { it.value < 1000.0 }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // [3.396.0] 旧: 「重み表（最適化器と一致）」＋「スコアの内部重み。大きいほど…」＝
            //   初見の人は「重み？ 最適化器？」と聞き返す。見出しと**並び順そのもの**（上から重い順）が
            //   読み方を教える形にし、説明の一文は落とした。英字コード(HARD/SOFT)も作り手語彙なので外す
            //   （日本語が既に同じことを言っている＝operator_ux「英字符号を画面に出さない」）。
            // [3.532.0] タイトルは呼出元の CollapsibleSection ヘッダが担う（設定タブの静音化・二重見出し回避）。
            Text("上にあるものから先に直します。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("絶対に守る", style = MaterialTheme.typography.titleSmall)
            hard.forEach { (k, w) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(breakdownLabels[k] ?: k, modifier = Modifier.weight(1f))
                    Text("×${fmt(w)}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
                }
            }
            Text("できれば守る", style = MaterialTheme.typography.titleSmall)
            soft.forEach { (k, w) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(breakdownLabels[k] ?: k, modifier = Modifier.weight(1f))
                    Text("×${fmt(w)}", fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}


// [3.286.0 冗長性C] RiskChip は dayRisks チップ列の撤去で呼出0となったため削除。


// [3.409.7] 内訳の家族キー → 日本語ラベル（breakdownLabels）は BreakdownLabels.kt へ移した。
//   Compose に依存しない表なので、切り出して「MirrorKeys.all と過不足なく一致する」をテストで固定する。

/**
 * [分析→場所] 内訳の家族キー(low/covO/c42 等)から、その違反の「場所」と関係スタッフindexを返す。
 *  - count系(low/high/c2)   : スタッフ名「シフト」 / staff=i        (countViolations: i,k)
 *  - 被覆系(covU/covO/c41/c41s): 日付「シフト」 / staff=null         (needViolations: k,j)
 *  - セル系(c1/c3/c3n/c3m/c3mn/c42/c42s/pref/groupViol): スタッフ名 日付=実シフト / staff=i (violationCells: i,j)
 * staff!=null の項目はタップで「そのスタッフが関わる交換」を探せる。
 */
internal fun breakdownLocations(famKey: String, ui: UiState): List<Pair<String, Int?>> {
    fun nm(i: Int) = ui.staffNames.getOrNull(i) ?: "#$i"
    fun sym(k: Int) = ui.shiftSymbols.getOrNull(k) ?: "$k"
    val want = "vio-$famKey"
    // [/code-review, 3.111.0/3.353.0と同根の第3キー空間] 重い族(covU8000/low90/high45等)と同じセルに
    //   軽い族(apt/c2/c41/c41s等)が重なると、単一クラスの countViolations/needViolations からは消える
    //   （内訳の件数は breakdown で正しいのに、タップ→場所一覧だけ件数より少なく見える）。*Families
    //   （全クラス保持）を先に見て、無ければ単一クラス版へフォールバック（読込直後等ui未充填時の保険）。
    fun countHits(target: String): List<String> =
        if (ui.countFamilies.isNotEmpty()) ui.countFamilies.entries.filter { target in it.value }.map { it.key }
        else ui.countViolations.entries.filter { it.value == target }.map { it.key }
    fun needHits(target: String): List<String> =
        if (ui.needFamilies.isNotEmpty()) ui.needFamilies.entries.filter { target in it.value }.map { it.key }
        else ui.needViolations.entries.filter { it.value == target }.map { it.key }
    return when (famKey) {
        "low", "high", "c2" -> countHits(want).mapNotNull {
            val p = it.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val k = p.getOrNull(1)?.toIntOrNull()
            if (i == null || k == null) null else ("${nm(i)} 「${sym(k)}」" to i)
        }
        // 適切回数(apt) は不足=vio-aptLow / 超過=vio-aptHigh の2クラスで countViolations(i,k) に入る。
        "apt" -> (countHits("vio-aptLow") + countHits("vio-aptHigh")).distinct().mapNotNull {
            val p = it.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val k = p.getOrNull(1)?.toIntOrNull()
            if (i == null || k == null) null else ("${nm(i)} 「${sym(k)}」" to i)
        }
        "covU", "covO", "c41", "c41s" -> needHits(want).mapNotNull {
            val p = it.split(","); val k = p.getOrNull(0)?.toIntOrNull(); val j = p.getOrNull(1)?.toIntOrNull()
            if (k == null || j == null) null else ("${dayMD(ui.startDate, j)} 「${sym(k)}」" to null)
        }
        // [場所表示] fair/weekly はセル単位でなく職員×シフト単位の偏り。distLocations から整形。
        // [3.345.0] weekly も職員×シフト（旧: 職員のみ）。休も1シフトとして出る。
        "weekly" -> (ui.distLocations["weekly"] ?: emptyList()).mapNotNull { e ->
            val i = e.getOrNull(0) ?: return@mapNotNull null; val k = e.getOrNull(1) ?: return@mapNotNull null
            val dev = e.getOrNull(2) ?: 0
            "${nm(i)} 「${sym(k)}」（曜日の偏り ${dev}）" to i
        }
        "fair" -> (ui.distLocations["fair"] ?: emptyList()).mapNotNull { e ->
            val i = e.getOrNull(0) ?: return@mapNotNull null; val k = e.getOrNull(1) ?: return@mapNotNull null; val dev = e.getOrNull(2) ?: 0
            "${nm(i)} 「${sym(k)}」（偏り ${dev}）" to i
        }
        else -> {
            val hits = if (ui.violationCellFamilies.isNotEmpty())
                ui.violationCellFamilies.entries.filter { want in it.value }.map { it.key }
            else ui.violationCells.entries.filter { it.value == want }.map { it.key }
            hits.mapNotNull {
                val p = it.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val j = p.getOrNull(1)?.toIntOrNull()
                if (i == null || j == null) null else {
                    val cell = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
                    ("${nm(i)} ${dayMD(ui.startDate, j)}=${if (cell >= 0) sym(cell) else "—"}" to i)
                }
            }
        }
    }
}

// [死にコード整理] BottleneckCard は 3.81.0 で AttentionCardsSection(全件＋トグル＋タップ修復)が上位互換となり
//   詳細タブから撤去済み。呼出0の composable 定義もここで撤去した（履歴は git にある）。

// [3.112.0 撤去] HeroMetricsRow（対象人数/対象期間の2タイル）はユーザー赤囲い指示で撤去。
//   読込ステータス行「読込完了: N名/N日/Nシフト」と重複する固定値で、トリアージに寄与しないため。

/** [★1/E1] 要確認一覧の1項目。個々の違反箇所を重大度マーク付きで表す（web「画面修正版」confirm ビュー移植）。 */
private data class ConfirmItem(
    val kind: Int,          // 0=不足/必須(bad) 1=過剰/調整(warn) 2=窓(c1)
    val mark: String,       // バッジ内グリフ（2文字以内）
    val title: String,      // 場所（職員名 / 日「シフト」）
    val sub: String,        // 族ラベル（人員不足・下限割れ 等）
    val staff: Int?,        // タップ修復のフォーカス職員（null=全体探索）
    val day: Int?,          // [ジャンプ] セル違反の日index（null=セルに紐付かない項目）
    val order: Int,         // 並び（kind→場所）
    // [下流→上流ディープリンク] pref違反→希望シフト登録(該当職員) / covU・covO→必要人数カレンダー(該当シフト)。
    //   null=そのカレンダーへの「設定で直す」導線を出さない。
    val wishStaff: Int? = null,
    val needShift: Int? = null,
    /**
     * [3.471.0] この項目に重なっている**生の族キー**（`MirrorKeys.all`）。`sub` は複数族を「・」で
     * 連結した表示ラベルなので突合に使えない（`AnalysisTriageCard` が族ごとに場所を数件だけ出すのに要る）。
     */
    val families: List<String> = emptyList(),
)

/**
 * [★1/E1] 各違反マップ（needViolations/countViolations/violationCells）を個々の「要確認」項目に展開。
 * BreakdownCard が族単位の集計なのに対し、こちらは spec「画面修正版」confirm ビューと同型の箇所単位リスト。
 * 表示のみ・スコアリング不変（読取専用）。
 */
private fun confirmItems(ui: UiState): List<ConfirmItem> {
    fun nm(i: Int) = ui.staffNames.getOrNull(i) ?: "#$i"
    fun sym(k: Int) = ui.shiftSymbols.getOrNull(k) ?: "$k"
    val out = ArrayList<ConfirmItem>()
    // 被覆・群レンジ: needViolations "k,j" -> vio-covU/covO/c41/c41s（日×シフト）
    for ((key, cls) in ui.needViolations) {
        val p = key.split(","); val k = p.getOrNull(0)?.toIntOrNull() ?: continue; val j = p.getOrNull(1)?.toIntOrNull() ?: continue
        val fam = cls.removePrefix("vio-")
        // [④用語統一] 過剰マークは「過剰」（凡例/集計と同語）。[⑥日別ジャンプ] day=j で勤務表の該当日列へ飛べる。
        val (mark, kind) = when (fam) { "covU" -> "不足" to 0; "covO" -> "過剰" to 1; else -> "調整" to 1 }
        out += ConfirmItem(kind, mark, "${dayMD(ui.startDate, j)}「${sym(k)}」", breakdownLabels[fam] ?: fam, null, j, kind * 100000 + j * 100 + k,
            needShift = if (fam == "covU" || fam == "covO") k else null, families = listOf(fam))
    }
    // 個人回数: countViolations "i,k" -> vio-low/high/c2/aptLow/aptHigh（職員×シフト）
    for ((key, cls) in ui.countViolations) {
        val p = key.split(","); val i = p.getOrNull(0)?.toIntOrNull() ?: continue; val k = p.getOrNull(1)?.toIntOrNull() ?: continue
        val fam = cls.removePrefix("vio-")
        // c2(個人の合計) は方向を持たない単一クラス vio-c2 → 「過」ではなく中立マーク「計」（誤って過剰と表示しない）。
        val (mark, kind) = when (fam) { "low", "aptLow" -> "不足" to 0; "c2" -> "計" to 1; else -> "過剰" to 1 }
        val labelFam = when (fam) { "aptLow", "aptHigh" -> "apt"; else -> fam }
        out += ConfirmItem(kind, mark, nm(i), "${sym(k)}・${breakdownLabels[labelFam] ?: labelFam}", i, null, kind * 100000 + 40000 + i * 100 + k,
            families = listOf(labelFam))
    }
    // セル違反: violationCells "i,j" -> vio-pref/groupViol/c3n/c3/c3m/c3mn/c1/c42/c42s（職員×日=実シフト）
    for ((key, cls) in ui.violationCells) {
        val p = key.split(","); val i = p.getOrNull(0)?.toIntOrNull() ?: continue; val j = p.getOrNull(1)?.toIntOrNull() ?: continue
        val fam = cls.removePrefix("vio-")
        val cell = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
        val cellSym = if (cell >= 0) sym(cell) else "—"
        val (mark, kind) = when (fam) {
            "c1" -> "窓" to 2
            "pref", "groupViol", "c3n", "c3w" -> "必須" to 0
            else -> "調整" to 1
        }
        // [Set化] 同セルに重なった族は sub に全列挙（重み降順）。行数=箇所数は不変（見出し件数の意味を保つ）。
        val famsAll = (ui.violationCellFamilies[key] ?: listOf(cls)).map { it.removePrefix("vio-") }
        val sub = famsAll.joinToString("・") { breakdownLabels[it] ?: it }
        out += ConfirmItem(kind, mark, "${nm(i)} ${dayMD(ui.startDate, j)}=$cellSym", sub, i, j, kind * 100000 + 80000 + j * 100 + i,
            wishStaff = if ("pref" in famsAll) i else null, families = famsAll)
    }
    return out.sortedWith(compareBy({ it.kind }, { it.order }))
}


/** [★1] 要確認一覧の1行（spec confirmCard 同型：マークバッジ＋タイトル＋族＋メタ）。 */
@Composable
private fun ConfirmRow(
    item: ConfirmItem, onFocusStaff: (Int) -> Unit, onShowCell: (Int, Int) -> Unit, onShowDay: (Int) -> Unit,
    onFixWish: (Int) -> Unit = {}, onFixNeed: (Int) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val (warnBg, warnFg) = magiWarnColors()
    val (bg, fg) = when (item.kind) {
        0 -> cs.errorContainer to cs.onErrorContainer
        1 -> warnBg to warnFg
        else -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
    // [⑥日別ジャンプ] staff無し・day有り（人員/群レンジ=日×シフト）は勤務表の該当日列へ移動できる。
    val dayOnly = item.staff == null && item.day != null
    val clickable = item.staff != null || dayOnly
    var m = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    if (clickable) m = m.clickable {
        val s = item.staff; val d = item.day
        when { s != null && d != null -> onShowCell(s, d); s != null -> onFocusStaff(s); d != null -> onShowDay(d) }
    }.semantics { contentDescription = "${item.title} ${item.sub}・" + (if (dayOnly) "タップで勤務表の該当日へ" else "タップで直し方を探す") }
    Surface(color = cs.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = m) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = bg, shape = MaterialTheme.shapes.small) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Text(item.mark, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.sub, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // [下流→上流ディープリンク] 末尾に「設定で直す」（行本体タップの勤務表/直し方導線とは別アクション）。
            //   ローカル val に取り出してラムダ内スマートキャストを安全化。
            val ws = item.wishStaff
            val ns = item.needShift
            when {
                ws != null -> TextButton(onClick = { onFixWish(ws) }) { Text("設定で直す") }
                ns != null -> TextButton(onClick = { onFixNeed(ns) }) { Text("設定で直す") }
                // [3.483.0 A-3] 「直し方→」は同じタブ内の下のカード（改善の提案）で探索が始まるだけ＝別画面へ
                //   飛ぶように読めた。動作をそのまま言う。
                clickable -> Text(if (dayOnly) "勤務表→" else "直し方を探す", style = MaterialTheme.typography.labelMedium,
                    color = ensureReadable(cs.surfaceVariant, MagiAccent.blue))
            }
        }
    }
}




/**
 * [3.471.0/分析タブ再構築] 分析タブを**スクロール最小の1画面**にまとめたカード。
 *
 * 旧 3枚統合カード（3.459.0）は「一般/プロ」「一覧/日別・人別/内訳」「全部/不足/過剰/窓」「族チップ6種」と
 * **4層の切り替え**を積み、そのうえで 0 件の項目まで並べていたため縦が極端に伸びていた。
 * ここでは切り替えを全部やめ、上から下へ流れる1本にする。
 *
 * ## 分類は族でなく「データを直さない限り消えるか」（根拠は [analysisTriage] に集約）
 * 「連勤・並びは自動で直るので手動修正は不要」と族の名前で断定すると、3.263.0 / 3.322.0 / 3.344.0 で
 * 直してきた楽観バイアスが戻る（c1・c3n はデータ次第で最適化では消せない）。上段へ上げるのは
 * **必須違反**と、**診断（settingIssues / forbiddenDiag / c1Plateau / coverageDiag）が構造的に残ると
 * 判定したもの**だけ。中段は「エンジンが挑戦する項目」であって「直さなくてよい」ではない旨を注記する。
 *
 * ## 同種を畳む
 * 設定の破綻は「古泉・Dﾃ」「山本・Dﾃ」…と1人1行で伸びていたのを種類ごとの1行へ集約する。
 * ただし**必須違反だけは場所を最大3件そのまま出す**（どのセルかが分からないと直しに行けない）。
 *
 * 表示のみ・読み取り専用＝スコアリング/エンジンは完全に不変。
 */
@Composable
internal fun AnalysisTriageCard(
    ui: UiState,
    onFocusStaff: (Int) -> Unit,
    onGoEdit: () -> Unit,
    onShowCell: (Int, Int) -> Unit,
    onShowDay: (Int) -> Unit,
    onFixWish: (Int) -> Unit,
    onFixNeed: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val (warnBg, warnFg) = magiWarnColors()
    val t = remember(ui.breakdown, ui.settingIssues, ui.coverageDiag, ui.forbiddenDiag, ui.c1Plateau, ui.hasResult) { analysisTriage(ui) }
    val items = remember(ui.violationCells, ui.violationCellFamilies, ui.needViolations, ui.countViolations, ui.schedule, ui.staffNames, ui.shiftSymbols, ui.startDate) { confirmItems(ui) }
    var showSummary by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("分析", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Surface(color = if (t.computed) cs.tertiaryContainer else cs.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text(
                        if (t.computed) "計算済み" else "未計算",
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (t.computed) cs.onTertiaryContainer else cs.onSurfaceVariant,
                    )
                }
            }

            if (!t.hasAnything && ui.schedule.isNotEmpty() && !ui.running) {
                Text("確認事項はありません（すべての条件を満たしています）。",
                    style = MaterialTheme.typography.bodyMedium, color = cs.tertiary)
            }

            if (t.blockers.isNotEmpty() || t.issues.isNotEmpty()) {
                Text("直さないと消えない項目", style = MaterialTheme.typography.titleSmall, color = cs.error)
                t.blockers.forEach { row ->
                    TriageRowLine(row, cs.errorContainer, cs.onErrorContainer)
                    val fam = row.family
                    if (fam != null && fam in MirrorKeys.hard) {
                        // 必須違反は「どのセルか」が分からないと直しに行けないので場所を数件だけ出す。
                        val locs = items.filter { fam in it.families }
                        locs.take(3).forEach { ConfirmRow(it, onFocusStaff, onShowCell, onShowDay, onFixWish, onFixNeed) }
                        if (locs.size > 3) Text("ほか ${locs.size - 3} 件", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                t.issues.forEach { row ->
                    Surface(color = warnBg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${row.label} ${row.count}件", style = MaterialTheme.typography.bodyMedium, color = warnFg, fontWeight = FontWeight.SemiBold)
                                if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.labelMedium, color = warnFg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = onGoEdit) { Text("設定へ") }
                        }
                    }
                }
            }

            if (t.searching.isNotEmpty()) {
                Text(
                    if (t.computed) "計算後に残っている項目" else "エンジンが挑戦する項目（未計算）",
                    style = MaterialTheme.typography.titleSmall,
                )
                Surface(color = cs.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        t.searching.forEach { row ->
                            Row {
                                Text("・${row.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${row.count}${row.unit}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            }
                        }
                        Text("※${t.searchNote}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }

            // 0件の族は畳む＝旧実装は「人員0」「希望0」…が画面の半分を占めていた。
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { showSummary = !showSummary },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("制約充足サマリー（正常 ${t.okFamilies.size} / 残り ${t.busyFamilies.size}）",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(if (showSummary) "閉じる ∧" else "全${t.okFamilies.size + t.busyFamilies.size}項目を展開 ∨",
                    style = MaterialTheme.typography.labelMedium, color = ensureReadable(cs.surface, MagiAccent.blue))
            }
            if (showSummary) {
                if (t.okFamilies.isNotEmpty()) Text("✔ 正常（${t.okFamilies.size}項目）: " + t.okFamilies.joinToString(" / "),
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                if (t.busyFamilies.isNotEmpty()) Text("⚠ 残っている（${t.busyFamilies.size}項目）: " + t.busyFamilies.joinToString(" / "),
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }

            // [3.483.0 A-1] 旧「▶ 勤務表をつくる」ボタンは撤去。固定フッター（BottomCommandBar）の同名ボタンと重複
            //   （3.480.0 ホーム／3.482.0 編集タブで「フッターに一本化」した方針の取り残し）。
            if (ui.running) Text("※実行中のため確定前の値です（確定後に最新化）", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/** 上段の1行（族の見出し＋件数＋診断が語った理由）。理由が空なら何も主張しない。 */
@Composable
private fun TriageRowLine(row: TriageRow, bg: Color, fg: Color) {
    Surface(color = bg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${row.label} ${row.count}${row.unit}" + if (row.promoted) "（構造的に残ると判定）" else "",
                style = MaterialTheme.typography.bodyMedium, color = fg, fontWeight = FontWeight.SemiBold)
            if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.labelMedium, color = fg)
        }
    }
}




@Composable
internal fun BreakdownGroup(title: String, keys: List<String>, severity: Int, ui: UiState, labels: Map<String, String>, expanded: String?, onTap: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        keys.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key -> SeverityChip(labels[key] ?: key, ui.breakdown[key] ?: 0, severity, key, expanded == key, onTap, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}


@Composable
internal fun SeverityChip(label: String, count: Int, severity: Int, famKey: String, expanded: Boolean, onTap: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val active = count > 0
    val container: Color; val onContainer: Color
    when {
        !active -> { container = cs.surfaceVariant; onContainer = cs.onSurfaceVariant }
        severity >= 2 -> { container = cs.errorContainer; onContainer = cs.onErrorContainer }
        severity == 1 -> { container = cs.secondaryContainer; onContainer = cs.onSecondaryContainer }
        else -> { container = cs.primaryContainer; onContainer = cs.onPrimaryContainer }
    }
    val shape = MaterialTheme.shapes.small
    var m = modifier.heightIn(min = 48.dp)
    if (expanded) m = m.border(2.dp, onContainer.copy(alpha = 0.7f), shape)
    if (active) m = m.clickable { onTap(famKey) }
        .semantics { contentDescription = "$label $count 件" + (if (expanded) "・展開中" else "・タップで場所を表示") }
    Surface(color = container, shape = shape, modifier = m) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = onContainer,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = onContainer)
            if (active) {
                Spacer(Modifier.width(2.dp))
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null, tint = onContainer, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(
            Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // [B7] 5桁等の大きな値でも2行に折り返して崩れないよう1行固定＋省略（hardCore/Guard の大値は稀）。
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}


/** 改善手の種類 → (チップ文言, 色)。 */
private fun fixKindTag(k: com.magi.app.v6.FixKind): Pair<String, androidx.compose.ui.graphics.Color> = when (k) {
    com.magi.app.v6.FixKind.CHANGE -> "変更" to MagiAccent.green
    com.magi.app.v6.FixKind.CHANGE_MULTI -> "複数変更" to MagiAccent.green
    com.magi.app.v6.FixKind.SWAP -> "交換" to MagiAccent.blue
    com.magi.app.v6.FixKind.SWAP_XDAY -> "別日交換" to MagiAccent.blue
    com.magi.app.v6.FixKind.SWAP_MULTI -> "3人交換" to MagiAccent.purple
    com.magi.app.v6.FixKind.CHAIN -> "連鎖" to MagiAccent.red
    com.magi.app.v6.FixKind.WINDOW -> "再最適化" to MagiAccent.orange
}

@Composable
internal fun FixSuggestionCard(ui: UiState, onSearch: () -> Unit, onApply: (com.magi.app.v6.FixSuggestion) -> Unit, proMode: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    // [3.483.0 A-2] ホームの「AIの解決提案」は必須違反が残っていれば自動で探すのに、ここは「探す」を押すまで空
    //   ＝同じエンジンで挙動が違った。同じ条件（結果あり・必須>0・未探索）で自動探索を共有する。
    //   ソフトだけの盤面は従来どおり手動（探索コストに見合う候補が少ない）。
    LaunchedEffect(ui.schedule, ui.bestHard) {
        if (ui.hasResult && ui.bestHard > 0L && !ui.running && !ui.fixSearching &&
            ui.fixSuggestions.isEmpty() && ui.fixFocusName.isBlank()) onSearch()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = "改善の提案" + if (ui.fixFocusName.isNotBlank()) "：${ui.fixFocusName} 関連" else ""
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (ui.fixSearching) {
                    Text("探索中…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                } else {
                    TextButton(onClick = onSearch) { Text(if (ui.fixSuggestions.isEmpty()) "探す" else "全体で再探索") }
                }
            }
            if (!proMode) {
                Text("違反を減らす1手を効果順に提案します。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            when {
                ui.fixSearching -> Text("候補を探しています。少しお待ちください。", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                ui.fixSuggestions.isEmpty() -> Text("候補がありません。「探す」を押すか、上の違反の場所をタップしてください。\n※1手で直せない違反（下限が競合する等の構造的不足）は、設定の見直しが根本解です。",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                else -> ui.fixSuggestions.forEach { s ->
                    val (tag, tagColor) = fixKindTag(s.kind)
                    Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MagiTagChip(text = tag, color = tagColor)
                                Text(s.label, style = MaterialTheme.typography.titleSmall, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f))
                            }
                            val diffTxt = s.diff.joinToString("・") { (k, d) ->
                                "${breakdownLabels[k] ?: k} ${if (d < 0) "−${-d}" else "+$d"}"
                            }
                            val totalTxt = if (s.deltaTotal <= 0) "−${-s.deltaTotal}" else "+${s.deltaTotal}"
                            Text("違反 $totalTxt" + if (diffTxt.isNotBlank()) "（$diffTxt）" else "",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
                            Button(onClick = { onApply(s) }, enabled = !ui.running, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                                Text("この手を適用")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * [3.480.0 ホームAIリデザイン] 「他の案」を単純なボタン列からセグメントコントロール（タブ切替）へ。
 * grilling決定#4のとおり `ui.alternatives` は要約文字列のみ（案ごとの盤面フルコピーはキャッシュされて
 * いない）ため、タブ切替は「上の表示だけ差し替えるプレビュー」ではなく既存の `applyAlternative`
 * （Undo付きの完全コミット）をそのまま呼ぶ＝**タップ＝即時反映**。
 */
@Composable
internal fun AlternativesCard(ui: UiState, onApply: (Int) -> Unit) {
    if (ui.alternatives.isEmpty()) return
    var selected by rememberSaveable(ui.alternatives) { mutableStateOf(-1) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("他の案（${ui.alternatives.size}）", style = MaterialTheme.typography.titleMedium)
            Text("タップした案をすぐに反映します（「元に戻す」でいつでも取消せます）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MagiSegmentedControl(
                options = ui.alternatives.indices.map { "案${it + 1}" },
                selected = selected,
                onSelect = { i -> selected = i; onApply(i) },
            )
            // [3.483.0 H-3] タップ＝即反映なので「見てから選ぶ」ために全案の要約（必須/合計）を常時出す。
            //   反映済みの案は太字。
            ui.alternatives.forEachIndexed { i, s ->
                Text(s, style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


@Composable
internal fun WishApplyCard(ui: UiState, onApply: () -> Unit) {
    if (!ui.loaded) return
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("希望シフトを反映", fontWeight = FontWeight.Bold)
                Text("登録済みの希望を勤務表へ上書きします（元に戻せます）。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onApply, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text("希望を反映する") }
        }
    }
}

