package dev.mcresearch.protocolclient.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PacketReaderWriterTest {
	@Test
	void primitivesRoundTrip() throws ProtocolException {
		UUID uuid = UUID.fromString("6842cb30-cc68-3262-af15-9d4eab81f4ed");
		byte[] bytes = new PacketWriter()
			.writeBoolean(true).writeByte(-5).writeShort(25565).writeInt(-123456).writeLong(Long.MAX_VALUE)
			.writeFloat(1.5f).writeDouble(-64.25).writeUuid(uuid).writeString("ResearchBot", 16).toByteArray();
		PacketReader r = new PacketReader(bytes);
		assertTrue(r.readBoolean());
		assertEquals(-5, r.readByte());
		assertEquals(25565, r.readUnsignedShort());
		assertEquals(-123456, r.readInt());
		assertEquals(Long.MAX_VALUE, r.readLong());
		assertEquals(1.5f, r.readFloat());
		assertEquals(-64.25, r.readDouble());
		assertEquals(uuid, r.readUuid());
		assertEquals("ResearchBot", r.readString(16));
		r.expectEnd("test");
	}

	@Test
	void booleanOnlyZeroOrOne() {
		assertThrows(ProtocolException.class, () -> new PacketReader(new byte[]{2}).readBoolean());
	}

	@Test
	void stringCharLimitEnforced() {
		byte[] seventeen = new PacketWriter().writeString("A".repeat(17), 17).toByteArray();
		assertThrows(ProtocolException.class, () -> new PacketReader(seventeen).readString(16));
	}

	@Test
	void stringByteLimitEnforcedBeforeDecoding() {
		// deklarierte Länge 49 Bytes > 3·16 -> Ablehnung ohne die Bytes zu lesen
		byte[] data = new byte[]{49};
		ProtocolException e = assertThrows(ProtocolException.class, () -> new PacketReader(data).readString(16));
		assertTrue(e.getMessage().contains("exceeds maximum 48"));
	}

	@Test
	void stringLongerThanRemainingRejected() {
		assertThrows(ProtocolException.class, () -> new PacketReader(new byte[]{5, 'a', 'b'}).readString(100));
	}

	@Test
	void invalidUtf8Rejected() {
		assertThrows(ProtocolException.class, () -> new PacketReader(new byte[]{2, (byte) 0xC3, 0x28}).readString(10));
	}

	@Test
	void multiByteCharactersCountedAsChars() throws ProtocolException {
		byte[] bytes = new PacketWriter().writeString("ÄÖÜ€", 4).toByteArray();
		assertEquals("ÄÖÜ€", new PacketReader(bytes).readString(4));
	}

	@Test
	void countLimitedByMaxAndRemainingBytes() {
		byte[] hugeCount = new PacketWriter().writeVarInt(1_000_000).toByteArray();
		assertThrows(ProtocolException.class, () -> new PacketReader(hugeCount).readCount(Integer.MAX_VALUE, 1, "entries"));
		byte[] overMax = new PacketWriter().writeVarInt(17).writeBytes(new byte[100]).toByteArray();
		assertThrows(ProtocolException.class, () -> new PacketReader(overMax).readCount(16, 1, "properties"));
		byte[] negative = new PacketWriter().writeVarInt(-1).toByteArray();
		assertThrows(ProtocolException.class, () -> new PacketReader(negative).readCount(16, 1, "x"));
	}

	@Test
	void trailingBytesDetected() throws ProtocolException {
		PacketReader r = new PacketReader(new byte[]{1, 2});
		r.readByte();
		assertThrows(ProtocolException.class, () -> r.expectEnd("x"));
	}

	@Test
	void identifierValidation() throws ProtocolException {
		assertEquals("minecraft:brand", Identifiers.normalize("brand"));
		assertEquals("minecraft:brand", Identifiers.normalize(":brand"));
		assertEquals("minecraft:worldgen/biome", Identifiers.normalize("minecraft:worldgen/biome"));
		assertThrows(ProtocolException.class, () -> Identifiers.normalize("Minecraft:brand"));
		assertThrows(ProtocolException.class, () -> Identifiers.normalize("minecraft:bad path"));
		assertThrows(ProtocolException.class, () -> Identifiers.normalize("../x:y"));
		assertThrows(ProtocolException.class, () -> Identifiers.normalize("..:y"));
		assertFalse(Identifiers.normalize("a.b-c_d:e/f").isEmpty());
	}

	@Test
	void readerRejectsReadingPastEnd() {
		assertThrows(ProtocolException.class, () -> new PacketReader(new byte[3]).readInt());
		assertThrows(ProtocolException.class, () -> new PacketReader(new byte[]{5, 1, 2}).readByteArray(100));
		assertThrows(ProtocolException.class, () -> new PacketReader(new byte[]{3, 1, 2, 3}).readByteArray(2));
	}
}
