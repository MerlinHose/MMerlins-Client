package dev.mcresearch.protocolclient.session;

import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Inventar und Container wie vom Server gemeldet (docs/28 §4, M8: docs/31). Der Server ist maßgeblich; der Client ändert den
 * Inhalt nie selbst, er wählt nur den Hotbar-Platz und sendet Klicks ({@link InventoryController}).
 *
 * <p>Container 0 ({@code InventoryMenu}, immer vorhanden): 0 Ergebnis, 1–4 Handwerksraster, 5–8 Rüstung (Kopf … Füße), 9–35
 * Hauptinventar, 36–44 Hotbar, 45 Nebenhand. Ein geöffneter Container ({@link ContainerView}) hat eigene Plätze und darauf die
 * 36 Spielerplätze; diese werden auf dieselben Stapel abgebildet wie in Container 0 (gemeinsames {@code Inventory}). Jedes Menü
 * hat einen eigenen Cursor ({@code AbstractContainerMenu#carried}). Threadsicher.
 */
public final class PlayerInventory {
	public static final int SLOTS = 46;
	public static final int HOTBAR_START = 36;
	public static final int OFFHAND = 45;

	/** Vom Server geänderter Platz (für sichtbare Rückmeldung), {@code slot} im Menü {@code containerId}; −1 = Cursor. */
	public record Update(int containerId, int slot, long nanos) {
	}

	private final ItemStack[] slots = new ItemStack[SLOTS];
	private ItemStack cursor = ItemStack.EMPTY;
	private int stateId;
	private int selected;
	private boolean synced;
	private int unsupportedPackets;
	private String lastUnsupported = "";
	private long changes;
	private ContainerView open;
	private int invalidSlotPackets;
	private int staleContainerPackets;
	private int fullResyncs;
	private long lastServerPacketNanos;
	private final Deque<Update> recent = new ArrayDeque<>();

	public PlayerInventory() {
		Arrays.fill(this.slots, ItemStack.EMPTY);
	}

	/** {@code Inventory}-Index (set_player_inventory) → Menüplatz in Container 0; −1 für Plätze ohne Menüplatz (Körperrüstung, Sattel). */
	public static int menuSlotOfInventoryIndex(final int index) {
		if (index >= 0 && index <= 8) {
			return HOTBAR_START + index;
		}
		if (index >= 9 && index <= 35) {
			return index;
		}
		if (index >= 36 && index <= 39) {
			return 44 - index; // ArmorSlot(inventory, …, 39 − i) an Menüplatz 5 + i
		}
		return index == 40 ? OFFHAND : -1;
	}

	/** Spielerplatz j (0–26 Inventar 9–35, 27–35 Hotbar) eines Containers → Menüplatz in Container 0. */
	static int playerSectionToMenu0(final int j) {
		return j < 27 ? 9 + j : HOTBAR_START + (j - 27);
	}

	private void touched(final int containerId, final int slot) {
		this.changes++;
		this.lastServerPacketNanos = System.nanoTime();
		this.recent.addLast(new Update(containerId, slot, this.lastServerPacketNanos));
		while (this.recent.size() > 64) {
			this.recent.removeFirst();
		}
	}

	// ================= Serverpakete =================

	/** {@code container_set_content}: Container 0 oder der geöffnete Container; andere IDs werden verworfen (gezählt). */
	synchronized void setContent(final int containerId, final int stateId, final List<ItemStack> items, final ItemStack carried) {
		if (containerId == 0) {
			if (items.size() != SLOTS) {
				this.invalidSlotPackets++;
				return;
			}
			this.stateId = stateId;
			for (int i = 0; i < SLOTS; i++) {
				this.slots[i] = items.get(i);
			}
			this.cursor = carried;
			this.synced = true;
			this.fullResyncs++;
			touched(0, -2);
			return;
		}
		ContainerView v = this.open;
		if (v == null || v.id != containerId) {
			this.staleContainerPackets++;
			return;
		}
		if (v.total >= 0 && items.size() != v.total || items.size() > 1024) {
			// Typ legt die Größe fest, Server meldet eine andere: Inhalt übernehmen, Größe anpassen (Server ist maßgeblich)
			this.invalidSlotPackets++;
		}
		v.resizeForTotal(items.size());
		int start = v.playerStart();
		for (int i = 0; i < items.size(); i++) {
			if (i < start) {
				v.own[i] = items.get(i);
			} else if (v.hasPlayerSlots()) {
				this.slots[playerSectionToMenu0(i - start)] = items.get(i);
			}
		}
		v.cursor = carried;
		v.stateId = stateId;
		v.contentReceived = true;
		this.fullResyncs++;
		touched(containerId, -2);
	}

	/** {@code container_set_slot}. */
	synchronized void setSlot(final int containerId, final int stateId, final int slot, final ItemStack item) {
		if (containerId == 0) {
			if (slot < 0 || slot >= SLOTS) {
				this.invalidSlotPackets++;
				return;
			}
			this.stateId = stateId;
			this.slots[slot] = item;
			touched(0, slot);
			return;
		}
		ContainerView v = this.open;
		if (v == null || v.id != containerId) {
			this.staleContainerPackets++;
			return;
		}
		int total = v.total >= 0 ? v.total : Integer.MAX_VALUE;
		if (slot < 0 || slot >= total || slot > 1024) {
			this.invalidSlotPackets++;
			return;
		}
		v.stateId = stateId;
		int start = v.total >= 0 ? v.playerStart() : v.own.length;
		if (slot < start) {
			if (slot >= v.own.length) {
				v.own = Arrays.copyOf(v.own, slot + 1);
				for (int i = 0; i < v.own.length; i++) {
					if (v.own[i] == null) {
						v.own[i] = ItemStack.EMPTY;
					}
				}
			}
			v.own[slot] = item;
		} else {
			this.slots[playerSectionToMenu0(slot - start)] = item;
		}
		touched(containerId, slot);
	}

	synchronized void setInventoryIndex(final int index, final ItemStack item) {
		int slot = menuSlotOfInventoryIndex(index);
		if (slot >= 0) {
			this.slots[slot] = item;
			touched(0, slot);
		} else if (index < 0 || index > 42) {
			this.invalidSlotPackets++;
		}
	}

	/** {@code set_cursor_item}: Cursor des aktuellen Menüs ({@code player.containerMenu.setCarried}). */
	synchronized void setCursor(final ItemStack item) {
		if (this.open != null) {
			this.open.cursor = item;
		} else {
			this.cursor = item;
		}
		touched(currentId(), -1);
	}

	synchronized void openScreen(final int containerId, final int menuType, final String title) {
		this.open = new ContainerView(containerId, menuType, title);
		touched(containerId, -3);
	}

	/** Container geschlossen (Server {@code container_close} oder eigener Schließen-Befehl): zurück zu Container 0. */
	synchronized void closeContainer() {
		this.open = null;
		touched(0, -3);
	}

	synchronized void setData(final int containerId, final int id, final int value) {
		ContainerView v = this.open;
		if (v == null || v.id != containerId) {
			this.staleContainerPackets++;
			return;
		}
		v.data.put(id, value);
		this.changes++;
	}

	synchronized void setSelected(final int slot) {
		if (slot >= 0 && slot <= 8) {
			this.selected = slot;
			this.changes++;
		}
	}

	synchronized void unsupported(final String packet, final String reason) {
		this.unsupportedPackets++;
		this.lastUnsupported = packet + ": " + reason;
		this.changes++;
	}

	/** Verbindungsende/Wiederbelebung: geöffneter Container gilt als geschlossen. */
	synchronized void reset() {
		this.open = null;
		this.changes++;
	}

	// ================= Lesen =================

	public synchronized ItemStack slot(final int menuSlot) {
		return this.slots[menuSlot];
	}

	public synchronized ItemStack hotbar(final int index) {
		return this.slots[HOTBAR_START + index];
	}

	public synchronized ItemStack mainHand() {
		return this.slots[HOTBAR_START + this.selected];
	}

	public synchronized ItemStack offHand() {
		return this.slots[OFFHAND];
	}

	/** Cursor des aktuellen Menüs. */
	public synchronized ItemStack cursor() {
		return this.open != null ? this.open.cursor : this.cursor;
	}

	public synchronized int selected() {
		return this.selected;
	}

	/** stateId von Container 0. */
	public synchronized int stateId() {
		return this.stateId;
	}

	/** ID des aktuellen Menüs: geöffneter Container oder 0. */
	public synchronized int currentId() {
		return this.open != null ? this.open.id : 0;
	}

	/** stateId des aktuellen Menüs (für container_click). */
	public synchronized int currentStateId() {
		return this.open != null ? this.open.stateId : this.stateId;
	}

	/** Anzahl der Plätze des aktuellen Menüs; −1, solange die Größe eines unbekannten Containers fehlt. */
	public synchronized int currentSize() {
		return this.open != null ? this.open.total : SLOTS;
	}

	/** Stapel an Menüplatz {@code slot} des aktuellen Menüs. */
	public synchronized ItemStack currentSlot(final int slot) {
		ContainerView v = this.open;
		if (v == null) {
			return slot >= 0 && slot < SLOTS ? this.slots[slot] : ItemStack.EMPTY;
		}
		int start = v.total >= 0 ? v.playerStart() : v.own.length;
		if (slot < 0) {
			return ItemStack.EMPTY;
		}
		if (slot < start) {
			return slot < v.own.length ? v.own[slot] : ItemStack.EMPTY;
		}
		if (!v.hasPlayerSlots() || slot - start >= 36) {
			return ItemStack.EMPTY;
		}
		return this.slots[playerSectionToMenu0(slot - start)];
	}

	/** Menüplatz im aktuellen Menü, der Hotbar-Platz {@code hotbarIndex} zeigt (für Anzeige und Tests). */
	public synchronized int currentHotbarSlot(final int hotbarIndex) {
		ContainerView v = this.open;
		if (v == null) {
			return HOTBAR_START + hotbarIndex;
		}
		return v.hasPlayerSlots() ? v.playerStart() + 27 + hotbarIndex : -1;
	}

	/** Geöffneter Container oder {@code null}. Kopie der Kennwerte (Typ, Titel, Plätze, Daten). */
	public synchronized OpenContainer openContainer() {
		ContainerView v = this.open;
		if (v == null) {
			return null;
		}
		return new OpenContainer(v.id, v.menuType, v.kind, v.menuName(), v.title, v.total, v.total >= 0 ? v.playerStart() : v.own.length, v.contentReceived,
			Map.copyOf(v.data));
	}

	/** Schnappschuss des geöffneten Containers. */
	public record OpenContainer(int id, int menuType, ContainerView.Kind kind, String menuName, String title, int total, int playerStart,
		boolean contentReceived, Map<Integer, Integer> data) {
	}

	/** Mindestens ein vollständiger Inhalt (container_set_content 0) empfangen. */
	public synchronized boolean synced() {
		return this.synced;
	}

	public synchronized int unsupportedPackets() {
		return this.unsupportedPackets;
	}

	public synchronized String lastUnsupported() {
		return this.lastUnsupported;
	}

	/** Verworfene Pakete mit ungültigem Platz bzw. veralteter Container-ID (docs/31 §4). */
	public synchronized int invalidSlotPackets() {
		return this.invalidSlotPackets;
	}

	public synchronized int staleContainerPackets() {
		return this.staleContainerPackets;
	}

	/** Anzahl vollständiger Inhalte (container_set_content), u. a. bei veralteter stateId (broadcastFullState). */
	public synchronized int fullResyncs() {
		return this.fullResyncs;
	}

	/** Zeitpunkt des letzten Inventarpakets vom Server (System.nanoTime). */
	public synchronized long lastServerPacketNanos() {
		return this.lastServerPacketNanos;
	}

	/** Vom Server zuletzt geänderte Plätze (neueste zuletzt). */
	public synchronized List<Update> recentUpdates() {
		return new ArrayList<>(this.recent);
	}

	public synchronized long changes() {
		return this.changes;
	}

	/** Container 0 (46 Plätze). */
	public synchronized ItemStack[] snapshot() {
		return this.slots.clone();
	}
}
