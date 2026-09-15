# バックログ / 未対応（CLAUDE.md から移設, 3.505.9）

> 完了した項目は打消し線のまま残す（同型の課題を二度登録しないため）。開いている項目の要約は CLAUDE.md にある。

1. ~~TallyCard の読取/編集モード完全整合（result専用検査結果の plumbing）~~ **→ 3.96.0 で完了**（ユーザー向け機能の TallyCard 項参照）。
2. 未レビュー領域の精読: ~~`V6LateOperators`~~（3.507.7）/~~`V6SearchOperators`~~（3.507.9）/~~`V6HotfixPasses` 各パス内部~~（3.508.1）, ~~CSV~~（3.507.8）/UI 層（3.508.2 でエンジン契約との接点は確認済み。全面精読は実機ビルドが要るので保留）。**（3.508.2）** `V6PortAnalyzer`（3.503.0）・`V6SwapSuggester`（3.507.4）・`V6SanityPort`（3.507.5）・`V6LateOperators`（3.507.7）は一巡。
   **(3.84.0, 並列監査で一巡＝`docs/history/3.0xx.md`)**。※`V6WebCompat` は 3.393.0 に撤去済み（Web 版は存在しない）。
3. ~~C++/NDK 移植は**不要**の結論（純Kotlin＋被覆対応Δ評価で十分高速）~~ **→ 撤回（3.136〜／第2期・第3期でネイティブ加速＝
   C++フル評価器＋SA/LAHC/ALNS/Polishチャンク＋JNI＋実行時パリティを実装。監査指摘は下記6/7）**。エンジンは ALNS/Destroy-Repair/
   ChainSwap3-4/C1BlockN/PathRelink/LNS/Reheat/Oscillation/適応的オペレータ重み/希望ロック枝刈り を実装済み。
   §4 ILP matheuristic のみ意図的に未実装。
4. cons3n のデータ重複（Dﾃ→A4 が2行）は二重計上だが最適化器/チェッカーで一貫（SettingIssue が dedup を提案）。
   **（3.502.0 確定）** エンジン側の dedup は採点を変える＝HF77 対象・C++ パリティ期待値も動くため行わない。`DELETE_DUP_SEQ` のワンタップ修正で運用解消＝閉じる。
5. **E5「月全体の俯瞰」= ユーザーの明示 go まで保留**（決定記録）。指数(見やすさ12指標)で唯一70未満(58)だが、
   最低スコア≠最高価値・片手一本指/編集主体との緊張のため、着手も再提案もしない（明示 go があった場合のみ）。
