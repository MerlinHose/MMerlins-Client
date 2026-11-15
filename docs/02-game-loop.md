# 02 – Game Loop

## Übersicht

```
Minecraft#run                                   Minecraft.java:889
└─ while (running)
   ├─ RenderSystem.pollEvents(sdlEventHandler)  SDL-Events → Minecraft#execute-Tasks
   └─ runTick(advanceGameTime)                  Minecraft.java:1148
      ├─ window.shouldClose() → stop()
      ├─ ggf. ausstehenden Resource-Reload starten
      ├─ ticksToDo = deltaTracker.advanceGameTime(now)
      ├─ packetProcessor.processQueuedPackets()        ← Netzwerkpakete
      ├─ runAllTasks()                                 ← Eingabe-Events, sonstige Tasks
      ├─ textureManager.tick()  (falls ticksToDo > 0)
      ├─ for i < min(10, ticksToDo): tick()            ← 20-TPS-Simulation
      ├─ soundManager.updateSource(camera)
      ├─ mouseHandler.handleAccumulatedMovement()      ← Kameradrehung pro Frame
      ├─ renderFrame(advanceGameTime)                  Minecraft.java:1227
      └─ Pause-Status aktualisieren → deltaTracker.updatePauseState/updateFrozenState
```

Fehlerbehandlung: `OutOfMemoryError` → einmalige Recovery (`emergencySave`, `OutOfMemoryScreen`); sonstige Exceptions → `emergencySaveAndCrash` mit `CrashReport` (`Minecraft.java:914-937`).
Der Render-Thread erhält bei > 4 Kernen Priorität 10 (`:891`).

## Tick-Timing – `DeltaTracker.Timer`

`net/minecraft/client/DeltaTracker.java`, instanziiert als `new DeltaTracker.Timer(20.0F, 0L, this::getTickTargetMillis)` (`Minecraft.java:291`).

- `advanceGameTime(ms)`: `deltaTicks = (ms - lastMs) / targetMspt(50 ms)`; Residuum akkumuliert, ganzzahliger Teil = Anzahl Ticks.
  `targetMsptProvider` erlaubt serverseitig geänderte Tickrate (`/tick rate`, `TickRateManager`).
- `getGameTimeDeltaPartialTick(ignoreFrozen)`: Interpolationsfaktor 0..1 für Rendering; bei Pause wird der Residualwert eingefroren, bei „frozen“ Spiel 1.0.
- `advanceRealTime(ms)`: Echtzeit-Delta für UI-Animationen; `getRealtimeDeltaTicks` kappt Sprünge > 7 Ticks auf 0.5.
- Maximal **10 Ticks pro Frame** werden nachgeholt (Catch-up-Limit, `Minecraft.java:1176`).

## `Minecraft#tick` (`Minecraft.java:1764`) – ein Simulationsschritt

Reihenfolge:

1. `level.tickRateManager().tick()`; `rightClickDelay--`
2. `gameMode.tick()` (Abbaufortschritt usw.)
3. `pick(1.0F)` – Raycast für `hitResult`
4. `gui.tick()`; falls kein Screen/Overlay offen: **`handleKeybinds()`** (`:1874`)
5. `gameRenderer.tick()`, `level.tickEntities()`, `level.tickBlockEntities()`
6. **`player.sendChanges()`** – Bewegungs-/Input-Pakete an Server (`LocalPlayer.java:265`)
7. `musicManager.tick()`, `soundManager.tick(pause)`
8. `level.tick(...)`, `level.animateTick(...)`, `particleEngine.tick()`
9. **`ServerboundClientTickEndPacket`** an den Server (markiert Tick-Ende)
10. ohne Welt: `pendingConnection.tick()` (Login-/Configuration-Phase)
11. `keyboardHandler.tick()`

## `Minecraft#renderFrame` (`Minecraft.java:1227`) – ein Frame

1. Fenster/Fullscreen aktualisieren; Swapchain bei Bedarf rekonfigurieren (`GpuSurface.Configuration`, VSync-Modus) und **`windowSurface.acquireNextTexture()`**.
2. `deltaTracker.advanceRealTime`, optional GPU-Timer-Query.
3. `gui.update()`, `level.update()`, `gameRenderer.update(deltaTracker)` (Kamera, angeforderte Post-Effekte).
4. `pick(partialTick)` (`:1284`).
5. **Extract-Phase:** `gameRenderer.extract(deltaTracker, advanceGameTime)` (`:1287`) – füllt `GameRenderState`.
6. `RenderSystem.executePendingTasks()`, **Render-Phase:** `gameRenderer.render()` (`:1294`).
7. Blit der Main-Render-Target-Farbtextur in die Swapchain (`windowSurface.blitFromTexture`).
8. `CommandEncoder#submit`, **`windowSurface.present()`** (`:1322`).
9. `RenderSystem.getDynamicUniforms().reset()`, `levelRenderer.endFrame()`.
10. **Frame-Limiter:** `FramerateLimiter.limitDisplayFPS(limit)` falls Limit < 260 (`:1335`).
11. FPS-Zählung (`fps` pro Sekunde).

## Frame-Limiter

`net/minecraft/client/FramerateLimiter.java#limitDisplayFPS`: hybrides Warten – `LockSupport.parkNanos` bis ca. 0,5 ms + durchschnittlicher Overshoot vor Ziel, danach `Thread.onSpinWait()`-Busy-Wait. Overshoot wird exponentiell geglättet (α = 0,1, max. 2 ms).
`FramerateLimitTracker#getFramerateLimit` (`com/mojang/blaze3d/platform/FramerateLimitTracker.java:29`) drosselt je nach `FramerateThrottleReason`:
minimiertes Fenster → 10 FPS, langes AFK → 10, kurzes AFK → min(Limit, 30), Menü ohne Welt → 60, sonst Optionswert.

## Folgerungen für den Nachbau

- Fester 50-ms-Tick mit Akkumulator, Catch-up-Limit 10, Interpolation im Rendering.
- Netzwerkpakete werden **einmal pro Frame vor den Ticks** verarbeitet; Bewegungspakete am **Ende jedes Ticks** gesendet, gefolgt von `ClientTickEnd`.
- Mausbewegung wird **pro Frame** (nicht pro Tick) auf die Spielerrotation angewendet.
