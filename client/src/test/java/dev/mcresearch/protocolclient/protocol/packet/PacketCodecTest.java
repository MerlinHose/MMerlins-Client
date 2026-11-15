package dev.mcresearch.protocolclient.protocol.packet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PacketCodecTest {
	private static final PacketRegistry REGISTRY = PacketRegistry.get();

	/** ID + Felder, wie sie auf dem Draht (ohne Frame) stehen. */
	private static byte[] encode(final ServerboundPacket p) {
		PacketWriter w = new PacketWriter().writeVarInt(REGISTRY.idOf(p.state(), PacketDirection.SERVERBOUND, p.name()));
		p.write(w);
		return w.toByteArray();
	}

	private static RawPacket raw(final ProtocolState state, final String name, final byte[] body) {
		int id = REGISTRY.idOf(state, PacketDirection.CLIENTBOUND, name);
		return new RawPacket(state, id, name, body, body.length + 1);
	}

	// ---------- Serverbound: Größen aus dem Vanilla-Referenzlauf (docs/10 §4) ----------

	@Test
	void intentionMatchesVanillaSizeAndLayout() throws ProtocolException {
		byte[] bytes = encode(new Serverbound.Intention("localhost", 25565, Serverbound.Intention.LOGIN));
		assertEquals(16, bytes.length); // Vanilla: "intention -> 16 bytes"
		PacketReader r = new PacketReader(bytes);
		assertEquals(0x00, r.readVarInt());
		assertEquals(777, r.readVarInt());
		assertEquals("localhost", r.readString(255));
		assertEquals(25565, r.readUnsignedShort());
		assertEquals(2, r.readVarInt());
		r.expectEnd("intention");
	}

	@Test
	void helloMatchesVanillaSize() {
		byte[] bytes = encode(new Serverbound.Hello("ResearchBot", GameProfile.offlineUuid("ResearchBot")));
		assertEquals(29, bytes.length); // Vanilla: "hello -> 29 bytes"
	}

	@Test
	void offlineUuidMatchesServerGeneratedUuid() {
		// UUID des Testspielers im Vanilla-Server (server/world/players/data/6842cb30-…​.dat, docs/10)
		assertEquals(UUID.fromString("6842cb30-cc68-3262-af15-9d4eab81f4ed"), GameProfile.offlineUuid("ResearchBot"));
	}

	@Test
	void clientInformationMatchesVanillaSize() {
		assertEquals(15, encode(Serverbound.ClientInformation.defaults(10)).length); // Vanilla: 15 bytes
	}

	@Test
	void selectKnownPacksMatchesVanillaSize() {
		byte[] bytes = encode(new Serverbound.SelectKnownPacks(List.of(new KnownPack("minecraft", "core", "26.3"))));
		assertEquals(22, bytes.length); // Vanilla: 22 bytes
	}

	@Test
	void emptyPacketsAreJustTheId() {
		assertArrayEquals(new byte[]{0x03}, encode(new Serverbound.LoginAcknowledged()));
		assertArrayEquals(new byte[]{0x03}, encode(new Serverbound.FinishConfiguration()));
		assertArrayEquals(new byte[]{0x2C}, encode(new Serverbound.PlayerLoaded()));
		assertArrayEquals(new byte[]{0x0D}, encode(new Serverbound.ClientTickEnd()));
		assertArrayEquals(new byte[]{0x10}, encode(new Serverbound.ConfigurationAcknowledged()));
		assertArrayEquals(new byte[]{0x00}, encode(new Serverbound.StatusRequest()));
	}

	@Test
	void keepAliveIdDependsOnState() {
		assertEquals(0x04, encode(new Serverbound.KeepAlive(ProtocolState.CONFIGURATION, 1))[0]);
		assertEquals(0x1C, encode(new Serverbound.KeepAlive(ProtocolState.PLAY, 1))[0]);
		assertEquals(9, encode(new Serverbound.KeepAlive(ProtocolState.PLAY, 1)).length);
		assertThrows(IllegalArgumentException.class, () -> new Serverbound.KeepAlive(ProtocolState.LOGIN, 1));
	}

	@Test
	void acceptTeleportationLayoutAndValidation() throws ProtocolException {
		byte[] bytes = encode(new Serverbound.AcceptTeleportation(5, 1.5, 64.0, -2.5, 90f, 10f));
		assertEquals(34, bytes.length); // Vanilla: accept_teleportation 34 bytes (Lauf 3)
		PacketReader r = new PacketReader(bytes);
		assertEquals(0x00, r.readVarInt());
		assertEquals(5, r.readVarInt());
		assertEquals(1.5, r.readDouble());
		assertEquals(64.0, r.readDouble());
		assertEquals(-2.5, r.readDouble());
		assertEquals(90f, r.readFloat());
		assertEquals(10f, r.readFloat());
		assertThrows(IllegalArgumentException.class, () -> new Serverbound.AcceptTeleportation(1, Double.NaN, 0, 0, 0, 0));
	}

	@Test
	void invalidPlayerNamesRejected() {
		assertThrows(IllegalArgumentException.class, () -> new Serverbound.Hello("ab", UUID.randomUUID()));
		assertThrows(IllegalArgumentException.class, () -> new Serverbound.Hello("x".repeat(17), UUID.randomUUID()));
		assertThrows(IllegalArgumentException.class, () -> new Serverbound.Hello("Bad Name", UUID.randomUUID()));
	}

	// ---------- Clientbound ----------

	@Test
	void loginFinishedDecodes() throws ProtocolException {
		UUID id = GameProfile.offlineUuid("ResearchBot");
		byte[] body = new PacketWriter().writeUuid(id).writeString("ResearchBot", 16)
			.writeVarInt(1).writeString("textures", 64).writeString("abc", 32767).writeBoolean(true).writeString("sig", 1024)
			.writeUuid(new UUID(1, 2)).toByteArray();
		Clientbound.LoginFinished p = assertInstanceOf(Clientbound.LoginFinished.class,
			ClientboundDecoder.decode(raw(ProtocolState.LOGIN, "minecraft:login_finished", body)));
		assertEquals(id, p.profile().id());
		assertEquals("ResearchBot", p.profile().name());
		assertEquals("sig", p.profile().properties().getFirst().signature());
	}

	@Test
	void loginFinishedMatchesVanillaSizeIncludingSessionId() throws ProtocolException {
		// Vanilla offline: "login_finished -> 46 bytes" (docs/10) = 1 ID + 16 UUID + 12 Name + 1 Anzahl + 16 sessionId
		UUID session = UUID.randomUUID();
		byte[] body = new PacketWriter().writeUuid(UUID.randomUUID()).writeString("ResearchBot", 16).writeVarInt(0).writeUuid(session).toByteArray();
		assertEquals(46, body.length + 1);
		Clientbound.LoginFinished p = assertInstanceOf(Clientbound.LoginFinished.class,
			ClientboundDecoder.decode(raw(ProtocolState.LOGIN, "minecraft:login_finished", body)));
		assertEquals(session, p.sessionId());
		// ohne sessionId fehlt Inhalt -> Fehler statt stiller Annahme
		byte[] truncated = new PacketWriter().writeUuid(UUID.randomUUID()).writeString("ResearchBot", 16).writeVarInt(0).toByteArray();
		assertThrows(ProtocolException.class, () -> ClientboundDecoder.decode(raw(ProtocolState.LOGIN, "minecraft:login_finished", truncated)));
	}

	@Test
	void trailingBytesInKnownPacketRejected() {
		byte[] body = new PacketWriter().writeLong(1).writeByte(0).toByteArray();
		assertThrows(ProtocolException.class, () -> ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:keep_alive", body)));
	}

	@Test
	void playerPositionDecodesAndValidates() throws ProtocolException {
		PacketWriter w = new PacketWriter().writeVarInt(7).writeDouble(10.5).writeDouble(65).writeDouble(1.5)
			.writeDouble(0).writeDouble(0).writeDouble(0).writeFloat(0).writeFloat(0).writeInt(Clientbound.PlayerPosition.REL_Y_ROT);
		Clientbound.PlayerPosition p = assertInstanceOf(Clientbound.PlayerPosition.class,
			ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:player_position", w.toByteArray())));
		assertEquals(7, p.teleportId());
		assertEquals(65, p.y());
		PacketWriter bad = new PacketWriter().writeVarInt(7).writeDouble(Double.NaN).writeDouble(65).writeDouble(1.5)
			.writeDouble(0).writeDouble(0).writeDouble(0).writeFloat(0).writeFloat(0).writeInt(0);
		assertThrows(ProtocolException.class, () -> ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:player_position", bad.toByteArray())));
		PacketWriter flags = new PacketWriter().writeVarInt(7).writeDouble(1).writeDouble(65).writeDouble(1.5)
			.writeDouble(0).writeDouble(0).writeDouble(0).writeFloat(0).writeFloat(0).writeInt(1 << 12);
		assertThrows(ProtocolException.class, () -> ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:player_position", flags.toByteArray())));
	}

	@Test
	void registryDataWithAndWithoutNbt() throws IOException {
		ByteArrayOutputStream nbt = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(nbt);
		d.writeByte(10);
		d.writeByte(3);
		d.writeUTF("height");
		d.writeInt(384);
		d.writeByte(0);
		byte[] body = new PacketWriter().writeIdentifier("minecraft:dimension_type").writeVarInt(2)
			.writeIdentifier("minecraft:overworld").writeBoolean(false)
			.writeIdentifier("minecraft:the_nether").writeBoolean(true).writeBytes(nbt.toByteArray()).toByteArray();
		Clientbound.RegistryData p = assertInstanceOf(Clientbound.RegistryData.class,
			ClientboundDecoder.decode(raw(ProtocolState.CONFIGURATION, "minecraft:registry_data", body)));
		assertEquals("minecraft:dimension_type", p.registry());
		assertEquals(2, p.entries().size());
		assertFalse(p.entries().get(0).hasData());
		assertTrue(p.entries().get(1).hasData());
	}

	@Test
	void updateTagsCountsValues() throws ProtocolException {
		byte[] body = new PacketWriter().writeVarInt(1).writeIdentifier("minecraft:block").writeVarInt(2)
			.writeIdentifier("minecraft:logs").writeVarInt(3).writeVarInt(1).writeVarInt(2).writeVarInt(3)
			.writeIdentifier("minecraft:leaves").writeVarInt(0).toByteArray();
		Clientbound.UpdateTags p = assertInstanceOf(Clientbound.UpdateTags.class,
			ClientboundDecoder.decode(raw(ProtocolState.CONFIGURATION, "minecraft:update_tags", body)));
		assertEquals(new Clientbound.UpdateTags(1, 2, 3), p);
	}

	@Test
	void customPayloadBrandAndUnknownChannel() throws ProtocolException {
		byte[] brand = new PacketWriter().writeIdentifier("minecraft:brand").writeString("vanilla", 32767).toByteArray();
		assertEquals(25, brand.length + 1); // Vanilla: custom_payload (brand "vanilla") -> 25 bytes
		Clientbound.CustomPayload p = assertInstanceOf(Clientbound.CustomPayload.class,
			ClientboundDecoder.decode(raw(ProtocolState.CONFIGURATION, "minecraft:custom_payload", brand)));
		assertEquals("vanilla", p.brand());
		byte[] other = new PacketWriter().writeIdentifier("example:data").writeBytes(new byte[]{1, 2, 3}).toByteArray();
		Clientbound.CustomPayload o = assertInstanceOf(Clientbound.CustomPayload.class,
			ClientboundDecoder.decode(raw(ProtocolState.CONFIGURATION, "minecraft:custom_payload", other)));
		assertNull(o.brand());
		assertEquals(3, o.payloadBytes());
	}

	@Test
	void disconnectReasonFromNbt() throws IOException {
		ByteArrayOutputStream nbt = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(nbt);
		d.writeByte(8);
		d.writeUTF("Laufzeittest beendet");
		Clientbound.Disconnect p = assertInstanceOf(Clientbound.Disconnect.class,
			ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:disconnect", nbt.toByteArray())));
		assertEquals("Laufzeittest beendet", p.reason());
	}

	@Test
	void statusResponseValidated() throws ProtocolException {
		Clientbound.StatusResponse s = ClientboundDecoder.statusResponse(
			"{\"version\":{\"name\":\"26.3\",\"protocol\":777},\"players\":{\"max\":20,\"online\":0},\"description\":{\"text\":\"Test\"}}");
		assertEquals(777, s.protocol());
		assertEquals("Test", s.description());
		assertThrows(ProtocolException.class, () -> ClientboundDecoder.statusResponse("{\"players\":{}}"));
		assertThrows(ProtocolException.class, () -> ClientboundDecoder.statusResponse("[]"));
	}

	@Test
	void playLoginDecodesVanillaLayout() throws ProtocolException {
		PacketWriter w = new PacketWriter().writeInt(17).writeBoolean(false)
			.writeVarInt(3).writeIdentifier("minecraft:overworld").writeIdentifier("minecraft:the_nether").writeIdentifier("minecraft:the_end")
			.writeVarInt(20).writeVarInt(10).writeVarInt(10).writeBoolean(false).writeBoolean(true).writeBoolean(false)
			.writeVarInt(0).writeIdentifier("minecraft:overworld").writeLong(123L).writeVarInt(0).writeVarInt(0)
			.writeBoolean(false).writeBoolean(false).writeBoolean(false).writeVarInt(0).writeVarInt(63).writeBoolean(false).writeBoolean(false);
		Clientbound.PlayLogin p = assertInstanceOf(Clientbound.PlayLogin.class,
			ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:login", w.toByteArray())));
		assertEquals(17, p.entityId());
		assertEquals("minecraft:overworld", p.dimension());
		assertEquals(-1, p.previousGameType());
		assertEquals(63, p.seaLevel());
		PacketWriter badGameType = new PacketWriter().writeInt(1).writeBoolean(false).writeVarInt(1).writeIdentifier("minecraft:overworld")
			.writeVarInt(20).writeVarInt(10).writeVarInt(10).writeBoolean(false).writeBoolean(true).writeBoolean(false)
			.writeVarInt(0).writeIdentifier("minecraft:overworld").writeLong(1L).writeVarInt(9);
		assertThrows(ProtocolException.class, () -> ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:login", badGameType.toByteArray())));
	}

	@Test
	void unhandledPacketsAreSkippedButMustBeValidIds() throws ProtocolException {
		Clientbound p = ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:award_stats", new byte[100]));
		assertEquals(new Clientbound.Skipped("minecraft:award_stats", 101), p);
	}

	/** M12 (docs/48 §2, §7): set_time mit Weltuhren, level_event, level_particles (einfacher Typ, Blocktyp, nicht dekodierbarer Typ). */
	@Test
	void m12EnvironmentAndParticlePackets() throws ProtocolException {
		PacketWriter w = new PacketWriter().writeLong(123456L).writeVarInt(1).writeVarInt(0).writeVarLong(18000L).writeFloat(0.25f).writeFloat(0f);
		Clientbound.SetTime p = assertInstanceOf(Clientbound.SetTime.class, ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:set_time", w.toByteArray())));
		assertEquals(123456L, p.gameTime());
		assertEquals(new Clientbound.ClockUpdate(0, 18000L, 0.25f, 0f), p.clocks().getFirst());
		PacketWriter ev = new PacketWriter().writeInt(2001).writeLong(dev.mcresearch.protocolclient.protocol.codec.Positions.packBlock(10, 64, -3)).writeInt(1).writeBoolean(false);
		Clientbound.LevelEvent le = assertInstanceOf(Clientbound.LevelEvent.class, ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:level_event", ev.toByteArray())));
		assertEquals(new Clientbound.LevelEvent(2001, 10, 64, -3, 1, false), le);
		PacketWriter crit = particle(new PacketWriter().writeVarInt(13));
		Clientbound.LevelParticles lp = assertInstanceOf(Clientbound.LevelParticles.class, ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:level_particles", crit.toByteArray())));
		assertEquals("minecraft:crit", lp.typeName());
		assertEquals(7, lp.count());
		PacketWriter block = particle(new PacketWriter().writeVarInt(1).writeVarInt(1));
		Clientbound.LevelParticles bp = assertInstanceOf(Clientbound.LevelParticles.class, ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:level_particles", block.toByteArray())));
		assertEquals(1, bp.blockState(), "block particle carries the stone state id");
		// minecraft:dust (Farbe + Größe) ist nicht dekodierbar → übersprungen statt Fehler
		PacketWriter dust = new PacketWriter().writeVarInt(21).writeInt(0xFF0000).writeFloat(1f).writeBoolean(false);
		assertInstanceOf(Clientbound.Skipped.class, ClientboundDecoder.decode(raw(ProtocolState.PLAY, "minecraft:level_particles", dust.toByteArray())));
	}

	private static PacketWriter particle(final PacketWriter w) {
		return w.writeBoolean(false).writeBoolean(true).writeDouble(1.5).writeDouble(64).writeDouble(-2.5).writeFloat(0.3f).writeFloat(0.3f).writeFloat(0.3f)
			.writeFloat(0.1f).writeFloat(0.1f).writeFloat(0.1f).writeVarInt(7).writeVarInt(0);
	}
}
