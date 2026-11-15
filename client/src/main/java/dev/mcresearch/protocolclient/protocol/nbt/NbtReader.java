package dev.mcresearch.protocolclient.protocol.nbt;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest Netzwerk-NBT (docs/12 §2): ein Typ-Byte, danach der Payload <b>ohne</b> Root-Namen; Typ 0 bedeutet "kein Tag".
 *
 * <p>Ergebnis als einfache Java-Werte: Byte, Short, Integer, Long, Float, Double, byte[], String, List, Map, int[], long[].
 * Grenzen: Verschachtelungstiefe ≤ 512 (wie NbtAccounter), alle Längen ≥ 0 und durch die verbleibenden Bytes gedeckt.
 */
public final class NbtReader {
	public static final int MAX_DEPTH = 512;

	private static final int END = 0;
	private static final int BYTE = 1;
	private static final int SHORT = 2;
	private static final int INT = 3;
	private static final int LONG = 4;
	private static final int FLOAT = 5;
	private static final int DOUBLE = 6;
	private static final int BYTE_ARRAY = 7;
	private static final int STRING = 8;
	private static final int LIST = 9;
	private static final int COMPOUND = 10;
	private static final int INT_ARRAY = 11;
	private static final int LONG_ARRAY = 12;

	private final PacketReader in;

	private NbtReader(final PacketReader in) {
		this.in = in;
	}

	/** Liest ein Netzwerk-NBT-Tag; {@code null}, wenn das Typ-Byte 0 (END) ist. */
	public static Object readNetworkTag(final PacketReader in) throws ProtocolException {
		int type = in.readUnsignedByte();
		if (type == END) {
			return null;
		}
		return new NbtReader(in).readPayload(type, 0);
	}

	private Object readPayload(final int type, final int depth) throws ProtocolException {
		if (depth > MAX_DEPTH) {
			throw new ProtocolException("NBT nesting deeper than " + MAX_DEPTH);
		}
		return switch (type) {
			case BYTE -> this.in.readByte();
			case SHORT -> this.in.readShort();
			case INT -> this.in.readInt();
			case LONG -> this.in.readLong();
			case FLOAT -> this.in.readFloat();
			case DOUBLE -> this.in.readDouble();
			case BYTE_ARRAY -> this.in.readBytes(length(1, "byte array"));
			case STRING -> readModifiedUtf8();
			case LIST -> readList(depth);
			case COMPOUND -> readCompound(depth);
			case INT_ARRAY -> {
				int n = length(4, "int array");
				int[] values = new int[n];
				for (int i = 0; i < n; i++) {
					values[i] = this.in.readInt();
				}
				yield values;
			}
			case LONG_ARRAY -> {
				int n = length(8, "long array");
				long[] values = new long[n];
				for (int i = 0; i < n; i++) {
					values[i] = this.in.readLong();
				}
				yield values;
			}
			default -> throw new ProtocolException("Invalid NBT tag type " + type);
		};
	}

	private int length(final int elementBytes, final String what) throws ProtocolException {
		int n = this.in.readInt();
		if (n < 0) {
			throw new ProtocolException("Negative NBT " + what + " length " + n);
		}
		if ((long) n * elementBytes > this.in.remaining()) {
			throw new ProtocolException("NBT " + what + " length " + n + " exceeds remaining data");
		}
		return n;
	}

	private List<Object> readList(final int depth) throws ProtocolException {
		int elementType = this.in.readUnsignedByte();
		int n = this.in.readInt();
		if (n < 0) {
			throw new ProtocolException("Negative NBT list length " + n);
		}
		if (elementType == END && n > 0) {
			throw new ProtocolException("NBT list of END tags with length " + n);
		}
		// jedes Element belegt mindestens 1 Byte (Ausnahme: leere Compounds belegen genau 1 Byte)
		if (n > this.in.remaining()) {
			throw new ProtocolException("NBT list length " + n + " exceeds remaining data");
		}
		List<Object> list = new ArrayList<>(Math.min(n, 1024));
		for (int i = 0; i < n; i++) {
			list.add(readPayload(elementType, depth + 1));
		}
		return list;
	}

	private Map<String, Object> readCompound(final int depth) throws ProtocolException {
		Map<String, Object> map = new LinkedHashMap<>();
		while (true) {
			int type = this.in.readUnsignedByte();
			if (type == END) {
				return map;
			}
			String name = readModifiedUtf8();
			map.put(name, readPayload(type, depth + 1));
		}
	}

	/** NBT-Strings: unsigned short Bytelänge + "modified UTF-8" (wie DataInput#readUTF). */
	private String readModifiedUtf8() throws ProtocolException {
		int length = this.in.readUnsignedShort();
		byte[] bytes = this.in.readBytes(length);
		byte[] withPrefix = new byte[length + 2];
		withPrefix[0] = (byte) (length >>> 8);
		withPrefix[1] = (byte) length;
		System.arraycopy(bytes, 0, withPrefix, 2, length);
		try {
			return new DataInputStream(new ByteArrayInputStream(withPrefix)).readUTF();
		} catch (IOException e) {
			throw new ProtocolException("Invalid NBT string", e);
		}
	}
}
