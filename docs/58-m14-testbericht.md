# 58 – Testbericht M14 (Stand 2026-11-18): teilweise abgeschlossen

**Status: M14 ist teilweise abgeschlossen.**

- **Erledigt:**
  - Code und Unit-Tests sind fertig: 457 Tests, alle grün.
  - Der Tick-Vergleich der Fahrzeuge gegen den unveränderten Vanilla-Client läuft.
  - 11 von 16 Szenarien sind bitgenau gleich.
- **Offen:**
  - Einige Abweichungen im Fahrzeugvergleich sind noch nicht geklärt (§3.3).
  - Der Fahrzeugtest ist deshalb noch rot.
  - Nach der Vorgabe „erst nach erfolgreichem Fahrzeugvergleich weiter“ wurden Bildvergleich, Grafiktest, Leistungsmessung und die Regression M1–M14 **nicht** ausgeführt (§5).
- **Konsequenz:**
  - Es gibt keinen Merge nach `main`.
  - Die echte Microsoft-Authentifizierung bleibt auf den letzten Meilenstein verschoben.
  - M15 wurde nicht begonnen.

**Rahmen:** Ausschließlich der lokale Vanilla-26.3-Server auf 127.0.0.1 (Protokoll 777, `online-mode=false`).

- **Speicher:** Server mit 1 GB, Vanilla-Referenzclient mit 1,5 GB. Vor jedem Lauf wurden ≥ 3000 MB freier Speicher geprüft.
- **Netzwerk:** Der Netzwerkmonitor meldet in allen Läufen **0 externe Verbindungen**.
- **Code und Ressourcen:** Es wurde kein Mojang-Code kopiert; Vanilla-Regeln wurden nur aus den Loom-Quellen gelesen. Laufzeitlogs liegen unter `analysis/runtime-logs/` (nicht versioniert).

**Kennzeichnung:**
- **[G]** gemessen bzw. automatisch geprüft.
- **[E]** erwartete, begründete Abweichung.
- **[O]** offen.

## 1. Ergebnisübersicht

| Bereich | Ergebnis |
|---|---|
| Unit-Tests | **457 grün** [G] (M13: 379 + M12-Nachträge; M14 neu u. a. Modelle, Skins, Passagiere, Fahrzeugphysik, Regressionstests aus §3) |
| Fahrzeugvergleich gegen Vanilla (Lauf `m14-vehicle6`) | 16 Szenarien; 1125 Ticks verglichen, 1018 bitgenau; **11 Szenarien vollständig bitgenau**, inklusive Absteigen und 9 Folgeticks [G] |
| Bester Lauf (`m14-vehicle5`) | 1075 von 1125 Ticks bitgenau [G] |
| Serverkorrekturen beim Fahren | **0** in allen Szenarien [G] |
| Ein- und Aussteigen | 15 Aufsitzen, 15 Absteigen, Absteige-Tick in allen vergleichbaren Fällen gleich [G] |
| Grafiklauf `m6-m14-gfx2` (vor dieser Sitzung) | 0 Debug-Boxen; Entladen und Neuladen 66 → 1 → 66 Entities; 60 FPS mit 66 Entities; Spitzenverbrauch 2174 MB [G] |
| Bildvergleich, Grafiktest (neu), Leistung RD2/RD8, Regression M1–M14 | **nicht ausgeführt** [O] (§5) |

## 2. Fahrzeugvergleich: Aufbau

- **Werkzeug:** `analysis/tools/m14-vehicle-run.sh <label>`. Der Vanilla-Referenzclient (Fabric-Forschungsmod, `VehicleReferenceClient`, nur Beobachtung und Tastensimulation) und der eigene Client (`VehicleIntegrationTest`) fahren nacheinander dieselben Szenarien aus `analysis/tools/m14-vehicle-course.txt`.
- **Verglichen wird je Tick:**
  - Fahrzeugposition, Geschwindigkeit und Gier
  - `onGround` und horizontale Kollision
  - Blickrichtung des Spielers
  - Eingabe
- **Zusätzlich verglichen:**
  - Absteige-Tick und Absteigeposition
  - je Tick die Spielerposition und -geschwindigkeit in den 9 Ticks nach dem Absteigen
  - Serverposition und Servergeschwindigkeit des Fahrzeugs am Ende
- **Szenarien:**
  - Boot auf Wasser: geradeaus, Kurven, rückwärts, gegen eine Wand
  - Boot auf Eis und an Land
  - Floß, Kistenboot
  - Boot gegen Boot
  - Pferd: Gehen, Stufe, Sprung, gegen ein Boot
  - Lore: Anschieben (serverseitig)
  - untergetauchtes Boot
  - Spieler läuft gegen ein Boot

  Absteigen jeweils per Schleichtaste.

