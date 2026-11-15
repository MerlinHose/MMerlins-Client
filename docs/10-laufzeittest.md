# 10 – Laufzeittest: Fabric-Client ↔ lokaler Vanilla-Server 26.3

Datum: 2026-11-06 · Server: Vanilla 26.3 (`server/server.jar`, unveränderte Kopie aus dem Loom-Cache) · Client: Fabric-Dev-Client (`gradlew runLocalTest`)

**Legende für alle Aussagen in diesem Dokument**

| Kennzeichen | Bedeutung |
|---|---|
| 🟢 **beobachtet** | im laufenden Betrieb in Logs/Monitoren gesehen |
| 🔵 **Quelle** | nur aus den Loom-Quellen abgeleitet, nicht zur Laufzeit gemessen |
| 🟡 **gefolgert** | Laufzeitbeobachtung + Quellcode kombiniert (z. B. Paketinhalt aus Kontext erschlossen, weil der Inhalt selbst nicht geloggt wird) |

---

## 1. Zusammenfassung

| Bereich | Ergebnis | Grundlage |
|---|---|---|
| Server nur auf `127.0.0.1:25565` erreichbar | ✅ einziger Listen-Socket `127.0.0.1:25565`, keine UDP-Endpunkte | 🟢 `Get-NetTCPConnection`/`Get-NetUDPEndpoint` |
| Kein Kontakt zu externen Servern | ✅ 0 externe TCP-Verbindungen in allen 3 Läufen (Client und Server) | 🟢 `netmon.ps1` (1-s-Abtastung) |
| Handshake → Login → Configuration → Play | ✅ vollständig, in allen 3 Läufen identische Abfolge | 🟢 Paketlogs |
| Paket-IDs aus `docs/07` | ✅ 260/260 mit offiziellem `packets.json` identisch, 0 ID-Abweichungen | 🟢 Vanilla-Datengenerator |
| Client-/Serverlog konsistent | ✅ je Lauf 0 abweichende Pakettypen/-anzahlen (z. B. Lauf 1: C→S 800 = S-IN 800, S→C 37 128 = C-IN 37 128) | 🟢 |
| Chunk-Laden inkl. Batching und Entladen | ✅ | 🟢 |
| Spielerbewegung (Gehen, Sprung, Rotation) | ✅ Gehen/Rotation; Sprung in Lauf 1 ✅, in Lauf 3 ohne Höhengewinn ⚠ | 🟢 |
| Blockinteraktion (Platzieren, Abbauen) | ✅ Erde + Fackel platziert, Fackel abgebaut | 🟢 |
| Inventar (Hotbar, Offhand, Drop, Aufsammeln, Container-Klicks) | ✅ | 🟢 |
| Disconnect clientseitig | ✅ (Lauf 1) | 🟢 |
| Disconnect serverseitig (`kick`) | ✅ (Lauf 2, 3) | 🟢 |
| Status-Ping (Serverliste) | ⏸ nicht getestet (QuickPlay umgeht ihn) | – |
| Verschlüsselung / Kompression | ⏸ bewusst deaktiviert (`online-mode=false`, Threshold −1) | 🔵 nur Quelle |

---

## 2. Testaufbau

### 2.1 Server (`server/`)
- `server.jar` = Kopie von `~/.gradle/caches/fabric-loom/26.3/minecraft-server.jar` (SHA-256 `d052f14d…` identisch, Original unverändert).
- EULA vom Nutzer persönlich akzeptiert (`eula.txt`).
- `server.properties` (Abweichungen vom generierten Default): `server-ip=127.0.0.1`, `online-mode=false`, `white-list=false`, `network-compression-threshold=-1`, `enforce-secure-profile=false`, `motd`. RCON, Query, JMX, Management-Server: bereits im Default aus.
- Start: `tail -f console.in | java -Dlog4j.configurationFile=log4j2-packets.xml -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery -jar server.jar nogui`
  - `log4j2-packets.xml` (neu, im Server-Ordner): Vanilla-Konfiguration aus `logging-1.7.12.jar` + DEBUG für `PacketDecoder`/`PacketEncoder`/`Connection` → `logs/packets.log`. Vanilla blockiert diese Logs sonst per `MarkerFilter NETWORK_PACKETS`.
  - `minecraft.api.discovery.host`: offizieller authlib-Override (`EnvironmentParser#fromHostNames`). 🟢 Ohne ihn würde der Server beim Start **unabhängig von `online-mode`** `discovery.minecraftservices.com` und den Public-Key-Endpunkt abrufen (🔵 `Main.java:126`, `Services#create`, `MinecraftServicesDiscoveryService` mit `Proxy.NO_PROXY`). So scheitert der Abruf lokal: `Connection refused` auf 127.0.0.1:9.
