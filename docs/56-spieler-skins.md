# 56 – Spieler-Skins (M14, Stand 2026-11-17)

## 1. Modelle und Schichten

- **Classic** (`minecraft:player#main`, Arme 4 px breit) und **Slim** (`minecraft:player_slim#main`, Arme 3 px) aus der
  Modelltabelle; UV-Abwicklung identisch zur Vanilla-Kastenabwicklung (docs/55 §2).
- **Zweite Schicht** als Kinder der Grundteile: `hat` (Kopf, +0,5), `jacket` (Körper, +0,25), `left_sleeve`/`right_sleeve` (Arme),
  `left_pants`/`right_pants` (Beine). Sichtbarkeit aus Datenfeld 16 (`DATA_PLAYER_MODE_CUSTOMISATION`): 0x02 Jacke, 0x04 linker Ärmel,
  0x08 rechter Ärmel, 0x10 linkes Hosenbein, 0x20 rechtes Hosenbein, 0x40 Hut (0x01 Umhang: nicht gezeichnet).
- **Darstellung** wie `AvatarRenderer`: Skalierung 0,9375; Schleichen 2/16 tiefer und Humanoid-Schleichpose; Schwimmen: Neigung
  −90° − Blickneigung, Versatz (0, −1, 0,3); Laufen, Kopf/Körper-Drehung, Passagierhaltung, Schlag, Gegenstand in der Hand, Rüstung
  über `player(_slim)#helmet …`. Namensschild wie in M9 (`NameTags`).

## 2. SkinManager (`view/skin/SkinManager`, ohne LWJGL, ohne Netzwerk)

| Funktion | Umsetzung |
|---|---|
| Vanilla-Standardskins | 9 Namen × {schmal, breit} aus dem lokalen JAR (`textures/entity/player/{slim,wide}/<name>.png`); Auswahl `floorMod(uuid.hashCode(), 18)`: 0–8 schmal, 9–17 breit (`DefaultPlayerSkin#get`). M9 hatte die Reihenfolge vertauscht – behoben. |
| Lokale PNG-Skins | Grafikoption `--skins <ordner>`: `<uuid>.png` oder `<name>.png` (Name nur `[A-Za-z0-9_]{1,16}`, kein Pfadausbruch); Slim-Modell durch Markierungsdatei `<uuid|name>.slim`. |
| Caching | dekodierte Bilder je Schlüssel (`skin:default/…`, `skin:local/…`), LRU mit höchstens 128 Einträgen; GPU-Textur einmal je Schlüssel im Renderer. |
| Zuordnung per UUID | erst lokale Datei nach UUID, dann nach Name, sonst Standardskin der UUID. |
| Classic/Slim | Standardskin: aus dem Index; lokal: `.slim`-Markierung; Mannequin: Skin-Vorgabe; Profil-`textures`: Modell aus `metadata.model`. |
| Sichere Dekodierung | Dateigröße ≤ 64 KiB; PNG-Signatur; `IHDR` muss erster Block sein; Maße aus `IHDR` **vor** dem Dekodieren geprüft (nur 64×64 oder 64×32, keine HD-/Riesenbilder); Dekodierfehler → null; Pfade auf den Skin-Ordner begrenzt. |
| Altes Format 64×32 | wie `SkinTextureDownloader#processLegacySkin`: 64×64 anlegen, Arm-/Beinflächen gespiegelt kopieren, Alpha-Regeln (`setNoAlpha` für Grundflächen, „Notch-Regel“: ganz deckender Hutbereich wird durchsichtig). |
| Fallback | ungültige/fehlende Daten → Standardskin; ohne `--assets` kein Bild → Debug-Box (wie M9 bei fehlenden Texturen). |

## 3. Mannequin (26.3) und Profile

- Die Entity `minecraft:mannequin` (Avatar ohne Spieler) trägt ein `ResolvableProfile` (Datenfeld 17, Serialisierer 41). Der Client
  dekodiert es jetzt vollständig (vorher: Rest des Datenpakets verworfen): vollständiges oder Teilprofil, Eigenschaften, Skin-Vorgabe
  (`texture`, `cape`, `elytra` als Bezeichner, Modell schmal/breit).
- Skin = Standardskin des Profils (UUID; sonst Offline-UUID des Namens; leeres Profil → Null-UUID = schmal/Alex) überschrieben mit der
  Vorgabe (Textur des lokalen JARs, Modell). Damit lassen sich Classic/Slim und alle 18 Standardskins ohne Konto und ohne Netzwerk
  testen.

## 4. Vorbereitung für den letzten Authentifizierungsmeilenstein (nicht aktiv)

- `SkinManager#profileTextures(uuid, base64)` wertet die `textures`-Eigenschaft aus (`textures.SKIN.url`, `metadata.model`) und merkt
  sich das Modell. Es wird **nichts heruntergeladen**: ohne lokale Kopie bleibt der Standardskin (mit dem Profil-Modell).
- Später nötig (docs/53 §9): Download nur von freigegebenen Texturhosts über die M13-`EndpointPolicy`, Signaturprüfung der
  Eigenschaft (Yggdrasil-Schlüssel aus `authentication/getPublicKeys`), Größen-/Formatprüfung wie oben, Cache auf Platte.
- Offline-Server senden keine `textures`-Eigenschaft; der SkinManager funktioniert ohne Microsoft-Konto vollständig.

## 5. Testskin

`analysis/tools/m14/MakeTestSkin.java` erzeugt eine eigene 64×64-Grafik (einfarbige Körperteile, deutlich andere Farben der zweiten
Schicht), keine Mojang-Grafik. Der Grafiktest (docs/58 §3) setzt sie als lokalen Skin des Begleiters ein.
