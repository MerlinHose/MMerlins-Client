package dev.mcresearch.protocolclient.view.env;

import dev.mcresearch.protocolclient.world.BiomeZoom;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Auswertung der Umgebungsattribute an der Kamera (docs/48 §1.4), je Client-Tick, mit Interpolation zwischen zwei Ticks.
 *
 * <p>Schichten in der Reihenfolge von 26.3: Vorgabewert → Dimension (konstant) → Biom (räumlich: Gauß-Gewichte der
 * Rasterbiome im 6×6×6-Umfeld der Kamera, sonst Rasterbiom an der Kameraposition) → Zeitleisten der Dimension (Weltuhr) →
 * Wetter (Regen, Gewitter; nur Dimensionen mit Wetter). Alle Eingaben stammen vom Server: Uhren aus {@code set_time},
 * Regen-/Gewitterstärke aus {@code game_event}, Biome aus den Chunkdaten; lokal wird nur zwischen Ticks interpoliert.
 */
public final class EnvironmentState {
	private static final double[] KERNEL = {0.0, 1.0, 4.0, 6.0, 4.0, 1.0, 0.0};

	private final EnvironmentData data;
	private EnvironmentData.DimensionData dimension;
	private List<EnvTimeline> timelines = List.of();
	private final Map<EnvAttr, float[]> last = new EnumMap<>(EnvAttr.class);
	private final Map<EnvAttr, float[]> current = new EnumMap<>(EnvAttr.class);
	private boolean first = true;

	public EnvironmentState(final EnvironmentData data) {
		this.data = data;
	}

	public EnvironmentData data() {
		return this.data;
	}

	/** Dimensionstyp (Registry-Name, z. B. {@code minecraft:overworld}); unbekannt → Vorgabewerte ohne Zeitleisten. */
	public void setDimension(final String dimensionType) {
		EnvironmentData.DimensionData d = this.data.dimension(dimensionType);
		if (d != this.dimension) {
			this.dimension = d;
			this.timelines = d == null ? List.of() : this.data.timelinesOf(d);
			this.first = true;
		}
	}

	public EnvironmentData.DimensionData dimension() {
		return this.dimension;
	}

	/** Eingaben eines Ticks. */
	public record Inputs(double x, double y, double z, BiomeZoom.QuartBiomes biomes, List<String> biomeNames, Map<String, Long> clocks, float rain,
		float thunder) {
	}

	/** Neuer Tick: Werte an der Kameraposition berechnen; die bisherigen werden zum Interpolationsstart. */
	public void tick(final Inputs in) {
		Map<String, Double> weights = gaussianWeights(in);
		String here = name(in, in.biomes().biome(Math.floorDiv((int) Math.floor(in.x()), 4), Math.floorDiv((int) Math.floor(in.y()), 4),
			Math.floorDiv((int) Math.floor(in.z()), 4)));
		for (EnvAttr a : EnvAttr.values()) {
			float[] v = compute(a, weights, here, in);
			float[] prev = this.current.get(a);
			this.last.put(a, this.first || prev == null ? v : prev);
			this.current.put(a, v);
		}
		this.first = false;
	}

	/** Wert zwischen letztem und aktuellem Tick ({@code partialTick} 0…1). */
	public float[] get(final EnvAttr a, final float partialTick) {
		float[] c = this.current.get(a);
		if (c == null) {
			return a.defaultValue();
		}
		return a.partialTickLerp(partialTick, this.last.get(a), c);
	}

	/** Wert des aktuellen Ticks. */
	public float[] value(final EnvAttr a) {
		float[] c = this.current.get(a);
		return c == null ? a.defaultValue() : c.clone();
	}

	private static String name(final Inputs in, final int id) {
		return id >= 0 && id < in.biomeNames().size() ? in.biomeNames().get(id) : "minecraft:plains";
	}

	/** {@code GaussianSampler}: Kamera/4 − 0,5, je Achse 6 Rasterpunkte, Gewicht = Produkt der verschobenen Kernwerte. */
	private Map<String, Double> gaussianWeights(final Inputs in) {
		Map<String, Double> w = new LinkedHashMap<>();
		double px = in.x() * 0.25 - 0.5;
		double py = in.y() * 0.25 - 0.5;
		double pz = in.z() * 0.25 - 0.5;
		int ix = (int) Math.floor(px);
		int iy = (int) Math.floor(py);
		int iz = (int) Math.floor(pz);
		double rx = px - ix;
		double ry = py - iy;
		double rz = pz - iz;
		for (int z = 0; z < 6; z++) {
			double wz = KERNEL[z + 1] + rz * (KERNEL[z] - KERNEL[z + 1]);
			for (int x = 0; x < 6; x++) {
				double wx = KERNEL[x + 1] + rx * (KERNEL[x] - KERNEL[x + 1]);
				for (int y = 0; y < 6; y++) {
					double wy = KERNEL[y + 1] + ry * (KERNEL[y] - KERNEL[y + 1]);
					String b = name(in, in.biomes().biome(ix - 2 + x, iy - 2 + y, iz - 2 + z));
					w.merge(b, wx * wy * wz, Double::sum);
				}
			}
		}
		return w;
	}

