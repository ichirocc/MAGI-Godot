package com.magi.app.v6

/**
 * 盤面の同値鍵: [AdaptiveEliteArchive.scheduleHash] を一次キーに、衝突時だけ全セルを比較する
 * （ビームの重複排除用。旧: 全セルを区切り文字つき文字列へ連結していた）。
 */
internal class BoardKey(val work: Array<IntArray>) {
    private val h = AdaptiveEliteArchive.scheduleHash(work)
    override fun hashCode(): Int = (h xor (h ushr 32)).toInt()
    override fun equals(other: Any?): Boolean = other is BoardKey && h == other.h && AdaptiveEliteArchive.sameSchedule(work, other.work)
}
