package dev.mcresearch.protocolclient.ui;

import dev.mcresearch.protocolclient.app.ConnectionManager;
import dev.mcresearch.protocolclient.app.ServerEntry;
import dev.mcresearch.protocolclient.app.ServerList;
import dev.mcresearch.protocolclient.app.ServerPinger;
import dev.mcresearch.protocolclient.app.ServerStatus;
import dev.mcresearch.protocolclient.settings.SettingsManager;
import dev.mcresearch.protocolclient.ui.screen.AppServices;
import dev.mcresearch.protocolclient.ui.screen.ConnectingScreen;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;

/** Attrappe der Anwendung für Screen-Tests (kein Netzwerk, kein OpenGL). */
public final class FakeApp implements AppServices {
	public final SettingsManager settings;

	public FakeApp() {
		this(SettingsManager.inMemory());
	}

	public FakeApp(final SettingsManager settings) {
		this.settings = settings;
	}

	public final ServerList servers = ServerList.inMemory();
	public final ServerPinger pinger = new ServerPinger();
	public volatile ServerStatus status = ServerStatus.offline("Server nicht erreichbar");
	public final ConnectionManager connections = new ConnectionManager((t, id, vd) -> {
		throw new ConnectException("Connection refused");
	}, t -> this.status);
	public final List<String> calls = new ArrayList<>();
	public ScreenManager manager;
	public boolean inGame;

	@Override
	public SettingsManager settings() {
		return this.settings;
	}

	@Override
	public ServerList servers() {
		return this.servers;
	}

	@Override
	public ServerPinger pinger() {
		return this.pinger;
	}

	@Override
	public ConnectionManager connections() {
		return this.connections;
	}

	@Override
	public void connect(final ServerEntry e) {
		this.calls.add("connect " + e.address());
		this.connections.connect(e, new dev.mcresearch.protocolclient.auth.OfflineIdentity("Tester"), 2, true);
		if (this.manager != null) {
			this.manager.open(new ConnectingScreen(this, e));
		}
	}

	@Override
	public void disconnect(final boolean toMainMenu) {
		this.calls.add("disconnect " + toMainMenu);
	}

	@Override
	public void resumeGame() {
		this.calls.add("resume");
		if (this.manager != null) {
			this.manager.closeAll();
		}
	}

	@Override
	public boolean inGame() {
		return this.inGame;
	}

	@Override
	public void quit() {
		this.calls.add("quit");
	}

	@Override
	public List<String> fullscreenModes() {
		return List.of("1920x1080@60", "1600x900@60", "1280x720@60");
	}

	@Override
	public String platformInfo() {
		return "Testsystem (kein OpenGL)";
	}
}
