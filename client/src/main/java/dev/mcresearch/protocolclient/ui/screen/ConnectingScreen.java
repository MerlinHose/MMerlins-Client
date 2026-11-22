package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.app.ConnectErrors;
import dev.mcresearch.protocolclient.app.ConnectionManager;
import dev.mcresearch.protocolclient.app.ConnectionManager.Phase;
import dev.mcresearch.protocolclient.app.ServerEntry;
import dev.mcresearch.protocolclient.ui.Button;
import dev.mcresearch.protocolclient.ui.Rect;
import dev.mcresearch.protocolclient.ui.TextLabel;
import dev.mcresearch.protocolclient.ui.UiCanvas;
import dev.mcresearch.protocolclient.ui.UiContext;
import java.util.List;

/**
 * Verbindungsbildschirm (docs/61 §4): zeigt die Phasen des tatsächlichen Zustandsautomaten (Server prüfen → Verbindung →
 * Anmeldung → Konfiguration → Welt laden), einen Abbrechen-Knopf und bei Fehlern eine verständliche Meldung mit
 * „Erneut versuchen“. Das Schließen nach erfolgreicher Verbindung übernimmt die Anwendung.
 */
public final class ConnectingScreen extends MenuScreen {
	private static final List<Phase> STEPS = List.of(Phase.CHECKING, Phase.CONNECTING, Phase.LOGIN, Phase.CONFIGURATION, Phase.LOADING_WORLD);
	private final ServerEntry target;
	private TextLabel phaseLabel;
	private TextLabel errorTitle;
	private TextLabel errorDetail;
	private Button cancel;
	private Button retry;
	private Button back;
	private Phase phase = Phase.CHECKING;
	private Phase highest = Phase.CHECKING;
	private final StringBuilder history = new StringBuilder();

	public ConnectingScreen(final AppServices app, final ServerEntry target) {
		super(app);
		this.target = target;
	}

	@Override
	public String name() {
		return "connecting";
	}

	@Override
	public String title() {
		return this.phase == Phase.FAILED ? "Fehler beim Verbinden" : "Verbinden mit " + this.target.name();
	}

	@Override
	protected void init() {
		this.phaseLabel = add(new TextLabel("phase", "").align(UiCanvas.Align.CENTER));
		this.errorTitle = add(new TextLabel("error-title", "").align(UiCanvas.Align.CENTER).style(TextLabel.Style.ERROR));
		this.errorDetail = add(new TextLabel("error-detail", "").wrap(true).align(UiCanvas.Align.CENTER));
		this.cancel = button("cancel", "Abbrechen", b -> {
			this.app.connections().cancel();
			manager().back();
		});
		this.retry = button("retry", "Erneut versuchen", b -> this.app.connect(this.target));
		this.back = button("back", "Zurück", b -> {
			this.app.connections().reset();
			manager().back();
		});
		tick();
	}

	public Phase phase() {
		return this.phase;
	}

	/** Durchlaufene Phasen (Autotest/Bericht). */
	public String history() {
		return this.history.toString();
	}

	@Override
	public void tick() {
		ConnectionManager cm = this.app.connections();
		Phase p = cm.phase();
		if (p != this.phase || this.history.isEmpty()) {
			this.phase = p;
			if (this.history.isEmpty() || !this.history.toString().endsWith(p.name())) {
				this.history.append(this.history.isEmpty() ? "" : ">").append(p.name());
			}
			if (STEPS.contains(p) && STEPS.indexOf(p) > STEPS.indexOf(this.highest)) {
				this.highest = p;
			}
			boolean failed = p == Phase.FAILED;
			ConnectErrors.UserError e = failed ? cm.error() : null;
			this.phaseLabel.setText(failed ? "" : p.label + " …");
			this.errorTitle.setText(e == null ? "" : e.title());
			this.errorDetail.setText(e == null ? "" : e.detail());
			this.cancel.setVisible(!failed);
			this.retry.setVisible(failed);
			this.back.setVisible(failed);
			if (manager() != null && width() > 0) {
				layout(width(), height());
				manager().markDirty();
			}
		}
	}

	@Override
	public double animationHz() {
		return this.phase.active() ? 4 : 0;
	}

	@Override
	public void onBack() {
		if (this.phase == Phase.FAILED) {
			this.app.connections().reset();
		} else {
			this.app.connections().cancel();
		}
		manager().back();
	}

	@Override
	protected void layout(final double w, final double h) {
		double cw = Math.min(340, w - 16);
		double x = (w - cw) / 2;
		double y = Math.max(titleY() + 22, h * 0.22);
		double stepsH = STEPS.size() * 12;
		this.phaseLabel.setBounds(new Rect(x, y + stepsH + 8, cw, 12));
		this.errorTitle.setBounds(new Rect(x, y + stepsH + 8, cw, 12));
		double dh = this.errorDetail.text().isEmpty() ? 12
			: manager().measure().wrap(this.errorDetail.text(), cw).size() * (manager().measure().lineHeight() + 1) + 2;
		this.errorDetail.setBounds(new Rect(x, y + stepsH + 24, cw, dh));
		double by = Math.min(h - BUTTON_H - 6, y + stepsH + 32 + dh);
		this.cancel.setBounds(new Rect(w / 2 - columnWidth(160, w) / 2, by, columnWidth(160, w), BUTTON_H));
		row(this.retry, this.back, w / 2, by, cw);
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		super.render(c, ctx);
		double cw = Math.min(340, width() - 16);
		double x = (width() - cw) / 2;
		double y = Math.max(titleY() + 22, height() * 0.22);
		c.textIn(this.target.address(), new Rect(x, y - 12, cw, 10), UiCanvas.Align.CENTER, ctx.theme().textDim(), false);
		int reached = STEPS.indexOf(this.highest);
		int dots = (int) (ctx.nanos() / 250_000_000L % 4);
		for (int i = 0; i < STEPS.size(); i++) {
			Phase s = STEPS.get(i);
			boolean done = i < reached || this.phase == Phase.CONNECTED;
			boolean current = s == this.phase;
			boolean failedHere = this.phase == Phase.FAILED && i == reached;
			String mark = failedHere ? "✗" : done ? "✓" : current ? "›" : "·";
			int color = failedHere ? ctx.theme().error() : done ? ctx.theme().ok() : current ? ctx.theme().focus() : ctx.theme().textDim();
			c.textIn(mark + " " + s.label + (current ? ".".repeat(dots) : ""), new Rect(x + cw * 0.2, y + i * 12, cw * 0.8, 11), UiCanvas.Align.LEFT,
				color, false);
		}
	}
}
