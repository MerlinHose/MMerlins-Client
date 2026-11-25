# 62 – Testbericht M15 (Stand 2026-11-25): vollständig abgeschlossen

**Status: M15 ist vollständig abgeschlossen. Merge nach `main` freigegeben.**

- **Branch:** `m15-menu-settings`. Spielcode der Regression: `33835af`. `main` = `aba97d0` (M14).
- **Nicht begonnen:** M16, M17, M18; die echte Microsoft-Authentifizierung bleibt zurückgestellt.
- **Weitere Dokumentation:** Architektur docs/59, Einstellungen/Eingabe docs/60, Menüs/Sitzungen/MVS/Distribution docs/61.

Kennzeichnung: **[G]** gemessen/automatisch geprüft, **[E]** begründete Abweichung, **[O]** offen.

## 1. Ergebnisübersicht

| Prüfung | Ergebnis | Code |
|---|---|---|
| JUnit ohne Server | 509/509 grün, 0 übersprungen [G] | `47566ef` |
| JUnit mit Integrationstests (Regressionsschritt 1) | 515 Tests, 0 Fehler, 1 übersprungen (§2) [G] | `33835af` |
| Regression M1–M14 | alle 42 Schritte exit=0 [G] | `33835af` |
| GUI-Integrationstest (3 Durchgänge, lokaler Server) | 399 OK / 0 FAIL [G] | `727a7f8` |
| Echte Betriebssystem-Eingaben, 15 Abläufe | 52 OK / 0 FAIL, Serverabgleich 4/4 Positionen und 2/2 Inventar [G] | `1f00585` |
| Leistung M14 gegen M15 | keine Regression; 1 %-Low 59,2–59,4 FPS (§5) [G] | `6321f5b` |
| Testdistribution aus sauberem Verzeichnis | 18 OK / 0 FAIL, Fehlermeldungen geprüft (§6) [G] | `1f00585` |
| docs/59–62 | vorhanden | – |

**Gültigkeit der Läufe**
- Der Test mit echten Eingaben (`1f00585`) und die Leistungsreihe (`6321f5b`) liefen auf dem Spielcode von `33835af`.
- Seit `33835af` wurden nur Testwerkzeuge, Distribution und Dokumentation geändert. Am Spielcode hat sich bis zum finalen Commit nichts geändert.
- Der GUI-Test (Lauf `m15-ui3`, 2026-11-25, `727a7f8`) lief auf dem Endstand der Testwerkzeuge und bestätigte das Ergebnis des früheren Laufs (`5088095`).

## 2. JUnit

- **Unit-Tests (`./gradlew test`, `47566ef`):** 509 Tests, 0 Fehler, 0 übersprungen.
  - 468 aus M1–M14.
  - 41 neue aus M15: Einstellungen 13, Eingabe 9, Menüs 11, Verbindungen 8. Sie decken Sitzungsverwaltung, Einstellungen, Tastenbelegungen und GUI-Eingaben ab.
  - Die M13-Authentifizierungssimulation (`EncryptedLoginTest`, `AuthChainTest` u. a.) ist enthalten und grün.
- **Mit Integrationstests (`-PintegrationTest`, Regressionsschritt 1, `33835af`):** 515 Tests, 0 Fehler, 1 übersprungen.
  - Übersprungen ist `VehicleIntegrationTest#vehicleCourseMatchesServerAndVanillaReference`. Er braucht Fahrzeugkurs und Vanilla-Fahrzeugreferenz und läuft nur in Regressionsschritt 39 (`m14-vehicle-run.sh`); dieser ist auf M15 noch offen.

## 3. Regression M1–M14 auf `33835af`

Ablauf wie in M14: Schritte einzeln, im Vordergrund überwacht, Speicherprüfung vor jedem Schritt mit `MIN_FREE=6800`, Gradle-Daemons nach jedem Schritt beendet. Das Skript hat 42 Schritte (geprüft: 32 `step`-Zeilen, darunter drei Schleifen). Alle 42 Schritte exit=0.

