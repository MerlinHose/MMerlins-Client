# 50 – Authentifizierung und Machbarkeit (M13 Phase A)

Stand 2026-11-15. Ziel: Microsoft-Anmeldung und verschlüsselter Login auf einem Vanilla-26.3-Server mit
`online-mode=true`, ohne fremde Zugangsdaten und ohne Umgehung von Prüfungen.

**Ergebnis in einem Satz:** Technisch ist alles mit JDK-Mitteln umsetzbar und lokal getestet. Ob eine **eigene
Microsoft-App-Registrierung Minecraft Services aufrufen darf, entscheidet Mojang per Freigabe.** Diese Freigabe und
die Erlaubnis für externe Verbindungen fehlen noch (§6, §8).

## 1. Quellen und Vorgehen

| Thema | Quelle | Art |
|---|---|---|
| OAuth 2.0 Authorization Code + PKCE | learn.microsoft.com/entra/identity-platform/v2-oauth2-auth-code-flow | offiziell (Microsoft) |
| Redirect-URI, Loopback | learn.microsoft.com/entra/identity-platform/reply-url | offiziell (Microsoft) |
| Device Code Flow | learn.microsoft.com/entra/identity-platform/v2-oauth2-device-code | offiziell (Microsoft) |
| App-Registrierung für Minecraft | aka.ms/AppRegInfo → help.minecraft.net/hc/en-us/articles/16254801392141 | offiziell (Mojang), **Inhalt nicht abrufbar** (2× Zeitüberschreitung) |
| Prüfformular für App-IDs | aka.ms/mce-reviewappid → forms.office.com → forms.cloud.microsoft (Microsoft Forms) | offiziell (Weiterleitung geprüft, Formularinhalt lädt nur per JavaScript) |
| Login-Zustandsautomat, Krypto | Vanilla 26.3 Quellen (Loom): `ClientHandshakePacketListenerImpl`, `ServerLoginPacketListenerImpl`, `Crypt`, `Connection`, `CipherBase` | tatsächlich verwendeter Code |
| Session-Dienst, Discovery, Fehlerzuordnung | authlib 10.0.77 (Abhängigkeit von 26.3): `MinecraftServicesSessionService`, `MinecraftServicesDiscoveryService`, `MinecraftClient`, `MinecraftClientHttpException`, `MinecraftServicesUserApiService` | tatsächlich verwendeter Code |
| Xbox-Live-/XSTS-/Minecraft-Services-Aufrufe der Launcher | minecraft.wiki „Microsoft Authentication Scheme“ (frühere wiki.vg-Seite) | Community-Dokumentation; Microsoft und Mojang veröffentlichen dazu keine Spezifikation |

Bei der Recherche habe ich nur Dokumentationsseiten gelesen. Ich habe keinen Anmeldeversuch unternommen und keinen
Authentifizierungsendpunkt aufgerufen. Der Projektcode hat keine externe Verbindung aufgebaut.

Wichtig: **Der Vanilla-Client meldet sich selbst nicht bei Microsoft an.** Diese Anmeldung übernimmt der offizielle
Launcher; er übergibt dem Spiel `--accessToken`, `--uuid`, `--username`, `--xuid` und `--clientId` (`net.minecraft.client.main.Main`).
Im Spiel bleiben daher nur zwei Schritte: der Session-Nachweis (`joinServer`) und optionale Dienste (Privilegien,
Profilschlüssel, Telemetrie). Unser Client übernimmt zusätzlich die Aufgabe des Launchers (§2–§5).

## 2. Microsoft OAuth 2.0 für Desktopanwendungen (Punkte 1–3)

- **Plattform:** Microsoft Identity Platform v2.0, Authority `https://login.microsoftonline.com/consumers/oauth2/v2.0`.
  Der Tenant `consumers` steht für persönliche Microsoft-Konten (Privatkunden); `common` würde zusätzlich
  Arbeits- und Schulkonten zulassen, die für Minecraft nicht vorgesehen sind.
- **Verfahren:** Authorization Code Flow mit PKCE.
  - `/authorize` benötigt `client_id`, `response_type=code`, `redirect_uri`, `scope` sowie `state` (gegen CSRF) und
    `code_challenge` mit `code_challenge_method=S256`.
  - `/token` benötigt `grant_type=authorization_code`, `code`, dieselbe `redirect_uri` und `code_verifier`.
  - Öffentliche Clients verwenden laut Microsoft kein Client-Secret: „Public clients … must not use secrets or
    certificates when redeeming an authorization code.“
