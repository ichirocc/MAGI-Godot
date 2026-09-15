package com.magi.app.ui

import com.magi.app.toHankakuKigou

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import com.magi.app.v6.V6SanityPort
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
 * [ユーザー提示の再設計案=③統合の再構成] 旧実装は `AptSection`(群×シフトの目標グリッド)・
 * `StaffRangeSection`(職員別チップ一覧)・`GroupRangeSection`(一括適用)が縦に3段並んでいた
 * （3.286.0でカードは1枚に統合済みだったが、同じ職員×シフトの情報が2段に分かれたままだった）。
 * `AptSection`/`StaffRangeSection`は [StaffShiftMatrixCard]（`StaffShiftMatrix.kt`）の
 * 職員×シフトマトリクスへ統合して撤去した（担当可否・目標・上下限・実績を1グリッドで見て
 * セルタップで編集する）。`GroupRangeSection`（グループ一括適用）はこの再設計の対象外のため維持。
 */
@Composable
fun CountsCard(
    ui: UiState, vm: MagiViewModel,
    /** [実機バグ修正] 呼び出し元がkey(ui.editRev)の外で保持する（詳細はStaffShiftMatrixCardのdoc）。 */
    sheetCell: Pair<Int, Int>?, onSheetCellChange: (Pair<Int, Int>?) -> Unit,
) {
    // [3.483.0 E-8] 旧: 説明文だけのカードが先頭にあった。同じ説明（目標＝やわらかい／上下限＝かたい）は
    //   StaffShiftMatrixCard の見出し直下にもあり二重だったので、こちらを撤去。
    StaffShiftMatrixCard(ui, vm, sheetCell, onSheetCellChange)
    Spacer(Modifier.height(8.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            GroupRangeSection(ui, vm)
        }
    }
}

