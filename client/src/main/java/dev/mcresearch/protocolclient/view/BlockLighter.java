package dev.mcresearch.protocolclient.view;

import dev.mcresearch.protocolclient.world.SectionSnapshot;

/**
 * Beleuchtung je Fläche und Vertex (docs/47 §3): Ambient Occlusion mit weichem Licht und flache Beleuchtung, eigene Umsetzung
 * des in 26.3 verwendeten Verfahrens ({@code BlockModelLighter}).
 *
 * <p><b>Lichtkoordinaten</b> wie {@code UV2}: Blocklicht in Bit 0–7, Himmelslicht in Bit 16–23, je Stufe × 16 (0…240),
 * Zwischenwerte durch Mittelung. {@code emissiveRendering} → volle Helligkeit; sonst Blocklicht mindestens die eigene
 * Lichtemission des Zustands.
 *
 * <p><b>Ambient Occlusion</b> einer Fläche in Richtung D: Liegt die Fläche auf der Blockgrenze (oder ist der Block voll), wird
 * in der Nachbarzelle P+D gemessen, sonst in P. Je Ecke werden die zwei seitlichen Nachbarn und – nur wenn einer der beiden
 * lichtdurchlässig ist – der diagonale Eckblock einbezogen; Schattenhelligkeit (0,2 voller Kollisionsblock, sonst 1,0) und
 * Licht werden mit der Mitte zu vier Eckwerten gemittelt. Vertices erhalten den Eckwert ihrer Ecke bzw. – bei Teilflächen –
 * bilinear nach ihrer Lage. Anschließend Richtungsschattierung ({@code CardinalLighting}: unten 0,5, oben 1,0, Nord/Süd 0,8,
 * West/Ost 0,6; Nether unten/oben 0,9).
 */
public final class BlockLighter {
	/** Ecken-Richtungen je Seite (Reihenfolge wie die Vorlage: [0]/[1] gegenüber, [2]/[3] gegenüber). */
	private static final Face[][] CORNERS = {
		{Face.WEST, Face.EAST, Face.NORTH, Face.SOUTH}, // DOWN
		{Face.EAST, Face.WEST, Face.NORTH, Face.SOUTH}, // UP
		{Face.UP, Face.DOWN, Face.EAST, Face.WEST}, // NORTH
		{Face.WEST, Face.EAST, Face.DOWN, Face.UP}, // SOUTH
		{Face.UP, Face.DOWN, Face.NORTH, Face.SOUTH}, // WEST
		{Face.DOWN, Face.UP, Face.NORTH, Face.SOUTH}, // EAST
	};
	public static final float[] CARDINAL_DEFAULT = {0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f};
	public static final float[] CARDINAL_NETHER = {0.9f, 0.9f, 0.8f, 0.8f, 0.6f, 0.6f};
	public static final int FULL_BRIGHT = 240 | 240 << 16;

	private final SectionSnapshot snap;
	private final BlockRenderFacts facts;
	private final float[] cardinal;

	public BlockLighter(final SectionSnapshot snap, final BlockRenderFacts facts, final float[] cardinal) {
		this.snap = snap;
		this.facts = facts;
		this.cardinal = cardinal;
	}

	private int state(final int x, final int y, final int z) {
		return SectionSnapshot.inside(x, y, z) ? Math.max(0, this.snap.state(x, y, z)) : 0;
	}

	/** {@code LightCoordsUtil#getLightCoords(level, state, pos)}: Helligkeit an pos mit der Emission von {@code stateId}. */
	public int lightCoords(final int stateId, final int x, final int y, final int z) {
		if (this.facts.emissiveRendering(stateId)) {
			return FULL_BRIGHT;
		}
		int sky = 15;
		int block = 0;
		if (SectionSnapshot.inside(x, y, z)) {
			sky = this.snap.sky(x, y, z);
			block = this.snap.block(x, y, z);
		}
		block = Math.max(block, this.facts.lightEmission(stateId));
		return block << 4 | sky << 20;
	}

	public float directional(final Face f) {
		return this.cardinal[f.ordinal()];
	}

	static int skyLevel(final int lc) {
		return lc >> 20 & 15;
	}

	static int blockLevel(final int lc) {
		return lc >> 4 & 15;
	}

	/** {@code smoothBlend}: dunkle Nachbarn (0) durch die Mitte ersetzen, wenn die Mitte heller als Stufe 2 ist. */
	static int smoothBlend(int n1, int n2, int n3, final int center) {
		if (skyLevel(center) > 2 || blockLevel(center) > 2) {
			if (n1 == 0) {
				n1 = center;
			} else if (skyLevel(n1) == 0) {
				n1 |= center & 0xFF0000;
			}
			if (n2 == 0) {
				n2 = center;
			} else if (skyLevel(n2) == 0) {
				n2 |= center & 0xFF0000;
			}
			if (n3 == 0) {
				n3 = center;
			} else if (skyLevel(n3) == 0) {
				n3 |= center & 0xFF0000;
			}
		}
		return n1 + n2 + n3 + center >> 2 & 0xFF00FF;
	}

