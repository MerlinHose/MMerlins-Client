# 58 – Testbericht M14 (Stand 2026-11-19): teilweise abgeschlossen

**Status: M14 ist teilweise abgeschlossen.**

- **Erledigt:**
  - Fahrzeugvergleich gegen Vanilla: 16/16 Szenarien, 1125/1125 Ticks bitgenau (§3).
  - Die drei Darstellungsfehler aus dem Bildvergleich sind behoben, der Bildvergleich ist wiederholt (§5).
  - Grafiktest mit Pferdeszene (§7), Leistung RD2/RD8 und grafischer Dauerbetrieb über 6 Minuten (§8).
  - 466 Unit-Tests sind grün.
- **Offen:**
  - Die Regression M1–M14 (§9). Claude Code hat sie zweimal wegen knappen Arbeitsspeichers abgebrochen (Schritt 1 und vorher der Grafiktest).
  - Ohne vollständige Regression gibt es keine Abschlusskennzeichnung.
- **Konsequenz:**
  - Es gibt keinen Merge nach `main`.
  - Die echte Microsoft-Authentifizierung bleibt auf den letzten Meilenstein verschoben.
  - M15 wurde nicht begonnen.

**Rahmen:** Ausschließlich der lokale Vanilla-26.3-Server auf 127.0.0.1 (Protokoll 777, `online-mode=false`).

- **Speicher:** Server mit 1 GB, Vanilla-Referenzclient mit 1,5 GB. Vor jedem Lauf wurden ≥ 3000 MB freier Speicher geprüft.
- **Netzwerk:** In allen Läufen **0 externe Verbindungen**.
- **Code und Ressourcen:** Es wurde kein Mojang-Code kopiert; Vanilla-Regeln wurden aus den Loom-Quellen gelesen. Laufzeitlogs liegen unter `analysis/runtime-logs/` (nicht versioniert).

**Kennzeichnung:**
- **[G]** gemessen bzw. automatisch geprüft.
- **[E]** begründete, nachgewiesene Abweichung.
- **[O]** offen.

## 1. Ergebnisübersicht

| Bereich | Ergebnis |
|---|---|
| Unit-Tests | **466 grün**, keiner übersprungen [G] |
| Fahrzeugvergleich gegen Vanilla (`m14-vehicle9`) | **16/16 Szenarien, 1125/1125 Ticks bitgenau**, inklusive Absteigen und je 9 Folgeticks; 0 Serverabweichungen, 0 Korrekturen; `VehicleIntegrationTest` grün [G] |
| Reproduzierbarkeit | Fahrzeugsuite und Diagnose zusammen in 13 Läufen; Vanilla selbst in bis zu 8 Wiederholungen je Szenario verglichen (§4) [G] |
| Bildvergleich (6 Entity-Szenen) | `m14-visual1`: meanΔE 6,57, meanSSIM 0,581 → nach den Korrekturen `m14-visual5`: **meanΔE 5,44, meanSSIM 0,623** (§5) [G] |
| Grafiktest `m14-gfx7` | 241/241 Schritte, **debug=0** in allen Messbildern, Feuer 3 → 0, Entladen/Neuladen 129 → 0 → 126, 4/4 Positionsprüfungen, 0 GL-Objekte (§7) [G] |
| Leistung RD2/RD8 | 59,2–60 FPS, 1 %-Low ≥ 59,2, p99 ≤ 16,9 ms bei bis zu 214 Entities (§8) [G] |
| Dauerbetrieb 6 min (RD8, 100 Entities) | 59,9 FPS je Minute, 1 %-Low ≥ 59,3, Heap ohne Anstieg, 0 GL-Objekte (§8) [G] |
| Regression M1–M14 | **nicht abgeschlossen**: Abbruch in Schritt 1 wegen Speichermangel (§9) [O] |

## 2. Testaufbau Fahrzeugvergleich

- **Ablauf:** `analysis/tools/m14-vehicle-run.sh <label>`. Der Vanilla-Referenzclient (Fabric-Forschungsmod `VehicleReferenceClient`, nur Beobachtung und Tastensimulation) und der eigene Client (`VehicleIntegrationTest`) fahren nacheinander dieselben Szenarien aus `analysis/tools/m14-vehicle-course.txt`.
- **Verglichen werden je Tick:**
  - Fahrzeugposition, Geschwindigkeit und Gier
  - `onGround` und horizontale Kollision
  - Blickrichtung und Eingabe
