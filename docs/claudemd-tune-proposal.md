# CLAUDE.md 見直し提案（Claude 5 世代向け・記事の 7 ステップ, 3.505.8）

> **手順 7（反映）は 3.505.9 で実施済み**（ユーザー「次」）。前後の棚卸し: 376 行／42,470 文字／強い語 18 行 → 84 行／約 10,500 文字／強い語 1 行。
> 移した本文は `docs/environment.md`・`docs/backlog.md`・`docs/architecture.md`（主要ファイル）・`docs/history/topics.md`（恒久の事実）・
> `.claude/rules/weights.md`。versionCode の規則は本人判断が無いので従来どおり（毎コミット加算）。

> 出典: nogataka「CLAUDE.md をそろそろ見直す時期かも」（Qiita, 2026-09-05）の手順 1〜6。**本人の承認なしに構造は変えない**。
> このコミットで先に直したのは「実装・環境と食い違う事実」だけ（重み c1/c3mn 30・covO 5、fork のリポジトリ名、ホスト JVM で
> エンジンをビルドできること、消えた `/tmp/cellfix.py`）＝CLAUDE.md 自身の「stale 化させない」規則に従った訂正。

## 手順 1. 棚卸し

| 指標 | 値 |
|---|---|
| 行数 / 文字数 | 376 行 / 42,470 文字（公式の目安 200 行未満の約 2 倍） |
| 見出し | 19 節 |
| 強い語（必ず・禁止・再提案しない・never…）を含む行 | 18 |
| `.claude/rules/` | なし |
| `.claude/skills/` | comment-check / design-review / grilling（この環境に superpowers・dig・genshijin プラグインは**無い**） |

| 節（行数） | 分類 | 扱いの案 |
|---|---|---|
| 冒頭の引き継ぎ（16） | A＋B | 残す。索引の引き方は末尾の節と重複＝1 か所に |
| 教訓メモ（5） | D | 2 行の参照へ |
| 基本方針（6） | C | 判断基準へ書き直し（矛盾 1） |
| スキル自動起動（26） | C＋B | 「どの場面でどのスキルか」1 行ずつへ。プラグイン再導入コマンドは `docs/environment.md`（新設）へ |
| ツール選択ルール Serena（17） | B | 表 3 行を残し、計測の経緯は history へ（既にある） |
| プロジェクト概要（21） | A＋B | 規模上限・片手一本指・DESIGN の参照は残す。package/minSdk は `build.gradle.kts` で分かる＝削除 |
| ビルド/検証（13） | B＋D | ホスト JVM の 1 行と versionCode の規則を残す。CI API の URL 列挙は `tools/host/README` へ |
| アーキテクチャ（30） | B | 「何が正か」（チェッカー＝source of truth・Evaluator/Delta 一致・ViolationComponentRepair の置き場所）だけ残し、ファイル一覧は `docs/architecture.md` へ |
| 制約ファミリーと意味（45） | B | 残す。「旧:」の経緯行は history へ。D3 決定記録は決定記録の節と重複＝1 か所に |
| 目的関数の統一（22） | E | 表は `docs/history/topics.md` へ。原則 2 行を残す |
| ユーザー向け機能（14） | E | 削除（`docs/screen_spec.md` にある）。TallyCard の注は解消済み |
| 業務ルール HF77（7） | C | **禁止形のまま残す**（業務担当者の数値指示＝組織上の制約） |
| 決定記録 D3〜D8・E5（33） | C | **残す**。各 1〜2 行に圧縮（経緯は history） |
| 検証ハーネス（7） | B | 今回書き直し済み |
| ドッグフーディング／ネイティブ／停滞脱出（35） | B＋E | 恒久の事実 1〜3 行ずつ（Kotlin が正・2 層番兵・測ってから採否）。残りは topics.md にある |
| バックログ（57） | E | 打消し済みの #1/#3/#7/#8/#9/#10 を `docs/backlog.md`（新設）へ。開いている #2/#5/#6 の残課題だけ 1 行ずつ |
| スキル／作業の進め方（12） | D | 3 行へ（design-review・grilling・comment-check をいつ使うか） |
| 作業記録の索引（10） | D | 冒頭と統合 |

## 手順 2. 削除（自明・重複）

