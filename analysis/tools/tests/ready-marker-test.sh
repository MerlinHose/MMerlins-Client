#!/usr/bin/env bash
# Test der Bereitschaftserkennung (M15, Regressionsschritt 5): Warteschleife auf "> ready" darf nicht durch den Marker eines
# früheren Laufs vorzeitig enden. Teil 1 stellt den Wettlauf nach (ohne Leeren: vorzeitiges Ende; mit Leeren: korrektes Warten),
# Teil 2 prüft alle Testskripte statisch: wer auf "> ready" in einer Datei wartet, muss sie vor dem Hintergrundstart leeren.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/tests/ready-marker-test.sh   (Exit 0 = bestanden)
set -u
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
fail=0
launch() { # simulierter Client: Subshell leert die Datei per Umleitung verzögert, meldet nach 2 s "> ready"
  # wie im Testskript: Umleitung erst am Befehl hinter cd/Pipe, also verzögert nach dem Start der Subshell
  ( cd / && sleep 0.3 && { echo "starting"; sleep 1.7; echo "> ready"; } > "$1" 2>&1 ) &
}
wait_ready() { local t0=$SECONDS; while [ $((SECONDS - t0)) -lt 10 ]; do grep -q "> ready" "$1" 2>/dev/null && break; sleep 0.1; done; }
# Teil 1a: ohne Leeren endet die Warteschleife sofort (Fehlerbild)
printf 'old run\n> ready\n' > "$TMP/a.out"
start=$(date +%s%N); launch "$TMP/a.out"; wait_ready "$TMP/a.out"; ms=$(( ($(date +%s%N) - start) / 1000000 ))
sleep 0.5 # danach hat die Subshell die Datei geleert: die Kontrollprüfung des Skripts meldet "client not ready"
if [ "$ms" -lt 300 ] && ! grep -q "> ready" "$TMP/a.out"; then echo "OK  reproduced: without truncation the wait ends after ${ms} ms, then the file is emptied (= client not ready)"; else echo "FAIL could not reproduce the early exit (${ms} ms)"; fail=1; fi
wait
# Teil 1b: mit Leeren vor dem Start wird bis zur echten Bereitschaft gewartet
printf 'old run\n> ready\n' > "$TMP/b.out"
start=$(date +%s%N); : > "$TMP/b.out"; launch "$TMP/b.out"; wait_ready "$TMP/b.out"; ms=$(( ($(date +%s%N) - start) / 1000000 ))
if [ "$ms" -ge 1800 ] && grep -q "> ready" "$TMP/b.out"; then echo "OK  truncated file waits for the real ready marker (${ms} ms)"; else echo "FAIL truncated wait ended after ${ms} ms"; fail=1; fi
wait
# Teil 2: statische Prüfung aller Testskripte
cd "$(dirname "$0")/.."
for f in *.sh; do
  grep -q '"> ready"' "$f" || continue
  for file in $(grep -o 'grep -q "> ready" "[^"]*"' "$f" | sed 's/.*"> ready" "//; s/"$//' | sort -u); do
    if grep -qF ": > \"$file\"" "$f"; then echo "OK  $f truncates $file before waiting"; else echo "FAIL $f waits on $file without truncating it first"; fail=1; fi
  done
done
exit $fail
