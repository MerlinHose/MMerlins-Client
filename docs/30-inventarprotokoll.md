# 30 – Inventarprotokoll (M8)

**Stand:** 2026-11-08 · Minecraft 26.3 / Protokoll 777
**Quellen:** Loom-Quellen in `analysis/mc-src/` (Referenzformat wie in [README](README.md)); Paket-IDs aus `client/src/main/resources/protocol/packets-777.txt` (identisch mit `packets.json` des Servers, siehe [07](07-paket-ids.md)).
**Ergebnisse:** [33-m8-testbericht.md](33-m8-testbericht.md).

Dieses Dokument beschreibt das Protokoll so, wie der eigene Client es umsetzt. Mojang-Code wurde nicht übernommen. Die Hash-Funktion ist nach dem Format nachgebaut und wurde mit Mojangs `HashOps` als Orakel geprüft (§4).

## 1. Paket-IDs (Play-Phase, 26.3, geprüft)

| Richtung | ID | Paket | Verwendung im Client |
|---|---|---|---|
| → | 17 | `container_button_click` | nicht benötigt (Verzauberung, Webstuhl …) |
| → | 18 | `container_click` | jede Inventaraktion |
| → | 19 | `container_close` | Fenster schließen |
| → | 39 | `place_recipe` | nicht umgesetzt (Rezeptbuch) |
| → | 41 | `player_action` | Q in der Welt: `DROP_ITEM` 5 / `DROP_ALL_ITEMS` 4 |
| → | 54 | `set_carried_item` | Hotbar-Auswahl |
| → | 57 | `set_creative_mode_slot` | nicht verwendet (§7) |
| ← | 17 | `container_close` | Server schließt Fenster |
| ← | 18 | `container_set_content` | vollständiger Inhalt + Cursor |
| ← | 19 | `container_set_data` | Ofen-Fortschritt u. a. |
| ← | 20 | `container_set_slot` | einzelner Platz |
| ← | 60 | `open_screen` | Server öffnet Container |
| ← | 98 | `set_cursor_item` | Cursor-Stapel |
| ← | 107 | `set_held_slot` | Hotbar-Platz vom Server |
| ← | 110 | `set_player_inventory` | Platz nach Inventory-Index |

## 2. Felder

### 2.1 `container_click` (`net/minecraft/network/protocol/game/ServerboundContainerClickPacket.java:14`)

| Feld | Typ | Bedeutung |
|---|---|---|
| containerId | VarInt (`CONTAINER_ID`) | aktuelles Menü (0 = Spielerinventar) |
| stateId | VarInt | zuletzt vom Server empfangene Zustandsnummer |
| slot | short | Menüplatz, −999 = außerhalb, −1 = kein Platz |
| button | byte | Maustaste bzw. Taste/Maske, siehe §3 |
| input | VarInt (`ContainerInput`) | PICKUP 0, QUICK_MOVE 1, SWAP 2, CLONE 3, THROW 4, QUICK_CRAFT 5, PICKUP_ALL 6 (`world/inventory/ContainerInput.java:9`) |
| changedSlots | Map ≤ 128: short → HashedStack | Plätze, die der Client als geändert ansieht |
| carried | HashedStack | Cursor nach Ansicht des Clients |

`HashedStack` (`network/HashedStack.java:24`): `bool vorhanden`, dann Item-ID (VarInt), Anzahl (VarInt) und `HashedPatchMap` (`network/HashedPatchMap.java:16`). Die HashedPatchMap besteht aus einer Map ≤ 256 von Komponententyp-ID (VarInt) auf einen 4-Byte-int-Hash und einer Menge ≤ 256 entfernter Komponententypen.

### 2.2 Clientgebunden

- `container_set_content`: containerId, stateId, Liste von `ItemStack` (optional), Cursor (`ClientboundContainerSetContentPacket.java:11`).
- `container_set_slot`: containerId, stateId, slot (short), ItemStack. Container-ID −1 / Slot −1 kommt in 26.3 nicht mehr vor, dafür gibt es `set_cursor_item`.
- `set_player_inventory`: Inventory-Index (VarInt), ItemStack. Wird z. B. für `give` und `item replace` verwendet.
- `open_screen`: containerId, Menütyp (Registry `menu`), Titel (Text-Komponente).
- `container_set_data`: containerId, Eigenschafts-ID (short), Wert (short).

**ItemStack** im Netz: Anzahl (VarInt, 0 = leer), Item-ID, Anzahl hinzugefügter Komponenten, Anzahl entfernter Komponenten, dann je Komponente Typ-ID und Wert im Netz-Codec. Komponenten ohne eigenen Netz-Codec werden als NBT übertragen. Für eine unbekannte Komponente ist die Länge nicht bestimmbar, deshalb ist das **ganze Paket** nicht dekodierbar (§6).

## 3. Klickarten (Clientseite, `client/gui/screens/inventory/AbstractContainerScreen.java`)

| Eingabe | input | button | slot |
|---|---|---|---|
| Linksklick / Rechtsklick, Cursor leer | PICKUP | 0 / 1 | Platz |
| Shift + Klick | QUICK_MOVE | 0 / 1 | Platz |
| Klick außerhalb des Fensters | PICKUP | 0 / 1 | −999 |
| Drücken mit Cursor-Stapel, Ziehen, Loslassen | QUICK_CRAFT, Folge Start (−999) – je Platz – Ende (−999) | Maske `stage \| type << 2`, type 0 links, 1 rechts, 2 Mitte | |
| Doppelklick (≤ 250 ms, gleicher Platz) | PICKUP_ALL | 0 | Platz |
| Taste 1–9 / F über Platz | SWAP | 0–8 / 40 | Platz |
| Q / Strg+Q über Platz | THROW | 0 / 1 | Platz |
| Mittelklick (Kreativ) | CLONE | 2 | Platz |

