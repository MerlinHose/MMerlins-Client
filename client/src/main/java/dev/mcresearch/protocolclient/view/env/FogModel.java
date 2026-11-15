package dev.mcresearch.protocolclient.view.env;

/**
 * Nebel (docs/48 §5), eigene Umsetzung des Verfahrens von 26.3 ({@code FogRenderer} + Umgebungen):
 *
 * <ul>
 *   <li><b>Luft</b>: Farbe {@code fog_color}; ab Sichtweite 4 beim Blick zur Sonne mit {@code sunrise_sunset_color} gemischt
 *       (Anteil = Blickrichtung · Sonnenseite × Alpha); dann zur (bei Regen/Gewitter abgedunkelten) Himmelsfarbe gemischt mit
 *       1 − clamp(skyEnd/16/32 → 0,25…1)^0,25. Abstand {@code fog_start/end_distance}, bei Regen um bis zu −160/−256 verkürzt
 *       (Regennebel nur bei Himmelslicht ≥ 8, geglättet 0,2 je Tick).</li>
 *   <li><b>Wasser</b>: {@code water_fog_color}, {@code water_fog_start/end_distance}, Ende × max(0,25; Wassersicht).</li>
 *   <li><b>Lava</b>: 0x991A00, 0,25…1 (Feuerresistenz 0…5).</li>
 *   <li><b>Sichtweitennebel</b>: Ende = Sichtweite × 16, Beginn = Ende − clamp(Sichtweite·16/10; 4…64).</li>
 *   <li>Unter Wasser bzw. mit Nachtsicht wird die Farbe zur hellsten Komponente hin aufgehellt.</li>
 * </ul>
 */
public final class FogModel {
	/** Ergebnis eines Bildes. */
	public record Fog(float r, float g, float b, float envStart, float envEnd, float rdStart, float rdEnd, float skyEnd, float cloudEnd) {
	}

	public static final int NONE = 0;
	public static final int WATER = 1;
	public static final int LAVA = 2;

	private float rainFogMultiplier;

	/**
	 * @param fluid        Fluid an der Kamera (0, {@link #WATER}, {@link #LAVA})
	 * @param forwardX     Blickrichtung x (Einheitsvektor), für die Sonnenseite
	 * @param skyLightHere Himmelslicht an der Kamera (Regennebel)
	 * @param rainsInBiome Biom an der Kamera hat Niederschlag
	 * @param deltaTicks   seit dem letzten Bild vergangene Ticks (Glättung des Regennebels)
	 */
	public Fog compute(final EnvironmentState env, final float partialTick, final int renderDistance, final int fluid, final float forwardX,
		final float rain, final float thunder, final int skyLightHere, final boolean rainsInBiome, final float deltaTicks, final float waterVision,
		final boolean fireResistance, final float nightVision) {
		float rd = renderDistance * 16f;
		float span = Math.max(4f, Math.min(64f, rd / 10f));
		float[] color;
		float start;
		float end;
		float skyEnd;
		float cloudEnd;
		if (fluid == LAVA) {
			color = new float[]{153 / 255f, 26 / 255f, 0f};
			start = fireResistance ? 0f : 0.25f;
			end = fireResistance ? 5f : 1f;
			skyEnd = end;
			cloudEnd = end;
		} else if (fluid == WATER) {
			color = env.get(EnvAttr.WATER_FOG_COLOR, partialTick);
			start = env.get(EnvAttr.WATER_FOG_START_DISTANCE, partialTick)[0];
			end = env.get(EnvAttr.WATER_FOG_END_DISTANCE, partialTick)[0] * Math.max(0.25f, waterVision);
			skyEnd = end;
			cloudEnd = end;
		} else {
			color = atmosphericColor(env, partialTick, renderDistance, forwardX, rain, thunder);
			float skyFactor = Math.max(0f, Math.min(1f, (skyLightHere - 8.0f) / 7.0f));
			float target = rain * skyFactor * (rainsInBiome ? 1.0f : 0.5f);
			this.rainFogMultiplier += (target - this.rainFogMultiplier) * deltaTicks * 0.2f;
			start = env.get(EnvAttr.FOG_START_DISTANCE, partialTick)[0] - 160.0f * this.rainFogMultiplier;
			float baseEnd = env.get(EnvAttr.FOG_END_DISTANCE, partialTick)[0];
			end = Math.max(Math.min(96.0f, baseEnd), baseEnd - 256.0f * this.rainFogMultiplier);
			skyEnd = Math.min(rd, env.get(EnvAttr.SKY_FOG_END_DISTANCE, partialTick)[0]);
			cloudEnd = env.get(EnvAttr.CLOUD_FOG_END_DISTANCE, partialTick)[0];
		}
		// Aufhellung (Wassersicht bzw. Nachtsicht)
		float brighten = fluid == WATER ? waterVision : nightVision;
		if (color[0] != 0f && color[1] != 0f && color[2] != 0f && brighten > 0f) {
			float k = 1.0f / Math.max(color[0], Math.max(color[1], color[2]));
			for (int i = 0; i < 3; i++) {
				color[i] = color[i] + brighten * (color[i] * k - color[i]);
			}
		}
		return new Fog(color[0], color[1], color[2], start, end, rd - span, rd, skyEnd, cloudEnd);
	}

