package com.magi.app.v6

import kotlin.math.ln
import kotlin.math.sqrt

/** 停滞脱出アクション。NoOp=何もしない（停滞前の既定）/ Reheat=最良へ戻して再加熱 /
 *  StrongPerturb=最良から大きく摂動して脱出 / ScaleTemp=盤面を最良へ戻さず現在解のまま次ラダーへ。
 *  [HF77訂正 3.213.0] ScaleTemp は専用の温度倍率を持たない（全アクション共通で次ラダーが t0 から再加熱）。
 *  Reheat との差は「盤面を best へ戻すか（Reheat）/ 現在解を保つか（ScaleTemp）」のみ。 */
enum class ConductorAction { NOOP, REHEAT, STRONG_PERTURB, SCALE_TEMP }

/**
 * Web版 `MagiConductor` の移植（com.magi.app の Python統合コア）。
 *
 * 停滞（直近の最良更新からの反復数 itersSinceImprove）が [stagThreshold] 未満なら NoOp。到達後は
 * UCB1 多腕バンディットで脱出戦略 {Reheat, StrongPerturb, ScaleTemp} を自律選択し、効果（報酬）を
 * オンライン学習して選好を更新する。固定 reheat の上位互換。selectAction は RNG 非依存（決定論的）。
 */
class MagiConductor(var stagThreshold: Int = 3000) {
    private val nA = ConductorAction.entries.size
    private val counts = IntArray(nA) { 1 }
    private val values = DoubleArray(nA) { 0.0 }
    private var total = 0
    var itersSinceImprove = 0
        private set

    /** 1反復ごとに呼ぶ。最良が更新されたら停滞カウンタをリセット、そうでなければ +1。 */
    fun updateStagnation(improved: Boolean) {
        itersSinceImprove = if (improved) 0 else itersSinceImprove + 1
    }

    /** [ネイティブ加速] チャンク実行後にまとめて反映。improvedInChunk=チャンク内で最良更新があったか、
     *  tailIters=最後の更新以降の反復数（無更新チャンクなら全反復数）。逐次 updateStagnation と等価。 */
    fun updateStagnationBulk(improvedInChunk: Boolean, tailIters: Int) {
        itersSinceImprove = if (improvedInChunk) tailIters else itersSinceImprove + tailIters
    }

    /** 停滞しきい値未満は NoOp。到達後は UCB1 で 1..3（NoOp以外）から選ぶ。 */
    fun selectAction(): ConductorAction {
        if (itersSinceImprove < stagThreshold) return ConductorAction.NOOP
        var best = 1
        var bestUcb = Double.NEGATIVE_INFINITY
        for (a in 1 until nA) {
            val ucb = values[a] + sqrt(2.0 * ln((total + 1).toDouble()) / counts[a])
            if (ucb > bestUcb) { bestUcb = ucb; best = a }
        }
        counts[best]++
        total++
        return ConductorAction.entries[best]
    }

    /** 選択アクションの効果（reward）をオンライン学習（指数移動平均, α=0.1）。 */
    fun updateReward(action: ConductorAction, reward: Double) {
        val a = action.ordinal
        values[a] += 0.1 * (reward - values[a])
    }

    /** 検査用。 */
    fun valueOf(action: ConductorAction): Double = values[action.ordinal]
    fun countOf(action: ConductorAction): Int = counts[action.ordinal]
}
