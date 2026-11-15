package dev.mcresearch.protocolclient.view.env;

/**
 * Niederschlagsart einer Position (docs/48 §6): {@code Biome#getPrecipitationAt}. Ohne Niederschlag im Biom → keiner; sonst
 * Schnee, wenn die höhenkorrigierte Temperatur unter 0,15 liegt, sonst Regen. Höhenkorrektur oberhalb Meereshöhe + 17:
 * t − (Rauschen(x/8, z/8)·8 + y − (Meer + 17)) · 0,05/40 mit 2D-Simplex-Rauschen (Startwert 1234, wie die Vorlage).
 * Der Temperaturmodifikator {@code frozen} (gefrorene Ozeane) ist nicht umgesetzt (docs/48 §8).
 */
public final class Precipitation {
	public enum Kind {
		NONE, RAIN, SNOW
	}

	private static final BiomeColors.Simplex2 TEMPERATURE_NOISE = new BiomeColors.Simplex2(1234L);

	private Precipitation() {
	}

	public static float temperature(final EnvironmentData.BiomeData b, final int x, final int y, final int z, final int seaLevel) {
		float t = b.temperature();
		int snowLevel = seaLevel + 17;
		if (y > snowLevel) {
			float v = TEMPERATURE_NOISE.get(x / 8.0f, z / 8.0f) * 8.0f;
			return t - (v + y - snowLevel) * 0.05f / 40.0f;
		}
		return t;
	}

	public static Kind at(final EnvironmentData.BiomeData b, final int x, final int y, final int z, final int seaLevel) {
		if (b == null || !b.hasPrecipitation()) {
			return Kind.NONE;
		}
		return temperature(b, x, y, z, seaLevel) >= 0.15f ? Kind.RAIN : Kind.SNOW;
	}
}
