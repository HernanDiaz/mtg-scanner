#!/usr/bin/env bash
# Evalúa el reconocimiento en el emulador/móvil conectado y trae los informes a build/eval/.
# Uso (Git Bash):  tools/run_eval.sh [subconjunto ...]
#   Subconjuntos = carpetas de testset/ (synthetic, basicas, espanol, ingles, real...).
#   Sin argumentos: synthetic basicas espanol real.
#   "real" se evalúa sin marco guía (son fotos sin encuadrar).
#   ART=false tools/run_eval.sh ...  evalúa sin comparar ilustraciones (ni símbolos).
#   SYMBOLS=false tools/run_eval.sh ...  evalúa sin comparar símbolos de edición.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/d/AndroidStudio/jbr}"
export MSYS_NO_PATHCONV=1   # que Git Bash no convierta las rutas del dispositivo
ADB="${ANDROID_HOME:-/d/AndroidSdk}/platform-tools/adb.exe"
PKG=com.hernan.mtgscanner
FILES=/sdcard/Android/data/$PKG/files

SUBSETS=("$@")
[ ${#SUBSETS[@]} -eq 0 ] && SUBSETS=(synthetic basicas espanol real)

./gradlew -q assembleDebug assembleDebugAndroidTest
"$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

mkdir -p build/eval
for s in "${SUBSETS[@]}"; do
  # Copia las fotos solo si faltan o cambiaron (labels.csv distinto: otro conjunto generado).
  local_sum=$(md5sum < "testset/$s/labels.csv" | cut -d' ' -f1)
  # Si aún no está en el dispositivo, el md5 falla: cuenta como distinto.
  remote_sum=$("$ADB" shell "md5sum $FILES/testset/$s/labels.csv 2>/dev/null" | tr -d '\r' | cut -d' ' -f1 || true)
  if [ "$local_sum" != "$remote_sum" ]; then
    echo "Copiando testset/$s ($(ls "testset/$s" | wc -l) ficheros)..."
    "$ADB" shell "rm -rf $FILES/testset/$s; mkdir -p $FILES/testset"
    "$ADB" push -q "testset/$s" "$FILES/testset/" >/dev/null
  fi
  guide=true; [ "$s" = real ] && guide=false
  "$ADB" shell am instrument -w -e class $PKG.RecognitionEval -e subset "$s" -e guide $guide -e art "${ART:-true}" -e symbols "${SYMBOLS:-true}" \
    $PKG.test/androidx.test.runner.AndroidJUnitRunner | tail -1
  for f in eval_$s.tsv eval_${s}_summary.tsv; do "$ADB" pull -q "$FILES/$f" build/eval/; done
  rm -rf "build/eval/symwin_$s"; "$ADB" pull -q "$FILES/symwin_$s" build/eval/ 2>/dev/null || true
  echo "== $s"; grep -v ":" "build/eval/eval_${s}_summary.tsv"
done