- **Autorisierungscode:** Er ist etwa 1 Minute gültig.
- **Refresh-Token:** Microsoft stellt ihn nur mit Scope `offline_access` aus. Er hat keine feste Laufzeit; nach jeder
  Erneuerung ist der alte Token zu verwerfen.
- **Scope:** `XboxLive.signin offline_access`.
- **Redirect über den Systembrowser:** Microsoft empfiehlt `http://localhost` für Apps, die den Systembrowser nutzen.
  - „the port component … is ignored for the purposes of matching a localhost redirect URI“; der Client darf also einen
    freien Port wählen.
  - `http` ist nur für localhost erlaubt; `[::1]` wird nicht unterstützt.
  - Microsoft empfiehlt `127.0.0.1` statt `localhost`. Die http-Variante mit `127.0.0.1` lässt sich aber nur über das
    App-Manifest eintragen. Unser Client registriert deshalb `http://localhost` und bindet den Empfänger nur an
    127.0.0.1 (und [::1], falls verfügbar).
- **Device Code Flow:** Er funktioniert mit `/consumers` und braucht keine Redirect-URI.
  - Der Benutzer hat 15 Minuten Zeit; Fehlercodes: `authorization_pending`, `authorization_declined`, `expired_token`.
  - Er ist als Alternative implementiert (Terminal, kein lokaler Port).
  - In der App-Registrierung muss dafür „Allow public client flows“ aktiv sein. Das stammt aus Community-Quellen, nicht
    von der Device-Code-Seite selbst.
- **MSAL4J:** Ich habe es geprüft und nicht verwendet. Der Flow ist mit `java.net.http`, `com.sun.net.httpserver`,
  `SecureRandom` und `MessageDigest` vollständig abbildbar. MSAL4J brächte Nimbus/Jackson/SLF4J als Abhängigkeiten,
  und schon das Herunterladen aus Maven Central wäre eine nicht freigegebene externe Verbindung. Der Terminalkern
  bleibt damit JDK-only.

## 3. Xbox Live und XSTS (Punkte 4–5)

1. `POST https://user.auth.xboxlive.com/user/authenticate`
   - Body: `{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=<MS-Access-Token>"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}`.
   - Das Präfix `d=` ist für Tokens eigener Azure-Apps nötig.
   - Antwort: `Token`, `NotAfter`, `DisplayClaims.xui[0].uhs` (User-Hash).
2. `POST https://xsts.auth.xboxlive.com/xsts/authorize`
   - Body: `{"Properties":{"SandboxId":"RETAIL","UserTokens":["<XBL-Token>"]},"RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}`.
   - Bei Kontoproblemen kommt HTTP 401 mit dem Fehlercode `XErr`:

| XErr | Bedeutung | Meldung im Client |
|---|---|---|
| 2148916227 | Xbox-Konto gesperrt | `XBOX_BANNED` |
| 2148916233 | kein Xbox-Profil | `NO_XBOX_ACCOUNT` |
| 2148916235 | Xbox Live im Land nicht verfügbar | `XBOX_COUNTRY` |
| 2148916236/7 | Altersverifikation erforderlich | `XBOX_ADULT_VERIFICATION` |
| 2148916238 | Kinderkonto ohne Familie | `XBOX_CHILD` |

## 4. Minecraft Services, Profil, UUID und Besitz (Punkte 6–7)

- `POST https://api.minecraftservices.com/authentication/login_with_xbox`
  - Body: `{"identityToken":"XBL3.0 x=<uhs>;<XSTS-Token>"}`.
  - Antwort: `access_token` mit `expires_in`, typisch 24 h.
  - **Bei nicht freigegebener App-ID**: HTTP 403 „Invalid app registration, see https://aka.ms/AppRegInfo“. Mehrere
    unabhängige Projekte berichten das übereinstimmend; der Client meldet dann `APP_NOT_APPROVED`.
- `GET https://api.minecraftservices.com/entitlements/mcstore` (Bearer): Berechtigungen, darunter
  `product_minecraft` und `game_minecraft`. Ein leeres Ergebnis führt zu `NO_OWNERSHIP`.
- `GET https://api.minecraftservices.com/minecraft/profile` (Bearer): offizielle UUID (ohne Bindestriche) und
  Spielername. HTTP 404 bedeutet: kein Java-Profil, also das Spiel nicht erworben oder noch kein Name gewählt
  (`NO_MINECRAFT_PROFILE`).
- **Privilegien:** Vanilla prüft sie wie in `Minecraft#allowsMultiplayer`. Der Endpunkt kommt aus dem
  Discovery-Dokument (`player/getAttributes`). Ausgewertet werden `privileges.multiplayerServer.enabled` und
  `banStatus.bannedScopes.MULTIPLAYER`.
