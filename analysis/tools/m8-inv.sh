#!/usr/bin/env bash
# M8-Inventartest (docs/33): eigener Client (Terminal) bedient Inventar, Truhe und Werkbank; nach jeder Aktion werden die
# tatsächlichen ItemStacks des Servers (data get entity … Inventory/equipment, data get block … Items) mit dem Client-Modell
# verglichen (m8-compare.js). Lokaler Server, friedlich; Originalkonfiguration wird bei jedem Ende zurückgesetzt.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m8-inv.sh <label> <szenariodatei>
set -u
LABEL="$1"; SCEN="$2"; ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m8-$LABEL"; NAME="ProtoBotM8"
mkdir -p "$OUT"; : > "$OUT/transcript.txt"; : > "$OUT/client.in"
log() { echo "$(date +%H:%M:%S.%3N) $*" | tee -a "$OUT/transcript.txt"; }
SERVER_XMX="${M8_SERVER_XMX:-1G}"; MIN_FREE="${M8_MIN_FREE_MB:-2500}"
FREE_MB=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9)
log "=== M8 $LABEL ($SCEN), free $FREE_MB MB, server -Xmx$SERVER_XMX"
if [ "${FREE_MB:-0}" -lt "$MIN_FREE" ]; then log "ABORT: less than $MIN_FREE MB free"; exit 2; fi
ORIG_DIFFICULTY=$(grep '^difficulty=' "$SERVER/server.properties" | cut -d= -f2)
restore() {
  echo quit >> "$OUT/client.in"; sleep 2; echo "" >> "$OUT/client.in"
  if ! grep -q "Stopping server" "$SERVER/logs/stdout.txt" 2>/dev/null; then echo "stop" >> "$SERVER/console.in"; sleep 8; fi
  echo "" >> "$SERVER/console.in"
  sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/" "$SERVER/server.properties"
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$(cygpath -w "$OUT")" > /dev/null 2>&1
}
trap restore EXIT
sed -i "s/^difficulty=.*/difficulty=peaceful/" "$SERVER/server.properties"
: > "$SERVER/console.in"; : > "$SERVER/logs/stdout.txt"
( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx$SERVER_XMX -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery \
    -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
log "$(grep -o 'Done ([^)]*)' "$SERVER/logs/stdout.txt")"
SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 1500 -Out "$(cygpath -w "$OUT/netmon.txt")" > /dev/null 2>&1 &
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/memmon.ps1")" -Seconds 1500 -Out "$(cygpath -w "$OUT/memory.txt")" > /dev/null 2>&1 &

server_cmd() { echo "$1" >> "$SERVER/console.in"; }
start_client() {
  [ -s "$OUT/client.out" ] && cp "$OUT/client.out" "$OUT/client-session-$(( $1 - 1 )).out"  # Ausgabe früherer Sitzungen behalten
  : > "$OUT/client.out"
  ( cd "$ROOT/client" && tail -n 0 -f "$OUT/client.in" | ./gradlew -q --console=plain run \
      --args="--interactive --username $NAME --packet-log $(cygpath -w "$OUT/client-packets-$1.log")" >> "$OUT/client.out" 2>&1 ) > /dev/null 2>&1 &
  T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "> ready" "$OUT/client.out" 2>/dev/null && break; sleep 0.5; done
  grep -q "> ready" "$OUT/client.out" || { log "client not ready"; exit 1; }
}
# Befehl an den Client, danach "mark <eindeutig>"; warten, bis die Marke erscheint (höchstens 60 s); Ausgabe dazwischen zurückgeben.
# Läuft oft in einer Subshell ($(...)): daher Marke aus Zeit und Zufall statt Zähler.
client() {
  local before; before=$(wc -l < "$OUT/client.out"); local m="m$(date +%s%N)$RANDOM"
  echo "$1" >> "$OUT/client.in"; echo "mark $m" >> "$OUT/client.in"
  local t0=$SECONDS
  while [ $((SECONDS - t0)) -lt 60 ]; do tail -n +$((before + 1)) "$OUT/client.out" | tr -d '\r' | grep -q "^MARK $m\$" && break; sleep 0.1; done
  tail -n +$((before + 1)) "$OUT/client.out" | tr -d '\r' | awk -v m="MARK $m" '$0 == m { exit } { print }'
}
server_query() {
  # Konsole liest console.in verzögert: auf die eigene Antwort warten (höchstens 6 s), erst dann zurückkehren
  local before; before=$(wc -l < "$SERVER/logs/latest.log")
  server_cmd "$1"
  local t0=$SECONDS line=""
  while [ $((SECONDS - t0)) -lt 6 ]; do
    line=$(tail -n +$((before + 1)) "$SERVER/logs/latest.log" | grep -E "has the following|Found no elements|No entity was found|Test (passed|failed)|matching item|No items|No block entity|is not a container" | head -1)
    [ -n "$line" ] && break; sleep 0.1
  done
  echo "$line"
}
STEP=0; OKS=0; FAILS=0
verdict() { if [ "$1" = OK ]; then OKS=$((OKS + 1)); else FAILS=$((FAILS + 1)); fi; log "RESULT $1 $2"; }
# Spielerinventar vergleichen (alle 41 Indizes)
check_player() {
  local name="$1"; STEP=$((STEP + 1))
  client "invdump" 0.6 | grep -E "^  (INV|CURSOR) " | sed 's/^  //' > "$OUT/step-$STEP-client.txt"
  server_query "data get entity $NAME Inventory" > "$OUT/step-$STEP-server-inv.txt"
  server_query "data get entity $NAME equipment" > "$OUT/step-$STEP-server-eq.txt"
  local res; res=$(node "$ROOT/analysis/tools/m8-compare.js" player "$OUT/step-$STEP-client.txt" "$OUT/step-$STEP-server-inv.txt" "$OUT/step-$STEP-server-eq.txt")
  echo "$res" | sed 's/^/    /' >> "$OUT/transcript.txt"
  if echo "$res" | grep -q "^MATCH"; then verdict OK "$name (player inventory: $(echo "$res" | tail -1))"; else verdict FAIL "$name (player inventory: $(echo "$res" | tail -1))"; fi
}
# Container-Plätze gegen den Block vergleichen
check_container() {
  local name="$1" pos="$2"; STEP=$((STEP + 1))
  client "cdump" 0.6 | grep -E "^  (SLOT|CONTAINER) " | sed 's/^  //' > "$OUT/step-$STEP-client.txt"
  server_query "data get block $pos Items" > "$OUT/step-$STEP-server-items.txt"
  local res; res=$(node "$ROOT/analysis/tools/m8-compare.js" container "$OUT/step-$STEP-client.txt" "$OUT/step-$STEP-server-items.txt")
  echo "$res" | sed 's/^/    /' >> "$OUT/transcript.txt"
  if echo "$res" | grep -q "^MATCH"; then verdict OK "$name (container $pos: $(echo "$res" | tail -1))"; else verdict FAIL "$name (container $pos: $(echo "$res" | tail -1))"; fi
}
# große Truhe: Client-Plätze 0–53 gegen zwei Blöcke (Reihenfolge wie die Doppeltruhe sie bildet)
check_container2() {
  local name="$1" posA="$2" posB="$3"; STEP=$((STEP + 1))
  client "cdump" 0.6 | grep -E "^  (SLOT|CONTAINER) " | sed "s/^  //" > "$OUT/step-$STEP-client.txt"
  server_query "data get block $posA Items" > "$OUT/step-$STEP-server-a.txt"
  server_query "data get block $posB Items" > "$OUT/step-$STEP-server-b.txt"
  local res; res=$(node "$ROOT/analysis/tools/m8-compare.js" container2 "$OUT/step-$STEP-client.txt" "$OUT/step-$STEP-server-a.txt" "$OUT/step-$STEP-server-b.txt")
  echo "$res" | sed "s/^/    /" >> "$OUT/transcript.txt"
  if echo "$res" | grep -q "^MATCH"; then verdict OK "$name (large chest $posA + $posB: $(echo "$res" | tail -1))"; else verdict FAIL "$name (large chest: $(echo "$res" | tail -1))"; fi
}
# Erwartung an die Client-Ausgabe (z. B. Cursor nach einem Klick)
expect() {
  local name="$1" pattern="$2" text="$3"
  if echo "$text" | grep -qE "$pattern"; then verdict OK "$name"; else verdict FAIL "$name (expected /$pattern/)"; echo "$text" | sed 's/^/    | /' >> "$OUT/transcript.txt"; fi
}
act() { local out; out=$(client "$1" "${2:-0.9}"); log "CLIENT> $1"; echo "$out" | grep -E "^  " | sed 's/^/    /' | tee -a "$OUT/transcript.txt" > /dev/null; LAST="$out"; }

log "--- client"
start_client 1
server_cmd "gamemode survival $NAME"; server_cmd "clear $NAME"; server_cmd "tp $NAME 0.5 100 -27.5 180 0"; sleep 2
# Szenario einlesen (Bash-Funktionen act/check_*/server_cmd/expect)
source "$SCEN"

client "quit" 3 > /dev/null
log "--- server log"
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" > "$OUT/server-session.log"
grep -E "logged in|lost connection|moved|kicked|mismatch|Mismatch|invalid|ERROR|Exception" "$OUT/server-session.log" | grep -v "Discovery\|Removed" | tee -a "$OUT/transcript.txt"
echo "stop" >> "$SERVER/console.in"
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 60 ]; do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 4
log "results: $OKS ok, $FAILS failed"
log "external connections: $(grep -v loopback "$OUT/netmon.txt" | grep -vc 'netmon start')"
log "memory: peak project $(grep -o 'project=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | tail -1) MB, min free $(grep -o 'free=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | head -1) MB"
log "=== done"
