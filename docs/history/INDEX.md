# 作業記録の索引（見出し一覧）

- 実機「起動直後にクラッシュ」への起動診断（3.550.0）。`MagiStartupGuard`＝起動段階マーカー・未捕捉例外記録・次回起動時の
  診断画面（OpenGL 互換切替付き）、PCK を filesDir へ複製し絶対パスで渡す、CI で `.so` の 16KiB 整列検査 → `docs/history/3.4xx.md`
- Compose UI を削除し Godot 版に一本化（3.549.0、ユーザー決定）。`MainActivity`・`ui/` Composable 17 本・会話バブルを削除、
  `MagiGodotActivity` を唯一の LAUNCHER に、`magiGodot` フラグ撤去、CI は Godot UI Check が主 → `docs/history/3.4xx.md`
- シフト種別の色のみ外部へ出力・取込む機能を追加（3.547.0）。`ShiftColorsCsvIO`（記号,色）、
  取込はupsert（載っている記号だけ更新、他は現状維持）、違反色は対象外 → `docs/history/3.4xx.md`
- シフト色パレットへ「背景色系」家族を新設、公休を移動（3.546.0、ユーザー指示「公休は背景色系にする」）。
  中立グレー6色を新規生成（`tools/palette_bg_family_cud.py`）、特別枠は残り5区分に → `docs/history/3.4xx.md`
- 特別枠の1文字ラベルを撤回（3.545.0、ユーザー指示「1文字ラベル不要」）。`lastRowCellLabels`・
  `SPECIAL_FAMILY_LABELS` を削除、他家族と同じ表示に統一 → `docs/history/3.4xx.md`
- シフト色パレットをユーザー手指定36色へ差し替え、違反色パレットを分離（3.544.0）。早番=黄/日勤=青/
  時短パート=緑/遅番=紫/夜勤=インディゴの5段階×6家族＋事由別「特別枠」6区分（公休/有休/研修/出張/
  特別休暇/欠勤）。P型/D型シミュレーション後のΔEを狭い範囲で微調整（`tools/palette_shift_families_cud.py`）。
  違反色は別パレット30色（`tools/palette_violation_cud.py`）。design_lint.py P12 は2パレット独立判定へ
  → `docs/history/3.4xx.md`
- 全画面・全ポップアップの CUD（色覚多様性）見直し（3.543.0）。ColorPickerDialog の36色パレットを
  P型/D型二色覚シミュレーション後のΔEで7家族(背景色系/早番/日勤/時短パート/遅番/夜勤/違反アクセント)×6色=42色へ
  再設計（`tools/palette_cud_redesign.py`・`tools/cud_colors.py`）、日ヘッダ下線を実線/破線化、マトリクス
  フッターに▲▼追加、design_lint.py に P12（パレットCUD距離）を新設 → `docs/history/3.4xx.md`
- 新制約「希望の前日に禁止」cons3w/c3w（3.542.0、ユーザーと対話で設計）。希望(wishLocked)で固定した X の前日セルが Y なら
  違反、HARD 9000＝c3n 同格（HF77 明示指示）。`C3wRow(wishKigou, prevKigou)`・静的表 `Problem.c3wBan` を3者＋C++＋
  `makesForbiddenRun` が共有。希望どうしの連日は違反として数え診断 1b が案内。編集タブ⑤に族追加、CSV 種別「希望前日禁止」。
  759 テスト緑・C++ parity 0 mismatch・言語跨ぎ 5×20 族 MATCH → `docs/history/3.4xx.md`
- 「そのデータで B4 に移行できるアルゴリズムと重み」の検証（3.541.1）。厳密解で B4 の値段を測定: 4 セル +94（古泉の
  c1 +2 窓）、16 セル +21（休のリレー 4 段、c3m +3）、10/28 休ゼロは +724。単一重みの採用境界は covO≥32（HF77 の high>covO
  を破る）か c3m≤3、境界でも利得 −1〜0。古泉の窓ルール免除は covO を減らさない。CountChainPolish に covO 起点を追加
  （現行・変種重みとも B4 連鎖には届かず）→ 結論: レバーは業務データ（休 9〜10）か covO 受容 → `docs/history/3.4xx.md`
- fair 達成率モード v2（3.541.0、ユーザー指示・案E）。範囲外の個人（古泉 休 0〜1 で 6 回）や担当不可の 0 回が群の基準を
  歪め設定どおりの人を罰していた 3.538.0 初版を改定: 回数を帯へクランプ・担当不可(上限0)で0回は母集団から除外・基準達成率は
  幅を重みにした中央値。実データ fair 136→39（旧式 181）。Kotlin/C++ 同式、5 フィクスチャ更新、パリティ MATCH → `docs/history/3.4xx.md`
- 回数連鎖研磨 CountChainPolish（3.540.0、既定OFF・測定中）。ユーザー指示「A4/Cｱ の超過を研磨できるアルゴリズム」。
  作る前に CP-SAT で到達値を測定（105セルで 3954→3806、A4 の目標超過は構造的に残る）。同日 k=2/3 巡回交換を
  複数日で束ね、回数族＋連続系の負債 ≤0 の連鎖だけをチェッカーで keep-best。実データ 3954→3931（high −1）、
  golden −67。既存パス大予算は 0〜−6。トグル/params/LoopBench 配線、テスト 2 件。tools/loop A/B（138 ペア）は新2/同等135/旧1・
  HARD 退行 0＝4 ゲート不合格で既定 OFF 維持（golden で −1.7%、差が出たのは 3 ペアのみ） → `docs/history/3.4xx.md`
- 「休の過剰がB4に移行しない」の切り分け（3.539.0、測定記録）。fair側の許容（全探索6%許容・構造下限差引・
  mayPlace除外・無設定シフト除外）は直交＝fairを評価0にしても10/28の休5人は動かない（最適化器60s）。入力盤面の
  ≤3セル連鎖25本はfair免除でも採用0、low免除でも0、fair+low+c1免除で18＝壁はlow(120)/c1(50)/c3m(10)。
  3.521.0パターン3の再来、是正は業務データ（休の範囲/10/28-29の休=0）。周辺: 達成率モードが意図的な範囲外個人に
  引きずられ他メンバーを罰する（古泉rate 6.0→荒井偏差26、設定は意図した個別設定＝ユーザー確認）、実行#3の予算超過511s/300s、MUS診断「証明つき」がHARD=0盤面と矛盾。追記: 休以外の
  超過(high/aptHigh 25セル)もB4へ移せない（基幹は全日lo=hi=1→空いた枠を休の人が埋めてlow +120、連鎖2052本で採用0） → `docs/history/3.4xx.md`
- fair(公平化)の平均計算を達成率モード化（3.538.0）。ユーザー指示「平均はOlympic average類似の、
  目標値・個人上下限などの設定を配慮した達成率スコアで計算する」をgrilling6問で確定。範囲/apt目標が
  群全員に揃う場合だけ達成率（回数の位置/目標比）で比較、揃わなければ従来のround(平均)へフォールバック。
  `Problem.fairDevOfBucket`を共通ソース化しEvaluator/Delta/Checker/DestroyRepairMarginalCostの4箇所を
  統一（1箇所の改修漏れをテストで検出）。C++も同式へ移植、sept2026を達成率モード検証用の5件目フィクスチャ
  として追加 → `docs/history/3.4xx.md`
- aptFairSoftToleranceのtools/loop正式A/B結果（3.537.0）。46ケース×3seed=138ペアで辞書式
  旧68/新38/同等32＝ONが劣勢、速度-62%・タイムアウト12→32、下位10%品質も旧未満＝4ゲート全て
  不合格。c40-large-c2deficit1でON側のみ例外を観測したが単体再実行6/6で非再現（未解決・環境依存
  の疑い）。HF77の恒久機能指定のため撤去せず既定OFFを維持 → `docs/history/3.4xx.md`
- aptFairSoftToleranceの「設定の効き」テレメトリ欠落を修正（3.536.0）。実機ログでON時も痕跡が
  出ないと発覚（`TuningTelemetry.summary`への追加漏れ）。素のbetterReportなら却下される手を
  容認で採用に転じさせた回数だけを数えるカウンタを追加、他トグルと同型の1行に統合。エンジンの
  採否・重みは不変（読み取り専用） → `docs/history/3.4xx.md`
- 公平化/適切回数研磨に他ソフト6%許容オプションを追加（3.535.0）。ユーザー指示でkeep-best原則
  （採用基準を増やさない）の唯一の明示的例外として、`AptFairPolish.toleratedBetter`の累積予算方式
  （fair/apt改善のためHARD不増加を維持したまま対象家族以外のSOFT悪化を研磨開始時点比+6%まで容認）を
  既定OFFのUIトグルで追加。grillingで範囲を確定、tools/loop A/Bも実施 → `docs/history/3.4xx.md`
- 設定ミス診断6f: 必須/推奨(c3/c3m)と禁止(c3n)の連続数矛盾を検出（3.534.0）。ユーザー提示案の具体例は
  検証の結果誤り（L=3,N=4は矛盾しない）と判明、正しい条件（L≥N）で実装。実現可能性表示・自動修正は
  新規DP計算/HF77の安全基準に照らし見送り、検知のみ既存のSettingIssue枠組みへ追加（UI変更不要）
  → `docs/history/3.4xx.md`
- グループ一括設定: グループ別セクション化と一括解除を追加（3.533.0）。`groupRangeSummary()`の既存
  ソート順を利用しgroupByでチップをグループ別に整理、重複するグループ名接頭辞を除去、
  `clearGroupRangeSection(g)`新設。編集モード化・インライン追加UI・配色全面見直し・下部ナビ記号化は
  既存の統一パターン維持のため不採用 → `docs/history/3.4xx.md`
- 設定タブを静音化: 「直す優先順位」表(WeightTableCard)を既定で折りたたむ（3.532.0）。7節中
  唯一この表だけ既存の折りたたみ対策(ColorSettingsView、3.483.0)から漏れていたと判明し
  CollapsibleSectionで統一。他6節は対象外（根拠なき全面リフォーム回避） → `docs/history/3.4xx.md`
- 回数マトリクスに行単位の警告マーカーを追加（3.531.0）。ユーザー提示デザイン案の大半（薄色/濃色・
  =N表記・罫線排除）は実装済みと確認、行頭⚠マーカーだけ新規実装。下部ナビ記号化は見送り
  → `docs/history/3.4xx.md`
- 職員一覧をシフト種別/グループと同じ並び替え・削除の形へ統一（3.530.0）。▲▼ボタン→ドラッグハンドル
  （`ReorderableRows`を`internal`化して再利用）、独立した編集/削除ボタン→行タップ+編集ダイアログ内の
  削除（`StaffDialog.onDelete`新設）。ユーザー提示デザイン案をgrillingで既存の3.515.6パターンへ統一する
  形にスコープ確定 → `docs/history/3.4xx.md`
- 外部仕様書「決定論的修復統合基盤」を評価し3件だけ取り入れ（3.529.0）。SHA-256+TLV正規化・因果証拠
  構造体・新規safety/パッケージ・全操作への汎用Preview化は既存の軽量な仕組み(checkSeq/fixBoardKey/
  FixApplyGate/SaveGate/checkRev)と重複するため不採用。採用: ①applyAlternative()の鮮度チェック追加
  ②保存状態(SaveState)をUIへ表示 ③Undo履歴に操作名を表示 → `docs/history/3.4xx.md`
- 設定タブの既定OFFトグルはUIに残し、AB評価で既定ONへ確定した機構(blockSwapC3nFilter/lnsAdaptive)は
  UI表示から外す（3.528.0）。opt-outフラグ自体は温存。ユーザー指示「AB評価で確定した既定Onのフラグは
  UIに表示しない」→ `docs/history/3.4xx.md`
- 日付ヘッダーに人員過剰バッジ「▲N」を新設（3.527.0）。既存「▼N」（人員不足）と対称。
  `V6PortAnalyzer.V6DayRisk.surplus`新設・`DayHeader`（MagiScheduleViews.kt）で同じ行に並べて表示
  （ユーザー明示指示、grillingで配置確認）。実データで12日A4 surplus=1を確認
  → `docs/history/3.4xx.md`

- weekly（曜日平準化）の指標式を再定義（3.526.0）。旧`round(平均)`式は合計3回以下だと目標が0に丸まり
  「配置に関わらず偏差が合計回数のまま不変」＝同一曜日集中を検出できない死角があった（実データ調査で
  発覚）。新式`dev=Σ|7×曜日回数-合計|÷7`へ変更（HF77明示指示、重みは不変）。Kotlin/C++両方の
  `weeklyDevOfBucket`のみ変更・増分コスト計算は自動追従。golden/sample_v6/sept2026の期待値を更新
  → `docs/history/3.4xx.md`

- 3.524.0への/code-review指摘3件を修正（3.525.0）。`Evaluator.fullEvalParts`のbreakdown mapを
  `clear()`してC++側の初期化と対称化、`NativeParityFixtureTest`の期待値パーサが`#`コメント行で
  クラッシュしないよう修正、`host_parity_bench.cpp`の族別breakdownが1〜18件だけ揃った壊れたファイルを
  黙って2値照合へ後退させず fail-loud にした
  → `docs/history/3.4xx.md`

- native-parityの言語跨ぎ照合を族単位へ強化・tools/loopにc2deficit/c42pair追加（3.524.0）。backlog#6:
  `Evaluator.fullEvalParts`/C++`fullEvalParts`に任意の`breakdown`出力を追加し、19族(MirrorKeys.all)
  すべてを比較する`full_coverage_state.json`フィクスチャを新設（golden/sample_v6/blocked_covuは3件
  そろってapt=c41=c41s=c42s=0で死角だった）。backlog#12(b): `tools/loop`のケース生成にcons2不足2件
  以上・cons42違反ありの2分類を追加し、C2Polish/C42FlowPolishのiter15/18「検証不能」を解消（再測定は
  両パスとも旧腕と同点、既定OFF維持）
  → `docs/history/3.4xx.md`

- 3.522.0の3件の既知failureを解決（3.523.0）。PinInvariantTestの真因は`厳密ピン(staffRange lo==hi)`
  ではなく`希望固定(wishLocked)`セルの値保持違反＝`C1WindowPolish.applyC1IndexChainRepair`の候補日
  フィルタに他の全パスが持つ`!p.wishLocked(staff,d)`ガードが欠けていた（重み非依存の旧バグだが
  重み表全面見直しで探索経路が変わり顕在化）。修正して候補から除外。CombinatorialRepairTest/
  ViolationComponentRepairTestはcons41を4重複させ「単独不採用・結合で採用」の性質を復元
  → `docs/history/3.4xx.md`
