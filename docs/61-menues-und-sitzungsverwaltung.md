# 61 – M15: Menüs, Sitzungsverwaltung, MVS-Vorbereitung, Testdistribution

Stand 2026-11-23. Tests: `ConnectionManagerTest`, `MenuTest`, GUI-Durchgänge 1–4, Distributionstest (docs/62).

## 1. Hauptmenü

Der reguläre Start (ohne `--autotest`/`--direct`/`--login-ui`) zeigt das Hauptmenü:

- **Einzelspieler / lokale Testwelt:**
  - Zeigt nur den vorhandenen lokalen Vanilla-Testserver (127.0.0.1:25565). Ein eingebauter Einzelspielerserver existiert nicht und wurde nicht erfunden.
  - Statusping beim Öffnen und alle 5 s: Erreichbarkeit, Version, Spieler, Ping, Kompatibilität.
  - Knöpfe „Welt betreten“ und „Erneut prüfen“; ein Hinweis erklärt, dass der Server außerhalb des Clients gestartet wird.
- **Mehrspieler:** Serverliste (§5).
- **Einstellungen:** docs/60.
- **Konto:**
  - Offline-Spielername.
  - „Mit Microsoft anmelden“ ist gesperrt mit Erklärung; die echte Anmeldung bleibt bis zum letzten Meilenstein zurückgestellt.
  - Es werden keine Tokens angezeigt oder gespeichert.
- **Über Merlin Client:**
  - Name, Client-Version `0.15.0` (eigene Kennung, nicht die Protokollnummer), Minecraft 26.3 / Protokoll 777, Hinweis auf MVS
  - Entwicklungsstand, Lizenz- und Markenhinweis, Repository `https://github.com/MerlinHose/MMerlins-Client`
  - Systeminfo (OpenGL, Java, Systemskalierung, Einstellungsdatei)
- **Spiel beenden:** regulär. Esc im Hauptmenü beendet nichts.

## 2. Verbindungsvorgang (`app.ConnectionManager`)

```
IDLE → CHECKING (Statusping) → CONNECTING (TCP) → LOGIN → CONFIGURATION → LOADING_WORLD → CONNECTED
                     ↘ FAILED (verständliche Meldung)      ↘ CANCELLED (Abbrechen)      → DISCONNECTED (Trennen)
```

**Phasen**
- Ab dem TCP-Aufbau leitet sich die Phase aus dem tatsächlichen Zustandsautomaten der `ClientSession` ab: Protokollzustand LOGIN, CONFIGURATION oder PLAY.
- `LOADING_WORLD` dauert, bis der Server die Position gesetzt hat und der erste Chunk geladen ist.
- Der `ConnectingScreen` zeigt genau diese Phasen mit Häkchen, die aktuelle animiert, und einen Abbrechen-Knopf.

**Threads**
- Ein kurzlebiger Thread `connect` für Statusping und TCP-Aufbau.
- Je Sitzung `session` (Paketschleife) und `client-tick` (20 Hz).
- `awaitIdle()` wartet auf beide; `release()` gibt die Sitzung frei. Endzustand und Meldung bleiben lesbar bis `reset()`.

**Abbrechen**
- Jederzeit möglich. Hängt der Statusping (stummer Dienst), endet der Thread nach höchstens 3 s.
- Eine Versuchsnummer verhindert, dass ein abgebrochener, noch laufender Versuch den nächsten verändert; das war ein im GUI-Test gefundener Fehler.

**Fehlermeldungen (`ConnectErrors`)**

| Meldung | Auslöser |
|---|---|
| Server nicht erreichbar | Verbindung abgewiesen |
| Zeitüberschreitung | Dienst antwortet nicht |
| Verbindung unterbrochen | Ende des Datenstroms |
| Nicht unterstützte Serverversion | Statusping meldet anderes Protokoll oder Server antwortet `outdated` |
| Server hat die Verbindung abgelehnt | Whitelist, gesperrt, voll, doppelte Anmeldung, online-mode, Anmeldedienste |
| Vom Server getrennt | Kick |
| Adresse nicht erlaubt | nicht Loopback |
| Protokollfehler | Datenfehler im Protokoll |

