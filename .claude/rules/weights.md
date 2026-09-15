---
paths:
  - "app/src/main/java/com/magi/app/v6/MirrorCore.kt"
  - "app/src/main/java/com/magi/app/v6/Evaluator.kt"
  - "app/src/main/java/com/magi/app/v6/DeltaEvaluator.kt"
  - "app/src/main/java/com/magi/app/v6/DestroyRepairMarginalCost.kt"
  - "app/src/main/java/com/magi/app/v6/RangePolish.kt"
  - "app/src/main/java/com/magi/app/v6/C1TemporalFlowPolish.kt"
  - "app/src/main/java/com/magi/app/v6/DayAssignmentPolish.kt"
  - "app/src/main/cpp/magi_native.cpp"
---

# 目的関数・重みを触るときの規約

- 重みの単一の真実は `MirrorKeys.weights`。変えるときは HF77（業務担当者の明示数値指示＋1 件ずつ）が前提。
- 同じコミットで揃える: `MirrorKeys.weights`・`Evaluator.fullEvalParts` のリテラル・`DeltaEvaluator` の集約式・
  **destroy-repair/polish系の重複リテラル**（`DestroyRepairMarginalCost.kt`・`RangePolish.kt`(2箇所)・
  `C1TemporalFlowPolish.kt`・`DayAssignmentPolish.kt`(2箇所、`45L * maxOf(...)` の**乗数が左**＝
  `grep '\* 45L'` では引っかからない逆順パターン)＝いずれも soft-aware repair/marginal cost の候補選択で
  checker と同じ式を再実装している。2026-09-10 の high 45→25 変更で当初この4ファイルが `paths:` に無く
  見落としかけた）・`magi_native.cpp`（評価器と SaChunk。high の場合は `fullEvalParts`相当・
  `SaChunk::contribRangeApt`・`staffCountPenaltyAtN`の3箇所＋コメント1箇所）・
  言語跨ぎ期待値 3 ファイル（`app/src/test/resources/*_eval_expected.txt`**と C# 側のミラー
  `-MAGI_PC/windows/MagiEngine.Tests/Fixtures/*_eval_expected.txt`＝別リポジトリの別コピーなので
  片方だけ直すと `CrossLanguageFixtureTest` が落ちる**）・
  テスト内の重複リテラル（`grep -rn '\* <旧重み>L\|to <旧重み>\.0' app/src/test/java` **と
  `grep -rn '<旧重み>L \*' app/src/test/java`（逆順）で必ず両方向を確認する**。
  2026-09-10 は `WeeklyFairMarginalTest.kt`・`DeltaEvaluatorTest.kt`・`V6NativeOptimizerChoiceTest.kt`・
  `WideC3nFixtureTest.kt`（golden weightedScore）にも見つかった）・
  `docs/business-logic.md`・CLAUDE.md の重み階層行。C#（-MAGI_PC）は同日に同期。
- **重み階層に依存する表示分類**も対象（数値だけでなくロジック）: `ShiftAppearance.severityFromVioKey`
  （Kotlin/C# 両方）と `MagiScheduleViews.kt` の `heavySoftFamilies` は「表示強度＝重み階層」という
  設計原則を明記しているため、重みの大小関係が変われば分類も追従が必要（2026-09-10、high 45→25 で
  c1/c3mn(30) を下回ったため heavySoftFamilies から除外・severityFromVioKey を HIGH→WARN へ変更。
  読みが分かれる判断のため AskUserQuestion で確認した）。
- **コメントで説明している不等式が値によって成否が変わる箇所**は数値だけでなく前提ごと見直す
  （`C1WindowPolish.kt`/`V6HotfixPasses.C1Window.cs` の「X追加は low/high>c1 で必ず isBetter に棄却される」
  という手R1/R2追加の根拠は high 45→25 で単窓局面において不成立になり、`C1RelocationPolishTest.kt`/
  `V6HotfixPassesC1WindowTest.cs`（Kotlin/C# 双方）のテスト前提も同時に壊れた＝該当テストの再設計が要る）。
- Δ（DeltaEvaluator）とフル（Evaluator）は常に一致させる（`SaOptimizer` が整合チェックする）。チェッカー
  （`UnifiedViolationChecker`）が source of truth で、最適化器はそれへ寄せる。
- コメントの主張と実装値を grep で照合してからコミットする（この領域は stale コメントの前科がある）。
