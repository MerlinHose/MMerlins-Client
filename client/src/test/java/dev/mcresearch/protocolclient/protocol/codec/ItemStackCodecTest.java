package dev.mcresearch.protocolclient.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.session.PlayerInventory;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ItemStack.OPTIONAL_STREAM_CODEC und Inventar-Layout (docs/28 §3–§4). */
class ItemStackCodecTest {
	private static final ItemRegistry ITEMS = ItemRegistry.get();

	private static ItemStack read(final PacketWriter w) throws Exception {
		PacketReader r = new PacketReader(w.toByteArray());
		ItemStack s = ItemStackCodec.read(r, ITEMS);
		assertEquals(0, r.remaining(), "codec consumed everything");
		return s;
	}

	@Test
	void tablesAreConsistentWithTheReports() {
		assertEquals(1658, ITEMS.size());
		assertEquals("minecraft:air", ITEMS.name(0));
		assertEquals(1, ITEMS.maxStackSize(ITEMS.idOf("minecraft:diamond_pickaxe")));
		assertEquals(16, ITEMS.maxStackSize(ITEMS.idOf("minecraft:ender_pearl")));
		assertEquals("minecraft:redstone_wire", ITEMS.placedBlock(ITEMS.idOf("minecraft:redstone")));
		assertNull(ITEMS.placedBlock(ITEMS.idOf("minecraft:diamond")));
		ItemRegistry.Tool pick = ITEMS.tool(ITEMS.idOf("minecraft:iron_pickaxe"));
		assertEquals(6.0f, pick.miningSpeed("minecraft:stone"));
		assertTrue(pick.correctForDrops("minecraft:diamond_ore"));
		assertEquals(false, ITEMS.tool(ITEMS.idOf("minecraft:stone_pickaxe")).correctForDrops("minecraft:diamond_ore"));
		assertEquals(1.0f, pick.miningSpeed("minecraft:oak_planks"), "no rule → default speed");
		assertTrue(ITEMS.componentId("minecraft:damage") >= 0);
	}

	@Test
	void emptyAndPlainStacks() throws Exception {
		assertTrue(read(new PacketWriter().writeVarInt(0)).isEmpty());
		int planks = ITEMS.idOf("minecraft:oak_planks");
		ItemStack s = read(new PacketWriter().writeVarInt(12).writeVarInt(planks).writeVarInt(0).writeVarInt(0));
		assertEquals(12, s.count());
		assertEquals(planks, s.itemId());
		assertTrue(s.components().isEmpty());
	}

	@Test
	void damageEnchantmentsAndRemovedTool() throws Exception {
		int pick = ITEMS.idOf("minecraft:diamond_pickaxe");
		PacketWriter w = new PacketWriter().writeVarInt(1).writeVarInt(pick).writeVarInt(2).writeVarInt(1);
		w.writeVarInt(ITEMS.componentId("minecraft:damage")).writeVarInt(17);
		w.writeVarInt(ITEMS.componentId("minecraft:enchantments")).writeVarInt(1).writeVarInt(9).writeVarInt(5);
		w.writeVarInt(ITEMS.componentId("minecraft:tool"));
		ItemStack s = read(w);
		assertEquals(17, s.components().get("minecraft:damage"));
		assertEquals(Map.of(9, 5), s.components().get("minecraft:enchantments"));
		assertTrue(s.removed().contains("minecraft:tool"));
		assertNull(s.tool(ITEMS), "removed tool component → hand speed");
	}

	@Test
	void unsupportedComponentIsReportedNotSkipped() {
		int book = ITEMS.idOf("minecraft:written_book");
		PacketWriter w = new PacketWriter().writeVarInt(1).writeVarInt(book).writeVarInt(1).writeVarInt(0)
			.writeVarInt(ITEMS.componentId("minecraft:written_book_content")).writeVarInt(3);
		assertThrows(ItemStackCodec.UnsupportedComponentException.class, () -> ItemStackCodec.read(new PacketReader(w.toByteArray()), ITEMS));
	}

	@Test
	void invalidItemIdIsAProtocolError() {
		PacketWriter w = new PacketWriter().writeVarInt(1).writeVarInt(99999).writeVarInt(0).writeVarInt(0);
		assertThrows(ProtocolException.class, () -> ItemStackCodec.read(new PacketReader(w.toByteArray()), ITEMS));
	}

	@Test
	void inventoryIndexToMenuSlot() {
		assertEquals(36, PlayerInventory.menuSlotOfInventoryIndex(0), "hotbar 1");
		assertEquals(44, PlayerInventory.menuSlotOfInventoryIndex(8));
		assertEquals(9, PlayerInventory.menuSlotOfInventoryIndex(9));
		assertEquals(35, PlayerInventory.menuSlotOfInventoryIndex(35));
		assertEquals(8, PlayerInventory.menuSlotOfInventoryIndex(36), "feet → menu 8");
		assertEquals(5, PlayerInventory.menuSlotOfInventoryIndex(39), "head → menu 5");
		assertEquals(45, PlayerInventory.menuSlotOfInventoryIndex(40), "offhand");
		assertEquals(-1, PlayerInventory.menuSlotOfInventoryIndex(41), "body armor has no menu slot");
	}
}
