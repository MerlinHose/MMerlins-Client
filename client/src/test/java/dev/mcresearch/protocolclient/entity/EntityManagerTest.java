package dev.mcresearch.protocolclient.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import dev.mcresearch.protocolclient.protocol.packet.GameProfile;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M9: Entity-Verwaltung (docs/35 §3). */
class EntityManagerTest {
	private static final int PIG = 101;
	private static final int PLAYER = 159;

	private static Clientbound.AddEntity spawn(final int id, final UUID u, final int type, final double x, final double y, final double z) {
		return new Clientbound.AddEntity(id, u, type, x, y, z, 0, 0, 0, 0, 0, 0, 0);
	}

	@Test
	void typeTableHasAll161TypesWithDimensions() {
		EntityTypes t = EntityTypes.get();
		assertEquals(161, t.size());
		assertEquals("minecraft:pig", t.type(PIG).name());
		assertEquals(0.9F, t.type(PIG).width());
		assertEquals(1.62F, t.type(PLAYER).eyeHeight());
		assertEquals(EntityTypes.UNKNOWN, t.type(5000));
	}

	@Test
	void deltaDecodingFollowsTheFixedPointRule() {
		// Achse ohne Delta bleibt exakt; sonst (round(basis · 4096) + delta) / 4096
		assertEquals(2.123456789, EntityManager.decodeAxis(2.123456789, (short) 0), 0.0);
		assertEquals((Math.round(2.123456789 * 4096) + 410) / 4096.0, EntityManager.decodeAxis(2.123456789, (short) 410), 0.0);
		assertEquals((Math.round(-0.3 * 4096) - 32768) / 4096.0, EntityManager.decodeAxis(-0.3, Short.MIN_VALUE), 0.0);
	}

	@Test
	void spawnMoveStepsRemove() {
		EntityManager m = new EntityManager();
		UUID u = UUID.randomUUID();
		m.handle(spawn(10, u, PIG, 1.5, 100, -3.5));
		m.handle(new Clientbound.MoveEntity(10, true, true, true,
			List.of(new Clientbound.DeltaStep(1, (short) 410, (short) 0, (short) 0), new Clientbound.DeltaStep(2, (short) 410, (short) 0, (short) 0)),
			90, 0, true));
		EntityManager.View v = m.view(10, 1.0F, 0);
		assertEquals((Math.round((Math.round(1.5 * 4096) + 410) / 4096.0 * 4096) + 410) / 4096.0, v.x(), 0.0);
		assertEquals(90.0F, v.yRot());
		assertTrue(v.onGround());
		assertEquals(u, v.uuid());
		assertEquals(10, m.idOf(u));
		m.handle(new Clientbound.RemoveEntities(new int[]{10}));
		assertNull(m.view(10, 1.0F, 0));
		assertNull(m.idOf(u));
		assertEquals(1, m.stats().removed);
	}

	@Test
	void teleportKeepsTheDeltaBaseLikeVanilla() {
		EntityManager m = new EntityManager();
		m.handle(spawn(1, UUID.randomUUID(), PIG, 0, 100, 0));
		m.handle(new Clientbound.TeleportEntity(1, 5, 100, 0, 0, 0, 0, 0, 0, 0, true));
		assertEquals(5.0, m.view(1, 1, 0).x());
		// nächstes Delta bezieht sich auf die alte Basis (0), nicht auf den Teleport-Zielpunkt
		m.handle(new Clientbound.MoveEntity(1, true, false, false, List.of(new Clientbound.DeltaStep(0, (short) 4096, (short) 0, (short) 0)), 0, 0, true));
		assertEquals(1.0, m.view(1, 1, 0).x());
	}

	@Test
	void relativeTeleportAndSyncSnap() {
		EntityManager m = new EntityManager();
		m.handle(spawn(1, UUID.randomUUID(), PIG, 0, 100, 0));
		m.handle(new Clientbound.TeleportEntity(1, 2, 0, 0, 0, 0, 0, 30, 0, 1 | 2 | 4 | 8, false));
		EntityManager.View v = m.view(1, 1, 0);
		assertEquals(2.0, v.x());
		assertEquals(100.0, v.y());
		assertEquals(30.0F, v.yRot());
		m.handle(new Clientbound.EntityPositionSync(1, false, List.of(new Clientbound.PathStep(40, 100, 0, 0)), 0, 0, true));
		assertEquals(40.0, m.view(1, 1, 0).rx(), 0.0); // > 8 Blöcke: Darstellung springt
		assertEquals(2, m.stats().snapsTeleport);
	}

