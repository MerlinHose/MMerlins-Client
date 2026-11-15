package dev.mcresearch.protocolclient.physics;

import dev.mcresearch.protocolclient.view.Face;
import java.util.Locale;

/**
 * Blockauswahl wie der Vanilla-Client (docs/27 §1): {@code LocalPlayer#raycastHitResult} → {@code pick} →
 * {@code Entity#pick} → {@code BlockGetter#clip} mit {@code ClipContext.Block.OUTLINE} und {@code Fluid.NONE}, danach
 * {@code filterHitResult}. Eigene Implementierung der dort dokumentierten Rechenschritte; Formen aus der Umrisstabelle.
 */
public final class BlockRaycast {
	public static final double DEFAULT_BLOCK_RANGE = 4.5;
	public static final double DEFAULT_ENTITY_RANGE = 3.0;
	/** {@code ServerPlayer#CREATIVE_BLOCK_INTERACTION_RANGE_MODIFIER} (+0,5) und Entity-Reichweite (+2,0) im Kreativmodus. */
	public static final double CREATIVE_BLOCK_BONUS = 0.5;
	public static final double CREATIVE_ENTITY_BONUS = 2.0;
	private static final float DEG = (float) (Math.PI / 180.0);

	/**
	 * Treffer ({@code BlockHitResult}): Trefferpunkt, Block, getroffene Seite, „im Block gestartet“; {@code miss} = kein Block
	 * in Reichweite (Position/Seite dann wie Vanilla für den Fehlschlag).
	 */
	public record Hit(boolean miss, double x, double y, double z, int blockX, int blockY, int blockZ, Face face, boolean inside) {
		public static Hit miss(final double x, final double y, final double z, final Face face) {
			return new Hit(true, x, y, z, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), face, false);
		}

		Hit withFace(final Face f) {
			return new Hit(this.miss, this.x, this.y, this.z, this.blockX, this.blockY, this.blockZ, f, this.inside);
		}

