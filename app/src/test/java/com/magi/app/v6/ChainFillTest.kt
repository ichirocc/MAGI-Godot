package com.magi.app.v6

import com.magi.app.model.C1Row
import com.magi.app.model.C3Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [E11] findCovUChain（多人数ブロック移動）の検証。実機 2026-08 データでユーザーが手作業で見つけた
 * 「勤務→勤務」連鎖（既存の休→勤務修復では踏めない）を、決定的な連鎖探索が解けることを確認する。
 */
class ChainFillTest {

    // 8/17 相当（深さ2連鎖）: covU=P。P可能者(上條)はQに在勤 → Qを空けると covU → Qは山本(Rに在勤)が埋め、
    // Rは過剰なので山本が抜けても充足。期待: 上條 Q→P, 山本 R→Q の2手。
    private fun depth2State(): MagiState {
        // shift: 0=休(need無) 1=P(need1) 2=Q(need1) 3=R(need1)
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("P", "P", "1", ""),
            Shift("Q", "Q", "1", ""),
            Shift("R", "R", "1", ""),
        )
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"), Group("G2", "G2"))
        // G0=休/P/Q, G1=休/Q/R, G2=休/R
        val groupShift = listOf(
            listOf(1, 1, 1, 0),
            listOf(1, 0, 1, 1),
            listOf(1, 0, 0, 1),
        )
        // 上條∈G0(Qに在勤), 山本∈G1(Rに在勤), X∈G2(Rに在勤→Rを過剰に), Y∈G2(休)
        val staff = listOf(Staff("上條", 0), Staff("山本", 1), Staff("X", 2), Staff("Y", 2))
        val schedule = listOf(
            listOf(2), // 上條 = Q
            listOf(3), // 山本 = R
            listOf(3), // X = R（R が2人＝過剰）
            listOf(0), // Y = 休
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift,
            groupShiftApt = List(3) { List(4) { "" } },
            schedule = schedule,
            wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun chainFillSolvesDepth2Deadlock() {
        val st = depth2State()
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()

        // 前提: P(shift1) が covU、既存の休→勤務修復では踏めない（休のYはPを担当不可＝G2）。
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("初期はP不足(covU>0)であること", (before.breakdown["covU"] ?: 0) > 0)

        // 連鎖探索（seed 固定で決定的）。P=shift1, j=0。
        val chain = findCovUChain(p, sched, 1, 0, Random(42))
        assertNotNull("勤務→勤務の連鎖が見つかること", chain)
        assertTrue("2手以内の連鎖", chain!!.size in 1..2)

        // 適用して covU=0・hard 非増加を確認（keep-best 相当の妥当性）。
        for (mv in chain) sched[mv[0]][mv[1]] = mv[2]
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("連鎖適用後は covU=0", 0, after.breakdown["covU"] ?: 0)
        assertTrue("hard は悪化しない", after.hard <= before.hard)
    }

    // [玉突きの三連] 3人の交代連鎖でしか埋まらない局面: P<-Q<-R<-S（末端Sが過剰=余裕）。
    // a(Q担当) が P へ、b(R担当) が Q へ、c(S担当) が R へ動いて初めて covU=0 になり、
    // どの1人の直接移動でも別の covU を生むだけの「深さ3」を BFS が正しく踏むことを確認する。
    @Test
    fun chainFillSolvesDepth3Cascade() {
        val shifts = listOf(
            Shift("休", "休", "", ""), Shift("P", "P", "1", ""), Shift("Q", "Q", "1", ""),
            Shift("R", "R", "1", ""), Shift("S", "S", "1", ""),
        )
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"), Group("G2", "G2"), Group("G3", "G3"))
        // G0=休/P/Q, G1=休/Q/R, G2=休/R/S, G3=休/S（各群は隣接シフトのみ担当可＝連鎖を1本道にする）
        val groupShift = listOf(
            listOf(1, 1, 1, 0, 0),
            listOf(1, 0, 1, 1, 0),
            listOf(1, 0, 0, 1, 1),
            listOf(1, 0, 0, 0, 1),
        )
        val staff = listOf(Staff("a", 0), Staff("b", 1), Staff("c", 2), Staff("d", 3))
        // a=Q, b=R, c=S, d=S（Sが2人＝過剰=末端の余裕）
        val schedule = listOf(listOf(2), listOf(3), listOf(4), listOf(4))
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(4) { List(5) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("P不足(covU>0)が前提", (before.breakdown["covU"] ?: 0) > 0)

        val chain = findCovUChain(p, sched, 1, 0, Random(11))
        assertNotNull("3人連鎖が見つかること", chain)
        assertEquals("深さ3(3手)の連鎖であること", 3, chain!!.size)
        for (mv in chain) sched[mv[0]][mv[1]] = mv[2]

        assertEquals("a=P, b=Q, c=R, d=S(不変)", listOf(1, 2, 3, 4), listOf(sched[0][0], sched[1][0], sched[2][0], sched[3][0]))
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("covU が解消されること", 0, after.breakdown["covU"] ?: 0)
        assertTrue("hard は悪化しない", after.hard <= before.hard)
    }

    // [玉突きの五連] 5人の交代連鎖: P<-Q<-R<-S<-T<-U（末端Uが過剰=余裕）。maxDepth=5 の上限まで
    // BFS が正しく踏み、5手全てを一括で返すことを確認する（深さ3と同型の一本道を1段長くしたもの）。
    @Test
    fun chainFillSolvesDepth5Cascade() {
        val shifts = listOf(
            Shift("休", "休", "", ""), Shift("P", "P", "1", ""), Shift("Q", "Q", "1", ""),
            Shift("R", "R", "1", ""), Shift("S", "S", "1", ""), Shift("T", "T", "1", ""), Shift("U", "U", "1", ""),
        )
        val groups = listOf(
            Group("G0", "G0"), Group("G1", "G1"), Group("G2", "G2"),
            Group("G3", "G3"), Group("G4", "G4"), Group("G5", "G5"),
        )
        // G0=休/P/Q ... G4=休/T/U, G5=休/U（末端）。各群は隣接シフトのみ担当可＝連鎖を1本道にする。
        val groupShift = listOf(
            listOf(1, 1, 1, 0, 0, 0, 0),
            listOf(1, 0, 1, 1, 0, 0, 0),
            listOf(1, 0, 0, 1, 1, 0, 0),
            listOf(1, 0, 0, 0, 1, 1, 0),
            listOf(1, 0, 0, 0, 0, 1, 1),
            listOf(1, 0, 0, 0, 0, 0, 1),
        )
        val staff = listOf(Staff("a", 0), Staff("b", 1), Staff("c", 2), Staff("d", 3), Staff("e", 4), Staff("f", 5))
        // a=Q, b=R, c=S, d=T, e=U, f=U（Uが2人＝過剰=末端の余裕）
        val schedule = listOf(listOf(2), listOf(3), listOf(4), listOf(5), listOf(6), listOf(6))
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(6) { List(7) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("P不足(covU>0)が前提", (before.breakdown["covU"] ?: 0) > 0)

        val chain = findCovUChain(p, sched, 1, 0, Random(13))
        assertNotNull("5人連鎖が見つかること", chain)
        assertEquals("深さ5(5手)の連鎖であること", 5, chain!!.size)
        for (mv in chain) sched[mv[0]][mv[1]] = mv[2]

        assertEquals(
            "a=P,b=Q,c=R,d=S,e=T,f=U(不変)",
            listOf(1, 2, 3, 4, 5, 6),
            listOf(sched[0][0], sched[1][0], sched[2][0], sched[3][0], sched[4][0], sched[5][0]),
        )
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("covU が解消されること", 0, after.breakdown["covU"] ?: 0)
        assertTrue("hard は悪化しない", after.hard <= before.hard)
    }

    // [3.232.0/ドッグフーディングで発見・maxDepth既定引き上げの検証] 深さ5(旧既定の上限)を1手超える
    // 深さ6の連鎖のみに解がある盤面（chainFillSolvesDepth5Cascadeと同型を1段延長）。旧既定
    // maxDepth=5を明示指定すると見つからず、新既定((p.K-1).coerceAtLeast(1)=7)なら見つかることを固定する。
    @Test
    fun chainFillFindsDepth6ChainOnlyReachableWithRaisedDefaultMaxDepth() {
        val shifts = listOf(
            Shift("休", "休", "", ""), Shift("P", "P", "1", ""), Shift("Q", "Q", "1", ""),
            Shift("R", "R", "1", ""), Shift("S", "S", "1", ""), Shift("T", "T", "1", ""),
            Shift("U", "U", "1", ""), Shift("V", "V", "1", ""),
        )
        val groups = listOf(
            Group("G0", "G0"), Group("G1", "G1"), Group("G2", "G2"), Group("G3", "G3"),
            Group("G4", "G4"), Group("G5", "G5"), Group("G6", "G6"),
        )
        // G0=休/P/Q ... G5=休/U/V, G6=休/V（末端）。各群は隣接シフトのみ担当可＝連鎖を1本道にする。
        val groupShift = listOf(
            listOf(1, 1, 1, 0, 0, 0, 0, 0),
            listOf(1, 0, 1, 1, 0, 0, 0, 0),
            listOf(1, 0, 0, 1, 1, 0, 0, 0),
            listOf(1, 0, 0, 0, 1, 1, 0, 0),
            listOf(1, 0, 0, 0, 0, 1, 1, 0),
            listOf(1, 0, 0, 0, 0, 0, 1, 1),
            listOf(1, 0, 0, 0, 0, 0, 0, 1),
        )
        val staff = listOf(
            Staff("a", 0), Staff("b", 1), Staff("c", 2), Staff("d", 3),
            Staff("e", 4), Staff("f", 5), Staff("g", 6), Staff("h", 6),
        )
        // a=Q,b=R,c=S,d=T,e=U,f=V,g=V,h=V（Vが3人＝過剰=末端の余裕、g/hがG6=休/Vのみ）
        val schedule = listOf(
            listOf(2), listOf(3), listOf(4), listOf(5), listOf(6), listOf(7), listOf(7), listOf(7),
        )
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(7) { List(8) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = cachedProblem(st)
        assertEquals("K=8シフト(休+P..V)である前提", 8, p.K)
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("P不足(covU>0)が前提", (before.breakdown["covU"] ?: 0) > 0)

        val capped = findCovUChain(p, sched, 1, 0, Random(13), maxDepth = 5)
        assertNull("旧既定maxDepth=5では深さ6の連鎖に届かないこと", capped)

        val chain = findCovUChain(p, sched, 1, 0, Random(13))
        assertNotNull("新既定((p.K-1)=7)なら深さ6の連鎖が見つかること", chain)
        assertEquals("深さ6(6手)の連鎖であること", 6, chain!!.size)
        for (mv in chain) sched[mv[0]][mv[1]] = mv[2]
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("covU が解消されること", 0, after.breakdown["covU"] ?: 0)
        assertTrue("hard は悪化しない", after.hard <= before.hard)
    }

    // 8/11 相当（深さ1）: covU=Cｵ、唯一の Cｵ可能者が過剰シフト B4 に在勤 → 1手で covU/covO 同時解消。
    @Test
    fun chainFillSolvesDepth1FromOvercoveredShift() {
        val shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("Co", "Co", "1", ""),   // need1=1
            Shift("B4", "B4", "1", ""),   // need1=1（現状2＝過剰）
        )
        val groups = listOf(Group("G0", "G0"))
        val groupShift = listOf(listOf(1, 1, 1))   // 全員 休/Co/B4 可
        val staff = listOf(Staff("モニカ", 0), Staff("a", 0), Staff("b", 0))
        val schedule = listOf(
            listOf(2), // モニカ = B4
            listOf(2), // a = B4（B4 が2人＝過剰）
            listOf(0), // b = 休
        )
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(1) { List(3) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        // Co(shift1) covU。ただし 休のbが Co を担当可能なので、休→勤務でも解けるが、連鎖は過剰B4からの
        // 1手（深さ1）も踏める。ここでは連鎖が非nullで covU を解消することを確認（どの1手でも可）。
        val chain = findCovUChain(p, sched, 1, 0, Random(7))
        assertNotNull(chain)
        for (mv in chain!!) sched[mv[0]][mv[1]] = mv[2]
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals(0, after.breakdown["covU"] ?: 0)
        assertTrue(after.hard <= before.hard)
    }

    // [三連/五連など任意長への配慮] 長さ2の枝刈りしか見ていないと、三連禁止(P,P,P)を新たに作る手を
    // 素通ししてしまう。a=[P,Q,P]・b=[休,休,休] で day1 の P不足を埋める候補は a/b の2人だが、
    // a の day1 を P にすると day0,1,2=P,P,P で三連禁止に触れる。b は無関係なので安全。
    // findCovUChain が a を枝刈りし、b だけを使う連鎖（1手）に着地することを確認する。
    // [隣接日調整の対象外であることを保証] b を day0/day2 に希望固定し、a の禁止連続を隣接日調整
    //   （下記 chainFillResolvesC3nBlockViaAdjacentDayFix）で回避できないようにする。これが無いと
    //   b が a の day0/day2 の肩代わりに使えてしまい、結果が非決定的（RNG次第でaかbか）になる。
    @Test
    fun chainFillAvoidsTripleForbiddenRun() {
        // shift: 0=休 1=P(need1・cons3n=P,P,P三連禁止) 2=Q
        val shifts = listOf(Shift("休", "休", "", ""), Shift("P", "P", "1", ""), Shift("Q", "Q", "", ""))
        val groups = listOf(Group("G0", "G0"))
        val groupShift = listOf(listOf(1, 1, 1))
        val staff = listOf(Staff("a", 0), Staff("b", 0))
        val schedule = listOf(
            listOf(1, 2, 1),   // a: P, Q, P（day1 を P にすると P,P,P で三連禁止）
            listOf(0, 0, 0),   // b: 休, 休, 休（day1 を P にしても無関係で安全）
        )
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-03",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(1) { List(3) { "" } },
            schedule = schedule, wishes = mapOf("1,0" to 0, "1,2" to 0), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = listOf(C3Row(listOf("P", "P", "P"))),
            cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        // 前提: day1 は P が0人(covU)・三連はまだ未成立（a は P,Q,P で Q が割って入っている）。
        assertTrue("day1 の P不足(covU)が前提", (before.breakdown["covU"] ?: 0) > 0)
        assertEquals(0, before.breakdown["c3n"] ?: 0)

        val chain = findCovUChain(p, sched, 1, 1, Random(3))
        assertNotNull("day1 の P不足を埋める連鎖が見つかること", chain)
        for (mv in chain!!) sched[mv[0]][mv[1]] = mv[2]

        assertEquals("a の行は不変（三連トラップを避けて動かさない）", listOf(1, 2, 1), sched[0].toList())
        assertEquals("b の day1 が P で埋まる", 1, sched[1][1])
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("covU が解消されること", 0, after.breakdown["covU"] ?: 0)
        assertEquals("三連禁止(c3n)を新たに作らないこと", 0, after.breakdown["c3n"] ?: 0)
    }

    // [禁止連続の回避=隣接日調整] a=[P,Q,P]・b=[休,休,休]で day1 の P不足を埋めたいが、b は day1 に
    // 希望固定（休）で使えず、直接候補は a のみ。a を day1=P にすると三連禁止に触れるため、
    // findCovUChain が a の day0 を休へ変えて三連を崩し、空いた day0 の P不足を b で玉突き充填する
    // （2段の合流手）ことを確認する。ユーザー指摘「禁止連続の並びにならないようにする」への対応。
    @Test
    fun chainFillResolvesC3nBlockViaAdjacentDayFix() {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("P", "P", "1", ""), Shift("Q", "Q", "", ""))
        val groups = listOf(Group("G0", "G0"))
        val groupShift = listOf(listOf(1, 1, 1))
        val staff = listOf(Staff("a", 0), Staff("b", 0))
        val schedule = listOf(
            listOf(1, 2, 1),   // a: P, Q, P
            listOf(0, 0, 0),   // b: 休, 休, 休
        )
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-03",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(1) { List(3) { "" } },
            schedule = schedule,
            wishes = mapOf("1,1" to 0),   // b は day1 のみ希望固定(休)＝直接候補から除外。day0/day2は自由。
            staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = listOf(C3Row(listOf("P", "P", "P"))),
            cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("day1 の P不足(covU)が前提", (before.breakdown["covU"] ?: 0) > 0)
        assertEquals(0, before.breakdown["c3n"] ?: 0)
        assertTrue("bはday1希望固定で直接候補から除外される前提", p.wishLocked(1, 1))

        val chain = findCovUChain(p, sched, 1, 1, Random(5))
        assertNotNull("隣接日調整＋玉突きでday1のP不足を埋める連鎖が見つかること", chain)
        for (mv in chain!!) sched[mv[0]][mv[1]] = mv[2]

        assertEquals("a の day1 が P で埋まる（禁止連続を回避しつつ使われる）", 1, sched[0][1])
        assertEquals("a の day0 が休へ変わり三連を崩す", 0, sched[0][0])
        assertEquals("空いた day0 の P不足を b が玉突きで埋める", 1, sched[1][0])
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("covU が解消されること", 0, after.breakdown["covU"] ?: 0)
        assertEquals("三連禁止(c3n)を新たに作らないこと", 0, after.breakdown["c3n"] ?: 0)
        assertTrue("hard は悪化しない", after.hard <= before.hard)
    }

    // Problem.makesForbiddenRun 自体の直接検証（三連・五連）。
    @Test
    fun makesForbiddenRunDetectsTripleAndQuintuple() {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("P", "P", "", ""))
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("a", 0))
        fun stateWith(sched: List<Int>, cons3n: List<C3Row>) = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-0${sched.size}",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = List(1) { List(2) { "" } },
            schedule = listOf(sched), wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = cons3n, cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        // 三連禁止(P,P,P)。row = P,休,P,休,休（休=0,P=1）。
        val row3 = listOf(1, 0, 1, 0, 0)
        val p3 = cachedProblem(stateWith(row3, listOf(C3Row(listOf("P", "P", "P")))))
        val sc3 = row3.toIntArray()
        // position1(休)をPにすると positions0..2=P,P,P で三連成立。
        assertTrue("position1をPにすると三連禁止に触れる", p3.makesForbiddenRun(arrayOf(sc3), 0, 1, 1))
        // position3(休)をPにすると positions1..3=休,P,P / positions2..4=P,P,休 のどちらも三連に届かない。
        assertFalse("position3をPにしても三連には届かない", p3.makesForbiddenRun(arrayOf(sc3), 0, 3, 1))

        // 五連禁止(P×5)。row = P,P,休,P,P。position2(休)をPにすると全区間が P,P,P,P,P で五連成立。
        val row5 = listOf(1, 1, 0, 1, 1)
        val p5 = cachedProblem(stateWith(row5, listOf(C3Row(List(5) { "P" }))))
        assertTrue("position2をPにすると五連禁止に触れる", p5.makesForbiddenRun(arrayOf(row5.toIntArray()), 0, 2, 1))
    }

    // [C1にE11を反映] i の c1 不足(shift X必須)を直そうにも、その日 X に在勤中の直接交換相手がいない局面。
    // 旧C1Polishはここで諦めていたが、i を X へ動かし、空いた旧シフト A の穴を玉突き連鎖（findCovUChain と
    // 同じ機構）で埋め直せば解決できる: A(i の担当・唯一在勤=1人)が空く → B(過剰=2人)在勤の h が A へ補充。
    @Test
    fun c1PolishSolvesViaChainWhenNoDirectSwapPartner() {
        // shift: 0=休 1=X(c1対象・need無) 2=A(need1・iのみ在勤) 3=B(need1・過剰=2人在勤)
        val shifts = listOf(
            Shift("休", "休", "", ""), Shift("X", "X", "", ""),
            Shift("A", "A", "1", ""), Shift("B", "B", "1", ""),
        )
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"), Group("G2", "G2"))
        // G0(i)=休/X/A（Bはできない）, G1(h)=休/A/B（Aへ補充できる唯一の候補）, G2(h2)=休/Bのみ（Aは担当不可
        //   ＝h/h2を非対称にして解を一意にする。乱数シャッフル順に依らずhが選ばれることを検証できる）。
        val groupShift = listOf(listOf(1, 1, 1, 0), listOf(1, 0, 1, 1), listOf(1, 0, 0, 1))
        val staff = listOf(Staff("i", 0), Staff("h", 1), Staff("h2", 2))
        val schedule = listOf(listOf(2), listOf(3), listOf(3))   // i=A, h=B, h2=B（Bが2人＝過剰）
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(3) { List(4) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(C1Row("1", "X", "1")),   // 1日窓でXが1回以上＝毎日Xが必須
            cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val before = UnifiedViolationChecker.check(st, st.schedule.toIntArray2D())
        assertTrue("c1(Xの窓不足)が前提", (before.breakdown["c1"] ?: 0) > 0)
        // 前提: day0 に X在勤者がいない＝直接スワップ相手が存在しない（旧実装はここで頭打ち）。
        assertTrue("day0にX在勤者がいない(直接交換相手なし)が前提", st.schedule.none { it[0] == 1 })

        val r = C1WindowPolish.applyC1WindowPolish(st, st.schedule.toIntArray2D())
        val after = UnifiedViolationChecker.check(st, r.newSchedule)
        assertEquals("玉突き連鎖でc1不足が解消すること", 0, after.breakdown["c1"] ?: 0)
        assertEquals("i がXへ移ること", 1, r.newSchedule[0][0])
        assertEquals("h がAへ玉突きで補充されること", 2, r.newSchedule[1][0])
        assertEquals("h2 はB(不変・1人に減って丁度need1)", 3, r.newSchedule[2][0])
        assertTrue("hard は悪化しない", after.hard <= before.hard)
        assertTrue("total も悪化しない（keep-best）", after.total <= before.total)
    }

    // [ユーザー指摘の検証=「Dﾃ-Dﾃ」仮説] 「移動先の翌日が別の禁止連続に触れるなら、同じシフトを
    //   もう一度充てる(例: 夜勤の連続=Dﾃ-Dﾃ)ことを試してみては」という提案の検証。実データ(cons3n=
    //   Dﾃ-A4/Aｱ/Cｵ/Cｱ/B4/Cｳ/B1・Dﾃ-休-A4/Aｱ の3連含む)を Python でリプレイしたところ、対象3名
    //   （実機ログの金沢勇輝=Dﾃ-Cｳ・モニカ=Dﾃ-休-Aｱ・アリフ=Dﾃ-Cｱ）はいずれも tryFixC3nViaAdjacentDay
    //   の altOrder 走査（休優先→担当可能シフト全種）で既に解決できることを確認。「同じシフトの
    //   繰り返し」は altOrder の2番目（休の次）に自然に含まれるため自動的に試されるが、単発では
    //   万能ではない: 翌々日が別の禁止連続の相手（例 Dﾃ-Aｱ）だと「Dﾃ-Dﾃ」自体が新たな禁止連続を
    //   翌日側へ1日ずらすだけで終わる。本テストは、この「繰り返しでは解決しないが、全候補探索の
    //   結果、別の安全なシフトで解決する」ケースを最小構成で再現し固定する:
    //   shift: 0=休 1=P(need1・充填対象=「Dﾃ」役) 2=N(P-Nが2連禁止＝「Aｱ」役) 3=O(禁止連続と無関係="有"役)
    //   cons3n = [P,N](2連) と [P,休,N](3連)。i の day1(j)=休(現在)・day2(j+1)=休・day3(j+2)=N。
    //   day1をPにすると [P,休,N] の3連禁止に触れる → 隣接日調整は day2 を別シフトへ変えようとする。
    //   1回目の試み(繰り返し=P, 「Dﾃ-Dﾃ」相当)は day2,3=[P,N] で2連禁止に新たに触れて失敗 → 続けて
    //   day2=N も day1,2=[P,N]で直ちに失敗 → day2=O でようやく成立（P-O・O-N とも禁止パターン外）。
    @Test
    fun chainFillAdjacentFixTriesRepeatShiftThenFallsBackToSafeAlternative() {
        val shifts = listOf(
            Shift("休", "休", "", ""), Shift("P", "P", "", ""),
            Shift("N", "N", "", ""), Shift("O", "O", "", ""),
        )
        val groups = listOf(Group("G0", "G0"))
        val groupShift = listOf(listOf(1, 1, 1, 1))
        val staff = listOf(Staff("i", 0))
        val schedule = listOf(listOf(0, 0, 0, 2))   // day0=休, day1(j)=休, day2(j+1)=休, day3(j+2)=N
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-04",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(1) { List(4) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            // [S=1のためP(need1)は「day1のみ」に限定] 基本need1を空にし、needDay1でday1だけ1を要求。
            //   基本need1="1"をシフト全日一律にすると、単一staffでは他日のPも埋まらずcovUが残ってしまう。
            needDay1 = mapOf("1,1" to "1"), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = listOf(C3Row(listOf("P", "N")), C3Row(listOf("P", "休", "N"))),
            cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("day1 の P不足(covU)が前提", (before.breakdown["covU"] ?: 0) > 0)
        assertEquals(0, before.breakdown["c3n"] ?: 0)
        assertTrue("day1をPにすると[P,休,N]の3連禁止に触れる前提", p.makesForbiddenRun(sched, 0, 1, 1))
        // 「繰り返し(Dﾃ-Dﾃ相当)」= day2もPにする案は、day2,3=[P,N]で新たな2連禁止に触れ単体では不成立。
        val repeatSched = arrayOf(sched[0].copyOf())
        repeatSched[0][2] = 1
        assertTrue("繰り返し(day2もP)は day2,3=[P,N]で新たな禁止連続に触れる", p.makesForbiddenRun(repeatSched, 0, 2, 1))

        val chain = findCovUChain(p, sched, 1, 1, Random(9))
        assertNotNull("altOrder走査で「Dﾃ-Dﾃ」を試した上で別の安全なシフトへ着地すること", chain)
        for (mv in chain!!) sched[mv[0]][mv[1]] = mv[2]

        assertEquals("i の day1 が P で埋まる", 1, sched[0][1])
        assertEquals("day2 は繰り返し(P)でもN でもなく安全なOへ", 3, sched[0][2])
        val after = UnifiedViolationChecker.check(st, sched)
        assertEquals("covU が解消されること", 0, after.breakdown["covU"] ?: 0)
        assertEquals("三連禁止(c3n)を新たに作らないこと", 0, after.breakdown["c3n"] ?: 0)
        assertTrue("hard は悪化しない", after.hard <= before.hard)
    }

    // [敵対的レビュー修正の回帰] tryComplete の静的 cnt[] 補正が「到着」だけでなく「離脱」も
    // 両方加味しないと、実際には別の covU を作る連鎖を安全と誤判定しうる（false accept）ことを固定する。
    // P(root,need1,0人)←Q(need2,2人=a,k1 在勤)←M(need1,1人=g 在勤) の3段連鎖: a が Q→P、g が M→Q、
    // k1 が Q→M と動く手は P を解消するが、正味では a と k1 の2人が Q を抜け g の1人しか戻らないため
    // Q が need2→1人 に壊れる。祖先の「到着」(g→Q)のみを補正し「離脱」(a→Q)を見逃す半端な修正だと
    // Q のtrueCntを3(過大)と誤算し、この有害な連鎖を安全と判定してしまう。正しい修正は到着と離脱を
    // 両方加味し trueCnt=2(不変)と正しく算出してこの連鎖を却下する。
    @Test
    fun chainFillNeverBreaksAnotherShiftViaStaleAncestorCount() {
        val shifts = listOf(
            Shift("休", "休", "", ""), Shift("P", "P", "1", ""),
            Shift("Q", "Q", "2", ""), Shift("M", "M", "1", ""),
        )
        val groups = listOf(Group("G0", "G0"), Group("G1", "G1"))
        // G0(a)=休/P/Q, G1(k1,g)=休/Q/M（k1・gは同一群＝同じ担当可能シフトで対称）
        val groupShift = listOf(listOf(1, 1, 1, 0), listOf(1, 0, 1, 1))
        val staff = listOf(Staff("a", 0), Staff("k1", 1), Staff("g", 1))
        val schedule = listOf(listOf(2), listOf(2), listOf(3))   // a=Q, k1=Q（Qが2人=need2ちょうど）, g=M
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(2) { List(4) { "" } },
            schedule = schedule, wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val p = cachedProblem(st)
        val sched = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, sched)
        assertTrue("P不足(covU>0)が前提", (before.breakdown["covU"] ?: 0) > 0)
        assertNull("Qはちょうどneed2で充足済み(covU無し)が前提", before.needViolations["2,0"])

        val chain = findCovUChain(p, sched, 1, 0, Random(5))
        if (chain != null) {
            // 万一チェーンが見つかった場合でも、適用後に他シフト(Q含む)へ新たな covU を作らないこと
            // （見つからず null が最も一般的だが、候補順序の実装詳細に依存しないよう安全性で担保する）。
            for (mv in chain) sched[mv[0]][mv[1]] = mv[2]
            val after = UnifiedViolationChecker.check(st, sched)
            assertTrue("連鎖適用後に新たな covU を作らないこと", (after.breakdown["covU"] ?: 0) <= (before.breakdown["covU"] ?: 0))
        }
        // このデータでは有効な安全な連鎖が存在しない設計のため、見つからない(null)ことが期待値。
        assertNull("Qを壊す唯一の経路しか無いため連鎖は見つからない(null)であること", chain)
    }

    // [頭打ち調査・3.218.0] rangeAvoid が無いと、findCovUChain は候補のコストを一切見ず rng順で最初に
    // 完成した候補をそのまま返す。桒澤美幸のAｱ超過(3.215.0 RangePolish)が研磨されずに残る実例を追跡した
    // 結果、「候補自身の新規range(high)違反を招く」手を引くと、isBetterが改善なしとして却下し、その日は
    // 二度と試行されない（1日1回きりの呼出のため）ことが根本原因と判明。
    // 盤面: 休(0)/P(1)の2シフト。day0=Pがcovid(covU)、bad/goodともにday0=休で担当可能・希望非固定・
    // 禁止連続なし＝どちらも構造的に同格の候補（1手で即完成）。bad は day1 に既にP保有＋staffRange hi=1
    // のため、day0のPを埋めると2>hi=1で自身の新規high違反を招く。good は無制限（staffRange未設定）。
    // rangeAvoid を渡すと、rng順に関わらず必ず good が選ばれることを複数seedで固定する
    // （渡さない場合は shuffle 次第で bad が選ばれ得ることも併せて確認＝旧実装の脆さの実証）。
    private fun rangeAvoidState(): MagiState {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("P", "P", "1", ""))
        val groups = listOf(Group("G0", "G0"))
        val groupShift = listOf(listOf(1, 1))   // 休/Pとも担当可
        val staff = listOf(Staff("bad", 0), Staff("good", 0))
        val schedule = listOf(
            listOf(0, 1), // bad = 休, P（day1に既にP保有）
            listOf(0, 0), // good = 休, 休
        )
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-02",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(1) { List(2) { "" } },
            schedule = schedule, wishes = emptyMap(),
            staffRange = mapOf("0,1" to Range(lo = "", hi = "1")),   // bad(index0)のP上限=1
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
    }