- 🟢 Start: `Starting Minecraft server on 127.0.0.1:25565` … `Done (1.789s)!`

### 2.2 Client (`research/`)
Änderungen am Mod-Projekt (keine Minecraft-Dateien betroffen):

| Datei | Änderung |
|---|---|
| `build.gradle.kts` | neue Loom-Run-Konfiguration `localTest` → Task `runLocalTest`, eigenes Laufverzeichnis `run-localtest/`. **`runClient` unverändert.** |
| `src/main/resources/fabric.mod.json` | `client`-Entrypoint `dev.mcresearch.client.LocalTestClient` |
| `src/main/java/dev/mcresearch/client/LocalTestClient.java` | Testtreiber, **nur aktiv mit `-Dmcresearch.localtest=true`** (sonst sofortiges `return`) |

`runLocalTest` übergibt: `--quickPlayMultiplayer localhost:25565 --offlineDeveloperMode --username ResearchBot --proxyHost 127.0.0.1 --proxyPort 9`, JVM: `-Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery`, `-Dhttp(s).proxyHost=127.0.0.1 -Dhttp(s).proxyPort=9`.

**Schutz vor externen Kontakten im Client** (jeweils 🔵 per Quelle/Bytecode ermittelt, 🟢 Wirkung im Log bestätigt):

| Externer Zugriff | Ursprung | Maßnahme |
|---|---|---|
| Mojang-Dienste (Auth, Profile-Keys, Freunde) | `Minecraft.java:413` `MinecraftServicesDiscoveryService.create(proxy, !offlineDeveloperMode)` | `--offlineDeveloperMode` + Discovery-Override |
| Sperrliste `https://sessionserver.mojang.com/blockedservers` | `AddressCheck#createFromService` → ServiceLoader → `com.mojang.patchy.MojangBlockListSupplier` (`URL.openConnection()`, `patchy-2.2.10.jar`) | `-Dhttps.proxyHost=127.0.0.1:9` → Fehler wird intern gefangen (`catch Throwable` → `null`) |
| Realms, Skins u. a. HTTP | `Request`/`SkinTextureDownloader` mit Minecraft-Proxy | `--proxyHost 127.0.0.1 --proxyPort 9` (SOCKS) |
| **DNS-SRV `_minecraft._tcp.localhost`** (UDP an System-DNS) | `ServerRedirectHandler#createDnsSrvRedirectHandler` (JNDI), bei Port 25565 (`:37`) | Treiber setzt `NamingManager.setInitialContextFactoryBuilder` → JNDI wirft → Vanilla-Fallback `ServerRedirectHandler.EMPTY` (`:34`) |

**Paketprotokollierung im Client:** Der Treiber setzt per `Configurator.setLevel` die vorhandenen Logger `net.minecraft.network.PacketDecoder/PacketEncoder/Connection` auf DEBUG; ihre Ausgaben landen über Looms `log4j.xml` in `run-localtest/logs/debug.log`. Es wird kein eigener Netzwerkcode verwendet.

**Eingabe-Injektion:** genau wie `KeyboardHandler#keyPress`/`MouseHandler#onButton`: `KeyMapping.set(key, down)` + `KeyMapping.click(key)`. Die Verarbeitung erfolgt unverändert durch `Minecraft#handleKeybinds`, `KeyboardInput#tick`, `LocalPlayer#sendChanges`. Ausnahmen: Rotation per `setYRot/setXRot` (statt Mausdelta), Container-Klicks per `MultiPlayerGameMode#handleContainerInput` (derselbe Aufruf, den `AbstractContainerScreen` bei Slot-Klicks macht).

