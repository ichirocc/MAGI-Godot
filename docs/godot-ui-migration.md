# Godot UI 移行（ソース作成の記録）

**この文書はソースコードを書いた記録であり、動作確認の合格証ではない。** APKビルド・実機起動・
画面のスクリーンショット・既存Compose UIとの見た目/操作の同等性は、このサンドボックス環境に
Android SDK / Godotエンジン本体が無いため**一切実施できていない**。以下「検証状況」の章を必ず読むこと。

## 目的・方針

既存Compose UI（`app/src/main/java/com/magi/app/ui/`）と並行して、Godot 4.5.1製のUIレイヤーを追加する
（第1〜5段。**第6段で Compose UI を削除し Godot 版のみ**＝本書末尾）。
- **エンジン層（`v6/`）・重み・保存形式・希望固定の業務解釈は一切変更しない。** Kotlinが正（CLAUDE.md）。
- 第5段まで: 既存Composeビルドは`magiGodot`フラグ（Gradleプロパティ）でOFF/ONを切り替え、既定(OFF)では無変更。
- GodotはKotlin側の可変状態(ViewModel)に直接触らず、`MagiBridge`が発行する不変JSONスナップショットと
  トークン付きdispatchのみを介して読み書きする。

## 実装したもの

### Kotlinブリッジ層（`app/src/main/java/com/magi/app/godot/`）
- `MagiOpWhitelist.kt`: dispatch可能な操作名と必須引数キーの許可リスト（純Kotlin、Android非依存）。
- `MagiBridgeToken.kt`: snapshotJSON(本文)のSHA-256 + 単調増加リビジョンからトークンを計算・検証する
  純ロジック（純Kotlin）。`op`が`stop`/`dismissInterrupted`のときはトークンの鮮度チェックを免除する
  （最適化実行中でも停止コマンドは必ず通す設計判断）。
  **リビジョンは dispatch（変更）成功時にだけ進む**（第3段で変更。旧: `snapshot()`のたびに進み、別画面が
  refresh しただけでプレビュー用トークンが失効していた）。変更なしで uiState が入れ替わる経路
  （背景最適化の結果到着など）は本文ハッシュ側が検出する。
- `MagiBridge.kt`（Android依存・`MagiViewModel`を保持）: `snapshot()`でUiStateの主要フィールドを
  JSONへ直列化し、`dispatch(op, argsJson)`で許可リスト・必須引数を検査したのち
  `Handler(mainLooper)+CountDownLatch`でメインスレッドへ移送し、**同じメインスレッド区間でトークンの
  鮮度検査→ViewModelの対応メソッド呼び出し**を行う（第3段で変更。旧: 鮮度検査を呼び出し元スレッドで
  行ってから post していたため、検査と実行の間に盤面が変わっても通る隙があった）。
  添字ベースの op（`setCell`/`ws1Move*`/`ws1Remove*`等）は `staffNames`/`structure` の並びが本文ハッシュに
  入るため、並び替え後に古い添字で来た操作はここで拒否される。
  メインスレッド待ちは **10秒で打ち切り**、`{"ok":false,"error":...}` を返す（旧: 無期限待ち＝メインスレッドが
  詰まると Godot 側まで固まった）。既存`MagiViewModel*.kt`は**1行も変更していない**。

### JVMホストテスト
- `app/src/test/java/com/magi/app/godot/MagiGodotBridgeTest.kt`: 許可リストの許可/拒否、必須引数の
  欠落検出、トークンの一致/不一致（盤面変更後の古いトークン拒否含む）、`stop`の免除対象指定を検証。
- `tools/host/hosttest.sh`のMAIN_SRCへ`godot/{MagiOpWhitelist,MagiBridgeToken}.kt`を追加。
  **`MagiBridge.kt`自体はAndroidViewModel(Context依存)を保持するためホストJVMでは実体化できず、
  ホストテストの対象に含まれていない**（dispatch/snapshotが使う純ロジック側のみ検証）。
- 実行結果（本セッション時点）: `tools/host/hosttest.sh` で773テストすべて成功（既存の
  `low native=8/web=9 DIFF`ログはハーネス既知の非該当出力で、テスト自体はfailureにカウントされていない）。

