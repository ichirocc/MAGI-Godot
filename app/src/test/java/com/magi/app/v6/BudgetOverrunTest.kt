package com.magi.app.v6

import com.magi.app.model.StateParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

/**
 * [3.511.3, backlog #14(g)] 「内部ループが 100〜250ms ごとに停止判定、300 秒設定で実時間 305 秒以内、
 * 停止要求から 5 秒以内、例外ワーカーで固まらない」を回帰として固定する。
 *
 * [StopPropagationTest] は `shouldStop={true}` を最初から渡すため、ループへ一度も入らずに戻る＝
 * 入口ガードの検証にしかならない。ここでは実際にループへ入ってから (a) 予算が自然に切れたときの超過幅、
 * (b) 計算中に外部停止要求が来てから戻るまでの時間、(c) 仮説ワーカーが例外を投げても制御が戻ること、
 * の3つを別々に固定する（300秒→1秒への比例縮小は下記の理由で不採用＝一定オーバーヘッドが埋もれる）。
 */
class BudgetOverrunTest {
    private fun goldenState() =
        StateParser.parse(javaClass.classLoader!!.getResource("golden_state.json")!!.readText())!!

    /** [3.511.3] 300秒→1秒のような比例縮小はしない: 予算満了後の後始末（hf67HardRepair・checker・
     *  ワーカーjoin）はbudgetMsにほぼ比例しない一定コストのため、縮小すると許容枠に対して過大に見える。
     *  1秒(=Int最小の totalBudgetSec)の予算でも一定コストの範囲(3秒)に収まることを確認する。
     *
     *  [3.511.3/発見] RSI_PLUS だけは別枠: 内部で Seed/Hypothesis/Refine/Polish の4フェーズへ予算を
     *  按分する式が `max(10, budgetSec*0.2)`/`max(10, budgetSec*0.35)`/`max(10, budgetSec*0.3)`/`max(5, 残り)`
     *  という**フェーズごとの下限**を持つため、budgetSec<50 ではほぼ常に 10+10+10+5=35 秒が下限になる
     *  （runRsiPlus 内、V6NativeOptimizer.kt）。backlog #14(g) の実要件「300秒設定で305秒以内」は
     *  300*0.2/0.35/0.3 がいずれも10を上回るためこの下限に一切触れず無傷。1秒のような極小予算だけが踏む
     *  経路で、production の予算（後処理予約25秒を含め常に数十秒〜300秒、CLAUDE.md「維持」参照）では
     *  発生しない。既存の予算按分の設計判断（フェーズ最低品質の下限）を無断で縮めるのは探索動学の変更＝
     *  HF77 相当の明示指示なしに変えない。ここでは別枠の緩い上限（フェーズ下限35秒＋定数コスト）で
     *  「暴走していないか」だけを検出する。 */
    @Test
    fun naturalBudgetExpiryOverrunStaysBounded() = runBlocking {
        val st = goldenState()
        val initial = Array(st.schedule.size) { st.schedule[it].toIntArray() }
        val budgetMs = 1_000L
        val overrunToleranceMs = 3_000L
        val algorithms = listOf(V6Algorithm.V5, V6Algorithm.ALNS, V6Algorithm.RSI, V6Algorithm.PORTFOLIO)
        for (alg in algorithms) {
            val t0 = EngineClock.nowMs()
            val res = V6NativeOptimizer.optimize(
                st, Array(initial.size) { initial[it].clone() },
                V6OptimizerOptions(algorithm = alg, totalBudgetSec = 1, workers = 4, seed = 12345L),
                shouldStop = { false },
            )
            val elapsed = EngineClock.nowMs() - t0
            assertTrue("$alg の予算超過が許容枠を超えた（budget=${budgetMs}ms elapsed=${elapsed}ms）", elapsed <= budgetMs + overrunToleranceMs)
            val base = UnifiedViolationChecker.check(st, initial)
            val after = UnifiedViolationChecker.check(st, res.schedule)
            assertTrue("$alg が予算満了時に入力より悪い盤面を返した", !betterReport(base, after))
        }
        // RSI_PLUS: フェーズ下限35秒＋定数コストの緩い上限で「暴走していないか」だけ見る（上のコメント参照）。
        run {
            val t0 = EngineClock.nowMs()
            val res = V6NativeOptimizer.optimize(
                st, Array(initial.size) { initial[it].clone() },
                V6OptimizerOptions(algorithm = V6Algorithm.RSI_PLUS, totalBudgetSec = 1, workers = 4, seed = 12345L),
                shouldStop = { false },
            )
            val elapsed = EngineClock.nowMs() - t0
            assertTrue("RSI_PLUS がフェーズ下限(35秒)を大幅に超えて暴走した（elapsed=${elapsed}ms）", elapsed <= 60_000L)
            val base = UnifiedViolationChecker.check(st, initial)
            val after = UnifiedViolationChecker.check(st, res.schedule)
            assertTrue("RSI_PLUS が予算満了時に入力より悪い盤面を返した", !betterReport(base, after))
        }
    }

