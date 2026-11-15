# 51 – Online-Mode-Protokoll (M13 Phase E/F)

Grundlage sind die Vanilla-26.3-Quellen (Loom) und authlib 10.0.77. Implementiert habe ich unabhängig davon, ohne
Mojang-Klassen zu kopieren und ausschließlich mit JCA-Bausteinen des JDK.

## 1. Login-Zustandsautomat (Vanilla-Client)

`ClientHandshakePacketListenerImpl` durchläuft die Zustände
`CONNECTING → AUTHORIZING → ENCRYPTING → JOINING → (Configuration)`.

| Schritt | Richtung | Paket (login) | Inhalt |
|---|---|---|---|
| 1 | C→S | `intention` (handshake) | Protokoll 777, Host, Port, Intent LOGIN |
| 2 | C→S | `hello` (0) | Name (≤ 16), Profil-UUID des Benutzers |
| 3 | S→C | `hello` (1) = Encryption Request | `serverId` (String ≤ 20, Vanilla-Server: ""), `publicKey` (Byte-Array, X.509-DER), `challenge` (Byte-Array, 4 Bytes), `shouldAuthenticate` (Boolean) |
| 4 | Client | – | 128-Bit-AES-Schlüssel erzeugen, Hash berechnen, bei `shouldAuthenticate` Session-Nachweis `joinServer` (asynchron) |
| 5 | C→S | `key` (1) = Encryption Response | `RSA(secret)` und `RSA(challenge)` als Byte-Arrays, **noch unverschlüsselt** |
| 6 | beide | – | Verschlüsselung aktiv: Vanilla nach dem Senden von `key` (`send(key, thenRun(setEncryptionKey))`); Server direkt nach der Prüfung in `handleKey` |
| 7 | S→C | `login_compression` (3), optional | Schwellwert, bereits verschlüsselt |
| 8 | S→C | `login_finished` (2) | GameProfile (UUID, Name, Properties) + Session-UUID |
| 9 | C→S | `login_acknowledged` (3) | → Configuration |

- **Offline-Server:** `ServerLoginPacketListenerImpl#handleHello` sendet bei `online-mode=false` (oder bei
  Memory-Verbindungen) kein `hello`; der Server erzeugt das Offline-Profil. Der bisherige Ablauf bleibt deshalb
  unverändert.
- **Fehlgeschlagener Session-Nachweis:** Der Vanilla-Client trennt die Verbindung (außer im LAN), sendet `key` nicht
  und schaltet nicht auf Verschlüsselung. Unser Client macht es genauso.

## 2. Implementierung im Client

`session.ClientSession#onEncryptionRequest` läuft im Lesethread; bis zur Umschaltung wird daher kein weiteres Paket
gelesen:

1. `LoginCrypto.serverPublicKey(der)`: RSA-Schlüssel aus X.509-DER; die Länge muss zwischen 512 und 4096 Bit liegen.
   Andernfalls folgt `ProtocolException` „Invalid server key“.
2. `LoginCrypto.newSharedSecret()`: `KeyGenerator("AES")`, 128 Bit, `SecureRandom`.
3. `LoginCrypto.serverHash(serverId, secret, publicKeyDer)`.
4. RSA-Verschlüsselung von Schlüssel und Challenge mit `RSA/ECB/PKCS1Padding`. Vanilla schreibt `"RSA"`; das ist beim
   SunJCE-Provider derselbe Algorithmus.
5. Bei `shouldAuthenticate`: `LoginIdentity#joinServer(hash)`. Scheitert der Nachweis, wirft die Methode eine
   `AuthException`; die Sitzung endet kontrolliert, ohne dass `key` gesendet wird.
6. `send(Key)`, danach `PacketChannel#enableEncryption(secret)`.

Eine Offline-Identität bricht bei `shouldAuthenticate=true` mit der Meldung „offline identity cannot join“ ab, genau
wie der frühere Stand.

## 3. Kryptografie (exakte Byte-Reihenfolge)

- **Session-Hash** (`Crypt#digestData` + `new BigInteger(digest).toString(16)`):
  `SHA-1( serverId als ISO-8859-1 ‖ secret (16 Byte, roh) ‖ publicKey (X.509-DER, wie empfangen) )`.
  - Das 20-Byte-Ergebnis wird als **vorzeichenbehaftete Zweierkomplement-Zahl** in Big-Endian gelesen.
  - Die Hexdarstellung hat keine führenden Nullen; negative Werte beginnen mit `-`.
  - Testvektoren für `SHA-1(Name)`:

    | Name | Hash |
    |---|---|
    | `Notch` | `4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48` |
    | `jeb_` | `-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1` |
    | `simon` | `88e16a1019277b15d58faf0541e11910eb756f6` (führende Null entfällt) |
