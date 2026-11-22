package dev.mcresearch.protocolclient.settings;

import dev.mcresearch.protocolclient.input.GameAction;
import dev.mcresearch.protocolclient.input.InputCode;
import dev.mcresearch.protocolclient.input.KeyBindings;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Zentrale Einstellungsverwaltung (docs/60 §3): lädt beim Start, prüft jeden Wert, speichert atomar (temporäre Datei +
 * Umbenennen). Fehler beim Lesen oder Schreiben führen nie zum Abbruch: ungültige Werte → Standard mit Hinweis, beschädigte
 * Datei → wird als {@code .broken-<Zeit>} aufbewahrt und durch Standardwerte ersetzt, Speichern fehlgeschlagen → Meldung,
 * Werte bleiben im Speicher.
 */
public final class SettingsManager {
	/** Herkunft der geladenen Werte. */
	public enum LoadStatus {
		/** Keine Datei – Standardwerte (Datei entsteht beim ersten Speichern). */
		MISSING,
		/** Datei gelesen (ggf. mit einzelnen Korrekturen, siehe Hinweise). */
		LOADED,
		/** Datei unlesbar/beschädigt – gesichert, Standardwerte verwendet. */
		RECOVERED,
		/** Nur im Speicher (Test-/Regressionsmodus, keine Datei). */
		MEMORY
	}

	public record SaveResult(boolean ok, String message) {
	}

	private final Path file;
	private final Map<Setting<?>, Object> values = new HashMap<>();
	private final KeyBindings bindings = new KeyBindings();
	/** Unbekannte Schlüssel (neuere Version, Erweiterungen) bleiben beim Speichern erhalten. */
	private final Map<String, String> unknown = new TreeMap<>();
	private final List<String> notes = new ArrayList<>();
	private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
	private LoadStatus status = LoadStatus.MEMORY;
	private boolean dirty;
	private String lastSaveMessage = "";

	private SettingsManager(final Path file) {
		this.file = file;
		for (Setting<?> s : Settings.ALL) {
			this.values.put(s, s.defaultValue());
		}
	}

	/** Standardpfad {@code ~/.minecraft-merlin/settings.properties} (neben der Anmeldekonfiguration aus M13). */
	public static Path defaultPath() {
		return Path.of(System.getProperty("user.home"), ".minecraft-merlin", "settings.properties");
	}

	/** Nur im Speicher, ohne Datei (Autotest/Regression: Nutzereinstellungen dürfen Vergleichsläufe nicht verändern). */
	public static SettingsManager inMemory() {
		return new SettingsManager(null);
	}

	/** Lädt {@code file}; wirft nie. */
	public static SettingsManager load(final Path file) {
		SettingsManager m = new SettingsManager(file);
		m.loadFile();
		return m;
	}

	public Path file() {
		return this.file;
	}

	public LoadStatus status() {
		return this.status;
	}

	/** Hinweise des letzten Ladens (korrigierte Werte, Migration, Wiederherstellung). */
	public List<String> notes() {
		return List.copyOf(this.notes);
	}

	public String lastSaveMessage() {
		return this.lastSaveMessage;
	}

	public boolean dirty() {
		return this.dirty;
	}

	public KeyBindings bindings() {
		return this.bindings;
	}

	public void addListener(final Consumer<String> l) {
		this.listeners.add(l);
	}

	public void removeListener(final Consumer<String> l) {
		this.listeners.remove(l);
	}

	public <T> T get(final Setting<T> s) {
		return s.cast(this.values.get(s));
	}

	/** Setzt einen geprüften Wert; ungültig → {@link IllegalArgumentException}, der alte Wert bleibt. */
	public <T> void set(final Setting<T> s, final T value) {
		T v = s.validate(value);
		if (!v.equals(this.values.get(s))) {
			this.values.put(s, v);
			this.dirty = true;
			fire(s.key());
		}
	}

	public KeyBindings.Change bind(final GameAction a, final InputCode code) {
		KeyBindings.Change c = this.bindings.set(a, code);
		if (c == KeyBindings.Change.OK) {
			this.dirty = true;
			fire("controls.key." + a.id());
		}
		return c;
	}

	public void resetBinding(final GameAction a) {
		this.bindings.reset(a);
		this.dirty = true;
		fire("controls.key." + a.id());
	}

