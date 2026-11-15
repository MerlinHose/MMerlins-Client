# 38 – Kampf- und Survival-Protokoll (M10)

**Stand:** 2026-11-13 · Minecraft 26.3 / Protokoll 777 · Architektur: [39](39-kampfsystem-architektur.md) · Gesundheit/Hunger/Effekte: [40](40-gesundheit-hunger-effekte.md) · Ergebnisse: [41](41-m10-testbericht.md)

**Quellen:**
- Loom-Quellen `analysis/mc-src/` (Referenzformat wie in [README](README.md));
- Paket-IDs aus `packets-777.txt`;
- Registry-IDs aus `registries.json`;
- Item-Komponenten aus `reports/minecraft/components/item/*.json` (Datengenerator).

Es wurde kein Mojang-Code übernommen. Formate aus älteren Versionen wurden nicht ungeprüft übernommen; alle Abweichungen zu früheren Versionen sind unten vermerkt.

## 1. Paket-IDs (Play, 26.3)

| Richtung | ID | Paket | Felder |
|---|---|---|---|
| → | 1 | `attack` | Entity-ID (VarInt) – **neu in 26.x**, früher `interact` mit Aktion ATTACK |
| → | 26 | `interact` | Entity-ID, Hand (VarInt 0/1), Ort (`LpVec3`, relativ zur Entity-Position), Schleichen (bool) |
| → | – | `swing` | gibt es in 26.3 **nicht mehr** (kein Eintrag in `packets-777.txt`); der Armschwung steckt in `punch` (M7) bzw. im Serverablauf |
| → | 67 | `use_item` | Hand, Sequenz, yRot, xRot (M7) |
| → | 41 | `player_action` | 6 = RELEASE_USE_ITEM (Essen abbrechen), 5/4 Werfen (M8) |
| → | 12 | `client_command` | VarInt: 0 PERFORM_RESPAWN, 1 REQUEST_STATS, 2 REQUEST_GAMERULE_VALUES |
| ← | 106 | `set_health` | Gesundheit (float), Nahrung (VarInt), Sättigung (float) |
| ← | 25 | `damage_event` | Entity-ID, Schadenstyp (VarInt, Registry `damage_type` aus der Konfiguration), Verursacher-ID + 1, direkte Quelle-ID + 1 (0 = keine), opt. Quellposition (3 × double) |
| ← | 43 | `hurt_animation` | Entity-ID, Gier (float) – M9 |
| ← | 34 | `entity_event` | int + byte: 3 Tod, 9 Benutzung beendet (Essen fertig), 35 Totem, 60 Todesrauch |
| ← | 135 | `update_mob_effect` | Entity-ID, Effekt (VarInt, Registry `mob_effect`), Verstärker (VarInt), Dauer in Ticks (VarInt, −1 = unendlich), Flags (byte: 1 Umgebung, 2 Partikel sichtbar, 4 Symbol, 8 Überblenden) |
| ← | 79 | `remove_mob_effect` | Entity-ID, Effekt (VarInt) |
| ← | 69 | `player_combat_kill` | Spieler-ID, Todesnachricht (Text, NBT) |
| ← | 67 / 68 | `player_combat_end` / `_enter` | Dauer (VarInt) / leer |
| ← | 84 | `respawn` | wie M3 (Dimension, Daten, Beibehalten-Flags) |
| ← | 105 | `set_experience` | Fortschritt (float), Stufe, Gesamt (VarInt) |
| ← | 22 | `cooldown` | Gruppe (Identifier), Dauer (VarInt) – Item-Abklingzeit (Schild, Enderperle) |
| ← | 39 | `game_event` | 11 = IMMEDIATE_RESPAWN (Parameter 1: ohne Todesbildschirm) |
| ← | 101 / 134 | `set_entity_data` / `update_attributes` | auch für den eigenen Spieler: Absorption (Index 17), Lebewesen-Flags (Index 8: 0x01 benutzt Gegenstand, 0x02 Nebenhand); Attribute `max_health` 23, `armor` 1, `armor_toughness` 2, `attack_damage` 3, `attack_speed` 5, `attack_knockback` 4, `knockback_resistance` 20, `entity_interaction_range` 13 |

## 2. Angriff

**Client** (`Minecraft#startAttack`, `client/Minecraft.java:1618`):
- Kein Angriff bei `missTime > 0`, belegten Händen oder `cannotAttackWithItem(item, 0)`.
- Auswahl ENTITY: `MultiPlayerGameMode#attack` (`:435`):
  1. gewählten Hotbar-Platz senden;
  2. **`attack`** senden;
  3. lokal `player.attack(entity)` (nur Darstellung/Vorhersage);
  4. `resetAttackStrengthTicker`.
- Auswahl BLOCK (nicht Luft): Abbau beginnen (M7).
- MISS oder Luft: `missTime = 10` (nicht Kreativ), Angriffsstärke zurücksetzen.
- Danach immer: lokaler Armschwung mit der **Angriffsanimation** des Items und **`punch`**.