### 2.3 Hilfsprogramme (`analysis/tools/`)
- `netmon.ps1` – TCP/UDP der beiden Java-Prozesse sekündlich, markiert alles Nicht-Loopback als `EXTERNAL`.
- `coordinator.sh` – wartet auf Treiber-Marker und schreibt Serverkonsolen-Befehle (`give`, `spreadplayers`, `kick`).
- `analyze-runtime.js` – Abfolge, Pakete je Testschritt, Zählungen, Client↔Server-Konsistenz, Abgleich mit `packets.json`/`docs/07`.
- Rohdaten: `analysis/runtime-logs/run{1,2,3}/` (Client-`debug.log`, Server-`packets.log`, `analysis.txt`, `netmon.txt`, `coordinator.txt`). Per `.gitignore` ausgeschlossen.

### 2.4 Läufe

| Lauf | Zweck | Gültigkeit |
|---|---|---|
| 1 (21:38–21:40) | Gesamtablauf, clientseitiger Disconnect | ✅ gültig. **Manueller Eingriff:** der `AccessibilityOnboardingScreen` (neues Laufverzeichnis = Erststart) blockierte QuickPlay ca. 80 s und wurde im Fenster bestätigt. Er hat keinen Timeout (🔵 `AccessibilityOnboardingScreen#onClose :113`). Danach automatisch. |
| 2 (21:44–21:45) | Reproduzierbarkeit, serverseitiger Kick | ⚠ **teilweise ungültig** (eigener Testfehler): (a) persistiertes Spielerinventar aus Lauf 1 (Erde nicht mehr in der Hotbar) → Platzieren/Abbauen/Aufsammeln ins Leere; (b) Koordinator las anfangs das alte `latest.log` → `give` vor dem Join („No player was found“). **Gültig:** Join ohne manuellen Eingriff, Teleport, Kick. |
| 3 (21:46–21:47) | sauberer Wiederholungslauf nach Korrektur (Spielerdatensatz `world/players/data/<uuid>.dat` gelöscht, Koordinator wartet auf Log-Rotation) | ✅ vollautomatisch, alle Schritte erfolgreich, Kick |

---

## 3. Was wird tatsächlich protokolliert?

🟢 Format (Vanilla, `PacketDecoder#decode`/`PacketEncoder#encode`):
```
[21:39:38] [Netty NIO IO #0/DEBUG] (net.minecraft.network.PacketEncoder) OUT: [handshake:serverbound/minecraft:intention] net.minecraft.network.protocol.handshake.ClientIntentionPacket -> 16 bytes
```

| Enthalten | Nicht enthalten |
|---|---|
| Protokollphase, Richtung, Pakettyp-ID (Ressourcenname), Java-Klasse, Größe des dekodierten Frames (Paket-ID + Felder; ohne Längenpräfix, ohne Kompression, da aus) | **numerische Paket-ID**, **Feldinhalte** (Positionen, Action-Werte, Item-Daten …), Längenpräfix, Zeitstempel < 1 s (Client; Server-Log mit ms) |

Konsequenzen:
- Numerische IDs wurden separat über den **offiziellen Datengenerator** verifiziert: `java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports` → `packets.json` (§5).
- Inhalte wie `player_action`-Action-Werte sind 🟡 **gefolgert** (aus Testschritt + Quellcode), nicht gemessen.
- Bundle-Pakete erscheinen als einzelne Teilpakete plus `bundle_delimiter` (Decoder loggt vor dem `bundler`).
- Für Feldinhalte bräuchte es einen Mitschnitt (Wireshark, für REA `inspect_pcap_capture` nicht installiert) oder zusätzliches Logging im Mod. Darauf wurde verzichtet, um den Client nicht weiter zu verändern.

---

## 4. Beobachtete Paketabfolge (Client-Sicht, Lauf 1 = Lauf 2 = Lauf 3)

