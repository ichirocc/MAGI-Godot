# MAGI Native (Kotlin/Android)

MAGI shift optimizer, native Android port.

This project contains an Android app that ports the MAGI web shift optimizer engine into native Kotlin.
The UI is built with Godot 4.5.1 (`godot/`) and a Kotlin bridge (`app/src/main/java/com/magi/app/godot/`);
the former Jetpack Compose UI was removed in 3.549.0.

## ドキュメント目次（AI はここから読む）

> 設計・仕様は下記の Markdown に分かれています。**まずこの表で当たりをつけて**から目的の文書を読んでください。事実が変わりやすい順に独立させており、とくに `business-logic.md` / `data-models.md` を最新に保つことでハルシネーションの大半を抑えます。

| ファイル | 何が書いてあるか |
|---|---|
| [`docs/overview.md`](./docs/overview.md) | 機能の目的と主要機能の粗い要約（まず全体像） |
| [`docs/requirements.md`](./docs/requirements.md) | 要件定義。ユーザーストーリーと受け入れ条件（なぜ存在するか） |
| [`docs/design.md`](./docs/design.md) | 設計。主要インタフェースと処理フロー（どう作られているか） |
| [`docs/architecture.md`](./docs/architecture.md) | レイヤー構成・依存関係・どのファイルが何を担当するか（地図） |
| [`docs/business-logic.md`](./docs/business-logic.md) | 判定条件・計算（重み19種）・エラー方針（**業務ルールの正解**） |
| [`docs/data-models.md`](./docs/data-models.md) | エンティティ定義・項目名と型（**存在しない項目を創作しない**） |
| [`docs/screen_spec.md`](./docs/screen_spec.md) | 画面仕様（挙動・実寸・違反/希望の表示） |
| [`docs/magi_design_system.md`](./docs/magi_design_system.md) | デザイン基盤（色/余白/タイポ/部品） |
| [`docs/v6_engine_native_port.md`](./docs/v6_engine_native_port.md) | エンジン（v6）の移植 |
| [`docs/algorithm_portfolio.md`](./docs/algorithm_portfolio.md) | 探索・研磨の**入口と責務の台帳**（どの手がどこで走るか・横断機構・既定OFF・廃止済み・未実施の提案） |
| [`docs/environment.md`](./docs/environment.md) | **環境固有の手順**（ホスト JVM ビルド・CI 監視・probe・プラグイン・Serena。CLAUDE.md から移設） |
| [`docs/automation.md`](./docs/automation.md) | **自動化方針と実装の対応**（済／部分／未、採用ゲート・探索段階・必須修正 10 項目） |
| [`docs/backlog.md`](./docs/backlog.md) | **バックログ / 未対応**（完了項目は打消し線で保持。CLAUDE.md から移設） |
| [`docs/claudemd-tune-proposal.md`](./docs/claudemd-tune-proposal.md) | **CLAUDE.md 見直し提案**（棚卸し・削除/書き直し/移動/残す/矛盾の表と全文案。承認待ち） |
| [`docs/sudo_model.md`](./docs/sudo_model.md) | **SUDO モデル**（3.505.7 で再照合）（S 関連図／U ユースケース／D ドメイン／O オブジェクト。実装から起こした全体像。D の不変条件と O の実測値つき） |
| [`docs/history/`](./docs/history/) | **作業記録の本文**（版数付き約360節・版数でバケツ分け: `2.x` / `3.0xx` / `3.1xx` / `3.2xx` / `3.3xx` / `3.4xx`）。見出し一覧は [`INDEX.md`](./docs/history/INDEX.md)、話題別の叙述（ネイティブ加速・停滞脱出・ドッグフーディング等）は [`topics.md`](./docs/history/topics.md)（3.497.3 で `CLAUDE.md` 本体から分離）。`grep -n 'キーワード' docs/history/INDEX.md` で当たりを付けてから版数で引く。毎ターン自動では読み込まれない＝過去に測って否決した案・同型のバグ・決定記録を再発させないため、同じ領域を触る前に INDEX.md を必ず確認する |
| [`docs/screen_inventory_textart.md`](./docs/screen_inventory_textart.md) | **画面棚卸し＋テキストアートのドッグフーディング検証**（3.482.0 時点。タブ5／画面12／モーダル30／Activity2 の集計と、全画面の再現図＋所見20件の優先表。UI を触る前の現状確認と、次の改善候補の一次ソース） |
| [`docs/lessons.md`](./docs/lessons.md) | **教訓メモ**（修正した点↔機能した点・作る前にやめた判断・測り方・検証手段の穴。新規作成せず更新する） |
| [`docs/godot-ui-migration.md`](./docs/godot-ui-migration.md) | **Godot UI移行の記録**（`godot/`配下のGodot 4.5.1 UIレイヤーとKotlinブリッジ`app/…/godot/`の実装範囲・許可リスト・トークン方式。第6段で Compose UI を削除し Godot 版に一本化。**未検証事項の一覧を含む＝動作確認の合格証ではない**） |
| [`CLAUDE.md`](./CLAUDE.md) | 引き継ぎ・直近の状態・作業の進め方（grilling 等） |
| [`docs/changelog.md`](./docs/changelog.md) | **版ごと（3.xxx.0単位）の詳細な変更履歴アーカイブ**（`CLAUDE.md`から切り出し。個別の修正内容・調査記録・実測値を確認したい時だけ検索して読む。通常のセッション開始時には注入されない） |

**最終更新**：2026-09-15（3.550.0＝Godot UI移行・第7段: 実機「起動直後にクラッシュ」報告への起動診断。
`MagiStartupGuard` が起動段階（activity→viewmodel→engine→ui）と未捕捉例外を filesDir に記録し、前回 ui に到達していなければ
`MagiGodotActivity` が Godot を起動する前に診断画面（到達段階・例外・Godot ログ末尾・OpenGL 互換レンダラー切替）を出す。
PCK は assets から filesDir へ複製し絶対パスで `--main-pack` に渡す（失敗時のみ `res://`）。`project.godot` でファイルログ有効化、
`pck` を非圧縮同梱。CI は `tools/check_apk_native_libs.py` で `libc++_shared.so` の同梱と `.so` の非圧縮・16KiB 整列を検査。
原因は未特定＝診断画面のスクリーンショットで絞る。versionName `3.550.0-godot-ui`）

**最終更新**：2026-09-15（3.549.0＝Godot UI移行・第6段: Compose UI を削除し Godot 版に一本化（ユーザー決定）。
`MainActivity`・`ui/` の Composable 17 ファイル・`work/BubbleActivity`・`BubbleSupport` を削除、`MagiGodotActivity` を
`src/main` へ移し Manifest の唯一の LAUNCHER に、`magiGodot` フラグと Compose 依存/プラグインを撤去（`-PgodotExecutable` は常時必須）。
背景最適化の通知は残し、タップ先を `MagiGodotActivity` に（会話バブルは廃止、進捗は前景通知を同 ID で更新）。
CI は Godot UI Check を主に（`v6-engine-check.yml`・`release-build.yml` 削除）、`design_lint.py` の Compose 系検査は対象なし。
`ui/` に残るのは `MagiUiState`・`MagiViewModel*`・純ロジック 5 本。ホストテスト 775 件成功。versionName `3.549.0-godot-ui`。
Android 実ビルドは CI で確認）

**最終更新**：2026-09-15（Godot UI移行・第5段: `magiGodot=true` の Godot 版 APK ビルドが CI（Godot UI Check run #9）で
初めて成功＝Godot AAR に対する `MagiGodotActivity` のコンパイル、`magi.pck` 生成・同梱、Manifest 合流、起動構成 assert を通過。
あわせて外部レビュー対応 A: `run_op` 失敗時の snapshot 再描画と status 表示、設定画面の state 同期＋並列数/予算/方式 UI、
分析の代替案採用・候補 index 指定、ホームの実行中 disabled と JSON 導線、勤務表の形状ガード、cons42 入力順統一、
BBCode エスケープ、`MagiBridge` の revision 乱数初期化と `changed` 応答。Godot 版の成果物用に `godot-release-build.yml`
（`v*` タグ／手動で Godot 版 release APK を artifact 化、起動構成 assert 付き）を追加。実機/エミュレータでの起動確認は未実施）

**最終更新**：2026-09-15（Godot UI移行・第4段: `magiGodot=true` 経路の CI を GitHub ホストランナーで自動化。
`godot-ui-check.yml` が Godot 4.5.1 Linux 版を公式リリースから取得（sha512 固定）し、push/PR ごとに
import → headless smoke → `testDebugUnitTest` → `assembleDebug`（magi.pck 同梱）を実行。self-hosted runner は不要に。
PCK 生成は export templates 不要の `godot/tools/build_pck.gd`（PCKPacker）へ変更、`export_presets.cfg` は撤去。
実在しなかった `godot/tools/headless_smoke.gd` を新設。ここまでで Godot 版の実ビルドは未確認＝この CI の初回結果が最初の検証）

**最終更新**：2026-09-15（Godot UI移行・第3段: 起動阻害3件の修正。①`src/godot/AndroidManifest.xml`を新設し
`magiGodot=true`時だけbuild-type manifestとして合流＝Compose側LAUNCHERを外し`MagiGodotActivity`を起動入口に
（第4段で訂正: main には `exported=false`・intent-filter 無しの宣言が実在した。CI の Manifest 合流で衝突したため
main 側は撤去し、宣言は `src/godot/AndroidManifest.xml` に一本化）。
②`MagiBridge`のメインスレッド待ちに10秒上限、超過時はエラーJSON。③トークン鮮度検査を実行と同じ
メインスレッド区間へ移し（旧: 検査→postの間に盤面が変わっても通る隙）、リビジョンをdispatch成功時のみ
進める（旧: snapshotごとに進みプレビュー用トークンが画面更新だけで失効）。あわせて`MagiGodotActivity`を
`FragmentActivity`化・`GodotHost.getActivity()`実装・`JavaClassWrapper`向けstaticエントリ化。
④`godot/`をAPKに同梱する工程が無かった（起動しても画面が無い）ため、`-PgodotExecutable`必須の
Gradleタスク`exportGodotPck`で`assets/magi.pck`へexportし`getCommandLine()`の`--main-pack`で読ませる。
実ビルド・実機は引き続き未検証＝`docs/godot-ui-migration.md`）

**最終更新**：2026-09-15（Godot UI移行・第2段: 職員/シフト/群 管理・担当とスキル・制約編集(全11族)の
3画面をプレースホルダから構造編集対応へ。`MagiOpWhitelist`/`MagiBridge`へws1系・addCons系・
updateConstraint/removeConstraintを追加し、`snapshot()`へ`MagiState`生値の`structure`キーを追加。
並び替えは片手一本指方針によりドラッグでなく上下ボタン。既存`v6/`・重み・`MagiViewModel`は無変更、
`magiGodot`Gradleフラグ既定OFFで既存Composeビルドは無変更。ホストJVMテスト773件成功。
**Android/Godotの実ビルド・実機検証はこのサンドボックスでは未実施**＝詳細は`docs/godot-ui-migration.md`）

**最終更新**：2026-09-14（3.542.0＝新制約「希望の前日に禁止」`cons3w`/違反キー `c3w`。ユーザーと対話（grilling）で設計:
希望(ws3)で固定した X の前日に Y を置けない（希望でない X・初日は対象外）、HARD 9000＝c3n と同格（HF77 明示指示）、行＝
`C3wRow(wishKigou, prevKigou)`。静的表 `Problem.c3wBan` を Evaluator/Delta/チェッカー/C++/`makesForbiddenRun` が共有。
希望どうしの連日は違反として数え、設定ミス診断が「前日の希望を取消」を案内。編集タブ⑤に族「希望の前日に禁止」、CSV 種別
「希望前日禁止」。759 テスト緑・C++ parity 0 mismatch・言語跨ぎ 5 フィクスチャ×20 族 MATCH。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-14（3.541.1＝「そのデータで B4 に移行できるアルゴリズムと重み」の検証記録。厳密解で B4 の値段を
測定（4 セル +94・16 セル +21・10/28 休ゼロ +724）、単一重みの採用境界は covO≥32（HF77 の high>covO を破る）か c3m≤3 で
境界でも利得 −1〜0＝重みでは正当化できない。古泉の窓ルール免除も covO を減らさない。`CountChainPolish` に人員過剰セル起点を
追加（既定 OFF のまま）。結論＝レバーは業務データ（休 9〜10）か covO 受容。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-13（3.541.0＝fair 達成率モード v2。ユーザー指示「平均に入れる率の課題を深く解決する」→候補式を
実データのバケット別に数値比較し案E（回数を帯へクランプ・担当不可で 0 回は母集団から除外・基準達成率は幅を重みにした
中央値）で確定。範囲外の個人設定（古泉 休 0〜1 で 6 回＝率 6.0）が群平均を釣り上げ設定どおりの荒井に偏差 31 を付ける等の
歪みを解消、実データ fair 136→39。Kotlin/C++ 同式・5 フィクスチャ更新・host 753/753・パリティ 5 件×19 族 MATCH。
詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-13（3.540.0＝回数連鎖研磨 `CountChainPolish`（既定OFF・測定中）。ユーザー指示「A4/Cｱ の
超過を研磨できるアルゴリズム」。作る前に CP-SAT で到達値を測定（制限なし 105 セルで 3954→3806、A4 の目標超過は設定上
残る）。同日 k=2/3 の被覆保存巡回交換を複数日で束ね、回数族＋連続系の負債が正味 ≤0 の連鎖だけをチェッカーで keep-best。
実データ 3954→3931（既存パス大予算は 0〜−6）、golden −67。トグル/params/LoopBench 配線・テスト 2 件・host 749/749。
tools/loop A/B（138 ペア）は新2/同等135/旧1・HARD 退行 0＝4 ゲート不合格で既定 OFF 維持。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-13（3.539.0＝「休の過剰がB4に移行しない」（実データ11名/31日）の切り分け記録。対策案
「B4に1人置いたときのfair悪化を探索全体で容認する」を実装前にホストJVMで測定: fairを評価0にしても10/28の
休5人は動かず（最適化器60s）、入力盤面の≤3セル連鎖25本はfair免除でも採用0・low免除でも0・fair+low+c1免除で
18＝壁はlow(120)/c1(50)/c3m(10)、3.521.0パターン3の再来。fair側4案は直交＝実装しない。是正は業務データ
（休の範囲・10/28-29の休=0）で担当者判断。周辺の敵対的所見（達成率モードが矛盾データで健全側を罰する、
実行#3の予算超過511s/300s、MUS診断がHARD=0盤面と矛盾）を未着手で記録。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-13（3.538.0＝ユーザー指示「公平化の平均は、目標値と個人上下限などの設定を配慮した
達成率スコアに変換してから計算する」をgrillingで確定し実装。範囲staffRange.lo/hiまたは実効apt目標が
群の全メンバーに揃う場合だけ達成率（回数の位置/目標比）で比較、揃わなければ従来のround(平均)へ
フォールバック。`Problem.fairDevOfBucket`を共通ソース化しEvaluator/DeltaEvaluator/UnifiedViolationChecker/
DestroyRepairMarginalCostの4箇所を統一（1箇所の改修漏れをテストで検出・修正）。C++`magi_native.cpp`も
同式へ移植し、達成率モードを実際に発火させる5件目のクロス言語フィクスチャ(sept2026)を追加。host JVM
747/747・C++ host_parity_bench 5フィクスチャ全MATCH・自己整合約600万手0 mismatch。C#(-MAGI_PC)は
別リポジトリのため同日同期が必要（本セッション未実施）。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-13（3.537.0＝aptFairSoftTolerance(3.535.0)のtools/loop正式A/B結果（46ケース×3seed
=138ペア、決定的モード）。辞書式で旧(OFF)68勝・新(ON)38勝・同等32＝ONが劣勢。速度-62%・タイムアウト
12→32件・下位10%品質も旧未満＝退行ゼロ/品質≥10%/速度≥10%/安定性の4ゲート全て不合格。c40-large-
c2deficit1でON側のみ例外を観測したが単体再実行6/6で非再現（未解決）。HF77の恒久機能指定のため撤去
せず既定OFFを維持、`docs/algorithm_portfolio.md`を更新。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-13（3.536.0＝実機ログ（Pixel 10 Pro XL・実データ）でaptFairSoftTolerance(3.535.0)
をONにしても「設定の効き」1行に痕跡が出ないと発覚。`TuningTelemetry.aptFairToleranceUsed`を新設し、
素のbetterReportなら却下される手を容認で採用に転じさせた回数だけを数えるカウンタを追加、他トグルと
同型の1行に統合。エンジンの採否・重みは不変。host JVM 746/746。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-13（3.535.0＝ユーザー指示「公平化の違反研磨時に、公平化以外のソフト制約違反を6%
以内なら容認する」を実装。CLAUDE.mdの標準原則「研磨はkeep-best、採用基準を増やさない」への唯一の明示的
例外として、grillingで範囲を確定した上で`AptFairPolish.toleratedBetter`（累積予算方式、HARDの不増加は
不変）を実装、`PolishGate.aptFairSoftTolerance`（既定OFF）で設定タブ→詳細設定にSwitch追加。apt/fair両系
に対称適用、`tools/loop`でA/Bも実施。`AptFairPolishToleranceTest`を新設、host JVM 746/746。詳細は
`docs/history/3.4xx.md`）

