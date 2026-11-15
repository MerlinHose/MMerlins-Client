package dev.mcresearch.protocolclient.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.auth.http.EndpointPolicy;
import dev.mcresearch.protocolclient.auth.http.HttpTransport;
import dev.mcresearch.protocolclient.auth.http.JdkHttpTransport;
import dev.mcresearch.protocolclient.auth.microsoft.LoopbackReceiver;
import dev.mcresearch.protocolclient.auth.microsoft.MicrosoftOAuth;
import dev.mcresearch.protocolclient.auth.microsoft.Pkce;
import dev.mcresearch.protocolclient.auth.minecraft.MicrosoftAccountLogin;
import dev.mcresearch.protocolclient.auth.minecraft.OnlineIdentity;
import dev.mcresearch.protocolclient.auth.xbox.XboxLive;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * M13 Phase C/D/F/J: Anmeldekette mit HTTP-Attrappe (keine externe Verbindung). Prüft Anfrageformate, Sicherheitsregeln,
 * Fehlerzuordnung und dass keine Testgeheimnisse ({@code SECRET-TEST-…}) in Meldungen gelangen.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AuthChainTest {
	/** Platzhalter-GUID für Tests, keine echte App-Registrierung. */
	static final String TEST_CLIENT_ID = "11111111-2222-3333-4444-555555555555";
	private final List<String> messages = new ArrayList<>();

	private <T extends Throwable> T record(final T t) {
		this.messages.add(String.valueOf(t.getMessage()));
		this.messages.add(t.toString());
		return t;
	}

	private void assertNoSecretsLeaked() {
		for (String m : this.messages) {
			assertFalse(m.contains("SECRET-TEST"), "secret leaked in: " + m);
		}
	}

	// ---------- Netzwerkregeln ----------

	@Test
	void endpointPolicy() {
		EndpointPolicy deny = EndpointPolicy.denyAll();
		assertEquals(AuthError.EXTERNAL_NOT_ALLOWED, record(assertThrows(AuthException.class,
			() -> deny.check(URI.create("https://login.microsoftonline.com/consumers")))).error());
		EndpointPolicy off = EndpointPolicy.official();
		assertDoesNotThrow(() -> off.check(URI.create("https://api.minecraftservices.com/minecraft/profile")));
		assertDoesNotThrow(() -> off.check(URI.create("https://sessionserver.mojang.com:443/session/minecraft/join")));
		for (String bad : List.of("http://api.minecraftservices.com/x", "https://evil.example/x", "https://api.minecraftservices.com.evil.example/",
			"https://api.minecraftservices.com:8443/", "https://user@api.minecraftservices.com/", "https://127.0.0.1:443/", "ftp://login.microsoftonline.com/")) {
			assertEquals(AuthError.ENDPOINT_NOT_ALLOWED, record(assertThrows(AuthException.class, () -> off.check(URI.create(bad)))).error(), bad);
		}
		EndpointPolicy sim = EndpointPolicy.localSimulation();
		assertDoesNotThrow(() -> sim.check(URI.create("http://127.0.0.1:25599/session/join")));
		for (String bad : List.of("https://sessionserver.mojang.com/x", "http://localhost:25599/x", "http://10.0.0.1:80/x", "http://127.0.0.1/x")) {
			assertThrows(AuthException.class, () -> sim.check(URI.create(bad)), bad);
		}
	}

	@Test
	void defaultTransportNeverConnects() {
		JdkHttpTransport t = new JdkHttpTransport(EndpointPolicy.denyAll());
		AuthException e = assertThrows(AuthException.class, () -> t.send(new HttpTransport.Request("GET", URI.create("https://api.minecraftservices.com/"),
			Map.of(), null)));
		assertEquals(AuthError.EXTERNAL_NOT_ALLOWED, e.error());
	}

	// ---------- PKCE und Loopback ----------

	@Test
	void pkceValues() {
		SecureRandom r = new SecureRandom();
		Pkce a = Pkce.generate(r);
		Pkce b = Pkce.generate(r);
		assertEquals(43, a.verifier().length(), "32 bytes base64url");
		assertTrue(a.verifier().matches("[A-Za-z0-9_-]{43}"));
		assertEquals(Pkce.challengeOf(a.verifier()), a.challenge());
		assertFalse(a.verifier().equals(b.verifier()));
		assertFalse(a.toString().contains(a.verifier()));
	}

	@Test
	void authorizationUriHasPkceStateAndNoSecret() {
		MicrosoftOAuth o = new MicrosoftOAuth(TEST_CLIENT_ID, new FakeHttp(EndpointPolicy.official()));
		Pkce p = Pkce.generate(new SecureRandom());
		URI u = o.authorizationUri("http://localhost:51234", "STATE123", p);
		Map<String, String> q = query(u);
		assertEquals("https", u.getScheme());
		assertEquals("login.microsoftonline.com", u.getHost());
		assertEquals("/consumers/oauth2/v2.0/authorize", u.getPath());
		assertEquals("code", q.get("response_type"));
		assertEquals("S256", q.get("code_challenge_method"));
		assertEquals(p.challenge(), q.get("code_challenge"));
		assertEquals("STATE123", q.get("state"));
		assertEquals("XboxLive.signin offline_access", q.get("scope"));
		assertEquals("http://localhost:51234", q.get("redirect_uri"));
		assertFalse(q.containsKey("client_secret"));
		assertFalse(u.toString().contains(p.verifier()));
		assertThrows(IllegalArgumentException.class, () -> new MicrosoftOAuth("not-a-guid", new FakeHttp(EndpointPolicy.official())));
	}

	static Map<String, String> query(final URI u) {
		Map<String, String> m = new HashMap<>();
		for (String part : u.getRawQuery().split("&")) {
			int eq = part.indexOf('=');
			m.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return m;
	}

	private static int get(final String url) throws Exception {
		try (HttpClient c = HttpClient.newHttpClient()) {
			return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
		}
	}

	private static String raw(final int port, final String request) throws Exception {
		try (Socket s = new Socket(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port)) {
			OutputStream o = s.getOutputStream();
			o.write(request.getBytes(StandardCharsets.US_ASCII));
			o.flush();
			InputStream in = s.getInputStream();
			return new String(in.readNBytes(12), StandardCharsets.US_ASCII);
		}
	}

	@Test
	void loopbackReceiverAcceptsOnlyMatchingState() throws Exception {
		try (LoopbackReceiver r = new LoopbackReceiver("GOODSTATE")) {
			assertTrue(r.redirectUri().startsWith("http://localhost:"));
			assertEquals(404, get("http://127.0.0.1:" + r.port() + "/favicon.ico"));
			assertFalse(r.result().isDone(), "unrelated paths do not end the flow");
			assertTrue(raw(r.port(), "GET /?code=SECRET-TEST-CODE&state=GOODSTATE HTTP/1.1\r\nHost: evil.example:" + r.port() + "\r\n\r\n")
				.contains("400"), "foreign Host header (DNS rebinding) rejected");
			assertFalse(r.result().isDone());
			assertEquals(200, get("http://127.0.0.1:" + r.port() + "/?code=SECRET-TEST-CODE&state=GOODSTATE"));
			assertEquals("SECRET-TEST-CODE", r.result().get().code());
			assertFalse(r.result().get().toString().contains("SECRET"));
			assertEquals(409, get("http://127.0.0.1:" + r.port() + "/?code=OTHER&state=GOODSTATE"), "only the first answer counts");
		}
		try (LoopbackReceiver r = new LoopbackReceiver("GOODSTATE")) {
			assertEquals(400, get("http://127.0.0.1:" + r.port() + "/?code=SECRET-TEST-CODE&state=FORGED"));
			Exception e = assertThrows(Exception.class, () -> r.result().get());
			assertEquals(AuthError.STATE_MISMATCH, ((AuthException) e.getCause()).error());
		}
		try (LoopbackReceiver r = new LoopbackReceiver("S")) {
			assertEquals(200, get("http://127.0.0.1:" + r.port() + "/?error=access_denied&error_description=user+cancelled&state=S"));
			Exception e = assertThrows(Exception.class, () -> r.result().get());
			assertEquals(AuthError.USER_CANCELLED, ((AuthException) e.getCause()).error());
		}
	}

	/** Browser-Attrappe: folgt der Weiterleitung lokal mit einem Test-Code (kein Microsoft-Kontakt). */
	private static MicrosoftOAuth.BrowserOpener fakeBrowser(final List<URI> opened, final String stateOverride) {
		return uri -> {
			opened.add(uri);
			Map<String, String> q = query(uri);
			String state = stateOverride == null ? q.get("state") : stateOverride;
			Thread t = new Thread(() -> {
				try {
					get(q.get("redirect_uri").replace("localhost", "127.0.0.1") + "/?code=SECRET-TEST-CODE&state=" + state);
				} catch (Exception e) {
					// Testabbruch
				}
			});
			t.setDaemon(true);
			t.start();
		};
	}

	@Test
	void interactiveLoginRedeemsCodeWithVerifier() throws Exception {
		FakeHttp http = FakeHttp.happyChain();
		MicrosoftOAuth o = new MicrosoftOAuth(TEST_CLIENT_ID, http);
		List<URI> opened = new ArrayList<>();
		var tokens = o.interactiveLogin(fakeBrowser(opened, null), new AuthCancellation(), Duration.ofSeconds(10));
		assertEquals("SECRET-TEST-MSA", tokens.accessToken().reveal());
		assertFalse(tokens.toString().contains("SECRET"));
		HttpTransport.Request tokenReq = http.requests.get(0);
		Map<String, String> form = query(URI.create("x:/?" + FakeHttp.body(tokenReq)));
		Map<String, String> auth = query(opened.get(0));
		assertEquals("authorization_code", form.get("grant_type"));
		assertEquals("SECRET-TEST-CODE", form.get("code"));
		assertEquals(auth.get("redirect_uri"), form.get("redirect_uri"));
		assertEquals(auth.get("code_challenge"), Pkce.challengeOf(form.get("code_verifier")), "verifier matches challenge");
		assertFalse(form.containsKey("client_secret"));
		assertFalse(tokenReq.toString().contains("SECRET"), "request toString hides body");
	}

	@Test
	void interactiveLoginStateMismatchTimeoutAndCancel() throws Exception {
		MicrosoftOAuth o = new MicrosoftOAuth(TEST_CLIENT_ID, FakeHttp.happyChain());
		AuthException e = assertThrows(AuthException.class, () -> o.interactiveLogin(fakeBrowser(new ArrayList<>(), "FORGED"), new AuthCancellation(),
			Duration.ofSeconds(10)));
		assertEquals(AuthError.STATE_MISMATCH, record(e).error());
		e = assertThrows(AuthException.class, () -> o.interactiveLogin(uri -> { }, new AuthCancellation(), Duration.ofMillis(300)));
		assertEquals(AuthError.TIMEOUT, record(e).error());
		AuthCancellation cancel = new AuthCancellation();
		e = assertThrows(AuthException.class, () -> o.interactiveLogin(uri -> {
			Thread t = new Thread(() -> {
				try {
					Thread.sleep(200);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				cancel.cancel();
			});
			t.start();
		}, cancel, Duration.ofSeconds(10)));
		assertEquals(AuthError.USER_CANCELLED, record(e).error());
		// ohne Freigabe externer Verbindungen öffnet sich kein Browser
		List<URI> opened = new ArrayList<>();
		MicrosoftOAuth denied = new MicrosoftOAuth(TEST_CLIENT_ID, new FakeHttp(EndpointPolicy.denyAll()));
		e = assertThrows(AuthException.class, () -> denied.interactiveLogin(opened::add, new AuthCancellation(), Duration.ofSeconds(1)));
		assertEquals(AuthError.EXTERNAL_NOT_ALLOWED, e.error());
		assertTrue(opened.isEmpty());
		assertNoSecretsLeaked();
	}

	@Test
	void deviceCodeFlow() throws Exception {
		FakeHttp http = new FakeHttp(EndpointPolicy.official());
		http.on("login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", 200,
			"{\"device_code\":\"SECRET-TEST-DEVICE\",\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"https://www.microsoft.com/link\",\"expires_in\":900,\"interval\":5}");
		http.on("login.microsoftonline.com/consumers/oauth2/v2.0/token", 400, "{\"error\":\"authorization_pending\"}");
		http.on("login.microsoftonline.com/consumers/oauth2/v2.0/token", 200, "{\"access_token\":\"SECRET-TEST-MSA\",\"expires_in\":3600}");
		List<MicrosoftOAuth.DeviceCodePrompt> shown = new ArrayList<>();
		long t0 = System.nanoTime();
		var tokens = new MicrosoftOAuth(TEST_CLIENT_ID, http).deviceCodeLogin(shown::add, new AuthCancellation());
		assertEquals("ABCD-EFGH", shown.get(0).userCode());
		assertEquals("SECRET-TEST-MSA", tokens.accessToken().reveal());
		assertTrue(System.nanoTime() - t0 >= TimeUnit.SECONDS.toNanos(9), "polls at the server interval (>= 5 s), no hammering");
		FakeHttp declined = new FakeHttp(EndpointPolicy.official());
		declined.on("login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", 200,
			"{\"device_code\":\"SECRET-TEST-DEVICE\",\"user_code\":\"X\",\"verification_uri\":\"https://www.microsoft.com/link\",\"interval\":5}");
		declined.on("login.microsoftonline.com/consumers/oauth2/v2.0/token", 400, "{\"error\":\"authorization_declined\"}");
		AuthException e = assertThrows(AuthException.class, () -> new MicrosoftOAuth(TEST_CLIENT_ID, declined).deviceCodeLogin(p -> { },
			new AuthCancellation()));
		assertEquals(AuthError.USER_CANCELLED, record(e).error());
		assertNoSecretsLeaked();
	}

	// ---------- Xbox, Minecraft, Session ----------

	private static OnlineIdentity fullLogin(final FakeHttp http) throws AuthException {
		List<URI> opened = new ArrayList<>();
		return new MicrosoftAccountLogin(TEST_CLIENT_ID, http, Clock.systemUTC()).login(MicrosoftAccountLogin.Method.BROWSER, fakeBrowser(opened, null),
			p -> { }, s -> { }, new AuthCancellation());
	}

	@Test
	void fullChainRequestFormats() throws Exception {
		FakeHttp http = FakeHttp.happyChain();
		OnlineIdentity id = fullLogin(http);
		assertEquals("TestPlayer_13", id.name());
		assertEquals(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), id.profileId());
		assertEquals("microsoft", id.kind());
		Map<String, HttpTransport.Request> byPath = new HashMap<>();
		for (HttpTransport.Request r : http.requests) {
			byPath.put(r.uri().getPath(), r);
		}
		assertTrue(FakeHttp.body(byPath.get("/user/authenticate")).contains("\"RpsTicket\":\"d=SECRET-TEST-MSA\""));
		assertTrue(FakeHttp.body(byPath.get("/xsts/authorize")).contains("\"RelyingParty\":\"rp://api.minecraftservices.com/\""));
		assertTrue(FakeHttp.body(byPath.get("/xsts/authorize")).contains("\"SandboxId\":\"RETAIL\""));
		assertTrue(FakeHttp.body(byPath.get("/authentication/login_with_xbox")).contains("\"identityToken\":\"XBL3.0 x=1234567890;SECRET-TEST-XSTS\""));
		assertEquals("Bearer SECRET-TEST-MC", byPath.get("/minecraft/profile").headers().get("Authorization"));
		assertEquals("Bearer SECRET-TEST-MC", byPath.get("/player/attributes").headers().get("Authorization"));
		id.joinServer("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1");
		HttpTransport.Request join = http.requests.get(http.requests.size() - 1);
		assertEquals("/session/minecraft/join", join.uri().getPath());
		assertEquals("{\"accessToken\":\"SECRET-TEST-MC\",\"selectedProfile\":\"0123456789abcdef0123456789abcdef\",\"serverId\":\"-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1\"}",
			FakeHttp.body(join), "authlib JoinMinecraftServerRequest format (undashed UUID)");
		assertFalse(id.toString().contains("SECRET"));
		id.logout();
		assertEquals(AuthError.INVALID_SESSION, record(assertThrows(AuthException.class, () -> id.joinServer("abc"))).error());
		assertNoSecretsLeaked();
	}

	private void expectChainError(final FakeHttp http, final AuthError expected) {
		AuthException e = assertThrows(AuthException.class, () -> fullLogin(http));
		assertEquals(expected, record(e).error(), e.getMessage());
	}

	@Test
	void knownAccountErrors() {
		Map<Long, AuthError> xerr = Map.of(2148916233L, AuthError.NO_XBOX_ACCOUNT, 2148916235L, AuthError.XBOX_COUNTRY, 2148916236L,
			AuthError.XBOX_ADULT_VERIFICATION, 2148916238L, AuthError.XBOX_CHILD, 2148916227L, AuthError.XBOX_BANNED, 1L, AuthError.XBOX_ERROR);
		for (Map.Entry<Long, AuthError> x : xerr.entrySet()) {
			FakeHttp h = FakeHttp.happyChain();
			h.routesReplace("xsts.auth.xboxlive.com/xsts/authorize", 401, "{\"Identity\":\"0\",\"XErr\":" + x.getKey() + ",\"Message\":\"\"}");
			expectChainError(h, x.getValue());
			assertEquals(x.getValue(), XboxLive.xerr(x.getKey(), "").error());
		}
		FakeHttp app = FakeHttp.happyChain();
		app.routesReplace("api.minecraftservices.com/authentication/login_with_xbox", 403,
			"{\"path\":\"/authentication/login_with_xbox\",\"errorMessage\":\"Invalid app registration, see https://aka.ms/AppRegInfo for more information\"}");
		expectChainError(app, AuthError.APP_NOT_APPROVED);
		FakeHttp noProfile = FakeHttp.happyChain();
		noProfile.routesReplace("api.minecraftservices.com/minecraft/profile", 404, "{\"error\":\"NOT_FOUND\"}");
		expectChainError(noProfile, AuthError.NO_MINECRAFT_PROFILE);
		FakeHttp noGame = FakeHttp.happyChain();
		noGame.routesReplace("api.minecraftservices.com/entitlements/mcstore", 200, "{\"items\":[]}");
		expectChainError(noGame, AuthError.NO_OWNERSHIP);
		FakeHttp noMp = FakeHttp.happyChain();
		noMp.routesReplace("api.minecraftservices.com/player/attributes", 200, "{\"privileges\":{\"multiplayerServer\":{\"enabled\":false}}}");
		expectChainError(noMp, AuthError.MULTIPLAYER_DISABLED);
		FakeHttp banned = FakeHttp.happyChain();
		banned.routesReplace("api.minecraftservices.com/player/attributes", 200,
			"{\"privileges\":{\"multiplayerServer\":{\"enabled\":true}},\"banStatus\":{\"bannedScopes\":{\"MULTIPLAYER\":{\"banId\":\"x\"}}}}");
		expectChainError(banned, AuthError.USER_BANNED);
		FakeHttp invalidGrant = FakeHttp.happyChain();
		invalidGrant.routesReplace("login.microsoftonline.com/consumers/oauth2/v2.0/token", 400,
			"{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS70008: code expired SECRET-TEST-CODE\",\"error_codes\":[70008]}");
		expectChainError(invalidGrant, AuthError.INVALID_GRANT);
		FakeHttp limited = FakeHttp.happyChain();
		limited.routesReplace("api.minecraftservices.com/authentication/login_with_xbox", 429, "");
		expectChainError(limited, AuthError.RATE_LIMITED);
		FakeHttp down = FakeHttp.happyChain();
		down.routesReplace("user.auth.xboxlive.com/user/authenticate", 503, "");
		expectChainError(down, AuthError.SERVICE_UNAVAILABLE);
		FakeHttp foreign = FakeHttp.happyChain();
		foreign.routesReplace("discovery.minecraftservices.com/minecraft/client", 200,
			"{\"environment\":\"prod\",\"discovery\":{\"player\":{\"endpoints\":{\"getAttributes\":{\"uri\":\"https://attacker.example/attributes\"}}}}}");
		expectChainError(foreign, AuthError.ENDPOINT_NOT_ALLOWED);
		assertNoSecretsLeaked();
	}

	@Test
	void sessionJoinErrors() throws Exception {
		Map<String, AuthError> cases = new java.util.LinkedHashMap<>();
		cases.put("403|{\"error\":\"ForbiddenOperationException\",\"errorMessage\":\"Invalid token\"}", AuthError.INVALID_SESSION);
		cases.put("403|{\"error\":\"InsufficientPrivilegesException\"}", AuthError.INSUFFICIENT_PRIVILEGES);
		cases.put("403|{\"error\":\"multiplayer.access.banned\"}", AuthError.USER_BANNED);
		cases.put("401|", AuthError.INVALID_SESSION);
		cases.put("429|", AuthError.RATE_LIMITED);
		cases.put("503|<html>down</html>", AuthError.SERVICE_UNAVAILABLE);
		cases.put("400|{}", AuthError.PROTOCOL);
		for (Map.Entry<String, AuthError> c : cases.entrySet()) {
			FakeHttp http = FakeHttp.happyChain();
			String[] sb = c.getKey().split("\\|", 2);
			http.routesReplace("sessionserver.mojang.com/session/minecraft/join", Integer.parseInt(sb[0]), sb[1]);
			OnlineIdentity id = fullLogin(http);
			AuthException e = assertThrows(AuthException.class, () -> id.joinServer("1a2b"));
			assertEquals(c.getValue(), record(e).error(), c.getKey());
		}
		assertNoSecretsLeaked();
	}

	@Test
	void expiredMinecraftTokenIsDetectedBeforeJoin() throws Exception {
		FakeHttp http = FakeHttp.happyChain();
		http.routesReplace("api.minecraftservices.com/authentication/login_with_xbox", 200, "{\"access_token\":\"SECRET-TEST-MC\",\"expires_in\":120}");
		Clock later = Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5));
		OnlineIdentity fresh = fullLogin(http);
		OnlineIdentity id = new OnlineIdentity(fresh.name(), fresh.profileId(), new SecretToken("minecraft-access", "SECRET-TEST-MC",
			Instant.now().plusSeconds(60)), new dev.mcresearch.protocolclient.auth.minecraft.ServiceDiscovery(http,
				dev.mcresearch.protocolclient.auth.minecraft.ServiceDiscovery.PRODUCTION), http, later, "microsoft");
		int before = http.requests.size();
		AuthException e = assertThrows(AuthException.class, () -> id.joinServer("1a2b"));
		assertEquals(AuthError.MINECRAFT_TOKEN_EXPIRED, record(e).error());
		assertEquals(before, http.requests.size(), "no request with an expired token");
		assertTrue(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC).instant().isBefore(Instant.now()));
		assertNoSecretsLeaked();
	}

	@Test
	void logoutAndRelogin() throws Exception {
		FakeHttp http = FakeHttp.happyChain();
		MicrosoftAccountLogin login = new MicrosoftAccountLogin(TEST_CLIENT_ID, http, Clock.systemUTC());
		OnlineIdentity a = login.login(MicrosoftAccountLogin.Method.BROWSER, fakeBrowser(new ArrayList<>(), null), p -> { }, s -> { }, new AuthCancellation());
		OnlineIdentity b = login.refresh(s -> { }, new AuthCancellation());
		assertEquals(a.profileId(), b.profileId());
		HttpTransport.Request refresh = http.requests.stream().filter(r -> FakeHttp.body(r).contains("grant_type=refresh_token")).findFirst().orElseThrow();
		assertTrue(FakeHttp.body(refresh).contains("refresh_token=SECRET-TEST-REFRESH"));
		login.logout();
		a.logout();
		assertFalse(a.canAuthenticate());
		AuthException e = assertThrows(AuthException.class, () -> login.refresh(s -> { }, new AuthCancellation()));
		assertEquals(AuthError.INVALID_GRANT, record(e).error());
		OnlineIdentity c = login.login(MicrosoftAccountLogin.Method.BROWSER, fakeBrowser(new ArrayList<>(), null), p -> { }, s -> { }, new AuthCancellation());
		assertTrue(c.canAuthenticate());
		assertNoSecretsLeaked();
	}
}
