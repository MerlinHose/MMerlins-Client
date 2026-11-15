# 35 – Entity-Architektur (M9)

**Stand:** 2026-11-12 · Protokoll: [34](34-entity-protokoll.md) · Rendering: [36](36-entity-rendering.md) · Ergebnisse: [37](37-m9-testbericht.md)

## 1. Überblick

| Schicht | Quellsatz | Klasse | Aufgabe |
|---|---|---|---|
| Daten | `main` (nur JDK) | `resources/protocol/entities-777.txt` | 161 Typen mit Maßen, Reichweite, Intervall; Datenfeld-Indizes (Laufzeitextraktion, `analysis/tools/EntityExtractor.java`) |
| Dekodierung | `main` | `protocol/packet/EntityPacketDecoder` | 21 Entity-Pakete → `Clientbound`-Records |
| Modell | `main` | `entity/EntityTypes`, `ClientEntity`, `EntityManager` | Typtabelle, Zustand je Entity, Verwaltung und Spielerliste |
| Bewegung | `main` | `entity/EntityInterpolator` | Darstellungs-Interpolation je Entity |
| Auswahl | `main` | `entity/EntityPick` | Entity- oder Blocktreffer ([34](34-entity-protokoll.md) §12) |
| Terminal | `main` | `cli/EntityCommands` | `entities`, `edump`, `etarget`, `epicks`, `players`, `estats` |
| Grafik | `render` | `gl/EntityRenderer`, `gl/NameTags`, `view/EntityModels` (main) | Modelle, Texturen, Animation, Namensschilder |

Die vorgeschlagenen Komponenten sind so zugeordnet:

| Vorschlag | Umsetzung |
|---|---|
| EntityRegistry | `EntityTypes` |
| EntityState / LivingEntity / PlayerEntity / ItemEntity | `ClientEntity`: eine Klasse; die abgeleiteten Werte (Gesundheit, Baby, Item, Skin-Teile, Pose) werden je nach Typ aus den Datenfeldern gelesen |
| EntityManager | `EntityManager` |
| EntityMetadata / EntityAttributes / EntityEquipment | Felder von `ClientEntity`: Datenfelder nach Index, Attribute nach Registry-ID, 8 Ausrüstungsplätze |
| EntityInterpolator | `EntityInterpolator` |

Die Typhierarchie ist bewusst flach. Das Protokoll unterscheidet Typen nur über die Typ-ID und die Indizes der Datenfelder; eine Klassenhierarchie würde den Mojang-Aufbau nachbilden, ohne Nutzen für den Client. Alles unterhalb von `render` ist JDK-only; der Terminalclient bleibt ohne LWJGL.

## 2. Datenfluss und Threads

```
Netzwerk-Thread ── ClientboundDecoder → EntityPacketDecoder → ClientSession.handle → EntityManager.handle (synchronized, µs)
Tick-Thread (20/s) ─ ClientSession.tick → … → client_tick_end → EntityManager.tick (Interpolation weiterschalten)
Render-Thread ───── EntityManager.snapshot(partialTick) → unveränderliche View-Liste → EntityRenderer / EntityPick / NameTags
Terminal-Thread ── EntityManager.snapshot / stats / players
```

- Alle öffentlichen Methoden des `EntityManager` sind über das Objekt synchronisiert und kurz: kein I/O, keine GPU-Aufrufe.
- Gemessen (Dauerlauf mit 100 Entities):

  | Vorgang | Durchschnitt | Maximum |
  |---|---|---|
  | Paketverarbeitung | 0,7–19 µs | 3,8 ms (erster Aufruf, Klassenladen) |
  | Tick | 3–8 µs | 0,43 ms |

- Keep-Alive und Netzwerk-Thread werden dadurch nicht blockiert; in allen Läufen blieb die Keep-Alive-Lücke ≤ 15,05 s (Server-Intervall 15 s).
- Der Renderer arbeitet nur auf der Momentaufnahme und hält die Sperre nie während des Zeichnens.

## 3. Zustand einer Entity (`ClientEntity`)

**Bestätigter Zustand (nur aus Serverpaketen):**
- Position `x/y/z` und davon getrennt die **Delta-Basis** `baseX/Y/Z` (`VecDeltaCodec`). Beide sind gleich, außer nach `teleport_entity`: Vanilla setzt dort die Basis nicht neu, und der Server sendet folgende Deltas relativ zur alten Basis ([34](34-entity-protokoll.md) §3.2, Unit-Test `teleportKeepsTheDeltaBaseLikeVanilla`).
- `yRot`, `xRot`, `headYRot`, `onGround`, Geschwindigkeit (`set_entity_motion`).
- Datenfelder nach Index (`Map<Integer, Object>`), Attribute nach ID (berechneter Wert mit Modifikatoren), 8 Ausrüstungsplätze, Fahrzeug und Passagiere.
- Ereignisse für die Darstellung: Tick des letzten Armschwungs, der letzten Schadensanimation und das letzte `entity_event`.

