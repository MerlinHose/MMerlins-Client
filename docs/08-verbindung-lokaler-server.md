# 08 – Verbindungsaufbau zu einem lokalen Vanilla-Server

Ergänzt [05-netzwerk.md](05-netzwerk.md) um die **Gegenseite (Vanilla-Server)** und den konkreten Ablauf `localhost:25565`.
Status: statisch analysiert **und im Laufzeittest überprüft** (2026-11-06, [10-laufzeittest.md](10-laufzeittest.md)). Abweichungen sind unten mit **[Laufzeit]** markiert; vollständige Liste in 10 §7.

## Vanilla-Server-Artefakt

| Datei (Loom-Cache `~/.gradle/caches/fabric-loom/26.3/`) | Inhalt |
|---|---|
| `minecraft-server.jar` (62 MB, 165 Einträge) | **Offizielles Vanilla-`server.jar`** (Bundler): `Main-Class: net.minecraft.bundler.Main`, startet `net.minecraft.server.Main` (`META-INF/main-class`), Bibliotheken in `META-INF/libraries.list` |
| `minecraft-extracted_server.jar` (18 557 Einträge) | Der vom Bundler eingebettete eigentliche Server |
| `minecraft-common.jar` | Von Loom abgeleiteter gemeinsamer Teil (Client ∩ Server) |

**REA-Befund** (`compare_artifacts`, siehe [09](09-rea-funktionspruefung.md)): `minecraft-extracted_server.jar` und `minecraft-common.jar` sind in **17 651 Dateien byte-identisch**. Unterschiede bestehen nur bei der Mojang-Signatur (`META-INF/MOJANGCS.RSA/.SF`, `META-INF/LICENSE` entfernt) und `MANIFEST.MF`.
→ Der Netzwerk-/Protokollcode, den der Client nutzt (`net.minecraft.network.*`), ist **derselbe Bytecode wie im Vanilla-Server**. Protokollverhalten aus den Quellen gilt damit 1:1 für einen echten Vanilla-Server 26.3.

## Relevante `server.properties` (Defaults aus `server/dedicated/DedicatedServerProperties.java`)

| Schlüssel | Default | Zeile | Wirkung auf den Client |
|---|---|---:|---|
| `server-port` | 25565 | 72 | Ziel-Port |
| `server-ip` | "" (alle Interfaces) | 58 | Bind-Adresse |
| `white-list` | **true** | – | **[Laufzeit]** fehlte hier: Default in 26.3 ist `true` → ohne Whitelist-Eintrag wird jeder Join abgewiesen. Für Tests auf `false` setzen. |
| `online-mode` | **true** | 56 | true → RSA/AES-Verschlüsselung + Mojang-Sessionprüfung; false → keine Verschlüsselung, Offline-UUID |
| `network-compression-threshold` | 256 | 107 | Schwelle für zlib; < 0 = aus |
| `enforce-secure-profile` | true | 126 | nur wirksam, wenn auch `online-mode` (`DedicatedServer#enforceSecureProfile`, `DedicatedServer.java:621`) |
| `enable-status` | true | 114 | false → Status-Anfragen werden getrennt |
| `accepts-transfers` | false | 131 | Intent `TRANSFER` wird abgelehnt |
| `rate-limit` | 0 | 101 | > 0 → `RateKickingConnection` |
| `prevent-proxy-connections` | false | 57 | |
| `view-distance` / `simulation-distance` | 10 / 10 | 104-105 | Chunk-Radius |

Start des Servers erfordert die Zustimmung zur Minecraft-EULA in `eula.txt` (`server/Main.java:113-120`); ohne sie beendet sich der Server nach dem Erzeugen der Dateien.

**Für die Entwicklung eines eigenen Clients empfohlen:** `online-mode=false` (kein Microsoft-Konto/Session-Server nötig, keine Verschlüsselung) und anfangs `network-compression-threshold=-1` (Kompression aus), später wieder 256.

## Ablauf – Client-Seite

