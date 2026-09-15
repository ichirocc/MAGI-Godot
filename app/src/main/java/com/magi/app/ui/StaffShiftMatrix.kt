package com.magi.app.ui

import com.magi.app.toHankakuKigou

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.magi.app.v6.V6SanityPort
import kotlinx.coroutines.launch

/**
 * [ユーザー提示の再設計案] 個人別の回数まわり（担当可否・目標(apt)・上下限(staffRange)・実績）を
 * 1つの行=職員×列=シフトのマトリクスへ統合する。旧: [AptSection]（群×シフトの目標グリッド）と
 * [StaffRangeSection]（職員別の上下限チップ一覧）が縦に並び、同じ職員×シフトの情報を2つの節を
 * 往復しないと把握できなかった（グリッドの群単位表示は「誰が」を、チップ一覧は「幾つ」を別々に語る）。
 *
 * この画面は [CountRuleView]/[MagiViewModel.staffCellLimits]/`ui.countViolations`（既存・source of
 * truth はすべてチェッカー側）だけを読む**表示専用の再配置**で、判定ロジックは一切増やさない。
 * 色は既存の方向カラー言語（不足=赤系/超過=橙系、[MagiScheduleViews.kt] の TallyCard・
 * [StaffRangeEditor.kt] の旧チップ色と同じ2色）をそのまま踏襲し、目標(apt)のズレは同じ色を薄く、
 * 個人上下限(staffRange)の逸脱は同じ色を濃く塗ることで「重さ」を表す（grilling で確定：新しい第3の
 * 色軸は作らず、この画面専用の色語彙を増やさない）。
 *
 * 担当可否の**編集**はこの画面では行わない（群単位のまま、年間マスター「① シフト・グループ・職員」の
 * 群×シフトマトリクス(3.476.0)が唯一の編集口）。ここでは群の canDo を職員行へ展開して「—」表示するのみ。
 *
 * 個人単位の目標(apt)上書きという新機能は作らない（grilling で確定）。セルタップシートでは
 * ①群の目標そのものを編集（既存 [MagiViewModel.ws1SetGroupApt]、同じ群の全員に影響する旨を明示）
 * ②個人の上下限（既存 [MagiViewModel.setStaffRange]／[MagiViewModel.removeStaffRange]）の2系統だけを
 * 提供する（= 個人ごとに違う目標が欲しければ上下限を lo=hi で固定する、という既存の代用手段に一本化）。
 */
