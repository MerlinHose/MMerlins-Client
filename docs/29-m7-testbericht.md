# 29 – Testbericht M7: Interaktive Spielwelt

**Datum:** 2026-11-08 · Windows 11, 16 GB RAM, RTX 3070 (Treiber 621.01) · Endstand des Codes: alle Läufe in §1–§5 („final“) nach der letzten Änderung.

**Server:** ausschließlich der lokale Vanilla-26.3-Server auf 127.0.0.1. Jeder Lauf setzt `difficulty=peaceful` und stellt danach die Originalwerte wieder her (`difficulty=easy`, `network-compression-threshold=-1`). Netzwerkmonitor in allen Läufen: **0 externe Verbindungen**. Das Server-JAR ist unverändert (sha256 `a362163eec5d1612…` vor und nach allen Läufen).

**Speicher:**
- Fremde Anwendungen belegten während der Arbeit 2,2–5,4 GB.
- Mit Zustimmung des Nutzers liefen die Tests mit reduzierten Grenzen: Server `-Xmx1G`, Vanilla-Referenzclient `-Xmx1536M`. Einstellbar über `M5_*`, `M6_SERVER_XMX`, `M7_*`; die Standardwerte der Skripte sind unverändert.
- Ein früher Grafiklauf wurde vom System wegen Speichermangels beendet und später vollständig wiederholt (§4).

**Rohdaten** (gitignored, `analysis/runtime-logs/`):
- `m7-final-picks-*`, `m7-final-interact`, `m6-m7-final-gfx`, `m7-light3`;
- `m5-threshold-256`, `m6-m7-regress-m6full`;
- Zwischenstände `m7-picks1…4`, `m7-interact1…5`, `m6-m7-gfx…gfx3`, `m7-light1…2`.

Die Bildschirmfotos enthalten lokale Mojang-Texturen und werden nicht nach `docs/images` kopiert.

## Ergebnisübersicht

| Prüfung | Ergebnis |
|---|---|
| Auswahl gegen Vanilla, Überleben (75 Blickrichtungen) | **75 / 75** identisch |
| Auswahl gegen Vanilla, Kreativ (75) | **75 / 75** identisch |
| Abbau/Platzieren/Benutzen, serverseitig geprüft | **14 / 14** |
| Grafiktest: Auswahl, Abbau, Platzieren, Hotbar, Inventar, Hebel, Leckzyklen | **5 / 5** Serverprüfungen, Bilder in Ordnung, 0 GL-Objekte offen |
| Licht nach Blockänderungen gegen Serverlicht (1 729 Zellen) | Himmel **1 729 / 1 729**, Block **1 729 / 1 729** |
| Unit-Tests | **266 / 266** |
| M1–M5-Regression inkl. Vanilla-Physikvergleich | **271 / 271** JUnit inkl. Integration, Physik 20 Szenarien / 896 Ticks, max. \|Δpos\| = 0 |
| M6-Volltest (5 min, Grafik) | **9 / 9** Positionsabgleiche, 60 FPS |

## 1. Auswahl gegen die Vanilla-Welt

**Verfahren** (`analysis/tools/m7-pick-run.sh`, Szenarien `m7-picks.txt`, sha256 `574dd12fd484220b…`):
1. Testreihe aus 21 Formen auf Steinboden: Fackel, Mohn, Gras, Zaun, Treppe, obere Stufe, Glasscheibe, Hebel, Truhe, offene Falltür, Tür, Knopf, Schnee 3, Teppich, Farn, Laterne, Endstab, Kuchen, Amboss, Trichter, Komposter.
2. Dazu Sonderfälle: Himmel, außer Reichweite, Schrägblick, senkrecht, Blickgerade exakt auf Blockgrenzen (x = 0, x = −2), Ecke, zweimal Auge im Block, negative x, Kreativ-Reichweite.
3. Der unveränderte Vanilla-Client (Fabric-Treiber `PickReferenceClient`) wertet `LocalPlayer#raycastHitResult(1.0F, player)` aus.
4. Der eigene Client wertet `BlockRaycast.pick` mit den Reichweiten aus `update_attributes` aus.
5. Beide verwenden dieselbe Datei und denselben Spielmodus (Server-Log „Set … game mode“; Treiber: `MEASURE gamemode … blockRange … entityRange`).
6. Verglichen werden alle Felder: Treffer/Fehlschlag, Block, Seite, Trefferpunkt (alle Ziffern), inside, Block- und Entity-Reichweite.