| # | Schritt | Referenz |
|---|---|---|
| 1 | Adresse parsen, Default-Port 25565 | `client/multiplayer/resolver/ServerAddress#parseString` (`ServerAddress.java:37-43`) |
| 2 | Einstieg über Serverliste oder `--quickPlayMultiplayer localhost` | `ConnectScreen#startConnecting` (`client/gui/screens/ConnectScreen.java:58`); QuickPlay: `client/quickplay/QuickPlay.java:110` |
| 3 | Eigener Thread „Server Connector #n“ | `ConnectScreen#connect` (`:92-94`) |
| 4 | Auflösung inkl. **SRV-Lookup** `_minecraft._tcp.<host>` und Blocklist-Prüfung | `ServerNameResolver.DEFAULT#resolveAddress` (`resolver/ServerNameResolver.java:20`), `ServerRedirectHandler` (`:40`), `AddressCheck` |
| 5 | `new Connection(PacketFlow.CLIENTBOUND)`, `Connection.connect(...)` (Netty, `TCP_NODELAY`, 30-s-Read-Timeout), `syncUninterruptibly` | `ConnectScreen.java:124-128`, `network/Connection.java:425` |
| 6 | `initiateServerboundPlayConnection(host, port, LoginProtocols…, new ClientHandshakePacketListenerImpl(...))` → sendet **ClientIntention(777, host, port, LOGIN)** und schaltet auf Login | `ConnectScreen.java:141-157`, `Connection.java:255-267` |
| 7 | **Hello(name, profileId)** senden | `ConnectScreen.java:159` |
| 8 | Login **und** Configuration: `ConnectScreen#tick` tickt die Verbindung (`Connection#tick`: Queue flushen, `TickablePacketListener#tick`, Disconnect-Erkennung) | `ConnectScreen.java:202-205`, `network/Connection.java:343` |
| 9 | Nach `LoginFinished`: `ClientConfigurationPacketListenerImpl` als Inbound-Listener, sofort `LoginAcknowledged`, Outbound auf Configuration, `CustomPayload(brand)`, `ClientInformation` | `ClientHandshakePacketListenerImpl.java:175-206` |
| 10 | Nach `FinishConfiguration`: `ClientPacketListener`, `Login`-Paket erzeugt `ClientLevel`/`LocalPlayer` | `ClientConfigurationPacketListenerImpl.java:150-184`, `ClientPacketListener.java:496` |
| 11 | Play: Verbindung wird aus `MultiPlayerGameMode#tick` getickt (aufgerufen in `Minecraft#tick`) | `client/multiplayer/MultiPlayerGameMode.java:301` |

(`Minecraft.pendingConnection`, getickt in `Minecraft#tick :1857`, wird nur in `Minecraft#doWorldLoad` (`:2061`) gesetzt, also für den integrierten Server im Einzelspieler.)

## Ablauf – Server-Seite

### Listener
`ServerConnectionListener#startTcpServerListener` (`server/network/ServerConnectionListener.java:58`): Netty-`ServerBootstrap`, Pipeline `timeout` (30 s) → `legacy_query` (`LegacyQueryHandler`, Ping alter Clients) → `Connection.configureSerialization` → `Connection` bzw. `RateKickingConnection`; Start-Listener ist `ServerHandshakePacketListenerImpl` (`:79`).

### Handshake – `ServerHandshakePacketListenerImpl#handleIntention` (`server/network/ServerHandshakePacketListenerImpl.java:15`)
- `STATUS` → Status-Protokoll (oder Trennung, wenn `enable-status=false`).
- `LOGIN` → `beginLogin`: **Protokollversion ≠ 777 → Disconnect** „outdated_client/outdated_server“, sonst `ServerLoginPacketListenerImpl`.
- `TRANSFER` → nur wenn `accepts-transfers=true`.

