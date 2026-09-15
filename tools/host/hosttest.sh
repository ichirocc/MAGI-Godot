#!/bin/bash
# ホスト JVM で v6/model（＋Android 非依存の ui/work）と全 JUnit テストを実コンパイル・実行する。
#   tools/host/hosttest.sh            # このリポジトリ
#   tools/host/hosttest.sh <ROOT>     # 別ツリー（バグ注入検証はこちらに渡す。既定は本体を見る）
# 依存 jar は初回に Maven Central から ~/.cache/magi-host-libs へ落とす（Java 21 が必要。Android SDK は不要）。
# stubs/KigouFormat.kt は android.icu の Transliterator を置き換える JVM 版（表示用の全角→半角）。
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=${1:-$(cd "$HERE/../.." && pwd)}
KV=2.3.21; CV=1.8.1
L=${MAGI_HOST_LIBS:-$HOME/.cache/magi-host-libs}; mkdir -p "$L"
M=https://repo1.maven.org/maven2
get(){ f=$(basename "$1"); [ -s "$L/$f" ] || curl -sSfL -o "$L/$f" "$M/$1" || { echo "download failed: $1"; exit 1; }; }
get org/jetbrains/kotlin/kotlin-compiler-embeddable/$KV/kotlin-compiler-embeddable-$KV.jar
get org/jetbrains/kotlin/kotlin-stdlib/$KV/kotlin-stdlib-$KV.jar
get org/jetbrains/kotlin/kotlin-script-runtime/$KV/kotlin-script-runtime-$KV.jar
get org/jetbrains/kotlin/kotlin-reflect/$KV/kotlin-reflect-$KV.jar
get org/jetbrains/kotlin/kotlin-daemon-embeddable/$KV/kotlin-daemon-embeddable-$KV.jar
get org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar
get org/jetbrains/annotations/13.0/annotations-13.0.jar
get org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/$CV/kotlinx-coroutines-core-jvm-$CV.jar
get junit/junit/4.13.2/junit-4.13.2.jar
get org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
get org/json/json/20240303/json-20240303.jar
OUT=${MAGI_HOST_OUT:-/tmp/magi-hostbuild}; rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
KC="$L/kotlin-compiler-embeddable-$KV.jar:$L/kotlin-stdlib-$KV.jar:$L/kotlin-script-runtime-$KV.jar:$L/kotlin-reflect-$KV.jar:$L/kotlin-daemon-embeddable-$KV.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-13.0.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar"
CP="$L/kotlin-stdlib-$KV.jar:$L/kotlinx-coroutines-core-jvm-$CV.jar:$L/json-20240303.jar:$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar"
A=$ROOT/app/src/main/java/com/magi/app
MAIN_SRC=$(find "$A/v6" "$A/model" -name '*.kt'; ls "$A"/ui/{MagiUiState,AnalysisTriage,BreakdownLabels,ConstraintHelp,VioBuckets}.kt "$A"/work/{RunFiles,SaveGate,OptimizationRepository}.kt "$HERE"/stubs/*.kt)
kotlinc(){ java -Xmx3g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 "$@" 2>&1 | grep -v JAVA_TOOL_OPTIONS; return ${PIPESTATUS[0]}; }
echo "== compile main ($(echo "$MAIN_SRC" | wc -l) files) from $ROOT"
kotlinc -cp "$CP" -d "$OUT/main" $MAIN_SRC | grep -E "^e: |error:|exception" | head -40; [ ${PIPESTATUS[0]} -eq 0 ] || { echo "MAIN COMPILE FAILED"; exit 1; }
TEST_SRC=$(find "$ROOT/app/src/test/java" -name '*.kt')
echo "== compile tests ($(echo "$TEST_SRC" | wc -l) files)"
kotlinc -cp "$CP:$OUT/main" -Xfriend-paths="$OUT/main" -d "$OUT/test" $TEST_SRC | grep -E "^e: |error:|exception" | head -40; [ ${PIPESTATUS[0]} -eq 0 ] || { echo "TEST COMPILE FAILED"; exit 1; }
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' ! -name '*$*' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)
echo "== run $(echo "$CLASSES" | wc -l) test classes"
cd "$ROOT/app" && java -Xmx3g -cp "$CP:$OUT/main:$OUT/test:$ROOT/app/src/test/resources" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -vE "^\s*at |^$|JAVA_TOOL" | tail -8
