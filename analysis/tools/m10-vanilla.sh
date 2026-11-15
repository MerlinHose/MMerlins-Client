#!/usr/bin/env bash
# M10 Vanilla-Vergleich (docs/41 §7): dieselben Angriffe zuerst mit dem unveränderten Vanilla-Client (Fabric-Treiber CombatReferenceClient,
# echte Eingabewege), dann mit dem eigenen Client (Terminal). Verglichen werden die vom Server gemeldete Gesundheit der Ziele und die
# Reihenfolge der vom Server empfangenen Pakete je Angriff (Server-Paketprotokoll logs/packets.log).
# Lokaler Server, friedlich, Kompressionsschwelle 256; Konfiguration wird bei jedem Ende zurückgesetzt.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m10-vanilla.sh <label>
set -u
LABEL="$1"; ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m10-$LABEL"; SCEN="$ROOT/analysis/tools/m10-vanilla.txt"
RUN="$ROOT/research/run-combatref"; REF="ResearchRef"; OWN="ProtoBotM10"
mkdir -p "$OUT" "$RUN"; : > "$OUT/run.txt"; : > "$OUT/client.in"
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$OUT/run.txt"; }
SERVER_XMX="${M10_SERVER_XMX:-1G}"; CLIENT_XMX="${M10_CLIENT_XMX:-1536M}"; MIN_FREE="${M10_MIN_FREE_MB:-3500}"
FREE_MB=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9)
log "=== M10 Vanilla comparison $LABEL, free $FREE_MB MB, server -Xmx$SERVER_XMX, vanilla -Xmx$CLIENT_XMX"
if [ "${FREE_MB:-0}" -lt "$MIN_FREE" ]; then log "ABORT: less than $MIN_FREE MB free"; exit 2; fi
ORIG_DIFFICULTY=$(grep '^difficulty=' "$SERVER/server.properties" | cut -d= -f2)
ORIG_THRESHOLD=$(grep '^network-compression-threshold=' "$SERVER/server.properties" | cut -d= -f2)
restore() {
  [ -f "$OUT/client.in" ] && { echo quit >> "$OUT/client.in"; sleep 2; echo "" >> "$OUT/client.in"; }
  if ! grep -q "Stopping server" "$SERVER/logs/stdout.txt" 2>/dev/null; then echo "stop" >> "$SERVER/console.in"; sleep 8; fi
  echo "" >> "$SERVER/console.in"
  sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/; s/^network-compression-threshold=.*/network-compression-threshold=$ORIG_THRESHOLD/" "$SERVER/server.properties"
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$(cygpath -w "$OUT")" > /dev/null 2>&1
}
trap restore EXIT
sed -i "s/^difficulty=.*/difficulty=peaceful/; s/^network-compression-threshold=.*/network-compression-threshold=256/" "$SERVER/server.properties"
grep -E "^(network-compression-threshold|difficulty|online-mode|server-ip)=" "$SERVER/server.properties" | tee -a "$OUT/run.txt"
: > "$SERVER/console.in"; : > "$SERVER/logs/stdout.txt"
( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx$SERVER_XMX -Dlog4j.configurationFile=log4j2-packets.xml \
    -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
log "$(grep -o 'Done ([^)]*)' "$SERVER/logs/stdout.txt")"
SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 900 -Out "$(cygpath -w "$OUT/netmon.txt")" > /dev/null 2>&1 &
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/memmon.ps1")" -Seconds 900 -Out "$(cygpath -w "$OUT/memory.txt")" > /dev/null 2>&1 &
server_cmd() { echo "$1" >> "$SERVER/console.in"; }
server_query() {
  local before; before=$(wc -l < "$SERVER/logs/latest.log"); server_cmd "$1"; local t0=$SECONDS line=""
  while [ $((SECONDS - t0)) -lt 6 ]; do
    line=$(tail -n +$((before + 1)) "$SERVER/logs/latest.log" | grep -E "has the following|No entity was found|Found no elements" | head -1); [ -n "$line" ] && break; sleep 0.1
  done
  echo "$line"
}
for c in "forceload add -32 -64 47 15" "fill -14 99 -48 20 99 -16 minecraft:stone" "fill -14 100 -48 20 106 -16 minecraft:air" \
  "fill -14 107 -48 20 107 -16 minecraft:stone" "fill -14 103 -44 20 103 -22 minecraft:light[level=15]"; do server_cmd "$c"; done
summon_targets() {
  server_cmd "kill @e[type=!player]"; server_cmd "kill @e[type=item]"
  grep "^target " "$SCEN" | while read -r _ tag type x y z; do server_cmd "summon $type $x $y $z {NoAI:1b,Tags:[m10v,$tag]}"; done
  sleep 2
}
query_targets() {
  for tag in $(grep "^target " "$SCEN" | awk '{print $2}'); do
    echo "$tag $(server_query "data get entity @e[tag=$tag,limit=1] Health" | sed -n 's/.*data: \([0-9.E-]*\)f$/\1/p')"
  done
}
prepare_player() {
  server_cmd "gamemode survival $1"; server_cmd "clear $1"; server_cmd "effect clear $1"
  server_cmd "item replace entity $1 hotbar.1 with minecraft:iron_sword"; server_cmd "item replace entity $1 hotbar.2 with minecraft:iron_axe"
  server_cmd "tp $1 0.5 100 -27.5 180 0"
}
pkt_mark() { wc -l < "$SERVER/logs/packets.log"; }
# ---- 1) Vanilla
summon_targets
P0=$(pkt_mark)
rm -f "$RUN/logs/latest.log" "$RUN/combatref.txt"
log "--- Vanilla reference client ($REF)"
( cd "$ROOT/research" && ./gradlew --no-daemon --console=plain -PcombatRefXmx=$CLIENT_XMX "-PcombatRefFile=$(cygpath -m "$SCEN")" runCombatRef > "$OUT/fabric-gradle.txt" 2>&1 ) &
GPID=$!; joined=0
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 600 ]; do
  sleep 1; L="$RUN/logs/latest.log"; [ -f "$L" ] || continue
  if [ $joined -eq 0 ] && grep -q "\[COMBATREF\] JOINED" "$L"; then joined=1; prepare_player "$REF"; log "joined -> prepared"; fi
  grep -q "\[COMBATREF\] DONE" "$L" && break
