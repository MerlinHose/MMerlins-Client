package dev.mcresearch.protocolclient.entity;

/**
 * M14 (docs/55 §4): clientseitige Animationszustände einer Entity, je Client-Tick fortgeschaltet (wie die entsprechenden
 * Felder der Vanilla-Entities, die nur auf dem Client laufen). Eigene Umsetzung der Fortschreibungsregeln:
 * <ul>
 * <li>Laufanimation ({@code WalkAnimationState}): Zielgeschwindigkeit min(4·Weg, 1), Faktor 0,4, Babys Position ×3;</li>
 * <li>Huhn: Flügelschlag ({@code flap}, {@code flapSpeed});</li>
 * <li>Pferde: Steigen und Fressen ({@code standAnim}, {@code eatAnim}) aus den Flaggen 0x20/0x10;</li>
 * <li>Creeper: Aufblähen ({@code swell}, Richtung aus Datenfeld 16, Zündung 18), höchstens 30;</li>
 * <li>Eisengolem: Angriff (Ereignis 4, 10 Ticks) und Blume (Ereignis 11/34);</li>
 * <li>Hase: Sprung (Ereignis 1: 15 Ticks) für die Hüpfanimation;</li>
 * <li>Katze: Liegen ({@code lieDownAmount}) aus Datenfeld 21;</li>
 * <li>Biene: Rollen aus Flagge 0x02.</li>
 * </ul>
 * Nur im Tick-Thread (unter der Sperre des {@link EntityManager}).
 */
public final class EntityAnimationState {
	public float walkSpeedOld, walkSpeed, walkPos, walkPosScale = 1;
	public float flap, flapO, flapSpeed, flapSpeedO, flapping = 1;
	public float standAnim, standAnimO, eatAnim, eatAnimO;
	public int swell, swellO;
	public int attackTicks, offerFlowerTicks;
	public int jumpTicks, jumpDuration;
	/** Hase: Tick, an dem die Hüpfanimation begann (Long.MIN_VALUE = gestoppt). */
	public long hopStartTick = Long.MIN_VALUE;
	public float lieDown, lieDownO, lieDownTail, lieDownTailO;
	public float roll, rollO;
	private double lastX = Double.NaN, lastZ, lastY;

