package dev.mcresearch.protocolclient.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M11 Phase C (docs/42 §1–§4): Sprinten, Schleichen, Posen, Kantenschutz. */
class SprintSneakTest {
	private static PlayerPhysics settled(final TestWorld w, final double x, final double y, final double z, final float yaw) {
		PlayerPhysics p = w.player(x, y, z, yaw);
		for (int i = 0; i < 3; i++) {
			p.tick(PlayerPhysics.Input.NONE);
		}
		assertTrue(p.onGround());
		return p;
	}

	private static List<PlayerPhysics.TickResult> run(final PlayerPhysics p, final String keys, final int n) {
		List<PlayerPhysics.TickResult> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(p.tick(PlayerPhysics.Input.parse(keys)));
		}
		return out;
	}

	@Test
	void sprintKeyStartsSprintAndRaisesEquilibriumSpeed() {
		TestWorld w = new TestWorld().floor(63, 80);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		List<PlayerPhysics.TickResult> t = run(p, "fp", 60);
		assertTrue(t.getFirst().sprinting(), "sprint key with forward impulse starts sprinting in the same tick");
		double step = t.get(59).z() - t.get(58).z();
		// v∞ = 0,98 · 0,13 / (1 − 0,546): Sprint-Modifikator ×1,3 (ADD_MULTIPLIED_TOTAL)
		assertEquals(0.98 * 0.1F * (1.0 + 0.3F) / (1.0 - 0.6F * 0.91F), step, 1e-4, "≈ 5.61 m/s");
		assertEquals((float) (0.1F * (1.0 + (double) 0.3F)), p.movementSpeed(), "speed attribute with sprint modifier");
		run(p, "f", 5);
		assertTrue(p.sprinting(), "sprint continues while forward is held");
		run(p, "-", 1);
		assertFalse(p.sprinting(), "no forward impulse stops sprinting");
		assertEquals(0.1F, p.movementSpeed(), "modifier removed");
	}

	@Test
	void doubleTapWithinSprintWindowStartsSprint() {
		TestWorld w = new TestWorld().floor(63, 80);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		run(p, "f", 2);
		run(p, "-", 2);
		assertFalse(p.sprinting());
		run(p, "f", 1);
		assertTrue(p.sprinting(), "second press within 7 ticks");
		PlayerPhysics q = settled(w, 5.5, 64, 0.5, 0f);
		run(q, "f", 2);
		run(q, "-", 8);
		run(q, "f", 3);
		assertFalse(q.sprinting(), "second press after the window");
	}

	@Test
	void sprintJumpAddsForwardImpulse() {
		TestWorld w = new TestWorld().floor(63, 80);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		run(p, "fp", 20);
		double vzBefore = p.velocity().z();
		PlayerPhysics.TickResult r = p.tick(PlayerPhysics.Input.parse("fpj"));
		assertTrue(r.jumped());
		assertTrue(r.vz() > vzBefore + 0.1, "+0.2 in view direction (before drag)");
	}

	@Test
	void noSprintWhenHungry() {
		TestWorld w = new TestWorld().floor(63, 80);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		p.setFood(6, false);
		run(p, "fp", 5);
		assertFalse(p.sprinting(), "food 6 is not enough");
		p.setFood(6, true);
		run(p, "fp", 1);
		assertTrue(p.sprinting(), "mayfly (creative) ignores food");
	}

	@Test
	void sneakingSlowsDownAndChangesPoseOneTickLater() {
		TestWorld w = new TestWorld().floor(63, 80);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		PlayerPhysics.TickResult first = p.tick(PlayerPhysics.Input.parse("fs"));
		assertEquals(PlayerPose.CROUCHING, first.pose(), "pose follows Shift at the end of the tick");
		assertFalse(p.crouching(), "isCrouching for movement uses the Shift state of the previous tick");
		assertEquals(0.98F * 0.1F, first.z() - 0.5, 1e-7, "first tick still at walking speed");
		List<PlayerPhysics.TickResult> t = run(p, "fs", 60);
		double step = t.get(59).z() - t.get(58).z();
		assertEquals(0.98F * 0.3F * 0.1F / (1.0 - 0.6F * 0.91F), step, 1e-4, "sneaking_speed 0.3");
		assertEquals(1.27F, p.eyeHeight());
		assertEquals(1.5F, p.boundingBox().maxY() - p.boundingBox().minY(), 1e-6);
	}

	@Test
	void sneakingStopsAtEdge() {
		TestWorld w = new TestWorld().floor(63, 80).fill(-2, 64, -2, 2, 64, 2, TestWorld.STONE);
		PlayerPhysics p = settled(w, 0.5, 65, 0.5, 0f);
		run(p, "s", 3);
		run(p, "fs", 80);
		assertEquals(65.0, p.y(), 1e-9, "did not fall off the 1-block edge");
		assertTrue(p.z() > 2.9 && p.z() < 3.3, "stopped at the edge (z = " + p.z() + ")");
		run(p, "f", 30);
		assertTrue(p.y() < 65.0, "without Shift the player walks off");
	}

	@Test
	void lowCeilingKeepsCrouchingWithoutShift() {
		int topSlab = TestWorld.id("minecraft:oak_slab", "type=top,waterlogged=false");
		TestWorld w = new TestWorld().floor(63, 80).fill(-2, 65, 3, 2, 65, 8, topSlab);
		PlayerPhysics p = settled(w, 0.5, 64, 0.5, 0f);
		run(p, "s", 3);
		run(p, "fs", 80);
		assertTrue(p.z() > 4.0, "walked under the 1.5-block ceiling crouched");
		List<PlayerPhysics.TickResult> t = run(p, "f", 6);
		assertEquals(PlayerPose.CROUCHING, t.getLast().pose(), "cannot stand up under the ceiling");
		assertTrue(p.crouching(), "isCrouching because the standing pose does not fit");
		run(p, "f", 60);
		assertEquals(PlayerPose.STANDING, p.pose(), "stands up after leaving the ceiling");
	}

	@Test
	void fullCollisionStopsSprintMinorDoesNot() {
		TestWorld w = new TestWorld().floor(63, 80).fill(2, 64, -10, 2, 65, 40, TestWorld.STONE);
		PlayerPhysics p = settled(w, 1.2, 64, 0.5, -3f);
		List<PlayerPhysics.TickResult> t = run(p, "fp", 40);
		assertTrue(t.stream().anyMatch(PlayerPhysics.TickResult::horizontalCollision), "touches the wall");
		assertTrue(p.sprinting(), "angle 3° < 8°: minor collision keeps sprinting");
		PlayerPhysics q = settled(w, 1.2, 64, 0.5, -30f);
		run(q, "fp", 40);
		assertFalse(q.sprinting(), "30° into the wall stops sprinting");
	}
}
