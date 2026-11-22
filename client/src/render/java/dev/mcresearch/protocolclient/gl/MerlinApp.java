package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.app.ConnectErrors;
import dev.mcresearch.protocolclient.app.ConnectionManager;
import dev.mcresearch.protocolclient.app.MerlinVersion;
import dev.mcresearch.protocolclient.app.ServerEntry;
import dev.mcresearch.protocolclient.app.ServerList;
import dev.mcresearch.protocolclient.app.ServerPinger;
import dev.mcresearch.protocolclient.auth.LoginIdentity;
import dev.mcresearch.protocolclient.auth.OfflineIdentity;
import dev.mcresearch.protocolclient.input.GameAction;
import dev.mcresearch.protocolclient.input.InputManager;
import dev.mcresearch.protocolclient.net.PacketLog;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.settings.Settings;
import dev.mcresearch.protocolclient.settings.SettingsManager;
import dev.mcresearch.protocolclient.ui.GameScreen;
import dev.mcresearch.protocolclient.ui.Screen;
import dev.mcresearch.protocolclient.ui.ScreenManager;
import dev.mcresearch.protocolclient.ui.Theme;
import dev.mcresearch.protocolclient.ui.screen.AppServices;
import dev.mcresearch.protocolclient.ui.screen.ConnectingScreen;
import dev.mcresearch.protocolclient.ui.screen.DisconnectedScreen;
import dev.mcresearch.protocolclient.ui.screen.MainMenuScreen;
import dev.mcresearch.protocolclient.ui.screen.PauseScreen;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * Anwendungslebenszyklus des Merlin Clients (docs/59 §2): Fenster, Ressourcen, Einstellungen, Eingabe, Screens, Verbindungen.
 *
 * <pre>
 *   Start → Hauptmenü ─► Verbinden (ConnectingScreen) ─► Spiel (GameScene) ─► Esc: Pausenmenü ─► Trennen ─► Hauptmenü …
 * </pre>
 *
 * Eine Spielszene lebt so lange wie ihre Verbindung; Renderer, Atlas und Shader leben so lange wie die Anwendung. Mehrere
 * Verbindungen hintereinander sind ohne Neustart möglich. Im Direktmodus ({@code --autotest}, {@code --direct}, {@code --login-ui})
 * verhält sich der Client wie bisher: sofort verbinden, kein Hauptmenü, Einstellungen nur aus den Startoptionen.
 */
final class MerlinApp implements AppServices {
	private final GraphicalOptions o;
	private final boolean direct;
	private final SettingsManager settings;
	private final ServerList servers;
	private final ServerPinger pinger = new ServerPinger();
	private final ScreenManager ui = new ScreenManager();
	private final InputManager input = new InputManager();
	private final GameView.FrameStats fps = new GameView.FrameStats();
	private ConnectionManager connections;
	private Window window;
	private ClientResources res;
	private GameScene scene;
	private ServerEntry target;
	private LoginIdentity directIdentity;
	private boolean autotestUsed;
	private boolean toMainMenuAfterDisconnect;
	private final GameScreen inventoryScreen = new GameScreen("inventory", "Inventar", () -> {
	});
	private final GameScreen deathScreen = new GameScreen("death", "Gestorben", () -> {
	});
	private List<String> videoModes = List.of();
	private int appliedGuiScale = -1;
	private long lastAutosave;
	private long frames;
	private UiAutotest uiTest;
	private String lastDisplay = "";

