# 19 – Weltarchitektur des eigenen Clients (M4)

Dieses Dokument beschreibt die eigene Weltverwaltung im Paket `dev.mcresearch.protocolclient.world`.
Das Datenformat ist in docs/18 spezifiziert. Die Laufzeitergebnisse stehen in docs/20.

Rahmenbedingungen:
- Zur Laufzeit wird nur das JDK verwendet.
- Es gibt kein Rendering und keine Kollisionsphysik.
- Mojang-Code wird nicht kopiert. Die Implementierung folgt dem in docs/18 beschriebenen Format.

## 1. Überblick

```
Netzwerk-Thread                                           Terminal-Thread
───────────────                                           ───────────────
RawPacket ─► ClientboundDecoder ─► Clientbound-Record ─► ClientSession#handle
               (Paketstruktur,                              │
                Restbytes = Fehler)                         ├─► WorldState  ◄───────── WorldCommands
                                                            │     ├─ ChunkDecoder        (block, chunk, world,
                                                            │     │    └─ PalettedContainer ─ BitStorage   nearby, blocks)
                                                            │     ├─ Chunk ─ ChunkSection[]
                                                            │     └─ BlockRegistry (35 723 Zustände, 49 BE-Typen)
                                                            └─► TerrainCache (M2, Bodenhöhe für Bewegung)
```

Die Dekodierung erfolgt in zwei Stufen.

1. **`ClientboundDecoder` (Protokollschicht)** liest die Paketstruktur von `level_chunk_with_light` vollständig:
   - Heightmaps
   - Sektionspuffer als Byte-Array
   - Block-Entities
   - Lichtmasken und Licht-Arrays

   Bleiben danach Bytes übrig, ist das ein Fehler (`expectEnd`). Dimension und Registries werden in dieser Stufe nicht benötigt.
2. **`ChunkDecoder` (Weltschicht)** interpretiert den Sektionspuffer. Dafür braucht er die Dimension (`min_y`, `height`), die Blockzustandstabelle und die Größe der Biom-Registry. Auch hier muss der Puffer exakt aufgebraucht werden.

Die Trennung hält die Protokollschicht frei von Weltwissen. Die Weltschicht lässt sich so ohne Netzwerk testen.

## 2. Klassen

| Klasse | Aufgabe |
|---|---|
| `BlockState` | Record aus globaler ID, Name und Eigenschaften. `toString()` liefert das Befehlsformat `name[k=v,…]` mit sortierten Schlüsseln. `isAir()` gilt für `air`, `cave_air` und `void_air`. |
| `BlockRegistry` | Lädt die Tabellen `protocol/blockstates-777.txt.gz` (194 962 B) und `blockentities-777.txt`. Prüft beim Laden, dass die IDs lückenlos sind. Bietet `ceilLog2`. |
| `BitStorage` | Packen und Entpacken nach `SimpleBitStorage`: `64/bits` Werte pro Long, kein Wert über eine Long-Grenze. Die Länge des Arrays wird geprüft. |
| `PalettedContainer` | Container-Arten `BLOCK_STATES` (16³) und `BIOMES` (4³). Enthält die Konfigurationstabelle aus docs/18 §4.2 und die Palettentypen `SINGLETON`, `LINEAR`, `HASHMAP` und `GLOBAL`. Speichert immer **globale** IDs. `get` und `set` arbeiten mit lokalen Koordinaten. |
| `ChunkSection` | Speichert `nonEmptyBlockCount` (bei Änderungen nachgeführt), den gesendeten Zähler, `fluidCount` (wie gesendet), Blockzustände und Biome. |
| `Chunk` | Enthält: <ul><li>Position und Dimension</li><li>`height/16` Sektionen</li><li>Heightmaps (Rohdaten wie gesendet)</li><li>Block-Entities, Schlüssel lokal `(y−min_y)<<8 \| z<<4 \| x`</li><li>Himmels- und Blocklicht für `sections + 2` Lichtsektionen</li></ul> |
| `BlockEntity` | Record mit absoluter Position, Typ-ID, Typname und NBT. Die NBT-Map ist leer, wenn der Server kein NBT sendet. |
| `ChunkDecoder` | Dekodiert Sektionen, Heightmaps, Block-Entities und Licht. Dekodiert außerdem `chunks_biomes`-Puffer. |
| `WorldState` | Hält die geladenen Chunks (höchstens 4096) und wendet alle Weltpakete an. Bietet Abfragen und Zähler (`Stats`). |
| `cli.WorldCommands` | Erzeugt die Textausgabe der Terminalbefehle. Reine Funktionen über `WorldState`. |

