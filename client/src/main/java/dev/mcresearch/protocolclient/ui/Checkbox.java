package dev.mcresearch.protocolclient.ui;

import dev.mcresearch.protocolclient.input.GameAction;
import java.util.function.Consumer;

/** Ein/Aus-Schalter mit Beschriftung; Klick, Bestätigen oder Links/Rechts schaltet um. */
public class Checkbox extends Widget {
	private final String label;
	private boolean checked;
	private final Consumer<Boolean> onChange;

	public Checkbox(final String id, final String label, final boolean checked, final Consumer<Boolean> onChange) {
		super(id);
		this.label = label;
		this.checked = checked;
		this.onChange = onChange;
	}

	public final boolean checked() {
		return this.checked;
	}

	/** Setzt den Zustand ohne Rückmeldung (Anzeige an Modell angleichen). */
	public final void setChecked(final boolean c) {
		this.checked = c;
	}

	public final void toggle() {
		if (enabled()) {
			this.checked = !this.checked;
			this.onChange.accept(this.checked);
		}
	}

	@Override
	public boolean mousePressed(final double x, final double y, final int button, final UiContext ctx) {
		if (button == 0) {
			toggle();
			return true;
		}
		return false;
	}

	@Override
	public boolean action(final GameAction a, final UiContext ctx) {
		if (a == GameAction.GUI_CONFIRM || a == GameAction.GUI_LEFT || a == GameAction.GUI_RIGHT) {
			toggle();
			return true;
		}
		return false;
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		drawButtonFrame(c, ctx, this, false);
		Rect r = bounds();
		double box = Math.min(r.h() - 6, 12);
		Rect b = new Rect(r.right() - box - 6, r.centerY() - box / 2, box, box);
		c.fill(b, ctx.theme().fieldBg());
		c.border(b, lineWidth(c), enabled() ? ctx.theme().text() : ctx.theme().textDisabled());
		if (this.checked) {
			c.fill(b.inset(Math.max(2, box / 4)), enabled() ? ctx.theme().ok() : ctx.theme().textDisabled());
		}
		c.textIn(this.label + ": " + (this.checked ? "An" : "Aus"), new Rect(r.x() + 6, r.y(), r.w() - box - 18, r.h()), UiCanvas.Align.LEFT,
			textColor(ctx, this), true);
	}

	@Override
	public String describe() {
		return "Checkbox " + id() + " " + this.checked;
	}
}
