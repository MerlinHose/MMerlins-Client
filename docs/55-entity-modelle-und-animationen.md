# 55 – Entity-Modelle und Animationen (M14, Stand 2026-11-17)

## 1. Architektur

```
entity/ (ohne LWJGL)                        view/model/ (ohne LWJGL)                      gl/ (Grafikthread)
  ClientEntity ── EntityAnimationState         ModelLibrary  (Layer, Teile, Quader)          EntityRenderer
  EntityManager ── View (+ anim, passengers)   ModelPose     (veränderliche Pose)    ──▶       Meshes je Layer/Teil (einmalig)
  EntityVariants (registry_data)               ModelMesher   (Abwicklung → Vertexdaten)        Texturen (JAR, Skins)
  PassengerSeats                               KeyframeLibrary                                Shader: Licht, Nebel, Augen, Weiß
                                               EntityLooks   (Darstellungsplan)
                                               EntityAnimators (Posenfunktionen)
view/skin/SkinManager (ohne LWJGL)
```

- **Datenverwaltung** (Entity-Zustand, Varianten, Animationszustände, Sitzpositionen) ist unabhängig von LWJGL und vollständig per
  Unit-Test prüfbar; der Terminalclient bleibt ohne LWJGL lauffähig.
- **GPU-Ressourcen** entstehen nur im Grafikthread: je Modell-Layer und Teil genau ein Mesh beim ersten Gebrauch, je Textur ein
  GL-Objekt; pro Bild werden nur Matrizen und Farben gesetzt. `close()` gibt alles frei (Zähler `GlResources`).

## 2. Modellsystem (`ModelLibrary`, `ModelPose`, `ModelMesher`)

- Ein Modell-Layer (`minecraft:wolf_baby#main`) besteht aus Teilen in Tiefensuche ab `root` (Eltern vor Kindern). Jedes Teil hat
  Drehpunkt, Grunddrehung, Skalierung und Quader.
- `ModelPose` stellt mit `reset()` die Grundpose her (wie `ModelPart#resetPose`), Animationen setzen/addieren Werte; `matrices()`
  liefert je Teil Wurzel → Teil (Verschiebung, Drehung Z·Y·X, Skalierung) – **Eltern-Kind-Beziehungen** wirken dadurch automatisch
  (z. B. Ärmel folgen dem Arm, Ohren dem Kopf, Kopfteile des Pferdes der Kopfgruppe). Sichtbarkeit wird vererbt.
- `ModelMesher`: eigene Kastenabwicklung aus M9, erweitert um Vergrößerung je Achse, Texturskalierung und sichtbare Seiten
  (Seitenbits im Modellraum mit y nach unten; bei Spiegelung tauschen West/Ost ihre Bits).
- **Mehrere Texturschichten**: ein Darstellungsplan (`EntityLooks.Look`) besteht aus Schichten (Modell-Layer + Textur + Farbe + Art);
  alle Schichten nutzen dieselbe Posenfunktion, damit z. B. Wolle, Halsband, Sattel, Rüstung exakt mitlaufen.
- Schichtarten: `CUTOUT` (normal), `EYES` (Spinne, Enderman: ohne Licht, additiv, ohne Tiefenschreiben), `TRANSLUCENT`
  (aufgeladener Creeper, halbtransparent).

## 3. Darstellungspläne (`EntityLooks`)

