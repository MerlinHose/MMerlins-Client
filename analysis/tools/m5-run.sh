#!/usr/bin/env bash
# M5-Testlauf (docs/23): Vanilla-Referenz der Teststrecke, alle Unit-/Integrationstests inkl. Physik, Terminaltests.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m5-run.sh <threshold>
set -u
T="$1"; ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m5-threshold-$T"
mkdir -p "$OUT"; : > "$OUT/run.txt"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT/run.txt"; }

log "=== threshold $T"
# Speicher-Vorprüfung (docs/23 §6): Spitze ≈ Server 1,5 GB + Minecraft-Client 2 GB + Gradle 1 GB
FREE_MB=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9)
log "free physical memory: $FREE_MB MB"
if [ "${FREE_MB:-0}" -lt "${M5_MIN_FREE_MB:-2500}" ]; then log "ABORT: less than ${M5_MIN_FREE_MB:-2500} MB free – close other programs or set M5_MIN_FREE_MB"; exit 2; fi
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/memmon.ps1")" -Seconds 1500 -Out "$(cygpath -w "$OUT/memory.txt")" > /dev/null 2>&1 &
MEMMON=$!
sed -i "s/^network-compression-threshold=.*/network-compression-threshold=$T/" "$SERVER/server.properties"
# Testserver friedlich (docs/23 §6): keine feindlichen Mobs; Originalwert wird beim Beenden wiederhergestellt
ORIG_DIFFICULTY=$(grep '^difficulty=' "$SERVER/server.properties" | cut -d= -f2)
restore_difficulty() { sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/" "$SERVER/server.properties"; }
trap restore_difficulty EXIT
sed -i "s/^difficulty=.*/difficulty=peaceful/" "$SERVER/server.properties"
grep -E "^(network-compression-threshold|online-mode|server-ip|difficulty)=" "$SERVER/server.properties" | tee -a "$OUT/run.txt"

# Server starten (Konsole über console.in)
: > "$SERVER/console.in"
( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx${M5_SERVER_XMX:-1536M} -Dlog4j.configurationFile=log4j2-packets.xml \
    -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
for i in $(seq 1 120); do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
grep -E "Starting Minecraft server on|Done \(" "$SERVER/logs/stdout.txt" | tee -a "$OUT/run.txt"
SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")

# Netzwerkmonitor
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 1200 -Out "$(cygpath -w "$OUT/netmon.txt")" > /dev/null 2>&1 &

# 0) Vanilla-Referenz der Teststrecke (Fabric-Client)
log "--- m5-reference (Vanilla client)"
bash "$ROOT/analysis/tools/m5-reference.sh" "$OUT/reference" > "$OUT/m5-reference.stdout.txt" 2>&1
log "m5-reference exit=$?"
grep -E "END|DONE|trace lines|exit" "$OUT/reference/reference.txt" | tee -a "$OUT/run.txt"

# 1) Unit- + Integrationstests (Status, Login, Configuration, 71 s Play, Keep-Alive, Bewegung, Disconnect, Kompressionszähler)
log "--- gradle test -PintegrationTest -PexpectedThreshold=$T"
( cd "$ROOT/client" && ./gradlew cleanTest test -PintegrationTest -PserverDir=../server -PexpectedThreshold="$T" -Pphysref="$OUT/reference/physref-trace.txt" --console=plain ) > "$OUT/gradle-test.txt" 2>&1
log "gradle exit=$?"
grep -E "IntegrationTest|FAILED|BUILD" "$OUT/gradle-test.txt" | tee -a "$OUT/run.txt"
grep -h -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$ROOT"/client/build/test-results/test/*.xml \
  | awk -F'"' '{t+=$2;s+=$4;f+=$6;e+=$8} END {print "junit tests="t" skipped="s" failures="f" errors="e}' | tee -a "$OUT/run.txt"
cp "$ROOT/client/build/test-results/test/TEST-dev.mcresearch.protocolclient.LocalServerIntegrationTest.xml" "$OUT/" 2>/dev/null
cp "$ROOT/client/build/integration-packets.log" "$OUT/integration-packets.log" 2>/dev/null

# 2) Terminal-Bewegungstest mit serverseitiger Beobachtung
log "--- m2-test (terminal)"
bash "$ROOT/analysis/tools/m2-test.sh" "m5-$T" "ProtoBotM3" > "$OUT/m2-test.stdout.txt" 2>&1
log "m2-test exit=$?"
grep -E "CLIENT>|SERVER: .*\[|^  (reached|corrected|blocked|claim)|compression threshold|RESULT" "$ROOT/analysis/runtime-logs/m2-m5-$T/transcript.txt" | grep -v OnGround | tee -a "$OUT/run.txt" > /dev/null

cp "$ROOT/client/build/test-results/test/TEST-dev.mcresearch.protocolclient.WorldIntegrationTest.xml" "$OUT/" 2>/dev/null
cp "$ROOT/client/build/m4-world-report.txt" "$OUT/" 2>/dev/null
grep -E "confirmed|negative control|unloaded after|stats:|after changes" "$OUT/m4-world-report.txt" | tee -a "$OUT/run.txt"

# 3) M4-Terminaltest der Weltbefehle mit serverseitiger Gegenprüfung
log "--- m4-terminal"
bash "$ROOT/analysis/tools/m4-terminal.sh" "m5-terminal-$T" "ProtoBotM4T" > "$OUT/m4-terminal.stdout.txt" 2>&1
log "m4-terminal exit=$?"
grep -E "CLIENT>|SERVER execute" "$ROOT/analysis/runtime-logs/m4-m5-terminal-$T/transcript.txt" | tee -a "$OUT/run.txt"

cp "$ROOT/client/build/m5-physics-report.txt" "$ROOT/client/build/m5-own-trace.txt" "$OUT/" 2>/dev/null
cp "$ROOT/client/build/test-results/test/TEST-dev.mcresearch.protocolclient.PhysicsIntegrationTest.xml" "$OUT/" 2>/dev/null
cat "$OUT/m5-physics-report.txt" | tee -a "$OUT/run.txt"

# 4) M5-Terminaltest der Physikbefehle
log "--- m5-terminal"
bash "$ROOT/analysis/tools/m5-terminal.sh" "terminal-$T" "ProtoBotM5T" > "$OUT/m5-terminal.stdout.txt" 2>&1
log "m5-terminal exit=$?"
grep -E "CLIENT>|SERVER:" "$ROOT/analysis/runtime-logs/m5-terminal-$T/transcript.txt" | tee -a "$OUT/run.txt"

# Server stoppen
echo "stop" >> "$SERVER/console.in"
for i in $(seq 1 60); do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 5
log "--- server log (joins, warnings)"
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" | grep -E "logged in|lost connection|moved|kicked|floating|Badly|ERROR|Exception|was slain|was shot|was killed|died|fell|drowned|suffocated|DIED" | grep -v Discovery | tee -a "$OUT/run.txt"
echo "" >> "$SERVER/console.in"
# Monitore dieses Laufs beenden (eindeutig über das Ausgabeverzeichnis in der Kommandozeile)
OUTWIN="$(cygpath -w "$OUT")"
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$OUTWIN" | tee -a "$OUT/run.txt"
log "memory peak (project JVMs): $(grep -o 'project=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | tail -1) MB, minimum free: $(grep -o 'free=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | head -1) MB"
log "=== done threshold $T"
