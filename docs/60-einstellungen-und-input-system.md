# 60 – M15: Einstellungen und Merlin Input System

Stand 2026-11-23. Tests: `SettingsManagerTest`, `InputSystemTest`, `MenuTest`, GUI-Durchgänge (docs/62).

## 1. Überblick

- **`settings.SettingsManager`:** lädt beim Start, prüft jeden Wert, meldet Änderungen und speichert atomar.
- **`input.InputManager`:** bildet aus Eingabequellen über eine Belegung den Zustand jeder `GameAction`.
- **Die Spiellogik fragt nur Aktionen ab**, nie Tastencodes. Die Physik erhält wie bisher `PlayerPhysics.Input`.

## 2. Einstellungen

Jede Einstellung ist ein `Setting`: Schlüssel, Bezeichnung, Bereich, Standardwert, Prüfung und Hilfetext. Typen: `Bool`, `Int` (min–max), `Num` (min–max), `Choice` (feste Werte), `Text` (Muster).

Die Menüs erzeugen ihre Bedienelemente aus diesen Definitionen. Alle hier aufgeführten Einstellungen sind umgesetzt und wirken sofort; der Listener in `MerlinApp` setzt sie um.

| Schlüssel | Bereich (Standard) | Wirkung |
|---|---|---|
| `graphics.displayMode` | WINDOWED / FULLSCREEN / BORDERLESS (WINDOWED) | `glfwSetWindowMonitor`; F11 schaltet Fenster ↔ Vollbild |
| `graphics.fullscreenResolution` | `auto` oder `BxH@Hz` (auto) | Auflösung im exklusiven Vollbild (Liste aus `glfwGetVideoModes`) |
| `graphics.windowWidth/Height` | 320–7680 × 240–4320 (1280 × 720) | letzte Fenstergröße |
| `graphics.vsync` | an/aus (an) | `glfwSwapInterval` |
| `graphics.maxFps` | 10–260, 260 = unbegrenzt (120) | Bildratenbegrenzung; Hauptmenü ≤ 60 |
| `graphics.renderDistance` | 2–8 (6) | Chunk-Rendering, Nebel; dem Server per `client_information` (Spielzustand) mitgeteilt |
| `graphics.fov` | 30–110 (70) | Projektion |
| `graphics.brightness` | 0–1 (0,5) | Gamma der Lightmap (Vanilla-Optionswert) |
| `graphics.particles` | Alle / Verringert / Minimal (Alle) | Vanilla `ParticleStatus` (verringert: jede dritte entfällt, minimal: nur erzwungene); an den Server gemeldet |
| `graphics.guiScale` | 0 = automatisch, 1–6 (0) | GUI-Skalierung; nach Loslassen des Reglers |
| `graphics.clouds` | Aus / Schön (Schön) | Wolkendarstellung; „Schnell“ ist als geplant gesperrt |
| `graphics.biomeBlend` | 0–7 (2) | Biomfarbmischung; baut die Chunk-Meshes neu |
| `graphics.ambientOcclusion` | an/aus (an) | weiche Beleuchtung; baut die Meshes neu |
| `controls.sensitivity` | 0–1 (0,5) | Vanilla-Formel (s·0,6+0,2)³·8·0,15 °/px; 0,5 = 0,15 °/px wie vor M15 |
| `controls.invertY` | an/aus (aus) | senkrechte Mausbewegung umkehren |
| `controls.toggleSprint` / `toggleSneak` | an/aus (aus) | Taste schaltet um statt gehalten |
| `controls.key.<aktion>` | Eingabecode | Tastenbelegung (§4) |
| `interface.showHud` | an/aus (an) | HUD, Fadenkreuz, Namensschilder (F1 schaltet vorübergehend) |
| `interface.debugOverlay` | an/aus (aus) | F3-Anzeige beim Start |
| `interface.tooltips` | an/aus (an) | Tooltips der Menüs |
| `accessibility.highContrast` | an/aus (aus) | kontrastreiche Menüs |
| `accessibility.reduceMotion` | an/aus (aus) | kein animierter Menühintergrund |
| `account.offlineName` | `[A-Za-z0-9_]{3,16}` (MerlinPlayer) | Spielername im Offline-Testmodus |

**Geplant, als gesperrt sichtbar, nicht gespeichert:** Wolken „Schnell“, Perspektive (F5), Controller (M17), Musik/Geräusche (der Client hat keine Tonausgabe), Sprache, Microsoft-Konto (letzter Meilenstein).

## 3. Datei und Robustheit

