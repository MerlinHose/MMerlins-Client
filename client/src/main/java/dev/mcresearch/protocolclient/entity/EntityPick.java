package dev.mcresearch.protocolclient.entity;

import dev.mcresearch.protocolclient.physics.Aabb;
import dev.mcresearch.protocolclient.physics.BlockRaycast;
import dev.mcresearch.protocolclient.physics.BlockView;
import dev.mcresearch.protocolclient.physics.ShapeRegistry;
import dev.mcresearch.protocolclient.physics.Vec3d;
import java.util.List;

/**
 * Auswahl zwischen Block und Entity (M9 Phase F, Regeln nach docs/34 §12, eigenständig umgesetzt):
 * <ol>
 *   <li>Strahl vom Auge, Länge max(Block-, Entity-Reichweite); ein Blocktreffer kürzt ihn.</li>
 *   <li>Für jede auswählbare Entity: Schnitt des Strahls mit ihrer Hitbox (dargestellte Position); liegt das Auge in der Box,
 *       gilt Abstand 0. Die nächste Entity gewinnt.</li>
 *   <li>Die Entity gewinnt, wenn sie näher als der Block ist – aber nur innerhalb der Entity-Reichweite; sonst gilt der Block
 *       innerhalb der Blockreichweite, sonst kein Treffer.</li>
 * </ol>
 */
public final class EntityPick {
	public enum Kind { ENTITY, BLOCK, MISS }

	/**
	 * Ergebnis; {@code entity} bei ENTITY, {@code block} bei BLOCK. Bei MISS ist {@code entity} gesetzt, wenn eine Entity vor dem Block lag, aber
	 * außerhalb der Entity-Reichweite (Vanilla: Fehlschlag am Trefferpunkt der Entity, {@code filterHitResult}).
	 */
	public record Target(Kind kind, EntityManager.View entity, BlockRaycast.Hit block, double x, double y, double z, double distance) {
	}

	private EntityPick() {
	}

	public static Target pick(final BlockView view, final ShapeRegistry shapes, final double eyeX, final double eyeY, final double eyeZ, final float yRot,
		final float xRot, final double blockRange, final double entityRange, final List<EntityManager.View> entities, final int ownVehicle) {
		double max = Math.max(blockRange, entityRange);
		Vec3d dir = BlockRaycast.viewVector(xRot, yRot);
		Vec3d from = new Vec3d(eyeX, eyeY, eyeZ);
		Vec3d to = new Vec3d(eyeX + dir.x() * max, eyeY + dir.y() * max, eyeZ + dir.z() * max);
		BlockRaycast.Hit block = view == null ? BlockRaycast.Hit.miss(to.x(), to.y(), to.z(), null) : BlockRaycast.clip(view, shapes, from, to);
		double blockDistSq = sq(block.x() - eyeX) + sq(block.y() - eyeY) + sq(block.z() - eyeZ);
		double maxSq = block.miss() ? max * max : blockDistSq;
		double reach = block.miss() ? max : Math.sqrt(maxSq);
		double ex = eyeX + dir.x() * reach;
		double ey = eyeY + dir.y() * reach;
		double ez = eyeZ + dir.z() * reach;
		EntityManager.View best = null;
		double bestSq = maxSq;
		double hx = 0, hy = 0, hz = 0;
		for (EntityManager.View v : entities) {
			if (!v.pickable()) {
				continue;
			}
			double hw = v.width() / 2.0;
			Aabb box = new Aabb(v.rx() - hw, v.ry(), v.rz() - hw, v.rx() + hw, v.ry() + v.height(), v.rz() + hw);
			if (box.contains(eyeX, eyeY, eyeZ)) {
				if (bestSq >= 0) {
					best = v;
					bestSq = 0;
					hx = eyeX;
					hy = eyeY;
					hz = eyeZ;
				}
				continue;
			}
			double t = clip(box, eyeX, eyeY, eyeZ, ex, ey, ez);
			if (t < 0) {
				continue;
			}
			double px = eyeX + (ex - eyeX) * t;
			double py = eyeY + (ey - eyeY) * t;
			double pz = eyeZ + (ez - eyeZ) * t;
			double d = sq(px - eyeX) + sq(py - eyeY) + sq(pz - eyeZ);
			if (d < bestSq || bestSq == 0) {
				if (ownVehicle >= 0 && v.id() == ownVehicle) {
					// eigenes Fahrzeug nur, wenn nichts anderes näher ist (ProjectileUtil: gleiche Wurzel-Fahrzeug)
					if (bestSq == 0) {
						best = v;
						hx = px;
						hy = py;
						hz = pz;
					}
				} else {
					best = v;
					bestSq = d;
					hx = px;
					hy = py;
					hz = pz;
				}
			}
		}
		if (best != null && bestSq < blockDistSq) {
			double d2 = sq(hx - eyeX) + sq(hy - eyeY) + sq(hz - eyeZ);
			double dist = Math.sqrt(d2);
			if (d2 < entityRange * entityRange) {
				return new Target(Kind.ENTITY, best, null, hx, hy, hz, dist);
			}
			return new Target(Kind.MISS, best, null, hx, hy, hz, dist);
		}
		double bd = Math.sqrt(blockDistSq);
		if (!block.miss() && blockDistSq < blockRange * blockRange) {
			return new Target(Kind.BLOCK, null, block, block.x(), block.y(), block.z(), bd);
		}
		return new Target(Kind.MISS, null, null, block.x(), block.y(), block.z(), bd);
	}

	/** Strecke from→to gegen Box (Slab-Verfahren): Eintrittsparameter 0–1 oder −1. */
	static double clip(final Aabb b, final double x0, final double y0, final double z0, final double x1, final double y1, final double z1) {
		double tMin = 0;
		double tMax = 1;
		double[] o = {x0, y0, z0};
		double[] d = {x1 - x0, y1 - y0, z1 - z0};
		double[] lo = {b.minX(), b.minY(), b.minZ()};
		double[] hi = {b.maxX(), b.maxY(), b.maxZ()};
		for (int a = 0; a < 3; a++) {
			if (Math.abs(d[a]) < 1.0e-12) {
				if (o[a] < lo[a] || o[a] > hi[a]) {
					return -1;
				}
				continue;
			}
			double t1 = (lo[a] - o[a]) / d[a];
			double t2 = (hi[a] - o[a]) / d[a];
			if (t1 > t2) {
				double s = t1;
				t1 = t2;
				t2 = s;
			}
			tMin = Math.max(tMin, t1);
			tMax = Math.min(tMax, t2);
			if (tMin > tMax) {
				return -1;
			}
		}
		return tMin;
	}

	private static double sq(final double v) {
		return v * v;
	}
}
