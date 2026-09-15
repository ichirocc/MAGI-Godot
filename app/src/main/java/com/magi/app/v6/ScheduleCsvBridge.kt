package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Group
import com.magi.app.model.Staff
import com.magi.app.model.Range
import com.magi.app.model.C1Row
import com.magi.app.model.C2Row
import com.magi.app.model.C3Row
import com.magi.app.model.C41Row
import com.magi.app.model.C42Row
import com.magi.app.model.C3wRow
import java.time.LocalDate

/**
 * 病院などで広く使われる「勤務表テンプレCSV」(CP932/Excel由来) を、完全な [MagiState] として取り込む。
 *
 * 添付サンプル (令和8年7月) の構成:
 *  - 先頭: 年月タイトル（例「令和8年 7月」）
 *  - ユニット(=グループ)ごとのブロック:
 *      行: 「ユニット名：,,<ユニット名>,,1,2,…,31,…」（日番号）
 *      行: 「№,,氏 名,,水,木,金,…」（曜日）
 *      行: 「<№>,<役割>,<氏名>,予定,<31日分のシフト記号>,…,<シフト別集計>」
 *  - 凡例ブロック:
 *      行: 「,記号,時刻/時間,休憩時間,<曜日…>」
 *      行: 「,<記号>,<時刻範囲 or 説明>,<休憩>,<日別の必要人数 31列>」
 *
 * 列位置: 氏名=idx2 / シフト記号は idx4 から T 列 / 凡例は 記号=idx1, 時刻=idx2, 必要数=idx4 から。
 * 空セルは「休」に割り当てる（＝勤務指定の無い日＝公休扱い）。担当可否情報は無いため groupShift は
 * 全シフト可(permissive)で取り込み、利用者が後から調整できるようにする。
 */
/**
 * [2026-09-02, 外部レビュー#86] [RosterCsvImport]/[FlatRosterCsvImport] が取込末尾で組み立てる
 * [MagiState] は、担当可否=全可・需要/制約=空で始めるという方針も含めてフィールド単位で完全に
 * 同一だった（フォーマット固有なのは氏名/日付列のレイアウト・期間推定方法・凡例の有無だけ）。
 * この末尾の構築部分を一本化する（フォーマット固有の解析ロジックは両 object に残す。
 * 冒頭の前処理の共通化は [parseCsvGuarded] 側で行う）。
 */
private fun buildImportedState(
    start: String, end: String,
    shiftsOut: List<Shift>, groupsOut: List<Group>, staffOut: List<Staff>,
    grid: List<IntArray>, wishes: Map<String, Int>,
): MagiState {
    val K = shiftsOut.size
    return MagiState(
        startDate = start,
        endDate = end,
        shifts = shiftsOut,
        groups = groupsOut,
        staff = staffOut,
        use2Patterns = false,
        groupShift = List(groupsOut.size) { List(K) { 1 } },           // 担当可否不明→全可(後から調整)
        groupShiftApt = List(groupsOut.size) { List(K) { "" } },
        schedule = grid.map { it.toList() },
        wishes = wishes,
        staffRange = emptyMap(),
        needDay1 = emptyMap(),   // 必要人数はCSVに無い
        needDay2 = emptyMap(),
        cons1 = emptyList(),
        cons2 = emptyList(),
        cons3 = emptyList(),
        cons3n = emptyList(),
        cons3m = emptyList(),
        cons3mn = emptyList(),
        cons41 = emptyList(),
        cons42 = emptyList(),
    )
}

/** CSV解析＋書式ガード（未閉引用符／空データは取込を断る）。各 CSV 取込 parse() の共通前処理。 */
private fun parseCsvGuarded(text: String): List<List<String>>? {
    // [3.413.0/I-08] 引用符が閉じていないCSVは、開いた引用符以降が1セルへ吸い込まれ**残りの行が
    //   丸ごと消える**。全置換の取込では「消えた」ことが取込結果から分からないので、書式の誤りとして断る。
    val parsed = parseCsvFull(text)
    if (parsed.unclosedQuote) return null
    return parsed.rows.ifEmpty { null }
}

/** 取込で解釈できなかった行の表示用サンプル（先頭60文字）。 */
private fun rowSample(r: List<String>): String = r.joinToString(",").take(60)

/**
 * [3.475.0/論理監査] 引用符が閉じていないCSVか。[parseCsvGuarded] は「未閉引用符」と「空データ」を同じ
 * null で返すため、種類別取込（職員/希望/制約）の呼出側が「取り込める行が0件」と氏名不一致を疑わせる
 * 案内をしていた。3.413.0/I-08 が断る理由（書式の誤り）を利用者へ届けるための判定口。
 */
fun csvHasUnclosedQuote(text: String): Boolean = parseCsvFull(text).unclosedQuote

object RosterCsvImport {
    private const val REST = "休"

    /** このテキストが勤務表テンプレ形式かを軽量判定。 */
    fun detect(text: String): Boolean {
        if (text.contains("ユニット名")) return true
        // 「氏 名」見出し＋時刻範囲(例 8:30～17:30 / 8：30～17：30)の両方があればテンプレとみなす。
        return text.contains("氏 名") && Regex("\\d{1,2}[:：]\\d{2}\\s*[~～]").containsMatchIn(text)
    }

