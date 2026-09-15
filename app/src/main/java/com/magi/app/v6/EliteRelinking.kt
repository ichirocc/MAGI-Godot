package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * エリート解の Path Relinking（Glover, Laguna & Martí 2000 / Scatter Search）。
 * [V6NativeOptimizer] から抽出（責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。
 * ロジックは一切変更していない（唯一の書き換えは下記）。
 *
 * **共有可変状態を一切参照しない純粋な計算関数**（[V6NativeOptimizer] 本体は @Volatile フィールド・
 * Atomic系・RunSlotのコルーチンコンテキスト連携で並行実行状態を密結合する「統括状態機械」の性格が
 * 強く、機械的な抽出は危険＝本ファイルはその中の安全な塊だけを切り出す）。
 *
 * [elitePathRelink]：並列ポートフォリオが保持する精鋭解（呼出元の alternatives）と現行最良を
 * 「再結合」し、両者の中間にしばしば存在する良解を拾う。
 *
 * **唯一の書き換え**: 元の本体は `better(a,b)`（[V6NativeOptimizer] の `private fun`、
 * `betterReport` への1行委譲）を呼んでいたが、抽出先からは private 関数を呼べないため、
 * 委譲先の [betterReport]（同一パッケージのpublicトップレベル関数、MirrorCore.kt）を
 * 直接呼ぶ形へ書き換えた（意味論は完全に同一＝`better`自体が`betterReport`への単純委譲のため）。
 *
 * 呼び出し側は全て`V6NativeOptimizer.elitePathRelink`の完全修飾で参照していたため、抽出時に
 * `EliteRelinking.elitePathRelink`へ一括置換した。本体内部からの無修飾自己呼出（元位置970行）も
 * 同様に完全修飾へ書き換えた。
 */
internal object EliteRelinking {
    /**
     * [品質向上] エリート解の Path Relinking（Glover, Laguna & Martí 2000 / Scatter Search）。
     * 並列ポートフォリオが保持する精鋭解（[lastAlternatives]）と現行最良を「再結合」し、両者の中間に
     * しばしば存在する、どの単独軌道でも届かない良解を拾う。best を起点に各 alt へ強制マーチ（差分セルを
     * alt 値へ順次適用）し、経路上の最良中間解を保持。常に best 起点から評価するので**退化しない**。
     * 早期停止で空いた予算を、頭打ちした同種探索ではなく「別種の探索」に充てて品質を底上げする。
     */
    fun elitePathRelink(
        state: MagiState,
        best: Array<IntArray>,
        alternatives: List<Array<IntArray>>,
        shouldStop: () -> Boolean,
        quantitativeRangeEval: Boolean = false,
    ): Pair<Array<IntArray>, ViolationReport> {
        var bestSched = best.copy2D()
        var bestRep = UnifiedViolationChecker.check(state, bestSched, quantitativeRangeEval = quantitativeRangeEval)
        if (alternatives.isEmpty()) return bestSched to bestRep
        for (alt in alternatives) {
            if (shouldStop()) break
            val cur = bestSched.copy2D()              // 常に現行最良から再結合（中間最良は別管理＝退化なし）
            var curRep = UnifiedViolationChecker.check(state, cur, quantitativeRangeEval = quantitativeRangeEval)
            val diffs = ArrayList<Pair<Int, Int>>()
            for (i in cur.indices) for (j in cur[i].indices) {
                if (i < alt.size && j < alt[i].size && cur[i][j] != alt[i][j]) diffs.add(i to j)
            }
            if (diffs.isEmpty()) continue
            // 違反セルを先に動かす（インパクト大の組み替えを前倒し）。
            val vcells = HashSet<Pair<Int, Int>>()
            for (vkey in curRep.violations.keys) {
                val parts = vkey.split(',')
                val ci = parts.getOrNull(0)?.toIntOrNull(); val cj = parts.getOrNull(1)?.toIntOrNull()
                if (ci != null && cj != null) vcells.add(ci to cj)
            }
            diffs.sortBy { if (it in vcells) 0 else 1 }
            for ((i, j) in diffs) {
                if (shouldStop()) break
                cur[i][j] = alt[i][j]                 // alt へ向けた強制マーチ
                curRep = UnifiedViolationChecker.check(state, cur, quantitativeRangeEval = quantitativeRangeEval)
                if (betterReport(curRep, bestRep)) { bestSched = cur.copy2D(); bestRep = curRep }
            }
        }
        return bestSched to bestRep
    }


}
