package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.app.MerlinVersion;
import dev.mcresearch.protocolclient.settings.Settings;
import dev.mcresearch.protocolclient.ui.Button;
import dev.mcresearch.protocolclient.ui.Rect;
import dev.mcresearch.protocolclient.ui.TextLabel;
import dev.mcresearch.protocolclient.ui.UiCanvas;
import dev.mcresearch.protocolclient.ui.UiContext;
import java.util.List;

/**
 * Hauptmenü (docs/61 §1): lokale Testwelt, Mehrspieler, Einstellungen, Konto, Über, Beenden. Hintergrund: eigener Verlauf mit
 * langsam treibenden Blockflächen (bei „Bewegung reduzieren“ still).
 */
public final class MainMenuScreen extends MenuScreen {
	private Button local;
	private Button multiplayer;
	private Button settings;
	private Button account;
	private Button about;
	private Button quit;
	private TextLabel version;
	private TextLabel notice;

	public MainMenuScreen(final AppServices app) {
		super(app);
	}

	@Override
	public String name() {
		return "main-menu";
	}

	@Override
	public String title() {
		return MerlinVersion.NAME;
	}

	@Override
	protected void init() {
		this.local = button("local", "Einzelspieler / lokale Testwelt", b -> manager().open(new LocalServerScreen(this.app)));
		this.local.setTooltip("Lokalen Vanilla-Testserver (127.0.0.1) anzeigen, prüfen und betreten.");
		this.multiplayer = button("multiplayer", "Mehrspieler", b -> manager().open(new MultiplayerScreen(this.app)));
		this.multiplayer.setTooltip("Serverliste (vorbereitet). Externe Server sind in dieser Version gesperrt.");
		this.settings = button("settings", "Einstellungen", b -> manager().open(new SettingsScreen(this.app)));
		this.account = button("account", "", b -> manager().open(new LoginScreen(this.app)));
		this.account.setTooltip("Spielername für den Offline-Testmodus; Microsoft-Anmeldung ist zurückgestellt.");
		this.about = button("about", "Über Merlin Client", b -> manager().open(new AboutScreen(this.app)));
		this.quit = button("quit", "Spiel beenden", b -> this.app.quit());
		this.version = add(new TextLabel("version", "Merlin " + MerlinVersion.CLIENT_VERSION + " · Minecraft " + MerlinVersion.MINECRAFT_VERSION)
			.style(TextLabel.Style.DIM));
		this.notice = add(new TextLabel("notice", "Nicht von Mojang oder Microsoft").style(TextLabel.Style.DIM).align(UiCanvas.Align.RIGHT));
	}

	@Override
	protected void layout(final double w, final double h) {
		this.account.setLabel("Konto: " + this.app.settings().get(Settings.OFFLINE_NAME) + " (offline)");
		double cw = columnWidth(220, w);
		double logoBottom = h * 0.30;
		double y = Math.max(logoBottom + 6, h * 0.36);
		double needed = 6 * (BUTTON_H + GAP) + 18;
		if (y + needed > h) {
			y = Math.max(40, h - needed);
		}
		y = column(List.of(this.local, this.multiplayer, this.settings, this.account, this.about), w / 2, y, cw);
		this.quit.setBounds(new Rect(w / 2 - cw / 2, y + 4, cw, BUTTON_H));
		double fw = Math.min(w / 2 - 6, 200);
		this.version.setBounds(new Rect(4, h - 12, fw, 10));
		this.notice.setBounds(new Rect(w - 4 - fw, h - 12, fw, 10));
	}

	@Override
	public void resumed() {
		this.account.setLabel("Konto: " + this.app.settings().get(Settings.OFFLINE_NAME) + " (offline)");
	}

	@Override
	public double animationHz() {
		return this.app.settings().get(Settings.REDUCE_MOTION) ? 0 : 15;
	}

	@Override
	public void onBack() {
		// Escape im Hauptmenü schließt nichts (Beenden nur über den Knopf)
	}

	@Override
	public void renderBackground(final UiCanvas c, final UiContext ctx) {
		super.renderBackground(c, ctx);
		// treibende, halbtransparente Blockflächen (eigene Gestaltung)
		double t = this.app.settings().get(Settings.REDUCE_MOTION) ? 0 : ctx.nanos() / 1e9;
		double cell = 24;
		int cols = (int) Math.ceil(width() / cell) + 2;
		int rows = (int) Math.ceil(height() / cell) + 2;
		double off = (t * 3) % cell;
		for (int r = 0; r < rows; r++) {
			for (int k = 0; k < cols; k++) {
				int hash = (r * 73856093) ^ (k * 19349663);
				if ((hash & 7) != 0) {
					continue;
				}
				int alpha = 0x10 + (hash >>> 4 & 0x0F);
				c.fill(new Rect(k * cell - off, r * cell - off * 0.5, cell - 2, cell - 2), alpha << 24 | 0x5FB4FF);
			}
		}
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		renderBackground(c, ctx);
		double logoH = Math.max(24, height() * 0.16);
		Rect logo = new Rect(8, height() * 0.30 - logoH - 6, width() - 16, logoH * 0.6);
		c.textLarge("MERLIN", logo, Math.min(4, Math.max(2, logoH / 14)), ctx.theme().accent());
		c.textIn("Minecraft Merlin Client", new Rect(8, logo.bottom() + 2, width() - 16, 12), UiCanvas.Align.CENTER, ctx.theme().text(), true);
		for (var w : widgets()) {
			if (w.visible()) {
				w.render(c, ctx);
			}
		}
	}
}
