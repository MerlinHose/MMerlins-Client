# 18 – Chunk-Spezifikation (M4)

Stand: 2026-11-07 · Protokoll 777 / Minecraft 26.3 · Quellen relativ zu `analysis/mc-src/net/minecraft/` · IDs aus `packets.json` (offizieller Datengenerator)
Alle Zahlen big-endian. Diese Spezifikation beschreibt **Formate**; die Implementierung in `client/` ist eigenständig ([19](19-weltarchitektur.md)).

## 1. Pakete der Weltverwaltung (PLAY, S→C)

| Paket | ID | Inhalt | Vanilla-Lauf docs/10 (Lauf 1, ~30 s) |
|---|---|---|---:|
| `level_chunk_with_light` | 0x2E | Chunk + Licht (§2–§5) | 913 |
| `forget_level_chunk` | 0x26 | ChunkPos Long | 440 |
| `block_update` | 0x08 | BlockPos Long + Zustand VarInt | 2 717 |
| `section_blocks_update` | 0x56 | SectionPos Long + Liste (Zustand ≪ 12 \| lokale Pos) | 4 761 |
| `light_update` | 0x31 | VarInt x, VarInt z + Lichtdaten (§5) | 626 |
| `block_entity_data` | 0x06 | BlockPos, Typ VarInt, NBT | 5 |
| `chunks_biomes` | 0x0D | Liste (ChunkPos, ByteArray Biomdaten) | 0 |
| `set_chunk_cache_center` | 0x60 | VarInt x, VarInt z | (nicht ausgewertet) |

## 2. `level_chunk_with_light`

`network/protocol/game/ClientboundLevelChunkWithLightPacket.java`:

| Feld | Typ | Quelle |
|---|---|---|
| chunkX | Int | `ByteBufCodecs.INT` |
| chunkZ | Int | |
| chunkData | `ClientboundLevelChunkPacketData` (§2.1) | |
| lightData | `ClientboundLightUpdatePacketData` (§5) | |

### 2.1 `ClientboundLevelChunkPacketData` (`network/protocol/game/ClientboundLevelChunkPacketData.java`)

