# 45 – Testbericht M11: Sonderbewegungen und Umgebungsphysik

**Datum:** 2026-11-14.

**Umgebung:**
- Windows 11, 16 GB RAM.
- Ausschließlich der lokale Vanilla-26.3-Server; `server-26.3.jar` ist unverändert (sha256 `a362163eec5d1612…`).
- Speichergrenzen: Server 1 GB, Vanilla-Client 1,5 GB.
- Vanilla-Läufe mit `difficulty=peaceful`. Danach wiederhergestellt: `difficulty=easy`, `network-compression-threshold=-1`, Gamerule `random_tick_speed` 3.

**Grundlagen:**
- [42](42-sonderbewegung-spezifikation.md): Sprinten, Schleichen, Posen, Attribute, Netzwerk.
- [43](43-fluid-und-kletterphysik.md): Fluide und Klettern.
- [44](44-sonderblock-physik.md): Sonderblöcke.

**Rohdaten** (gitignored, `analysis/runtime-logs/`):
- **Endstand:**
  - `m11-move-final3`, `m11-fluid-final3`, `m11-blocks-final3` (Schwelle 256);
  - `m11-move-final-t-1`, `m11-move-final-t0` (Schwellen −1 und 0);
  - Grafik: `m6-m11-gfx-final`, `m6-m11-gfx`, `m6-m11-gfx-1600`.
- **Regression:** `m11-regression.txt` mit den Schrittausgaben.
- **Zwischenstände:** `m11-move`, `m11-fluid`, `m11-blocks`, `m11-debug`, `m11-*-final`, `m11-*-final2`, `m10-m11-regress-vanilla2/3`.

**Kennzeichnung:**
- **bitgleich:** Position, Geschwindigkeit, Boden- und Kollisionsflags jedes Ticks sind mit `double` identisch zur Vanilla-Spur (|Δ| = 0).
- **[S]** serverseitig kontrolliert: `data get entity … Pos`, keine `player_position`-Korrektur.
- **[M]** Abweichung durch den Messaufbau.
- **[A]** Annahme, nicht verifiziert.

## Ergebnisübersicht

| Prüfung | Ergebnis |
|---|---|
| Sprinten und Schleichen gegen Vanilla (22 Szenarien, 1210 Ticks) | **22/22 bitgleich**, 0 Zustandsabweichungen; ebenso bei Kompressionsschwelle −1 und 0 |
| Wasser, Lava, Strömung, Schwimmen, Klettern (21 Szenarien, 2305 Ticks) | **21/21 bitgleich**, 0 Zustandsabweichungen |
| Eis, Schleim, Honig, Netz, Seelensand, Strauch, Blasensäulen, Kombinationen (22 Szenarien, 1863 Ticks) | **22/22 bitgleich**, 0 Zustandsabweichungen |
| Serverseitige Kontrolle (65 Szenarien) | **0 Positionskorrekturen**. Endposition = Server in allen Szenarien, die in Ruhe enden; 11 enden in Bewegung (treibend, prallend, Blasensäule) und sind getrennt ausgewiesen [M] |
| Netzwerk | `player_input` mit Shift-/Sprintbit, `player_command` START/STOP_SPRINTING vor dem Positionspaket; 0 externe Verbindungen |
| Grafik 1280×720 und 1600×900 | Kamerahöhe je Pose, Sicht unter Wasser und in Lava, Sprinttaste, keine Bewegung bei offenem Inventar, **0 GL-Objekte offen** |
| Unit-Tests | **344/344** auf dem Endstand; mit Integrationstests (Regressionsschritt 1): **349/349** |
| Regression M1–M10 (27 Gruppen) | **alle grün** (§8). Eine Messaufbau-Abweichung im M10-Vanilla-Vergleich ist erklärt |
| Korrigierte Szenarien `slime_fall`, `ladder_jump` | Mechanik durchlaufen und gemessen, bitgleich (§4, §3) |

