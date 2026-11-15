# 33 – Testbericht M8: Inventar, Container und Crafting

**Datum:** 2026-11-08 · Windows 11, 16 GB RAM, RTX 3070 (Treiber 621.01).
**Codestand:** Alle Läufe in diesem Bericht („final“) liefen nach der letzten Codeänderung (`Window#setScreenOpen`, F3 bei offenem Fenster ausgeblendet).

**Server:** ausschließlich der lokale Vanilla-26.3-Server auf 127.0.0.1.
- Jeder Lauf setzt `difficulty=peaceful`. Danach sind die Originalwerte wiederhergestellt: `difficulty=easy`, `network-compression-threshold=-1`. Nach `m5-run.sh 256` wurde der Schwellwert von Hand zurückgesetzt.
- Netzwerkmonitor in allen Läufen, die ihn verwenden: **0 externe Verbindungen**.
- Die Fehlermeldungen von authlib zu `127.0.0.1:9` sind gewollt: Die Dienste-Adressen zeigen auf einen geschlossenen lokalen Port.
- Server-JAR unverändert: `server/versions/26.3/server-26.3.jar` sha256 `a362163eec5d1612…`, gleicher Wert wie in [09](09-rea-funktionspruefung.md). `server/server.jar` (Starter) `d052f14d7a173734…`. Beide Dateien stammen vom 2026-11-06, also vor M8.

**Speicher:**
- Nach dem Beenden fremder Anwendungen durch den Nutzer standen 7,3 GB frei.
- Server `-Xmx1G` und Vanilla-Referenzclient `-Xmx1536M`, wie zuvor genehmigt.
- Alle Testgruppen liefen nacheinander.
- Spitzenwerte der Projektprozesse: M8-Szenarien 1,1 GB, Grafik 1,4–1,5 GB, M5-Lauf mit Vanilla-Client 2,2 GB.

**Rohdaten** (gitignored, `analysis/runtime-logs/`):
- M8: `m8-final-s1…s6`, `m6-m8-gfx`, `m6-m8-real`;
- Regression: `m5-threshold-256`, `m6-m8-regress-m6full`, `m7-m8-regress-*`, `m6-m8-regress-m7gfx`.

Die Bildschirmfotos enthalten lokale Mojang-Texturen und werden nicht nach `docs/images` kopiert.

## Ergebnisübersicht

| Prüfung | Ergebnis |
|---|---|
| s1 einfacher serverbestätigter Linksklick | **5 / 5** |
| s2 Klickarten, Ausrüsten, Werfen, 2×2-Crafting | **25 / 25** |
| s3 Truhe, große Truhe, Fass, Ofen | **28 / 28** |
| s4 Werkbank 3×3, wiederholtes Crafting, Rückgabe, erneuter Login | **11 / 11** |
| s5 Sicherheit, Last (200 Klicks), Trennung während Aktion | **16 / 16** |
| s6 Abbau (Haltbarkeit), Aufheben, Platzieren | **4 / 4** |
| Grafiktest (Autotest über den GLFW-Eingabepfad) | **11 / 11** Inventarvergleiche + Weltsperre, 0 GL-Objekte offen |
| **Echte Betriebssystem-Eingaben** (Maus/Tastatur) | **15 / 15** Eingaben ausgeführt, Endzustand Client = Server |
| Unit-Tests M8 | `InventoryModelTest` **8 / 8**, `ComponentHashTest` **2 / 2** (10 Mojang-Referenzhashes) |
| JUnit gesamt inkl. Integration | **281 / 281** |
| Regression M1–M7 | alle grün (§5) |

**Prüfverfahren** in s1–s6 (`analysis/tools/m8-inv.sh`):
- Nach jeder Aktion gibt der Client sein Inventar aus (`invdump`/`cdump`); der Server antwortet auf `data get entity … Inventory` / `equipment` bzw. `data get block … Items`.
- `analysis/tools/m8-compare.js` vergleicht Platz, Item, Anzahl und die dekodierten Komponenten (damage, repair_cost, max_damage, max_stack_size, enchantment_glint_override).
- Gilt nur bei vollständiger Übereinstimmung als OK. Eine bloß grafische Änderung zählt nicht.

