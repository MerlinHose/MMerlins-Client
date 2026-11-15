package dev.mcresearch.protocolclient.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** M9: Darstellungs-Interpolation (docs/35 §4). */
class EntityInterpolatorTest {
	@Test
	void linearTargetIsReachedAfterInterpolationTicks() {
		EntityInterpolator i = new EntityInterpolator(3);
		i.snap(0, 0, 0, 0, 0);
		i.addTarget(3, 0, 0, 90, 0, 0);
		i.tick();
		assertEquals(1.0, i.x(), 1e-9);
		assertEquals(30.0F, i.yRot(), 1e-4);
		i.tick();
		i.tick();
		assertEquals(3.0, i.x(), 1e-9);
		assertTrue(i.idle());
		// Darstellung zwischen zwei Ticks: vorheriger Tick 2,0, aktueller 3,0
		assertEquals(2.5, i.renderX(0.5F), 1e-9);
		i.tick();
		assertEquals(3.0, i.renderX(0.5F), 1e-9);
	}

	@Test
	void partialTickInterpolatesBetweenTickStates() {
		EntityInterpolator i = new EntityInterpolator(2);
		i.snap(0, 0, 0, 0, 0);
		i.addTarget(2, 0, 0, 0, 0, 0);
		i.tick();
		assertEquals(0.5, i.renderX(0.5F), 1e-9);
	}

	@Test
	void noExtrapolationWithoutNewData() {
		EntityInterpolator i = new EntityInterpolator(3);
		i.snap(0, 0, 0, 0, 0);
		i.addTarget(1, 0, 0, 0, 0, 0);
		for (int t = 0; t < 50; t++) {
			i.tick();
		}
		assertEquals(1.0, i.x(), 0.0);
		assertEquals(1.0, i.renderX(1.0F), 0.0);
	}

	@Test
	void rotationTakesTheShortWayAcross180() {
		EntityInterpolator i = new EntityInterpolator(2);
		i.snap(0, 0, 0, 170, 0);
		i.addTarget(0, 0, 0, -170, 0, 0);
		i.tick();
		// halber Weg über ±180, nicht über 0
		assertEquals(0.0F, EntityInterpolator.wrapDegrees(i.yRot() - 180.0F), 1e-3);
	}

	@Test
	void steppedTargetsUseTheirOwnDurations() {
		EntityInterpolator i = new EntityInterpolator(3);
		i.snap(0, 0, 0, 0, 0);
		i.addTarget(1, 0, 0, 0, 0, 1);
		i.addTarget(3, 0, 0, 0, 0, 2);
		i.tick();
		assertEquals(1.0, i.x(), 1e-9);
		i.tick();
		assertEquals(2.0, i.x(), 1e-9);
		i.tick();
		assertEquals(3.0, i.x(), 1e-9);
	}

	@Test
	void lateUpdatesAreCaughtUpFaster() {
		EntityInterpolator i = new EntityInterpolator(3);
		i.snap(0, 0, 0, 0, 0);
		for (int k = 1; k <= 4; k++) {
			i.addTarget(k, 0, 0, 0, 0, 0); // vier Ziele auf einmal (12 Ticks Rückstand)
		}
		int ticks = 0;
		while (!i.idle()) {
			i.tick();
			ticks++;
		}
		assertTrue(ticks < 12, "backlog caught up faster than real time: " + ticks);
		assertEquals(4.0, i.x(), 1e-9);
		assertTrue(i.catchUps() > 0);
	}

	@Test
	void hugeBacklogSnapsToTheLastTarget() {
		EntityInterpolator i = new EntityInterpolator(3);
		i.snap(0, 0, 0, 0, 0);
		for (int k = 1; k <= 20; k++) {
			i.addTarget(k, 0, 0, 0, 0, 0);
		}
		// beim 14. Ziel überschreitet der Rückstand 40 Ticks (14 · 3 = 42): Sprung dorthin, danach normal weiter
		assertEquals(14.0, i.x(), 0.0);
		assertEquals(2, i.snaps());
		while (!i.idle()) {
			i.tick();
		}
		assertEquals(20.0, i.x(), 1e-9);
	}

	@Test
	void headTurnsIndependentlyOfTheBody() {
		EntityInterpolator i = new EntityInterpolator(3);
		i.snap(0, 0, 0, 0, 0);
		i.snapHead(0);
		i.setHeadTarget(60);
		i.tick();
		assertEquals(20.0F, i.head(), 1e-4);
		assertEquals(0.0F, i.yRot(), 0.0F);
		i.tick();
		i.tick();
		assertEquals(60.0F, i.head(), 1e-4);
	}

	@Test
	void identicalInputGivesIdenticalOutput() {
		double[] a = run();
		double[] b = run();
		assertEquals(a.length, b.length);
		for (int k = 0; k < a.length; k++) {
			assertEquals(a[k], b[k], 0.0);
		}
	}

	private static double[] run() {
		EntityInterpolator i = new EntityInterpolator(3);
		i.snap(1, 2, 3, 10, 0);
		double[] out = new double[60];
		for (int t = 0; t < 60; t++) {
			if (t % 3 == 0) {
				i.addTarget(1 + t * 0.1, 2, 3 - t * 0.05, 10 + t, t % 7, 0);
			}
			if (t % 11 == 0) {
				i.setHeadTarget(t * 4);
			}
			i.tick();
			out[t] = i.x() * 7 + i.z() * 3 + i.yRot() + i.head();
		}
		return out;
	}
}
