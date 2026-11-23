# 62 – Testbericht M15 (Stand 2026-11-23): teilweise abgeschlossen

**Status: M15 ist teilweise abgeschlossen. Kein Merge nach `main`.**

- **Grund:** Die vollständige Regression M1–M14 auf dem M15-Stand ist noch offen. Die Schritte 5 und 11–42 konnten nicht laufen, weil der freie Arbeitsspeicher unter der vorgegebenen Grenze von 6800 MB blieb (§3).
- **Branch:** `m15-menu-settings`. Code der Regression: `33835af`. `main` = `aba97d0` (M14).
- **Nicht begonnen:** M16, M17, M18; die echte Microsoft-Authentifizierung bleibt zurückgestellt.
- **Weitere Dokumentation:** Architektur docs/59, Einstellungen/Eingabe docs/60, Menüs/Sitzungen/MVS/Distribution docs/61.

Kennzeichnung: **[G]** gemessen/automatisch geprüft, **[E]** begründete Abweichung, **[O]** offen.

## 1. Ergebnisübersicht

| Prüfung | Ergebnis | Code |
|---|---|---|
| JUnit ohne Server | 509/509 grün, 0 übersprungen [G] | `47566ef` |
| JUnit mit Integrationstests (Regressionsschritt 1) | 515 Tests, 0 Fehler, 1 übersprungen (§2) [G] | `33835af` |
| Regression M1–M14 | Schritte 1–4 und 6–10 bestanden; Schritt 5 Testskriptfehler; Schritte 11–42 wegen Speichers nicht gelaufen [O] | `33835af` |
| GUI-Integrationstest (3 Durchgänge, lokaler Server) | 399 OK / 0 FAIL [G] | `5088095` |
| Echte Betriebssystem-Eingaben, 15 Abläufe | 52 OK / 0 FAIL, Serverabgleich 4/4 Positionen und 2/2 Inventar [G] | `1f00585` |
| Leistung M14 gegen M15 | keine Regression; 1 %-Low 59,2–59,4 FPS (§5) [G] | `6321f5b` |
| Testdistribution aus sauberem Verzeichnis | 18 OK / 0 FAIL, Fehlermeldungen geprüft (§6) [G] | `1f00585` |
| docs/59–62 | vorhanden | – |

**Gelten die älteren Läufe für den Endstand?**
- Seit `5088095` (GUI-Test) wurden nur Testwerkzeuge, Distribution, Dokumentation und die Testskript-Klasse `UiAutotest` geändert. Diagnose und Profiler-Ausgabe in `UiAutotest` und `Window` wirken nur im Testmodus. Am Spielcode hat sich nichts geändert.
- Der Test mit echten Eingaben (`1f00585`) und die Leistungsreihe (`6321f5b`) liefen auf dem Spielcode von `33835af`.
- Der GUI-Test vom 22.11. (`5088095`) lief auf demselben Spielcode, aber mit einem älteren Stand der Testwerkzeuge. Vor dem Merge soll er auf dem Endstand einmal wiederholt werden (§8).

## 2. JUnit

- **Unit-Tests (`./gradlew test`, `47566ef`):** 509 Tests, 0 Fehler, 0 übersprungen.
  - 468 aus M1–M14.
  - 41 neue aus M15: Einstellungen 13, Eingabe 9, Menüs 11, Verbindungen 8. Sie decken Sitzungsverwaltung, Einstellungen, Tastenbelegungen und GUI-Eingaben ab.
  - Die M13-Authentifizierungssimulation (`EncryptedLoginTest`, `AuthChainTest` u. a.) ist enthalten und grün.
- **Mit Integrationstests (`-PintegrationTest`, Regressionsschritt 1, `33835af`):** 515 Tests, 0 Fehler, 1 übersprungen.
  - Übersprungen ist `VehicleIntegrationTest#vehicleCourseMatchesServerAndVanillaReference`. Er braucht Fahrzeugkurs und Vanilla-Fahrzeugreferenz und läuft nur in Regressionsschritt 39 (`m14-vehicle-run.sh`); dieser ist auf M15 noch offen.

## 3. Regression M1–M14 auf `33835af`

Ablauf wie in M14: Schritte einzeln, im Vordergrund überwacht, Speicherprüfung vor jedem Schritt mit `MIN_FREE=6800`, Gradle-Daemons nach jedem Schritt beendet. Das Skript hat 42 Schritte (geprüft: 32 `step`-Zeilen, darunter drei Schleifen).

