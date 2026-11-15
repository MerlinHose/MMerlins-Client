package dev.mcresearch.protocolclient.net;

import java.util.Locale;

/**
 * Transportzähler einer Verbindung (docs/16 §6).
 *
 * @param threshold      aktiver Kompressionsschwellenwert, −1 = Kompression aus
 * @param framesIn       empfangene Frames gesamt
 * @param compressedIn   davon mit zlib-Inhalt (Header dataLength ≠ 0)
 * @param framesOut      gesendete Frames gesamt
 * @param compressedOut  davon komprimiert gesendet
 * @param wireBytesIn    Bytes der Frame-Inhalte auf dem Draht (ohne Längenpräfix)
 * @param packetBytesIn  Bytes nach Dekompression (Paket-ID + Felder)
 */
public record TransportStats(int threshold, long framesIn, long compressedIn, long framesOut, long compressedOut,
	long wireBytesIn, long packetBytesIn, long wireBytesOut, long packetBytesOut) {
	public static final TransportStats NONE = new TransportStats(-1, 0, 0, 0, 0, 0, 0, 0, 0);

	public long uncompressedIn() {
		return this.framesIn - this.compressedIn;
	}

	public long uncompressedOut() {
		return this.framesOut - this.compressedOut;
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT,
			"compression threshold=%d | in: %d frames (%d compressed, %d uncompressed), %d wire bytes -> %d packet bytes"
				+ " | out: %d frames (%d compressed, %d uncompressed), %d packet bytes -> %d wire bytes",
			this.threshold, this.framesIn, this.compressedIn, uncompressedIn(), this.wireBytesIn, this.packetBytesIn,
			this.framesOut, this.compressedOut, uncompressedOut(), this.packetBytesOut, this.wireBytesOut);
	}
}
