package com.magi.app.ui

import com.magi.app.model.Range
import com.magi.app.v6.ShiftAppearance
import com.magi.app.v6.Ws1Ops
import com.magi.app.v6.cachedProblem
import kotlinx.coroutines.flow.update

/**
 * [MagiViewModel] の月次条件・表示設定（ws2 日別必要人数の例外 / ws5 個人別回数レンジと
 * グループ一括・回数センター / 集計セルのしきい値ビュー / ws3 希望シフト / シフト・違反の表示色）。
 * 本体ファイルから extension 関数として抽出（責務別の物理分割＝AIコードレビュー時のコンテキスト
 * 圧迫対策）。ロジックは一切変更していない。
 *
 * すべて「state を読む → 編集は [MagiViewModel.applyStructure]（本体残置の編集ゲート）へ渡す」
 * 薄いコマンド/ビュー層のみで、状態への直接書き込みを持たない。ビュー用 data class
 * （[NeedDayView]/[GroupRangeView]/[CountRuleView]/[WishView]/[ShiftColorView]）は外部の明示参照が
 * 無い（エディタ側は型推論で消費）ためトップレベルへ同時移動した。
 *
 * 触るメンバ: state（読み取りのみ）・logOp/opNm/opSy/opDays（操作ログ）・_ui（実行中ガードの
 * メッセージ表示）・optimizeInFlight/busyWhat（同ガード）・notify・allowedShiftsFor・applyStructure
 * ＝いずれも public または internal（モジュール内限定）。
 */
// ---- ws2: 日別の必要人数（例外） needDay1/needDay2 の疎な上書きを編集 ----
data class NeedDayView(val k: Int, val j: Int, val kigou: String, val p1: String, val p2: String)

fun MagiViewModel.needDayOverrides(): List<NeedDayView> {
    val st = state ?: return emptyList()
    val keys = (st.needDay1.keys + st.needDay2.keys).toSet()
    return keys.mapNotNull { key ->
        val parts = key.split(",")
        if (parts.size != 2) return@mapNotNull null
        val k = parts[0].toIntOrNull() ?: return@mapNotNull null
        val j = parts[1].toIntOrNull() ?: return@mapNotNull null
        NeedDayView(k, j, st.shifts.getOrNull(k)?.kigou ?: k.toString(), st.needDay1[key] ?: "", st.needDay2[key] ?: "")
    }.sortedWith(compareBy({ it.j }, { it.k }))
}

fun MagiViewModel.setNeedDay(k: Int, j: Int, p1: String, p2: String) {
    val st = state ?: return
    val key = "$k,$j"
    val nd1 = st.needDay1.toMutableMap()
    val nd2 = st.needDay2.toMutableMap()
    if (p1.isBlank()) nd1.remove(key) else nd1[key] = p1.trim()
    if (p2.isBlank()) nd2.remove(key) else nd2[key] = p2.trim()
    logOp("I", "需要設定: ${opSy(k)} ${j + 1}日 → P1=${p1.ifBlank { "-" }} P2=${p2.ifBlank { "-" }}")
    applyStructure(st.copy(needDay1 = nd1, needDay2 = nd2))
}

fun MagiViewModel.removeNeedDay(k: Int, j: Int) {
    val st = state ?: return
    val key = "$k,$j"
    logOp("I", "需要削除: ${opSy(k)} ${j + 1}日"); applyStructure(st.copy(needDay1 = st.needDay1 - key, needDay2 = st.needDay2 - key))
}

// ---- ws5: 個人別の回数（LimMin/LimMax） staffRange["i,k"]=Range(lo,hi) を編集 ----

