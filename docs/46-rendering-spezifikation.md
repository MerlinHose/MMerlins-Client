# 46 – Rendering-Spezifikation M12: Bestandsaufnahme, Vanilla-Analyse und Architektur

**Datum:** 2026-11-14.

**Quellen der Analyse:**
- Dekompilierte Vanilla-26.3-Quellen über Fabric Loom (`analysis/mc-src`, nicht versioniert): u. a. `BlockModelLighter`, `ModelBlockRenderer`, `FluidRenderer`, `LightCoordsUtil`, `Lightmap`, `SkyRenderer`, `CloudRenderer`, `WeatherEffectRenderer`, `FogRenderer`, `SpriteContents`, `EnvironmentAttributeSystem`, `ClientClockManager`, `BiomeManager`, `BlockColors`.
- Shader und Datendateien des lokalen Spiel-JARs (nur gelesen): `terrain.vsh/fsh`, `lightmap.fsh`, `fog.glsl`, `oit*.glsl`, `sky.fsh`, `clouds.vsh/fsh`, `animate_sprite*.fsh`, `timeline/*.json`, `dimension_type/*.json`, `worldgen/biome/*.json`, `*.png.mcmeta`.
- Faktentabellen aus dem unveränderten JAR (Analysewerkzeuge, §5).

Verwandte Dokumente: [47](47-beleuchtung-und-materialien.md) (Licht, AO, Materialien, Biome), [48](48-transparenz-animationen-und-wetter.md) (Transparenz, Fluide, Animation, Himmel, Wetter, Partikel, Overlays), [49](49-m12-testbericht.md) (Testbericht).

## 1. Bestandsaufnahme M11 (vor M12)

| Bereich | Zustand M11 | Bewertung |
|---|---|---|
| Licht | max(Himmel, Block) je Fläche aus der Nachbarzelle, eigene Kurve `0,08 + 0,92·f/(3−2f)` | vereinfacht; keine Tageszeit, kein Blocklicht-Farbton, kein weiches Licht |
| Ambient Occlusion | fehlt | – |
| Richtungsschattierung | 1,0/0,5/0,8/0,6 je Fläche; `shade:false` schaltete sie ab | 26.3 hat `shade` entfernt, `shade_direction_override` fehlte |
| Biomfarben | eine feste Ebenen-Farbe für Gras/Laub, Wasser fest #3F76E4, Sonderfarben je Blockname im Code | hartkodiert, keine Biome |
| Schichten | nur zwei Meshes (undurchsichtig+ausgestanzt gemeinsam, durchscheinend); Transparenz je ganzer Textur; `force_translucent` nur bei nicht deckender Textur | Vanilla unterscheidet drei Schichten und misst im UV-Ausschnitt |
| Transparenz | Sektionen fern → nah, Flächen innerhalb einer Sektion unsortiert, Tiefe nicht geschrieben | Überlagerungsfehler innerhalb einer Sektion |
| Fluide | Würfel mit Höhe nach Stufe, nur `water_still`, keine Strömung, keine Eckhöhen | vereinfacht |
| Animierte Texturen | nur erstes Bild, Atlas skaliert alles auf 16×16 | fehlt |
| Himmel/Zeit/Wetter | feste Himmelsfarbe, kein `set_time`, kein Wetter | fehlt |
| Nebel | linear in xz ab 0,8·Sichtweite; Wasser-/Lavanebel aus M11 | Vanilla: kugelförmig + zylindrisch, attributgesteuert |
| Entities | Helligkeit = eigene Kurve(max(Himmel, Block)) am Körpermittelpunkt | keine Lightmap, keine Diffuslichter |
| Partikel | M10-Trefferwürfel ohne Textur | Grundlage |
| Abbau-Overlay | fehlt | – |
| HUD | Hilfezeile bei 1280 px abgeschnitten (Textbild fest 1024 px) | Fehler aus M8 |
| Nicht unterstützt | Block-Entities (Truhen, Schilder), Item-Rendering in der Welt nur als Quad, Ausrüstung teilweise | unverändert |

## 2. Vanilla-26.3-Analyse (Ergebnisse)

### 2.1 Licht und Helligkeit
- **Lichtkoordinaten** `UV2`: Block- und Himmelslicht je Stufe × 16 (0…240); weiches Licht mittelt vier Werte.
- **Lightmap** 16 × 16 per Shader, Eingaben aus Umgebungsattributen: `b(l) = l/(4 − 3l)`, Himmel × `sky_light_factor` in `sky_light_color`, Block × (1,4 + Flackern) in `mix(block_light_tint, weiß, 0,9·(2l−1)²)`, Umgebung `ambient_light_color`, Helligkeitsoption (Vorgabe 0,5) über „notGamma“.
- **Fehlende Lichtdaten:** Himmel aus der nächsthöheren Sektion mit Daten (deren unterste Zeile) bzw. 15; Block 0.

