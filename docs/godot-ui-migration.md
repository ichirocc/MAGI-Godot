# Godot UI 移行（ソース作成の記録）

**この文書はソースコードを書いた記録であり、動作確認の合格証ではない。** APKビルド・実機起動・
画面のスクリーンショット・既存Compose UIとの見た目/操作の同等性は、このサンドボックス環境に
Android SDK / Godotエンジン本体が無いため**一切実施できていない**。以下「検証状況」の章を必ず読むこと。

## 目的・方針

既存Compose UI（`app/src/main/java/com/magi/app/ui/`）と並行して、Godot 4.5.1製のUIレイヤーを追加する。
- **エンジン層（`v6/`）・重み・保存形式・希望固定の業務解釈は一切変更しない。** Kotlinが正（CLAUDE.md）。
- 既存Composeビルドは`magiGodot`フラグ（Gradleプロパティ）でOFF/ONを切り替え、既定(OFF)では無変更。
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

## 参照した既存仕様

`app/src/main/java/com/magi/app/ui/{MagiViewModel,MagiUiState,MagiScheduleViews,Ws1Editor,
ConstraintEditor,AnalysisTriage,StaffShiftMatrix}.kt`、`docs/business-logic.md`、`docs/data-models.md`、
`docs/screen_spec.md`、`docs/sudo_model.md`、`CLAUDE.md`。
