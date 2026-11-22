package dev.mcresearch.protocolclient.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.app.ConnectionManager;
import dev.mcresearch.protocolclient.app.ServerEntry;
import dev.mcresearch.protocolclient.app.ServerStatus;
import dev.mcresearch.protocolclient.input.GameAction;
import dev.mcresearch.protocolclient.input.InputCode;
import dev.mcresearch.protocolclient.input.Keys;
import dev.mcresearch.protocolclient.settings.Settings;
import dev.mcresearch.protocolclient.settings.SettingsManager;
import dev.mcresearch.protocolclient.ui.screen.AboutScreen;
import dev.mcresearch.protocolclient.ui.screen.ConnectingScreen;
import dev.mcresearch.protocolclient.ui.screen.DisconnectedScreen;
import dev.mcresearch.protocolclient.ui.screen.LocalServerScreen;
import dev.mcresearch.protocolclient.ui.screen.LoginScreen;
import dev.mcresearch.protocolclient.ui.screen.MainMenuScreen;
import dev.mcresearch.protocolclient.ui.screen.MultiplayerScreen;
import dev.mcresearch.protocolclient.ui.screen.PauseScreen;
import dev.mcresearch.protocolclient.ui.screen.ServerEditScreen;
import dev.mcresearch.protocolclient.ui.screen.SettingsScreen;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M15 Phase K (ohne Fenster): Menüführung mit Maus und Tastatur, Einstellungen, Tastenbelegung, Darstellung bei allen
 * geprüften Auflösungen und GUI-Skalierungen (docs/62 §3–§4). Gezeichnet wird mit der echten Java2D-Fläche.
 */
class MenuTest {
	@TempDir
	Path dir;
	private FakeApp app = new FakeApp();
	private final ScreenManager m = new ScreenManager();
	private final Java2DCanvas canvas = new Java2DCanvas();

	private void setUp(final int w, final int h, final int scale) {
		this.app.manager = this.m;
		this.m.setMeasure(this.canvas);
		this.m.resize(w, h, scale);
	}

	@AfterEach
	void tearDown() {
		this.app.pinger.close();
	}

	private void render() {
		this.canvas.begin(this.m.pixelWidth(), this.m.pixelHeight(), this.m.scale());
		this.m.render(this.canvas);
		this.canvas.end();
	}

	private void click(final String id) {
		Widget w = this.m.find(id);
		assertNotNull(w, "widget " + id + " in " + this.m.dump());
		if (w.parent() instanceof ScrollContainer sc) {
			sc.scrollToShow(w); // wie ein Nutzer, der zuerst hinscrollt
		}
		double[] p = this.m.pixelCenter(w);
		this.m.mouseButton(true, 0, p[0], p[1]);
		this.m.mouseButton(false, 0, p[0], p[1]);
	}

	private void key(final int k) {
		this.m.keyPressed(k, 0);
	}

	private String focus() {
		Widget f = this.m.context().focused();
		return f == null ? "-" : f.id();
	}

	@Test
	void guiScaleFollowsVanillaRule() {
		assertEquals(3, ScreenManager.computeScale(0, 1280, 720));
		assertEquals(3, ScreenManager.computeScale(0, 1600, 900));
		assertEquals(4, ScreenManager.computeScale(0, 1920, 1080));
		assertEquals(2, ScreenManager.computeScale(0, 854, 480));
		assertEquals(1, ScreenManager.computeScale(0, 400, 300));
		assertEquals(2, ScreenManager.computeScale(2, 1920, 1080));
		assertEquals(2, ScreenManager.computeScale(6, 854, 480), "capped by window size");
		assertEquals(9, ScreenManager.computeScale(0, 3840, 2160), "high-DPI framebuffer: larger automatic scale");
	}

