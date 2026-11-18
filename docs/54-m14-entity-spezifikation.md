# 54 – M14: Entity-Spezifikation und Bestandsaufnahme (Stand 2026-11-17)

M14 erweitert die Entity-Darstellung (M9, docs/34–37) um weitere Modelle, Spieler-Skins, Passagiere und Fahrzeuge. Dieses Dokument
hält fest, was vor M14 vorhanden war, welche Vanilla-26.3-Regeln untersucht wurden und woher die Daten stammen. Details stehen in
docs/55 (Modelle, Animationen), docs/56 (Skins), docs/57 (Fahrzeuge, Passagiere, Kollision) und docs/58 (Tests).

## 1. Bestand vor M14

| Bereich | Stand M9–M13 | Befund für M14 |
|---|---|---|
| `EntityManager`/`ClientEntity` | Spawn, Bewegung (Deltas, Sync, Teleport), Daten, Ausrüstung, Attribute, Schaden, `set_passengers` (nur Liste) | Passagiere wurden an ihrer eigenen Position gezeichnet; der eigene Spieler als Passagier fehlte ganz |
| Interpolation | `EntityInterpolator` (Schritte, Kopf 3 Ticks) | unverändert; Passagiere folgen jetzt dem Fahrzeug |
| Modelle | `view/EntityModels`: 5 handgeschriebene Modelle (Schwein, Kuh, Schaf, Zombie, Skelett) + Spieler + Rüstung | nicht hierarchisch genug für weitere Mobs; Babys = Erwachsene × 0,5 (in 26.3 falsch, s. §4) |
| Animation | Beine/Arme mit `sin`, eigene Phase je Bild | Vanilla nutzt `cos` und eine tickgenaue Laufphase (`WalkAnimationState`) |
| Ausrüstung | Rüstung (Helm, Brust, Hose, Stiefel), Gegenstand in der Haupthand | bleibt; Rüstung jetzt über die Rüstungs-Layer des jeweiligen Modells |
| Skins | 9 Standardskins, Auswahl `hash % 18` mit „breit zuerst“ | Reihenfolge falsch (Vanilla: erst 9 schmal), keine zweite Schicht sichtbar steuerbar, keine lokalen Skins |
| Auswahl (M7/M9) | `EntityPick` über `pickable` | Boote/Loren waren nicht auswählbar (kein Einsteigen per Rechtsklick) |
| Interaktion | `Interact` (Rechtsklick auf Entity) | vorhanden; genügt zum Einsteigen |
| Physik (M5/M11) | `PlayerPhysics`, Blockkollision bitgleich zu Vanilla | keine Entity-Kollision (Boote), kein Schieben durch Mobs (docs/45 §8) |
| Rendering (M12) | Beleuchtung, Nebel, Diffuslicht, GPU-Ressourcenzähler | bleibt; Entity-Shader um Augen-Schichten und Weiß-Überblendung erweitert |

Dargestellt als Modell waren vor M14: Schwein, Kuh, Schaf, Zombie, Skelett, Spieler, Rüstungsständer (als Spielermodell), Items.
Alle übrigen 150 Typen erschienen als magentafarbene Debug-Box in Hitbox-Größe.

## 2. Datenquellen (nur Zahlen aus dem lokalen JAR, keine übernommenen Klassen)

| Tabelle | Werkzeug | Inhalt |
|---|---|---|
| `render/entitymodels-777.txt.gz` (46 KB) | `analysis/tools/EntityModelExtractor.java` (Client-JAR, gemappt) | alle 361 Modell-Layer von `LayerDefinitions.createRoots()`: Teilbaum, Drehpunkt, Grunddrehung, Skalierung, Quader (Ursprung, Größe, Vergrößerung je Achse, Spiegelung, Texturversatz, Texturskalierung, sichtbare Seiten), Texturgröße – 3380 Teile, 2516 Quader |
| `render/entityanimations-777.txt` | derselbe Extraktor | Keyframe-Animationen (Hase: Hüpfen, Kopfneigen; Baby-Varianten), 707 Schlüssel |
| `protocol/entities-777.txt` | `analysis/tools/EntityExtractor.java` (Server-JAR) | zusätzlich: Datenfelder der neuen Typen (`D`), Ankerpunkte je Typ (`X`: PASSENGER, VEHICLE, NAME_TAG …), eigene Baby-Maße mit Ankerpunkten (`B`/`XB`), Dorfbewohner-Register (`R`) |
| Texturen, Varianten-JSON, `.mcmeta` | zur Laufzeit aus dem lokalen JAR (`--assets`) | nie in Build-Artefakte kopiert |

