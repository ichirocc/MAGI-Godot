#!/usr/bin/env python3
"""APK の起動構成を検証する（CI: godot-ui-check.yml / godot-release-build.yml）。

- 必須エントリ: assets/magi.pck, lib/arm64-v8a/{libgodot_android,libmagi_native,libc++_shared}.so
- .so は非圧縮（STORED）かつローカルヘッダ後のデータ先頭が 16KiB 境界に載っていること
  （Android 15+/16KB ページ端末は APK から直接 mmap するため。AGP 8.5.1+ が整列させる前提の確認）。
- magi.pck は非圧縮（app/build.gradle.kts の noCompress）。

使い方: python3 tools/check_apk_native_libs.py <apk>
"""
import struct
import sys
import zipfile

REQUIRED = [
    "assets/magi.pck",
    "lib/arm64-v8a/libgodot_android.so",
    "lib/arm64-v8a/libmagi_native.so",
    "lib/arm64-v8a/libc++_shared.so",
]
PAGE = 16 * 1024


def data_offset(zf: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    # 中央ディレクトリの extra 長とローカルヘッダの extra 長は一致しない（zipalign はローカル側に詰める）。
    zf.fp.seek(info.header_offset + 26)
    name_len, extra_len = struct.unpack("<HH", zf.fp.read(4))
    return info.header_offset + 30 + name_len + extra_len


def main(path: str) -> int:
    errors = []
    with zipfile.ZipFile(path) as zf:
        names = set(zf.namelist())
        for req in REQUIRED:
            if req not in names:
                errors.append(f"missing {req}")
        for info in zf.infolist():
            if info.filename.startswith("lib/") and info.filename.endswith(".so"):
                off = data_offset(zf, info)
                stored = info.compress_type == zipfile.ZIP_STORED
                aligned = off % PAGE == 0
                print(f"{info.filename}: stored={stored} data_offset={off} mod16k={off % PAGE}")
                if not stored:
                    errors.append(f"{info.filename} is compressed (must be STORED for direct mmap)")
                if not aligned:
                    errors.append(f"{info.filename} data offset {off} is not 16KiB-aligned")
            if info.filename == "assets/magi.pck":
                print(f"{info.filename}: size={info.file_size} stored={info.compress_type == zipfile.ZIP_STORED}")
                if info.compress_type != zipfile.ZIP_STORED:
                    errors.append("assets/magi.pck is compressed (noCompress 'pck' expected)")
    for e in errors:
        print(f"::error::{e}")
    if not errors:
        print(f"OK: {path} launch assets / native libs / 16KiB alignment")
    return 1 if errors else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