	@Test
	void keyboardOnlyNavigation() {
		setUp(1280, 720, 0);
		this.m.reset(new MainMenuScreen(this.app));
		assertEquals("main-menu", this.m.current().name());
		key(Keys.TAB);
		assertEquals("local", focus());
		key(Keys.DOWN);
		assertEquals("multiplayer", focus());
		key(Keys.DOWN);
		assertEquals("settings", focus());
		this.m.keyPressed(Keys.TAB, Keys.MOD_SHIFT);
		assertEquals("multiplayer", focus());
		key(Keys.DOWN);
		key(Keys.ENTER);
		assertEquals("settings", this.m.current().name());
		key(Keys.ESCAPE);
		assertEquals("main-menu", this.m.current().name());
		assertEquals("settings", focus(), "focus restored after back");
		key(Keys.UP);
		key(Keys.UP);
		key(Keys.SPACE);
		assertEquals("local-server", this.m.current().name());
		key(Keys.ESCAPE);
		// Fokus wandert zyklisch: hinter „Spiel beenden“ wieder an den Anfang
		for (int i = 0; i < 5; i++) {
			key(Keys.DOWN);
		}
		assertEquals("quit", focus());
		key(Keys.DOWN);
		assertEquals("local", focus());
		key(Keys.UP);
		key(Keys.ENTER);
		assertEquals(List.of("quit"), this.app.calls);
		key(Keys.ESCAPE);
		assertEquals("main-menu", this.m.current().name(), "Escape does not leave the main menu");
	}

	@Test
	void mouseNavigationAndHover() {
		setUp(1600, 900, 0);
		this.m.reset(new MainMenuScreen(this.app));
		double[] p = this.m.pixelCenter(this.m.find("about"));
		this.m.mouseMoved(p[0], p[1]);
		assertEquals("about", this.m.context().hovered().id());
		assertFalse(this.m.context().showFocus(), "no focus frame for mouse users");
		click("about");
		assertInstanceOf(AboutScreen.class, this.m.current());
		click("back");
		click("multiplayer");
		assertInstanceOf(MultiplayerScreen.class, this.m.current());
		// Drücken über dem Knopf, Loslassen daneben: kein Auslösen (wie Vanilla)
		double[] b = this.m.pixelCenter(this.m.find("back"));
		this.m.mouseButton(true, 0, b[0], b[1]);
		this.m.mouseButton(false, 0, 2, 2);
		assertInstanceOf(MultiplayerScreen.class, this.m.current());
		click("back");
		assertEquals(3, this.m.openedCount());
		assertEquals(2, this.m.closedCount());
	}

	@Test
	void settingsChangeWithMouseAndKeyboardAndPersist() {
		Path f = this.dir.resolve("settings.properties");
		this.app = new FakeApp(SettingsManager.load(f));
		setUp(1280, 720, 0);
		this.m.reset(new MainMenuScreen(this.app));
		click("settings");
		SettingsScreen s = assertInstanceOf(SettingsScreen.class, this.m.current());
		// Checkbox per Maus
		click(Settings.VSYNC.key());
		assertFalse(this.app.settings.get(Settings.VSYNC));
		// Regler per Tastatur: Fokus auf „Sichtfeld“, zweimal rechts
		this.m.focus(this.m.find(Settings.FOV.key()));
		key(Keys.RIGHT);
		key(Keys.RIGHT);
		assertEquals(72, this.app.settings.get(Settings.FOV));
		// Regler per Maus: ganz links klicken
		Widget rd = this.m.find(Settings.RENDER_DISTANCE.key());
		this.m.mouseButton(true, 0, (rd.bounds().x() + 1) * this.m.scale(), rd.bounds().centerY() * this.m.scale());
		this.m.mouseButton(false, 0, (rd.bounds().x() + 1) * this.m.scale(), rd.bounds().centerY() * this.m.scale());
		assertEquals(2, this.app.settings.get(Settings.RENDER_DISTANCE));
		// Auswahlliste per Tastatur öffnen und Eintrag wählen
		this.m.focus(this.m.find(Settings.PARTICLES.key()));
		key(Keys.ENTER);
		assertNotNull(this.m.popup());
		key(Keys.DOWN);
		key(Keys.ENTER);
		assertNull(this.m.popup());
		assertEquals("DECREASED", this.app.settings.get(Settings.PARTICLES));
		// geplante Wolkenoption ist nicht wählbar
		Dropdown clouds = (Dropdown) this.m.find(Settings.CLOUDS.key());
		clouds.select("FAST");
		assertEquals("FANCY", this.app.settings.get(Settings.CLOUDS));
		// Reiter wechseln per Maus und Oberfläche ändern
		TabNavigation tabs = (TabNavigation) this.m.find("tabs");
		Rect t3 = tabs.tabRect(3);
		this.m.mouseButton(true, 0, t3.centerX() * this.m.scale(), t3.centerY() * this.m.scale());
		this.m.mouseButton(false, 0, t3.centerX() * this.m.scale(), t3.centerY() * this.m.scale());
		assertEquals(3, s.tab());
		click(Settings.HIGH_CONTRAST.key());
		assertTrue(this.app.settings.get(Settings.HIGH_CONTRAST));
		// Fertig speichert; neu laden liefert dieselben Werte
		click("done");
		assertEquals("main-menu", this.m.current().name());
		SettingsManager reloaded = SettingsManager.load(f);
		assertFalse(reloaded.get(Settings.VSYNC));
		assertEquals(72, reloaded.get(Settings.FOV));
		assertEquals(2, reloaded.get(Settings.RENDER_DISTANCE));
		assertEquals("DECREASED", reloaded.get(Settings.PARTICLES));
		assertTrue(reloaded.get(Settings.HIGH_CONTRAST));
	}

