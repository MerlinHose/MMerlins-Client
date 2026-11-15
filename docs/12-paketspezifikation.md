# 12 – Paketspezifikation für den minimalen Protokollclient (Protokoll 777)

Grundlage: Loom-Quellen 26.3 (`analysis/mc-src/`, Referenzen relativ dazu), IDs aus dem offiziellen Report `packets.json` (`net.minecraft.data.Main --reports`, 260 Pakete, mit `docs/07` identisch), Laufzeitbeobachtungen aus [10](10-laufzeittest.md).
Alle Zahlen big-endian (Netty-Standard). Diese Spezifikation beschreibt **Formate**; der Client in `client/` ist eine eigene Implementierung und enthält keinen Mojang-Code.

## 1. Wie viele Pakete werden wirklich benötigt?

Die Schätzung „~16“ aus [11](11-technischer-bericht.md) wurde gegen den Quellcode geprüft. Ergebnis:

| Kategorie | C→S | S→C | Begründung |
|---|---:|---:|---|
| **A – zwingend** für Login → Play → 60 s stabil | **7** | **9** | ohne diese Pakete bricht der Server ab bzw. der Zustandswechsel findet nicht statt |
| **B – empfohlen** (Verhalten wie Vanilla-Client) | 5 | 4 | Server funktioniert auch ohne, aber mit Einschränkungen |
| **C – bedingt** (nur wenn der Server sie sendet; Antwort dann Pflicht) | 8 | 13 | Cookies (×3 Zustände), Ping (×2), Custom Query, Kompression, Online-Modus-Erkennung, Reconfiguration, Disconnects |
| **Status-Abfrage** (eigene Verbindung, `intention` wie A) | 2 | 2 | `status_request`, `ping_request` / `status_response`, `pong_response` |
| Summe eindeutiger Pakettypen (Zustand + Name) im Client | **22** | **28** | gegen die Implementierung in `client/` gezählt |

**A – zwingend (C→S, 7):** `intention`, `hello`, `login_acknowledged`, `select_known_packs` (config), `finish_configuration` (config), `accept_teleportation`, `keep_alive` (play).
Nachweise: Server wartet in `SynchronizeRegistriesTask` auf `select_known_packs` (`server/network/config/SynchronizeRegistriesTask.java:46`), `JoinWorldTask` auf `finish_configuration` (`ServerConfigurationPacketListenerImpl.java:165`), ohne `accept_teleportation` bleibt `awaitingPositionFromClient` gesetzt (Bewegung wird verworfen, Teleport wiederholt; `ServerGamePacketListenerImpl.java:545`), fehlendes `keep_alive` → Kick nach 15 s (`ServerCommonPacketListenerImpl.java:122-125`).

**A – zwingend (S→C, 9, müssen dekodiert werden):** `login_finished`, `select_known_packs`, `registry_data`, `update_tags`, `finish_configuration`, `login` (play), `player_position`, `keep_alive` (play), `bundle_delimiter`.

**B – empfohlen:** C→S `client_information` (sonst gilt `ClientInformation#createDefault`: en_us, view-distance 2, `server/level/ClientInformation.java:47`), `custom_payload` brand, `chunk_batch_received` (sonst stoppt der Chunk-Versand nach dem ersten Batch, `PlayerChunkSender.java:51`), `player_loaded` (sonst 60 Ticks Eingabesperre, `ServerGamePacketListenerImpl.java:236/2261`), `client_tick_end`. S→C `custom_payload` (brand), `update_enabled_features`, `chunk_batch_start/finished`.

**C – bedingt:** C→S `cookie_response` (login/config/play), `custom_query_answer`, `pong` (config/play), `keep_alive` (config, wenn Configuration > 15 s), `configuration_acknowledged`; S→C `login_disconnect`, `login_compression`, `hello` (login, Online-Modus → **nicht unterstützt**, sauberer Abbruch), `custom_query`, `cookie_request` (×3), `ping` (config/play), `keep_alive` (config), `disconnect` (config/play), `start_configuration`.

Alle anderen 213 IDs werden **erkannt** (müssen in der ID-Tabelle des aktuellen Zustands stehen, sonst Protokollfehler) und mit ihrer Framelänge übersprungen.

## 2. Grunddatentypen

