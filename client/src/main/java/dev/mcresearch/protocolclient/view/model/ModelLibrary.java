package dev.mcresearch.protocolclient.view.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * M14 (docs/55 §2): hierarchische Entity-Modelle aus der Faktentabelle {@code render/entitymodels-777.txt.gz}
 * ({@code analysis/tools/EntityModelExtractor}). Ein Modell-Layer (z. B. {@code minecraft:wolf_baby#main}) besteht aus Teilen mit
 * Eltern-Kind-Beziehung, Drehpunkt, Grunddrehung, Skalierung und Quadern (Ursprung, Größe, Vergrößerung je Achse, Spiegelung,
 * Texturversatz und -skalierung, sichtbare Seiten).
 *
 * <p>Modellraum wie im Spiel: Einheit 1/16 Block, y nach unten. Unveränderlich und threadsicher; ohne LWJGL.
 */
public final class ModelLibrary {
	/** Seitenbits in Richtungsreihenfolge (unten, oben, Nord, Süd, West, Ost) – im Modellraum (y nach unten). */
	public static final int FACE_DOWN = 1, FACE_UP = 2, FACE_NORTH = 4, FACE_SOUTH = 8, FACE_WEST = 16, FACE_EAST = 32, ALL_FACES = 63;

	/** Ein Quader in Pixeln relativ zum Drehpunkt seines Teils. */
	public record Cube(float u, float v, float x, float y, float z, float w, float h, float d, float growX, float growY, float growZ, boolean mirror,
		float texScaleU, float texScaleV, int faces) {
	}

	/**
	 * Ein Teil. {@code index} = Position in {@link Model#parts()} (Eltern stehen vor Kindern), {@code parent} = Index des Elternteils
	 * (-1 für die Wurzel). Die Grundpose ({@code x … zScale}) gilt relativ zum Elternteil.
	 */
	public record Part(String name, String path, int index, int parent, float x, float y, float z, float xRot, float yRot, float zRot, float xScale,
		float yScale, float zScale, List<Cube> cubes) {
	}

	/** Ein Modell-Layer: Texturgröße und Teile (Tiefensuche ab "root"). */
	public static final class Model {
		private final String layer;
		private final int texWidth;
		private final int texHeight;
		private final List<Part> parts;
		private final Map<String, Integer> byName;

		Model(final String layer, final int texWidth, final int texHeight, final List<Part> parts) {
			this.layer = layer;
			this.texWidth = texWidth;
			this.texHeight = texHeight;
			this.parts = List.copyOf(parts);
			Map<String, Integer> m = new HashMap<>();
			for (Part p : parts) {
				m.putIfAbsent(p.name(), p.index()); // gleichnamige Teile: erster Treffer der Tiefensuche (wie createPartLookup)
			}
			this.byName = Map.copyOf(m);
		}

		public String layer() {
			return this.layer;
		}

		public int texWidth() {
			return this.texWidth;
		}

		public int texHeight() {
			return this.texHeight;
		}

		public List<Part> parts() {
			return this.parts;
		}

		/** Index des Teils mit diesem Namen oder -1. */
		public int part(final String name) {
			Integer i = this.byName.get(name);
			return i == null ? -1 : i;
		}

		public int cubeCount() {
			int n = 0;
			for (Part p : this.parts) {
				n += p.cubes().size();
			}
			return n;
		}

		@Override
		public String toString() {
			return this.layer + "[" + this.parts.size() + " parts, " + cubeCount() + " cubes, tex " + this.texWidth + "x" + this.texHeight + "]";
		}
	}

	private static volatile ModelLibrary instance;
	private final Map<String, Model> models;

	private ModelLibrary(final Map<String, Model> models) {
		this.models = Map.copyOf(models);
	}

	/** Mitgelieferte Tabelle (einmal geladen). */
	public static ModelLibrary get() {
		ModelLibrary l = instance;
		if (l == null) {
			synchronized (ModelLibrary.class) {
				l = instance;
				if (l == null) {
					try (InputStream in = ModelLibrary.class.getResourceAsStream("/render/entitymodels-777.txt.gz")) {
						if (in == null) {
							throw new IllegalStateException("render/entitymodels-777.txt.gz missing");
						}
						l = parse(new GZIPInputStream(in));
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
					instance = l;
				}
			}
		}
		return l;
	}

	/** Liefert den Layer oder null (z. B. {@code "minecraft:pig#main"}). */
	public Model model(final String layer) {
		return this.models.get(layer);
	}

	public int size() {
		return this.models.size();
	}

	public java.util.Set<String> layers() {
		return this.models.keySet();
	}

	public static ModelLibrary parse(final InputStream in) throws IOException {
		Map<String, int[]> tex = new LinkedHashMap<>();
		Map<String, List<String[]>> poses = new LinkedHashMap<>();
		Map<String, Map<String, List<Cube>>> cubes = new HashMap<>();
		BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		String line;
		while ((line = r.readLine()) != null) {
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] f = line.split("\t");
			switch (f[0]) {
				case "L" -> tex.put(f[1], new int[]{Integer.parseInt(f[2]), Integer.parseInt(f[3])});
				case "P" -> poses.computeIfAbsent(f[1], k -> new ArrayList<>()).add(f);
				case "C" -> cubes.computeIfAbsent(f[1], k -> new HashMap<>()).computeIfAbsent(f[2], k -> new ArrayList<>()).add(new Cube(fl(f[3]), fl(f[4]),
					fl(f[5]), fl(f[6]), fl(f[7]), fl(f[8]), fl(f[9]), fl(f[10]), fl(f[11]), fl(f[12]), fl(f[13]), f[14].equals("1"), fl(f[15]), fl(f[16]),
					Integer.parseInt(f[17])));
				default -> throw new IOException("bad line: " + line);
			}
		}
		Map<String, Model> models = new HashMap<>();
		for (Map.Entry<String, int[]> e : tex.entrySet()) {
			String layer = e.getKey();
			List<Part> parts = new ArrayList<>();
			Map<String, Integer> idx = new HashMap<>();
			Map<String, List<Cube>> lc = cubes.getOrDefault(layer, Map.of());
			for (String[] p : poses.getOrDefault(layer, List.of())) {
				String path = p[2];
				int slash = path.lastIndexOf('/');
				String name = slash < 0 ? path : path.substring(slash + 1);
				int parent = slash < 0 ? -1 : idx.getOrDefault(path.substring(0, slash), -1);
				if (slash >= 0 && parent < 0) {
					throw new IOException("parent missing for " + layer + " " + path);
				}
				int index = parts.size();
				idx.put(path, index);
				parts.add(new Part(name, path, index, parent, fl(p[3]), fl(p[4]), fl(p[5]), fl(p[6]), fl(p[7]), fl(p[8]), fl(p[9]), fl(p[10]), fl(p[11]),
					List.copyOf(lc.getOrDefault(path, List.of()))));
			}
			models.put(layer, new Model(layer, e.getValue()[0], e.getValue()[1], parts));
		}
		return new ModelLibrary(models);
	}

	private static float fl(final String s) {
		return Float.parseFloat(s);
	}
}
