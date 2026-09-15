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
 * 違反連結成分修復（Iteration 2 第一弾）。材料は各パスの拒否候補、採用は正式チェッカー＋厳密ピン検査。
 * 盤面は CombinatorialRepairTest と同じ検証済み最小盤面（X の P 超過と Y の D 不足は単独ではタイで不採用、束ねると apt が 2 件消える）。
 */
class ViolationComponentRepairTest {
    private fun isBetterLocal(a: ViolationReport, b: ViolationReport): Boolean {
        if (a.hard != b.hard) return a.hard < b.hard
        if (a.total != b.total) return a.total < b.total
        return a.weightedScore < b.weightedScore
    }

    private fun combineTwoRejectedState(): MagiState {
        val shifts = listOf(Shift("休", "休", "", ""), Shift("P", "P", "", ""), Shift("Qres", "Qres", "", ""), Shift("D", "D", "", ""))
        val groups = listOf(Group("G0", "G0"))
        val staff = listOf(Staff("X", 0), Staff("Y", 0), Staff("W1", 0), Staff("W2", 0))
        return MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = shifts, groups = groups, staff = staff, use2Patterns = false,
            groupShift = listOf(listOf(1, 1, 1, 1)), groupShiftApt = listOf(listOf("", "0", "", "1")),
            schedule = listOf(listOf(1), listOf(2), listOf(0), listOf(0)), wishes = emptyMap(),
            // [3.541.0] W2 の D は上限 0 でなく 0〜1。fair v2 は担当不可(上限0)で 0 回の職員を母集団から外すため、上限 0 のままだと
            //   D バケットが Y 1 人＝偏差 0 になり、Y 単独の移動が「タイ」でなく改善になって結合の検証にならない。
            staffRange = mapOf("0,3" to Range("", "0"), "2,3" to Range("", "0"), "3,3" to Range("0", "1")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(),
            cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            // [3.522.0] apt重み1→4でX/Y単独移動が「タイ」でなくなったため、c41行を4重複させ
            //   1違反=4件計上にして単独では確実に悪化するよう調整（CombinatorialRepairTestと同型）。
            cons41 = listOf(
                C41Row("G0", "Qres", "1", "1"), C41Row("G0", "Qres", "1", "1"),
                C41Row("G0", "Qres", "1", "1"), C41Row("G0", "Qres", "1", "1"),
            ),
            cons42 = emptyList(),
        )
    }

