#!/bin/bash
# godot/ 配下の静的チェック（構文・シーン参照整合性）。Godot本体の実行・ビルドは行わない
# （このサンドボックス環境にGodot 4.5.1実行環境が無いため。実機/CI相当の検証は未実施＝
# docs/godot-ui-migration.md「未実施・未検証」を参照）。
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
GODOT_DIR="$ROOT/godot"
FAIL=0

echo "== GDScriptファイル一覧"
GD_FILES=$(find "$GODOT_DIR" -name '*.gd' | sort)
echo "$GD_FILES" | wc -l

if command -v gdformat >/dev/null 2>&1 || command -v gdlint >/dev/null 2>&1; then
    echo "== gdtoolkit (gdlint) による構文チェック"
    for f in $GD_FILES; do
        if command -v gdlint >/dev/null 2>&1; then
            gdlint "$f" || FAIL=1
        fi
    done
else
    echo "== gdtoolkit未導入: 簡易チェック（括弧対応・タブ/スペース混在の粗検査）へフォールバック"
    for f in $GD_FILES; do
        # 全角文字を含むコメント行を除いた大まかな括弧バランスのみ確認（完全なパーサではない）。
        python3 - "$f" <<'PY' || FAIL=1
import sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
depth = 0
for ch in text:
    if ch in "([{":
        depth += 1
    elif ch in ")]}":
        depth -= 1
if depth != 0:
    print(f"NG: {path} 括弧の対応が取れていません(depth={depth})")
    sys.exit(1)
PY
    done
fi

echo "== シーン(.tscn)がext_resourceで参照するスクリプトの存在確認"
for tscn in $(find "$GODOT_DIR" -name '*.tscn'); do
    grep -oE 'path="res://[^"]+"' "$tscn" | sed 's/path="res:\/\///; s/"$//' | while read -r rel; do
        if [ ! -f "$GODOT_DIR/$rel" ]; then
            echo "NG: $tscn が参照する $rel が存在しません"
            exit 1
        fi
    done || FAIL=1
done

echo "== nav.gd の SCENES が指すシーンファイルの存在確認"
python3 - "$GODOT_DIR" <<'PY' || FAIL=1
import re, sys, os
godot_dir = sys.argv[1]
nav = open(os.path.join(godot_dir, "scripts", "nav.gd"), encoding="utf-8").read()
paths = re.findall(r'"res://scenes/[^"]+\.tscn"', nav)
missing = []
for p in paths:
    rel = p.strip('"').replace("res://", "")
    if not os.path.isfile(os.path.join(godot_dir, rel)):
        missing.append(rel)
if missing:
    print("NG: 見つからないシーン:", missing)
    sys.exit(1)
print(f"OK: {len(paths)} 件のシーン参照を確認")
PY

if [ "$FAIL" -ne 0 ]; then
    echo "== 静的チェック失敗"
    exit 1
fi
echo "== 静的チェック完了（構文/参照整合性のみ。実行・描画・実機動作は未確認）"
