extends Control
## 10画面共通の骨格。各画面スクリプトはこれを継承し、tab_key/render()/build_actions()だけ実装する。
## 描画は必ず MagiApi.state()（=MagiBridge.snapshot()由来のJSON）から行い、盤面へ直接触らない。

var tab_key: String = "home"  # サブクラスで上書き

func _ready() -> void:
	Nav.build_tab_bar($VBox/TabBar, tab_key)
	MagiApi.state_changed.connect(_on_state_changed)
	build_actions($VBox/Actions)
	MagiApi.refresh()

func _on_state_changed(state: Dictionary) -> void:
	render(state)

func render(_state: Dictionary) -> void:
	push_warning("render() not overridden by screen: %s" % tab_key)

func build_actions(_bar: HBoxContainer) -> void:
	pass  # サブクラスで操作ボタンを積む

## ボタン共通ハンドラ: dispatchしてエラーなら赤字表示、成功ならrender()は state_changed 経由で走る。
func run_op(op: String, args: Dictionary = {}) -> void:
	var result := MagiApi.dispatch(op, args)
	if not result.get("ok", false):
		$VBox/Content.append_text("\n[color=red]%s: %s[/color]" % [op, result.get("error", "?")])
