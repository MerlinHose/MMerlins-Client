#!/usr/bin/env bash
# M2/M3-Bewegungstest (docs/17): steuert den eigenen Client im Terminalmodus und prüft nach jedem Schritt
# serverseitig per Konsole (/data get entity), welche Position und Rotation der Server tatsächlich übernommen hat.
# Aufruf (im Projektwurzelverzeichnis): bash analysis/tools/m2-test.sh <label> [spielername]
set -u
LABEL="$1"; NAME="${2:-ProtoBot}"
ROOT="$(pwd)"; OUT="$ROOT/analysis/runtime-logs/m2-$LABEL"; SERVER="$ROOT/server"
mkdir -p "$OUT"; : > "$OUT/client.in"; : > "$OUT/transcript.txt"
# auch bei vorzeitigem Ende: Client beenden und tail -f per SIGPIPE schließen (docs/23 §6)
trap 'echo quit >> "$OUT/client.in"; sleep 3; echo "" >> "$OUT/client.in"' EXIT
log() { echo "$(date +%H:%M:%S.%3N) $*" | tee -a "$OUT/transcript.txt"; }
server_cmd() { echo "$1" >> "$SERVER/console.in"; }
# Serverseitige Beobachtung: letzte Antwortzeile auf /data get entity <name> <pfad>
observe() {
  local before; before=$(wc -l < "$SERVER/logs/latest.log")
  server_cmd "data get entity $NAME Pos"; server_cmd "data get entity $NAME Rotation"; server_cmd "data get entity $NAME OnGround"
  sleep 1.2
  tail -n +$((before + 1)) "$SERVER/logs/latest.log" | grep -E "has the following entity data|No entity was found" | sed 's/^\[[^]]*\] \[[^]]*\]: /    SERVER: /' | tee -a "$OUT/transcript.txt"
}
client_cmd() { log "CLIENT> $1"; echo "$1" >> "$OUT/client.in"; sleep "${2:-1.5}"; }

SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")
PKT_START=$(wc -l < "$SERVER/logs/packets.log")
log "start label=$LABEL player=$NAME"
: > "$OUT/client.out" # alte "> ready"-Zeile darf das Warten nicht beenden (Wettlauf mit der Umleitung)
( cd "$ROOT/client" && tail -n 0 -f "$OUT/client.in" | ./gradlew -q --console=plain run \
    --args="--interactive --username $NAME --packet-log $(cygpath -w "$OUT/client-packets.log")" > "$OUT/client.out" 2>&1 ) > /dev/null 2>&1 &
# zeitbasiert statt seq-Schleife; Fehler von sleep werden protokolliert (docs/23 §6)
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 90 ]; do grep -q "> ready" "$OUT/client.out" 2>/dev/null && break; sleep 0.5 || echo "sleep failed rc=$?" >&2; done
grep -q "> ready" "$OUT/client.out" || { log "client not ready"; cat "$OUT/client.out"; exit 1; }
log "client ready"

client_cmd "status" 1; observe
client_cmd "look 90 0" 1; observe                     # Rotation: Yaw 90 = Blick nach −X
client_cmd "move 3 0 0" 3; observe                    # Bewegung 1
client_cmd "move 0 0 3" 3; observe                    # Bewegung 2
client_cmd "move -3 0 -3" 4; observe                  # Bewegung 3 (diagonal zurück)
client_cmd "look -45 20" 1; observe                   # Rotation 2 inkl. Pitch
client_cmd "move 0 1 0" 3; observe                    # Sprung (reine Aufwärtsbewegung)
client_cmd "position" 1
client_cmd "claim 0 -2 0" 2; observe                  # TEST: in den Boden → Server muss zurücksetzen
client_cmd "claim 15 0 0" 2; observe                  # TEST: 15 Blöcke in einem Paket → "moved too quickly"
client_cmd "claim 0.1 0 0" 2; observe                 # TEST: kleine plausible Abweichung → akzeptiert
client_cmd "status" 1
log "idle 20 s (Keep-Alive, Positions-Heartbeat)"; sleep 20
client_cmd "status" 1; observe
client_cmd "quit" 4

log "--- client output"; cat "$OUT/client.out" | tee -a "$OUT/transcript.txt" > /dev/null
log "--- server log (warnings, joins)"
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" | grep -E "moved|logged in|lost connection|left the game|kicked|floating|WARN|ERROR" | grep -v "Discovery" | tee -a "$OUT/transcript.txt"
tail -n +$((PKT_START + 1)) "$SERVER/logs/packets.log" > "$OUT/server-packets.log"
sleep 2; echo "" >> "$OUT/client.in"   # beendet tail -f per SIGPIPE, nachdem der Client beendet ist
log "done"
