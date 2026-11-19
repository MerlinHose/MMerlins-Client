package dev.mcresearch.protocolclient.view.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * M14 (Grafiktest m14-gfx4: Debug-Box für einen liegenden Gegenstand): Textur eines Gegenstands über sein Modell, wenn weder
 * {@code item/<name>} noch {@code block/<name>} als Datei existiert.
 *
 * <ol>
 *   <li>Itemdefinition {@code items/<name>.json} (26.3): erstes Modell vom Typ {@code minecraft:model}, z. B.
 *       {@code block/tuff_wall_inventory};</li>
 *   <li>sonst {@code item/<name>}, dann {@code block/<name>};</li>
 *   <li>im Modell (entlang der {@code parent}-Kette, Verweise {@code #schlüssel} aufgelöst): {@code layer0}, sonst {@code particle},
 *       sonst die erste konkrete Textur.</li>
 * </ol>
 * Vereinfachung gegenüber Vanilla: flaches Sprite bzw. Würfel statt des vollständigen Modells.
 */
public final class ItemTextures {
	private static final int MAX_DEPTH = 8;

	private ItemTextures() {
	}

	/**
	 * @param name Gegenstandsname ohne Namensraum
	 * @param models lädt {@code models/<pfad>.json} (z. B. {@code item/stick}) als Map oder null
	 * @param itemDefinitions lädt {@code items/<name>.json} als Map oder null
	 * @return Texturpfad ohne Namensraum ({@code block/tuff}) oder null
	 */
	public static String resolve(final String name, final Function<String, Map<?, ?>> models, final Function<String, Map<?, ?>> itemDefinitions) {
		String defModel = itemDefinitions == null ? null : firstModel(itemDefinitions.apply(name), 0);
		for (String model : new String[]{defModel == null ? null : strip(defModel), "item/" + name, "block/" + name}) {
			if (model == null) {
				continue;
			}
			String t = texture(model, models);
			if (t != null) {
				return t;
			}
		}
		return null;
	}

	/** Erstes {@code "model"} eines Eintrags {@code "type": "minecraft:model"} (Tiefensuche durch bedingte/ausgewählte Modelle). */
	private static String firstModel(final Object node, final int depth) {
		if (node == null || depth > 12) {
			return null;
		}
		if (node instanceof Map<?, ?> m) {
			// Spezialmodelle (Köpfe, Banner, Truhen): Basismodell "base"
			if ("minecraft:special".equals(m.get("type")) && m.get("base") instanceof String b) {
				return b;
			}
			if ("minecraft:model".equals(m.get("type")) && m.get("model") instanceof String s) {
				return s;
			}
			for (Object v : m.values()) {
				String r = firstModel(v, depth + 1);
				if (r != null) {
					return r;
				}
			}
		} else if (node instanceof List<?> l) {
			for (Object v : l) {
				String r = firstModel(v, depth + 1);
				if (r != null) {
					return r;
				}
			}
		}
		return null;
	}

	private static String texture(final String model, final Function<String, Map<?, ?>> models) {
		Map<String, String> textures = new LinkedHashMap<>();
		String current = model;
		boolean found = false;
		for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
			Map<?, ?> json = models.apply(strip(current));
			if (json == null) {
				break;
			}
			found = true;
			if (json.get("textures") instanceof Map<?, ?> tex) {
				for (Map.Entry<?, ?> e : tex.entrySet()) {
					if (e.getValue() instanceof String s) {
						textures.putIfAbsent(String.valueOf(e.getKey()), s); // Kind überschreibt Eltern
					}
				}
			}
			current = json.get("parent") instanceof String p ? p : null;
		}
		if (!found) {
			return null;
		}
		for (String key : new String[]{"layer0", "particle"}) {
			String v = deref(textures, textures.get(key));
			if (v != null) {
				return strip(v);
			}
		}
		for (String v : textures.values()) {
			String d = deref(textures, v);
			if (d != null) {
				return strip(d);
			}
		}
		return null;
	}

	private static String deref(final Map<String, String> textures, final String value) {
		String v = value;
		for (int i = 0; v != null && v.startsWith("#") && i < MAX_DEPTH; i++) {
			v = textures.get(v.substring(1));
		}
		return v == null || v.startsWith("#") ? null : v;
	}

	private static String strip(final String s) {
		return s.startsWith("minecraft:") ? s.substring(10) : s;
	}
}
