# 17 – Testbericht M2 (Spielerbewegung) und M3 (Kompression)

Zeitraum: 2026-11-06 (M2) und 2026-11-07 (M3, Regression) · Client `client/` · Server Vanilla 26.3 `127.0.0.1:25565`, `online-mode=false`
Rohdaten: `analysis/runtime-logs/m2-nocompression*/`, `m2-m3-{-1,256,0,final-regression}/`, `m3-threshold-{-1,256,0}/`, `m3-final-regression/` (per `.gitignore` ausgeschlossen)
Werkzeuge: `analysis/tools/m2-test.sh` (Terminaltest + Serverbeobachtung), `m3-run.sh` (kompletter Lauf je Threshold), `netmon.ps1`, `analyze-runtime.js`

Legende: 🟢 im Betrieb gemessen · 🧪 Unit-Test · 🔵 Quellcode

## 1. Gesamtergebnis

| Abschlusskriterium | Ergebnis |
|---|---|
| **M2:** Client verändert Position und Blickrichtung, Server akzeptiert sie nachweislich | ✅ serverseitig per `/data get entity <Spieler> Pos/Rotation` bestätigt, in allen 5 Terminalläufen und im Integrationstest (jede Threshold-Einstellung) |
| **M3:** vollständiger Login + ≥ 70 s stabiler Play-State mit Kompression, Bewegung besteht weiterhin | ✅ Threshold 256 und 0: 71,5 s Play, Bewegungstests bestanden |
| Keine Regression | ✅ abschließender Lauf: 134/134 Tests, alle M1-Prüfungen weiter grün |
| Keine externen Verbindungen | ✅ `netmon`: 0 externe Verbindungen in allen Läufen |
| Original-JARs unverändert | ✅ SHA-256 `minecraft-server.jar`/`server.jar` `d052f14d…`, merged `033be6d7…` |
| Testserver gestoppt | ✅ kein Java-Prozess, Port 25565 frei; `server.properties` wieder auf `network-compression-threshold=-1` |

## 2. Testumfang

| Ebene | Anzahl | Inhalt |
|---|---:|---|
| Unit-Tests | 131 | M1-Bestand (88) + M2 (24: Pakete, Heightmaps, Bewegungsmodell, Tick-Reihenfolge) + M3 (19: Grenzen, zlib-Fehler, Zähler, Socket-Ende-zu-Ende) |
| Integrationstests | 3 | `statusQuery`, `stableSixtySecondsInPlayAndControlledDisconnect` (jetzt **71 s**, Keep-Alive-Lücke ≤ 16 s, Kompressionszähler), `movementAndRotationAcceptedByServer` (Server-Position/-Rotation per Konsole) |
| Terminaltest (`m2-test.sh`) | 1 Skript | Status, 3 Bewegungen, Sprung, 2 Rotationen, 3 Testpakete, 20 s Leerlauf, `quit`; nach jedem Schritt Serverbeobachtung |

Hinweis: Der Methodenname `stableSixtySeconds…` blieb aus Kompatibilität erhalten; der Test läuft und prüft jetzt 71 s.

## 3. M2 – Spielerbewegung (Threshold −1, 2026-11-06)

### 3.1 Lauf 1: Fehler gefunden 🟢

| Schritt | Client | Server (`/data get`) |
|---|---|---|
| `look 90 0` | – | Rotation `[90.0f, 0.0f]` ✅ |
| `move 3 0 0` | reached after 14 ticks | `[-6.5, 67.0, -0.5]` (Start −9,5) ✅ |
| `move 0 0 3` | Stufe abwärts (Schwerkraft) | `[-6.5, 66.0, 2.5]` ✅ |
| `move -3 0 -3` | **„corrected by server after 2 ticks“** | zurückgesetzt auf `[-6.653, 66.0, 2.347]` ❌ |

Ursache: Das Modell prüfte nur die Blockspalte unter der Spielermitte. Die 0,6 breite Hitbox ragte diagonal in eine höhere Spalte. Der Server setzte über `isEntityCollidingWithAnythingNew` zurück, ohne Logeintrag (🔵 `ServerGamePacketListenerImpl.java:1190`).
Behebung: Bodenhöhe = Maximum aller Spalten unter der Hitbox (`MovementController#groundAt`). Regressionstest `hitboxEdgeAgainstHigherColumnBlocksBeforeCenterEnters`.

### 3.2 Lauf 2 und Integrationstest: bestanden 🟢

| Schritt | Client (behauptet) | Server (akzeptiert) |
|---|---|---|
| `move 3 0 0` | (−3,553; 66; 2,347) | `[-3.5526, 66.0, 2.3474]` ✅ |
| `move 0 0 3` | (−3,553; 66; 5,347) | `[-3.5526, 66.0, 5.3474]` ✅ |
| `move -3 0 -3` | blocked by terrain bei (−5,689; 66; 3,211) | `[-5.6889, 66.0, 3.2111]` ✅ (keine Korrektur) |
| `look -45 20` | – | `[-45.0f, 20.0f]` ✅ |
| `move 0 1 0` | Sprung, landet auf 66 | unverändert, keine Korrektur ✅ |
| Integrationstest | −2 x, +1,5 z mit Abstieg 83 → 82 | `[-3.5, 82.0, -7.0]`, `[90.0f, 15.0f]` ✅ |

