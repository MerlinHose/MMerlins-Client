package dev.mcresearch.protocolclient.protocol.codec;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** VarInt/VarLong-Kodierung (7 Datenbits pro Byte, niederwertigste Gruppe zuerst, Bit 7 = Fortsetzung). docs/12 §2. */
public final class VarInts {
	public static final int MAX_VARINT_BYTES = 5;
	public static final int MAX_VARLONG_BYTES = 10;

	private VarInts() {
	}

	/** Liest ein VarInt aus einem Stream. Wirft EOFException bei Streamende vor dem ersten Byte bzw. mitten im Wert. */
	public static int read(final InputStream in, final int maxBytes) throws IOException {
		if (maxBytes < 1 || maxBytes > MAX_VARINT_BYTES) {
			throw new IllegalArgumentException("maxBytes " + maxBytes);
		}
		int value = 0;
		for (int i = 0; i < maxBytes; i++) {
			int b = in.read();
			if (b < 0) {
				throw new EOFException(i == 0 ? "End of stream" : "End of stream inside VarInt");
			}
			value |= (b & 0x7F) << (7 * i);
			if ((b & 0x80) == 0) {
				return value;
			}
		}
		throw new ProtocolException("VarInt longer than " + maxBytes + " bytes");
	}

	public static int size(final int value) {
		int size = 1;
		int v = value;
		while ((v & ~0x7F) != 0) {
			size++;
			v >>>= 7;
		}
		return size;
	}

	public static void write(final ByteArrayOutputStream out, final int value) {
		int v = value;
		while ((v & ~0x7F) != 0) {
			out.write((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		out.write(v);
	}

	public static byte[] encode(final int value) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(MAX_VARINT_BYTES);
		write(out, value);
		return out.toByteArray();
	}

	public static void writeLong(final ByteArrayOutputStream out, final long value) {
		long v = value;
		while ((v & ~0x7FL) != 0) {
			out.write((int) ((v & 0x7F) | 0x80));
			v >>>= 7;
		}
		out.write((int) v);
	}
}
