package dev.mcresearch.protocolclient.view;

import java.util.Arrays;

/**
 * CPU-Vertexdaten eines Meshes (docs/25 §2, M12: docs/47 §1), ohne OpenGL. Format je Vertex: x, y, z, u, v als float, Farbe als
 * RGBA-Bytes (AO × Richtungsschattierung × Tönung) und Licht als zwei 16-Bit-Werte (Blocklicht unten, Himmelslicht oben, je
 * 0…240 wie die Lichtkoordinaten {@code UV2} von Minecraft; Zwischenwerte durch weiches Licht) – 7 × 4 Byte = 28 Byte.
 * Dreiecke als Listen (je Quad zwei Dreiecke, 6 Vertices).
 */
public final class MeshBuilder {
	public static final int INTS_PER_VERTEX = 7;
	public static final int BYTES_PER_VERTEX = INTS_PER_VERTEX * 4;
	/** Volles Licht (Block 15, Himmel 15) – für Geometrie ohne Weltlicht (Overlay, Testwürfel). */
	public static final int FULL_BRIGHT = light(240, 240);
	private static final int[] FULL = {FULL_BRIGHT, FULL_BRIGHT, FULL_BRIGHT, FULL_BRIGHT};
	private int[] data;
	private int size;
	private final int maxVertices;

	public MeshBuilder(final int initialVertices, final int maxVertices) {
		this.data = new int[Math.max(6, initialVertices) * INTS_PER_VERTEX];
		this.maxVertices = maxVertices;
	}

	public MeshBuilder() {
		this(1024, 1 << 22);
	}

	/** Farbe als RGBA mit Komponenten 0–255. */
	public static int rgba(final int r, final int g, final int b, final int a) {
		return (r & 255) | (g & 255) << 8 | (b & 255) << 16 | (a & 255) << 24;
	}

	/** Lichtkoordinaten: Block- und Himmelslicht je 0…240 (Stufe × 16). */
	public static int light(final int block, final int sky) {
		return (block & 0xFFFF) | (sky & 0xFFFF) << 16;
	}

	private void vertex(final float x, final float y, final float z, final float u, final float v, final int color, final int light) {
		if (this.size / INTS_PER_VERTEX >= this.maxVertices) {
			throw new IllegalStateException("mesh exceeds " + this.maxVertices + " vertices");
		}
		if (this.size + INTS_PER_VERTEX > this.data.length) {
			this.data = Arrays.copyOf(this.data, this.data.length * 2);
		}
		int i = this.size;
		this.data[i] = Float.floatToRawIntBits(x);
		this.data[i + 1] = Float.floatToRawIntBits(y);
		this.data[i + 2] = Float.floatToRawIntBits(z);
		this.data[i + 3] = Float.floatToRawIntBits(u);
		this.data[i + 4] = Float.floatToRawIntBits(v);
		this.data[i + 5] = color;
		this.data[i + 6] = light;
		this.size += INTS_PER_VERTEX;
	}

	/** Viereck mit vollem Licht (Geometrie ohne Weltlicht). */
	public void quad(final float[] p, final float[] uv, final int[] colors) {
		quad(p, uv, colors, FULL);
	}

	/**
	 * Viereck aus vier Ecken gegen den Uhrzeigersinn (von außen gesehen, Vorderseite für Backface-Culling) mit je eigener
	 * Farbe und Lichtkoordinate; UV pro Ecke. Dreiecke 0-1-2 und 0-2-3.
	 */
	public void quad(final float[] p, final float[] uv, final int[] colors, final int[] lights) {
		int[] order = {0, 1, 2, 0, 2, 3};
		for (int k : order) {
			vertex(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], uv[k * 2], uv[k * 2 + 1], colors[k], lights[k]);
		}
	}

	/**
	 * Wie {@link #quad(float[], float[], int[], int[])}, wahlweise geteilt entlang der Diagonale 1–3 (Dreiecke 0-1-3, 1-2-3).
	 * Vanilla teilt jedes Quad entlang seiner Vertices 0–2 ({@code FaceInfo}-Reihenfolge); in der Eckenreihenfolge von
	 * {@link Face#corners} ist das für alle sechs Seiten die Diagonale 1–3. Bei Ambient Occlusion bestimmt die Diagonale die
	 * Interpolation sichtbar, daher für Blockmodelle immer {@code diagonal13 = true}.
	 */
	public void quad(final float[] p, final float[] uv, final int[] colors, final int[] lights, final boolean diagonal13) {
		int[] order = diagonal13 ? new int[]{0, 1, 3, 1, 2, 3} : new int[]{0, 1, 2, 0, 2, 3};
		for (int k : order) {
			vertex(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], uv[k * 2], uv[k * 2 + 1], colors[k], lights[k]);
		}
	}

	public int vertexCount() {
		return this.size / INTS_PER_VERTEX;
	}

	public boolean isEmpty() {
		return this.size == 0;
	}

	/** Vertexdaten (Kopie in exakter Länge). */
	public int[] toArray() {
		return Arrays.copyOf(this.data, this.size);
	}

	public float x(final int vertex) {
		return Float.intBitsToFloat(this.data[vertex * INTS_PER_VERTEX]);
	}

	public float y(final int vertex) {
		return Float.intBitsToFloat(this.data[vertex * INTS_PER_VERTEX + 1]);
	}

	public float z(final int vertex) {
		return Float.intBitsToFloat(this.data[vertex * INTS_PER_VERTEX + 2]);
	}

	public int color(final int vertex) {
		return this.data[vertex * INTS_PER_VERTEX + 5];
	}

	public int lightCoords(final int vertex) {
		return this.data[vertex * INTS_PER_VERTEX + 6];
	}

	/** Achsenparalleler Würfel mit einer Farbe je Seite (Test-Würfel, Debug-Ersatzmodell). */
	public static void cube(final MeshBuilder b, final float x0, final float y0, final float z0, final float x1, final float y1, final float z1,
		final int[] faceColors) {
		float[] uv = {0, 1, 1, 1, 1, 0, 0, 0};
		for (Face f : Face.values()) {
			int c = faceColors[f.ordinal()];
			b.quad(f.corners(x0, y0, z0, x1, y1, z1), uv, new int[]{c, c, c, c});
		}
	}
}
