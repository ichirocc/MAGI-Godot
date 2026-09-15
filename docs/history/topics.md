# 作業記録（話題別・CLAUDE.md 本体から分離）

> 3.497.3 で `CLAUDE.md` から移した「話題ごとの版数付き叙述」。本文は原文のまま（見出し・版数・ユーザー指示の引用を落とさない）。
> `CLAUDE.md` 本体には各話題の**恒久の事実**だけを 3〜5 行で残した。引き方は `grep -n '3.150.0' docs/history/topics.md` のように版数で。

## ネイティブ加速 第3期: 完全版C++移行（進行中・明示指示 2026-07-13「完全版C++移行できるようにする」）
> 方針は第1/2期と同一: **Kotlin チェッカー/評価器を「正」として温存**し、実行時間を占める残りの Kotlin ホットパスを
> C++ チャンク＋2層番兵（①チャンク自己整合 ②Kotlin fullEval Long== 照合、発火で NativeGate 退化）で置換する。
> 残余ヒートマップ（実機300sログ基準）: ①hf80PostPolish 45s×5並列ワーカー（最大の残り）→ **Stage10 で完了** /
> ②SaOptimizer PhaseB(LAHC, softPolish時のみ) / ③V6LateOperators(Chain3/4/Rect/BlkN, ラウンド境界) /
> ④後処理チェーン研磨(CyclicSwap/C1/C3系, 実測~1s) / ⑤RSI制御層・チェッカー=**軽量O(ラウンド)＋「正」のため対象外**。
> - **Stage10 完了(3.151.0)**: C++ に `PolishState`＋`runPolishChunk`（hf80PostPolish と同一の 11-way オペ構成
>   =単一セル/行内2日swap/同日2者swap/targetedFix×6/copy系DR(violations50%・day50%)＋hard時hf67、同一受理
>   =best-hardゲート＋`polishAcceptN`(acceptWorseScore temp0.15 と同式 exp(-Δ/30))、keep-best、hint=best盤面の
>   violations セル）。JNI 4関数（nativePolishCreate/Chunk/Read/Destroy）・ABI_VERSION=5。Kotlin `hf80PostPolish` は
>   `runPolishChunksNative`（200反復/チャンク・チャンク間で締切/E10停滞/キャンセル確認・best改善チャンクを
>   Kotlin fullEval Long== 照合）を先に試し、完走なら早期return（ログ「PostPolish …（ネイティブ）」）・番兵発火時は
>   照合済みbestを引き継いで従来 Kotlin ループが残り時間を続行（退化不能）。ホスト検証 TEST9: 6シード×25チャンク
>   =status0・自己整合・keep-best単調・hardゲート(63→0)・希望ロック不変。恩恵経路=RSI++ Phase4(45s×5ワーカー)・
>   optimize epilogue・仕上げ(polishOnly)。
> - **Stage11 完了(3.152.0)**: ①**[重要発見] `UiState.softPolish=true`（仕上げ最適化トグル）が既定ON のため、
>   `SaOptimizer:81` の `!params.softPolish` 条件で**既定設定では SA ネイティブ(Stage3)が丸ごと無効**だった
>   （実機ログの「ネイティブ探索=有効」表示でも V5 シード60s と RSI 奇数ラウンドの SA は全部 Kotlin。加速して
>   いたのは ALNS チャンクのみ＝実測+20%はALNS だけの寄与）。②C++ に `LahcState`＋`runLahcChunk`
>   （PhaseB=HARDガード付きLAHC の忠実移植: オペ=PhaseAと同一4種60/20/12/8・受理 candHard<=bestHard &&
>   (cand<=hist[bIt%L] || cand<=cur)・hist更新 cur<hist→hist=cur・keep-best。hist/bIt/bestHard はチャンク跨ぎ保持）。
>   JNI 4関数（nativeLahcCreate/Chunk/Read/Destroy）・ABI_VERSION=6。③SaOptimizer: softPolish 条件を撤去して
>   ネイティブ有効化＋`runWorkerNative` にラダー境界の hardStallMs 判定→`runLahcNative`（4000反復/チャンク・
>   2層番兵・発火時はワーカーごと Kotlin runWorker へ退化）で PhaseA→PhaseB の一方向遷移を移植。
>   ホスト検証 TEST10: 6シード×25チャンク=status0・自己整合・keep-best単調・HARDガード(hard 1〜4→0 単調)。
>   恩恵経路=**既定設定の全 SA フェーズ**（V5シード・高速・RSI 奇数ラウンド・RSI++ Phase1）が初めてネイティブ化。
> - **第3期 完了(3.153.0)**: Stage12(V6LateOperators)/Stage13(後処理チェーン)は**精読・定量の結果、移植しないと確定**。
>   ①コスト実測: LateOperators=ラウンド境界 O(2〜8回)×候補52手×checker(サブms)≈**~100ms/実行**、後処理チェーン=
>   実機 POST 総985〜1328ms＝**合わせて300s予算の~0.5%**。②両者の採否ゲート(gate/gateW/isBetter)は checker の
>   weightedScore/breakdown に直接依存＝C++化にはチェッカー移植が必要で「Kotlinが正」の合意に反する（Stage9/3.139.0 の
>   除外理由と同一）。→ 0.5%の利得のために安全アーキテクチャを壊さない。**実行時間ベースの C++ 移行はこれで完了**
>   （全ホットパス=評価器/SA PhaseA/LAHC PhaseB/ALNS/修復群/hf67/HF80研磨がネイティブ・残りは意図的に Kotlin の軽量制御層）。
>   併せて **NativeBridge 診断行の表示バグ修正**: 有効/無効を `NativeGate.enabled`(番兵のみ)→`usable`(番兵×トグル×ロード)
>   判定へ（旧: 設定トグルOFFの実行でも「ネイティブ探索=有効」と表示され A/B ログの判読を妨げていた。eb7919aa ログで実害確認）。
>   ラベルも実態に同期「有効(SA＋LAHC＋ALNS＋研磨チャンク)」/「無効(設定トグルOFF)」。
> - **(3.154.0, 全体計算の最小化=ユーザー指示「全体計算は必要最低限にする」)**: 番兵の全体計算（チャンク自己整合＋
>   改善チャンクの Kotlin fullEval 照合）は正しさの根幹のため不変とし、**表示/無駄の全体計算だけ**を削減:
>   ①PhaseA/LAHC ワーカーの**非改善チャンク flush の毎回 unflatten を撤去**（flush は localBest<globalBest でしか盤面を
>   読まず非改善では勝てない＝純粋な無駄。直近の照合済み best をキャッシュして渡す）②ALNS runRestartNative の
>   **checker.check＋liveBest 全面コピーを 250ms 周期に間引き**（表示専用。番兵 fullEval は改善毎に維持・restart終端／
>   番兵発火時は syncReport() で最終同期＝ログ精度不変）③**PolishChunk のチャンク頭 collectViolationCells を撤去**
>   （hint は best 基準で生成時＋改善時に更新済み＝毎チャンクの全面スキャンは冗長）④Polish チャンク 200→400反復
>   （チャンク末尾の自己整合フル評価の頻度半減・締切/停滞/キャンセル粒度は ms 級のまま）。全て挙動同一クラスの
>   純減量（hint 鮮度と番兵頻度の意味論は不変）・ホスト TEST1-10 全パスで回帰なし。

## ネイティブ加速 第2期: ALNS/RSI本体のC++化（進行中・明示合意）
> ユーザー指示「ALNS/RSI本体のC++化する」（2026-07-11）。3.139.0 の範囲確定（対象外）を**明示指示で解除**。
> 前提: Kotlin チェッカー/修復系が「正」の原則は維持し、C++ は同値の高速版＋2層番兵（チャンク自己整合
> ＋Kotlin照合、発火で NativeGate 退化）を第1期と同様に必須とする。段階計画:
> - **Stage5 完了(3.140.0)**: C++ に `collectViolationCells`（violations マップの8族=c1窓ラン先頭/
>   c42(s)ペア両セル/c3×4(run先頭・窓先頭・forbidden全セル)/pref/groupViol、count/need系はループ内未使用で対象外）
>   ＋ `GlsPenaltyN`（密配列・augment/moveAug/penalizeWorst/decay80%・lambda200）＋ `glsAcceptN`（SA/GD/Lam 3モード、
>   hard+2超は常に却下）。ホスト検証: セル抽出=重複なし・pref/groupViol完全包含、GLS=augment/moveAug 2000手一致・
>   decay算術一致。まだ未配線（Stage8 で ALNS チャンクが使用）。
> - ~~Stage5~~（完了・上記）: C++ 違反セル抽出（UnifiedViolationChecker の violations マップ相当＝GLSキック/
>   destroyRepairViolations の hint 用。MirrorCore の mark 系を移植・cells のみで breakdown/weighted は不要）
>   ＋ GlsPenalty 移植（penalty行列・augment/moveAug/penalizeWorst(util)/decay/lambda）。
> - **Stage6 完了(3.141.0)**: soft-aware 修復3種（destroyRepairDayAtN=非希望→休destroy+need1不足を
>   marginal soft(個人90/45/apt＋群c41DayMarg)最小の休職員でrepair / StaffAtN=行destroy+被覆穴のみ埋め /
>   ViolationsN=hint最大8セルをmarginal最小へ再割当・空hintはrandomAllowedCell）＋ find*Fix 8種
>   （covO/c2/rangeLow/c41/rangeHigh/c41s/c3Want/apt、c41系は群/スキル群を共通関数でパラメタ化）＋
>   findTargetedFixN（一様シャッフル順）。SaChunk の ssn/dsn が countForStaff/countOnDay に対応。
>   ホスト検証: 修復3種×200試行=wishLocked不変・担当可のみ・変更範囲閉じ込め、finder 500/500発見・全手妥当。
> - ~~Stage6~~（完了・上記）: soft-aware repairs 移植 = destroyRepairDayAt/StaffAt/Violations＋staffCountPenaltyAt＋
>   c41DayMarg（V6NativeOptimizer 1006-1200行）＋ findTargetedFix（8種, V6HotfixPasses）。
> - **Stage7 完了(3.142.0)**: hf67HardRepairN 移植（hf66=範囲外/担当外→先頭担当可 → 実現可能希望の適用 →
>   被覆不足3周充填(bestStaffForCoverage=上限超過500+回数×3+引き抜き不足コスト50・counts周内据え置き=Kotlin同) →
>   range下限充填(乱数タイブレーク)）。in-place 変異・changed数を返す。ホスト検証: 範囲外混入盤面100試行で
>   全セル担当可＋実現可能希望の完全充足、hard 63→48 の修復実効を確認。
> - ~~Stage7~~（完了・上記）: hf67HardRepair 移植（copy系オペの7反復毎・hard>0時のみ呼ばれる修復）。
> - **Stage8 本体完成(3.143.0・未配線)**: C++ に `runAlnsChunk`＋`AlnsState`（GLS・適応重み・Lam温度・best・
>   停滞カウンタをチャンク跨ぎで保持）を実装。runAlns 404-597 の内側ループを 1チャンク=N反復で完走:
>   7オペ（op0-2=copy系destroyRepair＋差分適用/op3-6=直接評価swap/randomCell/targetedFix）・
>   受理3モード(SA/GD/Lam)・opSelect(roulette/thompson)・softFocus(0.30/0.15)・hf67(7反復毎hard>0)・
>   GLSキック(停滞200超・50反復毎・256キック毎decay)・適応重み(64反復毎・反応0.2・下限0.05)。SaChunk に
>   resetBoard(restart境界のcur差替)追加。JNI: nativeAlnsCreate/Chunk/Read/SetCur/Destroy＋ABI_VERSION=4。
>   番兵1層目=チャンク末尾の自己整合(status!=0で退化)。ホスト検証: 3受理×2選択×20チャンクで status=0・
>   cur/best自己整合・keep-best単調（合成問題で 63M→800台の改善実効も確認）。**Kotlin runAlns への配線は
>   Stage8b（次段）**: SaOptimizer.runWorkerNative と同型の退化フォールバック＋2層目のKotlin照合を付す。
> - **Stage8b 完了(3.144.0)**: Kotlin runAlns へ配線。restart 本体の内側 while を `if (!usedNative)` で
>   囲み、ネイティブ可能時は `runRestartNative`（ローカル suspend fun）が 1チャンク=200反復で C++ ALNS を
>   駆動。Kotlin 保持: restart 境界の perturb+hf67・進捗onProgress/liveBest・キャンセル(チャンク間ensureActive)・
>   予算deadline・**2層目番兵**（best 更新チャンクを Kotlin Evaluator.fullEval で Long== 照合、不一致で
>   NativeGate 退化）。problem ハンドルは restart 跨ぎ共有・try/finally で destroy。番兵発火時は false 返しで
>   その restart 以降 Kotlin ループへ。SaOptimizer.runWorkerNative と同型。診断ログ「ネイティブ探索=有効(SA＋
>   ALNSチャンク)」。これで 60s 主経路(RSI→ALNS)の ALNS フェーズが加速。GLS penalty は restart 毎再構築
>   （生スコア最良は別管理＝退化なし）。Kotlin コンパイル検証は CI（assembleDebug）。実機で番兵不発を要確認。
>   **[3.161.0で訂正]** 上記「GLS penalty は restart 毎再構築」は誤り。実装は`runAlns`呼出につき1個の
>   `GlsPenalty`をrestartループの外側で生成し、全restart間で共有（decayのみ希薄化）。再構築されるのは
>   `runAlns`が新規に呼ばれた時（RSI各ラウンド/並列ワーカー等）のみ。globalBestは生スコアで別管理のため
>   受理動学にのみ作用し正しさは不変（keep-best）＝実害なしのHF77（コメント≠実装）訂正。
> - ~~Stage8/8b~~（完了・上記）: ALNSチャンク統合（チャンク=200反復: curReport更新周期に一致。7オペ・適応重み(roulette/
>   Thompson)・受理3モード(SA/GreatDeluge/Lam)・softFocus・wishLocked・GLSキック(50反復毎)を C++ 内で。
>   Kotlin保持: restart境界(perturb+hf67入口)・進捗/liveBest・キャンセル・番兵）。
> - **Stage9 完了(追加移植なし・実測は実機)**: RSI/RSI++ のラウンド内探索本体は `runAlns`(Stage8b) または
>   `runV5`→`SaOptimizer.run`(Stage3) を呼ぶため、**Stage3+8b の配線で既に全探索フェーズが加速済み**（追加の
>   チャンク化コード不要を確認: runRsi:700 が phase=runAlns/runV5、runRsiPlus も seed=runV5＋runRsi/runAlns）。
>   RSI 固有の制御層（focus選択/HF63/rsiGenerateHypothesis/EarlyChain=V6LateOperators/better判定）はラウンド境界で
>   O(2〜8回)しか走らない軽量な Kotlin「正」の層＋チェッカー breakdown 依存のため C++化は対象外（3.139.0 と同方針）。
>   実測は実機ログの TIME行 反復数比較＋NativeBridge行の番兵不発確認で行う（サンドボックスは Android コンパイル不可）。
>   **これで第2期(ALNS/RSI本体のC++化)の移植は完了**。加速経路=V5/高速/RSI/ALNS/RSI++ の全探索フェーズ。
> 各Stageでホスト検証（scratchpad/native_test.cpp 拡張）→CI→実機ログ確認の順。

