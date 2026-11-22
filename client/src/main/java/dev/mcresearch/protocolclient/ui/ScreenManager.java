package dev.mcresearch.protocolclient.ui;

import dev.mcresearch.protocolclient.input.GameAction;
import dev.mcresearch.protocolclient.input.Keys;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Zentrale Screen-Verwaltung (docs/59 §3): Stapel offener Screens (öffnen, schließen, zurück), modale Dialoge, aufklappende
 * Listen, Tastatur- und Mausfokus, Tooltips, GUI-Skalierung und Fenstergröße. Kennt weder OpenGL noch GLFW: Eingaben kommen
 * als Pixelkoordinaten und Tastencodes, gezeichnet wird auf einen {@link UiCanvas}.
 */
public final class ScreenManager {
	/** Mindestfläche in logischen Einheiten für die automatische Skalierung (wie Vanilla {@code Window#calculateScale}). */
	public static final int MIN_W = 320;
	public static final int MIN_H = 240;
	private static final long TOOLTIP_DELAY_NANOS = 500_000_000L;

	private final Deque<Screen> stack = new ArrayDeque<>();
	private final Deque<Dialog> dialogs = new ArrayDeque<>();
	private final UiContext ctx = new UiContext(this);
	private final List<Consumer<Screen>> listeners = new ArrayList<>();
	private Widget popup;
	private Widget capture;
	private Widget pressed;
	private int pixelW = 1280;
	private int pixelH = 720;
	private int scaleSetting;
	private int scale = 2;
	private double mouseX = -1;
	private double mouseY = -1;
	private long hoverSince;
	private boolean tooltips = true;
	private boolean dirty = true;
	private long opened;
	private long closed;
	private UiCanvas measure = new EstimateCanvas();

	public UiContext context() {
		return this.ctx;
	}

	/** Zeichenfläche nur zum Messen von Text beim Layout (die Grafikschicht setzt ihre Java2D-Fläche ein). */
	public UiCanvas measure() {
		return this.measure;
	}

	public void setMeasure(final UiCanvas c) {
		this.measure = c;
		if (c instanceof Java2DCanvas j) {
			j.measureAt(this.scale);
		}
		this.dirty = true;
	}

	/** Wird bei jedem Wechsel des obersten Screens aufgerufen (null = kein Screen, Spielwelt aktiv). */
	public void addListener(final Consumer<Screen> l) {
		this.listeners.add(l);
	}

	// ------------------------------------------------------------------ Skalierung und Größe

	/** Vanilla-Regel: größte Stufe ≤ Wunsch (0 = unbegrenzt), bei der noch 320 × 240 Einheiten Platz haben; mindestens 1. */
	public static int computeScale(final int setting, final int pixelW, final int pixelH) {
		int s = 1;
		while ((setting == 0 || s < setting) && pixelW / (s + 1) >= MIN_W && pixelH / (s + 1) >= MIN_H) {
			s++;
		}
		return s;
	}

	/** Größte sinnvolle Stufe für dieses Fenster (für den Regler). */
	public static int maxScale(final int pixelW, final int pixelH) {
		return computeScale(0, pixelW, pixelH);
	}

	public void resize(final int pw, final int ph, final int guiScaleSetting) {
		int nw = Math.max(1, pw);
		int nh = Math.max(1, ph);
		int ns = computeScale(guiScaleSetting, nw, nh);
		if (nw == this.pixelW && nh == this.pixelH && ns == this.scale && guiScaleSetting == this.scaleSetting) {
			return;
		}
		this.pixelW = nw;
		this.pixelH = nh;
		this.scaleSetting = guiScaleSetting;
		this.scale = ns;
		if (this.measure instanceof Java2DCanvas j) {
			j.measureAt(ns);
		}
		this.ctx.closePopup();
		for (Screen s : this.stack) {
			s.doLayout(width(), height());
		}
		for (Dialog d : this.dialogs) {
			d.layoutIn(this, width(), height());
		}
		this.dirty = true;
	}

