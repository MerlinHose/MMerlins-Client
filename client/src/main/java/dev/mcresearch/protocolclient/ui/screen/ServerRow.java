package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.app.ProtocolVersions;
import dev.mcresearch.protocolclient.app.ServerEntry;
import dev.mcresearch.protocolclient.app.ServerStatus;
import dev.mcresearch.protocolclient.input.GameAction;
import dev.mcresearch.protocolclient.ui.Rect;
import dev.mcresearch.protocolclient.ui.UiCanvas;
import dev.mcresearch.protocolclient.ui.UiContext;
import dev.mcresearch.protocolclient.ui.Widget;
import java.util.function.Consumer;

/** Zeile der Serverliste: Name, Adresse, Status/Version/Kompatibilität; Klick wählt aus, Bestätigen verbindet. */
public final class ServerRow extends Widget {
	private final int index;
	private ServerEntry entry;
	private ServerStatus status = ServerStatus.UNKNOWN;
	private boolean selected;
	private final Consumer<ServerRow> onSelect;
	private final Consumer<ServerRow> onConfirm;

	public ServerRow(final String id, final int index, final ServerEntry entry, final Consumer<ServerRow> onSelect,
		final Consumer<ServerRow> onConfirm) {
		super(id);
		this.index = index;
		this.entry = entry;
		this.onSelect = onSelect;
		this.onConfirm = onConfirm;
	}

	public int index() {
		return this.index;
	}

	public ServerEntry entry() {
		return this.entry;
	}

	public void setEntry(final ServerEntry e) {
		this.entry = e;
	}

	public ServerStatus status() {
		return this.status;
	}

	public void setStatus(final ServerStatus s) {
		this.status = s;
	}

	public boolean selected() {
		return this.selected;
	}

	public void setSelected(final boolean s) {
		this.selected = s;
	}

	@Override
	public boolean mousePressed(final double x, final double y, final int button, final UiContext ctx) {
		if (button == 0) {
			this.onSelect.accept(this);
			return true;
		}
		return false;
	}

	@Override
	public boolean action(final GameAction a, final UiContext ctx) {
		if (a == GameAction.GUI_CONFIRM) {
			this.onSelect.accept(this);
			this.onConfirm.accept(this);
			return true;
		}
		return false;
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		drawButtonFrame(c, ctx, this, this.selected);
		Rect r = bounds().inset(4);
		if (this.selected) {
			c.fill(new Rect(bounds().x(), bounds().y(), 2 * lineWidth(c), bounds().h()), ctx.theme().accent());
		}
		double half = r.h() / 2;
		c.textIn(this.entry.name() + "  ·  " + this.entry.address(), new Rect(r.x(), r.y(), r.w(), half), UiCanvas.Align.LEFT, ctx.theme().text(),
			true);
		String version = this.entry.lastProtocol() > 0 ? "  ·  zuletzt " + (this.entry.lastVersionName().isBlank()
			? "Protokoll " + this.entry.lastProtocol() : this.entry.lastVersionName()) : "";
		int color = switch (this.status.state()) {
			case ONLINE -> this.status.support() == ProtocolVersions.Support.NATIVE ? ctx.theme().ok() : ctx.theme().warning();
			case OFFLINE, BLOCKED -> ctx.theme().error();
			default -> ctx.theme().textDim();
		};
		c.textIn(this.status.summary() + (this.status.state() == ServerStatus.State.ONLINE ? "" : version), new Rect(r.x(), r.y() + half, r.w(), half),
			UiCanvas.Align.LEFT, color, false);
	}

	@Override
	public String describe() {
		return "ServerRow " + id() + " " + this.entry.address() + " " + this.status.state() + (this.selected ? " selected" : "");
	}
}
