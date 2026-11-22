package dev.mcresearch.protocolclient.ui;

import dev.mcresearch.protocolclient.input.GameAction;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** Schieberegler über [min, max] mit Schrittweite; Ziehen mit der Maus, Links/Rechts mit der Tastatur, Mausrad. */
public class Slider extends Widget {
	private final String label;
	private final double min;
	private final double max;
	private final double step;
	private double value;
	private final DoubleFunction<String> format;
	private final DoubleConsumer onChange;
	private boolean dragging;

	public Slider(final String id, final String label, final double min, final double max, final double step, final double value,
		final DoubleFunction<String> format, final DoubleConsumer onChange) {
		super(id);
		this.label = label;
		this.min = min;
		this.max = max;
		this.step = step;
		this.value = snap(value);
		this.format = format;
		this.onChange = onChange;
	}

	public final double value() {
		return this.value;
	}

	public final void setValue(final double v) {
		this.value = snap(v);
	}

	private double snap(final double v) {
		double c = Math.max(this.min, Math.min(this.max, v));
		if (this.step > 0) {
			c = this.min + Math.round((c - this.min) / this.step) * this.step;
			c = Math.max(this.min, Math.min(this.max, c));
		}
		return c;
	}

	/** Setzt den Wert und meldet ihn, wenn er sich ändert. */
	public final void change(final double v) {
		double n = snap(v);
		if (n != this.value) {
			this.value = n;
			this.onChange.accept(n);
		}
	}

	private double fromX(final double x) {
		Rect r = bounds().inset(4);
		double f = r.w() <= 0 ? 0 : (x - r.x()) / r.w();
		return this.min + Math.max(0, Math.min(1, f)) * (this.max - this.min);
	}

	@Override
	public boolean mousePressed(final double x, final double y, final int button, final UiContext ctx) {
		if (button != 0) {
			return false;
		}
		this.dragging = true;
		change(fromX(x));
		return true;
	}

	@Override
	public void mouseDragged(final double x, final double y, final UiContext ctx) {
		if (this.dragging) {
			change(fromX(x));
		}
	}

	@Override
	public void mouseReleased(final double x, final double y, final int button, final UiContext ctx) {
		this.dragging = false;
	}

	@Override
	public boolean scrolled(final double x, final double y, final double amount, final UiContext ctx) {
		change(this.value + Math.signum(amount) * keyStep());
		return true;
	}

	private double keyStep() {
		return this.step > 0 ? this.step : (this.max - this.min) / 100.0;
	}

	@Override
	public boolean action(final GameAction a, final UiContext ctx) {
		if (a == GameAction.GUI_LEFT || a == GameAction.GUI_RIGHT) {
			change(this.value + (a == GameAction.GUI_RIGHT ? 1 : -1) * keyStep());
			return true;
		}
		return false;
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		drawButtonFrame(c, ctx, this, false);
		Rect r = bounds();
		double f = this.max == this.min ? 0 : (this.value - this.min) / (this.max - this.min);
		Rect track = r.inset(4);
		c.fill(new Rect(track.x(), track.bottom() - 2, track.w() * f, 2), enabled() ? ctx.theme().accent() : ctx.theme().textDisabled());
		double kw = 6;
		c.fill(new Rect(track.x() + f * track.w() - kw / 2, r.y() + 2, kw, r.h() - 4), enabled() ? ctx.theme().text() : ctx.theme().textDisabled());
		c.textIn(this.label + ": " + this.format.apply(this.value), r.inset(4), UiCanvas.Align.CENTER, textColor(ctx, this), true);
	}

	@Override
	public String describe() {
		return "Slider " + id() + " " + this.format.apply(this.value);
	}
}
