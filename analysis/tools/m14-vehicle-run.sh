#!/usr/bin/env bash
# M14-Fahrzeugvergleich (docs/58 §4): Vanilla-Referenzclient (Fabric, VehicleReferenceClient) und eigener Client
# (VehicleIntegrationTest) fahren dieselben Fahrzeugszenarien auf dem lokalen Vanilla-Server; Tick-für-Tick-Vergleich der
# Fahrzeugposition und serverseitige Kontrolle (Fahrzeugposition nach dem Szenario).
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m14-vehicle-run.sh <label> [threshold]
# Speichergrenzen wie M5–M13: Server 1 GB, Vanilla-Client 1,5 GB; Abbruch unter M14_MIN_FREE_MB (Standard 3000) freiem Speicher.
set -u
LABEL="$1"; T="${2:-256}"; ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m14-$LABEL"
COURSE="$ROOT/analysis/tools/m14-vehicle-course.txt"; RUN="$ROOT/research/run-vehicleref"; REF="ResearchVeh"
mkdir -p "$OUT" "$RUN"; : > "$OUT/run.txt"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT/run.txt"; }
server_cmd() { echo "$1" >> "$SERVER/console.in"; }
log "=== M14 vehicles $LABEL threshold $T"
FREE_MB=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9)
log "free physical memory: $FREE_MB MB"
if [ "${FREE_MB:-0}" -lt "${M14_MIN_FREE_MB:-3000}" ]; then log "ABORT: less than ${M14_MIN_FREE_MB:-3000} MB free"; exit 2; fi
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
( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx${M14_SERVER_XMX:-1G} -Dlog4j.configurationFile=log4j2-packets.xml \
    -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
for i in $(seq 1 120); do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
grep -E "Done \(" "$SERVER/logs/stdout.txt" | tee -a "$OUT/run.txt"
SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 1500 -Out "$(cygpath -w "$OUT/netmon.txt")" > /dev/null 2>&1 &
grep "^build " "$COURSE" | sed 's/^build //' >> "$SERVER/console.in"; sleep 4
server_cmd "kill @e[tag=m14v]"; server_cmd "kill @e[tag=m14o]"

# ---- Vanilla-Referenz ----
log "--- Vanilla reference"
rm -f "$RUN/logs/latest.log" "$RUN/vehref-trace.txt"
( cd "$ROOT/research" && ./gradlew --no-daemon --console=plain -PvehicleRefXmx=${M14_CLIENT_XMX:-1536M} "-PvehicleRefCourse=$(cygpath -m "$COURSE")" runVehicleRef > "$OUT/fabric-gradle.txt" 2>&1 ) &
GPID=$!
seen_ready=0; seen_end=0; joined=0
for i in $(seq 1 4800); do
  kill -0 $GPID 2>/dev/null || break
  [ $joined -eq 0 ] && [ $i -eq 600 ] && log "WARN: reference client not joined after 300 s (Startbildschirm? options.txt in research/run-vehicleref prüfen)"
  sleep 0.5
  [ -f "$RUN/logs/latest.log" ] || continue
  L="$RUN/logs/latest.log"
  if [ $joined -eq 0 ] && grep -q "\[VEHREF\] JOINED" "$L"; then joined=1; server_cmd "gamemode creative $REF"; log "joined"; fi
  n_end=$(grep -c "\[VEHREF\] END" "$L")
  if [ "$n_end" -gt "$seen_end" ]; then
    seen_end=$n_end; name=$(grep "\[VEHREF\] END" "$L" | tail -1 | sed -E 's/.*END ([^ ]+) .*/\1/')
    before=$(wc -l < "$SERVER/logs/latest.log"); server_cmd "data get entity @e[tag=m14v,limit=1] Pos"; sleep 0.4
    log "END $name client=$(grep "\[VEHREF\] END" "$L" | tail -1 | sed -E 's/.* at //') server=$(tail -n +$((before + 1)) "$SERVER/logs/latest.log" | grep -o "has the following entity data: .*" | head -1 | sed 's/has the following entity data: //')"
    server_cmd "ride $REF dismount"; server_cmd "kill @e[tag=m14v]"; server_cmd "kill @e[tag=m14o]"
  fi
  n_ready=$(grep -c "\[VEHREF\] READY" "$L")
  if [ "$n_ready" -gt "$seen_ready" ]; then
    seen_ready=$n_ready
    set -- $(grep "\[VEHREF\] READY" "$L" | tail -1 | sed -E 's/.*READY //')
    server_cmd "kill @e[tag=m14v]"; server_cmd "kill @e[tag=m14o]"
    # Hindernisse des Szenarios (obstacle <szenario> <typ> <x> <y> <z> <gier>)
    grep "^obstacle $1 " "$COURSE" | while read -r _ _ ot ox oy oz oyaw; do
      server_cmd "summon $ot $ox $oy $oz {Rotation:[${oyaw}f,0f],Tags:[m14o]}"
    done
    if [ "$2" = "walk" ]; then
      server_cmd "tp $REF $3 $4 $5 $6 0"
      log "READY $1 -> walk (tp)"
    else
      nbt=$(grep "^vehicle-nbt $2 " "$COURSE" | sed -E "s/^vehicle-nbt $2 //"); [ -z "$nbt" ] && nbt="{Tags:[m14v]}"
      nbt=$(echo "$nbt" | sed -E "s/^\{/{Rotation:[${6}f,0f],/")
      server_cmd "tp $REF $3 $(awk -v y=$4 'BEGIN{print y+2}') $(awk -v z=$5 'BEGIN{print z-3}')"; sleep 0.8
      server_cmd "summon $2 $3 $4 $5 $nbt"; server_cmd "ride $REF mount @e[tag=m14v,limit=1]"
      log "READY $1 -> summon $2 + ride"
    fi
  fi
  grep -q "\[VEHREF\] DONE" "$L" && { log "DONE"; break; }
done
wait $GPID; log "fabric gradle exit=$?"
cp "$RUN/vehref-trace.txt" "$OUT/vehref-trace.txt" 2>/dev/null
grep -E "VEHREF|moved|Exception" "$RUN/logs/latest.log" > "$OUT/fabric-vehref.log"
log "reference trace lines: $(grep -vc '^#' "$OUT/vehref-trace.txt" 2>/dev/null)"
server_cmd "kill @e[tag=m14v]"; sleep 1

# ---- eigener Client ----
log "--- own client (VehicleIntegrationTest)"
( cd "$ROOT/client" && ./gradlew test --tests 'dev.mcresearch.protocolclient.VehicleIntegrationTest' -PintegrationTest -PserverDir=../server \
    -PexpectedThreshold="$T" "-Pcourse=$COURSE" "-Pphysref=$OUT/vehref-trace.txt" --console=plain ) > "$OUT/gradle-test.txt" 2>&1
log "gradle exit=$?"
grep -E "VehicleIntegrationTest|FAILED|BUILD" "$OUT/gradle-test.txt" | tee -a "$OUT/run.txt"
cp "$ROOT/client/build/m14-vehicle-own-trace.txt" "$ROOT/client/build/m14-vehicle-report.txt" "$OUT/" 2>/dev/null
cat "$OUT/m14-vehicle-report.txt" | tee -a "$OUT/run.txt"

# ---- Aufräumen: Strecke entfernen, Server stoppen ----
server_cmd "kill @e[tag=m14v]"; server_cmd "kill @e[tag=m14o]"
for zz in "98 136" "137 175" "176 214" "215 222"; do set -- $zz; server_cmd "fill 98 60 $1 172 70 $2 minecraft:air"; done; server_cmd "fill 98 59 98 172 59 222 minecraft:stone"; server_cmd "forceload remove 96 96 175 223"
sleep 2
server_cmd "stop"
for i in $(seq 1 60); do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 5
echo "" >> "$SERVER/console.in"
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$(cygpath -w "$OUT")" | tee -a "$OUT/run.txt"
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" > "$OUT/server-session.log"
grep -E "moved too quickly|moved wrongly|lost connection|logged in|kicked" "$OUT/server-session.log" | tee -a "$OUT/run.txt"
log "external connections: $(grep -c ' EXTERNAL ' "$OUT/netmon.txt") (TCP to non-loopback)"
log "memory peak (project JVMs): $(grep -o 'project=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | tail -1) MB, minimum free: $(grep -o 'free=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | head -1) MB"
(cd "$ROOT/client" && ./gradlew --stop > /dev/null 2>&1); (cd "$ROOT/research" && ./gradlew --stop > /dev/null 2>&1)
log "=== done"
