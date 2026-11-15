# 21 – Physik-Spezifikation (M5)

Dieses Dokument beschreibt Bewegung, Kollision, Schwerkraft, Sprung und Step-up eines Spielers in Minecraft Java 26.3.

- **Quelle:** Loom-Quellen in `analysis/mc-src/`, Zeilenangaben `Datei:Zeile`. Die Klassen- und Methodennamen sind die offiziellen Mojang-Namen.
- **Kollisionsformen:** Sie stammen nicht aus Abschriften, sondern aus dem unveränderten Server-JAR. Das Analysewerkzeug `analysis/tools/ShapeExtractor.java` erzeugt sie als Faktentabelle (siehe docs/22 §2).
- **Umsetzung im eigenen Client:** docs/22.
- **Laufzeitvergleich mit Vanilla:** docs/23.

Notation:
- `v` = `deltaMovement` (Geschwindigkeit in Blöcken pro Tick), `p` = Position (Fußmitte).
- „F“ hinter einer Zahl heißt: `float`-Literal. Die Rundung auf `float` ist Teil des Verhaltens.

## 1. Tickablauf auf dem Client

Ein Client-Tick dauert 50 ms. Reihenfolge in `Minecraft#tick` (`client/Minecraft.java:1797-1854`):

1. `level.tickEntities()`. Darin `LocalPlayer#tick` → `LivingEntity#tick` → **`aiStep`**: Eingabe übernehmen, Sprung auslösen, `travel`, Bewegung.
2. `player.sendChanges()`. Das sendet zuerst `player_input` (bei geändertem Tastenzustand), dann ein Positionspaket nach `LocalPlayer#sendPosition :295`, siehe docs/15 §3.
3. Am Ende des Ticks `ServerboundClientTickEndPacket`.

Damit verwendet die Physik eines Ticks den Tastenzustand, der **zu Beginn dieses Ticks** gilt (`KeyboardInput#tick` in `LocalPlayer#aiStep :794`). Die danach gesendete Position ist das Ergebnis dieser Physik.

## 2. Spieler-Hitbox

- Stehend: Breite 0,6F, Höhe 1,8F (`Avatar.STANDING_DIMENSIONS`, `world/entity/Avatar.java:21`).
- AABB = `[x−0,3, y, z−0,3] … [x+0,3, y+1,8, z+0,3]`. Die Werte sind `(double) 0.6F / 2` bzw. `(double) 1.8F`, also 0,30000001192… und 1,7999999523….
- Schleichen (1,5) und Schwimmen bzw. Gleiten (0,6) haben andere Posen. Sie sind nicht Teil von M5.

## 3. Eingabe → Bewegungsvektor

| Schritt | Regel | Quelle |
|---|---|---|
| Tasten | vorwärts/rückwärts → `forwardImpulse` ±1, links/rechts → `leftImpulse` ±1; `moveVector = Vec2(left, forward).normalized()` | `client/player/KeyboardInput.java:26-39` |
| Eingabeskalierung | `0,98F` × moveVector; bei Item-Nutzung oder Schleichen weitere Faktoren | `LocalPlayer#modifyInput :719` |
| Quadrat-Korrektur | Länge × `sqrt(1 + tan²)` (Abstand zum Einheitsquadrat), höchstens 1. **Diagonal:** 0,98·√2 → auf 1,0 begrenzt; **gerade:** 0,98 | `modifyInputSpeedForSquareMovement :737` |
| `xxa`/`zza` | links/vorwärts nach der Korrektur; `jumping` = Sprungtaste | `LocalPlayer#applyInput :704` |
| Weltvektor | `getInputVector(input, speed, yRot)`: Länge² < 1e−7 → 0; Länge > 1 → normieren; × speed; Drehung mit `Mth.sin/cos(yRot·π/180)` | `Entity#getInputVector :1780`, `#moveRelative :1775` |
| `Mth.sin/cos` | **Tabelle** mit 65 536 `float`-Werten: `SIN[(int)((long)(a·10430.378350470453) & 65535)]`, cos mit +16384 | `util/Mth.java:35-56` |
| Yaw | 0 = +Z (Süden), 90 = −X, 180 = −Z, −90 = +X | aus der Drehformel |

Folgerung: Eine eigene Physik muss die Sinustabelle nachbilden. `Math.sin` weicht in der 8. Stelle ab und läuft über viele Ticks auseinander.

