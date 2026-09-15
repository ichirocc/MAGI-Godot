extends "res://scripts/screens/base_screen.gd"
## 職員・シフト・群 管理: 追加・編集・削除・並び替え。
## 片手一本指方針（CLAUDE.md）によりドラッグは使わず、上下移動ボタンで並び替える
## （3.515.6/3.530.0のドラッグ許可例外＝並び替えの代替手段としてボタンを採用）。

var _staff_box: VBoxContainer
var _shift_box: VBoxContainer
var _group_box: VBoxContainer
var _staff_name_new: LineEdit
var _staff_group_new: OptionButton
var _shift_name_new: LineEdit
var _shift_kigou_new: LineEdit
var _shift_need1_new: LineEdit
var _shift_need2_new: LineEdit
var _group_name_new: LineEdit
var _group_kigou_new: LineEdit

func _ready() -> void:
	tab_key = "staff"
	super._ready()

func build_actions(_bar: HBoxContainer) -> void:
	var scroll := ScrollContainer.new()
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	scroll.vertical_scroll_mode = ScrollContainer.SCROLL_MODE_AUTO
	var root := VBoxContainer.new()
	scroll.add_child(root)
	$VBox.add_child(scroll)
	$VBox.move_child(scroll, $VBox.get_children().find($VBox/Content) + 1)

	root.add_child(_section_label("職員"))
	_staff_box = VBoxContainer.new(); root.add_child(_staff_box)
	var staff_add := HBoxContainer.new()
	_staff_name_new = LineEdit.new(); _staff_name_new.placeholder_text = "氏名"; staff_add.add_child(_staff_name_new)
	_staff_group_new = OptionButton.new(); staff_add.add_child(_staff_group_new)
	var staff_add_btn := Button.new(); staff_add_btn.text = "職員を追加"
	# 入力欄を消すのは dispatch が通ったときだけ（拒否された値を打ち直せるように残す）。
	staff_add_btn.pressed.connect(func():
		if run_op("ws1AddStaff", {"name": _staff_name_new.text, "groupIdx": max(_staff_group_new.get_selected_id(), 0)}):
			_staff_name_new.text = "")
	staff_add.add_child(staff_add_btn)
	root.add_child(staff_add)

	root.add_child(_section_label("シフト種"))
	_shift_box = VBoxContainer.new(); root.add_child(_shift_box)
	var shift_add := HBoxContainer.new()
	_shift_name_new = LineEdit.new(); _shift_name_new.placeholder_text = "名称"; shift_add.add_child(_shift_name_new)
	_shift_kigou_new = LineEdit.new(); _shift_kigou_new.placeholder_text = "記号"; shift_add.add_child(_shift_kigou_new)
	_shift_need1_new = LineEdit.new(); _shift_need1_new.placeholder_text = "最低人数"; shift_add.add_child(_shift_need1_new)
	_shift_need2_new = LineEdit.new(); _shift_need2_new.placeholder_text = "上限人数"; shift_add.add_child(_shift_need2_new)
	var shift_add_btn := Button.new(); shift_add_btn.text = "シフトを追加"
	shift_add_btn.pressed.connect(func():
		if run_op("ws1AddShift", {"name": _shift_name_new.text, "kigou": _shift_kigou_new.text, "need1": _shift_need1_new.text, "need2": _shift_need2_new.text}):
			_shift_name_new.text = ""; _shift_kigou_new.text = ""; _shift_need1_new.text = ""; _shift_need2_new.text = "")
	shift_add.add_child(shift_add_btn)
	root.add_child(shift_add)

	root.add_child(_section_label("グループ"))
	_group_box = VBoxContainer.new(); root.add_child(_group_box)
	var group_add := HBoxContainer.new()
	_group_name_new = LineEdit.new(); _group_name_new.placeholder_text = "名称"; group_add.add_child(_group_name_new)
	_group_kigou_new = LineEdit.new(); _group_kigou_new.placeholder_text = "記号"; group_add.add_child(_group_kigou_new)
	var group_add_btn := Button.new(); group_add_btn.text = "グループを追加"
	group_add_btn.pressed.connect(func():
		if run_op("ws1AddGroup", {"name": _group_name_new.text, "kigou": _group_kigou_new.text}):
			_group_name_new.text = ""; _group_kigou_new.text = "")
	group_add.add_child(group_add_btn)
	root.add_child(group_add)

func _section_label(t: String) -> Label:
	var l := Label.new(); l.text = t
	return l

