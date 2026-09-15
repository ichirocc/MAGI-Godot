# CLAUDE.md — MAGI ShiftOptimizer（Android）

看護師のシフト表を最適化する Android アプリ（内蔵エンジン MAGI V6＝Kotlin。UI は Godot 4.5.1（`godot/`）＋Kotlin ブリッジ
`app/src/main/java/com/magi/app/godot/`、Compose UI は 3.549.0 で削除＝`docs/godot-ui-migration.md`）。設計・仕様・業務ルールは README の目次から
`docs/*.md`（業務ルール＝`docs/business-logic.md`、データ項目＝`docs/data-models.md`（存在しない項目を創作しない）、全体像＝`docs/sudo_model.md`）。
コードを改修したら影響する文書と README の目次・最終更新を**同じコミットで**更新する。
作業記録は `docs/history/INDEX.md` で当たりを付けて本文を版数で引く（`grep -n 'キーワード' docs/history/INDEX.md` → `grep -n '（3.409.21' docs/history/3.4xx.md`）。
**同じ領域を触る前に索引を引く**＝測って否決した案・同型のバグ・決定記録を二度踏まないため。新しい記録は INDEX の先頭 1 行＋`docs/history/3.4xx.md` の先頭へ。
教訓は `docs/lessons.md` を**更新**する（新しいメモを作らない）。応答は簡潔・結論先出し・日本語。コード識別子は英語のまま。

## 環境の注意点（見ても分からない罠）
- Android のビルドはこのサンドボックスでは不可（CI の Godot UI Check が main / `claude/**` への push で APK まで作る）。エンジン層と JUnit は
  `tools/host/hosttest.sh` でホスト JVM で回る（約 1 分。`MAGI_HOST_OUT` で出力先を分けるとベンチ中でも安全）。Android 依存層
  （`MagiBridge`・`MagiGodotActivity`・`work/OptimizationWorker`）と Godot 実行は不可（`tools/godot-ui-check.sh` は静的確認のみ）。
- 規模の上限は職員 30 名・31 日（業務前提）。ビット化経路（`C3nBitScan`・C++ `SaChunk`）はこの範囲で常に有効＝スカラー経路は防御。
- **Kotlin が正**。C++（`magi_native.cpp`）と C#（`ichirocc/-MAGI_PC`）は同値の移植。評価器を変えたら C++ を同じコミットで
  （`.claude/rules/weights.md`）、C# は同日に同期。パリティは CI（native-parity）が守る。
- 2 層番兵（C++ 自己整合＋Kotlin `fullEval` 照合）は正しさの根幹＝削らない。不一致なら `NativeGate` が閉じて Kotlin へ退化する（誤出力でなく速度低下として現れる）。
- 片手一本指（ドラッグ不可、例外は編集タブのシフト種別/グループ/職員一覧の並び替えのみ＝3.515.6・3.530.0 ユーザー明示指示）・最小デザイン。色/角丸/影は `docs/DESIGN.md` の原則と `tools/design_lint.py`。
- Serena は名前の分かるシンボルの定義/参照に使い、意味検索・文言・docs は Grep。初期化に失敗したら Grep で進める。
- リポジトリは `ichirocc/magi7ichiro-fork`（作業用 fork）。環境固有の手順（CI 監視・probe・プラグイン）は `docs/environment.md`。

## 判断の基準
- チェッカー（`UnifiedViolationChecker`）が source of truth。最適化器（`Evaluator`/`DeltaEvaluator`）は同じ目的関数（Δ×フル整合）。
- 研磨は keep-best（`betterReport`＝hard→weightedScore→total の辞書式、単一ソースは `reportComparator`）。採用基準を増やさない
  （例外＝apt/fair研磨限定の`PolishGate.aptFairSoftTolerance`、既定OFF・3.535.0 HF77明示数値指示。詳細は`docs/business-logic.md`）。
- 探索動学の変更は測ってから採否（`tools/loop/` のペア比較、または実データ 4 件の probe で最終盤面のハッシュ比較）。便益が測れない／負なら入れない。
  否決済みの案は history にある（戦略的振動・nonlinear restart・GLS スイープ・targeted-perturb・big-destroy・softFocusProb）。
- ソフト研磨は構造的下限（3.94.0）。効き所は「解ける HARD が残る盤面」＝`ViolationComponentRepair`（拒否候補の結合は巡の末尾、
  起点からの候補生成は共同 LNS の**後**の最終段。巡の中で単セル covU 修正を採ると LNS の余地を先に使う）。
- 読みが分かれて成果物が変わるときだけ止まって聞く（AskUserQuestion、選択肢に推奨度⭐と理由）。それ以外は仮定を明記して進める。
- 周囲のコードのコメント密度・命名に合わせる。コミット前に `comment-check` で追加コメントを 1 行ずつ判定する。
- 画面（`godot/scenes`・`godot/scripts`）を触ったら `docs/DESIGN.md` の原則（色/角丸/影・片手一本指）に照らす（Compose 向けの
  `design-review` スキルは 3.549.0 で削除）。非自明な変更・仕様判断の前は `grilling`（一問ずつ、推奨案つき）。
