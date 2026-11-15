package dev.mcresearch.protocolclient.auth.minecraft;

import dev.mcresearch.protocolclient.auth.AuthCancellation;
import dev.mcresearch.protocolclient.auth.AuthError;
import dev.mcresearch.protocolclient.auth.AuthException;
import dev.mcresearch.protocolclient.auth.LoginIdentity;
import dev.mcresearch.protocolclient.auth.OfflineIdentity;
import dev.mcresearch.protocolclient.auth.http.EndpointPolicy;
import dev.mcresearch.protocolclient.auth.http.JdkHttpTransport;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * M13: erzeugt die Login-Identität aus den Startoptionen (Terminal- und Grafikclient, docs/52 §5).
 * <ul>
 * <li>{@code offline} (Standard): bisheriger Testmodus, keinerlei HTTP;</li>
 * <li>{@code simulation}: lokale Testdienste unter {@code http://127.0.0.1:<port>}, Testname/-UUID/-Token;</li>
 * <li>{@code microsoft}: offizielle Anmeldung; nur mit {@code allowExternal} (ausdrückliche Freigabe) und eigener Client-ID.</li>
 * </ul>
 */
public final class LoginFactory {
	public static final UUID SIMULATION_UUID = UUID.fromString("00000000-0000-4000-8000-00000000d013");

	public record Options(String mode, String username, String clientId, boolean allowExternal, MicrosoftAccountLogin.Method method,
		URI simulationDiscovery, UUID simulationUuid) {
		public static Options offline(final String username) {
			return new Options("offline", username, null, false, MicrosoftAccountLogin.Method.BROWSER, null, SIMULATION_UUID);
		}
	}

	/** Gemeinsame Optionsauswertung; liefert den verbrauchten Index oder -1, wenn die Option nicht zur Anmeldung gehört. */
	public static final class Parser {
		public String mode = "offline";
		public String clientId;
		public boolean allowExternal;
		public MicrosoftAccountLogin.Method method = MicrosoftAccountLogin.Method.BROWSER;
		public URI simulationDiscovery;
		public UUID simulationUuid = SIMULATION_UUID;

		public int parse(final String[] args, final int i) {
			switch (args[i]) {
				case "--auth" -> {
					this.mode = args[i + 1].toLowerCase(Locale.ROOT);
					if (!this.mode.matches("offline|simulation|microsoft")) {
						throw new IllegalArgumentException("--auth offline|simulation|microsoft");
					}
					return i + 1;
				}
				case "--client-id" -> {
					this.clientId = args[i + 1];
					return i + 1;
				}
				case "--allow-external-auth" -> {
					this.allowExternal = true;
					return i;
				}
				case "--login-method" -> {
					this.method = switch (args[i + 1].toLowerCase(Locale.ROOT)) {
						case "browser" -> MicrosoftAccountLogin.Method.BROWSER;
						case "device-code" -> MicrosoftAccountLogin.Method.DEVICE_CODE;
						default -> throw new IllegalArgumentException("--login-method browser|device-code");
					};
					return i + 1;
				}
				case "--sim-discovery" -> {
					this.simulationDiscovery = URI.create(args[i + 1]);
					return i + 1;
				}
				case "--sim-uuid" -> {
					this.simulationUuid = UUID.fromString(args[i + 1]);
					return i + 1;
				}
				default -> {
					return -1;
				}
			}
		}

		public Options options(final String username) {
			return new Options(this.mode, username, this.clientId, this.allowExternal, this.method, this.simulationDiscovery, this.simulationUuid);
		}
	}

	private LoginFactory() {
	}

	/** Wie {@link #create}, aber mit vorhandenem Login-Objekt (für Abmelden und Refresh in der Oberfläche). */
	public static MicrosoftAccountLogin microsoftLogin(final Options o) throws AuthException {
		if (!o.allowExternal()) {
			throw new AuthException(AuthError.EXTERNAL_NOT_ALLOWED, "");
		}
		if (o.clientId() == null || o.clientId().isBlank()) {
			throw new AuthException(AuthError.NO_CLIENT_ID, "");
		}
		try {
			return new MicrosoftAccountLogin(o.clientId(), new JdkHttpTransport(EndpointPolicy.official()), Clock.systemUTC());
		} catch (IllegalArgumentException e) {
			throw new AuthException(AuthError.NO_CLIENT_ID, e.getMessage());
		}
	}

	public static LoginIdentity create(final Options o, final Consumer<String> status, final AuthCancellation cancel) throws AuthException {
		switch (o.mode()) {
			case "offline" -> {
				return new OfflineIdentity(o.username());
			}
			case "simulation" -> {
				if (o.simulationDiscovery() == null) {
					throw new IllegalArgumentException("--auth simulation requires --sim-discovery http://127.0.0.1:<port>/...");
				}
				EndpointPolicy.localSimulation().check(o.simulationDiscovery());
				status.accept("LOKALE SIMULATION (Testdaten, kein offizieller Login): " + o.username() + " " + o.simulationUuid());
				return OnlineIdentity.simulation(o.username(), o.simulationUuid(), o.simulationDiscovery(), new JdkHttpTransport(EndpointPolicy.localSimulation()));
			}
			case "microsoft" -> {
				MicrosoftAccountLogin login = microsoftLogin(o);
				OnlineIdentity id = login.login(o.method(), LoginFactory::openBrowser,
					p -> status.accept("Anmeldung: " + p.verificationUri() + " öffnen und Code " + p.userCode() + " eingeben (gültig "
						+ p.expiresIn().toMinutes() + " min)"),
					s -> status.accept("Anmeldung: " + s.label()), cancel);
				status.accept("Angemeldet als " + id.name() + " (" + id.profileId() + ")");
				return id;
			}
			default -> throw new IllegalArgumentException("unknown auth mode " + o.mode());
		}
	}

	/** Systembrowser (kein eingebetteter Browser, kein Passwortfeld im Client). */
	public static void openBrowser(final URI uri) throws IOException {
		if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
			java.awt.Desktop.getDesktop().browse(uri);
			return;
		}
		throw new IOException("no system browser available; use --login-method device-code");
	}

}
