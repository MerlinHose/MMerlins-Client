package dev.mcresearch.protocolclient.physics;

/**
 * Tabellen-Sinus wie in Minecraft (docs/21 §3): 65 536 {@code float}-Werte, Index aus dem Winkel in Bogenmaß ×
 * 10430.378350470453 (= 65536 / 2π). Ohne diese Tabelle laufen Bahnen ab der 8. Stelle auseinander.
 */
public final class Trig {
	private static final double SCALE = 10430.378350470453;
	private static final float[] SIN = new float[65536];

	static {
		for (int i = 0; i < SIN.length; i++) {
			SIN[i] = (float) Math.sin(i / SCALE);
		}
	}

	private Trig() {
	}

	public static float sin(final double radians) {
		return SIN[(int) ((long) (radians * SCALE) & 65535L)];
	}

	public static float cos(final double radians) {
		return SIN[(int) ((long) (radians * SCALE + 16384.0) & 65535L)];
	}
}
