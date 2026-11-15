# 22 – Kollisionsarchitektur des eigenen Clients (M5)

Dieses Dokument beschreibt die Umsetzung der in docs/21 dokumentierten Regeln im Paket `dev.mcresearch.protocolclient.physics` sowie die neue Bewegungssteuerung.

Rahmenbedingungen:
- Zur Laufzeit wird nur das JDK verwendet.
- Es gibt kein Rendering.
- Es wird kein Minecraft-Code übernommen. Kollisionsformen kommen als Datentabelle, siehe §2.

## 1. Überblick

```
ClientSession ──► MovementController ──► PlayerPhysics.tick(Input) ──► CollisionWorld ──► BlockView (= WorldState#stateId)
   (Tick-Thread)      Befehle → Tasten       aiStep/travel/move/collide       BlockCollisions      M4-Weltmodell
                      Paketwahl              Step-up, Reibung, Sprung          │
                                                                               └──► ShapeRegistry (shapes-777.txt.gz)
                                                                                     Zustand → Shape, Offset, Kontext
```

| Klasse | Aufgabe |
|---|---|
| `Aabb`, `Vec3d`, `Axis` | Box, Vektor, Achsenreihenfolge (`stepOrder`: Y, dann X/Z wie `Direction#axisStepOrder`) |
| `Shape` | Vereinigung von Boxen plus Y-Gitterkoordinaten. `collide(axis, box, d)` entspricht `VoxelShape#collide`; `intersects` hat positives Schnittvolumen. |
| `Trig` | Sinustabelle mit 65 536 `float`-Werten wie `Mth.sin/cos` |
| `ShapeRegistry` | Lädt die Formtabelle. Liefert die Form je Zustand, positionsabhängige Offsets, kontextabhängige Formen (Gerüst, Pulverschnee) und Reibungs-, Geschwindigkeits- und Sprungfaktoren. |
| `CollisionContext` | Unterkante, Schleichen, Fallstrecke (`isAbove` wie `EntityCollisionContext`) |
| `BlockView` | Lesezugriff auf Zustands-IDs. `-1` steht für einen ungeladenen Chunk. |
| `CollisionWorld` | Zellabfrage wie `BlockCollisions`: Innenzellen, Randzellen nur bei großen Formen, ohne Kanten und Ecken. Ungeladene Zellen gelten als fest (§5). |
| `PlayerPhysics` | Ein Spieler-Tick (§4) |
| `session.MovementController` | Befehle → Tastenzustand je Tick → Physik → `player_input` und Positionspaket |
| `cli.PhysicsCommands` | Terminalausgaben (§7) |

## 2. Formdaten getrennt von der Spiellogik

`analysis/tools/ShapeExtractor.java` ist ein Analysewerkzeug. Es gehört nicht zum Client und läuft gegen das **unveränderte** `server/versions/26.3/server-26.3.jar`:

```
java -cp "<server-26.3.jar>;<server/libraries/**/*.jar>" analysis/tools/ShapeExtractor.java client/src/main/resources/physics/shapes-777.txt.gz
→ states=35723 shapes=359 dynamicStates=199 positionDependent=116 blockFactors=7
```

Das Werkzeug ruft für jeden Zustand `BlockState#getCollisionShape(EmptyBlockGetter, BlockPos.ZERO, CollisionContext.empty())` auf. Diese Form verwendet auch der Zustands-Cache des Spiels. Die Ausgabe ist Tab-getrennt und gzip-komprimiert (103 826 Bytes):