## 4. Bewegung je Tick (`LivingEntity#aiStep :3009` → `travel` → `travelInAir :2438`)

### 4.1 Vor der Bewegung (`aiStep`)

1. `noJumpDelay--`, falls > 0.
2. **Kleine Geschwindigkeiten nullen:**
   - Spieler: Ist `vx² + vz² < 9·10⁻⁶`, werden vx und vz auf 0 gesetzt.
   - Für alle Entities: Ist `|vy| < 0,003`, wird vy auf 0 gesetzt (`:3028-3047`).
3. `applyInput()` (§3).
4. **Sprung:**
   - Ist `jumping` gesetzt und der Spieler **steht** auf dem Boden (`onGround`) bei `noJumpDelay == 0`: `jumpFromGround()`, danach `noJumpDelay = 10`.
   - Ist `jumping` nicht gesetzt: `noJumpDelay = 0` (`:3060-3087`).
   - Wird die Taste gehalten, folgt der nächste Sprung frühestens 10 Ticks später und nur auf dem Boden.
5. `travel(input = (xxa, 0, zza))`.

### 4.2 `jumpFromGround` (`:2359`)

- `jumpPower = JUMP_STRENGTH (0,42F) · blockJumpFactor + jumpBoost`. Dabei:
  - `blockJumpFactor` kommt vom Block an der Position, sonst vom Block darunter (`Entity#getBlockJumpFactor :1116`). Honigblock 0,5.
- `vy = max(jumpPower, vy)`.
- Beim Sprinten zusätzlich ein horizontaler Impuls von 0,2 in Blickrichtung. Sprinten ist nicht Teil der M5-Tests.

### 4.3 `travelInAir` (Boden und Luft)

**1. Blockreibung:**
```
blockFriction = onGround ? friction(Block unter dem Spieler) : 1.0F
```
- Standard 0,6. Eis und Packeis 0,98, Blaueis 0,989, Schleim 0,8. Werte aus der Tabelle, `K`-Zeilen.
- Modifikator `computeModifiedFriction(f, FRICTION_MODIFIER = 1)` = f.

**2. Beschleunigung** (`getFrictionInfluencedSpeed :2692`):
- am Boden: `speed = f > 0,6 ? speed · 0,21600002F / f³ : speed`, mit `speed = MOVEMENT_SPEED = 0,1F`;
- in der Luft: `getFlyingSpeed` = 0,02F (sprintend 0,026F) (`Player :1934`).

**3. Bewegungsablauf:**
1. `moveRelative(speed, input)`: v += Eingabevektor.
2. Leiter-Klemmung (§9).
3. `move(SELF, v)` (§5).
4. `v` neu lesen. Die Kollision hat es eventuell verändert.

**4. Schwerkraft:**
- `vyNeu = v.y − 0,08`, mit `GRAVITY = 0,08`; Langsamer Fall begrenzt auf 0,01.
- Auf dem Client gilt das nur, wenn der Chunk unter dem Spieler geladen ist. Sonst ist `vy = −0,1` (über `min_y`) bzw. 0.

**5. Luftwiderstand:**
```
v = (vx · f · 0,91F,  vyNeu · 0,98F,  vz · f · 0,91F)
```
Am Boden ist f = 0,6 und damit ist der horizontale Faktor 0,546.

**Gleichgewicht beim Gehen:**
- v∞ = 0,098 · 0,1 / (1 − 0,546) ≈ 0,02159 Blöcke pro Tick Beschleunigung; das gibt ≈ 0,2158 Blöcke pro Tick bzw. 4,317 m/s geradeaus.
- Diagonal mit Eingabelänge 1,0: 0,2203.

### 4.4 Block unter dem Spieler (`getBlockPosBelowThatAffectsMyMovement :1089`)

```
getOnPos(0,500001F)
```

**Mit Stützblock `mainSupportingBlockPos`:**
- Ergebnis: dessen x/z mit `y = floor(p.y − 0,500001)`.
- Bei Zäunen, Mauern und Zauntoren: der Stützblock selbst.

**Ohne Stützblock:** `floor(p)` mit `y − 0,500001`.

**Stützblock** (`checkSupportingBlock :711`, `CollisionGetter#findSupportingBlock :138`):
- Auswahl unter allen Blöcken, deren Kollisionsform die Fläche `[minY − 1e−6, minY]` unter der Hitbox schneidet: der mit dem kleinsten Abstand zur Blockmitte.
- Bei gleichem Abstand gilt die Ordnung von `BlockPos.compareTo`.