6. ~~**[ネイティブ・保守性] C++評価器のパリティに自動テスト無し**（3.168.0系精読で判明）。JVM単体テストは arm64 `.so` を
   ロード不可（`NativeBridge.available=false`）→ Kotlin のみ検証。CI(Release Build/Android SDK)は CMake で `.so` を
   ビルドするので**C++コンパイルエラーは捕捉**するが**意味的乖離（重み取り違え等）は捕捉しない**。`Evaluator.kt`（や
   `MirrorCore`/`DeltaEvaluator`）を変えて `magi_native.cpp` を変え忘れると実機で番兵発火→**ネイティブ黙殺（速度退行・誤出力なし）**。
   3.171.0 で緩和策の一つ（ユーザーが明示的に照合を切れる「照合トグル」＋既定ONの維持）を実装。3.172.0 で
   `tools/native/host_parity_bench.cpp`（ホストビルド可能なパリティ+ベンチharness）を追加。
   **→ CI配線 完了（`docs/history/3.1xx.md` の「ネイティブパリティのCI自動化」3.178.0）**。`.github/workflows/native-parity.yml` が
   pull_request→main / push→main / 手動 で harness を g++ ビルド・実行し、mismatch>0 で非ゼロ終了＝ジョブ失敗。
   これで Evaluator.kt を変えて magi_native.cpp を変え忘れる意味的乖離が自動検出される。**残課題**: harness の
   合成問題は S<=64/T<=64・乱数生成で、実データ形状の網羅ではない（fixture 拡充は将来課題）
   **→ 3.357.0 で言語跨ぎパリティも追加**（旧: 照合は C++ scalar vs C++ bit-op ＝同じ言語どうしで、
   両方の C++ 経路を一貫して変えれば通ってしまった。`golden_eval_expected.txt` を Kotlin テストと
   `--expect=` の両側から固定し、片側だけの変更が必ず落ちるようにした）
   **→ 3.199.0 でフィクスチャ拡充**（`tools/native/state_to_flat.py`＝実 state JSON→flat 変換を新設し、
   CI が golden_state.json の実データ問題でも照合＋合成 builder に休シフト range/apt/c1/c2・実現不可能希望・
   -1/非canDo混入盤面を追加。この拡充が実バグ=SaChunk の -1 未対応を実際に捕捉した）。
   **→ 3.362.0 で2つ目の実データ形状 sample_v6 を追加**（golden は入力盤面 hard=0＝C++ の HARD族パスを
   実データで一度も exercise しないため、sample_state_v6 の hard=15 盤面を第2 fixture に。`--expect` を
   flat と出現順で対応づけ1回のベンチで両形状を言語跨ぎ照合。詳細は「パリティネットへ2つ目の…」節）。
   残課題: 合成問題は S<=64/T<=64・乱数生成で、実データ形状の網羅ではない（fixture 拡充は将来課題）
   残: real/user 相当の「構造的 covU が床超で blocked-now」な形状は依然 repo に無い
   **→ 3.409.15 で解消**（2026-08 実運用 state の**匿名化版** `blocked_covu_state.json`＝covU=4 が床0を超えて
   blocked-now、を第3フィクスチャとして追加。言語跨ぎ照合＋形状の回帰テストつき）~~。
   **→ 3.524.0 で「意味的乖離検出」を族単位へ強化して完了**。従来の `--expect=` 照合は hard/soft の集約値2つ
   だけで、golden/sample_v6/blocked_covu の3実データが**そろって apt=c41=c41s=c42s=0**（一度も発火しない）
   と判明——この4族はどちらの側にバグがあっても集約値だけでは検出できない死角だった（2族の重み取り違えが
   打ち消し合うケースも同様）。`Evaluator.fullEvalParts`/C++`fullEvalParts` に任意の族別 breakdown 出力を追加
   （引数省略時は既存呼び出しと挙動不変）し、`--expect=` ファイルに19族(MirrorKeys.all)の内訳行があれば
   `host_parity_bench.cpp` が族単位でも突き合わせるよう拡張。19族すべてを非ゼロにした `full_coverage_state.json`
   を4件目のフィクスチャに追加してこの死角を塞いだ（詳細は `docs/history/3.4xx.md`）。
7. ~~**[ネイティブ・堅牢性] 群index無検証のOOB（潜在）**（3.168.0系精読で判明）。探索オペレータ約13箇所が
   `p.bucket[p.sgrp[i]]`／`grpCnt[sgrp[i]*K+k]` を sgrp範囲未検証で使用しており、不正な groupIdx が渡ると
   C++側はUB（bucket=範囲外読み・grpCnt=範囲外**書込=ヒープ破壊**）でSIGSEGVし得た（Kotlin側は例外→
   runCatchingで安全退化するのと非対称）~~ **→ 3.171.0 で解消**（`nativeCreateProblem` に sgrp 一括範囲検証を
   追加し、外れていればハンドル生成自体を拒否=0返却。既存の「handle==0=native不可→Kotlinへ安全退化」という
   確立済みの契約にそのまま乗るため Kotlin 側の変更は不要）。
8. ~~**[軽微] SAチャンク自己整合の非対称**（3.168.0系精読で判明）。`runSaChunk` の番兵は `full != curVal` のみ。
   他3ランナー（LAHC/ALNS/Polish）は `curVal != st.score` の相互検査も持つ~~ **→ 3.179.0 で解消**
   （`runSaChunk` 末尾を `full != curVal || curVal != st.score` に対称化。受理時 curVal=st.score・revert で復元の
   ため通常は恒真＝挙動不変、不整合時のみ status=1 で Kotlin 退化。ホスト parity harness で compile+mismatch=0 確認）。

