package dev.mcresearch.protocolclient.gl;

import dev.mcresearch.protocolclient.session.BlockInteraction;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.PlayerInventory;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.Locale;

/**
 * Spiel-HUD (M7, docs/27 §5 / docs/28 §5): Hotbar mit 9 Plätzen, Name des gewählten Items, Abbaufortschritt unter dem
 * Fadenkreuz und ein einfaches Inventarfenster (nur Anzeige, Server-Sicht). Eigene Darstellung ohne Mojang-Grafiken: Items
 * erscheinen als Kurzname mit Anzahl. Texte werden nur bei Inventar-/Auswahländerung neu gezeichnet (TextPanel).
 */
final class Hud implements AutoCloseable {
	private static final int SLOT = 44;
	private final TextPanel hotbarText = new TextPanel();
	private final TextPanel inventoryText = new TextPanel();
	private final Font small = new Font(Font.SANS_SERIF, Font.BOLD, 11);
	private final Font name = new Font(Font.SANS_SERIF, Font.BOLD, 15);
	private final ItemRegistry items = ItemRegistry.get();

	void draw(final HudRenderer hud, final ClientSession session, final int w, final int h, final boolean inventoryOpen) {
		int scale = Math.max(1, h / 720);
		int slot = SLOT * scale;
		PlayerInventory inv = session.inventory();
		int selected = session.interaction().selectedSlot();
		int x0 = (w - 9 * slot) / 2;
		int y0 = h - slot - 8 * scale;
		// Hotbar-Rahmen
		hud.rect(x0 - 2, y0 - 2, 9 * slot + 4, slot + 4, 0x90000000);
		for (int i = 0; i < 9; i++) {
			int sx = x0 + i * slot;
			hud.rect(sx + 2, y0 + 2, slot - 4, slot - 4, i == selected ? 0xC0FFFFFF : 0x60808080);
			hud.rect(sx + 4, y0 + 4, slot - 8, slot - 8, i == selected ? 0xE0303030 : 0xB0202020);
		}
		// Abbaufortschritt (eigene Vorhersage des Fortschritts, nicht der Serverzustand) unter dem Fadenkreuz
		BlockInteraction.Status st = session.interaction().status();
		if (st.destroying() && st.progress() > 0) {
			int bw = 60 * scale, bh = 4 * scale;
			int bx = (w - bw) / 2, by = h / 2 + 14 * scale;
			hud.rect(bx - 1, by - 1, bw + 2, bh + 2, 0xA0000000);
			hud.rect(bx, by, Math.min(1f, st.progress()) * bw, bh, 0xFFE0E0E0);
		}
		if (inventoryOpen) {
			drawInventoryFrame(hud, w, h, scale);
		}
		hud.flushScreen(w, h);
		// Texte: Kurzname und Anzahl je Platz, darüber der volle Name des gewählten Items
		ItemStack[] hb = new ItemStack[9];
		StringBuilder key = new StringBuilder().append(w).append('x').append(h).append(':').append(selected);
		for (int i = 0; i < 9; i++) {
			hb[i] = inv.hotbar(i);
			key.append('|').append(hb[i].itemId()).append(',').append(hb[i].count()).append(',').append(hb[i].components().hashCode());
		}
		int textH = slot + 26 * scale;
		this.hotbarText.draw(key.toString(), x0, y0 + slot - textH, 9 * slot, textH, g -> {
			g.setFont(this.small.deriveFont(11f * scale));
			for (int i = 0; i < 9; i++) {
				if (hb[i].isEmpty()) {
					continue;
				}
				String n = shortName(hb[i]);
				FontMetrics fm = g.getFontMetrics();
				int cx = i * slot + slot / 2;
				int top = textH - slot;
				g.setColor(Color.WHITE);
				drawWrapped(g, n, cx, top + 8 * scale, slot - 8);
				if (hb[i].count() > 1) {
					String c = Integer.toString(hb[i].count());
					g.setColor(Color.YELLOW);
					g.drawString(c, i * slot + slot - 5 * scale - fm.stringWidth(c), textH - 6 * scale);
				}
				if (hb[i].components().containsKey("minecraft:damage")) {
					g.setColor(new Color(255, 120, 120));
					g.drawString("d" + hb[i].components().get("minecraft:damage"), i * slot + 5 * scale, textH - 6 * scale);
				}
			}
			ItemStack sel = hb[selected];
			if (!sel.isEmpty()) {
				g.setFont(this.name.deriveFont(15f * scale));
				String full = this.items.name(sel.itemId()).replace("minecraft:", "") + (sel.count() > 1 ? " ×" + sel.count() : "")
					+ (sel.components().containsKey("minecraft:enchantments") ? " (verzaubert)" : "");
				int fw = g.getFontMetrics().stringWidth(full);
				g.setColor(new Color(0, 0, 0, 150));
				g.fillRect((9 * slot - fw) / 2 - 6, 0, fw + 12, g.getFontMetrics().getHeight());
				g.setColor(Color.WHITE);
				g.drawString(full, (9 * slot - fw) / 2, g.getFontMetrics().getAscent());
			}
		}, w, h);
		if (inventoryOpen) {
			drawInventoryText(inv, w, h, scale);
		}
	}

	private static final int INV_COLS = 9;