## 5. Kollision (`Entity#move :737`, `#collide :1176`)

### 5.1 `move(SELF, delta)`

1. `collide(delta)` → `movement`.
2. Position += `movement`, wenn `|movement|² > 1e−7` oder die Differenz der Quadrate < 1e−7.
3. **Kollisionsflags:**
   - `xCollision = !Mth.equal(delta.x, movement.x)` und `zCollision` analog. Toleranz 1e−5 (`Mth.equal`).
   - `horizontalCollision = x ∨ z`.
   - `verticalCollision = delta.y ≠ movement.y`.
   - `verticalCollisionBelow = verticalCollision ∧ delta.y < 0`.
   - `onGround = verticalCollisionBelow`. Gesetzt wird das nur, wenn `delta.y ≠ 0` ist oder die Instanz autoritativ ist.
4. `restituteMovementAfterCollisions :840`: Bei x-/z-Kollision wird `v.x` bzw. `v.z` zu `−v·restitution`. Für Spieler gilt `BOUNCINESS = 0`, also 0. Bei vertikaler Kollision wird `v.y = 0`. Ausnahme Schleim und Bett: Prallfaktor des Blocks, sofern nicht geschlichen wird.
5. `v.x, v.z *= blockSpeedFactor`: Seelensand und Honig 0,4; Wasser 1. Der Faktor kommt vom Block an der Position, sonst vom Block darunter (`:1122`). `MOVEMENT_EFFICIENCY` interpoliert dazu (`LivingEntity :515`).

### 5.2 `collide(movement)` mit Step-up

1. **Erste Kollision:** `movementStep = collideBoundingBox(...)` mit allen Block- und Entity-Formen im Bereich `aabb ∪ aabb+movement`.
2. **Step-up versuchen:** Bedingung ist `maxUpStep > 0` (Spieler 0,6F, Attribut `STEP_HEIGHT`) und `(onGroundAfterCollision ∨ onGround)` und x- oder z-Kollision. Dabei ist `onGroundAfterCollision = yKollision ∧ movement.y < 0`.
   - `groundedAABB` = aabb nach unten auf den Boden verschoben, wenn `onGroundAfterCollision` gilt; sonst aabb.
   - Kollidierende Formen im Bereich `groundedAABB.expandTowards(mx, 0,6, mz)`, ohne Bodenkontakt zusätzlich 1e−5 nach unten.
3. **Kandidaten** (`collectCandidateStepUpHeights :1207`):
   - Alle **Y-Gitterkoordinaten** jeder Form (`VoxelShape#getCoords(Y)`), relativ zu `groundedAABB.minY` und als `float`.
   - Nur Werte ≥ 0 und ≠ `(float) movementStep.y`.
   - Je Form abgebrochen beim ersten Wert > 0,6.
   - Danach aufsteigend sortiert.
4. **Für jeden Kandidaten h aufsteigend:** `collideWithShapes((mx, h, mz), groundedAABB)`. Ist der horizontale Weg² **größer** als bei `movementStep`, ist das Ergebnis dieser Schritt minus `(aabb.minY − groundedAABB.minY)`.
   - Es gibt **kein** Absenken nach dem Stufenschritt. Die Füße enden exakt auf Höhe h.
5. Sonst `movementStep`.

### 5.3 Achsenreihenfolge (`collideWithShapes :1279`, `Direction#axisStepOrder :387`)

- Zuerst **Y**.
- Dann bei `|mx| < |mz|` erst **Z**, dann **X**; sonst erst X, dann Z.
- Jede Achse: `Shapes.collide(axis, box verschoben um bereits aufgelöste Achsen, shapes, distance)`.

### 5.4 Achsenkollision (`Shapes#collide :216`, `VoxelShape#collideX :251`)

Für jede Form wird `distance` nacheinander begrenzt. Bei |distance| < 1e−7 ist das Ergebnis 0.

Pro Form wird die erste besetzte Zelle in Bewegungsrichtung gesucht, die die bewegte Box auf den beiden anderen Achsen überlappt. Die Überlappung gilt mit Toleranz 1e−7 nach innen (`findIndex(min+1e−7)`, `findIndex(max−1e−7)`).

