#!/usr/bin/env bash
# Ejecuta SequenceEval (escaneo continuo simulado) en el emulador/móvil y trae los informes a build/eval/.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/d/AndroidStudio/jbr}"
export MSYS_NO_PATHCONV=1
ADB="${ANDROID_HOME:-/d/AndroidSdk}/platform-tools/adb.exe"
PKG=com.hernan.mtgscanner
FILES=/sdcard/Android/data/$PKG/files

./gradlew -q assembleDebug assembleDebugAndroidTest
"$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
"$ADB" shell "rm -rf $FILES/testset/secuencias; mkdir -p $FILES/testset"
"$ADB" push -q testset/secuencias "$FILES/testset/" >/dev/null
"$ADB" shell am instrument -w -e class $PKG.SequenceEval $PKG.test/androidx.test.runner.AndroidJUnitRunner | tail -3

mkdir -p build/eval
for f in eval_secuencias.tsv eval_secuencias_summary.tsv; do "$ADB" pull -q "$FILES/$f" build/eval/; done
column -t -s $'\t' build/eval/eval_secuencias_summary.tsv 2>/dev/null || cat build/eval/eval_secuencias_summary.tsv
