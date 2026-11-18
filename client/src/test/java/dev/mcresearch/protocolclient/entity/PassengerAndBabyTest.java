package dev.mcresearch.protocolclient.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M14 Phase D/F (docs/57): Baby-Maße, Passagierliste, Sitzpositionen, eigenes Fahrzeug, Zyklen, Entfernen. */
class PassengerAndBabyTest {
	private static final int PIG = 101, COW = 30, SHEEP = 114, ZOMBIE = 154, CHICKEN = 26, WOLF = 152, HORSE = 67, VILLAGER = 143, OAK_BOAT = 90,
		OAK_CHEST_BOAT = 91, MINECART = 86, PLAYER = 159, BEE = 11, DONKEY = 37;

	private static void spawn(final EntityManager m, final int id, final int type, final double x, final double y, final double z, final float yRot) {
		m.handle(new Clientbound.AddEntity(id, new UUID(3, id), type, x, y, z, 0, 0, 0, yRot, 0, yRot, 0));
	}

	private static void baby(final EntityManager m, final int id) {
		m.handle(new Clientbound.SetEntityData(id, List.of(new Clientbound.DataValue(16, 8, true)), null));
	}

	@Test
	void babyDimensionsComeFromTheTableAndKeepTheM9Values() {
		EntityManager m = new EntityManager();
		int[][] cases = {{PIG}, {COW}, {SHEEP}, {ZOMBIE}, {CHICKEN}, {WOLF}, {HORSE}, {VILLAGER}, {BEE}, {DONKEY}};
		float[][] expected = {{0.45F, 0.45F}, {0.45F, 0.7F}, {0.45F, 0.65F}, {0.49F, 0.98F}, {0.3F, 0.4F}, {0.3F, 0.425F}, {0.97753906F, 1.12F},
			{0.49F, 0.98F}, {0.275F, 0.25F}, {1.3964844F * 0.5F, 1.5F * 0.5F}};
		for (int i = 0; i < cases.length; i++) {
			spawn(m, i + 1, cases[i][0], 0, 64, 0, 0);
			baby(m, i + 1);
			EntityManager.View v = m.view(i + 1, 1, 0);
			assertEquals(expected[i][0], v.width(), 1e-6, v.type().name());
			assertEquals(expected[i][1], v.height(), 1e-6, v.type().name());
		}
		// Erwachsene: Typmaße unverändert
		spawn(m, 50, CHICKEN, 0, 64, 0, 0);
		assertEquals(0.4F, m.view(50, 1, 0).width(), 1e-6);
	}

	@Test
	void passengerRendersOnVehicleSeatNotAtOldPosition() {
		EntityManager m = new EntityManager();
		spawn(m, 1, OAK_BOAT, 10, 63, 20, 0);
		spawn(m, 2, ZOMBIE, 50, 70, 50, 0); // alte Weltposition weit weg
		m.handle(new Clientbound.SetPassengers(1, new int[]{2}));
		EntityManager.View z = m.view(2, 1, 0);
		// Boot: Sitz auf Hoehe/3 = 0,1875 minus Zombie-Ankerpunkt VEHICLE 0,7 (Tabelle X)
		assertEquals(10.0, z.rx(), 1e-9);
		assertEquals(63.0 + 0.5625 / 3.0 - 0.699999988079071, z.ry(), 1e-6);
		assertEquals(20.0, z.rz(), 1e-9);
		assertEquals(50.0, z.x(), 1e-9); // bestätigter Zustand bleibt getrennt
		assertEquals(1, z.vehicle());
		// zu zweit: vorne 0,2, hinten −0,6 (Gier 0 → +z)
		spawn(m, 3, ZOMBIE, 0, 0, 0, 0);
		m.handle(new Clientbound.SetPassengers(1, new int[]{2, 3}));
		assertEquals(20.2, m.view(2, 1, 0).rz(), 1e-6);
		assertEquals(19.4, m.view(3, 1, 0).rz(), 1e-6);
		// Absteigen: alte Liste aufgehoben
		m.handle(new Clientbound.SetPassengers(1, new int[]{3}));
		assertEquals(-1, m.view(2, 1, 0).vehicle());
		assertEquals(1, m.view(3, 1, 0).vehicle());
	}

