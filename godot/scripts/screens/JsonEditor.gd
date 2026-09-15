extends "res://scripts/screens/base_screen.gd"
## 詳細JSON編集: 生JSONを読み込む(load)。現行盤面のJSON全文出力はMagiBridge.snapshot()が
## UiStateの要約のみを返すため未対応（元の MagiState 全文が必要なら別opの追加が要る＝未実装）。

var _editor: TextEdit

func _ready() -> void:
	tab_key = "json"
	super._ready()

func build_actions(bar: HBoxContainer) -> void:
	_editor = TextEdit.new()
	_editor.custom_minimum_size = Vector2(0, 300)
	$VBox.add_child(_editor)
	var b := Button.new(); b.text = "このJSONを読み込む"
	b.pressed.connect(func(): run_op("load", {"json": _editor.text}))
	bar.add_child(b)

func render(_state: Dictionary) -> void:
	$VBox/Content.text = "[b]詳細JSON編集[/b]\n下のテキストへ盤面JSONを貼り付けて読込。\n(現盤面の全文書き出しは未実装＝取消/保存タブのCSV/JSON書出を使用)"