**Speicherort**
- `%USERPROFILE%\.minecraft-merlin\settings.properties`, neben der M13-Anmeldekonfiguration, außerhalb des Repositorys.
- Andere Datei: `--settings <datei>`. Serverliste: `servers.properties` im selben Ordner, andere Datei mit `--servers`.

**Format**
- Java-Properties, UTF-8, Kopfkommentar, `settings.version=2`, Schlüssel sortiert.
- Unbekannte Schlüssel (neuere Version, Erweiterungen) bleiben beim Speichern erhalten.

```properties
# Minecraft Merlin Client – Einstellungen (docs/60). …
settings.version=2
controls.invertY=false
controls.key.jump=key:32
controls.key.attack=mouse:0
controls.key.hotbar_next=wheel:down
controls.sensitivity=0.5000
graphics.displayMode=WINDOWED
graphics.renderDistance=6
…
```

**Laden** (wirft nie)

| Fall | Verhalten |
|---|---|
| keine Datei | Standardwerte (`MISSING`); die Datei entsteht beim ersten Speichern |
| unlesbar (Rechte, Verzeichnis) | Standardwerte und Hinweis (`RECOVERED`) |
| beschädigt (kein gültiges UTF-8, Steuerzeichen, kaputte `\u`-Folgen, kein gültiger Schlüssel) | Datei wird zu `settings.properties.broken-<Zeit>` umbenannt, Standardwerte, Hinweis im Einstellungsmenü |
| ungültiger Einzelwert | dieser Wert auf Standard, Hinweis, beim nächsten Speichern korrigiert |
| ungültige/reservierte Taste | Standard für diese Aktion, Hinweis |
| Format 1 (flache Schlüssel, Empfindlichkeit in %) | `SettingsMigration` überführt; Hinweis |
| neuere Formatversion | bekannte Werte übernommen, Hinweis |

**Speichern**
- Atomar: temporäre Datei im Zielordner, dann `ATOMIC_MOVE`.
- Wann: beim Verlassen der Einstellungen, alle 2 s bei Änderungen und beim Beenden.
- Schreibgeschützte Datei oder Fehler: verständliche Meldung (Dialog), die Werte bleiben aktiv, kein Abbruch.

**Zurücksetzen:** „Alle zurücksetzen“ mit Rückfrage; einzelne Tasten über „Zurücksetzen“ je Zeile.

**Regressionsläufe** (Direktmodus) verwenden keine Nutzerdatei, sondern Werte im Speicher aus den Startoptionen. Nutzereinstellungen können Vergleiche deshalb nicht verändern.

## 4. Merlin Input System

```
Eingabegerät (InputSource) → Belegung (KeyBindings: GameAction → InputCode) → GameAction (ActionState) → Spielmechanik
```

**`InputCode`:** Gerätetyp und Code.
- Gerätetypen: `KEYBOARD` (GLFW-Werte), `MOUSE_BUTTON`, `MOUSE_WHEEL` (up/down), `MOUSE_AXIS` (x/y), `GAMEPAD_BUTTON` und `GAMEPAD_AXIS` (vorbereitet).
- Textform in der Datei: `key:87`, `mouse:0`, `wheel:up`, `axis:x`, `pad:3`, `padaxis:1`, `none`.
- Deutsche Anzeigenamen.

**`GameAction`** (Bezeichnung, Gruppe, Kontext, analog/digital, frei belegbar, Standard):

| Gruppe | Aktionen |
|---|---|
| Bewegung | MOVE_FORWARD/BACKWARD/LEFT/RIGHT (WASD), JUMP (Leertaste), SNEAK (Umschalt links), SPRINT (Strg links), LOOK_HORIZONTAL/VERTICAL (Mausachsen, analog, fest) |
| Interaktion | ATTACK (links), USE (rechts) |
| Inventar | INVENTORY (E), DROP_ITEM (Q, Strg = Stapel), SWAP_OFFHAND (F, im Inventar), HOTBAR_1–9 (1–9), HOTBAR_NEXT/PREVIOUS (Mausrad, analog) |
| Sonstiges | TOGGLE_HUD (F1), SCREENSHOT (F2), TOGGLE_DEBUG (F3), TOGGLE_PERSPECTIVE (F5, geplant), TOGGLE_FULLSCREEN (F11), PAUSE (Esc, fest) |
| Menüs | GUI_CONFIRM, GUI_BACK, GUI_UP/DOWN/LEFT/RIGHT, GUI_NEXT (fest) |

**Kontexte**