## 1. Protokoll und Modell

- **Paket-IDs** aus `packets-777.txt` geprüft ([30](30-inventarprotokoll.md) §1).
- **Paketprotokolle s1–s6** (`client-packets*.log`):

  | Richtung | Pakete |
  |---|---|
  | gesendet | `container_click` 273, `container_close` 7, `set_carried_item` 12, `player_action` 3 |
  | empfangen | `container_set_slot` 299, `set_cursor_item` 182, `container_set_content` 17, `container_set_data` 730, `open_screen` 7, `container_close` 1, `set_held_slot` 8, `set_player_inventory` 1 |
- **Komponenten-Hashes:** 10 Werte (int, bool, Unit, Bezeichner, Seltenheit) stimmen mit Mojangs `HashOps.CRC32C_INSTANCE` überein. Orakel: `analysis/tools/HashCheck.java`.
- **Kein Vorhersagen:** In allen Szenarien zeigte der Client nur Werte aus Serverpaketen.

## 2. Server-Integrationstests (Terminalclient)

| Geforderter Fall | Szenario / Prüfung |
|---|---|
| 1 Aufnehmen und Ablegen (Linksklick) | s1 (5), s2 |
| 2 Teilen (Rechtsklick), einzeln ablegen | s2 „split stack“, „place one“ |
| 3 Stapeln/Zusammenführen | s2 „merge 7 into 10“ |
| 4 Schnelltransfer (Shift) | s2, s3 (Truhe ↔ Inventar, Ofen sortiert Brennstoff/Eingabe) |
| 5 Ziehen (QUICK_CRAFT) | s2 „drag left over 4 slots“ |
| 6 Doppelklick (PICKUP_ALL) | s2 |
| 7 Zahlentasten / Nebenhand | s2 SWAP 9, F |
| 8 Werfen (Q im Fenster, außerhalb klicken, Q in der Welt) | s2, jeweils mit Prüfung des Item-Entitys |
| 9 Ausrüsten | s2 Brustpanzer per Shift-Klick |
| 10 Truhe, große Truhe, Fass | s3, große Truhe mit beiden Blöcken einzeln |
| 11 Ofen inkl. `container_set_data` | s3: Eisenbarren nach 11 s, Fortschrittsdaten empfangen |
| 12 Crafting 2×2 und 3×3 | s2 Werkbank aus 4 Brettern, s4 Holzspitzhacke, Shift-Crafting 3 Stämme → 12 Bretter |
| 13 Sicherheit und Konsistenz | s5: ungültiger Platz (lokal verworfen), veralteter Container (verworfen), nicht dekodierbare Komponente (gezählt, Verbindung bleibt), Resync |
| 14 Last, Trennung, Neuanmeldung | s5: 200 Klicks in 20,1 s, 64 Bretter erhalten. Trennung mit 40 wartenden Klicks: nichts verdoppelt oder verloren, kein Item-Entity. s4: Inventar nach Neuanmeldung |
| Zusatz: Weltaktionen | s6: Holzspitzhacke `damage 1` nach Abbau, Bruchstein durch Aufheben, Erde 5 → 4 nach Platzieren |

**Behobene Fehler während M8** (alle vor den finalen Läufen):
- **Q in der Welt** wurde nicht synchronisiert. `ServerPlayer#drop` setzt den Clientstand selbst, deshalb sendet der Server kein Update. Behoben durch einen Resync-Klick nach dem Werfen ([30](30-inventarprotokoll.md) §5).
- **Testskripte:**
  - Marker-Wettlauf bei der Clientausgabe;
  - nachlaufende Serverantworten;
  - falsche Annahmen über Zielplätze des Servers;
  - SNBT-Schlüssel mit `:`;
  - Mehrbyte-Zeichen `×` in regulären Ausdrücken.

