package dev.mcresearch.protocolclient.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Überführt ältere Dateiformate in das aktuelle (docs/60 §3.3). Format 1 (flache Schlüssel wie {@code renderDistance},
 * Empfindlichkeit in Prozent, Tastenschlüssel {@code key_jump}) ist das Vorabformat der Entwicklungsstände; der Weg ist
 * eingerichtet und getestet, damit künftige Formatänderungen denselben Mechanismus verwenden.
 */
public final class SettingsMigration {
	private SettingsMigration() {
	}

	private static final Map<String, String> V1_KEYS = Map.ofEntries(Map.entry("renderDistance", "graphics.renderDistance"),
		Map.entry("fov", "graphics.fov"), Map.entry("vsync", "graphics.vsync"), Map.entry("maxFps", "graphics.maxFps"),
		Map.entry("guiScale", "graphics.guiScale"), Map.entry("clouds", "graphics.clouds"), Map.entry("particles", "graphics.particles"),
		Map.entry("fullscreen", "graphics.displayMode"), Map.entry("invertMouse", "controls.invertY"), Map.entry("showHud", "interface.showHud"),
		Map.entry("username", "account.offlineName"));

	/** Schlüssel des Formats 1 (für die Plausibilitätsprüfung beim Laden). */
	public static boolean isLegacyKey(final String k) {
		return V1_KEYS.containsKey(k) || k.equals("mouseSensitivity") || k.startsWith("key_");
	}

	/** Liefert die Schlüssel/Werte im aktuellen Format; Hinweise für den Nutzer landen in {@code notes}. */
	public static Map<String, String> migrate(final Map<String, String> in, final int fromVersion, final List<String> notes) {
		Map<String, String> m = new LinkedHashMap<>(in);
		if (fromVersion < 2) {
			Map<String, String> out = new LinkedHashMap<>();
			for (Map.Entry<String, String> e : m.entrySet()) {
				String k = e.getKey();
				String v = e.getValue();
				if (V1_KEYS.containsKey(k)) {
					String nk = V1_KEYS.get(k);
					if (k.equals("fullscreen")) {
						v = v.strip().equalsIgnoreCase("true") ? "FULLSCREEN" : "WINDOWED";
					}
					if (k.equals("clouds") && v.strip().equalsIgnoreCase("FAST")) {
						v = "FANCY";
						notes.add("Wolken „Schnell“ ist noch nicht umgesetzt – auf „Schön“ umgestellt.");
					}
					out.put(nk, v);
				} else if (k.equals("mouseSensitivity")) {
					// Format 1: Prozent 0–200 (100 = Vanilla-Standard) → Reglerwert 0–1
					try {
						double pct = Double.parseDouble(v.strip());
						out.put("controls.sensitivity", String.format(Locale.ROOT, "%.4f", Math.max(0, Math.min(200, pct)) / 200.0));
					} catch (NumberFormatException ex) {
						notes.add("Mausempfindlichkeit „" + v + "“ unlesbar – Standard verwendet.");
					}
				} else if (k.startsWith("key_")) {
					out.put("controls.key." + k.substring(4), v);
				} else {
					out.put(k, v);
				}
			}
			notes.add("Einstellungen aus Format " + fromVersion + " übernommen.");
			m = out;
		}
		m.put("settings.version", Integer.toString(Settings.FORMAT_VERSION));
		return m;
	}
}
