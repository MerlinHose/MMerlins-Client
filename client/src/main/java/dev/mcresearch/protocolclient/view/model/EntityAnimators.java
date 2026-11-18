package dev.mcresearch.protocolclient.view.model;

import dev.mcresearch.protocolclient.entity.ClientEntity;
import dev.mcresearch.protocolclient.entity.EntityAnimationState;
import dev.mcresearch.protocolclient.entity.EntityInterpolator;
import dev.mcresearch.protocolclient.entity.EntityManager;

/**
 * M14 (docs/55 §4): Posenfunktionen je Modellfamilie. Eigene Umsetzung der Animationsregeln der 26.3-Modelle
 * ({@code setupAnim}): Kopf folgt Blick, Beine/Arme schwingen mit Laufphase und -stärke, Sitzen, Steigen, Flügelschlag,
 * Keyframe-Hüpfen usw. Gemeinsame Bausteine (Vierbeiner-Beine, Humanoid) werden von mehreren Typen genutzt; fehlende Teile werden
 * übergangen, damit Erwachsenen- und Baby-Layer sowie Zusatzschichten dieselbe Funktion verwenden können.
 *
 * <p>Ohne LWJGL; reine Funktion von Eingabe → Pose.
 */
public final class EntityAnimators {
	private static final float PI = (float) Math.PI;
	private static final float DEG = PI / 180.0F;

	private EntityAnimators() {
	}

	/** Eingabe einer Pose: Momentaufnahme der Entity und abgeleitete Werte für diesen Frame. */
	public record Input(EntityManager.View v, float ageInTicks, float swimAmount) {
		float headYaw() {
			return EntityInterpolator.wrapDegrees(this.v.rHead() - this.v.rYRot());
		}

		float headPitch() {
			return this.v.rXRot();
		}

		EntityAnimationState.Snapshot anim() {
			return this.v.anim();
		}

		boolean baby() {
			return this.v.baby();
		}

		boolean crouching() {
			return this.v.pose() == 5;
		}

		boolean sprinting() {
			return (this.v.flags() & 0x08) != 0;
		}

		boolean passenger() {
			return this.v.vehicle() >= 0;
		}

		float ageScale() {
			return baby() ? 0.5F : 1.0F;
		}

		int byteData(final int index) {
			return this.v.data().get(index) instanceof Byte b ? b & 0xFF : 0;
		}
	}

	/** Setzt die Pose ({@code reset} + Animation). */
	public static void apply(final String animator, final ModelPose p, final Input in) {
		p.reset();
		switch (animator) {
			case "quadruped", "sheep" -> quadruped(p, in, 1.4F);
			case "chicken" -> chicken(p, in);
			case "wolf" -> wolf(p, in);
			case "feline" -> feline(p, in);
			case "horse", "horse_baby", "donkey", "donkey_baby" -> equine(p, in, animator.endsWith("_baby"));
			case "rabbit", "rabbit_baby" -> rabbit(p, in, animator.equals("rabbit_baby"));
			case "bee" -> bee(p, in);
			case "creeper" -> creeper(p, in);
			case "spider" -> spider(p, in);
			case "enderman" -> enderman(p, in);
			case "villager" -> villager(p, in);
			case "iron_golem" -> ironGolem(p, in);
			case "zombie", "drowned" -> zombie(p, in, animator.equals("drowned"));
			case "skeleton", "player" -> humanoid(p, in);
			case "boat" -> boat(p, in);
			default -> {
			}
		}
	}

	private static float cos(final float v) {
		return (float) Math.cos(v);
	}

	private static float sin(final float v) {
		return (float) Math.sin(v);
	}

	private static void head(final ModelPose p, final Input in) {
		p.setXRot("head", in.headPitch() * DEG);
		p.setYRot("head", in.headYaw() * DEG);
	}

