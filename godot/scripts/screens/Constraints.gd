extends "res://scripts/screens/base_screen.gd"
## 制約編集: 一覧の追加/編集/削除に対応する族は cons1/cons2/cons3系4種/cons3w/cons41/cons42/cons41s/cons42s
## の11族（ConstraintEditor.ktが扱う範囲＝行として登録するもの）。covU/covO/low/high/apt/fair/weekly/
## pref/groupVioは行の追加/削除対象ではなく評価結果（breakdown）としてのみ表示する。

const PATTERN_FAMS := ["cons3", "cons3n", "cons3m", "cons3mn"]

var _sections: Dictionary = {}  # family -> VBoxContainer
var _breakdown_label: RichTextLabel

func _ready() -> void:
	tab_key = "constraints"
	super._ready()

func _lbl(t: String) -> Label:
	var l := Label.new(); l.text = t
	return l

func build_actions(_bar: HBoxContainer) -> void:
	var scroll := ScrollContainer.new()
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	var root := VBoxContainer.new()
	scroll.add_child(root)
	$VBox.add_child(scroll)
	$VBox.move_child(scroll, $VBox.get_children().find($VBox/Content) + 1)

	_add_family_section(root, "cons1", "cons1: 期間の制約（○日間で○回など）",
		["日数", "シフト記号", "回数"], "addCons1", ["day1", "shiftKigou", "day2"])
	_add_family_section(root, "cons2", "cons2: 個人の合計（回数）",
		["シフト記号", "回数"], "addCons2", ["shiftKigou", "count"])
	for fam in PATTERN_FAMS:
		_add_pattern_section(root, fam)
	_add_family_section(root, "cons3w", "cons3w: 希望の前日に禁止（必ず守る）",
		["希望シフト記号", "前日禁止シフト記号"], "addCons3w", ["wishKigou", "prevKigou"])
	_add_family_section(root, "cons41", "cons41: グループのレンジ（1日の人数の下限〜上限）",
		["グループ記号", "シフト記号", "下限", "上限"], "addCons41", ["groupKigou", "shiftKigou", "l", "u"])
	# 追加フォームの並びは既存行の編集フォーム（g1/s1/g2/s2）と同じにする。引数はキー名で渡すので順序は自由。
	_add_family_section(root, "cons42", "cons42: グループペア禁止（同じ日に不可）",
		["グループ1記号", "シフト1記号", "グループ2記号", "シフト2記号"], "addCons42", ["g1", "s1", "g2", "s2"])
	_add_family_section(root, "cons41s", "cons41s: スキルグループのレンジ",
		["スキルグループ記号", "シフト記号", "下限", "上限"], "addCons41s", ["groupKigou", "shiftKigou", "l", "u"])
	_add_family_section(root, "cons42s", "cons42s: スキルグループペア禁止",
		["スキルグループ1記号", "シフト1記号", "スキルグループ2記号", "シフト2記号"], "addCons42s", ["g1", "s1", "g2", "s2"])

	root.add_child(_lbl("その他のSOFT/HARD違反内訳（covU/covO/low/high/apt/fair/weekly/pref/groupViol。行編集は無し・評価結果のみ）"))
	_breakdown_label = RichTextLabel.new(); _breakdown_label.fit_content = true; _breakdown_label.bbcode_enabled = true
	root.add_child(_breakdown_label)

func _add_family_section(root: VBoxContainer, fam: String, title: String, field_labels: Array, add_op: String, add_keys: Array) -> void:
	root.add_child(_lbl(title))
	var box := VBoxContainer.new(); root.add_child(box)
	_sections[fam] = box
	var add_row := HBoxContainer.new()
	var edits: Array = []
	for lbl in field_labels:
		var e := LineEdit.new(); e.placeholder_text = lbl; e.custom_minimum_size = Vector2(64, 0)
		edits.append(e); add_row.add_child(e)
	var btn := Button.new(); btn.text = "追加"
	btn.pressed.connect(func():
		var args := {}
		for idx in range(add_keys.size()):
			args[add_keys[idx]] = edits[idx].text
		if run_op(add_op, args):
			for e in edits: e.text = "")
	add_row.add_child(btn)
	root.add_child(add_row)

