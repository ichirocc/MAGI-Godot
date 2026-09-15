extends "res://scripts/screens/base_screen.gd"
## 職員・シフト・群 管理: [未実装/読み取り専用] 一覧表示のみ。
## 追加/編集/削除/並び替え（MagiViewModelConstraints.kt 相当のCRUD）はop許可リストに未追加＝
## Kotlin側の対応メソッドをdispatch対象へ広げる作業が別途必要（docs/godot-ui-migration.md参照）。

func _ready() -> void:
	tab_key = "staff"
	super._ready()

func render(state: Dictionary) -> void:
	var lines := ["[b]職員 / シフト / 群[/b]（現状: 一覧表示のみ・編集は未実装）", ""]
	lines.append("[u]職員(%d名)[/u]" % state.get("staff", 0))
	var names: Array = state.get("staffNames", [])
	var groupSymbols: Array = state.get("staffGroupSymbols", [])
	for i in range(names.size()):
		lines.append("  %d: %s  群=%s" % [i, names[i], (groupSymbols[i] if i < groupSymbols.size() else "")])
	lines.append("")
	lines.append("[u]シフト種(%d種)[/u]" % state.get("shifts", 0))
	var symbols: Array = state.get("shiftSymbols", [])
	for k in range(symbols.size()):
		lines.append("  %d: %s" % [k, symbols[k]])
	$VBox/Content.text = "\n".join(lines)