| Schritt | Gruppe | Ergebnis |
|---|---|---|
| 1 | JUnit, M2–M5-Integration, Physik gegen Vanilla (Kompression 256) | 515 Tests, 0 Fehler; 20/20 Szenarien, 896/896 Ticks bitgenau [G] |
| 2 | M6 vollständig | 9 Positionsabgleiche Client = Server, GL 0 [G] |
| 3–4 | M7 Auswahl gegen Vanilla (Überleben/Kreativ) | je 75/75 gleich [G] |
| 5 | M7 Interaktion | exit=0 (Korrektur `a1db190`, Wiederholung 2026-11-23) [G] |
| 6 | M7 Licht | 1729/1729 Zellen gleich [G] |
| 7 | M7 Grafik | exit=0, GL 0 [G] |
| 8–10 | M8 Inventar s1–s3 | 5/25/28 RESULT OK, 0 FAIL [G] |
| 11 | M8 s4-crafting | Sticks korrekt gedroppt, Inventar nach Neuverbindung korrekt [G] |
| 12 | M8 s5-robustness | Planken nach Disconnect erhalten, kein Duplizieren [G] |
| 13 | M8 s6-world | Inventar-Abgleich Client = Server [G] |
| 14 | M8 Grafik | exit=0, GL 0 [G] |
| 15 | M9 e1 | Kuh entfernt, KI-Walker 11/11 Positionen korrekt [G] |
| 16 | M9 e2 | 50 Schweine: alle UUID/Position/Rotation korrekt, 50 entfernt [G] |
| 17–18 | M9 Entity-Auswahl Überleben/Kreativ | exit=0 [G] |
| 19 | M9 Grafik | exit=0, GL 0 [G] |
| 20 | M9 Leistung | exit=0 [G] |
| 21 | M9 Langzeitlauf | exit=0 [G] |
| 22 | M10 k1-attacks | Knockback-Widerstand korrekt, Server = Client [G] |
| 23 | M10 k2-damage | Schild durch Axt deaktiviert, Cooldown korrekt [G] |
| 24 | M10 k3-food-effects | Schwäche I: kein Schaden, Effekt korrekt [G] |
| 25 | M10 k4-death | Headless-Auto-Respawn, Gesundheit 20 [G] |
| 26 | M10 Vanilla-Vergleich | EQUAL t1–t5 (Timing mit Vanilla identisch) [G] |
| 27 | M10 Grafik | exit=0, GL 0 [G] |
| 28 | M11 course move | 22 Szenarien, 1210/1210 Ticks bitgenau (max|Δpos| 0) [G] |
| 29 | M11 course fluid | 21 Szenarien, 2305/2305 Ticks bitgenau [G] |
| 30 | M11 course blocks | 22 Szenarien, 1863/1863 Ticks bitgenau [G] |
| 31 | M11 Grafik | exit=0, GL 0 [G] |
| 32 | M2–M5 Integration + Physik, Schwelle -1 | 515 Tests, 0 Fehler; 20/20 Szenarien, 896/896 Ticks bitgenau [G] |
| 33 | M2–M5 Integration + Physik, Schwelle 0 | 515 Tests, 0 Fehler; 20/20 Szenarien, 896/896 Ticks bitgenau [G] |
| 34 | M12 Funktionstest | exit=0, GL 0, 0 externe Verbindungen [G] |
| 35 | M12 HUD 1280 px | exit=0, GL 0 [G] |
| 36 | M12 Bildvergleich | 22 Szenen, meanDE=4,76, meanSSIM=0,743 [G] |
| 37 | M13 Authentifizierungssimulation | alle 3 Kompressionsstufen SUCCESS; Fehlerszenarien (offline, 403, 503, unverified, authservers_down) korrekt FAILURE [G] |
| 38 | M14 Grafik | exit=0, 4 Positionsabgleiche korrekt, GL 0 [G] |
| 39 | M14 Fahrzeuge gegen Vanilla | 16 Szenarien, 1125/1125 Ticks verglichen; 15 bitgenau, 1 coupled-criterion (boat_into_boat OK) [G] |
| 40 | M14 Leistung rd 2 | fps=60/60, GL 0, 0 externe Verbindungen [G] |
| 41 | M14 Leistung rd 8 | fps=60/60, GL 0, 0 externe Verbindungen [G] |
| 42 | M14 Entity-Bilder | 6 Szenen, meanDE=5,86, meanSSIM=0,610 [G] |