## ネイティブ加速（C++/NDK, 進行中）
> ユーザー指示「アンドロイドのネイティブ開発言語にして、実行速度改善する」（2026-07-11）。backlog#3 の
> 「C++/NDK 移植は不要」結論を明示指示で解除。**目的=両方（待ち時間短縮＋同時間の品質向上）／範囲=ホットパス
> （Δ評価＋SA内側ループ）のみ**で合意（AskUserQuestion）。方針: **Kotlin 実装を常に正として残し**、C++ は
> 高速版。返却盤面は Kotlin 側フル再評価で照合し不一致なら破棄（退化不能の番兵）。.so ロード失敗時は
> `NativeBridge.available=false` → 全経路 Kotlin フォールバック（JVMユニットテストも従来どおり）。
- (3.136.0, Stage1=足場): NDK/CMake ビルド配線（`app/src/main/cpp/`・ndkVersion 26.1.10909125・
  arm64-v8a のみ・CMake 3.22.1）＋ JNI 疎通（`NativeBridge.nativeAbiVersion` の ABI 照合）＋
  handleOptimize の診断ログに読込可否を1行表示。CI 両ワークフローの sdkmanager に ndk/cmake を追加
  （v6-engine-check は assembleDebug で NDK ビルドも検証）。エンジン動作は完全不変。
- (3.139.0, Stage4=配線完成＋範囲確定): ①**設定トグル「ネイティブ加速（C++）」**を最適化設定に新設
  （UiState.nativeAccel 既定ON・NativeGate.userEnabled と連動・実行中は変更不可）。番兵ゲートとは独立の
  ユーザー意思で、OFF=常に従来Kotlin。②**範囲決定: ALNS/RSI 本体のチャンク化は対象外**（=このプロジェクトの
  ネイティブ化はこれで完了）。理由: runAlns の反復ループは 7反復ごとの hf67HardRepair・200反復ごとの
  UnifiedViolationChecker 再検査・GLSキック(違反セルhint)が Kotlin の「正」実装と分かちがたく、これらの
  C++化は「ホットパス限定・Kotlinが正」の合意に反するエンジン全体移植になる。加速済み経路=V5(≤30s)・
  高速計算・RSI++(≥211s) Phase1種。60s主経路(RSI→ALNS)は Kotlin のまま（差分評価・零アロケ済みで
  1,800万反復/52s の実測性能）。再チャレンジする場合はチェッカー/hf67 の C++ 移植込みの新規合意が必要。
- (3.138.0, Stage3=SAチャンク): SaOptimizer PhaseA の**冷却ラダー1本を1チャンク**として C++ で完走
  （runSaChunk: Kotlin と同じ4オペレータ(single/swapDays/blockFill/LNS)＋Metropolis＋undoバッファ。乱数は
  mt19937_64=経路一致は狙わずスコアと盤面でパリティ）。スコアは**影響スライスの before/after 再計算**による
  差分方式（行族=c1/c2/c3系/pref/range/apt/weekly・日族=c41系/c42系/cov・群族=fair、ssn/dsn/wd を増分維持）。
  **番兵2層**: ①チャンク末尾に C++ 内で fullEval と照合(status!=0=Kotlin側が破棄) ②best 更新チャンクは
  Kotlin Evaluator.fullEval で Long== 照合。どちらか発火で NativeGate が閉じ**そのプロセスは Kotlin へ退化**
  （クラッシュさせない）。Kotlin が保持: 予算/キャンセル(チャンク間)・進捗flush・MagiConductor 境界
  （updateStagnationBulk 新設で停滞を一括反映）・strongPerturb。softPolish(PhaseB=LAHC) 有効時は従来 Kotlin。
  対象経路=SaOptimizer 利用箇所（V5≤30s・高速計算・RSI++ Phase1種）。ALNS 本体は Stage4+。
  **ホスト検証済み**（サンドボックスの clang++ ＋ JNI スタブ）: ランダム2万手で差分==フル一致・12シード×
  ラダーで status=0/スコア照合/keep-best 保持（scratchpad/native_test.cpp）。ABI_VERSION=3。
- (3.137.0, Stage2=C++フル評価器＋実行時パリティ): Evaluator.fullEvalParts を C++ へ忠実移植
  （magi_native.cpp。c1 canDoガード・c3 run-deficit/窓#fire・pref実現可能のみ・range 90/45・apt/fair/weekly
  L1偏差・covU/covO per-cell OR/AND、Math.round は floor(x+0.5) で同一化）。Problem は NativeEval.flatten が
  平坦配列（meta/staff/canDo/wish/needs/ranges/cons/c3/bucket）で1回だけ JNI へ渡す（members は sgrp から
  C++側導出）。**実行時パリティ**: handleOptimize 完了時に採用盤面で C++ vs Kotlin の hard/soft を照合し
  診断ログ1行（一致=µs比較付き / 不一致=W警告＋ネイティブ経路不使用）。ABI_VERSION=2。read-only＝採用結果に
  影響なし・スコアリング不変。JVMテストは available=false で全経路 Kotlin のまま。
- 予定: ~~Stage2~~（完了・上記）→ Stage2旧記述: C++フル評価器（平坦化 Problem を JNI へ1回渡し・Kotlin Evaluator と実行時照合）→
  Stage3=SAチャンク（Δ評価＋受理を C++ で回し返却盤面を Kotlin 再評価）→ Stage4=V6NativeOptimizer 配線
  （設定でON/OFF・フォールバック維持）。

- (3.135.0, 制約の項目名称を下流→上流で統一): 指示「各制約などの項目名称を下流から上流に向かって用語統一する」。
  **下流=違反チップ(breakdownLabels)の語彙を正**とし、上流（編集画面の節タイトル・ダイアログ題・診断ログ）を一致させる
  （違反を見て設定を直しに来たとき同じ名前で見つかるように。単位・補足は括弧で添える）:
  c1「期間の決まり」→**「窓の要件（○日間に△回以上）」**・c2「個人の合計回数」→「個人の合計（回数）」・
  c3m「並び希望」→**「推奨の並び」**・c3mn「並び回避」→**「回避の並び」**・c41(s)「グループ/スキル別の1日の人数」→
  **「群/スキル群のレンジ（1日の人数の下限〜上限）」**・c42(s)「…組み合わせ禁止」→**「群/スキル群ペア禁止（同じ日に不可）」**。
  診断 c3FamilyJp の英字混じり「必須MUST/禁止FORBIDDEN/希望Want/回避Hate」→ 並び4族の日本語名へ（operator_ux の
  「英字記号を画面に出さない」に整合）。groupViol は下流内の分裂（グループ不整合 vs 担当できないシフト）を
  **「担当外シフト」**へ統一。ColorSettingsView のチップ/ピッカー題も生キー(c3n等)→ breakdownLabels の日本語ラベルに。
  AttentionCards の「群レンジ」→「群のレンジ」。セクション注記（C41/C42 の生コード含む）も同語彙へ。文字列のみ・スコア不変。
- (3.134.0, 実機バグ修正=必須違反の枠が白リングだけに見える): 実機報告「違反の枠の色がおかしい」。原因=
  **Modifier.border はチェーンの先が最後=最前面に描かれる**（内側 drawContent 後に自枠を描く）のに、
  violationBorder(hard) がハロー5dp→違反色3dp の順で連結し、**ハローが違反色を完全に覆って白リングだけが見えていた**
  （3.118.0 のコメント「後掛けが上に描かれる」が逆。暗テーマでは暗ハローで目立たず、UD白地固定=3.121.0 で露呈。
  凡例はハロー無し単純枠のため赤く表示され、グリッドと食い違っていた）。違反色を先（最前面）・ハローを後（背面）に
  修正: 外側3dp=違反色/内側2dp=ハロー。破線(vk2)と角マーク(vk3)は同一 draw ブロック内で正順のため影響なし。
  FlatCell/CalendarCell 双方が同ヘルパー経由で同時修正。表示のみ・スコアリング不変。
- (3.133.0, 用語統一=全画面の表記ゆれ解消): 指示「用語統一する」。UI文字列を全数調査し5クラスタを統一:
  ①**「スタッフ」→「職員」**(34件。3.114.0 のドア名「職員管理」・集計「職員別」に整合。**CSV往復キーワードは除外**=
  ScheduleCsvBridge の「スタッフ \\ 日付」ヘッダと looksLikeScheduleCsv の startsWith 判定は同期のため不変。
  「ユニット」は外部フォーマット(ユニット列形式)の語彙のため不変) ②「盤面」→「勤務表」(診断ログ) ③「守れていない約束」
  「必須の条件」→**「必須違反」**(凡例と統一) ④c41説明の「最低／最高」・一括設定の「最低=最高」→**「下限／上限」**
  (ConstraintDialog の入力語と統一。need の「最低人数」は 3.127.0-B の決定どおり維持) ⑤「作る」→**「つくる」**
  (主導線「勤務表をつくる」に統一)。文字列リテラルのみ・スコアリング不変。V6SanityPortTest の assert("担当不可")は部分一致で不変。
- (3.132.0, 違反色の入口一本化=IA重複解消): 違反色の設定が **ShiftColorCard 内の必須色のみの部分入口** と
  **詳細設定（折りたたみ）内の ColorSettingsView（基準色2種＋族別の完全版）** の2か所に分裂し、後者は見つけにくかった。
  ShiftColorCard の「違反の色（必須違反）」節を撤去し、**ColorSettingsView を設定タブのシフトの表示色直後へ移動**
  （詳細設定はログのみに）。編集APIは不変（__vio__ は ColorSettingsView の「必須の基準色」チップから従来どおり変更可）。
  文言・コメントの「詳細設定→違反種別の色」参照も更新。表示・導線のみ＝スコア不変。
  (3.132.1, /code-review 後始末): 未マージ17コミットをインラインレビュー（サブエージェントはAPI上限で不可）。
  実バグ0。残滓2件を修正: 詳細設定の説明文「ログ・違反色トークン」→「ログの確認と出力」（移動後の実内容と整合）、
  AdvancedSettingsSection の未使用 vm パラメータ除去。制約編集の値順ラウンドトリップ（C42 の g/s 写像含む）・
  希望編集の移動意味論・loadAsync(markResult)→makeUi resultFresh 経路は検証で健全を確認。
- (3.131.0, 希望シフトの日入力カレンダー化=バックログ「希望の日範囲選択」解消): WishDialog の日入力（±ステッパー＋
  テキスト、3.112 で「指示範囲外」と据え置いた最後の旧式）を **NeedDayEditor と同じ DayPickerGrid**（日曜始まり・
  タップトグル・**複数日一括**）へ統一。「適用（N日）」で選択日すべてに同じ希望を登録。チップ編集は**移動の意味論**
  （元の日を選択から外す/別スタッフへ付替えると元の希望を削除。同スタッフ×元日が残っていれば setWish 上書き）。
  未使用 import（Button/OutlinedTextField/KeyboardOptions/KeyboardType/width）除去。表示・入力導線のみ＝スコア不変。
