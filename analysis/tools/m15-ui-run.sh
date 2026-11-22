#!/usr/bin/env bash
# M15 GUI-Integrationstest (docs/62 §5): lokaler Vanilla-Server + grafischer Client im MENÜMODUS mit --ui-test.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m15-ui-run.sh <label> <ui-skript> [<ui-skript> …]
# Jedes Skript ist ein eigener Clientstart mit DERSELBEN Einstellungs- und Serverliste (Speichern → Neu laden über Neustarts).
# Vor einem Skript namens *broken* wird die Einstellungsdatei absichtlich beschädigt (Robustheit beim Start).
# Zusätzlich lauscht auf 127.0.0.1:25599 ein stummer TCP-Dienst (nimmt an, antwortet nie): Abbrechen/Zeitüberschreitung.
# Optional M15_REALINPUT=1: echte Betriebssystem-Eingaben (analysis/tools/m15-realinput.ps1) parallel zum Skript.
set -u
LABEL="$1"; shift
ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m15-$LABEL"; T=256
mkdir -p "$OUT"; : > "$OUT/run.txt"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT/run.txt"; }
w() { cygpath -m "$1"; }

FREE_MB=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9)
log "=== M15 GUI $LABEL, free physical memory $FREE_MB MB"
if [ "${FREE_MB:-0}" -lt "${M15_MIN_FREE_MB:-3000}" ]; then log "ABORT: less than ${M15_MIN_FREE_MB:-3000} MB free"; exit 2; fi

ORIG_DIFFICULTY=$(grep '^difficulty=' "$SERVER/server.properties" | cut -d= -f2)
ORIG_THRESHOLD=$(grep '^network-compression-threshold=' "$SERVER/server.properties" | cut -d= -f2)
SILENT_PID=""
restore() {
  sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/; s/^network-compression-threshold=.*/network-compression-threshold=$ORIG_THRESHOLD/" "$SERVER/server.properties"
  [ -n "$SILENT_PID" ] && kill "$SILENT_PID" 2>/dev/null
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$(cygpath -w "$OUT")" > /dev/null 2>&1
}
trap restore EXIT
sed -i "s/^difficulty=.*/difficulty=peaceful/; s/^network-compression-threshold=.*/network-compression-threshold=$T/" "$SERVER/server.properties"
grep -E "^(network-compression-threshold|difficulty|online-mode|server-ip)=" "$SERVER/server.properties" | tee -a "$OUT/run.txt"

: > "$SERVER/console.in"; : > "$SERVER/logs/stdout.txt"
( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx${M15_SERVER_XMX:-1G} -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery \
    -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
log "$(grep -o 'Done ([^)]*)' "$SERVER/logs/stdout.txt")"
SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 2400 -Out "$(cygpath -w "$OUT/netmon.txt")" > /dev/null 2>&1 &
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/memmon.ps1")" -Seconds 2400 -Out "$(cygpath -w "$OUT/memory.txt")" > /dev/null 2>&1 &
# stummer Dienst: nimmt Verbindungen an und schweigt (Test „Abbrechen“ und „Zeitüberschreitung“)
powershell -NoProfile -Command "\$l=[System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,25599); \$l.Start(); \$c=@(); \$end=(Get-Date).AddMinutes(40); while((Get-Date) -lt \$end){ if(\$l.Pending()){ \$c+=\$l.AcceptTcpClient() } else { Start-Sleep -Milliseconds 50 } }" > /dev/null 2>&1 &
SILENT_PID=$!

SETTINGS="$OUT/settings.properties"; SERVERS="$OUT/servers.properties"
rm -f "$SETTINGS" "$SERVERS" "$OUT"/settings.properties.broken-*
printf 'server.0.name=Lokaler Testserver (Liste)\nserver.0.host=127.0.0.1\nserver.0.port=25565\nserver.1.name=Stummer Dienst\nserver.1.host=127.0.0.1\nserver.1.port=25599\nserver.2.name=Extern (gesperrt)\nserver.2.host=play.example.org\nserver.2.port=25565\n' > "$SERVERS"
PASS=0
for SCRIPT in "$@"; do
  PASS=$((PASS + 1)); NAME=$(basename "$SCRIPT" .txt)
  if [[ "$NAME" == *broken* ]]; then
    printf '\xff\xfe\x00garbage\x01\x02 not a settings file\n' > "$SETTINGS"
    log "pass $PASS: settings file deliberately corrupted"
  fi
  sed -e "s|\${OUT}|$(w "$OUT")|g" "$SCRIPT" > "$OUT/ui-$PASS-$NAME.txt"
  log "--- pass $PASS: $NAME"
  if [ "${M15_REALINPUT:-0}" = "1" ] && [[ "$NAME" == *real* ]]; then
    powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/m15-realinput.ps1")" -OutDir "$(cygpath -w "$OUT")" \
      -Report "$(cygpath -w "$OUT/report-$PASS.txt")" > /dev/null 2>&1 &
  fi
  ( cd "$ROOT/client" && ./gradlew -q --console=plain runGraphical -Pgargs="--settings $(w "$SETTINGS") --servers $(w "$SERVERS") --ui-test $(w "$OUT/ui-$PASS-$NAME.txt") --report $(w "$OUT/report-$PASS.txt") ${M15_CLIENT_ARGS:-}" ) > "$OUT/client-$PASS.out" 2>&1
  log "client exit=$?"
  grep -E "^\[APP\]|^\[GL\] live|^\[GL\] session|RESULT FAIL|uitest (done|end)|Exception|SCHWERWIEGEND" "$OUT/client-$PASS.out" | cut -c1-260 | tee -a "$OUT/run.txt"
  cp "$SETTINGS" "$OUT/settings-after-$PASS.properties" 2>/dev/null
done

echo "stop" >> "$SERVER/console.in"
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 60 ]; do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 4; echo "" >> "$SERVER/console.in"
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" > "$OUT/server-session.log"
log "--- server: joins/leaves"
log "joins $(grep -c 'logged in with entity id' "$OUT/server-session.log"), leaves $(grep -c 'lost connection' "$OUT/server-session.log")"
grep -E "ERROR|Exception|moved too quickly|kicked" "$OUT/server-session.log" | grep -v Discovery | head -20 | tee -a "$OUT/run.txt"
log "RESULT OK total $(cat "$OUT"/report-*.txt 2>/dev/null | grep -c 's  RESULT OK'), RESULT FAIL total $(cat "$OUT"/report-*.txt 2>/dev/null | grep -c 's  RESULT FAIL')"
log "external connections: $(grep -v loopback "$OUT/netmon.txt" | grep -c '|')"
log "memory: peak project $(grep -o 'project=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | tail -1) MB, min free $(grep -o 'free=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | head -1) MB"
log "=== done"
