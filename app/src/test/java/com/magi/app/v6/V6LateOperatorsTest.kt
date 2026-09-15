package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [HF528/541移植] RectSwap2 / C1BlockN の不変条件:
 *  (1) 同日内交換のみ → 日×シフトの被覆カウントは常に保存
 *  (2) HF537ゲート → 採用後の HARD は開始時以下
 *  (3) improve() は入力 schedule を変更しない(コピーに適用)
 */
class V6LateOperatorsTest {

    private fun st(): MagiState = MagiState(
        startDate = "2026-06-01",
        endDate = "2026-06-05",
        shifts = listOf(Shift("日勤A", "A", "2", ""), Shift("日勤B", "B", "1", ""), Shift("休み", "休", "", "")),
        groups = listOf(Group("G", "G")),
        staff = listOf(Staff("s0", 0), Staff("s1", 0), Staff("s2", 0), Staff("s3", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)),
        groupShiftApt = listOf(listOf("", "", "")),
        schedule = listOf(
            listOf(1, 2, 1, 2, 1), // s0: A不足(=range違反者) → Rect(D)/BlkN の標的
            listOf(0, 0, 0, 0, 0),
            listOf(0, 0, 0, 0, 0),
            listOf(0, 1, 0, 1, 0),
        ),
        wishes = emptyMap(),
        staffRange = mapOf("0,0" to Range("4", "")), // s0 の A 下限4(現0)
        needDay1 = emptyMap(),
        needDay2 = emptyMap(),
        cons1 = listOf(C1Row("3", "A", "3")), // 3日窓にA3回
        cons2 = emptyList(),
        cons3 = emptyList(),
        cons3n = emptyList(),
        cons3m = emptyList(),
        cons3mn = emptyList(),
        cons41 = emptyList(),
        cons42 = emptyList(),
    )

    private fun coverage(sched: Array<IntArray>, k: Int, t: Int): Array<IntArray> {
        val c = Array(k) { IntArray(t) }
        for (i in sched.indices) for (j in 0 until t) {
            val kk = sched[i][j]
            if (kk in 0 until k) c[kk][j]++
        }
        return c
    }

    @Test
    fun coveragePreservedAndHardNeverWorse() {
        val state = st()
        val sched = state.schedule.toIntArray2D()
        val snapshot = sched.copy2D()
        val pre = UnifiedViolationChecker.check(state, sched)
        val covPre = coverage(sched, state.shiftCount, state.dayCount)

        val res = V6LateOperators.improve(
            state, sched, pre, Random(7),
            EngineClock.nowMs() + 3000, rectTry = 60, blkTry = 60,
        )

        // (3) 入力不変
        for (i in sched.indices) assertTrue(sched[i].contentEquals(snapshot[i]))
        // (1) 被覆保存
        val covPost = coverage(res.schedule, state.shiftCount, state.dayCount)
        for (k in covPre.indices) assertTrue("coverage k=$k", covPre[k].contentEquals(covPost[k]))
        // (2) HARD 非悪化
        assertTrue("hard ${res.report.hard} <= ${pre.hard}", res.report.hard <= pre.hard)
        // 採用件数とログ件数の一致
        assertEquals(res.rect + res.blkN, res.logs.size)
        // 採用ゼロなら schedule/report は実質入力同等
        if (res.rect + res.blkN == 0) {
            assertEquals(pre.hard, res.report.hard)
            assertEquals(pre.soft, res.report.soft)
        }
    }

    @Test
    fun optFlagBoolReadsExtras() {
        val base = st()
        assertTrue(V6LateOperators.optFlagBool(base, "rectSwap", true))   // 未設定→既定
        // JVM単体テストでは android.jar の org.json がスタブのため Map 形のみ検証(JSONObject 分岐は実機経路)
        val mapOn = base.copy(extras = mapOf("optFlags" to mapOf("rectSwap" to true)))
        val mapOff = base.copy(extras = mapOf("optFlags" to mapOf("rectSwap" to false)))
        assertTrue(V6LateOperators.optFlagBool(mapOn, "rectSwap", false))
        assertTrue(!V6LateOperators.optFlagBool(mapOff, "rectSwap", true))
    }
}