fun MagiViewModel.setStaffRange(i: Int, k: Int, lo: String, hi: String) {
    val st = state ?: return
    val key = "$i,$k"
    val m = st.staffRange.toMutableMap()
    if (lo.isBlank() && hi.isBlank()) m.remove(key) else m[key] = Range(lo.trim(), hi.trim())
    logOp("I", "個人レンジ: ${opNm(i)} ${opSy(k)} → ${if (lo.isBlank() && hi.isBlank()) "削除" else "${lo.ifBlank { "?" }}〜${hi.ifBlank { "?" }}"}")
    applyStructure(st.copy(staffRange = m))
}

/**
 * [3.326.0] 回数固定(lo==hi)の幅を1段だけ広げる。**利用者のタップでのみ動く**（HF77: 数値の変更は
 * 業務判断）。幅の決め打ちを避けるため下限側・上限側を別々に選ばせ、押した内容は操作ログへ残す。
 * `applyStructure` 経由なので「元に戻す」で戻せる。
 *
 * @param loDelta 下限へ足す量（負で緩める）。@param hiDelta 上限へ足す量（正で緩める）。
 */
fun MagiViewModel.relaxStaffRangePin(i: Int, k: Int, loDelta: Int, hiDelta: Int) {
    if (optimizeInFlight()) { _ui.update { it.copy(messageIsError = true, message = "${busyWhat()}の実行中は回数を変更できません。終わってから試してください。") }; return }
    val st = state ?: return
    val cur = st.staffRange["$i,$k"] ?: return
    val lo = cur.lo.trim().toIntOrNull() ?: return
    val hi = cur.hi.trim().toIntOrNull() ?: return
    val newLo = (lo + loDelta).coerceAtLeast(0)
    val newHi = (hi + hiDelta).coerceAtLeast(newLo)
    if (newLo == lo && newHi == hi) return
    logOp("I", "回数固定を緩和: ${opNm(i)} ${opSy(k)} $lo〜$hi → $newLo〜$newHi（もう一度つくると効果が分かります）")
    setStaffRange(i, k, newLo.toString(), newHi.toString())
}

fun MagiViewModel.removeStaffRange(i: Int, k: Int) {
    val st = state ?: return
    logOp("I", "個人レンジ削除: ${opNm(i)} ${opSy(k)}"); applyStructure(st.copy(staffRange = st.staffRange - "$i,$k"))
}

// ---- グループ単位の回数（一括）: 既存 staffRange をグループ所属職員に展開する。
//   新しい制約種別やスコア評価器の変更は不要（low/high は既に重み90/25で最適化対象）＝退行リスクなし。
//   業務担当者が値を入力しボタンで適用する operator ツール（HF77準拠）。 ----
fun MagiViewModel.groupLabels(): List<String> = state?.groups?.map {
    if (it.kigou.isNotBlank() && it.kigou != it.name) "${it.name}·${it.kigou}" else it.name
} ?: emptyList()

fun MagiViewModel.groupMemberCount(g: Int): Int = state?.staff?.count { it.groupIdx == g } ?: 0

/** グループの全メンバーが担当できるシフトの積集合（下限を全員が満たせる範囲に限定し構造的floorを防ぐ）。 */
fun MagiViewModel.allowedShiftsForGroup(g: Int): Set<Int> {
    val st = state ?: return emptySet()
    val members = st.staff.indices.filter { st.staff[it].groupIdx == g }
    if (members.isEmpty()) return emptySet()
    return members.map { allowedShiftsFor(it).toHashSet() }.reduce { a, b -> a.apply { retainAll(b) } }
}

/** グループ g 所属の全職員に、ws5 個人別[lo,hi](staffRange, low/high 重み90/25=強い境界) を一括設定し、
 *  さらに ws1 C のグループ別 適切回数(groupShiftApt, apt 重み1=弱い目標) も同時に書く。
 *  apt は「最低=最高」の単一値のときのみ設定（範囲指定や空欄時はクリア）＝Excelの ws1 C→ws5 展開を1操作で再現。 */
