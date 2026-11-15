#!/usr/bin/env bash
# M13 Phase G (docs/53 §4): verschlüsselter Login des eigenen Clients gegen den UNVERÄNDERTEN Vanilla-26.3-Server mit
# online-mode=true, wobei die Minecraft-Services LOKAL SIMULIERT werden (analysis/tools/m13/MockSessionServices.java auf
# 127.0.0.1). Prüft RSA, AES/CFB8, Session-Hash, Kompression und Zustandsautomat mit dem Code des Vanilla-Servers.
# KEIN Nachweis einer offiziellen Anmeldung: Name, UUID und Token sind Testdaten.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m13-sim-vanilla.sh [schwellen…]   (Standard: -1 0 256)
set -u
ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m13-sim"; PORT=25599
THRESHOLDS="${*:--1 0 256}"
SIM_UUID="00000000-0000-4000-8000-00000000d013"
mkdir -p "$OUT"; : > "$OUT/run.txt"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT/run.txt"; }

FREE_MB=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9)
COMMIT=$(powershell -NoProfile -Command "\$o=Get-CimInstance Win32_OperatingSystem; [int](100-100*\$o.FreeVirtualMemory/\$o.TotalVirtualMemorySize)" | tr -dc 0-9)
log "=== M13 simulation vs Vanilla server, free physical $FREE_MB MB, commit $COMMIT %"
if [ "${FREE_MB:-0}" -lt 3000 ]; then log "ABORT: less than 3000 MB free"; exit 2; fi

cp "$SERVER/server.properties" "$OUT/server.properties.orig"
cp "$SERVER/usercache.json" "$OUT/usercache.json.orig"
MOCK_PID=""
restore() {
  curl -s --max-time 3 "http://127.0.0.1:$PORT/control?mode=exit" > /dev/null 2>&1
  [ -n "$MOCK_PID" ] && kill "$MOCK_PID" 2>/dev/null
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$(cygpath -w "$OUT")" > /dev/null 2>&1
  cp "$OUT/server.properties.orig" "$SERVER/server.properties"
  cp "$OUT/usercache.json.orig" "$SERVER/usercache.json"
  # nur die Dateien der Simulations-UUID entfernen (kein anderer Spieler betroffen)
  rm -f "$SERVER/world/players/data/$SIM_UUID.dat" "$SERVER/world/players/data/$SIM_UUID.dat_old" \
        "$SERVER/world/players/advancements/$SIM_UUID.json" "$SERVER/world/players/stats/$SIM_UUID.json"
  grep -E "^(online-mode|network-compression-threshold|difficulty)=" "$SERVER/server.properties" | tr '\n' ' ' | sed 's/^/restored: /' >> "$OUT/run.txt"
  echo >> "$OUT/run.txt"
}
trap restore EXIT

: > "$OUT/mock.log"
# absoluter Pfad in der Kommandozeile: m12-cleanup.ps1 erkennt den Prozess als Projektprozess
( exec java -Xmx64m "$(cygpath -w "$ROOT/analysis/tools/m13/MockSessionServices.java")" $PORT "$(cygpath -w "$OUT/mock.log")" ) > "$OUT/mock.out" 2>&1 &
MOCK_PID=$!
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 30 ]; do grep -q "LOCAL SIMULATION" "$OUT/mock.log" 2>/dev/null && break; sleep 1; done
log "mock: $(head -1 "$OUT/mock.log")"
ctl() { curl -s --max-time 3 "http://127.0.0.1:$PORT/control?mode=$1" > /dev/null; }

client() { # Label, Argumente
  local label="$1"; shift
  # direkt mit java (ohne Gradle-Daemon, spart Arbeitsspeicher); Klassen aus dem letzten Build
  ( cd "$ROOT/client" && java -Xmx256m -cp "build/classes/java/main;build/resources/main" dev.mcresearch.protocolclient.Main \
      --no-status --duration 6 --packet-log "$(cygpath -w "$OUT/$label-packets.log")" $* ) > "$OUT/$label.out" 2>&1
  local rc=$?
  log "client $label exit=$rc: $(grep -E 'LOGIN identity|LOGIN FAILED|RESULT' "$OUT/$label.out" | sed 's/^\[[^]]*\] \[[^]]*\] mcclient: //' | tr '\n' ' ' | cut -c1-600)"
}
SIM="--auth simulation --username M13Sim --sim-discovery http://127.0.0.1:$PORT/minecraft/client --sim-uuid $SIM_UUID"

for T in $THRESHOLDS; do
  sed -i "s/^online-mode=.*/online-mode=true/; s/^network-compression-threshold=.*/network-compression-threshold=$T/; s/^difficulty=.*/difficulty=peaceful/" "$SERVER/server.properties"
  log "--- server threshold $T: $(grep -E '^(online-mode|network-compression-threshold|enforce-secure-profile|server-ip)=' "$SERVER/server.properties" | tr '\n' ' ')"
  : > "$SERVER/console.in"; : > "$SERVER/logs/stdout.txt"
  ( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx1G -Dlog4j.configurationFile=log4j2-packets.xml \
      -Dminecraft.api.discovery.host=http://127.0.0.1:$PORT/minecraft/client -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
  T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
  log "$(grep -o 'Done ([^)]*)' "$SERVER/logs/stdout.txt") $(grep -m1 -o 'Environment: [^,]*' "$SERVER/logs/latest.log")"
  START=$(wc -l < "$SERVER/logs/latest.log")
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 400 -Out "$(cygpath -w "$OUT/netmon-$T.txt")" > /dev/null 2>&1 &

  ctl accept
  # erfolgreicher Login, Neuverbindung (2 Sitzungen), Identität laut Serverzustand
  ( sleep 9; echo "data get entity M13Sim UUID" >> "$SERVER/console.in"; echo "data get entity M13Sim Pos" >> "$SERVER/console.in" ) &
  client "ok-$T" "$SIM --sessions 2"
  if [ "$T" = "256" ]; then
    client "offline-$T" "--auth offline --username M13Off"
    ctl reject-join;  client "reject-join-$T" "$SIM"
    ctl down-join;    client "down-join-$T" "$SIM"
    ctl unverified;   client "unverified-$T" "$SIM"
    ctl down-verify;  client "down-verify-$T" "$SIM"
    ctl accept;       client "again-$T" "$SIM"
  fi
  echo "stop" >> "$SERVER/console.in"
  T0=$SECONDS; while [ $((SECONDS - T0)) -lt 60 ]; do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
  sleep 4; echo "" >> "$SERVER/console.in"; sleep 2
  tail -n +$((START + 1)) "$SERVER/logs/latest.log" > "$OUT/server-$T.log"
  log "server: $(grep -E 'UUID of player|logged in with|lost connection|has the following entity data|Failed to verify|tried to join|Couldn.t verify|disconnect' "$OUT/server-$T.log" | sed 's/^\[[^]]*\] \[[^]]*\]: //' | tr '\n' '|' | cut -c1-1500)"
  log "external connections ($T): $(grep -v loopback "$OUT/netmon-$T.txt" | grep -c '|')"
done
log "mock requests: $(grep -c . "$OUT/mock.log")"
grep -E "join ->|hasJoined" "$OUT/mock.log" | tail -30 >> "$OUT/run.txt"
log "=== done"
