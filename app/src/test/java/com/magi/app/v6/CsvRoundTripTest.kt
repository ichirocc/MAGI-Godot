package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.model.StateParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 4 実データで `parse(build(state))` が勤務表・職員・希望・制約・個人レンジを完全再現することを固定する
 *  （書出しと取込の対応表が片側だけ変わるドリフトを CI で止める）。 */
class CsvRoundTripTest {

    private val fixtures = listOf("golden_state", "sample_state_v6", "sept2026_state", "blocked_covu_state")

    private fun load(name: String): MagiState {
        val json = javaClass.getResourceAsStream("/$name.json")?.bufferedReader()?.readText()
        assertNotNull("$name.json がテストリソースにありません", json)
        return StateParser.parse(json!!)!!
    }

    private fun blankBase(st: MagiState) = Array(st.staffCount) { IntArray(st.dayCount) { -1 } }

    private fun assertScheduleRoundTrip(st: MagiState, label: String) {
        val p = Problem(st)
        val sched = normalizeSchedule(Array(st.schedule.size) { st.schedule[it].toIntArray() }, p)
        val csv = ScheduleCsvBridge.build(st, sched)
        val r = ScheduleCsvBridge.parse(csv, st, blankBase(st))
        assertEquals("$label: 全員一致", st.staffCount, r.matched)
        assertEquals("$label: 未知記号なし", 0, r.unknownCells)
        assertFalse("$label: 引用符は閉じている", r.unclosedQuote)
        for (i in 0 until p.S) assertArrayEquals("$label: 行 $i", sched[i], r.schedule[i])
    }

    private fun assertStaffRoundTrip(st: MagiState, label: String) {
        val r = StaffCsvIO.parse(StaffCsvIO.build(st), st)
        assertNotNull("$label: 職員CSV", r)
        assertEquals("$label: 一致数", st.staffCount, r!!.second)
        assertEquals("$label: 職員", st.staff, r.first.staff)
    }

    private fun assertWishesRoundTrip(st: MagiState, label: String) {
        val expected = st.wishes.filter { (key, k) ->
            val p = key.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val j = p.getOrNull(1)?.toIntOrNull()
            i != null && j != null && i in st.staff.indices && j in 0 until st.dayCount && k in st.shifts.indices
        }
        val r = WishesCsvIO.parse(WishesCsvIO.build(st), st)
        assertNotNull("$label: 希望CSV", r)
        assertEquals("$label: 拒否なし", 0, r!!.rejected)
        assertEquals("$label: 取込件数", expected.size, r.accepted)
        assertEquals("$label: 希望", expected, r.state.wishes)
    }

    private fun trimmed(rows: List<C3Row>) = rows.map { C3Row(it.pattern.takeWhile { c -> c.isNotBlank() }) }

    private fun assertConstraintsRoundTrip(st: MagiState, label: String) {
        val expectedRanges = st.staffRange.filter { (key, _) ->
            val p = key.split(","); val i = p.getOrNull(0)?.toIntOrNull(); val k = p.getOrNull(1)?.toIntOrNull()
            i != null && k != null && i in st.staff.indices && k in st.shifts.indices
        }
        val r = ConstraintsCsvIO.parse(ConstraintsCsvIO.build(st), st)
        assertNotNull("$label: 制約CSV", r)
        // 元データ自体が Problem で解決できない行を持つなら、その数だけ「読めない」に数えるのが仕様。
        val unresolvedInSource = Problem(st).let { it.unresolvedRows.size + it.c3UnknownShift.size }
        assertEquals("$label: 拒否", unresolvedInSource, r!!.rejected)
        val s = r.state
        assertEquals("$label: cons1", st.cons1, s.cons1)
        assertEquals("$label: cons2", st.cons2, s.cons2)
        // 連続パターンは JSON では 5 セルに空文字で右詰めされている。CSV は最初の空セルで打ち切る
        //   （`Problem.resolveC3` と同じ切り方＝評価上は同値）ので、その形へ揃えて比べる。
        assertEquals("$label: cons3", trimmed(st.cons3), s.cons3)
        assertEquals("$label: cons3n", trimmed(st.cons3n), s.cons3n)
        assertEquals("$label: cons3m", trimmed(st.cons3m), s.cons3m)
        assertEquals("$label: cons3mn", trimmed(st.cons3mn), s.cons3mn)
        assertEquals("$label: cons41", st.cons41, s.cons41)
        assertEquals("$label: cons41s", st.cons41s, s.cons41s)
        assertEquals("$label: cons42", st.cons42, s.cons42)
        assertEquals("$label: cons42s", st.cons42s, s.cons42s)
        assertEquals("$label: cons3w", st.cons3w, s.cons3w)
        assertEquals("$label: staffRange", expectedRanges, s.staffRange)
        val rows = st.cons1.size + st.cons2.size + st.cons3.size + st.cons3n.size + st.cons3m.size + st.cons3mn.size +
            st.cons41.size + st.cons41s.size + st.cons42.size + st.cons42s.size + st.cons3w.size + expectedRanges.size
        assertEquals("$label: 取込件数", rows - unresolvedInSource, r.accepted)
    }

    @Test fun scheduleCsvRoundTripsOnFixtures() { for (f in fixtures) assertScheduleRoundTrip(load(f), f) }
    @Test fun staffCsvRoundTripsOnFixtures() { for (f in fixtures) assertStaffRoundTrip(load(f), f) }
    @Test fun wishesCsvRoundTripsOnFixtures() { for (f in fixtures) assertWishesRoundTrip(load(f), f) }
    @Test fun constraintsCsvRoundTripsOnFixtures() { for (f in fixtures) assertConstraintsRoundTrip(load(f), f) }

