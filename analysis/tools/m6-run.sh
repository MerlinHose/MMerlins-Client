#!/usr/bin/env bash
# M6-Grafiktest (docs/26): lokaler Vanilla-Server (Threshold 256, friedlich) + grafischer Client mit Autotest-Skript.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m6-run.sh <autotest-datei> <label> ["zusätzliche Client-Argumente"]
# M12: M6_CLIENT_DIR=<Verzeichnis> startet den Grafikclient aus einer anderen Kopie (Baseline-Messung des M11-Stands).
# M9: M6_DIFFICULTY=easy (Monster), M6_COMPANION=<Name> startet zusätzlich einen Terminalclient als zweiten Spieler (Befehle über
#     <out>/companion.in, "quit" am Ende).
set -u
SCRIPT="$1"; LABEL="$2"; EXTRA="${3:-}"; T=256
ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m6-$LABEL"; NAME="ProtoBotGfx"
mkdir -p "$OUT"; : > "$OUT/run.txt"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT/run.txt"; }

FREE_MB=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9)
log "=== M6 $LABEL, free physical memory $FREE_MB MB"
if [ "${FREE_MB:-0}" -lt "${M6_MIN_FREE_MB:-2500}" ]; then log "ABORT: less than ${M6_MIN_FREE_MB:-2500} MB free"; exit 2; fi

# Serverkonfiguration nur für diesen Lauf; Originalwerte bei jedem Ende zurück
ORIG_DIFFICULTY=$(grep '^difficulty=' "$SERVER/server.properties" | cut -d= -f2)
ORIG_THRESHOLD=$(grep '^network-compression-threshold=' "$SERVER/server.properties" | cut -d= -f2)
restore() {
  sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/; s/^network-compression-threshold=.*/network-compression-threshold=$ORIG_THRESHOLD/" "$SERVER/server.properties"
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$(cygpath -w "$OUT")" > /dev/null 2>&1
}
trap restore EXIT
sed -i "s/^difficulty=.*/difficulty=${M6_DIFFICULTY:-peaceful}/; s/^network-compression-threshold=.*/network-compression-threshold=$T/" "$SERVER/server.properties"
grep -E "^(network-compression-threshold|difficulty|online-mode|server-ip)=" "$SERVER/server.properties" | tee -a "$OUT/run.txt"

: > "$SERVER/console.in"; : > "$SERVER/logs/stdout.txt"   # alte "Done"-Zeile darf das Warten nicht beenden
( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx${M6_SERVER_XMX:-1536M} -Dlog4j.configurationFile=log4j2-packets.xml \
    -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
log "$(grep -o 'Done ([^)]*)' "$SERVER/logs/stdout.txt")"
SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 1500 -Out "$(cygpath -w "$OUT/netmon.txt")" > /dev/null 2>&1 &
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/memmon.ps1")" -Seconds 1500 -Out "$(cygpath -w "$OUT/memory.txt")" > /dev/null 2>&1 &

if [ -n "${M6_COMPANION:-}" ]; then
  : > "$OUT/companion.in"; : > "$OUT/companion.out"
  ( cd "$ROOT/client" && tail -n 0 -f "$OUT/companion.in" | ./gradlew -q --console=plain run --args="--interactive --username $M6_COMPANION" > "$OUT/companion.out" 2>&1 ) > /dev/null 2>&1 &
  T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "> ready" "$OUT/companion.out" 2>/dev/null && break; sleep 1; done
  log "companion $M6_COMPANION: $(grep -c "> ready" "$OUT/companion.out") ready"
fi
# Autotest mit Pfad der Serverkonsole und Ausgabeverzeichnis
sed -e "s|\${CONSOLE}|$(cygpath -w "$SERVER/console.in" | sed 's|\\|/|g')|g" -e "s|\${OUT}|$(cygpath -w "$OUT" | sed 's|\\|/|g')|g" \
  -e "s|\${NAME}|$NAME|g" "$SCRIPT" > "$OUT/autotest.txt"
log "--- graphical client"
( cd "${M6_CLIENT_DIR:-$ROOT/client}" && ./gradlew -q --console=plain runGraphical -Pgargs="--username $NAME --autotest $(cygpath -w "$OUT/autotest.txt") --report $(cygpath -w "$OUT/report.txt") --packet-log $(cygpath -w "$OUT/client-packets.log") $EXTRA" ) > "$OUT/client.out" 2>&1
log "client exit=$?"
grep -E "^\[GL\]|\[AUTOTEST\].*(verify|done|shot)|Exception|ERROR|SCHWERWIEGEND" "$OUT/client.out" | cut -c1-260 | tee -a "$OUT/run.txt"

[ -n "${M6_COMPANION:-}" ] && { echo quit >> "$OUT/companion.in"; sleep 3; echo "" >> "$OUT/companion.in"; }
echo "stop" >> "$SERVER/console.in"
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 60 ]; do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 4; echo "" >> "$SERVER/console.in"
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" > "$OUT/server-session.log"
log "--- server: joins, warnings, deaths"
grep -E "logged in|lost connection|moved|kicked|floating|was slain|was shot|fell|died|ERROR|Exception" "$OUT/server-session.log" | grep -v Discovery | tee -a "$OUT/run.txt"
log "--- server checks (M7: say CHECK … + execute/clear/data)"
grep -E "\[Server\] CHECK|Test passed|Test failed|matching item|No items were found|has the following entity data: [0-9]+$" "$OUT/server-session.log" | sed 's/^\[[^]]*\] \[[^]]*\]: //' | tee -a "$OUT/run.txt"
# Client-Position (verify) gegen Serverantwort (data get … Pos), paarweise in Reihenfolge
grep -o "verify:[^ ]* *pos=([^)]*)" "$OUT/report.txt" > "$OUT/verify-client.txt"
grep -o "$NAME has the following entity data: \[[^]]*\]" "$OUT/server-session.log" | sed 's/.*data: //' > "$OUT/verify-server.txt"
paste -d'|' "$OUT/verify-client.txt" "$OUT/verify-server.txt" | tee "$OUT/verify.txt" | tee -a "$OUT/run.txt"
log "external connections: $(grep -v loopback "$OUT/netmon.txt" | grep -c '|')"
log "memory: peak project $(grep -o 'project=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | tail -1) MB, min free $(grep -o 'free=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | head -1) MB"
log "=== done"