		@Override
		public String toString() {
			return this.miss ? "miss" : String.format(Locale.ROOT, "block %d %d %d face %s at (%.4f, %.4f, %.4f)%s", this.blockX, this.blockY, this.blockZ,
				this.face.jsonName, this.x, this.y, this.z, this.inside ? " inside" : "");
		}
	}

	private BlockRaycast() {
	}

	/** {@code Entity#calculateViewVector} mit Tabellen-Sinus. */
	public static Vec3d viewVector(final float xRot, final float yRot) {
		float realXRot = xRot * DEG;
		float realYRot = -yRot * DEG;
		float yCos = Trig.cos(realYRot);
		float ySin = Trig.sin(realYRot);
		float xCos = Trig.cos(realXRot);
		float xSin = Trig.sin(realXRot);
		return new Vec3d(ySin * xCos, -xSin, yCos * xCos);
	}

	/**
	 * Auswahl vom Augenpunkt in Blickrichtung (ohne Entities – der Client kennt keine). Strahllänge = max(Block-, Entity-
	 * Reichweite) wie {@code LocalPlayer#pick}; Treffer weiter als die Blockreichweite gelten als Fehlschlag.
	 */
	public static Hit pick(final BlockView view, final ShapeRegistry shapes, final double eyeX, final double eyeY, final double eyeZ,
		final float yRot, final float xRot, final double blockRange, final double entityRange) {
		if (!(blockRange >= 0 && blockRange <= 64) || !(entityRange >= 0 && entityRange <= 64)) {
			throw new IllegalArgumentException("interaction range 0..64");
		}
		double max = Math.max(blockRange, entityRange);
		Vec3d dir = viewVector(xRot, yRot);
		Vec3d from = new Vec3d(eyeX, eyeY, eyeZ);
		Vec3d to = new Vec3d(eyeX + dir.x() * max, eyeY + dir.y() * max, eyeZ + dir.z() * max);
		Hit hit = clip(view, shapes, from, to);
		// filterHitResult: Trefferpunkt muss näher als die Blockreichweite liegen
		double dx = hit.x() - eyeX;
		double dy = hit.y() - eyeY;
		double dz = hit.z() - eyeZ;
		if (!(dx * dx + dy * dy + dz * dz < blockRange * blockRange)) {
			return Hit.miss(hit.x(), hit.y(), hit.z(), nearest(dx, dy, dz));
		}
		return hit;
	}

	/** {@code BlockGetter#clip} mit Umrissformen, ohne Flüssigkeiten. */
	public static Hit clip(final BlockView view, final ShapeRegistry shapes, final Vec3d from, final Vec3d to) {
		if (from.equals(to)) {
			return missAt(from, to);
		}
		double toX = lerp(-1.0E-7, to.x(), from.x());
		double toY = lerp(-1.0E-7, to.y(), from.y());
		double toZ = lerp(-1.0E-7, to.z(), from.z());
		double fromX = lerp(-1.0E-7, from.x(), to.x());
		double fromY = lerp(-1.0E-7, from.y(), to.y());
		double fromZ = lerp(-1.0E-7, from.z(), to.z());
		int bx = floor(fromX);
		int by = floor(fromY);
		int bz = floor(fromZ);
		Hit first = test(view, shapes, from, to, bx, by, bz);
		if (first != null) {
			return first;
		}
		double dx = toX - fromX;
		double dy = toY - fromY;
		double dz = toZ - fromZ;
		int sx = sign(dx);
		int sy = sign(dy);
		int sz = sign(dz);
		double tDeltaX = sx == 0 ? Double.MAX_VALUE : sx / dx;
		double tDeltaY = sy == 0 ? Double.MAX_VALUE : sy / dy;
		double tDeltaZ = sz == 0 ? Double.MAX_VALUE : sz / dz;
		double tX = tDeltaX * (sx > 0 ? 1.0 - frac(fromX) : frac(fromX));
		double tY = tDeltaY * (sy > 0 ? 1.0 - frac(fromY) : frac(fromY));
		double tZ = tDeltaZ * (sz > 0 ? 1.0 - frac(fromZ) : frac(fromZ));
		int guard = 0;
		while ((tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) && guard++ < 1024) {
			if (tX < tY) {
				if (tX < tZ) {
					bx += sx;
					tX += tDeltaX;
				} else {
					bz += sz;
					tZ += tDeltaZ;
				}
			} else if (tY < tZ) {
				by += sy;
				tY += tDeltaY;
			} else {
				bz += sz;
				tZ += tDeltaZ;
			}
			Hit h = test(view, shapes, from, to, bx, by, bz);
			if (h != null) {
				return h;
			}
		}
		return missAt(from, to);
	}

	private static Hit missAt(final Vec3d from, final Vec3d to) {
		return Hit.miss(to.x(), to.y(), to.z(), nearest(from.x() - to.x(), from.y() - to.y(), from.z() - to.z()));
	}

	/** Ein Block: Umriss treffen, dann Seite ggf. durch die Interaktionsform ersetzen ({@code clipWithInteractionOverride}). */
	private static Hit test(final BlockView view, final ShapeRegistry shapes, final Vec3d from, final Vec3d to, final int x, final int y, final int z) {
		int state = view.stateAt(x, y, z);
		if (state <= 0) {
			return null; // Luft oder nicht geladen (Vanilla-Client: ungeladene Chunks liefern Luft)
		}
		Shape outline = shapes.outlineShape(state, x, z);
		Hit result = clipShape(outline, from, to, x, y, z);
		if (result != null) {
			Shape interaction = shapes.interactionShape(state);
			Hit override = clipShape(interaction, from, to, x, y, z);
			if (override != null && dist2(override, from) < dist2(result, from)) {
				return result.withFace(override.face());
			}
		}
		return result;
	}

	private static double dist2(final Hit h, final Vec3d p) {
		double dx = h.x() - p.x();
		double dy = h.y() - p.y();
		double dz = h.z() - p.z();
		return dx * dx + dy * dy + dz * dz;
	}

	/** {@code VoxelShape#clip}: Startpunkt im Block → „inside“-Treffer, sonst {@code AABB.clip} über alle Boxen. */
	static Hit clipShape(final Shape shape, final Vec3d from, final Vec3d to, final int x, final int y, final int z) {
		if (shape.isEmpty()) {
			return null;
		}
		double ddx = to.x() - from.x();
		double ddy = to.y() - from.y();
		double ddz = to.z() - from.z();
		if (ddx * ddx + ddy * ddy + ddz * ddz < 1.0E-7) {
			return null;
		}
		double px = from.x() + ddx * 0.001;
		double py = from.y() + ddy * 0.001;
		double pz = from.z() + ddz * 0.001;
		for (Aabb b : shape.boxes()) {
			if (b.contains(px - x, py - y, pz - z)) {
				return new Hit(false, px, py, pz, x, y, z, nearest(ddx, ddy, ddz).opposite(), true);
			}
		}
		double[] scale = {1.0};
		Face face = null;
		for (Aabb b : shape.boxes()) {
			face = direction(b.move(x, y, z), from, scale, face, ddx, ddy, ddz);
		}
		if (face == null) {
			return null;
		}
		double s = scale[0];
		return new Hit(false, from.x() + s * ddx, from.y() + s * ddy, from.z() + s * ddz, x, y, z, face, false);
	}

	/** {@code AABB#getDirection}: X-, dann Y-, dann Z-Ebenen, nächster Eintrittspunkt gewinnt. */
	private static Face direction(final Aabb b, final Vec3d from, final double[] scale, Face face, final double dx, final double dy, final double dz) {
		if (dx > 1.0E-7) {
			face = clipPoint(scale, face, dx, dy, dz, b.minX(), b.minY(), b.maxY(), b.minZ(), b.maxZ(), Face.WEST, from.x(), from.y(), from.z());
		} else if (dx < -1.0E-7) {
			face = clipPoint(scale, face, dx, dy, dz, b.maxX(), b.minY(), b.maxY(), b.minZ(), b.maxZ(), Face.EAST, from.x(), from.y(), from.z());
		}
		if (dy > 1.0E-7) {
			face = clipPoint(scale, face, dy, dz, dx, b.minY(), b.minZ(), b.maxZ(), b.minX(), b.maxX(), Face.DOWN, from.y(), from.z(), from.x());
		} else if (dy < -1.0E-7) {
			face = clipPoint(scale, face, dy, dz, dx, b.maxY(), b.minZ(), b.maxZ(), b.minX(), b.maxX(), Face.UP, from.y(), from.z(), from.x());
		}
		if (dz > 1.0E-7) {
			face = clipPoint(scale, face, dz, dx, dy, b.minZ(), b.minX(), b.maxX(), b.minY(), b.maxY(), Face.NORTH, from.z(), from.x(), from.y());
		} else if (dz < -1.0E-7) {
			face = clipPoint(scale, face, dz, dx, dy, b.maxZ(), b.minX(), b.maxX(), b.minY(), b.maxY(), Face.SOUTH, from.z(), from.x(), from.y());
		}
		return face;
	}

	private static Face clipPoint(final double[] scale, final Face face, final double da, final double db, final double dc, final double point,
		final double minB, final double maxB, final double minC, final double maxC, final Face newFace, final double fromA, final double fromB,
		final double fromC) {
		double s = (point - fromA) / da;
		double pb = fromB + s * db;
		double pc = fromC + s * dc;
		if (0.0 < s && s < scale[0] && minB - 1.0E-7 < pb && pb < maxB + 1.0E-7 && minC - 1.0E-7 < pc && pc < maxC + 1.0E-7) {
			scale[0] = s;
			return newFace;
		}
		return face;
	}

	/** {@code Direction#getApproximateNearest} (float-Skalarprodukt, Start NORTH, Schwelle Float.MIN_VALUE). */
	public static Face nearest(final double dx, final double dy, final double dz) {
		float fx = (float) dx;
		float fy = (float) dy;
		float fz = (float) dz;
		Face result = Face.NORTH;
		float best = Float.MIN_VALUE;
		for (Face f : Face.values()) {
			float dot = fx * f.dx + fy * f.dy + fz * f.dz;
			if (dot > best) {
				best = dot;
				result = f;
			}
		}
		return result;
	}

	private static double lerp(final double a, final double from, final double to) {
		return from + a * (to - from);
	}

	private static int floor(final double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}

	private static int sign(final double v) {
		return v == 0.0 ? 0 : v > 0.0 ? 1 : -1;
	}

	/** {@code Mth.frac}: v − floor(v) (als long). */
	private static double frac(final double v) {
		return v - (double) (long) Math.floor(v);
	}
}
