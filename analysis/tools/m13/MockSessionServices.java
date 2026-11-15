import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M13 Phase G (docs/53 §4): LOKALE SIMULATION der Minecraft-Services für den unveränderten Vanilla-26.3-Server.
 * <p>
 * Der Vanilla-Server (authlib 10.0.77) liest seine Dienstadressen aus dem Discovery-Dokument, dessen Adresse die
 * Systemeigenschaft {@code minecraft.api.discovery.host} festlegt. Dieses Werkzeug beantwortet:
 * <ul>
 * <li>{@code GET /minecraft/client}: Discovery mit {@code session/join} und {@code session/verify} auf 127.0.0.1;</li>
 * <li>{@code POST /session/minecraft/join}: Session-Nachweis des Clients (nur das Testtoken wird akzeptiert);</li>
 * <li>{@code GET /session/minecraft/hasJoined}: Prüfung des Servers (Name + Hash müssen zu einem join passen);</li>
 * <li>{@code GET /control?mode=…}: Fehlerfälle (accept, reject-join, down-join, unverified, down-verify).</li>
 * </ul>
 * Es gibt keinen Microsoft-Login und keine echten Tokens. Ergebnisse gelten ausschließlich als Prüfung des
 * Protokolls (RSA, AES/CFB8, Session-Hash, Zustandsautomat) gegen den Vanilla-Server, NICHT als offizielle Anmeldung.
 * <p>
 * Aufruf: {@code java -Xmx64m MockSessionServices.java <port> <log-datei>}; bindet nur an 127.0.0.1.
 */
public final class MockSessionServices {
	static final String TEST_TOKEN = "TEST-ONLY-NOT-A-REAL-TOKEN-m13-simulation";
	static final Map<String, String> PROFILES = Map.of("M13Sim", "0000000000004000800000000000d013");
	static final Map<String, String> JOINS = new ConcurrentHashMap<>(); // serverId -> undashed profile id
	static volatile String mode = "accept";
	static PrintStream log;

	public static void main(final String[] args) throws Exception {
		int port = Integer.parseInt(args[0]);
		log = new PrintStream(new java.io.FileOutputStream(args[1], true), true, StandardCharsets.UTF_8);
		HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 16);
		String base = "http://127.0.0.1:" + port;
		s.createContext("/minecraft/client", ex -> respond(ex, 200, "{\"environment\":\"local-simulation\",\"product\":\"minecraft\",\"discovery\":{"
			+ "\"authentication\":{\"endpoints\":{}},"
			+ "\"session\":{\"endpoints\":{\"join\":{\"uri\":\"" + base + "/session/minecraft/join\"},\"verify\":{\"uri\":\"" + base
			+ "/session/minecraft/hasJoined\"}}},"
			+ "\"player\":{\"endpoints\":{}},\"profiles\":{\"endpoints\":{}},\"telemetry\":{\"endpoints\":{}}}}", "discovery"));
		s.createContext("/session/minecraft/join", MockSessionServices::join);
		s.createContext("/session/minecraft/hasJoined", MockSessionServices::hasJoined);
		s.createContext("/control", ex -> {
			mode = query(ex.getRequestURI().getRawQuery()).getOrDefault("mode", "accept");
			respond(ex, 200, "{\"mode\":\"" + mode + "\"}", "control mode=" + mode);
			if (mode.equals("exit")) {
				s.stop(0);
				System.exit(0);
			}
		});
		s.createContext("/", ex -> respond(ex, 404, "{\"error\":\"NOT_FOUND\"}", "404 " + ex.getRequestURI().getPath()));
		s.start();
		log("LOCAL SIMULATION of Minecraft services on " + base + " (test data only, no official authentication)");
	}

	static void join(final HttpExchange ex) throws IOException {
		String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		String token = field(body, "accessToken");
		String profile = field(body, "selectedProfile");
		String serverId = field(body, "serverId");
		String tokenInfo = TEST_TOKEN.equals(token) ? "test-token" : "OTHER-token(len=" + (token == null ? 0 : token.length()) + ")";
		if (mode.equals("down-join")) {
			respond(ex, 503, "", "join -> 503 (mode down-join)");
			return;
		}
		if (mode.equals("reject-join") || !TEST_TOKEN.equals(token) || profile == null || !PROFILES.containsValue(profile)) {
			respond(ex, 403, "{\"error\":\"ForbiddenOperationException\",\"errorMessage\":\"Invalid token\"}",
				"join -> 403 ForbiddenOperationException (" + tokenInfo + ", profile=" + profile + ", mode=" + mode + ")");
			return;
		}
		if (serverId == null || !serverId.matches("-?[0-9a-f]{1,40}")) {
			respond(ex, 400, "{\"error\":\"IllegalArgumentException\"}", "join -> 400 bad serverId");
			return;
		}
		JOINS.put(serverId, profile);
		ex.sendResponseHeaders(204, -1);
		ex.close();
		log("join -> 204 profile=" + profile + " serverId=" + serverId + " (" + tokenInfo + ")");
	}

	static void hasJoined(final HttpExchange ex) throws IOException {
		Map<String, String> q = query(ex.getRequestURI().getRawQuery());
		String name = q.get("username");
		String serverId = q.get("serverId");
		if (mode.equals("down-verify")) {
			respond(ex, 503, "", "hasJoined -> 503 (mode down-verify)");
			return;
		}
		String profile = serverId == null ? null : JOINS.get(serverId);
		boolean ok = !mode.equals("unverified") && profile != null && profile.equals(PROFILES.get(name));
		if (!ok) {
			ex.sendResponseHeaders(204, -1);
			ex.close();
			log("hasJoined username=" + name + " serverId=" + serverId + " -> 204 (not joined, mode=" + mode + ")");
			return;
		}
		JOINS.remove(serverId); // ein Nachweis gilt für genau eine Verbindung
		respond(ex, 200, "{\"id\":\"" + profile + "\",\"name\":\"" + name + "\",\"properties\":[]}",
			"hasJoined username=" + name + " serverId=" + serverId + " -> 200 id=" + profile);
	}

	static String field(final String json, final String name) {
		Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
		return m.find() ? m.group(1) : null;
	}

	static Map<String, String> query(final String raw) {
		Map<String, String> m = new HashMap<>();
		if (raw != null) {
			for (String p : raw.split("&")) {
				int eq = p.indexOf('=');
				if (eq > 0) {
					m.put(URLDecoder.decode(p.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
				}
			}
		}
		return m;
	}

	static void respond(final HttpExchange ex, final int status, final String body, final String what) throws IOException {
		byte[] b = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
		if (b.length > 0) {
			ex.getResponseBody().write(b);
		}
		ex.close();
		log(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + ": " + what);
	}

	static synchronized void log(final String s) {
		log.println(LocalTime.now().withNano(0) + " " + s);
	}
}
