package dev.mcresearch.protocolclient.auth.http;

import dev.mcresearch.protocolclient.auth.AuthError;
import dev.mcresearch.protocolclient.auth.AuthException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * M13: Netzwerkregel der Authentifizierung (docs/50 §6). Jede HTTP-Anfrage wird vor dem Senden geprüft:
 * <ul>
 * <li>{@link #official}: ausschließlich HTTPS (Port 443) zu einer festen Liste offizieller Hosts; nur nutzbar, wenn
 * externe Anmeldeverbindungen ausdrücklich freigegeben sind (Schalter {@code --allow-external-auth}).
 * Vom Discovery-Dienst gelieferte Adressen unterliegen derselben Liste.</li>
 * <li>{@link #localSimulation}: ausschließlich {@code http://127.0.0.1:<port>} (lokale Testdienste); keine externe Adresse.</li>
 * <li>{@link #denyAll}: Standard; jede Anfrage wird abgewiesen.</li>
 * </ul>
 */
public final class EndpointPolicy {
	/** Offizielle Endpunkte von Microsoft, Xbox Live und Minecraft Services (docs/50 §6). */
	public static final Set<String> OFFICIAL_HOSTS = Set.of(
		"login.microsoftonline.com",
		"user.auth.xboxlive.com",
		"xsts.auth.xboxlive.com",
		"api.minecraftservices.com",
		"discovery.minecraftservices.com",
		"sessionserver.mojang.com");

	private final String name;
	private final Set<String> httpsHosts;
	private final boolean loopbackHttp;

	private EndpointPolicy(final String name, final Set<String> httpsHosts, final boolean loopbackHttp) {
		this.name = name;
		this.httpsHosts = httpsHosts;
		this.loopbackHttp = loopbackHttp;
	}

	public static EndpointPolicy denyAll() {
		return new EndpointPolicy("deny-all", Set.of(), false);
	}

	public static EndpointPolicy official() {
		return new EndpointPolicy("official", OFFICIAL_HOSTS, false);
	}

	public static EndpointPolicy localSimulation() {
		return new EndpointPolicy("local-simulation", Set.of(), true);
	}

	public String name() {
		return this.name;
	}

	public boolean isOfficial() {
		return !this.httpsHosts.isEmpty();
	}

	/** Prüft eine Zieladresse; wirft {@link AuthException} ohne Netzwerkzugriff (keine DNS-Abfrage). */
	public void check(final URI uri) throws AuthException {
		if (uri == null || uri.getHost() == null) {
			throw new AuthException(AuthError.ENDPOINT_NOT_ALLOWED, "invalid uri");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		if (uri.getUserInfo() != null) {
			throw new AuthException(AuthError.ENDPOINT_NOT_ALLOWED, "user info in uri");
		}
		if (this.loopbackHttp) {
			if (scheme.equals("http") && host.equals("127.0.0.1") && uri.getPort() > 0) {
				return;
			}
			throw new AuthException(AuthError.ENDPOINT_NOT_ALLOWED, "local simulation allows only http://127.0.0.1:<port>, got host " + host);
		}
		if (this.httpsHosts.isEmpty()) {
			throw new AuthException(AuthError.EXTERNAL_NOT_ALLOWED, "host " + host);
		}
		if (!scheme.equals("https")) {
			throw new AuthException(AuthError.ENDPOINT_NOT_ALLOWED, "only https is allowed, got " + scheme + "://" + host);
		}
		if (uri.getPort() != -1 && uri.getPort() != 443) {
			throw new AuthException(AuthError.ENDPOINT_NOT_ALLOWED, "port " + uri.getPort());
		}
		if (!this.httpsHosts.contains(host)) {
			throw new AuthException(AuthError.ENDPOINT_NOT_ALLOWED, "host " + host + " is not on the approved list");
		}
	}

}
