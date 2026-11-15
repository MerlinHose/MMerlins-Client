# 47 – Beleuchtung, Ambient Occlusion, Materialien und Biomfarben (M12)

Eigene Umsetzungen der in 26.3 dokumentierten Verfahren. Klassen in `client/src/main/java/dev/mcresearch/protocolclient/view` (JDK, ohne LWJGL) und `…/gl` (Grafikteil). Analyse: [46](46-rendering-spezifikation.md) §2.

## 1. Vertexformat und Lightmap

**Vertex (28 Byte):** Position (3 float), UV (2 float), Farbe RGBA8 und Licht (2 × uint16: Block, Himmel je 0…240).
- Die Farbe enthält AO × Richtungsschattierung × Tönung.
- Die Helligkeit kommt ausschließlich aus der Lightmap. Tageszeit und Wetter ändern deshalb keine Meshes.

**Shader** (`gl.Shaders.WORLD_*`):
- `vColor = aColor · texture(Lightmap, clamp(aLight/256 + 1/32; 1/32…31/32))`, wie `sample_lightmap`, Texel linear gefiltert.
- Fragment: `Textur · vColor`, Alphatest je Schicht, Nebel (§48 §5).

**Lightmap** (`view.Lightmap`, `gl.LightmapGl`, 16 × 16 auf Texturstufe 2):

| Term | Formel |
|---|---|
| Kurve | b(l) = l / (4 − 3l), l = Stufe/15 |
| Grund | max(`ambient_light_color`, `night_vision_color` · Nachtsicht) |
| Himmel | + `sky_light_color` · b(Himmel) · `sky_light_factor` |
| Block | + mix(`block_light_tint`, weiß, 0,9 · (2l − 1)²) · b(Block) · (1,4 + Flackern) |
| Abschluss | − Dunkelheit, clamp 0…1, mix(c, notGamma(c), Helligkeit 0,5) |

**Eingaben:**
- Die Attribute kommen je Client-Tick aus `EnvironmentState` (§48 §1) und werden zwischen zwei Ticks interpoliert.
- Flackern je Tick: f += (r₁ − r₂)·r₃·r₄·0,1, danach f ·= 0,9.
- Eine neue Textur wird nur hochgeladen, wenn sich ein Eingabewert ändert.

**Nicht umgesetzt:**
- Dunkelheitseffekt und Bossnebel (Dunkelheit = 0).
- Bei der Nachtsicht wird die Stärke 1 angenommen. Vanilla blendet die letzten 10 s aus.

## 2. Darstellungsfakten je Blockzustand

`render/blockfacts-777.txt.gz` stammt aus dem unveränderten Server-JAR (`RenderFactsExtractor`). Für alle 35 723 Zustände sind enthalten:
- `isSolidRender` (R), `canOcclude` (C), `emissiveRendering` (E), volle Kollisionsform (F), `isSolid` (S), Heightmap MOTION_BLOCKING (H);
- Lichtemission, Lichtdämpfung, `getShadeBrightness`;
- Verdeckungsform je Seite: `Shapes.block()`, `Shapes.empty()` oder eine 16×16-Maske (105 verschiedene);
- die Regel von `skipRendering`:
  - H: gleiches Blockobjekt (Glas, Eis, Schleim, Honig, Pulverschnee);
  - I/IT: Gitter und Scheiben;
  - R: Mangrovenwurzeln (nur Y-Achse);
  - Q: Flüssigkeit;
  - L: Laub (bei `cutoutLeaves` ohne Auslassung).

### 2.1 Flächen-Culling (`SectionMesher#shouldRender`, wie `Block#shouldRenderFace`)

Für eine Fläche mit `cullface` d:
1. Ist die Gegenseite des Nachbarn `Shapes.block()`, entfällt die Fläche.
2. Greift `skipRendering`, entfällt sie.
3. Ist die Gegenseite leer, bleibt sie.
4. Ist die eigene Seitenform leer, bleibt sie.
5. Sonst bleibt sie, wenn die eigene Seitenmaske Zellen hat, die der Nachbar nicht abdeckt (`joinIsNotEmpty(ONLY_FIRST)`).

Ein Nachbar in einem nicht geladenen Chunk gilt als Luft. Flächen ohne `cullface` werden immer gezeichnet.

### 2.2 Schichten (`ResourceModels`, wie `FaceBakery#computeMaterialTransparency` + `ChunkSectionLayer#byTransparency`)

