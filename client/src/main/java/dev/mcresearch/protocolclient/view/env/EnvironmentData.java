package dev.mcresearch.protocolclient.view.env;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.json.MiniJson;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Darstellungsdaten aus dem <b>lokalen</b> Spiel-JAR (docs/48 §1, docs/47 §4): Dimensionstypen, Biome (Klima, Farben,
 * Attribute), Zeitleisten samt Tags und die Colormaps für Gras/Laub/trockenes Laub. Nur lesend zur Laufzeit; nichts davon
 * gelangt in Build-Artefakte. Unbekannte Einträge werden übersprungen (nicht darstellungsrelevant).
 */
public final class EnvironmentData {
	/** Dimensionstyp ({@code data/minecraft/dimension_type}). */
	public record DimensionData(String id, boolean hasSkylight, boolean hasCeiling, float ambientLight, boolean netherLighting, String skybox,
		Map<EnvAttr, EnvModifier> attributes, List<String> timelines, boolean fixedTime) {
		/** {@code Level#canHaveWeather}: Himmelslicht, keine Decke, nicht das Ende. */
		public boolean canHaveWeather() {
			return this.hasSkylight && !this.hasCeiling && !this.skybox.equals("end");
		}
	}

	/** Biom ({@code data/minecraft/worldgen/biome}): Klima, Effekte und Attribute. */
	public record BiomeData(String id, float temperature, float downfall, boolean hasPrecipitation, boolean frozenModifier, int waterColor,
		Integer grassColor, Integer foliageColor, Integer dryFoliageColor, String grassModifier, Map<EnvAttr, EnvModifier> attributes) {
	}

	private static final String DATA = "data/minecraft/";
	private final Map<String, DimensionData> dimensions = new HashMap<>();
	private final Map<String, BiomeData> biomes = new HashMap<>();
	private final Map<String, EnvTimeline> timelines = new HashMap<>();
	private final Map<String, List<String>> timelineTags = new HashMap<>();
	private int[] grassMap = new int[0];
	private int[] foliageMap = new int[0];
	private int[] dryFoliageMap = new int[0];
	private final List<String> problems = new ArrayList<>();

	private EnvironmentData() {
	}

	/** Leere Daten (Farbmodus, Tests ohne JAR): Vorgabewerte, Ebenen-Biom. */
	public static EnvironmentData empty() {
		return new EnvironmentData();
	}

