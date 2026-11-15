package dev.mcresearch.protocolclient.auth.microsoft;

import dev.mcresearch.protocolclient.auth.AuthCancellation;
import dev.mcresearch.protocolclient.auth.AuthError;
import dev.mcresearch.protocolclient.auth.AuthException;
import dev.mcresearch.protocolclient.auth.SecretToken;
import dev.mcresearch.protocolclient.auth.http.HttpTransport;
import dev.mcresearch.protocolclient.auth.http.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * M13: Microsoft-Anmeldung für Privatkonten über die Microsoft Identity Platform (v2.0), docs/50 §2 und docs/52 §4.
 * <ul>
 * <li>Authorization Code Flow mit PKCE (S256) über den Systembrowser, öffentlicher Client ohne Client-Secret;</li>
 * <li>Tenant {@code consumers} (persönliche Microsoft-Konten), Scope {@code XboxLive.signin offline_access};</li>
 * <li>alternativ Device Code Flow ({@link #deviceCodeLogin});</li>
 * <li>das Passwort wird nie vom Client gesehen: die Eingabe erfolgt ausschließlich auf der Microsoft-Seite im Browser.</li>
 * </ul>
 * Die Client-ID stammt aus der eigenen App-Registrierung des Benutzers (keine fremde oder extrahierte ID, docs/50 §4).
 */
public final class MicrosoftOAuth {
	public static final String AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
	public static final String SCOPE = "XboxLive.signin offline_access";

	/** Darstellung eines Device-Codes für den Benutzer (Code und Prüfadresse sind nicht geheim). */
	public record DeviceCodePrompt(String userCode, String verificationUri, Duration expiresIn, String message) {
	}

	/** Öffnet die Anmeldeseite im Systembrowser. */
	public interface BrowserOpener {
		void open(URI uri) throws IOException;
	}

	private final String clientId;
	private final HttpTransport http;
	private final Clock clock;
	private final SecureRandom random;

	public MicrosoftOAuth(final String clientId, final HttpTransport http) {
		this(clientId, http, Clock.systemUTC(), new SecureRandom());
	}

	public MicrosoftOAuth(final String clientId, final HttpTransport http, final Clock clock, final SecureRandom random) {
		if (clientId == null || !clientId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
			throw new IllegalArgumentException("client id must be the GUID of your own app registration");
		}
		this.clientId = clientId;
		this.http = http;
		this.clock = clock;
		this.random = random;
	}

	/** Anmelde-URL für den Browser (nicht geheim; enthält nur Challenge und state). */
	public URI authorizationUri(final String redirectUri, final String state, final Pkce pkce) {
		Map<String, String> q = new LinkedHashMap<>();
		q.put("client_id", this.clientId);
		q.put("response_type", "code");
		q.put("redirect_uri", redirectUri);
		q.put("response_mode", "query");
		q.put("scope", SCOPE);
		q.put("state", state);
		q.put("code_challenge", pkce.challenge());
		q.put("code_challenge_method", "S256");
		q.put("prompt", "select_account");
		return URI.create(AUTHORITY + "/authorize?" + form(q));
	}

	/**
	 * Interaktive Anmeldung: Loopback-Empfänger starten, Browser öffnen, auf die Weiterleitung warten (höchstens
	 * {@code timeout}), Code mit PKCE-Verifier einlösen.
	 */
	public MicrosoftTokens interactiveLogin(final BrowserOpener browser, final AuthCancellation cancel, final Duration timeout) throws AuthException {
		Pkce pkce = Pkce.generate(this.random);
		String state = Pkce.randomToken(this.random);
		LoopbackReceiver.Callback callback;
		String redirect;
		try (LoopbackReceiver receiver = new LoopbackReceiver(state)) {
			redirect = receiver.redirectUri();
			URI authorize = authorizationUri(redirect, state, pkce);
			this.http.policy().check(authorize); // der Browser verbindet sich extern: dieselbe Freigabe nötig
			cancel.onCancel(receiver::close);
			try {
				browser.open(authorize);
			} catch (IOException e) {
				throw new AuthException(AuthError.OAUTH_ERROR, "cannot open system browser: " + e.getClass().getSimpleName());
			}
			callback = receiver.result().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (AuthException e) {
			throw e;
		} catch (IOException e) {
			throw new AuthException(AuthError.OAUTH_ERROR, "loopback receiver: " + e.getClass().getSimpleName());
		} catch (TimeoutException e) {
			throw new AuthException(AuthError.TIMEOUT, "no browser redirect within " + timeout.toSeconds() + " s");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AuthException(AuthError.USER_CANCELLED, "interrupted");
		} catch (ExecutionException e) {
			if (e.getCause() instanceof AuthException a) {
				throw cancel.cancelled() ? new AuthException(AuthError.USER_CANCELLED, "") : a;
			}
			throw new AuthException(AuthError.OAUTH_ERROR, "redirect failed");
		}
		cancel.throwIfCancelled();
		Map<String, String> f = new LinkedHashMap<>();
		f.put("client_id", this.clientId);
		f.put("grant_type", "authorization_code");
		f.put("code", callback.code());
		f.put("redirect_uri", redirect);
		f.put("code_verifier", pkce.verifier());
		f.put("scope", SCOPE);
		return tokens(postForm("/token", f), "token");
	}

	/** Device Code Flow (Alternative ohne lokalen Redirect, z. B. Terminal): Code anzeigen, Token abfragen. */
	public MicrosoftTokens deviceCodeLogin(final Consumer<DeviceCodePrompt> show, final AuthCancellation cancel) throws AuthException {
		Map<String, String> f = new LinkedHashMap<>();
		f.put("client_id", this.clientId);
		f.put("scope", SCOPE);
		HttpTransport.Response r = postForm("/devicecode", f);
		Json j = Json.parse(r.body(), "devicecode");
		if (r.status() != 200) {
			throw oauthError(r, j);
		}
		String deviceCode = j.requireString("devicecode", "device_code");
		long interval = Math.max(5, j.number(5, "interval"));
		Duration expires = Duration.ofSeconds(Math.min(900, j.number(900, "expires_in")));
		show.accept(new DeviceCodePrompt(j.requireString("devicecode", "user_code"), j.requireString("devicecode", "verification_uri"), expires,
			j.string("message")));
		Instant deadline = this.clock.instant().plus(expires);
		while (true) {
			sleep(interval, cancel);
			if (this.clock.instant().isAfter(deadline)) {
				throw new AuthException(AuthError.TIMEOUT, "device code expired");
			}
			Map<String, String> t = new LinkedHashMap<>();
			t.put("client_id", this.clientId);
			t.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
			t.put("device_code", deviceCode);
			HttpTransport.Response tr = postForm("/token", t);
			if (tr.status() == 200) {
				return tokens(tr, "token");
			}
			Json e = Json.parse(tr.body(), "token");
			String err = String.valueOf(e.string("error"));
			switch (err) {
				case "authorization_pending" -> {
				}
				case "slow_down" -> interval += 5;
				case "authorization_declined" -> throw new AuthException(AuthError.USER_CANCELLED, "authorization_declined");
				case "expired_token" -> throw new AuthException(AuthError.TIMEOUT, "expired_token");
				default -> throw oauthError(tr, e);
			}
		}
	}

	/** Erneuert die Microsoft-Tokens mit dem Refresh-Token (alter Refresh-Token wird verworfen, docs/50 §2). */
	public MicrosoftTokens refresh(final MicrosoftTokens old) throws AuthException {
		if (old.refreshToken() == null) {
			throw new AuthException(AuthError.INVALID_GRANT, "no refresh token");
		}
		Map<String, String> f = new LinkedHashMap<>();
		f.put("client_id", this.clientId);
		f.put("grant_type", "refresh_token");
		f.put("refresh_token", old.refreshToken().reveal());
		f.put("scope", SCOPE);
		MicrosoftTokens fresh = tokens(postForm("/token", f), "refresh");
		old.discard();
		return fresh;
	}

	private MicrosoftTokens tokens(final HttpTransport.Response r, final String what) throws AuthException {
		Json j = Json.parse(r.body(), what);
		if (r.status() != 200) {
			throw oauthError(r, j);
		}
		Instant now = this.clock.instant();
		long expiresIn = j.number(3600, "expires_in");
		SecretToken access = new SecretToken("microsoft-access", j.requireString(what, "access_token"), now.plusSeconds(Math.max(60, expiresIn - 60)));
		String refresh = j.string("refresh_token");
		return new MicrosoftTokens(access, refresh == null ? null : new SecretToken("microsoft-refresh", refresh, null));
	}

	/** Nur der standardisierte Fehlercode und die numerischen AADSTS-Codes gelangen in die Meldung. */
	static AuthException oauthError(final HttpTransport.Response r, final Json j) {
		String err = j.string("error");
		Object codes = j.at("error_codes");
		String detail = "HTTP " + r.status() + (err == null ? "" : " error=" + LoopbackReceiver.safeCode(err))
			+ (codes == null ? "" : " AADSTS" + codes.toString().replaceAll("[^0-9,]", ""));
		if ("invalid_grant".equals(err)) {
			return new AuthException(AuthError.INVALID_GRANT, detail);
		}
		if ("access_denied".equals(err) || "authorization_declined".equals(err)) {
			return new AuthException(AuthError.USER_CANCELLED, detail);
		}
		if (r.status() == 429) {
			return new AuthException(AuthError.RATE_LIMITED, detail);
		}
		if (r.status() >= 500) {
			return new AuthException(AuthError.SERVICE_UNAVAILABLE, detail);
		}
		return new AuthException(AuthError.OAUTH_ERROR, detail);
	}

	private HttpTransport.Response postForm(final String path, final Map<String, String> fields) throws AuthException {
		Map<String, String> h = new LinkedHashMap<>();
		h.put("Content-Type", "application/x-www-form-urlencoded");
		h.put("Accept", "application/json");
		return this.http.send(new HttpTransport.Request("POST", URI.create(AUTHORITY + path), h, form(fields).getBytes(StandardCharsets.UTF_8)));
	}

	static String form(final Map<String, String> fields) {
		StringBuilder b = new StringBuilder();
		for (Map.Entry<String, String> e : fields.entrySet()) {
			if (b.length() > 0) {
				b.append('&');
			}
			b.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}
		return b.toString();
	}

	private static void sleep(final long seconds, final AuthCancellation cancel) throws AuthException {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (System.nanoTime() < end) {
			cancel.throwIfCancelled();
			try {
				Thread.sleep(Math.min(200, Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()))));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AuthException(AuthError.USER_CANCELLED, "interrupted");
			}
		}
		cancel.throwIfCancelled();
	}
}
