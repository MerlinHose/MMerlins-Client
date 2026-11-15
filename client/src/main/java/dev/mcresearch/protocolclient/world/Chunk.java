package dev.mcresearch.protocolclient.world;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ein geladener Chunk (docs/19 §3): Sektionen von {@code minSectionY} aufwärts, Heightmaps wie gesendet,
 * Block-Entities und Licht-Nibble-Arrays für {@code sections + 2} Lichtsektionen.
 */
public final class Chunk {
	/** Markierung für eine als leer gemeldete Lichtsektion (alle Werte 0). */
	static final byte[] EMPTY_LIGHT = new byte[0];

	private final int x;
	private final int z;
	private final DimensionInfo dimension;
	private final ChunkSection[] sections;
	private final Map<Integer, long[]> heightmaps;
	private final Map<Integer, BlockEntity> blockEntities = new TreeMap<>();
	private final byte[][] skyLight;
	private final byte[][] blockLight;
	private final int dataBytes;

	Chunk(final int x, final int z, final DimensionInfo dimension, final ChunkSection[] sections, final Map<Integer, long[]> heightmaps,
		final int dataBytes) {
		this.x = x;
		this.z = z;
		this.dimension = dimension;
		this.sections = sections;
		this.heightmaps = Map.copyOf(heightmaps);
		this.skyLight = new byte[sections.length + 2][];
		this.blockLight = new byte[sections.length + 2][];
		this.dataBytes = dataBytes;
	}

	public int x() {
		return this.x;
	}

	public int z() {
		return this.z;
	}

	public DimensionInfo dimension() {
		return this.dimension;
	}

	public int sectionCount() {
		return this.sections.length;
	}

	public int minSectionY() {
		return this.dimension.minY() >> 4;
	}

	/** Sektion nach Index (0 = unterste). */
	public ChunkSection section(final int index) {
		return this.sections[index];
	}

	/** Sektion nach Sektions-Y (absolut); {@code null} außerhalb der Welthöhe. */
	public ChunkSection sectionAtY(final int sectionY) {
		int index = sectionY - minSectionY();
		return index < 0 || index >= this.sections.length ? null : this.sections[index];
	}

	public int dataBytes() {
		return this.dataBytes;
	}

	public boolean containsY(final int y) {
		return y >= this.dimension.minY() && y < this.dimension.minY() + this.dimension.height();
	}

	/** Zustands-ID an absoluten Koordinaten; x/z müssen in diesem Chunk liegen, y in der Welthöhe. */
	public int blockState(final int bx, final int by, final int bz) {
		checkInside(bx, by, bz);
		return this.sections[(by - this.dimension.minY()) >> 4].blockState(bx & 15, by & 15, bz & 15);
	}

	int setBlockState(final int bx, final int by, final int bz, final int stateId, final BlockRegistry registry) {
		checkInside(bx, by, bz);
		return this.sections[(by - this.dimension.minY()) >> 4].setBlockState(bx & 15, by & 15, bz & 15, stateId, registry);
	}

	/** M12: Sektion der Höhe y enthält nur Luft (schnelle Spaltensuche). */
	public boolean sectionEmpty(final int by) {
		return this.sections[(by - this.dimension.minY()) >> 4].hasOnlyAir();
	}

	/** Biom-ID an absoluten Blockkoordinaten (Biomzelle 4×4×4). */
	public int biome(final int bx, final int by, final int bz) {
		checkInside(bx, by, bz);
		return this.sections[(by - this.dimension.minY()) >> 4].biome((bx & 15) >> 2, (by & 15) >> 2, (bz & 15) >> 2);
	}

	private void checkInside(final int bx, final int by, final int bz) {
		if (Math.floorDiv(bx, 16) != this.x || Math.floorDiv(bz, 16) != this.z) {
			throw new IllegalArgumentException("Block " + bx + "," + bz + " is not in chunk " + this.x + "," + this.z);
		}
		if (!containsY(by)) {
			throw new IllegalArgumentException("y=" + by + " outside " + this.dimension.minY() + ".." + (this.dimension.minY() + this.dimension.height() - 1));
		}
	}

	// ----- Heightmaps -----

	public Map<Integer, long[]> heightmaps() {
		return this.heightmaps;
	}

	/**
	 * Heightmap-Wert (relativ zu {@code min_y}) einer Spalte, so wie der Server ihn beim Laden gesendet hat; −1, wenn der
	 * Typ fehlt. Spätere Blockupdates aktualisieren die Heightmap nicht (docs/19 §5).
	 */
	public int heightmap(final int type, final int localX, final int localZ) throws ProtocolException {
		long[] raw = this.heightmaps.get(type);
		if (raw == null) {
			return -1;
		}
		int bits = this.dimension.heightmapBits();
		if (raw.length != BitStorage.longsFor(bits, 256)) {
			throw new ProtocolException("Heightmap " + type + " has " + raw.length + " longs, expected " + BitStorage.longsFor(bits, 256));
		}
		int perLong = 64 / bits;
		int i = localX + localZ * 16;
		return (int) ((raw[i / perLong] >>> ((i % perLong) * bits)) & ((1L << bits) - 1));
	}

	// ----- Block-Entities -----

	static int localKey(final int bx, final int by, final int bz, final int minY) {
		return ((by - minY) << 8) | ((bz & 15) << 4) | (bx & 15);
	}

	void putBlockEntity(final BlockEntity be) {
		this.blockEntities.put(localKey(be.x(), be.y(), be.z(), this.dimension.minY()), be);
	}