### Login – `ServerLoginPacketListenerImpl` (`server/network/ServerLoginPacketListenerImpl.java`)
Zustände (`State`, `:256`): `HELLO → KEY → AUTHENTICATING → NEGOTIATING → VERIFYING → WAITING_FOR_DUPE_DISCONNECT → PROTOCOL_SWITCHING → ACCEPTED`. Timeout: 600 Ticks (30 s) bis zum Login (`MAX_TICKS_BEFORE_LOGIN :52`).

```
C→S Hello(name, uuid)                         handleHello :118  (Name wird validiert)
 ├─ online-mode=true:
 │    S→C Hello("", publicKey, challenge(4 B Zufall), shouldAuthenticate=true)   :128
 │    C→S Key(enc(sharedSecret), enc(challenge))   handleKey :169 → Challenge prüfen,
 │        Verschlüsselung an, Session-Server hasJoinedServer(name, hash) :195
 │        → Erfolg: echtes GameProfile; Fehler → Disconnect "unverified_username"
 └─ online-mode=false:
      UUIDUtil.createOfflineProfile(name)   :130   (UUID.nameUUIDFromBytes("OfflinePlayer:"+name), core/UUIDUtil.java:86)
VERIFYING (tick :73) → verifyLoginAndFinishConnectionSetup :140
 ├─ S→C LoginCompression(threshold), danach Pipeline-Kompression an   :146-150
 ├─ ggf. Doppel-Login: alten Spieler trennen (WAITING_FOR_DUPE_DISCONNECT)
 └─ S→C LoginFinished(profile, sessionId)                              :165
C→S LoginAcknowledged → ServerConfigurationPacketListenerImpl           handleLoginAcknowledgement :236
```

Unterschied zum Einzelspieler: Beim integrierten Server (`isMemoryConnection`) entfallen Verschlüsselung und Kompression (`:126`, `:146`).

### Configuration – `ServerConfigurationPacketListenerImpl` (`server/network/ServerConfigurationPacketListenerImpl.java`)
`startConfiguration` (`:88`) sendet sofort:
1. `CustomPayload(minecraft:brand = "vanilla")` (`MinecraftServer#getServerModName`, `server/MinecraftServer.java:1201`)
2. `ServerLinks` (falls konfiguriert)
3. `UpdateEnabledFeatures`

und stellt eine **Task-Queue** auf, die nacheinander abgearbeitet wird (`server/network/config/`):

| Task | Pakete | Client-Antwort |
|---|---|---|
| `SynchronizeRegistriesTask` | S→C `SelectKnownPacks` → (nach Antwort) `RegistryData`× n, `UpdateTags` | C→S `SelectKnownPacks` (`handleSelectKnownPacks :149`) |
| `ServerCodeOfConductConfigurationTask` (optional) | Verhaltenskodex | C→S `AcceptCodeOfConduct` (`:160`) |
| `ServerResourcePackConfigurationTask` (optional) | `ResourcePackPush` | C→S `ResourcePack`-Status (`:141`) |
| `PrepareSpawnTask` | lädt Spawn-Chunks serverseitig vor (`ChunkLoadCounter`) | – |
| `JoinWorldTask` | S→C **`FinishConfiguration`** | C→S `FinishConfiguration` |

`handleConfigurationFinished` (`:165`): Outbound auf Game-Protokoll, Prüfungen (Doppel-Login, Bann/Whitelist via `PlayerList#canPlayerLogin`), dann `PrepareSpawnTask#spawnPlayer` → **`PlayerList#placeNewPlayer`** (`server/players/PlayerList.java:147`).

### Play-Eintritt – `PlayerList#placeNewPlayer`
**[Laufzeit]** Beobachtete Reihenfolge (10 §4): `login` → `change_difficulty` → `player_abilities` → `set_held_slot` → `update_recipes` → `entity_event` → **`commands`** → **`recipe_book_settings`** → `recipe_book_add` → `player_position` → `server_data` → `player_info_update` ×2 → `initialize_border` → `set_time` → `set_default_spawn_position` → `game_event` → `ticking_state` → `ticking_step` → `set_chunk_cache_center` → Entity-Bundles → `post_effects` → `container_set_content` → Chunk-Batches. **Keine Beitrittsnachricht** an den Beitretenden: `broadcastSystemMessage` (`PlayerList.java:201`) läuft vor `players.add` (`:209`).