| Typ | Kodierung | Grenzen / Prüfungen | Quelle |
|---|---|---|---|
| VarInt | 7 Bit pro Byte, LSB zuerst, Bit 7 = Fortsetzung | max. 5 Bytes, sonst Fehler | `network/VarInt.java` |
| VarLong | dto. | max. 10 Bytes | `network/VarLong.java` |
| Boolean | 1 Byte, 0/1 | Client akzeptiert nur 0 oder 1 (Vanilla: ≠0 = true) | Netty |
| Byte / UByte / Short / UShort / Int / Long / Float / Double | Fixbreite, big-endian | – | Netty |
| String(n) | VarInt Bytelänge + UTF-8 | Bytelänge ≤ 3·n, Zeichenzahl ≤ n, Länge ≥ 0, ≤ Restbytes | `network/Utf8String.java#read` |
| Identifier | String(32767) `namespace:path` | namespace `[a-z0-9_.-]`, ≠ `..`; path `[a-z0-9_./-]`; ohne `:` → `minecraft:` | `resources/Identifier.java#parse, isValidPath, isValidNamespace` |
| UUID | 2 × Long (MSB, LSB) | – | `network/FriendlyByteBuf.java:412` |
| ByteArray(max) | VarInt Länge + Bytes | Länge ≤ max bzw. ≤ Restbytes | `FriendlyByteBuf#readByteArray` |
| Optional/Nullable\<T\> | Boolean + T | – | `ByteBufCodecs#optional`, `FriendlyByteBuf#readNullable` |
| List/Collection\<T\>(max) | VarInt Anzahl + Elemente | Anzahl ≤ max (`ByteBufCodecs.readCount`) | `network/codec/ByteBufCodecs.java:429` |
| OptionalVarInt | VarInt, 0 = leer, sonst Wert+1 | – | `ByteBufCodecs.OPTIONAL_VAR_INT :118` |
| BlockPos | Long (x:26, z:26, y:12 Bit) | – | `core/BlockPos.java` |
| Vec3 | 3 × Double | – | `world/phys/Vec3.java:25` |
| NBT (Netzwerk) | Typ-Byte + Payload **ohne** Root-Namen; Typ 0 = leer | Quota 2 MiB, Tiefe ≤ 512 (`NbtAccounter.defaultQuota`) | `FriendlyByteBuf#readNbt :456`, `nbt/NbtIo#readAnyTag :145` |
| JSON-Text | String(max) mit JSON | lenient Parser | `ByteBufCodecs#lenientJson :735` |

## 3. Framing, Kompression, Verschlüsselung

| Schicht | Format | Grenzen | Quelle |
|---|---|---|---|
| Frame | `VarInt länge` + `länge` Bytes | Längen-VarInt max. **3 Bytes** (≤ 2 097 151), Länge 0 verboten | `network/Varint21FrameDecoder.java` |
| Paket | `VarInt id` + Felder | nach Dekodierung keine Restbytes erlaubt | `network/PacketDecoder.java` |
| Kompression (nach `login_compression`, Threshold ≥ 0) | Frame-Inhalt = `VarInt dataLength` + Daten; `0` = unkomprimiert, sonst zlib mit unkomprimierter Länge `dataLength` | `dataLength` ≥ Threshold und ≤ 8 388 608; komprimiert ≤ 2 097 152 | `network/CompressionDecoder.java:26`, `CompressionEncoder` |
| Verschlüsselung | AES/CFB8, Key = IV, nach `key` | nur Online-Modus – **im Client nicht implementiert** | `util/Crypt.java:187` |
| Bundle | `bundle_delimiter` öffnet/schließt Gruppe | ≤ 4096 Pakete je Bundle | `network/protocol/BundlerInfo.java:11` |

Testserver: `network-compression-threshold=-1` → keine Kompression; der Client implementiert sie trotzdem (Unit-getestet).

## 4. Zustandsautomat

```
HANDSHAKING --intention(intent=1)--> STATUS   --status_request/status_response, ping_request/pong_response--> Server schließt
HANDSHAKING --intention(intent=2)--> LOGIN    --login_finished / login_acknowledged--> CONFIGURATION
CONFIGURATION --finish_configuration (S→C) / finish_configuration (C→S)--> PLAY
PLAY --start_configuration / configuration_acknowledged--> CONFIGURATION     (Reconfiguration)
jeder Zustand --disconnect / TCP-Close--> CLOSED
```

Der Zustandswechsel erfolgt **beim Senden** des bestätigenden Pakets: Nach `login_acknowledged` gilt für ausgehende Pakete sofort CONFIGURATION, nach C→S `finish_configuration` PLAY (`ClientHandshakePacketListenerImpl.java:202-203`, `ClientConfigurationPacketListenerImpl.java:162-184`). Eingehend schaltet der Vanilla-Client beim Empfang von `login_finished` bzw. `finish_configuration` um.