### 2.1 Behobene Fehler im Testaufbau

| Befund | Ursache | Behebung |
|---|---|---|
| Erster Referenzlauf ohne Messwerte | Neues Laufverzeichnis ohne `options.txt` → Barrierefreiheits-Startbildschirm wartete ~56 min auf Eingabe | `run-vehicleref/options.txt` wie die bewährten Referenzläufe; Orchestrator warnt, wenn kein Beitritt nach 300 s |
| Alle Fahrzeuge blieben nach 0,8 Blöcken hängen | `fill … air` mit 103 125 Blöcken vom Server abgelehnt (Grenze 32 768); Reste alter Läufe standen in der Strecke. Außerdem Steindeckel über dem Becken (Wasser nur bis y 62) | Freiräumen in 4 Teilen; Wasser bis y 63; Boote starten an der Oberfläche |

## 3. Fahrzeugvergleich: Ergebnisse

### 3.1 Im eigenen Client gefundene und behobene Fehler (jeweils mit Regressionstest)

1. **Schleichtaste nach dem Absteigen nie losgelassen.** `leaveRiding` setzte den gesendeten Eingabezustand zurück, ohne etwas zu senden. Der Server hielt „Schleichen“ weiter für gedrückt und warf den Spieler beim nächsten Aufsitzen sofort wieder ab. Jetzt bleibt der zuletzt gesendete Zustand erhalten (wie `LocalPlayer#lastSentInput`).
   - Test: `MovementControllerTest#releasingSneakAfterDismountIsSentToTheServer`
2. **Boot sprang beim Aufsitzen an die Wasseroberfläche.** `BoatPhysics` begann mit dem Status `IN_AIR`, deshalb griff im ersten Tick die Regel „Übergang aus der Luft“. Der Vanilla-Client berechnet den Status in jedem Tick, auch ohne Steuerung. Neu ist `primeStatus()` beim Aufsitzen. Danach sind alle Wasser-, Floß- und Kistenbootszenarien bitgenau, ebenso das untergetauchte Boot (Sinken, Auswerfen nach 60 Ticks).
   - Test: `VehiclePhysicsTest#submergedBoatKeepsSinkingAfterMountInsteadOfSnappingToTheSurface`
3. **Stoß durch Boote zu spät bzw. gar nicht.**
   - Vanilla stößt über die um `inflate(0.2, -0.01, 0.2)` vergrößerte Bootsbox.
   - Der lokale Spieler tickt vor den übrigen Entities; der Stoß wirkt also nach der eigenen Bewegung im selben Tick.
   - Bei Gehskripten war der Stoß bisher ganz abgeschaltet.
   - Ergebnis: `walk_into_boat` ist bitgenau. Der Spieler steigt wie in Vanilla per Step-up (0,6 > 0,5625) auf das Boot; der frühere Verdacht „vom Boot blockiert“ war also kein Fehler.
   - Test: `PassengerAndBabyTest#boatPushesWithinItsInflatedBoxButNotAPlayerStandingOnIt`
4. **Tickreihenfolge beim Fahren.**
   - Vanilla tickt Entities in Einfügereihenfolge, ein Fahrzeug vor seinem Passagier.
   - Unser Client interpolierte alle anderen Entities erst nach der eigenen Bewegung. Hindernisse und die servergesteuerte eigene Lore lagen dadurch einen Tick zurück.
   - Neu: `EntityManager#tickBeforeOwnVehicle()`.
   - Tests: `PassengerAndBabyTest#entitiesInsertedBeforeTheOwnVehicleAreInterpolatedBeforeItMoves`, `#serverDrivenOwnVehicleIsInterpolatedBeforeItsRider`
5. **Interpolation von Booten und Loren.**
   - Vanilla verwendet `LinearInterpolationHandler`: Ein neues Ziel ersetzt das alte, es bleiben 3 Schritte, je Tick wird `pos + 1/rest · (ziel − pos)` gerechnet.
   - Unser Client arbeitete die Ziele nacheinander ab (Warteschlange).
   - Neu ist ein linearer Modus mit derselben Rechenreihenfolge (bitgleich zu `Mth.lerp`/`rotLerp`).
   - Die Vergleichsspur der Lore zeigt jetzt die interpolierte Position statt des Paketwerts.
   - Test: `EntityInterpolatorTest#linearModeRestartsTowardsTheNewestTargetLikeVanilla`

