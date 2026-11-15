# M10 Schritt 1: Angriffe gegen den Vanilla-Server (docs/41 §2). Ziele NoAI-Schweine/Kühe (10 HP); Spieler im Überlebensmodus.
# Erwartete Vanilla-Werte (docs/38 §2): Schaden = attack_damage · (0,2 + 0,8 · s²), kritisch · 1,5; maßgeblich ist der Serverwert (data get … Health).
for c in "forceload add -32 -64 47 15" "fill -14 99 -48 30 99 -16 minecraft:stone" "fill -14 100 -48 30 106 -16 minecraft:air" \
  "fill -14 107 -48 30 107 -16 minecraft:stone" "fill -14 103 -44 30 103 -22 minecraft:light[level=15]" "kill @e[type=!player]" "kill @e[type=item]" \
  "summon pig 0.5 100 -30.5 {NoAI:1b,Tags:[m10,p1]}" "summon pig 3.5 100 -30.5 {NoAI:1b,Tags:[m10,p2]}" "summon cow 6.5 100 -30.5 {NoAI:1b,Tags:[m10,p3]}" \
  "summon pig 9.0 100 -30.5 {NoAI:1b,Tags:[m10,p4]}" "summon pig 11.0 100 -30.5 {NoAI:1b,Tags:[m10,p5]}" "summon pig 15.5 100 -31.5 {NoAI:1b,Tags:[m10,p6]}" \
  "summon pig 18.5 100 -30.5 {NoAI:1b,Tags:[m10,p7]}" \
  "summon pig 21.5 100 -30.5 {Tags:[m10,p8],attributes:[{id:\"minecraft:movement_speed\",base:0.0d}]}" \
  "summon pig 24.5 100 -30.5 {Tags:[m10,p9],attributes:[{id:\"minecraft:movement_speed\",base:0.0d},{id:\"minecraft:knockback_resistance\",base:1.0d}]}" \
  "gamemode survival $NAME" "clear $NAME" "effect clear $NAME"; do server_cmd "$c"; done
sleep 2
at() { server_cmd "tp $NAME $1 100 -27.5 180 0"; sleep 1.2; act "look-at $1 100.45 -30.5"; }
health() { server_num "data get entity @e[tag=$1,limit=1] Health"; }
# --- 1 bloße Hand, volle Stärke: 1 Schaden
at 0.5; act "hit 1.0"
expect_num "fist, full strength: pig 10 -> 9" "$(health p1)" 9 0.0001
expect "fist hit confirmed by the server (damage_event caused by us)" "confirmed=0->1" "$LAST"
# --- 2 Eisenschwert, volle Stärke: 6 Schaden
server_cmd "item replace entity $NAME hotbar.0 with minecraft:iron_sword"; act "slot 1"; sleep 1
log "client attack_damage attribute: $(client_field attackDamage) (attack_damage is not synchronised to the client – Vanilla derives it from the item)"
expect_num "client attack_speed attribute with iron sword (4 - 2.4)" "$(client_field attackSpeed)" 1.6 0.0001
at 3.5; act "hit 1.0"
expect_num "iron sword, full strength: pig 10 -> 4" "$(health p2)" 4 0.0001
# --- 3 Eisenaxt, volle Stärke: 9 Schaden
server_cmd "item replace entity $NAME hotbar.1 with minecraft:iron_axe"; act "slot 2"; sleep 1
expect_num "client attack_speed with iron axe (4 - 3.1)" "$(client_field attackSpeed)" 0.9 0.0001
at 6.5; act "hit 1.0"
expect_num "iron axe, full strength: cow 10 -> 1" "$(health p3)" 1 0.0001
expect "iron axe lost 2 durability (weapon item_damage_per_attack)" "minecraft:iron_axe 1 minecraft:damage=2" "$(client invdump)"
# --- 4 Schwert ohne Abklingzeit: direkt nach einem Treffer erneut (s klein)
act "slot 1"; sleep 1
server_cmd "tp $NAME 10.0 100 -27.5 180 0"; sleep 1.2; act "look-at 9.0 100.45 -30.5"
act "hitpair 9.0 100.45 -30.5 11.0 100.45 -30.5"
expect_num "sword full strength on pig 4: 10 -> 4" "$(health p4)" 4 0.0001
SC=$(echo "$LAST" | grep "HITPAIR2" | grep -o "strength=[0-9.]*" | cut -d= -f2)
H5=$(health p5)
# Stärke t/d beim Senden (wie der Vanilla-Referenzclient: 0,16 = 2/12,5); der Server rechnet mit getAttackStrengthScale(0,5) → s = (t + 0,5)/d
RANGE=$(node -e 'const sc=+process.argv[1], d=12.5; const s=sc+0.5/d; const f=x=>10-6*(0.2+0.8*Math.min(1,Math.max(0,x))**2); console.log(f(s).toFixed(4)+" 0.0001")' -- "$SC")
log "no-cooldown hit: client strength at the click $SC, server health $H5, expected $RANGE (s = strength + 0.5/12.5)"
expect_num "sword without cooldown: damage = 6·(0.2+0.8·s²) with s = client strength + 0.5 ticks (server half tick)" "$H5" $RANGE
expect "the no-cooldown hit used a low attack strength" "strength=0.[0-4]" "$LAST"
# --- 5 kritischer Treffer im Fallen: 6 · 1,5 = 9
server_cmd "tp $NAME 15.5 100 -29.2 180 0"; sleep 1.2; act "look-at 15.5 100.6 -31.5"
act "hitfall 15.5 100.45 -31.5"
expect_num "critical hit while falling: pig 10 -> 1" "$(health p6)" 1 0.0001
# --- 6 Reichweite: Ziel 3,6 Blöcke entfernt → nicht ausgewählt, kein Angriff
server_cmd "tp $NAME 18.5 100 -26.5 180 0"; sleep 1.2; act "look-at 18.5 100.45 -30.5"
act "hit 1.0"
expect "target beyond the entity interaction range (3.0) is not selected" "HIT none" "$LAST"
expect_num "pig out of reach keeps 10 health" "$(health p7)" 10 0.0001
# --- 7 Rückstoß: Schwein (KI, Geschwindigkeit 0) wird in Blickrichtung (−Z) geschoben; mit Rückstoßresistenz 1 nicht
act "slot 3"; sleep 0.5
Z8=$(server_num "data get entity @e[tag=p8,limit=1] Pos[2]")
at 21.5; act "hit 1.0"; sleep 1.5
Z8B=$(server_num "data get entity @e[tag=p8,limit=1] Pos[2]")
log "knockback: pig 8 z $Z8 -> $Z8B"
expect "knockback pushes the pig away from the player (-Z)" "yes" "$(node -e "console.log(($Z8B) < ($Z8) - 0.2 ? 'yes' : 'no')")"
Z9=$(server_num "data get entity @e[tag=p9,limit=1] Pos[2]")
at 24.5; act "hit 1.0"; sleep 1.5
Z9B=$(server_num "data get entity @e[tag=p9,limit=1] Pos[2]")
expect_num "knockback resistance 1: pig 9 does not move" "$Z9B" "$Z9" 0.0001
check_entities "client positions/health of all targets equal the server after the hits (incl. knocked-back pig)" pig pos,health
act "slog"
act "estats"