- (3.130.0, 実機指摘2件=制約行の編集＋色ピッカー修正 / bg復元配線の完遂): ①**「登録した制約の変更ができない」**:
  ConstraintsCard/SkillConstraintsCard の行は削除のみだった→ **行タップで変更**（追加ダイアログのプリフィル版
  `ConstraintDialog(editIndex)`・確定で同位置を置換）。vm に `constraintRowValues(family,index)`（生値の取得、
  値順=追加ダイアログの入力順）と `updateConstraint(family,index,values)`（cons3系は addCons3 と同じ正規化
  =最初の空白まで・最大5）を新設。全10族（cons1/2/3系4/41(s)/42(s)）対応。mutateConstraints 経由=undo/再検査/保存は
  追加・削除と同一。②**「色ピッカーのレイアウトがおかしい・現在の色が無い・他の色も選択できない」**:
  ColorPickerDialog が 12色を chunked(5)→端数行2個が weight で巨大化していた→ **20色=5×4 の完全グリッド**
  （端数行は空 Spacer で同サイズ維持）。未設定時に「現在の色」がグレー（hexToColor("")のフォールバック）だった→
  **defaultHex（実効の既定色）**を新設し、必須=#BA1A1A/要調整=#E08A1E/族=重大度の基準色を渡して表示＋パレット✓一致。
  パレットに既定色と MagiAccent 系（赤/橙/緑/青/紫/桃/灰）を収載。③3.127.0-③の `loadAsync(markResult)` が
  **本体未配線**（hasResult=false のまま）だった回帰を完遂: markResult 時は hasResult=true＋resultSchedule も設定。
  ①②は表示・編集導線のみ、③は表示フラグのみ＝スコアリング不変。
- (3.129.0, 冗長性見直し=説明・項目名称の短文化): 指示「説明や項目名称の冗長性を見直す」。監査28件のうち
  適用対象を一括置換: 見出しの括弧補足を削除（今月の作成条件/片手モード/仕上げ最適化/まとめて割当 等）、
  重複説明文の削除（SettingIssues/E7チップ操作説明）、長文説明の要点化（FixSuggestion/Breakdown/ConfirmList/
  実働チェック/WishEditor/NeedDayEditor/StaffRangeEditor/SkillGroupEditor/SectionNote①）、「時間予算」→
  「計算の制限時間」。文言のみ・スコアリング不変。
- (3.128.0, 短予算も複合パイプラインへ=実機指摘「60秒予算を1つだけのアルゴリズムで使用」): AUTO の予算プランで
  31〜90s が ALNS 単発（60s=ALNS×1）＝詰まった HARD 族（アリフ c3n 等）を狙う RSI フェーズが一度も走らず、
  仮説5本も同一解に収束していた。**31〜210s を RSI(2/3)→ALNS(1/3) の複合に統一**（既存 RSIThenALNS チェーン
  を短予算へ拡張）。各段は入力比 keep-best 番兵つき＝入力より退化しない（原理採否・3.74.0 と同方針。bench は
  dispatch/RSI focus を模擬できない）。getAlgorithmLabel と V6FinalBridgePortTest のバンドも同期。
- (3.127.1, クラッシュ修正=「閉じても大丈夫」で即落ち): 実機報告。原因=**マニフェスト不足**。Worker は
  setForeground(FOREGROUND_SERVICE_TYPE_DATA_SYNC) で前景化するが、targetSdk 34+ は WorkManager の
  SystemForegroundService **宣言側にも foregroundServiceType="dataSync" のマージが必須**（権限だけでは不足）。
  無いと起動瞬間に MissingForegroundServiceTypeException（サービス側スレッド発生＝runCatching 捕捉不能）で
  アプリごと落ちる。manifest に tools:node="merge" のサービス宣言を追加。
- (3.127.0, 他画面見直し第2弾＋実機指摘5件の一括): 並列3監査（編集タブ3ドア/ホーム・分析/設定・エディタ）＋
  実機スクショ5枚。**実バグ修正**: ①ホーム見出しが人手不足ゼロの必須違反(希望/禁止連続/群)でも「人手が足りない」と
  誤診断＋GuidedFix が空回り→ 不足なし時は「必須の条件がN件残っています」＋分析タブの修復フローへ振り分け
  ②一括シート(割当/希望)が実行中でも適用でき完了時に黙って上書き消失→ !running ゲート＋ボタンが理由を語る
  ③bg最適化の復元結果が「未作成」表示(hasResult=false)→ loadAsync(markResult) で結果扱い。**実機指摘**:
  ④一括割当の矛盾解消=担当外職員を対象から自動除外（担当外違反の大量生成を根元で防止）＋存在しない「公」の
  文言删除 ⑤凡例チップの形バラつき(有/Aｱ/A4=文字種で内在サイズ差)→32dp固定高で均一化＋違反凡例をFlowRow化
  （縦1文字潰れ解消）⑥「今月にする」→**「来月にする」**(setNextMonth。月末に来月分を作る業務)
  ⑦日別必要人数の**複数日一括設定**（DayPickerGrid を Set トグル化・適用（N日））⑧重み表を分析タブ→設定タブ
  （最適化設定の直後）へ移動。**文言/整合**: SetupGuideCard の旧①〜⑤番号と旧スコープ名→新3ドア名へ・NeedDay
  誘導文・editScope コメント・中断メッセージのボタン名不一致・「直し方→」青リンクの WCAG 担保・StaffRange
  チップ色の違反トークン追従（🔴🟠絵文字凡例を廃止）。クリーン確認: applyAlternative/applyFixSuggestion は
  currentSchedule 更新で D7 後も正常・CSV/JSON 出力は編集中盤面・タブジャンプ先は全て正しい。表示・導線のみ＝スコア不変。
- (3.126.0, 「下書きをつくる」撤去): ユーザー判断「下書きをつくる不要です」。思考誘導カードの補助ボタンと
  onDraft パラメータを撤去し、作成導線は「勤務表をつくる」（本最適化）1本に。generateSimple() は API として温存。
- (3.125.0, 全画面再検証=並列3監査の修正): ユーザー指示「他の画面も再検証」で D7残滓/UDコントラスト/文言整合を
  並列監査。**D7残滓=実害ゼロ**(CSV出力は編集中盤面・死に分岐なし)。**UD白地コントラストの実害5件を修正**:
  ①MagiTagChip の生アクセント文字(橙2.7:1/緑3.4:1)→実効背景に compositeOver+ensureReadable ②グリッド今日
  マーカー緑→tertiary ③編集シートの注記(不足解消/超過)を ensureReadable ④実働チェック「！」橙→warnFg
  ⑤編集シート希望行の桃を太字化(大テキスト3:1基準で担保)。**文言整合2件**: ⑥違反種別の色の説明が自己矛盾
  (外観へ誘導)→「下の2チップから」へ ⑦集計の超過地色を __vioSoft__ トークン追従に(グリッドと同じ色言語)。
  クリーン確認: テーマ/読取モードの stale 文言ゼロ・Color.White は全て ensureReadable 済・ハロー白は白地でも
  設計上問題なし(枠色自体が淡地に対比)。表示のみ・スコアリング不変。
- (3.124.0, スタッフ別カレンダーの判読性=実機指摘「レイアウトが見にくい」第2弾): ①全違反を桃地+枠で塗って
  飽和していた（3.99.0 でグリッドは解消済みの残存）→ **グリッドと同じ3段階**（必須=桃地+実線 / 重=破線のみ /
  軽=右上角マークのみ）へ。CalendarCell を vk(0..3) 受けに刷新。②日付ラベル「12/1(月)」×31 の月接頭辞ノイズ→
  「1(月)」＋土青/日赤の曜日色。③D7残滓の掃除（孤児KDoc・UiState の読取モード言及コメント）。表示のみ・スコア不変。
- (3.123.0, 集計レイアウトの白抜け解消=実機指摘「レイアウトが見にくい」): シフト集計の 0セルが cs.surface
  （UD=真っ白）で表に白い穴が空いて見え、市松状のノイズになっていた→ **0セル=surfaceVariant α0.35 の淡色**へ
  （職員別/日別とも）。日別の**全日0のシフト行（未使用シフト）はラベルも淡色・細字に沈め**、使っている行の
  模様を浮かせる（行は消さない＝存在は読める）。表示のみ・スコアリング不変。
- (3.122.0, 違反種別の色=族ごとの個別設定): 実機指摘「違反種別の色を個別に設定できない」。**チップタップ=その族の
  色を個別変更**（新予約キー `__vioFam_<fam>__`、UiState.violationFamilyColorHex）。未設定族は重大度色
  （__vio__/__vioSoft__）へフォールバック＝従来互換。`resolvedVioColor(ui, cls, hard, soft)` が族→重大度の順で解決し、
  グリッド枠/角マーク（vioCls per-cell 配列を新設）・職員カレンダー・編集シートの理由テキストへ即反映。
  基準色2種（必須/要調整）の一括変更チップも下段に併設。情報(灰)族も族色設定可（設定すれば優先）。表示のみ・スコア不変。
- (3.121.0, D8=外観UD固定): ユーザー指示「外観は UD モードのみ」。テーマセレクタ（自動/明/暗/UD）を撤去し
  **UD（高コントラスト・白地）固定**。MainActivity は MagiTheme(3) 直指定＋ステータスバー暗アイコン固定、
  AppearanceCard は 片手モード＋表示モード（かんたん/プロ）のみに。明/暗/UD の配色定義は MagiTheme に温存
  （mode引数経由・復活可能）。表示のみ・スコアリング不変。
