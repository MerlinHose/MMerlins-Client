# 15 – Spielerbewegung (M2)

Stand: 2026-11-07 · Protokoll 777 / Minecraft 26.3 · Implementierung: `client/` (`session/MovementController`, `session/PlayerState`, `world/TerrainCache`, `cli/TerminalController`)
Quellen relativ zu `analysis/mc-src/`; IDs aus `packets.json` (offizieller Datengenerator). Testergebnisse: [17](17-m2-m3-testbericht.md).

## 1. Pakete

### 1.1 Client → Server (PLAY)

| Paket | ID | Felder | Größe¹ | Quelle |
|---|---|---|---:|---|
| `move_player_pos` | 0x1E | x Double, y Double, z Double, flags UByte | 26 B | `network/protocol/game/ServerboundMovePlayerPacket.java:108` (`Pos`) |
| `move_player_pos_rot` | 0x1F | x, y, z Double, yRot Float, xRot Float, flags UByte | 34 B | `…ServerboundMovePlayerPacket.java:144` (`PosRot`) |
| `move_player_rot` | 0x20 | yRot Float, xRot Float, flags UByte | 10 B | `…:184` (`Rot`) |
| `move_player_status_only` | 0x21 | flags UByte | 2 B | `…:214` (`StatusOnly`) |
| `player_input` | 0x2B | flags Byte: 1 forward, 2 backward, 4 left, 8 right, 16 jump, 32 shift, 64 sprint | 2 B | `network/protocol/game/ServerboundPlayerInputPacket.java`, `world/entity/player/Input.java` |
| `accept_teleportation` | 0x00 | teleportId VarInt, x/y/z Double, yRot/xRot Float | 34 B | `ServerboundAcceptTeleportationPacket` (docs/12) |
| `client_tick_end` | 0x0D | – | 1 B | `ServerboundClientTickEndPacket` |

`flags` (`ServerboundMovePlayerPacket#packFlags`, `:10`): Bit 0 `onGround`, Bit 1 `horizontalCollision`.
¹ ID + Felder; identisch mit dem Vanilla-Referenzlauf (docs/10: Pos 26, PosRot 34, Rot 10, StatusOnly 2, Input 2) und mit dem eigenen Client (Unit-Test `MovementPacketTest`, Laufzeit: `move_player_pos` 2 678 B / 103 Pakete = 26 B).

### 1.2 Server → Client (PLAY)

| Paket | ID | Felder | Reaktion des Clients | Quelle |
|---|---|---|---|---|
| `player_position` | 0x49 | teleportId VarInt, Position Vec3, Delta Vec3, yRot, xRot Float, relatives Int | Relative-Bits anwenden, `accept_teleportation` mit absoluter Position, laufende Bewegung abbrechen, nächster Tick meldet Position | `ClientPacketListener#handleMovePlayer :790` |
| `player_rotation` | 0x4A | yRot Float, relativeY Bool, xRot Float, relativeX Bool | Rotation setzen (relativ/absolut); nächster Tick sendet `move_player_rot` | `ClientboundPlayerRotationPacket`, `ClientPacketListener#handleRotatePlayer :827` |
| `level_chunk_with_light` | 0x2E | x Int, z Int, Heightmaps Map(VarInt Typ → LongArray), … | nur Präfix: Heightmap `MOTION_BLOCKING` (Typ 4) → Bodenhöhen | `ClientboundLevelChunkWithLightPacket`, `ClientboundLevelChunkPacketData.java:4` |
| `forget_level_chunk` | 0x26 | ChunkPos Long (x untere, z obere 32 Bit) | Höhendaten verwerfen | `ClientboundForgetLevelChunkPacket`, `world/level/ChunkPos.java:73` |

Nicht ausgewertet: `set_entity_motion` (0x67, Rückstoß), `respawn` (0x54), `set_health` (0x6A) – siehe §8.

## 2. Serverregeln (`server/network/ServerGamePacketListenerImpl.java`)

