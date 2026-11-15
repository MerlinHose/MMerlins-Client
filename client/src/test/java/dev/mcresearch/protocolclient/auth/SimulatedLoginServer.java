package dev.mcresearch.protocolclient.auth;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * M13 Phase G: lokale Protokoll-Testkomponente, die den verschlüsselten Login eines Online-Mode-Servers reproduzierbar
 * nachbildet (docs/53 §3). Bewusst <b>unabhängig</b> vom Client implementiert: eigene VarInt-/Framing-Routinen, JCA
 * {@link CipherInputStream}/{@link CipherOutputStream} für AES/CFB8, {@link Deflater}/{@link Inflater} direkt, eigene
 * Hash-Berechnung. So fallen symmetrische Fehler (z. B. falscher IV auf beiden Seiten) nicht weg.
 * <p>
 * Der Session-Nachweis wird über {@code hasJoined} (Name, Hash → UUID oder null) simuliert, gespeist von einer
 * Test-Identität. Kein Microsoft-Login, keine echten Tokens; nur Testdaten. Bindet ausschließlich an 127.0.0.1.
 */
final class SimulatedLoginServer implements AutoCloseable {
	enum Mode {
		NORMAL, CLOSE_AFTER_HELLO, BAD_PUBLIC_KEY, SILENT_AFTER_KEY, BAD_LENGTH_AFTER_KEY, TRUNCATED_AFTER_KEY
	}

	record Options(boolean shouldAuthenticate, int compression, int fragment, Mode mode, List<byte[]> payloads) {
		static Options normal(final int compression) {
			return new Options(true, compression, 0, Mode.NORMAL, List.of());
		}

		Options with(final Mode m) {
			return new Options(this.shouldAuthenticate, this.compression, this.fragment, m, this.payloads);
		}

		Options fragmented(final int f) {
			return new Options(this.shouldAuthenticate, this.compression, f, this.mode, this.payloads);
		}

		Options payloads(final List<byte[]> p) {
			return new Options(this.shouldAuthenticate, this.compression, this.fragment, this.mode, p);
		}

		Options noAuth() {
			return new Options(false, this.compression, this.fragment, this.mode, this.payloads);
		}
	}

	/** Beobachtungen des Servers für die Testauswertung. */
	static final class Observed {
		volatile String helloName;
		volatile UUID helloUuid;
		volatile String serverHash;
		volatile boolean challengeValid;
		volatile boolean sessionAccepted;
		volatile int secretLength;
		final List<String> afterEncryption = new ArrayList<>();
		volatile String failure;
	}

	private final ServerSocket socket;
	private final Options options;
	private final BiFunction<String, String, UUID> hasJoined;
	private final KeyPair keyPair;
	private final byte[] challenge = new byte[4];
	final Observed observed = new Observed();
	final CompletableFuture<Void> done = new CompletableFuture<>();
	private final Thread thread;

	SimulatedLoginServer(final Options options, final BiFunction<String, String, UUID> hasJoined) throws Exception {
		this.socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
		this.options = options;
		this.hasJoined = hasJoined;
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(1024); // wie Vanilla Crypt.generateKeyPair
		this.keyPair = g.generateKeyPair();
		new SecureRandom().nextBytes(this.challenge);
		this.thread = new Thread(this::serve, "simulated-login-server");
		this.thread.setDaemon(true);
		this.thread.start();
	}

	int port() {
		return this.socket.getLocalPort();
	}

	byte[] publicKey() {
		return this.keyPair.getPublic().getEncoded();
	}

	private void serve() {
		try (Socket s = this.socket.accept()) {
			s.setSoTimeout(15000);
			run(s);
			this.done.complete(null);
		} catch (Throwable t) {
			this.observed.failure = t.toString();
			this.done.complete(null);
		}
	}