### Godotプロジェクト（`godot/`）
- `project.godot`（Godot 4.5.1想定、`config/features="4.5"`）、メインシーンは`scenes/Home.tscn`。
- `scripts/magi_api.gd`（autoload）: 実機(Android)では`JavaClassWrapper.wrap("com.magi.app.godot.MagiGodotActivity")`
  経由で`magiSnapshot()`/`magiDispatch()`を呼ぶ。**この呼び出し経路自体は未検証**（後述）。
  エディタ実行時(非Android)はモックJSONを返すフォールバックを持ち、シーンの構文・遷移だけは
  Godotエディタ上で確認できる（業務データは含まない）。
- `scripts/nav.gd`（autoload）: 10画面のタブ切替と共通タブバー描画。
- `scripts/screens/base_screen.gd` + 各画面スクリプト（`Home`/`Schedule`/`StaffGroupsShifts`/
  `MonthWishesCounts`/`AptSkills`/`Constraints`/`Analysis`/`Settings`/`JsonEditor`/`UndoExport`）。

### Android側の埋め込み
- `app/build.gradle.kts`: `-PmagiGodot=true`のときのみ`org.godotengine:godot:4.5.1.stable`＋
  `androidx.fragment:fragment`依存と`src/godot/java`ソースディレクトリを追加し、`src/godot/AndroidManifest.xml`
  を debug/release の build-type manifest として合流させる。既定(false)では何も追加しない＝既存ビルドに影響なし。
- **Godotプロジェクトの同梱**（第3段で新設。旧: 同梱工程が無く、起動しても読み込む画面が無かった）:
  `magiGodot=true` では `-PgodotExecutable` を必須とし、Gradleタスク `importGodotProject`
  （`godot --headless --path godot --import`＝`.godot/imported` を生成）→ `exportGodotPck`
  （`godot --headless --path godot --script res://tools/build_pck.gd -- <build>/generated/godot-assets/magi.pck`）
  で `assets/magi.pck` として APK に入れる。`tools/build_pck.gd` は `PCKPacker` で res:// 配下（import キャッシュ含む）を
  そのまま詰める＝**export templates（約1GB）が不要**（第4段で `--export-pack`＋`export_presets.cfg` から変更。
  `--export-pack` はテンプレート未導入だと設定エラーで拒否されるため）。
  `MagiGodotActivity.getCommandLine()`が`--main-pack res://magi.pck`を返し、Godot 起動時に読ませる。
  Godot公式の Android library 手順（PCK を assets に置き `--main-pack` で渡す）に準拠。
  未検証: `res://magi.pck` の解決（APK assets）が実機で成立するか。
- `app/src/godot/AndroidManifest.xml`（第3段で新設）: main より高優先でマージされ、Compose 側
  `MainActivity` の LAUNCHER intent-filter を `tools:node="remove"` で外し、`MagiGodotActivity` を
  起動入口として宣言する（`configChanges` は Godot 公式テンプレートに準拠）。
  第2段までは main の `AndroidManifest.xml` に `exported=false`・intent-filter 無しの宣言があり（第3段の本文書と
  README に「宣言が無かった」と書いたのは誤り＝LAUNCHER 行だけを grep した見落とし）、第4段の CI で build-type
  manifest 側の宣言と `exported`/`configChanges` が衝突して合流に失敗した。**main 側の宣言は撤去**し、
  `MagiGodotActivity` の宣言は `src/godot/AndroidManifest.xml` だけに置く（既定ビルドは実体の無い Activity を
  宣言しない）。
- `app/src/godot/java/com/magi/app/godot/MagiGodotActivity.kt`: `FragmentActivity`＋`GodotHost`実装、
  `GodotFragment`埋め込み。GDScript の `JavaClassWrapper` は static メソッドしか呼べないため、
  `magiSnapshot()`/`magiDispatch()` は companion の `@JvmStatic` とし、Activity が生成した `MagiBridge` を
  `onCreate` で登録・`onDestroy` で解除する（第3段で変更。旧: `ComponentActivity` に
  `supportFragmentManager` が無く、`GodotHost.getActivity()` も未実装＝コンパイル不能だった）。