- 重み表の全面見直し（3.522.0、HF77明示指示）。tools/loop 34ケース×10seedのbaseline対比ベンチマークで決定:
  groupViol/covU/c3n/pref(HARD)を10000/8000/7000/9000→11000/10000/9000/8000へ再配分、
  low/c1/c3/c3m/c3mn/c2/c41s/c42s を90/30/3/2/30/1/1/1→120/50/15/10/90/4/6/6、apt/fair/weeklyを1→4/2/2、
  covOを5→10へ（SOFT中「上限超過(high)>人員過剰(covO)」をoutcomeレベルでも保つため。high違反+42%→+3.4%まで
  解消・covUは3配分中最良を維持）。B1のaptHigh違反群(古泉/山本/佐藤)がapt/fair同重みの綱引きで解消不能
  だった実機ログを受けapt=4に改定（D3再改定）。副産物として`DestroyRepairMarginalCost.fairMarginalAt`が
  fair重みを内部適用していなかった潜在ギャップ(旧重み1では無害)を発見・修正。`MirrorKeys.weights`・
  `Evaluator.fullEvalParts`・`DeltaEvaluator`・destroy-repair/polish系4ファイル・`magi_native.cpp`5箇所
  → `docs/history/3.4xx.md`
- 設定ミス診断に検査6e追加: 希望件数>個人上限（grilling確定）。実機ログで「古泉 健一の有給（上限0・
  希望10件）」「荒井克枝のCｵ（上限0・希望10件）」がhigh違反として最適化後も残り続ける事象を調査し、
  希望固定(wishLocked)セルは最適化器のどのパスも動かさないため上限超過は解消不能と判明（不具合ではなく
  設定ミス）。6d（apt版）と同型の判定を`staffRange.hi`に追加、`V6SanityPort.forcedCountIssues`（3.521.0）
  → `docs/history/3.4xx.md`
- UX改善: UI用語統一・ゲーム要素廃止（ユーザー指示、STEP①現状把握→②改善検討→③実施検証）。
  フェーズ名バッジ「狩猟」→「未完成」、感嘆符/煽り表現を平易な文へ、「できあがり度」の残存2箇所を
  「解消度」（3.480.0で改称済みの正式語）へ統一。「最適化」vs「計算」の混在を「最適化を実行したか」の
  概念に絞って統一（別概念の「計算」＝違反数の再評価等は残す）。Kotlin/C#両実装＋
  `docs/operator_ux.md`等の用語集docsを同時更新（docsは実装より古い旧語「できあがり度」「守れていない約束」
  「できれば直したい点」を含んでいたため実装に合わせて修正）。史料（changelog/history/archive）は不変
  （3.520.0）  → `docs/history/3.4xx.md`
- 未計測だった2件のAB結果: combineExhaustPairsはiter24（170ペア）でlarge/infeasibleの1ペアに必須件数増を
  確認し既定OFFで確定（再提案しない）。personSwapKick(3.517.0)は新規ハーネスPersonSwapBench.ktで実データ
  4件×5seed×フルoptimize(PORTFOLIO)を実施し全20ペアで必須退行ゼロ・4フィクスチャ全てで負け越しなしを
  確認、既定trueへ昇格。ユーザー向け説明文の「結果は悪化しない」という不正確な記述も訂正（3.519.0）
  → `docs/history/3.4xx.md`
- 既定OFFの全トグルを棚卸し・4分類（既存測定で判断可／既に決着済み＝再測定しない／未計測／新機能）し、
  `filterC3nIncrease`（ON/OFFで最終盤面完全一致・速度のみ）と`lnsAdaptive`（iter9で品質±0・速度実データ-23%、
  既存結論「推奨ON」）を既定trueへ昇格。`PolishGate`とUiStateの既定乖離（表示と実態の不一致）を発見・修正。
  C#（-magi_pc）もFilterC3nIncreaseを同時昇格・dotnet test green（3.518.0）  → `docs/history/3.4xx.md`
- PORTFOLIO の新役割 PERSON_SWAP_ILS（全月入替→再最適化のILS摂動、grilling4問）を既定OFFで実装。
  同群2名の1ヶ月分割当を丸ごと交換→RSI+でフル再最適化。fair は交換不変（分析的に証明済み）で、
  改善は c1/c3/high 等の局所解構造が変わることで生じる（実データprobeで weightedScore 9831→9605
  を確認済み）。ペア選定はfair負担の大きい職員優先のヒューリスティック。`PolishGate.personSwapKick`
  既定false＝OFF時は既存6役割ローテーションがビット単位で不変。`tools/loop`の正式A/Bは未着手
  （3.517.0）  → `docs/history/3.4xx.md`
