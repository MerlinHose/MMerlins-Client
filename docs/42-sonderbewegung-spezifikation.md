# 42 – Sonderbewegung: Spezifikation (M11)

Dieses Dokument beschreibt die Vanilla-26.3-Regeln für Sprinten, Schleichen, Posen, Bewegungsattribute, Effekte und Kantenschutz. Es setzt docs/21 (Gehen, Springen, Kollision) fort.

- **Quelle:** Loom-Quellen in `analysis/mc-src/`, Angaben `Klasse#Methode`. Offizielle Mojang-Namen.
- **Fakten je Blockzustand:** Sie stammen aus dem unveränderten Server-JAR (`analysis/tools/ShapeExtractor.java`, Zeilen `M`, `R`, `Y`, siehe §7).
- **Fluide und Klettern:** docs/43. **Sonderblöcke:** docs/44. **Messungen:** docs/45.

Notation wie docs/21: `v` = `deltaMovement`, „F“ = `float`-Literal.

## 1. Tickablauf des lokalen Spielers

Ein Client-Tick des eigenen Spielers (`LocalPlayer#tick` → `Player#tick` → `LivingEntity#tick` → `Entity#baseTick`, dann `aiStep`). Die Reihenfolge bestimmt, welcher Tastenzustand wo gilt.

| Nr. | Schritt | Quelle |
|---|---|---|
| 1 | `updateIsUnderwater`: Augen im Wasser (Stand des vorigen Fluid-Updates) | `Player#tick`, `LocalPlayer#updateIsUnderwater` |
| 2 | `wasEyeInWater`, `updateFluidInteraction` (Fluidhöhen, Strömung), `updateSwimming` | `Entity#baseTick` |
| 3 | `sprintTriggerTime--` | `LocalPlayer#aiStep` |
| 4 | **`crouching`** = nicht fliegend ∧ nicht schwimmend ∧ nicht reitend ∧ Schleichpose passt ∧ (Shift **des vorigen Ticks** ∨ Stehpose passt nicht) | `LocalPlayer#aiStep` |
| 5 | `KeyboardInput#tick`: neuer Tastenzustand (7 Bits), `moveVector = Vec2(links, vorwärts).normalized()` | `KeyboardInput#tick` |
| 6 | `moveTowardsClosestSpace` an vier Ecken (nur wenn die Ecke in einem erstickenden Block steckt) | `LocalPlayer#moveTowardsClosestSpace` |
| 7 | `sprintTriggerTime = 0`, wenn Shift im vorigen Tick, Item-Benutzung ohne Sprint oder Rückwärtstaste | `LocalPlayer#aiStep` |
| 8 | Sprintbeginn (§2.1) | `LocalPlayer#aiStep` |
| 9 | Sprintende (§2.2) | `#shouldStopRunSprinting`, `#shouldStopSwimSprinting` |
| 10 | Wasser: Shift → `goDownInWater` (vy −0,04F) | `LocalPlayer#aiStep` |
| 11 | `LivingEntity#aiStep`: `noJumpDelay--`, kleine v nullen, `applyInput` (§3), Sprung, `travel` | docs/21 §4 |
| 12 | `applyEffectsFromBlocks`: `stepOn`, `entityInside` (docs/44) | `LivingEntity#aiStep` |
| 13 | **`updatePlayerPose`** mit dem Shift des **aktuellen** Ticks (§4) | `Player#tick` (Ende) |
| 14 | `sendChanges`: `player_input` (bei Änderung), `player_command` Sprint (bei Änderung), Positionspaket | `LocalPlayer#sendChanges`, `#sendPosition` |

**Folgen der Reihenfolge:**
- Die Geschwindigkeit des Schleichens (Schritt 11) richtet sich nach `crouching` aus Schritt 4. Also wirkt Shift erst einen Tick nach dem Drücken auf die Geschwindigkeit, die Pose (Hitbox) aber schon am Ende des ersten Ticks.
- Wird Shift losgelassen, bleibt die Schleichgeschwindigkeit noch einen Tick erhalten.
- Wird gleichzeitig mit Shift die Sprinttaste gedrückt, beginnt der Sprint (Schritt 8 sieht `crouching` noch aus dem vorigen Tick).

