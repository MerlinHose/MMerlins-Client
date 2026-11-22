package dev.mcresearch.protocolclient.ui;

import java.util.List;

/** Erklärungsfeld neben dem Mauszeiger; bleibt vollständig im Fenster (umgebrochen, links/oben ausweichend). */
public final class Tooltip {
	private Tooltip() {
	}

	public static void render(final UiCanvas c, final UiContext ctx, final String text, final double mx, final double my, final double screenW,
		final double screenH) {
		double maxW = Math.min(220, screenW - 16);
		List<String> lines = c.wrap(text, maxW - 8);
		double w = 0;
		for (String l : lines) {
			w = Math.max(w, c.textWidth(l));
		}
		w += 8;
		double lh = c.lineHeight() + 1;
		double h = lines.size() * lh + 6;
		double x = mx + 10;
		double y = my + 10;
		if (x + w > screenW - 2) {
			x = Math.max(2, mx - w - 6);
		}
		if (y + h > screenH - 2) {
			y = Math.max(2, screenH - h - 2);
		}
		Rect r = new Rect(x, y, w, h);
		c.fill(r, ctx.theme().tooltipBg());
		c.border(r, Math.max(1, Math.round(c.scale() / 2.0)) * c.hairline(), ctx.theme().accent());
		for (int i = 0; i < lines.size(); i++) {
			c.textIn(lines.get(i), new Rect(x + 4, y + 3 + i * lh, w - 8, lh), UiCanvas.Align.LEFT, ctx.theme().text(), false);
		}
	}
}
