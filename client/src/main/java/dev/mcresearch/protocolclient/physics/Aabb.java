package dev.mcresearch.protocolclient.physics;

import java.util.Locale;

/**
 * Achsenparallele Box (docs/22 §3). Unveränderlich; Konstruktor ordnet min/max wie {@code AABB(x1, y1, z1, x2, y2, z2)}.
 */
public record Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
	/** Toleranz der Achsenkollision ({@code VoxelShape#collideX}). */
	public static final double EPSILON = 1.0E-7;

	public Aabb {
		if (!(Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ) && Double.isFinite(maxX) && Double.isFinite(maxY)
			&& Double.isFinite(maxZ))) {
			throw new IllegalArgumentException("non-finite box");
		}
		double x1 = Math.min(minX, maxX);
		double x2 = Math.max(minX, maxX);
		double y1 = Math.min(minY, maxY);
		double y2 = Math.max(minY, maxY);
		double z1 = Math.min(minZ, maxZ);
		double z2 = Math.max(minZ, maxZ);
		minX = x1;
		maxX = x2;
		minY = y1;
		maxY = y2;
		minZ = z1;
		maxZ = z2;
	}

	public double min(final Axis a) {
		return switch (a) {
			case X -> this.minX;
			case Y -> this.minY;
			case Z -> this.minZ;
		};
	}

	public double max(final Axis a) {
		return switch (a) {
			case X -> this.maxX;
			case Y -> this.maxY;
			case Z -> this.maxZ;
		};
	}

	public Aabb move(final double dx, final double dy, final double dz) {
		return new Aabb(this.minX + dx, this.minY + dy, this.minZ + dz, this.maxX + dx, this.maxY + dy, this.maxZ + dz);
	}

	public Aabb move(final Vec3d v) {
		return move(v.x(), v.y(), v.z());
	}

	/** {@code AABB#expandTowards}: erweitert nur in Bewegungsrichtung. */
	public Aabb expandTowards(final double dx, final double dy, final double dz) {
		double x1 = this.minX;
		double y1 = this.minY;
		double z1 = this.minZ;
		double x2 = this.maxX;
		double y2 = this.maxY;
		double z2 = this.maxZ;
		if (dx < 0) {
			x1 += dx;
		} else if (dx > 0) {
			x2 += dx;
		}
		if (dy < 0) {
			y1 += dy;
		} else if (dy > 0) {
			y2 += dy;
		}
		if (dz < 0) {
			z1 += dz;
		} else if (dz > 0) {
			z2 += dz;
		}
		return new Aabb(x1, y1, z1, x2, y2, z2);
	}

	public Aabb expandTowards(final Vec3d v) {
		return expandTowards(v.x(), v.y(), v.z());
	}

	/** Echte Überschneidung mit positivem Volumen (offene Intervalle, {@code AABB#intersects}). */
	public boolean intersects(final Aabb o) {
		return this.minX < o.maxX && this.maxX > o.minX && this.minY < o.maxY && this.maxY > o.minY && this.minZ < o.maxZ && this.maxZ > o.minZ;
	}

	public boolean contains(final double x, final double y, final double z) {
		return x >= this.minX && x < this.maxX && y >= this.minY && y < this.maxY && z >= this.minZ && z < this.maxZ;
	}

	public Aabb withMinY(final double y) {
		return new Aabb(this.minX, y, this.minZ, this.maxX, this.maxY, this.maxZ);
	}

	public Aabb withMaxY(final double y) {
		return new Aabb(this.minX, this.minY, this.minZ, this.maxX, y, this.maxZ);
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT, "[%.5f %.5f %.5f .. %.5f %.5f %.5f]", this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
	}
}