**最終更新**：2026-09-13（3.534.0＝ユーザー提示案「制約設定画面にインテリジェンスを追加」の技術検証。
提案の具体例（必須3連続+禁止4連続=矛盾）は実際の評価式(`C3Run.rowDeficit`=run-deficit・窓マッチ)では
矛盾しないと判明、正しい条件（必須の連続数L≥禁止の連続数N）で`V6SanityPort.mustForbiddenSeqIssues`を
新設。実現可能性表示・自動修正は新規DP計算/HF77の安全基準に照らし見送り、検知のみ既存の設定ミス診断
枠組み（`SettingIssue`/`IssueKind.CONSTRAINT`）へ実装＝UI変更不要。`V6SanityPortTest`に2件追加、
host JVM 742/742。エンジンの評価・重みは不変（読み取り専用診断）。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-13（3.533.0＝ユーザー提示のグループ一括設定デザイン案を`StaffRangeEditor.kt`と
照合。真因は各チップに「グループ名・シフト・範囲・人数」を全部詰め込み実質1行1件になっていたこと。
`groupRangeSummary()`の既存ソート順を使いgroupByでグループ別セクション化＋チップ短縮、`全解除`
（`clearGroupRangeSection`新設・1回のUndoでまとめて戻せる）を追加。編集モード化・インライン追加UI・
配色全面見直し・下部ナビ記号化は、この app 全体の統一パターンを崩すため不採用。エンジン・重みは
不変。design_lint.py新規違反0件。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-13（3.532.0＝ユーザー指示「設定に集中できる静かな環境を提供」→設定タブ全体の
視覚的ノイズを減らす、と確認。7節のうち`WeightTableCard`（19件の重み表）だけが`ColorSettingsView`
（3.483.0で同型対策済み）に対して既定展開のまま漏れていたと判明し、既存の共通部品
`CollapsibleSection`で既定折りたたみへ統一。他6節は日常的に触る操作が多いため対象外。エンジン・
重みは不変。design_lint.py新規違反0件。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-13（3.531.0＝ユーザー提示の回数マトリクス デザイン案を`StaffShiftMatrix.kt`と
照合。「薄色=目標のズレ/濃色=上下限の逸脱」「=N表記」「太字現在値+小さく薄い詳細」「セル罫線排除」は
すべて実装済みと確認（変更不要）。唯一の新規提案「行に違反があれば職員名の頭に⚠」だけ実装（既存の
`ui.countViolations`を行単位に集約するだけ・判定ロジック増やさず・新しい色軸も増やさない）。下部ナビの
記号化は全画面共通パーツで具体的な不具合報告が無いため見送り。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-12（3.530.0＝ユーザー提示のデザイン案（職員一覧の▲▼ボタン→ドラッグ&ドロップ）を
grillingで「既存のシフト種別/グループ(3.515.6)と同じ形（行タップ=編集、削除は編集ダイアログの中）」に
スコープ確定して実装。`StaffManageCard.kt`を`Ws1Editor.kt`の`ReorderableRows`(internal化して再利用)へ
統一、`StaffDialog`に`onDelete`追加、`ws1MoveStaffTo`新設・未使用化した`ws1MoveStaff`/`MoveRowButtons`は
削除。エンジン・重みは不変。design_lint.py新規違反0件。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-12（3.529.0＝ユーザー提示の外部仕様書「決定論的修復統合基盤」を調査エージェントで
現状コードと突合し、SHA-256+TLV正規化・因果証拠構造体・新規safety/パッケージ・全操作への汎用Preview化は
既存の`checkSeq`/`fixBoardKey`/`FixApplyGate`/`SaveGate`/`checkRev`と重複のため不採用、3件のみ既存パターンを
横展開して実装: ①`applyAlternative()`に鮮度チェック追加（`fixBoardKey`と同型） ②`MagiUiState.SaveState`
新設で保存状態(Dirty/Saving/Saved/Failed)を設定タブへ表示 ③`UndoSnap.label`追加でUndo/Redoのメッセージに
操作名を表示。エンジン・重みは不変。host JVM 740/740・design_lint.py新規違反0件。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-12（3.528.0＝ユーザー指示「AB評価のフラグはUIに出す」→grillingで確認し「既定OFF
トグルはUIに残し、AB評価で既定ONへ確定した機構はUIに出さない」と確定。`wideC3nBreakDays`・
`combineExhaustPairs`（既定OFF）は元々UI実装済みと判明（調査のみ）。`blockSwapC3nFilter`・`lnsAdaptive`
（3.518.0でAB評価により既定ON昇格済み）のUIスイッチを`MagiSetupCards.kt`の`OptimizationTuningSection`
から削除（opt-outフラグ自体は温存）。エンジン・重みは不変。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-12（3.527.0＝日付ヘッダーに人員過剰バッジ「▲N」を新設（ユーザー明示指示）。既存
「▼N」（人員不足）と対称に`V6PortAnalyzer.V6DayRisk.surplus`を新設し、同じ行に並べて表示（grillingで
配置確認、ヘッダー高さ・レイアウトは不変）。実データで12日A4のsurplus=1を確認。host JVM 740/740・
design_lint.py新規違反0件。`/design-review`はツールから起動不可のためユーザー側での実行を推奨。
詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-12（3.526.0＝weekly（曜日平準化）の指標式を再定義（ユーザー明示指示）。旧
`round(平均)`式は合計3回以下だと目標0に丸まり「配置に関わらず偏差が合計回数のまま不変」という死角が
あった（実データ調査で発覚）。新式`dev=Σ|7×曜日回数-合計|÷7`（重みは不変）。Kotlin/C++の
`weeklyDevOfBucket`のみ変更、増分コスト計算は自動追従。host JVM 740/740・host_parity_bench
4フィクスチャ×19族 全MATCH確認。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-12（3.525.0＝3.524.0への`/code-review`指摘3件を修正。`Evaluator.fullEvalParts`の
breakdown mapをC++側と対称に`clear()`、`NativeParityFixtureTest`の期待値パーサが`#`コメント行で落ちない
よう修正、`host_parity_bench.cpp`の族別breakdownが1〜18件だけの壊れたファイルを黙って2値照合へ後退させず
fail-loudに。host JVM 740/740・host_parity_bench 4フィクスチャ×19族 全MATCH確認。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-12（3.524.0＝backlog#6/#12(b)。#6: native-parity の言語跨ぎ照合（Kotlin `Evaluator.fullEval`
vs C++ `fullEvalParts`）を集約値(hard/soft)だけでなく19族の内訳へ強化（`fullEvalParts`に任意の`breakdown`
出力を追加、Kotlin/C++両側とも既存呼び出しは挙動不変）。golden/sample_v6/blocked_covuの3実データが揃って
apt=c41=c41s=c42s=0（どちらの側にバグがあっても集約値・族単位のどちらでも気づけない死角）と判明したため、
19族すべてを非ゼロにした`full_coverage_state.json`を4件目のフィクスチャとして追加。#12(b): `tools/loop`の
合成ケースにcons2不足2件以上(`c2deficit`)・cons42違反あり(`c42pair`)の2分類を追加（42ケースへ）し、
`C2Polish`/`C42FlowPolish`のiter15/18「検証不能」を解消。小/中/大の各カテゴリ・5seed のスコープ付き
再測定では両パスとも旧腕と完全同点（新規に置いた違反は一般探索の段階で解消されており、専用研磨に
残作業が無い）＝「検証不能」から「実測して無風」という結論に前進、既定OFFは維持。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-11（3.523.0＝3.522.0で既知の問題としてmainマージした3テスト失敗を解決。
`PinInvariantTest`の真因は厳密ピン(staffRange lo==hi)ではなく希望固定(wishLocked)セルの値保持違反で、
`C1WindowPolish.applyC1IndexChainRepair`の候補日フィルタに他の全パス同型の`!p.wishLocked`ガードが
欠けていた（重み非依存の旧バグ、重み表全面見直しで探索経路が変わり顕在化）。修正して候補除外。
`CombinatorialRepairTest`/`ViolationComponentRepairTest`はcons41を4重複させ「単独不採用・結合で採用」の
性質を新重みで復元。詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-11（3.522.0＝重み表の全面見直し（HF77明示指示）。tools/loop 34ケース×10seedの
baseline対比ベンチマークで決定: groupViol/covU/c3n/pref(HARD)を11000/10000/9000/8000へ再配分、
low/c1/c3/c3m/c3mn/c2/c41s/c42s を90/30/3/2/30/1/1/1→120/50/15/10/90/4/6/6 に、apt/fair/weeklyを
1→4/2/2に、covOを5→10へ引き上げ（SOFT中「上限超過(high)>人員過剰(covO)」の優先順位をoutcomeレベルでも
保つため。high違反+42%→+3.4%まで解消・covUは3配分中最良を維持）。`MirrorKeys.weights`・
`Evaluator.fullEvalParts`・`DeltaEvaluator`・destroy-repair/polish系4ファイル・`magi_native.cpp`5箇所を
同時更新、詳細は`docs/history/3.4xx.md`）

**最終更新**：2026-09-11（3.521.0＝設定ミス診断に検査6e追加。実機ログ調査で「希望件数(wishes)が
個人上限(staffRange.hi)を超過」（例: 古泉 健一「有給」上限0・希望10件、荒井克枝「Cｵ」上限0・希望10件）
というどの既存診断でも検出されていなかったパターンを確認。希望固定セルはLevel Zero不変条件で動かせない
ため上限超過は解消不能＝不具合ではなく設定ミス。既存の6d（apt版）と同型の判定を`staffRange.hi`に対して
`V6SanityPort.forcedCountIssues`へ追加（grillingでスコープ確定・読み取り専用、詳細は
`docs/history/3.4xx.md`）

**最終更新**：2026-09-11（3.520.0＝ユーザー指示「UIの用語統一。ゲーム要素廃止。」に3段階UXプロセス
（①現状把握→②改善検討→③実施検証）で対応。フェーズ名バッジ「狩猟」→「未完成」、感嘆符/進捗を煽る
前置き（「できました！」「もう少しです。」）を平易な文へ、旧語「できあがり度」の残存2箇所を正式語
「解消度」（3.480.0で改称済み）へ統一。「最適化」vs「計算」の混在（`MagiViewModel.kt`だけで10箇所超）を
「最適化を実行したか」という同一概念に絞って統一（違反数の再評価・分析タブ個別項目のcomputedフラグ等
別概念の「計算」は残置）。「要調整」vs「ソフト違反」・「職員」vs「スタッフ」・「勤務表」vs「シフト表」は
調査の結果ほぼ統一済みと判明し対象外。既存の用語集`docs/operator_ux.md`§2が実装より古い旧語を記載していた
ため実装に合わせて修正（`docs/power_user_ux.md`・`docs/magi_design_system.md`・`docs/screen_spec.md`も）。
史料（changelog/history/archive）は不変。C#（-magi_pc）も同時に全箇所同期。ホストJVM 739件・C# green
（`MagiViewModel.kt`等UI層の大半はhosttest.shのコンパイル対象外＝実機/CI確認が必要、C#の`.xaml`/
`.xaml.cs`編集も同様に未確認）

**最終更新**：2026-09-11（3.519.0＝3.518.0で未計測のまま残した2件のAB結果。`combineExhaustPairs`は
`tools/loop`の正式A/B（iter24、170ペア）でlarge/infeasibleの1ペアに必須件数増(119→120)を確認＝
退行ゼロを満たさず既定OFFで確定（再提案しない）。品質改善もほぼ無く速度はむしろ遅い。
`personSwapKick`(3.517.0)は新規ハーネス`PersonSwapBench.kt`（実データ4件×5seed×フルoptimize(PORTFOLIO)、
CLAUDE.mdが認める代替A/B手法）で全20ペア必須退行ゼロ・4フィクスチャ全てで負け越しなしを確認し
既定trueへ昇格（golden平均-2.1%改善など。3.517.0の手動probe-2.3%より幅が小さいのは同時昇格した
lnsAdaptiveと改善余地が一部重なるため）。既存テストのゲート既定値依存も発見して修正
（`sixEscapeWorkersRotateAcrossAllEscapeRoles`等）。ユーザー向け設定説明文「結果は悪化しない」の
不正確な記述も訂正（個々の手はisBetterゲートで悪化しないが、探索経路が変わり最終盤面が別の局所解に
着地し得るため）。ホストJVM 739件green）

**最終更新**：2026-09-11（3.518.0＝ユーザー指示「すべての既定OFFの処理をAB評価しメリットあれば既定Onに」に対応。
既定OFFの全トグル（`PolishGate`の`@Volatile var`・`PostOptimizationParams`・`ViolationComponentRepair.Params`）
を棚卸しし4分類: (1)既存測定だけで即判断できる`filterC3nIncrease`（3.296.0/3.298.0でON/OFF最終盤面完全一致・
速度のみの純増）と`lnsAdaptive`（iter9で必須退行0・品質±0・速度実データ-23%〜大規模-32%、当時の結論
「既定ONはユーザー判断待ち、推奨ON」が今回の指示で確定）は既定trueへ昇格、(2)`wideC3nBreakDays`/
`c3PairMaskEnabled`/`lnsWeightDebt`等17件は既に具体的な悪化例つきで決着済み＝CLAUDE.mdの「決定記録は
再提案しない」規律により再測定せず据え置き、(3)`combineExhaustPairs`は一度も測定されておらず、
(4)`personSwapKick`（3.517.0の新機能）も未測定＝(3)(4)は別途A/B実施。昇格2件は`PolishGate`とUI状態
（`MagiUiState`/C#`UiState`）の既定が乖離すると「表示はOFFなのに実際はON」になる不整合を発見し両方修正。
C#（-magi_pc）も`FilterC3nIncrease`を同時昇格（`lnsAdaptive`はC#未移植の既知ギャップ）。
ホストJVM 739件・C# 1278件（838+440）green）

**最終更新**：2026-09-11（3.517.0＝ユーザー指示「全月入替かつ再最適化の新しいアルゴリズムを賢く深く
高速化対応で作成する」に対応。既存 AdaptivePortfolio（`V6NativeOptimizer.kt`/
`AdaptiveHypothesisEpochPolicy.kt`）へ新役割`PERSON_SWAP_ILS`を追加（grilling4問で実装場所・
ペア選定・再最適化の深さ・既定ON/OFFを詰め、全問推奨どおり採用）。同群2名の1ヶ月分割当を丸ごと
交換してからRSI+でフル再最適化するILS摂動＝実データ1件の手動probeでweightedScore 9831→9605
（-2.3%）を確認済みの技術を恒久機能化。fairはグループ内回数集合が変わらないため交換不変（分析的に
証明済み）、改善はc1/c3/high等の局所解構造が変わることで生じる。`PolishGate.personSwapKick`は
既定false＝ゲートOFF時は既存6役割の固定ローテーションがビット単位で不変（`HypothesisEpochPolicyTest`
に固定）。ペア選定は全ペア総当たりでなく`MirrorCore.kt`のfair計算と同一式でfair負担が大きい職員を
優先するヒューリスティック（新規`internal fun personSwapKick`、`PersonSwapKickTest.kt`で固定）。
Kotlinのみの変更（探索オーケストレーションであり評価器/重み変更ではないためC++/C#移植義務の対象外）。
`tools/loop`の合成30ケース+実データ4件による正式A/Bは未着手＝それまで既定OFFのまま
（`docs/algorithm_portfolio.md`「実装済みだが既定OFF」に記載）。ホストJVM 739件green）