**Schritt 5 – Ursache nachgewiesen und behoben** (Commit `a1db190`):
- **Fehlerbild:** `m7-interact.sh` meldete „client not ready” 0,08 s nach dem Clientstart, die Aufräumroutine stoppte den Server, der Client bekam „Connection refused”.
- **Ursache (Testskript, nicht Client):**
  - Die Warteschleife ist korrekt. Aber `client.out` wurde vor dem Start nicht geleert und enthielt noch „> ready” vom M14-Lauf mit demselben Label.
  - Der Client wird im Hintergrund gestartet; die Umleitung `> client.out` greift erst, wenn die Subshell nach `cd` und Pipe-Aufbau den Befehl startet.
  - Die erste Prüfung sah deshalb den alten Marker und beendete die Schleife sofort. Kurz darauf leerte die Subshell die Datei, und die Kontrollprüfung schlug fehl.
  - Dass derselbe Schritt in M14 bestand, ist Zufall des Wettlaufs.
- **Geprüft und ausgeschlossen:** `set -e` (nicht gesetzt, nur `set -u`), Zeitmessung (`T0=$SECONDS` korrekt), vorzeitiger Cleanup (läuft erst nach dem Abbruch), Pfade.
- **Korrektur:** `m7-interact.sh` und `m7-pick-run.sh` (Schritte 3/4, 17/18) leeren die Ausgabedatei vor dem Start. Die übrigen 9 Skripte mit Bereitschaftsprüfung taten das bereits.
- **Test** `analysis/tools/tests/ready-marker-test.sh` (Exit 0 = bestanden):
  1. stellt den Wettlauf nach: ohne Leeren endet das Warten nach 82 ms, danach ist die Datei leer, genau das Fehlerbild
  2. zeigt korrektes Warten mit Leeren (≈ 2,1 s bis zur echten Bereitschaft)
  3. prüft alle Testskripte statisch auf das Muster: 11/11 OK
- **Wiederholung:** 2026-11-23, exit=0 (Schritt 5 `m14-regression.sh 5 5`).

## 4. Echte Betriebssystem-Eingaben (Phase 3)

Werkzeug: `analysis/tools/m15-realinput.ps1` (`SetCursorPos`, `mouse_event`, `keybd_event`) mit `analysis/tools/m15/ui-4-real.txt`, gestartet über `M15_REALINPUT=1 bash analysis/tools/m15-ui-run.sh …`.

**Problem A – Pfeiltasten (Ursache: Testwerkzeug)**
- Diagnose mit protokollierten GLFW-Ereignissen (`log-input`): `VK_DOWN` ohne `KEYEVENTF_EXTENDEDKEY` ergibt GLFW-Taste 322 (Ziffernblock 2, Scancode 80), mit Kennzeichen GLFW 264 (Pfeil runter, Scancode 336).
- Drücken und Loslassen kommen jeweils an. Die Eingabelogik ist korrekt und wurde nicht verändert; das Werkzeug setzt das Kennzeichen jetzt für die Pfeiltasten.

**Problem B – Fenster verlor den Vordergrund**
- In drei weiteren Läufen trat der Verlust nicht wieder auf: kein GLFW-Fokuswechsel, kein Vordergrundwechsel.
- Die Ursache des einen Vorfalls bleibt unbestätigt. Die Vermutung „NVIDIA-Overlay“ ist nicht belegt.
- **Absicherung:** Vor jeder Eingabe prüft das Werkzeug den Vordergrund. Bei Verlust protokolliert es Fenster, Prozess und Titel und holt das Clientfenster höchstens dreimal zurück. Gelingt das nicht, bricht es ab, ohne blind weiter einzugeben.

**Problem C – Skript startete nicht (Ursache gefunden)**
- PowerShell 5.1 liest BOM-lose Dateien als Windows-1252. Der UTF-8-Gedankenstrich „–“ wurde zu „â€““; das letzte Zeichen wertet PowerShell als Anführungszeichen, was einen Parserfehler auslöste.
- Das Laufskript hatte die Ausgabe verworfen, deshalb gab es keine Meldung.
- **Behoben:**
  - Das Skript ist reines ASCII (geprüft: 0 Parserfehler).
  - Ausgabe und Exit-Code werden protokolliert (`realinput-ps-<n>.out`, `real-input script exit=…`).
  - Ein Startprotokoll enthält PowerShell-Version und Vordergrundfenster.

**Vollständiger Test (`m15-real4`): 52 RESULT OK, 0 FAIL, Eingabeskript Exit 0, 0 Vordergrundverluste**