	/** {@code QuadrupedModel}: Kopf, vier Beine diagonal gegenphasig. */
	static void quadruped(final ModelPose p, final Input in, final float amp) {
		head(p, in);
		float pos = in.anim().walkPos();
		float speed = in.anim().walkSpeed();
		p.setXRot("right_hind_leg", cos(pos * 0.6662F) * amp * speed);
		p.setXRot("left_hind_leg", cos(pos * 0.6662F + PI) * amp * speed);
		p.setXRot("right_front_leg", cos(pos * 0.6662F + PI) * amp * speed);
		p.setXRot("left_front_leg", cos(pos * 0.6662F) * amp * speed);
	}

	private static void chicken(final ModelPose p, final Input in) {
		float flap = (sin(in.anim().flap()) + 1.0F) * in.anim().flapSpeed();
		float pos = in.anim().walkPos();
		float speed = in.anim().walkSpeed();
		p.setXRot("right_leg", cos(pos * 0.6662F) * 1.4F * speed);
		p.setXRot("left_leg", cos(pos * 0.6662F + PI) * 1.4F * speed);
		p.setZRot("right_wing", flap);
		p.setZRot("left_wing", -flap);
		if (!in.baby()) {
			head(p, in);
		}
	}

	private static void wolf(final ModelPose p, final Input in) {
		float pos = in.anim().walkPos();
		float speed = in.anim().walkSpeed();
		int flags = in.byteData(18);
		boolean sitting = (flags & 0x01) != 0;
		boolean tame = (flags & 0x04) != 0;
		boolean angry = in.v().data().get(22) instanceof Long l && l > 0;
		p.setYRot("tail", angry ? 0 : cos(pos * 0.6662F) * 1.4F * speed);
		if (sitting) {
			float s = in.ageScale();
			p.addPos("body", 0, 4 * s, -2 * s);
			p.setXRot("body", PI / 4);
			p.addPos("tail", 0, 9 * s, -2 * s);
			p.addPos("right_hind_leg", 0, 6.7F * s, -5 * s);
			p.setXRot("right_hind_leg", PI * 3 / 2);
			p.addPos("left_hind_leg", 0, 6.7F * s, -5 * s);
			p.setXRot("left_hind_leg", PI * 3 / 2);
			p.setXRot("right_front_leg", 5.811947F);
			p.addPos("right_front_leg", 0.01F * s, s, 0);
			p.setXRot("left_front_leg", 5.811947F);
			p.addPos("left_front_leg", -0.01F * s, s, 0);
			if (in.baby()) {
				p.addXRot("body", -PI / 2);
			} else {
				p.addPos("upper_body", 0, 2, 0);
				p.setXRot("upper_body", PI * 2 / 5);
				p.setYRot("upper_body", 0);
			}
		} else {
			p.setXRot("right_hind_leg", cos(pos * 0.6662F) * 1.4F * speed);
			p.setXRot("left_hind_leg", cos(pos * 0.6662F + PI) * 1.4F * speed);
			p.setXRot("right_front_leg", cos(pos * 0.6662F + PI) * 1.4F * speed);
			p.setXRot("left_front_leg", cos(pos * 0.6662F) * 1.4F * speed);
		}
		head(p, in);
		// Schwanzwinkel (Wolf#getTailAngle): wütend fest, zahm nach Gesundheit, sonst π/5
		float tail;
		if (angry) {
			tail = 1.5393804F;
		} else if (tame) {
			Float h = in.v().health();
			Double max = in.v().attributes().get(ClientEntity.ATTRIBUTE_MAX_HEALTH);
			float missing = (float) ((max == null ? 40.0 : max) - (h == null ? 40.0F : h));
			tail = (0.55F - missing * 0.02F) * PI;
		} else {
			tail = PI / 5;
		}
		p.setXRot("tail", tail);
	}

