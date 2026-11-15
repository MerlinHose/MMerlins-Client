package dev.mcresearch.protocolclient.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M11 Phase I (docs/45 §5): Grenzwerte, Zustandsübergänge, negative Koordinaten und Chunkgrenzen, wechselnde Fluidhöhen,
 * mehrere gleichzeitige Blockeffekte, ungeladene Chunks, ungewöhnliche gültige Zustände, Teleport. Die Bahnen selbst sind
 * gegen Vanilla geprüft (m11-run.sh); hier stehen die Regeln einzeln.
 */
class SpecialMovementTest {
	private static final int WATER = TestWorld.id("minecraft:water", "level=0");
	private static final int LAVA = TestWorld.id("minecraft:lava", "level=0");

	private static PlayerPhysics at(final TestWorld w, final double x, final double y, final double z) {
		return w.player(x, y, z, 0f);
	}

	private static List<PlayerPhysics.TickResult> run(final PlayerPhysics p, final String keys, final int n) {
		List<PlayerPhysics.TickResult> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(p.tick(PlayerPhysics.Input.parse(keys)));
		}
		return out;
	}

	@Test
	void fluidHeightsFollowAmountAndSameFluidAbove() {
		TestWorld w = new TestWorld().floor(60, 10);
		FluidModel fm = new FluidModel(w, BlockMovementFacts.get());
		for (int level = 0; level <= 7; level++) {
			w.set(level, 61, 0, TestWorld.id("minecraft:water", "level=" + level));
			float expected = (level == 0 ? 8 : 8 - level) / 9.0F;
			assertEquals(expected, fm.height(level, 61, 0), "level " + level);
		}
		w.set(0, 62, 0, WATER);
		assertEquals(1.0F, fm.height(0, 61, 0), "same fluid above → full height");
		assertEquals(8 / 9.0F, fm.height(0, 62, 0));
		w.set(0, 63, 0, TestWorld.STONE);
		assertEquals(1.0F, fm.heightForCamera(0, 62, 0), "source under a sturdy bottom face: camera height 1");
		BlockMovementFacts.Fluid falling = BlockMovementFacts.get().fluid(TestWorld.id("minecraft:water", "level=8"));
		assertTrue(falling.falling() && falling.amount() == 8 && !falling.source(), "level ≥ 8 is falling water");
	}

	@Test
	void waterloggedAndUnusualStatesCarryTheirOwnFluid() {
		BlockMovementFacts f = BlockMovementFacts.get();
		int dry = TestWorld.id("minecraft:oak_slab", "type=bottom,waterlogged=false");
		int wet = TestWorld.id("minecraft:oak_slab", "type=bottom,waterlogged=true");
		assertTrue(f.fluid(dry).isEmpty());
		assertEquals(BlockMovementFacts.FLUID_WATER, f.fluid(wet).type());
		assertTrue(f.fluid(wet).source());
		assertTrue(f.fluid(TestWorld.id("minecraft:kelp", "age=0")).source(), "kelp is a water source");
		assertTrue(f.fluid(TestWorld.id("minecraft:bubble_column", "drag=true")).source());
		assertTrue(f.climbable(TestWorld.id("minecraft:ladder", "facing=north,waterlogged=true")), "waterlogged ladder still climbable");
		assertFalse(f.climbable(TestWorld.id("minecraft:stone", "")));
		// offene Falltür über gleich ausgerichteter Leiter zählt als Kletterblock (LivingEntity#trapdoorUsableAsLadder)
		TestWorld w = new TestWorld().floor(60, 10)
			.set(0, 61, 0, TestWorld.id("minecraft:ladder", "facing=north,waterlogged=false"))
			.set(0, 62, 0, TestWorld.id("minecraft:oak_trapdoor", "facing=north,half=bottom,open=true,powered=false,waterlogged=false"));
		assertTrue(at(w, 0.5, 62.0, 0.5).onClimbable());
		w.set(0, 62, 0, TestWorld.id("minecraft:oak_trapdoor", "facing=south,half=bottom,open=true,powered=false,waterlogged=false"));
		assertFalse(at(w, 0.5, 62.0, 0.5).onClimbable(), "different facing");
	}

	@Test
	void enteringAndLeavingWaterChangesTheMovementRules() {
		TestWorld w = new TestWorld().floor(60, 20).fill(-3, 61, 3, 3, 62, 10, WATER);
		PlayerPhysics p = at(w, 0.5, 61, 0.5);
		run(p, "-", 3);
		assertFalse(p.inWater());
		List<PlayerPhysics.TickResult> t = run(p, "f", 40);
		assertTrue(p.inWater(), "walked into the pool");
		double vMax = t.stream().skip(30).mapToDouble(r -> Math.abs(r.vz())).max().orElse(0);
		assertTrue(vMax < 0.2, "water drag 0.8 limits the speed (" + vMax + ")");
		assertEquals(0.0, p.fallDistance(), "no fall distance in water");
	}

	@Test
	void buoyancyAndDivingAreSymmetricImpulses() {
		TestWorld w = new TestWorld().floor(50, 10).fill(-3, 51, -3, 3, 60, 3, WATER);
		PlayerPhysics up = at(w, 0.5, 55, 0.5);
		PlayerPhysics down = at(w, 0.5, 55, 0.5);
		PlayerPhysics idle = at(w, 0.5, 55, 0.5);
		run(up, "j", 5);
		run(down, "s", 5);
		run(idle, "-", 5);
		assertTrue(up.y() > idle.y(), "jump key: +0.04F per tick");
		assertTrue(down.y() < idle.y(), "Shift: −0.04F per tick");
	}

	@Test
	void swimmingNeedsSprintUnderWaterAndUsesSwimPose() {
		TestWorld w = new TestWorld().floor(50, 10).fill(-3, 51, -3, 3, 60, 10, WATER);
		PlayerPhysics p = at(w, 0.5, 53, 0.5);
		run(p, "-", 3);
		assertTrue(p.underWater());
		run(p, "fp", 3);
		assertTrue(p.swimming(), "sprinting under water starts swimming");
		assertEquals(PlayerPose.SWIMMING, p.pose());
		assertEquals(0.4F, p.eyeHeight());
		run(p, "-", 2);
		assertFalse(p.sprinting(), "no forward impulse, not on ground: swim sprint ends");
		run(p, "-", 2);
		assertFalse(p.swimming());
	}

	@Test
	void lavaIsSlowerAndSinksSlowly() {
		TestWorld w = new TestWorld().floor(50, 10).fill(-3, 51, -3, 3, 55, 3, LAVA);
		PlayerPhysics p = at(w, 0.5, 54, 0.5);
		run(p, "-", 2);
		assertTrue(p.inLava());
		List<PlayerPhysics.TickResult> t = run(p, "-", 30);
		double vy = t.getLast().vy();
		assertTrue(vy < 0 && vy > -0.06, "lava: drag 0.5 and −g/4 → slow sinking (" + vy + ")");
	}

	@Test
	void ladderLimitsFallAndShiftHoldsHeight() {
		TestWorld w = new TestWorld().floor(50, 10).fill(0, 51, 1, 0, 70, 1, TestWorld.STONE)
			.fill(0, 51, 0, 0, 70, 0, TestWorld.id("minecraft:ladder", "facing=north,waterlogged=false"));
		PlayerPhysics p = at(w, 0.5, 65, 0.5);
		List<PlayerPhysics.TickResult> fall = run(p, "-", 20);
		assertTrue(fall.stream().allMatch(r -> r.vy() >= -0.15F * 0.98F - 0.08 - 1e-9), "climbable: fall speed clamped to 0.15F");
		PlayerPhysics q = at(w, 0.5, 65, 0.5);
		run(q, "s", 20);
		assertEquals(65.0, q.y(), 0.2, "Shift on a ladder keeps the height");
		PlayerPhysics r = at(w, 0.5, 52, 0.5);
		run(r, "f", 20);
		assertTrue(r.y() > 53, "walking into the ladder climbs with 0.2 per tick");
	}

	@Test
	void cobwebAndBerryBushStuckFactorsAndNoAccumulation() {
		int web = TestWorld.id("minecraft:cobweb", "");
		TestWorld w = new TestWorld().floor(60, 20).fill(0, 61, 3, 0, 62, 30, web);
		PlayerPhysics p = at(w, 0.5, 61, 0.5);
		run(p, "-", 3);
		List<PlayerPhysics.TickResult> t = run(p, "f", 60);
		double step = t.getLast().z() - t.get(t.size() - 2).z();
		assertTrue(step > 0 && step < 0.03, "cobweb: movement × 0.25 each tick (" + step + ")");
		assertEquals(0.0, t.getLast().vz(), "velocity is reset after the stuck move");
		// zwei Effekte in einem Tick: der zuletzt besuchte Faktor gilt, Fallstrecke 0
		int bush = TestWorld.id("minecraft:sweet_berry_bush", "age=0");
		TestWorld both = new TestWorld().floor(60, 20).set(0, 61, 3, bush).set(0, 62, 3, web);
		PlayerPhysics b = at(both, 0.5, 61, 3.5);
		run(b, "f", 5);
		// erster Tick ungebremst (Faktor wirkt ab der nächsten Bewegung), danach × 0,25 bzw. × 0,8F
		assertTrue(b.z() < 3.75, "slowed by web and bush together (z = " + b.z() + ")");
	}

	@Test
	void slimeBouncesUnlessSneaking() {
		int slime = TestWorld.id("minecraft:slime_block", "");
		TestWorld w = new TestWorld().floor(59, 10).fill(-3, 60, -3, 3, 60, 3, slime);
		PlayerPhysics p = at(w, 0.5, 66, 0.5);
		boolean bounced = false;
		for (PlayerPhysics.TickResult r : run(p, "-", 30)) {
			bounced |= r.vy() > 0.2;
		}
		assertTrue(bounced, "restitution 1.0 on slime");
		PlayerPhysics q = at(w, 0.5, 66, 0.5);
		boolean qBounced = false;
		for (PlayerPhysics.TickResult r : run(q, "s", 30)) {
			qBounced |= r.vy() > 0.0;
		}
		assertFalse(qBounced, "Shift suppresses the bounce");
	}

	@Test
	void bubbleColumnPushesUpToTheCapAndHoneySuppressesBounce() {
		int up = TestWorld.id("minecraft:bubble_column", "drag=false");
		TestWorld w = new TestWorld().floor(50, 10).fill(0, 51, 0, 0, 60, 0, up);
		PlayerPhysics p = at(w, 0.5, 52, 0.5);
		double maxVy = run(p, "-", 30).stream().mapToDouble(PlayerPhysics.TickResult::vy).max().orElse(0);
		assertTrue(maxVy > 0.3 && maxVy <= 1.8, "bubble column lifts, at most 1.8 above the surface (" + maxVy + ")");
		assertTrue(BlockMovementFacts.get().suppressesBounce(TestWorld.id("minecraft:honey_block", "")));
		assertEquals(1.0F, BlockMovementFacts.get().bounceRestitution(TestWorld.id("minecraft:slime_block", "")));
	}

	@Test
	void negativeCoordinatesAndChunkBordersInWater() {
		TestWorld w = new TestWorld().floor(50, 40).fill(-20, 51, -20, -12, 54, -12, WATER);
		PlayerPhysics p = at(w, -16.0, 53, -16.0); // Hitbox liegt über vier Chunks (x, z = −16)
		run(p, "-", 2);
		assertTrue(p.inWater(), "fluid cells found in all four neighbouring chunks");
		assertTrue(p.waterHeight() > 1.0);
	}

	@Test
	void unloadedChunkMeansNoFluidLikeVanilla() {
		// geladen bis |x|, |z| ≤ 31: Chunks −1 … 1 vollständig, Chunk 2 (x ≥ 32) nicht
		TestWorld w = new TestWorld(31).floor(50, 31).fill(-14, 51, -14, 31, 54, 14, WATER);
		PlayerPhysics inside = at(w, 0.5, 53, 0.5);
		run(inside, "-", 2);
		assertTrue(inside.inWater());
		PlayerPhysics border = at(w, 31.5, 53, 0.5); // Nachbarbereich x0−1 … x1+1 = 30 … 32 reicht in den ungeladenen Chunk 2
		run(border, "-", 2);
		assertFalse(border.inWater(), "EntityFluidInteraction: a missing chunk in range → no fluid");
	}

	@Test
	void teleportResetsFallDistanceAndFluidStateFollowsNextTick() {
		TestWorld w = new TestWorld().floor(50, 10).fill(-3, 51, -3, 3, 54, 3, WATER);
		PlayerPhysics p = at(w, 5.5, 80, 5.5);
		run(p, "-", 10);
		assertTrue(p.fallDistance() > 1.0);
		p.setPosition(0.5, 52, 0.5, 0, 0, 0);
		assertEquals(0.0, p.fallDistance(), "teleport (player_position) resets the fall distance");
		assertFalse(p.inWater(), "fluid state is from before the teleport until the next tick");
		p.tick(PlayerPhysics.Input.NONE);
		assertTrue(p.inWater());
	}

	@Test
	void numericLimitsStayFiniteForExtremeSpeeds() {
		TestWorld w = new TestWorld().floor(50, 40).fill(-30, 51, -30, 30, 51, 30, TestWorld.id("minecraft:blue_ice", ""));
		PlayerPhysics p = at(w, 0.5, 52, 0.5);
		p.setVelocity(3.9, 0, 3.9);
		for (PlayerPhysics.TickResult r : run(p, "fp", 200)) {
			assertTrue(Double.isFinite(r.x()) && Double.isFinite(r.vx()), "finite");
		}
		MovementAttributes a = new MovementAttributes();
		a.setFromServer(MovementAttributes.MOVEMENT_SPEED, 5000.0, List.of());
		assertEquals(1024.0, a.value(MovementAttributes.MOVEMENT_SPEED), "attribute range clamps (RangedAttribute#sanitizeValue)");
		a.setFromServer(MovementAttributes.MOVEMENT_SPEED, Double.NaN, List.of());
		assertEquals(0.0, a.value(MovementAttributes.MOVEMENT_SPEED), "NaN → minimum");
	}
}
