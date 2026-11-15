# 49 – Testbericht M12: Grafikqualität und visuelle Vanilla-Kompatibilität

**Stand:** 15.11.2026, Minecraft 26.3 (Protokoll 777), Java 25, LWJGL/OpenGL 3.3, NVIDIA RTX 3070.

**Rahmen:**
- Ausschließlich der lokale Vanilla-Testserver auf 127.0.0.1.
- Netzwerkmonitor in jedem Lauf: **0 externe Verbindungen**. Ausnahme ist der absichtlich auf `127.0.0.1:9` umgeleitete Discovery-Dienst; dessen „Connection refused“ im Log ist erwartet.
- Minecraft-Assets werden zur Laufzeit aus der lokalen Installation geladen.
- Die Faktentabellen unter `client/src/main/resources/render/` enthalten nur abgeleitete Werte (Zustandsflags, Tönungsart je Block, Partikelnamen), keine Texturen.
- Vergleichsbilder liegen nur unter `analysis/runtime-logs/` (in `.gitignore`). Weder Build-Artefakte noch `docs/` enthalten Minecraft-Texturen oder Screenshots aus M12.

**Kennzeichnung:**
- **[G]** gemessen bzw. automatisch geprüft.
- **[V]** visuell an Bildern beurteilt.
- **[E]** erwartete, begründete Abweichung.
- **[O]** offen bzw. nicht geklärt.

## Ergebnisübersicht

| Bereich | Ergebnis |
|---|---|
| Visueller Vergleich gegen Vanilla, 22 Szenen | meanΔE 10,80 → **4,68**, meanSSIM 0,70 → **0,743** [G]; 10 Szenen ΔE < 4, 16 unter 6, Ausreißer klassifiziert (§3.6) |
| Ursache „Gras zu hell“ | **gefunden und behoben**: ImageIO hellt Graustufen-PNGs auf (§3.5) [G] |
| Leistung RD2/RD8 mit VSync | 60 FPS in allen Messfenstern; RD8 beim Gehen low1 53,4 FPS, p99 18,7 ms (M11: 55,0/18,2) [G] |
| Leistung ohne VSync, gleiche Bedingungen | RD2 −19 bis −26 %, RD8 −30 bis −38 % FPS gegenüber M11 (0,05 bzw. 0,25–0,36 ms je Bild; erwarteter Mehraufwand, §5) [G] |
| Grafischer Dauerlauf 6 min 8 s, RD8 | Ø 59,9 FPS, 6 Bilder > 33 ms, Heap ohne Anstieg, **MATCH 20/20** Entities [G] |
| GPU-Ressourcen | in **allen** Grafikläufen 0 offene GL-Objekte beim Beenden [G] |
| HUD-Hilfezeile bei 1280 px | **behoben**: umbrochen, breiteste Zeile 989 px [G][V] |
| In M12 gefundene und behobene eigene Fehler | Weltsperre beim Meshing (Ruckler), `uScreen`-Uniform (HUD unsichtbar), Endlosschleife im Zeilenumbruch (OOM) (§4, §5) |
| Unit-Tests | **379 grün**, davon 34 neu in M12 [G] |
| Regression M1–M11, 31 Schritte | **alle grün** nach Klärung von 3 Befunden (§8) [G] |
| Serverkonfiguration | wiederhergestellt: `difficulty=easy`, `network-compression-threshold=-1`, Gamerules (§11) [G] |

## 1. Umgesetzte Funktionen

Die Details stehen in docs/46–48; hier eine Übersicht:
- **Beleuchtung:**
  - Lightmap 16×16 nach der 26.3-Formel (Himmels-/Blocklicht, Tönung, Flackern, Gamma 0,5).
  - Weiches Licht und Ambient Occlusion wie `BlockModelLighter`.
  - Richtungsschattierung (Overworld/Nether), `shade_direction_override`, Element-Emission (docs/47).
- **Biomfarben:**
  - Biom-Zoom mit gehashtem Weltstartwert, Colormaps und Modifikatoren (Dunkelwald, Sumpf).
  - Mischung im Radius 2.
  - Tönungsquellen aus der Faktentabelle statt fester Farben.
- **Schichten:**
  - deckend, ausgestanzt, durchscheinend.
  - Durchscheinendes nach Abstand sortiert (Sektionen und Flächen), Tiefe wie Vanilla.
  - OIT bewusst nicht umgesetzt (docs/46 §4).