    /**
     * @param asWishes false=本表セルを「勤務表(初期割り当て)」として取り込む（既定）。
     *   true=本表セルを「希望シフト」として取り込む：埋まっているセルは wishes["i,j"]=記号 に、勤務表は
     *   全て公休で開始する（最適化で希望を尊重しつつ必要数を満たす）。空セルは希望なし（自由）。
     *   ※元表の明示「休」セルは希望休として wishes に入り、空セル（通常の休み）と区別される。
     * @param unknownOut [3.475.0/論理監査] 凡例に無い記号（誤字・凡例行の削除）を記号→セル数で返す受け皿。
     *   旧: そのセルを黙って休（希望取込では希望なし）にしており、3.410.0/I-01 が `ScheduleCsvBridge.parse`
     *   に入れた「読めない記号は必ず名指しする」がこの経路だけ抜けていた。
     */
    fun parse(text: String, asWishes: Boolean = false, unknownOut: MutableMap<String, Int>? = null): MagiState? {
        val rows = parseCsvGuarded(text) ?: return null
        fun cell(r: List<String>, idx: Int): String = r.getOrElse(idx) { "" }.trim()
        fun normName(s: String): String = s.replace('　', ' ').trim().replace(Regex("\\s+"), " ")

        // --- 列レイアウト（テンプレ固定。Excel列名→0始まり列番号）---
        //   グループ名 = C列(=2)の各ユニット見出し（例 C2=柳・C13=桐）。氏名 = C列(=2)。
        //   勤務記号 = E列(=4)から右へ T 日分（最大 AI列=34、31日）。シフト記号 = 凡例の B列(=1, 行25〜40)。
        //   スタッフ行は各ユニット見出しの2行下から、空行/凡例/次ユニットの手前まで
        //   （添付サンプルでは 4〜11 行目＝柳・15〜22 行目＝桐。空欄№は自動スキップ）。
        //   ※必要人数(need1/need2)はこのCSVに存在しない。凡例の日別数値は現在表の人数集計(タリー)であり
        //     必要数ではない（休/有の人数も含む）ため、需要としては取り込まない。
        val nameCol = 2           // C
        val dayCol0 = 4           // E
        val maxDayCol = 34        // AI（E..AI = 31日）

        // --- 日数 T: 最初のユニット見出しの日番号(1,2,3,…)の連続から求める（E列〜AI列で頭打ち） ---
        val unitHeaders = rows.indices.filter { cell(rows[it], 0).startsWith("ユニット名") }
        if (unitHeaders.isEmpty()) return null
        val uh0 = rows[unitHeaders.first()]
        var T = 0
        while (dayCol0 + T <= maxDayCol && cell(uh0, dayCol0 + T).toIntOrNull() == T + 1) T++
        if (T < 1) return null

        // --- 凡例(B列25〜40): シフト記号＋時刻表記。必要人数は無い（need1/need2は空）。 ---
        val legendHeader = rows.indexOfFirst { cell(it, 1) == "記号" && (cell(it, 2) == "時刻" || cell(it, 2) == "時間") }
        val shiftsOut = ArrayList<Shift>()
        val symToK = LinkedHashMap<String, Int>()
        if (legendHeader >= 0) {
            var r = legendHeader + 1
            while (r < rows.size) {
                val row = rows[r]
                val sym = cell(row, 1)            // B列＝シフト記号
                if (sym.isEmpty()) break          // 凡例の終端（合計行「Ａ～Ｃ」等）
                if (!symToK.containsKey(sym)) {
                    symToK[sym] = shiftsOut.size
                    val desc = cell(row, 2)       // C列＝時刻/説明（表示名に使用）
                    shiftsOut.add(Shift(name = desc.ifEmpty { sym }, kigou = sym, need1 = "", need2 = ""))
                }
                r++
            }
        }
        // 休シフトは必須（解析・整列の基準）。凡例に無ければ補う。
        if (!symToK.containsKey(REST)) {
            symToK[REST] = shiftsOut.size
            shiftsOut.add(Shift(name = "公休", kigou = REST, need1 = "", need2 = ""))
        }
        if (shiftsOut.isEmpty()) return null
        val restK = symToK.getValue(REST)

        // --- ユニット(グループ)・スタッフ・勤務表グリッド ---
        val groupsOut = ArrayList<Group>()
        val staffOut = ArrayList<Staff>()
        val grid = ArrayList<IntArray>()
        val wishes = LinkedHashMap<String, Int>()
        for (uhIdx in unitHeaders) {
            val unitName = normName(cell(rows[uhIdx], nameCol)).ifEmpty { "G${groupsOut.size + 1}" }
            val g = groupsOut.size
            groupsOut.add(Group(name = unitName, kigou = unitName))
            var rr = uhIdx + 2   // ユニット見出し＋曜日見出しを飛ばす
            while (rr < rows.size) {
                val row = rows[rr]
                if (cell(row, 0).startsWith("ユニット名")) break
                if (cell(row, 1) == "記号") break       // 凡例に到達
                val isStaffRow = cell(row, 3) == "予定"
                if (isStaffRow) {
                    val name = normName(cell(row, nameCol))
                    if (name.isNotEmpty()) {
                        val i = staffOut.size
                        staffOut.add(Staff(name = name, groupIdx = g))
                        val days = IntArray(T) { restK }
                        for (j in 0 until T) {
                            val sym = cell(row, dayCol0 + j)
                            val k = if (sym.isEmpty()) null else symToK[sym]
                            if (k != null) {
                                days[j] = k
                                if (asWishes) wishes["$i,$j"] = k   // 埋まっているセル＝希望
                            } else if (sym.isNotEmpty()) {
                                unknownOut?.merge(sym, 1, Int::plus)   // [3.475.0] 凡例に無い記号は数えて返す
                            }
                        }
                        // 希望取込時は勤務表を全公休で開始（最適化が希望を尊重して埋める）。
                        grid.add(if (asWishes) IntArray(T) { restK } else days)
                    }
                    rr++
                    continue
                }
                if (row.all { it.trim().isEmpty() }) break   // 空行＝ブロック終端
                rr++
            }
        }
        if (staffOut.isEmpty() || groupsOut.isEmpty()) return null

        // --- 期間: タイトル「令和N年 M月」から ---
        val title = cell(rows[0], 0)
        val reiwa = Regex("令和(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val yr = reiwa?.let { 2018 + it } ?: LocalDate.now().year
        // [3.329.0/外部レビュー M-03] 月はまずタイトル文字列から読む。旧は `rows[0].drop(1)` の
        //   セルだけを見ており、「令和8年 7月」が1セルに入った形式では**必ず1月**になっていた。
        val mo = Regex("(\\d{1,2})\\s*月").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 }
            ?: rows[0].drop(1).mapNotNull { it.trim().toIntOrNull() }.firstOrNull { it in 1..12 }
            ?: 1
        val start = String.format("%04d-%02d-01", yr, mo)
        val end = runCatching { LocalDate.parse(start).plusDays((T - 1).toLong()).toString() }.getOrDefault(start)

        // 必要人数はCSVに無い（凡例の日別数値は集計＝需要ではない）。共通構築は buildImportedState 参照。
        return buildImportedState(start, end, shiftsOut, groupsOut, staffOut, grid, wishes)
    }
}

/**
 * 「ユニット列形式」の勤務表CSVを [MagiState] として取り込む（凡例ブロックなし版）。
 *
 * 構成（添付サンプル）:
 *  - ヘッダ行: 「ユニット,No,役職,氏名,1,2,…,31」（日番号は氏名列の右隣から）
 *  - 曜日行(任意): 「,,,曜日,水,木,金,…」
 *  - スタッフ行: 「<ユニット>,<No>,<役職>,<氏名>,<31日分のシフト記号>」
 *
 * [RosterCsvImport] との違い: ユニットが「列(idx0)」/ 氏名は見出し「氏名」の列 / 凡例ブロックが無い。
 * シフト記号は本表セルから収集する。担当可否・apt・制約・需要は無し（全可・空）で取り込み、
 * 期間は曜日行から推定（不可なら当年1月）。空セルは「休」。利用者が後から調整できる。
 */
object FlatRosterCsvImport {
    private const val REST = "休"

    /** ヘッダ行 idx0=="ユニット" かつ 見出し「氏名」を含むか（軽量判定）。 */
    fun detect(text: String): Boolean {
        val rows = parseCsvRows(text)
        return rows.any { r -> r.isNotEmpty() && r[0].trim() == "ユニット" && r.any { it.trim() == "氏名" } }
    }

