#!/usr/bin/env bash
# Локальная проверка платформо-независимой логики агента БЕЗ Android SDK и без интернета.
# Использует компилятор Kotlin и JUnit 4, которые поставляются внутри дистрибутива Gradle.
# Это НЕ замена полной сборки (./gradlew test assembleDebug), а быстрая проверка ядра.
#
# Использование: scripts/verify-logic.sh [путь_к_GRADLE_HOME]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_HOME="${1:-${GRADLE_HOME:-/opt/gradle}}"
LIB="$GRADLE_HOME/lib"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

need() { [ -f "$LIB/$1" ] || { echo "Не найден $LIB/$1 — укажите GRADLE_HOME"; exit 2; }; }
KC="$(ls "$LIB"/kotlin-compiler-embeddable-*.jar | head -1)"
STD="$(ls "$LIB"/kotlin-stdlib-2*.jar | head -1)"
SCR="$(ls "$LIB"/kotlin-script-runtime-*.jar | head -1)"
REF="$(ls "$LIB"/kotlin-reflect-*.jar | head -1)"
COR="$(ls "$LIB"/kotlinx-coroutines-core-jvm-*.jar | head -1)"
TRO="$(ls "$LIB"/trove4j-*.jar | head -1)"
ANN="$(ls "$LIB"/annotations-*.jar | head -1)"
DAE="$(ls "$LIB"/kotlin-daemon-embeddable-*.jar | head -1)"
JUNIT="$(ls "$LIB"/junit-4*.jar | head -1)"
HAM="$(ls "$LIB"/hamcrest-core-*.jar 2>/dev/null | head -1 || true)"
[ -n "$HAM" ] || HAM="$(find "$GRADLE_HOME" -name 'hamcrest-core-*.jar' | head -1)"

COMPILER_CP="$KC:$STD:$SCR:$REF:$COR:$TRO:$ANN:$DAE"
RUN_CP="$STD:$COR:$JUNIT:$HAM"

echo "== Компиляция логики (app/src/main/java/com/rwai/agent/logic)"
set +e
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -cp "$STD:$COR" -d "$OUT/main" \
  "$ROOT"/app/src/main/java/com/rwai/agent/logic/*.kt >"$OUT/c1.log" 2>&1
RC=$?
set -e
grep -v '^Picked up' "$OUT/c1.log" || true
[ "$RC" -eq 0 ] || { echo "Ошибка компиляции логики (код $RC)"; exit 1; }

echo "== Компиляция тестов"
set +e
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -cp "$OUT/main:$STD:$COR:$JUNIT:$HAM" -d "$OUT/test" \
  "$ROOT"/app/src/test/java/com/rwai/agent/logic/*.kt >"$OUT/c2.log" 2>&1
RC=$?
set -e
grep -v '^Picked up' "$OUT/c2.log" || true
[ "$RC" -eq 0 ] || { echo "Ошибка компиляции тестов (код $RC)"; exit 1; }

CLASSES="$(cd "$OUT/test" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort | tr '\n' ' ')"
echo "== Запуск JUnit: $CLASSES"
java -cp "$OUT/main:$OUT/test:$RUN_CP" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v '^Picked up'
