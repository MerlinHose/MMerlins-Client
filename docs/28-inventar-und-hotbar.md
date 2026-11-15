# 28 – Inventar und Hotbar (M7)

**Stand:** 2026-11-08 · Minecraft 26.3 / Protokoll 777 · Ergebnisse: [29-m7-testbericht.md](29-m7-testbericht.md).

Der Client zeigt das Inventar so, wie der Server es meldet. Er wählt nur den Hotbar-Platz. Gegenstände verschieben (`container_click`) oder im Kreativmodus erzeugen (`set_creative_mode_slot`) gehört nicht zum Umfang von M7. Das Inventarfenster ist deshalb **nur eine Anzeige**.

> **Seit M8** ist das Inventar bedienbar: Klicks, Container und Crafting, siehe [30](30-inventarprotokoll.md)–[33](33-m8-testbericht.md). Die Pakettabelle unten bleibt als M7-Stand gültig.

## 1. Pakete

| Richtung | Paket | Felder | Verwendung |
|---|---|---|---|
| ← | `container_set_content` | Container-ID (VarInt), stateId (VarInt), Liste von Item-Stapeln (VarInt-Anzahl), Cursor-Stapel | Container 0 = Spielerinventar: alle 46 Plätze |
| ← | `container_set_slot` | Container-ID, stateId, Platz (short), Stapel | ein Menüplatz |
| ← | `set_player_inventory` | Inventory-Index (VarInt), Stapel | ein Platz im **Inventory**-Index (nicht Menüplatz), z. B. bei `give`/`item replace` |
| ← | `set_cursor_item` | Stapel | Stapel am Mauszeiger |
| ← | `set_held_slot` | Platz 0–8 (VarInt) | Server setzt den gewählten Platz |
| ← | `open_screen` / `container_close` | Container-ID, Menütyp, Titel / Container-ID | nur vermerkt |
| → | `set_carried_item` | Platz (short) | gewählter Hotbar-Platz |
| → | `container_close` | 0 | beim Schließen des Inventarfensters (wie `AbstractContainerScreen#removed`) |

### 1.1 Platzbelegung (`InventoryMenu`)

| Menüplatz | Inhalt | Inventory-Index |
|---|---|---|
| 0 | Handwerksergebnis | – |
| 1–4 | Handwerksraster | – |
| 5–8 | Rüstung Kopf, Brust, Beine, Füße | 39, 38, 37, 36 (`ArmorSlot(…, 39 − i)`) |
| 9–35 | Hauptinventar | 9–35 |
| 36–44 | Hotbar 1–9 | 0–8 |
| 45 | Nebenhand | 40 |

Die Inventory-Indizes 41 (Körperrüstung) und 42 (Sattel) haben keinen Menüplatz und werden übergangen.

## 2. Item-Fakten

| Datei | Quelle | Inhalt |
|---|---|---|
| `protocol/items-777.txt.gz` | `analysis/tools/gen-item-tables.js` aus den offiziellen Datagen-Reports (`registries.json`, `components/item/*.json`) und den Block-Tags (nur lesend aus dem Server-JAR entpackt) | `I` Item-ID, Name, Stapelgröße · `T` Werkzeug: Standardgeschwindigkeit, `can_destroy_blocks_in_creative` · `R` Regeln: Geschwindigkeit, `correct_for_drops`, aufgelöste Blockliste · `D` Datenkomponenten-IDs |
| `protocol/item-blocks-777.txt.gz` | `ShapeExtractor` (2. Ausgabe): `BlockItem#getBlock` | `IB` Item → platzierter Block |

Umfang: 1 658 Items, 38 Werkzeuge, 122 Komponententypen, 1 141 Block-Items.

## 3. ItemStack-Format

`ItemStack.OPTIONAL_STREAM_CODEC`:
1. VarInt Anzahl (≤ 0 = leer).
2. VarInt Item-ID.
3. `DataComponentPatch.STREAM_CODEC`: VarInt Anzahl gesetzter Typen, VarInt Anzahl entfernter Typen.
4. Je gesetztem Typ VarInt Typ-ID und der Wert im **typeigenen Codec ohne Längenpräfix**.
5. Je entferntem Typ nur die Typ-ID.

Ohne Längenpräfix lässt sich ein unbekannter Wert **nicht überspringen**. Unterstützt sind die Codecs, die in `DataComponents` (26.3) nachgelesen wurden:

| Art | Komponenten |
|---|---|
| VarInt | `max_stack_size`, `max_damage`, `damage`, `repair_cost`, `rarity` (Enum-ID), `additional_trade_cost` |
| Unit (0 Byte) | `unbreakable`, `creative_slot_lock`, `glider`, `waxed` |
| bool / float / Identifier | `enchantment_glint_override` / `minimum_attack_charge`, `potion_duration_scale` / `item_model`, `tooltip_style`, `note_block_sound` |
| NBT (Textkomponente) | `custom_name`, `item_name`; `lore` = Liste (≤ 256) |
| NBT-Rückfall | Komponenten **ohne** eigenen Netzwerk-Codec überträgt der Server als NBT (`DataComponentType.Builder#build` → `fromCodecWithRegistries`): `custom_data`, `intangible_projectile`, `map_decorations`, `debug_stick_state`, `recipes`, `lock`, `container_loot` |
| Map | `enchantments`, `stored_enchantments`: VarInt Anzahl, je Eintrag Register-ID und VarInt Stufe |

**Nicht dekodierbar:**
- Jede andere Komponente (z. B. `written_book_content`, `attribute_modifiers`, `tool` als Patch) führt zu `UnsupportedComponentException`.
- Der Decoder verwirft dann **das ganze Paket** kontrolliert. Die Paketlänge ist durch die Rahmung bekannt, daher bleibt die Verbindung bestehen.
- Er zählt es (`PlayerInventory#unsupportedPackets`), meldet es im Log, im F3-Overlay und im Inventarfenster.
- Bis zum nächsten vollständigen Paket kann der Inhalt veraltet sein.

Ein *entfernter* Typ ist immer lesbar, weil er nur aus der Typ-ID besteht. Der Client wertet `!minecraft:tool` aus: Ohne Werkzeugkomponente gilt Handgeschwindigkeit.

## 4. Hotbar

- **Auswahl:** Tasten 1–9 oder Mausrad (nach oben = Platz links, `ScrollWheelHandler`) setzen den clientseitigen Platz.
- **Senden:** `ensureHasSentCarriedItem` sendet `set_carried_item`, sobald sich der Platz vom zuletzt gesendeten `carriedIndex` unterscheidet. Das passiert am Tickanfang (`MultiPlayerGameMode#tick`) sowie vor `continueDestroyBlock`, `useItemOn` und `useItem`.
- **Reihenfolge:** Wie in Vanilla kann ein `START_DESTROY` im selben Tick noch vor dem `set_carried_item` stehen, denn `startDestroyBlock` ruft `ensureHasSentCarriedItem` nicht auf.
- **Vom Server gesetzt** (`set_held_slot`): Der Platz wird übernommen. Weil `carriedIndex` unverändert bleibt, bestätigt der Client ihn im nächsten Tick mit `set_carried_item`. Das entspricht Vanilla.
- **Hand-Item:** Für Abbau, Werkzeugregeln und `sameDestroyTarget` gilt der Stapel des gewählten Platzes (Menüplatz 36 + Platz). Ein Wechsel von Platz oder Item während des Abbaus bricht ab (ABORT) und startet neu, wie `MultiPlayerGameMode#sameDestroyTarget` (`isSameItemSameComponents`).

## 5. Anzeige

**Hotbar** (`gl/Hud`, eigene Darstellung ohne Mojang-Grafiken):
- neun Felder unten mittig, das gewählte Feld hell umrandet;
- je Feld der Kurzname des Items, gelb die Anzahl, rot der Schaden (`d1` …);
- darüber der volle Name des gewählten Items, mit Hinweis „verzaubert“, wenn vorhanden.

**Inventarfenster (E):**
- Rüstung, Nebenhand und Cursor;
- drei Zeilen Hauptinventar und die Hotbar;
- Kopfzeile mit Server-stateId und Hinweis auf nicht dekodierbare Pakete;
- während es offen ist: Maus frei, keine Klicks, `missTime = 10000` wie in Vanilla.

**Neuzeichnen:** Die Texte werden nur neu gezeichnet und hochgeladen, wenn sich Inventar, Auswahl oder Fenstergröße ändern.

## 6. Nicht umgesetzt

- Gegenstände verschieben, aufheben, ablegen, werfen (`container_click`, `player_action DROP_*`, Q-Taste);
- Kreativ-Inventar (`set_creative_mode_slot`);
- Behälter-Fenster (Truhe usw.);
- Item-Symbole/Texturen;
- Haltbarkeitsbalken (die Standard-`max_damage` steht nicht in der Tabelle).
