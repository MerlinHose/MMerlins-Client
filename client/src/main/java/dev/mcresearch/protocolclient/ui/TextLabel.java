package dev.mcresearch.protocolclient.ui;

import java.util.List;

/** Text (eine oder mehrere umbrochene Zeilen); nicht fokussierbar. */
public class TextLabel extends Widget {
	public enum Style {
		NORMAL, DIM, TITLE, ERROR, WARNING, OK
	}

	private String text;
	private UiCanvas.Align align = UiCanvas.Align.LEFT;
	private Style style = Style.NORMAL;
	private boolean wrap;

	public TextLabel(final String id, final String text) {
		super(id);
		this.text = text;
	}

	public final TextLabel align(final UiCanvas.Align a) {
		this.align = a;
		return this;
	}

	public final TextLabel style(final Style s) {
		this.style = s;
		return this;
	}

	/** Mehrzeilig umbrechen (Höhe muss passen; sonst wird gekürzt und als Überlauf gezählt). */
	public final TextLabel wrap(final boolean w) {
		this.wrap = w;
		return this;
	}

	public final String text() {
		return this.text;
	}

	public final void setText(final String t) {
		this.text = t;
	}

	public final Style style() {
		return this.style;
	}

	@Override
	public boolean focusable() {
		return false;
	}

	/** Benötigte Höhe bei der aktuellen Breite (für Layouts mit umbrochenem Text). */
	public double preferredHeight(final UiCanvas c) {
		return this.wrap ? c.wrap(this.text, bounds().w()).size() * (c.lineHeight() + 1) : c.lineHeight() + 2;
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		Theme t = ctx.theme();
		int color = switch (this.style) {
			case NORMAL, TITLE -> t.text();
			case DIM -> t.textDim();
			case ERROR -> t.error();
			case WARNING -> t.warning();
			case OK -> t.ok();
		};
		if (!this.wrap) {
			c.textIn(this.text, bounds(), this.align, color, this.style == Style.TITLE);
			return;
		}
		List<String> lines = c.wrap(this.text, bounds().w());
		double lh = c.lineHeight() + 1;
		int fit = (int) Math.max(1, Math.floor((bounds().h() + 1e-6) / lh));
		for (int i = 0; i < lines.size() && i < fit; i++) {
			String line = lines.get(i);
			if (i == fit - 1 && lines.size() > fit) {
				c.reportOverflow(this.text, lines.size() * lh, bounds().h()); // nicht alles passt: kürzen und als Überlauf zählen
				line = c.ellipsize(line + " …", bounds().w());
			}
			c.textIn(line, new Rect(bounds().x(), bounds().y() + i * lh, bounds().w(), lh), this.align, color, false);
		}
	}

	@Override
	public String describe() {
		return "Label " + id() + " \"" + this.text.replace('\n', ' ') + "\"";
	}
}
