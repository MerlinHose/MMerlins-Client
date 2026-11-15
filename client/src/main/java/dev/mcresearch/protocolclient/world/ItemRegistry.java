package dev.mcresearch.protocolclient.world;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Item-Fakten für Protokoll 777 (docs/28 §2): Item-IDs, Stapelgrößen, Standard-Werkzeugregeln, Datenkomponenten-IDs
 * ({@code protocol/items-777.txt.gz}, aus den offiziellen Datagen-Reports) und Block-Items
 * ({@code protocol/item-blocks-777.txt.gz}, {@code BlockItem#getBlock}). Unveränderlich, threadsicher.
 */
public final class ItemRegistry {
	/** Werkzeugregel ({@code Tool.Rule}): Blöcke, optionale Geschwindigkeit, optionales correct_for_drops. */
	public record Rule(Set<String> blocks, Float speed, Boolean correctForDrops) {
	}

	/** Standardwerkzeug ({@code minecraft:tool}). */
	public record Tool(float defaultSpeed, boolean canDestroyBlocksInCreative, List<Rule> rules) {
		/** {@code Tool#getMiningSpeed}: erste passende Regel mit Geschwindigkeit, sonst Standard. */
		public float miningSpeed(final String block) {
			for (Rule r : this.rules) {
				if (r.speed() != null && r.blocks().contains(block)) {
					return r.speed();
				}
			}
			return this.defaultSpeed;
		}

		/** {@code Tool#isCorrectForDrops}: erste passende Regel mit correct_for_drops, sonst false. */
		public boolean correctForDrops(final String block) {
			for (Rule r : this.rules) {
				if (r.correctForDrops() != null && r.blocks().contains(block)) {
					return r.correctForDrops();
				}
			}
			return false;
		}
	}

	private static volatile ItemRegistry instance;

	private final String[] names;
	private final int[] maxStack;
	private final Map<String, Integer> byName;
	private final Map<Integer, Tool> tools;
	private final Map<Integer, String> blockOf;
	private final String[] componentNames;
	private final Map<String, Integer> componentIds;

	private ItemRegistry(final String[] names, final int[] maxStack, final Map<Integer, Tool> tools, final Map<Integer, String> blockOf,
		final String[] componentNames) {
		this.names = names;
		this.maxStack = maxStack;
		this.tools = tools;
		this.blockOf = blockOf;
		this.componentNames = componentNames;
		this.byName = new HashMap<>();
		for (int i = 0; i < names.length; i++) {
			this.byName.put(names[i], i);
		}
		this.componentIds = new HashMap<>();
		for (int i = 0; i < componentNames.length; i++) {
			if (componentNames[i] != null) {
				this.componentIds.put(componentNames[i], i);
			}
		}
	}

	public static ItemRegistry get() {
		ItemRegistry r = instance;
		if (r == null) {
			synchronized (ItemRegistry.class) {
				r = instance;
				if (r == null) {
					instance = r = load();
				}
			}
		}
		return r;
	}

	private static BufferedReader open(final String resource) throws IOException {
		InputStream in = ItemRegistry.class.getResourceAsStream(resource);
		if (in == null) {
			throw new IllegalStateException("missing resource " + resource);
		}
		return new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
	}

	private static ItemRegistry load() {
		List<String> names = new ArrayList<>();
		List<Integer> stacks = new ArrayList<>();
		Map<String, Float> toolSpeed = new HashMap<>();
		Map<String, Boolean> toolCreative = new HashMap<>();
		Map<String, List<Rule>> toolRules = new HashMap<>();
		Map<Integer, String> components = new HashMap<>();
		try (BufferedReader r = open("/protocol/items-777.txt.gz")) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] p = line.split("\t", -1);
				switch (p[0]) {
					case "I" -> {
						int id = Integer.parseInt(p[1]);
						if (id != names.size()) {
							throw new IllegalStateException("item ids not dense at " + id);
						}
						names.add(p[2]);
						stacks.add(Integer.parseInt(p[3]));
					}
					case "T" -> {
						toolSpeed.put(p[1], Float.parseFloat(p[2]));
						toolCreative.put(p[1], Boolean.parseBoolean(p[3]));
						toolRules.computeIfAbsent(p[1], k -> new ArrayList<>());
					}
					case "R" -> toolRules.computeIfAbsent(p[1], k -> new ArrayList<>()).add(new Rule(
						p[4].isEmpty() ? Set.of() : new HashSet<>(List.of(p[4].split(","))),
						p[2].equals("-") ? null : Float.parseFloat(p[2]),
						p[3].equals("-") ? null : p[3].equals("1")));
					case "D" -> components.put(Integer.parseInt(p[1]), p[2]);
					default -> throw new IllegalStateException("unknown item table line " + p[0]);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		Map<String, Integer> idx = new HashMap<>();
		for (int i = 0; i < names.size(); i++) {
			idx.put(names.get(i), i);
		}
		Map<Integer, Tool> tools = new HashMap<>();
		toolSpeed.forEach((name, speed) -> tools.put(idx.get(name), new Tool(speed, toolCreative.get(name), List.copyOf(toolRules.get(name)))));
		Map<Integer, String> blockOf = new HashMap<>();
		try (BufferedReader r = open("/protocol/item-blocks-777.txt.gz")) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.startsWith("IB\t")) {
					String[] p = line.split("\t");
					Integer id = idx.get(p[1]);
					if (id == null) {
						throw new IllegalStateException("block item " + p[1] + " not in item table");
					}
					blockOf.put(id, p[2]);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		int maxComponent = components.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
		String[] comp = new String[maxComponent + 1];
		components.forEach((k, v) -> comp[k] = v);
		return new ItemRegistry(names.toArray(String[]::new), stacks.stream().mapToInt(Integer::intValue).toArray(), tools, blockOf, comp);
	}

	public int size() {
		return this.names.length;
	}

	public boolean isValid(final int id) {
		return id >= 0 && id < this.names.length;
	}

	public String name(final int id) {
		return this.names[id];
	}

	/** Item-ID zu {@code minecraft:…} oder −1. */
	public int idOf(final String name) {
		return this.byName.getOrDefault(name.contains(":") ? name : "minecraft:" + name, -1);
	}

	public int maxStackSize(final int id) {
		return this.maxStack[id];
	}

	/** Standard-Werkzeugkomponente oder {@code null}. */
	public Tool tool(final int id) {
		return this.tools.get(id);
	}

	/** Block, den das Item als {@code BlockItem} platziert, oder {@code null}. */
	public String placedBlock(final int id) {
		return this.blockOf.get(id);
	}

	/** Name des Datenkomponententyps (Protokoll-ID im Register {@code minecraft:data_component_type}) oder {@code null}. */
	public String componentName(final int id) {
		return id >= 0 && id < this.componentNames.length ? this.componentNames[id] : null;
	}

	public int componentId(final String name) {
		return this.componentIds.getOrDefault(name, -1);
	}
}
