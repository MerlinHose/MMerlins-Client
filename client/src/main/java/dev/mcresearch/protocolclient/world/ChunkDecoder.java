package dev.mcresearch.protocolclient.world;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * Interpretiert den Sektionspuffer, Heightmaps, Block-Entities und Licht eines {@code level_chunk_with_light}
 * (docs/18 §2–§6). Jede Abweichung von der erwarteten Struktur ist ein {@link ProtocolException}; der Puffer muss
 * exakt aufgebraucht werden.
 */
public final class ChunkDecoder {
	private final DimensionInfo dimension;
	private final BlockRegistry registry;
	private final int biomeCount;

	/**
	 * @param biomeCount Anzahl Einträge der synchronisierten Registry {@code minecraft:worldgen/biome} (globale Biom-Palette)
	 */
	public ChunkDecoder(final DimensionInfo dimension, final BlockRegistry registry, final int biomeCount) {
		if (biomeCount <= 0) {
			throw new IllegalArgumentException("biome registry is empty");
		}
		this.dimension = dimension;
		this.registry = registry;
		this.biomeCount = biomeCount;
	}

	/** Sektionsanzahl aus der Dimension: {@code height / 16} (Overworld 384 → 24, Nether/End 256 → 16). */
	public int sectionCount() {
		return this.dimension.height() >> 4;
	}

	public Chunk decode(final Clientbound.LevelChunkWithLight packet) throws ProtocolException {
		validateHeightmaps(packet.heightmaps());
		ChunkSection[] sections = readSections(packet.sectionData(), packet.chunkX(), packet.chunkZ());
		Chunk chunk = new Chunk(packet.chunkX(), packet.chunkZ(), this.dimension, sections, packet.heightmaps(), packet.sectionData().length);
		int maxY = this.dimension.minY() + this.dimension.height();
		for (Clientbound.BlockEntityInfo info : packet.blockEntities()) {
			if (info.y() < this.dimension.minY() || info.y() >= maxY) {
				throw new ProtocolException("Block entity at y=" + info.y() + " outside world height");
			}
			String type = this.registry.blockEntityType(info.typeId());
			if (type == null) {
				throw new ProtocolException("Unknown block entity type " + info.typeId() + " (" + this.registry.blockEntityTypeCount() + " known)");
			}
			chunk.putBlockEntity(new BlockEntity(packet.chunkX() * 16 + info.localX(), info.y(), packet.chunkZ() * 16 + info.localZ(), info.typeId(),
				type, info.nbt()));
		}
		applyLight(chunk, packet.light());
		return chunk;
	}

	private ChunkSection[] readSections(final byte[] buffer, final int chunkX, final int chunkZ) throws ProtocolException {
		PacketReader r = new PacketReader(buffer);
		ChunkSection[] sections = new ChunkSection[sectionCount()];
		for (int i = 0; i < sections.length; i++) {
			try {
				int nonEmpty = r.readShort();
				int fluids = r.readShort();
				if (nonEmpty < 0 || nonEmpty > 4096 || fluids < 0 || fluids > 4096) {
					throw new ProtocolException("counts nonEmpty=" + nonEmpty + " fluid=" + fluids + " outside 0..4096");
				}
				PalettedContainer states = PalettedContainer.read(r, PalettedContainer.Kind.BLOCK_STATES, this.registry.size());
				PalettedContainer biomes = PalettedContainer.read(r, PalettedContainer.Kind.BIOMES, this.biomeCount);
				sections[i] = new ChunkSection(nonEmpty, fluids, states, biomes);
			} catch (ProtocolException e) {
				throw new ProtocolException("Chunk " + chunkX + "," + chunkZ + " section " + i + " (y=" + ((this.dimension.minY() >> 4) + i) + "): "
					+ e.getMessage(), e);
			}
		}
		if (r.remaining() != 0) {
			throw new ProtocolException("Chunk " + chunkX + "," + chunkZ + ": " + r.remaining() + " trailing bytes after " + sections.length + " sections");
		}
		return sections;
	}

	/** Biom-Puffer aus {@code chunks_biomes}: je Sektion nur ein Biom-Container, ohne Restbytes. */
	public PalettedContainer[] decodeBiomes(final byte[] buffer) throws ProtocolException {
		PacketReader r = new PacketReader(buffer);
		PalettedContainer[] biomes = new PalettedContainer[sectionCount()];
		for (int i = 0; i < biomes.length; i++) {
			biomes[i] = PalettedContainer.read(r, PalettedContainer.Kind.BIOMES, this.biomeCount);
		}
		if (r.remaining() != 0) {
			throw new ProtocolException("chunks_biomes: " + r.remaining() + " trailing bytes after " + biomes.length + " sections");
		}
		return biomes;
	}

	private void validateHeightmaps(final Map<Integer, long[]> heightmaps) throws ProtocolException {
		int bits = this.dimension.heightmapBits();
		int expected = BitStorage.longsFor(bits, 256);
		for (Map.Entry<Integer, long[]> e : heightmaps.entrySet()) {
			if (e.getValue().length != expected) {
				throw new ProtocolException("Heightmap " + e.getKey() + " has " + e.getValue().length + " longs, expected " + expected);
			}
			for (int v : BitStorage.unpack(e.getValue(), bits, 256)) {
				if (v > this.dimension.height()) {
					throw new ProtocolException("Heightmap " + e.getKey() + " value " + v + " exceeds height " + this.dimension.height());
				}
			}
		}
	}

	/**
	 * Überträgt Lichtdaten wie {@code ClientPacketListener#readSectionList}: Bit in der Maske → Array, Bit in der
	 * Leer-Maske → leere Sektion, sonst unverändert. Bits außerhalb {@code sections + 2} sind ungültig.
	 */
	public void applyLight(final Chunk chunk, final Clientbound.LightData light) throws ProtocolException {
		applyLayer(chunk, true, light.skyMask(), light.emptySkyMask(), light.skyArrays());
		applyLayer(chunk, false, light.blockMask(), light.emptyBlockMask(), light.blockArrays());
	}

	private static void applyLayer(final Chunk chunk, final boolean sky, final BitSet mask, final BitSet empty, final List<byte[]> arrays)
		throws ProtocolException {
		int count = chunk.lightSectionCount();
		String layer = sky ? "sky" : "block";
		if (mask.length() > count || empty.length() > count) {
			throw new ProtocolException(layer + " light mask uses bit " + (Math.max(mask.length(), empty.length()) - 1) + ", only " + count
				+ " light sections");
		}
		if (mask.intersects(empty)) {
			throw new ProtocolException(layer + " light section marked both present and empty");
		}
		if (arrays.size() != mask.cardinality()) {
			throw new ProtocolException(layer + " light: " + arrays.size() + " arrays for " + mask.cardinality() + " mask bits");
		}
		int next = 0;
		for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
			chunk.setLight(sky, i, arrays.get(next++));
		}
		for (int i = empty.nextSetBit(0); i >= 0; i = empty.nextSetBit(i + 1)) {
			chunk.setLight(sky, i, Chunk.EMPTY_LIGHT);
		}
	}
}