**Server** (`ServerGamePacketListenerImpl#handleAttack`, `:1846`):
1. Nur geladen, nicht Zuschauer.
2. Ziel nicht vorhanden oder außerhalb der Weltgrenze → stillschweigend ignoriert.
3. Reichweite: `isWithinAttackRange(item, Hitbox, 3.0)`, also Entity-Reichweite plus 3 Blöcke Puffer.
4. Ungültige Ziele (Item, Erfahrungskugel, eigener Spieler, nicht angreifbarer Pfeil) → **Trennung** („invalid_entity_attacked“).
5. `cannotAttackWithItem(item, 5)` → ignoriert.
6. Sonst `Player#attack`.

**Schaden** (`Player#attack`, `world/entity/player/Player.java:933`):

| Größe | Formel |
|---|---|
| Angriffsstärke s | `clamp((ticker + 0,5) / Verzögerung, 0, 1)`; Verzögerung = 20 / `attack_speed` Ticks; `ticker` zählt je Server-Tick und wird beim Angriff, Schwung und Hotbar-Wechsel zurückgesetzt |
| Grundschaden | `attack_damage` · (0,2 + 0,8 · s²) |
| Kritisch (· 1,5) | s > 0,9 **und** Fallhöhe > 0, nicht am Boden, nicht kletternd, nicht im Wasser, nicht reitend, nicht sprintend, Ziel ist Lebewesen |
| Rückstoß extra | sprintend bei s > 0,9 (+0,5) oder Attribut `attack_knockback`; Ziel erhält `knockback(Stärke, sin yaw, −cos yaw)` |
| Fegen | s > 0,9, nicht kritisch, nicht sprintend, am Boden, langsam (< 2,5 · Geschwindigkeit), Schwert in der Haupthand |
| Erschöpfung | +0,1 je Treffer |

**Werte aus den Item-Komponenten** (Datengenerator):

| Waffe | `attack_damage` (inkl. Basis 1) | `attack_speed` (Basis 4) | Verzögerung |
|---|---|---|---|
| Hand | 1 | 4,0 | 5 Ticks |
| Holzschwert | 4 | 1,6 | 12,5 Ticks |
| Eisenschwert | 6 | 1,6 | 12,5 Ticks |
| Diamantschwert | 7 | 1,6 | 12,5 Ticks |
| Eisenaxt | 9 | 0,9 | 22,2 Ticks (`weapon`: 2 Haltbarkeit je Treffer, Schild 5 s gesperrt) |

**Animationskomponenten** (`DataComponents#ATTACK_ANIMATION` / `INTERACT_ANIMATION`, `core/component/DataComponents.java:222`):
- `SwingAnimation(Typ, Dauer)` mit den Typen NONE 0, WHACK 1, STAB 2.
- Standard für alle Items: WHACK, 6 Ticks.
- Abweichend nur die Speere: STAB, 13–23 Ticks, dazu `minimum_attack_charge` 1 und `piercing_weapon` (eigener Angriffsweg `piercingAttack`, in M10 nicht umgesetzt).
- Die Dauer wird durch Eile/Abbaulähmung verändert (`LivingEntity#getModifiedSwingDuration`).
- Der Server sendet Schwünge anderer Spieler als `swing_animation` (Hand, Typ, Dauer, M9).

## 3. Interaktion mit Entities (Rechtsklick)

**Client** (`Minecraft#startUseItem`, `:1693`):
1. Für beide Hände nacheinander: bei Entity-Auswahl innerhalb der Entity-Reichweite **`interact`** senden (Ort = Trefferpunkt − Entity-Position, Schleichen).
2. Ist die lokale Vorhersage `interactOn` erfolgreich → Ende; sonst weiter wie ohne Ziel: **`use_item`** für nicht leere Hände.
3. Der Armschwung nutzt die **Interaktionsanimation** des Items.

**Server** (`handleInteract`, `:1875`):
- Reichweite = Entity-Reichweite + 3.
- Bei Erfolg Schwung mit der Interaktionsanimation und Zurücksetzen der Angriffsstärke.

## 4. Schaden am Ziel (`LivingEntity#hurtServer`, `world/entity/LivingEntity.java:1170`)

1. **Schild:** `applyItemBlocking` mit `blocks_attacks`.
2. **Helm** bei fallenden Blöcken: Schaden · 0,75.
3. **Unverwundbarkeit:**
   - Nach einem vollen Treffer läuft `damageCooldownTime = 20`.
   - Solange sie größer als 10 ist, wirkt nur der Mehrbetrag über den letzten Schaden; der Rest wird ignoriert, ohne Rückstoß und ohne `damage_event`.
4. **Reduktionen** (`actuallyHurt`, `:1951`):
   - Rüstung (`CombatRules#getDamageAfterAbsorb`): Zähigkeit t = 2 + `armor_toughness`/4; wirksame Rüstung = clamp(Rüstung − Schaden/t, 0,2 · Rüstung, 20); Schaden · (1 − wirksam/25).
   - Resistenz: · (25 − 5 · (Stufe + 1)) / 25.
   - Verzauberungsschutz.
   - Absorption wird zuerst verbraucht.
