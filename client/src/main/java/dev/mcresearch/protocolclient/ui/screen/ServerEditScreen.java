package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.app.ServerEntry;
import dev.mcresearch.protocolclient.ui.Button;
import dev.mcresearch.protocolclient.ui.Rect;
import dev.mcresearch.protocolclient.ui.TextInput;
import dev.mcresearch.protocolclient.ui.TextLabel;

/** Servereintrag anlegen/bearbeiten: Name und Adresse (host[:port]); externe Adressen werden gespeichert, aber als gesperrt gekennzeichnet. */
public final class ServerEditScreen extends MenuScreen {
	private final int index;
	private TextInput name;
	private TextInput address;
	private TextLabel message;
	private Button save;
	private Button cancel;

	public ServerEditScreen(final AppServices app, final int index) {
		super(app);
		this.index = index;
	}

	@Override
	public String name() {
		return "server-edit";
	}

	@Override
	public String title() {
		return this.index < 0 ? "Server hinzufügen" : "Server bearbeiten";
	}

	@Override
	protected void init() {
		ServerEntry e = this.index >= 0 && this.index < this.app.servers().size() ? this.app.servers().entries().get(this.index)
			: new ServerEntry("Mein Testserver", "127.0.0.1", ServerEntry.DEFAULT_PORT, "", 0);
		this.name = add(new TextInput("server-name", "Name", e.name(), 32, cp -> cp >= 0x20, s -> check()));
		this.address = add(new TextInput("server-address", "Adresse", e.host().contains(":") ? "[" + e.host() + "]:" + e.port()
			: e.host() + ":" + e.port(), 64, cp -> cp > 0x20 && cp < 0x7F, s -> check()));
		this.message = add(new TextLabel("server-edit-msg", "").wrap(true));
		this.save = button("save", "Speichern", b -> save());
		this.cancel = button("cancel", "Abbrechen", b -> manager().back());
		check();
	}

	private ServerEntry parsed() {
		return ServerEntry.parse(this.name.text(), this.address.text());
	}

	private void check() {
		try {
			ServerEntry e = parsed();
			this.message.setText(e.allowed() ? "Lokaler Server – verbindbar." : "Gültig, aber extern: wird gespeichert und als gesperrt angezeigt.");
			this.message.style(e.allowed() ? TextLabel.Style.OK : TextLabel.Style.WARNING);
			this.save.setEnabled(true);
		} catch (IllegalArgumentException ex) {
			this.message.setText(ex.getMessage());
			this.message.style(TextLabel.Style.ERROR);
			this.save.setEnabled(false);
		}
	}

	private void save() {
		ServerEntry e;
		try {
			e = parsed();
		} catch (IllegalArgumentException ex) {
			check();
			return;
		}
		if (this.index >= 0 && this.index < this.app.servers().size()) {
			ServerEntry old = this.app.servers().entries().get(this.index);
			boolean same = old.host().equalsIgnoreCase(e.host()) && old.port() == e.port();
			this.app.servers().set(this.index, same ? e.withVersion(old.lastVersionName(), old.lastProtocol()) : e);
		} else {
			this.app.servers().add(e);
		}
		String err = this.app.servers().save();
		if (err != null) {
			this.message.setText(err);
			this.message.style(TextLabel.Style.ERROR);
			return;
		}
		manager().back();
	}

	@Override
	protected void layout(final double w, final double h) {
		double cw = Math.min(320, w - 16);
		double x = (w - cw) / 2;
		double y = Math.max(titleY() + 22, h * 0.25);
		this.name.setBounds(new Rect(x, y, cw, BUTTON_H));
		this.address.setBounds(new Rect(x, y + BUTTON_H + 14, cw, BUTTON_H));
		this.message.setBounds(new Rect(x, y + 2 * BUTTON_H + 20, cw, 22));
		row(this.save, this.cancel, w / 2, Math.min(h - BUTTON_H - 6, y + 2 * BUTTON_H + 48), cw);
	}

	@Override
	public void render(final dev.mcresearch.protocolclient.ui.UiCanvas c, final dev.mcresearch.protocolclient.ui.UiContext ctx) {
		super.render(c, ctx);
		c.textIn("Name", new Rect(this.name.bounds().x(), this.name.bounds().y() - 11, 120, 10), dev.mcresearch.protocolclient.ui.UiCanvas.Align.LEFT,
			ctx.theme().textDim(), false);
		c.textIn("Adresse (host:port)", new Rect(this.address.bounds().x(), this.address.bounds().y() - 11, 160, 10),
			dev.mcresearch.protocolclient.ui.UiCanvas.Align.LEFT, ctx.theme().textDim(), false);
	}
}
