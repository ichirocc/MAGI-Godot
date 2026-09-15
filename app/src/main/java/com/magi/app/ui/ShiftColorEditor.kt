package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "#rrggbb"/"#rgb" -> Compose Color。同パッケージ(ScheduleGrid)と共有。不正値はグレー。 */
internal fun hexToColor(hex: String): Color {
    val h = hex.trim().removePrefix("#")
    val full = when (h.length) {
        3 -> buildString { h.forEach { append(it); append(it) } }
        6 -> h
        else -> "888888"
    }
    val v = full.toIntOrNull(16) ?: 0x888888
    return Color((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
}

// [実機指摘 3.129系] 12色(5+5+2)では最終行2個が weight で巨大化＋既定色(必須の赤/要調整の橙)が
//   パレットに無く「現在の色が画面の中にない・他の色も選択できない」だった。20色=5×4の完全グリッドにし、
//   既定色(#BA1A1A 必須 / #E08A1E 要調整)と MagiAccent 系(赤/橙/緑/青/紫/桃/灰)を含める。
// [ユーザー指示] 25色=5×5へ拡張。5行目に濃色・アクセントを5つ追加（濃灰・茶・深緑・インディゴ・辛子色）。
// [ユーザー指示 再改訂] 「淡い中間色25色＋濃色太字テキスト」。全25色を淡いパステル調（中明度）へ差し替え。
//   （このパステル版は下記の再改訂で差し替え済み。経緯として残す）
// [ユーザー指示 再々改訂] 2画面のスクショから2件の指摘: ①「休」の色ピッカー＝隣接スウォッチが
//   淡色すぎて見分けにくい＝「各15%以上ぐらい差異を作る」 ②「人員不足」の色ピッカー＝同じ
//   ColorPickerDialog を共有するため、警告色（MagiAccent.orange=#E08A1E、"警告 / 夜勤（アンバー）"）を
//   実際に選べるスウォッチとして含める必要があった（従来は defaultHex のプレビューのみでタップ不可）。
//   farthest-point-selection + 隣接順序の全探索(24通り)で各行を再設計し全て15%以上を達成（このバージョンは
//   下記の再改訂で差し替え済み。経緯として残す）。
// [ユーザー指示 4回目改訂] 「各色は色彩など25%づつ変更する」＝隣接ペアの差異を15%→25%へ強化。
//   全探索(24通り)では各行内でヒュー帯が狭いままだと25%に届かなかった（実測: 旧パレットの隣接距離は
//   最良でも17.6%止まり）ため、各行のヒュー帯を広げ彩度0.22-0.60・明度0.55-0.82の範囲で乱数生成→
//   ヒルクライム局所探索（隣接ペアの最小距離を目的関数に、全体の最小距離が閾値を下回る移動のみ受理）で
//   再設計（このバージョンは下記の再改訂で差し替え済み。経緯として残す）。彩度上限0.60はネオン調を
//   避けるための絞り込み値だった（3.89.0のMagiAccent"Ward"系ミュートトーンに寄せた制約）。
// [ユーザー指示 5回目改訂] 25色を手指定の表（各セルに16進値＋色名を明記）で受領し全面差し替え。
//   検証の結果 ①アンカー(#E08A1E)が含まれていない ②隣接ペア最小距離が9.1%止まり(25%要件未達)
//   ③彩度が0.16〜0.94と上記の「ネオン回避=上限0.60」制約を大きく超える、の3点で既存の設計制約と
//   衝突していた（このバージョンは下記の再改訂で差し替え済み。経緯として残す）。①②は技術的に
//   解決: row0col0（アンカーの定位置）を占めていた「#FA8268 コーラルオレンジ」1色だけを
//   #E08A1E へ差し替え、残り24色は指定値を完全保持したまま全域ヒルクライム局所探索で配置のみ
//   再構成（隣接ペア最小距離35.6%）。③は解決せず＝既存の「ネオン回避・ミュートトーン」方針を
//   このパレットに限り明示的に上書きする（彩度上限0.60の制約はこの改訂で撤回）。
// [ユーザー指示 6回目改訂] 別の25色の手指定表（16進値＋色名）を受領し再び全面差し替え。
//   同じ3種の衝突を検証: ①アンカー(#E08A1E)が含まれていない ②隣接ペア最小距離8.3%止まり
//   (25%要件未達) ③彩度が0.00〜1.00と5回目改訂時よりさらに広い範囲でネオン調上限0.60を
//   超える。5回目改訂と同じ方針で解決: row0col0を占めていた「#E63946 警告赤」1色だけを
//   #E08A1E へ差し替え（他24色は指定値を完全保持）、全域ヒルクライム局所探索で配置のみ
//   再構成し隣接ペア最小距離39.7%（要件25%を大きく上回る）まで改善。彩度上限0.60は
//   5回目改訂で既に撤回済みの方針をそのまま継続適用（ユーザーが個々に選んだビビッドな色を
//   意図的な選択とみなす扱いを据え置く）。pickFg()の明度しきい値140に対し各色78〜229と幅が
//   あるため、選択チェック印の濃淡は従来どおり色ごとに自動で切り替わる（算出済み・17色が
//   濃色文字・8色が淡色文字）。
// [ユーザー指示 7回目改訂] 「6×6の36色にしてください」。既定25色(row0col0=#E08A1Eアンカー)は
//   位置・値とも完全に保持し、末尾へ11色を追加して36色化（perRowも5→6）。追加色は既存25色の
//   色相・明度分布の空白（真紅・鮮緑・濃紺・インディゴ・焦茶・寒色系グレー・ミントティール・
//   深紫・珊瑚赤・セージ・暖色ゴールド）を埋める方向で選定し、既存色との完全一致は無し。
//   perRow変更で既存25色の行/列の見た目上の並びは組み替わるが、格納順自体は不変のため
//   shiftColors[kigou]の明示指定（hex文字列を直接保存）には一切影響しない（このバージョンは
//   下記の再改訂で差し替え済み。経緯として残す）。
// [ユーザー指示 8回目改訂/CUD全面見直し, 3.543.0] 「背景色系／早番／日勤／時短パート／遅番／夜勤」の
//   6家族＋「違反/アクセント」の7家族×6色=42色へ全面再設計。既定色・MagiAccent7色をヒュー帯で
//   各家族へ振り分けた1枚共有パレット（このバージョンは下記の9回目改訂で差し替え済み。経緯として残す）。
// [ユーザー指示 9回目改訂/3.544.0] シフト色と違反色を**別パレットへ分離**。シフト色は
//   「早番／日勤／時短パート／遅番／夜勤」の5段階グラデーション＋「特別枠」(公休/有休/研修/出張/
//   特別休暇/欠勤・突発の事由別6区分)の36色をユーザーが手指定。色相・段階の意図は保ったまま
//   P型/D型二色覚シミュレーション後の最小 ΔE を狭い範囲（ヒュー±14°等）でだけ微調整
//   （`tools/palette_shift_families_cud.py`）。違反色（必須/要調整/族別19種）は既定色＋MagiAccent7色を
//   固定アンカーに全域で ΔE を最大化した30色（`tools/palette_violation_cud.py`）。CVD計算式は両方とも
//   `tools/cud_colors.py`＝design_lint.py の P12 検査と共有。既知の残存ペア（値を変えずには解消できない組、
//   design_lint.py の P12_EXEMPT_PAIRS 参照）は docs/history/3.4xx.md（3.544.0）。特別枠の各スウォッチに
//   区分名を1文字表示する案は撤回（ユーザー指示「1文字ラベル不要」、3.545.0）。
// [ユーザー指示 10回目改訂/3.546.0]「公休は背景色系にする」＝公休を独立の「背景色系」家族(中立グレー6色)へ
//   移し、特別枠は事由別5区分(有休/研修/出張/特別休暇/欠勤・突発)に。背景色系は他5家族と値を変えず
//   固定した上でCUD距離を最大化して新規生成（`tools/palette_bg_family_cud.py`）、公休には家族内最淡色を
//   割当（他家族との対比で「稼働色でない」ことを示す）。既存35色は不変のため他家族の格納位置・
//   保存済みhex文字列への影響は無い。
private val PALETTE_ROW_LABELS = listOf("背景色系", "早番家族", "日勤家族", "時短パート家族", "遅番家族", "夜勤家族", "特別枠")
private val COLOR_PALETTE = listOf(
    "#ced1d5", "#b5aca5", "#a09ea3", "#695f75", "#5c6264", "#3b3f41",
    "#e8e5e5", "#ffeeca", "#f5d964", "#ffca8f", "#ffd759", "#c68b0c",
    "#dceeff", "#d1e1fc", "#a1c4fa", "#7bb4f8", "#1c9bfc", "#084277",
    "#eef7e8", "#d5ead6", "#b7e2bb", "#95c09a", "#369937", "#246823",
    "#ddcafd", "#e1c7e7", "#d9acd9", "#c971c6", "#d421ce", "#801cb7",
    "#d9e0ec", "#9caee2", "#8f8ccb", "#5f5db0", "#3b43b5", "#1a1498",
    "#fab3c5", "#e5f4f7", "#fff2b8", "#d4c3ec", "#262b2f",
)
/** 違反色専用パレット（必須違反・要調整・族別19種）。シフト色とは別物＝家族ヒュー帯の制約が無い分、
 *  全域でCUD距離を最大化できる（`tools/palette_violation_cud.py`）。 */
internal val VIOLATION_COLOR_PALETTE = listOf(
    "#b71c1c", "#f59e0b", "#3b6fd4", "#2e9e62", "#e08a1e", "#8a5cd1",
    "#d24d89", "#d23b34", "#8a979b", "#5740d1", "#75581a", "#7ec6bd",
    "#975eca", "#915a2d", "#5eabe4", "#286760", "#339312", "#602e4f",
    "#b4bd85", "#8fc5cd", "#7386ce", "#106dbd", "#1b5574", "#9ab7e9",
    "#d4ccaf", "#306e20", "#b83997", "#c08ab5", "#2164b0", "#81ca9c",
)

/**
 * colors 移植: シフトの表示色設定。
 * shiftColors[kigou] の上書きを編集。既定は一覧上の位置で決まる色（resolveShiftColor）。
 * 表示専用のため採点・エンジンに影響しない。勤務表グリッドに反映される。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShiftColorCard(
    ui: UiState,
    vm: MagiViewModel,
) {
    var target by remember { mutableStateOf<String?>(null) }
    val shifts = vm.shiftColorList()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("シフトの表示色", style = MaterialTheme.typography.titleMedium)
            Text(
                "勤務表に表示される各シフトの色（既定はシフト種別ごとの色）。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (shifts.isEmpty()) {
                Text(
                    "（データ未読込）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // [校正] 縦長の一覧をやめ、スウォッチ＋記号のコンパクトなチップを折り返しグリッドに。
                //   カスタム色は枠色（primary）で「指定」を表現（テキスト列を削減＝冗長解消）。
                // [冗長性解消 3.461.0] チップの1dp枠を切替える専用トグル(旧「シフト種別の枠を表示」)は撤去。
                //   すぐ上の外観カードにある「勤務表の通常セルに枠線を表示」と隣接して並び、対象が違う
                //   （こちらは設定画面内のチップの見た目だけ・実際の勤務表には無関係）のに文言が酷似し
                //   「同じことを2回設定させられる」冗長に見えた（実機報告）。この枠は情報量を持つ既定表示
                //   （custom=trueの太枠との対比で「未指定」を示す）なので、常時表示へ戻し設定自体を無くす。
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shifts.forEach { sc ->
                        ColorChip(hex = sc.hex, label = sc.kigou, custom = sc.custom, enabled = !ui.running) { target = sc.kigou }
                    }
                }
            }
            // [IA重複解消 3.132系] 旧「違反の色（必須違反）」節（__vio__ のみの入口）は撤去。違反の色は
            //   直下の ColorSettingsView（違反種別の色＝基準色2種＋族別）に一本化（詳細設定から移動）。
        }
    }
    target?.let { kg ->
        val current = shifts.firstOrNull { it.kigou == kg }
        ColorPickerDialog(
            kigou = kg,
            currentHex = current?.hex ?: "",
            // [実機バグ修正] 選ぶ・既定に戻すで閉じない（「閉じる」/×だけで閉じる。経緯: history 3.515.2）。
            onPick = { hex -> vm.setShiftColor(kg, hex) },
            onReset = { vm.resetShiftColor(kg) },
            onClose = { target = null },
        )
    }
}

/**
 * 色を変える操作の共通の形。**枠＋色見本＋ラベル**で「押すと色が変わる」を形そのものが示す
 * （説明文で補わない）。同じ操作は同じ形にする＝シフトの表示色と違反種別の色が同一の見た目になり、
 * 片方だけ形が変わって取り残される事故も起きない。
 * custom=true（利用者が明示指定した色）は枠を太く primary にして「指定済み」を示す。
 */
@Composable
internal fun ColorChip(
    hex: String,
    label: String,
    custom: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = if (custom) 2.dp else 1.dp,
                color = if (custom) cs.primary else cs.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Swatch(hex, 24.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Swatch(hex: String, sizeDp: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(sizeDp)
            .background(hexToColor(hex), MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), MaterialTheme.shapes.small),
    )
}

@Composable
internal fun ColorPickerDialog(
    kigou: String,
    currentHex: String,
    onPick: (String) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    defaultHex: String = "",
    // [3.544.0/シフト色と違反色を別パレットへ分離] 既定はシフト色パレット。違反色ピッカーは
    // V6RemainingScreens.kt から VIOLATION_COLOR_PALETTE を明示指定する。
    palette: List<String> = COLOR_PALETTE,
    rowLabels: List<String>? = PALETTE_ROW_LABELS,
) {
    // [実機指摘「現在の設定している色が画面の中にない」] 未設定(空)のときグレーの偽色を出していた →
    //   実効色(既定色)を表示し、パレット上の一致スウォッチにも✓を付ける。
    val effectiveHex = currentHex.ifBlank { defaultHex }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { DialogConfirmButton("閉じる", onClick = onClose) },
        dismissButton = { DialogDismissButton(onClick = onReset, text = "既定に戻す") },
        title = { DialogHeader("「$kigou」の色", onClose) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(effectiveHex, 28.dp)
                    Text(if (currentHex.isBlank()) "  現在の色（既定）" else "  現在の色",
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text("色を選ぶ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val perRow = 6
                palette.chunked(perRow).forEachIndexed { rowIndex, rowColors ->
                    // [色覚配慮/3.543.0] 家族名を文字でも示す＝色だけに頼らない見出し（CUD全面見直し）。
                    rowLabels?.getOrNull(rowIndex)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // [不具合修正×2] 固定40dp×6は幅超過で6個目が切れ、weight等分は端数行(2個)が巨大化していた。
                    //   幅いっぱいを等分(weight)＋正方形(aspectRatio)＋端数行は空 Spacer で埋めて全行同サイズに。
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowColors.forEach { hex ->
                            val selected = hex.equals(effectiveHex, ignoreCase = true)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .background(hexToColor(hex), MaterialTheme.shapes.extraSmall)
                                    .border(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        MaterialTheme.shapes.extraSmall,
                                    )
                                    .clickable { onPick(hex) }
                                    // [a11y] 色のみの選択肢に読み上げ名を付与。
                                    .semantics { contentDescription = "色 $hex" + (if (selected) "・選択中" else "") },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Text(
                                        "✓",
                                        color = hexToColor(pickFg(hex)),
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                        repeat(perRow - rowColors.size) { Spacer(Modifier.weight(1f).aspectRatio(1f)) }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        },
    )
}

/** 簡易: 明度から文字色を選ぶ（チェック印用）。 */
private fun pickFg(bgHex: String): String {
    val h = bgHex.trim().removePrefix("#")
    val v = (if (h.length == 6) h else "888888").toIntOrNull(16) ?: 0x888888
    val r = (v shr 16) and 0xFF; val g = (v shr 8) and 0xFF; val b = v and 0xFF
    val lum = (0.299 * r + 0.587 * g + 0.114 * b)
    return if (lum > 140) "#14110d" else "#fbf4e8"
}