5. **Folgen** bei vollem Treffer:
   - `damage_event` an alle;
   - Rückstoß `knockback(0,4, …)`: Stärke · (1 − `knockback_resistance`), horizontal halbe alte Geschwindigkeit minus Richtung, vertikal min(0,4, y/2 + Stärke) am Boden;
   - für Spieler-Ziele `set_entity_motion`;
   - bei Gesundheit ≤ 0 Tod (Totem prüfen).
6. **Haltbarkeit:**
   - Rüstung verliert je Treffer max(1, Schaden/4) (`hurtArmor`).
   - Waffen verlieren 1 (Schwert) bzw. 2 (Axt) je Treffer.
   - Beides kommt beim Client als `container_set_slot` mit `damage` an (M8).

## 5. Gesundheit, Hunger, Regeneration

- **`set_health`** wird gesendet, wenn sich Gesundheit, Nahrung oder Sättigung (Sättigung == 0 ↔ ≠ 0) ändern.
- **Absorption** kommt als Datenfeld 17 des eigenen Spielers, **Maximalgesundheit** als Attribut 23.
- **Erschöpfung wird nicht übertragen.** Der Client kennt sie nicht und rechnet sie nicht nach.

**`FoodData#tick`** (`world/food/FoodData.java`):
- Erschöpfung > 4 → −1 Sättigung, sonst −1 Nahrung.
- Natürliche Regeneration:
  - Sättigung > 0 und Nahrung 20: alle 10 Ticks Heilung min(Sättigung, 6)/6;
  - Nahrung ≥ 18: alle 80 Ticks +1;
  - Nahrung 0: alle 80 Ticks Hungerschaden (je nach Schwierigkeit bis 10 bzw. 1 Herz).

**Essen:**
- Komponenten `food` (Nährwert, Sättigung) und `consumable` (Standard 1,6 s = 32 Ticks).
- Ablauf:
  1. `use_item` startet die Benutzung;
  2. der Client hält die Taste;
  3. nach der Dauer beendet der Server das Essen: `entity_event` 9 an den Spieler, `container_set_slot` (Stapel − 1), `set_health`.
- Loslassen vorher → `player_action` RELEASE_USE_ITEM (6); der Server bricht ab.

## 6. Statuseffekte

Registry `mob_effect` (40 Einträge), die umgesetzten Effekte:

| ID | Effekt | ID | Effekt |
|---|---|---|---|
| 0 | speed | 9 | regeneration |
| 1 | slowness | 10 | resistance |
| 2 | haste | 17 | weakness |
| 3 | mining_fatigue | 18 | poison |
| 4 | strength | 20 | health_boost |
| 5 | instant_health | 21 | absorption |
| 6 | instant_damage | 22 | saturation |

- **Sofort-Effekte** (5, 6) wirken nur einmal und erzeugen kein dauerhaftes `update_mob_effect`.
- **Attribut-Effekte** (Geschwindigkeit, Stärke, Schwäche, Gesundheitsschub) wirken über Modifikatoren der Attribute. Der Server sendet sie mit `update_attributes` an den eigenen Spieler (M5 übernimmt `movement_speed` bereits für die Physik).

## 7. Tod und Wiederbelebung

1. Gesundheit ≤ 0 → der Server sendet `set_health` (0) und `player_combat_kill` (Spieler-ID, Nachricht).
2. Der Vanilla-Client zeigt den **Todesbildschirm**, falls `showDeathScreen` gilt (Gamerule `doImmediateRespawn` → `game_event` 11), sonst `respawn()` sofort.
3. Taste „Wiederbeleben“ → `client_command` PERFORM_RESPAWN.
4. Der Server antwortet mit `respawn` (gleiche oder andere Dimension), `player_position` (Spawnpunkt), `set_health` (20), Inventar (`container_set_content`) und Attributen.
5. Ohne Wiederbelebungsanfrage bleibt der Spieler tot, auch über eine Trennung hinweg: `LivingEntity` speichert `Health` 0 und lädt sie beim Login wieder (`readAdditionalSaveData`). Nach dem erneuten Login sendet der Server `set_health` 0, aber **kein** `player_combat_kill`. Der Client muss den Tod deshalb an der Gesundheit erkennen (Vanilla `isDeadOrDying`). Bestätigt im Test k4 mit `--no-auto-respawn` ([41](41-m10-testbericht.md) §6). Ein früherer Lauf mit „Gesundheit 20 nach Login“ war ein Testfehler: Der Terminalclient hatte sich beim Login selbst wiederbelebt.

Die automatische Wiederbelebung aus M5 (`ClientSession#onSetHealth`) wird in M10 nur noch ausgeführt, wenn sie konfiguriert ist (Terminal/Headless); der Grafikclient wartet auf den Spieler ([39](39-kampfsystem-architektur.md) §5).
