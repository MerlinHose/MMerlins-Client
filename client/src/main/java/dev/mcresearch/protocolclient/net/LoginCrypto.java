package dev.mcresearch.protocolclient.net;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * M13: Kryptografie des Minecraft-Logins (docs/51 §3), ausschließlich mit JDK-Bausteinen (JCA). Verhalten nach der
 * Analyse von Vanilla 26.3 {@code net.minecraft.util.Crypt} und {@code ClientHandshakePacketListenerImpl#handleHello}:
 * <ul>
 * <li>gemeinsamer Schlüssel: 128-Bit-AES-Schlüssel aus {@link KeyGenerator} (SecureRandom des JDK);</li>
 * <li>Server-Schlüssel: X.509-DER-kodierter RSA-Public-Key aus {@code hello};</li>
 * <li>Schlüsselaustausch: RSA mit PKCS#1-v1.5-Padding ({@code "RSA"} = {@code RSA/ECB/PKCS1Padding}) für Schlüssel und Challenge;</li>
 * <li>Datenstrom: {@code AES/CFB8/NoPadding}, IV = Schlüsselbytes, je eine Chiffre pro Richtung;</li>
 * <li>Session-Hash: SHA-1 über serverId (ISO-8859-1), Schlüssel, Public-Key-DER; als vorzeichenbehaftete Zweierkomplement-Zahl
 * hexadezimal ({@code new BigInteger(digest).toString(16)}).</li>
 * </ul>
 */
public final class LoginCrypto {
	/** Obergrenze der akzeptierten RSA-Schlüssellänge (Vanilla-Server: 1024 Bit); schützt vor absurden Eingaben. */
	static final int MAX_RSA_BITS = 4096;
	static final int MIN_RSA_BITS = 512;

	private LoginCrypto() {
	}

	public static SecretKey newSharedSecret() throws GeneralSecurityException {
		KeyGenerator g = KeyGenerator.getInstance("AES");
		g.init(128, new SecureRandom());
		return g.generateKey();
	}

	/** Dekodiert den Server-Schlüssel; nur RSA mit plausibler Länge. */
	public static PublicKey serverPublicKey(final byte[] der) throws GeneralSecurityException {
		PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
		int bits = ((RSAPublicKey) key).getModulus().bitLength();
		if (bits < MIN_RSA_BITS || bits > MAX_RSA_BITS) {
			throw new GeneralSecurityException("Unsupported RSA key size " + bits);
		}
		return key;
	}

	public static byte[] rsaEncrypt(final PublicKey key, final byte[] data) throws GeneralSecurityException {
		Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
		c.init(Cipher.ENCRYPT_MODE, key);
		return c.doFinal(data);
	}

	/** Stromchiffre einer Richtung ({@link Cipher#ENCRYPT_MODE} oder {@link Cipher#DECRYPT_MODE}). */
	public static Cipher streamCipher(final int mode, final SecretKey key) throws GeneralSecurityException {
		Cipher c = Cipher.getInstance("AES/CFB8/NoPadding");
		c.init(mode, key, new IvParameterSpec(key.getEncoded()));
		return c;
	}

	/** Minecraft-Session-Hash (serverId-Parameter von join/hasJoined). */
	public static String serverHash(final String serverId, final byte[] sharedSecret, final byte[] publicKeyDer) {
		try {
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			sha1.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
			sha1.update(sharedSecret);
			sha1.update(publicKeyDer);
			return minecraftHex(sha1.digest());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-1 unavailable", e);
		}
	}

	/** Vorzeichenbehaftete Hex-Darstellung eines Digests (negativ mit führendem „-“, ohne führende Nullen). */
	public static String minecraftHex(final byte[] digest) {
		return new BigInteger(digest).toString(16);
	}
}
