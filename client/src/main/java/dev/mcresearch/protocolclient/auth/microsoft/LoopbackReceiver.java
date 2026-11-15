package dev.mcresearch.protocolclient.auth.microsoft;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mcresearch.protocolclient.auth.AuthError;
import dev.mcresearch.protocolclient.auth.AuthException;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * M13: lokaler Empfänger der OAuth-Weiterleitung (RFC 8252 §7.3, docs/52 §4). Sicherheitsregeln:
 * <ul>
 * <li>bindet ausschließlich an Loopback (127.0.0.1, zusätzlich [::1] auf demselben Port, falls verfügbar), Port vom
 * Betriebssystem gewählt; Redirect-URI {@code http://localhost:<port>} (Entra ignoriert den Port bei localhost);</li>
 * <li>akzeptiert nur {@code GET /} mit Host-Header localhost/127.0.0.1/[::1] und passendem Port (Schutz vor DNS-Rebinding);</li>
 * <li>{@code state} wird in konstanter Zeit verglichen; ein falscher state bricht die Anmeldung ab;</li>
 * <li>nur die erste gültige Antwort zählt; danach wird der Empfänger geschlossen;</li>
 * <li>die Browserseite enthält keine Codes oder Tokens; nichts davon wird protokolliert.</li>
 * </ul>
 */
public final class LoopbackReceiver implements AutoCloseable {
	/** Ergebnis der Weiterleitung: Autorisierungscode (geheim, nur zum Einlösen). */
	public record Callback(String code) {
		@Override
		public String toString() {
			return "Callback[code=redacted]";
		}
	}

	private final List<HttpServer> servers = new ArrayList<>();
	private final String expectedState;
	private final int port;
	private final CompletableFuture<Callback> result = new CompletableFuture<>();
	private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

	public LoopbackReceiver(final String expectedState) throws IOException {
		this.expectedState = expectedState;
		HttpServer v4 = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 4);
		this.port = v4.getAddress().getPort();
		this.servers.add(v4);
		try {
			byte[] v6addr = new byte[16];
			v6addr[15] = 1;
			HttpServer v6 = HttpServer.create(new InetSocketAddress(Inet6Address.getByAddress(null, v6addr, 0), this.port), 4);
			this.servers.add(v6);
		} catch (IOException | RuntimeException e) {
			// IPv6-Loopback nicht verfügbar oder Port belegt: nur IPv4 (Browser fallen auf 127.0.0.1 zurück)
		}
		for (HttpServer s : this.servers) {
			s.createContext("/", this::handle);
			s.start();
		}
	}

	public int port() {
		return this.port;
	}

	public String redirectUri() {
		return "http://localhost:" + this.port;
	}

	public CompletableFuture<Callback> result() {
		return this.result;
	}

	private void handle(final HttpExchange ex) throws IOException {
		try (ex) {
			if (!"GET".equals(ex.getRequestMethod()) || !"/".equals(ex.getRequestURI().getRawPath())) {
				respond(ex, 404, "Nicht gefunden.");
				return;
			}
			String host = ex.getRequestHeaders().getFirst("Host");
			if (!validHost(host)) {
				respond(ex, 400, "Ungültige Anfrage.");
				return;
			}
			if (this.result.isDone()) {
				respond(ex, 409, "Die Anmeldung wurde bereits abgeschlossen. Dieses Fenster kann geschlossen werden.");
				return;
			}
			Map<String, String> q = query(ex.getRequestURI().getRawQuery());
			String state = q.get("state");
			if (state == null || !constantTimeEquals(state, this.expectedState)) {
				respond(ex, 400, "Ungültige Antwort (state). Die Anmeldung wurde abgebrochen.");
				this.result.completeExceptionally(new AuthException(AuthError.STATE_MISMATCH, ""));
				return;
			}
			String error = q.get("error");
			if (error != null) {
				respond(ex, 200, "Anmeldung nicht erfolgreich. Dieses Fenster kann geschlossen werden.");
				AuthError kind = "access_denied".equals(error) ? AuthError.USER_CANCELLED : AuthError.OAUTH_ERROR;
				this.result.completeExceptionally(new AuthException(kind, "error=" + safeCode(error)));
				return;
			}
			String code = q.get("code");
			if (code == null || code.isEmpty()) {
				respond(ex, 400, "Ungültige Antwort (code fehlt). Die Anmeldung wurde abgebrochen.");
				this.result.completeExceptionally(new AuthException(AuthError.OAUTH_ERROR, "missing code"));
				return;
			}
			respond(ex, 200, "Anmeldung bei Microsoft abgeschlossen. Dieses Fenster kann geschlossen werden.");
			this.result.complete(new Callback(code));
		}
	}

	boolean validHost(final String host) {
		if (host == null) {
			return false;
		}
		String h = host.toLowerCase(java.util.Locale.ROOT);
		return h.equals("localhost:" + this.port) || h.equals("127.0.0.1:" + this.port) || h.equals("[::1]:" + this.port);
	}

	static String safeCode(final String s) {
		return s.matches("[a-z_]{1,64}") ? s : "?";
	}

	static boolean constantTimeEquals(final String a, final String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	static Map<String, String> query(final String raw) {
		Map<String, String> m = new HashMap<>();
		if (raw == null) {
			return m;
		}
		for (String part : raw.split("&")) {
			int eq = part.indexOf('=');
			String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
			String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
			m.putIfAbsent(k, v); // doppelte Parameter: nur der erste zählt
		}
		return m;
	}

	private static void respond(final HttpExchange ex, final int status, final String text) throws IOException {
		byte[] body = ("<!doctype html><meta charset=utf-8><title>Minecraft Research</title><p>" + text + "</p>").getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
		ex.sendResponseHeaders(status, body.length);
		ex.getResponseBody().write(body);
	}

	@Override
	public void close() {
		if (!this.closed.compareAndSet(false, true)) {
			return;
		}
		for (HttpServer s : this.servers) {
			s.stop(0);
		}
		this.result.completeExceptionally(new AuthException(AuthError.USER_CANCELLED, "receiver closed"));
	}
}
