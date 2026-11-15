package dev.mcresearch.protocolclient.view;

/**
 * 4×4-Matrix in Spaltenreihenfolge (wie OpenGL erwartet), {@code m[col * 4 + row]}. Eigene Implementierung, keine
 * Bibliothek (docs/24 §3). Unveränderlich.
 */
public final class Mat4 {
	private final float[] m;

	private Mat4(final float[] m) {
		this.m = m;
	}

	public static Mat4 identity() {
		float[] m = new float[16];
		m[0] = m[5] = m[10] = m[15] = 1f;
		return new Mat4(m);
	}

	/**
	 * Perspektive wie {@code gluPerspective}: rechtshändig, Blick entlang −Z, Tiefe auf [−1, 1].
	 *
	 * @param fovYDegrees vertikaler Sichtwinkel
	 */
	public static Mat4 perspective(final float fovYDegrees, final float aspect, final float near, final float far) {
		if (!(fovYDegrees > 1 && fovYDegrees < 179) || !(aspect > 0) || !(near > 0) || !(far > near)) {
			throw new IllegalArgumentException("invalid perspective fov=" + fovYDegrees + " aspect=" + aspect + " near=" + near + " far=" + far);
		}
		float f = (float) (1.0 / Math.tan(Math.toRadians(fovYDegrees) / 2.0));
		float[] m = new float[16];
		m[0] = f / aspect;
		m[5] = f;
		m[10] = (far + near) / (near - far);
		m[11] = -1f;
		m[14] = 2f * far * near / (near - far);
		return new Mat4(m);
	}

	public static Mat4 translation(final float x, final float y, final float z) {
		Mat4 t = identity();
		t.m[12] = x;
		t.m[13] = y;
		t.m[14] = z;
		return t;
	}

	public static Mat4 rotationX(final float radians) {
		Mat4 r = identity();
		float c = (float) Math.cos(radians);
		float s = (float) Math.sin(radians);
		r.m[5] = c;
		r.m[6] = s;
		r.m[9] = -s;
		r.m[10] = c;
		return r;
	}

	public static Mat4 rotationZ(final float radians) {
		Mat4 r = identity();
		float c = (float) Math.cos(radians);
		float s = (float) Math.sin(radians);
		r.m[0] = c;
		r.m[1] = s;
		r.m[4] = -s;
		r.m[5] = c;
		return r;
	}

	public static Mat4 scale(final float x, final float y, final float z) {
		Mat4 r = identity();
		r.m[0] = x;
		r.m[5] = y;
		r.m[10] = z;
		return r;
	}

	public static Mat4 rotationY(final float radians) {
		Mat4 r = identity();
		float c = (float) Math.cos(radians);
		float s = (float) Math.sin(radians);
		r.m[0] = c;
		r.m[2] = -s;
		r.m[8] = s;
		r.m[10] = c;
		return r;
	}

	/**
	 * Kameramatrix eines Minecraft-Spielers: Yaw 0 blickt nach +Z, 90 nach −X; Pitch positiv blickt nach unten
	 * (docs/24 §4). Die Weltposition ist die Augenposition.
	 */
	public static Mat4 minecraftView(final double eyeX, final double eyeY, final double eyeZ, final float yawDegrees, final float pitchDegrees) {
		// OpenGL blickt entlang −Z. Yaw 0 soll +Z sein → um 180° drehen; Minecraft-Yaw dreht im Uhrzeigersinn (von oben)
		Mat4 pitch = rotationX((float) Math.toRadians(pitchDegrees));
		Mat4 yaw = rotationY((float) Math.toRadians(yawDegrees + 180.0f));
		// Relative Koordinaten: Translation erfolgt im Shader über einen Kamera-Offset (Präzision bei großen Koordinaten)
		return pitch.multiply(yaw);
	}

	/** this × other. */
	public Mat4 multiply(final Mat4 o) {
		float[] r = new float[16];
		for (int c = 0; c < 4; c++) {
			for (int row = 0; row < 4; row++) {
				float sum = 0;
				for (int k = 0; k < 4; k++) {
					sum += this.m[k * 4 + row] * o.m[c * 4 + k];
				}
				r[c * 4 + row] = sum;
			}
		}
		return new Mat4(r);
	}

	/** Transformiert einen Punkt (w = 1) und liefert homogene Koordinaten {x, y, z, w}. */
	public float[] transform(final float x, final float y, final float z) {
		float[] out = new float[4];
		for (int row = 0; row < 4; row++) {
			out[row] = this.m[row] * x + this.m[4 + row] * y + this.m[8 + row] * z + this.m[12 + row];
		}
		return out;
	}

	public float get(final int col, final int row) {
		return this.m[col * 4 + row];
	}

	/** Kopie für {@code glUniformMatrix4fv} (Spaltenreihenfolge, ohne Transponieren). */
	public float[] toArray() {
		return this.m.clone();
	}
}
