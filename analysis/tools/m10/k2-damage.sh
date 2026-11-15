# M10 Schritt 2: Schadensquellen und Rüstung (docs/41 §3). Spieler A (eigener Client) im Überlebensmodus; maßgeblich ist die
# Gesundheit auf dem Server (data get entity … Health); der Client muss denselben Wert anzeigen (set_health) und den Schadenstyp (damage_event).
# Wasser- und Feuerphysik ist im Client nicht umgesetzt: geprüft wird nur die Schadensverarbeitung (docs/40 §5).
for c in "forceload add -32 -64 63 15" "fill -14 99 -48 30 99 -16 minecraft:stone" "fill -14 100 -48 30 106 -16 minecraft:air" \
  "fill -14 107 -48 30 107 -16 minecraft:stone" "fill -14 103 -44 30 103 -22 minecraft:light[level=15]" "kill @e[type=!player]" "kill @e[type=item]" \
  "fill 36 99 -34 44 99 -26 minecraft:stone" "fill 36 100 -34 44 125 -26 minecraft:air" \
  "fill 46 99 -34 52 99 -26 minecraft:stone" "fill 46 100 -34 52 104 -26 minecraft:glass" "fill 47 100 -33 51 103 -27 minecraft:water" \
  "gamemode survival $NAME" "clear $NAME" "effect clear $NAME"; do server_cmd "$c"; done
