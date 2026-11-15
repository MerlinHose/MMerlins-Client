package dev.mcresearch.protocolclient.auth.http;

import dev.mcresearch.protocolclient.auth.AuthError;
import dev.mcresearch.protocolclient.auth.AuthException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M13: HTTPS über {@code java.net.http.HttpClient}. Sicherheitsregeln (docs/52 §6):
 * <ul>
 * <li>{@link EndpointPolicy#check} vor jeder Anfrage; Weiterleitungen werden nicht verfolgt (sonst könnte ein Redirect die
 * Allowlist umgehen);</li>
 * <li>Standard-SSLContext des JDK, keine eigene TrustManager-/HostnameVerifier-Logik (TLS-Prüfung bleibt aktiv);</li>
 * <li>Verbindungsaufbau 5 s, Antwort 10 s (Vanilla authlib: 5 s/5 s);</li>
 * <li>Antwortgröße höchstens 1 MiB; Logs enthalten nur Methode, Host, Pfad und Status.</li>
 * </ul>
 */
public final class JdkHttpTransport implements HttpTransport {
	private static final int MAX_BODY = 1 << 20;
	private static final System.Logger LOG = System.getLogger(JdkHttpTransport.class.getName());
	private final EndpointPolicy policy;
	private final HttpClient client;
	private final Duration requestTimeout;

	public JdkHttpTransport(final EndpointPolicy policy) {
		this(policy, Duration.ofSeconds(5), Duration.ofSeconds(10));
	}

	public JdkHttpTransport(final EndpointPolicy policy, final Duration connectTimeout, final Duration requestTimeout) {
		this.policy = policy;
		this.requestTimeout = requestTimeout;
		this.client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(connectTimeout)
			.version(HttpClient.Version.HTTP_1_1)
			.build();
	}

	@Override
	public EndpointPolicy policy() {
		return this.policy;
	}

	@Override
	public Response send(final Request request) throws AuthException {
		this.policy.check(request.uri());
		HttpRequest.Builder b = HttpRequest.newBuilder(request.uri()).timeout(this.requestTimeout);
		request.headers().forEach(b::header);
		if (request.body() != null) {
			b.method(request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()));
		} else {
			b.method(request.method(), HttpRequest.BodyPublishers.noBody());
		}
		try {
			HttpResponse<byte[]> r = this.client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
			byte[] body = r.body();
			if (body.length > MAX_BODY) {
				throw new AuthException(AuthError.PROTOCOL, "response too large");
			}
			Map<String, String> headers = new LinkedHashMap<>();
			r.headers().map().forEach((k, v) -> headers.put(k, String.join(",", v)));
			LOG.log(System.Logger.Level.INFO, request + " -> " + r.statusCode());
			return new Response(r.statusCode(), headers, new String(body, StandardCharsets.UTF_8));
		} catch (HttpTimeoutException e) {
			throw new AuthException(AuthError.TIMEOUT, request.toString());
		} catch (IOException e) {
			throw new AuthException(AuthError.SERVICE_UNAVAILABLE, request + ": " + e.getClass().getSimpleName());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AuthException(AuthError.USER_CANCELLED, request.toString());
		}
	}
}
