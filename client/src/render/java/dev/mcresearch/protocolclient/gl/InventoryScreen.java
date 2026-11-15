package dev.mcresearch.protocolclient.gl;

import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.ContainerView;
import dev.mcresearch.protocolclient.session.InventoryController;
import dev.mcresearch.protocolclient.session.PlayerInventory;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bedienbares Inventar- und Containerfenster (M8, docs/31 §6). Eigene Darstellung (keine Mojang-Grafiken). Die Maus- und
 * Tastenlogik folgt {@code AbstractContainerScreen} (mouseClicked/mouseDragged/mouseReleased/keyPressed) und erzeugt Gesten für
 * den {@link InventoryController}; angezeigt wird ausschließlich der vom Server gemeldete Zustand. Nur Grafikthread.
 */
final class InventoryScreen implements AutoCloseable {
	private static final long DOUBLE_CLICK_NANOS = 250_000_000L; // MouseHandler: 250 ms
	private static final long FLASH_NANOS = 600_000_000L;

	/** Ein Platz im Fenster: Menüindex und Rechteck in Pixeln. */
	record SlotBox(int slot, int x, int y, int size, boolean result) {
		boolean contains(final double px, final double py) {
			return px >= this.x && px < this.x + this.size && py >= this.y && py < this.y + this.size;
		}
	}

	/** Berechnete Anordnung eines Menüs. */
	record Layout(int x, int y, int w, int h, int cell, List<SlotBox> slots, String title, int containerId) {
		SlotBox at(final double px, final double py) {
			for (SlotBox b : this.slots) {
				if (b.contains(px, py)) {
					return b;
				}
			}
			return null;
		}

		boolean inside(final double px, final double py) {
			return px >= this.x && px < this.x + this.w && py >= this.y && py < this.y + this.h;
		}

		SlotBox box(final int slot) {
			for (SlotBox b : this.slots) {
				if (b.slot() == slot) {
					return b;
				}
			}
			return null;
		}
	}

	private final ItemRegistry items = ItemRegistry.get();
	private final TextPanel slotText = new TextPanel();
	private final TextPanel cursorText = new TextPanel();
	private final TextPanel tooltip = new TextPanel();
	private final Font small = new Font(Font.SANS_SERIF, Font.BOLD, 10);
	private final Font title = new Font(Font.SANS_SERIF, Font.BOLD, 13);

	// Zustand der Eingabe (AbstractContainerScreen)
	private boolean dragging;
	private int dragButton;
	private final Set<Integer> dragSlots = new LinkedHashSet<>();
	private boolean skipNextRelease;
	private boolean doubleClick;
	private int lastClickSlot = Integer.MIN_VALUE;
	private long lastClickNanos;
	private double mouseX;
	private double mouseY;
	private Layout layout;
	private String message = "";
	private long messageNanos;
	private long lastDropped;
	private int lastResyncs;

	// ================= Anordnung =================

