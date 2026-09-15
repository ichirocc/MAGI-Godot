package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [/code-review, need2単独定義セル見落とし修正] `findCovOFix` の検証。
 * 旧実装は `p.need1[k][j]` のみで過剰スキャンを行い、need1未設定・need2のみで上限が定義された
 * シフトの過剰配置を一切検出できなかった（3.173.0のCoverageDiagnosis修正・3.309.0の
 * V6LateOperators.isBalanceable修正と同根、covOCell/covUCellをsource of truthとして統一）。
 */
class V6SearchOperatorsTest {
    private fun state(): MagiState = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-01",
        // shift 0="休"(needなし), shift 1="X"(need1未設定・need2=1のみで上限定義)
        shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", "1")),
        groups = listOf(Group("G", "G")),
        staff = listOf(Staff("s0", 0), Staff("s1", 0)),
        use2Patterns = true,
        groupShift = listOf(listOf(1, 1)),
        groupShiftApt = listOf(listOf("", "")),
        // 両名ともXへ配置済み＝need2=1に対し2人在勤で過剰配置(covO=1)。
        schedule = listOf(listOf(1), listOf(1)),
        wishes = emptyMap(),
        staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
        cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test
    fun findCovOFixDetectsNeed2OnlyOverCoverage() {
        val p = Problem(state())
        val eval = DeltaEvaluator(p)
        assertEquals("前提: X(day0)が2人で過剰配置(need2=1)", 1, p.covOCell(1, 0, eval.countOnDay(1, 0)))

        val fix = findCovOFix(p, eval, Random(1))
        assertNotNull("need2のみで定義された過剰配置も検出し修正候補を返すこと", fix)
        val (i, j, newK) = fix!!
        assertEquals(0, j)
        assertEquals(1, eval.at(i, j)) // 元は在勤中のX(=1)を退避させる手であること
        assertEquals(0, newK) // 唯一の移動先候補=休(index0)
    }

    /**
     * 過剰配置が無ければ null（誤検知しないことの対照）。
     */
    @Test
    fun findCovOFixReturnsNullWhenNoOverCoverage() {
        val st = state().copy(schedule = listOf(listOf(1), listOf(0))) // 1人だけXへ＝need2=1を満たすのみ
        val p = Problem(st)
        val eval = DeltaEvaluator(p)
        assertEquals(0, p.covOCell(1, 0, eval.countOnDay(1, 0)))
        assertNull(findCovOFix(p, eval, Random(1)))
    }
}

/** ターゲット修正(find*Fix)と厳密ピン判定の振る舞いを固定する。 */
class TargetedFixTest {
    private val REST = 0; private val A = 1; private val B = 2

