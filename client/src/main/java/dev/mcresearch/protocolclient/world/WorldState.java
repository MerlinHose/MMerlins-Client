package dev.mcresearch.protocolclient.world;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Eigene Weltverwaltung (docs/19): geladene Chunks einer Dimension, Anwendung von Block-, Licht-, Block-Entity- und
 * Biom-Updates, Entladen. Alle Methoden sind synchronisiert (Netzwerk-Thread schreibt, Terminal-Thread liest).
 */
public final class WorldState {
	public static final int MAX_CHUNKS = 4096;

	/** Zähler für Terminal und Tests. */
	public static final class Stats {
		public long chunksLoaded;
		public long chunksReplaced;
		public long chunksUnloaded;
		public long unloadsOfUnknownChunks;
		public long sectionsDecoded;
		public long chunkBytesDecoded;
		public long trailingByteChecks;
		public long nonEmptyCountMismatches;
		public long blockUpdates;
		public long sectionUpdates;
		public long sectionUpdateEntries;
		public long updatesForUnloadedChunks;
		public long lightUpdates;
		public long blockEntityUpdates;
		public long blockEntitiesRemoved;
		public long biomeUpdates;
		/** M7: clientseitige Lichtneuberechnungen und dabei geänderte Sektionen. */
		public long clientLightUpdates;
		public long clientLightSections;
		public final Map<PalettedContainer.PaletteType, Long> blockPalettes = new EnumMap<>(PalettedContainer.PaletteType.class);
		public final Map<PalettedContainer.PaletteType, Long> biomePalettes = new EnumMap<>(PalettedContainer.PaletteType.class);
	}

	private final DimensionInfo dimension;
	private final BlockRegistry registry;
	private final List<String> biomeNames;
	private final ChunkDecoder decoder;
	private final Map<Long, Chunk> chunks = new HashMap<>();
	private final Stats stats = new Stats();
	private final int voidAir;
	private volatile WorldListener listener;

	/** @param biomeNames Einträge von {@code minecraft:worldgen/biome} in Registry-Reihenfolge (ID = Index) */
	public WorldState(final DimensionInfo dimension, final BlockRegistry registry, final List<String> biomeNames) {
		this.dimension = dimension;
		this.registry = registry;
		this.biomeNames = List.copyOf(biomeNames);
		this.decoder = new ChunkDecoder(dimension, registry, biomeNames.size());
		this.voidAir = registry.idOf("minecraft:void_air", Map.of());
	}

	/** Empfänger für Änderungen (Rendering, docs/25 §5); {@code null} = keiner. */
	public void setListener(final WorldListener listener) {
		this.listener = listener;
	}

	public static long key(final int chunkX, final int chunkZ) {
		return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
	}

	public DimensionInfo dimension() {
		return this.dimension;
	}

	public BlockRegistry registry() {
		return this.registry;
	}

	public String biomeName(final int id) {
		return id >= 0 && id < this.biomeNames.size() ? this.biomeNames.get(id) : "#" + id;
	}

	public int biomeCount() {
		return this.biomeNames.size();
	}

	// ----- Pakete -----

	public synchronized Chunk loadChunk(final Clientbound.LevelChunkWithLight packet) throws ProtocolException {
		long key = key(packet.chunkX(), packet.chunkZ());
		boolean replace = this.chunks.containsKey(key);
		if (!replace && this.chunks.size() >= MAX_CHUNKS) {
			throw new ProtocolException("More than " + MAX_CHUNKS + " chunks loaded");
		}
		Chunk chunk = this.decoder.decode(packet);
		this.stats.trailingByteChecks++;
		for (int i = 0; i < chunk.sectionCount(); i++) {
			ChunkSection s = chunk.section(i);
			if (s.countNonAir(this.registry) != s.sentNonEmptyBlockCount()) {
				this.stats.nonEmptyCountMismatches++;
			}
			this.stats.blockPalettes.merge(s.states().paletteType(), 1L, Long::sum);
			this.stats.biomePalettes.merge(s.biomes().paletteType(), 1L, Long::sum);
		}
		this.chunks.put(key, chunk);
		this.stats.chunksLoaded++;
		if (replace) {
			this.stats.chunksReplaced++;
		}
		this.stats.sectionsDecoded += chunk.sectionCount();
		this.stats.chunkBytesDecoded += chunk.dataBytes();
		WorldListener l = this.listener;
		if (l != null) {
			l.chunkLoaded(packet.chunkX(), packet.chunkZ());
		}
		return chunk;
	}

