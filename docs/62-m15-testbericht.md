# 62 – Testbericht M15 (Stand 2026-11-22): teilweise abgeschlossen

**Status: M15 ist teilweise abgeschlossen. Kein Merge nach `main`.**

- **Branch:** `m15-menu-settings`, Basis `main` = `aba97d0` (M14).
- **Gepushte Zwischenstände:** `8b1a473`, `5088095`; dieser Bericht folgt im nächsten Commit.
- **Nicht begonnen:** M16, M17, M18; die echte Microsoft-Authentifizierung bleibt zurückgestellt.

Kennzeichnung: **[G]** gemessen bzw. automatisch geprüft, **[O]** offen.

## 1. Ergebnisübersicht

| Bereich | Ergebnis |
|---|---|
| GUI-System, ScreenManager, Hauptmenü, Einstellungen, Input System, Verbindungsverwaltung | umgesetzt (§2–§7) |
| Neue Unit-Tests (ohne Fenster) | 41 grün: Einstellungen 13, Eingabe 9, Menüs 11, Verbindungen 8 [G] |
| Darstellungsprüfung ohne Fenster | alle Screens und Reiter × 8 Auflösungen × 5 GUI-Skalierungen: 0 gekürzte Texte, nichts außerhalb, keine Überlappungen [G] |
| GUI-Integrationstest im echten Fenster mit lokalem Server (`m15-ui2`) | 3 Durchgänge, **399 RESULT OK, 0 FAIL** [G] |
| 20 Verbinden/Trennen ohne Neustart | 20/20; danach 0 Sitzungs-/Tick-Threads, GL-Objekte stabil 93 [G] |
| Test mit echten Betriebssystem-Eingaben | **offen** [O] (§9) |
| Startbare Testdistribution (Phase J) | **nicht umgesetzt** [O] |
| Vollständige Regression M1–M14 auf dem M15-Stand | **nicht ausgeführt** [O] |
| Dokumente 59, 60, 61 | **nicht erstellt** [O]; die Inhalte stehen in diesem Bericht kurz (§2–§7) |

## 2. Architektur (Kurzfassung statt docs/59)

Trennung nach der Vorgabe:

| Bereich | Klassen | Teil |
|---|---|---|
| Application Lifecycle | `gl/MerlinApp` | render |
| Screen Management | `ui/ScreenManager`, `ui/Screen`, `ui/GameScreen` | main (nur JDK) |
| GUI Components | `ui/*` (Widgets), `ui/Java2DCanvas`, `gl/UiLayer` | main / render |
| Settings Management | `settings/SettingsManager`, `Settings`, `Setting`, `SettingsMigration` | main |
| Input Management | `input/InputManager`, `GameAction`, `InputCode`, `KeyBindings`, `InputSource`; `gl/GlfwInputSource` | main / render |
| Network Connection Management | `app/ConnectionManager`, `ConnectErrors`, `ServerPinger`, `ServerList`, `ServerEntry`, `ProtocolVersions` | main |
| Game Rendering | `gl/GameScene` (je Verbindung), `gl/ClientResources` (Anwendungslebensdauer) | render |

- **Menülogik nicht in GameView:** Die bisherige Schleife ist in `GameScene` verschoben; `GameView` hält nur noch die gemeinsamen Zustände für den Autotest.
- **Ohne LWJGL testbar:** Menüs, Einstellungen, Eingabe und Verbindungsmodell liegen im `main`-Teil (nur JDK inklusive AWT für Java2D). Der Terminalclient bleibt ohne LWJGL.
- **Darstellung:** Menüs werden mit Java2D (eigene Gestaltung, Systemschrift, keine Mojang-Grafiken) in ein Bild gezeichnet und nur bei Änderungen als Textur hochgeladen.
- **Direktmodus unverändert:** `--autotest`, `--direct` und `--login-ui` verbinden wie bisher sofort, ohne Hauptmenü und ohne Nutzereinstellungen. Alle Regressionsskripte laufen in diesem Modus.

## 3. Neue GUI-Komponenten