| Lauf | Modus (Reichweiten) | Ergebnis |
|---|---|---|
| `m7-picks3` (vor der Korrektur, 69 Szenarien) | Überleben (4,5 / 3,0) | 65 gleich, 4 abweichend (`short_grass`, `fern`) |
| `m7-final-picks-survival` | Überleben (4,5 / 3,0) | **75 / 75** |
| `m7-final-picks-creative` | Kreativ (5,0 / 5,0) | **75 / 75** |

**Behobene Abweichung:** `TallGrassBlock#getShape` wendet den Positionsversatz **nicht** an; Vanilla trifft Gras und Farn bei z = −29,125. Der Extraktor prüft jetzt je Form empirisch, ob der Versatz angewendet wird, und kennt `OffsetType.XYZ` (docs/27 §1.2).

**Regressionstests** (`BlockRaycastTest`):
- negative Koordinaten auf allen sechs Seiten;
- Strahl genau in einer Blockgrenzebene (x = 0, x = −2: nur die Startspalte wie `traverseBlocks`);
- Start auf einer Fläche gilt als innen (Grenze 0,001 · Strahllänge, beide Seiten geprüft);
- Platte innen/außen;
- Blumen mit, Gras/Farn ohne Versatz.

## 2. Abbau, Platzieren, Benutzen gegen den Server

`analysis/tools/m7-interact.sh`: eigener Terminalclient. Nach jeder Aktion prüft die Serverkonsole den Zustand (`execute if block/entity`, `clear … 0`, `data get`). Lauf `m7-final-interact`:

| Prüfung | Vanilla-Formel | Client (WorldState) | Server |
|---|---|---|---|
| Erde, Hand | 15 Ticks | Luft nach ~16 Ticks | ✔ |
| Stein, Hand (Werkzeug nötig) | 150 Ticks, kein Drop | ~158 Ticks | ✔ Luft, ✔ kein Drop |
| Stein, Holzspitzhacke | 23 Ticks, Drop, Schaden 1 | ~28 Ticks, Schaden im Inventar | ✔ Luft, ✔ Drop, ✔ `damage` 1 |
| Abbruch nach 10 Ticks | Block bleibt | Stein | ✔ |
| Obsidian, Diamantspitzhacke Effizienz V | 45 Ticks | ~45 Ticks | ✔ |
| Bretter platzieren (Überleben) | Nachbarzelle, 4 → 3 | `oak_planks` | ✔ Block, ✔ „Found 3“ |
| Hebel, Tür (leere Hand) | `useWithoutItem` | `powered=true`, `open=true` | ✔ ✔ |
| Hotbar Platz 5 | `set_carried_item 4` | – | ✔ `SelectedItemSlot` 4 |
| Kreativ | Sofortabbau | ~2 Ticks | ✔ |
| `written_book_content` | Paket verwerfen, Verbindung bleibt | gezählt, nach `clear` aktuell | – |

Ergebnis: **14 / 14**. Das Serverprotokoll enthält keine „moved wrongly“-, Mismatch- oder Fehlermeldungen.

**Verlauf:** Die Läufe 1–4 hatten je eine fehlgeschlagene Prüfung. Jede lag nachweislich am Testskript, keine am Client:
- Platzierung vor dem Kreativ-Ziel;
- aufgehobener Obsidian-Drop belegte den Platz;
- Drop direkt aufgehoben;
- Prüfung las die Antwort eines vorherigen `kill`-Befehls.

Behebungen: `item replace` statt `give`, Drops entfernen, Drop „am Boden oder im Inventar“, Wartezeit vor der Prüfung. Lauf 5 und der finale Lauf: 14 / 14.

**Unit-Tests der Paketfolgen** (`BlockInteractionTest`, 10): Hand/Erde START#1 → STOP#2 im 15. Tick, Formeln, ABORT, Kreativ (5 Ticks Verzögerung, danach Block dahinter), Kreativ-Schwert, Platzieren, beide Hände, Hebel, Hotbar inkl. Echo. Dazu `ItemStackCodecTest` (6).