	private static void feline(final ModelPose p, final Input in) {
		float s = in.ageScale();
		boolean crouching = in.crouching();
		boolean sprinting = in.sprinting();
		boolean sitting = (in.byteData(18) & 0x01) != 0;
		boolean baby = in.baby();
		if (crouching) {
			p.addPos("body", 0, s, 0);
			p.addPos("head", 0, 2 * s, 0);
			p.addPos("tail1", 0, s, 0);
			p.addPos("tail2", 0, -4 * s, 2 * s);
			p.setXRot("tail1", PI / 2);
			p.setXRot("tail2", PI / 2);
		} else if (sprinting) {
			int t1 = p.part("tail1");
			int t2 = p.part("tail2");
			if (t1 >= 0 && t2 >= 0) {
				p.y[t2] = p.y[t1];
			}
			p.addPos("tail2", 0, 0, 2 * s);
			p.setXRot("tail1", PI / 2);
			p.setXRot("tail2", PI / 2);
		}
		head(p, in);
		if (!sitting) {
			if (!baby) {
				p.setXRot("body", PI / 2);
			}
			float speed = in.anim().walkSpeed();
			float pos = in.anim().walkPos();
			if (sprinting) {
				p.setXRot("left_hind_leg", cos(pos * 0.6662F) * speed);
				p.setXRot("right_hind_leg", cos(pos * 0.6662F + 0.3F) * speed);
				p.setXRot("left_front_leg", cos(pos * 0.6662F + PI + 0.3F) * speed);
				p.setXRot("right_front_leg", cos(pos * 0.6662F + PI) * speed);
				p.setXRot("tail2", 1.7278761F + PI / 10 * cos(pos) * speed);
			} else {
				p.setXRot("left_hind_leg", cos(pos * 0.6662F) * speed);
				p.setXRot("right_hind_leg", cos(pos * 0.6662F + PI) * speed);
				p.setXRot("left_front_leg", cos(pos * 0.6662F + PI) * speed);
				p.setXRot("right_front_leg", cos(pos * 0.6662F) * speed);
				p.setXRot("tail2", 1.7278761F + (crouching ? 0.47123894F : PI / 4) * cos(pos) * speed);
			}
		} else if (!baby) {
			p.setXRot("body", PI / 4);
			p.addPos("body", 0, -4 * s, 5 * s);
			p.addPos("head", 0, -3.3F * s, s);
			p.addPos("tail1", 0, 8 * s, -2 * s);
			p.addPos("tail2", 0, 2 * s, -0.8F * s);
			p.setXRot("tail1", 1.7278761F);
			p.setXRot("tail2", 2.670354F);
			p.setXRot("left_front_leg", -PI / 20);
			p.addPos("left_front_leg", 0, 2 * s, -2 * s);
			p.setXRot("right_front_leg", -PI / 20);
			p.addPos("right_front_leg", 0, 2 * s, -2 * s);
			p.setXRot("left_hind_leg", -PI / 2);
			p.addPos("left_hind_leg", 0, 3 * s, -4 * s);
			p.setXRot("right_hind_leg", -PI / 2);
			p.addPos("right_hind_leg", 0, 3 * s, -4 * s);
		} else {
			p.addXRot("body", -0.43633232F);
			p.addPos("body", 0, 1.25F, 0);
			p.addPos("head", 0, 0, 0.75F);
			p.addXRot("tail1", 0.5454154F);
			p.addPos("tail1", 0, 4, -0.9F);
			p.addPos("left_hind_leg", 0, 0, -0.9F);
			p.addPos("right_hind_leg", 0, 0, -0.9F);
		}
		float lie = in.anim().lieDown();
		if (lie > 0 && !baby) {
			p.setZRot("head", rotLerp(lie, p.zRot("head"), -1.2707963F));
			p.setYRot("head", rotLerp(lie, p.yRot("head"), 1.2707963F));
			p.setXRot("left_front_leg", -1.2707963F);
			p.setXRot("right_front_leg", -0.47079635F);
			p.setZRot("right_front_leg", -0.2F);
			p.addPos("right_front_leg", s, 0, 0);
			p.setXRot("left_hind_leg", -0.4F);
			p.setXRot("right_hind_leg", 0.5F);
			p.setZRot("right_hind_leg", -0.5F);
			p.addPos("right_hind_leg", 0.8F * s, 2 * s, 0);
			float tail = in.anim().lieDownTail();
			p.setXRot("tail1", rotLerp(tail, p.xRot("tail1"), 0.8F));
			p.setXRot("tail2", rotLerp(tail, p.xRot("tail2"), -0.4F));
		}
	}

