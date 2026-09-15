# 全画面モックアップ（3.483.0 時点・静的）

ユーザー指示「/design すべて」（2026-09-04）で起こした、Android 版の全7画面＋表紙の静的モックアップ。
Claude Design のキャンバス（アートボード＝`*.dc.html`、配置＝`canvas.json`）の**作業ファイル**をそのまま置く。
公開キャンバス: https://claude.ai/code/artifact/2dfa1488-bed1-4891-9a3b-0a8ddcc0ffd6

| ファイル | 画面 | 状態 |
|---|---|---|
| `Main.dc.html` | 表紙（この画面集について） | — |
| `Home.dc.html` | ホーム | 結果あり・必須違反2（人員不足1＋禁止の並び1） |
| `Schedule.dc.html` | 勤務表 | 結果あり |
| `Analysis.dc.html` | 分析 | 結果あり |
| `Settings.dc.html` | 設定 | 未計算 |
| `EditMonthly.dc.html` | 編集／月次条件 | 未計算 |
| `EditStaff.dc.html` | 編集／職員管理 | 未計算 |
| `EditMaster.dc.html` | 編集／年間マスター（①展開） | 未計算 |

- 幅 390px のスマホ枠。高さは Chromium 実測＋6% の余裕（`canvas.json` の `h`）。
- 配色・字面・角丸・ボタン高さは `MainActivity.MagiTheme`（UD固定 mode 3）と `MagiTokens.kt` の実値。
  文言と並びは 3.483.0 の実装文字列から写した（`docs/screen_inventory_textart.md` の所見20件は反映済み）。
- 職員名はサンプル。シフト記号と色はテストデータ（`golden_state.json`）のもの。
- 各 `.dc.html` は `<script src="./support.js">` をキャンバス側の実行時に差し替える形式＝単体で開くと
  素の HTML として見えるが、テンプレート機能は使っていないので見た目はそのまま読める。
- 更新のしかた: この作業ファイルを編集 → `/design` のヘルパーで再構成 → 上の URL へ再保存。
