package dev.mcresearch.protocolclient.ui;

import dev.mcresearch.protocolclient.input.GameAction;
import java.util.List;
import java.util.function.IntConsumer;

/** Reiterleiste: ein Klick oder Links/Rechts bei Fokus wechselt den Reiter; der aktive ist hervorgehoben. */
public class TabNavigation extends Widget {
	private final List<String> tabs;
	private int selected;
	private final IntConsumer onSelect;
	private int hoverTab = -1;

	public TabNavigation(final String id, final List<String> tabs, final int selected, final IntConsumer onSelect) {
		super(id);
		this.tabs = List.copyOf(tabs);
		this.selected = selected;
		this.onSelect = onSelect;
	}

	public final int selected() {
		return this.selected;
	}

	public final List<String> tabs() {
		return this.tabs;
	}

	public final void select(final int i) {
		if (i >= 0 && i < this.tabs.size() && i != this.selected) {
			this.selected = i;
			this.onSelect.accept(i);
		}
	}

	/** Lage des Reiters {@code i} (für Klicks im Autotest). */
	public final Rect tabRect(final int i) {
		Rect r = bounds();
		double w = r.w() / this.tabs.size();
		return new Rect(r.x() + i * w, r.y(), w, r.h());
	}

	private int tabAt(final double x) {
		Rect r = bounds();
		int i = (int) Math.floor((x - r.x()) / (r.w() / this.tabs.size()));
		return Math.max(0, Math.min(this.tabs.size() - 1, i));
	}

	@Override
	public boolean mousePressed(final double x, final double y, final int button, final UiContext ctx) {
		if (button == 0) {
			select(tabAt(x));
			return true;
		}
		return false;
	}

	@Override
	public boolean action(final GameAction a, final UiContext ctx) {
		if (a == GameAction.GUI_LEFT || a == GameAction.GUI_RIGHT) {
			select(Math.floorMod(this.selected + (a == GameAction.GUI_RIGHT ? 1 : -1), this.tabs.size()));
			return true;
		}
		return false;
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		Theme t = ctx.theme();
		this.hoverTab = ctx.hovered() == this ? -2 : -1;
		double line = lineWidth(c);
		for (int i = 0; i < this.tabs.size(); i++) {
			Rect r = tabRect(i).inset(1);
			boolean sel = i == this.selected;
			c.fill(r, sel ? t.buttonHover() : t.button());
			if (sel) {
				c.fill(new Rect(r.x(), r.bottom() - 2 * line, r.w(), 2 * line), t.accent());
			}
			c.textIn(this.tabs.get(i), r.inset(3), UiCanvas.Align.CENTER, sel ? t.text() : t.textDim(), sel);
		}
		if (ctx.focused() == this && ctx.showFocus()) {
			c.border(tabRect(this.selected), 2 * line, t.focus());
		}
	}

	@Override
	public String describe() {
		return "Tabs " + id() + " " + this.tabs.get(this.selected) + " (" + this.hoverTab + ")";
	}
}