	private static float rotLerp(final float a, final float from, final float to) {
		float d = to - from;
		while (d < -PI) {
			d += 2 * PI;
		}
		while (d >= PI) {
			d -= 2 * PI;
		}
		return from + a * d;
	}

	/** {@code AbstractEquineModel}: Kopfgruppe, Steigen, Fressen, Beine, Schwanz (Baby: eigene Versätze). */
	private static void equine(final ModelPose p, final Input in, final boolean baby) {
		float yaw = Math.clamp(in.headYaw(), -20.0F, 20.0F);
		float pitch = in.headPitch() * DEG;
		float speed = in.anim().walkSpeed();
		float pos = in.anim().walkPos();
		if (speed > 0.2F) {
			pitch += cos(pos * 0.8F) * 0.15F * speed;
		}
		float eating = in.anim().eatAnim();
		float standing = in.anim().standAnim();
		float iStanding = 1.0F - standing;
		int hp = p.part("head_parts");
		float baseHead = (1.0F - Math.max(standing, eating)) * (PI / 6 + pitch);
		if (hp >= 0) {
			p.xRot[hp] = standing * (PI / 12 + pitch) + eating * (2.1816616F + sin(in.ageInTicks()) * 0.05F) + baseHead;
			p.yRot[hp] = standing * yaw * DEG + (1.0F - Math.max(standing, eating)) * yaw * DEG;
			float dy = baby ? lerp(eating, lerp(standing, 0, -2), 2) : lerp(eating, lerp(standing, 0, -8), 7);
			p.y[hp] += dy;
			p.z[hp] = lerp(standing, p.z[hp], -4.0F);
		}
		int body = p.part("body");
		if (body >= 0) {
			p.xRot[body] = standing * (-PI / 4) + iStanding * p.xRot[body];
		}
		float legStandY = baby ? 4.0F : 12.0F;
		float legStandZ = baby ? 0.0F : 4.0F;
		int lf = p.part("left_front_leg");
		int rf = p.part("right_front_leg");
		if (lf >= 0 && rf >= 0) {
			p.y[lf] -= legStandY * standing;
			p.z[lf] += legStandZ * standing;
			p.y[rf] = p.y[lf];
			p.z[rf] = p.z[lf];
		}
		float water = 1.0F;
		float legAnim = cos(water * pos * 0.6662F + PI);
		float legX = legAnim * 0.8F * speed;
		float standAngle = PI / 12 * standing;
		float bob = cos(in.ageInTicks() * 0.6F + PI);
		float standX = -PI / 3;
		p.setXRot("left_hind_leg", standAngle - legAnim * 0.5F * speed * iStanding);
		p.setXRot("right_hind_leg", standAngle + legAnim * 0.5F * speed * iStanding);
		p.setXRot("left_front_leg", (standX + bob) * standing + legX * iStanding);
		p.setXRot("right_front_leg", (standX - bob) * standing - legX * iStanding);
		float s = in.ageScale();
		int tail = p.part("tail");
		if (tail >= 0) {
			p.xRot[tail] = (baby ? -PI / 2 : 0) + PI / 6 + speed * 0.75F;
			p.y[tail] += speed * s;
			p.z[tail] += speed * 2.0F * s;
			p.yRot[tail] = 0;
		}
		// Esel/Maultier: Truhen nur mit Truhe sichtbar (Datenfeld 19)
		boolean chest = Boolean.TRUE.equals(in.v().data().get(19)) && !in.v().type().shortName().equals("horse");
		p.setVisible("left_chest", chest);
		p.setVisible("right_chest", chest);
	}

	private static float lerp(final float t, final float a, final float b) {
		return a + (b - a) * t;
	}

	private static void rabbit(final ModelPose p, final Input in, final boolean baby) {
		head(p, in);
		float hop = in.anim().hopSeconds();
		if (hop >= 0) {
			KeyframeLibrary.get().apply(baby ? "BabyRabbitAnimation.HOP" : "RabbitAnimation.HOP", p, hop);
		}
	}