Servertexte werden bereinigt: Wörter wie access/refresh/bearer/token/secret samt Wert und lange Base64-, JWT- oder Hex-Folgen werden entfernt, die Länge ist auf 300 Zeichen begrenzt.

## 3. Pausenmenü und Trennen

- **Pausenmenü:** Esc in der Welt öffnet „Spielmenü“ mit „Zurück zum Spiel“, „Einstellungen“, „Verbindung trennen“ und „Zum Hauptmenü“.
  - Die Welt bleibt abgedunkelt sichtbar.
  - Die Sitzung läuft weiter: Paket-, Tick- und Keep-Alive-Verarbeitung laufen auf eigenen Threads und hängen nicht vom Menü ab. Die Serversimulation wird nicht angehalten.
  - Bewegung, Blick, Angriffe und Blockinteraktion sind gesperrt; im GUI-Test und mit echten Eingaben geprüft.
- **Einstellungen im Spiel:** Sie wirken sofort. Sichtweite und Partikelstufe gehen per `client_information` an den Server, nur bei einer Änderung.
- **„Verbindung trennen“ / „Zum Hauptmenü“** sind die Varianten „mit Meldung“ bzw. „direkt“. Ablauf:
  1. `ConnectionManager.disconnect` → `ClientSession.requestStop` (TCP schließen ohne Paket, wie Vanilla).
  2. Sobald die Sitzung beendet ist, gibt `GameScene.dispose` frei: Eingabe zurückgesetzt, Chunk-Meshes gelöscht, Entity-Zustand und Partikel geleert, Inventar- und Tod-Screens entfernt.
  3. `awaitIdle` wartet bis zu 5 s auf die Threads, `release` löst die Sitzung (Welt- und Entity-Daten sind dann nicht mehr erreichbar).
  4. Danach erscheint „Verbindung getrennt“ (mit „Erneut verbinden“ / „Hauptmenü“) bzw. direkt das Hauptmenü.
- **Trennung durch den Server oder Fehler im Spiel:** „Verbindung getrennt“ mit der verständlichen Meldung.
- **Wiederholtes Verbinden** ist ohne Neustart möglich; 20 Zyklen sind geprüft (docs/62).

## 4. Inventar und Tod

Die bewährten Bildschirme aus M8/M10 bleiben unverändert in der Spiellogik und werden als `GameScreen` im ScreenManager geführt (docs/59 §4):
- Inventartaste oder Esc schließt.
- Mausereignisse gehen an das Inventar.
- Das Pausenmenü öffnet sich nicht, solange ein solcher Screen offen ist.

## 5. Serverliste (Mehrspieler)

- **Eintrag (`ServerEntry`):** Name, Adresse, Port, zuletzt erkannte Version (Name und Protokoll).
- **Status (`ServerStatus`):** nicht geprüft / wird geprüft / erreichbar (Version, Spieler, Ping, Kompatibilität) / nicht erreichbar / gesperrt.
- **Bedienung:** Hinzufügen, Bearbeiten (Prüfung von Name und Port), Löschen mit Rückfrage, Aktualisieren. Speicherung atomar in `servers.properties`; ungültige Einträge werden übersprungen und gemeldet.
- **Keine Verbindung zu externen Servern:**
  - Nur `localhost` und IP-Literale im Loopback-Bereich.
  - Hostnamen werden ohne DNS-Abfrage als gesperrt gezeigt, auch für den Statusping.
  - Der Netzwerkmonitor meldet in allen Läufen 0 externe Verbindungen.
  - Eine öffentliche Serverliste ist nicht Teil von M15.
- **Statusping:** bis zu 4 parallel (`ServerPinger`), damit ein stummer Dienst die anderen Einträge nicht aufhält.

## 6. Vorbereitung des Merlin Multi Version Systems (M18)

- **Versionen getrennt:**
  - Client-Version (`MerlinVersion.CLIENT_VERSION`), Minecraft-Version des eigenen Protokolls (26.3 / 777) und Serverversion je Eintrag.
  - Die Serverversion wird je Eintrag aus dem Statusping gespeichert, unabhängig von der Client-Version.