- Ein Microsoft-Konto bedeutet noch keine Spielberechtigung. Die Kette bricht deshalb bei fehlendem Besitz, fehlendem
  Profil oder deaktiviertem Mehrspieler kontrolliert ab. Endgültig prüft der Server die Identität über `hasJoined` (§5).

## 5. Sessionserver-Verfahren (Punkt 8)

Seit authlib 10 liest 26.3 die Dienstadressen aus einem Discovery-Dokument:
`GET https://discovery.minecraftservices.com/minecraft/client` (`MinecraftServicesEnvironment.PROD`, abschaltbar per
Systemeigenschaft `minecraft.api.discovery.host`). Darin stehen unter anderem:

- `session/join` für den **Client**: `POST {"accessToken","selectedProfile":<UUID ohne Bindestriche>,"serverId":<Session-Hash>}`;
  Erfolg ist HTTP 204 ohne Inhalt.
- `session/verify` für den **Server**: `GET …?username=…&serverId=…[&ip=…]` liefert `{"id","properties",…}`, sonst
  „unverified_username“.
- Fehlerzuordnung (authlib `MinecraftClientHttpException#toAuthenticationException`):
  - `ForbiddenOperationException` oder HTTP 401 → ungültige Sitzung;
  - `InsufficientPrivilegesException` → fehlende Berechtigung;
  - `multiplayer.access.banned` → Konto gesperrt;
  - HTTP ≥ 500 oder E/A-Fehler → Dienst nicht erreichbar.
- Zeitlimits: authlib verwendet je 5 s für Verbindungsaufbau und Lesen.

Die konkreten Produktionsadressen von `join` und `getAttributes` liefert erst das Discovery-Dokument. Erwartet werden
`sessionserver.mojang.com` und `api.minecraftservices.com`. Liefert Discovery einen anderen Host, bricht der Client mit
`ENDPOINT_NOT_ALLOWED` ab und nennt den Host; er kontaktiert ihn nicht (§6).

## 6. Benötigte externe HTTPS-Endpunkte (Freigabeliste)

Alle Verbindungen laufen über HTTPS auf Port 443 mit der Standard-TLS-Prüfung des JDK und folgen keinen
Weiterleitungen. Die Liste ist in `auth.http.EndpointPolicy.OFFICIAL_HOSTS` festgeschrieben; ohne den Schalter
`--allow-external-auth` sind alle Verbindungen gesperrt.

| Host | Pfad | Von | Zweck |
|---|---|---|---|
| login.microsoftonline.com | `/consumers/oauth2/v2.0/authorize` | Systembrowser | Microsoft-Anmeldeseite (Passworteingabe nur dort) |
| login.microsoftonline.com | `/consumers/oauth2/v2.0/token`, `/devicecode` | Client | Code einlösen, Token erneuern, Device-Code |
| user.auth.xboxlive.com | `/user/authenticate` | Client | Xbox-Live-Benutzertoken |
| xsts.auth.xboxlive.com | `/xsts/authorize` | Client | XSTS-Token für `rp://api.minecraftservices.com/` |
| api.minecraftservices.com | `/authentication/login_with_xbox`, `/entitlements/mcstore`, `/minecraft/profile` | Client | Minecraft-Token, Besitz, Profil |
| discovery.minecraftservices.com | `/minecraft/client` | Client **und** Server | Dienstverzeichnis (wie Vanilla 26.3) |
| api.minecraftservices.com | laut Discovery `player/getAttributes` | Client | Mehrspieler-Privileg |
| sessionserver.mojang.com (erwartet) | laut Discovery `session/join` | Client | Session-Nachweis für die konkrete Verbindung |
| sessionserver.mojang.com (erwartet) | laut Discovery `session/verify` | **lokaler Vanilla-Server** | `hasJoined`-Prüfung |
| api.minecraftservices.com (erwartet) | laut Discovery `authentication/getPublicKeys` | lokaler Vanilla-Server | Schlüssel für Profilsignaturen (Server-Start) |

Hinweise:

- Der Browser lädt beim Anmelden weitere Microsoft-Ressourcen, etwa `login.live.com` und CDN-Adressen. Diese
  Verbindungen baut der Browser auf, nicht unser Client.
- Der Spielserver bleibt Loopback. `LoopbackGuard` und `Connection.open` erlauben weiterhin nur 127.0.0.1/localhost.

## 7. Anforderungen an die eigene App-Registrierung (Punkt 9)

Im Microsoft-Entra-Admin-Center beziehungsweise im Azure-Portal unter „App-Registrierungen“:

