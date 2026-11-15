# 41 – Testbericht M10: Kampf, Gesundheit, Hunger und Statuseffekte

**Datum:** 2026-11-13 · Windows 11, 16 GB RAM, RTX 3070 · ausschließlich lokaler Vanilla-26.3-Server (`server-26.3.jar` unverändert), Kompressionsschwelle 256 in allen Läufen.
Grundlagen: [38](38-kampf-und-survival-protokoll.md) (Protokoll, Formeln), [39](39-kampfsystem-architektur.md) (Architektur), [40](40-gesundheit-hunger-effekte.md) (Zustand, Effekte).

**Umgebung:**
- Schwierigkeit `easy` (Monster nötig) bzw. `peaceful` im Vanilla-Vergleich.
- Gamerules `spawn_mobs` und `natural_health_regeneration` werden während der Tests ausgeschaltet und danach auf den gelesenen Wert (`true`) zurückgesetzt.
- Nach jedem Lauf zurückgesetzt: `difficulty=easy`, `network-compression-threshold=-1`.
- Netzwerkmonitor: in allen Läufen 0 externe Verbindungen.
- Speichergrenzen: Server 1 GB, Vanilla-Client 1,5 GB.

**Rohdaten** (gitignored, `analysis/runtime-logs/`):
- Endstand: `m10-k1-attacks-end2`, `m10-k2-damage-end3`, `m10-k3-food-effects-end`, `m10-k4-death-end2`, `m10-vanilla3`, `m6-m10-gfx3`, `m6-m10-gfx3-1600`;
- Regression: `m10-regression.txt` und die dort genannten Läufe;
- Zwischenstände: `m10-k*-{a,b,final,end}`, `m10-vanilla1/2`, `m6-m10-gfx1/2`.

**Kennzeichnung in diesem Bericht:**
- **[S]** serverseitig bestätigt (Wert aus `data get entity …` bzw. Server-Paketprotokoll);
- **[C]** clientseitige Anzeige oder Animation;
- **[A]** nicht verifizierte Annahme bzw. nicht geprüft.

## Ergebnisübersicht

| Prüfung | Ergebnis |
|---|---|
| k1 Angriffe (Faust, Schwert, Axt, Abklingzeit, Krit, Reichweite, Rückstoß) | **16 / 16** |
| k2 Schaden (Zombie, Skelett, Fall, Feuer, Ertrinken, anderer Spieler, Rüstung, Schild) | **32 / 32** |
| k3 Essen und Effekte | **35 / 35** |
| k4 Tod, Wiederbelebung, Inventar, Trennung im Todeszustand, Headless-Automatik | **27 / 27** |
| Vanilla-Vergleich (gleiche Angriffe, unveränderter Vanilla-Client) | Gesundheit der Ziele **5 / 5 gleich**, Angriffsstärke gleich, Paketfolge **gleich** |
| Grafik 1280×720 und 1600×900 | alle Elemente sichtbar, Wiederbeleben per Klick, **0 GL-Objekte offen** |
| Unit-Tests (`./gradlew test`) | **320 / 320** |
| Regression M1–M9 (21 Testgruppen nacheinander) | **alle grün** nach zwei Korrekturen der Testinfrastruktur (§9) |

## 1. Testliste der Aufgabe (1–14)