	public synchronized boolean unloadChunk(final int chunkX, final int chunkZ) {
		boolean removed = this.chunks.remove(key(chunkX, chunkZ)) != null;
		if (removed) {
			this.stats.chunksUnloaded++;
			WorldListener l = this.listener;
			if (l != null) {
				l.chunkUnloaded(chunkX, chunkZ);
			}
		} else {
			this.stats.unloadsOfUnknownChunks++;
		}
		return removed;
	}

	/** {@code block_update}: wie {@code ClientLevel#setServerVerifiedBlockState}; ungeladene Chunks werden ignoriert (gezählt). */
	public synchronized boolean applyBlockUpdate(final Clientbound.BlockUpdate p) throws ProtocolException {
		this.stats.blockUpdates++;
		boolean applied = setBlock(p.x(), p.y(), p.z(), p.stateId());
		runLightChecks();
		return applied;
	}

	/** {@code section_blocks_update}: alle Einträge einer Sektion. */
	public synchronized int applySectionUpdate(final Clientbound.SectionBlocksUpdate p) throws ProtocolException {
		this.stats.sectionUpdates++;
		this.stats.sectionUpdateEntries += p.stateIds().length;
		int sectionMin = this.dimension.minY() >> 4;
		if (p.sectionY() < sectionMin || p.sectionY() >= sectionMin + (this.dimension.height() >> 4)) {
			throw new ProtocolException("section_blocks_update for section y=" + p.sectionY() + " outside world height");
		}
		int applied = 0;
		for (int i = 0; i < p.stateIds().length; i++) {
			short local = p.localPositions()[i];
			int x = (p.sectionX() << 4) + ((local >>> 8) & 15);
			int z = (p.sectionZ() << 4) + ((local >>> 4) & 15);
			int y = (p.sectionY() << 4) + (local & 15);
			if (setBlock(x, y, z, p.stateIds()[i])) {
				applied++;
			}
		}
		runLightChecks();
		return applied;
	}

	// ----- M7-Korrektur: Licht nach Blockänderungen clientseitig neu berechnen (ClientLightEngine, docs/29 §3.3) -----

	private ClientLightEngine lightEngine;
	private int[] lightChecks = new int[3 * 64];
	private int lightCheckCount;

	private void queueLightCheck(final int x, final int y, final int z) {
		if (3 * this.lightCheckCount + 3 > this.lightChecks.length) {
			this.lightChecks = java.util.Arrays.copyOf(this.lightChecks, this.lightChecks.length * 2);
		}
		this.lightChecks[3 * this.lightCheckCount] = x;
		this.lightChecks[3 * this.lightCheckCount + 1] = y;
		this.lightChecks[3 * this.lightCheckCount + 2] = z;
		this.lightCheckCount++;
	}

	private void runLightChecks() {
		if (this.lightCheckCount == 0) {
			return;
		}
		if (this.lightEngine == null) {
			this.lightEngine = new ClientLightEngine(dev.mcresearch.protocolclient.physics.ShapeRegistry.get(), this.registry, this.dimension.minY(),
				this.dimension.height(), (cx, cz) -> this.chunks.get(key(cx, cz)));
		}
		java.util.Set<Long> changed = this.lightEngine.blockChanged(this.lightChecks, this.lightCheckCount);
		this.lightCheckCount = 0;
		this.stats.clientLightUpdates++;
		this.stats.clientLightSections += changed.size();
		WorldListener l = this.listener;
		if (l != null && !changed.isEmpty()) {
			l.lightSectionsChanged(changed);
		}
	}

	private boolean setBlock(final int x, final int y, final int z, final int stateId) throws ProtocolException {
		if (!this.registry.isValid(stateId)) {
			throw new ProtocolException("Block update at " + x + "," + y + "," + z + " with unknown state id " + stateId);
		}
		if (y < this.dimension.minY() || y >= this.dimension.minY() + this.dimension.height()) {
			throw new ProtocolException("Block update at y=" + y + " outside world height");
		}
		Chunk chunk = this.chunks.get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
		if (chunk == null) {
			this.stats.updatesForUnloadedChunks++;
			return false;
		}
		int old = chunk.setBlockState(x, y, z, stateId, this.registry);
		// Blockwechsel entfernt die alte Block-Entity (LevelChunk#setBlockState); eine neue kommt per block_entity_data
		if (!this.registry.state(old).name().equals(this.registry.state(stateId).name()) && chunk.removeBlockEntity(x, y, z) != null) {
			this.stats.blockEntitiesRemoved++;
		}
		if (dev.mcresearch.protocolclient.physics.ShapeRegistry.get().hasDifferentLightProperties(old, stateId)) {
			queueLightCheck(x, y, z); // LevelChunk#setBlockState: skyLightSources.update + lightEngine.checkBlock
		}
		WorldListener l = this.listener;
		if (l != null && old != stateId) {
			l.blockChanged(x, y, z);
		}
		return true;
	}

