package dev.mcresearch.protocolclient.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.view.Face;
import org.junit.jupiter.api.Test;

/** Blockauswahl (docs/27 §1): Umriss statt Kollision, Seiten, Trefferpunkte, Reichweite, negative Koordinaten. */
class BlockRaycastTest {
	private static final ShapeRegistry SHAPES = ShapeRegistry.get();
	private static final double EYE = 1.62F;

	private static BlockRaycast.Hit pick(final TestWorld w, final double x, final double y, final double z, final float yaw, final float pitch) {
		return BlockRaycast.pick(w, SHAPES, x, y + EYE, z, yaw, pitch, BlockRaycast.DEFAULT_BLOCK_RANGE, BlockRaycast.DEFAULT_ENTITY_RANGE);
	}

	@Test
	void viewVectorMatchesMinecraftConvention() {
		Vec3d south = BlockRaycast.viewVector(0f, 0f);
		assertEquals(1.0, south.z(), 1e-6);
		Vec3d west = BlockRaycast.viewVector(0f, 90f);
		assertEquals(-1.0, west.x(), 1e-6, "yaw 90 looks −X");
		Vec3d down = BlockRaycast.viewVector(90f, 0f);
		assertEquals(-1.0, down.y(), 1e-6, "pitch 90 looks down");
	}

	@Test
	void lookingDownHitsTopFaceOfTheBlockBelow() {
		TestWorld w = new TestWorld().floor(63, 5);
		BlockRaycast.Hit h = pick(w, 0.5, 64, 0.5, 0f, 90f);
		assertFalse(h.miss());
		assertEquals(0, h.blockX());
		assertEquals(63, h.blockY());
		assertEquals(Face.UP, h.face());
		assertEquals(64.0, h.y(), 1e-9);
		assertEquals(0.5, h.x(), 1e-6);
	}

	@Test
	void sideFacesAndNegativeCoordinates() {
		TestWorld w = new TestWorld().set(-3, 65, -1, TestWorld.STONE);
		// bei (−0,5 64 −0,5) Blick nach Westen (Yaw 90) auf Augenhöhe 65,62 → Block (−3, 65, −1), Ostseite
		BlockRaycast.Hit h = pick(w, -0.5, 64, -0.5, 90f, 0f);
		assertFalse(h.miss(), h.toString());
		assertEquals(-3, h.blockX());
		assertEquals(65, h.blockY());
		assertEquals(-1, h.blockZ());
		assertEquals(Face.EAST, h.face());
		assertEquals(-2.0, h.x(), 1e-9);
	}

	@Test
	void outlineNotCollisionDecidesTheHit() {
		int fence = TestWorld.id("minecraft:oak_fence", "east=false,north=false,south=false,waterlogged=false,west=false");
		int grass = TestWorld.id("minecraft:short_grass", "");
		// Zaun: Kollision 1,5 hoch, Umriss nur 1,0 → Strahl bei y 1,2 über dem Zaun geht vorbei
		TestWorld w = new TestWorld().set(0, 64, 2, fence);
		BlockRaycast.Hit over = BlockRaycast.pick(w, SHAPES, 0.5, 65.2, 0.5, 0f, 0f, 4.5, 3.0);
		assertTrue(over.miss(), "ray at y=65.2 passes over the fence outline (top 65.0): " + over);
		BlockRaycast.Hit post = BlockRaycast.pick(w, SHAPES, 0.5, 64.5, 0.5, 0f, 0f, 4.5, 3.0);
		assertEquals(2.375, post.z(), 1e-9, "hits the post at 6/16");
		assertEquals(Face.NORTH, post.face());
		// Gras: keine Kollision, aber ein Umriss → wird ausgewählt
		TestWorld g = new TestWorld().set(0, 64, 2, grass);
		BlockRaycast.Hit gh = BlockRaycast.pick(g, SHAPES, 0.5, 64.5, 0.5, 0f, 0f, 4.5, 3.0);
		assertFalse(gh.miss());
		// Gras hat eine Offset-Funktion (XYZ), aber TallGrassBlock#getShape wendet sie nicht an: Umriss 2/16 eingerückt,
		// unabhängig von der Position (Vanilla-Vergleich m7-picks3: short_grass/fern bei z = −29,125)
		assertEquals(2.125, gh.z(), 1e-9);
		assertEquals(SHAPES.outlineShape(grass, 0, 2).boxes(), SHAPES.outlineShape(grass, -16, -30).boxes());
	}