1. Neue Registrierung anlegen; „Unterstützte Kontotypen“: **nur persönliche Microsoft-Konten**.
2. Plattform „Mobile und Desktopanwendungen“ mit Redirect-URI **`http://localhost`**.
3. „Öffentliche Clientflows zulassen“ auf **Ja** stellen. Das wird für Device Code gebraucht und ist für öffentliche
   Clients mit Redirect üblich.
4. **Kein Client-Secret, kein Zertifikat** anlegen: Der Client ist öffentlich.
5. Die „Anwendungs-ID (Client-ID)“ notieren; sie wird mit `--client-id <GUID>` übergeben und ist nicht geheim.
6. API-Berechtigungen: `XboxLive.signin` und `offline_access` sind delegierte Scopes. Sie werden erst bei der Anmeldung
   abgefragt; eine Administratorzustimmung ist bei persönlichen Konten nicht vorgesehen.

## 8. Kritischer Punkt: Freigabe der Client-ID durch Mojang (Punkt 10)

- **Befund:** Neue App-Registrierungen dürfen Minecraft Services nicht ohne Weiteres nutzen.
  - Microsoft- und Xbox-Anmeldung funktionieren mit jeder eigenen App-ID.
  - Erst `login_with_xbox` lehnt nicht freigegebene IDs mit HTTP 403 „Invalid app registration, see
    https://aka.ms/AppRegInfo“ ab.
  - Eine erfolgreiche Microsoft- oder Xbox-Anmeldung ist also **kein** Nachweis für den Zugang zu Minecraft Services.
    Der Client wertet erst den Minecraft-Token als Erfolg.
- **Offizieller Weg:**
  - `https://aka.ms/AppRegInfo` leitet auf den Minecraft-Hilfeartikel
    `https://help.minecraft.net/hc/en-us/articles/16254801392141` weiter.
  - Das Prüfformular `https://aka.ms/mce-reviewappid` ist ein Microsoft-Forms-Formular auf forms.office.com bzw.
    forms.cloud.microsoft.
  - Den Artikeltext konnte ich nicht abrufen (zweimal Zeitüberschreitung). Das Formular lädt seine Fragen erst per
    JavaScript. Die genauen Bedingungen bitte direkt dort lesen.
  - Projekte berichten von Bearbeitungszeiten zwischen einigen Tagen und mehreren Wochen. Das ist nicht offiziell.
- **Notwendige Schritte für dich:**
  1. App-Registrierung nach §7 anlegen.
  2. Artikel unter aka.ms/AppRegInfo lesen und die Bedingungen prüfen, insbesondere Minecraft-EULA und
     Nutzungsrichtlinien.
  3. Formular unter aka.ms/mce-reviewappid mit der Client-ID und der Projektbeschreibung einreichen. Beschreibung:
     privater Forschungsclient, nur eigener lokaler Server, keine Verbreitung.
  4. Auf die Freigabe warten.
  5. Mir die Client-ID nennen und die Endpunkte aus §6 ausdrücklich freigeben.
- **Ausgeschlossen:** fremde oder aus dem offiziellen Launcher bzw. anderen Launchern extrahierte Client-IDs. Der
  Client prüft nur das GUID-Format und kennt keine eingebaute ID.
- **Zugangsbeschränkungen für Drittclients:**
  - Mojang erlaubt Drittlaunchern den Zugriff nur nach dieser Prüfung.
  - Rate-Limits von Minecraft Services und Xbox Live sind zu beachten: HTTP 429 mit `Retry-After`. Der Client
    wiederholt nichts automatisch und fragt den Device-Code mindestens im vom Server vorgegebenen Intervall (≥ 5 s) ab.
  - Mehrspieler kann kontoseitig deaktiviert sein (Jugendschutz); der Client prüft das wie Vanilla.

## 9. Machbarkeit

| Teil | Machbar | Status |
|---|---|---|
| PKCE, Loopback-Redirect, Device Code | ja, JDK-only | implementiert; mit Attrappen getestet |
| Xbox/XSTS/Minecraft-Kette mit Fehlerzuordnung | ja | implementiert; mit Attrappen getestet |
| Verschlüsselter Login (RSA, AES/CFB8, Hash) | ja | implementiert; gegen lokale Testkomponente getestet, gegen den unveränderten Vanilla-Server mit lokaler Dienstsimulation in Arbeit (docs/53) |
| Session-Nachweis beim offiziellen Sessionserver | ja | implementiert, **nicht extern getestet** (keine Freigabe) |
| Echter Online-Mode-Login | abhängig von Mojang-Freigabe und deiner Endpunktfreigabe | offen |
