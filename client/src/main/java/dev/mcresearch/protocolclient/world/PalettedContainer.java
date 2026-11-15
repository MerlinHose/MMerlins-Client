package dev.mcresearch.protocolclient.world;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import java.util.Arrays;

/**
 * Dekodierter Paletten-Container (docs/18 §4). Speichert die globalen IDs aller Einträge:
 * Singleton als einzelner Wert, sonst als int-Array in Index-Reihenfolge {@code (y·n + z)·n + x}.
 */
public final class PalettedContainer {
	/** Container-Arten mit der Konfigurationstabelle aus {@code Strategy#createForBlockStates/ForBiomes}. */
	public enum Kind {
		BLOCK_STATES(4) {
			@Override
			Config config(final int sentBits, final int globalBits) {
				return switch (sentBits) {
					case 0 -> new Config(PaletteType.SINGLETON, 0);
					case 1, 2, 3, 4 -> new Config(PaletteType.LINEAR, 4);
					case 5, 6, 7, 8 -> new Config(PaletteType.HASHMAP, sentBits);
					default -> new Config(PaletteType.GLOBAL, globalBits);
				};
			}
		},
		BIOMES(2) {
			@Override
			Config config(final int sentBits, final int globalBits) {
				return switch (sentBits) {
					case 0 -> new Config(PaletteType.SINGLETON, 0);
					case 1, 2, 3 -> new Config(PaletteType.LINEAR, sentBits);
					default -> new Config(PaletteType.GLOBAL, globalBits);
				};
			}
		};

		final int bitsPerAxis;
		final int entries;

		Kind(final int bitsPerAxis) {
			this.bitsPerAxis = bitsPerAxis;
			this.entries = 1 << (bitsPerAxis * 3);
		}

		abstract Config config(int sentBits, int globalBits);

		public int entries() {
			return this.entries;
		}

		public int axisSize() {
			return 1 << this.bitsPerAxis;
		}

