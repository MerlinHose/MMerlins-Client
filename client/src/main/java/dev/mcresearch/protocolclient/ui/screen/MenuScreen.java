package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.ui.Button;
import dev.mcresearch.protocolclient.ui.Rect;
import dev.mcresearch.protocolclient.ui.Screen;
import dev.mcresearch.protocolclient.ui.UiCanvas;
import dev.mcresearch.protocolclient.ui.UiContext;
import dev.mcresearch.protocolclient.ui.Widget;
import java.util.List;
import java.util.function.Consumer;

/** Gemeinsame Grundlage der Merlin-Menüs: Dienste, Titel, Knopfspalten. */
public abstract class MenuScreen extends Screen {
	public static final double BUTTON_H = 20;
	public static final double GAP = 4;
	protected final AppServices app;

	protected MenuScreen(final AppServices app) {
		this.app = app;
	}

	protected final Button button(final String id, final String label, final Consumer<Button> action) {
		return add(new Button(id, label, action));
	}

	/** Breite einer Knopfspalte: Wunsch, aber höchstens Fensterbreite − 16. */
	protected static double columnWidth(final double want, final double screenW) {
		return Math.max(60, Math.min(want, screenW - 16));
	}

	/** Setzt Widgets untereinander, zentriert ab {@code y}; liefert die Unterkante. */
	protected static double column(final List<? extends Widget> ws, final double centerX, final double y, final double w) {
		double yy = y;
		for (Widget b : ws) {
			b.setBounds(new Rect(centerX - w / 2, yy, w, BUTTON_H));
			yy += BUTTON_H + GAP;
		}
		return yy;
	}

	/** Zwei Knöpfe nebeneinander in einer Zeile der Breite {@code w}. */
	protected static void row(final Widget a, final Widget b, final double centerX, final double y, final double w) {
		double half = (w - GAP) / 2;
		a.setBounds(new Rect(centerX - w / 2, y, half, BUTTON_H));
		b.setBounds(new Rect(centerX - w / 2 + half + GAP, y, half, BUTTON_H));
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		super.render(c, ctx);
		renderTitle(c, ctx, titleY());
	}

	protected double titleY() {
		return Math.max(4, Math.min(20, height() * 0.05));
	}
}
