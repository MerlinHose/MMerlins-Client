package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.app.ServerEntry;
import dev.mcresearch.protocolclient.ui.Button;
import dev.mcresearch.protocolclient.ui.Rect;
import dev.mcresearch.protocolclient.ui.TextLabel;
import dev.mcresearch.protocolclient.ui.UiCanvas;

/** Nach dem Trennen (durch den Spieler oder den Server): Meldung, „Erneut verbinden“, „Hauptmenü“. */
public final class DisconnectedScreen extends MenuScreen {
	private final String heading;
	private final String detail;
	private final ServerEntry target;
	private TextLabel text;
	private Button reconnect;
	private Button menu;

	public DisconnectedScreen(final AppServices app, final String heading, final String detail, final ServerEntry target) {
		super(app);
		this.heading = heading;
		this.detail = detail;
		this.target = target;
	}

	@Override
	public String name() {
		return "disconnected";
	}

	@Override
	public String title() {
		return this.heading;
	}

	@Override
	protected void init() {
		this.text = add(new TextLabel("disconnect-detail", this.detail).wrap(true).align(UiCanvas.Align.CENTER));
		this.reconnect = button("reconnect", "Erneut verbinden", b -> this.app.connect(this.target));
		this.reconnect.setEnabled(this.target != null);
		this.menu = button("main-menu", "Hauptmenü", b -> manager().reset(new MainMenuScreen(this.app)));
	}

	@Override
	public void onBack() {
		manager().reset(new MainMenuScreen(this.app));
	}

	@Override
	protected void layout(final double w, final double h) {
		double cw = Math.min(340, w - 16);
		double y = Math.max(titleY() + 22, h * 0.3);
		double th = manager().measure().wrap(this.detail, cw).size() * (manager().measure().lineHeight() + 1) + 2;
		this.text.setBounds(new Rect((w - cw) / 2, y, cw, th));
		row(this.reconnect, this.menu, w / 2, Math.min(h - BUTTON_H - 6, y + th + 12), cw);
	}
}