## 3. Datenmodell und Koordinaten

**Sektionen**
- Anzahl `height >> 4`: Overworld 384 → 24, Nether und End 256 → 16.
- Die Anzahl wird **nie** fest kodiert. Der `ChunkDecoder` liest genau so viele Container, wie die aktuelle Dimension vorgibt.

**Blockzugriff `WorldState#block(x, y, z)`**
- Der Chunk ergibt sich aus `floorDiv(x,16)` und `floorDiv(z,16)`.
  - Ist der Chunk nicht geladen, ist das Ergebnis leer (`Optional.empty()`).
- Liegt y außerhalb von `[min_y, min_y+height)`, ist das Ergebnis `minecraft:void_air`. Das entspricht `Level#getBlockState`.
- Sonst wird zuerst die Sektion bestimmt: `(y − min_y) >> 4`.
- Danach die lokale Position `x&15, y&15, z&15`.
- Der Index ist `(y·16 + z)·16 + x`.
- Bei negativen Koordinaten sind `floorDiv` und `&15` korrekt: x = −1 ergibt Chunk −1 und lokal 15. Die Tests in §9 decken das ab.

**Biome**
- Biomzelle `(lokal >> 2)` je Achse, Index `(qy·4 + qz)·4 + qx`.
- Namen kommen aus der in der Konfiguration empfangenen Registry `minecraft:worldgen/biome`. Die ID ist die Position in der Registry.
- Die Anzahl der Einträge bestimmt die globalen Bits der Biom-Palette.

**Licht**
- Lichtsektion i entspricht Sektions-Y `minSectionY − 1 + i`.
- Ein Eintrag hat einen von drei Zuständen:

  | Zustand | Bedeutung |
  |---|---|
  | `null` | unbekannt, `light()` liefert −1 |
  | `EMPTY_LIGHT` | als leer gemeldet, alle Werte 0 |
  | 2048-Byte-Nibble-Array | gesendete Lichtwerte |

## 4. Paketverarbeitung (`ClientSession#handle`)

| Paket | Wirkung |
|---|---|
| `registry_data` `minecraft:dimension_type` | `DimensionInfo` je Typ. Ohne NBT gibt es keine Weltverwaltung, daher werden Known Packs standardmäßig abgelehnt. |
| `registry_data` `minecraft:worldgen/biome` | Speichert die Biomnamen in Registry-Reihenfolge. |
| `login` | `enterDimension`: neuer `TerrainCache` und neuer `WorldState`. |
| `respawn` (neu dekodiert) | Bei anderem Dimensionsschlüssel wird die Welt verworfen und neu angelegt, wie bei `handleRespawn`. Gleiche Dimension: Die Welt bleibt erhalten. |
| `level_chunk_with_light` | `WorldState#loadChunk`: Dekodierung, Restbyte-Prüfung, Abgleich des Nicht-Luft-Zählers, Ersetzen eines vorhandenen Chunks. Zusätzlich erhält der `TerrainCache` die Heightmaps. |
| `forget_level_chunk` | Entfernt den Chunk aus `WorldState` und `TerrainCache`. |
| `block_update` | `setBlock` (siehe §5). |
| `section_blocks_update` | Wendet jeden Eintrag einzeln an. Die Sektion muss in der Welthöhe liegen. |
| `light_update` | Siehe §5. |
| `block_entity_data` | Typ prüfen, dann Block-Entity einsetzen oder ersetzen. |
| `chunks_biomes` | Biom-Container dekodieren (ohne Restbytes) und die Biome aller Sektionen ersetzen. |

Unbekannte oder ungültige Daten werden nicht still verworfen. Sie lösen eine `ProtocolException` aus, und die Sitzung endet mit Fehler. Bewusst ignorierte Fälle werden gezählt (`updatesForUnloadedChunks`, `unloadsOfUnknownChunks`).

## 5. Konsistenzregeln und bewusste Vereinfachungen