	private static void bee(final ModelPose p, final Input in) {
		boolean onGround = in.v().onGround();
		int flags = in.byteData(18);
		p.setVisible("stinger", (flags & 0x04) == 0);
		float age = in.ageInTicks();
		if (!onGround) {
			float speed = age * 120.32113F * DEG;
			p.setYRot("right_wing", 0);
			p.setZRot("right_wing", cos(speed) * PI * 0.15F);
			p.setXRot("left_wing", p.xRot("right_wing"));
			p.setYRot("left_wing", 0);
			p.setZRot("left_wing", -p.zRot("right_wing"));
			p.setXRot("front_legs", PI / 4);
			p.setXRot("middle_legs", PI / 4);
			p.setXRot("back_legs", PI / 4);
		}
		boolean angry = in.v().data().get(19) instanceof Long l && l > 0;
		if (!angry && !onGround) {
			float c = cos(age * 0.18F);
			p.setXRot("bone", 0.1F + c * PI * 0.025F);
			p.addPos("bone", 0, -cos(age * 0.18F) * 0.9F, 0);
			p.setXRot("front_legs", -c * PI * 0.1F + PI / 8);
			p.setXRot("back_legs", -c * PI * 0.05F + PI / 4);
			if (!in.baby()) {
				p.setXRot("left_antenna", c * PI * 0.03F);
				p.setXRot("right_antenna", c * PI * 0.03F);
			}
		}
		float roll = in.anim().roll();
		if (roll > 0) {
			p.setXRot("bone", rotLerp(roll, p.xRot("bone"), 3.0915928F));
		}
	}

	private static void creeper(final ModelPose p, final Input in) {
		quadruped(p, in, 1.4F);
	}

	private static void spider(final ModelPose p, final Input in) {
		head(p, in);
		float pos = in.anim().walkPos() * 0.6662F;
		float speed = in.anim().walkSpeed();
		float[] swing = {-(cos(pos * 2) * 0.4F) * speed, -(cos(pos * 2 + PI) * 0.4F) * speed, -(cos(pos * 2 + PI / 2) * 0.4F) * speed,
			-(cos(pos * 2 + PI * 3 / 2) * 0.4F) * speed};
		float[] step = {Math.abs(sin(pos) * 0.4F) * speed, Math.abs(sin(pos + PI) * 0.4F) * speed, Math.abs(sin(pos + PI / 2) * 0.4F) * speed,
			Math.abs(sin(pos + PI * 3 / 2) * 0.4F) * speed};
		String[] legs = {"hind_leg", "middle_hind_leg", "middle_front_leg", "front_leg"};
		for (int i = 0; i < 4; i++) {
			p.addYRot("right_" + legs[i], swing[i]);
			p.addYRot("left_" + legs[i], -swing[i]);
			p.addZRot("right_" + legs[i], step[i]);
			p.addZRot("left_" + legs[i], -step[i]);
		}
	}

	private static void enderman(final ModelPose p, final Input in) {
		humanoid(p, in);
		for (String n : new String[]{"right_arm", "left_arm", "right_leg", "left_leg"}) {
			p.setXRot(n, Math.clamp(p.xRot(n) * 0.5F, -0.4F, 0.4F));
		}
		if (in.v().data().get(16) instanceof Integer id && id > 0) {
			p.setXRot("right_arm", -0.5F);
			p.setXRot("left_arm", -0.5F);
			p.setZRot("right_arm", 0.05F);
			p.setZRot("left_arm", -0.05F);
		}
		if (Boolean.TRUE.equals(in.v().data().get(17))) {
			p.addPos("head", 0, -5, 0);
			p.addPos("hat", 0, 5, 0);
		}
	}

	private static void villager(final ModelPose p, final Input in) {
		head(p, in);
		boolean unhappy = in.v().data().get(18) instanceof Integer c && c > 0;
		if (unhappy) {
			p.setZRot("head", 0.3F * sin(0.45F * in.ageInTicks()));
			p.setXRot("head", 0.4F);
		}
		float pos = in.anim().walkPos();
		float speed = in.anim().walkSpeed();
		p.setXRot("right_leg", cos(pos * 0.6662F) * 1.4F * speed * 0.5F);
		p.setXRot("left_leg", cos(pos * 0.6662F + PI) * 1.4F * speed * 0.5F);
		p.setYRot("right_leg", 0);
		p.setYRot("left_leg", 0);
	}

