package dev.mcresearch.protocolclient.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * M12-Regression (docs/47 §6): Graustufen-PNGs liefern die gespeicherten Rohwerte wie Vanilla, nicht die von ImageIO über den
 * linearen Grau-Farbraum aufgehellten sRGB-Werte (stone.png: 143, nicht 197).
 */
class PngImagesTest {
	@Test
	void grayscaleTexturesKeepRawSampleValues() throws Exception {
		Optional<Path> jar = ResourceModelsTest.localJar();
		assumeTrue(jar.isPresent(), "local Minecraft 26.3 client jar not found");
		try (ZipFile z = new ZipFile(jar.get().toFile())) {
			BufferedImage stone = read(z, "stone");
			assertEquals(0xFF8F8F8F, stone.getRGB(0, 0), "stone.png raw sample 143 (Farbtyp 0)");
			assertEquals(0xFF949494, read(z, "grass_block_top").getRGB(0, 0), "grass_block_top.png raw sample 148");
			// Palette-Bild unverändert
			assertEquals(0xFFCFD5D6, read(z, "white_concrete").getRGB(0, 0));
			// Grau mit Alpha (Farbtyp 4): Alpha bleibt erhalten
			BufferedImage leaves = read(z, "spruce_leaves");
			assertEquals(16, leaves.getWidth());
		}
	}

	private static BufferedImage read(final ZipFile z, final String name) throws Exception {
		try (InputStream in = z.getInputStream(z.getEntry("assets/minecraft/textures/block/" + name + ".png"))) {
			return PngImages.read(in);
		}
	}
}
