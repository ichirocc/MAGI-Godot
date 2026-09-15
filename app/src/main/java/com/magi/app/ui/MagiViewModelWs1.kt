package com.magi.app.ui

import com.magi.app.model.Group
import com.magi.app.toHankakuKigou
import com.magi.app.v6.Ws1Ops
import com.magi.app.v6.restShiftIndex

/**
 * [MagiViewModel] の ws1（初期設定＝シフト/グループ/職員/スキル区分/期間の構造編集）コマンド群。
 * 本体ファイルから extension 関数として抽出（責務別の物理分割＝AIコードレビュー時のコンテキスト
 * 圧迫対策）。ロジックは一切変更していない（唯一の読み替え: shiftMonth の `_ui.value.startDate` →
 * 公開 `ui.value.startDate`＝asStateFlow の同一値・挙動不変）。
 *
 * 実際の状態遷移は本体に残る編集ゲート [MagiViewModel.applyStructure]（MagiState 版 / Ws1Result 版）・
 * [MagiViewModel.applyStructureWithMessage] が一手に担う（structuralEditBlocked/undo/editRev/
 * 再チェック/自動保存）。本ファイルは「入力を整えて Ws1Ops を呼び、結果をゲートへ渡す」薄い
 * コマンド層のみ。移行規則そのもの（削除時の参照置換等）は Ws1Ops（v6層＝ホストでテスト可能）。
 *
 * 触るメンバ: state/currentSchedule（読み取りのみ）・logOp/opNm/opSy（操作ログ）・上記ゲート
 * ＝いずれも internal（モジュール内限定）。violationRange（窓ハイライト表示）は ws1 ドメイン外の
 * ため本体に残置。呼出側（Ws1Editor/MagiSetupCards 等）は同一パッケージ＝無修正で解決される。
 */
fun MagiViewModel.ws1EditShift(k: Int, name: String, kigou: String, need1: String, need2: String) {
    val st = state ?: return
    if (symbolTaken(st.shifts.map { it.kigou }, kigou, "シフト", exceptIndex = k)) return
    // [3.416.0] 3.415.0 の R-04 ガード（休シフトの改名禁止）はユーザー方針「休は通常のシフト定義」により
    //   撤回。改名は他シフトと同じ経路＝renameShiftInConstraints が制約参照を追従させ、「休」記号が
    //   無くなった場合の帰結（既定シフト解決が先頭へ倒れる）は検査2g が案内する。
    logOp("I", "シフト編集: ${opSy(k)} → ${name.trim()}(${kigou.trim()}) 最低${need1.trim().ifBlank { "-" }}/上限${need2.trim().ifBlank { "-" }}")
    applyStructure(Ws1Ops.editShift(st, k, name.trim(), kigou.trim(), need1.trim(), need2.trim()))
}

/** [必要人数カレンダー] シフト既定のneed1/need2だけをその場で編集する（name/kigouは不変）。
 *  ws1EditShiftの狭い版＝NeedCalendarCardの「基本の必要人数」インライン編集用。 */
fun MagiViewModel.setShiftNeed(k: Int, need1: String, need2: String) {
    val st = state ?: return
    val sh = st.shifts.getOrNull(k) ?: return
    logOp("I", "必要人数編集: ${opSy(k)} → 最低${need1.trim().ifBlank { "-" }}/上限${need2.trim().ifBlank { "-" }}")
    applyStructure(Ws1Ops.editShift(st, k, sh.name, sh.kigou, need1.trim(), need2.trim()))
}

fun MagiViewModel.ws1EditGroup(g: Int, name: String, kigou: String) {
    val st = state ?: return
    if (symbolTaken(st.groups.map { it.kigou }, kigou, "グループ", exceptIndex = g)) return
    logOp("I", "グループ編集: [$g] → ${name.trim()}(${kigou.trim()})")
    applyStructure(Ws1Ops.editGroup(st, g, name.trim(), kigou.trim()))
}

