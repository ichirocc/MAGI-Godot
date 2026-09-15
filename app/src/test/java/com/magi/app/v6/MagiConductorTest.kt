package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Web版 MagiConductor.runSelfTest の Conductor 部（停滞発火・報酬学習）を移植した検証。 */
class MagiConductorTest {

    @Test fun staysNoOpUntilStagnationThenSelectsEscape() {
        val c = MagiConductor(stagThreshold = 100)
        // 停滞前は NoOp
        assertEquals(ConductorAction.NOOP, c.selectAction())
        // 100反復以上 最良未更新 → NoOp 以外の脱出戦略を選ぶ
        repeat(150) { c.updateStagnation(false) }
        assertNotEquals(ConductorAction.NOOP, c.selectAction())
    }

    @Test fun improvementResetsStagnation() {
        val c = MagiConductor(stagThreshold = 10)
        repeat(50) { c.updateStagnation(false) }
        c.updateStagnation(true)   // 最良更新でリセット
        assertEquals(ConductorAction.NOOP, c.selectAction())
    }

    @Test fun rewardLearningRaisesPreferredArm() {
        val c = MagiConductor(stagThreshold = 1)
        c.updateReward(ConductorAction.REHEAT, 0.5)
        assertTrue(c.valueOf(ConductorAction.REHEAT) > 0.0)
        // 高報酬の腕が UCB1 で選ばれやすくなる（値が他より高い）
        c.updateReward(ConductorAction.REHEAT, 1.0)
        assertTrue(c.valueOf(ConductorAction.REHEAT) > c.valueOf(ConductorAction.SCALE_TEMP))
    }
}
