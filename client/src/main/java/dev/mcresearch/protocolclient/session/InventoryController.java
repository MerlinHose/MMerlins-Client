package dev.mcresearch.protocolclient.session;

import dev.mcresearch.protocolclient.protocol.packet.Serverbound;
import dev.mcresearch.protocolclient.protocol.packet.ServerboundPacket;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Inventarbedienung ohne Vorhersage (M8, docs/31 §3): Eingaben (Gesten) werden gesammelt und je Client-Tick in die Klickpakete
 * übersetzt, die {@code AbstractContainerScreen} des Vanilla-Clients für dieselbe Eingabe sendet (docs/30 §6). Grundlage jeder
 * Entscheidung (z. B. Cursor leer → PICKUP, sonst Verteilen) ist der zuletzt vom Server bestätigte Zustand. Damit dieser
 * aktuell ist, führt der Controller höchstens alle {@link #TICKS_BETWEEN} Ticks eine Geste aus; der Server antwortet auf einen
 * Klick im selben Tick ({@code broadcastChanges}).
 *
 * <p>Im Klickpaket meldet der Client keine geänderten Plätze und als Cursor den bestätigten Stapel. Der Server führt den Klick
 * selbst aus und sendet danach jeden Platz, der von diesem gemeldeten Stand abweicht – also genau die echten Änderungen.
 * Gegenstände entstehen oder verschwinden daher nie clientseitig. Eingaben: Grafik- oder Terminal-Thread; {@link #tick} im
 * Tick-Thread der Sitzung.
 */
public final class InventoryController {
	/** Mindestabstand zwischen zwei Gesten in Client-Ticks (Serverantwort abwarten). */
	public static final int TICKS_BETWEEN = 2;
	public static final int OUTSIDE = -999;
	private static final int MAX_QUEUE = 64;

	/** Eine Bedieneingabe im Menü {@code containerId} (zum Zeitpunkt der Eingabe). */
	public sealed interface Gesture {
		int containerId();
	}

	/** Klick ohne Ziehen: Taste 0 links, 1 rechts; {@code slot} −999 = außerhalb des Fensters. */
	public record Click(int containerId, int slot, int button, boolean shift) implements Gesture {
	}

	/** Ziehen mit Cursor über mehrere Plätze (Taste 0 gleichmäßig verteilen, 1 je eines, 2 Kreativ-Kopie). */
	public record Drag(int containerId, List<Integer> slots, int button) implements Gesture {
		public Drag {
			slots = List.copyOf(slots);
		}
	}

	/** Doppelklick (zweiter Linksklick auf denselben Platz): PICKUP_ALL; mit Shift alle gleichen Stapel des Bereichs QUICK_MOVE. */
	public record DoubleClick(int containerId, int slot, boolean shift) implements Gesture {
	}

	/** Zifferntaste (0–8) bzw. F (40) über einem Platz: SWAP. */
	public record Swap(int containerId, int slot, int key) implements Gesture {
	}

	/** Q über einem Platz: THROW (ein Stück oder mit Strg den ganzen Stapel). */
	public record Throw(int containerId, int slot, boolean all) implements Gesture {
	}

	/** Mittelklick im Kreativmodus: CLONE. */
	public record Clone(int containerId, int slot) implements Gesture {
	}

	/** Fenster schließen: container_close (Container 0: Handwerksraster und Cursor gehen ans Inventar zurück). */
	public record Close(int containerId) implements Gesture {
	}

	/** Q außerhalb eines Fensters: player_action DROP_ITEM / DROP_ALL_ITEMS für die Haupthand. */
	public record DropSelected(int containerId, boolean all) implements Gesture {
	}

	/** Vollständigen Serverstand anfordern (docs/30 §8): No-op-Klick auf Platz −1 mit bewusst abweichender stateId. */
	public record Resync(int containerId) implements Gesture {
	}

	/** Ergebnis einer ausgeführten Geste (Anzeige, Tests). */
	public record Executed(Gesture gesture, List<String> packets, String note) {
	}

	private final PlayerInventory inventory;
	private final Deque<Gesture> queue = new ArrayDeque<>();
	private int cooldown;
	private long clicksSent;
	private long dropped;
	private long resyncs;
	private String lastNote = "";
	private final Deque<Executed> history = new ArrayDeque<>();
	/** Zuletzt per Shift verschobener Stapel (AbstractContainerScreen#lastQuickMoved) für Shift-Doppelklick. */
	private ItemStack lastQuickMoved = ItemStack.EMPTY;

	public InventoryController(final PlayerInventory inventory) {
		this.inventory = inventory;
	}

	// ================= Eingabe =================

	/** Geste vormerken; bei voller Warteschlange verworfen (false). */
	public synchronized boolean submit(final Gesture g) {
		if (this.queue.size() >= MAX_QUEUE) {
			this.dropped++;
			this.lastNote = "queue full";
			return false;
		}
		this.queue.addLast(g);
		return true;
	}

	public synchronized int pending() {
		return this.queue.size() + (this.cooldown > 0 ? 1 : 0);
	}

	/** Warteschlange leer und letzte Antwortzeit abgelaufen. */
	public synchronized boolean idle() {
		return this.queue.isEmpty() && this.cooldown == 0;
	}

	public synchronized long clicksSent() {
		return this.clicksSent;
	}

	public synchronized long dropped() {
		return this.dropped;
	}

	public synchronized String lastNote() {
		return this.lastNote;
	}

	public synchronized List<Executed> history() {
		return new ArrayList<>(this.history);
	}

	/** Verbindungsende/Wiederbelebung: offene Gesten verwerfen. */
	public synchronized void reset() {
		this.dropped += this.queue.size();
		this.queue.clear();
		this.cooldown = 0;
	}

	// ================= Tick =================

	/** Höchstens eine Geste je {@link #TICKS_BETWEEN} Ticks in Pakete übersetzen. */
	public synchronized List<ServerboundPacket> tick() {
		if (this.cooldown > 0) {
			this.cooldown--;
			return List.of();
		}
		while (!this.queue.isEmpty()) {
			Gesture g = this.queue.removeFirst();
			List<ServerboundPacket> out = new ArrayList<>(4);
			String note = execute(g, out);
			record(g, out, note);
			if (!out.isEmpty()) {
				this.cooldown = TICKS_BETWEEN - 1;
				return out;
			}
		}
		return List.of();
	}

	private void record(final Gesture g, final List<ServerboundPacket> out, final String note) {
		List<String> names = new ArrayList<>();
		for (ServerboundPacket p : out) {
			if (p instanceof Serverbound.ContainerClick c) {
				names.add("click(c" + c.containerId() + " s" + c.slot() + " b" + c.button() + " " + inputName(c.input()) + " #" + c.stateId() + ")");
			} else {
				names.add(p.name().replace("minecraft:", ""));
			}
		}
		this.lastNote = note;
		this.history.addLast(new Executed(g, names, note));
		while (this.history.size() > 32) {
			this.history.removeFirst();
		}
	}

	public static String inputName(final int input) {
		return switch (input) {
			case Serverbound.ContainerClick.PICKUP -> "PICKUP";
			case Serverbound.ContainerClick.QUICK_MOVE -> "QUICK_MOVE";
			case Serverbound.ContainerClick.SWAP -> "SWAP";
			case Serverbound.ContainerClick.CLONE -> "CLONE";
			case Serverbound.ContainerClick.THROW -> "THROW";
			case Serverbound.ContainerClick.QUICK_CRAFT -> "QUICK_CRAFT";
			case Serverbound.ContainerClick.PICKUP_ALL -> "PICKUP_ALL";
			default -> "#" + input;
		};
	}

	private boolean validSlot(final int slot) {
		if (slot == OUTSIDE) {
			return true;
		}
		int size = this.inventory.currentSize();
		return slot >= 0 && (size < 0 || slot < size);
	}

	private void click(final List<ServerboundPacket> out, final int containerId, final int slot, final int button, final int input) {
		out.add(new Serverbound.ContainerClick(containerId, this.inventory.currentStateId(), slot, button, input, Map.of(), this.inventory.cursor()));
		this.clicksSent++;
	}

	/** Übersetzt eine Geste (AbstractContainerScreen: mouseClicked/mouseReleased/keyPressed); Rückgabe = Notiz. */
	private String execute(final Gesture g, final List<ServerboundPacket> out) {
		int current = this.inventory.currentId();
		if (g instanceof DropSelected d) {
			if (current != 0 && d.containerId() == 0) {
				// LocalPlayer#drop außerhalb eines Fensters; bei geöffnetem Container verworfen
				this.dropped++;
				return "dropped: container open";
			}
			out.add(new Serverbound.PlayerAction(d.all() ? Serverbound.PlayerAction.DROP_ALL_ITEMS : Serverbound.PlayerAction.DROP_ITEM, 0, 0, 0, 0, 0));
			// ServerPlayer#drop setzt den Remote-Stand selbst (Vanilla sagt die Entnahme voraus) und sendet nichts:
			// ohne eigene Vorhersage den tatsächlichen Stand anfordern
			out.add(resyncClick(0));
			return (d.all() ? "drop all (player_action 4)" : "drop one (player_action 5)") + " + resync";
		}
		if (g.containerId() != current) {
			// MultiPlayerGameMode#handleContainerInput: "Ignoring click in mismatching container"
			this.dropped++;
			return "dropped: container changed (" + g.containerId() + " → " + current + ")";
		}
		ItemStack cursor = this.inventory.cursor();
		switch (g) {
			case Resync rs -> {
				out.add(resyncClick(rs.containerId()));
				return "resync request";
			}
			case Close c -> {
				out.add(new Serverbound.ContainerClose(c.containerId()));
				if (c.containerId() != 0) {
					this.inventory.closeContainer(); // LocalPlayer#clientSideCloseContainer
				}
				return "close";
			}
			case Click c -> {
				if (!validSlot(c.slot()) || c.button() < 0 || c.button() > 2) {
					this.dropped++;
					return "dropped: invalid slot " + c.slot();
				}
				int input;
				if (cursor.isEmpty()) {
					boolean quick = c.slot() != OUTSIDE && c.shift();
					input = quick ? Serverbound.ContainerClick.QUICK_MOVE
						: c.slot() == OUTSIDE ? Serverbound.ContainerClick.THROW : Serverbound.ContainerClick.PICKUP;
					if (quick) {
						this.lastQuickMoved = this.inventory.currentSlot(c.slot());
					}
				} else {
					boolean quick = c.slot() != OUTSIDE && c.shift();
					input = quick ? Serverbound.ContainerClick.QUICK_MOVE : Serverbound.ContainerClick.PICKUP;
				}
				click(out, c.containerId(), c.slot(), c.button(), input);
				return inputName(input);
			}
			case Drag d -> {
				if (cursor.isEmpty() || d.slots().isEmpty() || d.button() < 0 || d.button() > 2) {
					this.dropped++;
					return "dropped: drag without cursor";
				}
				for (int s : d.slots()) {
					if (!validSlot(s) || s == OUTSIDE) {
						this.dropped++;
						return "dropped: invalid drag slot " + s;
					}
				}
				// quickCraftToSlots: Start (−999), je Platz Stufe 1, Ende (−999); Maske = Stufe | Art << 2
				click(out, d.containerId(), OUTSIDE, mask(0, d.button()), Serverbound.ContainerClick.QUICK_CRAFT);
				for (int s : d.slots()) {
					click(out, d.containerId(), s, mask(1, d.button()), Serverbound.ContainerClick.QUICK_CRAFT);
				}
				click(out, d.containerId(), OUTSIDE, mask(2, d.button()), Serverbound.ContainerClick.QUICK_CRAFT);
				return "QUICK_CRAFT over " + d.slots().size() + " slots";
			}
			case DoubleClick dc -> {
				if (!validSlot(dc.slot()) || dc.slot() == OUTSIDE) {
					this.dropped++;
					return "dropped: invalid slot " + dc.slot();
				}
				if (dc.shift()) {
					// Vanilla: QUICK_MOVE auf jeden Platz desselben Bereichs mit gleichem Item wie lastQuickMoved
					if (this.lastQuickMoved.isEmpty()) {
						return "shift double click: nothing moved before";
					}
					for (int s : sameSectionSlots(dc.slot())) {
						ItemStack st = this.inventory.currentSlot(s);
						if (!st.isEmpty() && st.itemId() == this.lastQuickMoved.itemId() && st.components().equals(this.lastQuickMoved.components())) {
							click(out, dc.containerId(), s, 0, Serverbound.ContainerClick.QUICK_MOVE);
						}
					}
					return "shift double click: " + out.size() + " QUICK_MOVE";
				}
				click(out, dc.containerId(), dc.slot(), 0, Serverbound.ContainerClick.PICKUP_ALL);
				return "PICKUP_ALL";
			}
			case Swap s -> {
				if (!validSlot(s.slot()) || s.slot() == OUTSIDE || !(s.key() >= 0 && s.key() <= 8 || s.key() == 40) || !cursor.isEmpty()) {
					this.dropped++;
					return "dropped: swap needs empty cursor and a slot";
				}
				click(out, s.containerId(), s.slot(), s.key(), Serverbound.ContainerClick.SWAP);
				return "SWAP with " + (s.key() == 40 ? "offhand" : "hotbar " + (s.key() + 1));
			}
			case Throw t -> {
				if (!validSlot(t.slot()) || t.slot() == OUTSIDE || this.inventory.currentSlot(t.slot()).isEmpty()) {
					this.dropped++;
					return "dropped: nothing to throw";
				}
				click(out, t.containerId(), t.slot(), t.all() ? 1 : 0, Serverbound.ContainerClick.THROW);
				return t.all() ? "THROW stack" : "THROW one";
			}
			case Clone c -> {
				if (!validSlot(c.slot()) || c.slot() == OUTSIDE || !cursor.isEmpty()) {
					this.dropped++;
					return "dropped: clone needs empty cursor";
				}
				click(out, c.containerId(), c.slot(), 2, Serverbound.ContainerClick.CLONE);
				return "CLONE";
			}
			case DropSelected d -> throw new IllegalStateException("handled above");
		}
	}

	/**
	 * PICKUP auf Platz −1 ist auf dem Server wirkungslos ({@code doClick}: slotIndex &lt; 0 → return), eine abweichende stateId
	 * löst aber {@code broadcastFullState} aus: container_set_content mit allen Plätzen und Cursor.
	 */
	private ServerboundPacket resyncClick(final int containerId) {
		this.resyncs++;
		return new Serverbound.ContainerClick(containerId, (this.inventory.currentStateId() + 16384) & 0x7FFF, -1, 0, Serverbound.ContainerClick.PICKUP, Map.of(),
			this.inventory.cursor());
	}

	public synchronized long resyncs() {
		return this.resyncs;
	}

	/** {@code AbstractContainerMenu#getQuickcraftMask}. */
	static int mask(final int stage, final int type) {
		return stage & 3 | (type & 3) << 2;
	}

	/** Plätze desselben Bereichs (Container-eigene bzw. Spielerplätze) wie {@code slot} im aktuellen Menü. */
	private List<Integer> sameSectionSlots(final int slot) {
		List<Integer> out = new ArrayList<>();
		int size = this.inventory.currentSize();
		PlayerInventory.OpenContainer oc = this.inventory.openContainer();
		int start;
		int end;
		if (oc == null) {
			boolean player = slot >= 9;
			start = player ? 9 : 0;
			end = player ? PlayerInventory.SLOTS : 9;
		} else {
			boolean player = slot >= oc.playerStart();
			start = player ? oc.playerStart() : 0;
			end = player ? size : oc.playerStart();
		}
		for (int s = start; s < end; s++) {
			out.add(s);
		}
		return out;
	}
}
