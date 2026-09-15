extends Control
## 10画面共通の骨格。各画面スクリプトはこれを継承し、tab_key/render()/build_actions()だけ実装する。
## 描画は必ず MagiApi.state()（=MagiBridge.snapshot()由来のJSON）から行い、盤面へ直接触らない。

var tab_key: String = "home"  # サブクラスで上書き
var _status: Label  # 直近の操作結果/接続状態。Content への追記ではなく上書き表示（古いエラーを溜めない）

func _ready() -> void:
	Nav.build_tab_bar($VBox/TabBar, tab_key)
	_status = Label.new()
	_status.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_status.visible = false
	$VBox.add_child(_status)
	$VBox.move_child(_status, $VBox.get_children().find($VBox/Actions))
	MagiApi.state_changed.connect(_on_state_changed)
	build_actions($VBox/Actions)
	MagiApi.refresh()
	_show_bridge_status()

func _on_state_changed(state: Dictionary) -> void:
	render(state)
	_show_bridge_status()

func render(_state: Dictionary) -> void:
	push_warning("render() not overridden by screen: %s" % tab_key)

func build_actions(_bar: HBoxContainer) -> void:
	pass  # サブクラスで操作ボタンを積む

## ボタン共通ハンドラ。成功時は state_changed 経由で render() が走る。
## 失敗時は snapshot から再描画し、押したトグルや入力欄の見た目だけが変わった状態を残さない。
func run_op(op: String, args: Dictionary = {}) -> bool:
	var result := MagiApi.dispatch(op, args)
	var ok: bool = result.get("ok", false)
	if ok:
		_set_status("", false)
	else:
		_set_status("%s: %s" % [op, str(result.get("error", "?"))], true)
		render(MagiApi.state())
	return ok

func _set_status(text: String, is_error: bool) -> void:
	if _status == null:
		return
	_status.text = text
	_status.visible = text != ""
	_status.add_theme_color_override("font_color", Color.RED if is_error else Color.DARK_GREEN)

func _show_bridge_status() -> void:
	if MagiApi.last_error != "":
		_set_status("接続エラー（表示は最後に取得できた状態）: %s" % MagiApi.last_error, true)

## RichTextLabel(bbcode_enabled) に外部由来の文字列を入れるときのエスケープ。
static func bb(s) -> String:
	return str(s).replace("[", "[lb]")
