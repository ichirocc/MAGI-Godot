package com.magi.app

import android.icu.text.Transliterator

/**
 * シフト・グループ記号の「表示」を全角→半角に正規化するユーティリティ。
 *
 * 例:
 *   「Dテ」→「Dﾃ」 / 「Aア」→「Aｱ」 / 「Cオ」→「Cｵ」 / 「Ｂ４」→「B4」
 *   既に半角のもの（Dﾃ, B4 等）や漢字（休, 有 等）はそのまま（冪等）。
 *
 * 重要:
 *   これは「表示専用」のヘルパである。元データ（shifts[].kigou / groups[].kigou）は
 *   一切書き換えない。したがって CSV / VBA(CP932) 出力・最適化ロジック・制約照合・
 *   シフト色解決などには影響しない。見た目を全画面でそろえるためだけに使う。
 */
private val FULLWIDTH_TO_HALFWIDTH: Transliterator? =
    try {
        Transliterator.getInstance("Fullwidth-Halfwidth")
    } catch (e: Throwable) {
        null
    }

/**
 * 全角→半角の表示正規化を行う。
 * ICU の変換器が利用できない環境では原文をそのまま返し、クラッシュさせない。
 * Transliterator はスレッドセーフではないため synchronized で保護する
 * （呼び出しは状態更新時/コンポーズ時の有限回で、毎フレームではない）。
 */
fun toHankakuKigou(s: String): String =
    FULLWIDTH_TO_HALFWIDTH?.let { synchronized(it) { it.transliterate(s) } } ?: s