fun MagiViewModel.setGroupRange(g: Int, k: Int, lo: String, hi: String) {
    val st0 = state ?: return
    val members = st0.staff.indices.filter { st0.staff[it].groupIdx == g }
    if (members.isEmpty()) return
    val loT = lo.trim(); val hiT = hi.trim()
    if (loT.isBlank() && hiT.isBlank()) return
    // [共有ws5・スキップ方式] ws5(個人レンジ)へ直接書く。ただし既に個人値が在るメンバーは上書きせず保持する。
    //   ※同一保存先のため、適用後の値は個人値と区別がつかない→グループ範囲の後からの一括変更は不可
    //     （変更したい場合は対象のws5を一旦クリアして再適用）。Web/VBAの「ws5のみ」と厳密一致。
    val m = st0.staffRange.toMutableMap()
    var wrote = 0; var skipped = 0
    for (i in members) {
        val key = "$i,$k"
        val ex = m[key]
        if (ex != null && (ex.lo.isNotBlank() || ex.hi.isNotBlank())) { skipped++; continue }
        m[key] = Range(loT, hiT); wrote++
    }
    // ws1 C: グループ別 適切回数（弱い目標）。単一値(最低=最高)のときのみ設定。
    val aptVal = if (loT == hiT) loT else ""
    val stNew = Ws1Ops.setGroupApt(st0.copy(staffRange = m), g, k, aptVal)
    val gname = st0.groups.getOrNull(g)?.name ?: "#$g"
    logOp("I", "グループ一括: $gname ${opSy(k)} → ws5=${loT.ifBlank { "?" }}〜${hiT.ifBlank { "?" }} (書込${wrote}名/スキップ${skipped}名・既存個人値は保持)")
    applyStructure(stNew)
}

/** [共有ws5・スキップ方式] グループ既定の解除: 表示中レンジ(lo,hi)と一致するメンバーのws5だけ削除する。
 *  個人で別値にした職員(レンジが違う)は保持する。サマリの×から呼ぶ。 */
fun MagiViewModel.clearGroupRange(g: Int, k: Int, lo: String, hi: String) {
    val st0 = state ?: return
    val members = st0.staff.indices.filter { st0.staff[it].groupIdx == g }
    if (members.isEmpty()) return
    val loT = lo.trim(); val hiT = hi.trim()
    val m = st0.staffRange.toMutableMap()
    var cleared = 0
    for (i in members) {
        val key = "$i,$k"; val r = m[key] ?: continue
        if (r.lo.trim() == loT && r.hi.trim() == hiT) { m.remove(key); cleared++ }
    }
    if (cleared == 0) return
    val stNew = Ws1Ops.setGroupApt(st0.copy(staffRange = m), g, k, "")
    val gname = st0.groups.getOrNull(g)?.name ?: "#$g"
    // [3.409.11] チップ内の小さな✕1回で**N名ぶん**の個人設定が消えるのに、画面には
    //   チップが1つ消えるだけで、何人ぶん消えたかが出ていなかった（logOp は詳細設定のログ止まり）。
    //   3.399.0/3.400.0 の「イベントは Snackbar へ」に合わせ、実際の効果を件数つきで返す。
    notify("$gname「${opSy(k)}」のグループ上下限を解除しました（${cleared}名ぶん・「元に戻す」で戻せます）")
    applyStructure(stNew)
}

/** [3.506.0] 「グループ単位の回数」で両方「なし」を適用＝グループ全員の (i,k) 個人上下限を値に関係なく解除し、群の適切回数も空にする
 *  （サマリの✕は同一レンジのメンバーしか消せない。経緯は history 3.506.0）。 */
fun MagiViewModel.clearGroupRangeAll(g: Int, k: Int) {
    val st0 = state ?: return
    val members = st0.staff.indices.filter { st0.staff[it].groupIdx == g }
    if (members.isEmpty()) return
    val m = st0.staffRange.toMutableMap()
    var cleared = 0
    for (i in members) if (m.remove("$i,$k") != null) cleared++
    val gname = st0.groups.getOrNull(g)?.name ?: "#$g"
    if (cleared == 0) { notify("$gname「${opSy(k)}」に解除する個人上下限はありません"); return }
    val stNew = Ws1Ops.setGroupApt(st0.copy(staffRange = m), g, k, "")
    notify("$gname「${opSy(k)}」の個人上下限を全員ぶん「なし」にしました（${cleared}名ぶん・「元に戻す」で戻せます）")
    applyStructure(stNew)
}