| Feld | Typ | Grenzen | Quelle |
|---|---|---|---|
| heightmaps | Map(VarInt Typ-ID → LongArray) | Typ 0–5 (`Heightmap.Types`); gesendet werden nur Typen mit `Usage.CLIENT`: `WORLD_SURFACE` (1), `MOTION_BLOCKING` (4) (`Heightmap.java:177`); LongArray-Länge ≤ Restbytes/8 (`FriendlyByteBuf#readLongArray :270`) | `HEIGHTMAPS_STREAM_CODEC :2` |
| buffer | ByteArray(2 097 152) | enthält **genau** alle Sektionen hintereinander; Server prüft vollständige Füllung (`extractChunkData`, „Didn't fill chunk buffer“) | `:7`, `ByteBufCodecs.byteArray :278` |
| blockEntities | List(BlockEntityInfo) | – | `:13` |

**BlockEntityInfo** (`:91-98`):

| Feld | Typ | Bedeutung |
|---|---|---|
| packedXZ | Byte | `(localX << 4) \| localZ` (`create`: `sectionRelative(x) << 4 \| sectionRelative(z)`); Auswertung: x = `packedXZ >> 4 & 15`, z = `packedXZ & 15` (`forEachBlockEntityTag`) |
| y | Short | **absolute** Welt-Y |
| type | VarInt | Registry-ID in `minecraft:block_entity_type` (eingebaute Registry; `ByteBufCodecs.registry`) |
| tag | Netzwerk-NBT, Typ 0 = kein Tag | `ByteBufCodecs.OPTIONAL_COMPOUND_TAG :189` (Server sendet leer, wenn `getUpdateTag` leer ist) |

## 3. Sektionen

### 3.1 Anzahl und Lage

- Anzahl Sektionen = `height / 16` der Dimension (`LevelHeightAccessor#getSectionsCount`). `min_y` und `height` stehen im Registry-Eintrag `minecraft:dimension_type` (docs/15 §4).
- Sektion i (0-basiert im Puffer) umfasst Welt-Y `min_y + 16·i … min_y + 16·i + 15`; Sektionskoordinate `sectionY = (min_y >> 4) + i`.
- Überwelt 26.3: `min_y = −64`, `height = 384` → 24 Sektionen. Der Client berechnet die Anzahl **immer** aus der empfangenen Dimension.

### 3.2 `LevelChunkSection#write` (`world/level/chunk/LevelChunkSection.java:177-185`)

| Feld | Typ | Bedeutung |
|---|---|---|
| nonEmptyBlockCount | Short | Anzahl Nicht-Luft-Blöcke (0–4096) |
| fluidCount | Short | Anzahl Blöcke mit Fluid (0–4096) – **neu gegenüber älteren Versionen** |
| states | PalettedContainer (Blockzustände, 16³ = 4096 Einträge) | §4 |
| biomes | PalettedContainer (Biome, 4³ = 64 Einträge) | §4 |

## 4. `PalettedContainer` (`world/level/chunk/PalettedContainer.java`, `Strategy.java`, `Configuration.java`)

### 4.1 Format (`read :153-165`, `Data#write :342-346`)

```
bits        Byte                    (gesendet wird storage.getBits() = bitsInMemory der Konfiguration)
palette     abhängig vom Palettentyp (§4.3)
data        Long[n] OHNE Längenpräfix (readFixedSizeLongArray); n aus Konfiguration und Eintragszahl
```

### 4.2 Konfiguration je Bitbreite (`Strategy#createForBlockStates/ForBiomes`)

| gesendete Bits | Blockzustände (4096 Einträge, 4 Bit/Achse) | Biome (64 Einträge, 2 Bit/Achse) |
|---|---|---|
| 0 | Singleton, 0 Bit Speicher, **n = 0** | Singleton, n = 0 |
| 1 | **Linear mit 4 Bit** | Linear 1 Bit |
| 2 | **Linear mit 4 Bit** | Linear 2 Bit |
| 3 | **Linear mit 4 Bit** | Linear 3 Bit |
| 4 | Linear 4 Bit | **Global** |
| 5–8 | HashMap mit 5–8 Bit | Global |
| ≥ 9 | Global | Global |

Bei *Global* gilt `bitsInMemory = ceillog2(Größe der globalen Tabelle)` (`Strategy :21`, `Configuration.Global`). Die gesendete Zahl ist nur `bitsInStorage`; die Arraylänge folgt aus `bitsInMemory`.
- Blockzustände 26.3: 35 723 Zustände → **16 Bit**.
- Biome: Größe der synchronisierten Registry `minecraft:worldgen/biome`.

Wichtig: Für Blockzustände ergeben 1–3 gesendete Bits **4 Bit** pro Eintrag im Array. Die Arraylänge hängt nie von der gesendeten Zahl ab, sondern von der Konfiguration.

### 4.3 Palettentypen (`SingleValuePalette`, `LinearPalette`, `HashMapPalette`, `GlobalPalette`)

| Typ | Palettenfeld | Bedeutung des Werts im Array |
|---|---|---|
| Singleton | VarInt globale ID | alle Einträge = diese ID (kein Array) |
| Linear | VarInt Größe, dann Größe × VarInt globale ID | Index in die Palette |
| HashMap | VarInt Größe, dann Größe × VarInt globale ID | Index in die Palette |
| Global | (nichts) | globale ID direkt |

Vanilla prüft globale IDs mit `byIdOrThrow`. Die Palettengröße darf die Kapazität 2^bits nicht überschreiten; das Linear-Array hat feste Größe `1 << bits`.

### 4.4 Bitpacking (`util/SimpleBitStorage.java:250-292`)

- `valuesPerLong = 64 / bits`, n = ⌈entries / valuesPerLong⌉, Werte **nicht** über Long-Grenzen.
- Eintrag i: Long `i / valuesPerLong`, Bitoffset `(i mod valuesPerLong) · bits`, Maske `2^bits − 1`.
- Beispiele: Blockzustände 4 Bit → 16 Werte/Long → 256 Longs; 5 Bit → 12 → 342 Longs; 16 Bit (global) → 4 → 1 024 Longs. Biome 1 Bit → 64 → 1 Long; 3 Bit → 21 → 4 Longs; 7 Bit → 9 → 8 Longs.

### 4.5 Index

`Strategy#getIndex`: `index = (y << b | z) << b | x` mit b = 4 (Blöcke, lokal 0–15) bzw. b = 2 (Biome, Viertelblöcke 0–3: `blockLocal >> 2`).

## 5. Lichtdaten (`ClientboundLightUpdatePacketData`, auch in `light_update`)

| Feld | Typ | Bedeutung |
|---|---|---|
| skyYMask | BitSet | Bit i: Himmelslicht für Lichtsektion i enthalten |
| blockYMask | BitSet | Bit i: Blocklicht enthalten |
| emptySkyYMask | BitSet | Bit i: Himmelslicht der Sektion ist komplett 0 |
| emptyBlockYMask | BitSet | dto. Blocklicht |
| skyUpdates | List(ByteArray ≤ 2048) | ein Array je gesetztem Bit in skyYMask, aufsteigend |
| blockUpdates | List(ByteArray ≤ 2048) | dto. blockYMask |

- `BitSet` = `ByteArray` → `BitSet.valueOf(bytes)` (`ByteBufCodecs.BIT_SET :175`; **Byte-**, nicht Long-Array).
- Lichtsektionen: `sections + 2`, beginnend bei `minSectionY − 1` (`LevelLightEngine :157-163`). Bit i ↔ Sektion `minSectionY − 1 + i`.
- Ein Datenarray ist eine Nibble-Tabelle mit 4096 Einträgen: Index `y << 8 | z << 4 | x`, Byte `index >> 1`, unteres Nibble bei geradem Index (`DataLayer :40-60`). Vanilla verlangt genau 2048 Bytes (`DataLayer.SIZE`).
- `light_update` (0x31): VarInt chunkX, VarInt chunkZ, dann dieselbe Struktur (`ClientboundLightUpdatePacket :14-19`).

## 6. Blockänderungen

| Paket | Felder | Quelle |
|---|---|---|
| `block_update` 0x08 | pos BlockPos Long, state VarInt (globale Blockzustands-ID) | `ClientboundBlockUpdatePacket :14-19`, `ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY)` |
| `section_blocks_update` 0x56 | section SectionPos Long, count VarInt, count × VarLong `(stateId << 12) \| localPos` | `ClientboundSectionBlocksUpdatePacket :40-51` |

Positionen:

| Kodierung | Layout | Quelle |
|---|---|---|
| BlockPos | x 26 Bit (Offset 38), z 26 Bit (Offset 12), y 12 Bit (Offset 0), jeweils vorzeichenbehaftet | `core/BlockPos.java:47-55` |
| SectionPos | x 22 Bit (Offset 42), z 22 Bit (Offset 20), y 20 Bit (Offset 0), vorzeichenbehaftet | `core/SectionPos.java:23-35` |
| lokale Pos (Short) | x = `>>> 8 & 15`, z = `>>> 4 & 15`, y = `& 15` | `SectionPos#sectionRelativeX/Y/Z :100-110` |
| ChunkPos (Long) | x untere 32 Bit, z obere 32 Bit | `world/level/ChunkPos.java:73-86` |

`block_entity_data` 0x06: pos BlockPos, type VarInt, tag **Trusted-Compound-NBT** (`ClientboundBlockEntityDataPacket :17-24`).
`chunks_biomes` 0x0D: List(ChunkPos Long, ByteArray(2 MiB) mit je einem Biom-`PalettedContainer` pro Sektion).

## 7. Koordinaten

- Chunk = `floor(x / 16)` = `x >> 4`; lokal = `x & 15` (auch negativ korrekt: x = −1 → Chunk −1, lokal 15).
- Sektionsindex = `(y − min_y) >> 4`, lokales y = `(y − min_y) & 15`; außerhalb `[min_y, min_y + height)` gibt es keinen Block (Luft bzw. „außerhalb“).

## 8. Zuordnung Blockzustands-ID → Name und Eigenschaften

- Netzwerk-IDs sind Indizes in `Block.BLOCK_STATE_REGISTRY` (`Block.java:82`), **nicht** Teil der Registry-Synchronisation.
- Der offizielle Report `blocks.json` schreibt je Zustand `"id": Block.getId(state)` (`data/info/BlockListReport.java:68`) – also exakt die Netzwerk-ID.
- 26.3: 1 286 Blöcke, **35 723 Zustände**, IDs lückenlos 0–35 722. Beispiele: `minecraft:air` = 0, `minecraft:stone` = 1, `oak_stairs[facing=north,half=top,shape=straight,waterlogged=true]` = 5463.
- Block-Entity-Typen: `registries.json` → `minecraft:block_entity_type` (49 Einträge, `protocol_id` = `registry.getId`, `RegistryDumpReport :45`).
- Biom-IDs: Reihenfolge der Einträge in `registry_data` für `minecraft:worldgen/biome`.

Der Client bündelt aus diesen Reports reine Faktendaten (ID ↔ Name/Eigenschaften), keinen Code.

## 9. Lebenszyklus

| Ereignis | Vanilla-Client | Eigener Client |
|---|---|---|
| `level_chunk_with_light` | Chunk ersetzen (`replaceWithPacketData`) + Licht | Chunk neu anlegen/ersetzen |
| `forget_level_chunk` | Chunk entfernen | entfernen |
| `block_update` / `section_blocks_update` | Block setzen (nur in geladenem Chunk) | dto.; ungeladen → zählen, ignorieren |
| `light_update` | Licht aktualisieren | Licht des Chunks aktualisieren (falls geladen) |
| `respawn` (Dimensionswechsel) | Welt neu | Welt verwerfen (hier nicht getestet) |
