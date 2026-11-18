package dev.mcresearch.protocolclient.physics;

import dev.mcresearch.protocolclient.world.BlockRegistry;
import java.util.List;

/**
 * M14 Phase G (docs/57 §4): Bewegung eines selbst gesteuerten Boots, eigene Umsetzung der Regeln von {@code AbstractBoat#tick}
 * auf dem Client des Fahrers (der Client ist für das Boot autoritativ und meldet die Position mit {@code move_vehicle}).
 * <ol>
 * <li>Status ({@code getStatus}): unter (fließendem) Wasser → im Wasser (Wasserstand über der Unterkante) → an Land (mittlere
 * Blockreibung unter der Unterkante, Seerosen ausgenommen) → in der Luft;</li>
 * <li>Fluidströmung ({@code Entity#baseTick}): normierte Strömungssumme × 0,014;</li>
 * <li>{@code floatBoat}: Schwerkraft 0,04, Reibung je Status (Wasser/Luft 0,9, unter Wasser 0,45, an Land Blockreibung, mit Fahrer
 * halbiert für den nächsten Tick), Auftrieb {@code (Wasserstand − y)/Höhe}; Landen aus der Luft auf Wasser setzt das Boot auf den
 * Wasserspiegel;</li>
 * <li>{@code controlBoat}: links/rechts drehen ±1°/Tick (aufsummiert), vorwärts +0,04, rückwärts −0,005, nur drehen +0,005;</li>
 * <li>{@code Entity#move}: Kollision mit Blöcken und Entity-Boxen (ohne Stufensteigen), Geschwindigkeit an kollidierenden Achsen 0,
 * Landen setzt die senkrechte Geschwindigkeit 0, danach Blockgeschwindigkeitsfaktor.</li>
 * </ol>
 * Nicht umgesetzt: Blasensäulen, Schaden/Zerstören beim Aufprall, Mitnahme anderer Entities (serverseitig).
 */
public final class BoatPhysics {
	public enum Status {
		IN_WATER, UNDER_WATER, UNDER_FLOWING_WATER, ON_LAND, IN_AIR
	}

	/** Ergebnis eines Ticks (für Vergleichsspuren und Pakete). */
	public record TickResult(long tick, double x, double y, double z, double vx, double vy, double vz, float yRot, float deltaRotation, Status status,
		boolean paddleLeft, boolean paddleRight, boolean onGround, boolean horizontalCollision) {
	}

	public static final double GRAVITY = 0.04;
	private final CollisionWorld world;
	private final FluidModel fluids;
	private final BlockRegistry blocks;
	private final float width;
	private final float height;
	private double x, y, z, vx, vy, vz;
	private float yRot;
	private float deltaRotation;
	private Status status = Status.IN_AIR;
	private Status oldStatus = Status.IN_AIR;
	private double waterLevel;
	private float landFriction;
	private double lastYd;
	private boolean onGround;
	private boolean horizontalCollision;
	private boolean paddleLeft, paddleRight;
	private final float[] paddlePositions = new float[2];
	private boolean inputLeft, inputRight, inputUp, inputDown;
	private boolean playerDriver = true;
	private long ticks;

	public BoatPhysics(final CollisionWorld world, final BlockRegistry blocks, final float width, final float height) {
		this.world = world;
		this.fluids = new FluidModel(world.view(), BlockMovementFacts.get());
		this.blocks = blocks;
		this.width = width;
		this.height = height;
	}

	public void setPosition(final double nx, final double ny, final double nz, final float nyRot) {
		this.x = nx;
		this.y = ny;
		this.z = nz;
		this.yRot = nyRot;
	}

	public void setVelocity(final double nvx, final double nvy, final double nvz) {
		this.vx = nvx;
		this.vy = nvy;
		this.vz = nvz;
	}

