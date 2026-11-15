# 53 – Testbericht M13 (Stand 2026-11-15): teilweise abgeschlossen

**Status: M13 ist TEILWEISE abgeschlossen.** Alle lokal möglichen Teile sind implementiert und lokal getestet. Es
fehlen:

- **E1:** die Mojang-Freigabe einer eigenen Client-ID und die ausdrückliche Freigabe der externen
  Authentifizierungsendpunkte (docs/50 §6, §8);
- **E2:** die vollständige Regression M1–M12. Sie wurde wegen Speichermangels kontrolliert abgebrochen (§6).

## 1. Übersicht

| Bereich | Implementiert | Getestet | Wie getestet |
|---|---|---|---|
| Machbarkeit, Endpunkte, App-Registrierung | ja (docs/50) | – | Dokumentation, Vanilla-/authlib-Quellen |
| Microsoft-Anmeldung (PKCE, Loopback, Device Code, Refresh) | ja | lokal | HTTP-Attrappe, echter Loopback-Empfänger |
| Xbox Live, XSTS, Minecraft-Token, Besitz, Profil, Privilegien | ja | lokal | HTTP-Attrappe (Anfrageformate, Fehlerzuordnung) |
| Verschlüsselung (RSA, AES/CFB8, Session-Hash) | ja | **gegen unveränderten Vanilla-26.3-Server** | lokale Dienstsimulation (§4) |
| Session-Nachweis (join) | ja | gegen Simulation | nicht gegen den offiziellen Sessionserver |
| Grafische Anmeldung | ja | Modell per Unit-Test; Fenster kompiliert | kein Klicktest des Swing-Fensters |
| Echter Online-Mode-Login | ja, vorbereitet | **nein** | externe Voraussetzung E1 |
| Regression M1–M12 | – | **nein** (abgebrochen) | externe Voraussetzung E2 |

## 2. Unit-Tests (alle grün)

| Testklasse | Tests | Inhalt |
|---|---|---|
| `net.LoginCryptoTest` | 7 | Hash-Testvektoren (Notch, jeb_, simon); Vergleich mit unabhängiger SHA-1-Rechnung; 128-Bit-AES; RSA-Rundlauf; ungültige Schlüssel (Müll, EC); CFB8-Strom über zufällige Stückelung (Einzelbytes und Blöcke) = Referenz; nur einmal aktivierbar |
| `auth.EncryptedLoginTest` | 10 | verschlüsselter Login gegen `SimulatedLoginServer` (§3) |
| `auth.AuthChainTest` | 13 | Netzwerkregel, PKCE, Autorisierungs-URL, Loopback-Empfänger, interaktiver Login, Device Code, komplette Kette, Konto- und Sessionfehler, Ablauf, Abmelden und erneute Anmeldung, Token-Leaks |
| `auth.LoginModelTest` | 4 | Offline-Modus, Name, Abmelden verwirft Token, Abbruch, kein Mehrfach-Login, Fehlermeldungen, Fehler ohne Freigabe oder Client-ID |
| `session.ClientSessionTest` (angepasst) | 21 | unverändert grün, darunter die Ablehnung des Online-Modes bei Offline-Identität |

Die Gesamtsuite (`gradlew test`) lief in M13 nicht vollständig; sie ist Teil von Regressionsschritt 1 (§6).

## 3. Lokale Protokoll-Testkomponente (Phase G)

`SimulatedLoginServer` (Testquellen) bildet den Online-Mode-Login **unabhängig** vom Client nach: eigene
VarInt-/Framing-Routinen, JCA-`CipherInputStream`/`CipherOutputStream`, eigene zlib-Implementierung, RSA-1024 wie
Vanilla. Alle Identitäten und Tokens sind Testdaten.

