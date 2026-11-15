package dev.mcresearch.protocolclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Testhilfe: serverseitige Beobachtung über die Konsole des lokalen Testservers (docs/17).
 * Schreibt Befehle in {@code server/console.in} (wird per {@code tail -f} in die Serverkonsole geleitet)
 * und liest die Antworten aus {@code server/logs/latest.log}. Nur für Integrationstests.
 */
final class ServerConsole {
	private static final Pattern VECTOR = Pattern.compile("has the following entity data: \\[(-?[0-9.E-]+)[df], (-?[0-9.E-]+)[df](?:, (-?[0-9.E-]+)[df])?]");

	private final Path serverDir;

	private ServerConsole(final Path serverDir) {
		this.serverDir = serverDir;
	}

	static Optional<ServerConsole> fromSystemProperty() {
		String dir = System.getProperty("mcclient.serverDir", "");
		if (dir.isBlank()) {
			return Optional.empty();
		}
		Path p = Path.of(dir);
		return Files.isRegularFile(p.resolve("console.in")) && Files.isRegularFile(p.resolve("logs/latest.log")) ? Optional.of(new ServerConsole(p)) : Optional.empty();
	}

	/** Sendet Befehle ohne Auswertung der Antwort und wartet kurz. */
	void send(final List<String> commands, final long waitMillis) throws IOException, InterruptedException {
		Files.writeString(this.serverDir.resolve("console.in"), String.join("\n", commands) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		Thread.sleep(waitMillis);
	}

	private static final Pattern CHECK_RESULT = Pattern.compile("Test passed|Test failed|position is not loaded|<--\\[HERE\\]");

	/**
	 * Führt {@code execute if block …}-Prüfungen aus und liefert je Befehl die Antwortzeile in Reihenfolge
	 * ("Test passed", "Test failed", Fehlermeldung).
	 */
	List<String> check(final List<String> commands) throws IOException, InterruptedException {
		Path log = this.serverDir.resolve("logs/latest.log");
		long before = Files.readAllLines(log, StandardCharsets.UTF_8).size();
		send(commands, 0);
		List<String> results = new java.util.ArrayList<>();
		for (int i = 0; i < 300 && results.size() < commands.size(); i++) {
			Thread.sleep(100);
			results.clear();
			List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
			for (int k = (int) before; k < lines.size(); k++) {
				Matcher m = CHECK_RESULT.matcher(lines.get(k));
				if (m.find()) {
					results.add(m.group());
				}
			}
		}
		if (results.size() != commands.size()) {
			throw new IOException("expected " + commands.size() + " check results, got " + results.size());
		}
		return results;
	}

	/** Führt {@code data get entity <name> <path>} aus und liefert die Vektorwerte (Pos: 3, Rotation: 2). */
	double[] dataGet(final String player, final String path) throws IOException, InterruptedException {
		Path log = this.serverDir.resolve("logs/latest.log");
		long before = Files.readAllLines(log, StandardCharsets.UTF_8).size();
		Files.writeString(this.serverDir.resolve("console.in"), "data get entity " + player + " " + path + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		for (int i = 0; i < 40; i++) {
			Thread.sleep(100);
			List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
			for (int k = (int) before; k < lines.size(); k++) {
				Matcher m = VECTOR.matcher(lines.get(k));
				if (m.find()) {
					return m.group(3) == null
						? new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))}
						: new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))};
				}
			}
		}
		throw new IOException("no response to data get entity " + player + " " + path);
	}
}