	public synchronized boolean applyLightUpdate(final Clientbound.LightUpdate p) throws ProtocolException {
		this.stats.lightUpdates++;
		Chunk chunk = this.chunks.get(key(p.chunkX(), p.chunkZ()));
		if (chunk == null) {
			// Vanilla speichert Licht auch für noch nicht geladene Chunks; dieser Client verwirft es bewusst (gezählt)
			this.stats.updatesForUnloadedChunks++;
			return false;
		}
		this.decoder.applyLight(chunk, p.light());
		WorldListener l = this.listener;
		if (l != null) {
			l.lightChanged(p.chunkX(), p.chunkZ());
		}
		return true;
	}

	public synchronized boolean applyBlockEntityData(final Clientbound.BlockEntityData p) throws ProtocolException {
		this.stats.blockEntityUpdates++;
		String type = this.registry.blockEntityType(p.typeId());
		if (type == null) {
			throw new ProtocolException("block_entity_data with unknown type " + p.typeId());
		}
		if (p.y() < this.dimension.minY() || p.y() >= this.dimension.minY() + this.dimension.height()) {
			throw new ProtocolException("block_entity_data at y=" + p.y() + " outside world height");
		}
		Chunk chunk = this.chunks.get(key(Math.floorDiv(p.x(), 16), Math.floorDiv(p.z(), 16)));
		if (chunk == null) {
			this.stats.updatesForUnloadedChunks++;
			return false;
		}
		chunk.putBlockEntity(new BlockEntity(p.x(), p.y(), p.z(), p.typeId(), type, p.nbt()));
		return true;
	}

	public synchronized int applyBiomes(final Clientbound.ChunksBiomes p) throws ProtocolException {
		int applied = 0;
		for (Clientbound.ChunkBiomeData data : p.chunks()) {
			this.stats.biomeUpdates++;
			PalettedContainer[] biomes = this.decoder.decodeBiomes(data.buffer());
			Chunk chunk = this.chunks.get(key(data.chunkX(), data.chunkZ()));
			if (chunk == null) {
				this.stats.updatesForUnloadedChunks++;
				continue;
			}
			for (int i = 0; i < biomes.length; i++) {
				chunk.section(i).replaceBiomes(biomes[i]);
			}
			applied++;
		}
		return applied;
	}

	// ----- Abfragen -----

	public synchronized Chunk chunk(final int chunkX, final int chunkZ) {
		return this.chunks.get(key(chunkX, chunkZ));
	}

