package dev.mcresearch.protocolclient.auth.minecraft;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * M13: Herkunft der Client-ID der eigenen Microsoft-App-Registrierung (docs/52 §5).
 * Die ID steht bewusst weder im Quellcode noch in einer versionierten Datei; Reihenfolge:
 * <ol>
 * <li>Startoption {@code --client-id};</li>
 * <li>Umgebungsvariable {@value #ENV};</li>
 * <li>Datei {@code ~/.minecraft-merlin/auth.properties}, Schlüssel {@value #KEY} (außerhalb des Repositorys).</li>
 * </ol>
 * Eine Client-ID ist keine geheime Angabe (öffentlicher Client ohne Secret), gehört aber als persönlicher Wert nicht nach GitHub.
 * Ohne ID bleibt die Microsoft-Anmeldung mit {@code NO_CLIENT_ID} gesperrt.
 */
public final class ClientIdConfig {
	public static final String ENV = "MERLIN_AUTH_CLIENT_ID";
	public static final String KEY = "client-id";

	private ClientIdConfig() {
	}

	public static Path defaultFile() {
		return Path.of(System.getProperty("user.home"), ".minecraft-merlin", "auth.properties");
	}

	public static String resolve(final String cliValue) {
		return resolve(cliValue, System.getenv(), defaultFile());
	}

	/** Testbar: Umgebung und Datei werden übergeben. Liefert {@code null}, wenn keine Quelle einen Wert enthält. */
	public static String resolve(final String cliValue, final Map<String, String> env, final Path file) {
		if (cliValue != null && !cliValue.isBlank()) {
			return cliValue.trim();
		}
		String fromEnv = env.get(ENV);
		if (fromEnv != null && !fromEnv.isBlank()) {
			return fromEnv.trim();
		}
		if (file != null && Files.isRegularFile(file)) {
			Properties p = new Properties();
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(r);
			} catch (IOException e) {
				return null;
			}
			String v = p.getProperty(KEY);
			if (v != null && !v.isBlank()) {
				return v.trim();
			}
		}
		return null;
	}
}