	@Test
	void flowerOutlineFollowsTheXzOffsetGrassDoesNot() {
		int poppy = TestWorld.id("minecraft:poppy", "");
		int fern = TestWorld.id("minecraft:fern", "");
		// Mth.getSeed(-16, 0, -30): dx = 1/60, dz = 0,15 (FlowerBlock#getShape: SHAPE.move(getOffset(pos)), XZ, kein y)
		Aabb p = SHAPES.outlineShape(poppy, -16, -30).boxes().getFirst();
		Aabb p0 = SHAPES.outlineShape(poppy, 0, 0).boxes().getFirst(); // seed 0 → (−0,25, 0, −0,25)
		assertEquals(0.15 - -0.25, p.minZ() - p0.minZ(), 1e-6);
		assertEquals(p0.minY(), p.minY(), 0.0, "XZ offset: no vertical shift");
		assertEquals(SHAPES.outlineShape(fern, 0, 0).boxes(), SHAPES.outlineShape(fern, -16, -30).boxes());
	}

	@Test
	void rangeFilterAndCreativeBonus() {
		TestWorld w = new TestWorld().set(0, 65, 5, TestWorld.STONE);
		// Blockfläche bei z=5, Auge bei z=0,5 → Abstand 4,5: nicht "näher als 4,5" → Fehlschlag
		BlockRaycast.Hit survival = pick(w, 0.5, 64, 0.5, 0f, 0f);
		assertTrue(survival.miss());
		BlockRaycast.Hit creative = BlockRaycast.pick(w, SHAPES, 0.5, 64 + EYE, 0.5, 0f, 0f, 4.5 + BlockRaycast.CREATIVE_BLOCK_BONUS, 3.0);
		assertFalse(creative.miss());
		assertEquals(Face.NORTH, creative.face());
	}

	@Test
	void startingInsideABlockGivesInsideHit() {
		TestWorld w = new TestWorld().set(0, 65, 0, TestWorld.STONE);
		BlockRaycast.Hit h = pick(w, 0.5, 64, 0.5, 0f, 0f);
		assertTrue(h.inside());
		assertEquals(65, h.blockY());
		assertEquals(Face.NORTH, h.face(), "opposite of the view direction (south)");
	}

	@Test
	void stairsSelectTheStepThatIsHit() {
		int stairs = TestWorld.id("minecraft:oak_stairs", "facing=south,half=bottom,shape=straight,waterlogged=false");
		TestWorld w = new TestWorld().set(0, 64, 2, stairs);
		// Strahl in Höhe 64,25 trifft die untere Platte an der Nordseite (z=2); in Höhe 64,75 erst die Oberstufe (z=2,5)
		assertEquals(2.0, BlockRaycast.pick(w, SHAPES, 0.5, 64.25, 0.5, 0f, 0f, 4.5, 3).z(), 1e-9);
		assertEquals(2.5, BlockRaycast.pick(w, SHAPES, 0.5, 64.75, 0.5, 0f, 0f, 4.5, 3).z(), 1e-9);
	}

	// ---- Regression (M7, docs/29 §1): negative Koordinaten, Blockgrenzen, Treffer von innen ----

