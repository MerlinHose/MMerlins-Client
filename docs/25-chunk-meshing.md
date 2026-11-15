# 25 – Chunk-Meshing und Blockmodelle (M6)

Dieses Dokument beschreibt, wie aus dem Weltmodell (M4) GPU-Geometrie wird. Alle Klassen in §2–§4 und §8–§9 liegen im JDK-only-Paket `view` und werden ohne GPU unit-getestet (§10).

## 1. Ablauf je Sektion

```
WorldState ──(Listener: Sektion vormerken)──► dirty ──(Grafikthread: nächste zuerst, ≤ 32 offen)──► Mesh-Thread
   Mesh-Thread: sectionSnapshot (18³ Zustände + Licht, eine Sperre) → SectionMesher.build(snapshot, Modelle) → int[] opaque / translucent
   ──► Ergebnis-Queue ──(Grafikthread: ≤ 24 Uploads/Frame, Versionsprüfung)──► GpuMesh (VAO+VBO) ──► draw (Frustum, 2 Durchgänge)
```

## 2. Vertexformat (`MeshBuilder`)

Jeder Vertex umfasst 24 Byte:
- Position `x, y, z` als `float`, lokal 0…16;
- UV `u, v` als `float`, Atlas;
- Farbe RGBA als 4 Byte, normiert.

Ein Viereck besteht aus zwei Dreiecken mit 6 Vertices, gegen den Uhrzeigersinn von außen gesehen. So wirkt das Backface-Culling von OpenGL.

## 3. Modelle: visuell ≠ Kollision

`RenderModel` beschreibt, **was man sieht**:
- Flächen (`RenderQuad`) mit Ecken, UV, `cullFace`, Schattierungsrichtung, Tönungsindex, Farbe und Render-Schicht;
- je Seite `occludes`;
- `debug`;
- `cullGroup`.

Die Kollisionsformen aus M5 (`physics.Shape`) sind getrennte Daten. Beispiele:

| Block | sichtbar | Kollision |
|---|---|---|
| Gras/Blume | gekreuzte Flächen | keine |
| Glasscheibe | dünne Platte | 2/16 breit, mit Verbindungen |
| Truhe | ohne JSON-Elemente unsichtbar (Block-Entity) | 14/16 Kasten |

**Anbieter (`BlockModelProvider`):**
- `ColorModels` (Phase C): jeder Nicht-Luft-Block als voller Würfel in einer Farbe aus dem Blocknamen, Wasser durchscheinend. Dient dem Geometrienachweis ohne Ressourcen.
- `ResourceModels` (Phase D): aus den lokalen Minecraft-Ressourcen, siehe §8–§9.

## 4. `SectionMesher`

Für jede Zelle ≠ Luft wird jede Modellfläche geprüft:

1. **Flächen-Culling:** Hat die Fläche eine `cullFace` f und deckt der Nachbar in Richtung f dessen Gegenseite (`occludes(f.opposite())`) ab, entfällt sie. Sie entfällt ebenso, wenn beide derselben `cullGroup` angehören (Wasser an Wasser).
2. **Nachbarn außerhalb der Sektion:** Sie kommen aus dem 1-Block-Rand des Snapshots; Chunk- und Sektionsgrenzen brauchen keinen Sonderfall. Nicht geladene Nachbarchunks (`UNKNOWN_STATE`) zählen wie Luft, wie im Vanilla-Client. Die Fläche bleibt also sichtbar und wird neu bewertet, sobald der Nachbar lädt.
3. **Helligkeit:**
   - Richtungsschattierung: oben 1,0, unten 0,5, Nord/Süd 0,8, West/Ost 0,6.
   - Lichtkurve von `max(Himmelslicht, Blocklicht)` der Zelle, in die die Fläche zeigt; innere Flächen nehmen das Maximum aus eigener und Nachbarzelle.
   - Unbekanntes Licht = 15.
4. **Tönung:** `tintindex ≥ 0` → Farbe × Tönung des Zustands.
5. **Ausgabe:** Ecken + Zellposition. Schicht `SOLID` oder `CUTOUT` → undurchsichtiges Mesh; `TRANSLUCENT` → durchscheinendes Mesh.

**Negative Weltkoordinaten** treten im Mesher nicht auf: Der Snapshot ist lokal, der Ursprung `chunkX·16` usw. wird erst im Shader-Offset verwendet. Test: Sektion (−1, −1, −1) erzeugt Koordinaten 0…1 (`ViewTest`).