Button, TextLabel, Checkbox, Slider, Dropdown (gesperrte Einträge „geplant“), TextInput (Filter, Prüfung, Strg+A/C/V/X), ScrollContainer, Tooltip, Dialog, TabNavigation, KeyBindingEditor.

**Gemeinsame Mechanismen:**
- logische GUI-Einheiten mit GUI-Skalierung nach der Vanilla-Regel (automatisch: größte Stufe mit mindestens 320 × 240 Einheiten)
- Fokusreihenfolge, Hover-, Fokus- und Aktivzustände
- Popups, modale Dialoge, Tooltips mit Verzögerung
- Überlaufzähler für gekürzte Texte

**Screens:** Hauptmenü, Einzelspieler/lokale Testwelt, Mehrspieler, Servereditor, Einstellungen (Grafik, Steuerung, Tastenbelegung, Oberfläche), Konto, Über, Verbinden, Pause, Verbindung getrennt. Inventar/Container und Todesbildschirm bleiben die bewährten Spielklassen und sind über `GameScreen` im ScreenManager eingehängt.

## 4. Einstellungen und Dateiformat (Kurzfassung statt docs/60)

**Datei:** `~/.minecraft-merlin/settings.properties` (UTF-8, Properties, `settings.version=2`), außerhalb des Repositorys. Serverliste: `servers.properties` im selben Ordner.

**Umgesetzte Einstellungen, alle wirken sofort:**
- **Grafik:** Anzeigemodus (Fenster/Vollbild/randlos), Vollbildauflösung, VSync, max. Bildrate, Sichtweite (dem Server per `client_information` mitgeteilt), Sichtfeld, Helligkeit, Partikel (Vanilla-Stufen), GUI-Skalierung, Wolken, Biomübergang, weiche Beleuchtung
- **Steuerung:** Mausempfindlichkeit (Vanilla-Formel, 100 % = bisherige 0,15°/px), Maus invertieren, Sprinten und Schleichen als Umschalter, Tastenbelegung
- **Oberfläche:** HUD, Debug-Anzeige, Tooltips; Barrierefreiheit: kontrastreiche Menüs, Bewegung reduzieren
- **Konto:** Offline-Spielername

**Als „geplant“ gesperrt:** Wolken „Schnell“, Perspektive (F5), Controller (M17), Ton, Sprache, Microsoft-Konto.

**Robustheit, geprüft in Unit- und GUI-Tests:**
- fehlende Datei → Standardwerte
- ungültige Werte → Standard mit Hinweis
- beschädigte Datei → wird als `.broken-<Zeit>` gesichert, der Client startet mit Standardwerten (GUI-Durchgang 3)
- veraltetes Format 1 → Migration
- neuere Version und unbekannte Schlüssel bleiben erhalten
- Speichern atomar; schreibgeschützt oder fehlgeschlagen → Meldung ohne Abbruch
- Zurücksetzen mit Rückfrage

## 5. Merlin Input System, Vorbereitung input4j

- **Kette:** Gerät (`InputSource`) → Belegung (`KeyBindings`, `InputCode`) → `GameAction` → Spielmechanik.
- **Aktionen:** Bewegung, Umsehen (analog), Springen, Schleichen, Sprinten, Angreifen, Benutzen, Inventar, Fallenlassen, Zweithand, Schnellzugriff 1–9 sowie nächster und vorheriger Platz, HUD, Bildschirmfoto, Debug, Perspektive (geplant), Vollbild, Pause und die Menüaktionen GUI_*.
- **Bereiche:**
  - Spielwelt ohne Screen: alle Aktionen
  - Screen über der Welt: Spielaktionen losgelassen, Umschalter gelöscht
  - Hauptmenü: nur Menü- und globale Aktionen
- **Konflikte:** werden erkannt und rot markiert; Esc und Achsen sind nicht belegbar.
- **Weitere Quellen:** über `InputManager.register` anschließbar; ein Test zeigt eine zweite Quelle mit Gamepad-Codes. **input4j ist nicht eingebunden.**
- **Physik unverändert:** Die Physik erhält weiterhin dieselbe `PlayerPhysics.Input`; der Autotest-Weg ist unverändert.

