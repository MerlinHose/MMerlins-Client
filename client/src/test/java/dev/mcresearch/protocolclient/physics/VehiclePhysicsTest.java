package dev.mcresearch.protocolclient.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.world.BlockRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M14 Phase G/I/J (docs/57): Boots- und Pferdephysik, Entity-Kollision – Grenzwerte und Abläufe nach den Vanilla-Regeln. */
class VehiclePhysicsTest {
	private static final int WATER = TestWorld.id("minecraft:water", "level=0");
	private static final int PACKED_ICE = TestWorld.id("minecraft:packed_ice", "");

	private static BoatPhysics boat(final TestWorld w) {
		return new BoatPhysics(new CollisionWorld(w, ShapeRegistry.get()), BlockRegistry.get(), 1.375F, 0.5625F);
	}

	@Test
	void boatFloatsAndReachesWaterTerminalSpeed() {
		TestWorld w = new TestWorld().floor(58, 20).fill(-20, 59, -20, 20, 62, 20, WATER); // Wasser 59–62, Oberfläche 63
		BoatPhysics b = boat(w);
		b.setPosition(0.5, 62.6, 0.5, 0);
		for (int t = 0; t < 40; t++) {
			b.tick(); // ohne Eingabe: auf der Oberfläche einschwingen
		}
		assertEquals(BoatPhysics.Status.IN_WATER, b.status());
		assertTrue(b.y() > 62.4 && b.y() < 63.1, "floating height " + b.y());
		b.setInput(false, false, true, false);
		BoatPhysics.TickResult r = null;
		for (int t = 0; t < 200; t++) {
			r = b.tick();
		}
		// v' = 0,9·v + 0,04 → Grenzwert 0,4 Blöcke/Tick (Gier 0 → +z)
		assertEquals(0.4, r.vz(), 0.01);
		assertTrue(r.paddleLeft() && r.paddleRight());
	}

	@Test
	void boatOnPackedIceIsFastAndTurnsWithAccumulatedRotation() {
		TestWorld w = new TestWorld().fill(-3, 63, -5, 3, 63, 800, PACKED_ICE); // Eisbahn in +z
		BoatPhysics b = boat(w);
		b.setPosition(0.5, 64.0, 0.5, 0);
		b.tick();
		b.setInput(false, false, true, false);
		BoatPhysics.TickResult r = null;
		for (int t = 0; t < 300; t++) {
			r = b.tick();
		}
		assertEquals(BoatPhysics.Status.ON_LAND, r.status());
		// Paketeis 0,98: v' = 0,98·v + 0,04 → 2 Blöcke/Tick
		assertEquals(2.0, Math.hypot(r.vx(), r.vz()), 0.05);
		// nur links: Drehgeschwindigkeit sammelt sich (−1°/Tick) und wird mit der Reibung gedämpft
		b.setInput(true, false, false, false);
		float before = b.yRot();
		b.tick();
		b.tick();
		assertTrue(b.yRot() < before, "turning left decreases yaw");
		assertTrue(b.deltaRotation() < -1.0F);
	}

	@Test
	void boatStopsAtWallsAndDoesNotStepUp() {
		TestWorld w = new TestWorld().floor(63, 20).fill(-3, 64, 3, 3, 64, 3, TestWorld.STONE);
		BoatPhysics b = boat(w);
		b.setPosition(0.5, 64.0, 0.5, 0);
		b.setInput(false, false, true, false);
		for (int t = 0; t < 200; t++) {
			b.tick();
		}
		assertTrue(b.boundingBox().maxZ() <= 3.0 + 1.0E-6, "stopped before the 1-block wall: " + b.z());
		assertEquals(64.0, b.y(), 1.0E-6);
	}

	@Test
	void entityBoxesAreSolidForMovement() {
		TestWorld w = new TestWorld().floor(63, 20);
		CollisionWorld cw = new CollisionWorld(w, ShapeRegistry.get());
		Aabb boatBox = new Aabb(-0.1875, 64, 1.5, 1.1875, 64.5625, 2.875);
		cw.setEntityBoxes(() -> List.of(boatBox));
		Aabb player = new Aabb(0.2, 64, 0.2, 0.8, 65.8, 0.8);
		List<Shape> shapes = cw.collisions(player.expandTowards(0, 0, 2), new CollisionContext(64, false, 0)).shapes();
		Vec3d moved = PlayerPhysics.collideWithShapes(new Vec3d(0, 0, 2), player, shapes);
		assertEquals(0.7, moved.z(), 1.0E-6); // vor der Bootskante (1,5 − 0,8) angehalten
		cw.setEntityBoxes(null);
		assertEquals(2.0, PlayerPhysics.collideWithShapes(new Vec3d(0, 0, 2), player, cw.collisions(player.expandTowards(0, 0, 2),
			new CollisionContext(64, false, 0)).shapes()).z(), 1.0E-9);
	}

