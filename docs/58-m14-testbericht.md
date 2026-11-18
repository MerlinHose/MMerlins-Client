# 58 – Testbericht M14 (Stand 2026-11-18): teilweise abgeschlossen

**Status: M14 ist teilweise abgeschlossen.**

- **Erledigt:**
  - Der Tick-Vergleich der Fahrzeuge gegen den unveränderten Vanilla-Client ist grün: 16/16 Szenarien und 1125/1125 Ticks bitgenau (§3).
  - 459 Unit-Tests sind grün.
- **Bildvergleich:** Er wurde ausgeführt und zeigt drei Darstellungsunterschiede, die noch zu beheben sind (§5).
- **Noch nicht ausgeführt:** Grafiktest mit Pferdeszene, Leistung RD2/RD8 und die Regression M1–M14 (§6). Sie würden durch die anstehenden Renderer-Korrekturen ungültig.
- **Konsequenz:**
  - Es gibt keinen Merge nach `main`.
  - Die echte Microsoft-Authentifizierung bleibt auf den letzten Meilenstein verschoben.
  - M15 wurde nicht begonnen.

**Rahmen:** Ausschließlich der lokale Vanilla-26.3-Server auf 127.0.0.1 (Protokoll 777, `online-mode=false`).

- **Speicher:** Server mit 1 GB, Vanilla-Referenzclient mit 1,5 GB. Vor jedem Lauf wurden ≥ 3000 MB freier Speicher geprüft.
- **Netzwerk:** In allen Läufen **0 externe Verbindungen**.
- **Code und Ressourcen:** Es wurde kein Mojang-Code kopiert; Vanilla-Regeln wurden aus den Loom-Quellen gelesen. Laufzeitlogs liegen unter `analysis/runtime-logs/` (nicht versioniert).

**Kennzeichnung:**
- **[G]** gemessen bzw. automatisch geprüft.
- **[E]** begründete, nachgewiesene Abweichung.
- **[O]** offen.

## 1. Ergebnisübersicht

| Bereich | Ergebnis |
|---|---|
| Unit-Tests | **459 grün** [G] |
| Fahrzeugvergleich gegen Vanilla (`m14-vehicle9`) | **16/16 Szenarien, 1125/1125 Ticks bitgenau**, inklusive Absteigen und je 9 Folgeticks; 0 Serverabweichungen, 0 Korrekturen; `VehicleIntegrationTest` grün [G] |
| Reproduzierbarkeit | Fahrzeugsuite und Diagnose zusammen in 13 Läufen; Vanilla selbst in bis zu 8 Wiederholungen je Szenario verglichen (§4) [G] |
| Bildvergleich `m14-visual1` (6 Entity-Szenen) | meanΔE 6,57, meanSSIM 0,581. Modelle und Skins stimmen; 3 Darstellungsunterschiede und Szenenrauschen (§5) [G][O] |
| Grafiktest, Leistung RD2/RD8, Regression M1–M14 | nicht ausgeführt [O] |

## 2. Testaufbau Fahrzeugvergleich

- **Ablauf:** `analysis/tools/m14-vehicle-run.sh <label>`. Der Vanilla-Referenzclient (Fabric-Forschungsmod `VehicleReferenceClient`, nur Beobachtung und Tastensimulation) und der eigene Client (`VehicleIntegrationTest`) fahren nacheinander dieselben Szenarien aus `analysis/tools/m14-vehicle-course.txt`.
- **Verglichen werden je Tick:**
  - Fahrzeugposition, Geschwindigkeit und Gier
  - `onGround` und horizontale Kollision
  - Blickrichtung und Eingabe
- **Zusätzlich verglichen:**
  - Absteige-Tick und Absteigeposition
  - Spielerposition und -geschwindigkeit in den 9 Ticks nach dem Absteigen
  - Serverposition und Servergeschwindigkeit des Fahrzeugs
- **Szenarien:**
  - Boot auf Wasser: geradeaus, Kurven, rückwärts, gegen eine Wand
  - Boot auf Eis, Boot an Land, untergetauchtes Boot
  - Floß, Kistenboot
  - Boot gegen Boot
  - Pferd: Gehen, Stufe, Sprung, gegen ein Boot
  - Lore (servergesteuert)
  - Spieler läuft gegen ein Boot
- **Diagnose:**
  - `analysis/tools/m14-vehicle-diag.txt` enthält nur die betroffenen Szenarien.
  - Eine Befehlskette (Wiederholungs-Befehlsblock) schreibt in jedem Servertick Spielzeit, Passagierzustand, Schleichzustand sowie Position und Geschwindigkeit von Boot, Spieler und Hindernis (×10⁶) ins Serverlog.
  - `analysis/tools/m14-diag-table.sh` macht daraus eine Tabelle je Servertick.
  - Damit wurde der tatsächliche Serverzustand verglichen, nicht nur die Clientsicht.

