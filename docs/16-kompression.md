# 16 – Netzwerkkompression (M3)

Stand: 2026-11-07 · Protokoll 777 / Minecraft 26.3 · Implementierung: `client/` (`net/CompressionCodec`, `net/Connection`, `net/TransportStats`)
Quellen relativ zu `analysis/mc-src/`. Testergebnisse: [17](17-m2-m3-testbericht.md).

## 1. Aktivierung im Login

| Schritt | Vanilla | Quelle |
|---|---|---|
| Server prüft `network-compression-threshold` ≥ 0 (und keine In-Memory-Verbindung) | `ServerLoginPacketListenerImpl#verifyLoginAndFinishConnectionSetup` | `server/network/ServerLoginPacketListenerImpl.java:146` |
| S→C `login_compression` (login, 0x03) mit `threshold` VarInt; **dieses Paket selbst ist unkomprimiert** | Kompression wird erst nach dem Senden aktiviert (`PacketSendListener.thenRun(…setupCompression(threshold, true))`) | `:147-150` |
| Server-Decoder **prüft** eingehende Pakete (`validateDecompressed = true`) | – | `:150` |
| Client aktiviert Kompression beim Empfang, **ohne** Prüfung der Server-Pakete (`validateDecompressed = false`) | `ClientHandshakePacketListenerImpl#handleCompression` | `client/multiplayer/ClientHandshakePacketListenerImpl.java:231` |
| Einbau in die Pipeline: `decompress` nach `splitter`, `compress` nach `prepender`; Threshold < 0 entfernt beide | `Connection#setupCompression` | `network/Connection.java:540` |

Eigener Client: `ClientSession#handle(LoginCompression)` → `Connection#setCompressionThreshold`. Wirkt ab dem nächsten empfangenen und gesendeten Frame. Der Server sendet `login_compression` nur bei Threshold ≥ 0; bei −1 bleibt die Verbindung unkomprimiert.

## 2. Format

```
Frame        = VarInt frameLength (≤ 3 Bytes, ≤ 2 097 151, ≠ 0) · Inhalt
Inhalt       = VarInt dataLength · Daten
dataLength=0 → Daten = Paket (ID + Felder), unkomprimiert
dataLength>0 → Daten = zlib(Paket), entpackt genau dataLength Bytes
```

## 3. Schwellenwert-Semantik

`CompressionEncoder#encode` (`network/CompressionEncoder.java:18`):

```
if (uncompressedLength > 8 388 608) → Fehler "Packet too big"
if (uncompressedLength < threshold) → dataLength 0 + Rohdaten
else                                → dataLength = uncompressedLength + zlib
```

| Threshold | Bedeutung | Verifikation |
|---|---|---|
| −1 | kein `login_compression`, kein Header | Laufzeit: 0 komprimierte Frames (docs/17) |
| 256 | Pakete < 256 B unkomprimiert (Header 0), ≥ 256 B komprimiert | Unit-Test `thresholdBoundaryMatchesVanillaEncoder` (255 → roh, 256 → zlib); Laufzeit: 94 von 4 871 Frames komprimiert |
| **0** | „< 0“ trifft nie zu → **jedes Paket wird komprimiert**, auch 1-Byte-Pakete | Quellcode (s. o.); Unit-Test `thresholdZeroCompressesEveryPacketIncludingOneByte`; Laufzeit: 5 039 von 5 040 Frames komprimiert (Ausnahme: `login_compression`) |

Der eigene Encoder (`CompressionCodec#encode`) folgt exakt dieser Regel. Das ist Pflicht, weil der Server unsere Pakete prüft: Ein komprimierter Frame mit `dataLength < threshold` wäre „Badly compressed packet“ (`CompressionDecoder.java:32-34`).

## 4. Größenlimits

| Grenze | Wert | Wann geprüft | Vanilla-Quelle | Eigener Client |
|---|---|---|---|---|
| Framelänge | ≤ 2 097 151 B (VarInt ≤ 3 Bytes), ≠ 0 | **vor** allem anderen | `Varint21FrameDecoder` | `FrameCodec.readFrame` |
| komprimierte Daten | ≤ 2 097 152 B | **vor** der Dekompression | `CompressionDecoder.MAXIMUM_COMPRESSED_LENGTH :13` | `CompressionCodec.decode` |
| deklarierte unkomprimierte Länge | ≤ 8 388 608 B | **vor** der Dekompression (keine Allokation darüber) | `MAXIMUM_UNCOMPRESSED_LENGTH :14`, Prüfung `:36` | `CompressionCodec.decode` |
| deklarierte Länge ≥ Threshold | – | vor der Dekompression | `:32` (nur bei `validateDecompressed`) | immer geprüft |
| tatsächlich entpackte Länge = deklarierte Länge | exakt | **nach** der Dekompression | `inflate :72-77` | `CompressionCodec.decode` (zu kurz, zu lang, Stream nicht beendet) |
| Restbytes nach dem zlib-Stream | keine | nach der Dekompression | nicht geprüft | **zusätzlich geprüft** |
| Paket nach der Dekompression vollständig verbraucht | – | beim Dekodieren | `PacketDecoder` | `PacketReader#expectEnd` |
| ausgehendes Paket | ≤ 8 388 608 B | vor dem Komprimieren | `CompressionEncoder :20-22` | `CompressionCodec.encode` |