    private fun state(
        rows: List<List<Int>>, groups: List<Int> = rows.map { 0 }, skills: List<Int> = rows.map { 0 },
        cons2: List<com.magi.app.model.C2Row> = emptyList(), cons3: List<com.magi.app.model.C3Row> = emptyList(),
        cons41: List<com.magi.app.model.C41Row> = emptyList(), cons41s: List<com.magi.app.model.C41Row> = emptyList(),
        staffRange: Map<String, com.magi.app.model.Range> = emptyMap(), apt: List<String> = listOf("", "", ""),
    ): MagiState = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-04",
        shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "", ""), Shift("B", "B", "", "")),
        groups = listOf(Group("G", "G"), Group("H", "H")),
        staff = rows.indices.map { Staff("s$it", groups[it], skills[it]) },
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1), listOf(1, 1, 1)),
        groupShiftApt = listOf(apt, listOf("", "", "")),
        schedule = rows, wishes = emptyMap(), staffRange = staffRange,
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = cons2, cons3 = cons3, cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = cons41, cons42 = emptyList(),
        skillGroups = listOf(Group("S", "S"), Group("T", "T")), cons41s = cons41s,
    )

    private fun eval(st: MagiState): Pair<Problem, DeltaEvaluator> {
        val p = Problem(st)
        val ev = DeltaEvaluator(p); ev.reset(Array(st.staffCount) { st.schedule[it].toIntArray() })
        return p to ev
    }

    @Test fun c2FixAddsTheShiftToAStaffBelowTheCount() {
        val st = state(listOf(listOf(A, REST, REST, REST), listOf(A, A, REST, REST)), cons2 = listOf(com.magi.app.model.C2Row("A", "2")))
        val (p, ev) = eval(st)
        val fix = findC2Fix(p, ev, Random(1))!!
        assertEquals(0, fix[0]); assertEquals(A, fix[2]); assertEquals(REST, ev.at(fix[0], fix[1]))
        val (p2, ev2) = eval(state(listOf(listOf(A, A, REST, REST)), cons2 = listOf(com.magi.app.model.C2Row("A", "2"))))
        assertNull(findC2Fix(p2, ev2, Random(1)))
    }

    @Test fun rangeLowAndHighFixesMoveTowardTheRange() {
        val lowSt = state(listOf(listOf(A, REST, REST, REST)), staffRange = mapOf("0,1" to com.magi.app.model.Range("2", "")))
        val (p, ev) = eval(lowSt)
        val low = findRangeLowFix(p, ev, Random(3))!!
        assertEquals(0, low[0]); assertEquals(A, low[2]); assertEquals(REST, ev.at(0, low[1]))
        assertNull(findRangeHighFix(p, ev, Random(3)))
        val highSt = state(listOf(listOf(A, A, A, REST)), staffRange = mapOf("0,1" to com.magi.app.model.Range("", "1")))
        val (p2, ev2) = eval(highSt)
        val high = findRangeHighFix(p2, ev2, Random(3))!!
        assertEquals(0, high[0]); assertEquals(A, ev2.at(0, high[1])); assertTrue(high[2] != A)
        assertNull(findRangeLowFix(p2, ev2, Random(3)))
    }

    @Test fun groupRangeFixHandlesOverAndUnder() {
        // 群 G（s0,s1）が毎日 A×2 で上限 1 超過 → G の 1 人を A 以外へ
        val over = state(listOf(listOf(A, A, A, A), listOf(A, A, A, A), listOf(REST, REST, REST, REST)),
            groups = listOf(0, 0, 1), cons41 = listOf(com.magi.app.model.C41Row("G", "A", "0", "1")))
        val (p, ev) = eval(over)
        val fix = findC41Fix(p, ev, Random(5))!!
        assertTrue(fix[0] in 0..1); assertTrue(fix[2] != A)
        // 群 H（s2）は A が 0 で下限 1 未満 → s2 を A へ
        val under = state(listOf(listOf(A, A, A, A), listOf(A, A, A, A), listOf(REST, REST, REST, REST)),
            groups = listOf(0, 0, 1), cons41 = listOf(com.magi.app.model.C41Row("H", "A", "1", "3")))
        val (p2, ev2) = eval(under)
        val fix2 = findC41Fix(p2, ev2, Random(5))!!
        assertEquals(2, fix2[0]); assertEquals(A, fix2[2])
        // スキル群版は所属配列が ssk になるだけ
        val skill = state(listOf(listOf(A, A, A, A), listOf(A, A, A, A), listOf(REST, REST, REST, REST)),
            skills = listOf(1, 1, 0), cons41s = listOf(com.magi.app.model.C41Row("T", "A", "0", "1")))
        val (p3, ev3) = eval(skill)
        val fix3 = findC41sFix(p3, ev3, Random(5))!!
        assertTrue(fix3[0] in 0..1); assertTrue(fix3[2] != A)
        assertNull(findC41Fix(p3, ev3, Random(5)))
    }

    @Test fun c3WantFixCompletesAPatternMissingOneCell() {
        val st = state(listOf(listOf(A, REST, REST, REST)), cons3 = listOf(com.magi.app.model.C3Row(listOf("A", "B"))))
        val (p, ev) = eval(st)
        assertArrayEquals(intArrayOf(0, 1, B), findC3WantFix(p, ev, Random(1)))
    }

    @Test fun aptFixMovesOneDayFromOverToUnder() {
        val st = state(listOf(listOf(A, A, A, REST)), apt = listOf("3", "1", ""))
        val (p, ev) = eval(st)
        val fix = findAptFix(p, ev, Random(2))!!
        assertEquals(0, fix[0]); assertEquals(A, ev.at(0, fix[1])); assertEquals(REST, fix[2])
    }

    @Test fun exactPinRegressionAndOffendersAgree() {
        val st = state(listOf(listOf(A, A, REST, REST)), staffRange = mapOf("0,1" to com.magi.app.model.Range("2", "2")))
        val p = Problem(st)
        val before = arrayOf(intArrayOf(A, A, REST, REST))
        val worse = arrayOf(intArrayOf(A, A, A, REST))
        val same = arrayOf(intArrayOf(A, REST, A, REST))
        assertTrue(exactPinRegression(p, before, worse))
        assertEquals(listOf(listOf(0, A)), exactPinOffenders(p, before, worse).map { it.toList() })
        assertFalse(exactPinRegression(p, before, same))
        assertTrue(exactPinOffenders(p, before, same).isEmpty())
    }
}