| 箇所 | 現在 | 理由 |
|---|---|---|
| 概要: package/applicationId・minSdk/compileSdk・API 37 の移行メモ | 5 行 | `app/build.gradle.kts` で分かる。移行手順は history |
| ユーザー向け機能（FixSuggester・TallyCard の 14 行） | 実装済み機能の説明と解消済みの注 | `docs/screen_spec.md`／history にある |
| 目的関数の統一の表（11 行） | 版数付きの乖離解消一覧 | 履歴。原則（チェッカーが正・Δ×フル整合）だけ残す |
| バックログ #1/#3/#7/#8/#9/#10 | 打消し線つきの完了項目 | 完了済み。`docs/backlog.md` へ |
| 索引の引き方（冒頭 11〜12 行目と末尾 367〜376 行目） | 同文が 2 か所 | 1 か所に |
| D3 決定記録（制約ファミリー節と決定記録節） | 同内容が 2 か所 | 決定記録の節に 1 つ |

## 手順 3. 書き直し（ルール → 判断基準）

| 箇所 | 現在 | 提案 |
|---|---|---|
| 基本方針 | 「不明な点は積極的に質問する／質問は常に AskUserQuestion」 | 「読みが分かれて成果物が変わるときだけ止まって聞く（AskUserQuestion、推奨度⭐つき）。それ以外は仮定を明記して進め、完了報告で仮定を示す」 |
| スキル自動起動 | 「タスク着手前にスキル一覧を確認し該当スキルを自動起動する（brainstorming・dig・tdd…）」 | 「この repo に入っているスキル（comment-check・design-review・grilling）を場面で使う。無いスキル名は前提にしない」 |
| genshijin 常時起動 | 「全応答を圧縮体で書く」 | 「簡潔・結論先出し・日本語。コード識別子は英語のまま」（冒頭の 1 行と統合） |
| ビルド/検証「変更ごとに versionCode++」 | 全コミットで加算（docs だけの版も増える） | **本人判断**: コード変更時のみ加算し、docs だけの整備は版数を進めない／従来どおり毎回 |
| 停滞脱出「便益が測れない／負なら入れない」 | 禁止形 | 判断基準として残す（既に基準の形）。ベンチの置き場所（`tools/loop/`）を添える |

## 手順 4. 移動（スキル / rules / 参照ファイル）

| 箇所 | 移動先 | CLAUDE.md に残す 1 行 |
|---|---|---|
| プラグイン再導入コマンド・Serena 起動の経緯・CI API の URL | `docs/environment.md`（新設） | 「環境固有の手順は `docs/environment.md`」 |
| アーキテクチャのファイル一覧 | `docs/architecture.md`（既存）へ統合 | 「何が正か」の 6 行 |
| バックログ全文 | `docs/backlog.md`（新設） | 開いている項目 3 行 |
| 目的関数の統一・ネイティブ加速・停滞脱出の経緯 | `docs/history/topics.md`（既にある） | 恒久の事実 1〜3 行ずつ |
| comment-check の手順説明（スキル自動起動の節） | 既に `.claude/skills/comment-check`（本文あり） | 「コミット前に comment-check」1 行 |
| 「重みを変えたら 5 箇所＋期待値 3 ファイル＋business-logic を同じコミットで」 | `.claude/rules/weights.md`（`paths: MirrorCore.kt, Evaluator.kt, DeltaEvaluator.kt, magi_native.cpp`） | 「重みの変更は HF77 と `.claude/rules/weights.md`」 |

## 手順 5. 残す（禁止形のまま）

| 記述 | 残す理由 |
|---|---|
| HF77（パラメータ/重み/データは業務担当者の明示数値指示＋1 件ずつ） | 組織上の制約。誤変更は採点＝業務結果を変える |
| 決定記録 D3〜D8・E5「再提案しない」 | 業務判断。再提案は本人の時間を奪う |
| 「Kotlin が正、C++/C# は同値の移植」「番兵は削らない」 | 誤った勤務表を出さないための安全装置（不可逆側の防御） |
| 「探索動学の変更は測ってから採否」 | 否決済みの案を作り直す無駄の防止（history に否決一覧） |
| main へのマージは本人の指示で／force-push は自分のブランチのみ | 不可逆な操作の確認ゲート |

## 手順 6. 矛盾の組

| 行 A | 行 B | 判断（案） |
|---|---|---|
| 基本方針「不明な点は積極的に質問する」 | 実運用（長時間の自律作業。質問で止まると作業が止まる） | 判断基準へ書き直す（上表） |
| スキル自動起動「brainstorming・dig・tdd… を自動起動」 | この環境に存在するのは 3 スキルのみ | 「入っているものを使う」へ |
| 旧「このサンドボックスは Kotlin もコンパイル不可」 | `tools/host/hosttest.sh` で 610 テストを毎回回している | **訂正済み**（このコミット） |
| 制約ファミリー「c1 重み4」「c3mn 重み12」「covO 1.0」 | 階層行と `MirrorKeys.weights`（30/30/5.0） | **訂正済み**（このコミット） |
| 「変更ごとに versionCode++」 | docs だけの版が 3.505.2/3.505.3/3.505.5/3.505.7 と増えている | 本人判断（上表） |

