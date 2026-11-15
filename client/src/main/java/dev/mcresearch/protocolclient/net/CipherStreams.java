package dev.mcresearch.protocolclient.net;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import javax.crypto.Cipher;

/**
 * M13: umschaltbare Verschlüsselung des TCP-Datenstroms (docs/51 §4). Vor {@code enable} reichen beide Streams die
 * Bytes unverändert durch; danach läuft jedes Byte durch eine fortlaufende AES/CFB8-Chiffre. CFB8 ist ein
 * Stromchiffre-Modus: {@link Cipher#update} liefert zu jedem Eingabebyte genau ein Ausgabebyte, der Chiffrezustand wird
 * über Paket- und TCP-Segmentgrenzen hinweg fortgeführt (wie Vanilla {@code CipherDecoder}/{@code CipherEncoder}).
 * <p>
 * Die Streams sitzen <b>oberhalb</b> der Puffer (Socket → BufferedInputStream → Entschlüsselung → Framing). Damit werden
 * genau die Bytes entschlüsselt, die nach dem Umschalten gelesen werden, auch wenn der Puffer bereits weitere Daten
 * enthält.
 */
public final class CipherStreams {
	private CipherStreams() {
	}

	/** Eingang: entschlüsselt ab {@link #enable}. Nur ein Lesethread. */
	public static final class Decrypting extends InputStream {
		private final InputStream in;
		private volatile Cipher cipher;

		public Decrypting(final InputStream in) {
			this.in = in;
		}

		public void enable(final Cipher decrypt) {
			if (this.cipher != null) {
				throw new IllegalStateException("Encryption already enabled");
			}
			this.cipher = decrypt;
		}

		public boolean enabled() {
			return this.cipher != null;
		}

		@Override
		public int read() throws IOException {
			int b = this.in.read();
			Cipher c = this.cipher;
			if (b < 0 || c == null) {
				return b;
			}
			byte[] out = c.update(new byte[]{(byte) b});
			if (out == null || out.length != 1) {
				throw new IOException("Cipher stream out of sync");
			}
			return out[0] & 0xFF;
		}

		@Override
		public int read(final byte[] b, final int off, final int len) throws IOException {
			int n = this.in.read(b, off, len);
			Cipher c = this.cipher;
			if (n <= 0 || c == null) {
				return n;
			}
			try {
				int done = c.update(b, off, n, b, off); // CFB8: in-place, gleiche Länge
				if (done != n) {
					throw new IOException("Cipher stream out of sync (" + done + "/" + n + ")");
				}
			} catch (javax.crypto.ShortBufferException e) {
				throw new IOException(e);
			}
			return n;
		}

		@Override
		public long skip(final long n) throws IOException {
			// Überspringen würde den Chiffrezustand verlieren: Bytes lesen und verwerfen
			byte[] tmp = new byte[(int) Math.min(8192, Math.max(0, n))];
			long left = n;
			while (left > 0) {
				int r = read(tmp, 0, (int) Math.min(tmp.length, left));
				if (r < 0) {
					break;
				}
				left -= r;
			}
			return n - left;
		}

		@Override
		public boolean markSupported() {
			return false;
		}

		@Override
		public int available() throws IOException {
			return this.in.available();
		}

		@Override
		public void close() throws IOException {
			this.in.close();
		}
	}

	/** Ausgang: verschlüsselt ab {@link #enable}. Aufrufer synchronisieren (Connection#sendLock). */
	public static final class Encrypting extends OutputStream {
		private final OutputStream out;
		private Cipher cipher;

		public Encrypting(final OutputStream out) {
			this.out = out;
		}

		public void enable(final Cipher encrypt) {
			if (this.cipher != null) {
				throw new IllegalStateException("Encryption already enabled");
			}
			this.cipher = encrypt;
		}

		public boolean enabled() {
			return this.cipher != null;
		}

		@Override
		public void write(final int b) throws IOException {
			write(new byte[]{(byte) b}, 0, 1);
		}

		@Override
		public void write(final byte[] b, final int off, final int len) throws IOException {
			Cipher c = this.cipher;
			if (c == null) {
				this.out.write(b, off, len);
				return;
			}
			byte[] enc = c.update(b, off, len);
			if (enc == null || enc.length != len) {
				throw new IOException("Cipher stream out of sync");
			}
			this.out.write(enc);
		}

		@Override
		public void flush() throws IOException {
			this.out.flush();
		}

		@Override
		public void close() throws IOException {
			this.out.close();
		}
	}
}