## 5. Aktualisierung nur bei Änderungen

| Ereignis (Netzwerk-Thread) | vorgemerkt |
|---|---|
| `chunkLoaded(x, z)` | alle Sektionen des Chunks und der 4 Nachbarchunks (deren Randflächen) |
| `blockChanged(x, y, z)` (nur bei echter Änderung) | die Sektion, dazu die Nachbarsektion, wenn der Block am Sektionsrand liegt |
| `lightChanged(x, z)` | alle Sektionen des Chunks |
| `chunkUnloaded(x, z)` | Chunk zum Entladen vormerken, Versionen seiner Sektionen erhöhen, 4 Nachbarchunks neu bewerten |

**Versionen:** Jede Vormerkung erhöht die Version der Sektion. Ein Ergebnis mit älterer Version wird verworfen; so landet nie ein veraltetes Mesh auf der GPU.
- Das gilt auch, wenn ein Auftrag seinen Snapshot vor dem Entladen genommen hat.
- Ein Ergebnis für einen nicht mehr geladenen Chunk ist leer, weil der Snapshot `null` liefert. Das Mesh entfällt dann.

Pro Frame wird nichts neu berechnet: gezeichnet werden nur vorhandene `GpuMesh`es.

## 6. Sichtbarkeit

- **Sichtweite:** Sektionen außerhalb von |Δchunk| ≤ Sichtweite werden weder gebaut noch gezeichnet.
- **Frustum-Culling** (`Frustum`, Ebenen nach Gribb/Hartmann aus Projektion × Ansicht): Die Sektionsbox (16³, kamerarelativ) wird gegen die 6 Ebenen getestet.
- **Hardware:** Backface-Culling und Tiefentest.

## 7. GPU-Ressourcen

- `GpuMesh`: ein VAO und ein VBO je Schicht und Sektion.
- **Entladen:** Beim Entladen eines Chunks löscht der Grafikthread im nächsten Frame alle Puffer seiner Sektionen (`glDeleteBuffers`/`glDeleteVertexArrays`); Zähler `deletes`.
- **Schließen:** Beim Schließen werden alle Meshes, der Atlas, die Shader und die Overlay-Textur freigegeben und die Mesh-Threads beendet.
- **Gemessen:** Nach dem Teleport auf x = 600 sank die Zahl der Meshes von 308 auf 195 und der GPU-Speicher von 22,1 auf 11,6 MB. Nach der Rückkehr waren es wieder 224 Meshes bzw. 16,3 MB (docs/26).

## 8. Ressourcen und Textur-Atlas

**Quelle:** `assets/minecraft/{blockstates,models,textures}` im lokalen JAR, nur gelesen.

**Struktur in 26.3:**
- **Blockzustand:** `variants` (Schlüssel als Eigenschafts-Teilmenge, Wert als Objekt oder gewichtete Liste) oder `multipart` (`when` mit `|`, `OR`, `AND`; `apply`).
- **Modell:**
  - `parent`-Kette;
  - `textures`: Wert als Zeichenkette **oder neu als Objekt** `{"sprite": …, "force_translucent": true}`;
  - `elements` mit `from`/`to`, `rotation`;
  - `faces` mit `uv`, `texture` (`#variable`), `cullface`, `rotation`, `tintindex`;
  - `shade`.
- **Elementrotation:** Klassisch als `{origin, axis, angle, rescale}`. **Neu in 26.3** zusätzlich als Euler-Form `{x, y, z, origin}`, gefunden bei den Hängeschild-Vorlagen.

**Atlas (`TextureAtlas`):**
- 16×16-Kacheln in einem 2048²-Bild.
- Animierte Texturen liefern ihr erstes Bild; größere Texturen werden auf 16×16 abgetastet.
- Kachel 0 ist die Debug-Textur (magenta/schwarz).
- Je Textur werden **Transparenzklasse** und Mittelwertfarbe bestimmt:

  | Klasse | Bedingung |
  |---|---|
  | OPAQUE | kein Alpha < 255 |
  | CUTOUT | nur Alpha 0 oder 255 |
  | TRANSLUCENT | Zwischenwerte |

- Abtastung nächster Punkt, ohne Mipmaps.

