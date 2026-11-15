package dev.mcresearch.protocolclient.auth;

import dev.mcresearch.protocolclient.auth.http.EndpointPolicy;
import dev.mcresearch.protocolclient.auth.http.HttpTransport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * M13: Attrappe des HTTP-Transports für Unit-Tests der Anmeldekette. Keine Netzwerkverbindung: Antworten werden pro
 * Host+Pfad aus einer Warteschlange geliefert. Die Policy wird wie beim echten Transport vor jeder Anfrage geprüft.
 * Alle Tokens in den Antworten sind Testwerte mit dem Präfix {@code SECRET-TEST-}.
 */
final class FakeHttp implements HttpTransport {
	final List<Request> requests = new ArrayList<>();
	private final Map<String, Deque<Function<Request, Response>>> routes = new HashMap<>();
	private final EndpointPolicy policy;

	FakeHttp(final EndpointPolicy policy) {
		this.policy = policy;
	}

	FakeHttp on(final String hostAndPath, final int status, final String body) {
		return on(hostAndPath, r -> new Response(status, Map.of(), body));
	}

	/** Ersetzt alle Antworten eines Pfads durch eine feste Antwort (Fehlerfälle). */
	FakeHttp routesReplace(final String hostAndPath, final int status, final String body) {
		this.routes.remove(hostAndPath);
		return on(hostAndPath, status, body);
	}

	FakeHttp on(final String hostAndPath, final Function<Request, Response> f) {
		this.routes.computeIfAbsent(hostAndPath, k -> new ArrayDeque<>()).add(f);
		return this;
	}

	@Override
	public Response send(final Request request) throws AuthException {
		this.policy.check(request.uri());
		this.requests.add(request);
		String key = request.uri().getHost() + request.uri().getPath();
		Deque<Function<Request, Response>> q = this.routes.get(key);
		if (q == null || q.isEmpty()) {
			throw new AssertionError("unexpected request " + key);
		}
		return (q.size() > 1 ? q.poll() : q.peek()).apply(request);
	}

	@Override
	public EndpointPolicy policy() {
		return this.policy;
	}

	static String body(final Request r) {
		return r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8);
	}

	/** Standard-Antworten einer erfolgreichen Kette (Testdaten). */
	static FakeHttp happyChain() {
		FakeHttp h = new FakeHttp(EndpointPolicy.official());
		h.on("login.microsoftonline.com/consumers/oauth2/v2.0/token", 200,
			"{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"SECRET-TEST-MSA\",\"refresh_token\":\"SECRET-TEST-REFRESH\"}");
		h.on("user.auth.xboxlive.com/user/authenticate", 200,
			"{\"Token\":\"SECRET-TEST-XBL\",\"NotAfter\":\"2099-01-01T00:00:00.0000000Z\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"1234567890\"}]}}");
		h.on("xsts.auth.xboxlive.com/xsts/authorize", 200, "{\"Token\":\"SECRET-TEST-XSTS\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"1234567890\"}]}}");
		h.on("api.minecraftservices.com/authentication/login_with_xbox", 200, "{\"username\":\"x\",\"access_token\":\"SECRET-TEST-MC\",\"expires_in\":86400}");
		h.on("api.minecraftservices.com/entitlements/mcstore", 200, "{\"items\":[{\"name\":\"product_minecraft\"},{\"name\":\"game_minecraft\"}]}");
		h.on("api.minecraftservices.com/minecraft/profile", 200, "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"TestPlayer_13\"}");
		h.on("discovery.minecraftservices.com/minecraft/client", 200,
			"{\"environment\":\"prod\",\"product\":\"minecraft\",\"discovery\":{\"session\":{\"endpoints\":{\"join\":{\"uri\":\"https://sessionserver.mojang.com/session/minecraft/join\"}}},"
				+ "\"player\":{\"endpoints\":{\"getAttributes\":{\"uri\":\"https://api.minecraftservices.com/player/attributes\"}}}}}");
		h.on("api.minecraftservices.com/player/attributes", 200, "{\"privileges\":{\"multiplayerServer\":{\"enabled\":true},\"onlineChat\":{\"enabled\":true}}}");
		h.on("sessionserver.mojang.com/session/minecraft/join", 204, "");
		return h;
	}
}