/** [3.533.0/ユーザー提示のデザイン案] グループ一括設定の「このグループぶんを全解除」:
 *  groupRangeSummary に出ている、そのグループの全シフトぶんを1操作でまとめて解除する
 *  （×を1つずつ押すのと同じ判定＝表示中レンジと一致するメンバーだけ・1回のUndoで戻せる）。 */
fun MagiViewModel.clearGroupRangeSection(g: Int) {
    val st0 = state ?: return
    val members = st0.staff.indices.filter { st0.staff[it].groupIdx == g }
    if (members.isEmpty()) return
    val targets = groupRangeSummary().filter { it.g == g }
    if (targets.isEmpty()) return
    val m = st0.staffRange.toMutableMap()
    var cleared = 0
    var stNew = st0
    for (t in targets) {
        val loT = t.lo.trim(); val hiT = t.hi.trim()
        for (i in members) {
            val key = "$i,${t.k}"; val r = m[key] ?: continue
            if (r.lo.trim() == loT && r.hi.trim() == hiT) { m.remove(key); cleared++ }
        }
        stNew = Ws1Ops.setGroupApt(stNew.copy(staffRange = m), g, t.k, "")
    }
    if (cleared == 0) return
    val gname = st0.groups.getOrNull(g)?.name ?: "#$g"
    notify("$gname のグループ上下限をまとめて解除しました（${targets.size}件・${cleared}名ぶん・「元に戻す」で戻せます）")
    applyStructure(stNew)
}

/** グループ g のメンバーのうち (i,k) に個人上下限（非空）を持つ人数。ダイアログの「なし」適用可否と件数表示に使う。 */
fun MagiViewModel.groupRangeMemberCount(g: Int, k: Int): Int {
    val st = state ?: return 0
    return st.staff.indices.count { st.staff[it].groupIdx == g && st.staffRange["$it,$k"]?.let { r -> r.lo.isNotBlank() || r.hi.isNotBlank() } == true }
}

data class GroupRangeView(val g: Int, val k: Int, val groupName: String, val kigou: String, val lo: String, val hi: String, val members: Int, val shared: Int = members)

/** 「グループ単位の回数」適用済み一覧。グループ全メンバーが同一の非空レンジを持つ (g,k) のみ＝
 *  一括適用された(個別に変更されていない)グループ上下限を再構成して表示する。×で全員分をクリア。 */
fun MagiViewModel.groupRangeSummary(): List<GroupRangeView> {
    val st = state ?: return emptyList()
    val out = mutableListOf<GroupRangeView>()
    st.groups.forEachIndexed { g, gr ->
        val members = st.staff.indices.filter { st.staff[it].groupIdx == g }
        if (members.isEmpty()) return@forEachIndexed
        st.shifts.forEachIndexed { k, sh ->
            // [緩和] メンバーの非空レンジを (lo,hi) で集計し、最多共有レンジを代表として出す。完全一致で
            //   なくても「2名以上が共有」なら表示し N/M名 を添える。これにより一括適用後に一部メンバーを
            //   個別編集しても、グループ単位のレンジが消えずに残って見える（旧実装は全員一致のみ表示）。
            val counts = HashMap<Pair<String, String>, Int>()
            for (i in members) {
                val r = st.staffRange["$i,$k"] ?: continue
                if (r.lo.isBlank() && r.hi.isBlank()) continue
                val key = r.lo to r.hi
                counts[key] = (counts[key] ?: 0) + 1
            }
            val best = counts.maxByOrNull { it.value }
            if (best != null && (best.value >= 2 || members.size == 1)) {
                out.add(GroupRangeView(g, k, gr.name, sh.kigou, best.key.first, best.key.second, members.size, best.value))
            }
        }
    }
    return out.sortedWith(compareBy({ it.g }, { it.k }))
}

