package dev.mcresearch.protocolclient.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M14 (docs/55 §5): Varianten-Register der Tiere ({@code cat_variant}, {@code wolf_variant}, {@code pig_variant},
 * {@code cow_variant}, {@code chicken_variant}) aus {@code registry_data} der Konfigurationsphase. Das Datenfeld der Entity überträgt
 * nur die Registry-ID (Reihenfolge der Einträge); Texturen und Modelltyp stehen in den Einträgen ({@code asset_id},
 * {@code baby_asset_id}, {@code model} bzw. {@code assets}/{@code baby_assets} beim Wolf). Wurden die Known Packs angenommen,
 * sendet der Server nur die Namen – dann liefert ein {@link Fallback} die Daten aus dem lokalen Datenpaket (JSON im JAR).
 *
 * <p>Threadsicher (Konfigurations-/Netzthread schreibt, Grafikthread liest).
 */
public final class EntityVariants {
	public static final Set<String> REGISTRIES = Set.of("minecraft:cat_variant", "minecraft:wolf_variant", "minecraft:pig_variant",
		"minecraft:cow_variant", "minecraft:chicken_variant");

	/** Liefert die Rohdaten eines Eintrags (z. B. aus {@code data/minecraft/pig_variant/cold.json}) oder null. */
	@FunctionalInterface
	public interface Fallback {
		Map<?, ?> load(String registry, String entry);
	}

	/**
	 * Aufgelöste Variante: Texturpfade ohne Namensraum relativ zu {@code textures/} (z. B. {@code entity/pig/pig_cold}); beim Wolf
	 * {@code wild/tame/angry} je Alter; {@code model} = Modelltyp ({@code normal}, {@code cold}, {@code warm}).
	 */
	public record Variant(String name, String asset, String babyAsset, String model, String wild, String tame, String angry, String babyWild,
		String babyTame, String babyAngry) {
	}

	private final Map<String, List<String>> names = new ConcurrentHashMap<>();
	private final Map<String, Map<?, ?>> data = new ConcurrentHashMap<>();
	private final Map<String, Variant> resolved = new ConcurrentHashMap<>();

	/** Einträge eines Registers in Protokollreihenfolge ({@code data} null, wenn nicht gesendet). */
	public void setRegistry(final String registry, final List<String> entryNames, final List<Object> entryData) {
		if (!REGISTRIES.contains(registry)) {
			return;
		}
		this.names.put(registry, List.copyOf(entryNames));
		for (int i = 0; i < entryNames.size(); i++) {
			Object d = entryData == null || i >= entryData.size() ? null : entryData.get(i);
			String key = registry + "|" + entryNames.get(i);
			if (d instanceof Map<?, ?> m) {
				this.data.put(key, m);
			} else {
				this.data.remove(key);
			}
			this.resolved.remove(key);
		}
	}

	public void clear() {
		this.names.clear();
		this.data.clear();
		this.resolved.clear();
	}

	/** Name des Eintrags mit dieser ID (z. B. {@code minecraft:cold}) oder null. */
	public String name(final String registry, final int id) {
		List<String> l = this.names.get(registry);
		return l == null || id < 0 || id >= l.size() ? null : l.get(id);
	}

	public int size(final String registry) {
		List<String> l = this.names.get(registry);
		return l == null ? 0 : l.size();
	}

	/** Aufgelöste Variante zur Registry-ID oder null (Register unbekannt, ID außerhalb, Daten fehlen). */
	public Variant variant(final String registry, final int id, final Fallback fallback) {
		String name = name(registry, id);
		if (name == null) {
			return null;
		}
		String key = registry + "|" + name;
		Variant v = this.resolved.get(key);
		if (v != null) {
			return v;
		}
		Map<?, ?> d = this.data.get(key);
		if (d == null && fallback != null) {
			d = fallback.load(registry, name);
		}
		if (d == null) {
			return null;
		}
		v = parse(name, d);
		this.resolved.put(key, v);
		return v;
	}

	static Variant parse(final String name, final Map<?, ?> d) {
		Map<?, ?> assets = d.get("assets") instanceof Map<?, ?> m ? m : Map.of();
		Map<?, ?> baby = d.get("baby_assets") instanceof Map<?, ?> m ? m : Map.of();
		return new Variant(name, path(d.get("asset_id")), path(d.get("baby_asset_id")), d.get("model") instanceof String s ? s : "normal",
			path(assets.get("wild")), path(assets.get("tame")), path(assets.get("angry")), path(baby.get("wild")), path(baby.get("tame")),
			path(baby.get("angry")));
	}

	/** {@code minecraft:entity/pig/pig_cold} → {@code entity/pig/pig_cold}; andere Namensräume werden nicht unterstützt (null). */
	static String path(final Object id) {
		if (!(id instanceof String s) || s.isEmpty()) {
			return null;
		}
		if (s.startsWith("minecraft:")) {
			return s.substring(10);
		}
		return s.contains(":") ? null : s;
	}

	/** Registernamen (Test/Anzeige). */
	public List<String> registries() {
		return new ArrayList<>(this.names.keySet());
	}
}