	private float[] compute(final EnvAttr a, final Map<String, Double> weights, final String hereBiome, final Inputs in) {
		float[] v = a.defaultValue();
		if (this.dimension != null && this.dimension.attributes().get(a) != null) {
			v = this.dimension.attributes().get(a).apply(a, v);
		}
		// Biome
		if (a.spatial && !weights.isEmpty()) {
			if (weights.size() == 1) {
				v = applyBiome(a, weights.keySet().iterator().next(), v);
			} else {
				float[] result = null;
				double acc = 0;
				for (Map.Entry<String, Double> e : weights.entrySet()) {
					float[] sv = applyBiome(a, e.getKey(), v);
					acc += e.getValue();
					if (result == null) {
						result = sv;
					} else {
						result = a.lerp((float) (e.getValue() / acc), result, sv);
					}
				}
				v = result;
			}
		} else {
			v = applyBiome(a, hereBiome, v);
		}
		// Zeitleisten
		for (EnvTimeline t : this.timelines) {
			if (t.has(a)) {
				v = t.apply(a, v, in.clocks().getOrDefault(t.clock(), 0L));
			}
		}
		// Wetter
		if (this.dimension != null && this.dimension.canHaveWeather()) {
			v = Weather.apply(a, v, in.rain(), in.thunder());
		}
		return a.sanitize(v);
	}

	private float[] applyBiome(final EnvAttr a, final String biome, final float[] base) {
		EnvironmentData.BiomeData b = this.data.biome(biome);
		EnvModifier m = b == null ? null : b.attributes().get(a);
		return m == null ? base : m.apply(a, base);
	}

	/** Wetterschicht (WeatherAttributes von 26.3): Regen dann Gewitter, je linear um die Stärke zum modifizierten Wert. */
	static final class Weather {
		private static final float[] NIGHT_SKY = {0.48f, 0.48f, 1.0f};

		private Weather() {
		}

		private static EnvModifier rain(final EnvAttr a) {
			return switch (a) {
				case SKY_COLOR -> new EnvModifier(EnvModifier.Op.BLEND_TO_GRAY, new float[]{0.6f, 0.75f});
				case FOG_COLOR -> new EnvModifier(EnvModifier.Op.MULTIPLY, new float[]{0.5f, 0.5f, 0.6f});
				case CLOUD_COLOR -> new EnvModifier(EnvModifier.Op.BLEND_TO_GRAY, new float[]{0.24f, 0.5f});
				case SKY_LIGHT_COLOR -> new EnvModifier(EnvModifier.Op.ALPHA_BLEND, new float[]{NIGHT_SKY[0], NIGHT_SKY[1], NIGHT_SKY[2], 0.3125f});
				case SKY_LIGHT_FACTOR -> new EnvModifier(EnvModifier.Op.ALPHA_BLEND, new float[]{0.24f, 0.3125f});
				case STAR_BRIGHTNESS -> EnvModifier.override(new float[]{0f});
				case SUNRISE_SUNSET_COLOR -> new EnvModifier(EnvModifier.Op.MULTIPLY, new float[]{0.5f, 0.5f, 0.6f, 1.0f});
				default -> null;
			};
		}

		private static EnvModifier thunder(final EnvAttr a) {
			return switch (a) {
				case SKY_COLOR -> new EnvModifier(EnvModifier.Op.BLEND_TO_GRAY, new float[]{0.24f, 0.94f});
				case FOG_COLOR -> new EnvModifier(EnvModifier.Op.MULTIPLY, new float[]{0.25f, 0.25f, 0.3f});
				case CLOUD_COLOR -> new EnvModifier(EnvModifier.Op.BLEND_TO_GRAY, new float[]{0.095f, 0.94f});
				case SKY_LIGHT_COLOR -> new EnvModifier(EnvModifier.Op.ALPHA_BLEND, new float[]{NIGHT_SKY[0], NIGHT_SKY[1], NIGHT_SKY[2], 0.52734375f});
				case SKY_LIGHT_FACTOR -> new EnvModifier(EnvModifier.Op.ALPHA_BLEND, new float[]{0.24f, 0.52734375f});
				case STAR_BRIGHTNESS -> EnvModifier.override(new float[]{0f});
				case SUNRISE_SUNSET_COLOR -> new EnvModifier(EnvModifier.Op.MULTIPLY, new float[]{0.25f, 0.25f, 0.3f, 1.0f});
				default -> null;
			};
		}

		static float[] apply(final EnvAttr a, final float[] v, final float rainLevel, final float thunderLevel) {
			float rainOnly = rainLevel - thunderLevel;
			float[] result = v;
			EnvModifier r = rain(a);
			if (r != null && rainOnly > 0f) {
				result = a.lerp(rainOnly, result, r.apply(a, result));
			}
			EnvModifier t = thunder(a);
			if (t != null && thunderLevel > 0f) {
				result = a.lerp(thunderLevel, result, t.apply(a, result));
			}
			return result;
		}
	}
}