	private static BlockRaycast.Hit clip(final TestWorld w, final double x0, final double y0, final double z0, final double x1, final double y1,
		final double z1) {
		return BlockRaycast.clip(w, SHAPES, new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1));
	}

	@Test
	void negativeCoordinatesAllSixFaces() {
		// Block (−5, −3, −7): Strahlen aus allen sechs Richtungen, Trefferpunkt jeweils auf der Blockgrenze
		TestWorld w = new TestWorld().set(-5, -3, -7, TestWorld.STONE);
		double cx = -4.5, cy = -2.5, cz = -6.5;
		Object[][] cases = {
			{cx + 3, cy, cz, Face.EAST, -4.0}, {cx - 3, cy, cz, Face.WEST, -5.0},
			{cx, cy + 3, cz, Face.UP, -2.0}, {cx, cy - 3, cz, Face.DOWN, -3.0},
			{cx, cy, cz + 3, Face.SOUTH, -6.0}, {cx, cy, cz - 3, Face.NORTH, -7.0}};
		for (Object[] c : cases) {
			BlockRaycast.Hit h = clip(w, (double) c[0], (double) c[1], (double) c[2], cx, cy, cz);
			assertFalse(h.miss(), c[3] + ": " + h);
			assertEquals(-5, h.blockX());
			assertEquals(-3, h.blockY());
			assertEquals(-7, h.blockZ());
			assertEquals(c[3], h.face());
			double coord = switch ((Face) c[3]) {
				case EAST, WEST -> h.x();
				case UP, DOWN -> h.y();
				default -> h.z();
			};
			assertEquals((double) c[4], coord, 1e-9, c[3] + ": " + h);
			assertFalse(h.inside());
		}
	}

	@Test
	void rayAlongABlockBoundaryVisitsOnlyTheStartColumn() {
		// Strahl exakt in der Ebene x = 0 (dx = 0): traverseBlocks bleibt in Spalte x = 0 (floor(0) = 0); ein Block bei x = −1
		// berührt die Ebene nur und wird nicht geprüft – wie Vanilla BlockGetter#traverseBlocks
		TestWorld onlyWest = new TestWorld().set(-1, 64, -3, TestWorld.STONE);
		assertTrue(clip(onlyWest, 0.0, 64.5, 0.5, 0.0, 64.5, -4.5).miss());
		TestWorld east = new TestWorld().set(0, 64, -3, TestWorld.STONE);
		BlockRaycast.Hit h = clip(east, 0.0, 64.5, 0.5, 0.0, 64.5, -4.5);
		assertFalse(h.miss());
		assertEquals(0, h.blockX());
		assertEquals(Face.SOUTH, h.face());
		assertEquals(-2.0, h.z(), 1e-9);
		assertEquals(0.0, h.x(), 0.0);
	}

	@Test
	void negativeBoundaryColumnUsesFloor() {
		// gleiche Ebene bei x = −2: floor(−2) = −2, also wird der Block bei x = −2 geprüft, nicht der bei x = −3
		TestWorld w = new TestWorld().set(-3, 64, -3, TestWorld.STONE).set(-2, 64, -4, TestWorld.STONE);
		BlockRaycast.Hit h = clip(w, -2.0, 64.5, 0.5, -2.0, 64.5, -5.5);
		assertEquals(-2, h.blockX(), h.toString());
		assertEquals(-4, h.blockZ());
		assertEquals(-3.0, h.z(), 1e-9);
	}

	@Test
	void startOnAFaceCountsAsInside() {
		// Start genau auf der Südfläche (z = −2) des Blocks z = −3, Blick nach Norden: VoxelShape#clip prüft from + 0,001·diff –
		// dieser Punkt liegt im Block → Treffer "von innen", Seite = Gegenrichtung des Blicks
		TestWorld w = new TestWorld().set(0, 64, -3, TestWorld.STONE);
		BlockRaycast.Hit h = clip(w, 0.5, 64.5, -2.0, 0.5, 64.5, -6.0);
		assertTrue(h.inside(), h.toString());
		assertEquals(-3, h.blockZ());
		assertEquals(Face.SOUTH, h.face());
		assertEquals(-2.0 - 0.004, h.z(), 1e-12, "test point from + 0.001·diff");
		// Grenze des Innentests = 0,001 · Strahllänge (hier ≈ 0,004): 1e-4 vor der Fläche zählt noch als innen,
		// 0,01 davor ist ein regulärer Flächentreffer
		assertTrue(clip(w, 0.5, 64.5, -1.9999, 0.5, 64.5, -6.0).inside());
		BlockRaycast.Hit before = clip(w, 0.5, 64.5, -1.99, 0.5, 64.5, -6.0);
		assertFalse(before.inside());
		assertEquals(Face.SOUTH, before.face());
		assertEquals(-2.0, before.z(), 1e-9);
	}

	@Test
	void insidePartialOutlineOnlyWhenInsideTheShape() {
		int slab = TestWorld.id("minecraft:oak_slab", "type=bottom,waterlogged=false");
		TestWorld w = new TestWorld().set(-1, 64, -1, slab);
		// Auge in der leeren oberen Hälfte: kein Innentreffer, sondern die Oberseite der Platte (y = 64,5)
		BlockRaycast.Hit top = clip(w, -0.5, 64.75, -0.5, -0.5, 60, -0.5);
		assertFalse(top.inside());
		assertEquals(Face.UP, top.face());
		assertEquals(64.5, top.y(), 1e-9);
		assertEquals(-1, top.blockX());
		// Auge in der Platte: Innentreffer, Seite = Gegenrichtung (Blick nach Westen → EAST)
		BlockRaycast.Hit in = clip(w, -0.5, 64.25, -0.5, -5.5, 64.25, -0.5);
		assertTrue(in.inside());
		assertEquals(Face.EAST, in.face());
		assertEquals(-1, in.blockZ());
	}

	@Test
	void invalidRangeRejected() {
		TestWorld w = new TestWorld();
		assertThrows(IllegalArgumentException.class, () -> BlockRaycast.pick(w, SHAPES, 0, 64, 0, 0, 0, -1, 3));
		assertThrows(IllegalArgumentException.class, () -> BlockRaycast.pick(w, SHAPES, 0, 64, 0, 0, 0, 4.5, 100));
	}
}