| Schritt | Gruppe | Ergebnis |
|---|---|---|
| 1 | JUnit, M2–M5-Integration, Physik gegen Vanilla (Kompression 256) | 515 Tests, 0 Fehler; 20/20 Szenarien, 896/896 Ticks bitgenau [G] |
| 2 | M6 vollständig | 125/125 Schritte [G] |
| 3–4 | M7 Auswahl gegen Vanilla (Überleben/Kreativ) | je 75/75 gleich [G] |
| 5 | M7 Interaktion | **Abbruch durch das Testskript** (unten) [O] |
| 6 | M7 Licht | 1729/1729 Zellen gleich [G] |
| 7 | M7 Grafik | 308/308 [G] |
| 8–10 | M8 Inventar s1–s3 | 5/25/28 RESULT OK, 0 FAIL [G] |
| 11–14 | M8 s4–s6, M8 Grafik | kontrolliert nicht gestartet: 6340 MB frei < 6800 [O] |
| 15–42 | M9–M14, M13-Simulation, Fahrzeuge, Leistung, Bildvergleich | nicht gestartet [O] |

**Schritt 5 im Detail:**
- `m7-interact.sh` meldete „client not ready“ schon 0,08 s nach dem Start des Terminalclients, statt bis zu 120 s auf dessen Bereitschaft zu warten.
- Die Aufräumroutine schickte 2 s später `stop` an den Server (Serverlog 18:25:41). Der Client verband sich um 18:25:43 und bekam „Connection refused“.
- Damit liegt der Fehler beim Testablauf, nicht beim Client: Der Terminalclient ist von M15 nicht verändert, und derselbe Schritt bestand in M14 (14/14).
- Warum die Warteschleife sofort endete, ließ sich aus den Logs nicht klären [O].
- Ein verwaister `tail`-Prozess dieses Schritts wurde danach beendet.

**Speicher:**
- Mit dem Arbeitsspeicher-Bedarf eines geöffneten Browsers (DuckDuckGo, etwa 1,9 GB) lag der freie Speicher 8 Minuten lang stabil bei 6,3–6,4 GB.
- Fremde Programme wurden nicht beendet, Schutzmechanismen nicht verändert.
- **Fortsetzung:** `bash analysis/tools/m14-regression.sh 5 5`, dann `… 11 11` usw., jeweils bei mindestens 6800 MB frei.

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

## 7. GUI-Integrationstest (Lauf `m15-ui2`, vom 22.11.)

- **Durchgang 1, 84 OK:** Menüs mit Tastatur und Maus, Einstellungen, Tastenbelegung mit Konflikt, Abbrechen/Zeitüberschreitung, Verbinden, Inventar, Pause, Einstellungen im Spiel, Fortsetzen, Trennen, erneut verbinden, Auflösungen 1600 × 900, 1920 × 1080 (randlos), 854 × 480 und GUI-Skalierung 2/4 ohne gekürzte Texte, Beenden.
- **Durchgang 2, 308 OK:**
  - nach Neustart neu geladene Einstellungen, Zurücksetzen
  - 20 Verbindungs- und Trennzyklen mit Inventar; danach 0 Sitzungs- und Tick-Threads, GL-Bestand konstant 93
  - 25 schnelle Screenwechsel
  - VSync und Sichtweite im Spiel
- **Durchgang 3, 7 OK:** Start mit beschädigter Einstellungsdatei.

## 8. Offene Punkte (kein Merge)

1. **Regression M1–M14:** Schritt 5 wiederholen und die Schritte 11–42 auf `33835af` (bzw. dem Endstand) ausführen, jeweils bei mindestens 6800 MB frei. Darin enthalten: M9–M11, M12-HUD/Bildvergleich, M13-Simulation, M14-Grafik, Fahrzeuge (1125 Ticks) und Leistung.
2. **GUI-Durchgänge 1–3** auf dem Endstand der Testwerkzeuge einmal wiederholen (Phase 7): 20 Zyklen, Threads, GPU-Ressourcen, Menü-Eingabesperre.
3. **Schritt 5:** Ursache der sofort endenden Warteschleife klären, falls sie erneut auftritt.
4. **Danach:** Abschlussbericht, Merge nach `main` und Bestätigung des finalen Hashes.