	/** {@code AtmosphericFogEnvironment#getBaseColor}. */
	static float[] atmosphericColor(final EnvironmentState env, final float partialTick, final int renderDistance, final float forwardX, final float rain,
		final float thunder) {
		float[] fog = env.get(EnvAttr.FOG_COLOR, partialTick);
		if (renderDistance >= 4) {
			float sunAngle = (float) Math.toRadians(env.get(EnvAttr.SUN_ANGLE, partialTick)[0]);
			float sunX = Math.sin(sunAngle) > 0.0 ? -1.0f : 1.0f;
			float looking = forwardX * sunX;
			if (looking > 0.0f) {
				float[] c = env.get(EnvAttr.SUNRISE_SUNSET_COLOR, partialTick);
				float alpha = c[3];
				if (alpha > 0.0f) {
					float t = looking * alpha;
					for (int i = 0; i < 3; i++) {
						fog[i] = fog[i] + t * (c[i] - fog[i]);
					}
				}
			}
		}
		float[] sky = env.get(EnvAttr.SKY_COLOR, partialTick);
		sky = weatherDarken(sky, rain, thunder);
		float skyFogEnd = Math.min(env.get(EnvAttr.SKY_FOG_END_DISTANCE, partialTick)[0] / 16.0f, renderDistance);
		float mixf = 0.25f + (1.0f - 0.25f) * Math.max(0f, Math.min(1f, skyFogEnd / 32.0f));
		mixf = 1.0f - (float) Math.pow(mixf, 0.25);
		return new float[]{fog[0] + mixf * (sky[0] - fog[0]), fog[1] + mixf * (sky[1] - fog[1]), fog[2] + mixf * (sky[2] - fog[2])};
	}

	/** Abdunklung der Himmelsfarbe bei Regen (×(1−r/2), Blau ×(1−0,4 r)) und Gewitter (×(1−t/2)). */
	public static float[] weatherDarken(final float[] c, final float rain, final float thunder) {
		float[] r = c.clone();
		if (rain > 0f) {
			float m = 1.0f - rain * 0.5f;
			float mb = 1.0f - rain * 0.4f;
			r[0] = clamp8(r[0] * m);
			r[1] = clamp8(r[1] * m);
			r[2] = clamp8(r[2] * mb);
		}
		if (thunder > 0f) {
			float m = 1.0f - thunder * 0.5f;
			for (int i = 0; i < 3; i++) {
				r[i] = clamp8(r[i] * m);
			}
		}
		return r;
	}

	private static float clamp8(final float v) {
		return Math.max(0f, Math.min(1f, v));
	}
}
