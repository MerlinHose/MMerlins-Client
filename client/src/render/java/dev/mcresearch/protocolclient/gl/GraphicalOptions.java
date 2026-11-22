package dev.mcresearch.protocolclient.gl;

import java.nio.file.Path;

/**
 * Kommandozeilenoptionen des grafischen Clients (docs/24 §9). M15: Ohne {@code --autotest}, {@code --direct} und {@code --login-ui} startet der
 * Client im Hauptmenü und verwendet die Nutzereinstellungen; mit ihnen verbindet er sofort wie bisher (Regressionsläufe, Einstellungen nur im
 * Speicher aus den Startoptionen).
 */
public record GraphicalOptions(String host, int port, String username, int width, int height, float fov, boolean vsync, int renderDistance,
	Path assets, boolean textures, boolean testCube, Path screenshot, long screenshotFrame, double exitAfterSeconds, Path autotest,
	Path report, Path packetLog, boolean autoRespawn, dev.mcresearch.protocolclient.auth.minecraft.LoginFactory.Parser auth, boolean loginUi,
	Path skins, Path settingsFile, Path serversFile, boolean direct, Path uiTest, boolean noGrab) {

	public static final int MAX_RENDER_DISTANCE = 8;

	/** Sofort verbinden ohne Hauptmenü (bisheriges Verhalten, alle Regressionsläufe). */
	public boolean directMode() {
		return this.direct || this.autotest != null || this.loginUi;
	}

	public static GraphicalOptions parse(final String[] args) {
		String host = "127.0.0.1";
		int port = 25565;
		String username = "ProtoBotGfx";
		int width = 1280;
		int height = 720;
		float fov = 70f;
		boolean vsync = true;
		int renderDistance = 2;
		Path assets = null;
		boolean textures = true;
		boolean testCube = false;
		boolean autoRespawn = false;
		Path screenshot = null;
		long screenshotFrame = 120;
		double exitAfter = 0;
		Path autotest = null;
		Path report = null;
		Path packetLog = null;
		var auth = new dev.mcresearch.protocolclient.auth.minecraft.LoginFactory.Parser(); // M13 (docs/52 §5)
		boolean loginUi = false;
		Path skins = null;
		Path settingsFile = null;
		Path serversFile = null;
		boolean direct = false;
		Path uiTest = null;
		boolean noGrab = false;
		for (int i = 0; i < args.length; i++) {
			String a = args[i];
			int consumed = auth.parse(args, i);
			if (consumed >= 0) {
				i = consumed;
				continue;
			}
			switch (a) {
				case "--host" -> host = args[++i];
				case "--port" -> port = Integer.parseInt(args[++i]);
				case "--username" -> username = args[++i];
				case "--width" -> width = Integer.parseInt(args[++i]);
				case "--height" -> height = Integer.parseInt(args[++i]);
				case "--fov" -> fov = Float.parseFloat(args[++i]);
				case "--vsync" -> vsync = true;
				case "--no-vsync" -> vsync = false;
				case "--render-distance" -> renderDistance = Integer.parseInt(args[++i]);
				case "--assets" -> assets = GraphicalMain.path(args[++i]);
				case "--colors" -> textures = false;
				case "--test-cube" -> testCube = true;
				case "--auto-respawn" -> autoRespawn = true; // M10: nur für Regressionsläufe; sonst Todesbildschirm
				case "--screenshot" -> screenshot = Path.of(args[++i]);
				case "--screenshot-frame" -> screenshotFrame = Long.parseLong(args[++i]);
				case "--exit-after" -> exitAfter = Double.parseDouble(args[++i]);
				case "--autotest" -> autotest = Path.of(args[++i]);
				case "--report" -> report = Path.of(args[++i]);
				case "--packet-log" -> packetLog = Path.of(args[++i]);
				case "--login-ui" -> loginUi = true; // M13: Anmeldefenster vor dem Spielfenster
				case "--skins" -> skins = Path.of(args[++i]); // M14: lokale PNG-Skins (docs/56)
				case "--settings" -> settingsFile = Path.of(args[++i]); // M15: Einstellungsdatei statt ~/.minecraft-merlin/settings.properties
				case "--servers" -> serversFile = Path.of(args[++i]); // M15: Serverliste
				case "--direct" -> direct = true; // M15: ohne Hauptmenü sofort verbinden (bisheriges Verhalten)
				case "--test-no-grab" -> noGrab = true; // M15: GUI-Test ohne Fangen der Systemmaus
				case "--ui-test" -> uiTest = Path.of(args[++i]); // M15: GUI-Testskript im Menümodus (docs/62)
				default -> throw new IllegalArgumentException("unknown option " + a);
			}
		}
		if (width < 320 || height < 200 || width > 7680 || height > 4320) {
			throw new IllegalArgumentException("resolution 320x200 .. 7680x4320");
		}
		if (!(fov >= 30 && fov <= 110)) {
			throw new IllegalArgumentException("fov 30..110");
		}
		if (renderDistance < 1 || renderDistance > MAX_RENDER_DISTANCE) {
			throw new IllegalArgumentException("render distance 1.." + MAX_RENDER_DISTANCE);
		}
		return new GraphicalOptions(host, port, username, width, height, fov, vsync, renderDistance, assets, textures, testCube, screenshot,
			screenshotFrame, exitAfter, autotest, report, packetLog, autoRespawn, auth, loginUi, skins, settingsFile, serversFile, direct, uiTest, noGrab);
	}
}
