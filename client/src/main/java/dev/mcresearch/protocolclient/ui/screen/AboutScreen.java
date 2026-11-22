package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.app.MerlinVersion;
import dev.mcresearch.protocolclient.app.ProtocolVersions;
import dev.mcresearch.protocolclient.ui.Button;
import dev.mcresearch.protocolclient.ui.Rect;
import dev.mcresearch.protocolclient.ui.ScrollContainer;
import dev.mcresearch.protocolclient.ui.TextLabel;
import dev.mcresearch.protocolclient.ui.UiCanvas;
import java.util.ArrayList;
import java.util.List;

/** „Über Merlin Client“: Name, eigene Version, Minecraft-Version, Entwicklungsstand, Lizenz, Repository, Systeminfo. */
public final class AboutScreen extends MenuScreen {
	private ScrollContainer body;
	private Button back;
	private final List<String[]> rows = new ArrayList<>();

	public AboutScreen(final AppServices app) {
		super(app);
	}

	@Override
	public String name() {
		return "about";
	}

	@Override
	public String title() {
		return "Über Merlin Client";
	}

	@Override
	protected void init() {
		this.rows.add(new String[]{"Anwendung", MerlinVersion.NAME});
		this.rows.add(new String[]{"Client-Version", MerlinVersion.CLIENT_VERSION});
		this.rows.add(new String[]{"Minecraft-Version", MerlinVersion.MINECRAFT_VERSION + " (Protokoll " + MerlinVersion.PROTOCOL + ")"});
		this.rows.add(new String[]{"Ältere Server", "ab 1.20.2 (Protokoll " + ProtocolVersions.MVS_MIN_PROTOCOL
			+ ") mit dem Merlin Multi Version System geplant (M18)"});
		this.rows.add(new String[]{"Entwicklungsstand", MerlinVersion.STATUS});
		this.rows.add(new String[]{"Lizenz und Hinweise", MerlinVersion.LICENSE});
		this.rows.add(new String[]{"Repository", MerlinVersion.REPOSITORY});
		this.rows.add(new String[]{"System", this.app.platformInfo()});
		this.body = add(new ScrollContainer("about-body"));
		this.back = button("back", "Zurück", b -> manager().back());
	}

	@Override
	protected void layout(final double w, final double h) {
		double cw = Math.min(420, w - 16);
		double top = titleY() + 16;
		double bottom = h - BUTTON_H - 10;
		this.body.setBounds(new Rect((w - cw) / 2, top, cw, Math.max(20, bottom - top - 6)));
		this.body.clear();
		UiCanvas m = manager().measure();
		double y = 0;
		double inner = this.body.innerWidth();
		for (String[] r : this.rows) {
			this.body.add(new TextLabel("about." + r[0], r[0]).style(TextLabel.Style.DIM), new Rect(0, y, inner, 11));
			y += 11;
			double th = m.wrap(r[1], inner).size() * (m.lineHeight() + 1) + 2;
			this.body.add(new TextLabel("about.v." + r[0], r[1]).wrap(true), new Rect(0, y, inner, th));
			y += th + 5;
		}
		this.body.setContentHeight(y);
		this.back.setBounds(new Rect(w / 2 - columnWidth(150, w) / 2, h - BUTTON_H - 6, columnWidth(150, w), BUTTON_H));
	}
}