| Typ | Modell (Erwachsen / Baby) | Textur | Zusatzschichten |
|---|---|---|---|
| Huhn | `chicken`, `cold_chicken` / `chicken_baby` | Variante `asset_id`/`baby_asset_id` | – |
| Wolf | `wolf` / `wolf_baby` | Variante `assets.wild/tame/angry` bzw. `baby_assets` | Halsband (zahm, Farbstofffarbe) |
| Katze / Ozelot | `cat`/`ocelot` / `*_baby` | Variante bzw. `ocelot(_baby)` | Halsband-Layer `cat#collar` (zahm) |
| Pferd | `horse` / `horse_baby` | `horse_<farbe>(_baby)` aus Variante & 0xFF | Markierung (Variante ≫ 8), Sattel `horse#saddle`, Rüstung `horse_armor#main` |
| Esel, Maultier | `donkey`/`mule` / `*_baby` | `donkey`/`mule(_baby)` | Truhen-Teile sichtbar mit Truhe (Datenfeld 19), Sattel |
| Hase | `rabbit` / `rabbit_baby` | Typ 0–5, 99 (Killerhase), Name „Toast“ | – |
| Biene | `bee` / `bee_baby` | wütend/Nektar/Baby-Kombination | Stachel sichtbar bis gestochen |
| Creeper | `creeper` | `creeper` | aufgeladen: `creeper#armor` halbtransparent; Skalierung + Weißblitz beim Aufblähen (`CreeperRenderer#scale`) |
| Spinne, Höhlenspinne | `spider` (Höhlenspinne × 0,7) | `spider`/`cave_spider` | Augen (`EYES`) |
| Enderman | `enderman` | `enderman` | Augen (`EYES`), getragener Block (vereinfachter Würfel, §7) |
| Dorfbewohner | `villager` / `villager_baby` | `villager(_baby)` | Typ (`type/` bzw. `baby/`), Beruf, Stufe; Hut-Regel aus `.mcmeta` (`full`/`partial`) wählt den Layer ohne Hut |
| Eisengolem | `iron_golem` | `iron_golem` | Risse (`crackiness_low/medium/high` bei < 75/50/25 % Gesundheit) |
| Ertrunkener | `drowned` / `drowned_baby` | `drowned(_baby)` | äußere Schicht `#outer`, Rüstung |
| Schwein, Kuh | `pig`, `cold_pig` / `pig_baby`; `cow`, `warm_cow`, `cold_cow` / `*_baby` | Variante | Schweinesattel |
| Schaf | `sheep` / `sheep_baby` | `sheep(_baby)` | Unterwolle (nicht weiß, nur Erwachsene), Wolle (nicht geschoren), Farbstofffarbe |
| Zombie, Skelett | `zombie` / `zombie_baby`; `skeleton` | `zombie(_baby)`, `skeleton` | Rüstung, Gegenstand |
| Spieler, Mannequin | `player` / `player_slim` | Skin (docs/56) | Rüstung, Gegenstand; Skalierung 0,9375 |
| Boote, Flöße | `boat/<holz>`, `chest_boat/<holz>` | `entity/boat/<holz>`, `entity/chest_boat/<holz>` | Paddel animiert |
| Loren | `minecart` | `entity/minecart/minecart` | Blockinhalt (vereinfacht) |

Varianten: Die Register-IDs der Entity-Datenfelder werden über `registry_data` aufgelöst (`EntityVariants`). Hat der Server die
Einträge ohne Daten gesendet (Known Packs angenommen), liest der Renderer die JSON aus dem lokalen Datenpaket
(`data/minecraft/<register>/<name>.json`). Unbekannte IDs → Standardtextur (temperate/pale/tabby).

## 4. Animationen (`EntityAnimationState`, `EntityAnimators`)

- **Laufanimation** tickgenau wie `WalkAnimationState`: Zielgeschwindigkeit min(4 · Weg, 1), Faktor 0,4, Position += Geschwindigkeit,
  Babys Positionsfaktor 3; Passagiere und Tote stehen. Darstellung mit Teiltick: Position − Geschwindigkeit · (1 − pt).
  (M9 nutzte eine eigene Bildrate-abhängige Näherung mit `sin`; M14 folgt der Vanilla-Formel mit `cos`.)
