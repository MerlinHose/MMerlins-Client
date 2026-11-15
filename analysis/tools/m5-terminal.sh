#!/usr/bin/env bash
# M5-Terminaltest (docs/23): Physikbefehle des eigenen Clients (walk, jump, physics, collision, hitbox, trajectory);
# nach jeder Bewegung prüft die Serverkonsole die übernommene Position (data get entity … Pos).
# Aufruf (im Projektwurzelverzeichnis, Server läuft): bash analysis/tools/m5-terminal.sh <label> [spielername]
set -u
LABEL="$1"; NAME="${2:-ProtoBotM5T}"
ROOT="$(pwd)"; OUT="$ROOT/analysis/runtime-logs/m5-$LABEL"; SERVER="$ROOT/server"
mkdir -p "$OUT"; : > "$OUT/client.in"; : > "$OUT/transcript.txt"
# auch bei vorzeitigem Ende: Client beenden und tail -f per SIGPIPE schließen (docs/23 §6)
trap 'echo quit >> "$OUT/client.in"; sleep 3; echo "" >> "$OUT/client.in"' EXIT
log() { echo "$(date +%H:%M:%S.%3N) $*" | tee -a "$OUT/transcript.txt"; }
client_cmd() { log "CLIENT> $1"; echo "$1" >> "$OUT/client.in"; sleep "${2:-1.5}"; }
observe() {
  local before; before=$(wc -l < "$SERVER/logs/latest.log")
  echo "data get entity $NAME Pos" >> "$SERVER/console.in"; sleep 1.2
  tail -n +$((before + 1)) "$SERVER/logs/latest.log" | grep -E "has the following entity data" | sed 's/^\[[^]]*\] \[[^]]*\]: /    SERVER: /' | tee -a "$OUT/transcript.txt"
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
# Teststrecke L1 (eben, Kurs aus m5-course.txt): Spieler dorthin, Blick +Z
echo "tp $NAME -20.5 100 -30.5 0 0" >> "$SERVER/console.in"; sleep 2
client_cmd "hitbox" 1
client_cmd "physics" 1
client_cmd "walk f 20" 3; observe
client_cmd "trajectory 6" 1
client_cmd "jump" 2; observe
client_cmd "walk fl 15 jump" 3; observe
client_cmd "collision" 1
client_cmd "physics" 1
client_cmd "move 0 0 -4" 4; observe
client_cmd "status" 1
client_cmd "quit" 4
log "--- client output"; cat "$OUT/client.out" | tee -a "$OUT/transcript.txt" > /dev/null
log "--- server log"
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" | grep -E "logged in|lost connection|moved|kicked|floating|WARN|ERROR" | grep -v "Discovery" | tee -a "$OUT/transcript.txt"
sleep 2; echo "" >> "$OUT/client.in"
log "done"
