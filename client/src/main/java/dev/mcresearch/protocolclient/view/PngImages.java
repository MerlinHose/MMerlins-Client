package dev.mcresearch.protocolclient.view;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

/**
 * M12 (docs/47 §6): PNG-Dekodierung mit den gespeicherten Rohwerten, wie Vanilla sie verwendet.
 *
 * <p>Java ImageIO legt Graustufen-PNGs (Farbtyp 0 und 4) in einem <em>linearen</em> Grau-Farbraum ab; {@link BufferedImage#getRGB}
 * rechnet die Werte dann nach sRGB um und hellt sie auf (z. B. {@code stone.png}: Rohwert 143 → 197). Vanilla liest die Bytes
 * unverändert. Betroffen sind in 26.3 589 Texturen (Stein, Gras, Laub, Wasser-Überlagerung, Abbaustufen …). Palette-, RGB- und
 * RGBA-Bilder bleiben unverändert.
 */
public final class PngImages {
	private PngImages() {
	}

	/** Liest ein Bild; Graustufenbilder werden mit Rohwerten nach ARGB übertragen. {@code null}, wenn kein Bild erkannt wird. */
	public static BufferedImage read(final InputStream in) throws IOException {
		return raw(ImageIO.read(in));
	}

	/** Ersetzt ein Bild im Grau-Farbraum durch ein ARGB-Bild mit unveränderten Stichprobenwerten. */
	public static BufferedImage raw(final BufferedImage img) {
		if (img == null) {
			return null;
		}
		ColorModel cm = img.getColorModel();
		if (cm.getColorSpace().getType() != ColorSpace.TYPE_GRAY) {
			return img;
		}
		Raster r = img.getRaster();
		int w = img.getWidth();
		int h = img.getHeight();
		int bands = r.getNumBands();
		int maxG = (1 << r.getSampleModel().getSampleSize(0)) - 1;
		int maxA = bands > 1 ? (1 << r.getSampleModel().getSampleSize(1)) - 1 : 1;
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		int[] row = new int[w];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int g = scale(r.getSample(x, y, 0), maxG);
				int a = bands > 1 ? scale(r.getSample(x, y, 1), maxA) : 255;
				row[x] = a << 24 | g << 16 | g << 8 | g;
			}
			out.setRGB(0, y, w, 1, row, 0, w);
		}
		return out;
	}

	/** Tiefe auf 8 Bit: 16 Bit wie stb_image ({@code >> 8}), kleinere Tiefen auf 0–255 gestreckt. */
	private static int scale(final int v, final int max) {
		if (max == 255) {
			return v;
		}
		return max > 255 ? v >> 8 : v * 255 / max;
	}
}
