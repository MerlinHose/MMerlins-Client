package dev.mcresearch.protocolclient.world;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/** Erzeugt Heightmap-Daten wie {@code SimpleBitStorage} (Werte nicht über Long-Grenzen verteilt). */
public final class TerrainTestSupport {
	public static final DimensionInfo OVERWORLD = new DimensionInfo("minecraft:overworld", -64, 384);

	private TerrainTestSupport() {
	}

	public static long[] pack(final int[] values, final int bits) {
		int perLong = 64 / bits;
		long[] out = new long[(values.length + perLong - 1) / perLong];
		for (int i = 0; i < values.length; i++) {
			out[i / perLong] |= ((long) values[i] & ((1L << bits) - 1)) << ((i % perLong) * bits);
		}
		return out;
	}

	/** Chunk mit Bodenhöhe f(weltX, weltZ) (Weltkoordinate der ersten freien Höhe). */
	public static Clientbound.ChunkHeightmaps chunk(final DimensionInfo dim, final int chunkX, final int chunkZ, final IntBinaryOperator groundY) {
		int[] values = new int[256];
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				values[lx + lz * 16] = groundY.applyAsInt(chunkX * 16 + lx, chunkZ * 16 + lz) - dim.minY();
			}
		}
		return new Clientbound.ChunkHeightmaps(chunkX, chunkZ, Map.of(Clientbound.ChunkHeightmaps.MOTION_BLOCKING, pack(values, dim.heightmapBits())), 0);
	}

	/** Terrain aus mehreren Chunks rund um den Ursprung. */
	public static TerrainCache terrain(final IntBinaryOperator groundY, final int radius) throws ProtocolException {
		TerrainCache t = new TerrainCache(OVERWORLD);
		for (int cx = -radius; cx <= radius; cx++) {
			for (int cz = -radius; cz <= radius; cz++) {
				t.accept(chunk(OVERWORLD, cx, cz, groundY));
			}
		}
		return t;
	}
}
