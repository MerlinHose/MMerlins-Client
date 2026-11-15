package dev.mcresearch.protocolclient.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Paket-ID-Tabelle für Protokoll 777, geladen aus {@code /protocol/packets-777.txt}
 * (erzeugt aus dem offiziellen Datengenerator-Report, siehe docs/12).
 */
public final class PacketRegistry {
	public static final int PROTOCOL_VERSION = 777;
	private static final String RESOURCE = "/protocol/packets-777.txt";
	private static final PacketRegistry INSTANCE = load();

	private final Map<ProtocolState, Map<PacketDirection, Map<Integer, String>>> byId = new EnumMap<>(ProtocolState.class);
	private final Map<ProtocolState, Map<PacketDirection, Map<String, Integer>>> byName = new EnumMap<>(ProtocolState.class);
	private int size;

	private PacketRegistry() {
	}

	public static PacketRegistry get() {
		return INSTANCE;
	}

	private static PacketRegistry load() {
		try (InputStream in = PacketRegistry.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Missing resource " + RESOURCE);
			}
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static PacketRegistry parse(final String text) {
		PacketRegistry registry = new PacketRegistry();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))) {
			String line;
			int lineNo = 0;
			while ((line = reader.readLine()) != null) {
				lineNo++;
				line = line.strip();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split(" ");
				if (parts.length != 4) {
					throw new IllegalStateException("Invalid packet table line " + lineNo + ": " + line);
				}
				registry.add(ProtocolState.byId(parts[0]), PacketDirection.byId(parts[1]), Integer.parseInt(parts[2]), parts[3]);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return registry;
	}

	private void add(final ProtocolState state, final PacketDirection direction, final int id, final String name) {
		Map<Integer, String> ids = this.byId.computeIfAbsent(state, s -> new EnumMap<>(PacketDirection.class)).computeIfAbsent(direction, d -> new HashMap<>());
		Map<String, Integer> names = this.byName.computeIfAbsent(state, s -> new EnumMap<>(PacketDirection.class)).computeIfAbsent(direction, d -> new HashMap<>());
		if (ids.putIfAbsent(id, name) != null || names.putIfAbsent(name, id) != null) {
			throw new IllegalStateException("Duplicate packet " + state + " " + direction + " " + id + " " + name);
		}
		this.size++;
	}

	/** Name zur ID oder {@code null}, wenn die ID in diesem Zustand/dieser Richtung nicht existiert. */
	public String nameOf(final ProtocolState state, final PacketDirection direction, final int id) {
		return this.byId.getOrDefault(state, Map.of()).getOrDefault(direction, Map.of()).get(id);
	}

	/** ID zum Namen; wirft, wenn das Paket in diesem Zustand nicht existiert (Programmierfehler). */
	public int idOf(final ProtocolState state, final PacketDirection direction, final String name) {
		Integer id = this.byName.getOrDefault(state, Map.of()).getOrDefault(direction, Map.of()).get(name);
		return Objects.requireNonNull(id, () -> "Unknown packet " + state.id() + "/" + direction.id() + "/" + name);
	}

	public int size() {
		return this.size;
	}

	public int count(final ProtocolState state, final PacketDirection direction) {
		return this.byId.getOrDefault(state, Map.of()).getOrDefault(direction, Map.of()).size();
	}
}
