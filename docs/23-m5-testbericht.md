# 23 – Testbericht M5: Spielerphysik und Blockkollision

**Stand:** 2026-11-08 (endgültig). Der erste Lauf vom 2026-11-07 ist in §7 dokumentiert.

**Testumgebung:**
- Vanilla-Server 26.3 auf 127.0.0.1:25565, `online-mode=false`. Für Testläufe gilt `difficulty=peaceful` (§6); der Originalwert `easy` wird danach wiederhergestellt.
- Vanilla-Referenzclient: unveränderter Minecraft-26.3-Client im Fabric-Forschungsprojekt, Task `runPhysicsRef`, Spieler `ResearchRef`.
- Eigener Client: Spieler `ProtoBotM5`.

**Rohdaten** (gitignored): `analysis/runtime-logs/m5-threshold-{256,-1,0}/`

| Datei | Inhalt |
|---|---|
| `reference/physref-trace.txt` | Vanilla-Spur |
| `m5-own-trace.txt` | eigene Spur |
| `m5-physics-report.txt` | Vergleichsbericht |
| `memory.txt` | Speicherverlauf |
| `netmon.txt` | Netzwerkmonitor |

**Spezifikation und Architektur:** docs/21 und docs/22.

## 1. Verfahren

**Teststrecke:** `analysis/tools/m5-course.txt`. Die Datei enthält Aufbaubefehle und 20 Szenarien:
- ebener Boden: gerade, seitlich, diagonal, Yaw −63,5°;
- voller Block, Halbblock-Stufe, Treppe auf y = 103;
- schmaler Durchgang, Gleiten an der Wand;
- Sprung im Stand, gehaltene Sprungtaste, Sprung auf einen Block;
- niedrige Decke, Grube;
- Zaun, Mauer, Falltür, Tür, Teppich/Schnee/Gitter, Truhe.

Die Strecke kreuzt die Chunkgrenzen x = −16, x = 0 und z = −16.

**Ablauf je Szenario (beide Clients identisch):**
1. Teleport zu einem Zwischenpunkt 1 Block hinter dem Start, auf festem Boden.
   - Vanilla: Der Treiber meldet `STAGED`.
   - Eigener Client: Zähler der `player_position`-Pakete.
2. Erst danach Teleport zum Start.
3. Mindestens 20 Ticks Ruhe.
4. Die Tastenfolge Tick für Tick ausführen.
   - **Vanilla:** `KeyMapping.set` am Tickende, wirksam im nächsten Tick über `KeyboardInput#tick`. Danach wird der Zustand des `LocalPlayer` protokolliert.
   - **Eigener Client:** `MovementController#script` auf der echten Sitzung.
5. `data get entity … Pos` an der Serverkonsole.

**Verglichen wird:**
- je Tick Position, Geschwindigkeit, `onGround`, `horizontalCollision` und `verticalCollision` gegen die Vanilla-Spur;
- die Endposition gegen den Server;
- jede Positionskorrektur (`player_position`) während eines Szenarios.

## 2. Ergebnisse

### 2.1 Physik gegen Vanilla und Server (alle drei Kompressionsstufen)

