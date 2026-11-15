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
import dev.mcresearch.protocolclient.protocol.packet.ServerboundPacket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class NetLayerTest {
	// ---------- Framing ----------

	@Test
	void frameRoundTrip() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		FrameCodec.writeFrame(out, new byte[]{0x00, 0x01, 0x02});
		assertArrayEquals(new byte[]{3, 0x00, 0x01, 0x02}, out.toByteArray());
		assertArrayEquals(new byte[]{0x00, 0x01, 0x02}, FrameCodec.readFrame(new ByteArrayInputStream(out.toByteArray())));
	}

	@Test
	void frameLengthZeroRejected() {
		assertThrows(ProtocolException.class, () -> FrameCodec.readFrame(new ByteArrayInputStream(new byte[]{0})));
	}

	@Test
	void frameLengthWiderThan21BitRejected() {
		byte[] fourByteLength = {(byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01};
		assertThrows(ProtocolException.class, () -> FrameCodec.readFrame(new ByteArrayInputStream(fourByteLength)));
	}

	@Test
	void truncatedFrameDetected() {
		assertThrows(EOFException.class, () -> FrameCodec.readFrame(new ByteArrayInputStream(new byte[]{5, 1, 2})));
		assertThrows(EOFException.class, () -> FrameCodec.readFrame(new ByteArrayInputStream(new byte[0])));
	}

	@Test
	void consecutiveFramesAreSeparated() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		FrameCodec.writeFrame(out, new byte[]{1});
		FrameCodec.writeFrame(out, new byte[200]);
		InputStream in = new ByteArrayInputStream(out.toByteArray());
		assertEquals(1, FrameCodec.readFrame(in).length);
		assertEquals(200, FrameCodec.readFrame(in).length);
	}

	// ---------- Kompression ----------

	@Test
	void compressionRoundTripBelowAndAboveThreshold() throws ProtocolException {
		CompressionCodec codec = new CompressionCodec(256);
		byte[] small = new byte[10];
		byte[] encodedSmall = codec.encode(small);
		assertEquals(0, encodedSmall[0]); // dataLength 0 = unkomprimiert
		assertArrayEquals(small, codec.decode(encodedSmall));
		byte[] big = new byte[5000];
		new Random(1).nextBytes(big);
		assertArrayEquals(big, codec.decode(codec.encode(big)));
	}

	@Test
	void compressedPacketBelowThresholdRejected() throws ProtocolException {
		CompressionCodec codec = new CompressionCodec(256);
		byte[] encoded = new CompressionCodec(0).encode(new byte[100]); // als komprimiert markiert, aber < 256
		assertThrows(ProtocolException.class, () -> codec.decode(encoded));
	}

	@Test
	void uncompressedLengthAboveProtocolMaximumRejected() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		VarInts.write(out, CompressionCodec.MAX_UNCOMPRESSED + 1);
		out.writeBytes(new byte[]{0x78, (byte) 0x9C});
		assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(out.toByteArray()));
	}

	@Test
	void sizeMismatchAndGarbageRejected() {
		byte[] payload = new byte[1000];
		Deflater d = new Deflater();
		d.setInput(payload);
		d.finish();
		byte[] buf = new byte[2000];
		int n = d.deflate(buf);
		d.end();
		ByteArrayOutputStream wrongLength = new ByteArrayOutputStream();
		VarInts.write(wrongLength, 1001); // behauptet 1001, liefert 1000
		wrongLength.write(buf, 0, n);
		assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(wrongLength.toByteArray()));
		ByteArrayOutputStream garbage = new ByteArrayOutputStream();
		VarInts.write(garbage, 300);
		garbage.writeBytes(new byte[]{1, 2, 3, 4, 5});
		assertThrows(ProtocolException.class, () -> new CompressionCodec(0).decode(garbage.toByteArray()));
	}

	// ---------- Loopback-Schutz ----------

	@Test
	void onlyLoopbackAllowedWithoutDnsLookups() throws IOException {
		assertTrue(LoopbackGuard.resolveLoopback("localhost").isLoopbackAddress());
		assertTrue(LoopbackGuard.resolveLoopback("127.0.0.1").isLoopbackAddress());
		assertTrue(LoopbackGuard.resolveLoopback("::1").isLoopbackAddress());
		assertThrows(IllegalArgumentException.class, () -> LoopbackGuard.resolveLoopback("192.168.1.10"));
		assertThrows(IllegalArgumentException.class, () -> LoopbackGuard.resolveLoopback("8.8.8.8"));
		// Hostnamen werden ohne Auflösung abgewiesen
		assertThrows(IllegalArgumentException.class, () -> LoopbackGuard.resolveLoopback("example.org"));
		assertFalse(LoopbackGuard.isIpLiteral("999.1.1.1"));
		assertThrows(IllegalArgumentException.class, () -> Connection.open(InetAddress.getByName("10.0.0.1"), 25565, 100, 100, PacketLog.disabled()));
	}

	// ---------- Connection gegen lokalen Socket ----------

	private record TestPacket(String name) implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.HANDSHAKE;
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(777);
		}
	}

	@Test
	void connectionSendsFramedPacketsAndValidatesIncomingIds() throws Exception {
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Thread peer = Thread.ofPlatform().start(() -> {
				try (Socket s = server.accept()) {
					InputStream in = s.getInputStream();
					byte[] frame = FrameCodec.readFrame(in);
					PacketReader r = new PacketReader(frame);
					if (r.readVarInt() != 0 || r.readVarInt() != 777) {
						throw new AssertionError("unexpected packet");
					}
					OutputStream out = s.getOutputStream();
					FrameCodec.writeFrame(out, new PacketWriter().writeVarInt(0x01).writeLong(42L).toByteArray()); // pong_response
					FrameCodec.writeFrame(out, new PacketWriter().writeVarInt(0x7F).toByteArray()); // ungültige ID
					out.flush();
					in.read(); // bis Client schließt
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
			try (Connection c = Connection.open(InetAddress.getLoopbackAddress(), server.getLocalPort(), 1000, 2000, PacketLog.disabled())) {
				c.send(new TestPacket("minecraft:intention"));
				c.setOutboundState(ProtocolState.STATUS);
				c.setInboundState(ProtocolState.STATUS);
				assertThrows(ProtocolException.class, () -> c.send(new TestPacket("minecraft:intention")));
				RawPacket pong = c.receive();
				assertEquals("minecraft:pong_response", pong.name());
				assertEquals(42L, new PacketReader(pong.body()).readLong());
				assertEquals(9, pong.size());
				assertThrows(ProtocolException.class, c::receive);
			}
			peer.join(2000);
		}
	}
}