IDs aus `packets.json`. `×n` = n aufeinanderfolgende gleiche Pakete.

```
Zeit      Richt. Phase          ID    Pakettyp                                  Bytes
21:39:38  C→S   handshake      0x00  minecraft:intention                          16
21:39:38  C→S   login          0x00  minecraft:hello                              29
21:39:38  S→C   login          0x02  minecraft:login_finished                     46   ← kein Hello/Encryption, keine Compression (offline, −1)
21:39:38  C→S   login          0x03  minecraft:login_acknowledged                  1
21:39:38  C→S   configuration  0x02  minecraft:custom_payload  (brand)            24   ← Client sendet vor dem Server
21:39:38  C→S   configuration  0x00  minecraft:client_information                 15
21:39:38  S→C   configuration  0x01  minecraft:custom_payload  (brand "vanilla")  25
21:39:38  S→C   configuration  0x0D  minecraft:update_enabled_features            20
21:39:38  S→C   configuration  0x0F  minecraft:select_known_packs                 22
21:39:38  C→S   configuration  0x07  minecraft:select_known_packs                 22
21:39:38  S→C   configuration  0x07  minecraft:registry_data            ×32   (Σ 9 975)
21:39:38  S→C   configuration  0x0E  minecraft:update_tags                     58 755
21:39:40  S→C   configuration  0x03  minecraft:finish_configuration                1   ← Lauf 1: ~2 s (PrepareSpawnTask), Lauf 3: <1 s
21:39:40  C→S   configuration  0x03  minecraft:finish_configuration                1
21:39:40  S→C   play           0x32  minecraft:login                             110
          S→C   play           0x0A  change_difficulty · 0x41 player_abilities · 0x6B set_held_slot
          S→C   play           0x88  update_recipes (3 763) · 0x22 entity_event · 0x10 commands
          S→C   play           0x4D  recipe_book_settings · 0x4B recipe_book_add
          S→C   play           0x49  player_position  →  C→S 0x00 accept_teleportation
          S→C   play           0x58  server_data · 0x47 player_info_update ×2 · 0x2C initialize_border
          S→C   play           0x73  set_time · 0x63 set_default_spawn_position · 0x27 game_event
          S→C   play           0x82  ticking_state · 0x83 ticking_step · 0x60 set_chunk_cache_center
          S→C   play           0x00  bundle_delimiter / 0x01 add_entity / 0x65 set_entity_data / 0x00 …  (Entities als Bundles)
          S→C   play           0x53  post_effects · 0x12 container_set_content · …
          S→C   play           0x0C  chunk_batch_start · 0x2E level_chunk_with_light ×n · 0x0B chunk_batch_finished
          C→S   play           0x0B  chunk_batch_received   (je Batch)
21:39:41  C→S   play           0x2C  player_loaded
          C→S   play           0x0D  client_tick_end        (jeden Client-Tick)
```

---

## 5. Abgleich Paket-IDs: `docs/07` (statisch) vs. `packets.json` (offizieller Generator)

🟢 260/260 Pakete mit **identischer numerischer ID**, 0 Abweichungen.
6 Pakete unterscheiden sich nur im Namen zwischen Java-Konstante (`docs/07`) und Ressourcen-ID (Draht/Log):

| `docs/07` (PacketType-Konstante) | Ressourcen-ID | ID |
|---|---|---|
| `HandshakePacketTypes.CLIENT_INTENTION` | `minecraft:intention` | 0x00 |
| `GamePacketTypes.CLIENTBOUND_BUNDLE` | `minecraft:bundle_delimiter` | 0x00 |
| `…CLIENTBOUND_DEBUG_BLOCK_VALUE` / `_CHUNK_VALUE` / `_ENTITY_VALUE` / `_DEBUG_EVENT` | `minecraft:debug/block_value` / `debug/chunk_value` / `debug/entity_value` / `debug/event` | 0x1A–0x1D |

`docs/07` wurde um diesen Verifikationshinweis ergänzt.

---

## 6. Gameplay-Tests (Lauf 3, Bestätigung durch Lauf 1)