- **Fluide:** Geometrie mit Höhen, Strömung und Überlagerung.
- **Animierte Texturen:** aus `.mcmeta`, Upload nur der geänderten Atlasbereiche, keine Mesh-Neubauten.
- **Himmel, Zeit und Wetter vom Server:**
  - `set_time` mit Uhren, `game_event` 1/2/7/8.
  - Sonne, Mond, Sterne, Sonnenaufgangsfächer, Wolken (TBO), Regen und Schnee nach Heightmap, Biom und Temperatur.
  - Nebel mit Umgebungs- und Sichtweitenanteil.
- **Entities:** Lightmap-Licht am Augenpunkt und zwei Diffuslichter.
- **Partikel:**
  - Abbau, Kritisch, Flamme/Rauch, Tropfen, Regenspritzer.
  - Server-Partikel aus `level_particles` und `level_event` 2001, Obergrenze 4000.
- **Abbau-Overlay:** `destroy_stage`-Texturen mit Multiplikation DST·SRC.
- **HUD:** Zeilenumbruch statt Abschneiden.

## 2. Architektur

Diese Beschreibung gilt unverändert seit docs/46 §5:
- Drei Durchgänge (deckend/ausgestanzt, durchscheinend sortiert, Overlays).
- GL-Aufrufe ausschließlich im Grafikthread.
- Meshing in einem Thread-Pool auf Abschnitts-Snapshots (20³, Rand 2, Zustand + Licht + Biom).

**Änderung im Abschluss (§5):**
- Der Biom-Zoom für den Snapshot läuft jetzt **außerhalb** der Weltsperre.
- Unter der Sperre werden nur noch Zustände, Licht und 6×6×6 Quart-Biome kopiert.

## 3. Visuelle Vergleichssuite

### 3.1 Einstellungen

Der Vanilla-Referenzclient läuft unverändert und wird nur beobachtet (`research/…/ScreenshotReferenceClient`).

Gleiche Optionen in beiden Clients:
- Sichtweite 4, FOV 70, AO an, Biom-Mischung 2.
- Mipmaps 0, keine Einblendung neuer Sektionen, keine Entity-Schatten, kein Kamerawackeln.
- `improvedTransparency:false`, Wolken an, Gamma 0,5, HUD aus (F1).
- 1280×720.

Der Vanilla-Client protokolliert zu jedem Bild als Beobachtung:
- `LIGHTMAP`: Lightmap-Eingaben.
- `LIGHT`: Himmels-/Blocklicht an Auge und Boden.
- `TINT`: Biom und gemittelte Gras-/Laubfarbe am Boden.

Der eigene Client schreibt dazu `env-dump` mit Biom, Grasfarbe, Nebel und Umgebung.

### 3.2 Szenen und Ablauf

- 22 Szenen aus `analysis/tools/m12-scenes.txt`:
  - Wiese, Baumschatten, Treppen-AO, Höhle mit Fackeln, Wasser von oben und unten, Lava, Nether, Glas, Animation.
  - Regen, Gewitter, Schnee, Sonnenuntergang, Nacht.
  - Entities bei Tag und in der Höhle, Partikel, Biomgrenze, Chunkgrenze, Licht gesetzt/entfernt.
- `m12-visual.sh` baut die Szenen über die Serverkonsole auf, fotografiert erst in Vanilla, dann im eigenen Client, und stellt danach Welt und Konfiguration wieder her.
- **Neu im Abschluss:**
  - Stillstandserkennung (180 s ohne Bild → kontrollierter Abbruch des Vanilla-Clients).
  - Korrigierte Zählung der externen Verbindungen: Die Kommentarzeile „# netmon end“ wurde als Verbindung mitgezählt; tatsächlich waren alle Verbindungen Loopback.

### 3.3 Bildanalyse (`ImageCompare.java`)

| Kennzahl | Fehlerklasse |
|---|---|
| ΔE (CIE76, 320×180) und Zellen-ΔE (16×9) | Gesamt- und örtliche Farbabweichung |
| ΔL, ΔChroma | Helligkeit gegenüber Sättigung bzw. Tönung |
| SSIM | Struktur bzw. Textur |
| Kantenkorrelation | Geometrie, Kamera, Sichtbarkeit |
| Himmelsfarbe (oberste Zeile) | Himmel/Nebel getrennt vom Gelände |

