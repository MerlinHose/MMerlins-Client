package dev.mcresearch.protocolclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcresearch.protocolclient.net.Connection;
import dev.mcresearch.protocolclient.net.LoopbackGuard;
import dev.mcresearch.protocolclient.net.PacketLog;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.SessionConfig;
import dev.mcresearch.protocolclient.session.StatusQuery;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integrationstest gegen den lokalen Vanilla-26.3-Server (server/, siehe docs/14).
 * Aktivierung: {@code ./gradlew test -PintegrationTest}. Dauer ca. 70 s.
 */
@Tag("integration")
class LocalServerIntegrationTest {
	private static final int PORT = 25565;
	private static InetAddress address;

	@BeforeAll
	static void requireLocalServer() throws Exception {
		address = LoopbackGuard.resolveLoopback("127.0.0.1");
		boolean reachable;
		try (Socket s = new Socket()) {
			s.connect(new java.net.InetSocketAddress(address, PORT), 1000);
			reachable = true;
		} catch (java.io.IOException e) {
			reachable = false;
		}
		assumeTrue(reachable, "Local server on 127.0.0.1:25565 not running");
	}

	@Test
	void statusQuery() throws Exception {
		try (PacketLog log = PacketLog.disabled()) {
			StatusQuery.Result r = StatusQuery.query(Connection.open(address, PORT, 2000, 10000, log), "127.0.0.1", PORT);
			assertTrue(r.protocolMatches(), "protocol " + r.status().protocol());
			assertEquals("26.3", r.status().versionName());
		}
	}

	/** M2: Bewegung und Rotation werden vom Server übernommen (serverseitig geprüft, wenn -PserverDir gesetzt ist). */
	@Test
	void movementAndRotationAcceptedByServer() throws Exception {
		SessionConfig c = new SessionConfig("127.0.0.1", PORT, "ProtoBotMove", 2, Duration.ZERO, false, "mcresearch-protocol-client-it",
			9f, Duration.ofSeconds(5), SessionConfig.DEFAULT_READ_TIMEOUT, Path.of("build", "integration-move-packets.log"));
		try (PacketLog log = PacketLog.open(c.packetLog())) {
			ClientSession session = new ClientSession(Connection.open(address, PORT, 5000, 30000, log), c);
			java.util.concurrent.CompletableFuture<ClientSession.Result> result = java.util.concurrent.CompletableFuture.supplyAsync(session::run);
			for (int i = 0; i < 200 && !session.movement().ready(); i++) {
				Thread.sleep(50);
			}
			assertTrue(session.movement().ready(), "not ready for movement");
			java.util.Optional<ServerConsole> console = ServerConsole.fromSystemProperty();
			double startX = session.player().x();
			MovementResultHolder first = new MovementResultHolder(session.movement().move(-2, 0, 0).get(30, java.util.concurrent.TimeUnit.SECONDS));
			MovementResultHolder second = new MovementResultHolder(session.movement().move(0, 0, 1.5).get(30, java.util.concurrent.TimeUnit.SECONDS));
			// seit M5 dreht move den Spieler zum Ziel; die Rotation wird deshalb danach gesetzt und geprüft
			session.movement().look(90f, 15f);
			Thread.sleep(500);
			assertTrue(!first.r.correctedByServer() && !second.r.correctedByServer(), first.r + " / " + second.r);
			assertTrue(Math.abs(session.player().x() - startX) > 0.5 || first.r.outcome().startsWith("blocked"), "player did not move: " + first.r);
			if (console.isPresent()) {
				double[] pos = console.get().dataGet("ProtoBotMove", "Pos");
				double[] rot = console.get().dataGet("ProtoBotMove", "Rotation");
				assertEquals(session.player().x(), pos[0], 1e-6, "server X");
				assertEquals(session.player().y(), pos[1], 1e-6, "server Y");
				assertEquals(session.player().z(), pos[2], 1e-6, "server Z");
				assertEquals(90.0, rot[0], 1e-4, "server yaw");
				assertEquals(15.0, rot[1], 1e-4, "server pitch");
			}
			assertEquals(0, session.movement().corrections());
			assertCompression(session.transportStats());
			session.requestStop("integration test done");
			ClientSession.Result r = result.get(30, java.util.concurrent.TimeUnit.SECONDS);
			assertNull(r.error(), () -> "error: " + r.error());
			assertNull(r.serverDisconnectReason());
		}
	}