**Transparenz der Fläche:**
- Sie wird im UV-Ausschnitt der Fläche (Pixel ⌊u₀·w⌋…⌈u₁·w⌉) über **alle verwendeten Animationsbilder** gemessen.
- Alpha 0 → CUTOUT; Alpha 1…254 → TRANSLUCENT; sonst SOLID.
- `force_translucent` (26.3-Texturobjekt `{"sprite": …, "force_translucent": true}`) → TRANSLUCENT.

**Beobachtung 26.3:** Glas (`glass.json`: `{"sprite": "minecraft:block/glass", "force_translucent": true}`) und weitere Blöcke mit dieser Kennzeichnung liegen jetzt in TRANSLUCENT, obwohl die Textur nur Alpha 0/255 hat (191 transparente, 65 deckende Pixel). Bis M11 lag Glas in der ausgestanzten Schicht. Die neue Zuordnung folgt allein aus den Daten.

**Atlas:** Er ist neu, 2048², nach Regalverfahren gepackt. Texturen behalten ihre Originalgröße; M11 verkleinerte alle auf 16×16.

### 2.3 Modellformat 26.3

| Feld | Umsetzung |
|---|---|
| `shade` | entfällt in 26.3 (0 Vorkommen in den Blockmodellen); Richtungsschattierung immer aktiv |
| `shade_direction_override` | Richtung für die Schattierung; mit der Variantenrotation gedreht (`RenderQuad#shadeOverride`) |
| `light_emission` (Element) | Lichtkoordinaten der Fläche mindestens diese Stufe (Himmel und Block, `lightCoordsWithEmission`) |
| `ambientocclusion` | erster Wert in der Elternkette, Vorgabe true; bei Multipart der erste angewandte Teil |
| entartete Elemente | Ausdehnung 0 in einer Achse → nur Flächen senkrecht zu ihr (`UnbakedCuboidGeometry`) |
| Texturvariable `particle` | Partikelbild je Zustand (Abbaupartikel) |

### 2.4 Positionsversatz

Blumen, Gras, Bambus u. a. werden mit dem Versatz gerendert, den auch Kollision und Umriss nutzen (`ShapeRegistry#modelOffset`, `OffsetType` XZ/XYZ, positionsabhängiger Startwert).

## 3. Licht je Vertex (`view.BlockLighter`)

### 3.1 Lichtabfrage

Die Sektionskopie `world.SectionSnapshot` hat 20³ Zellen (Rand 2), weil AO bis zu zwei Zellen über die Sektionsgrenze schaut. Sie liefert Himmels- und Blocklicht getrennt nach `Level#getBrightness`:
- Fehlende Himmelsdaten kommen aus der nächsthöheren Sektion mit Daten (deren unterste Zeile) bzw. werden 15.
- Fehlendes Blocklicht ist 0.
- Ein nicht geladener Chunk liefert Himmel 15 und Block 0.

Das serverkompatible M7-Licht im Weltmodell (`ClientLightEngine`) bleibt unverändert; der Mesher liest es nur.

**Lichtkoordinaten eines Zustands an einer Position:**
- `emissiveRendering` → voll.
- Sonst ist das Blocklicht mindestens die eigene Emission.

### 3.2 Flach (ohne AO)

**Licht:**
- Fläche mit `cullface` → Licht der Nachbarzelle (mit der Emission des eigenen Zustands).
- Fläche ohne `cullface` → Nachbarzelle, wenn die Fläche auf der Blockgrenze liegt oder der Block voll ist, sonst die eigene Zelle.

**Farbe:** grau(Richtungsschattierung).

### 3.3 Ambient Occlusion mit weichem Licht

**Bedingungen:** AO-Option an (Vorgabe wie Vanilla), Lichtemission des Zustands 0 und Modell-AO an.

**Ablauf je Fläche mit Richtung D:**
1. **Messzelle:** Liegt die Fläche auf der Blockgrenze („kubisch“), oder hat der Block eine volle Kollisionsform, wird in P + D gemessen, sonst in P.
2. **Seiten:** Die vier Seiten der Fläche kommen aus der Vorlagentabelle; beim Deckel sind es Ost, West, Nord, Süd. Je Seite werden Licht und Schattenhelligkeit der Nachbarzelle bestimmt, dazu ob die Zelle dahinter lichtdurchlässig ist (`!isSolidRender || Dämpfung 0`).
3. **Eckzellen:**
   - Die diagonale Eckzelle zählt nur, wenn mindestens eine der beiden Seiten lichtdurchlässig ist.
   - Sonst wird der Wert der **Seite 0** verwendet. Das gilt wie in der Vorlage auch für die Ecken 1/2 und 1/3.
