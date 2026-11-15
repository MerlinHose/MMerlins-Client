# 27 – Blockinteraktion: Auswahl, Abbau, Platzieren (M7)

**Stand:** 2026-11-08 · Minecraft 26.3 / Protokoll 777 · Quellen: von Loom generierte Mojang-Quellen (nur gelesen), Datagen-Reports, Fakten aus dem unveränderten `server-26.3.jar`.

Dieses Dokument beschreibt, wie der eigenständige Client Blöcke auswählt, abbaut, platziert und benutzt. Die Testergebnisse stehen in [29-m7-testbericht.md](29-m7-testbericht.md), Inventar und Hotbar in [28-inventar-und-hotbar.md](28-inventar-und-hotbar.md).

**Grundsatz:** Der Client verändert seine Welt **nie** selbst. Jede Blockänderung entsteht ausschließlich aus `block_update` bzw. `section_blocks_update` des Servers. Wo Vanilla anhand einer lokalen Vorhersage weiterentscheidet, hilft eine unsichtbare Vorhersageliste (§3.4).

## 1. Auswahl (Raycast)

### 1.1 Vanilla-Ablauf

| Schritt | Vanilla 26.3 | eigener Client (`physics/BlockRaycast`) |
|---|---|---|
| Zeitpunkt | `GameRenderer#pick` je Bild und `Minecraft#tick` → `pick(1.0F)` je Tick | `GameView` je Bild (Anzeige), `BlockInteraction#tick` je Tick (Aktionen) |
| Auge | `Entity#getEyePosition(a)` = Position + `1.62F` | Füße + `(double) 1.62F` |
| Richtung | `Entity#calculateViewVector(xRot, yRot)` mit `Mth.sin/cos` (Sinustabelle) | `BlockRaycast.viewVector` mit derselben Tabelle (`Trig`, M5) |
| Länge | `max(blockRange, entityRange)` (`LocalPlayer#pick`) | gleich |
| Durchlauf | `BlockGetter#traverseBlocks`: Start/Ende um 1e-7 verschoben, DDA | gleich (Gleichstandsregel: x vor z, y vor z) |
| Form | `ClipContext.Block.OUTLINE` → `BlockState#getShape` (Umriss, **nicht** Kollision); Flüssigkeiten `NONE` | Umrissformen aus der Formtabelle (`U`/`UO`-Zeilen) |
| Interaktionsform | `clipWithInteractionOverride`: trifft die Interaktionsform (z. B. Komposter-Innenraum) näher, zählt deren Seite | `N`-Zeilen, gleiche Regel |
| Innen | `VoxelShape#clip`: liegt `from + 0,001·diff` in der Form → Treffer „inside“, Seite = Gegenrichtung von `Direction.getApproximateNearest(diff)` | gleich |
| Flächen | `AABB#clip`/`getDirection`/`clipPoint`, Toleranz 1e-7 | gleich |
| Reichweite | `filterHitResult`: Treffer nur wenn `closerThan(eye, range)` (strikt), sonst Fehlschlag | gleich |

**Reichweiten:** Attribute `minecraft:block_interaction_range` (Standard 4,5) und `minecraft:entity_interaction_range` (3,0). Im Kreativmodus setzt **der Server** einen Modifikator (+0,5 bzw. +2,0; `ServerPlayer#updatePlayerAttributes`). Der Client erfährt das über `update_attributes`. Unser Client dekodiert dieses Paket und rechnet `AttributeInstance#calculateValue` nach (ADD_VALUE → ADD_MULTIPLIED_BASE → ADD_MULTIPLIED_TOTAL, Bereichsgrenzen). Aus dem Spielmodus wird nichts abgeleitet.

**Entities:** Der Client verfolgt noch keine Entities. Ein Entity vor dem Block verdeckt daher nicht die Auswahl wie in Vanilla (siehe §6).

### 1.2 Formtabelle: Umriss, Interaktion, Härte

`analysis/tools/ShapeExtractor.java` (Analysewerkzeug, läuft gegen das unveränderte Server-JAR) schreibt zusätzlich zu den Kollisionsformen aus M5:

| Zeile | Inhalt |
|---|---|
| `U id idx` | Umrissform (`getShape(EmptyBlockGetter, ZERO, CollisionContext.empty())`) |
| `UO id idx` | offsetfreie Umrissform. Nur vorhanden, wenn `getShape` den Positionsversatz **tatsächlich anwendet** (empirisch an drei Probe-Positionen geprüft) |
| `N id idx` | Interaktionsform, falls nicht leer |
| `H id destroyTime requiresTool replaceable use` | Härte, Werkzeugpflicht, ersetzbar, eigene Rechtsklick-Logik (`W` = Klasse überschreibt `useWithoutItem`, `I` = `useItemOn`) |
| `O id maxH baseIdx maxV` | Offset-Block (M5-Format um `maxV` erweitert). `baseIdx` −1 = Kollision hängt nicht von der Position ab |