## 3. Schwarzer Bereich vor dem Erdblock

### 3.1 Untersuchung

**Reihenfolge wie beauftragt:** zuerst die Lichtberechnung, dann Meshing, Face Culling, Texturkoordinaten.

**Nachweis** (`analysis/tools/m7-light.sh`, Terminalbefehl `block`, ohne jedes Rendering):
- Nach `setblock … oak_planks` und `fill … air` bei geladenem Chunk meldete das eigene Weltmodell an (30, 100, −29) **Himmelslicht 0**.
- Die Zelle darüber und die Nachbarzelle hatten 15.
- Der Client hatte **kein** `light_update` erhalten (Zähler 0).

**Ursache in den Vanilla-Quellen:**
- `ChunkHolder#broadcastChanges` sendet Lichtpakete nur an Spieler, für die der Chunk am **Rand** der Sichtweite liegt (`getPlayers(pos, true)`).
- Für innere Chunks berechnet der Vanilla-Client das Licht selbst (`LevelChunk#setBlockState` → `skyLightSources.update`, `checkBlock`).
- Unser Client tat das seit M4 nicht.

**Einordnung:**
- Meshing, Face Culling und Texturkoordinaten sind ausgeschlossen, weil der Fehler schon im Weltmodell vor jeder Darstellung lag. Daran wurde nichts geändert.
- Die frühere Vermutung (verworfene Lichtpakete für ungeladene Chunks) war nicht die Ursache.

### 3.2 Behebung

`world/ClientLightEngine` ist eine eigene Umsetzung des Vanilla-Lichtverfahrens: Block- und Himmelslicht, Himmelsquellen je Spalte, Abnahme- und Zunahme-Warteschlangen, Formverdeckung (docs/27 §7). Die Lichtfakten kommen aus dem unveränderten Server-JAR: 22 235 Zustände, 37 Seitenmasken, 0 Koordinaten außerhalb des 1/16-Rasters.

### 3.3 Abgleich gegen das Serverlicht (`m7-light3`)

**Verfahren:**
1. 14 Änderungen bei geladenem, innerem Chunk: der Ausgangsfall (Bretter → Luft), Dach mit Schatten, Fackel, Leuchtstein, obere Stufe, Treppe, Loch im Dach, Seelaterne gesetzt und entfernt, Glas, Laterne, Laub.
2. `lightdump` des Quaders 28…46 × 99…105 × −36…−24 (1 729 Zellen) mit dem eigenen, inkrementell berechneten Licht.
3. Neu verbinden: Jetzt liefert der Server das von ihm berechnete Licht in den Chunkpaketen.
4. Erneuter `lightdump` und Zellvergleich.

| Lauf | Himmelslicht | Blocklicht |
|---|---|---|
| `m7-light2` | 1 721 / 1 729 | 1 729 / 1 729 |
| `m7-light3` | **1 729 / 1 729** | **1 729 / 1 729** |

**Zu `m7-light2`:**
- Die 8 Abweichungen lagen ausschließlich im Laub. Laub ohne Stamm zerfiel auf dem Server zwischen den beiden Messungen; verglichen wurden also verschiedene Blockzustände.
- Mit `persistent=true` gibt es keine Abweichung mehr.
- Ausgangszelle (30, 100, −29): eigenes Licht 15/8 = Server 15/8.
- Im Grafiktest (`m6-m7-final-gfx`, Bild 24) ist die Fläche vor dem Erdblock normal beleuchtet.

## 4. Grafikclient

**Lauf `m6-m7-final-gfx`** (Autotest `m7-gfx.txt`, gleicher Eingabeweg wie die Maus):

