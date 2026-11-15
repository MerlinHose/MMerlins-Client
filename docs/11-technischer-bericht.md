# 11 – Technischer Bericht: Laufzeittest und Empfehlung

Stand: 2026-11-06 · Details und Rohdaten: [10-laufzeittest.md](10-laufzeittest.md), `analysis/runtime-logs/`

## 1. Ergebnis in einem Satz

Der Fabric-Entwicklungsclient 26.3 wurde vollautomatisch mit einem lokalen Vanilla-Server 26.3 (`127.0.0.1:25565`, offline, ohne Kompression) verbunden. Handshake, Login, Configuration, Play sowie Chunk-Laden, Bewegung, Blockinteraktion, Inventar und beide Disconnect-Arten liefen fehlerfrei und reproduzierbar. Die dokumentierte Protokollbeschreibung ist im Kern bestätigt; zwölf Punkte wurden präzisiert bzw. ergänzt. Es gab keinen Kontakt zu externen Servern.

## 2. Testergebnisse

| Prüfung | Ergebnis |
|---|---|
| Server bindet nur `127.0.0.1:25565` | ✅ gemessen |
| Externe Verbindungen (Client + Server, 3 Läufe) | ✅ 0 |
| Verbindungsablauf bis Play | ✅ 3/3 Läufe identische Paketfolge |
| Paket-IDs (`docs/07`) vs. offizieller Generator | ✅ 260/260, 0 ID-Abweichungen |
| Client-OUT = Server-IN, Server-OUT = Client-IN | ✅ in jedem Lauf exakt (z. B. 800 / 37 128 Pakete) |
| Chunks: Batching, Laden, Entladen nach Teleport | ✅ (~470 Chunks, 35 MB unkomprimiert) |
| Bewegung, Rotation, Heartbeat | ✅ (Sprung in 1 von 2 gültigen Läufen ohne Höhengewinn, Ursache nicht untersucht) |
| Block platzieren / abbauen / aufsammeln | ✅ |
| Hotbar, Offhand, Drop, Container-Klicks | ✅ |
| Disconnect Client / Kick Server | ✅ / ✅ |
| Online-Modus, Kompression, Status-Ping | ⏸ nicht getestet |

Testqualität: Lauf 1 brauchte einen manuellen Klick (Erststart-Onboarding-Screen). Lauf 2 war wegen zweier eigener Testfehler teilweise ungültig (persistiertes Inventar, veraltetes Log im Koordinator). Beides wurde behoben; Lauf 3 lief vollautomatisch und vollständig.

## 3. Wichtigste neue Erkenntnisse

1. **`white-list=true` ist Default** in 26.3; das fehlte in der Dokumentation.
2. Der Vanilla-Server ruft beim Start **auch im Offline-Modus** Mojang-Discovery- und Key-Endpunkte ab (`Proxy.NO_PROXY`); abschaltbar nur über `-Dminecraft.api.discovery.host`.
3. Der Client führt bei Port 25565 eine **DNS-SRV-Abfrage** aus und lädt eine **Server-Sperrliste** von `sessionserver.mojang.com`. Ein eigener Client kann beides weglassen.
4. Neue/übersehene Pakete: **`player_loaded`** (C→S, 0x2C, sonst 3 s Eingabesperre), **`punch`** (C→S, 0x2E, bei jedem Schlag), `commands`, `recipe_book_settings`, `ticking_state/step` beim Join.
5. Clientseitiger Disconnect sendet **kein** Paket; der Kick kommt als `disconnect` (0x20).
6. Das Vanilla-Paket-Logging liefert Typ und Größe, aber **keine Feldinhalte und keine numerischen IDs**. Die IDs liefert der offizielle Datengenerator (`--reports`).

## 4. Reicht das Wissen für einen ersten eigenständigen Java-Protokollclient?

**Ja, für einen Headless-Protokollclient im Offline-Modus ohne Kompression.** Bekannt und verifiziert sind:

| Benötigt | Status |
|---|---|
| Framing (VarInt-Länge), VarInt/String-Kodierung | ✅ Quelle + Laufzeit |
| Paket-IDs aller Phasen | ✅ verifiziert (`packets.json`) |
| Reihenfolge und Pflichtantworten bis Play | ✅ verifiziert (§4 in 10) |
| Pflicht im Play: `accept_teleportation`, `chunk_batch_received`, `keep_alive`, `client_tick_end`, `player_loaded` | ✅ verifiziert |
| Feldlayouts der benötigten Pakete | 🔵 vollständig in den Loom-Quellen (`STREAM_CODEC` je Paket), **noch nicht als eigene Spezifikation dokumentiert** |
| Registry-Sync-Strategie | 🔵 geklärt: Known Packs ablehnen → Server sendet vollständige Registry-Inhalte (NBT) |

**Noch nicht ausreichend** für: Online-Modus (RSA/AES/Session-Server ungetestet), Kompression (ungetestet), Chunk-Dekodierung/Welt-Darstellung (Paletten-Format nicht analysiert), Item-/Komponenten-Codecs (`RegistryFriendlyByteBuf`), NBT-Parser.

## 5. Empfehlung für den nächsten Entwicklungsschritt

**Schritt 1 – Feldspezifikation (nur Analyse, ~1 Dokument):** Für die ca. 16 Pakete des Minimaldialogs die Feldlayouts aus den `STREAM_CODEC`s extrahieren (`docs/12-paketfelder-minimalclient.md`), dazu NBT-Grundformat und `registry_data`-/`login`-Struktur. Wichtig: `login` (Play) und `player_position` enthalten registry-abhängige bzw. komplexe Felder, die der Client zumindest überspringen können muss.

**Schritt 2 – Meilenstein M1 „Headless-Protokollclient“ (eigenes Gradle-Modul, ohne Minecraft-Abhängigkeit):**
- Reines Java 25 + Netty (oder `java.nio`), keine Klassen aus dem Minecraft-JAR.
- Ablauf: TCP → `intention` → `hello` → `login_acknowledged` → brand + `client_information` (kleine `view-distance`, z. B. 2) → `select_known_packs` **mit leerer Liste** → Registries/Tags lesen und verwerfen → `finish_configuration` → Play: `accept_teleportation`, `player_loaded`, `chunk_batch_received`, `keep_alive`, `client_tick_end` (20/s); unbekannte Pakete anhand der Länge überspringen.
- Erfolgskriterium: 60 s stabil im Spiel, danach sauberer Disconnect, Server-Log `ResearchBot joined/left` ohne Fehler.

**Schritt 3 – Verifikation mit vorhandener Infrastruktur:** Server mit `log4j2-packets.xml` starten, den eigenen Client verbinden und mit `analyze-runtime.js` die serverseitige Paketfolge gegen den Referenzlauf (`analysis/runtime-logs/run3/server-packets.log`) vergleichen. Abweichungen sind dann direkt sichtbar.

**Danach (M2+):** Bewegung senden (`move_player_*`, `player_input`), Kompression, Chunk-Dekodierung, später Online-Modus.

## 6. Hinweise zur Umgebung

- Neue Projektdateien: `research/src/main/java/dev/mcresearch/client/LocalTestClient.java`, `fabric.mod.json` (client-Entrypoint), `build.gradle.kts` (`runLocalTest`), `server/log4j2-packets.xml`, `analysis/tools/{netmon.ps1, coordinator.sh, analyze-runtime.js}`.
- `runClient` ist unverändert; der Testtreiber ist ohne `-Dmcresearch.localtest=true` inaktiv. `runClient` wurde wegen der Vorgabe „keine externen Server“ nicht erneut gestartet.
- Original-JARs: SHA-256 nach den Tests unverändert (server `d052f14d…`, merged `033be6d7…`, common `387e0765…`, extracted_server `a362163e…`).
- Server ist gestoppt; Welt unter `server/world/` (27 MB) bleibt erhalten. Laufzeitdaten sind per `.gitignore` ausgeschlossen.
