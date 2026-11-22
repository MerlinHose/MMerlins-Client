# M9 Schritt 1: Netzwerkebene – Spawn, Typen, IDs/UUIDs, Datenfelder, Ausrüstung, Bewegung, Teleport, Entfernen (docs/37 §1)
# Testfeld: Steinboden y=99, Luft bis y=106, Steindach y=107 (Zombies/Skelette verbrennen nicht). Alle Test-Entities tragen das Tag m9.
for c in "forceload add -32 -64 47 15" "fill -12 99 -48 20 99 -16 minecraft:stone" "fill -12 100 -48 20 106 -16 minecraft:air" \
  "fill -12 107 -48 20 107 -16 minecraft:stone" "kill @e[type=!player]" "kill @e[type=item]" \
  "summon pig 2.5 100 -31.5 {NoAI:1b,Rotation:[45f,0f],Tags:[m9]}" "summon cow 4.5 100 -31.5 {NoAI:1b,Tags:[m9]}" \
  "summon sheep 6.5 100 -31.5 {NoAI:1b,Color:14b,Tags:[m9]}" "summon zombie -1.5 100 -31.5 {NoAI:1b,Tags:[m9]}" \
  "summon skeleton -3.5 100 -31.5 {NoAI:1b,Tags:[m9]}" \
  "summon item 0.5 100 -33.5 {Item:{id:\"minecraft:diamond\",count:3},PickupDelay:32767,Tags:[m9]}"; do server_cmd "$c"; done
sleep 3
act "entities"
check_entities "spawn: all six types, IDs, UUIDs, position, rotation" all
for t in pig cow sheep zombie skeleton item; do check_count "spawned count" $t "0.5 100 -30.5" 20; done
# --- Datenfelder
server_cmd "data merge entity @e[type=pig,tag=m9,limit=1] {CustomName:\"Bob\"}"
server_cmd "data merge entity @e[type=sheep,tag=m9,limit=1] {Sheared:1b,Color:3b}"
server_cmd "damage @e[type=cow,tag=m9,limit=1] 3"
server_cmd "summon pig 8.5 100 -31.5 {NoAI:1b,Age:-24000,Tags:[m9,baby]}"
server_cmd "summon zombie -5.5 100 -31.5 {NoAI:1b,IsBaby:1b,Tags:[m9,baby]}"
sleep 2
check_entities "metadata: name, wool colour/sheared, health after damage, baby pig/zombie" all
expect "no unsupported entity data so far" "data unsupported 0" "$(client estats)"
# --- Ausrüstung
server_cmd "item replace entity @e[type=zombie,tag=m9,tag=!baby,limit=1] armor.head with minecraft:iron_helmet"
server_cmd "item replace entity @e[type=zombie,tag=m9,tag=!baby,limit=1] weapon.mainhand with minecraft:iron_sword"
server_cmd "item replace entity @e[type=skeleton,tag=m9,limit=1] weapon.mainhand with minecraft:bow"
sleep 1.5
check_entities "equipment: zombie helmet + sword, skeleton bow" zombie
check_entities "equipment: skeleton" skeleton
server_cmd "item replace entity @e[type=zombie,tag=m9,tag=!baby,limit=1] armor.head with minecraft:air"
sleep 1.5
check_entities "equipment removed again" zombie
# --- Bewegung (relativ) und Rotation über mehrere Ticks
for i in 1 2 3 4 5 6; do server_cmd "execute as @e[type=pig,tag=m9,tag=!baby] at @s run tp @s ~0.37 ~ ~0.11 ~15 ~"; sleep 0.3; done
sleep 1.5
check_entities "relative movement over several updates (pig)" pig pos,rot,head,ground
server_cmd "execute as @e[type=cow,tag=m9] at @s run tp @s ~ ~0.5 ~ 120 -30"
sleep 1.5
check_entities "rotation, pitch and head turn (cow)" cow pos,rot,head
# --- Teleport (> 8 Blöcke → absolute Position)
server_cmd "tp @e[type=skeleton,tag=m9] 15.5 100 -40.5 270 0"
sleep 1.5
check_entities "teleport over 12 blocks (position sync)" skeleton pos,rot,head
expect "teleport recognised (display snapped)" "teleport snaps [1-9]" "$(client estats)"
# --- Entfernen
server_cmd "kill @e[type=cow,tag=m9]"
# Konsole (tail -f, bis ~1 s Verzögerung) + 20 Todesticks (1 s) vor remove_entities; 1,5 s war ein Wettlauf (M14-Regression)
sleep 2.5
check_count "cow removed (remove_entities)" cow "0.5 100 -30.5" 30
# --- KI-Bewegung: frei laufen lassen, dann einfrieren und vergleichen
server_cmd "summon sheep 0.5 100 -40.5 {Tags:[m9,walker]}"; server_cmd "summon pig -6.5 100 -40.5 {Tags:[m9,walker]}"
sleep 8
server_cmd "data merge entity @e[tag=walker,type=sheep,limit=1] {NoAI:1b}"; server_cmd "data merge entity @e[tag=walker,type=pig,limit=1] {NoAI:1b}"
sleep 2
check_entities "AI walkers after freeze: position/rotation match the server" all pos,rot,ground,health,name,baby,wool,item,equip
act "estats"
