package com.magi.app.v6

import com.magi.app.model.C3wRow
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.542.0] 希望の前日に禁止（cons3w / 違反キー c3w, HARD 9000）。
 * 意味: 希望(ws3)で固定された X の前日セルが Y なら違反。初日・希望でない X・実現不可能な希望は対象外。
 * チェッカー／Evaluator／DeltaEvaluator の3者一致、枝刈り（makesForbiddenRun）、設定ミス診断、JSON/CSV 往復を固定する。
 */
class C3wConstraintTest {
    private val rest = 0; private val a = 1; private val b = 2

    private fun state(
        schedule: List<List<Int>>, wishes: Map<String, Int>, cons3w: List<C3wRow>,
        groupShift: List<List<Int>> = listOf(listOf(1, 1, 1)),
    ) = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-05",
        shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "", ""), Shift("B", "B", "", "")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("s0", 0), Staff("s1", 0)),
        use2Patterns = false, groupShift = groupShift, groupShiftApt = emptyList(),
        schedule = schedule, wishes = wishes, staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(),
        cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(), cons3w = cons3w,
    )

    private val row1 = listOf(rest, rest, rest, rest, rest)

    @Test fun bannedCellCountsOnceInCheckerEvaluatorAndDelta() {
        // s0: 希望 (0,2)=A、前日 (0,1)=B → c3w=1（違反箇所は前日側のセル）
        val st = state(listOf(listOf(b, b, a, rest, rest), row1), mapOf("0,2" to a), listOf(C3wRow("A", "B")))
        val sched = st.schedule.toIntArray2D()
        val rep = UnifiedViolationChecker.check(st, sched)
        assertEquals(1, rep.breakdown["c3w"])
        assertEquals(1, rep.hard)
        assertEquals("vio-c3w", rep.violations["0,1"])
        assertEquals(9000.0, MirrorKeys.weightOf("c3w"), 0.0)
        assertTrue("c3w" in MirrorKeys.hard)

        val p = Problem(st)
        val bd = HashMap<String, Long>()
        val parts = Evaluator(p).fullEvalParts(sched, bd)
        assertEquals(1L, parts[0]); assertEquals(1L, bd["c3w"])

        val de = DeltaEvaluator(p)
        assertEquals(1L, de.familyRaw()["c3w"])
        de.apply(0, 1, rest)
        assertEquals(0L, de.familyRaw()["c3w"])
        assertEquals(Evaluator(p).fullEval(de.snapshot()), de.score())
        de.apply(0, 1, b)
        assertEquals(1L, de.familyRaw()["c3w"])
        assertEquals(Evaluator(p).fullEval(de.snapshot()), de.score())
    }

    @Test fun firstDayAndNonWishedXAreExempt() {
        // 希望は (0,0)=A だけ。day0 に前日は無く、day2/day4 の A は希望でない → 0
        val st = state(listOf(listOf(a, b, a, b, a), row1), mapOf("0,0" to a), listOf(C3wRow("A", "B")))
        assertEquals(0, UnifiedViolationChecker.check(st).breakdown["c3w"])
    }

    @Test fun impossibleWishDoesNotTrigger() {
        // 群が A を担当できない＝希望 A は固定されない（wishLocked=false）→ 前日の B は違反にしない
        val st = state(listOf(listOf(b, b, a, rest, rest), row1), mapOf("0,2" to a), listOf(C3wRow("A", "B")),
            groupShift = listOf(listOf(1, 0, 1)))
        assertEquals(0, UnifiedViolationChecker.check(st).breakdown["c3w"])
    }

    @Test fun makesForbiddenRunPrunesTheBannedShift() {
        val st = state(listOf(listOf(rest, rest, a, rest, rest), row1), mapOf("0,2" to a), listOf(C3wRow("A", "B")))
        val p = Problem(st)
        val sched = st.schedule.toIntArray2D()
        assertTrue(p.c3wBanned(0, 1, b))
        assertTrue(p.makesForbiddenRun(sched, 0, 1, b))
        assertFalse(p.makesForbiddenRun(sched, 0, 1, rest))
        assertFalse(p.makesForbiddenRun(sched, 0, 0, b))   // 翌日 (0,1) は希望でない
        assertFalse(p.makesForbiddenRun(sched, 1, 1, b))   // 別の職員
    }

    @Test fun wishOnBothDaysIsCountedAndDiagnosed() {
        val st = state(listOf(listOf(rest, b, a, rest, rest), row1), mapOf("0,1" to b, "0,2" to a), listOf(C3wRow("A", "B")))
        assertEquals(1, UnifiedViolationChecker.check(st).breakdown["c3w"])
        val issue = V6SanityPort.buildGuidance(st).firstOrNull { it.wishKey == "0,1" && it.action == SettingFixAction.REMOVE_WISH }
        assertTrue("希望どうしの衝突が設定ミス診断に出ること", issue != null)
        assertTrue(issue!!.problem.contains("希望どうし"))
    }

    @Test fun jsonAndCsvRoundTrip() {
        val st = state(listOf(row1, row1), emptyMap(), listOf(C3wRow("A", "B"), C3wRow("休", "A")))
        val back = StateParser.parse(StateParser.serialize(st, st.schedule.toIntArray2D()))
        assertEquals(st.cons3w, back.cons3w)
        val csv = ConstraintsCsvIO.parse(ConstraintsCsvIO.build(st), st)
        assertEquals(st.cons3w, csv!!.state.cons3w)
        assertEquals(2, csv.accepted)
        // 既存 JSON（cons3w キー無し）は空として読む
        assertTrue(StateParser.parse(StateParser.serialize(st.copy(cons3w = emptyList()), st.schedule.toIntArray2D())).cons3w.isEmpty())
    }
}