| Regel | Stelle | Folge für den Client |
|---|---|---|
| NaN-Koordinate oder nicht endliche Rotation | `handleMovePlayer :1062`, `containsInvalidValues :439` | Disconnect `invalid_player_movement` → Client erzeugt nur endliche Werte (Record-Prüfung) |
| **mehr als ein Positionspaket zwischen zwei `client_tick_end`** | `handleMovePlayer` (`receivedPositionThisTick`), Rücksetzen in `handleClientTickEnd :2227` | Disconnect → **genau ein Positionspaket je Tick, danach `client_tick_end`** (Tick-Reihenfolge wie `Minecraft#tick`) |
| Bewegung erst nach `player_loaded` | `hasClientLoaded()` | Bewegung startet erst nach Spawn-Teleport + `player_loaded` |
| Während ein Teleport unbestätigt ist, nur Rotation | `updateAwaitingTeleport :1269` (Teleport wird alle 20 Ticks wiederholt) | `accept_teleportation` sofort; Teleport und Bestätigung unter der Tick-Sperre |
| „moved too quickly“: Weg² seit Tick-Beginn − Geschwindigkeit² > 100 je Paket (300 im Gleitflug) | `:1146` | WARN + Rücksetzung (`teleport`) |
| Kollisionsbewegung: horizontale Restabweichung² > 0,0625 | „moved wrongly“ `:1185` | WARN + Rücksetzung |
| neue Kollision mit Blöcken an der Zielposition | `isEntityCollidingWithAnythingNew :1190` | Rücksetzung **ohne** Logeintrag |
| vertikale Abweichung nach der Kollisionsbewegung | `if (yDist > -0.5 || yDist < 0.5) yDist = 0.0; :1171` | Bedingung ist **immer wahr** → Höhenabweichungen lösen nie „moved wrongly“ aus |
| Schweben | `tickPlayer :322-339`, `getMaximumFlyingTicks` | nach 80 Ticks Kick `multiplayer.disconnect.flying` → Client wendet Schwerkraft an |
| `player_input` | `handlePlayerInput :430` | speichert Eingabe, setzt Schleichen, AFK-Zeit; **nicht** nötig für Positionsannahme |
| Koordinatenklemmung | `clampHorizontal/Vertical :443-449` | ±3·10⁷ bzw. ±2·10⁷ |

## 3. Vanilla-Sendelogik (`client/player/LocalPlayer.java`)

`sendChanges :265`: `player_input` nur bei Änderung, dann `sendPosition :295`:
- Position gilt als bewegt, wenn |Δ|² > (2·10⁻⁴)² **oder** 20 Ticks ohne Positionspaket vergangen sind (Erinnerung).
- bewegt + Rotation geändert → `PosRot`; nur bewegt → `Pos`; nur Rotation → `Rot`; sonst `StatusOnly`, falls sich `onGround` oder `horizontalCollision` geändert haben.

Der eigene Client übernimmt diese Regeln unverändert (`MovementController#positionPacket`).

## 4. Bodenhöhen

- Heightmap `MOTION_BLOCKING` (`world/level/levelgen/Heightmap.java:150`: bewegungsblockierende Blöcke und Fluide).
- 256 Werte (Index `x + z·16`, `:141`), `ceillog2(height + 1)` Bits pro Wert (`:37`), `64 / bits` Werte pro Long, keine Werte über Long-Grenzen (`util/SimpleBitStorage.java:250-292`).
- Bodenhöhe = Wert + `min_y` (`Heightmap#getFirstAvailable :119`). Überwelt: `min_y = −64`, `height = 384` → 9 Bit, 37 Longs.
- `min_y`/`height` stammen aus dem Registry-Eintrag `minecraft:dimension_type` (`DimensionType.java:90-91`). Diese Inhalte sendet der Server **nur, wenn der Client die Known Packs ablehnt** (docs/12 §5.4). Deshalb ist `--known-packs decline` jetzt Standard; mit `accept` ist die Bodenhöhe unbekannt.
- Der Client nimmt die **höchste** Bodenhöhe aller Spalten unter der 0,6 × 0,6 breiten Hitbox. Das wurde durch einen Laufzeitfehler nötig (docs/17 §3.1).

## 5. Spielerzustand und Bewegungsmodell

`PlayerState` trennt:
- **behauptete** Position/Rotation (was der Client zuletzt gesendet hat bzw. senden wird),
- **letzte Serverposition** (aus `player_position`, Zähler `serverPositionUpdates`),
- `onGround`, `horizontalCollision`, vertikale Geschwindigkeit.

Ob der Server eine Behauptung übernimmt, sieht der Client nur indirekt: Es folgt keine Korrektur. Der Testbericht prüft es zusätzlich serverseitig per `/data get entity`.

| Parameter | Wert | Herkunft |
|---|---|---|
| Gehgeschwindigkeit | 0,2158 Blöcke/Tick | Vanilla-Gehen im Gleichgewicht (≈ 4,3 m/s) |
| Stufenhöhe ohne Sprung | 0,6 | `Attributes.STEP_HEIGHT`-Standard |
| Sprung | vy = 0,42 → max. ≈ 1,25 Blöcke | `LivingEntity#getJumpPower` |
| Schwerkraft | vy = (vy − 0,08) · 0,98 je Tick | `LivingEntity#travel` |
| Hitbox | 0,6 × 1,8 | `EntityType.PLAYER` |

