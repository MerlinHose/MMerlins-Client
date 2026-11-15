package dev.mcresearch.protocolclient.world;

import static dev.mcresearch.protocolclient.world.ChunkTestSupport.NETHER;
import static dev.mcresearch.protocolclient.world.ChunkTestSupport.OVERWORLD;
import static dev.mcresearch.protocolclient.world.ChunkTestSupport.filled;
import static dev.mcresearch.protocolclient.world.ChunkTestSupport.index;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.cli.WorldCommands;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;
import dev.mcresearch.protocolclient.protocol.codec.Positions;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import dev.mcresearch.protocolclient.protocol.packet.ClientboundDecoder;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldStateTest {
	private static final BlockRegistry REG = BlockRegistry.get();
	private static final int AIR = 0;
	private static final int STONE = 1;
	private static final int DIRT = 10;
	private static final int OAK_STAIRS_N_TOP = 5464; // facing=north,half=top,shape=straight,waterlogged=false
	private static final int OAK_SLAB_TOP = 15178; // type=top,waterlogged=false
	private static final int CHEST = 5544;

	// ----- Registry -----

	@Test
	void registryMatchesOfficialReport() {
		assertEquals(35_723, REG.size());
		assertEquals("minecraft:air", REG.state(0).name());
		assertTrue(REG.state(0).isAir());
		assertEquals("minecraft:stone", REG.state(STONE).toString());
		assertEquals("minecraft:dirt", REG.state(DIRT).toString());
		assertEquals("minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]", REG.state(OAK_STAIRS_N_TOP).toString());
		assertEquals(OAK_SLAB_TOP, REG.idOf("minecraft:oak_slab", Map.of("type", "top", "waterlogged", "false")));
		assertTrue(REG.state(REG.idOf("minecraft:cave_air", Map.of())).isAir());
		assertFalse(REG.state(89).isAir()); // water level=0
		assertEquals("minecraft:firefly_bush", REG.state(35_722).name());
		assertEquals(49, REG.blockEntityTypeCount());
		assertEquals("minecraft:chest", REG.blockEntityType(1));
		assertNull(REG.blockEntityType(49));
		assertThrows(IllegalArgumentException.class, () -> REG.state(35_723));
	}

	// ----- Positionen -----

	@Test
	void blockAndSectionPositionsRoundTripWithNegatives() {
		int[][] cases = {{0, 0, 0}, {-1, -64, -1}, {33554431, 2047, -33554432}, {-33554432, -2048, 33554431}, {-17, 319, 15}};
		for (int[] c : cases) {
			long p = Positions.packBlock(c[0], c[1], c[2]);
			assertArrayEquals(c, new int[]{Positions.blockX(p), Positions.blockY(p), Positions.blockZ(p)});
		}
		long s = Positions.packSection(-2, -4, -1);
		assertArrayEquals(new int[]{-2, -4, -1}, new int[]{Positions.sectionX(s), Positions.sectionY(s), Positions.sectionZ(s)});
		long s2 = Positions.packSection(2097151, 524287, -2097152);
		assertArrayEquals(new int[]{2097151, 524287, -2097152}, new int[]{Positions.sectionX(s2), Positions.sectionY(s2), Positions.sectionZ(s2)});
	}

	// ----- Vollständiger Chunk -----

	@Test
	void overworldChunkHas24SectionsFromMinY() throws ProtocolException {
		int[] layer = new int[4096];
		for (int i = 0; i < 4096; i++) {
			int y = i >> 8;
			layer[i] = y == 0 ? STONE : y < 4 ? DIRT : AIR;
		}
		layer[index(5, 4, 6)] = OAK_STAIRS_N_TOP;
		layer[index(15, 4, 15)] = OAK_SLAB_TOP;
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		Chunk c = w.loadChunk(ChunkTestSupport.simpleChunk(OVERWORLD, 2, 3, Map.of(4, layer, 0, filled(STONE))));
		assertEquals(24, c.sectionCount());
		assertEquals(-4, c.minSectionY());
		// Sektion 4 = y 0..15; Chunk 2,3 = Blöcke x 32..47, z 48..63
		assertEquals("minecraft:stone", w.block(32, 0, 48).orElseThrow().name());
		assertEquals("minecraft:dirt", w.block(40, 3, 50).orElseThrow().name());
		assertTrue(w.block(40, 4, 50).orElseThrow().isAir());
		assertEquals(OAK_STAIRS_N_TOP, w.block(37, 4, 54).orElseThrow().id());
		assertEquals(OAK_SLAB_TOP, w.block(47, 4, 63).orElseThrow().id());
		assertEquals(STONE, w.block(32, -64, 48).orElseThrow().id()); // unterste Sektion
		assertEquals(STONE, w.block(47, -49, 63).orElseThrow().id());
		assertTrue(w.block(32, -48, 48).orElseThrow().isAir());
		assertTrue(w.block(32, 319, 48).orElseThrow().isAir()); // oberste Sektion
		assertEquals("minecraft:void_air", w.block(32, 320, 48).orElseThrow().name());
		assertEquals("minecraft:void_air", w.block(32, -65, 48).orElseThrow().name());
		assertTrue(w.block(48, 0, 48).isEmpty(), "Nachbarchunk nicht geladen");
		assertEquals(0, w.stats().nonEmptyCountMismatches);
		assertEquals(1, w.stats().trailingByteChecks);
		assertEquals(PalettedContainer.PaletteType.LINEAR, c.sectionAtY(0).states().paletteType());
		assertEquals("minecraft:plains", w.biomeName(c.biome(32, 0, 48)));
	}

	@Test
	void netherSectionCountComesFromDimension() throws ProtocolException {
		WorldState w = ChunkTestSupport.world(NETHER);
		Chunk c = w.loadChunk(ChunkTestSupport.simpleChunk(NETHER, 0, 0, Map.of(15, filled(STONE))));
		assertEquals(16, c.sectionCount());
		assertEquals(STONE, w.block(0, 255, 0).orElseThrow().id());
		assertEquals("minecraft:void_air", w.block(0, 256, 0).orElseThrow().name());
		// Overworld-Puffer (24 Sektionen) in einer 16-Sektionen-Dimension: Restbytes
		byte[] ow = ChunkTestSupport.sections(OVERWORLD, Map.of());
		Clientbound.LevelChunkWithLight p = ChunkTestSupport.decodeChunk(
			ChunkTestSupport.chunkBody(1, 0, Map.of(), ow, List.of(), ChunkTestSupport.Light.none()));
		ProtocolException e = assertThrows(ProtocolException.class, () -> w.loadChunk(p));
		assertTrue(e.getMessage().contains("trailing bytes"), e.getMessage());
		// umgekehrt: zu wenige Sektionen
		WorldState ow2 = ChunkTestSupport.world(OVERWORLD);
		Clientbound.LevelChunkWithLight shortChunk = ChunkTestSupport.simpleChunk(NETHER, 0, 0, Map.of());
		assertThrows(ProtocolException.class, () -> ow2.loadChunk(shortChunk));
	}

	@Test
	void negativeChunkCoordinatesAndBorders() throws ProtocolException {
		int[] marks = new int[4096];
		marks[index(0, 0, 0)] = STONE; // Ecke x=-16, z=-16
		marks[index(15, 0, 15)] = DIRT; // Ecke x=-1, z=-1
		marks[index(15, 15, 0)] = OAK_SLAB_TOP; // x=-1, y=15, z=-16
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		w.loadChunk(ChunkTestSupport.simpleChunk(OVERWORLD, -1, -1, Map.of(4, marks)));
		w.loadChunk(ChunkTestSupport.simpleChunk(OVERWORLD, 0, -1, Map.of(4, filled(DIRT))));
		assertEquals(STONE, w.block(-16, 0, -16).orElseThrow().id());
		assertEquals(DIRT, w.block(-1, 0, -1).orElseThrow().id());
		assertEquals(OAK_SLAB_TOP, w.block(-1, 15, -16).orElseThrow().id());
		assertEquals(AIR, w.block(-2, 0, -1).orElseThrow().id());
		assertEquals(DIRT, w.block(0, 0, -1).orElseThrow().id()); // Grenze zum Chunk 0,-1
		assertTrue(w.block(-1, 0, 0).isEmpty()); // Chunk -1,0 nicht geladen
		assertTrue(w.block(-17, 0, -1).isEmpty());
		assertNotNull(w.chunk(-1, -1));
	}

	@Test
	void allPaletteKindsInOneChunk() throws ProtocolException {
		int[] single = filled(STONE);
		int[] linear = new int[4096];
		int[] hashmap = new int[4096];
		int[] global = new int[4096];
		for (int i = 0; i < 4096; i++) {
			linear[i] = i % 3 == 0 ? DIRT : STONE;
			hashmap[i] = 1000 + i % 200;
			global[i] = i % 2 == 0 ? AIR : 2000 + i % 2000;
		}
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		Chunk c = w.loadChunk(ChunkTestSupport.simpleChunk(OVERWORLD, 0, 0, Map.of(0, single, 1, linear, 2, hashmap, 3, global)));
		assertEquals(PalettedContainer.PaletteType.SINGLETON, c.section(0).states().paletteType());
		assertEquals(PalettedContainer.PaletteType.LINEAR, c.section(1).states().paletteType());
		assertEquals(PalettedContainer.PaletteType.HASHMAP, c.section(2).states().paletteType());
		assertEquals(PalettedContainer.PaletteType.GLOBAL, c.section(3).states().paletteType());
		assertArrayEquals(linear, c.section(1).states().toArray());
		assertArrayEquals(hashmap, c.section(2).states().toArray());
		assertArrayEquals(global, c.section(3).states().toArray());
		assertEquals(2048, c.section(3).nonEmptyBlockCount());
		assertEquals(0, w.stats().nonEmptyCountMismatches);
	}

	@Test
	void nonEmptyCountMismatchIsCountedNotHidden() throws ProtocolException {
		PacketWriter s = new PacketWriter();
		for (int i = 0; i < 24; i++) {
			s.writeShort(i == 0 ? 7 : 0).writeShort(0); // behauptet 7 Blöcke, Sektion ist aber Luft
			ChunkTestSupport.writeSingle(s, AIR);
			ChunkTestSupport.writeSingle(s, 0);
		}
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		w.loadChunk(ChunkTestSupport.decodeChunk(ChunkTestSupport.chunkBody(0, 0, Map.of(), s.toByteArray(), List.of(), ChunkTestSupport.Light.none())));
		assertEquals(1, w.stats().nonEmptyCountMismatches);
	}

	@Test
	void invalidSectionDataIsRejected() throws ProtocolException {
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		byte[] good = ChunkTestSupport.sections(OVERWORLD, Map.of(3, filled(STONE)));
		byte[] extra = Arrays.copyOf(good, good.length + 1);
		assertThrows(ProtocolException.class, () -> w.loadChunk(ChunkTestSupport.decodeChunk(
			ChunkTestSupport.chunkBody(0, 0, Map.of(), extra, List.of(), ChunkTestSupport.Light.none()))));
		byte[] truncated = Arrays.copyOf(good, good.length - 1);
		assertThrows(ProtocolException.class, () -> w.loadChunk(ChunkTestSupport.decodeChunk(
			ChunkTestSupport.chunkBody(0, 0, Map.of(), truncated, List.of(), ChunkTestSupport.Light.none()))));
		PacketWriter negCount = new PacketWriter().writeShort(-1).writeShort(0);
		ChunkTestSupport.writeSingle(negCount, STONE);
		assertThrows(ProtocolException.class, () -> w.loadChunk(ChunkTestSupport.decodeChunk(
			ChunkTestSupport.chunkBody(0, 0, Map.of(), negCount.toByteArray(), List.of(), ChunkTestSupport.Light.none()))));
		assertEquals(0, w.loadedChunks(), "fehlerhafte Chunks werden nicht teilweise übernommen");
		// Restbytes im Paket hinter den Lichtdaten
		byte[] body = ChunkTestSupport.chunkBody(0, 0, Map.of(), good, List.of(), ChunkTestSupport.Light.none());
		byte[] trailing = Arrays.copyOf(body, body.length + 1);
		assertThrows(ProtocolException.class, () -> ChunkTestSupport.decodeChunk(trailing));
	}

	@Test
	void heightmapsAreValidatedAndReadable() throws ProtocolException {
		int bits = OVERWORLD.heightmapBits(); // 9 → 37 Longs
		int[] values = new int[256];
		Arrays.fill(values, 64 + 64); // y=64
		values[15 + 15 * 16] = 0;
		long[] packed = BitStorage.pack(values, bits);
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		Chunk c = w.loadChunk(ChunkTestSupport.decodeChunk(ChunkTestSupport.chunkBody(0, 0, Map.of(4, packed, 1, packed),
			ChunkTestSupport.sections(OVERWORLD, Map.of()), List.of(), ChunkTestSupport.Light.none())));
		assertEquals(128, c.heightmap(4, 0, 0));
		assertEquals(0, c.heightmap(1, 15, 15));
		assertEquals(-1, c.heightmap(0, 0, 0));
		long[] wrongLength = Arrays.copyOf(packed, 36);
		assertThrows(ProtocolException.class, () -> w.loadChunk(ChunkTestSupport.decodeChunk(ChunkTestSupport.chunkBody(0, 0, Map.of(4, wrongLength),
			ChunkTestSupport.sections(OVERWORLD, Map.of()), List.of(), ChunkTestSupport.Light.none()))));
		int[] tooHigh = values.clone();
		tooHigh[3] = 385;
		assertThrows(ProtocolException.class, () -> w.loadChunk(ChunkTestSupport.decodeChunk(ChunkTestSupport.chunkBody(0, 0,
			Map.of(4, BitStorage.pack(tooHigh, bits)), ChunkTestSupport.sections(OVERWORLD, Map.of()), List.of(), ChunkTestSupport.Light.none()))));
	}

	// ----- Licht -----

	@Test
	void lightMasksMapToSectionsBelowAndAboveWorld() throws ProtocolException {
		byte[] sky = new byte[2048];
		Arrays.fill(sky, (byte) 0xFF); // überall 15
		byte[] block = new byte[2048];
		int i = (3 << 8) | (2 << 4) | 1; // lokal x=1 y=3 z=2, ungerader Index → oberes Nibble
		block[i >> 1] = (byte) (7 << 4);
		BitSet skyMask = new BitSet();
		skyMask.set(25); // oberste Lichtsektion = Sektion y=20 (über der Welt)
		skyMask.set(5); // Sektion y=0
		BitSet blockMask = new BitSet();
		blockMask.set(5);
		BitSet emptySky = new BitSet();
		emptySky.set(0); // Sektion y=-5 (unter der Welt)
		ChunkTestSupport.Light light = new ChunkTestSupport.Light(skyMask, blockMask, emptySky, new BitSet(), List.of(sky, sky), List.of(block));
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		Chunk c = w.loadChunk(ChunkTestSupport.decodeChunk(ChunkTestSupport.chunkBody(0, 0, Map.of(),
			ChunkTestSupport.sections(OVERWORLD, Map.of()), List.of(), light)));
		assertEquals(26, c.lightSectionCount());
		assertEquals(15, c.light(true, 4, 9, 4));
		assertEquals(15, c.light(true, 0, 330, 0));
		assertEquals(0, c.light(true, 0, -70, 0));
		assertEquals(-1, c.light(true, 0, 20, 0), "nicht gesendet = unbekannt");
		assertEquals(7, c.light(false, 1, 3, 2));
		assertEquals(0, c.light(false, 0, 3, 2));
		assertTrue(c.isLightEmpty(true, 0));
		// light_update: Bit 6 neu, Bit 5 leer, Rest unverändert
		BitSet m6 = new BitSet();
		m6.set(6);
		BitSet e5 = new BitSet();
		e5.set(5);
		w.applyLightUpdate(new Clientbound.LightUpdate(0, 0, new Clientbound.LightData(m6, new BitSet(), e5, new BitSet(), List.of(sky), List.of())));
		assertEquals(15, c.light(true, 0, 20, 0));
		assertEquals(0, c.light(true, 4, 9, 4));
		assertEquals(15, c.light(true, 0, 330, 0));
		assertEquals(7, c.light(false, 1, 3, 2));
	}

	@Test
	void invalidLightDataIsRejected() throws ProtocolException {
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		BitSet outside = new BitSet();
		outside.set(26); // nur 0..25 gültig
		ChunkTestSupport.Light l1 = new ChunkTestSupport.Light(outside, new BitSet(), new BitSet(), new BitSet(), List.of(new byte[2048]), List.of());
		assertThrows(ProtocolException.class, () -> w.loadChunk(ChunkTestSupport.decodeChunk(ChunkTestSupport.chunkBody(0, 0, Map.of(),
			ChunkTestSupport.sections(OVERWORLD, Map.of()), List.of(), l1))));
		BitSet one = new BitSet();
		one.set(3);
		ChunkTestSupport.Light both = new ChunkTestSupport.Light(one, new BitSet(), one, new BitSet(), List.of(new byte[2048]), List.of());
		assertThrows(ProtocolException.class, () -> w.loadChunk(ChunkTestSupport.decodeChunk(ChunkTestSupport.chunkBody(0, 0, Map.of(),
			ChunkTestSupport.sections(OVERWORLD, Map.of()), List.of(), both))));
		// Arrayanzahl ≠ Maskenbits und falsche Arraylänge scheitern schon im Paketdecoder
		ChunkTestSupport.Light count = new ChunkTestSupport.Light(one, new BitSet(), new BitSet(), new BitSet(), List.of(), List.of());
		assertThrows(ProtocolException.class, () -> ChunkTestSupport.decodeChunk(ChunkTestSupport.chunkBody(0, 0, Map.of(),
			ChunkTestSupport.sections(OVERWORLD, Map.of()), List.of(), count)));
		ChunkTestSupport.Light size = new ChunkTestSupport.Light(one, new BitSet(), new BitSet(), new BitSet(), List.of(new byte[2047]), List.of());
		assertThrows(ProtocolException.class, () -> ChunkTestSupport.decodeChunk(ChunkTestSupport.chunkBody(0, 0, Map.of(),
			ChunkTestSupport.sections(OVERWORLD, Map.of()), List.of(), size)));
	}

	// ----- Updates -----

	@Test
	void blockUpdateChangesStateAndCounters() throws ProtocolException {
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		Chunk c = w.loadChunk(ChunkTestSupport.simpleChunk(OVERWORLD, -1, 0, Map.of()));
		assertTrue(w.applyBlockUpdate(new Clientbound.BlockUpdate(-1, -64, 15, STONE)));
		assertEquals(STONE, w.block(-1, -64, 15).orElseThrow().id());
		assertEquals(1, c.sectionAtY(-4).nonEmptyBlockCount());
		assertTrue(w.applyBlockUpdate(new Clientbound.BlockUpdate(-1, -64, 15, AIR)));
		assertEquals(0, c.sectionAtY(-4).nonEmptyBlockCount());
		assertFalse(w.applyBlockUpdate(new Clientbound.BlockUpdate(5, 0, 5, STONE)), "Chunk 0,0 nicht geladen");
		assertEquals(1, w.stats().updatesForUnloadedChunks);
		assertThrows(ProtocolException.class, () -> w.applyBlockUpdate(new Clientbound.BlockUpdate(-1, 0, 0, REG.size())));
		assertThrows(ProtocolException.class, () -> w.applyBlockUpdate(new Clientbound.BlockUpdate(-1, 320, 0, STONE)));
	}

	@Test
	void blockUpdatePacketDecodesNegativePosition() throws ProtocolException {
		byte[] body = new PacketWriter().writeLong(Positions.packBlock(-30000000, -64, 29999999)).writeVarInt(OAK_STAIRS_N_TOP).toByteArray();
		Clientbound.BlockUpdate u = (Clientbound.BlockUpdate) ClientboundDecoder.decode(ChunkTestSupport.raw("minecraft:block_update", body));
		assertEquals(new Clientbound.BlockUpdate(-30000000, -64, 29999999, OAK_STAIRS_N_TOP), u);
	}

	@Test
	void sectionBlocksUpdateAppliesAllEntries() throws ProtocolException {
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		w.loadChunk(ChunkTestSupport.simpleChunk(OVERWORLD, -2, 1, Map.of()));
		// Sektion (-2, -1, 1) = Blöcke x -32..-17, y -16..-1, z 16..31
		PacketWriter p = new PacketWriter().writeLong(Positions.packSection(-2, -1, 1)).writeVarInt(3);
		p.writeVarLong((long) STONE << 12 | (0 << 8 | 0 << 4 | 0));
		p.writeVarLong((long) DIRT << 12 | (15 << 8 | 15 << 4 | 15));
		p.writeVarLong((long) OAK_SLAB_TOP << 12 | (3 << 8 | 9 << 4 | 4)); // x=3 z=9 y=4
		Clientbound.SectionBlocksUpdate u = (Clientbound.SectionBlocksUpdate) ClientboundDecoder.decode(
			ChunkTestSupport.raw("minecraft:section_blocks_update", p.toByteArray()));
		assertEquals(3, w.applySectionUpdate(u));
		assertEquals(STONE, w.block(-32, -16, 16).orElseThrow().id());
		assertEquals(DIRT, w.block(-17, -1, 31).orElseThrow().id());
		assertEquals(OAK_SLAB_TOP, w.block(-29, -12, 25).orElseThrow().id());
		assertEquals(3, w.chunk(-2, 1).sectionAtY(-1).nonEmptyBlockCount());
		assertEquals(3, w.stats().sectionUpdateEntries);
		Clientbound.SectionBlocksUpdate outside = new Clientbound.SectionBlocksUpdate(-2, 20, 1, new short[]{0}, new int[]{STONE});
		assertThrows(ProtocolException.class, () -> w.applySectionUpdate(outside));
		Clientbound.SectionBlocksUpdate badId = new Clientbound.SectionBlocksUpdate(-2, 0, 1, new short[]{0}, new int[]{REG.size()});
		assertThrows(ProtocolException.class, () -> w.applySectionUpdate(badId));
	}

	@Test
	void unloadAndReload() throws ProtocolException {
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		w.loadChunk(ChunkTestSupport.simpleChunk(OVERWORLD, 4, -7, Map.of(4, filled(STONE))));
		assertTrue(w.unloadChunk(4, -7));
		assertTrue(w.block(64, 0, -112).isEmpty());
		assertFalse(w.unloadChunk(4, -7));
		assertEquals(1, w.stats().unloadsOfUnknownChunks);
		w.loadChunk(ChunkTestSupport.simpleChunk(OVERWORLD, 4, -7, Map.of(4, filled(DIRT))));
		w.loadChunk(ChunkTestSupport.simpleChunk(OVERWORLD, 4, -7, Map.of(4, filled(STONE))));
		assertEquals(STONE, w.block(64, 0, -112).orElseThrow().id());
		assertEquals(1, w.stats().chunksReplaced);
		assertEquals(1, w.loadedChunks());
	}

	@Test
	void blockEntitiesFromChunkAndUpdates() throws ProtocolException {
		int[] blocks = new int[4096];
		blocks[index(2, 5, 3)] = CHEST;
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		Chunk c = w.loadChunk(ChunkTestSupport.decodeChunk(ChunkTestSupport.chunkBody(-1, 0, Map.of(),
			ChunkTestSupport.sections(OVERWORLD, Map.of(4, blocks)), List.of(new int[]{2, 5, 3, 1}), ChunkTestSupport.Light.none())));
		BlockEntity be = c.blockEntity(-14, 5, 3);
		assertNotNull(be);
		assertEquals("minecraft:chest", be.type());
		assertTrue(be.nbt().isEmpty());
		assertTrue(w.applyBlockEntityData(new Clientbound.BlockEntityData(-14, 5, 3, 1, Map.of("CustomName", "x"))));
		assertEquals("x", c.blockEntity(-14, 5, 3).nbt().get("CustomName"));
		w.applyBlockUpdate(new Clientbound.BlockUpdate(-14, 5, 3, CHEST - 1)); // gleicher Block, anderer Zustand
		assertNotNull(c.blockEntity(-14, 5, 3));
		w.applyBlockUpdate(new Clientbound.BlockUpdate(-14, 5, 3, AIR));
		assertNull(c.blockEntity(-14, 5, 3));
		assertEquals(1, w.stats().blockEntitiesRemoved);
		assertThrows(ProtocolException.class, () -> w.applyBlockEntityData(new Clientbound.BlockEntityData(0, 0, 0, 49, Map.of())));
		// unbekannter Typ und Höhe außerhalb im Chunk-Paket
		assertThrows(ProtocolException.class, () -> w.loadChunk(ChunkTestSupport.decodeChunk(ChunkTestSupport.chunkBody(0, 0, Map.of(),
			ChunkTestSupport.sections(OVERWORLD, Map.of()), List.of(new int[]{0, 0, 0, 99}), ChunkTestSupport.Light.none()))));
		assertThrows(ProtocolException.class, () -> w.loadChunk(ChunkTestSupport.decodeChunk(ChunkTestSupport.chunkBody(0, 0, Map.of(),
			ChunkTestSupport.sections(OVERWORLD, Map.of()), List.of(new int[]{0, 400, 0, 1}), ChunkTestSupport.Light.none()))));
	}

	@Test
	void chunksBiomesReplacesBiomes() throws ProtocolException {
		WorldState w = ChunkTestSupport.world(NETHER);
		Chunk c = w.loadChunk(ChunkTestSupport.simpleChunk(NETHER, 0, -1, Map.of()));
		PacketWriter buf = new PacketWriter();
		for (int i = 0; i < 16; i++) {
			ChunkTestSupport.writeSingle(buf, i == 2 ? 7 : 3);
		}
		byte[] biomes = buf.toByteArray();
		PacketWriter p = new PacketWriter().writeVarInt(1).writeLong((0 & 0xFFFFFFFFL) | ((long) -1 << 32)).writeVarInt(biomes.length).writeBytes(biomes);
		Clientbound.ChunksBiomes b = (Clientbound.ChunksBiomes) ClientboundDecoder.decode(ChunkTestSupport.raw("minecraft:chunks_biomes", p.toByteArray()));
		assertEquals(1, w.applyBiomes(b));
		assertEquals(7, c.biome(0, 40, -1));
		assertEquals(3, c.biome(0, 0, -16));
		byte[] extra = Arrays.copyOf(biomes, biomes.length + 2);
		assertThrows(ProtocolException.class, () -> w.applyBiomes(new Clientbound.ChunksBiomes(List.of(new Clientbound.ChunkBiomeData(0, -1, extra)))));
	}

	// ----- Terminalausgaben -----

	@Test
	void worldCommandsDescribeState() throws ProtocolException {
		int[] blocks = new int[4096];
		blocks[index(0, 0, 0)] = STONE;
		blocks[index(1, 0, 0)] = OAK_STAIRS_N_TOP;
		WorldState w = ChunkTestSupport.world(OVERWORLD);
		w.loadChunk(ChunkTestSupport.simpleChunk(OVERWORLD, 0, 0, Map.of(4, blocks)));
		List<String> b = WorldCommands.block(w, 1, 0, 0);
		assertTrue(b.get(0).contains("minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]"), b.toString());
		assertTrue(b.get(1).contains("section y=0 (index 4)"), b.toString());
		assertTrue(WorldCommands.block(w, 16, 0, 0).get(0).contains("not loaded"));
		List<String> n = WorldCommands.nearby(w, 0.5, 1, 0.5, 1, 10);
		assertTrue(n.get(0).contains("2 non-air of 27"), n.toString());
		assertTrue(n.get(1).contains("minecraft:stone"), n.toString());
		List<String> h = WorldCommands.blocks(w, 0, 0, 5);
		assertTrue(h.get(1).contains("98302 minecraft:air"), h.toString());
		assertEquals(3 + 24, WorldCommands.chunk(w, 0, 0).size());
		assertTrue(WorldCommands.world(w, "minecraft:overworld").get(0).contains("sections=24"));
		assertThrows(IllegalArgumentException.class, () -> WorldCommands.nearby(w, 0, 0, 0, 9, 10));
	}
}
