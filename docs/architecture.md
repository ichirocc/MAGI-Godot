# MAGI Android — モジュール構成と依存・呼び出し関係（知識グラフ）

本書は **MAGI ネイティブ（Android / Kotlin / Godot 4.5.1 UI / MVVM）** の主要モジュール・サービスと、その依存・呼び出し関係を **entities / relations / observations** 形式でまとめたもの。各 entity の役割は短い observation として併記する。関係は実コード（import / 参照）で裏取りしている。画面の挙動は `screen_spec.md`（Compose 時代の仕様）、Godot UI の実装範囲は `godot-ui-migration.md` を参照。**Compose UI は 3.549.0 で削除。**

- リポジトリ: `ichirocc/magi7ichiro` ／ パッケージ: `com.magi.app`
- ソース: `app/src/main/java/com/magi/app/`（パッケージ: `model/` `v6/` `ui/`（ViewModel と純ロジックのみ） `work/` `godot/`）
- 画面: `godot/`（`project.godot`・`scenes/*.tscn`・`scripts/*.gd`。Gradle の `exportGodotPck` が `assets/magi.pck` へ同梱）

---

## レイヤ概観（上から下へ）

```
MagiGodotActivity (FragmentActivity + GodotHost)
        │ hosts GodotFragment ／ registers
        ▼
MagiBridge  ◀── JavaClassWrapper（GDScript: godot/scripts/magi_api.gd） ── Godot 画面群（10画面, nav.gd）
        │ snapshot()=UiState の不変 JSON ／ dispatch(op,args)=MagiOpWhitelist で検査しメインスレッドへ
        ▼
MagiViewModel  ── produces ─▶ UiState
        │ holds
        │ ▶ MagiState（ドメイン＝JSONスキーマ）
        │ calls
        ▼
V6NativeOptimizer（最適化エンジン核）
   ├ uses ─▶ SaOptimizer / V6 operators / V6 seeders / V6 web-parity
   ├ scores-with ─▶ Evaluator / DeltaEvaluator ── use ─▶ MirrorCore（18違反種・重み）
   └ depends-on ─▶ Problem ◀── built-from ── MagiState

OptimizationWorker（WorkManager 前景サービス）── runs ─▶ V6NativeOptimizer
StateParser（JSON I/O） / ScheduleCsvBridge（CSV I/O）── map ─▶ MagiState
```

役割の分担：**Godot UI** は表示と操作のみ（Kotlin の可変状態に触れず JSON スナップショットとトークン付き dispatch だけ）、**ViewModel** が唯一のハブ（状態・操作・最適化起動・I/O）、**v6 エンジン**が探索本体、**model** がデータ、**work** が中断耐性のある背景実行。

---

## Entities（type ｜ 役割＝observation）

### アプリ基盤
| Entity | type | 役割 |
|---|---|---|
| `MagiGodotActivity`（`godot/`） | Activity | アプリ唯一の入口。`GodotFragment` を埋め込み、`MagiBridge` を生成して static エントリ（`magiSnapshot`/`magiDispatch`）に登録。`--main-pack res://magi.pck` を渡す |
| `MagiBridge`（`godot/`） | Bridge | `UiState`/`MagiState` を JSON へ直列化（`snapshot`）、許可された操作をメインスレッドで `MagiViewModel` へ委譲（`dispatch`、10 秒上限） |
| `MagiOpWhitelist` / `MagiBridgeToken`（`godot/`） | Bridge-Logic | 操作名と必須引数の許可リスト／snapshot 本文 SHA-256＋リビジョンのトークン（純 Kotlin、ホストでテスト） |
| `godot/scripts/magi_api.gd` ・ `nav.gd` ・ `screens/*.gd` | Godot-UI | autoload の API ラッパ（非 Android ではモック）、10 画面のタブ切替、各画面の描画と dispatch |
| `MagiViewModel` | ViewModel-Hub | **中央ハブ**。状態保持・全操作・最適化起動・I/O・`UiState` 生成（最大級・約124KB） |
| `UiState` | UI-State | UI 表示用の派生状態（違反/breakdown/schedule/色/満足度 等） |

