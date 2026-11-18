import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * M14-Testwerkzeug (docs/56 §5): erzeugt eine eigene 64×64-Test-Skin (einfarbige Flächen je Körperteil, deutlich andere Farben
 * für die zweite Schicht: Hut, Jacke, Ärmel, Hosenbeine), damit Abwicklung, Classic/Slim und Schichtsichtbarkeit im Bild erkennbar
 * sind. Keine Mojang-Grafik.
 *
 * <pre>java analysis/tools/m14/MakeTestSkin.java &lt;ziel.png&gt; [slim]</pre>
 */
public final class MakeTestSkin {
	private static void fill(final BufferedImage img, final int x, final int y, final int w, final int h, final int argb) {
		for (int j = y; j < y + h; j++) {
			for (int i = x; i < x + w; i++) {
				img.setRGB(i, j, argb);
			}
		}
	}

	public static void main(final String[] args) throws Exception {
		boolean slim = args.length > 1 && args[1].equals("slim");
		int arm = slim ? 3 : 4;
		BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		// Grundschicht
		fill(img, 0, 0, 32, 16, 0xFFE0B080); // Kopf (Haut)
		fill(img, 8, 8, 8, 8, 0xFFF0C090); // Gesicht
		fill(img, 9, 11, 2, 1, 0xFF203060); // Augen
		fill(img, 13, 11, 2, 1, 0xFF203060);
		fill(img, 16, 16, 24, 16, 0xFF2060C0); // Körper blau
		fill(img, 40, 16, 8 + 2 * arm, 16, 0xFFC03030); // rechter Arm rot
		fill(img, 32, 48, 8 + 2 * arm, 16, 0xFFC08030); // linker Arm orange
		fill(img, 0, 16, 16, 16, 0xFF306030); // rechtes Bein dunkelgrün
		fill(img, 16, 48, 16, 16, 0xFF60A060); // linkes Bein hellgrün
		// zweite Schicht (teilweise deckend, damit sie im Bild klar sichtbar ist)
		fill(img, 32, 0, 32, 8, 0xFFFFE000); // Hut oben gelb
		fill(img, 32, 8, 32, 2, 0xFFFFE000); // Hutkrempe
		fill(img, 16, 32, 24, 8, 0xFFFFFFFF); // Jacke oberer Teil weiß
		fill(img, 40, 32, 8 + 2 * arm, 4, 0xFFFF00FF); // rechter Ärmel magenta (Bündchen)
		fill(img, 48, 48, 8 + 2 * arm, 4, 0xFF00FFFF); // linker Ärmel cyan
		fill(img, 0, 32, 16, 4, 0xFF000000); // rechtes Hosenbein schwarzer Saum
		fill(img, 0, 48, 16, 4, 0xFF808080); // linkes Hosenbein grauer Saum
		Path out = Path.of(args[0]);
		Files.createDirectories(out.toAbsolutePath().getParent());
		ImageIO.write(img, "png", out.toFile());
		System.out.println("test skin " + (slim ? "slim" : "classic") + " -> " + out);
	}
}
