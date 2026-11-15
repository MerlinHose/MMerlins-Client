# M10 Schritt 3: Nahrung und Statuseffekte (docs/41 §4). Spieler A im Überlebensmodus, natürliche Regeneration aus (danach zurück).
for c in "forceload add -32 -64 63 15" "fill -14 99 -48 30 99 -16 minecraft:stone" "fill -14 100 -48 30 106 -16 minecraft:air" \
  "fill -14 107 -48 30 107 -16 minecraft:stone" "fill -14 103 -44 30 103 -22 minecraft:light[level=15]" "kill @e[type=!player]" "kill @e[type=item]" \
  "summon pig 10.5 100 -30.5 {NoAI:1b,Tags:[m10,q1]}" "summon pig 13.5 100 -30.5 {NoAI:1b,Tags:[m10,q2]}" \
  "gamemode survival $NAME" "clear $NAME" "effect clear $NAME" "tp $NAME 0.5 100 -27.5 180 0"; do server_cmd "$c"; done
sleep 2
ORIG_REGEN=$(server_query "gamerule natural_health_regeneration" | grep -oE "(true|false)" | tail -1); [ -z "$ORIG_REGEN" ] && ORIG_REGEN=true
server_cmd "gamerule natural_health_regeneration false"
heal() { server_cmd "effect give $NAME minecraft:instant_health 1 10 true"; sleep 1; server_cmd "effect clear $NAME"; sleep 1; }
srv() { server_num "data get entity $NAME $1"; }
check_food() {
  expect_num "$1: client food equals the server foodLevel" "$(client_field food)" "$(srv foodLevel)" 0
  expect_num "$1: client saturation equals the server" "$(client_field saturation)" "$(srv foodSaturationLevel)" 0.0001
}
check_effects() {
  STEP=$((STEP + 1))
  client "survival" > "$OUT/step-$STEP-client.txt"
  server_query "data get entity $NAME active_effects" > "$OUT/step-$STEP-server.txt"
  local res; res=$(node "$ROOT/analysis/tools/m10-effects.js" "$OUT/step-$STEP-client.txt" "$OUT/step-$STEP-server.txt")
  echo "$res" | sed 's/^/    /' >> "$OUT/transcript.txt"
  if echo "$res" | grep -q "^MATCH"; then verdict OK "$1 (effects: $res)"; else verdict FAIL "$1 (effects: $(echo "$res" | tr '\n' ' '))"; fi
}
heal
# --- Definierte Ausgangslage (M12-Regression): Nahrung und Sättigung 20/20, unabhängig vom gespeicherten Spielerzustand früherer Läufe.
#     Hunger 255 verbraucht 1,28 Erschöpfung je Tick (0,32 Punkte): 5 s = 32 Punkte von 40 → Sättigung 0, Nahrung ≈ 8. Der Server sendet
#     set_health nur bei Änderung von Gesundheit, Nahrung oder „Sättigung = 0“ (ServerPlayer), daher muss die Nahrung sinken.
server_cmd "effect give $NAME minecraft:saturation 1 255 true"; sleep 1.5
# --- Hunger erzeugen: Hunger 255 verbraucht Nahrung schnell
server_cmd "effect give $NAME minecraft:hunger 5 255"; sleep 6; server_cmd "effect clear $NAME"; sleep 1.5
F0=$(srv foodLevel)
log "food after hunger effect: $F0"
check_food "after hunger"
# --- Essen vollständig: gebratenes Rindfleisch, 32 Ticks, +8 Nahrung
server_cmd "item replace entity $NAME hotbar.0 with minecraft:cooked_beef 5"; act "slot 1"; sleep 1
act "eat 40"
expect_num "eating cooked beef: food +8 (capped at 20)" "$(srv foodLevel)" "$(node -e "console.log(Math.min(20, +process.argv[1] + 8))" -- "$F0")" 0
check_food "after eating"
expect "stack 5 -> 4 after eating (server confirmed slot update)" "minecraft:cooked_beef 4" "$(client invdump)"
expect "server completed the use (entity_event 9)" "use_item_complete" "$(client slog)"
# --- Essen abbrechen: nach 10 Ticks loslassen → RELEASE_USE_ITEM, keine Änderung
# 3 s = 19,2 Punkte > Sättigung 12,8 nach dem Rindfleisch: Nahrung sinkt sicher (set_health), Sättigung 0
server_cmd "effect give $NAME minecraft:hunger 3 255"; sleep 4; server_cmd "effect clear $NAME"; sleep 1.5
F1=$(srv foodLevel)
act "eat 10"
expect "early release sent RELEASE_USE_ITEM" "release_use_item" "$(client slog)"
expect_num "aborted eating: food unchanged" "$(srv foodLevel)" "$F1" 0
expect "aborted eating: stack still 4" "minecraft:cooked_beef 4" "$(client invdump)"
check_food "after abort"
# --- Goldener Apfel (immer essbar): Regeneration II + Absorption
server_cmd "item replace entity $NAME hotbar.1 with minecraft:golden_apple 1"; act "slot 2"; sleep 1
act "eat 40"; sleep 0.5
check_effects "golden apple: regeneration II and absorption on client and server"
expect_num "absorption 4 from the golden apple (client = server AbsorptionAmount)" "$(client_field absorption)" "$(srv AbsorptionAmount)" 0.0001
server_cmd "effect clear $NAME"; sleep 1
check_effects "effects cleared on both sides"
# --- Geschwindigkeit / Langsamkeit: Attribut movement_speed und eigene Physik
server_cmd "effect give $NAME minecraft:speed 60 1"; sleep 1.5
check_effects "speed II"
expect_num "speed II: client movement_speed attribute equals the server" "$(client_field moveSpeed)" "$(server_query "attribute $NAME minecraft:movement_speed get" | grep -oE "is [0-9.]+" | cut -d' ' -f2)" 0.0001
expect_num "speed II: own physics uses 0.1 · (1 + 2 · 0.2) = 0.14" "$(client_field physicsSpeed)" 0.14 0.0001
server_cmd "effect clear $NAME"; server_cmd "effect give $NAME minecraft:slowness 60 0"; sleep 1.5
expect_num "slowness I: own physics uses 0.1 · (1 − 0.15) = 0.085" "$(client_field physicsSpeed)" 0.085 0.0001
server_cmd "effect clear $NAME"; sleep 1.5
expect_num "no effect: physics speed back to 0.1" "$(client_field physicsSpeed)" 0.1 0.0001
# --- Resistenz II: Schaden · (25 − 10)/25
heal; H0=$(srv Health)
server_cmd "effect give $NAME minecraft:resistance 30 1"; sleep 1; server_cmd "damage $NAME 5 minecraft:generic"; sleep 1.5
expect_num "resistance II: 5 generic damage -> 3 (from $H0)" "$(srv Health)" "$(node -e "console.log(+process.argv[1] - 3)" -- "$H0")" 0.0001
expect_num "resistance: client health equals the server" "$(client_field health)" "$(srv Health)" 0.0001
server_cmd "effect clear $NAME"
# --- Soforteffekte: Schaden I = 6, Heilung I = 4
heal; H0=$(srv Health)
server_cmd "effect give $NAME minecraft:instant_damage 1 0"; sleep 1.5
expect_num "instant damage I: 6 damage (from $H0)" "$(srv Health)" "$(node -e "console.log(+process.argv[1] - 6)" -- "$H0")" 0.0001
H1=$(srv Health); server_cmd "effect give $NAME minecraft:instant_health 1 0"; sleep 1.5
expect_num "instant health I: +4 (from $H1)" "$(srv Health)" "$(node -e "console.log(Math.min(20, +process.argv[1] + 4))" -- "$H1")" 0.0001
expect_num "instant effects: client health equals the server" "$(client_field health)" "$(srv Health)" 0.0001
check_effects "instant effects leave no lasting effect"
# --- Regeneration und Vergiftung
server_cmd "damage $NAME 8 minecraft:generic"; sleep 1; H0=$(srv Health)
server_cmd "effect give $NAME minecraft:regeneration 10 1"; sleep 3
expect "regeneration II heals (health rose above $H0)" "yes" "$(node -e "console.log(+process.argv[1] > +process.argv[2] ? 'yes' : 'no')" -- "$(srv Health)" "$H0")"
check_effects "regeneration II active"
server_cmd "effect clear $NAME"; sleep 1.5
expect_num "regeneration (after it ended): client health equals the server" "$(client_field health)" "$(srv Health)" 0.0001
H0=$(srv Health)
server_cmd "effect give $NAME minecraft:poison 10 0"; sleep 3
expect "poison damages (health fell below $H0)" "yes" "$(node -e "console.log(+process.argv[1] < +process.argv[2] ? 'yes' : 'no')" -- "$(srv Health)" "$H0")"
expect "poison: damage type magic" "^minecraft:magic$" "$(client_field lastDamage)"
check_effects "poison active"
server_cmd "effect clear $NAME"; sleep 1
# --- Stärke I (+3 Angriffsschaden) und Schwäche I (−4)
server_cmd "item replace entity $NAME hotbar.0 with minecraft:iron_sword"; act "slot 1"
server_cmd "effect give $NAME minecraft:strength 60 0"; sleep 1
server_cmd "tp $NAME 10.5 100 -27.5 180 0"; sleep 1.2; act "look-at 10.5 100.45 -30.5"; act "hit 1.0"
expect_num "strength I: iron sword 6 + 3 = 9 damage, pig 10 -> 1" "$(server_num "data get entity @e[tag=q1,limit=1] Health")" 1 0.0001
server_cmd "effect clear $NAME"; server_cmd "item replace entity $NAME hotbar.0 with minecraft:air"; server_cmd "effect give $NAME minecraft:weakness 60 0"; sleep 1
server_cmd "tp $NAME 13.5 100 -27.5 180 0"; sleep 1.2; act "look-at 13.5 100.45 -30.5"; act "hit 1.0"
expect_num "weakness I: fist 1 − 4 < 0 → no damage, pig stays at 10" "$(server_num "data get entity @e[tag=q2,limit=1] Health")" 10 0.0001
check_effects "weakness active"
server_cmd "effect clear $NAME"
server_cmd "gamerule natural_health_regeneration $ORIG_REGEN"
act "slog"