	private MerlinApp(final GraphicalOptions o) {
		this.o = o;
		this.direct = o.directMode();
		if (this.direct) {
			// Regressionsläufe: Nutzereinstellungen dürfen Vergleiche nicht verändern
			this.settings = o.settingsFile() != null ? SettingsManager.load(o.settingsFile()) : SettingsManager.inMemory();
			if (o.settingsFile() == null) {
				this.settings.set(Settings.RENDER_DISTANCE, Math.max(2, Math.min(8, o.renderDistance())));
				this.settings.set(Settings.FOV, Math.round(o.fov()));
				this.settings.set(Settings.VSYNC, o.vsync());
				this.settings.set(Settings.DEBUG_OVERLAY, true);
				this.settings.set(Settings.MAX_FPS, Settings.UNLIMITED_FPS);
				this.settings.set(Settings.WINDOW_WIDTH, o.width());
				this.settings.set(Settings.WINDOW_HEIGHT, Math.max(240, o.height()));
			}
			this.servers = ServerList.inMemory();
		} else {
			this.settings = SettingsManager.load(o.settingsFile() != null ? o.settingsFile() : SettingsManager.defaultPath());
			this.servers = ServerList.load(o.serversFile() != null ? o.serversFile() : ServerList.defaultPath());
		}
	}