	BlockEntity removeBlockEntity(final int bx, final int by, final int bz) {
		return this.blockEntities.remove(localKey(bx, by, bz, this.dimension.minY()));
	}

	public BlockEntity blockEntity(final int bx, final int by, final int bz) {
		return this.blockEntities.get(localKey(bx, by, bz, this.dimension.minY()));
	}

	public Map<Integer, BlockEntity> blockEntities() {
		return Collections.unmodifiableMap(this.blockEntities);
	}

	// ----- Licht -----

	public int lightSectionCount() {
		return this.sections.length + 2;
	}

	/** Setzt die Arrays einer Lichtsektion (Index 0 = Sektion {@code minSectionY − 1}); {@code null} = unbekannt. */
	void setLight(final boolean sky, final int lightIndex, final byte[] data) {
		(sky ? this.skyLight : this.blockLight)[lightIndex] = data;
	}

	public boolean hasLight(final boolean sky, final int lightIndex) {
		return (sky ? this.skyLight : this.blockLight)[lightIndex] != null;
	}

	public boolean isLightEmpty(final boolean sky, final int lightIndex) {
		return (sky ? this.skyLight : this.blockLight)[lightIndex] == EMPTY_LIGHT;
	}

	/**
	 * Lichtwert 0–15 an absoluten Koordinaten; −1, wenn für die Sektion keine Daten vorliegen.
	 * Nibble-Index {@code y<<8 | z<<4 | x}, gerader Index im unteren Nibble ({@code DataLayer}).
	 */
	public int light(final boolean sky, final int bx, final int by, final int bz) {
		if (Math.floorDiv(bx, 16) != this.x || Math.floorDiv(bz, 16) != this.z) {
			throw new IllegalArgumentException("Block not in chunk");
		}
		int lightIndex = (by >> 4) - minSectionY() + 1;
		if (lightIndex < 0 || lightIndex >= lightSectionCount()) {
			return -1;
		}
		byte[] data = (sky ? this.skyLight : this.blockLight)[lightIndex];
		if (data == null) {
			return -1;
		}
		if (data == EMPTY_LIGHT) {
			return 0;
		}
		int index = ((by & 15) << 8) | ((bz & 15) << 4) | (bx & 15);
		return (data[index >> 1] >> ((index & 1) * 4)) & 15;
	}

	/**
	 * M12: Helligkeit für die Darstellung wie {@code Level#getBrightness} (docs/47 §3.1): Blocklicht ohne Daten 0; Himmelslicht
	 * ohne Daten aus der nächsthöheren Sektion mit Daten (deren unterste Zeile, {@code SkyLightSectionStorage#getLightValue}),
	 * oberhalb aller Daten 15.
	 */
	public int renderLight(final boolean sky, final int bx, final int by, final int bz) {
		int v = light(sky, bx, by, bz);
		if (v >= 0) {
			return v;
		}
		if (!sky) {
			return 0;
		}
		int lightIndex = (by >> 4) - minSectionY() + 1;
		for (int li = Math.max(lightIndex + 1, 0); li < lightSectionCount(); li++) {
			byte[] data = this.skyLight[li];
			if (data != null) {
				if (data == EMPTY_LIGHT) {
					return 0;
				}
				int index = ((bz & 15) << 4) | (bx & 15); // y = 0 der gefundenen Sektion
				return (data[index >> 1] >> ((index & 1) * 4)) & 15;
			}
		}
		return 15;
	}

	// ----- M7-Korrektur: Lichtneuberechnung bei Blockänderungen (ClientLightEngine, docs/29 §3.3) -----

	private int[] skySources;

	/** {@code storingLightForSection}: für diese Lichtsektion liegen Daten vor (auch „leer“ gemeldete). */
	boolean storingLight(final boolean sky, final int lightIndex) {
		return lightIndex >= 0 && lightIndex < lightSectionCount() && (sky ? this.skyLight : this.blockLight)[lightIndex] != null;
	}

	/** {@code getStoredLevel}: gespeicherter Wert, 0 ohne Daten. */
	int storedLight(final boolean sky, final int bx, final int by, final int bz) {
		int v = light(sky, bx, by, bz);
		return Math.max(v, 0);
	}

	/** {@code setStoredLevel}; „leer“ gemeldete Sektionen werden dabei zu einem echten Array. Nur bei {@link #storingLight}. */
	void setStoredLight(final boolean sky, final int bx, final int by, final int bz, final int level) {
		byte[][] layers = sky ? this.skyLight : this.blockLight;
		int lightIndex = (by >> 4) - minSectionY() + 1;
		byte[] data = layers[lightIndex];
		if (data == EMPTY_LIGHT) {
			if (level == 0) {
				return;
			}
			data = new byte[2048];
			layers[lightIndex] = data;
		}
		int index = ((by & 15) << 8) | ((bz & 15) << 4) | (bx & 15);
		int shift = (index & 1) * 4;
		data[index >> 1] = (byte) ((data[index >> 1] & ~(15 << shift)) | ((level & 15) << shift));
	}

	int[] skySources() {
		return this.skySources;
	}

	void setSkySources(final int[] sources) {
		this.skySources = sources;
	}

	public int lightSectionsWithData(final boolean sky) {
		int n = 0;
		for (byte[] a : sky ? this.skyLight : this.blockLight) {
			if (a != null && a != EMPTY_LIGHT) {
				n++;
			}
		}
		return n;
	}
}