### 3.4 Ergebnisse (finaler Codestand, `m12-v5`; Vanilla-Bilder aus `m12-v2`, Vanilla unverändert)

| Szene | ΔE | Zellen < 10 | ΔL | SSIM | Kanten | Bewertung |
|---|---|---|---|---|---|---|
| 01 Wiese | 4,68 | 133/144 | +0,96 | 0,72 | 0,91 | Wolkenlage zeitabhängig [E] |
| 02 Baumschatten | 3,15 | 144/144 | +0,04 | 0,74 | 0,94 | gleichwertig |
| 03 Treppen-AO | 4,03 | 144/144 | +0,01 | 0,53 | 0,72 | AO-Verlauf gleich, SSIM durch Texturkörnung [V] |
| 04 Höhle, Fackeln | 0,88 | 144/144 | −0,04 | 0,94 | 0,84 | gleichwertig |
| 05 Wasseroberfläche | 3,19 | 144/144 | −0,16 | 0,70 | 0,85 | gleichwertig |
| 06 Unter Wasser | 10,73 | 97/144 | +5,02 | 0,51 | 0,30 | Wassersicht zeitabhängig [E] |
| 07 Lava | 3,96 | 144/144 | +0,07 | 0,62 | 0,68 | Animationsphase [E] |
| 08 Nether | 6,04 | 133/144 | −0,94 | 0,52 | 0,61 | Lava-/Feueranimation [E] |
| 09 Glas | 5,74 | 126/144 | +1,23 | 0,72 | 0,94 | Himmel hinter Glas [O] |
| 10 Animation | 5,74 | 134/144 | +0,38 | 0,70 | 0,86 | Animationsphase [E] |
| 11 Regen | 8,17 | 133/144 | −0,51 | 0,72 | 0,76 | Regenstreifen zufällig [E], Himmel bläulicher [O] |
| 12 Gewitter | 7,36 | 142/144 | +0,10 | 0,74 | 0,73 | wie Regen |
| 13 Schnee | 1,17 | 144/144 | −0,20 | 0,92 | 0,79 | gleichwertig |
| 14 Sonnenuntergang | 11,33 | 88/144 | +1,19 | 0,78 | 0,84 | oberer Himmel rötlicher [O]; Wolken zeitabhängig [E] |
| 15 Nacht | 1,83 | 144/144 | +0,46 | 0,84 | 0,68 | gleichwertig |
| 16 Entities, Tag | 5,45 | 129/144 | +0,31 | 0,75 | 0,90 | Mob-Positionen/Animation [E] |
| 17 Entities, Höhle | 1,68 | 141/144 | −0,31 | 0,91 | 0,80 | gleichwertig |
| 18 Partikel | 5,75 | 131/144 | +0,38 | 0,72 | 0,82 | Partikel zufällig [E] |
| 19 Biomgrenze | 8,00 | 125/144 | +4,28 | 0,70 | 0,67 | entferntes Gelände am Rand fehlt (Sichtweitengrenze) [O] |
| 20 Chunkgrenze | 1,84 | 144/144 | +0,17 | 0,79 | 0,77 | gleichwertig |
| 21 Licht gesetzt | 1,40 | 144/144 | +0,25 | 0,85 | 0,91 | gleichwertig |
| 22 Licht entfernt | 0,87 | 144/144 | +0,16 | 0,94 | 0,88 | gleichwertig |

- **Gesamt:** meanΔE 4,68, meanSSIM 0,743.
- Ein Zwischenstand vor der Weltsperren- und Umbruchkorrektur (`m12-v4`) lag bei 4,78/0,745.
- **Ausdrücklich keine Pixelgleichheit:** Zufall (Partikel, Regen), Animationsphase, Mob-KI und Wolkendrift hängen vom Zeitpunkt ab. Die Texturkörnung senkt die SSIM auch bei gleichen Mittelwerten.

### 3.5 Befund: Gras zu hell → Graustufen-PNG-Dekodierung

**Symptom (v3):** Tagszenen waren um ΔL +4…+14 heller. Gras war im eigenen Client gleichmäßig über R, G und B um den Faktor 1/0,735 heller.

**Systematische Prüfung (docs/47 §6):**