	private void run(final Socket s) throws Exception {
		InputStream rawIn = s.getInputStream();
		OutputStream rawOut = s.getOutputStream();
		// Handshake + hello (unverschlüsselt, unkomprimiert)
		readFrame(rawIn);
		byte[] hello = readFrame(rawIn);
		Reader h = new Reader(hello);
		expect(h.varInt() == 0, "hello id");
		this.observed.helloName = h.string();
		this.observed.helloUuid = new UUID(h.longValue(), h.longValue());
		// Encryption Request (login clientbound 1)
		Writer req = new Writer().varInt(1).string("");
		if (this.options.mode == Mode.BAD_PUBLIC_KEY) {
			byte[] junk = new byte[162];
			new SecureRandom().nextBytes(junk);
			req.bytesWithLength(junk);
		} else {
			req.bytesWithLength(publicKey());
		}
		req.bytesWithLength(this.challenge).bool(this.options.shouldAuthenticate);
		writeFrame(rawOut, req.toBytes(), -1);
		if (this.options.mode == Mode.CLOSE_AFTER_HELLO || this.options.mode == Mode.BAD_PUBLIC_KEY) {
			// Client bricht ab oder Server trennt; warten, bis der Client schließt
			try {
				readFrame(rawIn);
			} catch (IOException expected) {
				// Verbindung beendet
			}
			return;
		}
		// Key (login serverbound 1)
		Reader k = new Reader(readFrame(rawIn));
		expect(k.varInt() == 1, "key id");
		byte[] encSecret = k.bytesWithLength();
		byte[] encChallenge = k.bytesWithLength();
		Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
		rsa.init(Cipher.DECRYPT_MODE, this.keyPair.getPrivate());
		byte[] secret = rsa.doFinal(encSecret);
		rsa.init(Cipher.DECRYPT_MODE, this.keyPair.getPrivate());
		this.observed.challengeValid = Arrays.equals(this.challenge, rsa.doFinal(encChallenge));
		this.observed.secretLength = secret.length;
		MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
		sha1.update("".getBytes(StandardCharsets.ISO_8859_1));
		sha1.update(secret);
		sha1.update(publicKey());
		this.observed.serverHash = new BigInteger(sha1.digest()).toString(16);
		SecretKeySpec key = new SecretKeySpec(secret, "AES");
		Cipher dec = Cipher.getInstance("AES/CFB8/NoPadding");
		dec.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(secret));
		Cipher enc = Cipher.getInstance("AES/CFB8/NoPadding");
		enc.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(secret));
		InputStream in = new CipherInputStream(rawIn, dec);
		OutputStream out = new CipherOutputStream(rawOut, enc);
		if (!this.observed.challengeValid) {
			throw new IOException("challenge mismatch");
		}
		switch (this.options.mode) {
			case SILENT_AFTER_KEY -> {
				Thread.sleep(4000);
				return;
			}
			case BAD_LENGTH_AFTER_KEY -> {
				out.write(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F});
				out.flush();
				Thread.sleep(500);
				return;
			}
			case TRUNCATED_AFTER_KEY -> {
				out.write(new byte[]{50, 1, 2, 3});
				out.flush();
				s.shutdownOutput();
				Thread.sleep(500);
				return;
			}
			default -> {
			}
		}
		UUID profile = this.observed.helloUuid;
		if (this.options.shouldAuthenticate) {
			profile = this.hasJoined.apply(this.observed.helloName, this.observed.serverHash);
			this.observed.sessionAccepted = profile != null;
			if (profile == null) {
				// login_disconnect (JSON-Text), bereits verschlüsselt
				writeFrame(out, new Writer().varInt(0).string("{\"translate\":\"multiplayer.disconnect.unverified_username\"}").toBytes(), -1, this.options.fragment);
				Thread.sleep(300);
				return;
			}
		}
		int threshold = this.options.compression;
		if (threshold >= 0) {
			writeFrame(out, new Writer().varInt(3).varInt(threshold).toBytes(), -1, this.options.fragment);
		}
		// login_finished: Profil (UUID, Name, 0 Properties)
		Writer fin = new Writer().varInt(2).longValue(profile.getMostSignificantBits()).longValue(profile.getLeastSignificantBits())
			.string(this.observed.helloName).varInt(0).longValue(0x13L).longValue(0x13L); // + Session-UUID (Vanilla 26.3)
		writeFrame(out, fin.toBytes(), threshold, this.options.fragment);
		// Client: login_acknowledged, dann (Configuration) custom_payload brand + client_information
		for (int i = 0; i < 3; i++) {
			Reader r = new Reader(decompress(readFrame(in), threshold));
			this.observed.afterEncryption.add(i == 0 ? "login:" + r.varInt() : "configuration:" + r.varInt());
		}
		// große bzw. viele Pakete: configuration custom_payload (clientbound 1)
		for (byte[] p : this.options.payloads) {
			writeFrame(out, new Writer().varInt(1).string("mcresearch:m13_test").raw(p).toBytes(), threshold, this.options.fragment);
		}
		// configuration disconnect (clientbound 2), Grund als NBT-String
		byte[] reason = "M13 simulated login complete".getBytes(StandardCharsets.UTF_8);
		Writer d = new Writer().varInt(2).raw(new byte[]{8, (byte) (reason.length >> 8), (byte) reason.length}).raw(reason);
		writeFrame(out, d.toBytes(), threshold, this.options.fragment);
		Thread.sleep(300);
	}

	private static void expect(final boolean ok, final String what) throws IOException {
		if (!ok) {
			throw new IOException("unexpected " + what);
		}
	}

	// ---------- unabhängige Framing-/Kompressionsroutinen ----------

	static byte[] readFrame(final InputStream in) throws IOException {
		int len = 0;
		for (int i = 0; ; i++) {
			int b = in.read();
			if (b < 0) {
				throw new EOFException();
			}
			len |= (b & 0x7F) << (7 * i);
			if ((b & 0x80) == 0) {
				break;
			}
			if (i >= 2) {
				throw new IOException("frame length too long");
			}
		}
		byte[] f = new byte[len];
		new DataInputStream(in).readFully(f);
		return f;
	}

	static byte[] decompress(final byte[] frame, final int threshold) throws Exception {
		if (threshold < 0) {
			return frame;
		}
		Reader r = new Reader(frame);
		int dataLength = r.varInt();
		byte[] rest = Arrays.copyOfRange(frame, r.pos, frame.length);
		if (dataLength == 0) {
			return rest;
		}
		Inflater inf = new Inflater();
		inf.setInput(rest);
		byte[] out = new byte[dataLength];
		int n = inf.inflate(out);
		inf.end();
		expect(n == dataLength, "inflated length");
		return out;
	}

	private static void writeFrame(final OutputStream out, final byte[] packet, final int threshold) throws IOException {
		writeFrame(out, packet, threshold, 0);
	}

	/** Schreibt einen Frame; {@code fragment > 0}: in Stücken dieser Größe mit flush und kurzer Pause (TCP-Fragmentierung). */
	static void writeFrame(final OutputStream out, final byte[] packet, final int threshold, final int fragment) throws IOException {
		byte[] content;
		if (threshold < 0) {
			content = packet;
		} else if (packet.length < threshold) {
			content = new Writer().varInt(0).raw(packet).toBytes();
		} else {
			Deflater def = new Deflater();
			def.setInput(packet);
			def.finish();
			ByteArrayOutputStream z = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			while (!def.finished()) {
				z.write(buf, 0, def.deflate(buf));
			}
			def.end();
			content = new Writer().varInt(packet.length).raw(z.toByteArray()).toBytes();
		}
		byte[] frame = new Writer().varInt(content.length).raw(content).toBytes();
		if (fragment <= 0) {
			out.write(frame);
			out.flush();
			return;
		}
		for (int off = 0; off < frame.length; off += fragment) {
			out.write(frame, off, Math.min(fragment, frame.length - off));
			out.flush();
			if ((off / fragment) % 64 == 0) {
				try {
					Thread.sleep(1);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException(e);
				}
			}
		}
	}

	static final class Writer {
		private final ByteArrayOutputStream b = new ByteArrayOutputStream();

		Writer varInt(final int v) {
			int x = v;
			while ((x & ~0x7F) != 0) {
				this.b.write((x & 0x7F) | 0x80);
				x >>>= 7;
			}
			this.b.write(x);
			return this;
		}

		Writer string(final String s) {
			byte[] u = s.getBytes(StandardCharsets.UTF_8);
			varInt(u.length);
			this.b.writeBytes(u);
			return this;
		}

		Writer bytesWithLength(final byte[] d) {
			varInt(d.length);
			this.b.writeBytes(d);
			return this;
		}

		Writer bool(final boolean v) {
			this.b.write(v ? 1 : 0);
			return this;
		}

		Writer longValue(final long v) {
			for (int i = 7; i >= 0; i--) {
				this.b.write((int) (v >>> (8 * i)));
			}
			return this;
		}

		Writer raw(final byte[] d) {
			this.b.writeBytes(d);
			return this;
		}

		byte[] toBytes() {
			return this.b.toByteArray();
		}
	}

	static final class Reader {
		private final byte[] d;
		int pos;

		Reader(final byte[] d) {
			this.d = d;
		}

		int varInt() throws IOException {
			int v = 0;
			for (int i = 0; i < 5; i++) {
				int b = this.d[this.pos++] & 0xFF;
				v |= (b & 0x7F) << (7 * i);
				if ((b & 0x80) == 0) {
					return v;
				}
			}
			throw new IOException("varint too long");
		}

		String string() throws IOException {
			int n = varInt();
			String s = new String(this.d, this.pos, n, StandardCharsets.UTF_8);
			this.pos += n;
			return s;
		}

		long longValue() {
			long v = 0;
			for (int i = 0; i < 8; i++) {
				v = (v << 8) | (this.d[this.pos++] & 0xFF);
			}
			return v;
		}

		byte[] bytesWithLength() throws IOException {
			int n = varInt();
			byte[] r = Arrays.copyOfRange(this.d, this.pos, this.pos + n);
			this.pos += n;
			return r;
		}
	}

	@Override
	public void close() throws IOException {
		this.socket.close();
	}
}