	/** Alles auf Standard (oder nur eine Kategorie; Tastenbelegung gehört zu {@link Setting.Category#CONTROLS}). */
	public void resetToDefaults(final Setting.Category only) {
		for (Setting<?> s : Settings.ALL) {
			if (only == null || s.category() == only) {
				if (!s.defaultValue().equals(this.values.get(s))) {
					this.values.put(s, s.defaultValue());
					fire(s.key());
				}
			}
		}
		if (only == null || only == Setting.Category.CONTROLS) {
			this.bindings.resetAll();
			fire("controls.key.*");
		}
		this.dirty = true;
	}

	private void fire(final String key) {
		for (Consumer<String> l : this.listeners) {
			l.accept(key);
		}
	}

	// ---------------------------------------------------------------- Laden

	private void loadFile() {
		if (this.file == null) {
			this.status = LoadStatus.MEMORY;
			return;
		}
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(this.file);
		} catch (NoSuchFileException e) {
			this.status = LoadStatus.MISSING;
			return;
		} catch (IOException | SecurityException e) {
			this.status = LoadStatus.RECOVERED;
			this.notes.add("Einstellungsdatei nicht lesbar (" + e.getClass().getSimpleName() + ") – Standardwerte verwendet.");
			return;
		}
		Map<String, String> raw;
		try {
			raw = parseProperties(bytes);
		} catch (IOException | IllegalArgumentException e) {
			this.status = LoadStatus.RECOVERED;
			Path backup = backupBroken();
			this.notes.add("Einstellungsdatei beschädigt (" + e.getMessage() + ") – Standardwerte verwendet"
				+ (backup != null ? ", alte Datei gesichert als " + backup.getFileName() : "") + ".");
			this.dirty = true;
			return;
		}
		int version;
		String vs = raw.remove("settings.version");
		try {
			version = vs == null ? 1 : Integer.parseInt(vs.strip());
		} catch (NumberFormatException e) {
			version = 1;
			this.notes.add("Formatversion „" + vs + "“ unlesbar – als Format 1 behandelt.");
		}
		if (version > Settings.FORMAT_VERSION) {
			this.notes.add("Datei stammt aus einer neueren Version (Format " + version + "); bekannte Werte übernommen.");
		} else if (version < Settings.FORMAT_VERSION) {
			raw = SettingsMigration.migrate(raw, version, this.notes);
			raw.remove("settings.version");
			this.dirty = true;
		}
		apply(raw);
		this.status = LoadStatus.LOADED;
	}

	/** Strenges UTF-8 und Plausibilität: Steuerzeichen oder kein einziger gültiger Eintrag gelten als beschädigt. */
	static Map<String, String> parseProperties(final byte[] bytes) throws IOException {
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			throw new IOException("kein gültiges UTF-8");
		}
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != '\f') {
				throw new IOException("Steuerzeichen an Position " + i);
			}
		}
		Properties p = new Properties();
		p.load(new StringReader(text)); // IllegalArgumentException bei kaputten \\u-Sequenzen
		Map<String, String> out = new LinkedHashMap<>();
		for (String k : p.stringPropertyNames()) {
			out.put(k, p.getProperty(k));
		}
		boolean blank = text.strip().isEmpty() || text.lines().allMatch(l -> l.isBlank() || l.strip().startsWith("#") || l.strip().startsWith("!"));
		if (!blank && out.keySet().stream().noneMatch(k -> k.equals("settings.version") || k.startsWith("controls.key.") || Settings.byKey(k) != null
			|| SettingsMigration.isLegacyKey(k) || k.matches("[a-z]+(\\.[A-Za-z0-9_]+)+"))) {
			throw new IOException("keine gültigen Einträge");
		}
		return out;
	}

	private void apply(final Map<String, String> raw) {
		for (Map.Entry<String, String> e : raw.entrySet()) {
			String k = e.getKey();
			if (k.startsWith("controls.key.")) {
				applyBinding(k.substring("controls.key.".length()), e.getValue());
				continue;
			}
			Setting<?> s = Settings.byKey(k);
			if (s == null) {
				this.unknown.put(k, e.getValue());
				continue;
			}
			try {
				this.values.put(s, s.parse(e.getValue()));
			} catch (RuntimeException ex) {
				this.values.put(s, s.defaultValue());
				this.notes.add(s.label() + ": ungültiger Wert „" + e.getValue() + "“ – Standard " + s.formatObject(s.defaultValue()) + " verwendet.");
				this.dirty = true;
			}
		}
	}

	private void applyBinding(final String id, final String value) {
		GameAction action = null;
		for (GameAction a : GameAction.values()) {
			if (a.id().equals(id)) {
				action = a;
			}
		}
		if (action == null) {
			this.unknown.put("controls.key." + id, value);
			return;
		}
		try {
			KeyBindings.Change c = this.bindings.set(action, InputCode.parse(value));
			if (c != KeyBindings.Change.OK) {
				this.bindings.reset(action);
				this.notes.add("Taste „" + action.label + "“: " + value + " nicht erlaubt (" + c + ") – Standard verwendet.");
				this.dirty = true;
			}
		} catch (RuntimeException ex) {
			this.bindings.reset(action);
			this.notes.add("Taste „" + action.label + "“: ungültiger Wert „" + value + "“ – Standard verwendet.");
			this.dirty = true;
		}
	}

	private Path backupBroken() {
		try {
			Path b = this.file.resolveSibling(this.file.getFileName() + ".broken-"
				+ LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
			Files.move(this.file, b, StandardCopyOption.REPLACE_EXISTING);
			return b;
		} catch (IOException | SecurityException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- Speichern

	/** Dateiinhalt (sortiert, mit Kopfzeile) – auch für Tests. */
	public String serialize() {
		StringBuilder sb = new StringBuilder();
		sb.append("# Minecraft Merlin Client – Einstellungen (docs/60). Wird vom Client geschrieben; ungültige Werte werden beim Start ersetzt.\n");
		sb.append("settings.version=").append(Settings.FORMAT_VERSION).append('\n');
		Map<String, String> all = new TreeMap<>(this.unknown);
		for (Setting<?> s : Settings.ALL) {
			all.put(s.key(), s.formatObject(this.values.get(s)));
		}
		all.putAll(this.bindings.toProperties());
		for (Map.Entry<String, String> e : all.entrySet()) {
			sb.append(escape(e.getKey(), true)).append('=').append(escape(e.getValue(), false)).append('\n');
		}
		return sb.toString();
	}

	private static String escape(final String s, final boolean key) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '=', ':', '#', '!' -> {
					if (key || i == 0) {
						sb.append('\\');
					}
					sb.append(c);
				}
				case ' ' -> sb.append(key || i == 0 ? "\\ " : " ");
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}

	/** Speichert, wenn sich etwas geändert hat. */
	public SaveResult saveIfDirty() {
		return this.dirty ? save() : new SaveResult(true, "unverändert");
	}

	/** Speichert atomar; liefert eine verständliche Meldung statt einer Ausnahme. */
	public SaveResult save() {
		if (this.file == null) {
			this.dirty = false;
			return new SaveResult(true, "nur im Speicher");
		}
		Path tmp = null;
		try {
			Path dir = this.file.toAbsolutePath().getParent();
			Files.createDirectories(dir);
			if (Files.exists(this.file) && !Files.isWritable(this.file)) {
				throw new AccessDeniedException(this.file.toString(), null, "schreibgeschützt");
			}
			tmp = Files.createTempFile(dir, "settings", ".tmp");
			Files.writeString(tmp, serialize(), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
			}
			tmp = null;
			this.dirty = false;
			this.lastSaveMessage = "Gespeichert in " + this.file;
			return new SaveResult(true, this.lastSaveMessage);
		} catch (AccessDeniedException e) {
			this.lastSaveMessage = "Einstellungen nicht gespeichert: Datei ist schreibgeschützt oder der Zugriff wurde verweigert.";
		} catch (IOException | SecurityException e) {
			this.lastSaveMessage = "Einstellungen nicht gespeichert: " + e.getClass().getSimpleName()
				+ (e.getMessage() == null ? "" : " (" + e.getMessage() + ")");
		} finally {
			if (tmp != null) {
				try {
					Files.deleteIfExists(tmp);
				} catch (IOException ignored) {
					// temporäre Datei bleibt liegen; harmlos
				}
			}
		}
		return new SaveResult(false, this.lastSaveMessage);
	}

	/** Alle Werte als Text (Autotest-Bericht, Diagnose). */
	public Map<String, String> snapshot() {
		Map<String, String> m = new TreeMap<>();
		for (Setting<?> s : Settings.ALL) {
			m.put(s.key(), s.formatObject(this.values.get(s)));
		}
		m.putAll(this.bindings.toProperties());
		return m;
	}
}
