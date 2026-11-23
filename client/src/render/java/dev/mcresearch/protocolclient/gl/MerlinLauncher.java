package dev.mcresearch.protocolclient.gl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Einstieg der Testdistribution (docs/61 §8): prüft die Voraussetzungen und startet den grafischen Client im Hauptmenü.
 *
 * <ul>
 *   <li>Java 25 oder neuer.</li>
 *   <li>Minecraft-26.3-Ressourcen aus einer lokal installierten, rechtmäßig erworbenen Spielversion. Suchreihenfolge:
 *     {@code --assets <jar>}, Umgebungsvariable {@code MERLIN_MINECRAFT_JAR},
 *     {@code %APPDATA%\.minecraft\versions\26.3\26.3.jar} bzw. {@code ~/.minecraft/versions/26.3/26.3.jar}. Die Distribution
 *     selbst enthält keine Mojang-Dateien.</li>
 * </ul>
 *
 * Fehlt etwas, erscheint eine verständliche Meldung (Konsole und, wenn möglich, Dialog) und der Prozess endet mit Code 2.
 */
public final class MerlinLauncher {
	private MerlinLauncher() {
	}

	public static void main(final String[] args) throws Exception {
		List<String> problems = new ArrayList<>();
		int java = Runtime.version().feature();
		if (java < 25) {
			problems.add("Java " + java + " gefunden, benötigt wird Java 25 oder neuer (z. B. Microsoft Build of OpenJDK 25).");
		}
		List<String> a = new ArrayList<>(List.of(args));
		Path assets = null;
		int i = a.indexOf("--assets");
		if (i >= 0 && i + 1 < a.size()) {
			assets = Path.of(a.get(i + 1));
			a.remove(i + 1);
			a.remove(i);
		}
		if (assets == null) {
			assets = findMinecraftJar();
		}
		if (assets == null) {
			problems.add("Keine Minecraft-26.3-Installation gefunden. Bitte Minecraft 26.3 einmal mit dem offiziellen Launcher starten "
				+ "(dadurch entsteht .minecraft/versions/26.3/26.3.jar) oder den Pfad mit --assets <jar> bzw. MERLIN_MINECRAFT_JAR angeben.");
		} else if (!valid(assets)) {
			problems.add("Die Datei " + assets + " ist keine lesbare Minecraft-26.3-JAR (assets/minecraft fehlt).");
		}
		if (!problems.isEmpty()) {
			fail(problems);
			return;
		}
		System.out.println("[LAUNCHER] Java " + Runtime.version() + ", Ressourcen " + assets);
		List<String> full = new ArrayList<>(List.of("--assets", assets.toString()));
		full.addAll(a);
		GraphicalMain.main(full.toArray(String[]::new));
	}

	static Path findMinecraftJar() {
		String env = System.getenv("MERLIN_MINECRAFT_JAR");
		if (env != null && !env.isBlank() && Files.isRegularFile(Path.of(env))) {
			return Path.of(env);
		}
		List<Path> candidates = new ArrayList<>();
		String appData = System.getenv("APPDATA");
		if (appData != null) {
			candidates.add(Path.of(appData, ".minecraft", "versions", "26.3", "26.3.jar"));
		}
		candidates.add(Path.of(System.getProperty("user.home"), ".minecraft", "versions", "26.3", "26.3.jar"));
		for (Path p : candidates) {
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		return null;
	}

	static boolean valid(final Path jar) {
		if (!Files.isRegularFile(jar)) {
			return false;
		}
		try (ZipFile z = new ZipFile(jar.toFile())) {
			return z.getEntry("assets/minecraft/models/block/stone.json") != null;
		} catch (java.io.IOException e) {
			return false;
		}
	}

	private static void fail(final List<String> problems) {
		String text = "Minecraft Merlin Client kann nicht starten:\n\n- " + String.join("\n- ", problems);
		System.err.println(text);
		try {
			if (!java.awt.GraphicsEnvironment.isHeadless() && System.getenv("MERLIN_NO_DIALOG") == null) {
				javax.swing.JOptionPane.showMessageDialog(null, text, "Minecraft Merlin Client", javax.swing.JOptionPane.ERROR_MESSAGE);
			}
		} catch (RuntimeException | Error e) {
			// Dialog nicht möglich: Meldung steht in der Konsole
		}
		System.exit(2);
	}
}
