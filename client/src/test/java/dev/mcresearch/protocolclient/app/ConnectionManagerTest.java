package dev.mcresearch.protocolclient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.auth.OfflineIdentity;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** M15 Phase G/H: Verbindungsphasen, Abbruch, Fehlermeldungen, Versionsprüfung, Adresssperre (docs/61 §4–§6). */
class ConnectionManagerTest {
	private static final OfflineIdentity ID = new OfflineIdentity("Tester");
	private static final ServerStatus ONLINE = new ServerStatus(ServerStatus.State.ONLINE, 2, "26.3", 777, 0, 20, "Test", "");

	private static void await(final ConnectionManager cm, final ConnectionManager.Phase p) throws InterruptedException {
		for (int i = 0; i < 200 && cm.phase() != p; i++) {
			Thread.sleep(10);
		}
		assertEquals(p, cm.phase());
	}

	@Test
	void unreachableServerGivesUnderstandableError() throws Exception {
		ConnectionManager cm = new ConnectionManager((t, id, vd) -> {
			throw new ConnectException("Connection refused: connect");
		}, t -> ONLINE);
		cm.connect(ServerEntry.localTestServer(), ID, 2, true);
		await(cm, ConnectionManager.Phase.FAILED);
		assertEquals(ConnectErrors.Kind.UNREACHABLE, cm.error().kind());
		assertEquals("Server nicht erreichbar", cm.error().title());
		assertTrue(cm.awaitIdle(2000));
		assertFalse(cm.busy());
		cm.release();
		assertEquals(ConnectionManager.Phase.FAILED, cm.phase(), "end state stays readable after release");
		cm.reset();
		assertEquals(ConnectionManager.Phase.IDLE, cm.phase());
		assertNull(cm.error());
	}

	@Test
	void olderServerVersionIsReportedForMvs() throws Exception {
		ConnectionManager cm = new ConnectionManager((t, id, vd) -> {
			throw new AssertionError("must not connect");
		}, t -> new ServerStatus(ServerStatus.State.ONLINE, 1, "1.20.4", 765, 0, 20, "", ""));
		cm.connect(ServerEntry.localTestServer(), ID, 2, true);
		await(cm, ConnectionManager.Phase.FAILED);
		assertEquals(ConnectErrors.Kind.UNSUPPORTED_VERSION, cm.error().kind());
		assertTrue(cm.error().detail().contains("1.20.4"), cm.error().detail());
		assertTrue(cm.error().detail().contains("M18"), cm.error().detail());
	}

	@Test
	void silentServiceTimesOut() throws Exception {
		ConnectionManager cm = new ConnectionManager((t, id, vd) -> null, t -> ServerStatus.offline("Zeitüberschreitung"));
		cm.connect(ServerEntry.localTestServer(), ID, 2, true);
		await(cm, ConnectionManager.Phase.FAILED);
		assertEquals(ConnectErrors.Kind.TIMEOUT, cm.error().kind());
	}

	@Test
	void externalAddressIsRefusedWithoutNetworkAccess() throws Exception {
		AtomicInteger checks = new AtomicInteger();
		ConnectionManager cm = new ConnectionManager((t, id, vd) -> {
			throw new AssertionError("must not connect");
		}, t -> {
			checks.incrementAndGet();
			return ONLINE;
		});
		cm.connect(new ServerEntry("Extern", "play.example.org", 25565, "", 0), ID, 2, true);
		assertEquals(ConnectionManager.Phase.FAILED, cm.phase());
		assertEquals(ConnectErrors.Kind.NOT_ALLOWED, cm.error().kind());
		assertEquals(0, checks.get(), "not even a status ping");
	}

	@Test
	void cancelDuringCheckAndStaleWorkerCannotDisturbNextAttempt() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		ConnectionManager cm = new ConnectionManager((t, id, vd) -> {
			throw new ConnectException("refused");
		}, t -> {
			if (calls.incrementAndGet() == 1) {
				try {
					release.await(5, TimeUnit.SECONDS); // erster Versuch hängt in der Statusprüfung
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return ONLINE;
			}
			return ServerStatus.offline("x");
		});
		cm.connect(ServerEntry.localTestServer(), ID, 2, true);
		assertEquals(ConnectionManager.Phase.CHECKING, cm.phase());
		assertThrows(IllegalStateException.class, () -> cm.connect(ServerEntry.localTestServer(), ID, 2, true), "no parallel attempts");
		cm.cancel();
		assertEquals(ConnectionManager.Phase.CANCELLED, cm.phase());
		cm.connect(ServerEntry.localTestServer(), ID, 2, true);
		await(cm, ConnectionManager.Phase.FAILED);
		release.countDown(); // alter Versuch kehrt zurück – darf nichts mehr verändern
		Thread.sleep(100);
		assertEquals(ConnectionManager.Phase.FAILED, cm.phase());
		assertEquals(ConnectErrors.Kind.UNREACHABLE, cm.error().kind());
		assertEquals(2, cm.connects());
	}

