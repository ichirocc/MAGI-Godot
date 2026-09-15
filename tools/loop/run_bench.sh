#!/bin/bash
# 自律改善ループのペア比較ベンチ（tools/loop/LoopBench.kt）をホスト JVM で走らせる。
#   tools/host/hosttest.sh                       # 先にエンジンを /tmp/magi-hostbuild へビルド（MAGI_HOST_OUT で変更可）
#   tools/loop/run_bench.sh results/iter2.csv [seeds=10] [caseプレフィックス=""]
#                                                # 30 合成ケース＋4 実データ × seed × 新旧 2 腕 → CSV（10 seed で約 2 時間）
#   python3 tools/loop/gate.py results/iter2.csv # 辞書式比較とゲート判定
# ベンチ中は同じ出力先を再ビルドしない（クラスファイルが差し替わり結果が汚れる）。
# [3.507.6] 出力 CSV が既にあれば済みの (case,seed,arm) を飛ばして追記＝途中で JVM が消えても同じコマンドで再開できる。
set -e
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
HOSTOUT=${MAGI_HOST_OUT:-/tmp/magi-hostbuild}
L=${MAGI_HOST_LIBS:-$HOME/.cache/magi-host-libs}; KV=2.3.21; CV=1.8.1
KC="$L/kotlin-compiler-embeddable-$KV.jar:$L/kotlin-stdlib-$KV.jar:$L/kotlin-script-runtime-$KV.jar:$L/kotlin-reflect-$KV.jar:$L/kotlin-daemon-embeddable-$KV.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-13.0.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar"
CP="$L/kotlin-stdlib-$KV.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar:$L/json-20240303.jar:$HOSTOUT/main"
OUT=$HOSTOUT/loopbench; rm -rf "$OUT"; mkdir -p "$OUT"
java -Xmx2g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 -Xfriend-paths="$HOSTOUT/main" -cp "$CP" -d "$OUT" "$HERE/LoopBench.kt" 2>&1 | grep -E "^e: |error:" && exit 1
java -Xmx3g -Dfile.encoding=UTF-8 -cp "$CP:$OUT" probe.LoopBenchKt "${1:-$HERE/results/out.csv}" "${2:-10}" "${3:-}" "$ROOT/app/src/test/resources" 2>&1 | grep --line-buffered -v JAVA_TOOL