| Szenario | Ticks | 256 | −1 | 0 | Endposition (Server, Threshold 256) |
|---|---|---|---|---|---|
| flat_walk | 45 | bitgleich | bitgleich | bitgleich | −20,5 / 100 / −13,02798334640485 |
| strafe_left | 35 | bitgleich | bitgleich | bitgleich | −16,186574 / 100 / −19,5 |
| diagonal | 45 | bitgleich | bitgleich | bitgleich | −15,8301976422899 / 100 / −0,8301976422899 |
| obstacle | 40 | bitgleich | bitgleich | bitgleich | z = −15,3 (Hitbox bündig am Block) |
| slab_step | 60 | bitgleich | bitgleich | bitgleich | y 100 → 101, 2 Step-ups |
| stairs | 55 | bitgleich | bitgleich | bitgleich | y 100 → 103, 6 Step-ups |
| narrow_pass | 60 | bitgleich | bitgleich | bitgleich | durch den 1-Block-Gang |
| wall_slide | 45 | bitgleich | bitgleich | bitgleich | x = −0,699999988 an der Wand, 28 Ticks Kollision |
| jump_in_place | 21 | bitgleich | bitgleich | bitgleich | Gipfel 101,2522 |
| **jump_hold** | 45 | **bitgleich** | **bitgleich** | **bitgleich** | drei Sprünge, Periode 12 Ticks |
| jump_onto | 43 | bitgleich | bitgleich | bitgleich | auf den Block, z = −14,3 |
| low_ceiling | 47 | bitgleich | bitgleich | bitgleich | Gipfel 100,2 = Decke − 1,8 |
| drop_pit | 50 | bitgleich | bitgleich | bitgleich | Grube |
| fence | 40 | bitgleich | bitgleich | bitgleich | z = −14,925 (Pfosten 6/16 − 0,3) |
| wall | 40 | bitgleich | bitgleich | bitgleich | z = −15,05 (Pfosten 4/16 − 0,3) |
| trapdoor | 45 | bitgleich | bitgleich | bitgleich | Step-up 0,1875 |
| door | 40 | bitgleich | bitgleich | bitgleich | z = −14,4875 |
| carpet_snow | 60 | bitgleich | bitgleich | bitgleich | Step-ups 1/16 und 0,375; Gitter stoppt |
| chest | 40 | bitgleich | bitgleich | bitgleich | 0,875 nicht übersteigbar |
| turned_yaw | 40 | bitgleich | bitgleich | bitgleich | Tabellen-Sinus bei −63,5° |
| **gesamt** | **896** | **896/896** | **896/896** | **896/896** | |

Zusammenfassung je Lauf, aus `m5-physics-report.txt`:

```
summary: 20 scenarios, 896 ticks, server mismatches/corrections 0, vanilla ticks compared 896, worst |Δpos| 0.000e+00, flag mismatches 0, corrections total 40
```

**Bewertung:**
- **Bitgleich:** In jedem der 896 Ticks liefert die eigene Physik dieselben `double`-Werte wie der Vanilla-Client, bei allen drei Schwellenwerten.
- **Server:** Er bestätigt jede der 20 Endpositionen mit Abweichung 0,0.
- **Korrekturen:** Die 40 gezählten Korrekturen sind die beiden Testteleports je Szenario. Während eines Szenarios gab es keine einzige.
- **Ergebnis:** Damit ist auch `jump_hold` bestätigt. Die frühere Abweichung war ein Messfehler (§7).

### 2.2 Physik-Kennwerte (Vanilla-bestätigt)

| Größe | Wert |
|---|---|
| Gehen, Gleichgewicht | 0,21585 Blöcke/Tick ≈ 4,317 m/s |
| erster Tick | 0,98F · 0,1F |
| Diagonal | ×1/0,98 (Eingabelänge 1,0) |
| Ausrollen | ×(0,6F · 0,91F) je Tick; Nullung unter 9·10⁻⁶ |
| Sprung | vy = 0,42F; Gipfel nach 6 Ticks bei +1,2522; Landung im 12. Tick; gehaltene Taste mit Periode 12 Ticks |
| Fall | vy ← (vy − 0,08) · 0,98F |
| Step-up | Kandidaten aus dem Formgitter: Halbblock und Treppe 0,5; Falltür 0,1875; Teppich 0,0625; Schnee (4 Lagen) 0,375 |
| nicht übersteigbar | voller Block; Zaun und Mauer 1,5; Truhe 0,875 |

### 2.3 Regression aller Meilensteine

Jeder der drei Läufe (`m5-run.sh 256 | -1 | 0`) enthält den vollständigen Satz bisheriger Tests:

| Bereich | 256 | −1 | 0 |
|---|---|---|---|
| JUnit gesamt (222 Unit + 5 Integration) | 227/227 | 227/227 | 227/227 |
| M1: `statusQuery`, Login/Configuration, `stableSixtySeconds…` (71 s Play, Keep-Alive ≤ 16 s, Disconnect) | ✓ | ✓ | ✓ |
| M2: `movementAndRotationAcceptedByServer`, M2-Terminaltest (`move`, `look`, `claim`, `/data get`) | ✓ | ✓ | ✓ |
| M3: Kompressionszähler (Integration); im Terminal eingehend | ✓ (Zählerprüfung im Integrationstest) | 0 von 2474 komprimiert | 2957 von 2958 komprimiert |
| M4: `WorldIntegrationTest` (657 + 81 + 81 Blöcke, Gegenprobe, 49 Chunks entladen); M4-Terminal (5× `Test passed`) | ✓ | ✓ | ✓ |
| M5: `PhysicsIntegrationTest`, M5-Terminaltest (`walk`, `jump`, `physics`, `collision`, `hitbox`, `trajectory`, `move`) | ✓ | ✓ | ✓ |
| Fabric-Client funktionsfähig (Referenzlauf) | ✓ | ✓ | ✓ |
| Tode im Serverlog | 0 | 0 | 0 |
| Serverwarnungen | nur das absichtliche `moved too quickly! 15,0` (`claim`-Test) | dto. | dto. |
| externe Verbindungen (netmon) | 0 | 0 | 0 |

**M5-Terminaltest** (Threshold 256), jede Bewegung serverseitig bestätigt:

```
walk f 20       → [-20.5d, 100.0d, -26.186574009714032d]
jump            → [-20.5d, 100.0d, -26.186574009714032d]
walk fl 15 jump → [-18.287495619980778d, 100.0d, -23.97406962969481d]
move 0 0 -4     → [-18.287495619980778d, 100.0d, -28.071636568885655d]
```

## 3. Unit-Tests

**Gesamt:** 222 Unit-Tests. Vorher waren es 188; die Integrationstests zählen nicht mit.

**Neu bzw. geändert in M5:**

| Testklasse | Fälle | Inhalt |
|---|---|---|
| `CollisionGeometryTest` | 15 | <ul><li>AABB</li><li>Achsenkollision mit Toleranzen</li><li>Achsenreihenfolge, Gleiten</li><li>Formtabelle gegen die Loom-Quellen</li><li>Step-up-Gitter</li><li>116/116 Offset-Proben</li><li>`Mth.getSeed`</li><li>Gerüst- und Pulverschnee-Kontext</li><li>Chunkgrenzen, negative Koordinaten</li><li>große Formen in Randzellen</li><li>ungeladene Zellen</li><li>Sinustabelle</li></ul> |
| `PlayerPhysicsTest` | 16 | <ul><li>Eingabe, Gleichgewicht, Ausrollen, Eis</li><li>Sprung, gehaltene Taste, Fall</li><li>Step-up: Halbblock, Treppe, Block</li><li>Zaun, Falltür, Decke</li><li>Chunkgrenzen, Durchgang, Wand</li><li>ungeladener Bereich, Wasser-Erkennung</li><li>Determinismus, ungültige Werte</li></ul> |
| `MovementControllerTest` | 14 | auf Blockgeometrie umgestellt; ersetzt die Heightmap-Tests aus M2 |
| `ClientSessionTest` | +2 | Regressionstests zu §4: Tod → genau ein `client_command`; nach `respawn` erneut `player_loaded`; Codecs von `set_health` und `client_command` |

## 4. Fehlende Chunks für `ProtoBotM3`: Ursache und Behebung

**Befund aus den Logs:**
1. Am 2026-11-07 um 09:22:04 meldet der Server `ProtoBotM3 was shot by Skeleton`. Das geschah im M2-Terminaltest des M4-Laufs bei −1: Überlebensmodus, Schwierigkeit `easy`, Nacht.
2. Der Tod ist in den Spielerdaten gespeichert. Seitdem enthält das `login`-Paket an diesen Spieler die optionale `lastDeathLocation`: 138 statt 110 Bytes, genau die 28 Bytes aus `minecraft:overworld` und der Positions-Long.
3. Seit dem M4-Lauf bei Threshold 0 erhielt `ProtoBotM3` **keinen einzigen Chunk**. Server-Paketlog: `set_chunk_cache_center`, aber nie `chunk_batch_start`. Der Server wartet auf `client_command PERFORM_RESPAWN` (`ServerGamePacketListenerImpl#handleClientCommand :1929`).
4. Der Vanilla-Client zeigt dafür den Todesbildschirm (`DeathScreen :80-81`: `player.respawn()`). Unser Client dekodierte `set_health` gar nicht und sendete nie einen Respawn.

