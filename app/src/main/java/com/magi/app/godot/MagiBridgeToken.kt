package com.magi.app.godot

import java.security.MessageDigest

// [Godot移行] 盤面/設定JSONのSHA-256 + 接続層の単調増加リビジョンをトークン化する。
// GodotはUIスレッド上の古いsnapshotを見て操作を組み立てることがあるため、dispatch側は
// 「そのsnapshotに対する操作か」をこのトークンで確認し、盤面が変わった後の古いdispatchを拒否する
// （＝取りこぼした編集を黙って上書きしない）。純Kotlin＝ホストJVMテスト対象。
object MagiBridgeToken {

    /** snapshotJson のハッシュ(16進64桁) + ":" + revision。 */
    fun compute(snapshotJson: String, revision: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(snapshotJson.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "$hex:$revision"
    }

    /** token が現在の snapshot/revision と一致するか。フォーマット不正は不一致扱い。 */
    fun verify(token: String, snapshotJson: String, revision: Long): Boolean {
        if (token.isBlank()) return false
        return token == compute(snapshotJson, revision)
    }

    /** token 文字列からリビジョン部分だけ取り出す（0以上の整数でなければ null）。 */
    fun revisionOf(token: String): Long? {
        val idx = token.lastIndexOf(':')
        if (idx < 0) return null
        return token.substring(idx + 1).toLongOrNull()
    }
}