**Effektive Obergrenze:** Ein Paket muss ≤ 8 MiB unkomprimiert sein **und** komprimiert in einen Frame passen (≤ 2 097 151 B). Testbefund: 8 MiB Zufallsdaten komprimieren auf 4,2 MB und sind daher nicht übertragbar (`incompressibleMaximumPacketDoesNotFitIntoAFrame`). 8 MiB gut komprimierbarer Daten passen (`maximumSizePacketRoundTripsWhenItCompressesIntoOneFrame`).

**Schutz vor Dekompressionsbomben:** Der Inflater füllt nur einen Puffer der deklarierten Länge (≤ 8 MiB). Liefert der Stream mehr, wird abgebrochen (`decompressionBombIsBoundedByDeclaredLength`: 8 MiB Nullen in < 20 kB zlib, deklariert 1 000 → Fehler, keine 8-MiB-Allokation).

## 5. Fehlerbehandlung

Jede Verletzung erzeugt eine `ProtocolException`. Die Session beendet die Verbindung dann mit Ergebnis „ERROR“ und protokolliert die Ursache.

| Fall | Unit-Test |
|---|---|
| ungültige zlib-Daten / zerstörter Header | `malformedZlibRejected` |
| abgeschnittener Stream, leere Daten | `truncatedZlibStreamRejected` |
| falsche Adler-32-Prüfsumme | `corruptedChecksumRejected` |
| überzählige Bytes nach dem Stream | `trailingBytesAfterZlibStreamRejected` |
| entpackt länger / kürzer als deklariert | `inflatedDataLongerThanDeclaredRejected`, `inflatedDataShorterThanDeclaredRejected` |
| deklarierte Länge > 8 MiB, komprimiert > 2 MiB | `declaredUncompressedLengthAboveEightMiBRejectedBeforeInflating`, `compressedPayloadAboveTwoMiBRejectedBeforeInflating` |
| Framelänge > 21 Bit | `frameLimitAppliesToCompressedFrameOnTheWire` |
| komprimiert, aber unter dem Threshold | `compressedFlagBelowThresholdRejected`, `compressedPacketBelowThresholdRejected` |
| negative oder überlange `dataLength` | `negativeOrOverlongDataLengthRejected` |
| ausgehend > 8 MiB | `oversizedOutgoingPacketRejected` |
| Ende-zu-Ende über Socket inkl. Zähler | `connectionCompressesAboveThresholdAndCountsFrames` |

## 6. Zähler (`TransportStats`)

`Connection#transportStats()` liefert: aktiven Threshold, Frames ein/aus, davon komprimiert/unkomprimiert, Drahtbytes (Frame-Inhalt ohne Längenpräfix) und Paketbytes (nach Dekompression).

Anzeige:
- **Terminal:** `status` (zusätzliche Zeile) und `compression`
- **Programmende:** `transport: …` in der Zusammenfassung
- **Integrationstest:** prüft mit `-PexpectedThreshold=N` exakt
  - −1: 0 komprimiert
  - 256: komprimierte Frames vorhanden, Drahtbytes < Paketbytes
  - 0: genau 1 unkomprimierter Eingangsframe (`login_compression`) und 2 unkomprimierte Ausgangsframes (`intention`, `hello`)

Frames vor der Aktivierung gelten als „unkomprimiert“.

## 7. Messwerte (Lauf `m2-m3-*`, je ~61 s im Spiel, Sichtweite 2)

| Threshold | Frames ein (komp./unkomp.) | Draht → Paket ein | Frames aus (komp./unkomp.) | Paket → Draht aus |
|---|---|---|---|---|
| −1 | 3 137 (0 / 3 137) | 2 769 033 → 2 769 033 B | 1 379 (0 / 1 379) | 4 649 → 4 649 B |
| 256 | 4 871 (94 / 4 777) | 461 719 → 2 767 696 B (**16,7 %**) | 1 381 (0 / 1 381) | 4 651 → 6 030 B (+1 Header-Byte je Frame) |
| 0 | 5 040 (5 039 / 1) | 498 415 → 2 768 853 B (18,0 %) | 1 381 (1 379 / 2) | 4 651 → 16 277 B (**3,5 ×** größer) |

Erkenntnis: Threshold 0 vergrößert kleine Pakete erheblich (zlib-Overhead ≈ 8–11 B je 1–2-Byte-Paket). Threshold 256 komprimiert nur die wenigen großen Pakete (Chunks, Registry-Daten, Tags) und spart dort ≈ 83 %. Die höhere Frame-Anzahl bei 256/0 gegenüber −1 stammt aus Entity-Bewegungspaketen der Mobs in der Nähe, nicht aus der Kompression. Belegt durch die Paketzählung (`analyze-runtime.js` §4): `rotate_head` −1: 947 / 256: 1 957 / 0: 2 094, `move_entity_pos_rot` 633 / 1 418 / 1 281. Die Paketbytes der Chunks und Registries sind in allen Läufen nahezu gleich (≈ 2,77 MB).
