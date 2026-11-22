package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.settings.Setting;
import dev.mcresearch.protocolclient.settings.Settings;
import dev.mcresearch.protocolclient.settings.SettingsManager;
import dev.mcresearch.protocolclient.ui.Button;
import dev.mcresearch.protocolclient.ui.Checkbox;
import dev.mcresearch.protocolclient.ui.Dialog;
import dev.mcresearch.protocolclient.ui.Dropdown;
import dev.mcresearch.protocolclient.ui.KeyBindingEditor;
import dev.mcresearch.protocolclient.ui.Rect;
import dev.mcresearch.protocolclient.ui.ScreenManager;
import dev.mcresearch.protocolclient.ui.ScrollContainer;
import dev.mcresearch.protocolclient.ui.Slider;
import dev.mcresearch.protocolclient.ui.TabNavigation;
import dev.mcresearch.protocolclient.ui.TextLabel;
import dev.mcresearch.protocolclient.ui.Widget;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Einstellungen (docs/60 §2): Reiter Grafik, Steuerung, Tastenbelegung, Oberfläche. Jede Änderung wirkt sofort (Listener der
 * Anwendung) und wird beim Verlassen gespeichert; schlägt das Speichern fehl, erscheint eine Meldung, die Werte bleiben aktiv.
 * Nicht umgesetzte Optionen sind gesperrt mit „geplant“ gekennzeichnet.
 */
public final class SettingsScreen extends MenuScreen {
	static final List<String> TABS = List.of("Grafik", "Steuerung", "Tastenbelegung", "Oberfläche");
	private TabNavigation tabs;
	private ScrollContainer content;
	private KeyBindingEditor keys;
	private TextLabel status;
	private Button reset;
	private Button done;
	private int tab;
	private final List<Widget> rows = new ArrayList<>();

	public SettingsScreen(final AppServices app) {
		super(app);
	}

	@Override
	public String name() {
		return "settings";
	}

	@Override
	public String title() {
		return "Einstellungen";
	}

	@Override
	public boolean overlaysWorld() {
		return this.app.inGame();
	}

	@Override
	protected void init() {
		this.tabs = add(new TabNavigation("tabs", TABS, 0, i -> {
			this.tab = i;
			layout(width(), height());
		}));
		this.content = add(new ScrollContainer("settings-content"));
		this.keys = add(new KeyBindingEditor("keys", this.app.settings()));
		this.status = add(new TextLabel("settings-status", "").style(TextLabel.Style.DIM));
		this.reset = button("reset", "Alle zurücksetzen", b -> confirmReset());
		this.done = button("done", "Fertig", b -> finish());
		SettingsManager s = this.app.settings();
		if (!s.notes().isEmpty()) {
			this.status.setText(s.notes().getLast());
			this.status.style(TextLabel.Style.WARNING);
		}
	}

	/** Aktiver Reiter (Autotest). */
	public int tab() {
		return this.tab;
	}

	private void confirmReset() {
		String scope = TABS.get(this.tab);
		manager().showDialog(new Dialog("confirm-reset", "Zurücksetzen?", "Alle Einstellungen (alle Reiter, auch die Tastenbelegung) werden auf "
			+ "die Standardwerte gesetzt. Aktueller Reiter: " + scope + ".", false, List.of(
			new Dialog.Choice("confirm-reset.yes", "Zurücksetzen", () -> {
				this.app.settings().resetToDefaults(null);
				this.status.setText("Alle Einstellungen auf Standard gesetzt.");
				this.status.style(TextLabel.Style.OK);
				layout(width(), height());
			}), new Dialog.Choice("confirm-reset.no", "Abbrechen", () -> {
			}))));
	}

	private void finish() {
		SettingsManager.SaveResult r = this.app.settings().saveIfDirty();
		if (!r.ok()) {
			manager().showDialog(new Dialog("save-failed", "Speichern fehlgeschlagen", r.message()
				+ " Die Einstellungen bleiben bis zum Beenden aktiv.", true, List.of(new Dialog.Choice("save-failed.ok", "OK", () -> manager().back()))));
			return;
		}
		manager().back();
	}

	@Override
	public void onBack() {
		finish();
	}