| # | Test | Wo | Ergebnis |
|---|---|---|---|
| 1 | Faustangriff | k1, Vanilla | [S] Schwein 10 → 9; `damage_event` mit eigener Ursache |
| 2 | Schwert/Axt | k1, Vanilla | [S] Eisenschwert 6 (10 → 4), Eisenaxt 9 (Kuh 10 → 1), Axt −2 Haltbarkeit (`item_damage_per_attack`); [C] `attack_speed` 1,6 / 0,9 |
| 3 | Abklingzeit | k1, Vanilla | [S] voller Treffer 6; ohne Abklingzeit 8,608 Gesundheit Rest, s = 0,2 = (2 + 0,5)/12,5; [C] Stärke beim Senden 0,160, gleich Vanilla (0,1600) |
| 4 | Kritischer Treffer | k1, Vanilla | [S] im Fallen 9 Schaden (6 · 1,5) |
| 5 | Reichweite | k1 | [C] Ziel jenseits 3,0 nicht ausgewählt, kein Paket; [S] Ziel behält 10 |
| 6 | Rüstung | k2 | [S] Eisenschwert gegen Rüstung 6: 5,28 (Formel [38](38-kampf-und-survival-protokoll.md) §4); Brustpanzer −1 Haltbarkeit; [C] Rüstung 6 angezeigt |
| 6b | Schild | k2 | [S] Schwert und Axt ganz geblockt; Schild +7 bzw. +10; Axt sperrt den Schild (100 Ticks); zweiter Axttreffer 9 |
| 7 | Rückstoß | k1 | [S] Schwein weggestoßen (−Z); mit Rückstoßresistenz 1 unbewegt; [C] Client-Positionen = Server (MATCH 8) |
| 8 | Gesundheitsverlust | k2 | [S]/[C] Zombie, Pfeil, Fall (⌈12 − 3⌉ = 9), Feuer, Ertrinken, anderer Spieler: Client = Server, richtige Schadensart |
| 9 | Heilung/Regeneration | k3 | [S] Sofortheilung +4, Regeneration II heilt; Client = Server |
| 10 | Nahrung | k3, Grafik | [S] Rindfleisch +8 Nahrung, 12,8 Sättigung, Stapel −1, `entity_event 9`; Abbruch ohne Verbrauch |
| 11 | Effekte | k3 | [S] Speed/Slowness auf die Physik, Resistenz, Sofortschaden, Gift, Stärke, Schwäche, Absorption ([40](40-gesundheit-hunger-effekte.md) §4) |
| 12 | Tod | k4, Grafik | [S] Gesundheit 0; [C] Todesnachricht, Todesbildschirm; keine automatische Wiederbelebung |
| 13 | Wiederbelebung | k4, Grafik | [S] Gesundheit 20, Spawnpunkt; genau ein `client_command`; [C] Schaltfläche „Wiederbeleben“ |
| 14 | Inventar nach dem Tod | k4 | [S] Inventar leer auf Client und Server; fallengelassene Items als Entities gleich dem Server (MATCH 2) |

## 2. k1 – Angriffe (`m10-combat.sh` + `m10/k1-attacks.sh`)

Ablauf: Ziele mit `NoAI` (Schweine, Kuh). Nach jeder Aktion wird `data get entity <UUID> Health` des Servers gelesen [S].

- **Faust:** 10 → 9; der Treffer ist durch `damage_event` (Ursache = eigene ID) bestätigt.
- **Eisenschwert:** `attack_speed` 1,6 (4 − 2,4) [C]; 10 → 4 [S].
- **Eisenaxt:** `attack_speed` 0,9 [C]; Kuh 10 → 1 [S]; Haltbarkeit +2 [S].
- **Ohne Abklingzeit** (`hitpair`: zweiter Treffer einen Tick nach dem ersten):
  - Der Client sendet mit Stärke 0,160.
  - Der Server rechnet mit `getAttackStrengthScale(0,5)`, also s = 0,2.
  - Schaden 6 · (0,2 + 0,8 · 0,04) = 1,392 → 8,608 [S]. Genau so beim Vanilla-Client (§7).
- **Kritischer Treffer** (Angriff im Fallen, `hitfall`): 10 → 1 [S].
- **Reichweite:** Ein Ziel jenseits von 3,0 wird nicht ausgewählt; es wird kein `attack` gesendet [C]. Gesundheit unverändert [S].
- **Rückstoß:** Schwein bewegt sich in −Z [S]. Mit `knockback_resistance` 1 bleibt es stehen [S]. Alle Client-Positionen und Gesundheiten gleich dem Server (MATCH 8).

## 3. k2 – Schadensquellen, Rüstung und Schild