	private int[] inventoryRect(final int w, final int h, final int scale) {
		int cell = SLOT * scale;
		int pw = INV_COLS * cell + 24 * scale;
		int ph = 5 * cell + 64 * scale;
		return new int[]{(w - pw) / 2, Math.max(10, h - ph - SLOT * scale - 24 * scale), pw, ph, cell}; // über der Hotbar
	}

	private void drawInventoryFrame(final HudRenderer hud, final int w, final int h, final int scale) {
		hud.rect(0, 0, w, h, 0x70000000);
		int[] r = inventoryRect(w, h, scale);
		hud.rect(r[0], r[1], r[2], r[3], 0xF0303038);
		int cell = r[4];
		// Zeile 0: Rüstung (4), Nebenhand, Cursor; Zeilen 1–3: Hauptinventar; Zeile 4: Hotbar (gleiche Abstände wie drawInventoryText)
		for (int row = 0; row <= 4; row++) {
			for (int col = 0; col < INV_COLS; col++) {
				if (row == 0 && col > 5) {
					continue;
				}
				int x = r[0] + 12 * scale + col * cell;
				int y = r[1] + 40 * scale + row * cell + (row >= 1 ? 6 * scale : 0) + (row == 4 ? 6 * scale : 0);
				hud.rect(x + 2, y + 2, cell - 4, cell - 4, 0xB0151518);
			}
		}
	}

	private void drawInventoryText(final PlayerInventory inv, final int w, final int h, final int scale) {
		int[] r = inventoryRect(w, h, scale);
		int cell = r[4];
		ItemStack[] all = inv.snapshot();
		ItemStack cursor = inv.cursor();
		StringBuilder key = new StringBuilder().append(w).append('x').append(h).append(':').append(inv.changes());
		this.inventoryText.draw(key.toString(), r[0], r[1], r[2], r[3], g -> {
			g.setFont(this.name.deriveFont(14f * scale));
			g.setColor(Color.WHITE);
			g.drawString(String.format(Locale.ROOT, "Inventar (nur Anzeige, Serverstand #%d)%s", inv.stateId(),
				inv.unsupportedPackets() > 0 ? " – " + inv.unsupportedPackets() + " Paket(e) nicht dekodierbar" : ""), 12 * scale, 24 * scale);
			g.setFont(this.small.deriveFont(10f * scale));
			// Zeile 0: Kopf, Brust, Beine, Füße (Menüplätze 5–8), Nebenhand (45), Cursor
			ItemStack[] top = {all[5], all[6], all[7], all[8], all[PlayerInventory.OFFHAND], cursor};
			String[] labels = {"Kopf", "Brust", "Beine", "Füße", "2. Hand", "Cursor"};
			for (int col = 0; col < 6; col++) {
				cellText(g, top[col], labels[col], 12 * scale + col * cell, 40 * scale, cell, scale);
			}
			for (int row = 1; row <= 4; row++) {
				for (int col = 0; col < INV_COLS; col++) {
					int menu = row <= 3 ? 9 + (row - 1) * 9 + col : PlayerInventory.HOTBAR_START + col;
					int y = 40 * scale + row * cell + 6 * scale + (row == 4 ? 6 * scale : 0);
					cellText(g, all[menu], null, 12 * scale + col * cell, y, cell, scale);
				}
			}
		}, w, h);
	}

	private void cellText(final Graphics2D g, final ItemStack s, final String label, final int x, final int y, final int cell, final int scale) {
		if (label != null && s.isEmpty()) {
			g.setColor(new Color(150, 150, 160));
			drawWrapped(g, label, x + cell / 2, y + 16 * scale, cell - 6);
			return;
		}
		if (s.isEmpty()) {
			return;
		}
		g.setColor(Color.WHITE);
		drawWrapped(g, shortName(s), x + cell / 2, y + 12 * scale, cell - 6);
		if (s.count() > 1) {
			g.setColor(Color.YELLOW);
			String c = Integer.toString(s.count());
			g.drawString(c, x + cell - 5 * scale - g.getFontMetrics().stringWidth(c), y + cell - 6 * scale);
		}
	}

	private String shortName(final ItemStack s) {
		return this.items.name(s.itemId()).replace("minecraft:", "").replace('_', ' ');
	}

	/** Zentrierter Text, umbrochen auf höchstens drei Zeilen innerhalb von {@code maxW}. */
	private static void drawWrapped(final Graphics2D g, final String text, final int cx, final int top, final int maxW) {
		FontMetrics fm = g.getFontMetrics();
		String[] words = text.split(" ");
		StringBuilder line = new StringBuilder();
		int y = top;
		int lines = 0;
		for (int i = 0; i <= words.length && lines < 3; i++) {
			String next = i < words.length ? (line.isEmpty() ? words[i] : line + " " + words[i]) : null;
			if (next != null && fm.stringWidth(next) <= maxW) {
				line.setLength(0);
				line.append(next);
				continue;
			}
			String out = line.isEmpty() && i < words.length ? words[i] : line.toString();
			while (fm.stringWidth(out) > maxW && out.length() > 1) {
				out = out.substring(0, out.length() - 1);
			}
			g.drawString(out, cx - fm.stringWidth(out) / 2, y);
			y += fm.getHeight() - 2;
			lines++;
			line.setLength(0);
			if (i < words.length && !out.equals(words[i])) {
				line.append(words[i]);
			}
			if (i < words.length && out.equals(words[i])) {
				line.setLength(0);
			}
		}
	}

	@Override
	public void close() {
		this.hotbarText.close();
		this.inventoryText.close();
	}
}
