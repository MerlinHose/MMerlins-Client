package dev.mcresearch.protocolclient.app;

import dev.mcresearch.protocolclient.auth.AuthException;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Übersetzt technische Verbindungsfehler in verständliche Meldungen (docs/61 §5). Meldungen enthalten nie Tokens, Schlüssel
 * oder Sitzungsdaten: Servertexte werden gekürzt und lange zeichenhafte Folgen (JWT, Base64, Hex) entfernt.
 */
public final class ConnectErrors {
	/** Fehlerart. */
	public enum Kind {
		UNREACHABLE, INTERRUPTED, TIMEOUT, UNSUPPORTED_VERSION, REJECTED, KICKED, NOT_ALLOWED, PROTOCOL, AUTH, CANCELLED, UNKNOWN
	}

	/** Meldung für Dialog/ConnectingScreen. */
	public record UserError(Kind kind, String title, String detail) {
	}

	private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_\\-+/=.]{40,}");
	private static final Pattern TOKEN_WORDS = Pattern.compile("(?i)(access|refresh|bearer|token|secret)[\"':= ]+[^\\s,;\"']+");

	private ConnectErrors() {
	}

	/** Entfernt mögliche Geheimnisse und begrenzt die Länge. */
	public static String sanitize(final String s) {
		if (s == null) {
			return "";
		}
		String t = TOKEN_WORDS.matcher(s).replaceAll("$1 [entfernt]");
		t = SECRET.matcher(t).replaceAll("[entfernt]");
		t = t.replaceAll("[\\p{Cntrl}&&[^\n]]", " ").strip();
		return t.length() > 300 ? t.substring(0, 300) + " …" : t;
	}

	/**
	 * @param error            Ausnahme des Verbindungsaufbaus/der Sitzung (oder null)
	 * @param disconnectReason Text des Servers aus {@code login_disconnect}/{@code disconnect} (oder null)
	 */
	public static UserError describe(final Throwable error, final String disconnectReason) {
		if (disconnectReason != null) {
			return serverReason(disconnectReason);
		}
		if (error == null) {
			return new UserError(Kind.INTERRUPTED, "Verbindung unterbrochen", "Die Verbindung zum Server wurde unerwartet beendet.");
		}
		Throwable e = error;
		for (Throwable c = error; c != null; c = c.getCause()) {
			if (c instanceof ConnectException || c instanceof SocketTimeoutException || c instanceof UnknownHostException
				|| c instanceof AuthException || c instanceof NoRouteToHostException) {
				e = c;
				break;
			}
		}
		String msg = e.getMessage() == null ? "" : e.getMessage();
		if (e instanceof ConnectException || e instanceof NoRouteToHostException) {
			return new UserError(Kind.UNREACHABLE, "Server nicht erreichbar",
				"An der Adresse antwortet kein Minecraft-Server. Läuft der lokale Testserver?");
		}
		if (e instanceof SocketTimeoutException) {
			return new UserError(Kind.TIMEOUT, "Zeitüberschreitung", msg.toLowerCase(Locale.ROOT).contains("connect")
				? "Der Server hat nicht rechtzeitig auf den Verbindungsaufbau reagiert." : "Der Server antwortet nicht mehr.");
		}
		if (msg.startsWith("No data from server")) {
			return new UserError(Kind.TIMEOUT, "Zeitüberschreitung", "Der Server hat zu lange keine Daten gesendet.");
		}
		if (e instanceof UnknownHostException || e instanceof IllegalArgumentException && msg.contains("loopback")) {
			return new UserError(Kind.NOT_ALLOWED, "Adresse nicht erlaubt",
				"Diese Entwicklungsversion verbindet sich nur mit localhost bzw. 127.0.0.1.");
		}
		if (e instanceof AuthException ae) {
			return new UserError(Kind.AUTH, "Anmeldung fehlgeschlagen", sanitize(ae.userMessage()));
		}
		if (e instanceof EOFException || e instanceof SocketException) {
			return new UserError(Kind.INTERRUPTED, "Verbindung unterbrochen", "Der Server hat die Verbindung geschlossen.");
		}
		if (e instanceof ProtocolException) {
			return new UserError(Kind.PROTOCOL, "Protokollfehler", "Der Server sendete Daten, die der Client nicht versteht: " + sanitize(msg));
		}
		return new UserError(Kind.UNKNOWN, "Verbindung fehlgeschlagen", sanitize(e.getClass().getSimpleName() + (msg.isEmpty() ? "" : ": " + msg)));
	}

	private static UserError serverReason(final String reason) {
		String r = sanitize(reason);
		String key = r.toLowerCase(Locale.ROOT);
		if (key.contains("outdated_client") || key.contains("outdated_server") || key.contains("incompatible") || key.contains("outdated")) {
			return new UserError(Kind.UNSUPPORTED_VERSION, "Nicht unterstützte Serverversion",
				"Der Server lehnt die Client-Version ab (" + r + "). Der Client spricht Minecraft " + MerlinVersion.MINECRAFT_VERSION + ".");
		}
		String why = switch (key) {
			case "multiplayer.disconnect.not_whitelisted" -> "Du stehst nicht auf der Whitelist des Servers.";
			case "multiplayer.disconnect.banned" -> "Du bist auf diesem Server gesperrt.";
			case "multiplayer.disconnect.server_full" -> "Der Server ist voll.";
			case "multiplayer.disconnect.duplicate_login" -> "Mit diesem Namen ist bereits jemand angemeldet.";
			case "multiplayer.disconnect.unverified_username" -> "Der Server verlangt eine echte Anmeldung (online-mode).";
			case "multiplayer.disconnect.authservers_down" -> "Die Anmeldedienste sind nicht erreichbar.";
			case "multiplayer.disconnect.kicked" -> null;
			default -> null;
		};
		if (why != null) {
			return new UserError(Kind.REJECTED, "Server hat die Verbindung abgelehnt", why);
		}
		if (key.contains("kick")) {
			return new UserError(Kind.KICKED, "Vom Server getrennt", r);
		}
		return new UserError(Kind.REJECTED, "Server hat die Verbindung beendet", r.isEmpty() ? "Keine Begründung angegeben." : r);
	}
}
