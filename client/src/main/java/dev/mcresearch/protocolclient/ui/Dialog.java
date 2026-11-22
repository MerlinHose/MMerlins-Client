package dev.mcresearch.protocolclient.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Modales Meldungsfenster mit Titel, umgebrochenem Text und Knöpfen (z. B. Verbindungsfehler, Rückfrage „Alles zurücksetzen?“).
 * Escape wählt den letzten Knopf (Abbrechen/Schließen), Bestätigen den fokussierten.
 */
public final class Dialog extends Container {
	/** Knopf: ID, Beschriftung und Aktion (der Dialog schließt sich vorher). */
	public record Choice(String id, String label, Runnable action) {
	}

	private final String title;
	private final String message;
	private final boolean error;
	private final List<Button> buttons = new ArrayList<>();
	private final TextLabel text;
	private ScreenManager manager;

	public Dialog(final String id, final String title, final String message, final boolean error, final List<Choice> choices) {
		super(id);
		panel(true);
		this.title = title;
		this.message = message;
		this.error = error;
		this.text = add(new TextLabel(id + ".text", message).wrap(true).style(error ? TextLabel.Style.ERROR : TextLabel.Style.NORMAL));
		for (Choice ch : choices) {
			this.buttons.add(add(new Button(ch.id(), ch.label(), btn -> close(ch.action()))));
		}
	}

	private void close(final Runnable then) {
		if (this.manager != null) {
			this.manager.closeDialog(this);
		}
		then.run();
	}

	public String title() {
		return this.title;
	}

	public String message() {
		return this.message;
	}

	public boolean error() {
		return this.error;
	}

	Widget defaultFocus() {
		return this.buttons.isEmpty() ? null : this.buttons.getFirst();
	}

	/** Escape: letzter Knopf (Abbrechen/Schließen). */
	void cancel(final UiContext ctx) {
		if (!this.buttons.isEmpty()) {
			this.buttons.getLast().press();
		} else {
			ctx.manager().closeDialog(this);
		}
	}

	void layoutIn(final ScreenManager m, final double sw, final double sh) {
		this.manager = m;
		UiCanvas measure = m.measure();
		double w = Math.min(340, sw - 16);
		double lh = measure.lineHeight() + 1;
		int lines = measure.wrap(this.message, w - 20).size();
		double maxText = Math.max(lh, sh - 8 - 24 - 10 - 28);
		double textH = Math.min(maxText, lines * lh + 2);
		double h = 24 + textH + 10 + 28;
		double x = (sw - w) / 2;
		double y = Math.max(4, (sh - h) / 2);
		setBounds(x, y, w, h);
		this.text.setBounds(x + 10, y + 24, w - 20, textH);
		int n = this.buttons.size();
		double gap = 6;
		double bw = n == 0 ? 0 : Math.min(140, (w - 20 - gap * (n - 1)) / n);
		double total = n * bw + (n - 1) * gap;
		for (int i = 0; i < n; i++) {
			this.buttons.get(i).setBounds(x + (w - total) / 2 + i * (bw + gap), y + h - 28, bw, 20);
		}
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		super.render(c, ctx);
		Rect b = bounds();
		c.textIn(this.title, new Rect(b.x() + 8, b.y() + 6, b.w() - 16, c.lineHeight() + 4), UiCanvas.Align.CENTER,
			this.error ? ctx.theme().error() : ctx.theme().text(), true);
	}

	@Override
	public String describe() {
		return "Dialog " + id() + " \"" + this.title + "\"";
	}
}