## 1. Verfahren

**Werkzeuge:**
- `analysis/tools/m11-run.sh <kurs> <label> [schwelle]` startet den Server und den unveränderten Vanilla-Client. Der Fabric-Treiber `PhysicsReferenceClient` setzt Tasten über `KeyMapping` und protokolliert den `LocalPlayer` nach jedem Tick.
- Danach läuft der eigene Client (`PhysicsIntegrationTest`) dieselbe Strecke; anschließend wird der Server gestoppt und die Konfiguration wiederhergestellt.

**Kursformat:** wie M5, zusätzlich
- Tasten `s` (Schleichen) und `p` (Sprinttaste);
- Startrichtung `yaw/neigung`;
- Segmente `tasten:ticks@neigung` (Blickneigung ab diesem Tick, wie eine Mausbewegung zwischen zwei Ticks);
- `cleanup`-Zeilen.

**Je Tick verglichen:**
- Position und Geschwindigkeit;
- `onGround`, `horizontalCollision`, `verticalCollision`;
- Pose, `isSprinting`, `LocalPlayer#crouching`, Shift, `minorHorizontalCollision`, Fallstrecke, Hitbox- und Augenhöhe;
- `isInWater`, `isUnderWater`, `isSwimming`, Wasser- und Lavahöhe, `isInLava`, `onClimbable`.

**Serverkontrolle:** Endposition per `data get`; jede `player_position`-Korrektur während eines Szenarios zählt als Fehler. Szenarien, die im Fluid treibend enden, bewegen sich beim Serverabruf noch [M]. Dort zählt nur eine Korrektur, und ihre Anzahl wird ausgewiesen.

**Faktentabelle:**
- `ShapeExtractor` erzeugt aus dem unveränderten Server-JAR je Blockzustand Fluid, tragfähige Seiten, Tags (aus den Tag-JSONs des JARs), Prallfaktor und Blockklasse.
- Die bisherigen Zeilen sind unverändert (gleiche Prüfsumme).

## 2. Sprinten und Schleichen (`m11-move.txt`)

| Szenario | Prüft | Ticks | Ergebnis |
|---|---|---|---|
| sprint_key, sprint_release | Sprinttaste; Sprint hält ohne Taste, solange vorwärts | 53, 45 | bitgleich |
| sprint_doubletap, doubletap_slow | Doppeltipp innerhalb/außerhalb von 7 Ticks | 49, 50 | bitgleich |
| sprint_jump, sprint_jump_onto | Sprint-Sprung (+0,2), Sprung auf einen Block | 50, 45 | bitgleich |
| sprint_wall_stop, sprint_minor_wall, sprint_major_wall | volle Kollision beendet den Sprint, < 8° nicht | 55, 55, 45 | bitgleich |
| sprint_backward, sprint_strafe, sprint_stairs | Rückwärtstaste, diagonal, Stufen | 35, 37, 50 | bitgleich |
| sneak_walk, sneak_jump | Schleichtempo, Sprung beim Schleichen | 48, 46 | bitgleich |
| sneak_edge, sneak_edge_diag | Kantenschutz; Halt bei z = 7,2999 bzw. an zwei Kanten | 73, 88 | bitgleich |
| sneak_edge_jump | Sprung an der Kante (aufwärts kein Kantenschutz) | 81 | bitgleich |
| sneak_low_ceiling, stand_low_ceiling | 1,5 Blöcke Freiraum: Schleichpose bleibt ohne Shift; stehend blockiert | 138, 40 | bitgleich |
| sprint_sneak, sneak_sprint_key | Schleichen beendet den Sprint nicht; gleichzeitiger Druck | 48, 48 | bitgleich |
| sneak_toggle | Shift im 2-Tick-Wechsel (Pose am Tickende, Tempo einen Tick später) | 31 | bitgleich |

Summe: 1210 Ticks, 0 Zustandsabweichungen, 26 Sprint-Kommandos. Mit den Kompressionsschwellen −1 und 0 ist das Ergebnis identisch.

