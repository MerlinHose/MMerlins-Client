import java.lang.reflect.Method;
import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.EasingType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Analysewerkzeug M12 (docs/49 §6): Referenzwerte aus dem unveränderten Server-JAR für die eigenen Umsetzungen – Sumpfgras-
 * Rauschen ({@code Biome.BIOME_INFO_NOISE}), Abstandsfunktion des Biom-Zooms ({@code BiomeManager#getFiddledDistance}) und die
 * Bézier-Kurve der Zeitleisten (Sonnen-/Mondwinkel). Die Werte landen als Konstanten in {@code EnvironmentReferenceTest}.
 *
 * <pre>java -cp "server/versions/26.3/server-26.3.jar;server/libraries/...jar" analysis/tools/RenderMathCheck.java</pre>
 */
public final class RenderMathCheck {
	public static void main(final String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		System.out.println("# swamp noise: x z value");
		int[][] pts = {{0, 0}, {100, 200}, {-300, 45}, {1234, -987}, {7, 13}, {-5000, 5000}, {300, 300}, {340, 305}, {41, -77}, {2000, 1}};
		for (int[] p : pts) {
			System.out.println(String.format(Locale.ROOT, "N %d %d %.9f", p[0], p[1], Biome.BIOME_INFO_NOISE.get(p[0] * 0.0225, p[1] * 0.0225)));
		}
		System.out.println("# fiddled distance: seed x y z dx dy dz value");
		Method m = BiomeManager.class.getDeclaredMethod("getFiddledDistance", long.class, int.class, int.class, int.class, double.class, double.class,
			double.class);
		m.setAccessible(true);
		long[] seeds = {0L, 123456789L, -8022371985442418345L};
		for (long s : seeds) {
			for (int i = 0; i < 4; i++) {
				int x = i * 37 - 50;
				int y = i * 3 - 4;
				int z = 81 - i * 29;
				double dx = i * 0.25;
				double dy = -0.5 + i * 0.25;
				double dz = 0.75 - i * 0.25;
				System.out.println(String.format(Locale.ROOT, "F %d %d %d %d %.2f %.2f %.2f %.12f", s, x, y, z, dx, dy, dz,
					(double) m.invoke(null, s, x, y, z, dx, dy, dz)));
			}
		}
		System.out.println("# cubic bezier (0.362, 0.241, 0.638, 0.759): x value");
		EasingType e = EasingType.cubicBezier(0.362F, 0.241F, 0.638F, 0.759F);
		for (int i = 0; i <= 10; i++) {
			float x = i / 10f;
			System.out.println(String.format(Locale.ROOT, "B %.1f %.7f", x, e.apply(x)));
		}
	}
}
