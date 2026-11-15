package dev.mcresearch.protocolclient.view;

import dev.mcresearch.protocolclient.world.BlockRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Tönungsquellen je Blockzustand und {@code tintindex} (docs/47 §4.3) aus {@code render/tints-777.txt}, erzeugt vom
 * Analysewerkzeug {@code TintExtractor} aus {@code BlockColors#createDefault} des unveränderten Spiel-JARs. Die Farbe selbst
 * kommt zur Laufzeit aus den Biomdaten (Gras, Laub, trockenes Laub, Wasser) oder ist eine in der Vorlage festgelegte Konstante
 * (z. B. Fichtenlaub, Seerose in der Welt, Redstone je Stärke).
 */
public final class TintTable {
	public static final String RESOURCE = "/render/tints-777.txt";

	public enum Kind {
		NONE, GRASS, GRASS_BELOW, FOLIAGE, DRY_FOLIAGE, WATER, CONSTANT
	}

	/** Eine Quelle: Art und (bei {@link Kind#CONSTANT}) RGB. */
	public record Source(Kind kind, int rgb) {
		public static final Source NONE = new Source(Kind.NONE, 0xFFFFFF);
	}

	private static volatile TintTable instance;
	private final Source[][] sources;

	private TintTable(final int states) {
		this.sources = new Source[states][];
	}

	public static TintTable get() {
		TintTable t = instance;
		if (t == null) {
			synchronized (TintTable.class) {
				t = instance;
				if (t == null) {
					t = load(BlockRegistry.get().size());
					instance = t;
				}
			}
		}
		return t;
	}

	static TintTable load(final int states) {
		TintTable t = new TintTable(states);
		try (InputStream raw = TintTable.class.getResourceAsStream(RESOURCE)) {
			if (raw == null) {
				throw new IllegalStateException("missing resource " + RESOURCE);
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8));
			for (String line; (line = r.readLine()) != null; ) {
				if (line.startsWith("#") || line.isBlank()) {
					continue;
				}
				String[] f = line.split("\t");
				int id = Integer.parseInt(f[1]);
				int layer = Integer.parseInt(f[2]);
				if (id < 0 || id >= states) {
					continue;
				}
				Kind kind = switch (f[3]) {
					case "grass" -> Kind.GRASS;
					case "grass_below" -> Kind.GRASS_BELOW;
					case "foliage" -> Kind.FOLIAGE;
					case "dry_foliage" -> Kind.DRY_FOLIAGE;
					case "water" -> Kind.WATER;
					case "constant" -> Kind.CONSTANT;
					default -> Kind.NONE;
				};
				int rgb = kind == Kind.CONSTANT ? Integer.parseInt(f[4], 16) : 0xFFFFFF;
				Source[] cur = t.sources[id];
				Source[] next = new Source[Math.max(cur == null ? 0 : cur.length, layer + 1)];
				if (cur != null) {
					System.arraycopy(cur, 0, next, 0, cur.length);
				}
				next[layer] = new Source(kind, rgb);
				t.sources[id] = next;
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return t;
	}

	/** Quelle für {@code tintindex}; ohne Eintrag (Index über der Anzahl der Quellen) → {@link Source#NONE} (weiß). */
	public Source source(final int stateId, final int tintIndex) {
		if (stateId < 0 || stateId >= this.sources.length || tintIndex < 0) {
			return Source.NONE;
		}
		Source[] s = this.sources[stateId];
		return s == null || tintIndex >= s.length || s[tintIndex] == null ? Source.NONE : s[tintIndex];
	}
}