## 3. Fluide und Klettern (`m11-fluid.txt`)

| Szenario | Prüft | Ticks | Ergebnis |
|---|---|---|---|
| water_walk_in, water_float_jump, water_dive_sneak | Eintritt, Auftrieb (+0,04F), Abtauchen (−0,04F) | 100, 130, 100 | bitgleich |
| water_sprint_shallow | kein Sprint in seichtem Wasser | 80 | bitgleich |
| swim_sprint | Schwimmen (88 Ticks Pose SWIMMING), Neigung +20/−20/0 | 155 | bitgleich |
| dive_and_surface, deep_jump_hold | Tauchen und Auftauchen, Schwimmrichtung bei −60° | 155, 125 | bitgleich |
| water_exit, water_exit_jump | Wand im 1 Block tiefen Becken, `jumpOutOfFluid` (vy 0,3F) | 100, 100 | bitgleich |
| current_drift, current_against | Strömung in einer Rinne (Höhenstufen, Impuls 0,014) | 100, 110 | bitgleich |
| lava_walk, lava_jump, lava_deep, lava_sink | Lava seicht/tief, Auftrieb, Absinken (−g/4) | 70, 80, 110, 80 | bitgleich |
| ladder_climb, ladder_jump, vine_climb | Leiter/Ranke: 0,2 aufwärts, ±0,15 Begrenzung, Shift hält | 115, 130, 100 | bitgleich |
| scaffold_climb | Gerüst: Sprung klettert, Shift sinkt | 165 | bitgleich |
| waterlogged_slab | Wasser in einer Halbstufe (Höhe 8/9, seicht) | 110 | bitgleich |
| flow_levels | frei fließendes Wasser, alle Höhenstufen | 90 | bitgleich |

**Wesentlicher Befund beim Abgleich:** `LivingEntity#checkFallDamage` ruft außerhalb von Wasser in jedem `move` erneut `updateFluidInteraction` auf, einschließlich Strömungsimpuls.
- Ohne diesen zweiten Aufruf wich der erste Wassertick ab (`isInWater` einen Tick zu spät).
- In Lava wich die gesamte Bahn ab (Δ bis 0,54).
- Nach der Korrektur sind alle Szenarien bitgleich.

## 4. Sonderblöcke (`m11-blocks.txt`)

| Szenario | Prüft | Ticks | Ergebnis |
|---|---|---|---|
| ice_walk, ice_sprint_jump, packed_ice_walk, blue_ice_walk | Reibung 0,98 / 0,989, Ausrollen | 100, 80, 100, 110 | bitgleich |
| slime_walk, slime_jump | `stepOn`-Bremse; Prallen (Landung vy +0,3764) | 65, 80 | bitgleich |
| slime_fall, slime_fall_sneak | Sturz aus 3 Blöcken auf Schleim: Prallen bzw. mit Shift keins | 105, 85 | bitgleich |
| honey_walk, honey_jump | Faktor 0,4, Sprung 0,5 | 75, 50 | bitgleich |
| honey_wall_slide | Rutschen an der Honigwand (10 Ticks mit vy = −0,1274) | 98 | bitgleich |
| cobweb_walk, cobweb_jump | Netz (× 0,25 / 0,05F) | 100, 70 | bitgleich |
| soul_sand_walk, soul_soil_walk | Faktor 0,4 bzw. keiner | 65, 65 | bitgleich |
| berry_bush_walk | Strauch (× 0,8F / 0,75) | 80 | bitgleich |
| bubble_up, bubble_down | Blasensäulen: Deckel 1,8 bzw. −0,3/−0,9 | 100, 120 | bitgleich |
| ice_to_slime, honey_web, water_over_ice, ladder_in_water | Kombinationen | 70, 70, 65, 110 | bitgleich |

**Korrigierte Szenarien (Endstand, beide Spuren gleich):**