	private static float triangleWave(final float index, final float period) {
		return (Math.abs(index % period - period * 0.5F) - period * 0.25F) / (period * 0.25F);
	}

	private static void ironGolem(final ModelPose p, final Input in) {
		float attack = in.anim().attackTicks();
		float speed = in.anim().walkSpeed();
		float pos = in.anim().walkPos();
		if (attack > 0) {
			p.setXRot("right_arm", -2.0F + 1.5F * triangleWave(attack, 10.0F));
			p.setXRot("left_arm", -2.0F + 1.5F * triangleWave(attack, 10.0F));
		} else if (in.anim().offerFlowerTicks() > 0) {
			p.setXRot("right_arm", -0.8F + 0.025F * triangleWave(in.anim().offerFlowerTicks(), 70.0F));
			p.setXRot("left_arm", 0);
		} else {
			p.setXRot("right_arm", (-0.2F + 1.5F * triangleWave(pos, 13.0F)) * speed);
			p.setXRot("left_arm", (-0.2F - 1.5F * triangleWave(pos, 13.0F)) * speed);
		}
		head(p, in);
		p.setXRot("right_leg", -1.5F * triangleWave(pos, 13.0F) * speed);
		p.setXRot("left_leg", 1.5F * triangleWave(pos, 13.0F) * speed);
		p.setYRot("right_leg", 0);
		p.setYRot("left_leg", 0);
	}

	private static void zombie(final ModelPose p, final Input in, final boolean drowned) {
		humanoid(p, in);
		boolean aggressive = (in.byteData(15) & 0x04) != 0;
		boolean baby = in.baby();
		boolean holding = !in.v().equipment()[ClientEntity.SLOT_MAINHAND].isEmpty();
		boolean raise = !baby || !holding;
		float swing = in.v().swingProgress();
		float drop = raise ? -PI / (aggressive ? 1.5F : 2.25F) : 0.0F;
		float attackY = (raise ? 1.0F : -1.0F) * sin(swing * PI);
		float attackX = sin((1.0F - (1.0F - swing) * (1.0F - swing)) * PI);
		float xRot = drop + attackY * 1.2F - attackX * 0.4F;
		float yRot = 0.1F - attackY * 0.6F;
		p.setRot("right_arm", xRot, raise ? -yRot : yRot, 0);
		p.setRot("left_arm", xRot, raise ? yRot : -yRot, 0);
		if (drowned && in.swimAmount() > 0) {
			float sa = in.swimAmount();
			float age = in.ageInTicks();
			p.setXRot("right_arm", rotLerp(sa, p.xRot("right_arm"), -PI * 4 / 5) + sa * 0.35F * sin(0.1F * age));
			p.setXRot("left_arm", rotLerp(sa, p.xRot("left_arm"), -PI * 4 / 5) - sa * 0.35F * sin(0.1F * age));
			p.setZRot("right_arm", rotLerp(sa, p.zRot("right_arm"), -0.15F));
			p.setZRot("left_arm", rotLerp(sa, p.zRot("left_arm"), 0.15F));
			p.addXRot("left_leg", -sa * 0.55F * sin(0.1F * age));
			p.addXRot("right_leg", sa * 0.55F * sin(0.1F * age));
			p.setXRot("head", 0);
		}
	}

