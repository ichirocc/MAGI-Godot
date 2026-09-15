extends "res://scripts/screens/base_screen.gd"
## ホーム画面: 新規作成/JSON読込/生成/最適化/調整/停止/書出（保存はUndoExport画面にも導線あり）。

func _ready() -> void:
	tab_key = "home"
	super._ready()

func build_actions(bar: HBoxContainer) -> void:
	_btn(bar, "新規作成", func(): run_op("initBlankState"))
	_btn(bar, "自動生成", func(): run_op("generateSmartInitial"))
	_btn(bar, "最適化", func(): run_op("runV6FullOptimize"))
	_btn(bar, "調整(研磨)", func(): run_op("runSoftPolish"))
	_btn(bar, "バックグラウンド実行", func(): run_op("runInBackground"))
	_btn(bar, "停止", func(): run_op("stop"))
	_btn(bar, "保存", func(): run_op("saveNow"))

func _btn(bar: HBoxContainer, text: String, cb: Callable) -> void:
	var b := Button.new()
	b.text = text
	b.pressed.connect(cb)
	bar.add_child(b)

func render(state: Dictionary) -> void:
	var lines := []
	lines.append("[b]MAGI ShiftOptimizer[/b]")
	lines.append("読込済み: %s" % state.get("loaded", false))
	lines.append("職員 %d / 日数 %d / シフト種 %d / 群 %d" % [
		state.get("staff", 0), state.get("days", 0), state.get("shifts", 0), state.get("groups", 0)])
	lines.append("実行中: %s / エンジン実行済: %s" % [state.get("running", false), state.get("engineRan", false)])
	lines.append("HARD違反 %d / SOFT重み付き %.1f" % [state.get("bestHard", 0), state.get("weightedScore", 0.0)])
	if state.get("runSummary", null) != null:
		lines.append("直近の変更: %s" % state["runSummary"])
	if state.get("message", null) != null:
		var color = "red" if state.get("messageIsError", false) else "green"
		lines.append("[color=%s]%s[/color]" % [color, state["message"]])
	lines.append("保存状態: %s" % state.get("saveState", ""))
	$VBox/Content.text = "\n".join(lines)