- この repo に入っているスキルは `.claude/skills/` の 2 つ（`comment-check`・`grilling`）。無いスキル名を前提にしない。

## 制約ファミリーと重み（実装＝`MirrorKeys.weights` が正）
- **c1**（窓制約, SOFT, 重み50）: `C1(day1=窓, shiftIdx=単一シフト, day2=最低数)`。窓day1内にshiftIdxがday2回以上。
  **担当不可スタッフは対象外（canDoガード）**。構造上単一シフトのみ（複数種類変種なし）。
- **c2**（職員別合計, SOFT, 重み4）。
- **c3族**（ws4の列パターン。ws3=希望シフトとは別物）:
  - c3 = MUST/want（SOFT, 重み15）, c3m = Want（SOFT, 重み10）— **非forbidden**。
  - c3n = FORBIDDEN（HARD, 重み9000）, c3mn = Hate（SOFT, 重み90）— **forbidden**。
  - **c3w**（希望の前日に禁止, HARD, 重み9000＝c3n 同格, 3.542.0）: `C3wRow(wishKigou, prevKigou)`＝希望(`wishLocked`)で固定した X の
    **前日**セルが Y なら違反（前日側セルが違反箇所）。希望でない X・初日は対象外。静的表 `Problem.c3wBan` を3者＋C++＋`makesForbiddenRun` が共有。
  - 評価モデル: **非forbiddenの単一シフト連 → run-deficit**（C3Run.rowDeficit。完成runを罰しない）。
    それ以外（複数シフト連 / forbidden）→ **窓マッチ #fire**。
- **c41/c42**（群/日 範囲, SOFT, 重み1）／**c41s/c42s**（スキル群変種, SOFT, 重み6）。
- **covU**（人員不足, HARD, 重み10000）/ **covO**（人員過剰, SOFT, 重み10）。被覆は同日のみ（夜勤繰越なし）。
  ※ covO 重みは 0.5→1.0（2026-07-13）→5.0（2026-08-27）→10（3.522.0、全面見直し＋SOFT中「上限超過(high)>人員過剰(covO)」
  指示）、いずれも HF77 明示指示。最適化器とチェッカーは同じ値。
  need1=P1, need2=P2。lo=need1, hi=(use2 && need2>=0 ? need2 : need1)。MIN/OR条件は2世代前からの意図的設計。
- **low/high**（staffRange=各職員の各シフト回数の下限/上限, SOFT, 重み120/25。amount計上）。
  **上限 0（hi=0、休を除く）は最適化器が置かない**（`Problem.mayPlace`＝候補生成・入口 hf66・最終番兵の基準。評価・表示は high 25 のまま、
  希望固定は優先。3.507.0 ユーザー決定「最適化器だけ除外、表示は今のまま」）。
- **apt**（適切回数=`groupShiftApt[群][シフト]` の**群単位双方向目標**, SOFT, 重み4, L1偏差`|回数-目標|`）。
  担当可シフトのみ有効（`Problem.apt` 構築時に bucket=canDo ガード）。不足=赤(vio-aptLow)/超過=橙(vio-aptHigh)。
  **個人の下限または上限が入っている (職員,シフト) には群目標を適用しない（決定 D9, 3.509.0。空欄だけのキーは未設定扱い）。**
  適用される組は「構造的に到達できる範囲」へクランプ（3.508.0: 到達下限 = T − Σ他の置けるシフトの実効上限、到達上限 = T − Σ他シフトの実効下限、
  実効値は希望固定込み。例: 休の希望固定 15 日の職員に 休 目標 10 → 15）。評価は実効目標 `Problem.apt`（個人設定あり＝-1）、
  設定ミス診断 6b/6d/6-C は設定値 `Problem.aptRaw` を読む。低/高(staffRange) とは別系統（LimMin/LimMax は別画面 ws5）。
  上限超過(high)がapt/fairの同時解消を阻む例＝B1のaptHigh（3.522.0、docs/history/3.4xx.md）。
- **fair**（グループ内公平化, SOFT, 重み2, L1偏差）。群×担当ONシフト(`bucket[g]`)ごとに`Problem.fairDevOfBucket`
  が偏差を出す。**達成率モード v2**（3.541.0、ユーザー指示「平均に入れる率の課題を深く解決する」＝案E。初版 3.538.0）:
  母集団＝`mayPlace` または回数>0 のメンバー（担当不可で0回は除外）。基準＝範囲staffRange.lo/hi両方有限→帯 [lo,hi]
  （中心(lo+hi)/2・半幅(hi−lo)/2、**回数は帯へクランプ**＝帯の外は high/low の担当）、無ければ実効apt目標（中心t・幅t）。
  達成率=(回数−中心)/幅、基準達成率＝**幅を重みにした中央値**（Σ幅×|達成率−基準|を最小にする点）、各人の偏差＝
  `round(|達成率−基準|×自分の幅)`（幅0は0）。1人でも基準が無ければ同じ母集団で従来の生回数`round(平均)`のL1偏差へ
  フォールバック。`Problem.groupMembers`使用、m<2は0。目的関数(Evaluator/Delta)/チェッカー3者と
  C++(`magi_native.cpp`の`fairDevOfBucket`)に統合。UI内訳チップには出さない（常時非ゼロになりやすいため
  weightedScore/total のみ算入）。
