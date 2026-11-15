package dev.mcresearch.protocolclient.protocol.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Schreibt Protokollfelder in einen Puffer. Grenzen werden wie beim Lesen erzwungen (docs/12 §2). */
public final class PacketWriter {
	private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

	public PacketWriter writeByte(final int value) {
		this.out.write(value);
		return this;
	}

	public PacketWriter writeBoolean(final boolean value) {
		return writeByte(value ? 1 : 0);
	}

	public PacketWriter writeShort(final int value) {
		this.out.write((value >>> 8) & 0xFF);
		this.out.write(value & 0xFF);
		return this;
	}

	public PacketWriter writeInt(final int value) {
		for (int shift = 24; shift >= 0; shift -= 8) {
			this.out.write((value >>> shift) & 0xFF);
		}
		return this;
	}

	public PacketWriter writeLong(final long value) {
		for (int shift = 56; shift >= 0; shift -= 8) {
			this.out.write((int) ((value >>> shift) & 0xFF));
		}
		return this;
	}

	public PacketWriter writeFloat(final float value) {
		return writeInt(Float.floatToIntBits(value));
	}

	public PacketWriter writeDouble(final double value) {
		return writeLong(Double.doubleToLongBits(value));
	}

	public PacketWriter writeVarInt(final int value) {
		VarInts.write(this.out, value);
		return this;
	}

	public PacketWriter writeVarLong(final long value) {
		VarInts.writeLong(this.out, value);
		return this;
	}

	public PacketWriter writeString(final String value, final int maxChars) {
		if (value.length() > maxChars) {
			throw new IllegalArgumentException("String too long (" + value.length() + " > " + maxChars + ")");
		}
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > (long) maxChars * 3) {
			throw new IllegalArgumentException("String too long in bytes (" + bytes.length + ")");
		}
		writeVarInt(bytes.length);
		this.out.writeBytes(bytes);
		return this;
	}

	public PacketWriter writeIdentifier(final String identifier) {
		return writeString(identifier, 32767);
	}

	public PacketWriter writeUuid(final UUID uuid) {
		writeLong(uuid.getMostSignificantBits());
		return writeLong(uuid.getLeastSignificantBits());
	}

	public PacketWriter writeBytes(final byte[] bytes) {
		this.out.writeBytes(bytes);
		return this;
	}

	public byte[] toByteArray() {
		return this.out.toByteArray();
	}

	public int size() {
		return this.out.size();
	}
}
