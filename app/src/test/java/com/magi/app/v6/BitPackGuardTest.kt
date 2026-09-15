package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.469.0/周辺再検証] 外部レポートの「ビット幅の境界」指摘（`C3nBitScan` の `starts>=64`/`width>=64`）は
 * 実コードに当てると**既にガード済み**で反証されたが、同じ形を周辺へ当て直すと
 * 「3.305.0 で作った窓口を1箇所だけ迂回している」が実在した。ここはその回帰。
 *
 * 巡回交換の署名パック幅（`cycleCap<=8`）は**本番の唯一の呼出が既定 maxCycle=5 で到達しない**防御ガードで、
 * 落ちるテストを構成できていない（3.369.0 の `C1TemporalDp` RELOC_BITS と同じ立ち位置）。
 * 到達不能なものを「テストが守っている」とは書かないため、ここでは検証していない。
 */
class BitPackGuardTest {

    /** T 日・禁止連続「X,X」だけの最小盤面。職員2名（i=0 を対象・i=1 は未使用）。 */
    private fun state(days: Int) = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-20",
        shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", ""), Shift("Y", "Y", "", "")),
        groups = listOf(Group("G0", "G0")),
        staff = listOf(Staff("A", 0), Staff("B", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)),
        groupShiftApt = listOf(listOf("", "", "")),
        schedule = List(2) { List(days) { 0 } },
        wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
        cons3n = listOf(C3Row(listOf("X", "X"))),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    /**
     * 65日以上（ビット経路が使えない期間）でも、成立している禁止パターンの日を候補として返すこと。
     *
     * 旧実装は `C3nBitScan` を直叩きし、`usable(p)`（T<=64）が偽なら **候補を作らず j±1 へ落として**いた。
     * `C3nRowScan` は 3.305.0 で「呼び出し側がビット経路かどうかを分岐しない」ために作られた窓口で、
     * 65日以上は同じ意味論のスカラー走査へ退避する。**この関数だけがその窓口を迂回していた**。
     *
     * ここでは 3連「Y,X,X」を day 5..7 に置き、j=7 の崩し候補を見る。パターンがまたぐのは 5,6,7 なので
     * 窓口経由なら {5,6}（当日 7 は除く）が返る。旧実装は T>64 で {6,8}＝**パターン外の 8 を含み
     * パターンの先頭 5 を落とす**（3.303.0 が広い範囲を見に行くようにした目的そのものを失う）。
     */
    @Test fun breakableDaysUsesTheScalarPathBeyondSixtyFourDays() {
        val st = state(70).copy(cons3n = listOf(C3Row(listOf("Y", "X", "X"))))
        val p = Problem(st)
        assertEquals("この盤面はビット経路の対象外（窓口のスカラー退避を通る）", 70, p.T)
        assertFalse(C3nBitScan.usable(p))
        val sched = Array(2) { IntArray(70) { 0 } }
        sched[0][5] = 2; sched[0][6] = 1; sched[0][7] = 1   // Y,X,X が成立
        val prev = PolishGate.wideC3nBreakDays
        PolishGate.wideC3nBreakDays = true
        try {
            val days = breakableDaysFor(p, sched, 0, 7, 1).toSortedSet()
            assertEquals("パターンがまたぐ全日から当日を除いたもの", setOf(5, 6), days)
        } finally { PolishGate.wideC3nBreakDays = prev }
    }

    /** 64日以内は従来どおりビット経路。窓口化で結果を変えていないこと。 */
    @Test fun breakableDaysKeepsTheBitPathWithinSixtyFourDays() {
        val st = state(10).copy(cons3n = listOf(C3Row(listOf("Y", "X", "X"))))
        val p = Problem(st)
        assertTrue(C3nBitScan.usable(p))
        val sched = Array(2) { IntArray(10) { 0 } }
        sched[0][5] = 2; sched[0][6] = 1; sched[0][7] = 1
        val prev = PolishGate.wideC3nBreakDays
        PolishGate.wideC3nBreakDays = true
        try {
            assertEquals(setOf(5, 6), breakableDaysFor(p, sched, 0, 7, 1).toSortedSet())
        } finally { PolishGate.wideC3nBreakDays = prev }
    }

    /** 範囲外の日は窓口が空を返す（呼び出し側が `1L shl j` を組み立てない）。 */
    @Test fun breakableDaysReturnsNothingForAnOutOfRangeDay() {
        val st = state(10)
        val p = Problem(st)
        val sched = Array(2) { IntArray(10) { 0 } }
        val prev = PolishGate.wideC3nBreakDays
        PolishGate.wideC3nBreakDays = true
        try {
            assertEquals(0, breakableDaysFor(p, sched, 0, 64, 1).size)
            assertEquals(0, breakableDaysFor(p, sched, 0, -1, 1).size)
        } finally { PolishGate.wideC3nBreakDays = prev }
    }
}