- **weekly**（7日周期(曜日)シフト平準化, SOFT, 重み2, L1偏差）。職員ごとに勤務日(非休)の**曜日別カウント**の
  `round(勤務日数/7)` からの L1 偏差和。weekday(j)=`(dow0+j)%7`（`Problem.dow0`=startDate曜日オフセット %7 /
  `Problem.restIdx`=休index）。「毎週おなじ曜日に偏る」を均す。共通ソース=`weeklyDevOfBucket(wd[7])`。
  Evaluator/Delta/チェッカー3者に統合（fairと同型）。UI内訳では「曜日の偏り」チップに件数表示（場所マップは無し）。
- **pref**（希望シフト未充足, HARD, 重み8000）/ **groupViol**（群外シフト, HARD, 重み11000）。

weightedScore 階層: groupViol(11000) > covU(10000) > c3n(9000)=c3w(9000) > pref(8000) > low(120) >
c3mn(90) > c1(50) > high(25) > c3(15) > c3m(10)=covO(10) > c41s(6)=c42s(6) > c2(4)=apt(4) >
fair(2)=weekly(2) > c41(1)=c42(1)。（3.522.0で全面見直し＝tools/loop 34ケース×10seedの
baseline対比ベンチマークで決定。旧: groupViol(10000) > pref(9000) > covU(8000) > c3n(7000) > low(90) >
c3mn(30)=c1(30) > high(25) > covO(5) > c3(3) > c3m(2) > c2/c41/c42/c41s/c42s/apt/fair/weekly(1)。
covO は 0.5→1.0→5.0→**10**、c1 は 4→5→15→30→**50**、c3mn は 12→15→30→**90**、high は 45→25で不変＝
いずれも HF77 明示指示。この行が stale だと監査が誤誘導されるので、重みを変えたら `MirrorKeys.weights`・
`Evaluator.fullEvalParts`・`DeltaEvaluator` の集約式・destroy-repair/polish系4ファイル・`magi_native.cpp` の
5箇所・言語跨ぎ期待値3ファイル・`docs/business-logic.md` と**同じコミットで**揃える）

## 実行前に確認を取る操作／変えない決定
- **HF77**: パラメータ・重み・データ値の変更は**業務担当者の明示数値指示＋1 件ずつ**のみ。コメントの主張と実装を grep で照合する。
  「賢く統一/改善する」等の明示指示は、目的関数統一における重み変更の承認とみなす。
- **決定記録（再提案しない。明示の数値指示・go があった場合のみ）**:
  D3 apt/weekly/fair の重みは各 1（2026-08-02 再確認、**3.522.0 で apt=4・weekly=2・fair=2 へ改定＝全面見直しのHF77明示指示。
  再確認しない＝この行が最新**）／ D4 360dp 帯は対象外、ただし OPPO A5 5G は 3.497.0 で対象（幅 390dp 未満だけ名前列 56dp）／
  D5 年度末モード（年間積算 5 項目）は実装不要／ D6 標準値 vs 月別例外の差分表示は実装不要（例外は needDay のみ数える）／
  D7 読取（結果）モードは不要＝勤務表タブは直接編集の 1 本／ D8 外観は UD 固定／ E5 月全体の俯瞰は明示 go まで保留。
  D9 個人の下限/上限がある (職員,シフト) には群目標（apt）を適用しない＝個人設定だけを適用（2026-09-07 ユーザー再指示、3.509.0）。
  ws8/ws9 新規シート・Cells()→CodeName 移行・staff タイプ自動判別・LimMax 自動設定は実装不要。cons3n のデータ重複はエンジンで dedup しない。
- VBA 配布物（.bas）は SJIS(CP932)+CRLF のみ。Unicode は ASCII 代替、コメント矢印は `->`。
- 被覆は同日のみ。MIN/OR は意図的設計。covU 構造的不足 -2（供給 153 vs 需要 155）は確定事項。
- main へのマージは本人の「mainにマージする」で（squash、`expectedHeadSha` は `git rev-parse` の値）。force-push は自分の作業ブランチだけ。

## 必要時に読むもの
- `docs/architecture.md`（ファイルと役割、レイヤ）／ `docs/backlog.md`（開いている課題: 未レビュー領域の精読 #2、E5 #5、C++ パリティの残課題 #6）／
  `docs/history/topics.md`（ネイティブ加速・停滞脱出・目的関数統一・ドッグフーディングの経緯）／ `docs/environment.md`（環境手順）／
  `tools/loop/README.md`（自律改善ループのベンチ）。