**最終更新**：2026-09-10（3.516.0＝HF77明示指示で上限超過(high)の重みを45→25に変更（業務担当者が
「上限超過を人員過剰と期間の制約の間に移動する」→数値指示で25を選択）。`MirrorKeys.weights`を起点に
Evaluator/DeltaEvaluator/destroy-repair系polish/`magi_native.cpp`（評価器+SaChunk+コメント）・
言語跨ぎ期待値ファイル（Kotlin/C#双方）・テスト内重複リテラル（逆順`45L * x`パターンも含む）を同期。
`ShiftAppearance.severityFromVioKey`と`heavySoftFamilies`はhigh(25)がc1/c3mn(30)を下回ったため
HIGH→WARN・破線枠対象外へ変更（表示強度＝重み階層の既存原則に従う、AskUserQuestionで確認）。
C1WindowPolish（Kotlin/C#）の「X追加は必ずisBetterに棄却される」という手R1/R2追加の根拠コメントが
単窓局面で不成立になり、`C1RelocationPolishTest`/`V6HotfixPassesC1WindowTest`の対応テストを再設計。
C#（-MAGI_PC）は同日同期）

**最終更新**：2026-09-10（3.515.6＝ユーザー提示モックアップを起点にgrillingで5問詰め、編集タブ「シフト種別」
「グループ」の行デザインを刷新。▲/▼(3.515.3)を長押しドラッグへ（片手一本指の既定をこの2箇所限定で明示的に
上書き・CLAUDE.md/docs/design.mdに例外を明記）、削除ボタンは行から編集シートの中へ、グループの並び替えは
新規実装（`Ws1Ops.moveGroup`）。TalkBackでは操作不可な既知の制約を開示。UI層のためCIで最終確認）

**最終更新**：2026-09-10（3.515.5＝backlog #17。設定・勤務表タブ（MagiSetupCards/MagiDashboardCards/MagiScheduleViews）
の文字サイズも3.515.4と同じ意味階層（章=titleMedium／節=titleSmall／本文・行=bodyMedium／補足=bodySmall）へ統一。
凡例・バッジ相当（違反の凡例・シフト集計の▼▲凡例・セル内の「現在」「超過」等の小さな注記）はlabel*のまま据え置き。
design_lint hard 0件・追加コメント0行。これでbacklog #17は完了）

**最終更新**：2026-09-10（3.515.4＝編集タブの文字サイズを意味階層に揃える（実機報告「フォントサイズがバラツキある」）。
節見出しを label+太字、説明も一覧の行も labelSmall で書いていたため全部14sp＝ボタン(16sp)より見出しが小さい逆転。
章=titleMedium／節=titleSmall／本文・行=bodyMedium／補足=bodySmall／label は部品ラベル・チップのみ、に統一し
DESIGN.md §3.3 へ規則を明文化。対象は編集タブ5ファイル、設定・勤務表タブは同規則で別途）

**最終更新**：2026-09-10（3.515.3＝職員・シフト種別の並び替え（▲/▼）を追加（実機報告）。Ws1Ops.moveStaff/moveShift
は removeStaff/removeShift と同じ index 付け替え（勤務表・希望・個人の回数・日別必要人数・担当可否・群目標が追従、
記号参照の制約行・表示色は不変、端は no-op）。片手一本指のため Affordance.MoveRowButtons の2ボタン。
Ws1OpsMoveTest 4件、host 731テストgreen。C#（-MAGI_PC）同期は別途）

**最終更新**：2026-09-10（3.515.2＝実機報告6件を修正。(1)群の目標ステッパーの+/-がセルタップシートを閉じる
（key(ui.editRev)配下のremember破棄。シート開閉状態をMagiApp.kt側へ引き上げ）、(2)曜日ヘッダの下線が
needViolations(シフト×日)のみの違反日に付かない、(3)covOセル詳細で希望固定していない在勤者が表示・
操作できない（在勤者一覧＋個別「直し方を探す」を追加）、(4)違反ナビでジャンプしたセルをShiftPickerSheetで
タップしても理由が出ないケースがある（(2)(4)はneedViolations未合算という同根）、(5)色ピッカーが選ぶと閉じる
（4呼出元とも「閉じる」/×だけで閉じるように）、(6)分析タブの設定警告（cons3n重複「Dﾃ→A4」）が18文字で
切れて勤務表の違反に見える（1件のときは場所＋理由を全文表示）。UI層はCIで最終確認、(6)はhost 727テストgreen）

**最終更新**：2026-09-09（3.515.1＝3.515.0への外部レビュー(/code-review)指摘2件を修正。(1)FixSuggesterのfocusShiftは日を指定できず別日の改善がこのセルの解消として誤カウントされ得た点を、返った手が実際にday j自体を動かすか確認するよう修正。(2)covO深追いが最適化完了直後の編集ロック解除・経過時間表示を最大8秒巻き込んでいた点を、pushReportを待たせない別ジョブ(deepCovODiagFollowUp)へ分離して修正）

**最終更新**：2026-09-09（3.515.0＝covOの「なぜ減らないか」診断を複数職員・別日の組合せまで探すよう拡張（ユーザー指示。既存のFixSuggester「直し方を探す」を再利用。実データでは今回の5件とも解なしと確定＝low/high重み差による構造的トレードオフ）。副次的にViolationComponentRepair/V6FinalPortの無駄なsurplus計算を除去、深追いは最適化完了直後のみでライブ編集診断には影響しない設計）

**最終更新**：2026-09-09（3.514.0＝combineExhaustPairs・lnsAdaptiveをUIの「詳細設定」からON/OFFできるように（ユーザー指示。不合格判定で凍結中のdebtLaneSlots等は対象外）。シフト集計カードの既定開閉を展開へ戻す（3.483.0 S-4を反転、ユーザー指示「シフト集計は開く。閉じない」））

**最終更新**：2026-09-09（3.513.0＝最終番兵の復帰盤面バグを修正。`handleOptimize`は番兵発火時に`finalSched`を`cappedInput`（`inputReport`と同じ、上限0のセルを外した盤面）でなく`normInput`（外す前の生入力）へ戻していたため、reportは違反なしと言うのに実際の盤面には上限0の割当が残る食い違いが起き得た。実機報告「大島愛のDﾃが上限0なのに1件割当」の調査で発見。`sentinelSchedule`へ抽出しユニットテスト、C#（`-MAGI_PC`）へ同日同期）

**最終更新**：2026-09-09（3.512.6＝実機報告（荒井克枝の休超過）から`CombinatorialRepair.combineAndApply`の固定`maxStagnantTries=200`回を調査。プール56件でも`C(56,2)=1540`通りの2人組交換があるのに約13%しか試さず打ち切っていた構造を発見し、`exhaustPairs`/`pairCap`でフルペア空間まで探索できるopt-inを実装（既定OFF、6箇所の研磨パスへ配線、`tools/loop`で採否測定予定）

**最終更新**：2026-09-09（3.512.5＝`LiveScheduleCard`「状態遷移」の変化件数が実機で常に0固定だったバグを修正（実機報告: 探索中16回の最良更新があっても表示は0のまま。原因はremember計算ラムダ内の副作用がComposeの破棄コンポジション契約に反していたこと。書き込みをSideEffectへ分離）

**最終更新**：2026-09-09（3.512.4＝debtLaneSlots・bestOfKの分離条件iter22/23はいずれも**不合格**（debtLaneSlots: 新良8/同等135/旧良27・品質-0.18%、bestOfK: 新良15/同等138/旧良17・品質-0.17%、必須増はlarge-infeasibleに集中）。ユーザー判断で検証・追加実装を中止、既定OFFのまま凍結。WeightDebt/ConstraintRepairInference/familyPriorityScoreに続く同系統4・5度目の不合格）

**最終更新**：2026-09-09（3.512.3＝ユーザーの机上テストで`trimFrontier`のビーム縮小バグを発見・修正（負債候補0件でも非負債候補の枠がbeamWidth-debtLaneSlotsに固定され純粋な退行を起こしうる構造）。`LoopBench`の`debtlane`/`bestofk`フラグをレバー単体で分離測定できる条件へ修正、旧測定値は交絡した参考値として保存・採否判定には不使用）

**最終更新**：2026-09-09（3.512.2＝quantitativeRangeEval（C2/C41/C41sの量的評価、backlog #12(a)）はiter21で**不合格**（170ペア新良5/同等153/旧良12、品質±0.00%・速度-1.1%とも不合格、必須件数が増えた試行6件はlarge-infeasibleカテゴリのみ）・既定OFF維持）

**最終更新**：2026-09-09（3.512.1＝`V6FinalPort.handleOptimize`のトレーリングラムダ誤束縛を修正。backlog #12(a)で`quantitativeRangeEval`を`onProgress`の後に追加したため本番呼出2箇所（MagiViewModel/OptimizationWorker）がAndroidビルドだけ壊れていた（`EliteRelinking`で一度踏んだのと同じ罠の再発、host JVMでは検出不能でCIで発覚）。回帰テスト追加）

**最終更新**：2026-09-09（3.512.0＝ユーザー提示「適応型制約違反研磨エンジン 完全統合実装仕様」v2.1の核（二車線ビーム`debtLaneSlots`・族優先度の順序非依存化`bestOfK`）を既存コードの構造的欠陥として実装。重い機構（結合度負債テンソル・Restless Bandit・並列トランザクション）は仕様書§20の解除条件未達のため見送り。既定OFF・未計測）

**最終更新**：2026-09-09（3.511.9＝C1重複窓の連結成分化 `C1RepairAnalysis.solveComponent` はiter20で**全170ペア新旧同等**（検証不能）・既定OFF維持。3.511.8 C3n前後余白込みLNS `C3nMarginLnsPolish` はiter19で**不合格**（ほぼ無風）・既定OFF維持。3.511.7 c42/c42s専用min-cost-flow研磨 `C42FlowPolish` はiter18で**全170ペア新旧同等**（検証不能）・既定OFF維持。3.511.6 族選択を件数×重み×改善可能性へ familyPriorityScore はiter17で**不合格**・既定OFF維持。3.511.5 C41/C41s専用 min-cost-flow研磨はiter16で**不合格**・既定OFF維持。3.511.4 予算超過回帰試験でRSI_PLUSの35秒下限を発見・記録。3.511.3/3.511.2/3.511.1/3.511.0 は不合格・既定OFF維持。lnsAdaptive の既定 ON は判断待ち）

**最終更新**：2026-09-08（3.510.0＝C3「選択日ペア交換」を最終段の低予算パスとして実装（既定 OFF、`tools/loop` iter8 で採否）。外部の重み・時間配分分析は backlog #14 に登録）

**最終更新**：2026-09-08（3.509.5＝研磨パス共通の採用ゲート `adoptionGate` へ 18 箇所を統一（backlog #13(b)）。挙動不変、後処理 4/4 一致）

**最終更新**：2026-09-08（3.509.4＝改善提案の適用直前に仮盤面で完全再評価する `FixApplyGate`（改善しない・固定を崩す提案は見送り）、完了カードに変更人数・セル数・希望充足・個人回数の 1 行。自動化 UX 評価 7 項目の対応は `docs/automation.md` §6）

**最終更新**：2026-09-08（3.509.3＝自動化方針（ユーザー提示）を `docs/automation.md` に実装との対応表として固定。CSV の個人レンジ行は非負整数・下限≤上限だけ受理）

**最終更新**：2026-09-07（3.509.2＝日割当研磨の apt 目標を `Problem.apt`（実効目標）へ統一。外部レビューの評価定義・探索動学・UX 提案は backlog #12 に集約（決定・測定待ち））

**最終更新**：2026-09-07（3.509.1＝外部レビュー一覧の仕分けと修正。負数の個人回数を未設定扱い（D9 に数えない）、CSV ヘッダ判定を構造で、停止ログの到達不能分岐、「群の目標（個人設定がない職員に適用）」。C# は CSV 例の複数表示・クイック解決の入力欄同期・言語跨ぎ契約テストを追加）

**最終更新**：2026-09-07（3.509.0＝決定 D9「個人の下限/上限がある (職員,シフト) には群目標（apt）を適用しない」（ユーザー再指示）。個人設定のある組の違反は low/high だけで apt と二重計上しない。3.508.2＝UI 層の接点レビュー（「なおすのを手伝って」の候補を mayPlace へ））

**最終更新**：2026-09-07（3.508.1＝自律レビュー＆リファクタ第 10 段 `V6HotfixPasses` 各パス内部。HF80 の内側探索を「その場で 1 セル書き換え→不採用なら戻す」に（試行ごとの盤面コピーを撤去）。seed 固定プローブで改修前後 48/48 一致、HF80 の検査 3 件追加。エンジン層の精読は一巡）

**最終更新**：2026-09-07（3.508.0＝適切回数（apt）の群目標を「構造的に到達できる範囲」へクランプ。桒澤 B4「目標 1／現在 17」の幻の超過の恒久対策（休 10・有 1 以外に置けるシフトが無い職員の B4 目標は 1→20）。評価は実効目標、設定ミス診断は設定値で「計算では N 回を目標として扱っています」と案内。実データで動くのは 9 組のみ）

**最終更新**：2026-09-07（3.507.9＝自律レビュー＆リファクタ第 9 段 `V6SearchOperators`（2 パス乱択 12 箇所の集約、c41/c41s 修正の統合、厳密ピン判定の共有）。seed 固定プローブで改修前後 72/72 一致、find*Fix の検査 6 件追加。探索動学は不変）

**最終更新**：2026-09-07（3.507.8＝自律レビュー＆リファクタ第 8 段 CSV 層 `ScheduleCsvBridge`。4 実データで `parse(build(state))` の完全再現を検査で固定（勤務表/職員/希望/制約、カンマ・引用符入り氏名を含む）。コードは無変更。C# 側の 3.474.0/3.475.0 未同期 5 点を同日同期）

**最終更新**：2026-09-07（3.507.7＝自律レビュー＆リファクタ第 7 段 `V6LateOperators`（400 行の 1 関数を `LateSession`＋4 演算子メソッドへ、RectSwap の違反者抽出を共通化、`isBalanceable` をシフト別に記憶）。4 実データ × 6 盤面 × 2 seed で結果盤面・採用数・ログ 48/48 一致）

**最終更新**：2026-09-07（3.507.6＝ループ Iteration 7 報告。決定的モードで**再現性 34/34**（JVM 再起動 2 回をまたいでも同一）、新良 179/同等 150/旧良 11・品質 +1.81%・必須が減った試行 61。ベンチを再開可能に（済みの行を飛ばして追記）。コード変更はベンチ側のみ）

**最終更新**：2026-09-07（3.507.5＝自律レビュー＆リファクタ第 6 段 `V6SanityPort`（設定ミス検査 689 行と違反詳細 316 行を話題ごとのメソッドへ分割、表示上限の定数集約）。「担当できる人数」を最適化器が置ける人数（上限 0 を除外・希望固定を含む）で数える＝3.507.0 の取り残し。4 実データ × 6 盤面で設定ミス 24/24・違反詳細 20/24 一致（4 件は「代用可」人数の意図した変化））

**最終更新**：2026-09-07（3.507.4＝自律レビュー＆リファクタ第 5 段 `V6SwapSuggester`（FixSuggester を Session 化・7 フェーズを関数に分離・上限の定数集約・連鎖の mayPlace ガード・冗長評価と同日重複列挙の撤去）。4 実データ × 5 設定で提案一覧 20/20 一致。C# 版のドリフト 2 件（休 index 0 決め打ち・署名に日が無い）も同時に修正）

**最終更新**：2026-09-07（3.507.3＝ループ Iteration 7 第一弾。後処理の決定的モード（`PostOptimizationParams.deterministic`＝ms キャップ・締切・残り時間の判定を回数上限へ。共同 LNS 2 種に `maxEvaluations`）。実データ 4 件で時間モードと同一盤面かつ 2 回とも再現。実機は既定 OFF＝挙動不変。ベンチ iter7 走行中）

**最終更新**：2026-09-07（3.507.2＝ループ Iteration 6 報告（新良 163/同等 169/旧良 8・品質 +1.84%・必須が減った試行 64）＋仕様 §4「機能同等性」の 14 機能回帰スイート `LoopFeatureRegressionTest`（旧腕・新腕の両方で不変条件を検査、14/14）。コード変更なし）

