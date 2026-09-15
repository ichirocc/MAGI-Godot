extends "res://scripts/screens/base_screen.gd"
## 制約編集(全11制約族: c1/c2/c3系/c41/c42/covU/covO/low/high/apt/fair/weekly/pref/groupViol)。
## [未実装/読み取り専用] 現状はbreakdownの内訳数値のみ表示。各族の行編集(MagiViewModelConstraints.kt
## の追加/削除/更新)はop許可リストへの追加が必要（重み自体は変更しない=CLAUDE.md方針を維持）。

func _ready() -> void:
	tab_key = "constraints"
	super._ready()

func render(state: Dictionary) -> void:
	var lines := ["[b]制約（11族）内訳[/b]", "(行の追加/編集/削除は未実装。件数のみ表示)"]
	var breakdown: Dictionary = state.get("breakdown", {})
	for key in breakdown.keys():
		lines.append("  %s: %s件" % [key, breakdown[key]])
	$VBox/Content.text = "\n".join(lines)
