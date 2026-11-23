#!/usr/bin/env bash
# M7-Auswahltest (docs/29 §1): gleiche Blickrichtungen (analysis/tools/m7-picks.txt) im unveränderten Vanilla-Client
# (Fabric-Forschungsprojekt, runPickRef → LocalPlayer#raycastHitResult) und im eigenen Client (Terminalbefehl picks →
# BlockRaycast), danach feldweiser Vergleich. Lokaler Server, friedlich; Originalkonfiguration wird bei jedem Ende zurückgesetzt.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m7-pick-run.sh <label> [survival|creative]
# M9: M7_PICKS=<datei> M7_PICK_CMD=epicks M7_DIFFICULTY=easy für die Entity-Auswahl (analysis/tools/m9-picks.txt); "after <befehl>"-Zeilen
#     der Datei laufen am Ende (z. B. Gamerule zurücksetzen).
set -u
LABEL="$1"; GM="${2:-survival}"; ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m7-$LABEL"; PICKS="${M7_PICKS:-$ROOT/analysis/tools/m7-picks.txt}"; PICKCMD="${M7_PICK_CMD:-picks}"; DIFF="${M7_DIFFICULTY:-peaceful}"
RUN="$ROOT/research/run-pickref"; REF="ResearchRef"; OWN="ProtoBotM7"
mkdir -p "$OUT" "$RUN"; : > "$OUT/run.txt"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT/run.txt"; }
FREE_MB=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9)
log "=== M7 picks $LABEL ($GM), free physical memory $FREE_MB MB"
# Speichergrenzen (Standard wie M5/M6; bei wenig freiem RAM z. B. M7_SERVER_XMX=1G M7_CLIENT_XMX=1536M M7_MIN_FREE_MB=3300)
SERVER_XMX="${M7_SERVER_XMX:-1536M}"; CLIENT_XMX="${M7_CLIENT_XMX:-2G}"; MIN_FREE="${M7_MIN_FREE_MB:-4000}"
log "limits: server -Xmx$SERVER_XMX, vanilla client -Xmx$CLIENT_XMX, min free $MIN_FREE MB"
if [ "${FREE_MB:-0}" -lt "$MIN_FREE" ]; then log "ABORT: less than $MIN_FREE MB free"; exit 2; fi

ORIG_DIFFICULTY=$(grep '^difficulty=' "$SERVER/server.properties" | cut -d= -f2)
restore() {
  sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/" "$SERVER/server.properties"
  [ -f "$OUT/client.in" ] && { echo quit >> "$OUT/client.in"; sleep 2; echo "" >> "$OUT/client.in"; }
  if ! grep -q "Stopping server" "$SERVER/logs/stdout.txt" 2>/dev/null; then echo "stop" >> "$SERVER/console.in"; sleep 8; fi
  echo "" >> "$SERVER/console.in"
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$(cygpath -w "$OUT")" > /dev/null 2>&1
}
trap restore EXIT
sed -i "s/^difficulty=.*/difficulty=$DIFF/" "$SERVER/server.properties"
grep -E "^(network-compression-threshold|difficulty|online-mode|server-ip)=" "$SERVER/server.properties" | tee -a "$OUT/run.txt"

