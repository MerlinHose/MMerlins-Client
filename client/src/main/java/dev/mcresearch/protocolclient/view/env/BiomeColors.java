package dev.mcresearch.protocolclient.view.env;

import java.util.Random;

/**
 * Biomfarben (docs/47 §4): Gras/Laub/trockenes Laub aus der Colormap nach Klima (Temperatur, Niederschlag) oder der im Biom
 * festgelegten Farbe; Grasmodifikatoren {@code dark_forest} und {@code swamp}; Wasserfarbe aus den Biomeffekten.
 *
 * <p>Colormap-Abfrage wie in 26.3: t = clamp(Temperatur), r = clamp(Niederschlag) · t, Pixel (⌊(1−t)·255⌋, ⌊(1−r)·255⌋).
 * Sumpfgras: 2D-Simplex-Rauschen (eigene Umsetzung des Standardverfahrens, Permutation aus dem Zufallsgenerator mit Startwert
 * 2345 wie die Spielvorlage) an (x·0,0225, z·0,0225) &lt; −0,1 → 0x4C763C, sonst 0x6A7039.
 */
public final class BiomeColors {
	/** Vorgaben der Spielklassen, wenn eine Colormap fehlt. */
	static final int GRASS_FALLBACK = 0xFF00FF;
	static final int FOLIAGE_FALLBACK = 0x48B518;
	static final int DRY_FOLIAGE_FALLBACK = 0x5C3C32;

	private final int[] grass;
	private final int[] foliage;
	private final int[] dryFoliage;
	private static final Simplex2 SWAMP_NOISE = new Simplex2(2345L);

	public BiomeColors(final EnvironmentData data) {
		this.grass = data.grassMap();
		this.foliage = data.foliageMap();
		this.dryFoliage = data.dryFoliageMap();
	}

	static int colormap(final double temp, final double rain, final int[] pixels, final int fallback) {
		double r = rain * temp;
		int x = (int) ((1.0 - temp) * 255.0);
		int y = (int) ((1.0 - r) * 255.0);
		int index = y << 8 | x;
		return index < 0 || index >= pixels.length ? fallback : pixels[index] & 0xFFFFFF;
	}

	private static double clamp01(final float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	public int grass(final EnvironmentData.BiomeData b, final double x, final double z) {
		int base = b.grassColor() != null ? b.grassColor() : colormap(clamp01(b.temperature()), clamp01(b.downfall()), this.grass, GRASS_FALLBACK);
		return switch (b.grassModifier()) {
			case "dark_forest" -> ((base & 0xFEFEFE) + 0x28340A) >> 1;
			case "swamp" -> SWAMP_NOISE.get(x * 0.0225, z * 0.0225) < -0.1 ? 0x4C763C : 0x6A7039;
			default -> base;
		};
	}

	public int foliage(final EnvironmentData.BiomeData b) {
		return b.foliageColor() != null ? b.foliageColor() : colormap(clamp01(b.temperature()), clamp01(b.downfall()), this.foliage, FOLIAGE_FALLBACK);
	}

	public int dryFoliage(final EnvironmentData.BiomeData b) {
		return b.dryFoliageColor() != null ? b.dryFoliageColor()
			: colormap(clamp01(b.temperature()), clamp01(b.downfall()), this.dryFoliage, DRY_FOLIAGE_FALLBACK);
	}

	public int water(final EnvironmentData.BiomeData b) {
		return b.waterColor();
	}

	/** Gras-Vorgabefarbe ohne Biom (Gegenstände, Partikel): Colormap bei (0,5; 1,0). */
	public int defaultGrass() {
		return colormap(0.5, 1.0, this.grass, GRASS_FALLBACK);
	}

	/**
	 * 2D-Simplex-Rauschen (Gustavson), Gradiententabelle der 12 Kantenrichtungen; Permutation: drei Gleitkommazahlen verworfen
	 * (Versatz × 0), dann Fisher-Yates mit {@code nextInt(256 − i)}.
	 */
	static final class Simplex2 {
		private static final int[][] GRAD = {{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1}, {0, 1, 1},
			{0, -1, 1}, {0, 1, -1}, {0, -1, -1}};
		private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
		private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
		private final int[] perm = new int[256];

		Simplex2(final long seed) {
			Random r = new Random(seed);
			r.nextDouble();
			r.nextDouble();
			r.nextDouble();
			for (int i = 0; i < 256; i++) {
				this.perm[i] = i;
			}
			for (int i = 0; i < 256; i++) {
				int j = r.nextInt(256 - i) + i;
				int t = this.perm[i];
				this.perm[i] = this.perm[j];
				this.perm[j] = t;
			}
		}

		private int p(final int x) {
			return this.perm[x & 255];
		}

		private static double corner(final int g, final double x, final double y) {
			double t = 0.5 - x * x - y * y;
			if (t < 0) {
				return 0;
			}
			t *= t;
			return t * t * (GRAD[g][0] * x + GRAD[g][1] * y);
		}

		float get(final double xin, final double yin) {
			double s = (xin + yin) * F2;
			int i = (int) Math.floor(xin + s);
			int j = (int) Math.floor(yin + s);
			double t = (i + j) * G2;
			double x0 = xin - (i - t);
			double y0 = yin - (j - t);
			int i1 = x0 > y0 ? 1 : 0;
			int j1 = x0 > y0 ? 0 : 1;
			double x1 = x0 - i1 + G2;
			double y1 = y0 - j1 + G2;
			double x2 = x0 - 1.0 + 2.0 * G2;
			double y2 = y0 - 1.0 + 2.0 * G2;
			int ii = i & 255;
			int jj = j & 255;
			int g0 = p(ii + p(jj)) % 12;
			int g1 = p(ii + i1 + p(jj + j1)) % 12;
			int g2 = p(ii + 1 + p(jj + 1)) % 12;
			return (float) (70.0 * (corner(g0, x0, y0) + corner(g1, x1, y1) + corner(g2, x2, y2)));
		}
	}
}