    @Test
    fun combinesCandidatesRejectedByDifferentPassesIntoOneTransaction() {
        val st = combineTwoRejectedState()
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        val before = UnifiedViolationChecker.check(st, work)
        assertEquals(2, before.breakdown["apt"] ?: 0)
        val candX = CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 2)), "AptChain", "X")   // apt パスの拒否候補
        val candY = CombinatorialRepair.Candidate(listOf(intArrayOf(1, 0, 3)), "tryRelocate", "Y") // range パスの拒否候補
        run {
            val w = work.map { it.clone() }.toTypedArray(); w[0][0] = 2
            assertFalse("単独は不採用(タイ)", isBetterLocal(UnifiedViolationChecker.check(st, w), before))
        }
        // 拒否候補の結合だけを見る（起点生成は別テスト）。
        val r = ViolationComponentRepair.repair(st, work, listOf(candX, candY), ViolationComponentRepair.Params(generateFromAnchors = false))
        val after = UnifiedViolationChecker.check(st, r.newSchedule)
        assertEquals(1, r.applied)
        assertEquals(0, after.breakdown["apt"] ?: -1)
        assertEquals(0, after.hard)
        assertEquals(2, r.newSchedule[0][0]); assertEquals(3, r.newSchedule[1][0])
        assertTrue(r.logs.first().message, r.logs.first().message.contains("採用1件"))
        assertTrue(r.logs.first().message, r.logs.first().message.contains("k=2"))
    }

    @Test
    fun neverWorsensAndLeavesBoardUntouchedWhenNoTransactionImproves() {
        val st = combineTwoRejectedState()
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        val snapshot = work.map { it.clone() }.toTypedArray()
        // 単独でも束ねても悪化する候補（X と W1 を D へ＝staffRange hi=0 を破り high が増える。チェッカーで 3 通り全部 w 悪化を確認済み。
        //   Y→休 は fair が 2 減って c41 の 1 増を上回る＝実は改善なので、この対照には入れない）
        val bad = listOf(
            CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 3)), "a", "X→D"),
            CombinatorialRepair.Candidate(listOf(intArrayOf(2, 0, 3)), "c", "W1→D"),
        )
        val r = ViolationComponentRepair.repair(st, work, bad, ViolationComponentRepair.Params(generateFromAnchors = false))
        assertEquals(0, r.applied)
        for (i in snapshot.indices) assertTrue(snapshot[i].contentEquals(r.newSchedule[i]))
        assertEquals(r.beforeTotal, r.afterTotal)
    }

    @Test
    fun anchorSetsPutPatchesTouchingTheViolationFirstAndHelpersSharingAStaffOrDayAfter() {
        val st = combineTwoRejectedState()
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        val rep = UnifiedViolationChecker.check(st, work)
        val anchors = ViolationComponentRepair.anchors(rep)
        assertTrue("apt の回数違反が起点になる", anchors.any { it.family.startsWith("apt") && it.staff == 0 })
        fun patch(vararg cells: IntArray) = ViolationComponentRepair.Patch(cells.toList(), "m", "")
        val x = patch(intArrayOf(0, 0, 2))           // X を Qres へ＝起点 apt(X) を触る主候補
        val y = patch(intArrayOf(1, 0, 3))           // Y を D へ＝X と日 0 を共有する助候補
        val far = patch(intArrayOf(3, 0, 1))         // W2＝日 0 を共有するので助候補
        val sets = ViolationComponentRepair.anchorSets(anchors.filter { it.staff == 0 && it.day < 0 }, listOf(x, y, far), cap = 2)
        assertEquals(1, sets.size)
        assertEquals(listOf(0, 1), sets[0].second)   // 主候補が先、助候補は cap まで
        assertTrue(x.overlaps(patch(intArrayOf(0, 0, 3))))
        assertFalse(x.overlaps(y))
    }

    @Test
    fun qualityVectorOrdersHardCountBeforeAnyWeight() {
        val st = combineTwoRejectedState()
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        val rep = UnifiedViolationChecker.check(st, work)
        val qv = ViolationComponentRepair.QualityVector.of(rep, changedCells = 3)
        assertEquals(rep.hard, qv.hardCount)
        assertEquals(0.0, qv.hardWeighted, 0.0)
        assertTrue(qv.softWeighted > 0.0)
        assertEquals(3, qv.changedCells)
        val worseHard = qv.copy(hardCount = 1, softWeighted = 0.0, changedCells = 0)
        assertTrue(qv < worseHard)
        val fewerChanges = qv.copy(changedCells = 1)
        assertTrue(fewerChanges < qv)
    }

    @Test
    fun combineAndApplyHandsUnusedCandidatesToTheLeftoverSink() {
        val st = combineTwoRejectedState()
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        val before = UnifiedViolationChecker.check(st, work)
        // 同じセルを触る 2 候補＝重複セルで結合されず、どちらも残る。
        val dup = listOf(
            CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 1)), "dup", "1"),
            CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 3)), "dup", "2"),
        )
        val leftover = ArrayList<CombinatorialRepair.Candidate>()
        CombinatorialRepair.combineAndApply(st, work, before, dup, ::isBetterLocal, leftover = leftover)
        assertEquals(2, leftover.size)
    }

    @Test
    fun postOptimizationWithComponentRepairEnabledRunsAndNeverWorsensTheBoard() {
        val st = combineTwoRejectedState()
        val sched = st.schedule.map { it.toIntArray() }.toTypedArray()
        val before = UnifiedViolationChecker.check(st, sched)
        val params = V6HotfixPasses.PostOptimizationParams(componentRepairEnabled = true, maxRounds = 1)
        val r = V6HotfixPasses.runPostOptimization(st, sched.map { it.clone() }.toTypedArray(), "t", seed = 7L, params = params)
        assertTrue(r.report.hard <= before.hard)
        assertTrue(!betterReport(before, r.report))
    }

    /** [測定中/二車線ビーム] debtExploration有効時、debtLaneSlotsを設定しても既存の不変条件（退行しない・
     *  例外を出さない）が保たれる。効果自体（負債候補が飢餓せず生き残るか）はtools/loopで測る。 */
    @Test
    fun debtLaneSlotsRunsWithoutRegressionWhenDebtExplorationEnabled() {
        val st = combineTwoRejectedState()
        val sched = st.schedule.map { it.toIntArray() }.toTypedArray()
        val before = UnifiedViolationChecker.check(st, sched)
        val params = V6HotfixPasses.PostOptimizationParams(
            componentRepairEnabled = true, maxRounds = 1,
            componentRepair = ViolationComponentRepair.Params(debtExploration = true, debtLaneSlots = 2),
        )
        val r = V6HotfixPasses.runPostOptimization(st, sched.map { it.clone() }.toTypedArray(), "t", seed = 7L, params = params)
        assertTrue(r.report.hard <= before.hard)
        assertTrue(!betterReport(before, r.report))
    }

    /** [3.512.3/バグ修正] trimFrontier: 負債枠(debtLaneSlots)は予約であって専有ではない＝負債候補が
     *  debtCap 未満（0件含む）のときは、余った枠を非負債候補へ返す。修正前は非負債候補の枠が
     *  beamWidth-debtCap に固定され、負債候補が無くてもビーム幅が実質縮小していた（純粋な退行）。 */
    @Test
    fun trimFrontierReturnsUnusedDebtSlotsToNormalCandidates() {
        val params = ViolationComponentRepair.Params(debtExploration = true, debtLaneSlots = 2, beamWidth = 8)
        // baseEst=100。非負債候補(est<=100)を10件、負債候補(est>100)は0件。
        val normals = (0 until 10).map { ViolationComponentRepair.Node(intArrayOf(it), 100L - it) }
        val trimmed = ViolationComponentRepair.trimFrontier(normals, baseEst = 100L, params = params)
        assertEquals("負債候補0件なら非負債候補がbeamWidth全体を使う（6件に縮小しない）", 8, trimmed.size)
        assertTrue("非負債候補のみ", trimmed.all { it.est <= 100L })
    }

    /** [3.512.3] 負債候補が debtCap 以上あるときは、従来どおり非負債候補は beamWidth-debtCap 件に絞られる。 */
    @Test
    fun trimFrontierCapsNormalCandidatesWhenDebtCandidatesFillTheirSlots() {
        val params = ViolationComponentRepair.Params(debtExploration = true, debtLaneSlots = 2, beamWidth = 8)
        val normals = (0 until 10).map { ViolationComponentRepair.Node(intArrayOf(it), 100L - it) }
        val debts = (0 until 5).map { ViolationComponentRepair.Node(intArrayOf(100 + it), 101L + it) }
        val trimmed = ViolationComponentRepair.trimFrontier(normals + debts, baseEst = 100L, params = params)
        assertEquals(8, trimmed.size)
        assertEquals("負債候補は予約枠2件まで", 2, trimmed.count { it.est > 100L })
        assertEquals("非負債候補はbeamWidth-debtCap=6件まで", 6, trimmed.count { it.est <= 100L })
    }

    /** [3.512.3] トリム後は est 昇順（同点は ids 辞書式）に再整列される＝負債候補が混ざっても
     *  search() の展開順（評価予算切れの打ち切り基準）が壊れない。 */
    @Test
    fun trimFrontierReturnsResultSortedByNodeOrderEvenWhenDebtSlotsAreUsed() {
        val params = ViolationComponentRepair.Params(debtExploration = true, debtLaneSlots = 2, beamWidth = 4)
        val nodes = listOf(
            ViolationComponentRepair.Node(intArrayOf(3), 99L), ViolationComponentRepair.Node(intArrayOf(1), 105L),
            ViolationComponentRepair.Node(intArrayOf(2), 100L), ViolationComponentRepair.Node(intArrayOf(4), 101L),
        )
        val trimmed = ViolationComponentRepair.trimFrontier(nodes, baseEst = 100L, params = params)
        assertEquals(listOf(99L, 100L, 101L, 105L), trimmed.map { it.est })
    }

    /** [測定中/bestOfK] bestOfK>=2 でも既存の不変条件（退行しない・例外を出さない）が保たれる。
     *  効果自体（順序非依存の採用が iter17 の無効さを覆すか）は tools/loop で測る。 */
    @Test
    fun bestOfKRunsWithoutRegressionAndPicksAnObjectivelyBetterResult() {
        val st = combineTwoRejectedState()
        val sched = st.schedule.map { it.toIntArray() }.toTypedArray()
        val before = UnifiedViolationChecker.check(st, sched)
        val params = V6HotfixPasses.PostOptimizationParams(
            componentRepairEnabled = true, maxRounds = 1,
            componentRepair = ViolationComponentRepair.Params(familyPriorityScoring = true, bestOfK = 3),
        )
        val r = V6HotfixPasses.runPostOptimization(st, sched.map { it.clone() }.toTypedArray(), "t", seed = 7L, params = params)
        assertTrue(r.report.hard <= before.hard)
        assertTrue(!betterReport(before, r.report))
    }

    /** [Iteration 3] 単独で厳密ピン（lo==hi）を崩す候補は、同じ集合に逆向きの相方が無ければ最初から外す（推定予算を有効な枝へ）。 */
    @Test
    fun lonePinBreakersAreDroppedBeforeTheSearch() {
        val st = combineTwoRejectedState().let { s -> s.copy(staffRange = s.staffRange + ("1,2" to Range("1", "1"))) }   // Y の Qres を 1 回に固定
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        val lone = CombinatorialRepair.Candidate(listOf(intArrayOf(1, 0, 3)), "range", "Y→D")     // Qres 1→0＝ピンを崩す。相方なし
        val other = CombinatorialRepair.Candidate(listOf(intArrayOf(0, 0, 2)), "apt", "X→Qres")   // ピンには触れない
        val r = ViolationComponentRepair.repair(st, work, listOf(lone, other))
        assertTrue(r.logs.first().message, r.logs.first().message.contains("相方なし除外1"))
        assertEquals(2, r.newSchedule[1][0])   // Y の Qres は動いていない
    }

    /** [Iteration 3] 構造的に埋められない人員不足（covU）の起点は末尾へ回す（解ける HARD を先に）。 */
    @Test
    fun infeasibleCoverageAnchorsAreOrderedLast() {
        val a = ViolationComponentRepair.Anchor(true, "covU", -1, 3, shift = 1)
        val b = ViolationComponentRepair.Anchor(true, "covU", -1, 5, shift = 1)
        val rep = ViolationReport(
            violations = emptyMap(), needViolations = linkedMapOf("1,3" to "vio-covU", "1,5" to "vio-covU"), countViolations = emptyMap(),
            breakdown = emptyMap(), total = 2, hard = 2, soft = 0, weightedScore = 16000.0,
        )
        val ordered = ViolationComponentRepair.anchors(rep, infeasible = setOf(1 * 1000L + 3))
        assertEquals(listOf(5, 3), ordered.map { it.day })
        assertTrue(a.hard && b.hard)
    }

    /** [Iteration 4] 拒否候補が無くても、起点（人員不足）から作った単セル候補で直せる。 */
    @Test
    fun generatesCandidatesFromAnchorsWhenThePoolIsEmpty() {
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-01",
            shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "1", "")), groups = listOf(Group("G", "G")),
            staff = listOf(Staff("甲", 0), Staff("乙", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(0), listOf(0)), wishes = emptyMap(), staffRange = emptyMap(),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        assertEquals(1, UnifiedViolationChecker.check(st, work).hard)
        val r = ViolationComponentRepair.repair(st, work, emptyList())
        assertEquals(r.logs.first().message, 1, r.applied)
        assertEquals(0, UnifiedViolationChecker.check(st, r.newSchedule).hard)
        assertTrue(r.logs.first().message.contains("起点生成"))
        // 生成を切ると候補が無いので何もしない
        val off = ViolationComponentRepair.repair(st, work, emptyList(), ViolationComponentRepair.Params(generateFromAnchors = false))
        assertEquals(0, off.applied)
    }

    /** [族選択] familyScore を渡すとセグメント内だけ降順に並び替わる。同点は元のキー順（安定）。 */
    @Test
    fun familyScoreOrdersWithinSegmentByScoreDescendingWithStableTiesOnKeyOrder() {
        val rep = ViolationReport(
            violations = linkedMapOf("0,0" to "vio-c2", "0,1" to "vio-low", "0,2" to "vio-c2"),
            needViolations = emptyMap(), countViolations = emptyMap(),
            breakdown = emptyMap(), total = 3, hard = 0, soft = 3, weightedScore = 0.0,
        )
        val score: (ViolationComponentRepair.Anchor) -> Double = { a -> if (a.family == "low") 90.0 else 1.0 }
        val ordered = ViolationComponentRepair.anchors(rep, familyScore = score)
        assertEquals(listOf("low", "c2", "c2"), ordered.map { it.family })
        assertEquals(listOf(0 to 1, 0 to 0, 0 to 2), ordered.map { it.staff to it.day })
    }

    /** [族選択] 「解ける HARD 優先 → SOFT → blocked」のセグメント境界は familyScore の値に関わらず死守する（不変条件）。 */
    @Test
    fun hardSegmentNeverOvertakesSoftSegmentEvenWithHighFamilyScores() {
        val rep = ViolationReport(
            violations = linkedMapOf("0,0" to "vio-low", "0,1" to "vio-low", "0,2" to "vio-covU"),
            needViolations = emptyMap(), countViolations = emptyMap(),
            breakdown = emptyMap(), total = 3, hard = 1, soft = 2, weightedScore = 0.0,
        )
        val score: (ViolationComponentRepair.Anchor) -> Double = { a -> if (a.family == "low") 1_000_000.0 else 1.0 }
        val ordered = ViolationComponentRepair.anchors(rep, familyScore = score)
        assertTrue("HARD(covU)が先頭のまま", ordered.first().hard)
        assertEquals("covU", ordered.first().family)
        assertEquals(listOf("covU", "low", "low"), ordered.map { it.family })
    }

    /** [Iteration 6] 厳密ピン（lo==hi）を単独で崩す単セル候補は生成しない＝推定でピン枝刈りされる無駄弾が出ない。
     *  甲は A 1〜1 固定で 1 日目に A。3 日目の人員不足に対して甲の単セル（A 2 回）は作らず、行内の入替（1 日目⇄3 日目）に置き換える。乙の単セルで直る。 */
    @Test
    fun pinBreakingSinglesAreReplacedByRowSwapsAtGeneration() {
        val st = MagiState(
            startDate = "2026-08-01", endDate = "2026-08-03",
            shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "1", "")), groups = listOf(Group("G", "G")),
            staff = listOf(Staff("甲", 0), Staff("乙", 0)), use2Patterns = false,
            groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(1, 0, 0), listOf(0, 1, 0)), wishes = emptyMap(), staffRange = mapOf("0,1" to Range("1", "1")),
            needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
            cons41 = emptyList(), cons42 = emptyList(),
        )
        val work = st.schedule.map { it.toIntArray() }.toTypedArray()
        val r = ViolationComponentRepair.repair(st, work, emptyList())
        val msg = r.logs.first().message
        assertTrue(msg, msg.contains("ピン枝刈り0"))
        assertEquals(msg, 0, UnifiedViolationChecker.check(st, r.newSchedule).hard)
        assertEquals("甲の A は固定 1 回のまま", 1, r.newSchedule[0].count { it == 1 })
    }
}
