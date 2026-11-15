package dev.mcresearch.protocolclient.view;

/**
 * Würfelseite mit Normale und Eckpunkten gegen den Uhrzeigersinn von außen gesehen (docs/25 §3). Namen wie in
 * Minecraft-Modellen ({@code down, up, north, south, west, east}); Norden = −Z, Osten = +X.
 */
public enum Face {
	DOWN(0, -1, 0, "down"), UP(0, 1, 0, "up"), NORTH(0, 0, -1, "north"), SOUTH(0, 0, 1, "south"), WEST(-1, 0, 0, "west"), EAST(1, 0, 0, "east");

	public final int dx;
	public final int dy;
	public final int dz;
	public final String jsonName;

	Face(final int dx, final int dy, final int dz, final String jsonName) {
		this.dx = dx;
		this.dy = dy;
		this.dz = dz;
		this.jsonName = jsonName;
	}

	public static Face byName(final String name) {
		for (Face f : values()) {
			if (f.jsonName.equals(name)) {
				return f;
			}
		}
		throw new IllegalArgumentException("unknown face " + name);
	}

	public Face opposite() {
		return switch (this) {
			case DOWN -> UP;
			case UP -> DOWN;
			case NORTH -> SOUTH;
			case SOUTH -> NORTH;
			case WEST -> EAST;
			case EAST -> WEST;
		};
	}

	/** Richtungsabhängige Grundhelligkeit wie Minecraft (oben 1,0, unten 0,5, Nord/Süd 0,8, West/Ost 0,6). */
	public float shade() {
		return switch (this) {
			case UP -> 1.0f;
			case DOWN -> 0.5f;
			case NORTH, SOUTH -> 0.8f;
			case WEST, EAST -> 0.6f;
		};
	}

	/**
	 * Vier Ecken der Seite der Box gegen den Uhrzeigersinn von außen; Reihenfolge passend zu UV (u0,v1)(u1,v1)(u1,v0)(u0,v0),
	 * d. h. Ecke 0 = links unten aus Sicht des Betrachters vor der Fläche.
	 */
	public float[] corners(final float x0, final float y0, final float z0, final float x1, final float y1, final float z1) {
		return switch (this) {
			case DOWN -> new float[]{x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1}; // von unten, Bildoberkante = Süden
			case UP -> new float[]{x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0}; // von oben, Bildoberkante = Norden
			case NORTH -> new float[]{x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0};
			case SOUTH -> new float[]{x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1};
			case WEST -> new float[]{x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0};
			case EAST -> new float[]{x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1};
		};
	}
}
