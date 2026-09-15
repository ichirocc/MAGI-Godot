extends Node
## [Godot移行] Kotlin側 MagiBridge への唯一の窓口（autoload）。
## 画面(Screen)スクリプトはこのAPIのsnapshot()/dispatch()だけを使い、盤面へ直接触らない。
##
## 実機(Android)では JavaClassWrapper 経由で MagiGodotActivity の magiSnapshot()/magiDispatch()
## を呼ぶ。未検証: このサンドボックスには Godot/Android 実行環境が無く、JavaClassWrapper 越しの
## 実際の呼び出しは実機でしか確認できない（docs/godot-ui-migration.md）。
## エディタ実行時（OS.get_name() != "Android"）はモックJSONを返し、画面の見た目・遷移だけ確認できるようにする。

signal state_changed(state: Dictionary)

var _current_token: String = ""
var _last_state: Dictionary = {}
var _activity_class = null

func _ready() -> void:
	if OS.get_name() == "Android":
		# NOTE: 実機でのクラス名・メソッドシグネチャの一致は未検証。
		_activity_class = JavaClassWrapper.wrap("com.magi.app.godot.MagiGodotActivity")

func is_native() -> bool:
	return _activity_class != null

## 現在のUiStateを取得し、内部キャッシュ(_last_state/_current_token)を更新する。
func refresh() -> Dictionary:
	var raw := ""
	if is_native():
		raw = _activity_class.magiSnapshot()
	else:
		raw = _mock_snapshot()
	var parsed: Dictionary = JSON.parse_string(raw)
	if parsed == null:
		push_error("MagiApi: snapshot JSON parse failed")
		return _last_state
	_last_state = parsed
	_current_token = str(parsed.get("_token", ""))
	state_changed.emit(_last_state)
	return _last_state

func state() -> Dictionary:
	return _last_state

## 許可された操作のみ dispatch する（実際の許可判定はKotlin側 MagiOpWhitelist が最終判定）。
func dispatch(op: String, args: Dictionary = {}) -> Dictionary:
	args = args.duplicate()
	args["_token"] = _current_token
	var args_json := JSON.stringify(args)
	var raw := ""
	if is_native():
		raw = _activity_class.magiDispatch(op, args_json)
	else:
		raw = _mock_dispatch(op, args)
	var result: Dictionary = JSON.parse_string(raw)
	if result == null:
		return {"ok": false, "error": "dispatch response parse failed"}
	if result.get("ok", false):
		var snap_raw = result.get("snapshot", "")
		var snap: Dictionary = JSON.parse_string(snap_raw)
		if snap != null:
			_last_state = snap
			_current_token = str(result.get("token", ""))
			state_changed.emit(_last_state)
	return result

# ---- エディタ/非Android実行用のモック（構文・画面遷移確認のみ。業務ロジックは含まない） ----

func _mock_snapshot() -> String:
	return JSON.stringify({
		"loaded": false, "staff": 0, "days": 0, "shifts": 0, "groups": 0,
		"running": false, "hasResult": false, "schedule": [], "staffNames": [],
		"shiftSymbols": [], "breakdown": {}, "message": "(editor mock)",
		"_token": "mock:0", "_rev": 0,
	})

func _mock_dispatch(op: String, _args: Dictionary) -> String:
	return JSON.stringify({"ok": true, "snapshot": _mock_snapshot(), "token": "mock:1"})
