package dev.mcresearch.protocolclient.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.protocol.packet.Serverbound;
import dev.mcresearch.protocolclient.protocol.packet.ServerboundPacket;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Inventarmodell und Klickübersetzung ohne Netzwerk (M8, docs/31 §3–§4, Phase G). */
class InventoryModelTest {
	private static final ItemRegistry ITEMS = ItemRegistry.get();
	private final PlayerInventory inv = new PlayerInventory();
	private final InventoryController ctl = new InventoryController(this.inv);

	private static ItemStack item(final String name, final int count) {
		return new ItemStack(ITEMS.idOf(name), count, Map.of(), Set.of());
	}

	private static List<ItemStack> empty(final int n) {
		return new ArrayList<>(Collections.nCopies(n, ItemStack.EMPTY));
	}

	private List<ServerboundPacket> run() {
		List<ServerboundPacket> out = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			out.addAll(this.ctl.tick());
		}
		return out;
	}

	@Test
	void invalidSlotsAndForeignContainersAreIgnoredAndCounted() {
		this.inv.setContent(0, 5, empty(46), ItemStack.EMPTY);
		this.inv.setSlot(0, 6, 46, item("minecraft:stone", 1));
		this.inv.setSlot(0, 6, -3, item("minecraft:stone", 1));
		this.inv.setSlot(7, 6, 0, item("minecraft:stone", 1));
		this.inv.setContent(0, 6, empty(10), ItemStack.EMPTY);
		assertEquals(3, this.inv.invalidSlotPackets(), "slot 46, slot −3, wrong content size");
		assertEquals(1, this.inv.staleContainerPackets(), "container 7 is not open");
		assertEquals(5, this.inv.stateId(), "rejected packets do not change the state id");
		this.inv.setInventoryIndex(99, item("minecraft:stone", 1));
		assertEquals(4, this.inv.invalidSlotPackets());
	}

	@Test
	void openContainerMapsPlayerSlotsToTheSharedInventory() {
		this.inv.setContent(0, 1, empty(46), ItemStack.EMPTY);
		this.inv.openScreen(3, 2, "Truhe"); // generic_9x3: 27 eigene + 36 Spielerplätze
		List<ItemStack> content = empty(63);
		content.set(0, item("minecraft:diamond", 5));
		content.set(27, item("minecraft:cobblestone", 32)); // Spieler-Inventarindex 9
		content.set(54, item("minecraft:oak_planks", 16)); // Hotbar 1
		this.inv.setContent(3, 9, content, item("minecraft:stick", 2));
		assertEquals(5, this.inv.currentSlot(0).count());
		assertEquals("minecraft:cobblestone", ITEMS.name(this.inv.slot(9).itemId()), "player part written to the inventory (menu 0 slot 9)");
		assertEquals("minecraft:oak_planks", ITEMS.name(this.inv.hotbar(0).itemId()));
		assertEquals(2, this.inv.cursor().count(), "cursor of the open menu");
		// set_player_inventory während die Truhe offen ist: sichtbar im Truhenfenster (gemeinsames Inventory)
		this.inv.setInventoryIndex(1, item("minecraft:torch", 3));
		assertEquals(3, this.inv.currentSlot(55).count());
		// Container 0 behält eigenen Cursor
		this.inv.closeContainer();
		assertTrue(this.inv.cursor().isEmpty(), "inventory menu has its own carried item");
		assertNull(this.inv.openContainer());
	}

	@Test
	void unknownMenuTypeTakesItsSizeFromTheServer() {
		this.inv.openScreen(4, 24, "Steinsäge"); // stonecutter: nicht modelliert
		this.inv.setContent(4, 1, empty(38), ItemStack.EMPTY);
		assertEquals(38, this.inv.currentSize());
		assertEquals(2, this.inv.openContainer().playerStart());
	}

	@Test
	void clickDecisionUsesTheConfirmedCursor() {
		this.inv.setContent(0, 4, empty(46), ItemStack.EMPTY);
		this.ctl.submit(new InventoryController.Click(0, 36, 0, false));
		List<ServerboundPacket> p = run();
		assertEquals(1, p.size());
		Serverbound.ContainerClick c = (Serverbound.ContainerClick) p.getFirst();
		assertEquals(Serverbound.ContainerClick.PICKUP, c.input());
		assertEquals(4, c.stateId());
		assertTrue(c.changedSlots().isEmpty(), "no client prediction: no changed slots claimed");
		this.ctl.submit(new InventoryController.Click(0, InventoryController.OUTSIDE, 0, false));
		assertEquals(Serverbound.ContainerClick.THROW, ((Serverbound.ContainerClick) run().getFirst()).input(), "empty cursor outside → THROW");
		this.inv.setCursor(item("minecraft:oak_planks", 8));
		this.ctl.submit(new InventoryController.Click(0, InventoryController.OUTSIDE, 0, false));
		Serverbound.ContainerClick out = (Serverbound.ContainerClick) run().getFirst();
		assertEquals(Serverbound.ContainerClick.PICKUP, out.input(), "carried item outside → PICKUP at −999 (drop)");
		assertEquals(8, out.carried().count(), "claims the confirmed cursor");
	}

	@Test
	void dragSendsTheQuickCraftSequence() {
		this.inv.setContent(0, 2, empty(46), item("minecraft:oak_planks", 8));
		this.ctl.submit(new InventoryController.Drag(0, List.of(21, 22, 23), 1));
		List<ServerboundPacket> p = run();
		assertEquals(5, p.size());
		int[] buttons = p.stream().mapToInt(x -> ((Serverbound.ContainerClick) x).button()).toArray();
		assertEquals(List.of(4, 5, 5, 5, 6), java.util.Arrays.stream(buttons).boxed().toList(), "mask = stage | type << 2 (type 1 = right)");
		assertEquals(-999, ((Serverbound.ContainerClick) p.getFirst()).slot());
		assertEquals(23, ((Serverbound.ContainerClick) p.get(3)).slot());
	}

	@Test
	void staleContainerGestureIsDroppedAndRateLimitApplies() {
		this.inv.setContent(0, 1, empty(46), ItemStack.EMPTY);
		this.ctl.submit(new InventoryController.Click(5, 0, 0, false));
		assertTrue(run().isEmpty());
		assertEquals(1, this.ctl.dropped());
		this.ctl.submit(new InventoryController.Click(0, 10, 0, false));
		this.ctl.submit(new InventoryController.Click(0, 11, 0, false));
		assertEquals(1, this.ctl.tick().size());
		assertEquals(0, this.ctl.tick().size(), "second gesture waits for the server answer");
		assertEquals(1, this.ctl.tick().size());
	}

	@Test
	void dropInTheWorldIsFollowedByAResyncRequest() {
		this.inv.setContent(0, 100, empty(46), ItemStack.EMPTY);
		this.ctl.submit(new InventoryController.DropSelected(0, false));
		List<ServerboundPacket> p = run();
		assertEquals(2, p.size());
		Serverbound.PlayerAction a = (Serverbound.PlayerAction) p.getFirst();
		assertEquals(Serverbound.PlayerAction.DROP_ITEM, a.action());
		Serverbound.ContainerClick r = (Serverbound.ContainerClick) p.get(1);
		assertEquals(-1, r.slot(), "no-op slot");
		assertTrue(r.stateId() != 100, "deliberately different state id forces broadcastFullState");
	}

	@Test
	void swapAndThrowNeedTheirPreconditions() {
		this.inv.setContent(0, 1, empty(46), item("minecraft:stick", 1));
		this.ctl.submit(new InventoryController.Swap(0, 20, 3));
		assertTrue(run().isEmpty(), "swap needs an empty cursor");
		this.ctl.submit(new InventoryController.Throw(0, 20, false));
		assertTrue(run().isEmpty(), "nothing to throw in an empty slot");
		assertEquals(2, this.ctl.dropped());
	}
}
