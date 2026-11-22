package dev.mcresearch.protocolclient.input;

import java.util.Locale;

/**
 * Geräteunabhängige Bezeichnung eines Eingabeelements (docs/60 §4): Taste, Maustaste, Mausrad-Richtung, Mausachse und – für M17
 * vorbereitet – Gamepad-Taste und Gamepad-Achse. Codes sind je Gerätetyp eindeutig; die Grafikschicht übersetzt GLFW-Werte,
 * eine spätere input4j-Quelle ihre eigenen Werte in diese Form.
 */
public record InputCode(Device device, int code) {
	public enum Device {
		KEYBOARD("key"), MOUSE_BUTTON("mouse"), MOUSE_WHEEL("wheel"), MOUSE_AXIS("axis"), GAMEPAD_BUTTON("pad"), GAMEPAD_AXIS("padaxis"), NONE("none");

		public final String prefix;

		Device(final String prefix) {
			this.prefix = prefix;
		}

		/** Liefert analoge Werte (Achse, Mausrad) statt nur gedrückt/losgelassen. */
		public boolean analog() {
			return this == MOUSE_WHEEL || this == MOUSE_AXIS || this == GAMEPAD_AXIS;
		}
	}

	public static final InputCode NONE = new InputCode(Device.NONE, 0);
	public static final int WHEEL_UP = 0;
	public static final int WHEEL_DOWN = 1;
	public static final int AXIS_X = 0;
	public static final int AXIS_Y = 1;

	public static InputCode key(final int key) {
		return new InputCode(Device.KEYBOARD, key);
	}

	public static InputCode mouse(final int button) {
		return new InputCode(Device.MOUSE_BUTTON, button);
	}

	public static InputCode wheel(final int direction) {
		return new InputCode(Device.MOUSE_WHEEL, direction);
	}

	public static InputCode axis(final int axis) {
		return new InputCode(Device.MOUSE_AXIS, axis);
	}

	public boolean isNone() {
		return this.device == Device.NONE;
	}

	/** Form für die Konfigurationsdatei, z. B. {@code key:87}, {@code mouse:0}, {@code wheel:up}, {@code none}. */
	public String serialize() {
		return switch (this.device) {
			case NONE -> "none";
			case MOUSE_WHEEL -> "wheel:" + (this.code == WHEEL_UP ? "up" : "down");
			case MOUSE_AXIS -> "axis:" + (this.code == AXIS_X ? "x" : "y");
			default -> this.device.prefix + ":" + this.code;
		};
	}

	/** Umkehrung von {@link #serialize()}; ungültige Werte → {@link IllegalArgumentException}. */
	public static InputCode parse(final String s) {
		String t = s.strip().toLowerCase(Locale.ROOT);
		if (t.equals("none")) {
			return NONE;
		}
		int c = t.indexOf(':');
		if (c <= 0) {
			throw new IllegalArgumentException("input code without device: " + s);
		}
		String dev = t.substring(0, c);
		String v = t.substring(c + 1);
		return switch (dev) {
			case "key" -> {
				int k = Integer.parseInt(v);
				if (!Keys.valid(k)) {
					throw new IllegalArgumentException("key code out of range: " + k);
				}
				yield key(k);
			}
			case "mouse" -> {
				int b = Integer.parseInt(v);
				if (b < 0 || b > 7) {
					throw new IllegalArgumentException("mouse button out of range: " + b);
				}
				yield mouse(b);
			}
			case "wheel" -> switch (v) {
				case "up" -> wheel(WHEEL_UP);
				case "down" -> wheel(WHEEL_DOWN);
				default -> throw new IllegalArgumentException("wheel direction: " + v);
			};
			case "axis" -> switch (v) {
				case "x" -> axis(AXIS_X);
				case "y" -> axis(AXIS_Y);
				default -> throw new IllegalArgumentException("axis: " + v);
			};
			case "pad" -> new InputCode(Device.GAMEPAD_BUTTON, Integer.parseInt(v));
			case "padaxis" -> new InputCode(Device.GAMEPAD_AXIS, Integer.parseInt(v));
			default -> throw new IllegalArgumentException("unknown input device: " + dev);
		};
	}

	/** Deutscher Anzeigename für Menüs. */
	public String displayName() {
		return switch (this.device) {
			case NONE -> "Nicht belegt";
			case KEYBOARD -> Keys.name(this.code);
			case MOUSE_BUTTON -> switch (this.code) {
				case 0 -> "Linke Maustaste";
				case 1 -> "Rechte Maustaste";
				case 2 -> "Mittlere Maustaste";
				default -> "Maustaste " + (this.code + 1);
			};
			case MOUSE_WHEEL -> this.code == WHEEL_UP ? "Mausrad hoch" : "Mausrad runter";
			case MOUSE_AXIS -> this.code == AXIS_X ? "Maus waagerecht" : "Maus senkrecht";
			case GAMEPAD_BUTTON -> "Gamepad-Taste " + this.code;
			case GAMEPAD_AXIS -> "Gamepad-Achse " + this.code;
		};
	}
}