**最終更新**：2026-09-06（3.507.1＝ループ Iteration 6 第一弾。起点生成でピン（lo=hi）を単独で崩す単セル・同日交換を、同じ職員の別の日で回数を戻す行内入替・2 日交換に置き換え。4 フィクスチャで退行なし、sample_v6 は採用 3→9・total 337→332。ベンチ iter6 走行中）

**最終更新**：2026-09-06（3.507.0＝個人上限 0（0〜0）のシフトを最適化器が置かない（`Problem.mayPlace`＝候補生成・入口 hf66・最終番兵の基準。評価・表示・重みは不変、希望固定は優先）。ユーザー決定「最適化器だけ除外、表示は今のまま」。再現データで 桒澤 A4/Cｵ・大島 Cｱ が 0、上限 0 の無い 4 フィクスチャは旧新同一）

**最終更新**：2026-09-06（3.506.1＝ループ Iteration 5 報告。新良 153/同等 179/旧良 8・必須が減った試行 62（最大 -38）・品質 +1.72%（大規模の充足不能 +11.5%）・速度 -2.3%・再現性 34/34。退行ゼロのゲート合格、10% ゲートは未達。コード変更なし）

**最終更新**：2026-09-06（3.506.0＝「グループ単位の回数」で下限・上限とも「なし」を適用できる（グループ全員ぶんの個人上下限を解除）。実機報告「なし 設定出来ない」。表示・編集のみ・スコア不変）

**最終更新**：2026-09-06（3.505.9＝CLAUDE.md を Claude 5 世代向けに見直し。376 行→84 行。手順と履歴は `docs/environment.md`・`docs/backlog.md`・`docs/architecture.md`・`docs/history/topics.md` へ、重み規約は `.claude/rules/weights.md` へ。コード変更なし）

**最終更新**：2026-09-06（3.505.8＝CLAUDE.md の見直し提案 `docs/claudemd-tune-proposal.md`（承認待ち）と、CLAUDE.md の事実訂正（重み c1/c3mn 30・covO 5・fork 名・ホスト JVM ビルド・検証ハーネス）。コード変更なし）

**最終更新**：2026-09-06（3.505.7＝SUDO モデルを実装と再照合。重み c1/c3mn 30・covO 5、O の実測 5015、欠損セル -1、S に Windows 版、D に拒否候補の再利用。コード変更なし）

**最終更新**：2026-09-06（3.505.6＝ループ Iteration 5 第一弾。起点生成に同長区間交換・同日 3 職員巡回、最終段の予算を残り時間で拡張。実データ 4/4 盤面同一。ベンチ中。重み不変）

**最終更新**：2026-09-06（3.505.5＝ループ Iteration 4 報告。新良 152/同等 180/旧良 8・必須が減った試行 52（最大 -20）・品質 +1.07%・速度 -1.8%。退行ゼロのゲートを初めて合格。コード変更なし）

**最終更新**：2026-09-06（3.505.4＝ループ Iteration 4 第一弾。違反起点からの候補生成を共同 LNS 後の最終段に追加（巡の中では退行を実測したため）。実データ 4/4 非退行。ベンチ中。重み不変）

**最終更新**：2026-09-06（3.505.3＝ループ Iteration 3 報告。新良 71/同等 245/旧良 24・必須退行 0・速度 -3%＝10% ゲート不合格、改善は飽和。ハイブリッド併用（既定 ON）を維持しループを閉じる推奨。コード変更なし）

**最終更新**：2026-09-06（3.505.2＝ループ Iteration 3 第一弾。相方なしピン破りの事前除外と構造的に埋められない covU 起点の後回し。実データ 4/4 盤面同一・推定回数は最大 1/2。ベンチ中。重み不変）

**最終更新**：2026-09-06（3.505.1＝ループ Iteration 2 報告。違反起点のトランザクション修復は必須退行 0・新良 68/同等 249/旧良 23・速度 -3%＝10% ゲート不合格→§6 ハイブリッド併用として既定 ON。C# 同期。重み不変）

**最終更新**：2026-09-06（3.505.0＝Iteration 2 第一弾 `ViolationComponentRepair`（各パスの拒否候補を違反起点のトランザクションに束ねる。既定 OFF、ベンチ中）。sample_v6 で HARD 9→8。外部ドッグフーディング 4 件を反映。重み不変）

**最終更新**：2026-09-06（3.504.2＝自律改善ループ Iteration 1 報告。希望島＋アンカー窓交換の一式を旧チェーンと 340 ペアで比較し、ゲート不合格（品質 -0.18%・速度 -0.8%・個別退行 2・再現性 33/34）→ §6 ハイブリッド併用で温存、断捨離せず。ハーネス `tools/loop/`。コード変更なし）

**最終更新**：2026-09-06（3.504.0＝外部提示 C# 6 ファイルの優劣検証と移植。希望島ビームを島巡回＋残り予算で頭打ちへ（単体 1 改善/11 不変・後処理 4/4 不変）、C1 ビームのハッシュ鍵 dedup、評価中例外の巻き戻し try/finally。公平分配・枝刈り計上・root 初期化・族ゲートは計測つきで否決。3.504.1＝最終コードレビュー 5 件を反映（ビームの走査枠は評価した手だけ数える）。重み不変）

**最終更新**：2026-09-06（3.503.0＝自律レビュー第4段 `V6PortAnalyzer`。`diagnoseCoverage` を不足/緩和案/過剰の 3 関数へ分割、Probe 定数と共有ヘルパ。出力ハッシュ 24/24 一致＝診断の出力は不変。C# 同期）

**最終更新**：2026-09-06（3.502.0＝バックログ #9(c)(d)・#10 を実装。希望島ビームは走査 2 倍・良い順に保持（計測は中立）、担当外希望の一括クリアは canDo 再判定、「なおすのを手伝って」の連打防止は検査世代 `checkRev`。#4 はエンジン側 dedup を行わないと確定。重み不変）

**最終更新**：2026-09-06（3.501.0＝希望島研磨の候補配分をユーザー指示で変更。禁止連は件数が増える手だけ枝刈り、同日/窓/両翼を交互評価、島の枠 25% を 3 職員巡回に確保。後処理全体の最終盤面は 4 実データで不変、単体は混在（history に計測）。重み不変）

**最終更新**：2026-09-06（3.500.2＝外部レビュー第3・4段の同期。`setCell`/`setCells` にシフト index の上限検査（-1 は未割当として可）。案内付き修正の連打防止の同型をバックログ #10 へ。エンジン・重み不変）

**最終更新**：2026-09-06（3.500.1＝-MAGI_PC 側の外部レビュー 2 段を Android に同期。`undo()`/`redo()` で `engineRan=false`・改善提案を破棄・`resultSchedule=null`（手操作＝計算済みでない）。希望島研磨への探索動学の指摘 4 件はバックログ #9 に登録（計測してから）。エンジン・重み不変）

**最終更新**：2026-09-06（3.500.0＝後処理オーケストレータ `runPostOptimization` を監査基準7項目でレビューして再構成。`PostChain` ランナー（採用・ピン帰属の合流・ログ・計時を 1 経路に＝合流の書き忘れを構造的に防ぐ）、`PostOptimizationParams`/`SeedTag` 集約、クラスタ・検証ログ・診断の分離。4 実データの最終盤面が改修前と一致＝採否の意味論は不変。エンジン挙動・重み不変）

**最終更新**：2026-09-05（3.497.3＝CLAUDE.md の常時読み込み分を 190KB→39KB（約 5.8 万→1.2 万トークン）へ。作業記録の索引を `docs/history/INDEX.md`、話題別の版数付き叙述を `docs/history/topics.md` へ移し、本体には恒久の事実だけを残した。docs のみ）

**最終更新**：2026-09-05（3.497.2＝ナレッジグラフ4ツールを実測し Serena（LSP）だけ `.mcp.json` で試験導入。グラフ系は Kotlin の呼び出し解決が0件のため見送り。docs/設定のみ）

**最終更新**：2026-09-05（3.497.1＝追加コメントの点検を手順化。`comment-check` スキルと `tools/comment_ratio.py` を新設し、コミット前に追加したコメント行を抽出して1行ずつ判定する。docs/tools のみ・アプリ本体は不変）

**最終更新**：2026-09-04（3.497.0＝OPPO A5 5G（Android 16・720×1604＝360dp 帯）を対象に追加。幅 390dp 未満では勤務表の
名前列を 56dp に詰めて7日表示を成立させ、下部バーの「元に戻す」「やり直し」をアイコン化。390dp 以上は不変。minSdk=36 のまま。）

**最終更新**：2026-09-04（3.496.0＝希望島研磨 `WishIslandPolish` を新設（ユーザー提示の確定仕様）。実現可能な希望日を固定
アンカーに、影響範囲が重なる希望を島へ統合し、周辺に違反がある島だけ起動。同日→窓→両翼→必要時のみ3者巡回、希望周辺も
全体も改善する手だけ採用、停滞時のみ短いビームで中立手。最終結果は開始盤面より改善（keep-best）。Windows 版も同時。）

**最終更新**：2026-09-04（3.495.0＝ユーザー提示の設計「違反アンカー型・可変長ウィンドウ交換」を `AdaptiveBlockSwapPolish` の
`WindowMode.STRICT_WHOLE_WINDOW` として実装（3.494.0 の RunSwapPolish は削除）。違反セル／回数超過・不足（逆引き）／連続規則／
週偏りをアンカーに、接する可変長の窓を同じ日付範囲で他職員と一括交換。安価な優先度で絞り正式 checker の keep-best で
pass ごとに最良1手。Windows 版も同時。）

**最終更新**：2026-09-04（3.494.0＝連交換研磨を汎用化。3.493.0 の夜勤前提（cons3n 先頭×翌日希望固定）を撤廃し、
`RunSwapPolish` があらゆる違反に隣接する同一シフトの連（種類を問わない）を他職員の同じ長さの連と窓ごと交換する。
keep-best・厳密ピン保護・境界の禁止の並び／被覆を欠く付替えの枝刈り・正式評価上限600。Windows 版も同時。）

**最終更新**：2026-09-04（3.493.0＝夜勤連交換研磨 `NightRunSwapPolish` を新設。前日=夜勤×翌日=希望固定に挟まれたセルの違反を、
夜勤の連を他職員の同じ長さの連と窓ごと交換して解く（keep-best・厳密ピン保護・境界の禁止の並びで枝刈り）。実データでは
改善手0（c1 と固定回数が壁）を計測したうえでユーザー判断で導入。Windows 版も同時。）

**最終更新**：2026-09-04（3.492.0＝実機指摘「データ修正のサポートが無い」。人員過剰の枠を本人希望で固定している在勤者を
名指しし（`CoverageSurplus.pinnedStaff`）、シフト集計の過剰セルのダイアログとホームの過剰診断カードから「◯◯ の希望を
取り消す」で希望をその場で取り消せるように。表示・導線のみ・スコアリング不変。）

**最終更新**：2026-09-04（3.491.0＝レビュー第7弾＋自己見直し。並列仮説の「既に勝者がいれば起動しない」事前チェックを撤去
（全本継続の仕様どおりに）、停止を例外で返す契約を `ensureActive()` とテストで明示（Android は `withContext` の完了時チェックで元から
成立＝レビューの指摘は Windows のみ実在）、読込時の endDate/groupShiftApt 正規化を保存にも反映、起動時の結果ファイル判定に validate、
RangePolish の「残存」表記に不採用の主因を併記。3.490.0 の「採否不変」を訂正＝EarlyChain が初めて生きた。Windows も同時。）

**最終更新**：2026-09-04（3.490.0＝レビュー第6弾。エンジン内の締切・経過・停滞判定（v6 の 87 箇所）を壁時計から
単調時計 `EngineClock.nowMs()`（`System.nanoTime`）へ（旧: NTP 補正・時刻変更・スリープ復帰で予算判定が前後した）。
ログの実時刻だけ壁時計のまま。採否・スコアリング不変。Windows のタブ再購読は `-MAGI_PC` 側で修正。）

**最終更新**：2026-09-04（3.489.0＝実機報告「個人の下限をゼロに出来ない」。`staffCellLimits` が下限 0 を未設定扱いにして
表示だけ落としていた（エンジンは保持）。明示した 0 を 0 として返す。表示のみ・スコアリング不変。）

**最終更新**：2026-09-04（3.488.0＝レビュー第5弾。読めない `startDate` を読込で拒否（旧: 受理して `Problem.dow0` が黙って
日曜へ落ち、曜日平準化・曜日単位の修復が実際のカレンダーと食い違った）。`groupShiftApt` を読込の検証後に G×K へ正規化
（読む側は既に添字を守っていたが境界を1か所へ）。詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-04（3.487.0＝レビュー第4弾の新規2件。原子置換（`writeFileAtomically`）が rename 失敗で直接書きへ落ちる前に
所有権（`commitGuard`）を再確認（旧: 稀な経路で古い writer が target を書けた）。`cleanup-artifacts.yml` は1件も消せなければ
ジョブを失敗に。Actions の SHA 固定は方針事項として記録のみ。詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-04（3.486.0＝レビュー第3弾（総括12件）の検証。新規は3件: 読込時に `endDate` を日数へ正規化
（`Ws1Ops.normalizeEndDate`、矛盾は警告ログ）／CI で Android Lint を報告専用に実行（旧: 一度も走っていなかった）／
ビルド失敗ログの保存条件を修正。⑧〜⑪（広い catch・largeHeap・デバッグ鍵・成果物全削除）は記録された意図的判断のため
変更せず、判断材料を `docs/history/3.4xx.md` に整理。）