| Quelle | Schadensart [C] | Ergebnis |
|---|---|---|
| Zombie (Nahkampf) | `mob_attack` | Client = Server (15,0) |
| Skelett (Pfeil) | `arrow` | Client = Server (17,0) |
| Fall aus 12 Blöcken | `fall` | 9 Schaden [S], Client = Server |
| Feuer | `in_fire` / `on_fire` | Client = Server (vor dem Vergleich Feuerresistenz, weil Nachbrennen weiter Schaden macht) |
| Ertrinken | `drown` | Luft ≤ 0 im Client (Datenfeld 1), Client = Server |
| anderer Spieler, Faust | `player_attack` | 1 Schaden [S], Client = Server |
| anderer Spieler, Eisenschwert gegen Rüstung 6 | – | 5,28 [S]; Rüstungswert 6 [C]; Brustpanzer −1 [S] |
| eigener Schwerttreffer auf den anderen Spieler | – | 6 [S], bestätigt |

**Feuer und Wasser:** Der Client hat keine Flüssigkeitsphysik. Geprüft wird deshalb nur die Schadensverarbeitung: Der Spieler wird per `tp` ins Feuer bzw. ins Wasser gesetzt und bewegt sich nicht ([40](40-gesundheit-hunger-effekte.md) §2).

**Schild** (§8 von k2): Der zweite Terminalclient hält den Schild in der Nebenhand mit `usehold on` erhoben. Erwartungen nach 26.3 (`BlocksAttacks`, Schildschaden ⌊1 + Schaden⌋ ab Schwelle 3):

| Schritt | Ergebnis |
|---|---|
| Client 2 startet die haltbare Benutzung | `using=true` [C] |
| Eisenschwert gegen den erhobenen Schild | Gesundheit unverändert [S], Schild +7 [S] |
| Eisenaxt | geblockt [S], Schild +10 [S] |
| Folge der Axt | `cooldown minecraft:shield 100` empfangen [C]; Server beendet die Benutzung, der Client folgt über die Living-Flags [C] |
| weitere Benutzungsversuche während der Sperre | PASS, keine lokale Benutzung [C] (wie `MultiPlayerGameMode#useItem`) |
| zweiter Axttreffer innerhalb der 5 s | 9 Schaden [S] |

## 4. k3 – Essen und Effekte

**Hunger** (Effekt `hunger`): Nahrung 11, Sättigung 0; Client = Server.

**Essen** (gebratenes Rindfleisch, Taste 2,2 s gehalten):
- Nahrung +8, gekappt bei 20;
- Sättigung 12,8;
- Stapel 5 → 4;
- `entity_event 9` empfangen.

**Abbruch** (Taste vorzeitig losgelassen): `RELEASE_USE_ITEM` gesendet; Nahrung und Stapel unverändert [S].

**Goldener Apfel** (immer essbar): Regeneration II und Absorption auf beiden Seiten (Effektvergleich MATCH 2); Absorption 4 = Server.

**Effekte:**

| Effekt | Ergebnis |
|---|---|
| Speed II | Attribut 0,14 = Server; eigene Physik 0,14 |
| Slowness I | eigene Physik 0,085; ohne Effekt wieder 0,1 |
| Resistenz II | 5 Schaden → 3 [S] |
| Sofortschaden I | −6 [S] |
| Sofortheilung I | +4 [S]; kein bleibender Effekt |
| Regeneration II | heilt [S] |
| Gift | Schaden, Art `magic` |
| Stärke I | 6 + 3 = 9 am Schwein [S] |
| Schwäche I | Faust 1 − 4 < 0 → kein Schaden [S] |

Alle Effektlisten werden je Schritt mit `active_effects` des Servers verglichen (`m10-effects.js`: ID, Stufe, Restzeit ± Toleranz).

## 5. Grafik (`m10-gfx.txt`, `m6-run.sh`; 1280×720 und 1600×900)

Bilder in `m6-m10-gfx3/` und `m6-m10-gfx3-1600/`:

