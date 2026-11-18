# 57 – Fahrzeuge, Passagiere und Entity-Kollisionen (M14, Stand 2026-11-17)

## 1. Protokoll

| Paket | Richtung | Inhalt | Verwendung |
|---|---|---|---|
| `set_passengers` | S→C | Fahrzeug-ID, Passagier-IDs (Reihenfolge = Plätze) | Auf-/Absteigen aller Entities inkl. eigener Spieler |
| `move_vehicle` | S→C | Position (3 × double), Gier, Neigung | Serverkorrektur des selbst gesteuerten Fahrzeugs; der Client übernimmt und bestätigt sofort (`handleMoveVehicle`) |
| `move_vehicle` | C→S | Position, Gier, Neigung, Bodenkontakt | jeden Tick, wenn der eigene Spieler ein Boot oder gesatteltes Pferd steuert |
| `paddle_boat` | C→S | Ruder links/rechts | jeden Tick des selbst gesteuerten Boots |
| `player_input` | C→S | Tasten (vorwärts … Schleichen) | bei Änderung; Schleichen = Absteigen (serverseitig `wantsToStopRiding`), Lore liest die Vorwärtstaste |
| `move_player_rot` | C→S | Blick | als Passagier jeden Tick statt Positionspaketen (`LocalPlayer#sendChanges`) |
| `player_command` 3 | C→S | `START_RIDING_JUMP`, Ladung 0–100 | Pferdesprung beim Loslassen der Sprungtaste |
| `interact` | C→S | Rechtsklick auf Fahrzeug | Einsteigen (Boote/Loren jetzt auswählbar) |

Reihenfolge je Tick beim Reiten (wie Vanilla: Fahrzeug tickt vor dem Passagier): `paddle_boat` → `player_input` (falls geändert) →
`move_player_rot` → `move_vehicle` → `client_tick_end`. Fahrzeuge nutzen die Tasten des **vorigen** Ticks (`rideTick` setzt die
Eingabe erst nach dem Fahrzeug-Tick).

## 2. Passagierverwaltung (`EntityManager`, `PassengerSeats`)

- **Bestätigter Zustand und Darstellung getrennt:** `x/y/z` bleiben die Serverwerte; die Darstellungsposition eines Passagiers ist die
  Sitzposition auf der (interpolierten) Darstellung seines Fahrzeugs, rekursiv für verschachtelte Passagiere (Tiefe ≤ 16).
- **Sitz** wie `Entity#positionRider`: Ankerpunkt PASSENGER des Fahrzeugs (Tabelle `X`, Index begrenzt; Babys: Tabelle `XB`;
  Attributskalierung), um −Gier gedreht, minus Ankerpunkt VEHICLE des Passagiers (Spieler 0,6, Zombie 0,7, Babyzombie 0,1875 …).
  Sonderregeln: Boot (Höhe/3, Floß 0,889; Versatz 0 bzw. Truhenboot 0,15; zu zweit 0,2 / −0,6, Tiere +0,2), Lore (Dorfbewohner/
  Händler tiefer), Pferd (+0,15/−0,7 · Steigewert).
- **Mehrere Passagiere, Änderungen, Ein-/Aussteigen:** jede Liste ersetzt die vorige; Ausgestiegene verlieren das Fahrzeug, ein
  Passagier, der auf ein anderes Fahrzeug wechselt, wird aus der alten Liste entfernt.
- **Spät gespawnte Passagiere:** IDs, die noch nicht existieren, bleiben in der Liste und werden beim Spawn verknüpft.
- **Zyklen** (Passagier ist das Fahrzeug selbst oder trägt es) werden verworfen und gezählt.
- **Fahrzeug entfernt / Unload / Dimensionswechsel:** `remove_entities` löst alle Verknüpfungen; Weltwechsel/Konfiguration leeren
  alles inkl. eigenem Fahrzeug.
- **Eigener Spieler:** steht nicht in der Entity-Liste; `ownVehicle`, Sitzindex und Sitzposition werden getrennt geführt. Der eigene
  Spieler wird nicht an seiner alten Weltposition geführt: Position = Sitz je Tick, Kamera folgt.

## 3. Boote (`physics/BoatPhysics`)

Eigene Umsetzung von `AbstractBoat#tick` für das selbst gesteuerte Boot (Fahrer = erster Passagier):

1. Status: unter Wasser (Oberkante + 0,001 unter Wasseroberfläche; fließend → `UNDER_FLOWING_WATER`), im Wasser (Wasserstand über
   der Unterkante), an Land (mittlere Reibung der Blöcke unter der Unterkante, Seerosen ausgenommen), in der Luft.