Pakete je Testschritt (nur C→S; `client_tick_end` läuft in jedem Schritt mit, 1 pro Tick):

| Test | Eingabe (Treiber) | Beobachtete C→S-Pakete 🟢 | Beobachtetes Ergebnis 🟢 | Abgleich |
|---|---|---|---|---|
| **Gehen** | `keyUp` 30 Ticks gehalten | `player_input` ×1 (0x2B), `move_player_pos` ×8 (Lauf 1: ×30) | Lauf 1: 6,0 Blöcke in 30 Ticks; Lauf 3: 1,2 Blöcke, dann blockiert | ✅ `LocalPlayer#sendPosition`: Positionspaket nur bei Δ > 2·10⁻⁴ → nach Blockade keine weiteren Pakete. Lauf 1: 6 Blöcke / 1,5 s ≈ 4 m/s (Gehen ≈ 4,3 m/s inkl. Anlauf) |
| **Springen** | `keyJump` 2 Ticks | `player_input` ×2 (gedrückt/losgelassen), `move_player_pos` | Lauf 1: y 65,00 → 66,25 ✅; Lauf 3: kein Höhengewinn bei Tick 6 ⚠ | Ursache nicht untersucht (Bewegung war in Lauf 3 ebenfalls blockiert – vermutlich Kollision) |
| **Rotation** | `setYRot/setXRot` | `move_player_rot` (0x20) bzw. `move_player_pos_rot` (0x1F) | – | ✅ |
| **Positions-Heartbeat** | Stillstand (SETTLE, 100 Ticks) | `move_player_pos` ×5, `move_player_status_only` ×1 | – | ✅ 20-Tick-Erinnerung (`positionReminder`) 🟡 |
| **Hotbar** | Taste 9, dann 1 | `set_carried_item` ×2 (0x36) | Slot 0 = Erde | ✅ |
| **Block platzieren** | `keyUse`-Klick auf Oberseite | `use_item_on` ×1 (0x42) | Erde bei (8,65,2), Inventar 4→3; S→C `block_changed_ack` (0x04), `block_update` | ✅ |
| **Fackel platzieren** | Hotbar 2, `keyUse` | `set_carried_item`, `use_item_on` | Fackel bei (8,66,2) | ✅ |
| **Block abbauen** | `keyAttack`-Klick (Fackel: Abbauzeit 0) | `player_action` ×1 (0x29, 🟡 `START_DESTROY_BLOCK`), **`punch` ×1 (0x2E)** | Block → Luft, Item-Entity gespawnt | ✅; `punch` aus `Minecraft#startAttack :1687` 🔵 |
| **Aufsammeln** | Richtung Item laufen | `player_input`, `move_player_pos_rot` ×6 | Fackeln 3→4 nach 6 Ticks; S→C `entity_event`, `container_set_slot` (0x14) | ✅ |
| **Offhand tauschen** | `keySwapOffhand` ×2 | `player_action` ×2 (🟡 `SWAP_ITEM_WITH_OFFHAND`) | Fackel Haupt↔Nebenhand | ✅ `Minecraft.java:1922` |
| **Drop** | `keyDrop` | `player_action` ×1 (🟡 `DROP_ITEM`) | 4→3 | ✅ |
| **Inventar öffnen** | `keyInventory` | **kein Paket** | `InventoryScreen`, containerId 0 | ✅ Survival-Inventar ist rein clientseitig (`isServerControlledInventory` = false) |
| **Container-Klicks** | PICKUP Slot 36, PICKUP Slot 9 | `container_click` ×2 (0x12) | Erde von Hotbar 0 nach Inventarslot 9 | ✅ |
| **Inventar schließen** | `Screen#onClose` | `container_close` (0x13) | – | ✅ |
| **Keep-Alive** | – | S→C `keep_alive` (0x2D) → C→S `keep_alive` (0x1C) | Antwort nach ~1 ms (Server-Zeitstempel 21:39:55.445 → .446), Intervall ≈ 15 s | ✅ |

### Chunk-Laden