## 2. Sprinten

### 2.1 Beginn (`LocalPlayer#canStartSprinting`)

Voraussetzungen:
- noch nicht sprintend;
- Vorwärtsimpuls: `moveVector.y > 1e−5F` (auch diagonal vorwärts);
- `isSprintingPossible`: keine Blindheit (`isMobilityRestricted`), Nahrung > 6 (`FoodData#hasEnoughFood`) oder `mayfly`, nicht in seichtem Wasser (im Wasser, aber Augen nicht unter Wasser);
- keine Item-Benutzung ohne `UseEffects.canSprint` (Standard: Benutzung verhindert Sprinten);
- nicht im Elytra-Flug (außer unter Wasser);
- nicht langsam (`isMovingSlowly` = `crouching` ∨ Kriechen), außer unter Wasser.

Auslöser:
- **Sprinttaste** (`keySprint`, Bit 64 im Tastenzustand): sofort.
- **Doppeltipp:** Hatte der vorige Tick keinen Vorwärtsimpuls, startet ein Druck `sprintTriggerTime = sprintWindow` (Option, Standard **7**). Kommt der nächste Vorwärtsimpuls, solange `sprintTriggerTime > 0` gilt, beginnt der Sprint.

### 2.2 Ende

An Land (`shouldStopRunSprinting`), geprüft jeden Tick nach dem Beginn:
- Sprinten nicht mehr möglich (§2.1, seichtes Wasser zählt);
- kein Vorwärtsimpuls;
- horizontale Kollision, die **nicht klein** ist.

**Kleine Kollision** (`LocalPlayer#isHorizontalCollisionMinor`, berechnet in `Entity#move` aus der tatsächlichen Bewegung):
```
gewünscht = Eingabe (xxa, zza) gedreht mit Tabellen-sin/cos des Yaw
klein ⇔ |gewünscht|² ≥ 1e−5F ∧ |Bewegung_xz|² ≥ 1e−5F ∧ acos(gewünscht·Bewegung / (|gewünscht|·|Bewegung|)) < 0,13962634F (8°)
```

Schwimmend (`shouldStopSwimSprinting`, docs/43): Sprinten unmöglich, nicht im Wasser, oder weder Vorwärtsimpuls noch Boden noch Shift.

**Schleichen beendet das Sprinten nicht.** Es setzt nur `sprintTriggerTime = 0`. Ein laufender Sprint geht mit Schleichgeschwindigkeit weiter, bis eine Endbedingung greift.

### 2.3 Wirkung

| Größe | Wert | Quelle |
|---|---|---|
| Bewegungsgeschwindigkeit | Modifikator `minecraft:sprinting`, +0,3F (`ADD_MULTIPLIED_TOTAL`) → 0,1F · (1 + 0,3F) | `LivingEntity#setSprinting` |
| Luftbeschleunigung | 0,025999999F statt 0,02F | `Player#getFlyingSpeed` |
| Sprung | zusätzlich (−sin(yaw)·0,2, 0, cos(yaw)·0,2), Tabellen-Sinus, `float`·`double` | `LivingEntity#jumpFromGround` |
| Wasser | Widerstand 0,9F statt 0,8F, kein Fluid-Fallausgleich | `LivingEntity#travelInWater`, `#getFluidFallingAdjustedMovement` |

**Gleichgewicht am Boden:** v∞ = 0,98 · 0,13 / (1 − 0,546) ≈ 0,2806 Blöcke pro Tick (5,61 m/s).

## 3. Bewegungsattribute

Der Wert eines Attributs (`AttributeInstance#calculateValue`): Grundwert + ADD_VALUE, dann + Grundwert · ADD_MULTIPLIED_BASE, dann · (1 + ADD_MULTIPLIED_TOTAL); danach auf den Bereich begrenzt.

**Synchronisierung:**
- `update_attributes` ersetzt Grundwert und alle Modifikatoren (`ClientPacketListener#handleUpdateAttributes`).
- Der Server setzt beim Sprintbeginn denselben Modifikator und sendet ihn mit. Der Client setzt ihn selbst in `setSprinting`.
- Trifft ein Serverpaket nach einem lokalen Sprintende ein, das noch den Modifikator enthält, gilt der Modifikator bis zum nächsten `setSprinting`. Das ist Vanilla-Verhalten.

