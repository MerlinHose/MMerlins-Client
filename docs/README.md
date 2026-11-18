# Minecraft 26.3 – Clientanalyse

Analyse der Minecraft-26.3-Entwicklungsumgebung in `research/` als Grundlage für einen späteren, unabhängigen Client-Nachbau.
**Der Nachbau selbst ist noch nicht begonnen.** Minecraft-Originaldateien wurden nicht verändert.

## Dokumente

| Datei | Inhalt |
|---|---|
| [00-umgebung-und-build.md](00-umgebung-und-build.md) | Projektaufbau, Versionen, Verifikation von `build` und `runClient` |
| [01-client-architektur.md](01-client-architektur.md) | Start, `Minecraft`-Singleton, Subsysteme, Threads, Paketstruktur |
| [02-game-loop.md](02-game-loop.md) | `run` → `runTick` → `tick`/`renderFrame`, Tick-Timing, Frame-Limiter |
| [03-rendering.md](03-rendering.md) | RenderPearl-GPU-Abstraktion (OpenGL/Vulkan), Extract/Render-Trennung, Frame Graph, Chunks |
| [04-eingaben.md](04-eingaben.md) | SDL3-Eventpfad, Keyboard/Mouse-Handler, `KeyMapping`, Bewegung → Pakete |
| [05-netzwerk.md](05-netzwerk.md) | Netty-Pipeline, Framing, Kompression, Verschlüsselung, Protokollphasen, Threading |
| [06-rea-analyse.md](06-rea-analyse.md) | Ergänzende REA-/Bytecode-Prüfung des ausgelieferten JARs |
| [07-paket-ids.md](07-paket-ids.md) | Generierte Paket-ID-Tabellen aller Protokollphasen (260 Pakete) |
| [08-verbindung-lokaler-server.md](08-verbindung-lokaler-server.md) | Verbindungsaufbau zu einem lokalen Vanilla-Server: Client- und Serverseite, `server.properties`, minimaler Paketdialog |
| [09-rea-funktionspruefung.md](09-rea-funktionspruefung.md) | Welche REA-MCP-Operationen für Java-JARs funktionieren; Vergleich Vanilla-Server ↔ Client-Code |
| [10-laufzeittest.md](10-laufzeittest.md) | **Laufzeittest** Fabric-Client ↔ lokaler Vanilla-Server: Paketabfolge, Gameplay-Tests, Abweichungen zu 08, Fehlermeldungen |
| [11-technischer-bericht.md](11-technischer-bericht.md) | Bericht und Empfehlung nach dem Laufzeittest |
| [12-paketspezifikation.md](12-paketspezifikation.md) | **Phase 2:** Feldgenaue Spezifikation aller für den Minimalclient nötigen Pakete (22 C→S / 28 S→C) |
| [13-eigener-client-architektur.md](13-eigener-client-architektur.md) | Architektur des eigenständigen Clients in `client/`, CLI, **Start aus IntelliJ** |
| [14-client-laufzeittest.md](14-client-laufzeittest.md) | Laufzeittest des eigenen Clients: Erfolgskriterien, Vergleich mit Vanilla, getestet vs. fehlend |
| [15-spielerbewegung.md](15-spielerbewegung.md) | **M2:** Bewegungspakete, Serverregeln, Bodenhöhen aus Heightmaps, Bewegungsmodell, Terminalbefehle |
| [16-kompression.md](16-kompression.md) | **M3:** Kompressionsaktivierung, Schwellenwert-Semantik (−1/256/0), Größenlimits, Fehlerbehandlung, Zähler |
| [17-m2-m3-testbericht.md](17-m2-m3-testbericht.md) | Testbericht M2/M3 mit Serverbeobachtung, Messwerten, Fehlern und Empfehlung für M4 |
| [18-chunk-spezifikation.md](18-chunk-spezifikation.md) | **M4:** Chunk-Paket, Sektionen, `PalettedContainer` (Singleton/Linear/HashMap/Global), Bitpacking, Licht, Blockupdates, Koordinaten, Zustands-IDs |
| [19-weltarchitektur.md](19-weltarchitektur.md) | **M4:** eigene Weltverwaltung (`WorldState`, `Chunk`, `ChunkDecoder` …), Validierung, Terminalbefehle `block`/`chunk`/`world`/`nearby`/`blocks` |
| [20-m4-testbericht.md](20-m4-testbericht.md) | Testbericht M4: Blockabgleich gegen Vanilla (`execute if block`), Ent-/Neuladen, Schwellenwerte −1/256/0, Empfehlung für M5 |
| [21-physik-spezifikation.md](21-physik-spezifikation.md) | **M5:** Tickablauf, Hitbox, Eingabe, Reibung, Schwerkraft, Sprung, Kollision mit Step-up, Kollisionsformen, Serverprüfung, nicht umgesetzte Sonderbewegungen |
| [22-kollisionsarchitektur.md](22-kollisionsarchitektur.md) | **M5:** Formtabelle (ShapeExtractor), `physics`-Paket, Bewegungssteuerung, Terminalbefehle `walk`/`jump`/`physics`/`collision`/`hitbox`/`trajectory` |
| [23-m5-testbericht.md](23-m5-testbericht.md) | Testbericht M5: Teststrecke, Tick-für-Tick-Vergleich mit dem Vanilla-Client, Serverbestätigung, offene Punkte |
| [24-rendering-architektur.md](24-rendering-architektur.md) | **M6:** grafischer Client (LWJGL 3.4.3, OpenGL 3.3), Threads, Kamera aus der Physik, Steuerung, Overlay, Speichergrenzen, Start |
| [25-chunk-meshing.md](25-chunk-meshing.md) | **M6:** Sektions-Snapshot, Meshing, Flächen-Culling, Aktualisierung, Frustum, Textur-Atlas, Modellauflösung aus den lokalen Ressourcen |
| [26-m6-testbericht.md](26-m6-testbericht.md) | Testbericht M6: Testwürfel, Farbmodus, Texturen, 5-Minuten-Integrationstest mit Serverabgleich, Disconnect, Messwerte, Regression |
| [27-blockinteraktion.md](27-blockinteraktion.md) | **M7:** Auswahl (Raycast, Umriss, Reichweite aus Attributen), Abbau (Kreativ/Überleben, Fortschritt, Sequenzen), Rechtsklick, unsichtbare Vorhersageliste, HUD |
| [28-inventar-und-hotbar.md](28-inventar-und-hotbar.md) | **M7:** Inventarpakete, ItemStack-/Komponenten-Codecs, Hotbar-Synchronisation, Inventarfenster (nur Anzeige) |
| [29-m7-testbericht.md](29-m7-testbericht.md) | Testbericht M7: Auswahl gegen Vanilla (75/75 je Modus), Abbau/Platzieren 14/14, Licht gegen Server 1729/1729, Grafik- und Leckprüfung, Regression M1–M7 |
| [30-inventarprotokoll.md](30-inventarprotokoll.md) | **M8:** Inventarpakete und -IDs, `container_click` mit HashedStack, Komponenten-Hashes (CRC32C), Serverablauf, Korrekturen, Überleben/Kreativ |
| [31-inventararchitektur.md](31-inventararchitektur.md) | **M8:** serverautoritatives Modell ohne Vorhersage, Gesten-Steuerung, Inventarfenster, Eingabesperre, Testschnittstellen |
| [32-container-und-crafting.md](32-container-und-crafting.md) | **M8:** Menütypen und Platzbelegung, Truhe/große Truhe/Fass/Ofen, 2×2- und 3×3-Crafting, Grenzen |
| [33-m8-testbericht.md](33-m8-testbericht.md) | Testbericht M8: 89/89 Serverabgleiche (s1–s6), Grafik 11/11, echte Maus-/Tastatureingaben, Regression M1–M7 |
| [34-entity-protokoll.md](34-entity-protokoll.md) | **M9:** Entity-Pakete und -IDs, VecDelta/PositionPath (26.x), LpVec3, Datenfelder und Serialisierer, Attribute, Ausrüstung, Spielerliste, Hitboxen, Auswahlregeln |
| [35-entity-architektur.md](35-entity-architektur.md) | **M9:** Entity-Verwaltung, Threads, bestätigter Zustand vs. Darstellung, eigene Interpolation, Lebenszyklus, Auswahl |
| [36-entity-rendering.md](36-entity-rendering.md) | **M9:** eigene Modellgeometrie, lokale Texturen, Transformation, Animation, Spieler/Skins, GPU-Ressourcen, Grenzen |
| [37-m9-testbericht.md](37-m9-testbericht.md) | Testbericht M9: Server-Abgleich 39/39, Auswahl gegen Vanilla 185/185 je Modus (bitgenau), Leistung 10/50/100 bei 60 FPS, 6,5-min-Dauerlauf MATCH 22/22, Regression M1–M8 vollständig grün (JUnit 311/311) |
| [38-kampf-und-survival-protokoll.md](38-kampf-und-survival-protokoll.md) | **M10:** Kampf- und Survival-Pakete (attack, interact, damage_event, Effekte, Tod), Schadens- und Rüstungsformeln, Animationskomponenten |
| [39-kampfsystem-architektur.md](39-kampfsystem-architektur.md) | **M10:** Angriff/Interaktion, Vorhersage vs. Serverbestätigung, Abklingzeit, Armschwung, Rückstoß, Schild, Tod/Wiederbelebung |
| [40-gesundheit-hunger-effekte.md](40-gesundheit-hunger-effekte.md) | **M10:** PlayerSurvivalState, Essen, Statuseffekte und ihre Wirkung auf Physik/Abbau, Todesbildschirm |
| [41-m10-testbericht.md](41-m10-testbericht.md) | Testbericht M10: Serverabgleiche k1–k4, Vergleich mit dem Vanilla-Client, Grafik, Regression M1–M9 |
| [42-sonderbewegung-spezifikation.md](42-sonderbewegung-spezifikation.md) | **M11:** Tickablauf des lokalen Spielers, Sprinten (Taste, Doppeltipp, Abbruch), Schleichen, Posen/Hitbox/Augenhöhe, Kantenschutz, Bewegungsattribute, Effekte, Netzwerk (player_input, player_command) |
| [43-fluid-und-kletterphysik.md](43-fluid-und-kletterphysik.md) | **M11:** Fluidzustand je Blockzustand, Fluidhöhen, Strömung, Schwimmen/Tauchen/Auftrieb, Wasser- und Lavabewegung, Leitern/Ranken/Gerüste |
| [44-sonderblock-physik.md](44-sonderblock-physik.md) | **M11:** Eis, Schleim (Prallen), Honig (Rutschen), Spinnennetz, Beerenstrauch, Seelensand, Blasensäulen, Zellen-Durchlauf von entityInside |
| [45-m11-testbericht.md](45-m11-testbericht.md) | Testbericht M11: Tick-für-Tick-Vergleich mit Vanilla (Sprint/Schleichen, Fluide/Klettern, Sonderblöcke), Grafik, Unit-Tests, Regression M1–M10 |
| [46-rendering-spezifikation.md](46-rendering-spezifikation.md) | **M12:** Bestandsaufnahme M11, Vanilla-26.3-Analyse (Licht, AO, Biome, OIT, Animation, Himmel, Wetter, Nebel, Reihenfolge), OIT-Entscheidung, Architektur, neue Faktentabellen |
| [47-beleuchtung-und-materialien.md](47-beleuchtung-und-materialien.md) | **M12:** Vertexformat, Lightmap, Darstellungsfakten, Culling, Schichten, Modellformat 26.3 (`shade_direction_override`), weiches Licht und AO, Biomfarben und -mischung, Entity-Licht |
| [48-transparenz-animationen-und-wetter.md](48-transparenz-animationen-und-wetter.md) | **M12:** Umgebungsattribute und Zeitleisten, Server-Zeit/Wetter, sortierte Transparenz, Fluide, Nebel, Himmel, Wolken, Niederschlag, Partikel, Abbau-Overlay, HUD-Umbruch, Texturanimation |
| [49-m12-testbericht.md](49-m12-testbericht.md) | Testbericht M12: visuelle Vergleichssuite gegen Vanilla, Leistung vor/nach, GPU-Ressourcen, Unit-Tests, Regression M1–M11 |
| [50-authentifizierung-und-machbarkeit.md](50-authentifizierung-und-machbarkeit.md) | **M13:** Microsoft OAuth/PKCE, Xbox Live/XSTS, Minecraft Services, Sessionserver, App-Registrierung, Mojang-Freigabe der Client-ID, Endpunkt-Freigabeliste |
| [51-online-mode-protokoll.md](51-online-mode-protokoll.md) | **M13:** Login-Zustandsautomat, Encryption Request/Response, RSA, AES/CFB8, Session-Hash, Verschlüsselung im Datenstrom, Kompression, join |
| [52-authentifizierungsarchitektur.md](52-authentifizierungsarchitektur.md) | **M13:** Pakete und Schnittstellen, Anmeldeablauf, Netzwerkregel, Tokenverwaltung, Fehlerbehandlung, grafische Anmeldung |
| [53-m13-testbericht.md](53-m13-testbericht.md) | Testbericht M13: lokale Protokolltests, Simulation gegen Vanilla-Server, Vorbereitung (Client-ID-Konfiguration, Entwicklungsmodus), Regression M1–M12 (offen), Checkliste für die auf den letzten Meilenstein verschobene Microsoft-Anmeldung |
| [54-m14-entity-spezifikation.md](54-m14-entity-spezifikation.md) | M14: Bestandsaufnahme der Entity-Engine, untersuchte Vanilla-26.3-Regeln (Modelle, Varianten, Babys, Animationen, Skins, Passagiere, Fahrzeuge, Kollision), Faktentabellen aus dem lokalen JAR |
| [55-entity-modelle-und-animationen.md](55-entity-modelle-und-animationen.md) | Hierarchisches Modellsystem, Darstellungspläne je Typ (Varianten, Baby-Modelle, Zusatzschichten), Animationen, Renderer-Transformation, Abweichungen |
| [56-spieler-skins.md](56-spieler-skins.md) | Classic/Slim, zweite Skin-Schicht, SkinManager (lokale/Standard-Skins, sichere Dekodierung, Cache, Altformat), Mannequin-Profile, vorbereitete Online-Skins |
| [57-fahrzeuge-und-passagiere.md](57-fahrzeuge-und-passagiere.md) | Fahrzeug- und Passagierprotokoll, Sitzpositionen, Boots- und Pferdephysik, Loren, Entity-Kollisionen und Schieben |
| [58-m14-testbericht.md](58-m14-testbericht.md) | Testbericht M14 (teilweise): Fahrzeug-Tickvergleich gegen Vanilla (11/16 Szenarien bitgenau), behobene Fehler, offene Abweichungen und offene Prüfungen |