- **Datenstrom:** `AES/CFB8/NoPadding`, Schlüssel = IV = die 16 Schlüsselbytes. Pro Richtung gibt es eine eigene
  Chiffre, deren Zustand über die ganze Verbindung fortläuft.

## 4. Verschlüsselung im Datenstrom

Vanilla-Pipeline: `decrypt` vor `splitter`, `encrypt` vor `prepender`. Die Verschlüsselung liegt also **unter** dem
Framing und der Kompression; verschlüsselt wird der ganze Bytestrom inklusive Längen-VarInts.

Im eigenen Client (`net.Connection`):

```
Socket-In → BufferedInputStream → CipherStreams.Decrypting → FrameCodec (VarInt-Länge) → CompressionCodec → Paket
Paket → CompressionCodec → FrameCodec → CipherStreams.Encrypting → BufferedOutputStream → Socket-Out
```

- Die Chiffre-Streams liegen **oberhalb** der Puffer. Bereits gepufferte, noch nicht gelesene Bytes werden deshalb
  korrekt entschlüsselt, sobald sie gelesen werden.
- Beim Umschalten des Ausgangs wird zuerst geleert, dann umgeschaltet (unter `sendLock`).
- `Cipher#update` liefert in CFB8 zu jedem Eingabebyte genau ein Ausgabebyte; Abweichungen führen zu `IOException`
  „out of sync“.
- Die Streams erben bewusst nicht von `FilterInputStream`/`FilterOutputStream`. So kann keine geerbte
  Massenoperation (`readNBytes`, `transferTo`) die Chiffre umgehen.
- **Kompression:** `login_compression` kommt *nach* der Umschaltung und ist bereits verschlüsselt. Die Kompression
  arbeitet innerhalb der Verschlüsselung (Frame-Inhalt = `VarInt dataLength` + zlib). Die Reihenfolge der
  Aktivierung entspricht Vanilla (erst Verschlüsselung, dann Kompression).

## 5. Abschluss des Logins

Der Rest des Logins läuft wie bisher: `login_finished` → Eingang auf Configuration → `login_acknowledged` → Ausgang
auf Configuration.

Neu ist, dass der Client das vom Server bestätigte Profil speichert (`ClientSession#loginProfile`). Weicht der Name
oder die UUID von der Identität ab, schreibt er eine Warnung ins Log. So lässt sich die Spieleridentität am Serverzustand
prüfen (docs/53).

## 6. Session-Nachweis (Phase F)

`auth.minecraft.OnlineIdentity#joinServer` entspricht `MinecraftServicesSessionService#joinServer`:

- Adresse: `ServiceDiscovery#endpoint("session", "join")`. Das Discovery-Dokument wird einmal pro Identität geladen,
  und jede Adresse wird gegen die `EndpointPolicy` geprüft.
- Body: `{"accessToken":"…","selectedProfile":"<32 Hexzeichen>","serverId":"<Hash>"}`, Content-Type
  `application/json; charset=utf-8`.
- Erfolg ist HTTP 2xx. Fehler werden zugeordnet wie in authlib (docs/50 §5); dazu kommen HTTP 429 (Rate-Limit mit
  `Retry-After`) und `MINECRAFT_TOKEN_EXPIRED`. Abgelaufene Tokens erkennt der Client lokal, **bevor** er eine Anfrage
  sendet.
- Zeitlimits: 5 s für den Verbindungsaufbau, 10 s für die Antwort. Der Lesetimeout der Spielverbindung (30 s) und das
  Login-Zeitlimit des Servers (600 Ticks = 30 s) liegen darüber.
- Jede Verbindung erzeugt einen neuen Schlüssel und damit einen neuen Hash. Ein Nachweis gilt deshalb nur für genau
  diese Verbindung; eine Neuverbindung braucht einen neuen `join`.

## 7. Nicht umgesetzt (bewusst)

- **Profilschlüssel und signierter Chat** (`player/certificates`, `chat_session_update`): Der Login braucht sie nicht.
  Der Testserver läuft mit `enforce-secure-profile=false`; laut `DedicatedServer#enforceSecureProfile` gilt die
  Pflicht ohnehin nur im Online-Mode mit gültigem Schlüsselsatz. Das ist eine offene Einschränkung für Chat auf
  Servern mit `enforce-secure-profile=true`.
- Telemetrie, Blockliste und Freunde (authlib `UserApiService`): nicht nötig und nicht implementiert.