| ID | Attribut | Spieler-Grundwert | Bereich | Wirkung |
|---|---|---|---|---|
| 0 | `air_drag_modifier` | 1 | 0–2048 | Luftwiderstand |
| 9 | `bounciness` | 0 | – | Abprall an Wänden (docs/44) |
| 17 | `friction_modifier` | 1 | 0–2048 | Blockreibung |
| 18 | `gravity` | 0,08 | −1–1 | Schwerkraft |
| 19 | `jump_strength` | 0,42F | 0–32 | Sprung |
| 25 | `movement_efficiency` | 0 | 0–1 | Seelensand-/Honigfaktor |
| 26 | `movement_speed` | 0,1F | 0–1024 | Bodenbeschleunigung |
| 31 | `sneaking_speed` | 0,3 | 0–1 | Eingabefaktor beim Schleichen |
| 33 | `step_height` | 0,6 | 0–10 | Step-up und Kantenschutz |
| 37 | `water_movement_efficiency` | 0 | 0–1 | Wasserläufer |

## 4. Schleichen, Posen, Kantenschutz

### 4.1 Eingabe (`LocalPlayer#modifyInput`)

```
v = moveVector · 0,98F
falls Item-Benutzung (nicht reitend):  v = v · UseEffects.speedMultiplier (Standard 0,2F)
falls isMovingSlowly:                    v = v · (float) sneaking_speed
danach Quadrat-Korrektur (docs/21 §3)
```

Gleichgewicht beim Schleichen geradeaus: 0,98 · 0,3 · 0,1 / 0,454 ≈ 0,0648 Blöcke pro Tick (1,30 m/s).

### 4.2 Posen (`Avatar.POSES`, `Player#updatePlayerPose`)

| Pose | Breite | Höhe | Augenhöhe | Protokoll-ID |
|---|---|---|---|---|
| STANDING | 0,6F | 1,8F | 1,62F | 0 |
| CROUCHING | 0,6F | 1,5F | 1,27F | 5 |
| SWIMMING (auch Kriechen) | 0,6F | 0,6F | 0,4F | 3 |

`updatePlayerPose` am Ende von `Player#tick`:
1. Nur wenn die Schwimmpose an der Position passt.
2. Gewünscht: Schwimmen → SWIMMING; sonst Shift (nicht fliegend) → CROUCHING; sonst STANDING.
3. Passt die gewünschte Pose, wird sie gesetzt. Sonst CROUCHING, falls das passt. Sonst SWIMMING (Kriechen).

**„Passt“** (`canPlayerFitWithinBlocksAndEntitiesWhen`): `noCollision` mit der Hitbox der Pose an der aktuellen Position, um 1e−7 verkleinert.

**Neue Abmessungen:** `setPose` → `refreshDimensions` setzt Hitbox und Augenhöhe sofort neu. Für Spieler gibt es keine Positionskorrektur (`fudgePositionAfterSizeChange` gilt nicht für `Player`).

**Niedrige Decke:**
- Bei 1,5 Blöcken Freiraum bleibt die Pose CROUCHING, auch ohne Shift.
- `crouching` ist dann wahr (Stehpose passt nicht), also langsame Bewegung.

### 4.3 Kantenschutz (`Player#maybeBackOffFromEdge`, in `Entity#move` vor `collide`)

Bedingungen:
- nicht fliegend;
- `delta.y ≤ 0`;
- Bewegungsart SELF/PLAYER;
- Shift (`isStayingOnGroundSurface`);
- `isAboveGround(maxUpStep)`: am Boden, oder Fallstrecke < maxUpStep und kein Fall um (maxUpStep − Fallstrecke) möglich.

Verfahren mit `maxDownStep = (float) step_height` (0,6F) und Schritt 0,05:
1. x verkürzen, solange `canFallAtLeast(dx, 0, maxDownStep)` gilt (|dx| ≤ 0,05 → 0).
2. Dann z ebenso.
3. Dann beide gemeinsam, solange der diagonale Fall möglich ist.

