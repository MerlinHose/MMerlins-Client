# 43 – Fluid- und Kletterphysik (M11)

Regeln für Wasser, Lava, Strömung, Schwimmen, Tauchen, Leitern, Ranken und Gerüste in Vanilla 26.3 und ihre Umsetzung im eigenen Client. Quellen: Loom-Quellen, `Klasse#Methode`. Messungen: docs/45.

**Umsetzung:**
- `physics/FluidModel` (Fluid je Block, Höhe, Kamerahöhe, Strömung) liest den bestehenden Weltzustand über `BlockView`. Es gibt keine zweite Weltverwaltung.
- `physics/BlockMovementFacts`: Fluidzustand und Tags je Blockzustand aus der Faktentabelle (docs/42 §7).
- `physics/PlayerPhysics`: Tickablauf (docs/42 §1) und Bewegung.

## 1. Fluidzustand je Blockzustand

Der Fluidzustand ist eine Eigenschaft des **Blockzustands** (`BlockState#getFluidState`). Er wird nicht pauschal aus dem Blocknamen abgeleitet.

| Blockzustand | Fluid | Quelle der Angabe |
|---|---|---|
| `water[level=0]` | Wasser, Menge 8, Quelle | Zeile `M … W8S …` |
| `water[level=1…7]` | Wasser, Menge 8 − level, fließend | `W7` … `W1` |
| `water[level=8…15]` | Wasser, Menge 8, fallend | `W8F` |
| `*[waterlogged=true]` (Stufen, Treppen, Zäune …) | Wasserquelle (Menge 8) | `W8S` |
| `copper_grate[waterlogged=true]` u. a. | Wasserquelle, fallend markiert | `W8SF` |
| Seetang, Seegras, Blasensäule | Wasserquelle | `W8S` |
| `lava[level=…]` | wie Wasser mit `L` | `L…` |

Aus der Faktentabelle: 13 350 Zustände `W8S`, je 8 fallende Wasser- und Lavazustände, je 7 fließende Stufen.

**Höhe** (`FlowingFluid#getHeight`): 1F, wenn darüber dasselbe Fluid liegt; sonst Menge / 9F. Eine Quelle unter Luft ist also 0,888… hoch, nicht 1.

**Kamerahöhe** (`FluidState#getHeightForCamera`): bei einer Quelle unter einem Block mit tragfähiger Unterseite 1F, sonst die Höhe. Sie gilt für „Augen im Fluid“.

## 2. Fluid-Update je Tick (`EntityFluidInteraction#update`, aus `Entity#baseTick`)

1. Box = Hitbox um 0,001 verkleinert. Zellen `floor(min) … ceil(max) − 1` je Achse.
2. **Laden:** Ist ein Chunk im Bereich x0−1 … x1+1 / z0−1 … z1+1 nicht geladen, gilt „kein Fluid“.
3. Je Zelle mit Fluid, deren Oberkante ≥ Unterkante der Box:
   - Höhe je Fluidart = max(Oberkante − Hitbox-Unterkante).
   - **Augen:** Zelle in der Augenspalte (`floor(x)`, `floor(z)`) und Augenhöhe zwischen Zellboden und Zellboden + Kamerahöhe.
   - **Strömung:** `getFlow` der Zelle. Ist die bisherige Höhe < 0,4, wird sie mit der Höhe skaliert. Dann aufsummieren und zählen.
4. **Strömungsimpuls** für Spieler: Summe / Anzahl × 0,014 (Wasser) bzw. × 0,0023333 (Lava, Oberwelt; Nether 0,007 nicht umgesetzt).
   - Bei |vx|, |vz| < 0,003 und Impuls < 0,0045 wird er auf 0,0045 normiert.
   - Nur wenn |Summe|² ≥ 1e−5F.
5. Im Wasser: Fallstrecke 0. `isInWater` = Wasserhöhe > 0. `isInLava` = Lavahöhe > 0, nicht im ersten Tick.
6. In Lava: Fallstrecke × 0,5 (`baseTick`).

**Strömungsrichtung** (`FlowingFluid#getFlow`):
- Für jede horizontale Richtung (N, O, S, W):
  - Hat der Nachbar die gleiche Fluidart oder kein Fluid, ist der Abstand die eigene Höhe minus die Nachbarhöhe.
  - Ohne Fluid im Nachbarn und ohne Tag `blocks_fluid_flow`: Nachbar darunter, Abstand eigene − (unten − 0,8888889F).
- Die Summe der Abstände (in `float` je Richtung) wird normiert.
- Bei fallendem Fluid an einer festen Seite (`isFaceSturdy`, Eis ausgenommen) zusätzlich (0, −6, 0).

## 3. Schwimmen, Tauchen, Auftrieb

