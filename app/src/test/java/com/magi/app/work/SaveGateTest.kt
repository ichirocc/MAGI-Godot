package com.magi.app.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [3.485.0] 古い世代の書き込みが新しい世代の後に来ても捨てられる（順序の逆転を防ぐ）。 */
class SaveGateTest {
    @Test fun staleGenerationIsDropped() {
        val gate = SaveGate()
        var file = ""
        assertEquals(true, gate.writeIfLatest(2) { file = "B"; true })
        assertNull(gate.writeIfLatest(1) { file = "A"; true })   // 古い世代＝書かない
        assertEquals("B", file)
        assertEquals(true, gate.writeIfLatest(2) { file = "B2"; true })   // 同じ世代の再書き込みは許す
        assertEquals(true, gate.writeIfLatest(3) { file = "C"; true })
        assertEquals("C", file)
    }

    @Test fun failedWriteDoesNotAdvanceGeneration() {
        val gate = SaveGate()
        assertEquals(false, gate.writeIfLatest(5) { false })
        assertEquals(true, gate.writeIfLatest(4) { true })   // 5 は書けていないので 4 はまだ最新扱い
    }
}
