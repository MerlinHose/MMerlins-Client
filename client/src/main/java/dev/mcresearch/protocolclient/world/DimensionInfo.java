package dev.mcresearch.protocolclient.world;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import java.util.Map;

/**
 * Höhenbereich einer Dimension aus dem Registry-Eintrag {@code minecraft:dimension_type}
 * (Felder {@code min_y}, {@code height}; {@code DimensionType.java:90-91}).
 */
public record DimensionInfo(String id, int minY, int height) {
	/** Wertebereiche wie in {@code DimensionType#MIN_Y/Y_SIZE}: −2032 … 2031, Höhe 16 … 4064, Vielfache von 16. */
	public DimensionInfo {
		if (minY < -2032 || minY > 2031 || height < 16 || height > 4064 || minY % 16 != 0 || height % 16 != 0) {
			throw new IllegalArgumentException("Invalid dimension bounds min_y=" + minY + " height=" + height);
		}
	}

	public static DimensionInfo fromNbt(final String id, final Object nbt) throws ProtocolException {
		if (!(nbt instanceof Map<?, ?> map) || !(map.get("min_y") instanceof Integer minY) || !(map.get("height") instanceof Integer height)) {
			throw new ProtocolException("dimension_type " + id + " lacks int fields min_y/height");
		}
		try {
			return new DimensionInfo(id, minY, height);
		} catch (IllegalArgumentException e) {
			throw new ProtocolException(e.getMessage(), e);
		}
	}

	/** Bits pro Heightmap-Wert: {@code Mth.ceillog2(height + 1)} ({@code Heightmap.java:37}). */
	public int heightmapBits() {
		return 32 - Integer.numberOfLeadingZeros(this.height); // = ceil(log2(height + 1))
	}
}
