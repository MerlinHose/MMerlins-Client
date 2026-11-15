#!/usr/bin/env bash
# M7-Lichtabgleich (docs/29 §3.3): Der eigene Client berechnet Licht nach Blockänderungen selbst (ClientLightEngine), weil der
# Server light_update nur an Spieler am Sichtweitenrand schickt. Prüfung gegen Vanilla: Änderungen bei geladenem Chunk →
# lightdump (eigene Rechnung) → neu verbinden → lightdump (vom Server berechnetes Licht aus den Chunkpaketen) → Zellvergleich.
# Lokaler Server, friedlich; Originalkonfiguration wird bei jedem Ende zurückgesetzt.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m7-light.sh <label>
set -u
LABEL="$1"; ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m7-$LABEL"; NAME="ProtoBotM7L"
REGION="28 99 -36 46 105 -24"
mkdir -p "$OUT"; : > "$OUT/transcript.txt"
log() { echo "$(date +%H:%M:%S.%3N) $*" | tee -a "$OUT/transcript.txt"; }
ORIG_DIFFICULTY=$(grep '^difficulty=' "$SERVER/server.properties" | cut -d= -f2)
restore() {
  [ -f "$OUT/client.in" ] && { echo quit >> "$OUT/client.in"; sleep 2; echo "" >> "$OUT/client.in"; }
  if ! grep -q "Stopping server" "$SERVER/logs/stdout.txt" 2>/dev/null; then echo "stop" >> "$SERVER/console.in"; sleep 8; fi
  echo "" >> "$SERVER/console.in"
  sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/" "$SERVER/server.properties"
}
trap restore EXIT
sed -i "s/^difficulty=.*/difficulty=peaceful/" "$SERVER/server.properties"
: > "$SERVER/console.in"; : > "$SERVER/logs/stdout.txt"
( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx${M7_SERVER_XMX:-1G} -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery \
    -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
log "$(grep -o 'Done ([^)]*)' "$SERVER/logs/stdout.txt")"
server_cmd() { echo "$1" >> "$SERVER/console.in"; }
client_cmd() { echo "$1" >> "$OUT/client.in"; sleep "${2:-1}"; }
session() { # $1 = Ausgabedatei
  : > "$OUT/client.in"; : > "$OUT/$1"
  ( cd "$ROOT/client" && tail -n 0 -f "$OUT/client.in" | ./gradlew -q --console=plain run --args="--interactive --username $NAME" > "$OUT/$1" 2>&1 ) > /dev/null 2>&1 &
  T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "> ready" "$OUT/$1" 2>/dev/null && break; sleep 0.5; done
  server_cmd "tp $NAME 37.5 100 -22.5 180 0"; sleep 4
}
for c in "forceload add -32 -48 47 31" "fill 28 99 -36 46 99 -24 minecraft:stone" "fill 28 100 -36 46 106 -24 minecraft:air" \
  "setblock 30 100 -30 minecraft:dirt"; do server_cmd "$c"; done
sleep 3
# 1) verbunden: Änderungen, dann eigene Rechnung festhalten
session client-own.out
log "changes while connected (chunks loaded, not at the view-distance edge)"
for c in "setblock 30 100 -29 minecraft:oak_planks" "fill 28 100 -36 46 104 -24 minecraft:air" "setblock 30 100 -30 minecraft:dirt" \
  "fill 30 103 -34 36 103 -30 minecraft:stone" "setblock 33 100 -32 minecraft:torch" "setblock 40 101 -30 minecraft:glowstone" \
  "setblock 42 100 -32 minecraft:smooth_stone_slab[type=top]" "setblock 43 100 -32 minecraft:oak_stairs[facing=east]" \
  "setblock 33 103 -32 minecraft:air" "setblock 38 100 -26 minecraft:sea_lantern" "setblock 38 100 -26 minecraft:air" \
  "fill 44 100 -34 46 102 -32 minecraft:glass" "setblock 45 101 -33 minecraft:lantern" "fill 29 101 -28 31 101 -26 minecraft:oak_leaves[persistent=true]"; do
  server_cmd "$c"; sleep 0.4
done
sleep 3
client_cmd "lightdump $REGION" 3; client_cmd "world" 1; client_cmd "quit" 4; echo "" >> "$OUT/client.in"; sleep 3
# 2) neu verbinden: Licht kommt jetzt vollständig vom Server (Chunkpakete)
session client-server.out
client_cmd "lightdump $REGION" 3; client_cmd "quit" 4
grep "^  L " "$OUT/client-own.out" | sed 's/^  L //' > "$OUT/light-own.txt"
grep "^  L " "$OUT/client-server.out" | sed 's/^  L //' > "$OUT/light-server.txt"
grep -E "client light recomputations|light_update" "$OUT/client-own.out" | tee -a "$OUT/transcript.txt"
paste -d'|' "$OUT/light-own.txt" "$OUT/light-server.txt" | awk -F'|' '{ split($1, a, " "); split($2, b, " ");
  if (a[1] != b[1] || a[2] != b[2] || a[3] != b[3]) { bad++; next }
  if (a[4] == b[4]) skyok++; else { skybad++; if (skybad <= 15) print "SKY  " a[1], a[2], a[3] ": own " a[4] " server " b[4] }
  if (a[5] == b[5]) blkok++; else { blkbad++; if (blkbad <= 15) print "BLOCK " a[1], a[2], a[3] ": own " a[5] " server " b[5] } }
  END { print "cells " NR ", sky equal " skyok+0 " differ " skybad+0 ", block equal " blkok+0 " differ " blkbad+0 (bad ? ", misaligned " bad : "") }' \
  | tee "$OUT/compare.txt" | tee -a "$OUT/transcript.txt"
echo "stop" >> "$SERVER/console.in"
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 60 ]; do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 4
log "=== done"
