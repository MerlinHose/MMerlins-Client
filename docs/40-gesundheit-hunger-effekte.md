# 40 – Gesundheit, Hunger und Statuseffekte (M10)

**Stand:** 2026-11-13 · Protokoll und Formeln: [38](38-kampf-und-survival-protokoll.md) §4–7 · Architektur: [39](39-kampfsystem-architektur.md) · Tests: [41](41-m10-testbericht.md)

Der eigene Überlebenszustand liegt in `session/PlayerSurvivalState`. Alle Werte, die das Spiel beeinflussen, kommen vom Server. Der Client rechnet nur weiter, was Vanilla ebenfalls clientseitig rechnet:
- Abklingzeit;
- Itembenutzungsdauer;
- Restlaufzeit der Effekte;
- Armschwung.

Erschöpfung, Regeneration, Hungerschaden und Effektwirkungen auf die Gesundheit rechnet **nur der Server**.

## 1. Zustand und Quellen

| Wert | Quelle (26.3) | Feld / Methode |
|---|---|---|
| Gesundheit, Nahrung, Sättigung | `set_health` (float, VarInt, float) | `onHealth` |
| Maximalgesundheit | Attribut 23 `max_health` (`update_attributes`) | `onAttributes` |
| Absorption | Datenfeld 17 des eigenen Spielers (float) | `onOwnData` |
| Rüstung, Zähigkeit | Attribute 1 `armor`, 2 `armor_toughness` | `onAttributes` |
| Angriffsgeschwindigkeit | Attribut 5 `attack_speed` | Abklingzeit |
| Angriffsschaden | Attribut 3 – **nicht synchronisiert** (nur Basiswert 1,0 sichtbar) | nur Protokoll |
| Rückstoßresistenz | Attribut 20 | Anzeige |
| Bewegungsgeschwindigkeit | Attribut 26 `movement_speed` (inkl. Effekt-Modifikatoren) | `PlayerPhysics#setMovementSpeed` |
| Luft | Datenfeld 1 (VarInt, Standard 300) | Anzeige, Ertrinken |
| Effekte | `update_mob_effect` / `remove_mob_effect` | `onEffect` / `onEffectRemoved`; Restzeit zählt je Tick herunter |
| Erschöpfung | **nicht übertragen** | – |
| Erfahrung | `set_experience` | Anzeige im Terminal |
| Tod | `set_health` ≤ 0, `player_combat_kill` | `onHealth`, `onDeath` |

**Wichtiger Decoder-Befund:**
- Das eigene `set_entity_data` enthält Effektpartikel (Index 10, Serialisierer PARTICLES 17) **vor** der Absorption (Index 17).
- Bis M9 brach der Decoder bei Partikeln ab, deshalb blieb die Absorption 0.
- M10 decodiert die Partikeltypen. Die Optionen hängen vom Typ ab ([38](38-kampf-und-survival-protokoll.md); `EntityPacketDecoder#particle`).
- Unbekannte Optionen werden weiterhin als „nicht unterstützt“ gemeldet; es wird nicht geraten.

## 2. Gesundheit und Schaden

- **Schadensrückmeldung:** Sinkt die Gesundheit, startet `hurtTicks = 10`. Die Herzen blinken, und ein roter Bildschirmrand verblasst über 10 Ticks.
- **Schadensart:** `damage_event` liefert die Art als Registry-ID. Die Namen stammen aus der `damage_type`-Registry der Konfigurationsphase (`ClientSession#damageTypeName`). Beispiele: `fall`, `in_fire`/`on_fire`, `drown`, `mob_attack`, `arrow`, `player_attack`, `generic_kill`.
- **Fallschaden** wird mit der eigenen Physik ausgelöst. Der Server rechnet `ceil(Fallhöhe − 3)` (Test: 9). Das Ergebnis kommt nur vom Server.
- **Feuer und Ertrinken** prüft nur der Server.
  - Der Client hat **keine Wasserphysik** (M5-Grenze, [21](21-physikmodell.md) §9).
  - Die Tests trennen deshalb Schaden und Bewegung: Der Spieler wird per `tp` in Feuer bzw. unter Wasser gesetzt und bewegt sich nicht.
  - Geprüft werden nur die Folgen (Luft, Schadensart, Gesundheit gleich dem Serverwert).
- **Rüstung:** Wirkung und Haltbarkeitsverlust entscheidet der Server ([38](38-kampf-und-survival-protokoll.md) §4). Der Client zeigt den Rüstungswert aus dem Attribut und den Gegenstandsschaden aus `container_set_slot`.

