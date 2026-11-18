package dev.mcresearch.protocolclient.physics;

import dev.mcresearch.protocolclient.world.BlockRegistry;
import java.util.List;

/**
 * M14 Phase I (docs/57 §5): Bewegung eines gerittenen, gesattelten Pferdes auf dem Client des Reiters (autoritativ, Meldung mit
 * {@code move_vehicle}). Eigene Umsetzung von {@code AbstractHorse#tickRidden}/{@code getRiddenInput}/{@code executeRidersJump} und
 * {@code LivingEntity#travelInAir}:
 * <ul>
 * <li>Drehung = Gier des Reiters, Neigung × 0,5;</li>
 * <li>Eingabe: seitlich × 0,5, vorwärts, rückwärts × 0,25 (Tasten × 0,98 wie {@code LivingEntity#aiStep});</li>
 * <li>Boden: Reibung des Blocks × 0,91, Beschleunigung Geschwindigkeit · 0,216/Reibung³; Luft: Geschwindigkeit × 0,1;</li>
 * <li>Schwerkraft 0,08, senkrecht × 0,98; Stufenhöhe aus dem Attribut (Pferd 1,0);</li>
 * <li>Sprung: Ladung 0–100 → Faktor ({@code ≥ 90} → 1, sonst 0,4 + 0,4·n/90), Impuls Sprungkraft·Faktor·Blocksprungfaktor, mit
 * Vorwärtseingabe zusätzlich 0,4·Faktor in Blickrichtung.</li>
 * </ul>
 * Vereinfachung (dokumentiert): im Wasser nur Reibung 0,8 und Auftrieb wie {@code travelFlying}; kein Steigen/Fressen-Stillstand.
 */
public final class HorsePhysics {
	public record TickResult(long tick, double x, double y, double z, double vx, double vy, double vz, float yRot, float xRot, boolean onGround,
		boolean horizontalCollision, boolean jumped) {
	}

	private final CollisionWorld world;
	private final FluidModel fluids;
	private final BlockRegistry blocks;
	private final float width;
	private final float height;
	private double x, y, z, vx, vy, vz;
	private float yRot, xRot;
	private boolean onGround;
	private boolean horizontalCollision;
	private float speed = 0.225F;
	private float jumpStrength = 0.7F;
	private float stepHeight = 1.0F;
	private float pendingJump;
	private long ticks;

	public HorsePhysics(final CollisionWorld world, final BlockRegistry blocks, final float width, final float height) {
		this.world = world;
		this.fluids = new FluidModel(world.view(), BlockMovementFacts.get());
		this.blocks = blocks;
		this.width = width;
		this.height = height;
	}

	public void setPosition(final double nx, final double ny, final double nz, final float nyRot, final boolean ground) {
		this.x = nx;
		this.y = ny;
		this.z = nz;
		this.yRot = nyRot;
		this.onGround = ground;
	}

	public void setVelocity(final double nvx, final double nvy, final double nvz) {
		this.vx = nvx;
		this.vy = nvy;
		this.vz = nvz;
	}

	/** Attribute des Pferdes ({@code update_attributes}: Bewegungsgeschwindigkeit, Sprungkraft, Stufenhöhe). */
	public void setAttributes(final double movementSpeed, final double jump, final double step) {
		this.speed = (float) movementSpeed;
		this.jumpStrength = (float) jump;
		this.stepHeight = (float) step;
	}

