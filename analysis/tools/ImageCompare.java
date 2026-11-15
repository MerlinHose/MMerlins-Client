import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Analysewerkzeug M12 (docs/49 §3.3): vergleicht Vanilla- und eigene Vergleichsbilder derselben Szene. Ein Pixelvergleich allein
 * wäre ungeeignet (unterschiedliche Renderer, Partikel- und Animationszufall), daher mehrere getrennte Kennzahlen:
 *
 * <ul>
 *   <li>{@code dE}: mittlerer Farbabstand CIE76 (ΔE*ab) je Pixel auf 320×180 verkleinert – Gesamtabweichung.</li>
 *   <li>{@code cellDE}, {@code cells<10}: ΔE der Mittelfarben eines 16×9-Rasters – Farb-/Beleuchtungsabweichung ohne Kantenrauschen.</li>
 *   <li>{@code dL}: Differenz der mittleren Helligkeit L* (eigen − Vanilla) – Beleuchtung insgesamt heller/dunkler.</li>
 *   <li>{@code dChroma}: Differenz der mittleren Buntheit C*ab – Tönung/Sättigung (Biomfarben, Nebel).</li>
 *   <li>{@code ssim}: strukturelle Ähnlichkeit (L*, 8×8-Fenster) – Geometrie und Texturen.</li>
 *   <li>{@code edge}: Korrelation der Sobel-Kantenstärken – Geometrie (Kanten an derselben Stelle).</li>
 *   <li>{@code sky}: mittlere Farbe der oberen 12 % (Vanilla → eigen), nur zur Einordnung.</li>
 * </ul>
 * Schreibt je Szene ein Differenzbild (ΔE je Pixel, 0…50 → schwarz…weiß) und ein Nebeneinanderbild in das Ausgabeverzeichnis.
 *
 * <pre>java analysis/tools/ImageCompare.java &lt;vanillaDir&gt; &lt;ownDir&gt; &lt;outDir&gt;</pre>
 */
public final class ImageCompare {
	private static final int W = 320;
	private static final int H = 180;

	public static void main(final String[] args) throws IOException {
		Path vanilla = Path.of(args[0]);
		Path own = Path.of(args[1]);
		Path out = Path.of(args[2]);
		Files.createDirectories(out);
		List<Path> files = new ArrayList<>();
		try (Stream<Path> s = Files.list(vanilla)) {
			s.filter(p -> p.toString().endsWith(".png")).sorted().forEach(files::add);
		}
		System.out.println("# scene dE cellDE cells<10 cells<20 dL dChroma ssim edge sky(vanilla->own)");
		double sumDe = 0;
		double sumSsim = 0;
		int n = 0;
		for (Path v : files) {
			Path o = own.resolve(v.getFileName());
			if (!Files.exists(o)) {
				System.out.println(v.getFileName() + " missing-own");
				continue;
			}
			BufferedImage a = scale(ImageIO.read(v.toFile()));
			BufferedImage b = scale(ImageIO.read(o.toFile()));
			double[][] la = lab(a);
			double[][] lb = lab(b);
			double de = 0;
			BufferedImage diff = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
			double lA = 0;
			double lB = 0;
			double cA = 0;
			double cB = 0;
			for (int i = 0; i < W * H; i++) {
				double d = dist(la[i], lb[i]);
				de += d;
				int g = (int) Math.min(255, d / 50 * 255);
				diff.setRGB(i % W, i / W, g << 16 | g << 8 | g);
				lA += la[i][0];
				lB += lb[i][0];
				cA += Math.hypot(la[i][1], la[i][2]);
				cB += Math.hypot(lb[i][1], lb[i][2]);
			}
			de /= W * H;
			// Raster 16×9 (je 20×20 Pixel)
			double cellSum = 0;
			int lt10 = 0;
			int lt20 = 0;
			for (int cy = 0; cy < 9; cy++) {
				for (int cx = 0; cx < 16; cx++) {
					double[] ma = meanLab(la, cx * 20, cy * 20, 20, 20);
					double[] mb = meanLab(lb, cx * 20, cy * 20, 20, 20);
					double d = dist(ma, mb);
					cellSum += d;
					if (d < 10) {
						lt10++;
					}
					if (d < 20) {
						lt20++;
					}
				}
			}
			double ssim = ssim(la, lb);
			double edge = edgeCorrelation(la, lb);
			int skyA = meanRgb(a, 0, 0, W, (int) (H * 0.12));
			int skyB = meanRgb(b, 0, 0, W, (int) (H * 0.12));
			String name = v.getFileName().toString().replace(".png", "");
			System.out.printf(Locale.ROOT, "%-18s %6.2f %6.2f %3d/144 %3d/144 %+6.2f %+6.2f %5.3f %5.3f #%06x->#%06x%n", name, de, cellSum / 144, lt10, lt20,
				(lB - lA) / (W * H), (cB - cA) / (W * H), ssim, edge, skyA, skyB);
			ImageIO.write(diff, "png", out.resolve(name + "-diff.png").toFile());
			BufferedImage side = new BufferedImage(W * 2, H, BufferedImage.TYPE_INT_RGB);
			Graphics2D g2 = side.createGraphics();
			g2.drawImage(a, 0, 0, null);
			g2.drawImage(b, W, 0, null);
			g2.dispose();
			ImageIO.write(side, "png", out.resolve(name + "-side.png").toFile());
			sumDe += de;
			sumSsim += ssim;
			n++;
		}
		System.out.printf(Locale.ROOT, "summary scenes=%d meanDE=%.2f meanSSIM=%.3f%n", n, n == 0 ? 0 : sumDe / n, n == 0 ? 0 : sumSsim / n);
	}

