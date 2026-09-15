package com.magi.app.work

/**
 * [3.485.0] 保存の世代ゲート。`saveJob?.cancel()` は**既に始まった書き込みを止められない**ので、
 * 古い自動保存（状態A）の書き込みが、後から始まった `saveNow()`（状態B）の後に完了すると自動保存
 * ファイルが A へ戻る（原子置換は破損を防ぐが、順序の逆転は防げない）。
 *
 * 書き手は main で世代を採番し（`exportJson()` と同じ時点＝状態の順序と一致）、[writeIfLatest] が
 * ロック下で「より新しい世代が書かれた後の古い世代」を捨てる。ロックで直列化するため
 * 「確認→書き込み」の間に別の書き手が割り込むことはない。
 */
class SaveGate {
    private val lock = Any()
    private var lastWritten = 0

    /**
     * @return 書いた=true／書き込み失敗=false／より新しい世代が既に書かれていたので捨てた=null。
     */
    fun writeIfLatest(gen: Int, write: () -> Boolean): Boolean? = synchronized(lock) {
        if (gen < lastWritten) return@synchronized null
        val ok = write()
        if (ok) lastWritten = gen
        ok
    }
}