## 5. Pakete

Spalten: **F** = Feld, Typ, Bedingung/Grenzen. „Antwort/Übergang“ beschreibt die vom Client erwartete Reaktion.

### 5.1 Handshake

#### C→S `intention` · HANDSHAKING · 0x00 · `ClientIntentionPacket`
| F | Typ | Grenzen |
|---|---|---|
| protocolVersion | VarInt | 777 (`SharedConstants#getProtocolVersion`) |
| hostName | String(255) | Client sendet `localhost`/`127.0.0.1` |
| port | UShort | 25565 |
| intention | VarInt | 1 = STATUS, 2 = LOGIN, 3 = TRANSFER (`ClientIntent#byId`) |

Übergang: Client wechselt sofort in STATUS bzw. LOGIN. Server: falsche Version → Login-Disconnect (`ServerHandshakePacketListenerImpl#beginLogin`).

### 5.2 Status

| Paket | Richtung | ID | Felder | Antwort / Hinweise | Klasse |
|---|---|---|---|---|---|
| `status_request` | C→S | 0x00 | – | genau einmal, zweite Anfrage → Disconnect (`ServerStatusPacketListenerImpl.java:35`) | `ServerboundStatusRequestPacket` |
| `status_response` | S→C | 0x00 | `json` String(32767): `{version{name,protocol}, players{max,online,sample?}, description, favicon?, enforcesSecureChat?}` | Client prüft JSON-Syntax und liest `version.protocol` | `ClientboundStatusResponsePacket`, `ServerStatus` |
| `ping_request` | C→S | 0x01 | `time` Long | – | `ServerboundPingRequestPacket` |
| `pong_response` | S→C | 0x01 | `time` Long (Echo) | Client prüft Gleichheit; Server schließt danach (`:44-46`) | `ClientboundPongResponsePacket` |

### 5.3 Login

| Paket | Richtung | ID | Felder (Reihenfolge) | Grenzen / Bedingungen | Antwort / Übergang | Klasse |
|---|---|---|---|---|---|---|
| `hello` | C→S | 0x00 | name String(16), profileId UUID | Server prüft `StringUtil#isValidPlayerName` (`util/StringUtil.java:66`): ≤ 16 Zeichen, keine Zeichen ≤ 32 oder ≥ 127; Client erzwingt zusätzlich `[A-Za-z0-9_]{3,16}` | – | `ServerboundHelloPacket` |
| `login_disconnect` | S→C | 0x00 | reason JSON-Text String(262144) | – | Ende | `ClientboundLoginDisconnectPacket` |
| `hello` | S→C | 0x01 | serverId String(20), publicKey ByteArray, challenge ByteArray, shouldAuthenticate Bool | nur `online-mode=true` | **Client bricht kontrolliert ab** („Online-Modus nicht unterstützt“) | `ClientboundHelloPacket` |
| `login_finished` | S→C | 0x02 | GameProfile: id UUID, name String(16), properties List(≤16){name String(64), value String(32767), signature Optional String(1024)}; **danach sessionId UUID** | Vanilla offline: 46 Bytes (1 ID + 16 + 12 + 1 + 16), siehe 10 §4 | C→S `login_acknowledged`, dann CONFIGURATION | `ClientboundLoginFinishedPacket`, `ByteBufCodecs.GAME_PROFILE :252` |
| `login_compression` | S→C | 0x03 | threshold VarInt | < 0 = aus | Kompression **ab dem nächsten Paket** aktivieren | `ClientboundLoginCompressionPacket` |
| `custom_query` | S→C | 0x04 | transactionId VarInt, channel Identifier, payload Rest (≤ 1 048 576) | – | C→S `custom_query_answer` (id, payload absent) | `ClientboundCustomQueryPacket` |
| `cookie_request` | S→C | 0x05 | key Identifier | – | C→S `cookie_response` (key, ohne Payload) | `ClientboundCookieRequestPacket` |
| `custom_query_answer` | C→S | 0x02 | transactionId VarInt, hasPayload Bool(false) | – | – | `ServerboundCustomQueryAnswerPacket` |
| `login_acknowledged` | C→S | 0x03 | – | nur nach `login_finished` (`ServerLoginPacketListenerImpl.java:237`) | **CONFIGURATION** | `ServerboundLoginAcknowledgedPacket` |
| `cookie_response` | C→S | 0x04 | key Identifier, payload Optional ByteArray(5120) | – | – | `ServerboundCookieResponsePacket` |

