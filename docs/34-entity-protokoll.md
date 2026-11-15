# 34 – Entity-Protokoll (M9)

**Stand:** 2026-11-12 · Minecraft 26.3 / Protokoll 777 · Architektur: [35](35-entity-architektur.md) · Rendering: [36](36-entity-rendering.md) · Ergebnisse: [37](37-m9-testbericht.md)

**Quellen:**
- Loom-Quellen in `analysis/mc-src/` (Referenzformat wie in [README](README.md)).
- Paket-IDs aus `packets-777.txt` (offizieller Datengenerator).
- Registry-IDs aus `registries.json` desselben Generators.
- Abmessungen und Datenfeld-Indizes mit `analysis/tools/EntityExtractor.java` aus dem unveränderten Server-JAR gelesen; Ergebnis: `client/src/main/resources/protocol/entities-777.txt`.

Es wurde kein Mojang-Code übernommen. Die Tabellen enthalten nur Zahlen und Feldformate.

## 1. Paket-IDs (Play, clientgebunden, 26.3)

| ID | Paket | Inhalt |
|---|---|---|
| 0 | `bundle_delimiter` | klammert zusammengehörige Pakete (Spawn-Daten) |
| 1 | `add_entity` | Spawn |
| 2 | `animate` | 0 Aufwachen, 1 kritischer Treffer, 2 magischer kritischer Treffer |
| 34 | `entity_event` | Ereignis-Byte (Tod, Schaden, Liebe …) |
| 35 | `entity_position_sync` | absolute Position (volle Genauigkeit) + Rotation |
| 54 / 55 / 57 | `move_entity_pos` / `_pos_rot` / `_rot` | relative Bewegung / Rotation |
| 70 / 71 | `player_info_remove` / `player_info_update` | Spielerliste |
| 78 | `remove_entities` | Entfernen |
| 85 | `rotate_head` | Kopfdrehung |
| 101 | `set_entity_data` | synchronisierte Datenfelder |
| 102 | `set_entity_link` | Leine |
| 103 | `set_entity_motion` | Geschwindigkeit |
| 104 | `set_equipment` | Ausrüstung |
| 109 | `set_passengers` | Passagiere |
| 123 | `swing_animation` | **neu in 26.x:** Armschwung (früher `animate` 0/3) |
| 127 | `take_item_entity` | Aufsammeln (Item fliegt zum Sammler) |
| 128 | `teleport_entity` | absoluter oder teilrelativer Teleport |
| 134 | `update_attributes` | Attribute |

## 2. Spawn (`add_entity`, `ClientboundAddEntityPacket.java:95`)

| Feld | Typ |
|---|---|
| Entity-ID | VarInt |
| UUID | 2 × long |
| Typ | VarInt (Registry `entity_type`, 161 Einträge; z. B. cow 30, item 72, pig 101, sheep 114, skeleton 118, zombie 154, player 159) |
| x, y, z | double |
| Bewegung | `LpVec3` (unten) |
| Pitch (xRot), Gier (yRot), Kopfgier | je 1 Byte, Winkel = Byte · 360 / 256 (`Mth.unpackDegrees`) |
| Daten | VarInt (typabhängig, z. B. Richtung bei Bildern, Besitzer bei Projektilen) |

**Spawn-Bündel:** Der Server sendet beim Sichtbarwerden ein `bundle` (`ServerEntity#addPairing`, `:300`):
1. `add_entity`;
2. `set_entity_data` mit allen Nicht-Standardwerten;
3. für Lebewesen `update_attributes` und `set_equipment` (nur belegte Plätze);
4. danach je nach Fall `set_passengers` und `set_entity_link`.