sleep 2
# Natürliche Regeneration während des Tests aus (sonst heilt volle Sättigung ~1 HP je 0,5 s und verfälscht die Prüfungen); danach zurück
ORIG_REGEN=$(server_query "gamerule natural_health_regeneration" | grep -oE "(true|false)" | tail -1); [ -z "$ORIG_REGEN" ] && ORIG_REGEN=true
log "gamerule natural_health_regeneration was $ORIG_REGEN"; server_cmd "gamerule natural_health_regeneration false"
# Sofortheilung wirkt erst im nächsten Server-Tick: erst danach Effekte entfernen
heal() { server_cmd "effect give $NAME minecraft:instant_health 1 10 true"; sleep 1; server_cmd "effect clear $NAME"; sleep 1; }
srv_health() { server_num "data get entity $1 Health"; }
check_health() {
  local c s; c=$(client_field health); s=$(srv_health "$NAME")
  expect_num "$1: client health equals the server" "$c" "$s" 0.0001
}
damage_type() { client_field lastDamage; }
heal
check_health "start"
# --- 1 Zombie
server_cmd "tp $NAME 0.5 100 -27.5 180 0"; server_cmd "summon zombie 0.5 100 -29.5 {Tags:[m10,z1],Rotation:[0f,0f]}"
for i in $(seq 1 30); do sleep 0.5; [ "$(damage_type)" = "minecraft:mob_attack" ] && break; done
server_cmd "kill @e[tag=z1]"; sleep 1.5
expect "zombie: damage_event type mob_attack" "^minecraft:mob_attack$" "$(damage_type)"
check_health "zombie"
expect "zombie: health below 20 (regeneration off)" "yes" "$(node -e "console.log(+process.argv[1] < 20 ? 'yes' : 'no')" -- "$(client_field health)")"
# --- 2 Skelett (Pfeil)
heal; server_cmd "kill @e[type=arrow]"
server_cmd "tp $NAME 10.5 100 -22.5 180 0"; server_cmd "summon skeleton 10.5 100 -32.5 {Tags:[m10,s1],Rotation:[0f,0f],equipment:{mainhand:{id:\"minecraft:bow\",count:1}}}"
for i in $(seq 1 40); do sleep 0.5; [ "$(damage_type)" = "minecraft:arrow" ] && break; done
server_cmd "kill @e[tag=s1]"; server_cmd "kill @e[type=arrow]"; sleep 1.5
expect "skeleton: damage_event type arrow" "^minecraft:arrow$" "$(damage_type)"
check_health "skeleton"
# --- 3 Fallschaden: 12 Blöcke → ceil(12 − 3) = 9
heal; H0=$(srv_health $NAME); server_cmd "tp $NAME 40.5 112 -30.5 180 0"; sleep 4
expect "fall: damage_event type fall" "^minecraft:fall$" "$(damage_type)"
expect_num "fall from 12 blocks: ceil(12 - 3) = 9 damage on the server (from $H0)" "$(srv_health $NAME)" "$(node -e "console.log(+process.argv[1] - 9)" -- "$H0")" 0.0001
check_health "fall"
# --- 4 Feuer
heal; server_cmd "tp $NAME 40.5 100 -30.5 180 0"; sleep 1; server_cmd "setblock 40 100 -31 minecraft:fire"; sleep 3; server_cmd "setblock 40 100 -31 minecraft:air"
sleep 1
expect "fire: damage_event type in_fire or on_fire" "^minecraft:(in_fire|on_fire)$" "$(damage_type)"
# Nachbrennen (on_fire, ~8 s) würde zwischen Client- und Serverabfrage weiter Schaden machen: Feuerresistenz stoppt ihn vor dem Vergleich
server_cmd "effect give $NAME minecraft:fire_resistance 30 0 true"; sleep 1.5
check_health "fire"
server_cmd "effect clear $NAME"
sleep 6
check_health "after burning stopped"
# --- 5 Ertrinken: Luft 300 → 0 in 15 s, danach 2 Schaden je Sekunde
heal; server_cmd "tp $NAME 48.5 100 -30.5 180 0"
for i in $(seq 1 50); do sleep 0.5; [ "$(damage_type)" = "minecraft:drown" ] && break; done
AIR=$(client_field air)
server_cmd "tp $NAME 40.5 100 -30.5 180 0"; sleep 1.5
expect "drowning: damage_event type drown" "^minecraft:drown$" "$(damage_type)"
expect "drowning: client air supply reached 0 or less (entity data 1)" "yes" "$(node -e "console.log(+process.argv[1] <= 0 ? 'yes' : 'no')" -- "$AIR")"
check_health "drowning"
# --- 6 anderer Spieler: Faust, dann Eisenschwert gegen Eisenbrustpanzer
start_client 1 2
server_cmd "gamemode survival $NAME2"; server_cmd "clear $NAME2"; server_cmd "effect clear $NAME2"
heal; server_cmd "tp $NAME 20.5 100 -30.5 0 0"; server_cmd "tp $NAME2 20.5 100 -28.0 180 0"; sleep 2; H0=$(srv_health $NAME)
client2 "look-at 20.5 101.2 -30.5" > /dev/null; client2 "hit 1.0" > /dev/null; sleep 1
expect "other player: damage_event type player_attack" "^minecraft:player_attack$" "$(damage_type)"
expect_num "other player, fist: 1 damage on the server (from $H0)" "$(srv_health $NAME)" "$(node -e "console.log(+process.argv[1] - 1)" -- "$H0")" 0.0001
check_health "other player fist"
heal; server_cmd "item replace entity $NAME armor.chest with minecraft:iron_chestplate"; server_cmd "item replace entity $NAME2 weapon.mainhand with minecraft:iron_sword"
sleep 1.5
expect_num "client armor attribute with iron chestplate" "$(client_field armor)" 6 0.0001
server_cmd "tp $NAME 20.5 100 -30.5 0 0"; server_cmd "tp $NAME2 20.5 100 -28.0 180 0"; sleep 1.5; H0=$(srv_health $NAME)
client2 "look-at 20.5 101.2 -30.5" > /dev/null; client2 "hit 1.0" > /dev/null; sleep 1
expect_num "iron sword vs armor 6: 6·(1 − clamp(6 − 6/2, 1.2, 20)/25) = 5.28 damage (from $H0)" "$(srv_health $NAME)" "$(node -e "console.log(+process.argv[1] - 5.28)" -- "$H0")" 0.0002
check_health "armor reduction"
expect "chestplate lost max(1, 6/4) = 1 durability" "minecraft:iron_chestplate 1 minecraft:damage=1" "$(client invdump)"
# --- 7 eigener Angriff auf den anderen Spieler
# erst den Platz wählen, dann das Schwert dorthin legen: der Server stellt beim Login den zuletzt gewählten Platz wieder her (set_held_slot)
act "slot 1"; server_cmd "item replace entity $NAME hotbar.0 with minecraft:iron_sword"
server_cmd "tp $NAME 20.5 100 -30.5 0 0"; server_cmd "tp $NAME2 20.5 100 -28.0 180 0"; sleep 1.5
H2=$(srv_health $NAME2); act "look-at 20.5 101.2 -28.0"; act "hit 1.0"
expect_num "own iron sword hit on the other player: 6 damage (from $H2)" "$(srv_health $NAME2)" "$(node -e "console.log(+process.argv[1] - 6)" -- "$H2")" 0.0001
expect "own hit on a player confirmed (damage_event caused by us)" "confirmed=0->1" "$LAST"
# --- 8 Schild (blocks_attacks, docs/38 §4): der andere Spieler hält den Schild in der Nebenhand erhoben (usehold) und blickt zu uns.
# Erwartung 26.3: Schaden ganz geblockt; Schildschaden ⌊1 + Schaden⌋ (Schwelle 3); Axt deaktiviert den Schild 5 s (cooldown 100 Ticks,
# der Server beendet die Benutzung); danach geht ein Schwerttreffer durch.
shield_damage() { local d; d=$(server_num "data get entity $NAME2 equipment.offhand.components.\"minecraft:damage\""); echo "${d:-0}"; }
plus() { node -e "console.log(+process.argv[1] + +process.argv[2])" -- "$1" "$2"; }
server_cmd "item replace entity $NAME2 weapon.mainhand with minecraft:air"; server_cmd "item replace entity $NAME2 weapon.offhand with minecraft:shield"
server_cmd "item replace entity $NAME hotbar.1 with minecraft:iron_axe"; server_cmd "effect give $NAME2 minecraft:instant_health 1 10 true"
server_cmd "tp $NAME 20.5 100 -30.5 0 0"; server_cmd "tp $NAME2 20.5 100 -28.0 180 0"; sleep 2
client2 "look-at 20.5 101.6 -30.5" > /dev/null
U=$(client2 "usehold on"); log "client2: $(echo "$U" | tr '\n' ' ')"
expect "shield: other client holds the use locally (blocks_attacks, hold)" "using=true" "$U"
sleep 1.5
H2=$(srv_health $NAME2); D0=$(shield_damage); act "look-at 20.5 101.2 -28.0"; act "hit 1.0"; sleep 0.5
expect_num "shield raised: iron sword hit fully blocked (health stays $H2)" "$(srv_health $NAME2)" "$H2" 0.0001
expect_num "shield durability +⌊1 + 6⌋ = 7 (from $D0)" "$(shield_damage)" "$(plus "$D0" 7)" 0.0001
act "slot 2"; sleep 1.5
# Axt: Treffer geblockt, Schild 5 s (100 Ticks) gesperrt; der zweite Axttreffer muss innerhalb dieser 5 s folgen, weil client2 die Taste
# weiter hält und den Schild danach wie Vanilla (rightClickDelay-Wiederholung) erneut hebt. Prüfungen erst danach (Serverabfragen dauern).
H2=$(srv_health $NAME2); D0=$(shield_damage); act "hit 1.0"; sleep 1.3; act "hit 1.0"; sleep 0.5
H3=$(srv_health $NAME2); D1=$(shield_damage)
expect_num "shield: first iron axe hit blocked, shield durability +⌊1 + 9⌋ = 10 (from $D0)" "$D1" "$(plus "$D0" 10)" 0.0001
expect_num "shield disabled by the axe: second axe hit goes through, 9 damage (from $H2)" "$H3" "$(plus "$H2" -9)" 0.0001
S2=$(client2 "slog")
expect "axe disables the shield: other client got cooldown minecraft:shield 100" "cooldown minecraft:shield 100" "$S2"
expect "axe disables the shield: server ended the use, client follows (living flags)" "use stopped by the server" "$S2"
expect "shield on cooldown: repeated use attempts while held give PASS (no local use)" "on cooldown \(PASS\)" "$S2"
client2 "usehold off" > /dev/null
act "survival"
act "slog"
server_cmd "gamerule natural_health_regeneration $ORIG_REGEN"
# Wasserbecken wieder entfernen: m7-interact räumt x 28..46 frei und würde sonst die Glaswand bei x=46 öffnen (docs/41 §9)
server_cmd "fill 46 100 -34 52 104 -26 minecraft:air"
