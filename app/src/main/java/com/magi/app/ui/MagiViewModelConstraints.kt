package com.magi.app.ui

import com.magi.app.model.C1Row
import com.magi.app.model.C2Row
import com.magi.app.model.C3Row
import com.magi.app.model.C41Row
import com.magi.app.model.C42Row
import com.magi.app.model.C3wRow
import com.magi.app.ui.MagiViewModel.ConstraintFamilyView
import kotlinx.coroutines.flow.update

/**
 * [MagiViewModel] の制約CRUD（cons1/cons2/cons3系4種/cons41(s)/cons42(s) の一覧・追加・変更・削除）。
 * 本体ファイルから extension 関数として抽出（責務別の物理分割＝AIコードレビュー時のコンテキスト
 * 圧迫対策）。ロジックは一切変更していない。
 *
 * MagiViewModel は class のため v6 層（object 分割）と違い partial class 相当が無く、分割は
 * 「extension 関数を別ファイルへ置く」形をとる。触るメンバは state（読み取りのみ・internal +
 * private set）・logOp・mutateConstraints（編集ゲート＝本体に残置。undo/editRev/再検査/自動保存は
 * すべてこのゲートが担う）の3つだけで、いずれも internal 昇格＝モジュール内限定の可視化。
 *
 * 呼出側（ConstraintEditor/SkillGroupEditor）は同一パッケージのため無修正で拡張関数へ解決される。
 */
fun MagiViewModel.groupKigouList(): List<String> = state?.groups?.map { it.kigou } ?: emptyList()

/** [冗長除去/データ密度] 1日人数の上下限 [l〜u] を意味で圧縮して短く表す。見出しが「人数(上下限)」の
 *  文脈を担うので、行は記号のみで足りる。l==u=ちょうどN / 下限のみ=N以上 / 上限のみ=N以下 / 両方=l〜u。 */
private fun boundLabel(l: String, u: String): String {
    val lo = l.ifBlank { null }; val hi = u.ifBlank { null }
    return when {
        lo != null && hi != null && lo == hi -> "ちょうど$lo"
        lo != null && hi != null -> "$lo〜$hi"
        lo != null -> "$lo 以上"
        hi != null -> "$hi 以下"
        else -> "制限なし"
    }
}

fun MagiViewModel.constraintFamilies(): List<ConstraintFamilyView> {
    val st = state ?: return emptyList()
    fun seq(p: List<String>) = p.filter { it.isNotBlank() }.joinToString(" -> ").ifEmpty { "(空)" }
    return listOf(
        // [用語統一/下流→上流] 節タイトルは違反チップ(breakdownLabels)の語彙を正として一致させる
        //   （違反を見て設定を直しに来たとき同じ名前で見つかるように）。単位や補足は括弧で添える。
        ConstraintFamilyView("cons1", "期間の制約（○日間で○回など）",
            st.cons1.map { "${it.shiftKigou}   ${it.day1}日で${it.day2}回以上" }),
        ConstraintFamilyView("cons2", "個人の合計（回数）",
            st.cons2.map { "${it.shiftKigou}   合計${it.count}回以上" }),
        ConstraintFamilyView("cons3", "必須の並び", st.cons3.map { seq(it.pattern) }),
        ConstraintFamilyView("cons3n", "禁止の並び", st.cons3n.map { seq(it.pattern) }),
        ConstraintFamilyView("cons3m", "推奨の並び", st.cons3m.map { seq(it.pattern) }),
        ConstraintFamilyView("cons3mn", "回避の並び", st.cons3mn.map { seq(it.pattern) }),
        // [3.542.0] 希望で固定した X の前日だけ Y を禁止（HARD）。素の並び禁止は cons3n。
        ConstraintFamilyView("cons3w", "希望の前日に禁止（必ず守る）",
            st.cons3w.map { "${it.wishKigou} の希望の前日は ${it.prevKigou} 禁止" }),
        ConstraintFamilyView("cons41", "グループのレンジ（1日の人数の下限〜上限）",
            st.cons41.map { "${it.groupKigou}・${it.shiftKigou}   ${boundLabel(it.l, it.u)}" }),
        // [3.409.18] 「禁止/不可」はラベルとして実態（最軽量のソフト条件＝他の条件と衝突すると
        //   真っ先に譲られる）と逆の約束をするため「できるだけ守る」を見出しへ明示（3.405.0 の言葉版）。
        // [3.427.0] 行タイトルを「吉・休 ✕ 古・休」→「吉の休 ✕ 古の休」（の形）へ。3.409.18 は
        //   羅列が読めない問題を行下の読み下し文で補ったが、タイトル自体を読める形にすれば
        //   文は見出しの「同じ日に不可」と全て重複＝行ごとの文を撤去（7行×2行→7行×1行）。
        ConstraintFamilyView("cons42", "グループペア禁止（同じ日に不可・できるだけ守る）",
            st.cons42.map { "${it.g1Kigou}の${it.s1Kigou} ✕ ${it.g2Kigou}の${it.s2Kigou}" }),
    )
}

