# 13 – Architektur des eigenständigen Protokollclients

Projekt: `client/` (Gradle Kotlin DSL, Java 25, Modul `mc-protocol-client`, Version 0.1.0)
Spezifikation: [12-paketspezifikation.md](12-paketspezifikation.md) · Laufzeittest: [14-client-laufzeittest.md](14-client-laufzeittest.md)

## 1. Grundsätze

| Grundsatz | Umsetzung | Nachweis |
|---|---|---|
| Keine Minecraft-/Fabric-/Forge-/Paper-Abhängigkeit | Laufzeit nur JDK; JUnit nur im Testklassenpfad | `gradlew dependencies --configuration runtimeClasspath` → *No dependencies*; `jdeps` → nur `java.base`, `java.logging`; kein Bytecode-Verweis auf `net/minecraft`, `com/mojang`, `net/fabricmc`, `io/netty` u. a.; REA-Inventar des JARs: 92 Einträge, nur eigene Klassen + `protocol/packets-777.txt` |
| Kein kopierter Mojang-Code | Eigene Implementierung nach den in docs/12 beschriebenen **Formaten**; NBT und JSON eigene Parser | – |
| Nur localhost | `LoopbackGuard` erlaubt `localhost` (fest 127.0.0.1) und Loopback-IP-Literale; Hostnamen werden **ohne DNS-Abfrage** abgewiesen; `Connection.open` prüft zusätzlich `isLoopbackAddress()` | Unit-Test `NetLayerTest#onlyLoopbackAllowedWithoutDnsLookups`; Netzwerkmonitor: 0 externe Verbindungen |
| Keine Zugangsdaten | Offline-Modus; Online-Modus (`hello` mit Schlüssel) wird erkannt und kontrolliert abgebrochen | `ClientSessionTest#onlineModeIsRejectedCleanly` |
| Validierung aller Eingaben | Framelänge, VarInt-Breite, String-Bytes/-Zeichen, Anzahlen gegen Grenzen **und** Restbytes, Identifier-Syntax, Booleans 0/1, Enums, endliche Zahlen, NBT-Tiefe, keine Restbytes, Paket-ID im Zustand, Paket im richtigen Zustand | 88 Unit-Tests |
| Timeouts und Ressourcenlimits | Connect 5 s, Read 30 s (`SessionConfig`), Frame ≤ 2 097 151 B, dekomprimiert ≤ 8 MiB, NBT-Tiefe ≤ 512, Bundle ≤ 4096 Pakete, Sammlungsobergrenzen in `ClientboundDecoder`, maximale Spielzeit 24 h | Unit-Tests |
| Nachvollziehbare Fehler | `System.Logger`/JUL auf der Konsole, Paketlog im Vanilla-Format, `ClientSession.Result` mit Zustand, Ursache und Statistik; Exit-Code 0/1/2 | – |

## 2. Schichten und Pakete

```
dev.mcresearch.protocolclient
├── Main                         CLI, Logging, Ablauf Status → Session, Ergebnisbericht
├── net                          Transport (kennt keine Paketinhalte)
│   ├── LoopbackGuard            nur Loopback, keine DNS-Abfragen
│   ├── Connection               Socket, Timeouts, Framing, Kompression, ID-Validierung, Paketlog, threadsicheres Senden
│   ├── PacketChannel            Schnittstelle für die Session (Test-Doubles möglich)
│   ├── FrameCodec               VarInt-Länge (≤ 3 Bytes) + Daten
│   ├── CompressionCodec         zlib nach login_compression (Grenzen wie Vanilla)
│   └── PacketLog                Zeilenformat wie Vanilla-PacketDecoder/Encoder
├── protocol                     Protokollwissen (ohne Sockets)
│   ├── ProtocolState, PacketDirection, ProtocolException, RawPacket
│   ├── PacketRegistry           260 IDs aus resources/protocol/packets-777.txt (aus packets.json erzeugt)
│   ├── codec/                   VarInts, PacketReader (mit Prüfungen), PacketWriter, Identifiers
│   ├── nbt/                     NbtReader (Netzwerk-NBT), TextComponents (Klartext aus Text-Komponenten)
│   ├── json/                    MiniJson (strikter Parser für Status/Login-Disconnect)
│   └── packet/                  Serverbound (18 Records → 22 Pakettypen, da keep_alive/pong/cookie_response
│                                mehrere Zustände abdecken), Clientbound (sealed; 24 Paket-Records → 28 Pakettypen,
│                                plus Skipped), ClientboundDecoder,
│                                GameProfile, KnownPack
└── session                      Zustand und Verhalten
    ├── ClientSession            Zustandsautomat Login → Configuration → Play (+ Reconfiguration), Tick-Thread
    ├── StatusQuery              Statusabfrage über eigene Verbindung
    ├── PlayerState              Position/Rotation mit Relative-Logik für player_position
    ├── SessionConfig            Parameter mit Wertebereichsprüfung
    └── SessionStats             Zähler für die Auswertung
```