	public int scale() {
		return this.scale;
	}

	public double width() {
		return (double) this.pixelW / this.scale;
	}

	public double height() {
		return (double) this.pixelH / this.scale;
	}

	public int pixelWidth() {
		return this.pixelW;
	}

	public int pixelHeight() {
		return this.pixelH;
	}

	public void setTheme(final Theme t) {
		if (t != this.ctx.theme()) {
			this.ctx.setTheme(t);
			this.dirty = true;
		}
	}

	public void setTooltipsEnabled(final boolean b) {
		this.tooltips = b;
	}

	// ------------------------------------------------------------------ Stapel

	public Screen current() {
		return this.stack.peek();
	}

	public boolean hasScreen() {
		return !this.stack.isEmpty();
	}

	public List<Screen> stack() {
		return List.copyOf(this.stack);
	}

	/** Öffnet {@code s} über dem aktuellen Screen. */
	public void open(final Screen s) {
		Screen prev = current();
		if (prev != null) {
			prev.rememberFocus(this.ctx.focused() == null ? null : this.ctx.focused().id());
		}
		this.stack.push(s);
		s.attach(this);
		s.doLayout(width(), height());
		this.opened++;
		enter(s, null);
	}

	/** Ersetzt den aktuellen Screen (der alte wird endgültig geschlossen). */
	public void replace(final Screen s) {
		Screen old = this.stack.poll();
		if (old != null) {
			old.removed();
			this.closed++;
		}
		open(s);
	}

	/** Schließt den obersten Screen und kehrt zum vorherigen zurück. */
	public void back() {
		Screen old = this.stack.poll();
		if (old == null) {
			return;
		}
		old.removed();
		this.closed++;
		Screen now = current();
		if (now != null) {
			now.doLayout(width(), height());
			now.resumed();
		}
		enter(now, now == null ? null : now.rememberedFocus());
	}

	/** Schließt alle Screens (zurück in die Spielwelt). */
	public void closeAll() {
		while (!this.stack.isEmpty()) {
			Screen s = this.stack.poll();
			s.removed();
			this.closed++;
		}
		this.dialogs.clear();
		enter(null, null);
	}

	/** Schließt alle Screens und öffnet {@code s} als einzigen. */
	public void reset(final Screen s) {
		while (!this.stack.isEmpty()) {
			Screen o = this.stack.poll();
			o.removed();
			this.closed++;
		}
		this.dialogs.clear();
		open(s);
	}

	/** Entfernt einen bestimmten Screen (z. B. vom Spiel geschlossenes Inventar), egal an welcher Stelle. */
	public void remove(final Screen s) {
		if (current() == s) {
			back();
			return;
		}
		if (this.stack.remove(s)) {
			s.removed();
			this.closed++;
		}
	}

	private void enter(final Screen s, final String focusId) {
		this.ctx.closePopup();
		this.capture = null;
		this.pressed = null;
		this.ctx.setHovered(null);
		Widget f = null;
		if (s != null && focusId != null) {
			f = s.find(focusId);
		}
		this.ctx.setFocusedInternal(f);
		this.dirty = true;
		for (Consumer<Screen> l : this.listeners) {
			l.accept(s);
		}
	}

	public long openedCount() {
		return this.opened;
	}

	public long closedCount() {
		return this.closed;
	}

	// ------------------------------------------------------------------ Dialoge, Popups, Fokus

	public void showDialog(final Dialog d) {
		this.dialogs.push(d);
		d.layoutIn(this, width(), height());
		this.ctx.setFocusedInternal(d.defaultFocus());
		this.dirty = true;
	}

	public void closeDialog(final Dialog d) {
		this.dialogs.remove(d);
		this.ctx.setFocusedInternal(null);
		this.dirty = true;
	}

	public Dialog dialog() {
		return this.dialogs.peek();
	}

