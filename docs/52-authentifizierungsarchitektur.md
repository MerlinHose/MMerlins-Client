# 52 – Authentifizierungsarchitektur (M13 Phase B/C/D/I/J)

## 1. Grundsätze

- Das Netzwerkprotokoll (`session`, `net`, `protocol`) kennt **keine** Microsoft-, Xbox- oder HTTP-Klassen. Die einzige
  Verbindung zur Anmeldung ist die Schnittstelle `auth.LoginIdentity`.
- Offline- und Online-Mode nutzen dieselbe `ClientSession`/`Connection`. Offline bleibt der Standard, wenn kein
  `--auth` angegeben ist.
- Alles bleibt JDK-only: `java.net.http`, `com.sun.net.httpserver` (Loopback-Redirect), JCA. Es gibt keine neuen
  Bibliotheken, und der Terminalclient bleibt frei von LWJGL.
- Externe Verbindungen sind standardmäßig gesperrt (`EndpointPolicy.denyAll`).

## 2. Pakete und Verantwortlichkeiten

| Verantwortung | Klasse(n) | Paket |
|---|---|---|
| Identität für den Login (Schnittstelle) | `LoginIdentity`, `OfflineIdentity` | `auth` |
| Fehlerbehandlung | `AuthError` (Meldungstexte), `AuthException` (nur nicht-sensitive Details) | `auth` |
| Tokenverwaltung | `SecretToken` (redigiertes `toString`, Ablaufzeit, `discard`), `microsoft.MicrosoftTokens` | `auth` |
| Abbruch | `AuthCancellation` | `auth` |
| Zustand der grafischen Anmeldung | `LoginModel` (ohne Swing testbar) | `auth` |
| HTTP und Netzwerkregel | `http.HttpTransport`, `http.JdkHttpTransport`, `http.EndpointPolicy`, `http.Json` | `auth.http` |
| Benutzeranmeldung (Microsoft) | `MicrosoftOAuth` (PKCE, Device Code, Refresh), `Pkce`, `LoopbackReceiver` | `auth.microsoft` |
| Xbox Live / XSTS | `XboxLive` | `auth.xbox` |
| Minecraft-Konto und Profil | `MinecraftServices` (Token, Besitz, Profil, Privilegien), `ServiceDiscovery` | `auth.minecraft` |
| Sessionverwaltung | `OnlineIdentity` (join, Ablauf, Abmelden), `MicrosoftAccountLogin` (Kette, Refresh, Logout) | `auth.minecraft` |
| Optionen und Verdrahtung | `LoginFactory` (+ `Parser` für Terminal und Grafik) | `auth.minecraft` |
| Verschlüsselung | `LoginCrypto`, `CipherStreams`; `Connection#enableEncryption` | `net` |
| Serververbindung | `ClientSession#onEncryptionRequest`, `Clientbound.EncryptionRequest`, `Serverbound.Key` | `session`, `protocol` |
| Grafische Anmeldung | `gl.LoginWindow` (Swing, vor dem GL-Fenster) | render |

Abhängigkeitsrichtung: `session → auth (Schnittstelle)`, `auth.minecraft → auth.microsoft/xbox/http → auth`. Keine
Klasse in `auth` hängt von `session` oder `gl` ab.

## 3. Ablauf der Anmeldung (`MicrosoftAccountLogin#login`)

```
MicrosoftOAuth (Browser+PKCE | Device Code) → XboxLive.userToken → XboxLive.xstsToken → MinecraftServices.loginWithXbox
  → entitlements (Besitz) → profile (UUID, Name) → privileges (Mehrspieler) → OnlineIdentity
```

- Jeder Schritt meldet seinen Fortschritt (`Step`) und prüft vorher auf Abbruch.
- Zwischentokens (XBL, XSTS) werden nach Gebrauch verworfen.
- Behalten werden nur der Minecraft-Token in der `OnlineIdentity` und die Microsoft-Tokens für den Refresh.
- Es gibt keine automatische Wiederholung.

## 4. Microsoft-Anmeldung (Phase C)

- Der Systembrowser wird über `java.awt.Desktop#browse` geöffnet. Der Client hat kein Passwortfeld und keinen
  eingebetteten Browser.
- `LoopbackReceiver`:
  - bindet an 127.0.0.1 (zusätzlich [::1] auf demselben Port) mit einem Port, den das Betriebssystem wählt;
  - Redirect-URI ist `http://localhost:<port>`;
  - nimmt nur `GET /` mit passendem Host-Header an (Schutz vor DNS-Rebinding);
  - vergleicht `state` in konstanter Zeit und bricht bei falschem `state` ab;
  - wertet nur die erste Antwort aus; `error=access_denied` gilt als Abbruch durch den Benutzer;
  - liefert eine Browserseite ohne Codes oder Tokens.
- **PKCE:** Verifier = Base64url(32 Bytes `SecureRandom`), also 43 Zeichen; Challenge = Base64url(SHA-256), Methode
  S256. `state` wird ebenso aus 32 Zufallsbytes gebildet.
- **Zeitlimits:** 5 Minuten bis zur Weiterleitung, 15 Minuten für den Device Code. Das Abfrageintervall ist das vom
  Server vorgegebene, mindestens 5 s; `slow_down` verlängert es um 5 s.
- **Freigabe:** Vor dem Öffnen des Browsers prüft der Client die Anmeldeadresse gegen die Policy. Ohne
  `--allow-external-auth` öffnet sich kein Browser.

## 5. Verdrahtung und Optionen

