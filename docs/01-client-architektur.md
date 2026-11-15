# 01 – Clientarchitektur

## Startsequenz

Einstieg: `net.minecraft.client.main.Main#main` (`net/minecraft/client/main/Main.java:58`). Unter Fabric startet Knot (Fabric Loader) diese Klasse.

| Schritt | Referenz |
|---|---|
| Native Bibliotheken laden | `NativeLibrariesBootstrap.loadLibraries()` (`Main.java:129`) |
| Optional Tracy-Profiler | `TracyBootstrap.setup()` (`Main.java:149`) |
| Registries/Bootstrap | `Bootstrap.bootStrap()`, `ClientBootstrap.bootstrap()`, `Bootstrap.validate()` (`Main.java:161-164`) |
| `GameConfig` aus CLI-Argumenten (User, Ordner, QuickPlay, …) | `Main.java:217` |
| Shutdown-Hook „Client Shutdown Thread“ | `Main.java:246-259` |
| Hauptthread wird **„Render thread“** | `Main.java:263` |
| `RenderSystem.initRenderThread()` | `Main.java:264` |
| `new Minecraft(gameConfig)` | `Main.java:265` |
| `minecraft.run()` – Game Loop | `Main.java:279` |

## `net.minecraft.client.Minecraft` – zentrales Singleton

`Minecraft` (`net/minecraft/client/Minecraft.java`, 2 981 Zeilen) ist God-Object und Event-Loop zugleich
(erbt von `ReentrantBlockableEventLoop<Runnable>`; `Minecraft#execute` stellt Tasks in die Queue des Render-Threads,
abgearbeitet in `runAllTasks()` innerhalb von `runTick`). Zugriff: `Minecraft#getInstance` (`:2544`).

### Konstruktor (`Minecraft.java:394`) – Initialisierungsreihenfolge

1. Ordner, Pack-Repository (`PackRepository`, `ClientPackSource`, `DownloadedPackSource`), Services/Auth (`Services.create`, `UserApiService`).
2. `Options` (liest `options.txt`), `HotbarManager`, `Tutorial`.
3. **Plattform/GPU:** `RenderSystem.initBackendSystem()` → SDL3-Init (`com/mojang/blaze3d/systems/RenderSystem.java:216`, `SDL_Init(0x20 = VIDEO)`).
   Backend-Auswahl-Schleife über `PreferredGraphicsApi#getBackendsToTry` → `GpuBackend#createDevice` (`Minecraft.java:495`),
   `RenderSystem.initRenderer(device)` (`:522`), `new Window(...)` (`:529`), `device.createSurface(window.handle(), …)`,
   `new SDLEventHandler(this, window)` (`:547`), `TextInputManager`.
4. **Eingabe:** `MouseHandler`, `KeyboardHandler` (`:562f`).
5. **Ressourcen:** `ReloadableResourceManager`, `LanguageManager`, `TextureManager`, `ShaderManager`, `SkinManager`, `LevelStorageSource`, `MusicManager`, `SoundManager`, `AtlasManager`, `FontManager`, `ModelManager`, `ItemModelResolver`, `MapRenderer`.
6. **Rendering:** `EntityRenderDispatcher`, `BlockEntityRenderDispatcher`, `ParticleEngine`, `GameRenderer` (`:644`), `LevelRenderer` (`:649`), `LevelExtractor` (`:660`).
7. **UI:** `Gui` mit `Hud` (`:666`) – `Gui` verwaltet jetzt auch `screen()`/`overlay()` (früher Felder in `Minecraft`).
8. Realms, Telemetrie, Narrator.
9. Initialer Ressourcen-Reload mit `LoadingOverlay` (`:719`).
10. `PacketProcessor` für den Render-Thread (`:736`), erstes `sdlEventHandler.pumpEvents()`.

### Wichtige Felder / Subsysteme

