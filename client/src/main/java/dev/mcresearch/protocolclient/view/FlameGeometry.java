package dev.mcresearch.protocolclient.view;

import java.util.Set;

/**
 * M14: Feuer um brennende Entities (Vanilla 26.3 {@code FlameFeatureRenderer}, nachgebildet aus den Regeln, kein kopierter Code).
 *
 * <ul>
 *   <li>Sichtbar, wenn {@code Entity#displayFireAnimation}: Brennflag (gemeinsames Flag 0 auf dem Client) und nicht feuerimmun
 *       ({@code EntityType#fireImmune}).</li>
 *   <li>Geometrie: kamerazugewandte Quads, skaliert mit {@code s = Breite · 1,4}; Lagen bis zur Höhe {@code h = Höhe / s}, je Lage
 *       0,45 höher, Halbbreite · 0,9, z −0,03; Gesamtversatz z {@code 0,3 − (int) h · 0,02}; Sprites {@code fire_0}/{@code fire_1}
 *       abwechselnd, jedes zweite Paar horizontal gespiegelt. Gleiche Float-Arithmetik wie Vanilla.</li>
 *   <li>Licht: Blocklicht 15, Normale (0, 1, 0).</li>
 * </ul>
 */
public final class FlameGeometry {
	/** Feuerimmune Typen (EntityTypes: {@code .fireImmune()}), Faktentabelle aus den 26.3-Quellen. */
	public static final Set<String> FIRE_IMMUNE = Set.of("area_effect_cloud", "blaze", "end_crystal", "ender_dragon", "ghast", "magma_cube",
		"shulker", "strider", "tnt", "vex", "warden", "wither", "wither_skeleton", "zoglin", "zombified_piglin");
	public static final int FLAG_ON_FIRE = 0x01;

	private FlameGeometry() {
	}

	/** {@code displayFireAnimation} aus Typ, gemeinsamen Flags und Zuschauerstatus. */
	public static boolean showsFire(final String type, final int sharedFlags, final boolean spectator) {
		return (sharedFlags & FLAG_ON_FIRE) != 0 && !FIRE_IMMUNE.contains(type) && !spectator;
	}

	public static float scale(final float width) {
		return width * 1.4F;
	}

	/** Anzahl der Lagen ({@code while (h > 0) h -= 0.45F}). */
	public static int layers(final float width, final float height) {
		float h = height / scale(width);
		int n = 0;
		while (h > 0.0F) {
			h -= 0.45F;
			n++;
		}
		return n;
	}

	/** Vertexanzahl eines Feuer-Meshes: je Lage ein Quad aus zwei Dreiecken (MeshBuilder). */
	public static int vertices(final int layers) {
		return 6 * layers;
	}

	/** Neuer MeshBuilder mit genau passender Kapazität. */
	public static MeshBuilder builder(final int layers) {
		return new MeshBuilder(vertices(layers), vertices(layers));
	}

	/** z-Versatz vor den Lagen: {@code 0,3 − (int) h · 0,02}. */
	public static float zOffset(final float width, final float height) {
		float h = height / scale(width);
		return 0.3F - (int) h * 0.02F;
	}

	/**
	 * Quads im Feuer-Raum (vor Skalierung, Drehung, z-Versatz). {@code fire0}/{@code fire1}: Sprite-Koordinaten {u0, v0, u1, v1}.
	 * {@code color}: Vertexfarbe (Lichtfaktor der Normale (0, 1, 0)).
	 */
	public static void build(final MeshBuilder b, final int layers, final float[] fire0, final float[] fire1, final int color) {
		float r = 0.5F;
		float yo = 0.0F;
		float zo = 0.0F;
		int[] c = {color, color, color, color};
		for (int ss = 0; ss < layers; ss++) {
			float[] t = ss % 2 == 0 ? fire0 : fire1;
			float u0 = t[0];
			float v0 = t[1];
			float u1 = t[2];
			float v1 = t[3];
			if (ss / 2 % 2 == 0) {
				float tmp = u1;
				u1 = u0;
				u0 = tmp;
			}
			float y0 = 0.0F - yo;
			float y1 = 1.4F - yo;
			b.quad(new float[]{-r, y0, zo, r, y0, zo, r, y1, zo, -r, y1, zo}, new float[]{u1, v1, u0, v1, u0, v0, u1, v0}, c);
			yo -= 0.45F;
			r *= 0.9F;
			zo -= 0.03F;
		}
	}
}
