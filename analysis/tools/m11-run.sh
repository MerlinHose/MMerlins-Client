#!/usr/bin/env bash
# M11-Physikvergleich (docs/45): Vanilla-Referenzclient und eigener Client fahren dieselbe Teststrecke; Tick-für-Tick-Vergleich
# (Position, Geschwindigkeit, Flags, Pose, Sprint-/Schleichzustand, Fluid-/Kletterzustand) und serverseitige Kontrolle.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m11-run.sh <kursdatei> <label> [threshold]
# Speichergrenzen wie M5–M10: Server 1 GB, Vanilla-Client 1,5 GB; Abbruch unter M11_MIN_FREE_MB (Standard 3000) freiem Speicher.
set -u
COURSE_IN="$1"; LABEL="$2"; T="${3:-256}"; ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m11-$LABEL"
COURSE="$(cd "$(dirname "$COURSE_IN")" && pwd)/$(basename "$COURSE_IN")"
mkdir -p "$OUT"; : > "$OUT/run.txt"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT/run.txt"; }
log "=== M11 $LABEL course $COURSE threshold $T"
FREE_MB=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9)
log "free physical memory: $FREE_MB MB"
if [ "${FREE_MB:-0}" -lt "${M11_MIN_FREE_MB:-3000}" ]; then log "ABORT: less than ${M11_MIN_FREE_MB:-3000} MB free"; exit 2; fi
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/memmon.ps1")" -Seconds 1500 -Out "$(cygpath -w "$OUT/memory.txt")" > /dev/null 2>&1 &
ORIG_T=$(grep '^network-compression-threshold=' "$SERVER/server.properties" | cut -d= -f2)
ORIG_DIFFICULTY=$(grep '^difficulty=' "$SERVER/server.properties" | cut -d= -f2)
restore() {
  sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/; s/^network-compression-threshold=.*/network-compression-threshold=$ORIG_T/" "$SERVER/server.properties"
}
trap restore EXIT
sed -i "s/^network-compression-threshold=.*/network-compression-threshold=$T/; s/^difficulty=.*/difficulty=peaceful/" "$SERVER/server.properties"
grep -E "^(network-compression-threshold|online-mode|server-ip|difficulty)=" "$SERVER/server.properties" | tee -a "$OUT/run.txt"

: > "$SERVER/console.in"
( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx${M11_SERVER_XMX:-1G} -Dlog4j.configurationFile=log4j2-packets.xml \
    -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
for i in $(seq 1 120); do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
grep -E "Done \(" "$SERVER/logs/stdout.txt" | tee -a "$OUT/run.txt"
SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 1200 -Out "$(cygpath -w "$OUT/netmon.txt")" > /dev/null 2>&1 &

log "--- Vanilla reference ($COURSE)"
PHYS_COURSE="$COURSE" M5_CLIENT_XMX="${M11_CLIENT_XMX:-1536M}" bash "$ROOT/analysis/tools/m5-reference.sh" "$OUT/reference" > "$OUT/reference.stdout.txt" 2>&1
log "reference exit=$?"
grep -E "END|DONE|trace lines|exit" "$OUT/reference/reference.txt" | tee -a "$OUT/run.txt" > /dev/null
grep -E "DONE|trace lines|exit" "$OUT/reference/reference.txt" | tee -a "$OUT/run.txt"

log "--- own client (PhysicsIntegrationTest, label $LABEL)"
( cd "$ROOT/client" && ./gradlew test --tests 'dev.mcresearch.protocolclient.PhysicsIntegrationTest' -PintegrationTest -PserverDir=../server \
    -PexpectedThreshold="$T" "-Pcourse=$COURSE" "-PphysicsLabel=$LABEL" "-Pphysref=$OUT/reference/physref-trace.txt" --console=plain ) > "$OUT/gradle-test.txt" 2>&1
log "gradle exit=$?"
grep -E "PhysicsIntegrationTest|FAILED|BUILD" "$OUT/gradle-test.txt" | tee -a "$OUT/run.txt"
cp "$ROOT/client/build/$LABEL-own-trace.txt" "$ROOT/client/build/$LABEL-physics-report.txt" "$OUT/" 2>/dev/null
cat "$OUT/$LABEL-physics-report.txt" | tee -a "$OUT/run.txt"

# Fläche wie nach der M5-Strecke zurücksetzen (Boden y=99 Stein, darüber Luft)
if grep -q "^cleanup " "$COURSE"; then grep "^cleanup " "$COURSE" | sed "s/^cleanup //" >> "$SERVER/console.in"; else
  for c in "fill -24 100 -4 40 110 20 minecraft:air" "fill -24 99 -3 40 99 20 minecraft:stone"; do echo "$c" >> "$SERVER/console.in"; done; fi
echo "fill -24 99 -3 40 99 20 minecraft:stone" >> "$SERVER/console.in"
sleep 2
echo "stop" >> "$SERVER/console.in"
for i in $(seq 1 60); do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 5
echo "" >> "$SERVER/console.in"
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$(cygpath -w "$OUT")" | tee -a "$OUT/run.txt"
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" | grep -E "moved too quickly|moved wrongly|lost connection|logged in" | tee -a "$OUT/run.txt"
log "external connections: $(grep -c ' EXTERNAL ' "$OUT/netmon.txt") (TCP to non-loopback); bound UDP endpoints: $(grep -c 'UDP-ENDPOINT' "$OUT/netmon.txt")"
grep 'UDP-ENDPOINT' "$OUT/netmon.txt" | tee -a "$OUT/run.txt"
log "memory peak (project JVMs): $(grep -o 'project=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | tail -1) MB, minimum free: $(grep -o 'free=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | head -1) MB"
(cd "$ROOT/client" && ./gradlew --stop > /dev/null 2>&1); (cd "$ROOT/research" && ./gradlew --stop > /dev/null 2>&1)
log "=== done"