done
wait $GPID; log "fabric exit=$?"
grep -E "COMBATREF|Exception" "$RUN/logs/latest.log" | cut -c1-200 | tee -a "$OUT/run.txt"
cp "$RUN/combatref.txt" "$OUT/vanilla-attacks.txt" 2>/dev/null || { log "no vanilla result"; exit 1; }
sleep 2
query_targets > "$OUT/vanilla-health.txt"; P1=$(pkt_mark)
sed -n "$((P0 + 1)),${P1}p" "$SERVER/logs/packets.log" | grep -oE "serverbound/minecraft:(attack|punch|interact|set_carried_item|use_item|player_action)" | sed 's/serverbound\/minecraft://' > "$OUT/vanilla-packets.txt"
cat "$OUT/vanilla-health.txt" | tee -a "$OUT/run.txt"
# ---- 2) eigener Client
summon_targets
P2=$(pkt_mark)
log "--- own client ($OWN)"
: > "$OUT/client.out"
( cd "$ROOT/client" && tail -n 0 -f "$OUT/client.in" | ./gradlew -q --console=plain run --args="--interactive --username $OWN" > "$OUT/client.out" 2>&1 ) > /dev/null 2>&1 &
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "> ready" "$OUT/client.out" 2>/dev/null && break; sleep 1; done
prepare_player "$OWN"; sleep 4
grep "^own " "$SCEN" | sed 's/^own //' | while read -r cmd; do echo "$cmd" >> "$OUT/client.in"; case "$cmd" in hit*) sleep 4 ;; *) sleep 1.2 ;; esac; done
sleep 3
query_targets > "$OUT/own-health.txt"; P3=$(pkt_mark)
sed -n "$((P2 + 1)),${P3}p" "$SERVER/logs/packets.log" | grep -oE "serverbound/minecraft:(attack|punch|interact|set_carried_item|use_item|player_action)" | sed 's/serverbound\/minecraft://' > "$OUT/own-packets.txt"
grep -E "HIT|HITPAIR|HITFALL" "$OUT/client.out" | tee "$OUT/own-attacks.txt" | tee -a "$OUT/run.txt" > /dev/null
cat "$OUT/own-health.txt" | tee -a "$OUT/run.txt"
echo quit >> "$OUT/client.in"; sleep 3
# ---- 3) Vergleich
log "--- comparison (server health per target; attack packet sequences)"
paste -d' ' "$OUT/vanilla-health.txt" "$OUT/own-health.txt" | awk '{ s = ($2 == $4) ? "EQUAL" : "DIFF"; print s, $1, "vanilla", $2, "own", $4 }' | tee "$OUT/compare.txt" | tee -a "$OUT/run.txt"
# Paketfolgen auf Angriffsgruppen reduzieren (set_carried_item wird beim Platzwechsel gesendet; Angriff = attack gefolgt von punch)
tr '\n' ' ' < "$OUT/vanilla-packets.txt" | sed 's/ $//' > "$OUT/vanilla-seq.txt"; echo >> "$OUT/vanilla-seq.txt"
tr '\n' ' ' < "$OUT/own-packets.txt" | sed 's/ $//' > "$OUT/own-seq.txt"; echo >> "$OUT/own-seq.txt"
log "vanilla packets: $(cat "$OUT/vanilla-seq.txt")"
log "own packets:     $(cat "$OUT/own-seq.txt")"
if cmp -s "$OUT/vanilla-seq.txt" "$OUT/own-seq.txt"; then log "PACKETS EQUAL"; else log "PACKETS DIFF"; fi
echo "stop" >> "$SERVER/console.in"
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 60 ]; do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 4
log "external connections: $(grep -v loopback "$OUT/netmon.txt" | grep -vc 'netmon start')"
log "memory: peak project $(grep -o "project=[0-9]*" "$OUT/memory.txt" | cut -d= -f2 | sort -n | tail -1) MB, min free $(grep -o "free=[0-9]*" "$OUT/memory.txt" | cut -d= -f2 | sort -n | head -1) MB"
log "=== done"