**最終更新**：2026-09-04（3.485.0＝自動保存の**世代逆転**を塞ぐ。`saveJob.cancel()` は始まった書き込みを止められず、
古い自動保存が `saveNow()` の後に完了すると自動保存が古い状態へ戻り得た。`SaveGate`（main で採番した世代をロック下で比較し、
古い世代を捨てる）を自動保存/即時保存の両方に通す。Windows 版レビュー第2弾3件の検証から（他2件は Android に相当なし）。
詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-04（3.484.0＝担当可否マトリクスの**単一セル**でも「休」を OFF にできないよう `Ws1Ops.setGroupShift` に
入口ガード（行/列一括と同じ拒否契約＋同じ案内）。Windows 版への外部レビュー3件を検証した際に見つかった兄弟バグ。
回帰テスト追加。スコアリング不変。詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-04（3.483.1＝docs のみ。全7画面＋表紙の静的モックアップを Claude Design のキャンバスに起こし、
作業ファイルを `docs/screens/mockups/` へ格納（README に対応表・更新手順）。コード変更なし。詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-03（3.483.0＝画面棚卸し `docs/screen_inventory_textart.md` の所見20件を全部修正（ユーザー指示
「すべて修正する」）。ホーム: 解消度の括弧を4分岐（必須0で調整が残るとき「必須は解消・調整N件」＝3.480.0 の退行を修正）・
実行中は進捗行だけ・人手不足なしの狩猟では大ボタンを出さない・他の案の要約を常時列挙。分析: 末尾の作成ボタンを撤去し
固定フッターへ一本化・改善の提案を必須>0で自動探索。勤務表: ナビ行を1段化・要確認「Nか所」「違反 k/31日」の単位・
希望反映カードをグリッド下へ・シフト集計を既定折りたたみ。編集: 手順カードは月次で「次の一手」だけ・入力診断をその場で展開・
期間節を「日数」に・並び見出しに族の意味・回数マトリクスの警告を「目標 ＞ 上限」の1式に。設定: 違反種別19色を折りたたみ・
文言2件。すべて表示・文言・導線のみでエンジン不変。詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-03（3.482.0＝ユーザー提示のスクショ18枚検証レポートをコードで照合し、grilling で確定した範囲を
実装。①職員一覧を「職員管理」ドアへ一本化（年間マスター①の職員節と②のスキル割当を撤去、一括追加は職員管理へ移設）
②並び4族を起点シフトごとのチップ（`SeqFamilyGrouped`）へ集約し、同じ並びの重複（族跨ぎも）を追加/変更の入口で拒否
（`seqDuplicateOf`） ③「勤務表をつくる」導線を固定フッター1か所へ（月次チェックリストのボタンと「ホームで作成」案内文を
撤去） ④LOADOUT/ARSENAL/SQUAD/PARTY 等の英語見出しを撤去し日本語のみ ⑤回数マトリクスのセルを2行＋省略記号にして
文字欠けを解消。根本原因（個人×個人のペア制約がエンジンに無い）は次の独立PRへ。エンジン/スコアリングは不変。
詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-03（3.481.0＝勤務表タブの再設計（Claude起案・grillingで「任せる」＝推奨2項目のみ実施）。
①日ヘッダの固定＝縦スクロールで日付行が画面上端に留まる（`MagiFlatGrid` のヘッダを独立行にして本体と `hScroll`
を共有し、ビューポート上端との差分だけ `graphicsLayer` で平行移動） ②週送り/違反ナビを `Scaffold` 下部バーへ
常駐（3.444.0 で保留した state 引き上げを `ScheduleNavState`/`ScheduleNavBar` として実施）。確定済み決定
（違反枠3段階・希望バッジ・集計の位置・違反フィルタ可視・E5/D7/D4）には触れていない。表示・導線のみ＝
エンジン/スコアリングは完全に不変。詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-03（3.480.0＝ユーザー提示の詳細なホーム画面リデザイン案（ASCIIモックアップ付き）を
grilling で5点確定のうえ実装。①診断文（却下N件等）を文言そのままに既定折りたたみへ（`DiagDetailToggle`
新設） ②分析タブの`FixSuggester`を再利用した「AIの解決提案」カード(`SmartActionCard`)を新設し先頭候補を
自動探索・即適用可能に ③「もう一度つくる」の重複3箇所→固定フッター1箇所へ集約 ④「他の案」をボタン列→
`MagiSegmentedControl`のタブへ、位置もホーム上部へ移動（タップ＝即時反映） ⑤「窓の要件」表記を全17箇所
「期間の制約（○日間で○回など）」へ統一。`SettingIssuesCard`に「担当外の希望を一括クリア」ボタン
（`vm.clearOutOfScopeWishes()`新設）も追加。表示・導線の再構成のみ＝エンジン/スコアリングは完全に不変。
詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-03（3.479.0＝実機スクショ提示「職員と日付の集計タブも追加する」を受け、勤務表
タブの「シフト集計」カード(`TallyCard`)に3.477.0で撤去した職員別/日別の切替を復活。3.477.0直前の
実装をそのまま復元（追加コメント以外diffゼロ）し、編集タブの`StaffShiftMatrixCard`（担当可否・目標
編集専用）とは役割を分けて併存させた（`TallyCard`職員別＝確認専用）。表示のみ・エンジン/スコアリング
は完全に不変。詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-03（3.478.0＝ユーザー指示「MagiThemeのFontSize/Typographyトークン適用」を
grillingで5点確定のうえ実装。`ui/`配下9ファイルのハードコード`fontSize = N.sp`63件を
`MaterialTheme.typography.*`へ置換（うち12sp多数は`docs/magi_design_system.md`明文の14sp下限を
下回っており、置換によりアクセシビリティ底上げも同時達成）。密なグリッドUI等の意図的な非標準値6件
（`Ws1Editor.kt`の群×シフトマトリクス・`MagiSetupCards.kt`の±ステッパー）は除外し`design_lint.py`の
新規P11（fontSize直書きのbaselineラチェット）で監視。表示のみ・エンジン/スコアリングは完全に不変。
詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-03（3.477.0＝ユーザー提示案「担当可否・目標設定・実績」統合マトリクスを grilling で
7点確定のうえ実装（Android）。`CountsCard`内の`AptSection`（群×シフトの目標グリッド）と`StaffRangeSection`
（職員別チップ一覧）を`StaffShiftMatrixCard`（`StaffShiftMatrix.kt`）へ統合・撤去。職員×シフトの1マトリクスで
担当可否(—)・実績・目標(apt,薄色)・個人上下限(staffRange,濃色)を一望し、セルタップで群目標／個人上下限を編集。
色は既存M6統一2色(不足=赤系/超過=橙系)のまま、個人単位のapt上書きという新機能は作らず既存代用(lo=hi固定)に
一本化。`TallyCard`の「職員別」モードも本統合で完全撤去し「日別」のみへ。詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-02（3.476.0＝ユーザー提示案「群×シフトの担当可否を2次元マトリックスへ」を Android と
Windows11版へ同時実装。行=群・列=シフトの✓/—マトリクス、左列（群名）固定＋右側だけ横スクロール、セル全面（48dp）
タップで ON/OFF、群名タップで行一括・シフト名タップで列一括。新しい不変条件「休はどの群からも外せない」を
`Ws1Ops.setGroupShiftRow/Column`＋`Ws1OpsMatrixTest` で固定（担当可能シフトの無い群を作らない＝3.418.0 の再発防止）。
詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-02（3.475.0＝ユーザー指示「論理的不具合も修正する」を受け、6角度（CSV取込系／入力診断
V6SanityPort・Problem／後処理研磨／ViewModel状態機械／修復提案・分析／背景実行・最適化入口）へ並列agentを割り
当て候補38件を実コードで検証、実在30件を修正。重いもの＝C1研磨・手R3が-1セルで`ArrayIndexOutOfBounds`し
最適化結果ごと入力へ戻っていたクラッシュ／背景実行の破棄分岐がファイルを片付けず次回起動で別データへ結果が
復元されうる穴／背景のkeep-best比較先が「前回の結果」で手編集後の背景実行が入力へ黙って巻き戻る／必要人数が
未定義の日を「席0」と数える設定ミス診断の false positive（3箇所）／改善提案が古い盤面のまま適用できる指紋
未照合。5件は複雑さ・確度・このサンドボックスでの検証限界（Androidコンパイル不可）から見送りを明記。
目的関数・重みは無改変（HF77）。詳細は `docs/history/3.4xx.md`。）

**最終更新**：2026-09-02（3.474.0＝`/code-review`（8並列finder）で 3.473.0 を自己検証し、実在した9件を全部修正。
**退行1件**＝CSV取込の「読めない行の例」を収集時にキャップしなくなっていた（CSVは任意ファイル＝30名×31日で有界ではなく、
誤ったファイル選択で拒否行ぶんの String を全件確保していた）→収集時に `MAX_SAMPLES` で止め、`ComponentImport` 自身が構築時に
上限を保証する形へ。**既存バグ1件**＝各制約CSVで `Problem` が捨てた行を「取込可」と「読めない」の両方に数えていた
（`連勤,,,` 1行で 取込可1・読めない1）→「読めない」側だけに。仕上げ漏れ＝`parseCsvGuarded` を残り4取込へ適用・
KDoc の付き先ズレ・絶対行番号コメント（3.437.0 で禁止済み）・`HfSwapPolish` の `Problem(state)` 4箇所を `cachedProblem` へ。
前2版（3.472.0＝外部レビュー100件の並列検証で実在4件修正／3.473.0＝`/code-review` 追加改善2件）はこの欄を更新し忘れていた。）

**最終更新**：2026-08-29（CLAUDE.md を「現行ルール（本体, ~300行）」と「版ごとの詳細変更履歴
（`docs/changelog.md`, ~11,800行）」に分割。動機＝5大ファイル（CLAUDE.md/V6HotfixPasses.kt/
V6NativeOptimizer.kt/MagiViewModel.kt/magi_native.cpp）が AI コードレビュー時の対話コンテキストを
圧迫していた件のうち、CLAUDE.md は毎セッション開始時にシステムプロンプトへ丸ごと注入される最大の
圧迫源だったため最優先で対応。D3〜D8・E5の恒久的な業務決定は、元々埋め込まれていた版ログ本体が
移動しても参照できるよう「決定記録（D3〜D8・E5）」節へ集約して本体側に残した。移動時に発見した
既存の書式崩れ（「スキル / 作業の進め方」節の末尾に無関係な日付付き履歴が見出し無しで直接続いていた
手作業ミスの残骸）も同時に是正し、行の欠落・重複が無いことをスクリプトで検証してから書き出した。
Kotlin/C++側の物理分割は別コミットで対応予定。）

**最終更新**：2026-08-30（3.471.0＝分析タブを**スクロール最小の1画面**へ再構築。4層の切り替え
（一般/プロ・一覧/日別・人別/内訳・全部/不足/過剰/窓・族チップ6種）を分析タブから全廃し、上から下へ流れる1本に。
**分類の軸を「族」から「診断」へ変えたのが本題**＝提示されたモックは連勤・並び/窓の要件/曜日の偏りを族の名前だけで
「手動修正は不要」と断定していたが、それは 3.263.0 / 3.322.0 / 3.344.0 で逆向きに直してきた楽観バイアスそのもの
（c1・c3n はデータ次第で最適化では消せない）。grilling でユーザーが「診断で動的にトリアージする」を選択し、
`settingIssues` / `forbiddenDiag` / `c1Plateau` / `coverageDiag` の**既にある結論だけ**を読んで
「直さないと消えない／エンジンが挑戦する」に割る純関数 `AnalysisTriage.kt` を新設（新しい判定は1つも作らない・
観測が無いときは理由を語らない）。同種の設定破綻は1行へ集約し、0件の族は畳んだサマリーへ。
全568テスト green・教訓#30 で「族で決め打ちへ戻すと該当2件だけが落ちる」ことを実行で確認。
前版は 3.470.0＝CLAUDE.md 本体から版数付き作業記録を全部出し固定費 202k→約54k-tok。）

**最終更新**：2026-08-27（3.467.0＝ユーザー指示「ドッグフーディング検証する」を受け、直前の3.466.0（③統合）を
自分でトレース検証。①`CountsCard`外側の余白4dpを内側3節と揃え8dpへ ②より重要な発見: 3.185.0/3.189.0が
確立した「`key(ui.editRev)`配下は editRev の増分だけが再構成を伝える」契約を、兄弟関数`applyStructure`は
守るのに`applyStructureWithMessage`の両オーバーロードだけが editRev を一度も増やしていなかった。呼出元4箇所
（`ws1ResetGroupApt`＝今回統合したAptSectionの「目標を全リセット」ボタン含む）へ横展開して是正。表示・状態
管理のみ・重み/採否/エンジンは完全に不変。design_lint exit=0。）

**最終更新**：2026-08-27（3.466.0＝編集タブ「③ 回数（1人あたり）」を実装から読み直し、ユーザー指示
「冗長性を賢くシンプルデザインに深く考え直す」に対応。`AptCard`/`StaffRangeCard`/`GroupRangeCard`の
別々の3枚のカードを、共通説明を1回だけ言う新設`CountsCard`1枚（`Divider`区切りの3節）へ統合。
各節の重複した「やわらかい/かたい/グループ一括」説明を、その節でしか言っていない具体だけへ削ぎ落とした。
②④⑤の節は3.409.18/3.427.0で既に同種の対応・確認済みと判断し対象外。表示・文言のみ・
重み/採否/エンジン/ViewModel APIは完全に不変。design_lint exit=0。）

**最終更新**：2026-08-27（3.465.0＝covO（人員過剰）の重みを1.0→5.0へ引き上げ（ユーザー明示数値指示・HF77）。
実機ログで「動かせる在勤者がいるのにcovOがhigh(重み45)に阻まれ研磨されない」逆転現象を確認し、apt/fair/
weekly等(重み1)より確実に優先しつつhigh/low/c1/c3mn(45/90/30/30)には遠く及ばない水準へ変更。単一ソース
(`MirrorKeys.weights`)・`Evaluator.fullEvalParts`・`DeltaEvaluator.scoreFrom`・`magi_native.cpp`（フル
評価器＋SA/ALNS/LAHCの全探索が通るホットパス`contribCov`）の4面を同時変更し、言語跨ぎ期待値3ファイルを
Kotlin/C++両方で再計算・一致確認してから更新。apt/fair/weeklyは決定記録D3の対象として別途ゼロから再検討
したが、covOと異なる位置づけの逆転は見つからず変更なしと結論。ホストJVM全563テストgreen・native parity
479万手mismatch=0。）

**最終更新**：2026-08-26（3.464.0＝外部レビュー(対象3.462.0)を検証し2件を修正。①`ViolationHubCard`の
達成表示が`fair`/`weekly`のみの違反（場所を持たない集計指標のため`confirmItems`の母数に現れない）を
「達成」と誤表示していた穴を、フィルタ前の`ui.breakdown`を併用する判定へ修正②内訳タブの展開パネルが
族フィルタOFFで隠れた族に対しても開いたまま残る穴を`LaunchedEffect(ui.breakdown)`で自動解消。
両方とも実コードを確認してから対応（`receiving-code-review`規律）。表示・UI状態のみ・エンジン不変。）

**最終更新**：2026-08-26（3.463.0＝ユーザー指示「AB評価」を受け`wideC3nBreakDays`（禁止連続の崩し範囲
トグル）の既定ON化を最終判断。240秒帯（正式ペア計測2ラウンド・3.409.24/3.449.0の計15ペア）はON7・OFF7・
引分1で完全な五分。AUTOがPORTFOLIOを選ぶのは予算211秒以上＝240秒帯こそ実利用の主経路で、3.456.0の
90秒帯ON優勢(21ペア中13勝)はAUTO経由では到達しない非代表条件かつその帯唯一の実データ(sept2026)自体も
OFF寄り。既定OFFで確定・再提案しないと決定し`docs/algorithm_portfolio.md`を更新。コード変更なし・
docs+versionのみ。）

**最終更新**：2026-08-26（3.459.0＝分析タブ3枚（要確認一覧/日別・人別/違反の内訳）を`ViolationHubCard`へ
統合し、勤務表タブと共有する違反フィルタ(E7の6バケツ)を「一覧／日別・人別／内訳」の3ビューへ一様に効かせる。
`applyVioFilter`(新設・Compose非依存)＋`ViolationBucketChips`(チップ行の共有化)。旧3カードの中身は
`ConfirmListBody`/`AttentionBody`/`BreakdownBody`へロジック不変で移動。ユーザーの「本格的に統合する」
「(フィルタを)有効活用してほしい」への全面委任対応。表示のみ・スコアリング/エンジンは完全に不変。）

**最終更新**：2026-08-25（3.458.0＝実機スクショ3件対応。①設定タブ「シフトの表示色」チップの通常枠を
選択式に（既定=表示、`plainBorder`パラメータ・他呼出元は非破壊）②折りたたみ凡例(`ViolationLegend`)に
希望バッジ（桃バッジ=未反映/緑リング=反映済み）の説明を追加③勤務表グリッド常時キャプションを
「凡例は『検索・凡例』へ」に短縮し②と重複していた全文を解消。表示のみ・スコアリング不変。）

**最終更新**：2026-08-25（3.457.0＝勤務表グリッドの通常セル（無違反）1dp輪郭の表示有無を選択式に。
既定は非表示（ユーザー明示指示）。設定タブ「外観」に片手モードと同型のSwitchを追加し
`MagiApp`→`ScheduleGrid`→`MagiFlatGrid`→`FlatCell`へ素通し。表示のみ・スコアリング不変。）

**最終更新**：2026-08-25（3.456.0＝既定OFFの3トグル(`filterC3nIncrease`/`normalStallFraction`/
`wideC3nBreakDays`)をサンドボックス上で再測定。filterC3nIncrease/normalStallFractionは文書の
「再測定不要」を完全再現。wideC3nBreakDaysは予算90秒(過去3ラウンドの240秒とは別条件)で24ペア測定し
決着21ペア中ON13勝(62%)とやや優勢だが、既定は変更せず`docs/algorithm_portfolio.md`へ記録のみ。
コード変更なし・docs+versionのみ。）

**最終更新**：2026-08-25（3.455.0＝ユーザー提示の11シフト配色案(A4/Aｱ/B4/B1/Pｼ/Cｵ/Cｱ/Dテ/休/有/F1)を、
記号一致するテスト用サンプルデータ4件(`golden_state.json`/`sample_state_v6.json`/`blocked_covu_state.json`/
`sept2026_state.json`)の`shiftColors`へ適用。表記ゆれ(Dﾃ=Dテ)は同一視、対応不明な記号(希≠F1)は適用せず。
`shiftColors`はStateFingerprintが明示的に除外する表示専用フィールド＝native-parity/診断/採点は完全に不変。）

**最終更新**：2026-08-25（3.454.0＝色ピッカーをユーザー手指定の別の25色表へ再び全面差替え。3.453.0と
同型の3制約衝突（アンカー未含有・隣接距離8.3%(要件25%未達)・彩度0.00〜1.00(既存上限0.60超)）を検証。
アンカー枠1色のみ差替え・残り24色は指定値のまま全域ヒルクライム局所探索で再配置し隣接距離39.7%を達成、
彩度上限の撤回は3.453.0で決定済みの方針をそのまま継続適用。表示のみ・エンジン/採点は不変。）

