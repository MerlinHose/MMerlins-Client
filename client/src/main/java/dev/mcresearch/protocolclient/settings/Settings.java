package dev.mcresearch.protocolclient.settings;

import dev.mcresearch.protocolclient.settings.Setting.Category;
import java.util.List;

/**
 * Alle Einstellungen des grafischen Clients (docs/60 §2). Jede ist umgesetzt; nicht umgesetzte Optionen stehen nur in
 * {@link #PLANNED} und erscheinen in den Menüs gesperrt mit dem Hinweis „geplant“.
 */
public final class Settings {
	private Settings() {
	}

	/** Version des Dateiformats; ältere Dateien werden beim Laden migriert ({@link SettingsMigration}). */
	public static final int FORMAT_VERSION = 2;

	public static final Setting.Choice DISPLAY_MODE = new Setting.Choice("graphics.displayMode", "Anzeigemodus", Category.GRAPHICS, "WINDOWED",
		List.of("WINDOWED", "FULLSCREEN", "BORDERLESS"), List.of("Fenster", "Vollbild", "Randloses Vollbild"),
		"Fenster, exklusives Vollbild (mit gewählter Auflösung) oder randloses Vollbild in Desktopauflösung. F11 schaltet um.");
	public static final Setting.Text FULLSCREEN_RESOLUTION = new Setting.Text("graphics.fullscreenResolution", "Vollbildauflösung",
		Category.GRAPHICS, "auto", "auto|[1-9][0-9]{2,3}x[1-9][0-9]{2,3}(@[1-9][0-9]{1,2})?", 16,
		"Auflösung im exklusiven Vollbild; „auto“ = aktuelle Bildschirmauflösung.");
	public static final Setting.Int WINDOW_WIDTH = new Setting.Int("graphics.windowWidth", "Fensterbreite", Category.GRAPHICS, 1280, 320, 7680,
		"Zuletzt verwendete Fenstergröße.");
	public static final Setting.Int WINDOW_HEIGHT = new Setting.Int("graphics.windowHeight", "Fensterhöhe", Category.GRAPHICS, 720, 240, 4320,
		"Zuletzt verwendete Fenstergröße.");
	public static final Setting.Bool VSYNC = new Setting.Bool("graphics.vsync", "VSync", Category.GRAPHICS, true,
		"Bildaufbau mit dem Bildschirm synchronisieren (kein Zerreißen).");
	public static final int UNLIMITED_FPS = 260;
	public static final Setting.Int MAX_FPS = new Setting.Int("graphics.maxFps", "Max. Bildrate", Category.GRAPHICS, 120, 10, UNLIMITED_FPS,
		"Obergrenze der Bildrate; 260 = unbegrenzt. Im Hauptmenü höchstens 60 (wie Vanilla).");
	public static final Setting.Int RENDER_DISTANCE = new Setting.Int("graphics.renderDistance", "Sichtweite", Category.GRAPHICS, 6, 2, 8,
		"Chunks; wird dem Server sofort mitgeteilt (client_information).");
	public static final Setting.Int FOV = new Setting.Int("graphics.fov", "Sichtfeld", Category.GRAPHICS, 70, 30, 110, "Senkrechtes Sichtfeld in Grad.");
	public static final Setting.Num BRIGHTNESS = new Setting.Num("graphics.brightness", "Helligkeit", Category.GRAPHICS, 0.5, 0, 1,
		"Gamma der Lightmap: 0 düster … 1 hell (Vanilla-Optionswert).");
	public static final Setting.Choice PARTICLES = new Setting.Choice("graphics.particles", "Partikel", Category.GRAPHICS, "ALL",
		List.of("ALL", "DECREASED", "MINIMAL"), List.of("Alle", "Verringert", "Minimal"),
		"Verringert: jede dritte Partikel entfällt; Minimal: nur erzwungene Partikel (wie Vanilla ParticleStatus).");
	public static final Setting.Int GUI_SCALE = new Setting.Int("graphics.guiScale", "GUI-Skalierung", Category.GRAPHICS, 0, 0, 6,
		"0 = automatisch (größte Stufe, bei der mindestens 320 × 240 Einheiten Platz bleiben).");
	public static final Setting.Choice CLOUDS = new Setting.Choice("graphics.clouds", "Wolken", Category.GRAPHICS, "FANCY", List.of("OFF", "FANCY"),
		List.of("Aus", "Schön"), "Wolkendarstellung; die Variante „Schnell“ ist geplant.");
	public static final Setting.Int BIOME_BLEND = new Setting.Int("graphics.biomeBlend", "Biomübergang", Category.GRAPHICS, 2, 0, 7,
		"Radius der Farbmischung zwischen Biomen (Gras, Laub, Wasser); Änderung baut die Chunk-Meshes neu.");
	public static final Setting.Bool AMBIENT_OCCLUSION = new Setting.Bool("graphics.ambientOcclusion", "Weiche Beleuchtung", Category.GRAPHICS, true,
		"Umgebungsverdeckung an Blockkanten; Änderung baut die Chunk-Meshes neu.");

