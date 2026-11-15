package dev.mcresearch.protocolclient.protocol.packet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Netzwerk-IDs der Partikeltypen (docs/48 §7) aus {@code render/particles-777.txt} (Analysewerkzeug
 * {@code ParticleTypeExtractor}, unverändertes Server-JAR): Name und ob das Paket Zusatzdaten enthält.
 */
public final class ParticleTypes {
	/** Typen mit Blockzustand als Zusatzdaten ({@code BlockParticleOption}). */
	public static final Set<String> BLOCK_DATA = Set.of("minecraft:block", "minecraft:block_marker", "minecraft:falling_dust", "minecraft:dust_pillar",
		"minecraft:block_crumble");

	public record Type(int id, String name, boolean simple) {
	}

	private static volatile ParticleTypes instance;
	private final Map<Integer, Type> byId = new HashMap<>();

	private ParticleTypes() {
	}

	public static ParticleTypes get() {
		ParticleTypes p = instance;
		if (p == null) {
			synchronized (ParticleTypes.class) {
				p = instance;
				if (p == null) {
					p = load();
					instance = p;
				}
			}
		}
		return p;
	}

	private static ParticleTypes load() {
		ParticleTypes p = new ParticleTypes();
		try (InputStream in = ParticleTypes.class.getResourceAsStream("/render/particles-777.txt")) {
			if (in == null) {
				throw new IllegalStateException("missing /render/particles-777.txt");
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line; (line = r.readLine()) != null; ) {
				if (line.startsWith("#") || line.isBlank()) {
					continue;
				}
				String[] t = line.split("\t");
				int id = Integer.parseInt(t[1]);
				p.byId.put(id, new Type(id, t[2], t[3].equals("simple")));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return p;
	}

	public Type byId(final int id) {
		return this.byId.get(id);
	}

	public int size() {
		return this.byId.size();
	}
}
