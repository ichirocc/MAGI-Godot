extends SceneTree
## [Godot移行] CI用の最小スモーク（godot --headless --path godot --script res://tools/headless_smoke.gd）。
## autoload（MagiApi/Nav）が読めること、Nav.SCENES の全画面がロード・インスタンス化でき、
## _ready（MagiApi.refresh → render）がスクリプトエラー無く走ることだけを確認する。
## 非Android実行なので MagiApi はモックJSONを返す＝業務ロジックや実機の橋渡しの検証ではない。

var _exit_code := 0
var _done := false

# _initialize() は root がツリーに入る前に呼ばれ、そこで add_child しても _ready が走らない
# （CI 初回で全画面の refresh 検査が空振りした）。最初のフレームで実行し、次のフレームで quit する。
func _process(_delta: float) -> bool:
	if _done:
		quit(_exit_code)
		return true
	_done = true
	_run()
	return false

func _run() -> void:
	var nav := root.get_node_or_null("Nav")
	var api := root.get_node_or_null("MagiApi")
	if nav == null or api == null:
		push_error("smoke: autoload missing (Nav=%s, MagiApi=%s)" % [nav != null, api != null])
		_exit_code = 1
		return
	var failed := 0
	for key in nav.SCENES.keys():
		var path: String = nav.SCENES[key]
		var packed = load(path)
		if packed == null or not (packed is PackedScene):
			push_error("smoke: load failed: %s" % path)
			failed += 1
			continue
		var inst = packed.instantiate()
		if inst == null:
			push_error("smoke: instantiate failed: %s" % path)
			failed += 1
			continue
		root.add_child(inst)
		var state: Dictionary = api.state()
		if not state.has("_token"):
			push_error("smoke: %s did not refresh MagiApi state" % key)
			failed += 1
		inst.queue_free()
		print("smoke: ok %s (%s)" % [key, path])
	print("smoke: %d screens, %d failed" % [nav.SCENES.size(), failed])
	_exit_code = 1 if failed > 0 else 0
