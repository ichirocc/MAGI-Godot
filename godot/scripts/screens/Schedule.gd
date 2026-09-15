extends "res://scripts/screens/base_screen.gd"
## 勤務表画面: 両方向スクロールのグリッド。セルタップでシフト選択→setCell。
## [未検証] 実機でのタッチ操作感・大規模盤面(30名x31日)でのスクロール性能はエディタでは確認できない。
## 描画は JSON の形状（行長の不揃い・添字範囲外）に対して防御的に行い、不正セルは無効ボタンで表す。

var _grid: GridContainer

func _ready() -> void:
	tab_key = "schedule"
	super._ready()

func build_actions(_bar: HBoxContainer) -> void:
	var scroll := ScrollContainer.new()
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_AUTO
	scroll.vertical_scroll_mode = ScrollContainer.SCROLL_MODE_AUTO
	_grid = GridContainer.new()
	scroll.add_child(_grid)
	# Content(RichTextLabel)の下にグリッドを追加する。
	$VBox.add_child(scroll)
	$VBox.move_child(scroll, $VBox.get_children().find($VBox/Content) + 1)

func _days_of(state: Dictionary, schedule: Array) -> int:
	var days: int = int(state.get("days", 0))
	if days <= 0:
		for row in schedule:
			if row is Array:
				days = max(days, row.size())
	return days

func render(state: Dictionary) -> void:
	var schedule: Array = state.get("schedule", [])
	var names: Array = state.get("staffNames", [])
	var symbols: Array = state.get("shiftSymbols", [])
	var colors: Array = state.get("shiftColorHex", [])
	var days := _days_of(state, schedule)
	$VBox/Content.text = "[b]勤務表[/b]  職員%d名 x %d日" % [schedule.size(), days]
	if _grid == null:
		return
	for c in _grid.get_children():
		c.queue_free()
	_grid.columns = days + 1
	_grid.add_child(Label.new())  # 左上の空セル
	for j in range(days):
		var h := Label.new(); h.text = str(j + 1); _grid.add_child(h)
	for i in range(schedule.size()):
		var nameLbl := Label.new()
		nameLbl.text = (str(names[i]) if i < names.size() else str(i))
		_grid.add_child(nameLbl)
		var row: Array = schedule[i] if schedule[i] is Array else []
		for j in range(days):
			var btn := Button.new()
			if j >= row.size():
				btn.text = "－"; btn.disabled = true
				_grid.add_child(btn)
				continue
			var shiftIdx: int = int(row[j])
			btn.text = (str(symbols[shiftIdx]) if shiftIdx >= 0 and shiftIdx < symbols.size() else "?")
			if shiftIdx >= 0 and shiftIdx < colors.size() and str(colors[shiftIdx]) != "":
				btn.modulate = Color(str(colors[shiftIdx]))
			btn.pressed.connect(_on_cell_tap.bind(i, j))
			_grid.add_child(btn)

func _on_cell_tap(i: int, j: int) -> void:
	# [簡易] シフト選択ダイアログの代わりに次のシフトへ巡回させる（実機用ピッカーは未実装）。
	var state := MagiApi.state()
	var symbols: Array = state.get("shiftSymbols", [])
	if symbols.is_empty():
		return
	var schedule: Array = state.get("schedule", [])
	if i >= schedule.size() or not (schedule[i] is Array) or j >= schedule[i].size():
		render(state)  # 表示が古い＝再描画して合わせる
		return
	var cur: int = int(schedule[i][j])
	var next: int = (cur + 1) % symbols.size()
	run_op("setCell", {"i": i, "j": j, "shift": next})
