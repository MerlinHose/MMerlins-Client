package dev.mcresearch.protocolclient.auth.http;

import dev.mcresearch.protocolclient.auth.AuthError;
import dev.mcresearch.protocolclient.auth.AuthException;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.json.MiniJson;
import java.util.List;
import java.util.Map;

/**
 * M13: JSON für Anmeldedienste auf Basis des vorhandenen {@link MiniJson} (Parser) plus minimalem Writer.
 * Fehlermeldungen nennen nur Feldnamen, nie Feldinhalte (Tokens).
 */
public final class Json {
	private final Object root;

	private Json(final Object root) {
		this.root = root;
	}

	public static Json parse(final String text, final String what) throws AuthException {
		try {
			return new Json(MiniJson.parse(text));
		} catch (ProtocolException e) {
			throw new AuthException(AuthError.PROTOCOL, what + ": invalid JSON");
		}
	}

	/** Navigiert über Objektschlüssel und Listenindizes ({@code "DisplayClaims", "xui", 0, "uhs"}); null wenn nicht vorhanden. */
	public Object at(final Object... path) {
		Object cur = this.root;
		for (Object p : path) {
			if (p instanceof String key && cur instanceof Map<?, ?> m) {
				cur = m.get(key);
			} else if (p instanceof Integer i && cur instanceof List<?> l) {
				cur = i >= 0 && i < l.size() ? l.get(i) : null;
			} else {
				return null;
			}
		}
		return cur;
	}

	public String string(final Object... path) {
		return at(path) instanceof String s ? s : null;
	}

	public long number(final long fallback, final Object... path) {
		Object v = at(path);
		if (v instanceof Long l) {
			return l;
		}
		if (v instanceof Double d) {
			return d.longValue();
		}
		return fallback;
	}

	public boolean bool(final boolean fallback, final Object... path) {
		return at(path) instanceof Boolean b ? b : fallback;
	}

	public String requireString(final String what, final Object... path) throws AuthException {
		String s = string(path);
		if (s == null || s.isEmpty()) {
			throw new AuthException(AuthError.PROTOCOL, what + ": missing field " + java.util.Arrays.toString(path));
		}
		return s;
	}

	/** Schreibt ein JSON-Objekt aus String-/Zahl-/Boolean-/Listen-/Map-Werten (Schlüsselreihenfolge bleibt erhalten). */
	public static String write(final Object value) {
		StringBuilder b = new StringBuilder();
		write(b, value);
		return b.toString();
	}

	private static void write(final StringBuilder b, final Object v) {
		switch (v) {
			case null -> b.append("null");
			case String s -> quote(b, s);
			case Boolean bo -> b.append(bo.booleanValue());
			case Number n -> b.append(n);
			case Map<?, ?> m -> {
				b.append('{');
				boolean first = true;
				for (Map.Entry<?, ?> e : m.entrySet()) {
					if (!first) {
						b.append(',');
					}
					first = false;
					quote(b, String.valueOf(e.getKey()));
					b.append(':');
					write(b, e.getValue());
				}
				b.append('}');
			}
			case List<?> l -> {
				b.append('[');
				for (int i = 0; i < l.size(); i++) {
					if (i > 0) {
						b.append(',');
					}
					write(b, l.get(i));
				}
				b.append(']');
			}
			default -> throw new IllegalArgumentException("unsupported JSON value " + v.getClass());
		}
	}

	private static void quote(final StringBuilder b, final String s) {
		b.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> {
					if (c < 0x20) {
						b.append(String.format("\\u%04x", (int) c));
					} else {
						b.append(c);
					}
				}
			}
		}
		b.append('"');
	}
}