**`LpVec3`** (`network/LpVec3.java`):
1. Ist das erste Byte 0, ist der Vektor null.
2. Sonst ergeben 6 Byte (1 + 1 + 4, little-endian zusammengesetzt) einen 48-Bit-Puffer:
   - Bits 0–1: Skalierung;
   - Bit 2: Fortsetzung → Skalierung `|= VarInt << 2`;
   - je 15 Bit für x (ab Bit 3), y (ab Bit 18), z (ab Bit 33).
3. Komponente = `min(v, 32766) · 2 / 32766 − 1`, multipliziert mit der Skalierung.

## 3. Bewegung

### 3.1 Relative Bewegung (`ClientboundMoveEntityPacket.java`)

| Paket | Felder |
|---|---|
| `move_entity_pos` | ID (VarInt), Eigenschaften (VarInt), VecDelta |
| `move_entity_pos_rot` | ID, Eigenschaften, VecDelta, yRot (Byte), xRot (Byte) |
| `move_entity_rot` | ID, onGround (bool), yRot, xRot |

**Eigenschaften:** Bit 0 = onGround, Rest = Schrittzahl n (`properties >>> 1`).

**VecDelta** (`VecDelta.java`, neu in 26.x):
- **n = 0:** ein Delta aus 3 × short (`Linear`).
- **n > 0:** n Schritte aus je `ticks` (VarInt) und 3 × short (`Stepped`). Jeder Schritt baut auf dem vorigen auf. Der Server erzeugt sie für Lebewesen aus den Zwischenpositionen mehrerer Ticks (`SteppedInterpolationTracker`).

**Dekodierung** (`VecDeltaCodec#decode`): Der Client hält je Entity eine **Basis**, die letzte vom Server bestätigte Position. Für jede Achse gilt:
- Delta 0 → Basiswert unverändert;
- sonst `(round(basis · 4096) + delta) / 4096`.

Nach dem Paket wird die Basis auf die Endposition gesetzt (`ClientPacketListener#handleMoveEntity`, `:724`).

**Folgerung für den Vergleich mit dem Server:** Der Client kennt die Position nur auf 1/4096 Block gerundet; die Abweichung beträgt höchstens 1/8192 ≈ 0,000122. Achsen ohne Änderung bleiben exakt.

### 3.2 Absolute Positionen

| Paket | Felder | Verwendung durch den Server (`ServerEntity#createMovePacket`, `:216`) |
|---|---|---|
| `entity_position_sync` | ID, PositionPath, yRot (float), xRot (float), onGround | Delta zu groß (> 8 Blöcke je Achse), onGround gewechselt, alle 400 Aktualisierungen, nach dem Reiten, für Items bei Kollision |
| `teleport_entity` | ID, Position (3 × double), Bewegung (3 × double), yRot, xRot (float), Relativ-Maske (int), onGround | `/tp`, Weltwechsel u. a. |

- **PositionPath:** VarInt-Typ (0 linear: 3 × double; 1 gestuft: Liste aus Position + ticks).
- **Relativ-Maske** wie bei `player_position` (M3): Bit 0–2 x/y/z, 3 yRot, 4 xRot, 5–7 Bewegung, 8 Drehung der Bewegung.

Der Vanilla-Client springt bei einem Abstand > 64 Blöcke oder bei nicht tickenden Entities; sonst interpoliert er (`handleEntityPositionSync`, `:645`).

### 3.3 Wie oft gesendet wird (`ServerEntity#sendChanges`, `:98`)

| Typ | Intervall (Ticks) | Reichweite (Chunks) | Geschwindigkeit mitsenden |
|---|---|---|---|
| Spieler | 2 | 32 | nein |
| Schwein, Kuh, Schaf | 3 | 10 | ja |
| Zombie, Skelett | 3 | 8 | ja |
| Item | 20 | 6 | ja |

- **Position:** Sie wird gesendet, wenn sie sich um ≥ √7,63·10⁻⁶ ≈ 0,0028 geändert hat, mindestens aber alle 60 Aktualisierungen.
- **Rotation:** Sie wird gesendet, wenn sich der gepackte Byte-Wert ändert.
- **Kopfdrehung:** `rotate_head` (ID, Byte) wird gesendet, wenn sich der gepackte Wert ändert.

