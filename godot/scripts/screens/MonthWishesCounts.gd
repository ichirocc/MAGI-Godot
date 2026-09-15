extends "res://scripts/screens/base_screen.gd"
## 月・希望・回数編集: 希望反映/範囲外希望クリアはdispatch可能。個々の希望セル編集(ws3行列)は
## [未実装] — MagiViewModelWs1.kt側の個別セット操作をop許可リストへ広げる作業が必要。

func _ready() -> void:
	tab_key = "wishes"
	super._ready()

func build_actions(bar: HBoxContainer) -> void:
	var b1 := Button.new(); b1.text = "希望を反映(範囲内)"
	b1.pressed.connect(func(): run_op("applyWishes", {"includeOutOfScope": false}))
	bar.add_child(b1)
	var b2 := Button.new(); b2.text = "希望を反映(範囲外含む)"
	b2.pressed.connect(func(): run_op("applyWishes", {"includeOutOfScope": true}))
	bar.add_child(b2)
	var b3 := Button.new(); b3.text = "範囲外希望をクリア"
	b3.pressed.connect(func(): run_op("clearOutOfScopeWishes"))
	bar.add_child(b3)

func render(state: Dictionary) -> void:
	var lines := ["[b]月・希望・回数[/b]", "開始日: %s" % state.get("startDate", "")]
	lines.append("希望不可能件数: %d" % state.get("impossibleWishCount", 0))
	lines.append("(希望セルの個別編集は未実装。反映/クリア操作のみ)")
	$VBox/Content.text = "\n".join(lines)
