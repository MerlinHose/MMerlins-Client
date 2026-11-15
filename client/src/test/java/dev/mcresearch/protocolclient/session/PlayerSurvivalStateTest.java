package dev.mcresearch.protocolclient.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** M10: Überlebenszustand (docs/40). */
class PlayerSurvivalStateTest {
	@Test
	void attackStrengthFollowsTheAttackSpeedAttribute() {
		PlayerSurvivalState s = new PlayerSurvivalState();
		s.onAttributes(Map.of(PlayerSurvivalState.ATTR_ATTACK_SPEED, 1.6)); // Schwert: Verzögerung 12,5 Ticks
		assertEquals(12.5F, s.attackDelay(), 1e-4);
		s.resetAttackStrength();
		assertEquals(0.0F, s.attackStrength(0.0F), 0.0F);
		s.tick();
		assertEquals(1 / 12.5F, s.attackStrength(0.0F), 1e-6);
		assertEquals(1.5F / 12.5F, s.attackStrength(0.5F), 1e-6);
		for (int i = 0; i < 20; i++) {
			s.tick();
		}
		assertEquals(1.0F, s.attackStrength(0.0F), 0.0F);
	}

	@Test
	void consumablesTableHasFoodAndDrinks() {
		PlayerSurvivalState.Consumable beef = PlayerSurvivalState.consumables().get("minecraft:cooked_beef");
		assertEquals(32, beef.ticks());
		assertEquals(8, beef.nutrition());
		assertTrue(beef.isFood());
		assertTrue(PlayerSurvivalState.consumables().get("minecraft:golden_apple").canAlwaysEat());
		assertFalse(PlayerSurvivalState.consumables().get("minecraft:milk_bucket").isFood());
		assertEquals(16, PlayerSurvivalState.consumables().get("minecraft:dried_kelp").ticks());
	}

	@Test
	void usingAnItemEndsAfterItsDurationOrOnServerCompletion() {
		PlayerSurvivalState s = new PlayerSurvivalState();
		s.startUsing(0, 3);
		assertTrue(s.usingItem());
		s.tick();
		s.tick();
		assertTrue(s.usingItem());
		s.tick();
		assertFalse(s.usingItem());
		s.startUsing(0, 32);
		s.onEntityEvent(PlayerSurvivalState.USE_ITEM_COMPLETE);
		assertFalse(s.usingItem());
	}

	@Test
	void healthFoodAbsorptionEffectsDeathAndRespawn() {
		PlayerSurvivalState s = new PlayerSurvivalState();
		s.onHealth(14.72F, 17, 2.5F);
		s.onOwnData(17, 4.0F);
		s.onOwnData(1, -20);
		s.onEffect(10, 1, 600, 6);
		s.onAttributes(Map.of(PlayerSurvivalState.ATTR_MAX_HEALTH, 40.0, PlayerSurvivalState.ATTR_ARMOR, 6.0));
		PlayerSurvivalState.View v = s.view(0);
		assertEquals(14.72F, v.health());
		assertEquals(40.0F, v.maxHealth());
		assertEquals(4.0F, v.absorption());
		assertEquals(6.0, v.armor());
		assertEquals(-20, v.air());
		assertEquals("resistance", v.effects().getFirst().name());
		assertEquals(10, v.hurtTicks()); // Gesundheit gesunken → Schadensrückmeldung
		s.tick();
		assertEquals(599, s.view(0).effects().getFirst().remaining());
		s.onDeath("x was killed", null);
		assertTrue(s.view(0).dead());
		s.onRespawnPacket();
		s.onHealth(20.0F, 20, 5.0F);
		v = s.view(0);
		assertFalse(v.dead());
		assertTrue(v.effects().isEmpty());
		assertEquals(0.0F, v.absorption());
	}

	@Test
	void zeroHealthWithoutDeathMessageCountsAsDeadLikeIsDeadOrDying() {
		// Erneuter Login im Todeszustand: der Server sendet set_health 0, aber kein player_combat_kill (k4, docs/38 §7)
		PlayerSurvivalState s = new PlayerSurvivalState();
		s.onHealth(0.0F, 20, 5.0F);
		assertTrue(s.view(0).dead());
		assertEquals("", s.view(0).deathMessage());
		s.onDeath("x was killed", null); // regulärer Tod: Nachricht folgt nach set_health 0
		assertEquals(1, s.view(0).deaths());
		s.onHealth(20.0F, 20, 5.0F);
		assertFalse(s.view(0).dead());
	}

	@Test
	void swingAnimationsFromItemComponents() {
		assertArrayEquals(new int[]{PlayerSurvivalState.SWING_WHACK, 6}, PlayerSurvivalState.swingAnimation("minecraft:iron_sword", true));
		assertArrayEquals(new int[]{PlayerSurvivalState.SWING_WHACK, 6}, PlayerSurvivalState.swingAnimation(null, true));
		assertArrayEquals(new int[]{PlayerSurvivalState.SWING_STAB, 19}, PlayerSurvivalState.swingAnimation("minecraft:iron_spear", true));
		assertArrayEquals(new int[]{PlayerSurvivalState.SWING_WHACK, 6}, PlayerSurvivalState.swingAnimation("minecraft:iron_spear", false));
		assertTrue(PlayerSurvivalState.piercingWeapon("minecraft:wooden_spear"));
		assertFalse(PlayerSurvivalState.piercingWeapon("minecraft:iron_axe"));
	}

	@Test
	void swingStateFollowsStartIfAbleAndEffectModifiedDuration() {
		PlayerSurvivalState s = new PlayerSurvivalState();
		assertTrue(s.swing(0, "minecraft:iron_sword", true));
		assertEquals(PlayerSurvivalState.SWING_WHACK, s.view(0).swingType());
		s.tick(); // ticks 0 → 1, Animation 0
		assertFalse(s.swing(0, "minecraft:iron_sword", true), "running swing is not restarted before half its duration");
		s.tick(); // Animation 1/6
		assertEquals(1 / 6.0F, s.view(1.0F).swingProgress(), 1e-6);
		s.tick();
		s.tick(); // ticks 4 > 6/2
		assertTrue(s.swing(0, "minecraft:iron_sword", true));
		for (int i = 0; i < 9; i++) {
			s.tick();
		}
		assertEquals(PlayerSurvivalState.SWING_NONE, s.view(0).swingType(), "swing ends after duration + 1 ticks");
		assertEquals(2, s.view(0).swings());
		// Eile II: 6 − (1 + 1) = 4 Ticks; Abbaulähmung I: 6 + 2 = 8 Ticks
		s.onEffect(2, 1, 100, 0);
		s.swing(0, null, true);
		s.tick();
		s.tick();
		assertEquals(1 / 4.0F, s.view(1.0F).swingProgress(), 1e-6);
		s.onEffectRemoved(2);
		for (int i = 0; i < 6; i++) {
			s.tick();
		}
		s.onEffect(3, 0, 100, 0);
		s.swing(0, null, true);
		s.tick();
		s.tick();
		assertEquals(1 / 8.0F, s.view(1.0F).swingProgress(), 1e-6);
	}

	@Test
	void effectAmplifierLookup() {
		PlayerSurvivalState s = new PlayerSurvivalState();
		assertEquals(-1, s.effectAmplifier(2));
		s.onEffect(2, 1, 100, 0);
		assertEquals(1, s.effectAmplifier(2));
		s.onEffectRemoved(2);
		assertEquals(-1, s.effectAmplifier(2));
	}
}