4. **Mitte:**
   - Licht: Normalerweise das Licht der eigenen Zelle. Bei kubischer Fläche oder nicht solidem Nachbarn in Richtung D gilt das Licht der Nachbarzelle P + D.
   - Schatten: bei kubischer Fläche die Messzelle, sonst die eigene Zelle.
5. **Eckwerte:**
   - Schatten = (Seite a + Seite b + Ecke + Mitte)/4.
   - Licht = `smoothBlend`: Sind Mitte-Himmel oder Mitte-Block über 2, ersetzt die Mitte Nachbarn mit Licht 0, und bei Himmel 0 wird der Himmelswert der Mitte übernommen.
6. **Vertices:**
   - Volle Fläche: Eckwert der Ecke.
   - Teilfläche: bilinear nach den Flächengrenzen (Gewichte wie die Vorlagentabelle `vert*Weights`). Das Licht wird je Komponente gewichtet (Ganzzahl).
7. **Farbe:** grau(Schatten) × Richtungsschattierung, ganzzahlig wie `ARGB#gray`/`scaleRGB`.

**Diagonale:** Vanilla zeichnet Quads und teilt sie entlang der Vertices 0–2 der `FaceInfo`-Reihenfolge. In unserer Eckenreihenfolge ist das die Diagonale 1–3 für alle sechs Seiten (`MeshBuilder#quad(…, true)`). Ohne diese Wahl liefe die AO-Interpolation spiegelverkehrt.

### 3.4 Richtungsschattierung

`CardinalLighting` der Dimension: Standard bzw. Nether (`cardinal_light: nether` im Dimensionstyp). Die Richtung ist `shade_direction_override` oder die Flächenrichtung nach der Variantenrotation. Element-Drehungen um 22,5°/45° behalten die Flächenrichtung, so wie Vanilla die nächstliegende Achse nimmt.

## 4. Biomfarben

### 4.1 Daten

Zur Laufzeit lädt `view.env.EnvironmentData` aus dem lokalen JAR:
- `worldgen/biome/*.json`: Temperatur, Niederschlag, `effects` (Wasser-, Gras-, Laub-, Trockenlaubfarbe, Grasmodifikator) und Attribute;
- die Colormaps `grass.png`, `foliage.png`, `dry_foliage.png`.

### 4.2 Biom je Block

- **Zoom:** `world.BiomeZoom` setzt das „verwackelte“ Voronoi-Verfahren um. Von acht Rasterpunkten gewinnt der mit dem kleinsten Abstand nach pseudozufälliger Verschiebung (lineare Kongruenz mit dem gehashten Startwert aus `login`/`respawn`).
- **Rasterbiom:** Es kommt aus den Chunkdaten. Ein nicht geladener Chunk zählt als Ebene; y wird auf die Welthöhe begrenzt.
- **Referenz:** Die Abstandsfunktion stimmt auf 1e−9 mit `BiomeManager#getFiddledDistance` überein (Unit-Test, Referenzwerte aus `RenderMathCheck`).
- **Sektionskopie:** Sie enthält das gezoomte Biom je Zelle (20³).

### 4.3 Tönungsquellen

`render/tints-777.txt` entsteht aus `BlockColors#createDefault` des unveränderten Client-JARs (`TintExtractor`). Dabei wird jede Quelle mit einem Proben-Getter ausgewertet, der die Farbauflöser unterscheidet.

| Art | Farbe |
|---|---|
| `grass` | Gras-Biomfarbe an der Position |
| `grass_below` | Gras-Biomfarbe eine Zelle tiefer (obere Hälfte von Farn/hohem Gras) |
| `foliage`, `dry_foliage` | Laub bzw. trockenes Laub |
| `water` | Wasserfarbe (Wasserkessel) |
| `constant` | Farbe der Vorlage je Zustand (Fichte 619961, Birke 80a755, Seerose, Stiele je Alter, Redstone je Stärke) |
| `none` | weiß (Wasserblock und Blasensäule: Tönung erst im Fluid-Renderer) |

Im Code steht keine Farbe pro Blocktyp.

### 4.4 Farbe und Mischung

