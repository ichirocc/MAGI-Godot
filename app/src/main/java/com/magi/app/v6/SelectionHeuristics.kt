package com.magi.app.v6

import java.util.Random
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * 演算子/アルゴリズム選択ヒューリスティクス。[V6NativeOptimizer] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * 全メンバが**共有可変状態を一切参照しない純粋な計算/判定関数**（[V6NativeOptimizer] 本体は
 * @Volatile フィールド・Atomic系・RunSlotのコルーチンコンテキスト連携で並行実行状態を密結合する
 * 「統括状態機械」の性格が強く、機械的な抽出は危険＝本ファイルはその中の安全な塊だけを切り出す）。
 *
 * - [rouletteSelect]/[thompsonSelect]：ALNS内側ループの演算子選択（重み比例／Thompson sampling）。
 * - [chooseAlgorithm]：AUTO予算からV5/ALNS/RSI/RSI++/PORTFOLIOを選ぶ全体ディスパッチ判定
 *   （[HypothesisDiversityPolicy.autoAlgorithmForBudget] へ委譲）。
 *
 * [chooseAlgorithm] の直後（元位置1203行）は `private suspend fun runV5` から始まる統括
 * ディスパッチチェーン（suspend fun は物理分割スクリプトの宣言検出パターンに一致しないため
 * 抽出範囲の自動判定では不可視＝境界を手動で自身の閉じ括弧までに限定して抽出した）。
 *
 * 呼び出し側は全て`V6NativeOptimizer.<name>`の完全修飾で参照していたため、抽出時に
 * `SelectionHeuristics.<name>`へ一括置換した（本体内部からの無修飾自己呼出は元々無い）。
 */
internal object SelectionHeuristics {
    /** Roulette-wheel operator selection for the adaptive LNS. */
    internal fun rouletteSelect(weights: DoubleArray, rng: Random): Int {
        var sum = 0.0
        for (wgt in weights) sum += wgt
        if (sum <= 0.0) return rng.nextInt(weights.size)
        var r = rng.nextDouble() * sum
        for (i in weights.indices) {
            r -= weights[i]
            if (r <= 0.0) return i
        }
        return weights.size - 1
    }


    /** [Thompson sampling] 演算子選択。平滑報酬 opW を事後平均、探索ノイズを反復で減衰させた
     *  ガウス事後から各演算子の標本を引き、最大の演算子を選ぶ。重み比例(roulette)より停滞しにくく、
     *  不確実性下での選択が原理的。ノイズσは序盤大きく(探索)→終盤小さく(活用)アニールする。 */
    internal fun thompsonSelect(opW: DoubleArray, iter: Long, rng: Random): Int {
        val sigma = 0.5 / sqrt(1.0 + iter / 500.0)
        var bestOp = 0
        var bestSample = Double.NEGATIVE_INFINITY
        for (k in opW.indices) {
            val u1 = rng.nextDouble().coerceIn(1e-9, 1.0)
            val u2 = rng.nextDouble()
            val g = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)   // Box-Muller 標準正規
            val s = opW[k] + g * sigma
            if (s > bestSample) { bestSample = s; bestOp = k }
        }
        return bestOp
    }


    fun chooseAlgorithm(requested: V6Algorithm, budgetSec: Int): V6Algorithm {
        if (requested != V6Algorithm.AUTO) return requested
        // [3.266.0] 211秒以上は同型RSI++クローン群でなく、ALNS/RSI/RSI++の異種PORTFOLIOを使う
        //   （HypothesisDiversityPolicy.autoAlgorithmForBudget、旧 portfolioAlgoFor は
        //   runAdaptivePortfolio への置換で不要化したため削除）。
        return HypothesisDiversityPolicy.autoAlgorithmForBudget(budgetSec)
    }

}