- **positive Richtung:** `d = zelleMin − box.max`. Ist `d ≥ −1e−7`, gilt `distance = min(distance, d)`.
- **negative Richtung:** symmetrisch.

Zellen, die die Box schon durchdringt, werden übersprungen. Für Formen, die eine Vereinigung von Boxen sind, ist das gleichwertig zum Minimum über alle Boxen mit `d ≥ −1e−7` und Querüberlappung > 1e−7. So setzt es der eigene Client um (docs/22 §3).

### 5.5 Welche Blockformen zählen (`world/level/BlockCollisions.java`)

- **Iterationsbereich:** Zellen `floor(min−1e−7)−1 … floor(max+1e−7)+1` je Achse.
  - Zellen auf dem äußeren Rand zählen nur bei **großer Form** (`hasLargeCollisionShape`: Form ragt über die Zelle hinaus, z. B. Zaun und Mauer mit 1,5).
  - Kanten- und Eckzellen des Rands sind nur für `moving_piston` bzw. gar nicht relevant.
- **Aufnahme in die Kollisionsliste:**
  - volle Blöcke bei echter Überschneidung mit der Abfragebox (offene Intervalle);
  - andere Formen, wenn ihr Schnitt mit der Abfragebox ein positives Volumen hat.
- **Ungeladene Chunks** liefern auf dem Client **keine** Formen (`getChunkForCollisions` → `null`).

## 6. Kollisionsformen und Nachbarblöcke

- **Form = Funktion des Blockzustands:** `BlockState#getCollisionShape(level, pos, context)`.
  - Für fast alle Zustände ist sie zustandsfest und im Cache (`BlockBehaviour.BlockStateBase.Cache :936`).
  - Ausgenommen sind Blöcke mit `dynamicShape()`.
- **Nachbarblöcke** wirken **über den Zustand**:
  - Treppen: `shape=inner_left/outer_right/…`.
  - Zäune, Glasscheiben und Gitter: `north/east/south/west`.
  - Mauern: `none/low/tall`, `up`.
  - Der Server berechnet diese Eigenschaften (`updateShape`) und sendet sie im Chunk bzw. per Blockupdate. Der Client muss sie **nicht** selbst herleiten.
- **Positionsabhängig** (Offset-Funktion `OffsetType.XZ`, `BlockBehaviour.Properties#offsetType :1261`): `bamboo`, `pointed_dripstone`, `sulfur_spike`.
  ```
  seed = Mth.getSeed(x, 0, z)
  dx = clamp(((float)(seed & 15) / 15F − 0,5) · 0,5, ±maxH)
  dz = analog mit seed >> 8
  ```
  - `maxH` ist 0,25 bzw. bei Speleothemen 0,125.
  - `Mth.getSeed :332`: `s = x·3129871 ^ z·116129781L ^ y; s = s·s·42317861L + s·11L; s >> 16`.
- **Kontextabhängig** (`CollisionContext`):

  | Block | Regel | Quelle |
  |---|---|---|
  | `scaffolding` | Ist die Unterkante des Spielers > `y + 1 − 1e−5` und schleicht er nicht: stabile Form (Rahmen mit Deckplatte). Sonst, bei `bottom=true` und `distance ≠ 0` und Unterkante > `y − 1e−5`: 2/16-Platte. Sonst leer. | `ScaffoldingBlock#getCollisionShape :136` |
  | `powder_snow` | Nach mehr als 2,5 Blöcken Fallstrecke 0,9F hoch. Mit Lederstiefeln als voller Block, solange man über ihm steht und nicht schleicht. Sonst leer. | `PowderSnowBlock :116` |
  | Flüssigkeiten | Für Spieler leer | `LiquidBlock :68` |
  | `shulker_box`, `moving_piston` | Hängen von Block-Entity-Daten ab (Öffnungszustand, Kolbenfortschritt). Die Tabelle enthält den geschlossenen bzw. leeren Zustand. | `dynamicShape` |

## 7. Bodenkontakt, Fallen, Landen

- **Bodenkontakt:** `onGround` gilt genau dann, wenn die letzte Bewegung nach unten kollidiert ist (§5.1).
  - Am Boden: vy = 0 nach der Kollision; danach Schwerkraft → −0,0784 (= −0,08 · 0,98).
  - Im nächsten Tick prallt das erneut am Boden ab. Deshalb ist `onGround` jeden Tick wahr.