| Zeile | Inhalt |
|---|---|
| `S idx boxes ys` | Form: Boxen `minX,minY,minZ,maxX,maxY,maxZ;…` und die Y-Gitterkoordinaten aus `VoxelShape#getCoords(Y)`. Letztere sind für die Step-up-Kandidaten nötig (docs/21 §5.2). |
| `B id idx flags` | Zustand → Form. `F` = voller Block, `L` = große Form (`hasLargeCollisionShape`), `D` = `dynamicShape`, `O` = Offset-Funktion. |
| `P id x,y,z idx` | Referenzform an drei weiteren Positionen für positionsabhängige Zustände. Dient der **Validierung** der eigenen Offset-Berechnung. |
| `O id maxH baseIdx maxV` | Offset-Block: offsetfreie Grundform und Grenze der horizontalen Verschiebung; M7: `maxV` für `OffsetType.XYZ`, `baseIdx` −1 = Form hängt nicht von der Position ab (empirisch geprüft, docs/27 §1.2) |
| `C name idx` | Kontextformen `ScaffoldingBlock.SHAPE_STABLE`, `SHAPE_UNSTABLE_BOTTOM`, `PowderSnowBlock.FALLING_COLLISION_SHAPE` |
| `K block f s j` | abweichende Reibung, Geschwindigkeitsfaktor, Sprungfaktor: Eis, Packeis, Blaueis, Frosteis, Schleim, Honig, Seelensand |

Die Tabelle besteht nur aus Faktendaten, wie die Blockzustandsliste in M4. Die Spiellogik dazu (Kollision, Step-up, Physik) ist eigenständig nach docs/21 geschrieben.

**Validierung gegen die Loom-Referenz** (`CollisionGeometryTest`):
- Stichproben aus den Quellen: Stufe 0–0,5 bzw. 0,5–1 (`SlabBlock`), Treppe als Platte plus Halbblock auf der `facing`-Seite (`StairBlock`), Zaunpfosten 6–10/16 mit Höhe 1,5 (`FenceBlock`), Mauerpfosten 4–12/16 (`WallBlock`), Tür 13–16/16, Falltür 3/16, Truhe 1–15/16 mit Höhe 14/16, Schnee `(layers−1)·2/16`, Teppich 1/16.
- Alle 116 Positionsproben (`P`) stimmen mit der eigenen Offset-Formel exakt überein.

## 3. Geometrie

**`Shape#collide(axis, box, d)`:**
- Für jede Box mit Querüberlappung > 1e−7 auf den beiden anderen Achsen: `d' = box.min − moving.max` (bzw. symmetrisch für negative Richtung). Ist `d' ≥ −1e−7`, gilt `d = min(d, d')`.
- Bereits durchdrungene Boxen werden ignoriert. Bei `|d| < 1e−7` ist das Ergebnis 0.
- Das entspricht `VoxelShape#collideX` mit `findIndex(min+1e−7)` und `findIndex(max−1e−7)` für jede Form, die eine Vereinigung von Boxen ist: Die nächste besetzte Zelle in Bewegungsrichtung ist die nächste Box mit `d' ≥ −1e−7`.

**`CollisionWorld#hits(query)`:**
- Durchläuft die Zellen `floor(min−1e−7)−1 … floor(max+1e−7)+1`.
- Eine Randzelle (genau eine Koordinate liegt auf dem Rand) zählt nur bei `L`-Formen. Zellen mit zwei oder drei Randkoordinaten zählen nie.
- Die Form wird an die Blockposition verschoben. Aufgenommen wird sie bei positivem Schnittvolumen mit der Abfragebox.
- Damit zählen Formen über Chunkgrenzen und bei negativen Koordinaten ohne Sonderfall, weil die `BlockView` absolute Koordinaten auflöst.

**Nachbarblöcke:**
- Verbindungen von Zäunen, Mauern, Scheiben sowie Treppenformen sind Teil des Zustands. Sie kommen vom Server im Chunk bzw. per Blockupdate (M4).
- Der Client berechnet sie nicht selbst. Lokale Vorhersage ist deshalb nicht nötig, solange der Server die Zustände sendet.

**Kontext:**
- **Gerüst:**
  - Ist die Unterkante > `y + 1 − 1e−5F` und schleicht der Spieler nicht: stabile Form.
  - Sonst bei `bottom=true` und `distance≠0`: 2/16-Platte.
  - Sonst leer.