9. **[探索動学・要計測] 希望島研磨（3.496.0）への外部レビュー指摘 4 件**（3.500.1 で登録。詳細は `docs/history/3.4xx.md`「外部レビュー…の Android 同期（3.500.1）」）:
   ~~(a) 島ごとの評価枠を同日候補が先に使い切り窓・両翼・巡回が飢餓になる~~ **→ 3.501.0 で交互評価＋巡回 25% 確保（ユーザー指示・計測は history）**、
   ~~(b) `makesForbiddenRun` の事前枝刈りが禁止連続を**減らす**手まで落とす~~ **→ 3.501.0 で増分判定へ**、~~(c) ビームの `expandNode` が
   列挙順の先頭 `beamWidth*beamBranchFactor` 件だけを並べ替える~~ **→ 3.502.0 で走査 2 倍・良い順に保持（計測は中立）**、~~(d) `clearOutOfScopeWishes` が
   非同期診断の `settingIssues` を根拠にする~~ **→ 3.502.0 で `Problem.canDo` から再判定**。4 件すべて完了（Kotlin が正・C# 同期済み）。

10. ~~**[UI・同型] 「なおすのを手伝って」の連打防止が盤面の変更で解除される**~~ **→ 3.502.0 で `UiState.checkRev`（検査世代）へ**（3.500.2 で登録。-MAGI_PC 側の外部レビュー第3段）。
   `GuidedFixDialog` の `pending = remember(ui.schedule)` は setCell 直後の schedule 変化でリセットされるが、`coverageDiag` の再検査はまだ
   終わっていない＝古い診断の候補が再有効化され、素早い2回操作で covO と covU を同時に作れる。C# は `UiState.CheckRev`（`makeUi` ごとの検査世代）
   を追加し「押下時より新しい世代の反映」でだけ解除する形に直した（`GuidedFixFlow`）。Android も同じ形（UiState に検査世代を足す）で直す。


11. **[探索動学・実機確認待ち] 個人上限 0 の除外（3.507.0）の実機での受容**。再現データでは 桒澤/大島 の該当が 0 になる代わりに c1 32→116・重み付き 2849→4877
   （残り 4 名の余裕ゼロ＝設定側の問題）。利用者の実データ（希望 93 件）で「0 になった」ことと代償の見え方を確認し、代償が受け入れ難ければ
   設定側の案内（上限 0 の職員を除いた容量の表示＝「上下注意」行に『置ける N 名』）を足す。上限 0 のセルを UI の集計に「除外中」と示す案は表示変更なので提案どまり。
   ~~実機報告「大島愛の Dﾃ が上限 0 なのに 1 件割当」の調査で、`handleOptimize` の最終番兵が発火時に `finalSched` を
   `cappedInput`（`inputReport` と同じ盤面）でなく `normInput`（外す前の生入力）へ戻していたバグを発見・修正~~ **→ 3.513.0 で解消**。
