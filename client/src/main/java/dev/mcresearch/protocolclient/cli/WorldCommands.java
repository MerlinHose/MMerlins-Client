package dev.mcresearch.protocolclient.cli;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.world.BlockEntity;
import dev.mcresearch.protocolclient.world.BlockState;
import dev.mcresearch.protocolclient.world.Chunk;
import dev.mcresearch.protocolclient.world.ChunkSection;
import dev.mcresearch.protocolclient.world.PalettedContainer;
import dev.mcresearch.protocolclient.world.WorldState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Textausgaben der Weltbefehle {@code block}, {@code chunk}, {@code world}, {@code nearby}, {@code blocks} (docs/19 §7).
 * Reine Funktionen über {@link WorldState}; dadurch ohne Netzwerk testbar.
 */
public final class WorldCommands {
	public static final int MAX_NEARBY_RADIUS = 8;
	private static final int[] HEIGHTMAP_TYPES = {1, 4};
	private static final String[] HEIGHTMAP_NAMES = {"WORLD_SURFACE_WG", "WORLD_SURFACE", "OCEAN_FLOOR_WG", "OCEAN_FLOOR", "MOTION_BLOCKING",
		"MOTION_BLOCKING_NO_LEAVES"};

	private WorldCommands() {
	}

	/** {@code block x y z}: Zustand, Lage im Chunk, Biom, Licht, Block-Entity. */
	public static List<String> block(final WorldState world, final int x, final int y, final int z) {
		List<String> out = new ArrayList<>();
		int cx = Math.floorDiv(x, 16);
		int cz = Math.floorDiv(z, 16);
		Optional<BlockState> state = world.block(x, y, z);
		if (state.isEmpty()) {
			out.add(String.format(Locale.ROOT, "block %d %d %d: chunk %d,%d not loaded", x, y, z, cx, cz));
			return out;
		}
		Chunk chunk = world.chunk(cx, cz);
		if (chunk == null || !chunk.containsY(y)) {
			out.add(String.format(Locale.ROOT, "block %d %d %d = %s (outside world height %d..%d)", x, y, z, state.get(),
				world.dimension().minY(), world.dimension().minY() + world.dimension().height() - 1));
			return out;
		}
		BlockState s = state.get();
		out.add(String.format(Locale.ROOT, "block %d %d %d = %s (state id %d)", x, y, z, s, s.id()));
		int sectionY = y >> 4;
		out.add(String.format(Locale.ROOT, "  chunk %d,%d section y=%d (index %d) local %d,%d,%d | biome %s | sky light %s block light %s",
			cx, cz, sectionY, sectionY - chunk.minSectionY(), x & 15, y & 15, z & 15, world.biomeName(chunk.biome(x, y, z)),
			light(chunk.light(true, x, y, z)), light(chunk.light(false, x, y, z))));
		BlockEntity be = chunk.blockEntity(x, y, z);
		if (be != null) {
			out.add("  block entity " + be.type() + " nbt keys " + be.nbt().keySet());
		}
		return out;
	}

	private static String light(final int v) {
		return v < 0 ? "unknown" : String.valueOf(v);
	}

	/** {@code chunk cx cz}: Sektionen, Paletten, Heightmaps, Licht, Block-Entities. */
	public static List<String> chunk(final WorldState world, final int cx, final int cz) {
		List<String> out = new ArrayList<>();
		Chunk c = world.chunk(cx, cz);
		if (c == null) {
			out.add("chunk " + cx + "," + cz + " not loaded");
			return out;
		}
		out.add(String.format(Locale.ROOT, "chunk %d,%d blocks x %d..%d z %d..%d | %d sections from y=%d | section data %d bytes | %d block entities",
			cx, cz, cx * 16, cx * 16 + 15, cz * 16, cz * 16 + 15, c.sectionCount(), c.minSectionY() * 16, c.dataBytes(), c.blockEntities().size()));
		StringBuilder hm = new StringBuilder("  heightmaps:");
		for (int type : HEIGHTMAP_TYPES) {
			try {
				int v = c.heightmap(type, 0, 0);
				hm.append(' ').append(HEIGHTMAP_NAMES[type]).append(v < 0 ? "=absent" : "(0,0)=y" + (v + c.dimension().minY()));
			} catch (ProtocolException e) {
				hm.append(' ').append(HEIGHTMAP_NAMES[type]).append("=invalid");
			}
		}
		out.add(hm.toString());
		out.add(String.format(Locale.ROOT, "  light sections with data: sky %d block %d (of %d)", c.lightSectionsWithData(true),
			c.lightSectionsWithData(false), c.lightSectionCount()));
		for (int i = 0; i < c.sectionCount(); i++) {
			ChunkSection s = c.section(i);
			PalettedContainer st = s.states();
			PalettedContainer bi = s.biomes();
			String blocks = st.paletteType() == PalettedContainer.PaletteType.SINGLETON
				? "all " + world.registry().state(st.get(0, 0, 0)).name()
				: st.paletteType() + " bits=" + st.sentBits() + (st.paletteSize() > 0 ? " palette=" + st.paletteSize() : "");
			String biomes = bi.paletteType() == PalettedContainer.PaletteType.SINGLETON
				? world.biomeName(bi.get(0, 0, 0)) : bi.paletteType() + " bits=" + bi.sentBits();
			out.add(String.format(Locale.ROOT, "  [%2d] y=%5d nonAir=%4d fluid=%4d blocks: %-28s biomes: %s", i, (c.minSectionY() + i) * 16,
				s.nonEmptyBlockCount(), s.fluidCount(), blocks, biomes));
		}
		return out;
	}

