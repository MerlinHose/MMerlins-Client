package dev.mcresearch.protocolclient.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** M14: Feuer um brennende Entities wie {@code FlameFeatureRenderer} (Bildvergleich m14-visual1: Feuer fehlte). */
class FlameGeometryTest {
	@Test
	void fireIsShownOnlyWhileTheFlagIsSetAndNotForFireImmuneTypes() {
		assertTrue(FlameGeometry.showsFire("zombie", 0x01, false));
		assertFalse(FlameGeometry.showsFire("zombie", 0x00, false), "extinguished: flag cleared");
		assertFalse(FlameGeometry.showsFire("zombie", 0x02, false), "other flags (sneaking) are not fire");
		assertFalse(FlameGeometry.showsFire("blaze", 0x01, false), "fire immune");
		assertFalse(FlameGeometry.showsFire("wither_skeleton", 0x01, false));
		assertFalse(FlameGeometry.showsFire("player", 0x01, true), "spectator");
	}

	@Test
	void layersAndOffsetScaleWithTheEntitySize() {
		// Zombie 0,6 × 1,95: s = 0,84, h = 2,32 → 6 Lagen, z-Versatz 0,3 − 2 · 0,02
		assertEquals(6, FlameGeometry.layers(0.6F, 1.95F));
		assertEquals(0.3F - 2 * 0.02F, FlameGeometry.zOffset(0.6F, 1.95F), 0.0F);
		// Huhn 0,4 × 0,7: s = 0,56, h = 1,25 → 3 Lagen, Versatz 0,3 − 1 · 0,02
		assertEquals(3, FlameGeometry.layers(0.4F, 0.7F));
		assertEquals(0.28F, FlameGeometry.zOffset(0.4F, 0.7F), 0.0F);
		// breit und flach (Boot 1,375 × 0,5625): h = 0,29 → 1 Lage, Versatz 0,3
		assertEquals(1, FlameGeometry.layers(1.375F, 0.5625F));
		assertEquals(0.3F, FlameGeometry.zOffset(1.375F, 0.5625F), 0.0F);
		assertEquals(0.84F, FlameGeometry.scale(0.6F), 1e-6F);
	}

	@Test
	void quadsAlternateSpritesShrinkAndMirrorEverySecondPair() {
		// genau passende Kapazität (m14-visual4: Absturz „mesh exceeds 28 vertices“ bei zu kleiner Kapazität)
		MeshBuilder b = FlameGeometry.builder(4);
		FlameGeometry.build(b, 4, new float[]{0.0F, 0.0F, 0.1F, 0.1F}, new float[]{0.5F, 0.5F, 0.6F, 0.6F}, MeshBuilder.rgba(255, 255, 255, 255));
		assertEquals(24, b.vertexCount(), "4 quads as 2 triangles each");
		for (int n = 1; n <= 12; n++) {
			MeshBuilder all = FlameGeometry.builder(n);
			FlameGeometry.build(all, n, new float[]{0, 0, 1, 1}, new float[]{0, 0, 1, 1}, -1);
			assertEquals(FlameGeometry.vertices(n), all.vertexCount());
		}
	}
}
