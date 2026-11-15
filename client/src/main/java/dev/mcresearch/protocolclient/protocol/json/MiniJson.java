package dev.mcresearch.protocolclient.protocol.json;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kleiner, strikter JSON-Parser (RFC 8259) für Statusantworten und Login-Disconnect-Texte.
 * Ergebnis: Map, List, String, Double/Long, Boolean oder {@code null}. Tiefe ≤ 64.
 */
public final class MiniJson {
	private static final int MAX_DEPTH = 64;
	private final String s;
	private int pos;

	private MiniJson(final String s) {
		this.s = s;
	}

	public static Object parse(final String text) throws ProtocolException {
		MiniJson p = new MiniJson(text);
		p.ws();
		Object value = p.value(0);
		p.ws();
		if (p.pos != text.length()) {
			throw p.error("Trailing characters");
		}
		return value;
	}

	private ProtocolException error(final String message) {
		return new ProtocolException("JSON: " + message + " at offset " + this.pos);
	}

	private void ws() {
		while (this.pos < this.s.length()) {
			char c = this.s.charAt(this.pos);
			if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
				this.pos++;
			} else {
				return;
			}
		}
	}

	private Object value(final int depth) throws ProtocolException {
		if (depth > MAX_DEPTH) {
			throw error("Nesting too deep");
		}
		if (this.pos >= this.s.length()) {
			throw error("Unexpected end");
		}
		char c = this.s.charAt(this.pos);
		return switch (c) {
			case '{' -> object(depth);
			case '[' -> array(depth);
			case '"' -> string();
			case 't' -> literal("true", Boolean.TRUE);
			case 'f' -> literal("false", Boolean.FALSE);
			case 'n' -> literal("null", null);
			default -> {
				if (c == '-' || (c >= '0' && c <= '9')) {
					yield number();
				}
				throw error("Unexpected character '" + c + "'");
			}
		};
	}

	private Object literal(final String word, final Object result) throws ProtocolException {
		if (!this.s.startsWith(word, this.pos)) {
			throw error("Invalid literal");
		}
		this.pos += word.length();
		return result;
	}

	private Map<String, Object> object(final int depth) throws ProtocolException {
		Map<String, Object> map = new LinkedHashMap<>();
		this.pos++;
		ws();
		if (peek() == '}') {
			this.pos++;
			return map;
		}
		while (true) {
			ws();
			if (peek() != '"') {
				throw error("Expected key");
			}
			String key = string();
			ws();
			expect(':');
			ws();
			map.put(key, value(depth + 1));
			ws();
			char c = next();
			if (c == '}') {
				return map;
			}
			if (c != ',') {
				throw error("Expected ',' or '}'");
			}
		}
	}

	private List<Object> array(final int depth) throws ProtocolException {
		List<Object> list = new ArrayList<>();
		this.pos++;
		ws();
		if (peek() == ']') {
			this.pos++;
			return list;
		}
		while (true) {
			ws();
			list.add(value(depth + 1));
			ws();
			char c = next();
			if (c == ']') {
				return list;
			}
			if (c != ',') {
				throw error("Expected ',' or ']'");
			}
		}
	}

	private String string() throws ProtocolException {
		expect('"');
		StringBuilder sb = new StringBuilder();
		while (true) {
			char c = next();
			if (c == '"') {
				return sb.toString();
			}
			if (c < 0x20) {
				throw error("Control character in string");
			}
			if (c != '\\') {
				sb.append(c);
				continue;
			}
			char e = next();
			switch (e) {
				case '"', '\\', '/' -> sb.append(e);
				case 'b' -> sb.append('\b');
				case 'f' -> sb.append('\f');
				case 'n' -> sb.append('\n');
				case 'r' -> sb.append('\r');
				case 't' -> sb.append('\t');
				case 'u' -> {
					if (this.pos + 4 > this.s.length()) {
						throw error("Truncated unicode escape");
					}
					try {
						sb.append((char) Integer.parseInt(this.s.substring(this.pos, this.pos + 4), 16));
					} catch (NumberFormatException ex) {
						throw error("Invalid unicode escape");
					}
					this.pos += 4;
				}
				default -> throw error("Invalid escape \\" + e);
			}
		}
	}

	private Object number() throws ProtocolException {
		int start = this.pos;
		if (peek() == '-') {
			this.pos++;
		}
		boolean fraction = false;
		while (this.pos < this.s.length()) {
			char c = this.s.charAt(this.pos);
			if (c >= '0' && c <= '9') {
				this.pos++;
			} else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
				fraction = true;
				this.pos++;
			} else {
				break;
			}
		}
		String text = this.s.substring(start, this.pos);
		try {
			return fraction ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
		} catch (NumberFormatException e) {
			throw error("Invalid number " + text);
		}
	}

	private char peek() throws ProtocolException {
		if (this.pos >= this.s.length()) {
			throw error("Unexpected end");
		}
		return this.s.charAt(this.pos);
	}

	private char next() throws ProtocolException {
		char c = peek();
		this.pos++;
		return c;
	}

	private void expect(final char c) throws ProtocolException {
		if (next() != c) {
			this.pos--;
			throw error("Expected '" + c + "'");
		}
	}
}