## 3. Hunger und Essen

**Anzeige** (Nahrung 0–20) rechts über der Hotbar, 10 Symbole, wie in Vanilla von rechts nach links gefüllt.

**Verzehrtabelle** `client/src/main/resources/protocol/consumables-777.txt`:
- erzeugt mit `analysis/tools/gen-consumables.js` aus den Item-Komponenten des Datengenerators;
- 43 Items mit Dauer (`consume_seconds · 20`), Nährwert, Sättigung, `can_always_eat` und Animation;
- Beispiele:
  - gebratenes Rindfleisch 32 Ticks, 8 / 12,8;
  - getrockneter Seetang 16 Ticks;
  - goldener Apfel immer essbar;
  - Milch ohne `food`.

**Ablauf:**
1. Taste halten → `use_item`. Der Client startet die Benutzung, wenn `canEat` gilt (Hunger < 20, `can_always_eat` oder Kreativ). Sonst passiert wie in Vanilla nichts.
2. Mehrere Ticks lang: `usingItem`. Abbau ruht; Linksklicks werden ignoriert.
3. **Abbruch:** Loslassen vor dem Ende → `player_action RELEASE_USE_ITEM`. Der Server verbraucht nichts (Test: Stapel und Nahrung unverändert).
4. **Abschluss:**
   - Nach der Dauer endet die Benutzung clientseitig (`completeUsingItem`).
   - Der Server sendet `entity_event 9`, `container_set_slot` (Stapel − 1) und `set_health` mit neuer Nahrung und Sättigung.
   - Nahrung = min(20, alt + Nährwert); Sättigung = min(Nahrung, alt + Sättigungswert). Beides wird **nur** vom Server übernommen.

**Regeneration und Hunger:**
- Der Client prüft nur, dass Gesundheit und Nahrung nach jedem `set_health` dem Serverwert entsprechen.
- Für reproduzierbare Messungen schalten die Tests die Gamerule `natural_health_regeneration` vorübergehend aus. Danach wird sie auf den gelesenen Wert (`true`) zurückgesetzt.

## 4. Statuseffekte

Die IDs stammen aus der `mob_effect`-Registry von 26.3 (`registries.json`; Tabelle in [38](38-kampf-und-survival-protokoll.md) §6). Namen zeigt der Client aus derselben Registry an.

| Effekt | Wirkung im Server | Wirkung im eigenen Client | Test ([41](41-m10-testbericht.md)) |
|---|---|---|---|
| speed (0) | Modifikator auf `movement_speed` | Physik nutzt das Attribut (+20 %/Stufe) | Stufe II: Attribut 0,14 gleich dem Server, Physik 0,14 |
| slowness (1) | Modifikator auf `movement_speed` | wie oben (−15 %/Stufe) | Stufe I: Physik 0,085; ohne Effekt wieder 0,1 |
| haste (2) | Abbaugeschwindigkeit, Schwungdauer | Abbaufortschritt ×(1 + 0,2·(Stufe + 1)); Schwung −(1 + Stufe) Ticks | Unit-Test |
| mining_fatigue (3) | Abbau ×0,3^(Stufe + 1), Schwung länger | wie Vanilla `getDestroySpeed`, Schwung +2·(1 + Stufe) | Unit-Test |
| strength (4) | Modifikator `attack_damage` | keine (Schaden nur im Server) | Stufe I: Eisenschwert 6 + 3 = 9 am Schwein |
| weakness (17) | Modifikator `attack_damage` | keine | Stufe I: Faust 1 − 4 < 0 → kein Schaden |
| resistance (10) | Schaden ·(1 − 0,2·(Stufe + 1)) | keine | Stufe II: 5 Schaden → 3 |
| instant_health (5) / instant_damage (6) | einmalig ±4·2^Stufe | keine (kein Dauer-Effekt) | Schaden I: −6; Heilung I: +4; kein bleibender Effekt |
| regeneration (9) | Heilung je 50 >> Stufe Ticks | Restzeit-Anzeige | Stufe II: Gesundheit steigt, danach gleich dem Server |
| poison (18) | Schaden je 25 >> Stufe Ticks, nicht unter 1 | Restzeit-Anzeige | Gesundheit sinkt, Schadensart `magic` |
| absorption (21), health_boost (20) | Absorption, Maximalgesundheit | Herzen in Gelb bzw. zusätzliche Reihen | Goldener Apfel: Absorption 4 = Server; Maximalgesundheit 40 grafisch über das Attribut (health_boost selbst nicht getestet) |