- (3.120.0, D7=読取(結果)モード撤去): ユーザー判断「読み取り結果モードは不要。下書き直すモードだけで大丈夫」。
  勤務表タブの ScheduleModeCard(結果/下書き切替)・gridUi差し替え・CellInfoDialog(3.119.0)・hintReadOnly を撤去し、
  **常に直接編集の1本**（タップ=即編集シート）。最適化完了時は schedule==resultSchedule のため結果はそのまま見える。
  結果スナップショットのモデル(resultSchedule/result専用違反マップ=3.96.0)とcommit/copy APIは温存（UI参照ゼロを明記）。
  誤編集の担保は「元に戻す」。**読取/編集の整合問題(backlog#1系)はモード自体の消滅で恒久解消**。
- (3.119.0, 読取モードの理由表示=実機指摘「なぜ例が出ていないんですか？」): 違反理由の表示(3.109.0)は編集シート
  実装のため、既定の**読取(結果)モードではタップしてもヒントだけで「なぜ違反か」が見えなかった**→ 読取タップで
  **見るだけの `CellInfoDialog`**（割当・違反理由の全列挙[重み降順・トークン色]・希望の反映状態・「直すには下書きへ」の案内）。
  変更操作は従来どおり不可。グリッド説明文に「緑リング=反映済み」も追記（前問の混乱対応）。表示のみ・スコア不変。
- (3.118.0, 違反枠のハロー縁取り=実機指摘「禁止の違反の枠が見にくい」): ダークテーマの違反色（淡い赤）が
  桃系セル背景（Cｱ等）と同系色で、c3n の実線枠が埋没していた。`violationBorder(halo: Color?=null)` を拡張し、
  **実線=5dpハロー(surface)の上に3dp違反色**（外3dp色/内2dpハロー）、**破線=下に太めの実線ハローを敷く**
  （隙間・両脇がハロー色に）。FlatCell(vk1/vk2)・CalendarCell が halo=cs.surface を渡す。角マーク 3.105.0 と
  同じ手法＝任意のシフト色上で枠が浮く。凡例はカード地=surface上なので変更不要。表示のみ・スコア不変。
- (3.117.0, 集中モード=Web試作③の移植・最終候補): 違反フィルタバーに **「集中」トグル**（既定OFF・rememberSaveable）。
  ON で**違反(vk>0)・未反映希望(wkk=2)・注目セル以外を淡色化**（休セル後退=3.99.0 と同じ alpha0.30＋onSurfaceVariant＋細字。
  非表示にはしない＝被覆の文脈は読める）。E7=種類の絞り込み・検索=行強調・集中=異常の浮き上がり、の直交3機能が揃った。
  `ViolationFilterBar(focusMode,onFocusMode)`→`ScheduleGrid/MagiFlatGrid(focusMode)`→quiet 判定。対象はメイングリッドのみ。
  表示のみ・スコアリング不変。これで Web試作検証の移植候補は**全て完了**。
- (3.116.0, シフト別不足サマリー=Web試作①の移植): 勤務表グリッド上部に **「人員不足（全31日中）: B4 29日 ・ 有 19日 …」**
  の1行バナー（errorContainer）。needViolations の covU をシフト別に日数集計（多い順・重複日dedup）。
  「どのシフトが慢性的に埋まらないか」を数字で即答＝採用/教育判断の入口。E7 人員バケツOFF時は非表示（covU 表示と整合）。
  read-only・表示のみ・スコアリング不変。Web試作検証の残り候補は「③集中モード（盤面淡色化トグル）」のみ。
- (3.115.0, 実働チェック=年度始めモードの心臓): D5 残スコープの完了。年間マスタードア先頭に `StaffingRealityCard`
  (read-only): シフト別に **担当できる人数(canDo) ・ 月間需要人日(Σ need1+日別例外) ・ 1人あたり回数 ・
  欠勤余裕 = 担当人数 − 日最大需要**（1人欠けても1日の必要人数を揃えられるか）を ✓/！/⚠ で提示。
  「15人いるから大丈夫」ではなく「B1 は実質4人運営」の認識へ誘導する。データは Problem 由来
  (allowedShiftsFor/needCellLimits)＝チェッカーと同じ実効値。需要0のシフトは非表示。表示のみ・スコアリング不変。
  これで時期モード設計の確定スコープ（月末=3.114.0 / 年度始め=本件）は**全て完了**（年度末=D5で不要）。
- (3.114.0, 入口4分割＋月次チェックリスト＋違反ナビ＋見直し候補): ユーザー承認済みの入力アーキテクチャ実装（D5/D6 の
  範囲確定後）。①**編集タブを3ドアへ再編**: 「月次条件(毎月)／職員管理(随時)／年間マスター(制度変更時)」
  (旧: 今月の調整/シフト希望/基本マスター。シフト希望は月次条件へ統合。4か所目=勤務表グリッドは勤務表タブ)。
  **職員管理ドア**=新設 `StaffManageCard`(入職/退職/改名/所属/スキル▼を職員単位で。Ws1Card と同一 vm API の別ビュー・併存)
  ＋StaffRangeCard(個人回数)。②**月次チェックリスト** `MonthlyChecklistCard`(月次条件の先頭): 職員N名/希望M/S名/
  必要人数(標準+日別例外K件=D6準拠)/入力診断N件 を ✓/！で確認→「▶勤務表をつくる」(runV6FullOptimize+ホームへ)。
  ③**違反ナビ**(ScheduleGrid): 表示中(E7フィルタ通過)の違反がある日を ＜前/次＞で巡回、focusCell=(-1,j) 番兵で
  日ヘッダを2.5秒ハイライト。④**見直し候補メモ**: セル編集シートの違反セルに「基本ルールの見直し候補にする」→
  `ReviewMemoCard`(年間マスター先頭)に積む(セッション内のみ・state非保存と明示)。「勤務を変える/今月の例外/土台を直す」
  の3分岐のうち第3の出口を明示化。全て表示・導線のみ＝スコアリング不変。
- (3.113.1, 自己見直し=3.112系の色トークン反映漏れ2件): ①編集シートの違反理由テキストの重大度色が
  cs.error/MagiAccent.orange 直書き＝ユーザーが変更した違反色(__vio__/__vioSoft__)が反映されなかった→トークン解決へ。
  ②職員カレンダー(StaffCalendarCard)のソフト違反破線が必須色のまま＝重大度の色分けがカレンダーだけ効いていなかった→
  hard ? __vio__ : __vioSoft__。③vm.start()/runLightOptimize() は 3.112.0 の ActionCard 撤去で UI 参照ゼロ＝
  API として温存しコメントで明示（テスト非依存・削除は保留）。
- (3.113.0, 希望シフトの記号バッジ): ユーザー指示「割付と希望が違っていたら希望シフトをバッジで重ねる」。
  勤務表グリッドの未反映希望(wishKind=2)を、旧・桃ドット（中身が読めない）→**希望シフト記号の桃バッジ**(左下、
  surfaceハロー縁取り・記号フォント=セル記号の70%・ensureReadable)へ。反映済(wishKind=1)は従来の青緑リングのまま。
  読み上げ(cd)にも「希望=記号」を併記、グリッド説明文に「桃バッジ=未反映の希望」を追加。表示のみ・スコア不変。
- (3.112.0, スクショ手書き指摘3系統=撤去・カレンダー化・違反色の変更対応): ユーザーが実機スクショ5枚に赤/青/黄で指示。
  **赤（オブジェクト不要→撤去）**: ①`HeroMetricsRow`(対象人数/対象期間タイル=読込ステータス行と重複、定義ごと撤去)
  ②ホーム`ActionCard`「ほかの作り方」(速く/かんたんは主導線と重複・実行中は全ボタン無効の死に領域。定義ごと撤去。
  **固有機能のバックグラウンド実行だけ SettingsCard(設定タブ最適化設定)へ移設**=`SettingsCard(onBgOptimize)`)
  ③実行中カードの見出し「いま、コンピューターが組んでいます…あと約N分…」(進捗行 progressSummary と重複。headline=""
  ＋isNotBlank ガード)。**青（カレンダー形式）**: 日別の必要人数ダイアログの日入力(テキスト+数字キーボード)→
  **`DayPickerGrid`**(**日曜始まり**=3.112.1でユーザー指示・日赤/土青・1タップ選択・40dp床。勤務表の週ページング
  mondayWeeks=月曜始まりとは別物)。WishEditor の日入力は対象外(指示範囲外)。
  **黄（違反種別の色が変更できない）**: `ColorSettingsView` が read-only 凡例でチップを押しても無反応だった→
  **チップタップでその重大度の色を変更**（必須=既存 `__vio__`・要調整=**新トークン `__vioSoft__`**(shiftColors 予約キー、
  vm.setViolationSoftColor/reset・UiState.violationSoftColorHex)・灰=情報は固定）。要調整色は MagiFlatGrid(破線枠/角マーク/
  日ヘッダ下線)・SearchLegendBar 凡例へ即反映(旧 MagiAccent.orange 直書きをトークン解決に)。ColorPickerDialog を internal 化し
  再利用。外観「違反の色」の説明文も更新。全て表示のみ・スコアリング不変。
- (3.111.0, 残作業④⑤⑥の一括完成=用語統一・違反Set化・日別ジャンプ): ユーザー指示「すべて一括完成させます」。
  ⑤**違反マップSet化**: `ViolationReport.cellFamilies`("i,j"→全違反クラスを重み降順、violations=最重1クラスは後方互換で不変)
  を新設し UiState `violationCellFamilies`(+result版) へ plumbing。効果=①編集シートの違反理由が**全列挙**(重なった c42+c3 等が
  1行ずつ) ②**E7フィルタの整合**: 旧は最重族のバケツOFFで表示中の族が同セルに残っていても枠ごと消えた→ `visibleCellVio`
  (フィルタ通過する最重クラス)で vioKind/カレンダー/違反セル一覧/バケツ件数を判定 ③要確認一覧の sub に全族を「・」列挙
  (行数=箇所数は不変)。SessionRegressionTest で「families 先頭==violations」「軽い族も保持」を回帰固定。
  ⑥**日別ジャンプ**: 要確認一覧の日×シフト項目(人員/群レンジ, staff無し)タップ→勤務表タブの該当日列へスクロール＋
  **日ヘッダを primary 枠で約2.5秒ハイライト**(`focusCell=(-1,j)`=日のみ注目の番兵、行セルには一致しない)。「勤務表→」表記。
  ④**用語統一**: covO ラベル「過剰な配置」→**「人員過剰」**(covU「人員不足」/集計凡例と対)・要確認一覧チップ「過剰・調整」→
  「過剰・**要調整**」(凡例/編集シートの重大度語と統一)・マーク/日別サブの「過」→「過剰」「範囲」→「群レンジ」。
  すべて表示のみ・スコアリング不変(inc/breakdown/weights 不変)。
- (3.110.0, C1/C3のタップ時窓ハイライト): 残作業③。セル編集シートを開くと、そのセルの違反が c1/c3/c3m の場合に
  **違反が指す窓/連の範囲を primary 枠でグリッド上に表示**(シートを閉じると消える)。`vm.violationRange(i,j)`=
  c1は最初の不足窓・c3/c3mは未完成パターン窓または単一シフト連の実範囲を Problem から再計算(read-only)。
  `focusRange`(i,開始日,終了日) を MagiApp→ScheduleGrid→FlatCell へ伝播し focusCell と同枠で描画。
  VBAの「期間を塗る」の利点を、常時でなくタップ時のみ=飽和なしで回収。表示のみ・スコア不変。
- (3.109.0, セルタップで違反理由): 認知ウォークスルー最優先項目。編集シートの状態欄に「⚠ 必須違反: 禁止の並び」
  「△ 要調整: 窓の要件」等を1行表示(violationCells の族→breakdownLabels、3.107 の重み優先で最重の族を保証)。
  従来は枠の意味を要確認一覧/診断ログへ往復しないと理解できなかった「見つける→理解する→直す」の断絶を解消。表示のみ。
- (3.108.0, Web試作の可視化/ナビ移植=ジャンプ＋グループ色帯): Web側試作ログの5機能をネイティブ照合(達成62%)し
  未実装2件を移植。①**セルへのジャンプ**: 要確認一覧のセル違反項目(staff+day保持)タップ→勤務表タブへ切替＋
  該当日列へ hScroll 自動スクロール＋**注目セルを primary 実線3dpで約2.5秒ハイライト後に自動クリア**
  (`focusCell` 状態を MagiApp が保持し ScheduleGrid/MagiFlatGrid/FlatCell へ伝播・LaunchedEffect+delay)。
  回数系項目(日なし)は従来どおり修復フローへ。②**グループ色帯**: 名前列左端4dpに所属群の色帯(出現順に
  黄金角 hsv 自動割当・設定不要)。行追跡の視線ガイド兼用=判読性確率の行追跡72%を補強。表示のみ・スコア不変。
- (3.77.0→3.78.0, 画面修正版の移植融合 ①③): web「画面修正版」を詳細検証しネイティブへ融合（ユーザー承認: 月表=E5は保留維持/
  要確認件数=ロケーション数）。①**週ページング＋横スクロール併用**（ユーザー修正: トグルでなく併用）: `ScheduleGrid` に **前週/次週**
  ボタンを追加し、全日を横スクロールで保持したまま `hScroll.animateScrollTo(週先頭×cellWpx)` で1週ぶんジャンプ。現在週は左端可視日から
  `derivedStateOf` で導出＝自由スクロールにも追従（列は隠さない＝併用）。`mondayWeeks(startDate,days)`(月曜始まり)で週分割。cellW=48 のまま
  ＝1画面≒1週＋スクロールで残り。`MagiFlatGrid(hScroll)` に外部 ScrollState を注入。③**要確認N件**: `ViolationFilterBar` に違反ロケーション数
  (violationCells+needViolations+countViolations の実箇所数)を併記。族fire数(c1=113)でなく作成者が見るべきセル数(golden_state=39)。
  表示のみ・スコアリング不変。
- (3.79.0, 画面修正版 ②検索・凡例の統合折りたたみ): `SearchLegendBar`(既定=閉)を新設し、**検索**(職員名で該当グリッド行を
  太字＋青で強調＝行は隠さず被覆文脈保持)＋**凡例**(ShiftColorLegend＋ViolationLegend)を1折りたたみに集約。グリッド内の凡例は撤去
  (重複回避)。`MagiFlatGrid(nameQuery)`/`ScheduleGrid(nameQuery)` で検索語を伝播。検索状態は勤務表タブに rememberSaveable。
  **E7種別フィルタは折りたたみに入れず独立バーで可視のまま維持**(ユーザー指示)。表示のみ・スコアリング不変。月表=E5は保留維持。
- (3.80.0, 融合仕様 ★1 要確認一覧): 添付 spec `schedule_mobile_fused_minimal.html` の confirm ビューをネイティブへ移植（ユーザー承認:
  E5除外・順序 ★1→2→3→4）。`ConfirmListCard`(MagiDashboardCards)= 散在していた診断を**箇所単位・重大度リスト**で1ハブに統合。
  `confirmItems(ui)` が needViolations(covU/covO/c41/c41s)・countViolations(low/high/c2/aptLow/aptHigh)・violationCells(pref/
  groupViol/c3n/c3/c3m/c3mn/c1/c42/c42s)を個々の項目へ展開し、**不足/過剰/窓**の3重大度マーク付きで列挙（BreakdownCard の族集計を補完）。
  重大度フィルタ(全部/不足・必須/過剰・調整/窓・件数付き)・staff 紐付き項目タップで修復フロー(`vm.findFixSuggestions(i)`)へ・
  設定ミス(settingIssues)あれば先頭に件数導線(→設定タブ)。詳細タブ(3)先頭にヒーロー配置(既存カードは下に併存=安全)。違反ゼロ時は達成表示。
  **表示のみ・スコアリング不変(読取専用)**。フィルタチップは Surface ベース(新規 import 不要)。次段: 2 hero metrics / 3 要確認のみ toggle / 4 日別・人別カード。
- (3.81.0, 融合仕様 ★2/★3/★4): confirm ビューに続く hero/day/staff/alertOnly を移植。**★2 概要ヒーロー** `HeroMetricsRow`(MagiDashboardCards)=
  **対象人数(名)/対象期間(日)/確認事項(件)** の3指標を既存 `BigStat` 再利用で並べ、詳細タブ先頭・要確認一覧の直前に配置。確認事項＝
  violationCells＋needViolations＋countViolations の実箇所数(ConfirmListCard/E7バーと同一定義)。**★3+★4 日別/人別 注意リスト** `AttentionCardsSection`=
  日別(needViolations を日集計・不足/過剰シフト併記)/人別(countViolations＋violationCells を職員集計・行タップで `findFixSuggestions(i)`)を
  MagiSegmentedControl で切替、**「要確認のみ」トグル(既定ON)** で違反0行を隠す＝そのまま triage。**BottleneckCard(top5テキスト・read-only)は
  AttentionCardsSection(全件＋トグル＋タップ修復)の上位互換のため詳細タブから撤去**(~~composable 定義は残置=無害~~
  →3.103.1 で定義も撤去=呼出0)。全て表示のみ・スコアリング不変(読取専用)。
  E5(全月横表)は保留維持。★1→★4 の融合移植これにて一巡。
- (3.82.0, ★1-★4 コードレビュー修正): /code-review(並列 finder×verify)で判明した表示バグを修正(スコアリング不変)。
  ①**ConfirmListCard の迷子フィルタ**: 選択中フィルタの件数がデータ変化で0になるとチップは消えるが `filter` は残り
  空リスト＋見出し件数>0 の迷子に。`effFilter`(件数0なら全部へ戻す)を導入しリスト・チップ選択の両方に適用。
  ②**stale タイトル**: `remember` キーに `staffNames/shiftSymbols/startDate` を追加(職員/シフト改名で行タイトルが古いまま残る)。
  ③**c2 の方向誤表示**: c2(個人の合計)は方向を持たない単一クラス vio-c2 なのに「過」固定だった→ ConfirmList はマーク「計」、
  AttentionCards は方向サフィックスなし(記号のみ)に(下限割れ/上限超過と混同回避)。④`ConfirmItem.shiftSym` 死にフィールド除去。
  非対応(判断): BottleneckCard 定義残置=既に無害と記載済で維持 / AttentionCards の remember 化=毎再構成で再計算するため
  そもそも stale にならず、データ極小で効率影響も無視可 / キー解析の共通化=別スコープ。
- (3.83.0, 見直し=詳細タブの「違反総数」三重表示を解消): 融合カードを旧カードの上に積んだ結果、詳細タブに違反件数が三重化。
  ①**ヒーローの「確認事項(件)」タイル撤去**: 直下 ConfirmListCard ヘッダ「要確認一覧（N件）」と完全に同数＝重複。ヒーローは
  対象人数/対象期間の規模コンテキストに純化(2タイル)、件数は要確認一覧に一本化。②**OverviewDashboard を詳細タブから撤去**:
  「気になる点(=rep.total 総違反リング)」は違反総数の3つ目の見せ方／「注意の日(highRiskDays リング)」は AttentionCardsSection
  日別リストが列挙で上位代替。D2(HARD 三重リング撤去)と同方針で「違反総数」の重複を解消。両 composable 定義は残置(OverviewDashboard は
  未描画の V6RemainingScreens から参照=無害)。CheckSummaryView(守れていない約束=bestHard)/BreakdownCard(族内訳)は固有情報で維持。
  表示のみ・スコアリング不変(読取専用)。

## 停滞脱出の改善（進行中）
探索本体が過拘束データで空転しがちな問題（停滞脱出の質向上）。
- (2.49.0): コードレビューで判明した冗長な後処理パス(applyGroupRangePairPolish)を revert。同日2者スワップ族
  (c41/c42/c41s/c42s/c2)は既存 CyclicSwap が isBetter で total 最小化の過程で研磨済＝専用パスは冗長だった。
  探索 focus 登録(2.46)は真の取りこぼし対策なので維持。
- (2.50.0, 完了): **GLS penalty aging（減衰）**を新設（`GlsPenalty.decay(keepPercent=80)`）。GLS penalty は従来
  増える一方で長期停滞時に肥大化し（観測 36k→64k）受理バイアスが固着していた。一定 kick(`GLS_DECAY_EVERY=256`)
  ごとに penalty を 80% へ減衰し curAug を `augment(cur)` で再同期。**globalBest は生スコア管理のため解の質は退化しない**
  （探索の受理動学のみに作用）。ユニットテスト(GlsPenaltyTest: decayShrinks/decayRemoves)で減衰算術を検証。
- (2.51.0, 完了): **restart摂動の非線形スケジュール**。restart 序盤ほど大きく揺らし(多様化)終盤ほど小さく(intensify)。
  mult=0.6+1.2*(1-frac)^2。摂動のみでスコア不変・globalBest 保持＝退化なし。
- (2.52.0→**2.55.0 で revert**): 戦略的振動(λ係数オシレーション)。受理層で hard を一時割引し実行不可の壁を越える手法。
  PoC(/tmp/osc_poc.py の理想化2盆地+薄い壁)では escape 20/20 だったが、**Python等価ベンチ(tools/nsp_bench.py)で
  現実的NSPでは一貫して悪化(AUC +5%〜+15%)と実測**。理由: 現実の過拘束NSPは feasibility 到達自体が難しく「壁の向こうに
  良い実行可能盆地が無い」ため、振動は限られた予算を実行不可彷徨に浪費するだけ。**「安全(解は退化しない)」は正しいが
  「有益」ではなかった**=安全性と有益性は別物。2.54 の HF63 選択発動も同根のため一括 revert。CRINN流の実測が推論を覆した好例。
  → nonlinear_restart(2.51, 実測で僅か改善)と GLS aging(2.50, 中立・無害)は維持。
- (教訓): 探索動学の変更は **tools/nsp_bench.py の実測報酬で A/B 検証してから**採否する(PoCの理想化landscapeは現実を
  代表しないことがある)。便益が測れない/負なら入れない。
- (2.56.0, 実測結論): nsp_bench.py に **ALNS destroy-repair を追加(高忠実度化)** し再測定。**destroy-repair を入れると
  GLS aging/nonlinear restart/振動 は全て AUC 中立**(repair が支配的で feasibility に容易到達=脱出の出番が消える)。
  GLS keep%/decay_every/lambda・restart係数の**パラメータスイープも全て +0.0%=チューニング価値なし**と実測。
  低忠実度(1セルSA)での「nonlinear_restart改善」は偽信号だった。→ **脱出ヒューリスティクスへの投資は停止**。
  維持判断: GLS aging(2.50)/nonlinear restart(2.51)は中立=無害かつ proxy は短時間簡易のため実機の数百万iter regime
  での penalty 肥大防止の意義が残り得る→維持。**今後の本当のレバーは repair/destroy-repair オペレータの質 と データ側**。
- (2.57.0, 実測駆動の改善): **soft-aware destroy-repair**。`destroyRepairDayAt` は従来ランダム順で被覆穴を埋めるだけ
  (soft無視)だったのを、nsp_bench.py で測った勝ち筋(soft-aware 修復: AUC -24%〜-34%)を移植。非希望セルを休へ destroy →
  各需要を「割当の marginal soft(`staffCountPenaltyAt`=low×90/high×45/apt, Evaluator と同一式)が最小の休スタッフ」で
  repair(休→k のみ=被覆穴を新たに作らない、希望固定は保持)。**探索オペレータの変更でスコアリング不変=Δ×フル無関係**、
  受理(SA/isBetter)が最終採否=安全。脱出ヒューリスティクスが全て中立だった中で実測された唯一の品質レバー。
- (実測・不採用): `destroyRepairStaffAt`/`destroyRepairViolations` の soft-aware 化も nsp_bench.py で測ったが、
  day-repair の上に**上乗せ効果なし**。smart_staff(職員DR)は **over で −23.6%→−9.6% と一貫せず有害**、smart_cell
  (violations相当)は borderline 改善だが over/hard で同等〜微悪化＋proxy が実機(違反セル限定・低頻度)より過大で不忠実。
  → **実装しない**(有害・無効な複雑性を入れない=測定駆動3度目の「やめる」判断)。検証済みの repair レバーは day-repair のみ。
- (実データ追認, nsp_bench `--real`): golden_state(実10職員/31日/10シフト, need=shifts[k].need1, canDo=groupShift,
  staffRange 51セル, apt 23セル, use2)を忠実ロードして A/B。**soft-aware day-repair は最終soft 274→22(12×改善)**=
  実データで大幅な品質向上を確認(2.57 妥当)。ただし**AUC は +24% 悪化**(clear+soft-fill が aggressive で序盤収束が遅い)。
  **指標の教訓: AUC(速度)と final(品質)は実データで乖離。実機は5分・数百万iterの単発最適化で最終品質が成果物=final が主指標**。
  staff+viol は実データで final を 22→12 と更に改善(AUC は +40% 悪化)＝合成の「有害」と逆で、最終品質基準なら小幅改善で
  再検討余地あり(限界効用は小)。合成ベンチの AUC 結論を実データの final で見直す価値がある(脱出機構の再評価含む)。
- (網羅再分析・final品質×実データ, nsp_bench `--real`): 全機構を **final(最終品質)** で再評価(base=R=repair(day) 2.57)。
  **過去の AUC 基準判定が複数覆った**: R+viol **−22.6%**(旧「不忠実で見送り」→改善)、R+staff+viol **−49.5%**(旧「有害」→
  大幅改善・最良級)、R+staff 単独 +52.7%(有害=viol との併用が要), R+oscillation/R+gls+decay **±0%**(inert=撤去/維持で確定),
  **R+nonlinear_restart +101%(旧「中立で維持」→有害!)**。教訓: **製品は final が主指標、AUC でなく final で採否すべき**。
  → 実装方針: **staff+viol soft-aware を実装(−49.5%)、nonlinear_restart(2.51) は revert(+101%有害)**、GLS は inert で維持。
- (2.58.0, 実装完了): final品質×実データの再分析どおり実機へ反映。**`destroyRepairStaffAt`/`destroyRepairViolations` を
  soft-aware 化**(staff-DR=非希望を休へdestroy→被覆穴を marginal soft 最小で repair / violations=違反セルを old→k の
  marginal soft 最小へ再割当。共に `staffCountPenaltyAt`=Evaluator同一式)。**nonlinear_restart(2.51) を revert**(一律
  strength=0.18 へ。final +101% 有害)。全て探索オペレータ/摂動の変更でスコアリング不変=Δ×フル無関係・受理が最終採否=安全。
  これで repair 3種(day/staff/violations)が全て soft-aware に統一。GLS aging(2.50)は inert で維持。
- (2.59.0): **c41-aware day-repair**(設定画面3箇所の上限下限を全て研磨)。soft-aware repair は ①適切回数(apt) ②個人別の
  回数(staffRange low/high) を staffCountPenaltyAt で既に研磨済。3つ目 ③グループ別の回数(cons41 群レンジ)を追加: 
  destroyRepairDayAt の選択 marginal に「群の日次人数レンジ(cons41)」の delta を加味(`c41DayMarg`, grpCnt を当日分維持)。
  **`p.cons41` 空(golden_state 等)ならゼロ overhead で無害**。合成(c41あり)実測: R+staff+viol の over tier で final 47→41・
  AUC -18%→-20.6% の小幅改善・他 tier 中立。スコアリング不変=Δ×フル無関係・受理が最終採否=安全。
  → これで repair が設定3画面の上限下限(apt/個人range/群range)を全て研磨。
- (3.95.0, HARD=0非到達への配慮＝静的covU床の focus 除外): 「HARD=0 に到達しない過拘束 regime でも良い解を出すよう停滞脱出を
  改良」の指示に対し、まず nsp_bench で**真の infeasible 実験**(per-day sum(need)>S で covU 強制。合成 tight=1.0 は destroy-repair
  で hard=0 到達＝infeasible にならないため専用インスタンスを作成)を実施。結論=**動学は既に良好・パラメータ変更は不採用**:
  ①softFocusProb(床での soft 集中率, 現行 0.15)を 0.25/0.35/0.50 に上げても soft は中立〜悪化(0.35+ で +2〜3%)＝現行 0.15 が最適。
  ②targeted-perturb(infeasible 時に uniform 摂動でなく destroy-repair 摂動)は hf67HardRepair が既に摂動後の hard 床を復元済で
  **冗長**(bench 中立・同 hard 床)。③big-destroy(hard 停滞時の多日破壊)も**同 hard 床・soft は誤差内**。全変種が同一 hard 床に到達＝
  hard 最小化は既に解けており、床での soft も最適近傍。→ **測定が支持しないパラメータ/近傍変更はしない**(2.55/2.56 の教訓を再確認)。
  **採用した唯一の改善(原理ベース・3.74.0 と同方針)**: RSI focus の `avoid` に**静的 covU 床**を追加。covU は
  `structuralHardFloor`(有資格全員就けても埋まらない席=forcedCovU)が下限で最適化中に不変。covU がこの床に達したら以後 covU は
  下げられないと**静的に確定**するので、HF63 の動的検知(約3ラウンド無改善を要す)を待たず **round 0 から focus 除外**し、RSI の
  残ラウンドを解ける族(他HARD/SOFT)へ回す(旧: covU=床でも HF63 が flag するまで ~3 ラウンド無駄打ち)。`covU>=床` は恒真ゆえ
  `covU<=床`＝「これ以上不可」を正しく判定。**床=0(構造的不足なし＝HARD=0 到達可能な一般ケース)は no-op＝挙動不変**。focus 選択
  のみでスコアリング不変(keep-best=better() が結果担保)＝退化なし。covU が下限であることは定理(測定不要)。~~golden(構造的covU=2)で発火。~~
  **※[3.362.0 訂正] 現行 golden_state.json は `structuralHardFloor=0`（実測）＝この床は golden では発火しない（no-op）。当時の golden か記述が stale。機構自体は floor>0 のデータで正しく動作する。**
  ※bench は RSI focus/portfolio を模擬しないため 3.74.0 同様「実測でなく原理」で採否(no-op安全・低リスク・可逆)。
- (3.95.1, 12h見直し=敵対的レビューで判明した3.95.0の相互作用バグを修正): ①**[実バグ] N4早期脱出の常時武装化**: 3.95.0 の
  静的covU床が `avoid` を合流させたため、N4 早期脱出(`stagnantRounds>=2 && avoid.isNotEmpty()`)が構造的covU>0 のデータ
  (golden含む)で **round 0 から常時武装**し、hf63 が何も検知していなくても2停滞ラウンドで RSI が即終了＝「旧N4の厳密な部分
  集合」保証を破壊していた。`dynamicAvoid`(HF63検知のみ)を分離し N4 発火をそれでゲート(focus除外は合流 avoid のまま)。
  ②**[latent] DeltaEvaluator commit() の wStep 非対称**: 3.92.0 の isWork ハードニングが previewMove 側のみで、commit 側
  (L246)は旧式のまま＝範囲外セントネルの仮定下で preview/commit の wdCnt が乖離し得た。同一ガードへ統一(対称性回復)。
  ③covU床コメントの「covU>=床は恒真」を訂正(groupViol 混在時は下回り得るが `<=` 除外が正しい旨)。単位整合
  (breakdown["covU"]=covUCell amount 和 == structuralHardFloor 単位)・checkResultWorse 3節・relink予約・rangePen 90/45 は
  検証で健全を確認。
- (3.101.0, ログ再精査＝c3n focus の no-op 仮説を修正): 実機ログ再精査で「3実行×計10ラウンドの RSI c3n focus で c3n=1 が
  不変→HF63 が c3n を誤 infeasible 判定」を発見。原因=`rsiGenerateHypothesis` が c3n を hf67HardRepair へルーティング
  していたが、hf67 は群外修正(hf66DataHardening)・希望反映・被覆/下限充填のみで**禁止連続(c3n)には一切作用しない**＝
  c3n focus のラウンドが無変化仮説で空転していた。groupViol/pref は hf67 の作用対象なので維持し、**c3n のみ違反セルを
  直接再割当する destroyRepairViolations(else 分岐)へ変更**(c3n セルは violations マップに両端2セルで載る)。仮説は
  ラウンド単位 better() keep-best でゲート済＝退化なし・原理採否(bench は c3n 非実装=3.74.0 と同方針)。
  併せて違反詳細(buildViolationDebug)の aptLow/aptHigh 行に**目標(クランプ後)を併記**(旧: 回数/下限/上限のみで
  「回数4 下限4 上限5 がなぜ違反?」が読めなかった。目標5 が発火理由)。
- (3.102.0, 高速化・高精度化): 実機ログの定量的無駄を3点解消。①**[高精度化] 予算残の追加精製**: 後処理予約枠(budget/12,
  8〜25s)は後処理がフィックスポイント到達で大半未使用のまま返っていた(実機: 予約25s中 実使用0.45s＝約24.5s廃棄=予算の8%)。
  残5s以上＋違反残あり＋停滞早期終了でない場合、最終盤面起点の keep-best 追加精製(ALNS, runAlns入力比番兵つき=退化不能)へ
  回す(`ExtraRefine` ログ・TIME行に「追加精製」列追加)。②**[高速化] destroyRepairStaffAt の被覆事前計算**: 旧 O(T×K×S)
  ≈3100演算/呼の全職員走査を、一度数えて差分更新する O(S×T+T×K)≈620 へ(~5×減・挙動同一)。③**[高速化/零アロケ]
  op0-2 copy系パスのダブルバッファ化**: 毎反復の `cur.copy2D()` 新規確保(数百万回/実行のGC圧)を、スクラッチ盤面への
  arraycopy＋採用時スワップに置換(hf67経由の fixed!==cand 採用時はスクラッチ温存)。②③は挙動同一の純高速化＝同一seed
  同一結果、①は keep-best のみ＝スコアリング不変。
- (3.102.1, 自己監査で判明した①の回帰を修正): ExtraRefine の2回目 `optimize()` が入口で `lastAlternatives` を
  空にするため、本走行ポートフォリオの**「他の案」(最大3件)が ViewModel の captureAlternatives 前に消える**回帰。
  退避→`restoreAlternatives()`(新設・private set のため) で復元。
- (3.102.2, 敵対的監査で判明した latent 3件を修正): 並列監査(スクラッチswap/被覆事前計算/ExtraRefine/c3nルーティング/
  apt表示/hf63時計を敵対検証→**実バグ0**・挙動同一性を全項目で確認)の指摘。①ExtraRefine の上限を**後処理予約枠
  (postReserveMs, 8〜25s)でキャップ**(N4早期脱出等 stagnationFired 以外の早期復帰時に「節約した数分」を食い潰し
  電池/熱の早期終了方針と矛盾していた)。②ExtraRefine 採用時に「採用盤面の集計」行を追加(N3と同型。ログ末尾の
  UnifiedCheck/違反詳細は精製前盤面の診断のままで件数不一致に見えた)。③ViewModel HF63 の時計を callback の
  elapsed(フェーズ境界で巻き戻るローカル時計＝長フェーズ後に族が永久フラグ不能)から**startMs基準の単調壁時計**へ。
- (3.97.0, 実機ログ起因＝再最適化で550秒無駄の根本修正): 実機ログ(2026年8月データ・300s×2回)で「入力(HARD=1/195)を
  一度も上回れず内側番兵が2回とも入力へ復帰＝予算全部無駄」を確認。根本原因=**runV5 だけ退化防止番兵が無い**
  (runAlns=番兵あり・runRsi=入力比keep-best)。RSI++ は Phase1 Seed に runV5 を使うため、SA+hf67修復の劣化
  (実測195→229)が全チェーン(RSI→ALNS→Polish)へ伝播していた。①**runV5 に runAlns と同じ入力比番兵を追加**
  (better(base,result)なら入力を返す=入力が品質床・SA が良解を見つけたら素通し=多様化維持・スコアリング不変)。
  ②**ViewModel HF63 の粗サンプリング補正**(3.93.1と同クラス): 旧 `iters.toInt()`(累積数千万)では閾値5000が
  「約20ms無改善」相当＝違反>0の族ほぼ全てが即 infeasible 判定される9族ノイズ警告だった。経過時間ベース
  (elapsed/10=100単位/秒、5000=50秒無改善)へ補正し、**最終盤面で違反0の族は警告から除外**(破棄された探索トラック
  でしか違反が無かった covO/LimMax まで列挙され誤解を招いていた)。

- (3.150.0, 実機ログ定量起因＝高速化＋focus の状況適応3点): ユーザー指示「1.高速化 2.停滞脱出を状況に応じて賢く」。
  実機ログ2本(10/12シフト, 300s AUTO)の定量分析: **HF80 PostPolish が 45s枠を走り切り改善0（40.977s/40.988s の2例）**、
  **RSI round=4 が focus=groupViol(件数0)の空振り~21s**、**c3n focus ×3R 連発(計~63s)で件数不変**(HF63 恒久判定は約3R要)。
  ①**[E8] 件数0の族を focus しない**: `maxViolatedFamily` SOFT フォールバックの `bestCount=-1`→`0`（旧: 非avoid正件数族が
  order に無いと先頭 groupViol=0 が当選→hf67 のクリーン盤面 no-op 仮説で1R空振り）。該当なしは "total"=全違反セル hint の
  汎用修復(destroyRepairViolations は focus 非依存・空ヒントは randomAllowedCell)。②**[E9] 空振り focus の1R冷却**: 候補
  不採用＋focus族件数不変の「完全空振り」ラウンド直後だけ同 focus を回避(c3n→c1→c3n…交互へ多様化)。進展あり/族件数が
  減った(方向有望)場合は冷却しない。恒久除外は従来どおり HF63 のみ・N4 発火条件(dynamicAvoid)には混ぜない。
  ③**[E10] hf80PostPolish の停滞早期終了**: best が枠の1/5(下限3s)無改善で早期 return(ログに「停滞早期終了」併記)。
  keep-best＋入力比番兵で品質不変＝時間/電池のみ節約(2.65.0/2.67.0 と同方針)。3点とも focus/時間配分のみ＝スコアリング
  不変・keep-best 維持（bench は RSI focus/polish を模擬できないため 3.74.0/3.95.0 と同じ原理採否）。
- (3.106.0, 外部レビュー4件の修正): 提示されたコードレビュー(対象7b22a50)の4件を全て検証→実在確認→修正。
  ①**[P1] 休シフト削除で休日が勤務化**: removeShift が削除セルをハードコード0へ写像(休が index0 でない/休より
  前を消すと勤務へ化ける)。削除セル→**削除後の休index**へ追従＋**休シフト自体の削除は禁止**(no-op、ViewModel が
  理由メッセージ提示)。②**[P1] editStaff が skillIdx を0へ戻す**: `Staff(name,gi)` 再構築→ `copy(name,groupIdx)`
  で保持(名前編集だけで cons41s/c42s 評価が変わっていた)。③**[P1] 重複記号/氏名の解決不一致**: CSV照合の
  associateBy/後勝ちループ(9箇所)を **firstWinsMap(先勝ち=Problem.indexOfFirst と同一解決)** へ統一＋
  **検査8(重複定義の事前診断)**を新設(職員名(空白無視)/シフト/グループ/スキル群記号。read-only・非ブロック=
  既存データは開ける)。④**[P2] bg再開で計算条件が化ける**: 予算秒数/並列数を WorkManager inputData に永続化
  (旧: インメモリのみで kill 後は既定60s/4並列)＋kill後の復元は**途中最良スナップショット優先**(8秒毎退避済み。
  旧: 常に元入力から再スタート)。SessionRegressionTest に removeShift/editStaff の回帰テスト追加。

## ドッグフーディング改善（オブジェクト単位, 2.61→）
コンポーネント単位の評価を反映。「見える」は達成済、残りは「直せる」への接続が主レバー。
- (2.61.0, 進捗の見える化): `progressSummary(ui)`(MagiScheduleViews) = 改善N%(initSoft→bestSoft) ・ 残りM:SS ・ X回
  (hard残あり時は ⚠N)。state既存値(initSoft/bestSoft/iters/elapsedMs/budgetSec)のみ・読取専用・スコアリング不変。
  上位バー実行中バッジに改善量併記＋ホーム組立中カード/LiveScheduleCard/操作ログ先頭で共有。
- (2.62.0, 直せる導線): **集計セル(TallyCard)の違反セルをタップ→原因を数字で提示**(職員別=現在/下限/上限/目標、
  日別=現在/必要/適正)＋「直し方を探す」で分析タブの FixSuggester へ遷移(onFix: staffはfocus、日別はnull=全体探索)。
  しきい値は ViewModel `staffCellLimits(i,k)`(rangeLo/rangeHi/apt)・`needCellLimits(k,j)`(need1/need2,use2)で Problem から取得。
  違反内訳カード(BreakdownCard)は既にチップ→場所→onFocusStaff→findFixSuggestions が機能済(2.62で追認)。
  注: TallyCard は gridUi(読取=resultSchedule)の count を使うため、現在値と違反マップのズレ既知事項は継続(backlog#1)。
- 未(ユーザー選択外): 軽い視覚調整(集計の左列固定/説明文ⓘ折りたたみ/やめる・前次ボタン縮小/ロゴ実行中アイコン化)。
- (2.64.0, 全21画面検証→単位・用語の明確化): 並列検証で全画面を engine 意味論と突合(ロジック誤り0・NUL/死にコード0)。実害は表記に集中し、表記のみ修正(ロジック不変):
  A) **cons41/cons41s を「回数」→「1日の人数」**(ConstraintsCard/SkillConstraintsCard の題・行「[l〜u]人/1日」・Addダイアログ題)。engine は `for j` 日次 `z<l||z>u`=1日人数。
  B) need を「必要数1/2」→**「最低人数」「上限人数(2パターン時)」**(Ws1Card 表示/ダイアログ・NeedDayCard 表示/ステッパー)。engine: need1=covU下限, need2=covO上限(use2時)。
  C) CoverageDiagnosisCard「不足N件」→**「不足N人」**(totalShortfall は人数)。
  非対応(検証で誤りでないと確認): SettingIssues の RANGE「…の回数/下限を…」DEMAND「…人に下げる」は既に単位適切=V6SanityPort 不変。
  UX摩擦(希望の日範囲選択・群×シフト表の横スク・満足度尺度・スキル解除・ワーカー用語隠し・集計左列固定)は構造変更を伴い backlog。