	private static BufferedImage scale(final BufferedImage src) {
		BufferedImage dst = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = dst.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		// Flächenmittel: erst halbieren bis nahe Zielgröße, dann bilinear
		BufferedImage cur = src;
		while (cur.getWidth() >= W * 2) {
			BufferedImage half = new BufferedImage(cur.getWidth() / 2, cur.getHeight() / 2, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < half.getHeight(); y++) {
				for (int x = 0; x < half.getWidth(); x++) {
					int r = 0;
					int gg = 0;
					int b = 0;
					for (int k = 0; k < 4; k++) {
						int c = cur.getRGB(Math.min(cur.getWidth() - 1, x * 2 + (k & 1)), Math.min(cur.getHeight() - 1, y * 2 + (k >> 1)));
						r += c >> 16 & 255;
						gg += c >> 8 & 255;
						b += c & 255;
					}
					half.setRGB(x, y, (r / 4) << 16 | (gg / 4) << 8 | b / 4);
				}
			}
			cur = half;
		}
		g.drawImage(cur, 0, 0, W, H, null);
		g.dispose();
		return dst;
	}

	private static double lin(final int c) {
		double v = c / 255.0;
		return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
	}

	private static double f(final double t) {
		return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
	}

	private static double[][] lab(final BufferedImage img) {
		double[][] out = new double[W * H][];
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				int c = img.getRGB(x, y);
				double r = lin(c >> 16 & 255);
				double g = lin(c >> 8 & 255);
				double b = lin(c & 255);
				double X = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
				double Y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
				double Z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
				double fx = f(X);
				double fy = f(Y);
				double fz = f(Z);
				out[y * W + x] = new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
			}
		}
		return out;
	}

	private static double dist(final double[] a, final double[] b) {
		return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
	}

	private static double[] meanLab(final double[][] l, final int x0, final int y0, final int w, final int h) {
		double[] m = new double[3];
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				for (int k = 0; k < 3; k++) {
					m[k] += l[y * W + x][k];
				}
			}
		}
		for (int k = 0; k < 3; k++) {
			m[k] /= w * h;
		}
		return m;
	}

	private static int meanRgb(final BufferedImage img, final int x0, final int y0, final int w, final int h) {
		long r = 0;
		long g = 0;
		long b = 0;
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				int c = img.getRGB(x, y);
				r += c >> 16 & 255;
				g += c >> 8 & 255;
				b += c & 255;
			}
		}
		long n = (long) w * h;
		return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
	}

	/** SSIM auf L* (0–100), 8×8-Fenster mit Schrittweite 4, Konstanten nach Wang et al. für den Wertebereich 100. */
	private static double ssim(final double[][] a, final double[][] b) {
		double c1 = Math.pow(0.01 * 100, 2);
		double c2 = Math.pow(0.03 * 100, 2);
		double sum = 0;
		int n = 0;
		for (int y = 0; y + 8 <= H; y += 4) {
			for (int x = 0; x + 8 <= W; x += 4) {
				double ma = 0;
				double mb = 0;
				for (int j = 0; j < 8; j++) {
					for (int i = 0; i < 8; i++) {
						ma += a[(y + j) * W + x + i][0];
						mb += b[(y + j) * W + x + i][0];
					}
				}
				ma /= 64;
				mb /= 64;
				double va = 0;
				double vb = 0;
				double cov = 0;
				for (int j = 0; j < 8; j++) {
					for (int i = 0; i < 8; i++) {
						double da = a[(y + j) * W + x + i][0] - ma;
						double db = b[(y + j) * W + x + i][0] - mb;
						va += da * da;
						vb += db * db;
						cov += da * db;
					}
				}
				va /= 63;
				vb /= 63;
				cov /= 63;
				sum += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2));
				n++;
			}
		}
		return sum / n;
	}

	private static double[] sobel(final double[][] l) {
		double[] m = new double[W * H];
		for (int y = 1; y < H - 1; y++) {
			for (int x = 1; x < W - 1; x++) {
				double gx = -l[(y - 1) * W + x - 1][0] - 2 * l[y * W + x - 1][0] - l[(y + 1) * W + x - 1][0] + l[(y - 1) * W + x + 1][0]
					+ 2 * l[y * W + x + 1][0] + l[(y + 1) * W + x + 1][0];
				double gy = -l[(y - 1) * W + x - 1][0] - 2 * l[(y - 1) * W + x][0] - l[(y - 1) * W + x + 1][0] + l[(y + 1) * W + x - 1][0]
					+ 2 * l[(y + 1) * W + x][0] + l[(y + 1) * W + x + 1][0];
				m[y * W + x] = Math.hypot(gx, gy);
			}
		}
		return m;
	}

	private static double edgeCorrelation(final double[][] a, final double[][] b) {
		double[] ea = sobel(a);
		double[] eb = sobel(b);
		double ma = 0;
		double mb = 0;
		for (int i = 0; i < ea.length; i++) {
			ma += ea[i];
			mb += eb[i];
		}
		ma /= ea.length;
		mb /= eb.length;
		double cov = 0;
		double va = 0;
		double vb = 0;
		for (int i = 0; i < ea.length; i++) {
			cov += (ea[i] - ma) * (eb[i] - mb);
			va += (ea[i] - ma) * (ea[i] - ma);
			vb += (eb[i] - mb) * (eb[i] - mb);
		}
		return va == 0 || vb == 0 ? 0 : cov / Math.sqrt(va * vb);
	}

}