- `tools/godot-ui-check.sh`: GDScriptの粗い構文チェック（gdtoolkit があれば`gdlint`、無ければ括弧対応の
  簡易チェックにフォールバック）とシーン参照の静的整合性確認。
- `godot/tools/headless_smoke.gd`（第4段で新設。第2段までの記録に「作成済み」とあったが実在しなかった）:
  `godot --headless --path godot --script res://tools/headless_smoke.gd` で autoload の存在と `Nav.SCENES` 全10画面の
  ロード・インスタンス化・`_ready`（`MagiApi.refresh`→`render`）を確認する。非Androidなのでモック経由。
- `.github/workflows/godot-ui-check.yml`（第4段で GitHub ホストランナーへ移行）: Godot 4.5.1 Linux 版を公式リリースから
  取得（sha512 固定・キャッシュ）し、静的確認 → import → smoke → JVM ホストテスト → `-PmagiGodot=true` の
  `testDebugUnitTest` → `assembleDebug`（手動実行では `assembleRelease` も）を push/PR/手動で走らせる。
  `magiGodot=true` 経路を検証する唯一の CI。

## 画面ごとの実装状況（10領域）

| # | 画面 | 状態 |
|---|---|---|
| 1 | ホーム | 新規/生成/最適化/調整/BG実行/停止/保存を実dispatch。主要指標を表示 |
| 2 | 勤務表 | グリッド描画・セルタップでシフト巡回setCell。**シフト選択ダイアログは未実装**（次のシフトへ巡回のみ） |
| 3 | 職員/シフト/群 管理 | 職員/シフト/群の一覧・追加・編集・削除・並び替え(上下ボタン)を実dispatch |
| 4 | 月・希望・回数編集 | 希望反映(範囲内/範囲外)・範囲外クリアのみ実装。**個別希望セル編集は未実装** |
| 5 | 担当とスキル管理 | 群×シフトのcanDo切替・apt目標編集・スキル区分CRUD・職員のスキル区分割当を実dispatch |
| 6 | 制約編集（全11族） | cons1/cons2/cons3系4種/cons3w/cons41/cons42/cons41s/cons42sの追加・編集・削除を実dispatch |
| 7 | 分析 | 内訳数値・改善提案探索/適用(先頭のみ簡易ボタン)・設定ミス適用・代替案/操作履歴の表示 |
| 8 | 設定 | 並列数/予算/ネイティブ加速/パリティ照合/ソフト研磨の表示、一部トグルをdispatch |
| 9 | 詳細JSON編集 | JSON文字列の`load`のみ。**現盤面の全文書き出しは未実装** |
| 10 | 取消・保存 | Undo/Redo/保存/CSV取込を実dispatch。**JSON/CSVのファイル書出し(SAF連携)は未実装** |

3/5/6画面は`MagiBridge.snapshot()`の`structure`キー（`MagiState`の生値をJSON化したもの＝shifts/
groups/staff/skillGroups/groupShift/groupShiftApt/cons1〜cons42s）を読み、`MagiOpWhitelist`へ追加した
ws1系・addCons系・updateConstraint/removeConstraintをdispatchする。並び替えはCLAUDE.md「片手一本指」
方針により**ドラッグではなく上下移動ボタン**（`ws1MoveStaffTo`/`ws1MoveShiftTo`/`ws1MoveGroupTo`、
既存の隣接swapを1方向へ繰り返す実装＝Compose側`Ws1Editor.kt`と同じ`Ws1Ops`を呼ぶ）で行う。
担当可否/apt目標はエンジン仕様上「群×シフト」単位（`groupShift`/`groupShiftApt`）であり、
個人単位のcanDoフィールドはデータモデルに存在しないため（`docs/data-models.md`参照）、AptSkills画面は
群×シフトの表として実装し、職員側はスキル区分の割当のみを扱う。

## 検証状況（正直な申告）

**実施した検証**:
- `tools/host/hosttest.sh`: v6/model/既存ui一部＋`MagiOpWhitelist`/`MagiBridgeToken`の純ロジックを
  ホストJVMでコンパイル・テスト実行し、773テスト全て成功（本作業のテストを含む）。