Zuvor in M14 behoben: schwarze Katze als Tabby (fehlendes Variantenfeld → Vanilla-Standardvariante), Rechtsklick auf Floß und Lore (Vorhersage des Interaktionsergebnisses), doppeltes `set_passengers` beim Aufsitzen.

### 3.2 Bitgenau gleich wie Vanilla (Lauf `m14-vehicle6`)

| Szenario | Ticks | Absteigen und 9 Folgeticks |
|---|---|---|
| boat_water_forward, boat_water_turn | 111, 121 | gleich |
| boat_wall, boat_land, raft_water_forward, chest_boat_turn | 51, 81, 81, 71 | gleich |
| horse_walk, horse_step, horse_jump, horse_into_boat | 81, 61, 63, 51 | gleich |
| boat_ice, boat_submerged | 91, 19 (Fahrt bitgenau) | **abweichend**, siehe §3.3 |
| walk_into_boat | 40 | – |

### 3.3 Verbleibende Abweichungen

| Szenario | Befund | Einordnung |
|---|---|---|
| boat_ice, boat_submerged: nach dem Absteigen | Fahrt und Absteigeposition (boat_ice) sind bitgleich. Danach hat das **Serverboot** in unserem Lauf eine Geschwindigkeit (vz −0,155) und steht am Ende 2,06 Blöcke weiter. In Vanilla stimmen Server- und Clientposition exakt überein. Unser Spieler wird ab Folgetick 3 von diesem Boot gestoßen. | **[O]** Ursache nicht gefunden. Die Paketfolge (Schleichen → ein Tick später `set_passengers` + Teleport, kein `move_vehicle` danach) ist unauffällig. Grund für den roten Fahrzeugtest („server accepted vehicle positions“). |
| boat_into_boat | Hindernisboot wird serverseitig geschoben. Vanilla kommt in drei Läufen bei Tick 36 frei, unser Client bei Tick 37. Vanilla selbst schwankt zwischen Läufen ab Tick 34 (1e‑5). | **[O]** systematischer Rückstand um einen Tick, Ursache offen |
| minecart_push | Interpolation jetzt in gleichen Dritteln wie Vanilla. Die serverseitige Bewegung der Lore (Ziele, Absteigeposition 102,5 / 101,5) unterscheidet sich zwischen Läufen. | **[E]** servergesteuert, nicht reproduzierbar; nur die Interpolation ist vergleichbar |
| „dismount only own“ | Je Lauf ein anderes Szenario: horse_walk (Lauf vehicle3), raft (vehicle4), boat_wall (vehicle5), boat_water_back (vehicle6). Vanilla steigt einen Tick später ab. | **[E]** Paket-Timing: Ob der Server die Schleichtaste im selben Server-Tick verarbeitet, hängt von der Ankunftszeit ab |

## 4. Prozesse und Ressourcen

- **Speicher:** Spitzenverbrauch der Projekt-JVMs je Fahrzeuglauf 2164–2230 MB; mindestens 3788–4238 MB frei.
- **Abgebrochener Lauf:** Ein früher Lauf (`vehicle4`, erster Versuch) wurde von Claude Code wegen Speicherdrucks beendet. Danach räumte `analysis/tools/m12-cleanup.ps1` auf: 0 Projektprozesse übrig, Server über die Konsole gestoppt, Konfiguration wiederhergestellt. Fremde Prozesse wurden nicht beendet, der Speicherschutz nicht umgangen.
- **Serverkonfiguration nach jedem Lauf:** `difficulty=easy`, `network-compression-threshold=-1`, `online-mode=false`.

## 5. Offene Prüfungen

1. Klärung der Abweichungen aus §3.3 (Serverboot nach dem Absteigen, Rückstand bei boat_into_boat). Danach Fahrzeugvergleich grün.
2. Bildvergleich `m14-visual1`: `M12_SCENES=$PWD/analysis/tools/m14-scenes.txt M12_DIFFICULTY=easy M6_DIFFICULTY=easy bash analysis/tools/m12-visual.sh m14-visual1 both`
3. Grafiktest mit korrigierter Pferdeszene (`analysis/tools/m14-gfx.txt`, Ritt auf 1,9 s verkürzt): `bash analysis/tools/m14-gfx-run.sh <label>`
4. Leistung RD2/RD8 mit 10/50/100 Entities (`analysis/tools/m14-perf.txt`, `M6_DIFFICULTY=easy`), Stabilitätslauf, GPU-Freigabe.
5. Regression M1–M14 nacheinander: `bash analysis/tools/m14-regression.sh [von] [bis]`

Erst danach sind ein Merge nach `main` und die Abschlusskennzeichnung möglich.
