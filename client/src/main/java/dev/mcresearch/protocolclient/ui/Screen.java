package dev.mcresearch.protocolclient.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Ein Bildschirm (docs/59 §3). Lebenszyklus: {@link #init()} einmal beim ersten Öffnen (Widgets anlegen),
 * {@link #layout(double, double)} bei jedem Öffnen und jeder Größen-/Skalierungsänderung, {@link #tick()} je Bild,
 * {@link #removed()} beim endgültigen Schließen (Ressourcen freigeben, laufende Vorgänge abbrechen).
 */
public abstract class Screen {
	private final List<Widget> widgets = new ArrayList<>();
	private ScreenManager manager;
	private boolean initialized;
	private double width;
	private double height;
	private String focusId;

	/** Kurzname für Autotest und Bericht (z. B. „main-menu“). */
	public abstract String name();

	/** Überschrift. */
	public abstract String title();

	protected abstract void init();

	protected abstract void layout(double w, double h);

	public void tick() {
	}

	/** Endgültig geschlossen (nicht beim Überdecken durch einen weiteren Screen). */
	public void removed() {
	}

	/** Wieder oben, nachdem ein darüberliegender Screen geschlossen wurde. */
	public void resumed() {
	}

	/** Bilder je Sekunde für Animationen (0 = nur bei Änderungen neu zeichnen). */
	public double animationHz() {
		return 0;
	}

	/** Spielwelteingaben sperren (Bewegung, Angriffe, Blockinteraktion). */
	public boolean blocksWorldInput() {
		return true;
	}

	/** Spielwelt bleibt darunter sichtbar (halbtransparenter Hintergrund, z. B. Pausenmenü). */
	public boolean overlaysWorld() {
		return false;
	}

	/**
	 * Vom Spiel selbst gezeichnete und bediente Bildschirme (Inventar, Container, Tod aus M8/M10): der Manager kennt sie für
	 * Eingabesperre, Fokus und Escape, gibt Maus- und Tasteneingaben aber an das Spiel weiter.
	 */
	public boolean handledByGame() {
		return false;
	}

	/** Escape/Zurück; Standard: diesen Screen schließen. */
	public void onBack() {
		manager().back();
	}

	public final ScreenManager manager() {
		return this.manager;
	}

	final void attach(final ScreenManager m) {
		this.manager = m;
		if (!this.initialized) {
			this.initialized = true;
			init();
		}
	}

	final void doLayout(final double w, final double h) {
		this.width = w;
		this.height = h;
		layout(w, h);
	}

	public final double width() {
		return this.width;
	}

	public final double height() {
		return this.height;
	}

	protected final <W extends Widget> W add(final W w) {
		this.widgets.add(w);
		return w;
	}

	protected final void remove(final Widget w) {
		this.widgets.remove(w);
	}

	public final List<Widget> widgets() {
		return List.copyOf(this.widgets);
	}

	/** Sucht ein Widget (auch in Containern) nach ID. */
	public final Widget find(final String id) {
		for (Widget w : this.widgets) {
			Widget f = find(w, id);
			if (f != null) {
				return f;
			}
		}
		return null;
	}

	private static Widget find(final Widget w, final String id) {
		if (w.id().equals(id)) {
			return w;
		}
		for (Widget c : w.children()) {
			Widget f = find(c, id);
			if (f != null) {
				return f;
			}
		}
		return null;
	}

	/** Fokussierbare Widgets in Tab-Reihenfolge. */
	public final List<Widget> focusOrder() {
		List<Widget> out = new ArrayList<>();
		for (Widget w : this.widgets) {
			collect(w, out);
		}
		return out;
	}

	private static void collect(final Widget w, final List<Widget> out) {
		if (!w.visible()) {
			return;
		}
		if (w.focusable()) {
			out.add(w);
		}
		for (Widget c : w.children()) {
			collect(c, out);
		}
	}

	String rememberedFocus() {
		return this.focusId;
	}

	void rememberFocus(final String id) {
		this.focusId = id;
	}

	/** Standardhintergrund: Verlauf des Merlin-Stils, bei Overlay-Screens halbtransparente Abdunklung. */
	public void renderBackground(final UiCanvas c, final UiContext ctx) {
		Rect all = new Rect(0, 0, this.width, this.height);
		if (overlaysWorld()) {
			c.fill(all, ctx.theme().overlay());
		} else {
			c.gradient(all, ctx.theme().background(), ctx.theme().backgroundBottom());
		}
	}

	public void render(final UiCanvas c, final UiContext ctx) {
		renderBackground(c, ctx);
		for (Widget w : this.widgets) {
			if (w.visible()) {
				w.render(c, ctx);
			}
		}
	}

	/** Überschrift oben mittig (gemeinsamer Stil). */
	protected final void renderTitle(final UiCanvas c, final UiContext ctx, final double y) {
		c.textIn(title(), new Rect(8, y, this.width - 16, c.lineHeight() + 4), UiCanvas.Align.CENTER, ctx.theme().text(), true);
	}
}
