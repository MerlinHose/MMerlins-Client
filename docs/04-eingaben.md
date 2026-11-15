# 04 – Eingaben

## Plattform: SDL3 statt GLFW

26.3 nutzt LWJGL-SDL (SDL3). Initialisierung in `RenderSystem#initBackendSystem` (`com/mojang/blaze3d/systems/RenderSystem.java:205-221`):
App-Metadaten, Hints (`SDL_NO_SIGNAL_HANDLERS`, `SDL_VIDEO_MINIMIZE_ON_FOCUS_LOSS=0`, `SDL_MOUSE_FOCUS_CLICKTHROUGH=1`, `SDL_IME_IMPLEMENTED_UI=composition`, …), `SDL_Init(SDL_INIT_VIDEO)`; Zeitquelle wird `SDL_GetTicksNS`.
Tastencodes in `com/mojang/blaze3d/platform/InputConstants.java` sind daher **SDL-Keycodes/Scancodes** (z. B. Pfeiltasten-Keycodes `0x4000004F…52` in `KeyboardHandler.java:461-466`; Default-Bindings nutzen SDL-Scancodes, etwa `keyShift` = 225 = `SDL_SCANCODE_LSHIFT`, `keySprint` = 224 = `SDL_SCANCODE_LCTRL`, `Options.java:650-651`).

## Ereignisfluss

```
SDL-Eventqueue
  └─ RenderSystem.pollEvents(handler)               jede Loop-Iteration (Minecraft.java:908)
     └─ SDLEventHandler#pollEvents                  com/mojang/blaze3d/platform/SDLEventHandler.java:37
        ├─ KEY_DOWN/UP (0x300/0x301)  → handleKeyEvent       → keyboardHandler.keyPress(handle, action, KeyEvent)
        ├─ TEXT_EDITING (0x302)       → handleTextEditingEvent → keyboardHandler.textEditing (IME-Preedit)
        ├─ TEXT_INPUT (0x303)         → handleTextInputEvent   → keyboardHandler.textInput
        ├─ KEYMAP_CHANGED (0x304)     → Labels im KeyBindsScreen aktualisieren
        ├─ MOUSE_MOTION (0x400)       → mouseHandler.onMove(x, y, xrel, yrel)
        ├─ MOUSE_BUTTON_DOWN/UP (0x401/0x402) → mouseHandler.onButton
        ├─ MOUSE_WHEEL (0x403)        → mouseHandler.onScroll (Shift-Inversion je Plattform)
        ├─ DROP_FILE/BEGIN/COMPLETE   → mouseHandler.onDrop
        └─ sonst                      → Window#handleEvent (Resize, Fokus, Schließen …)
```

Jeder Handler wird **nicht direkt** aufgerufen, sondern via `minecraft.execute(...)` eingereiht und in `runTick → runAllTasks()` auf dem Render-Thread ausgeführt.
Aktion: `1` = press, `0` = release, `-1` = repeat (`SDLEventHandler.java:98-103`).

## Tastatur – `KeyboardHandler#keyPress` (`net/minecraft/client/KeyboardHandler.java:439`)

1. Nur Events des eigenen Fensters; `FramerateLimitTracker#onInputReceived` (AFK-Erkennung).
2. Debug-Crash-Kombination (F3+C) überwachen.
3. Offener Screen → Event an Screen (`keyPressed`), Eingabetyp merken (Tab/Pfeile für Navigation).
4. Escape → `Minecraft#pauseGame`; F3-Kombinationen → `handleDebugKeys` (`:178`).
5. Spielinput: **`KeyMapping.set(key, true)` + `KeyMapping.click(key)`** bzw. `set(key, false)` beim Loslassen (`:566-569`).
6. `tick()` (`:635`) behandelt gehaltene Debug-Crash-Taste.

## Maus – `MouseHandler` (`net/minecraft/client/MouseHandler.java`)

