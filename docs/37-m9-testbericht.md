# 37 – Testbericht M9: Entities, Spieler und Mobs

**Datum:** 2026-11-12/13 · Windows 11, 16 GB RAM, RTX 3070 · Server: ausschließlich lokaler Vanilla-26.3-Server, Kompressionsschwelle 256.

> **Stand: Endbericht.** Alle M9-Prüfungen und die vollständige Regression M1–M8 sind auf dem finalen Codestand erfolgreich (§6).
> **Endstand des Codes:** letzte Quelländerung 2026-11-12 20:35. Alle hier als „Endstand“ genannten Läufe fanden danach statt; danach wurde nur noch eine Prüfung im Testskript e2 präzisiert (§1).

**Umgebung:**
- **Schwierigkeit:** easy statt peaceful, weil peaceful Zombies und Skelette sofort entfernt. Beide Testspieler sind im Kreativmodus und können nicht sterben.
- **Gamerule `spawn_mobs`:** während der Tests aus, danach wieder auf den gelesenen Wert (`true`).
- **Nach jedem Lauf zurückgesetzt:** `difficulty=easy`, `network-compression-threshold=-1`.
- **Netzwerkmonitor:** in allen Läufen 0 externe Verbindungen.

**Rohdaten** (gitignored, `analysis/runtime-logs/`):
- Endstand M9: `m9-endstand-e1-basic`, `m9-endstand2-e2-players-items`, `m7-m9-endstand-epicks-{survival,creative}`, `m6-m9-endstand-gfx`, `m6-m9-endstand-perf`, `m6-m9-endstand-long`, `m6-m9-orient`;
- Endstand Regression: `m5-threshold-256`, `m6-m9-regress-m6full`, `m7-m9-regress-*`, `m6-m9-regress-m7gfx`, `m8-m9-regress-s1…s6`, `m6-m9-regress-m8gfx`;
- Zwischenstände: `m9-e1a/b`, `m9-e2a/b`, `m9-final-*`, `m9-endstand-e2-players-items`, `m7-m9-epicks*`, `m6-m9-gfx1/2`, `m6-m9-perf1/2`, `m6-m9-long1`.

## Ergebnisübersicht

| Prüfung | Ergebnis |
|---|---|
| e1: Spawn, Typen, IDs/UUIDs, Datenfelder, Ausrüstung, Bewegung, Teleport, Entfernen, KI-Bewegung | **18 / 18** |
| e2: anderer Spieler, Spielerliste, Item fallen lassen/aufsammeln, Entladen/Neuladen, Nether und zurück, 50 Entities | **21 / 21** |
| Auswahl gegen den Vanilla-Client (185 Blickrichtungen, bitgenau) | Überleben **185 / 185** (54 Entity-Treffer), Kreativ **185 / 185** (67) |
| Grafik: alle Typen, Auswahlrahmen, Namensschild, Rüstung, Animation, Debug-Box | in Ordnung (Bilder §3), 0 GL-Objekte offen |
| Leistung 10 / 50 / 100 Entities | 60 / 60 / 60 FPS (1 %-Low 60), Entity-CPU 0,06 / 0,24 / 0,38 ms je Bild |
| Dauerbetrieb 6,5 min Grafik | 60 FPS, Heap ohne Anstieg, **MATCH 22 / 22** Entities gegen den Server am Ende |
| Unit-Tests M9 | `EntityPacketDecoderTest` 11, `EntityInterpolatorTest` 9, `EntityManagerTest` 10: **30 / 30** |
| Alle Unit-Tests | **306 / 306** |
| JUnit gesamt inkl. Integration | **311 / 311**, 0 übersprungen |
| Regression M1–M8 | **vollständig grün** (§6) |

## 1. Netzwerkebene gegen den Server (`analysis/tools/m9-ent.sh`, Terminalclient)

**Verfahren:**
1. Der Client gibt je Entity eine Zeile aus (`edump`: ID, UUID, Typ, Position in voller Genauigkeit, Rotationen, Datenfelder, Ausrüstung).
2. Für jede UUID fragt das Skript `data get entity <uuid>` ab.
3. `analysis/tools/m9-compare.js` vergleicht mit diesen Toleranzen:
   - UUID exakt;
   - Position ≤ 1/8192 (1/4096-Raster der Deltas, [34](34-entity-protokoll.md) §3.1);
   - Gier und Neigung ≤ 360/256°;
   - onGround, Gesundheit, eigener Name, Baby, Wollfarbe und geschoren, Item, Ausrüstung (bei Spielern die Haupthand aus Inventar und gewähltem Platz).
