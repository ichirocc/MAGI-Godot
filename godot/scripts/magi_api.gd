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
# Android なのに Kotlin 側へ繋げなかった状態。モックへ黙って落とすと「動いているように見えて
# 実データに何も反映されない」ため、この状態では snapshot/dispatch をエラー応答にする。
var _native_failed: bool = false
## 直近の snapshot 取得/接続の失敗（空なら正常）。画面はこれを見て「表示は最後に取得できた状態」と示す。
var last_error: String = ""

func _ready() -> void:
	if OS.get_name() == "Android":
		# NOTE: 実機でのクラス名・メソッドシグネチャの一致は未検証。
		_activity_class = JavaClassWrapper.wrap("com.magi.app.godot.MagiGodotActivity")
		if _activity_class == null:
			_native_failed = true
			last_error = "JavaClassWrapper.wrap(MagiGodotActivity) failed"
			push_error("MagiApi: JavaClassWrapper.wrap(MagiGodotActivity) failed; bridge unavailable")

func is_native() -> bool:
	return _activity_class != null

## エディタ等の非Android実行でだけモックを使う。Android で接続に失敗した場合はモックにしない。
func _use_mock() -> bool:
	return not is_native() and not _native_failed

## 現在のUiStateを取得し、内部キャッシュ(_last_state/_current_token)を更新する。
## 失敗時は前回の状態を返し last_error を立てる（エラー応答を盤面として描画しない）。
func refresh() -> Dictionary:
	var raw := ""
	if is_native():
		raw = _activity_class.magiSnapshot()
	elif _use_mock():
		raw = _mock_snapshot()
	else:
		last_error = "native bridge unavailable"
		push_error("MagiApi: snapshot unavailable (native bridge failed)")
		return _last_state
	var parsed = JSON.parse_string(raw)
	if parsed == null or not (parsed is Dictionary):
		last_error = "snapshot JSON parse failed"
		push_error("MagiApi: snapshot JSON parse failed")
		return _last_state
	if parsed.has("error") and not parsed.get("ok", true):
		last_error = str(parsed["error"])
		push_error("MagiApi: snapshot failed: %s" % last_error)
		return _last_state
	last_error = ""
	_last_state = parsed
	_current_token = str(parsed.get("_token", ""))
	state_changed.emit(_last_state)
	return _last_state

func state() -> Dictionary:
	return _last_state

## システムバー＋カットアウトの inset（px, [left, top, right, bottom]）。Kotlin 側の WindowInsets 由来。
## Godot の get_display_safe_area() はカットアウトしか含まないため、ステータスバー/ナビバー分はこちらで得る。
func insets() -> Array:
	if not is_native():
		return [0, 0, 0, 0]
	var parsed = JSON.parse_string(str(_activity_class.magiInsets()))
	if parsed is Array and parsed.size() == 4:
		return parsed
	return [0, 0, 0, 0]

## 許可された操作のみ dispatch する（実際の許可判定はKotlin側 MagiOpWhitelist が最終判定）。
## 戻り値の "changed" は Kotlin 側が本文の変化で判定した値（no-op の操作は ok=true, changed=false）。
func dispatch(op: String, args: Dictionary = {}) -> Dictionary:
	args = args.duplicate()
	args["_token"] = _current_token
	var args_json := JSON.stringify(args)
	var raw := ""
	if is_native():
		raw = _activity_class.magiDispatch(op, args_json)
	elif _use_mock():
		raw = _mock_dispatch(op, args)
	else:
		last_error = "native bridge unavailable"
		return {"ok": false, "error": "bridge unavailable: native bridge failed"}
	var result = JSON.parse_string(raw)
	if result == null or not (result is Dictionary):
		return {"ok": false, "error": "dispatch response parse failed"}
	if result.get("ok", false):
		last_error = ""
		var snap_raw = result.get("snapshot", "")
		var snap = JSON.parse_string(snap_raw)
		if snap != null and snap is Dictionary:
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

func _mock_dispatch(_op: String, _args: Dictionary) -> String:
	return JSON.stringify({"ok": true, "changed": false, "snapshot": _mock_snapshot(), "token": "mock:1"})
