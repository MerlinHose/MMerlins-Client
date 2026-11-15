#!/usr/bin/env bash
# M3-Testlauf für einen Kompressionsschwellenwert (docs/17).
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m3-run.sh <threshold>
set -u
T="$1"; ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m3-threshold-$T"
mkdir -p "$OUT"; : > "$OUT/run.txt"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT/run.txt"; }

log "=== threshold $T"
sed -i "s/^network-compression-threshold=.*/network-compression-threshold=$T/" "$SERVER/server.properties"
grep -E "^(network-compression-threshold|online-mode|server-ip)=" "$SERVER/server.properties" | tee -a "$OUT/run.txt"

# Server starten (Konsole über console.in)
: > "$SERVER/console.in"
( cd "$SERVER" && tail -n 0 -f console.in | java -Xmx2G -Dlog4j.configurationFile=log4j2-packets.xml \
    -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
for i in $(seq 1 120); do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
grep -E "Starting Minecraft server on|Done \(" "$SERVER/logs/stdout.txt" | tee -a "$OUT/run.txt"
SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")

# Netzwerkmonitor
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 330 -Out "$(cygpath -w "$OUT/netmon.txt")" > /dev/null 2>&1 &

# 1) Unit- + Integrationstests (Status, Login, Configuration, 71 s Play, Keep-Alive, Bewegung, Disconnect, Kompressionszähler)
log "--- gradle test -PintegrationTest -PexpectedThreshold=$T"
( cd "$ROOT/client" && ./gradlew cleanTest test -PintegrationTest -PserverDir=../server -PexpectedThreshold="$T" --console=plain ) > "$OUT/gradle-test.txt" 2>&1
log "gradle exit=$?"
grep -E "LocalServerIntegrationTest|FAILED|BUILD" "$OUT/gradle-test.txt" | tee -a "$OUT/run.txt"
grep -h -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$ROOT"/client/build/test-results/test/*.xml \
  | awk -F'"' '{t+=$2;s+=$4;f+=$6;e+=$8} END {print "junit tests="t" skipped="s" failures="f" errors="e}' | tee -a "$OUT/run.txt"
cp "$ROOT/client/build/test-results/test/TEST-dev.mcresearch.protocolclient.LocalServerIntegrationTest.xml" "$OUT/" 2>/dev/null
cp "$ROOT/client/build/integration-packets.log" "$OUT/integration-packets.log" 2>/dev/null

# 2) Terminal-Bewegungstest mit serverseitiger Beobachtung
log "--- m2-test (terminal)"
bash "$ROOT/analysis/tools/m2-test.sh" "m3-$T" "ProtoBotM3" > /dev/null 2>&1
log "m2-test exit=$?"
grep -E "CLIENT>|SERVER: .*\[|^  (reached|corrected|blocked|claim)|compression threshold|RESULT" "$ROOT/analysis/runtime-logs/m2-m3-$T/transcript.txt" | grep -v OnGround | tee -a "$OUT/run.txt" > /dev/null

# Server stoppen
echo "stop" >> "$SERVER/console.in"
for i in $(seq 1 60); do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 5
log "--- server log (joins, warnings)"
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" | grep -E "logged in|lost connection|moved|kicked|floating|Badly|ERROR|Exception" | grep -v Discovery | tee -a "$OUT/run.txt"
echo "" >> "$SERVER/console.in"
log "=== done threshold $T"
