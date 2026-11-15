package dev.mcresearch.protocolclient.cli;

import dev.mcresearch.protocolclient.entity.EntityManager;
import dev.mcresearch.protocolclient.entity.EntityPick;
import dev.mcresearch.protocolclient.session.BlockInteraction;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.PlayerSurvivalState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Terminalbefehle für Kampf, Gesundheit, Nahrung und Tod (M10, docs/41). */
final class CombatCommands {
	private CombatCommands() {
	}

	/** {@code survival}: Zustand des eigenen Spielers in einer Zeile (maschinenlesbar) plus Effekte. */
	static List<String> survival(final ClientSession s) {
		PlayerSurvivalState.View v = s.survival().view(0.0F);
		List<String> out = new ArrayList<>();
		out.add(String.format(Locale.ROOT,
			"SURVIVAL health=%s max=%s absorption=%s food=%d saturation=%s armor=%s toughness=%s attackDamage=%s attackSpeed=%s kbResist=%s dead=%s deaths=%d "
				+ "respawns=%d strength=%.3f using=%s lastDamage=%s damageEvents=%d xp=%d immediateRespawn=%s autoRespawn=%s air=%d moveSpeed=%s physicsSpeed=%s swings=%d",
			v.health(), v.maxHealth(), v.absorption(), v.food(), v.saturation(), v.armor(), v.toughness(), v.attackDamage(), v.attackSpeed(),
			v.knockbackResistance(), v.dead(), v.deaths(), v.respawnsRequested(), v.attackStrength(), v.usingItem(), v.lastDamageType().isEmpty() ? "-" : v.lastDamageType(),
			v.damageEvents(), v.xpLevel(), v.immediateRespawn(), s.autoRespawn(), v.air(),
			s.survival().attribute(PlayerSurvivalState.ATTR_MOVEMENT_SPEED, 0.1), s.movement().physics() == null ? "-" : s.movement().physics().movementSpeed(), v.swings()));
		for (PlayerSurvivalState.Effect e : v.effects()) {
			out.add("EFFECT " + e.id() + " " + e.name() + " amplifier=" + e.amplifier() + " duration=" + e.duration() + " remaining=" + e.remaining()
				+ " flags=" + e.flags());
		}
		if (v.dead()) {
			out.add("DEATH " + v.deathMessage());
		}
		return out;
	}

	/**
	 * {@code hit [stärke]}: wartet (höchstens 3 s), bis die vorhergesagte Angriffsstärke den Wert erreicht, und greift das gewählte Ziel
	 * einmal an. Ausgabe: Ziel, Stärke beim Klick, danach (nach 0,6 s) bestätigte Treffer und Gesundheit des Ziels laut Datenfeld.
	 */
	static List<String> hit(final ClientSession s, final float minStrength) throws InterruptedException {
		List<String> out = new ArrayList<>();
		long t0 = System.nanoTime();
		while (s.survival().attackStrength(0.0F) < minStrength && System.nanoTime() - t0 < 3_000_000_000L) {
			Thread.sleep(5);
		}
		if (minStrength >= 0) {
			Thread.sleep(60); // Auswahl des nächsten Ticks
		}
		BlockInteraction bi = s.interaction();
		EntityPick.Target t = bi.entityTarget();
		float strength = s.survival().attackStrength(0.0F);
		if (t == null) {
			out.add(String.format(Locale.ROOT, "HIT none (no entity targeted) strength=%.3f", strength));
			bi.attackClick();
			Thread.sleep(120);
			return out;
		}
		EntityManager.View before = s.entities().view(t.entity().id(), 1.0F, s.gameMode());
		long sent = bi.attacksSent();
		bi.attackClick();
		long t1 = System.nanoTime();
		while (bi.attacksSent() == sent && System.nanoTime() - t1 < 1_000_000_000L) {
			Thread.sleep(2);
		}
		Thread.sleep(600);
		EntityManager.View after = s.entities().view(t.entity().id(), 1.0F, s.gameMode());
		out.add(String.format(Locale.ROOT, "HIT %d %s %s strength=%.3f dist=%.3f sent=%s confirmed=%d->%d health=%s->%s", t.entity().id(),
			t.entity().type().shortName(), t.entity().uuid(), strength, t.distance(), bi.attacksSent() > sent,
			before == null ? -1 : before.confirmedHits(), after == null ? -1 : after.confirmedHits(), before == null ? "-" : before.health(),
			after == null ? "removed" : after.health()));
		return out;
	}

