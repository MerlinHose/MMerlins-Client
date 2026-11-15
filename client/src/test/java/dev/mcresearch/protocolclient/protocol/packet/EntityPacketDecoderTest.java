package dev.mcresearch.protocolclient.protocol.packet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.protocol.PacketDirection;
import dev.mcresearch.protocolclient.protocol.PacketRegistry;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.RawPacket;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M9: Entity-Pakete (docs/34). LpVec3-Referenzbytes aus Mojangs Kodierer ({@code analysis/tools/LpVecCheck.java}). */
class EntityPacketDecoderTest {
	private static RawPacket raw(final String name, final byte[] body) {
		return new RawPacket(ProtocolState.PLAY, PacketRegistry.get().idOf(ProtocolState.PLAY, PacketDirection.CLIENTBOUND, name), name, body, body.length + 1);
	}

	/** Eingabe-Vektor, Mojang-Bytes, von Mojang zurückgelesener Vektor (LpVecCheck-Ausgabe). */
	private static final Object[][] LPVEC = {
		{"00", 0.0, 0.0, 0.0},
		{"29338664ccca", 0.09998168833546961, -0.2000244155527071, 0.049990844167734805},
		{"f9ff7ffeebed", 0.0, -0.0783739241897089, 0.0},
		{"fcbf08010ffe01", 1.5000915583226515, 0.2500152597204419, -3.749984740279558},
		{"07008923492001", -7.0, 2.0000610388817686, 0.49990844167734827},
		{"9499899800020a", 12.000244155527069, -40.0, 3.000671427699446},
	};

	@Test
	void lpVec3MatchesMojangReferenceBytes() throws ProtocolException {
		for (Object[] row : LPVEC) {
			byte[] bytes = HexFormat.of().parseHex((String) row[0]);
			PacketReader r = new PacketReader(bytes);
			double[] v = EntityPacketDecoder.lpVec3(r);
			assertEquals(0, r.remaining(), "all bytes consumed for " + row[0]);
			assertEquals((double) row[1], v[0], 0.0, row[0] + " x");
			assertEquals((double) row[2], v[1], 0.0, row[0] + " y");
			assertEquals((double) row[3], v[2], 0.0, row[0] + " z");
		}
	}

	@Test
	void addEntityWithAnglesAndMotion() throws ProtocolException {
		UUID u = UUID.fromString("7cdd3477-7ded-4159-8435-2bceeeb112aa");
		byte[] body = new PacketWriter().writeVarInt(278).writeUuid(u).writeVarInt(101).writeDouble(2.5).writeDouble(100).writeDouble(-31.5)
			.writeBytes(HexFormat.of().parseHex("f9ff7ffeebed")).writeByte(-32).writeByte(32).writeByte(-128).writeVarInt(0).toByteArray();
		Clientbound.AddEntity p = assertInstanceOf(Clientbound.AddEntity.class, ClientboundDecoder.decode(raw("minecraft:add_entity", body)));
		assertEquals(278, p.id());
		assertEquals(u, p.uuid());
		assertEquals(101, p.type());
		assertEquals(-45.0F, p.xRot()); // -32 · 360 / 256
		assertEquals(45.0F, p.yRot());
		assertEquals(-180.0F, p.headYRot());
		assertEquals(-0.0783739241897089, p.dy(), 0.0);
	}

	@Test
	void steppedMoveDecodesAllSteps() throws ProtocolException {
		// Eigenschaften: onGround (1) | 3 Schritte << 1
		PacketWriter w = new PacketWriter().writeVarInt(5).writeVarInt(1 | 3 << 1);
		w.writeVarInt(1).writeShort(410).writeShort(0).writeShort(-100);
		w.writeVarInt(1).writeShort(410).writeShort(0).writeShort(-100);
		w.writeVarInt(2).writeShort(820).writeShort(4096).writeShort(0);
		w.writeByte(64).writeByte(0);
		Clientbound.MoveEntity p = assertInstanceOf(Clientbound.MoveEntity.class, ClientboundDecoder.decode(raw("minecraft:move_entity_pos_rot", w.toByteArray())));
		assertTrue(p.stepped() && p.hasPos() && p.hasRot() && p.onGround());
		assertEquals(3, p.steps().size());
		assertEquals(2, p.steps().get(2).ticks());
		assertEquals(4096, p.steps().get(2).dy());
		assertEquals(90.0F, p.yRot());
	}