/** [直せる導線] 集計セル(職員別)の違反詳細用しきい値: 下限/上限(staffRange)・目標(apt実効)。未設定は null。 */
fun MagiViewModel.staffCellLimits(i: Int, k: Int): Triple<Int?, Int?, Int?> {
    val st = state ?: return Triple(null, null, null)
    val p = cachedProblem(st)
    if (i !in 0 until p.S || k !in 0 until p.K) return Triple(null, null, null)
    // [3.489.0/実機報告「個人の下限をゼロに出来ない」] 旧: `it == 0` も未設定扱いにしていたため、下限を 0 に
    //   設定して適用しても再表示が「なし」に戻り、表も「〜0」（=0 の固定表示にならない）で、利用者には
    //   「設定できない」に見えた。エンジンは lo=0 を保持しており（Problem.rangeLo=0、lo==hi の固定も成立）、
    //   落としていたのは表示だけ。明示した 0 は 0 として返す（未設定＝MIN_VALUE のみ null）。
    val lo = p.rangeLo[i][k].let { if (it == Int.MIN_VALUE) null else it }
    val hi = p.rangeHi[i][k].let { if (it == Int.MAX_VALUE) null else it }
    val apt = p.apt[i][k].let { if (it < 0) null else it }
    return Triple(lo, hi, apt)
}

/** [直せる導線] 集計セル(日別)の必要数レンジ lo..hi（need1/need2 の OR）。どちらも未定義なら null。
 *
 *  [3.391.0/need1直参照の第5世代] 旧実装は `lo = need1; if (lo < 0) return null` で、
 *  **need2 だけで需要が定義されたセルを「対象外」として null を返していた**（need1 未設定は -1）。
 *  エンジンは `Problem.covUCell`（source of truth）の OR 意味論でそこに covU(HARD) を課すのに、
 *  UI 側だけが「要件なし」と表示していた＝赤いセルをタップしても何も出ない／必要人数カレンダーが
 *  「未設定」と出る／実働チェックの月間需要が 0 になる。3.173.0・3.309.0・3.369.0・3.379.0 と同根。
 *
 *  しきい値は `covUCell`/`covOCell` の選択と厳密に一致させる:
 *  両方定義なら lo=min・hi=max（小さい方で不足が立ち、大きい方を超えて初めて過剰が立つ）、
 *  片方だけなら双方その値。**通常データ（need1 <= need2）では旧実装と同じ値**になる。 */
fun MagiViewModel.needCellLimits(k: Int, j: Int): Pair<Int, Int>? {
    val st = state ?: return null
    val p = cachedProblem(st)
    if (k !in 0 until p.K || j !in 0 until p.T) return null
    val n1 = p.need1[k][j]
    val n2 = if (p.use2) p.need2[k][j] else -1
    if (n1 < 0 && n2 < 0) return null
    val lo = if (n1 >= 0 && n2 >= 0) minOf(n1, n2) else maxOf(n1, n2)
    val hi = maxOf(n1, n2)
    return lo to hi
}

/** [回数センター] 個人別の回数(上下限)と適切回数(apt)を職員×シフトで統合した一覧。
 *  staffRange または apt(実効=担当可＆クランプ後)が効くセルのみ返す。aptEff=実効目標(-1=なし),
 *  aptRaw=群目標の生値(-1=なし。aptEff と異なればクランプされている)。hasRange=個人別の上下限あり。 */
data class CountRuleView(
    val i: Int, val k: Int, val staffName: String, val kigou: String,
    val lo: String, val hi: String, val aptEff: Int, val aptRaw: Int, val hasRange: Boolean,
)

