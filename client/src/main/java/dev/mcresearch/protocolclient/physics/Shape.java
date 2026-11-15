package dev.mcresearch.protocolclient.physics;

import java.util.Arrays;
import java.util.List;

/**
 * Kollisionsform: Vereinigung von Boxen plus Y-Gitterkoordinaten (docs/22 §3). Entspricht in der Kollisionswirkung einer
 * {@code VoxelShape}; die Y-Koordinaten sind die von {@code VoxelShape#getCoords(Y)} und bestimmen die Step-up-Kandidaten.
 */
public final class Shape {
	public static final Shape EMPTY = new Shape(new Aabb[0], new double[0]);
	public static final Shape BLOCK = new Shape(new Aabb[]{new Aabb(0, 0, 0, 1, 1, 1)}, new double[]{0, 1});

	private final Aabb[] boxes;
	private final double[] yCoords;

	public Shape(final Aabb[] boxes, final double[] yCoords) {
		this.boxes = boxes.clone();
		this.yCoords = yCoords.clone();
		for (int i = 1; i < this.yCoords.length; i++) {
			if (!(this.yCoords[i] > this.yCoords[i - 1])) {
				throw new IllegalArgumentException("y coordinates not strictly ascending: " + Arrays.toString(yCoords));
			}
		}
		if (this.boxes.length == 0 && this.yCoords.length != 0) {
			throw new IllegalArgumentException("empty shape with coordinates");
		}
	}

	public static Shape box(final double x1, final double y1, final double z1, final double x2, final double y2, final double z2) {
		Aabb b = new Aabb(x1, y1, z1, x2, y2, z2);
		return new Shape(new Aabb[]{b}, b.minY() == b.maxY() ? new double[]{b.minY()} : new double[]{b.minY(), b.maxY()});
	}

	public boolean isEmpty() {
		return this.boxes.length == 0;
	}

	public List<Aabb> boxes() {
		return List.of(this.boxes);
	}

	public double[] yCoords() {
		return this.yCoords.clone();
	}

	double yCoord(final int i) {
		return this.yCoords[i];
	}

	int yCoordCount() {
		return this.yCoords.length;
	}

	public Shape move(final double dx, final double dy, final double dz) {
		if (isEmpty()) {
			return this;
		}
		Aabb[] moved = new Aabb[this.boxes.length];
		for (int i = 0; i < moved.length; i++) {
			moved[i] = this.boxes[i].move(dx, dy, dz);
		}
		double[] ys = new double[this.yCoords.length];
		for (int i = 0; i < ys.length; i++) {
			ys[i] = this.yCoords[i] + dy;
		}
		return new Shape(moved, ys);
	}

	public double maxY() {
		double m = Double.NEGATIVE_INFINITY;
		for (Aabb b : this.boxes) {
			m = Math.max(m, b.maxY());
		}
		return m;
	}

	/** Mindestens eine Box schneidet {@code query} mit positivem Volumen ({@code Shapes#joinIsNotEmpty(…, AND)}). */
	public boolean intersects(final Aabb query) {
		for (Aabb b : this.boxes) {
			if (b.intersects(query)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Begrenzt eine Bewegung der Box {@code moving} um {@code distance} entlang {@code axis} durch diese Form
	 * ({@code VoxelShape#collide}): nächste Box in Bewegungsrichtung mit Querüberlappung &gt; 1e−7; bereits durchdrungene
	 * Boxen werden ignoriert.
	 */
	public double collide(final Axis axis, final Aabb moving, double distance) {
		if (isEmpty()) {
			return distance;
		}
		if (Math.abs(distance) < Aabb.EPSILON) {
			return 0.0;
		}
		Axis b = axis == Axis.X ? Axis.Y : Axis.X;
		Axis c = axis == Axis.Z ? Axis.Y : Axis.Z;
		for (Aabb box : this.boxes) {
			// Zellbereich wie findIndex(min + 1e-7) … findIndex(max − 1e-7): obere Kante strikt, untere einschließlich
			if (box.max(b) <= moving.min(b) + Aabb.EPSILON || box.min(b) > moving.max(b) - Aabb.EPSILON
				|| box.max(c) <= moving.min(c) + Aabb.EPSILON || box.min(c) > moving.max(c) - Aabb.EPSILON) {
				continue;
			}
			if (distance > 0) {
				double d = box.min(axis) - moving.max(axis);
				if (d >= -Aabb.EPSILON) {
					distance = Math.min(distance, d);
				}
			} else {
				double d = box.max(axis) - moving.min(axis);
				if (d <= Aabb.EPSILON) {
					distance = Math.max(distance, d);
				}
			}
		}
		return distance;
	}

	@Override
	public String toString() {
		return isEmpty() ? "empty" : Arrays.toString(this.boxes);
	}
}
