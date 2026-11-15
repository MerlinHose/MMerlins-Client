package dev.mcresearch.protocolclient.view;

import java.util.Arrays;

/**
 * Sortierung durchscheinender Flächen (docs/48 §3) wie {@code VertexSorting#byDistance}: Flächen (je 6 Vertices im
 * {@link MeshBuilder}-Format) nach dem Abstand ihres Mittelpunkts zur Kamera, fern zu nah, damit Blending mit Tiefenschreiben
 * die richtige Überlagerung ergibt. Kamera in Sektionskoordinaten.
 */
public final class TranslucentSort {
	private TranslucentSort() {
	}

	public static int[] sort(final int[] data, final double cx, final double cy, final double cz) {
		int stride = MeshBuilder.INTS_PER_VERTEX;
		int per = 6 * stride;
		int quads = data.length / per;
		double[] dist = new double[quads];
		Integer[] order = new Integer[quads];
		for (int q = 0; q < quads; q++) {
			double sx = 0;
			double sy = 0;
			double sz = 0;
			for (int v = 0; v < 6; v++) {
				int i = q * per + v * stride;
				sx += Float.intBitsToFloat(data[i]);
				sy += Float.intBitsToFloat(data[i + 1]);
				sz += Float.intBitsToFloat(data[i + 2]);
			}
			double dx = sx / 6 - cx;
			double dy = sy / 6 - cy;
			double dz = sz / 6 - cz;
			dist[q] = dx * dx + dy * dy + dz * dz;
			order[q] = q;
		}
		Arrays.sort(order, (a, b) -> Double.compare(dist[b], dist[a]));
		int[] out = new int[data.length];
		for (int i = 0; i < quads; i++) {
			System.arraycopy(data, order[i] * per, out, i * per, per);
		}
		return out;
	}
}
