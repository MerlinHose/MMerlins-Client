package dev.mcresearch.protocolclient.entity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity-Typen des Registers {@code minecraft:entity_type} (161 Einträge, 26.3) mit Abmessungen, Verfolgungsreichweite und
 * Aktualisierungsintervall. Quelle: {@code protocol/entities-777.txt}, erzeugt mit {@code analysis/tools/EntityExtractor.java} (docs/34).
 */
public final class EntityTypes {
	/** Eine Zeile der Tabelle; {@code updateInterval} in Ticks, {@code trackingRange} in Chunks. */
	public record Type(int id, String name, float width, float height, float eyeHeight, boolean fixed, int trackingRange, int updateInterval,
		boolean trackDeltas, String category) {
		public String shortName() {
			return this.name.startsWith("minecraft:") ? this.name.substring(10) : this.name;
		}
	}

	/** Platzhalter für unbekannte Typ-IDs: wird sichtbar als Debug-Box dargestellt (docs/36). */
	public static final Type UNKNOWN = new Type(-1, "unknown", 0.5F, 0.5F, 0.425F, false, 5, 3, true, "misc");
	private static volatile EntityTypes instance;

	private final List<Type> byId;
	private final Map<String, Type> byName;

	private EntityTypes(final List<Type> byId) {
		this.byId = List.copyOf(byId);
		Map<String, Type> m = new HashMap<>();
		for (Type t : byId) {
			m.put(t.name(), t);
		}
		this.byName = Map.copyOf(m);
	}

	public static EntityTypes get() {
		EntityTypes i = instance;
		if (i == null) {
			synchronized (EntityTypes.class) {
				i = instance;
				if (i == null) {
					i = load();
					instance = i;
				}
			}
		}
		return i;
	}

	/** Typ zur Protokoll-ID oder {@link #UNKNOWN}. */
	public Type type(final int id) {
		return id >= 0 && id < this.byId.size() ? this.byId.get(id) : UNKNOWN;
	}

	/** Typ zum Namen ({@code minecraft:pig}) oder {@link #UNKNOWN}. */
	public Type byName(final String name) {
		return this.byName.getOrDefault(name.contains(":") ? name : "minecraft:" + name, UNKNOWN);
	}

	public int size() {
		return this.byId.size();
	}

	private static EntityTypes load() {
		List<Type> list = new ArrayList<>();
		try (InputStream in = EntityTypes.class.getResourceAsStream("/protocol/entities-777.txt")) {
			if (in == null) {
				throw new IllegalStateException("resource /protocol/entities-777.txt missing");
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = r.readLine()) != null) {
				if (!line.startsWith("T\t")) {
					continue;
				}
				String[] p = line.split("\t");
				int id = Integer.parseInt(p[1]);
				if (id != list.size()) {
					throw new IllegalStateException("entity type ids not contiguous at " + id);
				}
				list.add(new Type(id, p[2], Float.parseFloat(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]), Boolean.parseBoolean(p[6]),
					Integer.parseInt(p[7]), Integer.parseInt(p[8]), Boolean.parseBoolean(p[9]), p[10]));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return new EntityTypes(list);
	}
}
