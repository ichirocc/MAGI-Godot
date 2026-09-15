package com.magi.app.v6

import com.magi.app.model.C41Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [汎用玉突き結合フレームワーク, 3.249.0] `CombinatorialRepair.combineAndApply` 自体の直接検証。
 * c1/range/c3mn/apt/fair の5族はいずれもこの同一ヘルパへ「単独では不採用だった候補」を
 * 供給するだけ（各族固有の捕捉箇所は各Polishパス自身のテストで確認済み）。ここでは
 * 共有ロジック本体（組合せ列挙・重複セル排除・shouldStop 打ち切り・統計集計）を、
 * AptPolishTest と同一の検証済み最小盤面（`combineTwoRejectedState` 相当）を用いて
 * パイプラインを経由せず直接検証する。
 */
class CombinatorialRepairTest {

    private fun isBetterLocal(a: ViolationReport, b: ViolationReport): Boolean {
        if (a.hard != b.hard) return a.hard < b.hard
        if (a.total != b.total) return a.total < b.total
        return a.weightedScore < b.weightedScore
    }

    // shifts: 休(0) P(1) Qres(2) D(3)。staff: X(0) Y(1) W1(2) W2(3)。
    // X=P(aptHigh, 目標0)・Y=Qres(目標なし、Dへ動けばaptLow(目標1)を解消)。
    // Xの唯一の代替候補DはstaffRangeでhi=0固定＝Xが単独で「解決」する抜け道を塞ぐ。
    // G0のQres在籍数は常に1人固定(c41 l=u=1)。X到着(+1)とY退出(-1)が相殺すると
    // c41違反ゼロのままapt違反だけが2件解消する＝どちらも単独では不採用(タイ)。
    // W1/W2(常に休、動かない)は fair(グループ内公平化) の分母を薄める補助。X,Yのみ(2人)だと
    // X単独の1手がP側とQres側のfair偏りを同時に均してしまい、apt/c41の弱いタイをfairの大きな
    // 隠れ改善が圧倒してしまう（AptPolishTestの敵対検証=CI実測で発見・Pythonで独立再検証済み）。
    // W1/W2にもstaffRangeでDを禁止(hi=0)し、apt目標をクランプで実効0へ潰す（さもないとD目標=1が
    // グループ共有のためW1/W2も常時aptLow(D)を持ち、彼ら自身がDへ動いて「解決」してしまう）。
    private fun combineTwoRejectedState(): MagiState {
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("P", "P", "", ""),
            Shift("Qres", "Qres", "", ""),
            Shift("D", "D", "", ""),
        )
        val groups = listOf(Group("G0", "G0"))
        val groupShift = listOf(listOf(1, 1, 1, 1))
        val groupShiftApt = listOf(listOf("", "0", "", "1"))
        val staff = listOf(Staff("X", 0), Staff("Y", 0), Staff("W1", 0), Staff("W2", 0))
        val schedule = listOf(listOf(1), listOf(2), listOf(0), listOf(0))
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = groupShiftApt,
            schedule = schedule, wishes = emptyMap(),
            staffRange = mapOf(
                "0,3" to Range("", "0"), // X, D
                "2,3" to Range("", "0"), // W1, D
                "3,3" to Range("", "0"), // W2, D
            ),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            // [3.522.0] apt重み1→4でX/Y単独移動が「タイ」でなくなった（apt改善1件がc41悪化1件を
            //   上回ってしまう）ため、c41行を4重複させ1違反=4件計上にして単独では確実に悪化するよう調整。
            //   結合時はQres在籍数が相殺されゼロのままなので4重複の影響を受けない（意図した性質は不変）。
            cons41 = listOf(
                C41Row("G0", "Qres", "1", "1"), C41Row("G0", "Qres", "1", "1"),
                C41Row("G0", "Qres", "1", "1"), C41Row("G0", "Qres", "1", "1"),
            ),
            cons42 = emptyList(),
        )
    }

    @Test
    fun combineAndApplyAcceptsPairRejectedIndividuallyButImprovingTogether() {
        val st = combineTwoRejectedState()
        val work = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, work)
        // apt=2: X(P超過1)+Y(D不足1)。W1/W2はstaffRangeのクランプで実効目標0=違反なし。
        assertEquals(2, before.breakdown["apt"] ?: 0)
        assertEquals(0, before.hard)

        val candX = CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 2)), "test", "X")
        val candY = CombinatorialRepair.Candidate(listOf(intArrayOf(1, 0, 3)), "test", "Y")

        // [検証: 単独では不採用] それぞれ単独で適用するとタイ(改善なし)で不採用になることを確認
        //   （combineAndApply が実際に解くべき前提条件そのものを、まず自分で確かめる）。
        run {
            val w2 = st.schedule.toIntArray2D()
            w2[0][0] = 2
            val rep = UnifiedViolationChecker.check(st, w2)
            assertFalse("Xの単独移動は不採用(タイ)であるはず", isBetterLocal(rep, before))
        }
        run {
            val w2 = st.schedule.toIntArray2D()
            w2[1][0] = 3
            val rep = UnifiedViolationChecker.check(st, w2)
            assertFalse("Yの単独移動は不採用(タイ)であるはず", isBetterLocal(rep, before))
        }

        val stats = CombinatorialRepair.Stats()
        val after = CombinatorialRepair.combineAndApply(
            st, work, before, listOf(candX, candY), ::isBetterLocal, stats = stats,
        )

        assertEquals("結合後はapt=0", 0, after.breakdown["apt"] ?: -1)
        assertEquals("HARDは悪化しない", 0, after.hard)
        assertEquals("c41は相殺されゼロのまま", 0, after.breakdown["c41"] ?: -1)
        assertEquals(2, work[0][0])
        assertEquals(3, work[1][0])
        assertEquals(1, stats.combosAccepted)
        assertEquals(2, stats.mechanismCounts["test"])
        assertTrue(stats.acceptedLabels.isNotEmpty())
    }

    @Test
    fun combineAndApplySkipsCandidatesThatOverlapTheSameCell() {
        val st = combineTwoRejectedState()
        val work = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, work)

        // 両方とも同一セル(staff0,day0)を触る＝互いに排他な代替案。組合せても意味を持たない
        // ため、フルchecker呼出をスキップして採用されないことを確認する。
        val candA = CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 2)), "test")
        val candB = CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 3)), "test")

        val stats = CombinatorialRepair.Stats()
        val after = CombinatorialRepair.combineAndApply(
            st, work, before, listOf(candA, candB), ::isBetterLocal, stats = stats,
        )

        assertEquals("採用0件", 0, stats.combosAccepted)
        assertEquals("盤面は不変", 1, work[0][0])
        assertEquals("違反も不変", before.breakdown["apt"], after.breakdown["apt"])
    }

    @Test
    fun combineAndApplyStopsImmediatelyWhenShouldStopIsTrue() {
        val st = combineTwoRejectedState()
        val work = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, work)

        val candX = CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 2)), "test")
        val candY = CombinatorialRepair.Candidate(listOf(intArrayOf(1, 0, 3)), "test")

        val stats = CombinatorialRepair.Stats()
        val after = CombinatorialRepair.combineAndApply(
            st, work, before, listOf(candX, candY), ::isBetterLocal,
            shouldStop = { true }, stats = stats,
        )

        assertTrue("打ち切りフラグが立つこと", stats.truncated)
        assertEquals("採用0件", 0, stats.combosAccepted)
        assertEquals("盤面は不変", 1, work[0][0])
        assertEquals("違反も不変", before.breakdown["apt"], after.breakdown["apt"])
    }

    @Test
    fun combineAndApplyIsNoOpWithFewerThanTwoCandidates() {
        val st = combineTwoRejectedState()
        val work = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, work)

        val candX = CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 2)), "test")
        val stats = CombinatorialRepair.Stats()
        val after = CombinatorialRepair.combineAndApply(
            st, work, before, listOf(candX), ::isBetterLocal, stats = stats,
        )

        assertEquals(0, stats.combosTried)
        assertEquals(0, stats.combosAccepted)
        assertEquals(before, after)
    }

    // [停滞検知, ユーザー指示「早期脱出しないのか?」への対応] 全て同一セル(staff0,day0)への
    // 無変化(no-op)候補＝どの2件を組合せても必ず重複セルでスキップされ続ける。10件・C(10,2)=45通り
    // のうち、maxStagnantTries=3で全網羅する前に早期終了することを固定。
    @Test
    fun combineAndApplyGivesUpEarlyAfterConsecutiveMisses() {
        val st = combineTwoRejectedState()
        val work = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, work)

        val dupes = (0 until 10).map { CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 1)), "dup") }

        val stats = CombinatorialRepair.Stats()
        val after = CombinatorialRepair.combineAndApply(
            st, work, before, dupes, ::isBetterLocal, maxStagnantTries = 3, stats = stats,
        )

        assertTrue("停滞検知で早期終了", stats.stagnantExit)
        assertFalse("時間切れではない", stats.truncated)
        assertEquals("採用0件", 0, stats.combosAccepted)
        assertEquals("maxStagnantTries通りで打ち切り(45通り網羅しない)", 3, stats.combosTried)
        assertEquals("盤面は不変", before, after)
    }

    // [3.512.6] 上のcombineAndApplyGivesUpEarlyAfterConsecutiveMissesと同じ10件重複プール
    //   （C(10,2)=45通り）で、exhaustPairs=true なら maxStagnantTries=3 のままでも45通り全部試す。
    @Test
    fun combineAndApplyExhaustPairsExploresFullPairwiseSpaceInsteadOfStoppingEarly() {
        val st = combineTwoRejectedState()
        val work = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, work)

        val dupes = (0 until 10).map { CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 1)), "dup") }

        val stats = CombinatorialRepair.Stats()
        val after = CombinatorialRepair.combineAndApply(
            st, work, before, dupes, ::isBetterLocal, maxStagnantTries = 3, exhaustPairs = true, stats = stats,
        )

        assertEquals("C(10,2)=45通り全部を試す(既定3で早期終了しない)", 45, stats.combosTried)
        assertTrue("45通り使い切った時点では停滞終了フラグは立つ", stats.stagnantExit)
        assertEquals("採用0件(全て同一セルで重複)", 0, stats.combosAccepted)
        assertEquals("盤面は不変", before, after)
    }

    // exhaustPairs=true でも、既存の「単独では不採用だが結合で採用」の経路は変わらず動く
    // （新パラメータが通常の採用ロジックを壊していないことの回帰確認）。
    @Test
    fun combineAndApplyExhaustPairsStillAcceptsAWinningComboNormally() {
        val st = combineTwoRejectedState()
        val work = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, work)
        val candX = CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 2)), "test", "X")
        val candY = CombinatorialRepair.Candidate(listOf(intArrayOf(1, 0, 3)), "test", "Y")

        val stats = CombinatorialRepair.Stats()
        val after = CombinatorialRepair.combineAndApply(
            st, work, before, listOf(candX, candY), ::isBetterLocal, exhaustPairs = true, stats = stats,
        )

        assertEquals("結合後はapt=0", 0, after.breakdown["apt"] ?: -1)
        assertEquals(1, stats.combosAccepted)
    }

    // isBetter（呼び出し側から注入される判定）が例外を投げても、試行中の組合せを盤面に残さない。
    // 旧は「適用→評価→巻き戻し」が直列で、評価の途中で例外が出ると work に組合せが残ったまま伝播した。
    @Test
    fun combineAndApplyRestoresBoardWhenEvaluationThrows() {
        val st = combineTwoRejectedState()
        val work = st.schedule.toIntArray2D()
        val snapshot = work.map { it.clone() }.toTypedArray()
        val before = UnifiedViolationChecker.check(st, work)
        val candX = CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 2)), "test", "X")
        val candY = CombinatorialRepair.Candidate(listOf(intArrayOf(1, 0, 3)), "test", "Y")

        var thrown = false
        try {
            CombinatorialRepair.combineAndApply(st, work, before, listOf(candX, candY), { _, _ -> throw IllegalStateException("evaluator failed") })
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue("例外は呼び出し元へ伝播する", thrown)
        for (i in work.indices) assertTrue("行 $i が巻き戻されている", snapshot[i].contentEquals(work[i]))
    }
}
