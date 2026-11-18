package dev.mcresearch.protocolclient.view.model;

import dev.mcresearch.protocolclient.view.Mat4;

/**
 * M14 (docs/55 §3): veränderliche Pose eines Modells für ein Bild. {@link #reset()} stellt die Grundpose des Layers her (wie
 * {@code ModelPart#resetPose}); Animationen setzen oder addieren danach einzelne Werte. {@link #matrices} liefert je Teil die
 * Matrix Modellwurzel → Teil (Verschiebung in Pixeln, Drehung Z·Y·X, Skalierung – Reihenfolge wie {@code translateAndRotate}).
 *
 * <p>Nicht threadsicher; ein Objekt je Zeichenthread und Modell wiederverwenden.
 */
public final class ModelPose {
	public final ModelLibrary.Model model;
	public final float[] x, y, z, xRot, yRot, zRot, xScale, yScale, zScale;
	public final boolean[] visible;
	private final Mat4[] out;

	public ModelPose(final ModelLibrary.Model model) {
		this.model = model;
		int n = model.parts().size();
		this.x = new float[n];
		this.y = new float[n];
		this.z = new float[n];
		this.xRot = new float[n];
		this.yRot = new float[n];
		this.zRot = new float[n];
		this.xScale = new float[n];
		this.yScale = new float[n];
		this.zScale = new float[n];
		this.visible = new boolean[n];
		this.out = new Mat4[n];
		reset();
	}

	public ModelPose reset() {
		for (ModelLibrary.Part p : this.model.parts()) {
			int i = p.index();
			this.x[i] = p.x();
			this.y[i] = p.y();
			this.z[i] = p.z();
			this.xRot[i] = p.xRot();
			this.yRot[i] = p.yRot();
			this.zRot[i] = p.zRot();
			this.xScale[i] = p.xScale();
			this.yScale[i] = p.yScale();
			this.zScale[i] = p.zScale();
			this.visible[i] = true;
		}
		return this;
	}

	/** Index des Teils oder -1 (Animationen ignorieren fehlende Teile). */
	public int part(final String name) {
		return this.model.part(name);
	}

	/** Teil samt Kindern unsichtbar, wenn ein Vorfahr unsichtbar ist. */
	public boolean effectivelyVisible(final int i) {
		for (int p = i; p >= 0; p = this.model.parts().get(p).parent()) {
			if (!this.visible[p]) {
				return false;
			}
		}
		return true;
	}

	/** Matrizen Modellwurzel → Teil (Eltern vor Kindern). */
	public Mat4[] matrices() {
		for (ModelLibrary.Part p : this.model.parts()) {
			int i = p.index();
			Mat4 local = Mat4.translation(this.x[i], this.y[i], this.z[i]);
			if (this.zRot[i] != 0) {
				local = local.multiply(Mat4.rotationZ(this.zRot[i]));
			}
			if (this.yRot[i] != 0) {
				local = local.multiply(Mat4.rotationY(this.yRot[i]));
			}
			if (this.xRot[i] != 0) {
				local = local.multiply(Mat4.rotationX(this.xRot[i]));
			}
			if (this.xScale[i] != 1 || this.yScale[i] != 1 || this.zScale[i] != 1) {
				local = local.multiply(Mat4.scale(this.xScale[i], this.yScale[i], this.zScale[i]));
			}
			this.out[i] = p.parent() < 0 ? local : this.out[p.parent()].multiply(local);
		}
		return this.out;
	}

	/** Hilfen für Animationen (fehlende Teile werden übergangen). */
	public void setRot(final String name, final float rx, final float ry, final float rz) {
		int i = part(name);
		if (i >= 0) {
			this.xRot[i] = rx;
			this.yRot[i] = ry;
			this.zRot[i] = rz;
		}
	}

	public void setXRot(final String name, final float v) {
		int i = part(name);
		if (i >= 0) {
			this.xRot[i] = v;
		}
	}

	public void setYRot(final String name, final float v) {
		int i = part(name);
		if (i >= 0) {
			this.yRot[i] = v;
		}
	}

	public void setZRot(final String name, final float v) {
		int i = part(name);
		if (i >= 0) {
			this.zRot[i] = v;
		}
	}

	public void addXRot(final String name, final float v) {
		int i = part(name);
		if (i >= 0) {
			this.xRot[i] += v;
		}
	}

	public void addYRot(final String name, final float v) {
		int i = part(name);
		if (i >= 0) {
			this.yRot[i] += v;
		}
	}

	public void addZRot(final String name, final float v) {
		int i = part(name);
		if (i >= 0) {
			this.zRot[i] += v;
		}
	}

	public void addPos(final String name, final float dx, final float dy, final float dz) {
		int i = part(name);
		if (i >= 0) {
			this.x[i] += dx;
			this.y[i] += dy;
			this.z[i] += dz;
		}
	}

	public void setVisible(final String name, final boolean v) {
		int i = part(name);
		if (i >= 0) {
			this.visible[i] = v;
		}
	}

	public float xRot(final String name) {
		int i = part(name);
		return i < 0 ? 0 : this.xRot[i];
	}

	public float yRot(final String name) {
		int i = part(name);
		return i < 0 ? 0 : this.yRot[i];
	}

	public float zRot(final String name) {
		int i = part(name);
		return i < 0 ? 0 : this.zRot[i];
	}
}