Ergebnis: 35 723 Zustände, 958 Formen, 315 KB (`client/src/main/resources/physics/shapes-777.txt.gz`).

**Korrektur aus dem Vanilla-Vergleich (docs/29 §1):**
- Die erste Fassung nahm an, dass jeder Block mit Offset-Funktion seinen Umriss mitverschiebt.
- Der Abgleich zeigte das Gegenteil für `short_grass` und `fern`: `TallGrassBlock#getShape` liefert `SHAPE` **ohne** Versatz, der Versatz betrifft nur das Modell.
- Dieselbe Abweichung gibt es bei 41 Offset-Pflanzen (u. a. alle hohen Blumen, Wurzeln, `tall_seagrass`, `small_dripleaf`).
- Umgekehrt verschieben `FlowerBlock`, Bambus, `pointed_dripstone`, `mangrove_propagule` und `sulfur_spike` ihre Form tatsächlich.
- Außerdem gibt es `OffsetType.XYZ` mit vertikalem Versatz (`y = (float(seed>>4 & 15)/15F − 1,0) · maxV`). Der Extraktor erkennt den Typ am Versatz bei (0,0,0) (y ≠ 0) und schreibt `maxV` nur dann.
- `ShapeRegistry` wendet den Versatz mit y an.

### 1.3 Darstellung

- **Auswahlrahmen** (`gl/HudRenderer#outline`): die Kanten jeder Box der Umrissform, schwarz mit Alpha 0,4 wie Vanilla, Tiefentest an, um 0,002 vergrößert gegen Z-Fighting. Abweichung: Vanilla führt Kanten zusammengesetzter Formen zusammen (`VoxelShape#forAllEdges`). Bei Treppen zeigt unser Rahmen daher die inneren Kanten der beiden Boxen.
- **Fadenkreuz:** eigene Geometrie (kein Mojang-Sprite), invertierende Mischung `ONE_MINUS_DST_COLOR / ONE_MINUS_SRC_COLOR` wie `Gui#renderCrosshair`. Es ist auf hellem und dunklem Grund sichtbar.
- **F3-Zeilen:** Ziel mit Block, Seite, Trefferpunkt, inside und Zustand; Reichweiten; Interaktionsstatus (Modus, Abbaufortschritt, Sequenz/Ack, offene Vorhersagen, Hotbar, Inventar).

## 2. Protokoll

| Richtung | Paket (26.3) | Felder |
|---|---|---|
| → Server | `player_action` | Aktion (VarInt-Enum: 0 START_DESTROY_BLOCK, 1 CHANGE_DESTROY_DIRECTION, 2 ABORT, 3 STOP, 4 DROP_ALL, 5 DROP_ITEM, 6 RELEASE_USE_ITEM, 7 SWAP_OFFHAND, 8 STAB), BlockPos (long), Richtung als **Byte** (3D-Wert: down 0, up 1, north 2, south 3, west 4, east 5), Sequenz (VarInt) |
| → Server | `use_item_on` | Hand (VarInt 0/1), BlockHitResult = BlockPos, Richtung (**VarInt**), Trefferpunkt relativ zum Block als 3 × float, `inside`, `worldBorderHit`; Sequenz |
| → Server | `use_item` | Hand, Sequenz, yRot, xRot (float) |
| → Server | `punch` | leer: Armschwung beim Linksklick (ersetzt in 26.x das frühere Swing-Paket) |
| → Server | `set_carried_item` | Hotbar-Platz als short |
| ← Server | `block_changed_ack` | Sequenz: alle Vorhersagen bis hierher sind verarbeitet |
| ← Server | `block_destruction` | Entity, BlockPos, Stufe (ubyte; 0–9, sonst entfernen) – Fortschritt **anderer** Spieler |
| ← Server | `block_update` / `section_blocks_update` | einzige Quelle für Blockänderungen (M4) |
| ← Server | `update_attributes` | Reichweiten und Abbauattribute (§3.3) |
| ← Server | `player_abilities`, `game_event` (3 = Spielmodus), `login`/`respawn` | instabuild, Spielmodus |

