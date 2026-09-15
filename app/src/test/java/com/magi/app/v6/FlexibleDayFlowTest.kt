package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlexibleDayFlowTest {
    @Test
    fun flowAllowsChangingTheDailyShiftMultiset() {
        val x = FlexibleDayFlow.INF
        val staffCost = arrayOf(
            longArrayOf(x, x, 0), // victimはB1のみ
            longArrayOf(0, 0, x), // substituteは休/Aｱ
        )
        // AｱとB1の1人目を強く優遇。旧token方式では存在しないB1を生成できない。
        val marginal = arrayOf(
            longArrayOf(0, 0),
            longArrayOf(-8000, 1),
            longArrayOf(-8000, 1),
        )
        val r = FlexibleDayFlow.solve(staffCost, marginal)
        requireNotNull(r)
        assertEquals(listOf(2, 1), r.assignment.toList())
    }

    private fun fiveIllegalAaState(): MagiState {
        val shifts = listOf(
            Shift("休み", "休", "", ""),
            Shift("A", "Aｱ", "1", "1"),
            Shift("B1", "B1", "1", "1"),
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-05",
            shifts = shifts,
            groups = listOf(Group("victim", "V"), Group("general", "G")),
            staff = listOf(Staff("美幸相当", 0), Staff("代用者", 1)),
            use2Patterns = true,
            groupShift = listOf(
                listOf(1, 0, 1), // victim: 休/B1、Aｱ不可
                listOf(1, 1, 0), // substitute: 休/Aｱ
            ),
            groupShiftApt = List(2) { List(3) { "" } },
            schedule = listOf(
                List(5) { 1 }, // victimに担当不可Aｱが5回
                List(5) { 0 },
            ),
            wishes = emptyMap(),
            staffRange = mapOf(
                "0,0" to Range("0", "5"),
                "0,1" to Range("0", "0"),
                "0,2" to Range("0", "5"),
            ),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun rangePolishEliminatesFiveIllegalAaCellsInOnePass() {
        val st = fiveIllegalAaState()
        val before = UnifiedViolationChecker.check(st, st.schedule.toIntArray2D())
        assertEquals(5, before.breakdown["groupViol"] ?: 0)

        val result = RangePolish.applyRangePolish(
            st, st.schedule.toIntArray2D(), maxPasses = 1, seed = 1L,
        )
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("担当不可Aｱを全排出", 0, after.breakdown["groupViol"] ?: -1)
        assertEquals("victimは全日B1", List(5) { 2 }, result.newSchedule[0].toList())
        assertEquals("代用者は全日Aｱ", List(5) { 1 }, result.newSchedule[1].toList())
        assertEquals("被覆不足なし", 0, after.breakdown["covU"] ?: -1)
        assertTrue(result.logs.first().message.contains("柔軟日割当:5"))
    }

    @Test
    fun infeasibleWishForTheIllegalShiftDoesNotBlockTheFix() {
        // [3.270.0 監査で訂正] このテストは元々「希望固定Aｱは保持」＝`p.wish[i][j]>=0` だけで
        // 判定する旧`movable`実装（applyRangePolish等15箇所が共有していた定義）の挙動を固定していた。
        // だが `wishes = mapOf("0,0" to 1)` の希望先はまさにvictimがcanDo=falseなAｱ自身
        // （groupShift victim行=listOf(1,0,1)でAｱ不可）＝実現不能な希望。`Problem.wishLocked`
        // （MirrorCore.kt）は「実現不能な希望は凍結しない＝セルを最適化へ復帰させ、入口fallback値の
        // まま座礁するのを防ぐ」と明記済み（3.183.0でLightMirrorOptimizerに同種のバグを発見・修正した
        // のと同じ設計原則）。まさにこの座礁（担当不可Aｱに固定されたまま動かせない）を検証していた
        // 旧テストは、`applyRangePolish`等が`wishLocked`でなく生の`wish>=0`を使っていた実バグの
        // 回帰テストになっていた。`movable`を`!p.wishLocked(i,j)`へ統一した今、正しい挙動は
        // 「実現不能な希望はロックにならず、担当不可(groupViol)セルは通常どおり修復される」こと。
        val base = fiveIllegalAaState()
        val st = base.copy(wishes = mapOf("0,0" to 1))
        val result = RangePolish.applyRangePolish(
            st, st.schedule.toIntArray2D(), maxPasses = 1, seed = 1L,
        )
        assertEquals("実現不能な希望はロックにならずB1へ修復される", 2, result.newSchedule[0][0])
        assertEquals("担当不可groupViolは解消される", 0,
            UnifiedViolationChecker.check(st, result.newSchedule).breakdown["groupViol"] ?: -1)
    }

    /**
     * [3.246.0 隣接日連動] 上條洋平の実例（Dﾃを別職員へ渡そうとすると、自分自身の隣接日がまだDﾃの
     * ままなので新たな禁止連続に触れる）を最小盤面で再現。手Fの直接付替えだけでは日1の候補
     * （休・Q）がどちらも「日0=Q固定＋Q→休/Q→Qの禁止連続」で塞がる。`tryFixForbiddenRunViaAdjacentDay`
     * による隣接日(日0)の調整（Q→休）を伴って初めて日1をDﾃから解放できることを固定する。
     */
    private fun kamijoLikeState(): MagiState {
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("Dﾃ", "Dﾃ", "", ""),
            Shift("Q", "Q", "", ""),
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-02",
            shifts = shifts,
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("上條相当", 0)),
            use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1)),
            groupShiftApt = listOf(listOf("", "", "")),
            schedule = listOf(listOf(2, 1)), // 日0=Q(固定), 日1=Dﾃ(個人上限超過の対象)
            wishes = emptyMap(),
            staffRange = mapOf("0,1" to Range("0", "0")), // Dﾃ上限0
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = listOf(C3Row(listOf("Q", "休")), C3Row(listOf("Q", "Q"))),
            cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun rangePolishResolvesDteViaAdjacentDayLinkedFlexibleFlow() {
        val st = kamijoLikeState()
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertEquals("初期はDﾃ上限超過(high)が1件", 1, before.breakdown["high"] ?: 0)
        assertEquals("初期は禁止連続なし", 0, before.breakdown["c3n"] ?: 0)

        val result = RangePolish.applyRangePolish(st, sched, maxPasses = 1, seed = 1L)
        val after = UnifiedViolationChecker.check(st, result.newSchedule)

        assertEquals("Dﾃ超過が解消", 0, after.breakdown["high"] ?: -1)
        assertEquals("禁止連続を新たに作らない", 0, after.breakdown["c3n"] ?: -1)
        assertEquals("HARD不変", 0, after.hard)
        assertEquals("日1はDﾃから退避", 0, result.newSchedule[0].count { it == 1 })
        assertNotEquals("隣接日(日0)もQから調整されている", 2, result.newSchedule[0][0])
    }
}