	static void run(final GraphicalOptions o) throws Exception {
		System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tT] [%4$s] %3$s: %5$s%6$s%n");
		new MerlinApp(o).main();
	}

	private void main() throws Exception {
		if (this.direct) {
			this.directIdentity = directIdentity();
			if (this.directIdentity == null) {
				return;
			}
			System.out.println("[LOGIN] " + this.directIdentity.kind() + " " + this.directIdentity.name() + " " + this.directIdentity.profileId());
		}
		System.out.println("[APP] " + MerlinVersion.full() + " mode=" + (this.direct ? "direct" : "menu") + " settings=" + this.settings.status()
			+ (this.settings.file() == null ? "" : " " + this.settings.file()));
		for (String n : this.settings.notes()) {
			System.out.println("[APP] settings: " + n);
		}
		try (PacketLog packets = this.o.packetLog() != null ? PacketLog.open(this.o.packetLog()) : PacketLog.disabled()) {
			this.connections = new ConnectionManager(ConnectionManager.defaultFactory(packets), ServerPinger::pingNow);
			int w = this.direct ? this.o.width() : this.settings.get(Settings.WINDOW_WIDTH);
			int h = this.direct ? this.o.height() : this.settings.get(Settings.WINDOW_HEIGHT);
			String title = this.direct ? "Minecraft Research – Graphical Client" : MerlinVersion.NAME + " " + MerlinVersion.CLIENT_VERSION;
			try (Window win = new Window(title, w, h, this.settings.get(Settings.VSYNC))) {
				this.window = win;
				win.setGrabAllowed(!this.o.noGrab());
				System.out.println("[GL] " + win.glInfo());
				this.res = new ClientResources(this.o);
				try {
					setup();
					loop();
				} finally {
					shutdown();
				}
			}
		}
		this.pinger.close();
		SettingsManager.SaveResult sr = this.settings.saveIfDirty();
		if (!sr.ok()) {
			System.out.println("[APP] " + sr.message());
		}
	}

	private LoginIdentity directIdentity() throws Exception {
		// M13 (docs/52 §8): Identität vor dem Spielfenster bestimmen; Standard bleibt der Offline-Testmodus ohne Netzwerkanmeldung
		if (this.o.loginUi()) {
			LoginIdentity id = LoginWindow.show(this.o);
			if (id == null) {
				System.out.println("[LOGIN] Anmeldung geschlossen, kein Verbindungsaufbau");
			}
			return id;
		}
		try {
			return dev.mcresearch.protocolclient.auth.minecraft.LoginFactory.create(this.o.auth().options(this.o.username()),
				s -> System.out.println("[LOGIN] " + s), new dev.mcresearch.protocolclient.auth.AuthCancellation());
		} catch (dev.mcresearch.protocolclient.auth.AuthException e) {
			System.out.println("[LOGIN] FAILED " + e.error() + ": " + e.userMessage());
			return null;
		}
	}

	private void setup() throws Exception {
		this.ui.setMeasure(this.res.ui.canvas());
		this.ui.context().setClipboard(new dev.mcresearch.protocolclient.ui.UiContext.Clipboard() {
			@Override
			public String get() {
				return MerlinApp.this.window.clipboard();
			}

			@Override
			public void set(final String s) {
				MerlinApp.this.window.setClipboard(s);
			}
		});
		this.input.register(new GlfwInputSource(this.window));
		this.input.setBindings(this.settings.bindings());
		this.videoModes = this.window.videoModes();
		this.ui.resize(this.window.width(), this.window.height(), this.settings.get(Settings.GUI_SCALE));
		this.settings.addListener(this::apply);
		applyAll();
		if (this.direct) {
			this.target = new ServerEntry("Direkt", this.o.host(), this.o.port(), "", 0);
			this.connections.connect(this.target, this.directIdentity, this.o.renderDistance(), false);
		} else {
			this.ui.reset(new MainMenuScreen(this));
			if (this.o.uiTest() != null) {
				this.uiTest = UiAutotest.load(this.o.uiTest(), this.o.report(), this);
			}
		}
	}

	// ------------------------------------------------------------------ Einstellungen anwenden

	private void applyAll() {
		for (String k : List.of(Settings.VSYNC.key(), Settings.DISPLAY_MODE.key(), Settings.SENSITIVITY.key(), Settings.TOOLTIPS.key(),
			Settings.HIGH_CONTRAST.key(), Settings.PARTICLES.key(), Settings.SHOW_HUD.key())) {
			apply(k);
		}
		if (!this.direct) {
			apply(Settings.BIOME_BLEND.key());
			apply(Settings.AMBIENT_OCCLUSION.key());
		}
	}

	/** Wirkt eine geänderte Einstellung sofort aus (Listener des SettingsManagers). */
	private void apply(final String key) {
		SettingsManager s = this.settings;
		GameScene sc = this.scene;
		switch (key) {
			case "graphics.vsync" -> this.window.setVsync(s.get(Settings.VSYNC));
			case "graphics.displayMode", "graphics.fullscreenResolution" -> {
				if (!this.direct) {
					String d = s.get(Settings.DISPLAY_MODE) + "/" + s.get(Settings.FULLSCREEN_RESOLUTION);
					if (!d.equals(this.lastDisplay)) {
						this.lastDisplay = d;
						this.window.setDisplayMode(s.get(Settings.DISPLAY_MODE), s.get(Settings.FULLSCREEN_RESOLUTION));
					}
				}
			}
			case "graphics.renderDistance", "graphics.particles" -> {
				if (sc != null) {
					sc.renderDistance = s.get(Settings.RENDER_DISTANCE);
				}
				this.res.particles.setLevel(particleLevel());
				sendClientInformation();
			}
			case "graphics.fov" -> {
				if (sc != null) {
					sc.fov = s.get(Settings.FOV);
				}
			}
			case "graphics.brightness" -> {
				if (sc != null) {
					sc.brightness = (float) (double) s.get(Settings.BRIGHTNESS);
				}
			}
			case "graphics.clouds" -> {
				if (sc != null) {
					sc.clouds = !s.get(Settings.CLOUDS).equals("OFF");
				}
			}
			case "graphics.biomeBlend", "graphics.ambientOcclusion" -> {
				int blend = s.get(Settings.BIOME_BLEND);
				boolean ao = s.get(Settings.AMBIENT_OCCLUSION);
				if (blend != GameView.BIOME_BLEND_RADIUS || ao != GameView.AMBIENT_OCCLUSION) {
					GameView.BIOME_BLEND_RADIUS = blend;
					GameView.AMBIENT_OCCLUSION = ao;
					if (sc != null) {
						sc.rebuildMeshes();
					}
				}
			}
			case "controls.sensitivity", "controls.invertY", "controls.toggleSprint", "controls.toggleSneak" -> this.input.configure(
				s.get(Settings.SENSITIVITY), s.get(Settings.INVERT_Y), s.get(Settings.TOGGLE_SPRINT), s.get(Settings.TOGGLE_SNEAK));
			case "interface.showHud" -> GameView.HIDE_GUI = !s.get(Settings.SHOW_HUD);
			case "interface.debugOverlay" -> {
				if (sc != null) {
					sc.debug = s.get(Settings.DEBUG_OVERLAY);
				}
			}
			case "interface.tooltips" -> this.ui.setTooltipsEnabled(s.get(Settings.TOOLTIPS));
			case "accessibility.highContrast" -> this.ui.setTheme(s.get(Settings.HIGH_CONTRAST) ? Theme.HIGH_CONTRAST : Theme.DEFAULT);
			default -> {
				if (key.equals("controls.key.*")) {
					this.input.setBindings(s.bindings());
				}
			}
		}
		this.ui.markDirty();
	}

	private int particleLevel() {
		return switch (this.settings.get(Settings.PARTICLES)) {
			case "DECREASED" -> 1;
			case "MINIMAL" -> 2;
			default -> 0;
		};
	}

	private void sendClientInformation() {
		GameScene sc = this.scene;
		if (sc == null || this.direct) {
			return;
		}
		try {
			if (sc.session.updateClientInformation(this.settings.get(Settings.RENDER_DISTANCE), particleLevel())) {
				System.out.println("[APP] client_information: view distance " + this.settings.get(Settings.RENDER_DISTANCE) + ", particles " + particleLevel());
			}
		} catch (java.io.IOException e) {
			System.out.println("[APP] client_information failed: " + e);
		}
	}

	private void configureScene(final GameScene sc) {
		if (this.direct && this.o.settingsFile() == null) {
			sc.fov = this.o.fov();
			sc.renderDistance = this.o.renderDistance();
			sc.debug = true;
			sc.brightness = 0.5f;
			sc.clouds = true;
			return;
		}
		sc.fov = this.settings.get(Settings.FOV);
		sc.renderDistance = this.settings.get(Settings.RENDER_DISTANCE);
		sc.debug = this.settings.get(Settings.DEBUG_OVERLAY);
		sc.brightness = (float) (double) this.settings.get(Settings.BRIGHTNESS);
		sc.clouds = !this.settings.get(Settings.CLOUDS).equals("OFF");
	}

	// ------------------------------------------------------------------ Schleife

	private void loop() throws Exception {
		long start = System.nanoTime();
		glEnable(GL_DEPTH_TEST);
		glEnable(GL_CULL_FACE);
		glCullFace(GL_BACK);
		glFrontFace(GL_CCW);
		Window w = this.window;
		while (!w.shouldClose()) {
			long now = System.nanoTime();
			this.fps.frame(now);
			this.res.profiler.frameStart(now);
			this.frames++;
			if (w.consumeResize() && !this.direct && w.displayMode().equals("WINDOWED")) {
				int[] ws = w.windowSize();
				if (ws[0] >= 320 && ws[1] >= 240) {
					this.settings.set(Settings.WINDOW_WIDTH, Math.min(7680, ws[0]));
					this.settings.set(Settings.WINDOW_HEIGHT, Math.min(4320, ws[1]));
				}
			}
			int guiScale = this.settings.get(Settings.GUI_SCALE);
			if (guiScale != this.appliedGuiScale && !w.isButtonDown(0)) {
				this.appliedGuiScale = guiScale; // erst nach dem Loslassen des Reglers (wie Vanilla)
			}
			this.ui.resize(w.width(), w.height(), this.appliedGuiScale);
			// ---- Verbindung: Szene anlegen, Übergänge, Aufräumen ----
			connectionState();
			// ---- Eingabe: Bereich nach dem Zustand zu Bildbeginn ----
			InputManager.Scope scope = this.scene == null || !this.scene.session.playing() ? InputManager.Scope.MENU
				: this.ui.hasScreen() ? InputManager.Scope.WORLD_SCREEN : InputManager.Scope.WORLD;
			this.input.update(scope);
			// Besitz der Eingabe zu Bildbeginn: eine Taste, die einen Screen öffnet (Esc → Pause), darf ihn nicht im selben Bild wieder schließen
			boolean uiOwnsInput = this.ui.hasScreen() && !this.ui.gameHandlesInput();
			if (!uiOwnsInput) {
				w.consumeKeyEvents();
				w.consumeChars();
				w.consumeUiScroll();
			}
			globalActions(scope);
			if (uiOwnsInput) {
				if (w.mouseCaptured()) {
					w.setMouseCaptured(false);
				}
				w.setScreenOpen(true);
				double[] c = w.cursorPosition();
				this.ui.mouseMoved(c[0], c[1]);
				for (Window.MouseEvent e : w.consumeMouseEvents()) {
					this.ui.mouseButton(e.press(), e.button(), e.x(), e.y());
				}
				double sc = w.consumeUiScroll();
				if (sc != 0) {
					this.ui.scrolled(c[0], c[1], sc);
				}
				for (Window.KeyEvent k : w.consumeKeyEvents()) {
					this.ui.keyPressed(k.key(), k.mods());
				}
				for (int ch : w.consumeChars()) {
					this.ui.charTyped(ch);
				}
			}
			if (this.uiTest != null) {
				this.uiTest.step(now);
			}
			this.ui.tick(now);
			// ---- Zeichnen: Welt (wenn sichtbar), dann Menüs ----
			GameScene sc = this.scene;
			Screen top = this.ui.current();
			boolean worldVisible = sc != null && (top == null || top.overlaysWorld() || top.handledByGame());
			if (worldVisible) {
				boolean uiBlocking = this.ui.hasScreen() && !this.ui.gameHandlesInput();
				sc.frame(now, this.fps, uiBlocking);
				syncGameScreens(sc);
			} else {
				glClearColor(0.04f, 0.07f, 0.09f, 1f);
				glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
				this.res.profiler.frameEnd(0, 0, 0);
				if (sc == null) {
					w.setScreenOpen(true);
				}
			}
			Screen topNow = this.ui.current();
			if (topNow != null && !topNow.handledByGame()) {
				this.res.ui.draw(this.ui, this.ui.consumeDirty());
			}
			if (this.o.screenshot() != null && this.frames == this.o.screenshotFrame()) {
				w.screenshot(this.o.screenshot());
			}
			if (this.o.exitAfterSeconds() > 0 && (now - start) / 1e9 >= this.o.exitAfterSeconds()) {
				w.requestClose();
			}
			if (!this.direct && now - this.lastAutosave > 2_000_000_000L) {
				this.lastAutosave = now;
				if (this.settings.dirty() && !(topNow instanceof dev.mcresearch.protocolclient.ui.screen.SettingsScreen)) {
					this.settings.saveIfDirty();
				}
			}
			w.endFrame();
			limitFrameRate(now, sc == null || !worldVisible);
		}
	}

	/** FPS-Begrenzung (Einstellung; im Hauptmenü höchstens 60 wie Vanilla {@code Minecraft#getFramerateLimit}). */
	private void limitFrameRate(final long frameStart, final boolean menuOnly) {
		int limit = this.settings.get(Settings.MAX_FPS);
		if (menuOnly && !this.direct) {
			limit = Math.min(limit, 60);
		}
		if (limit >= Settings.UNLIMITED_FPS) {
			return;
		}
		long target = frameStart + 1_000_000_000L / limit;
		long now;
		while ((now = System.nanoTime()) < target) {
			long left = target - now;
			if (left > 1_500_000) {
				LockSupport.parkNanos(left - 1_000_000);
			} else {
				Thread.onSpinWait();
			}
		}
	}

	/** Tasten, die überall wirken: Pause, F1, F2, F3, F11. */
	private void globalActions(final InputManager.Scope scope) throws java.io.IOException {
		InputManager in = this.input;
		GameScene sc = this.scene;
		if (scope == InputManager.Scope.WORLD && in.presses(GameAction.PAUSE) > 0 && sc != null) {
			this.ui.open(new PauseScreen(this));
			this.window.setMouseCaptured(false);
		}
		if (sc != null && in.presses(GameAction.TOGGLE_DEBUG) > 0) {
			sc.debug = !sc.debug;
		}
		if (sc != null && in.presses(GameAction.TOGGLE_HUD) > 0) {
			GameView.HIDE_GUI = !GameView.HIDE_GUI;
		}
		if (in.presses(GameAction.SCREENSHOT) > 0) {
			Path shot = Path.of("build", "screenshots", "screenshot-" + System.currentTimeMillis() + ".png");
			this.window.screenshot(shot);
			System.out.println("[GL] screenshot " + shot);
		}
		if (in.presses(GameAction.TOGGLE_FULLSCREEN) > 0 && !this.direct) {
			this.settings.set(Settings.DISPLAY_MODE, this.settings.get(Settings.DISPLAY_MODE).equals("WINDOWED") ? "FULLSCREEN" : "WINDOWED");
		}
	}

	/** Vom Spiel bediente Screens (Inventar, Container, Tod) im ScreenManager führen (Eingabesperre, Escape). */
	private void syncGameScreens(final GameScene sc) {
		sync(this.inventoryScreen, sc.inventoryVisible);
		sync(this.deathScreen, sc.deathVisible);
	}

	private void sync(final GameScreen s, final boolean visible) {
		boolean open = this.ui.stack().contains(s);
		if (visible && !open) {
			this.ui.open(s);
		} else if (!visible && open) {
			this.ui.remove(s);
		}
	}

	private void connectionState() throws java.io.IOException {
		ConnectionManager cm = this.connections;
		ClientSession s = cm.session();
		if (s != null && (this.scene == null || this.scene.session != s)) {
			if (this.scene != null) {
				disposeScene();
			}
			Autotest auto = null;
			if (this.direct && !this.autotestUsed && this.o.autotest() != null) {
				auto = Autotest.load(this.o.autotest(), this.o.report(), this.o.username());
				this.autotestUsed = true;
			}
			this.scene = new GameScene(this.o, this.window, this.res, s, cm.result(), auto, this.input, this.direct, this.target.host(),
				this.target.port());
			configureScene(this.scene);
			sendClientInformation();
		}
		if (this.direct) {
			return; // Direktmodus: Fenster bleibt nach dem Trennen mit Meldung offen (bisheriges Verhalten)
		}
		ConnectionManager.Phase p = cm.phase();
		if (p == ConnectionManager.Phase.CONNECTED && this.ui.current() instanceof ConnectingScreen) {
			this.ui.closeAll();
			this.scene.requestCapture();
			System.out.println("[APP] connected to " + this.target.address() + " (connect #" + cm.connects() + ")");
		}
		if (this.scene != null && (this.scene.session.ended() || this.scene.result.isDone())) {
			boolean user = cm.userDisconnect();
			boolean wasConnecting = this.ui.current() instanceof ConnectingScreen;
			disposeScene();
			boolean idle = cm.awaitIdle(5000);
			ConnectionManager.Phase end = cm.phase();
			ConnectErrors.UserError err = cm.error();
			cm.release();
			System.out.println("[APP] session ended: " + end + (err == null ? "" : " – " + err.title()) + ", threads stopped " + idle);
			if (user && this.toMainMenuAfterDisconnect) {
				this.ui.reset(new MainMenuScreen(this));
			} else if (user) {
				this.ui.reset(new MainMenuScreen(this));
				this.ui.open(new DisconnectedScreen(this, "Verbindung getrennt", "Du hast die Verbindung zu " + this.target.name() + " getrennt.",
					this.target));
			} else if (!wasConnecting) {
				this.ui.reset(new MainMenuScreen(this));
				this.ui.open(new DisconnectedScreen(this, err == null ? "Verbindung getrennt" : err.title(),
					err == null ? "Die Verbindung wurde beendet." : err.detail(), this.target));
			}
			this.window.setMouseCaptured(false);
		}
	}

	private void disposeScene() {
		GameScene sc = this.scene;
		if (sc == null) {
			return;
		}
		sc.dispose(this.fps);
		this.scene = null;
		this.ui.remove(this.inventoryScreen);
		this.ui.remove(this.deathScreen);
	}

	private void shutdown() {
		// wie bisher: Autotest-Abschluss und Renderer zuerst, dann GL-Bilanz, dann Sitzung trennen
		GameScene sc = this.scene;
		if (sc != null) {
			sc.dispose(this.fps);
		}
		if (this.uiTest != null) {
			this.uiTest.close();
		}
		this.ui.closeAll();
		this.res.close();
		System.out.println("[GL] live GL objects at exit: " + GlResources.live() + " " + GlResources.summary());
		ClientSession s = this.connections.session();
		if (s != null) {
			s.requestStop("window closed");
			try {
				ClientSession.Result r = this.connections.result() == null ? null : this.connections.result().get(10, java.util.concurrent.TimeUnit.SECONDS);
				System.out.println("[GL] session: " + (r == null ? "-" : r.outcome()));
			} catch (java.util.concurrent.TimeoutException e) {
				System.out.println("[GL] session did not finish within 10 s");
			} catch (InterruptedException | java.util.concurrent.ExecutionException e) {
				System.out.println("[GL] session: " + e);
			}
		} else {
			this.connections.cancel();
		}
		this.connections.awaitIdle(5000);
		this.scene = null;
	}

	// ------------------------------------------------------------------ AppServices

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
		if (this.connections.busy()) {
			return;
		}
		this.target = e;
		this.toMainMenuAfterDisconnect = false;
		LoginIdentity id = new OfflineIdentity(this.settings.get(Settings.OFFLINE_NAME));
		this.connections.connect(e, id, this.settings.get(Settings.RENDER_DISTANCE), true);
		Screen top = this.ui.current();
		if (top instanceof ConnectingScreen || top instanceof DisconnectedScreen) {
			this.ui.replace(new ConnectingScreen(this, e));
		} else {
			this.ui.open(new ConnectingScreen(this, e));
		}
		System.out.println("[APP] connecting to " + e.address() + " as " + id.name());
	}

	@Override
	public void disconnect(final boolean toMainMenu) {
		this.toMainMenuAfterDisconnect = toMainMenu;
		this.connections.disconnect("Disconnected by player");
		System.out.println("[APP] disconnect requested (" + (toMainMenu ? "main menu" : "disconnected screen") + ")");
	}

	@Override
	public void resumeGame() {
		this.ui.closeAll();
		if (this.scene != null) {
			this.scene.requestCapture();
		}
	}

	@Override
	public boolean inGame() {
		return this.scene != null && this.scene.session.playing();
	}

	@Override
	public void quit() {
		System.out.println("[APP] quit requested");
		this.window.requestClose();
	}

	@Override
	public List<String> fullscreenModes() {
		return this.videoModes;
	}

	@Override
	public String platformInfo() {
		Runtime rt = Runtime.getRuntime();
		return String.format(Locale.ROOT, "%s · Java %s · %s %s · Fenster %dx%d, Systemskalierung %.0f %% · Heap max. %d MB · Einstellungen: %s",
			this.window.glInfo(), System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"), this.window.width(),
			this.window.height(), this.window.contentScale() * 100, rt.maxMemory() >> 20,
			this.settings.file() == null ? "nur im Speicher" : this.settings.file());
	}

	// ------------------------------------------------------------------ für den GUI-Test

	ScreenManager screens() {
		return this.ui;
	}

	Window window() {
		return this.window;
	}

	GameScene scene() {
		return this.scene;
	}

	ClientResources resources() {
		return this.res;
	}

	GameView.FrameStats frameStats() {
		return this.fps;
	}

	InputManager input() {
		return this.input;
	}
}