Abhängigkeitsrichtung: `Main → session → protocol`, `session → net (PacketChannel)`, `net → protocol`. Die Protokollschicht kennt keine Sockets; die Session kennt keine Sockets, nur `PacketChannel`.

Umfang: 3 029 Zeilen Produktivcode in 30 Dateien, 1 238 Zeilen Testcode.

## 3. Ablauf

```
Main
 ├─ StatusQuery (eigene TCP-Verbindung)
 │    intention(STATUS) → status_request → status_response (JSON prüfen, protocol == 777)
 │    → ping_request(t) → pong_response(t) → Server schließt
 └─ ClientSession.run()
      start(): intention(LOGIN) → [LOGIN] → hello(name, Offline-UUID)
      Leseschleife (Thread "main"):  Connection.receive() → ClientboundDecoder.decode() → handle()
      Tick-Thread "client-tick" (alle 50 ms): client_tick_end im PLAY; nach playDuration → requestStop()
```

### Zustandswechsel (synchronisiert über `ClientSession.lock`)

| Auslöser | Reihenfolge im Client | Vanilla-Vorbild |
|---|---|---|
| S→C `login_finished` | Eingang → CONFIGURATION, `login_acknowledged` senden (noch LOGIN), Ausgang → CONFIGURATION, dann `custom_payload`(brand) + `client_information` | `ClientHandshakePacketListenerImpl#handleLoginFinished :175-206` |
| S→C `finish_configuration` | Eingang → PLAY, `finish_configuration` senden (noch CONFIGURATION), Ausgang → PLAY | `ClientConfigurationPacketListenerImpl#handleConfigurationFinished :150-184` |
| S→C `start_configuration` | Eingang → CONFIGURATION, `configuration_acknowledged` (noch PLAY), Ausgang → CONFIGURATION; Ticks pausieren | – |

Das Tick-Senden prüft Zustand und sendet unter derselben Sperre; dadurch kann `client_tick_end` nie nach `configuration_acknowledged` hinausgehen.

### Reaktionen im Play-Zustand

| Empfangen | Reaktion |
|---|---|
| `login` | prüfen (Spielmodus 0–3, `dimensionType` < Anzahl Einträge in `minecraft:dimension_type`, Dimension in Levelliste); Play-Zeitmessung startet |
| `player_position` | Position mit Relative-Bits anwenden (`PlayerState`), `accept_teleportation` mit **absoluter** Position; beim ersten Mal danach `player_loaded` |
| `chunk_batch_finished` | `chunk_batch_received(9.0)` |
| `keep_alive` / `ping` | sofortiges Echo derselben ID |
| `disconnect` | Grund aus NBT-Text, Verbindung schließen, Ergebnis „vom Server getrennt“ |
| `bundle_delimiter` | Bundle öffnen/schließen, Mitglieder zählen (≤ 4096) |
| alle übrigen gültigen IDs | übersprungen (nur ID und Framelänge validiert), gezählt |

### Kontrollierter Disconnect
Wie der Vanilla-Client ([10](10-laufzeittest.md) §6) ohne Abmeldepaket: Ticks stoppen, `Connection.close()` leert den Ausgang, sendet TCP-FIN (`shutdownOutput`) und schließt den Socket; die Leseschleife endet mit `SocketException`, die bei angefordertem Stopp als regulär gilt. Tick-Executor wird beendet, alle Ressourcen in `finally` freigegeben.

## 4. Konfiguration (CLI)

