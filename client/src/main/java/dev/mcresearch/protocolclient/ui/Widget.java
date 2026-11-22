package dev.mcresearch.protocolclient.ui;

import dev.mcresearch.protocolclient.input.GameAction;
import java.util.List;

/**
 * Grundklasse aller Bedienelemente (docs/59 §4). Koordinaten in logischen GUI-Einheiten; Lage setzt der Screen in
 * {@link Screen#layout}. Fokus, Hover und Tooltips verwaltet der {@link ScreenManager}.
 */
public abstract class Widget {
	private final String id;
	private Rect bounds = Rect.EMPTY;
	private boolean visible = true;
	private boolean enabled = true;
	private String tooltip;
	private Widget parent;

	protected Widget(final String id) {
		this.id = id;
	}

	public final String id() {
		return this.id;
	}

	public Rect bounds() {
		return this.bounds;
	}

	public void setBounds(final Rect r) {
		this.bounds = r;
	}

	public final void setBounds(final double x, final double y, final double w, final double h) {
		setBounds(new Rect(x, y, w, h));
	}

	public boolean visible() {
		return this.visible;
	}

	public void setVisible(final boolean v) {
		this.visible = v;
	}

	public boolean enabled() {
		return this.enabled;
	}

	public void setEnabled(final boolean e) {
		this.enabled = e;
	}

	public String tooltip() {
		return this.tooltip;
	}

	public Widget setTooltip(final String t) {
		this.tooltip = t;
		return this;
	}

	public Widget parent() {
		return this.parent;
	}

	void setParent(final Widget p) {
		this.parent = p;
	}

	/** Kann den Tastaturfokus erhalten. */
	public boolean focusable() {
		return this.visible && this.enabled;
	}

	public abstract void render(UiCanvas c, UiContext ctx);

	/** Kinder (Container); in Fokusreihenfolge. */
	public List<Widget> children() {
		return List.of();
	}

	/** Oberstes Widget an (x, y) – Container liefern ihr Kind. */
	public Widget hit(final double x, final double y) {
		return this.visible && this.bounds.contains(x, y) ? this : null;
	}

	public boolean mousePressed(final double x, final double y, final int button, final UiContext ctx) {
		return false;
	}

	public void mouseReleased(final double x, final double y, final int button, final UiContext ctx) {
	}

	public void mouseDragged(final double x, final double y, final UiContext ctx) {
	}

	public boolean scrolled(final double x, final double y, final double amount, final UiContext ctx) {
		return false;
	}

	/** Menüaktion bei Fokus (Bestätigen, Links/Rechts …); true = verbraucht. */
	public boolean action(final GameAction a, final UiContext ctx) {
		return false;
	}

	/** Rohe Taste bei Fokus (Textfelder, Tastenerfassung); true = verbraucht. */
	public boolean keyPressed(final int key, final int mods, final UiContext ctx) {
		return false;
	}

	public boolean charTyped(final int codepoint, final UiContext ctx) {
		return false;
	}

	/** Widget will Pfeiltasten selbst (z. B. offene Auswahlliste). */
	public boolean capturesNavigation() {
		return false;
	}

	/** Kurzbeschreibung für den Autotest-Bericht {@code ui-dump}. */
	public String describe() {
		return getClass().getSimpleName() + " " + this.id;
	}

	/** Hintergrund/Rahmen eines Knopfes im aktuellen Zustand (gemeinsam für Button, Dropdown, Tastenfeld). */
	protected static void drawButtonFrame(final UiCanvas c, final UiContext ctx, final Widget w, final boolean pressed) {
		Theme t = ctx.theme();
		Rect r = w.bounds();
		boolean hover = ctx.hovered() == w && w.enabled();
		int bg = !w.enabled() ? t.buttonDisabled() : pressed ? t.buttonPressed() : hover ? t.buttonHover() : t.button();
		c.fill(r, bg);
		boolean focused = ctx.focused() == w && ctx.showFocus();
		double line = lineWidth(c);
		c.border(r, focused ? 2 * line : line, focused ? t.focus() : hover ? t.accent() : t.panelBorder());
	}

	/** Rahmenbreite: mindestens 1 physisches Pixel, mit der Skalierung wachsend. */
	protected static double lineWidth(final UiCanvas c) {
		return Math.max(1, Math.round(c.scale() / 2.0)) * c.hairline();
	}

	protected static int textColor(final UiContext ctx, final Widget w) {
		return w.enabled() ? ctx.theme().text() : ctx.theme().textDisabled();
	}
}
