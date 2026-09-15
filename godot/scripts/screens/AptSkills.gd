extends "res://scripts/screens/base_screen.gd"
## 担当とスキル管理。
## canDo(担当可否)とapt(適切回数目標)はエンジン仕様上「群×シフト」単位（groupShift/groupShiftApt。
## 個人単位のデータは存在しない＝docs/data-models.mdに項目なし・CLAUDE.md「存在しない項目を創作しない」）。
## 職員側は所属群の変更(職員/シフト/群管理画面)とスキル区分の割当のみここで扱う。

var _canDo_box: GridContainer
var _apt_box: GridContainer
var _skillgroup_box: VBoxContainer
var _staffskill_box: VBoxContainer
var _skillgroup_name_new: LineEdit
var _skillgroup_kigou_new: LineEdit

func _ready() -> void:
	tab_key = "skills"
	super._ready()

func build_actions(_bar: HBoxContainer) -> void:
	var scroll := ScrollContainer.new()
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_AUTO
	var root := VBoxContainer.new()
	scroll.add_child(root)
	$VBox.add_child(scroll)
	$VBox.move_child(scroll, $VBox.get_children().find($VBox/Content) + 1)

	root.add_child(_lbl("担当可否（グループ × シフト。タップで切替）"))
	_canDo_box = GridContainer.new(); root.add_child(_canDo_box)

	root.add_child(_lbl("適切回数 apt（グループ × シフト。空欄=目標なし）"))
	_apt_box = GridContainer.new(); root.add_child(_apt_box)

	root.add_child(_lbl("スキル区分"))
	_skillgroup_box = VBoxContainer.new(); root.add_child(_skillgroup_box)
	var sg_add := HBoxContainer.new()
	_skillgroup_name_new = LineEdit.new(); _skillgroup_name_new.placeholder_text = "名称"; sg_add.add_child(_skillgroup_name_new)
	_skillgroup_kigou_new = LineEdit.new(); _skillgroup_kigou_new.placeholder_text = "記号"; sg_add.add_child(_skillgroup_kigou_new)
	var sg_add_btn := Button.new(); sg_add_btn.text = "スキル区分を追加"
	sg_add_btn.pressed.connect(func():
		if run_op("addSkillGroup", {"name": _skillgroup_name_new.text, "kigou": _skillgroup_kigou_new.text}):
			_skillgroup_name_new.text = ""; _skillgroup_kigou_new.text = "")
	sg_add.add_child(sg_add_btn)
	root.add_child(sg_add)

	root.add_child(_lbl("職員のスキル区分割当"))
	_staffskill_box = VBoxContainer.new(); root.add_child(_staffskill_box)

func _lbl(t: String) -> Label:
	var l := Label.new(); l.text = t
	return l

func render(state: Dictionary) -> void:
	var structure: Dictionary = state.get("structure", {})
	var shifts: Array = structure.get("shifts", [])
	var groups: Array = structure.get("groups", [])
	var staff: Array = structure.get("staff", [])
	var skillGroups: Array = structure.get("skillGroups", [])
	var groupShift: Array = structure.get("groupShift", [])
	var groupShiftApt: Array = structure.get("groupShiftApt", [])
	$VBox/Content.text = "[b]担当・スキル[/b] グループ%d x シフト%d / スキル区分%d" % [groups.size(), shifts.size(), skillGroups.size()]
	if _canDo_box == null:
		return

	_canDo_box.columns = shifts.size() + 1
	for c in _canDo_box.get_children(): c.queue_free()
	_canDo_box.add_child(Label.new())
	for k in range(shifts.size()):
		_canDo_box.add_child(_lbl(str(shifts[k].get("kigou", k))))
	for g in range(groups.size()):
		_canDo_box.add_child(_lbl(str(groups[g].get("name", g))))
		var row: Array = groupShift[g] if g < groupShift.size() else []
		for k in range(shifts.size()):
			var on: bool = (k < row.size() and int(row[k]) != 0)
			var btn := Button.new(); btn.text = ("○" if on else "×"); btn.toggle_mode = true; btn.button_pressed = on
			btn.pressed.connect(func(): run_op("ws1SetGroupShift", {"g": g, "k": k, "allowed": not on}))
			_canDo_box.add_child(btn)

	_apt_box.columns = shifts.size() + 1
	for c in _apt_box.get_children(): c.queue_free()
	_apt_box.add_child(Label.new())
	for k in range(shifts.size()):
		_apt_box.add_child(_lbl(str(shifts[k].get("kigou", k))))
	for g in range(groups.size()):
		_apt_box.add_child(_lbl(str(groups[g].get("name", g))))
		var row: Array = groupShiftApt[g] if g < groupShiftApt.size() else []
		for k in range(shifts.size()):
			var v := str(row[k]) if k < row.size() else ""
			var edit := LineEdit.new(); edit.text = v; edit.custom_minimum_size = Vector2(48, 0)
			edit.text_submitted.connect(func(_t): run_op("ws1SetGroupApt", {"g": g, "k": k, "value": edit.text}))
			_apt_box.add_child(edit)

	for c in _skillgroup_box.get_children(): c.queue_free()
	for g in range(skillGroups.size()):
		_skillgroup_box.add_child(_skillgroup_row(g, skillGroups[g]))

	for c in _staffskill_box.get_children(): c.queue_free()
	for i in range(staff.size()):
		_staffskill_box.add_child(_staff_skill_row(i, staff[i], skillGroups))

func _skillgroup_row(g: int, sg: Dictionary) -> Control:
	var row := HBoxContainer.new()
	var name_edit := LineEdit.new(); name_edit.text = str(sg.get("name", "")); row.add_child(name_edit)
	var kigou_edit := LineEdit.new(); kigou_edit.text = str(sg.get("kigou", "")); row.add_child(kigou_edit)
	var save := Button.new(); save.text = "保存"
	save.pressed.connect(func(): run_op("editSkillGroup", {"g": g, "name": name_edit.text, "kigou": kigou_edit.text}))
	row.add_child(save)
	var del := Button.new(); del.text = "削除"
	del.pressed.connect(func(): run_op("removeSkillGroup", {"g": g}))
	row.add_child(del)
	return row

func _staff_skill_row(i: int, s: Dictionary, skillGroups: Array) -> Control:
	var row := HBoxContainer.new()
	row.add_child(_lbl(str(s.get("name", i))))
	var opt := OptionButton.new()
	for g in range(skillGroups.size()):
		opt.add_item(str(skillGroups[g].get("name", g)), g)
	opt.select(int(s.get("skillIdx", 0)))
	opt.item_selected.connect(func(_idx): run_op("setStaffSkill", {"i": i, "skillIdx": opt.get_selected_id()}))
	row.add_child(opt)
	return row