	static int weightedBlend(final int[] c, final float[] w) {
		float sky = 0;
		float block = 0;
		for (int i = 0; i < 4; i++) {
			sky += (c[i] >> 16 & 255) * w[i];
			block += (c[i] & 255) * w[i];
		}
		return ((int) block & 255) | ((int) sky & 255) << 16;
	}

	/** {@code lightCoordsWithEmission}: Licht mindestens {@code emission} (in Stufen) für Himmel und Block. */
	static int withEmission(final int lc, final int emission) {
		if (emission == 0) {
			return lc;
		}
		int sky = Math.max(skyLevel(lc), emission);
		int block = Math.max(blockLevel(lc), emission);
		return block << 4 | sky << 20;
	}

	static int gray(final float v) {
		int c = (int) Math.floor(v * 255.0f);
		c = Math.max(0, Math.min(255, c));
		return MeshBuilder.rgba(c, c, c, 255);
	}

	static int scaleRgb(final int rgba, final float s) {
		int r = Math.max(0, Math.min(255, (int) ((rgba & 255) * s)));
		int g = Math.max(0, Math.min(255, (int) ((rgba >> 8 & 255) * s)));
		int b = Math.max(0, Math.min(255, (int) ((rgba >> 16 & 255) * s)));
		return MeshBuilder.rgba(r, g, b, rgba >>> 24);
	}

	/** Ergebnis je Fläche: Farbe (RGBA, ohne Tönung) und Lichtkoordinaten je Vertex. */
	public static final class Out {
		public final int[] colors = new int[4];
		public final int[] lights = new int[4];
	}

	/**
	 * Flache Beleuchtung ({@code tesselateFlat}/{@code prepareQuadFlat}): Fläche mit cullface → Licht der Nachbarzelle; ohne
	 * cullface → Nachbarzelle, wenn die Fläche auf der Blockgrenze liegt, sonst eigene Zelle.
	 */
	public void flat(final int stateId, final int x, final int y, final int z, final RenderQuad q, final Out out) {
		int lc;
		if (q.cullFace() != null) {
			Face c = q.cullFace();
			lc = lightCoords(stateId, x + c.dx, y + c.dy, z + c.dz);
		} else {
			Face d = q.direction();
			lc = faceCubic(stateId, q, d) ? lightCoords(stateId, x + d.dx, y + d.dy, z + d.dz) : lightCoords(stateId, x, y, z);
		}
		int color = gray(directional(q.shadeFace()));
		for (int k = 0; k < 4; k++) {
			out.colors[k] = color;
			out.lights[k] = withEmission(lc, q.lightEmission());
		}
	}

	private boolean faceCubic(final int stateId, final RenderQuad q, final Face d) {
		float[] p = q.positions();
		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		int axis = d.dx != 0 ? 0 : d.dy != 0 ? 1 : 2;
		for (int k = 0; k < 4; k++) {
			min = Math.min(min, p[k * 3 + axis]);
			max = Math.max(max, p[k * 3 + axis]);
		}
		if (min != max) {
			return false;
		}
		boolean positive = d.dx + d.dy + d.dz > 0;
		return (positive ? max > 0.9999f : min < 1.0e-4f) || this.facts.fullCollision(stateId);
	}

