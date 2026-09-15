package com.magi.app.v6

/**
 * RSI探索のfocus族選択判定。[V6NativeOptimizer] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * **共有可変状態を一切参照しない純粋な判定関数**（[V6NativeOptimizer] 本体は @Volatile フィールド・
 * Atomic系・RunSlotのコルーチンコンテキスト連携で並行実行状態を密結合する「統括状態機械」の性格が
 * 強く、機械的な抽出は危険＝本ファイルはその中の安全な塊だけを切り出す）。
 *
 * [maxViolatedFamily]：`ViolationReport.breakdown` から次に focus すべき違反族を選ぶ
 * （HARD優先→周期的保証枠(apt/covO)→件数最大→weekly/apt優先順位調整、の多段判定。詳細は本体コメント）。
 *
 * 呼び出し側は全て`V6NativeOptimizer.maxViolatedFamily`の完全修飾で参照していたため、抽出時に
 * `RsiFocusSelection.maxViolatedFamily`へ一括置換した。
 */
internal object RsiFocusSelection {
    internal fun maxViolatedFamily(report: ViolationReport, avoid: Set<String> = emptySet(), round: Int = -1, roundsTotal: Int = -1): String {
        // [実機ログ起因=公平化のズレ] apt(適切回数)を追加。旧orderに無かったため RSI 探索中は一度も
        //   focus されず、post-processing(applyDayAssignmentPolish)頼みで広く未研磨のまま残っていた
        //   （実データ検証: apt L1偏差合計37、staffRange低/高はわずか3で規模が逆転）。rsiGenerateHypothesis
        //   側は既存の destroyRepairStaff(low/high/c2 と同経路、marginal costに apt 込み)へ合流するだけ＝
        //   新規オペレータ不要。
        // [同根の穴=weekly/fair] 同じ理由で weekly/fair も未focusだったため追加（実データ検証: weekly=65
        //   （aptの37より大きい）・fair=11）。destroyRepairStaff は weekly/fair の cost には未対応だが、
        //   専用ラウンドを割り当てるだけで無指向な"total"空振りより改善機会が増える（詳細はrsiGenerateHypothesis）。
        val order = listOf("groupViol", "covU", "pref", "c3n", "c3w", "low", "high", "c41", "c41s", "c2", "covO", "c42", "c42s", "apt", "weekly", "fair", "c1", "c3", "c3m", "c3mn")
        // [D1/A1] 解ける HARD 族(groupViol/covU/pref/c3n)は件数に関わらず SOFT より先に focus する。
        //   旧実装は純・件数最大だったため、単一の c3n=1 が c1=118 等の高頻度 SOFT に埋もれ RSI が一度も HARD を
        //   狙わない失敗があった。目的関数 better() は辞書式(hard<<total<<weighted)で HARD 支配ゆえ focus も HARD
        //   優先が整合。avoid(HF63=構造的に充足困難)に入る HARD は「解けない」ため除外し無駄打ちを避ける(残予算は
        //   下段の SOFT 研磨へ)。この分岐は hard=0 のとき no-op＝全 soft の一般ケースは従来と不変。
        for (key in order) {
            if (key !in MirrorKeys.hard || key in avoid) continue
            if ((report.breakdown[key] ?: 0) > 0) return key
        }
        // [3.204.0/実機ログ起因=covOが「件数最大」選択に構造的に勝てない] covO は日×シフトのセル単独違反
        //   （新設したCoverageDiag診断＝V6PortAnalyzer.diagnoseCoverage の covO 版で判明）のため件数が常に
        //   一桁台に留まり、c1/c42/c3mn/weekly のような数十件規模の族に下段の「件数最大」選択で恒久的に
        //   絶対勝てない（実機ログで「動かせる」と診断されたcovOセルが300秒経っても解消されないことを確認）。
        //   HARDの「件数に関わらず先に狙う」と同じ発想で、covO専用に周期的な保証枠(3ラウンドに1回)を設け、
        //   count>0かつavoid対象でなければ下段の最大値選択より優先する。他のSOFT族の選択順は完全に不変
        //   （round<0=呼出元が未対応の旧経路 or この分岐に該当しないラウンドは従来どおり件数最大へフォールバック）。
        // [3.207.0/実機ログで判明した3.204.0の実効性不足] 典型的な5ラウンドRSIでは round%3==2 の唯一の
        //   該当ラウンド(0始まりで2番目)が、HF63がc3n/covUをdeprioritizeし終える前(HF63は約3ラウンドの
        //   停滞を要する)に来てしまい、HARD優先ループがそのラウンドを丸ごと消費して covO 分岐へ到達しない
        //   （実機ログ: round=3/5 focus=c3n、covOは合計6のまま最後まで不変）。HARDが本当に解けない場合は
        //   HF63が最終的にdeprioritizeし尽くすため、**RSIフェーズの最終ラウンドでは高確率でavoidが揃っている**
        //   （実機ログでもround=5/5時点でc3n,covU,c1(E9冷却)が全てavoid/cooldown済）。最終ラウンドも
        //   保証枠に加え、周期枠が典型的な短いRSIフェーズで丸ごと空振りする問題を解消する
        //   （roundsTotal<0=呼出元が未対応なら従来どおり無効化＝後方互換）。
        val finalRound = roundsTotal > 0 && round == roundsTotal - 1
        // [3.208.0/実機ログで判明したaptの同型の恒久的starvation] 提供された全ログ(7本)を確認したところ、
        //   apt は常に breakdown 最小級（1または11、他族(c1=87/c42=18/weekly=56等)の一桁〜二桁下）で、
        //   "focus=apt" は一度も出現しなかった（"focus=weekly" のみが件数最大フォールバックで選ばれ続ける）。
        //   apt は 3.169.0 で正に「focus されず未研磨」を解消する狙いで order に追加されたが、追加した
        //   だけでは件数最大選択に構造的に勝てないという covO と全く同じ欠陥を抱えていた（3.169.0時点の
        //   検証データではapt=37とcovOより大きく問題が露呈しなかったが、実運用データでは apt が最小級に
        //   落ち着くことが多いと判明）。covOとは別の周期(round%3==1、covOの%3==2と衝突しない)を割当てる。
        // [3.239.0/実機ログで判明した最終ラウンド枠の固定順バグ] 旧実装は最終ラウンドで常に
        //   「aptを先にチェック（covOより小さく恒常的に不利なため優先）」という固定順だった。これは
        //   「aptは常にcovOより小さい」という3.208.0時点の観測（7本のログ全てでapt<covO）に基づく前提
        //   だったが、この前提自体がデータ依存で普遍的ではない（実機ログで apt=29 > covO=4 という逆転を
        //   確認。5ラウンドRSI中、covUがHARDとして数ラウンド粘り+周期枠(round%3==2)もHARD優先ループに
        //   食われ、最終ラウンドはfinalRound分岐に到達するがaptが先にreturnするためcovOには一度も
        //   到達しなかった＝8/26のcovO過剰1が「動かせる」診断なのに300秒経っても未解消だった根本原因の
        //   一つ）。最終ラウンドで両方が候補になる場合のみ、実際の件数を比較し「より少ない方
        //   （より構造的に不利＝件数最大選択に絶対勝てない方）」を優先する。通常ラウンド(round%3==1/2の
        //   単独枠)は従来どおり衝突しないため無変更。
        val aptEligible = round >= 0 && "apt" !in avoid && (report.breakdown["apt"] ?: 0) > 0 && (round % 3 == 1 || finalRound)
        val covOEligible = round >= 0 && "covO" !in avoid && (report.breakdown["covO"] ?: 0) > 0 && (round % 3 == 2 || finalRound)
        if (aptEligible && covOEligible) {
            return if ((report.breakdown["covO"] ?: 0) <= (report.breakdown["apt"] ?: 0)) "covO" else "apt"
        }
        if (aptEligible) return "apt"
        if (covOEligible) return "covO"
        // 解ける HARD が無い(全て 0 か avoid)＝以降は SOFT。従来どおり非avoidの族から件数最大を返す。
        // [E8/実機ログ起因] 件数0の族は focus しない（旧: bestCount=-1 初期化のため、非avoidの正件数族が
        //   order に1つも無いと先頭 groupViol=0 が「0 > -1」で当選→hf67ルートがクリーン盤面への no-op 仮説
        //   ＝1ラウンド(実測~21s)空振りしていた。12シフト実機ログ round=4/5 focus=groupViol(件数0)で確認）。
        //   該当なしは "total" を返し、rsiGenerateHypothesis の else 分岐＝全違反セル hint の汎用修復
        //   (destroyRepairViolations, focus 非依存)ラウンドとして時間を有効化する。focus 選択のみ＝スコアリング不変。
        var best = "total"
        var bestCount = 0
        for (key in order) {
            if (key in avoid) continue
            val n = report.breakdown[key] ?: 0
            if (n > bestCount) {
                bestCount = n
                best = key
            }
        }
        // [ユーザー明示指示(2026-07-20)「weeklyをaptより優先順位を下げる」] weekly は件数が大きくなりやすく
        //   (実機ログで41〜65)、apt(同1〜29)より小さくても件数最大選択で恒常的に勝ってしまっていた。
        //   件数比較でweeklyが選ばれた場合でも、aptに残り(avoid対象でなければ)があれば apt を優先する
        //   （apt自身の周期枠=aptEligibleが不発だったラウンドのみ到達＝この分岐で初めて効く）。
        //   apt/weekly以外の族どうしの順位（c1/c3/fair等）は無変更。
        if (best == "weekly" && "apt" !in avoid && (report.breakdown["apt"] ?: 0) > 0) {
            best = "apt"
        }
        return best
    }

}
