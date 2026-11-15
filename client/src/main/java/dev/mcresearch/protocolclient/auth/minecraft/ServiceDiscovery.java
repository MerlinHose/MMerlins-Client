package dev.mcresearch.protocolclient.auth.minecraft;

import dev.mcresearch.protocolclient.auth.AuthError;
import dev.mcresearch.protocolclient.auth.AuthException;
import dev.mcresearch.protocolclient.auth.http.HttpTransport;
import dev.mcresearch.protocolclient.auth.http.Json;
import java.net.URI;
import java.util.Map;

/**
 * M13: Endpunktverzeichnis von Minecraft Services, wie es Vanilla 26.3 (authlib 10.0.77,
 * {@code MinecraftServicesDiscoveryService}) nutzt: {@code GET https://discovery.minecraftservices.com/minecraft/client}
 * liefert pro Dienst ({@code session}, {@code player}, …) Endpunkte wie {@code join} und {@code getAttributes}
 * (docs/51 §6). Jede gelieferte Adresse wird vor der Nutzung gegen die {@code EndpointPolicy} geprüft.
 */
public final class ServiceDiscovery {
	public static final URI PRODUCTION = URI.create("https://discovery.minecraftservices.com/minecraft/client");
	private final HttpTransport http;
	private final URI discoveryUri;
	private volatile Json document;

	public ServiceDiscovery(final HttpTransport http, final URI discoveryUri) {
		this.http = http;
		this.discoveryUri = discoveryUri;
	}

	public URI discoveryUri() {
		return this.discoveryUri;
	}

	/** Adresse eines Endpunkts, z. B. {@code ("session", "join")}. */
	public URI endpoint(final String service, final String key) throws AuthException {
		Json d = load();
		String uri = d.string("discovery", service, "endpoints", key, "uri");
		if (uri == null) {
			throw new AuthException(AuthError.SERVICE_UNAVAILABLE, "discovery has no endpoint " + service + "/" + key);
		}
		URI u;
		try {
			u = URI.create(uri);
		} catch (IllegalArgumentException e) {
			throw new AuthException(AuthError.PROTOCOL, "discovery endpoint " + service + "/" + key + " is not a URI");
		}
		this.http.policy().check(u);
		return u;
	}

	private Json load() throws AuthException {
		Json d = this.document;
		if (d != null) {
			return d;
		}
		HttpTransport.Response r = this.http.send(new HttpTransport.Request("GET", this.discoveryUri, Map.of("Accept", "application/json"), null));
		if (r.status() >= 500 || r.status() == 429) {
			throw new AuthException(r.status() == 429 ? AuthError.RATE_LIMITED : AuthError.SERVICE_UNAVAILABLE, "discovery HTTP " + r.status());
		}
		if (r.status() != 200) {
			throw new AuthException(AuthError.PROTOCOL, "discovery HTTP " + r.status());
		}
		d = Json.parse(r.body(), "discovery");
		if ("offline".equals(d.string("environment"))) {
			throw new AuthException(AuthError.SERVICE_UNAVAILABLE, "discovery reports offline");
		}
		this.document = d;
		return d;
	}
}