### 2.2 Ambient Occlusion und Richtungsschattierung
- AO nur bei Option an, Lichtemission 0 und `ambientocclusion` des (ersten) Modellteils.
- Schattenhelligkeit 0,2 für Blöcke mit voller Kollisionsform, sonst 1,0 (Sonderfälle Glas, Barriere, Licht …).
- Richtungsschattierung `CardinalLighting` (Standard: unten 0,5, oben 1,0, N/S 0,8, W/O 0,6; Nether: unten/oben 0,9).
- **26.3-Änderung:** Das Elementfeld `shade` gibt es nicht mehr. `shade_direction_override` legt fest, mit welcher Richtung geschattet wird (in den Blockmodellen 333 × „up“, 16 × „north“, 1 × „down“, z. B. alle `cross`-Modelle). `light_emission` je Element erhöht die Lichtkoordinaten der Fläche.

### 2.3 Biomfarben
- Tönungsquellen je Block (`BlockColors#createDefault`): Gras, Gras des Blocks darunter (obere Hälfte doppelter Pflanzen), Laub, trockenes Laub, Wasser, Konstanten (Fichte, Birke, Seerose, Melonen-/Kürbisstiel, Redstone je Stärke).
- Farbe aus `effects` des Biom-JSONs oder Colormap nach Klima; Grasmodifikatoren `dark_forest` und `swamp` (Simplex-Rauschen).
- Mischung: Mittel über (2r+1)² Spalten in gleicher Höhe (`biomeBlendRadius`, Vorgabe 2), Biom je Block über den „verwackelten“ Zoom mit dem gehashten Startwert.
- Wasserblöcke werden **nicht** über die Blocktönung gefärbt (`waterParticles`), sondern im Fluid-Renderer über die Biom-Wasserfarbe.

### 2.4 Transparenz und OIT
- Schicht je Fläche aus der Transparenz im UV-Ausschnitt über alle Animationsbilder: Alpha 0 → CUTOUT (Alphatest 0,5), Alpha 1…254 → TRANSLUCENT (Alphatest 0,1, Blending, Tiefe wird geschrieben), `force_translucent` → TRANSLUCENT.
- Ohne „Verbesserte Transparenz“ (Vorgabe **aus**) sortiert Vanilla die Flächen der Sektion nach Abstand zur Kamera.
- Mit der Option rendert Vanilla **Wavelet-OIT**: drei Durchgänge (Tiefengrenzen, Transmittanzkoeffizienten in mehreren Float-Zielen, Akkumulation) und ein Compositing.

### 2.5 Animation
- `.png.mcmeta`: `frametime` (Vorgabe 1), `frames` (Index oder {index, time}), `interpolate`, `width`/`height`.
- Je Client-Tick: Teilschritt +1, Bildwechsel nach der Bilddauer; mit `interpolate` jeden Tick `mix(aktuell, nächstes, Teilschritt/Dauer)` (Fortschritt auf 1/1000). Vanilla blittet das Ergebnis per GPU in den Atlas; Meshes ändern sich nicht.

### 2.6 Himmel, Zeit, Wetter, Nebel
- **Datengetrieben:** Dimensionsattribute → Biomattribute (Gauß-gewichtet, 6×6×6 Rasterbiome) → Zeitleisten (`day`, `moon`, `early_game` über Tag `in_overworld`) → Wetter (Regen/Gewitter mischen Himmel, Nebel, Wolken, Himmelslicht).
- **Server-Zustand:** `set_time` (Spielzeit und Weltuhren mit Rate) und `game_event` 1/2/7/8 (Regen- und Gewitterstärke). Lokal wird nur je Tick weitergezählt und zwischen Ticks interpoliert.
- **Himmel:** Scheibe in `sky_color` mit Himmelsnebel, Auf-/Untergangsfächer, Sonne und Mond (Phasen), 1500 Sterne, End-Himmel; Nether ohne Himmel (`skybox none`).
- **Wolken:** 12×4×12-Zellen aus `clouds.png`, Weite 128 Chunks, Drift 0,03 Blöcke/Tick, Ausblendung bis `cloud_fog_end_distance`.
- **Niederschlag:** Spalten im Radius 10, Art nach Biom und höhenkorrigierter Temperatur.
- **Nebel:** max(linear(kugelförmig, Umgebung), linear(zylindrisch, Sichtweite)); Luft/Wasser/Lava; Regen verkürzt den Luftnebel.