- **`ProtocolVersions`:** Unterstützungsstufen und Beispiele:

| Stufe | Beispiel |
|---|---|
| `NATIVE` | 777 |
| `PLANNED_MVS` | 764 = 1.20.2 bis 776 |
| `TOO_OLD` | < 764 |
| `TOO_NEW` | > 777 |
| `UNKNOWN` | – |

- **Bekannte Nummern:** 764 (1.20.2), 765 (1.20.3–4), 766 (1.20.5–6), 767 (1.21–1.21.1), 768 (1.21.2–3), 769 (1.21.4), 770 (1.21.5), 777 (26.3). Unbekannte Zwischennummern zeigen den Versionsnamen des Servers.
- **Kompatibilitätsmeldung:** erscheint in der Liste, in der lokalen Testwelt und im Fehler „Nicht unterstützte Serverversion“, z. B. „Server 1.20.4 (Protokoll 765) ist älter. Unterstützung ab 1.20.2 ist mit dem Merlin Multi Version System (M18) geplant.“
- **Einbaustelle für M18:** In `ConnectionManager.work` wird nach dem Statusping heute nur `NATIVE` verbunden. M18 kann dort je Serverversion eine Übersetzungsschicht zwischen `ClientSession` und `Connection` wählen. Die vorhandenen Protokollklassen wurden in M15 nicht umgebaut; es gibt keine Protokollübersetzung.

## 7. Konfigurationsdateien

- `~/.minecraft-merlin/settings.properties` (docs/60 §3)
- `~/.minecraft-merlin/servers.properties`

Beide liegen außerhalb des Repositorys und sind nicht Teil der Distribution.

## 8. Startbare Testdistribution

**Bau:** `./gradlew merlinDist` → `client/build/merlin-dist/MerlinClient/`

```
MerlinClient/
  bin/merlin.cmd   bin/merlin.sh   LIESMICH.txt
  lib/mc-protocol-client-0.1.0.jar        (Protokoll, Welt, Physik, Menüs, Einstellungen – nur JDK)
  lib/merlin-client-render-0.1.0.jar      (LWJGL/OpenGL-Teil, MerlinLauncher)
  lib/lwjgl(-glfw|-opengl)-3.4.3.jar  +  …-natives-windows.jar
```

- **Größe:** 4,7 MB.
- **Enthält keine Minecraft- oder Mojang-Dateien**, keine Einstellungen und keine Tokens. Die enthaltenen `.txt`-Ressourcen sind die eigenen Faktentabellen des Projekts (Namen und Zahlen, seit M1 im Repository).
- **`merlin.cmd`:**
  - Prüft, ob Java vorhanden ist und Version ≥ 25 hat (`JAVA_HOME` oder PATH), mit verständlicher Meldung und Exit-Code 2.
  - Startet dann `MerlinLauncher` mit 768 MB Heap, 256 MB Direktspeicher und `--enable-native-access=ALL-UNNAMED`.
- **`MerlinLauncher`:**
  - Sucht die Minecraft-26.3-Ressourcen: `--assets <jar>`, `MERLIN_MINECRAFT_JAR`, `%APPDATA%\.minecraft\versions\26.3\26.3.jar`, `~/.minecraft/versions/26.3/26.3.jar`.
  - Prüft die JAR (assets/minecraft vorhanden).
  - Meldet fehlende Voraussetzungen in Konsole und Dialog (abschaltbar mit `MERLIN_NO_DIALOG`) und beendet mit Code 2; sonst startet er das Hauptmenü.

**Voraussetzungen, die der Benutzer selbst bereitstellen muss:**
1. Java 25 (z. B. Microsoft Build of OpenJDK 25).
2. Eine lokal installierte, rechtmäßig erworbene Minecraft-26.3-Version, mindestens einmal mit dem offiziellen Launcher gestartet.
3. Für die lokale Testwelt: einen laufenden Vanilla-26.3-Server auf 127.0.0.1:25565 mit `online-mode=false`.
4. Windows x64; die Natives der Distribution sind für Windows.

Ein Installer bzw. Launcher-System bleibt ein eigenes Thema.
