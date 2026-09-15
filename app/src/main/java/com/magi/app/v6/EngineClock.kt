package com.magi.app.v6

/**
 * [3.490.0] エンジン内の締切・経過・停滞判定に使う**単調時計**（ms）。
 *
 * 旧: `System.currentTimeMillis()`（壁時計）を締切に使っていた。壁時計は NTP 補正・手動の時刻変更・
 * スリープ復帰で前後する＝戻れば予算を大幅に超えて回り続け、進めば即時に期限切れ、フェーズ別の所要時間が
 * 負になる。`System.nanoTime()` は単調なので、エンジン内で扱う「時刻(ms)」はすべてここから取る
 * （呼出側・テストが渡す `deadlineMs` も同じ時計で作ること＝壁時計の値を混ぜると比較が成り立たない）。
 * ログに出す実時刻（`MirrorLog.ts`・`startedAt`）だけは壁時計のまま。
 */
object EngineClock {
    @JvmStatic fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /**
     * [3.491.0] 締切までの残り ms（負なら 0）。`deadlineMs == Long.MAX_VALUE`（締切なし）は
     * そのまま [Long.MAX_VALUE] を返す。旧: 呼出側が `(deadlineMs - now).coerceAtLeast(0)` と書いており、
     * `System.nanoTime()` は原点が任意（負も許される）ため `Long.MAX_VALUE - 負値` が桁あふれして
     * **残り 0 に化ける**余地があった（Android では起動からの経過で実際には正だが、JDK 契約上は保証されない）。
     */
    @JvmStatic fun remainingMs(deadlineMs: Long, nowMs: Long = nowMs()): Long =
        if (deadlineMs == Long.MAX_VALUE) Long.MAX_VALUE else (deadlineMs - nowMs).coerceAtLeast(0L)
}
