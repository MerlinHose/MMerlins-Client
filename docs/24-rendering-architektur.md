# 24 – Rendering-Architektur des grafischen Clients (M6)

Der grafische Client ist eine **optionale** Oberfläche über dem bestehenden Protokollclient. Netzwerk, Weltmodell (M4) und Spielerphysik (M5) laufen unverändert; das Fenster liest nur deren Zustand und liefert Tastenzustände.

- Meshing-Details: docs/25.
- Testbericht: docs/26.

## 1. Projektstruktur und Abhängigkeiten

| Source-Set | Inhalt | Laufzeitabhängigkeiten |
|---|---|---|
| `main` | Protokollclient, Welt, Physik, Terminal (M1–M5); neu: Paket `view` (Matrizen, Frustum, Blockmodelle, Atlas-Layout, Chunk-Meshing) | **nur JDK** (unverändert) |
| `render` (neu) | Paket `gl`: Fenster, Shader, GPU-Meshes, Chunk-Renderer, Overlay, Spielschleife, Autotest | `main` + LWJGL |
| `test` | Unit- und Integrationstests, darunter neue Tests für `view` (ohne GPU) | JUnit |

- **LWJGL 3.4.3:** aktuelle stabile Version laut offiziellen Maven-Central-Metadaten (`<release>3.4.3</release>`, veröffentlicht 2026-08-23). Gepinnt über `org.lwjgl:lwjgl-bom:3.4.3` mit den Modulen `lwjgl`, `lwjgl-glfw`, `lwjgl-opengl` und den Natives `natives-windows`.
- **Getrennte Bauteile:** Der Terminalclient (`./gradlew run`, Klasse `Main`) und alle Tests enthalten kein LWJGL. Das grafische Programm startet über einen eigenen Task.
- **Getestete Hardware:** NVIDIA GeForce RTX 3070, Treiber 621.01, „OpenGL 3.3.0 NVIDIA 621.01“ im Core-Profil.

Es gibt keine Fabric-, Minecraft-, Mojang- oder Netty-Klassen im Client. Shader, Mathematik, Modell-Parser und Mesher sind eigenständig geschrieben.

## 2. Start

| Weg | Befehl |
|---|---|
| IntelliJ | Startkonfiguration **„Minecraft Research – Graphical Client“** (`client/.run/`, Gradle-Task `runGraphical`) |
| Kommandozeile | `cd client && ./gradlew runGraphical -Pgargs="--username ProtoBotGfx --render-distance 2"` |
| Terminalclient (unverändert) | Startkonfiguration „ProtocolClient“ bzw. `./gradlew run --args="--interactive"` |

**Optionen (`GraphicalOptions`):**

| Option | Bedeutung | Standard / Bereich |
|---|---|---|
| `--host` | Server | `127.0.0.1`; über `LoopbackGuard` nur Loopback |
| `--port` | Port | 25565 |
| `--username` | Spielername | `ProtoBotGfx` |
| `--width`, `--height` | Fenstergröße | 1280×720 |
| `--fov` | Sichtwinkel | 70 (30–110) |
| `--vsync` / `--no-vsync` | Bildsynchronisation | an |
| `--render-distance` | Sichtweite in Chunks | 2 (1–8); wird auch als Sichtweite an den Server gemeldet |
| `--colors` | Farbmodus ohne Texturen | – |
| `--assets <jar>` | lokale Minecraft-Ressourcen | setzt der Task automatisch auf das Loom-JAR |
| `--test-cube` | Testwürfel ohne Server | – |
| `--autotest`, `--report` | Teststeuerung (docs/26) | – |
| `--screenshot`, `--screenshot-frame`, `--exit-after`, `--packet-log` | Hilfen für Tests | – |

**Lokale Ressourcen:** Der Task `runGraphical` sucht `research/.gradle/loom-cache/…/minecraft-merged-*-26.3.jar` und übergibt den Pfad.
- Blockzustände, Modelle und Texturen werden **zur Laufzeit nur gelesen**. Es gibt keine Kopie in `client/`, in Build-Artefakten oder in Git.
- Ohne dieses JAR startet der Client im Farbmodus.

## 3. Threads und Datenfluss