@Composable
internal fun StaffShiftMatrixCard(
    ui: UiState, vm: MagiViewModel,
    /** [実機バグ修正] セルタップシートの開閉状態。呼び出し元(MagiApp.kt)が`key(ui.editRev)`の**外**で
     *  保持する。群の目標の+/-自体が`ws1SetGroupApt`経由でeditRevを増やし、key配下のrememberだと
     *  シートが自分の操作のたびに閉じていた（経緯: history 3.515.2）。 */
    sheetCell: Pair<Int, Int>?, onSheetCellChange: (Pair<Int, Int>?) -> Unit,
) {
    val v = vm.ws1() ?: return
    val cs = MaterialTheme.colorScheme
    val K = v.shifts.size
    val S = v.staff.size
    if (K == 0 || S == 0) return
    // [P10] シフト記号の字面比較でなく `restShiftIndex`(MirrorCore.kt) の唯一の持ち場へ委譲。
    val restIdx = remember(v.shifts) { vm.state?.let { com.magi.app.v6.restShiftIndex(it) } ?: 0 }
    // 方向カラー（TallyCard/StaffRangeSectionと同じM6統一トークン。ここだけの新色は作らない）。
    val shortC = ui.violationColorHex.takeIf { it.isNotBlank() }?.let { hexToColor(it) } ?: MagiAccent.red
    val overC = ui.violationSoftColorHex.takeIf { it.isNotBlank() }?.let { hexToColor(it) } ?: MagiAccent.orange

    val counts = remember(ui.schedule, K) {
        Array(S) { i -> IntArray(K).also { c -> ui.schedule.getOrNull(i)?.forEach { kk -> if (kk in 0 until K) c[kk]++ } } }
    }

    var confirmResetApt by remember { mutableStateOf(false) }
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val cellW = 68.dp
    val labelW = 128.dp
    val rowH = 52.dp

    // 目標超過の検算（既存 aptBalances=検査6-C と単一ソース）。最も足りない列を1行で示し、その列へジャンプする。
    val overloaded = remember(ui.editRev, ui.structureEdited) { vm.aptBalances().filter { it.overloaded } }
    val worst = overloaded.maxByOrNull { it.shortfall }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("回数マトリクス（職員 × シフト：月に何回か）", style = MaterialTheme.typography.titleMedium)   // [3.483.0 E-7]
            Text(
                "セルをタップで目標・上下限を編集。「—」＝担当不可（担当可否は①で変更）。" +
                    "薄色＝目標(やわらかい)のズレ、濃色＝個人の上下限(かたい)の逸脱。",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
            )
            if (worst != null) {
                Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().clickable {
                        val col = v.shifts.indexOfFirst { it.kigou == worst.kigou }
                        if (col >= 0) scope.launch { hScroll.animateScrollTo((labelW.value.toInt() + col * cellW.value.toInt()).coerceAtLeast(0)) }
                    }) {
                    Text(
                        // [3.483.0 E-9] 旧「…に対し、…は…回（…）」の3段構文を「目標 ＞ 上限」の1式へ。
                        "⚠ ${toHankakuKigou(worst.kigou)}：目標の合計${worst.aptSum}回 ＞ " +
                            (if (worst.isRest) "休める日数の上限${worst.capacity}日" else "必要人数の合計${worst.capacity}回") +
                            "（${worst.shortfall}回ぶんは必ず届きません）。タップでその列へ",
                        style = MaterialTheme.typography.labelMedium, color = cs.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeleteRowButton(onClick = { confirmResetApt = true },
                    enabled = v.groupShiftApt.any { row -> row.any { it.isNotBlank() } }, text = "目標を全リセット")
            }

            Row(Modifier.fillMaxWidth()) {
                Column {
                    MatrixHeaderCell(labelW, rowH, cs.surfaceVariant) {
                        Text("職員 (群)", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                    for (i in 0 until S) {
                        val gr = v.groups.getOrNull(v.staff[i].groupIdx)
                        // [3.531.0/ユーザー提示のデザイン案] 行に1件でも違反があれば名前の頭に警告を出す。
                        //   新しい色軸は増やさない（このファイルの既定＝既存 error 色をそのまま使う）。
                        val flagged = (0 until K).any { k -> ui.countViolations.containsKey("$i,$k") }
                        MatrixHeaderCell(labelW, rowH, cs.surfaceVariant, alignStart = true) {
                            Text((if (flagged) "⚠ " else "") + "${v.staff[i].name}（${gr?.kigou?.let { toHankakuKigou(it) } ?: "?"}）",
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (flagged) cs.error else Color.Unspecified)
                        }
                    }
                    // [3.483.0 E-9] フッター行の見出し。旧: 右側の「実績/目標」数字列だけで、左列に何の行か無かった。
                    MatrixHeaderCell(labelW, rowH, cs.surfaceVariant, alignStart = true) {
                        Text("計 実績/目標", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
                Column(Modifier.horizontalScroll(hScroll)) {
                    Row {
                        for (k in 0 until K) {
                            MatrixHeaderCell(cellW, rowH, cs.surfaceVariant) {
                                Text(toHankakuKigou(v.shifts[k].kigou), style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                    for (i in 0 until S) {
                        Row {
                            val allowed = remember(v, i) { vm.allowedShiftsFor(i).toHashSet() }
                            for (k in 0 until K) {
                                val cell = matrixCell(
                                    allowed = k in allowed, count = counts[i][k],
                                    limits = vm.staffCellLimits(i, k), vio = ui.countViolations["$i,$k"],
                                    isRest = k == restIdx, shortC = shortC, overC = overC, cs = cs,
                                )
                                MatrixDataCell(cellW, rowH, cell) { if (k in allowed) onSheetCellChange(i to k) }
                            }
                        }
                    }
                    // フッター: シフトごとの 割当計/目標合計。grilling で確定した定義（apt目標合計 vs 割当実績合計）。
                    Row {
                        for (k in 0 until K) {
                            var targetSum = 0; var actualSum = 0; var hasTarget = false
                            for (i in 0 until S) {
                                actualSum += counts[i][k]
                                val apt = vm.staffCellLimits(i, k).third
                                if (apt != null) { targetSum += apt; hasTarget = true }
                            }
                            val over = hasTarget && actualSum > targetSum
                            val under = hasTarget && actualSum < targetSum
                            // [色覚配慮/3.543.0] 本文セル(▲/▼)と同じ語彙をフッターにも付与＝色だけで
                            //   方向を判断させない（旧: 色の濃淡だけで over/under を区別していた）。
                            val glyph = when { over -> "▲"; under -> "▼"; else -> "" }
                            MatrixHeaderCell(cellW, rowH,
                                when { over -> overC.copy(alpha = 0.30f); under -> shortC.copy(alpha = 0.30f); else -> cs.surfaceVariant }) {
                                Text(if (hasTarget) "$glyph$actualSum/$targetSum" else "$actualSum",
                                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }

    sheetCell?.let { (i, k) ->
        StaffShiftCellSheet(ui, vm, v, i, k, onDismiss = { onSheetCellChange(null) })
    }
    if (confirmResetApt) {
        AlertDialog(
            onDismissRequest = { confirmResetApt = false },
            title = { Text("目標を全リセット") },
            text = {
                Text(
                    "全グループ×全シフトの「目標」を空欄（目標なし）に戻します。\n" +
                        "・目標由来のやわらかい違反は消えます\n" +
                        "・担当ON/OFF・回数の下限上限・勤務表は変わりません\n" +
                        "・「元に戻す」で復帰できます\n実行しますか？",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { DialogDangerButton("全リセット", onClick = { vm.ws1ResetGroupApt(); confirmResetApt = false }) },
            dismissButton = { DialogDismissButton(onClick = { confirmResetApt = false }) },
        )
    }
}

/** 1セルの表示内容（表示専用。判定はすべて呼出側=既存 checker/staffCellLimits 由来）。 */
private data class MatrixCell(
    val text: String, val bg: Color, val fg: Color, val bold: Boolean, val bordered: Boolean, val cd: String?,
    /** [3.482.0] 2行目（上下限/目標）。1行目は現在値だけにして 68dp のセルで切れないようにする。 */
    val sub: String? = null,
)

/**
 * [3.482.0 文字欠け修正] 旧: `▲11[10-10]`（10文字）を 68dp・14sp・maxLines=1・overflow 未指定（Clip）で
 * 描いていたため、実機で `▲11[10-1` のように**無警告で切れて**判読不能だった。さらに `boundStr(null, 0)` が
 * `--0`（ハイフンが区切りと欠測を兼ねる）を返し、`▲1[--0]` という暗号になっていた。
 * 1行目＝現在値（▲/▼付き・最大3〜4文字）、2行目＝上下限（`10〜10`／`〜0`／`4〜`／`=5`）または目標（`目標5`）。
 * 判定・色・読み上げ文（cd）は不変＝表示の分割だけ。
 */
private fun matrixCell(
    allowed: Boolean, count: Int, limits: Triple<Int?, Int?, Int?>, vio: String?, isRest: Boolean,
    shortC: Color, overC: Color, cs: ColorScheme,
): MatrixCell {
    val (lo, hi, apt) = limits
    if (!allowed) return MatrixCell("—", cs.surfaceVariant.copy(alpha = 0.35f), cs.onSurfaceVariant, false, false, null)
    fun range(lo2: Int?, hi2: Int?): String = when {
        lo2 != null && hi2 != null && lo2 == hi2 -> "=$lo2"
        lo2 != null && hi2 != null -> "$lo2〜$hi2"
        lo2 != null -> "$lo2〜"
        hi2 != null -> "〜$hi2"
        else -> ""
    }
    return when (vio) {
        "vio-low" -> MatrixCell("▼$count", shortC.copy(alpha = 0.45f), cs.onSurface, true, false,
            "下限${lo ?: 0}回に対し現在${count}回・不足", sub = range(lo, hi))
        "vio-high" -> MatrixCell("▲$count", overC.copy(alpha = 0.50f), cs.onSurface, true, false,
            "上限${hi ?: 0}回に対し現在${count}回・超過", sub = range(lo, hi))
        "vio-aptLow" -> {
            val bg = if (isRest) MagiAccent.orange.copy(alpha = 0.28f) else shortC.copy(alpha = 0.22f)
            MatrixCell("▼$count", bg, cs.onSurface, false, false, "目標${apt ?: 0}回に対し現在${count}回・未達", sub = "目標${apt ?: count}")
        }
        "vio-aptHigh" -> {
            val bg = if (isRest) MagiAccent.orange.copy(alpha = 0.28f) else overC.copy(alpha = 0.22f)
            MatrixCell("▲$count", bg, cs.onSurface, false, false, "目標${apt ?: 0}回に対し現在${count}回・超過", sub = "目標${apt ?: count}")
        }
        null -> when {
            lo != null || hi != null -> MatrixCell("$count", Color.Transparent, cs.onSurface, false, true, null, sub = range(lo, hi))
            apt != null -> MatrixCell("$count", Color.Transparent, cs.onSurface, false, false, null, sub = "目標$apt")
            else -> MatrixCell("$count", Color.Transparent, cs.onSurfaceVariant, false, false, null)
        }
        // c2 等の想定外クラス（稀）: 方向不明の軽い注意のみ。詳細はシートで確認。
        else -> MatrixCell("△$count", overC.copy(alpha = 0.18f), cs.onSurface, false, false, breakdownLabels[vio.removePrefix("vio-")] ?: vio)
    }
}

@Composable
private fun MatrixHeaderCell(
    w: Dp, h: Dp, bg: Color, alignStart: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(Modifier.width(w).height(h).padding(1.dp)) {
        Box(
            Modifier.fillMaxSize().background(bg, MaterialTheme.shapes.extraSmall)
                .then(if (alignStart) Modifier.padding(horizontal = 6.dp) else Modifier),
            contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun MatrixDataCell(
    w: Dp, h: Dp, cell: MatrixCell, onClick: () -> Unit,
) {
    Box(Modifier.width(w).height(h).padding(1.dp)) {
        Box(
            Modifier.fillMaxSize()
                .background(cell.bg, MaterialTheme.shapes.extraSmall)
                .then(if (cell.bordered) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall) else Modifier)
                .clickable(onClick = onClick)
                .then(if (cell.cd != null) Modifier.semantics { contentDescription = cell.cd } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            // [3.482.0] 2行構成＋省略記号。旧は1行・overflow 未指定（Clip）で長い文字列が無警告で切れていた。
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cell.text, style = MaterialTheme.typography.bodySmall, fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                    color = cell.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (cell.sub != null && cell.sub.isNotBlank()) {
                    Text(cell.sub, style = MaterialTheme.typography.labelSmall, color = cell.fg.copy(alpha = 0.8f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** セルタップの編集シート。①群の目標(apt、全員に影響) ②個人の上下限(staffRange) の2系統のみ提供する。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StaffShiftCellSheet(ui: UiState, vm: MagiViewModel, v: MagiViewModel.Ws1View, i: Int, k: Int, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState()
    val name = v.staff.getOrNull(i)?.name ?: "$i"
    val g = v.staff.getOrNull(i)?.groupIdx ?: -1
    val groupName = v.groups.getOrNull(g)?.name ?: "?"
    val kigou = v.shifts.getOrNull(k)?.kigou ?: "$k"
    val count = ui.schedule.getOrNull(i)?.count { it == k } ?: 0
    val (lo0, hi0, apt) = vm.staffCellLimits(i, k)
    val vio = ui.countViolations["$i,$k"]
    var lo by remember(i, k) { mutableStateOf(lo0?.toString() ?: "") }
    var hi by remember(i, k) { mutableStateOf(hi0?.toString() ?: "") }
    val hasRange = lo0 != null || hi0 != null
    val bad = V6SanityPort.rangeOrderConflict(lo, hi) != null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogHeader("$name ・ ${toHankakuKigou(kigou)}", onDismiss)
            Surface(color = cs.surfaceVariant, shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("現在 ${count}回", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    when {
                        vio == "vio-low" && lo0 != null -> Text("下限${lo0}回に対し現在${count}回（${(lo0 - count).coerceAtLeast(0)}回不足）", color = cs.error, style = MaterialTheme.typography.bodyMedium)
                        vio == "vio-high" && hi0 != null -> Text("上限${hi0}回に対し現在${count}回（${(count - hi0).coerceAtLeast(0)}回超過）", color = cs.error, style = MaterialTheme.typography.bodyMedium)
                        vio == "vio-aptLow" && apt != null -> Text("目標${apt}回に対し現在${count}回（${(apt - count).coerceAtLeast(0)}回未達）", style = MaterialTheme.typography.bodyMedium)
                        vio == "vio-aptHigh" && apt != null -> Text("目標${apt}回に対し現在${count}回（${(count - apt).coerceAtLeast(0)}回超過）", style = MaterialTheme.typography.bodyMedium)
                        apt != null -> Text("目標${apt}回どおりです", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val raw = v.groupShiftApt.getOrNull(g)?.getOrNull(k) ?: ""
            Text("群の目標（$groupName の個人設定がない職員に適用）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            AptStepperRow(label = toHankakuKigou(kigou), value = raw, onChange = { vm.ws1SetGroupApt(g, k, it) })
            if (hasRange && raw.trim().toIntOrNull() != null) {
                Text("この職員・シフトは個人の下限・上限を優先するため、群の目標は適用されません", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            } else if (apt != null && raw.trim().toIntOrNull() != apt) {
                Text("この職員の希望・置けるシフトから ${raw.ifBlank { "0" }}→${apt} に調整されています", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text("個人の下限・上限（このシフトだけ）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            NumberStepper("下限", lo, { lo = it }, min = 0, blankLabel = "なし")
            NumberStepper("上限", hi, { hi = it }, min = 0, blankLabel = "なし")
            if (bad) Text(RANGE_ORDER_HINT, style = MaterialTheme.typography.labelMedium, color = cs.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasRange) {
                    DeleteRowButton(onClick = { vm.removeStaffRange(i, k); onDismiss() }, text = "上下限を解除")
                }
                if (vio == "vio-high") {
                    DialogConfirmButton("上限を${count}に引き上げて解決", enabled = true,
                        onClick = { vm.setStaffRange(i, k, lo0?.toString() ?: "", count.toString()); onDismiss() })
                } else if (vio == "vio-low") {
                    DialogConfirmButton("下限を${count}に下げて解決", enabled = true,
                        onClick = { vm.setStaffRange(i, k, count.toString(), hi0?.toString() ?: ""); onDismiss() })
                }
            }
            DialogConfirmButton("この上下限を適用", enabled = !bad && (lo.isNotBlank() || hi.isNotBlank() || hasRange),
                onClick = { vm.setStaffRange(i, k, lo.trim(), hi.trim()); onDismiss() })
        }
    }
}

/** [Ws1Editor.AptStepper と同型] 群の目標編集専用（このファイル内で完結させ、AptSection撤去後も再利用できるよう複製ではなく同じ形を保つ）。 */
@Composable
private fun AptStepperRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
        TextButton(onClick = {
            val c = value.trim().toIntOrNull()
            onChange(when { c == null -> "0"; c <= 0 -> ""; else -> (c - 1).toString() })
        }, modifier = Modifier.semantics { contentDescription = "$label の目標を減らす" }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Text(value.ifBlank { "なし" }, style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = {
            val c = value.trim().toIntOrNull() ?: -1
            onChange((c + 1).coerceAtLeast(0).toString())
        }, modifier = Modifier.semantics { contentDescription = "$label の目標を増やす" }) { Text("＋", style = MaterialTheme.typography.titleLarge) }
    }
}