| Stufe | Prüfung | Ergebnis |
|---|---|---|
| Lightmap-Eingaben | `LIGHTMAP` im Vanilla-Client | gleich (skyFactor 1, Umgebung 0,039, Blocktönung, Helligkeit 0,5, Gamma 0,5) |
| Lichtwerte | `LIGHT` an Auge und Boden | gleich (Himmel 15, Block 0, skyDarken 0) |
| Richtungsschattierung, Oberseite | weißer Beton senkrecht von oben | **pixelgleich** (207/213/214) |
| Biom und Grasfarbe | `TINT` gegen `env-dump` | gleich: `minecraft:river`, `#8eb971` |
| Tint-Index/Quelle | Modell `grass_block.json` (tintindex 0), `BlockTintSources` → `getAverageGrassColor` | gleich |
| Textur | Stein ohne Tönung von oben | Vanilla 126,8, eigen **186,7** → Fehler vor Tönung und Licht |
| PNG-Format | Farbtyp je Textur | `stone.png` Graustufe: Rohwert 143, ImageIO `getRGB` **197** |

- **Ursache:** Java ImageIO legt Graustufen-PNGs (589 Texturen in 26.3) in einem linearen Grau-Farbraum ab und hellt sie bei der Umrechnung nach sRGB auf. Vanilla nutzt die Rohbytes.
- **Korrektur:** `view.PngImages` gibt Graustufenbilder mit Rohwerten zurück und wird an allen Ladestellen für Assets verwendet. Ein pauschaler Helligkeitsfaktor wird nicht verwendet.

**Nachher (Diagnoseszenen `m12-diag4`):**
- Stein 126,8 / 126,8.
- Gras 75,6/108,4/58,5 gegen 75,5/108,4/58,5.
- Gras vor der Betonwand bis ΔRGB 0,4.

### 3.6 Klassifizierung der verbleibenden Abweichungen

- **[E] Zeitabhängig:**
  - Wolkendrift (Szenen 01, 14).
  - Wassersicht: steigt nach dem Eintauchen über bis zu 600 Ticks; der Aufnahmezeitpunkt unterscheidet sich (06).
  - Animationsphase (07, 08, 10), Regen- und Partikelzufall (11, 12, 18), Mob-Bewegung (16).
  - Die Implementierung ist in diesen Fällen geprüft (docs/48), die Bilder sind aber nicht gleichzeitig.
