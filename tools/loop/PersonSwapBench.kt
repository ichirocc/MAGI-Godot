package probe
// [3.518.0] PERSON_SWAP_ILS（PolishGate.personSwapKick）のA/B。LoopBench.kt は runPostOptimization
// （後処理研磨チェーンのみ）を比較する既存ハーネスだが、personSwapKickは AdaptivePortfolio のロール
// ローテーション（V6NativeOptimizer.optimize 本体、algorithm=PORTFOLIO）にしか出現しないため別ハーネスが要る。
// CLAUDE.mdが認める代替手法「実データ4件のprobeで最終盤面のハッシュ比較」に基づき、実データ4件×複数seed×
// フル optimize()（PORTFOLIO）で hard/weightedScore を比較する。
import com.magi.app.model.StateParser
import com.magi.app.v6.PolishGate
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.V6NativeOptimizer
import com.magi.app.v6.V6OptimizerOptions
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val resDir = args[0]
    val out = File(args[1])
    val seeds = args.getOrNull(2)?.toInt() ?: 5
    val budgetSec = args.getOrNull(3)?.toInt() ?: 120
    val workers = args.getOrNull(4)?.toInt() ?: 4
    val fixtures = listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json")

    val w = java.io.FileWriter(out, false).buffered()
    w.write("fixture,seed,arm,elapsedMs,hard,weightedScore,total\n")
    w.flush()

    for (fname in fixtures) {
        val f = File(resDir, fname)
        if (!f.exists()) { System.err.println("skip missing $fname"); continue }
        val st = StateParser.parse(f.readText())!!
        val sched0 = Array(st.schedule.size) { i -> st.schedule[i].toIntArray() }
        for (seed in 1..seeds) {
            for (armOn in listOf(false, true)) {
                PolishGate.personSwapKick = armOn
                val t0 = System.currentTimeMillis()
                val res = runBlocking {
                    V6NativeOptimizer.optimize(
                        st, sched0.map { it.copyOf() }.toTypedArray(),
                        V6OptimizerOptions(algorithm = V6Algorithm.PORTFOLIO, totalBudgetSec = budgetSec, workers = workers, seed = seed.toLong()),
                        shouldStop = { false },
                    )
                }
                val elapsed = System.currentTimeMillis() - t0
                val rep = UnifiedViolationChecker.check(st, res.schedule)
                val arm = if (armOn) "on" else "off"
                val line = "${fname.removeSuffix("_state.json").removeSuffix("_state_v6.json")},$seed,$arm,$elapsed,${rep.hard},${rep.weightedScore},${rep.total}"
                w.write(line + "\n"); w.flush()
                System.err.println(line)
            }
        }
    }
    PolishGate.personSwapKick = false
    w.close()
}