### 2.7 Entities, Partikel, Overlays
- Entity-Licht an der Augenposition (brennend: Block 15) über die Lightmap; zwei Diffuslichter (0,6/0,4).
- Partikel: kamerazugewandte Quads; Abbau 4×4×4 `TerrainParticle`; Bildfolge nach Alter.
- Abbau-Overlay: Modellflächen mit `destroy_stage_0…9`, Blending DST_COLOR·SRC_COLOR.

### 2.8 Rendering-Reihenfolge (Vanilla, klassische Transparenz)
Himmel → undurchsichtiges und ausgestanztes Terrain → undurchsichtige Features (Entities) → durchscheinende Features → durchscheinendes Terrain → Features nach dem Terrain (Partikel, Abbau) → Wolken → Niederschlag → Weltgrenze → HUD.

## 3. Übersicht: korrekt, fehlend, Fehler, Abhängigkeiten, Kosten

| Funktion | M11 | M12 | Abhängigkeit | Leistung (erwartet → gemessen in docs/49 §5) |
|---|---|---|---|---|
| Weiches Licht + AO | fehlt | umgesetzt (§47) | Render-Fakten, Snapshot mit Rand 2 | Mesh-Bau teurer (mehr Nachbarabfragen) |
| Lightmap + Tageszeit | fehlt | umgesetzt | Umgebungsattribute, `set_time` | vernachlässigbar (256 Werte je Änderung) |
| Biomtönung + Mischung | fest | umgesetzt | Biom-Zoom, Biom-JSON, Colormaps, Tönungstabelle | 25 Biomabfragen je getönter Fläche |
| Drei Schichten, sortierte Transparenz | teilweise | umgesetzt | UV-Transparenz | Neusortierung begrenzt auf 12 000 Flächen/Bild |
| OIT | fehlt | bewusst nicht umgesetzt (§4) | – | – |
| Fluidgeometrie | vereinfacht | umgesetzt | M11-Fluidfakten, Strömung | wenig |
| Animierte Texturen | erstes Bild | umgesetzt | `.png.mcmeta` | Upload nur geänderter Rechtecke |
| Himmel, Sonne, Mond, Sterne | fehlt | umgesetzt | Attribute | wenige Draw Calls |
| Wolken | fehlt | umgesetzt (TBO) | `clouds.png`, Attribute | Neubau nur bei Zellwechsel |
| Regen/Schnee | fehlt | umgesetzt | Heightmap, Biome, Temperatur | ≤ 441 Spalten/Bild |
| Entity-Licht | vereinfacht | umgesetzt | Lightmap | unverändert |
| Partikel | Würfel | umgesetzt (Ausschnitt) | Partikeltabellen | begrenzt auf 4000 |
| Abbau-Overlay | fehlt | umgesetzt | Modelle | ≤ wenige Blöcke |
| Hilfezeile 1280 px | Fehler | behoben | – | – |
| Block-Entities, Item-Modelle | fehlen | weiterhin nicht (Bereich M12 nicht verlangt) | – | – |

## 4. Entscheidung zur OIT

- **Prüfung:** Die Wavelet-OIT von 26.3 braucht mehrere RGBA16F/32F-Ziele, additive Mischung je Ziel und drei zusätzliche Durchgänge plus Compositing. Mit OpenGL 3.3 ist das umsetzbar (MRT, Float-Texturen, gleiche Mischfunktion für alle Ziele).
- **Entscheidung:** Vanilla rendert **ohne** diese Option („Verbesserte Transparenz“ standardmäßig aus) mit sortierten Flächen. Für die Vergleichbarkeit mit Vanilla-Standardeinstellungen ist das sortierte Verfahren der richtige Zielzustand.
- **Umgesetzt:** separate durchscheinende Stufe mit Sortierung der Sektionen (fern → nah) **und** der Flächen je Sektion nach Mittelpunktabstand, Tiefenschreiben wie Vanilla. Neusortierung bei Kamerabewegung um mehr als einen Block, Budget 12 000 Flächen je Bild.
- OIT bleibt als optionale Stufe verschoben (docs/49 §10). Vergleiche mit Vanilla laufen mit `improvedTransparency:false`; Pixelgleichheit wird nicht behauptet.

## 5. Architektur (Phase B)

