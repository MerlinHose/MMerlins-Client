# 14 – Laufzeittest des eigenständigen Protokollclients

Datum: 2026-11-06 · Client: `client/` v0.1.0 (Java 25, nur JDK) · Server: Vanilla 26.3, `127.0.0.1:25565`, `online-mode=false`, `network-compression-threshold=-1` (Einrichtung siehe [10](10-laufzeittest.md))
Rohdaten: `analysis/runtime-logs/client-run1/` und `client-run2-decline/` (per `.gitignore` ausgeschlossen)

Legende: 🟢 im Betrieb gemessen · 🧪 durch Unit-Test geprüft · 🔵 nur aus Quellcode abgeleitet · ⏸ nicht getestet

## 1. Erfolgskriterien

| Kriterium | Ergebnis | Nachweis |
|---|---|---|
| Erfolgreicher Build | ✅ `-Xlint:all -Werror`, 0 Warnungen | 🟢 `./gradlew build` |
| Unit-Tests | ✅ 88/88 | 🟢 JUnit-Berichte |
| Erfolgreiche Statusabfrage | ✅ `version=26.3 protocol=777 players=0/20 motd="MinecraftResearch local test server" ping=1 ms` | 🟢 |
| Erfolgreicher Login | ✅ `Logged in as ProtoBot 4b29dcd8-c747-3975-9ced-9cc32fe38557` (Offline-UUID) | 🟢 Server: `ProtoBot[/127.0.0.1:54123] logged in with entity id 19` |
| Configuration vollständig | ✅ 32 `registry_data`, `update_tags`, Known-Packs-Austausch, `finish_configuration` in beide Richtungen | 🟢 |
| Play-State erreicht | ✅ `login` (play) dekodiert und validiert, Entity 19, `minecraft:overworld` | 🟢 |
| ≥ 60 s stabil | ✅ **70,0 s** im Play-Zustand (Lauf 1), 61 s (Integrationstest), 20 s (Lauf 2) | 🟢 |
| Keep-Alive ohne Timeout | ✅ 4 von 4 beantwortet, Abstand max. 15 049 ms (Server-Intervall 15 s) | 🟢 |
| Kontrollierte Trennung ohne Serverfehler | ✅ Server: `ProtoBot lost connection: Disconnected` (identisch zum Vanilla-Client, docs/10 §6); keine Server-WARN/ERROR außer der gewollten Discovery-Umlenkung | 🟢 |
| Nur localhost, keine externen Kontakte | ✅ Netzwerkmonitor: nur `127.0.0.1:5412x ↔ 127.0.0.1:25565`, 0 externe Verbindungen, keine UDP-Endpunkte | 🟢 `netmon.ps1` |
| Integrationstest automatisiert | ✅ `./gradlew test -PintegrationTest` → 90/90 (2 Integrationstests, 62 s) | 🟢 |

## 2. Läufe

| Lauf | Konfiguration | Ergebnis |
|---|---|---|
| Status | `--status-only` | ✅ 5 Pakete (intention, status_request, status_response 128 B, ping_request, pong_response), Pong-Echo geprüft, Server schließt danach |
| 1 | `--duration 70`, Known Packs **accept** | ✅ SUCCESS, 70,0 s Play, empfangen 5 876 / gesendet 1 421 Pakete |
| 2 | `--duration 20 --known-packs decline` | ✅ SUCCESS, **432/432 Registry-Einträge mit NBT-Daten** (157 839 B `registry_data` statt ~10 kB) fehlerfrei geparst und validiert |
| IT | JUnit `LocalServerIntegrationTest` (61 s) | ✅ beide Tests grün |

### Lauf 1 im Detail 🟢

| Messgröße | Wert | Plausibilität |
|---|---|---|
| `client_tick_end` gesendet | 1 401 | 70 s × 20/s = 1 400 ✅ |
| `keep_alive` beantwortet | 4 | alle 15 s ✅ |
| Teleports bestätigt | 1 (Spawn) | ✅ |
| Chunk-Batches bestätigt / angekündigte Chunks | 7 / 49 | 49 = 7 × 7 bei `view-distance 2` ✅ |
| `player_loaded` | 1 × direkt nach dem ersten `accept_teleportation` | ✅ |
| Registries / Einträge (mit Daten) | 32 / 432 (0) | Known Packs bestätigt → Inhalte entfallen ✅ (docs/12 §5.4) |
| Server-Brand | `vanilla` | ✅ |

