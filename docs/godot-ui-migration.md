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
- `MagiBridge.kt`（Android依存・`MagiViewModel`を保持）: `snapshot()`でUiStateの主要フィールドを
  JSONへ直列化し、`dispatch(op, argsJson)`で許可リスト・必須引数・トークンを検査したのち
  `Handler(mainLooper)+CountDownLatch`でメインスレッドへ移送してViewModelの対応メソッドを呼ぶ。
  既存`MagiViewModel*.kt`は**1行も変更していない**。

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
- `app/build.gradle.kts`: `-PmagiGodot=true`のときのみ`org.godotengine:godot:4.5.1.stable`依存と
  `src/godot/java`ソースディレクトリを追加。既定(false)では追加しない＝既存ビルドに影響なし。
- `app/src/godot/java/com/magi/app/godot/MagiGodotActivity.kt`: `GodotHost`実装＋`GodotFragment`埋め込み。
  既存`MainActivity`（ランチャー）は変更していない。`AndroidManifest.xml`には`exported=false`で
  宣言のみ追加（`magiGodot=false`時はこのクラス自体がコンパイルされないが、宣言だけは残る＝
  マニフェストマージはクラスの実在を検証しないため既存ビルドの通過には影響しない）。
- `tools/godot-ui-check.sh`: GDScriptの粗い構文チェック（gdtoolkit があれば`gdlint`、無ければ括弧対応の
  簡易チェックにフォールバック）とシーン参照の静的整合性確認。

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
- Android Gradleビルド（`./gradlew assembleDebug -PmagiGodot=true`等）そのもの。
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
  実装は仕様どおりで変更不要と判断した。
- **非同期順序**: `MagiBridge.dispatch`は`Handler(mainLooper)+CountDownLatch`で常にメインスレッドへ
  同期的に移送する設計（呼び出し元スレッドは`dispatch`の戻りを待つ）。Godot(GDScript)側の
  `MagiApi.dispatch`も呼び出しごとに結果を待ってから次の操作を組み立てる作りのため、連続呼び出しの
  後勝ち/早い者勝ちの矛盾は起きない。シーケンス番号の追加は不要と判断した。
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
- `MagiGodotActivity`のビルド可否・`GodotFragment`の正しい生成方法は実機/実際のGodot Androidテンプレート
  との突き合わせが必要（Godot公式のAndroidプラグイン雛形を精査していない）。
- Godot本体の`.tscn`/`.gd`はテキストとして手書きしたものであり、Godotエディタで一度も開いていない。

## 参照した既存仕様

`app/src/main/java/com/magi/app/ui/{MagiViewModel,MagiUiState,MagiScheduleViews,Ws1Editor,
ConstraintEditor,AnalysisTriage,StaffShiftMatrix}.kt`、`docs/business-logic.md`、`docs/data-models.md`、
`docs/screen_spec.md`、`docs/sudo_model.md`、`CLAUDE.md`。
