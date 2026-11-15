package dev.mcresearch.protocolclient.physics;

import java.util.HashSet;
import java.util.Set;

/**
 * Zellen, die eine bewegte Hitbox auf ihrem Weg berührt, in der Besuchsreihenfolge von Vanilla (M11, docs/44 §3):
 * {@code BlockGetter#forEachBlockIntersectedBetween} mit {@code BlockPos#betweenCornersInDirection} und dem Eckpunkt-Durchlauf
 * {@code addCollisionsAlongTravel}. Die Reihenfolge ist wichtig, wenn mehrere Zellen dieselbe Größe verändern (Blasensäule).
 */
final class BlockTraversal {
	@FunctionalInterface
	interface Visitor {
		/** Zelle besuchen; false bricht ab. */
		boolean visit(int x, int y, int z, int iteration);
	}

	private BlockTraversal() {
	}

	private static long key(final int x, final int y, final int z) {
		return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFFL);
	}

	private static int floor(final double v) {
		return (int) Math.floor(v);
	}

	static boolean forEachBlockIntersectedBetween(final Vec3d from, final Vec3d to, final Aabb atTarget, final Visitor visitor) {
		Vec3d travel = to.subtract(from.x(), from.y(), from.z());
		float eps = 1.0E-5F;
		if (travel.lengthSqr() < eps * eps) {
			// BlockPos#betweenClosed: x am schnellsten, dann y, dann z
			int x0 = floor(atTarget.minX());
			int y0 = floor(atTarget.minY());
			int z0 = floor(atTarget.minZ());
			int x1 = floor(atTarget.maxX());
			int y1 = floor(atTarget.maxY());
			int z1 = floor(atTarget.maxZ());
			for (int z = z0; z <= z1; z++) {
				for (int y = y0; y <= y1; y++) {
					for (int x = x0; x <= x1; x++) {
						if (!visitor.visit(x, y, z, 0)) {
							return false;
						}
					}
				}
			}
			return true;
		}
		Set<Long> visited = new HashSet<>();
		Aabb start = atTarget.move(-travel.x(), -travel.y(), -travel.z());
		if (!corners(floor(start.minX()), floor(start.minY()), floor(start.minZ()), floor(start.maxX()), floor(start.maxY()), floor(start.maxZ()), travel,
			(x, y, z, it) -> {
				if (!visitor.visit(x, y, z, 0)) {
					return false;
				}
				visited.add(key(x, y, z));
				return true;
			})) {
			return false;
		}
		int iterations = alongTravel(visited, travel, atTarget, visitor);
		if (iterations < 0) {
			return false;
		}
		int last = iterations + 1;
		return corners(floor(atTarget.minX()), floor(atTarget.minY()), floor(atTarget.minZ()), floor(atTarget.maxX()), floor(atTarget.maxY()),
			floor(atTarget.maxZ()), travel, (x, y, z, it) -> !visited.add(key(x, y, z)) || visitor.visit(x, y, z, last));
	}

	/** {@code BlockPos#betweenCornersInDirection}: Start an der Ecke gegen die Bewegungsrichtung, Achsen in {@code axisStepOrder}. */
	static boolean corners(final int ax, final int ay, final int az, final int bx, final int by, final int bz, final Vec3d direction, final Visitor v) {
		int minX = Math.min(ax, bx);
		int minY = Math.min(ay, by);
		int minZ = Math.min(az, bz);
		int maxX = Math.max(ax, bx);
		int maxY = Math.max(ay, by);
		int maxZ = Math.max(az, bz);
		int[] diff = {maxX - minX, maxY - minY, maxZ - minZ};
		int[] startCorner = {direction.x() >= 0.0 ? minX : maxX, direction.y() >= 0.0 ? minY : maxY, direction.z() >= 0.0 ? minZ : maxZ};
		Axis[] axes = Axis.stepOrder(direction);
		int[][] dirs = new int[3][3];
		int[] maxIndex = new int[3];
		for (int i = 0; i < 3; i++) {
			int a = axes[i].ordinal();
			dirs[i][a] = direction.get(axes[i]) >= 0.0 ? 1 : -1;
			maxIndex[i] = diff[a];
		}
		for (int first = 0; first <= maxIndex[0]; first++) {
			for (int second = 0; second <= maxIndex[1]; second++) {
				for (int third = 0; third <= maxIndex[2]; third++) {
					int x = startCorner[0] + dirs[0][0] * first + dirs[1][0] * second + dirs[2][0] * third;
					int y = startCorner[1] + dirs[0][1] * first + dirs[1][1] * second + dirs[2][1] * third;
					int z = startCorner[2] + dirs[0][2] * first + dirs[1][2] * second + dirs[2][2] * third;
					if (!v.visit(x, y, z, 0)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** {@code BlockGetter#getFurthestCorner} (wörtlich nach der Referenz, einschließlich der Achsvertauschung). */
	private static int[] furthestCorner(final Vec3d d) {
		double xDot = Math.abs(d.x());
		double yDot = Math.abs(d.y());
		double zDot = Math.abs(d.z());
		int xSign = d.x() >= 0.0 ? 1 : -1;
		int ySign = d.y() >= 0.0 ? 1 : -1;
		int zSign = d.z() >= 0.0 ? 1 : -1;
		if (xDot <= yDot && xDot <= zDot) {
			return new int[]{-xSign, -zSign, ySign};
		}
		return yDot <= zDot ? new int[]{zSign, -ySign, -xSign} : new int[]{-ySign, xSign, -zSign};
	}

	private static int sign(final double v) {
		return v == 0.0 ? 0 : v > 0.0 ? 1 : -1;
	}

	private static double frac(final double v) {
		return v - Math.floor(v);
	}

	/** {@code BlockGetter#addCollisionsAlongTravel}: Eckpunkt-Strahl durch das Zellgitter, je getroffener Zelle eine Zellscheibe. */
	private static int alongTravel(final Set<Long> visited, final Vec3d deltaMove, final Aabb atTarget, final Visitor visitor) {
		double sizeX = atTarget.maxX() - atTarget.minX();
		double sizeY = atTarget.maxY() - atTarget.minY();
		double sizeZ = atTarget.maxZ() - atTarget.minZ();
		int[] cornerDir = furthestCorner(deltaMove);
		double cx = (atTarget.minX() + atTarget.maxX()) * 0.5;
		double cy = (atTarget.minY() + atTarget.maxY()) * 0.5;
		double cz = (atTarget.minZ() + atTarget.maxZ()) * 0.5;
		Vec3d toCorner = new Vec3d(cx + sizeX * 0.5 * cornerDir[0], cy + sizeY * 0.5 * cornerDir[1], cz + sizeZ * 0.5 * cornerDir[2]);
		Vec3d fromCorner = toCorner.subtract(deltaMove.x(), deltaMove.y(), deltaMove.z());
		int bx = floor(fromCorner.x());
		int by = floor(fromCorner.y());
		int bz = floor(fromCorner.z());
		int signX = sign(deltaMove.x());
		int signY = sign(deltaMove.y());
		int signZ = sign(deltaMove.z());
		double tDeltaX = signX == 0 ? Double.MAX_VALUE : signX / deltaMove.x();
		double tDeltaY = signY == 0 ? Double.MAX_VALUE : signY / deltaMove.y();
		double tDeltaZ = signZ == 0 ? Double.MAX_VALUE : signZ / deltaMove.z();
		double tX = tDeltaX * (signX > 0 ? 1.0 - frac(fromCorner.x()) : frac(fromCorner.x()));
		double tY = tDeltaY * (signY > 0 ? 1.0 - frac(fromCorner.y()) : frac(fromCorner.y()));
		double tZ = tDeltaZ * (signZ > 0 ? 1.0 - frac(fromCorner.z()) : frac(fromCorner.z()));
		int iterations = 0;
		while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
			if (tX < tY) {
				if (tX < tZ) {
					bx += signX;
					tX += tDeltaX;
				} else {
					bz += signZ;
					tZ += tDeltaZ;
				}
			} else if (tY < tZ) {
				by += signY;
				tY += tDeltaY;
			} else {
				bz += signZ;
				tZ += tDeltaZ;
			}
			Vec3d hit = clip(bx, by, bz, bx + 1, by + 1, bz + 1, fromCorner, toCorner);
			if (hit != null) {
				iterations++;
				double hx = Math.max(bx + 1.0E-5F, Math.min(bx + 1.0 - 1.0E-5F, hit.x()));
				double hy = Math.max(by + 1.0E-5F, Math.min(by + 1.0 - 1.0E-5F, hit.y()));
				double hz = Math.max(bz + 1.0E-5F, Math.min(bz + 1.0 - 1.0E-5F, hit.z()));
				int ox = floor(hx - sizeX * cornerDir[0]);
				int oy = floor(hy - sizeY * cornerDir[1]);
				int oz = floor(hz - sizeZ * cornerDir[2]);
				int current = iterations;
				if (!corners(bx, by, bz, ox, oy, oz, deltaMove, (x, y, z, it) -> !visited.add(key(x, y, z)) || visitor.visit(x, y, z, current))) {
					return -1;
				}
			}
		}
		return iterations;
	}

	/** {@code AABB#clip}: erster Eintrittspunkt der Strecke in die Box (0 &lt; s &lt; 1), sonst null. */
	static Vec3d clip(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ, final Vec3d from,
		final Vec3d to) {
		double[] scale = {1.0};
		double dx = to.x() - from.x();
		double dy = to.y() - from.y();
		double dz = to.z() - from.z();
		boolean hit = false;
		if (dx > 1.0E-7) {
			hit |= clipPoint(scale, dx, dy, dz, minX, minY, maxY, minZ, maxZ, from.x(), from.y(), from.z());
		} else if (dx < -1.0E-7) {
			hit |= clipPoint(scale, dx, dy, dz, maxX, minY, maxY, minZ, maxZ, from.x(), from.y(), from.z());
		}
		if (dy > 1.0E-7) {
			hit |= clipPoint(scale, dy, dz, dx, minY, minZ, maxZ, minX, maxX, from.y(), from.z(), from.x());
		} else if (dy < -1.0E-7) {
			hit |= clipPoint(scale, dy, dz, dx, maxY, minZ, maxZ, minX, maxX, from.y(), from.z(), from.x());
		}
		if (dz > 1.0E-7) {
			hit |= clipPoint(scale, dz, dx, dy, minZ, minX, maxX, minY, maxY, from.z(), from.x(), from.y());
		} else if (dz < -1.0E-7) {
			hit |= clipPoint(scale, dz, dx, dy, maxZ, minX, maxX, minY, maxY, from.z(), from.x(), from.y());
		}
		if (!hit) {
			return null;
		}
		return new Vec3d(from.x() + scale[0] * dx, from.y() + scale[0] * dy, from.z() + scale[0] * dz);
	}

	private static boolean clipPoint(final double[] scale, final double da, final double db, final double dc, final double point, final double minB,
		final double maxB, final double minC, final double maxC, final double fromA, final double fromB, final double fromC) {
		double s = (point - fromA) / da;
		double pb = fromB + s * db;
		double pc = fromC + s * dc;
		if (0.0 < s && s < scale[0] && minB - 1.0E-7 < pb && pb < maxB + 1.0E-7 && minC - 1.0E-7 < pc && pc < maxC + 1.0E-7) {
			scale[0] = s;
			return true;
		}
		return false;
	}
}