**Sequenzen:** Wie `BlockStatePredictionHandler` zählt der Client je vorhergesagter Aktion `++sequence` (erste Sequenz 1). Diese Aktionen sind START/STOP_DESTROY, `use_item_on` und `use_item`. ABORT und CHANGE_DIRECTION tragen Sequenz 0. Der Server bestätigt mit `block_changed_ack` (`ackBlockChangesUpTo`), **nachdem** er die zugehörigen Blockupdates gesendet hat.

## 3. Linksklick: Abbau

### 3.1 Ablauf je Client-Tick (`session/BlockInteraction`)

Nach `Minecraft#tick` und `handleKeybinds`, **vor** den Bewegungspaketen des Ticks:
1. `rightClickDelay--`; `ensureHasSentCarriedItem` (`MultiPlayerGameMode#tick`).
2. Auswahl mit `pick(1.0F)`.
3. Hotbar-Wechsel aus den Tasten übernehmen (gesendet beim nächsten `ensureHasSentCarriedItem`).
4. Je Klick `startAttack`, je Rechtsklick `startUseItem`, gehaltener Rechtsklick bei `rightClickDelay == 0`.
5. `continueAttack(gehalten && !instantAttack)`.
6. `missTime--`. Bei offenem Inventarfenster gilt `missTime = 10000`, Klicks werden verworfen.

| Fall | Vanilla-Logik | gesendet |
|---|---|---|
| Klick ins Leere (Überleben/Abenteuer) | `missTime = 10` | `punch` |
| Kreativ (instabuild) | `startDestroyBlock`: Vorhersage `destroyBlock`, `destroyDelay = 5`; gehalten: alle 6 Ticks der nächste Block | `player_action START` (Sequenz) + `punch`; danach `punch` je Tick |
| Überleben, sofort abbaubar (Fortschritt ≥ 1 im ersten Tick) | Vorhersage `destroyBlock` | `START` + `punch` |
| Überleben, normal | `isDestroying`, Fortschritt je Tick | `START`; je Tick `punch`; bei ≥ 1 `STOP` (Sequenz), dann `destroyDelay = 5` |
| Seite wechselt beim Abbau | `CHANGE_DESTROY_DIRECTION` | ohne Sequenz |
| Loslassen / anderes Ziel / anderes Item | `stopDestroyBlock` bzw. neuer START mit vorherigem ABORT | `ABORT` |

### 3.2 Fortschritt

`BlockBehaviour#getDestroyProgress` = `Player#getDestroySpeed / destroyTime / (richtiges Werkzeug ? 30 : 100)`; Härte −1 (Grundgestein) → 0.

Für `Player#getDestroySpeed` werden der Reihe nach angewendet:
1. Werkzeugregel (`Tool#getMiningSpeed`: erste passende Regel mit Geschwindigkeit, sonst Standard; ohne Werkzeug 1,0).
2. Liegt die Geschwindigkeit über 1, kommt das Attribut `mining_efficiency` hinzu.
3. Multiplikation mit `block_break_speed`.
4. Auge unter Wasser: Multiplikation mit `submerged_mining_speed`.
5. Nicht am Boden: Division durch 5.

„Richtiges Werkzeug“: `!requiresCorrectToolForDrops || Tool#isCorrectForDrops`.

Die Werkzeugregeln stammen aus den Datagen-Reports (`items-777.txt.gz`, docs/28). Der Effizienz-Zauber wirkt in 26.3 als Attribut-Modifikator am Spieler und kommt über `update_attributes` an. Das ist mit Effizienz V auf Obsidian geprüft (docs/29 §2).

**Server-Gegenprüfung:**
- `ServerPlayerGameMode` akzeptiert STOP, wenn `progress · (ticks + 1) ≥ 0,7` gilt. Sonst setzt er den Abbau verzögert fort.
- Die Reichweite prüft er mit 1,0 Puffer (`isWithinBlockInteractionRange(pos, 1.0)`).
- Dazu kommen Spawnschutz und `mayInteract`.

### 3.3 Attribute

`update_attributes` für die eigene Entity: `block_interaction_range` (ID 8), `entity_interaction_range` (13), `block_break_speed` (7), `mining_efficiency` (24) und `submerged_mining_speed` (34). Die IDs stammen aus dem festen Register `minecraft:attribute` (`reports/registries.json`).

### 3.4 Keine Vorhersage – unsichtbare Vorhersageliste

Vanilla entfernt beim Abbau den Block sofort lokal (`destroyBlock` in `startPrediction`). Erst die folgenden Entscheidungen hängen davon ab:
- Nach einem Kreativ-Klick ist der Block lokal Luft, also endet der Angriff (`endAttack`).
- Gehaltene Taste zielt im nächsten Tick auf den Block dahinter.
- `continueDestroyBlock` bricht auf Luft ab.

