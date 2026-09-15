extends "res://scripts/screens/base_screen.gd"
## 分析: 内訳・修正提案・代替案・操作履歴。修正案の検索/適用、設定ミスの適用は実装済み。

func _ready() -> void:
	tab_key = "analysis"
	super._ready()

func build_actions(bar: HBoxContainer) -> void:
	var b := Button.new(); b.text = "改善提案を探索"
	b.pressed.connect(func(): run_op("findFixSuggestions"))
	bar.add_child(b)
	var b2 := Button.new(); b2.text = "提案#0を適用"
	b2.pressed.connect(func(): run_op("applyFixSuggestion", {"suggestionIndex": 0}))
	bar.add_child(b2)
	var b3 := Button.new(); b3.text = "設定ミス#0を直す"
	b3.pressed.connect(func(): run_op("applySettingFix", {"issueIndex": 0}))
	bar.add_child(b3)

func render(state: Dictionary) -> void:
	var lines := ["[b]分析[/b]"]
	lines.append("違反総数 %d / weightedScore %.1f" % [state.get("totalViolations", 0), state.get("weightedScore", 0.0)])
	lines.append("改善提案候補数: %d / 設定ミス件数: %d" % [state.get("fixSuggestionCount", 0), state.get("settingIssueCount", 0)])
	lines.append("")
	lines.append("[u]他の案[/u]")
	for alt in state.get("alternatives", []):
		lines.append("  " + str(alt))
	lines.append("")
	lines.append("[u]操作履歴[/u]")
	for op in state.get("opLog", []):
		lines.append("  " + str(op))
	$VBox/Content.text = "\n".join(lines)
