#!/usr/bin/env bash
# M7-Interaktionstest (docs/29 §2): eigener Client (Terminal) baut ab, platziert und benutzt Blöcke; nach jeder Aktion prüft
# die Serverkonsole den tatsächlichen Zustand (execute if block … → "Test passed"/"Test failed"), Inventar per clear …  0.
# Lokaler Server, friedlich; Originalkonfiguration wird bei jedem Ende zurückgesetzt.
# Aufruf im Projektwurzelverzeichnis: bash analysis/tools/m7-interact.sh <label>
set -u
LABEL="$1"; ROOT="$(pwd)"; SERVER="$ROOT/server"; OUT="$ROOT/analysis/runtime-logs/m7-$LABEL"; NAME="ProtoBotM7"
mkdir -p "$OUT"; : > "$OUT/transcript.txt"; : > "$OUT/client.in"
log() { echo "$(date +%H:%M:%S.%3N) $*" | tee -a "$OUT/transcript.txt"; }
SERVER_XMX="${M7_SERVER_XMX:-1536M}"; MIN_FREE="${M7_MIN_FREE_MB:-2500}"
FREE_MB=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -dc 0-9)
log "=== M7 interact $LABEL, free $FREE_MB MB, server -Xmx$SERVER_XMX"
if [ "${FREE_MB:-0}" -lt "$MIN_FREE" ]; then log "ABORT: less than $MIN_FREE MB free"; exit 2; fi

ORIG_DIFFICULTY=$(grep '^difficulty=' "$SERVER/server.properties" | cut -d= -f2)
restore() {
  echo quit >> "$OUT/client.in"; sleep 2; echo "" >> "$OUT/client.in"
  if ! grep -q "Stopping server" "$SERVER/logs/stdout.txt" 2>/dev/null; then echo "stop" >> "$SERVER/console.in"; sleep 8; fi
  echo "" >> "$SERVER/console.in"
  sed -i "s/^difficulty=.*/difficulty=$ORIG_DIFFICULTY/" "$SERVER/server.properties"
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/stopmon.ps1")" -OutDir "$(cygpath -w "$OUT")" > /dev/null 2>&1
}
trap restore EXIT
sed -i "s/^difficulty=.*/difficulty=peaceful/" "$SERVER/server.properties"
grep -E "^(difficulty|online-mode|server-ip|network-compression-threshold)=" "$SERVER/server.properties" | tee -a "$OUT/transcript.txt"

: > "$SERVER/console.in"; : > "$SERVER/logs/stdout.txt"
( cd "$SERVER" && tail -n 0 -f console.in | java -Xms512M -Xmx$SERVER_XMX -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery \
    -jar server.jar nogui > logs/stdout.txt 2>&1 ) > /dev/null 2>&1 &
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "Done (" "$SERVER/logs/stdout.txt" 2>/dev/null && break; sleep 1; done
log "$(grep -o 'Done ([^)]*)' "$SERVER/logs/stdout.txt")"
SERVER_LOG_START=$(wc -l < "$SERVER/logs/latest.log")
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/netmon.ps1")" -Seconds 900 -Out "$(cygpath -w "$OUT/netmon.txt")" > /dev/null 2>&1 &
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/analysis/tools/memmon.ps1")" -Seconds 900 -Out "$(cygpath -w "$OUT/memory.txt")" > /dev/null 2>&1 &

server_cmd() { echo "$1" >> "$SERVER/console.in"; }
client_cmd() { log "CLIENT> $1"; echo "$1" >> "$OUT/client.in"; sleep "${2:-1.2}"; }
# Serverprüfung: Konsolenbefehl ausführen und die Antwortzeile protokollieren (CHECK <name> <erwartet> -> <Antwort>)
check() {
  local name="$1" expect="$2" cmd="$3" before
  before=$(wc -l < "$SERVER/logs/latest.log"); server_cmd "$cmd"; sleep 1.0
  local answer; answer=$(tail -n +$((before + 1)) "$SERVER/logs/latest.log" | grep -v "Can't keep up" | sed 's/^\[[^]]*\] \[[^]]*\]: //' | head -1)
  local verdict="FAIL"; echo "$answer" | grep -q "$expect" && verdict="OK"
  log "CHECK $verdict $name: '$cmd' -> '$answer' (expected /$expect/)"
}

