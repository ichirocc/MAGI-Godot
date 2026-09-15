package com.magi.app.v6

/**
 * 被覆穴埋めのドナー選定/コスト計算。[V6NativeOptimizer] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * 両メンバとも**共有可変状態を一切参照しない純粋な計算関数**（[V6NativeOptimizer] 本体は
 * @Volatile フィールド・Atomic系・RunSlotのコルーチンコンテキスト連携で並行実行状態を密結合する
 * 「統括状態機械」の性格が強く、機械的な抽出は危険＝本ファイルはその中の安全な塊だけを切り出す）。
 *
 * - [bestStaffForCoverage]：日jのシフトkの被覆穴を埋める職員を「上限超過500＋現回数×3＋
 *   引き抜き不足コスト」最小で選ぶ（hf67HardRepair が使用）。
 * - [coverageShortageCost]：ある職員を(j,k)から引き抜くと per-cell 実需要(covU)が悪化するかの判定。
 *   [bestStaffForCoverage] が内部で呼ぶ（同一ファイル内自己呼出のため無修飾のまま）。
 *
 * 呼び出し側（[V6NativeOptimizer] 本体の `hf67HardRepair`）は全て`V6NativeOptimizer.<name>`の
 * 完全修飾で参照していたため、抽出時に`CoverageRepairScoring.<name>`へ一括置換した。
 */
internal object CoverageRepairScoring {
    internal fun bestStaffForCoverage(p: Problem, schedule: Array<IntArray>, counts: Array<IntArray>, j: Int, k: Int): Int {
        var bestI = -1
        var bestScore = Int.MAX_VALUE
        for (i in 0 until p.S) {
            if (!p.mayPlace(i, k)) continue
            if (p.wishLocked(i, j) && p.wish[i][j] != k) continue
            val old = schedule[i][j]
            if (old == k) continue   // [監査#3] 既就業者はスキップ（旧: return で当該(日,シフト)の充填全体が中断していた）
            val hi = p.rangeHi[i][k]
            val over = if (hi != Int.MAX_VALUE && counts[i][k] >= hi) 500 else 0
            val oldNeedCost = coverageShortageCost(p, schedule, j, old)
            // [監査#12] 符号修正: 旧 `- oldNeedCost` は「外すと不足が生じる職員」ほど優先ドナー化していた
            //   （最小スコア採用のため減算=優遇）。引き抜きコストとして加算し、休・過剰被覆側を優先する。
            val score = over + counts[i][k] * 3 + oldNeedCost
            if (score < bestScore) { bestScore = score; bestI = i }
        }
        return bestI
    }


    internal fun coverageShortageCost(p: Problem, schedule: Array<IntArray>, j: Int, k: Int): Int {
        if (k !in 0 until p.K) return 0
        var cov = 0
        for (i in 0 until p.S) if (schedule[i][j] == k) cov++
        // [N1a] 引き抜きで per-cell 実需要(U)が増える＝不足を生む職員はコスト50（旧: need1のみ基準）。
        //   ちょうど充足のセル(U=0→1)も保護される点は旧 `cov <= need` と同等。
        return if (p.covUCell(k, j, cov - 1) > p.covUCell(k, j, cov)) 50 else 0
    }


}