## 3. Paketfolge im Vergleich zum Vanilla-Referenzlauf

Grundlage: serverseitige Paketlogs (`log4j2-packets.xml`) von Vanilla-Lauf 3 (docs/10) und Client-Lauf 1, ausgewertet mit `analysis/tools/compare-sequences.js` und `analyze-runtime.js`.

**Client-Sicht bis Play (identisch zu docs/10 §4):**
```
C→S handshake     0x00 intention                16 B   (Vanilla 16)
C→S login         0x00 hello                    26 B   (Vanilla 29: Name 3 Zeichen länger)
S→C login         0x02 login_finished           43 B   (Vanilla 46, dito)
C→S login         0x03 login_acknowledged        1 B
C→S configuration 0x02 custom_payload (brand)   44 B   (Vanilla 24: Brand "fabric" statt "mcresearch-protocol-client")
C→S configuration 0x00 client_information       15 B   (Vanilla 15)
S→C configuration 0x01 custom_payload (brand)   25 B
S→C configuration 0x0D update_enabled_features  20 B
S→C configuration 0x0F select_known_packs       22 B
C→S configuration 0x07 select_known_packs       22 B   (Vanilla 22)
S→C configuration 0x07 registry_data ×32
S→C configuration 0x0E update_tags          58 755 B
S→C configuration 0x03 finish_configuration
C→S configuration 0x03 finish_configuration
S→C play          0x32 login                   110 B   (Vanilla 110)
S→C play          … change_difficulty, player_abilities, set_held_slot, update_recipes, entity_event, commands,
                    recipe_book_settings, recipe_book_add
S→C play          0x49 player_position          62 B
C→S play          0x00 accept_teleportation     34 B   (Vanilla 34)
C→S play          0x2C player_loaded             1 B
```

| Vergleich | Ergebnis |
|---|---|
| Gemeinsame Join-Folge (beide Richtungen, Server-Sicht) | **die ersten 65 Pakete identisch**; danach nur Verschiebung durch Timing: der eigene Client bestätigt den Teleport ~1 ms nach `player_position`, der Vanilla-Client später, daher hat der Server bei Vanilla zuvor schon Entity-Bundles eingeschoben |
| Reihenfolge C→S bis `player_loaded` | identisch bis auf **ein** zusätzliches Play-`custom_payload` beim Referenzclient; dieses stammt aus der Fabric API (`AbstractChanneledNetworkAddon#sendInitialChannelRegistrationPacket`, Kanal `minecraft:register`), Vanilla-`ClientPacketListener` sendet keins 🔵 → der eigene Client verhält sich wie ein reiner Vanilla-Client |
| `client_tick_end` pro Minute | Vanilla 1 202,1 / eigen 1 200,6 ✅ |
| `keep_alive` pro Minute | Vanilla 3,5 / eigen 3,4 ✅ |
| Nur beim Vanilla-Lauf (Testtreiber-Aktionen) | `move_player_*`, `player_input`, `use_item_on`, `player_action`, `punch`, `container_*`, `set_carried_item` – im eigenen Client bewusst nicht implementiert |
| `chunk_batch_received` | Vanilla 51 / eigen 7 – Folge der Sichtweite (10 vs. 2) |
| Client-OUT = Server-IN / Server-OUT = Client-IN | 0 abweichende Pakettypen (beide Läufe) ✅ |

## 4. Während der Entwicklung gefundene Fehler

