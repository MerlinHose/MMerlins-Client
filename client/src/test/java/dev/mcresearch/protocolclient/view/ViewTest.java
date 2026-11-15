package dev.mcresearch.protocolclient.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.ChunkTestSupport;
import dev.mcresearch.protocolclient.world.SectionSnapshot;
import dev.mcresearch.protocolclient.world.WorldListener;
import dev.mcresearch.protocolclient.world.WorldState;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Kamera-Mathematik, Flächenorientierung und Chunk-Meshing ohne GPU (docs/25 §10). */
class ViewTest {
	private static final BlockRegistry REG = BlockRegistry.get();
	private static final ColorModels COLORS = new ColorModels(REG);
	private static final int STONE = 1;
	private static final int WATER = REG.idOf("minecraft:water", Map.of("level", "0"));

	// ----- Kamera -----

	@Test
	void yawZeroLooksSouthAndPitchDownLooksDown() {
		Mat4 view = Mat4.minecraftView(0, 0, 0, 0f, 0f);
		float[] front = view.transform(0, 0, 5); // +Z (Süden) bei Yaw 0
		assertEquals(-5f, front[2], 1e-5, "in front = −Z in view space");
		assertEquals(0f, front[0], 1e-5);
		float[] left = view.transform(1, 0, 0); // Yaw 0: links = +X
		assertTrue(left[0] < 0, "+X appears on the left");
		float[] west = Mat4.minecraftView(0, 0, 0, 90f, 0f).transform(-5, 0, 0);
		assertEquals(-5f, west[2], 1e-5, "yaw 90 looks towards −X (west)");
		float[] below = Mat4.minecraftView(0, 0, 0, 0f, 90f).transform(0, -5, 0);
		assertEquals(-5f, below[2], 1e-5, "pitch 90 looks straight down");
	}

	@Test
	void frustumKeepsVisibleAndRejectsBehind() {
		Mat4 vp = Mat4.perspective(70, 16f / 9f, 0.05f, 100f).multiply(Mat4.minecraftView(0, 0, 0, 0f, 0f));
		Frustum f = new Frustum(vp);
		assertTrue(f.intersects(-1, -1, 10, 1, 1, 12), "block 10 m ahead");
		assertFalse(f.intersects(-1, -1, -12, 1, 1, -10), "block behind");
		assertFalse(f.intersects(-1, -1, 200, 1, 1, 202), "beyond far plane");
		assertTrue(f.intersects(-20, -20, -20, 20, 20, 20), "box containing the camera");
		assertThrows(IllegalArgumentException.class, () -> Mat4.perspective(0, 1, 0.1f, 10));
		assertThrows(IllegalArgumentException.class, () -> Mat4.perspective(70, 1, 10, 1));
	}

	@Test
	void allFacesAreCounterClockwiseFromOutside() {
		for (Face f : Face.values()) {
			float[] p = f.corners(0, 0, 0, 1, 1, 1);
			float ax = p[3] - p[0];
			float ay = p[4] - p[1];
			float az = p[5] - p[2];
			float bx = p[6] - p[0];
			float by = p[7] - p[1];
			float bz = p[8] - p[2];
			float nx = ay * bz - az * by;
			float ny = az * bx - ax * bz;
			float nz = ax * by - ay * bx;
			assertEquals(f.dx, Math.signum(nx), 1e-6, f.name());
			assertEquals(f.dy, Math.signum(ny), 1e-6, f.name());
			assertEquals(f.dz, Math.signum(nz), 1e-6, f.name());
		}
	}

	// ----- Meshing -----