`canFallAtLeast(dx, dz, h)` = `noCollision` der Box `[minX+1e−7+dx, minY−h−1e−7, minZ+1e−7+dz] … [maxX−1e−7+dx, minY, maxZ−1e−7+dz]`.

**Fallstrecke** (`Entity#checkFallDamage`): Sie wächst um `(float) movement.y` nur außerhalb von Wasser und wird bei Bodenkontakt zurückgesetzt. Auf Leitern, in Fluiden und mit Sturzflug-/Schwebe-Effekt ebenfalls.

### 4.4 Kontext für Kollisionen

`isDescending` = Shift. Das wirkt auf das Gerüst (docs/43 §5) und auf Pulverschnee.

## 5. Netzwerk

| Paket | Inhalt | Zeitpunkt |
|---|---|---|
| `player_input` (43) | ein Byte: vorwärts 1, rückwärts 2, links 4, rechts 8, Sprung 16, **Shift 32, Sprint 64** | bei Änderung, vor dem Positionspaket |
| `player_command` (42) | VarInt Entity-ID, VarInt Aktion (START_SPRINTING 1, STOP_SPRINTING 2), VarInt Daten 0 | bei Änderung von `isSprinting` (`sendIsSprintingIfNeeded`), vor dem Positionspaket |
| Positionspaket | wie docs/15 §3 | nach den beiden obigen |

**Server:**
- `handlePlayerInput` setzt `setShiftKeyDown(shift)`. Die Serverpose folgt in dessen `Player#tick`.
- `handlePlayerCommand` ruft `setSprinting`.
- Die Serverbewegungsprüfung (`player.move(MoverType.PLAYER, …)`) wendet denselben Kantenschutz mit dem Server-Shift an.

**Zurück zum Client:**
- Der Server sendet Shared Flags (Index 0, Bit 1 Shift, Bit 3 Sprint) und Pose (Index 6) auch an den Spieler selbst.
- `ClientPacketListener#handleSetEntityData` übernimmt sie in den `LocalPlayer`:
  - Die Pose wird sofort gesetzt.
  - Das Sprint-Flag ändert nur das Flag, nicht den Attribut-Modifikator.
- Beides korrigiert der Client im nächsten Tick selbst (§1 Schritte 8, 9, 13).
- Bei sehr schnellem Umschalten (kürzer als die Paketlaufzeit) kann ein verspätetes Serverecho einen Tick lang eine andere Hitbox bewirken. Das ist Vanilla-Verhalten und hängt vom Messaufbau ab (docs/45).

## 6. Effekte mit Bewegungswirkung (Luft und Boden)

| Effekt | Wirkung | Quelle |
|---|---|---|
| Geschwindigkeit / Langsamkeit | Attributmodifikatoren auf `movement_speed`, vom Server gesendet | `MobEffects` |
| Sprungkraft | + 0,1F · (Amplifier + 1) auf die Sprungkraft | `LivingEntity#getJumpBoostPower` |
| Sturzflug | Schwerkraft beim Fallen höchstens 0,01; Fallstrecke 0 | `#getEffectiveGravity`, `#aiStep` |
| Schweben | vy += (0,05 · (Amplifier + 1) − vy) · 0,2 statt Schwerkraft; Fallstrecke 0 | `#travelInAir` |
| Blindheit | kein Sprinten | `Player#isMobilityRestricted` |
| Delfinsgunst | Wasserwiderstand 0,96F | `#travelInWater` (docs/43) |

## 7. Faktentabelle (M11-Zeilen in `shapes-777.txt.gz`)

| Zeile | Inhalt |
|---|---|
| `M stateId fluid sturdy tags` | Fluid: `W`/`L` + Menge + `S` (Quelle) + `F` (fallend). Sturdy: Bitmaske je Seite. Tags: `C` climbable, `B` blocks_fluid_flow, `N` suppresses_bounce, `X` erstickend, `I` Eisblock |
| `R block restitution` | Prallfaktor `getBounceRestitution` (≠ 0) |
| `Y block Klasse` | Blockklasse für das Verhalten von `stepOn`/`entityInside`/`fallOn` |
