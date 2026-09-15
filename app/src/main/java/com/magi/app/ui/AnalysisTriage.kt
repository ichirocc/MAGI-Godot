package com.magi.app.ui

import com.magi.app.v6.C1PlateauDiagnosis
import com.magi.app.v6.CoverageDiagnosis
import com.magi.app.v6.ForbiddenRunDiagnosis
import com.magi.app.v6.IssueKind
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.SettingIssue

/**
 * [3.471.0/分析タブ再構築] 分析タブの1画面トリアージを組み立てる **Compose 非依存の純関数**。
 *
 * ## なぜ族で決め打ちしないか
 * 提示された初版のモックは「連勤・並び／窓の要件／曜日の偏り」を**族の名前だけで**
 * 「🤖 最適化エンジンが自動調整する項目・手動修正は不要です」と断定していた。これは
 * 3.263.0（covU「玉突き」診断の楽観バイアス）・3.322.0（C1 が直せなかった理由の構造化）・
 * 3.344.0（「充足可能」と「どう組んでも解消できません」の矛盾）で**逆向きに直してきた誤表示**
 * そのもので、c1 と c3n はデータ次第で最適化では絶対に消せない構造的な壁になる。
 *
 * そこで分類の軸を「族が HARD か SOFT か」ではなく **「データを直さない限り消えないか」** に置く。
 * 判定は新しいロジックを作らず、**既にある診断の結論だけ**を読む:
 *  - `settingIssues`（検査2b-2 / 2b-3 / 6b / 6c ほか＝実行前に分かる構造的破綻）
 *  - `forbiddenDiag`（3.280.0＝禁止連続の run ごとに崩せるか実際に検証済み）
 *  - `c1Plateau`（3.322.0＝窓の要件が**なぜ**直せなかったかの観測。観測が無ければ理由を語らない）
 *  - `coverageDiag`（人員不足/過剰の充足可否と「いまの希望のままでは不能」）
 *
 * ## 断定しないこと
 * 実行前は `c1Plateau`/`forbiddenDiag` が無い（まだ何も観測していない）ので、SOFT 族は
 * **「エンジン探索対象（未計算）」**であって「手動修正は不要」ではない。注記でそれを明示する。
 *
 * 表示のみ・読み取り専用＝スコアリング/エンジンは一切不変。
 */
internal enum class TriageBand { BLOCKER, SEARCH }

/** 上段/中段に出す1行。`count` は族の件数、weekly/fair だけ L1 偏差なので `unit` が "pt" になる。 */
internal data class TriageRow(
    val label: String,
    val count: Int,
    val unit: String,
    val detail: String,
    /** 族キー（`MirrorKeys.all`）。settingIssues 由来の行は null。 */
    val family: String? = null,
    /** 診断が「データを直さない限り消えない」と判定して上段へ上げた行か。 */
    val promoted: Boolean = false,
    /** タップで修復フローへ渡す職員（null=全体探索 or 導線なし）。 */
    val staff: Int? = null,
)

internal data class AnalysisTriage(
    val computed: Boolean,
    /** 🔴 上段: 必須違反＋診断が構造的な壁と判定した族。 */
    val blockers: List<TriageRow>,
    /** 🔴 上段: 設定の破綻（`settingIssues` を種類ごとに集約）。 */
    val issues: List<TriageRow>,
    /** ⏳/🟡 中段: エンジンが挑戦する残りの族。 */
    val searching: List<TriageRow>,
    /** 📊 下段: 0件の族（ゼロサプレッションして畳む）。 */
    val okFamilies: List<String>,
    /** 📊 下段: 残っている族。 */
    val busyFamilies: List<String>,
    /** 中段の注記（断定しない文言）。 */
    val searchNote: String,
) {
    val hasAnything: Boolean get() = blockers.isNotEmpty() || issues.isNotEmpty() || searching.isNotEmpty()
}

/** weekly/fair は件数でなく L1 偏差の合計。単位を分けないと「186件」と読めてしまう。 */
private fun unitOf(family: String) = if (family == "fair" || family == "weekly") "pt" else "件"

private fun labelOf(family: String) = breakdownLabels[family] ?: family

/** `SettingIssue` の種類 → 画面に出す見出し（英字符号を出さない＝`docs/operator_ux.md`）。 */
private fun issueKindLabel(kind: IssueKind) = when (kind) {
    IssueKind.WISH -> "希望の設定"
    IssueKind.CONSTRAINT -> "決まりの設定"
    IssueKind.DEMAND -> "必要人数の設定"
    IssueKind.RANGE -> "回数の設定"
}

/**
 * 同種を1行に畳む＝「古泉・Dﾃ」「山本・Dﾃ」…と1人1行で伸びていたのを
 * 「回数の設定 8件（古泉/山本 ほか）」にする。名前は先頭2件＋「ほか」まで。
 */
