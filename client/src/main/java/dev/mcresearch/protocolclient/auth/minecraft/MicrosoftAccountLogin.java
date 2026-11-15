package dev.mcresearch.protocolclient.auth.minecraft;

import dev.mcresearch.protocolclient.auth.AuthCancellation;
import dev.mcresearch.protocolclient.auth.AuthError;
import dev.mcresearch.protocolclient.auth.AuthException;
import dev.mcresearch.protocolclient.auth.SecretToken;
import dev.mcresearch.protocolclient.auth.http.HttpTransport;
import dev.mcresearch.protocolclient.auth.microsoft.MicrosoftOAuth;
import dev.mcresearch.protocolclient.auth.microsoft.MicrosoftTokens;
import dev.mcresearch.protocolclient.auth.xbox.XboxLive;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * M13: vollständige Kontoanmeldung (docs/52 §3): Microsoft → Xbox Live → XSTS → Minecraft-Token → Berechtigung →
 * Profil → Mehrspieler-Privileg. Ergebnis ist eine {@link OnlineIdentity}; alle Tokens bleiben im Arbeitsspeicher.
 * Jeder Schritt wird genau einmal versucht (keine automatischen Wiederholungen, Rate-Limits respektieren).
 */
public final class MicrosoftAccountLogin {
	public enum Method {
		BROWSER, DEVICE_CODE
	}

	public enum Step {
		MICROSOFT("Microsoft-Anmeldung im Browser"),
		XBOX("Xbox Live"),
		XSTS("Xbox-Sicherheitstoken"),
		MINECRAFT("Minecraft Services"),
		ENTITLEMENTS("Besitzprüfung"),
		PROFILE("Minecraft-Profil"),
		PRIVILEGES("Mehrspieler-Berechtigung"),
		DONE("Angemeldet");

		private final String label;

		Step(final String label) {
			this.label = label;
		}

		public String label() {
			return this.label;
		}
	}

	private final MicrosoftOAuth oauth;
	private final XboxLive xbox;
	private final MinecraftServices services;
	private final ServiceDiscovery discovery;
	private final HttpTransport http;
	private final Clock clock;
	private volatile MicrosoftTokens microsoft;

	public MicrosoftAccountLogin(final String clientId, final HttpTransport http, final Clock clock) {
		this.oauth = new MicrosoftOAuth(clientId, http);
		this.xbox = new XboxLive(http);
		this.services = new MinecraftServices(http, clock);
		this.discovery = new ServiceDiscovery(http, ServiceDiscovery.PRODUCTION);
		this.http = http;
		this.clock = clock;
	}

	public OnlineIdentity login(final Method method, final MicrosoftOAuth.BrowserOpener browser, final Consumer<MicrosoftOAuth.DeviceCodePrompt> deviceCode,
		final Consumer<Step> progress, final AuthCancellation cancel) throws AuthException {
		progress.accept(Step.MICROSOFT);
		MicrosoftTokens ms = method == Method.BROWSER
			? this.oauth.interactiveLogin(browser, cancel, Duration.ofMinutes(5))
			: this.oauth.deviceCodeLogin(deviceCode, cancel);
		this.microsoft = ms;
		return minecraft(ms.accessToken(), progress, cancel);
	}

	/** Neue Minecraft-Sitzung mit dem Refresh-Token (z. B. nach Ablauf des Minecraft-Tokens), ohne Browser. */
	public OnlineIdentity refresh(final Consumer<Step> progress, final AuthCancellation cancel) throws AuthException {
		MicrosoftTokens ms = this.microsoft;
		if (ms == null) {
			throw new AuthException(AuthError.INVALID_GRANT, "not logged in");
		}
		progress.accept(Step.MICROSOFT);
		ms = this.oauth.refresh(ms);
		this.microsoft = ms;
		return minecraft(ms.accessToken(), progress, cancel);
	}

	private OnlineIdentity minecraft(final SecretToken msAccess, final Consumer<Step> progress, final AuthCancellation cancel) throws AuthException {
		cancel.throwIfCancelled();
		progress.accept(Step.XBOX);
		XboxLive.XboxToken user = this.xbox.userToken(msAccess);
		cancel.throwIfCancelled();
		progress.accept(Step.XSTS);
		XboxLive.XboxToken xsts = this.xbox.xstsToken(user);
		user.token().discard();
		cancel.throwIfCancelled();
		progress.accept(Step.MINECRAFT);
		SecretToken mc = this.services.loginWithXbox(xsts);
		xsts.token().discard();
		cancel.throwIfCancelled();
		progress.accept(Step.ENTITLEMENTS);
		List<String> ent = this.services.entitlements(mc);
		if (!MinecraftServices.ownsJavaEdition(ent)) {
			throw new AuthException(AuthError.NO_OWNERSHIP, "entitlements: " + ent.size());
		}
		progress.accept(Step.PROFILE);
		MinecraftServices.Profile profile = this.services.profile(mc);
		progress.accept(Step.PRIVILEGES);
		MinecraftServices.Privileges priv = this.services.privileges(mc, this.discovery);
		if (priv.multiplayerBanned()) {
			throw new AuthException(AuthError.USER_BANNED, "banStatus MULTIPLAYER");
		}
		if (!priv.multiplayerServer()) {
			throw new AuthException(AuthError.MULTIPLAYER_DISABLED, "privileges.multiplayerServer=false");
		}
		progress.accept(Step.DONE);
		return new OnlineIdentity(profile.name(), profile.id(), mc, this.discovery, this.http, this.clock, "microsoft");
	}

	/** Abmelden: alle Tokens verwerfen. */
	public void logout() {
		MicrosoftTokens ms = this.microsoft;
		this.microsoft = null;
		if (ms != null) {
			ms.discard();
		}
	}
}