- (2.66.0, 群×シフト表の見やすさ): Ws1Card「担当できるシフト(群×シフト)」「適切回数」が横スクロール(12シフトで画面外)だった。
  各群を Row(horizontalScroll) → 群名を行頭＋**FlowRow でチップ折り返し**に。横スクロール不要・群名常時可視。未使用 import 除去。
- (2.68.0, 希望シフトの日付直接入力): 希望追加ダイアログの日が ±ステッパーのみ(1→30で多タップ・スタッフ▼/日± の操作系不統一)
  →直接入力テキストフィールド(1〜maxDay・数字キーボード)＋± に。dayText:String 化で空入力も安全。NeedDayEditor と操作系統一。
- (2.69.0, 計算方式の日本語ラベル): SettingsCard が `${ui.v6Algorithm}`/`alg.name`=生enum(AUTO/V5/ALNS/RSI/RSI_PLUS/PORTFOLIO)を
  表示していた。`v6AlgorithmLabel()` を新設し おまかせ/高速/破壊再構築/違反集中/違反集中＋/並列(複数案) へ。表示のみ・ロジック不変。
- (2.70.0, スキル割当の解除): SkillGroupEditor の職員スキル▼に **「(なし)」(skillIdx=-1)** を追加(どのスキル群にも所属させない)。
  engine は cons41s/cons42s で `ssk[i]==groupIdx(≥0)` ＝ -1 は常に偽=未所属で安全。永続化は素の int 往復(optInt/put・クランプ無)、
  群削除リインデックス(`k>g`)も -1 は不変で安全。既定 skillIdx=0 は変更せず追加のみ。表示は skillIdx<0 で「(なし)」。
  検証で判明し**既に対応済みだった backlog**: ①集計の左列固定=職員名カラムは horizontalScroll の外で既に固定 ②コパイロット文言=
  `topHardFamilyJp`→`hardFamilyJp` で既に日本語(生コード露出なし)。**非対応(意図的)**: 満足度尺度の式変更=表示専用だが業務判断要・保留 /
  EmptyState ボタン順=既存ユーザーは「データを開く」主操作が妥当 / Bottleneck/ようすの説明追加=「説明文は読まれない(④)」原則に反するため見送り。