	void setPopup(final Widget p) {
		if (this.popup instanceof Dropdown.PopupList old && old != p) {
			old.owner().listClosed();
		}
		this.popup = p;
		this.dirty = true;
	}

	public Widget popup() {
		return this.popup;
	}

	void setCapture(final Widget w) {
		this.capture = w;
		this.dirty = true;
	}

	public Widget captureOwner() {
		return this.capture;
	}

	public void focus(final Widget w) {
		this.ctx.setFocusedInternal(w);
		ensureVisible(w);
		this.dirty = true;
	}

	private List<Widget> focusables() {
		if (this.popup != null) {
			List<Widget> out = new ArrayList<>();
			for (Widget w : this.popup.children()) {
				if (w.visible() && w.focusable()) {
					out.add(w);
				}
			}
			return out;
		}
		Dialog d = dialog();
		if (d != null) {
			List<Widget> out = new ArrayList<>();
			for (Widget w : d.children()) {
				if (w.visible() && w.focusable()) {
					out.add(w);
				}
			}
			return out;
		}
		Screen s = current();
		return s == null ? List.of() : s.focusOrder();
	}

	private void moveFocus(final int dir) {
		List<Widget> f = focusables();
		if (f.isEmpty()) {
			return;
		}
		int i = f.indexOf(this.ctx.focused());
		int n = i < 0 ? dir > 0 ? 0 : f.size() - 1 : Math.floorMod(i + dir, f.size());
		this.ctx.setShowFocus(true);
		focus(f.get(n));
	}

	private static void ensureVisible(final Widget w) {
		for (Widget p = w == null ? null : w.parent(); p != null; p = p.parent()) {
			if (p instanceof ScrollContainer sc) {
				sc.scrollToShow(w);
			}
		}
	}

	public void markDirty() {
		this.dirty = true;
	}

	/** Muss neu gezeichnet werden (Ereignis, Zustandsänderung, Größenänderung); setzt das Kennzeichen zurück. */
	public boolean consumeDirty() {
		boolean d = this.dirty;
		this.dirty = false;
		return d;
	}

	// ------------------------------------------------------------------ Eingabe

	/** Eingaben an das Spiel durchreichen (kein Screen oder ein vom Spiel bedienter). */
	public boolean gameHandlesInput() {
		Screen s = current();
		return s == null || s.handledByGame() && dialog() == null;
	}

	public boolean blocksWorldInput() {
		Screen s = current();
		return s != null && s.blocksWorldInput();
	}

	private Widget hitTest(final double x, final double y) {
		if (this.popup != null) {
			Widget h = this.popup.hit(x, y);
			if (h != null) {
				return h;
			}
		}
		Dialog d = dialog();
		if (d != null) {
			return d.hit(x, y);
		}
		Screen s = current();
		if (s == null) {
			return null;
		}
		List<Widget> ws = s.widgets();
		for (int i = ws.size() - 1; i >= 0; i--) {
			Widget h = ws.get(i).hit(x, y);
			if (h != null) {
				return h;
			}
		}
		return null;
	}

	/** Mausposition in Pixeln. */
	public void mouseMoved(final double px, final double py) {
		double x = px / this.scale, y = py / this.scale;
		if (x == this.mouseX && y == this.mouseY) {
			return;
		}
		this.mouseX = x;
		this.mouseY = y;
		if (this.pressed != null) {
			this.pressed.mouseDragged(x, y, this.ctx);
			this.dirty = true;
		}
		Widget h = hitTest(x, y);
		if (h != this.ctx.hovered()) {
			this.ctx.setHovered(h);
			this.hoverSince = this.ctx.nanos();
			this.dirty = true;
		}
	}

	/** Hover neu bestimmen, nachdem sich Inhalte unter der Maus bewegt haben (Scrollen). */
	public void mouseMovedRefresh() {
		Widget h = hitTest(this.mouseX, this.mouseY);
		if (h != this.ctx.hovered()) {
			this.ctx.setHovered(h);
			this.hoverSince = this.ctx.nanos();
		}
		this.dirty = true;
	}

