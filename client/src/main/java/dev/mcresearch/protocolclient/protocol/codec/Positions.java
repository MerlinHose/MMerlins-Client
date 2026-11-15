package dev.mcresearch.protocolclient.protocol.codec;

/**
 * Gepackte Positionen (docs/18 §6):
 * BlockPos = x 26 Bit (Offset 38) | z 26 Bit (Offset 12) | y 12 Bit (Offset 0);
 * SectionPos = x 22 Bit (Offset 42) | z 22 Bit (Offset 20) | y 20 Bit (Offset 0); jeweils vorzeichenbehaftet.
 */
public final class Positions {
	private Positions() {
	}

	public static int blockX(final long packed) {
		return (int) (packed >> 38);
	}

	public static int blockY(final long packed) {
		return (int) (packed << 52 >> 52);
	}

	public static int blockZ(final long packed) {
		return (int) (packed << 26 >> 38);
	}

	public static long packBlock(final int x, final int y, final int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
	}

	public static int sectionX(final long packed) {
		return (int) (packed >> 42);
	}

	public static int sectionY(final long packed) {
		return (int) (packed << 44 >> 44);
	}

	public static int sectionZ(final long packed) {
		return (int) (packed << 22 >> 42);
	}

	public static long packSection(final int x, final int y, final int z) {
		return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFFL);
	}
}