- (3.75.0, E7 違反フィルタ = 種別トリアージ): 勤務表タブに**制約種別フィルタ**を新設（grilling で要件確定）。18族を作成者の語彙で
  **コース6分類**(人員 covU/covO・希望 pref・連勤 c3系・回数 low/high/apt/c2・群ルール groupViol/c41/c42/c41s/c42s・窓 c1)に束ね、
  **勤務表タブ全面**(グリッドセル/日ヘッダ不足N/Tally職員=回数/Tally日=人員/カレンダーセル)を**1つの共有フィルタ**で絞る。
  複数トグル・初期全ON(引き算)・**件数付きチップ**(breakdown 族合計)でそのまま「まずどれを潰すか」の種別トリアージに。
  実装: `MagiScheduleViews` に `VioBucket`/`vioBuckets`/`familyOfVioClass`(aptLow/aptHigh→apt)/`vioVisible`/`ViolationFilterBar`。
  状態は `MagiApp` 勤務表タブに `rememberSaveable` の Int bitmask(回転/復元で保持)。ScheduleGrid/StaffCalendarCard/TallyCard/
  MagiFlatGrid に `vioEnabled: Set<String>=allVioBucketKeys` を追加し各違反読取を `vioVisible` でゲート。**表示のみ・スコアリング
  不変**(違反自体は不変、表示するかだけ制御)。旧 ScheduleGrid の中途半端な per-family フィルタ(hiddenVio・grid未反映・grid限定)を
  置換。公平/曜日は場所無しでバケツ対象外。E7②(カレンダーへ need/count 追加)は per-staff 面と不整合のためスコープ外(業務選択)。
