# 48 – Transparenz, Fluide, Animationen, Himmel, Wetter, Partikel und Overlays (M12)

Eigene Umsetzungen nach den in [46](46-rendering-spezifikation.md) §2 analysierten Verfahren von 26.3.

## 1. Umgebungsattribute (`view.env`)

### 1.1 Daten

`EnvironmentData` liest zur Laufzeit aus dem lokalen JAR:
- `dimension_type/*.json`: Attribute, Himmelslicht, Decke, Umgebungslicht, `cardinal_light`, `skybox`, Zeitleisten-Tag;
- `worldgen/biome/*.json`: Attribute, Klima, Effekte;
- `timeline/*.json` und `tags/timeline/*.json`.

### 1.2 Attribute und Modifikatoren

`EnvAttr` umfasst 22 darstellungsrelevante Attribute mit den Vorgabewerten und Wertebereichen von 26.3, z. B. `sky_color`, `fog_color`, `sky_light_factor`, `sun_angle`, `moon_phase` und `cloud_color`.

| Modifikator | Wirkung |
|---|---|
| override | Wert ersetzen |
| add / subtract | komponentenweise, Farben auf 0…1 begrenzt |
| multiply | komponentenweise (bei ARGB auch Alpha) |
| alpha_blend | Zahl: lerp(Wert, Argument, α); RGB: lerp zur Argumentfarbe um deren Alpha; ARGB: Porter-Duff-„über“ |
| blend_to_gray | Grauwert (0,3/0,59/0,11) × Helligkeit, lerp um `factor` |
| minimum / maximum | Zahl |

### 1.3 Zeitleisten (`EnvTimeline`)

- **Spuren:** Je Attribut eine Keyframe-Spur mit Modifikator, Easing (`linear`, `constant`, `cubic_bezier`, gängige benannte Kurven) und optionaler Periode.
- **Abtastung:**
  - Die Zeit wird modulo Periode genommen.
  - Vor dem ersten und nach dem letzten Keyframe wird über die Periodengrenze interpoliert.
  - Das Argument wird nach dem Easing gemischt, dann wird der Modifikator angewendet.
- **Bézier:** Newton-Verfahren mit Bisektion als Rückfall. Gegen `EasingType` des JARs stimmt die Kurve auf 2·10⁻⁵ (Unit-Test).

### 1.4 Auswertung (`EnvironmentState`, je Client-Tick, interpoliert je Bild)

1. Vorgabe → Dimension.
2. Biom: räumlich interpoliert über Gauß-Gewichte (Kern 0,1,4,6,4,1,0) der Rasterbiome im 6×6×6-Umfeld der Kamera/4. Nicht interpolierte Attribute nehmen das Rasterbiom an der Kamera.
3. Zeitleisten der Dimension in Tag-Reihenfolge, jeweils mit der Gesamttick-Zahl ihrer Weltuhr.
4. Wetter (nur Dimensionen mit Himmelslicht, ohne Decke, nicht das Ende):
   - Regen = Regenstärke − Gewitter. Der Wert wird zuerst mit dem Regenmodifikator, dann mit dem Gewittermodifikator linear gemischt (Werte wie `WeatherAttributes`).
   - Regen: Himmel zu Grau (0,6/0,75), Nebel × (0,5; 0,5; 0,6), Himmelslicht-Faktor um 0,3125 zu 0,24, keine Sterne.
   - Gewitter: (0,24/0,94), × (0,25; 0,25; 0,3), 0,52734375.
5. Wertebereich begrenzen.

Zwischen zwei Ticks: lineare Interpolation; Winkel über den kürzeren Weg, ab einer Differenz von 90° als Sprung.

## 2. Zeit und Wetter vom Server (`session.LevelEnvironment`)

**Zeit:**
- `set_time` liefert die Spielzeit (long) und je Weltuhr (Registry `minecraft:world_clock`, ID = Reihenfolge in `registry_data`) VarLong-Gesamtticks, den Bruchteil und die Rate.
- Je Client-Tick zählen die Uhren um Δ Spielzeit × Rate weiter (`ClientClockManager`). Bei `advance_time false` sendet der Server die Rate 0.

**Wetter:**
- `game_event` 1/2/7/8 setzt die Regen- und Gewitterstärke.
- Wirksame Gewitterstärke = Gewitter × Regen.

**Neue Welt:** Beim Login oder Dimensionswechsel wird das Wetter auf 0 gesetzt, die Uhren bleiben erhalten.

Die Darstellung liest diese Werte nur. Es gibt keine lokale Echtzeit-Simulation, und nichts wird an den Server gemeldet.

## 3. Transparenz (`gl.ChunkRenderer`)

### 3.1 Durchgänge