Weitere Messwerte Lauf 2:
- Paketgrößen auf dem Draht = Vanilla: `move_player_pos` 26 B (113 Pakete), `move_player_rot` 10 B, `move_player_status_only` 2 B, `player_input` 2 B.
- Client OUT = Server IN in allen Typen (0 Abweichungen).
- Höchstens ein Positionspaket je `client_tick_end` (🧪 `movementPacketsPrecedeTickEndAndAtMostOnePositionPerTick`); der Server trennte nie mit `invalid_player_movement`.

### 3.3 Serverkorrekturen nach den Serverregeln (Testbefehl `claim`) 🟢

| Testpaket | Erwartung (🔵 Quelle) | Server-Log | Server-Position | Ergebnis |
|---|---|---|---|---|
| `claim 0 -2 0` (2 Blöcke in den Boden) | Rücksetzung ohne Log (`:1190`) | – | unverändert | ✅ korrigiert |
| `claim 15 0 0` (15 Blöcke in einem Paket, 225 > 100) | „moved too quickly“ + Rücksetzung (`:1146`) | `ProtoBot moved too quickly! 15.0,0.0,0.0` | unverändert | ✅ korrigiert |
| `claim 0.1 0 0` | akzeptiert | – | x + 0,1 (z. B. `-6.5526`) | ✅ übernommen |

Behauptet und akzeptiert sind sichtbar getrennt. Nach `claim 0.1` zeigt `position` „claimed −5,589 | last server position −5,689“. Die letzte *gesetzte* Serverposition bleibt die alte, weil der Server keine Korrektur schickt. Die *akzeptierte* Position (`/data get`) entspricht der Behauptung.

## 4. M3 – Kompression je Schwellenwert (2026-11-07)

Je Konfiguration: Server neu gestartet, `./gradlew cleanTest test -PintegrationTest -PserverDir=../server -PexpectedThreshold=N` (alle Unit- und Integrationstests), dann `m2-test.sh`.

### 4.1 Integrationstests 🟢

| Prüfung | −1 | 256 | 0 |
|---|---|---|---|
| Status-Ping (Protokoll 777, Pong-Echo) | ✅ | ✅ | ✅ |
| Login inkl. `login_compression` (bei ≥ 0) | ✅ | ✅ | ✅ |
| Configuration (32 Registries, 432 Einträge mit NBT) | ✅ | ✅ | ✅ |
| Play-State | ✅ 71,55 s | ✅ 71,54 s | ✅ 71,54 s |
| Keep-Alive (≥ 4, max. Lücke ≤ 16 s) | ✅ | ✅ | ✅ |
| Bewegung + Rotation serverseitig bestätigt | ✅ | ✅ | ✅ |
| Kontrollierter Disconnect (Server: `lost connection: Disconnected`) | ✅ | ✅ | ✅ |
| Kompressionszähler exakt wie erwartet | ✅ 0 komprimiert | ✅ komprimiert > 0, Draht < Paket | ✅ nur 1 Frame ein / 2 Frames aus unkomprimiert |
| JUnit gesamt | 134/134 | 134/134 | 134/134 |

### 4.2 Terminal-Bewegungstest bei Kompression 🟢

In allen drei Konfigurationen identisch (Spieler `ProtoBotM3`):

| Schritt | Server (`/data get`) |
|---|---|
| Start | `[-7.5, 67.0, -2.5]` (−1) / `[-7.4, …]` (256) / `[-7.3, …]` (0) – jeweils Endpunkt des Vorlaufs |
| `move 3 0 0` | x + 3 ✅ |
| `move 0 0 3` | z + 3 ✅ |
| `move -3 0 -3` | zurück zum Start, 20 Ticks, keine Korrektur ✅ |
| `look 90 0` / `look -45 20` | `[90.0f, 0.0f]` / `[-45.0f, 20.0f]` ✅ |
| `claim 0 -2 0`, `claim 15 0 0` | zurückgesetzt; „moved too quickly! 15.0,0.0,0.0“ ✅ |
| `claim 0.1 0 0` | x + 0,1 übernommen ✅ |
| 20 s Leerlauf | Keep-Alive beantwortet (max. Lücke 15 047–15 049 ms) ✅ |
| `quit` | `RESULT SUCCESS: Controlled disconnect (quit command)` ✅ |

### 4.3 Kompressionsmessung 🟢

