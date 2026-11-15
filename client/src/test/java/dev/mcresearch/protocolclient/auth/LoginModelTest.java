package dev.mcresearch.protocolclient.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.auth.http.EndpointPolicy;
import dev.mcresearch.protocolclient.auth.minecraft.LoginFactory;
import dev.mcresearch.protocolclient.auth.minecraft.OnlineIdentity;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** M13 Phase I/J: Zustandsmodell der Anmeldeoberfläche ohne Fenster. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class LoginModelTest {
	private static OnlineIdentity testIdentity() {
		return OnlineIdentity.simulation("TestPlayer_13", UUID.fromString("00000000-0000-4000-8000-000000000013"), URI.create("http://127.0.0.1:1/d"),
			new FakeHttp(EndpointPolicy.localSimulation()));
	}

	private static void assertNoToken(final List<LoginModel.Snapshot> shots) {
		for (LoginModel.Snapshot s : shots) {
			assertFalse(s.toString().contains("TEST-ONLY"), "token visible in UI: " + s);
			assertFalse(s.toString().contains("SECRET"), "token visible in UI: " + s);
		}
	}

	@Test
	void offlineModeKeepsWorkingWithoutNetwork() {
		LoginModel m = new LoginModel();
		List<LoginModel.Snapshot> shots = new CopyOnWriteArrayList<>();
		m.addListener(shots::add);
		assertEquals(LoginModel.State.SIGNED_OUT, m.snapshot().state());
		assertFalse(m.snapshot().canConnect());
		m.useOffline("x"); // ungültiger Name
		assertEquals(LoginModel.State.ERROR, m.snapshot().state());
		assertFalse(m.snapshot().error().isEmpty());
		m.useOffline("ProtoBotGfx");
		assertTrue(m.snapshot().canConnect());
		assertFalse(m.snapshot().canLogout(), "nothing to log out in offline mode");
		assertEquals("offline", m.identity().kind());
		assertEquals("ProtoBotGfx", m.snapshot().playerName());
	}

	@Test
	void signInShowsNameAndLogoutDiscardsToken() throws Exception {
		LoginModel m = new LoginModel();
		List<LoginModel.Snapshot> shots = new CopyOnWriteArrayList<>();
		m.addListener(shots::add);
		AtomicInteger logouts = new AtomicInteger();
		m.onLogout(logouts::incrementAndGet);
		OnlineIdentity id = testIdentity();
		m.signIn("microsoft", (status, cancel) -> {
			status.accept("Xbox Live");
			return id;
		});
		m.awaitIdle(5000);
		LoginModel.Snapshot s = m.snapshot();
		assertEquals(LoginModel.State.SIGNED_IN, s.state());
		assertEquals("TestPlayer_13", s.playerName());
		assertTrue(s.canConnect() && s.canLogout());
		assertTrue(shots.stream().anyMatch(x -> x.status().equals("Xbox Live")), "progress shown");
		m.logout();
		assertFalse(id.canAuthenticate(), "token discarded on logout");
		assertEquals(1, logouts.get());
		assertNull(m.identity());
		assertEquals(LoginModel.State.SIGNED_OUT, m.snapshot().state());
		// erneute Anmeldung nach Abmelden
		OnlineIdentity id2 = testIdentity();
		m.signIn("microsoft", (status, cancel) -> id2);
		m.awaitIdle(5000);
		assertEquals(id2, m.identity());
		assertNoToken(shots);
	}

	@Test
	void cancelRunningLoginAndNoParallelLogins() throws Exception {
		LoginModel m = new LoginModel();
		CountDownLatch started = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		LoginModel.Authenticator blocking = (status, cancel) -> {
			calls.incrementAndGet();
			started.countDown();
			while (!cancel.cancelled()) {
				Thread.onSpinWait();
			}
			throw new AuthException(AuthError.USER_CANCELLED, "");
		};
		m.signIn("microsoft", blocking);
		started.await();
		assertTrue(m.snapshot().canCancel());
		assertFalse(m.snapshot().canSignIn());
		m.signIn("microsoft", blocking); // Mehrfach-Login wird ignoriert
		m.useOffline("Other"); // während der Anmeldung gesperrt
		m.cancel();
		m.awaitIdle(5000);
		assertEquals(1, calls.get());
		assertEquals(LoginModel.State.SIGNED_OUT, m.snapshot().state());
		assertEquals("Anmeldung abgebrochen", m.snapshot().status());
		assertNull(m.identity());
	}

	@Test
	void errorsAreShownAsUserMessages() throws Exception {
		LoginModel m = new LoginModel();
		m.signIn("microsoft", (status, cancel) -> {
			throw new AuthException(AuthError.APP_NOT_APPROVED, "login_with_xbox HTTP 403");
		});
		m.awaitIdle(5000);
		assertEquals(LoginModel.State.ERROR, m.snapshot().state());
		assertEquals(AuthError.APP_NOT_APPROVED.message(), m.snapshot().error());
		assertFalse(m.snapshot().canConnect());
		// ohne Freigabe: Fehler vor jeder Netzwerkverbindung
		LoginFactory.Options noApproval = new LoginFactory.Options("microsoft", "x", AuthChainTest.TEST_CLIENT_ID, false,
			dev.mcresearch.protocolclient.auth.minecraft.MicrosoftAccountLogin.Method.BROWSER, null, null);
		m.signIn("microsoft", (status, cancel) -> LoginFactory.create(noApproval, status, cancel));
		m.awaitIdle(5000);
		assertEquals(AuthError.EXTERNAL_NOT_ALLOWED.message(), m.snapshot().error());
		LoginFactory.Options noClient = new LoginFactory.Options("microsoft", "x", null, true,
			dev.mcresearch.protocolclient.auth.minecraft.MicrosoftAccountLogin.Method.BROWSER, null, null);
		m.signIn("microsoft", (status, cancel) -> LoginFactory.create(noClient, status, cancel));
		m.awaitIdle(5000);
		assertEquals(AuthError.NO_CLIENT_ID.message(), m.snapshot().error());
		assertNotNull(m.snapshot());
	}
}