	/** {@code world}: Dimension und Zähler. */
	public static List<String> world(final WorldState world, final String dimensionKey) {
		WorldState.Stats s = world.stats();
		List<String> out = new ArrayList<>();
		out.add(String.format(Locale.ROOT, "dimension %s (%s) min_y=%d height=%d sections=%d | block states %d | biomes %d | loaded chunks %d",
			dimensionKey, world.dimension().id(), world.dimension().minY(), world.dimension().height(), world.dimension().height() >> 4,
			world.registry().size(), world.biomeCount(), world.loadedChunks()));
		out.add(String.format(Locale.ROOT, "  chunks loaded %d (replaced %d) unloaded %d (unknown %d) | sections %d | section bytes %d | trailing-byte checks %d | nonAir mismatches %d",
			s.chunksLoaded, s.chunksReplaced, s.chunksUnloaded, s.unloadsOfUnknownChunks, s.sectionsDecoded, s.chunkBytesDecoded, s.trailingByteChecks,
			s.nonEmptyCountMismatches));
		out.add(String.format(Locale.ROOT, "  block_update %d | section_blocks_update %d (%d entries) | light_update %d | block_entity_data %d | biome updates %d | ignored (unloaded) %d",
			s.blockUpdates, s.sectionUpdates, s.sectionUpdateEntries, s.lightUpdates, s.blockEntityUpdates, s.biomeUpdates, s.updatesForUnloadedChunks));
		out.add(String.format(Locale.ROOT, "  client light recomputations %d (sections changed %d)", s.clientLightUpdates, s.clientLightSections));
		out.add("  block palettes " + s.blockPalettes + " | biome palettes " + s.biomePalettes);
		return out;
	}

	/** {@code nearby r}: alle Nicht-Luft-Blöcke im Würfel mit Radius r um die Blockposition, nach Abstand sortiert. */
	public static List<String> nearby(final WorldState world, final double px, final double py, final double pz, final int radius, final int limit) {
		if (radius < 0 || radius > MAX_NEARBY_RADIUS) {
			throw new IllegalArgumentException("radius 0.." + MAX_NEARBY_RADIUS);
		}
		int bx = (int) Math.floor(px);
		int by = (int) Math.floor(py);
		int bz = (int) Math.floor(pz);
		record Hit(int x, int y, int z, int d2, BlockState s) {
		}
		List<Hit> hits = new ArrayList<>();
		int unloaded = 0;
		for (int dy = -radius; dy <= radius; dy++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					Optional<BlockState> s = world.block(bx + dx, by + dy, bz + dz);
					if (s.isEmpty()) {
						unloaded++;
					} else if (!s.get().isAir()) {
						hits.add(new Hit(bx + dx, by + dy, bz + dz, dx * dx + dy * dy + dz * dz, s.get()));
					}
				}
			}
		}
		hits.sort(Comparator.comparingInt(Hit::d2).thenComparingInt(Hit::y).thenComparingInt(Hit::z).thenComparingInt(Hit::x));
		List<String> out = new ArrayList<>();
		int side = 2 * radius + 1;
		out.add(String.format(Locale.ROOT, "nearby %d %d %d radius %d: %d non-air of %d blocks%s", bx, by, bz, radius, hits.size(), side * side * side,
			unloaded > 0 ? " (" + unloaded + " in unloaded chunks)" : ""));
		Optional<BlockState> below = world.block(bx, by - 1, bz);
		out.add("  below feet (" + bx + " " + (by - 1) + " " + bz + "): " + below.map(BlockState::toString).orElse("unloaded"));
		for (int i = 0; i < Math.min(limit, hits.size()); i++) {
			Hit h = hits.get(i);
			out.add(String.format(Locale.ROOT, "  %d %d %d %s", h.x(), h.y(), h.z(), h.s()));
		}
		if (hits.size() > limit) {
			out.add("  … " + (hits.size() - limit) + " more");
		}
		return out;
	}

	/** {@code blocks cx cz}: Häufigkeit der Blockarten (Name) in einem Chunk. */
	public static List<String> blocks(final WorldState world, final int cx, final int cz, final int limit) {
		List<String> out = new ArrayList<>();
		Chunk c = world.chunk(cx, cz);
		if (c == null) {
			out.add("chunk " + cx + "," + cz + " not loaded");
			return out;
		}
		Map<String, Integer> counts = new HashMap<>();
		Map<Integer, Integer> perState = new HashMap<>();
		for (int i = 0; i < c.sectionCount(); i++) {
			for (int id : c.section(i).states().toArray()) {
				perState.merge(id, 1, Integer::sum);
			}
		}
		perState.forEach((id, n) -> counts.merge(world.registry().state(id).name(), n, Integer::sum));
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
		out.add(String.format(Locale.ROOT, "chunk %d,%d: %d blocks, %d block types, %d distinct states", cx, cz, c.sectionCount() * 4096, counts.size(),
			perState.size()));
		for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
			out.add(String.format(Locale.ROOT, "  %7d %s", sorted.get(i).getValue(), sorted.get(i).getKey()));
		}
		if (sorted.size() > limit) {
			out.add("  … " + (sorted.size() - limit) + " more types");
		}
		return out;
	}
}
