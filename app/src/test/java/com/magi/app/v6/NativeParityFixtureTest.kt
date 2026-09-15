package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.357.0] Kotlin と C++ の評価器を**1つの数字に固定する**。
 *
 * これまで CI が照合していたのは **C++ scalar vs C++ bit-op**（native-parity）と
 * **Checker vs Evaluator**（[ObjectiveParityTest]・どちらも Kotlin）だけで、
 * **Kotlin と C++ の間**を見るものが1つも無かった。実機には `NativeEval.parityCheck` の
 * 番兵があるが、発火するとネイティブが黙って無効化される＝**速度が落ちるだけで気づけない**。
 *
 * `<fixture>_eval_expected.txt` は Kotlin の `Evaluator.fullEval`（実データ state の
 * 入力盤面）が出す hard/soft。このテストが Kotlin 側を、native-parity ワークフローの
 * `--expect=` が C++ 側を、同じファイルへ固定する。片側だけを変えれば必ずどちらかが落ちる。
 *
 * **片側だけ変えたときに本当に落ちるかを実測して確認済み**: C++ の soft へ +113 を足す
 * （内部の scalar/bit 整合は崩れない＝旧 CI は 0 mismatch で通る）改変を入れると、
 * `--expect=` 側だけが MISMATCH で非ゼロ終了する。
 *
 * [3.361.0/backlog#6] 実データ形状の網羅を広げるため **2つ目のフィクスチャ sample_state_v6** を追加。
 * golden は `hard=0`（C++ の HARD 族パスを実データで一度も exercise しない）だが、sample_v6 の
 * 入力盤面は `hard=15`（groupViol/c3n/pref/covU が発火＝別形状）。C++ 側は 1回のベンチ実行のまま
 * `--expect` を flat と出現順で対応づけて両方を照合する（`host_parity_bench.cpp`）。
 *
 * 期待値を意図的に変えるとき（重みの変更・族の定義変更など）は、**Kotlin と C++ の両方を直してから**
 * 該当の期待値ファイルを更新する。片方だけ直して期待値を書き換えると、この仕組みは意味を失う。
 */
class NativeParityFixtureTest {
    @Test
    fun goldenEvaluatorValueMatchesTheSharedCrossLanguageFixture() {
        assertFixtureMatchesEvaluator("/golden_state.json", "/golden_eval_expected.txt")
    }

    /** [3.361.0] hard=15 の実データ形状（groupViol/c3n/pref/covU＋c1/c2/c42）を言語跨ぎで固定する。 */
    @Test
    fun sampleV6EvaluatorValueMatchesTheSharedCrossLanguageFixture() {
        assertFixtureMatchesEvaluator("/sample_state_v6.json", "/sample_v6_eval_expected.txt")
    }

    /** [3.409.15/backlog#6 解消] covU が構造床(0)を超えて blocked-now な第3の実データ形状
     *  （2026-08 実運用 state の匿名化版＝職員名のみ 職員A..J へ・診断ログは除去・評価は
     *  匿名化前と bit 一致を実測済み）。golden(hard=0)/sample_v6(covU は解ける形) のどちらにも
     *  無かった「いまの希望・盤面では埋められない covU」を言語跨ぎで固定する。 */
    @Test
    fun blockedCovUEvaluatorValueMatchesTheSharedCrossLanguageFixture() {
        assertFixtureMatchesEvaluator("/blocked_covu_state.json", "/blocked_covu_eval_expected.txt")
    }

    /** [3.524.0/backlog#6] 他3件は apt=c41=c41s=c42s=0（一度も発火しない）＝手組みの小さな合成 state で
     *  19族すべてを非ゼロにした4件目のフィクスチャ（経緯は docs/history/3.4xx.md）。 */
    @Test
    fun fullCoverageEvaluatorValueMatchesTheSharedCrossLanguageFixtureAndExercisesAllFamilies() {
        val breakdown = assertFixtureMatchesEvaluator("/full_coverage_state.json", "/full_coverage_eval_expected.txt")
        val zero = MirrorKeys.all.filter { (breakdown[it] ?: 0L) == 0L }
        assertTrue("全19族が非ゼロになるはずが、発火しない族がある: $zero（fixture 側の修正が要る）", zero.isEmpty())
    }

    /** [3.538.0] fairの達成率モード（`Problem.fairDevOfBucket`）を実際に発火させる5件目の実データ形状
     *  （他4件は範囲staffRange.lo/hiとapt目標の両方が群の全メンバーに揃う組合せを持たず、旧来の
     *  生回数round(平均)方式のままだった＝達成率モードが検証不能だった）。C++側は
     *  `.github/workflows/native-parity.yml` の `--expect=` で同じファイルを照合する。 */
    @Test
    fun sept2026EvaluatorValueMatchesTheSharedCrossLanguageFixtureAndExercisesFairAchievementMode() {
        assertFixtureMatchesEvaluator("/sept2026_state.json", "/sept2026_eval_expected.txt")
    }

    private fun assertFixtureMatchesEvaluator(stateResource: String, expectResource: String): Map<String, Long> {
        val json = javaClass.getResourceAsStream(stateResource)?.bufferedReader()?.readText()
        assertNotNull("$stateResource がテストリソースにありません", json)
        val expectText = javaClass.getResourceAsStream(expectResource)?.bufferedReader()?.readText()
        assertNotNull("$expectResource がテストリソースにありません", expectText)

        val expected = HashMap<String, Long>()
        expectText!!.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach   // 空行・コメント行は非対象（数値以外は無視）
            val eq = t.indexOf('=')
            if (eq > 0) expected[t.substring(0, eq)] = t.substring(eq + 1).toLong()
        }
        assertTrue("期待値ファイルに hard=/soft= が無い（$expectResource）", "hard" in expected && "soft" in expected)
        // [3.524.0] 族の行（MirrorKeys.all の19キー）はあれば全部揃っていること＝一部だけの部分照合を防ぐ。
        val bdKeys = expected.keys - setOf("hard", "soft")
        assertTrue(
            "期待値ファイルの族の行が19族の一部だけ（$expectResource）。全部揃えるか1つも書かないこと: $bdKeys",
            bdKeys.isEmpty() || bdKeys == MirrorKeys.all.toSet(),
        )

        val st = StateParser.parse(json!!)!!
        val p = Problem(st)
        val sched = Array(st.schedule.size) { i -> st.schedule[i].toIntArray() }
        val ev = Evaluator(p)
        val breakdown = HashMap<String, Long>()
        val parts = ev.fullEvalParts(sched, breakdown)

        assertEquals(
            "Kotlin の hard が固定値と違う（$expectResource）。C++ 側も同時に直したうえで期待値ファイルを更新すること",
            expected["hard"], parts[0],
        )
        assertEquals(
            "Kotlin の soft が固定値と違う（$expectResource）。C++ 側も同時に直したうえで期待値ファイルを更新すること",
            expected["soft"], parts[1],
        )
        for (k in bdKeys) {
            assertEquals(
                "Kotlin の族別内訳「$k」が固定値と違う（$expectResource）。C++ 側も同時に直したうえで期待値ファイルを更新すること",
                expected[k], breakdown[k] ?: 0L,
            )
        }
        return breakdown
    }
}
