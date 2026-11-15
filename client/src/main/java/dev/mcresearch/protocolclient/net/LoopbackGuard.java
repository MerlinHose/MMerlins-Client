package dev.mcresearch.protocolclient.net;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Sicherheitsvorgabe: Der Client verbindet sich ausschließlich mit Loopback-Adressen (docs/13 §Sicherheit).
 * Erlaubt sind "localhost" und IP-Literale im Loopback-Bereich. Andere Hostnamen werden <b>ohne</b> DNS-Abfrage
 * abgewiesen, damit keine Namensauflösung über externe DNS-Server stattfindet.
 */
public final class LoopbackGuard {
	private LoopbackGuard() {
	}

	public static InetAddress resolveLoopback(final String host) throws UnknownHostException {
		if (host.equalsIgnoreCase("localhost")) {
			// Explizit 127.0.0.1 statt Systemauflösung: der Testserver bindet nur IPv4-Loopback.
			return InetAddress.getByAddress("localhost", new byte[]{127, 0, 0, 1});
		}
		if (!isIpLiteral(host)) {
			throw new IllegalArgumentException("Only localhost or loopback IP literals are allowed, got: " + host);
		}
		InetAddress address = InetAddress.getByName(host); // IP-Literal: keine DNS-Abfrage
		if (!address.isLoopbackAddress()) {
			throw new IllegalArgumentException("Refusing non-loopback address " + address.getHostAddress());
		}
		return address;
	}

	static boolean isIpLiteral(final String host) {
		if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
			for (String part : host.split("\\.")) {
				if (Integer.parseInt(part) > 255) {
					return false;
				}
			}
			return true;
		}
		String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
		return h.contains(":") && h.matches("[0-9a-fA-F:.]+");
	}
}