	@Test
	void resetToDefaultsViaDialog() {
		setUp(1280, 720, 0);
		this.app.settings.set(Settings.FOV, 100);
		this.app.settings.bind(GameAction.JUMP, InputCode.key('J'));
		this.m.reset(new SettingsScreen(this.app));
		click("reset");
		assertNotNull(this.m.dialog());
		key(Keys.ESCAPE); // Abbrechen
		assertNull(this.m.dialog());
		assertEquals(100, this.app.settings.get(Settings.FOV));
		click("reset");
		click("confirm-reset.yes");
		assertEquals(70, this.app.settings.get(Settings.FOV));
		assertEquals(GameAction.JUMP.defaultCode, this.app.settings.bindings().get(GameAction.JUMP));
		assertEquals(70.0, ((Slider) this.m.find(Settings.FOV.key())).value(), "widgets show the reset values");
	}

	@Test
	void keyBindingEditorCapturesKeysMouseAndDetectsConflicts() {
		setUp(1280, 720, 0);
		this.m.reset(new SettingsScreen(this.app));
		((TabNavigation) this.m.find("tabs")).select(2);
		KeyBindingEditor ed = (KeyBindingEditor) this.m.find("keys");
		assertEquals(0, ed.conflictCount());
		click("keys.key.jump");
		assertEquals("keys.key.jump", this.m.captureOwner().id());
		key('J');
		assertNull(this.m.captureOwner());
		assertEquals(InputCode.key('J'), this.app.settings.bindings().get(GameAction.JUMP));
		// Tastatur: Fokus auf „Sprinten“, Bestätigen, dann J → Konflikt mit Springen
		this.m.focus(this.m.find("keys.key.sprint"));
		key(Keys.ENTER);
		key('J');
		assertEquals(2, ed.conflictCount());
		assertTrue(this.m.find("keys.key.sprint").describe().contains("CONFLICT"));
		render();
		// Maustaste belegen
		click("keys.key.use");
		double[] p = this.m.pixelCenter(this.m.find("keys.key.use"));
		this.m.mouseButton(true, 4, p[0], p[1]);
		this.m.mouseButton(false, 4, p[0], p[1]);
		assertEquals(InputCode.mouse(4), this.app.settings.bindings().get(GameAction.USE));
		// Mausrad belegen
		click("keys.key.drop_item");
		this.m.scrolled(p[0], p[1], -1);
		assertEquals(InputCode.wheel(InputCode.WHEEL_DOWN), this.app.settings.bindings().get(GameAction.DROP_ITEM));
		// Esc = nicht belegt (Vanilla); Zurücksetzen-Knopf stellt den Standard her
		click("keys.key.sprint");
		key(Keys.ESCAPE);
		assertEquals(InputCode.NONE, this.app.settings.bindings().get(GameAction.SPRINT));
		assertEquals(2, ed.conflictCount(), "J resolved; wheel down is still shared by 'drop' and 'next hotbar slot'");
		assertTrue(this.m.find("keys.key.hotbar_next").describe().contains("CONFLICT"));
		click("keys.reset.drop_item");
		assertEquals(0, ed.conflictCount());
		click("keys.reset.sprint");
		assertEquals(GameAction.SPRINT.defaultCode, this.app.settings.bindings().get(GameAction.SPRINT));
		assertEquals("settings", this.m.current().name(), "Escape during capture did not close the screen");
	}