**Weitere Regeln:**
- **Anzeige** oben rechts: Name, Stufe (römisch), Restzeit. Negative Effekte sind rot, positive blau. Unendliche Dauer (−1) erscheint als ∞.
- **Restzeit:** Der Server sendet die Dauer nur beim Hinzufügen oder Ändern. Der Client zählt je Tick herunter, wie Vanilla `MobEffectInstance#tickClient`. Das Ende kommt immer als `remove_mob_effect`.
- **Flags** (Byte):
  - 1 Umgebungseffekt;
  - 2 Partikel sichtbar;
  - 4 Symbol anzeigen;
  - 8 Mischung.
  
  Sie werden gespeichert, beeinflussen die einfache Anzeige aber nicht.

### Auswirkungen auf die bestätigte Vanilla-Physik

- **Ohne Effekte unverändert:** `movement_speed` ist 0,1; die M5-Physik (896 Ticks, bitgenau) nutzt weiterhin genau diesen Wert. Die Regression läuft in [41](41-m10-testbericht.md) §9.
- **Mit speed/slowness** übernimmt die Physik den Attributwert vom Server. Das entspricht Vanilla, wo `getSpeed()` das Attribut liest.
- **Sprinten** simuliert der Client weiterhin nicht (M5-Grenze). Es gibt deshalb keinen doppelten Sprint-Modifikator.
- **Nicht umgesetzt** (dokumentierte Grenzen): Sprungkraft (jump_boost), slow_falling, levitation und dolphins_grace. Die Physik rechnet diese Effekte nicht; unter ihnen weicht die vorhergesagte Bewegung ab, und der Server korrigiert per Teleport (wie bei jeder Abweichung, M5).
- **Abbau** (M7): haste, conduit_power und mining_fatigue gehen in `destroyProgressPerTick` ein. Ohne Effekte bleibt die M7-Rechnung unverändert (Regression M7 in [41](41-m10-testbericht.md) §9).

## 5. Tod, Todesbildschirm, Wiederbelebung

**Tod:**
- `set_health` ≤ 0 → tot. Der Client bricht die Itembenutzung ab; Eingaben in die Welt sind im Grafikclient gesperrt.
- `player_combat_kill` → Todesnachricht als Text-Komponente.
- Der Grafikclient übersetzt sie mit der lokalen `en_us.json`, Platzhalter `%s` und `%1$s` (`TextComponents#toPlainText`). Ein Beispiel ist „ProtoBotGfx was killed“.

**Todesbildschirm** (Grafikclient, Standard):
- abgedunkelter roter Hintergrund mit „Du bist gestorben!“, der Nachricht und der Schaltfläche „Wiederbeleben“;
- Die Maus wird freigegeben. Der Klick prüft die Schaltfläche in Fensterkoordinaten und funktioniert bei jeder Fenstergröße (getestet 1280×720 und 1600×900).

**Wiederbelebung:**
- `client_command PERFORM_RESPAWN`, nur auf Wunsch des Spielers. Headless-Tests schalten die Automatik ausdrücklich ein ([39](39-kampfsystem-architektur.md) §5).
- Danach sendet der Server:
  - `respawn`;
  - Position am Spawnpunkt;
  - `set_health` 20/20/5;
  - Attribute;
  - das Inventar (leer, ohne keepInventory);
  - Welt und Entities neu.
- Der Client verwirft dabei Effekte, Absorption und Luft.

**Trennung im Todeszustand:** Der Server speichert die Gesundheit 0. Nach dem erneuten Login sendet er `set_health` 0, aber kein `player_combat_kill`. Der Client erkennt den Tod trotzdem und wartet ohne Nachricht, falls die Automatik aus ist ([41](41-m10-testbericht.md) §6).

## 6. Grenzen

- **Keine Vorhersage** von Erschöpfung, natürlicher Regeneration und Effektschaden. Die Anzeige springt mit jedem `set_health`, wie bei Vanilla, das ebenfalls nur Serverwerte zeigt.
- **Vereinfachte Grafik:** Symbole als Flächen statt Vanilla-Sprites; keine Hardcore-Herzen, kein Wackeln bei niedriger Gesundheit; Hunger-Effekt nicht grün eingefärbt.
- **Feuer- und Wasserdarstellung** am Spieler fehlt (keine Flüssigkeitsphysik).
