#!/usr/bin/env bash
# M5-Vanilla-Referenz (docs/23): Vanilla-Client (Fabric-Forschungsprojekt, runPhysicsRef) fährt die Teststrecke
# analysis/tools/m5-course.txt ab. Dieses Skript baut die Strecke über die Serverkonsole, teleportiert den Spieler zu
# jedem Szenario (Logzeile "READY") und liest nach jedem Szenario die serverseitige Position (data get … Pos).
# Aufruf im Projektwurzelverzeichnis bei laufendem Server: bash analysis/tools/m5-reference.sh <ausgabeverzeichnis>
set -u
OUT="$1"; ROOT="$(pwd)"; SERVER="$ROOT/server"; RUN="$ROOT/research/run-physicsref"; NAME="ResearchRef"
# M11: andere Teststrecke über PHYS_COURSE (Standard: M5-Strecke)
COURSE="${PHYS_COURSE:-$ROOT/analysis/tools/m5-course.txt}"
mkdir -p "$OUT" "$RUN"; : > "$OUT/reference.txt"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT/reference.txt"; }
server_cmd() { echo "$1" >> "$SERVER/console.in"; }

# Strecke aufbauen
grep "^build " "$COURSE" | sed 's/^build //' >> "$SERVER/console.in"; sleep 3
rm -f "$RUN/logs/latest.log" "$RUN/physref-trace.txt"
log "start Vanilla reference client ($NAME)"
( cd "$ROOT/research" && ./gradlew --no-daemon --console=plain -PphysicsRefXmx=${M5_CLIENT_XMX:-2G} "-PphysicsRefCourse=$(cygpath -m "$COURSE")" runPhysicsRef > "$OUT/fabric-gradle.txt" 2>&1 ) &
GPID=$!
seen_ready=0; seen_end=0; seen_staged=0; joined=0; pending_tp=""
for i in $(seq 1 3600); do
  sleep 0.5
  [ -f "$RUN/logs/latest.log" ] || continue
  L="$RUN/logs/latest.log"
  if [ $joined -eq 0 ] && grep -q "\[PHYSREF\] JOINED" "$L"; then joined=1; server_cmd "gamemode survival $NAME"
    # M11: volle Nahrung (Sprinten erst über 6), keine Effekte
    [ -n "${PHYS_COURSE:-}" ] && { server_cmd "effect clear $NAME"; server_cmd "effect give $NAME minecraft:saturation 1 20 true"; server_cmd "effect give $NAME minecraft:fire_resistance 900 0 true"; }
    log "joined"; fi
  n_ready=$(grep -c "\[PHYSREF\] READY" "$L"); n_end=$(grep -c "\[PHYSREF\] END" "$L")
  if [ "$n_end" -gt "$seen_end" ]; then
    seen_end=$n_end; name=$(grep "\[PHYSREF\] END" "$L" | tail -1 | sed -E 's/.*END ([^ ]+) .*/\1/')
    before=$(wc -l < "$SERVER/logs/latest.log"); server_cmd "data get entity $NAME Pos"; sleep 0.4
    log "END $name client=$(grep "\[PHYSREF\] END" "$L" | tail -1 | sed -E 's/.* at //') server=$(tail -n +$((before + 1)) "$SERVER/logs/latest.log" | grep -o "has the following entity data: .*" | head -1 | sed 's/has the following entity data: //')"
  fi
  n_staged=$(grep -c "\[PHYSREF\] STAGED" "$L")
  if [ "$n_ready" -gt "$seen_ready" ]; then
    # Handshake (docs/23 §6): 1) Zwischenpunkt 1 Block hinter dem Start auf festem Boden, 2) Treiber meldet STAGED, 3) Start
    seen_ready=$n_ready
    set -- $(grep "\[PHYSREF\] READY" "$L" | tail -1 | sed -E 's/.*READY //')
    server_cmd "tp $NAME $2 $3 $(awk -v z=$4 'BEGIN{print z-1}') $5 ${6:-0}"; pending_tp="tp $NAME $2 $3 $4 $5 ${6:-0}"; log "READY $1 -> staging"
  fi
  if [ "$n_staged" -gt "$seen_staged" ] && [ -n "$pending_tp" ]; then
    seen_staged=$n_staged; server_cmd "$pending_tp"; log "STAGED -> $pending_tp"; pending_tp=""
  fi
  grep -q "\[PHYSREF\] DONE" "$L" && { log "DONE"; break; }
done
wait $GPID; log "fabric gradle exit=$?"
cp "$RUN/physref-trace.txt" "$OUT/physref-trace.txt" 2>/dev/null
grep -E "PHYSREF|moved|Exception" "$RUN/logs/latest.log" | grep -v "READY\|START" > "$OUT/fabric-physref.log"
log "trace lines: $(grep -vc '^#' "$OUT/physref-trace.txt" 2>/dev/null)"
