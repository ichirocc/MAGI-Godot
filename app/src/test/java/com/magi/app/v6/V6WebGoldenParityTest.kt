package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden parity test against the authoritative Web build (MAGI v5.33.19 SE-15 HF542).
 *
 * Fixture `golden_state.json` is a real production state exported from the Web app
 * (10 staff × 31 days × 10 shifts) after an RSI++ optimize.
 *
 * What is asserted (reliable Web ground truth):
 *  - constraint resolution == Web `LoadDataBit_Summary`
 *  - the Web-optimized schedule is HARD-clean (distributable) natively too
 *  - the violation report is internally self-consistent
 *
 * The full soft-family breakdown is printed (not asserted) for comparison: the
 * Web export's stored `lastResult.breakdown` is internally inconsistent with its
 * own saved schedule/violation maps (e.g. breakdown covU=0 while needViolations
 * lists 9 covU cells), i.e. it is a stale snapshot, so it is not a reliable
 * golden for an exact soft-level equality assertion.
 */
class V6WebGoldenParityTest {

    private fun loadState(): MagiState {
        val json = javaClass.getResourceAsStream("/golden_state.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("golden_state.json not found on the test classpath")
        return StateParser.parse(json)
    }

    /**
     * Native constraint resolution must reproduce the Web `LoadDataBit_Summary`:
     *   N=10 T=31 K=10 R=10 | c1=2 c2=1 c3=1 c3n=8 c3m=2 c3mn=4 c41=0 c42=7 | 希望 84
     */
    @Test
    fun loadDataBitMatchesWeb() {
        val p = Problem(loadState())
        assertEquals("staff (N)", 10, p.S)
        assertEquals("days (T)", 31, p.T)
        assertEquals("shifts (K)", 10, p.K)
        assertEquals("groups (R)", 10, p.G)
        assertEquals("c1", 2, p.cons1.size)
        assertEquals("c2", 1, p.cons2.size)
        assertEquals("c3", 1, p.cons3.size)
        assertEquals("c3n", 8, p.cons3n.size)
        assertEquals("c3m", 2, p.cons3m.size)
        assertEquals("c3mn", 4, p.cons3mn.size)
        assertEquals("c41", 0, p.cons41.size)
        assertEquals("c42", 7, p.cons42.size)
        assertEquals("wishes", 84, p.state.wishes.size)
    }

    /**
     * Native UnifiedViolationChecker on the stored (Web-optimized) schedule. The Web reports
     * HARD=0 for it; native must agree. The full native vs Web breakdown is printed for the
     * record (see class doc for why soft families are not asserted exactly).
     */
    @Test
    fun webOptimizedScheduleIsHardCleanAndConsistent() {
        val st = loadState()
        val rep = UnifiedViolationChecker.check(st, st.schedule.toIntArray2D())
        val web = linkedMapOf(
            "c1" to 113, "c2" to 4, "c3" to 37, "c3n" to 0, "c3m" to 36, "c3mn" to 11,
            "c41" to 0, "c42" to 7, "covU" to 0, "covO" to 4, "pref" to 0,
            "low" to 9, "high" to 2, "groupViol" to 0,
        )
        val table = web.keys.joinToString("\n") { k ->
            val w = web[k]; val n = rep.breakdown[k] ?: 0
            "  %-9s web=%-4d native=%-4d %s".format(k, w, n, if (w == n) "OK" else "DIFF")
        }
        println("=== V6 Web-golden breakdown comparison (HARD=${rep.hard}/web 0, total native=${rep.total}/web 223) ===\n$table")

        // Reliable Web-parity assertions:
        assertEquals("native HARD on a Web HARD=0 schedule", 0, rep.hard)
        assertEquals("breakdown self-consistency", rep.total, rep.breakdown.values.sum())
        assertEquals("hard == sum of hard families", rep.hard, MirrorKeys.hard.sumOf { rep.breakdown[it] ?: 0 })
    }
}
