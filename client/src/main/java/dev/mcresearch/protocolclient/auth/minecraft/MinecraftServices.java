package dev.mcresearch.protocolclient.auth.minecraft;

import dev.mcresearch.protocolclient.auth.AuthError;
import dev.mcresearch.protocolclient.auth.AuthException;
import dev.mcresearch.protocolclient.auth.SecretToken;
import dev.mcresearch.protocolclient.auth.http.HttpTransport;
import dev.mcresearch.protocolclient.auth.http.Json;
import dev.mcresearch.protocolclient.auth.xbox.XboxLive;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M13: Minecraft Services (docs/50 §5): Minecraft-Access-Token aus XSTS, Besitzprüfung, Profil, Mehrspieler-Berechtigung.
 * <ul>
 * <li>{@code POST /authentication/login_with_xbox} mit {@code "XBL3.0 x=<uhs>;<xsts>"}; HTTP 403 „Invalid app
 * registration“ = Client-ID nicht von Mojang freigegeben ({@link AuthError#APP_NOT_APPROVED});</li>
 * <li>{@code GET /entitlements/mcstore}: Berechtigungen ({@code product_minecraft}, {@code game_minecraft});</li>
 * <li>{@code GET /minecraft/profile}: offizielle UUID und Name; HTTP 404 = kein Java-Profil;</li>
 * <li>{@code player/getAttributes} (über Discovery, wie Vanilla {@code UserApiService#fetchProperties}): Privileg
 * {@code multiplayerServer} und Sperren ({@code banStatus.bannedScopes.MULTIPLAYER}).</li>
 * </ul>
 */
public final class MinecraftServices {
	public static final String API = "https://api.minecraftservices.com";
	private final HttpTransport http;
	private final Clock clock;

	public MinecraftServices(final HttpTransport http, final Clock clock) {
		this.http = http;
		this.clock = clock;
	}

	public SecretToken loginWithXbox(final XboxLive.XboxToken xsts) throws AuthException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("identityToken", "XBL3.0 x=" + xsts.userHash() + ";" + xsts.token().reveal());
		HttpTransport.Response r = send("POST", URI.create(API + "/authentication/login_with_xbox"), null, Json.write(body));
		if (r.status() == 403 && r.body().toLowerCase(java.util.Locale.ROOT).contains("invalid app registration")) {
			throw new AuthException(AuthError.APP_NOT_APPROVED, "login_with_xbox HTTP 403");
		}
		check(r, "login_with_xbox");
		Json j = Json.parse(r.body(), "login_with_xbox");
		long expires = j.number(86400, "expires_in");
		return new SecretToken("minecraft-access", j.requireString("login_with_xbox", "access_token"),
			this.clock.instant().plusSeconds(Math.max(60, expires - 60)));
	}

	/** Namen der Berechtigungen; leer = keine. */
	public List<String> entitlements(final SecretToken mc) throws AuthException {
		HttpTransport.Response r = send("GET", URI.create(API + "/entitlements/mcstore"), mc, null);
		check(r, "entitlements");
		Json j = Json.parse(r.body(), "entitlements");
		List<String> names = new java.util.ArrayList<>();
		if (j.at("items") instanceof List<?> items) {
			for (int i = 0; i < items.size(); i++) {
				String n = j.string("items", i, "name");
				if (n != null) {
					names.add(n);
				}
			}
		}
		return names;
	}

	public static boolean ownsJavaEdition(final List<String> entitlements) {
		return entitlements.contains("game_minecraft") || entitlements.contains("product_minecraft");
	}

	public record Profile(UUID id, String name) {
	}

	public Profile profile(final SecretToken mc) throws AuthException {
		HttpTransport.Response r = send("GET", URI.create(API + "/minecraft/profile"), mc, null);
		if (r.status() == 404) {
			throw new AuthException(AuthError.NO_MINECRAFT_PROFILE, "profile HTTP 404");
		}
		check(r, "profile");
		Json j = Json.parse(r.body(), "profile");
		String id = j.requireString("profile", "id");
		String name = j.requireString("profile", "name");
		if (!name.matches("[A-Za-z0-9_]{1,16}")) {
			throw new AuthException(AuthError.PROTOCOL, "profile name has unexpected format");
		}
		return new Profile(undashed(id), name);
	}

	public record Privileges(boolean multiplayerServer, boolean multiplayerBanned) {
	}

	public Privileges privileges(final SecretToken mc, final ServiceDiscovery discovery) throws AuthException {
		HttpTransport.Response r = send("GET", discovery.endpoint("player", "getAttributes"), mc, null);
		check(r, "attributes");
		Json j = Json.parse(r.body(), "attributes");
		boolean mp = j.bool(false, "privileges", "multiplayerServer", "enabled");
		boolean banned = j.at("banStatus", "bannedScopes", "MULTIPLAYER") != null;
		return new Privileges(mp, banned);
	}

	static UUID undashed(final String s) throws AuthException {
		String h = s.replace("-", "");
		if (!h.matches("[0-9a-fA-F]{32}")) {
			throw new AuthException(AuthError.PROTOCOL, "profile id has unexpected format");
		}
		return new UUID(Long.parseUnsignedLong(h.substring(0, 16), 16), Long.parseUnsignedLong(h.substring(16), 16));
	}

	private HttpTransport.Response send(final String method, final URI uri, final SecretToken bearer, final String json) throws AuthException {
		Map<String, String> h = new LinkedHashMap<>();
		h.put("Accept", "application/json");
		if (json != null) {
			h.put("Content-Type", "application/json");
		}
		if (bearer != null) {
			if (bearer.expired(this.clock.instant())) {
				throw new AuthException(AuthError.MINECRAFT_TOKEN_EXPIRED, "");
			}
			h.put("Authorization", "Bearer " + bearer.reveal());
		}
		return this.http.send(new HttpTransport.Request(method, uri, h, json == null ? null : json.getBytes(StandardCharsets.UTF_8)));
	}

	private static void check(final HttpTransport.Response r, final String what) throws AuthException {
		int s = r.status();
		if (s == 200) {
			return;
		}
		if (s == 401) {
			throw new AuthException(AuthError.MINECRAFT_TOKEN_EXPIRED, what + " HTTP 401");
		}
		if (s == 429) {
			throw new AuthException(AuthError.RATE_LIMITED, what + retryAfter(r));
		}
		if (s >= 500) {
			throw new AuthException(AuthError.SERVICE_UNAVAILABLE, what + " HTTP " + s);
		}
		throw new AuthException(AuthError.PROTOCOL, what + " HTTP " + s);
	}

	static String retryAfter(final HttpTransport.Response r) {
		String ra = r.header("Retry-After");
		return ra != null && ra.matches("\\d{1,6}") ? " retry after " + ra + " s" : "";
	}

	/** Nur für Diagnose: Zeitpunkt jetzt. */
	Instant now() {
		return this.clock.instant();
	}
}