	@Test
	void packetsForUnknownIdsAreCountedAndIgnored() {
		EntityManager m = new EntityManager();
		m.setOwnId(99);
		m.handle(new Clientbound.RotateHead(5, 10));
		m.handle(new Clientbound.RotateHead(99, 10)); // eigene ID: nicht zählen
		assertEquals(1, m.stats().unknownIdPackets);
		m.handle(spawn(99, UUID.randomUUID(), PLAYER, 0, 0, 0)); // eigene Spieler-Entity wird nie geführt
		assertEquals(0, m.size());
	}

	@Test
	void dataEquipmentAttributesAndDimensions() {
		EntityManager m = new EntityManager();
		m.handle(spawn(3, UUID.randomUUID(), PIG, 0, 0, 0));
		m.handle(new Clientbound.SetEntityData(3, List.of(new Clientbound.DataValue(16, 8, true), new Clientbound.DataValue(9, 3, 4.0F),
			new Clientbound.DataValue(2, 6, "Bob")), null));
		m.handle(new Clientbound.UpdateAttributes(3, List.of(new Clientbound.UpdateAttributes.Attribute(30, 2.0, List.of()))));
		EntityManager.View v = m.view(3, 1, 0);
		assertTrue(v.baby());
		assertEquals(4.0F, v.health());
		assertEquals("Bob", v.customName());
		assertEquals(0.45F * 2, v.width(), 1e-6); // eigene Baby-Maße des Schweins (0,45) · Skalierung 2
		m.handle(new Clientbound.SetEntityData(3, List.of(), "index 10: serializer particles"));
		assertEquals(1, m.stats().dataUnsupported);
		assertTrue(v.pickable());
	}

	@Test
	void playerListAndUuidMapping() {
		EntityManager m = new EntityManager();
		UUID u = UUID.randomUUID();
		m.handle(new Clientbound.PlayerInfoUpdate(1 | 4, List.of(new Clientbound.PlayerInfoEntry(u, "Other", List.of(new GameProfile.Property("textures", "x", null)),
			3, false, 0, null, false))));
		m.handle(spawn(4, u, PLAYER, 0, 0, 0));
		EntityManager.View v = m.view(4, 1, 0);
		assertEquals("Other", v.playerName());
		assertTrue(v.skinProperty());
		assertFalse(v.pickable(), "player in spectator mode (game mode 3 in the player list) is not pickable");
		m.handle(new Clientbound.PlayerInfoUpdate(4, List.of(new Clientbound.PlayerInfoEntry(u, null, List.of(), 1, false, 0, null, false))));
		assertTrue(m.view(4, 1, 0).pickable());
		m.handle(new Clientbound.PlayerInfoUpdate(16, List.of(new Clientbound.PlayerInfoEntry(u, null, List.of(), -1, false, 42, null, false))));
		assertEquals(42, m.playerInfo(u).latency());
		assertEquals("Other", m.playerInfo(u).name());
		m.handle(new Clientbound.PlayerInfoRemove(List.of(u)));
		assertNull(m.playerInfo(u));
	}

	@Test
	void clearOnDimensionChangeKeepsNothing() {
		EntityManager m = new EntityManager();
		for (int i = 0; i < 20; i++) {
			m.handle(spawn(i, UUID.randomUUID(), PIG, i, 0, 0));
		}
		m.clear(false);
		assertEquals(0, m.size());
		assertEquals(1, m.stats().cleared);
	}

	@Test
	void passengersLinkBothWays() {
		EntityManager m = new EntityManager();
		m.handle(spawn(1, UUID.randomUUID(), PIG, 0, 0, 0));
		m.handle(spawn(2, UUID.randomUUID(), PIG, 0, 0, 0));
		m.handle(new Clientbound.SetPassengers(1, new int[]{2}));
		assertEquals(1, m.view(2, 1, 0).vehicle());
		m.handle(new Clientbound.RemoveEntities(new int[]{1}));
		assertEquals(-1, m.view(2, 1, 0).vehicle());
	}
}
