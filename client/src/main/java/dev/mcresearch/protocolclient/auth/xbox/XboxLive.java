package dev.mcresearch.protocolclient.auth.xbox;

import dev.mcresearch.protocolclient.auth.AuthError;
import dev.mcresearch.protocolclient.auth.AuthException;
import dev.mcresearch.protocolclient.auth.SecretToken;
import dev.mcresearch.protocolclient.auth.http.HttpTransport;
import dev.mcresearch.protocolclient.auth.http.Json;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M13: Xbox-Live-Benutzertoken und XSTS-Token für die Relying Party von Minecraft Services (docs/50 §3).
 * <ol>
 * <li>{@code POST https://user.auth.xboxlive.com/user/authenticate}: Microsoft-Access-Token als RPS-Ticket
 * ({@code "d=" + token}, Pflichtpräfix für Tokens eigener App-Registrierungen) → Xbox-Benutzertoken + User-Hash (uhs);</li>
 * <li>{@code POST https://xsts.auth.xboxlive.com/xsts/authorize}: Sandbox {@code RETAIL}, Relying Party
 * {@code rp://api.minecraftservices.com/} → XSTS-Token; Kontoprobleme kommen als HTTP 401 mit {@code XErr}.</li>
 * </ol>
 */
public final class XboxLive {
	public static final URI USER_AUTH = URI.create("https://user.auth.xboxlive.com/user/authenticate");
	public static final URI XSTS_AUTH = URI.create("https://xsts.auth.xboxlive.com/xsts/authorize");
	public static final String MINECRAFT_RELYING_PARTY = "rp://api.minecraftservices.com/";

	/** Token mit User-Hash (uhs ist nicht geheim, aber nicht für Logs gedacht). */
	public record XboxToken(SecretToken token, String userHash) {
		@Override
		public String toString() {
			return "XboxToken[redacted]";
		}
	}

	private final HttpTransport http;

	public XboxLive(final HttpTransport http) {
		this.http = http;
	}

	public XboxToken userToken(final SecretToken microsoftAccess) throws AuthException {
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("AuthMethod", "RPS");
		props.put("SiteName", "user.auth.xboxlive.com");
		props.put("RpsTicket", "d=" + microsoftAccess.reveal());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Properties", props);
		body.put("RelyingParty", "http://auth.xboxlive.com");
		body.put("TokenType", "JWT");
		return parse(post(USER_AUTH, body), "xbl");
	}

	public XboxToken xstsToken(final XboxToken user) throws AuthException {
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("SandboxId", "RETAIL");
		props.put("UserTokens", List.of(user.token().reveal()));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Properties", props);
		body.put("RelyingParty", MINECRAFT_RELYING_PARTY);
		body.put("TokenType", "JWT");
		return parse(post(XSTS_AUTH, body), "xsts");
	}

	private HttpTransport.Response post(final URI uri, final Map<String, Object> body) throws AuthException {
		Map<String, String> h = new LinkedHashMap<>();
		h.put("Content-Type", "application/json");
		h.put("Accept", "application/json");
		h.put("x-xbl-contract-version", "1");
		return this.http.send(new HttpTransport.Request("POST", uri, h, Json.write(body).getBytes(StandardCharsets.UTF_8)));
	}

	private static XboxToken parse(final HttpTransport.Response r, final String what) throws AuthException {
		if (r.status() == 401 || r.status() == 403) {
			Json j = r.body().isBlank() ? null : Json.parse(r.body(), what);
			long xerr = j == null ? 0 : j.number(0, "XErr");
			throw xerr(xerr, what + " HTTP " + r.status());
		}
		if (r.status() == 429) {
			throw new AuthException(AuthError.RATE_LIMITED, what);
		}
		if (r.status() >= 500) {
			throw new AuthException(AuthError.SERVICE_UNAVAILABLE, what + " HTTP " + r.status());
		}
		if (r.status() != 200) {
			throw new AuthException(AuthError.XBOX_ERROR, what + " HTTP " + r.status());
		}
		Json j = Json.parse(r.body(), what);
		String token = j.requireString(what, "Token");
		String uhs = j.requireString(what, "DisplayClaims", "xui", 0, "uhs");
		Instant notAfter = null;
		String na = j.string("NotAfter");
		if (na != null) {
			try {
				notAfter = Instant.parse(na);
			} catch (DateTimeParseException e) {
				notAfter = null;
			}
		}
		return new XboxToken(new SecretToken(what, token, notAfter), uhs);
	}

	/** Bekannte XErr-Codes des XSTS-Dienstes (docs/50 §3, Tabelle). */
	public static AuthException xerr(final long code, final String detail) {
		AuthError e = switch ((int) (code - 2148916000L)) {
			case 227 -> AuthError.XBOX_BANNED;
			case 233 -> AuthError.NO_XBOX_ACCOUNT;
			case 235 -> AuthError.XBOX_COUNTRY;
			case 236, 237 -> AuthError.XBOX_ADULT_VERIFICATION;
			case 238 -> AuthError.XBOX_CHILD;
			default -> AuthError.XBOX_ERROR;
		};
		return new AuthException(e, detail + (code == 0 ? "" : " XErr " + code));
	}
}
