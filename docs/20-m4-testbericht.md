# 20 – Testbericht M4: Chunk-Dekodierung und eigene Weltverwaltung

**Datum:** 2026-11-07

**Testumgebung:**
- Vanilla-Server 26.3 auf 127.0.0.1:25565.
- `online-mode=false`, `view-distance` wie bisher.
- Eigener Client mit View-Distance 2. Known Packs werden abgelehnt (Standard).
- Rohdaten liegen in `analysis/runtime-logs/m4-threshold-{256,-1,0}/`, `m4-terminal-*/` und `m2-m4-*/` (gitignored).

**Spezifikation und Architektur:** docs/18 und docs/19.

## 1. Testverfahren

Alle Läufe verwenden `analysis/tools/m4-run.sh <threshold>`:
1. Schwellenwert setzen.
2. Server starten.
3. Netzwerkmonitor starten.
4. Alle Unit- und Integrationstests ausführen.
5. M2-Terminaltest ausführen.
6. M4-Terminaltest ausführen.
7. Server stoppen.

Reihenfolge der Schwellenwerte: **256** zuerst, danach **−1** und **0**.

**Referenz ist der Server.** Für jede geprüfte Position stellt der Client seinen dekodierten Zustand bereit. Dieser Zustand wird als vollständiges Prädikat mit allen Eigenschaften an die Serverkonsole übergeben:

```
execute if block <x> <y> <z> minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]
```

Der Server antwortet mit `Test passed` oder `Test failed`. Die Texte stammen aus `commands.execute.conditional.pass/fail` in der `en_us.json` des Server-JARs. Eine Übereinstimmung heißt also: Blockart **und** jede Eigenschaft sind identisch.

Eine Gegenprobe mit absichtlich falschen Zuständen zeigt, dass die Prüfung tatsächlich unterscheidet.

### 1.1 `WorldIntegrationTest` (JUnit, Tag `integration`)

1. **Spieler vorbereiten:** Spieler `ProtoBotM4` beitreten lassen, `gamemode creative` (kein Floating-Kick), `forceload add -48 -48 47 47`, `tp 0.5 205 0.5`. Warten, bis die Chunks −2…1 × −2…1 im Client geladen sind.
2. **Originalzustände merken:** Der Client liest alle Zielpositionen, damit sie später zurückgesetzt werden können.
3. **Änderungen ausführen:**
   - Mehrfachänderungen (`section_blocks_update`):
     - `fill -3 200 -3 2 200 2 stone`: 36 Blöcke über die 4 Chunks um den Ursprung.
     - `fill 8 210 8 11 211 11 oak_planks`: 32 Blöcke in einer Sektion.
   - Einzeländerungen (`block_update`, teils vom Server zu Sektionsupdates zusammengefasst):

     | Position | Zustand | Fall |
     |---|---|---|
     | −1 201 −1 | `oak_stairs[facing=east,half=top,…]` | Treppe, negative X/Z, Chunk −1/−1 |
     | 0 201 0 | `oak_slab[type=top]` | Stufe oben, Chunkgrenze |
     | 1 201 0 | `oak_slab[type=bottom]` | Stufe unten |
     | 15 201 15 / 16 201 16 | `dirt` / `glass` | lokale Ecke 15,15 und Nachbarchunk 1/1 |
     | −16 202 −16 / −17 202 −17 | `stone` / `dirt` | lokal 0,0 in Chunk −1/−1 bzw. 15,15 in Chunk −2/−2 |
     | 5 −64 5 | `stone` (ersetzt Grundgestein) | unterste Ebene |
     | 5 319 5 | `glass` | oberste Ebene |
     | 5 −1 5 / 5 0 5 | `oak_planks` / `glass` | Sektionsgrenze y −1 ↔ 0 (negative Sektion) |
     | 5 15 5 / 5 16 5 | `oak_planks` / `glass` | Sektionsgrenze y 15 ↔ 16 |