## 3. Grafischer Client

### 3.1 Autotest (`m8-gfx.txt`, Lauf `m6-m8-gfx`)

- **Eingabepfad:** Die Schritte `gui-click`, `gui-drag`, `gui-dbl` und `gui-key` erzeugen Maus- und Tastenereignisse an derselben Stelle wie die GLFW-Rückrufe.
- **Vergleiche:** Nach jeder Gruppe wird das Clientinventar (`inv-dump`) mit dem Server verglichen (`m8-gfx-compare.sh`):

| Gruppe | Ergebnis |
|---|---|
| g1 Aufnehmen/Ablegen | MATCH (4 Stapel) |
| g2 Teilen + Ziehen | MATCH (8) |
| g3 Doppelklick | MATCH (8) |
| g4 Ausrüsten, Tauschen, Werfen | MATCH (8) |
| g4b Zahlentasten | MATCH (8) |
| g5 2×2-Crafting | MATCH (8) |
| g5b nach Schließen | MATCH (8) |
| g6 Truhe (Spieler) | MATCH (8) |
| Truhe (Blockinhalt) | MATCH (1) |
| g7 3×3-Crafting | MATCH (3) |
| g8 Endzustand | MATCH (3) |

- **Weltsperre:** Bei offenem Inventar 1,5 s Linksklick auf Erde gehalten; der Server meldet die Erde unverändert (`Test passed`).
- 60 FPS (1 %-Low 45); 0 GL-Objekte beim Beenden offen.

### 3.2 Echte Maus- und Tastatureingaben (`m8-real.txt` + `analysis/tools/m8-realinput.ps1`, Lauf `m6-m8-real`)

**Verfahren** (vom Nutzer gewählt: automatisch einspielen):
- Ein PowerShell-Treiber erzeugt Betriebssystem-Eingaben mit `SetCursorPos`, `mouse_event` und `keybd_event`. GLFW erhält sie wie Hardware-Eingaben.
- Der Autotest selbst gibt keine Eingaben (`external-input on`). Er liefert nur die Bildschirmkoordinaten der Plätze (`export-slots`) und prüft am Ende.
- Vor **jeder** Eingabe prüft der Treiber, dass das Clientfenster im Vordergrund ist; sonst bricht er ohne weitere Eingabe ab.
- Fremde Fenster mit Vollbild (Spiel) waren vom Nutzer geschlossen worden.

**Eingabefolge** (alle 15 ausgeführt, kein Abbruch):
- Taste E öffnet das Inventar;
- Linksklick 36, Linksklick 20, Rechtsklick 20;
- Ziehen 21–24, Doppelklick 21, Linksklick 25;
- Shift-Klick 9;
- Taste 1 über 20, F über 39, Q über 25;
- Esc.

**Vom Client gesendet** (`inv-history`):
- PICKUP und QUICK_CRAFT. Ablegen mit belegtem Cursor ist auch in Vanilla ein Ziehvorgang über einen Platz, siehe [30](30-inventarprotokoll.md) §3.
- PICKUP (Rechtsklick), QUICK_CRAFT über 4 Plätze, PICKUP + PICKUP_ALL, QUICK_MOVE, SWAP mit Hotbar 1, SWAP mit Nebenhand (button 40), THROW, `container_close`.

**Ergebnis:**
- Endzustand `r1-final` Client = Server (MATCH, 3 Stapel inkl. Nebenhand): Bruchstein 32 in Platz 20, Bretter 15 in Platz 25, Stock in der Nebenhand.
- 0 GL-Objekte offen.

**In diesem Test gefundener und behobener Fehler:** Bei offenem Fenster löste ein echter Linksklick den Mausfang aus (`Window`), statt den Platz anzuklicken. Der interne Autotest konnte das nicht zeigen, weil er diesen Rückruf umgeht. Behoben mit `Window#setScreenOpen`. Bestätigt durch diesen Lauf und den Grafiktest §3.1.

