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
 * M14 (docs/57 §2): zusätzlich Ankerpunkte je Typ ({@code X}: PASSENGER, VEHICLE, NAME_TAG …) und eigene Baby-Maße mit
 * Ankerpunkten ({@code B}/{@code XB}).
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
	/** Klasse der Baby-Maße (Tabelle B) → Kurzname des Typs. */
	private static final Map<String, String> BABY_CLASS_TO_TYPE = Map.ofEntries(Map.entry("Pig", "pig"), Map.entry("Cow", "cow"),
		Map.entry("Sheep", "sheep"), Map.entry("Zombie", "zombie"), Map.entry("Chicken", "chicken"), Map.entry("Wolf", "wolf"), Map.entry("Cat", "cat"),
		Map.entry("Ocelot", "ocelot"), Map.entry("Horse", "horse"), Map.entry("Rabbit", "rabbit"), Map.entry("Villager", "villager"),
		Map.entry("Drowned", "drowned"));
	private static volatile EntityTypes instance;

	private final List<Type> byId;
	private final Map<String, Type> byName;
	/** Kurzname → {Breite, Höhe, Augenhöhe}. */
	private final Map<String, float[]> baby;
	/** "kurzname|ART" bzw. "kurzname|ART|baby" → Punkte (x, y, z je Index). */
	private final Map<String, List<double[]>> attachments;
	/** Feste Register (Tabelle R): "register|id" → Name, z. B. villager_type, villager_profession. */
	private final Map<String, String> registryNames;

	private EntityTypes(final List<Type> byId, final Map<String, float[]> baby, final Map<String, List<double[]>> attachments,
		final Map<String, String> registryNames) {
		this.registryNames = Map.copyOf(registryNames);
		this.byId = List.copyOf(byId);
		Map<String, Type> m = new HashMap<>();
		for (Type t : byId) {
			m.put(t.name(), t);
		}
		this.byName = Map.copyOf(m);
		this.baby = Map.copyOf(baby);
		this.attachments = Map.copyOf(attachments);
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

	/** Name im festen Register (ohne Namensraum), z. B. {@code registryName("villager_type", 2)} = {@code plains}; null wenn unbekannt. */
	public String registryName(final String registry, final int id) {
		return this.registryNames.get(registry + "|" + id);
	}

	/** Eigene Baby-Maße {Breite, Höhe, Augenhöhe} oder null (dann Typmaße × Baby-Skalierung). */
	public float[] babyDimensions(final String shortName) {
		float[] b = this.baby.get(shortName);
		return b == null ? null : b.clone();
	}

	/**
	 * Ankerpunkt ({@code EntityAttachments#getClamped}: Index auf die vorhandenen Punkte begrenzt) vor der Drehung; null, wenn der Typ
	 * keinen Punkt dieser Art hat. {@code baby}: Punkte der eigenen Baby-Maße, sonst Typpunkte × 0,5 wie {@code EntityDimensions#scale}.
	 */
	public double[] attachment(final String shortName, final String kind, final int index, final boolean baby) {
		List<double[]> pts = baby ? this.attachments.get(shortName + "|" + kind + "|baby") : null;
		double f = 1;
		if (pts == null) {
			pts = this.attachments.get(shortName + "|" + kind);
			f = baby ? babyScale(shortName) : 1;
		}
		if (pts == null || pts.isEmpty()) {
			return null;
		}
		double[] p = pts.get(Math.clamp(index, 0, pts.size() - 1));
		return new double[]{p[0] * f, p[1] * f, p[2] * f};
	}

	private static double babyScale(final String shortName) {
		return switch (shortName) {
			case "skeleton_horse", "zombie_horse" -> 0.7;
			default -> 0.5;
		};
	}

	private static EntityTypes load() {
		List<Type> list = new ArrayList<>();
		Map<String, float[]> baby = new HashMap<>();
		Map<String, List<double[]>> att = new HashMap<>();
		Map<String, String> reg = new HashMap<>();
		try (InputStream in = EntityTypes.class.getResourceAsStream("/protocol/entities-777.txt")) {
			if (in == null) {
				throw new IllegalStateException("resource /protocol/entities-777.txt missing");
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = r.readLine()) != null) {
				String[] p = line.split("\t");
				switch (p[0]) {
					case "T" -> {
						int id = Integer.parseInt(p[1]);
						if (id != list.size()) {
							throw new IllegalStateException("entity type ids not contiguous at " + id);
						}
						list.add(new Type(id, p[2], Float.parseFloat(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]), Boolean.parseBoolean(p[6]),
							Integer.parseInt(p[7]), Integer.parseInt(p[8]), Boolean.parseBoolean(p[9]), p[10]));
					}
					case "B" -> {
						String type = BABY_CLASS_TO_TYPE.get(p[1]);
						if (type != null) {
							baby.put(type, new float[]{Float.parseFloat(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5])});
						}
					}
					case "R" -> reg.put(p[1] + "|" + p[2], p[3].startsWith("minecraft:") ? p[3].substring(10) : p[3]);
					case "X", "XB" -> {
						String type = p[0].equals("X") ? (p[1].startsWith("minecraft:") ? p[1].substring(10) : p[1]) : BABY_CLASS_TO_TYPE.get(p[1]);
						if (type != null) {
							String key = type + "|" + p[2] + (p[0].equals("XB") ? "|baby" : "");
							List<double[]> l = att.computeIfAbsent(key, k -> new ArrayList<>());
							if (Integer.parseInt(p[3]) == l.size()) { // doppelte Klassen (Unter-/Oberklasse) nur einmal
								l.add(new double[]{Double.parseDouble(p[4]), Double.parseDouble(p[5]), Double.parseDouble(p[6])});
							}
						}
					}
					default -> {
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return new EntityTypes(list, baby, att, reg);
	}
}
