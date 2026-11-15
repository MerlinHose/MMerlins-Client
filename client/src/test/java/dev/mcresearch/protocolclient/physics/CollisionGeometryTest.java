package dev.mcresearch.protocolclient.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** AABB, Formen, Achsenkollision und Formtabelle (docs/22 §2–§3). */
class CollisionGeometryTest {
	private static final ShapeRegistry SHAPES = ShapeRegistry.get();

	private static Shape shape(final String name, final String props) {
		return SHAPES.baseShape(TestWorld.id(name, props));
	}

	// ----- AABB -----

	@Test
	void aabbOrdersCornersAndRejectsNonFinite() {
		Aabb b = new Aabb(1, 2, 3, 0, 0, 0);
		assertEquals(new Aabb(0, 0, 0, 1, 2, 3), b);
		assertThrows(IllegalArgumentException.class, () -> new Aabb(0, 0, 0, Double.NaN, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new Aabb(0, 0, 0, 1, Double.POSITIVE_INFINITY, 1));
	}

	@Test
	void intersectionIsStrictTouchingIsNotOverlap() {
		Aabb a = new Aabb(0, 0, 0, 1, 1, 1);
		assertTrue(a.intersects(new Aabb(0.5, 0.5, 0.5, 2, 2, 2)));
		assertFalse(a.intersects(new Aabb(1, 0, 0, 2, 1, 1)), "shared face is not an intersection");
		assertFalse(a.intersects(new Aabb(0, 1, 0, 1, 2, 1)));
		assertTrue(a.intersects(new Aabb(-1, -1, -1, 0.0000001, 0.0000001, 0.0000001)));
	}

	@Test
	void expandTowardsOnlyGrowsInMovementDirection() {
		Aabb a = new Aabb(0, 0, 0, 1, 1, 1);
		assertEquals(new Aabb(-0.5, 0, 0, 1, 1.6, 1), a.expandTowards(-0.5, 0.6, 0));
		assertEquals(new Aabb(0, 0, 0, 1, 1, 3), a.expandTowards(0, 0, 2));
		assertEquals(new Aabb(10, -5, 0, 11, -4, 1), a.move(10, -5, 0));
	}

	// ----- Achsenkollision -----

	@Test
	void axisCollisionStopsAtNearestFaceAndIgnoresPenetratedBoxes() {
		Shape wall = Shape.BLOCK.move(2, 0, 0);
		Aabb player = new Aabb(0.7, 0, 0.2, 1.3, 1.8, 0.8);
		assertEquals(0.7, wall.collide(Axis.X, player, 5.0), 1e-12, "stops flush at x=2");
		assertEquals(-3.0, wall.collide(Axis.X, player, -3.0), "moving away is unaffected");
		Aabb inside = new Aabb(2.2, 0, 0.2, 2.8, 1.8, 0.8);
		assertEquals(1.0, wall.collide(Axis.X, inside, 1.0), "already penetrated box does not block");
		Aabb above = new Aabb(0.7, 1.0, 0.2, 1.3, 2.8, 0.8);
		assertEquals(5.0, wall.collide(Axis.X, above, 5.0), "touching only at y=1 is no cross overlap");
		Aabb grazing = new Aabb(0.7, 1.0 - 1.0E-6, 0.2, 1.3, 2.8, 0.8);
		assertEquals(0.7, wall.collide(Axis.X, grazing, 5.0), 1e-12, "overlap above 1e-7 blocks");
		Aabb withinTolerance = new Aabb(0.7, 1.0 - 5.0E-8, 0.2, 1.3, 2.8, 0.8);
		assertEquals(5.0, wall.collide(Axis.X, withinTolerance, 5.0), "overlap below 1e-7 is ignored");
		assertEquals(0.0, wall.collide(Axis.X, player, 5.0E-8), "|distance| < 1e-7 → 0");
	}

	@Test
	void axisOrderIsYThenLargerHorizontalLast() {
		assertEquals(List.of(Axis.Y, Axis.X, Axis.Z), List.of(Axis.stepOrder(new Vec3d(1, 0, 0.5))));
		assertEquals(List.of(Axis.Y, Axis.Z, Axis.X), List.of(Axis.stepOrder(new Vec3d(0.4, 0, -0.5))));
		assertEquals(List.of(Axis.Y, Axis.X, Axis.Z), List.of(Axis.stepOrder(new Vec3d(0.5, 0, -0.5))), "tie → X before Z");
	}

	@Test
	void diagonalMoveSlidesAlongWall() {
		List<Shape> wall = List.of(Shape.BLOCK.move(1, 0, 0), Shape.BLOCK.move(1, 0, 1));
		Aabb player = new Aabb(0.4, 0, 0.2, 0.7 + 0.2999, 1.8, 0.8);
		Vec3d r = PlayerPhysics.collideWithShapes(new Vec3d(0.3, 0, 0.3), player, wall);
		assertEquals(0.0001, r.x(), 1e-9, "x clipped at the wall");
		assertEquals(0.3, r.z(), 1e-12, "z continues: sliding");
	}

	// ----- Formtabelle (Werte gegen die Loom-Quellen) -----

	@Test
	void tableCoversAllStatesWithVanillaShapes() {
		assertEquals(35_723, SHAPES.stateCount());
		assertTrue(SHAPES.shapeCount() > 300);
		assertTrue(SHAPES.baseShape(0).isEmpty(), "air");
		assertTrue(SHAPES.isFullBlock(1), "stone");
		assertEquals(List.of(new Aabb(0, 0, 0, 1, 1, 1)), SHAPES.baseShape(1).boxes());
		// SlabBlock: Block.column(16, 0, 8) bzw. (16, 8, 16)
		assertEquals(List.of(new Aabb(0, 0, 0, 1, 0.5, 1)), shape("minecraft:oak_slab", "type=bottom,waterlogged=false").boxes());
		assertEquals(List.of(new Aabb(0, 0.5, 0, 1, 1, 1)), shape("minecraft:oak_slab", "type=top,waterlogged=false").boxes());
		assertTrue(SHAPES.isFullBlock(TestWorld.id("minecraft:oak_slab", "type=double,waterlogged=false")));
		// StairBlock: untere Platte + obere Hälfte auf der Seite von "facing"
		Shape stairs = shape("minecraft:oak_stairs", "facing=south,half=bottom,shape=straight,waterlogged=false");
		assertEquals(List.of(new Aabb(0, 0, 0, 1, 0.5, 1), new Aabb(0, 0.5, 0.5, 1, 1, 1)), stairs.boxes());
		// FenceBlock-Pfosten 6..10/16, Höhe 24/16 → große Form
		int fence = TestWorld.id("minecraft:oak_fence", "east=false,north=false,south=false,waterlogged=false,west=false");
		assertEquals(List.of(new Aabb(0.375, 0, 0.375, 0.625, 1.5, 0.625)), SHAPES.baseShape(fence).boxes());
		assertTrue(SHAPES.isLarge(fence));
		int fenceNs = TestWorld.id("minecraft:oak_fence", "east=false,north=true,south=true,waterlogged=false,west=false");
		assertEquals(1.5, SHAPES.baseShape(fenceNs).maxY());
		assertTrue(SHAPES.baseShape(fenceNs).boxes().stream().anyMatch(b -> b.minZ() == 0 && b.maxZ() == 1), "connected fence spans the cell");
		// WallBlock-Pfosten 4..12/16, Höhe 24/16
		int wall = TestWorld.id("minecraft:cobblestone_wall", "east=none,north=none,south=none,up=true,waterlogged=false,west=none");
		assertEquals(List.of(new Aabb(0.25, 0, 0.25, 0.75, 1.5, 0.75)), SHAPES.baseShape(wall).boxes());
		// DoorBlock 3/16 dick, TrapDoorBlock 3/16 hoch, ChestBlock 1..15/16 × 14/16
		Shape door = shape("minecraft:oak_door", "facing=north,half=lower,hinge=left,open=false,powered=false");
		assertEquals(List.of(new Aabb(0, 0, 0.8125, 1, 1, 1)), door.boxes());
		Shape trapdoor = shape("minecraft:oak_trapdoor", "facing=north,half=bottom,open=false,powered=false,waterlogged=false");
		assertEquals(List.of(new Aabb(0, 0, 0, 1, 0.1875, 1)), trapdoor.boxes());
		Shape chest = shape("minecraft:chest", "type=single,facing=north,waterlogged=false");
		assertEquals(List.of(new Aabb(0.0625, 0, 0.0625, 0.9375, 0.875, 0.9375)), chest.boxes());
		// SnowLayerBlock: (layers − 1) · 2/16; ein Layer ohne Kollision
		assertTrue(shape("minecraft:snow", "layers=1").isEmpty());
		assertEquals(0.375, shape("minecraft:snow", "layers=4").maxY());
		// CarpetBlock 1/16; Wasser, Gras, Blumen ohne Kollision
		assertEquals(0.0625, shape("minecraft:white_carpet", "").maxY());
		assertTrue(shape("minecraft:water", "level=0").isEmpty());
		assertTrue(shape("minecraft:short_grass", "").isEmpty());
	}

	@Test
	void stepCandidatesComeFromTheShapeGrid() {
		Shape stairs = shape("minecraft:oak_stairs", "facing=south,half=bottom,shape=straight,waterlogged=false");
		assertEquals(3, stairs.yCoords().length, "stairs grid 0, 0.5, 1");
		float[] c = PlayerPhysics.candidateStepUpHeights(new Aabb(0, 64, 0, 0.6, 65.8, 0.6), List.of(stairs.move(0, 64, 1)), 0.6F, 0.0F);
		assertEquals(1, c.length);
		assertEquals(0.5F, c[0], "0 equals the skipped height, 1 exceeds the step limit");
	}

	@Test
	void offsetBlocksMatchServerProbes() {
		assertEquals(116, SHAPES.probes().size(), "bamboo, pointed_dripstone, sulfur_spike at 3 positions");
		CollisionContext ctx = new CollisionContext(0, false, 0);
		for (ShapeRegistry.Probe p : SHAPES.probes()) {
			Shape own = SHAPES.collisionShape(p.stateId(), p.x(), p.y(), p.z(), ctx);
			assertEquals(p.shape().boxes(), own.boxes(), "state " + p.stateId() + " at " + p.x() + "," + p.y() + "," + p.z());
		}
	}

	@Test
	void seedMatchesMthGetSeedIncludingIntOverflow() {
		// Mth.getSeed(1, 0, 1): s = 3129871 ^ 116129781 → s·s·42317861 + s·11 → >> 16
		long s = 3129871L ^ 116129781L;
		assertEquals((s * s * 42317861L + s * 11L) >> 16, ShapeRegistry.seed(1, 0, 1));
		int x = 1_000_000; // x · 3129871 läuft als int über
		long sx = (long) (x * 3129871) ^ 0L;
		assertEquals((sx * sx * 42317861L + sx * 11L) >> 16, ShapeRegistry.seed(x, 0, 0));
	}

	@Test
	void scaffoldingAndPowderSnowDependOnContext() {
		int scaffold = TestWorld.id("minecraft:scaffolding", "bottom=false,distance=0,waterlogged=false");
		Shape above = SHAPES.collisionShape(scaffold, 0, 64, 0, new CollisionContext(65.0, false, 0));
		assertEquals(1.0, above.maxY(), "standing on top: stable shape");
		assertTrue(SHAPES.collisionShape(scaffold, 0, 64, 0, new CollisionContext(64.5, false, 0)).isEmpty(), "inside: no collision");
		assertTrue(SHAPES.collisionShape(scaffold, 0, 64, 0, new CollisionContext(65.0, true, 0)).isEmpty(), "sneaking descends");
		int bottomScaffold = TestWorld.id("minecraft:scaffolding", "bottom=true,distance=1,waterlogged=false");
		assertEquals(0.125, SHAPES.collisionShape(bottomScaffold, 0, 64, 0, new CollisionContext(64.5, false, 0)).maxY());
		int snow = TestWorld.id("minecraft:powder_snow", "");
		assertTrue(SHAPES.collisionShape(snow, 0, 64, 0, new CollisionContext(65, false, 0)).isEmpty());
		assertEquals((double) 0.9F, SHAPES.collisionShape(snow, 0, 64, 0, new CollisionContext(65, false, 3.0)).maxY());
	}

	// ----- Abfragen über Zellen und Chunkgrenzen -----

	@Test
	void collisionQueryCrossesChunkBordersAndNegativeCoordinates() {
		TestWorld w = new TestWorld().set(-1, 64, -1, TestWorld.STONE).set(0, 64, 0, TestWorld.STONE).set(-17, 64, 15, TestWorld.STONE);
		CollisionWorld cw = new CollisionWorld(w, SHAPES);
		CollisionContext ctx = new CollisionContext(64, false, 0);
		assertEquals(2, cw.hits(new Aabb(-0.5, 64.1, -0.5, 0.5, 64.5, 0.5), ctx).size(), "blocks on both sides of the 0/−1 border");
		List<CollisionWorld.Hit> h = cw.hits(new Aabb(-17, 64, 15, -16.5, 65, 15.5), ctx);
		assertEquals(1, h.size());
		assertEquals(-17, h.getFirst().x());
		assertEquals(new Aabb(-17, 64, 15, -16, 65, 16), h.getFirst().shape().boxes().getFirst());
	}

	@Test
	void largeShapesAreFoundFromTheBorderRing() {
		int fence = TestWorld.id("minecraft:oak_fence", "east=false,north=false,south=false,waterlogged=false,west=false");
		TestWorld w = new TestWorld().set(0, 63, 0, fence).set(2, 63, 0, TestWorld.STONE);
		CollisionWorld cw = new CollisionWorld(w, SHAPES);
		// Box über y=64..65: der Zaun bei y=63 ragt bis 64,5 hinein (Randzelle, große Form)
		Aabb box = new Aabb(0.2, 64, 0.2, 0.8, 65, 0.8);
		List<CollisionWorld.Hit> hits = cw.hits(box, new CollisionContext(64, false, 0));
		assertEquals(1, hits.size());
		assertEquals(63, hits.getFirst().y());
	}

	@Test
	void unloadedCellsCollideAsSolidAndAreReported() {
		TestWorld w = new TestWorld(4);
		CollisionWorld cw = new CollisionWorld(w, SHAPES);
		CollisionWorld.Result r = cw.collisions(new Aabb(4.5, 64, 0, 5.5, 65, 1), new CollisionContext(64, false, 0));
		assertTrue(r.touchedUnloaded());
		assertEquals(1, r.shapes().size());
		assertThrows(IllegalArgumentException.class, () -> cw.hits(new Aabb(-100, 0, -100, 100, 64, 100), new CollisionContext(0, false, 0)));
	}

	@Test
	void trigTableMatchesMinecraft() {
		assertEquals(0.0F, Trig.sin(0));
		assertEquals(1.0F, Trig.cos(0));
		float sin90 = Trig.sin(90 * (float) (Math.PI / 180.0));
		assertEquals(1.0F, sin90, 1e-6F);
		assertNotNull(Trig.class);
		// Tabellenwert weicht von Math.sin ab (Quantisierung auf 65536 Schritte)
		double a = 0.123456;
		assertTrue(Math.abs(Trig.sin(a) - Math.sin(a)) > 1e-7);
	}
}