**最終更新**：2026-08-25（3.453.0＝色ピッカーをユーザー手指定の25色表へ全面差替え。検証でアンカー
(`#E08A1E`)未含有・隣接距離9.1%(要件25%未達)・彩度0.16〜0.94(既存上限0.60超)の3件の制約衝突を発見。
アンカー枠1色のみ差替え・残り24色は指定値のまま全域ヒルクライム局所探索で再配置し隣接距離35.6%を達成、
彩度上限はこの改訂に限り明示的に上書き（ユーザーの意図的な鮮やかな配色選択とみなす）。表示のみ・
エンジン/採点は不変。）

**最終更新**：2026-08-25（3.452.0＝色ピッカーの隣接ペア差異を15%→25%へ強化。旧・全探索(5!)方式では狭い
色相帯の制約下で17.6%が上限だったため、色相帯を広げヒルクライム局所探索（隣接距離最小値を悪化させない
移動のみ受理）で再設計。全25色の隣接ペア距離が25%以上（最悪27.5%）を達成、彩度上限0.60（ネオン調回避）と
`#E08A1E`固定は既存の制約を維持。表示のみ・エンジン/採点は不変。）

**最終更新**：2026-08-25（3.451.0＝3.450.0（largeHeap）に対しユーザーが「根本的に修正する」と追加指示。
後処理HF80戦略的振動の内側探索`localBestImprovement`が候補ごとに`UnifiedViolationChecker.check()`
（重いViolationReportを毎回アロケート）を呼んでいたのを`Evaluator.fullEval()`（packed Long、アロケートなし）
へ置換。呼出元の外側採否ゲート（check()+isBetter）は無変更のため最終品質は理論上も退化せず、3データセット
のA/B実測でも新旧実装が決定的ベンチでバイト一致することを確認。largeHeapは二重の安全網として維持。）

**最終更新**：2026-08-25（3.450.0＝3.449.0で見つけた12月データログのOutOfMemoryErrorを調査。原因は
後処理HF80戦略的振動（`UnifiedViolationChecker.check()`を1回のパスで1,000回超呼ぶタイトループ）が
既定（largeHeap未設定）ヒープ上限256MiBを、6回連続の300秒PORTFOLIO実行の蓄積の末に使い切ったもの。
`catch(Throwable)`で捕捉済みでアプリはクラッシュせず失敗をログ報告していたが、根本のヒープ上限を
`android:largeHeap="true"`追加で緩和。設定変更のみ＝エンジン・重み・探索ロジックは不変。）

**最終更新**：2026-08-25（3.449.0＝ユーザーが実機ログ2本＋その基データのstate.jsonを直接アップロード。
`wideC3nBreakDays`（禁止連続の崩し範囲トグル）を`docs/algorithm_portfolio.md`が要求していた
「4件目以降の実データ」で初めて再測定。職員名＋グループ名を匿名化のうえ`sept2026_state.json`として
新規fixture化（匿名化前後のbit一致をホストJVM実行で確認）し、6ペア(12run)のPORTFOLIO A/Bを実施。
結果はON2勝/OFF3勝/引分1（weighted差0.5%・HARDは12run全て0で不変）＝符号不一致のため
「既定OFFで確定的に据え置き・再測定不要」を確定。副次的に、同一データの実機ログでも同一設定の
連続実行だけでHARDが振れる実例を確認し単発ログ比較が信頼できないことを裏づけ。併せて別セッションの
12月データログでOutOfMemoryError（HF80戦略的振動フェーズ中）を確認し、今回のA/Bとは独立した別バグとして
記録。ホストJVM555テストgreen。エンジン・重み・探索は不変（測定・fixture追加のみ）。）

**最終更新**：2026-08-25（3.448.0＝ユーザー提示のスクショ2枚から色ピッカーを再設計。「休」の色ピッカーで
隣接スウォッチの淡色差が小さい指摘（各15%以上の差異を要求）と、「人員不足」の色ピッカーで警告色
（`MagiAccent.orange=#E08A1E`）が実選択できない指摘（同一の`ColorPickerDialog`を共有）に対応。
farthest-point-selection＋隣接順序全探索で25色パレットを再設計し、`#E08A1E`を実スウォッチとして含め、
水平隣接ペアの最小距離を17.6%（要求15%以上）まで確保。表示のみ・スコアリング/エンジンは完全に不変。
design_lint exit=0（P2/P4ともbaseline不変）。）

**最終更新**：2026-08-25（3.447.0＝対象コミット328b059への外部レビュー5件を全件実コードで検証し全件実在と
確認。背景計算の入力保存・「開く前のデータ」退避の非原子書込み2箇所を`RunFiles.writeAtomically`へ統一、
`enqueueUniqueWork()`の未捕捉例外に所有権ロールバック付きの防御を追加、`StateParser`のJSON配列不正要素の
無言スキップを明示例外へ変更（17呼出）。release署名は既存の意図的製品判断と確認し対応せず。あわせて
`docs/algorithm_portfolio.md`の見直しの条件に基づき`normalStallFraction`をblocked_covu型に絞って12ペア
追加測定＝第1ラウンドの3/3一貫はn=3のサンプルノイズと判明、効果は再現せず既定0.9のまま確定。ホストJVM
553テストgreen。）

**最終更新**：2026-08-25（3.446.0＝3.445.0の続きで提示された2表（13+15項目）をreceiving-code-review規律で
全件再検証。`DeltaEvaluator.previewMove`のdWeekly自己復元ブロックにも3.445.0と同型の例外安全性ギャップを
自力発見・修正（try/finally追加）。残り全項目は実コード確認・不変条件の証明・既存テストチェーン
（ObjectiveParityTest/DeltaEvaluatorTest/NativeParityFixtureTest+native-parity CI）の存在確認により
根拠つきで対応不要と結論。ホストJVM550テストgreen。HF77非該当。）

**最終更新**：2026-08-25（3.445.0＝ユーザー提示の外部レビュー8件（🔴高3/🟡中3/🟢低2）をreceiving-code-review規律
どおり全件実コードで検証。実在の欠陥は1件のみ（`DeltaEvaluator.previewMove`の一時盤面書換え窓にtry/finally
なし）で修正・ホストJVM550テストgreen。残り7件は「既に対応済み(重み同期documentation/文書)」「意図的トレード
オフとして検討済み(commitロールバック/ProblemCacheキャッシュ)」「過大な主張(SCORE_HARD_UNITオーバーフロー＝
実際は fail-loud 設計・試算も1e9の約400分の1)」「既に grilling で却下済み(ファイル分割2件)」「低深刻度で妥当
(fairDevAtのO(m))」のいずれかで対応不要と判断・根拠を明記。HF77非該当。）

**最終更新**：2026-08-25（3.444.0＝ユーザー提示の勤務表グリッド再設計案をAskUserQuestionで既存の意図的判断
（違反枠3段階・希望バッジの記号表示・TallyCardの配置）と突合し3点は現状維持を確認、唯一の実施項目
「週送り/違反ジャンプを画面下部（サムゾーン）へ移動」を`ScheduleGrid`内で実装（新規state無し・純粋な並べ替え）。
併せてセルタップ時の行列クロスハイライト（職員名の行/日付の列を約2.5秒強調）を追加し読み間違いを防止。
design_lint exit=0（P2/P4/P10 baseline不変）。）

**最終更新**：2026-08-25（3.443.0＝ユーザー提示の「シフト集計」修正版モックアップと現行`TallyCard`を突合。
ヘッダー列・不足/超過バッジ色・0セル空白表示・凡例は既に一致を確認。バッジセル（押せる集計セル＝
`onClick != null`）の角丸を extraSmall(10dp)→extraLarge(24dp、既存トークン)へ拡大しピル形に近づけ視認性を
改善（新規の任意角丸値は導入せずP4 lint対象外）。職員名の隣の⚠️アイコンは同じ行のバッジと情報が重複するため
見送りと判断（3.129.0/3.396.0の重複表示回避原則）。design_lint exit=0（baseline不変）。版番号は当初3.442.0を
名乗ったが、並行セッションが同じ番号を別内容（CSV/JNI検証、下記）に使用済みと判明し3.443.0へrenumber。）