### ドメイン・データ（model）
| Entity | type | 役割 |
|---|---|---|
| `MagiState` | Domain-Model | ドメイン状態＝**JSON 入出力スキーマ**（shifts/groups/staff/cons1..42/wishes/staffRange/shiftColors） |
| `StateParser` | IO-JSON | JSON ↔ `MagiState` の解析・直列化 |

### 最適化エンジン（v6）
| Entity | type | 役割 |
|---|---|---|
| `Problem` | Engine-Model | `MagiState` から構築する最適化問題（次元・制約・重み） |
| `V6NativeOptimizer` | Engine-Core | **主最適化器**。SA + ALNS + GLS + Tabu + Path Relinking を統括、最良解を研磨（約70KB） |
| `SaOptimizer` | Engine-SA | 焼きなまし（Metropolis 基準）本体 |
| `Evaluator` / `DeltaEvaluator` | Engine-Scoring | 違反スコアの計算 / 差分評価（高速化） |
| `MirrorCore`（`MirrorKeys`） | Constraint-Defs | **18 違反種と重み**＝`weightedScore` の唯一の真実 |
| `V6SearchOperators` / `V6LateOperators` / `V6SwapSuggester` | Engine-Operators | 近傍・交換・修復などの探索手 |
| `SmartInitialScheduler` / `GreedyMirrorScheduler` | Engine-Seed | 初期解の生成（後者はテスト専用の旧生成器） |
| `V6SanityPort` / `V6FinalPort` / `V6PortAnalyzer` | Engine-Facade | 事前診断・UI 向けファサード・分析層 |
| `ShiftAppearance` | UI-Support | シフト記号→表示色 と 違反キー→重大度 の唯一の解決元（3.393.0 に `V6WebCompat` から切り出し） |
| `ScheduleCsvBridge` | IO-CSV | 勤務表 / 希望 CSV ↔ `MagiState`（文字コード自動判定） |

### 背景実行（work）
| Entity | type | 役割 |
|---|---|---|
| `OptimizationWorker` | Background-Service | WorkManager の**前景サービス**で最適化を実行。中断耐性・スナップショット。通知のタップ先は `MagiGodotActivity`（3.549.0 で会話バブルは廃止） |

### UI 補助（ui、Compose 非依存）
| Entity | type | 役割 |
|---|---|---|
| `AnalysisTriage` / `BreakdownLabels` / `VioBuckets` | UI-Logic | 分析の優先付け・内訳ラベル・違反の集計（ホストでテスト） |
| `ConstraintHelp` / `JapanHolidays` | UI-Logic | 制約族の説明文・祝日表 |

（Compose の画面・部品＝`MagiApp`・`MagiScheduleViews`・各 Editor・`Affordance`/`MagiComponents`/`MagiTokens` は 3.549.0 で削除。
画面は `godot/scenes` へ移行、実装状況は `godot-ui-migration.md` の表）

---

## Relations（呼ぶ・依存する など）

UI 層
- `MagiGodotActivity` **hosts** `GodotFragment` ／ **creates** `MagiBridge`
- Godot 画面群（`screens/*.gd`）**call** `magi_api.gd` → `JavaClassWrapper` → `MagiGodotActivity.magiSnapshot/magiDispatch` → `MagiBridge`
- `MagiBridge` **reads** `MagiViewModel.uiState`/`state` ／ **checks** `MagiOpWhitelist`, `MagiBridgeToken` ／ **calls** `MagiViewModel`（メインスレッド）

