package dev.mcresearch.protocolclient.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import dev.mcresearch.protocolclient.protocol.packet.GameProfile;
import dev.mcresearch.protocolclient.protocol.packet.KnownPack;
import dev.mcresearch.protocolclient.protocol.packet.Serverbound;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ClientSessionTest {
	private final AtomicLong clock = new AtomicLong();
	private final FakeChannel channel = new FakeChannel();

	private ClientSession session(final boolean acceptPacks, final int playSeconds) {
		SessionConfig c = new SessionConfig("127.0.0.1", 25565, "ResearchBot", 2, Duration.ofSeconds(playSeconds), acceptPacks, "test",
			9f, Duration.ofSeconds(1), Duration.ofSeconds(5), null);
		return new ClientSession(this.channel, c, this.clock::get);
	}

	private static final List<KnownPack> CORE = List.of(new KnownPack("minecraft", "core", "26.3"));

	private static Clientbound.PlayLogin playLogin(final int dimensionType) {
		return new Clientbound.PlayLogin(17, false, List.of("minecraft:overworld"), 20, 10, 10, false, true, false, dimensionType,
			"minecraft:overworld", 0L, 0, -1, false, false, null, 0L, 0, 63, false, false);
	}

	/** Bringt die Sitzung bis in den Play-Zustand. */
	private ClientSession toPlay(final ClientSession s) throws Exception {
		s.start();
		s.handle(new Clientbound.LoginFinished(new GameProfile(GameProfile.offlineUuid("ResearchBot"), "ResearchBot", List.of()), UUID.randomUUID()));
		s.handle(new Clientbound.SelectKnownPacks(CORE));
		s.handle(new Clientbound.RegistryData("minecraft:dimension_type", List.of(new Clientbound.RegistryEntry("minecraft:overworld", false))));
		s.handle(new Clientbound.FinishConfiguration());
		s.handle(playLogin(0));
		return s;
	}

	@Test
	void loginSequenceAndStateTransitionsMatchVanilla() throws Exception {
		ClientSession s = session(true, 60);
		s.start();
		assertEquals(ProtocolState.LOGIN, s.state());
		s.handle(new Clientbound.LoginFinished(new GameProfile(GameProfile.offlineUuid("ResearchBot"), "ResearchBot", List.of()), UUID.randomUUID()));
		assertEquals(ProtocolState.CONFIGURATION, s.state());
		// Reihenfolge und Zustand beim Senden wie im Vanilla-Referenzlauf (docs/10 §4)
		assertEquals(List.of("handshake/minecraft:intention", "login/minecraft:hello", "login/minecraft:login_acknowledged",
			"configuration/minecraft:custom_payload", "configuration/minecraft:client_information"), this.channel.sentNames());
		assertEquals(List.of("STEP HANDSHAKE -> LOGIN", "STEP LOGIN -> CONFIGURATION"), this.channel.markers);
	}

	@Test
	void knownPacksAcceptedOrDeclined() throws Exception {
		ClientSession accept = session(true, 60);
		accept.start();
		accept.handle(new Clientbound.LoginFinished(new GameProfile(UUID.randomUUID(), "ResearchBot", List.of()), UUID.randomUUID()));
		accept.handle(new Clientbound.SelectKnownPacks(CORE));
		assertEquals(CORE, this.channel.last(Serverbound.SelectKnownPacks.class).packs());

		FakeChannel other = new FakeChannel();
		ClientSession decline = new ClientSession(other, new SessionConfig("127.0.0.1", 25565, "ResearchBot", 2, Duration.ofSeconds(1), false,
			"t", 9f, Duration.ofSeconds(1), Duration.ofSeconds(1), null), this.clock::get);
		decline.start();
		decline.handle(new Clientbound.LoginFinished(new GameProfile(UUID.randomUUID(), "ResearchBot", List.of()), UUID.randomUUID()));
		decline.handle(new Clientbound.SelectKnownPacks(CORE));
		assertTrue(other.last(Serverbound.SelectKnownPacks.class).packs().isEmpty());
	}

	@Test
	void keepAliveAndPingEchoedInCurrentState() throws Exception {
		ClientSession s = session(true, 60);
		s.start();
		s.handle(new Clientbound.LoginFinished(new GameProfile(UUID.randomUUID(), "ResearchBot", List.of()), UUID.randomUUID()));
		s.handle(new Clientbound.KeepAlive(123456789L));
		Serverbound.KeepAlive ka = this.channel.last(Serverbound.KeepAlive.class);
		assertEquals(123456789L, ka.id());
		assertEquals(ProtocolState.CONFIGURATION, ka.state());
		s.handle(new Clientbound.Ping(42));
		assertEquals(42, this.channel.last(Serverbound.Pong.class).id());
		s.handle(new Clientbound.FinishConfiguration());
		s.handle(new Clientbound.KeepAlive(7L));
		assertEquals(ProtocolState.PLAY, this.channel.last(Serverbound.KeepAlive.class).state());
		assertEquals(2, s.stats().keepAlivesAnswered());
	}

	@Test
	void finishConfigurationSwitchesToPlayAfterAcknowledging() throws Exception {
		ClientSession s = toPlay(session(true, 60));
		assertEquals(ProtocolState.PLAY, s.state());
		List<String> sent = this.channel.sentNames();
		assertEquals("configuration/minecraft:finish_configuration", sent.getLast());
	}

	@Test
	void teleportAcceptedWithAbsoluteAndRelativeCoordinatesAndPlayerLoadedOnce() throws Exception {
		ClientSession s = toPlay(session(true, 60));
		s.handle(new Clientbound.PlayerPosition(1, 10.5, 65, 1.5, 0, 0, 0, 90f, 10f, 0));
		Serverbound.AcceptTeleportation a = this.channel.last(Serverbound.AcceptTeleportation.class);
		assertEquals(1, a.teleportId());
		assertEquals(10.5, a.x());
		assertEquals(65, a.y());
		assertEquals(90f, a.yRot());
		int rel = Clientbound.PlayerPosition.REL_X | Clientbound.PlayerPosition.REL_Y_ROT;
		s.handle(new Clientbound.PlayerPosition(2, 2.0, 70, 3.0, 0, 0, 0, 5f, 0f, rel));
		Serverbound.AcceptTeleportation b = this.channel.last(Serverbound.AcceptTeleportation.class);
		assertEquals(12.5, b.x()); // relativ: 10.5 + 2
		assertEquals(70, b.y()); // absolut
		assertEquals(95f, b.yRot()); // relativ: 90 + 5
		assertEquals(1, this.channel.sentNames().stream().filter(n -> n.endsWith("player_loaded")).count());
		assertEquals(2, s.stats().teleportsAccepted());
	}

	@Test
	void chunkBatchAcknowledged() throws Exception {
		ClientSession s = toPlay(session(true, 60));
		s.handle(new Clientbound.ChunkBatchStart());
		s.handle(new Clientbound.ChunkBatchFinished(25));
		assertEquals(9f, this.channel.last(Serverbound.ChunkBatchReceived.class).desiredChunksPerTick());
		assertEquals(25, s.stats().chunksAnnounced());
	}

	@Test
	void tickEndOnlyInPlayAndStopsAfterDuration() throws Exception {
		ClientSession s = session(true, 60);
		s.start();
		s.tick();
		assertFalse(this.channel.sentNames().stream().anyMatch(n -> n.endsWith("client_tick_end")));
		toPlayFromLogin(s);
		s.tick();
		s.tick();
		assertEquals(2, this.channel.sentNames().stream().filter(n -> n.equals("play/minecraft:client_tick_end")).count());
		this.clock.addAndGet(Duration.ofSeconds(61).toNanos());
		s.tick();
		assertTrue(s.stopRequested());
		assertTrue(this.channel.closed);
	}

	private void toPlayFromLogin(final ClientSession s) throws Exception {
		s.handle(new Clientbound.LoginFinished(new GameProfile(UUID.randomUUID(), "ResearchBot", List.of()), UUID.randomUUID()));
		s.handle(new Clientbound.SelectKnownPacks(CORE));
		s.handle(new Clientbound.FinishConfiguration());
		s.handle(playLogin(0));
	}

	@Test
	void movementPacketsPrecedeTickEndAndAtMostOnePositionPerTick() throws Exception {
		ClientSession s = session(false, 60);
		s.start();
		s.handle(new Clientbound.LoginFinished(new GameProfile(UUID.randomUUID(), "ResearchBot", List.of()), UUID.randomUUID()));
		s.handle(new Clientbound.SelectKnownPacks(CORE));
		s.handle(new Clientbound.RegistryData("minecraft:dimension_type", List.of(new Clientbound.RegistryEntry("minecraft:overworld", true,
			java.util.Map.of("min_y", -64, "height", 384)))));
		s.handle(new Clientbound.RegistryData("minecraft:worldgen/biome", List.of(new Clientbound.RegistryEntry("minecraft:plains", false),
			new Clientbound.RegistryEntry("minecraft:desert", false))));
		s.handle(new Clientbound.FinishConfiguration());
		s.handle(playLogin(0));
		// vollständige Chunk-Pakete: Stein bis y=63 (Sektionen 0–7), Heightmap MOTION_BLOCKING = 64
		java.util.Map<Integer, int[]> ground = new java.util.HashMap<>();
		for (int i = 0; i < 8; i++) {
			ground.put(i, dev.mcresearch.protocolclient.world.ChunkTestSupport.filled(1));
		}
		byte[] sections = dev.mcresearch.protocolclient.world.ChunkTestSupport.sections(dev.mcresearch.protocolclient.world.ChunkTestSupport.OVERWORLD, ground);
		for (int cx = -1; cx <= 1; cx++) {
			for (int cz = -1; cz <= 1; cz++) {
				long[] hm = dev.mcresearch.protocolclient.world.TerrainTestSupport.chunk(dev.mcresearch.protocolclient.world.TerrainTestSupport.OVERWORLD,
					cx, cz, (x, z) -> 64).heightmaps().get(Clientbound.ChunkHeightmaps.MOTION_BLOCKING);
				s.handle(dev.mcresearch.protocolclient.world.ChunkTestSupport.decodeChunk(dev.mcresearch.protocolclient.world.ChunkTestSupport.chunkBody(
					cx, cz, java.util.Map.of(Clientbound.ChunkHeightmaps.MOTION_BLOCKING, hm), sections, List.of(),
					dev.mcresearch.protocolclient.world.ChunkTestSupport.Light.none())));
			}
		}
		assertEquals(9, s.world().loadedChunks());
		assertEquals("minecraft:stone", s.world().block(-5, 63, 7).orElseThrow().name());
		assertTrue(s.world().block(-5, 64, 7).orElseThrow().isAir());
		assertEquals(2, s.world().biomeCount());
		s.handle(new Clientbound.PlayerPosition(1, 0.5, 64, 0.5, 0, 0, 0, 0f, 0f, 0));
		assertTrue(s.movement().ready());
		s.movement().move(2, 0, 0);
		int before = this.channel.sent.size();
		for (int i = 0; i < 12; i++) {
			s.tick();
		}
		List<String> names = this.channel.sentNames().subList(before, this.channel.sent.size());
		int positionsSinceTickEnd = 0;
		for (String n : names) {
			if (n.startsWith("play/minecraft:move_player_pos")) {
				positionsSinceTickEnd++;
				assertTrue(positionsSinceTickEnd <= 1, "two position packets before client_tick_end: " + names);
			}
			if (n.equals("play/minecraft:client_tick_end")) {
				positionsSinceTickEnd = 0;
			}
		}
		assertEquals("play/minecraft:client_tick_end", names.getLast());
		assertTrue(names.stream().filter(n -> n.equals("play/minecraft:move_player_pos")).count() >= 9);
		assertTrue(s.player().x() > 2.4);
	}

	@Test
	void reconfigurationAcknowledgedAndTicksPause() throws Exception {
		ClientSession s = toPlay(session(true, 60));
		s.handle(new Clientbound.StartConfiguration());
		assertEquals(ProtocolState.CONFIGURATION, s.state());
		assertEquals("play/minecraft:configuration_acknowledged", this.channel.sentNames().getLast());
		int before = this.channel.sent.size();
		s.tick();
		assertEquals(before, this.channel.sent.size());
		s.handle(new Clientbound.FinishConfiguration());
		assertEquals(ProtocolState.PLAY, s.state());
	}

	@Test
	void packetsInWrongStateAreProtocolErrors() throws Exception {
		ClientSession s = toPlay(session(true, 60));
		assertThrows(ProtocolException.class, () -> s.handle(new Clientbound.LoginFinished(
			new GameProfile(UUID.randomUUID(), "ResearchBot", List.of()), UUID.randomUUID())));
		assertThrows(ProtocolException.class, () -> s.handle(new Clientbound.FinishConfiguration()));
		ClientSession fresh = new ClientSession(new FakeChannel(), SessionConfig.defaults());
		assertThrows(ProtocolException.class, () -> fresh.handle(new Clientbound.PlayerPosition(1, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
	}

	@Test
	void dimensionTypeMustExistInReceivedRegistry() throws Exception {
		ClientSession s = session(true, 60);
		s.start();
		s.handle(new Clientbound.LoginFinished(new GameProfile(UUID.randomUUID(), "ResearchBot", List.of()), UUID.randomUUID()));
		s.handle(new Clientbound.RegistryData("minecraft:dimension_type", List.of(new Clientbound.RegistryEntry("minecraft:overworld", false))));
		s.handle(new Clientbound.FinishConfiguration());
		assertThrows(ProtocolException.class, () -> s.handle(playLogin(3)));
	}

	@Test
	void onlineModeIsRejectedCleanly() throws Exception {
		ClientSession s = session(true, 60);
		s.start();
		ProtocolException e = assertThrows(ProtocolException.class, () -> s.handle(new Clientbound.EncryptionRequest("", new byte[162], new byte[4], true)));
		assertTrue(e.getMessage().contains("online-mode"));
	}

	@Test
	void compressionAndCookiesAndCustomQueryHandled() throws Exception {
		ClientSession s = session(true, 60);
		s.start();
		s.handle(new Clientbound.LoginCompression(256));
		assertEquals(256, this.channel.compressionThreshold);
		s.handle(new Clientbound.CustomQuery(9, "example:query", 0));
		assertEquals(9, this.channel.last(Serverbound.CustomQueryAnswer.class).transactionId());
		s.handle(new Clientbound.CookieRequest("example:cookie"));
		assertEquals(ProtocolState.LOGIN, this.channel.last(Serverbound.CookieResponse.class).state());
	}

	@Test
	void runEndsWithServerDisconnectReason() {
		this.channel.add(ProtocolState.LOGIN, "minecraft:login_disconnect", new PacketWriter().writeString("{\"text\":\"Server full\"}", 262144));
		ClientSession.Result r = session(true, 60).run();
		assertFalse(r.success());
		assertEquals("Server full", r.serverDisconnectReason());
		assertTrue(this.channel.closed);
	}

	@Test
	void runReportsUnexpectedEndAsFailure() {
		ClientSession.Result r = session(true, 60).run();
		assertFalse(r.success());
		assertInstanceOf(java.io.EOFException.class, r.error());
	}

	@Test
	void oversizedBundleRejected() {
		UUID id = GameProfile.offlineUuid("ResearchBot");
		this.channel.add(ProtocolState.LOGIN, "minecraft:login_finished",
			new PacketWriter().writeUuid(id).writeString("ResearchBot", 16).writeVarInt(0).writeUuid(UUID.randomUUID()));
		this.channel.add(ProtocolState.CONFIGURATION, "minecraft:finish_configuration");
		this.channel.add(ProtocolState.PLAY, "minecraft:bundle_delimiter");
		for (int i = 0; i <= ClientSession.BUNDLE_SIZE_LIMIT; i++) {
			this.channel.add(ProtocolState.PLAY, "minecraft:award_stats", new PacketWriter().writeLong(0));
		}
		ClientSession.Result r = session(true, 60).run();
		assertFalse(r.success());
		assertInstanceOf(ProtocolException.class, r.error());
		assertTrue(r.error().getMessage().contains("Bundle exceeds"));
	}

	/**
	 * Regression M5 (docs/23 §4): ein im vorigen Lauf gestorbener Spieler meldet sich mit Lebenspunkten 0 an; der Server
	 * schickt dann keine Chunks, bis der Client {@code client_command PERFORM_RESPAWN} sendet. Danach ist nach der neuen
	 * Spawn-Position erneut {@code player_loaded} fällig.
	 */
	@Test
	void deadPlayerRequestsRespawnOnceAndReportsLoadedAgain() throws Exception {
		ClientSession s = toPlay(session(true, 60));
		s.handle(new Clientbound.PlayerPosition(1, 0.5, 64, 0.5, 0, 0, 0, 0f, 0f, 0));
		assertEquals(1, this.channel.sentNames().stream().filter(n -> n.equals("play/minecraft:player_loaded")).count());
		s.handle(new Clientbound.SetHealth(0.0F, 20, 5.0F));
		s.handle(new Clientbound.SetHealth(0.0F, 20, 5.0F)); // wiederholte Meldung: nur ein Wiederbelebungsantrag
		assertEquals(1, this.channel.sentNames().stream().filter(n -> n.equals("play/minecraft:client_command")).count());
		assertEquals(1, s.stats().respawnsRequested());
		s.handle(new Clientbound.Respawn(0, "minecraft:overworld", 0, 0, 0L));
		s.handle(new Clientbound.SetHealth(20.0F, 20, 5.0F));
		s.handle(new Clientbound.PlayerPosition(2, 8.5, 70, -3.5, 0, 0, 0, 0f, 0f, 0));
		assertEquals(2, this.channel.sentNames().stream().filter(n -> n.equals("play/minecraft:player_loaded")).count(),
			"player_loaded again after respawn (ClientPacketListener#handleRespawn sets clientLoaded = false)");
		assertEquals(20.0F, s.health());
		// ein erneuter Tod nach der Wiederbelebung löst wieder genau einen Antrag aus
		s.handle(new Clientbound.SetHealth(0.0F, 20, 5.0F));
		assertEquals(2, this.channel.sentNames().stream().filter(n -> n.equals("play/minecraft:client_command")).count());
	}

	@Test
	void healthAndClientCommandCodecs() throws Exception {
		byte[] body = new dev.mcresearch.protocolclient.protocol.codec.PacketWriter().writeFloat(0.0F).writeVarInt(17).writeFloat(2.5F).toByteArray();
		dev.mcresearch.protocolclient.protocol.RawPacket raw = new dev.mcresearch.protocolclient.protocol.RawPacket(ProtocolState.PLAY,
			dev.mcresearch.protocolclient.protocol.PacketRegistry.get().idOf(ProtocolState.PLAY,
				dev.mcresearch.protocolclient.protocol.PacketDirection.CLIENTBOUND, "minecraft:set_health"), "minecraft:set_health", body, body.length + 1);
		assertEquals(new Clientbound.SetHealth(0.0F, 17, 2.5F), dev.mcresearch.protocolclient.protocol.packet.ClientboundDecoder.decode(raw));
		byte[] nan = new dev.mcresearch.protocolclient.protocol.codec.PacketWriter().writeFloat(Float.NaN).writeVarInt(0).writeFloat(0).toByteArray();
		dev.mcresearch.protocolclient.protocol.RawPacket bad = new dev.mcresearch.protocolclient.protocol.RawPacket(ProtocolState.PLAY, raw.id(),
			"minecraft:set_health", nan, nan.length + 1);
		org.junit.jupiter.api.Assertions.assertThrows(dev.mcresearch.protocolclient.protocol.ProtocolException.class,
			() -> dev.mcresearch.protocolclient.protocol.packet.ClientboundDecoder.decode(bad));
		dev.mcresearch.protocolclient.protocol.codec.PacketWriter w = new dev.mcresearch.protocolclient.protocol.codec.PacketWriter();
		new Serverbound.ClientCommand(Serverbound.ClientCommand.PERFORM_RESPAWN).write(w);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{0}, w.toByteArray());
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new Serverbound.ClientCommand(2));
	}
}