- (2.67.0, 早期脱出方針): 実機ログで停滞検知が予算上限とほぼ同時(301s)発火＝時間が節約できていなかった。停滞ウォッチドッグ
  (V6FinalPort)の許容を短縮: `minRunMs budget/5→/6(上限60→45s)`, `stallMs budget/4→/6(300s予算 75→50s)`, `stallHardMs budget/6→/8(50→37.5s)`。
  globalBest は生スコア管理＋最後の改善時刻でタイマリセット(フェーズ遷移でもリセット)のため**早期終了でも品質は不変**(無改善時に早く返すだけ)。
- (2.65.0, HF66 残予算ガード): 実機ログで後処理 HF66(職員内再配分)が applied=0 でも 12,999ms を消費し予算超過(301s>300s)→
  「後処理を短縮(残りパス打ち切り)」を誘発していた。原因=HF66 は「1手ごとに全候補をフル check」する高コストパスで
  `shouldStop` を手ごとにしか見ず、締切後も現在手のスキャンを走り切る。対策: ①`runPostOptimization(deadlineMs=hardDeadlineMs)`
  を新設し、HF66 専用上限 `hf66Cap = min(残予算/2, 6s)` を渡す(残り半分を後段研磨へ確保) ②HF66 内に `outOfTime()=shouldStop()||
  now>=deadlineMs` を導入し**内側スキャン(`scan@`ラベル)でも締切確認**して暴走を即中断。keep-best(isBetter採用)のため早期中断
  でも解は退化せず=スコアリング不変・安全。他パスの予算超過打ち切りを防ぐのが狙い。

## 後処理研磨の族カバレッジ（進行中）
後処理(V6HotfixPasses.runPostOptimization)は c1/c3系・low/high・apt・fair は研磨するが、c2・c41/c42・**c41s/c42s**・covO は
専用研磨が無く「評価のみで研磨されない」取りこぼしだった（weightedScoreには算入済）。
- (2.44.0): **c41s/c42s 専用研磨**を新設しフィックスポイント巡回に組込。c41(s) は markNeed で needViolations 側に出て
  群情報が失われ職員セルから拾えないため、アンカーは `Problem.cons41(s)/cons42(s)` から直接算出（違反群の職員を起点）。
  同日2者スワップ(被覆/HARD不変)・`isBetter`採否(keep-best=退化なし)。
- (2.45.0, 完了): 上記を `applyGroupRangePairPolish(skill: Boolean)` に**汎用化(DRY)**し、**c41/c42(通常群)も研磨**（skill=false=sgrp,
  skill=true=ssk）。フィックスポイント巡回に群範囲(c41/c42)＋スキル群(c41s/c42s)の2呼び出しを追加。SoftPolishVerify ログに
  c41/c42/c41s/c42s 増減と採用数(群:n / skill:n)を表示。サンプル最大残違反 c41=124 に直接効く（過拘束分は keep-best で無害）。
- (検証で判明・重要): 同日スワップで直せる単日族(c2/c41/c42/c41s/c42s)は**既存 CyclicSwap が isBetter で total を最小化する過程で既に研磨済**
  （isBetter=hard→total→weighted、total に全 soft 込み）。よって 2.44/2.45 の同日スワップ専用パスは CyclicSwap の部分集合＝
  ほぼ冗長（keep-best で無害・診断ログ充実の効果はあり）。c2 も同様で専用パス不要。covO は HF66(職員内再配分=被覆変更)が
  isBetter で既に扱う。**残る本当の取りこぼしは探索側の focus 漏れだった**。
- (2.46.0, 完了): 探索の `maxViolatedFamily` の order に **c41s/c42s を登録**（従来 c41/c42 のみで RSI が一度もスキル群を
  focus しなかった）。c41s は c41 と同型(1日人数)なので `rsiGenerateHypothesis` の destroyRepairDay 分岐へ、c42s は else
  (destroyRepairViolations)。後処理スワップでは届かない destroy-repair でスキル群を直接攻める。
- (3.74.0, D1/A1 完了): `maxViolatedFamily` を **純・件数最大 → 「解ける HARD 族を件数に関わらず SOFT より先に focus」** へ。
  旧実装は単一の c3n=1 が c1=118 等の高頻度 SOFT に埋もれ RSI が**一度も HARD を狙わない**失敗があった（停滞解析の根本原因）。
  first-pass=order 上の HARD 族(groupViol/covU/pref/c3n)で avoid 外かつ件数>0 の先頭を返す／無ければ従来の非avoid件数最大。
  **hard=0 のとき no-op＝全 soft の一般ケースは不変**。avoid(HF63=構造的に充足困難)の HARD は除外し無駄打ちを避ける
  (解けない HARD は 3.69 hardFloor と同方針で SOFT 研磨へ回す)。目的関数 better() の辞書式(hard<<total<<weighted)に focus を
  整合させる**スコアリング不変**の変更（Evaluator/Delta/Checker/重み 不変）。※探索動学の A/B 原則(nsp_bench)に対しては、
  bench が focus 選択を模擬できない（c3n/c1 制約非実装）ため**実測でなく原理**で採否：hard=0 で不変・HF63 で無駄打ち回避・
  1関数で可逆、という限定的・低リスク設計に依拠。単一呼出(V6NativeOptimizer:607)・テスト非依存。
- (3.94.0, ソフト研磨の網羅的計測 → 候補生成の重み整合): 「ソフト研磨を50%以上に改良」の指示に対し、`tools/nsp_bench.py`
  で **6レバーを網羅 A/B**(golden_state 実データ 24 seeds ＋ 合成 over-constrained)。結論=**現行のソフト研磨は既に構造的下限**:
  - **現行(smart repair day+staff+viol, 2.57-2.59)が既に repair-day 基準比 −54.5%**(golden 24seed 頑健)＝**50%目標は現行実装で達成済**。
  - golden の残差 12.6 は **low/high(重90/45)=0 まで消え、apt(8.8)+covO(3.8, 重み~1)のみ＝構造的**(供給<需要・群apt目標の競合)。
  - 試した in-loop 追加レバーは**すべて不整合か有害**: multiday=golden 微改善だが over-constrained +28%悪化 / worst-removal(日/職員)=
    +162〜375%悪化(過集中で floor を撹拌) / covo-aware=完全中立(不発) / dr比率up(0.35/0.50)=大幅悪化 / in-loop swap=golden +96〜356%悪化
    (貪欲交換が SA を局所最適に固着)。→ **被覆保存スワップは in-loop でなく後処理**が正しい(既存 CyclicSwap が担当済)。
    2.55/2.56/2.57 の「脱出/近傍ヒューリスティクスは中立or有害」教訓を再確認。
  - **採用した唯一の安全・原理的改善**: `applyDayAssignmentPolish` の Hungarian 候補生成 proxy `rangePen` を **3/3 → 90/45**
    (apt=1 は不変)へ整合し目的関数(Evaluator/staffCountPenaltyAt)と一致。keep-best(isBetter@UnifiedViolationChecker)採否のため
    **退化不能=スコアリング不変**。bench では**中立**(単純化 bench の SA が low/high を研磨前に0化するため proxy 差が顕在化しない=
    bench の限界)だが、実機は c1/c3/pref 等の競合族が多く low/high 残差が研磨段へ到達し得る→ 真の目的への proxy 整合が効く見込み。
    HF77 非該当(スコア重みでなく探索内部の候補生成 proxy)。実測ハーネス: `/tmp/.../soft_polish_*.py`, `dayassign_weights.py`(scratchpad)。

## 回数設定UIの改善（進行中）
回数設定が複数画面に分散し紛らわしい（#1必要人数/#3グループ別c41(=1日の人数)/#5適切回数apt/#6個人別low-high/#7合計c2/#8連続窓c1）ため、
「⬇️1日の人数 / ➡️1人が月に何回」の軸でレイアウト統合する方針（ユーザー承認済み）。段階実装:
- 第1段(2.42.0, 完了): `StaffRangeCard`(個人別の回数)に**現状回数「今◯」＋過不足色**(🔴vio-low/aptLow ・ 🟠vio-high/aptHigh)を追加。
  説明文を「1か月に何回」に明確化。既存UiStateのみ使用(低リスク)。
- 第2段(2.43.0, 完了): 個人別カードに**適切回数(apt)の実効目標を併記**。ViewModel に `staffCountRules()`(=`CountRuleView`)を新設し、
  staffRange または apt が効く職員×シフトを統合一覧。チップ表記「Dﾃ 2–5 目標3 ・今3」、クランプ時「目標10→2」。
  apt のみ(個人別上下限なし)のセルも行に出し(× は出さない=群目標は群単位)、タップで個人別上下限を追加可能。`Problem(st).apt` を実効値の source。
- 第3段(予定・大): 軸ハブ画面で need/c41(1日)と apt/low-high/c2/c1(月) を分離。**`ConstraintsCard` が c41(1日)＋c1/c2(月) を束ねているため、
  カード分割を伴う大規模リファクタ**。c41 の「◯回」表記を「◯人(1日)」へ。要・別途着手。

## スキル / 作業の進め方（末尾にあった版数付き記録 3.145.0〜3.149.0）

- (3.145.0, シンプルデザイン融合①=シフト集計の凡例): 実機スクショのシンプルな集計画面を融合する第1歩。
  TallyLegend を **1行凡例「▼不足 ▲超過 — 対象外」** に統一（旧: 呼出側が「回数が下限/目標未満」等の
  長文ラベルを渡していた4引数版）。タブ名(職員別/日別)が不足/超過の意味を文脈で示すため長文を撤去し、
  詳細はセルタップで出す方針。色見本＋▼▲＝色覚二重符号化は維持。呼出2箇所(職員別/日別)を2引数へ。
  表示のみ・スコアリング不変。**未対応(次段候補)**: 集計期間の読取ラベル・操作ヒントのアイコン化
  （期間バーの<>ナビは月スナップショットモデルと衝突するため read-only ラベルに留める想定）。
