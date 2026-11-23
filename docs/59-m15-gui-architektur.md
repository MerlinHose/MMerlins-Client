# 59 – M15: GUI-Architektur

Stand 2026-11-23, Branch `m15-menu-settings`. Ergänzt docs/24 (grafischer Client) und docs/33 (Inventar). Tests: docs/62.

## 1. Ziel und Abgrenzung

Der grafische Client startet als eigenständige Spielanwendung im Hauptmenü. Menüs, Einstellungen, Eingabe und Verbindungen sind vom Welt-Rendering getrennt.

- **Kein Mojang-Code:** Es werden keine Mojang-GUI-Klassen übernommen. Gestaltung und Schrift sind eigen (System-SansSerif), es werden keine Minecraft-Grafiken für Menüs verwendet.
- **Regressionsläufe unverändert:** Sie laufen im Direktmodus und verhalten sich wie vor M15 (§7).

## 2. Schichten

| Bereich | Klassen | Quellteil |
|---|---|---|
| Anwendungslebenszyklus | `gl.MerlinApp`, `gl.MerlinLauncher` | render (LWJGL) |
| Screen-Verwaltung | `ui.ScreenManager`, `ui.Screen`, `ui.GameScreen` | main (nur JDK) |
| GUI-Komponenten | `ui.*` (Widgets), `ui.UiCanvas`, `ui.Java2DCanvas`, `gl.UiLayer` | main / render |
| Screens | `ui.screen.*` | main |
| Einstellungen | `settings.*` (docs/60) | main |
| Eingabe | `input.*`, `gl.GlfwInputSource` (docs/60) | main / render |
| Verbindungen | `app.*` (docs/61) | main |
| Spielwelt-Rendering | `gl.GameScene`, `gl.ClientResources`, bisherige Renderer | render |

**Grundregel:** Screens und Widgets kennen weder OpenGL noch GLFW noch Sockets. Sie sprechen ausschließlich über die Schnittstelle `ui.screen.AppServices` mit der Anwendung.

Dadurch gilt:
- Der gesamte Menüteil liegt im `main`-Quellteil, der nur das JDK braucht.
- Er ist ohne Fenster testbar (`MenuTest`, docs/62 §2).
- Der Terminalclient bleibt ohne LWJGL lauffähig.

## 3. ScreenManager

**Screen-Stapel**
- `open` (darüber), `replace`, `back` (zurück, Fokus wird wiederhergestellt), `closeAll` (zurück in die Welt), `reset` (einziger Screen), `remove` (ein bestimmter, z. B. das vom Server geschlossene Inventar).
- Listener melden jeden Wechsel des obersten Screens.

**Modale Dialoge und Popups**
- Dialoge (`ui.Dialog`) liegen über dem Stapel; Esc wählt den letzten Knopf (Abbrechen).
- Popups (aufgeklappte Auswahllisten) liegen über allem. Ein Klick daneben schließt sie; Fokus und Pfeiltasten wirken innerhalb der Liste.

**Fokus**
- Tab und Umschalt+Tab sowie Pfeil hoch/runter durchlaufen die fokussierbaren Widgets zyklisch.
- Links/Rechts geht zuerst an das Widget (Regler, Liste, Reiter), sonst wandert der Fokus.
- Eingabe und Leertaste bestätigen, Esc heißt zurück.
- Der Fokusrahmen erscheint nur nach Tastaturbedienung (wie in Vanilla).
- Der Fokus scrollt seinen `ScrollContainer` mit.

**Maus und Erfassung**
- Hover per Trefferprüfung von oben nach unten.
- Ein Klick löst beim Loslassen über demselben Widget aus.
- Ziehen geht an das gedrückte Widget.
- Eine Erfassung leitet alle Eingaben an ein Widget, z. B. an die Tastenbelegung, die auf die nächste Taste wartet.

**Tooltips**
- Erscheinen nach 500 ms über dem Widget, abschaltbar.
- Bleiben vollständig im Fenster.

**GUI-Skalierung**
- Vanilla-Regel: größte Stufe s mit Breite/s ≥ 320 und Höhe/s ≥ 240, höchstens der eingestellte Wert (0 = automatisch). Logische Fläche daher mindestens 320 × 240 Einheiten.
- Ein Framebuffer mit höherer DPI ergibt automatisch eine höhere Stufe (3840 × 2160 → 9).
- Ein geänderter Regler wird erst nach dem Loslassen angewandt.