fun MagiViewModel.ws1EditStaff(i: Int, name: String, groupIdx: Int) {
    val st = state ?: return
    logOp("I", "職員編集: ${opNm(i)} → ${name.trim()} / グループ[$groupIdx]")
    applyStructure(Ws1Ops.editStaff(st, i, name.trim(), groupIdx))
}

fun MagiViewModel.ws1SetGroupShift(g: Int, k: Int, allowed: Boolean) {
    val st = state ?: return
    val ns = Ws1Ops.setGroupShift(st, g, k, allowed)
    if (ns === st) {
        // [3.484.0] 単一セルでも休は外せない（列一括と同じ理由・同じ案内）。
        if (!allowed && k == restShiftIndex(st)) {
            notify("「休」はどのグループからも外せません（担当できるシフトが無い群を作らないため）", "W")
        }
        return
    }
    logOp("I", "担当可否: グループ[$g] × ${opSy(k)} → ${if (allowed) "担当できる" else "担当しない"}")
    applyStructure(ns)
}

/** [マトリックス一括] 群 g の全シフトを一括ON/OFF（行ヘッダ＝群名のタップ）。OFF でも休は残る。 */
fun MagiViewModel.ws1SetGroupShiftRow(g: Int, allowed: Boolean) {
    val st = state ?: return
    val name = st.groups.getOrNull(g)?.name ?: "[$g]"
    logOp("I", "担当可否(一括): グループ $name の全シフト → ${if (allowed) "担当できる" else "担当しない（休は残す）"}")
    applyStructure(Ws1Ops.setGroupShiftRow(st, g, allowed))
}

/** [マトリックス一括] シフト k を全群へ一括ON/OFF（列ヘッダ＝シフト名のタップ）。休の列は OFF にできない。 */
fun MagiViewModel.ws1SetGroupShiftColumn(k: Int, allowed: Boolean) {
    val st = state ?: return
    val ns = Ws1Ops.setGroupShiftColumn(st, k, allowed)
    if (ns === st) {
        if (!allowed && k == restShiftIndex(st)) {
            notify("「休」はどのグループからも外せません（担当できるシフトが無い群を作らないため）", "W")
        }
        return
    }
    logOp("I", "担当可否(一括): ${opSy(k)} を全グループ → ${if (allowed) "担当できる" else "担当しない"}")
    applyStructure(ns)
}

/** グループ別シフトの適切回数（1人あたり期間内目標。空欄＝目標なし）を設定。 */
fun MagiViewModel.ws1SetGroupApt(g: Int, k: Int, value: String) {
    val st = state ?: return
    logOp("I", "適切回数: グループ[$g] × ${opSy(k)} → ${value.trim().ifBlank { "未設定" }}")
    applyStructure(Ws1Ops.setGroupApt(st, g, k, value))
}

/**
 * [apt強制リセット] 適切回数(apt)を全グループ×全シフトで空欄(目標なし)に戻す。
 * apt由来のソフト違反が消える。担当ON/OFF・回数レンジ・勤務表は不変、表も保持。元に戻すで復帰可。
 */
fun MagiViewModel.ws1ResetGroupApt() {
    val st = state ?: return
    val cleared = st.groupShiftApt.sumOf { row -> row.count { it.trim().isNotEmpty() } }
    logOp("I", "apt強制リセット: 適切回数を全空欄に（$cleared 件クリア）")
    applyStructureWithMessage(Ws1Ops.resetGroupApt(st), "適切回数(apt)を全リセットしました（$cleared 件 → 0）")
}

fun MagiViewModel.ws1SetUse2(on: Boolean) {
    val st = state ?: return
    logOp("I", "設定変更: 上限人数(2パターン目) → ${if (on) "使う" else "使わない"}")
    applyStructure(Ws1Ops.setUse2(st, on))
}