| Bild | Inhalt |
|---|---|
| 50 | Herzen, Rüstung (8), Hunger, Hotbar |
| 51 | Treffer: Abklingbalken unter dem Fadenkreuz, gelbe Trefferpartikel (vom Server bestätigt), rotes Aufblinken des Ziels, WHACK-Schwung des Arms |
| 51b | Speer: STAB-Schwung nach vorn, kein Angriffspaket |
| 52 | Schaden: roter Rand, blinkende Herzen |
| 53 | Effekte oben rechts (Speed II, Regeneration, Absorption II), gelbe Absorptionsherzen |
| 54 | nach dem Essen: Nahrung 0 → 8 |
| 55 | Maximalgesundheit 40: zwei Herzreihen |
| 56 | Todesbildschirm mit übersetzter Nachricht („ProtoBotGfx was killed“) |
| 57 | nach dem Klick auf „Wiederbeleben“ |

- **Zustände** ([C], Autotest-Abfragen `survival-dump`): `effects=[speed:1, regeneration:0, absorption:1]`, `absorption=8.0`; nach Essen `food=8`; `max40` 40/40; tot `deathScreen=true`; nach Klick `respawns=1`, Gesundheit 20.
- **Wiederbeleben:** Die Schaltfläche wird in beiden Fenstergrößen per synthetischem Mausklick in der Mitte getroffen.
- **Ressourcen:** Am Ende sind in beiden Läufen 0 GL-Objekte offen (Puffer, Meshes, Programme, Texturen).
- **Leistung:** 50–53 FPS mit Debug-Overlay; Spitzenwert des Projekts 1,5 GB.

## 6. k4 – Tod und Wiederbelebung

**Tod:**
- Erkannt über `player_combat_kill`; Nachricht empfangen; Gesundheit 0 [S][C].
- 3 s ohne Wiederbelebung [S]: Der Client wartet (`autorespawn off`).
- Fallengelassene Items liegen an der Todesstelle [S].

**Wiederbelebung (Befehl `respawn`):**
- genau ein `client_command`; Gesundheit 20 [S][C];
- Position = Spawnpunkt des Servers;
- Inventar leer auf Client und Server (keepInventory aus);
- Effekte auf beiden Seiten leer;
- in Sichtweite der Todesstelle: Item-Entities gleich dem Server (MATCH 2).

**Trennung im Todeszustand:**
- Der Client startet mit `--no-auto-respawn`.
- Der Server behält die Gesundheit 0 [S] und sendet nach dem Login `set_health` 0, aber kein `player_combat_kill`.
- Der Client erkennt den Tod trotzdem (`dead=true`) und sendet kein `client_command`. 3 s später ist der Spieler noch tot [S].
- Erst der Befehl `respawn` führt zu Gesundheit 20 [S].

**Headless-Automatik:** Mit `autorespawn on` lebt der Spieler nach `kill` ohne Befehl wieder (Gesundheit 20 [S]).

**Korrektur gegenüber Zwischenständen:** Ein früherer Lauf meldete „nach erneutem Login lebendig“. Das war ein Testfehler: Der Terminalclient startete mit aktiver Automatik und belebte sich 32 ms nach dem Login selbst wieder, bevor `autorespawn off` ankam.

## 7. Vergleich mit dem Vanilla-Client (`m10-vanilla.sh`, Fabric-Treiber `CombatReferenceClient`)

**Ablauf:**
- Dieselben fünf Ziele werden nacheinander zuerst vom unveränderten Vanilla-Client angegriffen, dann vom eigenen Terminalclient.
- Der Vanilla-Client nutzt dabei seine eigenen Eingabewege: Angriffstaste über `KeyMapping.click` → `startAttack`.
- Verglichen werden die Gesundheit der Ziele [S] und die vom Server empfangene Paketfolge (`logs/packets.log`).

| Ziel | Vanilla | eigener Client |
|---|---|---|
| t1 Faust | 9,0 | 9,0 |
| t2 Schwert voll | 4,0 | 4,0 |
| t3 Schwert ohne Abklingzeit | 8,608 | 8,608 |
| t4 Axt voll (Kuh) | 1,0 | 1,0 |
| t5 kritischer Treffer | 1,0 | 1,0 |

