package dev.mcresearch.protocolclient.physics;

import dev.mcresearch.protocolclient.world.BlockRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Eigenständige Spielerphysik für Gehen, Springen, Fallen und Blockkollision an Land (docs/21 §3–§7, docs/22 §4).
 * Ein Aufruf von {@link #tick(Input)} entspricht einem Vanilla-Client-Tick ({@code LivingEntity#aiStep}) mit dem
 * Tastenzustand dieses Ticks. Alle Gleitkommaschritte folgen den Typen der Referenz ({@code float}/{@code double}),
 * damit die Bahn bitgleich reproduzierbar ist.
 *
 * <p>M11 (docs/42): Sprinten (Taste und Doppeltipp), Schleichen mit Posenwechsel, Hitbox/Augenhöhe je Pose, Kantenschutz,
 * Bewegungsattribute und Effekte. Noch nicht simulierte Umgebung meldet {@link TickResult#unsupported()}.
 */
public final class PlayerPhysics {
	public static final float WIDTH = 0.6F;
	public static final float HEIGHT = 1.8F;
	public static final float MOVEMENT_SPEED = 0.1F;
	public static final float JUMP_STRENGTH = 0.42F;
	public static final double GRAVITY = 0.08;
	public static final float STEP_HEIGHT = 0.6F;
	public static final float AIR_SPEED = 0.02F;
	public static final float SPRINT_AIR_SPEED = 0.025999999F;
	public static final float INPUT_FRICTION = 0.98F;
	/** Vanilla-Option {@code sprintWindow} (Standard 7 Ticks) für den Doppeltipp. */
	public static final int SPRINT_WINDOW = 7;
	private static final float ON_POS_BELOW = 0.500001F;
	private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

	/**
	 * Tastenzustand eines Ticks wie {@code net.minecraft.world.entity.player.Input}: Bewegung, Sprung, Schleichen (Shift) und
	 * Sprinttaste. Sprinten selbst ist ein Zustand der Physik (Taste oder Doppeltipp), kein Tastenbit.
	 */
	public record Input(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint) {
		public static final Input NONE = new Input(false, false, false, false, false, false, false);

		public Input(final boolean forward, final boolean backward, final boolean left, final boolean right, final boolean jump) {
			this(forward, backward, left, right, jump, false, false);
		}

		/** Tastenkürzel der Teststrecken: f b l r j, s = Schleichen, p = Sprinttaste, "-" = keine Taste. */
		public static Input parse(final String keys) {
			return new Input(keys.indexOf('f') >= 0, keys.indexOf('b') >= 0, keys.indexOf('l') >= 0, keys.indexOf('r') >= 0, keys.indexOf('j') >= 0,
				keys.indexOf('s') >= 0, keys.indexOf('p') >= 0);
		}

		public boolean any() {
			return this.forward || this.backward || this.left || this.right || this.jump || this.sneak || this.sprint;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			if (this.forward) {
				sb.append('f');
			}
			if (this.backward) {
				sb.append('b');
			}
			if (this.left) {
				sb.append('l');
			}
			if (this.right) {
				sb.append('r');
			}
			if (this.jump) {
				sb.append('j');
			}
			if (this.sneak) {
				sb.append('s');
			}
			if (this.sprint) {
				sb.append('p');
			}
			return sb.isEmpty() ? "-" : sb.toString();
		}
	}

	/** Bewegungszustand nach einem Tick (M11): Pose, Sprinten, Schleichen, kleine Kollision, Fallstrecke, Fluid- und Kletterzustand. */
	public record MotionState(PlayerPose pose, boolean sprinting, boolean crouching, boolean shift, boolean minorHorizontalCollision,
		double fallDistance, boolean inWater, boolean underWater, boolean swimming, double waterHeight, double lavaHeight, boolean inLava,
		boolean onClimbable) {
	}

	/** Zustand nach einem Tick (für Trajektorie, Terminal, Vergleich mit Vanilla). */
	public record TickResult(long tick, Input input, double x, double y, double z, double vx, double vy, double vz, boolean onGround,
		boolean horizontalCollision, boolean verticalCollision, boolean jumped, boolean stepped, boolean touchedUnloaded, String unsupported,
		MotionState motion) {
		public PlayerPose pose() {
			return this.motion.pose();
		}

		public boolean sprinting() {
			return this.motion.sprinting();
		}

		@Override
		public String toString() {
			return String.format(Locale.ROOT, "t=%d in=%s pos=(%.6f, %.6f, %.6f) v=(%.6f, %.6f, %.6f) ground=%s hcol=%s vcol=%s pose=%s%s%s%s%s%s",
				this.tick, this.input, this.x, this.y, this.z, this.vx, this.vy, this.vz, this.onGround, this.horizontalCollision,
				this.verticalCollision, this.motion.pose(), this.motion.sprinting() ? " SPRINT" : "", this.jumped ? " JUMP" : "",
				this.stepped ? " STEP" : "", this.touchedUnloaded ? " UNLOADED" : "", this.unsupported == null ? "" : " unsupported=" + this.unsupported);
		}
	}

	private final CollisionWorld world;
	private final BlockRegistry blocks;
	private double x;
	private double y;
	private double z;
	private double vx;
	private double vy;
	private double vz;
	private float yRot;
	private boolean onGround;
	private boolean horizontalCollision;
	private boolean verticalCollision;
	private int noJumpDelay;
	private int[] mainSupport;
	private boolean onGroundNoBlocks;
	private double fallDistance;
	private long tick;
	private boolean lastStepped;
	private boolean lastTouchedUnloaded;
	// M11: Bewegungszustand (docs/42 §1–§4)
	private final MovementAttributes attributes = new MovementAttributes();
	private PlayerPose pose = PlayerPose.STANDING;
	private boolean crouching;
	private boolean sprinting;
	private boolean swimming;
	private int sprintTriggerTime;
	private boolean minorHorizontalCollision;
	/** Tastenzustand des vorigen Ticks ({@code input.keyPresses} vor {@code KeyboardInput#tick}). */
	private Input previousInput = Input.NONE;
	/** Aktueller Shift-Zustand für {@code isShiftKeyDown}/{@code isDescending} im Kollisionskontext. */
	private boolean shift;
	private int foodLevel = 20;
	private boolean mayfly;
	private boolean blind;
	private boolean usingItem;
	private float itemUseSpeedMultiplier = 0.2F;
	private boolean itemUseCanSprint;
	private int jumpBoostAmplifier = -1;
	private int levitationAmplifier = -1;
	private boolean slowFalling;

	public PlayerPhysics(final CollisionWorld world, final BlockRegistry blocks) {
		this.world = world;
		this.blocks = blocks;
	}

	// ----- M11: Zustand aus Sitzung und Server -----

	public MovementAttributes attributes() {
		return this.attributes;
	}

	public PlayerPose pose() {
		return this.pose;
	}

	public boolean sprinting() {
		return this.sprinting;
	}

	public boolean crouching() {
		return this.crouching;
	}

	public boolean swimming() {
		return this.swimming;
	}

	public boolean shiftKeyDown() {
		return this.shift;
	}

	public float eyeHeight() {
		return this.pose.eyeHeight();
	}

	/** Nahrung (Sprinten nur über 6, {@code FoodData#hasEnoughFood}); {@code mayfly} (Kreativ) erlaubt es immer. */
	public void setFood(final int food, final boolean mayfly) {
		this.foodLevel = food;
		this.mayfly = mayfly;
	}

	/** Effekte mit Wirkung auf die Bewegung (Amplifier, −1 = nicht aktiv). Geschwindigkeit/Langsamkeit wirken über Attribute. */
	public void setEffects(final boolean blindness, final int jumpBoost, final int levitation, final boolean slowFall) {
		this.blind = blindness;
		this.jumpBoostAmplifier = jumpBoost;
		this.levitationAmplifier = levitation;
		this.slowFalling = slowFall;
	}

	/** Item-Benutzung ({@code UseEffects}: Faktor, darf sprinten). */
	public void setItemUse(final boolean using, final float speedMultiplier, final boolean canSprint) {
		this.usingItem = using;
		this.itemUseSpeedMultiplier = speedMultiplier;
		this.itemUseCanSprint = canSprint;
	}

	/** Pose vom Server ({@code set_entity_data} Index 6 für den eigenen Spieler) – Vanilla übernimmt sie, korrigiert aber jeden Tick selbst. */
	public void setPose(final PlayerPose p) {
		this.pose = p;
	}

	/** Sprintzustand setzen ({@code LivingEntity#setSprinting}: Flag und Attribut-Modifikator). */
	public void setSprinting(final boolean s) {
		this.sprinting = s;
		this.attributes.setSprinting(s);
	}

	// ----- Zustand -----

	/** Position setzen (Spawn, Teleport, Serverkorrektur); Geschwindigkeit wie im Paket. */
	public void setPosition(final double nx, final double ny, final double nz, final double nvx, final double nvy, final double nvz) {
		for (double v : new double[]{nx, ny, nz, nvx, nvy, nvz}) {
			if (!Double.isFinite(v)) {
				throw new IllegalArgumentException("non-finite position or velocity");
			}
		}
		this.x = nx;
		this.y = ny;
		this.z = nz;
		this.vx = nvx;
		this.vy = nvy;
		this.vz = nvz;
		this.mainSupport = null;
		this.onGroundNoBlocks = false;
		this.fallDistance = 0;
	}

	/** Nur Geschwindigkeit ersetzen ({@code Entity#lerpMotion}/{@code set_entity_motion}); Position, Stützblock und Fallstrecke bleiben. */
	public void setVelocity(final double nvx, final double nvy, final double nvz) {
		if (!Double.isFinite(nvx) || !Double.isFinite(nvy) || !Double.isFinite(nvz)) {
			throw new IllegalArgumentException("non-finite velocity");
		}
		this.vx = nvx;
		this.vy = nvy;
		this.vz = nvz;
	}

	/** Sprint-Flag aus den Entity-Metadaten des Servers (Index 0 Bit 3): nur das Flag, der Attribut-Modifikator bleibt. */
	public void setSprintFlag(final boolean s) {
		this.sprinting = s;
	}

	public void setYRot(final float yaw) {
		if (!Float.isFinite(yaw)) {
			throw new IllegalArgumentException("non-finite yaw");
		}
		this.yRot = yaw;
	}

	public void setOnGround(final boolean ground) {
		this.onGround = ground;
	}

	public double x() {
		return this.x;
	}

	public double y() {
		return this.y;
	}

	public double z() {
		return this.z;
	}

	public Vec3d velocity() {
		return new Vec3d(this.vx, this.vy, this.vz);
	}

	public float yRot() {
		return this.yRot;
	}

	public boolean onGround() {
		return this.onGround;
	}

	public boolean horizontalCollision() {
		return this.horizontalCollision;
	}

	public boolean verticalCollision() {
		return this.verticalCollision;
	}

	public int noJumpDelay() {
		return this.noJumpDelay;
	}

	public double fallDistance() {
		return this.fallDistance;
	}

	public int[] supportingBlock() {
		return this.mainSupport == null ? null : this.mainSupport.clone();
	}

	public long ticks() {
		return this.tick;
	}

	public CollisionWorld world() {
		return this.world;
	}

	/** Hitbox an der aktuellen Position ({@code EntityDimensions#makeBoundingBox}: halbe Breite als {@code float}). */
	public Aabb boundingBox() {
		return boundingBox(this.x, this.y, this.z);
	}

	public Aabb boundingBox(final double px, final double py, final double pz) {
		return this.pose.box(px, py, pz);
	}

	/** Stehende Hitbox (M5-Schnittstelle). */
	public static Aabb standingBox(final double px, final double py, final double pz) {
		return PlayerPose.STANDING.box(px, py, pz);
	}

	/** Blöcke, deren Form die Hitbox (um 1e−3 vergrößert) berührt – für {@code collision}. */
	public List<CollisionWorld.Hit> contacts() {
		Aabb b = boundingBox();
		Aabb probe = new Aabb(b.minX() - 1.0E-3, b.minY() - 1.0E-3, b.minZ() - 1.0E-3, b.maxX() + 1.0E-3, b.maxY() + 1.0E-3, b.maxZ() + 1.0E-3);
		return this.world.hits(probe, context());
	}

	private CollisionContext context() {
		return new CollisionContext(this.y, this.shift, this.fallDistance);
	}

	/** {@code CollisionGetter#noCollision} (nur Blöcke; ungeladene Chunks liefern wie in Vanilla keine Form). */
	private boolean noCollision(final Aabb box) {
		for (CollisionWorld.Hit h : this.world.hits(box, context())) {
			if (!h.unloaded()) {
				return false;
			}
		}
		return true;
	}

	/** {@code Player#canPlayerFitWithinBlocksAndEntitiesWhen}: Hitbox der Pose an der Position, um 1e−7 verkleinert. */
	public boolean canFit(final PlayerPose p) {
		Aabb b = p.box(this.x, this.y, this.z);
		return noCollision(new Aabb(b.minX() + 1.0E-7, b.minY() + 1.0E-7, b.minZ() + 1.0E-7, b.maxX() - 1.0E-7, b.maxY() - 1.0E-7, b.maxZ() - 1.0E-7));
	}

	// ----- Tick (LivingEntity#aiStep) -----

	public TickResult tick(final Input in) {
		this.tick++;
		this.lastStepped = false;
		this.lastTouchedUnloaded = false;
		// ---- Player#tick: updateIsUnderwater (Augen im Wasser nach dem vorigen Fluid-Update) ----
		this.wasUnderwater = this.eyeInWater;
		// ---- Entity#baseTick: Fluidhöhen, Strömung, Schwimmen (docs/43 §1–§3) ----
		updateFluidInteraction();
		updateSwimming();
		if (inLava()) {
			this.fallDistance *= 0.5;
		}
		this.firstTick = false;
		// ---- LocalPlayer#aiStep vor LivingEntity#aiStep (docs/42 §1) ----
		if (this.sprintTriggerTime > 0) {
			this.sprintTriggerTime--;
		}
		boolean wasShiftKeyDown = this.previousInput.sneak();
		boolean hadForwardImpulse = forwardImpulse(this.previousInput);
		// isCrouching für diesen Tick: Shift des VORIGEN Ticks (vor KeyboardInput#tick), Platz für die Schleichpose
		this.crouching = !this.swimming && canFit(PlayerPose.CROUCHING) && (wasShiftKeyDown || !canFit(PlayerPose.STANDING));
		// KeyboardInput#tick: ab hier gilt der neue Tastenzustand
		this.shift = in.sneak();
		boolean hasForwardImpulse = forwardImpulse(in);
		if (wasShiftKeyDown || slowDueToUsingItem() || in.backward()) {
			this.sprintTriggerTime = 0;
		}
		if (canStartSprinting(hasForwardImpulse)) {
			if (!hadForwardImpulse) {
				if (this.sprintTriggerTime > 0) {
					setSprinting(true);
				} else {
					this.sprintTriggerTime = SPRINT_WINDOW;
				}
			}
			if (in.sprint()) {
				setSprinting(true);
			}
		}
		if (this.sprinting) {
			if (this.swimming) {
				// shouldStopSwimSprinting
				if (!sprintingPossible(true) || !inWater() || !hasForwardImpulse && !this.onGround && !in.sneak()) {
					setSprinting(false);
				}
			} else if (!sprintingPossible(false) || !hasForwardImpulse || this.horizontalCollision && !this.minorHorizontalCollision) {
				setSprinting(false); // shouldStopRunSprinting
			}
		}
		// Abtauchen mit Shift (LocalPlayer#aiStep → goDownInWater)
		if (inWater() && in.sneak()) {
			this.vy += -0.04F;
		}
		// Sichtweite unter Wasser (waterVisionTime, für die Grafik)
		if (this.eyeInWater) {
			this.waterVisionTime = Math.min(600, this.waterVisionTime + 1);
		} else if (this.waterVisionTime > 0) {
			this.waterVisionTime = Math.max(0, this.waterVisionTime - 10);
		}
		// ---- LivingEntity#aiStep ----
		if (this.noJumpDelay > 0) {
			this.noJumpDelay--;
		}
		// kleine Geschwindigkeiten nullen (Spieler: horizontal über die Länge)
		if (this.vx * this.vx + this.vz * this.vz < 9.0E-6) {
			this.vx = 0.0;
			this.vz = 0.0;
		}
		if (Math.abs(this.vy) < 0.003) {
			this.vy = 0.0;
		}
		// LocalPlayer#applyInput
		float[] move = modifiedInput(in, this.usingItem ? this.itemUseSpeedMultiplier : 1.0F,
			movingSlowly() ? (float) this.attributes.value(MovementAttributes.SNEAKING_SPEED) : 1.0F);
		this.xxa = move[0];
		this.zza = move[1];
		this.jumping = in.jump();
		boolean jumped = false;
		if (this.jumping) {
			// LivingEntity#aiStep: Sprung an Land oder im seichten Fluid, sonst Auftrieb (jumpInLiquid +0,04F)
			double fluidHeight = inLava() ? this.lavaHeight : this.waterHeight;
			boolean inWaterAndHasFluidHeight = inWater() && fluidHeight > 0.0;
			double threshold = fluidJumpThreshold();
			if (!inWaterAndHasFluidHeight || this.onGround && !(fluidHeight > threshold)) {
				if (!inLava() || this.onGround && this.lavaHeight <= threshold) {
					if ((this.onGround || inWaterAndHasFluidHeight && fluidHeight <= threshold) && this.noJumpDelay == 0) {
						jumpFromGround();
						this.noJumpDelay = 10;
						jumped = true;
					}
				} else {
					this.vy += 0.04F;
				}
			} else {
				this.vy += 0.04F;
			}
		} else {
			this.noJumpDelay = 0;
		}
		String unsupported = unsupportedAround();
		if (this.slowFalling || this.levitationAmplifier >= 0) {
			this.fallDistance = 0;
		}
		this.movementsThisTick.clear();
		double oldX = this.x;
		double oldY = this.y;
		double oldZ = this.z;
		travel(new Vec3d(this.xxa, 0.0F, this.zza));
		// LivingEntity#aiStep → applyEffectsFromBlocks: stepOn, entityInside (docs/44)
		applyEffectsFromBlocks(oldX, oldY, oldZ);
		this.previousInput = in;
		// ---- Player#tick: updatePlayerPose am Ende des Ticks ----
		updatePlayerPose();
		return new TickResult(this.tick, in, this.x, this.y, this.z, this.vx, this.vy, this.vz, this.onGround, this.horizontalCollision,
			this.verticalCollision, jumped, this.lastStepped, this.lastTouchedUnloaded, unsupported, motionState());
	}

	private float xxa;
	private float zza;
	private boolean jumping;
	// ---- Fluidzustand (EntityFluidInteraction, docs/43 §1) ----
	private FluidModel fluids;
	private double waterHeight;
	private double lavaHeight;
	private boolean eyeInWater;
	private boolean eyeInLava;
	private boolean wasTouchingWater;
	private boolean wasUnderwater;
	private boolean firstTick = true;
	private int waterVisionTime;
	private float xRot;
	private boolean dolphinsGrace;

	public MotionState motionState() {
		return new MotionState(this.pose, this.sprinting, this.crouching, this.shift, this.minorHorizontalCollision,
			this.fallDistance, inWater(), underWater(), this.swimming, this.waterHeight, this.lavaHeight, inLava(), onClimbable());
	}

	public void setXRot(final float pitch) {
		this.xRot = pitch;
	}

	public void setDolphinsGrace(final boolean dolphin) {
		this.dolphinsGrace = dolphin;
	}

	/** Fluidmodell auf derselben Blockwelt wie die Kollision (keine zweite Weltverwaltung). */
	private FluidModel fluids() {
		if (this.fluids == null) {
			this.fluids = new FluidModel(this.world.view(), BlockMovementFacts.get());
		}
		return this.fluids;
	}

	public boolean eyeInWater() {
		return this.eyeInWater;
	}

	public boolean eyeInLava() {
		return this.eyeInLava;
	}

	public double waterHeight() {
		return this.waterHeight;
	}

	public double lavaHeight() {
		return this.lavaHeight;
	}

	/** {@code LocalPlayer#getWaterVision} (0 … 1) für die Sichtweite unter Wasser. */
	public float waterVision() {
		if (!this.eyeInWater) {
			return 0.0F;
		}
		if (this.waterVisionTime >= 600.0F) {
			return 1.0F;
		}
		float a = Math.max(0.0F, Math.min(1.0F, this.waterVisionTime / 100.0F));
		float b = this.waterVisionTime < 100.0F ? 0.0F : Math.max(0.0F, Math.min(1.0F, (this.waterVisionTime - 100.0F) / 500.0F));
		return a * 0.6F + b * 0.39999998F;
	}

	/** {@code Entity#getFluidJumpThreshold}: Augenhöhe &lt; 0,4 → 0, sonst 0,4. */
	private double fluidJumpThreshold() {
		return this.pose.eyeHeight() < 0.4 ? 0.0 : 0.4;
	}

	/**
	 * {@code Entity#updateFluidInteraction} / {@code EntityFluidInteraction#update}: Fluidhöhe über der Hitbox-Unterkante je Fluidart,
	 * Augen im Fluid, Strömung (Mittel der Strömungsvektoren der berührten Zellen, unter 0,4 Höhe mit der Höhe skaliert),
	 * Strömungsimpuls 0,014 (Wasser) bzw. 0,0023333 (Lava, Oberwelt). Ein ungeladener Chunk im Bereich: kein Fluid.
	 */
	private void updateFluidInteraction() {
		this.waterHeight = 0.0;
		this.lavaHeight = 0.0;
		this.eyeInWater = false;
		this.eyeInLava = false;
		Aabb bb = boundingBox();
		Aabb box = new Aabb(bb.minX() + 0.001, bb.minY() + 0.001, bb.minZ() + 0.001, bb.maxX() - 0.001, bb.maxY() - 0.001, bb.maxZ() - 0.001);
		int x0 = (int) Math.floor(box.minX());
		int y0 = (int) Math.floor(box.minY());
		int z0 = (int) Math.floor(box.minZ());
		int x1 = (int) Math.ceil(box.maxX()) - 1;
		int y1 = (int) Math.ceil(box.maxY()) - 1;
		int z1 = (int) Math.ceil(box.maxZ()) - 1;
		FluidModel fm = fluids();
		for (int cx = Math.floorDiv(x0 - 1, 16); cx <= Math.floorDiv(x1 + 1, 16); cx++) {
			for (int cz = Math.floorDiv(z0 - 1, 16); cz <= Math.floorDiv(z1 + 1, 16); cz++) {
				if (fm.stateAt(cx * 16, y0, cz * 16) == BlockView.UNLOADED) {
					this.wasTouchingWater = false;
					return;
				}
			}
		}
		double entityY = bb.minY();
		int eyeBlockX = (int) Math.floor(this.x);
		int eyeBlockZ = (int) Math.floor(this.z);
		double eyeY = this.y + this.pose.eyeHeight();
		double[] currentHeight = new double[3];
		double[] accX = new double[3];
		double[] accY = new double[3];
		double[] accZ = new double[3];
		int[] count = new int[3];
		for (int bx = x0; bx <= x1; bx++) {
			for (int by = y0; by <= y1; by++) {
				for (int bz = z0; bz <= z1; bz++) {
					BlockMovementFacts.Fluid f = fm.fluid(bx, by, bz);
					if (f.isEmpty()) {
						continue;
					}
					double fluidBottom = by;
					double fluidTop = fluidBottom + fm.height(bx, by, bz);
					if (fluidTop < box.minY()) {
						continue;
					}
					int type = f.type();
					if (bx == eyeBlockX && bz == eyeBlockZ && eyeY >= fluidBottom && eyeY <= fluidBottom + fm.heightForCamera(bx, by, bz)) {
						if (type == BlockMovementFacts.FLUID_WATER) {
							this.eyeInWater = true;
						} else {
							this.eyeInLava = true;
						}
					}
					double h;
					if (type == BlockMovementFacts.FLUID_WATER) {
						this.waterHeight = Math.max(fluidTop - entityY, this.waterHeight);
						h = this.waterHeight;
					} else {
						this.lavaHeight = Math.max(fluidTop - entityY, this.lavaHeight);
						h = this.lavaHeight;
					}
					Vec3d flow = fm.flow(bx, by, bz);
					currentHeight[type] = Math.max(h, currentHeight[type]);
					if (currentHeight[type] < 0.4) {
						flow = flow.scale(currentHeight[type]);
					}
					accX[type] += flow.x();
					accY[type] += flow.y();
					accZ[type] += flow.z();
					count[type]++;
				}
			}
		}
		boolean water = this.waterHeight > 0.0;
		if (water) {
			this.fallDistance = 0;
		}
		this.wasTouchingWater = water;
		if (water) {
			applyCurrent(accX[BlockMovementFacts.FLUID_WATER], accY[BlockMovementFacts.FLUID_WATER], accZ[BlockMovementFacts.FLUID_WATER],
				count[BlockMovementFacts.FLUID_WATER], 0.014);
		}
		if (this.lavaHeight > 0.0) {
			applyCurrent(accX[BlockMovementFacts.FLUID_LAVA], accY[BlockMovementFacts.FLUID_LAVA], accZ[BlockMovementFacts.FLUID_LAVA],
				count[BlockMovementFacts.FLUID_LAVA], 0.0023333333333333335);
		}
	}

	/** {@code EntityFluidInteraction.CurrentAccumulator#applyTo} für Spieler: Mittelwert × Faktor, Mindestimpuls 0,0045 bei Stillstand. */
	private void applyCurrent(final double ax, final double ay, final double az, final int n, final double scale) {
		Vec3d acc = new Vec3d(ax, ay, az);
		if (n == 0 || acc.lengthSqr() < 1.0E-5F) {
			return;
		}
		Vec3d impulse = acc.scale(1.0 / n).scale(scale);
		if (Math.abs(this.vx) < 0.003 && Math.abs(this.vz) < 0.003 && impulse.length() < 0.0045000000000000005) {
			impulse = impulse.normalize().scale(0.0045000000000000005);
		}
		this.vx += impulse.x();
		this.vy += impulse.y();
		this.vz += impulse.z();
	}

	/** {@code Entity#updateSwimming}: beginnt sprintend unter Wasser (Block an der Position Wasser), endet ohne Sprint oder Wasser. */
	private void updateSwimming() {
		if (this.swimming) {
			this.swimming = this.sprinting && inWater();
		} else {
			BlockMovementFacts.Fluid here = fluids().fluid((int) Math.floor(this.x), (int) Math.floor(this.y), (int) Math.floor(this.z));
			this.swimming = this.sprinting && underWater() && here.type() == BlockMovementFacts.FLUID_WATER;
		}
	}

	/** {@code LivingEntity#onClimbable}: Block an der Position mit Tag climbable oder offene Falltür über gleich ausgerichteter Leiter. */
	public boolean onClimbable() {
		int bx = (int) Math.floor(this.x);
		int by = (int) Math.floor(this.y);
		int bz = (int) Math.floor(this.z);
		int s = this.world.view().stateAt(bx, by, bz);
		if (s == BlockView.UNLOADED) {
			return false;
		}
		if (BlockMovementFacts.get().climbable(s)) {
			return true;
		}
		var st = this.blocks.state(s);
		if (st.name().endsWith("_trapdoor") && "true".equals(st.properties().get("open"))) {
			int below = this.world.view().stateAt(bx, by - 1, bz);
			if (below != BlockView.UNLOADED) {
				var b = this.blocks.state(below);
				return b.name().equals("minecraft:ladder") && java.util.Objects.equals(b.properties().get("facing"), st.properties().get("facing"));
			}
		}
		return false;
	}

	private boolean inBlock(final String name) {
		int s = this.world.view().stateAt((int) Math.floor(this.x), (int) Math.floor(this.y), (int) Math.floor(this.z));
		return s != BlockView.UNLOADED && this.blocks.state(s).name().equals(name);
	}

	/** {@code LivingEntity#travel} mit {@code Player#travel}: Schwimm-Neigung, dann Fluid- oder Luftbewegung. */
	private void travel(final Vec3d input) {
		if (this.swimming) {
			double lookAngleY = -Trig.sin(this.xRot * DEG_TO_RAD);
			double multiplier = lookAngleY < -0.2 ? 0.085 : 0.06;
			BlockMovementFacts.Fluid above = fluids().fluid((int) Math.floor(this.x), (int) Math.floor(this.y + 1.0 - 0.1), (int) Math.floor(this.z));
			if (lookAngleY <= 0.0 || this.jumping || !above.isEmpty()) {
				this.vy += (lookAngleY - this.vy) * multiplier;
			}
		}
		if (inWater() || inLava()) {
			travelInFluid(input);
		} else {
			travelInAir(input);
		}
	}

	/** {@code LivingEntity#travelInFluid} → {@code #travelInWater} / {@code #travelInLava} (docs/43 §2). */
	private void travelInFluid(final Vec3d input) {
		boolean isFalling = this.vy <= 0.0;
		double oldY = this.y;
		double baseGravity = effectiveGravity();
		if (inWater()) {
			float slowDown = this.sprinting ? 0.9F : 0.8F;
			float speed = 0.02F;
			float waterWalker = (float) this.attributes.value(MovementAttributes.WATER_MOVEMENT_EFFICIENCY);
			if (!this.onGround) {
				waterWalker *= 0.5F;
			}
			if (waterWalker > 0.0F) {
				slowDown += (0.54600006F - slowDown) * waterWalker;
				speed += (movementSpeed() - speed) * waterWalker;
			}
			if (this.dolphinsGrace) {
				slowDown = 0.96F;
			}
			moveRelative(speed, input);
			move(new Vec3d(this.vx, this.vy, this.vz));
			double my = this.vy;
			if (this.horizontalCollision && onClimbable()) {
				my = 0.2;
			}
			this.vx = this.vx * slowDown;
			this.vz = this.vz * slowDown;
			this.vy = fluidFallingAdjustedY(baseGravity, isFalling, my * 0.8F);
		} else {
			moveRelative(0.02F, input);
			move(new Vec3d(this.vx, this.vy, this.vz));
			if (this.lavaHeight <= fluidJumpThreshold()) {
				this.vx = this.vx * 0.5;
				this.vz = this.vz * 0.5;
				this.vy = fluidFallingAdjustedY(baseGravity, isFalling, this.vy * 0.8F);
			} else {
				this.vx = this.vx * 0.5;
				this.vy = this.vy * 0.5;
				this.vz = this.vz * 0.5;
			}
			if (baseGravity != 0.0) {
				this.vy = this.vy + -baseGravity / 4.0;
			}
		}
		// jumpOutOfFluid: an einer Wand, 0,6 über der Ausgangshöhe frei → vy = 0,3F
		if (this.horizontalCollision && isFree(this.vx, this.vy + 0.6F - this.y + oldY, this.vz)) {
			this.vy = 0.3F;
		}
	}

	/** {@code LivingEntity#getFluidFallingAdjustedMovement} (nur y). */
	private double fluidFallingAdjustedY(final double baseGravity, final boolean isFalling, final double yIn) {
		if (baseGravity != 0.0 && !this.sprinting) {
			if (isFalling && Math.abs(yIn - 0.005) >= 0.003 && Math.abs(yIn - baseGravity / 16.0) < 0.003) {
				return -0.003;
			}
			return yIn - baseGravity / 16.0;
		}
		return yIn;
	}

	/** {@code Entity#isFree}: verschobene Hitbox ohne Kollision und ohne Flüssigkeit ({@code LevelReader#containsAnyLiquid}). */
	private boolean isFree(final double dx, final double dy, final double dz) {
		Aabb b = boundingBox().move(dx, dy, dz);
		if (!noCollision(b)) {
			return false;
		}
		FluidModel fm = fluids();
		for (int bx = (int) Math.floor(b.minX()); bx <= (int) Math.floor(b.maxX()); bx++) {
			for (int by = (int) Math.floor(b.minY()); by <= (int) Math.floor(b.maxY()); by++) {
				for (int bz = (int) Math.floor(b.minZ()); bz <= (int) Math.floor(b.maxZ()); bz++) {
					if (!fm.fluid(bx, by, bz).isEmpty()) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** {@code LivingEntity#handleOnClimbable}: horizontal ±0,15F, abwärts höchstens 0,15F; Shift hält die Höhe (nicht im Gerüst). */
	private void handleOnClimbable() {
		if (!onClimbable()) {
			return;
		}
		this.fallDistance = 0;
		this.vx = Math.max(-0.15F, Math.min(0.15F, this.vx));
		this.vz = Math.max(-0.15F, Math.min(0.15F, this.vz));
		double yd = Math.max(this.vy, -0.15F);
		if (yd < 0.0 && !inBlock("minecraft:scaffolding") && this.shift) {
			yd = 0.0;
		}
		this.vy = yd;
	}

	/** {@code ClientInput#hasForwardImpulse}: y des normierten Bewegungsvektors &gt; 1e−5F. */
	private static boolean forwardImpulse(final Input in) {
		float forward = impulse(in.forward(), in.backward());
		float left = impulse(in.left(), in.right());
		float dist = (float) Math.sqrt(left * left + forward * forward);
		return dist >= 1.0E-4F && forward / dist > 1.0E-5F;
	}

	/** {@code LocalPlayer#isMovingSlowly}: Schleichpose (isCrouching) oder Kriechen (Schwimmpose außerhalb von Wasser). */
	private boolean movingSlowly() {
		return this.crouching || this.pose == PlayerPose.SWIMMING && !inWater();
	}

	/** {@code Entity#isInWater} ({@code wasTouchingWater}). */
	public boolean inWater() {
		return this.wasTouchingWater;
	}

	/** {@code LocalPlayer#isUnderWater} ({@code wasUnderwater}: Augen im Wasser zu Tickbeginn). */
	public boolean underWater() {
		return this.wasUnderwater;
	}

	/** {@code Entity#isInLava}: nicht im ersten Tick, Lavahöhe &gt; 0. */
	public boolean inLava() {
		return !this.firstTick && this.lavaHeight > 0.0;
	}

	private boolean slowDueToUsingItem() {
		return this.usingItem && !this.itemUseCanSprint;
	}

	/** {@code LocalPlayer#isSprintingPossible}: keine Blindheit, genug Nahrung (oder Kreativflug erlaubt), ggf. nicht in seichtem Wasser. */
	private boolean sprintingPossible(final boolean allowedInShallowWater) {
		return !this.blind && (this.foodLevel > 6 || this.mayfly) && (allowedInShallowWater || !(inWater() && !underWater()));
	}

	/** {@code LocalPlayer#canStartSprinting} (ohne Elytra/Fliegen). */
	private boolean canStartSprinting(final boolean hasForwardImpulse) {
		return !this.sprinting && hasForwardImpulse && sprintingPossible(false) && !slowDueToUsingItem() && (!movingSlowly() || underWater());
	}

	/** {@code Player#updatePlayerPose}: gewünschte Pose, sonst Schleichen, sonst Schwimmpose; nur wenn die Schwimmpose passt. */
	private void updatePlayerPose() {
		if (!canFit(PlayerPose.SWIMMING)) {
			return;
		}
		PlayerPose desired = this.swimming ? PlayerPose.SWIMMING : this.shift ? PlayerPose.CROUCHING : PlayerPose.STANDING;
		if (canFit(desired)) {
			this.pose = desired;
		} else if (canFit(PlayerPose.CROUCHING)) {
			this.pose = PlayerPose.CROUCHING;
		} else {
			this.pose = PlayerPose.SWIMMING;
		}
	}

	/** {@code LocalPlayer#modifyInput} ohne Item-/Schleichfaktor (M5-Schnittstelle). */
	static float[] modifiedInput(final Input in) {
		return modifiedInput(in, 1.0F, 1.0F);
	}

	/**
	 * {@code KeyboardInput#tick} → {@code Vec2.normalized}, {@code LocalPlayer#modifyInput}: ×0,98F, × Item-Faktor bei Benutzung,
	 * × {@code sneaking_speed} bei langsamer Bewegung, dann Quadrat-Korrektur (je Schritt {@code Vec2#scale} in {@code float}).
	 */
	static float[] modifiedInput(final Input in, final float itemFactor, final float sneakFactor) {
		float forward = impulse(in.forward(), in.backward());
		float left = impulse(in.left(), in.right());
		float dist = (float) Math.sqrt(left * left + forward * forward);
		if (dist < 1.0E-4F) {
			return new float[]{0.0F, 0.0F};
		}
		float ix = left / dist;
		float iy = forward / dist;
		if (ix * ix + iy * iy == 0.0F) {
			return new float[]{ix, iy};
		}
		ix *= INPUT_FRICTION;
		iy *= INPUT_FRICTION;
		if (itemFactor != 1.0F) {
			ix *= itemFactor;
			iy *= itemFactor;
		}
		if (sneakFactor != 1.0F) {
			ix *= sneakFactor;
			iy *= sneakFactor;
		}
		float length = (float) Math.sqrt(ix * ix + iy * iy);
		if (length <= 0.0F) {
			return new float[]{ix, iy};
		}
		float inv = 1.0F / length;
		float dx = ix * inv;
		float dy = iy * inv;
		float ax = Math.abs(dx);
		float ay = Math.abs(dy);
		float tan = ay > ax ? ax / ay : ay / ax;
		float toSquare = (float) Math.sqrt(1.0F + tan * tan);
		float modified = Math.min(length * toSquare, 1.0F);
		return new float[]{dx * modified, dy * modified};
	}

	private static float impulse(final boolean positive, final boolean negative) {
		return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
	}

	/** {@code LivingEntity#jumpFromGround}: Sprungkraft × Blockfaktor + Sprungkraft-Effekt; beim Sprinten 0,2 in Blickrichtung. */
	private void jumpFromGround() {
		float boost = this.jumpBoostAmplifier >= 0 ? 0.1F * (this.jumpBoostAmplifier + 1.0F) : 0.0F;
		float power = (float) this.attributes.value(MovementAttributes.JUMP_STRENGTH) * 1.0F * blockJumpFactor() + boost;
		if (!(power <= 1.0E-5F)) {
			this.vy = Math.max(power, this.vy);
			if (this.sprinting) {
				float angle = this.yRot * DEG_TO_RAD;
				this.vx += -Trig.sin(angle) * 0.2;
				this.vz += Trig.cos(angle) * 0.2;
			}
		}
	}

	/** {@code LivingEntity#travelInAir}. */
	private void travelInAir(final Vec3d input) {
		int[] below = blockPosBelowThatAffectsMyMovement();
		int belowState = this.world.view().stateAt(below[0], below[1], below[2]);
		float blockFriction = this.onGround
			? modifiedFriction(belowState == BlockView.UNLOADED ? ShapeRegistry.DEFAULT_FRICTION : this.world.registry().friction(belowState),
				(float) this.attributes.value(MovementAttributes.FRICTION_MODIFIER))
			: 1.0F;
		// handleRelativeFrictionAndCalculateMovement: Beschleunigung, Klettern begrenzen, bewegen, an Kletterblöcken 0,2 aufwärts
		moveRelative(frictionInfluencedSpeed(blockFriction), input);
		handleOnClimbable();
		move(new Vec3d(this.vx, this.vy, this.vz));
		double movementY = this.vy;
		if ((this.horizontalCollision || this.jumping) && onClimbable()) {
			movementY = 0.2;
		}
		if (this.levitationAmplifier >= 0) {
			movementY += (0.05 * (this.levitationAmplifier + 1) - movementY) * 0.2;
		} else if (belowState != BlockView.UNLOADED) {
			movementY -= effectiveGravity();
		} else if (this.y > -2032) {
			movementY = -0.1; // Vanilla: Chunk unter dem Spieler nicht geladen
		} else {
			movementY = 0.0;
		}
		float airDragModifier = (float) this.attributes.value(MovementAttributes.AIR_DRAG_MODIFIER);
		float airDrag = modifiedFriction(0.91F, airDragModifier);
		float friction = blockFriction * airDrag;
		float verticalFriction = modifiedFriction(0.98F, airDragModifier);
		this.vx = this.vx * friction;
		this.vy = movementY * verticalFriction;
		this.vz = this.vz * friction;
	}

	/** {@code LivingEntity#computeModifiedFriction}. */
	static float modifiedFriction(final float friction, final float modifier) {
		float v = 1.0F - (1.0F - friction) * modifier;
		return v < 0.0F ? 0.0F : Math.min(v, 1.0F);
	}

	/** {@code LivingEntity#getEffectiveGravity}: Attribut {@code gravity}; Sturzflug-Effekt begrenzt beim Fallen auf 0,01. */
	private double effectiveGravity() {
		double g = this.attributes.value(MovementAttributes.GRAVITY);
		return this.vy <= 0.0 && this.slowFalling ? Math.min(g, 0.01) : g;
	}

	/** Testzugang (M10-Schnittstelle): Bewegungsgeschwindigkeit als Grundwert ohne Modifikatoren. */
	public void setMovementSpeed(final float speed) {
		this.attributes.setMovementSpeed(speed);
		this.attributes.setSprinting(this.sprinting);
	}

	/** {@code Player#getSpeed}: (float) Attributwert {@code movement_speed} (inklusive Sprint-Modifikator). */
	public float movementSpeed() {
		return (float) this.attributes.value(MovementAttributes.MOVEMENT_SPEED);
	}

	private float frictionInfluencedSpeed(final float blockFriction) {
		if (this.onGround) {
			float speed = movementSpeed();
			return blockFriction > 0.6 ? speed * (0.21600002F / (blockFriction * blockFriction * blockFriction)) : speed;
		}
		return this.sprinting ? SPRINT_AIR_SPEED : AIR_SPEED;
	}

	private float maxUpStep() {
		return (float) this.attributes.value(MovementAttributes.STEP_HEIGHT);
	}

	/** {@code Entity#moveRelative} / {@code #getInputVector} mit Tabellen-Sinus. */
	private void moveRelative(final float speed, final Vec3d input) {
		double length = input.lengthSqr();
		if (length < 1.0E-7) {
			return;
		}
		Vec3d m = (length > 1.0 ? input.normalize() : input).scale(speed);
		float sin = Trig.sin(this.yRot * DEG_TO_RAD);
		float cos = Trig.cos(this.yRot * DEG_TO_RAD);
		this.vx += m.x() * cos - m.z() * sin;
		this.vy += m.y();
		this.vz += m.z() * cos + m.x() * sin;
	}

	// ----- Entity#move -----

	// ---- M11 Phase E: Sonderblöcke (docs/44) ----
	private double stuckX;
	private double stuckY;
	private double stuckZ;
	/** Bewegungen des Ticks ({@code Entity.Movement}): von x,y,z nach x,y,z mit ursprünglicher Bewegung (für die Achsreihenfolge). */
	private final List<double[]> movementsThisTick = new ArrayList<>();

	/** {@code Entity#makeStuckInBlock}: Fallstrecke 0, Faktor für die nächste Bewegung. */
	private void makeStuckInBlock(final double fx, final double fy, final double fz) {
		this.fallDistance = 0;
		this.stuckX = fx;
		this.stuckY = fy;
		this.stuckZ = fz;
	}

	/** {@code Entity#restituteMovementAfterCollisions}: Abprall horizontal mit {@code bounciness}, vertikal mit dem Prallfaktor des Blocks. */
	private void restituteMovementAfterCollisions(final boolean xCollision, final boolean zCollision, final Vec3d movement, final boolean below) {
		double restitution = this.shift ? 0.0 : this.attributes.value(MovementAttributes.BOUNCINESS);
		double cx = this.vx;
		double cy = this.vy;
		double cz = this.vz;
		if (xCollision) {
			this.vx = -cx * restitution;
		}
		if (zCollision) {
			this.vz = -cz * restitution;
		}
		if (this.verticalCollision) {
			double gravity = effectiveGravity();
			if (below) {
				int[] p = onPos(0.2F);
				int s = this.world.view().stateAt(p[0], p[1], p[2]);
				float blockBounce = s == BlockView.UNLOADED ? 0.0F : BlockMovementFacts.get().bounceRestitution(s);
				boolean suppress = s != BlockView.UNLOADED && BlockMovementFacts.get().suppressesBounce(s);
				restitution = !(-cy <= gravity) && !this.shift && !suppress ? Math.max(restitution, blockBounce) : 0.0;
			}
			double gravityCompensation;
			double effectiveDrag;
			if (restitution > 0.0) {
				double portionWithMovement = movement.y() / cy;
				gravityCompensation = portionWithMovement * gravity;
				float airDrag = modifiedFriction(0.98F, (float) this.attributes.value(MovementAttributes.AIR_DRAG_MODIFIER));
				effectiveDrag = 1.0 + portionWithMovement * (airDrag - 1.0);
			} else {
				gravityCompensation = 0.0;
				effectiveDrag = 1.0;
			}
			this.vy = (gravityCompensation - cy) * effectiveDrag * restitution;
		}
	}

	/** {@code Entity#getOnPos(offset)}: Stützblock (Zäune/Mauern/Zauntore bleiben) oder Blockspalte unter den Füßen. */
	private int[] onPos(final float offset) {
		int by = (int) Math.floor(this.y - offset);
		if (this.mainSupport != null) {
			int s = this.world.view().stateAt(this.mainSupport[0], this.mainSupport[1], this.mainSupport[2]);
			String name = s == BlockView.UNLOADED ? "" : this.blocks.state(s).name();
			boolean fence = offset <= 0.5 && name.endsWith("_fence");
			boolean keep = fence || name.endsWith("_wall") || name.endsWith("_fence_gate");
			return keep ? this.mainSupport.clone() : new int[]{this.mainSupport[0], by, this.mainSupport[2]};
		}
		return new int[]{(int) Math.floor(this.x), by, (int) Math.floor(this.z)};
	}

	/**
	 * {@code Entity#applyEffectsFromBlocks}: am Boden {@code stepOn} des Blocks bei getOnPosLegacy (Schleim), dann
	 * {@code checkInsideBlocks} über alle Bewegungen des Ticks (achsweise, Zellen der um 1e−5F verkleinerten Hitbox entlang
	 * des Wegs, je Zelle einmal) mit {@code entityInside} (Netz, Beerenstrauch, Pulverschnee, Honig, Blasensäule).
	 */
	private void applyEffectsFromBlocks(final double oldX, final double oldY, final double oldZ) {
		if (this.onGround) {
			int[] p = onPos(0.2F);
			int s = this.world.view().stateAt(p[0], p[1], p[2]);
			if (s != BlockView.UNLOADED && this.blocks.state(s).name().equals("minecraft:slime_block")) {
				double absDeltaY = Math.abs(this.vy);
				if (absDeltaY < 0.1 && !this.shift) {
					double scale = 0.4 + absDeltaY * 0.2;
					this.vx *= scale;
					this.vz *= scale;
				}
			}
		}
		List<double[]> moves = new ArrayList<>(this.movementsThisTick);
		if (moves.isEmpty()) {
			moves.add(new double[]{oldX, oldY, oldZ, this.x, this.y, this.z, Double.NaN, 0, 0});
		} else {
			double[] last = moves.getLast();
			double dx = this.x - last[3];
			double dy = this.y - last[4];
			double dz = this.z - last[5];
			if (dx * dx + dy * dy + dz * dz > 9.9999994E-11F) {
				moves.add(new double[]{last[3], last[4], last[5], this.x, this.y, this.z, Double.NaN, 0, 0});
			}
		}
		java.util.Set<Long> visited = new java.util.HashSet<>();
		for (double[] m : moves) {
			int budget = 16;
			if (!Double.isNaN(m[6]) && (m[3] - m[0]) * (m[3] - m[0]) + (m[4] - m[1]) * (m[4] - m[1]) + (m[5] - m[2]) * (m[5] - m[2]) > 0.0) {
				double[] pos = {m[0], m[1], m[2]};
				for (Axis axis : Axis.stepOrder(new Vec3d(m[6], m[7], m[8]))) {
					int i = axis.ordinal();
					double axisMove = m[3 + i] - m[i];
					if (axisMove != 0.0) {
						double[] to = pos.clone();
						to[i] += axisMove;
						budget -= insideBlocksBetween(pos, to, visited, budget);
						pos = to;
					}
				}
			} else {
				budget -= insideBlocksBetween(new double[]{m[0], m[1], m[2]}, new double[]{m[3], m[4], m[5]}, visited, 16);
			}
			if (budget <= 0) {
				insideBlocksBetween(new double[]{m[3], m[4], m[5]}, new double[]{m[3], m[4], m[5]}, visited, 1);
			}
		}
	}

	/**
	 * {@code Entity#checkInsideBlocks(from, to, …)}: Zellen der um 1e−5F verkleinerten Zielhitbox entlang des Wegs in Vanilla-Reihenfolge
	 * ({@link BlockTraversal}); je Zelle und Tick einmal {@code entityInside}, „präzise“ wenn weit bewegt oder die Zielhitbox die Zelle schneidet.
	 * Rückgabe: verbrauchte Iterationen (Budget 16 je Bewegung).
	 */
	private int insideBlocksBetween(final double[] from, final double[] to, final java.util.Set<Long> visited, final int maxIterations) {
		float d = 1.0E-5F;
		Aabb target = this.pose.box(to[0], to[1], to[2]);
		Aabb t = new Aabb(target.minX() + d, target.minY() + d, target.minZ() + d, target.maxX() - d, target.maxY() - d, target.maxZ() - d);
		double ddx = to[0] - from[0];
		double ddy = to[1] - from[1];
		double ddz = to[2] - from[2];
		boolean movedFar = ddx * ddx + ddy * ddy + ddz * ddz > 0.9999900000002526 * 0.9999900000002526;
		int[] used = {0};
		BlockTraversal.forEachBlockIntersectedBetween(new Vec3d(from[0], from[1], from[2]), new Vec3d(to[0], to[1], to[2]), t, (bx, by, bz, iteration) -> {
			if (iteration >= maxIterations) {
				return false;
			}
			used[0] = iteration;
			int s = this.world.view().stateAt(bx, by, bz);
			if (s == BlockView.UNLOADED || s == 0) {
				return true;
			}
			String name = this.blocks.state(s).name();
			if (!isInsideEffectBlock(name)) {
				return true;
			}
			long key = ((long) bx & 0x3FFFFFF) << 38 | ((long) bz & 0x3FFFFFF) << 12 | (by & 0xFFFL);
			if (visited.add(key)) {
				boolean precise = movedFar || t.maxX() > bx && t.minX() < bx + 1 && t.maxY() > by && t.minY() < by + 1 && t.maxZ() > bz && t.minZ() < bz + 1;
				entityInside(name, s, bx, by, bz, precise);
			}
			return true;
		});
		return used[0] + 1;
	}

	private static boolean isInsideEffectBlock(final String name) {
		return switch (name) {
			case "minecraft:cobweb", "minecraft:sweet_berry_bush", "minecraft:powder_snow", "minecraft:honey_block", "minecraft:bubble_column" -> true;
			default -> false;
		};
	}

	/** {@code BlockBehaviour#entityInside} der umgesetzten Blöcke (docs/44 §3–§5). */
	private void entityInside(final String name, final int state, final int bx, final int by, final int bz, final boolean precise) {
		switch (name) {
			case "minecraft:cobweb" -> makeStuckInBlock(0.25, 0.05F, 0.25);
			case "minecraft:sweet_berry_bush" -> makeStuckInBlock(0.8F, 0.75, 0.8F);
			case "minecraft:powder_snow" -> {
				if (inBlock("minecraft:powder_snow")) {
					makeStuckInBlock(0.9F, 1.5, 0.9F);
				}
			}
			case "minecraft:honey_block" -> honeySlide(bx, by, bz);
			case "minecraft:bubble_column" -> {
				if (precise) {
					boolean dragDown = "true".equals(this.blocks.state(state).properties().get("drag"));
					int above = this.world.view().stateAt(bx, by + 1, bz);
					boolean nothingAbove = above != BlockView.UNLOADED && this.world.registry().collisionShape(above, bx, by + 1, bz,
						new CollisionContext(-Double.MAX_VALUE, false, 0.0)).isEmpty() && BlockMovementFacts.get().fluid(above).isEmpty();
					if (nothingAbove) {
						this.vy = dragDown ? Math.max(-0.9, this.vy - 0.03) : Math.min(1.8, this.vy + 0.1);
					} else {
						this.vy = dragDown ? Math.max(-0.3, this.vy - 0.03) : Math.min(0.7, this.vy + 0.06);
						this.fallDistance = 0;
					}
				}
			}
			default -> {
			}
		}
	}

	/** {@code HoneyBlock#isSlidingDown} / {@code #doSlideMovement}. */
	private void honeySlide(final int bx, final int by, final int bz) {
		if (this.onGround || this.y > by + 0.9375 - 1.0E-7) {
			return;
		}
		double oldDeltaY = this.vy / 0.98F + 0.08;
		if (oldDeltaY >= -0.08) {
			return;
		}
		double dx = Math.abs(bx + 0.5 - this.x);
		double dz = Math.abs(bz + 0.5 - this.z);
		double overlap = 0.4375 + PlayerPose.WIDTH / 2.0F;
		if (!(dx + 1.0E-7 > overlap || dz + 1.0E-7 > overlap)) {
			return;
		}
		double newY = (-0.05 - 0.08) * 0.98F;
		if (oldDeltaY < -0.13) {
			double factor = -0.05 / oldDeltaY;
			this.vx = this.vx * factor;
			this.vz = this.vz * factor;
		}
		this.vy = newY;
		this.fallDistance = 0;
	}

	/**
	 * {@code Player#maybeBackOffFromEdge} (docs/42 §4): beim Schleichen (Shift), nicht aufwärts, über dem Boden wird die
	 * horizontale Bewegung in 0,05-Schritten verkürzt, solange sie über eine Kante von mehr als {@code maxUpStep} führen würde.
	 */
	private Vec3d maybeBackOffFromEdge(final Vec3d delta) {
		float maxDownStep = maxUpStep();
		if (delta.y() > 0.0 || !this.shift || !isAboveGround(maxDownStep)) {
			return delta;
		}
		double deltaX = delta.x();
		double deltaZ = delta.z();
		double stepX = Math.signum(deltaX) * 0.05;
		double stepZ = Math.signum(deltaZ) * 0.05;
		while (deltaX != 0.0 && canFallAtLeast(deltaX, 0.0, maxDownStep)) {
			if (Math.abs(deltaX) <= 0.05) {
				deltaX = 0.0;
				break;
			}
			deltaX -= stepX;
		}
		while (deltaZ != 0.0 && canFallAtLeast(0.0, deltaZ, maxDownStep)) {
			if (Math.abs(deltaZ) <= 0.05) {
				deltaZ = 0.0;
				break;
			}
			deltaZ -= stepZ;
		}
		while (deltaX != 0.0 && deltaZ != 0.0 && canFallAtLeast(deltaX, deltaZ, maxDownStep)) {
			if (Math.abs(deltaX) <= 0.05) {
				deltaX = 0.0;
			} else {
				deltaX -= stepX;
			}
			if (Math.abs(deltaZ) <= 0.05) {
				deltaZ = 0.0;
			} else {
				deltaZ -= stepZ;
			}
		}
		return new Vec3d(deltaX, delta.y(), deltaZ);
	}

	private boolean isAboveGround(final float maxDownStep) {
		return this.onGround || this.fallDistance < maxDownStep && !canFallAtLeast(0.0, 0.0, maxDownStep - this.fallDistance);
	}

	private boolean canFallAtLeast(final double deltaX, final double deltaZ, final double minHeight) {
		Aabb b = boundingBox();
		return noCollision(new Aabb(b.minX() + 1.0E-7 + deltaX, b.minY() - minHeight - 1.0E-7, b.minZ() + 1.0E-7 + deltaZ, b.maxX() - 1.0E-7 + deltaX,
			b.minY(), b.maxZ() - 1.0E-7 + deltaZ));
	}

	/** {@code LocalPlayer#isHorizontalCollisionMinor}: Winkel zwischen gewünschter und tatsächlicher Bewegung &lt; 0,13962634F (8°). */
	private boolean horizontalCollisionMinor(final Vec3d movement) {
		float rad = this.yRot * DEG_TO_RAD;
		double sin = Trig.sin(rad);
		double cos = Trig.cos(rad);
		double globalXA = this.xxa * cos - this.zza * sin;
		double globalZA = this.zza * cos + this.xxa * sin;
		double aLengthSquared = globalXA * globalXA + globalZA * globalZA;
		double movementLengthSquared = movement.x() * movement.x() + movement.z() * movement.z();
		if (aLengthSquared < 1.0E-5F || movementLengthSquared < 1.0E-5F) {
			return false;
		}
		double dot = globalXA * movement.x() + globalZA * movement.z();
		return Math.acos(dot / Math.sqrt(aLengthSquared * movementLengthSquared)) < 0.13962634F;
	}

	private void move(final Vec3d requested) {
		Vec3d stuckDelta = requested;
		if (this.stuckX * this.stuckX + this.stuckY * this.stuckY + this.stuckZ * this.stuckZ > 1.0E-7) {
			// Entity#move: in Netz/Strauch/Pulverschnee Bewegung × Faktor, danach Geschwindigkeit 0
			stuckDelta = new Vec3d(requested.x() * this.stuckX, requested.y() * this.stuckY, requested.z() * this.stuckZ);
			this.stuckX = 0.0;
			this.stuckY = 0.0;
			this.stuckZ = 0.0;
			this.vx = 0.0;
			this.vy = 0.0;
			this.vz = 0.0;
		}
		Vec3d delta = maybeBackOffFromEdge(stuckDelta);
		Vec3d movement = collide(delta);
		double movementLength = movement.lengthSqr();
		if (movementLength > 1.0E-7 || delta.lengthSqr() - movementLength < 1.0E-7) {
			this.movementsThisTick.add(new double[]{this.x, this.y, this.z, this.x + movement.x(), this.y + movement.y(), this.z + movement.z(),
				delta.x(), delta.y(), delta.z()});
			this.x += movement.x();
			this.y += movement.y();
			this.z += movement.z();
		}
		boolean xCollision = !equal(delta.x(), movement.x());
		boolean zCollision = !equal(delta.z(), movement.z());
		this.horizontalCollision = xCollision || zCollision;
		// LocalPlayer ist autoritativ: Bodenzustand wird jeden Tick neu bestimmt
		this.verticalCollision = delta.y() != movement.y();
		boolean below = this.verticalCollision && delta.y() < 0.0;
		this.onGround = below;
		checkSupportingBlock(below, movement);
		this.minorHorizontalCollision = this.horizontalCollision && horizontalCollisionMinor(movement);
		// LivingEntity#checkFallDamage: außerhalb von Wasser Fluidzustand nach der Bewegung neu bestimmen (inkl. Strömungsimpuls)
		if (!inWater()) {
			updateFluidInteraction();
		}
		// Fallstrecke (Entity#checkFallDamage): Abwärtsweg als float addieren (nicht im Wasser), am Boden zurücksetzen
		if (!inWater() && movement.y() < 0) {
			this.fallDistance -= (float) movement.y();
		}
		if (this.onGround) {
			this.fallDistance = 0;
		}
		if (delta.y() != 0.0 && this.verticalCollision || this.horizontalCollision) {
			restituteMovementAfterCollisions(xCollision, zCollision, movement, below);
		}
		float speedFactor = blockSpeedFactor();
		this.vx *= speedFactor;
		this.vz *= speedFactor;
	}

	/** {@code Mth#equal}: |b − a| &lt; 1e−5F. */
	private static boolean equal(final double a, final double b) {
		return Math.abs(b - a) < 1.0E-5F;
	}

	/** {@code Entity#collide} mit Step-up (docs/21 §5.2). */
	private Vec3d collide(final Vec3d movement) {
		Aabb aabb = boundingBox();
		CollisionContext ctx = context();
		Vec3d step = movement.lengthSqr() == 0.0 ? movement : collideBoundingBox(movement, aabb, ctx);
		boolean xCollision = movement.x() != step.x();
		boolean yCollision = movement.y() != step.y();
		boolean zCollision = movement.z() != step.z();
		boolean onGroundAfterCollision = yCollision && movement.y() < 0.0;
		float maxStep = maxUpStep();
		if (maxStep > 0.0F && (onGroundAfterCollision || this.onGround) && (xCollision || zCollision)) {
			Aabb grounded = onGroundAfterCollision ? aabb.move(0.0, step.y(), 0.0) : aabb;
			Aabb stepUp = grounded.expandTowards(movement.x(), maxStep, movement.z());
			if (!onGroundAfterCollision) {
				stepUp = stepUp.expandTowards(0.0, -1.0E-5F, 0.0);
			}
			CollisionWorld.Result colliders = this.world.collisions(stepUp, ctx);
			this.lastTouchedUnloaded |= colliders.touchedUnloaded();
			float skip = (float) step.y();
			for (float h : candidateStepUpHeights(grounded, colliders.shapes(), maxStep, skip)) {
				Vec3d fromGround = collideWithShapes(new Vec3d(movement.x(), h, movement.z()), grounded, colliders.shapes());
				if (fromGround.horizontalDistanceSqr() > step.horizontalDistanceSqr()) {
					this.lastStepped = true;
					double distanceToGround = aabb.minY() - grounded.minY();
					return fromGround.subtract(0.0, distanceToGround, 0.0);
				}
			}
		}
		return step;
	}

	/** {@code Entity#collectCandidateStepUpHeights}: Y-Gitter jeder Form relativ zur Unterkante, ≤ maxStep, sortiert. */
	static float[] candidateStepUpHeights(final Aabb box, final List<Shape> colliders, final float maxStep, final float skip) {
		float[] buf = new float[8];
		int n = 0;
		for (Shape s : colliders) {
			for (int i = 0; i < s.yCoordCount(); i++) {
				float rel = (float) (s.yCoord(i) - box.minY());
				if (!(rel < 0.0F) && rel != skip) {
					if (rel > maxStep) {
						break;
					}
					boolean known = false;
					for (int k = 0; k < n; k++) {
						if (buf[k] == rel) {
							known = true;
							break;
						}
					}
					if (!known) {
						if (n == buf.length) {
							buf = java.util.Arrays.copyOf(buf, n * 2);
						}
						buf[n++] = rel;
					}
				}
			}
		}
		float[] out = java.util.Arrays.copyOf(buf, n);
		java.util.Arrays.sort(out);
		return out;
	}

	private Vec3d collideBoundingBox(final Vec3d movement, final Aabb box, final CollisionContext ctx) {
		CollisionWorld.Result r = this.world.collisions(box.expandTowards(movement), ctx);
		this.lastTouchedUnloaded |= r.touchedUnloaded();
		return collideWithShapes(movement, box, r.shapes());
	}

	/** {@code Entity#collideWithShapes}: Achsen in {@link Axis#stepOrder} nacheinander. */
	static Vec3d collideWithShapes(final Vec3d movement, final Aabb box, final List<Shape> shapes) {
		if (shapes.isEmpty()) {
			return movement;
		}
		Vec3d resolved = Vec3d.ZERO;
		for (Axis axis : Axis.stepOrder(movement)) {
			double d = movement.get(axis);
			if (d != 0.0) {
				resolved = resolved.with(axis, collideAxis(axis, box.move(resolved), shapes, d));
			}
		}
		return resolved;
	}

	/** {@code Shapes#collide}. */
	static double collideAxis(final Axis axis, final Aabb moving, final List<Shape> shapes, double distance) {
		for (Shape s : shapes) {
			if (Math.abs(distance) < Aabb.EPSILON) {
				return 0.0;
			}
			distance = s.collide(axis, moving, distance);
		}
		return distance;
	}

	// ----- Stützblock und Blockfaktoren -----

	/** {@code Entity#checkSupportingBlock} / {@code CollisionGetter#findSupportingBlock}. */
	private void checkSupportingBlock(final boolean ground, final Vec3d movement) {
		if (ground) {
			Aabb b = boundingBox();
			Aabb test = new Aabb(b.minX(), b.minY() - 1.0E-6, b.minZ(), b.maxX(), b.minY(), b.maxZ());
			int[] support = findSupportingBlock(test);
			if (support != null || this.onGroundNoBlocks) {
				this.mainSupport = support;
			} else if (movement != null) {
				support = findSupportingBlock(test.move(-movement.x(), 0.0, -movement.z()));
				this.mainSupport = support;
			}
			this.onGroundNoBlocks = support == null;
		} else {
			this.onGroundNoBlocks = false;
			this.mainSupport = null;
		}
	}

	private int[] findSupportingBlock(final Aabb area) {
		int[] best = null;
		double bestDistance = Double.MAX_VALUE;
		for (CollisionWorld.Hit h : this.world.hits(area, context())) {
			if (h.unloaded()) {
				continue;
			}
			double dx = h.x() + 0.5 - this.x;
			double dy = h.y() + 0.5 - this.y;
			double dz = h.z() + 0.5 - this.z;
			double d = dx * dx + dy * dy + dz * dz;
			int[] pos = {h.x(), h.y(), h.z()};
			if (d < bestDistance || d == bestDistance && (best == null || compare(best, pos) < 0)) {
				best = pos;
				bestDistance = d;
			}
		}
		return best;
	}

	/** {@code Vec3i#compareTo}: y, dann z, dann x. */
	private static int compare(final int[] a, final int[] b) {
		if (a[1] != b[1]) {
			return Integer.compare(a[1], b[1]);
		}
		if (a[2] != b[2]) {
			return Integer.compare(a[2], b[2]);
		}
		return Integer.compare(a[0], b[0]);
	}

	/** {@code Entity#getOnPos(0.500001F)}. */
	private int[] blockPosBelowThatAffectsMyMovement() {
		int by = (int) Math.floor(this.y - ON_POS_BELOW);
		if (this.mainSupport != null) {
			int s = this.world.view().stateAt(this.mainSupport[0], this.mainSupport[1], this.mainSupport[2]);
			String name = s == BlockView.UNLOADED ? "" : this.blocks.state(s).name();
			boolean keep = name.endsWith("_wall") || name.endsWith("_fence_gate"); // Tag walls, FenceGateBlock
			return keep ? this.mainSupport.clone() : new int[]{this.mainSupport[0], by, this.mainSupport[2]};
		}
		return new int[]{(int) Math.floor(this.x), by, (int) Math.floor(this.z)};
	}

	private int[] blockPosition() {
		return new int[]{(int) Math.floor(this.x), (int) Math.floor(this.y), (int) Math.floor(this.z)};
	}

	private float factorAt(final int[] p, final boolean jump) {
		int s = this.world.view().stateAt(p[0], p[1], p[2]);
		if (s == BlockView.UNLOADED) {
			return 1.0F;
		}
		return jump ? this.world.registry().jumpFactor(s) : this.world.registry().speedFactor(s);
	}

	/** {@code Entity#getBlockJumpFactor}: Block an der Position, sonst darunter. */
	private float blockJumpFactor() {
		float here = factorAt(blockPosition(), true);
		return here == 1.0 ? factorAt(blockPosBelowThatAffectsMyMovement(), true) : here;
	}

	/** {@code Entity#getBlockSpeedFactor} (Wasser/Blasensäule ausgenommen), {@code MOVEMENT_EFFICIENCY} = 0. */
	private float blockSpeedFactor() {
		int[] p = blockPosition();
		int s = this.world.view().stateAt(p[0], p[1], p[2]);
		String name = s == BlockView.UNLOADED ? "" : this.blocks.state(s).name();
		float here = factorAt(p, false);
		if (name.equals("minecraft:water") || name.equals("minecraft:bubble_column")) {
			return here;
		}
		return here == 1.0 ? factorAt(blockPosBelowThatAffectsMyMovement(), false) : here;
	}

	/** Meldet nicht simulierte Umgebung in der Hitbox (Flüssigkeit, Kletterblock, Spinnennetz …). */
	private String unsupportedAround() {
		Aabb b = boundingBox();
		List<String> found = new ArrayList<>(1);
		for (int bx = (int) Math.floor(b.minX()); bx <= (int) Math.floor(b.maxX()); bx++) {
			for (int by = (int) Math.floor(b.minY()); by <= (int) Math.floor(b.maxY()); by++) {
				for (int bz = (int) Math.floor(b.minZ()); bz <= (int) Math.floor(b.maxZ()); bz++) {
					int s = this.world.view().stateAt(bx, by, bz);
					if (s == BlockView.UNLOADED) {
						continue;
					}
					var st = this.blocks.state(s);
					String n = st.name();
					// M11: Fluide, Klettern und Sonderblöcke sind simuliert (docs/43, docs/44); offen: Pulverschnee mit Lederstiefeln, Endportal
					if (n.equals("minecraft:end_portal") || n.equals("minecraft:end_gateway") || n.equals("minecraft:moving_piston")) {
						if (!found.contains(n)) {
							found.add(n);
						}
					}
				}
			}
		}
		return found.isEmpty() ? null : String.join(",", found);
	}
}