	/** Ein Tick. {@code x,y,z} = Darstellungsposition dieses Ticks; {@code stopWalk} bei Passagieren und toten Entities. */
	void tick(final ClientEntity e, final double x, final double y, final double z, final long tick) {
		double dx = Double.isNaN(this.lastX) ? 0 : x - this.lastX;
		double dz = Double.isNaN(this.lastX) ? 0 : z - this.lastZ;
		double dy = Double.isNaN(this.lastX) ? 0 : y - this.lastY;
		this.lastX = x;
		this.lastY = y;
		this.lastZ = z;
		String type = e.type().shortName();
		boolean flying = type.equals("bee");
		float distance = (float) Math.sqrt(dx * dx + (flying ? dy * dy : 0) + dz * dz);
		Float health = e.health();
		if (e.vehicle() >= 0 || (health != null && health <= 0)) {
			this.walkSpeedOld = 0;
			this.walkSpeed = 0;
			this.walkPos = 0;
		} else {
			float target = Math.min(distance * 4.0F, 1.0F);
			this.walkSpeedOld = this.walkSpeed;
			this.walkSpeed += (target - this.walkSpeed) * 0.4F;
			this.walkPos += this.walkSpeed;
			this.walkPosScale = e.baby() ? 3.0F : 1.0F;
		}
		switch (type) {
			case "chicken" -> {
				this.flapO = this.flap;
				this.flapSpeedO = this.flapSpeed;
				this.flapSpeed = Math.clamp(this.flapSpeed + (e.onGround() ? -1.0F : 4.0F) * 0.3F, 0.0F, 1.0F);
				if (!e.onGround() && this.flapping < 1.0F) {
					this.flapping = 1.0F;
				}
				this.flapping *= 0.9F;
				this.flap += this.flapping * 2.0F;
			}
			case "horse", "donkey", "mule", "skeleton_horse", "zombie_horse" -> {
				int flags = e.data(18) instanceof Byte b ? b & 0xFF : 0;
				this.eatAnimO = this.eatAnim;
				if ((flags & 16) != 0) {
					this.eatAnim = Math.min(1.0F, this.eatAnim + ((1.0F - this.eatAnim) * 0.4F + 0.05F));
				} else {
					this.eatAnim = Math.max(0.0F, this.eatAnim + ((0.0F - this.eatAnim) * 0.4F - 0.05F));
				}
				this.standAnimO = this.standAnim;
				if ((flags & 32) != 0) {
					this.eatAnim = 0.0F;
					this.eatAnimO = 0.0F;
					this.standAnim = Math.min(1.0F, this.standAnim + ((1.0F - this.standAnim) * 0.4F + 0.05F));
				} else {
					this.standAnim = Math.max(0.0F, this.standAnim + ((0.8F * this.standAnim * this.standAnim * this.standAnim - this.standAnim) * 0.6F
						- 0.05F));
				}
			}
			case "creeper" -> {
				this.swellO = this.swell;
				int dir = e.data(16) instanceof Integer i ? i : -1;
				if (Boolean.TRUE.equals(e.data(18))) {
					dir = 1;
				}
				this.swell = Math.clamp(this.swell + dir, 0, 30);
			}
			case "iron_golem" -> {
				if (this.attackTicks > 0) {
					this.attackTicks--;
				}
				if (this.offerFlowerTicks > 0) {
					this.offerFlowerTicks--;
				}
			}
			case "rabbit" -> {
				if (this.jumpTicks != this.jumpDuration) {
					this.jumpTicks++;
				} else if (this.jumpDuration != 0) {
					this.jumpTicks = 0;
					this.jumpDuration = 0;
				}
				if (this.jumpTicks > 0) {
					if (this.hopStartTick == Long.MIN_VALUE) {
						this.hopStartTick = tick;
					}
				} else {
					this.hopStartTick = Long.MIN_VALUE;
				}
			}
			case "cat" -> {
				this.lieDownO = this.lieDown;
				this.lieDownTailO = this.lieDownTail;
				if (Boolean.TRUE.equals(e.data(21))) {
					this.lieDown = Math.min(1.0F, this.lieDown + 0.15F);
					this.lieDownTail = Math.min(1.0F, this.lieDownTail + 0.08F);
				} else {
					this.lieDown = Math.max(0.0F, this.lieDown - 0.22F);
					this.lieDownTail = Math.max(0.0F, this.lieDownTail - 0.13F);
				}
			}
			case "bee" -> {
				this.rollO = this.roll;
				int flags = e.data(18) instanceof Byte b ? b & 0xFF : 0;
				this.roll = (flags & 2) != 0 ? Math.min(1.0F, this.roll + 0.2F) : Math.max(0.0F, this.roll - 0.24F);
			}
			default -> {
			}
		}
	}

	/** Entity-Ereignis ({@code entity_event}) mit Animationswirkung. */
	void event(final ClientEntity e, final int event) {
		switch (e.type().shortName()) {
			case "iron_golem" -> {
				if (event == 4) {
					this.attackTicks = 10;
				} else if (event == 11) {
					this.offerFlowerTicks = 400;
				} else if (event == 34) {
					this.offerFlowerTicks = 0;
				}
			}
			case "rabbit" -> {
				if (event == 1) {
					this.jumpDuration = 15;
					this.jumpTicks = 0;
				}
			}
			default -> {
			}
		}
	}

	/** Unveränderliche Werte bei Teiltick {@code pt} für die Darstellung. */
	public record Snapshot(float walkPos, float walkSpeed, float flap, float flapSpeed, float standAnim, float eatAnim, float swell, float attackTicks,
		int offerFlowerTicks, float hopSeconds, float lieDown, float lieDownTail, float roll) {
		public static final Snapshot NONE = new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0);
	}

	Snapshot snapshot(final float pt, final long tick) {
		float pos = (this.walkPos - this.walkSpeed * (1.0F - pt)) * this.walkPosScale;
		float speed = Math.min(this.walkSpeedOld + (this.walkSpeed - this.walkSpeedOld) * pt, 1.0F);
		float hop = this.hopStartTick == Long.MIN_VALUE ? -1 : (tick - this.hopStartTick + pt) / 20.0F;
		return new Snapshot(pos, speed, lerp(pt, this.flapO, this.flap), lerp(pt, this.flapSpeedO, this.flapSpeed), lerp(pt, this.standAnimO,
			this.standAnim), lerp(pt, this.eatAnimO, this.eatAnim), lerp(pt, this.swellO, this.swell) / 28.0F, this.attackTicks > 0 ? this.attackTicks - pt
			: 0, this.offerFlowerTicks, hop, lerp(pt, this.lieDownO, this.lieDown), lerp(pt, this.lieDownTailO, this.lieDownTail), lerp(pt, this.rollO,
			this.roll));
	}

	private static float lerp(final float t, final float a, final float b) {
		return a + (b - a) * t;
	}
}
