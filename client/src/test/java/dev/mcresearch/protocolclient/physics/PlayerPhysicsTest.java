package dev.mcresearch.protocolclient.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Spielerphysik: Gehen, Reibung, Springen, Fallen, Step-up, Wände, Decken, Chunkgrenzen (docs/21 §3–§7). */
class PlayerPhysicsTest {
	private static final PlayerPhysics.Input F = new PlayerPhysics.Input(true, false, false, false, false);
	private static final PlayerPhysics.Input FL = new PlayerPhysics.Input(true, false, true, false, false);
	private static final PlayerPhysics.Input J = new PlayerPhysics.Input(false, false, false, false, true);
	private static final PlayerPhysics.Input FJ = new PlayerPhysics.Input(true, false, false, false, true);
	private static final int SLAB = TestWorld.id("minecraft:oak_slab", "type=bottom,waterlogged=false");

	private static PlayerPhysics settled(final TestWorld w, final double x, final double y, final double z, final float yaw) {
		PlayerPhysics p = w.player(x, y, z, yaw);
		for (int i = 0; i < 3; i++) {
			p.tick(PlayerPhysics.Input.NONE);
		}
		assertTrue(p.onGround());
		return p;
	}

	private static List<PlayerPhysics.TickResult> run(final PlayerPhysics p, final PlayerPhysics.Input in, final int n) {
		List<PlayerPhysics.TickResult> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(p.tick(in));
		}
		return out;
	}

	@Test
	void inputVectorMatchesKeyboardAndSquareCorrection() {
		float[] straight = PlayerPhysics.modifiedInput(F);
		assertEquals(0.0F, straight[0]);
		assertEquals(0.98F, straight[1]);
		float[] diagonal = PlayerPhysics.modifiedInput(FL);
		assertEquals(1.0, Math.hypot(diagonal[0], diagonal[1]), 1e-6, "diagonal 0.98·√2 is capped at 1");
		float[] opposite = PlayerPhysics.modifiedInput(new PlayerPhysics.Input(true, true, false, false, false));
		assertEquals(0.0F, opposite[0]);
		assertEquals(0.0F, opposite[1]);
	}

	@Test
	void walkingReachesVanillaEquilibriumSpeed() {
		TestWorld w = new TestWorld().floor(63, 60);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		List<PlayerPhysics.TickResult> t = run(p, F, 40);
		double step = t.get(39).z() - t.get(38).z();
		assertEquals(0.21585, step, 1e-4, "≈ 4.317 m/s straight");
		assertEquals((double) 0.98F * (double) 0.1F, t.getFirst().z() - 0.5, 1e-15, "first tick: 0.98F · 0.1F");
		PlayerPhysics q = settled(w, 0.5, 64, 0.5, 0f);
		List<PlayerPhysics.TickResult> d = run(q, FL, 40);
		double diag = Math.hypot(d.get(39).x() - d.get(38).x(), d.get(39).z() - d.get(38).z());
		assertEquals(step / 0.98, diag, 1e-4, "diagonal input length 1.0 instead of 0.98");
		assertTrue(d.get(39).x() > 0.5, "left of yaw 0 (facing +Z) is +X");
	}

	@Test
	void releasingKeysDeceleratesWithGroundFriction() {
		TestWorld w = new TestWorld().floor(63, 60);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		run(p, F, 30);
		double v0 = p.velocity().z();
		p.tick(PlayerPhysics.Input.NONE);
		assertEquals(v0 * (0.6F * 0.91F), p.velocity().z(), 1e-15, "×(0.6F·0.91F) per tick on the ground (float product)");
		run(p, PlayerPhysics.Input.NONE, 20);
		assertEquals(0.0, p.velocity().z(), "zeroed below 9e-6");
	}

	@Test
	void iceIsSlipperyAndAcceleratesSlower() {
		int ice = TestWorld.id("minecraft:ice", "");
		TestWorld w = new TestWorld().fill(-5, 63, -5, 5, 63, 60, ice);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		PlayerPhysics.TickResult first = p.tick(F);
		float f = 0.98F;
		assertEquals(0.98 * (0.1F * (0.21600002F / (f * f * f))), first.z() - 0.5, 1e-9);
		run(p, F, 60);
		p.tick(PlayerPhysics.Input.NONE);
		double v = p.velocity().z();
		p.tick(PlayerPhysics.Input.NONE);
		assertEquals(v * (0.98F * 0.91F), p.velocity().z(), 1e-12);
	}

	@Test
	void jumpHasVanillaApexAndDuration() {
		TestWorld w = new TestWorld().floor(63, 10);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		PlayerPhysics.TickResult jump = p.tick(J);
		assertTrue(jump.jumped());
		assertEquals(64 + 0.41999998688697815, jump.y(), 1e-12, "vy = 0.42F");
		List<PlayerPhysics.TickResult> air = run(p, PlayerPhysics.Input.NONE, 12);
		assertEquals(65.2522, air.get(4).y(), 1e-4, "apex 6 ticks after the jump");
		assertTrue(air.get(5).y() < air.get(4).y());
		assertTrue(air.get(10).onGround(), "lands in the 12th tick");
		assertFalse(air.get(9).onGround());
		assertEquals(64.0, air.get(10).y());
	}

	@Test
	void holdingJumpRepeatsOnlyOnTheGroundAfterDelay() {
		TestWorld w = new TestWorld().floor(63, 10);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		List<PlayerPhysics.TickResult> t = run(p, J, 30);
		long jumps = t.stream().filter(PlayerPhysics.TickResult::jumped).count();
		assertEquals(3, jumps, "jump at t1, after landing (t12) delay ≥ 10 is over → t13, t25");
		assertTrue(t.get(0).jumped());
		assertFalse(t.get(1).jumped());
	}

	@Test
	void fallingFollowsGravityAndDrag() {
		TestWorld w = new TestWorld().floor(39, 10);
		PlayerPhysics p = w.player(0.5, 64, 0.5, 0f);
		List<PlayerPhysics.TickResult> t = run(p, PlayerPhysics.Input.NONE, 40);
		double v = 0;
		double y = 64;
		for (int i = 0; i < 6; i++) {
			y += v;
			v = (v - 0.08) * 0.98F;
			assertEquals(y, t.get(i).y(), 1e-9, "tick " + i);
		}
		PlayerPhysics.TickResult landed = t.stream().filter(PlayerPhysics.TickResult::onGround).findFirst().orElseThrow();
		assertEquals(40.0, landed.y());
		assertTrue(p.fallDistance() == 0.0);
		PlayerPhysics q = w.player(0.5, 41, 0.5, 0f);
		List<PlayerPhysics.TickResult> one = run(q, PlayerPhysics.Input.NONE, 8);
		assertTrue(one.get(5).onGround() && !one.get(4).onGround(), "1-block fall lands in tick 6");
	}

	@Test
	void stepsUpSlabAndStairsButNotFullBlock() {
		TestWorld w = new TestWorld().floor(63, 30).set(0, 64, 3, SLAB);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		List<PlayerPhysics.TickResult> t = run(p, F, 20);
		assertTrue(t.stream().anyMatch(PlayerPhysics.TickResult::stepped));
		assertEquals(64.5, t.stream().mapToDouble(PlayerPhysics.TickResult::y).max().orElse(0), 1e-12, "feet exactly on the slab");
		int stairs = TestWorld.id("minecraft:oak_stairs", "facing=south,half=bottom,shape=straight,waterlogged=false");
		TestWorld s = new TestWorld().floor(63, 30).set(0, 64, 3, stairs).set(0, 64, 4, TestWorld.STONE).set(0, 65, 4, stairs).fill(0, 64, 5, 0, 65, 9,
			TestWorld.STONE);
		PlayerPhysics q = settled(s, 0.5, 64, 0.5, 0f);
		run(q, F, 40);
		assertEquals(66.0, q.y(), 1e-12, "two stair steps up to the platform");
		TestWorld b = new TestWorld().floor(63, 30).set(0, 64, 3, TestWorld.STONE);
		PlayerPhysics r = settled(b, 0.5, 64, 0.5, 0f);
		List<PlayerPhysics.TickResult> blocked = run(r, F, 30);
		assertEquals(64.0, r.y());
		assertEquals(3 - 0.30000001192092896, r.z(), 1e-12, "stops flush, never inside the block");
		assertTrue(blocked.getLast().horizontalCollision());
		assertEquals(0.0, Math.abs(r.velocity().z()), "collision zeroes the velocity component (Vanilla yields -0.0)");
	}

	@Test
	void fenceAndWallCannotBeSteppedOrJumped() {
		int fence = TestWorld.id("minecraft:oak_fence", "east=false,north=false,south=false,waterlogged=false,west=false");
		TestWorld w = new TestWorld().floor(63, 30).set(0, 64, 3, fence);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		run(p, FJ, 60);
		assertEquals(3 + 0.375 - 0.30000001192092896, p.z(), 1e-12, "fence post at 6/16 blocks even with jumping");
		assertTrue(p.y() < 64.0 + 1.3);
		int trapdoor = TestWorld.id("minecraft:oak_trapdoor", "facing=north,half=bottom,open=false,powered=false,waterlogged=false");
		TestWorld t = new TestWorld().floor(63, 30).set(0, 64, 3, trapdoor);
		PlayerPhysics q = settled(t, 0.5, 64, 0.5, 0f);
		run(q, F, 20);
		assertTrue(q.z() > 4.0, "closed trapdoor (3/16) is stepped over");
	}

	@Test
	void ceilingStopsTheJumpAndZeroesVerticalSpeed() {
		TestWorld w = new TestWorld().floor(63, 10).set(0, 66, 0, TestWorld.STONE);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		PlayerPhysics.TickResult jump = p.tick(J);
		assertEquals(66.0 - (double) PlayerPhysics.HEIGHT, jump.y(), 1e-12);
		assertTrue(jump.verticalCollision());
		assertFalse(jump.onGround());
		assertTrue(p.velocity().y() < 0, "after hitting the ceiling only gravity remains");
	}

	@Test
	void walkingAcrossChunkBordersAndNegativeCoordinatesIsSeamless() {
		TestWorld w = new TestWorld().floor(63, 60);
		PlayerPhysics neg = settled(w, -16.5, 64, -20.5, 0f);
		PlayerPhysics pos = settled(w, 16.5, 64, 11.5, 0f);
		List<PlayerPhysics.TickResult> a = run(neg, F, 40);
		List<PlayerPhysics.TickResult> b = run(pos, F, 40);
		for (int i = 0; i < 40; i++) {
			assertEquals(b.get(i).z() - 11.5, a.get(i).z() + 20.5, 1e-9, "tick " + i);
			assertTrue(a.get(i).onGround());
		}
		assertTrue(a.getLast().z() > -16.0, "crossed z = -16 (chunk border)");
	}

	@Test
	void narrowPassageFitsAndWallSlideKeepsDistance() {
		TestWorld w = new TestWorld().floor(63, 30).fill(-2, 64, 2, -2, 65, 20, TestWorld.STONE).fill(0, 64, 2, 0, 65, 20, TestWorld.STONE);
		PlayerPhysics p = settled(w, -0.5, 64, 0.5, 0f);
		run(p, F, 40);
		assertTrue(p.z() > 8, "0.6 wide hitbox passes a 1 wide corridor");
		assertEquals(-0.5, p.x(), 1e-12);
		PlayerPhysics q = settled(w, -0.5, 64, 4.5, 0f);
		List<PlayerPhysics.TickResult> t = run(q, new PlayerPhysics.Input(true, false, false, true, false), 30);
		assertEquals(-1 + 0.30000001192092896, q.x(), 1e-12, "slides along the wall at x = -2..-1");
		assertTrue(t.getLast().horizontalCollision());
		assertTrue(q.z() > 8);
	}

	@Test
	void unloadedAreaStopsMovementAndIsReported() {
		TestWorld w = new TestWorld(5).floor(63, 5);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		List<PlayerPhysics.TickResult> t = run(p, F, 60);
		assertTrue(t.stream().anyMatch(PlayerPhysics.TickResult::touchedUnloaded));
		assertTrue(p.z() <= 6 - 0.3 + 1e-9, "never enters the unloaded chunk");
		assertEquals(64.0, p.y());
	}

	@Test
	void unsupportedEnvironmentIsReported() {
		int water = TestWorld.id("minecraft:end_portal", ""); // M11: Fluide und Sonderblöcke sind simuliert, das Endportal nicht
		TestWorld w = new TestWorld().floor(63, 10).set(0, 64, 0, water);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		assertNotNull(p.tick(PlayerPhysics.Input.NONE).unsupported());
		TestWorld dry = new TestWorld().floor(63, 10);
		assertNull(settled(dry, 0.5, 64, 0.5, 0f).tick(PlayerPhysics.Input.NONE).unsupported());
	}

	@Test
	void identicalInputsGiveIdenticalTrajectories() {
		TestWorld w = new TestWorld().floor(63, 30).set(0, 64, 4, SLAB).set(1, 64, 7, TestWorld.STONE);
		List<PlayerPhysics.TickResult> a = new ArrayList<>();
		List<PlayerPhysics.TickResult> b = new ArrayList<>();
		for (List<PlayerPhysics.TickResult> out : List.of(a, b)) {
			PlayerPhysics p = settled(w, 0.5, 64, 0.5, 12.5f);
			out.addAll(run(p, FJ, 25));
			out.addAll(run(p, FL, 25));
		}
		for (int i = 0; i < a.size(); i++) {
			assertEquals(a.get(i).x(), b.get(i).x());
			assertEquals(a.get(i).y(), b.get(i).y());
			assertEquals(a.get(i).z(), b.get(i).z());
		}
	}

	@Test
	void invalidValuesAreRejected() {
		TestWorld w = new TestWorld().floor(63, 10);
		PlayerPhysics p = w.player(0.5, 64, 0.5, 0f);
		assertThrows(IllegalArgumentException.class, () -> p.setPosition(Double.NaN, 64, 0, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> p.setPosition(0, 64, 0, 0, Double.POSITIVE_INFINITY, 0));
		assertThrows(IllegalArgumentException.class, () -> p.setYRot(Float.NaN));
		assertThrows(IllegalArgumentException.class, () -> Shape.box(0, 0, 0, Double.NaN, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new Shape(new Aabb[]{new Aabb(0, 0, 0, 1, 1, 1)}, new double[]{1, 0}));
	}
}