**最終更新**：2026-08-25（3.442.0＝並行セッションの3.438.0が同じドッグフーディングレポートから
C1(Worker)/H2/M2の一部を解消したのを受け、残る3件を実装。①CSV取込の追加行が「その群が休を担当できるか」を
見ずに休で埋めていた＝担当外の群では行まるごとgroupViol(HARD 10000)になる穴（main側は「再現しない」と
判断したが、修正を戻すと落ちる回帰テストで反証）②JNI入口の制約index検証にcons1/cons2が抜けていた
（cons1.siは`ssn`への書込index＝範囲外はヒープ破壊・cons1.d1<=0は`1ULL<<d1`のUB。ガード検査を6→12ケースへ）
③`stop()`の押した側でも`OptimizationRepository.running`を降ろす＋重み15→30のコメント残り18箇所。
検証＝ホストJVM 550テスト green・native parity 4,794,967手 mismatch=0・実データ3件は既知値と完全一致。
前版は 3.441.0＝ユーザー提示の勤務表タブ画像を熟読し、土日・祝日の列色分けと祝日データの
外部ファイル化を実装。祝日は`tools/generate_japan_holidays.py`が祝日法（国民の祝日に関する法律）の規則
（固定日・ハッピーマンデー・春分秋分の近似式・国民の休日・振替休日）を一般ロジックとして計算し
`app/src/main/assets/japan_holidays.json`（2026〜2036年・196件）へ出力。2026年9月22日が「敬老の日」と
「秋分の日」に挟まれた実在の国民の休日インスタンスであることを手計算とスクリプトのassertで確認済み。
`JapanHolidays.kt`がプロセス内キャッシュで読み込み、`MagiFlatGrid`の日ヘッダ（データセルは対象外）へ
祝日=日曜と同色（日本の慣行）の背景タグ＋文字色を適用、3.125.0と同型のコントラスト担保
（`compositeOver`+`ensureReadable`）付き。画像との差分列挙のうち凡例意匠(⚠NG/⚠要注意/★希望の簡易2段階
表記)は3.99.0の3段階飽和回避設計を上書きする独立判断のため今回は見送りと記録。design_lint exit=0
（baseline不変）。
2026-08-25（3.440.0＝ユーザー指示「賢く深く考え修正する」で3.438.0のM8/M9を解消。
M8=`saveNow()`のメインスレッド同期I/Oは、呼出元がonStop/onPauseのプロセス破棄直前区間のため**意図的な
設計**（非同期化するとディスパッチしたコルーチンが走る前にプロセスが死にうる＝機能そのものが壊れる）と
判断し非同期化せず、想定閾値(100ms)超過時のみ操作ログへ記録する観測性強化に留めた。M9=`allowBackup`を
`true`→`false`へ（filesDirの自動保存/スナップショットは職員氏名・勤務割当を含む実データで、adb backup／
Auto Backupの両経路でアプリ外へ複製されうる。アプリ自身がJSON/CSV入出力という明示的な移行手段を持つため
機能損失なし）。実装時にAndroidManifest.xmlの開始タグ内へコメントを誤挿入しXML不正になる事故を
`xml.dom.minidom`実パースで検出・是正。design_lint exit=0（baseline不変）。
2026-08-25（3.439.0＝ユーザー提示の勤務表タブ目標デザイン画像へレイアウトを合わせる。
grillingで①目標デザイン（現状screenshotでない）②違反凡例はモックアップのカジュアルな言い回し
（絶対NG／できれば直す）に合わせる＝3.133.0用語統一を本箇所限定で明示上書き、を確認。差分は週ラベルへの
年月併記（`ScheduleGrid`の週ページング、表示中週の初日基準で計算）と、`ViolationLegend`＋キャプション＋
セルcontent descriptionの計3箇所の凡例文言変更（実線/破線/角マークの3段階構造自体は3.99.0のまま不変）の
2点に限定。他タブの「必須違反」「要調整」表記(breakdownLabels等)はスコープ外＝3.133.0のまま。表示・
文言のみ・スコアリング不変。design_lint exit=0（P10 baseline=2不変）。
2026-08-24（3.438.0＝ユーザー貼付の外部レビュー文書を`receiving-code-review`規律で
1件ずつ検証。最重要（C1）＝背景最適化を停止(cancel)すると`OptimizationWorker`の`CancellationException`
処理が`releasedByMe`を立てないまま所有権マーカーを削除し、`finally`の`setRunning(false)`条件が
両方falseで一度も呼ばれず`OptimizationRepository.running`が恒久固着＝画面は実行中でなく見えるのに
編集・Undo/Redo・新規実行が理由なく拒否され続ける実バグを発見・修正。H2＝`applyWishes`/
`applyAlternative`に`setCell`等と同じ実行中ガードが無かったのを追加。M2＝c1/c3mn重みの実装(30)に対し
コメント7箇所が旧値15のまま＝ドリフト是正。H3(fillShiftIndex)は既に一本化済みと確認、H5/M8/M9は
既知の記録済みギャップ/意図的設計/製品判断として据え置き。3.437.0＝MagiViewModel.ktのコメント4件がコード内の別位置を絶対行番号で
参照しており、うち3件は既に指し先がずれていた（3.368.1/3.409.9と同型のドリフト。1件は3.435.0で自分が
書いたばかりの再発）のをタグ/関数名ベースの参照へ統一。「mainにマージする」を受けブランチを全数調査した
結果、未取り込みの有用な内容は無し（既評価・選択的移植済み or 実測で否決済み）と確認して監査へ復帰。
3.436.0＝必要人数(need1/need2)の3編集面(年間マスターのシフト追加/編集・必要人数
カレンダーの基本設定・日別例外)のうち2面に下限>上限の入力時ガードが皆無だった（3.403.0がcons41/staffRange
の4面へ入れたのと同じ穴の対象漏れ）＋唯一ガードを持つ日別例外面もヒント文言が自分のフィールドラベルと
不一致だった、を修正。共有関数V6SanityPort.rangeOrderConflictを単一ソースに統一。自律監査（design-review
の観点を手動適用）の継続で発見。3.435.0＝勤務表づくり完了メッセージが生の内部識別子（"optimize:PORTFOLIO"等）を
画面に出していた（3.400.0が別経路で既に除去した同型漏れの取りこぼし）＋開始/完了メッセージの語彙不一致
（最適化/ソフト研磨→整え）＋仕上げ最適化の失敗メッセージが生の例外文を出していた、の3件を修正。
3.434.0＝職員削除の確認ダイアログに「勤務・希望も消えます」
警告が無いドアがあった問題を修正。同じ削除操作(vm.ws1RemoveStaff)なのに年間マスタードアと職員管理ドアで
警告内容が違っていた。3.433.0＝シフト/グループ削除ダイアログの参照件数開示をスキルグループと同じ「独立した
文」の形へ統一＋開発者向け内部語「インデックス」の言い換え。
表示のみ・エンジン/採点は不変。design_lint exit=0。
3.431.0＝勤務表タブの「違反セル：…他75件」棒読みリストを撤去（ユーザー指示「冗長を
賢く見直す」）。タップ不可・直下の違反ナビと役割重複・件数の母数が要確認N件と食い違う、の3点で貼り紙と判定。
表示のみ・エンジン/採点は不変。design_lint exit=0。
3.430.0＝色ピッカーを20色(5×4)→25色(5×5)へ拡張（ユーザー指示）。既存4行は不変・
5行目に濃灰/茶/深緑/インディゴ/辛子色の5色を追加。表示のみ・エンジン/採点は不変。design_lint exit=0。
3.429.0＝外部100件レポートの「元100件表の外」4件（R-01/R-02/R-03/R-05）を検証。
R-01/R-02（記号重複改名）は既に3.411.0で対応済みと確認、R-05（非数値の黙示無制限）は既知の意図的据え置き。
R-03（削除済み参照の評価対象外置き去り）は削除確認ダイアログへ参照件数を表示する形で対応
（Ws1Ops.shiftRefCount等新設。削除の可否・挙動は不変・情報を足すだけ）。ホストJVM 544テストgreen。
※versionCode 629/「3.428.0」は同時期の別セッション（100件レポート本体70件を検証・実在4件を修正）が
先に占有していたため 3.429.0 へ renumber（3.421.0/3.425.0 と同型の衝突）。
3.428.0＝**100件レビューの未確認70件を全部当てて実在4件を修正**。①`RunFiles.clear` の消し残りを
読んでいたのは1箇所だけで残り9箇所は捨てていた（3.410.0/B-06 の契約の取り残し）＝停止や失敗が
次回起動で「中断＝再開できます」と誤案内されるのに痕跡が残らない ②前景サービス化の失敗が無言
③原子置換を諦めたことが残らない ④`hf66DataHardening` だけ埋めシフト規則が `fillShiftIndex` の写しの
まま（休が先頭でないデータで勤務シフトへ倒れる。実データでは no-op と測ってから委譲）。
**実装中に既存テストが危険を捕まえた**＝`onNonAtomic` を `commitGuard` の後ろに足したせいで、本番2箇所の
末尾ラムダ `{ ownsFiles() }` が束縛先を変え **3.385.0 の TOCTOU 再確認が既定 `{true}` に落ちていた**。
末尾R表4件は全部すでに閉じており表自体が stale。539テスト green。
3.427.0＝ペア禁止の行を「吉の休 ✕ 古の休」の読める形にして行下の
読み下し文を撤去（タイトル＋見出しとの完全重複＝7行×2行→1行）。④⑤の列挙型SectionNote・カード見出しの
括弧補足も直下の族見出しとの重複として整理。表示のみ・スコア不変。
3.426.0＝版数の重複ラベルを全数走査。真の衝突は 3.173.0 の1件
（need2修正と Android 17 バブルが同じ 327/3.173.0 を名乗る並行セッション衝突・git で確定）＝出荷済み履歴は
renumber せず注記で正史化。build.gradle の stale「API 37 未提供」コメントも 3.409.12 の実測へ更新。
3.425.0＝未マージブランチ `12ysbf`（旧PR#111 head）の後始末を選別統合。
版番号取り違え10箇所（記号中立化=3.417.0 の作業コメントが `[3.415.0]`=改名ガードの番号のまま）を実番号へ、
CLAUDE.md の節順・3.416.0 の stale 行（休セル淡色化「維持」→実際は3.417.0が撤去済み）を是正、
`magi_design_system.md` の広範な実装乖離（色表HEX・Shapes全値・§5の存在しない部品9/19等）を実測値へ、
design_lint P8 を地の文の「`名前`✅」へ拡張。ブランチは 3.421.0 を名乗り main と衝突するため
3.421.0 の前例どおり盲目マージせず renumber（→3.425.0）して統合。docs/コメントのみ・挙動不変。
3.424.0＝/code-reviewが3.422.0に検出した3件を全件修正。①3.422.0の
searchWindowMs基準へのrebaseは「帯の外はビット単位で同一」という主張に反し、到達可能だった帯まで
無計測で厳格化していた（300s予算: stallMs 270s→247.5s＝−8.3%等）→ stallHardMs/phaseGraceMsは
budgetMs基準へ完全復元、stallMsは「予算×割合を基本・発火し得ない帯（≦80s）だけ探索区間×割合へ
フォールバック」の条件式へ＝修正帯の外は旧挙動とビット一致・60s帯はA/B済みの挙動を維持・ノブは
大予算帯でも有効。②`normalStallFraction`へ入力検証（(0,1)排他・有限のみ＝fraction≥1.0は到達不能バグの
再現・NaNは20秒床への崩落）。③コメントドリフト3件（絶対行番号参照含む）。ホストJVM全539テストgreen・
教訓#30実践＝初版ロジック注入で基準依存の3テストだけが落ちることを確認。
3.423.0＝3.422.0のA/Bフォローアップ。18run(3fixture×2条件×3反復)の結果、
全9ペア符号検定6勝3敗(p≈0.25＝有意でない)。ただし入口HARDが構造床より高いまま留まる唯一のfixture
（blocked_covu_state）だけは3/3全反復が品質・速度とも一貫して有利（かつHARDは4→4不変＝歴史的後悔は
再現せず）＝信号は機構的に筋が通っているがサンプルが薄い。既定を変えるだけの決定力は無いと判定し
`normalStallFraction`は既定0.9のまま据え置き（ノブ自体は削除せず残す）。コード変更なし・docsのみ。
3.422.0＝ユーザー報告「停滞の早期終了が実質効いていない」への対応。
Part A（正しさの修正・A/B不要）＝停滞監視の閾値(`stallMs`等)が生の予算(budgetMs)基準のままだったため、
後処理予約枠の下限クランプが効く中程度の予算帯（実測60秒）で閾値が実際の探索区間を超え、**基準閾値自体が
到達不能**になっていた（3.410.0のE-14＝上書き倍率の到達不能、よりも重い症状）。算出順序を実際の探索区間
(`searchWindowMs`)基準へ統一し、実測（54s>52s→46s<52s）で到達可能になったことを確認。Part B（A/B用インフラ・
既定値は不変）＝`stallMs`算出を純関数`normalStallMs`へ抽出し`PolishGate.normalStallFraction`（既定0.9＝
旧値と同一）を新設。**A/B自体は本コミット時点で未確定**（18run測定中・採否基準は事前固定・結果は
次バージョンで確定）。検証: ホストJVM 全537テストgreen（+4新規）・design_lint 0/2件・C++無変更。
3.421.0＝別ブランチ発の PR#110（`tools/native/host_parity_bench.cpp` の
テスト/CI強化）を受領。ブランチ間で版番号3.366.0/3.367.0が main の別内容と衝突していたため cherry-pick
はせず、fork点との差分が無いことを確認したうえで内容を手動再適用。①スレッド生成の例外安全化
（生成中の例外で `std::terminate()` しないよう join してから再送出）②`out[0]`(自己整合番兵)を独立検証
してから比較（serial/parallel が同じ status!=0 に偶然一致しても「一致」と呼ばない）③`--expect` と
flat引数の件数不一致を事前検証してfail-loud化 ④`--shared-only` が flat引数無しで無言成功していた
バグの修正（0件ガード）⑤スレッド数の名前付き定数化・ラムダキャプチャの絞り込み・比較ループの単一化。
専用CIジョブ `shared-handle-tsan`（TSANビルドで `--shared-only` を実行、~5秒）を新設し、
`runSharedHandleConcurrency` のコメントが元々主張していた「TSANでビルドすれば検出される」を実際に
CIが継続的に担保する形へ格上げ。検証: 実ビルド+実行で全5シナリオ（通常フルラン・`--shared-only`単体・
`--shared-only`+flat無し・`--shared-only`+`--expect`同時・`--expect`件数不一致）を確認、TSANビルドで
競合なし、通常フルランは golden/sample_v6/blocked_covu の3フィクスチャとも MATCH・4,794,967手・
0 mismatches で不変。test/tools/CIのみ・engine/重み/スコア不変。3.420.0＝`design_lint.py` P10（3.417.0で新設）が baseline超過(3件)を検出。
3件目は `Ws1Ops.removeShift` 自身の `newRest` 計算が `restShiftIndex` へ委譲せず記号比較を再実装して
いたもの（3.416.0由来）。`restShiftIndex(state.copy(shifts=shifts))` へ委譲し baseline 2 に復帰。
3.419.0＝同じ穴が**探索の入口**（`Problem.initialAssignment`）にもあり、さらにその
1行上で欠損セルがハードコードの index 0＝勤務シフトへ黙って化けていた。埋める規則を `fillShiftIndex` の
1箇所に集約し `Ws1Ops` と共有。初期解生成2種は掃討の結果**健全**と確認。3.418.0＝**空きマスを「担当できない
シフト」で埋めていた件**。`Ws1Ops` の
職員追加・期間延長・シフト削除が、埋めるシフトを一律「休」で決め担当可否を見ていなかったため、担当可否から
休を外した群に職員を足すと**その行の全日が groupViol(HARD)** になった（31日なら1クリックで必須違反31件）。
実データ3件は全群が休を担当できるので**結果は不変**。3.417.0＝**記号の字面でシフトを分類・除外する経路を撤去**。表示色のカテゴリ推測
（「休/夜/早/遅/日」を含むかで色を決めていた）・グリッドの休セル淡色化・「希」を割当先から外すガード3件。
「希」ガードは実データで**1686回発火するのに外すと結果がバイト一致**（弾いていた候補は目的関数側でも全て
負けていた）かつ**探索本体には同じガードが無く方針として機能していなかった**、と実測してから撤去。
受領差分のうち `restIdx` 全廃・修復オペレータ書換・休関連診断の削除は、no-op 化／ABI 据え置き／
計測付き決定の巻き戻しになるため**採らない**（根拠は CLAUDE.md）。再発は `design_lint.py` の P10（baseline 2）
で機械的に止める。3.416.0＝ユーザー方針「**休は通常のシフト定義**」を main 全体へ適用——評価層
（Evaluator/Δ/checker/C++）は 3.345.0 以降 restIdx 参照ゼロ＝既に統一済みと監査で確定し、残っていた
編集ガード2つ（3.106.0 の削除禁止・3.415.0 の改名禁止=R-04）を撤廃。削除の fill 式は「削除後の一覧で
解決した既定シフト」へ差し替え。3.415.0 外部レビュー撤回文書のR-01〜R-05を照合。R-01/R-02は3.411.0で・
R-03は3.309.0のunresolvedRows案内で・R-05は3.327.0/3.328.0の検査2hで既に対応済みと確認。**R-04
（休シフトの記号改名に入口ガードが無い）だけが実在**——`ws1EditShift`へ削除と同じ姿勢の入口ブロックを
追加（後に3.416.0の方針転換で撤回）。100件レビュー自体は先行表を「不具合として不正確」と自ら撤回する
結果になった。以下は 3.412.0 の記録: 100件レビューの照合を **56件まで**進め、実在した項目を順に修正。3.411.0＝

記号の改名で制約が黙って合流する穴（`editShift`/`editGroup` が既存記号との衝突を拒否しない）・担当できるシフトが
1つも無い群（どう組んでも必須違反が残る）・自動保存の失敗の握り潰し・取込のサイズ上限・Gradle zip の
チェックサム。3.412.0＝**期間より長い窓の要件が評価も警告もされずに消えていた件**（`c.day1 > p.T` を無言で
`continue`。連続パターンは検査2d が案内するのに窓の要件だけ取り残されていた）・停止経路だけがバブルを片付けて
いなかった件・`areBubblesAllowed` が定義だけで戻り値を誰も使っていなかった件。**照合した56件のうち16件は
「確定」とすべきでなかった**（既修正・事実誤認・意図的で記録済）一方、レビューが正しく実際に直したものが25件。
**残り44件は未検証**＝判定を書ける根拠を持っていないので、そう数える。以下は 3.410.0 の記録:
外部レビュー100件のうち **30件を実コードに当てて**検証し、実在した
15項目だけを修正——**16件は確定として扱うべきでなかった**（既修正2・事実でない6・意図的設計8）。直したのは
PORTFOLIO だけコア数クランプが無かった件（設定16ワーカーで8コア機が希釈）・`runAlnsChains` が 3.376.0 の
兄弟キャンセル撤廃から取り残されていた件・`groupIdx` の範囲検査欠落（`skillIdx` の検査2i と非対称）・
勤務表CSVの未知記号が黙って休になる件・入力/途中経過の非原子書き込み・背景結果に run 識別子が無い件ほか。
実データ3件の後処理研磨は HEAD と完全一致＝正しさは不変。3.409.25 トグル A/B の第2ラウンドを 240s で測り切った——立て直し方は
24ペア通算 ON7/OFF17（p≈0.032）で **OFF が有意に良い**＝削除は実測に支持されていた／ロール内並列SA は
6ペア ON3/OFF3 で事前バーに届かず削除確定（ただし第1ラウンドの「反復希釈」は 240s では再現せず、根拠を
中立の方へ置き直した）／崩し範囲は 9ペア ON5/OFF4 で既定OFF据え置き。3.409.24 回避の並び(c3mn)と窓の要件(c1)の重みを 15→30（HF77 明示指示）——
`MirrorKeys`/`Evaluator`/`DeltaEvaluator`/`magi_native.cpp` の4面と言語跨ぎ期待値3ファイルを同時更新
（golden soft 3109→4999・sample_v6 825→930・blocked_covu 1681→2731・hard は不変）。
併せて重み変更が露呈させた C1広域ビームの実バグを修正——`bestEver` にピン検査が無く、あとから来た
ピンを崩す候補が先に見つけたピン安全な改善を追い出していた。3.409.23 並列監査の残り2件——c1 壁判定の非休側が「上限未設定＝上限0」と読み、
一部の日だけ need を設定したシフトで不足量が過大に出ていた（前提が崩れているので案内しない形へ）／
`contribDayGroups` のビット経路が制約 index を無検査で、天井は2層番兵で捕捉できない SIGSEGV＝
`nativeCreateProblem` の入口で一括拒否（判定は harness から叩ける `consIndicesValidN` として JNI の外へ）。
3.409.22 ネイティブ修復器が Kotlin から5世代ぶん取り残されていた——
`magi_native.cpp` の destroy-repair / findCovOFix が 3.266.0(同点抽選)・3.267.0(weekly/fair 費用)・
3.319.0(canDo ガード)・3.369.0/3.379.0(`covUCell`/`covOCell` への委譲) を1つも反映しておらず、
**ネイティブ既定 ON なので直したはずの規則が既定経路では効いていなかった**。2層番兵も
native-parity CI も**スコアの一致**しか見ないため、候補生成の乖離は構造的に網から外れる
（harness に修復器の直接テストを2本追加してこの穴を塞いだ）。事前診断(V6SanityPort)の9箇所も
同じ `need1` 直読みを実効値へ委譲。3.409.21 既定OFFトグル2つを単体 A/B で測って削除——
`portfolioRoleParallelSa`（ロール内並列SA）は 15ペアで ON7/OFF8＝中立、しかも ON は反復数中央値が
2/3データセットで**低い**＝チェーン分割が希釈になっていた（動機だった「1本=遊休」仮説の反証）。
`adaptiveEscapeControl`（停滞脱出の適応制御）は ON5/OFF10＝3.306.0 に続く2度目の中立。
「見直しの条件」（3.384.0）の事前約束どおり**機構ごと削除**（規律7＝反証されたコードは残さない）。
経緯と数値は `docs/algorithm_portfolio.md`「廃止・統合済み」。3.409.20 C41/C42/C41s/C42s の説明を
別々の制約として書き分け（分類の明文化・兄弟の名指し・テストで固定）。3.409.19 /design-review で
自分の 11sp を 12sp へ是正。3.409.18 ペア禁止の説明を人間に分かる形へ——「禁止/不可」が実態（最軽量の
ソフト条件）と逆の約束をしていたのを見出しで正直化・ⓘへ「向き」（違うシフトの組は鏡の2行が必要）を追記・
ルール行に読み下し文・スキル群が「いま効いていない」ことの状態表示。根拠は実機で2回聞き返されたこと自体。
3.409.17 2本目の実機ログで**予算300秒の実行が13回中5回、474〜959秒まで超過**
しているのを発見。W4 epoch3 の最良更新が経過474sに出た＝ロールが停止確認を数百秒無視した証拠。手元の
実データでは再現せず（120s/300s とも予算内）＝9月データ固有。原因特定の代わりに観測を入れた——
エポック超過の役割名つき検出 `[W] エポック超過` と、予算超過の実行の内訳(TIME/エポック超過/後処理パス別)を
**操作ログへ写す**（診断ログは最後の実行しか残らず、今回まさに5回分の内訳が全部消えていた）。
3.409.16 実機ログ(3.409.14搭載機)の検分＝表示の自己矛盾3件を修正——
「探索締切」の正常終了を「締切前離脱」と数える集計／「設定の効き:」の二重ラベル／C1広域ビームの
採用ログに weighted が無く正しい取引が退行に見える。実機では adaptiveEscapeControl の初観測(91回)・
portfolioRoleParallelSa の配線実動作ほかを確認。以下は前回まで: 3.409.5〜3.409.7 デザイン lint のラチェットを実際に効かせる。3.409.5 で
P2「生 hex」/P4「任意角丸」が**baseline を記録しておらず、増えても exit 0 で静かに通っていた**のを
ラチェット化（`docs/DESIGN.md` §4 が「禁止事項（machine-checkable）」と呼ぶのに強制されていなかった）。
3.409.6 で据え置いていた13件を `MagiTheme` の tier へ寄せて **baseline を 0/0**＝以後は1件でも増えたら
落ちる（8dp→`extraSmall`・16dp→`large`。**視覚は +2dp 変わる**）。あわせて「落ちる検査を1つ報告して
止まる」欠陥も修正。3.409.7 で `breakdownLabels`（族→日本語名）を Compose 非依存ファイルへ切り出し、
`MirrorKeys.all` と過不足なく一致することをテストで固定＝族を足して書き忘れると**生の英字キーが画面に
出る**のを機械で止める（3.382.0 の `vioBuckets` と同じ形）。3.409.8 で呼出0の関数6つを撤去し、
**`cycleCell` が初回インポート以来一度も呼ばれていない**＝CLAUDE.md が3世代にわたり書いてきた
「編集の4入口」は実際には**3つ**だったと確定（死んだ関数を守ってもガードの網羅は証明されない）。
3.409.9 で提示レビュー P0-P3 を照合＝**P0 の名指し（`adafe28`/`rAiPriority`）はこの
リポジトリに存在しない**が、同じ欠陥クラスを機械で数えて**広域ビームのピン却下が終端集計へ
合流していない**実在の1件を発見・修正（ただし実測すると全ラウンド attempts=0＝構造的な欠落で
あって、この3データでは1件も落としていなかった）。P1 として停止伝播の回帰テストを新設。
3.409.10 で 3.409.8 が「理由つきで残した」3つを、**残した理由そのものを検証してから**片付けた
（λ上限は `gLam` 不在＋辞書式パックにより**書かれたままでは配線できない**と確定して撤去／
未採用の `MagiSectionHeader` を撤去／**残す根拠にしたデザインシステムの ✅ が実在と食い違っていた**ため
3件を訂正し `design_lint` P8 で機械検査化）。3.409.11 で判断待ち4件を決着＝**c1 を破線側へ昇格**
（3.367.0 の「最多件数だから飽和する」判断は **fire 数**で見ており、この表示が扱うのは**セル数**＝
単位が違った。実測でセル総数310に対し破線は 20→42／11→28／0→5 で 3.99.0 の飽和 194 には遠い）。
グループ上下限の一括解除が**何名ぶん消えたか画面に出ていなかった**のを Snackbar へ、最後の1シフト・
1職員の削除ボタンが**理由なく消える**のを 3.400.0 のグループと同じ形で説明に。3.409.12 で「この環境では
実行できない」と記録していた項目を検証＝**2件は前提が誤っていた**。①実機スクショ起因: 最適化の実行中でも
編集タブの追加/編集/削除が押せ、**入力し終えてから拒否**していた（3.405.0 の対象漏れ・13サイトを無効化）
②C++ に停止確認は元から**1つも無い**（grep 0件）＝確かめるべきは「止まるか」でなく「止まるまで回り続ける
長さ」で、実測 **1チャンク4.7ms・最悪257ms** ③P2 の Robolectric 試験は、実際に起きた回帰2件がどちらも
呼び出し側の書き忘れなので**機械検査 P9**（begin と finally の end が対）へ置換——注入試験で **P9 自身の
欠陥を2つ**（`io` 未 import を広い except が飲み込む／同名変数で隣の関数の解放を拾う）出荷前に発見・修正
④compileSdk 37 は **stable 公開済み**と確認＝3.173.0/3.373.0 の「未公開」は解消（AGP 互換の確認は残る）。
3.409.14 で実機の「条件の意味は？」を受け、制約10族の**詳しい説明を既定で閉じた ⓘ 展開**としてアプリへ
（本文は Compose 非依存の表＝MagiState の cons* と過不足なく一致することをテストで固定・重みの数値は
書かない＝HF77 で stale 化しないため）。3.409.15 で backlog#6 の残り＝「covU が構造床を超えて
blocked-now」な実データ形状を、**職員名だけ匿名化**（評価は bit 一致を実測）して第3フィクスチャへ
（言語跨ぎ照合3本目＋形状の回帰テスト＋匿名化規約を守らせる防具つき）。
3.409.13 で /code-review 7件を全修正＝**前日「注入で2欠陥を潰した」と書いた P9 自身にまだ3つの穴**
（裸呼び出し不可視・判定が生テキストの語検索・1行 try-finally の偽陽性）→ ブロック所属ベースへ作り直し
（注入8ケース＝欠陥4発火・正当4素通り）。行本文タップ・スキル▼・担当可否チップの実行中ガード漏れと、
`ui.running` の stale-false 経路（背景 StateFlow を写しへ反映）も修正。ホストJVM 508テスト green。）