| Durchgang | Alphatest | Mischung | Tiefe schreiben |
|---|---|---|---|
| SOLID | keiner | aus | ja |
| CUTOUT | 0,5 | aus | ja |
| TRANSLUCENT | 0,1 | SRC_ALPHA, ONE_MINUS_SRC_ALPHA (Alpha: ONE, ONE_MINUS_SRC_ALPHA) | ja (wie 26.3) |

Tiefentest LEQUAL. Jeder Durchgang setzt seinen Zustand selbst.

**Entities** zeichnen zwischen CUTOUT und TRANSLUCENT, **Partikel und Abbau-Overlay** nach TRANSLUCENT, dann **Wolken** und **Niederschlag**.

### 3.2 Sortierung

- Sektionen fern → nah.
- Flächen je Sektion nach dem Abstand ihres Mittelpunkts zur Kamera, fern → nah (`TranslucentSort`, wie `VertexSorting#byDistance`).
- **Neusortierung:** wenn sich die Kamera seit der letzten Sortierung um mehr als einen Block bewegt hat. Die nächsten Sektionen kommen zuerst, höchstens 12 000 Flächen je Bild, die Daten werden per `glBufferSubData` ersetzt.

### 3.3 OIT

Nicht umgesetzt (Begründung [46](46-rendering-spezifikation.md) §4). Es gilt das sortierte Verfahren, das auch Vanilla in der Standardeinstellung nutzt. Pixelgleichheit mit Vanilla wird nicht behauptet.

## 4. Fluide (`SectionMesher#fluid`, eigene Umsetzung von `FluidRenderer`)

**Datenquelle:** Fluidzustand aus den M11-Fakten (`BlockMovementFacts`), Strömung über das M11-`FluidModel` (nur gelesen; die Physik ist unverändert).

**Höhen:**
- Gleiches Fluid darüber → 1; sonst Menge/9.
- Fremder Nachbar: 0, solider Nachbar (`isSolid`): −1.
- Ecken werden gewichtet gemittelt: Höhen ≥ 0,8 zählen zehnfach. Bei voller Nachbar- oder Eckzelle wird die Ecke 1.

**Oberseite:**
- Gezeichnet, wenn darüber kein gleiches Fluid liegt und ein Block darüber sie nicht verdeckt.
- Alle Ecken −0,001.
- Ohne Strömung: `*_still`, sonst `*_flow` gedreht nach atan2(Strömung) − 90°.
- Rückseite, wenn in 3×3 über der Fläche fremdes, nicht solides Feld liegt.

**Seiten und Unterseite:**
- Seiten von Eckhöhe zu Unterkante, Textur `*_flow` (halbe Breite).
- Neben Glas-/Eis-/Schleim-/Honigblöcken und Laub zeigt Wasser `water_overlay` ohne Rückseite.
- Unterseite bei fremdem Fluid unten, Höhe 0,8889 gegen Verdeckung.

**Farbe und Licht:**
- Wasser in der gemischten Biom-Wasserfarbe, Lava ungetönt.
- Richtungsschattierung: oben 1,0, unten 0,5, Seiten N/S 0,8 bzw. W/O 0,6, jeweils × oben.
- Licht = Maximum aus Zelle und Zelle darüber.

**Schicht:** Wasser durchscheinend, Lava undurchsichtig.

**Unterwassersicht:** Wassernebel mit `water_fog_*`-Attributen und Wassersicht aus M11.

## 5. Nebel (`view.env.FogModel`)

| Umgebung | Farbe | Abstand |
|---|---|---|
| Luft | `fog_color`; ab Sichtweite 4 beim Blick zur Sonne mit `sunrise_sunset_color` gemischt (Anteil = Blick · Sonnenseite × Alpha), dann zur (bei Regen/Gewitter abgedunkelten) Himmelsfarbe um 1 − clamp(skyEnd/16/32 → 0,25…1)^0,25 | `fog_start/end_distance`; Regen verkürzt um bis zu −160/−256 (Himmelslicht ≥ 8, geglättet 0,2 je Tick) |
| Wasser | `water_fog_color`, aufgehellt nach Wassersicht | `water_fog_start/end_distance`, Ende × max(0,25; Wassersicht) |
| Lava | 0x991A00 | 0,25…1 (Feuerresistenz 0…5) |
| Sichtweite | – | Ende = Sichtweite·16, Beginn = Ende − clamp(Sichtweite·16/10; 4…64) |

**Shader:** max(linear(Abstand, Umgebung), linear(max(|xz|, |y|), Sichtweite)).

**Löschfarbe:** die Nebelfarbe.

**Ferne Ebene:** max(Sichtweite·64, Wolkenweite·16) wie `Camera#depthFar`.

## 6. Himmel, Wolken, Niederschlag