    /** [3.511.3] 実計算に入ってから300ms後に外部停止要求を出し、5秒以内に戻ることを確認する
     *  （StopPropagationTestの「t=0で即true」と違い、実機の「しばらく待ってからやめるを押す」に対応）。
     *  内部ループ構造が異なる代表2種（単一チェーン系のV5・複数仮説協調のPORTFOLIO）に絞る。 */
    @Test
    fun stopRequestDuringRealComputationHonoredWithinFiveSeconds() = runBlocking {
        val st = goldenState()
        val initial = Array(st.schedule.size) { st.schedule[it].toIntArray() }
        for (alg in listOf(V6Algorithm.V5, V6Algorithm.PORTFOLIO)) {
            val stopFlag = AtomicBoolean(false)
            val flipMs = AtomicLong(-1L)
            val job = async(Dispatchers.Default) {
                V6NativeOptimizer.optimize(
                    st, Array(initial.size) { initial[it].clone() },
                    V6OptimizerOptions(algorithm = alg, totalBudgetSec = 10, workers = 4, seed = 12345L),
                    shouldStop = { stopFlag.get() },
                )
            }
            launch(Dispatchers.Default) {
                delay(300L)
                flipMs.set(EngineClock.nowMs())
                stopFlag.set(true)
            }
            job.await()
            val returnMs = EngineClock.nowMs()
            assertTrue("$alg の停止要求が反映されていない", flipMs.get() > 0)
            assertTrue("$alg が停止要求から5秒以内に戻らなかった（${returnMs - flipMs.get()}ms）", returnMs - flipMs.get() < 5_000L)
        }
    }

    /** [3.511.3] 仮説の一部（偶数index）が例外を投げても、runMultiWorker 全体が2秒以内に制御を返す
     *  （supervisorScope + per-hypothesis try/catch が機能し続けることの固定）。全滅時のフォールバック
     *  （results.isEmpty() 経由の無防備な run(0,...) 再呼出）は例外伝播が仕様のため、ハングしないことだけを見る。 */
    @Test
    fun runMultiWorkerDoesNotHangWhenHypothesesThrow() = runBlocking {
        val st = goldenState()
        val schedule = Array(st.schedule.size) { st.schedule[it].toIntArray() }
        val report = UnifiedViolationChecker.check(st, schedule)
        fun trivialResult() = V6OptimizerResult(schedule, report, V6Algorithm.V5, emptyList(), 0L, 0L)

        val t0 = EngineClock.nowMs()
        val partial = V6NativeOptimizer.runMultiWorker(4, V6OptimizerOptions(workers = 4), onProgress = { _, _, _, _ -> }) { i, _, _ ->
            if (i % 2 == 0) throw RuntimeException("injected w=$i") else trivialResult()
        }
        assertTrue("部分失敗が2秒以内に戻らなかった", EngineClock.nowMs() - t0 < 2_000L)
        assertTrue("非throw側の結果が返る", partial.schedule.contentDeepEquals(schedule))

        val t1 = EngineClock.nowMs()
        runCatching {
            V6NativeOptimizer.runMultiWorker(4, V6OptimizerOptions(workers = 4), onProgress = { _, _, _, _ -> }) { i, _, _ ->
                throw RuntimeException("injected all w=$i")
            }
        }
        assertTrue("全滅時も2秒以内に制御が戻った（成功/例外いずれでもハングしない）", EngineClock.nowMs() - t1 < 2_000L)
    }
}