**Kosmetisch, offen:** Die Hilfezeile oben wird bei 1 280 px Fensterbreite abgeschnitten.

**Nicht bestätigt:** Die Bedienung durch einen Menschen (Feingefühl, Bedienbarkeit) wurde nicht von Hand getestet. Bestätigt ist, dass echte Betriebssystem-Eingaben über GLFW richtig verarbeitet werden.

## 4. Speicher und Ressourcen

| Prüfung | Ergebnis |
|---|---|
| GL-Objekte beim Beenden | 0 in allen fünf Grafikläufen (M8-Grafik, Echteingabe, M6-Volltest mit 2 617 Meshes, M7-Grafik) |
| Java-Heap im M6-Volltest (5 min) | schwankt 87–240 MB mit der Speicherbereinigung, kein Anstieg |
| 200 schnelle Klicks | Warteschlange abgearbeitet, Gesten nach 2 Ticks, keine Rückstände; nach Trennung leer |

## 5. Regression M1–M7 (nacheinander)

| Prüfung | Ergebnis |
|---|---|
| `m5-run.sh 256`: Vanilla-Physikreferenz | 20 Szenarien, 896 Ticks, worst \|Δpos\| = 0, 0 Flag-Abweichungen, Serverkorrekturen nur die 40 Skript-Teleports |
| JUnit inkl. Integration | **281 / 281** (Status, Login/Konfiguration/Play, Kompression 256, Keep-Alive, Bewegung, Welt, Physik) |
| M2-Terminaltest, M4-Weltabgleich, M4/M5-Terminaltests | SUCCESS; 657/657, nach Entladen 81/81, nach Wiederherstellen 81/81; exit 0 |
| M6-Volltest (5 min, Grafik) | 9 / 9 Positionen Client = Server, 60 FPS, Disconnect kontrolliert |
| M7 Auswahl gegen Vanilla | Überleben **75 / 75**, Kreativ **75 / 75** |
| M7 Interaktion | **14 / 14** |
| M7 Licht | Himmel und Block je **1 729 / 1 729** |
| M7 Grafiktest | alle Serverprüfungen bestanden, 0 GL-Objekte offen |

Die Warnung „ProtoBotM3 moved too quickly“ im M5-Lauf ist der beabsichtigte Korrekturtest aus M3 ([17](17-m2-m3-testbericht.md)).

## 6. Grenzen (nicht Teil von M8)

- Rezeptbuch (`place_recipe`) und Kreativ-Katalog nicht umgesetzt. Kreativ nutzt `container_click` statt `set_creative_mode_slot`.
- Amboss, Verzauberung, Webstuhl, Händler, Brauen, Schmieden, Crafter: nur generisches Raster, keine Knöpfe/Textfelder.
- Komponenten-Hashes nur für einfache Werte. Andere, z. B. Verzauberungen, kosten ein zusätzliches Korrekturpaket vom Server; der Zustand bleibt trotzdem richtig.
- Komponenten ohne eigenen Decoder (z. B. `written_book_content`) machen das Paket nicht dekodierbar. Es wird gezählt; der Zustand stimmt wieder nach dem nächsten Vollabgleich.
- Durchsatz eine Geste je 2 Ticks (≈ 10/s).
- Menschliche Bedienung nicht von Hand bestätigt (§3.2).

## 7. Abschluss

Alle M8-Abschlusskriterien sind mit Serverabgleich erfüllt:
- grafisch verschieben, stapeln, teilen, ausrüsten;
- Truhe übertragen;
- 2×2- und 3×3-Crafting.

Alle Regressionstests M1–M7 sind grün. Nach den Tests liefen keine Projektprozesse außer dem Gradle-Daemon; die Serverkonfiguration ist wiederhergestellt.
