package dev.mcresearch.protocolclient.ui;

/**
 * Laufzeitzustand, den Widgets beim Zeichnen und bei Eingaben brauchen (docs/59 §3): Stil, Hover, Fokus, Zwischenablage,
 * aufklappende Listen. Gehört dem {@link ScreenManager}.
 */
public final class UiContext {
	/** Zwischenablage des Systems (GLFW); in Tests ein Speicherwert. */
	public interface Clipboard {
		String get();

		void set(String s);
	}

	private final ScreenManager manager;
	private Theme theme = Theme.DEFAULT;
	private Widget hovered;
	private Widget focused;
	private boolean showFocus;
	private Clipboard clipboard = new Clipboard() {
		private String value = "";

		@Override
		public String get() {
			return this.value;
		}

		@Override
		public void set(final String s) {
			this.value = s;
		}
	};
	private long nanos;

	UiContext(final ScreenManager manager) {
		this.manager = manager;
	}

	public Theme theme() {
		return this.theme;
	}

	void setTheme(final Theme t) {
		this.theme = t;
	}

	public Widget hovered() {
		return this.hovered;
	}

	void setHovered(final Widget w) {
		this.hovered = w;
	}

	public Widget focused() {
		return this.focused;
	}

	void setFocusedInternal(final Widget w) {
		this.focused = w;
	}

	/** Fokusrahmen sichtbar (nach Tastaturbedienung; bei reiner Mausbedienung aus – wie Vanilla). */
	public boolean showFocus() {
		return this.showFocus;
	}

	void setShowFocus(final boolean s) {
		this.showFocus = s;
	}

	public Clipboard clipboard() {
		return this.clipboard;
	}

	public void setClipboard(final Clipboard c) {
		this.clipboard = c;
	}

	public long nanos() {
		return this.nanos;
	}

	void setNanos(final long n) {
		this.nanos = n;
	}

	/** Aufklappende Fläche über allen Widgets (Auswahlliste); null schließt. */
	public void openPopup(final Widget popup) {
		this.manager.setPopup(popup);
	}

	public void closePopup() {
		this.manager.setPopup(null);
	}

	/** Leitet alle Eingaben an {@code w} (Tastenerfassung), bis {@link #releaseCapture()}. */
	public void capture(final Widget w) {
		this.manager.setCapture(w);
	}

	public void releaseCapture() {
		this.manager.setCapture(null);
	}

	public void requestFocus(final Widget w) {
		this.manager.focus(w);
	}

	public void markDirty() {
		this.manager.markDirty();
	}

	public ScreenManager manager() {
		return this.manager;
	}
}