12. **外部レビュー（2026-09-07、ユーザー提示 3 表）の提案＝決定・測定待ち**（3.509.2 で仕分け。詳細は history 3.509.2）。
    (a) 評価定義: C2 を不足量、C41/C41s を距離で評価＝HF77・言語跨ぎ期待値・C++ 5 箇所を同時に変える目的関数変更。300 試行ペア比較（品質 10%・速度 10%・必須退行 0）を通してから。
    (b) 探索動学: C2 専用研磨＝3.511.3 `C2Polish` 実装・iter15 で**全170ペア無変化**（合成ケースのcons2は不足最大1件でバッチ化の優位性を試せていない＝検証不能、既定OFF維持。deficit≥2の合成ケース追加か玉突き拡張の実装後に再計測）
        **→ 3.524.0 で `tools/loop` に `c2deficit`（cons2目標を不足2件以上へ）・`c42pair`（cons42違反を実際に仕込む）の2分類を追加（30→42ケース）し検証可能にした。
        小/中/大×5seedのスコープ付き再測定では `C2Polish`/`C42FlowPolish` とも**全ケースで旧腕と完全同点**（新たに仕込んだ初期違反は一般探索の段階で解消済み＝専用研磨に残作業が無い）。
        「検証不能」から「実測して無風」に前進したが既定OFFは維持（全170ペアでの正式ゲート再測定は未実施、詳細は `docs/history/3.4xx.md`）**／
        C41・C41s の最小費用フロー研磨＝3.511.5 `C41FlowPolish` 実装・iter16 で**不合格**（170ペア新良21/同等143/旧良6、品質+0.14%・速度+1.1%とも10%未達、必須が増えた試行2件、既定OFF維持。c42/c42s は別途 3.511.7 `C42FlowPolish` 実装・iter18 で**全170ペア新旧同等**＝合成ケースにc42違反の仕込みが無く検証不能、既定OFF維持）／
        族選択を件数×重み×改善可能性へ＝3.511.6 `familyPriorityScore` 実装・iter17 で**不合格**（170ペア新良10/同等143/旧良17、品質−0.26%、必須が増えた試行9件、既定OFF維持）。
        3.512.0 で「並べ替えても`repair()`の貪欲な早期採用（最初に成功した起点をすぐ採用）の下では効果が埋もれる」という仮説から
        `Params.bestOfK`（既定1、2以上で最大bestOfK件を実際に`search()`し`betterReport`で客観的に最良の一つを採用）を実装・iter23で**不合格**
        （170ペア新良15/同等138/旧良17、品質−0.17%、必須が増えた試行12件はlarge-infeasibleに集中、既定OFF維持。ユーザー判断で検証終了）／採用 0 時の探索半径拡張＝3.511.1 `StallEscalationConfig` 実装・iter13 で**不合格**（170ペア全件無変化＝発火条件「クラスタ全体で1巡も採用0」が実質発火しない、既定OFF維持）。発火条件を「直近ラウンドの採用数が閾値未満」等へ緩めた再設計が要る（測定）／C1 重複窓の連結成分化＝3.511.9 `C1RepairAnalysis.solveComponent` 実装・iter20 で**全170ペア新旧同等**（合成ケースに複数cons1窓の近接局面が無く検証不能、既定OFF維持）／C3n の前後余白込み LNS＝3.511.8 `C3nMarginLnsPolish` 実装・iter19 で**不合格**（170ペア新良1/同等168/旧良1、品質±0.00%とほぼ無風、既定OFF維持）／
        循環交換の 3〜5 人拡張＝3.511.2 実装・iter14 で必須退行0だが品質・速度10%未達＝**不合格、既定OFF維持**（試行数20回/日では170ペア中1件にしか当たらない、増やせば変わる可能性）／適応的予算配分／~~C3「選択日ペア交換」~~（3.510.0 実装・iter8 で品質 +0.04%＝不合格、既定 OFF 維持）＝いずれも `tools/loop` で測ってから 1 件ずつ。
    (c) D9 の方向別合成（片側だけ個人優先）＝決定 D9 は完全上書き。要件変更の明示があれば。
    (d) UX: 高度修正の適用前プレビュー（対象・変更セル数・必須/ソフト/希望の前後）、自動修正候補の比較、実現不能と探索不足の分離表示、
        時間予算/並列数/アルゴリズムのプリセット化、操作名付き Undo 履歴、利用者向け名称（「破壊」「LNS」「HF80」を出さない）＝画面設計。実機ビルドと design-review が要る。
13. **自動化方針（`docs/automation.md`）の「未」**: (a) 停滞時の探索半径・職員数・窓長の段階的拡大（測定）／~~(b) 採用ゲートの一本化~~（3.509.5 `adoptionGate`）／
    (c) 必須減少時のソフト損失上限（5%）＝採用基準の変更（決定待ち）／(d) 時間予算の自動計算（規模・必須数から）／~~(e) 3〜5 職員循環交換の 5 人まで拡張（測定）~~（3.511.2、backlog #12(b) と同一実装）。
    (f) 安全ゲート（`FixApplyGate`）合格の先頭候補を利用者のタップなしで自動適用（自動化 UX 評価 優先 1 の後半。適用後に「変更を見る／元に戻す」）／(g) 1 手候補ゼロ→「自動で整える」（区間交換・循環・成分修復・LNS）への自動接続（優先 2、Undo は接続全体で 1 回）／(h) 前景/背景の自動選択（優先 5）／(i) 内部用語の詳細設定への隔離（優先 7、design-review）。