ViewModel ハブ
- `MagiViewModel` **produces** `UiState`
- `MagiViewModel` **holds** `MagiState`
- `MagiViewModel` **calls** `V6NativeOptimizer`
- `MagiViewModel` **enqueues**（WorkManager 経由）`OptimizationWorker`
- `MagiViewModel` **uses** `StateParser`（JSON）, `ScheduleCsvBridge`（CSV）

エンジン（v6）
- `OptimizationWorker` **runs** `V6NativeOptimizer` ／ **uses** `StateParser`
- `V6NativeOptimizer` **depends-on** `Problem`
- `V6NativeOptimizer` **uses** `SaOptimizer`, `V6SearchOperators`/`V6LateOperators`/`V6SwapSuggester`, `SmartInitialScheduler`/`GreedyMirrorScheduler`, `V6SanityPort`/`V6FinalPort`/`V6PortAnalyzer`
- `V6NativeOptimizer` **scores-with** `Evaluator` / `DeltaEvaluator`
- `DeltaEvaluator` **builds-on** `Evaluator`
- `Evaluator` **depends-on** `Problem`
- `Problem` **built-from** `MagiState`
- `Evaluator`系・`MagiViewModel` **use** `MirrorCore`（重み）

データ・I/O
- `StateParser` **parses/serializes** `MagiState`（JSON）
- `ScheduleCsvBridge` **maps** `MagiState`（CSV）

---

## 主要フロー（呼び出し連鎖）

1. **起動**: `MagiGodotActivity` → `GodotFragment`（`assets/magi.pck`）→ `Home.tscn` → `magi_api.gd.refresh()` → `MagiBridge.snapshot()`（`MagiViewModel` の `UiState` を JSON で受ける）。
2. **最適化（前景）**: ユーザ操作 → `dispatch("optimize")` → `MagiViewModel.optimize()` → `OptimizationWorker`（前景サービス）→ `V6NativeOptimizer`（seed → SA/ALNS/operators、`Evaluator`/`DeltaEvaluator` で採点、`MirrorCore` の重みで `weightedScore`）→ 結果を `MagiViewModel` → `UiState` → 次の `snapshot()` で画面反映（中断時はスナップショットから復帰）。
3. **編集 → 再最適化**: 画面の dispatch（トークン付き）→ `MagiViewModel` が `MagiState` 更新（自動保存 JSON）→ `Problem` 再構築 → 再最適化。
4. **保存/読込/取込**: JSON は `StateParser`、CSV は `ScheduleCsvBridge` を介して `MagiState` と相互変換。

---

## 補足
- 本書は「主要モジュール」を対象とした要約であり、全ファイルの網羅ではない（`v6/` には Hotfix/解析系の補助ファイルも存在する）。
- `Hf63Infeasibility` は呼び手が自己テストのみで実質死蔵（Web 側と同様）。
- 関係は import / 参照に基づくが、実行時の動的呼び出しの一部は含まれない場合がある。
- 関連ドキュメント: Godot UI＝`godot-ui-migration.md`、画面挙動（Compose 時代の仕様）＝`screen_spec.md`、デザイン基盤＝`magi_design_system.md`、エンジン移植＝`v6_engine_native_port.md`。

## 主要ファイルと役割（CLAUDE.md から移設, 3.505.9）

エンジンは `app/src/main/java/com/magi/app/v6/`:
- `MirrorCore.kt` — **`UnifiedViolationChecker`（UIの違反表示・提案の基準＝source of truth）**。
  `check(state, schedule) -> ViolationReport{violations, needViolations, countViolations, breakdown, hard, total, weightedScore}`。
  `Problem`（`cachedProblem(state)`）, `canDo(i,k)`（担当可否＝評価・表示）, `mayPlace(i,k)`／`allowedShiftsForStaff(i)`（最適化器が置けるか＝
  担当可から個人上限 0 を除く。3.507.0）, `canDoShiftsForStaff(i)`（UI の選択肢）, `countMatrix`, `coverage`,
  `normalizeSchedule`。`MirrorKeys`（hard/soft/all のキー分割）と weightedScore の重み定義もここ。
