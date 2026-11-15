package dev.mcresearch.protocolclient.world;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Globale Blockzustands-IDs ({@code Block.BLOCK_STATE_REGISTRY}) und Block-Entity-Typen für Protokoll 777.
 * Faktendaten aus den offiziellen Reports {@code blocks.json}/{@code registries.json} (docs/18 §8), erzeugt mit
 * {@code analysis/tools/gen-block-tables.js}.
 */
public final class BlockRegistry {
	private static final BlockRegistry INSTANCE = load();

	private final BlockState[] states;
	private final String[] blockEntityTypes;
	private final Map<String, Integer> blockEntityIds = new HashMap<>();

	private BlockRegistry(final BlockState[] states, final String[] blockEntityTypes) {
		this.states = states;
		this.blockEntityTypes = blockEntityTypes;
		for (int i = 0; i < blockEntityTypes.length; i++) {
			this.blockEntityIds.put(blockEntityTypes[i], i);
		}
	}

	public static BlockRegistry get() {
		return INSTANCE;
	}

	private static BlockRegistry load() {
		try (InputStream raw = BlockRegistry.class.getResourceAsStream("/protocol/blockstates-777.txt.gz");
			 InputStream be = BlockRegistry.class.getResourceAsStream("/protocol/blockentities-777.txt")) {
			if (raw == null || be == null) {
				throw new IllegalStateException("Missing block registry resources");
			}
			List<BlockState> list = new ArrayList<>(40_000);
			Map<String, String> interned = new HashMap<>();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}
					String[] parts = line.split("\t", -1);
					int id = Integer.parseInt(parts[0]);
					if (id != list.size() || parts.length != 3) {
						throw new IllegalStateException("Block state table not contiguous at " + line);
					}
					Map<String, String> props = new LinkedHashMap<>();
					if (!parts[2].isEmpty()) {
						for (String kv : parts[2].split(",")) {
							int eq = kv.indexOf('=');
							props.put(interned.computeIfAbsent(kv.substring(0, eq), k -> k), interned.computeIfAbsent(kv.substring(eq + 1), v -> v));
						}
					}
					list.add(new BlockState(id, interned.computeIfAbsent(parts[1], n -> n), props));
				}
			}
			List<String> types = new ArrayList<>();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(be, StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}
					String[] parts = line.split("\t");
					if (Integer.parseInt(parts[0]) != types.size()) {
						throw new IllegalStateException("Block entity table not contiguous");
					}
					types.add(parts[1]);
				}
			}
			return new BlockRegistry(list.toArray(BlockState[]::new), types.toArray(String[]::new));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Anzahl der Blockzustände = Größe der globalen Palette. */
	public int size() {
		return this.states.length;
	}

	public boolean isValid(final int id) {
		return id >= 0 && id < this.states.length;
	}

	public BlockState state(final int id) {
		if (!isValid(id)) {
			throw new IllegalArgumentException("Unknown block state id " + id);
		}
		return this.states[id];
	}

	public int blockEntityTypeCount() {
		return this.blockEntityTypes.length;
	}

	public String blockEntityType(final int id) {
		return id >= 0 && id < this.blockEntityTypes.length ? this.blockEntityTypes[id] : null;
	}

	public Integer blockEntityTypeId(final String name) {
		return this.blockEntityIds.get(name);
	}

	/** Erste ID eines Blocks mit genau diesen Eigenschaften (für Tests und Abgleiche). */
	public int idOf(final String name, final Map<String, String> properties) {
		for (BlockState s : this.states) {
			if (s.name().equals(name) && s.properties().equals(properties)) {
				return s.id();
			}
		}
		throw new IllegalArgumentException("No state " + name + properties);
	}

	/** ceillog2(n) wie {@code Mth.ceillog2}: kleinste b mit 2^b ≥ n. */
	public static int ceilLog2(final int n) {
		if (n <= 1) {
			return 0;
		}
		return 32 - Integer.numberOfLeadingZeros(n - 1);
	}
}