/**
 * [3.410.0/W-01・W-02] 記号の重複を**入力時に**断る。既存の記号へ改名すると制約行が一括置換されて
 * 別の行と合流し、改名し直しても戻らない（検査8 が事後に警告するが、そのときには手遅れ）。
 */
private fun MagiViewModel.symbolTaken(existing: List<String>, kigou: String, what: String, exceptIndex: Int = -1): Boolean {
    if (!com.magi.app.v6.Ws1Ops.symbolCollides(existing, kigou, exceptIndex)) return false
    val k = kigou.trim()
    notify("記号「$k」はすでに別の${what}で使われています（制約の参照が混ざるため、別の記号にしてください）", "W")
    return true
}

fun MagiViewModel.ws1AddShift(name: String, kigou: String, need1: String, need2: String) {
    val st = state ?: return
    if (kigou.isBlank()) return
    if (symbolTaken(st.shifts.map { it.kigou }, kigou, "シフト")) return
    logOp("I", "シフト追加: ${name.trim()}(${kigou.trim()}) 最低${need1.trim().ifBlank { "-" }}/上限${need2.trim().ifBlank { "-" }}")
    applyStructure(Ws1Ops.addShift(st, name.trim(), kigou.trim(), need1.trim(), need2.trim()))
}

fun MagiViewModel.ws1AddGroup(name: String, kigou: String) {
    val st = state ?: return
    if (kigou.isBlank()) return
    if (symbolTaken(st.groups.map { it.kigou }, kigou, "グループ")) return
    logOp("I", "グループ追加: ${name.trim()}(${kigou.trim()})")
    applyStructure(Ws1Ops.addGroup(st, name.trim(), kigou.trim()))
}

fun MagiViewModel.ws1AddStaff(name: String, groupIdx: Int) {
    val st = state ?: return
    val sched = currentSchedule ?: return
    logOp("I", "職員追加: ${name.trim()} / グループ[$groupIdx]")
    applyStructure(Ws1Ops.addStaff(st, sched, name.trim(), groupIdx))
}

fun MagiViewModel.ws1ResizeDays(newT: Int) {
    val st = state ?: return
    val sched = currentSchedule ?: return
    logOp("I", "期間変更: ${st.dayCount}日 → ${newT}日")
    applyStructure(Ws1Ops.resizeDays(st, sched, newT))
}

/** [対象月の選択] 開始日を指定年月の1日にし、その月の日数へ整える（endDate/希望/必要人数も追従）。 */
fun MagiViewModel.setMonth(year: Int, month1to12: Int) {
    val st = state ?: return
    val sched = currentSchedule ?: return
    val first = runCatching { java.time.LocalDate.of(year, month1to12, 1) }.getOrNull() ?: return
    logOp("I", "期間変更: ${year}年${month1to12}月"); applyStructure(Ws1Ops.resizeDays(st.copy(startDate = first.toString()), sched, first.lengthOfMonth()))
}

/** 現在の開始日から相対的に月を移動（-1=前月 / +1=翌月）。開始日が不明なら端末の今月を起点。 */
fun MagiViewModel.shiftMonth(delta: Int) {
    val base = runCatching { java.time.LocalDate.parse(ui.value.startDate) }.getOrNull()
        ?: java.time.LocalDate.now().withDayOfMonth(1)
    val m = base.withDayOfMonth(1).plusMonths(delta.toLong())
    setMonth(m.year, m.monthValue)
}

/** [実機指摘] 月末に「来月」の勤務表を作る業務のため、ワンタップは来月が適切。 */
fun MagiViewModel.setNextMonth() {
    val next = java.time.LocalDate.now().plusMonths(1)
    setMonth(next.year, next.monthValue)
}

