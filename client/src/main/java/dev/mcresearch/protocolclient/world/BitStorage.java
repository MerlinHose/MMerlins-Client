package dev.mcresearch.protocolclient.world;

import dev.mcresearch.protocolclient.protocol.ProtocolException;

/**
 * Gepackte Werte wie {@code SimpleBitStorage} (docs/18 §4.4): {@code 64 / bits} Werte pro Long, kein Wert über
 * Long-Grenzen, Eintrag i in Long {@code i / perLong} ab Bit {@code (i % perLong) · bits}.
 */
public final class BitStorage {
	private BitStorage() {
	}

	public static int longsFor(final int bits, final int entries) {
		if (bits <= 0 || bits > 32) {
			throw new IllegalArgumentException("bits " + bits);
		}
		int perLong = 64 / bits;
		return (entries + perLong - 1) / perLong;
	}

	public static int[] unpack(final long[] data, final int bits, final int entries) throws ProtocolException {
		if (bits <= 0 || bits > 32) {
			throw new ProtocolException("Invalid bits per entry " + bits);
		}
		int perLong = 64 / bits;
		int expected = longsFor(bits, entries);
		if (data.length != expected) {
			throw new ProtocolException("Packed array has " + data.length + " longs, expected " + expected + " for " + bits + " bits");
		}
		long mask = (1L << bits) - 1;
		int[] out = new int[entries];
		for (int i = 0; i < entries; i++) {
			out[i] = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
		}
		return out;
	}

	public static long[] pack(final int[] values, final int bits) {
		int perLong = 64 / bits;
		long[] out = new long[longsFor(bits, values.length)];
		long mask = (1L << bits) - 1;
		for (int i = 0; i < values.length; i++) {
			if (((values[i] & 0xFFFFFFFFL) & ~mask) != 0) {
				throw new IllegalArgumentException("value " + values[i] + " does not fit into " + bits + " bits");
			}
			out[i / perLong] |= ((long) values[i] & mask) << ((i % perLong) * bits);
		}
		return out;
	}
}