| Beobachtung 🟢 | Lauf 1 | Lauf 3 |
|---|---|---|
| geladene Chunks 1 s nach Join → nach 5 s | 37 → 417 | 250 → 473 |
| Endzustand (view-distance 10) | 473 / 547 | 473 |
| `level_chunk_with_light` (0x2E) | 913 Pakete, Σ 35,3 MB unkomprimiert | 890, Ø 38,5 kB, max 64,7 kB |
| `chunk_batch_start` / `_finished` / C→S `chunk_batch_received` | je 108 | – |
| Teleport ~300 Blöcke (`spreadplayers`) | `forget_level_chunk` ×417 (0x26), `remove_entities`, danach ~411 neue Chunks; `set_chunk_cache_center` | ✅ |
| `player_position` je `spreadplayers` | **2** (je mit `accept_teleportation` beantwortet) | 2 |

Weitere häufige S→C-Pakete (Lauf 1, ≈ 30 s Play): `rotate_head` 7 041, `set_entity_motion` 6 138, `move_entity_pos` 4 878, `section_blocks_update` 4 761, `move_entity_pos_rot` 4 423, `block_update` 2 717, `light_update` 626 (1,8 MB). 84 verschiedene Pakettypen.

### Disconnect

| Variante | Beobachtung 🟢 |
|---|---|
| **clientseitig** (Lauf 1, `Minecraft#disconnectFromWorld` = Pausenmenü) | Letztes Client-Paket `client_tick_end`; **kein Abmeldepaket**, nur TCP-Close. Server: `ResearchBot lost connection: Disconnected`. Client-Screen danach: `JoinMultiplayerScreen`. |
| **serverseitig** (Lauf 2/3, Konsole `kick`) | S→C `play/disconnect` (0x20, 52 B) mit Grund, dann Close. Client-WARN `Client disconnected with reason: …`, Screen `DisconnectedScreen` („Connection Lost“). |

---

## 7. Abweichungen gegenüber `docs/08-verbindung-lokaler-server.md`

| # | `docs/08` (Quelle) | Laufzeit 🟢 | Bewertung |
|---|---|---|---|
| 1 | Properties-Tabelle ohne `white-list` | Default ist **`white-list=true`** | **Fehlend** – ohne Änderung würde jeder Join abgewiesen. In 08 ergänzt. |
| 2 | Login online: Hello/Key/Compression | offline + Threshold −1: direkt `login_finished` | ✅ wie für Offline beschrieben; Online-Zweig **nicht getestet** |
| 3 | Configuration: Server sendet brand, features, SelectKnownPacks; Client brand + ClientInformation | exakt so; Client-Pakete kommen **vor** denen des Servers | ✅ |
| 4 | `placeNewPlayer`: Login → Difficulty → Abilities → HeldSlot → Recipes → EntityEvent → Rezeptbuch → Beitrittsnachricht → PlayerPosition → … | zusätzlich **`commands`** (nach `entity_event`), **`recipe_book_settings`**, `game_event`, `ticking_state/step`, `set_chunk_cache_center`, `container_set_content`; **keine Beitrittsnachricht beim Beitretenden** | **Unvollständig.** Ursache Beitrittsnachricht: `broadcastSystemMessage` (`PlayerList.java:201`) läuft vor `players.add` (`:209`) 🔵 |
| 5 | Minimaler Dialog ohne `player_loaded` | Client sendet **`player_loaded` (0x2C)** kurz nach Join | **Fehlend** in 08. 🔵 Nicht zwingend: ohne das Paket gilt der Client nach 60 Ticks als geladen (`CLIENT_LOADED_TIMEOUT_TIME`, `ServerGamePacketListenerImpl.java:236/2261`); bis dahin ignoriert der Server Bewegung/Interaktion (`hasClientLoaded()`-Prüfungen). → eigener Client sollte es senden. |
| 6 | Chunk-Batching + `chunk_batch_received` | ✅ je Batch | ✅ |
| 7 | KeepAlive mit gleicher ID beantworten | ✅ (~1 ms) | ✅ |
| 8 | Teleport → AcceptTeleportation | ✅; `spreadplayers` erzeugt 2 Teleports | ✅ (Detail ergänzt) |
| 9 | Disconnect nicht detailliert | clientseitig **ohne Paket**, serverseitig `disconnect` 0x20 | ergänzt |
| 10 | Abbau: `player_action` | zusätzlich **`punch` (0x2E)** bei jedem Schlag (neu in 26.x) | **Fehlend** in 04/08 |
| 11 | „Während Login und Configuration tickt `ConnectScreen` die Verbindung“ | nicht direkt messbar | 🔵 unverändert, nicht verifiziert |
| 12 | Zeilenangaben/Klassen | unverändert gültig (Hashes der JARs unverändert) | ✅ |