| Szenario | Kante überquert | Fall ab | Landung | Danach |
|---|---|---|---|---|
| slime_fall | Tick 17 | Tick 20 | Tick 28 | Rückprall, vy bis +0,5907 |
| slime_fall_sneak | Tick 17 | Tick 20 | Tick 28 | Shift: kein Prall (vy ≤ 0) |

`ladder_jump` (§3):
- erreicht die Leiter in Tick 46;
- 85 Ticks auf dem Kletterblock, davon 32 Klettertakte (vy = 0,2 − g);
- Gipfel 103,8.

In den ersten Läufen erreichten beide Szenarien Kante bzw. Leiter nicht. Sie wurden verlängert und erst danach gewertet.

**Befunde beim Abgleich:**
- **Blasensäule abwärts** (zuerst 0,06 Δ): Mehrere Säulenzellen ändern vy, die oberste ohne −0,3-Grenze. Das Ergebnis hängt von der Besuchsreihenfolge ab. Umgesetzt ist jetzt `BlockGetter#forEachBlockIntersectedBetween` mit `betweenCornersInDirection` und dem Eckpunkt-Durchlauf (`physics/BlockTraversal`).
- **Beerenstrauch** (zuerst 0,19 Δ, einzeln bitgleich) [M]:
  - Der Strauch wuchs durch Zufallsticks in den ~3 min zwischen Aufbau und Szenario auf Alter ≥ 1. Danach verursacht er serverseitig Schaden mit Geschwindigkeitspaket.
  - Abhilfe: Die Strecke setzt `random_tick_speed 0` und danach wieder 3.
  - Der Bericht weist dazu Server-Geschwindigkeitspakete je Szenario aus.

## 5. Unit- und Integrationstests (Phase I)

`SprintSneakTest`, `SpecialMovementTest`, `MovementControllerTest`:
- Gleichgewichte von Sprint und Schleichen; Doppeltipp; Nahrungsgrenze 6.
- Kantenschutz; niedrige Decke; volle und kleine Kollision.
- Fluidhöhen aller Stufen; Quelle unter festem Block (Kamerahöhe 1).
- Wassergeloggte Zustände, Seetang, Blasensäule; Falltür über Leiter.
- Ein- und Austritt; Auftrieb und Abtauchen; Schwimmpose; Lava.
- Leiter mit Fallbegrenzung und Shift-Halt; Netz und Strauch gleichzeitig.
- Schleim mit und ohne Shift; Blasensäulen-Deckel.
- Chunkgrenze bei x, z = −16; ungeladener Chunk ergibt kein Fluid; Teleport setzt die Fallstrecke zurück.
- Grenzwerte (Attributbereich, NaN, Blaueis mit Startgeschwindigkeit 3,9).
- Paketfolge `player_input` → `player_command` → Position.
- Respawn mitten im Sprint: neuer Zustand, Serverattribute bleiben erhalten.

Ergebnis: **344/344** Unit-Tests.

## 6. Grafik und Eingabe (Phase G, `m11-gfx.txt`)

Augenhöhe in beiden Fenstergrößen (1280×720, 1600×900), aus `motion-dump`:

| Situation | Ergebnis |
|---|---|
| Stehen | 1,62 |
| Schleichen (Umschalttaste) | 1,27 |
| Schwimmen | 0,4 |
| Niedrige Decke ohne Shift | 1,27 |

Die Kamera folgt der Pose mit `eye += (Ziel − eye) · 0,5F` je Tick.

| Prüfung | Ergebnis |
|---|---|
| Sprinten über die Sprinttaste (Strg) | 8,4 Blöcke in 1,5 s, `sprinting=true` |
| Unter Wasser | `waterVision` 0,26 → 0,65 nach 6 s, Wassernebel (Farbe 0x050533, Weite 96 · max(0,25; Sicht)) |
| Lava | roter Nebel 0x991A00, mit Feuerresistenz 0–5 Blöcke |
| Inventar offen und Tasten `fp` 1,5 s gedrückt | Position unverändert (0,5 / 100 / −27,5) |
| GL-Objekte bei Programmende | 0 |