    fun parse(text: String, asWishes: Boolean = false): MagiState? {
        val rows = parseCsvGuarded(text) ?: return null
        fun cell(r: List<String>, i: Int): String = r.getOrElse(i) { "" }.trim()
        fun normName(s: String): String = s.replace('　', ' ').trim().replace(Regex("\\s+"), " ")

        // ヘッダ行（idx0="ユニット" かつ 見出し「氏名」を含む）と、氏名列・日付開始列を特定。
        val headerIdx = rows.indexOfFirst { r -> cell(r, 0) == "ユニット" && r.any { it.trim() == "氏名" } }
        if (headerIdx < 0) return null
        val header = rows[headerIdx]
        val nameCol = header.indexOfFirst { it.trim() == "氏名" }
        if (nameCol < 0) return null
        val dayCol0 = nameCol + 1
        // 日数T: ヘッダの dayCol0 以降の連番(1,2,3…)の長さ。
        // [3.329.0/外部レビュー M-03] 連番ヘッダが無いときの「最大列数からの推定」を**やめる**。
        //   合計・注記などの末尾列まで日付として取り込み、期間が伸びて中身が空の日ができていた。
        //   期間はデータの根幹なので、推測せず取込を断る（利用者が日付行を足せば通る）。
        var T = 0
        while (dayCol0 + T < header.size && cell(header, dayCol0 + T).toIntOrNull() == T + 1) T++
        if (T < 1) return null

        // 曜日行（任意）: ヘッダ直後で氏名列が「曜日」。
        val youbiRow = rows.getOrNull(headerIdx + 1)?.takeIf { cell(it, nameCol) == "曜日" }

        // スタッフ行を収集（ユニット空欄なら直前を継承＝Excel結合セル対策）。
        val staffRows = ArrayList<Triple<String, String, List<String>>>()
        val symSet = LinkedHashSet<String>()
        var lastUnit = ""
        for (rr in (headerIdx + 1) until rows.size) {
            val r = rows[rr]
            val u = cell(r, 0)
            if (u.isNotEmpty()) lastUnit = u
            val name = normName(cell(r, nameCol))
            if (name.isEmpty() || name == "氏名" || name == "曜日") continue
            if (lastUnit.isEmpty()) continue
            val shifts = (0 until T).map { cell(r, dayCol0 + it) }
            staffRows.add(Triple(lastUnit, name, shifts))
            for (s in shifts) if (s.isNotEmpty()) symSet.add(s)
        }
        if (staffRows.isEmpty()) return null

        // シフト一覧（本表セルから収集、休を先頭）。
        val symbols = ArrayList<String>()
        symbols.add(REST)
        for (s in symSet) if (s != REST) symbols.add(s)
        val symToK = LinkedHashMap<String, Int>()
        symbols.forEachIndexed { i, s -> symToK[s] = i }
        val shiftsOut = symbols.map { Shift(name = it, kigou = it, need1 = "", need2 = "") }
        val restK = symToK.getValue(REST)

        // ユニット→グループ（出現順）。
        val groupOrder = LinkedHashMap<String, Int>()
        for (row in staffRows) groupOrder.getOrPut(row.first) { groupOrder.size }
        val groupsOut = groupOrder.keys.map { Group(name = it, kigou = it) }

        // スタッフ・勤務表グリッド。
        val staffOut = ArrayList<Staff>()
        val grid = ArrayList<IntArray>()
        val wishes = LinkedHashMap<String, Int>()
        for ((i, row) in staffRows.withIndex()) {
            val g = groupOrder.getValue(row.first)
            staffOut.add(Staff(name = row.second, groupIdx = g))
            val days = IntArray(T) { restK }
            for (j in 0 until T) {
                val sym = row.third[j]
                val k = if (sym.isEmpty()) null else symToK[sym]
                if (k != null) {
                    days[j] = k
                    if (asWishes) wishes["$i,$j"] = k
                }
            }
            grid.add(if (asWishes) IntArray(T) { restK } else days)
        }

        // 期間: 曜日行の1日目の曜日から、当年で「1日がその曜日かつT日以上ある月」を推定。不可なら当年1月。
        val yr = LocalDate.now().year
        val dow = youbiRow?.let { cell(it, dayCol0) }?.let {
            mapOf("月" to 1, "火" to 2, "水" to 3, "木" to 4, "金" to 5, "土" to 6, "日" to 7)[it]
        }
        var mo = 1
        if (dow != null) {
            for (m in 1..12) {
                val d = LocalDate.of(yr, m, 1)
                if (d.dayOfWeek.value == dow && d.lengthOfMonth() >= T) { mo = m; break }
            }
        }
        val start = String.format("%04d-%02d-01", yr, mo)
        val end = runCatching { LocalDate.parse(start).plusDays((T - 1).toLong()).toString() }.getOrDefault(start)

        return buildImportedState(start, end, shiftsOut, groupsOut, staffOut, grid, wishes)
    }
}

object ScheduleCsvBridge {
    fun build(state: MagiState, schedule: Array<IntArray>): String {
        val p = Problem(state)
        val s = normalizeSchedule(schedule, p)
        val out = StringBuilder()
        val header = ArrayList<String>()
        header.add("スタッフ \\ 日付")
        for (j in 0 until p.T) header.add(formatDay(state.startDate, j))
        appendCsvRow(out, header)

        for (i in 0 until p.S) {
            val line = ArrayList<String>()
            line.add(state.staff[i].name)
            for (j in 0 until p.T) {
                val k = s[i][j]
                val symbol = state.shifts.getOrNull(k)?.kigou ?: ""
                line.add(symbol)
            }
            appendCsvRow(out, line)
        }

        appendCsvRow(out, emptyList())
        val sumHeader = ArrayList<String>()
        sumHeader.add("集計")
        for (shift in state.shifts) sumHeader.add(shift.kigou)
        appendCsvRow(out, sumHeader)

        val counts = countMatrix(p, s)
        for (i in 0 until p.S) {
            val row = ArrayList<String>()
            row.add(state.staff[i].name)
            for (k in 0 until p.K) row.add(counts[i][k].toString())
            appendCsvRow(out, row)
        }
        return out.toString()
    }