	/**
	 * {@code hitfall}: volle Angriffsstärke abwarten, springen und im Fallen (y sinkt, nicht am Boden) angreifen – Bedingung für einen
	 * kritischen Treffer (docs/38 §2). Ausgabe wie {@code hit}.
	 */
	static List<String> hitFall(final ClientSession s, final double tx, final double ty, final double tz) throws InterruptedException {
		List<String> out = new ArrayList<>();
		long t0 = System.nanoTime();
		while (s.survival().attackStrength(0.0F) < 1.0F && System.nanoTime() - t0 < 3_000_000_000L) {
			Thread.sleep(5);
		}
		var future = s.movement().jump();
		long t1 = System.nanoTime();
		while (System.nanoTime() - t1 < 1_500_000_000L) {
			aim(s, tx, ty, tz); // Blick folgt dem Ziel, während das Auge steigt und fällt
			var rs = s.movement().renderState();
			if (!rs.onGround() && rs.y() < rs.prevY()) {
				break;
			}
			Thread.sleep(2);
		}
		aim(s, tx, ty, tz);
		Thread.sleep(55); // Auswahl im nächsten Tick mit dem neuen Blick
		BlockInteraction bi = s.interaction();
		EntityPick.Target t = bi.entityTarget();
		long sent = bi.attacksSent();
		var rs = s.movement().renderState();
		bi.attackClick();
		long t2 = System.nanoTime();
		while (bi.attacksSent() == sent && System.nanoTime() - t2 < 1_000_000_000L) {
			Thread.sleep(2);
		}
		try {
			future.get(3, java.util.concurrent.TimeUnit.SECONDS);
		} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
			out.add("! jump did not finish: " + e);
		}
		Thread.sleep(400);
		EntityManager.View after = t == null ? null : s.entities().view(t.entity().id(), 1.0F, s.gameMode());
		out.add(String.format(Locale.ROOT, "HITFALL %s sent=%s falling y=%.4f onGround=%s confirmed=%d health=%s", t == null ? "none" : t.entity().id() + " "
			+ t.entity().type().shortName(), bi.attacksSent() > sent, rs.y(), rs.onGround(), after == null ? -1 : after.confirmedHits(),
			after == null ? "removed" : after.health()));
		return out;
	}

	/** Blickrichtung vom aktuellen Auge auf einen Punkt (wie Terminalbefehl look-at). */
	static void aim(final ClientSession s, final double tx, final double ty, final double tz) {
		var p = s.player();
		double dx = tx - p.x(), dy = ty - (p.y() + (double) 1.62F), dz = tz - p.z();
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		s.movement().look(yaw, Math.max(-90f, Math.min(90f, pitch)));
	}

	/**
	 * {@code hitnext x y z}: auf den Punkt blicken und <b>ohne</b> Warten angreifen (Angriff ohne Abklingzeit, z. B. direkt nach einem
	 * anderen Treffer). Ausgabe wie {@code hit}, mit der vorhergesagten Stärke beim Klick.
	 */
	static List<String> hitNext(final ClientSession s, final double tx, final double ty, final double tz) throws InterruptedException {
		aim(s, tx, ty, tz);
		Thread.sleep(55);
		return hit(s, -1.0F);
	}

	/**
	 * {@code hitpair x1 y1 z1 x2 y2 z2}: erstes Ziel mit voller Stärke treffen, dann sofort (ohne Abklingzeit) das zweite. Ausgabe je Treffer
	 * eine Zeile mit der vorhergesagten Stärke beim Klick.
	 */
	static List<String> hitPair(final ClientSession s, final double[] p) throws InterruptedException {
		List<String> out = new ArrayList<>();
		BlockInteraction bi = s.interaction();
		long t0 = System.nanoTime();
		while (s.survival().attackStrength(0.0F) < 1.0F && System.nanoTime() - t0 < 3_000_000_000L) {
			Thread.sleep(5);
		}
		int[] ids = new int[2];
		float[] strength = new float[2];
		for (int k = 0; k < 2; k++) {
			aim(s, p[k * 3], p[k * 3 + 1], p[k * 3 + 2]);
			Thread.sleep(55);
			EntityPick.Target t = bi.entityTarget();
			ids[k] = t == null ? -1 : t.entity().id();
			strength[k] = s.survival().attackStrength(0.0F);
			long sent = bi.attacksSent();
			bi.attackClick();
			long t1 = System.nanoTime();
			while (bi.attacksSent() == sent && System.nanoTime() - t1 < 1_000_000_000L) {
				Thread.sleep(1);
			}
		}
		Thread.sleep(600);
		for (int k = 0; k < 2; k++) {
			EntityManager.View v = ids[k] < 0 ? null : s.entities().view(ids[k], 1.0F, s.gameMode());
			out.add(String.format(Locale.ROOT, "HITPAIR%d %d strength=%.3f confirmed=%d health=%s", k + 1, ids[k], strength[k], v == null ? -1 : v.confirmedHits(),
				v == null ? "-" : v.health()));
		}
		return out;
	}

	/** {@code eat <ticks>}: Benutzen-Taste so lange halten, dann loslassen (RELEASE_USE_ITEM, falls noch in Benutzung). */
	static List<String> eat(final ClientSession s, final int ticks) throws InterruptedException {
		List<String> out = new ArrayList<>();
		BlockInteraction bi = s.interaction();
		PlayerSurvivalState.View v0 = s.survival().view(0.0F);
		bi.useClick();
		bi.setUseHeld(true);
		try {
			Thread.sleep(ticks * 50L);
		} finally {
			bi.setUseHeld(false);
		}
		Thread.sleep(400);
		PlayerSurvivalState.View v1 = s.survival().view(0.0F);
		out.add(String.format(Locale.ROOT, "EAT held=%d ticks food %d->%d saturation %s->%s using=%s", ticks, v0.food(), v1.food(), v0.saturation(),
			v1.saturation(), v1.usingItem()));
		return out;
	}

	static String respawn(final ClientSession s) {
		try {
			s.requestRespawn();
			return "respawn requested";
		} catch (IOException e) {
			return "! respawn failed: " + e.getMessage();
		}
	}

	static List<String> log(final ClientSession s) {
		List<String> out = new ArrayList<>();
		for (String l : s.survival().log()) {
			out.add("SLOG " + l);
		}
		for (String l : s.interaction().combatLog()) {
			out.add("CLOG " + l);
		}
		return out;
	}
}
