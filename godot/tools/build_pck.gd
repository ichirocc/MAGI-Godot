extends SceneTree
## [Godot移行] godot/ を PCK に固める（godot --headless --path godot --script res://tools/build_pck.gd -- <出力.pck>）。
## `--export-pack` は export templates（約1GB）の導入が前提で CI が重くなるため、PCKPacker で
## res:// 配下（import キャッシュ .godot/imported を含む）をそのまま詰める。事前に `--import` を走らせておくこと。

const SKIP_DIRS := [".godot/editor", ".godot/shader_cache", ".godot/exported"]

var _exit_code := 0

func _initialize() -> void:
	var args := OS.get_cmdline_user_args()
	if args.size() < 1:
		push_error("build_pck: usage: -- <output.pck>")
		_exit_code = 2
		return
	var out_path: String = args[0]
	var files: Array[String] = []
	_walk("res://", files)
	if files.is_empty():
		push_error("build_pck: no files found under res://")
		_exit_code = 1
		return
	var packer := PCKPacker.new()
	var err := packer.pck_start(out_path)
	if err != OK:
		push_error("build_pck: pck_start failed (%d): %s" % [err, out_path])
		_exit_code = 1
		return
	for res_path in files:
		# PCK 内のパスは res:// 相対。ソースは OS パスに直す（FileAccess が読む）。
		err = packer.add_file(res_path, ProjectSettings.globalize_path(res_path))
		if err != OK:
			push_error("build_pck: add_file failed (%d): %s" % [err, res_path])
			_exit_code = 1
			return
	err = packer.flush(false)
	if err != OK:
		push_error("build_pck: flush failed (%d)" % err)
		_exit_code = 1
		return
	print("build_pck: %d files -> %s" % [files.size(), out_path])

func _walk(dir_path: String, out: Array[String]) -> void:
	var dir := DirAccess.open(dir_path)
	if dir == null:
		push_error("build_pck: cannot open %s" % dir_path)
		_exit_code = 1
		return
	dir.include_hidden = true
	dir.list_dir_begin()
	var name := dir.get_next()
	while name != "":
		if name != "." and name != "..":
			var child: String = dir_path.path_join(name)
			var rel: String = child.trim_prefix("res://")
			if dir.current_is_dir():
				if not SKIP_DIRS.has(rel):
					_walk(child, out)
			else:
				out.append(child)
		name = dir.get_next()
	dir.list_dir_end()

func _process(_delta: float) -> bool:
	quit(_exit_code)
	return true