- **Zusätzlich verglichen:**
  - Absteige-Tick und Absteigeposition
  - Spielerposition und -geschwindigkeit in den 9 Ticks nach dem Absteigen
  - Serverposition und Servergeschwindigkeit des Fahrzeugs
- **Szenarien:**
  - Boot auf Wasser: geradeaus, Kurven, rückwärts, gegen eine Wand
  - Boot auf Eis, Boot an Land, untergetauchtes Boot
  - Floß, Kistenboot
  - Boot gegen Boot
  - Pferd: Gehen, Stufe, Sprung, gegen ein Boot
  - Lore (servergesteuert)
  - Spieler läuft gegen ein Boot
- **Diagnose:**
  - `analysis/tools/m14-vehicle-diag.txt` enthält nur die betroffenen Szenarien.
  - Eine Befehlskette (Wiederholungs-Befehlsblock) schreibt in jedem Servertick Spielzeit, Passagierzustand, Schleichzustand sowie Position und Geschwindigkeit von Boot, Spieler und Hindernis (×10⁶) ins Serverlog.
  - `analysis/tools/m14-diag-table.sh` macht daraus eine Tabelle je Servertick.
  - Damit wurde der tatsächliche Serverzustand verglichen, nicht nur die Clientsicht.

## 3. Ergebnisse und behobene Fehler

### 3.1 Behobene Clientfehler (jeweils mit Ursache aus den Vanilla-26.3-Quellen und Regressionstest)

1. **Delta-Basis des eigenen Fahrzeugs.**
   - Vanilla schreibt `VecDeltaCodec` auch für ein selbst gesteuertes Fahrzeug fort (`ClientPacketListener#handleMoveEntity`, `handleEntityPositionSync`) und setzt die Basis nur aus Paketen.
   - Unser Client verwarf die Deltas während der Fahrt und setzte die Basis auf die lokale Physikposition. Nach dem Absteigen erschien das Boot deshalb an falscher Stelle (diag1: z = 161,895 statt Server 161,677) und stieß den Spieler weg. Das war die Ursache der Serverabweichung von „2 m“.
   - Test: `PassengerAndBabyTest#deltaBaseOfTheOwnVehicleFollowsTheServerNotTheLocalPhysics`
2. **Passagierbewegung.**
   - `Entity#rideTick` setzt die Geschwindigkeit auf 0 und ruft dann `tick()` auf. Der lokale Spieler ist autoritativ und rechnet `travel` mit seiner Eingabe. `positionRider` setzt danach nur die Position.
   - Der Absteige-Teleport (`ServerPlayer#teleportTo`) ist `Relative.DELTA`, die Geschwindigkeit bleibt also erhalten.
   - Gemessen (diag4): vz = 0,017836 in der Luft, 0,01568 unter Wasser bei „vorwärts“. Unser Client setzte sie fest auf 0, deshalb glitt der Spieler nach dem Auswerfen aus dem untergetauchten Boot nicht weiter.
   - Test: `MovementControllerTest#riderKeepsTheTravelVelocityOfItsLastRideTick`
3. Aus dem vorigen Stand bestätigt:
   - Schleichtaste nach dem Absteigen
   - Status des Boots beim Aufsitzen (`primeStatus`)
   - Bootsstoß (`inflate(0.2, -0.01, 0.2)`, nach der eigenen Bewegung)
   - Tickreihenfolge beim Fahren (`tickBeforeOwnVehicle`)
   - lineare Interpolation von Booten und Loren
4. **Zurückgenommen:** Eine Änderung an `set_entity_motion` beruhte auf einer falschen Annahme. Das Paket wird beim Auswerfen gar nicht gesendet (Paketlog diag3). Die Änderung und ihr Test wurden entfernt.

### 3.2 Synchronisierter Testaufbau