	public static EnvironmentData load(final Path jar) throws IOException {
		EnvironmentData d = new EnvironmentData();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Enumeration<? extends ZipEntry> en = zip.entries();
			List<ZipEntry> entries = new ArrayList<>();
			while (en.hasMoreElements()) {
				entries.add(en.nextElement());
			}
			for (ZipEntry e : entries) {
				String n = e.getName();
				if (!n.endsWith(".json") || !n.startsWith(DATA)) {
					continue;
				}
				String rel = n.substring(DATA.length(), n.length() - 5);
				try {
					if (rel.startsWith("dimension_type/")) {
						d.dimensions.put("minecraft:" + rel.substring(15), d.parseDimension("minecraft:" + rel.substring(15), json(zip, e)));
					} else if (rel.startsWith("worldgen/biome/")) {
						d.biomes.put("minecraft:" + rel.substring(15), d.parseBiome("minecraft:" + rel.substring(15), json(zip, e)));
					} else if (rel.startsWith("timeline/")) {
						d.timelines.put("minecraft:" + rel.substring(9), EnvTimeline.parse("minecraft:" + rel.substring(9), json(zip, e)));
					} else if (rel.startsWith("tags/timeline/")) {
						d.timelineTags.put("minecraft:" + rel.substring(14), stringList(json(zip, e).get("values")));
					}
				} catch (RuntimeException | ProtocolException ex) {
					d.problems.add(rel + ": " + ex.getMessage());
				}
			}
			d.grassMap = colormap(zip, "assets/minecraft/textures/colormap/grass.png");
			d.foliageMap = colormap(zip, "assets/minecraft/textures/colormap/foliage.png");
			d.dryFoliageMap = colormap(zip, "assets/minecraft/textures/colormap/dry_foliage.png");
		}
		return d;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> json(final ZipFile zip, final ZipEntry e) throws IOException, ProtocolException {
		try (InputStream in = zip.getInputStream(e)) {
			return (Map<String, Object>) MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private static List<String> stringList(final Object o) {
		List<String> out = new ArrayList<>();
		if (o instanceof List<?> l) {
			for (Object v : l) {
				out.add(String.valueOf(v));
			}
		}
		return out;
	}

	private static int[] colormap(final ZipFile zip, final String file) throws IOException {
		ZipEntry e = zip.getEntry(file);
		if (e == null) {
			return new int[0];
		}
		try (InputStream in = zip.getInputStream(e)) {
			BufferedImage img = dev.mcresearch.protocolclient.view.PngImages.read(in);
			int[] px = new int[img.getWidth() * img.getHeight()];
			img.getRGB(0, 0, img.getWidth(), img.getHeight(), px, 0, img.getWidth());
			return px;
		}
	}

	private Map<EnvAttr, EnvModifier> attributes(final Object o) {
		Map<EnvAttr, EnvModifier> out = new EnumMap<>(EnvAttr.class);
		if (o instanceof Map<?, ?> m) {
			for (Map.Entry<?, ?> e : m.entrySet()) {
				EnvAttr a = EnvAttr.byId(String.valueOf(e.getKey()));
				if (a != null) {
					try {
						out.put(a, EnvModifier.parse(a, e.getValue()));
					} catch (RuntimeException ex) {
						this.problems.add(e.getKey() + ": " + ex.getMessage());
					}
				}
			}
		}
		return out;
	}

	private DimensionData parseDimension(final String id, final Map<String, Object> j) {
		Object tl = j.get("timelines");
		List<String> timelines = tl instanceof String s ? List.of(s) : stringList(tl);
		return new DimensionData(id, Boolean.TRUE.equals(j.get("has_skylight")), Boolean.TRUE.equals(j.get("has_ceiling")),
			j.get("ambient_light") instanceof Number n ? n.floatValue() : 0f, "nether".equals(j.get("cardinal_light")),
			j.get("skybox") instanceof String s ? s : "overworld", attributes(j.get("attributes")), timelines, Boolean.TRUE.equals(j.get("has_fixed_time")));
	}

	@SuppressWarnings("unchecked")
	private BiomeData parseBiome(final String id, final Map<String, Object> j) {
		Map<String, Object> fx = j.get("effects") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
		return new BiomeData(id, ((Number) j.getOrDefault("temperature", 0.5)).floatValue(), ((Number) j.getOrDefault("downfall", 0.5)).floatValue(),
			!Boolean.FALSE.equals(j.get("has_precipitation")), "frozen".equals(j.get("temperature_modifier")), color(fx.get("water_color"), 0x3F76E4),
			colorOrNull(fx.get("grass_color")), colorOrNull(fx.get("foliage_color")), colorOrNull(fx.get("dry_foliage_color")),
			fx.get("grass_color_modifier") instanceof String s ? s : "none", attributes(j.get("attributes")));
	}

	private static Integer colorOrNull(final Object o) {
		return o == null ? null : color(o, 0);
	}

	private static int color(final Object o, final int fallback) {
		if (o instanceof String s && s.startsWith("#")) {
			return (int) Long.parseLong(s.substring(1), 16) & 0xFFFFFF;
		}
		if (o instanceof Number n) {
			return n.intValue() & 0xFFFFFF;
		}
		return fallback;
	}

	// ----- Abfragen -----

	public DimensionData dimension(final String id) {
		return this.dimensions.get(id);
	}

	public BiomeData biome(final String id) {
		return this.biomes.get(id);
	}

	public EnvTimeline timeline(final String id) {
		return this.timelines.get(id);
	}

	/** Zeitleisten einer Dimension in Tag-Reihenfolge (Tags aufgelöst, verschachtelt). */
	public List<EnvTimeline> timelinesOf(final DimensionData dim) {
		List<EnvTimeline> out = new ArrayList<>();
		for (String ref : dim.timelines()) {
			resolve(ref, out, 0);
		}
		return out;
	}

	private void resolve(final String ref, final List<EnvTimeline> out, final int depth) {
		if (depth > 8) {
			return;
		}
		if (ref.startsWith("#")) {
			for (String r : this.timelineTags.getOrDefault(ref.substring(1), List.of())) {
				resolve(r, out, depth + 1);
			}
		} else if (this.timelines.containsKey(ref) && !out.contains(this.timelines.get(ref))) {
			out.add(this.timelines.get(ref));
		}
	}

	public int[] grassMap() {
		return this.grassMap;
	}

	public int[] foliageMap() {
		return this.foliageMap;
	}

	public int[] dryFoliageMap() {
		return this.dryFoliageMap;
	}

	public String summary() {
		return String.format(Locale.ROOT, "%d dimension types, %d biomes, %d timelines, colormaps %s%s", this.dimensions.size(), this.biomes.size(),
			this.timelines.size(), this.grassMap.length == 65536 && this.foliageMap.length == 65536 ? "ok" : "missing",
			this.problems.isEmpty() ? "" : ", " + this.problems.size() + " problems e.g. " + this.problems.getFirst());
	}
}
