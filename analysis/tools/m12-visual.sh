#!/usr/bin/env bash
# M12 visuelle Vergleichstests (docs/49 §3): gleiche Szenen (analysis/tools/m12-scenes.txt) im unveränderten Vanilla-Client
# (Fabric-Forschungsprojekt, runShotRef → Screenshot#takeScreenshot) und im eigenen Grafikclient (Autotest, gleiche Befehle),
# danach Bildanalyse (ImageCompare.java). Lokaler Server; Konfiguration und Gamerules werden am Ende zurückgesetzt.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m12-visual.sh <label> [vanilla|own|both] [Sichtweite]
#   M12_CLIENT_DIR=<Verzeichnis>   eigener Client aus einer anderen Kopie (Baseline des M11-Stands)
#   M12_VANILLA_FROM=<label>        Vanilla-Bilder eines früheren Laufs wiederverwenden (Modus own)
#   M12_SCENES=<datei>              andere Szenendatei (z. B. Teilmenge zur Diagnose)
set -u
LABEL="$1"; MODE="${2:-both}"; RD="${3:-4}"; ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m12-$LABEL"
SCENES="${M12_SCENES:-$ROOT/analysis/tools/m12-scenes.txt}"; RUN="$ROOT/research/run-shotref"; REF="ResearchRef"
mkdir -p "$OUT/vanilla" "$RUN"; : > "$OUT/run.txt"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT/run.txt"; }
free_mb() { powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9; }
SERVER_XMX="${M12_SERVER_XMX:-1G}"; CLIENT_XMX="${M12_CLIENT_XMX:-1536M}"; MIN_FREE="${M12_MIN_FREE_MB:-3000}"
log "=== M12 visual $LABEL mode=$MODE renderDistance=$RD, free $(free_mb) MB; limits server $SERVER_XMX, vanilla $CLIENT_XMX, min free $MIN_FREE MB"
log "scene file sha256 $(sha256sum "$SCENES" | cut -c1-16), $(grep -c '^scene ' "$SCENES") scenes"

