# M8 Schritt 3: Container – Truhe, große Truhe, Fass, Ofen (Phase E)
for c in "forceload add -32 -48 47 31" "fill -2 99 -34 14 99 -24 minecraft:stone" "fill -2 100 -34 14 104 -24 minecraft:air" \
  "setblock 0 100 -30 minecraft:chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:diamond\",count:5},{Slot:13b,id:\"minecraft:iron_ingot\",count:7}]}" \
  "setblock 3 100 -30 minecraft:chest[facing=south,type=right]" "setblock 4 100 -30 minecraft:chest[facing=south,type=left]" \
  "setblock 6 100 -30 minecraft:barrel[facing=up]" "setblock 8 100 -30 minecraft:furnace[facing=south]" "kill @e[type=item]" \
  "item replace entity $NAME hotbar.0 with minecraft:oak_planks 20" "item replace entity $NAME hotbar.1 with minecraft:coal 4" \
  "item replace entity $NAME hotbar.2 with minecraft:raw_iron 2" "item replace entity $NAME hotbar.4 with minecraft:stick 4"; do server_cmd "$c"; done
sleep 2
act "slot 9" 0.5
check_player "s3 initial"
# --- einfache Truhe (generic_9x3): eigene Plätze 0–26, Spielerplätze 27–53, Hotbar 54–62
server_cmd "tp $NAME 0.5 100 -27.5 180 0"; sleep 1.5
act "look-at 0.5 100.5 -29.5"; act "use" 1.2; act "inv"
expect "right click opens the chest (open_screen generic_9x3)" "menu [0-9]+ minecraft:generic_9x3" "$LAST"
check_container "chest contents after opening" "0 100 -30"
act "click 0 left shift"; check_container "shift click diamonds chest → player" "0 100 -30"; check_player "diamonds arrived in the player inventory"
act "click 54 left"; act "click 5 left"; check_container "planks placed into chest slot 5" "0 100 -30"; check_player "planks left the hotbar"
act "click 5 right"; act "click 30 left"; check_container "right click takes half back (10 stay)" "0 100 -30"; check_player "10 planks in the player inventory"
act "click 13 left"; act "click 14 right"; act "click 13 left"; check_container "split iron 1 into slot 14" "0 100 -30"
act "close"; check_player "after closing the chest"
act "use" 1.2; act "inv"; expect "chest opens again" "menu [0-9]+ minecraft:generic_9x3" "$LAST"
check_container "contents unchanged after reopening" "0 100 -30"
act "close"
# --- große Truhe (generic_9x6): eigene Plätze 0–53, Hotbar 81–89
server_cmd "tp $NAME 3.5 100 -27.5 180 0"; sleep 1.5
act "look-at 3.5 100.5 -29.5"; act "use" 1.2; act "inv"
expect "large chest opens as generic_9x6" "menu [0-9]+ minecraft:generic_9x6" "$LAST"
act "click 85 left shift"; check_container2 "shift click sticks into the large chest" "3 100 -30" "4 100 -30"
act "click 0 left"; act "click 40 left"; check_container2 "move sticks to slot 40 (second half)" "3 100 -30" "4 100 -30"
act "close"; check_player "after closing the large chest"
# --- Fass (generic_9x3)
server_cmd "tp $NAME 6.5 100 -27.5 180 0"; sleep 1.5
act "look-at 6.5 100.5 -29.5"; act "use" 1.2; act "inv"
expect "barrel opens as generic_9x3" "menu [0-9]+ minecraft:generic_9x3" "$LAST"
act "click 56 left shift"; check_container "shift click raw iron into the barrel" "6 100 -30"
act "click 0 left shift"; check_container "and back to the player" "6 100 -30"; check_player "raw iron back in the player inventory"
act "close"
# --- Ofen: 0 Eingabe, 1 Brennstoff, 2 Ergebnis, Hotbar 30–38
server_cmd "clear $NAME minecraft:raw_iron"; server_cmd "item replace entity $NAME hotbar.2 with minecraft:raw_iron 2"; sleep 1
server_cmd "tp $NAME 8.5 100 -27.5 180 0"; sleep 1.5
act "look-at 8.5 100.5 -29.5"; act "use" 1.2; act "inv"
expect "furnace opens (menu furnace)" "menu [0-9]+ minecraft:furnace" "$LAST"
act "click 31 left shift"; act "click 32 left shift"; check_container "coal → fuel slot, raw iron → input slot (quickMoveStack)" "8 100 -30"
sleep 11
act "inv"; expect "furnace produced an iron ingot (slot 2) and reports progress data" "\[2\] 1.* minecraft:iron_ingot" "$LAST"
expect "container_set_data values received (burn/cook progress)" "data \{" "$LAST"
act "click 2 left shift"; check_container "take the result with shift click" "8 100 -30"; check_player "iron ingot in the player inventory"
act "close"; check_player "after closing the furnace"
