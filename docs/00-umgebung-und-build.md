# 00 – Umgebung und Build-Verifikation

Stand: 2026-11-06

## Projektlayout

```
MinecraftResearch/
├── research/        Fabric-Mod-Projekt (Gradle, Loom) – Analyse-/Laufzeitumgebung
│   ├── build.gradle.kts, gradle.properties, settings.gradle.kts
│   ├── src/main/java/dev/mcresearch/MinecraftResearch.java   (ModInitializer, loggt "Hello Fabric world!")
│   ├── src/main/java/dev/mcresearch/mixin/ExampleMixin.java  (leerer @Inject in MinecraftServer#loadLevel)
│   └── run/         Laufzeitverzeichnis von runClient
├── analysis/
│   ├── mc-src/      entpackte Loom-Quellen (Lesekopie)
│   ├── tools/       eigene Analysewerkzeuge
│   └── rea/         REA-Evidence
├── docs/            diese Dokumentation
└── server/          leer – vorgesehen für einen lokalen Vanilla-Server (siehe 08)
```

### Von Loom erzeugte Minecraft-Artefakte

| Datei | Bedeutung |
|---|---|
| `~/.gradle/caches/fabric-loom/26.3/minecraft-client.jar` | Original-Client-JAR von Mojang |
| `~/.gradle/caches/fabric-loom/26.3/minecraft-server.jar` | Original-Vanilla-`server.jar` (Bundler) |
| `~/.gradle/caches/fabric-loom/26.3/minecraft-extracted_server.jar` | aus dem Bundler extrahierter Server |
| `~/.gradle/caches/fabric-loom/26.3/minecraft-common.jar` / `minecraft-client-only.jar` | Loom-Split: gemeinsamer bzw. nur-Client-Code |
| `research/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-1b0498e74c/26.3/…-26.3.jar` | gemergtes Laufzeit-JAR (Client+Server), auf dem `runClient` läuft |
| `…/minecraft-merged-1b0498e74c-26.3-sources.jar` | **generierte Quellen** (Analysegrundlage), entpackt in `analysis/mc-src/` |

## Versionen

| Komponente | Version | Quelle |
|---|---|---|
| Minecraft | 26.3 (stable, Build 2026-09-15) | `gradle.properties`, `version.json` im JAR |
| Netzwerkprotokoll | 777 | `SharedConstants#getProtocolVersion` (`net/minecraft/SharedConstants.java:207`) |
| World-Version | 5023 | `SharedConstants.WORLD_VERSION` |
| Resource-Pack-Format | 97.1 | `version.json` |
| Fabric Loader | 0.20.1 | `gradle.properties` |
| Fabric Loom | 1.19.3 (`1.19-SNAPSHOT`) | Build-Ausgabe |
| Fabric API | 0.165.2+26.3 | `gradle.properties` |
| Gradle | 9.8.2 | `gradle/wrapper/gradle-wrapper.properties` |
| Java | 25 (Microsoft OpenJDK 25.0.4.1), `options.release = 25` | `java -version`, `build.gradle.kts` |
| LWJGL | 3.4.3-snapshot | Client-Log |

## Verifikation

### `gradlew build`

- Erster Lauf: `BUILD SUCCESSFUL` (alle Tasks up-to-date, Konfigurations-Cache gespeichert).
- Erzwungener Neubau `gradlew compileJava jar --rerun-tasks`: `BUILD SUCCESSFUL in 4s`.
- Artefakte: `research/build/libs/mc-research-1.0.0.jar`, `mc-research-1.0.0-sources.jar`.
- Die Minecraft-Quellen waren bereits durch Loom generiert (`genSources` nicht erneut nötig).

### `gradlew runClient`

Client startete fehlerfrei bis zum Titelbildschirm (Log ohne `WARN`/`ERROR`). Relevante Logzeilen:

```
Loading Minecraft 26.3 with Fabric Loader 0.20.1
Loading 52 mods: ... (fabric-api-Module, mc-research)
[Render thread] (mc-research) Hello Fabric world!
[Render thread] Backend library: LWJGL version 3.4.3-snapshot
[Render thread] Using graphics backend OpenGL, using drivers: 3.3.0 NVIDIA 621.01
[Render thread] Using graphics device extensions: GL_ARB_multi_draw_indirect, GL_ARB_shader_draw_parameters, ...
[Render thread] Sound engine started
[Render thread] Created: 2048x2048x4 minecraft:textures/atlas/blocks.png-atlas
```

- Start bis Atlas-Erstellung ≈ 12 s.
- Grafik-Backend: **OpenGL** (Default-Einstellung → OpenGL zuerst, siehe [03-rendering.md](03-rendering.md)).
- Der Client wurde danach per Prozess-Kill beendet; das abschließende `BUILD FAILED … non-zero exit value -1` im Gradle-Log ist **ausschließlich Folge dieses Kills**, kein Build- oder Laufzeitfehler.

### Hinweis

Das Template-Mixin `ExampleMixin` greift auf `MinecraftServer#loadLevel` zu und ist für die Analyse irrelevant; es wurde nicht verändert.