14. **重み・時間配分の外部分析（2026-09-08、ユーザー提示。最新 main とログ 10 本の傾向）＝適応制御として導入しペア比較で採否**:
    (a) C1 共同 LNS・個人共同 LNS を「短時間試行→改善時だけ拡張」（3.510.2 で `lnsAdaptive` 実装・iter9: 退行 0・品質 ±0・速度 平均 +10%/実データ −23%/中央値 −1.5%＝規則上不合格。**既定 ON はユーザー判断待ち、推奨 ON**）。
        ログでは両 LNS が後処理時間の 75〜91% を占め採用 0／(b) 後処理 4 巡→基本 2 巡・採用時だけ最大 4／固定長ブロック交換（11/13/17/19/23/28 日）を違反窓長・禁止連長・希望島半径からの動的窓へ＝3.511.0 `dynamicBlockLengths` 実装・iter12 で**不合格**（大・充足不能ケースで必須+1、既定OFF維持。単一パスのkeep-bestがチェーン全体を保証しない経路依存）／
    (d) C2・C41/C41s を不足量・超過量評価へ（評価仕様変更＝#12(a)）＝3.512.0で`Problem.quantitativeRangeEval`として全層実装済み・既定OFF、
        iter21で**不合格**（170ペア新良5/同等153/旧良12、品質±0.00%・速度-1.1%とも10%未達、必須件数が増えた試行6件はlarge-infeasible
        カテゴリのみ〈平均122.00→122.60、供給不足枠で不足量評価が異なる手を選ぶ〉、既定OFF維持）／(e) apt・fair・weekly は重みを変えず辞書式の副目的（apt 偏差→fair 最大偏差→weekly 最大偏差→偏差合計→変更セル数）で比較（採用基準の変更＝決定待ち）／
    (f) low=90（→60）と c3m=2 の A/B（HF77: 明示数値の指示があれば）。low の値自体より「一時的に low を悪化させ同一トランザクション内で戻す複合手」を先に強化／
    (g) 予算超過の回帰試験（内部ループが 100〜250ms ごとに停止判定、300 秒設定で実時間 305 秒以内、停止要求から 5 秒以内、例外ワーカーで固まらない）。
    維持: HARD 辞書式・HARD 桁 1e9・HARD 族分類・C1/C3mn=30・8 ワーカー・後処理予約 25 秒・月内完結。
15. **「制約クレジット台帳付き複合修復」（2026-09-08 ユーザー提示設計）＝机上評価済み（history 3.510.2 補遺）、決定待ち**:
    ~~(a) 族別 Credit/Debt を採用ログ・完了表示（`ChangeSummary`）へ出す＝判定不変の可視化~~（3.510.3）／(b) 件数倍率上限（≤3/≤1/≤2）と Total 厳密改善＝採用基準の追加。
    実データの採用 31 件中 12 件を却下するので**入れない推奨**。入れるなら明示 go＋weekly/fair/apt を対象外＋opt-in で `tools/loop`／
    (c) 明示個人範囲の最終悪化禁止＝現行採用に影響 0。HARD 扱いにする決定があれば／(d) 一時 HARD 負債の禁止＝既存 `C1JointLnsPolish.hardDebt=1` の撤去（要計測）／(e) C2/C41 不足量評価＝#12(a)。
    設計 v2（3.510.3 補遺）: (f) 中間探索の件数 debt（C1/個人共同 LNS の totalDebt/c1Debt/personalDebt）を重み基準（負債 ≤ クレジット×係数）へ＝3.510.4 `WeightDebt` opt-in 実装、iter10 で新良 0・旧良 15・必須 +2 が 1 件＝**不合格、既定 OFF 維持**／
    (g) 凸型負債 W×D(D+1)/2（族単位で採用 31 件中 1 件却下、職員集中は未計測）＝採用基準の追加、opt-in＋計測／(h) 族別予算 Priority=W×違反量＝HARD 残存盤面で SOFT が 0 になるので下限つきで計測／
    (i) 設定由来倍率（明示個人 ×3）は不要（D9 で群目標を置換済み、チェッカーとずれる）／(j) WeightProfile は重みが設定化されるまで不要。
    仕様 v3（3.510.5）: (k) 成分修復の一時負債予算 `ConstraintRepairInference`＝opt-in 実装、iter11 で新良 8・旧良 12・実データ全件同一＝**不合格、既定 OFF 維持**／(l) 同点処理「変更セル数」の段＝採用基準の追加（決定待ち）／
    (m) C# `ConstraintRepairInference` は origin/main に無い＝push 後に Kotlin と突き合わせる。
    仕様 v2.1（3.512.0、ユーザー提示「適応型制約違反研磨エンジン 完全統合実装仕様」）: iter11 の無差別の一因として `search()` の
    ビームトリムが単一プールのため負債候補が飢餓しうる構造を発見し `Params.debtLaneSlots`（二車線ビーム、既定 0）を実装。
    trimFrontier のビーム縮小バグ（3.512.3）を修正した分離条件 iter22 で**不合格**（170ペア新良8/同等135/旧良27、品質−0.18%、
    個別10%超退行1件、既定OFF維持）。**ユーザー判断（2026-09-09）で検証・追加実装を中止**——仕様書の重い機構
    （結合度認識型負債テンソル§7.1・Restless Bandit§12・並列トランザクション§13・予算再配分）は着手しない。
    WeightDebt(iter10)・ConstraintRepairInference(iter11)・familyPriorityScore(iter17)に続き「族別一時SOFT負債」
    「族優先度スケジューリング」系統は4・5度目の不合格＝新しい具体的な測定可能な仮説を伴わない限り再検証しない。