- **Pulverschnee:** Nach mehr als 2,5 Blöcken Fall 0,9F hoch, sonst leer. Lederstiefel werden nicht modelliert.
- **Flüssigkeiten:** leer.
- **Shulkerkiste, `moving_piston`:** Tabellenwert, also geschlossen bzw. leer. Die Block-Entity-Animation ist nicht modelliert.

## 4. `PlayerPhysics#tick(Input)`

Die Reihenfolge entspricht `LivingEntity#aiStep` (docs/21 §4). Jeder Schritt verwendet die `float`/`double`-Typen der Referenz:

1. `noJumpDelay--`; Kleinstgeschwindigkeiten nullen (horizontal < 9e−6 für Spieler, |vy| < 0,003).
2. **Eingabe:**
   1. `Vec2(left, forward).normalized()`
   2. ×0,98F
   3. Quadrat-Korrektur (`modifyInputSpeedForSquareMovement`)
3. **Sprung:** Bei gehaltener Taste, `onGround` und `noJumpDelay == 0`: `vy = max(0,42F·Sprungfaktor, vy)`, dann `noJumpDelay = 10`. Ohne Taste: `noJumpDelay = 0`.
4. **`travelInAir`:**
   1. Reibung des Blocks unter dem Spieler (`getOnPos(0,500001F)` über den Stützblock).
   2. Beschleunigung 0,1F am Boden (bei Reibung > 0,6 mit 0,216F/f³) bzw. 0,02F in der Luft.
   3. `moveRelative` mit Tabellen-Sinus.
   4. `move`.
   5. Schwerkraft 0,08.
   6. Reibung ×(f·0,91F) bzw. ×0,98F.
5. **`move`:**
   1. `collide` (docs/21 §5.2, Step-up mit Gitterkandidaten).
   2. Position übernehmen.
   3. Flags `horizontalCollision` (Toleranz 1e−5F), `verticalCollision`, `onGround = verticalCollisionBelow`.
   4. Stützblock (`findSupportingBlock`) und Fallstrecke.
   5. Kollidierte Geschwindigkeitskomponenten → 0.
   6. Geschwindigkeitsfaktor des Blocks.

**Ergebnis** ist `TickResult` mit:
- Position, Geschwindigkeit, `onGround`, beiden Kollisionsflags;
- `jumped`, `stepped`, `touchedUnloaded` und `unsupported`. Das letzte Feld meldet Wasser, Lava, Kletterblöcke, Spinnennetz, Pulverschnee, Honig oder Schleim in der Hitbox. Für diese wäre die Bewegung nicht mehr exakt.

**Hitbox:** `[x ± 0,3F, y … y + 1,8F, z ± 0,3F]` wie `EntityDimensions#makeBoundingBox`. Die Werte sind `float`; der Halbwert 0,30000001192092896 ist im Laufzeittest sichtbar.

## 5. Ungeladene Chunks (bewusste Abweichung)

**Vanilla:**
- Ein ungeladener Chunk liefert **keine** Kollisionsformen.
- Steht der Spieler über einem ungeladenen Chunk, fällt er langsam (`vy = −0,1`).

**Eigener Client:**
- Zellen ungeladener Chunks gelten als **voller Block**. `TickResult.touchedUnloaded` wird gesetzt, und `move` bricht mit „chunk not loaded ahead“ ab.
- Ohne geladenen Chunk unter dem Spieler gilt die Vanilla-Regel `vy = −0,1` bzw. 0 unter `min_y`.
- Begründung: Der Client hat dort keine Weltdaten. Eine Position, die in unbekanntes Gelände hineinläuft, würde der Server korrigieren. Anhalten ist sicherer und bleibt nachvollziehbar.

## 6. Bewegungssteuerung (`MovementController`)

Die Heightmap-Bodenschätzung aus M2 ist ersetzt. Der `TerrainCache` bleibt nur als Anzeige im Befehl `position`.

