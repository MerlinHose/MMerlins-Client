# 05 – Netzwerkprotokoll

Protokollversion **777** (`SharedConstants#getProtocolVersion`, `net/minecraft/SharedConstants.java:207`; im Bytecode als `sipush 777` bestätigt, siehe [06](06-rea-analyse.md)).
Vollständige Paket-ID-Tabellen: [07-paket-ids.md](07-paket-ids.md). Server-Gegenseite und konkreter Ablauf mit lokalem Vanilla-Server: [08-verbindung-lokaler-server.md](08-verbindung-lokaler-server.md).

## Transport & Netty-Pipeline

Verbindungsaufbau: `Connection#connect` (`net/minecraft/network/Connection.java:425`), aufgerufen aus `ConnectScreen` (`client/gui/screens/ConnectScreen.java:126`).
`TCP_NODELAY`, `ReadTimeoutHandler(30 s)`; Event-Loop-Threads „Netty … IO #n“.

Pipeline nach `Connection#configureSerialization` (`:458`) und `configurePacketHandler` (`:449`), Namen aus `HandlerNames`:

```
inbound  →  [decrypt] → splitter → [decompress] → FlowControlHandler → decoder/inbound_config → [bundler] → packet_handler (Connection)
outbound ←  [encrypt] ← prepender ← [compress] ← encoder/outbound_config ← [unbundler] ← hackfix
```

`[..]` = nachträglich eingefügt: `decrypt/encrypt` durch `setEncryptionKey` (`:513`), `decompress/compress` durch `setupCompression` (`:540`).
`inbound_config/outbound_config` (`UnconfiguredPipelineHandler`) sind Platzhalter, bis ein Protokoll gesetzt wird; `ProtocolSwapHandler` tauscht Codecs atomar beim Phasenwechsel.

## Wire-Format

### Frame
`Varint21FrameDecoder` / `Varint21LengthFieldPrepender`:
```
VarInt length (1–3 Bytes, max. 2^21-1) | payload[length]
```
Länge 0 → `CorruptedFrameException` (`Varint21FrameDecoder.java`).

### Kompression (nach `ClientboundLoginCompressionPacket`)
`CompressionDecoder#decode` (`network/CompressionDecoder.java:26`):
```
VarInt dataLength | data
dataLength == 0  → data unkomprimiert
dataLength  > 0  → zlib(Inflater)-komprimiert, entpackt = dataLength Bytes
```
Grenzen: komprimiert ≤ 2 097 152 B, unkomprimiert ≤ 8 388 608 B; Schwellwert-Validierung optional (Client: `validateDecompressed = false`, `ClientHandshakePacketListenerImpl.java:231`).

### Paket
```
VarInt packetId | Felder (StreamCodec des Pakets)
```
- IDs entstehen **implizit aus der Registrierungsreihenfolge** in `ProtocolInfoBuilder#addPacket` (`network/protocol/ProtocolInfoBuilder.java:28`) → `IdDispatchCodec` (`network/codec/IdDispatchCodec.java:26/51`).
- Bundle: `withBundlePacket` registriert nur das Delimiter-Paket (Play/clientbound ID 0); Pakete zwischen zwei Delimitern werden zu `ClientboundBundlePacket` zusammengefasst (`PacketBundlePacker`).
- `PacketDecoder#decode` (`network/PacketDecoder.java`) wirft bei überzähligen Bytes einen Fehler – Codecs müssen exakt sein.
- Primitive: `VarInt`, `VarLong`, `Utf8String`, `FriendlyByteBuf`, in Play/Configuration `RegistryFriendlyByteBuf` (Registry-abhängige Codecs, z. B. Items, Komponenten).

### Verschlüsselung
`util/Crypt.java`: RSA-Public-Key vom Server, gemeinsamer 128-Bit-AES-Schlüssel, **`AES/CFB8/NoPadding`** (`Crypt#getCipher :187`), Key = IV.
Server-Hash: `SHA-1(serverId ‖ sharedSecret ‖ publicKey)` als signierte BigInteger-Hex (`Crypt#digestData :78`, `ClientHandshakePacketListenerImpl.java:124`).

## Protokollphasen (`ConnectionProtocol`)

`HANDSHAKING`, `STATUS`, `LOGIN`, `CONFIGURATION`, `PLAY` (`network/ConnectionProtocol.java`).
Definitionen: `network/protocol/{handshake,status,login,configuration,game}/*Protocols.java`.

### Ablauf Client → Multiplayer-Server