> **Korrektur zu docs/20:** Dort steht für den M2-Terminaltest bei Threshold 0 „wie docs/17“. Tatsächlich war der Spieler damals schon tot und erhielt keine Chunks. Die Bewegungen fanden ohne Weltdaten statt. Das war damals nicht erkannt worden.

**Isolierte Reproduktion** (`analysis/tools/m5-chunk-repro.sh`: nur Server und eigener Client, 20 s):

```
login packet: 138 bytes (lastDeathLocation vorhanden)
WARNUNG mcclient.session: Player is dead (health 0.0) – requesting respawn
set_health: 2  client_command: 1  respawn: 1  player_loaded: 2  chunks: 49
```

**Behebung:**
- `Clientbound.SetHealth` mit Validierung endlicher Werte.
- `Serverbound.ClientCommand`.
- `ClientSession#onSetHealth`: Bei Lebenspunkten ≤ 0 genau einmal `PERFORM_RESPAWN` senden; Zähler `respawnsRequested`.
- `onRespawn` setzt `playerLoadedSent` zurück, wie `ClientPacketListener#handleRespawn :1300`.
- Der Vanilla-Treiber belebt ebenso automatisch wieder (`player.respawn()` und `gui.setScreen(null)`).
- Regressionstest: `ClientSessionTest#deadPlayerRequestsRespawnOnceAndReportsLoadedAgain`.

Ergebnis: In allen drei Läufen erhält `ProtoBotM3` 49 Chunks. Der M2-Test bewegt sich wieder auf echtem Gelände.

## 5. Weitere während des Nachtests gefundene Fehler im Testaufbau

| Befund | Ursache | Behebung |
|---|---|---|
| `ResearchRef was slain by Zombie` (Referenzspur unvollständig) | Survival auf `easy` | `difficulty=peaceful` für Testläufe (§6) |
| 6 Szenarien „no teleport – skipping“ | Zwischenpunkt y + 0,25 war nur einen Tick lang beobachtbar | Zwischenpunkt auf festem Boden plus Handshake `STAGED` |
| `ProtoBotM4 fell from a high place` (in Peaceful) | `WorldIntegrationTest` schaltete gleichzeitig mit dem Rück-Teleport auf Survival; die Fallstrecke aus Kreativ-Stürzen wurde als Schaden gewertet | erst zurück und landen lassen, dann Survival |
| Terminaltests endeten sofort mit „client not ready“ | Warteschleife `for i in $(seq 1 120)` endete nach 0,09 s; Fehlerausgabe ging nach `/dev/null`, daher keine sichere Ursache | zeitbasierte Schleife mit `$SECONDS`; `sleep`-Fehler werden protokolliert; Ausgaben gehen nach `*.stdout.txt`. Danach in drei Läufen nicht mehr aufgetreten. |
| `tail -f`-Prozesse und Netzwerkmonitor blieben nach dem Lauf | kein Aufräumen bei vorzeitigem Ende | Exit-Trap in den Terminaltests; `stopmon.ps1` beendet die Monitore genau dieses Laufs |

## 6. Speicher

### Analyse des Abbruchs vom 2026-11-07

- Es gab weder einen `OutOfMemoryError` noch eine `hs_err`-Datei.
- Das Skript lief sogar bis `=== done threshold 256`.
- Claude Code hat die Hintergrund-Shell beendet, weil der freie Arbeitsspeicher des **gesamten Systems** kritisch niedrig war.
- Von 15,9 GB RAM belegten Programme außerhalb des Projekts rund 10 GB, darunter ein Spielclient mit etwa 5 GB und die IDE mit etwa 2 GB. Diese wurden nicht angetastet.
- Im Projekt liefen JVMs mit offenen oder zu großen Grenzen gleichzeitig.

