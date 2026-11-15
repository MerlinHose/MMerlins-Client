package dev.mcresearch.protocolclient.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** M12 (docs/48 §7): Partikelsimulation – Abbau 4×4×4, Lebensdauer, Schwerkraft, Bodenkontakt, Kapazitätsgrenze. */
class ParticleSystemTest {
	@Test
	void destroySpawnsAGridOfTerrainParticles() {
		ParticleSystem s = new ParticleSystem();
		s.destroy(10, 64, -5, 1, 0xFFFFFF, null);
		assertEquals(64, s.size(), "4 × 4 × 4 for a full block");
		ParticleSystem.Particle p = s.particles().getFirst();
		assertEquals(ParticleSystem.Kind.TERRAIN, p.kind);
		assertTrue(p.x >= 10 && p.x <= 11 && p.y >= 64 && p.y <= 65);
		assertEquals(0.6f, p.r, 1e-6, "terrain particles are 0.6 × tint");
	}

	@Test
	void particlesFallLandAndExpire() {
		ParticleSystem s = new ParticleSystem();
		s.destroy(0, 64, 0, 1, 0xFFFFFF, null);
		// Boden: alles unter y = 64 ist fest
		for (int i = 0; i < 200 && s.size() > 0; i++) {
			s.tick((x, y, z) -> y < 64);
			for (ParticleSystem.Particle p : s.particles()) {
				assertTrue(p.y >= 63.99, "particles do not fall through the floor");
			}
		}
		assertEquals(0, s.size(), "all particles expire");
	}

	@Test
	void capacityIsBounded() {
		ParticleSystem s = new ParticleSystem();
		for (int i = 0; i < ParticleSystem.MAX / 64 + 10; i++) {
			s.destroy(i, 64, 0, 1, 0xFFFFFF, null);
		}
		assertEquals(ParticleSystem.MAX, s.size());
		assertTrue(s.dropped() > 0);
	}

	@Test
	void critGrowsInSizeAndFlameShrinks() {
		ParticleSystem s = new ParticleSystem();
		ParticleSystem.Particle crit = s.spawn(ParticleSystem.Kind.CRIT, 0, 64, 0, 0.1, 0.2, 0, -1, 0xFFFFFF);
		assertEquals(0f, crit.quadSize(0f), 1e-6, "crit starts at size 0 (age/lifetime·32)");
		ParticleSystem.Particle flame = s.spawn(ParticleSystem.Kind.FLAME, 0, 64, 0, 0, 0, 0, -1, 0xFFFFFF);
		assertTrue(flame.quadSize(flame.lifetime) < flame.quadSize(0), "flame shrinks with age");
		assertTrue(flame.emissive);
	}

	/** Regression (M12-Lauf v2): Tropfen erzeugen beim Ablauf fallende Tropfen – kein Fehler durch Einfügen während der Iteration. */
	@Test
	void drippingTurnsIntoFallingWithoutConcurrentModification() {
		ParticleSystem s = new ParticleSystem();
		s.spawn(ParticleSystem.Kind.DRIPPING_WATER, 0.5, 70, 0.5, 0, 0, 0, -1, 0xFFFFFF);
		boolean falling = false;
		for (int i = 0; i < 60; i++) {
			s.tick((x, y, z) -> y < 64);
			falling |= s.particles().stream().anyMatch(p -> p.kind == ParticleSystem.Kind.FALLING_WATER);
		}
		assertTrue(falling);
	}
}
