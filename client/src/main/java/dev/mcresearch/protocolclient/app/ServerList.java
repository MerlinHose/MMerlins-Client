package dev.mcresearch.protocolclient.app;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Gespeicherte Mehrspieler-Einträge (docs/61 §6), Datei {@code ~/.minecraft-merlin/servers.properties}. Unlesbare Einträge
 * werden übersprungen und gemeldet; die Liste ist nie die Ursache für einen Startabbruch.
 */
public final class ServerList {
	public static final int MAX_ENTRIES = 64;
	private final Path file;
	private final List<ServerEntry> entries = new ArrayList<>();
	private final List<String> notes = new ArrayList<>();

	private ServerList(final Path file) {
		this.file = file;
	}

	public static Path defaultPath() {
		return Path.of(System.getProperty("user.home"), ".minecraft-merlin", "servers.properties");
	}

	public static ServerList inMemory() {
		return new ServerList(null);
	}

	public static ServerList load(final Path file) {
		ServerList l = new ServerList(file);
		if (file == null) {
			return l;
		}
		try {
			Properties p = new Properties();
			p.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
			for (int i = 0; i < MAX_ENTRIES; i++) {
				String name = p.getProperty("server." + i + ".name");
				if (name == null) {
					continue;
				}
				try {
					int port = Integer.parseInt(p.getProperty("server." + i + ".port", "25565").strip());
					int proto = Integer.parseInt(p.getProperty("server." + i + ".lastProtocol", "0").strip());
					l.entries.add(new ServerEntry(name, p.getProperty("server." + i + ".host", ""), port,
						p.getProperty("server." + i + ".lastVersion", ""), proto));
				} catch (RuntimeException e) {
					l.notes.add("Servereintrag " + i + " ungültig und übersprungen (" + e.getMessage() + ").");
				}
			}
		} catch (NoSuchFileException e) {
			// noch keine Liste
		} catch (IOException | RuntimeException e) {
			l.notes.add("Serverliste nicht lesbar (" + e.getClass().getSimpleName() + ") – leere Liste.");
		}
		return l;
	}

	public List<ServerEntry> entries() {
		return List.copyOf(this.entries);
	}

	public List<String> notes() {
		return List.copyOf(this.notes);
	}

	public void add(final ServerEntry e) {
		if (this.entries.size() >= MAX_ENTRIES) {
			throw new IllegalStateException("Höchstens " + MAX_ENTRIES + " Einträge");
		}
		this.entries.add(e);
	}

	public void set(final int index, final ServerEntry e) {
		this.entries.set(index, e);
	}

	public void remove(final int index) {
		this.entries.remove(index);
	}

	public int size() {
		return this.entries.size();
	}

	/** Version eines Eintrags nach einem Statusping merken (unabhängig von der Client-Version). */
	public void rememberVersion(final int index, final String versionName, final int protocol) {
		this.entries.set(index, this.entries.get(index).withVersion(versionName, protocol));
	}

	public String serialize() {
		StringBuilder sb = new StringBuilder("# Minecraft Merlin Client – Serverliste (docs/61)\n");
		for (int i = 0; i < this.entries.size(); i++) {
			ServerEntry e = this.entries.get(i);
			sb.append("server.").append(i).append(".name=").append(esc(e.name())).append('\n');
			sb.append("server.").append(i).append(".host=").append(esc(e.host())).append('\n');
			sb.append("server.").append(i).append(".port=").append(e.port()).append('\n');
			sb.append("server.").append(i).append(".lastVersion=").append(esc(e.lastVersionName())).append('\n');
			sb.append("server.").append(i).append(".lastProtocol=").append(e.lastProtocol()).append('\n');
		}
		return sb.toString();
	}

	private static String esc(final String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\') {
				sb.append("\\\\");
			} else if (c < 0x20) {
				sb.append(' ');
			} else if (i == 0 && (c == ' ' || c == '#' || c == '!')) {
				sb.append('\\').append(c);
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/** Speichert atomar; liefert eine Fehlermeldung oder null. */
	public String save() {
		if (this.file == null) {
			return null;
		}
		try {
			Files.createDirectories(this.file.toAbsolutePath().getParent());
			Path tmp = Files.createTempFile(this.file.toAbsolutePath().getParent(), "servers", ".tmp");
			try {
				Files.writeString(tmp, serialize(), StandardCharsets.UTF_8);
				try {
					Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				Files.deleteIfExists(tmp);
			}
			return null;
		} catch (IOException | SecurityException e) {
			return "Serverliste nicht gespeichert: " + e.getClass().getSimpleName();
		}
	}
}