| Prüfung | Ergebnis |
|---|---|
| Auswahlrahmen Erde, Zaun, Treppe, Gras | an der richtigen Form (Bilder 20–23) |
| Abbau mit gehaltener Taste, Fortschrittsbalken | Balken sichtbar (Bild 24), Server: Luft ✔ |
| Platzieren über Hotbar Platz 1 | Block ✔, 8 → 7 ✔ |
| Hebel mit leerer Hand | ✔ |
| Hotbar-Anzeige, Inventarfenster | korrekt (Bilder 25, 27; Rahmen und Text jetzt deckungsgleich) |
| 20 Zyklen Platzieren/Abbauen/Inventar (Kreativ) | Server: Block danach entfernt ✔ |
| Gewählter Platz am Ende | Server `SelectedItemSlot` 0 = Platz 1 (zuletzt gewählt) ✔ |
| Bildrate (10 s mit HUD und Auswahl) | 60 FPS, 1 %-Low 59 |

**Behobene Darstellungsfehler aus früheren Läufen:**
- Bild „Abbau“ entstand nach dem Loslassen; neue Schritte `attack-start`/`attack-stop`.
- Das Inventarfenster lag unter dem F3-Text und zeichnete eine leere Zeile zu viel.

## 5. Speicher- und GPU-Lecks

| Prüfung | Ergebnis |
|---|---|
| Lebende GL-Objekte beim Beenden (neuer Zähler `GlResources`: erzeugt − freigegeben) | **0** – Grafiktest: 1 253 Meshes, 5 Programme, 4 Texturen, 1 HUD-Puffer erzeugt, alle freigegeben; M6-Volltest (5 min): 2 929 Meshes, alle freigegeben |
| 20 Interaktionszyklen | Meshes 283 → 283, GPU 23,7 → 23,7 MB |
| Java-Heap | schwankt mit der Speicherbereinigung (104 / 186 / 179 / 125 MB von 768 MB), kein Anstieg; M6-Volltest über 5 min ohne Anstieg |
| Prozessspeicher | Spitze Projekt 1,4 GB (Grafiktest), 1,1 GB (Interaktion) |

## 6. Regression M1–M7

**`m5-run.sh 256`** (Server 1 GB, Vanilla-Client 1,5 GB):

| Prüfung | Ergebnis |
|---|---|
| Vanilla-Physikreferenz | 20 Szenarien, 896 Ticks aufgezeichnet |
| JUnit inkl. Integration | **271 / 271** – Status, Login/Konfiguration/Play, 60-s-Play mit Keep-Alive und kontrolliertem Disconnect, Bewegung, Weltabgleich, Physik gegen Server **und** Vanilla |
| Physikvergleich | worst \|Δpos\| = 0, 0 Flag-Abweichungen, 0 Serverkorrekturen außer den 40 Skript-Teleports |
| M2-Terminaltest | SUCCESS |
| M4-Weltabgleich | 657/657, nach Entladen 81/81, nach Wiederherstellen 81/81; Negativkontrolle schlägt erwartungsgemäß fehl |
| M4- und M5-Terminaltests | exit 0 |

Der erste Durchlauf zeigte bei den drei Terminaltests „client not ready“. Ursache war ein Wettlauf im Testskript: Eine alte `> ready`-Zeile in `client.out` beendete das Warten, bevor der neue Client die Datei leerte. Die Skripte leeren `client.out` jetzt vor dem Start; der zweite Durchlauf war vollständig grün.

**M6-Volltest** (`m6-full.txt`): 9/9 Positionen Client = Server, 5-Minuten-Lauf, 60 FPS, Disconnect kontrolliert.

**M7:** §1–§5.

## 7. Grenzen (nicht Teil von M7, unverändert offen)

- **Entities:** Auswahl, Angriff, `interact`;
- **Abbau-Sonderfälle:** Statuseffekte (Eile/Abbaulähmung), Abbau unter Wasser nur vereinfacht, Abenteuermodus mit `can_break`, Weltgrenze;
- **Rechtsklick:** mit Nicht-Block-Items und Schleichen nur als Näherung (docs/27 §4);
- **Inventar:** nur Anzeige (kein Verschieben, Droppen, Kreativ-Inventar, keine Behälterfenster);
- **Licht:** Sonderfälle der Lichtspeicherung (`updateSectionStatus`, leere Sektionen unter Sektionsrändern) nicht nachgebildet; im Abgleich ohne Auswirkung;
- **Echte Bedienung:** Maus/Tastatur durch einen Menschen wurde in M7 nicht erneut von Hand getestet. Der Autotest ruft dieselben Methoden auf, aber ohne GLFW-Ereignisse.
