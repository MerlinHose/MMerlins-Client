package dev.mcresearch.protocolclient.world;

/**
 * Biom an einer Blockposition aus den 4×4×4-Rasterbiomen (docs/47 §4.2): „verwackeltes“ Voronoi-Verfahren mit dem gehashten
 * Weltstartwert aus {@code login}/{@code respawn}. Von den acht umgebenden Rasterpunkten gewinnt der mit dem kleinsten Abstand
 * nach einer pseudozufälligen Verschiebung (lineare Kongruenz, je Achse ±0,45 Rasterweiten). Eigene Umsetzung des
 * dokumentierten Verfahrens; ergibt dieselben Biomgrenzen wie im Spiel.
 */
public final class BiomeZoom {
	/** Rasterbiom (Quart-Koordinaten) → Registry-ID. */
	@FunctionalInterface
	public interface QuartBiomes {
		int biome(int qx, int qy, int qz);
	}

	private BiomeZoom() {
	}

	static long lcg(final long r, final long c) {
		return r * (r * 6364136223846793005L + 1442695040888963407L) + c;
	}

	private static double fiddle(final long r) {
		double uniform = Math.floorMod(r >> 24, 1024) / 1024.0;
		return (uniform - 0.5) * 0.9;
	}

	static double fiddledDistance(final long seed, final int x, final int y, final int z, final double dx, final double dy, final double dz) {
		long r = seed;
		r = lcg(r, x);
		r = lcg(r, y);
		r = lcg(r, z);
		r = lcg(r, x);
		r = lcg(r, y);
		r = lcg(r, z);
		double fx = fiddle(r);
		r = lcg(r, seed);
		double fy = fiddle(r);
		r = lcg(r, seed);
		double fz = fiddle(r);
		return (dz + fz) * (dz + fz) + (dy + fy) * (dy + fy) + (dx + fx) * (dx + fx);
	}

	public static int biome(final long seed, final int x, final int y, final int z, final QuartBiomes source) {
		int ax = x - 2;
		int ay = y - 2;
		int az = z - 2;
		int px = ax >> 2;
		int py = ay >> 2;
		int pz = az >> 2;
		double fx = (ax & 3) / 4.0;
		double fy = (ay & 3) / 4.0;
		double fz = (az & 3) / 4.0;
		int best = 0;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int i = 0; i < 8; i++) {
			boolean xe = (i & 4) == 0;
			boolean ye = (i & 2) == 0;
			boolean ze = (i & 1) == 0;
			double d = fiddledDistance(seed, xe ? px : px + 1, ye ? py : py + 1, ze ? pz : pz + 1, xe ? fx : fx - 1.0, ye ? fy : fy - 1.0, ze ? fz : fz - 1.0);
			if (bestDistance > d) {
				best = i;
				bestDistance = d;
			}
		}
		return source.biome((best & 4) == 0 ? px : px + 1, (best & 2) == 0 ? py : py + 1, (best & 1) == 0 ? pz : pz + 1);
	}
}