4. Positionen werden nur bei eingefrorenen Mobs (NoAI) verglichen.

| Bereich | Prüfungen (Endlauf) |
|---|---|
| Spawn aller 6 Typen, Anzahlen je Typ | MATCH 6, 6 × Anzahl gleich |
| Name, Wolle/geschoren, Gesundheit nach `damage`, Babyschwein und -zombie | MATCH 8 |
| Ausrüstung (Helm + Schwert, Bogen, Entfernen) | 3 × MATCH |
| Relative Bewegung über 6 Updates mit Drehung; Kopf | MATCH |
| Neigung und Kopfdrehung (Kuh) | MATCH |
| Teleport 12 Blöcke (`entity_position_sync`) und Sprung der Darstellung | MATCH, Sprung gezählt |
| Entfernen (`kill`) | Client 0 = Server 0 |
| Zwei KI-Tiere laufen 8 s frei, dann eingefroren | MATCH 11 |
| Anderer Spieler: UUID, Position, Rotation; Spielerliste mit Entity-Zuordnung; eigener Spieler nicht als Entity | MATCH, 2 × erwartet |
| Anderer Spieler läuft mit eigener Physik; Ausrüstung | 2 × MATCH |
| Fallen gelassenes Item (Typ, Anzahl, gelandete Position) | MATCH |
| Aufsammeln: Entity weg in Client und Server, Gold im eigenen Inventar | 3 × OK |
| Entladen (600 Blöcke weg): Client 0, Server behält; zurück: dieselben UUIDs | 4 × OK |
| Nether und zurück: keine Overworld-Entities behalten, danach wieder identisch | 4 × OK |
| 50 Schweine gleichzeitig: Anzahl, jede UUID/Position, Entfernen | 51 = 51, MATCH 51 |

**Zähler (e1):**
- 1 651 Entity-Pakete, darunter 379 Bewegungen; davon 305 als gestufte Pfade (26.x-Format `VecDelta.Stepped`).
- 0 Pakete für unbekannte IDs, 0 nicht dekodierbare Datenfelder.
- Verarbeitung im Mittel 5 µs je Paket.

**Im Lauf gefundene Testfehler** (nicht im Client; korrigiert):
- Item-Entities haben ein NBT-Feld `Health` (Haltbarkeit);
- Spieler speichern die Haupthand im Inventar, nicht unter `equipment`;
- `Count:` großgeschrieben;
- Entladen bedeutet „Server behält, Client entfernt“;
- der zweite Spieler hob ein Testitem zuerst auf;
- Entladen: In einem Endstand-Lauf wurde am Zielort (600/600) ein dort bereits lebendes Schwein sichtbar (`add_entity` nach den `remove_entities` der Testtiere). Die Prüfung zählte jedes Schwein. Sie prüft jetzt je UUID, dass genau die Testtiere verschwinden; der Wiederholungslauf `m9-endstand2-e2-players-items` ergab 21 / 21.

## 2. Auswahl gegen Vanilla (`m7-pick-run.sh` mit `M7_PICKS=analysis/tools/m9-picks.txt`, `M7_PICK_CMD=epicks`)

**Verfahren:**
- Der unveränderte Vanilla-Client wertet `LocalPlayer#raycastHitResult` aus; der eigene Treiber `PickReferenceClient` wurde dafür um die Ausgabe von `EntityHitResult` erweitert.
- Der eigene Client wertet `EntityPick` aus.
- 185 Blickrichtungen auf 10 Ziele:
  - Schwein, Zombie, Schaf, Kuh, Skelett, Babyzombie, Rüstungsständer;
  - nicht auswählbar: Markierung, Item;
  - eine Kuh hinter Glas;
  - Schwein außerhalb der Entity-Reichweite (Vanilla: Fehlschlag am Trefferpunkt der Entity);
  - zweimal das Auge in der Hitbox.
- Verglichen wird die ganze Zeile (Art, UUID, Typ, Trefferpunkt mit allen Ziffern, Reichweiten).

**Ergebnis:** In beiden Spielmodi sind alle 185 Zeilen **bitgleich** (54 bzw. 67 Entity-Treffer).