	/** Ambient Occlusion mit weichem Licht ({@code prepareQuadAmbientOcclusion}). */
	public void ambientOcclusion(final int stateId, final int x, final int y, final int z, final RenderQuad q, final Out out) {
		Face d = q.direction();
		float[] p = q.positions();
		float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (int k = 0; k < 4; k++) {
			for (int a = 0; a < 3; a++) {
				min[a] = Math.min(min[a], p[k * 3 + a]);
				max[a] = Math.max(max[a], p[k * 3 + a]);
			}
		}
		int axis = d.dx != 0 ? 0 : d.dy != 0 ? 1 : 2;
		int ua = axis == 0 ? 1 : 0;
		int ub = axis == 2 ? 1 : 2;
		boolean partial = min[ua] >= 1.0e-4f || min[ub] >= 1.0e-4f || max[ua] <= 0.9999f || max[ub] <= 0.9999f;
		boolean cubic = faceCubic(stateId, q, d);
		int bx = cubic ? x + d.dx : x;
		int by = cubic ? y + d.dy : y;
		int bz = cubic ? z + d.dz : z;
		Face[] c = CORNERS[d.ordinal()];
		int[] light = new int[4];
		float[] shade = new float[4];
		boolean[] permeable = new boolean[4];
		for (int i = 0; i < 4; i++) {
			int sx = bx + c[i].dx;
			int sy = by + c[i].dy;
			int sz = bz + c[i].dz;
			int s = state(sx, sy, sz);
			light[i] = lightCoords(s, sx, sy, sz);
			shade[i] = this.facts.shadeBrightness(s);
			permeable[i] = this.facts.lightPermeable(state(sx + d.dx, sy + d.dy, sz + d.dz));
		}
		float[] cornerShade = new float[4];
		int[] cornerLight = new int[4];
		// Reihenfolge: 02, 03, 12, 13 (bei zwei undurchlässigen Seiten Wert von Seite 0 – wie die Vorlage, auch für 12/13)
		int[][] pairs = {{0, 2}, {0, 3}, {1, 2}, {1, 3}};
		for (int k = 0; k < 4; k++) {
			int i = pairs[k][0];
			int j = pairs[k][1];
			if (!permeable[j] && !permeable[i]) {
				cornerShade[k] = shade[0];
				cornerLight[k] = light[0];
			} else {
				int sx = bx + c[i].dx + c[j].dx;
				int sy = by + c[i].dy + c[j].dy;
				int sz = bz + c[i].dz + c[j].dz;
				int s = state(sx, sy, sz);
				cornerShade[k] = this.facts.shadeBrightness(s);
				cornerLight[k] = lightCoords(s, sx, sy, sz);
			}
		}
		int lightCenter = lightCoords(stateId, x, y, z);
		int next = state(x + d.dx, y + d.dy, z + d.dz);
		if (cubic || !this.facts.solidRender(next)) {
			lightCenter = lightCoords(next, x + d.dx, y + d.dy, z + d.dz);
		}
		float shadeCenter = cubic ? this.facts.shadeBrightness(state(bx, by, bz)) : this.facts.shadeBrightness(stateId);
		// Eckwerte: L1 (Seite 0 & 3), L2 (0 & 2), L3 (1 & 2), L4 (1 & 3)
		float[] ls = {(shade[3] + shade[0] + cornerShade[1] + shadeCenter) * 0.25f, (shade[2] + shade[0] + cornerShade[0] + shadeCenter) * 0.25f,
			(shade[2] + shade[1] + cornerShade[2] + shadeCenter) * 0.25f, (shade[3] + shade[1] + cornerShade[3] + shadeCenter) * 0.25f};
		int[] ll = {smoothBlend(light[3], light[0], cornerLight[1], lightCenter), smoothBlend(light[2], light[0], cornerLight[0], lightCenter),
			smoothBlend(light[2], light[1], cornerLight[2], lightCenter), smoothBlend(light[3], light[1], cornerLight[3], lightCenter)};
		float dir = directional(q.shadeFace());
		float[] w = new float[4];
		for (int k = 0; k < 4; k++) {
			// Anteil zur Seite c0 entlang ihrer Achse (a) und zur Seite c2 entlang ihrer Achse (b); Teilflächen: Grenzen der Fläche
			float a = toward(c[0], p, k, min, max, partial);
			float b = toward(c[2], p, k, min, max, partial);
			w[0] = a * (1 - b); // L1: c0 & c3
			w[1] = a * b; // L2: c0 & c2
			w[2] = (1 - a) * b; // L3: c1 & c2
			w[3] = (1 - a) * (1 - b); // L4: c1 & c3
			float s;
			int lc;
			if (partial) {
				s = Math.max(0f, Math.min(1f, ls[0] * w[0] + ls[1] * w[1] + ls[2] * w[2] + ls[3] * w[3]));
				lc = weightedBlend(ll, w);
			} else {
				int corner = a >= 0.5f ? (b >= 0.5f ? 1 : 0) : (b >= 0.5f ? 2 : 3);
				s = ls[corner];
				lc = ll[corner];
			}
			out.colors[k] = scaleRgb(gray(s), dir);
			out.lights[k] = withEmission(lc, q.lightEmission());
		}
	}

	/**
	 * Lage des Vertex k zur Seite {@code f} (1 = auf der Seite f, 0 = gegenüber). Teilflächen: wie die Vorlage aus den
	 * Flächengrenzen (min/max) – der Vertex zählt zur Grenze, der er näher liegt.
	 */
	private static float toward(final Face f, final float[] p, final int k, final float[] min, final float[] max, final boolean partial) {
		int axis = f.dx != 0 ? 0 : f.dy != 0 ? 1 : 2;
		float v = p[k * 3 + axis];
		if (partial) {
			v = Math.abs(v - min[axis]) <= Math.abs(v - max[axis]) ? min[axis] : max[axis];
		}
		v = Math.max(0f, Math.min(1f, v));
		boolean positive = f.dx + f.dy + f.dz > 0;
		return positive ? v : 1 - v;
	}
}