	/** {@code HumanoidModel}: Kopf, Gehen, Passagierhaltung, Gegenstand in der Hand, Schlag, Schleichen, Armwippen, Schwimmen. */
	static void humanoid(final ModelPose p, final Input in) {
		head(p, in);
		float pos = in.anim().walkPos();
		float speed = in.anim().walkSpeed();
		p.setXRot("right_arm", cos(pos * 0.6662F + PI) * 2.0F * speed * 0.5F);
		p.setXRot("left_arm", cos(pos * 0.6662F) * 2.0F * speed * 0.5F);
		p.setXRot("right_leg", cos(pos * 0.6662F) * 1.4F * speed);
		p.setXRot("left_leg", cos(pos * 0.6662F + PI) * 1.4F * speed);
		p.setYRot("right_leg", 0.005F);
		p.setYRot("left_leg", -0.005F);
		p.setZRot("right_leg", 0.005F);
		p.setZRot("left_leg", -0.005F);
		if (in.passenger()) {
			p.addXRot("right_arm", -PI / 5);
			p.addXRot("left_arm", -PI / 5);
			p.setRot("right_leg", -1.4137167F, PI / 10, 0.07853982F);
			p.setRot("left_leg", -1.4137167F, -PI / 10, -0.07853982F);
		}
		// Gegenstand in der Hand: Haltung ITEM (rechte Hand = Haupthand bei rechtshändigen Wesen)
		boolean leftHanded = in.v().type().shortName().equals("player") && Integer.valueOf(0).equals(in.v().data().get(15));
		boolean main = !in.v().equipment()[ClientEntity.SLOT_MAINHAND].isEmpty();
		boolean off = !in.v().equipment()[ClientEntity.SLOT_OFFHAND].isEmpty();
		String mainArm = leftHanded ? "left_arm" : "right_arm";
		String offArm = leftHanded ? "right_arm" : "left_arm";
		if (main) {
			p.setXRot(mainArm, p.xRot(mainArm) * 0.5F - PI / 10);
		}
		p.setYRot(mainArm, 0);
		if (off) {
			p.setXRot(offArm, p.xRot(offArm) * 0.5F - PI / 10);
		}
		p.setYRot(offArm, 0);
		// Schlag (WHACK)
		float swing = in.v().swingProgress();
		if (swing > 0) {
			String arm = (in.v().swingHand() == 1) != leftHanded ? "left_arm" : "right_arm";
			float bodyY = sin((float) Math.sqrt(swing) * PI * 2) * 0.2F;
			if (arm.equals("left_arm")) {
				bodyY = -bodyY;
			}
			p.setYRot("body", bodyY);
			float s = in.ageScale();
			int ra = p.part("right_arm");
			int la = p.part("left_arm");
			if (ra >= 0 && la >= 0) {
				p.z[ra] = sin(bodyY) * 5.0F * s;
				p.x[ra] = -cos(bodyY) * 5.0F * s;
				p.z[la] = -sin(bodyY) * 5.0F * s;
				p.x[la] = cos(bodyY) * 5.0F * s;
			}
			p.addYRot("right_arm", bodyY);
			p.addYRot("left_arm", bodyY);
			p.addXRot("left_arm", bodyY);
			float aa = sin((1.0F - (1.0F - swing) * (1.0F - swing) * (1.0F - swing) * (1.0F - swing)) * PI);
			float bb = sin(swing * PI) * -(p.xRot("head") - 0.7F) * 0.75F;
			p.addXRot(arm, -(aa * 1.2F + bb));
			p.addYRot(arm, bodyY * 2.0F);
			p.addZRot(arm, sin(swing * PI) * -0.4F);
		}
		if (in.crouching()) {
			p.setXRot("body", 0.5F);
			p.addXRot("right_arm", 0.4F);
			p.addXRot("left_arm", 0.4F);
			p.addPos("right_leg", 0, 0, 4.0F);
			p.addPos("left_leg", 0, 0, 4.0F);
			p.addPos("head", 0, 4.2F, 0);
			p.addPos("body", 0, 3.2F, 0);
			p.addPos("left_arm", 0, 3.2F, 0);
			p.addPos("right_arm", 0, 3.2F, 0);
		}
		float age = in.ageInTicks();
		p.addZRot("right_arm", cos(age * 0.09F) * 0.05F + 0.05F);
		p.addXRot("right_arm", sin(age * 0.067F) * 0.05F);
		p.addZRot("left_arm", -(cos(age * 0.09F) * 0.05F + 0.05F));
		p.addXRot("left_arm", -(sin(age * 0.067F) * 0.05F));
		float sa = in.swimAmount();
		if (sa > 0) {
			swim(p, in, pos, sa);
		}
	}

