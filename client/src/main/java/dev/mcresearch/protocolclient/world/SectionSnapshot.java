package dev.mcresearch.protocolclient.world;

/**
 * Kopie einer 16³-Sektion mit {@value #MARGIN} Blöcken Rand aus den Nachbarn ({@value #SIZE}³) für das Meshing ohne
 * Weltsperre (docs/25 §4; M12: Rand 2 für Ambient Occlusion und Biommischung, docs/47 §3). Index
 * {@code (y·SIZE + z)·SIZE + x} mit lokalen Koordinaten −2…17.
 *
 * <ul>
 *   <li>{@code states}: Zustands-IDs; {@link #UNKNOWN_STATE} = Nachbarchunk nicht geladen.</li>
 *   <li>{@code light}: Himmelslicht (obere 4 Bit) und Blocklicht (untere 4 Bit) wie {@code Level#getBrightness}: fehlende
 *       Himmelsdaten → Wert der nächsthöheren Sektion mit Daten (deren unterste Zeile) bzw. 15; fehlendes Blocklicht → 0;
 *       nicht geladener Chunk → Himmel 15, Block 0.</li>
 *   <li>{@code biomes}: Biom-Registry-ID je Block nach dem Biom-Zoom (für die Tönung).</li>
 * </ul>
 */
public record SectionSnapshot(int chunkX, int sectionY, int chunkZ, int[] states, byte[] light, int[] biomes) {
	public static final int MARGIN = 2;
	public static final int SIZE = 16 + 2 * MARGIN;
	public static final int UNKNOWN_STATE = -1;

	/** Ohne Biome (Tests, Farbmodus): alle Zellen Biom 0. */
	public SectionSnapshot(final int chunkX, final int sectionY, final int chunkZ, final int[] states, final byte[] light) {
		this(chunkX, sectionY, chunkZ, states, light, new int[states.length]);
	}

	public static int index(final int lx, final int ly, final int lz) {
		return ((ly + MARGIN) * SIZE + (lz + MARGIN)) * SIZE + (lx + MARGIN);
	}

	public static boolean inside(final int lx, final int ly, final int lz) {
		return lx >= -MARGIN && lx < 16 + MARGIN && ly >= -MARGIN && ly < 16 + MARGIN && lz >= -MARGIN && lz < 16 + MARGIN;
	}

	public int state(final int lx, final int ly, final int lz) {
		return this.states[index(lx, ly, lz)];
	}

	/** max(Himmel, Block) – wie bis M11 (Bericht, Tests). */
	public int light(final int lx, final int ly, final int lz) {
		int v = this.light[index(lx, ly, lz)] & 255;
		return Math.max(v >> 4, v & 15);
	}

	public int sky(final int lx, final int ly, final int lz) {
		return (this.light[index(lx, ly, lz)] & 255) >> 4;
	}

	public int block(final int lx, final int ly, final int lz) {
		return this.light[index(lx, ly, lz)] & 15;
	}

	public int biome(final int lx, final int ly, final int lz) {
		return this.biomes[index(lx, ly, lz)];
	}

	public static byte pack(final int sky, final int block) {
		return (byte) ((sky & 15) << 4 | (block & 15));
	}

	/** Weltkoordinaten des Sektionsursprungs. */
	public int originX() {
		return this.chunkX * 16;
	}

	public int originY() {
		return this.sectionY * 16;
	}

	public int originZ() {
		return this.chunkZ * 16;
	}
}
