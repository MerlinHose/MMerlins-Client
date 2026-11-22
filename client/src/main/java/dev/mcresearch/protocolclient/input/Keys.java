package dev.mcresearch.protocolclient.input;

import java.util.HashMap;
import java.util.Map;

/**
 * Tastencodes der Tastatur (Werte wie GLFW, damit die Grafikschicht sie unverändert durchreichen kann). Der {@code main}-Teil
 * bleibt ohne LWJGL; diese Tabelle enthält nur die Zahlen und deutsche Anzeigenamen.
 */
public final class Keys {
	public static final int SPACE = 32;
	public static final int APOSTROPHE = 39;
	public static final int COMMA = 44;
	public static final int MINUS = 45;
	public static final int PERIOD = 46;
	public static final int SLASH = 47;
	public static final int DIGIT_0 = 48;
	public static final int DIGIT_1 = 49;
	public static final int SEMICOLON = 59;
	public static final int EQUAL = 61;
	public static final int A = 65;
	public static final int D = 68;
	public static final int E = 69;
	public static final int F = 70;
	public static final int Q = 81;
	public static final int S = 83;
	public static final int V = 86;
	public static final int W = 87;
	public static final int LEFT_BRACKET = 91;
	public static final int BACKSLASH = 92;
	public static final int RIGHT_BRACKET = 93;
	public static final int GRAVE = 96;
	public static final int ESCAPE = 256;
	public static final int ENTER = 257;
	public static final int TAB = 258;
	public static final int BACKSPACE = 259;
	public static final int INSERT = 260;
	public static final int DELETE = 261;
	public static final int RIGHT = 262;
	public static final int LEFT = 263;
	public static final int DOWN = 264;
	public static final int UP = 265;
	public static final int PAGE_UP = 266;
	public static final int PAGE_DOWN = 267;
	public static final int HOME = 268;
	public static final int END = 269;
	public static final int CAPS_LOCK = 280;
	public static final int F1 = 290;
	public static final int F2 = 291;
	public static final int F3 = 292;
	public static final int F5 = 294;
	public static final int F11 = 300;
	public static final int F12 = 301;
	public static final int KP_0 = 320;
	public static final int KP_ENTER = 335;
	public static final int LEFT_SHIFT = 340;
	public static final int LEFT_CONTROL = 341;
	public static final int LEFT_ALT = 342;
	public static final int LEFT_SUPER = 343;
	public static final int RIGHT_SHIFT = 344;
	public static final int RIGHT_CONTROL = 345;
	public static final int RIGHT_ALT = 346;
	public static final int LAST = 348;

	public static final int MOD_SHIFT = 1;
	public static final int MOD_CONTROL = 2;
	public static final int MOD_ALT = 4;

	private static final Map<Integer, String> NAMES = new HashMap<>();

	static {
		NAMES.put(SPACE, "Leertaste");
		NAMES.put(APOSTROPHE, "'");
		NAMES.put(COMMA, ",");
		NAMES.put(MINUS, "-");
		NAMES.put(PERIOD, ".");
		NAMES.put(SLASH, "/");
		NAMES.put(SEMICOLON, ";");
		NAMES.put(EQUAL, "=");
		NAMES.put(LEFT_BRACKET, "[");
		NAMES.put(BACKSLASH, "\\");
		NAMES.put(RIGHT_BRACKET, "]");
		NAMES.put(GRAVE, "^");
		NAMES.put(ESCAPE, "Esc");
		NAMES.put(ENTER, "Eingabe");
		NAMES.put(TAB, "Tab");
		NAMES.put(BACKSPACE, "Rücktaste");
		NAMES.put(INSERT, "Einfg");
		NAMES.put(DELETE, "Entf");
		NAMES.put(RIGHT, "Pfeil rechts");
		NAMES.put(LEFT, "Pfeil links");
		NAMES.put(DOWN, "Pfeil unten");
		NAMES.put(UP, "Pfeil oben");
		NAMES.put(PAGE_UP, "Bild auf");
		NAMES.put(PAGE_DOWN, "Bild ab");
		NAMES.put(HOME, "Pos1");
		NAMES.put(END, "Ende");
		NAMES.put(CAPS_LOCK, "Feststell");
		NAMES.put(KP_ENTER, "Num Eingabe");
		NAMES.put(LEFT_SHIFT, "Umschalt links");
		NAMES.put(LEFT_CONTROL, "Strg links");
		NAMES.put(LEFT_ALT, "Alt links");
		NAMES.put(LEFT_SUPER, "Windows links");
		NAMES.put(RIGHT_SHIFT, "Umschalt rechts");
		NAMES.put(RIGHT_CONTROL, "Strg rechts");
		NAMES.put(RIGHT_ALT, "Alt Gr");
		for (int i = 0; i < 25; i++) {
			NAMES.put(F1 + i, "F" + (i + 1));
		}
		for (int i = 0; i < 10; i++) {
			NAMES.put(DIGIT_0 + i, Integer.toString(i));
			NAMES.put(KP_0 + i, "Num " + i);
		}
		for (int c = 'A'; c <= 'Z'; c++) {
			NAMES.put(c, Character.toString(c));
		}
	}

	private Keys() {
	}

	/** Anzeigename einer Taste (unbekannte Codes als „Taste 123“). */
	public static String name(final int key) {
		String n = NAMES.get(key);
		return n != null ? n : "Taste " + key;
	}

	/** Gültiger Tastencode (GLFW-Bereich, ohne „unbekannt“ −1). */
	public static boolean valid(final int key) {
		return key >= SPACE && key <= LAST;
	}

	public static boolean isModifier(final int key) {
		return key >= LEFT_SHIFT && key <= RIGHT_ALT || key == LEFT_SUPER;
	}
}
