# 32 – Container und Crafting (M8)

**Stand:** 2026-11-08 · Protokoll: [30](30-inventarprotokoll.md) · Architektur: [31](31-inventararchitektur.md) · Ergebnisse: [33](33-m8-testbericht.md)

## 1. Öffnen und Schließen

1. Ein Rechtsklick auf den Block (`use_item_on`, M7) führt dazu, dass der Server `open_screen` sendet (Container-ID 1–100, zyklisch, Menütyp, Titel). Danach folgen `container_set_content` und, bei Öfen, `container_set_data`.
2. Der Client legt eine `ContainerView` an (Art aus dem Menütyp, Tabelle §2) und öffnet das Fenster.
3. Geschlossen wird mit E/Esc → `container_close`, oder serverseitig, wenn der Block entfernt wird oder zu weit entfernt ist → Client erhält `container_close`. In beiden Fällen gilt danach wieder Container 0.
4. Gesten, die noch für den alten Container in der Warteschlange stehen, werden verworfen ([31](31-inventararchitektur.md) §4).

## 2. Menütypen und Platzbelegung

Menütyp-IDs aus der Registry `menu` (Reihenfolge in `world/inventory/MenuType.java`):

| ID | Menü | eigene Plätze | Spielerplätze (Inventar / Hotbar) | Umsetzung |
|---|---|---|---|---|
| 0–5 | `generic_9x1` … `generic_9x6` | 9·Reihen | danach 27 / 9 | Truhe, große Truhe, Fass, Endertruhe |
| 6 | `generic_3x3` | 9 | 9–35 / 36–44 | Spender, Werfer |
| 10, 14, 22 | `blast_furnace`, `furnace`, `smoker` | 0 Eingabe, 1 Brennstoff, 2 Ergebnis | 3–29 / 30–38 | mit Fortschritt (Daten 0–3) |
| 12 | `crafting` | 0 Ergebnis, 1–9 Raster | 10–36 / 37–45 | 3×3 |
| 16 | `hopper` | 5 | 5–31 / 32–40 | |
| 20 | `shulker_box` | 27 | 27–53 / 54–62 | |
| sonst | Amboss, Webstuhl, Händler … | aus der Gesamtgröße von `set_content` (Gesamtzahl − 36) | letzte 36 | generisches Raster, Klicks funktionieren, keine eigene Darstellung |

**Große Truhe:** Zwei Truhen mit `type=right` und `type=left` bilden ein `generic_9x6`. Die erste Hälfte (Plätze 0–26) ist die Truhe mit `type=right`; in der Testwelt mit Blick nach Süden ist das x = 3. Die zweite Hälfte (27–53) ist die Truhe bei x = 4. Der Test prüft beide Blöcke einzeln mit `data get block` (`check_container2`).

**Ofendaten** (`container_set_data`): 0 verbleibende Brenndauer, 1 Brenndauer gesamt, 2 Kochfortschritt, 3 Kochdauer gesamt. Die GUI zeichnet daraus Flamme und Pfeil.

## 3. Übertragen zwischen Container und Inventar

Alle Übertragungen sind normale Klicks: PICKUP/PICKUP, Shift-Klick (QUICK_MOVE), Rechtsklick-Teilen und Ziehen. Wohin ein Shift-Klick einen Stapel bringt, entscheidet allein der Server (`quickMoveStack` des jeweiligen Menüs). Ein Ofen sortiert zum Beispiel Kohle in den Brennstoffplatz und Erz in den Eingabeplatz. Der Client zeigt nur das Ergebnis an.

## 4. Crafting

- **2×2 im Spielerinventar** (Container 0, Plätze 1–4, Ergebnis 0) und **3×3 an der Werkbank** (Container `crafting`, Plätze 1–9, Ergebnis 0).
- Der Client berechnet **keine Rezepte**. Das Ergebnis erscheint erst, wenn der Server es mit `container_set_slot` für Platz 0 meldet. Ein Klick auf einen leeren Ergebnisplatz bleibt beim Server wirkungslos. Der Ergebnisplatz ist kein Ziel für Ziehvorgänge (wie in Vanilla).
- Ein Klick auf das Ergebnis legt es auf den Cursor; ein Shift-Klick craftet wiederholt, bis die Zutaten aufgebraucht sind. Beides führt der Server aus, und der Client übernimmt die gemeldeten Raster-, Inventar- und Cursorplätze.
- Beim Schließen der Werkbank gibt der Server die Rasterinhalte ins Inventar zurück (`AbstractContainerMenu#dropOrPlaceInInventory`). Ist das Inventar voll oder der Spieler getrennt, wirft er sie aus. Der Test prüft, dass dabei kein Item-Entity entsteht.
- Das Rezeptbuch (`place_recipe`, `recipe_book_*`) ist nicht umgesetzt.

## 5. Grenzen

| Bereich | Stand |
|---|---|
| Rezeptbuch, Kreativ-Katalog | nicht umgesetzt |
| Amboss, Verzauberungstisch, Webstuhl, Händler, Brauen, Schmieden, Crafter … | Klicks funktionieren generisch; keine eigene Darstellung, keine `container_button_click` / Textfelder (`rename_item`) |
| Komponenten ohne eigenen Decoder (z. B. `written_book_content`) | Paket wird gezählt und verworfen, die Verbindung bleibt; der Zustand stimmt erst wieder nach dem nächsten Vollabgleich |
| Durchsatz | eine Geste je 2 Ticks (≈ 10/s) |
