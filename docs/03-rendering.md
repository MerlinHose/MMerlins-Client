# 03 – Rendering

## Schichten

```
GameRenderer / LevelRenderer / GuiRenderer         net.minecraft.client.renderer.*, client.gui.render.*
        │  Render-States, SubmitNodes, Features
RenderSystem, FrameGraphBuilder, RenderTarget      com.mojang.blaze3d.*
        │
RenderPearl-API: GpuDevice, GpuSurface,            com.mojang.renderpearl.api.*
CommandEncoder, RenderPass, RenderPipeline,
GpuBuffer, GpuTexture
        │
Backends: GlBackend/GlDevice … | VulkanBackend/VulkanDevice …   com.mojang.renderpearl.backend.{opengl,vulkan}
        │
LWJGL (OpenGL / Vulkan / SDL3)
```

## RenderPearl – GPU-Abstraktion (neu)

`com.mojang.renderpearl` (146 Quelldateien) ersetzt direkten GL-Zugriff durch eine explizite, Vulkan-artige API.

| Schnittstelle | Datei | Rolle |
|---|---|---|
| `GpuBackend` | `api/device/GpuBackend.java` | Backend-Fabrik, `createDevice(...)` |
| `GpuDevice` | `api/device/GpuDevice.java` | `createSurface`, `createCommandEncoder`, `createTexture(View)`, `createBuffer`, `compilePipeline`, `createTimestampQueryPool`, `getDeviceInfo` (`:28-60`) |
| `GpuSurface` | `api/device/GpuSurface.java` | Swapchain: `configure`, `acquireNextTexture`, `blitFromTexture`, `present`, `PresentMode` |
| `CommandEncoder` | `api/commands/CommandEncoder.java` | Clear-, Copy-, Upload-Befehle, `submit` |
| `RenderPass` | `api/commands/RenderPass.java` | Draw-Aufrufe innerhalb eines Passes |
| `RenderPipeline` | `api/pipeline/RenderPipeline.java` | Unveränderliche Pipeline (Shader, Blend, Depth/Stencil, Vertexformat, BindGroupLayouts) |

Backends: `backend/opengl/` (`GlBackend`, `GlDevice`, `GlCommandEncoder`, `GlRenderPass`, `GlStateManager`, `DirectStateAccess`, …) und `backend/vulkan/` (`VulkanBackend`, `VulkanDevice`, `VulkanCommandEncoder`, …). Shader liegen als GLSL in den Ressourcen vor (93 Einträge unter `assets/minecraft/shaders/` im JAR); `frontend/shaders/GlslCompiler` und `backend/api/SpvModule` (SPIR-V-Modul mit `Reflection`/`Descriptor`) bilden einen GLSL→SPIR-V-Pfad, den `GlPipelineRecompiler` auch für das GL-Backend nutzt (Details noch nicht untersucht).

### Backend-Auswahl

`net/minecraft/client/PreferredGraphicsApi.java`: `DEFAULT`, `OPENGL`, `VULKAN`.
`getBackendsToTry()` liefert für `VULKAN` → `[Vulkan, GL]`, sonst `[GL, Vulkan]` (`:29-31`).
Im Konstruktor (`Minecraft.java:489-520`) werden die Backends der Reihe nach probiert; `BackendCreationException` → nächstes Backend.
Ein per CLI erzwungenes API (`gameConfig.game.forcedGraphicsApi`) hat Vorrang. Im Testlauf: OpenGL 3.3 Core (NVIDIA).
Genutzte GL-Erweiterungen (Log): `ARB_multi_draw_indirect`, `ARB_shader_draw_parameters`, `ARB_base_instance`, `KHR_debug`, `ARB_draw_indirect`, `ARB_clip_control`, `ARB_vertex_attrib_binding`, `ARB_direct_state_access`, `EXT_texture_filter_anisotropic`.

## Frame-Ablauf

### 1. Update – `GameRenderer#update` (`GameRenderer.java:423`)
Kamera (`Camera#update`), Liste angeforderter Post-Effekte.

### 2. Extract – `GameRenderer#extract` (`GameRenderer.java:439`)
Kopiert allen benötigten Zustand in **`GameRenderState`** (`renderer/state/GameRenderState.java`):
`extractWindow`, `extractOptions`, `LightmapRenderStateExtractor#extract`, `extractCamera`,
**`LevelExtractor#extract`** (`renderer/extract/LevelExtractor.java:109`: sichtbare Entities `extractVisibleEntities`, Block-Entities, Abbau-Animation, Block-Outline, Spielerzustand, Frustum) und `Gui#extractRenderState` (GUI-Elemente → `GuiRenderState`).
Ab hier liest das Rendering **nur noch Render-States**, nicht mehr `Level`/`Entity`.

