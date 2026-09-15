package com.magi.app.ui

import com.magi.app.toHankakuKigou
import com.magi.app.v6.V6SanityPort

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextOverflow
import com.magi.app.model.Group
import com.magi.app.model.Shift
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * ws1 (初期設定) editor card. Edits the problem definition: period length (days),
 * use2 flag, shifts / groups / staff (rename + per-field edit, append-add), and the
 * group×shift bucket. Each change re-dimensions the working table consistently
 * (MagiViewModel.ws1* -> Ws1Ops) and re-runs the check; saving emits the full state.
 * Remove operations are deferred to a later increment.
 */
@Composable
fun Ws1Card(ui: UiState, vm: MagiViewModel) {
    val v = vm.ws1() ?: return
    var dialog by remember { mutableStateOf<Ws1Dialog?>(null) }
    var daysText by remember(v.days) { mutableStateOf(v.days.toString()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader("マスター設定")
            Spacer(Modifier.height(6.dp))
            Text("変更すると表を作り直し、すぐ問題がないか調べ直します。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)

            // --- period ---
            Spacer(Modifier.height(10.dp))
            // [3.483.0 E-5] 旧「期間／対象月」＝対象の月は月次条件の「対象の月」で選ぶため、ここは日数だけ。
            SectionHeader("期間の日数（月単位以外の特殊な期間用）")
            Text("対象の月は「月次条件」の『対象の月』で選びます。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${v.startDate} 〜 ${v.endDate}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                W1Field("日数(1-31)", daysText, Modifier.width(130.dp)) { daysText = it }
                EditRowButton(onClick = { daysText.toIntOrNull()?.let { vm.ws1ResizeDays(it) } }, enabled = !ui.running, text = "変更")
            }

            // --- use2 ---
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("必要人数の2パターン目を使う（特殊な月用・通常はOFF）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = v.use2, onCheckedChange = { vm.ws1SetUse2(it) })
            }
            Divider()

            // --- shifts ---
            Spacer(Modifier.height(8.dp))
            SectionHeader("シフト種別 (${v.shifts.size})")
            Text("タップで編集、ハンドル(${DRAG_HANDLE_GLYPH})を長押しして並び替え。必要人数を変更（勤務表と制約にも反映）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // [3.515.6/ドラッグ&ドロップ・ユーザー指示] ▲/▼(3.515.3)から並び替えを行内ドラッグへ変更。
            //   削除は行から編集シートの中へ移した（行はタップ＝編集・ハンドル長押し＝並び替えの2導線に整理）。
            ReorderableRows(items = v.shifts, enabled = !ui.running, onMove = { from, to -> vm.ws1MoveShiftTo(from, to) }) { k, s, dragHandle ->
                // [不具合修正] 行に .clickable が無く、シフト行をタップしても選択/編集できなかった
                //   （小さな「編集」ボタンのみ反応）。行全体タップで編集ダイアログを開く。
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !ui.running) { dialog = Ws1Dialog.EditShift(k, s.name, s.kigou, s.need1, s.need2) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DragHandle, contentDescription = "ドラッグで並び替え", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp).padding(12.dp).then(dragHandle))
                    // [3.483.0 E-6] 旧「(最低 -人 / 上限 -人)」＝未設定でも括弧が並び1行が長かった。設定時だけ短く添える。
                    val needLabel = when {
                        s.need1.isBlank() && s.need2.isBlank() -> ""
                        s.need2.isBlank() -> "  必要 ${s.need1}人"
                        else -> "  必要 ${s.need1.ifBlank { "-" }}〜${s.need2}人"
                    }
                    Text("${toHankakuKigou(s.kigou)}  ${s.name}$needLabel",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // [3.409.11] 残り1シフトのとき削除ボタンが**理由の説明なく消える**（3.400.0 でグループには
            //   理由を付けたが、シフトと職員は対象漏れだった）。同じ形で理由を出す。
            if (v.shifts.size <= 1) {
                Text("最後の1シフトは削除できません（勤務表のセルが指す先が無くなるため）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            AddRowButton("シフト追加", onClick = { dialog = Ws1Dialog.AddShift }, enabled = !ui.running)
            AddRowButton("一括追加", onClick = { dialog = Ws1Dialog.BulkAddShift }, enabled = !ui.running)   // [⛏12]
            Divider()

            // --- groups ---
            Spacer(Modifier.height(8.dp))
            SectionHeader("グループ (${v.groups.size})")
            Text("タップで改名、ハンドル(${DRAG_HANDLE_GLYPH})を長押しして並び替え。削除すると所属者は先頭グループへ移動。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // [不具合報告「グループが削除出来ない」対応] 残り1グループの場合、削除ボタンが理由の説明なく
            //   消えるだけだった（担当可否の分類が無くなるため意図的に不可）。理由を明示。
            //   ※旧記述が引き合いに出していた「休シフトの削除不可」は 3.416.0 の方針（休は通常のシフト定義）で撤廃済み。
            if (v.groups.size <= 1) {
                Text("最後の1グループは削除できません（担当可否の分類が無くなるため）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            ReorderableRows(items = v.groups, enabled = !ui.running, onMove = { from, to -> vm.ws1MoveGroupTo(from, to) }) { g, gr, dragHandle ->
                // [押下明示O4] 行タップで編集（シフト行と統一・小さな編集ボタンだけに依存しない）。
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !ui.running) { dialog = Ws1Dialog.EditGroup(g, gr.name, gr.kigou) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DragHandle, contentDescription = "ドラッグで並び替え", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp).padding(12.dp).then(dragHandle))
                    Text("${toHankakuKigou(gr.kigou)}  ${gr.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AddRowButton("グループ追加", onClick = { dialog = Ws1Dialog.AddGroup }, enabled = !ui.running)
            Divider()

            // [3.482.0 編集タブ簡素化] 旧「職員」節（氏名/所属の一覧・追加・一括追加・削除）は撤去し、
            //   職員管理ドア（StaffManageCard）へ一本化した。3.114.0 は「同一 vm API の別ビュー・併存」を選び
            //   3.286.0 も「維持」と判断していたが、実機スクショで「同じ画面が2か所」と指摘され、grilling で
            //   一本化を選択。一括追加（BulkAddDialog）は職員管理側へ移設。年間マスター①はシフト・グループ・
            //   担当可否の「土台」だけを扱う。

            // --- groupShift bucket ---
            Spacer(Modifier.height(8.dp))
            SectionHeader("担当可否（群 × シフト：担当できるか）")   // [3.483.0 E-7] 回数マトリクスと軸が同じため副題で区別
            Text("セルをタップで担当ON/OFF（✓＝担当できる）。群名をタップでその群を一括、シフト名をタップで全グループへ一括。「休」は外せません。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            // [マトリックス再設計（ユーザー提示案）] 旧: 群ごとに FlowRow でチップを折り返す形（2.66.0）。群とシフトの
            //   対応が縦に並ばず一目で比較できなかった。行=群・列=シフトの2次元マトリクスへ。左列（群名）は固定、
            //   右側（シフト名ヘッダ＋セル）だけ横スクロール。セル全面がタップ標的（48dp）。行/列ヘッダのタップで一括。
            GroupShiftMatrix(
                groups = v.groups, shifts = v.shifts, groupShift = v.groupShift,
                enabled = !ui.running,   // [3.409.13] 実行中は applyStructure が必ず拒否＝押せる形は嘘（3.405.0）
                onCell = { g, k, on -> vm.ws1SetGroupShift(g, k, on) },
                onRow = { g, on -> vm.ws1SetGroupShiftRow(g, on) },
                onColumn = { k, on -> vm.ws1SetGroupShiftColumn(k, on) },
            )

            // [③回数へ移動] 適切回数(apt)の編集は「回数（1人あたり）」節の StaffShiftMatrixCard（職員×シフト
            //   マトリクスのセルタップシート）へ統合済み（旧 AptSection は撤去、StaffShiftMatrix.kt 参照）。

        }
    }

    when (val d = dialog) {
        // [3.515.6] 削除の入口は行から編集シートの中へ（ユーザー指示）。参照件数の確認は従来どおり
        //   ConfirmDelete を経由する（安全確認は不変、経由の場所だけ変える）。
        is Ws1Dialog.EditShift -> ShiftDialog("シフト編集", d.name, d.kigou, d.need1, d.need2,
            { n, kg, n1, n2 -> vm.ws1EditShift(d.k, n, kg, n1, n2); dialog = null }, { dialog = null },
            onDelete = if (v.shifts.size > 1) ({
                // [3.429.0/R-03] 削除する前に、参照している制約の件数を見せる（削除自体は
                //   従来どおり進められる＝止めるのではなく、確認ダイアログを情報つきにする）。
                val refs = vm.ws1ShiftRefCount(d.k)
                val note = if (refs > 0) "このシフトを参照する制約が${refs}件あります。削除すると評価対象から外れます。" else ""
                dialog = Ws1Dialog.ConfirmDelete("shift", d.k, "シフト ${toHankakuKigou(d.kigou)}", note)
            }) else null)
        Ws1Dialog.AddShift -> ShiftDialog("シフト追加", "", "", "", "",
            { n, kg, n1, n2 -> vm.ws1AddShift(n, kg, n1, n2); dialog = null }, { dialog = null })
        is Ws1Dialog.EditGroup -> GroupDialog("グループ編集", d.name, d.kigou,
            { n, kg -> vm.ws1EditGroup(d.g, n, kg); dialog = null }, { dialog = null },
            onDelete = if (vm.ws1CanRemoveGroup(d.g)) ({
                // [3.429.0/R-03] 所属者移動に加え、参照している制約の件数も見せる。
                val members = vm.ws1GroupMemberCount(d.g)
                val refs = vm.ws1GroupRefCount(d.g)
                val note = if (refs > 0) "このグループを参照する制約が${refs}件あります。削除すると評価対象から外れます。" else ""
                val label = "グループ ${toHankakuKigou(d.kigou)}" + if (members > 0) "（所属${members}名→先頭グループへ移動）" else ""
                dialog = Ws1Dialog.ConfirmDelete("group", d.g, label, note)
            }) else null)
        Ws1Dialog.AddGroup -> GroupDialog("グループ追加", "", "",
            { n, kg -> vm.ws1AddGroup(n, kg); dialog = null }, { dialog = null })
        Ws1Dialog.BulkAddShift -> BulkAddDialog("シフトを一括追加", "記号を改行で複数入力（例: 休 / Dﾃ / A4）。記号がそのまま名称になります。", null,
            { lines, _ -> lines.forEach { vm.ws1AddShift(it, it, "", "") }; dialog = null }, { dialog = null })
        is Ws1Dialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = {
                DialogDangerButton("削除", onClick = {
                    when (d.kind) {
                        "shift" -> vm.ws1RemoveShift(d.index)
                        "group" -> vm.ws1RemoveGroup(d.index)
                    }
                    dialog = null
                })
            },
            dismissButton = { DialogDismissButton(onClick = { dialog = null }) },
            title = { Text("削除の確認") },
            // [design-review] 「インデックス」は開発者向けの内部語（operator_ux.md §1「専門用語を使わない」）。
            //   ふつうの言葉に置き換え、参照件数(note)は独立の文として挟む。
            text = { Text("${d.label} を削除します。${d.note}残りの設定は自動で調整されます。よろしいですか？") },
        )
        null -> Unit
    }
}

/** [3.515.6] ドラッグハンドルの説明文に埋め込む記号。実際のアイコンは [Icons.Filled.DragHandle]。
 *  [3.530.0] StaffManageCard.kt から再利用するため internal へ（シフト種別/グループと同じ形に統一）。 */
internal const val DRAG_HANDLE_GLYPH = "≡"

/** [3.515.6] 一覧行を長押し+ドラッグで並び替える（片手一本指の既定からの明示的な例外。経緯: history 3.515.6）。
 *  掴んだ行だけを指に追従させ、離した位置を行の高さで割った目標位置で [onMove] を1回だけ呼ぶ。
 *  [3.530.0] StaffManageCard.kt からも使うため internal。 */
@Composable
internal fun <T> ReorderableRows(
    items: List<T>,
    enabled: Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    row: @Composable (index: Int, item: T, dragHandle: Modifier) -> Unit,
) {
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    Column {
        items.forEachIndexed { idx, item ->
            val dragging = draggingIndex == idx
            val dragHandle = if (!enabled) Modifier else Modifier.pointerInput(items.size) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { draggingIndex = idx; dragOffsetPx = 0f },
                    onDragEnd = {
                        val from = draggingIndex
                        if (from in items.indices && rowHeightPx > 0f) {
                            val to = (from + (dragOffsetPx / rowHeightPx).roundToInt()).coerceIn(0, items.size - 1)
                            if (to != from) onMove(from, to)
                        }
                        draggingIndex = -1; dragOffsetPx = 0f
                    },
                    onDragCancel = { draggingIndex = -1; dragOffsetPx = 0f },
                ) { change, dragAmount -> change.consume(); dragOffsetPx += dragAmount.y }
            }
            Box(
                Modifier.fillMaxWidth()
                    .onGloballyPositioned { c -> if (rowHeightPx == 0f) rowHeightPx = c.size.height.toFloat() }
                    .graphicsLayer { translationY = if (dragging) dragOffsetPx else 0f }
                    .zIndex(if (dragging) 1f else 0f),
            ) { row(idx, item, dragHandle) }
        }
    }
}

private sealed interface Ws1Dialog {
    data class EditShift(val k: Int, val name: String, val kigou: String, val need1: String, val need2: String) : Ws1Dialog
    object AddShift : Ws1Dialog
    data class EditGroup(val g: Int, val name: String, val kigou: String) : Ws1Dialog
    object AddGroup : Ws1Dialog
    object BulkAddShift : Ws1Dialog
    data class ConfirmDelete(val kind: String, val index: Int, val label: String, val note: String = "") : Ws1Dialog
}

@Composable
private fun ShiftDialog(
    title: String, name0: String, kigou0: String, need10: String, need20: String,
    onOk: (String, String, String, String) -> Unit, onClose: () -> Unit,
    // [3.515.6] 削除の入口（行から移設）。編集時のみ渡す＝追加時はnullで非表示。
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(name0) }
    var kigou by remember { mutableStateOf(kigou0) }
    var need1 by remember { mutableStateOf(need10) }
    var need2 by remember { mutableStateOf(need20) }
    // [design-review] 下限>上限は他の3面（群/スキル群のレンジ・個人回数、3.403.0）と同じく必ず違反を
    //   生む設定ミスだが、必要人数(need1/need2)のこの面だけ入力時のガードが無かった（対象漏れ）。
    val bad = V6SanityPort.rangeOrderConflict(need1, need2) != null
    W1Shell(title, onClose, { onOk(name, kigou, need1, need2) }, kigou.isNotBlank() && !bad) {
        W1Text("記号 (kigou)", kigou) { kigou = it }
        W1Text("名称", name) { name = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            W1Field("最低人数", need1, Modifier.weight(1f), isError = bad) { need1 = it }
            W1Field("上限人数(2パターン時)", need2, Modifier.weight(1f), isError = bad) { need2 = it }
        }
        if (bad) Text(NEED_ORDER_HINT, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        if (onDelete != null) DeleteRowButton(onClick = onDelete, text = "このシフトを削除")
    }
}

@Composable
private fun GroupDialog(
    title: String, name0: String, kigou0: String,
    onOk: (String, String) -> Unit, onClose: () -> Unit,
    // [3.515.6] 削除の入口（行から移設）。編集時のみ渡す＝追加時はnullで非表示。
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(name0) }
    var kigou by remember { mutableStateOf(kigou0) }
    W1Shell(title, onClose, { onOk(name, kigou) }, kigou.isNotBlank()) {
        W1Text("記号 (kigou)", kigou) { kigou = it }
        W1Text("名称", name) { name = it }
        if (onDelete != null) DeleteRowButton(onClick = onDelete, text = "このグループを削除")
    }
}

@Composable
internal fun StaffDialog(
    title: String, name0: String, group0: Int, groupKigou: List<String>,
    onOk: (String, Int) -> Unit, onClose: () -> Unit,
    // [3.530.0] 削除の入口（行から移設・シフト種別/グループと同じ形）。編集時のみ渡す＝追加時はnullで非表示。
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(name0) }
    var gi by remember { mutableStateOf(group0.coerceIn(0, (groupKigou.size - 1).coerceAtLeast(0))) }
    W1Shell(title, onClose, { onOk(name, gi) }, name.isNotBlank() && groupKigou.isNotEmpty()) {
        W1Text("名称", name) { name = it }
        var open by remember { mutableStateOf(false) }
        Text("グループ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (groupKigou.isEmpty()) {
            // [A7] 鶏卵問題の誘導：グループが無いとスタッフの所属先が決められない（OKは無効）。
            Text("先に「グループ」を追加してください。職員はグループに所属します。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else {
            OutlinedButton(onClick = { open = true }) { Text(groupKigou.getOrNull(gi) ?: "(なし)") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                groupKigou.forEachIndexed { idx, kg ->
                    DropdownMenuItem(text = { Text(kg, fontFamily = FontFamily.Monospace) },
                        onClick = { gi = idx; open = false })
                }
            }
        }
        if (onDelete != null) DeleteRowButton(onClick = onDelete, text = "この職員を削除")
    }
}

/**
 * [⛏12] 改行区切りで複数件をまとめて追加する汎用ダイアログ。1件ずつの追加(各4-5tap×N)を
 * 1回の入力に短縮し、ゼロ構築の操作量を削減する。groups!=null のときは既定グループを選ぶ
 * (スタッフ用)。groups==null はグループ選択なし(シフト用)。追加は呼び出し側で既存の
 * ws1AddStaff/ws1AddShift をループ呼びするだけ＝ロジックは不変。
 */
@Composable
internal fun BulkAddDialog(
    title: String, hint: String, groups: List<String>?,
    onApply: (List<String>, Int) -> Unit, onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var gi by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf(false) }
    val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val groupOk = groups == null || groups.isNotEmpty()
    W1Shell(title, onClose, { onApply(lines, gi) }, lines.isNotEmpty() && groupOk) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = false, minLines = 3,
            label = { Text("1行に1件", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.fillMaxWidth(),
        )
        if (groups != null) {
            if (groups.isEmpty()) {
                Text("先に「グループ」を追加してください。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                Text("既定のグループ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { open = true }) { Text(groups.getOrNull(gi) ?: "(なし)") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    groups.forEachIndexed { idx, kg ->
                        DropdownMenuItem(text = { Text(kg, fontFamily = FontFamily.Monospace) },
                            onClick = { gi = idx; open = false })
                    }
                }
            }
        }
        if (lines.isNotEmpty()) Text("追加: ${lines.size}件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun W1Shell(
    title: String, onClose: () -> Unit, onOk: () -> Unit, okEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { DialogConfirmButton("OK", enabled = okEnabled, onClick = onOk) },
        dismissButton = { DialogDismissButton(onClick = onClose) },
        title = { DialogHeader(title, onClose) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        },
    )
}

@Composable
private fun W1Text(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun W1Field(label: String, value: String, modifier: Modifier = Modifier, isError: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) }, singleLine = true, modifier = modifier, isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

// [ユーザー提示の再設計案] `AptSection`（群×シフトの目標グリッド）は撤去し、目標編集は
//   `StaffShiftMatrixCard`（`StaffShiftMatrix.kt`）のセルタップシートへ統合した
//   （担当可否・目標・上下限・実績を1グリッドで見て編集する。理由・過剰警告(aptBalances)の
//   移設先は `StaffShiftMatrix.kt` のクラスKDoc参照）。

// ===== 担当可否マトリックス（行=群 × 列=シフト） =====
// [ユーザー提示の再設計案] 左列（群名）は横スクロールの外＝固定。右側（シフト名ヘッダ＋セル）だけ横スクロール
//   （両側とも同じ行高で揃える）。セルは全面がタップ標的（48dp、WCAG/Material の下限）。
//   ON＝主色地＋✓（onPrimary）／OFF＝薄い地＋「—」＝色だけに依存しない手がかり。
//   行ヘッダ（群名）タップ＝その群を一括（1つでもOFFがあれば全ON、全ONなら全OFF＝休は残る）。
//   列ヘッダ（シフト名）タップ＝そのシフトを全群へ一括（同じ規則。休の列はOFFにできない＝VMが案内）。
//   トークン: 角丸は shapes.extraSmall（任意値を使わない）、色は colorScheme のロールのみ（生 hex なし）。
@Composable
private fun GroupShiftMatrix(
    groups: List<Group>,
    shifts: List<Shift>,
    groupShift: List<List<Int>>,
    enabled: Boolean,
    onCell: (g: Int, k: Int, on: Boolean) -> Unit,
    onRow: (g: Int, on: Boolean) -> Unit,
    onColumn: (k: Int, on: Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val cellH = 48.dp
    val cellW = 48.dp
    val nameW = 104.dp
    val shape = MaterialTheme.shapes.extraSmall
    val hScroll = rememberScrollState()
    Row(Modifier.fillMaxWidth()) {
        // 固定列: 左上の角＋行ヘッダ（群名＝タップで行一括）
        Column(Modifier.width(nameW)) {
            Box(Modifier.width(nameW).height(cellH).padding(end = 4.dp), contentAlignment = Alignment.CenterStart) {
                Text("群 ＼ シフト", fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
            groups.forEachIndexed { g, gr ->
                val row = groupShift.getOrNull(g).orEmpty()
                val anyOff = shifts.indices.any { k -> row.getOrNull(k) != 1 }
                Box(
                    Modifier.width(nameW).height(cellH).padding(end = 4.dp, top = 1.dp, bottom = 1.dp)
                        .background(cs.secondaryContainer, shape)
                        .clickable(enabled = enabled) { onRow(g, anyOff) }
                        .semantics { contentDescription = "グループ ${gr.name}: タップで全シフトを${if (anyOff) "担当できる" else "担当しない"}に一括" }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("${toHankakuKigou(gr.kigou)} ${gr.name}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = cs.onSecondaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // スクロール側: 列ヘッダ（シフト名＝タップで列一括）＋セル
        Column(Modifier.horizontalScroll(hScroll)) {
            Row {
                shifts.forEachIndexed { k, s ->
                    val anyOff = groups.indices.any { g -> groupShift.getOrNull(g)?.getOrNull(k) != 1 }
                    Box(
                        Modifier.width(cellW).height(cellH).padding(1.dp)
                            .background(cs.secondaryContainer, shape)
                            .clickable(enabled = enabled) { onColumn(k, anyOff) }
                            .semantics { contentDescription = "シフト ${s.kigou}: タップで全グループを${if (anyOff) "担当できる" else "担当しない"}に一括" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(toHankakuKigou(s.kigou), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, color = cs.onSecondaryContainer)
                    }
                }
            }
            groups.forEachIndexed { g, gr ->
                Row {
                    shifts.forEachIndexed { k, s ->
                        val on = groupShift.getOrNull(g)?.getOrNull(k) == 1
                        Box(
                            Modifier.width(cellW).height(cellH).padding(1.dp)
                                .background(if (on) cs.primary else cs.surfaceVariant, shape)
                                .clickable(enabled = enabled) { onCell(g, k, !on) }
                                .semantics { contentDescription = "${gr.name} × ${s.kigou}: ${if (on) "担当できる" else "担当しない"}" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (on) "✓" else "—", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                color = if (on) cs.onPrimary else cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ===== セクション見出し =====
// [3.482.0 用語統一] 旧: 3.45.0 で移植した HUD 流の二段見出し（LOADOUT/ARSENAL/SQUAD/PARTY/MATRIX/PERIOD ＋ 日本語）。
//   FPS/RPG 風の英語コードネームは勤務表アプリの語彙でなく、operator_ux「専門用語を使わない」とも
//   整合しない（ユーザー指示「ゲーム用語と数理用語の混在。勤務表アプリ用語統一する」）。日本語の見出しだけにする。
@Composable
// [3.515.4] 節見出しは title 層（DESIGN.md §3.3: title=見出し／body=本文／label=部品ラベル・チップ・凡例）。
private fun SectionHeader(jp: String) {
    Text(jp, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
}
