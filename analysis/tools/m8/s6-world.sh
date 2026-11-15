# M8 Schritt 6: Inventaränderungen durch Weltaktionen (docs/30 §8) – Abbau mit Werkzeug (damage), Aufheben, Platzieren
for c in "forceload add -32 -48 47 31" "fill -2 99 -34 6 99 -24 minecraft:stone" "fill -2 100 -34 6 104 -24 minecraft:air" \
  "setblock 0 100 -30 minecraft:stone" "kill @e[type=item]" \
  "item replace entity $NAME hotbar.0 with minecraft:wooden_pickaxe" "item replace entity $NAME hotbar.1 with minecraft:dirt 5"; do server_cmd "$c"; done
sleep 2
act "slot 1" 0.5
check_player "s6 initial"
# Abbau: Holzspitzhacke an Stein → damage 1, Bruchstein fällt
server_cmd "tp $NAME 0.5 100 -27.5 180 0"; sleep 1.5
act "look-at 0.5 100.5 -29.5"; act "dig 80" 4.5
check_player "after break: pickaxe damage 1 on client and server"
# Aufheben: zum Item gehen
server_cmd "tp $NAME 0.5 100 -29.5 180 0"; sleep 2
check_player "after pickup: cobblestone arrived via server update"
# Platzieren: Erde auf den Boden setzen → Stapel 5 → 4
server_cmd "tp $NAME 0.5 100 -27.5 180 0"; sleep 1.5
act "slot 2" 0.5
act "look-at 0.5 100.0 -29.5"; act "use" 1.2
check_player "after place: dirt stack reduced on client and server"