/** [スキルグループ専用ルール] C41s/C42s。スキルグループ定義の直下に co-locate して表示する。 */
fun MagiViewModel.skillConstraintFamilies(): List<ConstraintFamilyView> {
    val st = state ?: return emptyList()
    return listOf(
        ConstraintFamilyView("cons41s", "スキルグループのレンジ（1日の人数の下限〜上限）",
            st.cons41s.map { "${it.groupKigou}・${it.shiftKigou}   ${boundLabel(it.l, it.u)}" }),
        ConstraintFamilyView("cons42s", "スキルグループペア禁止（同じ日に不可・できるだけ守る）",
            st.cons42s.map { "${it.g1Kigou}の${it.s1Kigou} ✕ ${it.g2Kigou}の${it.s2Kigou}" }),
    )
}

fun MagiViewModel.skillGroupKigouList(): List<String> = state?.skillGroups?.map { it.kigou } ?: emptyList()
fun MagiViewModel.addCons41s(groupKigou: String, shiftKigou: String, l: String, u: String) {
    val st = state ?: return
    logOp("I", "制約追加(スキルグループ回数): $groupKigou $shiftKigou ${l.trim()}〜${u.trim()}"); mutateConstraints(st.copy(cons41s = st.cons41s + C41Row(groupKigou, shiftKigou, l.trim(), u.trim())))
}
fun MagiViewModel.addCons42s(g1: String, g2: String, s1: String, s2: String) {
    val st = state ?: return
    logOp("I", "制約追加(スキルグループ組合せ禁止): ${g1}${s1} & ${g2}${s2}"); mutateConstraints(st.copy(cons42s = st.cons42s + C42Row(g1, g2, s1, s2)))
}

fun MagiViewModel.addCons1(day1: String, shiftKigou: String, day2: String) {
    val st = state ?: return
    logOp("I", "制約追加(連勤/休): ${day1.trim()}日に${shiftKigou}${day2.trim()}回以上"); mutateConstraints(st.copy(cons1 = st.cons1 + C1Row(day1.trim(), shiftKigou, day2.trim())))
}

fun MagiViewModel.addCons2(shiftKigou: String, count: String) {
    val st = state ?: return
    logOp("I", "制約追加(cons2): $shiftKigou ${count.trim()}"); mutateConstraints(st.copy(cons2 = st.cons2 + C2Row(shiftKigou, count.trim())))
}

fun MagiViewModel.addCons41(groupKigou: String, shiftKigou: String, l: String, u: String) {
    val st = state ?: return
    logOp("I", "制約追加(グループ回数): $groupKigou $shiftKigou ${l.trim()}〜${u.trim()}"); mutateConstraints(st.copy(cons41 = st.cons41 + C41Row(groupKigou, shiftKigou, l.trim(), u.trim())))
}

fun MagiViewModel.addCons42(g1: String, g2: String, s1: String, s2: String) {
    val st = state ?: return
    logOp("I", "制約追加(グループ組合せ禁止): ${g1}${s1} & ${g2}${s2}"); mutateConstraints(st.copy(cons42 = st.cons42 + C42Row(g1, g2, s1, s2)))
}