### Maßnahmen

| Prozess | vorher | jetzt |
|---|---|---|
| Vanilla-Server | `-Xmx2G` | `-Xms512M -Xmx1536M` |
| Minecraft-Client (Referenz) | kein `-Xmx` (≈ 4 GB möglich) | `-Xms512M -Xmx2G`; Sichtweite 2, Simulation 5, 30 FPS |
| Gradle des Forschungsprojekts | Daemon `-Xmx1G`, blieb resident | `--no-daemon` beim Referenzlauf |
| Gradle des Clients | Standard | `org.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=320m` |
| Test-JVM | Standard 512 MB | `maxHeapSize = 512m` (explizit) |
| eigener Client (`gradlew run`) | kein `-Xmx` | `maxHeapSize = 256m` |
| Ablauf | – | Vorprüfung mit Abbruch unter 2,5 GB freiem RAM; `memmon.ps1` alle 5 s; danach gezieltes Beenden der Monitore |

### Messung

Werte aus `memory.txt`, Summe der Working Sets aller Projekt-JVMs:

| Lauf | freier RAM beim Start | Spitze Projekt-JVMs | minimal freier RAM |
|---|---|---|---|
| 256 | 5605 MB | 2252 MB | 3035 MB |
| −1 | – | 2236 MB | 2255 MB |
| 0 | 5714 MB | 2209 MB | 1078 MB |

Der Projektbedarf bleibt mit rund 2,2 GB konstant. Den Tiefstwert von 1078 MB bei Threshold 0 verursachten Programme außerhalb des Projekts. Alle drei Läufe blieben stabil.

## 7. Verlauf: erster Lauf vom 2026-11-07

Im ersten Lauf waren 19 von 20 Szenarien bitgleich. Bei `jump_hold` war die Vanilla-Spur ab Tick 9 um eine Sprungperiode verschoben.

Ursache: Das Szenario begann am Endpunkt des vorigen. Der Treiber sah sich deshalb sofort „am Start“, und der eigentliche Teleport kam erst während des Laufs an.

Mit dem Handshake aus §1 ist `jump_hold` in allen drei Läufen bitgleich.

## 8. Abschlusskriterien

| Kriterium | Ergebnis |
|---|---|
| 1. Tatsächliche Kollisionsformen der getesteten Blöcke | ✓ Tabelle aus dem Server-JAR, gegen die Quellen geprüft; 896/896 Ticks bitgleich |
| 2. Kein Durchlaufen voller Blöcke oder Sonderformen | ✓ bündiger Stopp an Block, Zaun, Mauer, Tür, Gitter, Truhe; serverbestätigt |
| 3. Springen, Fallen, Landen | ✓ bitgleich (`jump_in_place`, `jump_hold`, `jump_onto`, `low_ceiling`, `drop_pit`) |
| 4. Serverseitig akzeptierte Abläufe stimmen mit Vanilla überein | ✓ 20/20 Endpositionen gleich Server; 20/20 Spuren bitgleich bei 256, −1 und 0 |
| 5. Keine unbeabsichtigten Positionskorrekturen | ✓ 0 in allen Szenarien und Terminalbewegungen |
| 6. Alle bisherigen Tests bestehen | ✓ 227/227 bei allen drei Schwellenwerten, dazu alle Terminaltests |

**M5 ist abgeschlossen.**

**Nach den Läufen:**
- Alle Projektprozesse sind beendet; Port 25565 ist frei.
- `server.properties` steht wieder auf dem Original: `difficulty=easy`, `network-compression-threshold=-1`.
- Die Original-JARs sind unverändert (Zeitstempel 2026-11-06).

**Bewusst nicht umgesetzt** (docs/21 §9): Flüssigkeiten, Leitern, Sprinten, Schleichen, Prallen, Spinnennetz, Entity-Kollision. Der Client meldet solche Umgebungen als `unsupported`.