| Kontext | Gilt |
|---|---|
| `GAME` | nur in der Welt ohne offenen Screen |
| `GLOBAL_GAME` | in der Welt, auch bei offenem Screen (Pause, F1, F3) |
| `GUI` | nur in Menüs |
| `GLOBAL` | immer (F2, F11) |

`InputManager.update(scope)` mit `WORLD`, `WORLD_SCREEN` oder `MENU`:
- **Gesperrte Aktionen** sind losgelassen; Umschaltzustände (Sprint/Schleichen umschalten) werden gelöscht, wie Vanilla `KeyMapping#releaseAll`. GUI-Eingaben und Spielwelteingaben sind damit getrennt.
- **Bewegung:** `movement()` liefert die Bewegungseingabe der Physik.
- **Blick:** `lookDelta()` liefert Grad aus der Mausbewegung, mit Empfindlichkeit und Y-Umkehr.
- **Schnellzugriff:**
  - `hotbarSteps()` folgt `ScrollWheelHandler`: Rad hoch = vorheriger Platz, höchstens 9; Bruchteile eines Touchpads werden aufgerundet.
  - `hotbarSlotPressed()` liefert den gedrückten Platz.
- **`rawPresses()`:** Belegungen unabhängig vom Bereich, für die vom Spiel bedienten Screens; so wertet das Inventar 1–9, F und Q über die Belegung aus, wie Vanilla `AbstractContainerScreen#checkHotbarKeyPressed`.

**Menünavigation**
- Der `ScreenManager` erhält rohe Tastenereignisse (für Textfelder und Tastenerfassung) und übersetzt Navigationstasten in GUI-Aktionen: Esc, Eingabe/Leertaste, Pfeile, Tab.
- Eine Controllerquelle kann später dieselben Aktionen direkt über `ScreenManager.action(GameAction)` liefern.

**Belegung (`KeyBindings`)**
- `set` lehnt ab: nicht belegbare Aktionen (`NOT_REBINDABLE`), Esc (`RESERVED`) und Achsen (`UNSUPPORTED_DEVICE`).
- **Konflikte:**
  - Dieselbe Eingabe für zwei Aktionen mit überlappendem Kontext ist ein Konflikt; Menütasten überschneiden sich nicht mit Spielaktionen.
  - Konflikte werden wie in Vanilla angezeigt (rot, Tooltip nennt die anderen Aktionen), aber nicht verboten.
- **Erfassung im Editor:** Taste, Maustaste oder Mausrad; Esc bedeutet „nicht belegt“ (Vanilla-Verhalten).

**Quellen**
- `InputSource` mit `id`, `handles(device)`, `poll`, `down`, `presses`, `analog`.
- Heute gibt es nur `gl.GlfwInputSource` für Tastatur und Maus. Klicks und Mausbewegung zählen wie vor M15 nur bei gefangener Maus.

**Physik und Interaktion unverändert**
- Die Eingabestruktur der Physik und der Autotest-Weg sind gleich geblieben.
- Die Regressionsschritte mit Physik- und Fahrzeugvergleich bestätigen das (docs/62 §3).

## 5. Vorbereitung input4j (M17, nicht eingebunden)

Geplante Integration, ohne Änderung an Spiellogik oder Menüs:

1. `Input4jSource implements InputSource`:
   - `handles` für `GAMEPAD_BUTTON`/`GAMEPAD_AXIS`
   - `poll()` liest den Controllerzustand einmal je Bild
   - `analog()` liefert Achsen −1…1 mit Totzone
2. **Registrierung** mit `InputManager.register(source)` beim Start, wenn ein Controller gefunden wird; mehrere Quellen werden zusammengeführt (oder-verknüpft, analoge Werte addiert).
3. **Belegung:** eigener Standardsatz für Gamepads (z. B. A = Springen, Trigger = Angreifen/Benutzen, linker Stick = Bewegung, rechter Stick = `LOOK_*`). Die heutige `KeyBindings`-Tabelle wird dafür je Gerätefamilie geführt.
4. **Blick mit dem Stick:** Grad je Tick aus dem Achsenwert statt Pixeln. `lookDelta()` erhält dazu einen zweiten Pfad für `GAMEPAD_AXIS`.
5. **Menüs:** Steuerkreuz und Stick → `GUI_UP/DOWN/LEFT/RIGHT`, A → `GUI_CONFIRM`, B → `GUI_BACK`, jeweils über `ScreenManager.action`.

Dass eine zweite Quelle mit Gamepad-Codes über dieselbe Aktion wirkt, zeigt bereits der Test `InputSystemTest#additionalSourceCanBeRegisteredForFutureControllers`.