- **[O] Sonnenuntergang (14):**
  - Der obere Himmel ist im eigenen Client rötlicher (#907375 gegen #71718a).
  - Geprüft und gleich: Geometrie des Sonnenaufgangsfächers (Bytecode `SkyRenderer.buildSunriseFan/renderSunriseAndSunset`) und Nebelweite des Himmels (`min(rd·16, sky_fog_end_distance)`).
  - Vermutete Ursache in der Mischung der Nebelfarbe zur Sonne hin; nicht geklärt.
- **[O] Biomgrenze (19):**
  - Am linken Bildrand zeigt Vanilla entfernte Hügel, die im eigenen Bild fehlen.
  - Farbe, Laub und Wasser in der Bildmitte sind gleich.
  - Vermutlich ein Unterschied an der Sichtweitengrenze (welche Sektionen am Rand gezeichnet werden); nicht weiter untersucht.
- **[O] Glas, Regen:** Der Himmel hinter Glas bzw. im Regen weicht leicht im Blauanteil ab (ΔChroma ≤ 1,3).

## 4. HUD

**Hilfezeile bei 1280 px (Fehler aus M8):**
- Vorher (M11, `m6-m11-regress-m8gfx/30-inventory.png`) abgeschnitten bei „… Q werfen, E/“.
- Jetzt umbrochen, Folgezeilen um zwei Zeichen eingerückt.
- Messung mit `analysis/tools/m12-hud.txt` und neuem Autotest-Schritt `hud-dump`:
  - Inventar offen: 4 Zeilen, 1 umbrochen, breiteste Zeile **989 px** bei 1280 px Fenster.
  - Debugansicht: 19 Zeilen, 3 umbrochen, 981 px. [G][V]

**Zwei Fehler aus der M12-Entwicklung, im Abschluss gefunden und behoben:**

1. **HUD-Rechtecke unsichtbar:**
   - Betroffen waren Hotbar, Leisten, Inventarfenster, Fadenkreuz und Hand.
   - **Ursache:** Die neue Überladung `ShaderProgram.set(String, int, int)` (für `ivec2`) erhielt den bisherigen Aufruf `set("uScreen", w, h)` mit int-Werten. `glUniform2i` auf ein `vec2` schlägt fehl, `uScreen` blieb 0.
   - **Lösung:** Umbenennung in `setInt2`; nur `uLight` (EntityRenderer) verwendet sie.
   - **Nachweis:** M8-Grafiktest 166/166, Inventarbild wie M11.
2. **Endlosschleife im Zeilenumbruch:**
   - **Ursache:** Bei Folgezeilen galt die eigene Einrückung als Umbruchstelle. Ein Wort, das breiter als die Zeile ist, führte zu Stillstand, die Liste wuchs bis zum `OutOfMemoryError`.
   - **Auftreten:** Regression, M10-Grafiktest beim Todesbildschirm.
   - **Lösung:** `view.TextWrap` (reines JDK, testbar) garantiert Fortschritt und trennt ohne Umbruchstelle hart.
   - **Tests:** `TextWrapTest` (4 Tests, einer reproduziert den alten Fehler); die alte Logik läuft mit derselben Eingabe nachweislich endlos.

## 5. Leistung vor und nach M12

Gemessen wurde mit `analysis/tools/m12-perf.txt`: 15-s-Fenster an der Teststrecke und im Gelände (stehend, gehend, Neubau durch `fill`), Fenster 1280×720, GPU-Zeit per Timer-Query. Die Werte für **vorher** stammen vom M11-Code mit derselben Messinstrumentierung (Kopie `baseline-client`). Alle Vorher-Werte in den Tabellen wurden unter denselben Bedingungen wie die Nachher-Werte gemessen (systemweiter VRAM 1,8–2,1 GB). Die ersten Vorher-Messungen (`m6-m12-base-*`) liefen mit fremder GPU-Last (VRAM 3,6–3,8 GB) und wurden durch `m6-m12-base2-*` bzw. `m6-m12-base-rd8-vsync` ersetzt.

**Mit VSync (Ziel 60 FPS)** – Ø FPS / 1 %-Low / p99 ms / max ms:

| Fenster | RD2 vorher | RD2 nachher | RD8 vorher | RD8 nachher |
|---|---|---|---|---|
| Teststrecke stehend | 60,1 / 56,1 / 17,8 / 18,5 | 60,0 / 59,3 / 16,9 / 19,3 | 60,0 / 54,8 / 18,2 / 20,3 | 60,0 / 54,2 / 18,4 / 18,9 |
| Gelände stehend | 60,0 / 56,3 / 17,8 / 18,7 | 60,1 / 59,4 / 16,8 / 17,0 | 60,0 / 54,9 / 18,2 / 18,6 | 60,0 / 54,1 / 18,5 / 20,7 |
| Gelände gehend | 60,1 / 56,3 / 17,8 / 18,2 | 60,0 / 59,4 / 16,8 / 17,1 | 60,0 / 55,0 / 18,2 / 19,2 | 60,0 / 53,4 / 18,7 / 20,8 |
| Neubau (`fill`) | 60,0 / 53,4 / 18,7 / 70,4 | 60,0 / 59,3 / 16,9 / 66,8 | 59,8 / 53,8 / 18,6 / 82,4 | 59,9 / 50,7 / 19,7 / 74,9 |

**Ohne VSync, gleiche Bedingungen:**

| Fenster | RD2 vorher | RD2 nachher | RD8 vorher | RD8 nachher |
|---|---|---|---|---|
| Teststrecke stehend | 4673 FPS, p99 0,37 ms | 3775, 0,52 | 1768, 0,87 | 1231, 1,32 |
| Gelände stehend | 4949, 0,35 | 3655, 0,58 | 1790, 0,87 | 1167, 1,34 |
| Gelände gehend | 4864, 0,35 | 3775, 0,51 | 1755, 0,88 | 1081, 1,66 |
| Neubau | 5014, 0,35 | 3842, 0,54 | 1771, 0,88 | 1134, 1,59 |

**Bewertung:**
- Mit VSync wird das 60-FPS-Ziel in allen Fenstern gehalten. RD2 ist nicht schlechter als vorher (1 %-Low 59,3 gegen 56,1). RD8 liegt innerhalb von 1–2 FPS 1 %-Low und 0,5 ms p99. [G]
- Ohne VSync kostet M12 je Bild etwa 0,05 ms (RD2, −19 bis −26 % FPS) bzw. 0,25–0,36 ms (RD8, −30 bis −38 % FPS). Ursachen sind mehr Draw Calls (RD8: 594–651 → 916–1075; drei Schichten, Himmel, Wolken), GPU 0,18–0,21 → 0,26–0,28 ms, Lightmap, AO und größere Vertices. Das ist erwartet. [E]
- Die einzelne Spitze im Neubau-Fenster (`fill` mit vielen Blöcken auf einmal) gibt es vor und nach M12 (vorher 70–82 ms, nachher 67–79 ms).

**Behobener Ruckler (Weltsperre):**
- **Symptom:** Der erste Nachher-Lauf zeigte bei RD8 mit VSync beim Gehen 1 %-Low 26,4 FPS, p99 37,9 ms, max 74 ms; ohne VSync max 55 ms.
- **Ursache:** `WorldState.sectionSnapshot` berechnete den in M12 neuen Biom-Zoom (8000 Zellen) **unter** der Weltsperre. Der Grafikthread wartete auf die Meshing-Threads.
- **Lösung:** Unter der Sperre werden nur Quart-Biome kopiert, der Zoom läuft außerhalb.
- **Ergebnis:** Gehen RD8 mit VSync 53,4 / 18,7 / 20,8; ohne VSync max 6,4 ms. [G]

**Speicher:**
- Mesh-Speicher RD8: 172–196 MB → 203–230 MB (+17 %).
- Systemweiter VRAM während RD8: 2,0–2,1 GB vorher und nachher.
- Java-Heap 118–295 MB von 768 MB.

## 6. Funktionstests, Dauerlauf, Unit-Tests

**Funktionstest (`m12-func.txt`, `m6-m12-func1`):**
- Zeit und Wetter vom Server: Sonnenwinkel 85,5° bei 12 500, 180° bei 18 000, Sterne 0,5, Himmelslicht 0,24.
- Regen und Gewitter mit Nebelverkürzung, Wolken (36 875 Flächen).
- Animation: nur Atlas-Uploads, keine Mesh-Neubauten.
- Partikel: 3 Servertypen dekodiert, 1 nicht unterstützter Typ gezählt.
- Abbau-Overlay, 0 GL-Objekte. [G][V]

**Grafischer Dauerlauf (`m12-long.txt`, `m6-m12-long`, RD8, VSync):**
- 367,7 s, 22 029 Bilder, Ø 59,9 FPS, 1 %-Low 46,9, p99 21,3 ms, 6 Bilder > 33 ms, max 88 ms.
- Ablauf: Tag → Regen → Gewitter mit Sonnenuntergang → Nacht → klar.
- 20 KI-Mobs, ein zweiter Spieler läuft, bis 950 Partikel und 263 Niederschlagsspalten.
- Animationsupload 329 MB über die Laufzeit.
- Heap je Minute 195–283 MB ohne Anstieg; Draw Calls Ø 1583; Mesh-Speicher 203 MB.
- Am Ende Entities je UUID gegen den Server: **MATCH 20/20** (`m9-compare.js gfx`).
- 0 GL-Objekte offen (9363 Meshes erzeugt).
- Dieser Lauf erfolgte vor der Umbruch-Korrektur (§4), die nur die Zeilenaufteilung des Textes betrifft. [G]

**Unit-Tests:**
- 379, alle grün.
- Neu in M12 (34): `EnvironmentReferenceTest` 8, `RenderingM12Test` 11, `BiomeZoomTest` 3, `LevelEnvironmentTest` 2, `ParticleSystemTest` 5 (inkl. CME-Regression), `PngImagesTest` 1, `TextWrapTest` 4.
- Dazu `PacketCodecTest` mit M12-Paketen (`set_time`, `level_particles`, `level_event`).

## 7. GPU-Speicher und Ressourcenfreigabe

- **Ressourcenbilanz:** Jeder Grafiklauf (Funktion, Leistung, Dauerlauf, HUD, Vergleichssuite, alle Grafik-Regressionen) meldet beim Beenden `live GL objects at exit: 0` für Puffer, VAOs, Meshes, Programme, Texturen und Queries.
- **Dauerhaft belegt:**
  - Atlas 2048² RGBA (16 MB).
  - Lightmap 16×16, Wolken-TBO.
  - Himmelsmeshes, Partikel- und HUD-Puffer.
- **Variabel:** Meshes je Sektion; RD8 etwa 230 MB, wird bei Entladen sofort freigegeben (`deletes` im Profiler).

## 8. Regression M1–M11 (`analysis/tools/m12-regression.sh`)

Die Schritte liefen nacheinander mit RAM-Prüfung vor jedem Schritt; der freie Speicher lag stets bei mindestens 5486 MB. Das Skript läuft jetzt abschnittsweise im Vordergrund (`[ab] [bis]`), damit nach einem Abbruch nichts unbeaufsichtigt weiterläuft.

| # | Gruppe | Ergebnis |
|---|---|---|
| 1 | M1–M5: JUnit, Physik, M4-Welt | 380 Tests grün (375 Unit + 5 Integration, vor `TextWrapTest`); alle Kurse Δ 0; Welt 0 Abweichungen |
| 2 | M6 komplett | 125/125, alle Verify-Positionen gleich |
| 3–4 | M7 Picks Überleben/Kreativ | 75/75 gleich |
| 5 | M7 Interaktion | 14/14 |
| 6 | **M7 Licht** | **1729 Zellen, Himmel und Block 1729/1729 gleich** |
| 7 | M7 Grafik | 308/308, 0 GL-Objekte |
| 8–13 | M8 Inventar s1–s6 | 5, 25, 28, 11, 16, 4 ok, 0 fehlgeschlagen |
| 14 | M8 Grafik | 166/166 (auf finalem Code wiederholt) |
| 15–16 | M9 e1/e2 | 18 und 21 ok |
| 17–18 | M9 Entity-Picks | 185/185 gleich |
| 19–21 | M9 Grafik, Leistung, Dauerlauf | 66/66, 141/141, 126/126; Dauerlauf **MATCH 21/21** |
| 22–23 | M10 Angriffe, Schaden | 16 und 32 ok |
| 24 | M10 Nahrung/Effekte | **35/35** nach Korrektur der Vorbedingung (siehe unten) |
| 25 | M10 Tod | 27 ok |
| 26 | M10 Vanilla-Vergleich | Ziele 5/5 gleich; Paketfolge wie M11 (siehe unten) |
| 27 | M10 Grafik | 93/93 (nach Korrektur des Zeilenumbruchs, siehe unten) |
| 28–30 | M11-Kurse gegen Vanilla | move 1210, fluid 2305, blocks 1863 Ticks: **Δpos 0**, 0 Abweichungen (wie M11-Endstand) |
| 31 | M11 Grafik | 84/84, Augenhöhen 1,62/1,27/0,4 wie M11 |

**Befunde und Klärung:**

- **Schritt 24, Nahrung/Effekte:**
  - **Befund:** Der erste Lauf hatte 1 Fehler (Sättigung Client 12,8, Server 0,8), der zweite 5 Fehler.
  - **Ursache:** Der Test übernahm den gespeicherten Ernährungszustand des Spielers aus früheren Läufen. Bei 20/20 verbrauchte die Hunger-Phase nur Sättigung. Der Server sendet `set_health` nur bei Änderung von Gesundheit, Nahrung oder „Sättigung = 0“ (`ServerPlayer`: `lastSentHealth`, `lastSentFood`, `lastFoodSaturationZero`, im Bytecode geprüft). Der Client zeigte also korrekt den letzten gesendeten Wert. Bei Nahrung 20 war zudem kein Essen möglich, daraus entstanden die Folgefehler.
  - **Korrektur nur am Test (`m10/k3-food-effects.sh`):** definierte Ausgangslage per Sättigungseffekt (20/20), Hunger 5 s statt 3 s bzw. 3 s statt 2 s, sodass die Nahrung immer sinkt. Die Prüfungen sind unverändert.
  - **Ergebnis:** zweimal hintereinander 35/35.
- **Schritt 26, Paketfolge:**
  - **Befund:** Dem eigenen Client fehlen die ersten beiden `set_carried_item`; Unterschied zeichengleich mit dem M11-Regressionslauf (docs/45 §8).
  - **Einordnung:** Unser `carriedIndex` entspricht Vanillas `ensureHasSentCarriedItem` (`BlockInteraction.java:307`). Die zusätzlichen Pakete kommen vom gespeicherten Hotbar-Platz des Vanilla-Referenzspielers. Alle Angriffspakete und die Gesundheit der 5 Ziele sind gleich.
  - **Bewertung:** erklärte Abweichung im Messaufbau, nicht durch M12 verursacht. Eine Wiederholung ergab dasselbe.
- **Schritt 27 und 31, M10-Grafik und M11-Grafik:**
  - **Befund:** Der erste Lauf von 27 endete mit `OutOfMemoryError` (Endlosschleife im Umbruch, §4). Der Exit-Code der Gradle-Kette war trotzdem 0, deshalb fiel der Abbruch erst bei der Auswertung auf.
  - **Folgen:** Der Testspieler blieb tot gespeichert, und ein toter Spieler bekommt beim Login keine Chunks; deshalb scheiterten Schritt 31 und ein HUD-Test mit „no play state with meshes“. Die Abschlussbefehle des Tests (Gamerules) liefen nicht.
  - **Behebung:**
    1. Umbruchfehler korrigiert.
    2. Spieler mit `analysis/tools/m12-respawn.txt` über den eigenen Todesbildschirm wiederbelebt.
    3. Schritt 27 wiederholt: 93/93, Gamerules wieder gesetzt.
    4. Schritt 31 wiederholt: 84/84.
  - **Weitere Prüfung:** Alle Schrittausgaben wurden zusätzlich auf `Exception`, `BUILD FAILED`, `finished=false` und `RESULT FAIL` durchsucht; danach keine weiteren Fälle.
- **Fehler im Regressionsskript:** Die M11-Kurse bekamen nur `move` statt `analysis/tools/m11-move.txt`. Der erste Lauf von Schritt 28 hing deshalb und wurde kontrolliert beendet; es wurde nichts aufgebaut. Das Skript ist korrigiert, danach liefen 28–30 grün.

## 9. Bekannte Abweichungen (getestet bzw. begründet)

- Sonnenuntergang: oberer Himmel rötlicher [O] (§3.6).
- Biomgrenze: entferntes Gelände am Bildrand [O].
- Unter Wasser, Animationen, Partikel, Regen, Wolken: zeitabhängig, nicht gleichzeitig fotografierbar [E].
- Entities: Treffer-Overlay als Farbmultiplikation statt Overlay-Textur; keine Entity-Schatten (im Vergleich auch in Vanilla aus) [E] (docs/47 §5).
- Partikel: nur ein Ausschnitt der Typen. Nicht unterstützte Server-Partikel werden gezählt und nicht gezeichnet (Funktionstest: 1 von 4) [E].
- Paketfolge im M10-Vanilla-Vergleich: erklärt (§8).
- Die Leistung ohne VSync liegt unter M11 (§5) [E].

## 10. Zurückgestellte Funktionen

- **OIT** („Verbesserte Transparenz“, Wavelet-OIT): bewusst nicht umgesetzt, da Vanilla standardmäßig sortiert (docs/46 §4).
- **Block-Entities** (Truhen, Schilder, Banner) und **Item-Modelle** in der Welt bzw. Hand.
- **Weitere Partikeltypen** und Partikel aus Entity-Ereignissen.
- **Entity-Schatten**, Effekt **Dunkelheit** (Lightmap-Eingang ist vorhanden, Effekt nicht verdrahtet).
- **Temperaturmodifikator „frozen“** für Niederschlag in Sonderbiomen.
- Nachgeführte **Ursachenklärung** für Sonnenuntergang und Biomgrenze (§3.6).

## 11. Abschluss und Wiederherstellung

- **Serverkonfiguration** nach allen Läufen: `difficulty=easy`, `network-compression-threshold=-1`.
- **Gamerules:** `advance_time`, `advance_weather`, `spawn_mobs`, `natural_health_regeneration` = true, `random_tick_speed` = 3 (Abfrage am Ende, §12).
- **Original-JARs unverändert (SHA-256):**
  - `server.jar` / Loom `minecraft-server.jar` `d052f14d…`
  - `server-26.3.jar` `a362163e…`
  - `minecraft-merged-1b0498e74c-26.3.jar` `033be6d7…`
  - Loom `minecraft-client.jar` `4508d006…` (nur gelesen)
- **Neue Werkzeuge:**
  - `m12-cleanup.ps1`: beendet nur Projektprozesse, Skripte zuerst, Server über die Konsole, stellt die Konfiguration wieder her.
  - `m12-hud.txt`, `m12-long.txt`, `m12-respawn.txt`.
  - Stillstandserkennung in `m12-visual.sh`, abschnittsweise Regression.

## 12. Abschlussprüfung

Siehe Abschlussbericht im Gespräch und `analysis/runtime-logs/m12-final-check.txt`: Gamerule-Abfrage, Prozessliste, Konfiguration.