4. **Abgleich:** Alle 81 geänderten Positionen plus eine Stichprobe des natürlichen Terrains prüfen. Die Stichprobe umfasst x ∈ {−17, −16, −1, 0, 15, 16}, z ∈ {−17, −1, 0, 16} und 24 Höhen von −64 bis 319, also 576 Positionen. Zusammen 657 Prüfungen.
5. **Gegenprobe:** 3 falsche Zustände; erwartet wird jeweils `Test failed`.
6. **Entladen:** `tp 3000.5 250 3000.5`. Warten, bis die Chunks am Ursprung entladen und die Chunks um 187/187 geladen sind. Alle 81 Positionen müssen im Client „nicht geladen“ sein.
7. **Neuladen:** `tp` zurück. Die neu gesendeten Chunks müssen die Änderungen enthalten. Die 81 Positionen werden erneut serverseitig geprüft.
8. **Zurücksetzen:** Jede Position erhält ihren Originalzustand; Abgleich der 81 Positionen. Danach `tp` zur Startposition, `gamemode survival`, `forceload remove all`.
9. **Strukturprüfungen über den Lauf:**
   - Restbyte-Prüfungen = geladene Chunks.
   - Sektionen = 24 × Chunks.
   - Abweichungen im Nicht-Luft-Zähler = 0.
   - Kein Fehler, kein Server-Disconnect.

### 1.2 Terminaltest `m4-terminal.sh`

Der eigene Client läuft mit `--interactive` und führt diese Befehle aus: `world`, `position`, `block ~ ~-1 ~`, `block ~ ~ ~`, `block ~3 ~-2 ~-5`, `block ~ -64 ~`, `block ~ 319 ~`, `block ~ 320 ~`, `nearby 1`, `chunk`, `blocks`, `status`, `quit`.

Jede `block`-Antwort innerhalb der Welthöhe wird sofort per `execute if block` gegengeprüft.

## 2. Ergebnisse

### 2.1 Blockabgleich (`WorldIntegrationTest`)

| | Threshold 256 | Threshold −1 | Threshold 0 |
|---|---|---|---|
| Abgleich nach Änderungen (81 geändert + 576 Terrain) | **657/657** | **657/657** | **657/657** |
| Gegenprobe (3 falsche Zustände) | 3 × `Test failed` | 3 × `Test failed` | 3 × `Test failed` |
| Updates direkt nach den Änderungen | 6 `block_update`, 6 `section_blocks_update` (75 Einträge) | 7 / 6 (75) | 6 / 6 (75) |
| Entladen nach Teleport | 49 Chunks | 49 | 49 |
| Abgleich nach Neuladen | **81/81** | **81/81** | **81/81** |
| Abgleich nach Zurücksetzen | **81/81** | **81/81** | **81/81** |
| Chunk-Pakete dekodiert | 146 | 147 | 147 |
| davon ohne Restbytes | 146 | 147 | 147 |
| Sektionen dekodiert (= 24 × Chunks) | 3 504 | 3 528 | 3 528 |
| Sektionspuffer gesamt | 3 152 382 B | 3 153 355 B | 3 155 294 B |
| Abweichungen `nonEmptyBlockCount` | **0** | **0** | **0** |
| `block_update` / `section_blocks_update` (Einträge), gesamter Lauf | 22 / 26 (229) | 22 / 26 (200) | 22 / 24 (183) |
| `light_update` | 27 | 20 | 31 |
| Updates für ungeladene Chunks | 0 | 0 | 0 |
| Blockpaletten (Singleton/Linear/HashMap/Global) | 2114 / 1087 / 303 / 0 | 2129 / 1106 / 293 / 0 | 2129 / 1104 / 295 / 0 |
| Biompaletten (Singleton/Linear/Global) | 1616 / 1888 / 0 | 2432 / 1096 / 0 | 2432 / 1096 / 0 |

**Beobachtungen:**
- Von den 75 Einträgen der Sektionsupdates nach den Änderungen stammen 68 aus den beiden `fill`-Befehlen (36 + 32).
- Der Rest sind Einzel-`setblock`s. Der Server hat sie zusammengefasst, weil sie im selben Tick in derselben Sektion lagen: 0/1/15 201 in Sektion 12 von Chunk 0/0, zusammen mit der Plattform.
- Eine Annahme „ein `setblock` = ein `block_update`“ wäre falsch. Der Test prüft deshalb die Summe der Einträge, nicht die Paketart.