## 6. Verbindungsverwaltung und MVS-Vorbereitung (Kurzfassung statt docs/61)

- **Phasen:** Server prüfen (Statusping) → Verbindung → Anmeldung → Konfiguration → Welt laden → verbunden. Sie werden aus dem echten Zustandsautomaten der `ClientSession` gelesen; Abbrechen ist jederzeit möglich.
- **Verständliche Fehler:**
  - Server nicht erreichbar
  - Zeitüberschreitung
  - Verbindung unterbrochen
  - nicht unterstützte Serverversion
  - Server hat abgelehnt
  - Adresse nicht erlaubt
- **Keine Geheimnisse in Meldungen:** Tokens und Schlüssel werden aus Servertexten entfernt (Test).
- **Pausenmenü:** Die Sitzung läuft weiter; Keep-Alive und Tick laufen auf eigenen Threads.
- **Trennen gibt frei:** Chunk-Meshes, Entity- und Partikelzustand werden gelöscht, Sitzungs- und Tick-Thread beendet. Renderer, Atlas und Shader bleiben für die nächste Verbindung.
- **MVS:**
  - Client-Version `0.15.0` getrennt von Minecraft 26.3 und Protokoll 777
  - `ProtocolVersions` meldet: nativ, ab 1.20.2 „mit MVS geplant“, zu alt, zu neu
  - Servereinträge speichern die erkannte Serverversion unabhängig vom Client
  - **keine Protokollübersetzung**
- **Externe Server bleiben gesperrt:** nur localhost und 127.x; Hostnamen werden ohne DNS-Abfrage abgewiesen, auch für den Statusping. Laut Netzwerkmonitor in allen Läufen **0 externe Verbindungen** [G].

## 7. Änderungen an bestehendem Code

- **`ClientSession`:** neue Methode `updateClientInformation`. Sie sendet `client_information` im Spielzustand nur bei einer Änderung; im Direktmodus wird sie nie aufgerufen.
- **`ParticleRenderer`:** Partikelstufe mit eigenem Zufallsgenerator; bei „Alle“ unverändert und ohne zusätzliche Zufallszahlen.
- **`InventoryScreen`:** Tasten 1–9, F und Q laufen über die Belegung.
- **`EntityRenderer`:** neue Methode `resetSession()`.
- **`Window`:** Ereigniswarteschlangen für die Menüs, Anzeigemodi, VSync, Zwischenablage, Mindestgröße 320 × 240 und Test-Einspeisung.

## 8. GUI-Tests mit Fenster und Server (`analysis/tools/m15-ui-run.sh`, Lauf `m15-ui2`)

- **Aufbau:** lokaler Vanilla-Server, Client im Menümodus mit `--ui-test` und `--test-no-grab`, damit die Testmaus nicht die Systemmaus fängt.
- **Eingaben:** Klicks und Tasten laufen über die Ereigniswarteschlangen des Fensters.
- **Durchgang 1, 84 OK:**
  - Start im Hauptmenü
  - Tastatur- und Mausnavigation
  - Einstellungen ändern und speichern
  - Tastenbelegung mit Konflikt
  - Abbrechen bei einem stummen Dienst und Zeitüberschreitung
  - Verbinden, Inventar, Pausenmenü (Bewegung, Blick und Interaktion gesperrt; Δpos 0)
  - Einstellungen im Spiel, Fortsetzen (Spieler bewegt sich)
  - Trennen, Hauptmenü, erneut verbinden
  - 1600 × 900, 1920 × 1080 randlos, 854 × 480, GUI-Skalierung 2 und 4: 0 gekürzte Texte
  - Beenden mit 0 offenen GL-Objekten
- **Durchgang 2, 308 OK:**
  - nach Neustart neu geladene Werte
  - Zurücksetzen
  - 20 Verbindungszyklen mit Inventar
  - 25 schnelle Screenwechsel
  - Leistung
- **Durchgang 3, 7 OK:** Start mit beschädigter Einstellungsdatei.