| Threshold | Frames ein (komp./unkomp.) | Drahtbytes → Paketbytes ein | Frames aus (komp./unkomp.) | Paketbytes → Drahtbytes aus |
|---|---|---|---|---|
| −1 | 3 137 (0 / 3 137) | 2 769 033 → 2 769 033 | 1 379 (0 / 1 379) | 4 649 → 4 649 |
| 256 | 4 871 (94 / 4 777) | 461 719 → 2 767 696 (16,7 %) | 1 381 (0 / 1 381) | 4 651 → 6 030 |
| 0 | 5 040 (5 039 / 1) | 498 415 → 2 768 853 (18,0 %) | 1 381 (1 379 / 2) | 4 651 → 16 277 |

Konsistenzprüfungen:
- 256 aus: 4 651 + 1 379 Header-Bytes = 6 030 ✅ (die 2 Frames vor der Aktivierung haben keinen Header).
- 0: einziger unkomprimierter Eingangsframe = `login_compression` ✅ (🔵 Server aktiviert die Kompression erst nach dem Senden).
- Server: kein „Badly compressed packet“; der Server prüft unsere Pakete mit `validateDecompressed = true` (🔵 `ServerLoginPacketListenerImpl.java:150`).

### 4.4 Abschließende Regression (Threshold −1) 🟢

134/134 Tests, Integrationstests 71,54 s / 0,012 s / 3,18 s, Terminaltest bestanden, 0 externe Verbindungen.

## 5. Gefundene Fehler

| # | Fehler | Gefunden durch | Ursache | Behebung |
|---|---|---|---|---|
| 1 | Diagonalbewegung vom Server zurückgesetzt | 🟢 M2-Lauf 1 | Bodenhöhe nur aus der Mittelspalte | Hitbox-Maximum + Regressionstest |
| 2 | Bewegung endete einen Tick zu spät (leerer Extratick) | 🧪 | Zielprüfung vor statt nach dem Schritt | Prüfung nach dem Schritt |
| 3 | Spieler auf Halbblock wäre auf Heightmap-Kante angehoben worden | 🧪 (Testprämisse korrigiert) | Heightmaps sind ganzzahlig | gleiche Oberfläche → Höhe halten |
| 4 | Testannahme: jedes 8-MiB-Paket übertragbar | 🧪 | 8 MiB Zufallsdaten → 4,2 MB komprimiert > Frame-Limit | Test korrigiert, Erkenntnis in docs/16 §4 |
| 5 | Paketlog landete in `C:\c\Users\…` | 🟢 Skript | Git-Bash-Pfad an Java übergeben | `cygpath -w` |
| 6 | Testskript hing am Ende | 🟢 | `tail -f` hielt Pipe offen | Umleitung + SIGPIPE-Zeile |
| 7 | Zwei eigene Testerwartungen falsch (`heightmapBits` 4064 → 12, Beispielpaket) | 🧪 | Rechen-/Annahmefehler im Test | korrigiert, Code unverändert |

Keine Fehler in der Kompressionsimplementierung gegen den echten Server.

## 6. Bekannte Grenzen

- Bewegungsmodell ohne Blockformen, Fluide, Leitern, Rückstoß, Sprinten, Schleichen, Respawn (docs/15 §8).
- Mit `--known-packs accept` keine Bodenhöhen.
- Online-Modus (Verschlüsselung) weiterhin nicht implementiert.
- `netmon` tastet sekündlich ab; die kurzlebige Statusverbindung erscheint nicht in jedem Lauf (immer Loopback).

## 7. Empfehlung für M4 – Chunk-Dekodierung

1. **Vorarbeit – Spezifikation (`docs/18`):**
   - Format von `ClientboundLevelChunkPacketData`: Puffer mit 24 Sektionen à `blockCount` Short, Paletten-Container für Blockzustände und Biome mit Bits/Palette/Long-Array.
   - Block-Entities, `ClientboundLightUpdatePacketData`.
   - Quellen: `LevelChunkSection#write`, `PalettedContainer`, `Strategy`/`Configuration`.
   - Alles im Laufzeittest belegen; Chunkgrößen sind in docs/10 bekannt (Ø 38,5 kB).
2. **Registry-Abhängigkeit klären:** Blockzustands-IDs sind globale Indizes. Sie werden nicht per Registry-Sync übertragen, sondern aus dem Blockregister abgeleitet. Referenz: `blocks.json` des Datengenerators (nur Faktendaten). Mit Decline-Modus und `blocks.json` als Testreferenz abgleichen.
3. **Inkrementell umsetzen:**
   - a) Sektionen parsen und vollständig validieren (Restbytes = 0).
   - b) Blockzustand an Position abfragbar.
   - c) `block_update`/`section_blocks_update` anwenden.
   - d) Bodenhöhe der Bewegung aus echten Kollisionsformen statt aus der Heightmap (behebt docs/15 §8).
4. **Verifikation:** Serverseitig per `/data get`- bzw. `/execute if block`-Abfragen gegen einzelne dekodierte Blöcke, plus Vollständigkeitsprüfung jedes `level_chunk_with_light` (keine Restbytes) bei Threshold 256.