# Prüfung mit zwei Alternativen (z. B. Drop liegt am Boden ODER wurde schon aufgehoben): OK, wenn eine zutrifft
check_either() {
  local name="$1" e1="$2" c1="$3" e2="$4" c2="$5" before a1 a2
  before=$(wc -l < "$SERVER/logs/latest.log"); server_cmd "$c1"; sleep 1.0
  a1=$(tail -n +$((before + 1)) "$SERVER/logs/latest.log" | sed 's/^\[[^]]*\] \[[^]]*\]: //' | head -1)
  before=$(wc -l < "$SERVER/logs/latest.log"); server_cmd "$c2"; sleep 1.0
  a2=$(tail -n +$((before + 1)) "$SERVER/logs/latest.log" | sed 's/^\[[^]]*\] \[[^]]*\]: //' | head -1)
  local verdict="FAIL"; { echo "$a1" | grep -q "$e1" || echo "$a2" | grep -q "$e2"; } && verdict="OK"
  log "CHECK $verdict $name: '$c1' -> '$a1' | '$c2' -> '$a2' (expected /$e1/ or /$e2/)"
}

# Testfläche: Steinboden y=99, Luft darüber, x 28..46, z -36..-24 (in forceload-Bereich der Teststrecke)
# Nachbarfläche x 46..53 vorher leeren (Rest des M10-k2-Wasserbeckens würde sonst in die Testfläche fließen, docs/41 §9)
for c in "forceload add -32 -48 47 31" "forceload add 48 -48 63 -16" "fill 46 100 -36 53 105 -24 minecraft:air" "fill 28 99 -36 46 99 -24 minecraft:stone" "fill 28 100 -36 46 104 -24 minecraft:air" \
  "setblock 30 100 -30 minecraft:dirt" "setblock 32 100 -30 minecraft:stone" "setblock 34 100 -30 minecraft:stone" \
  "setblock 36 100 -30 minecraft:stone" "setblock 38 100 -30 minecraft:stone" "setblock 40 100 -30 minecraft:obsidian" \
  "setblock 42 100 -30 minecraft:lever[face=floor,facing=south,powered=false]" \
  "setblock 44 100 -30 minecraft:oak_door[facing=south,half=lower,open=false]" "setblock 44 101 -30 minecraft:oak_door[facing=south,half=upper,open=false]" \
  "gamerule spawn_radius 0"; do server_cmd "$c"; done
sleep 3

log "--- own client ($NAME)"
( cd "$ROOT/client" && tail -n 0 -f "$OUT/client.in" | ./gradlew -q --console=plain run \
    --args="--interactive --username $NAME --packet-log $(cygpath -w "$OUT/client-packets.log")" > "$OUT/client.out" 2>&1 ) > /dev/null 2>&1 &
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 120 ]; do grep -q "> ready" "$OUT/client.out" 2>/dev/null && break; sleep 1; done
grep -q "> ready" "$OUT/client.out" || { log "client not ready"; exit 1; }
server_cmd "gamemode survival $NAME"; server_cmd "clear $NAME"; server_cmd "effect clear $NAME"; sleep 2
at() { server_cmd "tp $NAME $1 100 -27.5 180 0"; sleep 1.5; }

