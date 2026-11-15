package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import java.util.Arrays;
import java.util.Locale;
import org.lwjgl.opengl.GL;

/**
 * Leistungsmessung je Bild (M12 Phase K, docs/49 §5). Nur Grafikthread.
 *
 * <ul>
 *   <li>Bildzeit: Abstand zweier Bildanfänge (enthält VSync-Wartezeit).</li>
 *   <li>CPU-Bildzeit: Bildanfang bis vor {@code glfwSwapBuffers} (Eingabe, Meshing-Verwaltung, Upload, Zeichenaufrufe).</li>
 *   <li>GPU-Bildzeit: {@code GL_TIME_ELAPSED}-Abfrage um alle Zeichenbefehle eines Bildes; Ergebnisse werden mit bis zu
 *       {@link #QUERIES} Bildern Verzögerung abgeholt, nie blockierend.</li>
 *   <li>VRAM: {@code GL_NVX_gpu_memory_info} (nur NVIDIA; sonst −1) – belegter Grafikspeicher des ganzen Systems.</li>
 * </ul>
 * Ein Messfenster beginnt mit {@link #begin} und endet mit {@link #report}; Werte zwischen den Fenstern werden verworfen.
 */
final class FrameProfiler implements AutoCloseable {
	private static final int QUERIES = 4;
	private static final int GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX = 0x9048;
	private static final int GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
	private static final int MAX_SAMPLES = 1 << 16;

	private final int[] queries = new int[QUERIES];
	private final boolean[] pending = new boolean[QUERIES];
	private final boolean nvxMemory;
	private int queryIndex;
	private boolean queryOpen;
	private long frameStart;
	private long lastFrameStart;

	private final double[] frameMs = new double[MAX_SAMPLES];
	private final double[] cpuMs = new double[MAX_SAMPLES];
	private int frames;
	private double gpuSum;
	private double gpuMax;
	private int gpuSamples;
	private long windowStart;
	private int drawCallSum;
	private long translucentVertexSum;
	private int translucentSectionSum;

	FrameProfiler() {
		glGenQueries(this.queries);
		for (int i = 0; i < QUERIES; i++) {
			GlResources.created("query");
		}
		this.nvxMemory = GL.getCapabilities().GL_NVX_gpu_memory_info;
	}

	/** Anfang eines Bildes (vor dem Löschen des Bildschirms). */
	void frameStart(final long now) {
		if (this.lastFrameStart != 0 && this.windowStart != 0 && this.frames < MAX_SAMPLES) {
			this.frameMs[this.frames] = (now - this.lastFrameStart) / 1e6;
		}
		this.lastFrameStart = now;
		this.frameStart = now;
		collect(false);
		if (!this.pending[this.queryIndex]) {
			glBeginQuery(GL_TIME_ELAPSED, this.queries[this.queryIndex]);
			this.queryOpen = true;
		}
	}

	/** Ende der Zeichenbefehle (vor dem Tausch der Puffer); Zähler des Bildes. */
	void frameEnd(final int drawCalls, final int translucentSections, final long translucentVertices) {
		if (this.queryOpen) {
			glEndQuery(GL_TIME_ELAPSED);
			this.pending[this.queryIndex] = true;
			this.queryOpen = false;
			this.queryIndex = (this.queryIndex + 1) % QUERIES;
		}
		if (this.windowStart != 0 && this.frames < MAX_SAMPLES) {
			this.cpuMs[this.frames] = (System.nanoTime() - this.frameStart) / 1e6;
			this.drawCallSum += drawCalls;
			this.translucentSectionSum += translucentSections;
			this.translucentVertexSum += translucentVertices;
			this.frames++;
		}
	}

	private void collect(final boolean wait) {
		for (int i = 0; i < QUERIES; i++) {
			if (!this.pending[i]) {
				continue;
			}
			if (!wait && glGetQueryObjecti(this.queries[i], GL_QUERY_RESULT_AVAILABLE) != GL_TRUE) {
				continue;
			}
			long ns = glGetQueryObjecti64(this.queries[i], GL_QUERY_RESULT);
			this.pending[i] = false;
			if (this.windowStart != 0) {
				double ms = ns / 1e6;
				this.gpuSum += ms;
				this.gpuMax = Math.max(this.gpuMax, ms);
				this.gpuSamples++;
			}
		}
	}

	/** Neues Messfenster. */
	void begin(final long now) {
		this.windowStart = now;
		this.frames = 0;
		this.gpuSum = 0;
		this.gpuMax = 0;
		this.gpuSamples = 0;
		this.drawCallSum = 0;
		this.translucentSectionSum = 0;
		this.translucentVertexSum = 0;
	}

	boolean active() {
		return this.windowStart != 0;
	}

	/** Belegter Grafikspeicher in MB (gesamtes System) oder −1. */
	int vramUsedMb() {
		if (!this.nvxMemory) {
			return -1;
		}
		int total = glGetInteger(GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX);
		int avail = glGetInteger(GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX);
		return (total - avail) / 1024;
	}

	/** Kennzahlen des laufenden Fensters als Berichtszeile; das Fenster endet damit. */
	String report(final long now) {
		int n = this.frames;
		double seconds = (now - this.windowStart) / 1e9;
		double[] f = Arrays.copyOf(this.frameMs, Math.max(1, n));
		double[] c = Arrays.copyOf(this.cpuMs, Math.max(1, n));
		double fSum = 0;
		double cSum = 0;
		double cMax = 0;
		int spikes = 0;
		for (int i = 0; i < n; i++) {
			fSum += f[i];
			cSum += c[i];
			cMax = Math.max(cMax, c[i]);
			if (f[i] > 33.4) {
				spikes++;
			}
		}
		Arrays.sort(f, 0, Math.max(1, n));
		double p99 = n == 0 ? 0 : f[Math.min(n - 1, (int) Math.floor(n * 0.99))];
		double maxFrame = n == 0 ? 0 : f[n - 1];
		String line = String.format(Locale.ROOT,
			"frames=%d seconds=%.1f avgFps=%.1f low1Fps=%.1f p99FrameMs=%.2f maxFrameMs=%.2f spikes33ms=%d cpuMsAvg=%.3f cpuMsMax=%.2f "
				+ "gpuMsAvg=%s gpuMsMax=%s drawsAvg=%.1f translucentSectionsAvg=%.1f translucentVertsAvg=%.0f vramUsedMB=%d",
			n, seconds, n == 0 ? 0 : 1000.0 * n / fSum, p99 == 0 ? 0 : 1000.0 / p99, p99, maxFrame, spikes, n == 0 ? 0 : cSum / n, cMax,
			this.gpuSamples == 0 ? "-" : String.format(Locale.ROOT, "%.3f", this.gpuSum / this.gpuSamples),
			this.gpuSamples == 0 ? "-" : String.format(Locale.ROOT, "%.2f", this.gpuMax), n == 0 ? 0 : (double) this.drawCallSum / n,
			n == 0 ? 0 : (double) this.translucentSectionSum / n, n == 0 ? 0 : (double) this.translucentVertexSum / n, vramUsedMb());
		this.windowStart = 0;
		return line;
	}

	@Override
	public void close() {
		if (this.queryOpen) {
			glEndQuery(GL_TIME_ELAPSED);
			this.queryOpen = false;
		}
		glDeleteQueries(this.queries);
		for (int i = 0; i < QUERIES; i++) {
			GlResources.deleted("query");
		}
	}
}
