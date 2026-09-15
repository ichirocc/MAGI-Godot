extends "res://scripts/screens/base_screen.gd"
## 担当とスキル管理: [未実装/読み取り専用] canDo/apt目標の一覧表示のみ。編集は未実装
## （群単位apt目標・担当可否の書き換えは別途 dispatch op の追加が必要）。

func _ready() -> void:
	tab_key = "skills"
	super._ready()

func render(state: Dictionary) -> void:
	var lines := ["[b]担当・スキル[/b]", "(読み取り専用: canDo/適切回数目標の編集は未実装)"]
	lines.append("職員 %d 名 / シフト種 %d" % [state.get("staff", 0), state.get("shifts", 0)])
	$VBox/Content.text = "\n".join(lines)
