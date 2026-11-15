package dev.mcresearch.protocolclient.view;

/**
 * Sichtpyramide aus Projektion × Ansicht (Gribb/Hartmann-Ebenenextraktion) für das Frustum-Culling von Chunk-Sektionen
 * (docs/25 §6). Koordinaten relativ zur Kamera.
 */
public final class Frustum {
	private final float[][] planes = new float[6][4];

	public Frustum(final Mat4 viewProjection) {
		for (int i = 0; i < 3; i++) {
			for (int sign = 0; sign < 2; sign++) {
				float[] p = this.planes[i * 2 + sign];
				float s = sign == 0 ? 1f : -1f;
				for (int c = 0; c < 4; c++) {
					p[c] = viewProjection.get(c, 3) + s * viewProjection.get(c, i);
				}
				float len = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
				for (int c = 0; c < 4; c++) {
					p[c] /= len;
				}
			}
		}
	}

	/** Box (relativ zur Kamera) liegt zumindest teilweise in der Sichtpyramide. */
	public boolean intersects(final float minX, final float minY, final float minZ, final float maxX, final float maxY, final float maxZ) {
		for (float[] p : this.planes) {
			// positiver Eckpunkt: der Punkt der Box, der am weitesten in Richtung der Ebenennormalen liegt
			float x = p[0] >= 0 ? maxX : minX;
			float y = p[1] >= 0 ? maxY : minY;
			float z = p[2] >= 0 ? maxZ : minZ;
			if (p[0] * x + p[1] * y + p[2] * z + p[3] < 0) {
				return false;
			}
		}
		return true;
	}
}
