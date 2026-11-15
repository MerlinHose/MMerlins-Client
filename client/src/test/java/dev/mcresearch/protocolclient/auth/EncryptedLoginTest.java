package dev.mcresearch.protocolclient.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.net.Connection;
import dev.mcresearch.protocolclient.net.LoginCrypto;
import dev.mcresearch.protocolclient.net.PacketLog;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.RawPacket;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import dev.mcresearch.protocolclient.protocol.packet.ClientboundDecoder;
import dev.mcresearch.protocolclient.protocol.packet.Serverbound;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.SessionConfig;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * M13 Phase G: verschlüsselter Login gegen die lokale Protokoll-Testkomponente {@link SimulatedLoginServer}.
 * Alle Identitäten und Tokens sind Testdaten; es findet keine externe Verbindung statt.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class EncryptedLoginTest {
	private static final UUID TEST_UUID = UUID.fromString("00000000-0000-4000-8000-000000000013");

	/** Test-Identität: merkt sich den Hash wie ein Sessionserver (join), {@code hasJoined} prüft ihn. */
	static final class FakeSession implements LoginIdentity {
		final Map<String, String> joined = new ConcurrentHashMap<>();
		final AtomicInteger joins = new AtomicInteger();
		volatile AuthException failWith;

		@Override
		public String name() {
			return "M13Sim";
		}

		@Override
		public UUID profileId() {
			return TEST_UUID;
		}

		@Override
		public String kind() {
			return "simulation";
		}

		@Override
		public boolean canAuthenticate() {
			return true;
		}

		@Override
		public void joinServer(final String serverHash) throws AuthException {
			this.joins.incrementAndGet();
			if (this.failWith != null) {
				throw this.failWith;
			}
			this.joined.put(name(), serverHash);
		}

		UUID hasJoined(final String name, final String hash) {
			return hash.equals(this.joined.get(name)) ? TEST_UUID : null;
		}
	}

	private static SessionConfig config(final int port, final Duration readTimeout) {
		return new SessionConfig("127.0.0.1", port, "M13Sim", 2, Duration.ofSeconds(5), false, "mcresearch-m13-test", 9f, Duration.ofSeconds(5),
			readTimeout, null);
	}

	private static ClientSession.Result runSession(final SimulatedLoginServer server, final LoginIdentity id, final Duration readTimeout,
		final List<ClientSession> out) throws Exception {
		Connection c = Connection.open(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), server.port(), 5000, (int) readTimeout.toMillis(),
			PacketLog.disabled());
		ClientSession s = new ClientSession(c, config(server.port(), readTimeout), id);
		out.add(s);
		ClientSession.Result r = s.run();
		server.done.get(20, TimeUnit.SECONDS);
		return r;
	}

	@Test
	void encryptedLoginWithSessionJoinAndCompression() throws Exception {
		for (int threshold : new int[]{-1, 0, 256}) {
			FakeSession id = new FakeSession();
			try (SimulatedLoginServer server = new SimulatedLoginServer(SimulatedLoginServer.Options.normal(threshold), id::hasJoined)) {
				List<ClientSession> s = new ArrayList<>();
				ClientSession.Result r = runSession(server, id, Duration.ofSeconds(10), s);
				assertNull(server.observed.failure, "server: " + server.observed.failure);
				assertEquals("M13Sim", server.observed.helloName);
				assertEquals(TEST_UUID, server.observed.helloUuid);
				assertTrue(server.observed.challengeValid, "challenge");
				assertEquals(16, server.observed.secretLength, "128-bit AES key");
				assertTrue(server.observed.sessionAccepted, "hasJoined accepted the client's hash");
				assertEquals(1, id.joins.get());
				assertEquals(server.observed.serverHash, id.joined.get("M13Sim"), "client and server compute the same session hash");
				assertEquals(List.of("login:3", "configuration:2", "configuration:0"), server.observed.afterEncryption,
					"login_acknowledged, brand, client_information decrypted correctly (threshold " + threshold + ")");
				assertEquals("Disconnected by server: M13 simulated login complete", r.outcome());
				assertTrue(s.get(0).encrypted());
				assertEquals(TEST_UUID, s.get(0).loginProfile().id());
				String expectedTrace = "hello(auth) join key encrypted " + (threshold >= 0 ? "compression(" + threshold + ") " : "") + "finished";
				assertEquals(expectedTrace, s.get(0).loginTrace());
			}
		}
	}

	@Test
	void largeAndManyEncryptedPacketsAcrossFragmentedTcp() throws Exception {
		Random rnd = new Random(13);
		List<byte[]> payloads = new ArrayList<>();
		byte[] big = new byte[1_000_000]; // nahe der Vanilla-Grenze für custom_payload (1 MiB)
		rnd.nextBytes(big);
		payloads.add(big);
		byte[] compressible = new byte[900_000];
		for (int i = 0; i < compressible.length; i++) {
			compressible[i] = (byte) (i % 7);
		}
		payloads.add(compressible);
		for (int i = 0; i < 200; i++) {
			byte[] p = new byte[rnd.nextInt(600)];
			rnd.nextBytes(p);
			payloads.add(p);
		}
		for (int threshold : new int[]{-1, 256}) {
			for (int fragment : new int[]{0, 1, 7, 1500}) {
				if (fragment == 1 && threshold < 0) {
					continue; // 1-Byte-Fragmente einmal genügen (Laufzeit)
				}
				SimulatedLoginServer.Options o = SimulatedLoginServer.Options.normal(threshold).noAuth().fragmented(fragment)
					.payloads(fragment == 1 ? payloads.subList(1, 60) : payloads);
				try (SimulatedLoginServer server = new SimulatedLoginServer(o, (n, h) -> null)) {
					verifyPayloadsOnConnection(server, o.payloads(), threshold);
					server.done.get(20, TimeUnit.SECONDS);
					assertNull(server.observed.failure, "server: " + server.observed.failure);
				}
			}
		}
	}

	/** Client auf Connection-Ebene (ohne ClientSession), damit Paketinhalte bytegenau verglichen werden können. */
	private static void verifyPayloadsOnConnection(final SimulatedLoginServer server, final List<byte[]> payloads, final int threshold) throws Exception {
		try (Connection c = Connection.open(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), server.port(), 5000, 15000, PacketLog.disabled())) {
			c.send(new Serverbound.Intention("127.0.0.1", server.port(), Serverbound.Intention.LOGIN));
			c.setOutboundState(ProtocolState.LOGIN);
			c.setInboundState(ProtocolState.LOGIN);
			c.send(new Serverbound.Hello("M13Sim", TEST_UUID));
			Clientbound.EncryptionRequest req = (Clientbound.EncryptionRequest) ClientboundDecoder.decode(c.receive());
			assertFalse(req.shouldAuthenticate());
			assertArrayEquals(server.publicKey(), req.publicKey());
			SecretKey secret = LoginCrypto.newSharedSecret();
			var key = LoginCrypto.serverPublicKey(req.publicKey());
			c.send(new Serverbound.Key(LoginCrypto.rsaEncrypt(key, secret.getEncoded()), LoginCrypto.rsaEncrypt(key, req.challenge())));
			c.enableEncryption(secret);
			assertTrue(c.encrypted());
			if (threshold >= 0) {
				Clientbound.LoginCompression lc = (Clientbound.LoginCompression) ClientboundDecoder.decode(c.receive());
				c.setCompressionThreshold(lc.threshold());
			}
			assertInstanceOf(Clientbound.LoginFinished.class, ClientboundDecoder.decode(c.receive()));
			c.setInboundState(ProtocolState.CONFIGURATION);
			c.send(new Serverbound.LoginAcknowledged());
			c.setOutboundState(ProtocolState.CONFIGURATION);
			c.send(new Serverbound.Brand("m13"));
			c.send(Serverbound.ClientInformation.defaults(2));
			for (byte[] expected : payloads) {
				RawPacket raw = c.receive();
				assertEquals("minecraft:custom_payload", raw.name());
				PacketReader r = new PacketReader(raw.body());
				assertEquals("mcresearch:m13_test", r.readIdentifier());
				assertArrayEquals(expected, r.readRemaining(1 << 21), "payload of " + expected.length + " bytes");
			}
			assertInstanceOf(Clientbound.Disconnect.class, ClientboundDecoder.decode(c.receive()));
		}
	}

	@Test
	void noAuthenticationRequestedStillEncryptsWithoutJoin() throws Exception {
		FakeSession id = new FakeSession();
		try (SimulatedLoginServer server = new SimulatedLoginServer(SimulatedLoginServer.Options.normal(256).noAuth(), id::hasJoined)) {
			List<ClientSession> s = new ArrayList<>();
			ClientSession.Result r = runSession(server, id, Duration.ofSeconds(10), s);
			assertEquals(0, id.joins.get(), "no session join when shouldAuthenticate=false (Vanilla)");
			assertTrue(s.get(0).encrypted());
			assertEquals("Disconnected by server: M13 simulated login complete", r.outcome());
		}
	}

	@Test
	void offlineIdentityRefusesAuthenticatedServer() throws Exception {
		try (SimulatedLoginServer server = new SimulatedLoginServer(SimulatedLoginServer.Options.normal(-1).with(SimulatedLoginServer.Mode.CLOSE_AFTER_HELLO),
			(n, h) -> null)) {
			List<ClientSession> s = new ArrayList<>();
			ClientSession.Result r = runSession(server, new OfflineIdentity("M13Sim"), Duration.ofSeconds(10), s);
			assertFalse(r.success());
			assertInstanceOf(ProtocolException.class, r.error());
			assertTrue(r.error().getMessage().contains("offline identity"));
			assertFalse(s.get(0).encrypted());
		}
	}

	@Test
	void sessionServerRejectionStopsBeforeKeyIsSent() throws Exception {
		FakeSession id = new FakeSession();
		id.failWith = new AuthException(AuthError.INVALID_SESSION, "simulated 403 ForbiddenOperationException");
		try (SimulatedLoginServer server = new SimulatedLoginServer(SimulatedLoginServer.Options.normal(-1), id::hasJoined)) {
			List<ClientSession> s = new ArrayList<>();
			ClientSession.Result r = runSession(server, id, Duration.ofSeconds(10), s);
			assertFalse(r.success());
			AuthException e = assertInstanceOf(AuthException.class, r.error());
			assertEquals(AuthError.INVALID_SESSION, e.error());
			assertFalse(s.get(0).encrypted(), "no key packet, no encryption after a failed join (Vanilla disconnects)");
			assertNotNull(server.observed.failure, "server saw the connection close instead of a key packet");
		}
	}

	@Test
	void serverRejectsHashThatWasNeverJoined() throws Exception {
		FakeSession id = new FakeSession();
		try (SimulatedLoginServer server = new SimulatedLoginServer(SimulatedLoginServer.Options.normal(-1), (n, h) -> null)) {
			List<ClientSession> s = new ArrayList<>();
			ClientSession.Result r = runSession(server, id, Duration.ofSeconds(10), s);
			assertFalse(server.observed.sessionAccepted);
			assertTrue(r.outcome().contains("multiplayer.disconnect.unverified_username"), r.outcome());
		}
	}

	@Test
	void invalidServerKeyIsAProtocolError() throws Exception {
		try (SimulatedLoginServer server = new SimulatedLoginServer(SimulatedLoginServer.Options.normal(-1).with(SimulatedLoginServer.Mode.BAD_PUBLIC_KEY),
			(n, h) -> null)) {
			FakeSession id = new FakeSession();
			ClientSession.Result r = runSession(server, id, Duration.ofSeconds(10), new ArrayList<>());
			assertInstanceOf(ProtocolException.class, r.error());
			assertTrue(r.error().getMessage().startsWith("Invalid server key"));
			assertEquals(0, id.joins.get(), "no join with an unusable key");
		}
	}

	@Test
	void abortedHandshakeAfterHello() throws Exception {
		try (SimulatedLoginServer server = new SimulatedLoginServer(
			SimulatedLoginServer.Options.normal(-1).noAuth().with(SimulatedLoginServer.Mode.TRUNCATED_AFTER_KEY), (n, h) -> null)) {
			ClientSession.Result r = runSession(server, new FakeSession(), Duration.ofSeconds(10), new ArrayList<>());
			assertFalse(r.success());
			assertInstanceOf(java.io.EOFException.class, r.error(), String.valueOf(r.error()));
		}
	}

	@Test
	void invalidEncryptedFrameLength() throws Exception {
		try (SimulatedLoginServer server = new SimulatedLoginServer(
			SimulatedLoginServer.Options.normal(-1).noAuth().with(SimulatedLoginServer.Mode.BAD_LENGTH_AFTER_KEY), (n, h) -> null)) {
			ClientSession.Result r = runSession(server, new FakeSession(), Duration.ofSeconds(10), new ArrayList<>());
			assertInstanceOf(ProtocolException.class, r.error(), String.valueOf(r.error()));
		}
	}

	@Test
	void silentServerAfterKeyTimesOut() throws Exception {
		try (SimulatedLoginServer server = new SimulatedLoginServer(
			SimulatedLoginServer.Options.normal(-1).noAuth().with(SimulatedLoginServer.Mode.SILENT_AFTER_KEY), (n, h) -> null)) {
			ClientSession.Result r = runSession(server, new FakeSession(), Duration.ofSeconds(1), new ArrayList<>());
			assertFalse(r.success());
			assertInstanceOf(SocketTimeoutException.class, r.error().getCause(), String.valueOf(r.error()));
		}
	}
}