    fun parse(text: String, state: MagiState, base: Array<IntArray>): ScheduleRunResult {
        // [3.413.0/I-08] 引用符が閉じないCSVは残りの行が丸ごと消える。ここは非nullを返す経路なので
        //   断れない代わりに旗を立て、呼出側が「一致が少ない」と「消えた」を区別できるようにする。
        val parsedAll = parseCsvFull(text)
        val rows = parsedAll.rows
        val p = Problem(state)
        val schedule = normalizeSchedule(base, p)
        // [P1修正/レビュー指摘] 重複した氏名/記号は「最初の1件」に解決する（Problem.shiftIdxOf=indexOfFirst と同じ）。
        //   旧: 後勝ちで、制約評価(最初)とCSV取込(最後)が同じ記号を別シフトとして扱っていた。
        val nameToI = firstWinsMap(state.staff.size) { nameMatchKey(state.staff[it].name) }
        val kigouToK = firstWinsMap(state.shifts.size) { state.shifts[it].kigou.trim() }
        // [3.475.0/論理監査] 一致は**職員単位**で数える（旧: 行単位＝同じ職員の行が2つあると 2 と数え、
        //   欠けている職員がいても「全員更新」に見えた。値は後勝ちで前の行が黙って上書きされる）。
        val matchedStaff = HashSet<Int>()
        // [3.410.0/I-01] 未知記号を数える（旧: 黙って読み飛ばしていた）。
        val unknown = LinkedHashMap<String, Int>()
        // [3.475.0/論理監査] 先頭行はヘッダ「スタッフ \ 日付,…」のときだけ飛ばす（旧: 無条件に rr=1 で、
        //   ヘッダ無しCSVの先頭職員が黙って落ち「氏名不一致でスキップ」と誤案内していた。
        //   3.314.0/M-08 が種類別CSVで直したのと同じ穴）。判定は「先頭セルが職員名に解決しない」＝
        //   csvBody() と同じ考え方（ヘッダの先頭セルが職員名と一致することは実運用上ない）。
        // [3.509.1] ヘッダは build() の見出し語か 2 列目以降が日付列のときだけ（先頭が職員名に解決しないだけでは飛ばさない＝氏名誤記の行を落とさない）。
        var rr = if (rows.isNotEmpty() && nameToI[nameMatchKey(rows[0].getOrElse(0) { "" })] == null && looksLikeHeaderRow(rows[0])) 1 else 0
        while (rr < rows.size) {
            val r = rows[rr]
            // build() は勤務表の後に「空行＋『集計』ヘッダ＋職員名で始まる回数行」を出力する。ここで終端しないと
            // 回数行が名前一致で再取込され matched が二重化し、シフト記号が数値の場合は回数値が記号解決して勤務表を破壊する。
            if (r.isEmpty() || r.all { it.isBlank() }) break
            if (r[0].trim() == "集計") break
            if (r[0].trim().isNotEmpty()) {
                val staffIndex = nameToI[nameMatchKey(r[0])]
                if (staffIndex != null) {
                    matchedStaff.add(staffIndex)
                    val last = minOf(p.T, r.size - 1)
                    var j = 0
                    while (j < last) {
                        val sym = r[j + 1].trim()
                        val k = kigouToK[sym]
                        if (k != null) schedule[staffIndex][j] = k
                        else if (sym.isNotEmpty()) unknown[sym] = (unknown[sym] ?: 0) + 1
                        j++
                    }
                }
            }
            rr++
        }
        val report = UnifiedViolationChecker.check(state, schedule)
        val matched = matchedStaff.size
        val unknownTotal = unknown.values.sum()
        val unknownTop = unknown.entries.sortedByDescending { it.value }.take(5).map { "${it.key}(${it.value})" }
        val log = MirrorLog(tag = "CSVImport", message = "CSV取込: staff一致 ${matched}名" +
            if (unknownTotal > 0) " / 読めない記号 ${unknownTotal}セル: ${unknownTop.joinToString("・")}" else "")
        val logs = ArrayList<MirrorLog>()
        logs.add(log)
        logs.addAll(report.logs)
        return ScheduleRunResult(
            schedule, report.copy(logs = logs), matched = matched,
            unknownCells = unknownTotal, unknownSymbols = unknownTop,
            unclosedQuote = parsedAll.unclosedQuote,
        )
    }
}

/** 勤務表CSVのヘッダ行か: 先頭セルが「スタッフ」を含むか、2 列目以降の非空セルがすべて日付列（数字・日付書式）。 */
private fun looksLikeHeaderRow(row: List<String>): Boolean {
    val head = row.getOrElse(0) { "" }.trim()
    if (head.contains("スタッフ") || head.contains("日付") || head.contains("氏名")) return true
    val rest = row.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
    return rest.isNotEmpty() && rest.all { c -> c.all { it.isDigit() || it == '/' || it == '-' || it == '.' } }
}

private fun appendCsvRow(out: StringBuilder, values: List<String>) {
    var idx = 0
    while (idx < values.size) {
        if (idx > 0) out.append(',')
        out.append(csvEscapeCell(values[idx]))
        idx++
    }
    out.append('\n')
}

private fun csvEscapeCell(value: String): String {
    var mustQuote = false
    for (ch in value) {
        if (ch == ',' || ch == '"' || ch == '\n' || ch == '\r') {
            mustQuote = true
            break
        }
    }
    val escaped = value.replace("\"", "\"\"")
    return if (mustQuote) "\"$escaped\"" else escaped
}

/**
 * 氏名照合用キー: 全角(U+3000)/半角を含む空白を全て除去する。これにより外部CSVの
 * "山本 昌幸"(空白あり) と 状態側の "山本昌幸"(空白なし) を同一人物として照合できる
 * （取込で1人分しか入らない/氏名不一致で弾かれる事故を防ぐ）。
 */
internal fun nameMatchKey(s: String): String = s.filterNot { it.isWhitespace() }

/** [P1/重複解決の一致] 先勝ちの index マップ。Kotlin の associateBy は後勝ちで、制約評価
 *  （Problem の indexOfFirst=先勝ち）と食い違うため、CSV照合は必ずこちらを使う。 */
internal fun firstWinsMap(n: Int, key: (Int) -> String): Map<String, Int> {
    val m = LinkedHashMap<String, Int>()
    for (i in 0 until n) { val k = key(i); if (!m.containsKey(k)) m[k] = i }
    return m
}

/**
 * [3.413.0/I-08] CSV の解析結果。旧実装は行だけを返し、**引用符が閉じないまま入力が終わっても
 * 何も検出しなかった**（`inQuote` が true のまま抜ける）。この場合、開いた引用符以降の全文が
 * 1セルへ吸い込まれ**残りの行が丸ごと消える**のに、呼出側からは「短いCSV」と区別が付かない。
 * 走査器を2つ作ると必ずドリフトするので、既存のループから両方を返す形にして
 * [parseCsvRows] はその行だけを取り出す薄い委譲にする（既存の呼出は無変更）。
 */
private class CsvParse(val rows: List<List<String>>, val unclosedQuote: Boolean)

private fun parseCsvRows(raw: String): List<List<String>> = parseCsvFull(raw).rows

private fun parseCsvFull(raw: String): CsvParse {
    // UTF-8 BOM(U+FEFF) 除去: 付いていると先頭セルが "\uFEFFユニット" 等になり、trim()でも消えず
    //   ヘッダ判定(== "ユニット" 等)が失敗して取り込めなくなる。Excel/UTF-8出力由来で頻出。
    val text = if (raw.isNotEmpty() && raw[0] == '\uFEFF') raw.substring(1) else raw
    val rows = ArrayList<List<String>>()
    val row = ArrayList<String>()
    val cell = StringBuilder()
    var inQuote = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (inQuote && c == '"' && i + 1 < text.length && text[i + 1] == '"') {
            cell.append('"')
            i++
        } else if (c == '"') {
            inQuote = !inQuote
        } else if (!inQuote && c == ',') {
            row.add(cell.toString())
            cell.setLength(0)
        } else if (!inQuote && (c == '\n' || c == '\r')) {
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
            row.add(cell.toString())
            cell.setLength(0)
            rows.add(ArrayList(row))
            row.clear()
        } else {
            cell.append(c)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) {
        row.add(cell.toString())
        rows.add(ArrayList(row))
    }
    return CsvParse(rows, inQuote)
}