| Regel | Quelle |
|---|---|
| **Unter Wasser** (`LocalPlayer#isUnderWater`) = Augen im Wasser zu Tickbeginn (Stand des vorigen Fluid-Updates) | `Player#updateIsUnderwater` |
| **Seichtes Wasser** = im Wasser, aber nicht unter Wasser → kein Sprintbeginn, Sprint endet | `LocalPlayer#isSprintingPossible` |
| **Schwimmen beginnt**: sprintend, unter Wasser, Block an der Position ist Wasser | `Entity#updateSwimming` |
| **Schwimmen endet**: kein Sprint oder nicht im Wasser | `Entity#updateSwimming` |
| Schwimmpose SWIMMING (0,6 hoch, Augen 0,4) | `Player#updatePlayerPose` |
| **Abtauchen**: im Wasser mit Shift → vy − 0,04F | `LocalPlayer#aiStep`, `#goDownInWater` |
| **Auftrieb**: Sprungtaste im Wasser (Höhe > Sprungschwelle 0,4 bzw. 0 bei Augenhöhe < 0,4) → vy + 0,04F; in Lava ebenso | `LivingEntity#aiStep`, `#jumpInLiquid` |
| **Sprung aus seichtem Fluid**: am Boden und Höhe ≤ Schwelle → normaler Sprung | `LivingEntity#aiStep` |
| **Schwimmrichtung**: lookY = −sin(Neigung). Faktor 0,085 bei lookY < −0,2, sonst 0,06. vy += (lookY − vy) · Faktor, wenn lookY ≤ 0, gesprungen wird oder 0,9 über den Füßen Fluid ist | `Player#travel` |

## 4. Bewegung im Fluid (`LivingEntity#travelInFluid`)

**Wasser** (`travelInWater`):
```
widerstand = sprintend ? 0,9F : 0,8F ; tempo = 0,02F
ww = water_movement_efficiency (in der Luft · 0,5F)
falls ww > 0: widerstand += (0,54600006F − widerstand)·ww ; tempo += (Bewegungstempo − tempo)·ww
Delfinsgunst: widerstand = 0,96F
moveRelative(tempo); move
an Kletterblock mit horizontaler Kollision: vy = 0,2
v = (vx·widerstand, vy·0,8F, vz·widerstand)
Fallausgleich (nicht sprintend, Schwerkraft ≠ 0): vy −= g/16; fallend mit |vy − g/16| < 0,003 und |vy − 0,005| ≥ 0,003 → vy = −0,003
jumpOutOfFluid: horizontale Kollision und Box frei bei (vx, vy + 0,6F − y + y_alt, vz) (ohne Kollision und ohne Flüssigkeit) → vy = 0,3F
```

**Lava** (`travelInLava`):
```
moveRelative(0,02F); move
seicht (Höhe ≤ Schwelle): v = (vx·0,5, vy·0,8F, vz·0,5), dann Fallausgleich ; sonst v · 0,5
vy −= g/4 ; jumpOutOfFluid
```

**Wahl:** `travelInFluid`, wenn im Wasser oder in Lava (`shouldTravelInFluid`). Sonst `travelInAir`.

## 5. Klettern (`LivingEntity#onClimbable`, `#handleOnClimbable`)

**Kletterblock:** Zustand an `blockPosition` mit Tag `climbable`. Das sind Leiter, Ranke, Gerüst, Trauer-, Zwirbel- und Höhlenranken samt Pflanzenteilen. Ebenso eine offene Falltür über einer gleich ausgerichteten Leiter.

**Vor der Bewegung:**
- Fallstrecke 0.
- vx und vz auf ±0,15F begrenzt.
- vy ≥ −0,15F.
- Mit Shift: vy = 0, wenn es negativ wäre. Im Gerüst gilt das nicht.

**Nach der Bewegung:** Bei horizontaler Kollision oder gedrückter Sprungtaste ist vy = 0,2. Danach folgen wie gewohnt Schwerkraft und Widerstand.

**Gerüst:** Die Kollisionsform hängt vom Kontext ab (docs/21 §6). Shift (`isDescending`) lässt den Spieler durch das obere Brett fallen.

## 6. Umsetzung und Grenzen

- **Blasensäulen und Spinnennetze:** docs/44.
- **Pulverschnee:** Klettern mit Lederstiefeln ist nicht umgesetzt, weil die Ausrüstung in der Physik fehlt.
- **Schnelle Lava im Nether** (0,007) ist nicht umgesetzt. Die Strecken liegen in der Oberwelt.
- **Ungeladene Chunks:** Liegt ein Chunk im Bereich nicht vor, gilt der Spieler wie in Vanilla als nicht im Fluid.
- **Grafik:**
  - Die Augenhöhe der Kamera folgt der Pose mit Glättung je Tick: `eye += (Ziel − eye) · 0,5F`, gerendert interpoliert (`Camera#tick`).
  - Unter Wasser hängt die Sichtweite von `waterVisionTime` ab (`LocalPlayer#getWaterVision`, `WaterFogEnvironment`). Siehe docs/45 §6.
