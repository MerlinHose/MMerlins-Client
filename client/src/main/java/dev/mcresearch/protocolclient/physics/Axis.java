package dev.mcresearch.protocolclient.physics;

/** Koordinatenachse. */
public enum Axis {
	X, Y, Z;

	/** Reihenfolge der Achsenauflösung ({@code Direction#axisStepOrder}): Y zuerst, dann die größere horizontale zuletzt. */
	public static Axis[] stepOrder(final Vec3d movement) {
		return Math.abs(movement.x()) < Math.abs(movement.z()) ? new Axis[]{Y, Z, X} : new Axis[]{Y, X, Z};
	}
}
