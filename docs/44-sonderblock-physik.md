# 44 – Sonderblock-Physik (M11)

Wirkung spezieller Blöcke auf die Spielerbewegung in Vanilla 26.3 und ihre Umsetzung. Quellen: Loom-Quellen, `Klasse#Methode`. Fakten je Blockzustand: Faktentabelle (docs/42 §7). Messungen: docs/45.

## 1. Reibung, Geschwindigkeits- und Sprungfaktoren (Zeilen `K`)

| Block | Reibung | Geschwindigkeitsfaktor | Sprungfaktor |
|---|---|---|---|
| Eis, Packeis, Frosteis | 0,98 | 1 | 1 |
| Blaueis | 0,989 | 1 | 1 |
| Schleimblock | 0,8 | 1 | 1 |
| Honigblock | 0,6 | 0,4 | 0,5 |
| Seelensand | 0,6 | 0,4 | 1 |
| Seelenerde | 0,6 | 1 | 1 |

**Reibung:**
- Am Boden zählt die Reibung des Blocks unter dem Spieler (`getBlockPosBelowThatAffectsMyMovement`, docs/21 §4.4), mit `friction_modifier` (`computeModifiedFriction`).
- Bodenbeschleunigung `speed · 0,21600002F / f³` bei f > 0,6.
- Horizontaler Widerstand `f · 0,91F`.

**Geschwindigkeitsfaktor** (`Entity#getBlockSpeedFactor`, nach jeder Bewegung auf vx, vz):
- Block an der Position.
- Ist dessen Faktor 1, der Block unter dem Spieler. Wasser und Blasensäule gelten immer selbst.
- `movement_efficiency` interpoliert zum Faktor 1 (`LivingEntity#getBlockSpeedFactor`).

**Sprungfaktor** (`getBlockJumpFactor`): ebenso, ohne die Ausnahme für Wasser.

## 2. Prallen (`Entity#restituteMovementAfterCollisions`)

| Block | `getBounceRestitution` |
|---|---|
| Schleimblock | 1,0 |
| Betten (alle Farben), Regal-Pilz (`shelf_mushroom`) | 0,75 |

Gilt nach einer vertikalen Kollision nach unten. `effectState` ist der Block bei `getOnPosLegacy` (0,2 unter den Füßen).

**Prallfaktor** r = max(`bounciness`-Attribut, Prallfaktor des Blocks). Das gilt nur, wenn
- `−vy > effektive Schwerkraft`,
- kein Shift (`isSuppressingBounce`),
- der Block nicht das Tag `suppresses_bounce` hat (nur Honigblock).

Sonst ist r = 0. Bei r > 0:
```
anteil = Bewegung.y / v.y ; ausgleich = anteil · g ; widerstand = lerp(anteil, 1, airDrag (0,98F))
vy_neu = (ausgleich − v.y) · widerstand · r
```
Bei r = 0 ist vy_neu = −v.y · 1 · 0 = 0 (M5-Verhalten).

**Horizontal:** Abprall mit `−v · bounciness`. Für Spieler ist das Attribut 0, also kein Abprall.

**Schleim beim Gehen** (`SlimeBlock#stepOn`): am Boden, |vy| < 0,1, kein Shift → vx, vz × (0,4 + |vy| · 0,2).

`stepOn` läuft in `applyEffectsFromBlocks` nach `travel` für den Block bei `getOnPosLegacy`.

## 3. Blöcke, in denen der Spieler steckt (`entityInside`)

`Entity#checkInsideBlocks` geht nach der Bewegung die Zellen ab, die die (um 1e−5F verkleinerte) Hitbox auf ihrem Weg berührt. Für jede Bewegung des Ticks geschieht das achsweise in Kollisionsreihenfolge (`forEachBlockIntersectedBetween`). Hat die Zelle die Standardform „ganzer Block“ für `getEntityInsideCollisionShape`, zählt jede berührte Zelle. Jede Zelle wird je Tick höchstens einmal behandelt.

| Block | Wirkung | Quelle |
|---|---|---|
| Spinnennetz | `makeStuckInBlock(0,25; 0,05F; 0,25)`; mit Effekt „Weben“ (0,5; 0,25; 0,5) | `WebBlock#entityInside` |
| Süßbeerenstrauch | `makeStuckInBlock(0,8F; 0,75; 0,8F)` (Schaden nur serverseitig) | `SweetBerryBushBlock#entityInside` |
| Pulverschnee | `makeStuckInBlock(0,9F; 1,5; 0,9F)`, wenn der Block an der Position Pulverschnee ist | `PowderSnowBlock#entityInside` |
| Honigblock | Rutschen an der Seite (§4) | `HoneyBlock#entityInside` |
| Blasensäule | §5 | `BubbleColumnBlock#entityInside` |

**`makeStuckInBlock`:**
1. Fallstrecke 0, `stuckSpeedMultiplier` = Faktor.
2. In der **nächsten** Bewegung (`Entity#move`, vor dem Kantenschutz): Bewegung × Faktor, danach v = 0.
3. Der Faktor wird verbraucht (zurück auf 0).

## 4. Honigblock (`HoneyBlock`)

- **Form:** 14/16 breit, 15/16 hoch (Kollisionsform aus der Tabelle). Seitlich kann die Hitbox die Zelle also um 1/16 überlappen.
- **Rutschen** (`isSlidingDown`), alle Bedingungen:
  - nicht am Boden;
  - y ≤ Blockunterkante + 0,9375 − 1e−7;
  - alte vy (`vy / 0,98F + 0,08`) < −0,08;
  - die horizontale Mitte ist am Rand: |Δx| oder |Δz| zur Blockmitte + 1e−7 > 0,4375 + Breite/2.
- **Bewegung** (`doSlideMovement`):
  - Bei alter vy < −0,13: vx, vz × (−0,05 / alte vy), vy = (−0,05 − 0,08) · 0,98F.
  - Sonst nur vy = (−0,05 − 0,08) · 0,98F.
  - Fallstrecke 0.
- Geschwindigkeitsfaktor 0,4 und Sprungfaktor 0,5 gelten auf der Oberseite (§1). `suppresses_bounce` verhindert Prallen.

## 5. Blasensäule (`BubbleColumnBlock#entityInside`, nur „präzise“: Zielhitbox schneidet die Zelle)

Über der Säule ist nichts (Block darüber ohne Kollisionsform und ohne Fluid):
- Aufwärts: vy = min(1,8; vy + 0,1).
- Abwärts (Magmablock): vy = max(−0,9; vy − 0,03).

Sonst (innen):
- Aufwärts: vy = min(0,7; vy + 0,06).
- Abwärts: vy = max(−0,3; vy − 0,03).
- Fallstrecke 0.

Die Blasensäule ist zugleich eine Wasserquelle. Fluidphysik (docs/43) und Säule wirken also zusammen.

## 6. Umsetzung

Siehe `PlayerPhysics#applyEffectsFromBlocks`: `stepOn` (Schleim), Zellen-Durchlauf je Bewegung, `entityInside` (Netz, Beerenstrauch, Pulverschnee, Honig, Blasensäule), `stuckSpeedMultiplier` in `move`, Prallen in `restituteMovementAfterCollisions`. Messungen gegen Vanilla: docs/45 §4.
