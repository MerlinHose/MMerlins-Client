package dev.mcresearch.protocolclient.world;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Bodenhöhen aus der Heightmap {@code MOTION_BLOCKING} der empfangenen Chunks (docs/15 §4).
 *
 * <p>Pro Chunk 256 Werte (Index {@code x + z·16}), gepackt mit {@code bits} Bits pro Wert, {@code 64 / bits} Werte pro Long,
 * ohne Überlauf über Long-Grenzen ({@code SimpleBitStorage}). Wert + {@code min_y} = erste freie Höhe über dem höchsten
 * bewegungsblockierenden Block oder Fluid ({@code Heightmap#getFirstAvailable}).
 *
 * <p>Nicht berücksichtigt: spätere Blockänderungen ({@code block_update}), Blockformen (Stufen, Zäune), Nachbarspalten
 * der 0,6 m breiten Hitbox. Abweichungen korrigiert der Server per {@code player_position}.
 */
public final class TerrainCache {
	public static final int MAX_CHUNKS = 4096;

	private final DimensionInfo dimension;
	private final int bits;
	private final int valuesPerLong;
	private final int expectedLongs;
	private final Map<Long, int[]> chunks = new HashMap<>();

	public TerrainCache(final DimensionInfo dimension) {
		this.dimension = dimension;
		this.bits = dimension.heightmapBits();
		this.valuesPerLong = 64 / this.bits;
		this.expectedLongs = (256 + this.valuesPerLong - 1) / this.valuesPerLong;
	}

	public DimensionInfo dimension() {
		return this.dimension;
	}

	private static long key(final int chunkX, final int chunkZ) {
		return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
	}

	public synchronized void accept(final Clientbound.ChunkHeightmaps chunk) throws ProtocolException {
		long[] raw = chunk.heightmaps().get(Clientbound.ChunkHeightmaps.MOTION_BLOCKING);
		if (raw == null) {
			return; // ohne MOTION_BLOCKING keine Bodenhöhe; Spalte bleibt unbekannt
		}
		if (raw.length != this.expectedLongs) {
			throw new ProtocolException("MOTION_BLOCKING heightmap has " + raw.length + " longs, expected " + this.expectedLongs
				+ " for height " + this.dimension.height());
		}
		int[] values = new int[256];
		long mask = (1L << this.bits) - 1;
		for (int i = 0; i < 256; i++) {
			long cell = raw[i / this.valuesPerLong];
			int v = (int) ((cell >>> ((i % this.valuesPerLong) * this.bits)) & mask);
			if (v > this.dimension.height()) {
				throw new ProtocolException("Heightmap value " + v + " exceeds dimension height " + this.dimension.height());
			}
			values[i] = v;
		}
		if (this.chunks.size() >= MAX_CHUNKS && !this.chunks.containsKey(key(chunk.chunkX(), chunk.chunkZ()))) {
			throw new ProtocolException("More than " + MAX_CHUNKS + " chunks loaded");
		}
		this.chunks.put(key(chunk.chunkX(), chunk.chunkZ()), values);
	}

	public synchronized void forget(final int chunkX, final int chunkZ) {
		this.chunks.remove(key(chunkX, chunkZ));
	}

	public synchronized int loadedChunks() {
		return this.chunks.size();
	}

	/** Erste freie Blockhöhe (Weltkoordinate) der Spalte, die (x, z) enthält; leer, wenn der Chunk nicht geladen ist. */
	public synchronized OptionalInt groundY(final double x, final double z) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		int[] values = this.chunks.get(key(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16)));
		if (values == null) {
			return OptionalInt.empty();
		}
		return OptionalInt.of(values[Math.floorMod(bx, 16) + Math.floorMod(bz, 16) * 16] + this.dimension.minY());
	}
}