	/** Snapshot mit frei gesetzten Zellen (lokal −2…17, M12), sonst Luft, Himmelslicht 15. */
	private static SectionSnapshot snapshot(final Map<int[], Integer> blocks, final int defaultState) {
		int n = SectionSnapshot.SIZE;
		int[] states = new int[n * n * n];
		Arrays.fill(states, defaultState);
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					states[SectionSnapshot.index(x, y, z)] = 0;
				}
			}
		}
		blocks.forEach((p, s) -> states[SectionSnapshot.index(p[0], p[1], p[2])] = s);
		byte[] light = new byte[states.length];
		Arrays.fill(light, SectionSnapshot.pack(15, 0));
		return new SectionSnapshot(-1, -1, -1, states, light);
	}

	private static Map<int[], Integer> blocks(final int[]... pos) {
		Map<int[], Integer> m = new HashMap<>();
		for (int[] p : pos) {
			m.put(new int[]{p[0], p[1], p[2]}, p.length > 3 ? p[3] : STONE);
		}
		return m;
	}

	@Test
	void singleBlockHasSixFacesAndNeighboursHideSharedFaces() {
		SectionMesher.Result one = SectionMesher.build(snapshot(blocks(new int[]{5, 5, 5}), 0), COLORS);
		assertEquals(6, one.quadsEmitted());
		assertEquals(36, one.opaque().length / MeshBuilder.INTS_PER_VERTEX);
		SectionMesher.Result two = SectionMesher.build(snapshot(blocks(new int[]{5, 5, 5}, new int[]{6, 5, 5}), 0), COLORS);
		assertEquals(10, two.quadsEmitted(), "two touching cubes: 12 − 2 hidden faces");
		assertEquals(2, two.quadsCulled());
		List<int[]> list = new ArrayList<>();
		for (int x = 0; x < 3; x++) {
			for (int y = 0; y < 3; y++) {
				for (int z = 0; z < 3; z++) {
					list.add(new int[]{x + 4, y + 4, z + 4});
				}
			}
		}
		SectionMesher.Result solid = SectionMesher.build(snapshot(blocks(list.toArray(int[][]::new)), 0), COLORS);
		assertEquals(54, solid.quadsEmitted(), "3×3×3 block: only the 54 outer faces");
		assertEquals(27 * 6 - 54, solid.quadsCulled(), "all inner faces culled");
	}

	@Test
	void sectionBorderUsesNeighbourCellsAndUnloadedNeighboursCountAsAir() {
		// Block bei x=15, Nachbarsektion (x=16) voller Stein → Ostfläche entfällt
		SectionMesher.Result border = SectionMesher.build(snapshot(blocks(new int[]{15, 3, 3}, new int[]{16, 3, 3}), 0), COLORS);
		assertEquals(5, border.quadsEmitted());
		// nicht geladener Nachbar (UNKNOWN) zählt wie Luft: Fläche bleibt sichtbar
		SectionMesher.Result unknown = SectionMesher.build(snapshot(blocks(new int[]{15, 3, 3}), SectionSnapshot.UNKNOWN_STATE), COLORS);
		assertEquals(6, unknown.quadsEmitted());
	}

	@Test
	void waterIsTranslucentAndCullsOnlyAgainstWater() {
		SectionMesher.Result w = SectionMesher.build(snapshot(blocks(new int[]{2, 2, 2, WATER}, new int[]{3, 2, 2, WATER}), 0), COLORS);
		assertEquals(0, w.opaque().length);
		assertTrue(w.translucent().length > 0);
		// Farbmodus: Wasser verdeckt nichts – Stein neben Wasser behält seine Fläche
		SectionMesher.Result mixed = SectionMesher.build(snapshot(blocks(new int[]{2, 2, 2, WATER}, new int[]{3, 2, 2}), 0), COLORS);
		assertEquals(6, mixed.opaque().length / MeshBuilder.INTS_PER_VERTEX / 6);
	}

	@Test
	void positionsAreRelativeToTheSectionOriginAndShadedByDirection() {
		SectionMesher.Result r = SectionMesher.build(snapshot(blocks(new int[]{0, 0, 0}), 0), COLORS);
		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		int[] d = r.opaque();
		for (int v = 0; v < d.length / MeshBuilder.INTS_PER_VERTEX; v++) {
			for (int a = 0; a < 3; a++) {
				float c = Float.intBitsToFloat(d[v * MeshBuilder.INTS_PER_VERTEX + a]);
				min = Math.min(min, c);
				max = Math.max(max, c);
			}
		}
		assertEquals(0f, min);
		assertEquals(1f, max, "local coordinates 0..16 even for section (−1, −1, −1)");
		int top = SectionMesher.scale(ColorModels.colorOf("minecraft:stone"), 1.0f);
		int bottom = SectionMesher.scale(ColorModels.colorOf("minecraft:stone"), 0.5f);
		assertTrue((top & 255) > (bottom & 255), "top face brighter than bottom face");
	}

	// ----- Weltanbindung -----

	@Test
	void snapshotCopiesBordersAcrossNegativeChunksAndListenerReportsChanges() throws Exception {
		WorldState w = ChunkTestSupport.world(ChunkTestSupport.OVERWORLD);
		List<String> events = new ArrayList<>();
		w.setListener(new WorldListener() {
			@Override
			public void chunkLoaded(final int x, final int z) {
				events.add("load " + x + "," + z);
			}

			@Override
			public void chunkUnloaded(final int x, final int z) {
				events.add("unload " + x + "," + z);
			}

			@Override
			public void blockChanged(final int x, final int y, final int z) {
				events.add("block " + x + "," + y + "," + z);
			}

			@Override
			public void lightChanged(final int x, final int z) {
				events.add("light " + x + "," + z);
			}
		});
		w.loadChunk(ChunkTestSupport.simpleChunk(ChunkTestSupport.OVERWORLD, -1, -1, Map.of(4, ChunkTestSupport.filled(STONE))));
		w.loadChunk(ChunkTestSupport.simpleChunk(ChunkTestSupport.OVERWORLD, 0, -1, Map.of()));
		SectionSnapshot s = w.sectionSnapshot(-1, 0, -1);
		assertNotNull(s);
		assertEquals(STONE, s.state(15, 0, 15), "block (-1, 0, -1)");
		assertEquals(0, s.state(16, 0, 15), "east neighbour chunk (0, -1) is air");
		assertEquals(SectionSnapshot.UNKNOWN_STATE, s.state(15, 0, 16), "south neighbour chunk (-1, 0) not loaded");
		assertEquals(0, s.state(0, -1, 0), "cell below comes from section y=-1 (air)");
		assertNull(w.sectionSnapshot(5, 0, 5), "unloaded chunk");
		assertNull(w.sectionSnapshot(-1, 20, -1), "above world height");
		w.applyBlockUpdate(new Clientbound.BlockUpdate(-1, 0, -1, 0));
		w.applyBlockUpdate(new Clientbound.BlockUpdate(-1, 0, -1, 0)); // keine Änderung → keine Meldung
		w.unloadChunk(0, -1);
		assertEquals(List.of("load -1,-1", "load 0,-1", "block -1,0,-1", "unload 0,-1"), events);
	}
}
