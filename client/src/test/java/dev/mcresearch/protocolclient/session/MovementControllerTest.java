package dev.mcresearch.protocolclient.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.physics.BlockView;
import dev.mcresearch.protocolclient.physics.PlayerPhysics;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import dev.mcresearch.protocolclient.protocol.packet.Serverbound;
import dev.mcresearch.protocolclient.protocol.packet.ServerboundPacket;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Bewegungssteuerung auf echter Blockgeometrie (M5). Ersetzt die Heightmap-Tests aus M2; die geprüften Absichten
 * (Gehgeschwindigkeit, ein Positionspaket je Tick, Stufen, Wände, Fallen, Sprung, Serverkorrektur, unbekanntes Gelände)
 * bleiben erhalten.
 */
class MovementControllerTest {
	private static final int STONE = 1;
	private static final int SLAB = BlockRegistry.get().idOf("minecraft:oak_slab", Map.of("type", "bottom", "waterlogged", "false"));
	private final PlayerState player = new PlayerState();
	private final MovementController m = new MovementController(this.player);
	private final Map<Long, Integer> blocks = new HashMap<>();
	private int loadedRadius = 64;

	private static long key(final int x, final int y, final int z) {
		return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFFL);
	}

	private void set(final int x, final int y, final int z, final int state) {
		this.blocks.put(key(x, y, z), state);
	}

	private void fill(final int x1, final int y1, final int z1, final int x2, final int y2, final int z2, final int state) {
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					set(x, y, z, state);
				}
			}
		}
	}

	private final BlockView view = (x, y, z) -> Math.abs(x) > this.loadedRadius || Math.abs(z) > this.loadedRadius ? BlockView.UNLOADED
		: this.blocks.getOrDefault(key(x, y, z), 0);

	/** Steinboden mit Oberkante 64; Spieler bei (0.5, 64, 0.5), ein paar Ticks zum Aufsetzen. */
	private void spawn() {
		fill(-10, 63, -10, 40, 63, 10, STONE);
		this.player.apply(new Clientbound.PlayerPosition(1, 0.5, 64, 0.5, 0, 0, 0, 0f, 0f, 0));
		this.m.setBlockView(this.view);
		this.m.setReady();
		for (int i = 0; i < 3; i++) {
			this.m.tick();
		}
	}

	private List<List<ServerboundPacket>> run(final CompletableFuture<?> until, final int maxTicks) {
		List<List<ServerboundPacket>> ticks = new ArrayList<>();
		for (int i = 0; i < maxTicks && !until.isDone(); i++) {
			ticks.add(this.m.tick());
		}
		return ticks;
	}

	private static long positionPackets(final List<ServerboundPacket> tick) {
		return tick.stream().filter(p -> p instanceof Serverbound.MovePlayerPos || p instanceof Serverbound.MovePlayerPosRot).count();
	}

	private static double x(final ServerboundPacket p) {
		return p instanceof Serverbound.MovePlayerPos pos ? pos.x() : ((Serverbound.MovePlayerPosRot) p).x();
	}

	@Test
	void walksOnFlatGroundAtWalkingSpeedWithOnePositionPacketPerTick() {
		spawn();
		CompletableFuture<MovementController.MoveResult> f = this.m.move(3, 0, 0);
		List<List<ServerboundPacket>> ticks = run(f, 200);
		MovementController.MoveResult r = f.join();
		assertTrue(r.reached(), r.toString());
		assertEquals(3.5, this.player.x(), MovementController.ARRIVE_RADIUS);
		assertEquals(64, this.player.y());
		double lastX = 0.5;
		for (List<ServerboundPacket> t : ticks) {
			assertTrue(positionPackets(t) <= 1, "at most one position packet per tick");
			for (ServerboundPacket p : t) {
				if (p instanceof Serverbound.MovePlayerPos || p instanceof Serverbound.MovePlayerPosRot) {
					assertTrue(x(p) - lastX <= 0.2159, "step " + (x(p) - lastX));
					lastX = x(p);
				}
			}
		}
	}

	@Test
	void playerInputReflectsKeysOfTheTick() {
		spawn();
		CompletableFuture<MovementController.MoveResult> f = this.m.move(2, 0, 0);
		List<ServerboundPacket> first = this.m.tick();
		Serverbound.PlayerInput in = assertInstanceOf(Serverbound.PlayerInput.class, first.getFirst());
		assertTrue(in.forward(), "move turns towards the target and walks forward");
		assertFalse(in.left() || in.right() || in.backward());
		assertEquals(-90f, this.player.yRot(), 1e-3, "yaw −90 looks towards +X");
		run(f, 200);
		List<ServerboundPacket> after = this.m.tick();
		assertFalse(after.stream().anyMatch(p -> p instanceof Serverbound.PlayerInput i && i.forward()));
	}

	@Test
	void packetChoiceMatchesVanillaSendPosition() {
		spawn();
		this.m.look(45f, 10f);
		List<ServerboundPacket> rotOnly = this.m.tick();
		assertInstanceOf(Serverbound.MovePlayerRot.class, rotOnly.getLast());
		this.m.walk(new PlayerPhysics.Input(true, false, false, false, false), 1);
		this.m.look(50f, 10f);
		assertInstanceOf(Serverbound.MovePlayerPosRot.class, this.m.tick().getLast());
		run(new CompletableFuture<>(), 30); // ausrollen
		int reminder = 0;
		for (int i = 0; i < 40; i++) {
			if (this.m.tick().stream().anyMatch(p -> p instanceof Serverbound.MovePlayerPos)) {
				reminder++;
			}
		}
		assertEquals(2, reminder, "position reminder every 20 ticks while standing still");
	}

	@Test
	void stepsUpSlabButIsBlockedByFullBlockWithoutJump() {
		spawn();
		set(2, 64, 0, SLAB); // 0,5 hoch → Step-up
		fill(4, 64, 0, 4, 64, 0, STONE); // 1,0 hoch → blockiert
		CompletableFuture<MovementController.MoveResult> f = this.m.move(5, 0, 0);
		run(f, 300);
		assertFalse(f.join().reached(), f.join().toString());
		assertTrue(f.join().outcome().startsWith("blocked"), f.join().toString());
		assertEquals(4 - MovementController.HALF_WIDTH, this.player.x(), 1e-6, "hitbox stops flush at the wall");
		// mit dy > 0 springt der Spieler auf den Block
		CompletableFuture<MovementController.MoveResult> climb = this.m.move(1, 1, 0);
		run(climb, 300);
		assertEquals(65, this.player.y(), 1e-9, climb.join().toString());
		assertTrue(this.player.x() > 4.0);
	}

	@Test
	void twoBlockWallIsNotClimbable() {
		spawn();
		fill(3, 64, -2, 3, 65, 2, STONE);
		CompletableFuture<MovementController.MoveResult> f = this.m.move(4, 1, 0);
		run(f, 400);
		assertTrue(f.join().outcome().startsWith("blocked"), f.join().toString());
		assertTrue(this.player.x() <= 3 - MovementController.HALF_WIDTH + 1e-9);
	}

	@Test
	void walkingDownASingleStepUsesGravityNotTeleport() {
		spawn();
		fill(2, 63, -10, 40, 63, 10, 0);
		fill(2, 62, -10, 40, 62, 10, STONE);
		CompletableFuture<MovementController.MoveResult> f = this.m.move(3, 0, 0);
		List<List<ServerboundPacket>> ticks = run(f, 200);
		assertEquals(63, this.player.y(), 1e-9);
		double maxDrop = 0;
		double lastY = 64;
		for (List<ServerboundPacket> t : ticks) {
			for (ServerboundPacket p : t) {
				double y = p instanceof Serverbound.MovePlayerPos pos ? pos.y() : p instanceof Serverbound.MovePlayerPosRot pr ? pr.y() : lastY;
				maxDrop = Math.max(maxDrop, lastY - y);
				lastY = y;
			}
		}
		assertTrue(maxDrop < 0.5, "drop per tick " + maxDrop);
	}

	@Test
	void fallsWithGravityOffAnEdgeAndLands() {
		spawn();
		fill(2, 63, -10, 40, 63, 10, 0);
		fill(2, 59, -10, 40, 59, 10, STONE);
		CompletableFuture<MovementController.MoveResult> f = this.m.move(3, 0, 0);
		run(f, 300);
		assertEquals(60, this.player.y(), 1e-9);
		assertTrue(this.player.onGround());
		List<Double> ys = new ArrayList<>();
		for (PlayerPhysics.TickResult r : this.m.trajectory()) {
			if (!r.onGround()) {
				ys.add(r.y());
			}
		}
		assertTrue(ys.size() >= 3, "fall ticks " + ys);
		assertTrue(ys.get(0) - ys.get(1) < ys.get(1) - ys.get(2), "falling accelerates " + ys);
	}

	@Test
	void jumpReachesVanillaApexAndLandsAfterTwelveTicks() {
		spawn();
		CompletableFuture<MovementController.MoveResult> f = this.m.jump();
		List<List<ServerboundPacket>> ticks = run(f, 100);
		assertTrue(f.join().reached());
		assertEquals(64, this.player.y(), 1e-9);
		double maxY = this.m.trajectory().stream().mapToDouble(PlayerPhysics.TickResult::y).max().orElse(0);
		assertEquals(65.2522, maxY, 1e-4);
		assertTrue(ticks.getFirst().stream().anyMatch(p -> p instanceof Serverbound.PlayerInput in && in.jump()));
		List<PlayerPhysics.TickResult> jumpTicks = this.m.trajectory().subList(3, this.m.trajectory().size()); // ohne Aufsetzticks
		long air = jumpTicks.stream().filter(r -> !r.onGround()).count();
		assertEquals(11, air, "11 ticks airborne, landing in the 12th");
	}

	@Test
	void ceilingCutsTheJump() {
		spawn();
		set(0, 66, 0, STONE); // Decke: Kopf bei 64 + 1,8 = 65,8 → nur 0,2 Luft
		CompletableFuture<MovementController.MoveResult> f = this.m.jump();
		run(f, 100);
		double maxY = this.m.trajectory().stream().mapToDouble(PlayerPhysics.TickResult::y).max().orElse(0);
		assertEquals(66.0 - (double) PlayerPhysics.HEIGHT, maxY, 1e-9, "head stops at the ceiling");
		assertTrue(this.m.trajectory().stream().anyMatch(r -> r.verticalCollision() && !r.onGround()));
	}

	@Test
	void serverCorrectionCancelsMoveAndIsReportedNextTick() {
		spawn();
		CompletableFuture<MovementController.MoveResult> f = this.m.move(10, 0, 0);
		run(new CompletableFuture<>(), 5);
		this.player.apply(new Clientbound.PlayerPosition(2, 0.5, 64, 0.5, 0, 0, 0, 0f, 0f, 0));
		this.m.onServerPosition(true);
		assertTrue(f.isDone());
		assertTrue(f.join().correctedByServer());
		assertEquals(1, this.m.corrections());
		List<ServerboundPacket> next = this.m.tick();
		// Teleport setzt auch die Blickrichtung (Yaw 0) zurück → Position mit Rotation
		Serverbound.MovePlayerPosRot report = assertInstanceOf(Serverbound.MovePlayerPosRot.class, next.getLast());
		assertEquals(0.5, report.x(), "new server position is reported back like Vanilla");
		assertEquals(0f, report.yRot());
	}

	@Test
	void claimSendsSinglePacketAndReportsServerDecision() {
		spawn();
		CompletableFuture<MovementController.MoveResult> accepted = this.m.claim(0.1, 0, 0);
		List<ServerboundPacket> first = this.m.tick();
		assertEquals(0.6, assertInstanceOf(Serverbound.MovePlayerPos.class, first.getLast()).x(), 1e-9);
		run(accepted, 20);
		assertTrue(accepted.join().reached());
		assertEquals(0.6, this.m.physics().x(), 1e-9, "physics continues from the accepted claim");
		CompletableFuture<MovementController.MoveResult> rejected = this.m.claim(20, 0, 0);
		this.m.tick();
		this.player.apply(new Clientbound.PlayerPosition(3, 0.6, 64, 0.5, 0, 0, 0, 0f, 0f, 0));
		this.m.onServerPosition(true);
		assertTrue(rejected.join().correctedByServer());
	}

	@Test
	void unloadedChunkStopsMovement() {
		this.loadedRadius = 8;
		spawn();
		CompletableFuture<MovementController.MoveResult> far = this.m.move(30, 0, 0);
		run(far, 1000);
		assertTrue(far.join().outcome().contains("not loaded") || far.join().outcome().startsWith("blocked"), far.join().toString());
		assertTrue(this.player.x() <= 9 - MovementController.HALF_WIDTH + 1e-9, "never walks into the unloaded area, x=" + this.player.x());
		assertEquals(64, this.player.y(), 1e-9);
	}

	@Test
	void hitboxEdgeAgainstWallBlocksBeforeCenterEnters() {
		// Regression aus M2 (docs/17 §3): die Hitbox berührt die Wand schon 0,3 vor dem Mittelpunkt
		spawn();
		fill(-1, 64, -3, -1, 65, 3, STONE);
		CompletableFuture<MovementController.MoveResult> f = this.m.move(-2, 0, 0);
		run(f, 200);
		assertTrue(f.join().outcome().startsWith("blocked"), f.join().toString());
		assertEquals(MovementController.HALF_WIDTH, this.player.x(), 1e-6, "center stays half a hitbox away from the wall");
	}

	@Test
	void invalidCommandsRejected() {
		spawn();
		assertThrows(IllegalArgumentException.class, () -> this.m.look(0, 91));
		assertThrows(IllegalArgumentException.class, () -> this.m.move(Double.NaN, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> this.m.move(1000, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> this.m.walk(PlayerPhysics.Input.NONE, 0));
		assertThrows(IllegalArgumentException.class, () -> this.m.walk(PlayerPhysics.Input.NONE, MovementController.MAX_WALK_TICKS + 1));
		assertThrows(IllegalArgumentException.class, () -> this.m.script(List.of()));
		MovementController notReady = new MovementController(new PlayerState());
		assertFalse(notReady.move(1, 0, 0).join().reached());
	}

	/** M11 (docs/42 §5): Paketfolge player_input → player_command (Sprint) → Position; Sprint-/Schleichbits im Tastenbyte. */
	@Test
	void sprintSendsPlayerCommandBeforeThePositionPacket() {
		spawn();
		this.m.setEntityId(42);
		this.m.setManualInput(PlayerPhysics.Input.parse("fp"));
		List<ServerboundPacket> first = this.m.tick();
		Serverbound.PlayerInput in = assertInstanceOf(Serverbound.PlayerInput.class, first.get(0));
		assertTrue(in.forward() && in.sprint() && !in.shift());
		Serverbound.PlayerCommand cmd = assertInstanceOf(Serverbound.PlayerCommand.class, first.get(1));
		assertEquals(42, cmd.entityId());
		assertEquals(Serverbound.PlayerCommand.START_SPRINTING, cmd.action());
		assertTrue(first.get(2) instanceof Serverbound.MovePlayerPos || first.get(2) instanceof Serverbound.MovePlayerPosRot);
		List<ServerboundPacket> second = this.m.tick();
		assertFalse(second.stream().anyMatch(p -> p instanceof Serverbound.PlayerCommand), "no duplicate command while sprinting");
		this.m.setManualInput(PlayerPhysics.Input.NONE);
		List<ServerboundPacket> stop = this.m.tick();
		assertTrue(stop.stream().anyMatch(p -> p instanceof Serverbound.PlayerCommand c && c.action() == Serverbound.PlayerCommand.STOP_SPRINTING));
		assertEquals(2, this.m.sprintCommands());
	}

	/** M11: Respawn/Dimensionswechsel (neue Physik) mitten im Sprint – Sprintzustand neu, Serverattribute bleiben erhalten. */
	@Test
	void newPhysicsAfterRespawnKeepsServerAttributesAndResetsSprint() {
		spawn();
		this.m.setAttribute(new Clientbound.UpdateAttributes.Attribute(dev.mcresearch.protocolclient.physics.MovementAttributes.SNEAKING_SPEED, 0.6,
			List.of()));
		this.m.setManualInput(PlayerPhysics.Input.parse("fp"));
		this.m.tick();
		assertTrue(this.m.physics().sprinting());
		this.m.setBlockView(this.view); // wie setWorld bei respawn/Disconnect-Neuaufbau
		assertFalse(this.m.physics().sprinting(), "new LocalPlayer: not sprinting");
		assertEquals(0.6, this.m.physics().attributes().value(dev.mcresearch.protocolclient.physics.MovementAttributes.SNEAKING_SPEED), 1e-12);
		this.m.setManualInput(PlayerPhysics.Input.parse("fp"));
		List<ServerboundPacket> again = this.m.tick();
		assertTrue(again.stream().anyMatch(p -> p instanceof Serverbound.PlayerCommand c && c.action() == Serverbound.PlayerCommand.START_SPRINTING),
			"sprint start is reported again after the reset");
	}

	/**
	 * M14: Absteigen per Schleichtaste. Der Server kennt danach weiterhin „Schleichen gedrückt“ (letztes player_input); wie
	 * {@code LocalPlayer#lastSentInput} darf der Client den gesendeten Zustand beim Absteigen nicht still zurücksetzen, sonst geht das
	 * Loslassen nie hinaus und der Server wirft den Spieler beim nächsten Aufsitzen sofort wieder ab.
	 */
	@Test
	void releasingSneakAfterDismountIsSentToTheServer() {
		spawn();
		dev.mcresearch.protocolclient.entity.EntityManager em = new dev.mcresearch.protocolclient.entity.EntityManager();
		em.setOwnId(500);
		this.m.setEntities(em);
		em.handle(new Clientbound.AddEntity(77, new java.util.UUID(3, 77), 90, 0.5, 64, 0.5, 0, 0, 0, 0, 0, 0, 0)); // Eichenboot
		em.handle(new Clientbound.SetPassengers(77, new int[]{500}));
		this.m.tick();
		this.m.setManualInput(new PlayerPhysics.Input(false, false, false, false, false, true, false));
		List<ServerboundPacket> sneak = this.m.tick();
		assertTrue(sneak.stream().anyMatch(p -> p instanceof Serverbound.PlayerInput i && i.shift()), "sneak is sent while riding");
		em.handle(new Clientbound.SetPassengers(77, new int[0])); // Server: abgestiegen
		this.m.setManualInput(PlayerPhysics.Input.NONE);
		List<ServerboundPacket> after = this.m.tick();
		assertTrue(after.stream().anyMatch(p -> p instanceof Serverbound.PlayerInput i && !i.shift()), "release of sneak reaches the server");
	}
}
