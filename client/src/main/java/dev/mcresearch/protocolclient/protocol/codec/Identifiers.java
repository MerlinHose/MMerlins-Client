package dev.mcresearch.protocolclient.protocol.codec;

import dev.mcresearch.protocolclient.protocol.ProtocolException;

/**
 * Prüfung von Identifiern {@code namespace:path} nach den in docs/12 §2 dokumentierten Regeln:
 * namespace [a-z0-9_.-] (nicht ".."), path [a-z0-9_./-]; ohne ':' gilt der Namespace "minecraft".
 */
public final class Identifiers {
	private Identifiers() {
	}

	/** Normalisiert und validiert; liefert immer die Form "namespace:path". */
	public static String normalize(final String raw) throws ProtocolException {
		int sep = raw.indexOf(':');
		String namespace;
		String path;
		if (sep < 0) {
			namespace = "minecraft";
			path = raw;
		} else {
			namespace = sep == 0 ? "minecraft" : raw.substring(0, sep);
			path = raw.substring(sep + 1);
		}
		if (namespace.equals("..") || !namespace.chars().allMatch(Identifiers::isNamespaceChar)) {
			throw new ProtocolException("Invalid identifier namespace: " + raw);
		}
		if (!path.chars().allMatch(Identifiers::isPathChar)) {
			throw new ProtocolException("Invalid identifier path: " + raw);
		}
		return namespace + ":" + path;
	}

	private static boolean isNamespaceChar(final int c) {
		return c == '_' || c == '-' || c == '.' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
	}

	private static boolean isPathChar(final int c) {
		return isNamespaceChar(c) || c == '/';
	}
}