/**
 * [3.314.0] コンポーネント別CSV の本体行を返す。
 *
 * 旧実装は各 parse が `if (rows.size < 2) return null` で**1行だけのCSVを無条件に拒否**し、
 * かつヘッダ判定を「先頭が既知の職員名か」という間接的な推測に頼っていた。`build()` が出す実ヘッダ
 * （氏名 / 種別 …）で明示的に判定し、それ以外は全行を本体として扱う。1行データも取り込める。
 */
private fun csvBody(rows: List<List<String>>, headerFirstCell: String): List<List<String>> {
    if (rows.isEmpty()) return emptyList()
    val head = rows[0].getOrElse(0) { "" }.trim()
    return if (head == headerFirstCell) rows.drop(1) else rows
}

// ============================================================================
// コンポーネント別CSV入出力（オペレーターが取込種別を選択して使用）
//   各CSVは1行目をヘッダとして読み飛ばす。氏名・群・シフトは「氏名/記号」で照合。
//   上の private parseCsvRows / appendCsvRow / csvEscapeCell を共用する。
// ============================================================================

/** スタッフ一覧: 「氏名,グループ,スキル」。氏名一致で所属群・スキルを更新（追加/削除はしない）。 */
/**
 * [3.329.0/外部レビュー H-02] コンポーネント別CSV取込の結果。
 *
 * これらの取込は**既存を全置換**する（希望なら `wishes` を丸ごと差し替える）。旧実装は
 * 未知の氏名・記号・日付の行を `continue` で黙って捨て、1行でも有効なら置換を実行していた。
 * つまり「80行のうち79行が誤記のCSV」を読ませると、**残り79件の希望が消える**。
 * 中身が空でない行を1つでも解釈できなかったら、呼び出し側が置換を中止できるように件数を返す。
 */
class ComponentImport(
    val state: MagiState,
    val accepted: Int,
    val rejected: Int,
    /**
     * 解釈できなかった行の例（最大[MAX_SAMPLES]件、利用者へどこが悪いか示すため）。
     * [2026-09-02, 外部レビュー#76] 旧実装は最初の1行しか保持せず、複数種類の原因が混在するCSVでは
     * 1回の取込結果から1つしか原因が分からず、修正のたびに再アップロードが必要だった。
     * `ScheduleCsvBridge.parse` の `unknownTop`（上位5件保持）と同じ考え方で複数件へ拡張する。
     */
    samples: List<String>,
) {
    /** 上限は構築時にここで保証する（呼出側の take() に頼らない＝KDocの約束を構造で守る）。 */
    val samples: List<String> = samples.take(MAX_SAMPLES)
    companion object { const val MAX_SAMPLES = 3 }
}

object StaffCsvIO {
    fun build(state: MagiState): String {
        val sb = StringBuilder()
        appendCsvRow(sb, listOf("氏名", "グループ", "スキル"))
        for (s in state.staff) {
            appendCsvRow(sb, listOf(
                s.name,
                state.groups.getOrNull(s.groupIdx)?.kigou ?: "",
                state.skillGroups.getOrNull(s.skillIdx)?.kigou ?: "",
            ))
        }
        return sb.toString()
    }

    /** @return Pair(更新後state, 一致件数) または null（解析不能/一致0件）。 */
    fun parse(text: String, state: MagiState): Pair<MagiState, Int>? {
        val rows = parseCsvGuarded(text) ?: return null
        val nameToI = firstWinsMap(state.staff.size) { nameMatchKey(state.staff[it].name) }
        val gByK = firstWinsMap(state.groups.size) { state.groups[it].kigou.trim() }
        val skByK = firstWinsMap(state.skillGroups.size) { state.skillGroups[it].kigou.trim() }
        val newStaff = state.staff.toMutableList()
        var matched = 0
        // [3.314.0] ヘッダ判定を `build()` が出す実ヘッダ「氏名」の一致へ。旧:「先頭が既知の職員名か」
        //   という間接的な推測で、**未知の職員名で始まるヘッダ無CSVの先頭行を黙って捨てて**いた。
        val body = csvBody(rows, "氏名")
        for (r in body) {
            val name = r.getOrElse(0) { "" }.trim()
            if (name.isEmpty()) continue
            val i = nameToI[nameMatchKey(name)] ?: continue
            matched++
            val gi = gByK[r.getOrElse(1) { "" }.trim()] ?: newStaff[i].groupIdx
            val si = skByK[r.getOrElse(2) { "" }.trim()] ?: newStaff[i].skillIdx
            newStaff[i] = newStaff[i].copy(groupIdx = gi, skillIdx = si)
        }
        if (matched == 0) return null
        return state.copy(staff = newStaff) to matched
    }

    /** スタッフ一覧 upsert の結果（新規追加分の勤務表行も反映済み）。 */
    /**
     * @param unknownGroups 空でないのに既存のグループ記号と一致しなかったセル（記号→件数）。
     *   新規職員は先頭グループへ、既存職員は現状維持へ**黙って**落ちるため、呼出側が必ず知らせる。
     * @param unknownSkills 同じくスキル群。未所属(-1)へ落ちる。
     */
    class StaffUpsertResult(
        val state: MagiState, val schedule: Array<IntArray>, val updated: Int, val added: Int,
        val unknownGroups: Map<String, Int> = emptyMap(), val unknownSkills: Map<String, Int> = emptyMap(),
    )