### 6.1 Himmel (`gl.SkyRenderer`)

Ohne Tiefe, vor dem Terrain. Die Geometrie ist eine eigene Umsetzung der Maße von `SkyRenderer`:
- **Scheibe:** Radius 512, Höhe 16, `sky_color`, Himmelsnebel bis `skyEnd`.
- **Auf-/Untergangsfächer:** 16 Segmente, Mitte (0, 100, 0) deckend, Rand transparent, gedreht nach Sonnenseite, Tiefe × Alpha.
- **Sonne und Mond:** Sonne 30 × 30, Mond 20 × 20 mit acht Phasen (Texturen `environment/celestial`), Höhe 100, gedreht um Y −90° und X um `sun_angle`/`moon_angle`, additiv mit Alpha 1 − Regen.
- **Sterne:** 1500 Kandidaten aus Startwert 10842 (gleiche Zufallsfolge wie die Vorlage), Größe 0,15…0,25, Helligkeit `star_brightness` (additiv).
- **Dunkle Scheibe:** unter der Horizonthöhe 63, nicht unter Wasser.
- **End-Himmel:** Würfel mit `end_sky.png`, Farbe 0x282828.
- **Nether:** kein Himmel (`skybox none`); nur die Nebelfarbe.

### 6.2 Wolken (`gl.CloudRenderer`)

- **Zellen:** `clouds.png` (Alpha ≥ 10), 12 × 4 × 12 Blöcke.
- **Weite:** 128 Chunks (Radius 171 Zellen), Flächen ringweise von der Mitte nach außen.
- **Flächen:**
  - Oben, wenn die Kamera nicht unter der Schicht ist; unten, wenn nicht darüber.
  - Seitenflächen zu leeren Nachbarzellen nur auf der Kameraseite.
  - Im 3×3-Umfeld der Kamera zusätzlich Innenflächen.
- **Lage:** Drift 0,03 Blöcke/Tick in +X (Spielzeit), Versatz z 3,96, Höhe `cloud_height`.
- **Farbe:** `cloud_color` × Flächenhelligkeit (unten 0,7, oben 1,0, N/S 0,8, W/O 0,9). Alpha nimmt bis `cloud_fog_end_distance` linear ab.
- **Umsetzung für OpenGL 3.3:** Die Flächenliste liegt als Texture Buffer (R16I) vor; der Vertexshader erzeugt die Ecken aus `gl_VertexID`. Die Liste wird nur beim Zellwechsel oder Lagewechsel (über/in/unter) neu gebaut.

### 6.3 Niederschlag (`gl.WeatherRenderer`, `view.env.Precipitation`)

- **Spalten:** Radius 10 um die Kamera. Unterkante max(Kamera − 10, Gelände), Oberkante max(Kamera + 10, Gelände).
- **Gelände:** Heightmap MOTION_BLOCKING, aus den aktuellen Blockzuständen berechnet (Tag `blocks_motion_in_heightmap` oder Fluid), damit Blockänderungen sofort wirken.
- **Art:**
  - Biom an (x, Kamera-y, z); kein Niederschlag → keine Spalte.
  - Temperatur < 0,15 → Schnee, sonst Regen.
  - Höhenkorrektur über Meereshöhe + 17 mit Simplex-Rauschen (Startwert 1234).
- **Ausrichtung:** Die Flächen stehen senkrecht zur Richtung Kamera→Spalte (32×32-Tabelle).
- **Textur:**
  - Regen: Versatz −(Ticks + Spaltenversatz + Teiltick)/32 × (3 + Zufall).
  - Schnee: Drift nach Gauß-Zufall.
  - Zufall je Spalte aus x²·3121 + x·45238971 ^ z²·418711 + z·13761.
- **Alpha:** 1 (Schnee 0,8) zur Mitte, 0,5 am Rand, × Regenstärke.
- **Licht:** über die Lightmap, Schnee aufgehellt (3·l + 15)/4.

## 7. Partikel (`view.ParticleSystem`, `gl.ParticleRenderer`)

### 7.1 Quellen

| Quelle | Inhalt |
|---|---|
| `level_particles` | einfache Typen und Blockzustands-Typen (Typ-IDs aus `render/particles-777.txt`). Andere Datenformate (Staub, Items, Vibration …) haben kein bestimmbares Paketende und werden übersprungen und gezählt. Verteilung: Anzahl 0 → eine Partikel mit Tempo × Streuung; sonst Gauß bzw. gleichverteilt wie `handleParticleEvent` |
| `level_event` 2001 | Abbaupartikel anderer Spieler |
| eigener Abbau | Haken in `BlockInteraction#markPredicted`, entspricht `playerWillDestroy` → Ereignis 2001 lokal |
| `animate` 4/5 | kritischer bzw. magischer Treffer, drei Ausstöße à 16 |
| Umgebung (`animateTick`-Ausschnitt) | je Tick 667 Zufallsblöcke im Umkreis 16 und 32: Fackel (Rauch + Flamme), Feuer (großer Rauch), brennendes Lagerfeuer (Rauch, 11 % je Tick), Tropfen unter Wasser führenden Decken |
| Regenspritzer | 100·Regen² Versuche im Radius 10 auf der Heightmap |

