package dev.mcresearch.protocolclient.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** M12 (docs/47 §4.2): Abstandsfunktion des Biom-Zooms gegen {@code BiomeManager#getFiddledDistance} (RenderMathCheck). */
class BiomeZoomTest {
	@Test
	void fiddledDistanceMatchesVanilla() {
		Object[][] ref = {
			{0L, -50, -4, 81, 0.00, -0.50, 0.75, 1.947924222946}, {0L, -13, -1, 52, 0.25, -0.25, 0.50, 0.385942211151},
			{0L, 24, 2, 23, 0.50, 0.00, 0.25, 0.505182514191}, {0L, 61, 5, -6, 0.75, 0.25, 0.00, 0.503560132980},
			{123456789L, -50, -4, 81, 0.00, -0.50, 0.75, 0.897855615616}, {123456789L, -13, -1, 52, 0.25, -0.25, 0.50, 0.209360246658},
			{123456789L, 24, 2, 23, 0.50, 0.00, 0.25, 0.335126686096}, {123456789L, 61, 5, -6, 0.75, 0.25, 0.00, 0.188043346405},
			{-8022371985442418345L, -50, -4, 81, 0.00, -0.50, 0.75, 0.858692445755},
			{-8022371985442418345L, -13, -1, 52, 0.25, -0.25, 0.50, 0.465122308731},
			{-8022371985442418345L, 24, 2, 23, 0.50, 0.00, 0.25, 0.333333940506},
			{-8022371985442418345L, 61, 5, -6, 0.75, 0.25, 0.00, 1.111426029205}};
		for (Object[] r : ref) {
			double d = BiomeZoom.fiddledDistance((long) r[0], (int) r[1], (int) r[2], (int) r[3], (double) r[4], (double) r[5], (double) r[6]);
			assertEquals((double) r[7], d, 1e-9);
		}
	}

	@Test
	void uniformNoiseGridGivesThatBiomeEverywhere() {
		for (int x = -20; x < 20; x += 3) {
			for (int z = -20; z < 20; z += 5) {
				assertEquals(7, BiomeZoom.biome(42L, x, 64, z, (qx, qy, qz) -> 7));
			}
		}
	}

	@Test
	void boundaryIsFuzzyButNearTheQuartBorder() {
		// Rasterbiom 1 für qx < 0, 2 für qx ≥ 0: Blockgrenze liegt innerhalb ±4 Blöcken um x = 0
		for (int z = -8; z < 8; z++) {
			assertEquals(1, BiomeZoom.biome(42L, -7, 64, z, (qx, qy, qz) -> qx < 0 ? 1 : 2));
			assertEquals(2, BiomeZoom.biome(42L, 6, 64, z, (qx, qy, qz) -> qx < 0 ? 1 : 2));
		}
	}
}