**Befehle** erzeugen ausschließlich **Tastenzustände**:

| Befehl | Tasten je Tick |
|---|---|
| `move dx dy dz` | Blick zum Ziel (Yaw, sendet Rotation), vorwärts. Bei `dy > 0` Sprung, sobald eine Wand blockiert und der Spieler am Boden ist. Rechtzeitig loslassen; Ausrollweg ≈ v·0,546/0,454. Ende bei Stillstand. Ergebnis „reached“, wenn ≤ 0,25 vom Ziel. Abbruch nach 20 Ticks ohne Fortschritt („blocked by collision“) oder bei ungeladenem Chunk. |
| `walk <dir> <n> [jump]` | n Ticks die Tasten `f/b/l/r/fl/fr/bl/br` (optional mit Sprung) halten, danach ausrollen bis zum Stillstand |
| `jump` | einen Tick Sprungtaste, dann bis zur Landung warten |
| `script(inputs)` (Test-API) | genau diese Folge, Rückgabe aller `TickResult` |
| `claim` | unverändert: Testpaket ohne Physik |

**Ablauf je Tick:**
1. Tasten aus dem laufenden Befehl bestimmen.
2. `physics.tick`.
3. Ergebnis in `PlayerState` übernehmen; Trajektorie mit 400 Einträgen.
4. `player_input`, falls sich die Tasten geändert haben.
5. Positionspaket nach `LocalPlayer#sendPosition`: höchstens eines je Tick, mit `onGround` und `horizontalCollision` aus der Physik.

**Serverpositionen** (`player_position`):
- Position, Rotation und Geschwindigkeit nach `PositionMoveRotation#calculateAbsolute`, mit den relativen Bits `DELTA_*`.
- `onGround` bleibt wie im Vanilla-Client unverändert.
- Laufende Befehle werden abgebrochen.

## 7. Terminalbefehle (neu)

| Befehl | Ausgabe |
|---|---|
| `walk <f\|b\|l\|r\|fl\|fr\|bl\|br> <ticks> [jump]` | Ergebnis nach dem Ausrollen |
| `jump` | Ergebnis nach der Landung |
| `physics` | Position, Yaw, Geschwindigkeit, `onGround` und Kollisionsflags, `noJumpDelay`, Fallstrecke, letzte Tasten, Stützblock |
| `collision` | Bodenkontakt, Flags und alle Blockformen im Abstand ≤ 0,001 zur Hitbox, mit Zustand, Boxanzahl und Lage (below/above/side) |
| `hitbox` | Grenzen der Hitbox, Breite, Höhe, Step-Höhe |
| `trajectory [n]` | letzte n Physik-Ticks (Standard 20, höchstens 400) |

Die bisherigen Befehle (`position`, `move`, `look`, `status`, `compression`, `block`, `chunk`, `world`, `nearby`, `blocks`, `wait`, `claim`, `help`, `quit`) bleiben erhalten.

## 8. Nebenläufigkeit

- `MovementController` ist synchronisiert.
- Die Physik ruft `WorldState#stateId` auf. Das ist ebenfalls synchronisiert; die Sperrreihenfolge ist immer Movement → World.
- Der Netzwerk-Thread nimmt nur die World-Sperre, wenn er Chunks oder Blöcke ändert. Ein Deadlock ist damit ausgeschlossen.
- Die Terminalbefehle `physics`, `collision` und `hitbox` lesen unter der Movement-Sperre.

## 9. Grenzen

- Nicht modelliert: Flüssigkeiten, Leitern und Ranken, Sprinten, Schleichen, Schwimmen, Gleiten, Fliegen, Prallen auf Schleim und Bett, Spinnennetz, Entity-Kollisionen, Weltgrenze.
- Die Regeln und Quellstellen dazu stehen in docs/21 §9. Die Erkennung meldet solche Umgebungen über `TickResult.unsupported`.