    @Test
    fun chainFillRangeAvoidAlwaysPrefersCandidateWithoutOwnRangeViolation() {
        val st = rangeAvoidState()
        val p = cachedProblem(st)
        val before = UnifiedViolationChecker.check(st, st.schedule.toIntArray2D())
        assertTrue("day0のP不足(covU>0)が前提", (before.breakdown["covU"] ?: 0) > 0)

        fun exceedsHi(staffIdx: Int, work: Array<IntArray>, fillShift: Int): Boolean {
            val hi = p.rangeHi[staffIdx][fillShift]
            if (hi == Int.MAX_VALUE) return false
            var c = 0
            for (jj in 0 until p.T) if (work[staffIdx][jj] == fillShift) c++
            return c + 1 > hi
        }

        var badPickedWithoutAvoid = false
        for (seed in 0..15) {
            val sched = st.schedule.toIntArray2D()
            val chain = findCovUChain(p, sched, 1, 0, Random(seed.toLong()))
            assertNotNull("候補が2人ともいるため必ず連鎖(1手)が見つかること seed=$seed", chain)
            assertEquals(1, chain!!.size)
            if (chain[0][0] == 0) badPickedWithoutAvoid = true

            val schedWithAvoid = st.schedule.toIntArray2D()
            val chainWithAvoid = findCovUChain(
                p, schedWithAvoid, 1, 0, Random(seed.toLong()),
                rangeAvoid = { staffIdx, fillShift -> exceedsHi(staffIdx, schedWithAvoid, fillShift) },
            )
            assertNotNull("rangeAvoid指定時も連鎖は見つかること seed=$seed", chainWithAvoid)
            assertEquals(
                "rangeAvoid指定時はrng順に関わらずgood(index1)が選ばれること seed=$seed",
                1, chainWithAvoid!![0][0],
            )
        }
        assertTrue(
            "rangeAvoid無しではrng順次第でbad(index0, 自身の新規high違反を招く候補)が選ばれ得ること" +
                "（旧実装の脆さの実証。全seedでgoodのみ選ばれるなら本テストの前提が崩れている）",
            badPickedWithoutAvoid,
        )
    }
}
