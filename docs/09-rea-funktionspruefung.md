# 09 – REA-MCP: Funktionsprüfung für dieses Projekt

Ziel: feststellen, welche REA-Operationen für die Minecraft-Analyse (Java-Bytecode in JARs) **tatsächlich funktionieren**. Alle Tests wurden real ausgeführt (2026-11-06).

## Ergebnis-Übersicht

| Operation / Provider | Test | Ergebnis | Nutzen hier |
|---|---|---|---|
| `open_binary` auf `.jar` | Minecraft-JARs | ✅ als `zip`/`archive` erkannt | Basis für Inventar |
| `inspect_artifact` | merged-, extracted_server-, common-JAR | ✅ vollständiges Inventar, Integritätsprüfung | Dateibestand, Größen, Existenz von Klassen |
| `inspect_analysis_view` (kind `inventory`) | Pfadfilter auf Klassen | ✅ | gezielte Abfrage einzelner Einträge |
| `compare_artifacts` | Vanilla-Server ↔ Loom-common | ✅ (Ergebnis 585 kB, Auswertung per Node.js) | Gleichheit von Client- und Server-Code belegen |
| `export_evidence_bundle` / `close_binary` | | ✅ (Zielverzeichnis muss existieren) | Nachvollziehbarkeit |
| `inspect_analysis_settings` | | ✅ | zeigt verfügbare Provider |
| `open_binary` auf einzelne `.class` | `Connection.class` | ❌ „unsupported binary format“ | – |
| jadx (`search_android_classes` usw.) | auf JAR | ❌ „provide one standalone APK“ | nur Android-APKs |
| `inspect_managed_*` | – | ❌ nicht anwendbar (.NET/PE-CLI-Metadaten) | – |
| JEB (`inspect_jeb_client`) | | ❌ kein JEB-Client unter `127.0.0.1:8425` | – |
| PCAP (`inspect_pcap_client`) | | ❌ `tshark`/`capinfos` nicht installiert | Paketmitschnitte wären möglich nach Installation von Wireshark 4.x |
| Ghidra/Hopper (native) | nicht getestet | – | nur für native Bibliotheken (z. B. LWJGL/SDL-DLLs) relevant, nicht für Spiellogik |

**Fazit:** Für Minecraft-Java-Code unterstützt REA nur **Archiv-Ebene** (Inventar, Integrität, Vergleich, Extraktion). Code-Ebene (Klassen, Methoden, Bytecode) leisten die **Loom-Quellen** und JDK-Werkzeuge (`javap`). Deshalb ist die Analyse in `docs/01`–`08` quellenbasiert, REA dient der Absicherung.

## Durchgeführte REA-Analysen

### 1. Inventar des Analyse-JARs
Siehe [06-rea-analyse.md](06-rea-analyse.md): `minecraft-merged-…-26.3.jar`, 32 877 Dateien, 0 Integritätswidersprüche, alle 10 zentralen Klassen vorhanden.
Evidence: `analysis/rea/minecraft-26.3-jar-evidence.json`.

### 2. Vanilla-Server vs. Client-gemeinsamer Code

| | linke Seite | rechte Seite |
|---|---|---|
| Datei | `minecraft-extracted_server.jar` (aus Vanilla-`server.jar`) | `minecraft-common.jar` (Loom-Split) |
| SHA-256 | `a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6` | `387e0765e4aa46760ff86a9ca0d5f2edf8ff7f59141bf63428556b2678cc017a` |
| Einträge | 17 656 Dateien, 902 Verzeichnisse | 17 653 Dateien, 902 Verzeichnisse |

`compare_artifacts`-Ergebnis:

| Klasse | Anzahl | Details |
|---|---:|---|
| unverändert | 17 651 | alle Klassen und Ressourcen inkl. komplett `net/minecraft/network/**` (0 Änderungen) |
| entfernt | 3 | `META-INF/LICENSE`, `META-INF/MOJANGCS.RSA`, `META-INF/MOJANGCS.SF` (Mojang-Signatur) |
| geändert (Inhalt) | 1 | `META-INF/MANIFEST.MF` |
| geändert (nur Attribut) | 901 | Verzeichnisse: nur Executable-Flag des ZIP-Eintrags |
| geändert (Container) | 2 | Wurzel `.` und `META-INF` – Folge der geänderten/entfernten Kinder |
| unbekannt / Widerspruch | 0 / 0 | |

→ Loom entfernt nur Signaturdateien; der Code ist bytegleich. Netzwerk-Erkenntnisse aus den Quellen gelten unverändert für den Vanilla-Server.
Evidence: `analysis/rea/vanilla-server-vs-common-evidence.json` (8 Records, 1 unknown).

## Möglichkeiten nach Installation zusätzlicher Werkzeuge

- **Wireshark 4.x** (`tshark`, `capinfos` in PATH oder `REA_TSHARK_COMMAND`/`REA_CAPINFOS_COMMAND`): `inspect_pcap_capture`, um eine echte Verbindung zum lokalen Server mitzuschneiden. Ohne Verschlüsselung (`online-mode=false`) und ohne Kompression sind Paket-IDs und Felder direkt lesbar. Die Minecraft-Protokoll-Dissektion in tshark ist allerdings nicht garantiert, Rohbytes wären aber verfügbar.
- **JEB** (eigener MCP-Client): wäre für Java-Bytecode geeignet, ist aber kommerziell; für dieses Projekt dank unobfuskierter Loom-Quellen nicht notwendig.