	@Test
	void errorMessagesNeverLeakTokensAndClassifyServerReasons() {
		ConnectErrors.UserError e = ConnectErrors.describe(null, "multiplayer.disconnect.outdated_client");
		assertEquals(ConnectErrors.Kind.UNSUPPORTED_VERSION, e.kind());
		e = ConnectErrors.describe(null, "multiplayer.disconnect.not_whitelisted");
		assertEquals(ConnectErrors.Kind.REJECTED, e.kind());
		e = ConnectErrors.describe(null, "Kicked: access_token=eyJhbGciOiJIUzI1NiJ9.abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG secret=hunter2");
		assertFalse(e.detail().contains("eyJhbGci"), e.detail());
		assertFalse(e.detail().contains("hunter2"), e.detail());
		assertEquals(ConnectErrors.Kind.TIMEOUT, ConnectErrors.describe(new SocketTimeoutException("Read timed out"), null).kind());
		assertEquals(ConnectErrors.Kind.INTERRUPTED, ConnectErrors.describe(new EOFException(), null).kind());
		assertEquals(ConnectErrors.Kind.NOT_ALLOWED, ConnectErrors.describe(new IllegalArgumentException("Refusing non-loopback address 1.2.3.4"), null).kind());
		assertEquals("Verbindung unterbrochen", ConnectErrors.describe(null, null).title());
	}

	@Test
	void serverEntryParsingAndLoopbackRule() {
		assertTrue(ServerEntry.parse("a", "127.0.0.1").allowed());
		assertEquals(25566, ServerEntry.parse("a", "localhost:25566").port());
		assertTrue(ServerEntry.parse("a", "[::1]:25565").allowed());
		assertFalse(ServerEntry.parse("a", "example.org").allowed(), "hostnames need DNS → refused");
		assertFalse(ServerEntry.parse("a", "192.168.0.10").allowed());
		assertThrows(IllegalArgumentException.class, () -> ServerEntry.parse("a", "127.0.0.1:70000"));
		assertThrows(IllegalArgumentException.class, () -> ServerEntry.parse("", "127.0.0.1"));
		assertEquals(ProtocolVersions.Support.NATIVE, ProtocolVersions.support(777));
		assertEquals(ProtocolVersions.Support.PLANNED_MVS, ProtocolVersions.support(764));
		assertEquals(ProtocolVersions.Support.TOO_OLD, ProtocolVersions.support(763));
		assertEquals(ProtocolVersions.Support.TOO_NEW, ProtocolVersions.support(778));
		assertEquals("1.21.4", ProtocolVersions.knownName(769));
		assertFalse(MerlinVersion.CLIENT_VERSION.equals(String.valueOf(MerlinVersion.PROTOCOL)), "own version id");
	}

	@Test
	void serverListPersistsVersionsIndependentlyOfClient() throws Exception {
		java.nio.file.Path f = java.nio.file.Files.createTempFile("servers", ".properties");
		try {
			ServerList l = ServerList.load(f);
			l.add(new ServerEntry("Lokal = #1", "127.0.0.1", 25565, "", 0));
			l.add(new ServerEntry("Alt", "127.0.0.1", 25570, "", 0));
			l.rememberVersion(1, "1.21.1", 767);
			assertNull(l.save());
			ServerList r = ServerList.load(f);
			assertEquals(2, r.size());
			assertEquals("Lokal = #1", r.entries().get(0).name());
			assertEquals(767, r.entries().get(1).lastProtocol());
			assertEquals(ProtocolVersions.Support.PLANNED_MVS, r.entries().get(1).lastSupport());
			java.nio.file.Files.writeString(f, "server.0.name=Kaputt\nserver.0.port=abc\nserver.1.name=Gut\nserver.1.host=localhost\n");
			ServerList b = ServerList.load(f);
			assertEquals(1, b.size());
			assertEquals(1, b.notes().size());
		} finally {
			java.nio.file.Files.deleteIfExists(f);
		}
	}
}
