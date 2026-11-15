package dev.mcresearch.protocolclient.cli;

import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.InventoryController;
import dev.mcresearch.protocolclient.session.PlayerInventory;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Terminalbefehle der Inventarbedienung (M8, docs/31 §5). Jede Aktion wird als Geste an den {@link InventoryController}
 * übergeben; der Befehl wartet, bis sie ausgeführt und die Serverantwort eingetroffen ist, und meldet die gesendeten Pakete
 * sowie die vom Server geänderten Plätze.
 */
final class InventoryCommands {
	private InventoryCommands() {
	}

	static String describe(final ItemStack s) {
		return s.describe(ItemRegistry.get());
	}

	/** Aktuelles Menü: Container-ID, Typ, stateId, Cursor, alle belegten Plätze (Menüindex). */
	static List<String> show(final ClientSession session) {
		PlayerInventory inv = session.inventory();
		List<String> out = new ArrayList<>();
		PlayerInventory.OpenContainer oc = inv.openContainer();
		int size = inv.currentSize();
		out.add(String.format(Locale.ROOT, "menu %d %s, slots %d, stateId %d, cursor %s", inv.currentId(),
			oc == null ? "player inventory (InventoryMenu)" : oc.menuName() + " \"" + oc.title() + "\" player slots from " + oc.playerStart()
				+ (oc.contentReceived() ? "" : " (no content yet)") + (oc.data().isEmpty() ? "" : " data " + new TreeMap<>(oc.data())),
			size, inv.currentStateId(), describe(inv.cursor())));
		for (int i = 0; i < Math.max(size, 0); i++) {
			ItemStack s = inv.currentSlot(i);
			if (!s.isEmpty()) {
				out.add("  [" + i + "] " + describe(s));
			}
		}
		out.add(String.format(Locale.ROOT, "invalid-slot packets %d, stale-container packets %d, full resyncs %d, undecodable %d, clicks %d, dropped gestures %d",
			inv.invalidSlotPackets(), inv.staleContainerPackets(), inv.fullResyncs(), inv.unsupportedPackets(), session.inventoryController().clicksSent(),
			session.inventoryController().dropped()));
		return out;
	}

	/** Spielerinventar nach Inventory-Index (0–8 Hotbar, 9–35 Haupt, 36–39 Füße…Kopf, 40 Nebenhand) für den Serverabgleich. */
	static List<String> dump(final ClientSession session) {
		PlayerInventory inv = session.inventory();
		ItemRegistry items = ItemRegistry.get();
		List<String> out = new ArrayList<>();
		for (int index = 0; index <= 40; index++) {
			ItemStack s = inv.slot(PlayerInventory.menuSlotOfInventoryIndex(index));
			if (!s.isEmpty()) {
				StringBuilder sb = new StringBuilder("INV ").append(index).append(' ').append(items.name(s.itemId())).append(' ').append(s.count());
				Map<String, Object> c = new TreeMap<>(s.components());
				c.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
				out.add(sb.toString());
			}
		}
		ItemStack cursor = inv.cursor();
		out.add("CURSOR " + (cursor.isEmpty() ? "empty" : items.name(cursor.itemId()) + " " + cursor.count()));
		return out;
	}

	/** Eigene Plätze des geöffneten Containers ("SLOT i item anzahl …") für den Abgleich mit {@code data get block … Items}. */
	static List<String> containerDump(final ClientSession session) {
		PlayerInventory inv = session.inventory();
		PlayerInventory.OpenContainer oc = inv.openContainer();
		ItemRegistry items = ItemRegistry.get();
		List<String> out = new ArrayList<>();
		if (oc == null) {
			out.add("NO-CONTAINER");
			return out;
		}
		for (int i = 0; i < oc.playerStart(); i++) {
			ItemStack s = inv.currentSlot(i);
			if (!s.isEmpty()) {
				StringBuilder sb = new StringBuilder("SLOT ").append(i).append(' ').append(items.name(s.itemId())).append(' ').append(s.count());
				new TreeMap<>(s.components()).forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
				out.add(sb.toString());
			}
		}
		out.add("CONTAINER " + oc.id() + " " + oc.menuName() + " own " + oc.playerStart());
		return out;
	}

	/** Geste ausführen und auf Ausführung + Serverantwort warten. */
	static List<String> run(final ClientSession session, final InventoryController.Gesture g) throws InterruptedException {
		InventoryController c = session.inventoryController();
		PlayerInventory inv = session.inventory();
		int historyBefore = c.history().size();
		long t0 = System.nanoTime();
		if (!c.submit(g)) {
			return List.of("! gesture queue full");
		}
		long deadline = t0 + 3_000_000_000L;
		while (!c.idle() && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		Thread.sleep(120); // Antwort des Servers (gleicher Servertick) abwarten
		List<String> out = new ArrayList<>();
		List<InventoryController.Executed> h = c.history();
		InventoryController.Executed ex = h.size() > historyBefore ? h.getLast() : null;
		out.add("sent " + (ex == null ? "nothing" : ex.packets() + " (" + ex.note() + ")"));
		List<String> changed = new ArrayList<>();
		for (PlayerInventory.Update u : inv.recentUpdates()) {
			if (u.nanos() >= t0) {
				changed.add(u.slot() == -1 ? "cursor" : u.slot() == -2 ? "content(c" + u.containerId() + ")" : u.slot() == -3 ? "screen" : "c" + u.containerId()
					+ "[" + u.slot() + "]");
			}
		}
		out.add("server updated " + (changed.isEmpty() ? "nothing" : String.join(" ", changed)) + "; cursor now " + describe(inv.cursor()));
		return out;
	}

	static int button(final String s) {
		return switch (s.toLowerCase(Locale.ROOT)) {
			case "left", "l", "0" -> 0;
			case "right", "r", "1" -> 1;
			case "middle", "m", "2" -> 2;
			default -> throw new IllegalArgumentException("button left|right|middle");
		};
	}
}