- **Angriffsstärke beim zweiten Schwerttreffer:** Vanilla 0,1600, eigener Client 0,160.
- **Paketfolge, gleich** (`set_carried_item ×2, attack, punch, set_carried_item, attack, punch, attack, punch, set_carried_item, attack, punch, set_carried_item, attack, punch`).

**Zwei Fehler, die dieser Vergleich aufgedeckt hat (behoben):**
1. **Startwert `carriedIndex`:** Der eigene Client sendete ein zusätzliches `set_carried_item`, weil der Wert mit −1 statt 0 begann (Vanilla-Startwert 0).
2. **Zurücksetzen der Angriffsstärke:** Der eigene Client setzte die Stärke auch bei geänderter Haltbarkeit zurück, Vanilla nur bei anderem Item-Typ (`ItemStack#isSameItem`). Dadurch zeigte er 0,080 statt 0,160 an. Der Serverschaden war schon vorher gleich, weil der Server seine eigene Stärke nutzt.

## 8. Grenzen und offene Punkte

| Punkt | Art |
|---|---|
| Speerangriffe (`piercing_weapon`, `kinetic_weapon`): kein Paket, nur STAB-Schwung | nicht umgesetzt |
| Bogen, Armbrust, Ziegenhorn, Bündel: keine lokale haltbare Benutzung (Munition/Ladezustand fehlen im Snapshot) | nicht umgesetzt |
| `use_cooldown`-Gruppen: Gruppe wird als Item-ID angenommen | [A] |
| Sweep-Angriff, Verzauberungen, Totem | nur Server, nicht geprüft [A] |
| health_boost: nur über das Attribut `max_health` grafisch geprüft | teilweise |
| Hunger-Schaden bei Nahrung 0 und Regeneration bei voller Sättigung | nur Serverwerte, Vergleich Client = Server [S]; Raten nicht gemessen |
| Sprungkraft, langsames Fallen, Schweben in der Physik | nicht umgesetzt ([40](40-gesundheit-hunger-effekte.md) §4) |
| Wasser- und Feuerphysik | nicht umgesetzt (M5-Grenze) |
| Vanilla-Grafik (Herz-Sprites, Armmodell) | bewusst eigene, vereinfachte Darstellung |

## 9. Regression M1–M9

**Ablauf:** `analysis/tools/m10-regression.sh` mit 21 Schritten nacheinander (2026-11-13, 22:14–23:31). Vor jedem Schritt wird der freie Speicher geprüft (Abbruch unter 3 000 MB, freier Speicher war durchgehend 6,2–6,5 GB). Nach jedem Schritt werden die Gradle-Daemons beendet. Speichergrenzen: Server 1 GB, Vanilla-Client 1,5 GB. Ausnahme: Die M7-Auswahl (Schritte 3 und 4) lief mit den Skript-Standards 1,5 GB / 2 GB, weil `M7_*` im ersten Lauf nicht gesetzt war; das ist inzwischen korrigiert. Zusammenfassung in `analysis/runtime-logs/m10-regression.txt`, Ausgaben in `m10-regression-step<N>.out`.

| Gruppe | Prüfung | Ergebnis | M9-Stand (docs/37 §6) |
|---|---|---|---|
| 1 M1–M5 (`m5-run.sh 256`) | JUnit inkl. Integration | **325 / 325**, 0 übersprungen | 311 (+14 neue M10-Tests) |
| | Vanilla-Physikvergleich | 20 Szenarien, 896 Ticks, worst \|Δpos\| = 0, 0 Flag-Abweichungen, 0 Serverkorrekturen | gleich |
| | M4-Weltverwaltung | 657 / 657, 0 nonAir-Abweichungen; M2-, M4-, M5-Terminaltests exit 0 | gleich |
| 2 M6 | Volltest mit Grafik | 9 / 9 Positionen Client = Server, 0 GL-Objekte offen | gleich |
| 3 M7 | Auswahl gegen Vanilla | Überleben 75 / 75, Kreativ 75 / 75 | gleich |
| | Interaktion | **14 / 14** (Wiederholung, siehe unten) | gleich |
| | Licht | Himmel und Block je 1729 / 1729 | gleich |
| | Grafiktest | Serverprüfungen bestanden, 0 GL-Objekte offen | gleich |
| 4 M8 | Inventar s1–s6 | 5 + 25 + 28 + 11 + 16 + 4 = **89 / 89** | gleich |
| | Grafiktest (`m8-gfx-compare.sh`) | 11 / 11 Inventarvergleiche, 0 GL-Objekte offen | gleich |
| 5 M9 | Netzwerk e1 / e2 | 18 / 18, 21 / 21 | gleich |
| | Auswahl gegen Vanilla | **185 / 185 je Modus** (Wiederholung, siehe unten) | gleich |
| | Grafik, Leistung | exit 0, 0 GL-Objekte offen | gleich |
| | Dauerbetrieb 6,5 min | 60 FPS, Heap ohne Anstieg (100–209 MB), **MATCH 20 / 20**, 0 GL-Objekte offen | MATCH 22 / 22 |

