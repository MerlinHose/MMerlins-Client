package dev.mcresearch.protocolclient.physics;

/**
 * Fluidzustände der Welt für die Spielerphysik (M11, docs/43 §1): Höhe, Kamerahöhe und Strömungsrichtung je Block, gelesen aus
 * dem bestehenden Weltmodell ({@link BlockView}) und den Fluidfakten der Blockzustände ({@link BlockMovementFacts}). Keine eigene
 * Weltverwaltung: jede Abfrage liest den aktuellen Blockzustand.
 */
public final class FluidModel {
	/** Horizontale Richtungen in der Reihenfolge von {@code Direction.Plane.HORIZONTAL}: Norden, Osten, Süden, Westen. */
	private static final int[][] HORIZONTAL = {{0, -1, 2}, {1, 0, 5}, {0, 1, 3}, {-1, 0, 4}};

	private final BlockView view;
	private final BlockMovementFacts facts;

	public FluidModel(final BlockView view, final BlockMovementFacts facts) {
		this.view = view;
		this.facts = facts;
	}

	public BlockMovementFacts facts() {
		return this.facts;
	}

	public int stateAt(final int x, final int y, final int z) {
		return this.view.stateAt(x, y, z);
	}

	/** Fluid an der Position; ungeladene Chunks wie leer. */
	public BlockMovementFacts.Fluid fluid(final int x, final int y, final int z) {
		int s = this.view.stateAt(x, y, z);
		return s == BlockView.UNLOADED ? BlockMovementFacts.Fluid.EMPTY : this.facts.fluid(s);
	}

	/** {@code FlowingFluid#getHeight}: 1F, wenn darüber dasselbe Fluid liegt, sonst eigene Höhe (Menge / 9F). */
	public float height(final int x, final int y, final int z) {
		BlockMovementFacts.Fluid f = fluid(x, y, z);
		if (f.isEmpty()) {
			return 0.0F;
		}
		return fluid(x, y + 1, z).type() == f.type() ? 1.0F : f.ownHeight();
	}

	/** {@code FluidState#getHeightForCamera}: Quelle unter einem Block mit tragfähiger Unterseite → 1F, sonst {@link #height}. */
	public float heightForCamera(final int x, final int y, final int z) {
		BlockMovementFacts.Fluid f = fluid(x, y, z);
		if (f.source()) {
			int above = this.view.stateAt(x, y + 1, z);
			if (above != BlockView.UNLOADED && this.facts.sturdy(above, 0)) {
				return 1.0F;
			}
		}
		return height(x, y, z);
	}

	/** {@code FlowingFluid#affectsFlow}: leer oder dieselbe Fluidart. */
	private static boolean affectsFlow(final BlockMovementFacts.Fluid neighbour, final int type) {
		return neighbour.isEmpty() || neighbour.type() == type;
	}

	/** {@code FlowingFluid#isSolidFace} für horizontale Richtungen: gleiche Fluidart nein, Eisblock nein, sonst {@code isFaceSturdy}. */
	private boolean solidFace(final int x, final int y, final int z, final int direction3d, final int type) {
		int s = this.view.stateAt(x, y, z);
		if (s == BlockView.UNLOADED) {
			return false;
		}
		if (this.facts.fluid(s).type() == type) {
			return false;
		}
		return !this.facts.ice(s) && this.facts.sturdy(s, direction3d);
	}

	/**
	 * {@code FlowingFluid#getFlow}: Summe der Höhenunterschiede zu den vier Nachbarn ({@code float}-Abstand · Richtung, in
	 * {@code double} summiert), bei fallendem Fluid an einer festen Seite zusätzlich −6 nach unten; Ergebnis normiert
	 * ({@code Vec3#normalize}: Länge &lt; 1e−5F → 0).
	 */
	public Vec3d flow(final int x, final int y, final int z) {
		BlockMovementFacts.Fluid self = fluid(x, y, z);
		if (self.isEmpty()) {
			return Vec3d.ZERO;
		}
		double flowX = 0.0;
		double flowZ = 0.0;
		for (int[] d : HORIZONTAL) {
			int nx = x + d[0];
			int nz = z + d[1];
			BlockMovementFacts.Fluid neighbour = fluid(nx, y, nz);
			if (affectsFlow(neighbour, self.type())) {
				float neighborHeight = neighbour.ownHeight();
				float distance = 0.0F;
				if (neighborHeight == 0.0F) {
					int ns = this.view.stateAt(nx, y, nz);
					if (ns != BlockView.UNLOADED && !this.facts.blocksFluidFlow(ns)) {
						BlockMovementFacts.Fluid below = fluid(nx, y - 1, nz);
						if (affectsFlow(below, self.type())) {
							neighborHeight = below.ownHeight();
							if (neighborHeight > 0.0F) {
								distance = self.ownHeight() - (neighborHeight - 0.8888889F);
							}
						}
					}
				} else if (neighborHeight > 0.0F) {
					distance = self.ownHeight() - neighborHeight;
				}
				if (distance != 0.0F) {
					flowX += d[0] * distance;
					flowZ += d[1] * distance;
				}
			}
		}
		Vec3d flow = new Vec3d(flowX, 0.0, flowZ);
		if (self.falling()) {
			for (int[] d : HORIZONTAL) {
				int nx = x + d[0];
				int nz = z + d[1];
				if (solidFace(nx, y, nz, d[2], self.type()) || solidFace(nx, y + 1, nz, d[2], self.type())) {
					flow = normalize(flow).add(0.0, -6.0, 0.0);
					break;
				}
			}
		}
		return normalize(flow);
	}

	static Vec3d normalize(final Vec3d v) {
		return v.normalize();
	}
}