### 3.4 Interpolation im Vanilla-Client (zum Vergleich, nicht übernommen)

- Lebewesen: `SteppedInterpolationHandler` mit `updateInterval` Ticks je Schritt und Aufholgeschwindigkeit bei Rückstand.
- Items: keine Interpolation, eigene Physik mit `set_entity_motion`.
- Kopf: `lerpHeadTo(…, 3)`.

Der eigene Ansatz steht in [35](35-entity-architektur.md) §4.

## 4. Entfernen und Entladen

- `remove_entities`: VarInt-Liste von IDs.
- Der Server sendet sie, wenn die Entity verschwindet (Tod, `kill`, Aufsammeln) oder der Spieler sie nicht mehr verfolgt: Reichweite verlassen oder Chunk nicht mehr gesendet.
- Weltwechsel (`respawn`) und Trennung: Der Vanilla-Client verwirft die ganze Welt einschließlich aller Entities. Ein eigenes Paket gibt es dafür nicht.

## 5. Synchronisierte Datenfelder (`set_entity_data`)

**Format:** ID (VarInt), dann Einträge aus Index (unsigned byte), Serialisierer-ID (VarInt) und Wert; Ende bei Index 255 (`ClientboundSetEntityDataPacket.java:33`).

**Serialisierer-IDs** (Registrierungsreihenfolge `EntityDataSerializers.java:164`):

| ID | Typ | Kodierung | ID | Typ | Kodierung |
|---|---|---|---|---|---|
| 0 | byte | 1 Byte | 12 | Direction | VarInt |
| 1 | int | VarInt | 13 | opt. Entity-Referenz | bool + UUID |
| 2 | long | VarLong | 14 | BlockState | VarInt |
| 3 | float | 4 Byte | 15 | opt. BlockState | VarInt (0 = leer) |
| 4 | String | VarInt-Länge + UTF-8 | 16/17 | Partikel / Liste | typabhängig |
| 5 | Text | NBT | 18 | VillagerData | 3 VarInt |
| 6 | opt. Text | bool + NBT | 19 | opt. uint | VarInt (0 = leer, sonst v−1) |
| 7 | ItemStack | wie im Inventar (M8) | 20 | Pose | VarInt |
| 8 | bool | 1 Byte | 21–32 | Varianten (Katze, Kuh, Wolf, Frosch, Schwein, Huhn …) | VarInt (Registry-ID) |
| 9 | Rotations | 3 float | 33 | opt. GlobalPos | bool + Dimension + Position |
| 10 | BlockPos | long | 39 / 40 | Vector3 / Quaternion | 3 / 4 float |
| 11 | opt. BlockPos | bool + long | 41–43 | Profil, HumanoidArm, DyeColor | –, VarInt, VarInt |

**Indizes der umgesetzten Typen** (Laufzeitextraktion):

| Klasse | Index: Feld (Serialisierer) |
|---|---|
| Entity | 0 Flags (byte: 0x01 brennt, 0x02 schleicht, 0x08 sprintet, 0x10 schwimmt, 0x20 unsichtbar, 0x40 leuchtet, 0x80 Elytra), 1 Luft, 2 eigener Name (opt. Text), 3 Name sichtbar, 4 stumm, 5 ohne Schwerkraft, 6 Pose, 7 Frostticks |
| LivingEntity | 8 Flags, 9 Gesundheit (float), 10 Effektpartikel, 11 Effekt-Ambiente, 12 Pfeile, 13 Stachel, 14 Schlafposition |
| Mob | 15 Mob-Flags (0x01 ohne KI, 0x02 Linkshänder, 0x04 aggressiv) |
| AgeableMob (Schwein, Kuh, Schaf) | 16 Baby, 17 Alter gesperrt |
| Schwein | 18 Boost, 19 Variante (28), 20 Klangvariante (29) |
| Kuh | 18 Variante (23), 19 Klangvariante (24) |
| Schaf | 18 Wolle (byte: Bits 0–3 Farbe, 0x10 geschoren) |
| Zombie | 16 Baby, 17 Sondertyp, 18 Ertrinken-Umwandlung |
| Skelett | 16 Umwandlung zu Eiswanderer |
| Avatar / Spieler | 15 Haupthand (HumanoidArm), 16 Skin-Teile (byte), 17 Absorption, 18 Punkte, 19/20 Papagei links/rechts |
| ItemEntity | 8 Gegenstand (ItemStack) |

