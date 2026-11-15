# 39 – Kampfsystem: Architektur (M10)

**Stand:** 2026-11-13 · Protokoll: [38](38-kampf-und-survival-protokoll.md) · Gesundheit/Hunger/Effekte: [40](40-gesundheit-hunger-effekte.md) · Tests: [41](41-m10-testbericht.md)

Das Kampfsystem erweitert die bestehende Blockinteraktion (M7, [27](27-blockinteraktion.md)) und die Entity-Verwaltung (M9, [35](35-entity-architektur.md)). Es braucht keine Darstellung und läuft deshalb auch im Terminalclient ohne LWJGL. Es gibt keine neue Thread-Art und keine Mojang-Klassen. Die Abläufe folgen den in [38](38-kampf-und-survival-protokoll.md) beschriebenen Vanilla-Schritten.

## 1. Grundsatz: Vorhersage vs. Bestätigung

| Was | Wer entscheidet | Clientseitig | Bestätigung durch |
|---|---|---|---|
| Ziel unter dem Fadenkreuz | Client (wie Vanilla `LocalPlayer#pick`) | `EntityPick` (M9) auf der vorhergesagten Welt | Server prüft Reichweite in `handleAttack`/`handleInteract` |
| Angriffsstärke/Abklingzeit | Client und Server getrennt | `PlayerSurvivalState#attackStrength` (Anzeige) | Server rechnet den Schaden mit **seiner** Stärke |
| Armschwung | nur Darstellung | `PlayerSurvivalState#swing` (`SwingState`) | Server sendet ihn **anderen** Spielern als `swing_animation` |
| Treffer und Schaden | Server | – (keine Vorhersage) | `damage_event` (Ursache = eigene ID), `set_entity_data` Gesundheit (Index 9) |
| Rückstoß auf Ziele | Server | Darstellung über Positionspakete (M9) | `set_entity_motion` / Positionen |
| Rückstoß auf den eigenen Spieler | Server | Physik übernimmt die Geschwindigkeit | `set_entity_motion` mit eigener ID |
| Eigene Gesundheit, Hunger, Effekte | Server | nur Anzeige und Zeitablauf | `set_health`, `update_mob_effect`, `remove_mob_effect` |
| Tod | Server | Todesbildschirm/Wartezustand | `set_health` 0, `player_combat_kill` |

Ein Angriff wird lokal **nur** animiert (Schwung, Zurücksetzen der Abklingzeit). Ob er getroffen hat, steht erst fest, wenn der Server ein `damage_event` mit der eigenen Entity-ID als Ursache sendet. Dann zählt der Client den Treffer als bestätigt (`ClientEntity#confirmedHits`), und der Grafikclient zeigt gelbe Trefferpartikel. Schaden ohne eigene Ursache erscheint rot.

## 2. Bausteine

| Klasse | Aufgabe |
|---|---|
| `session/BlockInteraction` | Tastenverarbeitung (`handleKeybinds`), Ziel (Block **oder** Entity), `startAttack`/`continueAttack`/`startUseItem`, Paketausgabe |
| `entity/EntityPick` (M9) | Strahl gegen Entity-Hitboxen und Blöcke; Ergebnis ENTITY, BLOCK oder Fehlschlag |
| `session/PlayerSurvivalState` | eigener Überlebenszustand, Abklingzeit, Itembenutzung (Essen), Armschwung, Ereignisprotokoll |
| `session/ClientSession#handleSurvival` | leitet Survival-Pakete vor der Entity-Verwaltung an den Zustand weiter (eigene ID) |
| `entity/EntityManager` | `damage_event` für fremde Entities (Trefferzähler, `hurtTick` für das rote Aufblinken) |
| `protocol/packet/SurvivalPacketDecoder` | Decodierung der Survival-Pakete ([38](38-kampf-und-survival-protokoll.md) §1) |
| `cli/CombatCommands` | Terminalbefehle `survival`, `hit`, `hitnext`, `hitpair`, `hitfall`, `eat`, `respawn`, `slog` |
| `gl/SurvivalHud`, `gl/EntityRenderer` | HUD (Herzen, Rüstung, Hunger, Abklingzeit, Effekte, Schwung, Todesbildschirm), Trefferpartikel |

**Threads:**
- **Netzwerk-Thread:** schreibt Serverpakete in `PlayerSurvivalState` (synchronisiert).
- **Tick-Thread (20 Hz):** `PlayerSurvivalState#tick` und `BlockInteraction#tick` (Ziel, Tasten, Pakete).
- **Render-Thread:** liest nur unveränderliche Momentaufnahmen (`PlayerSurvivalState.View`, `EntityManager.View`).

## 3. Ziel und Reichweite

In jedem Tick (`BlockInteraction#tick`):

