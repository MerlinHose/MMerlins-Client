package dev.mcresearch.protocolclient.protocol.codec;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Liest Protokollfelder aus einem vollständig empfangenen Paketkörper und prüft dabei jede Länge
 * gegen die verbleibenden Bytes und die in docs/12 dokumentierten Grenzen.
 */
public final class PacketReader {
	private final byte[] data;
	private final int end;
	private int pos;

	public PacketReader(final byte[] data) {
		this(data, 0, data.length);
	}

	public PacketReader(final byte[] data, final int offset, final int length) {
		if (offset < 0 || length < 0 || offset + length > data.length) {
			throw new IllegalArgumentException("Invalid slice");
		}
		this.data = data;
		this.pos = offset;
		this.end = offset + length;
	}

	public int remaining() {
		return this.end - this.pos;
	}

	public int position() {
		return this.pos;
	}

	private void require(final int bytes, final String what) throws ProtocolException {
		if (bytes < 0 || bytes > remaining()) {
			throw new ProtocolException("Not enough data for " + what + ": need " + bytes + ", have " + remaining());
		}
	}

	public byte readByte() throws ProtocolException {
		require(1, "byte");
		return this.data[this.pos++];
	}

	public int readUnsignedByte() throws ProtocolException {
		return readByte() & 0xFF;
	}

	/** Strenge Prüfung: nur 0 oder 1 sind gültig. */
	public boolean readBoolean() throws ProtocolException {
		int b = readUnsignedByte();
		if (b > 1) {
			throw new ProtocolException("Invalid boolean value " + b);
		}
		return b == 1;
	}

	public short readShort() throws ProtocolException {
		require(2, "short");
		short v = (short) (((this.data[this.pos] & 0xFF) << 8) | (this.data[this.pos + 1] & 0xFF));
		this.pos += 2;
		return v;
	}

	public int readUnsignedShort() throws ProtocolException {
		return readShort() & 0xFFFF;
	}

	public int readInt() throws ProtocolException {
		require(4, "int");
		int v = ByteBuffer.wrap(this.data, this.pos, 4).getInt();
		this.pos += 4;
		return v;
	}

	public long readLong() throws ProtocolException {
		require(8, "long");
		long v = ByteBuffer.wrap(this.data, this.pos, 8).getLong();
		this.pos += 8;
		return v;
	}

	public float readFloat() throws ProtocolException {
		return Float.intBitsToFloat(readInt());
	}

	public double readDouble() throws ProtocolException {
		return Double.longBitsToDouble(readLong());
	}

	public int readVarInt() throws ProtocolException {
		int value = 0;
		for (int i = 0; i < VarInts.MAX_VARINT_BYTES; i++) {
			int b = readUnsignedByte();
			value |= (b & 0x7F) << (7 * i);
			if ((b & 0x80) == 0) {
				return value;
			}
		}
		throw new ProtocolException("VarInt too big");
	}

	public long readVarLong() throws ProtocolException {
		long value = 0;
		for (int i = 0; i < VarInts.MAX_VARLONG_BYTES; i++) {
			int b = readUnsignedByte();
			value |= (long) (b & 0x7F) << (7 * i);
			if ((b & 0x80) == 0) {
				return value;
			}
		}
		throw new ProtocolException("VarLong too big");
	}

	/** VarInt, das nicht negativ sein darf (Längen, Anzahlen). */
	public int readNonNegativeVarInt(final String what) throws ProtocolException {
		int v = readVarInt();
		if (v < 0) {
			throw new ProtocolException("Negative " + what + ": " + v);
		}
		return v;
	}

	/**
	 * Anzahl für Listen/Mengen: ≤ max und ≤ verbleibende Bytes / minimale Elementgröße
	 * (verhindert Speicherreservierung für nicht vorhandene Elemente).
	 */
	public int readCount(final int max, final int minElementBytes, final String what) throws ProtocolException {
		int count = readNonNegativeVarInt(what + " count");
		if (count > max) {
			throw new ProtocolException(what + " count " + count + " exceeds maximum " + max);
		}
		if (minElementBytes > 0 && (long) count * minElementBytes > remaining()) {
			throw new ProtocolException(what + " count " + count + " exceeds remaining data (" + remaining() + " bytes)");
		}
		return count;
	}

	/** UTF-8-String mit VarInt-Bytelänge; Bytelänge ≤ 3·maxChars, Zeichenzahl ≤ maxChars (wie Utf8String#read). */
	public String readString(final int maxChars) throws ProtocolException {
		int byteLength = readNonNegativeVarInt("string length");
		long maxBytes = (long) maxChars * 3;
		if (byteLength > maxBytes) {
			throw new ProtocolException("String byte length " + byteLength + " exceeds maximum " + maxBytes);
		}
		require(byteLength, "string");
		String s;
		try {
			s = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(this.data, this.pos, byteLength))
				.toString();
		} catch (CharacterCodingException e) {
			throw new ProtocolException("Invalid UTF-8 in string", e);
		}
		this.pos += byteLength;
		if (s.length() > maxChars) {
			throw new ProtocolException("String length " + s.length() + " exceeds maximum " + maxChars);
		}
		return s;
	}

	public String readIdentifier() throws ProtocolException {
		return Identifiers.normalize(readString(32767));
	}

	public UUID readUuid() throws ProtocolException {
		return new UUID(readLong(), readLong());
	}

	public byte[] readByteArray(final int maxLength) throws ProtocolException {
		int length = readNonNegativeVarInt("byte array length");
		if (length > maxLength) {
			throw new ProtocolException("Byte array length " + length + " exceeds maximum " + maxLength);
		}
		return readBytes(length);
	}

	public byte[] readBytes(final int length) throws ProtocolException {
		require(length, "bytes");
		byte[] out = new byte[length];
		System.arraycopy(this.data, this.pos, out, 0, length);
		this.pos += length;
		return out;
	}

	public void skip(final int length) throws ProtocolException {
		require(length, "skip");
		this.pos += length;
	}

	/** Rest des Pakets (z. B. Custom-Payload-Daten) mit Obergrenze. */
	public byte[] readRemaining(final int maxLength) throws ProtocolException {
		if (remaining() > maxLength) {
			throw new ProtocolException("Payload of " + remaining() + " bytes exceeds maximum " + maxLength);
		}
		return readBytes(remaining());
	}

	/** Wie PacketDecoder in Vanilla: nach dem Dekodieren dürfen keine Bytes übrig sein. */
	public void expectEnd(final String packetName) throws ProtocolException {
		if (remaining() != 0) {
			throw new ProtocolException("Packet " + packetName + " has " + remaining() + " unexpected trailing bytes");
		}
	}
}