**Unbekannte Serialisierer:** Ein Wert lässt sich nur mit bekanntem Format überspringen. Trifft der Client auf einen nicht umgesetzten Serialisierer (z. B. Partikel), endet die Auswertung dieses Pakets an dieser Stelle. Bereits gelesene Werte bleiben gültig, der Rest wird gezählt. Das ist sicher, weil jedes Paket durch das Framing abgegrenzt ist.

## 6. Attribute (`update_attributes`)

**Format:**
- ID, Anzahl;
- je Attribut: Registry-ID (VarInt), Basiswert (double), Modifikatoren (Anzahl; je Identifier, Betrag als double, Operation als VarInt 0–2).

Diese Dekodierung existiert seit M5 (eigene Attribute) und gilt unverändert für alle Lebewesen. Registry `attribute` hat 40 Einträge, z. B. 23 max_health, 26 movement_speed, 30 scale.

## 7. Ausrüstung (`set_equipment`)

- ID, dann Einträge aus Platz (Byte; Bit 7 = weitere Einträge folgen) und ItemStack.
- Platz = Ordinalzahl von `EquipmentSlot`: 0 Haupthand, 1 Nebenhand, 2 Füße, 3 Beine, 4 Brust, 5 Kopf, 6 Körper (Tierpanzer), 7 Sattel.

## 8. Animationen und Ereignisse

| Paket | Felder |
|---|---|
| `swing_animation` | ID, Hand (VarInt 0/1), SwingAnimation |
| `animate` | ID, Aktion (unsigned byte) |
| `entity_event` | ID (int, 4 Byte), Ereignis (byte), z. B. 3 Tod, 18 Liebe, 60 Todesrauch (der Client speichert nur das letzte Ereignis) |
| `hurt_animation` | ID, Gier (float) |
| `take_item_entity` | Item-ID, Sammler-ID, Anzahl (je VarInt) |

## 9. Spielerinformationen

**`player_info_update`:**
- Aktionsmaske: 1 Byte, Bit i = Aktion i;
- Liste von Einträgen aus UUID und den Feldern aller gesetzten Aktionen in aufsteigender Reihenfolge:

| Bit | Aktion | Felder |
|---|---|---|
| 0 | ADD_PLAYER | Name (String ≤ 16), Eigenschaften (≤ 16 × Name, Wert, opt. Signatur) |
| 1 | INITIALIZE_CHAT | opt. (Sitzungs-UUID, Ablauf long, Schlüssel-Bytes, Signatur-Bytes) |
| 2 | UPDATE_GAME_MODE | VarInt |
| 3 | UPDATE_LISTED | bool |
| 4 | UPDATE_LATENCY | VarInt |
| 5 | UPDATE_DISPLAY_NAME | opt. Text (NBT) |
| 6 | UPDATE_LIST_ORDER | VarInt |
| 7 | UPDATE_HAT | bool |

**`player_info_remove`:** Liste von UUIDs.

Ein Spieler-Entity (Typ 159) wird über die UUID aus `add_entity` dem Listeneintrag zugeordnet (`handleAddEntity`, `:586`). Die Skin-Textur steckt in der Eigenschaft `textures` (Base64-JSON mit URL). Ein Download ist untersagt, der Client verwendet immer einen lokalen Standard-Skin.

