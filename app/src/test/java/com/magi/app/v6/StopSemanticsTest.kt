package com.magi.app.v6

import com.magi.app.model.StateParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * [3.491.0/外部レビュー第7弾] 並列仮説と停止の意味論を固定する回帰テスト。
 *
 *  1. `runMultiWorker` は「合格（HARD=0）が出ても全本継続」（3.376.0 の仕様）。旧: 起動時の
 *     「既に勝者がいれば何もせず抜ける」事前チェックが残っており、仮説0が即座に HARD=0 を報告すると
 *     まだ起動していない仮説がスレッドプールの都合で丸ごと走らないことがあった（Windows 版のテストは
 *     その競合を避けるために偽の run を使い、コメントで「バグではない」と仕様化していた）。
 *  2. `handleOptimize` は停止要求を**例外で**返す。ViewModel は CancellationException で「直前の勤務表を
 *     保持」へ分岐する設計＝正常に返すと停止したのに「完了」として途中盤面が採用される。Android では
 *     `withContext(Dispatchers.Default)` が完了時に親のキャンセルを見て自分で投げるため元から成立していた
 *     （変異検証: 終端の `ensureActive()` を外しても本テストは緑＝ensureActive は契約の明示であって修正ではない）。
 *     実在した非対称は Windows 版（workers=1 の短絡と HandleOptimize の終端）。
 */
class StopSemanticsTest {

    private fun sampleState() = StateParser.parse(
        javaClass.getResourceAsStream("/sample_state_v6.json")!!.readBytes().toString(Charsets.UTF_8))

    @Test
    fun allHypothesesRunEvenWhenHypothesisZeroReportsHardZeroImmediately() = runBlocking {
        val s = sampleState()
        val p = Problem(s)
        val sched = p.initialAssignment()
        val report = UnifiedViolationChecker.check(s, sched)
        val hardZero = report.copy(hard = 0)
        val w = 4
        val (hSpawn, _) = HypothesisPlanning.hypothesisSpawnPlan(workers = w, w = w, cores = 8)
        assertTrue("この検証には複数仮説が要る", hSpawn > 1)
        val invoked = ConcurrentHashMap.newKeySet<Int>()
        // 競合を**決定的に**再現する: Default ディスパッチャの CPU 枠を1本だけ残して他を塞ぐ。仮説0が
        //   その1本で先に走って winner を立て、残りの仮説はそのあと同じ1本で順に起動する＝旧実装の
        //   事前チェックなら仮説1以降が run() を呼ばずに抜ける（3.491.0 の変異検証: 旧コードで本テストが赤）。
        //   （塞がないと 8 コアでは全本がほぼ同時に起動し、競合窓が µs で緑になってしまう。）
        val cores = Runtime.getRuntime().availableProcessors()
        val blockers = (1 until cores).map { launch(Dispatchers.Default) { Thread.sleep(1_500) } }
        delay(50)   // blockers が実際にスレッドを占有するまで待つ
        val result = V6NativeOptimizer.runMultiWorker(
            w = w, options = V6OptimizerOptions(workers = w, seed = 1L), onProgress = { _, _, _, _ -> },
        ) { i, _, prog ->
            invoked.add(i)
            if (i == 0) {
                // 仮説0が起動と同時に合格を報告する＝旧実装では他仮説の起動が省かれ得た競合を再現。
                prog("test", hardZero, 1L, 0L)
            } else {
                delay(150)
            }
            V6OptimizerResult(sched.copy2D(), if (i == 0) hardZero else report, V6Algorithm.ALNS, emptyList(), 1L, 0L)
        }
        blockers.forEach { it.join() }
        assertEquals("合格が出ても全本が起動する（全本継続）", (0 until hSpawn).toSet(), invoked)
        assertTrue(result.phaseLogs.any { it.message.contains("合格あり(全本継続)") })
    }

    @Test
    fun handleOptimizeThrowsCancellationInsteadOfReturningAfterStop() = runBlocking {
        val s = sampleState()
        var returnedNormally = false
        val thrown = runCatching {
            coroutineScope {
                launch { delay(300); this@coroutineScope.cancel() }
                V6FinalPort.handleOptimize(s, secondsRaw = 10, workers = 1, requestedAlgorithm = V6Algorithm.ALNS, allowImpossible = true)
                returnedNormally = true
            }
        }.exceptionOrNull()
        assertTrue("停止は CancellationException で伝わる: $thrown", thrown is CancellationException)
        assertFalse("停止後に正常終了して途中盤面を「完了」として返さない", returnedNormally)
    }
}
