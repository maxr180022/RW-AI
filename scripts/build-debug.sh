#!/usr/bin/env bash
# Локальная сборка debug APK (нужны JDK 17 и Android SDK: переменная ANDROID_HOME или файл local.properties с sdk.dir).
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --no-daemon testDebugUnitTest assembleDebug
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/RWAI-0.1.0-debug.apk
(cd dist && sha256sum RWAI-0.1.0-debug.apk | tee SHA256SUMS.txt)
echo "Готово: $(pwd)/dist/RWAI-0.1.0-debug.apk"
