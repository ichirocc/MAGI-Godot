package com.magi.app
// Host-harness stub for KigouFormat.kt (real one uses android.icu Transliterator "Fullwidth-Halfwidth").
private val KANA = ("ァｧアｱィｨイｲゥｩウｳェｪエｴォｫオｵカｶキｷクｸケｹコｺサｻシｼスｽセｾソｿタﾀチﾁッｯツﾂテﾃトﾄナﾅニﾆヌﾇネﾈノﾉハﾊヒﾋフﾌヘﾍホﾎマﾏミﾐムﾑメﾒモﾓャｬヤﾔュｭユﾕョｮヨﾖラﾗリﾘルﾙレﾚロﾛワﾜヲｦンﾝー-。｡「｢」｣、､・･")
    .let { s -> (s.indices step 2).associate { s[it] to s[it + 1].toString() } }
private val DAKU = "ガギグゲゴザジズゼゾダヂヅデドバビブベボ".mapIndexed { i, c -> c to "カキクケコサシスセソタチツテトハヒフヘホ"[i] }.toMap()
private val HANDAKU = "パピプペポ".mapIndexed { i, c -> c to "ハヒフヘホ"[i] }.toMap()
fun toHankakuKigou(s: String): String = buildString {
    for (c in s) when {
        c == '　' -> append(' ')
        c in '！'..'～' -> append((c.code - 0xFF01 + 0x21).toChar())
        c in DAKU -> append(KANA[DAKU[c]!!]).append('ﾞ')
        c in HANDAKU -> append(KANA[HANDAKU[c]!!]).append('ﾟ')
        c == 'ヴ' -> append("ｳﾞ")
        c in KANA -> append(KANA[c])
        else -> append(c)
    }
}