	@Test
	void horseWalksStepsOneBlockAndChargedJumpsFollowTheScale() {
		TestWorld w = new TestWorld().floor(63, 40).fill(-5, 64, 6, 5, 64, 40, TestWorld.STONE); // 1 Block hohe Plattform ab z=6
		HorsePhysics h = new HorsePhysics(new CollisionWorld(w, ShapeRegistry.get()), BlockRegistry.get(), 1.3964844F, 1.6F);
		h.setPosition(0.5, 64.0, 0.5, 0, true);
		h.setAttributes(0.225, 0.7, 1.0);
		HorsePhysics.TickResult r = null;
		for (int t = 0; t < 60; t++) {
			r = h.tick(0, 0.98F, 0, 0);
		}
		assertTrue(r.y() >= 65.0 - 1.0E-6, "stepped onto the 1-block ledge: y=" + r.y());
		assertTrue(r.z() > 6.0);
		// volle Ladung (≥ 90): Sprungimpuls = Sprungkraft 0,7
		h.playerJump(100);
		r = h.tick(0, 0, 0, 0);
		assertTrue(r.jumped());
		assertEquals(0.7 - 0.08, r.vy() / 0.98, 0.02); // nach Schwerkraft und Luftwiderstand
		// halbe Ladung: Faktor 0,4 + 0,4·45/90 = 0,6
		HorsePhysics h2 = new HorsePhysics(new CollisionWorld(w, ShapeRegistry.get()), BlockRegistry.get(), 1.3964844F, 1.6F);
		h2.setPosition(0.5, 64.0, -10.5, 0, true);
		h2.setAttributes(0.225, 0.7, 1.0);
		h2.playerJump(45);
		HorsePhysics.TickResult j = h2.tick(0, 0, 0, 0);
		assertEquals((0.7 * 0.6 - 0.08) * 0.98, j.vy(), 1.0E-4);
		assertFalse(h2.onGround());
	}

	/**
	 * M14 (Fahrzeugvergleich vehicle3): Ein vollständig untergetauchtes Boot sinkt in Vanilla (UNDER_WATER: Auftrieb 0,01 gegen
	 * Schwerkraft 0,04). Beim Aufsitzen kennt der Vanilla-Client den Status bereits (tick() berechnet ihn auch ohne Steuerung); ohne
	 * {@link BoatPhysics#primeStatus()} galt der Anfangsstatus IN_AIR und das Boot sprang im ersten Tick an die Oberfläche.
	 */
	@Test
	void submergedBoatKeepsSinkingAfterMountInsteadOfSnappingToTheSurface() {
		TestWorld w = new TestWorld().floor(58, 20).fill(-20, 59, -20, 20, 63, 20, WATER); // Wasser 59–63, Oberfläche 64
		BoatPhysics b = boat(w);
		b.setPosition(0.5, 61.0, 0.5, 0);
		b.primeStatus();
		assertEquals(BoatPhysics.Status.UNDER_WATER, b.status());
		BoatPhysics.TickResult r = b.tick();
		assertEquals(BoatPhysics.Status.UNDER_WATER, r.status());
		assertTrue(r.y() < 61.0, "sinks, y=" + r.y());
		// Gegenprobe ohne primeStatus: Übergang „aus der Luft“ setzt das Boot nach oben (altes Fehlverhalten)
		BoatPhysics unprimed = boat(w);
		unprimed.setPosition(0.5, 61.0, 0.5, 0);
		BoatPhysics.TickResult u = unprimed.tick();
		assertTrue(u.y() > 62.0, "unprimed y=" + u.y() + " " + u.status()); // getWaterLevelAbove: maxY+1 = 63 → 63 − 0,5625 + 0,101
	}
}