func render(state: Dictionary) -> void:
	var structure: Dictionary = state.get("structure", {})
	var staff: Array = structure.get("staff", [])
	var shifts: Array = structure.get("shifts", [])
	var groups: Array = structure.get("groups", [])
	$VBox/Content.text = "[b]職員 / シフト / グループ[/b]（職員%d名・シフト%d種・グループ%d）" % [staff.size(), shifts.size(), groups.size()]
	if _staff_box == null:
		return  # build_actions未実行（初回render前）

	_staff_group_new.clear()
	for g in range(groups.size()):
		_staff_group_new.add_item(str(groups[g].get("name", g)), g)

	for c in _staff_box.get_children(): c.queue_free()
	for i in range(staff.size()):
		_staff_box.add_child(_staff_row(i, staff[i], groups, staff.size()))

	for c in _shift_box.get_children(): c.queue_free()
	for k in range(shifts.size()):
		_shift_box.add_child(_shift_row(k, shifts[k], shifts.size()))

	for c in _group_box.get_children(): c.queue_free()
	for g in range(groups.size()):
		_group_box.add_child(_group_row(g, groups[g], groups.size()))

func _move_buttons(idx: int, count: int, op: String) -> Control:
	var box := HBoxContainer.new()
	var up := Button.new(); up.text = "↑"; up.disabled = (idx == 0)
	up.pressed.connect(func(): run_op(op, {"from": idx, "to": idx - 1}))
	box.add_child(up)
	var down := Button.new(); down.text = "↓"; down.disabled = (idx == count - 1)
	down.pressed.connect(func(): run_op(op, {"from": idx, "to": idx + 1}))
	box.add_child(down)
	return box

func _staff_row(i: int, s: Dictionary, groups: Array, count: int) -> Control:
	var row := HBoxContainer.new()
	var name_edit := LineEdit.new(); name_edit.text = str(s.get("name", "")); row.add_child(name_edit)
	var group_opt := OptionButton.new()
	for g in range(groups.size()):
		group_opt.add_item(str(groups[g].get("name", g)), g)
	group_opt.select(int(s.get("groupIdx", 0)))
	row.add_child(group_opt)
	var save := Button.new(); save.text = "保存"
	save.pressed.connect(func(): run_op("ws1EditStaff", {"i": i, "name": name_edit.text, "groupIdx": group_opt.get_selected_id()}))
	row.add_child(save)
	row.add_child(_move_buttons(i, count, "ws1MoveStaffTo"))
	var del := Button.new(); del.text = "削除"
	del.pressed.connect(func(): run_op("ws1RemoveStaff", {"i": i}))
	row.add_child(del)
	return row

func _shift_row(k: int, sh: Dictionary, count: int) -> Control:
	var row := HBoxContainer.new()
	var name_edit := LineEdit.new(); name_edit.text = str(sh.get("name", "")); row.add_child(name_edit)
	var kigou_edit := LineEdit.new(); kigou_edit.text = str(sh.get("kigou", "")); row.add_child(kigou_edit)
	var need1_edit := LineEdit.new(); need1_edit.text = str(sh.get("need1", "")); row.add_child(need1_edit)
	var need2_edit := LineEdit.new(); need2_edit.text = str(sh.get("need2", "")); row.add_child(need2_edit)
	var save := Button.new(); save.text = "保存"
	save.pressed.connect(func(): run_op("ws1EditShift", {"k": k, "name": name_edit.text, "kigou": kigou_edit.text, "need1": need1_edit.text, "need2": need2_edit.text}))
	row.add_child(save)
	row.add_child(_move_buttons(k, count, "ws1MoveShiftTo"))
	var del := Button.new(); del.text = "削除"
	del.pressed.connect(func(): run_op("ws1RemoveShift", {"k": k}))
	row.add_child(del)
	return row

func _group_row(g: int, gr: Dictionary, count: int) -> Control:
	var row := HBoxContainer.new()
	var name_edit := LineEdit.new(); name_edit.text = str(gr.get("name", "")); row.add_child(name_edit)
	var kigou_edit := LineEdit.new(); kigou_edit.text = str(gr.get("kigou", "")); row.add_child(kigou_edit)
	var save := Button.new(); save.text = "保存"
	save.pressed.connect(func(): run_op("ws1EditGroup", {"g": g, "name": name_edit.text, "kigou": kigou_edit.text}))
	row.add_child(save)
	row.add_child(_move_buttons(g, count, "ws1MoveGroupTo"))
	var del := Button.new(); del.text = "削除"
	del.pressed.connect(func(): run_op("ws1RemoveGroup", {"g": g}))
	row.add_child(del)
	return row
