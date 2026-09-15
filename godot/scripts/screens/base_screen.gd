extends Control
## 10画面共通の骨格。各画面スクリプトはこれを継承し、tab_key/render()/build_actions()だけ実装する。
## 描画は必ず MagiApi.state()（=MagiBridge.snapshot()由来のJSON）から行い、盤面へ直接触らない。

var tab_key: String = "home"  # サブクラスで上書き
var _status: Label  # 直近の操作結果/接続状態。Content への追記ではなく上書き表示（古いエラーを溜めない）

func _ready() -> void:
	var tab_bar: HBoxContainer = $VBox/TabBar
	var actions: HBoxContainer = $VBox/Actions
	Nav.build_tab_bar(tab_bar, tab_key)
	_status = Label.new()
	_status.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_status.visible = false
	$VBox.add_child(_status)
	$VBox.move_child(_status, $VBox.get_children().find(actions))
	MagiApi.state_changed.connect(_on_state_changed)
	build_actions(actions)
	# 端末幅に収まらないボタン列は横スクロール（build_actions の後＝サブクラスは $VBox/Actions を直接見られる）。
	_wrap_horizontal(tab_bar)
	_wrap_horizontal(actions)
	_apply_safe_area()
	get_tree().root.size_changed.connect(_apply_safe_area)
	MagiApi.refresh()
	_show_bridge_status()

func _wrap_horizontal(bar: Control) -> void:
	var parent := bar.get_parent()
	var idx := bar.get_index()
	var scroll := ScrollContainer.new()
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_AUTO
	scroll.vertical_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED  # 縦は子の高さに合わせる
	parent.remove_child(bar)
	scroll.add_child(bar)
	parent.add_child(scroll)
	parent.move_child(scroll, idx)

## ステータスバー・カメラ穴・ナビゲーションバーの下に UI が潜らないよう、$VBox の余白を安全領域に合わせる。
## Android のみ（デスクトップの get_display_safe_area はスクリーン座標でウィンドウと対応しない）。回転で再適用。
func _apply_safe_area() -> void:
	if OS.get_name() != "Android":
		return
	var win := DisplayServer.window_get_size()
	var safe := DisplayServer.get_display_safe_area()
	if win.x <= 0 or win.y <= 0 or safe.size.x <= 0 or safe.size.y <= 0:
		return
	var s: float = get_tree().root.content_scale_factor
	$VBox.offset_left = maxf(0.0, safe.position.x / s)
	$VBox.offset_top = maxf(0.0, safe.position.y / s)
	$VBox.offset_right = -maxf(0.0, (win.x - safe.end.x) / s)
	$VBox.offset_bottom = -maxf(0.0, (win.y - safe.end.y) / s)

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
## 左角括弧を chr(91) で書くのは tools/godot-ui-check.sh の括弧対応チェック（文字列内も数える）を通すため。
static func bb(s) -> String:
	return str(s).replace(String.chr(91), "[lb]")