**Lesepfad für den späteren eigenen Client:** 01 → 02 (Hauptschleife) → 04 (Eingaben) → 05 + 08 (Verbindung) → 07 (IDs) → 03 (Rendering).

## Analysegrundlage

- **Quellen:** von Fabric Loom 1.19.3 generierte Quellen
  `research/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-1b0498e74c/26.3/minecraft-merged-1b0498e74c-26.3-sources.jar`
  – zum Lesen entpackt nach `analysis/mc-src/` (7 301 `.java`-Dateien; reine Lesekopie).
- 26.3 wird von Mojang **unobfuskiert** ausgeliefert; die Namen in den Quellen sind die offiziellen Mojang-Namen (keine Yarn/Intermediary-Mappings im Spiel).
- **Referenzformat:** `pfad/Klasse.java:Zeile` relativ zu `analysis/mc-src/`, Methoden als `Klasse#methode`.
  Zeilennummern beziehen sich auf die dekompilierten Loom-Quellen dieser Build-Instanz.
- **Ergänzend:** REA (Archiv-Inventar, Integrität, Existenzprüfung der Klassen im Bytecode) und `javap` (siehe [06](06-rea-analyse.md)).

## Hilfswerkzeuge

- `analysis/tools/PacketTableExtractor.java` – erzeugt `07-paket-ids.md` aus den `*Protocols.java`-Dateien:
  `java analysis/tools/PacketTableExtractor.java analysis/mc-src docs/07-paket-ids.md`