Bilder: `61-sneak`, `63/64-underwater`, `65-swim`, `66-lava`, `67-low-ceiling`.

## 7. Grenzen und offene Punkte

- **Nicht umgesetzt:**
  - Elytra-Gleiten und Kreativflug;
  - Pulverschnee-Klettern mit Lederstiefeln;
  - schnelle Lava im Nether (0,007);
  - Entity-Kollisionen (Boote, Shulker);
  - `moveTowardsClosestSpace` (nur wenn der Spieler in einem erstickenden Block steckt) [A].
- **Mehrere `ADD_MULTIPLIED_TOTAL`-Modifikatoren** (z. B. Sprint + Tempo-Effekt): Vanilla multipliziert in Hash-Reihenfolge, hier gilt die Einfügereihenfolge. Das kann die letzte Stelle betreffen und ist nicht gegen Vanilla gemessen [A].
- **Schnelles Umschalten:**
  - Pose und Sprint-Flag des Servers (eigene Entity-Metadaten) werden wie in Vanilla übernommen.
  - Beim Umschalten schneller als die Paketlaufzeit hängt der Effekt von der Netzlaufzeit ab. Gemessen ist er nur bis zum 2-Tick-Wechsel (sneak_toggle bitgleich).
- **Kurse und M5-Strecke** [M]:
  - Die M5-Strecke (vom Regressionsschritt 1 neu aufgebaut) hat eine Wand bei x = 0 bis z = −4.
  - Sie überlappte den Staging-Punkt der Lava-Bahn. Der Vanilla-Client wurde aus der Wand geschoben, und zwei Lava-Szenarien blieben ohne Referenz.
  - Die M11-Kurse räumen jetzt die Reihe z = −4 mit frei. Der Endlauf ist vollständig.
- **Ruhekriterium der Serverkontrolle:** In Ruhe heißt jetzt „am Boden und die letzten beiden Ticks an derselben Position“. Vorher zählte der Bodenkontakt-Tick eines Schleimpralls als Ruhe.

## 8. Regression M1–M10 (Phase J)

`analysis/tools/m11-regression.sh`: 27 Gruppen nacheinander, vor jedem Schritt Speicherprüfung (≥ 3000 MB frei).

**Ablauf:**
- **Schritt 1:** am 2026-11-14, 11:42–11:51.
- **Abbruch:** vor Schritt 2 kontrolliert, nur 2985 MB frei; fremde Programme wurden nicht beendet.
- **Fortsetzung:** nach Speicherfreigabe (4049 MB frei, Commit 19,7/32,7 GB) mit `m11-regression.sh 2`, 12:45–13:54.
- **Schritt 1 nicht wiederholt:** Seit Schritt 1 wurden nur Kurs- und Dokumentdateien geändert, kein Clientcode.

**Rahmenbedingungen:**
- Freier Speicher während der Fortsetzung: 4,0–6,9 GB.
- Alle Schritte mit exit 0.
- In allen Schritten 0 externe Verbindungen.
- Danach `difficulty=easy`, `network-compression-threshold=-1`.

