package dev.mcresearch.protocolclient.physics;

import java.util.ArrayList;
import java.util.List;

/**
 * Sammelt die Blockformen, die eine Abfragebox schneiden ({@code BlockCollisions}, docs/21 §5.5): Zellen
 * {@code floor(min−1e−7)−1 … floor(max+1e−7)+1}; Randzellen (eine Koordinate auf dem Rand) nur für große Formen,
 * Kanten/Ecken (zwei/drei) nie. Aufgenommen wird eine Form, wenn sie die Abfragebox mit positivem Volumen schneidet.
 *
 * <p><b>Ungeladene Chunks:</b> Vanilla liefert dort keine Formen (der Spieler könnte hineinfallen). Dieser Client
 * behandelt jede Zelle eines ungeladenen Chunks als vollen Block und meldet das ({@link Result#touchedUnloaded()}),
 * damit Bewegungen dort anhalten statt eine vom Server abweichende Position zu erzeugen (docs/22 §5).
 */
public final class CollisionWorld {
	/** Eine gefundene Form mit ihrer Blockposition (Form in Weltkoordinaten). */
	public record Hit(int x, int y, int z, Shape shape, boolean unloaded) {
	}

	public record Result(List<Shape> shapes, boolean touchedUnloaded) {
	}

	private final BlockView view;
	private final ShapeRegistry registry;

	public CollisionWorld(final BlockView view, final ShapeRegistry registry) {
		this.view = view;
		this.registry = registry;
	}

	public ShapeRegistry registry() {
		return this.registry;
	}

	public BlockView view() {
		return this.view;
	}

	public Result collisions(final Aabb box, final CollisionContext ctx) {
		List<Hit> hits = hits(box, ctx);
		List<Shape> shapes = new ArrayList<>(hits.size());
		boolean unloaded = false;
		for (Hit h : hits) {
			shapes.add(h.shape());
			unloaded |= h.unloaded();
		}
		return new Result(shapes, unloaded);
	}

	public List<Hit> hits(final Aabb box, final CollisionContext ctx) {
		int x0 = floor(box.minX() - Aabb.EPSILON) - 1;
		int x1 = floor(box.maxX() + Aabb.EPSILON) + 1;
		int y0 = floor(box.minY() - Aabb.EPSILON) - 1;
		int y1 = floor(box.maxY() + Aabb.EPSILON) + 1;
		int z0 = floor(box.minZ() - Aabb.EPSILON) - 1;
		int z1 = floor(box.maxZ() + Aabb.EPSILON) + 1;
		long cells = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
		if (cells > 1 << 16) {
			throw new IllegalArgumentException("collision query too large: " + box);
		}
		List<Hit> out = new ArrayList<>();
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					int border = (x == x0 || x == x1 ? 1 : 0) + (y == y0 || y == y1 ? 1 : 0) + (z == z0 || z == z1 ? 1 : 0);
					if (border >= 2) {
						continue; // Kanten nur für moving_piston (Form im Client leer), Ecken nie
					}
					int state = this.view.stateAt(x, y, z);
					if (state == BlockView.UNLOADED) {
						if (border == 0) {
							Shape solid = Shape.BLOCK.move(x, y, z);
							if (solid.intersects(box)) {
								out.add(new Hit(x, y, z, solid, true));
							}
						}
						continue;
					}
					if (border == 1 && !this.registry.isLarge(state)) {
						continue;
					}
					Shape local = this.registry.collisionShape(state, x, y, z, ctx);
					if (local.isEmpty()) {
						continue;
					}
					Shape placed = local.move(x, y, z);
					if (placed.intersects(box)) {
						out.add(new Hit(x, y, z, placed, false));
					}
				}
			}
		}
		return out;
	}

	private static int floor(final double v) {
		return (int) Math.floor(v);
	}
}