- **Fallen:** `vy(n+1) = (vy(n) − 0,08) · 0,98`.
  - Endgeschwindigkeit −3,92.
  - Ab einem Block Fall sind es 6 Ticks bis zum Aufprall (siehe Unit-Tests).
- **Sprung:**
  - vy = 0,42 (`float` 0,41999998688697815).
  - Gipfel nach 6 Ticks bei Δy = 1,2522.
  - Landung auf derselben Höhe nach 12 Ticks.
  - Dazwischen wirkt horizontal nur die Luftbeschleunigung 0,02 mit Reibung 0,91.
- **Fallschaden** (`checkFallDamage`) und die Fallstrecke: werden für die Physik nicht benötigt. Ausnahme: der Kontext von Pulverschnee.

## 8. Server: Prüfung statt Simulation

Der Server **simuliert die Spielerbewegung nicht nach**. Er prüft das gemeldete Ziel (`ServerGamePacketListenerImpl#handleMovePlayer :1062`, Details in docs/15 §2):

1. **„moved too quickly“:** Weg² − v² > 100 je Paket.
2. **Kollisionsbewegung:** `player.move(MoverType.PLAYER, Ziel − letzte gute Position)` mit derselben Kollision wie §5, einschließlich Step-up. Ist danach die horizontale Restabweichung² > 0,0625 (0,25 Blöcke), meldet der Server „moved wrongly“ und setzt die Position zurück (`:1185`).
   - Vertikale Restabweichungen ignoriert der Server **immer** (`:1171`).
3. **`isEntityCollidingWithAnythingNew :1285`:** Steckt die Zielbox in neuen Blockformen, setzt der Server die Position zurück, ohne Log.
4. **Sprungerkennung:** War der Spieler auf dem Boden, meldet `onGround=false` und bewegt sich nach oben, ruft der Server `jumpFromGround` auf (`:1157`).
5. **Schweben:** Mehr als 80 Ticks ohne Bodenkontakt und ohne Abwärtsbewegung (`oyDist ≥ −0,03125`) führen zum Kick.

**Folgen für die Validierung:**
- Der Server erkennt durchdrungene Blöcke und horizontale Abweichungen über 0,25 Blöcke.
- Er erkennt **nicht**, ob Beschleunigung, Reibung, Sprunghöhe oder Fallkurve stimmen.
- Deshalb wird in M5 zusätzlich die **Tick-für-Tick-Bahn des Vanilla-Clients** als Referenz aufgezeichnet (docs/23).

## 9. Nicht in M5 umgesetzt (Folgemeilenstein)

| Thema | Regel (Kurzfassung) | Quelle |
|---|---|---|
| Wasser und Lava | `travelInFluid`: Beschleunigung 0,02 (Wasserläufer bis 0,0546); Widerstand 0,8 (Wasser) bzw. 0,5 (Lava); `vy` −0,02 bzw. −0,005 je Tick; Strömung (`updateFluidHeightAndDoFluidPushing`); Auftrieb beim Springen +0,04 | `LivingEntity#travelInFluid :2472`, `#jumpInLiquid` |
| Leitern, Ranken, Gerüst-Klettern | horizontal und abwärts auf ±0,15 geklemmt; bei horizontaler Kollision oder Sprung vy = 0,2; Schleichen hält die Höhe | `#handleOnClimbable :2675`, `#handleRelativeFrictionAndCalculateMovement :2648` |
| Sprinten | Geschwindigkeit ×1,3 (Attributmodifikator), Sprungimpuls 0,2, Luftbeschleunigung 0,026, Abbruch bei Kollision | `LocalPlayer#aiStep :819-841` |
| Schleichen | Pose 1,5 hoch, Eingabe × `SNEAKING_SPEED` 0,3, Kantenschutz (`maybeBackOffFromEdge`) | `Player :862` |
| Schwimmen, Gleiten, Fliegen | eigene Posen und Bewegungsgesetze | `Player#travel :1368`, `travelFallFlying` |
| Prallen (Schleim, Bett), Honigwand, Spinnennetz (`stuckSpeedMultiplier`) | Block-Callbacks | `Entity#move :755`, `restituteMovementAfterCollisions` |
| Entity-Kollisionen (Boote, Shulker) | `getEntityCollisions` | `Entity#collide :1178` |
| Weltgrenze | zusätzliche Form nahe der Grenze | `collectCollidersIgnoringWorldBorder` |
