package dev.mcresearch.protocolclient.auth.http;

import dev.mcresearch.protocolclient.auth.AuthException;
import java.net.URI;
import java.util.Map;

/**
 * M13: minimale HTTP-Abstraktion der Authentifizierung. Die echte Implementierung ({@link JdkHttpTransport}) nutzt
 * {@code java.net.http.HttpClient} mit Standard-TLS-Prüfung; Unit-Tests ersetzen sie durch eine Attrappe ohne Netzwerk.
 */
public interface HttpTransport {
	record Request(String method, URI uri, Map<String, String> headers, byte[] body) {
		/** Keine Header-Werte und keinen Body ausgeben: sie können Tokens enthalten. */
		@Override
		public String toString() {
			return this.method + " " + this.uri.getScheme() + "://" + this.uri.getHost() + this.uri.getPath();
		}
	}

	record Response(int status, Map<String, String> headers, String body) {
		@Override
		public String toString() {
			return "HTTP " + this.status + " (" + this.body.length() + " chars)";
		}

		public String header(final String name) {
			for (Map.Entry<String, String> e : this.headers.entrySet()) {
				if (e.getKey().equalsIgnoreCase(name)) {
					return e.getValue();
				}
			}
			return null;
		}
	}

	/** Sendet eine Anfrage; {@link AuthException} bei Policy-Verstoß, Zeitüberschreitung oder Verbindungsfehler. */
	Response send(Request request) throws AuthException;

	/** Policy, gegen die vor jeder Anfrage geprüft wird. */
	EndpointPolicy policy();
}