	/** Keep-Alive: alle 15 s, keine Lücke > 16 s (Timeout wäre 15 s nach der Anfrage). */
	private static void assertKeepAlivesWithoutGap(final ClientSession.Result r) {
		assertTrue(r.stats().keepAlivesAnswered() >= 4, "keep-alives " + r.stats().keepAlivesAnswered());
		assertTrue(r.stats().maxKeepAliveGapMillis() <= 16_000, "max keep-alive gap " + r.stats().maxKeepAliveGapMillis());
	}

	/**
	 * Erwartete Kompression (-PexpectedThreshold=N, docs/16 §6). Vor der Aktivierung: C→S intention + hello, S→C login_compression
	 * (unkomprimiert). Bei Threshold 0 ist danach jeder Frame komprimiert, bei −1 keiner.
	 */
	private static void assertCompression(final dev.mcresearch.protocolclient.net.TransportStats t) {
		String expected = System.getProperty("mcclient.expectedThreshold", "");
		if (expected.isBlank()) {
			return;
		}
		int threshold = Integer.parseInt(expected);
		assertEquals(threshold, t.threshold(), t.toString());
		if (threshold < 0) {
			assertEquals(0, t.compressedIn(), t.toString());
			assertEquals(0, t.compressedOut(), t.toString());
		} else if (threshold == 0) {
			assertEquals(1, t.uncompressedIn(), "only login_compression uncompressed: " + t);
			assertEquals(2, t.uncompressedOut(), "only intention + hello uncompressed: " + t);
		} else {
			assertTrue(t.compressedIn() > 0 && t.uncompressedIn() > 1, t.toString());
			assertTrue(t.wireBytesIn() < t.packetBytesIn(), "compression must reduce wire bytes: " + t);
		}
	}

	private record MovementResultHolder(dev.mcresearch.protocolclient.session.MovementController.MoveResult r) {
	}

	@Test
	void stableSixtySecondsInPlayAndControlledDisconnect() throws Exception {
		SessionConfig c = new SessionConfig("127.0.0.1", PORT, "ProtoBotIT", 2, Duration.ofSeconds(71), true, "mcresearch-protocol-client-it",
			9f, Duration.ofSeconds(5), SessionConfig.DEFAULT_READ_TIMEOUT, Path.of("build", "integration-packets.log"));
		try (PacketLog log = PacketLog.open(c.packetLog())) {
			ClientSession.Result r = new ClientSession(Connection.open(address, PORT, 5000, 30000, log), c).run();
			assertNull(r.error(), () -> "error: " + r.error());
			assertNull(r.serverDisconnectReason());
			assertTrue(r.reachedPlay());
			assertTrue(r.success(), r.outcome());
			assertTrue(r.timeInPlay().toSeconds() >= 70, "time in play " + r.timeInPlay());
			assertTrue(r.stats().keepAlivesAnswered() >= 3, "keep-alives " + r.stats().keepAlivesAnswered());
			assertTrue(r.stats().teleportsAccepted() >= 1);
			assertTrue(r.stats().chunkBatchesAcknowledged() >= 1);
			assertEquals(1, r.stats().sentCount("minecraft:player_loaded"));
			assertTrue(r.stats().ticksSent() >= 1300, "ticks " + r.stats().ticksSent());
			assertKeepAlivesWithoutGap(r);
			assertCompression(r.transport());
		}
	}
}
