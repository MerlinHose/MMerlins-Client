package dev.mcresearch.protocolclient.cli;

import dev.mcresearch.protocolclient.physics.Aabb;
import dev.mcresearch.protocolclient.physics.CollisionWorld;
import dev.mcresearch.protocolclient.physics.PlayerPhysics;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Textausgaben der Physikbefehle {@code physics}, {@code collision}, {@code hitbox}, {@code trajectory} (docs/22 §7). */
public final class PhysicsCommands {
	private PhysicsCommands() {
	}

	/** Richtung wie im Terminal: f, b, l, r und Kombinationen (fl, fr, bl, br); optional mit Sprung. */
	public static PlayerPhysics.Input parseDirection(final String dir, final boolean jump) {
		String d = dir.toLowerCase(Locale.ROOT);
		boolean f = false;
		boolean b = false;
		boolean l = false;
		boolean r = false;
		switch (d) {
			case "forward", "f" -> f = true;
			case "back", "backward", "b" -> b = true;
			case "left", "l" -> l = true;
			case "right", "r" -> r = true;
			case "forward-left", "fl" -> {
				f = true;
				l = true;
			}
			case "forward-right", "fr" -> {
				f = true;
				r = true;
			}
			case "back-left", "bl" -> {
				b = true;
				l = true;
			}
			case "back-right", "br" -> {
				b = true;
				r = true;
			}
			case "none", "-" -> {
			}
			default -> throw new IllegalArgumentException("direction must be f|b|l|r|fl|fr|bl|br|none");
		}
		return new PlayerPhysics.Input(f, b, l, r, jump);
	}

	public static List<String> physics(final PlayerPhysics p, final PlayerPhysics.Input lastInput) {
		List<String> out = new ArrayList<>();
		var v = p.velocity();
		out.add(String.format(Locale.ROOT, "pos (%.6f, %.6f, %.6f) yaw %.1f | velocity (%.6f, %.6f, %.6f) horizontal %.4f blocks/tick", p.x(), p.y(),
			p.z(), p.yRot(), v.x(), v.y(), v.z(), Math.hypot(v.x(), v.z())));
		int[] s = p.supportingBlock();
		out.add(String.format(Locale.ROOT, "onGround %s | horizontalCollision %s | verticalCollision %s | noJumpDelay %d | fallDistance %.3f | input %s",
			p.onGround(), p.horizontalCollision(), p.verticalCollision(), p.noJumpDelay(), p.fallDistance(), lastInput));
		out.add("supporting block " + (s == null ? "none" : s[0] + " " + s[1] + " " + s[2]) + " | physics ticks " + p.ticks());
		PlayerPhysics.MotionState ms = p.motionState();
		out.add(String.format(Locale.ROOT, "pose %s | eye %.2f | sprinting %s | crouching %s | swimming %s | inWater %s (height %.4f, under %s) | inLava %s | climbing %s | speed %.6f",
			ms.pose(), p.eyeHeight(), ms.sprinting(), ms.crouching(), ms.swimming(), ms.inWater(), ms.waterHeight(), ms.underWater(), ms.inLava(),
			ms.onClimbable(), p.movementSpeed()));
		return out;
	}

	public static List<String> collision(final PlayerPhysics p, final BlockRegistry blocks) {
		List<String> out = new ArrayList<>();
		List<CollisionWorld.Hit> contacts = p.contacts();
		Aabb box = p.boundingBox();
		out.add(String.format(Locale.ROOT, "onGround %s horizontalCollision %s verticalCollision %s | %d block shapes within 0.001 of the hitbox",
			p.onGround(), p.horizontalCollision(), p.verticalCollision(), contacts.size()));
		for (CollisionWorld.Hit h : contacts) {
			StringBuilder where = new StringBuilder();
			for (Aabb b : h.shape().boxes()) {
				if (Math.abs(b.maxY() - box.minY()) < 1.0E-3 && overlapsXz(b, box)) {
					where.append(" below");
				} else if (Math.abs(b.minY() - box.maxY()) < 1.0E-3 && overlapsXz(b, box)) {
					where.append(" above");
				} else if (Math.abs(b.minX() - box.maxX()) < 1.0E-3 || Math.abs(b.maxX() - box.minX()) < 1.0E-3) {
					where.append(" side-x");
				} else if (Math.abs(b.minZ() - box.maxZ()) < 1.0E-3 || Math.abs(b.maxZ() - box.minZ()) < 1.0E-3) {
					where.append(" side-z");
				}
			}
			String name = h.unloaded() ? "(chunk not loaded → treated as solid)" : blocks.state(stateOf(p, h)).toString();
			out.add(String.format(Locale.ROOT, "  %d %d %d %s boxes=%d%s", h.x(), h.y(), h.z(), name, h.shape().boxes().size(), where));
		}
		return out;
	}

	private static int stateOf(final PlayerPhysics p, final CollisionWorld.Hit h) {
		return p.world().view().stateAt(h.x(), h.y(), h.z());
	}

	private static boolean overlapsXz(final Aabb a, final Aabb b) {
		return a.minX() < b.maxX() && a.maxX() > b.minX() && a.minZ() < b.maxZ() && a.maxZ() > b.minZ();
	}

	public static List<String> hitbox(final PlayerPhysics p) {
		Aabb b = p.boundingBox();
		return List.of(String.format(Locale.ROOT, "hitbox x %.6f..%.6f y %.6f..%.6f z %.6f..%.6f (width %.6f, height %.6f, step height %.1f)",
			b.minX(), b.maxX(), b.minY(), b.maxY(), b.minZ(), b.maxZ(), b.maxX() - b.minX(), b.maxY() - b.minY(), PlayerPhysics.STEP_HEIGHT));
	}

	public static List<String> trajectory(final List<PlayerPhysics.TickResult> ticks, final int n) {
		List<String> out = new ArrayList<>();
		int from = Math.max(0, ticks.size() - n);
		for (int i = from; i < ticks.size(); i++) {
			out.add(ticks.get(i).toString());
		}
		if (out.isEmpty()) {
			out.add("no physics ticks recorded yet");
		}
		return out;
	}
}
