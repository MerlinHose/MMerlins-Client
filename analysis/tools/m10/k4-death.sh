# M10 Schritt 4: Tod und Wiederbelebung (docs/41 §5). Keine automatische Wiederbelebung (autorespawn off): der Client bleibt tot, bis
# er selbst client_command PERFORM_RESPAWN sendet. Danach Spawnpunkt, Gesundheit, Effekte und Inventar gegen den Server.
for c in "forceload add -32 -64 63 15" "fill -14 99 -48 30 99 -16 minecraft:stone" "fill -14 100 -48 30 106 -16 minecraft:air" \
  "fill -14 107 -48 30 107 -16 minecraft:stone" "kill @e[type=!player]" "kill @e[type=item]" \
  "gamemode survival $NAME" "clear $NAME" "effect clear $NAME" "tp $NAME 4.5 100 -30.5 180 0"; do server_cmd "$c"; done
sleep 2
srv() { server_num "data get entity $NAME $1"; }
act "autorespawn off"
server_cmd "item replace entity $NAME hotbar.0 with minecraft:iron_sword"; server_cmd "item replace entity $NAME inventory.0 with minecraft:dirt 10"
server_cmd "effect give $NAME minecraft:regeneration 600 0"; sleep 1.5
expect "before death: inventory has sword and dirt" "minecraft:dirt 10" "$(client invdump)"
# --- Tod
server_cmd "kill $NAME"; sleep 2
expect "death detected (player_combat_kill), message received" "dead=true" "$(client survival)"
expect "death message shown" "^  DEATH .*$NAME" "$(client survival)"
expect_num "dead: client health 0" "$(client_field health)" 0 0.0001
expect_num "server: player health 0" "$(srv Health)" 0 0.0001
sleep 3
expect "no automatic respawn while waiting for the player (respawns=0)" "respawns=0 " "$(client survival)"
expect_num "server: still dead after 3 s" "$(srv Health)" 0 0.0001
expect "dropped items exist at the death location" "Test passed" "$(server_query "execute positioned 4.5 100 -30.5 if entity @e[type=item,distance=..6]")"
# --- Wiederbelebung
act "respawn"; sleep 3
expect "respawned: alive again" "dead=false" "$(client survival)"
expect_num "respawn: client health 20" "$(client_field health)" 20 0.0001
expect_num "respawn: server health 20" "$(srv Health)" 20 0.0001
expect "respawn: client_command PERFORM_RESPAWN sent exactly once" "respawns=1 " "$(client survival)"
SP=$(server_query "data get entity $NAME Pos" | sed -n 's/.*data: \[\(.*\)\]$/\1/p' | tr -d 'd' | tr ',' ' ')
CP=$(client "position" | head -2)
log "respawn position server [$SP] client: $CP"
expect "respawn: client position equals the server spawn position" "yes" "$(node -e "const s=process.argv[1].trim().split(/\s+/).map(Number); const m=process.argv[2].match(/(-?[0-9.]+),? (-?[0-9.]+),? (-?[0-9.]+)/); if(!m){console.log('no');process.exit()} const c=[+m[1],+m[2],+m[3]]; console.log(s.every((v,i)=>Math.abs(v-c[i])<0.01)?'yes':'no')" -- "$SP" "$CP")"
expect "respawn: inventory empty on the client (keepInventory off)" "^$" "$(client invdump | grep -E '^  INV ')"
expect "respawn: server inventory empty" "Found no elements|\\[\\]" "$(server_query "data get entity $NAME Inventory")"
STEP=$((STEP + 1)); client "survival" > "$OUT/step-$STEP-client.txt"; server_query "data get entity $NAME active_effects" > "$OUT/step-$STEP-server.txt"
expect "respawn: effects cleared on both sides" "^MATCH" "$(node "$ROOT/analysis/tools/m10-effects.js" "$OUT/step-$STEP-client.txt" "$OUT/step-$STEP-server.txt")"
# Spawnpunkt ist zufällig im Spawnradius: zurück in Sichtweite (Sichtweite 2) der Todesstelle, dann gesendete Entities vergleichen
server_cmd "tp $NAME 4.5 100 -25.5 180 0"; sleep 3
check_entities "respawn: world and entities sent again (dropped items match the server)" item pos,item
# --- Trennung im Todeszustand
server_cmd "tp $NAME 4.5 100 -30.5 180 0"; sleep 1; server_cmd "kill $NAME"; sleep 2
expect "second death detected" "dead=true" "$(client survival)"
client "quit" > /dev/null; sleep 3; echo "" >> "$OUT/client.in"; sleep 2
# ohne Automatik schon beim Login (sonst belebt der Client sich selbst wieder, bevor ein Befehl ankommt)
CLIENT_ARGS="--no-auto-respawn " start_client 2
sleep 3
DEADSTATE=$(client survival)
log "after reconnect while dead: $(echo "$DEADSTATE" | grep -o "health=[^ ]*\|dead=[^ ]*\|autoRespawn=[^ ]*" | tr '\n' ' ') server health $(srv Health)"
expect "after reconnect: automatic respawn off from the start" "autoRespawn=false" "$DEADSTATE"
expect_num "after reconnect while dead: server keeps health 0" "$(srv Health)" 0 0.0001
expect "after reconnect while dead: client detects the death from set_health 0" "dead=true" "$DEADSTATE"
expect "after reconnect: no respawn request sent by the client" "^0$" "$(grep -c 'serverbound/minecraft:client_command' "$OUT/client-packets-2.log")"
sleep 3
expect_num "after reconnect: still dead 3 s later (no automatic respawn)" "$(srv Health)" 0 0.0001
act "respawn"; sleep 3
expect_num "after reconnect dead: explicit respawn → server health 20" "$(srv Health)" 20 0.0001
expect "after reconnect: client alive in the end" "dead=false" "$(client survival)"
# --- Headless-Automatik bleibt erhalten
act "autorespawn on"
server_cmd "kill $NAME"; sleep 3
expect "headless auto respawn (configured): alive again without a manual command" "dead=false" "$(client survival)"
expect_num "headless auto respawn: server health 20" "$(srv Health)" 20 0.0001
act "slog"