	Layout layout(final PlayerInventory inv, final int screenW, final int screenH) {
		int scale = Math.max(1, screenH / 720);
		int cell = 40 * scale;
		int pad = 10 * scale;
		int titleH = 22 * scale;
		PlayerInventory.OpenContainer oc = inv.openContainer();
		List<SlotBox> boxes = new ArrayList<>();
		int ownRows;
		String t;
		int cid = inv.currentId();
		int playerStart;
		if (oc == null) {
			t = "Inventar";
			ownRows = 4;
			playerStart = 9;
		} else {
			t = oc.menuName().replace("minecraft:", "") + (oc.title().isEmpty() ? "" : " – " + oc.title());
			ContainerView.Kind k = oc.kind();
			ownRows = switch (k) {
				case CRAFTING, FURNACE, BLAST_FURNACE, SMOKER, GENERIC_3X3 -> 3;
				case OTHER -> Math.max(1, (oc.playerStart() + 8) / 9);
				default -> Math.max(1, k.rows());
			};
			playerStart = oc.playerStart();
		}
		int w = 9 * cell + 2 * pad;
		int h = titleH + ownRows * cell + pad + 4 * cell + pad / 2 + pad + 16 * scale;
		int x0 = (screenW - w) / 2;
		int y0 = Math.max(4, (screenH - h) / 2 - 20 * scale);
		int ox = x0 + pad;
		int oy = y0 + titleH;
		if (oc == null) {
			for (int i = 0; i < 4; i++) {
				boxes.add(new SlotBox(5 + i, ox, oy + i * cell, cell, false)); // Kopf … Füße
			}
			boxes.add(new SlotBox(PlayerInventory.OFFHAND, ox + cell, oy + 3 * cell, cell, false));
			boxes.add(new SlotBox(1, ox + 5 * cell, oy + cell / 2, cell, false));
			boxes.add(new SlotBox(2, ox + 6 * cell, oy + cell / 2, cell, false));
			boxes.add(new SlotBox(3, ox + 5 * cell, oy + cell / 2 + cell, cell, false));
			boxes.add(new SlotBox(4, ox + 6 * cell, oy + cell / 2 + cell, cell, false));
			boxes.add(new SlotBox(0, ox + 8 * cell, oy + cell, cell, true));
		} else {
			switch (oc.kind()) {
				case CRAFTING -> {
					for (int i = 0; i < 9; i++) {
						boxes.add(new SlotBox(1 + i, ox + (1 + i % 3) * cell, oy + (i / 3) * cell, cell, false));
					}
					boxes.add(new SlotBox(0, ox + 7 * cell, oy + cell, cell, true));
				}
				case FURNACE, BLAST_FURNACE, SMOKER -> {
					boxes.add(new SlotBox(0, ox + 2 * cell, oy, cell, false));
					boxes.add(new SlotBox(1, ox + 2 * cell, oy + 2 * cell, cell, false));
					boxes.add(new SlotBox(2, ox + 6 * cell, oy + cell, cell, true));
				}
				default -> {
					int cols = oc.kind() == ContainerView.Kind.GENERIC_3X3 ? 3 : 9;
					int off = cols == 3 ? 3 : 0;
					for (int i = 0; i < playerStart; i++) {
						boxes.add(new SlotBox(i, ox + (off + i % cols) * cell, oy + (i / cols) * cell, cell, false));
					}
				}
			}
		}
		int py = oy + ownRows * cell + pad;
		if (oc == null || oc.total() >= 36) {
			for (int j = 0; j < 27; j++) {
				boxes.add(new SlotBox(playerStart + j, ox + (j % 9) * cell, py + (j / 9) * cell, cell, false));
			}
			for (int j = 0; j < 9; j++) {
				boxes.add(new SlotBox(oc == null ? PlayerInventory.HOTBAR_START + j : playerStart + 27 + j, ox + j * cell, py + 3 * cell + pad / 2, cell, false));
			}
		}
		this.layout = new Layout(x0, y0, w, h, cell, boxes, t, cid);
		return this.layout;
	}

	// ================= Eingabe =================

