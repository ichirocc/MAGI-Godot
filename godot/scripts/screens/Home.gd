extends "res://scripts/screens/base_screen.gd"
## ホーム画面: 新規作成/JSON読込(JSONタブへ)/生成/最適化/調整/停止/保存。
## ボタンの有効/無効は render() で running/loaded に合わせる（実行中の二重起動や未読込での操作を UI で止める）。

var _buttons: Dictionary = {}  # 名前 -> Button

func _ready() -> void:
	tab_key = "home"
	super._ready()

func build_actions(bar: HBoxContainer) -> void:
	_btn(bar, "new", "新規作成", func(): run_op("initBlankState"))
	_btn(bar, "json", "JSON読込へ", func(): Nav.go("json"))
	_btn(bar, "generate", "自動生成", func(): run_op("generateSmartInitial"))
	_btn(bar, "optimize", "最適化", func(): run_op("runV6FullOptimize"))
	_btn(bar, "polish", "調整(研磨)", func(): run_op("runSoftPolish"))
	_btn(bar, "background", "バックグラウンド実行", func(): run_op("runInBackground"))
	_btn(bar, "stop", "停止", func(): run_op("stop"))
	_btn(bar, "save", "保存", func(): run_op("saveNow"))

func _btn(bar: HBoxContainer, key: String, text: String, cb: Callable) -> void:
	var b := Button.new()
	b.text = text
	b.pressed.connect(cb)
	bar.add_child(b)
	_buttons[key] = b

func render(state: Dictionary) -> void:
	var running: bool = bool(state.get("running", false))
	var loaded: bool = bool(state.get("loaded", false))
	var lines := []
	lines.append("[b]MAGI ShiftOptimizer[/b]")
	lines.append("読込済み: %s" % loaded)
	lines.append("職員 %d / 日数 %d / シフト種 %d / 群 %d" % [
		state.get("staff", 0), state.get("days", 0), state.get("shifts", 0), state.get("groups", 0)])
	lines.append("実行中: %s / エンジン実行済: %s" % [running, state.get("engineRan", false)])
	lines.append("HARD違反 %d / SOFT重み付き %.1f" % [state.get("bestHard", 0), state.get("weightedScore", 0.0)])
	if state.get("runSummary", null) != null:
		lines.append("直近の変更: %s" % bb(state["runSummary"]))
	if state.get("message", null) != null:
		var color = "red" if state.get("messageIsError", false) else "green"
		lines.append("[color=%s]%s[/color]" % [color, bb(state["message"])])
	lines.append("保存状態: %s" % state.get("saveState", ""))
	$VBox/Content.text = "\n".join(lines)
	if _buttons.is_empty():
		return
	for key in ["generate", "optimize", "polish", "background", "save"]:
		_buttons[key].disabled = running or not loaded
	_buttons["new"].disabled = running
	_buttons["stop"].disabled = not running