**`block_update` für ungeladene Chunks:** wird ignoriert und gezählt. Vanilla verhält sich gleich (`ClientLevel#setServerVerifiedBlockState` auf einem fehlenden Chunk bewirkt nichts).

**Nicht-Luft-Zähler:** wird beim Setzen nachgeführt, wie in `LevelChunkSection#setBlockState`. Beim Laden wird er mit den dekodierten Daten verglichen. Eine Abweichung wird gezählt (`nonEmptyCountMismatches`), nicht korrigiert. Laufzeit: 0 Abweichungen, siehe docs/20.

**`fluidCount`:** bleibt der gesendete Wert. Fluidzustände (z. B. `waterlogged`) werden nicht modelliert.

**Heightmaps:** bleiben der Stand beim Laden. Blockupdates rechnen sie nicht nach. Vanilla aktualisiert sie lokal; das ist für M4 ohne Physik nicht nötig.

**Block-Entities:**
- Ändert sich der *Block* (nicht nur der Zustand), wird die Block-Entity an dieser Stelle entfernt. Das entspricht `LevelChunk#setBlockState`.
- Eine neue Block-Entity entsteht erst durch `block_entity_data` oder durch ein neues Chunk-Paket.
- Der eigene Client kennt keine Zuordnung Block → Block-Entity-Typ. Er legt deshalb keine leeren Block-Entities an.

**`light_update` für ungeladene Chunks:**
- Vanilla kann Licht vor dem Chunk erhalten. Dieser Client verwirft solche Daten und zählt sie.
- Beim Laden kommen die Lichtdaten ohnehin mit dem Chunk-Paket.

**Licht-Masken:**
- Ein Bit, das in Daten- und Leer-Maske zugleich gesetzt ist, gilt als ungültig. Der Server erzeugt die Masken exklusiv (`prepareSectionData`).
- Bits ab `sections + 2` sind ein Fehler.

## 6. Validierung und Grenzen

| Prüfung | Ort |
|---|---|
| Sektionspuffer ≤ 2 MiB, Block-Entities ≤ 65 536, Licht-Arrays ≤ 256+2 je Liste, Biom-Chunks ≤ 4096 | `ClientboundDecoder` |
| Heightmap-Typ 0–5, keine Duplikate, Länge ≤ Rest/8 | `ClientboundDecoder` |
| Heightmap-Länge = `longsFor(ceillog2(height+1), 256)`, Werte ≤ `height` | `ChunkDecoder` |
| Licht: Arrays = Maskenbits, je genau 2048 Byte, Bits < `sections+2`, Masken disjunkt | `ClientboundDecoder`, `ChunkDecoder` |
| Sektion: `nonEmpty` und `fluid` in 0…4096 | `ChunkDecoder` |
| Palette: Größe 1…Kapazität (2^bits), jede ID < Größe der globalen Tabelle, jeder Datenwert < Palettengröße bzw. < globale Größe | `PalettedContainer` |
| Long-Array-Länge aus `bitsInMemory`, nicht aus den gesendeten Bits; ausreichend Restbytes vor der Allokation | `PalettedContainer` |
| Puffer exakt aufgebraucht (keine Restbytes) | `ChunkDecoder` (Sektionen, Biome), `ClientboundDecoder` (Paket) |
| Block-Entity: Typ bekannt, y in Welthöhe | `ChunkDecoder`, `WorldState` |
| Blockupdates: Zustands-ID gültig, y bzw. Sektions-y in Welthöhe | `WorldState` |
| höchstens 4096 geladene Chunks | `WorldState`, `TerrainCache` |

Ein fehlerhafter Chunk wird nicht teilweise übernommen. Der neue `Chunk` wird erst nach vollständiger Dekodierung eingetragen.

## 7. Terminalbefehle

