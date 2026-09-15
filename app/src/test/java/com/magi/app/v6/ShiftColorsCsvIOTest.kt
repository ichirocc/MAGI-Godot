package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** シフト種別の色のみを出力・取込む `ShiftColorsCsvIO`（3.547.0）。違反色は対象外、
 *  取込はupsert（CSVに載っている記号だけ更新、載っていない記号の既存カスタム色は現状維持）。 */
class ShiftColorsCsvIOTest {

    private val fixtures = listOf("golden_state", "sample_state_v6", "sept2026_state", "blocked_covu_state")

    private fun load(name: String) =
        StateParser.parse(javaClass.getResourceAsStream("/$name.json")!!.bufferedReader().readText())!!

    @Test fun roundTripsOnFixtures() {
        for (f in fixtures) {
            val st = load(f)
            val expected = st.shiftColors.filterKeys { k -> st.shifts.any { it.kigou == k } }
            val r = ShiftColorsCsvIO.parseUpsert(ShiftColorsCsvIO.build(st), st)
            assertNotNull("$f: シフト色CSV", r)
            assertEquals("$f: 反映件数", expected.size, r!!.updated)
            assertEquals("$f: 未知記号なし", emptyMap<String, Int>(), r.unknownKigou)
            assertEquals("$f: 不正な色なし", emptyMap<String, Int>(), r.invalidHex)
            assertEquals("$f: shiftColors", expected, r.state.shiftColors)
        }
    }

    /** 予約キー（違反色）は掲載されない＝シフト種別の色だけが出力される。 */
    @Test fun violationColorsAreExcludedFromExport() {
        val st = load("golden_state").let { it.copy(shiftColors = it.shiftColors + ("__vio__" to "#B71C1C")) }
        val csv = ShiftColorsCsvIO.build(st)
        assertTrue("__vio__ は出力されない", !csv.contains("__vio__"))
    }

    /** CSVに載っている記号だけ更新し、載っていない記号の既存カスタム色は変えない（ユーザー決定・3.547.0）。 */
    @Test fun upsertLeavesUnlistedKigouUntouched() {
        val st = load("golden_state")
        val kigou0 = st.shifts[0].kigou
        val kigou1 = st.shifts[1].kigou
        val before1 = st.shiftColors[kigou1]
        val csv = "記号,色\n$kigou0,#123456\n"
        val r = ShiftColorsCsvIO.parseUpsert(csv, st)!!
        assertEquals("記載した記号は更新", 1, r.updated)
        assertEquals("#123456", r.state.shiftColors[kigou0])
        assertEquals("未記載の記号は現状維持", before1, r.state.shiftColors[kigou1])
    }

    /** 現在のシフト一覧に無い記号は反映せず件数で返す（新規シフト作成はこの機能の範囲外）。 */
    @Test fun unknownKigouIsSkippedAndCounted() {
        val st = load("golden_state")
        val r = ShiftColorsCsvIO.parseUpsert("記号,色\n存在しない記号,#123456\n", st)!!
        assertEquals(0, r.updated)
        assertEquals(1, r.unknownKigou["存在しない記号"])
        assertTrue("shiftColorsは変化しない", !r.state.shiftColors.containsKey("存在しない記号"))
    }

    /** "#rrggbb" 形式でないセルは反映せず件数で返す。 */
    @Test fun invalidHexIsSkippedAndCounted() {
        val st = load("golden_state")
        val kigou0 = st.shifts[0].kigou
        val before = st.shiftColors[kigou0]
        val r = ShiftColorsCsvIO.parseUpsert("記号,色\n$kigou0,red\n", st)!!
        assertEquals(0, r.updated)
        assertEquals(1, r.invalidHex[kigou0])
        assertEquals("不正な行は既存色を変えない", before, r.state.shiftColors[kigou0])
    }

    @Test fun blankOrUnclosedQuoteCsvIsRejected() {
        val st = load("golden_state")
        assertNull(ShiftColorsCsvIO.parseUpsert("", st))
        assertNull(ShiftColorsCsvIO.parseUpsert("記号,色\n\"未閉,#123456\n", st))
    }
}