| # | Prüfung (Vorgabe Phase G) | Ergebnis |
|---|---|---|
| 1 | RSA-Schlüsselaustausch | Server entschlüsselt 16-Byte-Schlüssel; Challenge stimmt |
| 2 | AES/CFB8-Datenstrom | `login_acknowledged`, `brand`, `client_information` korrekt entschlüsselt |
| 3 | Session-Hash | Client-Hash = Server-Hash; `hasJoined` akzeptiert; Testvektoren in `LoginCryptoTest` |
| 4 | Pakete über mehrere Grenzen | 202 Pakete hintereinander, bytegenau verglichen |
| 5 | fragmentiertes TCP | Fragmente zu 1, 7 und 1500 Bytes mit `flush` |
| 6 | große Pakete | 1 000 000 Byte zufällig, 900 000 Byte komprimierbar |
| 7 | Kompression in der richtigen Reihenfolge | Schwellen −1, 0 und 256; `login_compression` verschlüsselt; Trace `hello(auth) join key encrypted compression(n) finished` |
| 8 | abgebrochene Handshakes | Server schließt nach `key` → `EOFException`; Offline-Identität → `ProtocolException`; abgelehnter join → `AuthException`, kein `key`, keine Verschlüsselung |
| 9 | ungültige Schlüssel und Paketlängen | Müllschlüssel → „Invalid server key“ ohne join; verschlüsselte Länge > 21 Bit → `ProtocolException` |
| 10 | Timeouts und Disconnects | stummer Server → `SocketTimeoutException`; nie gemeldeter Hash → `unverified_username` |

## 4. Verschlüsselter Login gegen den unveränderten Vanilla-26.3-Server (lokale Simulation)

> **Lokale Simulation, kein Nachweis einer offiziellen Anmeldung.** Name `M13Sim`, UUID
> `00000000-0000-4000-8000-00000000d013` und das Token `TEST-ONLY-NOT-A-REAL-TOKEN-…` sind Testdaten.

**Aufbau** (`analysis/tools/m13-sim-vanilla.sh`, Protokolle in `analysis/runtime-logs/m13-sim/`):

- Vanilla-Server mit `online-mode=true`, Authentifizierung **eingeschaltet**.
- Die Systemeigenschaft `minecraft.api.discovery.host` von authlib zeigt auf `MockSessionServices` unter
  127.0.0.1:25599. Der Server ruft dort `hasJoined` auf und prüft damit mit seinem eigenen Code (`handleKey`, `Crypt`,
  authlib), ob unser Client den richtigen Hash gemeldet hat.
- Vor dem Lauf: 3014 MB frei, 69 % Commit.

| Fall | Kompression | Ergebnis |
|---|---|---|
| Login + Neuverbindung (2 Sitzungen) | −1, 0, 256 | alle 6 Sitzungen `SUCCESS`, verschlüsselt, Spielwelt erreicht, kontrolliert getrennt |
| Identität laut Serverzustand | −1, 0, 256 | Server-Log „UUID of player M13Sim is 00000000-0000-4000-8000-00000000d013“; `data get entity M13Sim UUID` → `[I; 0, 16384, -2147483648, 53267]` (= …d013); `login_finished` enthält dieselbe UUID |
| Session-Hash, positiv und negativ | alle | Server hat 8 verschiedene Hashes akzeptiert, darunter negative (`-1615002c…`) und solche mit weggelassener führender Null |
| Offline-Identität am Online-Server | 256 | Client bricht ab: „offline identity cannot join“ |
| join abgelehnt (403 ForbiddenOperationException) | 256 | `INVALID_SESSION`, kein `key` gesendet |
| Sessiondienst nicht erreichbar (503) | 256 | `SERVICE_UNAVAILABLE` |
| Server-Prüfung schlägt fehl | 256 | Vanilla trennt: `multiplayer.disconnect.unverified_username` |
| Server-Prüfung nicht erreichbar | 256 | Vanilla trennt: `multiplayer.disconnect.authservers_down` |
| erneuter Login danach | 256 | `SUCCESS` |
| externe Verbindungen (netmon, alle Projektprozesse und Mock) | alle | **0** |

**Nach dem Lauf:**

- `server.properties` und `usercache.json` sind wiederhergestellt: `online-mode=false`, `difficulty=easy`,
  Kompression −1.
- Die Spielerdateien der Simulations-UUID sind gelöscht.
- Es laufen 0 Projektprozesse.

## 5. Sicherheit (Phase J)

