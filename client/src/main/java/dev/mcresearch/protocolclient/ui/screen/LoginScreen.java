package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.settings.Settings;
import dev.mcresearch.protocolclient.ui.Button;
import dev.mcresearch.protocolclient.ui.Rect;
import dev.mcresearch.protocolclient.ui.TextInput;
import dev.mcresearch.protocolclient.ui.TextLabel;

/**
 * Konto (docs/61 §2, Nachfolger des M13-Anmeldefensters im Spielfenster): Offline-Testmodus mit Spielername. Die echte
 * Microsoft-Anmeldung bleibt bis zum letzten Meilenstein zurückgestellt und ist hier nur gesperrt sichtbar; es werden keine
 * Tokens angezeigt oder gespeichert.
 */
public final class LoginScreen extends MenuScreen {
	private TextLabel offlineInfo;
	private TextInput name;
	private TextLabel message;
	private Button microsoft;
	private TextLabel microsoftInfo;
	private Button done;

	public LoginScreen(final AppServices app) {
		super(app);
	}

	@Override
	public String name() {
		return "login";
	}

	@Override
	public String title() {
		return "Konto";
	}

	@Override
	protected void init() {
		this.offlineInfo = add(new TextLabel("offline-info", "Offline-Testmodus für den lokalen Server (online-mode=false):").style(TextLabel.Style.DIM));
		this.name = add(new TextInput("offline-name", "Spielername", this.app.settings().get(Settings.OFFLINE_NAME), 16,
			cp -> cp < 128 && (Character.isLetterOrDigit(cp) || cp == '_'), s -> check()).validator(Settings.OFFLINE_NAME::accepts));
		this.message = add(new TextLabel("offline-msg", ""));
		this.microsoft = button("microsoft", "Mit Microsoft anmelden", b -> {
		});
		this.microsoft.setEnabled(false);
		this.microsoft.setTooltip("Zurückgestellt bis zum letzten Projektmeilenstein.");
		this.microsoftInfo = add(new TextLabel("microsoft-info", "Die echte Microsoft-Anmeldung ist in dieser Version deaktiviert und bis zum "
			+ "letzten Projektmeilenstein zurückgestellt.").wrap(true).style(TextLabel.Style.DIM));
		this.done = button("done", "Fertig", b -> manager().back());
		check();
	}

	private void check() {
		String n = this.name.text();
		if (Settings.OFFLINE_NAME.accepts(n)) {
			this.app.settings().set(Settings.OFFLINE_NAME, n);
			this.message.setText("Spielername: " + n);
			this.message.style(TextLabel.Style.OK);
		} else {
			this.message.setText("3–16 Zeichen: Buchstaben, Ziffern, Unterstrich.");
			this.message.style(TextLabel.Style.ERROR);
		}
	}

	@Override
	public void removed() {
		this.app.settings().saveIfDirty();
	}

	@Override
	protected void layout(final double w, final double h) {
		double cw = Math.min(320, w - 16);
		double x = (w - cw) / 2;
		double y = Math.max(titleY() + 20, h * 0.18);
		this.offlineInfo.setBounds(new Rect(x, y, cw, 11));
		this.name.setBounds(new Rect(x, y + 13, cw, BUTTON_H));
		this.message.setBounds(new Rect(x, y + 15 + BUTTON_H, cw, 11));
		this.microsoft.setBounds(new Rect(x, y + 34 + BUTTON_H, cw, BUTTON_H));
		double infoH = manager().measure().wrap(this.microsoftInfo.text(), cw).size() * (manager().measure().lineHeight() + 1) + 2;
		this.microsoftInfo.setBounds(new Rect(x, y + 36 + 2 * BUTTON_H, cw, infoH));
		this.done.setBounds(new Rect(x, Math.min(h - BUTTON_H - 6, y + 44 + 2 * BUTTON_H + infoH), cw, BUTTON_H));
	}
}
