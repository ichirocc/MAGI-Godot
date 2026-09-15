extends "res://scripts/screens/base_screen.gd"
## 設定: 並列数/予算秒/ネイティブ加速・照合/研磨系トグル/方式。値はCLAUDE.md方針どおり変更しない
## （UIから重みそのものはいじれない＝既存Composeと同じ制約）。

func _ready() -> void:
	tab_key = "settings"
	super._ready()

func build_actions(bar: HBoxContainer) -> void:
	var accel := CheckBox.new(); accel.text = "ネイティブ加速"
	accel.toggled.connect(func(on): run_op("setNativeAccel", {"on": on}))
	bar.add_child(accel)
	var parity := CheckBox.new(); parity.text = "パリティ照合"
	parity.toggled.connect(func(on): run_op("setNativeParity", {"on": on}))
	bar.add_child(parity)
	var polish := CheckBox.new(); polish.text = "ソフト研磨"
	polish.toggled.connect(func(on): run_op("setSoftPolish", {"on": on}))
	bar.add_child(polish)

func render(state: Dictionary) -> void:
	var lines := ["[b]設定[/b]"]
	lines.append("並列数: %d / 予算: %d秒" % [state.get("workers", 0), state.get("budgetSec", 0)])
	lines.append("ネイティブ加速: %s / パリティ照合: %s" % [state.get("nativeAccel", false), state.get("nativeParity", false)])
	lines.append("ソフト研磨: %s / 方式: %s" % [state.get("softPolish", false), state.get("v6Algorithm", "")])
	lines.append("保存状態: %s" % state.get("saveState", ""))
	$VBox/Content.text = "\n".join(lines)