## 見直し後の CLAUDE.md（全文案・約 120 行）

```markdown
# CLAUDE.md — MAGI ShiftOptimizer（Android）

看護師のシフト表を最適化する Android アプリ（Kotlin/Compose）。設計・仕様・業務ルールは README の目次から `docs/*.md`
（業務ルール＝`docs/business-logic.md`、データ項目＝`docs/data-models.md`、全体像＝`docs/sudo_model.md`）。
作業記録は `docs/history/INDEX.md` で当たりを付けて本文を版数で引く。**同じ領域を触る前に索引を引く**＝測って否決した案・
同型のバグ・決定記録を二度踏まないため。新しい記録は INDEX の先頭 1 行＋`docs/history/3.4xx.md` の先頭へ。
応答は簡潔・結論先出し・日本語。コード識別子は英語のまま。

## 環境の注意点（見ても分からない罠）
- Android のビルドはこのサンドボックスでは不可（CI の Release Build / Android SDK）。エンジン層と JUnit は
  `tools/host/hosttest.sh` でホスト JVM で回る（約 1 分、`MAGI_HOST_OUT` で出力先を分けるとベンチ中でも安全）。
- 規模の上限は職員 30 名・31 日。ビット化経路（`C3nBitScan`・C++ `SaChunk`）はこの範囲で常に有効。
- Kotlin が正。C++（`magi_native.cpp`）と C#（`-MAGI_PC`）は同値の移植。`Evaluator.kt`/`MirrorCore.kt`/`DeltaEvaluator.kt` を
  変えたら C++ を同じコミットで（`.claude/rules/weights.md`）、C# は同日に同期。パリティは CI（native-parity）が守る。
- 2 層番兵（C++ 自己整合＋Kotlin `fullEval` 照合）は正しさの根幹＝削らない。不一致なら Kotlin へ退化する。
- 片手一本指（ドラッグ不可）・最小デザイン。色/角丸/影は `docs/DESIGN.md` と `tools/design_lint.py`。
- Serena は名前の分かるシンボルの定義/参照に使う。意味検索・文言・docs は Grep。初期化に失敗したら Grep で進める。
- 環境固有の手順（プラグイン再導入・CI API・probe の走らせ方）は `docs/environment.md`。

## 判断の基準
- チェッカー（`UnifiedViolationChecker`）が source of truth。最適化器（Evaluator/Delta）は同じ目的関数（Δ×フル整合）。
- 研磨は keep-best（`betterReport`＝hard→weightedScore→total）。新しい採用基準を増やさない。
- 探索動学の変更は測ってから採否（`tools/loop/` のペア比較か実データ 4 件の probe）。便益が測れない／負なら入れない。
- 読みが分かれて成果物が変わるときだけ止まって聞く（AskUserQuestion、推奨度⭐つき）。それ以外は仮定を明記して進める。
- 周囲のコードのコメント密度・命名に合わせる。コミット前に comment-check で追加コメントを 1 行ずつ判定する。
- 画面を触ったら design-review、非自明な変更の前は grilling。

## 制約ファミリーと重み（実装＝`MirrorKeys.weights` が正）
（現行の「制約ファミリーと意味」節を、経緯行を除いて 25 行程度に。HARD 4 族・SOFT 15 族・重み階層・
c3 族の評価モデル・apt/fair/weekly の定義・被覆は同日のみ）

## 実行前に確認を取る操作／変えない決定
- **HF77**: パラメータ・重み・データ値は業務担当者の明示数値指示＋1 件ずつ。コメントの主張と実装は grep で照合。
- **決定記録（再提案しない）**: D3 apt/weekly/fair の重み 1 ／ D4 360dp 帯（OPPO A5 5G のみ対象、3.497.0）／
  D5 年度末モード不要／ D6 標準値 vs 月別例外の差分表示不要／ D7 読取モード不要／ D8 外観は UD 固定／ E5 月全体の俯瞰は明示 go まで保留／
  ws8/ws9・Cells()→CodeName・staff タイプ自動判別・LimMax 自動設定は実装不要。
- main へのマージは本人の「mainにマージする」で。force-push は自分の作業ブランチだけ。

## 必要時に読むもの
- `docs/architecture.md`（ファイルと役割）、`docs/backlog.md`（開いている課題）、`docs/history/topics.md`（ネイティブ加速・停滞脱出・
  目的関数統一の経緯）、`docs/lessons.md`（教訓。追記でなく更新）。
```