fun MagiViewModel.staffCountRules(): List<CountRuleView> {
    val st = state ?: return emptyList()
    // [レビュー#1] 再描画毎に呼ばれるため cachedProblem で Problem 再構築(高コスト)を避ける。
    val p = cachedProblem(st)
    val rows = mutableListOf<CountRuleView>()
    for (i in 0 until p.S) {
        val g = st.staff.getOrNull(i)?.groupIdx ?: continue
        for (k in 0 until p.K) {
            val r = st.staffRange["$i,$k"]
            val hasRange = r != null && (r.lo.isNotBlank() || r.hi.isNotBlank())
            val aptEff = p.apt[i][k]
            if (!hasRange && aptEff < 0) continue
            val aptRaw = st.groupShiftApt.getOrNull(g)?.getOrNull(k)?.trim()?.toIntOrNull() ?: -1
            rows.add(
                CountRuleView(
                    i, k, st.staff[i].name, st.shifts.getOrNull(k)?.kigou ?: k.toString(),
                    r?.lo ?: "", r?.hi ?: "", aptEff, if (aptEff >= 0) aptRaw else -1, hasRange,
                )
            )
        }
    }
    return rows.sortedWith(compareBy({ it.i }, { it.k }))
}

// [3.286.0 冗長性B] 旧「回数設定画面」(CountSettingsCard, 2.60〜2.63世代)の集約ビュー
//   （shiftRuleBlocks/staffRuleBlocks/setCons41＋GroupRule等のデータ型）は、画面本体の撤去後も
//   呼出0のまま残存していた孤児クラスタのため削除（grep で外部参照0を確認済み）。

// ---- ws3 移植: 希望シフト wishes["i,j"]=シフトindex（採点=pref/hard1。割当やcons3系とは別。UIのみ・モデル/エンジン不変）----
data class WishView(val i: Int, val j: Int, val staffName: String, val day: Int, val kigou: String, val k: Int)

fun MagiViewModel.wishOverrides(): List<WishView> {
    val st = state ?: return emptyList()
    return st.wishes.mapNotNull { (key, k) ->
        val parts = key.split(",")
        if (parts.size != 2) return@mapNotNull null
        val i = parts[0].toIntOrNull() ?: return@mapNotNull null
        val j = parts[1].toIntOrNull() ?: return@mapNotNull null
        WishView(i, j, st.staff.getOrNull(i)?.name ?: i.toString(), j + 1, st.shifts.getOrNull(k)?.kigou ?: k.toString(), k)
    }.sortedWith(compareBy({ it.i }, { it.j }))
}

fun MagiViewModel.setWish(i: Int, j: Int, k: Int) {
    val st = state ?: return
    val m = st.wishes.toMutableMap()
    m["$i,$j"] = k
    logOp("I", "希望設定: ${opNm(i)} ${j + 1}日 → ${opSy(k)}")
    applyStructure(st.copy(wishes = m))
}

fun MagiViewModel.removeWish(i: Int, j: Int) {
    val st = state ?: return
    logOp("I", "希望削除: ${opNm(i)} ${j + 1}日")
    applyStructure(st.copy(wishes = st.wishes - "$i,$j"))
}

/** [一括] スタッフ(null=全員)×日群に希望 k を一括設定。Undo1回・再チェック1回。 */
fun MagiViewModel.setWishesForDays(staffIdx: Int?, days: List<Int>, k: Int) {
    val st = state ?: return
    if (days.isEmpty() || k !in st.shifts.indices) return
    val m = st.wishes.toMutableMap()
    val staffRange = if (staffIdx != null) listOf(staffIdx) else st.staff.indices.toList()
    for (i in staffRange) for (j in days) if (i in st.staff.indices && j in 0 until st.dayCount) m["$i,$j"] = k
    logOp("I", "希望一括: ${if (staffIdx != null) opNm(staffIdx) else "全員"} ${opDays(days)} → ${opSy(k)}")
    applyStructure(st.copy(wishes = m))
}