- (3.146.0, シンプルデザイン融合②=集計期間ラベル＋操作ヒントのアイコン化): TallyCard に read-only の
  **「集計期間 YYYY年M月D日(曜)〜…」** ラベルを追加（startDate〜startDate+(days-1)・曜日付き・パース失敗時は非表示）。
  スクショの期間バーの <> ナビは月スナップショットモデル（集計は常に現在の全期間）と衝突するため付けず、
  読取ラベルに留める。操作ヒントをアイコン化: 職員別「ⓘ タップで内訳と直し方」／日別「ⓘ タップで内訳と直し方
  ・👆 左右スワイプで他の日」。表示のみ・スコアリング不変。
- (3.147.0, 設定ミス案内から VBA ワークシート符号(wsN)を除去): 実機に出る設定ミス/診断ガイダンスの文言に
  VBA 由来の内部符号「(ws1)〜(ws5)」が22文字列・29箇所で露出していた（例「個人下限を下げる(ws1)か、必要人数を
  増やしてください(ws2)」）。各文はすでに平文で対象を名指し（設定・必要人数・希望・連続パターン設定・個人上下限・
  担当範囲）しているため、符号は冗長な相互参照＝**削除で誤誘導リスクゼロ**。「(ws1/担当範囲)」のみ意味語を残し
  「(担当範囲)」に。対象=V6SanityPort/V6HotfixPasses/MagiDashboardCards/StaffRangeEditor の**文字列リテラルのみ**
  （コメント内の wsN=内部参照は不変。テスト依存なし=grep 確認済み）。paren balance は HEAD と同一（符号は括弧対で
  除去＝中立）。文字列のみ・スコアリング不変。operator_ux「英字符号を画面に出さない」に整合。
- (3.148.0, covO 重みを 0.5→1.0 に統一＝目的関数の最後の乖離を解消): 実機ログ(83cc183a, 12シフト)の算術照合で
  **評価器 soft=1912 vs チェッカー weightedScore 再構成=1903（差9=covO×(1.0−0.5)）**を発見し、コード照合で確定:
  最適化器(`Evaluator.kt:155`/`DeltaEvaluator.kt:87`/`magi_native.cpp` fullEval＋slice)は covO を **amount×1.0** で加算
  していたのに、チェッカー(`MirrorCore.kt:67` weightedScore)だけ **×0.5**。`Evaluator.kt:11` のコメント「covO は checker と
  同重み」が実装(×1.0)と矛盾＝HF77 の「コメント≠実装」を捕捉。原因は soft が Long で 0.5 を表現できず 2.28.0 統一時に
  整数×1.0 で入ったまま残存。keep-best `better()`(`V6NativeOptimizer:1269`)は既にチェッカー(0.5)基準＝**最終選択は正しく
  ランク済**で、ズレは SA 探索コンパスが最下位族を2倍罰する内部のみ＝実効微小。**修正方針(ユーザー選択)**: 真0.5化は
  スコア全体×2＋SA温度/GLS lambda/Lam スケール等の**番兵の無い動学定数**を全連動させる大改修（native 再検証必須・
  取り漏らしで静かに劣化）になるため回避し、**「最適化器を正」として チェッカーを 1.0 に統一**（`MirrorCore:67` の1行、
  最適化器/Delta/C++ は既に 1.0＝変更不要）。これで weightedScore が評価器と一致(1903→1912)し「族寄与==weightedScore寄与」
  の不変条件が復活。副次: `V6WebCompat.severityFromVioKey` の covO を **INFO→WARN**（重み1.0=c2/c41 と同格の実違反、
  集計/グリッドで既に橙=過剰表示のため整合。fair/weekly は「整え・常時非ゼロ」で INFO 維持＝3.85.0 の重大度=重み整合原則）。
  covO の内訳チップ件数(amount)は不変、weightedScore と重大度色のみ変化。C++/native は変更なし＝parity 影響なし。
- (3.149.0, 曜日の偏り/公平化の「場所」表示): 実機指摘「曜日の偏りなどが表示されません」。原因=fair/weekly は
  セル単位でなく**職員(weekly)・群×シフト(fair)単位の偏り**のためチェッカーが `inc()`(件数)のみで `mark()`(セル印)を
  呼ばず、`breakdownLocations` の violationCells 検索が常に0箇所＝「場所情報がありません」だった（かつ意図的に
  グリッドへは出していない＝62セルを枠表示すると 3.99.0 の飽和が再発）。**チェッカー(source of truth)が職員単位の偏り箇所を
  出力する方式**で解決（UI 再計算は restIdx/群/bucket が UiState に無く covO と同型のドリフトを生むため不採用）:
  `ViolationReport.distLocations`("weekly"→[[i,dev]] / "fair"→[[i,k,dev]]、dev降順) を新設し fair/weekly ループで
  偏り職員を収集→UiState→`breakdownLocations` が「職員（曜日の偏り N）」「職員 「シフト」（偏り N）」で整形・タップで
  当該職員へフォーカス。**内訳パネルのみに表示（グリッド不変＝飽和回避）・スコアリング不変**。配線=MirrorCore→
  ViolationReport→UiState→makeUi→breakdownLocations（表示専用フィールド追加、既存構築は全て named 引数＋デフォルトで非破壊）。

# CLAUDE.md から移設した恒久の事実（3.505.9）

## 目的関数の統一（完了。乖離と解消の表）
**最適化器（Evaluator/Delta）とUI/提案（UnifiedViolationChecker）が乖離していた目的関数を統一した。**
原則: **チェッカーを source of truth とし、より正確なモデルへ両者を寄せる**。重みは線形集約点で適用し
Δ×フル整合を維持（`soft = sc*W + ..., sc += dC ⇒ soft_new = soft_old + dC*W`）。

| 乖離 | 解消 | コミット |
|---|---|---|
| (a) covO 最適化器で無罰則 | 最適化器 soft に追加 | 2.28.0 |
| (a') covO 最適化器×1.0 vs チェッカー weightedScore×0.5 の factor-2 乖離 | チェッカーも×1.0 に統一（最適化器を正） | 2026-07-13 |
| (b) range(low/high) 最適化器が表示HARD | 最適化器 soft化＋重み90/45 | 2.28.0 |
| (c) c3/c3m 単一シフト連: 窓 vs run-deficit（方向相違） | チェッカーも run-deficit化 | 2.31.0 |
| (c) c3/c3m 複数シフト連: +D vs +1, フラット vs 重み | #fire＋重み3/2/12 | 2.32.0 |
| c3n/c3mn | forbidden は両側窓マッチ#fire・c3mn×12 で既に一致 | (2.32.0で副次的) |
| (a) c1: canDoガード無/+d1/フラット | canDoガード＋#fire＋重み4 | 2.35.0 |
| apt(適切回数): 目的関数/チェッカー双方に未統合（事実上死に機能） | 3者にL1偏差×1で統合・双方向目標・違反表示 | 2.36.0 |
| fair(グループ内公平化): 後処理polishのみ＝目的関数外の「整え」 | 3者にL1偏差×1で統合（群×シフトの round(平均) 偏差） | 2.37.0 |
| c41s/c42s: 違反は研磨・検出済みだが内訳UIに列挙漏れ | breakdownLabels/BreakdownGroupに追加し表示 | 2.37.0 |
| weekly(7日周期(曜日)平準化): 後処理polish(分散)のみ＝目的関数外の「整え」 | 3者にL1偏差×1で統合（職員×曜日の round(勤務日/7) 偏差、`weeklyDevOfBucket`共通ソース） | 3.72.0 |

検証はすべて Python で「最適化器の族寄与 == チェッカー weightedScore 寄与」「soft<<1e6（hardゲート安全）」
「Δ==フル」を確認済み。

## ユーザー向け機能（FixSuggester・TallyCard）
- **FixSuggester**（`V6SwapSuggester.kt`）: 7種の手（単一変更/同日交換/複数変更/連鎖/再最適化窓/3人交換/別日交換）を
  deadline内で探索、(deltaHard, deltaTotal, deltaWeighted) でランク、kind+ops署名で重複排除。
  UI: `FixSuggestionCard`（kindチップ＋差分＋適用ボタン）、`BreakdownCard` から「直し方を探す」。
- **TallyCard**（`MagiScheduleViews.kt`、タブ1の勤務表表下）: 職員別（職員×シフト回数）と日別（シフト×日 人数）。
  **違反ハイライト**: 職員別=countViolations(vio-low赤/vio-high橙)、日別=needViolations(vio-covU赤/vio-covO橙)。
  ~~注: 読取モードで `gridUi.schedule=resultSchedule` に差し替わるため、編集後に読取へ切替えた場合のみ
  集計値と違反マップがズレ得る~~ **→ 3.96.0 で解消（backlog#1 完了）**: UiState に result専用マップ
  (`resultViolationCells/resultNeedViolations/resultCountViolations`, null=未計算→現行へフォールバック) を追加。
  `makeUi` が `schedule.contentDeepEquals(resultSchedule)` の検査時に report から一元充填（resultSchedule 更新サイトは
  全て makeUi(schedule==result, 対応report) を通ることを確認済）。`commitEditingToResult` は現行マップを引き継ぎ
  （refreshCheck 進行中でも完了時 makeUi が自己修復）。読取モードの gridUi は schedule と3マップを同時に差し替え。
  表示のみ・スコアリング不変。

## ドッグフーディング・後処理研磨・回数設定UI（恒久の事実）

- 同日2者スワップで直せる単日族（c2/c41/c42/c41s/c42s）は **CyclicSwap が isBetter で既に研磨**＝専用パスは冗長（2.49.0 で撤去）。
  探索側の focus 順に c41s/c42s を登録済み（2.46.0）。ソフト研磨は 3.94.0 の網羅 A/B で**構造的下限**と確認済み
  （in-loop の追加レバーはすべて不整合か有害。候補生成 proxy `rangePen` は 90/45 で目的関数と整合）。
- 回数設定UI: 第1〜2段（現状回数「今◯」＋apt 実効目標の併記、`staffCountRules()`）は完了。**第3段（need/c41 と apt/low-high/c2/c1 の
  軸ハブ分離）は未着手＝`ConstraintsCard` 分割を伴う大改修で別途着手**。
- 表示・導線の版数付き記録（2.61〜3.149）は `docs/history/topics.md`「ドッグフーディング改善」「後処理研磨の族カバレッジ」。

## ネイティブ加速（恒久の事実の要約）

- **Kotlin が正、C++（`app/src/main/cpp/magi_native.cpp`）は同値の高速版**。評価器・SA PhaseA・LAHC PhaseB・ALNS チャンク・
  修復群（hf67 含む）・HF80 研磨チャンクがネイティブ。RSI 制御層・V6LateOperators・後処理チェーンはチェッカー依存の軽量層で
  **意図的に Kotlin のまま**（移植しないと確定＝3.153.0）。
- **2層番兵**: ①チャンク末尾の C++ 自己整合 ②best 更新チャンクを Kotlin `Evaluator.fullEval` と Long== 照合。どちらか発火で
  `NativeGate` が閉じそのプロセスは Kotlin へ退化（クラッシュさせない）。番兵の全体計算は正しさの根幹＝削らない（3.154.0）。
- 設定トグル「ネイティブ加速（C++）」（既定ON）と「照合トグル」（既定ON）。`.so` ロード失敗時は `NativeBridge.available=false`
  で全経路 Kotlin。JVM テストは常に Kotlin 経路。
- **パリティは CI が守る**: `.github/workflows/native-parity.yml`（`tools/native/host_parity_bench.cpp`＋言語跨ぎ期待値
  `golden_eval_expected.txt`、実データ3形状）。`Evaluator.kt`/`MirrorCore.kt`/`DeltaEvaluator.kt` を変えたら
  `magi_native.cpp` も同じコミットで（バックログ #6）。
- 経緯（Stage1〜13・第2期・第3期・3.135〜3.077 の版数付き記録）は `docs/history/topics.md`「ネイティブ加速」。

## 停滞脱出（恒久の事実の要約）

- **探索動学の変更は `tools/nsp_bench.py` で A/B してから採否**。指標は AUC（速度）でなく **final（最終品質）**（実データで両者は
  乖離した）。便益が測れない／負なら入れない。
- 採用済み: soft-aware destroy-repair 3種（day/staff/violations, `staffCountPenaltyAt`＝Evaluator と同一式）＋c41-aware（2.57〜2.59）、
  RSI focus は「解ける HARD 族を先に」（3.74.0）＋静的 covU 床の除外（3.95.0、N4 早期脱出は `dynamicAvoid` のみでゲート）＋
  c3n は `destroyRepairViolations` へ（3.101.0）＋件数0の族を focus しない／空振り focus の1R冷却／HF80 の停滞早期終了（3.150.0）、
  `runV5` の入力比番兵（3.97.0）、ExtraRefine は後処理予約枠でキャップ（3.102.x）。
- **測って否決（再提案は計測つきで）**: 戦略的振動、nonlinear restart、GLS のパラメータスイープ、targeted-perturb、big-destroy、
  softFocusProb の変更。GLS aging は中立で温存。
- 経緯と数値は `docs/history/topics.md`「停滞脱出の改善」。