| Ursache | Nachweis | Maßnahme |
|---|---|---|
| Pferd wandert nach dem Absteigen zufällig (KI) | Serverpferd erhält `set_entity_motion`, Stoß in Folgetick 10 (vehicle7) | Pferde mit `NoAI:1b`; steuerbar bleiben sie über `AbstractHorse#getControllingPassenger` |
| Skript endete vor dem Absteigen („dismount only own“) | Absteigen erst nach dem letzten Skripttick | 5 Leerticks nach `s:2` |
| Erkennungstick des Absteigens | Die Referenz lässt Tasten erst am Tickende los | Eigenes Skript verwendet in diesem Tick ebenfalls die Skripteingabe |
| Freiräumen der Strecke abgelehnt, Steindeckel über dem Becken | `fill` > 32 768 Blöcke | Freiräumen in 4 Teilen, Wasser bis y 63 |

## 4. Determinismus und zusätzliches Kriterium

Ein Teil der Szenarien hängt vom Server-Timing ab. Das zeigen auch Wiederholungen des **unveränderten Vanilla-Clients**:

- **Absteigen eines fahrenden Boots (Wettlauf):**
  - Kommt `set_passengers` erst nach Beginn des nächsten Clientticks an, fährt der Client einen Tick länger.
  - Variante A (Vanilla, diag5/diag6): Der Server hat das `move_vehicle` dieses Ticks schon angenommen (Server = Client = 185,69998760692673).
  - Variante B (eigener Client, diag7): Der Server ist vorher abgestiegen. Die Clientsicht schwenkt dann über das periodische Positionsupdate (`ServerEntity`: `tickCount % 60`) auf die Serverposition ein.
- **Auswerfen nach 60 Ticks unter Wasser:** Der Server wirft zu einem festen Servertick aus. Welcher `move_vehicle` bis dahin angekommen ist, hängt von der Paketlaufzeit ab (diag1: Bootsposition von Tick 18, diag3: Tick 19).
- **Boot gegen geschobenes Boot:**
  - Der Server bewegt das Hindernis in allen Läufen identisch (Servertabelle).
  - Wann der Client es sieht, hängt aber von der Tracker-Phase ab (`updateInterval`, `needsSync`, Delta- oder Absolutpaket).
  - Vanilla in 8 Läufen: Kontakt immer bei Tick 32, Freigabe bei 34 (1×), 36 (3×) oder 37 (4×).
  - Größte Positionsabweichung zwischen zwei Vanilla-Läufen: 0,6104.

**Zusätzliches Kriterium.** Es gilt nur für diese nachgewiesen gekoppelten Fälle; die bitgenaue Zählung bleibt unverändert und wird immer berichtet:

| Fall | Bedingungen |
|---|---|
| Absteige-Timing | Alle gemeinsamen Fahrticks bitgenau, Absteige-Tick genau 1 versetzt |
| Geschobenes Hindernis | Bis einschließlich Kontakt bitgenau, Freigabe im gemessenen Vanilla-Bereich [34, 37], Abweichung ≤ 0,6104 |
| Zusatztick ohne Annahme durch den Server | Serverposition = vorletzter Fahrtick, und die Clientsicht des Fahrzeugs schwenkt innerhalb von 4 s auf die Serverposition ein |
| Servergesteuerte Lore | Bis zur ersten Serverbewegung bitgenau; die Endposition ist von der Serverprüfung ausgenommen (Lore rollt nach dem Absteigen weiter) [E] |

Im abschließenden Lauf `m14-vehicle9` war das Kriterium **nicht nötig**: Alle 16 Szenarien waren streng bitgenau. In `vehicle7` und `vehicle8` griff es bei 3 Szenarien (Absteige-Timing, Boot gegen Boot) mit den oben genannten Nachweisen.

### Verlauf

| Lauf | Bitgenaue Szenarien | Bitgenaue Ticks | Bemerkung |
|---|---|---|---|
| vehicle4 | 11/16 | 1043/1125 | Ausgangslage |
| vehicle6 | 11/16 | 1018/1125 | Tickreihenfolge und lineare Interpolation |
| vehicle7 | 12/16 | 1089/1125 | Delta-Basis, Passagier-`travel` |
| vehicle8 | 12/16 | 1087/1125 | Pferde ohne KI, Kriterium für 3 Szenarien |
| **vehicle9** | **16/16** | **1125/1125** | Test grün |

## 5. Darstellungsfehler aus dem Bildvergleich (Ursache, Korrektur, Regressionstest)

