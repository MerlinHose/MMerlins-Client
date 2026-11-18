package dev.mcresearch.protocolclient.view.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M14 Phase E (docs/56): Skin-Verwaltung ohne Netzwerk. */
class SkinManagerTest {
	private static byte[] png(final int w, final int h, final int argb) throws Exception {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				img.setRGB(x, y, argb);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	@Test
	void defaultSkinFollowsVanillaOrderSlimFirst() {
		// floorMod(hashCode, 18): 0â8 schmal, 9â17 breit (DefaultPlayerSkin)
		UUID a = new UUID(0, 6); // hashCode = 6 â slim/steve
		assertEquals(6, Math.floorMod(a.hashCode(), 18));
		assertEquals("skin:default/slim/steve", SkinManager.defaultSkin(a).texture());
		assertTrue(SkinManager.defaultSkin(a).slim());
		UUID b = new UUID(0, 15); // 15 â wide/steve
		assertEquals("skin:default/wide/steve", SkinManager.defaultSkin(b).texture());
		assertFalse(SkinManager.defaultSkin(b).slim());
	}

	@Test
	void decodesModernAndConvertsLegacySkins() throws Exception {
		SkinManager.SkinImage modern = SkinManager.decode(png(64, 64, 0x80FF0000), "t");
		assertNotNull(modern);
		assertEquals(0xFFFF0000, modern.argb()[0]); // Kopfbereich ohne Alpha (setNoAlpha)
		assertEquals(0x80FF0000, modern.argb()[40]); // Hutbereich (32â¦63, 0â¦15) behÃ¤lt Transparenz
		BufferedImage legacy = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
		legacy.setRGB(4, 16, 0xFF00FF00); // rechtes Bein, Oberseite links
		for (int y = 0; y < 32; y++) {
			for (int x = 32; x < 64; x++) {
				legacy.setRGB(x, y, 0xFFFFFFFF); // Hutbereich ganz deckend → Hut wird durchsichtig (Notch-Regel)
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(legacy, "png", out);
		SkinManager.SkinImage conv = SkinManager.decode(out.toByteArray(), "legacy");
		assertNotNull(conv);
		// gespiegelt nach (16+4, 48): x = 4 + 16 + 4 â 1 â 0 = 23
		assertEquals(0xFF00FF00, conv.argb()[48 * 64 + 23]);
		assertEquals(0x00FFFFFF & 0xFFFFFFFF, conv.argb()[32] & 0xFFFFFFFF);
	}

	@Test
	void rejectsWrongSizesNonPngAndOversizedData() throws Exception {
		assertNull(SkinManager.decode(png(32, 32, 0xFF000000), "small"));
		assertNull(SkinManager.decode(png(128, 128, 0xFF000000), "hd"));
		assertNull(SkinManager.decode("not a png at all, just text padding....".getBytes(StandardCharsets.US_ASCII), "txt"));
		assertNull(SkinManager.decode(new byte[SkinManager.MAX_FILE_BYTES + 1], "big"));
		// IHDR behauptet 64Ã64, Daten sind kaputt â kein Absturz, null
		byte[] good = png(64, 64, 0xFF000000);
		byte[] broken = java.util.Arrays.copyOf(good, 60);
		assertNull(SkinManager.decode(broken, "broken"));
		// IHDR mit riesigen MaÃen wird vor dem Dekodieren abgewiesen
		byte[] bomb = good.clone();
		bomb[16] = 0x7F;
		assertNull(SkinManager.decode(bomb, "bomb"));
	}

	@Test
	void localSkinsByUuidOrNameWithSlimMarkerAndFallback(@TempDir final Path dir) throws Exception {
		UUID u = UUID.fromString("00000000-0000-4000-8000-000000000042");
		Files.write(dir.resolve(u + ".png"), png(64, 64, 0xFF112233));
		Files.write(dir.resolve("Alice.png"), png(64, 64, 0xFF445566));
		Files.createFile(dir.resolve("Alice.slim"));
		Files.write(dir.resolve("Broken.png"), "x".getBytes(StandardCharsets.US_ASCII));
		SkinManager m = new SkinManager(null, dir);
		assertEquals("skin:local/" + u, m.skin(u, "Bob").texture());
		assertFalse(m.skin(u, "Bob").slim());
		UUID other = new UUID(1, 2);
		assertEquals("skin:local/Alice", m.skin(other, "Alice").texture());
		assertTrue(m.skin(other, "Alice").slim());
		// ungÃ¼ltige Datei â Standardskin
		assertEquals(SkinManager.defaultSkin(other).texture(), m.skin(other, "Broken").texture());
		// Pfad-Ausbruch Ã¼ber den Namen wird nicht versucht
		assertEquals(SkinManager.defaultSkin(other).texture(), m.skin(other, "../x").texture());
		assertNotNull(m.image("skin:local/Alice"));
		assertEquals(0xFF445566, m.image("skin:local/Alice").argb()[0]);
		assertNull(m.image("skin:local/../../etc"));
		assertTrue(m.cacheSize() >= 2);
	}

	@Test
	void profileTexturesArePreparedButNeverDownloaded() {
		SkinManager m = new SkinManager(null, null);
		UUID u = new UUID(0, 6); // Standard: schmal
		String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc\"}}}";
		SkinManager.ProfileTextures t = m.profileTextures(u, Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
		assertNotNull(t);
		assertFalse(t.slim());
		assertEquals("http://textures.minecraft.net/texture/abc", t.skinUrl());
		// Modell aus dem Profil (breit) wird Ã¼bernommen, Bild bleibt ein lokaler Standardskin
		assertEquals("skin:default/wide/steve", m.skin(u, "x").texture());
		assertNull(m.profileTextures(u, "%%%"));
	}
}
