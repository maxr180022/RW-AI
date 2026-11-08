#!/usr/bin/env bash
# Проверка целостности проекта и быстрая проверка логики (без Android SDK).
set -euo pipefail
cd "$(dirname "$0")/.."
missing=0
for f in settings.gradle.kts build.gradle.kts gradle.properties gradle/libs.versions.toml gradle/wrapper/gradle-wrapper.jar \
         gradle/wrapper/gradle-wrapper.properties gradlew app/build.gradle.kts app/src/main/AndroidManifest.xml \
         README.md TODO.md docs/ARCHITECTURE.md docs/SECURITY.md docs/PERMISSIONS.md docs/LIMITATIONS.md docs/TEST_PLAN.md \
         docs/USER_GUIDE_RU.md docs/BUILD_AND_INSTALL_RU.md .github/workflows/build-apk.yml; do
  [ -f "$f" ] || { echo "ОТСУТСТВУЕТ: $f"; missing=1; }
done
[ "$missing" -eq 0 ] || exit 1
echo "Структура проекта: OK"
python3 - <<'PY'
import glob, xml.dom.minidom
for f in glob.glob('app/src/main/**/*.xml', recursive=True):
    xml.dom.minidom.parse(f)
print("XML-файлы корректны")
PY
bash scripts/verify-logic.sh "${GRADLE_HOME:-/opt/gradle}"
