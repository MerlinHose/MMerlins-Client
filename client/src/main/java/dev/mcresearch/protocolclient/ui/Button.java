package dev.mcresearch.protocolclient.ui;

import dev.mcresearch.protocolclient.input.GameAction;
import java.util.function.Consumer;

/** Schaltfläche: Klick (linke Maustaste beim Loslassen über dem Knopf) oder Bestätigen bei Fokus. */
public class Button extends Widget {
	private String label;
	private final Consumer<Button> onPress;
	private boolean down;
	private long presses;

	public Button(final String id, final String label, final Consumer<Button> onPress) {
		super(id);
		this.label = label;
		this.onPress = onPress;
	}

	public final String label() {
		return this.label;
	}

	public final void setLabel(final String l) {
		this.label = l;
	}

	public final long presses() {
		return this.presses;
	}

	/** Auslösen (auch vom Autotest über {@code ui-click}, dann aber über den echten Mausweg). */
	public final void press() {
		if (enabled()) {
			this.presses++;
			this.onPress.accept(this);
		}
	}

	@Override
	public boolean mousePressed(final double x, final double y, final int button, final UiContext ctx) {
		if (button != 0) {
			return false;
		}
		this.down = true;
		return true;
	}

	@Override
	public void mouseReleased(final double x, final double y, final int button, final UiContext ctx) {
		boolean was = this.down;
		this.down = false;
		if (was && bounds().contains(x, y)) {
			press();
		}
	}

	@Override
	public boolean action(final GameAction a, final UiContext ctx) {
		if (a == GameAction.GUI_CONFIRM) {
			press();
			return true;
		}
		return false;
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		drawButtonFrame(c, ctx, this, this.down);
		c.textIn(this.label, bounds().inset(4), UiCanvas.Align.CENTER, textColor(ctx, this), true);
	}

	@Override
	public String describe() {
		return "Button " + id() + " \"" + this.label + "\"";
	}
}