**Tönung:**
- Gras- und Laubfarbe aus den Colormaps bei Ebenen-Klima (Temperatur 0,8, Niederschlag 0,4).
- Konstanten für Wasser (0x3F76E4), Birke und Fichte.

**Messwert:** Laden aller 35 723 Zustände dauert 1,95–2,2 s. Ergebnis: 1245 Texturen, 2757 Modelle, **0 Debug-Zustände**.

## 9. Modellauflösung (`ResourceModels`)

**Ablauf je Zustand:**
1. Blockzustandsdatei lesen.
2. Passende Variante bzw. alle passenden Multipart-Teile auswählen.
3. Jedes Teil backen: Modell, Variantenrotation `x`/`y`, `uvlock`.

**Ergebnisse mit Cache:**
- je `(Modell, x, y, uvlock)` die gebackenen Flächen;
- je Zustandstext das `RenderModel`.

**Backen:**
- Texturvariablen werden entlang der Elternkette aufgelöst; das Kind hat Vorrang.
- `elements` kommen vom nächsten Vorfahren, der welche hat.
- **Standard-UV je Seite aus der Elementgröße**, z. B. `up = [x0, z0, x1, z1]` und `north = [16−x1, 16−y1, 16−x0, 16−y0]`.
- Die Flächenrotation verschiebt die Eck-UV in 90°-Schritten.
- **Elementrotation:** um `origin`, Winkel ±22,5/45 usw., mit `rescale = 1/cos(Winkel)`. Bei der Euler-Form X → Y → Z (Reihenfolge nicht verifiziert).
- **Variantenrotation** um den Blockmittelpunkt: erst `x` (x=90 dreht oben → Norden), dann `y` (y=90 dreht Osten → Süden). Das entspricht den negativen Winkeln von `rotateYXZ(−y, −x, 0)`. `cullface` und Schattierungsrichtung drehen mit.
- **`uvlock`:** UV aus der gedrehten Flächenlage neu ableiten, damit die Textur weltfest bleibt.
- **Schicht:** aus der Transparenzklasse der Textur. `force_translucent` erzwingt den durchscheinenden Durchgang (in 26.3 z. B. Glas).
- **Verdeckung (`occludes`):** Alle `SOLID`-Flächen mit `cullface` f auf der Randebene müssen zusammen das 16×16-Raster der Seite vollständig abdecken. Das gilt z. B. für die Treppenrückseite aus zwei Elementen.

**Sonderfälle:**
- **Flüssigkeiten:** Wasser und Lava haben keine Elemente. Sie erhalten ein eigenes Kastenmodell mit Höhe 14/16 (Quelle) bzw. abnehmend nach `level`, Textur `water_still`/`lava_still`, Wasser getönt und durchscheinend, `cullGroup` je Flüssigkeit.
- **Fehler:**
  - Fehlende Blockzustandsdatei, fehlendes Modell, Formfehler → Debug-Würfel (magenta/schwarz, 0,1–0,9). Gezählt in „Debug-Blöcke“ und als Beispiel in der Zusammenfassung; kein Absturz.
  - Modelle ohne Elemente (Block-Entities) → leer.

## 10. Tests (ohne GPU)

| Test | Inhalt |
|---|---|
| `ViewTest` (8) | Kamera-Konvention (Yaw 0 → +Z, Yaw 90 → −X, Pitch 90 → unten); Frustum (vorn sichtbar, hinten/zu weit nicht); alle 6 Flächen gegen den Uhrzeigersinn; 1 Block = 6 Flächen; 2 Blöcke = 10; 3×3×3 = 54 Außenflächen; Sektionsrand mit Nachbarblock bzw. ungeladenem Nachbarn; Wasser durchscheinend; lokale Koordinaten bei negativer Sektion; Snapshot über negative Chunks und Rand; Listener-Ereignisse (Laden, Blockänderung nur bei echter Änderung, Entladen) |
| `ResourceModelsTest` (6, nur mit lokalem JAR) | Stein verdeckt alle Seiten; Gras mit Seitentexturen und Tönung oben; Stufe verdeckt nur unten; Treppe facing=south mit Oberstufe bei z 0,5–1 und verdeckter Südseite; Zaun-Multipart; Glas mit `force_translucent`; Laub ausgestanzt und getönt; Wasser mit `cullGroup`; < 2 % Debug-Zustände (gemessen: 0) |
