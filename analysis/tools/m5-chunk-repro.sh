#!/usr/bin/env bash
# Isolierte Reproduktion "keine Chunks für ProtoBotM3" (docs/23 §4): nur Server + eigener Client, 20 s Play.
# Aufruf im Projektwurzelverzeichnis (Server darf nicht laufen): bash analysis/tools/m5-chunk-repro.sh [spieler]
set -u
NAME="${1:-ProtoBotM3}"; ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m5-chunk-repro"
mkdir -p "$OUT"; : > "$OUT/repro.txt"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT/repro.txt"; }
# Testserver friedlich (docs/23 §6): keine feindlichen Mobs; Originalwert wird beim Beenden wiederhergestellt
ORIG_DIFFICULTY=$(grep '^difficulty=' "$SERVER/server.properties" | cut -d= -f2)
restore_difficulty() { sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/" "$SERVER/server.properties"; }
trap restore_difficulty EXIT
sed -i "s/^difficulty=.*/difficulty=peaceful/" "$SERVER/server.properties"
: > "$SERVER/console.in"
( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx1536M -Dlog4j.configurationFile=log4j2-packets.xml \
    -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
for i in $(seq 1 120); do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
log "server: $(grep -o 'Done ([^)]*)' "$SERVER/logs/stdout.txt")"
START=$(wc -l < "$SERVER/logs/latest.log")
( cd "$ROOT/client" && ./gradlew -q --console=plain run --args="--username $NAME --duration 20 --packet-log $(cygpath -w "$OUT/client-packets.log")" ) > "$OUT/client.out" 2>&1
log "client exit=$?"
grep -E "dead|respawn|RESULT|chunks=" "$OUT/client.out" | tee -a "$OUT/repro.txt"
log "login packet: $(grep -o 'clientbound/minecraft:login\] raw -> [0-9]* bytes' "$OUT/client-packets.log")"
log "set_health: $(grep -c 'minecraft:set_health' "$OUT/client-packets.log") client_command: $(grep -c 'minecraft:client_command' "$OUT/client-packets.log") respawn: $(grep -c 'clientbound/minecraft:respawn' "$OUT/client-packets.log") player_loaded: $(grep -c 'minecraft:player_loaded' "$OUT/client-packets.log") chunks: $(grep -c 'level_chunk_with_light' "$OUT/client-packets.log")"
tail -n +$((START + 1)) "$SERVER/logs/latest.log" | grep -E "$NAME|WARN" | grep -v Discovery | tee -a "$OUT/repro.txt"
echo "stop" >> "$SERVER/console.in"
for i in $(seq 1 60); do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 4; echo "" >> "$SERVER/console.in"
log "server stopped"