Unser Client **zeigt und speichert nur den Serverzustand**. Damit die Paketfolge trotzdem Vanilla entspricht, merkt sich `BlockInteraction` die Positionen, deren Abbau Vanilla vorhergesagt hätte (Sequenz). Diese Positionen gelten **nur für die Auswahl und die Abbauentscheidungen** als Luft, und nur bis `block_changed_ack` die Sequenz bestätigt. Danach gilt allein der Serverzustand. Diese Regel bildet `MultiPlayerGameMode#destroyBlock` nach: Ein Schwert im Kreativmodus darf nicht zerstören (`Tool#canDestroyBlocksInCreative`), also wird nichts vorgemerkt.

Die Anzeige (Auswahlrahmen im Bild) benutzt immer die Serverwelt. Zwischen Klick und Serverantwort (auf localhost < 1 Tick) zeigt der Rahmen daher noch den alten Block.

## 4. Rechtsklick: Benutzen und Platzieren

`startUseItem` (nicht während eines Abbaus; `rightClickDelay = 4`) geht für Haupt- und Nebenhand nacheinander so vor:
1. Bei einem Blocktreffer: `useItemOn`. Dabei werden `ensureHasSentCarriedItem`, `++sequence` und `use_item_on` mit Hand, BlockPos, Seite, Trefferpunkt relativ zum Block (float), `inside` und `worldBorderHit = false` ausgeführt.
2. Ist das Ergebnis Erfolg, Verbrauch oder Fehlschlag, endet der Ablauf.
3. Sonst, falls das Item nicht leer ist: `useItem` → `use_item` mit Hand, Sequenz und Blickrichtung.

Vanilla bestimmt das Ergebnis von `useItemOn` durch eine **lokale Simulation** (`performUseItemOn`: `BlockState#useItemOn`/`useWithoutItem`, dann `ItemStack#useOn`). Ohne Vorhersage ersetzt unser Client diese Simulation durch Fakten aus dem Server-JAR:

| Situation | angenommenes Ergebnis | Grundlage |
|---|---|---|
| Zuschauer | verbraucht | `performUseItemOn` |
| Item in der Hand und die Blockklasse überschreibt `useItemOn` | Erfolg | `H`-Flag `I` |
| Haupthand und die Blockklasse überschreibt `useWithoutItem` (Tür, Hebel, Truhe, Knopf …) | Erfolg | `H`-Flag `W` |
| Item ist ein `BlockItem` | Erfolg (Platzieren; Gültigkeit entscheidet der Server) | `item-blocks-777.txt.gz` (1 141 Einträge, z. B. `redstone` → `redstone_wire`) |
| sonst | PASS → `use_item`, danach Nebenhand | wie Vanilla bei PASS |

Folgen der Näherung (nicht getestet, docs/29 §5):
- Ein Block mit überschriebener Methode, der im konkreten Zustand PASS liefert, beendet bei uns die Kette früher als in Vanilla. Ein Beispiel ist Redstone-Erz.
- Bei Nicht-Block-Items (Essen, Bogen, Eimer) sendet unser Client nach `use_item` auch die Nebenhand-Pakete, weil das Ergebnis von `ItemStack#use` nicht simuliert wird.
- Schleichen („secondary use“) kennt der Client nicht.

## 5. Bedienung

| Eingabe | Grafikclient | Terminal |
|---|---|---|
| Abbauen | linke Maustaste (Klick + Halten); der Klick zum Fangen der Maus zählt nicht | `dig <ticks>` (Klick + Halten bis zur Serveränderung) |
| Benutzen/Platzieren | rechte Maustaste (Halten wiederholt alle 4 Ticks) | `use` |
| Hotbar | 1–9, Mausrad (nach oben = links) | `slot <1-9>` |
| Inventar | E (Esc schließt; sendet `container_close 0`) | `inventory` |
| Ziel / Status | F3 | `target`, `interaction`, `look-at <x y z>` |
| Auswahlvergleich | – | `pick …`, `picks <datei>` (docs/29 §1) |

**HUD** (`gl/Hud`, eigene Darstellung):
- Hotbar mit 9 Plätzen; Items als Kurzname mit Anzahl und Schaden;
- Name des gewählten Items;
- Fortschrittsbalken unter dem Fadenkreuz. Er zeigt den eigenen Abbaufortschritt; der Server meldet den eigenen Fortschritt nicht zurück;
- Inventarfenster (§28).

Texte werden nur bei Änderung neu gezeichnet (`TextPanel`), Rahmen und Balken sind einfache Rechtecke (`HudRenderer`).

## 6. Grenzen

