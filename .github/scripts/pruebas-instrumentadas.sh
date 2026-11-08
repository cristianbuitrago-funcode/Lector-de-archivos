#!/usr/bin/env bash
# Pruebas instrumentadas en el emulador ya arrancado, con la comprobación de que cada módulo ejecutó todas las suyas.
#
# Un fallo de una prueba no se repite. Solo se repite, una vez, un módulo que no dejó ningún resultado: eso es un fallo
# del emulador o de ADB al lanzar el ejecutor, no de las pruebas. Si falla algo, se guarda el registro del emulador.
set -u
cd "$(dirname "$0")/../.."
comprobar=".github/scripts/comprobar_pruebas_instrumentadas.py"

fallo() {
  adb logcat -d > emulator-logcat.txt || true
  exit 1
}

./gradlew connectedDebugAndroidTest --stacktrace || fallo

sin_resultados=$(python3 -I "$comprobar" --sin-resultados) || fallo
if [ -n "$sin_resultados" ]; then
  echo "Sin resultados (el ejecutor no llegó a correr): $sin_resultados. Se repiten una vez."
  # shellcheck disable=SC2086 # cada tarea es una palabra
  ./gradlew $sin_resultados --stacktrace || fallo
fi

python3 -I "$comprobar" || fallo