---

## 8. Fehlermeldungen und Warnungen

| Quelle | Meldung | Ursache | Bewertung |
|---|---|---|---|
| Server (`--initSettings`) | `ERROR Failed to load properties … NoSuchFileException` | erster Start ohne `server.properties` | erwartet |
| Server + Client | `WARN Failed to connect … http://127.0.0.1:9/discovery … Connection refused` (Server 19×, Client 1–2×) | absichtliche Umlenkung der Mojang-Discovery | **gewollt** |
| Server | `Services are unavailable, unable to fetch uri for AUTHENTICATION/getPublicKeys` | dito | gewollt |
| Server | `**** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!` | `online-mode=false` | erwartet |
| Client | `ERROR Failed to initialize SRV redirect resolved, some servers might not work` (sic) | JNDI absichtlich deaktiviert (keine externe DNS-Abfrage) | **gewollt**; Vanilla fällt sauber zurück |
| Client | `WARN Ignoring chat session from ResearchBot due to missing Services public key` | keine Profile-Keys (offline) | erwartet; Chat-Signierung inaktiv |
| Client | `WARN Requested post effect does not exist: minecraft:end_of_frame` | `GameRenderer.END_OF_FRAME_POST_EFFECT` (`:105`) wird angefordert, Vanilla liefert kein `post_effect/end_of_frame.json` | Vanilla-Verhalten, testunabhängig |
| Client (Lauf 3) | `WARN Client disconnected with reason: Laufzeittest beendet …` | Kick | erwartet |
| Server (Lauf 2) | `No player was found` ×2 | eigener Koordinatorfehler (Befehle vor Join) | behoben |
| Gradle | `'configName'/'runDir'/'vmArgs'/'programArgs' is deprecated` | Loom-1.19-Kotlin-DSL | kosmetisch; Funktion OK |
| netmon | Client-UDP `:::4445` nach Disconnect | `LanServerDetection` (`MulticastSocket(4445)`, Gruppe 224.0.2.60) im `JoinMultiplayerScreen` – nur Empfang | kein Serverkontakt |

Keine Exceptions in der Paketverarbeitung, keine Protokollfehler, keine Abstürze.

---

## 9. Grenzen dieses Tests

- Feldinhalte von Paketen nicht geloggt (§3) → Action-Werte und Koordinaten auf dem Draht nur gefolgert.
- `netmon` tastet sekündlich ab: sehr kurzlebige Verbindungen (z. B. sofort abgewiesene Versuche auf 127.0.0.1:9) können fehlen – diese sind aber Loopback. DNS-Abfragen anderer Prozesse (Windows-DNS-Client) werden nicht erfasst.
- Online-Modus (RSA/AES, Session-Server), Kompression, Status-Ping, Ressourcenpakete, Chat, Kampf, Fahrzeuge, Dimensionen, Respawn, Rückkehr in Configuration (`start_configuration`) **nicht getestet**.
- Survival-Abbau mit **gehaltener** Taste nicht getestet: `Minecraft#continueAttack` (`Minecraft.java:1592`) wird aus `handleKeybinds` nur mit `isDown && mouseHandler.isMouseGrabbed()` aufgerufen, sonst bricht `stopDestroyBlock` den Abbau jeden Tick ab (🔵), daher Test mit Sofortabbau-Block.
- Das normale `runClient` wurde nach den Änderungen **nicht erneut gestartet**, weil es ohne Offline-Schutz Mojang-Dienste kontaktieren würde (Vorgabe: keine externen Server). Funktionsfähigkeit: Build erfolgreich, `runClient`-Konfiguration unverändert, Treiber ohne Property inaktiv.