if [ "$MODE" = "vanilla" ] || [ "$MODE" = "both" ]; then
  if [ "$(free_mb)" -lt "$MIN_FREE" ]; then log "ABORT: less than $MIN_FREE MB free (vanilla pass not run)"; exit 2; fi
  ORIG_DIFFICULTY=$(grep '^difficulty=' "$SERVER/server.properties" | cut -d= -f2)
  restore() {
    sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/" "$SERVER/server.properties"
    if ! grep -q "Stopping server" "$SERVER/logs/stdout.txt" 2>/dev/null; then
      grep '^final ' "$SCENES" | sed 's/^final //' >> "$SERVER/console.in"; sleep 2; echo "stop" >> "$SERVER/console.in"; sleep 8
    fi
    echo "" >> "$SERVER/console.in"
    powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$(cygpath -w "$OUT")" > /dev/null 2>&1
  }
  trap restore EXIT
  sed -i "s/^difficulty=.*/difficulty=${M12_DIFFICULTY:-peaceful}/" "$SERVER/server.properties"
  grep -E "^(network-compression-threshold|difficulty|online-mode|server-ip)=" "$SERVER/server.properties" | tee -a "$OUT/run.txt"
  : > "$SERVER/console.in"; : > "$SERVER/logs/stdout.txt"
  ( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx$SERVER_XMX -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery \
      -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
  T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
  log "$(grep -o 'Done ([^)]*)' "$SERVER/logs/stdout.txt")"
  SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 1200 -Out "$(cygpath -w "$OUT/netmon.txt")" > /dev/null 2>&1 &
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/memmon.ps1")" -Seconds 1200 -Out "$(cygpath -w "$OUT/memory.txt")" > /dev/null 2>&1 &
  # Gamerules vorher (zur Kontrolle der Wiederherstellung)
  for g in advance_time advance_weather random_tick_speed spawn_mobs; do echo "gamerule $g" >> "$SERVER/console.in"; done; sleep 2
  grep "build " "$SCENES" | grep '^build ' | sed 's/^build //' >> "$SERVER/console.in"; sleep 6
  # Vanilla-Optionen: Vorlage aus run-pickref, vergleichbare Einstellungen erzwingen (docs/49 §3.1)
  if [ -f "$ROOT/research/run-pickref/options.txt" ] && [ ! -f "$RUN/options.txt" ]; then cp "$ROOT/research/run-pickref/options.txt" "$RUN/options.txt"; fi
  setopt() { if grep -q "^$1:" "$RUN/options.txt"; then sed -i "s/^$1:.*/$1:$2/" "$RUN/options.txt"; else echo "$1:$2" >> "$RUN/options.txt"; fi; }
  setopt renderDistance "$RD"; setopt simulationDistance 5; setopt fov 0.0; setopt ao true; setopt biomeBlendRadius 2; setopt mipmapLevels 0
  setopt chunkSectionFadeInTime 0.0; setopt entityShadows false; setopt bobView false; setopt gamma 0.5; setopt graphicsPreset '"fancy"'
  setopt improvedTransparency false; setopt cutoutLeaves true; setopt renderClouds '"true"'; setopt enableVsync true; setopt pauseOnLostFocus false
  setopt textureFiltering 0; setopt particles 0; setopt vignette true; setopt fullscreen false
  grep -E "^(renderDistance|fov|ao|biomeBlendRadius|mipmapLevels|chunkSectionFadeInTime|entityShadows|graphicsPreset|improvedTransparency|renderClouds|textureFiltering|gamma):" "$RUN/options.txt" | tr '\n' ' ' | tee -a "$OUT/run.txt"; echo | tee -a "$OUT/run.txt"
  rm -f "$RUN/logs/latest.log"; rm -rf "$RUN/shots"
  log "--- Vanilla reference client ($REF)"
  ( cd "$ROOT/research" && ./gradlew --no-daemon --console=plain -PshotRefXmx=$CLIENT_XMX "-PshotRefFile=$(cygpath -m "$SCENES")" runShotRef > "$OUT/fabric-gradle.txt" 2>&1 ) &
  GPID=$!; joined=0; done_scenes=0; TS=$SECONDS
  T0=$SECONDS; while [ $((SECONDS - T0)) -lt 1200 ]; do
    sleep 0.5
    kill -0 $GPID 2>/dev/null || { log "vanilla client process ended"; break; }
    L="$RUN/logs/latest.log"; [ -f "$L" ] || continue
    if [ $joined -eq 0 ] && grep -q "\[SHOTREF\] JOINED" "$L"; then joined=1; TS=$SECONDS; echo "gamemode creative $REF" >> "$SERVER/console.in"; log "joined: $(grep -o 'JOINED.*' "$L")"; fi
    n=$(grep -c "\[SHOTREF\] WAIT" "$L")
    if [ "$n" -gt "$done_scenes" ]; then
      done_scenes=$n; TS=$SECONDS; name=$(grep -o "\[SHOTREF\] WAIT .*" "$L" | tail -1 | awk '{print $3}')
      line=$(grep "^scene $name " "$SCENES"); set -- $line; dim="${9:-minecraft:overworld}"
      grep "^setup $name " "$SCENES" | sed "s/^setup $name //" >> "$SERVER/console.in"
      echo "execute in $dim run tp $REF $3 $4 $5 $6 $7" >> "$SERVER/console.in"
      spams=$(grep "^spam $name " "$SCENES" | sed "s/^spam $name //")
      if [ -n "$spams" ]; then
        secs=$(echo "$spams" | head -1 | awk '{print $1}')
        ( end=$((SECONDS + secs + 4)); while [ $SECONDS -lt $end ]; do echo "$spams" | cut -d' ' -f2- >> "$SERVER/console.in"; sleep 0.25; done ) &
      fi
      log "scene $name -> tp $3 $4 $5 $6 $7 ($dim)"
    fi
    grep -q "\[SHOTREF\] DONE" "$L" && break
    if [ $joined -eq 1 ] && [ $((SECONDS - TS)) -gt 180 ]; then
      log "STALL: scene ${name:-?} not captured within 180 s - stopping the vanilla reference client"
      powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*fabric.dli*' -and \$_.CommandLine -like '*MinecraftResearch*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }"
      break
    fi
  done
  wait $GPID; log "fabric exit=$?"
  grep -E "SHOTREF\] (SHOT|DONE)|Exception" "$RUN/logs/latest.log" | sed 's/^.*\[SHOTREF\] //' | cut -c1-260 | tee -a "$OUT/run.txt"
  cp "$RUN"/shots/*.png "$OUT/vanilla/" 2>/dev/null; log "vanilla images: $(ls "$OUT/vanilla" | wc -l)"
  grep '^final ' "$SCENES" | sed 's/^final //' >> "$SERVER/console.in"; sleep 2
  for g in advance_time advance_weather random_tick_speed spawn_mobs; do echo "gamerule $g" >> "$SERVER/console.in"; done; sleep 2
  echo "stop" >> "$SERVER/console.in"
  T0=$SECONDS; while [ $((SECONDS - T0)) -lt 60 ]; do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
  sleep 4
  tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" > "$OUT/server-session-vanilla.log"
  grep -E "Gamerule|gamerule|logged in|lost connection|moved|kicked|died|ERROR|Exception|Unknown|Incorrect|Expected" "$OUT/server-session-vanilla.log" | grep -v Discovery | cut -c1-220 | tee -a "$OUT/run.txt"
  log "external connections (vanilla pass): $(grep -v loopback "$OUT/netmon.txt" | grep -c '|')"
  trap - EXIT; sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/" "$SERVER/server.properties"
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$(cygpath -w "$OUT")" > /dev/null 2>&1
fi

if [ "$MODE" = "own" ] || [ "$MODE" = "both" ]; then
  if [ -n "${M12_VANILLA_FROM:-}" ]; then cp "$ROOT/analysis/runtime-logs/m12-$M12_VANILLA_FROM/vanilla/"*.png "$OUT/vanilla/" 2>/dev/null; fi
  if [ "$(free_mb)" -lt "$MIN_FREE" ]; then log "ABORT: less than $MIN_FREE MB free (own pass not run)"; exit 2; fi
  node "$ROOT/analysis/tools/m12-own-autotest.js" "$SCENES" "$OUT/own-autotest.txt" '${OUT}/shots' | tee -a "$OUT/run.txt"
  log "--- own graphical client (m6-run.sh, label m12v-$LABEL)"
  M6_SERVER_XMX=$SERVER_XMX M6_MIN_FREE_MB=$MIN_FREE M6_CLIENT_DIR="${M12_CLIENT_DIR:-$ROOT/client}" \
    bash "$ROOT/analysis/tools/m6-run.sh" "$OUT/own-autotest.txt" "m12v-$LABEL" "--render-distance $RD" > "$OUT/own-run.txt" 2>&1
  log "own pass exit=$?"
  OWNOUT="$ROOT/analysis/runtime-logs/m6-m12v-$LABEL"
  mkdir -p "$OUT/own"; cp "$OWNOUT"/shots/*.png "$OUT/own/" 2>/dev/null; log "own images: $(ls "$OUT/own" | wc -l)"
  grep -E "live GL objects|external connections|client exit|Exception|ERROR" "$OUT/own-run.txt" | cut -c1-240 | tee -a "$OUT/run.txt"
  grep -E "Unknown|Incorrect|Expected|Gamerule" "$OWNOUT/server-session.log" | cut -c1-200 | head -20 | tee -a "$OUT/run.txt"
fi

if [ -d "$OUT/own" ] && [ "$(ls "$OUT/vanilla" | wc -l)" -gt 0 ]; then
  java "$ROOT/analysis/tools/ImageCompare.java" "$OUT/vanilla" "$OUT/own" "$OUT/compare" | tee "$OUT/compare.txt" | tee -a "$OUT/run.txt"
fi
grep -E "^(difficulty|network-compression-threshold)=" "$SERVER/server.properties" | tee -a "$OUT/run.txt"
log "=== done"