## 10. Passagiere

- `set_passengers`: Fahrzeug-ID, VarInt-Liste der Passagier-IDs.
- `set_entity_link`: Leinen-Entity (int), Halter (int; 0 = keiner).

## 11. Hitbox und Augenhöhe

**Grundlage:** Laufzeitextraktion, `EntityType#getDimensions`.

| Typ | Breite | Höhe | Augenhöhe |
|---|---|---|---|
| Spieler (stehend) | 0,6 | 1,8 | 1,62 |
| Spieler schleichend | 0,6 | 1,5 | 1,27 |
| Spieler schwimmend/Elytra | 0,6 | 0,6 | 0,4 |
| Spieler schlafend | 0,2 | 0,2 | 0,2 |
| Schwein | 0,9 | 0,9 | 0,765 |
| Kuh | 0,9 | 1,4 | 1,3 |
| Schaf | 0,9 | 1,3 | 1,235 |
| Zombie | 0,6 | 1,95 | 1,74 |
| Skelett | 0,6 | 1,99 | 1,74 |
| Item | 0,25 | 0,25 | 0,2125 |

**Berechnung** (`EntityDimensions#makeBoundingBox`): Box = (x ± b/2, y … y + h, z ± b/2).

**Skalierung:** Bei Lebewesen werden die Maße mit dem Attribut `scale` multipliziert, außer bei festen Maßen (`LivingEntity#getDimensions`, `:3728`).

**Babys und Sonderformen:** Viele Typen überschreiben `getDefaultDimensions` mit eigenen Baby-Maßen. Allgemein gilt sonst der Altersfaktor 0,5. Der erste Auswahlvergleich gegen Vanilla hat diesen Unterschied aufgedeckt ([37](37-m9-testbericht.md) §2).

| Typ | Baby (B × H) | Quelle |
|---|---|---|
| Schwein | 0,45 × 0,45 | `Pig.java:70` |
| Kuh | 0,45 × 0,7 | `Cow.java:37` |
| Schaf | 0,45 × 0,65 | `Sheep.java:61` |
| Zombie | 0,49 × 0,98 | `Zombie.java:91` |
| Rüstungsständer klein (Flag 0x01) | halbe Typmaße | `ArmorStand.java:64` |
| Rüstungsständer Markierung (Flag 0x10) | 0 × 0, nicht auswählbar | `ArmorStand.java:63` |

**Auswählbar** (`isPickable`):
- Lebewesen;
- Spieler außer Zuschauern;
- Rüstungsständer außer Markierungen.

Items sind nicht auswählbar.

## 12. Auswahl (Vanilla, `LocalPlayer#pick`, `:1286`)

1. Strahl vom Auge mit der Länge max(Block-, Entity-Reichweite).
2. Ein Blocktreffer kürzt den Strahl.
3. Für jede auswählbare Entity in der erweiterten Box wird der Schnitt mit ihrer Hitbox (vergrößert um `pickRadius`, meist 0) berechnet; der nächste Schnitt gewinnt. Liegt das Auge in der Box, gilt Abstand 0.
4. Die Entity gewinnt, wenn sie näher als der Block ist, aber nur innerhalb der Entity-Reichweite (Standard 3,0; Kreativ 5,0). Sonst gilt der Block innerhalb der Block-Reichweite, sonst kein Treffer.

## 13. Zustandsübergänge

```
(unbekannt) --add_entity--> lebendig --move/sync/teleport/data/attr/equip--> lebendig
lebendig --remove_entities--> entfernt
alle --respawn (Weltwechsel) / Trennung--> verworfen
Spielerliste: player_info_update(ADD) --> Eintrag; player_info_remove --> gelöscht
Pakete für unbekannte IDs werden gezählt und ignoriert (Vanilla ebenso)
```
