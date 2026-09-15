extends Node
## 画面遷移用の小さなautoload。タブ切替をシーン差し替えで表現する（Compose版のNavHostの代替）。

const SCENES := {
	"home": "res://scenes/Home.tscn",
	"schedule": "res://scenes/Schedule.tscn",
	"staff": "res://scenes/StaffGroupsShifts.tscn",
	"wishes": "res://scenes/MonthWishesCounts.tscn",
	"skills": "res://scenes/AptSkills.tscn",
	"constraints": "res://scenes/Constraints.tscn",
	"analysis": "res://scenes/Analysis.tscn",
	"settings": "res://scenes/Settings.tscn",
	"json": "res://scenes/JsonEditor.tscn",
	"undo_export": "res://scenes/UndoExport.tscn",
}

const TAB_LABELS := {
	"home": "ホーム", "schedule": "勤務表", "staff": "職員/群",
	"wishes": "希望/回数", "skills": "担当/スキル", "constraints": "制約",
	"analysis": "分析", "settings": "設定", "json": "JSON",
	"undo_export": "取消/保存",
}

func _ready() -> void:
	if OS.get_name() == "Android":
		# 端末ピクセル 1:1 だと高 DPI 端末で文字が極小になる（実機 3.550.0）。dp 相当（DPI/160）へ拡大する。
		get_tree().root.content_scale_factor = clampf(DisplayServer.screen_get_dpi() / 160.0, 1.0, 4.0)

func go(tab: String) -> void:
	var path: String = SCENES.get(tab, "")
	if path == "":
		push_error("Nav: unknown tab '%s'" % tab)
		return
	get_tree().change_scene_to_file(path)

## 各画面の共通ヘッダー: タブ切替ボタンを1行並べる。current は強調表示のみ（禁止ではない）。
func build_tab_bar(container: HBoxContainer, current: String) -> void:
	for child in container.get_children():
		child.queue_free()
	for key in SCENES.keys():
		var btn := Button.new()
		btn.text = TAB_LABELS.get(key, key)
		btn.disabled = (key == current)
		btn.pressed.connect(func(): go(key))
		container.add_child(btn)