// ---- スキルグループ（年次マスター・新C41s/C42s 専用） -----------------------
fun MagiViewModel.skillGroups(): List<Group> = state?.skillGroups ?: emptyList()
fun MagiViewModel.addSkillGroup(name: String, kigou: String) {
    val st = state ?: return; if (kigou.isBlank()) return
    if (symbolTaken(st.skillGroups.map { it.kigou }, kigou, "スキル区分")) return
    logOp("I", "スキル区分追加: ${name.trim()}(${kigou.trim()})"); applyStructure(st.copy(skillGroups = st.skillGroups + Group(name.trim(), kigou.trim())))
}
fun MagiViewModel.editSkillGroup(g: Int, name: String, kigou: String) {
    val st = state ?: return
    if (symbolTaken(st.skillGroups.map { it.kigou }, kigou, "スキル区分", exceptIndex = g)) return
    val old = st.skillGroups.getOrNull(g)?.kigou ?: ""
    val renamed = st.copy(skillGroups = st.skillGroups.mapIndexed { i, x -> if (i == g) Group(name.trim(), kigou.trim()) else x })
    logOp("I", "スキル区分編集: [$g] → ${name.trim()}(${kigou.trim()})")
    // [記号変更の伝播] スキル群記号を変えたら cons41s/cons42s の参照も一括置換(幽霊行防止)
    applyStructure(Ws1Ops.renameSkillGroupInConstraints(renamed, old, kigou.trim()))
}
fun MagiViewModel.removeSkillGroup(g: Int) {
    val st = state ?: return
    // [3.330.0] 移行規則は Ws1Ops.removeSkillGroup（担当グループの removeGroup と対）。
    //   ここに手書きしていた間はホストでテストできなかった。
    logOp("I", "スキル区分削除: [$g]"); applyStructure(Ws1Ops.removeSkillGroup(st, g))
}
fun MagiViewModel.setStaffSkill(i: Int, skillIdx: Int) {
    val st = state ?: return
    logOp("I", "スキル割当: ${opNm(i)} → 区分[$skillIdx]"); applyStructure(st.copy(staff = st.staff.mapIndexed { idx, s -> if (idx == i) s.copy(skillIdx = skillIdx) else s }))
}

/** グループを削除できるか（2グループ以上あれば可。所属者がいても先頭グループへ移動して削除）。 */
fun MagiViewModel.ws1CanRemoveGroup(g: Int): Boolean = state?.let { g in it.groups.indices && it.groups.size > 1 } ?: false

/** グループgの所属人数（削除確認の警告表示用）。 */
fun MagiViewModel.ws1GroupMemberCount(g: Int): Int = state?.staff?.count { it.groupIdx == g } ?: 0

/** [3.429.0/R-03] 削除確認ダイアログで見せる影響件数（Ws1Ops.shiftRefCount/groupRefCount へ委譲）。
 *  対象のシフト/グループを参照する制約行数。0 件なら影響なし。 */
fun MagiViewModel.ws1ShiftRefCount(k: Int): Int = state?.let { st -> st.shifts.getOrNull(k)?.let { Ws1Ops.shiftRefCount(st, it.kigou) } } ?: 0
fun MagiViewModel.ws1GroupRefCount(g: Int): Int = state?.let { st -> st.groups.getOrNull(g)?.let { Ws1Ops.groupRefCount(st, it.kigou) } } ?: 0
fun MagiViewModel.ws1SkillGroupRefCount(g: Int): Int = state?.let { st -> st.skillGroups.getOrNull(g)?.let { Ws1Ops.skillGroupRefCount(st, it.kigou) } } ?: 0

fun MagiViewModel.ws1RemoveShift(k: Int) {
    val st = state ?: return
    val sched = currentSchedule ?: return
    // [3.416.0/方針「休は通常のシフト定義」] 旧: 休シフトの削除を入口で拒否（3.106.0）。撤廃＝
    //   休も他シフトと同じ編集規則。削除セルは残りの一覧の「休」（無ければ先頭シフト）へ。
    logOp("I", "シフト削除: ${opSy(k)}（このシフトのマスは休（無ければ先頭シフト）へ・希望も削除）")
    applyStructure(Ws1Ops.removeShift(st, sched, k))
}