	@Test
	void textInputAndAccountScreen() {
		setUp(1280, 720, 0);
		this.m.reset(new LoginScreen(this.app));
		TextInput name = (TextInput) this.m.find("offline-name");
		this.m.focus(name);
		for (int i = 0; i < 20; i++) {
			key(Keys.BACKSPACE);
		}
		assertEquals("", name.text());
		for (char c : "Ab c_1ü!".toCharArray()) {
			this.m.charTyped(c);
		}
		assertEquals("Abc_1", name.text(), "filter drops invalid characters");
		assertEquals("Abc_1", this.app.settings.get(Settings.OFFLINE_NAME));
		key(Keys.BACKSPACE);
		key(Keys.BACKSPACE);
		key(Keys.BACKSPACE);
		assertEquals("Ab", name.text());
		assertFalse(name.valid());
		assertEquals("Abc", this.app.settings.get(Settings.OFFLINE_NAME), "last valid name stays, invalid is not stored");
		this.m.context().clipboard().set("Merlin_42");
		key(Keys.HOME);
		this.m.keyPressed(Keys.V, Keys.MOD_CONTROL);
		assertEquals("Merlin_42Ab", name.text());
		assertFalse(this.m.find("microsoft").enabled(), "Microsoft login stays disabled");
	}

	@Test
	void multiplayerListMarksExternalServersAndRemembersVersion() throws Exception {
		setUp(1280, 720, 0);
		this.app.servers.add(new ServerEntry("Lokal", "127.0.0.1", 25599, "", 0));
		this.app.servers.add(new ServerEntry("Extern", "play.example.org", 25565, "", 0));
		this.m.reset(new MultiplayerScreen(this.app));
		click("server.1");
		assertFalse(this.m.find("join").enabled(), "external server cannot be joined");
		click("server.0");
		for (int i = 0; i < 100 && !this.m.find("server.0").describe().contains("OFFLINE"); i++) {
			Thread.sleep(50);
			this.m.tick(System.nanoTime());
		}
		assertTrue(this.m.find("server.0").describe().contains("OFFLINE"), this.m.find("server.0").describe());
		assertTrue(this.m.find("server.1").describe().contains("BLOCKED"));
		// Bearbeiten: ungültiger Port
		click("edit");
		TextInput addr = (TextInput) this.m.find("server-address");
		addr.setText("127.0.0.1:99999");
		addr.insert("");
		this.m.focus(addr);
		this.m.charTyped('9');
		assertFalse(this.m.find("save").enabled());
		click("cancel");
		assertInstanceOf(MultiplayerScreen.class, this.m.current());
		this.app.servers.rememberVersion(0, "1.21.4", 769);
		assertEquals(769, this.app.servers.entries().getFirst().lastProtocol(), "server version stored independently of client version");
	}

	@Test
	void connectingScreenShowsFailureAndRetry() throws Exception {
		setUp(1280, 720, 0);
		this.m.reset(new LocalServerScreen(this.app));
		this.app.status = ServerStatus.offline("x");
		click("join");
		ConnectingScreen cs = assertInstanceOf(ConnectingScreen.class, this.m.current());
		for (int i = 0; i < 100 && cs.phase() != ConnectionManager.Phase.FAILED; i++) {
			Thread.sleep(20);
			this.m.tick(System.nanoTime());
		}
		assertEquals(ConnectionManager.Phase.FAILED, cs.phase());
		assertEquals("Server nicht erreichbar", ((TextLabel) this.m.find("error-title")).text());
		assertTrue(cs.history().endsWith("FAILED"), cs.history());
		render();
		assertEquals(0, this.canvas.overflows(), this.canvas.overflowSamples().toString());
		// nicht unterstützte Version
		this.app.status = new ServerStatus(ServerStatus.State.ONLINE, 1, "1.21.4", 769, 0, 20, "", "");
		click("retry");
		cs = assertInstanceOf(ConnectingScreen.class, this.m.current());
		for (int i = 0; i < 100 && cs.phase() != ConnectionManager.Phase.FAILED; i++) {
			Thread.sleep(20);
			this.m.tick(System.nanoTime());
		}
		assertEquals("Nicht unterstützte Serverversion", ((TextLabel) this.m.find("error-title")).text());
		assertTrue(((TextLabel) this.m.find("error-detail")).text().contains("Merlin Multi Version System"));
		key(Keys.ESCAPE);
		assertEquals(ConnectionManager.Phase.IDLE, this.app.connections.phase());
	}

	@Test
	void pauseScreenResumesOnEscape() {
		setUp(1280, 720, 0);
		this.app.inGame = true;
		this.m.open(new PauseScreen(this.app));
		assertTrue(this.m.blocksWorldInput());
		assertFalse(this.m.gameHandlesInput());
		key(Keys.ESCAPE);
		assertEquals(List.of("resume"), this.app.calls);
		assertFalse(this.m.hasScreen());
		this.m.open(new PauseScreen(this.app));
		click("main-menu");
		click("disconnect");
		assertEquals(List.of("resume", "disconnect true", "disconnect false"), this.app.calls);
		// Spiel-Screen (Inventar): Eingaben gehen an das Spiel, Welt bleibt gesperrt
		this.m.closeAll();
		GameScreen inv = new GameScreen("inventory", "Inventar", () -> this.app.calls.add("inv-back"));
		this.m.open(inv);
		assertTrue(this.m.gameHandlesInput());
		assertTrue(this.m.blocksWorldInput());
		key(Keys.ESCAPE);
		assertEquals("inv-back", this.app.calls.getLast());
	}