    /** カンマ・引用符・改行・全角空白を含む氏名/記号でも、書出し→取込で同じ盤面に戻る。 */
    @Test fun escapedCellsSurviveRoundTrip() {
        val base = load("golden_state")
        val shifts = base.shifts.toMutableList()
        shifts[3] = shifts[3].copy(kigou = "A,4")
        shifts[4] = shifts[4].copy(kigou = "A\"ｱ")
        val staff = base.staff.toMutableList()
        staff[0] = staff[0].copy(name = "山田, \"太郎\"")
        staff[1] = staff[1].copy(name = "佐藤　花子")
        staff[2] = staff[2].copy(name = "改行\n入り")
        val groups = base.groups.toMutableList()
        groups[0] = groups[0].copy(kigou = "V,1")
        val cons3n = listOf(C3Row(listOf("A,4", "A\"ｱ", "休")))
        val st = base.copy(
            shifts = shifts, staff = staff, groups = groups, cons3n = cons3n,
            staffRange = mapOf("0,3" to Range("1", "2"), "1,4" to Range("", "3")),
            wishes = mapOf("0,0" to 3, "2,5" to 4),
        )
        assertScheduleRoundTrip(st, "escaped")
        assertStaffRoundTrip(st, "escaped")
        assertWishesRoundTrip(st, "escaped")
        assertConstraintsRoundTrip(st, "escaped")
    }
}

/** 空欄だけの個人レンジ（未設定）は取込で受理し、Sanity は案内しない。数値でない値は 3.509.3 から取込で拒否する（`StaffRangeCsvValidationTest`）。 */
class NonNumericStaffRangeImportTest {
    private fun load(): MagiState {
        val json = javaClass.getResourceAsStream("/golden_state.json")!!.bufferedReader().readText()
        return StateParser.parse(json)!!
    }

    @Test fun blankRangeIsAcceptedAndNotReported() {
        val st = load().copy(staffRange = mapOf("0,3" to Range("1", "2")))
        val sym = st.shifts[3].kigou
        val csv = ConstraintsCsvIO.build(st) + "個人レンジ,${st.staff[2].name},$sym,,\n"
        val r = ConstraintsCsvIO.parse(csv, st)!!
        assertEquals(0, r.rejected)
        assertEquals(Range("", ""), r.state.staffRange["2,3"])
        val issues = V6SanityPort.buildGuidance(r.state)
        assertFalse(issues.any { it.where.contains("個人の回数「${st.staff[2].name} $sym」") })
    }
}

/** 勤務表CSVのヘッダ判定は構造で決める（先頭が未知の職員名なだけの行をヘッダ扱いしない）。 */
class ScheduleCsvHeaderTest {
    private fun load(): MagiState {
        val json = javaClass.getResourceAsStream("/golden_state.json")!!.bufferedReader().readText()
        return StateParser.parse(json)!!
    }

    @Test fun unknownFirstStaffRowIsNotTreatedAsHeader() {
        val st = load()
        val p = Problem(st)
        val base = Array(st.staffCount) { IntArray(st.dayCount) { -1 } }
        val sym = st.shifts[1].kigou
        val known = st.staff[0].name
        val row = { name: String -> name + "," + List(st.dayCount) { sym }.joinToString(",") }
        // 先頭が誤記の職員名 → その行はデータ行（一致なし）として扱われ、2 行目の既知職員は取り込まれる
        val r = ScheduleCsvBridge.parse(row("誰か") + "\n" + row(known) + "\n", st, base)
        assertEquals(1, r.matched); assertEquals(1, r.schedule[0][0])
        // build() のヘッダと、日付だけの行はヘッダとして飛ばす
        val header = "スタッフ \\ 日付," + (1..st.dayCount).joinToString(",")
        assertEquals(1, ScheduleCsvBridge.parse(header + "\n" + row(known) + "\n", st, base).matched)
        val dates = "," + (1..st.dayCount).joinToString(",") { "2026/06/$it" }
        assertEquals(1, ScheduleCsvBridge.parse(dates + "\n" + row(known) + "\n", st, base).matched)
        assertEquals(p.T, r.schedule[0].size)
    }
}

/** 制約CSVの個人レンジ行は「空欄か 0 以上の整数、下限≤上限」だけを受理する（Problem が捨てる値で置換しない）。 */
class StaffRangeCsvValidationTest {
    private fun load(): MagiState {
        val json = javaClass.getResourceAsStream("/golden_state.json")!!.bufferedReader().readText()
        return StateParser.parse(json)!!
    }

    @Test fun invalidRangeRowsAreRejected() {
        val st = load().copy(staffRange = emptyMap())
        val name = st.staff[0].name; val sym = st.shifts[3].kigou
        fun rejected(cells: String) = ConstraintsCsvIO.parse("個人レンジ,$name,$sym,$cells", st)!!.rejected
        assertEquals(1, rejected("abc,"))     // 数値でない
        assertEquals(1, rejected("-1,"))      // 負数
        assertEquals(1, rejected(",-2"))
        assertEquals(1, rejected("5,2"))      // 下限 > 上限
        assertEquals(0, rejected("2,5"))
        assertEquals(0, rejected(",3"))
        assertEquals(0, rejected("0,0"))
        assertEquals(0, rejected("１,２"))    // 全角数字は数値として読める
    }
}

