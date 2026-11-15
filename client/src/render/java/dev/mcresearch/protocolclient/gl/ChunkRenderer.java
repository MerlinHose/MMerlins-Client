package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.view.BlockModelProvider;
import dev.mcresearch.protocolclient.view.Frustum;
import dev.mcresearch.protocolclient.view.SectionMesher;
import dev.mcresearch.protocolclient.world.SectionSnapshot;
import dev.mcresearch.protocolclient.world.WorldListener;
import dev.mcresearch.protocolclient.world.WorldState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chunk-Darstellung (docs/25 §5–§7).
 *
 * <p>Threads: Der Netzwerk-Thread meldet Änderungen über {@link WorldListener} – hier wird nur vorgemerkt. Mesh-Threads
 * (fest 2) kopieren eine Sektion samt Rand ({@link WorldState#sectionSnapshot}) und bauen die Vertexdaten. Nur der
 * Grafikthread erzeugt, zeichnet und löscht GPU-Puffer ({@link #frame}).
 */
public final class ChunkRenderer implements WorldListener, AutoCloseable {
	public static final int MESH_THREADS = 2;
	/** Höchstens so viele Mesh-Uploads je Frame (gleichmäßige Bildzeit). */
	public static final int UPLOADS_PER_FRAME = 24;
	/** Höchstens so viele gleichzeitig laufende Mesh-Aufträge (begrenzt CPU-Zwischenspeicher). */
	public static final int MAX_PENDING_BUILDS = 32;

	private record SectionKey(int x, int y, int z) {
	}

	private record Built(SectionKey key, long version, SectionMesher.Result result, long nanos) {
	}

	/** M12: je Sektion drei Schichten; durchscheinende Flächen mit CPU-Kopie zum Neusortieren (docs/48 §3). */
	private static final class SectionMeshes {
		GpuMesh solid;
		GpuMesh cutout;
		GpuMesh translucent;
		int[] translucentData;
		double sortX = Double.NaN;
		double sortY;
		double sortZ;
		long version;

		void close() {
			if (this.solid != null) {
				this.solid.close();
			}
			if (this.cutout != null) {
				this.cutout.close();
			}
			if (this.translucent != null) {
				this.translucent.close();
			}
			this.solid = null;
			this.cutout = null;
			this.translucent = null;
			this.translucentData = null;
		}

		boolean empty() {
			return this.solid == null && this.cutout == null && this.translucent == null;
		}
	}

	/** Höchstens so viele durchscheinende Flächen werden je Bild neu sortiert (nächste Sektionen zuerst; begrenzt die Bildzeit). */
	public static final int RESORT_QUADS_PER_FRAME = 12_000;

	/**
	 * Durchscheinende Flächen (je 6 Vertices) nach Abstand ihres Mittelpunkts zur Kamera ordnen, fern zu nah (wie
	 * {@code VertexSorting#byDistance}); Kamera in Sektionskoordinaten.
	 */
	static int[] sortTranslucent(final int[] data, final double cx, final double cy, final double cz) {
		return dev.mcresearch.protocolclient.view.TranslucentSort.sort(data, cx, cy, cz);
	}


	private final WorldState world;
	private final SectionMesher.Context context;
	private final int minSection;
	private final int sectionCount;
	private final Set<SectionKey> dirty = ConcurrentHashMap.newKeySet();
	private final Set<Long> unloaded = ConcurrentHashMap.newKeySet();
	private final Map<SectionKey, AtomicLong> versions = new ConcurrentHashMap<>();
	private final Set<SectionKey> building = ConcurrentHashMap.newKeySet();
	private final ConcurrentLinkedQueue<Built> results = new ConcurrentLinkedQueue<>();
	private final Map<SectionKey, SectionMeshes> meshes = new HashMap<>(); // nur Grafikthread
	private final ExecutorService pool;

	// Messwerte
	private final AtomicLong builds = new AtomicLong();
	private final AtomicLong buildNanosTotal = new AtomicLong();
	private final AtomicLong buildNanosMax = new AtomicLong();
	private final AtomicInteger debugBlocks = new AtomicInteger();
	private int drawCalls;
	private volatile int lastCamChunkX;
	private volatile int lastCamChunkZ;
	private volatile int lastRenderDistance = Integer.MAX_VALUE;
	private int drawnSections;
	private long drawnVertices;
	private int translucentSections;
	private long translucentVertices;
	private long resorts;
	private long gpuBytes;
	private long uploads;
	private long deletes;

	public ChunkRenderer(final WorldState world, final BlockModelProvider models) {
		this(world, SectionMesher.Context.simple(models));
	}

	/** M12: Mesh-Aufträge mit Darstellungsfakten, Tönung, Biomen und Lichtoptionen (docs/47). */
	public ChunkRenderer(final WorldState world, final SectionMesher.Context context) {
		this.world = world;
		this.context = context;
		this.minSection = world.dimension().minY() >> 4;
		this.sectionCount = world.dimension().height() >> 4;
		AtomicInteger n = new AtomicInteger();
		this.pool = Executors.newFixedThreadPool(MESH_THREADS, r -> {
			Thread t = new Thread(r, "mesh-" + n.incrementAndGet());
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		});
		// bereits geladene Chunks (Fenster nach dem Login geöffnet)
		for (var c : world.chunks()) {
			markChunk(c.x(), c.z());
		}
		world.setListener(this);
	}

	// ----- WorldListener (Netzwerk-Thread, unter der Weltsperre: nur vormerken) -----

	@Override
	public void chunkLoaded(final int chunkX, final int chunkZ) {
		this.unloaded.remove(chunkKey(chunkX, chunkZ));
		markChunk(chunkX, chunkZ);
		// Randflächen der Nachbarn neu bewerten
		markChunk(chunkX + 1, chunkZ);
		markChunk(chunkX - 1, chunkZ);
		markChunk(chunkX, chunkZ + 1);
		markChunk(chunkX, chunkZ - 1);
	}

	@Override
	public void chunkUnloaded(final int chunkX, final int chunkZ) {
		this.unloaded.add(chunkKey(chunkX, chunkZ));
		// laufende Aufträge dieses Chunks veralten lassen (Snapshot evtl. vor dem Entladen genommen)
		for (int s = 0; s < this.sectionCount; s++) {
			this.versions.computeIfAbsent(new SectionKey(chunkX, this.minSection + s, chunkZ), kk -> new AtomicLong()).incrementAndGet();
		}
		markChunk(chunkX + 1, chunkZ);
		markChunk(chunkX - 1, chunkZ);
		markChunk(chunkX, chunkZ + 1);
		markChunk(chunkX, chunkZ - 1);
	}

	@Override
	public void blockChanged(final int x, final int y, final int z) {
		int sx = Math.floorDiv(x, 16);
		int sy = Math.floorDiv(y, 16);
		int sz = Math.floorDiv(z, 16);
		mark(sx, sy, sz);
		// Nachbarsektionen, wenn der Block am Rand liegt (deren Randflächen hängen davon ab)
		int lx = x & 15;
		int ly = y & 15;
		int lz = z & 15;
		if (lx == 0) {
			mark(sx - 1, sy, sz);
		} else if (lx == 15) {
			mark(sx + 1, sy, sz);
		}
		if (ly == 0) {
			mark(sx, sy - 1, sz);
		} else if (ly == 15) {
			mark(sx, sy + 1, sz);
		}
		if (lz == 0) {
			mark(sx, sy, sz - 1);
		} else if (lz == 15) {
			mark(sx, sy, sz + 1);
		}
	}

	@Override
	public void lightChanged(final int chunkX, final int chunkZ) {
		markChunk(chunkX, chunkZ);
	}

	@Override
	public void lightSectionsChanged(final java.util.Set<Long> packedSections) {
		// Mesh einer Sektion liest das Licht der Nachbarzellen am Rand (Snapshot 18³): Sektion und 6 Nachbarn neu bauen
		for (long p : packedSections) {
			int sx = dev.mcresearch.protocolclient.protocol.codec.Positions.sectionX(p);
			int sy = dev.mcresearch.protocolclient.protocol.codec.Positions.sectionY(p);
			int sz = dev.mcresearch.protocolclient.protocol.codec.Positions.sectionZ(p);
			mark(sx, sy, sz);
			mark(sx - 1, sy, sz);
			mark(sx + 1, sy, sz);
			mark(sx, sy - 1, sz);
			mark(sx, sy + 1, sz);
			mark(sx, sy, sz - 1);
			mark(sx, sy, sz + 1);
		}
	}

	private void markChunk(final int cx, final int cz) {
		for (int s = 0; s < this.sectionCount; s++) {
			mark(cx, this.minSection + s, cz);
		}
	}

	private void mark(final int sx, final int sy, final int sz) {
		if (sy < this.minSection || sy >= this.minSection + this.sectionCount) {
			return;
		}
		SectionKey k = new SectionKey(sx, sy, sz);
		this.versions.computeIfAbsent(k, kk -> new AtomicLong()).incrementAndGet();
		this.dirty.add(k);
	}

	private static long chunkKey(final int cx, final int cz) {
		return WorldState.key(cx, cz);
	}

	// ----- Grafikthread -----

	/**
	 * Ein Frame: Entladungen anwenden, neue Mesh-Aufträge nahe der Kamera vergeben, fertige Meshes hochladen.
	 *
	 * @param camChunkX Chunk der Kamera (Priorität nach Abstand)
	 */
	public void frame(final int camChunkX, final int camSectionY, final int camChunkZ, final int renderDistance) {
		// 1) Entladene Chunks: GPU-Puffer freigeben
		if (!this.unloaded.isEmpty()) {
			for (Iterator<Map.Entry<SectionKey, SectionMeshes>> it = this.meshes.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<SectionKey, SectionMeshes> e = it.next();
				if (this.unloaded.contains(chunkKey(e.getKey().x(), e.getKey().z()))) {
					this.gpuBytes -= bytes(e.getValue());
					e.getValue().close();
					this.deletes++;
					it.remove();
				}
			}
			this.dirty.removeIf(k -> this.unloaded.contains(chunkKey(k.x(), k.z())));
			this.unloaded.clear();
		}
		// 2) Aufträge: nächstgelegene schmutzige Sektionen in Sichtweite zuerst; Vormerkungen für nicht geladene Chunks verwerfen
		this.dirty.removeIf(k -> this.world.chunk(k.x(), k.z()) == null);
		this.lastCamChunkX = camChunkX;
		this.lastCamChunkZ = camChunkZ;
		this.lastRenderDistance = renderDistance;
		int free = MAX_PENDING_BUILDS - this.building.size();
		if (free > 0 && !this.dirty.isEmpty()) {
			List<SectionKey> candidates = new ArrayList<>();
			for (SectionKey k : this.dirty) {
				if (Math.abs(k.x() - camChunkX) <= renderDistance && Math.abs(k.z() - camChunkZ) <= renderDistance && !this.building.contains(k)) {
					candidates.add(k);
				}
			}
			candidates.sort((a, b) -> Integer.compare(dist(a, camChunkX, camSectionY, camChunkZ), dist(b, camChunkX, camSectionY, camChunkZ)));
			for (int i = 0; i < Math.min(free, candidates.size()); i++) {
				SectionKey k = candidates.get(i);
				this.dirty.remove(k);
				this.building.add(k);
				long version = this.versions.get(k).get();
				this.pool.execute(() -> build(k, version));
			}
		}
		// 3) Fertige Meshes hochladen (begrenzt je Frame)
		for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
			Built b = this.results.poll();
			if (b == null) {
				break;
			}
			if (b.version() < this.versions.get(b.key()).get()) {
				continue; // veraltet: neuere Änderung ist schon vorgemerkt
			}
			SectionMeshes m = this.meshes.computeIfAbsent(b.key(), k -> new SectionMeshes());
			this.gpuBytes -= bytes(m);
			m.close();
			if (b.result() != null && !b.result().isEmpty()) {
				m.solid = b.result().solid().length > 0 ? new GpuMesh(b.result().solid()) : null;
				m.cutout = b.result().cutout().length > 0 ? new GpuMesh(b.result().cutout()) : null;
				if (b.result().translucent().length > 0) {
					m.translucentData = b.result().translucent();
					m.translucent = new GpuMesh(m.translucentData);
					m.sortX = Double.NaN; // beim nächsten Zeichnen sortieren
				}
				this.uploads++;
			}
			m.version = b.version();
			this.gpuBytes += bytes(m);
			if (m.empty()) {
				this.meshes.remove(b.key());
			}
		}
	}

	private static int dist(final SectionKey k, final int cx, final int cy, final int cz) {
		int dx = k.x() - cx;
		int dy = k.y() - cy;
		int dz = k.z() - cz;
		return dx * dx + dy * dy / 4 + dz * dz;
	}

	private static long bytes(final SectionMeshes m) {
		return (m.solid == null ? 0 : m.solid.bytes()) + (m.cutout == null ? 0 : m.cutout.bytes()) + (m.translucent == null ? 0 : m.translucent.bytes());
	}

	/** Mesh-Thread: Kopie der Sektion, Meshing, Ergebnis vormerken. */
	private void build(final SectionKey k, final long version) {
		try {
			long t0 = System.nanoTime();
			SectionSnapshot snap = this.world.sectionSnapshot(k.x(), k.y(), k.z());
			SectionMesher.Result r = snap == null ? null : SectionMesher.build(snap, this.context);
			long dt = System.nanoTime() - t0;
			this.builds.incrementAndGet();
			this.buildNanosTotal.addAndGet(dt);
			this.buildNanosMax.accumulateAndGet(dt, Math::max);
			if (r != null) {
				this.debugBlocks.addAndGet(r.debugBlocks());
			}
			this.results.add(new Built(k, version, r, dt));
		} catch (RuntimeException e) {
			System.err.println("[render] mesh build failed for " + k + ": " + e);
		} finally {
			this.building.remove(k);
		}
	}

	/**
	 * Zeichnet alle Sektionen in Sichtweite und im Frustum in drei Durchgängen (docs/48 §3, wie {@code ChunkSectionLayer}):
	 * undurchsichtig (ohne Alphatest), ausgestanzt (Alphatest 0,5), durchscheinend (Alphatest 0,1, Blending
	 * SRC_ALPHA/ONE_MINUS_SRC_ALPHA, Tiefe wird geschrieben; Sektionen fern zu nah, Flächen je Sektion nach Abstand sortiert).
	 * Tiefentest LEQUAL für alle Durchgänge; der Zustand wird je Durchgang vollständig gesetzt und am Ende zurückgesetzt.
	 */
	public void draw(final ShaderProgram shader, final Frustum frustum, final double camX, final double camY, final double camZ,
		final int renderDistance, final int camChunkX, final int camChunkZ) {
		drawOpaque(shader, frustum, camX, camY, camZ, renderDistance, camChunkX, camChunkZ);
		drawTranslucent(shader, camX, camY, camZ);
	}

	/** Sichtbare Sektionen des laufenden Bildes (von {@link #drawOpaque} bestimmt, von {@link #drawTranslucent} genutzt). */
	private List<Map.Entry<SectionKey, SectionMeshes>> visibleThisFrame = new ArrayList<>();

	/** Durchgänge 1 und 2 (undurchsichtig, ausgestanzt); Entities folgen danach, dann {@link #drawTranslucent}. */
	public void drawOpaque(final ShaderProgram shader, final Frustum frustum, final double camX, final double camY, final double camZ,
		final int renderDistance, final int camChunkX, final int camChunkZ) {
		this.drawCalls = 0;
		this.drawnSections = 0;
		this.drawnVertices = 0;
		this.translucentSections = 0;
		this.translucentVertices = 0;
		List<Map.Entry<SectionKey, SectionMeshes>> visible = new ArrayList<>();
		for (Map.Entry<SectionKey, SectionMeshes> e : this.meshes.entrySet()) {
			SectionKey k = e.getKey();
			if (Math.abs(k.x() - camChunkX) > renderDistance || Math.abs(k.z() - camChunkZ) > renderDistance) {
				continue;
			}
			float ox = (float) (k.x() * 16 - camX);
			float oy = (float) (k.y() * 16 - camY);
			float oz = (float) (k.z() * 16 - camZ);
			if (frustum.intersects(ox, oy, oz, ox + 16, oy + 16, oz + 16)) {
				visible.add(e);
			}
		}
		this.drawnSections = visible.size();
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_LEQUAL);
		glDisable(GL_BLEND);
		glDepthMask(true);
		// 1) undurchsichtig
		shader.set("uAlphaCutoff", -1f);
		for (Map.Entry<SectionKey, SectionMeshes> e : visible) {
			drawLayer(shader, e.getKey(), e.getValue().solid, camX, camY, camZ);
		}
		// 2) ausgestanzt
		shader.set("uAlphaCutoff", 0.5f);
		for (Map.Entry<SectionKey, SectionMeshes> e : visible) {
			drawLayer(shader, e.getKey(), e.getValue().cutout, camX, camY, camZ);
		}
		this.visibleThisFrame = visible;
	}

	/** Durchgang 3: durchscheinend (Shader wie bei {@link #drawOpaque} gebunden). */
	public void drawTranslucent(final ShaderProgram shader, final double camX, final double camY, final double camZ) {
		List<Map.Entry<SectionKey, SectionMeshes>> visible = this.visibleThisFrame;
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_LEQUAL);
		// 3) durchscheinend: Sektionen fern zu nah, Flächen sortiert
		visible.sort((x, y) -> Double.compare(sqDist(y.getKey(), camX, camY, camZ), sqDist(x.getKey(), camX, camY, camZ)));
		resortNearest(visible, camX, camY, camZ);
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		glDepthMask(true);
		shader.set("uAlphaCutoff", 0.1f);
		for (Map.Entry<SectionKey, SectionMeshes> e : visible) {
			if (e.getValue().translucent != null) {
				drawLayer(shader, e.getKey(), e.getValue().translucent, camX, camY, camZ);
				this.translucentSections++;
				this.translucentVertices += e.getValue().translucent.vertexCount();
			}
		}
		glDisable(GL_BLEND);
		glDepthMask(true);
	}

	private void drawLayer(final ShaderProgram shader, final SectionKey k, final GpuMesh mesh, final double camX, final double camY, final double camZ) {
		if (mesh == null) {
			return;
		}
		shader.set("uOffset", (float) (k.x() * 16 - camX), (float) (k.y() * 16 - camY), (float) (k.z() * 16 - camZ));
		mesh.draw();
		this.drawCalls++;
		this.drawnVertices += mesh.vertexCount();
	}

	/** Neusortierung, wenn sich die Kamera seit der letzten Sortierung um mehr als einen Block bewegt hat (nächste zuerst). */
	private void resortNearest(final List<Map.Entry<SectionKey, SectionMeshes>> farToNear, final double camX, final double camY, final double camZ) {
		int budget = RESORT_QUADS_PER_FRAME;
		int quadInts = 6 * dev.mcresearch.protocolclient.view.MeshBuilder.INTS_PER_VERTEX;
		for (int i = farToNear.size() - 1; i >= 0 && budget > 0; i--) {
			SectionKey k = farToNear.get(i).getKey();
			SectionMeshes m = farToNear.get(i).getValue();
			if (m.translucent == null || m.translucentData == null) {
				continue;
			}
			double lx = camX - k.x() * 16;
			double ly = camY - k.y() * 16;
			double lz = camZ - k.z() * 16;
			if (!Double.isNaN(m.sortX)) {
				double dx = lx - m.sortX;
				double dy = ly - m.sortY;
				double dz = lz - m.sortZ;
				if (dx * dx + dy * dy + dz * dz < 1.0) {
					continue;
				}
			}
			m.translucentData = sortTranslucent(m.translucentData, lx, ly, lz);
			m.translucent.update(m.translucentData);
			m.sortX = lx;
			m.sortY = ly;
			m.sortZ = lz;
			this.resorts++;
			budget -= m.translucentData.length / quadInts;
		}
	}

	private static double sqDist(final SectionKey k, final double x, final double y, final double z) {
		double dx = k.x() * 16 + 8 - x;
		double dy = k.y() * 16 + 8 - y;
		double dz = k.z() * 16 + 8 - z;
		return dx * dx + dy * dy + dz * dz;
	}

	/** Messwerte für Overlay und Bericht. */
	public record Stats(int meshes, int drawCalls, int drawnSections, long drawnVertices, long gpuBytes, long builds, double avgBuildMs,
		double maxBuildMs, int dirty, int building, long uploads, long deletes, int debugBlocks, int translucentSections, long translucentVertices,
		long resorts) {
	}

	public Stats stats() {
		long b = this.builds.get();
		return new Stats(this.meshes.size(), this.drawCalls, this.drawnSections, this.drawnVertices, this.gpuBytes, b,
			b == 0 ? 0 : this.buildNanosTotal.get() / 1e6 / b, this.buildNanosMax.get() / 1e6, pendingWork() - this.building.size(), this.building.size(), this.uploads,
			this.deletes, this.debugBlocks.get(), this.translucentSections, this.translucentVertices, this.resorts);
	}

	/** Offene Arbeit innerhalb der Sichtweite (vorgemerkt, im Bau, fertig zum Hochladen). */
	public int pendingWork() {
		int n = 0;
		for (SectionKey k : this.dirty) {
			if (Math.abs(k.x() - this.lastCamChunkX) <= this.lastRenderDistance && Math.abs(k.z() - this.lastCamChunkZ) <= this.lastRenderDistance) {
				n++;
			}
		}
		return n + this.building.size() + this.results.size();
	}

	/** Nur auf dem Grafikthread: alle GPU-Puffer freigeben, Mesh-Threads beenden. */
	@Override
	public void close() {
		this.world.setListener(null);
		this.pool.shutdownNow();
		for (SectionMeshes m : this.meshes.values()) {
			m.close();
		}
		this.meshes.clear();
		this.gpuBytes = 0;
		if (this.pool instanceof ThreadPoolExecutor t) {
			t.getQueue().clear();
		}
	}
}