- **Entities:** keine Entity-Auswahl, kein Angriff, kein `interact`; Entities verdecken die Blockauswahl nicht.
- **Statuseffekte:** Eile und Abbaulähmung fließen nicht in den Fortschritt ein (keine Effektpakete dekodiert). Die Anzeige läuft dann vor oder nach; der Server entscheidet trotzdem korrekt.
- **Unter Wasser:** vereinfachte Prüfung „Auge in Wasser“: Wasser, wassergeflutet, Seegras, Kelp, Blasensäule; Füllhöhe 8/9.
- **Abenteuermodus:** `can_break`/`can_place_on` werden nicht ausgewertet. Der Client verhält sich wie „Aktion eingeschränkt“.
- **Weltgrenze:** `worldBorderHit` ist immer false; Ziele außerhalb der Grenze werden nicht gefiltert.
- **Spielleiterblöcke:** Befehlsblöcke u. ä. werden nicht gesondert behandelt.
- **Rahmen:** Innenkanten zusammengesetzter Formen werden nicht zusammengeführt (§1.3).

## 7. Licht nach Blockänderungen (M7-Korrektur)

**Befund (docs/29 §3.3):**
- Nach Abbau oder Platzieren blieb das Licht im eigenen Weltmodell veraltet; eine frei gewordene Zelle hatte z. B. Himmelslicht 0 statt 15. Das zeigte sich als schwarze Fläche.
- Ursache: `ChunkHolder#broadcastChanges` sendet `light_update` nur an Spieler, für die der Chunk **am Rand der Sichtweite** liegt (`getPlayers(pos, true)`).
- Für alle anderen Chunks berechnet der Vanilla-Client das Licht selbst: `LevelChunk#setBlockState` → `skyLightSources.update` und `lightEngine.checkBlock`; ausgeführt in `runLightUpdates`.
- Unser Client hat das seit M4 nicht getan.

**Umsetzung:** `world/ClientLightEngine` ist eine eigene Umsetzung des Vanilla-Verfahrens.

| Teil | Vanilla | eigener Client |
|---|---|---|
| Auslöser | `hasDifferentLightProperties(alt, neu)`: Dämpfung, Leuchtkraft, Formverdeckung | gleich; je `block_update` sofort, je `section_blocks_update` gesammelt |
| Himmelsquellen | `ChunkSkyLightSources`: je Spalte die niedrigste y, ab der nach oben keine Kante verdeckt (`isEdgeOccluded`: Dämpfung des unteren Blocks ≠ 0 oder Seitenformen decken ab) | gleich; je Chunk beim ersten Bedarf aus den Blöcken bestimmt, danach inkrementell (`update`/`updateEdge`/`findLowestSourceBelow`) |
| Prüfknoten | Blocklicht: Leuchtkraft gegen alten Wert; Himmelslicht: Quelle (15) oder Abnahme, dazu Quellen der Spalte entfernen/ergänzen | gleich |
| Ausbreitung | Abnahme-, dann Zunahme-Warteschlange; neuer Wert = Pegel − max(1, Dämpfung); gesperrt, wenn `faceShapeOccludes` | gleich; Richtungsbits und „aus leerer Form“ wie `LightEngine.QueueEntry` |
| Speicher | nur Sektionen mit Daten (`storingLightForSection`) | nur Lichtsektionen mit empfangenen Daten; „leer“ gemeldete werden beim ersten Schreiben zu einem Array |

**Faktentabelle:** `L`-Zeilen der Formtabelle (22 235 Zustände) mit Dämpfung, Leuchtkraft und Formverdeckung. Dazu `LF`-Zeilen mit 37 verschiedenen Seitenformen als 16×16-Bedeckungsmasken. Alle Koordinaten liegen auf dem 1/16-Raster (vom Extraktor geprüft), daher ist `faceShapeOccludes(a, b)` = „(a | b) bedeckt alles“ exakt.

**Darstellung:** Das Weltmodell meldet die geänderten Sektionen (`WorldListener#lightSectionsChanged`). Der Renderer baut diese Sektionen und ihre sechs Nachbarn neu, weil der Rand eines Sektionsmeshes Licht der Nachbarzellen liest.

**Vereinfachungen** (im Abgleich ohne Auswirkung, docs/29 §3.3):
- `updateSectionStatus` (Sektion wird leer bzw. nicht leer) legt keine neuen Lichtsektionen an.
- Die Sonderbehandlung leerer Sektionen unter einem Sektionsrand (`propagateFromEmptySections`) fehlt.
- `hasLightDataAtOrBelow` gilt ab der untersten Lichtsektion der Welt.

Vom Server gesendete `light_update`-Pakete werden weiterhin übernommen.