Alle Läufe: 0 externe Verbindungen. Konfiguration nach jedem Lauf: `difficulty=easy`, `network-compression-threshold=-1`.

**Vorfälle und Wiederholungen.** Keiner der Vorfälle ist ein Clientfehler; die Ursachen lagen in der Testinfrastruktur und sind behoben.

1. **M9-Auswahl (Schritte 17/18), exit 1.**
   - Ursache: Das Regressionsskript übergab `M7_PICKS` als relativen Pfad. Der Vanilla-Treiber (`PickReferenceClient`) löst ihn relativ zu `research/` auf und brach mit `NoSuchFileException` ab (Absturzbericht in `research/run-pickref/crash-reports`).
   - Korrektur: absoluter Pfad. Zusätzlich setzt das Skript jetzt `M7_SERVER_XMX=1G`, `M7_CLIENT_XMX=1536M` und `M7_MIN_FREE_MB=3000`.
   - Wiederholung: `m7-m10-regress-epicks2-survival` und `m7-m10-regress-epicks2-creative`, je 185 / 185.
2. **M7-Interaktion (Schritt 5), 12 / 14.**
   - Fehlgeschlagen: `efficiency_obsidian` und `lever_on`.
   - Ursache: Das Wasserbecken aus k2 (Glas, x 46–52) blieb nach dem M10-Lauf in der Welt. `m7-interact.sh` räumt x 28–46 frei, entfernt dabei die Glaswand bei x = 46, und das Wasser lief in die Testfläche. Das Clientprotokoll zeigt `adjacent … minecraft:water`; Hebel und Obsidianposition waren überflutet.
   - Korrektur 1: k2 entfernt das Becken am Ende. Nachgeprüft: k2 32 / 32, „Successfully filled 315 block(s)“.
   - Korrektur 2: `m7-interact.sh` leert vor dem Aufbau die Nachbarfläche x 46–53.
   - Wiederholung: `m7-m10-regress-interact2`, 14 / 14.
3. **Dauerbetrieb: 20 statt 22 Entities.**
   - Zwei der vier Zombies starben während des Laufs auf dem Server. Beim Einfrieren meldete der Server 19 Mobs („Modified entity data“, davon 2 Zombies), dazu den zweiten Spieler.
   - Client und Server stimmen in allen 20 vorhandenen Entities überein. Die abweichende Anzahl kommt aus der Spielwelt (Mob-KI und Tageszeit), nicht vom Client.

**Endzustand nach allen Läufen:**
- keine Java-, Gradle- oder Serverprozesse des Projekts aktiv;
- `difficulty=easy`, `network-compression-threshold=-1`;
- Gamerules `spawn_mobs` und `natural_health_regeneration` beide `true` (Abfrage am lokalen Server);
- `server/versions/26.3/server-26.3.jar` sha256 `a362163eec5d1612…` und `server/server.jar` `d052f14d7a173734…`, beide unverändert vom 2026-11-06.

Die Grafikläufe `m9-gfx.txt` enthalten nur `entity-dump`-Markierungen ohne Serverabfrage; ein Abgleich mit `m9-compare.js gfx` ist dort nicht vorgesehen.