	public synchronized boolean isLoaded(final int x, final int z) {
		return this.chunks.containsKey(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
	}

	/**
	 * Blockzustand an absoluten Koordinaten; leer, wenn der Chunk nicht geladen ist. Außerhalb der Welthöhe
	 * {@code minecraft:void_air} wie {@code Level#getBlockState}.
	 */
	public synchronized Optional<BlockState> block(final int x, final int y, final int z) {
		Chunk chunk = this.chunks.get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
		if (chunk == null) {
			return Optional.empty();
		}
		if (!chunk.containsY(y)) {
			return Optional.of(this.registry.state(this.voidAir));
		}
		return Optional.of(this.registry.state(chunk.blockState(x, y, z)));
	}

	/** Lichtwert 0–15 (Himmel oder Block) an absoluten Koordinaten; −1 ohne Daten bzw. ungeladenen Chunk. */
	public synchronized int light(final boolean sky, final int x, final int y, final int z) {
		Chunk chunk = this.chunks.get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
		return chunk == null ? -1 : chunk.light(sky, x, y, z);
	}

	/**
	 * Zustands-ID für die Kollision: {@code -1}, wenn der Chunk nicht geladen ist; außerhalb der Welthöhe
	 * {@code minecraft:void_air} (keine Kollision).
	 */

	public synchronized int stateId(final int x, final int y, final int z) {
		Chunk chunk = this.chunks.get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
		if (chunk == null) {
			return -1;
		}
		if (!chunk.containsY(y)) {
			return this.voidAir;
		}
		return chunk.blockState(x, y, z);
	}

	/**
	 * Kopie einer Sektion samt einem Block Rand (docs/25 §4) in einem Schritt unter der Weltsperre; {@code null}, wenn der
	 * Chunk nicht geladen ist oder die Sektion außerhalb der Welthöhe liegt.
	 *
	 * @param sectionY absolute Sektions-y (Block-y / 16)
	 */
	public SectionSnapshot sectionSnapshot(final int chunkX, final int sectionY, final int chunkZ) {
		int n = SectionSnapshot.SIZE;
		int m = SectionSnapshot.MARGIN;
		int ox = chunkX * 16;
		int oy = sectionY * 16;
		int oz = chunkZ * 16;
		// Quart-Biome, die der Zoom für die Zellen −m…15+m braucht ((x − 2) >> 2 und +1)
		int qx0 = (ox - m - 2) >> 2;
		int qy0 = (oy - m - 2) >> 2;
		int qz0 = (oz - m - 2) >> 2;
		int qn = ((15 + m - 2) >> 2) + 1 - ((-m - 2) >> 2) + 1;
		int[] quarts = new int[qn * qn * qn];
		int[] states = new int[n * n * n];
		byte[] light = new byte[n * n * n];
		long seed;
		synchronized (this) {
			if (!copySection(chunkX, sectionY, chunkZ, states, light, quarts, qx0, qy0, qz0, qn)) {
				return null;
			}
			seed = this.hashedSeed;
		}
		// Biom-Zoom außerhalb der Sperre (M12-Leistung: Render-Thread wartet sonst auf die Meshing-Threads)
		int[] biomes = new int[n * n * n];
		BiomeZoom.QuartBiomes source = (qx, qy, qz) -> quarts[((qy - qy0) * qn + (qz - qz0)) * qn + (qx - qx0)];
		for (int ly = -m; ly < 16 + m; ly++) {
			for (int lz = -m; lz < 16 + m; lz++) {
				for (int lx = -m; lx < 16 + m; lx++) {
					biomes[SectionSnapshot.index(lx, ly, lz)] = BiomeZoom.biome(seed, ox + lx, oy + ly, oz + lz, source);
				}
			}
		}
		return new SectionSnapshot(chunkX, sectionY, chunkZ, states, light, biomes);
	}

	/** Kopiert Zustände, Licht und Quart-Biome einer Sektion samt Rand; nur unter der Sperre aufrufen. */
	private boolean copySection(final int chunkX, final int sectionY, final int chunkZ, final int[] states, final byte[] light, final int[] quarts,
		final int qx0, final int qy0, final int qz0, final int qn) {
		Chunk center = this.chunks.get(key(chunkX, chunkZ));
		int minSection = this.dimension.minY() >> 4;
		int sections = this.dimension.height() >> 4;
		if (center == null || sectionY < minSection || sectionY >= minSection + sections) {
			return false;
		}
		int m = SectionSnapshot.MARGIN;
		int ox = chunkX * 16;
		int oy = sectionY * 16;
		int oz = chunkZ * 16;
		int minY = this.dimension.minY();
		int maxY = minY + this.dimension.height();
		Chunk[] neighbours = new Chunk[9];
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				neighbours[(dz + 1) * 3 + dx + 1] = this.chunks.get(key(chunkX + dx, chunkZ + dz));
			}
		}
		for (int ly = -m; ly < 16 + m; ly++) {
			int y = oy + ly;
			for (int lz = -m; lz < 16 + m; lz++) {
				int z = oz + lz;
				int cz = lz < 0 ? 0 : lz > 15 ? 2 : 1;
				for (int lx = -m; lx < 16 + m; lx++) {
					int x = ox + lx;
					int cx = lx < 0 ? 0 : lx > 15 ? 2 : 1;
					Chunk c = neighbours[cz * 3 + cx];
					int i = SectionSnapshot.index(lx, ly, lz);
					if (c == null) {
						states[i] = SectionSnapshot.UNKNOWN_STATE;
						light[i] = SectionSnapshot.pack(15, 0);
					} else if (y < minY || y >= maxY) {
						states[i] = 0;
						light[i] = SectionSnapshot.pack(y >= maxY ? 15 : 0, 0);
					} else {
						states[i] = c.blockState(x, y, z);
						light[i] = SectionSnapshot.pack(c.renderLight(true, x, y, z), c.renderLight(false, x, y, z));
					}
				}
			}
		}
		for (int qy = 0; qy < qn; qy++) {
			for (int qz = 0; qz < qn; qz++) {
				for (int qx = 0; qx < qn; qx++) {
					int ax = qx0 + qx;
					int az = qz0 + qz;
					int dcx = (ax >> 2) - chunkX;
					int dcz = (az >> 2) - chunkZ;
					Chunk c = dcx >= -1 && dcx <= 1 && dcz >= -1 && dcz <= 1 ? neighbours[(dcz + 1) * 3 + dcx + 1] : this.chunks.get(key(ax >> 2, az >> 2));
					quarts[(qy * qn + qz) * qn + qx] = noiseBiome(c, ax, qy0 + qy, az);
				}
			}
		}
		return true;
	}

	/**
	 * M12: oberste Höhe + 1 der Spalte mit einem Block, für den {@code blocking} gilt (Heightmap MOTION_BLOCKING für Niederschlag,
	 * docs/48 §6); aus den aktuellen Blockzuständen, damit Blockänderungen sofort wirken. Ungeladen: Weltuntergrenze.
	 */
	public synchronized int motionBlockingHeight(final int x, final int z, final java.util.function.IntPredicate blocking) {
		Chunk c = this.chunks.get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
		int minY = this.dimension.minY();
		if (c == null) {
			return minY;
		}
		for (int y = minY + this.dimension.height() - 1; y >= minY; y--) {
			if ((y & 15) == 15 && c.sectionEmpty(y)) {
				y -= 15;
				continue;
			}
			if (blocking.test(c.blockState(x, y, z))) {
				return y + 1;
			}
		}
		return minY;
	}

	/** M12: Helligkeit wie {@code Level#getBrightness} (fehlende Himmelsdaten von oben, ungeladen: Himmel 15, Block 0). */
	public synchronized int renderLight(final boolean sky, final int x, final int y, final int z) {
		Chunk c = this.chunks.get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
		if (c == null || !c.containsY(y)) {
			return sky ? 15 : 0;
		}
		return c.renderLight(sky, x, y, z);
	}

	/** M12: gehashter Weltstartwert aus {@code login}/{@code respawn} (Biom-Zoom, docs/47 §4.2). */
	private volatile long hashedSeed;

	public void setHashedSeed(final long seed) {
		this.hashedSeed = seed;
	}

	public long hashedSeed() {
		return this.hashedSeed;
	}

	/** Biom-Registry-Namen (ID = Index). */
	public List<String> biomeNames() {
		return this.biomeNames;
	}

	private int plainsId = -2;

	private int plainsId() {
		if (this.plainsId == -2) {
			this.plainsId = Math.max(0, this.biomeNames.indexOf("minecraft:plains"));
		}
		return this.plainsId;
	}

	/** Rasterbiom (Quart-Koordinaten) wie {@code ClientLevel#getNoiseBiome}: y begrenzt, ungeladener Chunk → Ebene. */
	public synchronized int noiseBiome(final int qx, final int qy, final int qz) {
		return noiseBiome(this.chunks.get(key(qx >> 2, qz >> 2)), qx, qy, qz);
	}

	private int noiseBiome(final Chunk c, final int qx, final int qy, final int qz) {
		if (c == null) {
			return plainsId();
		}
		int qMin = this.dimension.minY() >> 2;
		int qMax = qMin + (this.dimension.height() >> 2) - 1;
		int cqy = Math.max(qMin, Math.min(qMax, qy));
		return c.biome(qx << 2, cqy << 2, qz << 2);
	}

	public synchronized int loadedChunks() {
		return this.chunks.size();
	}

	public synchronized List<Chunk> chunks() {
		return new ArrayList<>(this.chunks.values());
	}

	/** Kopie der Zähler. */
	public synchronized Stats stats() {
		Stats s = new Stats();
		s.chunksLoaded = this.stats.chunksLoaded;
		s.chunksReplaced = this.stats.chunksReplaced;
		s.chunksUnloaded = this.stats.chunksUnloaded;
		s.unloadsOfUnknownChunks = this.stats.unloadsOfUnknownChunks;
		s.sectionsDecoded = this.stats.sectionsDecoded;
		s.chunkBytesDecoded = this.stats.chunkBytesDecoded;
		s.trailingByteChecks = this.stats.trailingByteChecks;
		s.nonEmptyCountMismatches = this.stats.nonEmptyCountMismatches;
		s.blockUpdates = this.stats.blockUpdates;
		s.sectionUpdates = this.stats.sectionUpdates;
		s.sectionUpdateEntries = this.stats.sectionUpdateEntries;
		s.updatesForUnloadedChunks = this.stats.updatesForUnloadedChunks;
		s.lightUpdates = this.stats.lightUpdates;
		s.blockEntityUpdates = this.stats.blockEntityUpdates;
		s.blockEntitiesRemoved = this.stats.blockEntitiesRemoved;
		s.biomeUpdates = this.stats.biomeUpdates;
		s.clientLightUpdates = this.stats.clientLightUpdates;
		s.clientLightSections = this.stats.clientLightSections;
		s.blockPalettes.putAll(this.stats.blockPalettes);
		s.biomePalettes.putAll(this.stats.biomePalettes);
		return s;
	}
}