private fun aggregateIssues(issues: List<SettingIssue>): List<TriageRow> =
    issues.groupBy { it.kind }
        .map { (kind, list) ->
            // [実機バグ修正] 1件だけの種類は場所を切り詰めず理由(problem)まで出す。場所だけを18文字で切ると
            //   設定の重複警告が勤務表の違反に読めた（経緯: history 3.515.2）。
            val detail = if (list.size == 1) {
                val s = list.single()
                if (s.problem.isBlank()) s.where else "${s.where} — ${s.problem}"
            } else {
                list.take(2).joinToString("/") { it.where.take(18) } + (if (list.size > 2) " ほか" else "")
            }
            TriageRow(
                label = issueKindLabel(kind),
                count = list.size,
                unit = "件",
                detail = detail,
            )
        }
        .sortedByDescending { it.count }

/**
 * 上段へ上げてよい SOFT 族か。**診断が実際に観測・検証した結論だけ**を根拠にする。
 * 観測が無いとき（`causeUnknown` など）は上げない＝「直せない理由」を語らない。
 */
private fun promotedSoftFamilies(
    computed: Boolean,
    c1Plateau: C1PlateauDiagnosis?,
    coverage: CoverageDiagnosis?,
): Map<String, String> {
    if (!computed) return emptyMap()
    val out = LinkedHashMap<String, String>()
    if (c1Plateau != null && c1Plateau.hasEntries) {
        val e = c1Plateau.entries.first()
        out["c1"] = "${e.label}ほか${c1Plateau.entries.size}件 — ${e.recommendedAction(::labelOf)}"
    }
    val stuckSurplus = coverage?.surpluses?.filter { it.blockedFamily != null }.orEmpty()
    if (stuckSurplus.isNotEmpty()) {
        val fam = labelOf(stuckSurplus.first().blockedFamily!!)
        out["covO"] = "${stuckSurplus.size}枠は動かす手が「${fam}」に負けて採用されません。${fam}の設定を緩めると動きます。"
    }
    return out
}

/** 必須違反の行に添える「なぜ残るか」。診断が無い/観測が無いときは空文字＝何も主張しない。 */
private fun hardDetail(
    family: String,
    coverage: CoverageDiagnosis?,
    forbidden: ForbiddenRunDiagnosis?,
): String = when (family) {
    "c3n" -> when {
        forbidden == null || !forbidden.hasRuns -> ""
        forbidden.allBlocked -> "この希望・担当のままでは崩せません（${forbidden.totalRuns}件すべて）。"
        else -> forbidden.runs.firstOrNull { !it.escapable }
            ?.let { "「${it.seqLabel}」は崩せません（${it.staffName}）。" } ?: "崩す手が残っています。"
    }
    "covU" -> when {
        coverage == null || !coverage.hasShortage -> ""
        coverage.allBlockedNow -> "いまの希望のままでは、どう組んでも埋まりません（${coverage.totalShortfall}人）。"
        coverage.blockedNowSlots > 0 -> "${coverage.blockedNowSlots}枠はいまの希望のままでは埋まりません。"
        else -> "担当を動かせば埋まる見込みです。"
    }
    else -> ""
}

internal fun analysisTriage(ui: UiState): AnalysisTriage {
    // [3.475.0/論理監査] 「計算済み」はエンジンが走った事実で決める。旧: hasResult＝手編集・取込でも true に
    //   なる「盤面がある」旗を読んでいたため、読み込んで1セル直しただけで「計算後に残っている項目」と語っていた。
    val computed = ui.engineRan
    val counts = ui.breakdown
    val promoted = promotedSoftFamilies(computed, ui.c1Plateau, ui.coverageDiag)

    val blockers = ArrayList<TriageRow>()
    val searching = ArrayList<TriageRow>()
    val ok = ArrayList<String>()
    val busy = ArrayList<String>()

    // 重い順に並べる＝重み階層（groupViol > pref > covU > c3n > low > high > …）と表示順を一致させる。
    val ordered = MirrorKeys.all.sortedByDescending { MirrorKeys.weightOf(it) }
    for (fam in ordered) {
        val n = counts[fam] ?: 0
        if (n <= 0) { ok.add(labelOf(fam)); continue }
        busy.add(labelOf(fam))
        val row = TriageRow(labelOf(fam), n, unitOf(fam), "", fam)
        when {
            fam in MirrorKeys.hard ->
                blockers.add(row.copy(detail = hardDetail(fam, ui.coverageDiag, ui.forbiddenDiag)))
            promoted.containsKey(fam) ->
                blockers.add(row.copy(detail = promoted.getValue(fam), promoted = true))
            else -> searching.add(row)
        }
    }

    val note = if (computed) {
        "最適化後も残っている項目です。構造的に残ると判定されたものは上へ移しています。"
    } else {
        "実行前の概算です。期間の制約・禁止の並びなどの構造的な要因により、最適化後も残る場合があります。"
    }
    return AnalysisTriage(
        computed = computed,
        blockers = blockers,
        issues = aggregateIssues(ui.settingIssues),
        searching = searching,
        okFamilies = ok,
        busyFamilies = busy,
        searchNote = note,
    )
}