	/** {@code AbstractHorse#onPlayerJump}: Ladung 0–100 (aus {@code player_command START_RIDING_JUMP}). */
	public void playerJump(final int amount) {
		int a = Math.max(0, amount);
		this.pendingJump = a >= 90 ? 1.0F : 0.4F + 0.4F * a / 90.0F;
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

	public float xRot() {
		return this.xRot;
	}

	public boolean onGround() {
		return this.onGround;
	}

	public Aabb boundingBox(final double px, final double py, final double pz) {
		double hw = this.width / 2.0F;
		return new Aabb(px - hw, py, pz - hw, px + hw, py + this.height, pz + hw);
	}

	/**
	 * Ein Tick. {@code strafe}/{@code forward}: Tastenachsen des Reiters (−1…1, bereits × 0,98); {@code riderYRot}/{@code riderXRot}:
	 * Blick des Reiters.
	 */
	public TickResult tick(final float strafe, final float forward, final float riderYRot, final float riderXRot) {
		this.ticks++;
		// LivingEntity#aiStep: sehr kleine Geschwindigkeiten verwerfen
		if (Math.abs(this.vx) < 0.003) {
			this.vx = 0;
		}
		if (Math.abs(this.vy) < 0.003) {
			this.vy = 0;
		}
		if (Math.abs(this.vz) < 0.003) {
			this.vz = 0;
		}
		// tickRidden: Drehung vom Reiter, Sprung
		this.yRot = riderYRot;
		this.xRot = riderXRot * 0.5F;
		float side = strafe * 0.5F;
		float fwd = forward <= 0.0F ? forward * 0.25F : forward;
		boolean jumped = false;
		if (this.onGround) {
			if (this.pendingJump > 0.0F) {
				float jbf = blockJumpFactor();
				this.vy = this.jumpStrength * this.pendingJump * jbf;
				if (fwd > 0.0F) {
					float rad = this.yRot * (float) (Math.PI / 180.0);
					this.vx += -0.4F * Trig.sin(rad) * this.pendingJump;
					this.vz += 0.4F * Trig.cos(rad) * this.pendingJump;
				}
				jumped = true;
			}
			this.pendingJump = 0.0F;
		}
		boolean inWater = inWater();
		if (inWater) {
			moveRelative(0.02F, side, fwd);
			move(this.vx, this.vy, this.vz);
			this.vx *= 0.8F;
			this.vy *= 0.8F;
			this.vz *= 0.8F;
			this.vy -= 0.08 / 4.0;
		} else {
			float friction = this.onGround ? this.world.registry().friction(belowState()) : 1.0F;
			float accel = this.onGround ? this.speed * (0.21600002F / (friction * friction * friction)) : this.speed * 0.1F;
			moveRelative(accel, side, fwd);
			move(this.vx, this.vy, this.vz);
			double my = this.vy - 0.08;
			float f = friction * 0.91F;
			this.vx *= f;
			this.vy = my * 0.98F;
			this.vz *= f;
		}
		return new TickResult(this.ticks, this.x, this.y, this.z, this.vx, this.vy, this.vz, this.yRot, this.xRot, this.onGround, this.horizontalCollision,
			jumped);
	}

	private void moveRelative(final float spd, final float side, final float fwd) {
		double len = side * side + fwd * fwd;
		if (len < 1.0E-7) {
			return;
		}
		double ix = side;
		double iz = fwd;
		if (len > 1.0) {
			double l = Math.sqrt(len);
			ix /= l;
			iz /= l;
		}
		ix *= spd;
		iz *= spd;
		float rad = this.yRot * (float) (Math.PI / 180.0);
		float sin = Trig.sin(rad);
		float cos = Trig.cos(rad);
		this.vx += ix * cos - iz * sin;
		this.vz += iz * cos + ix * sin;
	}

	private int belowState() {
		int s = this.world.view().stateAt((int) Math.floor(this.x), (int) Math.floor(this.y - 0.500001F), (int) Math.floor(this.z));
		return s == BlockView.UNLOADED ? 0 : s;
	}

	private float blockJumpFactor() {
		int s = this.world.view().stateAt((int) Math.floor(this.x), (int) Math.floor(this.y), (int) Math.floor(this.z));
		float here = s == BlockView.UNLOADED ? 1.0F : this.world.registry().jumpFactor(s);
		if (here != 1.0F) {
			return here;
		}
		int b = belowState();
		return b == 0 ? 1.0F : this.world.registry().jumpFactor(b);
	}

	private boolean inWater() {
		BlockMovementFacts.Fluid f = this.fluids.fluid((int) Math.floor(this.x), (int) Math.floor(this.y + 0.1), (int) Math.floor(this.z));
		return f.type() == BlockMovementFacts.FLUID_WATER;
	}

	private void move(final double dx, final double dy, final double dz) {
		Vec3d delta = new Vec3d(dx, dy, dz);
		Aabb box = boundingBox(this.x, this.y, this.z);
		CollisionContext ctx = new CollisionContext(box.minY(), dy < 0, 0);
		Vec3d step = delta.lengthSqr() == 0.0 ? delta : PlayerPhysics.collideWithShapes(delta, box, this.world.collisions(box.expandTowards(delta), ctx)
			.shapes());
		boolean xc = delta.x() != step.x();
		boolean yc = delta.y() != step.y();
		boolean zc = delta.z() != step.z();
		boolean groundAfter = yc && delta.y() < 0.0;
		Vec3d movement = step;
		if (this.stepHeight > 0.0F && (groundAfter || this.onGround) && (xc || zc)) {
			Aabb grounded = groundAfter ? box.move(0.0, step.y(), 0.0) : box;
			Aabb stepUp = grounded.expandTowards(delta.x(), this.stepHeight, delta.z());
			if (!groundAfter) {
				stepUp = stepUp.expandTowards(0.0, -1.0E-5F, 0.0);
			}
			List<Shape> colliders = this.world.collisions(stepUp, ctx).shapes();
			for (float h : PlayerPhysics.candidateStepUpHeights(grounded, colliders, this.stepHeight, (float) step.y())) {
				Vec3d fromGround = PlayerPhysics.collideWithShapes(new Vec3d(delta.x(), h, delta.z()), grounded, colliders);
				if (fromGround.horizontalDistanceSqr() > step.horizontalDistanceSqr()) {
					movement = fromGround.subtract(0.0, box.minY() - grounded.minY(), 0.0);
					break;
				}
			}
		}
		if (movement.lengthSqr() > 1.0E-7 || delta.lengthSqr() - movement.lengthSqr() < 1.0E-7) {
			this.x += movement.x();
			this.y += movement.y();
			this.z += movement.z();
		}
		boolean xCollision = Math.abs(movement.x() - delta.x()) >= 1.0E-5F;
		boolean zCollision = Math.abs(movement.z() - delta.z()) >= 1.0E-5F;
		this.horizontalCollision = xCollision || zCollision;
		boolean vertical = delta.y() != movement.y();
		this.onGround = vertical && delta.y() < 0.0;
		if (delta.y() != 0.0 && vertical || this.horizontalCollision) {
			if (xCollision) {
				this.vx = -this.vx * 0.0;
			}
			if (zCollision) {
				this.vz = -this.vz * 0.0;
			}
			if (vertical) {
				this.vy = 0.0; // Lebewesen ohne Prallblock: senkrecht 0 (Schleimprall nicht umgesetzt)
			}
		}
		int s = this.world.view().stateAt((int) Math.floor(this.x), (int) Math.floor(this.y), (int) Math.floor(this.z));
		float sf = s == BlockView.UNLOADED ? 1.0F : this.world.registry().speedFactor(s);
		if (sf == 1.0F && !this.blocks.state(s == BlockView.UNLOADED ? 0 : s).name().equals("minecraft:water")) {
			int b = belowState();
			sf = b == 0 ? 1.0F : this.world.registry().speedFactor(b);
		}
		this.vx *= sf;
		this.vz *= sf;
	}
}