// ---- グループ単位の回数（一括）: 選んだグループの全職員に同じ上下限を設定する。
//   内部は既存 staffRange への展開（vm.setGroupRange）＝新制約・スコア評価器の変更なし。 ----
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupRangeSection(ui: UiState, vm: MagiViewModel) {
    var dialog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("グループ一括設定", style = MaterialTheme.typography.titleSmall)
            Text(
                "選んだグループ全員に同じ上下限を一度に設定します（個人設定済みは保持）。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // [適用済み一覧] 一括適用したグループ上下限(全メンバー同一レンジ)を表示。各メンバーの個人の回数にも
            //   展開済みだが、ここでグループ単位に集約して確認・削除できるようにする。×=全員分クリア。
            // [3.533.0/ユーザー提示のデザイン案] groupRangeSummary は既に g→k 順ソート済み＝groupBy で
            //   隣接するグループ単位にまとまる。グループ名の重複表示をやめ見出し1つにまとめ、
            //   チップからも「グループ名・」の接頭辞を外して短くする（1行に収まる件数を増やす）。
            val applied = vm.groupRangeSummary()
            if (applied.isNotEmpty()) {
                Text("適用中のグループ上下限（${applied.size}件・個人の回数にも展開済み）",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                applied.groupBy { it.g to it.groupName }.forEach { (gKey, rows) ->
                    val (g, groupName) = gKey
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(groupName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        DeleteRowButton(onClick = { vm.clearGroupRangeSection(g) }, enabled = !ui.running, text = "全解除")
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rows.forEach { gr ->
                            val rangeLab = when {
                                gr.lo.isNotBlank() && gr.hi.isNotBlank() -> "${gr.lo}–${gr.hi}"
                                gr.hi.isNotBlank() -> "≤${gr.hi}"
                                gr.lo.isNotBlank() -> "≥${gr.lo}"
                                else -> ""
                            }
                            InputChip(
                                selected = false,
                                enabled = !ui.running,
                                onClick = { dialog = true },
                                label = { Text("${toHankakuKigou(gr.kigou)} $rangeLab（${if (gr.shared >= gr.members) "${gr.members}" else "${gr.shared}/${gr.members}"}名）") },
                                trailingIcon = {
                                    Icon(Icons.Filled.Close, contentDescription = "削除",
                                        modifier = Modifier.size(32.dp).clickable(enabled = !ui.running) { vm.clearGroupRange(gr.g, gr.k, gr.lo, gr.hi) }.padding(7.dp))
                                },
                            )
                        }
                    }
                }
            }
            AddRowButton("グループに上下限を適用", onClick = { dialog = true }, enabled = ui.loaded && !ui.running)
    }
    if (dialog) {
        GroupRangeDialog(
            groups = vm.groupLabels(),
            shifts = vm.shiftKigouList(),
            allowedFor = { g -> vm.allowedShiftsForGroup(g) },
            memberCount = { g -> vm.groupMemberCount(g) },
            rangeCount = { g, k -> vm.groupRangeMemberCount(g, k) },
            onApply = { g, k, lo, hi ->
                if (lo.isBlank() && hi.isBlank()) vm.clearGroupRangeAll(g, k) else vm.setGroupRange(g, k, lo, hi)
                dialog = false
            },
            onClose = { dialog = false },
        )
    }
}

@Composable
internal fun GroupRangeDialog(
    groups: List<String>,
    shifts: List<String>,
    allowedFor: (Int) -> Set<Int>,
    memberCount: (Int) -> Int,
    rangeCount: (Int, Int) -> Int,
    onApply: (Int, Int, String, String) -> Unit,
    onClose: () -> Unit,
) {
    var g by remember { mutableStateOf(0) }
    var k by remember { mutableStateOf(0) }
    var lo by remember { mutableStateOf("") }
    var hi by remember { mutableStateOf("") }
    var openG by remember { mutableStateOf(false) }
    var openK by remember { mutableStateOf(false) }
    val allowed = allowedFor(g)
    val bad = V6SanityPort.rangeOrderConflict(lo, hi) != null   // [3.403.0] 個人別と同じ（全員へ一括適用するぶん影響は大きい）
    val blank = lo.isBlank() && hi.isBlank()
    val existing = if (g in groups.indices && k in allowed) rangeCount(g, k) else 0
    // [3.506.0] 両方「なし」は「全員ぶん解除」として適用できる（解除対象がある場合のみ）。
    val ok = g in groups.indices && k in allowed && !bad && (!blank || existing > 0)
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            DialogConfirmButton("適用", enabled = ok, onClick = { if (ok) onApply(g, k, lo.trim(), hi.trim()) })
        },
        dismissButton = { DialogDismissButton(onClick = onClose) },
        title = { DialogHeader("グループ単位の回数", onClose) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("グループ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { openG = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(groups.getOrNull(g)?.let { "$it（${memberCount(g)}名）" } ?: "(選択)")
                    }
                    DropdownMenu(expanded = openG, onDismissRequest = { openG = false }) {
                        groups.forEachIndexed { idx, n ->
                            DropdownMenuItem(text = { Text("$n（${memberCount(idx)}名）") }, onClick = { g = idx; k = 0; openG = false })
                        }
                    }
                }
                Text("シフト（全員が担当可のもの）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { openK = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(shifts.getOrNull(k)?.takeIf { k in allowed } ?: "(選択)")
                    }
                    DropdownMenu(expanded = openK, onDismissRequest = { openK = false }) {
                        shifts.forEachIndexed { idx, kg ->
                            if (idx in allowed) DropdownMenuItem(text = { Text(kg) }, onClick = { k = idx; openK = false })
                        }
                    }
                }
                Column(if (bad) Modifier.border(1.dp, MaterialTheme.colorScheme.error, MaterialTheme.shapes.medium) else Modifier) {
                    NumberStepper("下限", lo, { lo = it }, min = 0, blankLabel = "なし")
                    NumberStepper("上限", hi, { hi = it }, min = 0, blankLabel = "なし")
                }
                if (bad) {
                    Text(RANGE_ORDER_HINT, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                } else if (blank && existing > 0) {
                    Text("このまま適用すると、${existing}名の個人上下限を「なし」に戻します（適切回数も空になります）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (blank) {
                    Text(RANGE_REQUIRED_HINT, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("全員の個人上下限に設定し、下限=上限なら適切回数も同時に設定します（個人で設定済みの人は保持）。両方「なし」で適用すると全員ぶん解除します。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