	public void mouseButton(final boolean press, final int button, final double px, final double py) {
		mouseMoved(px, py);
		double x = this.mouseX, y = this.mouseY;
		this.ctx.setShowFocus(false);
		this.dirty = true;
		if (this.capture != null) {
			if (press) {
				this.capture.mousePressed(x, y, button, this.ctx);
			}
			return;
		}
		if (!press) {
			if (this.pressed != null) {
				this.pressed.mouseReleased(x, y, button, this.ctx);
				this.pressed = null;
			}
			return;
		}
		Widget h = hitTest(x, y);
		if (this.popup != null && (h == null || !isInside(h, this.popup))) {
			this.ctx.closePopup(); // Klick neben die Liste schließt sie
			return;
		}
		if (h == null) {
			this.ctx.setFocusedInternal(null);
			return;
		}
		if (h.focusable()) {
			this.ctx.setFocusedInternal(h);
		}
		if (h.enabled() && h.mousePressed(x, y, button, this.ctx)) {
			this.pressed = h;
		}
	}

	private static boolean isInside(final Widget w, final Widget root) {
		for (Widget p = w; p != null; p = p.parent()) {
			if (p == root) {
				return true;
			}
		}
		return false;
	}

	public void scrolled(final double px, final double py, final double amount) {
		mouseMoved(px, py);
		this.dirty = true;
		if (this.capture != null) {
			this.capture.scrolled(this.mouseX, this.mouseY, amount, this.ctx);
			return;
		}
		for (Widget w = hitTest(this.mouseX, this.mouseY); w != null; w = w.parent()) {
			if (w.enabled() && w.scrolled(this.mouseX, this.mouseY, amount, this.ctx)) {
				return;
			}
		}
	}

	/** Taste gedrückt (auch Wiederholung). */
	public void keyPressed(final int key, final int mods) {
		this.dirty = true;
		Widget f = this.capture != null ? this.capture : this.ctx.focused();
		if (f != null && f.keyPressed(key, mods, this.ctx)) {
			return;
		}
		GameAction a = switch (key) {
			case Keys.ESCAPE -> GameAction.GUI_BACK;
			case Keys.ENTER, Keys.KP_ENTER, Keys.SPACE -> GameAction.GUI_CONFIRM;
			case Keys.UP -> GameAction.GUI_UP;
			case Keys.DOWN -> GameAction.GUI_DOWN;
			case Keys.LEFT -> GameAction.GUI_LEFT;
			case Keys.RIGHT -> GameAction.GUI_RIGHT;
			case Keys.TAB -> GameAction.GUI_NEXT;
			default -> null;
		};
		if (a == GameAction.GUI_NEXT && (mods & Keys.MOD_SHIFT) != 0) {
			moveFocus(-1);
			return;
		}
		if (a != null) {
			action(a);
		}
	}

	public void charTyped(final int codepoint) {
		Widget f = this.ctx.focused();
		if (f != null && f.charTyped(codepoint, this.ctx)) {
			this.dirty = true;
		}
	}