| Fehler | Ursache (Vanilla 26.3) | Korrektur | Test |
|---|---|---|---|
| Brennende Entities ohne Feuer | Feuerdarstellung fehlte; Vanilla `FlameFeatureRenderer`: kamerazugewandte Quads, s = Breite · 1,4, Lagen je 0,45, `fire_0`/`fire_1` aus dem animierten Blockatlas, Blocklicht 15, feuerimmune Typen ausgenommen. Zusätzlich lud unser Atlas nur `fire_0` (Vanilla fordert beide Sprites an). | `FlameGeometry` und `EntityRenderer#drawFlame` mit derselben Float-Arithmetik; beide Sprites werden angefordert. Ein Absturz durch eine zu kleine Mesh-Kapazität (m14-visual4) ist behoben. | `FlameGeometryTest` (Lagen, Versatz, Größen, Erlöschen, Kapazität), `RenderingM12Test#bothFireSpritesAreInTheBlockAtlasForBurningEntities` |
| Kistenlore als Holzwürfel | Das Blockmodell `block/chest` ist leer (nur Partikeltextur); Vanilla ergänzt es über `BuiltInBlockModels` durch das Kistenmodell mit `ChestRenderer#modelTransformation`. Außerdem fehlte der typabhängige Standard-Anzeigeversatz (Kistenlore 8, Trichterlore 1, sonst 6), weil der Server Standardwerte nicht sendet. | Kistenmodell `chest#main` (Texturen normal/trapped/ender) über den vorhandenen Modellweg, Versatz je Typ | `EntityModelSystemTest` (`minecartDisplayOffset`) |
| Dorfbewohner mit hautfarbenen Armen | Ohne Beruf hat der Dorfbewohner Standarddaten; der Server sendet das Feld nicht, unser Client brach ohne Feld vor der Typschicht ab | Standard plains/none/1 (`Villager#createDefaultVillagerData`) | `EntityModelSystemTest` (ohne Feld, Wüste mit Schmied, Baby) |

### Bildvergleich `m14-visual5`

| Szene | ΔE | SSIM |
|---|---|---|
| 18 Haustiere | 5,08 | 0,633 |
| 19 Pferde, Kaninchen | 5,26 | 0,628 |
| 20 Monster (mit Feuer) | 6,34 | 0,602 |
| 21 Dorfbewohner, Nutztiere | 5,21 | 0,631 |
| 22 Humanoide (mit Feuer) | 5,56 | 0,615 |
| 23 Fahrzeuge und Passagiere | 5,19 | 0,629 |
| **Mittel** | **5,44** (vorher 6,57) | **0,623** (vorher 0,581) |

- **Szenenaufbau:** keine Drops (`mob_drops`/`entity_drops`). Die vorige Szene wird unter die Welt teleportiert und dann entfernt; liegende Reste werden vor jeder Szene entfernt. Feste Brenndauer (`Fire`) statt zufälliger Sonnenentzündung. Kamera, Blickfeld, Tageszeit und Wetter sind in beiden Clients gleich.
- **Gezielt geprüft (vergrößerte Ausschnitte):** Feuer an Spinne, Höhlenspinne und Ertrunkenen in Größe und Lagen wie Vanilla; Kiste in der Lore in Höhe, Schloss und Ausrichtung wie Vanilla; Dorfbewohner mit braunen Ärmeln.
- **Verbleibende Unterschiede [E/O]:**
  - keine Schatten unter Entities
  - keine Partikel beim Enderman
  - Animationsphase der Creeper-Ladung
  - Himmelsfarbe und Wolken: Wolken hängen an der Weltzeit, die zwischen den Läufen weiterläuft
  - Animationsphase des Feuers

## 6. Weitere Modelle (Grafiktest)

- **Debug-Boxen** traten auf bei natürlich gespawnten oder im Test beschworenen Typen ohne Plan (Tintenfisch, Lachs, Lohe, Fledermaus) und bei liegenden Gegenständen ohne gleichnamige Textur.
- **Neu:**
  - Tintenfisch und Leuchttintenfisch (Baby; Versatz aus `SquidRenderer#setupRotations`; ohne Schwimmneigung und Tentakelbewegung)
  - Kabeljau, Lachs (3 Größen), Kugelfisch (3 Stufen)
  - Tropenfisch (gepackte Variante: Muster, Grund- und Musterfarbe)
  - Lohe (ohne Stabrotation und Eigenleuchten)
  - Rückfall auf das Grundmodell `minecraft:<typ>#main` mit `entity/<typ>/<typ>` für Typen ohne eigenen Plan
  - Gegenstände: Textur über die Itemdefinition (`items/*.json`, auch Spezialmodelle), `layer0` oder die Partikeltextur
