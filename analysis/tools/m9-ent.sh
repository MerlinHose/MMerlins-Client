#!/usr/bin/env bash
# M9-Entitytest (docs/37): eigener Client (Terminal) empfängt Entities; nach jeder Aktion wird der Client-Zustand (edump) je UUID mit
# "data get entity <uuid>" des Servers verglichen (m9-compare.js). Ein zweiter Terminalclient dient als "anderer Spieler".
# Lokaler Server, Schwierigkeit easy (Monster nötig; Spieler im Kreativmodus), Kompressionsschwelle 256, Gamerule spawn_mobs
# während des Tests aus und danach wieder auf den vorherigen Wert; Originalkonfiguration wird bei jedem Ende zurückgesetzt.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m9-ent.sh <label> <szenariodatei>
set -u
LABEL="$1"; SCEN="$2"; ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m9-$LABEL"; NAME="ProtoBotM9"; NAME2="ProtoBotM9B"
mkdir -p "$OUT"; : > "$OUT/transcript.txt"; : > "$OUT/client.in"; : > "$OUT/client2.in"
log() { echo "$(date +%H:%M:%S.%3N) $*" | tee -a "$OUT/transcript.txt"; }
SERVER_XMX="${M9_SERVER_XMX:-1G}"; MIN_FREE="${M9_MIN_FREE_MB:-2500}"
FREE_MB=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9)
log "=== M9 $LABEL ($SCEN), free $FREE_MB MB, server -Xmx$SERVER_XMX"
if [ "${FREE_MB:-0}" -lt "$MIN_FREE" ]; then log "ABORT: less than $MIN_FREE MB free"; exit 2; fi
ORIG_DIFFICULTY=$(grep '^difficulty=' "$SERVER/server.properties" | cut -d= -f2)
ORIG_THRESHOLD=$(grep '^network-compression-threshold=' "$SERVER/server.properties" | cut -d= -f2)
restore() {
  echo quit >> "$OUT/client.in"; echo quit >> "$OUT/client2.in"; sleep 2; echo "" >> "$OUT/client.in"; echo "" >> "$OUT/client2.in"
  if ! grep -q "Stopping server" "$SERVER/logs/stdout.txt" 2>/dev/null; then echo "stop" >> "$SERVER/console.in"; sleep 8; fi
  echo "" >> "$SERVER/console.in"
  sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/" "$SERVER/server.properties"
  sed -i "s/^network-compression-threshold=.*/network-compression-threshold=$ORIG_THRESHOLD/" "$SERVER/server.properties"
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$(cygpath -w "$OUT")" > /dev/null 2>&1
}
trap restore EXIT
# Zombies und Skelette gibt es nur ab "easy" (peaceful entfernt sie sofort); beide Spieler sind im Kreativmodus und können nicht sterben
sed -i "s/^difficulty=.*/difficulty=easy/" "$SERVER/server.properties"
sed -i "s/^network-compression-threshold=.*/network-compression-threshold=256/" "$SERVER/server.properties"
grep -E "^(difficulty|network-compression-threshold)=" "$SERVER/server.properties" | tee -a "$OUT/transcript.txt"
: > "$SERVER/console.in"; : > "$SERVER/logs/stdout.txt"
( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx$SERVER_XMX -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery \
    -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
log "$(grep -o 'Done ([^)]*)' "$SERVER/logs/stdout.txt")"
SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 1500 -Out "$(cygpath -w "$OUT/netmon.txt")" > /dev/null 2>&1 &
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/memmon.ps1")" -Seconds 1500 -Out "$(cygpath -w "$OUT/memory.txt")" > /dev/null 2>&1 &

server_cmd() { echo "$1" >> "$SERVER/console.in"; }
# start_client <sitzung> [2]: Client 1 (Beobachter) oder 2 (anderer Spieler)
start_client() {
  local n="${2:-}" name="$NAME"; [ "$n" = 2 ] && name="$NAME2"
  [ -s "$OUT/client$n.out" ] && cp "$OUT/client$n.out" "$OUT/client$n-session-$(( $1 - 1 )).out"
  : > "$OUT/client$n.out"
  ( cd "$ROOT/client" && tail -n 0 -f "$OUT/client$n.in" | ./gradlew -q --console=plain run \
      --args="--interactive --username $name --packet-log $(cygpath -w "$OUT/client$n-packets-$1.log")" >> "$OUT/client$n.out" 2>&1 ) > /dev/null 2>&1 &
  T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "> ready" "$OUT/client$n.out" 2>/dev/null && break; sleep 0.5; done
  grep -q "> ready" "$OUT/client$n.out" || { log "client$n not ready"; exit 1; }
}
# Befehl an Client 1 (bzw. "client2"), danach Marke; Ausgabe bis zur Marke zurückgeben.
client_n() {
  local n="$1" cmd="$2"; local before; before=$(wc -l < "$OUT/client$n.out"); local m="m$(date +%s%N)$RANDOM"
  echo "$cmd" >> "$OUT/client$n.in"; echo "mark $m" >> "$OUT/client$n.in"
  local t0=$SECONDS
  while [ $((SECONDS - t0)) -lt 60 ]; do tail -n +$((before + 1)) "$OUT/client$n.out" | tr -d '\r' | grep -q "^MARK $m\$" && break; sleep 0.1; done
  tail -n +$((before + 1)) "$OUT/client$n.out" | tr -d '\r' | awk -v m="MARK $m" '$0 == m { exit } { print }'
}
client() { client_n "" "$1"; }
client2() { client_n 2 "$1"; }
server_query() {
  local before; before=$(wc -l < "$SERVER/logs/latest.log")
  server_cmd "$1"
  local t0=$SECONDS line=""
  while [ $((SECONDS - t0)) -lt 6 ]; do
    line=$(tail -n +$((before + 1)) "$SERVER/logs/latest.log" | grep -E "has the following|Found no elements|No entity was found|Test (passed|failed)|matching item|No items|Teleported|Summoned|Modified|Gamerule|is currently set" | head -1)
    [ -n "$line" ] && break; sleep 0.1
  done
  echo "$line"
}
STEP=0; OKS=0; FAILS=0
verdict() { if [ "$1" = OK ]; then OKS=$((OKS + 1)); else FAILS=$((FAILS + 1)); fi; log "RESULT $1 $2"; }
# check_entities <name> <typ|all> [felder]: alle Client-Entities des Typs je UUID gegen "data get entity <uuid>"
check_entities() {
  local name="$1" type="$2" fields="${3:-}"; STEP=$((STEP + 1))
  local filter=""; [ "$type" != all ] && filter=" $type"
  client "edump$filter" | grep -E "^  ENT " | sed 's/^  //' > "$OUT/step-$STEP-client.txt"
  : > "$OUT/step-$STEP-server.txt"
  for u in $(awk '{print $3}' "$OUT/step-$STEP-client.txt"); do
    printf '%s\t%s\n' "$u" "$(server_query "data get entity $u")" >> "$OUT/step-$STEP-server.txt"
  done
  local res; res=$(node "$ROOT/analysis/tools/m9-compare.js" entities "$OUT/step-$STEP-client.txt" "$OUT/step-$STEP-server.txt" $fields)
  echo "$res" | sed 's/^/    /' >> "$OUT/transcript.txt"
  local n; n=$(wc -l < "$OUT/step-$STEP-client.txt")
  if echo "$res" | grep -q "^MATCH" && [ "$n" -gt 0 ]; then verdict OK "$name ($type: $(echo "$res" | tail -1))"; else verdict FAIL "$name ($type, $n client entities: $(echo "$res" | tail -1))"; fi
}
# check_count <name> <typ> <x y z> <radius>: Anzahl im Client (edump) = Anzahl auf dem Server im Umkreis
check_count() {
  local name="$1" type="$2" pos="$3" r="$4"
  local c; c=$(client "edump $type" | grep -c "^  ENT ")
  local s; s=$(server_query "execute positioned $pos if entity @e[type=$type,distance=..$r]")
  local sc; sc=$(echo "$s" | grep -io "count: [0-9]*" | grep -o "[0-9]*"); [ -z "$sc" ] && { echo "$s" | grep -q "Test passed" && sc=1 || sc=0; }
  if [ "$c" = "$sc" ]; then verdict OK "$name ($type: client $c = server $sc)"; else verdict FAIL "$name ($type: client $c, server $sc | $s)"; fi
}
# check_client_count <name> <typ> <erwartet>: Anzahl im Client (z. B. 0 nach dem Entladen, während der Server die Entity behält)
check_client_count() {
  local c; c=$(client "edump $2" | grep -c "^  ENT ")
  if [ "$c" = "$3" ]; then verdict OK "$1 ($2: client $c)"; else verdict FAIL "$1 ($2: client $c, expected $3)"; fi
}
expect() {
  local name="$1" pattern="$2" text="$3"
  if echo "$text" | grep -qE "$pattern"; then verdict OK "$name"; else verdict FAIL "$name (expected /$pattern/)"; echo "$text" | sed 's/^/    | /' >> "$OUT/transcript.txt"; fi
}
act() { local out; out=$(client "$1"); log "CLIENT> $1"; echo "$out" | grep -E "^  " | sed 's/^/    /' | tee -a "$OUT/transcript.txt" > /dev/null; LAST="$out"; }

log "--- client"
start_client 1
ORIG_SPAWN=$(server_query "gamerule spawn_mobs" | grep -oE "(true|false)" | tail -1); [ -z "$ORIG_SPAWN" ] && ORIG_SPAWN=true
log "gamerule spawn_mobs was $ORIG_SPAWN"; server_cmd "gamerule spawn_mobs false"
server_cmd "gamemode creative $NAME"; server_cmd "clear $NAME"; server_cmd "tp $NAME 0.5 100 -27.5 180 0"; sleep 2
source "$SCEN"

act "estats"
client "quit" > /dev/null
server_cmd "kill @e[tag=m9]"; server_cmd "gamerule spawn_mobs $ORIG_SPAWN"; sleep 1
log "--- server log"
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" > "$OUT/server-session.log"
grep -E "logged in|lost connection|moved|kicked|invalid|ERROR|Exception" "$OUT/server-session.log" | grep -v "Discovery" | tee -a "$OUT/transcript.txt"
echo "stop" >> "$SERVER/console.in"
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 60 ]; do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 4
log "results: $OKS ok, $FAILS failed"
log "external connections: $(grep -v loopback "$OUT/netmon.txt" | grep -vc 'netmon start')"
log "memory: peak project $(grep -o 'project=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | tail -1) MB, min free $(grep -o 'free=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | head -1) MB"
log "=== done"
