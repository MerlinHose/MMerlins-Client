# M8 Schritt 2: Klickarten im Spielerinventar (Container 0), Ausrüsten, Fallenlassen, 2×2-Crafting
# Menüplätze Container 0: 0 Ergebnis, 1–4 Raster, 5–8 Kopf/Brust/Beine/Füße, 9–35 Inventar, 36–44 Hotbar, 45 Nebenhand
for c in "item replace entity $NAME hotbar.0 with minecraft:oak_planks 16" "item replace entity $NAME hotbar.1 with minecraft:oak_planks 10" \
  "item replace entity $NAME inventory.0 with minecraft:cobblestone 64" "item replace entity $NAME hotbar.2 with minecraft:iron_chestplate" \
  "item replace entity $NAME hotbar.3 with minecraft:stick 3" "item replace entity $NAME hotbar.5 with minecraft:wooden_pickaxe[damage=7]" "kill @e[type=item]"; do server_cmd "$c"; done
sleep 1.5
check_player "s2 initial"
act "click 36 right"; expect "right click splits 16 → cursor 8" "cursor now 8.* minecraft:oak_planks" "$LAST"; check_player "split stack"
act "click 20 right"; expect "right click on empty slot places one (cursor 7)" "cursor now 7.* minecraft:oak_planks" "$LAST"; check_player "place one"
act "click 37 left"; expect "left click on same item merges (cursor empty)" "cursor now empty" "$LAST"; check_player "merge 7 into 10"
act "click 9 left shift"; check_player "shift click cobblestone (main → hotbar)"
act "swap 20 9"; check_player "number key 9 over slot 20 (SWAP with hotbar 9)"
act "click 38 left shift"; check_player "shift click chestplate equips it (chest slot)"
act "throw 37"; check_player "Q over slot 37 throws one"
expect "thrown item exists on the server" "Test passed" "$(server_query 'execute if entity @e[type=item,nbt={Item:{id:"minecraft:oak_planks"}}]')"
server_cmd "kill @e[type=item]"
act "click 36 left"; act "drag left 21 22 23 24"; expect "drag distributes 8 over 4 slots (cursor empty)" "cursor now empty" "$LAST"; check_player "drag left over 4 slots"
act "click 21 left"; act "dblclick 21"; check_player "double click PICKUP_ALL collects planks"
act "click 25 left"; check_player "put collected planks down"
act "swap 39 f"; check_player "F over sticks swaps into the offhand"
act "click 40 left"; act "click out left"; check_player "click outside with cursor drops the stack"
expect "dropped cobblestone exists" "Test passed" "$(server_query 'execute if entity @e[type=item,nbt={Item:{id:"minecraft:cobblestone"}}]')"
server_cmd "kill @e[type=item]"
act "slot 6" 0.5; act "drop"; check_player "Q in the world drops one from the main hand"
server_cmd "kill @e[type=item]"
act "inv"
act "click 25 left"; act "click 1 right"; act "click 2 right"; act "click 3 right"; act "click 4 right"; act "inv"
expect "2x2 grid with 4 planks shows the server result crafting_table in slot 0" "\[0\] 1.* minecraft:crafting_table" "$LAST"
act "click 25 left"; check_player "remaining planks back in slot 25"
act "click 0 left"; expect "taking the result puts the crafting table on the cursor" "cursor now 1.* minecraft:crafting_table" "$LAST"
act "click 30 left"; check_player "crafted table in the inventory, grid consumed"
act "inv"
act "close"; check_player "close inventory (container_close 0)"