**Gefundene und behobene Fehler** (erster Lauf 179/185):
- Markierungs-Rüstungsstände galten als auswählbar (Flag 0x10, Maße 0 × 0).
- Babyzombies haben in 26.3 eigene Maße 0,49 × 0,98 statt halber Typmaße. Ebenso Schwein, Kuh und Schaf ([34](34-entity-protokoll.md) §11).

## 3. Grafischer Client

| Lauf | Inhalt | Ergebnis |
|---|---|---|
| `m6-m9-orient` | Gier 0 und 90 für Schwein und Zombie unter Lichtblöcken | Ausrichtung, Gesicht, Arme, Texturen korrekt |
| `m6-m9-gfx2` (`m9-gfx.txt`) | alle Typen, gefärbtes und geschorenes Schaf, Zombie mit Eisenhelm und -schwert, Skelett mit Bogen, Babyzombie, Diamant und Erde am Boden, zweiter Spieler (Standard-Skin, Diamantbrust, Schwert, Namensschild), Auswahlrahmen, laufender Spieler und KI-Schwein | Bilder in Ordnung; Auswahl Schwein 2,72 m, Spieler 2,70 m; 0 GL-Objekte offen |
| `m6-m9-long1` | Debug-Box (Rüstungsständer, magenta) | sichtbar |

Die Bildschirmfotos enthalten lokale Mojang-Texturen und werden nicht nach `docs/images` kopiert.

## 4. Leistung (`m9-perf.txt`, Sichtweite 6, KI-Schweine im Blickfeld; Endstand `m6-m9-endstand-perf`)

| Entities | gezeichnet | FPS / 1 %-Low | Bildzeit | Entity-CPU je Bild | Draws | Paket Ø / max | Tick Ø / max | Heap |
|---|---|---|---|---|---|---|---|---|
| 0 | 0 | 52 / 16 (Laden) | 19,2 ms | 0,004 ms | 0 | – | – | 144 MB |
| 10 | 10 | 60 / 60 | 16,7 ms | 0,06 ms | 60 | 3,9 µs / 1,6 ms | 5,1 µs / 0,09 ms | 239 MB |
| 50 | 50 | 60 / 60 | 16,7 ms | 0,24 ms | 300 | 1,2 µs | 5,9 µs | 153 MB |
| 100 | 99 | 60 / 60 | 16,7 ms | 0,38 ms | 594 | 0,6 µs | 6,4 µs | 147 MB |

- Die Bildrate ist durch V-Sync auf 60 begrenzt; die Entity-Darstellung braucht weniger als 4 % der Bildzeit.
- Es gibt keine GPU-Neuinitialisierung und keine GPU-Objekte je Bild: Meshes je Modellteil werden einmal erzeugt, 0 Objekte beim Beenden.
- Der Höchstwert der Paketverarbeitung (2–4 ms) tritt einmalig beim ersten Entity-Paket auf (Klassenladen).
- Hinweis: Mit der Standardsichtweite 2 sendet der Server nur Entities in den 5 × 5 geladenen Chunks (erster Lauf: 47 von 100). Das ist gewollt und kein Verlust.

## 5. Dauerbetrieb (`m9-long.txt`, 6,5 min, Sichtweite 4)

**Aufbau:** 20 KI-Mobs (5 Schweine, 4 Kühe, 4 Schafe, 4 Zombies, 3 Skelette), 2 Items, Rüstungsständer und ein zweiter Spieler, der jede Minute dreimal in eine neue Richtung läuft.

**Verlauf:**
- 60 FPS, 1 %-Low 59 über die ganze Zeit.
- Heap 99–233 MB ohne Anstieg; Paketverarbeitung im Mittel ≤ 0,8 µs nach dem Start (Endstand `m6-m9-endstand-long`; erster Lauf `m6-m9-long1`: 135–235 MB).
- Nach 5 Minuten verschwanden die 2 Items: das reguläre Despawn des Servers, vom Client per `remove_entities` übernommen.

**Ende:**
- Alle Mobs wurden eingefroren und je UUID mit dem Server verglichen: **MATCH 22 / 22** (Position, Rotation, onGround, Gesundheit, Wolle, Ausrüstung).
- Kontrollierte Trennung, 0 GL-Objekte offen (2 713 Meshes erzeugt und freigegeben).