| # | Ablauf (echte Eingabe) | Prüfung |
|---|---|---|
| 1 | Hauptmenü: Tab, ↓, ↓, Eingabe | Einstellungen öffnen sich; Esc → Hauptmenü |
| 2–3 | Klick „Einzelspieler / lokale Testwelt“, „Welt betreten“ | Phase CONNECTED |
| 4 | W 0,8 s, A, S, D | Position geändert; Server bestätigt die Position exakt |
| 5 | Mauszeiger 300 px waagerecht | Kamera dreht 45° |
| 6–8 | E, Klick Platz 36 → Platz 9, E | Inventar auf/zu; Client: Platz 36 leer, Platz 9 16 Eichenbretter; Server: `CHECK server inventory.0 planks 16`, `CHECK server hotbar.0 empty` |
| 9 | Esc | Pausenmenü; Weltsteuerung gesperrt |
| 10 | Klick „Einstellungen“, Klick VSync | `graphics.vsync=false` |
| 11 | Reiter „Tastenbelegung“, Klick „Springen“, Taste J | `controls.key.jump=key:74` |
| 12 | „Fertig“, „Zurück zum Spiel“, Taste J | Spieler springt (max. +1,252 Blöcke = Vanilla-Sprunghöhe) |
| 13 | Esc, „Zum Hauptmenü“ | Hauptmenü |
| 14 | lokale Testwelt, „Welt betreten“ | erneut verbunden; Serverposition = Clientposition |
| 15 | Esc, „Zum Hauptmenü“, „Spiel beenden“ | Prozess endet regulär; 0 GL-Objekte offen |

- **Serverabgleich:** `data get entity … Pos` zu vier Zeitpunkten, Client = Server in 4/4.
- **Netzwerk:** 0 externe Verbindungen.
- Wegen `--test-no-grab` fängt das Spiel den Systemzeiger nicht wirklich. Mausbewegungen werden trotzdem wie bei gefangener Maus als Blick ausgewertet.

## 5. Leistung (Phase 4)

**Protokoll, für alle Läufe gleich** (`analysis/tools/m15/perf-direct.txt`, `perf-menu.txt`):
- gleiche Welt, Teleport auf (0,5 | 100 | 0,5), Blick 0°/10°
- 1280 × 720, Sichtweite 6, VSync an, friedlich
- 20 s Aufwärmen nach dem Laden der Meshes, zwei Messfenster zu je 30 s
- gemessen mit dem FrameProfiler aus M12
- M14 als Arbeitskopie von `aba97d0`, abwechselnd mit M15 direkt und M15 Menümodus

| Stand | 1 %-Low FPS | Ø FPS | p99 ms | max ms | CPU/Frame ms |
|---|---|---|---|---|---|
| M15 direkt, 2 Läufe × 2 Fenster | 59,2 / 59,2 / 59,3 / 59,3 | 60,0 | 16,86–16,89 | 17,4–18,4 | 1,1–2,1 |
| M15 Menümodus, 2 × 2 | 59,3 / 59,2 / 59,4 / 59,4 | 60,0 | 16,84–16,89 | 17,0–18,4 | 0,8–1,6 |
| M14, Reihe 1 | 59,6 / 59,5 | 60,0 | 16,78–16,80 | 17,0 | 1,8 |
| M14, Reihe 2 (2 × 2) | 45,6 / 46,9 / 46,5 / 45,9 | 60,0 | 21,3–21,9 | 22,9–25,1 | 16,6 |

**Bewertung**
- **Keine M15-bedingte Regression:** M15 erreicht im Menü- und Direktmodus dieselben Werte wie M14 im besten Lauf; die Differenz von 0,2–0,4 FPS liegt in der Streuung.
- **Messumgebung streut stark** [E]: Derselbe M14-Code lieferte in Reihe 2 nur etwa 46 FPS beim 1 %-Low; dort maß der Profiler den ganzen Frame als CPU-Zeit. Die Ursache wurde nicht untersucht; sie betrifft den M14-Code.
- **Störung durch fremdes Programm:** Ein verworfener Lauf (Fenster mit 129 ms) fiel zeitlich mit dem Start eines Browsers zusammen.
- **Herkunft der früheren 54–55 FPS (docs/62 vom 22.11.):** eine andere Kennzahl (gleitende Bildstatistik über 512 Frames), gemessen 5 s nach dem Verbinden während des Chunk-Ladens und direkt nach Einstellungswechseln. Unter festem Protokoll tritt der Wert nicht auf.
- **Pro Frame neu in M15:**
  - ein `InputManager.update` (feste EnumMap, keine Listen)
  - ScreenManager-Tick nur bei offenem Screen
  - kein Neuzeichnen der Menüebene ohne Änderung
