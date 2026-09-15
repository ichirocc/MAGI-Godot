package com.magi.app.v6

import com.magi.app.model.MagiState

/** 改善手の種類（UIのチップ表示用）。 */
enum class FixKind { CHANGE, CHANGE_MULTI, SWAP, SWAP_XDAY, SWAP_MULTI, CHAIN, WINDOW }

/** 1セルへの代入（move = これらを盤面にセットする）。 */
data class FixCell(val staff: Int, val day: Int, val toShift: Int)

/**
 * [改善提案] 1手で違反がどれだけ減るかを評価した候補。
 * move は ops（セル代入の集合）で表現し、適用は ops を順にセットするだけ（全種類を統一）。
 *  - CHANGE       : 1マスを別シフトへ
 *  - CHANGE_MULTI : 同一スタッフの2マスを同時変更（下限の競合など、1マスでは直せない不足に有効）
 *  - SWAP         : 同日2人を入れ替え（被覆不変）
 *  - SWAP_XDAY    : 別日どうしを入れ替え（被覆が両日で変化）
 *  - SWAP_MULTI   : 同日3人を巡回交換（2人交換が担当可否で塞がる時の打開）
 *  - CHAIN        : 不足シフトを貪欲に最大3コマ補充（エジェクションチェーン／玉突き）
 *  - WINDOW       : 1日×最大4名を総当たりで最適割当（ミニ・マスヒューリスティクス）
 */
data class FixSuggestion(
    val kind: FixKind,
    val ops: List<FixCell>,
    val label: String,
    val deltaHard: Int,
    val deltaTotal: Int,
    val diff: List<Pair<String, Int>>,
)

/**
 * 違反を減らす「1手」を列挙する。最適化エンジンと同じ評価（mayPlace 可否・希望ロック保護・
 * UnifiedViolationChecker による被覆込み (hard,weighted,total) 辞書式改善）。CHANGE / CHANGE_MULTI /
 * SWAP / SWAP_XDAY / SWAP_MULTI / CHAIN / WINDOW を統合し、効果順・同型重複排除で返す。読取専用。
 * 高コストな手（複数マス・別日・3人）は違反箇所にターゲットし、締切（deadlineMs）で打ち切る賢い探索。
 */
object FixSuggester {
    /** 探索の上限（3.507.4 で集約。値は 2.4x 系からの据え置き）。 */
    internal object Limits {
        const val CHAIN_ROUNDS = 3          // 連鎖で積み上げる最大コマ数
        const val WINDOW_STAFF = 4          // 再最適化で同時に動かす最大人数
        const val WINDOW_COMBOS = 20_000L   // 再最適化 1 日あたりの総当たり上限（超えれば人数を減らす）
        const val WINDOW_DAYS = 5           // 再最適化する最大日数
        const val PACK = 1000               // (staff, day) を staff*PACK+day で詰める（T<=31 の前提）
    }

    fun suggest(
        state: MagiState,
        schedule: Array<IntArray>,
        focusStaff: Int? = null,
        focusShift: Int? = null,
        maxResults: Int = 8,
        deadlineMs: Long = 8000L,
    ): List<FixSuggestion> {
        val p = Problem(state)
        if (p.S < 1 || p.T < 1) return emptyList()
        return Session(state, p, normalizeSchedule(schedule, p), focusStaff, focusShift, deadlineMs).run(maxResults)
    }

    private class Quad(val sug: FixSuggestion, val dHard: Int, val dTotal: Int, val dWeighted: Double)

