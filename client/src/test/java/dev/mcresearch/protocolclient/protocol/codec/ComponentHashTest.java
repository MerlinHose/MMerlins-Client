package dev.mcresearch.protocolclient.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.world.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Komponenten-Hashes gegen die Referenzwerte, die analysis/tools/HashCheck.java mit dem unveränderten Server-JAR
 * (persistenter Codec → HashOps.CRC32C_INSTANCE) berechnet hat (docs/30 §6).
 */
class ComponentHashTest {
	private static int h(final String component, final Object value) {
		return ComponentHash.of(component, value).orElseThrow();
	}

	@Test
	void matchesMojangHashOps() {
		assertEquals(-1726626450, h("minecraft:damage", 7));
		assertEquals(-2146075228, h("minecraft:damage", 0));
		assertEquals(707932877, h("minecraft:max_damage", 59));
		assertEquals(-499649379, h("minecraft:repair_cost", 3));
		assertEquals(1769065625, h("minecraft:max_stack_size", 16));
		assertEquals(-1019818302, h("minecraft:enchantment_glint_override", true));
		assertEquals(828198337, h("minecraft:enchantment_glint_override", false));
		assertEquals(-982207288, h("minecraft:unbreakable", ItemStack.UNIT));
		assertEquals(-292715907, h("minecraft:rarity", 3), "rarity EPIC (id 3) is encoded as the string \"epic\"");
		assertEquals(-1153337500, h("minecraft:item_model", "minecraft:stick"));
	}

	@Test
	void unsupportedComponentsHaveNoHash() {
		assertTrue(ComponentHash.of("minecraft:enchantments", java.util.Map.of(9, 5)).isEmpty());
		assertTrue(ComponentHash.of("minecraft:custom_name", "x").isEmpty());
	}
}