	@Test
	void seatsRotateWithVehicleYaw() {
		double[] off = PassengerSeats.offset(EntityTypes.get().byName("oak_chest_boat"), false, 1, 0, 1, EntityTypes.get().byName("zombie"), false, 90,
			0);
		// Truhenboot: Versatz 0,15 längs; Gier 90° → in −x-Richtung
		assertEquals(-0.15, off[0], 1e-6);
		assertEquals(0.0, off[2], 1e-6);
		double[] horse = PassengerSeats.offset(EntityTypes.get().byName("horse"), false, 1, 0, 1, EntityTypes.get().byName("player"), false, 0, 0);
		assertEquals(1.44375 - 0.6, horse[1], 1e-6); // Pferd PASSENGER 1,44375 − Spieler VEHICLE 0,6
		double[] cart = PassengerSeats.offset(EntityTypes.get().byName("minecart"), false, 1, 0, 1, EntityTypes.get().byName("villager"), false, 0, 0);
		assertArrayEquals(new double[]{0, 0, 0}, cart, 1e-9);
	}

	@Test
	void ownPlayerMountsDismountsAndVehicleRemoval() {
		EntityManager m = new EntityManager();
		m.setOwnId(500);
		spawn(m, 1, MINECART, 5, 64, 5, 0);
		m.handle(new Clientbound.SetPassengers(1, new int[]{500}));
		// Server sendet beim Einsteigen dieselbe Liste doppelt (ServerPlayer#startRiding + Tracker): kein Ab-/Aufsteigen (Lauf m14-gfx2)
		m.handle(new Clientbound.SetPassengers(1, new int[]{500}));
		assertEquals(1, m.stats().ownMounts);
		assertEquals(0, m.stats().ownDismounts);
		assertEquals(1, m.ownVehicle());
		assertEquals(0, m.ownSeatIndex());
		double[] seat = m.ownSeat(1);
		assertNotNull(seat);
		assertEquals(64 + 0.1875 - 0.6, seat[1], 1e-6); // Lore PASSENGER 0,1875 − Spieler VEHICLE 0,6
		m.handle(new Clientbound.SetPassengers(1, new int[0]));
		assertEquals(-1, m.ownVehicle());
		m.handle(new Clientbound.SetPassengers(1, new int[]{500}));
		m.handle(new Clientbound.RemoveEntities(new int[]{1}));
		assertEquals(-1, m.ownVehicle()); // Fahrzeug entfernt
		assertNull(m.ownSeat(1));
		assertEquals(2, m.stats().ownMounts);
		assertEquals(2, m.stats().ownDismounts);
	}

	@Test
	void cyclesAreRejectedAndLateSpawnsLink() {
		EntityManager m = new EntityManager();
		spawn(m, 1, HORSE, 0, 64, 0, 0);
		spawn(m, 2, PIG, 0, 64, 0, 0);
		m.handle(new Clientbound.SetPassengers(1, new int[]{2}));
		m.handle(new Clientbound.SetPassengers(2, new int[]{1})); // Pferd auf eigenem Passagier: Zyklus
		assertEquals(-1, m.view(1, 1, 0).vehicle());
		assertEquals(1, m.stats().passengerCycles);
		m.handle(new Clientbound.SetPassengers(1, new int[]{1}));
		assertEquals(2, m.stats().passengerCycles);
		// Passagier spawnt erst nach set_passengers
		spawn(m, 3, OAK_BOAT, 0, 63, 0, 0);
		m.handle(new Clientbound.SetPassengers(3, new int[]{4}));
		spawn(m, 4, ZOMBIE, 9, 9, 9, 0);
		assertEquals(3, m.view(4, 1, 0).vehicle());
		assertEquals(1, m.stats().lateLinks);
		// Wechsel: Zombie springt auf das Pferd → altes Boot führt ihn nicht mehr
		m.handle(new Clientbound.SetPassengers(1, new int[]{2, 4}));
		assertEquals(1, m.view(4, 1, 0).vehicle());
		assertArrayEquals(new int[0], m.view(3, 1, 0).passengers());
	}