## 3. Ergebnisse und behobene Fehler

### 3.1 Behobene Clientfehler (jeweils mit Ursache aus den Vanilla-26.3-Quellen und Regressionstest)

1. **Delta-Basis des eigenen Fahrzeugs.**
   - Vanilla schreibt `VecDeltaCodec` auch für ein selbst gesteuertes Fahrzeug fort (`ClientPacketListener#handleMoveEntity`, `handleEntityPositionSync`) und setzt die Basis nur aus Paketen.
   - Unser Client verwarf die Deltas während der Fahrt und setzte die Basis auf die lokale Physikposition. Nach dem Absteigen erschien das Boot deshalb an falscher Stelle (diag1: z = 161,895 statt Server 161,677) und stieß den Spieler weg. Das war die Ursache der Serverabweichung von „2 m“.
   - Test: `PassengerAndBabyTest#deltaBaseOfTheOwnVehicleFollowsTheServerNotTheLocalPhysics`
2. **Passagierbewegung.**
   - `Entity#rideTick` setzt die Geschwindigkeit auf 0 und ruft dann `tick()` auf. Der lokale Spieler ist autoritativ und rechnet `travel` mit seiner Eingabe. `positionRider` setzt danach nur die Position.
   - Der Absteige-Teleport (`ServerPlayer#teleportTo`) ist `Relative.DELTA`, die Geschwindigkeit bleibt also erhalten.
   - Gemessen (diag4): vz = 0,017836 in der Luft, 0,01568 unter Wasser bei „vorwärts“. Unser Client setzte sie fest auf 0, deshalb glitt der Spieler nach dem Auswerfen aus dem untergetauchten Boot nicht weiter.
   - Test: `MovementControllerTest#riderKeepsTheTravelVelocityOfItsLastRideTick`
3. Aus dem vorigen Stand bestätigt:
   - Schleichtaste nach dem Absteigen
   - Status des Boots beim Aufsitzen (`primeStatus`)
   - Bootsstoß (`inflate(0.2, -0.01, 0.2)`, nach der eigenen Bewegung)
   - Tickreihenfolge beim Fahren (`tickBeforeOwnVehicle`)
   - lineare Interpolation von Booten und Loren
4. **Zurückgenommen:** Eine Änderung an `set_entity_motion` beruhte auf einer falschen Annahme. Das Paket wird beim Auswerfen gar nicht gesendet (Paketlog diag3). Die Änderung und ihr Test wurden entfernt.

### 3.2 Synchronisierter Testaufbau

| Ursache | Nachweis | Maßnahme |
|---|---|---|
| Pferd wandert nach dem Absteigen zufällig (KI) | Serverpferd erhält `set_entity_motion`, Stoß in Folgetick 10 (vehicle7) | Pferde mit `NoAI:1b`; steuerbar bleiben sie über `AbstractHorse#getControllingPassenger` |
| Skript endete vor dem Absteigen („dismount only own“) | Absteigen erst nach dem letzten Skripttick | 5 Leerticks nach `s:2` |
| Erkennungstick des Absteigens | Die Referenz lässt Tasten erst am Tickende los | Eigenes Skript verwendet in diesem Tick ebenfalls die Skripteingabe |
| Freiräumen der Strecke abgelehnt, Steindeckel über dem Becken | `fill` > 32 768 Blöcke | Freiräumen in 4 Teilen, Wasser bis y 63 |

## 4. Determinismus und zusätzliches Kriterium

Ein Teil der Szenarien hängt vom Server-Timing ab. Das zeigen auch Wiederholungen des **unveränderten Vanilla-Clients**:

- **Absteigen eines fahrenden Boots (Wettlauf):**
  - Kommt `set_passengers` erst nach Beginn des nächsten Clientticks an, fährt der Client einen Tick länger.
  - Variante A (Vanilla, diag5/diag6): Der Server hat das `move_vehicle` dieses Ticks schon angenommen (Server = Client = 185,69998760692673).
  - Variante B (eigener Client, diag7): Der Server ist vorher abgestiegen. Die Clientsicht schwenkt dann über das periodische Positionsupdate (`ServerEntity`: `tickCount % 60`) auf die Serverposition ein.