（以下は過去分）3.377.0 実機ログ起因の診断バグ2件。①**`残存分析` が「もう直せない covU」を
「まだ狙える」に入れていた**＝covU の構造判定が `hardFloor`（有資格者数ベースの静的下限）しか見ておらず、
同じ実行の `CoverageDiag`（「いまの希望のままでは不能」）と設定ミス診断（同じ2日を「証明つき」で名指し）と
食い違っていた。3.344.0 の `CoverageDiagnosis.blockedNow` を単一ソースとして読む形へ（**3.361.0 の実測＝
早期終了への配線は却下、はそのまま維持**＝表示だけ）。②**`Watchdog` 行が2つの時間軸を混ぜていた**＝
「最終改善=経過287s・**探索終了時**の停滞0s」なのに探索は274sで終了。ExtraRefine の改善が
`lastBestImproveMs` を進めるため負→0 に丸められていた。探索終了時点のスナップショットへ統一し、
探索後の改善は別項目で出す。ホストJVM **470テスト green**（新規1・教訓#30 の scratch-revert 確認済み）。
読み取り専用・表示のみ＝重み・採否・探索・停止条件は完全に不変。3.371.0 ユーザー指示「並列SAの本格再有効化する」「soft全族の完全差分する」の2件。
①**並列SAの本格再有効化**＝`hypothesisChainPlan`は`hypotheses==workers`（3.224.0以降の常態）だと
`distributable=max(h,min(workers,cores))`が構造的に常に`h`へ収束し、`runMultiWorker`（ALNS/RSI/RSI_PLUS
明示選択の経路）の仮説内チェーン本数が実機コア数に関わらず常に1本に潰れることを数式で特定。新設
`hypothesisSpawnPlan(workers,w,cores)`が`workers>cores`のときだけ仮説本数を`max(2,min(w,cores))`へ
畳んで余剰をチェーン深さへ再配分（`workers`上限は`clampWorkersToCores`の先例どおり厳守）。**さらに実機ログ
2本（Pixel 10 Pro XL・8コア・workers=8）で PORTFOLIO（既定211秒以上の主経路）は`roleOptions.workers=1`が
無条件固定と確認**＝希釈リスクの無いworkers==coresでもSAチェーン1本のまま。430行の`runAdaptivePortfolio`を
under-testedなまま作り替えるリスクを避け、`PolishGate.portfolioRoleParallelSa`（既定OFF・未測定のため
実機検証用トグル、設定タブ「詳細設定」に配線）で明示オプトイン化。②**soft全族の完全差分**＝
`DeltaEvaluator`の既存検証はaggregate（`sc1+sc2+...`合算値）のみで、2族が同じ重み（c1=15=c3mn等）だと
片方の誤りをもう片方が相殺しうる盲点を発見。`familyRaw()`/`rangeWeighted()`を新設し、`MirrorKeys.all`の
19族すべてを個別にcheckerの`report.breakdown`と突合するテストを追加。**教訓#30を実践**＝scratch限定で
`sc1`/`sc3mn`を意図的に取り違えるバグを注入し、新テストだけが単独で落ちる(`family=c1 expected:<2> but
was:<0>`)ことを確認してから実コミットへ反映（scratch側は検証後に削除・repo本体は無傷）。ホストJVM実行で
main+test 47+61ファイルを再コンパイルし**457テストgreen**（新規4件、既知の false positive
`EliteIntegrationRandomSafetyTest`1件を除く）。エンジン重み・探索・keep-best採否ロジックは完全に不変
（並列度の配線とテストの追加のみ）。3.370.0 ユーザー指示「同様な問題などあるか?」＝外部レポート（別コードベース確定済み）の
カテゴリ名だけを写して監査し実在2件を発見・修正。**`breakdownLocations`（内訳→場所タップ）が3キー空間
（セル/回数/被覆）とも単一クラスの生マップを直接フィルタ**しており、重い違反(covU8000/low90等)と同じセルの
軽い違反(c41/apt/c2等)が場所一覧から消える表示バグ＝3.111.0/3.353.0の`cellFamilies`/`countFamilies`は
checker層で解決済みだったのに消費者(`breakdownLocations`)へ一度も配線されておらず、`needViolations`
（covU/covO/c41系の被覆キー空間）には兄弟union自体が存在しなかった（第3のキー空間で同型の穴が未対応）。
`MirrorCore`に`needFamilies`新設・`UiState`/`makeUi`へ配線・`breakdownLocations`4分岐を`*Families`優先へ
書き換え。ホストJVM実行で452テストgreen（新規1件）。あわせて**CIのGradleダウンロードが3ワークフローとも
リトライ・キャッシュなし**という実在の gap を発見し`actions/cache`+`wget --tries=3`の多重防御を追加
（編集中に`name:`行の孤立という自己ミスをYAML構文検証で発見・是正）。3.369.0 ユーザー指示「すべてのフルコードを/code-review する」＝約35,000行の
Kotlin/C++全体をインライン単一パス精査。**need2単独定義セル見落としの第3世代を発見・修正**＝`covUCell`/
`covOCell`（3.173.0/3.309.0で確立済みのsource of truth）を経由せず生の`need1`だけを見る箇所が
`SmartInitialScheduler.kt`(demand-fill/残り埋め)・`GreedyMirrorScheduler.kt`(同型)・
`V6SearchOperators.findCovOFix`の4箇所に残存＝初期解生成がneed2のみ定義の需要/上限を見落としcovU(HARD)
違反を残しうる不具合。全箇所をsource of truthへ統一。`C1TemporalDp`のRELOC_BITS未検証（現状到達不能だが
latent、3.213.0のSCORE_HARD_UNIT検証と同型の精神で防御的ガード追加）も修正。ホストJVM実行で main+test
61ファイルをコンパイル・**451テストgreen**（新規3件）。教訓#30実践＝SmartInitialSchedulerの修正をscratch
コピーでのみ一時revertし新規テストが単独で落ちる(expected:0 but was:2)ことを確認してから復元。エンジン
評価器本体(Checker/Evaluator/DeltaEvaluator/native parity)は既存の大量の敵対的レビュー履歴で堅牢化済みで
新規欠陥は0。3.368.0 族数「18種」の docs 取り残しを**19種**へ横断修正＝`MirrorKeys.all` は19族(weekly が19番目)なのに 3.202.0 が business-logic.md だけ直し data-models/overview/requirements/magi_design_system/screen_spec が18のまま取り残されていた（screen_spec:199 の族列挙は weekly が抜け）。コード1コメント（MagiScheduleViews:354 E7 バケツ=実17族＝fair/weekly 除外）も是正。docs＋コメントのみ・ロジック不変。3.367.0 sibling-bug 掃討を**重み定数コメント**へ拡張＝c3mn(12)/c1(4)/covO(0.5) の旧値が残る現行記述コメント（MagiScheduleViews/V6WebCompat/V6HotfixPasses）を現行値へ訂正（3ファイル/コメントのみ/分類コード不変）。あわせて c1 が重み 4→15 に上がったのに表示分類（グリッド heavySoftFamilies・凡例 severityFromVioKey）が非 heavy のままな**設計上の緊張**を発見＝どちらも c1 を非 heavy で一貫・視覚不変（凡例は HIGH/WARN を下流で同一表示に畳む・グリッドは c1=最多件数族で飽和回避）のため分類は据え置き、severity-match を優先した c1 破線昇格は一行変更で可能な**判断点**としてコメント化。3.366.0 外部 fork レポート L1-L10 の周辺検証＝L5「better の順序」・L9「正規化を通さない raw Move」の実在アナログを精読で両方否定（実装は全て `better()`=`reportComparator`=hard→weightedScore→total で正しい・chain の手は必ず keep-best ゲート）。実在した唯一の項目＝keep-best 順序を旧 `hard→total→weight` で書いた**現行記述コメント12箇所を訂正**（3.287.0 の統一取り残し・9ファイル/コメントのみ・コンパイル/テスト/スコア不変。歴史記述と正しい移行説明は温存）。3.365.0 別ブランチ x8ygvy を精査し**共有ネイティブハンドルの並列安全テスト**（923bf07）だけを選択的に移植＝8スレッドが同一 MagiProblem で逐次と bit 一致を実行で証明・既存 native-parity CI で自動実行（`-pthread` 追加）。covU 早期終了系（このセッションで real3 A/B により有害実証・却下）は取り込まず。3.364.0 c1「壁」判定(検査2b-2)の need2 依存を実データ計測で **false wall と確定**し正直化＝非休の c1 窓は物理供給≥需要が常に成立し構造的不能にならない（golden Dﾃ は物理供給248>>需要32・手作り盤面は既に35回配置）ため「構造的に残ります」を撤回し covO-tension として案内。休のみ真の壁を維持。read-only・スコア不変。backlog#4 解消・3.179.0 の据え置き前提を反証。3.363.0 直近コード（3.352-3.360）を sibling-bug 狙いで焦点レビュー＝**実バグ0**（keep-best 比較の集約は完全・診断の算術も正しい・environmentLine/telemetry も健全）。あわせて 3.95.0 の stale fact「golden 構造的covU=2」を実測（structuralHardFloor=0）で反証・訂正／docs のみ。3.362.0 パリティネット（言語跨ぎ・3.357.0）へ**2つ目の実データ形状 sample_v6**（入力盤面 hard=15＝groupViol/c3n/pref/covU 発火）を追加＝golden(hard=0)では未 exercise の C++ HARD族パスを実データで照合。`host_parity_bench.cpp` の `--expect` を flat と出現順で対応づけ1回のベンチで両形状を照合／test・CIのみ・エンジン不変（backlog#6）。3.361.0 残作業 #1「covU-blocked をウォッチドッグへ配線し早期終了」を**実測のうえ却下**＝早期終了は keep-best-safe でない（探索を止めると未見の改善を諦める・keep-best では回収不能）／sample_v6 が「22秒停滞後の soft バースト改善」の実在を示す／動機データ real/user 消失＋golden は hard=0 到達で no-op のため直接 A/B 不可＝反証されたコードは revert（3.307.0 の規律）。3.306.0 適応ポートフォリオの停滞脱出（残差ベースの役割選択＋深さ保持）を既定OFFのトグルで温存＝実データ3件×各4回のA/Bで有意差を検出できず。3.305.0 staffPacked の重みドリフト（c1=4/c3mn=12 のまま）と比較順序（total優先）を修正＝3.249.0/3.253.0/3.287.0 の取り残し。3.304.0 禁止連続の崩し範囲を設定トグルへ配線し実データA/Bで検証（既定OFF維持。ONは人員不足2件を禁止連続2件へ交換する挙動と判明）。3.303.0 禁止連続をパターン全域（前日・当日・翌日）で崩す C3nPolish 新設＋ビット走査＋不採用の主因を全 Polish パスへ水平展開（範囲拡張は実測で利得が一貫せず既定OFF）。3.302.0 研磨の「不採用」に主因の族名を併記＝何に負けて捨てたのかがログから読めるように（C1Polish/RangePolish/C1JointLNS）。3.301.1 3.301.0 の検算を実データ3件で論理検証し、休の判定が旧実装ではほとんど実行されていなかった潜在バグを確認／検査6-C が引数 Problem を無視していたのを是正。3.301.0 目標（適切回数）カードにその場の検算を追加＝「目標の合計 N回 ／ 必要人数 M回 → K回は必ず届きません」と直し方を入力中に表示。判定は設定ミス診断の検査6-C と同じ単一ソース（`V6SanityPort.aptBalances`・盤面不要）。3.214.0〜3.300.0 の詳細は `CLAUDE.md` の各節に記録）／ **コード基準コミット**：main HEAD（この目次が古いと他が正しくても信頼が崩れるため、改修時は対象文書と本目次を必ず更新）。

## Status

- Engine core: Kotlin-native greedy + SA optimizer
- V6 web bridge compatibility: partially ported
- Constraint fidelity: Level Zero preserved for top-level constraints
- Input: JSON state via editor/sample assets
- Output: optimized assignments and diagnostics

## Build

```bash
./gradlew assembleDebug
```

## Run tests

```bash
./gradlew test
```

## Notes

This is a generated p11 project snapshot.
