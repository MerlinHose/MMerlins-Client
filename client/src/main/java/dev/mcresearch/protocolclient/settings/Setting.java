package dev.mcresearch.protocolclient.settings;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Eine Einstellung mit Schlüssel, Typ, Standardwert und Prüfung (docs/60 §2). {@link #parse} wirft bei ungültigen Werten;
 * Zahlen außerhalb des Bereichs gelten als ungültig (der Manager setzt dann den Standard und meldet es).
 *
 * @param <T> Werttyp
 */
public abstract sealed class Setting<T> permits Setting.Bool, Setting.Int, Setting.Num, Setting.Choice, Setting.Text {
	/** Bereich in den Menüs. */
	public enum Category {
		GRAPHICS("Grafik"), CONTROLS("Steuerung"), INTERFACE("Oberfläche"), ACCESSIBILITY("Barrierefreiheit"), ACCOUNT("Konto");

		public final String label;

		Category(final String label) {
			this.label = label;
		}
	}

	private final String key;
	private final String label;
	private final Category category;
	private final T defaultValue;
	private final String help;

	Setting(final String key, final String label, final Category category, final T defaultValue, final String help) {
		this.key = key;
		this.label = label;
		this.category = category;
		this.defaultValue = defaultValue;
		this.help = help;
	}

	public String key() {
		return this.key;
	}

	public String label() {
		return this.label;
	}

	public Category category() {
		return this.category;
	}

	public T defaultValue() {
		return this.defaultValue;
	}

	public String help() {
		return this.help;
	}

	/** Wert aus der Datei lesen; ungültig → {@link IllegalArgumentException}. */
	public abstract T parse(String raw);

	public String format(final T value) {
		return String.valueOf(value);
	}

	/** Prüft einen im Programm gesetzten Wert (gleiche Regeln wie {@link #parse}). */
	public abstract T validate(T value);

	@SuppressWarnings("unchecked")
	final T cast(final Object o) {
		return (T) o;
	}

	public static final class Bool extends Setting<Boolean> {
		public Bool(final String key, final String label, final Category c, final boolean def, final String help) {
			super(key, label, c, def, help);
		}

		@Override
		public Boolean parse(final String raw) {
			return switch (raw.strip().toLowerCase(Locale.ROOT)) {
				case "true", "yes", "on", "1" -> true;
				case "false", "no", "off", "0" -> false;
				default -> throw new IllegalArgumentException("not a boolean: " + raw);
			};
		}

		@Override
		public Boolean validate(final Boolean value) {
			if (value == null) {
				throw new IllegalArgumentException("null");
			}
			return value;
		}
	}

	public static final class Int extends Setting<Integer> {
		public final int min;
		public final int max;

		public Int(final String key, final String label, final Category c, final int def, final int min, final int max, final String help) {
			super(key, label, c, def, help);
			this.min = min;
			this.max = max;
		}

		@Override
		public Integer parse(final String raw) {
			return validate(Integer.parseInt(raw.strip()));
		}

		@Override
		public Integer validate(final Integer v) {
			if (v == null || v < this.min || v > this.max) {
				throw new IllegalArgumentException(v + " outside " + this.min + ".." + this.max);
			}
			return v;
		}
	}

	public static final class Num extends Setting<Double> {
		public final double min;
		public final double max;

		public Num(final String key, final String label, final Category c, final double def, final double min, final double max, final String help) {
			super(key, label, c, def, help);
			this.min = min;
			this.max = max;
		}

		@Override
		public Double parse(final String raw) {
			return validate(Double.parseDouble(raw.strip()));
		}

		@Override
		public Double validate(final Double v) {
			if (v == null || !(v >= this.min && v <= this.max)) {
				throw new IllegalArgumentException(v + " outside " + this.min + ".." + this.max);
			}
			return v;
		}

		@Override
		public String format(final Double value) {
			return String.format(Locale.ROOT, "%.4f", value);
		}
	}

	/** Auswahl aus festen Werten; {@code names} sind die Anzeigenamen (gleiche Reihenfolge wie {@code values}). */
	public static final class Choice extends Setting<String> {
		public final List<String> values;
		public final List<String> names;

		public Choice(final String key, final String label, final Category c, final String def, final List<String> values, final List<String> names,
			final String help) {
			super(key, label, c, def, help);
			this.values = List.copyOf(values);
			this.names = List.copyOf(names);
			if (values.size() != names.size() || !values.contains(def)) {
				throw new IllegalArgumentException("choice " + key);
			}
		}

		@Override
		public String parse(final String raw) {
			return validate(raw.strip().toUpperCase(Locale.ROOT));
		}

		@Override
		public String validate(final String v) {
			if (v == null || !this.values.contains(v)) {
				throw new IllegalArgumentException("not one of " + this.values + ": " + v);
			}
			return v;
		}

		public String nameOf(final String v) {
			int i = this.values.indexOf(v);
			return i < 0 ? v : this.names.get(i);
		}
	}

	public static final class Text extends Setting<String> {
		private final Pattern pattern;
		public final int maxLength;

		public Text(final String key, final String label, final Category c, final String def, final String regex, final int maxLength,
			final String help) {
			super(key, label, c, def, help);
			this.pattern = Pattern.compile(regex);
			this.maxLength = maxLength;
		}

		@Override
		public String parse(final String raw) {
			return validate(raw.strip());
		}

		@Override
		public String validate(final String v) {
			if (v == null || v.length() > this.maxLength || !this.pattern.matcher(v).matches()) {
				throw new IllegalArgumentException("invalid text: " + v);
			}
			return v;
		}

		public boolean accepts(final String v) {
			return v != null && v.length() <= this.maxLength && this.pattern.matcher(v).matches();
		}
	}

	/** Hilfsfunktion: Wert unabhängig vom Typ als Text (für Bericht und Datei). */
	public String formatObject(final Object o) {
		return format(cast(o));
	}
}
