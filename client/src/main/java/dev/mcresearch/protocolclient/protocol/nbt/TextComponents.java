package dev.mcresearch.protocolclient.protocol.nbt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Wandelt einen Text-Component (als NBT- oder JSON-Baum aus einfachen Java-Werten) in lesbaren Klartext,
 * z. B. für Disconnect-Gründe. Unterstützt: String, {"text"}, {"translate","with"}, {"extra"}, Listen.
 * Ohne Sprachtabelle werden Übersetzungsschlüssel nicht aufgelöst, sondern mit ihren Argumenten ausgegeben („Schlüssel[Argumente]“).
 */
public final class TextComponents {
	private static final int MAX_DEPTH = 64;

	private TextComponents() {
	}

	public static String toPlainText(final Object component) {
		StringBuilder sb = new StringBuilder();
		append(sb, component, 0, null);
		return sb.toString();
	}

	/**
	 * M10: wie {@link #toPlainText(Object)}, aber Übersetzungsschlüssel werden über {@code lang} aufgelöst (z. B. lokale en_us.json);
	 * Platzhalter {@code %s} und {@code %1$s} werden durch die Argumente ersetzt. Unbekannte Schlüssel bleiben „Schlüssel[Argumente]“.
	 */
	public static String toPlainText(final Object component, final Function<String, String> lang) {
		StringBuilder sb = new StringBuilder();
		append(sb, component, 0, lang);
		return sb.toString();
	}

	/** Ersetzt {@code %s} (fortlaufend), {@code %n$s} (n-tes Argument) und {@code %%}. */
	static String format(final String pattern, final List<String> args) {
		StringBuilder out = new StringBuilder();
		int next = 0;
		int i = 0;
		while (i < pattern.length()) {
			char ch = pattern.charAt(i);
			if (ch != '%' || i + 1 >= pattern.length()) {
				out.append(ch);
				i++;
				continue;
			}
			char n = pattern.charAt(i + 1);
			if (n == '%') {
				out.append('%');
				i += 2;
			} else if (n == 's') {
				out.append(next < args.size() ? args.get(next) : "");
				next++;
				i += 2;
			} else {
				int k = i + 1;
				while (k < pattern.length() && Character.isDigit(pattern.charAt(k))) {
					k++;
				}
				boolean positional = k > i + 1 && k + 1 < pattern.length() && pattern.charAt(k) == '$' && pattern.charAt(k + 1) == 's';
				if (positional) {
					int index = Integer.parseInt(pattern.substring(i + 1, k)) - 1;
					out.append(index >= 0 && index < args.size() ? args.get(index) : "");
					i = k + 2;
				} else {
					out.append(ch);
					i++;
				}
			}
		}
		return out.toString();
	}

	private static void append(final StringBuilder sb, final Object c, final int depth, final Function<String, String> lang) {
		if (c == null || depth > MAX_DEPTH || sb.length() > 4096) {
			return;
		}
		if (c instanceof String s) {
			sb.append(s);
		} else if (c instanceof List<?> list) {
			for (Object part : list) {
				append(sb, part, depth + 1, lang);
			}
		} else if (c instanceof Map<?, ?> map) {
			Object text = map.get("text");
			Object translate = map.get("translate");
			if (text instanceof String s) {
				sb.append(s);
			} else if (translate instanceof String key && lang != null && lang.apply(key) != null) {
				List<String> args = new ArrayList<>();
				if (map.get("with") instanceof List<?> with) {
					for (Object a : with) {
						StringBuilder ab = new StringBuilder();
						append(ab, a, depth + 1, lang);
						args.add(ab.toString());
					}
				}
				sb.append(format(lang.apply(key), args));
			} else if (translate instanceof String key) {
				sb.append(key);
				if (map.get("with") instanceof List<?> args && !args.isEmpty()) {
					sb.append('[');
					for (int i = 0; i < args.size(); i++) {
						if (i > 0) {
							sb.append(", ");
						}
						append(sb, args.get(i), depth + 1, lang);
					}
					sb.append(']');
				}
			} else if (map.get("") != null) {
				append(sb, map.get(""), depth + 1, lang);
			}
			if (map.get("extra") instanceof List<?> extra) {
				for (Object part : extra) {
					append(sb, part, depth + 1, lang);
				}
			}
		} else {
			sb.append(c);
		}
	}
}