# 1) Überleben, Hand, Erde (Härte 0,5 → 15 Ticks)
at 30.5; client_cmd "look-at 30.5 100.5 -29.5"; client_cmd "target" 0.5
client_cmd "dig 60" 4.5; check survival_hand_dirt "Test passed" "execute if block 30 100 -30 minecraft:air"
# 2) Überleben, Hand, Stein (Werkzeug nötig: 1/1,5/100 → 150 Ticks)
at 32.5; client_cmd "look-at 32.5 100.5 -29.5"; client_cmd "dig 220" 12; check survival_hand_stone "Test passed" "execute if block 32 100 -30 minecraft:air"
check survival_hand_stone_nodrop "Test failed" "execute if entity @e[type=item,nbt={Item:{id:\"minecraft:cobblestone\"}}]"
# 3) Holzspitzhacke (2/1,5/30 → 23 Ticks), Schaden danach = 1
server_cmd "kill @e[type=item]"; server_cmd "item replace entity $NAME hotbar.0 with minecraft:wooden_pickaxe"; sleep 1; client_cmd "inventory" 0.5; client_cmd "slot 1" 0.5
at 34.5; client_cmd "look-at 34.5 100.5 -29.5"; client_cmd "dig 80" 4.5; check pickaxe_stone "Test passed" "execute if block 34 100 -30 minecraft:air"
check_either pickaxe_drop "Test passed" "execute if entity @e[type=item,nbt={Item:{id:\"minecraft:cobblestone\"}}]" "Found 1 matching" "clear $NAME minecraft:cobblestone 0"
server_cmd "kill @e[type=item]"; server_cmd "clear $NAME minecraft:cobblestone"; sleep 1.5 # Antworten abwarten, damit check die eigene Antwort liest
client_cmd "inventory" 0.5; check pickaxe_damage "1" "data get entity $NAME Inventory[{id:\"minecraft:wooden_pickaxe\"}].components.\"minecraft:damage\""
# 4) Abbruch nach 10 Ticks: Block bleibt
at 36.5; client_cmd "look-at 36.5 100.5 -29.5"; client_cmd "dig 10" 1.5; check abort_keeps_block "Test passed" "execute if block 36 100 -30 minecraft:stone"
client_cmd "interaction" 0.5
# 5) Effizienz V (Attribut mining_efficiency über update_attributes): Diamantspitzhacke auf Obsidian
server_cmd "item replace entity $NAME hotbar.1 with minecraft:diamond_pickaxe[enchantments={\"minecraft:efficiency\":5}]"; sleep 1; client_cmd "slot 2" 0.5; client_cmd "inventory" 0.5
client_cmd "interaction" 0.5
at 40.5; client_cmd "look-at 40.5 100.5 -29.5"; client_cmd "dig 400" 6; check efficiency_obsidian "Test passed" "execute if block 40 100 -30 minecraft:air"
# 6) Platzieren: Eichenbretter auf die Oberseite des Bodens (Überleben, Anzahl sinkt 4 → 3)
server_cmd "kill @e[type=item]"; server_cmd "clear $NAME minecraft:obsidian"; server_cmd "item replace entity $NAME hotbar.2 with minecraft:oak_planks 4"; sleep 1; client_cmd "slot 3" 0.5
at 46.5; client_cmd "look-at 46.5 100.0 -28.5"; client_cmd "target" 0.5; client_cmd "use" 1
check place_planks "Test passed" "execute if block 46 100 -29 minecraft:oak_planks"
check place_count "Found 3 matching" "clear $NAME minecraft:oak_planks 0"
# 7) Benutzen: Hebel (leere Hand, Platz 9) und Tür
client_cmd "slot 9" 0.5
at 42.5; client_cmd "look-at 42.5 100.2 -29.5"; client_cmd "target" 0.5; client_cmd "use" 1
check lever_on "Test passed" "execute if block 42 100 -30 minecraft:lever[powered=true]"
at 44.5; client_cmd "look-at 44.5 100.8 -29.85"; client_cmd "target" 0.5; client_cmd "use" 1
check door_open "Test passed" "execute if block 44 100 -30 minecraft:oak_door[open=true]"
# 8) Hotbar: Platz 5 → SelectedItemSlot 4
client_cmd "slot 5" 0.8; check hotbar_slot "4" "data get entity $NAME SelectedItemSlot"
# 9) Kreativ: Sofortabbau
server_cmd "gamemode creative $NAME"; sleep 1.5; client_cmd "interaction" 0.5
at 38.5; client_cmd "look-at 38.5 100.5 -29.5"; client_cmd "dig 10" 1.5; check creative_instant "Test passed" "execute if block 38 100 -30 minecraft:air"
server_cmd "gamemode survival $NAME"; sleep 1
# 10) Nicht dekodierbare Komponente (written_book_content): Paket wird verworfen, Verbindung bleibt
server_cmd "give $NAME minecraft:written_book[written_book_content={title:\"t\",author:\"a\"}]"; sleep 1.5; client_cmd "inventory" 0.5
server_cmd "clear $NAME minecraft:written_book"; sleep 1; client_cmd "inventory" 0.5; client_cmd "status" 0.5
client_cmd "quit" 4
log "--- client output"; cat "$OUT/client.out" >> "$OUT/transcript.txt"
echo "stop" >> "$SERVER/console.in"
T0=$SECONDS; while [ $((SECONDS - T0)) -lt 60 ]; do grep -q "Stopping server" "$SERVER/logs/stdout.txt" && break; sleep 1; done
sleep 4
tail -n +$((SERVER_LOG_START + 1)) "$SERVER/logs/latest.log" > "$OUT/server-session.log"
log "--- server: joins, warnings"
grep -E "logged in|lost connection|moved|kicked|Mismatch|mismatch|WARN|ERROR|Exception" "$OUT/server-session.log" | grep -v Discovery | tee -a "$OUT/transcript.txt"
log "checks: $(grep -c 'CHECK OK' "$OUT/transcript.txt") ok, $(grep -c 'CHECK FAIL' "$OUT/transcript.txt") failed"
log "external connections: $(grep -v loopback "$OUT/netmon.txt" | grep -vc 'netmon start')"
log "memory: peak project $(grep -o 'project=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | tail -1) MB, min free $(grep -o 'free=[0-9]*' "$OUT/memory.txt" | cut -d= -f2 | sort -n | head -1) MB"
log "=== done"