| Feld | Typ | Aufgabe |
|---|---|---|
| `window`, `windowSurface` | `Window`, `GpuSurface` | SDL-Fenster, Swapchain |
| `deltaTracker` | `DeltaTracker.Timer` (20 TPS) | Tick-Timing (`Minecraft.java:291`) |
| `gameRenderer` | `GameRenderer` | Frame-Orchestrierung, Kamera, Post-Effekte, GUI-Rendering |
| `levelRenderer` / `levelExtractor` | `LevelRenderer` / `LevelExtractor` | Welt-Rendering / Zustandsextraktion |
| `gui` | `Gui` (+ `Hud`) | Screens, Overlays, HUD |
| `level` | `ClientLevel` | Clientseitige Welt |
| `player` | `LocalPlayer` | Lokaler Spieler |
| `gameMode` | `MultiPlayerGameMode` | Interaktionen (Abbauen, Benutzen) → Pakete |
| `pendingConnection` / `getConnection()` | `Connection` / `ClientPacketListener` | Netzwerk |
| `singleplayerServer` | `IntegratedServer` | Integrierter Server im Einzelspieler |
| `packetProcessor` | `PacketProcessor` | Warteschlange für Pakete aus Netty-Threads |
| `soundManager`, `musicManager` | | OpenAL-Audio |

## Threads

| Thread | Ursprung | Aufgabe |
|---|---|---|
| **Render thread** | `Main.java:263` | Game Loop, Ticks, Rendering, Paketverarbeitung, Eingabe |
| Netty `… IO #n` | `EventLoopGroupHolder#createThreadFactory` (`net/minecraft/server/network/EventLoopGroupHolder.java:81`) | Socket-I/O, Dekodierung |
| Worker-Pool (`Util.backgroundExecutor()`) | `Util` | Ressourcen-Reload (`"resourceLoad"`), Chunk-Mesh-Kompilierung (`SectionRenderDispatcher`, `TracingExecutor`) |
| Server thread | `IntegratedServer` | nur im Einzelspieler; Kommunikation über In-Memory-`Connection` (`Connection#connectToLocalServer`) |
| Client Shutdown Thread | `Main.java:246` | Shutdown-Hook |

## Paketstruktur (Client-relevant)

| Paket | Inhalt |
|---|---|
| `net.minecraft.client` | `Minecraft`, `Options`, `KeyMapping`, `KeyboardHandler`, `MouseHandler`, `DeltaTracker`, `Camera` |
| `net.minecraft.client.main` | `Main`, `GameConfig` |
| `net.minecraft.client.renderer` | `GameRenderer`, `LevelRenderer`, `RenderPipelines`, Unterpakete `chunk`, `feature`, `extract`, `state`, `entity`, `blockentity`, `oit`, `fog`, … |
| `net.minecraft.client.gui` | `Gui`, Screens, `gui/render/GuiRenderer` |
| `net.minecraft.client.multiplayer` | `ClientPacketListener`, `ClientHandshakePacketListenerImpl`, `ClientConfigurationPacketListenerImpl`, `ClientLevel`, `MultiPlayerGameMode` |
| `net.minecraft.client.player` | `LocalPlayer`, `KeyboardInput`, `ClientInput` |
| `com.mojang.blaze3d.*` | Plattform (SDL-Fenster, Events, Input-Konstanten), `RenderSystem`, Vertex-/Pipeline-Hilfen, Frame Graph, Audio |
| `com.mojang.renderpearl.*` | **GPU-API** (`api`), Backends (`backend/opengl`, `backend/vulkan`), Shader-Frontend |
| `net.minecraft.network.*` | `Connection`, Codecs, Framing, Protokolle |

## Architekturmuster für den Nachbau

- **Ein Haupt-Thread** für alles Spiellogische; andere Threads liefern nur über Queues zu (`Minecraft#execute`, `PacketProcessor`).
- **Simulation (Tick) und Darstellung (Frame) sind entkoppelt**; Darstellung interpoliert.
- **Render-State-Objekte** (`net.minecraft.client.renderer.state.*`) bilden die Schnittstelle zwischen Spiellogik und Renderer.
- **Plattform-/GPU-Code ist hinter Schnittstellen gekapselt** (`GpuDevice`, `GpuSurface`, `CommandEncoder`, `RenderPass`).