	@Test
	void nestedPassengersFollowTheRootVehicle() {
		EntityManager m = new EntityManager();
		spawn(m, 1, OAK_BOAT, 0, 63, 0, 0);
		spawn(m, 2, PIG, 0, 0, 0, 0);
		spawn(m, 3, CHICKEN, 0, 0, 0, 0);
		m.handle(new Clientbound.SetPassengers(1, new int[]{2}));
		m.handle(new Clientbound.SetPassengers(2, new int[]{3}));
		double pigY = m.view(2, 1, 0).ry();
		double chickenY = m.view(3, 1, 0).ry();
		assertEquals(pigY + 0.9 * 0.86 + 0.0, chickenY, 0.2); // Huhn sitzt auf dem Schwein (Typpunkt PASSENGER)
		m.handle(new Clientbound.RemoveEntities(new int[]{1}));
		assertEquals(-1, m.view(2, 1, 0).vehicle());
		assertEquals(2, m.view(3, 1, 0).vehicle());
	}

	/**
	 * Vergleich vehicle3 (walk_into_boat): Boote stoßen über ihre um 0,2 horizontal vergrößerte, um 0,01 vertikal verkleinerte Box
	 * ({@code AbstractBoat#tick}: inflate(0.2F, -0.01F, 0.2F)); Vanilla bremste den Spieler 0,2 Blöcke vor dem Boot um 0,05.
	 */
	@Test
	void boatPushesWithinItsInflatedBoxButNotAPlayerStandingOnIt() {
		EntityManager m = new EntityManager();
		spawn(m, 1, OAK_BOAT, 0.0, 64.0, 0.0, 0); // Box z −0,6875…0,6875, y 64…64,5625
		// Spieler (0,6 × 1,8) mit maxZ −0,8: 0,1125 vor dem Boot → nur in der vergrößerten Box
		dev.mcresearch.protocolclient.physics.Aabb near = new dev.mcresearch.protocolclient.physics.Aabb(-0.3, 64.0, -1.4, 0.3, 65.8, -0.8);
		double[] push = m.pushImpulse(near, 0.0, -1.1);
		assertEquals(0.0, push[0], 1e-12);
		assertEquals(-0.05, push[1], 1e-6); // dd = √1,1, Richtung vom Boot weg, pow = 1/dd → −0,05
		// auf dem Boot stehend (minY = Oberkante): vertikal um 0,01 verkleinert → kein Stoß
		dev.mcresearch.protocolclient.physics.Aabb onTop = new dev.mcresearch.protocolclient.physics.Aabb(-0.3, 64.5625, -0.9, 0.3, 66.3625, -0.3);
		assertArrayEquals(new double[]{0, 0}, m.pushImpulse(onTop, 0.0, -0.6), 1e-12);
		// weiter als 0,2 entfernt: kein Stoß
		dev.mcresearch.protocolclient.physics.Aabb far = new dev.mcresearch.protocolclient.physics.Aabb(-0.3, 64.0, -1.6, 0.3, 65.8, -0.95);
		assertArrayEquals(new double[]{0, 0}, m.pushImpulse(far, 0.0, -1.25), 1e-12);
	}

	private static EntityManager ridingWithObstacleMoving() {
		EntityManager m = new EntityManager();
		m.setOwnId(500);
		spawn(m, 5, OAK_BOAT, 0.0, 64.0, 10.0, 90); // Hindernis, vor dem eigenen Boot eingefügt
		spawn(m, 10, OAK_BOAT, 0.0, 64.0, 0.0, 0);
		m.handle(new Clientbound.SetPassengers(10, new int[]{500}));
		m.handle(new Clientbound.MoveEntity(5, true, false, false, List.of(new Clientbound.DeltaStep(0, (short) 0, (short) 0, (short) 4096)), 0, 0, false)); // Server schiebt das Hindernis 1 Block weg
		return m;
	}