**Neu zeichnen**
- Gezeichnet wird nur bei `dirty`: nach Eingaben, Zustandsänderungen, Größenänderung, Tooltip-Verzögerung und Animationen nach `Screen.animationHz()`.
- Animationen: Hauptmenü 15 Hz, abgeschaltet bei „Bewegung reduzieren“; Verbinden 4 Hz; Schreibmarke 2 Hz.

**Eingabebesitz**
- `gameHandlesInput()`: Es gibt keinen Screen, oder der oberste ist ein vom Spiel bedienter `GameScreen`.
- `blocksWorldInput()`: Ein Screen ist offen, also keine Bewegung und keine Blockinteraktion.

## 4. Screen-Lebenszyklus

| Methode | Zeitpunkt |
|---|---|
| `init()` | einmal beim ersten Öffnen: Widgets anlegen |
| `layout(w, h)` | bei jedem Öffnen, jeder Größen- oder Skalierungsänderung und bei Inhaltsänderungen; nur Lage, Widgets bleiben |
| `tick()` | je Bild (Statusabfragen, z. B. Ping, Verbindungsphase) |
| `resumed()` | wieder oben nach `back()` |
| `removed()` | endgültig geschlossen: laufende Vorgänge beenden, speichern |
| `onBack()` | Esc/Zurück (Standard: `back()`; Pause: Fortsetzen; Hauptmenü: nichts) |

**Eigenschaften eines Screens**
- `overlaysWorld()`: Die Welt bleibt dahinter sichtbar (Pause, Einstellungen im Spiel).
- `blocksWorldInput()`: Weltsteuerung ist gesperrt.
- `handledByGame()`: Das Spiel zeichnet und bedient den Screen selbst (Inventar, Container, Tod).

**Screens**
- Hauptmenü, Einzelspieler/lokale Testwelt, Mehrspieler, Servereditor, Einstellungen (4 Reiter), Konto, Über, Verbinden, Pause, Verbindung getrennt.
- Inventar/Container (M8) und Todesbildschirm (M10) bleiben die bewährten Spielklassen. `MerlinApp` führt sie als `GameScreen` („inventory“, „death“) im Stapel. Damit gelten Eingabesperre, Escape und Fokus einheitlich, ohne die Spiellogik umzubauen.

## 5. GUI-Komponenten

| Komponente | Bedienung |
|---|---|
| `Button` | Klick (Loslassen über dem Knopf), Bestätigen |
| `TextLabel` | ein- oder mehrzeilig; Überlauf wird gezählt |
| `Checkbox` | Klick, Bestätigen, Links/Rechts |
| `Slider` | Ziehen, Klick, Mausrad, Links/Rechts; Wertformat frei |
| `Dropdown` | Liste über allen Elementen; gesperrte Einträge „(geplant)“ |
| `TextInput` | Zeichenfilter und Prüfung (roter Rahmen); Rücktaste, Entf, Pfeile, Pos1/Ende, Strg+A/C/V/X |
| `ScrollContainer` | Mausrad, Leiste ziehen, Fokus scrollt mit; Ausschnitt per Clip |
| `Tooltip` | verzögert, umgebrochen, im Fenster |
| `Dialog` | modal, Titel, Text, Knöpfe; Esc = letzter Knopf |
| `TabNavigation` | Klick, Links/Rechts |
| `KeyBindingEditor` | Zeilen je Aktion; Erfassung von Taste, Maustaste oder Mausrad; Esc = nicht belegt; Konflikte rot mit Erklärung; Zurücksetzen je Zeile |
| `Container` | Gruppe mit optionalem Panel |

**Gemeinsamer Stil:** `ui.Theme` mit normaler und kontrastreicher Variante. Zustände: normal, Hover (Akzentrahmen), gedrückt, Fokus (gelber Doppelrahmen), gesperrt.

**Abgeschnittene Texte:** `UiCanvas.textIn` kürzt mit „…“ und zählt den Überlauf. Die Tests verlangen 0 Überläufe bei allen geprüften Auflösungen und Skalierungen.

