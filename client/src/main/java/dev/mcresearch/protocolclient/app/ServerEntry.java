package dev.mcresearch.protocolclient.app;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Servereintrag (docs/61 §6): Name, Adresse, Port und die zuletzt erkannte Serverversion – getrennt von der Client-Version
 * gespeichert, damit MVS (M18) je Server die passende Protokollschicht wählen kann.
 *
 * @param lastProtocol zuletzt per Statusping erkannte Protokollnummer (0 = noch nie erreicht)
 */
public record ServerEntry(String name, String host, int port, String lastVersionName, int lastProtocol) {
	public static final int DEFAULT_PORT = 25565;

	public ServerEntry {
		if (name == null || name.isBlank() || name.length() > 32) {
			throw new IllegalArgumentException("Name: 1–32 Zeichen");
		}
		if (host == null || host.isBlank() || host.length() > 253 || !host.matches("[A-Za-z0-9.\\-:\\[\\]]+")) {
			throw new IllegalArgumentException("Adresse ungültig");
		}
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("Port 1–65535");
		}
		lastVersionName = lastVersionName == null ? "" : lastVersionName;
	}

	/** Lokaler Vanilla-Testserver des Projekts (Einzelspieler/lokale Testwelt). */
	public static ServerEntry localTestServer() {
		return new ServerEntry("Lokaler Testserver", "127.0.0.1", DEFAULT_PORT, "", 0);
	}

	public ServerEntry withVersion(final String versionName, final int protocol) {
		return new ServerEntry(this.name, this.host, this.port, versionName, protocol);
	}

	public String address() {
		return (this.host.contains(":") && !this.host.startsWith("[") ? "[" + this.host + "]" : this.host) + ":" + this.port;
	}

	/**
	 * Darf in M15 kontaktiert werden? Nur Adressen, die ohne DNS-Abfrage als Loopback erkennbar sind (localhost, 127.x, ::1) –
	 * keine Verbindung zu externen Servern, auch nicht für den Statusping.
	 */
	public boolean allowed() {
		String h = this.host.startsWith("[") ? this.host.substring(1, this.host.length() - 1) : this.host;
		if (h.equalsIgnoreCase("localhost")) {
			return true;
		}
		if (!h.matches("[0-9.]+") && !h.contains(":")) {
			return false; // Hostname: würde DNS benötigen
		}
		try {
			return InetAddress.getByName(h).isLoopbackAddress(); // reine IP-Literale, keine Namensauflösung
		} catch (UnknownHostException e) {
			return false;
		}
	}

	public ProtocolVersions.Support lastSupport() {
		return ProtocolVersions.support(this.lastProtocol);
	}

	/** Ein Eintrag aus Adresseingabe „host[:port]“. */
	public static ServerEntry parse(final String name, final String address) {
		String a = address.strip();
		String host = a;
		int port = DEFAULT_PORT;
		if (a.startsWith("[")) {
			int end = a.indexOf(']');
			if (end < 0) {
				throw new IllegalArgumentException("Adresse ungültig");
			}
			host = a.substring(1, end);
			if (a.length() > end + 1) {
				if (a.charAt(end + 1) != ':') {
					throw new IllegalArgumentException("Adresse ungültig");
				}
				port = parsePort(a.substring(end + 2));
			}
		} else if (a.indexOf(':') >= 0 && a.indexOf(':') == a.lastIndexOf(':')) {
			host = a.substring(0, a.indexOf(':'));
			port = parsePort(a.substring(a.indexOf(':') + 1));
		}
		return new ServerEntry(name.strip(), host, port, "", 0);
	}

	private static int parsePort(final String s) {
		try {
			return Integer.parseInt(s.strip());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Port ungültig");
		}
	}
}