    /** 1 回の提案探索。盤面 [s] は各手を適用→評価→復元するので、フェーズ間で常に入力（正規化後）に一致する。 */
    private class Session(
        private val state: MagiState, private val p: Problem, private val s: Array<IntArray>,
        private val focus: Int?, private val focusShift: Int?, private val deadlineMs: Long,
    ) {
        private val base = UnifiedViolationChecker.check(state, s)
        private val found = ArrayList<Quad>()
        private val start = EngineClock.nowMs()

        // 違反に関与する staff / day / shift のターゲット集合。
        private val countHot = HashSet<Int>()                  // 回数違反(low/high)のある staff
        private val shortShift = HashMap<Int, MutableSet<Int>>()  // staff -> 下限割れのシフト集合
        private val hotCells = ArrayList<Int>()                // staff*PACK+day（セル違反）
        private val hotDays = HashSet<Int>()

        init {
            for ((key, cls) in base.countViolations) {
                val (i, k) = parseKey(key) ?: continue
                countHot.add(i)
                if (cls == "vio-low") shortShift.getOrPut(i) { HashSet() }.add(k)
            }
            for (key in base.violations.keys) {
                val (i, j) = parseKey(key) ?: continue
                hotCells.add(i * Limits.PACK + j); hotDays.add(j)
            }
            for (key in base.needViolations.keys) parseKey(key)?.let { hotDays.add(it.second) }
        }

        private fun parseKey(key: String): Pair<Int, Int>? {
            val pp = key.split(",")
            val a = pp.getOrNull(0)?.toIntOrNull() ?: return null
            val b = pp.getOrNull(1)?.toIntOrNull() ?: return null
            return a to b
        }
        private fun nm(i: Int) = state.staff.getOrNull(i)?.name ?: "#$i"
        private fun sym(k: Int) = if (k >= 0) (state.shifts.getOrNull(k)?.kigou ?: "$k") else "—"
        private fun dlab(j: Int): String = try {
            val d = java.time.LocalDate.parse(state.startDate).plusDays(j.toLong())
            "${d.monthValue}/${d.dayOfMonth}"
        } catch (e: Exception) { "${j + 1}日" }
        private fun diffOf(rep: ViolationReport): List<Pair<String, Int>> {
            val out = ArrayList<Pair<String, Int>>()
            for (k in (base.breakdown.keys + rep.breakdown.keys)) {
                val d = (rep.breakdown[k] ?: 0) - (base.breakdown[k] ?: 0)
                if (d != 0) out.add(k to d)
            }
            out.sortBy { it.second }
            return out
        }
        private fun timeUp() = EngineClock.nowMs() - start > deadlineMs
        private fun inFocus(i: Int) = focus == null || i == focus
        private fun pairFocus(i: Int, i2: Int) = focus == null || i == focus || i2 == focus
        private fun targetStaff(): List<Int> = if (focus != null) listOf(focus) else countHot.toList()
        private fun targetDays(): List<Int> = if (focus != null) (0 until p.T).toList() else hotDays.toList()

        /** [ops] を当てた盤面の report（必ず元へ戻す）。 */
        private fun evalOps(ops: List<FixCell>): ViolationReport {
            val saved = IntArray(ops.size) { s[ops[it].staff][ops[it].day] }
            for (op in ops) s[op.staff][op.day] = op.toShift
            val rep = UnifiedViolationChecker.check(state, s)
            for (idx in ops.indices) s[ops[idx].staff][ops[idx].day] = saved[idx]
            return rep
        }
        private fun record(kind: FixKind, ops: List<FixCell>, label: String, rep: ViolationReport) {
            found.add(Quad(FixSuggestion(kind, ops, label, rep.hard - base.hard, rep.total - base.total, diffOf(rep)),
                rep.hard - base.hard, rep.total - base.total, rep.weightedScore - base.weightedScore))
        }
        /** ops をその場で適用→評価→復元。base より良ければ候補に追加。 */
        private fun tryOps(kind: FixKind, ops: List<FixCell>, label: String) {
            val rep = evalOps(ops)
            if (betterReport(rep, base)) record(kind, ops, label, rep)
        }

        fun run(maxResults: Int): List<FixSuggestion> {
            singleChanges()
            sameDaySwaps()
            multiChanges()
            chains()
            windows()
            rotations3()
            crossDaySwaps()
            return collect(maxResults)
        }

        /** Phase 1: 単一マス変更（広く）。 */
        private fun singleChanges() {
            for (i in 0 until p.S) {
                if (!inFocus(i)) continue
                val allowed = p.allowedShiftsForStaff(i)
                for (j in 0 until p.T) {
                    if (p.wishLocked(i, j)) continue
                    val a = s[i][j]
                    for (k in allowed) {
                        if (k == a || timeUp()) continue
                        tryOps(FixKind.CHANGE, listOf(FixCell(i, j, k)), "${nm(i)} ${dlab(j)} 「${sym(a)}」→「${sym(k)}」")
                    }
                }
            }
        }

        /** Phase 2: 同日 2 人交換。 */
        private fun sameDaySwaps() {
            for (i in 0 until p.S) for (i2 in i + 1 until p.S) {
                if (!pairFocus(i, i2)) continue
                for (j in 0 until p.T) {
                    if (timeUp()) break
                    if (p.wishLocked(i, j) || p.wishLocked(i2, j)) continue
                    val a = s[i][j]; val b = s[i2][j]
                    if (a == b || !p.mayPlace(i, b) || !p.mayPlace(i2, a)) continue
                    tryOps(FixKind.SWAP, listOf(FixCell(i, j, b), FixCell(i2, j, a)),
                        "${nm(i)} 「${sym(a)}」 ↔ ${nm(i2)} 「${sym(b)}」（${dlab(j)}）")
                }
            }
        }

        /** Phase 3: 同一スタッフの 2 マス同時変更（下限割れ当事者にターゲット）。 */
        private fun multiChanges() {
            for (i in targetStaff()) {
                if (timeUp()) break
                val allowed = p.allowedShiftsForStaff(i)
                // 目標シフト = そのstaffの下限割れシフト ∪ 休（記号で解決した restIdx）。なければ置けるシフト全部。
                val targets: List<Int> = (shortShift[i]?.toList() ?: emptyList())
                    .let { if (it.isEmpty()) allowed.toList() else it + p.restIdx }
                    .distinct().filter { k -> allowed.contains(k) }
                val cells = (0 until p.T).filter { !p.wishLocked(i, it) }
                for (a in cells.indices) {
                    if (timeUp()) break
                    for (b in a + 1 until cells.size) {
                        if (timeUp()) break
                        val j1 = cells[a]; val j2 = cells[b]; val s1 = s[i][j1]; val s2 = s[i][j2]
                        for (k1 in targets) {
                            if (k1 == s1) continue
                            for (k2 in targets) {
                                if (k2 == s2 || timeUp()) continue
                                tryOps(FixKind.CHANGE_MULTI, listOf(FixCell(i, j1, k1), FixCell(i, j2, k2)),
                                    "${nm(i)} ${dlab(j1)}「${sym(s1)}」→「${sym(k1)}」＋${dlab(j2)}「${sym(s2)}」→「${sym(k2)}」")
                            }
                        }
                    }
                }
            }
        }

        /** Phase 6: エジェクションチェーン（不足シフトを貪欲に最大 CHAIN_ROUNDS コマ充足。文書§2 玉突き）。 */
        private fun chains() {
            for (i in targetStaff()) {
                if (timeUp()) break
                val shorts = shortShift[i] ?: continue
                for (x in shorts) {
                    if (timeUp()) break
                    if (!p.mayPlace(i, x)) continue   // [3.507.4] 上限 0 のシフトは最適化器と同じく置かない
                    val picked = ArrayList<FixCell>()
                    val applied = ArrayList<Pair<Int, Int>>()   // (day, savedShift) 復元用
                    while (picked.size < Limits.CHAIN_ROUNDS && !timeUp()) {
                        // 現在の積み上げ盤面のスコアを基準に、x へ変えて更に改善する可動コマを1つ選ぶ（単調改善を保証）
                        var bestRep = UnifiedViolationChecker.check(state, s)
                        var bestJ = -1; var bestSaved = -1
                        for (j in 0 until p.T) {
                            if (p.wishLocked(i, j)) continue
                            val a = s[i][j]
                            if (a == x) continue
                            s[i][j] = x
                            val rep = UnifiedViolationChecker.check(state, s)
                            s[i][j] = a
                            if (betterReport(rep, bestRep)) { bestRep = rep; bestJ = j; bestSaved = a }
                        }
                        if (bestJ < 0) break
                        s[i][bestJ] = x; picked.add(FixCell(i, bestJ, x)); applied.add(bestJ to bestSaved)
                    }
                    for ((j, sv) in applied) s[i][j] = sv   // 復元
                    // 2コマ以上のときだけ採用（1コマは単一変更で既出）。base 改善を再確認。
                    if (picked.size >= 2) tryOps(FixKind.CHAIN, picked.toList(), "（連鎖）${nm(i)} の「${sym(x)}」不足を${picked.size}コマ補充")
                }
            }
        }

        /** Phase 7: ミニ再最適化（1 日 × 最大 WINDOW_STAFF 名を総当たりで最適割当。文書§4 マスヒューリスティクスのミニ版）。 */
        private fun windows() {
            var windows = 0
            for (j in targetDays()) {
                if (timeUp() || windows >= Limits.WINDOW_DAYS) break
                val movable = (0 until p.S).filter { !p.wishLocked(it, j) }
                if (movable.size < 2) continue
                // 違反関与(countHot)を優先、focus があれば先頭に。
                val ranked = movable.sortedByDescending { it in countHot }
                val chosen0 = if (focus != null) (ranked.filter { it == focus } + ranked.filter { it != focus }) else ranked
                var n = minOf(Limits.WINDOW_STAFF, chosen0.size)
                val cells0 = chosen0.take(Limits.WINDOW_STAFF)
                val opts0 = cells0.map { p.allowedShiftsForStaff(it).toList() }
                fun combos(m: Int): Long { var c = 1L; for (t in 0 until m) c *= opts0[t].size; return c }
                while (n > 2 && combos(n) > Limits.WINDOW_COMBOS) n--
                if (n < 2 || combos(n) > Limits.WINDOW_COMBOS) continue
                val cells = cells0.take(n)
                val cellOpts = opts0.take(n)
                val cur = IntArray(n) { s[cells[it]][j] }
                windows++
                val sizes = IntArray(n) { cellOpts[it].size }
                val idx = IntArray(n)
                var bestComboRep: ViolationReport? = null
                var bestCombo: IntArray? = null
                while (true) {
                    for (c in 0 until n) s[cells[c]][j] = cellOpts[c][idx[c]]
                    val rep = UnifiedViolationChecker.check(state, s)
                    if (betterReport(rep, base) && (bestComboRep == null || betterReport(rep, bestComboRep))) {
                        bestComboRep = rep; bestCombo = IntArray(n) { cellOpts[it][idx[it]] }
                    }
                    var c = 0
                    while (c < n) { idx[c]++; if (idx[c] < sizes[c]) break; idx[c] = 0; c++ }
                    if (c == n || timeUp()) break
                }
                for (c in 0 until n) s[cells[c]][j] = cur[c]   // 復元
                val bc = bestCombo; val rep = bestComboRep
                if (rep != null && bc != null) {
                    val ops = (0 until n).filter { bc[it] != cur[it] }.map { FixCell(cells[it], j, bc[it]) }
                    // 最良組合せの report をそのまま使う（旧: 同じ盤面を作り直してもう 1 回評価していた）。
                    if (ops.size >= 2) record(FixKind.WINDOW, ops, "（再最適化）${dlab(j)} の${ops.size}名を最適割当", rep)
                }
            }
        }

        /** Phase 4: 同日 3 人巡回交換（被覆不変・違反日にターゲット）。a を最小に固定して重複列挙を避ける。 */
        private fun rotations3() {
            for (j in targetDays()) {
                if (timeUp()) break
                for (a in 0 until p.S) {
                    if (timeUp()) break
                    if (p.wishLocked(a, j)) continue
                    for (b in a + 1 until p.S) {
                        if (p.wishLocked(b, j)) continue
                        for (c in a + 1 until p.S) {
                            if (c == b || p.wishLocked(c, j) || timeUp()) continue
                            if (focus != null && a != focus && b != focus && c != focus) continue
                            val sa = s[a][j]; val sb = s[b][j]; val sc = s[c][j]
                            if (sa == sb && sb == sc) continue
                            // 巡回: a<-sb, b<-sc, c<-sa
                            if (!p.mayPlace(a, sb) || !p.mayPlace(b, sc) || !p.mayPlace(c, sa)) continue
                            tryOps(FixKind.SWAP_MULTI, listOf(FixCell(a, j, sb), FixCell(b, j, sc), FixCell(c, j, sa)),
                                "（3人）${nm(a)}・${nm(b)}・${nm(c)} を ${dlab(j)} で入替")
                        }
                    }
                }
            }
        }

        /** Phase 5: 別日交換（違反関与セルを起点）。同日は Phase 2 が網羅済みなので飛ばす。 */
        private fun crossDaySwaps() {
            val anchors = ArrayList<Int>(hotCells)
            for (i in targetStaff()) for (j in 0 until p.T) if (!p.wishLocked(i, j)) anchors.add(i * Limits.PACK + j)
            val seenAnchor = HashSet<Int>()
            for (packed in anchors) {
                if (!seenAnchor.add(packed) || timeUp()) continue
                val i1 = packed / Limits.PACK; val j1 = packed % Limits.PACK
                if (i1 !in 0 until p.S || j1 !in 0 until p.T || p.wishLocked(i1, j1)) continue
                val a = s[i1][j1]
                for (i2 in 0 until p.S) {
                    if (timeUp()) break
                    if (focus != null && i1 != focus && i2 != focus) continue
                    for (j2 in 0 until p.T) {
                        if (j2 == j1) continue
                        if (p.wishLocked(i2, j2) || timeUp()) continue
                        val b = s[i2][j2]
                        if (a == b || !p.mayPlace(i1, b) || !p.mayPlace(i2, a)) continue
                        val label = if (i1 == i2)
                            "${nm(i1)} ${dlab(j1)}「${sym(a)}」 ↔ ${dlab(j2)}「${sym(b)}」（別日）"
                        else
                            "${nm(i1)} ${dlab(j1)}「${sym(a)}」 ↔ ${nm(i2)} ${dlab(j2)}「${sym(b)}」（別日）"
                        tryOps(FixKind.SWAP_XDAY, listOf(FixCell(i1, j1, b), FixCell(i2, j2, a)), label)
                    }
                }
            }
        }

        /** 効果順（hard→weighted→total＝betterReport と同順）に並べ、セル限定と盤面変化の実体による重複排除で絞る。 */
        private fun collect(maxResults: Int): List<FixSuggestion> {
            found.sortWith(compareBy({ it.dHard }, { it.dWeighted }, { it.dTotal }))
            val fShift = focusShift
            // [セル限定] 押したセル(focus職員×focusシフト)からシフトを移す手か、そのシフトへ移す手だけ。
            fun touchesFocusCell(sug: FixSuggestion): Boolean {
                if (fShift == null) return true
                return sug.ops.any { c ->
                    if (focus != null && c.staff != focus) return@any false
                    if (c.toShift == fShift) return@any true
                    val row = s.getOrNull(c.staff) ?: return@any false
                    c.day in row.indices && row[c.day] == fShift
                }
            }
            // 署名＝無変化の脚を除いた (staff, day, toShift) の正規順。kind や ops の列挙順に依らず「最終的にどのセルが
            // どの値になるか」で重複を判定する（経緯は history 3.202.0/3.475.0）。s はこの時点で入力盤面に一致している。
            val seen = HashSet<String>()
            val result = ArrayList<FixSuggestion>()
            for (q in found) {
                val sug = q.sug
                if (!touchesFocusCell(sug)) continue
                val realOps = sug.ops.filter { it.toShift != s[it.staff][it.day] }
                if (realOps.isEmpty()) continue
                val sig = realOps.sortedWith(compareBy({ it.staff }, { it.day })).joinToString("|") { "${it.staff}.${it.day}.${it.toShift}" }
                if (seen.add(sig)) result.add(sug)
                if (result.size >= maxResults) break
            }
            return result
        }
    }
}