	private static float quadraticArmUpdate(final float x) {
		return -65.0F * x + x * x;
	}

	private static void swim(final ModelPose p, final Input in, final float pos, final float sa) {
		p.setXRot("head", rotLerp(sa, p.xRot("head"), -PI / 4));
		float sp = pos % 26.0F;
		if (sp < 14.0F) {
			p.setXRot("left_arm", rotLerp(sa, p.xRot("left_arm"), 0));
			p.setXRot("right_arm", lerp(sa, p.xRot("right_arm"), 0));
			p.setYRot("left_arm", rotLerp(sa, p.yRot("left_arm"), PI));
			p.setYRot("right_arm", lerp(sa, p.yRot("right_arm"), PI));
			p.setZRot("left_arm", rotLerp(sa, p.zRot("left_arm"), PI + 1.8707964F * quadraticArmUpdate(sp) / quadraticArmUpdate(14.0F)));
			p.setZRot("right_arm", lerp(sa, p.zRot("right_arm"), PI - 1.8707964F * quadraticArmUpdate(sp) / quadraticArmUpdate(14.0F)));
		} else if (sp < 22.0F) {
			float t = (sp - 14.0F) / 8.0F;
			p.setXRot("left_arm", rotLerp(sa, p.xRot("left_arm"), PI / 2 * t));
			p.setXRot("right_arm", lerp(sa, p.xRot("right_arm"), PI / 2 * t));
			p.setYRot("left_arm", rotLerp(sa, p.yRot("left_arm"), PI));
			p.setYRot("right_arm", lerp(sa, p.yRot("right_arm"), PI));
			p.setZRot("left_arm", rotLerp(sa, p.zRot("left_arm"), 5.012389F - 1.8707964F * t));
			p.setZRot("right_arm", lerp(sa, p.zRot("right_arm"), 1.2707963F + 1.8707964F * t));
		} else {
			float t = (sp - 22.0F) / 4.0F;
			p.setXRot("left_arm", rotLerp(sa, p.xRot("left_arm"), PI / 2 - PI / 2 * t));
			p.setXRot("right_arm", lerp(sa, p.xRot("right_arm"), PI / 2 - PI / 2 * t));
			p.setYRot("left_arm", rotLerp(sa, p.yRot("left_arm"), PI));
			p.setYRot("right_arm", lerp(sa, p.yRot("right_arm"), PI));
			p.setZRot("left_arm", rotLerp(sa, p.zRot("left_arm"), PI));
			p.setZRot("right_arm", lerp(sa, p.zRot("right_arm"), PI));
		}
		p.setXRot("left_leg", lerp(sa, p.xRot("left_leg"), 0.3F * cos(pos * 0.33333334F + PI)));
		p.setXRot("right_leg", lerp(sa, p.xRot("right_leg"), 0.3F * cos(pos * 0.33333334F)));
	}

	/** Boot: Paddel aus den Datenfeldern 11/12 (rudern links/rechts); Phase aus dem Alter wie {@code AbstractBoat#getRowingTime}. */
	private static void boat(final ModelPose p, final Input in) {
		boolean left = Boolean.TRUE.equals(in.v().data().get(11));
		boolean right = Boolean.TRUE.equals(in.v().data().get(12));
		float l = left ? in.ageInTicks() * (PI / 8) : 0;
		float r = right ? in.ageInTicks() * (PI / 8) : 0;
		paddle(p, "left_paddle", l, false);
		paddle(p, "right_paddle", r, true);
	}

	static void paddle(final ModelPose p, final String name, final float time, final boolean right) {
		int i = p.part(name);
		if (i < 0) {
			return;
		}
		p.xRot[i] = clampedLerp((sin(-time) + 1.0F) / 2.0F, -PI / 3, -PI / 12);
		p.yRot[i] = clampedLerp((sin(-time + 1.0F) + 1.0F) / 2.0F, -PI / 4, PI / 4);
		if (right) {
			p.yRot[i] = PI - p.yRot[i];
		}
	}

	private static float clampedLerp(final float t, final float a, final float b) {
		return t < 0 ? a : t > 1 ? b : lerp(t, a, b);
	}
}
