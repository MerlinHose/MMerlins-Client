# M8 Schritt 1: einfache, serverbestätigte Linksklick-Interaktion (Aufnehmen und Ablegen)
server_cmd "item replace entity $NAME hotbar.0 with minecraft:oak_planks 16"; sleep 1.2
check_player "initial: 16 planks in hotbar 1"
act "inv"
act "click 36 left"
expect "left click on slot 36 picks up the stack (server cursor)" "cursor now 16.* minecraft:oak_planks" "$LAST"
check_player "after pickup: slot 36 empty on client and server"
act "click 20 left"
expect "left click on empty slot 20 places the stack" "cursor now empty" "$LAST"
check_player "after place: planks at inventory index 20"