16. **[探索動学・実機影響は無し・決定待ち] RSI_PLUS のフェーズ予算按分に35秒の暗黙下限**（3.511.4 `BudgetOverrunTest` で発見）。
    `runRsiPlus`（V6NativeOptimizer.kt）は Seed/Hypothesis/Refine/Polish の4フェーズへ `max(10, budgetSec*0.2)`/`max(10, budgetSec*0.35)`/
    `max(10, budgetSec*0.3)`/`max(5, 残り)` で予算を按分し、budgetSec<50 ではほぼ常に 10+10+10+5=35秒が下限になる（1秒予算でも実測36秒）。
    production の予算（後処理予約25秒を含め常に数十秒〜300秒、backlog #14(g) の実要件「300秒→305秒以内」も無傷）では発生しないため
    実害は無いが、将来 UI から短い予算（プレビュー・クイック実行等）でRSI_PLUSを呼ぶ機能を足す場合は踏む。フェーズ下限を
    budgetSec に応じて緩めるかは探索動学の変更＝明示指示があれば。現状は放置で良い（実機で踏む経路が無い）。
17. ~~**[表示のみ] 設定タブ・勤務表タブの文字サイズ階層**（3.515.4 の続き）~~ **→ 3.515.5 で完了**。
    `MagiSetupCards.kt`・`MagiScheduleViews.kt`・`MagiDashboardCards.kt`を`docs/DESIGN.md` §3.3の階層
    （章=titleMedium／節=titleSmall／本文・行=bodyMedium／補足=bodySmall／label=部品ラベル・チップ・凡例のみ）へ統一。
18. ~~**[要継続調査・ユーザー承知の上でmainへマージ済み] 重み表全面見直し（3.522.0）後、厳密ピン(staffRange lo==hi)保護に
    残存する穴**~~ **→ 3.523.0で完了**。デバッグ計装（呼出直前に盤面をdeep copyしてからexactPinRegressionで
    比較、`chain.adopt`/`replaceBoard`全箇所＋関数末尾の絶対値チェック）で全経路を追ったが不一致ゼロ＝
    exactPinRegression(staffRange lo==hi)は無傷と判明。実際の失敗はテスト内の**別の**assertion
    （希望固定`lockedCells`の値保持）で、`C1WindowPolish.applyC1IndexChainRepair`の候補日フィルタが
    `p.wishLocked(staff, d)` を見ておらず、他の全パスが持つガードだけこの1関数に欠けていた
    （`C1DeltaPrefilter.screenCell`の合算delta方式はpref+1と他族-1が相殺してNEUTRAL判定になり得るため
    生成側で塞ぐ必要があった）。候補フィルタへ`!p.wishLocked(staff, d)`を追加して修正、
    `PinInvariantTest`green化。`CombinatorialRepairTest`・`ViolationComponentRepairTest`の
    `combineTwoRejectedState`もcons41を4重複させ「単独不採用(タイ)・結合で採用」の性質を新重みで復元
    （詳細はdocs/history/3.4xx.md）。