	@Override
	public void tick() {
		if (this.tab == 2) {
			int n = this.keys.conflictCount();
			String msg = this.keys.message();
			String text = (msg.isEmpty() ? "" : msg + "  ·  ") + (n == 0 ? "Keine Konflikte" : n + " Aktionen mit Tastenkonflikt (rot)");
			if (!text.equals(this.status.text())) {
				this.status.setText(text);
				this.status.style(n == 0 ? TextLabel.Style.DIM : TextLabel.Style.WARNING);
				manager().markDirty();
			}
		}
	}

	@Override
	protected void layout(final double w, final double h) {
		double cw = Math.min(420, w - 16);
		double x = (w - cw) / 2;
		double top = titleY() + 16;
		this.tabs.setBounds(new Rect(x, top, cw, 18));
		double bodyTop = top + 22;
		double bodyH = Math.max(30, h - bodyTop - BUTTON_H - 22);
		Rect body = new Rect(x, bodyTop, cw, bodyH);
		this.status.setBounds(new Rect(x, bodyTop + bodyH + 2, cw, 11));
		row(this.reset, this.done, w / 2, h - BUTTON_H - 6, cw);
		boolean keyTab = this.tab == 2;
		this.keys.setVisible(keyTab);
		this.content.setVisible(!keyTab);
		if (keyTab) {
			this.keys.setBounds(body);
			this.keys.build(cw);
		} else {
			this.content.setBounds(body);
			buildContent(this.content.innerWidth());
		}
	}

	private void buildContent(final double cw) {
		double scroll = this.content.scroll();
		this.content.clear();
		this.rows.clear();
		List<Widget> ws = new ArrayList<>();
		switch (this.tab) {
			case 0 -> graphics(ws);
			case 1 -> controls(ws);
			default -> interfaceTab(ws);
		}
		double y = 0;
		double half = (cw - GAP) / 2;
		int col = 0;
		for (Widget wdg : ws) {
			boolean full = wdg instanceof TextLabel;
			if (full) {
				if (col == 1) {
					y += BUTTON_H + GAP;
					col = 0;
				}
				this.content.add(wdg, new Rect(0, y + 2, cw, 11));
				y += 15;
				continue;
			}
			this.content.add(wdg, new Rect(col == 0 ? 0 : half + GAP, y, half, BUTTON_H));
			this.rows.add(wdg);
			col++;
			if (col == 2) {
				col = 0;
				y += BUTTON_H + GAP;
			}
		}
		if (col == 1) {
			y += BUTTON_H + GAP;
		}
		this.content.setContentHeight(y);
		this.content.scrollTo(scroll);
	}

	// ------------------------------------------------------------------ Reiter

	private void graphics(final List<Widget> ws) {
		SettingsManager s = this.app.settings();
		ws.add(dropdown(Settings.DISPLAY_MODE));
		List<Dropdown.Option> res = new ArrayList<>();
		res.add(new Dropdown.Option("auto", "Desktop", true));
		for (String m : this.app.fullscreenModes()) {
			res.add(new Dropdown.Option(m, m, true));
		}
		String cur = s.get(Settings.FULLSCREEN_RESOLUTION);
		if (res.stream().noneMatch(o -> o.value().equals(cur))) {
			res.add(new Dropdown.Option(cur, cur + " (nicht verfügbar)", true));
		}
		Dropdown rd = new Dropdown("graphics.fullscreenResolution", "Vollbild", res, cur, v -> s.set(Settings.FULLSCREEN_RESOLUTION, v));
		rd.setTooltip(Settings.FULLSCREEN_RESOLUTION.help());
		ws.add(rd);
		ws.add(checkbox(Settings.VSYNC));
		ws.add(slider(Settings.MAX_FPS, 10, v -> v >= Settings.UNLIMITED_FPS ? "Unbegrenzt" : (int) v + " FPS"));
		ws.add(slider(Settings.RENDER_DISTANCE, 1, v -> (int) v + " Chunks"));
		ws.add(slider(Settings.FOV, 1, v -> (int) v + "°"));
		ws.add(sliderNum(Settings.BRIGHTNESS, 0.01, v -> v <= 0 ? "Düster" : v >= 1 ? "Hell" : Math.round(v * 100) + " %"));
		ws.add(dropdown(Settings.PARTICLES));
		int maxScale = ScreenManager.maxScale(manager().pixelWidth(), manager().pixelHeight());
		Slider gui = slider(Settings.GUI_SCALE, 1, v -> v == 0 ? "Auto (" + ScreenManager.computeScale(0, manager().pixelWidth(),
			manager().pixelHeight()) + ")" : (int) v + (v > maxScale ? " (zu groß → " + maxScale + ")" : ""));
		ws.add(gui);
		List<Dropdown.Option> clouds = new ArrayList<>();
		clouds.add(new Dropdown.Option("OFF", "Aus", true));
		clouds.add(new Dropdown.Option("FAST", "Schnell", false));
		clouds.add(new Dropdown.Option("FANCY", "Schön", true));
		Dropdown cd = new Dropdown(Settings.CLOUDS.key(), Settings.CLOUDS.label(), clouds, s.get(Settings.CLOUDS), v -> s.set(Settings.CLOUDS, v));
		cd.setTooltip(Settings.CLOUDS.help());
		ws.add(cd);
		ws.add(slider(Settings.BIOME_BLEND, 1, v -> v == 0 ? "Aus" : (2 * (int) v + 1) + "×" + (2 * (int) v + 1)));
		ws.add(checkbox(Settings.AMBIENT_OCCLUSION));
		planned(ws, Setting.Category.GRAPHICS);
	}

