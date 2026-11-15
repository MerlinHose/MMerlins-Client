package dev.mcresearch.protocolclient.physics;

import java.util.Locale;

/** Unveränderlicher 3D-Vektor (Position, Geschwindigkeit). */
public record Vec3d(double x, double y, double z) {
	public static final Vec3d ZERO = new Vec3d(0, 0, 0);

	public double get(final Axis a) {
		return switch (a) {
			case X -> this.x;
			case Y -> this.y;
			case Z -> this.z;
		};
	}

	public Vec3d with(final Axis a, final double v) {
		return switch (a) {
			case X -> new Vec3d(v, this.y, this.z);
			case Y -> new Vec3d(this.x, v, this.z);
			case Z -> new Vec3d(this.x, this.y, v);
		};
	}

	public Vec3d add(final Vec3d o) {
		return new Vec3d(this.x + o.x, this.y + o.y, this.z + o.z);
	}

	public Vec3d add(final double dx, final double dy, final double dz) {
		return new Vec3d(this.x + dx, this.y + dy, this.z + dz);
	}

	public double length() {
		return Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
	}

	public Vec3d subtract(final double dx, final double dy, final double dz) {
		return new Vec3d(this.x - dx, this.y - dy, this.z - dz);
	}

	public Vec3d scale(final double s) {
		return new Vec3d(this.x * s, this.y * s, this.z * s);
	}

	public double lengthSqr() {
		return this.x * this.x + this.y * this.y + this.z * this.z;
	}

	public double horizontalDistanceSqr() {
		return this.x * this.x + this.z * this.z;
	}

	/** {@code Vec3#normalize}: Länge &lt; 1e−5F → Nullvektor. */
	public Vec3d normalize() {
		double d = Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
		return d < 1.0E-5F ? ZERO : new Vec3d(this.x / d, this.y / d, this.z / d);
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT, "(%.6f, %.6f, %.6f)", this.x, this.y, this.z);
	}
}
