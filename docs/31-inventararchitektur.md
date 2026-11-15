# 31 – Inventararchitektur (M8)

**Stand:** 2026-11-08 · Protokoll: [30](30-inventarprotokoll.md) · Container und Crafting: [32](32-container-und-crafting.md) · Ergebnisse: [33](33-m8-testbericht.md)

## 1. Grundsatz: Der Server ist maßgeblich

Der Client **sagt nichts voraus**. Jede Eingabe wird in eine Geste übersetzt und als `container_click` gesendet. Angezeigt wird erst, was der Server zurückmeldet (`container_set_slot`, `set_cursor_item`, `container_set_content`). Ein nur grafisch verschobenes Item gibt es dadurch nicht.

Möglich macht das der Serverablauf aus [30](30-inventarprotokoll.md) §5: Der Client meldet leere `changedSlots` und den bestätigten Cursor, und der Server antwortet genau mit den Plätzen, die sich geändert haben.

| Vorteil | Preis |
|---|---|
| kein Rückrollen, keine Duplikate, kein Nachbau der Vanilla-Klicklogik nötig | Anzeige folgt nach einer Rundreise (lokal < 1 Tick) |
| Komponenten-Hashes müssen nicht vollständig sein | höchstens ein Korrekturpaket mehr |

## 2. Schichten

| Schicht | Quellsatz | Klasse | Aufgabe |
|---|---|---|---|
| Codec | `main` (nur JDK) | `protocol/codec/ComponentHash` | CRC32C-Komponenten-Hashes ([30](30-inventarprotokoll.md) §4) |
| Codec | `main` | `protocol/packet/Serverbound.ContainerClick`, `#writeHashedStack` | Paketaufbau |
| Modell | `main` | `session/PlayerInventory` | Spieler- und Containerzustand, nur aus Serverpaketen |
| Modell | `main` | `session/ContainerView` | Menütyp → Art, Platzanzahl, Beginn der Spielerplätze |
| Steuerung | `main` | `session/InventoryController` | Gesten-Warteschlange → Pakete (Vanilla-Abbildung) |
| Terminal | `main` | `cli/InventoryCommands`, `TerminalController` | `inv`, `click`, `drag` … für Tests |
| GUI | `render` (LWJGL) | `gl/InventoryScreen` | Zeichnen, Maus/Tasten → Gesten |
| GUI | `render` | `gl/GameView`, `gl/Window` | Öffnen/Schließen, Eingabesperre der Welt |

Der Terminalclient bleibt ohne LWJGL. `InventoryScreen` greift nur lesend auf das Modell zu und reicht Gesten an `InventoryController#submit` weiter.

## 3. Modell (`PlayerInventory`)

- **Spielerinventar** (Container 0, 46 Plätze) und höchstens **ein offener Container** (ID, Menütyp, Titel, Größe, Inhalt, stateId, Daten aus `container_set_data`).
- **Cursor je Menü:** Wie auf dem Server hat jedes Menü einen eigenen Cursor. `set_cursor_item` gilt für das aktuelle Menü. Ein neu geöffneter Container beginnt mit leerem Cursor, bis der Server etwas anderes meldet.
- Spielerplätze eines Containers werden in das Spielerinventar gespiegelt (`playerSectionToMenu0`), damit Hotbar und Inventar stimmen, während ein Container offen ist.
- **Validierung:** Pakete mit fremder Container-ID (`staleContainerPackets`) und Plätze außerhalb des Menüs (`invalidSlotPackets`) werden gezählt und **nicht angewandt**. Jeder `set_content` ersetzt den Inhalt vollständig (`fullResyncs`).
- `recentUpdates` merkt sich die Plätze, die der Server gerade gemeldet hat. Die GUI hebt sie grün hervor.

## 4. Steuerung (`InventoryController`)

