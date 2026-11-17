package dev.mcresearch.protocolclient.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mcresearch.protocolclient.auth.minecraft.ClientIdConfig;
import dev.mcresearch.protocolclient.auth.minecraft.LoginFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M13 (Vorbereitung): Entwicklungsmodus ist Standard, externe Anmeldung bleibt ohne ausdrückliche Freigabe gesperrt. */
class AuthDefaultsTest {
	private static final String ID_A = "aaaaaaaa-0000-4000-8000-000000000001";
	private static final String ID_B = "bbbbbbbb-0000-4000-8000-000000000002";
	private static final String ID_C = "cccccccc-0000-4000-8000-000000000003";

	@Test
	void defaultsAreOfflineWithoutExternalAccess() throws Exception {
		LoginFactory.Parser p = new LoginFactory.Parser();
		LoginFactory.Options o = p.options("ProtoBot");
		assertEquals("offline", o.mode());
		assertFalse(o.allowExternal());
		assertNull(o.clientId(), "offline mode must not read a client id");
		assertInstanceOf(OfflineIdentity.class, LoginFactory.create(o, s -> { }, new AuthCancellation()));
	}

	@Test
	void microsoftModeWithoutReleaseIsRejectedBeforeAnyRequest() {
		LoginFactory.Options o = new LoginFactory.Options("microsoft", "ProtoBot", ID_A, false,
			dev.mcresearch.protocolclient.auth.minecraft.MicrosoftAccountLogin.Method.BROWSER, null, LoginFactory.SIMULATION_UUID);
		AuthException e = assertThrows(AuthException.class, () -> LoginFactory.create(o, s -> { }, new AuthCancellation()));
		assertEquals(AuthError.EXTERNAL_NOT_ALLOWED, e.error());
	}

	@Test
	void microsoftModeWithoutClientIdIsRejected() {
		LoginFactory.Options o = new LoginFactory.Options("microsoft", "ProtoBot", null, true,
			dev.mcresearch.protocolclient.auth.minecraft.MicrosoftAccountLogin.Method.BROWSER, null, LoginFactory.SIMULATION_UUID);
		AuthException e = assertThrows(AuthException.class, () -> LoginFactory.microsoftLogin(o));
		assertEquals(AuthError.NO_CLIENT_ID, e.error());
	}

	@Test
	void clientIdPrecedenceCliThenEnvThenFile(@TempDir final Path dir) throws Exception {
		Path file = dir.resolve("auth.properties");
		Files.writeString(file, "# lokal, nicht versioniert\nclient-id=" + ID_C + "\n");
		Map<String, String> env = Map.of(ClientIdConfig.ENV, ID_B);
		assertEquals(ID_A, ClientIdConfig.resolve(ID_A, env, file));
		assertEquals(ID_B, ClientIdConfig.resolve(null, env, file));
		assertEquals(ID_C, ClientIdConfig.resolve(" ", Map.of(), file));
		assertNull(ClientIdConfig.resolve(null, Map.of(), dir.resolve("missing.properties")));
		assertNull(ClientIdConfig.resolve(null, Map.of(ClientIdConfig.ENV, "  "), null));
	}
}