- **Colormap:** t = clamp(Temperatur), r = clamp(Niederschlag)·t, Pixel (⌊(1−t)·255⌋, ⌊(1−r)·255⌋).
- **Vorrang:** Eine Farbe in `effects` geht der Colormap vor.
- **Dunkelwald:** ((c & FEFEFE) + 28340A) » 1.
- **Sumpf:** 2D-Simplex-Rauschen, Startwert 2345, Permutation wie die Vorlage, an (x·0,0225, z·0,0225). Bei < −0,1 gilt 4C763C, sonst 6A7039. Auf 1e−6 gleich `Biome.BIOME_INFO_NOISE` (Unit-Test).
- **Mischung:** Mittel der RGB-Werte (ganzzahlig) über (2r+1)² Spalten in gleicher Höhe, r = 2 (Vanilla-Vorgabe `biomeBlendRadius`). Die Sektionskopie erlaubt r ≤ 2; größere Radien sind nicht unterstützt.
- **Vertikale Biome:** Das Biom wird je Block mit dessen y gezoomt.
- **Biom-Updates:** `chunks_biomes` ersetzt die Biome. Die Sektionen werden über den Weltlistener neu gebaut (M4-Mechanik).

## 5. Entities

`gl.EntityRenderer`:
- **Licht:** Lichtkoordinaten an der Augenposition (Höhe · 0,85, `getLightProbePosition`); brennend: Blocklicht 15. Die Lightmap ist dieselbe wie beim Terrain. Damit wirken Tageszeit, Höhlen und Wasser auf Entities wie auf Blöcke.
- **Diffuslicht:** (0,2; 1; −0,7) und (−0,2; 1; 0,7) normiert (Nether: zweites nach unten), Summe der positiven Skalarprodukte · 0,6 + 0,4, höchstens 1.
- **Normale:** flach je Fläche aus den Bildschirmableitungen der Weltposition, zur Kamera gewandt. Unsere Entity-Meshes haben keine Normalen; für sichtbare Flächen entspricht das der Außennormale.
- **Übergänge:** Das Licht wird je Bild an der interpolierten Position gemessen, also blockweise, wie Vanilla.
- **Nebel:** wie beim Terrain.
- **Ausrüstung:** Rüstungsteile werden mit demselben Licht gezeichnet.
- **Verbleibende Unterschiede:** Treffer-Overlay als Farbmultiplikation statt Overlay-Textur, keine Schatten unter Entities (Vanilla-Option `entityShadows` im Vergleich aus).

## 6. Texturdekodierung: Graustufen-PNG (`view.PngImages`)

**Befund aus dem Bildvergleich (docs/49 §3.5):** Gras, Stein und Laub erschienen im eigenen Client bei Tageslicht um den Faktor 1/0,68 bis 1/0,74 heller als in Vanilla. Weißer Beton war dagegen pixelgleich. Damit lagen Lightmap, Lichtwerte und die Schattierung der Oberseite richtig.

**Ursache:**
- 589 Texturen in 26.3 sind Graustufen-PNGs (Farbtyp 0 oder 4). Dazu gehören `stone`, `grass_block_top`, `grass_block_side_overlay`, `smooth_stone`, Laub, `water_overlay`, `destroy_stage_*`, Redstone-Staub und gefärbte Glasvarianten.
- Java ImageIO legt solche Bilder in einem *linearen* Grau-Farbraum ab. `BufferedImage#getRGB` rechnet sie deshalb nach sRGB um und hellt sie auf. Beispiel `stone.png`: Rohwert 143 wird zu 197.
- Vanilla (stb_image) verwendet die gespeicherten Bytes unverändert.
- Palette-, RGB- und RGBA-Bilder sind nicht betroffen, darunter `white_concrete.png` (Palette). Deshalb war Beton gleich.

**Korrektur:**
- `PngImages.read` überträgt Graustufenbilder mit ihren Rohwerten nach ARGB. 16 Bit werden wie bei stb_image um 8 Bit verschoben, kleinere Tiefen auf 0–255 gestreckt.
- Alle Ladestellen für Minecraft-Assets verwenden es: Modelle/Atlas, Colormaps, Entity-Texturen, Partikel, Himmel.
- Regressionstest: `PngImagesTest` prüft `stone.png` (0,0) = 143, `grass_block_top.png` = 148 und eine unveränderte Palette bei weißem Beton.

**Messung nach der Korrektur (Diagnoseszenen):**

| Szene | Vanilla | eigen |
|---|---|---|
| Gras senkrecht von oben (Biom river) | 75,6/108,4/58,5 | 75,5/108,4/58,5 |
| Stein senkrecht von oben | 126,8 | 126,8 |
| Gras vor der Betonwand | 85,8/108,4/70,8 | 86,1/108,8/71,0 |

Biom und gemittelte Grasfarbe waren schon vorher gleich: beide Clients `minecraft:river`, `#8eb971`, erfasst über `TINT`/`env-dump`. Die Abweichung lag also ausschließlich in der Texturdekodierung, nicht in Tönung, Biom-Blending, Gamma, Richtungsschattierung oder AO.