### 7.2 Simulation und Darstellung

**Simulation:**
- `Particle#tick`: Schwerkraft 0,04·g, Bewegung, Reibung, Bodenreibung 0,7.
- Arten mit den Parametern der Vorlagen: `TerrainParticle`, Kritischer Treffer, Rauch, Flamme, Puff, Regen/Spritzer, Tropfen, Lagerfeuerrauch, Blase.
- Kollision vereinfacht gegen volle Blöcke.
- Höchstens 4000 Partikel; die ältesten fallen weg.

**Darstellung:**
- Kamerazugewandte Quads.
- Bild nach Alter (`age·(n−1)/lifetime`) oder fest gewählt.
- Abbaupartikel: Viertel-Ausschnitt der `particle`-Textur des Blocks.
- Licht an der Partikelposition über die Lightmap; Flammen werden mit dem Alter selbstleuchtend.
- Durchscheinend, Tiefe geprüft, nicht geschrieben.
- Ein Stream-Puffer, zwei Draw Calls (Blockatlas, Partikelatlas).

**Nicht umgesetzt:** Partikeltypen mit anderen Datenformaten, Items, Staubfarben, Explosionen und Seelen-/Sporenpartikel.

## 8. Abbau-Overlay und HUD

**Abbau-Overlay** (`gl.DestroyOverlay`):
- Stufe = ⌊Fortschritt·10⌋ − 1 für den eigenen Abbau; für andere Spieler `block_destruction`.
- Die Modellflächen des Blocks werden mit `destroy_stage_N` gezeichnet. Die UV kommt aus der Lage auf der Flächenebene.
- Blending DST_COLOR·SRC_COLOR, Polygonversatz (−1, −10), Tiefe nicht schreibend.
- Wie in 26.3 gilt das Overlay auch für durchscheinende Blöcke über deren Modellflächen.

**HUD:**
- **M8-Fehler:** Das Textbild ist 1024 px breit; bei einer Fensterbreite von 1280 px wurde die Hilfezeile abgeschnitten.
- **Behebung:** Zeilen werden an der verfügbaren Breite an „, “ oder Leerzeichen umgebrochen, Folgezeilen eingerückt, und bei Größenänderung neu gezeichnet.
- Umsetzung in `view.TextWrap` (reines JDK, `TextWrapTest`): Jeder Schritt verbraucht mindestens ein Zeichen. Ohne passende Umbruchstelle wird hart getrennt. Die erste Fassung konnte bei einem überlangen Wort endlos laufen (docs/49 §4).
- Eingabe- und Inventarlogik sind unverändert.

## 9. Texturanimation (`view.TextureAtlas`, `gl.TextureAtlasGl`)

- **Laden:** Beim Laden einer Textur mit `.png.mcmeta` werden alle Einzelbilder gespeichert (quadratisch, Kantenlänge = Bildbreite, oder `width`/`height`).
- **Bildfolge:** Bilder `frames` mit Index und eigener Dauer, sonst alle Bilder; Standarddauer `frametime` (Vorgabe 1). Ungültige Einträge (Dauer ≤ 0, Index außerhalb) entfallen.
- **Je Client-Tick:**
  - Der Teilschritt wird erhöht; mit Erreichen der Dauer folgt das nächste Bild (Looping).
  - Geändert hochgeladen werden nur Bilder, deren Inhalt wechselt.
  - Bei `interpolate` wird jeden Tick `mix(aktuell, nächstes, Fortschritt)` berechnet, Fortschritt auf 1/1000 gerundet.
- **Upload:** nur die betroffenen Atlas-Rechtecke per `glTexSubImage2D`. Meshes bleiben unverändert; die Kosten hängen von der Zahl animierter Texturen ab (26.3: 52 Blocktexturen), nicht von der Zahl sichtbarer Blöcke.
- **Getestet:** Wasser, Lava, Feuer, Netherportal, Seelaterne, Magma (interpoliert), Prismarin (interpoliert, lange Folgen), Lagerfeuer.
- **Phase:** Vanilla startet die Animation beim Programmstart. Die Phase zwischen zwei Clients ist daher nicht gleich; der Vergleich prüft Bildfolge und Takt, nicht das momentane Bild.
