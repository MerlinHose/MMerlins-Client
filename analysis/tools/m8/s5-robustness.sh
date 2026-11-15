# M8 Schritt 5: Sicherheit/Konsistenz (Phase G) und Last (Phase H)
for c in "forceload add -32 -48 47 31" "fill -2 99 -34 4 99 -24 minecraft:stone" "fill -2 100 -34 4 104 -24 minecraft:air" "kill @e[type=item]" \
  "clear $NAME" "setblock 0 100 -30 minecraft:chest[facing=south]" \
  "item replace entity $NAME hotbar.0 with minecraft:oak_planks 64" "item replace entity $NAME inventory.0 with minecraft:cobblestone 32"; do server_cmd "$c"; done
sleep 2
act "slot 9" 0.5
check_player "s5 initial"
act "click 999 left"; expect "invalid slot index is rejected locally (no packet)" "dropped: invalid slot 999" "$LAST"
server_cmd "tp $NAME 0.5 100 -27.5 180 0"; sleep 1.5
act "look-at 0.5 100.5 -29.5"; act "use" 1.2; act "inv"
expect "chest opened" "menu [0-9]+ minecraft:generic_9x3" "$LAST"
CID=$(echo "$LAST" | grep -o "menu [0-9]*" | head -1 | cut -d' ' -f2)
server_cmd "setblock 0 100 -30 minecraft:air"; sleep 1.5
act "inv"; expect "server closed the container after the chest was removed (menu 0)" "menu 0 player inventory" "$LAST"
act "clickc $CID 0"; expect "click for the closed container $CID is discarded" "dropped: container changed" "$LAST"
check_player "inventory unaffected by the stale click"
server_cmd "give $NAME minecraft:written_book[written_book_content={title:\"t\",author:\"a\"}]"; sleep 1.5
act "inv"; expect "undecodable component is counted, connection stays" "undecodable [1-9]" "$LAST"
server_cmd "clear $NAME minecraft:written_book"; sleep 1.2
check_player "after removing the book the inventory matches again"
act "resync"; expect "resync request answered with full content" "content\(c0\)" "$LAST"
check_player "explicit resync: client takes over the server state"
act "burst 200 36 20" 40; expect "200 rapid clicks executed" "burst done" "$LAST"
check_player "after 200 rapid clicks"
expect "planks conserved after load (64)" "Found 64 matching" "$(server_query "clear $NAME minecraft:oak_planks 0")"
act "burstq 40 36 20" 0.1
client "quit" 2 > /dev/null; sleep 3; echo "" >> "$OUT/client.in"; sleep 2
start_client 2
server_cmd "tp $NAME 0.5 100 -27.5 180 0"; sleep 2
check_player "after disconnect during queued clicks"
expect "planks conserved after disconnect (64, nothing duplicated or lost)" "Found 64 matching" "$(server_query "clear $NAME minecraft:oak_planks 0")"
expect "no planks dropped as entity" "Test failed" "$(server_query 'execute if entity @e[type=item,nbt={Item:{id:"minecraft:oak_planks"}}]')"
act "inv"
