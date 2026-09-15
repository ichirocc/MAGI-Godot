extends "res://scripts/screens/base_screen.gd"
## 設定: 並列数/予算秒/方式/ネイティブ加速・照合/ソフト研磨。値の意味・重みは変えない（既存Composeと同じ制約）。
## 実行フラグ系（setBlockSwapC3nFilter 等）は UiState に現在値が無く同期表示できないため、この画面では扱わない。

const ALGOS := ["AUTO", "V5", "ALNS", "RSI", "RSI_PLUS", "PORTFOLIO"]

var _accel: CheckBox
var _parity: CheckBox
var _polish: CheckBox
var _workers: SpinBox
var _budget: SpinBox
var _algo: OptionButton
var _syncing := false  # render() で state を部品へ写す間は signal を dispatch に流さない

func _ready() -> void:
	tab_key = "settings"
	super._ready()

func build_actions(bar: HBoxContainer) -> void:
	_accel = _check(bar, "ネイティブ加速", "setNativeAccel")
	_parity = _check(bar, "パリティ照合", "setNativeParity")
	_polish = _check(bar, "ソフト研磨", "setSoftPolish")

	var row := HBoxContainer.new()
	row.add_child(_lbl("並列数"))
	_workers = SpinBox.new(); _workers.min_value = 1; _workers.max_value = 8; _workers.step = 1
	_workers.value_changed.connect(func(v):
		if not _syncing:
			run_op("setWorkers", {"n": int(v)}))
	row.add_child(_workers)
	row.add_child(_lbl("予算(秒)"))
	_budget = SpinBox.new(); _budget.min_value = 10; _budget.max_value = 3600; _budget.step = 10
	_budget.value_changed.connect(func(v):
		if not _syncing:
			run_op("setBudget", {"sec": int(v)}))
	row.add_child(_budget)
	row.add_child(_lbl("方式"))
	_algo = OptionButton.new()
	for i in range(ALGOS.size()):
		_algo.add_item(ALGOS[i], i)
	_algo.item_selected.connect(func(idx):
		if not _syncing:
			run_op("setV6Algorithm", {"algorithm": ALGOS[idx]}))
	row.add_child(_algo)
	$VBox.add_child(row)
	$VBox.move_child(row, $VBox.get_children().find(bar) + 1)

func _check(bar: HBoxContainer, text: String, op: String) -> CheckBox:
	var c := CheckBox.new(); c.text = text
	c.toggled.connect(func(on):
		if not _syncing:
			run_op(op, {"on": on}))
	bar.add_child(c)
	return c

func _lbl(t: String) -> Label:
	var l := Label.new(); l.text = t
	return l

func render(state: Dictionary) -> void:
	var lines := ["[b]設定[/b]"]
	lines.append("並列数: %d / 予算: %d秒" % [state.get("workers", 0), state.get("budgetSec", 0)])
	lines.append("ネイティブ加速: %s / パリティ照合: %s" % [state.get("nativeAccel", false), state.get("nativeParity", false)])
	lines.append("ソフト研磨: %s / 方式: %s" % [state.get("softPolish", false), state.get("v6Algorithm", "")])
	lines.append("保存状態: %s" % state.get("saveState", ""))
	$VBox/Content.text = "\n".join(lines)
	if _accel == null:
		return
	_syncing = true
	_accel.button_pressed = bool(state.get("nativeAccel", false))
	_parity.button_pressed = bool(state.get("nativeParity", false))
	_polish.button_pressed = bool(state.get("softPolish", false))
	_workers.value = int(state.get("workers", 1))
	_budget.value = int(state.get("budgetSec", 300))
	var idx := ALGOS.find(str(state.get("v6Algorithm", "AUTO")))
	if idx >= 0:
		_algo.select(idx)
	_syncing = false