- **Auswerfen nach 60 Ticks unter Wasser:** Der Server wirft zu einem festen Servertick aus. Welcher `move_vehicle` bis dahin angekommen ist, hängt von der Paketlaufzeit ab (diag1: Bootsposition von Tick 18, diag3: Tick 19).
- **Boot gegen geschobenes Boot:**
  - Der Server bewegt das Hindernis in allen Läufen identisch (Servertabelle).
  - Wann der Client es sieht, hängt aber von der Tracker-Phase ab (`updateInterval`, `needsSync`, Delta- oder Absolutpaket).
  - Vanilla in 8 Läufen: Kontakt immer bei Tick 32, Freigabe bei 34 (1×), 36 (3×) oder 37 (4×).
  - Größte Positionsabweichung zwischen zwei Vanilla-Läufen: 0,6104.

**Zusätzliches Kriterium.** Es gilt nur für diese nachgewiesen gekoppelten Fälle; die bitgenaue Zählung bleibt unverändert und wird immer berichtet:

| Fall | Bedingungen |
|---|---|
| Absteige-Timing | Alle gemeinsamen Fahrticks bitgenau, Absteige-Tick genau 1 versetzt |
| Geschobenes Hindernis | Bis einschließlich Kontakt bitgenau, Freigabe im gemessenen Vanilla-Bereich [34, 37], Abweichung ≤ 0,6104 |
| Zusatztick ohne Annahme durch den Server | Serverposition = vorletzter Fahrtick, und die Clientsicht des Fahrzeugs schwenkt innerhalb von 4 s auf die Serverposition ein |
| Servergesteuerte Lore | Bis zur ersten Serverbewegung bitgenau; die Endposition ist von der Serverprüfung ausgenommen (Lore rollt nach dem Absteigen weiter) [E] |

Im abschließenden Lauf `m14-vehicle9` war das Kriterium **nicht nötig**: Alle 16 Szenarien waren streng bitgenau. In `vehicle7` und `vehicle8` griff es bei 3 Szenarien (Absteige-Timing, Boot gegen Boot) mit den oben genannten Nachweisen.

### Verlauf

| Lauf | Bitgenaue Szenarien | Bitgenaue Ticks | Bemerkung |
|---|---|---|---|
| vehicle4 | 11/16 | 1043/1125 | Ausgangslage |
| vehicle6 | 11/16 | 1018/1125 | Tickreihenfolge und lineare Interpolation |
| vehicle7 | 12/16 | 1089/1125 | Delta-Basis, Passagier-`travel` |
| vehicle8 | 12/16 | 1087/1125 | Pferde ohne KI, Kriterium für 3 Szenarien |
| **vehicle9** | **16/16** | **1125/1125** | Test grün |

## 5. Bildvergleich `m14-visual1`

- **Aufbau:** 6 Szenen mit Haustieren, Pferden und Kaninchen, Monstern, Dorfbewohnern und Nutztieren, Humanoiden sowie Fahrzeugen mit Passagieren. Der Vanilla-Referenzclient und der eigene Grafikclient nehmen dieselbe Kameraposition ein.
- **Ergebnis:**
  - meanΔE 6,57, meanSSIM 0,581.
  - 0 offene GL-Objekte beim Beenden, 0 externe Verbindungen.
  - Modelle, Babyformen, Varianten und Mannequin-Skins (Classic und Slim) stimmen sichtbar überein; die Passagiere sitzen richtig.

| Befund | Einordnung |
|---|---|
| Brennende Untote (Tageslicht): Vanilla zeichnet Feuer am Entity, unser Client nicht | **[O]** fehlende Darstellung brennender Entities (`EntityRenderDispatcher`, Feuer-Sprites) |
| Kistenlore: Vanilla zeigt die Kiste, unser Client einen Würfel mit Holzbretter-Textur | **[O]** bisher dokumentierte Vereinfachung (docs/55 §7); Kistenmodell `minecraft:chest#main` liegt vor |
| Dorfbewohner: verschränkte Arme bei uns hautfarben, bei Vanilla braune Ärmel | **[O]** Ursache offen; Typ- und Berufsschichten werden gezeichnet |
| Drops und Partikel aus dem Entfernen der vorherigen Szene, Wolkenstand, Himmelsfarbe | Szenenaufbau: Entfernen ohne Drops und feste Wolkenzeit nötig, bevor die Kennzahlen aussagekräftig sind |

## 6. Offene Prüfungen

1. Die drei Darstellungsunterschiede aus §5 beheben, die Szenen bereinigen, den Bildvergleich wiederholen.
2. Grafiktest mit Pferdeszene: `bash analysis/tools/m14-gfx-run.sh <label>`
3. Leistung RD2/RD8 mit 10/50/100 Entities (`analysis/tools/m14-perf.txt`, `M6_DIFFICULTY=easy`), Stabilitätslauf, GPU-Freigabe.
4. Regression M1–M14 nacheinander: `bash analysis/tools/m14-regression.sh [von] [bis]`

Erst danach sind die Abschlusskennzeichnung und ein Merge nach `main` möglich.