fun MagiViewModel.addCons3w(wishKigou: String, prevKigou: String) {
    val st = state ?: return
    if (st.cons3w.any { it.wishKigou == wishKigou && it.prevKigou == prevKigou }) {
        _ui.update { it.copy(messageIsError = true, message = "「${wishKigou} の希望の前日は ${prevKigou} 禁止」は登録済みです") }
        return
    }
    logOp("I", "制約追加(希望前日禁止): ${wishKigou} の希望の前日は ${prevKigou} 禁止"); mutateConstraints(st.copy(cons3w = st.cons3w + C3wRow(wishKigou, prevKigou)))
}

/** 並び4族の日本語名（ConstraintDialog の見出しと同じ語彙）。 */
internal fun seqFamilyJp(family: String): String = when (family) {
    "cons3" -> "必須の並び"; "cons3n" -> "禁止の並び"; "cons3m" -> "推奨の並び"; "cons3mn" -> "回避の並び"; else -> family
}

/**
 * [3.482.0 入口ガード] 同じ並びが既に登録されていれば、その族の日本語名を返す（無ければ null）。
 * 旧: 追加/変更のどちらにも重複検出が無く、見本データの `Dﾃ→A4` が禁止の並びに2行入ったまま
 * 事後診断（V6SanityPort の DELETE_DUP_SEQ）が「重複を1つ削除」を提案するだけだった＝入口で止める。
 * 族をまたぐ同一の並び（例: 禁止と回避に同じ `Cｵ→Aｱ`）も返す。HARD の禁止と SOFT の回避を同じ並びへ
 * 二重掛けしても評価は禁止側が支配し回避側は無意味＝登録の意図と違う可能性が高いので、事後診断
 * （族内しか見ない `collectDuplicateSeq`）より一段厳しく入口で知らせる。
 * 正規化は addCons3 と同じ（先頭から最初の空白まで・最大5）。`excludeIndex` は変更時に自分自身を除く。
 */
fun MagiViewModel.seqDuplicateOf(family: String, pattern: List<String>, excludeIndex: Int? = null): String? {
    val st = state ?: return null
    val key = pattern.map { it.trim() }.takeWhile { it.isNotEmpty() }.take(5).joinToString("→")
    if (key.isBlank()) return null
    val fams = listOf("cons3" to st.cons3, "cons3n" to st.cons3n, "cons3m" to st.cons3m, "cons3mn" to st.cons3mn)
    for ((fam, rows) in fams) {
        rows.forEachIndexed { idx, r ->
            if (fam == family && idx == excludeIndex) return@forEachIndexed
            val k = r.pattern.map { it.trim() }.takeWhile { it.isNotEmpty() }.take(5).joinToString("→")
            if (k == key) return seqFamilyJp(fam)
        }
    }
    return null
}

fun MagiViewModel.addCons3(family: String, pattern: List<String>) {
    val st = state ?: return
    // Level Zero loads cons3 by reading day columns until the first blank (truncate at
    // first blank, max 5 days), not by removing all blanks. Match that here.
    val pat = pattern.map { it.trim() }.takeWhile { it.isNotEmpty() }.take(5)
    if (pat.isEmpty()) return
    // [3.482.0 入口ガード] 画面（ConstraintDialog）でも同じ判定で OK を無効化しているが、VM 側でも止める
    //   （CSV 取込など画面を通らない経路は従来どおり事後診断に任せる＝ここは画面経由の追加だけ）。
    seqDuplicateOf(family, pat)?.let { dupFam ->
        logOp("W", "制約追加を無視($family): ${pat.joinToString("→")} は「$dupFam」に登録済み")
        _ui.update { it.copy(messageIsError = true, message = "同じ並び「${pat.joinToString("→")}」は「$dupFam」に登録済みです") }
        return
    }
    logOp("I", "制約追加($family): ${pat.joinToString("→")}")
    mutateConstraints(
        when (family) {
            "cons3" -> st.copy(cons3 = st.cons3 + C3Row(pat))
            "cons3n" -> st.copy(cons3n = st.cons3n + C3Row(pat))
            "cons3m" -> st.copy(cons3m = st.cons3m + C3Row(pat))
            "cons3mn" -> st.copy(cons3mn = st.cons3mn + C3Row(pat))
            else -> return
        }
    )
}