	/** Eingabe für den nächsten Tick ({@code LocalPlayer#rideTick} → {@code AbstractBoat#setInput}). */
	public void setInput(final boolean left, final boolean right, final boolean up, final boolean down) {
		this.inputLeft = left;
		this.inputRight = right;
		this.inputUp = up;
		this.inputDown = down;
	}

	public void setPlayerDriver(final boolean player) {
		this.playerDriver = player;
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

	public float yRot() {
		return this.yRot;
	}

	public float deltaRotation() {
		return this.deltaRotation;
	}

	public boolean onGround() {
		return this.onGround;
	}

	public Status status() {
		return this.status;
	}

	public boolean paddle(final int side) {
		return side == 0 ? this.paddleLeft : this.paddleRight;
	}

	/** Ruderphase für die Darstellung ({@code getRowingTime}). */
	public float rowingTime(final int side, final float partialTick) {
		return paddle(side) ? this.paddlePositions[side] - (float) (Math.PI / 8) * (1.0F - partialTick) : 0.0F;
	}

	public Aabb boundingBox() {
		return boundingBox(this.x, this.y, this.z);
	}

	public Aabb boundingBox(final double px, final double py, final double pz) {
		double hw = this.width / 2.0F;
		return new Aabb(px - hw, py, pz - hw, px + hw, py + this.height, pz + hw);
	}

	/** Ein Tick des selbst gesteuerten Boots. */
	public TickResult tick() {
		this.ticks++;
		this.oldStatus = this.status;
		this.status = computeStatus();
		applyFluidCurrent();
		if (!this.playerDriver) {
			this.paddleLeft = false;
			this.paddleRight = false;
		}
		floatBoat();
		controlBoat();
		move(this.vx, this.vy, this.vz);
		for (int i = 0; i <= 1; i++) {
			if (paddle(i)) {
				this.paddlePositions[i] += (float) (Math.PI / 8);
			} else {
				this.paddlePositions[i] = 0.0F;
			}
		}
		return new TickResult(this.ticks, this.x, this.y, this.z, this.vx, this.vy, this.vz, this.yRot, this.deltaRotation, this.status,
			this.paddleLeft, this.paddleRight, this.onGround, this.horizontalCollision);
	}

	// ---- Status ----

	private Status computeStatus() {
		Status under = underwater();
		if (under != null) {
			this.waterLevel = boundingBox().maxY();
			return under;
		}
		if (checkInWater()) {
			return Status.IN_WATER;
		}
		float friction = groundFriction();
		if (friction > 0.0F) {
			this.landFriction = friction;
			return Status.ON_LAND;
		}
		return Status.IN_AIR;
	}

	private boolean isWater(final BlockMovementFacts.Fluid f) {
		return f.type() == BlockMovementFacts.FLUID_WATER;
	}

	private Status underwater() {
		Aabb bb = boundingBox();
		double maxY = bb.maxY() + 0.001;
		int x0 = floor(bb.minX());
		int x1 = ceil(bb.maxX());
		int y0 = floor(bb.maxY());
		int y1 = ceil(maxY);
		int z0 = floor(bb.minZ());
		int z1 = ceil(bb.maxZ());
		boolean under = false;
		for (int bx = x0; bx < x1; bx++) {
			for (int by = y0; by < y1; by++) {
				for (int bz = z0; bz < z1; bz++) {
					BlockMovementFacts.Fluid f = this.fluids.fluid(bx, by, bz);
					if (isWater(f) && maxY < by + this.fluids.height(bx, by, bz)) {
						if (!f.source()) {
							return Status.UNDER_FLOWING_WATER;
						}
						under = true;
					}
				}
			}
		}
		return under ? Status.UNDER_WATER : null;
	}

	private boolean checkInWater() {
		Aabb bb = boundingBox();
		int x0 = floor(bb.minX());
		int x1 = ceil(bb.maxX());
		int y0 = floor(bb.minY());
		int y1 = ceil(bb.minY() + 0.001);
		int z0 = floor(bb.minZ());
		int z1 = ceil(bb.maxZ());
		boolean in = false;
		this.waterLevel = -Double.MAX_VALUE;
		for (int bx = x0; bx < x1; bx++) {
			for (int by = y0; by < y1; by++) {
				for (int bz = z0; bz < z1; bz++) {
					BlockMovementFacts.Fluid f = this.fluids.fluid(bx, by, bz);
					if (isWater(f)) {
						float h = by + this.fluids.height(bx, by, bz);
						this.waterLevel = Math.max(h, this.waterLevel);
						in |= bb.minY() < h;
					}
				}
			}
		}
		return in;
	}

	/** {@code getGroundFriction}: mittlere Reibung der Blöcke, deren Kollisionsform die Platte unter dem Boot (0,001 hoch) schneidet. */
	float groundFriction() {
		Aabb bb = boundingBox();
		Aabb plate = new Aabb(bb.minX(), bb.minY() - 0.001, bb.minZ(), bb.maxX(), bb.minY(), bb.maxZ());
		int x0 = floor(plate.minX()) - 1;
		int x1 = ceil(plate.maxX()) + 1;
		int y0 = floor(plate.minY()) - 1;
		int y1 = ceil(plate.maxY()) + 1;
		int z0 = floor(plate.minZ()) - 1;
		int z1 = ceil(plate.maxZ()) + 1;
		float friction = 0.0F;
		int count = 0;
		CollisionContext ctx = new CollisionContext(plate.minY(), false, 0);
		for (int bx = x0; bx < x1; bx++) {
			for (int bz = z0; bz < z1; bz++) {
				int edges = (bx != x0 && bx != x1 - 1 ? 0 : 1) + (bz != z0 && bz != z1 - 1 ? 0 : 1);
				if (edges == 2) {
					continue;
				}
				for (int by = y0; by < y1; by++) {
					if (edges > 0 && (by == y0 || by == y1 - 1)) {
						continue;
					}
					int state = this.world.view().stateAt(bx, by, bz);
					if (state == BlockView.UNLOADED || this.blocks.state(state).name().equals("minecraft:lily_pad")) {
						continue;
					}
					Shape s = this.world.registry().collisionShape(state, bx, by, bz, ctx);
					if (!s.isEmpty() && s.move(bx, by, bz).intersects(plate)) {
						friction += this.world.registry().friction(state);
						count++;
					}
				}
			}
		}
		return count == 0 ? 0.0F : friction / count;
	}

	/** {@code getWaterLevelAbove}: erste Höhe über der Oberkante, an der das Wasser nicht mehr den ganzen Block füllt. */
	private float waterLevelAbove() {
		Aabb bb = boundingBox();
		int x0 = floor(bb.minX());
		int x1 = ceil(bb.maxX());
		int y0 = floor(bb.maxY());
		int y1 = ceil(bb.maxY() - this.lastYd);
		int z0 = floor(bb.minZ());
		int z1 = ceil(bb.maxZ());
		int lastY = y0;
		outer:
		for (int by = y0; by < y1; by++) {
			float h = 0.0F;
			lastY = by;
			for (int bx = x0; bx < x1; bx++) {
				for (int bz = z0; bz < z1; bz++) {
					if (isWater(this.fluids.fluid(bx, by, bz))) {
						h = Math.max(h, this.fluids.height(bx, by, bz));
					}
					if (h >= 1.0F) {
						continue outer;
					}
				}
			}
			if (h < 1.0F) {
				return by + h;
			}
		}
		return y1 + 1;
	}

	// ---- Strömung ----

	private void applyFluidCurrent() {
		Aabb bb = boundingBox();
		Aabb box = new Aabb(bb.minX() + 0.001, bb.minY() + 0.001, bb.minZ() + 0.001, bb.maxX() - 0.001, bb.maxY() - 0.001, bb.maxZ() - 0.001);
		int x0 = floor(box.minX());
		int y0 = floor(box.minY());
		int z0 = floor(box.minZ());
		int x1 = ceil(box.maxX()) - 1;
		int y1 = ceil(box.maxY()) - 1;
		int z1 = ceil(box.maxZ()) - 1;
		double height = 0;
		double ax = 0, ay = 0, az = 0;
		int n = 0;
		for (int bx = x0; bx <= x1; bx++) {
			for (int by = y0; by <= y1; by++) {
				for (int bz = z0; bz <= z1; bz++) {
					BlockMovementFacts.Fluid f = this.fluids.fluid(bx, by, bz);
					if (!isWater(f)) {
						continue;
					}
					double top = by + this.fluids.height(bx, by, bz);
					if (top < box.minY()) {
						continue;
					}
					height = Math.max(top - bb.minY(), height);
					Vec3d flow = this.fluids.flow(bx, by, bz);
					if (height < 0.4) {
						flow = flow.scale(height);
					}
					ax += flow.x();
					ay += flow.y();
					az += flow.z();
					n++;
				}
			}
		}
		Vec3d acc = new Vec3d(ax, ay, az);
		if (n == 0 || acc.lengthSqr() < 1.0E-5F) {
			return;
		}
		Vec3d impulse = acc.normalize().scale(0.014);
		if (Math.abs(this.vx) < 0.003 && Math.abs(this.vz) < 0.003 && impulse.length() < 0.0045000000000000005) {
			impulse = impulse.normalize().scale(0.0045000000000000005);
		}
		this.vx += impulse.x();
		this.vy += impulse.y();
		this.vz += impulse.z();
	}

	// ---- floatBoat / controlBoat ----

	private void floatBoat() {
		double vspeed = -GRAVITY;
		double buoyancy = 0.0;
		float invFriction = 0.05F;
		if (this.oldStatus == Status.IN_AIR && this.status != Status.IN_AIR && this.status != Status.ON_LAND) {
			this.waterLevel = this.y + this.height;
			double targetY = waterLevelAbove() - this.height + 0.101;
			if (noCollision(boundingBox(this.x, targetY, this.z))) {
				this.y = targetY;
				this.vy = 0.0;
				this.lastYd = 0.0;
			}
			this.status = Status.IN_WATER;
			return;
		}
		switch (this.status) {
			case IN_WATER -> {
				buoyancy = (this.waterLevel - this.y) / this.height;
				invFriction = 0.9F;
			}
			case UNDER_FLOWING_WATER -> {
				vspeed = -7.0E-4;
				invFriction = 0.9F;
			}
			case UNDER_WATER -> {
				buoyancy = 0.01F;
				invFriction = 0.45F;
			}
			case IN_AIR -> invFriction = 0.9F;
			case ON_LAND -> {
				invFriction = this.landFriction;
				if (this.playerDriver) {
					this.landFriction /= 2.0F;
				}
			}
		}
		this.vx *= invFriction;
		this.vy += vspeed;
		this.vz *= invFriction;
		this.deltaRotation *= invFriction;
		if (buoyancy > 0.0) {
			this.vy = (this.vy + buoyancy * (GRAVITY / 0.65)) * 0.75;
		}
	}

	private void controlBoat() {
		float acceleration = 0.0F;
		if (this.inputLeft) {
			this.deltaRotation--;
		}
		if (this.inputRight) {
			this.deltaRotation++;
		}
		if (this.inputRight != this.inputLeft && !this.inputUp && !this.inputDown) {
			acceleration += 0.005F;
		}
		this.yRot += this.deltaRotation;
		if (this.inputUp) {
			acceleration += 0.04F;
		}
		if (this.inputDown) {
			acceleration -= 0.005F;
		}
		float rad = this.yRot * (float) (Math.PI / 180.0);
		this.vx += Trig.sin(-rad) * acceleration;
		this.vz += Trig.cos(rad) * acceleration;
		this.paddleLeft = this.inputRight && !this.inputLeft || this.inputUp;
		this.paddleRight = this.inputLeft && !this.inputRight || this.inputUp;
	}

	// ---- Bewegung ----

	private boolean noCollision(final Aabb box) {
		return this.world.collisions(box, new CollisionContext(box.minY(), false, 0)).shapes().isEmpty();
	}

	private void move(final double dx, final double dy, final double dz) {
		Vec3d delta = new Vec3d(dx, dy, dz);
		Aabb box = boundingBox();
		Vec3d movement;
		if (delta.lengthSqr() == 0.0) {
			movement = delta;
		} else {
			List<Shape> shapes = this.world.collisions(box.expandTowards(delta), new CollisionContext(box.minY(), dy < 0, 0)).shapes();
			movement = PlayerPhysics.collideWithShapes(delta, box, shapes);
		}
		if (movement.lengthSqr() > 1.0E-7 || delta.lengthSqr() - movement.lengthSqr() < 1.0E-7) {
			this.x += movement.x();
			this.y += movement.y();
			this.z += movement.z();
		}
		boolean xCollision = !equal(delta.x(), movement.x());
		boolean zCollision = !equal(delta.z(), movement.z());
		this.horizontalCollision = xCollision || zCollision;
		boolean verticalCollision = delta.y() != movement.y();
		this.onGround = verticalCollision && delta.y() < 0.0;
		this.lastYd = this.vy; // AbstractBoat#checkFallDamage: Geschwindigkeit vor der Kollisionskorrektur
		if (delta.y() != 0.0 && verticalCollision || this.horizontalCollision) {
			// Entity#restituteMovementAfterCollisions (26.3): Boote haben keine eigene Sprungkraft (0); senkrecht prallen sie nur von
			// Blöcken mit Prallfaktor ab (Schleim), für Nicht-Lebewesen × 0,8
			double cy = this.vy;
			if (xCollision) {
				this.vx = -this.vx * 0.0;
			}
			if (zCollision) {
				this.vz = -this.vz * 0.0;
			}
			if (verticalCollision) {
				double restitution = 0.0;
				if (this.onGround) {
					int s = this.world.view().stateAt(floor(this.x), floor(this.y - 0.2F), floor(this.z));
					float bounce = s == BlockView.UNLOADED ? 0.0F : BlockMovementFacts.get().bounceRestitution(s) * 0.8F;
					boolean suppress = s != BlockView.UNLOADED && BlockMovementFacts.get().suppressesBounce(s);
					restitution = !(-cy <= GRAVITY) && !suppress ? bounce : 0.0;
				}
				if (restitution > 0.0) {
					double portion = movement.y() / cy;
					this.vy = (portion * GRAVITY - cy) * (1.0 + portion * (0.98 - 1.0)) * restitution;
				} else {
					this.vy = (0.0 - cy) * 1.0 * 0.0;
				}
			}
		}
		float speed = speedFactor();
		this.vx *= speed;
		this.vz *= speed;
	}

	/** {@code Entity#getBlockSpeedFactor}: Block an der Position, bei 1 der Block darunter (Seelensand, Honig 0,4). */
	private float speedFactor() {
		int bx = floor(this.x);
		int by = floor(this.y);
		int bz = floor(this.z);
		int s = this.world.view().stateAt(bx, by, bz);
		if (s == BlockView.UNLOADED) {
			return 1.0F;
		}
		String name = this.blocks.state(s).name();
		float here = this.world.registry().speedFactor(s);
		if (name.equals("minecraft:water") || name.equals("minecraft:bubble_column") || here != 1.0F) {
			return here;
		}
		int below = this.world.view().stateAt(bx, floor(this.y - 0.500001F), bz);
		return below == BlockView.UNLOADED ? 1.0F : this.world.registry().speedFactor(below);
	}

	private static boolean equal(final double a, final double b) {
		return Math.abs(b - a) < 1.0E-5F;
	}

	private static int floor(final double v) {
		return (int) Math.floor(v);
	}

	private static int ceil(final double v) {
		return (int) Math.ceil(v);
	}
}
