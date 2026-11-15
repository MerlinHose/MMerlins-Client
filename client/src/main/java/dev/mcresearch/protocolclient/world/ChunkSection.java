package dev.mcresearch.protocolclient.world;

/**
 * Eine 16×16×16-Sektion (docs/18 §3): Blockzähler wie gesendet, Blockzustände (4096) und Biome (4×4×4).
 *
 * <p>{@code nonEmptyBlockCount} wird bei Blockänderungen wie in {@code LevelChunkSection#setBlockState} nachgeführt
 * (nicht-Luft-Zähler). {@code fluidCount} bleibt der gesendete Wert, weil Fluidzustände nicht modelliert werden.
 */
public final class ChunkSection {
	private int nonEmptyBlockCount;
	private final int sentNonEmptyBlockCount;
	private final int fluidCount;
	private final PalettedContainer states;
	private PalettedContainer biomes;

	public ChunkSection(final int nonEmptyBlockCount, final int fluidCount, final PalettedContainer states, final PalettedContainer biomes) {
		this.nonEmptyBlockCount = nonEmptyBlockCount;
		this.sentNonEmptyBlockCount = nonEmptyBlockCount;
		this.fluidCount = fluidCount;
		this.states = states;
		this.biomes = biomes;
	}

	public int blockState(final int x, final int y, final int z) {
		return this.states.get(x, y, z);
	}

	/** Setzt einen Zustand und führt den Nicht-Luft-Zähler nach; liefert die vorherige ID. */
	public int setBlockState(final int x, final int y, final int z, final int stateId, final BlockRegistry registry) {
		int old = this.states.get(x, y, z);
		if (old == stateId) {
			return old;
		}
		this.states.set(x, y, z, stateId);
		if (!registry.state(old).isAir()) {
			this.nonEmptyBlockCount--;
		}
		if (!registry.state(stateId).isAir()) {
			this.nonEmptyBlockCount++;
		}
		return old;
	}

	/** Biom-ID an Biom-Zellkoordinaten (je 0–3). */
	public int biome(final int qx, final int qy, final int qz) {
		return this.biomes.get(qx, qy, qz);
	}

	void replaceBiomes(final PalettedContainer biomes) {
		this.biomes = biomes;
	}

	/** Zählt die Nicht-Luft-Blöcke aus den dekodierten Daten (Abgleich mit dem gesendeten Zähler). */
	public int countNonAir(final BlockRegistry registry) {
		if (this.states.paletteType() == PalettedContainer.PaletteType.SINGLETON) {
			return registry.state(this.states.get(0, 0, 0)).isAir() ? 0 : 4096;
		}
		int n = 0;
		for (int id : this.states.toArray()) {
			if (!registry.state(id).isAir()) {
				n++;
			}
		}
		return n;
	}

	public int nonEmptyBlockCount() {
		return this.nonEmptyBlockCount;
	}

	public int sentNonEmptyBlockCount() {
		return this.sentNonEmptyBlockCount;
	}

	public int fluidCount() {
		return this.fluidCount;
	}

	public PalettedContainer states() {
		return this.states;
	}

	public PalettedContainer biomes() {
		return this.biomes;
	}

	public boolean hasOnlyAir() {
		return this.nonEmptyBlockCount == 0;
	}
}