- `tools/godot-ui-check.sh`: GDScript 13ファイルの括弧対応の粗チェックと、`.tscn`のext_resource参照・
  `nav.gd`のシーン参照がすべて実在ファイルを指すことを確認。

**実施できていない（このサンドボックスに実行環境が無いため不可能）**:
- Godotエディタ/エンジンでの実行・レンダリング確認（`.tscn`ファイルがGodot 4.5.1として実際に
  パース可能かは、`gdlint`/`gdformat`が入っていない環境での手書き確認に留まる）。
- Android Gradleビルド（`./gradlew assembleDebug -PmagiGodot=true -PgodotExecutable=...`等）そのもの
  （`exportGodotPck`タスクの実行を含む）。
  `org.godotengine:godot:4.5.1.stable`という座標が実際にMaven解決可能かも未確認。
- `GodotFragment`/`GodotHost`のAPIシグネチャがGodot 4.5.1のAndroidバインディング実体と一致するか
  （`MagiGodotActivity.kt`はAPI仕様の理解に基づく実装であり、コンパイル未検証）。
- `JavaClassWrapper.wrap(...)`経由でのインスタンスメソッド呼び出しが実機で機能するか（クラス名解決・
  引数のマーシャリングを含む）。
- 実機でのタッチ操作・30名×31日規模でのスクロール/描画性能。
- 既存Compose UIとの画面ごとの見た目・操作の同等性（今回は一部画面が読み取り専用のプレースホルダに
  留まっており、そもそも同等ではない）。

## タスクA（照合・修正）で見つけた点

- **トークン設計**: `MagiBridgeToken`はsnapshot本文のSHA-256+単調リビジョンで、dispatch時に
  現在の状態と一致するトークンだけを受理する（`stop`/`dismissInterrupted`は鮮度チェック免除）。
  第2段では「変更不要」と判断したが、第3段の再点検で2点を修正した: (a) 鮮度検査が呼び出し元スレッドで
  行われ、post されたメインスレッド実行までの間に盤面が変わると通ってしまう隙（検査と実行を同一
  メインスレッド区間へ移動）、(b) `snapshot()` ごとにリビジョンが進み、プレビュー→確定の間に
  別画面が refresh するだけで確定が拒否される（リビジョンは dispatch 成功時のみ進める）。
- **非同期順序**: `MagiBridge.dispatch`は`Handler(mainLooper)+CountDownLatch`で常にメインスレッドへ
  同期的に移送する設計（呼び出し元スレッドは`dispatch`の戻りを待つ）。Godot(GDScript)側の
  `MagiApi.dispatch`も呼び出しごとに結果を待ってから次の操作を組み立てる作りのため、連続呼び出しの
  後勝ち/早い者勝ちの矛盾は起きない。シーケンス番号の追加は不要と判断した。
  待ちは10秒で打ち切る（第3段）。同期待ちが Godot 描画スレッドを止める点は順序保証との意図的な
  トレードオフで、大盤面での体感は実機計測が要る。
- **許可リストの網羅性**: タスクBで追加する職員/シフト/群・スキル・制約のCRUD操作（`ws1*`/`addCons*`/
  `updateConstraint`/`removeConstraint`）が未収載だったため、本作業で`MagiOpWhitelist`と
  `MagiBridge.invokeOp`へ追加した。
- **状態の二重保持**: `MagiBridge`は`viewModel.uiState`/`viewModel.state`を読むだけで独自の可変状態を
  持っておらず、単一真実源は保たれている。3/5/6画面の生値表示のため`snapshot()`へ`structure`キー
  （`viewModel.state`の直列化コピー）を追加したが、これも読み取り専用のJSONコピーであり二重保持ではない。
- **ログ/セキュリティ**: `MagiBridge`/Godotスクリプトともに`Log.*`/`println`によるトークンや盤面JSON・
  職員名の出力は無かった（新規追加分にも追加していない）。

## 未完了・既知の制限