| Fehler | Erkannt durch | Ursache | Behebung |
|---|---|---|---|
| `login_finished`: `sessionId` (UUID) fehlte in Decoder und Spezifikation | 🧪 Größenvergleich mit Vanilla-Laufzeitdaten: 46 B gemessen, eigene Feldsumme 30 B | Feld beim Lesen des Codecs übersehen (`ClientboundLoginFinishedPacket(GameProfile, UUID sessionId)`) | Decoder, Test (exakt 46 B) und docs/12 korrigiert – **vor** dem ersten Serverkontakt |
| `RawPacket` lag in der Netzwerkschicht, Decoder (Protokollschicht) hing davon ab | Code-Review | Schichtungsfehler | in `protocol` verschoben |
| Zählung der Pakettypen in docs/12 §1 (25 statt 28 S→C) | Abgleich mit Implementierung | Schätzfehler in der eigenen Tabelle | korrigiert |

Gegen den echten Server trat **kein** Fehler auf: Lauf 1 war beim ersten Versuch erfolgreich.

## 5. Was getestet ist und was fehlt

| Funktion | Status |
|---|---|
| TCP-Verbindung, nur Loopback, Connect-/Read-Timeout | 🟢 + 🧪 |
| VarInt/VarLong, Strings, UUID, Identifier, Booleans | 🧪 (Grenzfälle, Überlängen, ungültiges UTF-8) + 🟢 |
| Framing mit Längenprüfung (0, > 21 Bit, abgeschnitten) | 🧪 + 🟢 |
| Statusabfrage inkl. Pong-Echo | 🟢 + 🧪 |
| Login offline, Offline-UUID = Server-UUID | 🟢 + 🧪 |
| Configuration inkl. Registry-Daten (beide Known-Packs-Modi, NBT) | 🟢 |
| Übergang Play, `login`-Dekodierung und -Validierung | 🟢 + 🧪 |
| Keep-Alive (Configuration + Play) | 🟢 (Play) · 🧪 (Configuration: Configuration dauerte < 15 s) |
| Teleport-Bestätigung inkl. Relative-Bits | 🟢 (absolut) · 🧪 (relativ) |
| Chunk-Batch-Bestätigung | 🟢 |
| `player_loaded`, `client_tick_end` | 🟢 |
| Kontrollierter Disconnect, Ressourcenfreigabe | 🟢 |
| Bundles (Zählung, Limit 4096) | 🟢 (15 Bundles = 30 `bundle_delimiter` in Lauf 1 korrekt geöffnet/geschlossen) · 🧪 (Limit) |
| Kompression (zlib, Grenzwerte) | 🧪 nur Unit-Test · ⏸ gegen Server (Threshold −1) |
| `ping`/`pong`, `cookie_request`, `custom_query` | 🧪 · ⏸ Server sendet sie nicht |
| Reconfiguration (`start_configuration`) | 🧪 · ⏸ |
| Server-Disconnect während Play (`disconnect` NBT) | 🧪 (Decoder + Session) · ⏸ gegen Server (nur für den Vanilla-Client in docs/10 getestet) |
| Online-Modus (Verschlüsselung) | 🧪 wird erkannt und abgebrochen · ⏸ nicht implementiert |
| Bewegung, Chat, Blockinteraktion, Inventar | ⏸ nicht implementiert |
| Chunk-Inhalte, Entities, Welt | ⏸ nur übersprungen (ID + Länge validiert, Felder nicht) |
| Langzeitbetrieb > 70 s, mehrere Clients | ⏸ |

**Einschränkung der Validierung:** Vollständig feldweise validiert werden die 28 dekodierten Serverpakettypen. Für die übrigen ~200 IDs prüft der Client nur, ob die ID im aktuellen Zustand existiert und ob die Framelänge zulässig ist.

## 6. Reproduktion

```bash
# Server (in server/)
java -Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery -jar server.jar nogui
# Client (in client/)
./gradlew build
./gradlew run --args="--status-only"
./gradlew run --args="--duration 70"
./gradlew run --args="--duration 20 --known-packs decline --username ProtoBotFull"
./gradlew test -PintegrationTest
# Vergleich mit dem Vanilla-Referenzlauf
node ../analysis/tools/compare-sequences.js ../analysis/runtime-logs/run3/server-packets.log <server-packets.log des eigenen Laufs>
```
Für serverseitige Paketlogs den Server mit `-Dlog4j.configurationFile=log4j2-packets.xml` starten (docs/10 §2.1).
