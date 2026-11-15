package dev.mcresearch.protocolclient.net;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Paketprotokoll im selben Zeilenformat wie die DEBUG-Ausgabe von Vanilla-PacketDecoder/PacketEncoder,
 * damit {@code analysis/tools/analyze-runtime.js} die Läufe direkt vergleichen kann:
 * <pre>[HH:mm:ss.SSS] [thread] OUT: [state:direction/name] class -> N bytes</pre>
 */
public final class PacketLog implements AutoCloseable {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private final BufferedWriter writer;

	private PacketLog(final BufferedWriter writer) {
		this.writer = writer;
	}

	public static PacketLog open(final Path file) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		return new PacketLog(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
	}

	public static PacketLog disabled() {
		return new PacketLog(null);
	}

	public synchronized void packet(final boolean outgoing, final String state, final String direction, final String name, final String type, final int bytes) {
		write((outgoing ? "OUT: [" : " IN: [") + state + ":" + direction + "/" + name + "] " + type + " -> " + bytes + " bytes");
	}

	public synchronized void marker(final String text) {
		write("[CLIENT] " + text);
	}

	private void write(final String line) {
		if (this.writer == null) {
			return;
		}
		try {
			this.writer.write("[" + LocalTime.now().format(TIME) + "] [" + Thread.currentThread().getName() + "] " + line);
			this.writer.newLine();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public synchronized void close() {
		if (this.writer == null) {
			return;
		}
		try {
			this.writer.close();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
