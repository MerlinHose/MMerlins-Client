package dev.mcresearch.protocolclient.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.physics.ShapeRegistry;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M14-Fehleranalyse „Floß lässt sich nicht per Rechtsklick betreten“ (docs/58 §2.1): Das Floß ist auswählbar; im ersten Grafiklauf
 * verfehlte der Blickstrahl die Hitbox, weil {@code look-at} noch während des Absinkens im Wasser berechnet wurde. Der Strahl lief unter
 * dem Floß hindurch und traf den Beckenboden in 3,306 m (Report: {@code ETARGET boat BLOCK dist=3.306}).
 */
class VehiclePickTest {
	private static final int BAMBOO_RAFT = 9, OAK_BOAT = 90, MINECART = 86;
	private static final int AIR = 0, STONE = 1;

	private static EntityManager.View vehicle(final EntityManager m, final int id, final int type, final double x, final double y, final double z) {
		m.handle(new Clientbound.AddEntity(id, new UUID(9, id), type, x, y, z, 0, 0, 0, 0, 0, 0, 0));
		return m.view(id, 1.0F, 0);
	}

	/** Becken wie in m14-gfx.txt: Steinboden y=98 (Oberkante 99), darüber Wasser (für den Auswahlstrahl ohne Umriss → hier Luft). */
	private static int pool(final int x, final int y, final int z) {
		return y <= 98 ? STONE : AIR;
	}

	@Test
	void raftsBoatsAndMinecartsArePickableAndUseTheirOwnHitbox() {
		EntityManager m = new EntityManager();
		EntityManager.View raft = vehicle(m, 1, BAMBOO_RAFT, 211.5, 99.52, -20.5);
		assertTrue(raft.pickable(), "Floß ist auswählbar (VehicleEntity#isPickable)");
		assertEquals(1.375, raft.width(), 1e-6);
		assertEquals(0.5625, raft.height(), 1e-6);
		assertTrue(vehicle(m, 2, OAK_BOAT, 0, 64, 0).pickable());
		assertTrue(vehicle(m, 3, MINECART, 0, 64, 0).pickable());
	}

	@Test
	void raftIsTargetedFromAStableStandingPosition() {
		EntityManager m = new EntityManager();
		EntityManager.View raft = vehicle(m, 1, BAMBOO_RAFT, 211.5, 99.52, -20.5);
		// Spieler steht auf einem Block am Beckenrand (Füße y=100, Auge 101.62), Blick auf die Floßmitte
		double ex = 211.5, ey = 101.62, ez = -22.5;
		double dy = 99.9 - ey, dz = -20.5 - ez;
		float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.abs(dz)));
		EntityPick.Target t = EntityPick.pick(VehiclePickTest::pool, ShapeRegistry.get(), ex, ey, ez, 0.0F, pitch, 4.5, 3.0, List.of(raft), -1);
		assertEquals(EntityPick.Kind.ENTITY, t.kind());
		assertEquals(1, t.entity().id());
	}

	@Test
	void firstGraphicsRunAimedBelowTheRaftAndHitThePoolFloor() {
		EntityManager m = new EntityManager();
		EntityManager.View raft = vehicle(m, 1, BAMBOO_RAFT, 211.5, 99.52, -20.5);
		// Zustand beim „use“ im Lauf m6-m14-gfx1: Füße 99.0 (Beckenboden), Blickneigung aus einer höheren Augenposition berechnet
		float pitch = (float) Math.toDegrees(Math.atan(0.5625));
		EntityPick.Target t = EntityPick.pick(VehiclePickTest::pool, ShapeRegistry.get(), 211.5, 100.62, -23.5, 0.0F, pitch, 4.5, 3.0, List.of(raft), -1);
		assertEquals(EntityPick.Kind.BLOCK, t.kind(), "Strahl läuft unter der Floß-Hitbox hindurch");
		assertEquals(3.306, t.distance(), 0.01);
	}
}
