# 06 – Ergänzende REA- und Bytecode-Analyse

Ziel: Die Aussagen aus den dekompilierten Loom-Quellen gegen das tatsächlich ausgeführte Artefakt absichern.

## Artefakt

| | |
|---|---|
| Datei | `research/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-1b0498e74c/26.3/minecraft-merged-1b0498e74c-26.3.jar` (Client+Server gemergt, von Loom erzeugt) |
| SHA-256 | `033be6d72db3f8b6400e46b9e6a35f94a4b9bac1359b15b98c58dc49325fb8da` |
| Größe | 39 380 626 B |

## REA – Archivinventar (`open_binary` → `inspect_artifact`)

- Format ZIP, Inventar **vollständig** (`source_coverage: complete`), **0 Integritätswidersprüche**.
- 32 877 Dateien, 1 349 Verzeichnisse.
- Evidence-ID der Inspektion: `ev_5835ed23f6b27855eef834786d38f64b8d20efb3f40e33b0a0554f2756071a1b`
- Evidence-Bundle exportiert: `analysis/rea/minecraft-26.3-jar-evidence.json` (≈ 78 MB, 3 Records).

### Existenzprüfung der in den Docs zentralen Klassen

| Eintrag | Größe (B) |
|---|---:|
| `net/minecraft/client/Minecraft.class` | 150 863 |
| `net/minecraft/client/main/Main.class` | 21 825 |
| `net/minecraft/client/renderer/extract/LevelExtractor.class` | 43 218 |
| `com/mojang/blaze3d/platform/SDLEventHandler.class` | 10 225 |
| `com/mojang/renderpearl/backend/opengl/GlBackend.class` | 3 804 |
| `com/mojang/renderpearl/backend/vulkan/VulkanBackend.class` | 23 578 |
| `net/minecraft/network/Connection.class` | 33 692 |
| `net/minecraft/network/PacketProcessor.class` | 2 355 |
| `net/minecraft/network/protocol/game/GameProtocols.class` | 34 606 |
| `version.json` | 422 |

Ergebnis: Alle 10 abgefragten Einträge sind vorhanden → SDL-Plattformschicht, RenderPearl mit beiden Backends und die Extract-Architektur sind im ausgelieferten Bytecode enthalten, nicht nur Artefakte der Dekompilierung.

## Bytecode-Gegenprüfung (`javap`)

```
public static int getProtocolVersion();
  Code:
     0: sipush 777
     3: ireturn
```
`version.json` im JAR: `"protocol_version": 777`, `"world_version": 5023`, `"java_version": 25`, `"stable": true`, `build_time 2026-09-15T11:20:48+00:00`.

## Konsistenzprüfung Paket-Tabellen

Die Zahl der `addPacket`/`withBundlePacket`-Aufrufe je `*Protocols.java` (grep) stimmt mit den vom Extraktor erzeugten Tabellen überein:
Handshake 1, Status 4, Login 11, Configuration 31, Play 213 → **260**.

## Grenzen

- Die verfügbaren REA-„managed“-Werkzeuge (`inspect_managed_artifact`, `inspect_managed_members`) zielen auf .NET/PE-CLI-Metadaten und sind für Java-Bytecode **nicht anwendbar**; auch jadx (nur APK) und `.class`-Dateien werden nicht unterstützt. Vollständige Prüfung: [09-rea-funktionspruefung.md](09-rea-funktionspruefung.md). REA wurde daher nur für Inventar/Integrität/Existenzprüfung/Vergleich genutzt; Bytecode-Details wurden mit `javap` geprüft.
- Laufzeitverhalten (z. B. reale Paketmitschnitte) wurde noch nicht erhoben. Möglicher nächster Schritt: Mitschnitt einer Verbindung zu einem lokalen Offline-Server (`server/`) mit REA `inspect_pcap_capture` zur Validierung der Phasenabläufe aus [05](05-netzwerk.md).
