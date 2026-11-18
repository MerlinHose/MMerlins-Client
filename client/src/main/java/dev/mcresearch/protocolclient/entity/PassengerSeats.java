package dev.mcresearch.protocolclient.entity;

/**
 * M14 Phase F (docs/57 §2): Sitzposition eines Passagiers wie {@code Entity#positionRider}: Ankerpunkt des Fahrzeugs
 * ({@code getPassengerAttachmentPoint}, um die Fahrzeug-Gier gedreht) minus Fahrzeug-Ankerpunkt des Passagiers
 * ({@code getVehicleAttachmentPoint}). Typabhängig:
 * <ul>
 * <li>Standard: Typtabelle {@code X … PASSENGER} (Index begrenzt), bei Babys die eigenen Baby-Punkte, mit Attributskalierung;</li>
 * <li>Boote: Höhe = Hitboxhöhe/3 (Floß 0,889), Versatz längs 0 (Truhenboot 0,15), zu zweit 0,2 / −0,6, Tiere +0,2;</li>
 * <li>Loren: Dorfbewohner und Händler sitzen tiefer (0, 0, 0);</li>
 * <li>Pferde: zusätzlich (0, 0,15·steigen, −0,7·steigen) mit dem Steigewert des letzten Ticks.</li>
 * </ul>
 * Ohne LWJGL.
 */
public final class PassengerSeats {
	private PassengerSeats() {
	}

	/**
	 * Versatz Fahrzeugposition → Fußposition des Passagiers. {@code index}/{@code count}: Platz und Anzahl der Passagiere;
	 * {@code vehicleYRot}: Gier des Fahrzeugs in Grad; {@code standAnim}: Steigewert (nur Pferde).
	 */
	public static double[] offset(final EntityTypes.Type vehicle, final boolean vehicleBaby, final double vehicleScale, final int index, final int count,
		final EntityTypes.Type passenger, final boolean passengerBaby, final float vehicleYRot, final float standAnim) {
		String vt = vehicle.shortName();
		double[] p;
		boolean boat = vt.endsWith("_boat") || vt.endsWith("_raft");
		if (boat) {
			boolean raft = vt.endsWith("_raft");
			boolean chest = vt.contains("chest_");
			double height = vehicle.height() * (raft ? 0.8888889F : 1.0F / 3.0F);
			float off = chest ? 0.15F : 0.0F;
			if (count > 1) {
				off = index == 0 ? 0.2F : -0.6F;
				if ("creature".equals(passenger.category())) {
					off += 0.2F;
				}
			}
			p = yRot(new double[]{0, height, off}, vehicleYRot);
		} else if ((vt.equals("minecart") || vt.endsWith("_minecart")) && (passenger.shortName().equals("villager") || passenger.shortName().equals(
			"wandering_trader"))) {
			p = new double[]{0, 0, 0};
		} else {
			double[] a = EntityTypes.get().attachment(vt, "PASSENGER", index, vehicleBaby);
			if (a == null) {
				a = new double[]{0, vehicle.height(), 0};
			}
			double s = vehicleScale;
			p = yRot(new double[]{a[0] * s, a[1] * s, a[2] * s}, vehicleYRot);
			if (vt.equals("horse") || vt.equals("donkey") || vt.equals("mule") || vt.equals("skeleton_horse") || vt.equals("zombie_horse")) {
				double[] extra = yRot(new double[]{0, 0.15 * standAnim * s, -0.7 * standAnim * s}, vehicleYRot);
				p[0] += extra[0];
				p[1] += extra[1];
				p[2] += extra[2];
			}
		}
		double[] v = EntityTypes.get().attachment(passenger.shortName(), "VEHICLE", 0, passengerBaby);
		if (v != null) {
			p[0] -= v[0];
			p[1] -= v[1];
			p[2] -= v[2];
		}
		return p;
	}

	/** {@code Vec3#yRot(-yRotDeg·π/180)} wie {@code EntityAttachments#transformPoint}. */
	static double[] yRot(final double[] v, final float yRotDeg) {
		double a = -yRotDeg * (Math.PI / 180.0);
		double c = Math.cos(a);
		double s = Math.sin(a);
		return new double[]{v[0] * c + v[2] * s, v[1], v[2] * c - v[0] * s};
	}
}
