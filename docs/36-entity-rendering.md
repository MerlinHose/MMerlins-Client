# 36 – Entity-Rendering (M9)

**Stand:** 2026-11-12 · Architektur: [35](35-entity-architektur.md) · Ergebnisse: [37](37-m9-testbericht.md)

## 1. Ressourcen

- **Texturen:** Sie werden zur Laufzeit aus dem **lokalen** Client-JAR (`--assets`, Loom-Cache) gelesen: `assets/minecraft/textures/entity/…`, `item/…`, `block/…`. Es wird nichts in Build-Artefakte kopiert; die Screenshots mit diesen Texturen bleiben in `analysis/runtime-logs/` (gitignored).
- **Modellgeometrie:** In Minecraft ist sie Code (`LayerDefinition`), nicht JSON. `view/EntityModels` ist eine eigene Beschreibung aus Teilen und Quadern.
  - Abgelesen wurden nur Zahlen: Quadergrößen, Texturversätze, Drehpunkte (`client/model/…/PigModel.java`, `CowModel.java`, `SheepModel.java`, `SheepFurModel.java`, `QuadrupedModel.java`, `HumanoidModel.java`, `PlayerModel.java`, `SkeletonModel.java`).
  - Abwicklung, Aufbau, Animation und Shader sind eigener Code.

## 2. Umgesetzte Typen

| Typ | Modell | Textur | Besonderheiten |
|---|---|---|---|
| Spieler | Humanoid 64 × 64 mit Überzügen (Hut, Jacke, Ärmel, Hose), breit oder schmal | Standard-Skins `entity/player/{wide,slim}/<name>.png` | Auswahl nach `floorMod(uuid.hashCode(), 18)`; Namensschild |
| Schwein | Vierbeiner, Beine 6 | `pig/pig_temperate.png` | |
| Kuh | Vierbeiner, Hörner, Euter | `cow/cow_temperate.png` | |
| Schaf | Vierbeiner + Wollschicht (Vergrößerung 0,5–1,75) | `sheep/sheep.png` + `sheep_wool.png` | Wollfarbe aus Datenfeld 18; geschoren ohne Wolle |
| Zombie | Humanoid 64 × 64, Arme vorgestreckt | `zombie/zombie.png` | Baby: verkleinert |
| Skelett | Humanoid mit dünnen Gliedern, 64 × 32 | `skeleton/skeleton.png` | |
| Item am Boden | Bild-Quad (Item-Textur) oder kleiner Würfel (Blocktextur) | `item/<name>.png`, sonst `block/<name>.png` | dreht sich, wippt |
| alle anderen 154 Typen | **Debug-Box** in Hitbox-Größe, magenta | – | auch bei fehlender Textur |

**Ausrüstung** (Humanoide):
- Helm, Brustpanzer und Stiefel aus `equipment/humanoid/<material>.png` (äußere Schicht, Vergrößerung 1,0);
- Hose aus `equipment/humanoid_leggings/<material>.png` (0,5);
- Material aus dem Itemnamen (`iron_helmet` → `iron`, `golden_*` → `gold`);
- Haupthand als Item-Quad an der rechten Hand.

## 3. Transformation

**Modellraum:** Einheit 1/16 Block, y nach unten, Boden bei y = 24, Vorderseite −z.

**Kette je Entity** (kamerarelativ wie die Chunks, Präzision bei großen Koordinaten):

```
T(Position − Kamera) · R_y(180° − Körpergier) · S(−s, −s, s) · T(0, −1,501, 0) · S(1/16) · [Teil: T(Drehpunkt) · R_z · R_y · R_x]
```

- s ist das Attribut `scale` (× 0,5 bei Babys).
- Die Ausrichtung wurde mit einer eigenen Szene geprüft (`analysis/tools/m9-orient.txt`): Gier 0 blickt zur Kamera (+Z), Gier 90 nach −X, Gesicht und Arme der Zombies passen.

## 4. Animation (eigene Formeln)

| Bewegung | Formel |
|---|---|
| Laufen | Phase += Weg/Tick · 4; Stärke folgt min(1, Weg/Tick · 4) weich; Beine ±sin(Phase · 0,66) · 1,2 · Stärke, Arme gegenläufig · 0,8 |
| Kopf | Gier = Kopf − Körper, Neigung = xRot |
| Armschwung (`swing_animation`) | rechter Arm −sin(Fortschritt · π) · 1,2 über 6 Ticks |
| Schaden (`hurt_animation`) | 10 Ticks rötlich |
| Zombie | Arme fest vorgestreckt (−1,45 rad) |
| Unsichtbar (Flag 0x20) | nicht gezeichnet |

**Licht:** max(Himmels-, Blocklicht) am Körpermittelpunkt aus dem eigenen Lichtmodell (M7), dieselbe Helligkeitskurve wie die Chunks. Nebel wie bei der Welt.

## 5. Spieler

- **Skins:** Es gibt keine Downloads und keine externen Verbindungen. Eine vom Server gelieferte Eigenschaft `textures` würde nur vermerkt (`PlayerInfo#hasSkinProperty`), nie geladen. Mit `online-mode=false` sendet der lokale Server keine (beobachtet: `skinProperty=false` für beide Testspieler). Stattdessen wird ein lokaler Standard-Skin verwendet.
- **Namensschild:** Name aus der Spielerliste (oder eigener Name der Entity) über dem Kopf, bis 32 Blöcke. Eine Textur je Name, höchstens 24 gleichzeitig.
- **Hitbox und Pose:** Die Hitbox folgt der Pose (schleichen 1,5, schwimmen/Elytra 0,6, schlafen 0,2). Das Modell zeigt nur die Standpose.

## 6. Ressourcen und Leistung

- **GPU-Objekte:**
  - je Modellteil ein einmal hochgeladenes Mesh (VAO + VBO), je Texturpfad eine Textur, ein Shader, ein Debug-Würfel, ein Item-Quad, ein Texturwürfel;
  - pro Bild werden nur Uniforms gesetzt, keine Objekte erzeugt;
  - alles wird über `close()` freigegeben und von `GlResources` gezählt: in allen Läufen 0 offene Objekte beim Beenden.
- **Frustum-Culling:** je Entity mit der um 0,5 vergrößerten Hitbox.
- **Zeichenaufrufe:** etwa 6 je Vierbeiner, 6–12 je Humanoid (mit Rüstung).

**Grenzen:**
- Eigene Baby-Modelle und -Texturen (`pig_temperate_baby.png` …) sind nicht umgesetzt; Babys zeigen das verkleinerte Erwachsenenmodell.
- Kalte und warme Schweine- und Kuhvarianten haben in 26.3 eigene Geometrie (Fell, andere Hörner). Gezeigt wird immer die gemäßigte Variante; die Varianten-ID ist im Datenfeld vorhanden, wird aber nicht ausgewertet.
- Animationsdetails wie Körperdrehung beim Laufen, Bogen-Zielhaltung, Schwimm- und Schlafpose werden nicht dargestellt.
- Rüstungsverzauberungsglanz und Lederfarbe fehlen.
- Gegenstände in der Hand werden nur als flaches Bild gezeigt.
