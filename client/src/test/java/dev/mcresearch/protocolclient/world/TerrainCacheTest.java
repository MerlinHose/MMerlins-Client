package dev.mcresearch.protocolclient.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerrainCacheTest {
	@Test
	void heightmapBitsLikeVanilla() {
		// Mth.ceillog2(height + 1)
		assertEquals(9, TerrainTestSupport.OVERWORLD.heightmapBits()); // 384 -> 9 Bit, 7 Werte/Long, 37 Longs
		assertEquals(9, new DimensionInfo("x:n", 0, 256).heightmapBits());
		assertEquals(5, new DimensionInfo("x:s", 0, 16).heightmapBits());
		assertEquals(12, new DimensionInfo("x:max", -2032, 4064).heightmapBits()); // ceil(log2(4065)) = 12
	}

	@Test
	void decodesGroundHeightsIncludingNegativeCoordinates() throws ProtocolException {
		TerrainCache t = TerrainTestSupport.terrain((x, z) -> 64 + Math.floorMod(x + 2 * z, 7), 1);
		assertEquals(64 + Math.floorMod(5 + 2 * 3, 7), t.groundY(5.7, 3.2).getAsInt());
		assertEquals(64 + Math.floorMod(-1 + 2 * -16, 7), t.groundY(-0.5, -15.5).getAsInt());
		assertEquals(9, t.loadedChunks());
		assertTrue(t.groundY(100, 100).isEmpty());
		t.forget(0, 0);
		assertTrue(t.groundY(5, 5).isEmpty());
	}

	@Test
	void rejectsWrongLengthAndOutOfRangeValues() {
		TerrainCache t = new TerrainCache(TerrainTestSupport.OVERWORLD);
		assertThrows(ProtocolException.class, () -> t.accept(new Clientbound.ChunkHeightmaps(0, 0, Map.of(4, new long[36]), 0)));
		int[] tooHigh = new int[256];
		tooHigh[0] = 400; // > height 384
		assertThrows(ProtocolException.class, () -> t.accept(new Clientbound.ChunkHeightmaps(0, 0, Map.of(4, TerrainTestSupport.pack(tooHigh, 9)), 0)));
	}

	@Test
	void missingMotionBlockingIsIgnored() throws ProtocolException {
		TerrainCache t = new TerrainCache(TerrainTestSupport.OVERWORLD);
		t.accept(new Clientbound.ChunkHeightmaps(0, 0, Map.of(1, new long[37]), 0));
		assertEquals(0, t.loadedChunks());
	}

	@Test
	void dimensionInfoFromRegistryNbt() throws ProtocolException {
		DimensionInfo d = DimensionInfo.fromNbt("minecraft:overworld", Map.of("min_y", -64, "height", 384, "logical_height", 384));
		assertEquals(-64, d.minY());
		assertThrows(ProtocolException.class, () -> DimensionInfo.fromNbt("x:y", Map.of("min_y", -64)));
		assertThrows(ProtocolException.class, () -> DimensionInfo.fromNbt("x:y", Map.of("min_y", -60, "height", 384)));
		assertThrows(ProtocolException.class, () -> DimensionInfo.fromNbt("x:y", List.of()));
	}
}