1. `BlockRaycast.pick` liefert den Block (Reichweite `block_interaction_range`, Standard 4,5).
2. `EntityPick.pick` prüft die Entities der Momentaufnahme (Attribut `entity_interaction_range` vom Server, Standard 3,0).
3. Ist eine Entity näher als der Block, **ersetzt** sie das Blockziel. Liegt eine Entity außerhalb der Reichweite vor dem Block, wird das Ziel zum Fehlschlag (wie Vanilla `filterHitResult`). Ein Klick baut dann keinen Block ab.

Damit sind Blockabbau und Entity-Angriff getrennt:
- Bei einem Entity-Ziel sendet `startAttack` kein `player_action START_DESTROY`.
- `continueAttack` setzt den Abbau nur bei einem Blockziel fort.
- Während einer Itembenutzung (Essen) ruht der Abbau.

Die Entity-ID im `attack`-Paket ist die Netzwerk-ID aus der Momentaufnahme (`EntityManager.View#id`). In k1 stimmte sie in allen Fällen mit der vom Server gemeldeten Ziel-UUID überein.

## 4. Abläufe

### Linksklick (`startAttack`, entspricht `Minecraft#startAttack`)

1. `missTime > 0` → nichts.
2. Item mit `piercing_weapon` (Speere) → Vanilla nutzt `piercingAttack`, einen eigenen Angriffsweg mit Aufladung. **Nicht umgesetzt**; der Client sendet dann bewusst kein Paket und protokolliert das ([41](41-m10-testbericht.md) §8).
3. **Entity-Ziel:**
   - `ensureHasSentCarriedItem`;
   - `attack(entityId)`;
   - Angriffsstärke zurücksetzen;
   - Schwung (`attack_animation`);
   - `punch`.
4. **Blockziel:** `START_DESTROY` wie in M7, dann Schwung und `punch`.
5. **Fehlschlag:** `missTime = 10` (nicht im Kreativmodus), Angriffsstärke zurücksetzen, Schwung, `punch`.

In 26.3 gibt es kein `swing`-Paket mehr. Der Server erkennt den Schwung über `punch`. Das Server-Paketprotokoll zeigt `attack` und `punch` in derselben Millisekunde, wie bei Vanilla.

### Rechtsklick (`startUseItem`)

1. Je Hand: Bei einem Entity-Ziel wird `interact(entityId, hand, Ort relativ zur Entity als LpVec3, schleichen)` gesendet.
2. Danach folgt wie in Vanilla die Itembenutzung (`use_item`). Das Ergebnis von `interactOn` sagt der Client nicht vorher (PASS angenommen); das Server-Paketprotokoll zeigt dieselbe Folge `interact` → `use_item` wie Vanilla.
3. Beim Blockziel greift M7 (`use_item_on`). Eine erfolgreiche Platzierung löst den Schwung mit der `interact_animation` aus.

### Itembenutzung (Essen/Trinken)

- `use_item` für ein Item aus der Verzehrtabelle (`consumables-777.txt`, 43 Einträge aus den Item-Komponenten) startet die Benutzung, wenn das Item essbar ist (`canEat`: Hunger < 20, `can_always_eat` oder Kreativ).
- Die Dauer kommt aus `consume_seconds · 20`.
- Loslassen der Taste vor dem Ende → `player_action RELEASE_USE_ITEM` (Abbruch).
- Das Ende bestätigt der Server mit `entity_event 9` (USE_ITEM_COMPLETE) sowie `set_health` und `container_set_slot`.

### Abklingzeit (`PlayerSurvivalState#attackStrength`)

- `ticker` zählt pro Client-Tick.
- `delay = 20 / attack_speed`; das Attribut ist synchronisiert (`attack_damage` dagegen nicht, [38](38-kampf-und-survival-protokoll.md) §2).
- `stärke(pt) = clamp((ticker + pt) / delay, 0, 1)`.
- **Zurücksetzen bei:**
  - Angriff;
  - Fehlschlag;
  - Wechsel auf einen **anderen Item-Typ** in der Haupthand (`Player#tick` mit `ItemStack#isSameItem`). Haltbarkeit oder Anzahl zählen nicht.
- **Anzeige:** Balken unter dem Fadenkreuz, solange die Stärke < 1 ist. Ist die Stärke 1 und das Ziel eine Entity, erscheint ein Punkt.

### Armschwung (`PlayerSurvivalState#swing`, entspricht `LivingEntity.SwingState`)

- **Typ und Dauer** aus `attack_animation` bzw. `interact_animation`. Standard ist WHACK, 6 Ticks; Speere nutzen STAB mit 13–23 Ticks. Die Tabelle `item-actions-777.txt` wird mit `analysis/tools/gen-item-actions.js` aus den Item-Komponenten erzeugt; sie enthält auch Stichwaffen und haltbare Benutzungen.
- **Dauer mit Effekten** (`getModifiedSwingDuration`): Eile bzw. Meereskraft −(1 + Stufe); Abbaulähmung +2·(1 + Stufe).
- **Neustart** (`startIfAble`): erst nach der halben Dauer.
- **Ende:** nach Dauer + 1 Ticks.
- **Fortschritt** wie `getAnimation(pt)`, interpoliert zwischen den Ticks.
- **Anzeige:** eigene, vereinfachte Ich-Perspektive (Arm aus zwei gedrehten Rechtecken). WHACK schwingt als Bogen, STAB stößt nach vorn. Die Vanilla-Arm- und Itemmodelle werden **nicht** nachgebildet.

