#!/usr/bin/env sh
# Minecraft Merlin Client – Start der Testdistribution (docs/61 §8). Benötigt Java 25; Natives in lib/ sind für Windows (x64).
APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_EXE="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! "$JAVA_EXE" -version >/dev/null 2>&1; then
  echo "Minecraft Merlin Client kann nicht starten: Java wurde nicht gefunden (Java 25 benötigt)." >&2; exit 2
fi
JMAJOR=$("$JAVA_EXE" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
if [ "${JMAJOR:-0}" -lt 25 ]; then
  echo "Minecraft Merlin Client kann nicht starten: Java $JMAJOR gefunden, benötigt wird Java 25 oder neuer." >&2; exit 2
fi
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
exec "$JAVA_EXE" -Xmx768m -XX:MaxDirectMemorySize=256m --enable-native-access=ALL-UNNAMED -cp "$APP_HOME/lib/*" dev.mcresearch.protocolclient.gl.MerlinLauncher "$@"