fun MagiViewModel.removeConstraint(family: String, index: Int) {
    val st = state ?: return
    // [3.271.0, 実機ログ起因] index を先に検証する。旧: 検証なしで先にログ→mutate のため、
    //   リスト縮小後の古い index（連続タップ等）でも「制約削除: cons3mn[7]」の幻ログ＋無駄な
    //   undo/保存/再検査が走り、実機ログで「2回削除されたのか1回なのか」が判別不能だった
    //   （without() 自体は no-op なのでデータは壊れない＝ログと副作用だけが嘘をつく状態）。
    val size = when (family) {
        "cons1" -> st.cons1.size
        "cons2" -> st.cons2.size
        "cons3" -> st.cons3.size
        "cons3n" -> st.cons3n.size
        "cons3m" -> st.cons3m.size
        "cons3mn" -> st.cons3mn.size
        "cons41" -> st.cons41.size
        "cons42" -> st.cons42.size
        "cons41s" -> st.cons41s.size
        "cons42s" -> st.cons42s.size
        "cons3w" -> st.cons3w.size
        else -> return
    }
    if (index !in 0 until size) {
        logOp("W", "制約削除を無視: $family[$index] は存在しません（削除済みの行への連続タップ等）")
        return
    }
    logOp("I", "制約削除: $family[$index]")
    fun <T> List<T>.without(i: Int) = filterIndexed { idx, _ -> idx != i }
    mutateConstraints(
        when (family) {
            "cons1" -> st.copy(cons1 = st.cons1.without(index))
            "cons2" -> st.copy(cons2 = st.cons2.without(index))
            "cons3" -> st.copy(cons3 = st.cons3.without(index))
            "cons3n" -> st.copy(cons3n = st.cons3n.without(index))
            "cons3m" -> st.copy(cons3m = st.cons3m.without(index))
            "cons3mn" -> st.copy(cons3mn = st.cons3mn.without(index))
            "cons41" -> st.copy(cons41 = st.cons41.without(index))
            "cons42" -> st.copy(cons42 = st.cons42.without(index))
            "cons41s" -> st.copy(cons41s = st.cons41s.without(index))
            "cons42s" -> st.copy(cons42s = st.cons42s.without(index))
            "cons3w" -> st.copy(cons3w = st.cons3w.without(index))
            else -> return
        }
    )
}

/** [制約編集/実機指摘「登録した制約の変更ができない」] 行の生値（編集ダイアログのプリフィル用）。
 *  値の並びは追加ダイアログの入力順と同じ:
 *  cons1=[日数,シフト,回数] / cons2=[シフト,回数] / cons3系=並び(最大5) /
 *  cons41(s)=[群,シフト,下限,上限] / cons42(s)=[群1,シフト1,群2,シフト2] / cons3w=[希望シフト,前日禁止シフト]。 */
fun MagiViewModel.constraintRowValues(family: String, index: Int): List<String>? {
    val st = state ?: return null
    return when (family) {
        "cons1" -> st.cons1.getOrNull(index)?.let { listOf(it.day1, it.shiftKigou, it.day2) }
        "cons2" -> st.cons2.getOrNull(index)?.let { listOf(it.shiftKigou, it.count) }
        "cons3" -> st.cons3.getOrNull(index)?.pattern
        "cons3n" -> st.cons3n.getOrNull(index)?.pattern
        "cons3m" -> st.cons3m.getOrNull(index)?.pattern
        "cons3mn" -> st.cons3mn.getOrNull(index)?.pattern
        "cons41" -> st.cons41.getOrNull(index)?.let { listOf(it.groupKigou, it.shiftKigou, it.l, it.u) }
        "cons41s" -> st.cons41s.getOrNull(index)?.let { listOf(it.groupKigou, it.shiftKigou, it.l, it.u) }
        "cons42" -> st.cons42.getOrNull(index)?.let { listOf(it.g1Kigou, it.s1Kigou, it.g2Kigou, it.s2Kigou) }
        "cons42s" -> st.cons42s.getOrNull(index)?.let { listOf(it.g1Kigou, it.s1Kigou, it.g2Kigou, it.s2Kigou) }
        "cons3w" -> st.cons3w.getOrNull(index)?.let { listOf(it.wishKigou, it.prevKigou) }
        else -> null
    }
}

