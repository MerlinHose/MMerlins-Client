package dev.mcresearch.protocolclient.ui;

import dev.mcresearch.protocolclient.input.GameAction;
import dev.mcresearch.protocolclient.input.InputCode;
import dev.mcresearch.protocolclient.input.KeyBindings;
import dev.mcresearch.protocolclient.input.Keys;
import dev.mcresearch.protocolclient.settings.SettingsManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Tastenbelegung (docs/60 §5): je frei belegbarer Aktion eine Zeile mit Bezeichnung, Belegungsknopf und „Zurücksetzen“.
 * Klick/Bestätigen auf den Belegungsknopf wartet auf die nächste Taste, Maustaste oder Mausradbewegung; Esc setzt wie in Vanilla
 * „Nicht belegt“. Konflikte werden rot markiert und im Tooltip erklärt.
 */
public final class KeyBindingEditor extends ScrollContainer {
	private final SettingsManager settings;
	private final List<KeyButton> keyButtons = new ArrayList<>();
	private String message = "";

	public KeyBindingEditor(final String id, final SettingsManager settings) {
		super(id);
		this.settings = settings;
	}

	/** Baut die Zeilen für die Breite {@code w} neu auf. */
	public void build(final double w) {
		clear();
		this.keyButtons.clear();
		double y = 0;
		GameAction.Category cat = null;
		double rowH = 20;
		double inner = w - 8;
		double keyW = Math.min(130, inner * 0.36);
		double resetW = Math.min(80, inner * 0.2);
		double labelW = inner - keyW - resetW - 8;
		for (GameAction a : GameAction.values()) {
			if (!a.rebindable) {
				continue;
			}
			if (a.category != cat) {
				cat = a.category;
				add(new TextLabel(id() + ".cat." + cat.name().toLowerCase(java.util.Locale.ROOT), cat.label).style(TextLabel.Style.DIM),
					new Rect(0, y + 2, inner, 12));
				y += 15;
			}
			String label = a.label + (a.supported() ? "" : " (geplant)");
			add(new TextLabel(id() + ".label." + a.id(), label), new Rect(0, y, labelW, rowH));
			KeyButton kb = add(new KeyButton(this, a), new Rect(labelW + 4, y, keyW, rowH));
			this.keyButtons.add(kb);
			Button reset = add(new Button(id() + ".reset." + a.id(), "Zurücksetzen", b -> {
				this.settings.resetBinding(a);
				refresh();
			}), new Rect(labelW + 8 + keyW, y, resetW, rowH));
			reset.setTooltip("Standard: " + a.defaultCode.displayName());
			y += rowH + 3;
		}
		setContentHeight(y);
		refresh();
	}

	/** Beschriftungen, Konfliktmarkierung und Zurücksetzen-Knöpfe an die Belegung angleichen. */
	public void refresh() {
		KeyBindings b = this.settings.bindings();
		for (KeyButton kb : this.keyButtons) {
			List<GameAction> conflicts = b.conflictsOf(kb.action);
			kb.conflict = !conflicts.isEmpty();
			kb.setTooltip(conflicts.isEmpty() ? kb.action.label + ": " + b.get(kb.action).displayName()
				: "Konflikt: " + b.get(kb.action).displayName() + " ist auch belegt mit " + String.join(", ",
				conflicts.stream().map(c -> c.label).toList()));
			Widget reset = findChild(id() + ".reset." + kb.action.id());
			if (reset != null) {
				reset.setEnabled(!b.isDefault(kb.action));
			}
		}
	}

	private Widget findChild(final String cid) {
		for (Widget w : children()) {
			if (w.id().equals(cid)) {
				return w;
			}
		}
		return null;
	}

	/** Zahl der Aktionen mit Konflikt (Anzeige und Tests). */
	public int conflictCount() {
		int n = 0;
		for (KeyButton kb : this.keyButtons) {
			n += kb.conflict ? 1 : 0;
		}
		return n;
	}

	public String message() {
		return this.message;
	}

	void bind(final KeyButton kb, final InputCode code, final UiContext ctx) {
		KeyBindings.Change c = this.settings.bind(kb.action, code);
		this.message = switch (c) {
			case OK -> kb.action.label + " → " + code.displayName();
			case RESERVED -> code.displayName() + " ist fest belegt (Pause/Menü).";
			case NOT_REBINDABLE -> kb.action.label + " ist nicht frei belegbar.";
			case UNSUPPORTED_DEVICE -> "Diese Eingabe kann nicht belegt werden.";
		};
		kb.capturing = false;
		ctx.releaseCapture();
		refresh();
		ctx.markDirty();
	}

	/** Belegungsknopf einer Aktion. */
	public static final class KeyButton extends Widget {
		private final KeyBindingEditor editor;
		private final GameAction action;
		private boolean capturing;
		private boolean conflict;

		KeyButton(final KeyBindingEditor editor, final GameAction action) {
			super(editor.id() + ".key." + action.id());
			this.editor = editor;
			this.action = action;
		}

		public GameAction action() {
			return this.action;
		}

		public boolean capturing() {
			return this.capturing;
		}

		public boolean conflict() {
			return this.conflict;
		}

		void startCapture(final UiContext ctx) {
			this.capturing = true;
			ctx.capture(this);
			ctx.markDirty();
		}

		@Override
		public boolean mousePressed(final double x, final double y, final int button, final UiContext ctx) {
			if (this.capturing) {
				this.editor.bind(this, InputCode.mouse(button), ctx);
				return true;
			}
			if (button == 0) {
				startCapture(ctx);
				return true;
			}
			return false;
		}

		@Override
		public boolean scrolled(final double x, final double y, final double amount, final UiContext ctx) {
			if (this.capturing && amount != 0) {
				this.editor.bind(this, InputCode.wheel(amount > 0 ? InputCode.WHEEL_UP : InputCode.WHEEL_DOWN), ctx);
				return true;
			}
			return false;
		}

		@Override
		public boolean action(final GameAction a, final UiContext ctx) {
			if (a == GameAction.GUI_CONFIRM && !this.capturing) {
				startCapture(ctx);
				return true;
			}
			return false;
		}

		@Override
		public boolean keyPressed(final int key, final int mods, final UiContext ctx) {
			if (!this.capturing) {
				return false;
			}
			this.editor.bind(this, key == Keys.ESCAPE ? InputCode.NONE : InputCode.key(key), ctx);
			return true;
		}

		@Override
		public void render(final UiCanvas c, final UiContext ctx) {
			drawButtonFrame(c, ctx, this, this.capturing);
			String text = this.capturing ? "> Taste drücken <" : this.editor.settings.bindings().get(this.action).displayName();
			int color = this.capturing ? ctx.theme().focus() : this.conflict ? ctx.theme().error() : textColor(ctx, this);
			c.textIn(text, bounds().inset(4), UiCanvas.Align.CENTER, color, true);
		}

		@Override
		public String describe() {
			return "KeyButton " + id() + " " + this.editor.settings.bindings().get(this.action).serialize() + (this.conflict ? " CONFLICT" : "")
				+ (this.capturing ? " CAPTURING" : "");
		}
	}
}