## 6. Darstellung und Ressourcen

- **Zeichnen:** `Java2DCanvas` zeichnet die Menüs in ein ARGB-Bild in Fenstergröße. Koordinaten sind logisch × Skala; Kanten werden auf Pixel gerundet, Text ist geglättet.
- **Messen:** Der ScreenManager misst Text beim Layout mit derselben Fläche. Layout und Darstellung stimmen daher überein.
- **`UiLayer`:**
  - eine Textur in Fenstergröße und ein Bildschirmviereck
  - Hochladen nur nach einer Änderung, ohne Pixelumrechnung (`GL_BGRA` / `GL_UNSIGNED_INT_8_8_8_8_REV` entspricht int-ARGB)
  - Kosten bei 1280 × 720: 7–15 ms je Änderung; ohne Änderung nur ein Draw Call
- **Lebensdauer der Ressourcen:**
  - **Anwendung:** Fenster, Atlas, Modelle, Shader, alle Renderer, Menüebene (`ClientResources`). Geladen einmal beim Start, geschlossen beim Beenden.
  - **Verbindung:** `GameScene` mit Chunk-Meshes, Autotest, Entity-Zustand je Sitzung (`EntityRenderer.resetSession`) und Partikeln. `GameScene.dispose()` gibt sie beim Trennen frei.
  - Nach 20 Verbindungen bleibt der GL-Bestand konstant (93 Objekte im Hauptmenü), beim Beenden sind 0 Objekte offen (docs/62).

## 7. Anwendungsschleife (`MerlinApp`)

Je Bild:
1. Bildzeit erfassen und Größe übernehmen; im Fenstermodus die Fenstergröße in den Einstellungen merken.
2. Verbindungszustand auswerten: Szene anlegen, nach „verbunden“ den Verbindungsbildschirm schließen und die Maus fangen; nach dem Sitzungsende freigeben und „Verbindung getrennt“ oder das Hauptmenü zeigen.
3. Eingabebereich bestimmen (`WORLD`, `WORLD_SCREEN`, `MENU`) und `InputManager.update` aufrufen.
4. Eingabebesitz nach dem Zustand zu Bildbeginn festlegen. Eine Taste, die einen Screen öffnet (Esc → Pause), schließt ihn nicht im selben Bild wieder; das war ein im GUI-Test gefundener Fehler.
5. Globale Aktionen: Pause, F1, F2, F3, F11.
6. Menüereignisse (Maus, Rad, Tasten, Zeichen) an den ScreenManager, wenn die Menüs die Eingabe besitzen.
7. Zeichnen: Welt über `GameScene.frame`, wenn sie sichtbar ist; dann die Menüebene; danach die vom Spiel bedienten Screens abgleichen.
8. Bildratenbegrenzung (Einstellung; im Hauptmenü höchstens 60) und Speichern von Einstellungsänderungen im Hintergrund alle 2 s.

**Direktmodus** (`--autotest`, `--direct`, `--login-ui`):
- sofort verbinden (M13-Identität wie bisher), kein Hauptmenü
- Einstellungen nur im Speicher aus den Startoptionen
- Fenstertitel „Minecraft Research – Graphical Client“, keine Bildratenbegrenzung
- Nach dem Trennen bleibt das Fenster mit der Meldung offen, wie bisher.
- Alle Regressionsskripte nutzen diesen Modus; Esc öffnet aber auch hier das Pausenmenü.

## 8. Test- und Diagnosehilfen

- **`--ui-test <skript>`** (`gl.UiAutotest`):
  - Klicks und Tasten laufen über die Ereigniswarteschlangen des Fensters, also denselben Weg wie echte Eingaben.
  - Prüfungen schreiben `RESULT OK|FAIL`; Positionsabgleich über die Serverkonsole.
  - Ein fehlerhafter Schritt zählt als FAIL und beendet den Client nicht.
- **`--test-no-grab`:** Die Maus gilt spielintern als gefangen, der Systemzeiger bleibt aber frei, damit automatische Tests nicht die Maus des Nutzers festhalten.
- **`log-input`:** Protokolliert jede GLFW-Taste (Code, Scancode, Aktion) und jeden Fokuswechsel; Diagnose für den Test mit echten Eingaben.