| Prüfung | Ergebnis |
|---|---|
| Ohne `--allow-external-auth` | jede Anfrage abgewiesen, Browser öffnet sich nicht (`EXTERNAL_NOT_ALLOWED`) |
| Allowlist | http, fremder Host, Suffix-Trick, Port 8443, userinfo, IP-Literal → `ENDPOINT_NOT_ALLOWED`; Discovery mit fremdem Host → Abbruch |
| state | gefälschter state → `STATE_MISMATCH`; fremder Host-Header → 400; zweite Antwort → 409 |
| OAuth-Abbruch, Timeout, Abbrechen-Schaltfläche | `USER_CANCELLED` bzw. `TIMEOUT` |
| ungültige oder abgelaufene Tokens | `invalid_grant` → `INVALID_GRANT`; abgelaufener Minecraft-Token → `MINECRAFT_TOKEN_EXPIRED` ohne Anfrage |
| Konto ohne Xbox, Kinderkonto, Land, Sperre | XErr-Zuordnung geprüft |
| kein Profil, kein Besitz, Mehrspieler aus, gesperrt, App nicht freigegeben | geprüft |
| Sessionfehler, Rate-Limit, 5xx | geprüft (Attrappe und Vanilla-Simulation) |
| Mehrfach-Login; Logout und erneuter Login | geprüft (`LoginModelTest`, `AuthChainTest`) |
| Token-Leaks | keine Testgeheimnisse in Meldungen, `toString`, Oberfläche; Logs enthalten nur Methode, Host, Pfad, Status |
| falsche Protokollversion, fehlerhafte Pakete | unveränderte Prüfungen aus M2/M3 (Statusabfrage, `ProtocolException`) |

## 6. Regression M1–M12 (Phase K): nicht abgeschlossen

- **Werkzeug:** `analysis/tools/m13-regression.sh` mit den 31 Schritten aus M12 und zusätzlich:
  - Kompression −1 und 0;
  - M12-Funktionstest, HUD-Test bei 1280 px und visuelle Suite;
  - M13-Simulation.
- **Lauf 1:**
  - Schritt 1 lief im Vordergrund mit einer 590-s-Grenze an und überschritt sie (Dauer über 10 min).
  - Bis dahin waren laut Ausgabe die **896 Physikticks bitgleich** (20/20 Szenarien, Δpos 0, 0 Korrekturen) und der
    Terminaltest `m5-terminal` lief mit exit 0.
  - Der Lauf wurde beendet; die Protokolle hat Lauf 2 überschrieben. Dieses Ergebnis gilt daher nur als Beobachtung,
    nicht als Nachweis.
- **Lauf 2:**
  - Schritt 1 im Hintergrund wurde von Claude Code wegen kritischer Speicherknappheit des Systems beendet.
  - Speicherbedarf: Fremdprozesse, vor allem TFTClient mit etwa 3,4 GB, die ich nicht beendet habe. Davor waren 3081 MB
    frei.
  - `m12-cleanup.ps1` hat danach alle verwaisten Projektprozesse beendet (10 Skripte, 2 Java-Prozesse, den Server über
    die Konsole, tail). Die Konfiguration ist wiederhergestellt, 0 Projektprozesse.
- **Folge:** Gemäß Vorgabe habe ich kontrolliert gestoppt und **nicht** automatisch neu gestartet.

**Auswirkung auf den Offline-Mode, die zu prüfen bleibt:**

- `Connection` liest und schreibt jetzt durch die (vor `enable` durchlässigen) `CipherStreams`.
- `ClientSession` sendet `hello` mit der Identität; für Offline gilt derselbe Name und dieselbe Offline-UUID wie
  bisher.
- Alle `ClientSessionTest`-Fälle und die Netzwerktests sind grün. Die Integrations- und Grafikläufe stehen aus.

## 7. Offene Einschränkungen

- **E1, Microsoft/Mojang:**
  - Es gibt keinen echten Microsoft-Login und keinen Abruf eines echten Profils.
  - `join` gegen den offiziellen Sessionserver ist nicht getestet.
  - Ein Online-Mode-Server mit offizieller Prüfung ist nicht getestet.
  - Voraussetzungen: eigene App-Registrierung, Mojang-Freigabe der Client-ID (aka.ms/AppRegInfo,
    aka.ms/mce-reviewappid) und deine Freigabe der Endpunkte aus docs/50 §6.
- **E2:** Die Regression M1–M12 braucht mindestens 3000 MB freien Speicher über mehrere Stunden.
- Den Inhalt des Mojang-Hilfeartikels konnte ich nicht abrufen (Zeitüberschreitung).
- Keine dauerhafte Tokenspeicherung (DPAPI). Profilschlüssel und signierter Chat sind nicht implementiert (docs/51 §7).
- Das Swing-Anmeldefenster ist nur über das Modell getestet; ein Klicktest steht aus.
