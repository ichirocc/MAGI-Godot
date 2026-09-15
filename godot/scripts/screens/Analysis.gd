extends "res://scripts/screens/base_screen.gd"
## 分析: 内訳・修正提案・代替案・操作履歴。修正案の検索/適用、設定ミスの適用、代替案の適用に対応。
## 候補の本文は UiState JSON に無く件数だけ（fixSuggestionCount/settingIssueCount）＝index 指定で適用する。

var _apply_btn: Button
var _fix_btn: Button
var _sug_idx: SpinBox
var _issue_idx: SpinBox
var _alt_box: VBoxContainer

func _ready() -> void:
	tab_key = "analysis"
	super._ready()

func build_actions(bar: HBoxContainer) -> void:
	var b := Button.new(); b.text = "改善提案を探索"
	b.pressed.connect(func(): run_op("findFixSuggestions"))
	bar.add_child(b)

	_sug_idx = SpinBox.new(); _sug_idx.min_value = 0; _sug_idx.max_value = 0
	bar.add_child(_sug_idx)
	_apply_btn = Button.new(); _apply_btn.text = "提案を適用"; _apply_btn.disabled = true
	_apply_btn.pressed.connect(func(): run_op("applyFixSuggestion", {"suggestionIndex": int(_sug_idx.value)}))
	bar.add_child(_apply_btn)

	_issue_idx = SpinBox.new(); _issue_idx.min_value = 0; _issue_idx.max_value = 0
	bar.add_child(_issue_idx)
	_fix_btn = Button.new(); _fix_btn.text = "設定ミスを直す"; _fix_btn.disabled = true
	_fix_btn.pressed.connect(func(): run_op("applySettingFix", {"issueIndex": int(_issue_idx.value)}))
	bar.add_child(_fix_btn)

	_alt_box = VBoxContainer.new()
	$VBox.add_child(_alt_box)
	$VBox.move_child(_alt_box, $VBox.get_children().find($VBox/Content) + 1)

func render(state: Dictionary) -> void:
	var lines := ["[b]分析[/b]"]
	lines.append("違反総数 %d / weightedScore %.1f" % [state.get("totalViolations", 0), state.get("weightedScore", 0.0)])
	var sug_count: int = int(state.get("fixSuggestionCount", 0))
	var issue_count: int = int(state.get("settingIssueCount", 0))
	lines.append("改善提案候補数: %d / 設定ミス件数: %d" % [sug_count, issue_count])
	lines.append("")
	lines.append("[u]操作履歴[/u]")
	for op in state.get("opLog", []):
		lines.append("  " + bb(op))
	$VBox/Content.text = "\n".join(lines)
	if _apply_btn == null:
		return
	_sug_idx.max_value = max(0, sug_count - 1)
	_apply_btn.disabled = sug_count == 0
	_issue_idx.max_value = max(0, issue_count - 1)
	_fix_btn.disabled = issue_count == 0

	for c in _alt_box.get_children():
		c.queue_free()
	var alts: Array = state.get("alternatives", [])
	if not alts.is_empty():
		var head := Label.new(); head.text = "他の案（タップで即時に採用。取消/保存タブで戻せる）"
		_alt_box.add_child(head)
	for i in range(alts.size()):
		var btn := Button.new()
		btn.text = "案%d を採用: %s" % [i + 1, str(alts[i])]
		btn.pressed.connect(func(): run_op("applyAlternative", {"index": i}))
		_alt_box.add_child(btn)
