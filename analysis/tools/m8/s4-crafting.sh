# M8 Schritt 4: 3×3-Crafting an der Werkbank, wiederholtes Crafting per Shift, Rückgabe beim Schließen, erneuter Login
for c in "forceload add -32 -48 47 31" "fill 8 99 -34 14 99 -24 minecraft:stone" "fill 8 100 -34 14 104 -24 minecraft:air" \
  "setblock 10 100 -30 minecraft:crafting_table" "kill @e[type=item]" "clear $NAME" \
  "item replace entity $NAME hotbar.0 with minecraft:oak_planks 8" "item replace entity $NAME hotbar.1 with minecraft:stick 4" \
  "item replace entity $NAME hotbar.2 with minecraft:oak_log 3"; do server_cmd "$c"; done
sleep 2
act "slot 9" 0.5
check_player "s4 initial"
# Werkbank (crafting): 0 Ergebnis, 1–9 Raster (zeilenweise), 10–36 Inventar, 37–45 Hotbar
server_cmd "tp $NAME 10.5 100 -27.5 180 0"; sleep 1.5
act "look-at 10.5 100.5 -29.5"; act "use" 1.2; act "inv"
expect "right click opens the crafting table (menu crafting)" "menu [0-9]+ minecraft:crafting" "$LAST"
# Holzspitzhacke: Bretter in 1,2,3; Stöcke in 5 und 8
act "click 37 left"; act "click 1 right"; act "click 2 right"; act "click 3 right"; act "click 37 left"
act "click 38 left"; act "click 5 right"; act "click 8 right"; act "click 38 left"
act "inv"; expect "3x3 recipe: server shows wooden_pickaxe in the result slot" "\[0\] 1.* minecraft:wooden_pickaxe" "$LAST"
check_player "ingredients left the inventory (5 planks, 2 sticks remain)"
act "click 0 left shift"; act "inv"
expect "shift click on the result crafts into the inventory, grid empty" "^  menu .*cursor empty" "$LAST"
check_player "wooden pickaxe crafted and stored by the server"
# wiederholtes Crafting: 3 Stämme in Platz 1 → 4 Bretter je Stamm; Shift-Klick craftet alle (12 Bretter)
act "click 39 left"; act "click 1 left"; act "inv"
expect "log in the grid gives 4 planks as result" "\[0\] 4.* minecraft:oak_planks" "$LAST"
act "click 0 left shift"; check_player "shift click crafted all three logs (12 more planks)"
# Rückgabe: Stöcke im Raster lassen und schließen → CraftingMenu#removed gibt sie zurück
act "click 38 left"; act "click 5 left"; act "inv"
act "close"; check_player "closing the crafting table returns the grid items"
expect "returned sticks were not dropped as item entity" "Test failed" "$(server_query 'execute if entity @e[type=item,nbt={Item:{id:"minecraft:stick"}}]')"
# erneuter Login
act "inv"
client "quit" 3 > /dev/null; sleep 3; echo "" >> "$OUT/client.in"; sleep 2
start_client 2
server_cmd "tp $NAME 10.5 100 -27.5 180 0"; sleep 2
check_player "inventory after disconnect and new login"
