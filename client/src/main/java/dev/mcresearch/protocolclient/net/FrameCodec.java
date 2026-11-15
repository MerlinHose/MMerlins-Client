package dev.mcresearch.protocolclient.net;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.codec.VarInts;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Paket-Framing: {@code VarInt länge} + {@code länge} Bytes (docs/12 §3).
 * Die Längenangabe darf höchstens 3 Bytes belegen (≤ 2 097 151) und nicht 0 sein.
 */
public final class FrameCodec {
	public static final int MAX_LENGTH_BYTES = 3;
	public static final int MAX_FRAME_LENGTH = (1 << 21) - 1;

	private FrameCodec() {
	}

	/** Liest einen vollständigen Frame. EOFException nur, wenn der Stream vor dem ersten Byte endet. */
	public static byte[] readFrame(final InputStream in) throws IOException {
		int length = VarInts.read(in, MAX_LENGTH_BYTES);
		if (length <= 0) {
			throw new ProtocolException("Invalid frame length " + length);
		}
		if (length > MAX_FRAME_LENGTH) {
			throw new ProtocolException("Frame length " + length + " exceeds " + MAX_FRAME_LENGTH);
		}
		byte[] frame = in.readNBytes(length);
		if (frame.length != length) {
			throw new EOFException("Connection closed inside frame (" + frame.length + "/" + length + " bytes)");
		}
		return frame;
	}

	public static void writeFrame(final OutputStream out, final byte[] content) throws IOException {
		if (content.length == 0 || content.length > MAX_FRAME_LENGTH) {
			throw new ProtocolException("Cannot frame " + content.length + " bytes");
		}
		ByteArrayOutputStream buf = new ByteArrayOutputStream(content.length + MAX_LENGTH_BYTES);
		VarInts.write(buf, content.length);
		buf.writeBytes(content);
		buf.writeTo(out);
	}
}