- **Kopf:** Gier relativ zum Körper, Neigung; Pferde begrenzen auf ±20°.
- **Vierbeiner** (Schwein, Kuh, Schaf, Creeper): `cos(pos · 0,6662) · 1,4 · speed`, diagonal gegenphasig.
- **Huhn:** Beine + Flügelschlag (`flap`, `flapSpeed`, in der Luft schneller).
- **Wolf:** Beine, Schwanz (wütend/zahm/wild: Winkel nach Gesundheit), Sitzen (Körper, Hinterbeine, Oberkörper bzw. Baby-Variante).
- **Katze/Ozelot:** Gehen, Sprinten, Schleichen, Sitzen (Erwachsen/Baby verschieden), Liegen (Kopf/Beine/Schwanz interpoliert).
- **Pferd:** Kopfgruppe mit Fressen/Steigen, Vorderbeine beim Steigen, Schwanz mit Geschwindigkeit; Baby mit eigenen Versätzen.
- **Hase:** Keyframe-Animation `HOP` (Ereignis 1 startet einen 15-Tick-Sprung), eigene Auswertung (linear, Catmull-Rom).
- **Biene:** Flügelschlag im Flug, Schweben (bob), Fühler, Rollen.
- **Spinne:** acht Beine mit Schritt-/Schwingphasen.
- **Enderman:** Humanoid mit halber, begrenzter Armbewegung, Arme vorn mit Block, offener Mund (`creepy`).
- **Dorfbewohner:** Beine, Kopfschütteln bei Unzufriedenheit.
- **Eisengolem:** Dreieckswelle für Arme/Beine, Angriffsschwung (Ereignis 4), Blume anbieten (Ereignis 11).
- **Humanoid** (Zombie, Ertrunkener, Skelett, Spieler, Mannequin): Gehen, Passagierhaltung, Gegenstand (`ITEM`-Haltung), Schlag
  (`WHACK`), Schleichen, Armwippen, Schwimmen; Zombie-/Ertrunkenen-Arme (angriffslustig höher).
- **Boot:** Paddel aus den Ruderflaggen (Datenfelder 11/12).

## 5. Babys: Darstellung, Hitbox, Augenhöhe, Daten, Animation

| Aspekt | Quelle | Umsetzung |
|---|---|---|
| Visuelle Skalierung | eigenes Baby-Modell | Baby-Layer, **keine** zusätzliche Verkleinerung (M9 hatte × 0,5) |
| Hitbox | `getDefaultDimensions` (Tabelle `B`), sonst Typmaße × 0,5 (Esel/Maultier, Biene), Skelett-/Zombiepferd × 0,7 | `ClientEntity#dimensions`, Werte für Schwein/Kuh/Schaf/Zombie unverändert gegenüber M9 |
| Augenhöhe | Tabelle `B` | in der Tabelle hinterlegt |
| Metadaten | Datenfeld 16 (`DATA_BABY_ID`) | `ClientEntity#baby()` für alle Ziel-Typen |
| Animation | `ageScale` 0,5 für Posenversätze, Lauf-Positionsfaktor 3 | `EntityAnimators.Input#ageScale`, `EntityAnimationState` |

## 6. Renderer-Transformation (Lebewesen)

Position → Skalierung (Attribut) → Drehung 180° − Körpergier → (Spieler: Schwimmneigung, Schleichversatz) → Spiegelung (−1, −1, 1) →
`scale()` des Renderers (Creeper, Höhlenspinne, Spieler 0,9375) → −1,501 nach unten → 1/16 (Pixel) → Teilmatrix.
Boote: +0,375, Drehung 180° − Gier, Schadenswackeln, Spiegelung, +90°. Loren: +0,375, Gier, Neigung, Schadenswackeln, Spiegelung.

## 7. Bekannte Abweichungen

- Getragene Blöcke (Enderman) und Loreninhalte werden als Würfel mit einer Blocktextur gezeichnet (Truhe: Eichenbretter), nicht mit
  dem Blockmodell.
- Aufgeladener Creeper: statisch halbtransparent statt wandernder Energie-Textur.
- Wolfsrüstung, Lamadecken, Halsband-Schütteln (nass), Dorfbewohner-Blume des Golems, gekreuzte Arme mit Gegenstand des
  Dorfbewohners: nicht gezeichnet.
- Lederrüstung des Pferdes ohne Färbung.
- Lore: Drehung aus Gier/Neigung des Servers, nicht aus der Schienengeometrie (`OldMinecartBehavior#getPos`).
- Todesanimation (Kippen) wie M9 nicht umgesetzt.