```
 Netzwerk-Thread "session"         Tick-Thread "client-tick" (20 Hz)       Mesh-Threads "mesh-1/2"      Grafikthread "main"
 ─────────────────────────         ─────────────────────────────────       ────────────────────────     ─────────────────────
 Pakete lesen/dekodieren           MovementController.tick():              WorldState.sectionSnapshot   GLFW-Ereignisse, Eingabe
 Keep-Alive beantworten            Tasten → PlayerPhysics → Pakete         → SectionMesher.build        → setManualInput / look
 WorldState ändern ──┐             (unabhängig von der Bildrate)            (CPU, keine GL-Aufrufe)      RenderState → Kamera
                     │                                                          │                          ChunkRenderer.frame:
                     └─ WorldListener (unter Weltsperre: nur vormerken) ──► dirty-Menge ──► Auftrag ──► Ergebnis-Queue ──► Upload (VBO)
                                                                                                           draw, Overlay, swap
```

- **Keep-Alive** und Physik hängen nicht von der Bildrate ab. Ein langsames oder geschlossenes Fenster hält das Netzwerk nicht an. Gemessen: größte Keep-Alive-Lücke 15 059 ms bei 60 und bei ~3800 FPS.
- **OpenGL nur auf dem Grafikthread:** `Window#checkThread` erzwingt das für Fenster-, Screenshot- und Eingabefunktionen. `ChunkRenderer` legt GPU-Puffer ausschließlich in `frame()`/`draw()` an, also auf dem Grafikthread. Mesh-Threads erzeugen nur `int[]`-Vertexdaten.
- **Weltänderungen:** `WorldState` ruft unter seiner Sperre `chunkLoaded`, `chunkUnloaded`, `blockChanged` und `lightChanged` auf. Der Renderer merkt sich dabei nur Sektionen in nebenläufigen Mengen vor (docs/25 §5).

## 4. Kamera und Physik

Die Kamera führt **keine eigene Spielerposition**:
- `MovementController.renderState()` liefert die Position vor und nach dem letzten Physik-Tick und dessen Zeitpunkt.
- Die Kamera interpoliert linear mit `α = (jetzt − Tickzeit) / 50 ms` zwischen diesen beiden echten Physikzuständen. Nach einem Teleport sind beide gleich, es gibt also keine Interpolation über den Sprung.
- Augenhöhe 1,62.
- Yaw und Pitch kommen aus `PlayerState`, also denselben Werten, die an den Server gehen.

**Konvention:** Yaw 0 blickt nach +Z (Süden), Yaw 90 nach −X; positiver Pitch blickt nach unten. Die Ansichtsmatrix ist `Rx(pitch)·Ry(yaw+180°)`. Unit-Test `ViewTest#yawZeroLooksSouthAndPitchDownLooksDown`.

**Kamerarelative Geometrie:** Sektionsmeshes speichern lokale Koordinaten 0…16; der Shader erhält `uOffset = Sektionsursprung − Kamera`. Dadurch bleibt `float` auch bei großen Weltkoordinaten genau.

## 5. Steuerung

| Eingabe | Wirkung |
|---|---|
| Linksklick ins Fenster | Maus fangen (Cursor ausgeblendet, Raw-Mausbewegung, wenn verfügbar) |
| `Esc` | Maus freigeben |
| `W` / `S` / `A` / `D` | vorwärts / rückwärts / links / rechts → Tastenzustand für die Physik (M5) |
| Leertaste | Springen (Physikregel: nur am Boden, `noJumpDelay`) |
| Maus | Yaw/Pitch, Empfindlichkeit 0,15°/Pixel, Pitch ±90° |
| `F3` | Debug-Overlay ein/aus |
| `F2` | Bildschirmfoto nach `client/build/screenshots/` |
| Fenster schließen | sauberes Trennen (`requestStop`), Freigabe aller GPU-Ressourcen |

Tasten werden nur bei gefangener Maus ausgewertet, damit Tippen in anderen Fenstern nichts bewegt.

## 6. Fenster, Shader, Overlay

**Fenster** (`Window`):
- GLFW 3, OpenGL 3.3 Core, Forward-Compatible, in der Größe veränderbar.
- Eine Größenänderung setzt im nächsten Frame den Viewport; das Seitenverhältnis kommt aus der Framebuffer-Größe.
- VSync per `glfwSwapInterval`.

