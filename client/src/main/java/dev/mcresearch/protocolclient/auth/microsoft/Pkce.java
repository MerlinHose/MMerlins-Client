package dev.mcresearch.protocolclient.auth.microsoft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * M13: PKCE nach RFC 7636 (docs/50 §2): {@code code_verifier} = Base64url(32 Zufallsbytes aus {@link SecureRandom}),
 * 43 Zeichen; {@code code_challenge} = Base64url(SHA-256(verifier)), Methode {@code S256}. Zusätzlich erzeugt
 * {@link #randomToken()} die Werte für {@code state} und {@code nonce}-artige Prüfwerte.
 */
public record Pkce(String verifier, String challenge) {
	private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

	public static Pkce generate(final SecureRandom random) {
		byte[] b = new byte[32];
		random.nextBytes(b);
		String verifier = URL.encodeToString(b);
		return new Pkce(verifier, challengeOf(verifier));
	}

	public static String challengeOf(final String verifier) {
		try {
			return URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String randomToken(final SecureRandom random) {
		byte[] b = new byte[32];
		random.nextBytes(b);
		return URL.encodeToString(b);
	}

	@Override
	public String toString() {
		return "Pkce[S256, redacted]";
	}
}