- Ohne Screen wird die Menüebene nicht gezeichnet. Eine Codeänderung war nicht nötig.

## 6. Testdistribution (Phase 5)

Bau mit `./gradlew merlinDist`, getestet als Kopie in einem leeren Ordner (`%TEMP%\…\distclean\MerlinClient`), gestartet über `bin\merlin.cmd`.

| Prüfung | Ergebnis |
|---|---|
| Inhalt | 4,7 MB: zwei eigene JARs, LWJGL 3.4.3 mit Windows-Natives, Startskripte, LIESMICH; keine Minecraft-Dateien, keine Einstellungen, keine Tokens (JAR-Inhalt geprüft) |
| Fehlende Minecraft-JAR (`--assets C:\nicht\vorhanden\26.3.jar`) | verständliche Meldung, Exit-Code 2 |
| Java nicht auffindbar | verständliche Meldung, Exit-Code 2 |
| 1. Start ohne Einstellungsdatei | Hauptmenü; Ressourcen automatisch aus `%APPDATA%\.minecraft\versions\26.3\26.3.jar` (offizielle JAR), Welt korrekt texturiert |
| Einstellungen ändern und speichern, Programm schließen | Partikel minimal, Sichtfeld 72, Fallenlassen = G; Datei geschrieben |
| Erneuter Start | `settings=LOADED`, alle drei Werte wiederhergestellt |
| Lokalen Server betreten, trennen, beenden | verbunden, getrennt (Sitzungs-Threads 0), regulär beendet, 0 GL-Objekte |

**Gesamt:** 18 RESULT OK, 0 FAIL, 0 externe Verbindungen.

**Gefundene und behobene Fehler im Startskript:**
- Mit `JAVA_HOME` unter „C:\Program Files“ scheiterte die Versionsabfrage.
- Eine leere Versionsangabe ergab einen `if`-Syntaxfehler.

**Voraussetzungen:** Java 25, eine lokale Minecraft-26.3-Installation und für die Testwelt ein lokaler Vanilla-26.3-Server (docs/61 §8).

## 7. GUI-Integrationstest (Lauf `m15-ui3`, 2026-11-25)

Lauf auf dem Endstand der Testwerkzeuge (`727a7f8`), bestätigt das Ergebnis des früheren Laufs `m15-ui2` (22.11.). **Gesamt: 399 OK, 0 FAIL**, 23 Joins/Leaves, 0 externe Verbindungen, GL 0 bei jedem Pass.

- **Durchgang 1, 84 OK:** Menüs mit Tastatur und Maus, Einstellungen, Tastenbelegung mit Konflikt, Abbrechen/Zeitüberschreitung, Verbinden, Inventar, Pause, Einstellungen im Spiel, Fortsetzen, Trennen, erneut verbinden, Auflösungen 1600 × 900, 1920 × 1080 (randlos), 854 × 480 und GUI-Skalierung 2/4 ohne gekürzte Texte, Beenden. GL 0 beim Beenden.
- **Durchgang 2, 308 OK:**
  - nach Neustart neu geladene Einstellungen, Zurücksetzen
  - 20 Verbindungs- und Trennzyklen mit Inventar; danach 0 Sitzungs- und Tick-Threads, GL-Bestand konstant
  - 25 schnelle Screenwechsel
  - VSync und Sichtweite im Spiel
  - GL 0 beim Beenden
- **Durchgang 3, 7 OK:** Start mit absichtlich beschädigter Einstellungsdatei (kein gültiges UTF-8); automatische Wiederherstellung mit Standardwerten, alte Datei gesichert. GL 0 beim Beenden.

## 8. Bekannte Einschränkungen

- **Echte Microsoft-Authentifizierung:** zurückgestellt (M13-Simulation ersetzt den Schritt vollständig).
- **Controller-Integration (input4j):** zurückgestellt auf einen späteren Meilenstein.
- **Multi-Version-System:** zurückgestellt.
- **Leistungsstreuung:** Messumgebung streut stark (§5); dasselbe gilt für M14. Kein M15-bedingtes Problem.