	/** Mausereignisse, Mausbewegung und Tasten eines Bildes verarbeiten. Liefert true, wenn das Fenster geschlossen werden soll. */
	boolean input(final ClientSession session, final Window w, final List<Window.MouseEvent> events, final boolean closeKey) {
		double[] pos = w.cursorPosition();
		move(session, pos[0], pos[1]);
		for (Window.MouseEvent e : events) {
			if (e.press()) {
				press(session, e.button(), e.x(), e.y(), e.shift(), e.nanos());
			} else {
				release(session, e.button(), e.x(), e.y(), e.shift());
			}
		}
		for (int k = 0; k < 9; k++) {
			if (w.wasPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_1 + k)) {
				key(session, k, false);
			}
		}
		if (w.wasPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_F)) {
			key(session, 40, false);
		}
		if (w.wasPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_Q)) {
			key(session, -1, w.controlDown());
		}
		return closeKey;
	}

	private SlotBox hovered() {
		return this.layout == null ? null : this.layout.at(this.mouseX, this.mouseY);
	}

	/** Platz-ID wie AbstractContainerScreen: Platz, −999 außerhalb des Fensters, −1 im Fenster ohne Platz. */
	private int slotId(final double x, final double y) {
		if (this.layout == null) {
			return -1;
		}
		SlotBox b = this.layout.at(x, y);
		if (b != null) {
			return b.slot();
		}
		return this.layout.inside(x, y) ? -1 : InventoryController.OUTSIDE;
	}

	void move(final ClientSession session, final double x, final double y) {
		this.mouseX = x;
		this.mouseY = y;
		if (!this.dragging || this.layout == null) {
			return;
		}
		SlotBox b = this.layout.at(x, y);
		ItemStack carried = session.inventory().cursor();
		// shouldAddSlotToQuickCraft (vereinfacht): Ergebnisplätze ausgenommen, Platz leer oder gleiches Item
		if (b != null && !b.result() && !carried.isEmpty() && (carried.count() > this.dragSlots.size() || this.dragButton == 2)) {
			ItemStack in = session.inventory().currentSlot(b.slot());
			if (in.isEmpty() || in.itemId() == carried.itemId() && in.components().equals(carried.components())) {
				this.dragSlots.add(b.slot());
			}
		}
	}

	void press(final ClientSession session, final int button, final double x, final double y, final boolean shift, final long nanos) {
		this.mouseX = x;
		this.mouseY = y;
		int cid = session.inventory().currentId();
		int slot = slotId(x, y);
		boolean creative = session.gameMode() == 1;
		this.doubleClick = button == 0 && slot >= 0 && slot == this.lastClickSlot && nanos - this.lastClickNanos < DOUBLE_CLICK_NANOS;
		this.skipNextRelease = false;
		InventoryController c = session.inventoryController();
		if (button == 2) {
			if (creative && slot >= 0 && session.inventory().cursor().isEmpty() && !session.inventory().currentSlot(slot).isEmpty()) {
				c.submit(new InventoryController.Clone(cid, slot));
			}
			return;
		}
		if (button > 2 || slot == -1) {
			return;
		}
		if (session.inventory().cursor().isEmpty()) {
			c.submit(new InventoryController.Click(cid, slot, button, shift && slot != InventoryController.OUTSIDE));
			this.skipNextRelease = true;
		} else {
			this.dragging = true;
			this.dragButton = button;
			this.dragSlots.clear();
		}
		if (button == 0) {
			this.lastClickSlot = slot;
			this.lastClickNanos = nanos;
		}
	}

	void release(final ClientSession session, final int button, final double x, final double y, final boolean shift) {
		int cid = session.inventory().currentId();
		int slot = slotId(x, y);
		InventoryController c = session.inventoryController();
		if (this.doubleClick && slot >= 0 && button == 0) {
			c.submit(new InventoryController.DoubleClick(cid, slot, shift));
			this.doubleClick = false;
			this.dragging = false;
			this.lastClickSlot = Integer.MIN_VALUE;
			return;
		}
		if (this.dragging && this.dragButton != button) {
			this.dragging = false;
			this.dragSlots.clear();
			this.skipNextRelease = true;
			return;
		}
		if (this.skipNextRelease) {
			this.skipNextRelease = false;
			return;
		}
		if (this.dragging && !this.dragSlots.isEmpty()) {
			c.submit(new InventoryController.Drag(cid, new ArrayList<>(this.dragSlots), this.dragButton));
		} else if (!session.inventory().cursor().isEmpty() && slot != -1) {
			c.submit(new InventoryController.Click(cid, slot, button, shift && slot != InventoryController.OUTSIDE));
		}
		this.dragging = false;
		this.dragSlots.clear();
	}

	/** Zifferntaste (0–8), F (40) oder Q (−1, mit Strg ganzer Stapel) über dem Platz unter der Maus. */
	void key(final ClientSession session, final int key, final boolean control) {
		SlotBox b = hovered();
		if (b == null) {
			return;
		}
		int cid = session.inventory().currentId();
		if (key == -1) {
			if (!session.inventory().currentSlot(b.slot()).isEmpty()) {
				session.inventoryController().submit(new InventoryController.Throw(cid, b.slot(), control));
			}
		} else if (session.inventory().cursor().isEmpty()) {
			session.inventoryController().submit(new InventoryController.Swap(cid, b.slot(), key));
		}
	}

	/** Für Autotest: Mitte eines Platzes. */
	double[] slotCenter(final int slot) {
		SlotBox b = this.layout == null ? null : this.layout.box(slot);
		return b == null ? null : new double[]{b.x() + b.size() / 2.0, b.y() + b.size() / 2.0};
	}

	double[] outsidePoint() {
		return this.layout == null ? new double[]{2, 2} : new double[]{Math.max(2, this.layout.x() - 20), this.layout.y() + 10};
	}

	// ================= Darstellung =================

	void draw(final HudRenderer hud, final ClientSession session, final int sw, final int sh) {
		PlayerInventory inv = session.inventory();
		InventoryController c = session.inventoryController();
		Layout l = layout(inv, sw, sh);
		long now = System.nanoTime();
		// Rückmeldung: verworfene Gesten, Vollabgleich (Korrektur durch den Server)
		if (c.dropped() != this.lastDropped) {
			this.lastDropped = c.dropped();
			this.message = "verworfen: " + c.lastNote();
			this.messageNanos = now;
		}
		if (inv.fullResyncs() != this.lastResyncs) {
			if (this.lastResyncs != 0) {
				this.message = "Server: vollständiger Abgleich (stateId " + inv.currentStateId() + ")";
				this.messageNanos = now;
			}
			this.lastResyncs = inv.fullResyncs();
		}
		hud.rect(0, 0, sw, sh, 0x60000000);
		hud.rect(l.x(), l.y(), l.w(), l.h(), 0xF0303038);
		Set<Integer> flashed = new java.util.HashSet<>();
		for (PlayerInventory.Update u : inv.recentUpdates()) {
			if (u.containerId() == l.containerId() && now - u.nanos() < FLASH_NANOS && u.slot() >= 0) {
				flashed.add(u.slot());
			}
		}
		SlotBox hov = hovered();
		for (SlotBox b : l.slots()) {
			int border = flashed.contains(b.slot()) ? 0xFF40D040 : this.dragSlots.contains(b.slot()) ? 0xFFE0C040 : b.result() ? 0xFF9090B0 : 0xFF505058;
			hud.rect(b.x() + 1, b.y() + 1, b.size() - 2, b.size() - 2, border);
			hud.rect(b.x() + 3, b.y() + 3, b.size() - 6, b.size() - 6, b == hov ? 0xFF3A3A48 : 0xFF18181C);
		}
		PlayerInventory.OpenContainer oc = inv.openContainer();
		if (oc != null && oc.kind().isFurnace()) {
			// Fortschritt aus container_set_data: 0 Brennzeit, 1 Brenndauer, 2 Garfortschritt, 3 Gardauer
			Map<Integer, Integer> d = oc.data();
			float cook = d.getOrDefault(3, 0) > 0 ? d.getOrDefault(2, 0) / (float) d.get(3) : 0;
			float burn = d.getOrDefault(1, 0) > 0 ? d.getOrDefault(0, 0) / (float) d.get(1) : 0;
			SlotBox in = l.box(0);
			if (in != null) {
				hud.rect(in.x() + 2 * l.cell(), in.y() + l.cell() + l.cell() / 2 - 3, 2 * l.cell() * cook, 6, 0xFFE0E0E0);
				hud.rect(in.x() + l.cell() / 4, in.y() + l.cell() + 4, l.cell() / 2f * burn, 6, 0xFFE08020);
			}
		}
		hud.flushScreen(sw, sh);
		// Texte der Plätze (nur bei Änderung neu gezeichnet)
		String key = sw + "x" + sh + ":" + inv.changes() + ":" + l.containerId() + ":" + l.slots().size();
		this.slotText.draw(key, l.x(), l.y(), l.w(), l.h(), g -> paintSlots(g, inv, l), sw, sh);
		// Cursor-Stapel an der Maus
		ItemStack carried = inv.cursor();
		if (!carried.isEmpty()) {
			int cw = l.cell();
			this.cursorText.draw("c" + carried.itemId() + ":" + carried.count() + ":" + cw, (int) this.mouseX - cw / 2, (int) this.mouseY - cw / 2, cw, cw,
				g -> {
					g.setColor(new Color(40, 40, 50, 220));
					g.fillRect(0, 0, cw, cw);
					g.setFont(this.small.deriveFont((float) Math.max(10, cw / 4)));
					paintItem(g, carried, 0, 0, cw);
				}, sw, sh);
		}
		// Tooltip
		if (hov != null && carried.isEmpty()) {
			ItemStack s = inv.currentSlot(hov.slot());
			if (!s.isEmpty()) {
				List<String> lines = tooltipLines(s, hov.slot());
				int tw = 320, th = 18 * lines.size() + 8;
				int tx = (int) Math.min(this.mouseX + 14, sw - tw - 2), ty = (int) Math.max(2, this.mouseY - th - 4);
				this.tooltip.draw(String.join("|", lines), tx, ty, tw, th, g -> {
					g.setColor(new Color(16, 0, 32, 235));
					g.fillRect(0, 0, tw, th);
					g.setFont(this.title);
					for (int i = 0; i < lines.size(); i++) {
						g.setColor(i == 0 ? Color.WHITE : new Color(180, 180, 200));
						g.drawString(lines.get(i), 6, 18 + 18 * i);
					}
				}, sw, sh);
			}
		}
	}

	List<String> tooltipLines(final ItemStack s, final int slot) {
		List<String> lines = new ArrayList<>();
		Object custom = s.components().get("minecraft:custom_name");
		lines.add((custom != null ? dev.mcresearch.protocolclient.protocol.nbt.TextComponents.toPlainText(custom) + " (" : "")
			+ this.items.name(s.itemId()).replace("minecraft:", "") + (custom != null ? ")" : "") + "  ×" + s.count() + " / " + this.items.maxStackSize(s.itemId()));
		if (s.components().containsKey("minecraft:damage")) {
			lines.add("Schaden " + s.components().get("minecraft:damage"));
		}
		if (s.components().get("minecraft:enchantments") instanceof Map<?, ?> m && !m.isEmpty()) {
			lines.add("Verzauberungen: " + m.size() + " (Register-IDs " + m + ")");
		}
		if (!s.removed().isEmpty()) {
			lines.add("entfernte Komponenten: " + s.removed());
		}
		lines.add("Platz " + slot);
		return lines;
	}

	private void paintSlots(final Graphics2D g, final PlayerInventory inv, final Layout l) {
		g.setFont(this.title);
		g.setColor(Color.WHITE);
		g.drawString(l.title(), 10, 16);
		g.setFont(this.small.deriveFont((float) Math.max(10, l.cell() / 4)));
		for (SlotBox b : l.slots()) {
			ItemStack s = inv.currentSlot(b.slot());
			if (!s.isEmpty()) {
				paintItem(g, s, b.x() - l.x(), b.y() - l.y(), b.size());
			}
		}
		g.setFont(this.small);
		g.setColor(new Color(170, 170, 190));
		g.drawString(String.format(Locale.ROOT, "Container %d · stateId %d%s", l.containerId(), inv.currentStateId(),
			inv.unsupportedPackets() > 0 ? " · " + inv.unsupportedPackets() + " Paket(e) nicht dekodierbar" : ""), 10, l.h() - 6);
	}

	private void paintItem(final Graphics2D g, final ItemStack s, final int x, final int y, final int size) {
		String n = this.items.name(s.itemId()).replace("minecraft:", "").replace('_', ' ');
		FontMetrics fm = g.getFontMetrics();
		g.setColor(Color.WHITE);
		String[] words = n.split(" ");
		int line = 0;
		StringBuilder cur = new StringBuilder();
		for (String wd : words) {
			String next = cur.isEmpty() ? wd : cur + " " + wd;
			if (fm.stringWidth(next) > size - 6 && !cur.isEmpty()) {
				drawClipped(g, cur.toString(), x + 3, y + 3 + fm.getAscent() + line * (fm.getHeight() - 2), size - 6);
				line++;
				cur.setLength(0);
				cur.append(wd);
				if (line >= 2) {
					break;
				}
			} else {
				cur.setLength(0);
				cur.append(next);
			}
		}
		if (line < 2 && !cur.isEmpty()) {
			drawClipped(g, cur.toString(), x + 3, y + 3 + fm.getAscent() + line * (fm.getHeight() - 2), size - 6);
		}
		if (s.count() > 1) {
			String c = Integer.toString(s.count());
			g.setColor(Color.YELLOW);
			g.drawString(c, x + size - 4 - fm.stringWidth(c), y + size - 4);
		}
		if (s.components().containsKey("minecraft:damage")) {
			g.setColor(new Color(255, 120, 120));
			g.drawString("d" + s.components().get("minecraft:damage"), x + 3, y + size - 4);
		}
	}

	private static void drawClipped(final Graphics2D g, final String text, final int x, final int y, final int maxW) {
		String t = text;
		while (g.getFontMetrics().stringWidth(t) > maxW && t.length() > 1) {
			t = t.substring(0, t.length() - 1);
		}
		g.drawString(t, x, y);
	}

	/** Statuszeile unter dem Fenster: ausstehende Gesten und letzte Rückmeldung. */
	String status(final ClientSession session) {
		int pending = session.inventoryController().pending();
		boolean showMsg = System.nanoTime() - this.messageNanos < 3_000_000_000L && !this.message.isEmpty();
		return (pending > 0 ? "warte auf Server (" + pending + ")" : "") + (showMsg ? (pending > 0 ? " · " : "") + this.message : "");
	}

	boolean dragging() {
		return this.dragging;
	}

	@Override
	public void close() {
		this.slotText.close();
		this.cursorText.close();
		this.tooltip.close();
	}
}
