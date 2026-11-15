package dev.mcresearch.protocolclient.auth.minecraft;

import dev.mcresearch.protocolclient.auth.AuthError;
import dev.mcresearch.protocolclient.auth.AuthException;
import dev.mcresearch.protocolclient.auth.LoginIdentity;
import dev.mcresearch.protocolclient.auth.SecretToken;
import dev.mcresearch.protocolclient.auth.http.HttpTransport;
import dev.mcresearch.protocolclient.auth.http.Json;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * M13: Online-Identität mit Session-Nachweis (docs/51 §6), Verhalten wie Vanilla {@code MinecraftServicesSessionService#joinServer}:
 * {@code POST <session/join>} mit {@code {"accessToken", "selectedProfile" (UUID ohne Bindestriche), "serverId" (Session-Hash)}},
 * Erfolg = HTTP 2xx ohne Inhalt. Fehlerzuordnung wie authlib {@code MinecraftClientHttpException#toAuthenticationException}.
 * <p>
 * {@code kind} unterscheidet die offizielle Sitzung ({@code microsoft}) von der lokalen Simulation ({@code simulation}),
 * die ausschließlich eindeutig gekennzeichnete Testdaten und {@code http://127.0.0.1} verwendet.
 */
public final class OnlineIdentity implements LoginIdentity {
	/** Kennzeichnung der Testdaten der lokalen Simulation (docs/53 §3). */
	public static final String SIMULATION_TOKEN = "TEST-ONLY-NOT-A-REAL-TOKEN-m13-simulation";

	private final String name;
	private final UUID id;
	private final SecretToken accessToken;
	private final ServiceDiscovery discovery;
	private final HttpTransport http;
	private final Clock clock;
	private final String kind;

	public OnlineIdentity(final String name, final UUID id, final SecretToken accessToken, final ServiceDiscovery discovery, final HttpTransport http,
		final Clock clock, final String kind) {
		this.name = name;
		this.id = id;
		this.accessToken = accessToken;
		this.discovery = discovery;
		this.http = http;
		this.clock = clock;
		this.kind = kind;
	}

	/** Lokale Simulation: Testname/-UUID, Testtoken, Discovery unter {@code http://127.0.0.1:<port>} (keine externe Verbindung). */
	public static OnlineIdentity simulation(final String name, final UUID id, final java.net.URI discoveryUri, final HttpTransport localHttp) {
		if (localHttp.policy().isOfficial()) {
			throw new IllegalArgumentException("simulation must use the local-simulation endpoint policy");
		}
		return new OnlineIdentity(name, id, new SecretToken("simulation", SIMULATION_TOKEN, null), new ServiceDiscovery(localHttp, discoveryUri), localHttp,
			Clock.systemUTC(), "simulation");
	}

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public UUID profileId() {
		return this.id;
	}

	@Override
	public String kind() {
		return this.kind;
	}

	@Override
	public boolean canAuthenticate() {
		return !this.accessToken.discarded();
	}

	public boolean expired() {
		return this.accessToken.expired(this.clock.instant());
	}

	/** Abmelden: Token verwerfen; weitere join-Versuche schlagen kontrolliert fehl. */
	@Override
	public void logout() {
		this.accessToken.discard();
	}

	@Override
	public void joinServer(final String serverHash) throws AuthException {
		if (this.accessToken.discarded()) {
			throw new AuthException(AuthError.INVALID_SESSION, "logged out");
		}
		if (expired()) {
			throw new AuthException(AuthError.MINECRAFT_TOKEN_EXPIRED, "");
		}
		if (!serverHash.matches("-?[0-9a-f]{1,40}")) {
			throw new IllegalArgumentException("server hash has unexpected format");
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("accessToken", this.accessToken.reveal());
		body.put("selectedProfile", this.id.toString().replace("-", ""));
		body.put("serverId", serverHash);
		Map<String, String> h = new LinkedHashMap<>();
		h.put("Content-Type", "application/json; charset=utf-8");
		h.put("Accept", "application/json");
		HttpTransport.Response r = this.http.send(new HttpTransport.Request("POST", this.discovery.endpoint("session", "join"), h,
			Json.write(body).getBytes(StandardCharsets.UTF_8)));
		if (r.status() >= 200 && r.status() < 300) {
			return;
		}
		throw joinError(r);
	}

	/** Zuordnung wie authlib: error-Feld zuerst, dann Status (401 → ungültige Sitzung, ≥500 → nicht erreichbar). */
	static AuthException joinError(final HttpTransport.Response r) {
		String error = null;
		if (!r.body().isBlank()) {
			try {
				error = Json.parse(r.body(), "join").string("error");
			} catch (AuthException e) {
				error = null;
			}
		}
		String detail = "join HTTP " + r.status() + (error == null ? "" : " " + error.replaceAll("[^A-Za-z0-9_.]", ""));
		if (error != null) {
			if (error.equalsIgnoreCase("ForbiddenOperationException")) {
				return new AuthException(AuthError.INVALID_SESSION, detail);
			}
			if (error.equalsIgnoreCase("multiplayer.access.banned")) {
				return new AuthException(AuthError.USER_BANNED, detail);
			}
			if (error.equalsIgnoreCase("InsufficientPrivilegesException")) {
				return new AuthException(AuthError.INSUFFICIENT_PRIVILEGES, detail);
			}
		}
		if (r.status() == 401) {
			return new AuthException(AuthError.INVALID_SESSION, detail);
		}
		if (r.status() == 429) {
			return new AuthException(AuthError.RATE_LIMITED, detail + MinecraftServices.retryAfter(r));
		}
		if (r.status() >= 500) {
			return new AuthException(AuthError.SERVICE_UNAVAILABLE, detail);
		}
		return new AuthException(AuthError.PROTOCOL, detail);
	}

	@Override
	public String toString() {
		return "OnlineIdentity[" + this.kind + ", " + this.name + ", " + this.id + "]";
	}
}