: > "$SERVER/console.in"; : > "$SERVER/logs/stdout.txt"
( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx$SERVER_XMX -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery \
    -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
log "$(grep -o 'Done ([^)]*)' "$SERVER/logs/stdout.txt")"
SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 900 -Out "$(cygpath -w "$OUT/netmon.txt")" > /dev/null 2>&1 &
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/memmon.ps1")" -Seconds 900 -Out "$(cygpath -w "$OUT/memory.txt")" > /dev/null 2>&1 &

log "scenario file: $(grep -c "^pick " "$PICKS") picks, sha256 $(sha256sum "$PICKS" | cut -c1-16)"
grep "^build " "$PICKS" | sed 's/^build //' >> "$SERVER/console.in"; sleep 4

# 1) Vanilla-Referenz
rm -f "$RUN/logs/latest.log" "$RUN/pickref.txt"
log "--- Vanilla reference client ($REF)"
( cd "$ROOT/research" && ./gradlew --no-daemon --console=plain -PpickRefXmx=$CLIENT_XMX "-PpickRefFile=$(cygpath -m "$PICKS")" runPickRef > "$OUT/fabric-gradle.txt" 2>&1 ) &
GPID=$!; joined=0
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 600 ]; do
  sleep 1
  L="$RUN/logs/latest.log"; [ -f "$L" ] || continue
  if [ $joined -eq 0 ] && grep -q "\[PICKREF\] JOINED" "$L"; then joined=1; echo "gamemode $GM $REF" >> "$SERVER/console.in"; echo "tp $REF 0 100 -27.5 180 0" >> "$SERVER/console.in"; log "joined -> tp"; fi
  grep -q "\[PICKREF\] DONE" "$L" && break
done
wait $GPID; log "fabric exit=$?"
grep -E "PICKREF|Exception" "$RUN/logs/latest.log" | cut -c1-240 | tee -a "$OUT/run.txt"
cp "$RUN/pickref.txt" "$OUT/vanilla.txt" 2>/dev/null || { log "no vanilla result"; exit 1; }

# 2) eigener Client (Terminal)
log "--- own client ($OWN)"
: > "$OUT/client.out" # Marker eines früheren Laufs entfernen (siehe m7-interact.sh)
: > "$OUT/client.in"
( cd "$ROOT/client" && tail -n 0 -f "$OUT/client.in" | ./gradlew -q --console=plain run \
    --args="--interactive --username $OWN" > "$OUT/client.out" 2>&1 ) > /dev/null 2>&1 &
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "> ready" "$OUT/client.out" 2>/dev/null && break; sleep 1; done
echo "gamemode $GM $OWN" >> "$SERVER/console.in"; echo "tp $OWN 0 100 -27.5 180 0" >> "$SERVER/console.in"; sleep 6
echo "$PICKCMD $(cygpath -m "$PICKS")" >> "$OUT/client.in"; sleep 4
echo "quit" >> "$OUT/client.in"; sleep 4; echo "" >> "$OUT/client.in"
grep "PICK " "$OUT/client.out" | sed 's/^ *PICK //' > "$OUT/own.txt"

# 3) Vergleich (Felder 1–12 inkl. Reichweiten; Feld 13 = Vanilla-Blockzustand, nur zur Information)
grep -v '^#' "$OUT/vanilla.txt" | cut -d' ' -f1-12 > "$OUT/vanilla-cmp.txt"
paste -d'|' "$OUT/vanilla-cmp.txt" "$OUT/own.txt" | awk -F'|' '{ if ($1 == $2) ok++; else { bad++; print "DIFF vanilla: " $1 "\n     own:     " $2 } } END { print "picks equal " ok+0 " differ " bad+0 }' | tee "$OUT/compare.txt" | tee -a "$OUT/run.txt"
log "vanilla lines $(wc -l < "$OUT/vanilla-cmp.txt"), own lines $(wc -l < "$OUT/own.txt")"
grep "^after " "$PICKS" | sed "s/^after //" >> "$SERVER/console.in"; sleep 2
echo "stop" >> "$SERVER/console.in"
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 60 ]; do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 4
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" > "$OUT/server-session.log"
grep -E "logged in|lost connection|moved|kicked|died|game mode|Teleported|ERROR|Exception" "$OUT/server-session.log" | grep -v Discovery | tee -a "$OUT/run.txt"
log "external connections: $(grep -v loopback "$OUT/netmon.txt" | grep -vc 'netmon start')"
log "memory: peak project $(grep -o "project=[0-9]*" "$OUT/memory.txt" | cut -d= -f2 | sort -n | tail -1) MB, min free $(grep -o "free=[0-9]*" "$OUT/memory.txt" | cut -d= -f2 | sort -n | head -1) MB"
log "=== done"
