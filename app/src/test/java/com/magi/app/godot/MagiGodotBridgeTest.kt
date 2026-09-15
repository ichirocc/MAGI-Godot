package com.magi.app.godot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// [Godot移行] MagiBridge本体(Android/ViewModel依存)はホストJVMでは動かせないため、
// dispatch/snapshotが実際に使う純Kotlinロジック（許可リスト・トークンのハッシュ+リビジョン）を
// ここで単体テストする。tools/host/hosttest.sh から実行される。
class MagiGodotBridgeTest {

    @Test
    fun `許可リストにある操作は通る`() {
        assertTrue(MagiOpWhitelist.isAllowed("setCell"))
        assertTrue(MagiOpWhitelist.isAllowed("runV6FullOptimize"))
    }

    @Test
    fun `許可リストにない操作名は拒否される`() {
        assertFalse(MagiOpWhitelist.isAllowed("deleteEverything"))
        assertFalse(MagiOpWhitelist.isAllowed(""))
        // 内部実装(v6等)の任意メソッド名を偽装しても通さない
        assertFalse(MagiOpWhitelist.isAllowed("setCellUnchecked"))
    }

    @Test
    fun `必須引数が欠けたdispatchはhasRequiredArgsで検出できる`() {
        assertTrue(MagiOpWhitelist.hasRequiredArgs("setCell", setOf("i", "j", "shift")))
        assertFalse(MagiOpWhitelist.hasRequiredArgs("setCell", setOf("i", "j")))
        assertTrue(MagiOpWhitelist.hasRequiredArgs("stop", emptySet()))
    }

    @Test
    fun `stopは古いトークンでも例外的に受理対象`() {
        assertTrue("stop" in MagiOpWhitelist.staleTokenExempt)
        assertFalse("setCell" in MagiOpWhitelist.staleTokenExempt)
    }

    @Test
    fun `同じ本文と世代なら同じトークンになる`() {
        val a = MagiBridgeToken.compute("""{"staff":3}""", 1)
        val b = MagiBridgeToken.compute("""{"staff":3}""", 1)
        assertEquals(a, b)
        assertTrue(MagiBridgeToken.verify(a, """{"staff":3}""", 1))
    }

    @Test
    fun `盤面が変わるとトークンは一致しなくなる`() {
        val issued = MagiBridgeToken.compute("""{"staff":3}""", 1)
        // 盤面編集でrevisionが進んだ後に、古いissuedトークンを使うケースを模す
        assertFalse(MagiBridgeToken.verify(issued, """{"staff":3}""", 2))
        assertFalse(MagiBridgeToken.verify(issued, """{"staff":4}""", 1))
    }

    @Test
    fun `職員の並び替えは本文に反映され古い添字向けトークンが失効する`() {
        // MagiBridge.uiStateToJson は staffNames / structure.staff を並び順どおりに直列化する。
        // 並び替え前に発行したトークンは、世代が同じでも本文ハッシュが変わるので通らない
        // （＝古い添字 i で setCell/ws1EditStaff が別職員を書き換える事故を防ぐ）。
        val before = """{"staffNames":["A","B","C"],"schedule":[[0],[1],[2]]}"""
        val after = """{"staffNames":["B","A","C"],"schedule":[[1],[0],[2]]}"""
        val issued = MagiBridgeToken.compute(before, 5)
        assertTrue(MagiBridgeToken.verify(issued, before, 5))
        assertFalse(MagiBridgeToken.verify(issued, after, 5))
    }

    @Test
    fun `添字ベースの構造編集opは鮮度検査の例外にしない`() {
        for (op in listOf("setCell", "setCells", "ws1MoveStaffTo", "ws1MoveShiftTo", "ws1MoveGroupTo",
            "ws1RemoveStaff", "ws1EditStaff", "removeConstraint")) {
            assertTrue("$op は許可リストにあるべき", MagiOpWhitelist.isAllowed(op))
            assertFalse("$op を鮮度検査の例外にしてはいけない", op in MagiOpWhitelist.staleTokenExempt)
        }
    }

    @Test
    fun `不正フォーマットのトークンは常に不一致`() {
        assertFalse(MagiBridgeToken.verify("", "{}", 1))
        assertFalse(MagiBridgeToken.verify("garbage", "{}", 1))
    }

    @Test
    fun `トークンからリビジョンを取り出せる`() {
        val t = MagiBridgeToken.compute("""{"x":1}""", 42)
        assertEquals(42L, MagiBridgeToken.revisionOf(t))
        assertEquals(null, MagiBridgeToken.revisionOf("no-colon-here"))
    }
}