/** [一括] スタッフ(null=全員)×日群の希望を一括削除。 */
fun MagiViewModel.clearWishesForDays(staffIdx: Int?, days: List<Int>) {
    val st = state ?: return
    if (days.isEmpty()) return
    val m = st.wishes.toMutableMap()
    val staffRange = if (staffIdx != null) listOf(staffIdx) else st.staff.indices.toList()
    for (i in staffRange) for (j in days) m.remove("$i,$j")
    if (m.size == st.wishes.size) return
    logOp("I", "希望クリア: ${if (staffIdx != null) opNm(staffIdx) else "全員"} ${opDays(days)}")
    applyStructure(st.copy(wishes = m))
}

/** [一括] すべての希望を削除。 */
fun MagiViewModel.clearAllWishes() {
    val st = state ?: return
    if (st.wishes.isEmpty()) return
    logOp("I", "希望全クリア")
    applyStructure(st.copy(wishes = emptyMap()))
}

// ---- colors: シフトの表示色 shiftColors[kigou]="#rrggbb"（表示専用）----
data class ShiftColorView(val kigou: String, val name: String, val hex: String, val custom: Boolean)

fun MagiViewModel.shiftColorList(): List<ShiftColorView> {
    val st = state ?: return emptyList()
    return st.shifts.mapIndexed { i, sh ->
        val ov = st.shiftColors[sh.kigou]
        ShiftColorView(sh.kigou, sh.name, ShiftAppearance.resolveShiftColor(ov, i), !ov.isNullOrBlank())
    }
}

fun MagiViewModel.setShiftColor(kigou: String, hex: String) {
    val st = state ?: return
    if (kigou.isBlank()) return
    val m = st.shiftColors.toMutableMap()
    m[kigou] = hex.trim()
    applyStructure(st.copy(shiftColors = m))
}

fun MagiViewModel.resetShiftColor(kigou: String) {
    val st = state ?: return
    applyStructure(st.copy(shiftColors = st.shiftColors - kigou))
}
/** [違反色] 違反セルの枠/マーカー色。予約キー "__vio__" に保存（状態スキーマ非変更）。 */
fun MagiViewModel.setViolationColor(hex: String) {
    val st = state ?: return; if (hex.isBlank()) return
    applyStructure(st.copy(shiftColors = st.shiftColors + ("__vio__" to hex.trim())))
}
fun MagiViewModel.resetViolationColor() {
    val st = state ?: return
    applyStructure(st.copy(shiftColors = st.shiftColors - "__vio__"))
}
/** [違反色] 要調整(ソフト違反)の枠/マーカー色。予約キー "__vioSoft__"（空=既定の橙）。 */
fun MagiViewModel.setViolationSoftColor(hex: String) {
    val st = state ?: return; if (hex.isBlank()) return
    applyStructure(st.copy(shiftColors = st.shiftColors + ("__vioSoft__" to hex.trim())))
}
fun MagiViewModel.resetViolationSoftColor() {
    val st = state ?: return
    applyStructure(st.copy(shiftColors = st.shiftColors - "__vioSoft__"))
}

/** [違反色/族別] 違反種別（族）ごとの個別色。予約キー "__vioFam_<fam>__"（例: __vioFam_c3n__）。
 *  未設定の族は重大度色（__vio__/__vioSoft__）へフォールバック。 */
fun MagiViewModel.setViolationFamilyColor(fam: String, hex: String) {
    val st = state ?: return; if (hex.isBlank() || fam.isBlank()) return
    applyStructure(st.copy(shiftColors = st.shiftColors + ("__vioFam_${fam}__" to hex.trim())))
}
fun MagiViewModel.resetViolationFamilyColor(fam: String) {
    val st = state ?: return
    applyStructure(st.copy(shiftColors = st.shiftColors - "__vioFam_${fam}__"))
}
