package dev.mcresearch.protocolclient.view;

/**
 * Lightmap (docs/47 §1): 16 × 16 Farben für (Blocklicht, Himmelslicht), eigene Umsetzung der in 26.3 per Shader berechneten
 * Formel. Eingaben aus den Umgebungsattributen ({@code sky_light_factor}, {@code sky_light_color}, {@code ambient_light_color},
 * {@code block_light_tint}, {@code night_vision_color}), dem Blocklicht-Flackern und der Helligkeitsoption (Vorgabe 0,5).
 *
 * <pre>
 *   b(l) = l / (4 − 3 l)                    Helligkeitskurve je Stufe l/15
 *   c    = max(Umgebung, Nachtsicht · Stärke)
 *        + Himmelsfarbe · b(Himmel) · Himmelsfaktor
 *        + mix(Blocktönung, weiß, 0,9 · (2l−1)²) · b(Block) · Blockfaktor
 *   c    = clamp(c − Dunkelheit); Helligkeit: mix(c, „notGamma“(c), Option)
 * </pre>
 * Wird je Client-Tick (bzw. bei Änderung) neu berechnet und hochgeladen; Chunk-Meshes bleiben unverändert.
 */
public final class Lightmap {
	/** Eingaben eines Ticks. */
	public record Params(float skyFactor, float blockFactor, float nightVision, float darkness, float bossDarkening, float brightness,
		float[] blockLightTint, float[] skyLightColor, float[] ambientColor, float[] nightVisionColor) {
		/** Mittag in der Oberwelt ohne Effekte (Prüfwerte, Tests). */
		public static Params noon() {
			return new Params(1f, 1.4f, 0f, 0f, 0f, 0.5f, new float[]{1f, 216 / 255f, 140 / 255f}, new float[]{1f, 1f, 1f},
				new float[]{10 / 255f, 10 / 255f, 10 / 255f}, new float[]{0.6f, 0.6f, 0.6f});
		}
	}

	private Lightmap() {
	}

	static float brightness(final float level) {
		return level / (4.0f - 3.0f * level);
	}

	static float[] notGamma(final float[] c) {
		float max = Math.max(Math.max(c[0], c[1]), c[2]);
		if (max <= 0f) {
			return c.clone();
		}
		float inv = 1.0f - max;
		float scaled = 1.0f - inv * inv * inv * inv;
		float k = scaled / max;
		return new float[]{c[0] * k, c[1] * k, c[2] * k};
	}

	/** Farbe für Blockstufe {@code block} und Himmelsstufe {@code sky} (0–15), RGB 0…1. */
	public static float[] color(final Params p, final int block, final int sky) {
		float blockLevel = block / 15f;
		float skyLevel = sky / 15f;
		float blockB = brightness(blockLevel) * p.blockFactor();
		float skyB = brightness(skyLevel) * p.skyFactor();
		float[] c = new float[3];
		for (int i = 0; i < 3; i++) {
			c[i] = Math.max(p.ambientColor()[i], p.nightVisionColor()[i] * p.nightVision());
			c[i] += p.skyLightColor()[i] * skyB;
			float mixf = 0.9f * (2f * blockLevel - 1f) * (2f * blockLevel - 1f);
			float blockColor = p.blockLightTint()[i] + (1f - p.blockLightTint()[i]) * mixf;
			c[i] += blockColor * blockB;
		}
		float[] darken = {0.7f, 0.6f, 0.6f};
		for (int i = 0; i < 3; i++) {
			c[i] = c[i] + (c[i] * darken[i] - c[i]) * p.bossDarkening();
			c[i] = Math.max(0f, Math.min(1f, c[i] - p.darkness()));
		}
		float[] ng = notGamma(c);
		for (int i = 0; i < 3; i++) {
			c[i] = c[i] + (ng[i] - c[i]) * p.brightness();
		}
		return c;
	}

	/** Alle 256 Einträge als RGBA-Bytes (Zeile = Himmelsstufe, Spalte = Blockstufe), wie die 16×16-Textur. */
	public static byte[] texture(final Params p) {
		byte[] out = new byte[16 * 16 * 4];
		for (int sky = 0; sky < 16; sky++) {
			for (int block = 0; block < 16; block++) {
				float[] c = color(p, block, sky);
				int i = (sky * 16 + block) * 4;
				for (int k = 0; k < 3; k++) {
					out[i + k] = (byte) Math.round(Math.max(0f, Math.min(1f, c[k])) * 255f);
				}
				out[i + 3] = (byte) 255;
			}
		}
		return out;
	}
}