### 3. Render – `GameRenderer#render` (`GameRenderer.java:466`)
1. Resize der Main-Render-Target, Clear Farbe + Tiefe (**Tiefe = 0.0 → Reversed-Z**).
2. `GlobalSettingsUniform#update` (Bildschirmgröße, Spielzeit, partialTick, Kamera).
3. `Lightmap#render`, **`renderLevel()`** (`:631`): Projektionsmatrix inkl. View-Bobbing/Hurt-Bobbing/Nausea, Fog-Puffer, dann `LevelRenderer#render`, danach `render3dHud` (Hand/Item).
4. Screenshot, Entity-Outline-Blit, Post-Effekte (`PostChain`).
5. Tiefe löschen, **`GuiRenderer#render`** (`client/gui/render/GuiRenderer.java`).
6. `renderBuffers.endFrame()`, `resourcePool.endFrame()`.

### 4. Present – siehe [02-game-loop.md](02-game-loop.md)
Blit `mainRenderTarget` → Swapchain, `submit`, `present`.

## `LevelRenderer#render` (`LevelRenderer.java:186`) – Weltdarstellung via Frame Graph

1. `repositionCamera`, Model-View-Stack mit `viewRotationMatrix`.
2. **`submitFeatures`** (`:328`): Entities, Block-Entities, Abbau-Overlay, transiente Blöcke, Outline werden als *Submit Nodes* in `SubmitNodeStorage` gesammelt (`submitEntities :975`, `submitBlockEntities :986`, …).
3. `FeatureRenderDispatcher#prepareFrame` – sortiert/gruppiert die Nodes nach Feature-Renderern (`renderer/feature/*FeatureRenderer`).
4. **Frame Graph** (`com/mojang/blaze3d/framegraph/FrameGraphBuilder`): Ressourcen `main`, ggf. OIT-Targets (`depth_bounds`, `transmittance`, `accumulate`, `cloud_depth`, `terrain_depth`), `entity_outline`.
   Pässe: `clear` → `addSkyPass` (`:364`) → **`addMainPass`** (`:384`; Pass „main“: `executeSolid` (`:507`) → `executeClassicTransparency` **oder** `executeOit` → `executeOutline` (`:692`) → `executeSeeThrough` (`:470`) → ggf. `executeAlwaysOnTop`-Gizmos (`:479`); Wolkentiefe wird bei OIT als eigenes Target geführt) → Entity-Outline-PostChain.
5. `frame.execute(resourceAllocator, …)`.
6. Nach dem Frame: `compileSections` (`:938`), `uploadTerrainBuffersToGpu`, `SectionOcclusionGraph#update`.

### Transparenz
Zwei Pfade: **Improved Transparency / OIT** (`executeOit :543`, Weighted-Blended-artig mit Transmittance/Accumulate-Targets, `executeDepthBoundsCull :628`) oder klassisch sortiert (`executeClassicTransparency :666`). Umschaltung über `GameRenderer#useImprovedTransparency`.

## Terrain / Chunk-Sections

| Klasse | Rolle |
|---|---|
| `renderer/chunk/SectionRenderDispatcher` | Plant Kompilierung (`CompileTask`, `ResortTransparencyTask`) auf Worker-Threads (`TracingExecutor`), Upload `uploadTerrainBuffersToGpu :143` |
| `renderer/chunk/SectionCompiler` | Erzeugt Meshes für 16³-Sections aus `RenderSectionRegion` (Snapshot der Blockdaten) |
| `renderer/chunk/ChunkSectionLayer` | Layer `SOLID`, `CUTOUT`, `TRANSLUCENT` mit je normaler und `*_MULTIDRAW`-Pipeline |
| `renderer/SectionOcclusionGraph`, `chunk/VisGraph` | Sichtbarkeits-Flood-Fill (Cave Culling) |
| `renderer/ViewArea` | Ring der RenderSections um die Kamera |
| `LevelRenderer#prepareChunkRenders(Indirect)` (`:785`, `:871`) | Draw-Listen; **Multi-Draw-Indirect**, falls Hardware unterstützt |

Block-Änderungen markieren Sections schmutzig über `LevelExtractor#setBlockDirty`/`setSectionDirty` (`LevelExtractor.java:525-571`).

## Pipelines & Shader

- `renderer/RenderPipelines.java`: 102 statische `RenderPipeline`-Definitionen (Terrain, Entities, GUI, Partikel, Post …).
- Shader-Quellen und Post-Chains kommen aus den Ressourcen (`assets/minecraft/shaders/…`), verwaltet durch `ShaderManager` (inkl. `preloadUiShader`, Recovery bei Fehlern).

## Folgerungen für den Nachbau

- Saubere Trennung **Simulation → Extract (Snapshot) → Render** ist der zentrale Architekturgedanke und lässt sich unabhängig übernehmen.
- Terrain: Section-basiertes Meshing auf Worker-Threads, drei Layer, Occlusion-Flood-Fill, optional MDI.
- Reversed-Z-Tiefe (Clear 0.0, `ARB_clip_control`).