		/** {@code Strategy#getIndex}: {@code (y << b | z) << b | x}. */
		public int index(final int x, final int y, final int z) {
			int size = axisSize();
			if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size) {
				throw new IndexOutOfBoundsException("local position " + x + "," + y + "," + z + " outside 0.." + (size - 1));
			}
			return ((y << this.bitsPerAxis | z) << this.bitsPerAxis) | x;
		}
	}

	public enum PaletteType {
		SINGLETON, LINEAR, HASHMAP, GLOBAL
	}

	/** @param bitsInMemory Bits pro Eintrag im Long-Array (bestimmt dessen Länge) */
	record Config(PaletteType type, int bitsInMemory) {
	}

	private final Kind kind;
	private final PaletteType paletteType;
	private final int sentBits;
	private final int paletteSize;
	private int singleValue;
	private int[] values;

	private PalettedContainer(final Kind kind, final PaletteType type, final int sentBits, final int paletteSize, final int singleValue, final int[] values) {
		this.kind = kind;
		this.paletteType = type;
		this.sentBits = sentBits;
		this.paletteSize = paletteSize;
		this.singleValue = singleValue;
		this.values = values;
	}

	/** Ein Container mit einem einzigen Wert (z. B. für Tests oder leere Sektionen). */
	public static PalettedContainer single(final Kind kind, final int globalId) {
		return new PalettedContainer(kind, PaletteType.SINGLETON, 0, 1, globalId, null);
	}

	/**
	 * Liest einen Container: Byte bits, Palette, Long-Array fester Länge ohne Präfix (docs/18 §4.1).
	 *
	 * @param globalSize Größe der globalen Tabelle (Blockzustände bzw. Biom-Registry); IDs müssen kleiner sein
	 */
	public static PalettedContainer read(final PacketReader r, final Kind kind, final int globalSize) throws ProtocolException {
		if (globalSize <= 0) {
			throw new ProtocolException("Global palette for " + kind + " is empty");
		}
		int sentBits = r.readUnsignedByte();
		Config cfg = kind.config(sentBits, BlockRegistry.ceilLog2(globalSize));
		int[] palette;
		switch (cfg.type()) {
			case SINGLETON -> palette = new int[]{globalId(r, globalSize, kind)};
			case LINEAR, HASHMAP -> {
				int capacity = 1 << cfg.bitsInMemory();
				int size = r.readCount(capacity, 1, kind + " palette");
				if (size == 0) {
					throw new ProtocolException(kind + " palette is empty");
				}
				palette = new int[size];
				for (int i = 0; i < size; i++) {
					palette[i] = globalId(r, globalSize, kind);
				}
			}
			case GLOBAL -> palette = null;
			default -> throw new IllegalStateException();
		}
		int bits = cfg.bitsInMemory();
		int longs = bits == 0 ? 0 : (kind.entries + (64 / bits) - 1) / (64 / bits);
		if ((long) longs * 8 > r.remaining()) {
			throw new ProtocolException(kind + " data needs " + longs + " longs, only " + r.remaining() + " bytes left");
		}
		long[] data = new long[longs];
		for (int i = 0; i < longs; i++) {
			data[i] = r.readLong();
		}
		if (cfg.type() == PaletteType.SINGLETON) {
			return new PalettedContainer(kind, PaletteType.SINGLETON, sentBits, 1, palette[0], null);
		}
		// globale Tabelle mit nur einem Eintrag: 0 Bit (ZeroBitStorage), alle Einträge 0
		int[] raw = bits == 0 ? new int[kind.entries] : BitStorage.unpack(data, bits, kind.entries);
		int[] values = new int[kind.entries];
		for (int i = 0; i < raw.length; i++) {
			int v = raw[i];
			if (palette != null) {
				if (v >= palette.length) {
					throw new ProtocolException(kind + " entry " + i + " references palette index " + v + " of " + palette.length);
				}
				values[i] = palette[v];
			} else {
				if (v >= globalSize) {
					throw new ProtocolException(kind + " entry " + i + " has global id " + v + " >= " + globalSize);
				}
				values[i] = v;
			}
		}
		return new PalettedContainer(kind, cfg.type(), sentBits, palette == null ? 0 : palette.length, 0, values);
	}

	private static int globalId(final PacketReader r, final int globalSize, final Kind kind) throws ProtocolException {
		int id = r.readVarInt();
		if (id < 0 || id >= globalSize) {
			throw new ProtocolException(kind + " palette id " + id + " outside global table of " + globalSize);
		}
		return id;
	}

	public int get(final int x, final int y, final int z) {
		int index = this.kind.index(x, y, z);
		return this.values == null ? this.singleValue : this.values[index];
	}

	/** Setzt einen Eintrag (Blockupdate); ein Singleton wird dabei in ein Array umgewandelt. */
	public void set(final int x, final int y, final int z, final int globalId) {
		int index = this.kind.index(x, y, z);
		if (this.values == null) {
			if (globalId == this.singleValue) {
				return;
			}
			this.values = new int[this.kind.entries];
			Arrays.fill(this.values, this.singleValue);
		}
		this.values[index] = globalId;
	}

	/** Alle globalen IDs in Index-Reihenfolge (Kopie). */
	public int[] toArray() {
		if (this.values == null) {
			int[] all = new int[this.kind.entries];
			Arrays.fill(all, this.singleValue);
			return all;
		}
		return this.values.clone();
	}

	public Kind kind() {
		return this.kind;
	}

	public PaletteType paletteType() {
		return this.paletteType;
	}

	public int sentBits() {
		return this.sentBits;
	}

	public int paletteSize() {
		return this.paletteSize;
	}

	public boolean isUniform() {
		if (this.values == null) {
			return true;
		}
		for (int v : this.values) {
			if (v != this.values[0]) {
				return false;
			}
		}
		return true;
	}
}
