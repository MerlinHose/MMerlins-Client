package dev.mcresearch.protocolclient.protocol.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mcresearch.protocolclient.protocol.PacketDirection;
import dev.mcresearch.protocolclient.protocol.PacketRegistry;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.RawPacket;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;
import org.junit.jupiter.api.Test;

class MovementPacketTest {
	private static byte[] encode(final ServerboundPacket p) {
		PacketWriter w = new PacketWriter().writeVarInt(PacketRegistry.get().idOf(p.state(), PacketDirection.SERVERBOUND, p.name()));
		p.write(w);
		return w.toByteArray();
	}

	private static RawPacket raw(final String name, final byte[] body) {
		return new RawPacket(ProtocolState.PLAY, PacketRegistry.get().idOf(ProtocolState.PLAY, PacketDirection.CLIENTBOUND, name), name, body, body.length + 1);
	}

	@Test
	void movePacketsMatchVanillaIdsAndSizes() throws ProtocolException {
		// Größen aus dem Vanilla-Referenzlauf (docs/10): Pos 26, PosRot 34, Rot 10, StatusOnly 2, player_input 2
		byte[] pos = encode(new Serverbound.MovePlayerPos(1.5, 64, -2.5, true, false));
		assertEquals(26, pos.length);
		PacketReader r = new PacketReader(pos);
		assertEquals(0x1E, r.readVarInt());
		assertEquals(1.5, r.readDouble());
		assertEquals(64, r.readDouble());
		assertEquals(-2.5, r.readDouble());
		assertEquals(0x01, r.readUnsignedByte()); // onGround
		byte[] posRot = encode(new Serverbound.MovePlayerPosRot(1, 2, 3, 90f, -10f, false, true));
		assertEquals(34, posRot.length);
		assertEquals(0x1F, posRot[0]);
		assertEquals(0x02, posRot[33]); // nur horizontalCollision
		byte[] rot = encode(new Serverbound.MovePlayerRot(90f, 10f, true, true));
		assertEquals(10, rot.length);
		assertEquals(0x20, rot[0]);
		assertEquals(0x03, rot[9]);
		byte[] status = encode(new Serverbound.MovePlayerStatusOnly(true, false));
		assertEquals(2, status.length);
		assertEquals(0x21, status[0]);
		byte[] input = encode(new Serverbound.PlayerInput(true, false, true, false, true, false, true));
		assertEquals(2, input.length);
		assertEquals(0x2B, input[0]);
		assertEquals(1 | 4 | 16 | 64, input[1]);
		assertThrows(IllegalArgumentException.class, () -> new Serverbound.MovePlayerPos(Double.NaN, 0, 0, true, false));
		assertThrows(IllegalArgumentException.class, () -> new Serverbound.MovePlayerRot(Float.POSITIVE_INFINITY, 0, true, false));
	}

	@Test
	void playerRotationDecodes() throws ProtocolException {
		byte[] body = new PacketWriter().writeFloat(180f).writeBoolean(false).writeFloat(5f).writeBoolean(true).toByteArray();
		Clientbound.PlayerRotation p = assertInstanceOf(Clientbound.PlayerRotation.class, ClientboundDecoder.decode(raw("minecraft:player_rotation", body)));
		assertEquals(new Clientbound.PlayerRotation(180f, false, 5f, true), p);
		byte[] bad = new PacketWriter().writeFloat(Float.NaN).writeBoolean(false).writeFloat(5f).writeBoolean(true).toByteArray();
		assertThrows(ProtocolException.class, () -> ClientboundDecoder.decode(raw("minecraft:player_rotation", bad)));
	}

	@Test
	void chunkPrefixWithHeightmapsDecodes() throws ProtocolException {
		PacketWriter w = new PacketWriter().writeInt(-3).writeInt(7).writeVarInt(2);
		w.writeVarInt(1).writeVarInt(37);
		for (int i = 0; i < 37; i++) {
			w.writeLong(i);
		}
		w.writeVarInt(4).writeVarInt(37);
		for (int i = 0; i < 37; i++) {
			w.writeLong(-i);
		}
		// seit M4 vollständige Struktur: Sektionspuffer (hier 3 Bytes, Inhalt prüft world.ChunkDecoder), 0 Block-Entities,
		// Licht: 4 leere BitSets, 2 leere Listen
		w.writeVarInt(3).writeBytes(new byte[]{1, 2, 3}).writeVarInt(0);
		w.writeVarInt(0).writeVarInt(0).writeVarInt(0).writeVarInt(0).writeVarInt(0).writeVarInt(0);
		byte[] body = w.toByteArray();
		Clientbound.LevelChunkWithLight chunk = assertInstanceOf(Clientbound.LevelChunkWithLight.class,
			ClientboundDecoder.decode(raw("minecraft:level_chunk_with_light", body)));
		Clientbound.ChunkHeightmaps c = chunk.heightmapsRecord();
		assertEquals(-3, c.chunkX());
		assertEquals(7, c.chunkZ());
		assertEquals(-5L, c.heightmaps().get(4)[5]);
		assertEquals(3, chunk.sectionData().length);
		byte[] trailing = java.util.Arrays.copyOf(body, body.length + 4);
		assertThrows(ProtocolException.class, () -> ClientboundDecoder.decode(raw("minecraft:level_chunk_with_light", trailing)));
		PacketWriter badType = new PacketWriter().writeInt(0).writeInt(0).writeVarInt(1).writeVarInt(9).writeVarInt(0);
		assertThrows(ProtocolException.class, () -> ClientboundDecoder.decode(raw("minecraft:level_chunk_with_light", badType.toByteArray())));
		PacketWriter tooLong = new PacketWriter().writeInt(0).writeInt(0).writeVarInt(1).writeVarInt(4).writeVarInt(1000);
		assertThrows(ProtocolException.class, () -> ClientboundDecoder.decode(raw("minecraft:level_chunk_with_light", tooLong.toByteArray())));
	}

	@Test
	void forgetChunkUnpacksChunkPos() throws ProtocolException {
		long packed = (-5 & 0xFFFFFFFFL) | ((long) 12 << 32); // ChunkPos#pack(-5, 12)
		Clientbound.ForgetChunk f = assertInstanceOf(Clientbound.ForgetChunk.class,
			ClientboundDecoder.decode(raw("minecraft:forget_level_chunk", new PacketWriter().writeLong(packed).toByteArray())));
		assertEquals(new Clientbound.ForgetChunk(-5, 12), f);
	}

	@Test
	void registryDataKeepsNbtForDimensionInfo() throws Exception {
		java.io.ByteArrayOutputStream nbt = new java.io.ByteArrayOutputStream();
		java.io.DataOutputStream d = new java.io.DataOutputStream(nbt);
		d.writeByte(10);
		d.writeByte(3);
		d.writeUTF("min_y");
		d.writeInt(-64);
		d.writeByte(3);
		d.writeUTF("height");
		d.writeInt(384);
		d.writeByte(0);
		byte[] body = new PacketWriter().writeIdentifier("minecraft:dimension_type").writeVarInt(1).writeIdentifier("minecraft:overworld")
			.writeBoolean(true).writeBytes(nbt.toByteArray()).toByteArray();
		RawPacket rp = new RawPacket(ProtocolState.CONFIGURATION, 7, "minecraft:registry_data", body, body.length + 1);
		Clientbound.RegistryData p = assertInstanceOf(Clientbound.RegistryData.class, ClientboundDecoder.decode(rp));
		assertEquals(-64, dev.mcresearch.protocolclient.world.DimensionInfo.fromNbt("minecraft:overworld", p.entries().getFirst().data()).minY());
	}
}
