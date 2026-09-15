package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [3.517.0] PERSON_SWAP_ILS の摂動本体 `V6NativeOptimizer.personSwapKick` の固定テスト。
 * fair(公平化)が交換で不変であること・fair負担が最大のペアを優先して選ぶことを固定する
 * （`MirrorCore.kt`のfair計算と同一式で負担を集計している前提）。
 */
class PersonSwapKickTest {
    // 同群4名(a,b,c,d)。A(idx1)の回数: a=4,b=0,c=2,d=2 → 群平均2 → 負担 a=2,b=2,c=0,d=0。
    // a,b が唯一の最大負担ペアなので、乱数種によらず交換相手は決定的に a<->b になる。
    private fun fixture(): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-04",
        shifts = listOf(Shift("休み", "休", "", ""), Shift("早番", "A", "1", "")),
        groups = listOf(Group("G0", "G0")),
        staff = listOf(Staff("a", 0), Staff("b", 0), Staff("c", 0), Staff("d", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)),
        groupShiftApt = listOf(listOf("", "")),
        schedule = listOf(
            listOf(1, 1, 1, 1), // a: A,A,A,A
            listOf(0, 0, 0, 0), // b: 休,休,休,休
            listOf(1, 0, 1, 0), // c: A,休,A,休
            listOf(0, 1, 0, 1), // d: 休,A,休,A
        ),
        wishes = emptyMap(), staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
        cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test
    fun swapsTheHighestFairBurdenPairRegardlessOfSeed() {
        for (seed in 1L..5L) {
            val st = fixture()
            val p = Problem(st)
            val sched = st.schedule.toIntArray2D()
            V6NativeOptimizer.personSwapKick(p, sched, Random(seed), pairs = 1)
            // a<->b が丸ごと入れ替わっている（c,dは無傷）。
            assertEquals("seed=$seed: a は b の元の行を持つ", listOf(0, 0, 0, 0), sched[0].toList())
            assertEquals("seed=$seed: b は a の元の行を持つ", listOf(1, 1, 1, 1), sched[1].toList())
            assertEquals("seed=$seed: c は無傷", listOf(1, 0, 1, 0), sched[2].toList())
            assertEquals("seed=$seed: d は無傷", listOf(0, 1, 0, 1), sched[3].toList())
        }
    }

    @Test
    fun fairIsInvariantUnderTheSwap() {
        val st = fixture()
        val p = Problem(st)
        val before = UnifiedViolationChecker.check(st, st.schedule.toIntArray2D())
        val sched = st.schedule.toIntArray2D()
        V6NativeOptimizer.personSwapKick(p, sched, Random(1), pairs = 1)
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("交換前後でfairは不変（群内の回数集合が変わらないため）", before.breakdown["fair"] ?: 0, after.breakdown["fair"] ?: 0)
        assertTrue("非自明のケース（実際にfairが立っている）", (before.breakdown["fair"] ?: 0) > 0)
    }
}