	/**
	 * Vergleich vehicle4 (boat_into_boat): Vanilla tickt Entities in Einfügereihenfolge. Das vor dem eigenen Boot eingefügte Hindernis
	 * ist schon interpoliert, wenn das eigene Boot kollidiert; vorher sah unser Client die Hindernisposition einen Tick zu spät.
	 */
	@Test
	void entitiesInsertedBeforeTheOwnVehicleAreInterpolatedBeforeItMoves() {
		EntityManager early = ridingWithObstacleMoving();
		EntityManager late = ridingWithObstacleMoving();
		double before = early.collisionBoxes().getFirst().minZ();
		early.tickBeforeOwnVehicle();
		double stepped = early.collisionBoxes().getFirst().minZ();
		assertTrue(stepped > before, "obstacle interpolated before the own vehicle moves: " + before + " -> " + stepped + " ownVehicle=" + early.ownVehicle());
		late.tick();
		assertEquals(late.collisionBoxes().getFirst().minZ(), stepped, 1e-12, "same single interpolation step as a normal tick");
		early.tick(); // darf das Hindernis nicht ein zweites Mal weiterschalten
		assertEquals(stepped, early.collisionBoxes().getFirst().minZ(), 1e-12);
		// ohne eigenes Fahrzeug: nichts vorgezogen
		EntityManager walking = new EntityManager();
		spawn(walking, 5, OAK_BOAT, 0.0, 64.0, 10.0, 90);
		walking.handle(new Clientbound.MoveEntity(5, true, false, false, List.of(new Clientbound.DeltaStep(0, (short) 0, (short) 0, (short) 4096)), 0, 0, false));
		walking.tickBeforeOwnVehicle();
		assertEquals(before, walking.collisionBoxes().getFirst().minZ(), 1e-12);
	}

	/** Vergleich vehicle4 (minecart_push): die vom Server bewegte eigene Lore tickt vor ihrem Passagier; Sitz und Spur sind nicht einen Tick alt. */
	@Test
	void serverDrivenOwnVehicleIsInterpolatedBeforeItsRider() {
		EntityManager m = new EntityManager();
		m.setOwnId(500);
		spawn(m, 10, MINECART, 0.5, 64.0, 0.5, 0);
		m.handle(new Clientbound.SetPassengers(10, new int[]{500}));
		m.handle(new Clientbound.MoveEntity(10, true, false, false, List.of(new Clientbound.DeltaStep(0, (short) 0, (short) 0, (short) 4096)), 0, 0, false));
		double before = m.ownSeatTick()[2];
		m.tickBeforeOwnVehicle();
		double seat = m.ownSeatTick()[2];
		assertTrue(seat > before, "seat follows the interpolated cart in the same tick");
		m.tick(); // kein zweiter Schritt
		assertEquals(seat, m.ownSeatTick()[2], 1e-12);
	}

	/**
	 * Diagnose diag1 (boat_ice/boat_submerged): Vanilla schreibt die Delta-Basis ({@code VecDeltaCodec}) auch für das selbst gesteuerte
	 * Fahrzeug fort und setzt sie nur aus Paketen. Unser Client verwarf die Deltas während der Fahrt und setzte die Basis auf die lokale
	 * Physikposition – nach dem Absteigen erschien das Boot dadurch an falscher Stelle und stieß den Spieler weg.
	 */
	@Test
	void deltaBaseOfTheOwnVehicleFollowsTheServerNotTheLocalPhysics() {
		EntityManager m = new EntityManager();
		m.setOwnId(500);
		spawn(m, 10, OAK_BOAT, 0.5, 64.0, 0.5, 0);
		m.handle(new Clientbound.SetPassengers(10, new int[]{500}));
		m.setLocalVehicle(10, 0.5, 64.0, 3.0, 0, 0); // eigene Physik ist voraus
		m.handle(new Clientbound.MoveEntity(10, true, false, false, List.of(new Clientbound.DeltaStep(0, (short) 0, (short) 0, (short) 4096)), 0, 0, true));
		assertEquals(3.0, m.view(10, 1, 0).z(), 1e-12, "own vehicle keeps the local position while riding");
		m.handle(new Clientbound.SetPassengers(10, new int[0])); // abgestiegen
		m.handle(new Clientbound.MoveEntity(10, true, false, false, List.of(new Clientbound.DeltaStep(0, (short) 0, (short) 0, (short) 2048)), 0, 0, true));
		assertEquals(2.0, m.view(10, 1, 0).z(), 1e-12, "server base 0.5 + 1.0 + 0.5, not local 3.0 + 0.5");
	}
}
