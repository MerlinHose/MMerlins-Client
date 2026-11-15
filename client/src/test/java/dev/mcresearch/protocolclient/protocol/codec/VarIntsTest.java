package dev.mcresearch.protocolclient.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VarIntsTest {
	@Test
	void encodesKnownValues() {
		assertArrayEquals(new byte[]{0x00}, VarInts.encode(0));
		assertArrayEquals(new byte[]{0x01}, VarInts.encode(1));
		assertArrayEquals(new byte[]{0x7F}, VarInts.encode(127));
		assertArrayEquals(new byte[]{(byte) 0x80, 0x01}, VarInts.encode(128));
		assertArrayEquals(new byte[]{(byte) 0x89, 0x06}, VarInts.encode(777)); // Protokollversion
		assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF, 0x7F}, VarInts.encode(2_097_151)); // max. Framelänge
		assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F}, VarInts.encode(-1));
		assertArrayEquals(new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x08}, VarInts.encode(Integer.MIN_VALUE));
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 127, 128, 255, 25565, 777, 2_097_151, 2_097_152, Integer.MAX_VALUE, -1, Integer.MIN_VALUE})
	void roundTripsThroughReaderAndStream(final int value) throws IOException {
		byte[] encoded = VarInts.encode(value);
		assertEquals(VarInts.size(value), encoded.length);
		assertEquals(value, new PacketReader(encoded).readVarInt());
		assertEquals(value, VarInts.read(new ByteArrayInputStream(encoded), VarInts.MAX_VARINT_BYTES));
	}

	@Test
	void rejectsOverlongVarInt() {
		byte[] sixBytes = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01};
		assertThrows(ProtocolException.class, () -> new PacketReader(sixBytes).readVarInt());
		assertThrows(ProtocolException.class, () -> VarInts.read(new ByteArrayInputStream(sixBytes), 5));
	}

	@Test
	void frameLengthLimitedToThreeBytes() {
		byte[] fourByteLength = {(byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01};
		assertThrows(ProtocolException.class, () -> VarInts.read(new ByteArrayInputStream(fourByteLength), 3));
	}

	@Test
	void truncatedVarIntIsDetected() {
		assertThrows(EOFException.class, () -> VarInts.read(new ByteArrayInputStream(new byte[]{(byte) 0x80}), 5));
		assertThrows(ProtocolException.class, () -> new PacketReader(new byte[]{(byte) 0x80}).readVarInt());
	}

	@Test
	void varLongRoundTrip() throws ProtocolException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		VarInts.writeLong(out, Long.MIN_VALUE);
		VarInts.writeLong(out, 1234567890123L);
		PacketReader reader = new PacketReader(out.toByteArray());
		assertEquals(Long.MIN_VALUE, reader.readVarLong());
		assertEquals(1234567890123L, reader.readVarLong());
	}
}