**Sektionsgrenzen in der Stichprobe:** Die Stichprobe enthält y = −64, −63, −1, 0, 63, 64 und 319. Damit sind alle Fälle abgedeckt:
- die unterste und die oberste Sektion,
- die negative Sektion −1 und die Sektion 0,
- Positionen an und neben jeder Chunkgrenze bei x/z = −17, −16, −1, 0, 15, 16.

Eine Auswahl aus `m4-world-report.txt` (Threshold 256):

```
initial  -1 201 -1    client=minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false] server=Test passed
initial  0 201 0      client=minecraft:oak_slab[type=top,waterlogged=false]                         server=Test passed
initial  1 201 0      client=minecraft:oak_slab[type=bottom,waterlogged=false]                      server=Test passed
initial  -17 -64 -17  client=minecraft:bedrock                                                      server=Test passed
initial  -17 -63 -17  client=minecraft:deepslate[axis=y]                                            server=Test passed
initial  16 65 -1     client=minecraft:leaf_litter[facing=west,segment_amount=3]                    server=Test passed
initial  16 66 -1     client=minecraft:oak_leaves[distance=2,persistent=false,waterlogged=false]    server=Test passed
```

### 2.2 Terminaltest (Threshold 256; −1 und 0 identisch)

```
> world
  dimension minecraft:overworld (minecraft:overworld) min_y=-64 height=384 sections=24 | block states 35723 | biomes 67 | loaded chunks 49
    chunks loaded 49 (replaced 0) unloaded 0 (unknown 0) | sections 1176 | section bytes 1114704 | trailing-byte checks 49 | nonAir mismatches 0
> block ~ ~-1 ~
  block 1 68 7 = minecraft:oak_leaves[distance=3,persistent=false,waterlogged=false] (state id 278)
    chunk 0,0 section y=4 (index 8) local 1,4,7 | biome minecraft:forest | sky light 14 block light 0
    SERVER execute if block 1 68 7 minecraft:oak_leaves[distance=3,persistent=false,waterlogged=false] -> Test passed
> block ~ -64 ~
  block 1 -64 7 = minecraft:bedrock (state id 88)          → SERVER: Test passed
> block ~ 319 ~
  block 1 319 7 = minecraft:air (state id 0)               → SERVER: Test passed
> block ~ 320 ~
  block 1 320 7 = minecraft:void_air (outside world height -64..319)
> chunk
  chunk 0,0 blocks x 0..15 z 0..15 | 24 sections from y=-64 | section data 21693 bytes | 0 block entities
    [ 4] y=    0 nonAir=4050 fluid=   0 blocks: HASHMAP bits=5 palette=20    biomes: minecraft:dripstone_caves
    [ 8] y=   64 nonAir= 606 fluid=   0 blocks: HASHMAP bits=5 palette=31    biomes: minecraft:forest
    [ 9] y=   80 nonAir=   0 fluid=   0 blocks: all minecraft:air            biomes: minecraft:forest
> blocks
  chunk 0,0: 98304 blocks, 40 block types, 65 distinct states
      66345 minecraft:air
      14428 minecraft:deepslate
```

Alle 5 serverseitig geprüften `block`-Ausgaben ergeben bei jedem Schwellenwert `Test passed`. Die Biom-Registry hat in dieser Welt 67 Einträge, also 7 globale Bits.

### 2.3 Regression aller bisherigen Tests

| | 256 | −1 | 0 |
|---|---|---|---|
| JUnit gesamt (188 Unit + 4 Integration) | 192/192 | 192/192 | 192/192 |
| `statusQuery` | ✓ | ✓ | ✓ |
| `stableSixtySecondsInPlayAndControlledDisconnect` (71 s Play, Keep-Alive ≤ 16 s, Kompressionszähler) | ✓ | ✓ | ✓ |
| `movementAndRotationAcceptedByServer` (`/data get` Pos/Rotation) | ✓ | ✓ | ✓ |
| `WorldIntegrationTest` | ✓ | ✓ | ✓ |
| M2-Terminaltest (`move`, `look`, `claim`, Server-Korrekturen) | wie docs/17 | wie docs/17 | **korrigiert (docs/23 §4):** `ProtoBotM3` war seit dem Lauf bei −1 tot (Skelett) und erhielt bei 0 keine Chunks; Bewegung ohne Weltdaten |
| Kompression (Terminal, eingehend) | 102 von 2186 Frames komprimiert | 0 von 2367 | alle bis auf `login_compression` |
| Unit-Tests nach Rückstellung auf −1 | 188/188 | | |

