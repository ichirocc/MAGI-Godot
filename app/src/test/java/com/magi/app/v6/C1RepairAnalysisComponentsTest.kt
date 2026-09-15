package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [C1 重複窓の連結成分化] `C1RepairAnalysis.components()`（同一職員の cons1 窓を区間重なりでグラフ化し
 * 連結成分へ分割する）と `solveComponent`（`solveWindow` の一般化）の検証。
 * 実データ4件が全件「休の短期窓＋長期窓」の入れ子パターンを持つため、`components()` がこれを正しく
 * 1成分に併合すること・重ならない窓や別職員は分割されたままであることを固定する。
 */
class C1RepairAnalysisComponentsTest {

    private fun st(
        days: Int,
        staff: Int,
        sched: List<List<Int>>,
        cons1: List<C1Row>,
    ): MagiState {
        val end = "2026-01-" + days.toString().padStart(2, '0')
        val shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", ""))
        return MagiState(
            startDate = "2026-01-01", endDate = end,
            shifts = shifts, groups = listOf(Group("G", "G")),
            staff = List(staff) { Staff("s$it", 0) }, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = sched, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = cons1, cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    // ---- 1) 同一staffで別ルールの窓が重なる → 1つの WindowComponent へ併合 ------------------------

    @Test
    fun overlappingWindowsFromDifferentRulesMergeIntoOneComponent() {
        // staff0: 休を day5,10,14 に配置（X=index1='X'扱いの他日はXで埋める）。
        //   ルールA「5日窓に休>=1」: 窓[0,5) だけが不足（day0-4に休が無い）、他の窓(w=1..10)は充足。
        //   ルールB「15日窓に休>=4」: 唯一の窓[0,15)は休が3個（5,10,14）で不足(<4)。
        //   両方とも start=0 で重なるため1成分に併合される。
        val row = MutableList(15) { 1 }
        row[5] = 0; row[10] = 0; row[14] = 0
        val s = st(15, 1, listOf(row), listOf(C1Row("5", "休", "1"), C1Row("15", "休", "4")))
        val p = Problem(s); val sched = s.schedule.toIntArray2D()
        val vios = C1RepairAnalysis.analyze(p, sched)
        assertEquals("2ルール分の窓不足が検出される", 2, vios.size)
        val comps = C1RepairAnalysis.components(p, sched)
        assertEquals("1成分に併合される", 1, comps.size)
        val c = comps[0]
        assertEquals(0, c.staff)
        assertEquals(0, c.start)
        assertEquals(15, c.end)
        assertEquals("両ルールの窓を両方含む", 2, c.members.size)
    }

    // ---- 2) 重ならない窓（十分離れた別々の違反）は別コンポーネントのまま ---------------------------

    @Test
    fun nonOverlappingWindowsStaySeparateComponents() {
        // staff0: T=12, ルール「3日窓に休>=1」。休を day0,4,5,9,11 に配置すると、
        //   窓[1,4)(w=1)と[2,5)(w=2)だけが不足（休が無い連続3日）、
        //   窓[6,9)(w=6)と[7,10)(w=7)と[8,11)(w=8)も別クラスタとして不足。
        val row = listOf(0, 1, 1, 1, 0, 0, 1, 1, 1, 0, 1, 0)
        val s = st(12, 1, listOf(row), listOf(C1Row("3", "休", "1")))
        val p = Problem(s); val sched = s.schedule.toIntArray2D()
        val comps = C1RepairAnalysis.components(p, sched)
        assertEquals("離れた2クラスタは別成分のまま", 2, comps.size)
        val sorted = comps.sortedBy { it.start }
        assertTrue("1つ目のクラスタが2つ目より前で終わる(重ならない)", sorted[0].end <= sorted[1].start)
        for (c in comps) assertEquals(0, c.staff)
    }

    // ---- 3) 別staffは常に別コンポーネント（staff間のエッジは張らない） --------------------------

    @Test
    fun differentStaffAreAlwaysSeparateComponentsEvenWhenWindowsCoincide() {
        // staff0/staff1 とも同じ窓位置(start=0)で同じルールが不足する（幾何学的には「重なって」いる
        // ように見えるが、職員が違うので絶対に併合されない）。
        val s = st(5, 2, listOf(listOf(1, 1, 1, 1, 1), listOf(1, 1, 1, 1, 1)), listOf(C1Row("5", "休", "1")))
        val p = Problem(s); val sched = s.schedule.toIntArray2D()
        val comps = C1RepairAnalysis.components(p, sched)
        assertEquals(2, comps.size)
        assertEquals(setOf(0, 1), comps.map { it.staff }.toSet())
        for (c in comps) assertEquals(1, c.members.size)
    }

    // ---- 境界: 違反が0件・1件のときの components() ---------------------------------------------

    @Test
    fun componentsIsEmptyWhenNoViolations() {
        val s = st(5, 1, listOf(listOf(0, 0, 0, 0, 0)), listOf(C1Row("5", "休", "1")))
        val p = Problem(s); val sched = s.schedule.toIntArray2D()
        assertTrue(C1RepairAnalysis.components(p, sched).isEmpty())
    }

    @Test
    fun singleViolationYieldsSingleMemberComponentMatchingTheViolationItself() {
        val s = st(4, 2, listOf(listOf(1, 1, 2, 2), listOf(2, 2, 1, 1)), listOf(C1Row("2", "X", "1")))
        val p = Problem(s); val sched = s.schedule.toIntArray2D()
        val vios = C1RepairAnalysis.analyze(p, sched)
        val comps = C1RepairAnalysis.components(p, sched)
        // s の 2 職員はそれぞれ独立した窓不足を持つ（別 staff なので併合されない）。
        assertEquals(vios.size, comps.sumOf { it.members.size })
        for (c in comps) {
            assertEquals(1, c.members.size)
            val v = c.members[0]
            assertEquals(v.staff, c.staff)
            assertEquals(v.start, c.start)
            assertEquals(v.start + v.windowDays, c.end)
        }
    }

    // ---- 4) solveWindow は solveComponent(単一メンバー) と完全に同じ結果を返す -------------------

    @Test
    fun solveWindowMatchesSolveComponentForASingleMember() {
        val s = st(4, 2, listOf(listOf(1, 1, 2, 2), listOf(2, 2, 1, 1)), listOf(C1Row("2", "X", "1")))
        val p = Problem(s); val sched = s.schedule.toIntArray2D()
        val v = C1RepairAnalysis.analyze(p, sched).first { it.staff == 0 }
        val viaWindow = C1RepairAnalysis.solveWindow(p, sched, v)
        val viaComponent = C1RepairAnalysis.solveComponent(
            p, sched, C1RepairAnalysis.WindowComponent(listOf(v), v.staff, v.start, v.start + v.windowDays),
        )
        assertEquals(viaWindow.minJointC1, viaComponent.minJointC1)
        assertEquals(viaWindow.baselineJointC1, viaComponent.baselineJointC1)
        assertEquals(viaWindow.exhaustive, viaComponent.exhaustive)
        assertEquals(viaWindow.focusResidual, viaComponent.focusResidual)
        assertEquals(viaWindow.patch == null, viaComponent.patch == null)
        if (viaWindow.patch != null) {
            val a = viaWindow.patch!!.map { it.toList() }
            val b = viaComponent.patch!!.map { it.toList() }
            assertEquals("patch も同一（単一メンバーは数式的に同じ入力になる）", a, b)
        }
    }

    @Test
    fun solveWindowMatchesSolveComponentOnAllExistingFixtures() {
        // 既存 C1RepairAnalysisTest の主要フィクスチャを総なめし、単一メンバー経路が
        // solveWindow と solveComponent とで一致し続けることを回帰として固定する。
        data class Case(val s: MagiState)
        val cases = listOf(
            Case(st(3, 2, listOf(listOf(2, 2, 2), listOf(1, 1, 2)), listOf(C1Row("2", "X", "1")))),
            Case(st(4, 2, listOf(listOf(1, 1, 2, 2), listOf(2, 2, 1, 1)), listOf(C1Row("2", "X", "1")))),
            Case(st(3, 2, listOf(listOf(2, 2, 2), listOf(1, 0, 0)), listOf(C1Row("3", "X", "2")))),
            Case(st(5, 2, listOf(listOf(2, 2, 2, 2, 2), listOf(1, 0, 0, 0, 0)), listOf(C1Row("2", "X", "1")))),
        )
        for (c in cases) {
            val p = Problem(c.s); val sched = c.s.schedule.toIntArray2D()
            for (v in C1RepairAnalysis.analyze(p, sched)) {
                val viaWindow = C1RepairAnalysis.solveWindow(p, sched, v)
                val viaComponent = C1RepairAnalysis.solveComponent(
                    p, sched, C1RepairAnalysis.WindowComponent(listOf(v), v.staff, v.start, v.start + v.windowDays),
                )
                assertEquals("staff=${v.staff} start=${v.start}", viaWindow.minJointC1, viaComponent.minJointC1)
                assertEquals(viaWindow.exhaustive, viaComponent.exhaustive)
                assertEquals(viaWindow.focusResidual, viaComponent.focusResidual)
                assertEquals(viaWindow.patch == null, viaComponent.patch == null)
            }
        }
    }

    // ---- 5) useComponents=false（既定）は旧経路のまま：既存の全テストの通過に加え、ここでも直接確認 ----

    @Test
    fun applyC1ExactWindowRepairDefaultsToLegacyPathAndIgnoresComponents() {
        val s = st(4, 2, listOf(listOf(1, 1, 2, 2), listOf(2, 2, 1, 1)), listOf(C1Row("2", "X", "1")))
        val sched = s.schedule.toIntArray2D()
        val viaDefault = C1WindowPolish.applyC1ExactWindowRepair(s, sched)
        val viaExplicitFalse = C1WindowPolish.applyC1ExactWindowRepair(s, sched, useComponents = false)
        assertTrue(viaDefault.newSchedule.contentDeepEquals(viaExplicitFalse.newSchedule))
        assertEquals(viaDefault.applied, viaExplicitFalse.applied)
    }

    // ---- 6) useComponents=true が単独窓修復では届かない解に届く（乱数探索で発見・UnifiedViolationChecker
    //         で確定した合成例。手計算での決め打ちはしない）------------------------------------------
    //
    // 職員0(休/夜/日すべて担当可)が「休>=1」「夜>=1」の2ルールを同時に不足（休職員1/4は休のみ担当可、
    // 夜職員2/3は夜のみ担当可）。旧経路(useComponents=false)は休ルールの窓を単独で厳密探索し、
    // 職員0のjoint c1を2→1にする解（休を必要数(1)よりずっと多く職員0に割り当てる配置）を先に確定する
    // ——その窓だけを見るDFSには「後で夜ルールも解かねばならない」という視野が無いため、職員0の
    // 可動日（休でない日）を使い切ってしまい、続く夜ルールの窓探索(M={職員0,2,3}、休を担当できない
    // 2人だけ)は職員0のどの可動日にも夜トークンが残っておらず解消不能に陥る（exhaustive=true・
    // patch=null で確定）。連結成分化は最初から M={職員0,1,2,3,4}（休・夜どちらの関与者も）で
    // 1回のDFSにかけるため、休を必要数(1)ぴったりに抑えつつ夜も1つ確保する配置を見つけられる。
    private fun sequentialBlindSpotFixture(): MagiState {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("夜", "夜", "", ""), Shift("日", "日", "", ""))
        val groups = listOf(Group("全可", "G0"), Group("休班", "G1"), Group("夜班", "G2"))
        val groupShift = listOf(listOf(1, 1, 1), listOf(1, 0, 1), listOf(0, 1, 1))
        val staffGroup = listOf(0, 1, 2, 2, 1)
        val staff = staffGroup.mapIndexed { i, g -> Staff("s$i", g) }
        val sched = listOf(
            listOf(2, 2, 2, 2, 2, 2),
            listOf(0, 2, 2, 0, 2, 0),
            listOf(1, 1, 2, 1, 2, 2),
            listOf(1, 1, 2, 1, 2, 2),
            listOf(2, 0, 2, 0, 0, 0),
        )
        return MagiState(
            startDate = "2026-01-01", endDate = "2026-01-06",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = groups.map { shifts.map { "" } },
            schedule = sched, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row("6", "休", "1"), C1Row("6", "夜", "1")),
            cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun sequentialSingleWindowRepairGetsStuckAtOneOfTwoOverlappingRules() {
        val s = sequentialBlindSpotFixture()
        val sched = s.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(s, sched)
        assertEquals("職員0が休・夜の両方で不足", 2, before.breakdown["c1"])
        val cfg = C1RepairAnalysis.Config(maxInvolvedStaff = 8, maxWindowDays = 6)
        val res = C1WindowPolish.applyC1ExactWindowRepair(s, sched, cfg, useComponents = false)
        val after = UnifiedViolationChecker.check(s, res.newSchedule)
        assertEquals("旧経路は1件しか解消できない（視野の狭さで手詰まり）", 1, after.breakdown["c1"])
        assertEquals(0, after.hard)
    }

    @Test
    fun componentBasedRepairResolvesBothOverlappingRulesTogether() {
        val s = sequentialBlindSpotFixture()
        val sched = s.schedule.toIntArray2D()
        val cfg = C1RepairAnalysis.Config(maxInvolvedStaff = 8, maxWindowDays = 6)
        val res = C1WindowPolish.applyC1ExactWindowRepair(s, sched, cfg, useComponents = true)
        val after = UnifiedViolationChecker.check(s, res.newSchedule)
        assertEquals("成分化により2件とも解消できる", 0, after.breakdown["c1"])
        assertEquals(0, after.hard)
        // coverage(被覆)は permutation で保存されるはず。cons41/covU/covO は本フィクスチャに無いが、
        // 念のため各日・各シフトの人数が変わっていないことを確認する（探索の安全性の根幹）。
        for (d in 0 until s.dayCount) for (k in shiftsCount(s)) {
            assertEquals("d=$d k=$k の人数は不変", countAt(sched, d, k), countAt(res.newSchedule, d, k))
        }
    }

    private fun shiftsCount(s: MagiState) = 0 until s.shiftCount
    private fun countAt(sched: Array<IntArray>, d: Int, k: Int) = sched.count { it[d] == k }
}
