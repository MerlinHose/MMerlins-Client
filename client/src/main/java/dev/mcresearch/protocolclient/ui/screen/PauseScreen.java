package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.ui.Button;
import dev.mcresearch.protocolclient.ui.Rect;
import dev.mcresearch.protocolclient.ui.TextLabel;
import dev.mcresearch.protocolclient.ui.UiCanvas;
import java.util.List;

/**
 * Pausenmenü (docs/61 §3). Die Verbindung läuft weiter: Netzwerk-, Tick- und Keep-Alive-Verarbeitung der Sitzung sind
 * unabhängig von den Menüs; nur Spielwelteingaben sind gesperrt.
 */
public final class PauseScreen extends MenuScreen {
	private Button resume;
	private Button settings;
	private Button disconnect;
	private Button mainMenu;
	private TextLabel hint;

	public PauseScreen(final AppServices app) {
		super(app);
	}

	@Override
	public String name() {
		return "pause";
	}

	@Override
	public String title() {
		return "Spielmenü";
	}

	@Override
	public boolean overlaysWorld() {
		return true;
	}

	@Override
	protected void init() {
		this.resume = button("resume", "Zurück zum Spiel", b -> this.app.resumeGame());
		this.settings = button("settings", "Einstellungen", b -> manager().open(new SettingsScreen(this.app)));
		this.disconnect = button("disconnect", "Verbindung trennen", b -> this.app.disconnect(false));
		this.disconnect.setTooltip("Sitzung beenden; danach erneut verbinden oder zum Hauptmenü.");
		this.mainMenu = button("main-menu", "Zum Hauptmenü", b -> this.app.disconnect(true));
		this.mainMenu.setTooltip("Verbindung trennen und direkt zum Hauptmenü.");
		this.hint = add(new TextLabel("pause-hint", "Mehrspieler: Der Server läuft weiter, während dieses Menü offen ist.")
			.style(TextLabel.Style.DIM).align(UiCanvas.Align.CENTER));
	}

	@Override
	public void onBack() {
		this.app.resumeGame();
	}

	@Override
	protected void layout(final double w, final double h) {
		double cw = columnWidth(200, w);
		double y = Math.max(titleY() + 20, h * 0.3);
		y = column(List.of(this.resume, this.settings, this.disconnect, this.mainMenu), w / 2, y, cw);
		this.hint.setBounds(new Rect(8, Math.min(h - 14, y + 6), w - 16, 11));
	}
}