**Erwartete Serverlogs:**
- Der einzige WARN-Eintrag ist das absichtliche `moved too quickly! 15.0,0.0,0.0` aus dem `claim`-Test.
- Dazu kommen die bekannten, absichtlich fehlschlagenden Discovery-Abrufe auf `127.0.0.1:9`.
- Keine Kicks, keine Fehler.

**Netzwerkmonitor:** in allen drei Läufen nur Loopback-Verbindungen, **0 externe Verbindungen**.

## 3. Unit-Tests (Phase F)

**Neue Testklassen:**
- `PalettedContainerTest`: 39 Testfälle einschließlich Parametrisierungen.
- `WorldStateTest`: 18 Testfälle.

**Angepasst:**
- `ClientSessionTest`: Bewegungstest mit echten Chunk-Paketen.
- `MovementPacketTest`: vollständige Chunk-Struktur, Restbytes werden abgelehnt.

Die Unit-Tests wachsen damit von 131 auf 188 Fälle; zusammen mit den Integrationstests sind es 192 (vorher 134). Den Inhalt beschreibt docs/19 §9.

**Zwei Befunde während der Testentwicklung:**
1. **Falsche Erwartung im Test:** Ich hatte für 9 Bit 456 Longs erwartet. Das entspräche dem Packen *über* Long-Grenzen. Korrekt sind ⌈4096 / 7⌉ = **586**, denn `SimpleBitStorage` legt 7 Werte zu je 9 Bit in ein Long und lässt 1 Bit ungenutzt. Die Implementierung war richtig, die Erwartung falsch. Der Test prüft jetzt 586.
2. **Fehler im Code:** `BitStorage.pack` prüfte 32-Bit-Werte über ein vorzeichenerweitertes `int` und lehnte gültige Werte ab. Behoben mit einer `& 0xFFFFFFFFL`-Maske. Für den Netzwerkpfad war das nicht relevant, denn der Decoder nutzt nur `unpack` und höchstens 16 Bit. Es wurde vom Roundtrip-Test für 32 Bit gefunden.

## 4. Was nicht oder nur in Unit-Tests abgedeckt ist

**Globale Paletten (Block ≥ 9 Bit, Biom ≥ 4 Bit):**
- In keinem der rund 440 dekodierten Chunks gesendet; das natürliche Terrain bleibt bei ≤ 31 Palettenwerten je Sektion.
- Nur durch Unit-Tests abgedeckt: Blöcke mit 9, 15 und 16 Bit, Biome mit 4 Bit.
- Ein Laufzeitfall bräuchte eine Sektion mit mehr als 256 verschiedenen Zuständen. Das ist mit Konsolenbefehlen möglich und wäre eine sinnvolle Ergänzung.

**`block_entity_data` und `chunks_biomes`:** In diesen Läufen nicht empfangen (0). Der Ursprungsbereich enthält keine Block-Entities. Beide Pakete sind dekodiert und unit-getestet. Zur Einordnung: der Vanilla-Referenzlauf aus docs/10 enthielt 5 `block_entity_data`.

**Licht:**
- Masken und Werte werden dekodiert und strukturell geprüft.
- Die Lichtwerte selbst sind **nicht** gegen den Server abgeglichen; es gibt keinen Konsolenbefehl für Lichtwerte.
- Plausibel: Himmelslicht 15 und 14 unter einem Blatt, 0 auf Grundgesteinshöhe.
- Lichtsektionen ohne Daten bleiben „unknown“, z. B. y = 319 über dem Gelände. Der Server sendet dort keine Arrays; Vanilla leitet den Wert lokal her.