- `onButton` (`:66`): Screen-Klicks (inkl. Doppelklick via `LastClick`) oder `KeyMapping.set/click` für Maustasten (`:143-145`).
- `onMove` (`:231`): bei gegriffener Maus relative Bewegung (`xrel/yrel`) akkumulieren (`accumulatedDX/DY`), sonst Positionsdifferenz.
- `handleAccumulatedMovement` (`:254`) **einmal pro Frame** aus `runTick`: Screen-Drag/Hover oder `turnPlayer`.
- `turnPlayer` (`:321`): Sensitivität `s = opt*0.6+0.2`, `factor = s³ * 8`; Smooth-Camera via `SmoothDouble`; beim Zoomen (Fernrohr) ohne ×8; Invertierung; → `LocalPlayer#turn(dx, dy)`.
- `grabMouse`/`releaseMouse` (`:398/:416`) – Relative Mouse Mode.
- `onScroll` (`:172`): Hotbar-Wechsel bzw. Screen-Scroll (`ScrollWheelHandler`).

## Key-Bindings – `KeyMapping` (`net/minecraft/client/KeyMapping.java`)

- Statische Maps `ALL` (Name → Mapping) und `MAP` (Taste → Liste von Mappings).
- Zustand pro Mapping: `isDown` (gehalten) und `clickCount` (Anzahl Druckvorgänge seit letztem Konsum).
- `click(key)` (`:33`) inkrementiert `clickCount`, `set(key, state)` (`:37`) setzt `isDown`.
- `consumeClick()` (`:116`) dekrementiert – Spiellogik nutzt `while (key.consumeClick())`, damit **kein Druck zwischen zwei Ticks verloren geht**.
- `ToggleKeyMapping` für Umschalt-Modi (Schleichen/Sprinten), `setAll()` beim Zurückkehren ins Spiel.
- Definitionen und Defaults in `Options` (`keyUp`, `keyJump`, `keyAttack`, `keyUse`, `keyHotbarSlots[9]`, …), persistiert in `options.txt`.

## Verarbeitung im Tick – `Minecraft#handleKeybinds` (`Minecraft.java:1874`)

Nur wenn kein Screen/Overlay offen ist (`Minecraft#tick :1789`). Behandelt: Perspektive, Smooth-Camera, Hotbar 1–9, Inventar, Quick-Actions-Dialog, Hand tauschen (`ServerboundPlayerActionPacket.SWAP_ITEM_WITH_OFFHAND`), Drop, Angriff (`startAttack :1618`; sendet zusätzlich **`ServerboundPunchPacket`** (`:1687`), ebenso `continueAttack` (`:1608`) – zur Laufzeit bestätigt, siehe [10](10-laufzeittest.md)), Benutzen (`startUseItem :1693`), Pick-Block, Spectator-Hotbar; gehaltenes Benutzen mit `rightClickDelay`; `continueAttack` (`:1592`) für Dauerabbau.

## Bewegung – `KeyboardInput` → `LocalPlayer` → Pakete

- `KeyboardInput#tick` (`net/minecraft/client/player/KeyboardInput.java`): liest `keyUp/Down/Left/Right/Jump/Shift/Sprint.isDown()` in einen `Input`-Record; `moveVector = normalized(left-right, forward-backward)`.
- `LocalPlayer#aiStep` (`client/player/LocalPlayer.java:779`) wendet Input und Physik an (clientseitige Bewegungsvorhersage).
- `LocalPlayer#sendChanges` (`:265`) am Tickende:
  - `ServerboundPlayerInputPacket` bei geänderten Tasten (`:268`),
  - `sendIsSprintingIfNeeded` → `ServerboundPlayerCommandPacket START/STOP_SPRINTING` (`:334`),
  - **`sendPosition`** (`:295`): `Pos`, `PosRot`, `Rot` oder `StatusOnly` je nachdem ob Position (Δ² > (2·10⁻⁴)² **oder** alle 20 Ticks) bzw. Rotation geändert wurde.
- Danach in `Minecraft#tick`: `ServerboundClientTickEndPacket`.

## Folgerungen für den Nachbau

- SDL3-Eventloop, Events in eine Hauptthread-Queue umleiten.
- „Gehalten + Klickzähler“-Modell für Bindings übernehmen.
- Kamera pro Frame, Bewegung pro Tick; Positionsupdate mit Schwellwert und 20-Tick-Heartbeat.