**Wichtig:** Auch ein einzelner Klick mit belegtem Cursor ist in Vanilla ein Ziehvorgang über einen Platz: QUICK_CRAFT Start/Platz/Ende (`AbstractContainerScreen.java:430`, `:576–582`). Der eigene Client verhält sich genauso; der Echteingabetest hat es bestätigt ([33](33-m8-testbericht.md) §3.2).

## 4. Komponenten-Hashes

Der Server vergleicht die gemeldeten Stapel nicht vollständig, sondern über Hashes der Komponentenwerte (`RemoteSlot.java:52`). Die Hashes erzeugt `HashOps.CRC32C_INSTANCE` (`util/HashOps.java:58`): CRC32C (Guava) über eine typisierte Byte-Kodierung. Nachgebaut sind:

| Wert | Bytes vor CRC32C |
|---|---|
| int | Tag 8, int little-endian |
| bool | Tag 13, 0/1 |
| Unit (leere Map) | Tag 2, Tag 3 |
| String | Tag 12, Länge (int LE), UTF-16LE-Zeichen |

`ComponentHash` deckt damit diese Komponenten ab: `damage`, `max_damage`, `max_stack_size`, `repair_cost`, `additional_trade_cost`, `enchantment_glint_override`, `unbreakable` (Unit), Bezeichner wie `item_model` sowie `rarity`.

**Prüfung:** `analysis/tools/HashCheck.java` lässt Mojangs Codec dieselben Werte hashen. Alle 10 Referenzwerte stimmen überein (`ComponentHashTest`). Für nicht abgedeckte Komponenten, etwa Verzauberungen und Texte, schreibt der Client 0. Der Server erkennt dann eine Abweichung und sendet den echten Stapel zurück. Das kostet ein Korrekturpaket, führt aber nie zu einem falschen Zustand.

## 5. Ablauf auf dem Server (`server/network/ServerGamePacketListenerImpl.java:1979`)

1. Falsche Container-ID → das Paket wird stillschweigend verworfen. Zuschauer und Tote erhalten den vollständigen Zustand. Ungültige Platznummer → verworfen (nur Debug-Log).
2. `fullResyncNeeded = stateId ≠ menu.stateId`.
3. `suppressRemoteUpdates`; der Server führt den Klick **selbst** aus (`AbstractContainerMenu#clicked`, `:321`).
4. Die gemeldeten `changedSlots` und `carried` werden als „Stand beim Client“ übernommen (`setRemoteSlotUnsafe`, `:292`).
5. Danach folgt `broadcastFullState` (alles, `:218`) oder `broadcastChanges` (`:197`). Letzteres sendet für jeden Platz, dessen Serverwert nicht zum vermerkten Clientstand passt, ein `container_set_slot`, für den Cursor ein `set_cursor_item`.

**Folgerung für den Client (Entwurf ohne Vorhersage):** Der Client meldet **keine** geänderten Plätze und als Cursor den zuletzt vom Server bestätigten Stapel. Für jeden Platz, den der Klick wirklich verändert hat, weicht dann der vermerkte Clientstand ab. Der Server sendet deshalb genau diese Plätze mit ihrem echten Inhalt zurück. Der Client übernimmt nur diese Werte. Ein falsch vorhergesagtes Item kann so nie angezeigt werden.

**Wegwerfen in der Welt:** `ServerPlayer#drop` (`server/level/ServerPlayer.java:2089`) setzt den vermerkten Clientstand selbst auf den neuen Wert. Dadurch sendet der Server **kein** Update. Der Client fordert deshalb nach Q eine Neusynchronisation an: einen PICKUP-Klick auf Platz −1 (wirkungslos) mit absichtlich falscher stateId. Darauf antwortet der Server mit `container_set_content`.

## 6. Fehlerfälle und Server-Korrekturen

| Fall | Serververhalten | Client |
|---|---|---|
| falsche Container-ID | ignoriert | Geste verworfen, wenn sich das Menü geändert hat; spätere Pakete mit fremder ID gezählt |
| ungültiger Platz | ignoriert | Geste vor dem Senden verworfen; ungültige Platz-Updates gezählt, nicht angewandt |
| veraltete stateId | `set_content` | Inhalt vollständig ersetzt (`fullResyncs`) |
| falscher Hash | `set_slot` mit echtem Stapel | übernommen, Platz blinkt grün |
| unbekannte Komponente | — | Paket nicht dekodierbar → gezählt, Verbindung bleibt; der nächste `set_content` korrigiert |
| Trennung während Aktion | Menü geschlossen, Cursor ins Inventar | Warteschlange verworfen; nach Neuanmeldung voller Inhalt |

## 7. Überleben und Kreativ

- Im Überlebensmodus nur `container_click`. Der Server prüft jede Aktion.
- Im Kreativmodus sendet Vanilla für das Spielerinventar `set_creative_mode_slot`, also absolute Setzbefehle ohne Prüfung. Der eigene Client verwendet **auch im Kreativmodus `container_click`**. Das ist serverseitig gültig, erlaubt aber nicht, Items aus dem Kreativ-Katalog zu nehmen (kein Katalog-Bildschirm umgesetzt).
- CLONE wirkt nur im Kreativmodus.

## 8. Änderungen nach Abbau, Aufheben, Platzieren

- Aufheben eines Item-Entities → `container_set_slot` für den Zielplatz (`broadcastChanges` im Server-Tick).
- Platzieren verringert den Stapel in der Hand → `container_set_slot`.
- Abbau mit Werkzeug → `container_set_slot` mit neuem `damage`.

Alle drei wurden im Testlauf beobachtet und gegen `data get entity` geprüft ([33](33-m8-testbericht.md) §2).