- **Tests:**
  - `EntityModelSystemTest#aquaticMobsHaveLooksInsteadOfDebugBoxes`
  - `ItemTexturesTest#everyRegisteredItemHasADrawableTexture`: jeder Gegenstand der Registry hat eine Textur

## 7. Grafiktest `m14-gfx7`

241/241 Schritte. Mindestens 29 FPS beim Szenenaufbau, sonst 59–60 FPS. 0 externe Verbindungen, 0 offene GL-Objekte; keine Ausnahme außer der erwarteten Discovery-Meldung (127.0.0.1:9).

| Prüfung | Ergebnis |
|---|---|
| Modelle, Varianten, Babys, Classic-/Slim-Skins, Skin-Ebenen | gezeichnet, `debug=0` in allen 7 Messbildern |
| Wassertiere (beschworen) | Tintenfisch, Leuchttintenfisch-Baby, Kabeljau, großer Lachs, aufgeblasener Kugelfisch, Tropenfisch groß/Muster 4/rot-gelb |
| Brennende Entities | Huhn, Schwein, Eisengolem brennen (3 Feuer), Lohe feuerimmun; nach `Fire:0s` 0 Feuer |
| Passagiere, Boote, Floß, Kistenboot, Loren, Kistenlore | Sitzpositionen und Fahrten wie in §3; Schieben der Lore |
| Pferd mit Reiter | Gehen und Sprung auf der Plattform, Entfernen während der Fahrt |
| Entladen und Neuladen | 129 → 0 → 126 Entities |
| Kollisionen | 4/4 Positionsprüfungen Client = Server (Absteigen, Pferd entfernt, Boot, Stoß durch Kuh) |

## 8. Leistung und Dauerbetrieb

Fenster 1280 × 720, VSync, RTX 3070. Messfenster je 15 s (Dauerbetrieb 60 s). „Entities“ = vom Client verfolgte Entities. Bei Renderdistanz 2 sieht der Client nur die Entities der nahen Chunks.

| Lauf | Fenster | Entities (verfolgt/gezeichnet) | Ø FPS | 1 %-Low | p99 ms | max ms | Draw Calls | Entity-CPU ms |
|---|---|---|---|---|---|---|---|---|
| RD2 | 10 / 50 / 100 | 5 / 4 / 45 (42) | 60,0 / 60,2 / 60,0 | 59,2 / 59,6 / 59,6 | ≤ 16,9 | ≤ 17,1 | 55–486 | 0,03–0,44 |
| RD8 | 0 / 10 / 50 / 100 | – / 145 / 175 / 214 (175) | 59,7 / 59,6 / 59,2 / 60,0 | ≥ 59,2 | ≤ 16,9 | 83 / 67 / 100 / 18 | 1487–2412 | 0,41–1,13 |
| Dauerbetrieb RD8, 6 × 60 s | 100 Entities | 120–224 (92–186) | 59,9 je Minute | 59,3–59,5 | ≤ 16,9 | 50–67 | 852–962 | 0,70–0,96 |

- **GPU und Heap:**
  - 0 offene GL-Objekte bei allen Läufen.
  - Entity-Meshes RD8 bei 100 Entities: 330 Meshes / 363 KB, 68 Texturen.
  - Heap im Dauerbetrieb im Sägezahn zwischen 179 und 326 MB, ohne Anstieg.
- **Frühere 52–59 FPS bei 34–50 Entities:** treten nicht mehr auf.
- **Einzelne Bilder mit 50–107 ms (1–3 je Fenster):**
  - Untersucht und **nicht** durch Entities verursacht: Sie treten auch ohne Entities auf (`entities-0`).
  - Das erstmalige Laden von Texturen und Meshes (neuer Zähler) dauerte zusammen 125 ms, höchstens 6,5 ms je Vorgang; in den Minuten 2–6 wurde nichts mehr geladen, und die Ausreißer blieben.
  - Die Häufigkeit entspricht dem M12-Grundwert (6 Bilder > 33 ms in 6 Minuten). Ursache vermutlich Garbage Collection oder Betriebssystem-Scheduling **[O]**; ohne Einfluss auf 1 %-Low und p99.
