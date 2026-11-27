#!/usr/bin/env bash
# Verifies the pure-Kotlin :core module without Gradle/Android SDK (CI fallback and sandbox use):
#   1. compiles core (main + tests) with kotlinc and runs every @Test via tools/jvm-test/MiniRunner
#   2. checks AndroidConst against the real android.jar (inlined compile-time constants)
#   3. applies the SQL migrations to SQLite with FTS5 (tools/verify-schema.py)
#
# Env: KOTLINC (path to kotlinc), JAVA_HOME, ANDROID_JAR (platform android.jar; step 2 skipped if unset).
# The Gradle build runs the same tests with real JUnit 4: ./gradlew :core:test
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"           # apps/shell/android
KOTLINC="${KOTLINC:-kotlinc}"
OUT="${OUT_DIR:-$(mktemp -d)}"
KLIB="$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC" || echo "$KOTLINC")")")")/lib"
TESTLIB="$KLIB/kotlin-test.jar"

echo "▸ compiling :core (main + test) → $OUT"
mapfile -t MAIN < <(find "$ROOT/core/src/main/kotlin" -name '*.kt')
mapfile -t TEST < <(find "$ROOT/core/src/test/kotlin" -name '*.kt')
"$KOTLINC" -jvm-target 21 -nowarn -cp "$TESTLIB" -d "$OUT/classes" \
  "${MAIN[@]}" "${TEST[@]}" "$HERE/jvm-test/org/junit/Test.kt" "$HERE/jvm-test/MiniRunner.kt" 2>&1 | grep -v '^warning:' || true
[ -d "$OUT/classes/com/agentkeyboard/core" ] || { echo "compile failed"; exit 1; }
cp -r "$ROOT/core/src/main/resources/." "$OUT/classes/"

mapfile -t CLASSES < <(cd "$OUT/classes" && find com -name '*Test.class' | sed 's#/#.#g; s#\.class$##' | sort)
echo "▸ running ${#CLASSES[@]} test classes"
CP="$OUT/classes:$TESTLIB:$KLIB/kotlin-stdlib.jar"
(cd "$ROOT" && "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Dstdout.encoding=UTF-8 -cp "$CP" MiniRunnerKt "${CLASSES[@]}")

if [ -n "${ANDROID_JAR:-}" ]; then
  echo "▸ AndroidConst parity against $(basename "$(dirname "$ANDROID_JAR")")/android.jar"
  "$KOTLINC" -jvm-target 21 -nowarn -cp "$OUT/classes:$ANDROID_JAR" -d "$OUT/parity" "$HERE/parity/AndroidConstParity.kt" 2>&1 | grep -v '^warning:' || true
  "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT/parity:$OUT/classes:$KLIB/kotlin-stdlib.jar" AndroidConstParityKt
else
  echo "▸ AndroidConst parity skipped (ANDROID_JAR unset)"
fi

echo "▸ schema"
python3 "$HERE/verify-schema.py" "$ROOT/core/src/main/resources/db/migrations"
