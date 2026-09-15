package com.magi.app.ui

import com.magi.app.v6.C1PlateauCause
import com.magi.app.v6.C1PlateauDiagnosis
import com.magi.app.v6.C1PlateauEntry
import com.magi.app.v6.C1PlateauEvidence
import com.magi.app.v6.CoverageDiagnosis
import com.magi.app.v6.CoverageSurplus
import com.magi.app.v6.IssueKind
import com.magi.app.v6.SettingIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.471.0] 分析タブのトリアージ分類を固定する。
 *
 * 一番大事な不変条件は **「c1 を族の名前だけで『自動で直る』側へ入れない」**こと。
 * 3.263.0 / 3.322.0 / 3.344.0 は「直せる」と言いすぎた表示を逆に直した版で、
 * ここで族による決め打ちに戻すとその3版の修正が全部無効になる。
 */
class AnalysisTriageTest {

    private fun ui(
        breakdown: Map<String, Int> = emptyMap(),
        hasResult: Boolean = false,
        issues: List<SettingIssue> = emptyList(),
        c1Plateau: C1PlateauDiagnosis? = null,
        coverage: CoverageDiagnosis? = null,
    ) = UiState(
        // [3.475.0/論理監査] analysisTriage の「計算済み」判定は hasResult でなく engineRan を読む
        //   （手編集だけで hasResult=true になり「計算後に残っている項目」と誤って語っていたため分離）。
        //   このテストの hasResult=true は「直後にエンジンが走った」状況を表すので engineRan も揃える。
        breakdown = breakdown, hasResult = hasResult, engineRan = hasResult, settingIssues = issues,
        c1Plateau = c1Plateau, coverageDiag = coverage,
    )

    private fun plateau(observed: Boolean) = C1PlateauDiagnosis(
        remainingC1 = 6,
        entries = if (!observed) emptyList() else listOf(
            C1PlateauEntry(
                staff = 0, staffName = "古泉 健一", shift = 1, shiftKigou = "Dﾃ", ruleIndex = 0,
                ruleLabel = "14日で2回以上", cause = C1PlateauCause.PIN_CONSTRAINED,
                evidence = C1PlateauEvidence.OBSERVED,
                rejectedByPin = 12, rejectedByScore = 0, noCandidate = 0, topScoreCulprits = emptyList(),
            ),
        ),
    )

    /** 実行前は診断が無い＝c1 は「エンジンが挑戦する項目」であって「壁」ではない。 */
    @Test fun beforeRunSoftFamiliesStayInTheSearchBand() {
        val t = analysisTriage(ui(breakdown = mapOf("c1" to 6, "c3" to 97, "weekly" to 186)))
        assertFalse(t.computed)
        assertTrue("実行前に壁と決めつけない", t.blockers.isEmpty())
        assertEquals(setOf("期間の制約", "必須の並び", "曜日の偏り"), t.searching.map { it.label }.toSet())
        assertTrue("断定しない注記が出る", t.searchNote.contains("最適化後も残る場合があります"))
    }

    /**
     * **この版の肝**: 同じ c1=6 でも、C1頭打ち診断が却下を実際に観測していれば上段（構造的な壁）へ、
     * 観測が無ければ（`causeUnknown`）中段のまま。族名では決めない。
     */
    @Test fun c1MovesToBlockersOnlyWhenThePlateauDiagnosisObservedSomething() {
        val observed = analysisTriage(ui(mapOf("c1" to 6), hasResult = true, c1Plateau = plateau(true)))
        assertEquals(listOf("期間の制約"), observed.blockers.map { it.label })
        assertTrue(observed.blockers.single().promoted)
        assertTrue("診断が語った理由をそのまま出す", observed.blockers.single().detail.contains("回数を固定"))
        assertTrue(observed.searching.isEmpty())

        val unknown = analysisTriage(ui(mapOf("c1" to 6), hasResult = true, c1Plateau = plateau(false)))
        assertTrue("観測が無いなら理由を語らない＝壁にしない", unknown.blockers.isEmpty())
        assertEquals(listOf("期間の制約"), unknown.searching.map { it.label })
    }