### Rückstoß

- **Eigener Spieler:** `set_entity_motion` mit der eigenen ID → `MovementController#applyServerMotion` ersetzt die Geschwindigkeit der Physik (wie `lerpMotion`). Die Bewegung danach rechnet die eigene Physik; der Server prüft sie wie jede Bewegung.
- **Andere Entities:** nur Darstellung der vom Server gesendeten Positionen (M9).

## 5. Tod und Wiederbelebung

**Erkennung:**
- `set_health` ≤ 0 setzt den Zustand „tot“ (Vanilla `isDeadOrDying`).
- `player_combat_kill` liefert die Todesnachricht.
- Nach einem erneuten Login im Todeszustand sendet der Server nur `set_health` 0 ([41](41-m10-testbericht.md) §6). Der Client gilt dann ebenfalls als tot, ohne Nachricht.

**Automatische Wiederbelebung** (`ClientSession#autoRespawn`):

| Client | Standard | Verhalten |
|---|---|---|
| Grafikclient | **aus** | Todesbildschirm; die Maus wird freigegeben; nur die Schaltfläche „Wiederbeleben“ sendet `client_command PERFORM_RESPAWN`. Welt-Eingaben sind gesperrt. |
| Grafikclient mit `--auto-respawn` | an | für Headless-/Autotest-Abläufe |
| Terminalclient | an | M5-Verhalten für die Headless-Regression; `autorespawn on/off` zur Laufzeit |
| Terminalclient mit `--no-auto-respawn` | aus | schon beim Login wartend (z. B. Test „erneuter Login im Todeszustand“); Wiederbelebung nur mit dem Befehl `respawn` |

`requestRespawn` sendet je Tod höchstens einmal. Der Zustand „Wiederbelebung angefordert“ endet erst, wenn `set_health` > 0 eintrifft.

Nach `respawn` (Paket) gilt:
- Effekte, Absorption, Luft und Itembenutzung werden verworfen. Der Server sendet weiterbestehende Werte neu.
- Welt, Entities, Inventar und Position kommen wie bei M4/M8/M9 neu vom Server.

## 6. Grafik und Ressourcen

- **Statt eigener GL-Objekte:** `SurvivalHud` zeichnet über den bestehenden `HudRenderer` (Rechteckpuffer) sowie zwei `TextPanel`-Texturen (Effekte, Todesbildschirm).
- **Trefferpartikel:** liegen im bestehenden Würfel-Puffer des `EntityRenderer`, höchstens 400.
- **Fenstergrößen:** Alle Maße skalieren mit der Fensterhöhe (`scale = h / 720`). Die Schaltfläche wird je Bild neu berechnet; Treffer werden in Fensterkoordinaten geprüft.
- **Freigabe:** `SurvivalHud#close` gibt die Texturen frei (try-with-resources in `GameView`). Die Prüfung der offenen GL-Objekte am Ende ergab 0 ([41](41-m10-testbericht.md) §5).
- **Todesnachricht:** Die Übersetzung liest zur Laufzeit die lokale `en_us.json` aus dem Client-JAR. Sie wird nicht kopiert und nicht in Build-Artefakte aufgenommen. Ohne lokale Ressourcen zeigt der Client den Übersetzungsschlüssel mit Argumenten.

## 7. Bewusste Grenzen

- **Speere** (`piercing_weapon`, `kinetic_weapon`, `minimum_attack_charge`): kein Angriff, nur Schwunganimation.
- **Keine Schadensvorhersage:** Gesundheitsbalken und Treffer kommen ausschließlich vom Server.
- **Haltbare Benutzung:** Schild und Speere (`blocks_attacks`, `kinetic_weapon`), Dreizack, Fernrohr und Pinsel starten wie in Vanilla eine lokale Benutzung; Loslassen sendet `RELEASE_USE_ITEM`. Item-Abklingzeiten (`cooldown`) werden gespeichert; ein Item auf Abklingzeit ergibt PASS. Beendet der Server die Benutzung (Schild durch Axt deaktiviert), folgt der Client über die Living-Flags (Index 8). Blockierwinkel und Schadensminderung entscheidet nur der Server (Test k2 §8). **Nicht** umgesetzt: Bogen und Armbrust (Munition bzw. Ladezustand sind nicht im Interaktions-Snapshot), Ziegenhorn, Bündel, Enderauge sowie `use_cooldown`-Gruppen (Gruppe = Item-ID).
- **Sweep, Verzauberungen, Kampf auf Reittieren** und ähnliche Fälle entscheidet nur der Server; eine clientseitige Darstellung fehlt.