- 勤務表のシフト選択は「次のシフトへ巡回」の簡易実装。実際のシフト選択ピッカー(ボトムシート相当)は未実装。
- JSON全文の読み書き（現盤面のエクスポート）・CSV/JSONのファイル保存(SAF)は未配線。
- `MagiGodotActivity`のビルド可否・`GodotFragment`の正しい生成方法・`GodotHost`の抽象メソッド一覧は
  実機/実際のGodot 4.5.1 Androidバインディングとの突き合わせが必要（第3段で `FragmentActivity`化・
  `getActivity()`実装・static エントリ化を行ったが、コンパイル未検証である点は変わらない）。
- `src/godot/AndroidManifest.xml` の `tools:node="remove"` による LAUNCHER 付け替えは、マニフェスト
  マージの出力（`app/build/intermediates/merged_manifest/`）で確認が必要（未実施）。
- Godot本体の`.tscn`/`.gd`はテキストとして手書きしたものであり、Godotエディタで一度も開いていない。

## 第5段: レビュー対応 A（操作失敗時の再同期・設定同期・防御的描画）

外部レビュー（2026-09-15、`9370626` 時点の静的レビュー）のうち即時対応できる指摘を反映した。
- `base_screen.run_op` は成功/失敗を返し、失敗時は `MagiApi.state()` から再描画してトグル/入力欄の見た目だけが
  変わった状態を残さない。エラーは `Content` への追記でなく専用 status ラベルへ上書き表示。
  `MagiApi.last_error`（接続失敗・snapshot 失敗）があれば「表示は最後に取得できた状態」と明示する。
- 設定画面: CheckBox/SpinBox/OptionButton を `render()` で state と同期（同期中は signal を dispatch に流さない）し、
  並列数・予算秒・方式（`setWorkers`/`setBudget`/`setV6Algorithm`）の操作 UI を追加。実行フラグ系は UiState に
  現在値が無いため扱わない（明記）。
- 分析画面: 代替案を「案N を採用」ボタン（`applyAlternative`）に、提案/設定ミスの適用は index 指定（件数 0 で無効化）。
  候補本文は UiState JSON に無い＝件数だけ（本文を返す契約拡張は別段）。`opLog` は BBCode エスケープ。
- ホーム: `running`/`loaded` に応じてボタンを無効化、「JSON読込へ」導線を追加。
- 勤務表: 行長の不揃い・添字範囲外に防御的（不正セルは無効ボタン）。`_on_cell_tap` も境界検査。
- 制約編集: cons42/cons42s の追加フォームを編集フォームと同じ「群1/シフト1/群2/シフト2」順に統一。
- 追加フォーム（職員/シフト/群・スキル区分・制約各族）は dispatch が通ったときだけ入力欄を消す
  （旧: 失敗しても消え、拒否された値を打ち直せなかった）。
- `MagiBridge`: revision の初期値を生成ごとの乱数にし（Activity 再生成で旧トークンが同じ本文・世代で通る穴を塞ぐ）、
  応答に `changed`（本文が実際に変わったか）を加え、変わらない no-op では revision を進めない。

対応しなかった/別段のもの: 候補詳細（`fixSuggestions`/`settingIssues` 本文）の JSON 契約拡張、希望セル編集、
JSON 全文書き出し・SAF 入出力、安定 ID 化、`canonicalBody` のキーソート（両側が同じ生成器・同じ順序のため現状は不要）、
実機/エミュレータでの統合テスト。

## 第6段: Compose UI 削除・Godot 一本化（3.549.0、ユーザー決定）

第5段の CI 成功を受け、Compose UI を削除して Godot 版を唯一のビルドにした。`main` は実機確認まで凍結＝作業ブランチのみ。
上の各節に残る「`magiGodot=true` のときだけ」「Compose 側の LAUNCHER を外す」等の記述は第5段までの経緯であり、現状は本節が正。

**削除したもの**
- `app/src/main/java/com/magi/app/MainActivity.kt`（Compose ホスト。`MagiTheme` を含む）
- `ui/` の Composable 17 ファイル: `Affordance`・`ConstraintEditor`・`MagiApp`・`MagiComponents`・`MagiDashboardCards`・
  `MagiScheduleViews`・`MagiSetupCards`・`MagiTokens`・`NeedDayEditor`・`ShiftColorEditor`・`SkillGroupEditor`・`StaffManageCard`・
  `StaffRangeEditor`・`StaffShiftMatrix`・`V6RemainingScreens`・`WishEditor`・`Ws1Editor`