	@Test
	void linearMoveAndRotationOnly() throws ProtocolException {
		Clientbound.MoveEntity p = assertInstanceOf(Clientbound.MoveEntity.class, ClientboundDecoder.decode(raw("minecraft:move_entity_pos",
			new PacketWriter().writeVarInt(5).writeVarInt(0).writeShort(1).writeShort(-2).writeShort(3).toByteArray())));
		assertEquals(1, p.steps().size());
		assertEquals(0, p.steps().getFirst().ticks());
		Clientbound.MoveEntity r = assertInstanceOf(Clientbound.MoveEntity.class, ClientboundDecoder.decode(raw("minecraft:move_entity_rot",
			new PacketWriter().writeVarInt(5).writeBoolean(true).writeByte(-64).writeByte(10).toByteArray())));
		assertTrue(r.hasRot() && !r.hasPos());
		assertEquals(-90.0F, r.yRot());
	}

	@Test
	void tooManyStepsIsRejected() {
		byte[] body = new PacketWriter().writeVarInt(5).writeVarInt(500 << 1).writeVarInt(1).writeShort(0).writeShort(0).writeShort(0).toByteArray();
		assertThrows(ProtocolException.class, () -> ClientboundDecoder.decode(raw("minecraft:move_entity_pos", body)));
	}

	@Test
	void positionSyncLinearAndStepped() throws ProtocolException {
		Clientbound.EntityPositionSync a = assertInstanceOf(Clientbound.EntityPositionSync.class, ClientboundDecoder.decode(raw("minecraft:entity_position_sync",
			new PacketWriter().writeVarInt(9).writeVarInt(0).writeDouble(1).writeDouble(2).writeDouble(3).writeFloat(10).writeFloat(-5).writeBoolean(true)
				.toByteArray())));
		assertEquals(1, a.path().size());
		PacketWriter w = new PacketWriter().writeVarInt(9).writeVarInt(1).writeVarInt(2);
		w.writeDouble(1).writeDouble(2).writeDouble(3).writeVarInt(1).writeDouble(4).writeDouble(5).writeDouble(6).writeVarInt(2);
		w.writeFloat(0).writeFloat(0).writeBoolean(false);
		Clientbound.EntityPositionSync b = assertInstanceOf(Clientbound.EntityPositionSync.class,
			ClientboundDecoder.decode(raw("minecraft:entity_position_sync", w.toByteArray())));
		assertTrue(b.stepped());
		assertEquals(4.0, b.path().getLast().x());
	}

	@Test
	void entityDataStopsAtUnsupportedSerializerAndKeepsEarlierValues() throws ProtocolException {
		PacketWriter w = new PacketWriter().writeVarInt(7);
		w.writeByte(0).writeVarInt(0).writeByte(0x20); // Flags (byte) = unsichtbar
		w.writeByte(9).writeVarInt(3).writeFloat(7.5F); // Gesundheit (float)
		w.writeByte(6).writeVarInt(20).writeVarInt(5); // Pose (schleichend)
		w.writeByte(16).writeVarInt(8).writeBoolean(true); // Baby
		w.writeByte(10).writeVarInt(17).writeVarInt(1).writeVarInt(57); // Partikelliste mit Item-Partikel (Optionen nicht umgesetzt)
		w.writeByte(255);
		Clientbound.SetEntityData p = assertInstanceOf(Clientbound.SetEntityData.class, ClientboundDecoder.decode(raw("minecraft:set_entity_data", w.toByteArray())));
		assertEquals(4, p.values().size());
		assertEquals((byte) 0x20, p.values().get(0).value());
		assertEquals(7.5F, p.values().get(1).value());
		assertEquals(5, p.values().get(2).value());
		assertNotNull(p.unsupported());
		assertTrue(p.unsupported().contains("particles"));
	}

