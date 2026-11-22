package dev.mcresearch.protocolclient.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Senkrecht scrollbarer Bereich. Kinder liegen in Inhaltskoordinaten ({@link #setContentHeight}); gezeichnet und getroffen wird
 * nur der sichtbare Ausschnitt. Mausrad, Ziehen der Leiste und Fokuswechsel (Fokus bleibt sichtbar) scrollen.
 */
public class ScrollContainer extends Widget {
	private final List<Widget> children = new ArrayList<>();
	private final List<Rect> contentBounds = new ArrayList<>();
	private double contentHeight;
	private double scroll;
	private boolean dragBar;
	private double dragOffset;

	public ScrollContainer(final String id) {
		super(id);
	}

	/** Fügt {@code w} mit Lage {@code r} relativ zur Inhaltsoberkante hinzu. */
	public final <W extends Widget> W add(final W w, final Rect r) {
		this.children.add(w);
		this.contentBounds.add(r);
		w.setParent(this);
		place();
		return w;
	}

	public final void clear() {
		for (Widget w : this.children) {
			w.setParent(null);
		}
		this.children.clear();
		this.contentBounds.clear();
		this.contentHeight = 0;
		this.scroll = 0;
	}

	public final void setContentHeight(final double h) {
		this.contentHeight = h;
		clampScroll();
		place();
	}

	public final double contentHeight() {
		return this.contentHeight;
	}

	public final double scroll() {
		return this.scroll;
	}

	public final double maxScroll() {
		return Math.max(0, this.contentHeight - bounds().h());
	}

	public final void scrollTo(final double s) {
		this.scroll = s;
		clampScroll();
		place();
	}

	private void clampScroll() {
		this.scroll = Math.max(0, Math.min(maxScroll(), this.scroll));
	}

	private double barWidth() {
		return maxScroll() > 0 ? 6 : 0;
	}

	/** Breite für Kinder (abzüglich Scrollleiste). */
	public final double innerWidth() {
		return bounds().w() - 8;
	}

	@Override
	public void setBounds(final Rect r) {
		super.setBounds(r);
		clampScroll();
		place();
	}

	private void place() {
		Rect b = bounds();
		for (int i = 0; i < this.children.size(); i++) {
			Rect c = this.contentBounds.get(i);
			this.children.get(i).setBounds(new Rect(b.x() + c.x(), b.y() + c.y() - this.scroll, c.w(), c.h()));
		}
	}

	/** Scrollt so, dass {@code w} ganz sichtbar ist. */
	public final void scrollToShow(final Widget w) {
		int i = this.children.indexOf(w);
		if (i < 0) {
			return;
		}
		Rect c = this.contentBounds.get(i);
		if (c.y() < this.scroll) {
			scrollTo(c.y());
		} else if (c.bottom() > this.scroll + bounds().h()) {
			scrollTo(c.bottom() - bounds().h());
		}
	}

	@Override
	public List<Widget> children() {
		return List.copyOf(this.children);
	}

	@Override
	public boolean focusable() {
		return false;
	}

	@Override
	public Widget hit(final double x, final double y) {
		if (!visible() || !bounds().contains(x, y)) {
			return null;
		}
		if (barWidth() > 0 && x >= bounds().right() - barWidth() - 1) {
			return this;
		}
		for (int i = this.children.size() - 1; i >= 0; i--) {
			Widget h = this.children.get(i).hit(x, y);
			if (h != null) {
				return h;
			}
		}
		return this;
	}

	private Rect bar() {
		Rect b = bounds();
		double frac = Math.min(1, b.h() / Math.max(1, this.contentHeight));
		double h = Math.max(12, b.h() * frac);
		double y = b.y() + (maxScroll() == 0 ? 0 : (b.h() - h) * this.scroll / maxScroll());
		return new Rect(b.right() - barWidth(), y, barWidth(), h);
	}

	@Override
	public boolean mousePressed(final double x, final double y, final int button, final UiContext ctx) {
		if (barWidth() > 0 && x >= bounds().right() - barWidth() - 1 && button == 0) {
			Rect bar = bar();
			this.dragBar = true;
			this.dragOffset = bar.contains(x, y) ? y - bar.y() : bar.h() / 2;
			mouseDragged(x, y, ctx);
			return true;
		}
		return false;
	}

	@Override
	public void mouseDragged(final double x, final double y, final UiContext ctx) {
		if (this.dragBar) {
			Rect b = bounds();
			double h = bar().h();
			double f = (y - this.dragOffset - b.y()) / Math.max(1, b.h() - h);
			scrollTo(f * maxScroll());
		}
	}

	@Override
	public void mouseReleased(final double x, final double y, final int button, final UiContext ctx) {
		this.dragBar = false;
	}

	@Override
	public boolean scrolled(final double x, final double y, final double amount, final UiContext ctx) {
		if (maxScroll() <= 0) {
			return false;
		}
		scrollTo(this.scroll - amount * 24);
		ctx.manager().mouseMovedRefresh();
		return true;
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		Rect b = bounds();
		c.pushClip(b);
		for (Widget w : this.children) {
			if (w.visible() && w.bounds().intersects(b)) {
				w.render(c, ctx);
			}
		}
		c.popClip();
		if (barWidth() > 0) {
			c.fill(new Rect(b.right() - barWidth(), b.y(), barWidth(), b.h()), 0x40FFFFFF);
			c.fill(bar(), ctx.hovered() == this || this.dragBar ? ctx.theme().accent() : ctx.theme().textDim());
		}
	}

	@Override
	public String describe() {
		return String.format(java.util.Locale.ROOT, "Scroll %s %.0f/%.0f", id(), this.scroll, maxScroll());
	}
}
