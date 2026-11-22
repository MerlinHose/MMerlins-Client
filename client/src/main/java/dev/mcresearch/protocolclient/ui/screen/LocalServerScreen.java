package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.app.ProtocolVersions;
import dev.mcresearch.protocolclient.app.ServerEntry;
import dev.mcresearch.protocolclient.app.ServerStatus;
import dev.mcresearch.protocolclient.ui.Button;
import dev.mcresearch.protocolclient.ui.Container;
import dev.mcresearch.protocolclient.ui.Rect;
import dev.mcresearch.protocolclient.ui.TextLabel;
import java.util.concurrent.CompletableFuture;

/**
 * Einzelspieler / lokale Testwelt (docs/61 §1): nur der vorhandene lokale Vanilla-Testserver – kein eingebauter Server. Zeigt
 * Adresse und Erreichbarkeit (Statusping alle 5 s), betritt die Welt über den Verbindungsbildschirm.
 */
public final class LocalServerScreen extends MenuScreen {
	private static final long REPING_NANOS = 5_000_000_000L;
	private final ServerEntry entry = ServerEntry.localTestServer();
	private Container panel;
	private TextLabel name;
	private TextLabel status;
	private TextLabel detail;
	private TextLabel hint;
	private Button join;
	private Button refresh;
	private Button back;
	private CompletableFuture<ServerStatus> pending;
	private ServerStatus last = ServerStatus.UNKNOWN;
	private long lastPing;

	public LocalServerScreen(final AppServices app) {
		super(app);
	}

	@Override
	public String name() {
		return "local-server";
	}

	@Override
	public String title() {
		return "Einzelspieler / lokale Testwelt";
	}

	@Override
	protected void init() {
		this.panel = add(new Container("local-panel").panel(true));
		this.name = this.panel.add(new TextLabel("local-name", this.entry.name() + " – " + this.entry.address()));
		this.status = this.panel.add(new TextLabel("local-status", ServerStatus.UNKNOWN.summary()));
		this.detail = this.panel.add(new TextLabel("local-detail", "").wrap(true).style(TextLabel.Style.DIM));
		this.hint = add(new TextLabel("local-hint", "Der Testserver wird außerhalb des Clients gestartet (Ordner server/). Ein eingebauter "
			+ "Einzelspielerserver ist nicht Teil dieser Version.").wrap(true).style(TextLabel.Style.DIM));
		this.join = button("join", "Welt betreten", b -> this.app.connect(this.entry));
		this.refresh = button("refresh", "Erneut prüfen", b -> ping());
		this.back = button("back", "Zurück", b -> manager().back());
		ping();
	}

	private void ping() {
		this.last = ServerStatus.PINGING;
		this.lastPing = System.nanoTime();
		this.pending = this.app.pinger().ping(this.entry);
		update();
	}

	@Override
	public void tick() {
		CompletableFuture<ServerStatus> p = this.pending;
		if (p != null && p.isDone()) {
			this.pending = null;
			this.last = p.getNow(ServerStatus.offline("?"));
			update();
			manager().markDirty();
		} else if (p == null && System.nanoTime() - this.lastPing > REPING_NANOS) {
			ping();
		}
	}

	private void update() {
		this.status.setText("Status: " + this.last.summary());
		this.status.style(switch (this.last.state()) {
			case ONLINE -> this.last.support() == ProtocolVersions.Support.NATIVE ? TextLabel.Style.OK : TextLabel.Style.WARNING;
			case OFFLINE, BLOCKED -> TextLabel.Style.ERROR;
			default -> TextLabel.Style.DIM;
		});
		String d = switch (this.last.state()) {
			case ONLINE -> (this.last.motd().isBlank() ? "" : "„" + this.last.motd() + "“ – ")
				+ ProtocolVersions.describe(this.last.protocol(), this.last.versionName());
			case OFFLINE -> "Der Server antwortet nicht. Starte den lokalen Testserver und prüfe erneut.";
			default -> "";
		};
		this.detail.setText(d);
		this.join.setEnabled(this.last.state() != ServerStatus.State.BLOCKED && !this.app.connections().busy());
		this.join.setTooltip(this.last.state() == ServerStatus.State.ONLINE ? "Verbinden als " + this.app.settings()
			.get(dev.mcresearch.protocolclient.settings.Settings.OFFLINE_NAME) : "Verbindungsversuch trotzdem möglich; der Fehler wird angezeigt.");
		if (manager() != null && width() > 0) {
			layout(width(), height()); // Detailtext kann die Panelhöhe ändern
		}
	}

	@Override
	public void resumed() {
		update();
	}

	@Override
	protected void layout(final double w, final double h) {
		double cw = Math.min(360, w - 16);
		double x = (w - cw) / 2;
		double top = titleY() + 18;
		double lh = manager().measure().lineHeight() + 1;
		int detailLines = Math.max(2, manager().measure().wrap(this.detail.text(), cw - 16).size());
		double ph = 40 + detailLines * lh + 2;
		this.panel.setBounds(new Rect(x, top, cw, ph));
		this.name.setBounds(new Rect(x + 8, top + 6, cw - 16, 12));
		this.status.setBounds(new Rect(x + 8, top + 20, cw - 16, 12));
		this.detail.setBounds(new Rect(x + 8, top + 34, cw - 16, ph - 40));
		double hintH = manager().measure().wrap(this.hint.text(), cw).size() * (manager().measure().lineHeight() + 1) + 2;
		this.hint.setBounds(new Rect(x, top + ph + 6, cw, hintH));
		double by = Math.min(h - 2 * (BUTTON_H + GAP) - 4, top + ph + 12 + hintH);
		row(this.join, this.refresh, w / 2, by, cw);
		this.back.setBounds(new Rect(w / 2 - cw / 2, by + BUTTON_H + GAP, cw, BUTTON_H));
	}
}