/** [制約編集] 行を同じ位置で置き換える。values の並びは constraintRowValues と同一。
 *  cons3系は追加(addCons3)と同じ正規化（先頭から最初の空白まで・最大5）。 */
fun MagiViewModel.updateConstraint(family: String, index: Int, values: List<String>) {
    val st = state ?: return
    fun <T> List<T>.replaced(i: Int, v: T) = mapIndexed { idx, e -> if (idx == i) v else e }
    val v = values.map { it.trim() }
    fun g(i: Int) = v.getOrElse(i) { "" }
    val next = when (family) {
        "cons1" -> { if (index !in st.cons1.indices) return; st.copy(cons1 = st.cons1.replaced(index, C1Row(g(0), g(1), g(2)))) }
        "cons2" -> { if (index !in st.cons2.indices) return; st.copy(cons2 = st.cons2.replaced(index, C2Row(g(0), g(1)))) }
        "cons41" -> { if (index !in st.cons41.indices) return; st.copy(cons41 = st.cons41.replaced(index, C41Row(g(0), g(1), g(2), g(3)))) }
        "cons41s" -> { if (index !in st.cons41s.indices) return; st.copy(cons41s = st.cons41s.replaced(index, C41Row(g(0), g(1), g(2), g(3)))) }
        "cons42" -> { if (index !in st.cons42.indices) return; st.copy(cons42 = st.cons42.replaced(index, C42Row(g(0), g(2), g(1), g(3)))) }
        "cons42s" -> { if (index !in st.cons42s.indices) return; st.copy(cons42s = st.cons42s.replaced(index, C42Row(g(0), g(2), g(1), g(3)))) }
        "cons3w" -> { if (index !in st.cons3w.indices) return; st.copy(cons3w = st.cons3w.replaced(index, C3wRow(g(0), g(1)))) }
        "cons3", "cons3n", "cons3m", "cons3mn" -> {
            val pat = v.takeWhile { it.isNotEmpty() }.take(5)
            if (pat.isEmpty()) return
            // [3.482.0 入口ガード] 変更後の並びが他の行（自分自身は除く）と同じなら止める（addCons3 と同じ）。
            seqDuplicateOf(family, pat, excludeIndex = index)?.let { dupFam ->
                logOp("W", "制約変更を無視($family[$index]): ${pat.joinToString("→")} は「$dupFam」に登録済み")
                _ui.update { it.copy(messageIsError = true, message = "同じ並び「${pat.joinToString("→")}」は「$dupFam」に登録済みです") }
                return
            }
            when (family) {
                "cons3" -> { if (index !in st.cons3.indices) return; st.copy(cons3 = st.cons3.replaced(index, C3Row(pat))) }
                "cons3n" -> { if (index !in st.cons3n.indices) return; st.copy(cons3n = st.cons3n.replaced(index, C3Row(pat))) }
                "cons3m" -> { if (index !in st.cons3m.indices) return; st.copy(cons3m = st.cons3m.replaced(index, C3Row(pat))) }
                else -> { if (index !in st.cons3mn.indices) return; st.copy(cons3mn = st.cons3mn.replaced(index, C3Row(pat))) }
            }
        }
        else -> return
    }
    logOp("I", "制約変更: $family[$index] → ${v.filter { it.isNotBlank() }.joinToString(" ")}")
    mutateConstraints(next)
}