| Option | Standard | Bedeutung |
|---|---|---|
| `--host` | `127.0.0.1` | nur `localhost`, `127.x.x.x`, `::1` |
| `--port` | `25565` | |
| `--username` | `ProtoBot` | `[A-Za-z0-9_]{3,16}` |
| `--duration` | `70` | Sekunden im Play-Zustand bis zum Disconnect (0–86400) |
| `--view-distance` | `2` | 2–32, gesendet in `client_information` |
| `--known-packs` | `accept` | `accept`: angebotene Known Packs bestätigen (Vanilla-Verhalten, Registry-Inhalte entfallen); `decline`: leere Liste, Server sendet alle Registry-Inhalte als NBT |
| `--packet-log` | `logs/packets.log` | Paketlog (relativ zum Arbeitsverzeichnis) |
| `--status-only` / `--no-status` | – | nur Statusabfrage / ohne Statusabfrage |
| `--verbose` | – | DEBUG-Logging |

Exit-Codes: `0` Erfolg (Play erreicht, volle Dauer, kontrollierter Disconnect), `1` Fehlschlag, `2` ungültige Argumente.

## 5. Bauen, testen, starten

### Kommandozeile (im Ordner `client/`)
```bash
./gradlew build                       # Kompilieren (-Xlint:all -Werror) + 88 Unit-Tests
./gradlew test -PintegrationTest      # zusätzlich 2 Integrationstests gegen den laufenden lokalen Server (~65 s)
./gradlew run --args="--duration 70"  # Client starten
./gradlew run --args="--status-only"
```
Server vorher starten (siehe [10](10-laufzeittest.md) §11), z. B. in `server/`:
```bash
java -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery -jar server.jar nogui
```

### IntelliJ IDEA

**Variante A – `client/` als eigenes Projekt (empfohlen)**
1. *File → Open…* → `MinecraftResearch/client/build.gradle.kts` → *Open as Project*.
2. Bei Rückfrage *Trust Project*; IntelliJ importiert das Gradle-Projekt (`mc-protocol-client`).
3. *File → Project Structure → Project → SDK*: JDK 25 wählen (Gradle-Toolchain verlangt 25).
4. Die mitgelieferten Startkonfigurationen aus `client/.run/` erscheinen automatisch:
   - **ProtocolClient (localhost:25565, 70 s)** – startet `dev.mcresearch.protocolclient.Main` mit `--duration 70`
   - **ProtocolClient Status** – nur Statusabfrage
   - **ProtocolClient Tests (Gradle)** – alle Unit-Tests
5. Server starten, dann *Run ▶* auf „ProtocolClient …“. Ausgabe im Run-Fenster, Paketlog in `client/logs/packets.log`.

**Variante B – im bestehenden Projekt `MinecraftResearch`**
1. Gradle-Fenster → *+ (Link Gradle Project)* → `client/build.gradle.kts` auswählen.
2. *Run → Edit Configurations… → + → Application*:
   - Name: `ProtocolClient`
   - Module/Classpath: `mc-protocol-client.main`
   - JDK: 25
   - Main class: `dev.mcresearch.protocolclient.Main`
   - Program arguments: `--duration 70 --packet-log logs/packets.log`
   - Working directory: `$PROJECT_DIR$/client`
3. Alternativ direkt `Main.java` öffnen → grüner ▶ neben `main` → danach Argumente in der erzeugten Konfiguration ergänzen.

Debuggen: dieselbe Konfiguration mit *Debug*; wegen des Keep-Alive-Timeouts des Servers (15 s) Haltepunkte im Play-Zustand nur kurz halten, sonst trennt der Server.

## 6. Erweiterungspunkte

| Ziel | Ansatzpunkt |
|---|---|
| Weitere Pakete dekodieren | Record in `Clientbound` + `case` in `ClientboundDecoder` + Behandlung in `ClientSession#handle` (der `switch` ist über das sealed Interface vollständigkeitsgeprüft) |
| Bewegung senden | neue `Serverbound`-Records (`move_player_*`, `player_input`) + Tick-Logik |
| Chunks lesen | Decoder für `level_chunk_with_light` (Paletten) |
| Online-Modus | RSA/AES-CFB8 in `net` (Cipher-Streams vor Framing) |
| Weitere Protokollversionen | neue `packets-<version>.txt`, Version in `PacketRegistry` |