Offline-UUID: `UUID.nameUUIDFromBytes("OfflinePlayer:"+name)` (Version 3; `core/UUIDUtil.java:86`). Der Server ignoriert die gesendete UUID im Offline-Modus und erzeugt sie selbst (`ServerLoginPacketListenerImpl.java:130`); der Client sendet trotzdem die korrekte.

### 5.4 Configuration

| Paket | Richtung | ID | Felder | Grenzen / Bedingungen | Antwort / Übergang | Klasse |
|---|---|---|---|---|---|---|
| `client_information` | C→S | 0x00 | language String(16), viewDistance Byte, chatVisibility VarInt (0 FULL,1 SYSTEM,2 HIDDEN), chatColors Bool, modelCustomisation UByte (Bitmaske Skin-Teile), mainHand VarInt (0 LEFT,1 RIGHT), textFiltering Bool, allowsListing Bool, particleStatus VarInt (0 ALL,1 DECREASED,2 MINIMAL) | – | – | `ServerboundClientInformationPacket`, `server/level/ClientInformation.java` |
| `custom_payload` | C→S | 0x02 | channel Identifier, data Rest ≤ 32767; `minecraft:brand`: String(32767) | – | – | `ServerboundCustomPayloadPacket`, `BrandPayload` |
| `select_known_packs` | C→S | 0x07 | List(≤64) KnownPack{namespace String(32767), id String(32767), version String(32767)} | **Pflicht** | gleiche Liste → Server lässt Inhalte bekannter Einträge weg; andere/leere Liste → volle Daten (`SynchronizeRegistriesTask#handleResponse :46-51`) | `ServerboundSelectKnownPacks` |
| `finish_configuration` | C→S | 0x03 | – | nur nach S→C `finish_configuration` | **PLAY** | `ServerboundFinishConfigurationPacket` |
| `keep_alive` | C→S | 0x04 | id Long (Echo) | **gleiche ID**, sonst Kick (`ServerCommonPacketListenerImpl.java:83-89`) | – | `ServerboundKeepAlivePacket` |
| `pong` | C→S | 0x05 | id Int (Echo) | – | – | `ServerboundPongPacket` |
| `cookie_response` | C→S | 0x01 | wie Login | – | – | |
| `cookie_request` | S→C | 0x00 | key Identifier | – | `cookie_response` | |
| `custom_payload` | S→C | 0x01 | channel Identifier, data Rest ≤ 1 048 576 (`minecraft:brand` = String) | – | – | `ClientboundCustomPayloadPacket.CONFIG_STREAM_CODEC` |
| `disconnect` | S→C | 0x02 | reason **NBT**-Text (`ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC`) | – | Ende | `ClientboundDisconnectPacket` |
| `finish_configuration` | S→C | 0x03 | – | – | C→S `finish_configuration` | `ClientboundFinishConfigurationPacket` |
| `keep_alive` | S→C | 0x04 | id Long | – | C→S `keep_alive` | `ClientboundKeepAlivePacket` |
| `ping` | S→C | 0x05 | id Int | – | C→S `pong` | `ClientboundPingPacket` |
| `registry_data` | S→C | 0x07 | registry Identifier, List entries{id Identifier, data Optional NBT} | NBT-Quota 2 MiB | – (Client speichert Einträge/IDs in Empfangsreihenfolge) | `ClientboundRegistryDataPacket`, `core/RegistrySynchronization.java:91` |
| `update_enabled_features` | S→C | 0x0D | Collection Identifier | – | – | `ClientboundUpdateEnabledFeaturesPacket` |
| `update_tags` | S→C | 0x0E | Map(registry Identifier → Map(tag Identifier → Collection VarInt)) | – | – | `ClientboundUpdateTagsPacket`, `tags/TagNetworkSerialization.java:61` |
| `select_known_packs` | S→C | 0x0F | List KnownPack (wie oben) | – | C→S `select_known_packs` | `ClientboundSelectKnownPacks` |

Sonstige S→C-Configuration-Pakete (`reset_chat`, `resource_pack_pop/push`, `store_cookie`, `transfer`, `server_links`, `custom_report_details`, `show_dialog`, `clear_dialog`, `code_of_conduct`) kommen auf dem Testserver nicht vor (beobachtet) und werden übersprungen. Achtung: `resource_pack_push` und `code_of_conduct` würden eine Antwort erwarten – bei aktivem Ressourcenpaket oder Verhaltenskodex muss der Client erweitert werden.

### 5.5 Play