**Darstellung:** `EntityInterpolator` (§4). Er ändert den bestätigten Zustand nie. Die Tests vergleichen immer den bestätigten Zustand mit dem Server; die Darstellung wird nur für Auswahl und Rendering genutzt.

**Abgeleitet:**
- Hitbox nach Typ, Pose (Spieler), eigenen Baby-Maßen, Rüstungsständer-Flags und Attribut `scale`;
- `living`: alle Kategorien außer `misc`, dazu die Lebewesen aus `misc` (Spieler, Rüstungsständer, Dorfbewohner, Golems, Mannequin);
- `pickable`: Lebewesen; Spieler nur, wenn sie laut Spielerliste nicht Zuschauer sind; Rüstungsständer ohne Markierung; keine Items.

## 4. Interpolation (eigener Entwurf)

| Anforderung | Umsetzung |
|---|---|
| Interpolation zwischen Updates | Jedes Ziel ist ein Schritt mit Dauer. Lineare Deltas dauern so viele Ticks wie das Typintervall (1–3); gestufte Pfade (`VecDelta.Stepped`, `PositionPath.Stepped`) übernehmen die Tick-Abstände des Servers. Je Client-Tick rückt die Darstellung vor; gezeichnet wird zwischen vorigem und aktuellem Tick-Zustand (`partialTick`). |
| Gleichmäßige Rotation | Gier über den kürzeren Weg (Wrap ±180°), Neigung linear. Bei gestuften Pfaden mit Rotation wird die Drehung über die Schritte verteilt. |
| Kopf unabhängig | Eigenes Ziel, 3 Ticks, unabhängig vom Körper. |
| Bewegung vs. Teleport | `teleport_entity` springt sofort. `entity_position_sync` mit mehr als 8 Blöcken Abstand springt ebenfalls (größer als jedes kodierbare Delta). Abweichung: Vanilla interpoliert bis 64 Blöcke; der Sprung zeigt einen Teleport ohne Gleiten. |
| Verspätete Updates | Rückstand größer als zwei Intervalle → schneller abarbeiten (Faktor Rückstand / zwei Intervalle). Rückstand größer als 40 Ticks (2 s) → Sprung auf das letzte Ziel. |
| Begrenzte Interpolation bei fehlenden Daten | Keine Extrapolation: Ohne neue Ziele bleibt die Entity am letzten bestätigten Punkt. |
| Reproduzierbar | Rein deterministisch; gleiche Folge von Paketen und Ticks ergibt gleiche Zustände (Unit-Test `identicalInputGivesIdenticalOutput`). |

## 5. Lebenszyklus

| Ereignis | Wirkung |
|---|---|
| `add_entity` | Neu anlegen. Eine schon vorhandene ID oder UUID wird ersetzt und gezählt. Die eigene Spieler-ID wird nie geführt. |
| Pakete für unbekannte IDs | Gezählt (`unknownIdPackets`) und ignoriert (wie Vanilla) |
| `remove_entities` | Entfernen, Fahrzeug-Verknüpfungen lösen |
| `respawn` in eine andere Dimension | Alle Entities verwerfen, Spielerliste behalten (wie Vanilla) |
| `start_configuration` | Entities und Spielerliste verwerfen |
| Trennung | Die Sitzung endet; das Objekt wird mit ihr verworfen, ein neuer Login beginnt leer |
| Nicht umgesetzte Datenfelder | Gelesene Werte bleiben; der Rest des Pakets wird gezählt (`dataUnsupported`), die Verbindung bleibt |

## 6. Auswahl (`EntityPick`)

- Umsetzung der Regeln aus [34](34-entity-protokoll.md) §12.
- Die Rechenreihenfolge entspricht der dokumentierten (Strahllänge bei Fehlschlag = Reichweite, Vergleiche quadratisch, Schnitt je Ebene).
- Deshalb stimmen die Trefferpunkte **bitgenau** mit dem Vanilla-Client überein ([37](37-m9-testbericht.md) §2).

**Verwendung:**
- Grafik: Rahmen um die Hitbox, F3-Zeile „Entity-Ziel“.
- Terminal: `etarget`, `epicks`.
- Angriff und Interaktion mit Entities sind nicht umgesetzt (späterer Meilenstein).
