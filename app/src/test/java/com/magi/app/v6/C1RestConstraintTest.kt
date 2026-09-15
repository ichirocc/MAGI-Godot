package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1（一定日数内の休日下限）検証アルゴリズムの正しさを、ユーザ提示の14日パターンで固定する。
 *
 * 条件: 連続14日のどの窓でも「休」を4回以上（C1Row(day1=14, shiftKigou=休, day2=4)）。
 * rest=0(休) / work=1(勤) の2シフトだけにし、C1 以外の制約・希望・need を空にして、
 * 違反マークが c1 のみになるようにする（純粋に C1 ロジックだけを検証）。
 */
class C1RestConstraintTest {

    // rest=0, work=1 のまま index と一致（shift0=休, shift1=勤）
    private val validPatterns = listOf(
        listOf(1, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1, 0, 0), // 休4
        listOf(1, 1, 1, 1, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0), // 休6
        listOf(1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0), // 休7
        listOf(1, 1, 0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 1, 1), // 休4
        listOf(1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1, 0, 0, 1), // 休4
        listOf(1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1), // 休4
        listOf(1, 1, 1, 0, 0, 1, 1, 1, 0, 0, 1, 1, 1, 0), // 休5
        listOf(1, 0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 1, 0, 1), // 休5
    )
    private val violationPatterns = listOf(
        listOf(1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 0), // 休3
        listOf(1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 0), // 休2
        listOf(1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1), // 休1
        listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), // 休0
        listOf(1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1), // 休3
    )

    private fun buildState(rows: List<List<Int>>): MagiState {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("勤", "勤", "", ""))
        val groups = listOf(Group("G0", "G0"))
        val staff = rows.indices.map { Staff("s$it", 0) }
        return MagiState(
            startDate = "2025-01-01",
            endDate = "2025-01-14",
            shifts = shifts,
            groups = groups,
            staff = staff,
            use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),           // G0 は 休・勤 とも担当可
            groupShiftApt = listOf(listOf("", "")),
            schedule = rows,
            wishes = emptyMap(),
            staffRange = emptyMap(),
            needDay1 = emptyMap(),
            needDay2 = emptyMap(),
            cons1 = listOf(C1Row("14", "休", "4")),       // 14日窓で休4回以上
            cons2 = emptyList(),
            cons3 = emptyList(),
            cons3n = emptyList(),
            cons3m = emptyList(),
            cons3mn = emptyList(),
            cons41 = emptyList(),
            cons42 = emptyList(),
        )
    }

    private fun rowHasC1(report: ViolationReport, staffIdx: Int, days: Int): Boolean =
        (0 until days).any { j -> report.violations["$staffIdx,$j"]?.contains("c1") == true }

    @Test
    fun validPatternsHaveNoC1Violation() {
        val st = buildState(validPatterns)
        val report = UnifiedViolationChecker.check(st)
        for (i in validPatterns.indices) {
            assertFalse("V${i + 1}（休≥4）は C1 違反にならないはず", rowHasC1(report, i, 14))
        }
    }

    @Test
    fun violationPatternsAreAllFlagged() {
        val st = buildState(violationPatterns)
        val report = UnifiedViolationChecker.check(st)
        for (i in violationPatterns.indices) {
            assertTrue("X${i + 1}（休≤3）は C1 違反として検出されるはず", rowHasC1(report, i, 14))
        }
        // day1(14) == 期間(14) なので各違反スタッフにつき窓は1個 → 件数はちょうどパターン数
        assertEquals(violationPatterns.size, report.breakdown["c1"])
    }

    @Test
    fun mixedSetCountsExactlyTheViolatingRows() {
        val st = buildState(validPatterns + violationPatterns)
        val report = UnifiedViolationChecker.check(st)
        // 合格8 + 違反5 を混在させても、c1 件数は違反5件のみ
        assertEquals(violationPatterns.size, report.breakdown["c1"])
    }
}
