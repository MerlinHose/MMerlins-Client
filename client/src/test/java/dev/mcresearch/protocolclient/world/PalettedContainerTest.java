package dev.mcresearch.protocolclient.world;

import static dev.mcresearch.protocolclient.world.PalettedContainer.Kind.BIOMES;
import static dev.mcresearch.protocolclient.world.PalettedContainer.Kind.BLOCK_STATES;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PalettedContainerTest {
	private static final int STATES = BlockRegistry.get().size();

	private static PalettedContainer read(final PacketWriter w, final PalettedContainer.Kind kind, final int globalSize) throws ProtocolException {
		PacketReader r = new PacketReader(w.toByteArray());
		PalettedContainer c = PalettedContainer.read(r, kind, globalSize);
		assertEquals(0, r.remaining(), "container must consume exactly its bytes");
		return c;
	}

	// ----- Bitpacking -----

	@Test
	void longCountsMatchSimpleBitStorage() {
		// docs/18 §4.4: 64/bits Werte pro Long, kein Übertrag über Long-Grenzen
		assertEquals(256, BitStorage.longsFor(4, 4096));
		assertEquals(342, BitStorage.longsFor(5, 4096)); // 12 pro Long, 4 Bit Rest ungenutzt
		assertEquals(586, BitStorage.longsFor(9, 4096)); // 7 pro Long (63 Bit), nicht 4096·9/64 = 576
		assertEquals(1024, BitStorage.longsFor(16, 4096));
		assertEquals(1, BitStorage.longsFor(1, 64));
		assertEquals(4, BitStorage.longsFor(3, 64)); // 21 pro Long
		assertEquals(8, BitStorage.longsFor(7, 64)); // 9 pro Long
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 15, 16, 31, 32})
	void packUnpackRoundTrip(final int bits) throws ProtocolException {
		Random rnd = new Random(bits);
		int[] values = new int[4096];
		for (int i = 0; i < values.length; i++) {
			values[i] = (int) (rnd.nextLong() & ((1L << bits) - 1));
		}
		assertArrayEquals(values, BitStorage.unpack(BitStorage.pack(values, bits), bits, 4096));
	}

	@Test
	void valuesDoNotSpanLongs() throws ProtocolException {
		// 5 Bit: Einträge 0–11 in Long 0 (Bits 0–59), Eintrag 12 beginnt bei Bit 0 von Long 1
		long[] data = new long[342];
		data[0] = 31L << 55; // Eintrag 11
		data[1] = 17L; // Eintrag 12
		data[0] |= 1L << 60; // ungenutzte Bits 60–63 dürfen nichts beeinflussen
		int[] v = BitStorage.unpack(data, 5, 4096);
		assertEquals(31, v[11]);
		assertEquals(17, v[12]);
		assertEquals(0, v[13]);
	}

	@Test
	void unpackRejectsWrongLength() {
		assertThrows(ProtocolException.class, () -> BitStorage.unpack(new long[255], 4, 4096));
		assertThrows(ProtocolException.class, () -> BitStorage.unpack(new long[257], 4, 4096));
		assertThrows(ProtocolException.class, () -> BitStorage.unpack(new long[1], 0, 4096));
	}

	// ----- Indexierung -----

	@Test
	void indexOrderIsYzx() {
		assertEquals(0, BLOCK_STATES.index(0, 0, 0));
		assertEquals(1, BLOCK_STATES.index(1, 0, 0));
		assertEquals(16, BLOCK_STATES.index(0, 0, 1));
		assertEquals(256, BLOCK_STATES.index(0, 1, 0));
		assertEquals(4095, BLOCK_STATES.index(15, 15, 15));
		assertEquals((1 * 4 + 2) * 4 + 3, BIOMES.index(3, 1, 2)); // (y·4 + z)·4 + x
		assertThrows(IndexOutOfBoundsException.class, () -> BLOCK_STATES.index(16, 0, 0));
		assertThrows(IndexOutOfBoundsException.class, () -> BIOMES.index(0, -1, 0));
	}

	// ----- Blockzustände -----

	@Test
	void singletonBlockStates() throws ProtocolException {
		PacketWriter w = new PacketWriter();
		ChunkTestSupport.writeSingle(w, 1);
		PalettedContainer c = read(w, BLOCK_STATES, STATES);
		assertEquals(PalettedContainer.PaletteType.SINGLETON, c.paletteType());
		assertEquals(1, c.get(7, 3, 12));
		assertTrue(c.isUniform());
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4})
	void linearPaletteAlwaysUsesFourBitsInMemory(final int sentBits) throws ProtocolException {
		int[] values = new int[4096];
		for (int i = 0; i < values.length; i++) {
			values[i] = i % 2 == 0 ? 1 : 10; // Stein / Erde
		}
		PacketWriter w = new PacketWriter();
		ChunkTestSupport.writeIndirect(w, BLOCK_STATES, values, sentBits);
		assertEquals(1 + 1 + 1 + 1 + 256 * 8, w.size(), "4 Bit im Speicher → 256 Longs, unabhängig von " + sentBits);
		PalettedContainer c = read(w, BLOCK_STATES, STATES);
		assertEquals(PalettedContainer.PaletteType.LINEAR, c.paletteType());
		assertEquals(sentBits, c.sentBits());
		assertArrayEquals(values, c.toArray());
		assertEquals(10, c.get(1, 0, 0));
		assertEquals(1, c.get(0, 5, 0));
	}

	@ParameterizedTest
	@ValueSource(ints = {5, 6, 7, 8})
	void hashMapPaletteUsesSentBits(final int sentBits) throws ProtocolException {
		int distinct = (1 << (sentBits - 1)) + 1; // braucht genau sentBits
		int[] values = new int[4096];
		for (int i = 0; i < values.length; i++) {
			values[i] = 100 + i % distinct;
		}
		PacketWriter w = new PacketWriter();
		ChunkTestSupport.writeIndirect(w, BLOCK_STATES, values, sentBits);
		PalettedContainer c = read(w, BLOCK_STATES, STATES);
		assertEquals(PalettedContainer.PaletteType.HASHMAP, c.paletteType());
		assertEquals(distinct, c.paletteSize());
		assertArrayEquals(values, c.toArray());
	}

	@ParameterizedTest
	@ValueSource(ints = {9, 15, 16})
	void globalPaletteUsesRegistryBits(final int sentBits) throws ProtocolException {
		assertEquals(16, BlockRegistry.ceilLog2(STATES)); // 35 723 Zustände → 16 Bit (docs/18 §4.2)
		int[] values = new int[4096];
		for (int i = 0; i < values.length; i++) {
			values[i] = (i * 9) % STATES;
		}
		PacketWriter w = new PacketWriter();
		ChunkTestSupport.writeGlobal(w, values, sentBits, STATES);
		assertEquals(1 + 1024 * 8, w.size());
		PalettedContainer c = read(w, BLOCK_STATES, STATES);
		assertEquals(PalettedContainer.PaletteType.GLOBAL, c.paletteType());
		assertArrayEquals(values, c.toArray());
	}

	// ----- Biome -----

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3})
	void biomeLinearPaletteUsesSentBits(final int bits) throws ProtocolException {
		int[] values = new int[64];
		for (int i = 0; i < 64; i++) {
			values[i] = (i % (1 << bits)) * 3;
		}
		PacketWriter w = new PacketWriter();
		ChunkTestSupport.writeIndirect(w, BIOMES, values, bits);
		PalettedContainer c = read(w, BIOMES, ChunkTestSupport.BIOMES);
		assertEquals(PalettedContainer.PaletteType.LINEAR, c.paletteType());
		assertArrayEquals(values, c.toArray());
		assertEquals(values[BIOMES.index(1, 2, 3)], c.get(1, 2, 3));
	}

	@Test
	void biomeGlobalFromFourBits() throws ProtocolException {
		int[] values = new int[64];
		for (int i = 0; i < 64; i++) {
			values[i] = i;
		}
		PacketWriter w = new PacketWriter();
		ChunkTestSupport.writeGlobal(w, values, 4, ChunkTestSupport.BIOMES); // 65 Biome → 7 Bit → 8 Longs
		assertEquals(1 + 8 * 8, w.size());
		PalettedContainer c = read(w, BIOMES, ChunkTestSupport.BIOMES);
		assertEquals(PalettedContainer.PaletteType.GLOBAL, c.paletteType());
		assertArrayEquals(values, c.toArray());
	}

	// ----- Ungültige Daten -----

	@Test
	void rejectsPaletteIndexOutsidePalette() {
		PacketWriter w = new PacketWriter().writeByte(4).writeVarInt(2).writeVarInt(1).writeVarInt(10);
		int[] idx = new int[4096];
		idx[77] = 2; // Palette hat nur 0 und 1
		for (long l : BitStorage.pack(idx, 4)) {
			w.writeLong(l);
		}
		ProtocolException e = assertThrows(ProtocolException.class, () -> read(w, BLOCK_STATES, STATES));
		assertTrue(e.getMessage().contains("entry 77"), e.getMessage());
	}

	@Test
	void rejectsUnknownStateIds() {
		PacketWriter single = new PacketWriter().writeByte(0).writeVarInt(STATES);
		assertThrows(ProtocolException.class, () -> read(single, BLOCK_STATES, STATES));
		PacketWriter negative = new PacketWriter().writeByte(0).writeVarInt(-1);
		assertThrows(ProtocolException.class, () -> read(negative, BLOCK_STATES, STATES));
		PacketWriter palette = new PacketWriter().writeByte(4).writeVarInt(1).writeVarInt(STATES + 5);
		assertThrows(ProtocolException.class, () -> read(palette, BLOCK_STATES, STATES));
		int[] values = new int[4096];
		values[4095] = STATES; // 16 Bit passen, ID aber außerhalb der Tabelle
		PacketWriter global = new PacketWriter();
		ChunkTestSupport.writeGlobal(global, values, 16, 1 << 16);
		assertThrows(ProtocolException.class, () -> read(global, BLOCK_STATES, STATES));
		PacketWriter biome = new PacketWriter().writeByte(0).writeVarInt(ChunkTestSupport.BIOMES);
		assertThrows(ProtocolException.class, () -> read(biome, BIOMES, ChunkTestSupport.BIOMES));
	}

	@Test
	void rejectsPaletteLargerThanCapacityOrEmpty() {
		PacketWriter tooBig = new PacketWriter().writeByte(4).writeVarInt(17);
		for (int i = 0; i < 17; i++) {
			tooBig.writeVarInt(i);
		}
		tooBig.writeBytes(new byte[256 * 8]);
		assertThrows(ProtocolException.class, () -> read(tooBig, BLOCK_STATES, STATES));
		PacketWriter empty = new PacketWriter().writeByte(4).writeVarInt(0).writeBytes(new byte[256 * 8]);
		assertThrows(ProtocolException.class, () -> read(empty, BLOCK_STATES, STATES));
		PacketWriter biomeTooBig = new PacketWriter().writeByte(1).writeVarInt(3).writeVarInt(0).writeVarInt(1).writeVarInt(2).writeLong(0);
		assertThrows(ProtocolException.class, () -> read(biomeTooBig, BIOMES, ChunkTestSupport.BIOMES));
	}

	@Test
	void rejectsTruncatedData() {
		PacketWriter w = new PacketWriter().writeByte(4).writeVarInt(1).writeVarInt(1).writeBytes(new byte[255 * 8 + 7]);
		assertThrows(ProtocolException.class, () -> read(w, BLOCK_STATES, STATES));
		PacketWriter noPalette = new PacketWriter().writeByte(0);
		assertThrows(ProtocolException.class, () -> read(noPalette, BLOCK_STATES, STATES));
	}

	@Test
	void setConvertsSingletonAndKeepsOtherEntries() {
		PalettedContainer c = PalettedContainer.single(BLOCK_STATES, 0);
		c.set(0, 0, 0, 0);
		assertTrue(c.isUniform());
		c.set(15, 15, 15, 1);
		assertEquals(1, c.get(15, 15, 15));
		assertEquals(0, c.get(14, 15, 15));
		assertEquals(1, java.util.Arrays.stream(c.toArray()).filter(v -> v == 1).count());
	}
}