2. Strömung: normierte Summe × 0,014 (nicht Spieler: normiert, nicht gemittelt).
3. `floatBoat`: Schwerkraft 0,04; Reibung Wasser/Luft 0,9, unter Wasser 0,45, an Land Blockreibung (Fahrer: für den Folgetick
   halbiert); Auftrieb `(Wasserstand − y)/Höhe`, `vy = (vy + Auftrieb · 0,04/0,65) · 0,75`; aus der Luft aufs Wasser: auf den
   Wasserspiegel setzen.
4. `controlBoat`: links/rechts ±1° Drehgeschwindigkeit (summiert, mit Reibung gedämpft), vorwärts +0,04, rückwärts −0,005, nur
   drehen +0,005; Ruder `rechts∧¬links ∨ vorwärts` / `links∧¬rechts ∨ vorwärts`.
5. Bewegung wie `Entity#move` (Blockformen + Entity-Boxen, ohne Stufensteigen), 26.3-Rückprall (horizontal 0, senkrecht nur
   Schleim × 0,8), Blockgeschwindigkeitsfaktor.
6. Passagierblick dreht mit dem Boot und bleibt in ±105° (`clampRotation`).

Grenzwerte (Unit-Tests): Wasser 0,4 Blöcke/Tick, Packeis 2 Blöcke/Tick, Halt vor Wänden ohne Hochsteigen.
Nicht umgesetzt: Blasensäulen, Zerbrechen bei Aufprall, Mitnahme anderer Entities (serverseitig), 60-Tick-Auswurf unter Wasser
(serverseitig).

## 4. Loren

Loren bewegt in 26.3 (ohne Experiment „Minecart Improvements“) der Server (`OldMinecartBehavior`); der Client interpoliert. Als
Passagier sitzt der eigene Spieler auf der Lore (Sitz PASSENGER 0,1875 − 0,6), sendet `player_input` (Vorwärts schiebt die Lore
serverseitig an) und Blickpakete; Absteigen über Schleichen. Die Darstellung nutzt die Server-Gier/-Neigung; die Schienengeometrie-
Glättung von `AbstractMinecartRenderer#oldRender` ist nicht umgesetzt (docs/55 §7).

## 5. Pferde (`physics/HorsePhysics`)

Gesatteltes Pferd mit dem eigenen Spieler als Fahrer (clientseitig autoritativ):
- Drehung = Gier des Reiters, Neigung × 0,5; Eingabe seitlich × 0,5, rückwärts × 0,25 (Tasten × 0,98).
- Boden: Blockreibung × 0,91, Beschleunigung Geschwindigkeit · 0,216/Reibung³ (Attribut `movement_speed` des Pferdes); Luft:
  Geschwindigkeit × 0,1; Schwerkraft 0,08, senkrecht × 0,98; Stufenhöhe aus dem Attribut (1,0).
- Sprung: Taste halten lädt (`jumpRidingScale`: 10 Ticks bis 1,0, danach abnehmend), Loslassen sendet `player_command` 3 mit Ladung ·
  100; Faktor ≥ 90 → 1, sonst 0,4 + 0,4 · n/90; Impuls = `jump_strength` · Faktor · Blocksprungfaktor, mit Vorwärtseingabe zusätzlich
  0,4 · Faktor in Blickrichtung.
- Vereinfacht (dokumentiert): Wasser nur Reibung 0,8 und leichter Abtrieb; Steigen/Fressen-Stillstand und Bocken nicht simuliert.
Esel, Maultiere, Skelett-/Zombiepferde nutzen dieselbe Klasse; Kamele haben eigene Regeln (Sprint-Sprung, Sitzen) und sind nicht
umgesetzt.

## 6. Entity-Kollisionen

| Fall | Vanilla-Regel | Umsetzung |
|---|---|---|
| Boote, Shulker als feste Körper | `canBeCollidedWith` → Box in `getEntityCollisions`, Teil von `Entity#collide` inkl. Stufensuche | `CollisionWorld#setEntityBoxes` (Lieferant aus `EntityManager#collisionBoxes`), ohne eigenes Fahrzeug; gilt für Spieler-, Boots- und Pferdebewegung |
| Schieben durch Lebewesen/Boote | `pushEntities` der anderen Entities → `Entity#push`: ±0,05 · min(1, 1/√d) weg | `EntityManager#pushImpulse` vor dem eigenen Physik-Tick (nicht für Zuschauer, Rüstungsständer, eigenes Fahrzeug und Mitfahrer) |
| Loren | `canBeCollidedWith` = false (nicht fest) | keine feste Box (wie Vanilla) |
| Größere Mobs | nicht fest, nur Schieben | Schieben wie oben |

Die Blockphysik aus M5/M11 ist unverändert (Entity-Boxen kommen nur hinzu, wenn Entities in der Nähe sind; die bitgleichen
Teststrecken enthalten keine Entities). Keine doppelte Berechnung: die Entity-Boxen werden einmal je Kollisionsabfrage geliefert,
Schieben einmal je Tick.