Der extrahierte Schweine-Layer ist bytegleich zur M9-Handumsetzung (Unit-Test `extractedPigMatchesTheM9HandWrittenModel`: identische
Vertexdaten aller Teile) – die Abwicklung blieb unverändert, nur die Zahlen kommen jetzt aus der Tabelle.

## 3. Untersuchte Vanilla-26.3-Regeln (Kurzfassung)

1. **Modellgeometrie und Hierarchie:** `PartDefinition`/`CubeDefinition`; Kinder relativ zum Elternteil, Drehung Z·Y·X, danach
   Skalierung (`ModelPart#translateAndRotate`). Sichtbare Seiten je Quader (z. B. flache Flügel).
2. **Texturzuordnung:** je Renderer fest oder über Varianten-Register (`asset_id`, `baby_asset_id`, `assets.wild/tame/angry`).
3. **Varianten:** Katze, Wolf, Schwein, Kuh, Huhn als synchronisierte Register (ID im Datenfeld, Daten in `registry_data`); Pferd
   (Farbe | Markierung ≪ 8), Hase (Typ-Index), Dorfbewohner (`villager_data`: Typ, Beruf, Stufe), Schaf (Wollfarbe).
4. **Babys:** 26.3 hat für jedes Ziel-Tier eigene Baby-Layer und eigene Baby-Texturen (`*_baby.png`). Der Renderer wählt das
   Baby-Modell (`AgeableMobRenderer`) und skaliert **nicht** zusätzlich (`state.scale` = Attribut `scale`); `ageScale` (0,5) wirkt nur
   auf Posenversätze (z. B. Sitzen) und den Schatten. Eigene Baby-Hitboxen über `getDefaultDimensions` (Tabelle `B`).
5. **Animationen:** `setupAnim` je Modell (Vierbeiner, Humanoid, Katze, Pferd, Biene …), Keyframes (Hase), clientseitige Zustände je
   Tick (Flügel, Steigen/Fressen, Aufblähen, Golem-Angriff, Hasensprung, Liegen, Rollen, Schwimmanteil).
6. **Ausrüstung:** Rüstungs-Layer je Modell (`<modell>#helmet/chestplate/leggings/boots`), Pferdesattel/-rüstung, Schweinesattel.
7. **Spieler-Skins:** `AvatarRenderer` (Skalierung 0,9375, Schleichen −2/16, Schwimmrotation), Skin-Teile (Datenfeld 16), Standardskin
   `floorMod(uuid.hashCode(), 18)`, Mannequin mit Profil und Skin-Vorgabe.
8. **Passagiere:** `set_passengers`, `positionRider` = Ankerpunkt PASSENGER des Fahrzeugs (gedreht) − Ankerpunkt VEHICLE des
   Passagiers; Sonderregeln Boot, Lore, Pferd.
9. **Fahrzeugeingaben:** Boot/gesatteltes Pferd sind beim Fahrer clientseitig autoritativ (`move_vehicle`, `paddle_boat`,
   `player_command START_RIDING_JUMP`); Loren sind serverseitig (Eingabe über `player_input`).
10. **Entity-Kollisionen:** `getEntityCollisions` (`canBeCollidedWith`: Boote, Shulker) als feste Boxen in `Entity#collide`;
    Schieben über `pushEntities`/`Entity#push` (±0,05).

## 4. Abgrenzung

- Neu als Modell (Abschlusskriterium 1): Huhn, Wolf, Katze, Ozelot, Pferd, Esel, Maultier, Hase, Biene, Creeper, Spinne,
  Höhlenspinne, Enderman, Dorfbewohner, Eisengolem, Ertrunkener, Mannequin, alle Boote/Flöße (mit/ohne Truhe), alle Loren.
- Bestehende Typen (Schwein, Kuh, Schaf, Zombie, Skelett, Spieler) laufen über dasselbe System; Kühe/Schweine/Hühner mit warmen/
  kalten Modellen.
- Nicht umgesetzte Typen bleiben Debug-Box (Verhalten wie M9).
- Die M9-Entity-Auswahl ist für alle bisherigen Typen unverändert (gleiche Hitboxen, gleiche `pickable`-Regel); neu auswählbar sind
  nur Boote und Loren, ausgenommen das eigene Fahrzeug und dessen Mitfahrer (wie `getEntityHitResult`).