```
TCP connect
│ HANDSHAKE   C→S ClientIntention(protocol=777, host, port, intent=LOGIN|TRANSFER|STATUS)
│             Connection#initiateServerboundConnection (Connection.java:255)
▼
│ LOGIN       C→S Hello(name, uuid)                                     ConnectScreen.java:159
│             S→C Hello(serverId, publicKey, challenge, shouldAuthenticate)
│               → handleHello (ClientHandshakePacketListenerImpl.java:114):
│                 SecretKey erzeugen, Session-Server joinServer(uuid, token, hash) (:159),
│                 C→S Key(verschlüsselt) und danach Pipeline-Verschlüsselung aktivieren (:154)
│             S→C LoginCompression(threshold) → setupCompression (:229)
│             S→C CustomQuery → C→S CustomQueryAnswer(null) (:236)
│             S→C CookieRequest → C→S CookieResponse (:246)
│             S→C LoginFinished(profile) → handleLoginFinished (:175)
│             C→S LoginAcknowledged; Protokollwechsel (:202-203)
▼
│ CONFIGURATION  C→S CustomPayload(minecraft:brand), ClientInformation (:204-205)
│             S→C SelectKnownPacks ↔ C→S SelectKnownPacks
│             S→C RegistryData*, UpdateTags, UpdateEnabledFeatures, ResourcePackPush …
│             S→C FinishConfiguration → handleConfigurationFinished
│                (ClientConfigurationPacketListenerImpl.java:150): Registries einfrieren,
│                GameProtocols.CLIENTBOUND an RegistryAccess binden, new ClientPacketListener (:162-163)
│             C→S FinishConfiguration (:184)
▼
│ PLAY        S→C Login (handleLogin, ClientPacketListener.java:496) → ClientLevel, LocalPlayer
│             S→C PlayerPosition → C→S AcceptTeleportation (handleMovePlayer :790)
│             S→C ChunkBatchStart, LevelChunkWithLight*, ChunkBatchFinished
│                 → C→S ChunkBatchReceived(desiredChunksPerTick) (:2467-2475)
│             laufend: C→S MovePlayer*, PlayerInput, ClientTickEnd …; KeepAlive/Ping
│             S→C StartConfiguration ↔ C→S ConfigurationAcknowledged (Rückkehr zu CONFIGURATION möglich)
```

Status/Serverliste: Handshake mit Intent `STATUS` → `StatusRequest`/`StatusResponse`, `PingRequest`/`PongResponse` (`Connection#initiateServerboundStatusConnection :241`).

## Paketverarbeitung & Threading

- Netty-Thread: `Connection#channelRead0` (`:143`) → `packet.handle(listener)`.
- Fast jeder Handler beginnt mit `PacketUtils.ensureRunningOnSameThread(packet, this, minecraft.packetProcessor())`
  (121 Vorkommen in `ClientPacketListener`). Läuft er nicht auf dem Render-Thread, wird das Paket in `PacketProcessor` eingereiht und `RunningOnDifferentThreadException` geworfen (von `Connection` still gefangen).
- `PacketProcessor#processQueuedPackets` (`network/PacketProcessor.java:26`) wird in `Minecraft#runTick` vor den Ticks aufgerufen.
- Ausnahmen (direkt im Netty-Thread): z. B. `handleKeepAlive` (`ClientCommonPacketListenerImpl.java:150`, antwortet per `sendWhen` (`:394`) sofort, außer der Render-Thread hängt > 200 ms in `pollEvents` (`RenderSystem#isFrozenAtPollEvents`) – dann wird die Antwort bis zu 1 min zurückgestellt), `handleChunkBatchStart/Finished`.
- Senden: `Connection#send` → bei Nicht-Event-Loop-Thread Übergabe an die Netty-Event-Loop; `flush`-Steuerung über Parameter.

## Wichtige Client-Listener

| Klasse | Phase |
|---|---|
| `client/multiplayer/ClientHandshakePacketListenerImpl` | Login |
| `client/multiplayer/ClientConfigurationPacketListenerImpl` | Configuration |
| `client/multiplayer/ClientPacketListener` (2 886 Zeilen) | Play |
| `client/multiplayer/ClientCommonPacketListenerImpl` | gemeinsame Pakete (KeepAlive, Ping, CustomPayload, ResourcePacks, Cookies, Transfer, Dialoge) |
| `client/multiplayer/ClientStatusPacketListener`-Implementierungen (`ServerStatusPinger`) | Status |

## Folgerungen für den Nachbau

- Pipeline: Framing → (Kompression) → (AES/CFB8) – Reihenfolge bei Verschlüsselung *außen*.
- ID-Tabellen nicht hart kodieren, sondern aus [07](07-paket-ids.md) bzw. dem Extraktor generieren (ändern sich je Version).
- Configuration-Phase und Registry-Synchronisierung sind Pflicht vor PLAY; viele Play-Codecs benötigen die synchronisierten Registries.
- Chunk-Batching-Rückmeldung, Teleport-Bestätigung und KeepAlive sind für eine stabile Verbindung notwendig.
- Für Online-Server ist Microsoft/Mojang-Authentifizierung (Session-`joinServer`) erforderlich; für Tests eignet sich ein lokaler Server im Offline-Modus.