	/** Menünavigation über Aktionen (Tastatur heute, Controller ab M17). */
	public void action(final GameAction a) {
		this.dirty = true;
		Widget f = this.ctx.focused();
		if (this.popup != null && a == GameAction.GUI_BACK) {
			Widget owner = this.popup instanceof Dropdown.PopupList pl ? pl.owner() : null;
			this.ctx.closePopup();
			if (owner != null) {
				focus(owner);
			}
			return;
		}
		if (f != null && f.enabled() && (f.capturesNavigation() || a == GameAction.GUI_CONFIRM || a == GameAction.GUI_LEFT || a == GameAction.GUI_RIGHT)
			&& f.action(a, this.ctx)) {
			this.ctx.setShowFocus(true);
			return;
		}
		switch (a) {
			case GUI_BACK -> {
				Dialog d = dialog();
				if (d != null) {
					d.cancel(this.ctx);
				} else if (current() != null) {
					current().onBack();
				}
			}
			case GUI_DOWN, GUI_NEXT, GUI_RIGHT -> moveFocus(1);
			case GUI_UP, GUI_LEFT -> moveFocus(-1);
			case GUI_CONFIRM -> {
				if (f == null) {
					moveFocus(1);
				}
			}
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------ Zeichnen

	/** Je Bild: Zeit, Tick des Screens; Tooltipverzögerung macht ggf. neu zeichnen nötig. */
	public void tick(final long nanos) {
		boolean tooltipBefore = tooltipDue();
		this.ctx.setNanos(nanos);
		Screen s = current();
		if (s != null) {
			s.tick();
			double hz = s.animationHz();
			if (this.ctx.focused() instanceof TextInput) {
				hz = Math.max(hz, 2); // Schreibmarke blinkt
			}
			if (hz > 0 && nanos - this.lastAnimation >= (long) (1e9 / hz)) {
				this.lastAnimation = nanos;
				this.dirty = true;
			}
		}
		if (tooltipDue() != tooltipBefore) {
			this.dirty = true;
		}
	}

	private long lastAnimation;

	private boolean tooltipDue() {
		Widget h = this.ctx.hovered();
		return this.tooltips && h != null && h.tooltip() != null && this.ctx.nanos() - this.hoverSince >= TOOLTIP_DELAY_NANOS
			&& this.popup == null && this.capture == null;
	}

	public void render(final UiCanvas c) {
		Screen s = current();
		if (s == null) {
			return;
		}
		s.render(c, this.ctx);
		for (Dialog d : this.dialogs.reversed()) {
			c.fill(new Rect(0, 0, width(), height()), 0x90000000);
			d.render(c, this.ctx);
		}
		if (this.popup != null) {
			this.popup.render(c, this.ctx);
		}
		if (tooltipDue()) {
			Tooltip.render(c, this.ctx, this.ctx.hovered().tooltip(), this.mouseX, this.mouseY, width(), height());
		}
	}

	/** Zustand für {@code ui-dump}: Screen-Stapel, Fokus, Hover, Widgets mit Lage. */
	public List<String> dump() {
		List<String> out = new ArrayList<>();
		out.add("stack " + this.stack.stream().map(Screen::name).toList() + " scale " + this.scale + " size " + this.pixelW + "x" + this.pixelH
			+ " logical " + Math.round(width()) + "x" + Math.round(height()) + " dialog " + (dialog() == null ? "-" : dialog().id())
			+ " focus " + (this.ctx.focused() == null ? "-" : this.ctx.focused().id()) + " hover " + (this.ctx.hovered() == null ? "-"
			: this.ctx.hovered().id()) + " opened " + this.opened + " closed " + this.closed);
		Screen s = current();
		if (s != null) {
			for (Widget w : s.focusOrder()) {
				Rect r = w.bounds();
				out.add(String.format(java.util.Locale.ROOT, "  %s [%.0f %.0f %.0f %.0f]%s", w.describe(), r.x(), r.y(), r.w(), r.h(),
					w.enabled() ? "" : " disabled"));
			}
		}
		return out;
	}

	/** Mitte eines Widgets in Pixeln (Autotest klickt über denselben Weg wie die echte Maus). */
	public double[] pixelCenter(final Widget w) {
		return new double[]{w.bounds().centerX() * this.scale, w.bounds().centerY() * this.scale};
	}

	/** Sucht ein Widget im aktuellen Dialog oder Screen. */
	public Widget find(final String id) {
		Dialog d = dialog();
		if (d != null) {
			for (Widget w : d.children()) {
				if (w.id().equals(id)) {
					return w;
				}
			}
		}
		if (this.popup != null) {
			for (Widget w : this.popup.children()) {
				if (w.id().equals(id)) {
					return w;
				}
			}
		}
		Screen s = current();
		return s == null ? null : s.find(id);
	}
}