	private void controls(final List<Widget> ws) {
		ws.add(sliderNum(Settings.SENSITIVITY, 0.005, v -> v <= 0 ? "Sehr langsam" : Math.round(v * 200) + " %"));
		ws.add(checkbox(Settings.INVERT_Y));
		ws.add(checkbox(Settings.TOGGLE_SPRINT));
		ws.add(checkbox(Settings.TOGGLE_SNEAK));
		Button keys = new Button("open-keys", "Tastenbelegung …", b -> this.tabs.select(2));
		ws.add(keys);
		planned(ws, Setting.Category.CONTROLS);
	}

	private void interfaceTab(final List<Widget> ws) {
		ws.add(checkbox(Settings.SHOW_HUD));
		ws.add(checkbox(Settings.DEBUG_OVERLAY));
		ws.add(checkbox(Settings.TOOLTIPS));
		ws.add(new TextLabel("cat.accessibility", "Barrierefreiheit").style(TextLabel.Style.DIM));
		ws.add(checkbox(Settings.HIGH_CONTRAST));
		ws.add(checkbox(Settings.REDUCE_MOTION));
		planned(ws, Setting.Category.INTERFACE);
		planned(ws, Setting.Category.ACCOUNT);
		SettingsManager s = this.app.settings();
		ws.add(new TextLabel("settings-file", "Datei: " + (s.file() == null ? "nur im Speicher (Testmodus)" : s.file().toString()))
			.style(TextLabel.Style.DIM));
	}

	private void planned(final List<Widget> ws, final Setting.Category c) {
		for (Settings.Planned p : Settings.PLANNED) {
			if (p.category() == c) {
				Button b = new Button("planned." + p.label().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"), p.label() + ": geplant", x -> {
				});
				b.setEnabled(false);
				b.setTooltip(p.reason());
				ws.add(b);
			}
		}
	}

	// ------------------------------------------------------------------ Fabriken

	private Checkbox checkbox(final Setting.Bool st) {
		Checkbox c = new Checkbox(st.key(), st.label(), this.app.settings().get(st), v -> this.app.settings().set(st, v));
		c.setTooltip(st.help());
		return c;
	}

	private Slider slider(final Setting.Int st, final double step, final java.util.function.DoubleFunction<String> fmt) {
		Slider sl = new Slider(st.key(), st.label(), st.min, st.max, step, this.app.settings().get(st), fmt,
			v -> this.app.settings().set(st, (int) Math.round(v)));
		sl.setTooltip(st.help());
		return sl;
	}

	private Slider sliderNum(final Setting.Num st, final double step, final java.util.function.DoubleFunction<String> fmt) {
		Slider sl = new Slider(st.key(), st.label(), st.min, st.max, step, this.app.settings().get(st), fmt,
			v -> this.app.settings().set(st, Math.max(st.min, Math.min(st.max, v))));
		sl.setTooltip(st.help());
		return sl;
	}

	private Dropdown dropdown(final Setting.Choice st) {
		List<Dropdown.Option> opts = new ArrayList<>();
		for (int i = 0; i < st.values.size(); i++) {
			opts.add(new Dropdown.Option(st.values.get(i), st.names.get(i), true));
		}
		Dropdown d = new Dropdown(st.key(), st.label(), opts, this.app.settings().get(st), v -> this.app.settings().set(st, v));
		d.setTooltip(st.help());
		return d;
	}
}