    /** 必須違反は診断の有無に関わらず常に上段。理由が無いときは何も主張しない（空文字）。 */
    @Test fun hardFamiliesAreAlwaysBlockersAndClaimNothingWithoutADiagnosis() {
        val t = analysisTriage(ui(mapOf("c3n" to 1, "covU" to 3), hasResult = true))
        assertEquals(listOf("人員不足", "禁止の並び"), t.blockers.map { it.label })   // 重み順 covU(8000) > c3n(7000)
        assertTrue("診断が無ければ理由は空", t.blockers.all { it.detail.isEmpty() })
    }

    /** 設定の破綻は種類ごとに1行へ畳む＝1人1行で伸びていたのを止める。 */
    @Test fun settingIssuesAreAggregatedByKind() {
        val issues = listOf(
            SettingIssue(IssueKind.RANGE, "古泉 健一 のB4", "…", "…"),
            SettingIssue(IssueKind.RANGE, "山本 昌幸 のB4", "…", "…"),
            SettingIssue(IssueKind.RANGE, "佐藤 直美 のB4", "…", "…"),
            SettingIssue(IssueKind.DEMAND, "Dﾃ の必要人数", "…", "…"),
        )
        val t = analysisTriage(ui(issues = issues))
        assertEquals(listOf("回数の設定" to 3, "必要人数の設定" to 1), t.issues.map { it.label to it.count })
        assertTrue("先頭2名＋ほか", t.issues.first().detail.contains("ほか"))
    }

    /** [3.515.2] 1件だけの種類は場所を切り詰めず理由まで出す（重複登録の警告が勤務表の違反に見えないように）。 */
    @Test fun singleIssueShowsFullWhereAndProblem() {
        val issues = listOf(
            SettingIssue(IssueKind.CONSTRAINT, "連続パターン「Dﾃ→A4」(禁止の並び)", "同じパターンが2重に登録されています", "…"),
        )
        val row = analysisTriage(ui(issues = issues)).issues.single()
        assertEquals("連続パターン「Dﾃ→A4」(禁止の並び) — 同じパターンが2重に登録されています", row.detail)
    }

    /** weekly/fair は L1 偏差なので「件」でなく「pt」。186件と読ませない。 */
    @Test fun distributionFamiliesUsePointsNotCounts() {
        val t = analysisTriage(ui(mapOf("weekly" to 186, "c1" to 6)))
        assertEquals("pt", t.searching.first { it.label == "曜日の偏り" }.unit)
        assertEquals("件", t.searching.first { it.label == "期間の制約" }.unit)
    }

    /** 0件の族はサマリー側へ回してゼロサプレッションする（全族がどちらかに入る）。 */
    @Test fun zeroCountFamiliesGoToTheCollapsedSummary() {
        val t = analysisTriage(ui(mapOf("c1" to 6)))
        assertEquals(com.magi.app.v6.MirrorKeys.all.size, t.okFamilies.size + t.busyFamilies.size)
        assertEquals(listOf("期間の制約"), t.busyFamilies)
        assertTrue("人員不足は0件なので正常側", "人員不足" in t.okFamilies)
    }

    /** 人員過剰は「動かす手が他の族に負ける」と診断が言ったときだけ上段へ上がる。 */
    @Test fun surplusIsPromotedOnlyWhenTheDiagnosisNamesTheFamilyThatBlocksIt() {
        fun cov(blockedBy: String?) = CoverageDiagnosis(
            totalShortfall = 0, infeasibleSlots = 0, fixableSlots = 0, shortfalls = emptyList(),
            totalSurplus = 1,
            surpluses = listOf(CoverageSurplus(0, "8/1(土)", 1, "休", 0, 1, 1, blockedBy, "…")),
        )
        val blocked = analysisTriage(ui(mapOf("covO" to 1), hasResult = true, coverage = cov("weekly")))
        assertEquals(listOf("人員過剰"), blocked.blockers.map { it.label })
        assertTrue(blocked.blockers.single().detail.contains("曜日の偏り"))

        val free = analysisTriage(ui(mapOf("covO" to 1), hasResult = true, coverage = cov(null)))
        assertTrue(free.blockers.isEmpty())
        assertEquals(listOf("人員過剰"), free.searching.map { it.label })
    }
}
