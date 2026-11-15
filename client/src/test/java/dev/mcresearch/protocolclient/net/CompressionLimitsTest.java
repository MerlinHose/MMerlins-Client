package dev.mcresearch.protocolclient.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.RawPacket;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;
import dev.mcresearch.protocolclient.protocol.codec.VarInts;
import dev.mcresearch.protocolclient.protocol.packet.Serverbound;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/** Kompressionsgrenzen und Fehlerfälle (docs/16 §4–5). */
class CompressionLimitsTest {
	private static byte[] deflate(final byte[] data) {
		Deflater d = new Deflater();
		d.setInput(data);
		d.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		while (!d.finished()) {
			out.write(buf, 0, d.deflate(buf));
		}
		d.end();
		return out.toByteArray();
	}

	private static byte[] frame(final int declaredLength, final byte[] content) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		VarInts.write(out, declaredLength);
		out.writeBytes(content);
		return out.toByteArray();
	}

	// ---------- Schwellenwert-Semantik wie CompressionEncoder ("size < threshold" → unkomprimiert) ----------

	@Test
	void thresholdBoundaryMatchesVanillaEncoder() throws ProtocolException {
		CompressionCodec c = new CompressionCodec(256);
		byte[] below = c.encode(new byte[255]);
		assertFalse(CompressionCodec.isCompressedFrame(below));
		assertEquals(256, below.length); // Header 0 + 255 Bytes
		byte[] at = c.encode(new byte[256]);
		assertTrue(CompressionCodec.isCompressedFrame(at));
		assertEquals(256, new PacketReader(at).readVarInt()); // dataLength = unkomprimierte Länge
		assertArrayEquals(new byte[256], c.decode(at));
	}

	@Test
	void thresholdZeroCompressesEveryPacketIncludingOneByte() throws ProtocolException {
		CompressionCodec c = new CompressionCodec(0);
		byte[] oneByte = c.encode(new byte[]{0x0D}); // z. B. client_tick_end (nur Paket-ID)
		assertTrue(CompressionCodec.isCompressedFrame(oneByte));
		assertEquals(1, new PacketReader(oneByte).readVarInt());
		assertArrayEquals(new byte[]{0x0D}, c.decode(oneByte));
		// Vanilla-Server sendet bei Threshold 0 nie dataLength 0; ein 0-Header bleibt trotzdem gültig (unkomprimiert)
		assertArrayEquals(new byte[]{0x2D}, c.decode(new byte[]{0x00, 0x2D}));
	}

	@Test
	void negativeThresholdIsNotACompressionCodec() {
		assertThrows(IllegalArgumentException.class, () -> new CompressionCodec(-1));
	}

	// ---------- Grenzen VOR der Dekompression ----------

	@Test
	void declaredUncompressedLengthAboveEightMiBRejectedBeforeInflating() {
		byte[] f = frame(CompressionCodec.MAX_UNCOMPRESSED + 1, deflate(new byte[10]));
		ProtocolException e = assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(f));
		assertTrue(e.getMessage().contains("exceeds " + CompressionCodec.MAX_UNCOMPRESSED), e.getMessage());
	}

	@Test
	void compressedPayloadAboveTwoMiBRejectedBeforeInflating() {
		byte[] hugeCompressed = new byte[CompressionCodec.MAX_COMPRESSED + 1];
		byte[] f = frame(300, hugeCompressed);
		ProtocolException e = assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(f));
		assertTrue(e.getMessage().contains("Compressed length"), e.getMessage());
	}

	@Test
	void frameLimitAppliesToCompressedFrameOnTheWire() {
		// Längenpräfix 2 097 152 passt nicht in 3 VarInt-Bytes → abgewiesen, bevor Daten gelesen werden
		byte[] lengthPrefix = VarInts.encode(FrameCodec.MAX_FRAME_LENGTH + 1);
		assertEquals(4, lengthPrefix.length);
		assertThrows(ProtocolException.class, () -> FrameCodec.readFrame(new ByteArrayInputStream(lengthPrefix)));
	}

	@Test
	void compressedFlagBelowThresholdRejected() {
		byte[] f = frame(100, deflate(new byte[100]));
		assertThrows(ProtocolException.class, () -> new CompressionCodec(256).decode(f));
	}

	@Test
	void negativeOrOverlongDataLengthRejected() {
		assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(frame(-1, new byte[4])));
		byte[] overlong = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01};
		assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(overlong));
	}

	// ---------- Grenzen NACH der Dekompression ----------

	@Test
	void inflatedDataLongerThanDeclaredRejected() {
		byte[] f = frame(500, deflate(new byte[1000])); // liefert mehr als deklariert
		ProtocolException e = assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(f));
		assertTrue(e.getMessage().contains("mismatch") || e.getMessage().contains("not finished"), e.getMessage());
	}

	@Test
	void inflatedDataShorterThanDeclaredRejected() {
		byte[] f = frame(1001, deflate(new byte[1000]));
		assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(f));
	}

	@Test
	void decompressionBombIsBoundedByDeclaredLength() {
		// 8 MiB Nullen komprimieren auf wenige KiB; deklariert wird nur 1 000 → es wird nie mehr als 1 000 Byte allokiert/gelesen
		byte[] bomb = deflate(new byte[CompressionCodec.MAX_UNCOMPRESSED]);
		assertTrue(bomb.length < 20_000);
		assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(frame(1000, bomb)));
	}

	@Test
	void maximumSizePacketRoundTripsWhenItCompressesIntoOneFrame() throws IOException {
		// 8 MiB gut komprimierbare Daten: komprimiert weit unter dem Frame-Limit → zulässig
		byte[] max = new byte[CompressionCodec.MAX_UNCOMPRESSED];
		Arrays.fill(max, (byte) 7);
		CompressionCodec c = new CompressionCodec(256);
		byte[] encoded = c.encode(max);
		assertTrue(encoded.length <= FrameCodec.MAX_FRAME_LENGTH);
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		FrameCodec.writeFrame(wire, encoded);
		assertArrayEquals(max, c.decode(FrameCodec.readFrame(new ByteArrayInputStream(wire.toByteArray()))));
	}

	@Test
	void incompressibleMaximumPacketDoesNotFitIntoAFrame() throws ProtocolException {
		// Laufzeit-/Testbefund: 8 MiB Zufallsdaten komprimieren auf > 2 MiB → nicht übertragbar (Frame-Limit 2 097 151 B).
		// Effektive Obergrenze ist also "≤ 8 MiB unkomprimiert UND ≤ 2 097 151 B komprimiert".
		byte[] random = new byte[CompressionCodec.MAX_UNCOMPRESSED];
		new Random(7).nextBytes(random);
		byte[] encoded = new CompressionCodec(256).encode(random);
		assertTrue(encoded.length > FrameCodec.MAX_FRAME_LENGTH);
		assertThrows(ProtocolException.class, () -> FrameCodec.writeFrame(new ByteArrayOutputStream(), encoded));
		assertThrows(ProtocolException.class, () -> new CompressionCodec(256).decode(encoded));
	}

	// ---------- fehlerhafte / unvollständige / überzählige Daten ----------

	@Test
	void malformedZlibRejected() {
		byte[] garbage = {0x13, 0x37, (byte) 0xBE, (byte) 0xEF, 0x00, 0x11};
		assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(frame(300, garbage)));
		byte[] badHeader = deflate(new byte[300]);
		badHeader[0] = 0x00; // ungültiger zlib-Header (CMF)
		assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(frame(300, badHeader)));
	}

	@Test
	void truncatedZlibStreamRejected() {
		byte[] full = deflate(new byte[300]);
		byte[] truncated = Arrays.copyOf(full, full.length - 4); // Adler-32-Prüfsumme fehlt
		assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(frame(300, truncated)));
		assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(frame(300, new byte[0])));
	}

	@Test
	void corruptedChecksumRejected() {
		byte[] data = deflate(new byte[300]);
		data[data.length - 1] ^= 0x55;
		assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(frame(300, data)));
	}

	@Test
	void trailingBytesAfterZlibStreamRejected() {
		byte[] data = deflate(new byte[300]);
		byte[] withTrailer = Arrays.copyOf(data, data.length + 3);
		ProtocolException e = assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(frame(300, withTrailer)));
		assertTrue(e.getMessage().contains("after zlib stream"), e.getMessage());
	}

	@Test
	void oversizedOutgoingPacketRejected() {
		assertThrows(ProtocolException.class, () -> new CompressionCodec(256).encode(new byte[CompressionCodec.MAX_UNCOMPRESSED + 1]));
	}

	// ---------- Ende-zu-Ende über einen lokalen Socket ----------

	@Test
	void connectionCompressesAboveThresholdAndCountsFrames() throws Exception {
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Thread peer = Thread.ofPlatform().start(() -> {
				try (Socket s = server.accept()) {
					InputStream in = s.getInputStream();
					OutputStream out = s.getOutputStream();
					CompressionCodec c = new CompressionCodec(64);
					// 1) unkomprimiertes Paket vom Client (keep_alive, 9 Byte < 64)
					byte[] f1 = FrameCodec.readFrame(in);
					if (CompressionCodec.isCompressedFrame(f1)) {
						throw new AssertionError("small packet must not be compressed");
					}
					// 2) Antwort: großes Paket (keep_alive ist 9 B; daher custom-payload-ähnliche Größe über cookie_request)
					PacketWriter big = new PacketWriter().writeVarInt(0x15).writeIdentifier("minecraft:" + "a".repeat(200));
					FrameCodec.writeFrame(out, c.encode(big.toByteArray()));
					PacketWriter small = new PacketWriter().writeVarInt(0x2D).writeLong(99L);
					FrameCodec.writeFrame(out, c.encode(small.toByteArray()));
					out.flush();
					in.read();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
			try (Connection c = Connection.open(InetAddress.getLoopbackAddress(), server.getLocalPort(), 1000, 2000, PacketLog.disabled())) {
				c.setOutboundState(ProtocolState.PLAY);
				c.setInboundState(ProtocolState.PLAY);
				c.setCompressionThreshold(64);
				c.send(new Serverbound.KeepAlive(ProtocolState.PLAY, 1L));
				RawPacket cookie = c.receive();
				assertEquals("minecraft:cookie_request", cookie.name());
				RawPacket ka = c.receive();
				assertEquals("minecraft:keep_alive", ka.name());
				assertEquals(99L, new PacketReader(ka.body()).readLong());
				TransportStats t = c.transportStats();
				assertEquals(64, t.threshold());
				assertEquals(2, t.framesIn());
				assertEquals(1, t.compressedIn());
				assertEquals(1, t.uncompressedIn());
				assertEquals(1, t.framesOut());
				assertEquals(0, t.compressedOut());
				assertTrue(t.wireBytesIn() < t.packetBytesIn(), t.toString()); // großes Paket wurde komprimiert übertragen
				c.setCompressionThreshold(-1);
				assertEquals(-1, c.transportStats().threshold());
			}
			peer.join(2000);
		}
	}
}
