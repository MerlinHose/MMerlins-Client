package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.app.ServerEntry;
import dev.mcresearch.protocolclient.app.ServerStatus;
import dev.mcresearch.protocolclient.ui.Button;
import dev.mcresearch.protocolclient.ui.Dialog;
import dev.mcresearch.protocolclient.ui.Rect;
import dev.mcresearch.protocolclient.ui.ScrollContainer;
import dev.mcresearch.protocolclient.ui.TextLabel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Mehrspieler (docs/61 §6): gespeicherte Servereinträge mit Status, Version und Kompatibilität. Vorbereitung für reguläre
 * Serververbindungen und MVS; in M15 werden nur Loopback-Einträge geprüft und verbunden, externe sind sichtbar gesperrt.
 */
public final class MultiplayerScreen extends MenuScreen {
	private ScrollContainer list;
	private TextLabel empty;
	private TextLabel info;
	private Button join;
	private Button add;
	private Button edit;
	private Button delete;
	private Button refresh;
	private Button back;
	private final List<ServerRow> rows = new ArrayList<>();
	private final Map<Integer, CompletableFuture<ServerStatus>> pending = new HashMap<>();
	private int selected = -1;

	public MultiplayerScreen(final AppServices app) {
		super(app);
	}

	@Override
	public String name() {
		return "multiplayer";
	}

	@Override
	public String title() {
		return "Mehrspieler";
	}

	@Override
	protected void init() {
		this.list = add(new ScrollContainer("server-list"));
		this.empty = add(new TextLabel("server-empty", "Noch keine Server. „Hinzufügen“ legt einen Eintrag an (in dieser Version nur localhost).")
			.wrap(true).style(TextLabel.Style.DIM));
		this.info = add(new TextLabel("server-info", "Externe Server sind gesperrt; eine öffentliche Serverliste ist nicht Teil dieser Version.")
			.style(TextLabel.Style.DIM));
		this.join = button("join", "Verbinden", b -> connectSelected());
		this.add = button("add", "Hinzufügen", b -> manager().open(new ServerEditScreen(this.app, -1)));
		this.edit = button("edit", "Bearbeiten", b -> manager().open(new ServerEditScreen(this.app, this.selected)));
		this.delete = button("delete", "Löschen", b -> confirmDelete());
		this.refresh = button("refresh", "Aktualisieren", b -> pingAll());
		this.back = button("back", "Zurück", b -> manager().back());
		rebuildRows();
		pingAll();
	}

	private void rebuildRows() {
		this.rows.clear();
		this.list.clear();
		List<ServerEntry> es = this.app.servers().entries();
		for (int i = 0; i < es.size(); i++) {
			this.rows.add(new ServerRow("server." + i, i, es.get(i), this::select, r -> connectSelected()));
		}
		if (this.selected >= es.size()) {
			this.selected = es.size() - 1;
		}
		for (ServerRow r : this.rows) {
			r.setSelected(r.index() == this.selected);
		}
		updateButtons();
	}

	private void select(final ServerRow r) {
		this.selected = r.index();
		for (ServerRow x : this.rows) {
			x.setSelected(x == r);
		}
		updateButtons();
	}

	private void updateButtons() {
		boolean has = this.selected >= 0 && this.selected < this.rows.size();
		ServerEntry e = has ? this.rows.get(this.selected).entry() : null;
		this.join.setEnabled(has && e.allowed() && !this.app.connections().busy());
		this.join.setTooltip(has && !e.allowed() ? "Externe Server sind in dieser Entwicklungsversion gesperrt." : null);
		this.edit.setEnabled(has);
		this.delete.setEnabled(has);
		this.empty.setVisible(this.rows.isEmpty());
	}

	private void connectSelected() {
		if (this.selected >= 0 && this.selected < this.rows.size() && this.rows.get(this.selected).entry().allowed()) {
			this.app.connect(this.rows.get(this.selected).entry());
		}
	}

	private void confirmDelete() {
		if (this.selected < 0) {
			return;
		}
		ServerEntry e = this.rows.get(this.selected).entry();
		int idx = this.selected;
		manager().showDialog(new Dialog("confirm-delete", "Server löschen?", "„" + e.name() + "“ (" + e.address() + ") wird aus der Liste entfernt.",
			false, List.of(new Dialog.Choice("confirm-delete.yes", "Löschen", () -> {
				this.app.servers().remove(idx);
				this.app.servers().save();
				this.selected = -1;
				rebuildRows();
				layout(width(), height());
			}), new Dialog.Choice("confirm-delete.no", "Abbrechen", () -> {
			}))));
	}

	private void pingAll() {
		for (ServerRow r : this.rows) {
			r.setStatus(r.entry().allowed() ? ServerStatus.PINGING : ServerStatus.BLOCKED);
			this.pending.put(r.index(), this.app.pinger().ping(r.entry()));
		}
	}

	@Override
	public void tick() {
		var it = this.pending.entrySet().iterator();
		while (it.hasNext()) {
			var en = it.next();
			if (en.getValue().isDone()) {
				int i = en.getKey();
				ServerStatus st = en.getValue().getNow(ServerStatus.offline("?"));
				if (i < this.rows.size()) {
					this.rows.get(i).setStatus(st);
					if (st.state() == ServerStatus.State.ONLINE) {
						this.app.servers().rememberVersion(i, st.versionName(), st.protocol()); // Serverversion je Eintrag merken (MVS)
						this.rows.get(i).setEntry(this.app.servers().entries().get(i));
						this.app.servers().save();
					}
				}
				it.remove();
				manager().markDirty();
			}
		}
	}

	@Override
	public void resumed() {
		rebuildRows();
		pingAll();
	}

	@Override
	protected void layout(final double w, final double h) {
		double cw = Math.min(400, w - 16);
		double x = (w - cw) / 2;
		double top = titleY() + 16;
		double buttonsH = 2 * BUTTON_H + GAP;
		double listH = Math.max(30, h - top - buttonsH - 26);
		this.list.setBounds(new Rect(x, top, cw, listH));
		this.list.clear();
		double rowH = 26;
		for (ServerRow r : this.rows) {
			this.list.add(r, new Rect(0, r.index() * (rowH + 3), this.list.innerWidth(), rowH));
		}
		this.list.setContentHeight(this.rows.size() * (rowH + 3));
		this.empty.setBounds(new Rect(x + 8, top + 8, cw - 16, 24));
		this.info.setBounds(new Rect(x, top + listH + 2, cw, 11));
		double by = top + listH + 16;
		double third = (cw - 2 * GAP) / 3;
		this.join.setBounds(new Rect(x, by, third, BUTTON_H));
		this.add.setBounds(new Rect(x + third + GAP, by, third, BUTTON_H));
		this.edit.setBounds(new Rect(x + 2 * (third + GAP), by, third, BUTTON_H));
		this.delete.setBounds(new Rect(x, by + BUTTON_H + GAP, third, BUTTON_H));
		this.refresh.setBounds(new Rect(x + third + GAP, by + BUTTON_H + GAP, third, BUTTON_H));
		this.back.setBounds(new Rect(x + 2 * (third + GAP), by + BUTTON_H + GAP, third, BUTTON_H));
		updateButtons();
	}
}
