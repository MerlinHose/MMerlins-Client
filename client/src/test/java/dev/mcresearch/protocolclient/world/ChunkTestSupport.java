package dev.mcresearch.protocolclient.world;

import dev.mcresearch.protocolclient.protocol.PacketDirection;
import dev.mcresearch.protocolclient.protocol.PacketRegistry;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.RawPacket;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import dev.mcresearch.protocolclient.protocol.packet.ClientboundDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Erzeugt Chunk-Pakete im Vanilla-Format (docs/18) für Tests. Eigenständig geschrieben nach der Spezifikation;
 * die Dekodierung läuft danach vollständig über {@link ClientboundDecoder} und {@link ChunkDecoder}.
 */
public final class ChunkTestSupport {
	public static final DimensionInfo OVERWORLD = new DimensionInfo("minecraft:overworld", -64, 384);
	public static final DimensionInfo NETHER = new DimensionInfo("minecraft:the_nether", 0, 256);
	/** Testgröße der Biom-Registry → 7 globale Bits. */
	public static final int BIOMES = 65;
	public static final List<String> BIOME_NAMES = biomeNames(BIOMES);

	private ChunkTestSupport() {
	}

	static List<String> biomeNames(final int n) {
		List<String> names = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			names.add(i == 0 ? "minecraft:plains" : "test:biome_" + i);
		}
		return names;
	}

	public static void writeSingle(final PacketWriter w, final int id) {
		w.writeByte(0).writeVarInt(id); // Singleton: Daten-Array hat 0 Longs
	}

	/** Indirekte Palette (Reihenfolge des ersten Auftretens) mit gesendeten Bits {@code sentBits}. */
	public static void writeIndirect(final PacketWriter w, final PalettedContainer.Kind kind, final int[] values, final int sentBits) {
		Map<Integer, Integer> palette = new LinkedHashMap<>();
		for (int v : values) {
			palette.putIfAbsent(v, palette.size());
		}
		int memoryBits = kind == PalettedContainer.Kind.BLOCK_STATES && sentBits <= 4 ? 4 : sentBits;
		w.writeByte(sentBits).writeVarInt(palette.size());
		palette.keySet().forEach(w::writeVarInt);
		int[] indices = Arrays.stream(values).map(palette::get).toArray();
		for (long l : BitStorage.pack(indices, memoryBits)) {
			w.writeLong(l);
		}
	}

	/** Direkte (globale) Speicherung; Bits im Speicher = ceillog2(globalSize). */
	public static void writeGlobal(final PacketWriter w, final int[] values, final int sentBits, final int globalSize) {
		w.writeByte(sentBits);
		for (long l : BitStorage.pack(values, BlockRegistry.ceilLog2(globalSize))) {
			w.writeLong(l);
		}
	}

	/** Index {@code (y·16 + z)·16 + x}. */
	public static int index(final int x, final int y, final int z) {
		return (y << 8 | z << 4) | x;
	}

	/** Sektionspuffer: alle Sektionen Luft, Biom 0, außer den übergebenen Blockarrays (Index = Sektionsindex). */
	public static byte[] sections(final DimensionInfo dim, final Map<Integer, int[]> blocks) {
		PacketWriter w = new PacketWriter();
		BlockRegistry reg = BlockRegistry.get();
		for (int i = 0; i < dim.height() / 16; i++) {
			int[] b = blocks.get(i);
			if (b == null) {
				w.writeShort(0).writeShort(0);
				writeSingle(w, 0);
			} else {
				int nonAir = (int) Arrays.stream(b).filter(id -> !reg.state(id).isAir()).count();
				w.writeShort(nonAir).writeShort(0);
				int distinct = (int) Arrays.stream(b).distinct().count();
				if (distinct == 1) {
					writeSingle(w, b[0]);
				} else if (distinct <= 16) {
					writeIndirect(w, PalettedContainer.Kind.BLOCK_STATES, b, Math.max(1, BlockRegistry.ceilLog2(distinct)));
				} else if (distinct <= 256) {
					writeIndirect(w, PalettedContainer.Kind.BLOCK_STATES, b, BlockRegistry.ceilLog2(distinct));
				} else {
					writeGlobal(w, b, 15, reg.size());
				}
			}
			writeSingle(w, 0);
		}
		return w.toByteArray();
	}

	public static int[] filled(final int id) {
		int[] a = new int[4096];
		Arrays.fill(a, id);
		return a;
	}

	public record Light(BitSet sky, BitSet block, BitSet emptySky, BitSet emptyBlock, List<byte[]> skyArrays, List<byte[]> blockArrays) {
		public static Light none() {
			return new Light(new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of());
		}
	}

	/** Körper eines {@code level_chunk_with_light} (ohne Paket-ID). */
	public static byte[] chunkBody(final int cx, final int cz, final Map<Integer, long[]> heightmaps, final byte[] sections,
		final List<int[]> blockEntities, final Light light) {
		PacketWriter w = new PacketWriter().writeInt(cx).writeInt(cz).writeVarInt(heightmaps.size());
		heightmaps.forEach((type, data) -> {
			w.writeVarInt(type).writeVarInt(data.length);
			for (long l : data) {
				w.writeLong(l);
			}
		});
		w.writeVarInt(sections.length).writeBytes(sections);
		w.writeVarInt(blockEntities.size());
		for (int[] be : blockEntities) { // {localX, y, localZ, type}
			w.writeByte(be[0] << 4 | be[2]).writeShort(be[1]).writeVarInt(be[3]).writeByte(0); // TAG_End = kein NBT
		}
		writeLight(w, light);
		return w.toByteArray();
	}

	public static void writeLight(final PacketWriter w, final Light light) {
		for (BitSet b : List.of(light.sky(), light.block(), light.emptySky(), light.emptyBlock())) {
			byte[] bytes = b.toByteArray();
			w.writeVarInt(bytes.length).writeBytes(bytes);
		}
		for (List<byte[]> arrays : List.of(light.skyArrays(), light.blockArrays())) {
			w.writeVarInt(arrays.size());
			for (byte[] a : arrays) {
				w.writeVarInt(a.length).writeBytes(a);
			}
		}
	}

	public static RawPacket raw(final String name, final byte[] body) {
		return new RawPacket(ProtocolState.PLAY, PacketRegistry.get().idOf(ProtocolState.PLAY, PacketDirection.CLIENTBOUND, name), name, body,
			body.length + 1);
	}

	public static Clientbound.LevelChunkWithLight decodeChunk(final byte[] body) throws ProtocolException {
		return (Clientbound.LevelChunkWithLight) ClientboundDecoder.decode(raw("minecraft:level_chunk_with_light", body));
	}

	/** Chunk mit den angegebenen Sektionen, ohne Heightmaps, Block-Entities und Licht. */
	public static Clientbound.LevelChunkWithLight simpleChunk(final DimensionInfo dim, final int cx, final int cz, final Map<Integer, int[]> blocks)
		throws ProtocolException {
		return decodeChunk(chunkBody(cx, cz, Map.of(), sections(dim, blocks), List.of(), Light.none()));
	}

	public static WorldState world(final DimensionInfo dim) {
		return new WorldState(dim, BlockRegistry.get(), BIOME_NAMES);
	}
}
