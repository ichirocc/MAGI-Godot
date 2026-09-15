package com.magi.app.ui

import com.magi.app.toHankakuKigou
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * [入口4分割/職員管理] 入退職・改名・所属グループ・資格スキルを職員単位で随時変更する1枚。
 * 年間マスター(Ws1Card)のスタッフ節と同じ ViewModel API（ws1EditStaff/ws1AddStaff/ws1RemoveStaff/
 * setStaffSkill）を使う職員中心の別ビュー（併存＝安全）。個人の回数上下限は年間マスター③「回数（1人あたり）」
 * の CountsCard（StaffShiftMatrixCard, StaffShiftMatrix.kt）が担当。
 */
@Composable
fun StaffManageCard(ui: UiState, vm: MagiViewModel) {
    val v = vm.ws1() ?: return
    val skills = vm.skillGroups()
    val cs = MaterialTheme.colorScheme
    var edit by remember { mutableStateOf<Triple<Int, String, Int>?>(null) }   // (i, name, groupIdx)
    var addOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    // [3.482.0 編集タブ簡素化] 一括追加は年間マスター①の職員節（撤去）から移設。職員の一覧・入退職はこのドアだけ。
    var bulkOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("職員一覧（${v.staff.size}名）", style = MaterialTheme.typography.titleMedium)
            Text("タップで編集、ハンドル(${DRAG_HANDLE_GLYPH})を長押しして並び替え。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            // [3.530.0] シフト種別/グループ（3.515.6）と同じ形へ統一（経緯: docs/history/3.4xx.md）。
            ReorderableRows(items = v.staff, enabled = !ui.running, onMove = { from, to -> vm.ws1MoveStaffTo(from, to) }) { i, st, dragHandle ->
                val gk = v.groups.getOrNull(st.groupIdx)?.kigou?.let { toHankakuKigou(it) } ?: "?"
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = !ui.running) { edit = Triple(i, st.name, st.groupIdx) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.DragHandle, contentDescription = "ドラッグで並び替え", tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(48.dp).padding(12.dp).then(dragHandle))
                    // [3.483.0 E-4] 旧: 名前列＋スキル▼＋編集＋削除が1行に並び、名前が押し潰されていた。
                    //   スキル▼はグループと同じ「所属」の情報なので2行目に移し、右端は「›」だけにする。
                    Column(Modifier.weight(1f)) {
                        Text(st.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("グループ $gk", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            if (skills.isNotEmpty()) {
                                Spacer(Modifier.width(10.dp))
                                Text("スキル", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                var open by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { open = true }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text(skills.getOrNull(st.skillIdx)?.kigou ?: "(なし)")
                                    }
                                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                        DropdownMenuItem(text = { Text("(なし)") }, onClick = { vm.setStaffSkill(i, -1); open = false })
                                        skills.forEachIndexed { gi, sg ->
                                            DropdownMenuItem(text = { Text("${sg.kigou}  ${sg.name}") }, onClick = { vm.setStaffSkill(i, gi); open = false })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                }
            }
            // [3.409.11] 残り1名のとき削除ボタンが理由なく消える（年間マスター側と同じ対象漏れ）。
            if (v.staff.size <= 1) {
                Text("最後の1名は削除できません（勤務表の行が無くなるため）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            AddRowButton("入職（職員追加）", onClick = { addOpen = true }, enabled = ui.loaded && !ui.running)
            AddRowButton("一括追加（改行区切り）", onClick = { bulkOpen = true }, enabled = ui.loaded && !ui.running)
        }
    }
    if (bulkOpen) {
        BulkAddDialog("職員を一括追加", "名前を改行で複数入力。全員を既定グループに追加します（後で個別変更可）。",
            v.groups.map { toHankakuKigou(it.kigou) },
            { lines, gi -> lines.forEach { vm.ws1AddStaff(it, gi) }; bulkOpen = false }, { bulkOpen = false })
    }
    edit?.let { (i, nm, gi0) ->
        // [3.530.0] 削除の入口はシフト種別/グループと同じく編集ダイアログの中（3.515.6と同じ形）。
        StaffDialog("職員の編集（改名・所属）", nm, gi0, v.groups.map { toHankakuKigou(it.kigou) },
            { n, gi -> vm.ws1EditStaff(i, n, gi); edit = null }, { edit = null },
            onDelete = if (v.staff.size > 1) ({ edit = null; confirmDelete = i }) else null)
    }
    if (addOpen) {
        StaffDialog("入職（職員追加）", "", 0, v.groups.map { toHankakuKigou(it.kigou) },
            { n, gi -> vm.ws1AddStaff(n, gi); addOpen = false }, { addOpen = false })
    }
    confirmDelete?.let { i ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = { DialogDangerButton("削除（退職）", onClick = { vm.ws1RemoveStaff(i); confirmDelete = null }) },
            dismissButton = { DialogDismissButton(onClick = { confirmDelete = null }) },
            title = { DialogHeader("退職・削除の確認", { confirmDelete = null }) },
            text = { Text("${v.staff.getOrNull(i)?.name ?: ""} を削除します。この職員の勤務・希望も消えます。") },
        )
    }
}