fun MagiViewModel.ws1RemoveStaff(i: Int) {
    val st = state ?: return
    val sched = currentSchedule ?: return
    logOp("I", "職員削除: ${opNm(i)}（勤務行・希望・個人の回数も削除）")
    applyStructure(Ws1Ops.removeStaff(st, sched, i))
}

/** [3.530.0/ドラッグ&ドロップ] 職員を任意位置へ移動。[ws1MoveShiftTo] と同じ理由・同じ形
 *  （3.515.3の隣接swap `Ws1Ops.moveStaff` を from→to の方向へ内部で繰り返し適用）。 */
fun MagiViewModel.ws1MoveStaffTo(from: Int, to: Int) {
    val st0 = state ?: return
    val sched0 = currentSchedule ?: return
    if (from == to || from !in st0.staff.indices || to !in st0.staff.indices) return
    val name = opNm(from)
    val dir = if (to > from) 1 else -1
    var r = Ws1Ops.moveStaff(st0, sched0, from, dir)
    var pos = from + dir
    while (pos != to) { r = Ws1Ops.moveStaff(r.state, r.schedule, pos, dir); pos += dir }
    logOp("I", "職員の並び替え: $name を${from + 1}→${to + 1}番目へ")
    applyStructure(r)
}

/** [3.515.6/ドラッグ&ドロップ] シフト種別を任意位置へ移動。行のドラッグを離した瞬間に1回だけ呼ぶ想定
 *  （3.515.3の隣接swap `Ws1Ops.moveShift` を from→to の方向へ内部で繰り返し適用し、検査・ログ・undo
 *  チェックポイントは最後に1回だけにする＝ドラッグ中に何度も検査が走るのを避ける）。 */
fun MagiViewModel.ws1MoveShiftTo(from: Int, to: Int) {
    val st0 = state ?: return
    val sched0 = currentSchedule ?: return
    if (from == to || from !in st0.shifts.indices || to !in st0.shifts.indices) return
    val name = opSy(from)
    val dir = if (to > from) 1 else -1
    var r = Ws1Ops.moveShift(st0, sched0, from, dir)
    var pos = from + dir
    while (pos != to) { r = Ws1Ops.moveShift(r.state, r.schedule, pos, dir); pos += dir }
    logOp("I", "シフトの並び替え: $name を${from + 1}→${to + 1}番目へ")
    applyStructure(r)
}

/** [3.515.6] グループを任意位置へ移動。[ws1MoveShiftTo] と同じ理由・同じ形。`Ws1Ops.moveGroup` は
 *  schedule を返さないため `MagiState` 版の `applyStructure`（`ws1RemoveGroup` と同じ）を使う。 */
fun MagiViewModel.ws1MoveGroupTo(from: Int, to: Int) {
    val st0 = state ?: return
    if (from == to || from !in st0.groups.indices || to !in st0.groups.indices) return
    val name = st0.groups.getOrNull(from)?.let { toHankakuKigou(it.kigou) } ?: "#$from"
    val dir = if (to > from) 1 else -1
    var ns = Ws1Ops.moveGroup(st0, from, dir)
    var pos = from + dir
    while (pos != to) { ns = Ws1Ops.moveGroup(ns, pos, dir); pos += dir }
    logOp("I", "グループの並び替え: $name を${from + 1}→${to + 1}番目へ")
    applyStructure(ns)
}

fun MagiViewModel.ws1RemoveGroup(g: Int) {
    val st = state ?: return
    if (g !in st.groups.indices || st.groups.size <= 1) return
    // 所属者は先頭グループへ移る＝担当できるシフトが黙って変わるので、人数を必ず記録する。
    val moved = st.staff.count { it.groupIdx == g }
    logOp("I", "グループ削除: [$g]" + if (moved > 0) "（所属${moved}名は先頭グループへ移動＝担当できるシフトが変わります）" else "")
    applyStructure(Ws1Ops.removeGroup(st, g))
}