	/** Alle Screens in allen Zuständen bei allen geprüften Auflösungen und Skalierungen: keine abgeschnittenen Texte, nichts außerhalb. */
	@Test
	void allScreensFitAtAllTestedResolutionsAndScales() {
		this.app.servers.add(new ServerEntry("Lokaler Testserver (langer Name)", "127.0.0.1", 25565, "26.3", 777));
		this.app.servers.add(new ServerEntry("Extern", "play.example.org", 25565, "", 0));
		List<Function<FakeApp, Screen>> screens = new ArrayList<>();
		screens.add(MainMenuScreen::new);
		screens.add(LocalServerScreen::new);
		screens.add(MultiplayerScreen::new);
		screens.add(a -> new ServerEditScreen(a, 0));
		screens.add(AboutScreen::new);
		screens.add(LoginScreen::new);
		screens.add(PauseScreen::new);
		screens.add(a -> new DisconnectedScreen(a, "Verbindung getrennt", "Der Server hat die Verbindung beendet: Server wird neu gestartet. "
			+ "Bitte in einer Minute erneut versuchen.", ServerEntry.localTestServer()));
		for (int tab = 0; tab < 4; tab++) {
			final int t = tab;
			screens.add(a -> {
				SettingsScreen s = new SettingsScreen(a);
				this.m.reset(s);
				((TabNavigation) s.find("tabs")).select(t);
				return null;
			});
		}
		int[][] sizes = {{1280, 720}, {1600, 900}, {1920, 1080}, {854, 480}, {800, 600}, {640, 480}, {2560, 1440}, {3840, 2160}};
		List<String> problems = new ArrayList<>();
		int checked = 0;
		for (int[] size : sizes) {
			for (int scale : new int[]{0, 1, 2, 3, 4}) {
				for (Function<FakeApp, Screen> f : screens) {
					setUp(size[0], size[1], scale);
					Screen s = f.apply(this.app);
					if (s != null) {
						this.m.reset(s);
					}
					this.m.tick(System.nanoTime());
					render();
					String where = this.m.current().name() + " " + size[0] + "x" + size[1] + " scale " + this.m.scale();
					if (this.canvas.overflows() > 0) {
						problems.add(where + " overflow " + this.canvas.overflowSamples());
					}
					problems.addAll(outside(this.m.current(), where));
					checked++;
				}
			}
		}
		// Dialog über dem Hauptmenü
		setUp(854, 480, 0);
		this.m.reset(new MainMenuScreen(this.app));
		this.m.showDialog(new Dialog("d", "Fehler beim Verbinden", "Server nicht erreichbar: An 127.0.0.1:25565 antwortet kein Minecraft-Server. "
			+ "Läuft der lokale Testserver?", true, List.of(new Dialog.Choice("ok", "OK", () -> {
		}))));
		render();
		assertEquals(0, this.canvas.overflows(), this.canvas.overflowSamples().toString());
		assertTrue(checked >= 400, "checked " + checked);
		assertTrue(problems.isEmpty(), problems.size() + " problems:\n" + String.join("\n", problems.subList(0, Math.min(25, problems.size()))));
	}

	private List<String> outside(final Screen s, final String where) {
		List<String> out = new ArrayList<>();
		Rect screen = new Rect(0, 0, this.m.width(), this.m.height());
		List<Widget> top = new ArrayList<>();
		for (Widget w : s.widgets()) {
			if (!w.visible()) {
				continue;
			}
			if (!screen.encloses(w.bounds())) {
				out.add(where + ": " + w.describe() + " outside " + w.bounds());
			}
			if (w.focusable()) {
				top.add(w);
			}
		}
		for (int i = 0; i < top.size(); i++) {
			for (int j = i + 1; j < top.size(); j++) {
				if (top.get(i).bounds().intersects(top.get(j).bounds())) {
					out.add(where + ": overlap " + top.get(i).id() + " / " + top.get(j).id());
				}
			}
		}
		return out;
	}
}