| Paket | Richtung | ID | Felder | Grenzen / Bedingungen | Antwort / Übergang | Klasse |
|---|---|---|---|---|---|---|
| `login` | S→C | 0x32 | playerId Int, hardcore Bool, levels Collection Identifier, maxPlayers VarInt, chunkRadius VarInt, simulationDistance VarInt, reducedDebugInfo Bool, showDeathScreen Bool, doLimitedCrafting Bool, **SpawnInfo**{dimensionType VarInt (Registry-ID), dimension Identifier, seed Long, gameType VarInt (0–3), previousGameType OptionalVarInt, isDebug Bool, isFlat Bool, lastDeathLocation Optional{dimension Identifier, pos BlockPos}, portalCooldown VarInt, seaLevel VarInt}, onlineMode Bool, enforcesSecureChat Bool | dimensionType muss Index in der empfangenen `minecraft:dimension_type`-Registry sein | – | `ClientboundLoginPacket`, `CommonPlayerSpawnInfo` |
| `player_position` | S→C | 0x49 | teleportId VarInt, position Vec3, delta Vec3, yRot Float, xRot Float, relatives Int (Bits 0 X,1 Y,2 Z,3 Y_ROT,4 X_ROT,5–7 DELTA_X/Y/Z,8 ROTATE_DELTA) | – | neue absolute Position berechnen (relative Bits addieren), C→S `accept_teleportation` | `ClientboundPlayerPositionPacket`, `world/entity/PositionMoveRotation`, `world/entity/Relative.java:11-19` |
| `accept_teleportation` | C→S | 0x00 | teleportId VarInt, x/y/z Double, yRot/xRot Float | **neu in 26.x:** Server übernimmt die Werte als Bewegung (`handlePlayerPositionChange`, `ServerGamePacketListenerImpl.java:562`); NaN/∞ → Kick | – | `ServerboundAcceptTeleportationPacket` |
| `chunk_batch_start` | S→C | 0x0C | – | – | – | `ClientboundChunkBatchStartPacket` |
| `chunk_batch_finished` | S→C | 0x0B | batchSize VarInt | ≥ 0 | C→S `chunk_batch_received` | `ClientboundChunkBatchFinishedPacket` |
| `chunk_batch_received` | C→S | 0x0B | desiredChunksPerTick Float | Server klemmt auf 0,01–64 (`PlayerChunkSender.java:114`) | – | `ServerboundChunkBatchReceivedPacket` |
| `player_loaded` | C→S | 0x2C | – | einmal nach Join/Respawn | – | `ServerboundPlayerLoadedPacket` |
| `client_tick_end` | C→S | 0x0D | – | 1 × pro Client-Tick (20/s) | – | `ServerboundClientTickEndPacket` |
| `keep_alive` | S→C / C→S | 0x2D / 0x1C | id Long | Antwort mit gleicher ID binnen 15 s | – | |
| `ping` / `pong` | S→C / C→S | 0x3E / 0x2D | id Int | – | Echo | |
| `cookie_request` / `cookie_response` | S→C / C→S | 0x15 / 0x15 | wie Login | – | – | |
| `start_configuration` | S→C | 0x78 | – | – | C→S `configuration_acknowledged` (0x10), dann CONFIGURATION | `ClientboundStartConfigurationPacket` |
| `disconnect` | S→C | 0x20 | reason NBT-Text | – | Ende | `ClientboundDisconnectPacket` |
| `bundle_delimiter` | S→C | 0x00 | – | max. 4096 Pakete pro Bundle | Gruppe öffnen/schließen | `ClientboundBundleDelimiterPacket` |

Alle weiteren Play-Pakete (Chunks, Entities, Licht, Zeit …, 130 clientbound IDs) sind für das Ziel nicht notwendig und werden validiert übersprungen.

## 6. Disconnect

| Variante | Ablauf | Beobachtung |
|---|---|---|
| Client | kein Abmeldepaket; TCP schließen | Server: `lost connection: Disconnected` ([10](10-laufzeittest.md) §6) |
| Server | `login_disconnect` (JSON) / `disconnect` (NBT) mit Grund, dann Close | – |

## 7. Zeitgrenzen (Server)

| Grenze | Wert | Quelle |
|---|---|---|
| Login bis `login_finished` | 600 Ticks | `ServerLoginPacketListenerImpl.java:52` |
| Read-Timeout Netty | 30 s | `ServerConnectionListener.java:69` |
| Keep-Alive-Intervall / Antwortfrist | 15 s / 15 s | `ServerCommonPacketListenerImpl.java:122` |
| `player_loaded`-Fallback | 60 Ticks | `ServerGamePacketListenerImpl.java:236` |
