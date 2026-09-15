package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 病院勤務表テンプレCSVの取込検証。テキストは復号済み(UTF-8)前提＝エンコーディング非依存の構造解析を確認する。
 * 列位置（氏名=idx2 / シフト記号 idx4〜 / 凡例 記号=idx1, 必要数 idx4〜）と空セル→休 を中心に検証。
 */
class RosterCsvImportTest {

    private val sample = listOf(
        "令和8年,,,7,月",
        "ユニット名：,,柳,,1,2,3",
        "№,,氏 名,,水,木,金",
        "1,リーダー,古泉 健一,予定,Aｱ,,休",
        "2,,山本 昌幸,予定,A4,休,",
        "3,,,予定,,,",
        ",,,,,,",
        "ユニット名：,,桐,,1,2,3",
        "№,,氏 名,,水,木,金",
        "1,主任,上條 洋平,予定,B4,休,Aｱ",
        ",,,,,,",
        ",記号,時刻,休憩時間,水,木,金",
        ",A4,6:00～15:00,1h,1,0,1",
        ",Aｱ,7:30～16:30,1h,2,0,1",
        ",B4,8:30～17:30,1h,1,0,0",
        ",休,定休,,1,2,1",
    ).joinToString("\n")

    @Test fun detectsTemplate() {
        assertTrue(RosterCsvImport.detect(sample))
        assertTrue(!RosterCsvImport.detect("name,1,2,3\nA,休,休,休"))
    }

    @Test fun parsesUnitsStaffShiftsAndGrid() {
        val st = RosterCsvImport.parse(sample)
        assertNotNull(st)
        st!!

        // 期間: 令和8年7月 → 2026-07-01、3日
        assertEquals("2026-07-01", st.startDate)
        assertEquals(3, st.dayCount)

        // ユニット=グループ（柳・桐）
        assertEquals(2, st.groupCount)
        assertEquals("柳", st.groups[0].kigou)
        assertEquals("桐", st.groups[1].kigou)

        // スタッフ（空欄№3は除外）。柳=2名 + 桐=1名 = 3名。
        assertEquals(3, st.staffCount)
        assertEquals("古泉 健一", st.staff[0].name)
        assertEquals(0, st.staff[0].groupIdx)
        assertEquals("上條 洋平", st.staff[2].name)
        assertEquals(1, st.staff[2].groupIdx)

        // シフトは凡例から（A4, Aｱ, B4, 休）
        val k = st.shifts.associate { it.kigou to st.shifts.indexOf(it) }
        assertNotNull(k["A4"]); assertNotNull(k["Aｱ"]); assertNotNull(k["B4"]); assertNotNull(k["休"])

        // 勤務表グリッド（空セル→休）
        val rest = k.getValue("休")
        // 古泉: Aｱ, (空→休), 休
        assertEquals(k.getValue("Aｱ"), st.schedule[0][0])
        assertEquals(rest, st.schedule[0][1])
        assertEquals(rest, st.schedule[0][2])
        // 山本: A4, 休, (空→休)
        assertEquals(k.getValue("A4"), st.schedule[1][0])
        assertEquals(rest, st.schedule[1][2])
        // 上條(桐): B4, 休, Aｱ
        assertEquals(k.getValue("B4"), st.schedule[2][0])
        assertEquals(k.getValue("Aｱ"), st.schedule[2][2])

        // 必要人数はCSVに無い（凡例の日別数値は現在表の人数集計＝需要ではない）→ needDay は取り込まない。
        assertTrue(st.needDay1.isEmpty())
        assertTrue(st.needDay2.isEmpty())
        assertTrue(st.shifts.all { it.need1.isBlank() && it.need2.isBlank() })

        // 担当可否は不明→全シフト可で取込
        assertEquals(st.shiftCount, st.groupShift[0].size)
        assertTrue(st.groupShift[0].all { it == 1 })
    }

    @Test fun parsesAsWishesLeavesScheduleEmptyAndFillsWishes() {
        val st = RosterCsvImport.parse(sample, asWishes = true)
        assertNotNull(st)
        st!!
        val k = st.shifts.associate { it.kigou to st.shifts.indexOf(it) }
        val rest = k.getValue("休")

        // 希望モード: 勤務表は全て公休で開始（最適化が希望を尊重して埋める）。
        for (i in 0 until st.staffCount) for (j in 0 until st.dayCount) {
            assertEquals(rest, st.schedule[i][j])
        }
        // 埋まっていたセルは希望として登録（空セルは希望なし）。元の明示「休」は希望休として残る。
        assertEquals(k["Aｱ"], st.wishes["0,0"])   // 古泉 d0
        assertNull(st.wishes["0,1"])               // 空セル→希望なし
        assertEquals(rest, st.wishes["0,2"])       // 古泉 d2 = 希望休
        assertEquals(k["A4"], st.wishes["1,0"])    // 山本 d0
        assertEquals(k["B4"], st.wishes["2,0"])    // 上條(桐) d0
        assertEquals(k["Aｱ"], st.wishes["2,2"])    // 上條 d2

        // 必要人数は取込方法に依らずCSVに無い。
        assertTrue(st.needDay1.isEmpty())
    }
}