func _add_pattern_section(root: VBoxContainer, fam: String) -> void:
	var jp := {"cons3": "必須の並び", "cons3n": "禁止の並び", "cons3m": "推奨の並び", "cons3mn": "回避の並び"}
	root.add_child(_lbl("%s: %s（最大5シフト記号を→で並べる）" % [fam, jp.get(fam, fam)]))
	var box := VBoxContainer.new(); root.add_child(box)
	_sections[fam] = box
	var add_row := HBoxContainer.new()
	var edits: Array = []
	for i in range(5):
		var e := LineEdit.new(); e.placeholder_text = "%d日目" % (i + 1); e.custom_minimum_size = Vector2(48, 0)
		edits.append(e); add_row.add_child(e)
	var btn := Button.new(); btn.text = "追加"
	btn.pressed.connect(func():
		var pat: Array = []
		for e in edits: pat.append(e.text)
		if run_op("addCons3", {"family": fam, "pattern": pat}):
			for e in edits: e.text = "")
	add_row.add_child(btn)
	root.add_child(add_row)

func render(state: Dictionary) -> void:
	var structure: Dictionary = state.get("structure", {})
	var breakdown: Dictionary = state.get("breakdown", {})
	var total := 0
	for fam in ["cons1", "cons2", "cons3", "cons3n", "cons3m", "cons3mn", "cons3w", "cons41", "cons42", "cons41s", "cons42s"]:
		total += (structure.get(fam, []) as Array).size()
	$VBox/Content.text = "[b]制約（全11族）[/b] 登録行数合計 %d" % total
	if _sections.is_empty():
		return

	_render_simple(structure, "cons1", ["day1", "shiftKigou", "day2"], "addCons1")
	_render_simple(structure, "cons2", ["shiftKigou", "count"], "addCons2")
	for fam in PATTERN_FAMS:
		_render_pattern(structure, fam)
	_render_simple(structure, "cons3w", ["wishKigou", "prevKigou"], "addCons3w")
	_render_simple(structure, "cons41", ["groupKigou", "shiftKigou", "l", "u"], "addCons41")
	_render_simple(structure, "cons42", ["g1Kigou", "s1Kigou", "g2Kigou", "s2Kigou"], "addCons42")
	_render_simple(structure, "cons41s", ["groupKigou", "shiftKigou", "l", "u"], "addCons41s")
	_render_simple(structure, "cons42s", ["g1Kigou", "s1Kigou", "g2Kigou", "s2Kigou"], "addCons42s")

	var lines := []
	for key in breakdown.keys():
		lines.append("%s: %s件" % [key, breakdown[key]])
	_breakdown_label.text = "\n".join(lines)

func _render_simple(structure: Dictionary, fam: String, keys: Array, _add_op: String) -> void:
	var box: VBoxContainer = _sections[fam]
	for c in box.get_children(): c.queue_free()
	var rows: Array = structure.get(fam, [])
	for idx in range(rows.size()):
		box.add_child(_row_generic(fam, idx, keys.map(func(k): return str(rows[idx].get(k, "")))))

func _render_pattern(structure: Dictionary, fam: String) -> void:
	var box: VBoxContainer = _sections[fam]
	for c in box.get_children(): c.queue_free()
	var rows: Array = structure.get(fam, [])
	for idx in range(rows.size()):
		var pat: Array = rows[idx]
		var vals: Array = []
		for i in range(5): vals.append(str(pat[i]) if i < pat.size() else "")
		box.add_child(_row_generic(fam, idx, vals))

func _row_generic(fam: String, idx: int, values: Array) -> Control:
	var row := HBoxContainer.new()
	var edits: Array = []
	for v in values:
		var e := LineEdit.new(); e.text = v; e.custom_minimum_size = Vector2(48, 0)
		edits.append(e); row.add_child(e)
	var save := Button.new(); save.text = "保存"
	save.pressed.connect(func():
		var vs: Array = []
		for e in edits: vs.append(e.text)
		run_op("updateConstraint", {"family": fam, "index": idx, "values": vs}))
	row.add_child(save)
	var del := Button.new(); del.text = "削除"
	del.pressed.connect(func(): run_op("removeConstraint", {"family": fam, "index": idx}))
	row.add_child(del)
	return row