- **Optimierungen:** Keine nötig. Die Bildqualität ist unverändert, und die Vanilla-Vergleiche bleiben unberührt.

## 9. Regression und Prozessverwaltung

- **Regression M1–M14** (`analysis/tools/m14-regression.sh`, 45 Schritte einschließlich M13, M5/M11-Physikvergleichen, Fahrzeugvergleich, M14-Grafik/Leistung/Bildvergleich):
  - Gestartet am 2026-11-19 um 18:14 mit 5324 MB frei.
  - Claude Code hat den Lauf in **Schritt 1** (JUnit, M5-Physikvergleich mit Vanilla-Referenzclient) wegen knappen Arbeitsspeichers beendet. Die Projekt-JVMs erreichten bis zu 2501 MB, die freien Reserven sanken auf 2732 MB; andere Programme belegten mehrere GB.
  - **Die Regression ist damit nicht ausgeführt [O].** Neustart ab Schritt 1: `bash analysis/tools/m14-regression.sh` (fortsetzbar mit `[von] [bis]`, erfolgreiche Schrittlogs bleiben erhalten).
- **Zweiter Lauf am 2026-11-22**, gestartet mit 7312 MB frei, Commit-Auslastung 14,4/32,7 GB. Schritte 1–16 sind gelaufen [G]:

  | Schritt | Gruppe | Ergebnis |
  |---|---|---|
  | 1 | JUnit und M2–M5-Integration, Physik gegen Vanilla | 472 Tests, 0 Fehler (1 übersprungen); 20/20 Szenarien, 896/896 Ticks bitgenau, 0 Serverabweichungen |
  | 2 | M6 vollständig | 125/125 Schritte, 0 GL-Objekte |
  | 3–4 | M7 Auswahl gegen Vanilla (Überleben/Kreativ) | je 75/75 gleich |
  | 5 | M7 Interaktion | 14/14 Prüfungen |
  | 6 | M7 Licht | 1729/1729 Zellen gleich (Himmels- und Blocklicht) |
  | 7 | M7 Grafik | 308/308, 0 GL-Objekte |
  | 8–13 | M8 Inventar s1–s6 | 5/25/28/11/16/4 RESULT OK, 0 FAIL |
  | 14 | M8 Grafik | 166/166, 0 GL-Objekte |
  | 15 | M9 e1 | 17 OK, **1 FAIL**: „cow removed“. Ursache Zeitwettlauf im Testskript: Konsole (`tail -f`, bis ~1 s) + 20 Todesticks vor `remove_entities`, Abfrage nach festen 1,5 s; der Client zeigte die Kuh noch sterbend (Gesundheit 0, Pose 7), entfernt wird sofort beim Paket. Wartezeit auf 2,5 s erhöht; Schritt muss wiederholt werden [O] |
  | 16 | M9 e2 | 21 OK |

  - Claude Code hat den Lauf in **Schritt 17** (M9-Entity-Auswahl gegen Vanilla) erneut wegen Speichermangels beendet (Projekt-JVMs bis 2405 MB). Fortsetzung: `bash analysis/tools/m14-regression.sh 15` (Schritt 15 mit der Testkorrektur, dann ab 17).
- **Aufräumen:**
  - `analysis/tools/m12-cleanup.ps1` beendet jetzt auch übrig gebliebene Projekt-Gradle-Daemons. Erkannt werden sie an der Markierung `-XX:ErrorFile=mcresearch-daemon-hs_err_%p.log` aus `client/` und `research/gradle.properties`; `-D`-Eigenschaften erscheinen nicht in der Daemon-Kommandozeile.
  - Daemons anderer Projekte bleiben unberührt.
  - Beendet wird erst nach Testskripten, Test-JVMs und Server.
  - Nach jedem Abbruch: 0 Projektprozesse, Konfiguration `difficulty=easy`, `network-compression-threshold=-1`, `online-mode=false`.

## 10. Offene Prüfungen

1. Vollständige Regression M1–M14 bei ausreichend freiem Speicher (Ziel ≥ 5000 MB frei während des Laufs).
2. Danach Abschlusskennzeichnung und Merge nach `main`.