    /**
     * [氏名,グループ,スキル] を upsert で取込: 既存氏名は所属群/スキルを更新、未知の氏名は
     * 新規スタッフとして追加し勤務表に1行足す。空き日を何で埋めるかは `Ws1Ops.fillShift`
     * （その群が休を担当できるなら休、できなければ担当できる先頭のシフト）が決める
     * ＝3.442.0/H3。旧 KDoc の「休(0)」は 3.329.0 の記号解決化より前の記述だった。氏名は空白無視で照合。
     * 群/スキルは記号(kigou)照合、未知なら新規は先頭群/未所属(-1)・既存は現状維持。
     * [3.413.0/I-07] 未知の記号は [StaffUpsertResult.unknownGroups]/[StaffUpsertResult.unknownSkills] に
     * 記録する（空欄＝指定なしと、誤記＝解決できなかった、を呼出側が区別できるようにするため）。
     * @return StaffUpsertResult、または null（解析不能/更新0かつ追加0）。
     */
    fun parseUpsert(text: String, state: MagiState, sched: Array<IntArray>): StaffUpsertResult? {
        val rows = parseCsvGuarded(text) ?: return null
        val nameToI = firstWinsMap(state.staff.size) { nameMatchKey(state.staff[it].name) }
        val gByK = firstWinsMap(state.groups.size) { state.groups[it].kigou.trim() }
        val skByK = firstWinsMap(state.skillGroups.size) { state.skillGroups[it].kigou.trim() }
        val newStaff = state.staff.toMutableList()
        val t = if (sched.isNotEmpty()) sched[0].size else state.dayCount
        val extraRows = ArrayList<IntArray>()
        val seenNew = HashMap<String, Int>()
        val seenExisting = HashSet<Int>()   // [3.475.0] 既存職員は1人1回だけ「更新」に数える
        var updated = 0
        var added = 0
        // [3.413.0/I-07] 空でないのに解決できなかった群/スキル記号を数える。旧実装は `gi ?: 0`（新規＝
        //   先頭グループ）・`gi ?: cur.groupIdx`（既存＝現状維持）で、**空欄と誤記が見分けられない**まま
        //   黙って落ちていた。所属グループは担当できるシフトを決めるので、誤記が通ると「なぜこの人が
        //   この勤務に入るのか」が説明できない盤面になる。3.410.0 の勤務表CSV未知記号と同じ形で知らせる。
        val unknownG = LinkedHashMap<String, Int>()
        val unknownS = LinkedHashMap<String, Int>()
        // [3.314.0] 実ヘッダ「氏名」の一致で判定する。この経路は未知名を**新規追加**するため、旧実装は
        //   「ヘッダ文字列を職員として登録しない」保守のために既知名一致のときだけ先頭行を本体へ入れて
        //   おり、**先頭が新規職員のヘッダ無CSVはその1件を黙って捨てて**いた。厳密なヘッダ判定なら
        //   その保守は不要で、取りこぼしも起きない。
        val body = csvBody(rows, "氏名")
        for (r in body) {
            val rawName = r.getOrElse(0) { "" }.trim()
            if (rawName.isEmpty()) continue
            val key = nameMatchKey(rawName)
            val gRaw = r.getOrElse(1) { "" }.trim()
            val sRaw = r.getOrElse(2) { "" }.trim()
            val gi = gByK[gRaw]
            val si = skByK[sRaw]
            if (gi == null && gRaw.isNotEmpty()) unknownG[gRaw] = (unknownG[gRaw] ?: 0) + 1
            if (si == null && sRaw.isNotEmpty()) unknownS[sRaw] = (unknownS[sRaw] ?: 0) + 1
            val existing = nameToI[key]
            if (existing != null) {
                val cur = newStaff[existing]
                newStaff[existing] = cur.copy(groupIdx = gi ?: cur.groupIdx, skillIdx = si ?: cur.skillIdx)
                // [3.475.0/論理監査] 旧: 同じ既存職員が2行あると updated=2（「2名を更新」）になっていた。
                if (seenExisting.add(existing)) updated++
            } else {
                val dup = seenNew[key]
                if (dup != null) {
                    val cur = newStaff[dup]
                    newStaff[dup] = cur.copy(groupIdx = gi ?: cur.groupIdx, skillIdx = si ?: cur.skillIdx)
                } else {
                    seenNew[key] = newStaff.size
                    // [3.329.0/外部レビュー H-01/M-01] 新しい職員の空き日は**休の記号解決**で埋める
                    //   （旧: index 0 直書きで、休が先頭でないデータでは全日が勤務になっていた）。
                    //   未知のスキル群は 0（先頭の群）でなく **-1（未所属）**へ（3.70.0 の「(なし)」）。
                    // [3.442.0/H3] さらに**その群が休を担当できるか**まで見る。休を担当可否から外した群
                    //   （UI の担当可否チップで実際にできる操作）へ CSV で職員を足すと、旧実装は全日を
                    //   休で埋めて**行まるごと groupViol(HARD 10000)**になっていた（31日なら1回の取込で
                    //   必須違反31件）。3.418.0 が `Ws1Ops` の3経路で直したのと同じ穴の、CSV 側の取り残し。
                    //   未知の群は `gi ?: 0`＝先頭グループへ落ちるので、そこが休を持たない場合も同様に効く。
                    val gIdx = gi ?: 0
                    val fill = Ws1Ops.fillShift(state.groupShift.getOrNull(gIdx), restShiftIndex(state))
                    newStaff.add(Staff(rawName, gIdx, si ?: -1))
                    extraRows.add(IntArray(t) { fill })
                    added++
                }
            }
        }
        if (updated == 0 && added == 0) return null
        val newSched = Array(sched.size + extraRows.size) { i ->
            if (i < sched.size) sched[i].copyOf() else extraRows[i - sched.size]
        }
        val ns = state.copy(staff = newStaff, schedule = newSched.map { it.toList() })
        return StaffUpsertResult(ns, newSched, updated, added, unknownG, unknownS)
    }
}

/** 希望シフト: 「氏名,日,希望シフト」（1希望=1行）。氏名一致で希望を全置換。 */
object WishesCsvIO {
    fun build(state: MagiState): String {
        val sb = StringBuilder()
        appendCsvRow(sb, listOf("氏名", "日", "希望シフト"))
        val entries = state.wishes.entries.mapNotNull { (key, k) ->
            val p = key.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val j = p.getOrNull(1)?.toIntOrNull()
            if (i == null || j == null) null else Triple(i, j, k)
        }.sortedWith(compareBy({ it.first }, { it.second }))
        for ((i, j, k) in entries) {
            val name = state.staff.getOrNull(i)?.name ?: continue
            val sym = state.shifts.getOrNull(k)?.kigou ?: continue
            appendCsvRow(sb, listOf(name, (j + 1).toString(), sym))
        }
        return sb.toString()
    }

    /** @return Pair(更新後state, 取込件数) または null（解析不能/0件）。 */
    fun parse(text: String, state: MagiState): ComponentImport? {
        val rows = parseCsvGuarded(text) ?: return null
        val nameToI = firstWinsMap(state.staff.size) { nameMatchKey(state.staff[it].name) }
        val symToK = firstWinsMap(state.shifts.size) { state.shifts[it].kigou.trim() }
        val m = LinkedHashMap<String, Int>()
        var n = 0
        // [3.314.0] ヘッダ判定を `build()` が出す実ヘッダ「氏名」の一致へ。旧:「先頭が既知の職員名か」
        //   という間接的な推測で、**未知の職員名で始まるヘッダ無CSVの先頭行を黙って捨てて**いた。
        val body = csvBody(rows, "氏名")
        var bad = 0
        val samples = ArrayList<String>()
        for (r in body) {
            val name = r.getOrElse(0) { "" }.trim()
            val day = r.getOrElse(1) { "" }.trim().toIntOrNull()
            val sym = r.getOrElse(2) { "" }.trim()
            // 完全な空行は書式上のもの＝無視してよい。中身があるのに解釈できない行だけを数える。
            if (name.isEmpty() && sym.isEmpty() && r.getOrElse(1) { "" }.isBlank()) continue
            val i = nameToI[nameMatchKey(name)]
            val k = symToK[sym]
            if (i == null || k == null || day == null || day < 1 || day > state.dayCount) {
                bad++
                // [3.474.0] 収集時に止める（CSV は任意ファイル＝行数は業務規模で有界ではないため、
                //   全件貯めると誤ったファイル選択で拒否行ぶんの String を丸ごと確保してしまう）。
                if (samples.size < ComponentImport.MAX_SAMPLES) samples.add(rowSample(r))
                continue
            }
            // [3.475.0/論理監査] 同じ職員×同じ日が2行あるとき、旧: 後勝ちで上書きしつつ n は2件と数えて
            //   「2件を反映」と言いながら1件しか残らず、食い違う前の行は黙って消えていた。同値の重複は
            //   1件として数え、値が違う衝突は「読めない行」として置換を止める（全置換の方針＝3.329.0）。
            val key = "$i,${day - 1}"
            val prev = m[key]
            if (prev != null) {
                if (prev != k) { bad++; if (samples.size < ComponentImport.MAX_SAMPLES) samples.add("重複(値が違う): " + rowSample(r)) }
                continue
            }
            m[key] = k
            n++
        }
        if (n == 0 && bad == 0) return null
        return ComponentImport(state.copy(wishes = m), n, bad, samples)
    }
}