Die Optionen gelten im Terminalclient (`Main`) und im Grafikclient (`GraphicalOptions`) gleich:

| Option | Bedeutung |
|---|---|
| `--auth offline` (Standard) | bisheriger Testmodus, kein HTTP |
| `--auth simulation --sim-discovery http://127.0.0.1:<p>/minecraft/client [--sim-uuid …]` | lokale Testdienste mit Testdaten |
| `--auth microsoft --client-id <GUID> --allow-external-auth [--login-method browser\|device-code]` | offizielle Anmeldung |
| `--sessions <n>` (Terminal) | n Verbindungen nacheinander mit derselben Identität (Neuverbindung, je ein neuer `join`) |
| `--login-ui` (Grafik) | Anmeldefenster vor dem Spielfenster |

## 6. Sicherheit und Tokenverwaltung

- **Keine Passwörter:** Die Eingabe erfolgt ausschließlich auf der Microsoft-Seite.
- **Kein Client-Secret:** Der Client ist öffentlich. Die Client-ID stammt aus der eigenen Registrierung und ist nicht
  eingebaut.
- **HTTPS:**
  - Es gilt eine feste Host-Allowlist, und Port 443 ist Pflicht.
  - Weiterleitungen werden nicht verfolgt (`Redirect.NEVER`).
  - Der Client nutzt den Standard-`SSLContext` des JDK; TrustManager und Hostname-Prüfung bleiben unverändert.
  - Discovery-Adressen werden ebenfalls gegen die Allowlist geprüft.
- **Logs:**
  - `HttpTransport.Request#toString` zeigt nur Methode, Host und Pfad; `Response#toString` nur Status und Länge.
  - `AuthException` enthält Fehlerart, Status und standardisierte Fehlercodes. Antwortkörper von OAuth-Fehlern gelangen
    nicht in Meldungen, weil `error_description` Codes enthalten kann.
  - `SecretToken`, `Pkce`, `Callback` und `XboxToken` geben ihre Werte in `toString` nicht aus.
  - `AuthChainTest#assertNoSecretsLeaked` prüft alle Fehlermeldungen auf Testgeheimnisse.
- **Speicherung:** Tokens liegen nur im Arbeitsspeicher; das ist die erste Ausbaustufe. Es gibt keine Datei, keine
  Umgebungsvariable und keine Ausgabe.
  - Abmelden verwirft die Referenzen. Java-Strings lassen sich nicht sicher überschreiben; das ist dokumentiert und
    akzeptiert.
  - Eine dauerhafte Anmeldung (Refresh-Token, z. B. per Windows-DPAPI) ist **nicht** implementiert.
- **Oberfläche:** `LoginModel.Snapshot` enthält nur Zustand, Status, Spielername und Fehlertext. Tokens erreichen die
  Oberfläche nicht.
- **Screenshots und Berichte:** Die Simulationsläufe verwenden ausschließlich gekennzeichnete Testdaten
  (`TEST-ONLY-NOT-A-REAL-TOKEN-…`). Echte Tokens kommen in keinem Bericht vor.

## 7. Fehlerbehandlung

`AuthError` enthält für jeden bekannten Fall eine deutsche Meldung, die in Terminal und Oberfläche erscheint:

| Bereich | Fälle |
|---|---|
| Freigabe und Konfiguration | keine Freigabe, Endpunkt nicht erlaubt, keine Client-ID |
| OAuth | Abbruch, Zeitüberschreitung, `state`-Fehler, OAuth-Fehler, `invalid_grant` |
| Xbox | die fünf XErr-Fälle aus docs/50 §3 und allgemeiner Xbox-Fehler |
| Minecraft-Konto | App nicht freigegeben, kein Profil, kein Besitz, Mehrspieler deaktiviert, Token abgelaufen |
| Session | ungültige Sitzung, fehlende Berechtigung, Sperre |
| Allgemein | Rate-Limit, Dienst nicht erreichbar, unerwartete Antwort, Offline-Identität auf einem Online-Server |

Netzwerk- und Protokollfehler der Spielverbindung bleiben wie bisher `ProtocolException`/`IOException`, zum Beispiel
ungültige Länge, abgebrochener Handshake oder Timeout.

## 8. Grafische Anmeldung (Phase I)

`gl.LoginWindow` ist ein Swing-Fenster, das nur mit `--login-ui` vor dem GL-Fenster erscheint. Es wird geschlossen,
bevor GLFW startet.

- **Auswahl:** Offline-Testmodus mit Namensfeld, „Mit Microsoft anmelden“ und, wenn `--sim-discovery` gesetzt ist,
  die lokale Simulation.
- **Eingaben:**
  - ein Feld für die Client-ID;
  - eine Checkbox „Offizielle Anmeldeendpunkte freigeben“ (Standard aus; nur an, wenn `--allow-external-auth`
    gesetzt ist);
  - eine Checkbox „Device-Code statt Browser-Weiterleitung“.
- **Anzeige:** Status mit dem aktuellen Schritt, Spielername und Fehlertext.
- **Schaltflächen:**
  - Anmelden.
  - Abbrechen (laufende Anmeldung).
  - Abmelden (verwirft Tokens und Refresh-Token).
  - Verbinden (aktiv, sobald eine Identität vorliegt).
  - Beenden.
- **Mehrfach-Login:** Eine zweite Anmeldung während einer laufenden ist gesperrt. Eine neue Anmeldung meldet die alte
  vorher ab.
- **Ohne `--login-ui`:** Das Verhalten ist unverändert. Alle Regressionsskripte starten ohne Microsoft-Konto.