Ablauf eines `move dx dy dz` (je Tick):
1. Horizontal höchstens 0,2158 Richtung Ziel.
2. Bodenhöhe der Zielspalten ≤ aktuelle Höhe + 0,6 → gehen bzw. hochsteigen. Höher bis 1,25 und `dy > 0` → springen. Sonst: **blockiert**, Abbruch mit Meldung.
3. Boden tiefer → fallen mit Schwerkraft. In der Luft kann der Spieler nur in Spalten eintreten, deren Boden unter den Füßen liegt.
4. Reine Aufwärtsbewegung (`move 0 1 0`) ist nur als Sprung möglich und endet wieder auf dem Boden.
5. Ende, wenn die Zielspalte erreicht ist und der Spieler steht. Abweichungen der Höhe werden gemeldet.

**Kein Teleport als Bewegung:** Jede Positionsänderung erfolgt über reguläre Ticks mit ≤ 0,2158 horizontal und physikalischer Vertikalbewegung. Nur der Testbefehl `claim` sendet absichtlich ein einzelnes unphysikalisches Paket.

**Serverkorrektur:** `player_position` während einer Bewegung bricht diese ab („corrected by server“). Die Serverposition wird übernommen und bestätigt; im nächsten Tick folgt wie bei Vanilla ein `move_player_pos` mit dieser Position.

## 6. Testbefehl `claim`

`claim dx dy dz` sendet **ein** `move_player_pos` mit versetzter Position ohne Physik und wartet 10 Ticks:
- kommt `player_position` → „claim rejected: server reset the position“,
- sonst → „server accepted“.

Damit lassen sich die Regeln aus §2 prüfen: Bewegung in den Boden, mehr als 10 Blöcke, kleine plausible Verschiebung.

## 7. Terminalbedienung

`./gradlew run --args="--interactive"` (oder IntelliJ-Konfiguration mit `--interactive`). Ohne `--duration` läuft die Sitzung bis `quit`.

| Befehl | Wirkung |
|---|---|
| `position` | behauptete Position, letzte Serverposition, Bodenhöhe, geladene Chunks |
| `move <dx> <dy> <dz>` | Bewegung über reguläre Ticks; Ausgabe: Ergebnis, Ticks, Endposition |
| `look <yaw> <pitch>` | Blickrichtung (Yaw 0 = +Z/Süden, 90 = −X/Westen; Pitch −90…90) |
| `status` | Zustand, Tick-Zähler, Korrekturen, Keep-Alives, Position, **Kompressionszähler** |
| `compression` | nur Kompressions- und Transportzähler (docs/16 §6) |
| `wait <s>` | warten (für Skripte) |
| `claim <dx> <dy> <dz>` | Testpaket (§6) |
| `quit` | kontrollierter Disconnect |

Beispielausgabe (Lauf m2-m3-256):
```
> move 3 0 0
  reached target after 14 ticks at (-4.400, 67.000, -2.500)
> claim 15 0 0
  claim rejected: server reset the position after 10 ticks at (-7.400, 67.000, -2.500) [server correction]
> status
  state=PLAY playing=true ticks=740 movementTicks=740 moving=false corrections=2 keepAlives=2
  claimed (-7.300, 67.000, -2.500) yaw=-45.0 pitch=20.0 onGround=true | last server position (-7.400, 67.000, -2.500) | ground 67 | chunks 49
  compression threshold=256 | in: 3861 frames (94 compressed, 3767 uncompressed), 451070 wire bytes -> 2758057 packet bytes | out: 871 frames (0 compressed, 871 uncompressed), 3500 packet bytes -> 4369 wire bytes
```

## 8. Grenzen

- Blockformen (Stufen, Zäune, Schnee), Wasser- und Leiterphysik, Blockänderungen nach dem Chunk-Empfang und Blöcke über dem Kopf werden nicht modelliert. Abweichungen korrigiert der Server.
- Liegt die Heightmap über den Füßen (Überhang, Baumkrone, Halbblock), gilt der Boden als unbekannt. Der Client bleibt dann auf seiner Höhe und hebt den Spieler nie an.
- Kein Sprinten, Schleichen, Schwimmen, Fliegen; kein Rückstoß (`set_entity_motion`), kein Respawn/Dimensionswechsel.
- Mit `--known-packs accept` keine Bodenhöhen → Bewegung meldet „terrain unknown“.
