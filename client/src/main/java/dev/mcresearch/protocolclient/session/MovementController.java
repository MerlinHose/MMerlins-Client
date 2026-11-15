package dev.mcresearch.protocolclient.session;

import dev.mcresearch.protocolclient.physics.BlockView;
import dev.mcresearch.protocolclient.physics.CollisionWorld;
import dev.mcresearch.protocolclient.physics.PlayerPhysics;
import dev.mcresearch.protocolclient.physics.ShapeRegistry;
import dev.mcresearch.protocolclient.protocol.packet.Serverbound;
import dev.mcresearch.protocolclient.protocol.packet.ServerboundPacket;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.TerrainCache;
import dev.mcresearch.protocolclient.world.WorldState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Spielerbewegung (docs/15, ab M5 docs/22 §6). Wird einmal pro Client-Tick (50 ms) aufgerufen: bestimmt den
 * Tastenzustand des Ticks aus dem laufenden Befehl, führt einen Physik-Tick ({@link PlayerPhysics}) aus und liefert die zu
 * sendenden Pakete ({@code player_input}, höchstens ein Positionspaket; danach sendet die Session {@code client_tick_end}).
 *
 * <p>Befehle erzeugen ausschließlich <b>Tastenzustände</b> wie ein menschlicher Spieler (vorwärts/rückwärts/seitlich,
 * Sprung, Blickrichtung); die Position ergibt sich allein aus der Physik. Ausnahme: {@link #claim} (Testfunktion).
 */
public final class MovementController {
	/** Bewegungsschwelle von {@code LocalPlayer#sendPosition}: (2·10⁻⁴)². */
	static final double MOVE_EPSILON_SQ = 2.0E-4 * 2.0E-4;
	static final int POSITION_REMINDER_TICKS = 20;
	/** Obergrenze je Bewegungsbefehl (Schutz vor Endlosbewegung). */
	public static final int MAX_MOVE_TICKS = 20 * 120;
	public static final double MAX_MOVE_DISTANCE = 256;
	public static final int MAX_WALK_TICKS = 20 * 120;
	/** Wartezeit auf eine Serverkorrektur nach einem Testpaket ({@link #claim}). */
	static final int CLAIM_OBSERVE_TICKS = 10;
	public static final int TRAJECTORY_SIZE = 400;
	/** Zielradius von {@link #move}. */
	public static final double ARRIVE_RADIUS = 0.25;
	/** Bodenreibung × Luftwiderstand am Boden (0,6 · 0,91): Ausrollweg ≈ v · f / (1 − f). */
	private static final double GROUND_DRAG = 0.6F * 0.91F;

	/** Halbe Breite der Spieler-Hitbox (0,6F / 2). */
	public static final double HALF_WIDTH = PlayerPhysics.WIDTH / 2.0F;

	/** Ergebnis eines Bewegungsbefehls. */
	public record MoveResult(String outcome, boolean reached, boolean correctedByServer, double x, double y, double z, int ticks) {
		@Override
		public String toString() {
			return String.format(Locale.ROOT, "%s after %d ticks at (%.3f, %.3f, %.3f)%s", this.outcome, this.ticks, this.x, this.y, this.z,
				this.correctedByServer ? " [server correction]" : "");
		}
	}

	/** Laufender Befehl: liefert je Tick einen Tastenzustand und entscheidet über das Ende. */
	private abstract class Command {
		final CompletableFuture<MoveResult> future = new CompletableFuture<>();
		int ticks;

		abstract PlayerPhysics.Input input();

		/** M11: Blickneigung ab diesem Tick (null = unverändert), z. B. Schwimmrichtung in Teststrecken. */
		Float pitch() {
			return null;
		}

		/** Nach dem Physik-Tick; true = beendet. */
		abstract boolean after(PlayerPhysics.TickResult r);

		void complete(final String outcome, final boolean reached, final boolean corrected) {
			this.future.complete(result(outcome, reached, corrected, this.ticks));
		}
	}

	/** Feste Tastenfolge (je Tick ein Eintrag), danach Ausrollen bis zum Stillstand ({@code walk}, {@code jump}, Skripte). */
	private final class ScriptCommand extends Command {
		final List<PlayerPhysics.Input> inputs;
		List<Float> pitches = List.of();
		final List<PlayerPhysics.TickResult> trace = new ArrayList<>();
		final boolean settle;
		final CompletableFuture<List<PlayerPhysics.TickResult>> traceFuture = new CompletableFuture<>();
		int idleTicks;

		ScriptCommand(final List<PlayerPhysics.Input> inputs, final boolean settle) {
			this.inputs = List.copyOf(inputs);
			this.settle = settle;
		}

		@Override
		PlayerPhysics.Input input() {
			return this.ticks < this.inputs.size() ? this.inputs.get(this.ticks) : PlayerPhysics.Input.NONE;
		}

		@Override
		Float pitch() {
			return this.ticks < this.pitches.size() ? this.pitches.get(this.ticks) : null;
		}

		@Override
		boolean after(final PlayerPhysics.TickResult r) {
			this.trace.add(r);
			this.ticks++;
			if (this.ticks < this.inputs.size()) {
				return false;
			}
			if (!this.settle) {
				done("script done");
				return true;
			}
			boolean still = r.onGround() && Math.abs(r.vx()) < 1.0E-6 && Math.abs(r.vz()) < 1.0E-6;
			this.idleTicks = still ? this.idleTicks + 1 : 0;
			if (this.idleTicks >= 2 || this.ticks >= this.inputs.size() + 100) {
				done(still ? "done, at rest" : "done (still moving after 100 ticks)");
				return true;
			}
			return false;
		}

		void done(final String outcome) {
			complete(outcome, true, false);
			this.traceFuture.complete(List.copyOf(this.trace));
		}

		@Override
		void complete(final String outcome, final boolean reached, final boolean corrected) {
			super.complete(outcome, reached, corrected);
			this.traceFuture.complete(List.copyOf(this.trace));
		}
	}

	/** Zielbewegung: zum Ziel drehen, vorwärts gehen, an Hindernissen bei Bedarf springen, rechtzeitig ausrollen. */
	private final class MoveCommand extends Command {
		final double targetX;
		final double targetY;
		final double targetZ;
		final boolean allowJump;
		boolean coasting;
		boolean verticalJumpDone;
		int blockedTicks;
		double bestDist = Double.MAX_VALUE;

		MoveCommand(final double tx, final double ty, final double tz, final boolean allowJump) {
			this.targetX = tx;
			this.targetY = ty;
			this.targetZ = tz;
			this.allowJump = allowJump;
		}

		double horizontalDistance() {
			return Math.hypot(this.targetX - MovementController.this.physics.x(), this.targetZ - MovementController.this.physics.z());
		}

		@Override
		PlayerPhysics.Input input() {
			PlayerPhysics p = MovementController.this.physics;
			double dx = this.targetX - p.x();
			double dz = this.targetZ - p.z();
			double dist = Math.hypot(dx, dz);
			if (dist < 1.0E-3) {
				// reine Aufwärtsbewegung: einmal springen
				if (this.allowJump && !this.verticalJumpDone && p.onGround()) {
					this.verticalJumpDone = true;
					return new PlayerPhysics.Input(false, false, false, false, true);
				}
				return PlayerPhysics.Input.NONE;
			}
			if (!this.coasting) {
				double speed = Math.hypot(p.velocity().x(), p.velocity().z());
				double coast = speed * GROUND_DRAG / (1.0 - GROUND_DRAG);
				if (dist <= coast + 0.05 && p.onGround()) {
					this.coasting = true;
				}
			}
			if (this.coasting) {
				return PlayerPhysics.Input.NONE;
			}
			if (dist > ARRIVE_RADIUS) {
				// Blickrichtung zum Ziel (Yaw 0 = +Z, vorwärts = (−sin, cos))
				float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
				MovementController.this.player.setRotation(yaw, MovementController.this.player.xRot());
			}
			boolean jump = this.allowJump && p.onGround() && p.horizontalCollision();
			return new PlayerPhysics.Input(true, false, false, false, jump);
		}

		@Override
		boolean after(final PlayerPhysics.TickResult r) {
			if (++this.ticks > MAX_MOVE_TICKS) {
				complete("aborted after " + MAX_MOVE_TICKS + " ticks", false, false);
				return true;
			}
			if (r.touchedUnloaded()) {
				complete("stopped: chunk not loaded ahead", false, false);
				return true;
			}
			double dist = horizontalDistance();
			if (dist < this.bestDist - 1.0E-3) {
				this.bestDist = dist;
				this.blockedTicks = 0;
			} else if (!this.coasting) {
				this.blockedTicks++;
			}
			if (this.blockedTicks >= 20) {
				complete(String.format(Locale.ROOT, "blocked by collision %.2f blocks from target", dist), false, false);
				return true;
			}
			boolean still = r.onGround() && Math.abs(r.vx()) < 1.0E-6 && Math.abs(r.vz()) < 1.0E-6;
			boolean verticalPending = this.allowJump && !this.verticalJumpDone && dist < 1.0E-3 && this.targetY > r.y() + 1.0E-3;
			if ((this.coasting || dist < 1.0E-3) && still && !verticalPending) {
				double dy = r.y() - this.targetY;
				boolean reached = dist <= ARRIVE_RADIUS;
				String outcome = reached
					? (Math.abs(dy) < 1.0E-3 ? "reached target" : String.format(Locale.ROOT, "reached target column; height differs by %.2f", dy))
					: String.format(Locale.ROOT, "stopped %.2f blocks from target", dist);
				complete(outcome, reached, false);
				return true;
			}
			return false;
		}
	}

	private final PlayerState player;
	private TerrainCache terrain;
	private PlayerPhysics physics;
	private WorldState world;
	private Command command;
	private Claim claim;
	private boolean ready;
	private final Deque<PlayerPhysics.TickResult> trajectory = new ArrayDeque<>();
	private PlayerPhysics.Input lastPhysicsInput = PlayerPhysics.Input.NONE;
	/** Tastenzustand aus dem Grafikfenster (M6); gilt, solange kein Befehl läuft. */
	private PlayerPhysics.Input manualInput = PlayerPhysics.Input.NONE;
	private double prevX;
	private double prevY;
	private double prevZ;
	private long lastTickNanos;

	/**
	 * Zustand für die Kamera (docs/24 §5): Position vor und nach dem letzten Physik-Tick und dessen Zeitpunkt. Die Kamera
	 * interpoliert nur zwischen diesen beiden echten Physikzuständen; sie führt keine eigene Spielerposition.
	 */
	public record RenderState(double prevX, double prevY, double prevZ, double x, double y, double z, long tickNanos, float yaw, float pitch,
		boolean onGround, boolean ready, float eyeHeightOld, float eyeHeight) {
		/** Kamerahöhe über den Füßen bei Teiltick {@code alpha} ({@code Camera}: lerp(eyeHeightOld, eyeHeight)). */
		public float eyeHeightAt(final double alpha) {
			return this.eyeHeightOld + (float) alpha * (this.eyeHeight - this.eyeHeightOld);
		}
	}

	/** M11: Kamera-Augenhöhe wie {@code Camera#tick}: je Tick halber Weg zur Augenhöhe der Pose. */
	private float cameraEyeHeight = 1.62F;
	private float cameraEyeHeightOld = 1.62F;

	private static final class Claim {
		final double dx;
		final double dy;
		final double dz;
		final CompletableFuture<MoveResult> future = new CompletableFuture<>();
		boolean sent;
		int ticksSinceSent;

		Claim(final double dx, final double dy, final double dz) {
			this.dx = dx;
			this.dy = dy;
			this.dz = dz;
		}
	}

	// zuletzt gesendeter Zustand (wie LocalPlayer.xLast/yRotLast/lastOnGround …)
	private double lastX;
	private double lastY;
	private double lastZ;
	private float lastYRot;
	private float lastXRot;
	private boolean lastOnGround;
	private boolean lastHorizontalCollision;
	private int positionReminder;
	private Serverbound.PlayerInput lastInput = Serverbound.PlayerInput.EMPTY;
	private int corrections;
	private long ticks;

	public MovementController(final PlayerState player) {
		this.player = player;
	}

	/** Heightmap-Bodenhöhe (M2) – seit M5 nur noch zur Anzeige ({@code physics}). */
	public synchronized void setTerrain(final TerrainCache terrain) {
		this.terrain = terrain;
	}

	public synchronized TerrainCache terrain() {
		return this.terrain;
	}

	/** Weltmodell für die Kollision; ohne Welt (Known Packs angenommen) keine Physik, Position wird gehalten. */
	public synchronized void setWorld(final WorldState world) {
		this.world = world;
		if (world == null) {
			this.physics = null;
			return;
		}
		BlockView view = world::stateId;
		this.physics = new PlayerPhysics(new CollisionWorld(view, ShapeRegistry.get()), BlockRegistry.get());
		initPhysics();
		syncPhysicsFromPlayer();
	}

	/** Testzugang: Physik auf einer beliebigen Blockwelt. */
	synchronized void setBlockView(final BlockView view) {
		this.world = null;
		this.physics = new PlayerPhysics(new CollisionWorld(view, ShapeRegistry.get()), BlockRegistry.get());
		initPhysics();
		syncPhysicsFromPlayer();
	}

	/**
	 * M10: {@code set_entity_motion} für den eigenen Spieler (Rückstoß nach Treffern, Explosionen): wie {@code Entity#lerpMotion} wird die
	 * Geschwindigkeit ersetzt; die Position bleibt, die weitere Bewegung rechnet die M5-Physik (docs/40 §2).
	 */
	public synchronized void applyServerMotion(final double vx, final double vy, final double vz) {
		if (this.physics != null) {
			this.physics.setVelocity(vx, vy, vz);
			this.serverMotions++;
			java.util.logging.Logger.getLogger("mcclient.movement").fine(String.format(Locale.ROOT, "server motion at tick %d: (%s, %s, %s)", this.ticks, vx, vy, vz));
			this.lastServerMotionTick = this.ticks;
		}
	}

	/** Testzugang (M10): Bewegungsgeschwindigkeit als Grundwert; im Betrieb kommen alle Bewegungsattribute über {@link #setAttribute}. */
	public synchronized void setMovementSpeed(final float speed) {
		this.movementSpeed = speed;
		if (this.physics != null) {
			this.physics.setMovementSpeed(speed);
		}
	}

	private float movementSpeed = Float.NaN;

	/** Neue Physik-Instanz mit dem bekannten Attribut-, Nahrungs- und Spielmodusstand versehen. */
	private void initPhysics() {
		if (!Float.isNaN(this.movementSpeed)) {
			this.physics.setMovementSpeed(this.movementSpeed);
		}
		for (var a : this.serverAttributes.values()) {
			applyAttribute(this.physics, a);
		}
		this.physics.setFood(this.food, this.mayfly);
		this.physics.setEffects(this.effBlind, this.effJump, this.effLevitation, this.effSlowFall);
		this.physics.setDolphinsGrace(this.effDolphin);
		this.wasSprinting = false;
	}

	public synchronized int serverMotions() {
		return this.serverMotions;
	}

	private int serverMotions;
	private long lastServerMotionTick = -1;

	public synchronized long lastServerMotionTick() {
		return this.lastServerMotionTick;
	}
	private int entityId = -1;
	/** Zuletzt gemeldeter Sprintzustand ({@code LocalPlayer.wasSprinting}). */
	private boolean wasSprinting;
	private int sprintCommands;
	private final java.util.Map<Integer, dev.mcresearch.protocolclient.protocol.packet.Clientbound.UpdateAttributes.Attribute> serverAttributes =
		new java.util.HashMap<>();
	private int food = 20;
	private boolean mayfly;

	private boolean effBlind;
	private int effJump = -1;
	private int effLevitation = -1;
	private boolean effSlowFall;
	private boolean effDolphin;

	/** M11: Effekte mit Bewegungswirkung (Blindheit, Sprungkraft, Schweben, Sturzflug, Delfinsgunst); bleibt über Physik-Neuanlagen erhalten. */
	public synchronized void setMovementEffects(final boolean blindness, final int jumpBoost, final int levitation, final boolean slowFalling,
		final boolean dolphinsGrace) {
		this.effBlind = blindness;
		this.effJump = jumpBoost;
		this.effLevitation = levitation;
		this.effSlowFall = slowFalling;
		this.effDolphin = dolphinsGrace;
		if (this.physics != null) {
			this.physics.setEffects(blindness, jumpBoost, levitation, slowFalling);
			this.physics.setDolphinsGrace(dolphinsGrace);
		}
	}

	/** M11: Sichtweite unter Wasser (0 … 1) für die Grafik. */
	public synchronized float waterVision() {
		return this.physics == null ? 0.0F : this.physics.waterVision();
	}

	public synchronized void setEntityId(final int id) {
		this.entityId = id;
	}

	public synchronized int sprintCommands() {
		return this.sprintCommands;
	}

	/**
	 * M11: Bewegungsattribute aus {@code update_attributes} (ersetzt Grundwert und Modifikatoren wie
	 * {@code ClientPacketListener#handleUpdateAttributes}); bleibt über Physik-Neuanlagen erhalten.
	 */
	public synchronized void setAttribute(final dev.mcresearch.protocolclient.protocol.packet.Clientbound.UpdateAttributes.Attribute a) {
		if (!dev.mcresearch.protocolclient.physics.MovementAttributes.relevant(a.id())) {
			return;
		}
		this.serverAttributes.put(a.id(), a);
		if (this.physics != null) {
			applyAttribute(this.physics, a);
		}
	}

	private static void applyAttribute(final PlayerPhysics p, final dev.mcresearch.protocolclient.protocol.packet.Clientbound.UpdateAttributes.Attribute a) {
		List<dev.mcresearch.protocolclient.physics.MovementAttributes.Modifier> mods = new ArrayList<>();
		for (var m : a.modifiers()) {
			mods.add(new dev.mcresearch.protocolclient.physics.MovementAttributes.Modifier(m.id(), m.amount(), m.operation()));
		}
		p.attributes().setFromServer(a.id(), a.base(), mods);
	}

	/** M11: Nahrung aus {@code set_health} (Sprinten erst über 6). */
	public synchronized void setFoodLevel(final int foodLevel) {
		this.food = foodLevel;
		if (this.physics != null) {
			this.physics.setFood(this.food, this.mayfly);
		}
	}

	/** M11: Fähigkeit {@code mayfly} aus {@code player_abilities} (Kreativ: Sprinten unabhängig von der Nahrung). */
	public synchronized void setMayfly(final boolean mayFly) {
		this.mayfly = mayFly;
		if (this.physics != null) {
			this.physics.setFood(this.food, this.mayfly);
		}
	}

	/**
	 * M11: eigene Entity-Metadaten vom Server ({@code ClientPacketListener#handleSetEntityData} gilt auch für den eigenen Spieler):
	 * Index 0 Bit 3 setzt das Sprint-Flag (ohne Attribut-Modifikator), Index 6 die Pose; beides korrigiert die Physik danach selbst.
	 */
	public synchronized void onOwnEntityData(final int index, final Object value) {
		if (this.physics == null) {
			return;
		}
		if (index == 0 && value instanceof Byte b) {
			this.physics.setSprintFlag((b & 0x08) != 0);
		} else if (index == 6 && value instanceof Integer p) {
			for (dev.mcresearch.protocolclient.physics.PlayerPose pose : dev.mcresearch.protocolclient.physics.PlayerPose.values()) {
				if (pose.protocolId() == p) {
					this.physics.setPose(pose);
				}
			}
		}
	}

	public synchronized PlayerPhysics physics() {
		return this.physics;
	}

	public synchronized WorldState world() {
		return this.world;
	}

	private void syncPhysicsFromPlayer() {
		if (this.physics != null && this.player.positioned()) {
			this.physics.setPosition(this.player.x(), this.player.y(), this.player.z(), this.player.velocityX(), this.player.velocityY(),
				this.player.velocityZ());
			this.physics.setYRot(this.player.yRot());
		}
	}

	/** Ab jetzt Bewegung senden (nach Spawn-Teleport und {@code player_loaded}). */
	public synchronized void setReady() {
		if (!this.ready) {
			this.ready = true;
			this.lastX = this.player.x();
			this.lastY = this.player.y();
			this.lastZ = this.player.z();
			this.lastYRot = this.player.yRot();
			this.lastXRot = this.player.xRot();
			syncPhysicsFromPlayer();
		}
	}

	public synchronized boolean ready() {
		return this.ready;
	}

	public synchronized int corrections() {
		return this.corrections;
	}

	public synchronized long ticks() {
		return this.ticks;
	}

	public synchronized boolean moving() {
		return this.command != null || this.claim != null;
	}

	/** Letzte Physik-Ticks, älteste zuerst. */
	public synchronized List<PlayerPhysics.TickResult> trajectory() {
		return new ArrayList<>(this.trajectory);
	}

	/**
	 * Der Server hat die Position per {@code player_position} gesetzt (Spawn, Korrektur oder Teleport). Laufende Befehle
	 * werden abgebrochen; Position und Geschwindigkeit gehen wie in {@code ClientPacketListener#handleMovePlayer} in die
	 * Physik über ({@code onGround} bleibt unverändert).
	 */
	public synchronized void onServerPosition(final boolean correction) {
		if (correction) {
			this.corrections++;
		}
		if (this.command != null) {
			Command c = this.command;
			this.command = null;
			c.complete("corrected by server", false, true);
		}
		if (this.claim != null && this.claim.sent) {
			this.claim.future.complete(result("claim rejected: server reset the position", false, true, CLAIM_OBSERVE_TICKS));
			this.claim = null;
		}
		syncPhysicsFromPlayer();
		this.prevX = this.player.x();
		this.prevY = this.player.y();
		this.prevZ = this.player.z();
	}

	private CompletableFuture<MoveResult> start(final Command c) {
		if (!this.ready || this.physics == null) {
			c.complete(this.physics == null ? "no world model (dimension data unknown) – physics unavailable" : "not ready", false, false);
			return c.future;
		}
		if (this.command != null) {
			Command old = this.command;
			this.command = null;
			old.complete("superseded by new command", false, false);
		}
		this.command = c;
		return c.future;
	}

	/** Zur relativen Zielposition gehen (Blick zum Ziel, vorwärts, Sprung an Hindernissen wenn dy &gt; 0). */
	public synchronized CompletableFuture<MoveResult> move(final double dx, final double dy, final double dz) {
		validate(dx, dy, dz);
		if (Math.hypot(dx, dz) > MAX_MOVE_DISTANCE || Math.abs(dy) > MAX_MOVE_DISTANCE) {
			throw new IllegalArgumentException("move distance exceeds " + MAX_MOVE_DISTANCE + " blocks");
		}
		return start(new MoveCommand(this.player.x() + dx, this.player.y() + dy, this.player.z() + dz, dy > 0));
	}

	/** Tasten {@code input} für {@code ticks} Ticks halten, danach ausrollen lassen. */
	public synchronized CompletableFuture<MoveResult> walk(final PlayerPhysics.Input input, final int ticks) {
		if (ticks < 1 || ticks > MAX_WALK_TICKS) {
			throw new IllegalArgumentException("ticks must be within 1.." + MAX_WALK_TICKS);
		}
		List<PlayerPhysics.Input> inputs = new ArrayList<>(ticks);
		for (int i = 0; i < ticks; i++) {
			inputs.add(input);
		}
		return start(new ScriptCommand(inputs, true));
	}

	/** Regulärer Sprung: Sprungtaste einen Tick lang, danach warten bis gelandet. */
	public synchronized CompletableFuture<MoveResult> jump() {
		return start(new ScriptCommand(List.of(new PlayerPhysics.Input(false, false, false, false, true)), true));
	}

	/** Testskript: genau diese Tastenfolge, ohne Ausrollphase; liefert die Physik-Ticks. */
	public synchronized CompletableFuture<List<PlayerPhysics.TickResult>> script(final List<PlayerPhysics.Input> inputs) {
		if (inputs.isEmpty() || inputs.size() > MAX_WALK_TICKS) {
			throw new IllegalArgumentException("script length 1.." + MAX_WALK_TICKS);
		}
		ScriptCommand c = new ScriptCommand(inputs, false);
		start(c);
		return c.traceFuture;
	}

	/** M11-Testskript mit Blickneigung je Tick (Eintrag null = unverändert). */
	public synchronized CompletableFuture<List<PlayerPhysics.TickResult>> script(final List<PlayerPhysics.Input> inputs, final List<Float> pitches) {
		if (inputs.isEmpty() || inputs.size() > MAX_WALK_TICKS || pitches.size() != inputs.size()) {
			throw new IllegalArgumentException("script length 1.." + MAX_WALK_TICKS + ", one pitch entry per tick");
		}
		ScriptCommand c = new ScriptCommand(inputs, false);
		c.pitches = java.util.Collections.unmodifiableList(new ArrayList<>(pitches));
		start(c);
		return c.traceFuture;
	}

	/**
	 * <b>Testfunktion:</b> sendet genau ein {@code move_player_pos_rot} mit um (dx, dy, dz) versetzter Position, ohne Physik.
	 * Dient dazu, die Korrekturregeln des Servers zu prüfen (docs/15 §6). Keine reguläre Bewegung.
	 */
	public synchronized CompletableFuture<MoveResult> claim(final double dx, final double dy, final double dz) {
		validate(dx, dy, dz);
		if (!this.ready) {
			return CompletableFuture.completedFuture(result("not ready", false, false, 0));
		}
		if (this.command != null) {
			Command old = this.command;
			this.command = null;
			old.complete("superseded by claim", false, false);
		}
		this.claim = new Claim(dx, dy, dz);
		return this.claim.future;
	}

	public synchronized void look(final float yaw, final float pitch) {
		if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
			throw new IllegalArgumentException("yaw/pitch must be finite");
		}
		if (pitch < -90f || pitch > 90f) {
			throw new IllegalArgumentException("pitch must be within -90..90");
		}
		this.player.setRotation(yaw, pitch);
	}

	private static void validate(final double... values) {
		for (double v : values) {
			if (!Double.isFinite(v)) {
				throw new IllegalArgumentException("values must be finite");
			}
		}
	}

	/** Ein Tick: Tastenzustand bestimmen, Physik anwenden, zu sendende Pakete bestimmen (ohne client_tick_end). */
	public synchronized List<ServerboundPacket> tick() {
		List<ServerboundPacket> out = new ArrayList<>(2);
		if (!this.ready) {
			return out;
		}
		this.ticks++;
		this.prevX = this.player.x();
		this.prevY = this.player.y();
		this.prevZ = this.player.z();
		this.lastTickNanos = System.nanoTime();
		PlayerPhysics.Input input = this.command == null && this.claim == null ? this.manualInput : PlayerPhysics.Input.NONE;
		if (this.claim != null) {
			tickClaim();
		} else if (this.physics != null) {
			Command c = this.command;
			if (c != null) {
				input = c.input();
				Float pitch = c.pitch();
				if (pitch != null) {
					this.player.setRotation(this.player.yRot(), pitch);
				}
			}
			this.physics.setYRot(this.player.yRot());
			this.physics.setXRot(this.player.xRot()); // M11: Schwimmrichtung folgt der Blickneigung
			PlayerPhysics.TickResult r = this.physics.tick(input);
			this.player.setPhysics(r.x(), r.y(), r.z(), r.vx(), r.vy(), r.vz(), r.onGround(), r.horizontalCollision());
			this.trajectory.addLast(r);
			while (this.trajectory.size() > TRAJECTORY_SIZE) {
				this.trajectory.removeFirst();
			}
			if (c != null && c.after(r) && this.command == c) {
				this.command = null;
			}
			this.cameraEyeHeightOld = this.cameraEyeHeight;
			this.cameraEyeHeight = this.cameraEyeHeight + (this.physics.eyeHeight() - this.cameraEyeHeight) * 0.5F;
		}
		this.lastPhysicsInput = input;
		// LocalPlayer#sendChanges: player_input bei geändertem Tastenzustand, dann sendIsSprintingIfNeeded, dann Positionspaket
		Serverbound.PlayerInput keys = new Serverbound.PlayerInput(input.forward(), input.backward(), input.left(), input.right(), input.jump(),
			input.sneak(), input.sprint());
		if (!keys.equals(this.lastInput)) {
			out.add(keys);
			this.lastInput = keys;
		}
		if (this.physics != null && this.claim == null) {
			boolean sprinting = this.physics.sprinting();
			if (sprinting != this.wasSprinting) {
				out.add(new Serverbound.PlayerCommand(this.entityId, sprinting ? Serverbound.PlayerCommand.START_SPRINTING
					: Serverbound.PlayerCommand.STOP_SPRINTING, 0));
				this.wasSprinting = sprinting;
				this.sprintCommands++;
			}
		}
		ServerboundPacket movePacket = positionPacket();
		if (movePacket != null) {
			out.add(movePacket);
		}
		return out;
	}

	/** Tastenzustand des Grafikfensters für die folgenden Ticks (ohne laufenden Befehl). */
	public synchronized void setManualInput(final PlayerPhysics.Input input) {
		this.manualInput = input == null ? PlayerPhysics.Input.NONE : input;
	}

	public synchronized RenderState renderState() {
		return new RenderState(this.prevX, this.prevY, this.prevZ, this.player.x(), this.player.y(), this.player.z(), this.lastTickNanos,
			this.player.yRot(), this.player.xRot(), this.player.onGround(), this.ready, this.cameraEyeHeightOld, this.cameraEyeHeight);
	}

	public synchronized PlayerPhysics.Input lastInput() {
		return this.lastPhysicsInput;
	}

	private void tickClaim() {
		Claim c = this.claim;
		if (!c.sent) {
			this.player.setPosition(this.player.x() + c.dx, this.player.y() + c.dy, this.player.z() + c.dz);
			c.sent = true;
			return;
		}
		if (++c.ticksSinceSent >= CLAIM_OBSERVE_TICKS) {
			this.claim = null;
			// keine Korrektur: die Behauptung gilt, die Physik übernimmt die neue Position
			syncPhysicsFromPlayer();
			c.future.complete(result("claim not corrected within " + CLAIM_OBSERVE_TICKS + " ticks (server accepted)", true, false, c.ticksSinceSent));
		}
	}

	private MoveResult result(final String outcome, final boolean reached, final boolean corrected, final int ticks) {
		return new MoveResult(outcome, reached, corrected, this.player.x(), this.player.y(), this.player.z(), ticks);
	}

	/** Paketwahl wie {@code LocalPlayer#sendPosition}. */
	private ServerboundPacket positionPacket() {
		double x = this.player.x();
		double y = this.player.y();
		double z = this.player.z();
		float yRot = this.player.yRot();
		float xRot = this.player.xRot();
		boolean og = this.player.onGround();
		boolean hc = this.player.horizontalCollision();
		this.positionReminder++;
		double dx = x - this.lastX;
		double dy = y - this.lastY;
		double dz = z - this.lastZ;
		boolean moved = dx * dx + dy * dy + dz * dz > MOVE_EPSILON_SQ || this.positionReminder >= POSITION_REMINDER_TICKS;
		boolean rot = yRot != this.lastYRot || xRot != this.lastXRot;
		ServerboundPacket packet = null;
		if (moved && rot) {
			packet = new Serverbound.MovePlayerPosRot(x, y, z, yRot, xRot, og, hc);
		} else if (moved) {
			packet = new Serverbound.MovePlayerPos(x, y, z, og, hc);
		} else if (rot) {
			packet = new Serverbound.MovePlayerRot(yRot, xRot, og, hc);
		} else if (og != this.lastOnGround || hc != this.lastHorizontalCollision) {
			packet = new Serverbound.MovePlayerStatusOnly(og, hc);
		}
		if (moved) {
			this.lastX = x;
			this.lastY = y;
			this.lastZ = z;
			this.positionReminder = 0;
		}
		if (rot) {
			this.lastYRot = yRot;
			this.lastXRot = xRot;
		}
		this.lastOnGround = og;
		this.lastHorizontalCollision = hc;
		return packet;
	}
}
