package dev.mcresearch.protocolclient.ui;

import dev.mcresearch.protocolclient.input.GameAction;
import java.util.List;
import java.util.function.Consumer;

/**
 * Auswahlliste: zeigt „Bezeichnung: Wert“; Klick/Bestätigen öffnet eine Liste über allen Elementen (Maus oder Pfeiltasten,
 * Esc schließt), Links/Rechts wählt direkt den vorherigen/nächsten freigegebenen Eintrag. Gesperrte Einträge („geplant“) sind
 * sichtbar, aber nicht wählbar.
 */
public class Dropdown extends Widget {
	/** Eintrag: Wert, Anzeigename, wählbar. */
	public record Option(String value, String name, boolean enabled) {
	}

	private final String label;
	private final List<Option> options;
	private String selected;
	private final Consumer<String> onChange;
	private PopupList open;

	public Dropdown(final String id, final String label, final List<Option> options, final String selected, final Consumer<String> onChange) {
		super(id);
		this.label = label;
		this.options = List.copyOf(options);
		this.selected = selected;
		this.onChange = onChange;
	}

	public final String selected() {
		return this.selected;
	}

	public final void setSelected(final String v) {
		this.selected = v;
	}

	public final List<Option> options() {
		return this.options;
	}

	public final boolean isOpen() {
		return this.open != null;
	}

	private String nameOf(final String v) {
		for (Option o : this.options) {
			if (o.value().equals(v)) {
				return o.name();
			}
		}
		return v;
	}

	/** Wählt {@code v} (nur freigegebene Einträge) und meldet die Änderung. */
	public final void select(final String v) {
		for (Option o : this.options) {
			if (o.value().equals(v) && o.enabled()) {
				boolean changed = !v.equals(this.selected);
				this.selected = v;
				if (changed) {
					this.onChange.accept(v);
				}
				return;
			}
		}
	}

	private void cycle(final int dir) {
		int i = 0;
		for (int k = 0; k < this.options.size(); k++) {
			if (this.options.get(k).value().equals(this.selected)) {
				i = k;
			}
		}
		for (int n = 1; n <= this.options.size(); n++) {
			Option o = this.options.get(Math.floorMod(i + dir * n, this.options.size()));
			if (o.enabled()) {
				select(o.value());
				return;
			}
		}
	}

	private void openList(final UiContext ctx) {
		double rowH = bounds().h();
		double listH = rowH * this.options.size();
		double screenH = ctx.manager().height();
		double y = bounds().bottom();
		if (y + listH > screenH && bounds().y() - listH >= 0) {
			y = bounds().y() - listH;
		}
		y = Math.max(0, Math.min(y, screenH - listH));
		PopupList p = new PopupList(this);
		p.setBounds(bounds().x(), y, bounds().w(), listH);
		Widget focus = null;
		for (int i = 0; i < this.options.size(); i++) {
			Option o = this.options.get(i);
			Button b = p.add(new Button(id() + ".opt." + o.value(), o.name() + (o.enabled() ? "" : " (geplant)"), btn -> {
				select(o.value());
				closeList(ctx);
			}));
			b.setEnabled(o.enabled());
			b.setBounds(bounds().x(), y + i * rowH, bounds().w(), rowH);
			if (o.value().equals(this.selected)) {
				focus = b;
			}
		}
		this.open = p;
		ctx.openPopup(p);
		if (focus != null) {
			ctx.requestFocus(focus);
		}
	}

	final void closeList(final UiContext ctx) {
		ctx.closePopup();
		ctx.requestFocus(this);
	}

	void listClosed() {
		this.open = null;
	}

	@Override
	public boolean mousePressed(final double x, final double y, final int button, final UiContext ctx) {
		if (button != 0) {
			return false;
		}
		if (this.open != null) {
			closeList(ctx);
		} else {
			openList(ctx);
		}
		return true;
	}

	@Override
	public boolean action(final GameAction a, final UiContext ctx) {
		switch (a) {
			case GUI_CONFIRM -> {
				openList(ctx);
				return true;
			}
			case GUI_LEFT -> {
				cycle(-1);
				return true;
			}
			case GUI_RIGHT -> {
				cycle(1);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		drawButtonFrame(c, ctx, this, this.open != null);
		Rect r = bounds().inset(4);
		c.textIn(this.label + ": " + nameOf(this.selected), new Rect(r.x(), r.y(), r.w() - 10, r.h()), UiCanvas.Align.CENTER, textColor(ctx, this),
			true);
		c.textIn("▾", new Rect(r.right() - 8, r.y(), 8, r.h()), UiCanvas.Align.RIGHT, ctx.theme().textDim(), false);
	}

	@Override
	public String describe() {
		return "Dropdown " + id() + " " + this.selected;
	}

	/** Aufgeklappte Liste (gehört dem {@link ScreenManager} als Popup). */
	public static final class PopupList extends Container {
		private final Dropdown owner;

		PopupList(final Dropdown owner) {
			super(owner.id() + ".list");
			this.owner = owner;
			panel(true);
		}

		public Dropdown owner() {
			return this.owner;
		}
	}
}