## 6. Regression M1–M8 (Endstand)

**Ablauf:** Testgruppen nacheinander; vor jedem Start Prüfung des freien Speichers (Abbruch unter 3 000 MB); danach jeweils Gradle-Daemons beendet. Speichergrenzen wie bisher: Server 1 GB, Vanilla-Client 1,5 GB.

| Gruppe | Prüfung | Ergebnis |
|---|---|---|
| 1 M1–M5 (`m5-run.sh 256`) | JUnit inkl. Integration (Status, Login/Konfiguration/Play, Kompression 256, Keep-Alive, Bewegung, Welt, Physik) und der 30 M9-Tests | **311 / 311**, 0 übersprungen |
| | Vanilla-Physikvergleich | 20 Szenarien, 896 Ticks, worst |Δpos| = 0, 0 Flag-Abweichungen |
| | M4-Weltverwaltung und Blockvergleich | 657/657, nach Entladen 81/81, nach Wiederherstellen 81/81; Negativkontrolle schlägt erwartungsgemäß fehl |
| | M2-, M4-, M5-Terminaltests | exit 0 |
| 2 M6 | Volltest (5 min Grafik) | 9 / 9 Positionen Client = Server, kontrollierter Disconnect, 0 GL-Objekte offen |
| 3 M7 | Auswahl gegen Vanilla | Überleben 75/75, Kreativ 75/75 |
| | Interaktion, Licht | 14 / 14; Himmel und Block je 1729 / 1729 |
| | Grafiktest | alle 5 Serverprüfungen bestanden, 0 GL-Objekte offen |
| 4 M8 | Inventar s1–s6 | 5 + 25 + 28 + 11 + 16 + 4 = **89 / 89** |
| | Grafiktest | 11 / 11 Inventarvergleiche + Weltsperre, 0 GL-Objekte offen |
| 5 M9 | Netzwerk e1 / e2 | 18 / 18, 21 / 21 |
| | Auswahl gegen Vanilla | 185 / 185 je Modus |
| | Grafik, Leistung, Dauerbetrieb | §3–§5, 0 GL-Objekte offen, MATCH 22 / 22 |

Alle Läufe: 0 externe Verbindungen. Danach: alle Projektprozesse beendet, `difficulty=easy`, `network-compression-threshold=-1`, Gamerule `spawn_mobs` wieder `true`, Server-JAR unverändert (`a362163eec5d1612…`).

**Vorfälle während der Regression** (keine Codefehler, Läufe wiederholt):
- Der erste Versuch am 2026-11-12 wurde vom System wegen Speichermangels beendet.
- Der folgende Lauf scheiterte an einem unvollständigen Build-Zustand des abgebrochenen Laufs (`NoClassDefFoundError` einer Test-Hilfsklasse); alle 306 Unit-Tests liefen danach einzeln grün.
- Ein weiterer Lauf wurde verworfen, weil weiterlaufende Prozesse des abgebrochenen Laufs dieselben Dateien und denselben Server nutzten.
- Der erste Versuch der M9-Endläufe wurde ebenfalls wegen Speichermangels beendet; Restprozesse wurden gezielt beendet, die Läufe am 2026-11-13 wiederholt.

## 7. Grenzen (siehe [35](35-entity-architektur.md), [36](36-entity-rendering.md))

- **Modelle:**
  - nur Spieler, Schwein, Kuh, Schaf, Zombie, Skelett und Items; alle anderen Typen als Debug-Box;
  - keine eigenen Baby-Modelle; warme und kalte Varianten mit gemäßigter Geometrie;
  - keine Posen (Schleichen, Schwimmen, Schlafen) im Modell, nur in der Hitbox.
- **Skins:** keine Downloads (Vorgabe), immer lokale Standard-Skins.
- **Datenfelder:** Partikel, Profile und Gemäldevarianten werden nicht dekodiert. Das Paket wird dann ab dieser Stelle gezählt verworfen; in allen Läufen kam das nicht vor.
- **Interaktion:** kein Angriff und keine Entity-Interaktion (späterer Meilenstein); Auswahl nur zur Anzeige.
- **Teleport-Darstellung:** springt ab 8 Blöcken (Vanilla interpoliert bis 64).
- **Fahrzeuge:** Passagiere werden verwaltet, aber nicht relativ zum Fahrzeug positioniert oder dargestellt.
