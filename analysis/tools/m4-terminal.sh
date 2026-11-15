#!/usr/bin/env bash
# M4-Terminaltest (docs/20): Weltbefehle des eigenen Clients (world, chunk, block, nearby, blocks) im Terminalmodus;
# jede "block"-Ausgabe wird serverseitig per "execute if block" mit genau dem gemeldeten Zustand gegengeprüft.
# Aufruf (im Projektwurzelverzeichnis, Server läuft): bash analysis/tools/m4-terminal.sh <label> [spielername]
set -u
LABEL="$1"; NAME="${2:-ProtoBotM4T}"
ROOT="$(pwd)"; OUT="$ROOT/analysis/runtime-logs/m4-$LABEL"; SERVER="$ROOT/server"
mkdir -p "$OUT"; : > "$OUT/client.in"; : > "$OUT/transcript.txt"
# auch bei vorzeitigem Ende: Client beenden und tail -f per SIGPIPE schließen (docs/23 §6)
trap 'echo quit >> "$OUT/client.in"; sleep 3; echo "" >> "$OUT/client.in"' EXIT
log() { echo "$(date +%H:%M:%S.%3N) $*" | tee -a "$OUT/transcript.txt"; }
client_cmd() { log "CLIENT> $1"; echo "$1" >> "$OUT/client.in"; sleep "${2:-1.5}"; }
# Letzte "block"-Antwort des Clients serverseitig prüfen
server_check() {
  local line coords state before
  line=$(grep -E "^  block -?[0-9]+ -?[0-9]+ -?[0-9]+ = " "$OUT/client.out" | tail -1)
  coords=$(echo "$line" | sed -E 's/^  block (-?[0-9]+ -?[0-9]+ -?[0-9]+) = .*/\1/')
  state=$(echo "$line" | sed -E 's/^  block -?[0-9]+ -?[0-9]+ -?[0-9]+ = ([^ ]+) \(state id.*/\1/')
  before=$(wc -l < "$SERVER/logs/latest.log")
  echo "execute if block $coords $state" >> "$SERVER/console.in"; sleep 1.2
  log "    SERVER execute if block $coords $state -> $(tail -n +$((before + 1)) "$SERVER/logs/latest.log" | grep -oE "Test passed|Test failed|not loaded" | head -1)"
}

SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")
log "start label=$LABEL player=$NAME"
: > "$OUT/client.out" # alte "> ready"-Zeile darf das Warten nicht beenden (Wettlauf mit der Umleitung)
( cd "$ROOT/client" && tail -n 0 -f "$OUT/client.in" | ./gradlew -q --console=plain run \
    --args="--interactive --username $NAME --packet-log $(cygpath -w "$OUT/client-packets.log")" > "$OUT/client.out" 2>&1 ) > /dev/null 2>&1 &
# zeitbasiert statt seq-Schleife; Fehler von sleep werden protokolliert (docs/23 §6)
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 90 ]; do grep -q "> ready" "$OUT/client.out" 2>/dev/null && break; sleep 0.5 || echo "sleep failed rc=$?" >&2; done
grep -q "> ready" "$OUT/client.out" || { log "client not ready"; cat "$OUT/client.out"; exit 1; }
log "client ready"; sleep 3

client_cmd "world" 1
client_cmd "position" 1
client_cmd "block ~ ~-1 ~" 1; server_check          # Block unter den Füßen
client_cmd "block ~ ~ ~" 1; server_check            # Luft auf Fußhöhe
client_cmd "block ~3 ~-2 ~-5" 1; server_check
client_cmd "block ~ -64 ~" 1; server_check          # unterste Ebene (Grundgestein)
client_cmd "block ~ 319 ~" 1; server_check          # oberste Ebene
client_cmd "block ~ 320 ~" 1                         # außerhalb der Welthöhe → void_air (lokal, ohne Serverprüfung)
client_cmd "nearby 1" 1
client_cmd "chunk" 1
client_cmd "blocks" 1
client_cmd "status" 1
client_cmd "quit" 4

log "--- client output"; cat "$OUT/client.out" | tee -a "$OUT/transcript.txt" > /dev/null
log "--- server log (joins, warnings)"
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" | grep -E "logged in|lost connection|left the game|kicked|WARN|ERROR" | grep -v "Discovery" | tee -a "$OUT/transcript.txt"
sleep 2; echo "" >> "$OUT/client.in"
log "done"