- HF77明示指示で上限超過(high)の重みを45→25に変更（設定タブの重み表で「上限超過を人員過剰と期間の制約の
  間に移動する」→数値指示25）。Evaluator/DeltaEvaluator/destroy-repair系polish(4ファイル)/`magi_native.cpp`
  （評価器+SaChunk+コメント）・言語跨ぎ期待値(Kotlin/C#)・テスト内重複リテラル(逆順パターン含む)を同期。
  `severityFromVioKey`/`heavySoftFamilies`のHIGH→WARN降格、`C1WindowPolish`関連テストの前提再設計を伴う
  （3.516.0）  → `docs/history/3.4xx.md`
- 編集タブ「シフト種別」「グループ」を行デザイン刷新＋ドラッグ&ドロップ並び替えへ（ユーザー提示モックアップ・
  grilling5問）: ▲/▼(3.515.3)から長押しドラッグへ（片手一本指の既定をこの2箇所限定で明示的に上書き）、削除は
  行から編集シートの中へ、グループの並び替えは新規実装（Ws1Ops.moveGroup）。TalkBackでは操作不可な既知の制約を開示（3.515.6）  → `docs/history/3.4xx.md`
- 設定・勤務表タブの文字サイズを意味階層に揃える（backlog #17、3.515.4の続き）: MagiSetupCards/MagiDashboardCards/
  MagiScheduleViewsのlabelSmall・label+太字見出しを章=titleMedium／節=titleSmall／本文・行=bodyMedium／補足=bodySmall
  へ統一。凡例・バッジ相当（ViolationLegend/TallyLegend/セル内注記）はlabel*のまま据え置き（3.515.5）  → `docs/history/3.4xx.md`
- 編集タブの文字サイズを意味階層に揃える: 節見出しがlabel+太字・説明も行もlabelSmallで全部14sp＝ボタンより見出しが
  小さい逆転を、章=titleMedium／節=titleSmall／本文・行=bodyMedium／補足=bodySmall／label=部品ラベル・チップのみに
  統一（DESIGN.md §3.3に規則を明文化。設定・勤務表タブは backlog）（3.515.4）  → `docs/history/3.4xx.md`
- 職員・シフト種別の並び替え（▲/▼）を追加: Ws1Ops.moveStaff/moveShift（removeStaff/removeShiftと同じ index 付け替え、
  記号参照は不変、端は no-op）＋Affordance.MoveRowButtons（片手一本指＝ドラッグなし）。Ws1OpsMoveTest 4件。
  C#同期は別途（3.515.3）  → `docs/history/3.4xx.md`
- 実機報告6件を修正: (1)群の目標ステッパーの+/-がセルタップシートを閉じる（key(ui.editRev)配下のremember破棄）、
  (2)曜日ヘッダの下線がneedViolations(シフト×日)のみの違反日に付かない、(3)covOセル詳細で希望固定してない
  在勤者が表示・操作できない、(4)違反ナビでジャンプしたセルをタップしても理由が出ないケースがある
  （(2)(4)はneedViolations未合算という同根）、(5)色ピッカーが選ぶと閉じる（4呼出元のonPickが明示的に閉じていた）、
  (6)分析タブの設定警告（cons3n重複「Dﾃ→A4」）が18文字で切れて勤務表の違反に見える（1件のときは場所＋理由を全文表示）
  （3.515.2）  → `docs/history/3.4xx.md`
- 3.515.0の外部レビュー指摘2件を修正: (1)FixSuggesterのfocusShiftは日を指定できず、別日の改善がこのセルの解消として誤カウントされ得た＝返った手が実際にday j自体を動かすか確認するよう修正、(2)covO深追いがpushReport本体（編集ロック解除・経過時間表示）を最大8秒巻き込んでいた＝deepCovODiagFollowUpとして別ジョブ化しpushReportを待たせない設計に修正（3.515.1）  → `docs/history/3.4xx.md`
- covO「なぜ減らないか」診断（V6PortAnalyzer.diagnoseSurpluses）を複数職員・別日の組合せまで探すよう拡張（ユーザー指示「複数の職員および過去未来の日も押し出し可能できるように」、既存のFixSuggester「直し方を探す」を再利用、実データでは今回の5件とも解なし＝low/high重み差による構造的トレードオフと確定。副次的にViolationComponentRepair/V6FinalPortの無駄なsurplus計算をincludeSurplus=falseで除去、deepSurplusは最適化完了直後のみtrueにしライブ編集診断への影響を回避）（3.515.0）  → `docs/history/3.4xx.md`
- combineExhaustPairs・lnsAdaptiveをUIの詳細設定からON/OFFできるように（ユーザー指示「フラグのOn,Offはユーザーが選択できるように」、不合格判定で凍結中のdebtLaneSlots等は対象外）＋シフト集計カードの既定開閉を展開へ戻す（3.483.0 S-4の反転、ユーザー指示「シフト集計は開く。閉じない」）（3.514.0）  → `docs/history/3.4xx.md`
- 最終番兵の復帰盤面バグを修正（handleOptimizeで番兵発火時にfinalSchedをcappedInputでなくnormInputへ戻していたため、finalReportは「上限0のセル除外済み」の集計を返すのに実際に返す盤面には元の上限0割当が残る食い違いが起き得た。実機報告「大島愛のDﾃが上限0なのに1件割当」から発見。sentinelScheduleへ抽出しユニットテスト、C#同日同期）（3.513.0）  → `docs/history/3.4xx.md`
- CombinatorialRepair.combineAndApplyのmaxStagnantTries固定200回を実機報告から緩和（プール56件でもC(56,2)=1540通りある2人組交換のうち13%しか試さず打ち切っていた。exhaustPairs/pairCap追加、既定OFF・opt-in、6箇所の研磨パスへ配線、未計測）（3.512.6）  → `docs/history/3.4xx.md`
- LiveScheduleCard「状態遷移」の変化件数が実機で常に0固定だった実機バグを修正（remember(cur)の計算ラムダ内でprevHolder[0]=curを書いていたため、Composeの「破棄コンポジションでも複数回呼ばれうる」契約に反し副作用が破棄回で先に効いてしまっていた。書き込みをSideEffectへ分離）（3.512.5）  → `docs/history/3.4xx.md`
- iter22/23 結果（分離条件）: debtLaneSlots（新良8/同等135/旧良27、品質-0.18%）・bestOfK（新良15/同等138/旧良17、品質-0.17%、必須増12件はlarge-infeasibleに集中）はいずれも不合格。ユーザー判断で検証・追加実装を中止、既定OFFのまま凍結（WeightDebt/ConstraintRepairInference/familyPriorityScoreに続く同系統4度目・5度目の不合格）（3.512.4）  → `docs/history/3.4xx.md`
- debtLaneSlots/bestOfK: trimFrontierのビーム縮小バグ修正（負債候補0件でも非負債候補の枠がbeamWidth-debtLaneSlotsに固定され純粋な退行を起こしうる構造をユーザーの机上テストで発見）＋LoopBenchのdebtlane/bestofkフラグをレバー単体で分離測定できる条件へ修正。旧iter22/23は交絡した参考値のまま保存し採否判定には不使用。host JVM 全721テストgreen（3.512.3）  → `docs/history/3.4xx.md`
- iter21 結果: quantitativeRangeEval（C2/C41/C41sの量的評価、backlog #12(a)）は170ペアで新良5/同等153/旧良12、品質±0.00%・速度-1.1%とも不合格、必須件数が増えた試行6件はlarge-infeasibleカテゴリのみ（他15分類は完全無風）＝ゲート不合格、既定OFF維持（3.512.2 追記）  → `docs/history/3.4xx.md`
- V6FinalPort.handleOptimizeのトレーリングラムダ誤束縛を修正（backlog #12(a)でonProgressの後にquantitativeRangeEvalを追加したため本番呼出2箇所がAndroidビルドだけ壊れていた、EliteRelinkingで一度踏んだのと同じ罠の再発、host JVMでは検出不能=v6-engine-checkで発覚）。回帰テスト追加、host JVM 718テストgreen（3.512.1, backlog #12(a)）  → `docs/history/3.4xx.md`
- 違反連結成分修復の二車線ビーム debtLaneSlots と族優先度の順序非依存化 bestOfK（ユーザー提示「適応型制約違反研磨エンジン 完全統合実装仕様」v2.1の核だけを既存コードの構造的欠陥として取り出した実装、重い機構=結合度負債テンソル・Restless Bandit・並列トランザクションは§20の解除条件未達のため見送り）。既定OFF・未計測、host JVM 717テストgreen（3.512.0, backlog #12(b)/#15）  → `docs/history/3.4xx.md`
- iter20 結果: C1RepairAnalysis.solveComponentは170ペア全件で新旧同等（合成ケースに複数cons1窓の近接局面が無く検証不能、c42flow/iter18と同型）＝ゲート不合格、既定OFF維持（3.511.9 追記）  → `docs/history/3.4xx.md`
- C1重複窓の連結成分化 C1RepairAnalysis.solveComponent（同一職員の複数cons1窓を区間重なりでグラフ化し連結成分へ分割、休/夜2ルール重複でsolveWindowが後の窓を解けなくなる局面を解消）。既定OFFのhash 4/4不変、iter20で不合格・既定OFF維持（3.511.9, backlog #12(b)）  → `docs/history/3.4xx.md`
- iter19 結果: C3nMarginLnsPolishは170ペアで新良1／同等168／旧良1＝品質±0.00%とほぼ無風（合成ケースに専用局面の仕込みが薄い）＝ゲート不合格、既定OFF維持（3.511.8 追記）  → `docs/history/3.4xx.md`
- C3n（禁止連続）の前後余白込みLNS C3nMarginLnsPolish（違反パターン日+前後marginDaysを複数セル同時destroy-rebuild、1セル付け替えが届かない局面を拾う）。既定OFFのhash 4/4不変、iter19で不合格・既定OFF維持（3.511.8, backlog #12(b)）  → `docs/history/3.4xx.md`
- iter18 結果: C42FlowPolishは170ペア全件で新旧同等（合成ケースにc42違反の仕込みが無く検証不能、C2Polish/iter15と同型）＝ゲート不合格、既定OFF維持（3.511.7 追記）  → `docs/history/3.4xx.md`
- c42/c42s（群ペア禁止）専用 min-cost-flow研磨 C42FlowPolish（c42PairCountは真に凸のためc41のような誘導コスト置換は不要、片側固定ヤコビ近似＋対称2試行）。既定OFFのhash 4/4不変、iter18で不合格・既定OFF維持（3.511.7, backlog #12(b)）  → `docs/history/3.4xx.md`
- iter17 結果: familyPriorityScore は170ペアで新良10／同等143／旧良17＝品質−0.26%、必須が増えた試行9件・個別10%超退行2件＝ゲート不合格、既定OFF維持（3.511.6 追記）  → `docs/history/3.4xx.md`
- 族選択を件数×重み×改善可能性へ familyPriorityScore（ViolationComponentRepair.anchors、セグメント内のみソート、HARD優先の境界は死守）。既定OFFのhash 4/4不変、iter17で不合格・既定OFF維持（3.511.6, backlog #12(b)）  → `docs/history/3.4xx.md`
- iter16 結果: C41FlowPolish は170ペアで新良21／同等143／旧良6＝品質+0.14%・速度+1.1%とも10%未達、必須が増えた試行2件＝ゲート不合格、既定OFF維持（3.511.5 追記）  → `docs/history/3.4xx.md`
- C41/C41s専用 min-cost-flow研磨 C41FlowPolish: FlexibleDayFlowを群内サブセットへ再利用。二値指標の生差分は非凸でMCMFが誤って先取りする発見（RangePolish型の凸誘導コストへ置換して解消）。既定OFFのhash 4/4不変、iter16で不合格・既定OFF維持（3.511.5, backlog #12(b)）  → `docs/history/3.4xx.md`
- 予算超過の回帰試験 BudgetOverrunTest（自然満了3秒枠・外部停止5秒以内・例外ワーカー2秒以内）を追加。RSI_PLUS のフェーズ予算按分に35秒下限を発見（極小予算のみ、production の300秒規模では無傷）＝記録のみ、探索コードは無変更（3.511.4, backlog #14(g)）  → `docs/history/3.4xx.md`
- iter15 結果: C2Polish は170ペア全件で完全に無変化（合成ケースのcons2は不足最大1件でバッチ化の優位性を試せていない、実データでも自己変換だけでは届かない残りのみ）＝ゲート不合格、既定OFF維持。教訓: 無変化=検証不能、deficit≥2のケースが必要（3.511.3 追記）  → `docs/history/3.4xx.md`
- iter14 結果: 循環交換のk=4,5拡張は170ペアで新良1／同等169／旧良0＝必須退行0だが品質・速度10%未達でゲート不合格、既定OFF維持（3.511.2 追記）  → `docs/history/3.4xx.md`
- C2（個人合計）専用研磨パス C2Polish: 不足日をまとめて一括適用・1回だけ判定（c2は二値フラグのため1件ずつでは同点却下される）。T=5の小盤面ではweeklyの悪化で相殺される教訓、T=31で解消。既定OFFのhash 4/4不変、iter15で採否（3.511.3, backlog #12(b)）  → `docs/history/3.4xx.md`\n- iter12 結果: 長期ブロック交換の動的長 dynamicBlockLengths は170ペアで新良0／同等169／旧良1（大・充足不能ケースで必須+1）＝ゲート不合格、既定OFF維持。単一パスのkeep-bestはチェーン全体のkeep-bestを保証しない経路依存の一例（3.511.0 追記）  → `docs/history/3.4xx.md`
- 循環交換の k=4,5 拡張（既存 k=2,3 全列挙はそのまま、4/5人はランダム試行で追加。全列挙は組合せ爆発するため避けた）。4職員の環で2/3職員では届かない改善を固定するテスト追加。既定OFFのhash 4/4不変、iter14で採否（3.511.2, backlog #12(b)/#13(e)）  → `docs/history/3.4xx.md`
- 停滞時の探索幅拡大 StallEscalationConfig（巡回研磨クラスタが1巡も採用0のときだけ共同LNS/成分修復の幅を2倍で1回だけ再試行）。既定OFFのhash 4/4不変、iter13で採否（3.511.1, backlog #12(b)/#13(a)）  → `docs/history/3.4xx.md`
- 長期ブロック交換の候補長を動的化 dynamicBlockLengths（違反窓長・禁止連長・希望島半径・当月日数から導出、固定28が2月以外で当月まるごとに届かない穴を修正）。既定OFFのhash 4/4不変、iter12で採否（3.511.0, backlog #14(c)）  → `docs/history/3.4xx.md`
- iter11 結果: 成分修復の一時負債予算 ConstraintRepairInference は 170 ペアで新良 8／同等 150／旧良 12、実データ 20 ペア全件同一、充足不能ケースで必須 ±（増 6・減 4）＝ゲート不合格、既定 OFF 維持（3.510.5 追記）  → `docs/history/3.4xx.md`
- iter10 結果: 共同 LNS の WeightDebt は 170 ペアで新良 0／同等 155／旧良 15、必須 8→10 が 1 件、品質 −0.16%＝ゲート不合格、既定 OFF 維持（3.510.4 追記）  → `docs/history/3.4xx.md`
- 違反連結成分修復の一時負債予算 ConstraintRepairInference（起点の族の 重み×現在量×2.0 を SOFT 負債の上限、HARD 悪化の枝は外す）を opt-in 実装。前提訂正: 既存ビームは悪化ノードも展開していた。4 実データ最終盤面 4/4 同一・推定 40〜70% 減＝iter11 で採否（3.510.5, ユーザー仕様 v3）  → `docs/history/3.4xx.md`
- 共同 LNS の一時負債を重みで絞る WeightDebt（負債 ≤ クレジット×2.0、HARD は件数予算のまま）を opt-in 実装。4 実データ: 2 件同一盤面で 26〜30% 速く、sample_v6 重み −480、sept2026 +5＝iter10 で採否（3.510.4, backlog #15(f)）  → `docs/history/3.4xx.md`
- 机上評価「動的重み対応・制約負債型複合修復」（設計 v2）: 最終採用は既に重みのみ、追従しないのは中間の件数 debt 3 箇所・固定予算・3 言語のリテラル。凸型負債は採用 31 件中 1 件却下、由来倍率は不要（D9 で 90 倍済み）、予算配分は下限が要る（3.510.3 補遺）  → `docs/history/3.4xx.md`
- 族別 Credit/Debt の可視化: ChangeSummary.familyDeltas/familyLine（改善した族・悪化した族と重み付き合計）を完了カード 2 行目と「後処理 収支」ログへ。採否判定は不変、後処理ハッシュ 4/4 一致（3.510.3, backlog #15(a)）  → `docs/history/3.4xx.md`
- 机上評価「制約クレジット台帳付き複合修復」（ユーザー提示設計）: Credit≥Debt は betterReport と同式、一時負債・候補盤面分離・共通ゲートは既存（成分修復/共同 LNS/adoptionGate）。件数上限＋Total 厳密改善は 4 実データの採用 31 件中 12 件（39%）を却下＝入れない推奨、明示個人範囲の保護は影響 0（3.510.2 補遺）  → `docs/history/3.4xx.md`
- 共同 LNS の短い試行→採用時だけ本予算（lnsAdaptive、既定 OFF）を実装し iter9 で測定: 退行 0・品質 ±0・速度 平均 +10%（p90 −20%、実データ −23%、メモリ 0.64 倍）だが中央値 −1.5%＝規則上は不合格、既定 ON はユーザー判断（推奨 ON）（3.510.2）  → `docs/history/3.4xx.md`
- iter8 結果: C3 選択日ペア交換は 170 ペアで新良 11（全部実データ）／同等 158／旧良 1、品質 +0.04%・速度 ±0＝ゲート不合格、既定 OFF 維持（3.510.1）  → `docs/history/3.4xx.md`
- C3「選択日ペア交換」C3PairMaskPolish を最終段の低予算パスとして実装（違反日を含む非連続 1〜3 日の同日交換、c3n 枝刈りで正式評価 1/5、既定 OFF）。4 実データで golden 採用 2（重み −0.7%）。採否は tools/loop iter8 で（3.510.0）  → `docs/history/3.4xx.md`
- 研磨パス共通の採用ゲート adoptionGate（改善判定→改善時だけ厳密ピン判定）へ 18 箇所を統一。挙動不変（後処理 4/4 一致）。C# 同型（3.509.5）  → `docs/history/3.4xx.md`
- 改善提案の適用直前ゲート FixApplyGate（仮盤面で完全再評価、辞書式改善・固定不変のときだけ反映、見送り理由を表示）と完了カードの前後比較 ChangeSummary（変更人数・セル数・希望充足・個人回数）。自動化 UX 評価 7 項目の対応を automation.md §6 に（3.509.4）  → `docs/history/3.4xx.md`
- 自動化方針（ユーザー提示）を docs/automation.md に実装との対応表（済/部分/未）として固定。制約 CSV の個人レンジ行は非負整数・下限≤上限のみ受理（3.509.3）  → `docs/history/3.4xx.md`
- 日割当研磨（厳密/交互）の apt 目標を生の groupShiftApt から Problem.apt（実効目標）へ統一＝D9・到達クランプ・担当可ゲートと整合。「頭打ち＝改善手なし・正常」を「現在の探索範囲では改善手なし」へ。外部レビューの評価定義・探索動学・UX 提案は backlog #12 へ集約（3.509.2）  → `docs/history/3.4xx.md`
- 外部レビュー一覧の仕分け＝Android で既に済んでいた 6 項目を確認し、残りを修正: 負数の個人回数を未設定扱い（D9 に数えない・Sanity 2h で案内）、CSV ヘッダ判定を構造で（先頭未知職員をヘッダ扱いしない）、停止ログの到達不能分岐、「全員に適用」文言。C# は加えて CSV 例の複数表示・クイック解決の入力欄同期・アクセシビリティ名・言語跨ぎ契約テスト（3.509.1）  → `docs/history/3.4xx.md`
- 決定 D9: 個人の下限/上限がある (職員,シフト) には群目標（apt）を適用しない＝個人設定だけを適用（ユーザー再指示）。空欄キーは未設定扱い、別職員・別シフト不干渉、二重計上なし。到達範囲クランプは適用される組にだけ残す。期待値 3 ファイル更新・C++ MATCH・C# 同期（3.509.0）  → `docs/history/3.4xx.md`
- 自律レビュー第11段 UI 層＝エンジン契約との接点。「なおすのを手伝って」の候補職員を canDo→mayPlace（上限 0 の職員を出さない）、他 5 接点は整合。全面精読は実機ビルド待ち（3.508.2）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第10段 V6HotfixPasses 各パス内部＝HF80 の localBestImprovement を「その場で 1 セル書き換え→不採用なら戻す」に（試行ごとの盤面コピーを撤去、Problem/Evaluator は入口で 1 回）、runPolishCluster は cachedProblem。seed 固定プローブ 48/48 一致、HF80 の検査 3 件追加。エンジン層の精読は一巡、残りは UI 層（3.508.1）  → `docs/history/3.4xx.md`
- 適切回数（apt）の群目標を「構造的に到達できる範囲」へクランプ＝桒澤 B4「目標 1／現在 17」の幻の超過の恒久対策（到達下限 T−Σ他シフト実効上限・希望固定込み、個人 [lo,hi] 優先）。評価は実効目標 apt、診断 6b/6d/6-C は設定値 aptRaw。実データで動くのは 9 組のみ、期待値 3 ファイル更新・C++ MATCH・C# 同期（3.508.0）  → `docs/history/3.4xx.md`
- 机上評価: C3「選択日ペア交換」／C1「連結成分・選択日交換」（ユーザー設計案、実装せず）＝机上例はペア交換限定でのみ局所障壁、既存 C3Sequence が c3n 単独違反者を起点にしない隙間を発見。実データ 4 件の最終盤面に残る 2 職員×≤3 日交換の改善手は golden −2.9%（非連続 2〜3 日、c3mn）・他 ≤0.2%。推奨: C3 版のみ最終段の低予算パスとして測る、C1 版は見送り（3.507.9 補遺）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第9段 V6SearchOperators＝2 パス乱択 12 箇所を pickUniform へ、findC41Fix/findC41sFix を findGroupRangeFix に統合、厳密ピン判定を共有、隣接日修正の不変量計算をループ外へ。seed 固定プローブ 72/72 一致、find*Fix の検査 6 件追加（3.507.9）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第8段 CSV 層 ScheduleCsvBridge＝4 実データで parse(build) の完全再現を固定（勤務表/職員/希望/制約、エスケープ文字含む）。数値でない個人レンジは取込で弾かず Sanity 2h に任せる決定を検査で固定。C# の 3.474.0/3.475.0 未同期 5 点を同日同期（3.507.8）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第7段 V6LateOperators＝improve（400 行）を LateSession＋chainSwap3/4・rectSwap2・c1BlockN へ（RNG の呼び出し順を保持）、RectSwap の違反者抽出を baseViolators に統一、isBalanceable をシフト別に記憶。seed 固定・締切遠方で 48/48 一致（3.507.7）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 7 報告＝決定的モードで再現性 34/34（VM 再起動で JVM が 2 回消え、再開可能にした続きも同一盤面）・新良 179/同等 150/旧良 11・品質 +1.81%・必須が減った試行 61。タイムアウト 19 行は c25/c26（予算を無視する設計の名目超え）。ベンチの再開（済み行を飛ばして追記）と grep の行バッファ（3.507.6）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第6段 V6SanityPort＝buildGuidance（689 行）/buildViolationDebug（316 行）を GuidanceBuilder/DebugBuilder のメソッドへ機械分割、表示上限を Guidance に集約。「担当できる人数」を placeableFor（mayPlace＋その日の希望固定）で数える（forcedCovU/構造 HARD 下限/検査 3/c1 作業需要/代用可）＝3.507.0 の取り残し。設定ミス 24/24・違反詳細 20/24 一致（4 件は代用可の意図した減少）（3.507.5）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第5段 V6SwapSuggester＝FixSuggester を Session 化（7 フェーズを関数へ、上限を Limits に集約）、連鎖に mayPlace ガード、再最適化の冗長評価と別日交換の同日重複列挙を撤去。4 実データ × 5 設定で提案一覧 20/20 一致。C# のドリフト 2 件（休 index 0・署名に日なし）を修正（3.507.4）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 7 第一弾＝後処理の決定的モード（`deterministic`: hf67/hf66 の ms キャップ・巡回クラスタの締切・共同 LNS の maxMillis/patience・最終段の残り時間判定を回数上限へ。C1 90,000・個人 60,000 で時間モードと 4/4 同一盤面、2 回とも再現）。実機は既定 OFF。ベンチ iter7 走行中（3.507.3）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 6 報告＝ピン対応生成で新良 163/同等 169/旧良 8・必須が減った試行 64（最大 -38）・品質 +1.84%（大規模の充足不能 +11.3%・中規模 dense +2.2%）・速度 -3.1%。退行ゼロ合格、10% ゲート未達。再現性 32/34 は同時実行の CPU 競合。§4 の 14 機能回帰スイート `LoopFeatureRegressionTest` を新設し機能同等性 14/14 を初めて計測（3.507.2）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 6 第一弾＝起点生成をピン対応に（lo=hi を単独で崩す単セル→行内入替、同日交換→2 日交換）。ピン枝刈り golden 775→256・sample 1421→61・sept 272→98、sample_v6 は採用 3→9・total 337→332、他 3 件は同一。ベンチ中（3.507.1）  → `docs/history/3.4xx.md`
- 個人上限 0（0〜0）のシフトを最適化器が置かない＝`Problem.mayPlace`（候補生成 Kotlin 101 箇所・C++ 28 箇所・C# 101 箇所）＋入口 hf66 で既存セルを外す＋最終番兵の基準を「外した入力」に。評価・表示・重みは不変。再現データで必須 0 のまま 桒澤/大島 の該当が 0、4 フィクスチャは旧新同一、C++ パリティ 0（3.507.0）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 5 報告＝半径拡大＋最終段予算で新良 153/同等 179/旧良 8・必須が減った試行 62（最大 -38）・増 1・品質 +1.72%（大規模の充足不能 +11.5%）・速度 -2.3%・再現性 34/34。退行ゼロ合格、10% ゲート未達。次は決定的予算のベンチと生成候補の質（3.506.1）  → `docs/history/3.4xx.md`
- 「グループ単位の回数」で下限・上限とも「なし」を適用＝グループ全員ぶんの個人上下限を解除（`clearGroupRangeAll`、実機報告「なし 設定出来ない」）（3.506.0）  → `docs/history/3.4xx.md`
- CLAUDE.md を Claude 5 世代向けに見直し＝376 行→84 行（手順・履歴・バックログを docs/environment.md・docs/backlog.md・architecture.md・topics.md へ移動、重み規約を .claude/rules/weights.md へ、禁止形を判断基準へ）（3.505.9）  → `docs/history/3.4xx.md`
- CLAUDE.md の見直し提案（記事の 7 ステップ、docs/claudemd-tune-proposal.md＝承認待ち）と事実の訂正（重み c1/c3mn 30・covO 5、fork 名、ホスト JVM でビルド可、検証ハーネス節）（3.505.8）  → `docs/history/3.4xx.md`
- SUDO モデル（docs/sudo_model.md）を実装と再照合＝重み c1/c3mn 30・covO 5、O の実測 5015、欠損セル -1、S に Windows 版、D に拒否候補の再利用（3.505.7）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 5 第一弾実装＝起点生成に同長区間交換と同日 3 職員巡回、最終段の予算を残り時間で 4 倍。実データ 4/4 盤面同一。ベンチ中（3.505.6）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 4 報告＝起点生成の最終段で新良 152/同等 180/旧良 8・必須が減った試行 52（最大 -20）・増 1・品質 +1.07%（充足不能 +6〜8%）・速度 -1.8%。退行ゼロのゲートを初めて合格、10% ゲートは未達。次は生成の半径拡大（3.505.5）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 4 第一弾実装＝違反起点からの候補生成（半径 1）。巡の中で生成すると単セル covU 修正が共同 LNS の余地を先に使い sample_v6 が HARD 8→11 に退行したため、生成つき修復は共同 LNS の後の最終段へ（4/4 非退行）。ベンチ中（3.505.4）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 3 報告＝新良 71/同等 245/旧良 24・必須退行 0（減 6・増 4、最大 -9）・品質 +0.11%・速度 -3.1%・メモリ 1.18 倍＝10% ゲート不合格。改善は飽和＝ハイブリッド併用を維持してループを閉じる推奨（3.505.3）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 3 第一弾実装＝相方なしピン破りの事前除外・構造的に埋められない covU 起点の後回し。実データ 4/4 盤面同一、推定回数は最大 1/2（3.505.2、ベンチ中）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 2 報告＝違反起点のトランザクション修復を 340 ペアで比較。必須退行 0・新良 68/同等 249/旧良 23・品質 +0.08%・速度 -3.3%・再現性 33/34＝10% ゲートは不合格→§6 ハイブリッド併用として既定 ON（C# 同期）。次反復の候補 3 点（3.505.1）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 2 第一弾＝違反起点のトランザクション修復 ViolationComponentRepair（拒否候補の横断プール・DeltaEvaluator 推定＋ピン事前枝刈りのビーム・正式 commit・既定 OFF）。実データ sample_v6 で HARD 9→8。外部ドッグフーディング 4 件（ISCC 終了コード・RoundRobin 例外経路・月初月末テスト・ビーム盤面重複排除）も反映（3.505.0）  → `docs/history/3.4xx.md`
- 自律改善ループ Iteration 1 報告＝希望島＋アンカー窓交換 vs 旧チェーンを 340 ペアで比較。必須ゼロ率 35.3%→35.3%・品質 平均 -0.18%・速度 -0.8%・再現性 33/34＝ゲート不合格→§6 ハイブリッド併用で温存、断捨離せず、改善飽和として閉じる推奨。ハーネスは tools/loop/（3.504.2）  → `docs/history/3.4xx.md`
- 外部提示 C# 6 ファイルの優劣検証と移植＝希望島ビームの島巡回＋残り予算で頭打ち（単体 1 改善/11 不変・後処理 4/4 不変）と C1 ビームのハッシュ鍵 dedup・try/finally 巻き戻しを採用、公平分配/枝刈り計上/root 初期化/族ゲート/OpsAreValid は計測つきで否決。提示 RoundRobin の破棄漏れをテストで捕捉（3.504.0）。最終コードレビュー 5 件を反映＝ビームの走査枠は評価した手だけ数える・KDoc 修正・beamMoves 一本化（3.504.1）  → `docs/history/3.4xx.md`
- 自律レビュー＆リファクタ第4段 V6PortAnalyzer＝diagnoseCoverage を不足/緩和案/過剰の3関数へ分割、Probe 定数・chainFills/spreadTerm/parseKeyPair 共有化。出力ハッシュ 24/24 一致（3.503.0）  → `docs/history/3.4xx.md`
- バックログ #9(c)(d)・#10 を実装＝希望島ビームを「走査 2 倍・良い順に保持」へ（計測は中立）、担当外希望の一括クリアを canDo 再判定へ、「なおすのを手伝って」の連打防止を検査世代 checkRev へ。#4 は運用で解消と確定（3.502.0）  → `docs/history/3.4xx.md`
- 希望島研磨の候補配分を変更＝禁止連は件数が増える手だけ枝刈り・同日/窓/両翼を交互評価・島の枠 25% を巡回に確保（3.501.0, ユーザー指示。単体は混在・後処理全体の最終盤面は 4/4 不変）  → `docs/history/3.4xx.md`
- 外部レビュー第3・4段の Android 同期＝`setCell`/`setCells` にシフト index の上限検査、案内付き修正の連打防止（同型）をバックログ #10 へ（3.500.2）  → `docs/history/3.4xx.md`
- 外部レビュー（-MAGI_PC 側 2 段）の Android 同期＝Undo/Redo で engineRan を落とし改善提案を破棄、希望島の評価配分・c3n 枝刈り・ビーム列挙順・担当外希望の再判定をバックログ #9 に登録（3.500.1）  → `docs/history/3.4xx.md`

版数付きの作業記録（355節）は**この索引だけを常時読み込み**、本文は `docs/history/` に置く
（毎ターンのコンテキスト固定費を減らすため。3.468.0 で分離を始め、3.470.0 で本体から全部出した）。
バケツは版数で分かれる: `2.x.md` / `3.0xx.md` / `3.1xx.md` / `3.2xx.md` / `3.3xx.md` / `3.4xx.md`。
**同じ領域を触る前に必ずここを検索する**＝過去に測って否決した案・同型のバグ・決定記録を二度踏まないための索引。
本文の引き方: `grep -n '（3.409.21' docs/history/3.4xx.md` のように版数で引き、その節を読む。
見出しは原文のまま（版数・補足・ユーザー指示の引用を落とさない）。

- 後処理オーケストレータ（V6HotfixPasses.runPostOptimization）の自律レビュー＆リファクタ＝PostChain ランナー（採用・ピン帰属合流・ログ・計時を 1 経路に）・PostOptimizationParams 集約・SeedTag・クラスタ/検証ログ/診断の分離（3.500.0, ユーザー指示「自律レビュー＆リファクタリングループ」第3段、4 実データで最終盤面一致、24 サイトの定型を撤去）  → `docs/history/3.4xx.md`
- 可変長ブロック交換（AdaptiveBlockSwapPolish）の自律レビュー＆リファクタ＝Session 化・KeepBest/RejectStats の共通化・Priority/Params 集約・厳密窓交換の重複排除を方向フィルタの後へ（3.499.0, ユーザー指示「自律レビュー＆リファクタリングループ」第2段、fixture 24 ケースで盤面一致、旧実装が捨てていた窓が各条件 1〜9 件回復、C# は Step B と重み出典のみ同時）  → `docs/history/3.4xx.md`
- 希望島研磨（WishIslandPolish）の自律レビュー＆リファクタ＝候補の遅延生成・Session 化・パラメータ集約・不活性島の評価スキップ（3.498.0, ユーザー指示「自律レビュー＆リファクタリングループ」、fixture 12 ケースで盤面一致を確認、C# も同時）  → `docs/history/3.4xx.md`
- CLAUDE.md の常時読み込み分を 5.8 万→1.2 万トークンへ＝索引と話題別叙述を docs/history へ（3.497.3, docs のみ, ユーザー提示の記事2本→ AskUserQuestion で「索引と叙述節を全部 docs へ」を選択）  → `docs/history/3.4xx.md`
- ナレッジグラフ4ツールを実測して Serena だけ試験導入（3.497.2, docs/設定のみ, ユーザー提示の記事「トークン2000分の1——オントロジー×ナレッジグラフで Claude Code の推測を消す」→ AskUserQuestion で「Serena だけ」を選択）  → `docs/history/3.4xx.md`
- 追加コメントの点検を手順化＝comment-check スキルと tools/comment_ratio.py（3.497.1, docs/tools のみ, ユーザー提示の記事「ルールではなく skill に指示を書くことで、Claude のコメントを減らせた」）  → `docs/history/3.4xx.md`
- エンジンの締切・経過・停滞判定を単調時計へ（3.490.0, レビュー第6弾＝Android 側は1件が該当）  → `docs/history/3.4xx.md`
- 個人の下限を 0 に設定できなかった（3.489.0, 実機報告「設定出来ない」→「個人の下限をゼロに出来ない」）  → `docs/history/3.4xx.md`
- 読めない startDate を読込で拒否＋groupShiftApt を読込で G×K に正規化（3.488.0, レビュー第5弾）  → `docs/history/3.4xx.md`
- 原子置換のフォールバック前に所有権を再確認＋成果物掃除の全件失敗を赤に（3.487.0, レビュー第4弾＝新規2件）  → `docs/history/3.4xx.md`
- 読込時に endDate を日数へ正規化＋Android Lint を CI で実行＋失敗ログ条件（3.486.0, レビュー第3弾＝総括12件の検証）  → `docs/history/3.4xx.md`
- 自動保存の世代逆転を塞ぐ（3.485.0, Windows 版レビュー第2弾3件の検証＝Android 側は1件が該当）  → `docs/history/3.4xx.md`
- 担当可否の単一セルでも「休」を外せないようにする（3.484.0, Windows 版レビュー指摘の兄弟バグ）  → `docs/history/3.4xx.md`
- OPPO A5 5G（Android 16・360dp 帯）を対象に加える＝幅 390dp 未満の名前列56dp化と下部バーの圧縮（3.497.0, ユーザー指示）  → `docs/history/3.4xx.md`
- 希望島研磨（WishIslandPolish）を新設（3.496.0, ユーザー提示の確定仕様「実現可能な希望シフト日を固定アンカーにする」）  → `docs/history/3.4xx.md`
- 違反アンカー型・可変長ウィンドウ交換＝AdaptiveBlockSwap に STRICT_WHOLE_WINDOW モードを追加（3.495.0, ユーザー提示の設計。RunSwapPolish を置換）  → `docs/history/3.4xx.md`
- 連交換研磨を汎用化＝夜勤前提を撤廃（3.494.0, ユーザー指示「特定のシフトを特別扱いしない」。RunSwapPolish が NightRunSwapPolish を置換）  → `docs/history/3.4xx.md`
- 夜勤連交換研磨（NightRunSwapPolish）を新設（3.493.0, ユーザー指示「夜勤を他職員と交換する違反研磨」。計測で改善手0を示したうえで「keep-best で入れる」を選択）  → `docs/history/3.4xx.md`
- 人員過剰の「希望固定N人」を名指しして、その場で希望を取り消せるようにする（3.492.0, 実機指摘「データ修正のサポートが無い」）  → `docs/history/3.4xx.md`
- 停止の意味論と並列仮説の全本起動を揃える＋読込正規化の永続化（3.491.0, レビュー第7弾＋「あなたが賢く見直す」の自己見直し。3.490.0 の「採否不変」を訂正＝EarlyChain が初めて生きた。回帰テストは変異検証つき）  → `docs/history/3.4xx.md`
- 全画面の静的モックアップをキャンバスに起こしリポジトリへ格納（3.483.1, docs のみ, ユーザー指示「/design すべて」→「mainにマージする」）  → `docs/history/3.4xx.md`
- ドッグフーディング所見20件を全部修正（3.483.0, ユーザー指示「すべて修正する」＝`docs/screen_inventory_textart.md` の所見表）  → `docs/history/3.4xx.md`
- 編集タブの簡素化＝職員一覧の一本化・並びルールの起点別チップ＋重複ガード・導線重複の解消・用語統一・マトリクスの文字欠け修正（3.482.0, ユーザー提示のスクショ18枚検証レポート・grillingで4点確定＋追加指示1点）  → `docs/history/3.4xx.md`
- 勤務表タブの再設計＝日ヘッダの固定＋週送り/違反ナビの画面下固定（3.481.0, Claude起案・grillingで「任せる」＝推奨2項目を実施）  → `docs/history/3.4xx.md`
- ホームタブをAI提案型に再設計＝処方箋(スマートアクション)先出し・診断文の折りたたみ・重複ボタン排除（3.480.0, ユーザー提示の詳細設計案・grillingで5点確定）  → `docs/history/3.4xx.md`
- シフト集計カードに職員別/日別の切替を復活＝3.477.0で撤去した機能を実機要望で再統合（3.479.0, ユーザー指示・grillingで1点確認）  → `docs/history/3.4xx.md`
- MagiThemeのFontSize/Typographyトークン適用＝ハードコードfontSize 63件をMaterialTheme.typography.*へ・design_lint P11新設（3.478.0, ユーザー指示・grillingで5点確定）  → `docs/history/3.4xx.md`
- 担当可否・目標・上下限・実績を職員×シフトの1マトリクスへ統合＝AptSection/StaffRangeSectionを撤去（3.477.0, ユーザー提示案・grillingで7点確定）  → `docs/history/3.4xx.md`
- 群×シフト担当可否を2次元マトリックスへ再設計＝固定ヘッダ・セル全面タップ・行/列一括（3.476.0, ユーザー提示案・Windows11版と同時対応）  → `docs/history/3.4xx.md`
- 論理的不具合の並列監査（6角度）＝実在30件を修正・5件は複雑さ/確度から見送り明記（3.475.0, ユーザー指示「論理的不具合も修正する」）  → `docs/history/3.4xx.md`
- /code-review（3.473.0の自己検証, 8並列finder）＝実在9件を全部修正、うち1件は3.473.0自身の退行（3.474.0, ユーザー指示「不具合が全て修正する」）  → `docs/history/3.4xx.md`
- /code-review（3.472.0の自己検証）＝2件を追加修正（3.473.0）  → `docs/history/3.4xx.md`
- 外部レビュー100件を並列検証し、実在4件を修正（3.472.0, ユーザー提示「添付は何%正しいか」＋「問題点のコードをすべて実装する」）  → `docs/history/3.4xx.md`
- 分析タブを1画面へ再構築＝分類の軸を「族」から「診断」へ（3.471.0, ユーザー提示のモック＋grilling の合意）  → `docs/history/3.4xx.md`
- CLAUDE.md 本体から版数付き作業記録を全部出した＝固定費 202k→54k-tok（3.470.0, ユーザー選択「残り148節も分離」）  → `docs/history/3.4xx.md`
- ビット幅の境界を周辺へ当て直す＝窓口の迂回1件と署名パック幅の無保証1件（3.469.0, ユーザー指示「周辺や類似する箇所を再検証する」）  → `docs/history/3.4xx.md`
- CLAUDE.md の作業記録を索引化＝毎ターンの固定費を 422k→202k へ（3.468.0, ユーザー回答「困りごと＝コンテキスト圧迫」）  → `docs/history/3.4xx.md`
- ドッグフーディング＝③統合の直後に editRev 取り残しを発見・是正（3.467.0, ユーザー指示「ドッグフーディング検証する」）  → `docs/history/3.4xx.md`
- 編集タブ「③ 回数（1人あたり）」の3枚カードを1枚へ統合（3.466.0, ユーザー指示「冗長性を賢くシンプルデザインに深く考え直す」）  → `docs/history/3.4xx.md`
- covO（人員過剰）重み 1.0→5.0（3.465.0, ユーザー明示数値指示＝HF77一時保留のうえ確定）  → `docs/history/3.4xx.md`
- 統合カード(ViolationHubCard)の達成表示・展開状態を修正＝外部レビューP1-01/P2-01（3.464.0）  → `docs/history/3.4xx.md`
- wideC3nBreakDays を既定OFFで最終確定＝ユーザー指示「AB評価」への回答（3.463.0）  → `docs/history/3.4xx.md`
- fixture の shiftColors に専用テストを追加＝外部レビュー L-01（3.462.0, receiving-code-review規律で検証のうえ実施）  → `docs/history/3.4xx.md`
- 冗長な「チップの枠」トグルを撤去＝常時表示へ一本化（3.461.0, ユーザー指示「冗長性をシンプルデザインにする」）  → `docs/history/3.4xx.md`
- 色ピッカーを36色(6×6)へ拡張＋チップ枠トグルの文言修正（3.460.0, ユーザー指示「6×6の36色にしてください」＋実機報告「シフト種別の枠が勤務表に反映しない」）  → `docs/history/3.4xx.md`
- 分析タブ3カードの統合＋違反フィルタの有効活用（3.459.0, ユーザー指示「1，2をあなたが賢く深く考え判断して修正する」）  → `docs/history/3.4xx.md`
- シフト種別チップの枠線を選択式に＋希望バッジ凡例の統合＋グリッドキャプションの重複解消（3.458.0, ユーザー指示3件）  → `docs/history/3.4xx.md`
- 勤務表グリッドの通常セル枠線を選択式に（3.457.0, ユーザー指示「違反以外の普通の枠の表示有無を選択できるようにする。デフォルトは枠表示無し」）  → `docs/history/3.4xx.md`
- 既定OFFの3トグルをこのサンドボックス上で再測定（3.456.0, ユーザー指示「既定OFFのトグル3つ…全てあなたの仮想環境で再測定」）  → `docs/history/3.4xx.md`
- テスト用サンプルデータの既定シフト表示色を設定（3.455.0, ユーザー提示の11シフト配色案）  → `docs/history/3.4xx.md`
- 色ピッカーをユーザー手指定の25色へ再び全面差替え（3.454.0, ユーザー提示の別の25色表）  → `docs/history/3.4xx.md`
- 色ピッカーをユーザー手指定の25色へ全面差替え＝アンカー制約と両立させて再配置（3.453.0, ユーザー提示の25色表）  → `docs/history/3.4xx.md`
- 色ピッカーの隣接差異を15%→25%へ強化＝ヒルクライム局所探索で再設計（3.452.0, ユーザー指示「各色は色彩など25%づつ変更する」）  → `docs/history/3.4xx.md`
- HF80戦略的振動のOOM根本修正＝localBestImprovementをEvaluatorベースの候補生成へ（3.451.0, ユーザー指示「根本的に修正する」）  → `docs/history/3.4xx.md`
- largeHeap 追加＝後処理HF80のタイトループOOMを既定ヒープ緩和で対処（3.450.0, ユーザー提示の実機ログ12月データから調査）  → `docs/history/3.4xx.md`
- wideC3nBreakDays を4件目の実データで再測定＝符号不一致で既定OFF据え置きを確定（3.449.0, ユーザー提示の実機ログ3本＋state.json直接アップロード）  → `docs/history/3.4xx.md`
- 色ピッカーの識別性と警告色の実選択可能性を確保（3.448.0, ユーザー提示のスクショ2枚から）  → `docs/history/3.4xx.md`
- 背景実行の開始トランザクション3件を原子化＋normalStallFraction再測定を完了（3.447.0）  → `docs/history/3.4xx.md`
- 続く2表(13+15項目)の検証＝dWeekly自己復元窓にも同型の穴を発見・修正、残りは反証つきで対応不要（3.446.0）  → `docs/history/3.4xx.md`
- 外部レビュー8件の検証＝実在1件のみ・DeltaEvaluator.previewMoveへtry/finally（3.445.0, receiving-code-review規律で全件検証）  → `docs/history/3.4xx.md`
- 週送り/違反ジャンプを画面下部へ＋行列クロスハイライト（3.444.0, ユーザー提示の再設計案から）  → `docs/history/3.4xx.md`
- シフト集計の違反バッジをピル形に＝視認性改善（3.443.0, ユーザー提示の目標デザイン画像から）  → `docs/history/3.4xx.md`
- 勤務表グリッドの土日・祝日色分け＋祝日データの外部ファイル化（3.441.0, ユーザー提示の目標デザイン画像から）  → `docs/history/3.4xx.md`
- M8/M9を明示指示で解消＝allowBackupの無効化とsaveNow()の観測性強化（3.440.0, ユーザー指示「賢く深く考え修正する」）  → `docs/history/3.4xx.md`
- 勤務表タブのレイアウトをモックアップへ合わせる＝週ラベルの年月併記＋違反凡例のカジュアル化（3.439.0, ユーザー提示の目標デザイン画像から）  → `docs/history/3.4xx.md`
- CSV追加行の埋めシフトと JNI の cons1/cons2 検証＝並行セッションの 3.438.0 が残した3件（3.442.0）  → `docs/history/3.4xx.md`
- 外部提示の「レビュー文書」を検証＝停止後に running が永久固着する実バグを発見・修正（3.438.0）  → `docs/history/3.4xx.md`
- MagiViewModel のコメントに残っていた絶対行番号参照4件を是正（3.437.0, 自律監査の続き）  → `docs/history/3.4xx.md`
- 必要人数(need1/need2)だけ下限>上限の入力時ガードが無かった（3.436.0, 自律監査の続き）  → `docs/history/3.4xx.md`
- 完了メッセージが内部識別子を出し、開始と語彙が食い違っていた（3.435.0, 自律監査の続き）  → `docs/history/3.4xx.md`
- 職員削除の確認ダイアログに「勤務・希望も消えます」警告が無いドアがあった（3.434.0, 自律監査の続き）  → `docs/history/3.4xx.md`
- シフト/グループ削除確認ダイアログの文言整理（3.433.0, 自律監査＝design-review 手順を適用）  → `docs/history/3.4xx.md`
- 色ピッカーの25色を淡いパステル調へ＋選択チェック印を太字化（3.432.0, ユーザー指示「淡い中間色 25色＋ 濃色太字テキスト」＋実機スクショ）  → `docs/history/3.4xx.md`
- 勤務表タブの「違反セル：他75件」棒読みリストを撤去（3.431.0, ユーザー指示「冗長を賢く見直す。人間が見やすいデザインにする。」＋実機スクショ4枚）  → `docs/history/3.4xx.md`
- 色ピッカーを25色（5×5）へ拡張（3.430.0, ユーザー指示「25色設定にする」＋実機スクショ）  → `docs/history/3.4xx.md`
- シフト/グループ削除の確認ダイアログへ影響件数を表示＝外部レポートR-03（3.429.0, 実機ログ＋100件統合レポートの検証から）  → `docs/history/3.4xx.md`
- 100件レビューの未確認70件を全部当てて、実在した4件を直す（3.428.0）  → `docs/history/3.4xx.md`
- ペア禁止の行を読める形にして二重表示を撤去＝④⑤の説明レイヤーも整理（3.427.0, ユーザー指示「冗長を賢く直す」＋実機スクショ2枚）  → `docs/history/3.4xx.md`
- 並行ブランチの後始末を選別統合＝版番号の取り違えと文書の実装乖離（3.425.0, ユーザー指示「mainでマージする」）  → `docs/history/3.4xx.md`
- A/B結果を確定＝`normalStallFraction` は既定0.9のまま据え置き（3.423.0, 3.422.0 Part B のフォローアップ）  → `docs/history/3.4xx.md`
- 3.422.0 が到達可能な帯まで無計測で厳格化していた＝予算基準の復元と入力検証（3.424.0, /code-review 3件）  → `docs/history/3.4xx.md`
- 停滞ウォッチドッグ「通常」分岐の閾値を実際の探索区間へ合わせる＋A/B用の可変割合を新設（3.422.0）  → `docs/history/3.4xx.md`
- 別ブランチ発PRの棚卸し＝x8ygvy/rsmp2p は版番号衝突で盲目マージせず選別統合（3.421.0）  → `docs/history/3.4xx.md`
- P10 が baseline超過を検出＝removeShift 自身の記号比較を一本化（3.420.0, ラチェット実効）  → `docs/history/3.4xx.md`
- 同じ穴が探索の入口にもあった＝埋める規則を1箇所へ（3.419.0）  → `docs/history/3.4xx.md`
- 空きマスを「担当できないシフト」で埋めていた（3.418.0, 3.417.0 の掃討で発見）  → `docs/history/3.4xx.md`
- 記号の字面でシフトを分類・除外する経路を撤去（3.417.0, 受領した中立化パッチ2本の再検証）  → `docs/history/3.4xx.md`
- 「休」を通常のシフト定義へ統一＝編集ガード2つの撤廃と全域監査（3.416.0, ユーザー明示方針）  → `docs/history/3.4xx.md`
- 「休」シフトの記号改名に入口ガードが無かった（3.415.0, 外部レビュー撤回文書 R-04）  → `docs/history/3.4xx.md`
- CSV取込が期間を推定して黙って確定していた（3.414.0, 100件レビュー I-02）  → `docs/history/3.4xx.md`
- CSVの取込で黙って消えていた2つ＝未知の群記号と閉じない引用符（3.413.0, 100件レビュー第4巡）  → `docs/history/3.4xx.md`
- 期間より長い窓の要件が評価も警告もされず消えていた（3.412.0, 100件レビュー第3巡）  → `docs/history/3.4xx.md`
- 100件レビューの第2巡＝残り6件を直し、44件は「未検証」と正直に数える（3.411.0）  → `docs/history/3.4xx.md`
- 外部レビュー100件を実コードに当てて、実在した項目だけ直す（3.410.0, ユーザー指示「すべて修正する」）  → `docs/history/3.4xx.md`
- トグル A/B の第2ラウンド＝240s で3件とも測り切った（3.409.25, docs のみ）  → `docs/history/3.4xx.md`
- 回避の並び(c3mn)と窓の要件(c1)の重みを 15→30（3.409.24, ユーザー明示数値指示＝HF77）  → `docs/history/3.4xx.md`
- 並列監査の残り2件＝制約 index の入口ガードと「1日あたり上限」の前提（3.409.23）  → `docs/history/3.4xx.md`
- ネイティブ修復器が Kotlin から5世代ぶん取り残されていた（3.409.22, 外部レビューを全件検証して修正）  → `docs/history/3.4xx.md`
- 既定OFFトグル2つを単体 A/B で測って削除（3.409.21, ユーザー選択「両方削除」）  → `docs/history/3.4xx.md`
- C41/C42/C41s/C42s の説明を別々の制約として書き分け（3.409.20, ユーザー指示）  → `docs/history/3.4xx.md`
- /design-review＝3.409.18 の自分の変更に SHOULD 1件（3.409.19）  → `docs/history/3.4xx.md`
- ペア禁止の説明を人間に分かる形へ＝聞き返された3点を修正（3.409.18, ユーザー選択で4件全部）  → `docs/history/3.4xx.md`
- 予算超過を観測可能にする＝2本目の実機ログで13回中5回が300秒予算を474〜959秒まで超過（3.409.17）  → `docs/history/3.4xx.md`
- covU-blocked の実データを匿名化して第3フィクスチャへ＝backlog#6 の残りを解消（3.409.15）  → `docs/history/3.4xx.md`
- 実機ログ(3.409.14・2026-09データ)の検分＝表示の自己矛盾3件を修正（3.409.16）  → `docs/history/3.4xx.md`
- 制約10族の「詳しい説明」をアプリへ＝既定で閉じた ⓘ 展開（3.409.14, ユーザー指示「詳しく説明をアプリにも追加」）  → `docs/history/3.4xx.md`
- /code-review 7件を全て修正＝作ったばかりの P9 に本物の穴が3つあった（3.409.13）  → `docs/history/3.4xx.md`
- この環境で実行できないと言っていたものを、実行できる形に置き換える（3.409.12）  → `docs/history/3.4xx.md`
- 判断待ち4件を決着＝c1 の表示強度は「件数」でなく「セル数」で測る（3.409.11）  → `docs/history/3.4xx.md`
- 残した3つを片付ける＝λ上限は「配線できない」と確定／DS の ✅ を機械検査へ（3.409.10）  → `docs/history/3.4xx.md`
- 提示レビュー P0-P3 の照合＝広域ビームのピン合流漏れと停止伝播の回帰（3.409.9）  → `docs/history/3.4xx.md`
- 呼出0の関数6つを撤去＝「4つの編集入口」は最初から3つだった（3.409.8）  → `docs/history/3.4xx.md`
- 族→日本語名の表を、テストできる場所へ（3.409.7）  → `docs/history/3.4xx.md`
- ラチェットを 0 まで下げる＝任意の角丸と生 hex を tier へ（3.409.6）  → `docs/history/3.4xx.md`
- 「baseline 監視」が監視になっていなかった＝P2/P4 のラチェット化（3.409.5）  → `docs/history/3.4xx.md`
- 外側ワーカーの実効並列度をログへ＋検証器が空振りしていた件（3.409.4）  → `docs/history/3.4xx.md`
- 画面の語彙を記録された決定へ揃える（3.409.3, /design-review の SHOULD 3件）  → `docs/history/3.4xx.md`
- タッチ域チェックリストの誤った ✅ を実測して訂正（3.409.2）  → `docs/history/3.4xx.md`
- 死んだ配管の撤去と、生きている配管の配線（3.409.1, ユーザー指示「配管と配線する」「スタブなどを実装する」）  → `docs/history/3.4xx.md`
- 検査自身が守れていなかった＝P6 の複数行見落としと、そこに隠れていた3件（3.409.0, /code-review）  → `docs/history/3.4xx.md`
- 操作ログに実行IDが無く、複数回実行後の書き出しが自己矛盾に見えた＋停滞監視が並列で恒久的に無効化されていた（3.408.0）  → `docs/history/3.4xx.md`
- 同梱の見本データが文字化けしたまま出荷されていた＋テストの永続的な誤検出（3.407.0, ユーザー指示「文字化けを修正する」「500テストの断捨離する」）  → `docs/history/3.4xx.md`
- 実機ログと受領した不具合一覧を裏取りして6件（3.406.0）  → `docs/history/3.4xx.md`
- シートが守れない約束をしていた（3.405.0, 監査 high の最後の1件）  → `docs/history/3.4xx.md`
- 旗の名前が「最適化」だったせいで、同じ性質の3ジョブが旗を立て忘れていた（3.404.0）  → `docs/history/3.4xx.md`
- 下限>上限を、あとから叱るのでなく入力時に止める（3.403.0, 監査 D-1）  → `docs/history/3.4xx.md`
- 止める手段が消える／押せる行と押せない行が同じカードに混ざる（3.402.0, Nielsen 監査の残り2件）  → `docs/history/3.4xx.md`
- 「なおすのを手伝って」が診断と正反対の約束をしていた（3.401.0）  → `docs/history/3.4xx.md`
- 操作の返事が返ってこない群を潰した＋3.399.0 の自分の回帰（3.400.0）  → `docs/history/3.3xx.md`
- Nielsen 10原則の並列監査＝確認できた最上位2件を直した（3.399.0）  → `docs/history/3.3xx.md`
- 日本語テンプレート食い込みを機械の検査にした＋取消の無いダイアログ（3.398.0）  → `docs/history/3.3xx.md`
- 残していた貼り紙2件の「形」を作った（3.397.0）  → `docs/history/3.3xx.md`
- 貼り紙を剥がして形に語らせる（3.396.0, ユーザー提示の設計原則）  → `docs/history/3.3xx.md`
- 違反チェッカーを −23% 高速化＝出力は1ビットも変えずに（3.395.0, ユーザー指示「高速化対応する」）  → `docs/history/3.3xx.md`
- ちらつき対策が既定経路で効いていなかった＝測り直して修正（3.394.0, /code-review 6件）  → `docs/history/3.3xx.md`
- Web互換の撤去・最適化中のちらつき・死んだ配管の始末（3.393.0, ユーザー指示3点）  → `docs/history/3.3xx.md`
- 論理的問題の横断監査＝旗の固着・無言の編集・矛盾するデッド述語（3.392.0, ユーザー指示「すべての論理的問題点などを修正する」）  → `docs/history/3.3xx.md`
- 実現不能な希望を「固定」と誤扱いする穴を9箇所修正（3.391.0, ユーザー指示「不具合を全て修正する」）  → `docs/history/3.3xx.md`
- SUDO モデルを実装から起こす（3.389.0, ユーザー提示「SUDOモデリング」）  → `docs/history/3.3xx.md`
- 所有権を失った実行が新しい実行を恒久的に無効化していた（3.388.0, /code-review 11件の検証）  → `docs/history/3.3xx.md`
- 埋められない穴をログで観測可能にする（3.387.0, ユーザー指示「残っている、埋められない穴などログ強化する」）  → `docs/history/3.3xx.md`
- Worker の「コメントだけの再発防止」をテストへ＝RunFiles 抽出（3.386.0, ユーザー指示「修正する」）  → `docs/history/3.3xx.md`
- 途中最良の publish が「評価」と「盤面」で食い違う競合を修正（3.385.0, 外部レビューの検証から）  → `docs/history/3.3xx.md`
- 既定 OFF トグルの「見直しの条件」を明文化＝腐らせない（3.384.0, R-09 解消）  → `docs/history/3.3xx.md`
- 「検証できないと見送った項目」をログで検証可能にする（3.383.0, ユーザー指示）  → `docs/history/3.3xx.md`
- 終端ログの保証を全経路へ＋Error も拾う＋族分類の取りこぼしを機械固定（3.382.0, ユーザー指示「修正する」「完了・停止・失敗のいずれも記録されるログ強化」）  → `docs/history/3.3xx.md`
- 停止処理が丸ごと飛んでいた＝ハンドラ全体を NonCancellable で包む（3.381.0, 実機ログで原因特定）  → `docs/history/3.3xx.md`
- covO の違反詳細が「場所数」を「件数」として出していた（3.380.0, 添付ログから）  → `docs/history/3.3xx.md`
- need1直参照の第4世代（修復オペレータ2つ）＋最適化診断がログから消える（3.379.0）  → `docs/history/3.3xx.md`
- デバッグできるログへ＝スコア収支・改善の軌跡・沈黙していた追加精製（3.378.0, ユーザー指示「デバッグできるようにログを強化する」）  → `docs/history/3.3xx.md`
- 残存分析が「もう直せない covU」を見落としていた＋Watchdog の時間軸混在（3.377.0, 実機ログ 2026-08-15 から）  → `docs/history/3.3xx.md`
- 並列を本当に動かす＝HARD=0 到達時の「残りを即キャンセル」を撤廃（3.376.0, ユーザー指示「ワーカー、並列が本当に動くようにする」）  → `docs/history/3.3xx.md`
- HARD=0 入力で8並列が実質1並列に潰れる＋残存分析の二重計上＋停滞ログに反復数（3.375.0, ユーザー指示「ログから新しい不具合を見つける」「停滞脱出のログを強化する。イテ回数と時間を出す」）  → `docs/history/3.3xx.md`
- 希望ロックで達成不能な適切回数を事前診断する＝検査6d（3.374.0, ユーザー指示「全て修正する」）  → `docs/history/3.3xx.md`
- 実機ログ(2026-08-15)起因の2件＋compileSdk 37 の可否確認（3.373.0, ユーザー指示「修正する？」で4件全選択）  → `docs/history/3.3xx.md`
- 3.371.0 の /code-review 指摘4件を検証して修正（3.372.0）  → `docs/history/3.3xx.md`
- 並列SAの本格再有効化＋soft全族の完全差分（3.371.0, ユーザー指示「並列SAの本格再有効化する」「soft全族の完全差分する」）  → `docs/history/3.3xx.md`
- needFamilies 新設＝covU/c41系の重なりで場所一覧が件数より少なく見える穴を解消＋CI download の無防備さを是正（3.370.0, ユーザー指示「同様な問題などあるか?」）  → `docs/history/3.3xx.md`
- /code-review 全コード＝need2単独定義セル見落としの第3世代を発見・修正（3.369.0, ユーザー指示「すべてのフルコードを/code-review する」）  → `docs/history/3.3xx.md`
- 族数「18種」の docs 取り残しを19種へ横断修正（3.368.0, 3.202.0 の兄弟 docs への波及完了）  → `docs/history/3.3xx.md`
- 重み値コメントのドリフト＋c1 表示昇格の判断点（3.367.0, 3.366.0 の sibling-bug 掃討を重み定数へ拡張）  → `docs/history/3.3xx.md`
- 外部レポート L1-L10 の周辺検証＝keep-best 順序コメントのドリフト12件を訂正（3.366.0, ユーザー「周辺も検証する」）  → `docs/history/3.3xx.md`
- 共有ネイティブハンドルの並列安全性を実行テストで示す（3.365.0, 別ブランチ x8ygvy から選択的に取り込み）  → `docs/history/3.3xx.md`
- c1「壁」判定の need2 依存を実データ計測で false wall と確定・正直化（3.364.0, backlog#4 解消）  → `docs/history/3.3xx.md`
- 直近コード（3.352-3.360）の焦点レビュー＝clean 確認＋stale fact 1件訂正（3.363.0）  → `docs/history/3.3xx.md`
- パリティネットへ2つ目の実データ形状 sample_v6 を追加（3.362.0, backlog#6「実データ形状の網羅」）  → `docs/history/3.3xx.md`
- covU-blocked 早期終了を実データ多seed A/B で確認却下（3.361.0 の再オープン条件を満たし、却下を補強）  → `docs/history/3.3xx.md`
- covU-blocked のウォッチドッグ配線を実測して却下（3.361.0, ユーザー指示「修正する」＝#1 の A/B）  → `docs/history/3.3xx.md`
- 提出された静的解析レポートの照合＝実コードに当たったのは1件（3.360.3）  → `docs/history/3.3xx.md`
- main のビルドを直す＝LocusIdCompat の import が誤っていた（3.360.2）  → `docs/history/3.3xx.md`
- ログのヘッダに版と実行環境を書く＋PORTFOLIO の合計iterと最良更新回数（3.360.0, ユーザー提示のログ強化仕様）  → `docs/history/3.3xx.md`
- 残りのピン計測外3箇所を測って決着（3.359.0, ユーザー指示「残り作業を最適化する」）  → `docs/history/3.3xx.md`
- 「どの日が塞いでいるか」をログへ＋パリティ不一致の次の一手（3.358.0, ユーザー指示「ログ強化する」）  → `docs/history/3.3xx.md`
- Kotlin↔C++ の言語跨ぎパリティを CI へ（3.357.0, 外部レポートの P0 主張を検証して判明した本当の穴）  → `docs/history/3.3xx.md`
- 設定トグルが実際に何をしたかをログへ＋詰まった理由の可視化（3.356.0, ユーザー指示「オプションを減らせるようにログ強化する」）  → `docs/history/3.3xx.md`
- weekly の構造床と大きな族の職員別集約をログへ（3.355.0, ユーザー指示「ログ強化する」）  → `docs/history/3.3xx.md`
- 「まだ狙える」に構造的な apt を入れていた＋6b/6c の断定を実態へ（3.354.0, 実機ログから）  → `docs/history/3.3xx.md`
- 回数の違反が診断から消えていた＝countFamilies 新設（3.353.0, 実機ログから）  → `docs/history/3.3xx.md`
- keep-best の順序を写す実装をなくす＝`reportComparator` 単一ソース（3.352.0）  → `docs/history/3.3xx.md`
- 予算按分の敵対検証＝壁は再現せず、代わりに wishLocked の取り残し19箇所（3.351.0）  → `docs/history/3.3xx.md`
- 最終LNS 2本のピン却下を計測へ配線（3.350.0, 外部レビュー #1 の再指摘を実測して採用）  → `docs/history/3.3xx.md`
- エリート統合の敵対検証＝実バグ0・観測性2件（3.349.2）  → `docs/history/3.3xx.md`
- 業務前提（30名・1か月）をコードで確認する＋提示レポート21件の検証（3.349.0）  → `docs/history/3.3xx.md`
- 3.347.0 の報告のみ2件を消化（3.348.0）  → `docs/history/3.3xx.md`
- 「ピン破り」の誤ラベルで主因族が隠れていた（3.347.0, 新領域の敵対検証）  → `docs/history/3.3xx.md`
- 停滞ラッチが降りない＋ワーカーの片肺運転（3.346.0, 実機ログ 2026-08-03 から）  → `docs/history/3.3xx.md`
- 休を通常のシフト種として扱う＋weekly を7日周期のシフト平準化へ（3.345.0, ユーザー明示指示）  → `docs/history/3.3xx.md`
- 人員不足診断の「充足可能」と「どう組んでも解消できません」の矛盾を解消（3.344.0）  → `docs/history/3.3xx.md`
- 禁止連続診断が「崩せる」と誤主張していた＝隣接日調整にも pref の代金を勘定（3.343.0）  → `docs/history/3.3xx.md`
- C1共同LNS が改善0のまま8秒を使い切っていた＝停滞打ち切り（3.342.0）  → `docs/history/3.3xx.md`
- 早期終了で余った予算を soft へ回す案を測って否決（3.341.1, 敵対レビュー A5）  → `docs/history/3.3xx.md`
- 複合手を原子化＝「意図を果たさない大きな破壊」をやめる（3.341.0, 敵対レビュー A2 を測って採用）  → `docs/history/3.3xx.md`
- C1広域ビームが探索を長く回すほど成果を捨てていた＝最良保持と停滞打ち切り（3.340.0）  → `docs/history/3.3xx.md`
- 後処理のパス別テレメトリ＝時間の行き先を見えるようにする（3.339.0, 敵対レビュー A4 の計測半分）  → `docs/history/3.3xx.md`
- ピンの不変条件を試験にする＝何が本当に守っているのかを実測（3.338.0, 敵対レビュー A2）  → `docs/history/3.3xx.md`
- 目的関数の二重管理を試験で塞ぐ＝Checker と Evaluator のパリティ（3.337.0, 敵対レビュー A1）  → `docs/history/3.3xx.md`
- ピンガードの抜け穴・c1ブーストの weighted 迂回・殻の失敗パス（3.336.0, 敵対レビュー）  → `docs/history/3.3xx.md`
- 探索の成果物を実行ごとの持ち物にする＋後段オペレータの比較が total へ落ちていなかった（3.335.0, 外部レビュー P1 2件）  → `docs/history/3.3xx.md`
- SA/LAHC の近傍が希望固定セルを触っていた＝手の35%が空振り（3.334.0, 3.333.0 の残り1件を計測して採用）  → `docs/history/3.3xx.md`
- 制約CSVの全置換ガード・完了後の実行中固着・指紋の行境界（3.333.0, 外部レビュー5件）  → `docs/history/3.3xx.md`
- 適応ポートフォリオのログが2つの母集団を1行に混ぜていた（3.332.0, 実機A/Bログから）  → `docs/history/3.3xx.md`
- C1頭打ち診断が最後の巡だけを見ていた＋結合探索の検査順（3.331.0, 実機ログ 2026-12 から）  → `docs/history/3.3xx.md`
- 新しい安全機構をテストできる場所へ移す（3.330.0, レビュー3回が挙げた「テスト不足」への回答）  → `docs/history/3.3xx.md`
- 入力の意味論を一本化＝休index・CSV全置換・所有権の残り（3.329.0, 外部レビュー第3回）  → `docs/history/3.3xx.md`
- 実行中の編集で全ガードが外れる欠陥＝running の二重用途を解消（3.328.0, 外部レビュー再確認）  → `docs/history/3.3xx.md`
- 実行の所有権と入力の検証＝外部レビュー High 5件（3.327.0, ユーザー指示「修正する」）  → `docs/history/3.3xx.md`
- 残作業3件を対応＝規則単位の診断・全パス計上・回数固定の緩和導線（3.326.0, ユーザー指示「1,2,3を賢く考え対応する」）  → `docs/history/3.3xx.md`
- 診断の紐付けと粒度を仕上げる＝原因未確定の明示・横断集計の分離・改名（3.325.0, ユーザー指示6項目）  → `docs/history/3.3xx.md`
- 診断表示の整合性を修正＝外部レビュー5件を全件確認して直す（3.324.0）  → `docs/history/3.3xx.md`
- 専用証明探索は実データで1件も証明できないと実測→代わりに緩和の根拠を出す（3.323.0, 優先順③の再設計）  → `docs/history/3.3xx.md`
- C1が直せなかった理由を構造化してUIへ＝3.321.0の一般化を実データで訂正（3.322.0, 優先順②「C-1診断表示」）  → `docs/history/3.3xx.md`
- 不採用理由を5分類で構造化＝ピン破りが研磨の最大の壁と判明（3.321.0, ユーザー指示「拒否理由を別々に構造化して記録する」）  → `docs/history/3.3xx.md`
- 制約行の無言除外を全族で可視化＋「休」不在の警告＋CI トリガー（3.320.0, 外部レビューの照合から3件）  → `docs/history/3.3xx.md`
- destroy-repair の marginal cost に canDo ガードを追加（3.319.0, 外部レビューの照合から1件）  → `docs/history/3.3xx.md`
- c42 の自己ペア／順序重複の除去と groupViol の HARD 統一（3.318.0, ユーザー明示指示）  → `docs/history/3.3xx.md`
- 分散指標の平準化2パスを撤去＝目的関数と指標が一致していなかった（3.317.0）  → `docs/history/3.3xx.md`
- 休の下限合計チェックが必ず誤警告していた（3.316.0, 診断を実データに当てて発見）  → `docs/history/3.3xx.md`
- C1厳密窓修復の探索を実採否と揃える＝厳密ピン・c3n を目的関数へ（3.315.0, ユーザー指示「次へ進める」）  → `docs/history/3.3xx.md`
- レビュー積み残し5件の解消（3.314.0, ユーザー指示「修正する」）  → `docs/history/3.3xx.md`
- free repair の締切確認と UI 指標の単位統一（3.313.0, 6本目のレビュー）  → `docs/history/3.3xx.md`
- C1合同LNSの「構造下限」からSOFT個人回数を除外（3.312.0, 5本目のレビューの新規項目 N-01）  → `docs/history/3.3xx.md`
- 禁止連続診断の偽 PINNED 修正ほか4件（3.311.0, 4本目のレビュー）  → `docs/history/3.3xx.md`
- PERSONAL_RSI の focus 選択を A/B して否決（3.310.1, ユーザー指示「あなたがABテストをする」）  → `docs/history/3.3xx.md`
- C1TemporalDp に状態数の安全弁（3.310.0, 3本目のレビューの新規項目）  → `docs/history/3.3xx.md`
- 外部レビュー2本の検証と確認できた4件の修正（3.309.0）  → `docs/history/3.3xx.md`
- 適応ポートフォリオの敵対フルコードトレース（3.308.2, ユーザー指示「敵対フルコードトレースする」）  → `docs/history/3.3xx.md`
- アップロード版の優秀な部分を部分融合（3.308.0, ユーザー指示「優秀な部分を部分融合する」）  → `docs/history/3.3xx.md`
- 3.307.0/3.308.0 の敵対検証＝自分の主張3件を反証して修正（3.308.1, ユーザー指示「敵対検証する」）  → `docs/history/3.3xx.md`
- 役割別worker秒のログ化＋秒予算再配分の否決（3.307.0, ユーザー指示「あなたが賢く考える」）  → `docs/history/3.3xx.md`
- 適応ポートフォリオの停滞脱出を既定OFFのトグルで温存（3.306.0, ユーザー選択「b」）  → `docs/history/3.3xx.md`
- staffPacked の重みドリフトと比較順序を修正（3.305.0, 外部提示コードを検証のうえ採用）  → `docs/history/3.3xx.md`
- 禁止連続の崩し範囲を設定トグルへ配線＋A/B実測（3.304.0, ユーザー指示「接続する。配線する。仮想的テストする。ABテストを仮想的にする」）  → `docs/history/3.3xx.md`
- 禁止連続をパターン全域で崩す＋Polish系の水平展開（3.303.0, ユーザー指示「C3nは前後日と当日も他の勤務シフトに変更できるようにアルゴリズムを賢く昇華する」「各制約のPolish系を相互に水平展開する」）  → `docs/history/3.3xx.md`
- 研磨の「不採用」に主因の族名を併記（3.302.0, ユーザー指示「ログ強化する」）  → `docs/history/3.3xx.md`
- 3.301.0 の論理的検証＝休の判定が旧実装で死んでいたことが判明（3.301.1）  → `docs/history/3.3xx.md`
- 目標（適切回数）の検算を設定画面へ＝オペレーターの思考誘導（3.301.0, ユーザー指示「オペレーターの思考を誘導してください」）  → `docs/history/3.3xx.md`
- アルゴリズム台帳の新設と未実施2件の実施（3.299.0 / 3.300.0, ユーザー提示の台帳案→「両方」）  → `docs/history/3.3xx.md`
- c3n 事前フィルタを PolishGate 経由で配線（3.298.0, ユーザー指示「配線する」）  → `docs/history/3.2xx.md`
- 壁になっている禁止の並びを名指しして緩める導線（3.297.0, ユーザー指示「1」＝3.296.0 の残り案(1)）  → `docs/history/3.2xx.md`
- c3n 事前フィルタを既定OFFへ（3.296.0, ユーザー指示「巡回交換の c3n フィルタを外す」）  → `docs/history/3.2xx.md`
- 境界 c3n の事前フィルタ＝構造的な不採用要因を両方とも0にする（3.295.0, ユーザー指示「(b) も入れる」）  → `docs/history/3.2xx.md`
- ピン保存交換＝ブロック巡回交換の最大の壁を除去（3.294.0, ユーザー指示「a」＝3.293.0 の次の手(a)）  → `docs/history/3.2xx.md`
- ブロック巡回交換の「採用0の理由」をログ化（3.293.0, ユーザー質問「採用ゼロの内訳を教えてください」）  → `docs/history/3.2xx.md`
- ブロック交換を可変長の巡回交換（3者・多者）へ一般化（3.292.0, ユーザー指示「三者交換、多者交換なども追加する」）  → `docs/history/3.2xx.md`
- ブロック交換の候補生成を「希望固定日は据え置き」へ緩和（3.291.0, ユーザー明示指示）  → `docs/history/3.2xx.md`
- 可変長ブロック交換（AdaptiveBlockSwap, 3.290.0, ユーザー提供パッチを検証のうえ統合）  → `docs/history/3.2xx.md`
- keep-best統一の取り残し修正＝UI層2経路・テスト不変条件・退避の原子性（3.289.0, 外部レビュー3件＋自己発見1件）  → `docs/history/3.2xx.md`
- 判断ログの3軸強化＋RSIラウンド行のスパム抑制（3.288.0, ユーザー指示「時間/回数/状態の3軸をログ強化する。ログスパム対応する」）  → `docs/history/3.2xx.md`
- keep-best比較順の統一＝第2キーを total→weightedScore へ（3.287.0, ユーザー指示「停滞に至るまでの改善の質を賢く高める」→AskUserQuestionで「isBetterをweighted優先化」を明示選択）  → `docs/history/3.2xx.md`
- 画面間冗長性の解消4件（3.286.0, ユーザー指示「各画面と各オブジェクトの一覧表を作成し、画面間の冗長性をシンプルにする」→「フルコードトレースしてフルコードレビューする」でD追加）  → `docs/history/3.2xx.md`
- 判断設計監査の改善3件（3.285.0, 全5画面監査の「改善して再テスト」項目→「マージする」）  → `docs/history/3.2xx.md`
- 外部リポジトリ全体レビューの検証と採用5件の実装（3.284.0, ユーザー「何%正しいか?」→「強化修正する」）  → `docs/history/3.2xx.md`
- 3.283.0実機検証＝5連リリース全機能の実効確認＋フェーズ行全滅の自己回帰を修正（3.283.1）  → `docs/history/3.2xx.md`
- ログ観測性の強化＋スパムログ対策（3.283.0, ユーザー指示「ログ解析出来ない箇所はログ強化する。スパムログ対策する」）  → `docs/history/3.2xx.md`
- 新領域のログ並列監査＝適応ポートフォリオ改善判定の恒真化ほか一括修正（3.282.0, ユーザー指示「新領域もログ解析する。不具合など修正する。コスト無視する」）  → `docs/history/3.2xx.md`
- 停滞脱出レビューで確定した2欠陥の修正＝c3n構造壁の動的床＋HF63エポック横断共有（3.281.0, ユーザー指示「停滞脱出は賢く適切かログ解析してコードレビューする」→「新しい不具合を修正しマージする」）  → `docs/history/3.2xx.md`
- 禁止連続(c3n)の「なぜ崩せないか」診断＝ForbiddenDiag新設（3.280.0, ユーザー指示「実装する、実装コスト無視する」）  → `docs/history/3.2xx.md`
- 3.279.0セルフレビュー指摘5件の後始末（3.279.1, ユーザー指示「コードレビューする」→「修正する」）  → `docs/history/3.2xx.md`
- 外部レビューC1-01〜C1-12の検証と修正（3.279.0, ユーザー指示「不具合など修正する」）  → `docs/history/3.2xx.md`
- 敵対監査で実証した2クラッシュ＋正しさ/実効性バグの一括修正（3.278.0, ユーザー指示「新しい論理的な不具合などを見つける」→「すべて修正する」）  → `docs/history/3.2xx.md`
- c1Deltaをload-bearing化=exact net c1 deltaへ格上げし候補順位付けへ接続（3.277.0, ユーザー選択「c1Deltaもload-bearing化」）  → `docs/history/3.2xx.md`
- index駆動C1修復オペレータ新設=screenCell/c1Deltaを実駆動する経路（3.276.0, ユーザー指示「接続する」→AskUserQuestionで「screenCellを新規オペレータへ」）  → `docs/history/3.2xx.md`
- C1研磨アーキテクチャを図どおりに寄せる=Index/Operators façade/Delta Prefilter を新設（3.275.0, ユーザー提示のパイプライン図→「賢く実装する。実装コスト無視する」）  → `docs/history/3.2xx.md`
- 3.273.0のA4/診断3件を敵対監査で確認・修正（3.274.0, 実機ログ4fca3273→ユーザー指示「敵対監査」）  → `docs/history/3.2xx.md`
- C1 Repair Analysis + 厳密窓修復（A1-A6, 3.273.0, ユーザー指示「A1からA6を実装する。コスト無視する」）  → `docs/history/3.2xx.md`
- Constraint IR + MUS＝矛盾の最小説明エンジン（3.272.0, v8構想第1段・ユーザー指示「あなたが賢く実装する。実装コスト無視する」）  → `docs/history/3.2xx.md`
- 実機ログの敵対解析＝共同LNS恒常飢餓の解消＋実行入口3種の穴を修正（3.271.0, ユーザー指示「ログ解析して新しい論理的な不具合などを見つける。敵対検証をする」）  → `docs/history/3.2xx.md`
- 新規論理不具合の並列監査＝3件を確認・修正（3.270.0, ユーザー指示「新しい論理的な不具合などを見直す」）  → `docs/history/3.2xx.md`
- 後処理タイミングログの「HF66」誤表示を修正（3.269.0, 実機ログ精読で発見）  → `docs/history/3.2xx.md`
- C1JointLnsPolish/EliteIntegrationPolishの2件を賢く改良（3.268.0、外部レビュー評価の続き＋ユーザー指示「賢くアルゴリズムをし直す」）  → `docs/history/3.2xx.md`
- destroyRepair系のsoft-aware修復にweekly/fairを統合（3.267.0、ユーザー指示「残作業と残不具合と残提案を最適化する。実装コスト無視する」）  → `docs/history/3.2xx.md`
- 適応的仮説ポートフォリオ＝8並列仮説が同一解へ収束する問題への対応（3.266.0、外部パッチ受領・検証のうえ手作業で統合）  → `docs/history/3.2xx.md`
- Joint LNS予算按分の折半を既定比按分へ訂正（3.265.0、ユーザー質問「予算配分は適切か?」への自己検証）  → `docs/history/3.2xx.md`
- 外部コードレビュー(C1JointLnsPolish/PersonalBalanceJointLnsPolish)の受領・検証・修正（3.264.0）  → `docs/history/3.2xx.md`
- 600秒改善ゼロの停滞を実データで解剖＝covU「玉突き」診断の楽観バイアスを修正（3.263.0, ユーザー指示「600秒で改善ゼロという停滞そのものを調査」「新たに深く網羅的に改善する」）  → `docs/history/3.2xx.md`
- 初期解生成のC1残差=真の構造的衝突と確認＋2b-3診断のfalse negativeを修正（3.262.0, ユーザー指示「初期解生成でC1違反をゼロにする」）  → `docs/history/3.2xx.md`
- 初期解生成が「既に充足済みの盤面」でno-opになっていた実バグを修正（3.261.0, ユーザー実機報告「初期解生成後にC1違反になる。初期解生成が何度も出来ない」）  → `docs/history/3.2xx.md`
- AptPolish/FairPolishの自己振替が1パスにつき1単位しか解消できなかった欠陥を修正（3.260.0, ユーザー指摘「大島が違反研磨で来てない」）  → `docs/history/3.2xx.md`
- 初期解生成のC1が個人上限(rangeHi)を無視していた欠陥を修正（3.259.0, ユーザー実機ログ提示「初期解生成のC1は適切か?」）  → `docs/history/3.2xx.md`
- 初期解生成＝C1複数規則の反映を検証（3.258.0, ユーザー指摘「C1は複数ある。初期解生成に反映できるか?」）  → `docs/history/3.2xx.md`
- 初期解生成(賢い版)＝SmartInitialScheduler新設＋専用ボタン（3.257.0, ユーザー指示「初期解生成のアルゴリズムを新たに賢く作る」）  → `docs/history/3.2xx.md`
- staffRange厳密ピン(lo==hi)保護＝exactPinRegression新設（3.256.0, ユーザー指示「厳密ピン保護rangeAvoidの実装」）  → `docs/history/3.2xx.md`
- C1JointLnsPolish・PersonalBalanceJointLnsPolish新設＝受領・実測検証のうえ最終研磨として追加（3.255.0）  → `docs/history/3.2xx.md`
- C1研磨5系統の整理＝C1TemporalFlowPolish新設でC1TemporalSwapPolish/BeamC1PolishV2を置換（3.254.0）  → `docs/history/3.2xx.md`
- c1(窓の要件)重み5→15（3.253.0, ユーザー明示数値指示）＋Free系リペア(covO/c41/c41s/c42/c42s)を実チェッカーによるkeep-best gateへ全面刷新  → `docs/history/3.2xx.md`
- 「大嶋と美幸の違反研磨は適切か?」への回答＝Free系リペア(covO/c41/c41s/c42/c42s)の欠陥を発見・全面修正（3.253.0）  → `docs/history/3.2xx.md`
- BeamC1PolishV2の停滞脱出=候補走査順のseed多様化（3.252.0, ユーザー指摘「停滞脱出しないのか?」）  → `docs/history/3.2xx.md`
- C1広域ビーム研磨=applyC1BeamPolish新設（3.251.0, 外部パッチ受領→重大な欠陥を発見・修正のうえ適用）  → `docs/history/3.2xx.md`
- C1協調ビーム研磨=BeamC1PolishV2新設（3.250.0, 外部パッチ受領・検証のうえ適用）  → `docs/history/3.2xx.md`
- 汎用玉突き結合フレームワーク新設＋c1/c3mn重み変更（3.249.0）  → `docs/history/3.2xx.md`
- c1(窓の要件)重み4→5・c3mn(回避の並び)重み12→15（3.249.0, ユーザー明示数値指示）  → `docs/history/3.2xx.md`
- RSI focus選択でweeklyの優先順位をaptより下げる（3.248.0, ユーザー明示指示）  → `docs/history/3.2xx.md`
- C1専用の時系列DP研磨を新設＝2箇所以上の同時移設でしか越えられない局所最適に対応（3.247.0, 外部パッチ受領・検証のうえ適用）  → `docs/history/3.2xx.md`
- 手Fを隣接日連動型へ拡張＝上條洋平のDﾃ3→2回で判明した穴を修正（3.246.0）  → `docs/history/3.2xx.md`
- RangePolishに柔軟日フロー(手F)を新設＝日別シフト多重集合も変えられる最小費用フロー（3.245.0, 外部パッチ受領・検証のうえ適用）  → `docs/history/3.2xx.md`
- RangePolishに日単位最小費用完全割当(手M)を新設＝任意長循環の玉突きに対応（3.244.0, 外部パッチ受領・検証のうえ適用）  → `docs/history/3.2xx.md`
- countViolationsのapt表示優先度をweight1.0扱いへ（3.243.0, ユーザー明示数値指示）  → `docs/history/3.2xx.md`
- staffRange上限違反の構造的判定＋代用要員提示（3.242.0）  → `docs/history/3.2xx.md`
- 専用freeオペレータの改善がdestroyRepairDayで相殺される順序バグを修正（3.241.0）  → `docs/history/3.2xx.md`
- RSI5ラウンド完全停滞の修正＝destroyRepairStaffの摂動過大を是正（3.240.0）  → `docs/history/3.2xx.md`
- RangePolishのペアスワップ新設＋maxViolatedFamily最終ラウンド枠の固定順バグ修正（3.239.0）  → `docs/history/3.2xx.md`
- C1研磨に「職員内シフト配置の全ペア網羅再配置」を追加＝手R3（3.238.0）  → `docs/history/3.2xx.md`
- 進捗の「残り時間」表示が繰り返しリセットされる不具合修正（3.237.0）  → `docs/history/3.2xx.md`
- C1Polish頭打ち理由の可視化＋休の適切回数チェックを実質的上限へ差替え（3.236.0）  → `docs/history/3.2xx.md`
- FairPolish・C3PatternPolish新設＋実機報告2件の修正（3.235.0）  → `docs/history/3.2xx.md`
- c42/c42s専用repair新設＝covO・c41,c41sと同型の穴を横展開（3.233.0）  → `docs/history/3.2xx.md`
- findCovUChainのmaxDepth既定を引き上げ＝depth5の壁を撤廃（3.232.0）  → `docs/history/3.2xx.md`
- ドッグフーディング4トピックの一括改善（3.228.0〜3.231.0）  → `docs/history/3.2xx.md`
- c1違反の職員×窓ルール別件数をログへ出力（3.227.0）  → `docs/history/3.2xx.md`
- covOの自動解消に隣接日調整を追加＝禁止連続で全候補が塞がる局面を突破（3.226.0）  → `docs/history/3.2xx.md`
- 仮説数の固定上限(5)を撤廃＝ワーカー設定まで仮説を増やす（3.225.0）  → `docs/history/3.2xx.md`
- 外部の並行/並列処理レビュー(9件)を実装（3.224.0）  → `docs/history/3.2xx.md`
- AptPolish新設＝適切回数(apt)専用の研磨パス（3.223.0）  → `docs/history/3.2xx.md`
- RangePolishの頭打ち理由をログ可視化＋制約編集(cons3mn等)の削除が反映されない実機バグ修正（3.222.0）  → `docs/history/3.2xx.md`
- ラウンド跨ぎで同一seed固定だった頭打ちを解消＝roundSeed新設（3.221.0, 「なぜゼロにならないのか」）  → `docs/history/3.2xx.md`
- 15日ブロック丸ごと2人交換研磨=BlockSwapPolish新設（3.220.0）  → `docs/history/3.2xx.md`
- 研磨ログに対象/残存職員名を追加（3.219.0）  → `docs/history/3.2xx.md`
- findCovUChain に rangeAvoid（新規range違反の後回し）を追加＝頭打ちの根本原因を修正（3.218.0）  → `docs/history/3.2xx.md`
- 停滞時間をログへ出力（3.217.0）  → `docs/history/3.2xx.md`
- 「他の制約は大丈夫か」監査→c3/c3m・c41/c41sへも玉突き連鎖を横展開（3.216.0）  → `docs/history/3.2xx.md`
- 玉突き連鎖(findCovUChain)をlow/high(個人回数)研磨へ横展開＝RangePolish新設（3.215.0）  → `docs/history/3.2xx.md`
- 玉突き連鎖(findCovUChain)をc3mn(回避,SOFT)研磨へ横展開＝C3mnPolish新設（3.214.0）  → `docs/history/3.2xx.md`
- 3.213.0の自己監査で発見した見落とし＝hard許容ゲートの旧スケール残置を修正（3.213.1）  → `docs/history/3.2xx.md`
- 外部レビュー再検証文書の8件を一括修正（3.213.0）  → `docs/history/3.2xx.md`
- 3.211.0の敵対的フルコードレビュー→主要修正の一括適用（3.212.0）  → `docs/history/3.2xx.md`
- 余剰ワーカーの実質的活用＝RSI/RSI++のSAチェーン数拡張＋ALNS多チェーン新設（3.211.0）  → `docs/history/3.2xx.md`
- countViolations(markCount)を重み優先へ統一（3.210.0, 「新領域も敵対検証する」の追加防御）  → `docs/history/3.2xx.md`
- 停滞脱出アルゴリズムのゼロベース敵対検証で発見したc41/c41sの専用repair欠如を修正（3.209.0）  → `docs/history/3.2xx.md`
- apt(適切回数)も同型の恒久的starvationを起こしていたことを確認・修正（3.208.0, 「他も検証する」）  → `docs/history/3.2xx.md`
- covO周期枠が典型的な短いRSIフェーズで空振りする実効性不足を修正（3.207.0）  → `docs/history/3.2xx.md`
- 同種のanchor選定シャドーイングをC3系研磨・回転研磨(C1Rotate/C3Rotate)にも横展開（3.206.0）  → `docs/history/3.2xx.md`
- C1研磨のanchor選定が重み優先シャドーイングで職員を取りこぼす実バグを修正（3.205.0）  → `docs/history/3.2xx.md`
- 人員過剰(covO)を探索フォーカスへ組み込み実際に解消する（3.204.0, 3.203.0診断の恒久対応）  → `docs/history/3.2xx.md`
- 人員過剰(covO)の「なぜ減らないか」診断を新設（3.203.0, ログ強化）  → `docs/history/3.2xx.md`
- docs/business-logic.md のドリフト修正（3.202.0, 「残不具合などを修正する」の一環）  → `docs/history/3.2xx.md`
- FixSuggester の改善提案リストが同一の盤面変化を複数回表示する不具合を修正（3.202.0）  → `docs/history/3.2xx.md`
- C1研磨・手Bの玉突き連鎖に c1Pref 優先付けを追加（3.201.0、外部検証の追認）  → `docs/history/3.2xx.md`
- C1研磨アルゴリズムの再設計＝回数保存移設プリミティブの追加（3.200.0）  → `docs/history/3.2xx.md`
- ネイティブSAチャンクの-1セル未対応＝実データで番兵発火（高速化の根本修正, 3.199.0）  → `docs/history/3.1xx.md`
- 交互最適化（Alternating Optimization）をソフト制約研磨に追加（3.198.0）  → `docs/history/3.1xx.md`
- weekly（曜日平準化）研磨の穴を長方形交換で埋める（3.197.0）  → `docs/history/3.1xx.md`
- 操作ログのフェーズ遷移・必須改善ログが巻き戻り時計で欠落するバグ修正（3.196.0）  → `docs/history/3.1xx.md`
- ホーム〜設定タブの冗長性一巡監査（3.195.0）  → `docs/history/3.1xx.md`
- 勤務表タブ「不一致だけ抽出」の撤去＋希望バッジのcanDo不整合修正（3.194.0）  → `docs/history/3.1xx.md`
- 勤務表タブの「職員別カレンダー」撤去（3.193.0）  → `docs/history/3.1xx.md`
- 設定画面のテキストアート再現＋「おまかせ」解決先の表示（3.192.0）  → `docs/history/3.1xx.md`
- 設定タブの用語重複解消：「並列ワーカー」と「並列(複数案)」（3.191.0）  → `docs/history/3.1xx.md`
- 同種の再構成バグの横展開（3.190.0, ユーザー指示「他の画面も再検索してください」）  → `docs/history/3.1xx.md`
- 「③ 回数（1人あたり）」+/- が同一画面で反映されない実機バグ修正（3.189.0）  → `docs/history/3.1xx.md`
- 設定タブ「最適化設定」のオプション集約（3.188.0）  → `docs/history/3.1xx.md`
- 希望シフト登録も「4つの情報」に集約（3.187.0）  → `docs/history/3.1xx.md`
- 必要人数設定を「4つの情報」に集約（3.186.0）  → `docs/history/3.1xx.md`
- HARD残でもSOFTをRSI focusできるようにする（3.183.0, 実データ検証で根本特定）  → `docs/history/3.1xx.md`
- 下流→上流ディープリンク「設定で直す」（3.182.0, 3.180.0 タスク2の完了）  → `docs/history/3.1xx.md`
- Android 16並行/並列監査＋16KBページ対応（3.181.0）  → `docs/history/3.1xx.md`
- 希望/必要人数カレンダーのレイアウト刷新＋未設定導線（3.180.0）  → `docs/history/3.1xx.md`
- バックログB/C の消化（番兵対称化・停滞時計・デッドコード除去, 3.179.0）  → `docs/history/3.1xx.md`
- ネイティブパリティのCI自動化（backlog#6 解消, 3.178.0）  → `docs/history/3.1xx.md`
- メインスレッド負荷の削減=表示解析の並列化＋起動I/Oの並行化（3.176.0）  → `docs/history/3.1xx.md`
- allowedShiftsFor をキャッシュ経由に統一（メインスレッド負荷削減, 3.175.0）  → `docs/history/3.1xx.md`
- SaChunk の c3 窓マッチもビット化（3.174.0, 3.172.0の続き）  → `docs/history/3.1xx.md`
- 版数の重複ラベルを全数走査＝真の衝突は 3.173.0 の1件・注記で正史化（3.426.0）  → `docs/history/3.3xx.md`
- CoverageDiagnosis の need2 単独定義セル見落とし修正（3.173.0）  → `docs/history/3.1xx.md`
- Android 17 会話バブル対応（2026-07-15・PR#27。※当時の版数ラベル「3.173.0」は重複）  → `docs/history/3.1xx.md`
- SaChunk のビット化評価（c1窓・c41/c42/c41s/c42s の O(1) 化, 3.172.0）  → `docs/history/3.1xx.md`
- ネイティブ照合トグル＋監査#7 SIGSEGV修正（3.171.0）  → `docs/history/3.1xx.md`
- weekly/fairも同じ理由でRSI探索focusに追加（3.170.0, 「apt以外は大丈夫か」への回答）  → `docs/history/3.1xx.md`
- apt(適切回数)をRSI探索focusに追加（3.169.0, 「公平化のズレ」実機report対応）  → `docs/history/3.1xx.md`
- 希望シフトカレンダーのインタラクティブ化（3.168.0）  → `docs/history/3.1xx.md`
- 必要人数カレンダーのインタラクティブ化（3.167.0）  → `docs/history/3.1xx.md`
- 必要人数カレンダー＋希望シフトの既存登録可視化（3.166.0）  → `docs/history/3.1xx.md`
- 「Dﾃ-Dﾃ」仮説の検証＝隣接日調整の全候補探索を実データで確認（3.165.0）  → `docs/history/3.1xx.md`
- 希望シフト登録の見つけやすさ改善（3.164.0）  → `docs/history/3.1xx.md`
- covU多人数連鎖(E11)を禁止連続の回避=隣接日調整へ拡張（3.163.0）  → `docs/history/3.1xx.md`
- 対応OSをAndroid 16以降のみに変更（3.162.0）  → `docs/history/3.1xx.md`
- 未レビュー領域の再監査（3.161.0）  → `docs/history/3.1xx.md`
- Gradle 9 移行（3.160.0）  → `docs/history/3.1xx.md`
- 敵対的コードレビューで判明した2件の修正（3.159.0）  → `docs/history/3.1xx.md`
- C1研磨への多人数ブロック移動の反映（3.158.0）  → `docs/history/3.1xx.md`
- 禁止連続の枝刈りを任意長へ一般化（三連・五連など, 3.157.0）  → `docs/history/3.1xx.md`
- 人員不足の「なぜ埋まらないか」内訳（CoverageDiag 拡張, 3.156.0）  → `docs/history/3.1xx.md`
- 多人数ブロック移動（勤務→勤務連鎖, 3.155.0）  → `docs/history/3.1xx.md`
- 未レビュー領域の精読（3.84.0, 並列監査で一巡）  → `docs/history/3.0xx.md`
- 回数設定画面（スマホ最適化・シフト中心, 2.60.0 Stage1-3）  → `docs/history/2.x.md`
- 事前診断（実行前の過拘束検知, 2.47.0 完了）  → `docs/history/2.x.md`
- 2.41.0 時点のスナップショット（当時の記録・現在の版ではない）  → `docs/history/2.x.md`
- native/JNI 層への外部レビュー2本の検証（コード変更なし, ユーザー提示「フルコードレビュー」「深いレビュー」）  → `docs/history/3.4xx.md`
- 後処理研磨の「Range 先頭化」を3データセット A/B で否決（敵対検証ケース6の続き・実データ受領）  → `docs/history/3.3xx.md`