/** 各制約: 種別タグ付き行（種別,a,b,c,d,e）。取込時は制約一式＋個人レンジを置換。氏名/群/シフトは記号・氏名で照合。 */
object ConstraintsCsvIO {
    fun build(state: MagiState): String {
        val sb = StringBuilder()
        appendCsvRow(sb, listOf("種別", "a", "b", "c", "d", "e"))
        for (c in state.cons1) appendCsvRow(sb, listOf("連勤", c.day1, c.shiftKigou, c.day2))
        for (c in state.cons2) appendCsvRow(sb, listOf("回数下限", c.shiftKigou, c.count))
        for (c in state.cons3) appendCsvRow(sb, listOf("MUST連続") + c.pattern)
        for (c in state.cons3n) appendCsvRow(sb, listOf("禁止連続") + c.pattern)
        for (c in state.cons3m) appendCsvRow(sb, listOf("希望連続") + c.pattern)
        for (c in state.cons3mn) appendCsvRow(sb, listOf("回避連続") + c.pattern)
        for (c in state.cons41) appendCsvRow(sb, listOf("群回数", c.groupKigou, c.shiftKigou, c.l, c.u))
        for (c in state.cons41s) appendCsvRow(sb, listOf("スキル群回数", c.groupKigou, c.shiftKigou, c.l, c.u))
        for (c in state.cons42) appendCsvRow(sb, listOf("群組合せ禁止", c.g1Kigou, c.s1Kigou, c.g2Kigou, c.s2Kigou))
        for (c in state.cons42s) appendCsvRow(sb, listOf("スキル群組合せ禁止", c.g1Kigou, c.s1Kigou, c.g2Kigou, c.s2Kigou))
        for (c in state.cons3w) appendCsvRow(sb, listOf("希望前日禁止", c.wishKigou, c.prevKigou))
        for ((key, r) in state.staffRange) {
            val p = key.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val k = p.getOrNull(1)?.toIntOrNull()
            if (i == null || k == null) continue
            val name = state.staff.getOrNull(i)?.name ?: continue
            val sym = state.shifts.getOrNull(k)?.kigou ?: continue
            appendCsvRow(sb, listOf("個人レンジ", name, sym, r.lo, r.hi))
        }
        return sb.toString()
    }