---

## 10. Erkenntnisse für den unabhängigen Client

1. **Minimal-Handshake bis Play ist vollständig belegt** (§4). Offline ohne Kompression braucht der Client bis zum Spawn genau: `intention`, `hello`, `login_acknowledged`, `custom_payload`(brand), `client_information`, `select_known_packs`, `finish_configuration`, dann `accept_teleportation`, `chunk_batch_received`, `player_loaded`, `keep_alive`, `client_tick_end`.
2. **Registry-Sync ist der größte Brocken der Configuration-Phase:** 32 `registry_data` + `update_tags` (~59 kB) 🟢. Bestätigt der Client die angebotenen Known Packs (`minecraft:core`), lässt der Server die Eintragsinhalte weg (🔵 `RegistrySynchronization.java:51-57`: `canSkipContents` → `Optional.empty()`; 🟡 passt zu Σ 9 975 B für 32 Registries) → ein eigener Client muss dann die **Vanilla-Registry-Inhalte selbst mitbringen**. Antwortet er mit einer leeren/abweichenden Pack-Liste, sendet der Server alle Inhalte vollständig (🔵 `SynchronizeRegistriesTask#handleResponse`). Für den ersten eigenen Client ist die zweite Variante einfacher. → nächster Analysepunkt.
3. **Play-Codecs hängen von Registries ab** (`RegistryFriendlyByteBuf`): Items/Komponenten/Blockzustände benötigen synchronisierte Registry-IDs; `blocks.json`/`registries.json` aus dem Datengenerator sind dafür die Referenz.
4. **Pflichtantworten:** `accept_teleportation` je `player_position` (auch mehrere hintereinander), `chunk_batch_received` je Batch, `keep_alive` mit derselben ID, `client_tick_end` pro Tick.
5. **Bewegung:** 20 TPS, Positionspaket nur bei Änderung + 20-Tick-Heartbeat, `player_input` nur bei Tastenänderung.
6. **Bundles:** `bundle_delimiter` umschließt zusammengehörige Pakete (z. B. Entity-Spawn + Metadaten) – atomar anwenden.
7. **Datenvolumen:** ~35 MB unkomprimierte Chunkdaten für den Erst-Join bei view-distance 10 → Kompression später aktivieren; anfangs kleinere `view-distance` im `client_information` senden.
8. **Testinfrastruktur ist wiederverwendbar:** Server-Setup, Paket-DEBUG-Logging, Datengenerator-Report und `analyze-runtime.js` können die Pakete eines eigenen Clients direkt mit dem Referenzclient vergleichen.

---

## 11. Reproduktion

```bash
# Server (in server/)
: > console.in
tail -n 0 -f console.in | java -Xmx2G -Dlog4j.configurationFile=log4j2-packets.xml \
  -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery -jar server.jar nogui > logs/stdout.txt 2>&1 &
# Monitor + Koordinator (im Projektwurzelverzeichnis)
powershell -File analysis/tools/netmon.ps1 -Seconds 600 &
bash analysis/tools/coordinator.sh research/run-localtest/logs/latest.log server/console.in analysis/runtime-logs/coordinator.txt &
# Client (optional serverseitiger Kick: touch research/run-localtest/localtest-kick.flag)
(cd research && ./gradlew runLocalTest)
# Auswertung
node analysis/tools/analyze-runtime.js research/run-localtest/logs/debug.log server/logs/packets.log \
  analysis/runtime-logs/reports/reports/packets.json docs/07-paket-ids.md
# Offizieller Paket-Report
(cd server && java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --output ../analysis/runtime-logs/reports)
```
Vor einem Wiederholungslauf `server/world/players/data/<uuid>.dat` von `ResearchBot` löschen (sonst persistiertes Inventar, vgl. Lauf 2) und für einen Erststart `onboardAccessibility:false` in `run-localtest/options.txt` setzen.