**Behobene Fehler aus diesen Tests:**
- Esc öffnete und schloss das Pausenmenü im selben Bild.
- Die Serverliste war nach der Rückkehr leer.
- Der Statusping blockierte bei einem stummen Dienst.
- Ein abgebrochener Verbindungsversuch konnte einen neuen stören.

**Leistung** (1280 × 720, RTX 3070):

| Situation | FPS | 1 %-Low | Menübild neu zeichnen |
|---|---|---|---|
| Hauptmenü (Begrenzung 60) | 60,0 | 58,7 | 8,5 ms je Änderung |
| Einstellungen | 60,0 | 59,8 | 7,3 ms |
| Schneller Screenwechsel | 60,0 | 60,0 | bis 14,3 ms |
| Spiel, Sichtweite 6, VSync | 60,0 | 54,7 | – |
| Spiel, VSync aus (Begrenzung 120) | 120,0 | 119,9 | – |
| Spiel, Sichtweite 2 / 8 | 60,0 / 59,9 | 54,6 / 54,5 | – |

- **1 %-Low im Spiel (54–55):** Ursache nicht untersucht [O]; zu vergleichen mit den M14-Leistungsschritten im Direktmodus.
- **Speicher:** Heap 140–360 MB; Projekt-JVMs (Server und Client) höchstens 2,3 GB.

## 9. Offene Punkte und Stand des Abbruchs

Auf Wunsch abgebrochen am 2026-11-22 gegen 22:30.

1. **Test mit echten Betriebssystem-Eingaben** (`m15-realinput.ps1`, `ui-4-real.txt`) nicht bestanden:
   - **Erster Lauf:**
     - Menünavigation per echter Maus (Über, Zurück, lokale Testwelt, Welt betreten) funktionierte.
     - Zwei echte Pfeiltasten kamen falsch an. Ursache im Testwerkzeug: fehlendes Kennzeichen „erweiterte Taste“. Korrigiert, aber nicht erneut geprüft.
     - Nach dem Verbinden verlor das Clientfenster den Vordergrund; die Schutzprüfung brach korrekt ab. Vermutet wird das NVIDIA-Overlay, nicht nachgewiesen.
   - **Zweiter Lauf:** Das Eingabeskript lief nicht an, und der Lauf wurde vom Zeitlimit beendet. Ursache nicht untersucht.
2. **Startbare Testdistribution** (Phase J) nicht begonnen.
3. **Vollständige Regression M1–M14** auf dem M15-Stand nicht ausgeführt.
   - Bisher nur Unit-Tests der neuen Pakete.
   - Die Grafikschleife wurde umgebaut (`GameScene`, `MerlinApp`); alle 42 Schritte müssen wiederholt werden, besonders M5/M11-Physik, M8 mit echten Eingaben (Esc), M12-HUD, M14-Fahrzeuge und Leistung.
4. **Volle JUnit-Suite** auf dem letzten Stand nicht ausgeführt (zuletzt nur `settings.*`, `input.*`, `ui.*`, `app.*`).
5. **Dokumente** docs/59, 60 und 61 nicht erstellt; Kurzfassungen stehen in §2–§6.
6. **Restfunktionen nicht umgesetzt:**
   - Wolken „Schnell“
   - Perspektivwechsel
   - Ton, Sprache
   - Gamepad (M17)
   - Protokollübersetzung (M18)
   - eingebauter Einzelspielerserver (nicht vorgesehen)
7. **Abschlusskriterien:**
   - **Erfüllt und geprüft:** 1–11 und 13 (Ressourcen nach 20 Verbindungen).
   - **Offen:** 12 (Stabilität nur in den GUI-Läufen geprüft) und 14 (Regression).

**Wiederaufnahme:**
1. Volle JUnit-Suite.
2. `bash analysis/tools/m14-regression.sh` schrittweise bei mindestens 6,8 GB freiem Speicher.
3. Test mit echten Eingaben wiederholen (`M15_REALINPUT=1 M15_CLIENT_ARGS="--test-no-grab" bash analysis/tools/m15-ui-run.sh real1 analysis/tools/m15/ui-4-real.txt`).
4. Distribution.
5. docs/59–61.
6. Merge.