- `work/BubbleActivity.kt`（会話バブルの展開ビュー＝Compose）と `work/BubbleSupport.kt`（バブル通知・会話ショートカット）
- `app/src/godot/AndroidManifest.xml`（build-type manifest による LAUNCHER 付け替えは不要に）
- `.claude/skills/design-review/`（Compose の Composable 専用レビュー）
- CI: `v6-engine-check.yml`（Compose 版 test+assembleDebug）・`release-build.yml`（Compose 版 release）

**残したもの**（Compose 非依存）
- `ui/`: `MagiUiState`・`MagiViewModel`・`MagiViewModelConditions`・`MagiViewModelConstraints`・`MagiViewModelIo`・
  `MagiViewModelWs1`・`AnalysisTriage`・`BreakdownLabels`・`ConstraintHelp`・`JapanHolidays`・`VioBuckets`（ロジック無変更）
- `v6/`・`model/`・`work/OptimizationWorker`・`OptimizationRepository`・`RunFiles`・`SaveGate`・`godot/`（ブリッジ）・`KigouFormat.kt`
- `docs/DESIGN.md`・`docs/magi_design_system.md`・`docs/screen_spec.md` 等の Compose 時代の文書（Godot テーマの指針として保持）

**通知の変更**（`work/OptimizationWorker.kt`）
- 会話バブル（`BubbleMetadata`・`MessagingStyle`・長寿命ショートカット）は展開先 Activity が無くなるため廃止。
- 前景通知（`NID_PROGRESS`）を同 ID で ~1.5 秒間引きの進捗文（経過・違反数）に更新＝バブルが担っていた常時表示の代替。
- 前景・完了・失敗の各通知に `contentIntent`＝`MagiGodotActivity`（`FLAG_ACTIVITY_NEW_TASK|CLEAR_TOP`）を付与。

**ビルドの一本化**
- `MagiGodotActivity.kt` を `app/src/main/java/com/magi/app/godot/` へ移動。`app/src/main/AndroidManifest.xml` が
  `.godot.MagiGodotActivity`（`exported=true`・`configChanges` は Godot 公式テンプレート準拠）を唯一の LAUNCHER として宣言。
- `app/build.gradle.kts`: `magiGodot` フラグ撤去。Godot AAR（`org.godotengine:godot:4.5.1.stable`）・`androidx.fragment` を通常の
  `implementation` に、`importGodotProject`/`exportGodotPck`・assets 同梱・`preBuild.dependsOn` を無条件に。`-PgodotExecutable` は
  常時必須（無ければ `GradleException`）。Compose プラグイン・`buildFeatures.compose`・compose-bom/activity-compose/
  lifecycle-*-compose/material-icons-extended を撤去し、`MagiViewModel`（`AndroidViewModel`/`viewModelScope`）用に
  `androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6` を明示。`activity-ktx` は `fragment` 経由で届くため追加せず。
  root `build.gradle.kts` から Compose プラグイン宣言を撤去。versionCode 768→769、versionName `3.549.0-godot-ui`。

**CI の整理**
- `godot-ui-check.yml` が主 CI（`-PmagiGodot=true` 指定を外し、コメントを現状に）。トリガーは従来どおり。
- `godot-release-build.yml` も同様。`design-lint.yml` のコメントを更新（P1〜P4/P11 は対象なし）。
- `tools/design_lint.py`: P8（設計文書の ✅ 実在確認）は `@Composable` が無いときスキップ、P11 baseline 6→0。
  P5/P6/P7/P9/P10 は従来どおり動く。

**検証**: `tools/host/hosttest.sh` 775 件成功、`tools/godot-ui-check.sh`・`tools/design_lint.py` 通過、ワークフロー YAML の
パース確認。Android 実ビルドはこの環境では不可＝作業ブランチへの push で走る Godot UI Check（testDebugUnitTest →
assembleDebug → 起動構成 assert）が確認手段。実機での起動は引き続き未実施。

## 第7段: 実機「起動直後にクラッシュ」への対応＝起動診断（3.550.0）

