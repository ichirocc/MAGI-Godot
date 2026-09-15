extends "res://scripts/screens/base_screen.gd"
## 取消・保存: Undo/Redo/保存/CSV取込。JSON/CSV書出そのもの(ファイル選択・共有)はAndroid側の
## 既存 MagiViewModelIo.kt が SAF(Storage Access Framework) 経由で行っており、GodotからSAFの
## ファイル選択UIを直接開く経路は[未実装/未検証]（docs/godot-ui-migration.md参照）。

var _csv: TextEdit

func _ready() -> void:
	tab_key = "undo_export"
	super._ready()

func build_actions(bar: HBoxContainer) -> void:
	var u := Button.new(); u.text = "取消(Undo)"
	u.pressed.connect(func(): run_op("undo"))
	bar.add_child(u)
	var r := Button.new(); r.text = "やり直し(Redo)"
	r.pressed.connect(func(): run_op("redo"))
	bar.add_child(r)
	var s := Button.new(); s.text = "保存"
	s.pressed.connect(func(): run_op("saveNow"))
	bar.add_child(s)
	_csv = TextEdit.new()
	_csv.custom_minimum_size = Vector2(0, 200)
	_csv.placeholder_text = "CSVを貼り付けて取込"
	$VBox.add_child(_csv)
	var imp := Button.new(); imp.text = "CSV取込"
	imp.pressed.connect(func(): run_op("importCsv", {"csv": _csv.text}))
	bar.add_child(imp)

func render(state: Dictionary) -> void:
	var lines := ["[b]取消・保存[/b]"]
	lines.append("取消可能: %s / やり直し可能: %s" % [state.get("canUndo", false), state.get("canRedo", false)])
	lines.append("保存状態: %s" % state.get("saveState", ""))
	lines.append("(JSON/CSVのファイル書出しはAndroid側SAF連携が未実装＝未検証)")
	$VBox/Content.text = "\n".join(lines)