| Schritt | Gruppe | Ergebnis | Sollwert (docs/41 §9) |
|---|---|---|---|
| 1 | M1–M5 (`m5-run.sh 256`) | JUnit **349/349**; Vanilla-Physik 20 Szenarien / **896 Ticks, \|Δpos\| = 0**; M4 **657/657**, nach Entladen und Wiederherstellen 81/81 | 325 (+24 M11-Tests); gleich |
| 2 | M6 Volltest | **9/9** Positionen Client = Server, 0 GL-Objekte | gleich |
| 3, 4 | M7 Auswahl gegen Vanilla | Überleben **75/75**, Kreativ **75/75** | gleich |
| 5 | M7 Interaktion | **14/14** | gleich |
| 6 | M7 Licht | Himmel und Block je **1729/1729** | gleich |
| 7 | M7 Grafik | Serverprüfungen bestanden (Abbau, Platzieren 8 → 7, Hebel, Leck-Zyklen), 0 GL-Objekte | gleich |
| 8–13 | M8 Inventar s1–s6 | 5 + 25 + 28 + 11 + 16 + 4 = **89/89** | gleich |
| 14 | M8 Grafik | **11/11** Inventarvergleiche, 0 GL-Objekte | gleich |
| 15, 16 | M9 Netzwerk e1 / e2 | **18/18**, **21/21** | gleich |
| 17, 18 | M9 Auswahl gegen Vanilla | **185/185** je Modus | gleich |
| 19, 20 | M9 Grafik, Leistung | exit 0, 0 GL-Objekte | gleich |
| 21 | M9 Dauerbetrieb 6,5 min | 60 FPS, Heap ohne Anstieg (110–214 MB), **MATCH 21/21**, 0 GL-Objekte | Anzahl hängt von überlebenden Mobs ab |
| 22–25 | M10 k1–k4 | **16/16, 32/32, 35/35, 27/27** | gleich |
| 26 | M10 Vanilla-Vergleich | Ziele 5/5 gleich, Angriffsstärke gleich (0,16), Paketfolge: siehe unten | gleich |
| 27 | M10 Grafik | exit 0, 0 GL-Objekte | gleich |

**M9 Dauerbetrieb:** Client 21 Entities = Server 20 eingefrorene Mobs + zweiter Spieler. Alle 21 stimmen überein.

**M10 Vanilla-Vergleich, Paketfolge (Schritt 26)** [M]:
- Dem eigenen Client fehlten die ersten beiden `set_carried_item`. Alle übrigen Pakete waren gleich (Anzahl je Typ wie im M10-Endstand, darunter `player_input` 2 und Positionspakete 40/9/4/2).
- Ob diese zwei Pakete entstehen, hängt vom Hotbar-Platz ab, den der Server aus der vorigen Sitzung des Spielers gespeichert hat. Der eigene Spieler stand bereits auf Platz 1, sodass weder der Login-Abgleich noch `slot 1` einen Wechsel auslösten.
- Wiederholungen `m10-m11-regress-vanilla2` und `…3`: **Paketfolge gleich**.

**M10 Vanilla-Vergleich, Ziel t3** [M]:
- In beiden Wiederholungen meldete der Server für den **Vanilla**-Client bei Ziel t3 (Schwert ohne Abklingzeit) Gesundheit 8,73088 statt 8,608.
- Beide Clients senden dabei clientseitig identisch: Stärke 0,1600 und gleiche Paketfolge.
- Der Server rechnete für Vanilla mit s = 0,12 statt 0,20, also einen Server-Tick weniger zwischen dem Eintreffen der beiden Angriffe (docs/38: Serverstärke aus dem Server-Tick-Zähler).
- Der eigene Client liefert in allen fünf Läufen 8,608, wie im M10-Endstand und im ersten Regressionslauf auch Vanilla.
- Bewertung: Zeitjitter beim Eintreffen am Server, keine Abweichung des eigenen Clients.

**Endzustand nach allen Läufen:**
- keine Java-, Gradle- oder Serverprozesse des Projekts aktiv;
- `difficulty=easy`, `network-compression-threshold=-1`;
- Gamerules `spawn_mobs` true, `natural_health_regeneration` true, `random_tick_speed` 3 (Abfrage am lokalen Server);
- `server-26.3.jar` sha256 `a362163eec5d1612…`, `server.jar` `d052f14d7a173734…`, beide unverändert;
- in allen Läufen 0 externe TCP-Verbindungen. Der einzige gebundene UDP-Endpunkt gehört zum Gradle-Wrapper (lokale Kommunikation über Dateisperren, `-Dorg.gradle.appname=gradlew`).