| Befehl | Ausgabe |
|---|---|
| `block <x> <y> <z>` | Zustand mit Eigenschaften und ID, Chunk, Sektions-y und Index, lokale Position, Biom, Himmels- und Blocklicht, Block-Entity. Koordinaten absolut oder relativ (`~`, `~n`). |
| `chunk [<cx> <cz>]` | Blockgrenzen, Sektionsanzahl und unterste Höhe, Puffergröße, Heightmaps an (0,0), Lichtsektionen mit Daten. Je Sektion: Nicht-Luft- und Fluidzähler, Palettentyp, Bits, Palettengröße, Biome. Ohne Argument: eigener Chunk. |
| `world` | Dimension mit `min_y`, Höhe und Sektionen, Tabellengrößen, Zahl der geladenen Chunks und alle `Stats`-Zähler, darunter Restbyte-Prüfungen und Palettenstatistik. |
| `nearby [r]` | Alle Nicht-Luft-Blöcke im Würfel mit Radius r um die eigene Blockposition, nach Abstand sortiert. Dazu der Block unter den Füßen. r höchstens 8. |
| `blocks [<cx> <cz>]` | Häufigkeit der Blockarten in einem Chunk (Top 25) und Anzahl der verschiedenen Zustände. |

Die bisherigen Befehle bleiben unverändert: `position`, `move`, `look`, `status`, `compression`, `wait`, `claim`, `help`, `quit`.

## 8. Nebenläufigkeit

- Alle öffentlichen Methoden von `WorldState` sind `synchronized`.
- Der Netzwerk-Thread verändert Chunks nur innerhalb dieser Methoden.
- Der Terminal-Thread führt jeden Weltbefehl vollständig unter derselben Sperre aus (`synchronized (world)` in `TerminalController#print`). Er sieht dadurch nie einen halb angewendeten `section_blocks_update`.
- `ClientSession.world` ist `volatile`. Ein Dimensionswechsel ersetzt die Referenz atomar.

## 9. Tests

| Testklasse | Inhalt |
|---|---|
| `PalettedContainerTest` | <ul><li>Long-Anzahlen (4/5/9/16 Bit, Biome 1/3/7)</li><li>Roundtrip für 1–32 Bit, keine Werte über Long-Grenzen</li><li>Indexreihenfolge</li><li>Singleton; Linear mit 1–4 gesendeten Bits (immer 4 im Speicher); HashMap 5–8; Global ab 9 (16 Bit); Biome linear 1–3 und global ab 4</li><li>Ungültige Fälle: Palettenindex, unbekannte IDs, Palette zu groß oder leer, abgeschnittene Daten</li></ul> |
| `WorldStateTest` | <ul><li>Registry gegen den Report</li><li>Block- und Sektionspositionen mit negativen und extremen Werten</li><li>Overworld (24 Sektionen, y −64 und 319, `void_air` außerhalb), Nether (16 Sektionen), Restbytes bei falscher Sektionszahl</li><li>Negative Chunks und Chunkgrenzen; alle Palettentypen in einem Chunk</li><li>Zählerabweichung; abgeschnittene und überlange Puffer; Heightmaps</li><li>Licht-Masken (unter und über der Welt, leer, unbekannt, `light_update`); ungültiges Licht</li><li>`block_update` und `section_blocks_update` mit negativen Koordinaten; Ent- und Neuladen</li><li>Block-Entities aus dem Chunk-Paket und aus `block_entity_data` sowie deren Entfernung; `chunks_biomes`</li><li>Terminalausgaben</li></ul> |
| `ClientSessionTest` | Bewegungstest jetzt mit vollständigen Chunk-Paketen und Biom-Registry. Prüft das Weltmodell in der Sitzung. |
| `MovementPacketTest` | Chunk-Paket mit vollständiger Struktur; Restbytes werden abgelehnt. |
| `WorldIntegrationTest` | Abgleich gegen Vanilla (docs/20). |

Die Testpakete erzeugt `ChunkTestSupport`. Das ist ein eigenständiger Encoder nach docs/18, unabhängig vom Decoder. Dekodiert wird immer über den echten Pfad `ClientboundDecoder` → `WorldState`.

## 10. Grenzen von M4 und Anschluss an M5

Nicht modelliert:
- Fluidzustände
- Lokale Neuberechnung von Heightmaps und Licht
- Blockformen und Kollisionsboxen
- Entities

Die Bewegung (M2) nutzt weiterhin den `TerrainCache` (MOTION_BLOCKING). Mit dem jetzt vorhandenen, serverbestätigten Blockmodell kann M5 die Bodenhöhe und Kollisionen aus echten Blockzuständen bestimmen. Dafür braucht M5 zusätzlich eine Tabelle Zustand → Kollisionsform.
