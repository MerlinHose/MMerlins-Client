package dev.mcresearch.protocolclient.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** M13: Session-Hash, RSA-Schlüsselaustausch und AES/CFB8-Datenstrom (docs/51 §3–§4). */
class LoginCryptoTest {
	private static byte[] sha1(final String s) throws Exception {
		return MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.US_ASCII));
	}

	/** Bekannte Testvektoren der Minecraft-Hex-Darstellung (positiv, negativ, mit führender Null). */
	@Test
	void minecraftHexKnownVectors() throws Exception {
		assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48", LoginCrypto.minecraftHex(sha1("Notch")));
		assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1", LoginCrypto.minecraftHex(sha1("jeb_")));
		assertEquals("88e16a1019277b15d58faf0541e11910eb756f6", LoginCrypto.minecraftHex(sha1("simon")));
	}

	@Test
	void serverHashEqualsIndependentComputation() throws Exception {
		byte[] secret = new byte[16];
		byte[] pub = new byte[162];
		new Random(1).nextBytes(secret);
		new Random(2).nextBytes(pub);
		MessageDigest d = MessageDigest.getInstance("SHA-1");
		d.update("".getBytes(StandardCharsets.ISO_8859_1));
		d.update(secret);
		d.update(pub);
		assertEquals(new BigInteger(d.digest()).toString(16), LoginCrypto.serverHash("", secret, pub));
		// serverId ist Teil des Hashs (Vanilla-Server senden "", ältere Server eine Kennung)
		d.update("abc".getBytes(StandardCharsets.ISO_8859_1));
		d.update(secret);
		d.update(pub);
		assertEquals(new BigInteger(d.digest()).toString(16), LoginCrypto.serverHash("abc", secret, pub));
	}

	@Test
	void sharedSecretIs128BitAes() throws Exception {
		SecretKey k = LoginCrypto.newSharedSecret();
		assertEquals("AES", k.getAlgorithm());
		assertEquals(16, k.getEncoded().length);
	}

	@Test
	void rsaKeyExchangeRoundTrip() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(1024);
		KeyPair kp = g.generateKeyPair();
		byte[] secret = LoginCrypto.newSharedSecret().getEncoded();
		byte[] enc = LoginCrypto.rsaEncrypt(LoginCrypto.serverPublicKey(kp.getPublic().getEncoded()), secret);
		assertEquals(128, enc.length, "1024-bit RSA block");
		Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
		c.init(Cipher.DECRYPT_MODE, kp.getPrivate());
		assertArrayEquals(secret, c.doFinal(enc));
	}

	@Test
	void invalidServerKeysRejected() throws Exception {
		assertThrows(GeneralSecurityException.class, () -> LoginCrypto.serverPublicKey(new byte[]{1, 2, 3}));
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(384 + 128); // 512 ist die Untergrenze und noch erlaubt
		LoginCrypto.serverPublicKey(g.generateKeyPair().getPublic().getEncoded());
		KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
		ec.initialize(256);
		assertThrows(GeneralSecurityException.class, () -> LoginCrypto.serverPublicKey(ec.generateKeyPair().getPublic().getEncoded()));
	}

	/** Strom über beliebige Stückelung = einmalige Verschlüsselung (Zustand über Paket-/Segmentgrenzen). */
	@Test
	void cfb8StreamIsContinuousAcrossChunks() throws Exception {
		byte[] key = new byte[16];
		new Random(3).nextBytes(key);
		SecretKey k = new SecretKeySpec(key, "AES");
		byte[] plain = new byte[100_000];
		new Random(4).nextBytes(plain);
		Cipher ref = Cipher.getInstance("AES/CFB8/NoPadding");
		ref.init(Cipher.ENCRYPT_MODE, k, new IvParameterSpec(key));
		byte[] expected = ref.doFinal(plain);

		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		CipherStreams.Encrypting enc = new CipherStreams.Encrypting(sink);
		enc.write(plain, 0, 10); // vor dem Umschalten: Klartext
		enc.enable(LoginCrypto.streamCipher(Cipher.ENCRYPT_MODE, k));
		Random chunks = new Random(5);
		int off = 10;
		while (off < plain.length) {
			int n = Math.min(plain.length - off, 1 + chunks.nextInt(3000));
			if (n == 1) {
				enc.write(plain[off]);
			} else {
				enc.write(plain, off, n);
			}
			off += n;
		}
		byte[] wire = sink.toByteArray();
		assertArrayEquals(java.util.Arrays.copyOf(plain, 10), java.util.Arrays.copyOf(wire, 10));
		ref.init(Cipher.ENCRYPT_MODE, k, new IvParameterSpec(key));
		assertArrayEquals(ref.doFinal(java.util.Arrays.copyOfRange(plain, 10, plain.length)), java.util.Arrays.copyOfRange(wire, 10, wire.length));

		// Entschlüsselung: Einzelbytes und Blöcke gemischt
		CipherStreams.Decrypting dec = new CipherStreams.Decrypting(new ByteArrayInputStream(expected));
		dec.enable(LoginCrypto.streamCipher(Cipher.DECRYPT_MODE, k));
		ByteArrayOutputStream back = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		while (true) {
			int b = dec.read();
			if (b < 0) {
				break;
			}
			back.write(b);
			int n = dec.read(buf, 0, 1 + chunks.nextInt(buf.length - 1));
			if (n < 0) {
				break;
			}
			back.write(buf, 0, n);
		}
		assertArrayEquals(plain, back.toByteArray());
	}

	@Test
	void encryptionCanOnlyBeEnabledOnce() throws Exception {
		SecretKey k = LoginCrypto.newSharedSecret();
		CipherStreams.Decrypting dec = new CipherStreams.Decrypting(new ByteArrayInputStream(new byte[0]));
		dec.enable(LoginCrypto.streamCipher(Cipher.DECRYPT_MODE, k));
		assertThrows(IllegalStateException.class, () -> dec.enable(LoginCrypto.streamCipher(Cipher.DECRYPT_MODE, k)));
	}
}