	public static final Setting.Num SENSITIVITY = new Setting.Num("controls.sensitivity", "Mausempfindlichkeit", Category.CONTROLS, 0.5, 0, 1,
		"Vanilla-Skala: 50 % der Reglerbreite = 100 %.");
	public static final Setting.Bool INVERT_Y = new Setting.Bool("controls.invertY", "Maus invertieren", Category.CONTROLS, false,
		"Senkrechte Mausbewegung umkehren.");
	public static final Setting.Bool TOGGLE_SPRINT = new Setting.Bool("controls.toggleSprint", "Sprinten umschalten", Category.CONTROLS, false,
		"Sprinttaste schaltet um statt gehalten zu werden.");
	public static final Setting.Bool TOGGLE_SNEAK = new Setting.Bool("controls.toggleSneak", "Schleichen umschalten", Category.CONTROLS, false,
		"Schleichtaste schaltet um statt gehalten zu werden.");

	public static final Setting.Bool SHOW_HUD = new Setting.Bool("interface.showHud", "HUD anzeigen", Category.INTERFACE, true,
		"Fadenkreuz, Schnellzugriffsleiste, Lebensanzeige, Namensschilder (F1).");
	public static final Setting.Bool DEBUG_OVERLAY = new Setting.Bool("interface.debugOverlay", "Debug-Anzeige", Category.INTERFACE, false,
		"F3-Informationen beim Start anzeigen.");
	public static final Setting.Bool TOOLTIPS = new Setting.Bool("interface.tooltips", "Tooltips anzeigen", Category.INTERFACE, true,
		"Erklärungen beim Verweilen über Bedienelementen.");
	public static final Setting.Bool HIGH_CONTRAST = new Setting.Bool("accessibility.highContrast", "Kontrastreiche Menüs", Category.ACCESSIBILITY,
		false, "Schwarzer Hintergrund, weiße Schrift, gelber Fokusrahmen.");
	public static final Setting.Bool REDUCE_MOTION = new Setting.Bool("accessibility.reduceMotion", "Bewegung reduzieren",
		Category.ACCESSIBILITY, false, "Kein animierter Menühintergrund.");

	public static final Setting.Text OFFLINE_NAME = new Setting.Text("account.offlineName", "Spielername (Offline-Testmodus)", Category.ACCOUNT,
		"MerlinPlayer", "[A-Za-z0-9_]{3,16}", 16, "3–16 Zeichen: Buchstaben, Ziffern, Unterstrich.");

	/** Alle Einstellungen in Menüreihenfolge. */
	public static final List<Setting<?>> ALL = List.of(DISPLAY_MODE, FULLSCREEN_RESOLUTION, WINDOW_WIDTH, WINDOW_HEIGHT, VSYNC, MAX_FPS,
		RENDER_DISTANCE, FOV, BRIGHTNESS, PARTICLES, GUI_SCALE, CLOUDS, BIOME_BLEND, AMBIENT_OCCLUSION, SENSITIVITY, INVERT_Y, TOGGLE_SPRINT,
		TOGGLE_SNEAK, SHOW_HUD, DEBUG_OVERLAY, TOOLTIPS, HIGH_CONTRAST, REDUCE_MOTION, OFFLINE_NAME);

	/** Geplante, noch nicht umgesetzte Optionen (nur Anzeige, nicht in der Datei). */
	public record Planned(String label, Category category, String reason) {
	}

	public static final List<Planned> PLANNED = List.of(
		new Planned("Wolken „Schnell“", Category.GRAPHICS, "Nur flache Wolken fehlen noch."),
		new Planned("Perspektive (F5)", Category.CONTROLS, "Der Client zeichnet nur die Ich-Perspektive."),
		new Planned("Controller", Category.CONTROLS, "Native Controller-Unterstützung mit input4j folgt in M17."),
		new Planned("Musik und Geräusche", Category.INTERFACE, "Der Client hat noch keine Tonausgabe."),
		new Planned("Sprache", Category.INTERFACE, "Die Oberfläche ist derzeit nur auf Deutsch."),
		new Planned("Microsoft-Konto", Category.ACCOUNT, "Echte Microsoft-Anmeldung ist bis zum letzten Meilenstein zurückgestellt."));

	public static Setting<?> byKey(final String key) {
		for (Setting<?> s : ALL) {
			if (s.key().equals(key)) {
				return s;
			}
		}
		return null;
	}
}