- Gesten (versiegelt): `Click`, `Drag`, `DoubleClick`, `Swap`, `Throw`, `Clone`, `Close`, `DropSelected`, `Resync`.
- Höchstens 64 Gesten in der Warteschlange. **Eine Geste je 2 Ticks**, damit vor der nächsten Geste die Serverantwort für die vorige verarbeitet ist (also ≈ 10 Gesten/s).
- Beim Ausführen wird geprüft:
  - Ist die Container-ID der Geste nicht mehr die aktuelle, wird die Geste verworfen (`dropped`). So wirkt nie ein Klick, der für ein inzwischen geschlossenes Fenster gedacht war, auf ein anderes.
  - Ungültige Plätze werden ebenfalls verworfen.
- Gesendet werden die aktuelle `stateId`, leere `changedSlots` und der bestätigte Cursor.
- `DropSelected` (Q in der Welt) sendet `player_action` und danach einen Resync-Klick ([30](30-inventarprotokoll.md) §5).
- `history()` protokolliert jede ausgeführte Geste mit ihren Paketen. Die Tests werten das aus.
- Bei Trennung und beim Wiedereinstieg wird die Warteschlange geleert.

## 5. GUI (`InventoryScreen`)

- **Layouts:** Spielerinventar (Rüstung, 2×2, Nebenhand), Werkbank, Ofen-Familie (mit Fortschritt aus den Daten), generische Reihen 1–6, Rest als generisches Raster.
- **Maus** (Vanilla-Logik):
  - Drücken mit leerem Cursor → PICKUP, mit Shift → QUICK_MOVE, außerhalb → Wurf (−999).
  - Drücken mit belegtem Cursor startet einen Ziehvorgang; die überfahrenen Plätze werden gelb markiert. Beim Loslassen wird eine QUICK_CRAFT-Folge gesendet.
  - Doppelklick ≤ 250 ms → PICKUP_ALL.
- **Tasten:** 1–9 / F → SWAP, Q / Strg+Q → THROW, E/Esc → schließen (`container_close`).
- **Anzeige:**
  - Stapelgröße, Schaden als Kurzangabe; Cursor-Stapel an der Maus.
  - Tooltip: Name (ggf. eigener Name), Anzahl / maximale Stapelgröße, Schaden, Zahl der Verzauberungen, entfernte Komponenten, Platznummer.
  - Vom Server gemeldete Plätze blinken grün.
  - Eine Statuszeile zeigt „warte auf Server (n)“, solange Gesten offen sind. Für 3 s erscheinen dort außerdem verworfene Gesten und vollständige Abgleiche durch den Server (Korrektur).
- **Eingabesperre:** Bei offenem Fenster gehen Maus und Tasten nur an das Fenster. Die Welt erhält keine Bewegungs-, Abbau- oder Benutzeingaben. Die Maus wird freigegeben und beim Schließen wieder gefangen. `Window#setScreenOpen` verhindert, dass ein Klick ins Fenster als Mausfang verschluckt wird (im Echteingabetest gefundener Fehler, [33](33-m8-testbericht.md) §3.2).
- Öffnet der Server einen Container (`open_screen`), erscheint das Fenster automatisch. Schließt der Server ihn, kehrt der Client in die Welt zurück.
- Mit offenem Fenster ist das F3-Overlay ausgeblendet.

## 6. Testschnittstellen

- **Terminal:** `inv`, `invdump`, `cdump`, `click`, `drag`, `dblclick`, `swap`, `throw`, `clone`, `close`, `drop`, `resync`, `clickc`, `burst`, `burstq`, `mark`.
- **Autotest (Grafik):** `gui-click`, `gui-drag`, `gui-dbl`, `gui-key`, `inv-dump`, `inv-history`.
  - Diese Schritte erzeugen dieselben Maus- und Tastenereignisse wie GLFW, im selben Eingabepfad.
  - `external-input on` schaltet sie ab, damit echte Betriebssystemeingaben (`analysis/tools/m8-realinput.ps1`) wirken. `export-slots` liefert dafür die Bildschirmkoordinaten der Plätze.
