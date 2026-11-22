package dev.mcresearch.protocolclient.ui;

import dev.mcresearch.protocolclient.input.Keys;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Einzeiliges Eingabefeld: Zeichen, Rücktaste, Entf, Pfeile, Pos1/Ende, Strg+A/C/V/X. {@code filter} prüft jedes Zeichen,
 * {@code validator} den ganzen Text (ungültig → roter Rahmen, Wert wird dennoch angezeigt).
 */
public class TextInput extends Widget {
	private final String label;
	private final int maxLength;
	private final Predicate<Integer> filter;
	private Predicate<String> validator = s -> true;
	private final Consumer<String> onChange;
	private final StringBuilder text = new StringBuilder();
	private int cursor;
	private String placeholder = "";

	public TextInput(final String id, final String label, final String initial, final int maxLength, final Predicate<Integer> filter,
		final Consumer<String> onChange) {
		super(id);
		this.label = label;
		this.maxLength = maxLength;
		this.filter = filter;
		this.onChange = onChange;
		this.text.append(initial.length() > maxLength ? initial.substring(0, maxLength) : initial);
		this.cursor = this.text.length();
	}

	public final TextInput validator(final Predicate<String> v) {
		this.validator = v;
		return this;
	}

	public final TextInput placeholder(final String p) {
		this.placeholder = p;
		return this;
	}

	public final String text() {
		return this.text.toString();
	}

	public final boolean valid() {
		return this.validator.test(text());
	}

	public final void setText(final String s) {
		this.text.setLength(0);
		this.text.append(s.length() > this.maxLength ? s.substring(0, this.maxLength) : s);
		this.cursor = this.text.length();
	}

	public final String label() {
		return this.label;
	}

	private void changed() {
		this.onChange.accept(text());
	}

	/** Fügt Text an der Schreibmarke ein (gefiltert, Länge begrenzt). */
	public final void insert(final String s) {
		boolean any = false;
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			if (this.text.length() + Character.charCount(cp) > this.maxLength || !this.filter.test(cp) || cp < 0x20) {
				continue;
			}
			this.text.insert(this.cursor, Character.toChars(cp));
			this.cursor += Character.charCount(cp);
			any = true;
		}
		if (any) {
			changed();
		}
	}

	@Override
	public boolean mousePressed(final double x, final double y, final int button, final UiContext ctx) {
		if (button == 0) {
			this.cursor = this.text.length();
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(final int codepoint, final UiContext ctx) {
		insert(new String(Character.toChars(codepoint)));
		return true;
	}

	@Override
	public boolean keyPressed(final int key, final int mods, final UiContext ctx) {
		boolean ctrl = (mods & Keys.MOD_CONTROL) != 0;
		switch (key) {
			case Keys.BACKSPACE -> {
				if (this.cursor > 0) {
					int start = this.text.offsetByCodePoints(this.cursor, -1);
					this.text.delete(start, this.cursor);
					this.cursor = start;
					changed();
				}
				return true;
			}
			case Keys.DELETE -> {
				if (this.cursor < this.text.length()) {
					this.text.delete(this.cursor, this.text.offsetByCodePoints(this.cursor, 1));
					changed();
				}
				return true;
			}
			case Keys.LEFT -> {
				if (this.cursor > 0) {
					this.cursor = this.text.offsetByCodePoints(this.cursor, -1);
					return true;
				}
				return false; // am Anfang: Fokus darf wandern
			}
			case Keys.RIGHT -> {
				if (this.cursor < this.text.length()) {
					this.cursor = this.text.offsetByCodePoints(this.cursor, 1);
					return true;
				}
				return false;
			}
			case Keys.HOME -> {
				this.cursor = 0;
				return true;
			}
			case Keys.END -> {
				this.cursor = this.text.length();
				return true;
			}
			case Keys.SPACE -> {
				return true; // Leertaste ist Text, nicht „Bestätigen“ (das Zeichen kommt über charTyped)
			}
			default -> {
				if (ctrl && key == Keys.V) {
					insert(ctx.clipboard().get() == null ? "" : ctx.clipboard().get());
					return true;
				}
				if (ctrl && key == Keys.A) {
					this.cursor = this.text.length();
					return true;
				}
				if (ctrl && (key == 'C' || key == 'X')) {
					ctx.clipboard().set(text());
					if (key == 'X' && !this.text.isEmpty()) {
						this.text.setLength(0);
						this.cursor = 0;
						changed();
					}
					return true;
				}
				return false;
			}
		}
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		Rect r = bounds();
		Theme t = ctx.theme();
		c.fill(r, t.fieldBg());
		boolean focused = ctx.focused() == this;
		double line = lineWidth(c);
		c.border(r, focused ? 2 * line : line, !valid() ? t.error() : focused ? t.focus() : ctx.hovered() == this ? t.accent() : t.panelBorder());
		Rect inner = r.inset(5);
		String shown = this.text.isEmpty() && !focused ? this.placeholder : text();
		int color = this.text.isEmpty() && !focused ? t.textDim() : textColor(ctx, this);
		// lange Inhalte: das Ende (an der Schreibmarke) bleibt sichtbar, vorne wird abgeschnitten
		String before = this.text.substring(0, this.cursor);
		String visible = shown;
		double avail = inner.w() - 2;
		if (c.textWidth(visible) > avail) {
			int from = 0;
			while (from < before.length() && c.textWidth(shown.substring(from)) > avail && c.textWidth(before.substring(from)) > avail * 0.8) {
				from++;
			}
			visible = c.ellipsize(shown.substring(from), avail);
			before = before.substring(Math.min(from, before.length()));
		}
		c.textIn(visible, new Rect(inner.x(), inner.y(), avail, inner.h()), UiCanvas.Align.LEFT, color, false);
		if (focused && (ctx.nanos() / 500_000_000L) % 2 == 0) {
			double cx = inner.x() + Math.min(avail, c.textWidth(before));
			c.fill(new Rect(cx, inner.centerY() - c.lineHeight() / 2, line, c.lineHeight()), t.text());
		}
	}

	@Override
	public String describe() {
		return "TextInput " + id() + " \"" + text() + "\"" + (valid() ? "" : " invalid");
	}
}
