package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * Free系リペア（[V6NativeOptimizer] の `applyCovOFree`/`applyC41Free`/`applyC42Free` 等）が使う
 * 候補コミットヘルパー。[V6NativeOptimizer] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない
 * （唯一の書き換えは下記）。
 *
 * **共有可変状態を一切参照しない純粋な計算関数**（[V6NativeOptimizer] 本体は @Volatile フィールド・
 * Atomic系・RunSlotのコルーチンコンテキスト連携で並行実行状態を密結合する「統括状態機械」の性格が
 * 強く、機械的な抽出は危険＝本ファイルはその中の安全な塊だけを切り出す）。sched の in-place 変更は
 * 引数として渡された盤面配列への正当な副作用であり、オブジェクト自身の共有状態ではない。
 *
 * [commitBestMove]：候補（セル代入の束）を1つずつ一時適用し実チェッカーで評価、baseline に対し
 * 真に改善する候補の中から最良の1件だけを選んでコミットする。改善する候補が無ければ何もしない。
 *
 * **唯一の書き換え**: 元の本体は `better(a,b)`（[V6NativeOptimizer] の `private fun`、
 * `betterReport` への1行委譲）を呼んでいたが、抽出先からは private 関数を呼べないため、
 * 委譲先の [betterReport]（同一パッケージのpublicトップレベル関数、MirrorCore.kt）を
 * 直接呼ぶ形へ書き換えた（意味論は完全に同一＝`better`自体が`betterReport`への単純委譲のため）。
 *
 * **抽出時に発見した既存の文書配置の乱れ**: 元位置(旧2596-2612行)の直前には、無関係な
 * `applyCovOFree`自身を説明するKDocブロック(旧2579-2595行)が連続して存在していた
 * （過去のリファクタで本関数が両者の間に挿入され、`applyCovOFree`のKDocが追従しなかったと
 * 推測される）。本抽出はそのKDocを持ち込まず[V6NativeOptimizer]側に残置し、結果として
 * 削除後は自然に`applyCovOFree`の直前へ再配置される（副次的なドキュメント配置の改善）。
 *
 * 呼び出し側は全て`V6NativeOptimizer.commitBestMove`の完全修飾で参照していたため、抽出時に
 * `CandidateCommit.commitBestMove`へ一括置換した（4箇所、いずれも[V6NativeOptimizer]の
 * `applyCovOFree`/`applyC41Free`/`applyC42Free`等の内部から）。
 */
internal object CandidateCommit {
    /**
     * [3.253.0, 実データ検証で判明した「Free」系リペア共通の欠陥を修正] `applyCovOFree`/`applyC41Free`/
     * `applyC42Free` は従来「移動先/移動元のcovU/covOだけを見て構造的に安全な最初の候補」を採用しており、
     * 動かす本人自身の他制約(staffRange低/高・apt・c1・c2・weekly・fair等)への影響を一切見ずに移動していた。
     * 実データ検証(golden_state.json/sample_state_v6.json、ホストJVM実行で独立検証)で、covOは単体実行の
     * 大半の試行でtotalを悪化(313→325〜351)、c42はgolden 15/15・sample_v6 11/15が悪化——「動かせる」は
     * 「動かして得」を意味しないことを確認した（ユーザー指摘「大嶋と美幸の違反研磨は適切か」を機に、
     * AptPolish/RangePolishは既に全候補で実チェッカー+isBetter/betterのkeep-best gateを持つ健全な実装と
     * 確認済み＝この欠陥はcovO/c41/c41s/c42/c42s専用のFree系のみ）。
     *
     * 候補（セル代入の束＝直接移動、または移動＋玉突き連鎖の複合手）を1つずつ実際に一時適用し、
     * UnifiedViolationChecker で全体評価、baseline(この手を試す直前の盤面)に対して真に改善する
     * (betterReport()=hard→weighted→total辞書式で厳密改善)候補の中から最良の1件だけを選んでコミットする。
     * 改善する候補が1つも無ければ何もしない(null)＝そのセルは諦める（安全側・退化不能）。
     * 実装コストは度外視（ユーザー指示）＝候補ごとにフルcheckを行うため計算量は増えるが、
     * これらはRSI 1ラウンドにつき1回しか呼ばれない仮説生成器のため許容範囲。
     */
    internal fun commitBestMove(
        state: MagiState, sched: Array<IntArray>,
        baseline: ViolationReport, candidates: List<List<IntArray>>,
        quantitativeRangeEval: Boolean = false,
    ): ViolationReport? {
        var bestOps: List<IntArray>? = null
        var bestRep: ViolationReport? = null
        for (ops in candidates) {
            val saved = IntArray(ops.size) { sched[ops[it][0]][ops[it][1]] }
            for (mv in ops) sched[mv[0]][mv[1]] = mv[2]
            val rep = UnifiedViolationChecker.check(state, sched, quantitativeRangeEval = quantitativeRangeEval)
            for (idx in ops.indices) sched[ops[idx][0]][ops[idx][1]] = saved[idx]
            if (betterReport(rep, baseline) && (bestRep == null || betterReport(rep, bestRep!!))) {
                bestOps = ops; bestRep = rep
            }
        }
        val ops = bestOps ?: return null
        for (mv in ops) sched[mv[0]][mv[1]] = mv[2]
        return bestRep
    }

}
