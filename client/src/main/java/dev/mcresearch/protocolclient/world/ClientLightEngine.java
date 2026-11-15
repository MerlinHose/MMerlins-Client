package dev.mcresearch.protocolclient.world;

import dev.mcresearch.protocolclient.physics.ShapeRegistry;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Clientseitige Lichtneuberechnung bei Blockänderungen (M7-Korrektur, docs/29 §3.3), eigene Umsetzung des Verfahrens von
 * Vanilla 26.3 ({@code LightEngine}, {@code BlockLightEngine}, {@code SkyLightEngine}, {@code ChunkSkyLightSources}).
 *
 * <p>Hintergrund: Der Server schickt {@code light_update} nach Blockänderungen nur an Spieler, für die der Chunk am Rand der
 * Sichtweite liegt ({@code ChunkHolder#broadcastChanges} → {@code getPlayers(pos, true)}). Für alle anderen Chunks berechnet der
 * Vanilla-Client das Licht selbst ({@code LevelChunk#setBlockState} → {@code skyLightSources.update} + {@code checkBlock}).
 *
 * <p>Verfahren je Ebene: Prüfknoten → Abnahme-Warteschlange (Licht entfernen, Nachbarn mit höherem Wert wieder einspeisen) →
 * Zunahme-Warteschlange (Ausbreitung mit {@code Pegel − max(1, Dämpfung)}, gesperrt durch {@code faceShapeOccludes}). Himmelslicht:
 * Quellen je Spalte = niedrigste y, ab der nach oben nichts mehr verdeckt ({@code isEdgeOccluded}); dort Pegel 15, nach unten
 * ohne Abnahme. Nur Sektionen mit Lichtdaten werden beschrieben ({@code storingLightForSection}).
 *
 * <p>Vereinfachungen (docs/29 §3.3): {@code updateSectionStatus} (leer ↔ nicht leer) legt keine neuen Lichtsektionen an;
 * {@code hasLightDataAtOrBelow} gilt ab der untersten Lichtsektion der Welt. Läuft unter der Weltsperre.
 */
final class ClientLightEngine {
	private static final int[] DX = {0, 0, 0, 0, -1, 1};
	private static final int[] DY = {-1, 1, 0, 0, 0, 0};
	private static final int[] DZ = {0, 0, -1, 1, 0, 0};
	private static final int DOWN = 0;
	private static final int UP = 1;
	private static final long DIRECTIONS = 1008L; // Bits 4–9
	private static final long FROM_EMPTY = 1024L;
	private static final long FROM_EMISSION = 2048L;

	private final ShapeRegistry shapes;
	private final BiFunction<Integer, Integer, Chunk> chunks;
	private final int minY; // Weltuntergrenze (Block-y)
	private final int height;
	private final int air;
	private final int bedrock;
	private final LongQueue decrease = new LongQueue();
	private final LongQueue increase = new LongQueue();
	private final Set<Long> touchedSections = new HashSet<>();
	private boolean sky;
	private long updates;

	ClientLightEngine(final ShapeRegistry shapes, final BlockRegistry registry, final int minY, final int height,
		final BiFunction<Integer, Integer, Chunk> chunks) {
		this.shapes = shapes;
		this.chunks = chunks;
		this.minY = minY;
		this.height = height;
		this.air = registry.idOf("minecraft:air", java.util.Map.of());
		this.bedrock = registry.idOf("minecraft:bedrock", java.util.Map.of());
	}

	long updates() {
		return this.updates;
	}

	// ================= Einstieg =================

	/**
	 * Nach einer Blockänderung (Zustand bereits gesetzt): Himmelsquellen der Spalte aktualisieren und beide Ebenen neu
	 * berechnen. Liefert die Sektionen (gepackt x/y/z), deren Licht sich geändert hat.
	 */
	Set<Long> blockChanged(final int[] xyz, final int count) {
		this.touchedSections.clear();
		// 1) Quellen (LevelChunk#setBlockState: skyLightSources.update je Block)
		for (int i = 0; i < count; i++) {
			int x = xyz[3 * i], y = xyz[3 * i + 1], z = xyz[3 * i + 2];
			Chunk c = chunk(x >> 4, z >> 4);
			if (c != null) {
				updateSources(c, x & 15, y, z & 15);
			}
		}
		// 2) LevelLightEngine#runLightUpdates: erst Blocklicht, dann Himmelslicht
		this.sky = false;
		for (int i = 0; i < count; i++) {
			checkNodeBlock(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]);
		}
		run();
		this.sky = true;
		for (int i = 0; i < count; i++) {
			checkNodeSky(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]);
		}
		run();
		this.updates++;
		return new LinkedHashSet<>(this.touchedSections);
	}

	/** Neuer oder neu gesendeter Chunk: Quellen werden bei Bedarf neu bestimmt (ChunkSkyLightSources#fillFrom). */
	void chunkLoaded(final Chunk c) {
		c.setSkySources(null);
	}

	// ================= Speicher =================

	private Chunk chunk(final int cx, final int cz) {
		return this.chunks.apply(cx, cz);
	}

	private int state(final int x, final int y, final int z) {
		Chunk c = chunk(x >> 4, z >> 4);
		if (c == null) {
			return this.bedrock; // LightEngine#getState: nicht geladen → Grundgestein
		}
		if (y < this.minY || y >= this.minY + this.height) {
			return this.air;
		}
		return c.blockState(x, y, z);
	}

	private int lightIndex(final int y) {
		return (y >> 4) - (this.minY >> 4) + 1;
	}

	private boolean storing(final int x, final int y, final int z) {
		Chunk c = chunk(x >> 4, z >> 4);
		return c != null && c.storingLight(this.sky, lightIndex(y));
	}

	private int stored(final int x, final int y, final int z) {
		Chunk c = chunk(x >> 4, z >> 4);
		if (c == null || !c.storingLight(this.sky, lightIndex(y))) {
			return 0;
		}
		return c.storedLight(this.sky, x, y, z);
	}

	private void setStored(final int x, final int y, final int z, final int level) {
		Chunk c = chunk(x >> 4, z >> 4);
		if (c != null && c.storingLight(this.sky, lightIndex(y))) {
			if (c.storedLight(this.sky, x, y, z) != level) {
				c.setStoredLight(this.sky, x, y, z, level);
				this.touchedSections.add(dev.mcresearch.protocolclient.protocol.codec.Positions.packSection(x >> 4, y >> 4, z >> 4));
			}
		}
	}

	private int opacity(final int state) {
		return Math.max(1, this.shapes.lightDampening(state));
	}

	private boolean shapeOccludes(final int from, final int to, final int dir) {
		return ShapeRegistry.faceShapeOccludes(this.shapes.lightFaceMask(from, dir), this.shapes.lightFaceMask(to, dir ^ 1));
	}

	private static long withLevel(final long e, final int level) {
		return e & -16L | level & 15L;
	}

	private static long without(final long e, final int dir) {
		return e & ~(1L << dir + 4);
	}

	private static boolean has(final long e, final int dir) {
		return (e & 1L << dir + 4) != 0L;
	}

	private static long pack(final int x, final int y, final int z) {
		return dev.mcresearch.protocolclient.protocol.codec.Positions.packBlock(x, y, z);
	}

	// ================= Blocklicht =================

	private int emission(final int state) {
		return this.shapes.lightEmission(state);
	}

	private void checkNodeBlock(final int x, final int y, final int z) {
		if (!storing(x, y, z)) {
			return;
		}
		int st = state(x, y, z);
		int emission = emission(st);
		int old = stored(x, y, z);
		long node = pack(x, y, z);
		if (emission < old) {
			setStored(x, y, z, 0);
			this.decrease.add(node, withLevel(DIRECTIONS, old));
		} else {
			this.decrease.add(node, withLevel(DIRECTIONS, 1)); // PULL_LIGHT_IN_ENTRY
		}
		if (emission > 0) {
			this.increase.add(node, withLevel(DIRECTIONS | FROM_EMISSION | (this.shapes.lightEmptyShape(st) ? FROM_EMPTY : 0), emission));
		}
	}

	// ================= Himmelslicht: Quellen je Spalte =================

	private int sourcesMinY() {
		return this.minY - 1;
	}

	private int[] sources(final Chunk c) {
		int[] s = c.skySources();
		if (s == null) {
			s = new int[256];
			int top = this.minY + this.height; // erste y über der Welt
			for (int lz = 0; lz < 16; lz++) {
				for (int lx = 0; lx < 16; lx++) {
					s[lx + lz * 16] = Math.max(findLowestSourceBelow(c, lx, lz, top, this.air), sourcesMinY());
				}
			}
			c.setSkySources(s);
		}
		return s;
	}

	/** {@code ChunkSkyLightSources#isEdgeOccluded}. */
	private boolean edgeOccluded(final int top, final int bottom) {
		if (this.shapes.lightDampening(bottom) != 0) {
			return true;
		}
		return ShapeRegistry.faceShapeOccludes(this.shapes.lightFaceMask(top, DOWN), this.shapes.lightFaceMask(bottom, UP));
	}

	private int blockInChunk(final Chunk c, final int lx, final int y, final int lz) {
		if (y < this.minY || y >= this.minY + this.height) {
			return this.air;
		}
		return c.blockState((c.x() << 4) + lx, y, (c.z() << 4) + lz);
	}

	/** {@code findLowestSourceBelow}: von (startY, startState) abwärts bis zur ersten verdeckten Kante. */
	private int findLowestSourceBelow(final Chunk c, final int lx, final int lz, final int startY, final int startState) {
		int topY = startY;
		int topState = startState;
		for (int y = startY - 1; y >= sourcesMinY(); y--) {
			int bottom = blockInChunk(c, lx, y, lz);
			if (edgeOccluded(topState, bottom)) {
				return topY;
			}
			topState = bottom;
			topY = y;
		}
		return sourcesMinY();
	}

	private int lowestSourceY(final int x, final int z, final int defaultValue) {
		Chunk c = chunk(x >> 4, z >> 4);
		if (c == null) {
			return defaultValue;
		}
		int v = sources(c)[(x & 15) + (z & 15) * 16];
		return v == sourcesMinY() ? Integer.MIN_VALUE : v; // extendSourcesBelowWorld
	}

	/** {@code ChunkSkyLightSources#update}. */
	private void updateSources(final Chunk c, final int lx, final int y, final int lz) {
		int[] s = sources(c);
		int index = lx + lz * 16;
		int current = s[index];
		if (y + 1 < current) {
			return;
		}
		int topState = blockInChunk(c, lx, y + 1, lz);
		int middleState = blockInChunk(c, lx, y, lz);
		if (updateEdge(c, s, index, lx, lz, current, y + 1, topState, y, middleState)) {
			return;
		}
		int bottomState = blockInChunk(c, lx, y - 1, lz);
		updateEdge(c, s, index, lx, lz, current, y, middleState, y - 1, bottomState);
	}

	private boolean updateEdge(final Chunk c, final int[] s, final int index, final int lx, final int lz, final int oldTop, final int topY, final int topState,
		final int bottomY, final int bottomState) {
		if (edgeOccluded(topState, bottomState)) {
			if (topY > oldTop) {
				s[index] = topY;
				return true;
			}
		} else if (topY == oldTop) {
			s[index] = findLowestSourceBelow(c, lx, lz, bottomY, bottomState);
			return true;
		}
		return false;
	}

	// ================= Himmelslicht: Knoten =================

	private void checkNodeSky(final int x, final int y, final int z) {
		int lowest = lowestSourceY(x, z, Integer.MAX_VALUE);
		if (lowest != Integer.MAX_VALUE) {
			updateSourcesInColumn(x, z, lowest);
		}
		if (storing(x, y, z)) {
			long node = pack(x, y, z);
			if (y >= lowest) {
				this.decrease.add(node, withLevel(without(DIRECTIONS, UP), 15)); // REMOVE_SKY_SOURCE_ENTRY
				this.increase.add(node, withLevel(without(DIRECTIONS, UP), 15)); // ADD_SKY_SOURCE_ENTRY
			} else {
				int old = stored(x, y, z);
				if (old > 0) {
					setStored(x, y, z, 0);
					this.decrease.add(node, withLevel(DIRECTIONS, old));
				} else {
					this.decrease.add(node, withLevel(DIRECTIONS, 1));
				}
			}
		}
	}

	private void updateSourcesInColumn(final int x, final int z, final int lowest) {
		int bottom = ((this.minY >> 4) - 1) << 4; // unterste Lichtsektion
		removeSourcesBelow(x, z, lowest, bottom);
		addSourcesAbove(x, z, lowest, bottom);
	}

	private void removeSourcesBelow(final int x, final int z, final int lowest, final int worldBottomY) {
		if (lowest <= worldBottomY) {
			return;
		}
		for (int y = lowest - 1; y >= worldBottomY; y--) {
			if (!storing(x, y, z)) {
				y = ((y >> 4) << 4); // ganze Sektion ohne Daten überspringen
				continue;
			}
			if (stored(x, y, z) != 15) {
				return;
			}
			setStored(x, y, z, 0);
			this.decrease.add(pack(x, y, z), y == lowest - 1 ? withLevel(DIRECTIONS, 15) : withLevel(without(DIRECTIONS, UP), 15));
		}
	}

	private void addSourcesAbove(final int x, final int z, final int lowest, final int worldBottomY) {
		int neighbour = Math.max(Math.max(lowestSourceY(x - 1, z, Integer.MIN_VALUE), lowestSourceY(x + 1, z, Integer.MIN_VALUE)),
			Math.max(lowestSourceY(x, z - 1, Integer.MIN_VALUE), lowestSourceY(x, z + 1, Integer.MIN_VALUE)));
		int startY = Math.max(lowest, worldBottomY);
		int topY = (((this.minY + this.height) >> 4) + 1) << 4; // über der obersten Lichtsektion
		for (int y = startY; y < topY; y++) {
			if (!storing(x, y, z)) {
				continue;
			}
			if (stored(x, y, z) == 15) {
				return;
			}
			setStored(x, y, z, 15);
			if (y < neighbour || y == lowest) {
				this.increase.add(pack(x, y, z), withLevel(without(DIRECTIONS, UP), 15));
			}
		}
	}

	// ================= Ausbreitung (gemeinsam, Unterschiede über this.sky) =================

	private void run() {
		while (!this.decrease.isEmpty()) {
			long node = this.decrease.pollNode();
			long data = this.decrease.pollData();
			propagateDecrease(node, data);
		}
		while (!this.increase.isEmpty()) {
			long node = this.increase.pollNode();
			long data = this.increase.pollData();
			int x = dev.mcresearch.protocolclient.protocol.codec.Positions.blockX(node);
			int y = dev.mcresearch.protocolclient.protocol.codec.Positions.blockY(node);
			int z = dev.mcresearch.protocolclient.protocol.codec.Positions.blockZ(node);
			int level = stored(x, y, z);
			int target = (int) (data & 15L);
			if ((data & FROM_EMISSION) != 0 && level < target) {
				setStored(x, y, z, target);
				level = target;
			}
			if (level == target) {
				propagateIncrease(x, y, z, data, level);
			}
		}
	}

	private void propagateIncrease(final int x, final int y, final int z, final long data, final int fromLevel) {
		int fromState = -1;
		for (int d = 0; d < 6; d++) {
			if (!has(data, d)) {
				continue;
			}
			int tx = x + DX[d], ty = y + DY[d], tz = z + DZ[d];
			if (!storing(tx, ty, tz)) {
				continue;
			}
			int toLevel = stored(tx, ty, tz);
			if (fromLevel - 1 <= toLevel) {
				continue;
			}
			int toState = state(tx, ty, tz);
			int newLevel = fromLevel - opacity(toState);
			if (newLevel <= toLevel) {
				continue;
			}
			if (fromState < 0) {
				fromState = (data & FROM_EMPTY) != 0 ? this.air : state(x, y, z);
			}
			if (!shapeOccludes(fromState, toState, d)) {
				setStored(tx, ty, tz, newLevel);
				if (newLevel > 1) {
					this.increase.add(pack(tx, ty, tz), withLevel(without(DIRECTIONS, d ^ 1) | (this.shapes.lightEmptyShape(toState) ? FROM_EMPTY : 0), newLevel));
				}
			}
		}
	}

	private void propagateDecrease(final long node, final long data) {
		int x = dev.mcresearch.protocolclient.protocol.codec.Positions.blockX(node);
		int y = dev.mcresearch.protocolclient.protocol.codec.Positions.blockY(node);
		int z = dev.mcresearch.protocolclient.protocol.codec.Positions.blockZ(node);
		int oldFrom = (int) (data & 15L);
		for (int d = 0; d < 6; d++) {
			if (!has(data, d)) {
				continue;
			}
			int tx = x + DX[d], ty = y + DY[d], tz = z + DZ[d];
			if (!storing(tx, ty, tz)) {
				continue;
			}
			int toLevel = stored(tx, ty, tz);
			if (toLevel == 0) {
				continue;
			}
			long toNode = pack(tx, ty, tz);
			if (toLevel <= oldFrom - 1) {
				if (this.sky) {
					setStored(tx, ty, tz, 0);
					this.decrease.add(toNode, withLevel(without(DIRECTIONS, d ^ 1), toLevel));
				} else {
					int toState = state(tx, ty, tz);
					int toEmission = emission(toState);
					setStored(tx, ty, tz, 0);
					if (toEmission < toLevel) {
						this.decrease.add(toNode, withLevel(without(DIRECTIONS, d ^ 1), toLevel));
					}
					if (toEmission > 0) {
						this.increase.add(toNode, withLevel(DIRECTIONS | FROM_EMISSION | (this.shapes.lightEmptyShape(toState) ? FROM_EMPTY : 0), toEmission));
					}
				}
			} else {
				this.increase.add(toNode, withLevel(1L << (d ^ 1) + 4, toLevel)); // increaseOnlyOneDirection
			}
		}
	}

	/** FIFO aus (Knoten, Daten)-Paaren ohne Boxing. */
	private static final class LongQueue {
		private long[] a = new long[1024];
		private int head;
		private int tail;

		void add(final long node, final long data) {
			if (this.tail + 2 > this.a.length) {
				if (this.head > 0) {
					System.arraycopy(this.a, this.head, this.a, 0, this.tail - this.head);
					this.tail -= this.head;
					this.head = 0;
				}
				if (this.tail + 2 > this.a.length) {
					this.a = java.util.Arrays.copyOf(this.a, this.a.length * 2);
				}
			}
			this.a[this.tail++] = node;
			this.a[this.tail++] = data;
		}

		boolean isEmpty() {
			return this.head == this.tail;
		}

		long pollNode() {
			return this.a[this.head++];
		}

		long pollData() {
			long d = this.a[this.head++];
			if (this.head == this.tail) {
				this.head = 0;
				this.tail = 0;
			}
			return d;
		}
	}
}
