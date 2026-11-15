package dev.mcresearch.protocolclient.net;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.codec.VarInts;
import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * zlib-Kompression nach {@code login_compression} (docs/12 §3).
 * Frame-Inhalt = {@code VarInt dataLength} + Daten; dataLength 0 = unkomprimiert,
 * sonst zlib-Daten mit genau dataLength unkomprimierten Bytes.
 */
public final class CompressionCodec {
	public static final int MAX_UNCOMPRESSED = 8_388_608;
	public static final int MAX_COMPRESSED = 2_097_152;

	private final int threshold;

	public CompressionCodec(final int threshold) {
		if (threshold < 0) {
			throw new IllegalArgumentException("threshold must be >= 0 when compression is enabled");
		}
		this.threshold = threshold;
	}

	public int threshold() {
		return this.threshold;
	}

	/** true, wenn der Frame-Header (VarInt dataLength) ≠ 0 ist, der Inhalt also zlib-komprimiert vorliegt. */
	public static boolean isCompressedFrame(final byte[] frame) throws ProtocolException {
		return new PacketReader(frame).readNonNegativeVarInt("uncompressed length") != 0;
	}

	public byte[] decode(final byte[] frame) throws ProtocolException {
		PacketReader reader = new PacketReader(frame);
		int dataLength = reader.readNonNegativeVarInt("uncompressed length");
		if (dataLength == 0) {
			return reader.readBytes(reader.remaining());
		}
		if (dataLength < this.threshold) {
			throw new ProtocolException("Compressed packet of " + dataLength + " bytes is below threshold " + this.threshold);
		}
		if (dataLength > MAX_UNCOMPRESSED) {
			throw new ProtocolException("Uncompressed length " + dataLength + " exceeds " + MAX_UNCOMPRESSED);
		}
		if (reader.remaining() > MAX_COMPRESSED) {
			throw new ProtocolException("Compressed length " + reader.remaining() + " exceeds " + MAX_COMPRESSED);
		}
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(frame, reader.position(), reader.remaining());
			byte[] out = new byte[dataLength];
			int total = 0;
			while (total < dataLength) {
				int n = inflater.inflate(out, total, dataLength - total);
				if (n == 0) {
					if (inflater.finished() || inflater.needsInput()) {
						break;
					}
					if (inflater.needsDictionary()) {
						throw new ProtocolException("zlib stream requires a dictionary");
					}
				}
				total += n;
			}
			if (total != dataLength || !inflater.finished()) {
				throw new ProtocolException("Decompressed size mismatch: expected " + dataLength + ", got " + total
					+ (inflater.finished() ? "" : " (stream not finished)"));
			}
			if (inflater.getRemaining() > 0) {
				throw new ProtocolException(inflater.getRemaining() + " unexpected bytes after zlib stream");
			}
			return out;
		} catch (DataFormatException e) {
			throw new ProtocolException("Invalid zlib data", e);
		} finally {
			inflater.end();
		}
	}

	/** Wie {@code CompressionEncoder#encode}: unter dem Schwellenwert unkomprimiert (Header 0), sonst zlib; > 8 MiB unzulässig. */
	public byte[] encode(final byte[] packet) throws ProtocolException {
		if (packet.length > MAX_UNCOMPRESSED) {
			throw new ProtocolException("Packet too big (" + packet.length + " > " + MAX_UNCOMPRESSED + ")");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(packet.length + 5);
		if (packet.length < this.threshold) {
			VarInts.write(out, 0);
			out.writeBytes(packet);
			return out.toByteArray();
		}
		VarInts.write(out, packet.length);
		Deflater deflater = new Deflater();
		try {
			deflater.setInput(packet);
			deflater.finish();
			byte[] buf = new byte[8192];
			while (!deflater.finished()) {
				int n = deflater.deflate(buf);
				out.write(buf, 0, n);
			}
		} finally {
			deflater.end();
		}
		return out.toByteArray();
	}
}