**Welt-Shader** (`Shaders.WORLD_*`):
- Position + `uOffset`, Atlas-Textur × Vertexfarbe (Licht, Schattierung, Tönung).
- Alphatest für Ausgestanztes, linearer Nebel zum Himmel ab 80 % der Sichtweite.

**Zeichenreihenfolge:**
1. Erster Durchgang undurchsichtig und ausgestanzt, mit Tiefenschreiben.
2. Zweiter Durchgang durchscheinend: Blending, ohne Tiefenschreiben, Sektionen von fern nach nah sortiert.

**Overlay:**
- Text wird mit Java2D in ein Bild gezeichnet; eigene Darstellung, keine Minecraft-Schrift.
- Die Textur wird höchstens 4× pro Sekunde neu hochgeladen; eine Statusmeldung wie „Disconnected: …“ erscheint sofort.
- Erste Zeile: FPS, mittlere Bildzeit, 1%-Low.
- F3 zusätzlich: Position, Block, Chunk, Blickrichtung, `onGround`, Korrekturen, geladene Chunks, Verbindungsstatus, Keep-Alive, Meshes, sichtbare Sektionen, Draw Calls, Vertices, GPU-Speicher, Meshing-Zeiten, Heap, Kompressionszähler.

## 7. Verbindungsende

`ClientSession.playing()` berücksichtigt seit M6 auch eine Trennung durch den Server und das Ende der Paketschleife. Davor blieb der Wert nach einem Server-Disconnect `true`; der Terminalclient profitiert ebenfalls.

Das Fenster zeigt dann ein rotes Banner mit dem Grund. Die zuletzt bekannte Welt bleibt sichtbar, bis das Fenster geschlossen wird. Es gibt keinen automatischen Neuverbindungsversuch.

## 8. Speicher- und Leistungsgrenzen (16-GB-Rechner)

| Größe | Grenze |
|---|---|
| Java-Heap grafischer Client | `-Xmx768m`, `-XX:MaxDirectMemorySize=256m` |
| native Upload-Puffer | `MemoryUtil.memAlloc` je Upload, sofort freigegeben |
| Mesh-Threads | 2 |
| gleichzeitige Mesh-Aufträge | 32 |
| Uploads je Frame | 24 |
| Sichtweite | Standard 2, höchstens 8 |
| GPU-Speicher bei Sichtweite 2 (gemessen) | 15–28 MB Vertexdaten + 16 MB Atlas (2048² RGBA) |
| Testläufe | Speicher-Vorprüfung (≥ 2,5 GB frei), `memmon.ps1`, Server `-Xmx1536M`, Peaceful, Aufräumen per `trap` (`analysis/tools/m6-run.sh`) |

**Gemessener Projektbedarf:** Server, grafischer Client und Gradle zusammen 1,4–1,8 GB (Working Set). Messwerte stehen in docs/26 §4.

## 9. Bekannte Grenzen

- **Keine Entities:** keine Spieler, Mobs oder Gegenstände.
- **Block-Entities ohne JSON-Elemente sind unsichtbar:** Truhe, Schild, Banner, Bett, Shulkerkiste, Kopf. Die Kollision aus M5 gilt trotzdem.
- **Einfaches Licht:** keine Umgebungsverdeckung (AO) und kein weiches Licht; Helligkeit je Fläche aus den Serverlichtdaten.
- **Gleiche Tönung überall:** Biomtönung konstant mit Ebenen-Klima (Gras- und Laub-Colormap).
- **Texturen:** Animierte Texturen zeigen ihr erstes Bild; es gibt keine Mipmaps.
- **Flüssigkeiten:** vereinfacht (ebene Oberfläche, keine Strömung); wasserdurchflutete Blöcke zeigen kein Wasser.
- **Varianten mit Gewichtung:** Es wird immer die erste Variante gewählt, ohne Zufall nach Position.
- **Euler-Elementrotation (neu in 26.3):** Die Reihenfolge X → Y → Z ist nicht gegen den Quellcode verifiziert (docs/25 §9).
- **Kein Menü:** keine Inventar- oder Chat-Oberfläche, kein Blockabbau oder -setzen. M6 ist eine reine Darstellung mit Bewegung.