Quellbasierte Reihenfolge (relativ zu `:147`): `new ServerGamePacketListenerImpl` → **`Login`** (`+23`) → `ChangeDifficulty` → `PlayerAbilities` → `SetHeldSlot` → `UpdateRecipes` → Permission-Level (`ClientboundEntityEventPacket`, `PlayerList.java:575`) → Rezeptbuch → Beitrittsnachricht (Broadcast) → **`teleport(...)` = `PlayerPosition`** (`+56`, Client muss mit `AcceptTeleportation` antworten) → Server-Status → `PlayerInfoUpdate` an alle → `sendLevelInfo` (Weltgrenze, Zeit, Spawn, Wetter) → aktive Effekte → Post-Effekte. Anschließend streamt `ServerGamePacketListenerImpl`/`ChunkMap` Chunks in Batches (`ChunkBatchStart` … `LevelChunkWithLight` … `ChunkBatchFinished`).

## Minimaler Paketdialog eines eigenen Clients (offline, ohne Kompression)

```
→ ClientIntention(777, "localhost", 25565, LOGIN)        handshake/serverbound 0x00
→ Hello(name, uuid)                                       login/serverbound    0x00
← LoginFinished(profile)                                   login/clientbound    0x02
→ LoginAcknowledged                                        login/serverbound    0x03
→ CustomPayload(brand), ClientInformation                  (direkt danach, schon Configuration)
← CustomPayload(brand), UpdateEnabledFeatures, SelectKnownPacks
→ SelectKnownPacks(Auswahl)
← RegistryData…, UpdateTags, FinishConfiguration
→ FinishConfiguration                                      configuration/serverbound 0x03
← Login, …, PlayerPosition
→ AcceptTeleportation(id)                                  play/serverbound 0x00
← ChunkBatchStart, LevelChunkWithLight…, ChunkBatchFinished
→ ChunkBatchReceived(chunksPerTick)                        play/serverbound 0x0B
→ PlayerLoaded                                             play/serverbound 0x2C   [Laufzeit: fehlte hier]
↔ KeepAlive (Antwort mit gleicher ID), pro Tick MovePlayer*/ClientTickEnd
```

**[Laufzeit]** Dieser Dialog wurde mit dem Fabric-Client gegen den Vanilla-Server bestätigt; alle IDs stimmen mit `packets.json` aus dem offiziellen Datengenerator. Disconnect: clientseitig **ohne** Paket (nur TCP-Close), serverseitig `play/disconnect` (0x20).

IDs aus [07-paket-ids.md](07-paket-ids.md); vor einer Implementierung gegen die Tabelle prüfen (siehe Abschnitt Login/Configuration dort).

## Offene Punkte

- ~~Laufzeitverifikation~~ → erledigt, siehe [10-laufzeittest.md](10-laufzeittest.md). Weiterhin offen: Online-Modus (Verschlüsselung), Kompression, Status-Ping.
- (historisch) **Laufzeitverifikation**: `server/` war leer. Für einen echten Test müssten das Vanilla-`server.jar` dort gestartet und die **EULA akzeptiert** werden – das ist eine Entscheidung des Nutzers. Danach: `runClient` mit `--quickPlayMultiplayer localhost:25565` und Debug-Logging des Markers `PACKET_RECEIVED`/`PACKET_SENT` (`PacketDecoder`/`PacketEncoder` loggen auf `DEBUG`).
- **Paketmitschnitt**: REA `inspect_pcap_capture` benötigt Wireshark 4.x (`tshark`, `capinfos`), das derzeit nicht installiert ist ([09](09-rea-funktionspruefung.md)). Bei `online-mode=false` und Kompression aus wäre der Mitschnitt vollständig lesbar.
