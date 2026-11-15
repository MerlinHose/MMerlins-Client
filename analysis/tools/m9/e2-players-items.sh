# M9 Schritt 2: anderer Spieler, Items (Fallenlassen/Aufsammeln), Entladen/Neuladen, Dimensionswechsel, viele Entities (docs/37 §1)
for c in "forceload add -32 -64 47 15" "fill -12 99 -48 20 99 -16 minecraft:stone" "fill -12 100 -48 20 106 -16 minecraft:air" \
  "fill -12 107 -48 20 107 -16 minecraft:stone" "kill @e[type=!player]" "kill @e[type=item]" \
  "summon pig 2.5 100 -31.5 {NoAI:1b,Tags:[m9]}" "summon zombie -1.5 100 -31.5 {NoAI:1b,Tags:[m9]}"; do server_cmd "$c"; done
sleep 2
# --- anderer Spieler (zweiter Terminalclient)
start_client 1 2
server_cmd "gamemode creative $NAME2"; server_cmd "clear $NAME2"; server_cmd "tp $NAME2 3.5 100 -29.5 90 20"; sleep 2
check_entities "other player: UUID, position, rotation, onGround" player pos,rot,ground,equip
expect "player list contains the other player with its entity" "PLAYER .* $NAME2 gamemode=1 .*entity=[0-9]+" "$(client players)"
expect "own player is in the list but not an entity" "PLAYER .* $NAME gamemode=1 .*entity=-" "$(client players)"
client2 "look 30 -10" > /dev/null; client2 "walk f 20" > /dev/null; sleep 2
check_entities "other player walked with its own physics (server position)" player pos,rot,ground
server_cmd "item replace entity $NAME2 armor.chest with minecraft:diamond_chestplate"; server_cmd "item replace entity $NAME2 weapon.mainhand with minecraft:diamond_sword"
sleep 1.5
check_entities "other player's equipment (chest, main hand)" player pos,rot,equip
# --- Items: anderer Spieler wirft einen Gegenstand, er landet und wird verglichen
server_cmd "item replace entity $NAME2 hotbar.1 with minecraft:emerald 5"; client2 "slot 2" > /dev/null; sleep 0.5
client2 "drop" > /dev/null; sleep 3
check_entities "item dropped by the other player (type, count, landed position)" item pos,item,ground
check_count "dropped item exists" item "0.5 100 -30.5" 30
# eigener Spieler sammelt ein Item auf: Entity verschwindet (take_item_entity + remove_entities), Inventar hat es
server_cmd "tp $NAME2 12.5 100 -42.5"; sleep 1
server_cmd "kill @e[type=item]"; server_cmd "summon item -6.5 100 -24.5 {Item:{id:\"minecraft:gold_ingot\",count:2},PickupDelay:0,Tags:[m9]}"; sleep 1.5
check_entities "item before pickup" item pos,item
server_cmd "tp $NAME -6.5 100 -24.5"; sleep 2
check_count "item picked up: entity removed in client and server" item "-6.5 100 -24.5" 10
expect "picked-up gold ingots arrived in the own inventory" "minecraft:gold_ingot 2" "$(client invdump)"
server_cmd "tp $NAME 0.5 100 -27.5 180 0"; sleep 1
# --- Entladen und Neuladen: weit weg (außerhalb der Sichtweite), zurück
# UUIDs der Test-Entities merken: in der Ferne können andere, bereits vorhandene Tiere sichtbar werden (nur die Test-UUIDs zählen)
TEST_UUIDS=$(client "edump" | grep -E "^  ENT " | grep -E "minecraft:(pig|zombie) " | awk '{print $3}')
server_cmd "tp $NAME 600.5 120 600.5"; sleep 4
FAR=$(client "edump")
for u in $TEST_UUIDS; do
  if echo "$FAR" | grep -q "$u"; then verdict FAIL "far away: test entity $u still in the client"; else verdict OK "far away: test entity $u unloaded in the client (remove_entities)"; fi
done
expect "far away: the server still has the pig (only no longer tracked)" "Test passed" "$(server_query "execute positioned 0.5 100 -30.5 if entity @e[type=pig,distance=..30]")"
server_cmd "tp $NAME 0.5 100 -27.5 180 0"; sleep 5
check_entities "back: entities sent again and identical (same UUIDs)" all pos,rot,ground,health,equip
# --- Dimensionswechsel: in den Nether und zurück
server_cmd "execute in minecraft:the_nether run tp $NAME 0.5 128 0.5"; sleep 5
expect "nether: no overworld pig/zombie kept after the dimension change" "^  0 entities|^  [0-9]+ entities" "$(client entities)"
check_client_count "nether: overworld pig not kept in the client" pig 0
expect "nether: client dimension is the nether" "dimension minecraft:the_nether" "$(client entities)"
server_cmd "execute in minecraft:overworld run tp $NAME 0.5 100 -27.5 180 0"; sleep 5
check_entities "back in the overworld: entities identical again" all pos,rot,ground,health,equip
# --- viele Entities gleichzeitig: 50 Schweine im Raster
for x in 0 1 2 3 4 5 6 7 8 9; do for z in 0 1 2 3 4; do server_cmd "summon pig $((x * 2 - 9)).5 100 $((z * 2 - 46)).5 {NoAI:1b,Tags:[m9,herd]}"; done; done
sleep 4
check_count "50 pigs plus 1: count" pig "0.5 100 -30.5" 40
check_entities "50 pigs: every UUID, position and rotation" pig pos,rot,ground,health
server_cmd "kill @e[tag=herd]"; sleep 2
check_count "50 pigs removed" pig "0.5 100 -30.5" 40
act "estats"