- `Evaluator.kt` / `DeltaEvaluator.kt` — **最適化器の目的関数**（SA の受理判定）。`Evaluator(p)`（3.393.0 で `c3RunMode` は撤去＝単一シフト連は常に run-deficit）。
  Delta は差分評価。`SaOptimizer` が Delta×Full の整合チェック（安全網）を行うため**両者は常に一致させる**。
- `C3Run.kt` — `isSingleShiftSeq(seq)`, `rowDeficit(a,i,k,L)`（単一シフト連の不足評価）。
- `V6FinalPort.kt` — `handleOptimize`（最適化オーケストレーション）, `handleCheck`（UnifiedViolationChecker）。
  最終番兵 `checkResultWorse`（入力より悪化したら入力へ復帰）。
- `V6NativeOptimizer.kt`/`V6HotfixPasses.kt`/`V6LateOperators.kt`/`V6SearchOperators.kt` — 探索本体・各オペレータ。
- `ViolationComponentRepair.kt` — **違反起点のトランザクション修復**（Iteration 2 第一弾, 3.505.0）。各研磨パスが単独で不採用にした候補
  （`CombinatorialRepair.Candidate`＝`CyclicSwapResult.rejectedCandidates` で巡ごとに集める）を、違反（セル/回数/人数）を起点に
  「主候補＋職員か日を共有する助候補」へ絞り、`DeltaEvaluator` の推定＋厳密ピンの事前枝刈りでビーム、commit は正式チェッカーの
  `betterReport`。3.505.4 から起点からの候補生成（半径 1）を**共同 LNS の後の最終段**でだけ行う（`componentRepairFinal`。巡の中で行うと
  単セル covU 修正が LNS の余地を先に使い実データで HARD 退行）。`PostOptimizationParams.componentRepairEnabled`（3.505.1 で**既定 ON**＝Iteration 2 のベンチで必須退行 0・新良 68/同等 249/旧良 23、
  10% ゲートは未達なので §6 のハイブリッド併用として温存。数値は `docs/history/3.4xx.md`）。
- `V6SwapSuggester.kt` — **`FixSuggester.suggest(...)`**（ユーザー向け修復提案。7種の手を探索）。
- `Problem.kt` — `C1(day1,shiftIdx,day2)` 等の制約データ型。

ViewModel は `app/src/main/java/com/magi/app/ui/`:
- `MagiViewModel.kt` — 状態管理。`findFixSuggestions`/`applyFixSuggestion`、`refreshCheck`(currentSchedule検査)。
  ジョブ: `job`/`checkJob`/`fixJob`（連続タップ競合回避）。ws1系・制約系は `MagiViewModelWs1.kt`/`MagiViewModelConstraints.kt` の拡張関数。
- `MagiUiState.kt` — `schedule`, `staffNames`, `staffGroupSymbols`, `shiftSymbols`, `countViolations("i,k")`,
  `needViolations("k,j")`, `resultSchedule`, `breakdown` 等。

UI は `godot/`（Godot 4.5.1）＋ `app/src/main/java/com/magi/app/godot/`:
- `godot/scripts/magi_api.gd` — autoload。`JavaClassWrapper.wrap("com.magi.app.godot.MagiGodotActivity")` で `magiSnapshot`/`magiDispatch`。
- `godot/scripts/nav.gd` — 10 画面（Home/Schedule/StaffGroupsShifts/MonthWishesCounts/AptSkills/Constraints/Analysis/Settings/JsonEditor/UndoExport）のタブ切替。
- `godot/scripts/screens/base_screen.gd` — `run_op`（失敗時は snapshot から再描画）・status 表示の共通基底。
- `MagiGodotActivity.kt` / `MagiBridge.kt` / `MagiOpWhitelist.kt` / `MagiBridgeToken.kt` — 上の表を参照。