第6段までの APK（ad82143 の成果物）が実機で起動直後に落ちたとの報告。logcat は得られない前提で、
**原因を画面に出す仕組み**と**疑わしい経路の回避**を同時に入れた（原因は未特定＝次の実機報告で絞る）。

**MagiStartupGuard（`app/src/main/java/com/magi/app/godot/MagiStartupGuard.kt`）**
- 起動段階を `filesDir/magi_startup_stage.txt` に記録: `activity`（Activity 生成）→ `viewmodel`（MagiViewModel・MagiBridge 生成）→
  `engine`（`GodotFragment.commitNow()` 復帰＝`Godot.initEngine`/レンダービュー生成が済んだ）→ `ui`（GDScript が最初に
  `magiSnapshot()`/`magiDispatch()` を呼んだ＝画面が Kotlin まで到達）。
- `Thread.setDefaultUncaughtExceptionHandler` で未捕捉例外を `filesDir/magi_crash.txt` に残してから既定ハンドラへ渡す。
  ネイティブクラッシュ（SIGSEGV 等）は Java 側で捕まえられないが、到達段階で「どこまで来たか」は分かる。
- 次の起動で「前回 `ui` に到達していない」または「例外記録がある」なら `MagiGodotActivity` は Godot を起動せず、素の View で
  診断画面を出す: 版数・端末・レンダラー・到達段階と説明・PCK の状態（assets のサイズ／filesDir 複製）・Java 例外・
  Godot ログ末尾（`project.godot` の `debug/file_logging/enable_file_logging=true` で `user://logs/` に出る）。
  ボタンは「そのまま起動」（記録を消して再起動）と「OpenGL 互換レンダラーで起動」（Vulkan 初期化で落ちる端末の退避先。
  Godot 4.5 の `Godot.kt` は `--rendering-method`/`--rendering-driver` をコマンドラインから読み、ProjectSettings より優先して
  GL 用レンダービューを選ぶ＝Java 側とネイティブ側の食い違いは起きない）。
- `launchGodot()` を `try/catch(Throwable)` で包み、`GodotFragment` が拾わない例外（`.so` 読込失敗の `UnsatisfiedLinkError` 等）も
  同じ診断画面に出す。`GodotFragment.performEngineInitialization` 自身は `IllegalStateException`（PCK 読込失敗・レンダービュー
  生成失敗）を捕まえてダイアログを出しプロセスを終了する＝この場合も次回起動で `engine`/`viewmodel` 段階として現れる。

**PCK の渡し方を変更**
- `assets/magi.pck` を `filesDir/magi.pck` へ複製し、絶対パスで `--main-pack` に渡す（Godot 自身の APK 拡張パック経路と同じ形。
  再複製の判定は versionCode と APK の更新時刻）。複製に失敗したときだけ従来の `res://magi.pck`（AAsset 直読み）へ退避。
- `app/build.gradle.kts` に `androidResources { noCompress += listOf("pck") }`＝退避経路でも圧縮エントリの seek を避ける。

**CI の検証を強化**（`tools/check_apk_native_libs.py`、`godot-ui-check.yml` と `godot-release-build.yml` の両方から実行）
- 必須エントリに `lib/arm64-v8a/libc++_shared.so`（`libgodot_android.so` の依存）を追加。
- `.so` が非圧縮（STORED）で、データ先頭が 16KiB 境界に載っていることを APK のローカルヘッダから検査
  （Android 16 の 16KB ページ端末は APK から直接 mmap する。ELF の LOAD 整列は確認済みだったが APK 内整列は未確認だった）。
- `assets/magi.pck` が非圧縮であること。

**確認済み（Godot AAR のバイトコード読解）**: `GodotFragment.onCreate` は `parentHost.getGodot()` が null なら
`Godot.getInstance(context)` を使う（本 Activity の `getGodot()` はフラグメント生成前は null＝想定どおり）。`Godot.initEngine` は
`--main-pack` を含むコマンドラインをそのまま `GodotLib.setup` へ渡す。`--use_apk_expansion` を渡していないので expansion
downloader 経路（`IllegalArgumentException`）には入らない。

**次の実機報告で見るもの**: 診断画面のスクリーンショット（到達段階＋例外／Godot ログ）。`viewmodel` 止まりなら
`.so`/PCK/レンダラー、`engine` 止まりなら描画開始か GDScript（Godot ログに出る）、例外記録があればその内容。