| Verantwortung | Klasse(n) | Thread |
|---|---|---|
| Chunk-Geometrie | `view.SectionMesher` (Blöcke, Fluide), `world.SectionSnapshot` (20³-Kopie) | Mesh-Threads |
| Materialeigenschaften | `view.ResourceModels` (Modelle, UV-Transparenz, Schichten, AO-Flag, Partikelbild), `view.BlockRenderFacts` (Tabelle) | Laden / Mesh-Threads lesend |
| Licht und Sampling | `view.BlockLighter` (AO, weiches/flaches Licht), `view.Lightmap` (Formel), `gl.LightmapGl` (Textur) | Mesh-Threads / Grafikthread |
| Biomfarben | `view.env.BiomeColors`, `world.BiomeZoom`, `view.TintTable` | Mesh-Threads |
| Transparenz | `gl.ChunkRenderer` (drei Durchgänge), `view.TranslucentSort` | Grafikthread |
| Texturanimation | `view.TextureAtlas` (Bildfolge, Überblendung), `gl.TextureAtlasGl#upload` | Grafikthread je Tick |
| Umgebung | `view.env.*` (Attribute, Zeitleisten, Nebel, Niederschlag), `session.LevelEnvironment` (Server-Uhren, Wetter) | Netzwerk/Tick schreibend, Grafikthread lesend |
| Himmel und Wetter | `gl.SkyRenderer`, `gl.CloudRenderer`, `gl.WeatherRenderer` | Grafikthread |
| Entities | `gl.EntityRenderer` (Lightmap, Diffuslicht) | Grafikthread |
| Partikel | `view.ParticleSystem` (Simulation), `gl.ParticleRenderer` | Grafikthread je Tick/Bild |
| Overlays/HUD | `gl.DestroyOverlay`, `gl.Overlay` (Umbruch), `gl.Hud`, `gl.SurvivalHud`, `gl.HudRenderer` | Grafikthread |
| Pipeline und GPU-Ressourcen | `gl.GameView` (Reihenfolge), `gl.GlResources` (Zähler), `gl.FrameProfiler` | Grafikthread |

**Regeln:**
- Alle OpenGL-Aufrufe nur auf dem Grafikthread. Mesh-Threads arbeiten auf Kopien (`SectionSnapshot`) und unveränderlichen Tabellen.
- Jeder Durchgang setzt seinen GL-Zustand selbst (Tiefentest/-schreiben, Mischung, Culling, Polygonversatz) und setzt ihn am Ende zurück. Es gibt keine Abhängigkeit vom vorherigen Aufrufer.
- Reihenfolge wie §2.8: Himmel → SOLID → CUTOUT → Entities → TRANSLUCENT → Abbau-Overlay → Partikel → Wolken → Niederschlag → Auswahlrahmen → HUD.
- Jedes GL-Objekt wird über `GlResources` gezählt (Texturen, VAOs, Puffer, Programme, Abfragen); beim Beenden muss die Summe 0 sein.
- Physik, Weltverwaltung und Netzwerk bleiben unverändert. Neu sind nur lesende Zugriffe (Licht, Biome, Heightmap) und das Dekodieren rein darstellender Pakete (`set_time`, `level_particles`, `level_event`).

## 6. Neue Faktentabellen und Werkzeuge

| Datei | Werkzeug | Inhalt |
|---|---|---|
| `render/blockfacts-777.txt.gz` | `analysis/tools/RenderFactsExtractor.java` (Server-JAR) | je Zustand: solidRender, canOcclude, emissiveRendering, volle Kollision, isSolid, Heightmap-Blockade, Emission, Dämpfung, Schattenhelligkeit, Seitenformen (105 Masken), skipRendering-Regel |
| `render/tints-777.txt` | `analysis/tools/TintExtractor.java` (Client-JAR, `BlockColors#createDefault`) | Tönungsquelle je Zustand und `tintindex` (1676 Einträge) |
| `render/particles-777.txt` | `analysis/tools/ParticleTypeExtractor.java` | Netzwerk-IDs der 128 Partikeltypen |
| – | `analysis/tools/RenderMathCheck.java` | Referenzwerte (Sumpf-Rauschen, Biom-Zoom, Bézier-Kurve) für Unit-Tests |
| – | `analysis/tools/ImageCompare.java`, `m12-visual.sh`, `m12-scenes.txt`, `gen-m12-scenes.js`, `m12-own-autotest.js` | visuelle Vergleichssuite (docs/49 §3) |
| – | `research/.../ScreenshotReferenceClient.java` (`runShotRef`) | Vanilla-Bildreferenz (Beobachter, Rendering unverändert) |

Die Tabellen enthalten nur abgeleitete Fakten, keinen Minecraft-Code. Texturen, Modelle, Zeitleisten und Biome lädt der Client zur Laufzeit aus der lokalen Installation.