	@Test
	void effectParticlesBeforeAbsorptionAreDecodedSoAbsorptionArrives() throws ProtocolException {
		// M10: Effektpartikel (Index 10) stehen im selben Paket vor der Absorption (Index 17) des Spielers
		PacketWriter w = new PacketWriter().writeVarInt(7);
		w.writeByte(10).writeVarInt(17).writeVarInt(2).writeVarInt(28).writeInt(0xFFF82423).writeVarInt(41); // entity_effect + einfacher Typ
		w.writeByte(17).writeVarInt(3).writeFloat(4.0F);
		w.writeByte(255);
		Clientbound.SetEntityData p = assertInstanceOf(Clientbound.SetEntityData.class, ClientboundDecoder.decode(raw("minecraft:set_entity_data", w.toByteArray())));
		assertNull(p.unsupported());
		assertEquals(2, p.values().size());
		assertEquals(4.0F, p.values().get(1).value());
	}

	@Test
	void entityDataCompleteWithOptionals() throws ProtocolException {
		PacketWriter w = new PacketWriter().writeVarInt(7);
		w.writeByte(2).writeVarInt(6).writeBoolean(false); // kein eigener Name
		w.writeByte(19).writeVarInt(19).writeVarInt(0); // opt. uint leer
		w.writeByte(13).writeVarInt(13).writeBoolean(false);
		w.writeByte(255);
		Clientbound.SetEntityData p = assertInstanceOf(Clientbound.SetEntityData.class, ClientboundDecoder.decode(raw("minecraft:set_entity_data", w.toByteArray())));
		assertNull(p.unsupported());
		assertEquals(3, p.values().size());
		assertNull(p.values().get(0).value());
	}

	@Test
	void equipmentWithContinuationBit() throws ProtocolException {
		PacketWriter w = new PacketWriter().writeVarInt(3);
		w.writeByte(0x80 | 5).writeVarInt(1).writeVarInt(1).writeVarInt(0).writeVarInt(0); // Kopf: Item 1 (Stein), 1 Stück, keine Komponenten
		w.writeByte(0).writeVarInt(0); // Haupthand: leer
		Clientbound.SetEquipment p = assertInstanceOf(Clientbound.SetEquipment.class, ClientboundDecoder.decode(raw("minecraft:set_equipment", w.toByteArray())));
		assertNull(p.unsupported());
		assertEquals(1, p.slots().get(5).itemId());
		assertTrue(p.slots().get(0).isEmpty());
	}

	@Test
	void playerInfoAddWithGameModeAndListed() throws ProtocolException {
		UUID u = UUID.randomUUID();
		PacketWriter w = new PacketWriter().writeByte(1 | 4 | 8).writeVarInt(1).writeUuid(u);
		w.writeString("ProtoBotM9B", 16).writeVarInt(1).writeString("textures", 64).writeString("e30=", 32767).writeBoolean(false);
		w.writeVarInt(1).writeBoolean(true);
		Clientbound.PlayerInfoUpdate p = assertInstanceOf(Clientbound.PlayerInfoUpdate.class, ClientboundDecoder.decode(raw("minecraft:player_info_update", w.toByteArray())));
		Clientbound.PlayerInfoEntry e = p.entries().getFirst();
		assertEquals("ProtoBotM9B", e.name());
		assertEquals(1, e.gameMode());
		assertTrue(e.listed());
		assertEquals("textures", e.properties().getFirst().name());
	}

	@Test
	void removeAndPassengers() throws ProtocolException {
		Clientbound.RemoveEntities r = assertInstanceOf(Clientbound.RemoveEntities.class, ClientboundDecoder.decode(raw("minecraft:remove_entities",
			new PacketWriter().writeVarInt(3).writeVarInt(1).writeVarInt(300).writeVarInt(70000).toByteArray())));
		assertArrayEquals(new int[]{1, 300, 70000}, r.ids());
		Clientbound.SetPassengers s = assertInstanceOf(Clientbound.SetPassengers.class, ClientboundDecoder.decode(raw("minecraft:set_passengers",
			new PacketWriter().writeVarInt(4).writeVarInt(2).writeVarInt(5).writeVarInt(6).toByteArray())));
		assertArrayEquals(new int[]{5, 6}, s.passengers());
	}
}