**結果（3.550.0 実機）**: 起動成功。ホーム画面がブリッジ経由の実データ（`保存状態: Saved`）を表示＝GDScript→JavaClassWrapper→
Kotlin の往復が実機で動いた。3.549.0 以前が落ちた原因は特定できていない（診断画面は起動失敗時にしか出ないため。
変更点のうち効いた可能性が高いのは PCK の絶対パス化／非圧縮同梱）。診断の仕組みは今後の起動不良にそのまま使える。

## 第8段: 実機レイアウト対応（3.551.0）

3.550.0 のスクリーンショットで見えた 3 点への対応。
- **向き**: `window/handheld/orientation` を `landscape`（第1段の仮置き）から `sensor`（端末の回転に追従）へ＝ユーザー決定。
- **拡大率**: Godot は既定で端末ピクセル 1:1 に描く（stretch 無効）ため高 DPI 端末で文字が極小だった。`nav.gd` の `_ready` で
  `root.content_scale_factor = DPI/160`（dp 相当、1〜4 にクランプ、Android のみ）。stretch 無効でも
  `content_scale_factor` は適用される（`Window::_update_viewport_size` の DISABLED 分岐）。
- **安全領域**: タブ列がステータスバー／カメラ穴の下に潜っていた。`base_screen.gd` の `_apply_safe_area()` が
  `DisplayServer.get_display_safe_area()` と `window_get_size()` の差を拡大率で割って `$VBox` の余白にする（Android のみ、
  `root.size_changed` で回転時に再適用）。
- **はみ出し**: タブ 10 個・ホームの操作ボタン 8 個は dp 換算の幅に収まらないため、`base_screen.gd` がタブ列と操作列を
  横スクロールの `ScrollContainer` で包む（縦はスクロール無効＝子の高さに従う。`build_actions` の後に包むので
  サブクラスの `$VBox/Actions` 参照は従来どおり）。
- 検証はこの環境では静的（`tools/godot-ui-check.sh`）のみ。見た目は次の実機スクリーンショットで確認する。

**3.551.0 の実機結果**: 3 点とも効かず（横固定・極小文字・ステータスバー下のまま）。原因と 3.552.0 での修正:
- **向き**: `window/handheld/orientation` は整数 enum（0=landscape … 6=sensor）。文字列 `"sensor"` は `int()` で 0＝landscape に化けていた
  （第1段の `"landscape"` も同じ理由で「たまたま」横向きだった）。`=6` に修正。
- **拡大率**: `Main::start` が `display/window/stretch/scale`（既定 1.0）を root に適用するため、autoload の `_ready` で入れた
  `content_scale_factor` は上書きされうる。各画面の `_ready`（`base_screen`）から `Nav.apply_ui_scale()` を呼ぶ（冪等）。
- **安全領域**: Godot の `get_display_safe_area()` は**カットアウトしか含まない**（`GodotIO.getDisplaySafeArea` は `DisplayCutout` の
  inset のみ）。ステータスバー／ナビバー分は Kotlin 側の `WindowInsets`（systemBars＋displayCutout）を `MagiGodotActivity.magiInsets()`
  で渡し、`base_screen.safe_margins_px()` が両者の大きい方を余白にする。Godot 自身のシステムバー padding（`Godot.kt` の
  `enableEdgeToEdge`）と二重にならないよう `--edge_to_edge` を渡して Godot 側の padding を止める。
- **版の可視化**: snapshot に `appVersion` を追加し、ホーム画面の末尾に「版 / 画面 px / DPI / 倍率 / 余白」を灰色で出す＝
  スクリーンショットだけで、どの APK か・拡大率が効いたか・余白が取れたかを判別できる。

## 参照した既存仕様

`app/src/main/java/com/magi/app/ui/{MagiViewModel,MagiUiState,MagiScheduleViews,Ws1Editor,
ConstraintEditor,AnalysisTriage,StaffShiftMatrix}.kt`、`docs/business-logic.md`、`docs/data-models.md`、
`docs/screen_spec.md`、`docs/sudo_model.md`、`CLAUDE.md`。
