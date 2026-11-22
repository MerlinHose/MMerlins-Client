package dev.mcresearch.protocolclient.ui;

import java.util.ArrayList;
import java.util.List;

/** Gruppe von Widgets (Panel); zeichnet optional einen Hintergrund. */
public class Container extends Widget {
	private final List<Widget> children = new ArrayList<>();
	private boolean panel;

	public Container(final String id) {
		super(id);
	}

	/** Mit Panel-Hintergrund und Rahmen. */
	public final Container panel(final boolean p) {
		this.panel = p;
		return this;
	}

	public final <W extends Widget> W add(final W w) {
		this.children.add(w);
		w.setParent(this);
		return w;
	}

	public final void clear() {
		for (Widget w : this.children) {
			w.setParent(null);
		}
		this.children.clear();
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
		for (int i = this.children.size() - 1; i >= 0; i--) {
			Widget h = this.children.get(i).hit(x, y);
			if (h != null) {
				return h;
			}
		}
		return this.panel ? this : null;
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		if (this.panel) {
			c.fill(bounds(), ctx.theme().panel());
			c.border(bounds(), lineWidth(c), ctx.theme().panelBorder());
		}
		for (Widget w : this.children) {
			if (w.visible()) {
				w.render(c, ctx);
			}
		}
	}
}
