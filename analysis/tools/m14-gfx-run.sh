#!/usr/bin/env bash
# M14-Grafiktest (docs/58 §3): erzeugt eine lokale Test-Skin für den Begleiter (ProtoBotM9B, Classic) und startet m6-run.sh mit der
# Szene analysis/tools/m14-gfx.txt (Monster an, Begleiter als zweiter Spieler, --skins mit dem Testordner).
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m14-gfx-run.sh <label> ["zusätzliche Client-Argumente"]
set -u
LABEL="$1"; EXTRA="${2:-}"; ROOT="$(pwd)"; SKINS="$ROOT/analysis/runtime-logs/m6-$LABEL/skins"
mkdir -p "$SKINS"
java "$ROOT/analysis/tools/m14/MakeTestSkin.java" "$SKINS/ProtoBotM9B.png" classic
M6_DIFFICULTY=easy M6_COMPANION=ProtoBotM9B bash "$ROOT/analysis/tools/m6-run.sh" "$ROOT/analysis/tools/m14-gfx.txt" "$LABEL" \
  "--skins $(cygpath -w "$SKINS") --render-distance 4 $EXTRA"