    /** @return Pair(更新後state, 取込件数) または null（解析不能/0件）。 */
    fun parse(text: String, state: MagiState): ComponentImport? {
        val rows = parseCsvGuarded(text) ?: return null
        val nameToI = firstWinsMap(state.staff.size) { nameMatchKey(state.staff[it].name) }
        fun c(r: List<String>, i: Int) = r.getOrElse(i) { "" }.trim()
        // [3.336.0/外部レビュー P2] 空セルで打ち切るので `MUST連続,A,,B` は ["A"] になり、**B が黙って
        //   消えたまま accepted に数えられた**（3.333.0 で他の族に入れた「評価されない行を受理しない」
        //   の取り残し）。穴が空いた行は書式の誤りとして呼び出し側で弾けるよう、別に判定する。
        fun pat(r: List<String>): List<String> = (1..5).map { c(r, it) }.takeWhile { it.isNotEmpty() }.take(5)
        /** 途中に空セルがあり、その後ろにまだ中身がある＝並びが途切れている（書式の誤り）。 */
        fun patHasGap(r: List<String>): Boolean {
            val cells = (1..5).map { c(r, it) }
            val last = cells.indexOfLast { it.isNotEmpty() }
            return last >= 0 && cells.take(last).any { it.isEmpty() }
        }
        val cons1 = ArrayList<C1Row>(); val cons2 = ArrayList<C2Row>()
        val cons3 = ArrayList<C3Row>(); val cons3n = ArrayList<C3Row>()
        val cons3m = ArrayList<C3Row>(); val cons3mn = ArrayList<C3Row>()
        val cons41 = ArrayList<C41Row>(); val cons41s = ArrayList<C41Row>()
        val cons42 = ArrayList<C42Row>(); val cons42s = ArrayList<C42Row>()
        val cons3w = ArrayList<C3wRow>()
        val ranges = LinkedHashMap<String, Range>()
        var n = 0
        // [3.314.0] ヘッダ判定を `build()` が出す実ヘッダ「種別」の一致へ（旧: 既知キーワード集合との
        //   照合で、キーワードを増やすたびに取込側も直す必要があった）。
        val body = csvBody(rows, "種別")
        var bad = 0
        val samples = ArrayList<String>()
        fun reject(r: List<String>) {
            bad++
            // [3.474.0] 収集時に止める（WishesCsvIO.parse と同じ理由）。
            if (samples.size < ComponentImport.MAX_SAMPLES) samples.add(rowSample(r))
        }
        for (r in body) {
            if (r.all { it.isBlank() }) continue   // 書式上の空行は無視
            when (c(r, 0)) {
                "連勤" -> { cons1.add(C1Row(c(r, 1), c(r, 2), c(r, 3))); n++ }
                "回数下限" -> { cons2.add(C2Row(c(r, 1), c(r, 2))); n++ }
                "MUST連続" -> { val p = pat(r); if (p.isNotEmpty() && !patHasGap(r)) { cons3.add(C3Row(p)); n++ } else reject(r) }
                "禁止連続" -> { val p = pat(r); if (p.isNotEmpty() && !patHasGap(r)) { cons3n.add(C3Row(p)); n++ } else reject(r) }
                "希望連続" -> { val p = pat(r); if (p.isNotEmpty() && !patHasGap(r)) { cons3m.add(C3Row(p)); n++ } else reject(r) }
                "回避連続" -> { val p = pat(r); if (p.isNotEmpty() && !patHasGap(r)) { cons3mn.add(C3Row(p)); n++ } else reject(r) }
                "群回数" -> { cons41.add(C41Row(c(r, 1), c(r, 2), c(r, 3), c(r, 4))); n++ }
                "スキル群回数" -> { cons41s.add(C41Row(c(r, 1), c(r, 2), c(r, 3), c(r, 4))); n++ }
                "群組合せ禁止" -> { cons42.add(C42Row(c(r, 1), c(r, 3), c(r, 2), c(r, 4))); n++ }
                "スキル群組合せ禁止" -> { cons42s.add(C42Row(c(r, 1), c(r, 3), c(r, 2), c(r, 4))); n++ }
                "希望前日禁止" -> { cons3w.add(C3wRow(c(r, 1), c(r, 2))); n++ }
                "個人レンジ" -> {
                    val i = nameToI[nameMatchKey(c(r, 1))]
                    val sym = c(r, 2)
                    val k = state.shifts.indexOfFirst { it.kigou.trim() == sym }
                    // [3.329.0/外部レビュー H-02] 氏名・記号が今のデータに無い行は黙って捨てない。
                    //   捨てたまま置換すると、その職員の個人レンジが**消える**。
                    // [3.509.3] 下限/上限は空欄か 0 以上の整数、両方あれば下限≤上限。Problem は負数・非数値を未設定として
                    //   捨てるので、ここで受理すると「評価されない行で置換」になる（3.333.0 と同じ穴）。
                    val loV = c(r, 3); val hiV = c(r, 4)
                    val loN = loV.toIntOrNull(); val hiN = hiV.toIntOrNull()
                    val numOk = (loV.isEmpty() || (loN != null && loN >= 0)) && (hiV.isEmpty() || (hiN != null && hiN >= 0)) &&
                        (loN == null || hiN == null || loN <= hiN)
                    if (i != null && k >= 0 && numOk) {
                        // [3.475.0/論理監査] 同じ職員×シフトの重複行（希望CSVと同じ扱い＝同値は1件、衝突は拒否）。
                        val key = "$i,$k"; val rng = Range(c(r, 3), c(r, 4)); val prev = ranges[key]
                        if (prev == null) { ranges[key] = rng; n++ } else if (prev != rng) reject(r)
                    } else reject(r)
                }
                // 未知の種別も黙って捨てない（種別の綴り違いで制約一式が消えるのを防ぐ）。
                else -> reject(r)
            }
        }
        if (n == 0 && bad == 0) return null
        val candidate = state.copy(
            cons1 = cons1, cons2 = cons2, cons3 = cons3, cons3n = cons3n,
            cons3m = cons3m, cons3mn = cons3mn, cons41 = cons41, cons41s = cons41s,
            cons42 = cons42, cons42s = cons42s, cons3w = cons3w, staffRange = ranges,
        )
        // [3.333.0/外部レビュー Critical] 種別が既知なだけの行を**無条件に受理**していた。
        //   例えば `連勤,,,` は C1Row("","","") として n に数えられ、`Problem` は
        //   `d1>0 && si>=0 && d2>0` で捨てる＝**評価されない行で既存の有効な制約を全置換**できた
        //   （実質「制約なし」で最適化される）。3.329.0 の中止条件は未知の氏名・記号しか見ておらず、
        //   構造的に空/不正な行を通していた。
        //
        //   判定は `Problem` を単一ソースにする（各族の条件をここへ複製すると必ずドリフトする）。
        //   この取込は制約族を**すべて置換**するので、候補 state の未解決行は必ずこのCSV由来。
        //   連続パターン(cons3系)の未解決記号は別のリスト(`c3UnknownShift`)に入るので両方見る。
        val unresolved = runCatching {
            val pc = Problem(candidate)
            pc.unresolvedRows + pc.c3UnknownShift
        }.getOrDefault(emptyList())
        if (unresolved.isNotEmpty()) {
            // [3.474.0, /code-review] 評価されない行は when 分岐で n に数えた行そのもの（Problem は
            //   捨てた行につき1件記録する）＝同じ行を「取込可」と「読めない」の両方に数えていた
            //   （旧: `連勤,,,` の1行で 取込可1・読めない1）。n から差し引き「読めない」側だけにする。
            n = (n - unresolved.size).coerceAtLeast(0)
            bad += unresolved.size
            for (u in unresolved) {
                if (samples.size >= ComponentImport.MAX_SAMPLES) break
                samples.add("${u.first}「${u.second}」".take(60))
            }
        }
        return ComponentImport(candidate, n, bad, samples)
    }
}

/**
 * [3.547.0/ユーザー指示] シフト種別の色のみを外部へ出力・取込む機能。「記号,色」の2列。
 * 違反色（`shiftColors["__vio__"]`等の予約キー）は対象外＝シフト記号のカスタム色だけを扱う。
 * 出力は`state.shiftColors`に実際に値がある記号だけ（既定色のまま=未カスタムの記号は出さない）。
 * 取込はupsert（グリルで確認済み: 3.547.0「載っている分だけ上書き」）: CSVに載っている記号だけ
 * shiftColors を更新し、載っていない記号の既存カスタム色は変更しない。他のコンポーネント別CSV
 * （希望/制約）と違い全置換ではない＝表示専用データで欠落による業務影響が無いため。
 */
class ShiftColorsUpsertResult(
    val state: MagiState,
    val updated: Int,
    /** 現在のシフト一覧に無い記号（記号→行数）。カスタム色は追加しない（新規シフト作成はこの機能の範囲外）。 */
    val unknownKigou: Map<String, Int> = emptyMap(),
    /** "#rrggbb" 形式でないセル（記号→行数）。反映しない。 */
    val invalidHex: Map<String, Int> = emptyMap(),
)

object ShiftColorsCsvIO {
    private val HEX_RE = Regex("^#[0-9a-fA-F]{6}$")

    fun build(state: MagiState): String {
        val sb = StringBuilder()
        appendCsvRow(sb, listOf("記号", "色"))
        for (sh in state.shifts) {
            val hex = state.shiftColors[sh.kigou] ?: continue
            appendCsvRow(sb, listOf(sh.kigou, hex))
        }
        return sb.toString()
    }

    /** @return null は解析不能（空/引用符未閉）のみ。0件反映でも本体を返す（呼出側が updated==0 で判定）。 */
    fun parseUpsert(text: String, state: MagiState): ShiftColorsUpsertResult? {
        val rows = parseCsvGuarded(text) ?: return null
        val body = csvBody(rows, "記号")
        val known = state.shifts.map { it.kigou }.toHashSet()
        val m = state.shiftColors.toMutableMap()
        var updated = 0
        val unknownKigou = LinkedHashMap<String, Int>()
        val invalidHex = LinkedHashMap<String, Int>()
        for (r in body) {
            val kigou = r.getOrElse(0) { "" }.trim()
            val hex = r.getOrElse(1) { "" }.trim()
            if (kigou.isEmpty()) continue
            if (kigou !in known) { unknownKigou[kigou] = (unknownKigou[kigou] ?: 0) + 1; continue }
            if (!HEX_RE.matches(hex)) { invalidHex[kigou] = (invalidHex[kigou] ?: 0) + 1; continue }
            m[kigou] = hex
            updated++
        }
        return ShiftColorsUpsertResult(state.copy(shiftColors = m), updated, unknownKigou, invalidHex)
    }
}