**Dimensionswechsel:** `respawn` ist dekodiert und verdrahtet, aber nicht gegen den Server getestet. Ein Portal oder `execute in` würde dafür reichen.

**Heightmaps nach Blockupdates:** werden bewusst nicht nachgerechnet (docs/19 §5).

## 5. Abschlusskriterien

| Kriterium | Ergebnis |
|---|---|
| Vollständiges Chunk-Paket dekodiert, keine Restbytes | ✓ 440/440 Chunks in drei Läufen, jeder Puffer exakt aufgebraucht |
| Sektionsanzahl dynamisch aus `height`/`min_y` | ✓ 24 Sektionen in der Overworld; Nether-Fall (16) unit-getestet |
| Blockzustände und Biome aller Sektionen | ✓ jede Sektion vollständig, Biomnamen aus der Registry |
| Zustands-ID → Name und Eigenschaften (verifizierte 26.3-Daten) | ✓ 35 723 Zustände; 657 + 81 + 81 Positionen je Lauf serverbestätigt |
| Heightmaps, Block-Entities, Licht inkl. Masken | ✓ dekodiert und validiert (Einschränkungen in §4) |
| `block_update` / `section_blocks_update` | ✓ einzeln und mehrfach, über Chunkgrenzen, bei negativen Koordinaten |
| Chunk-Entladung und Neuladen | ✓ 49 Chunks entladen, Neuladen mit erhaltenen Änderungen |
| Schutz vor fehlerhaften Daten | ✓ Größen-, Bereichs-, Paletten- und Restbyte-Prüfungen, unit-getestet |
| Terminalbefehle `block`, `chunk`, `world`, `nearby`, `blocks` | ✓ Ausgaben serverseitig gegengeprüft |
| Schwellenwerte 256, −1, 0 | ✓ identische Ergebnisse |
| Alle bisherigen Tests | ✓ 192/192 bei jedem Schwellenwert |
| Nur localhost, keine Änderung an JARs, Server gestoppt | ✓ Details in §2.3 und unten |

**Nachweise zu localhost, JARs und Server:**
- Netzwerkmonitor: 0 externe Verbindungen.
- `server.jar` und `server-26.3.jar` unverändert (Zeitstempel vom 2026-11-06).
- Der Server ist gestoppt; es lauscht kein Prozess mehr auf 25565.
- `server.properties` steht wieder auf `network-compression-threshold=-1`.

## 6. Empfehlung für M5

**M5: Kollision aus echten Blockzuständen.** Die Bewegung (M2) schätzt den Boden noch aus der Heightmap MOTION_BLOCKING. Das ist bei Stufen, Treppen, Zäunen und nach Blockupdates ungenau. Das Weltmodell ist jetzt serverbestätigt. Damit kann M5 die Bodenhöhe und horizontale Kollisionen direkt aus den Blöcken bestimmen.

Vorgeschlagene Schritte:
1. **Tabelle Zustand → Kollisionsboxen.** Quelle müssen Faktendaten sein, z. B. ein eigener Datagen-Report oder ein Auszug über einen Fabric-Hilfsmod im `research`-Projekt. Mojang-Code wird nicht übernommen.
2. **AABB-Sweep der Spielerhitbox** (0,6 × 1,8) gegen diese Boxen, achsenweise wie in Vanilla: zuerst Y, dann X/Z. Dazu kommen Step-up 0,6 und Schwerkraft wie in M2.
3. **Validierung wie in M4.** Den Server als Referenz nutzen: Bewegungen über gezielt gebaute Hindernisse (Stufen, Treppen, Zaun, Lücke) dürfen **keine** Korrekturen erzeugen. Die Kontrolle erfolgt über `/data get` und den `corrections`-Zähler.

**Ergänzungen zu M4 mit niedriger Priorität:**
- Laufzeittest für globale Paletten (mehr als 256 Zustände in einer Sektion).
- Test für Block-Entities (Truhe mit Namen) und für den Dimensionswechsel.
- Lokale Nachführung der Heightmaps bei Blockupdates.