- `analysis/rea/minecraft-26.3-jar-evidence.json` – REA-Evidence-Bundle des Minecraft-JARs.

## Wichtigste Erkenntnisse auf einen Blick

1. **Plattformschicht ist SDL3** (nicht mehr GLFW): Fenster und Eingaben laufen über LWJGL-SDL (`SDLEventHandler`, `RenderSystem.initBackendSystem`).
2. **Neue GPU-Abstraktion „RenderPearl“** (`com.mojang.renderpearl`) mit **OpenGL- und Vulkan-Backend**; Standard ist OpenGL mit Vulkan-Fallback.
3. **Strikte Extract/Render-Trennung:** Pro Frame wird der Spielzustand in `GameRenderState` extrahiert, danach rendert `GameRenderer#render` nur aus diesem Zustand.
4. **Fester 20-TPS-Tick** über `DeltaTracker.Timer`, max. 10 Ticks pro Frame nachgeholt; Rendering interpoliert mit `partialTick`.
5. **Client- und Vanilla-Server-Code sind bytegleich** (REA-Vergleich: 17 651 identische Dateien); Netzwerkverhalten aus den Quellen gilt 1:1 für einen lokalen Vanilla-Server.
6. **REA** unterstützt für Java nur Archiv-Operationen (Inventar, Vergleich); Code-Analyse erfolgt über die Loom-Quellen.
7. **Laufzeit-verifiziert:** Verbindung zum lokalen Vanilla-Server inkl. Chunk-Laden, Bewegung, Blockinteraktion, Inventar und beiden Disconnect-Arten; alle 260 Paket-IDs stimmen mit dem offiziellen `packets.json` überein ([10](10-laufzeittest.md)).
8. **Netzwerk:** Protokoll **777**, Netty, VarInt-Framing (max. 21 Bit), zlib-Kompression, AES/CFB8; Phasen Handshake → Status | Login → Configuration → Play. Paket-IDs = Registrierungsreihenfolge.
