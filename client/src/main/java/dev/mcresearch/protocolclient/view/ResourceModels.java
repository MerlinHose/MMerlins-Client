package dev.mcresearch.protocolclient.view;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.json.MiniJson;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.BlockState;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Blockmodelle aus den <b>lokal vorhandenen</b> Minecraft-26.3-Ressourcen (docs/25 §8–§9). Liest zur Laufzeit
 * {@code assets/minecraft/blockstates}, {@code models} und {@code textures} aus dem angegebenen JAR; nichts davon wird in
 * Build-Artefakte kopiert. Eigene Implementierung des dokumentierten JSON-Formats, kein Minecraft-Code.
 *
 * <p>Unterstützt: {@code variants} (Teilmengen-Schlüssel, Listen → erster Eintrag), {@code multipart} ({@code when} mit
 * {@code |}, {@code OR}, {@code AND}), Elternketten, Texturvariablen, {@code elements} mit {@code from/to}, Flächen-UV,
 * Flächenrotation, {@code cullface}, {@code tintindex}, {@code shade}, Elementrotation (inkl. {@code rescale}),
 * Variantenrotation {@code x/y} mit {@code uvlock}. Wasser/Lava (ohne Elemente) erhalten ein eigenes Flüssigkeitsmodell.
 * Fehlende oder fehlerhafte Modelle → Debug-Würfel (magenta/schwarz), kein Absturz.
 */
public final class ResourceModels implements BlockModelProvider {
	private static final String PREFIX = "assets/minecraft/";
	private static final int WATER_GROUP = 1;
	private static final int LAVA_GROUP = 2;

	private final RenderModel[] models;
	private final TextureAtlas atlas;
	private final FluidSprites waterSprites;
	private final FluidSprites lavaSprites;
	private final TextureAtlas.Sprite[] particles;
	private final String summary;

	/** M12: Fluidtexturen (still, fließend, Überlagerung an Glas/Laub – nur Wasser), wie {@code FluidStateModelSet}. */
	public record FluidSprites(TextureAtlas.Sprite still, TextureAtlas.Sprite flowing, TextureAtlas.Sprite overlay) {
	}

	private ResourceModels(final RenderModel[] models, final TextureAtlas atlas, final FluidSprites water, final FluidSprites lava,
		final TextureAtlas.Sprite[] particles, final String summary) {
		this.models = models;
		this.particles = particles;
		this.atlas = atlas;
		this.waterSprites = water;
		this.lavaSprites = lava;
		this.summary = summary;
	}

	/** M12: Partikelbild eines Zustands (Texturvariable {@code particle} des Modells) – Abbau- und Blockpartikel. */
	public TextureAtlas.Sprite particleSprite(final int stateId) {
		TextureAtlas.Sprite s = stateId >= 0 && stateId < this.particles.length ? this.particles[stateId] : null;
		return s == null ? this.atlas.debug() : s;
	}

	public FluidSprites fluidSprites(final boolean water) {
		return water ? this.waterSprites : this.lavaSprites;
	}

	/** {@code HalfTransparentBlock} oder {@code LeavesBlock} neben Wasser → Seitenfläche mit {@code water_overlay}. */
	public boolean overlayNeighbour(final int stateId) {
		BlockRenderFacts f = BlockRenderFacts.get();
		BlockRenderFacts.Skip s = f.skip(stateId);
		return s == BlockRenderFacts.Skip.LEAVES
			|| s == BlockRenderFacts.Skip.SAME_BLOCK && !BlockRegistry.get().state(stateId).name().equals("minecraft:powder_snow");
	}

	/** Lädt alle Modelle für alle Blockzustände. */
	public static ResourceModels load(final Path jar, final BlockRegistry registry) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Loader l = new Loader(zip, registry);
			return l.run();
		}
	}

	public TextureAtlas atlas() {
		return this.atlas;
	}

	public String summary() {
		return this.summary;
	}

	@Override
	public RenderModel model(final int stateId) {
		return stateId >= 0 && stateId < this.models.length ? this.models[stateId] : RenderModel.EMPTY;
	}


	@Override
	public boolean textured() {
		return true;
	}

	// =====================================================================================================================

	private static final class Loader {
		private final ZipFile zip;
		private final BlockRegistry registry;
		private final TextureAtlas atlas = new TextureAtlas();
		private final Map<String, Map<String, Object>> modelJson = new HashMap<>();
		private final Map<String, Baked> bakedCache = new HashMap<>();
		private final Map<String, TextureAtlas.Sprite> particleByState = new HashMap<>();
		private final Map<String, Object> blockstateJson = new HashMap<>();
		private int debugStates;
		private int failedModels;
		private final List<String> problems = new ArrayList<>();

		Loader(final ZipFile zip, final BlockRegistry registry) {
			this.zip = zip;
			this.registry = registry;
		}

		ResourceModels run() throws IOException {
			RenderModel[] out = new RenderModel[this.registry.size()];
			TextureAtlas.Sprite[] particles = new TextureAtlas.Sprite[this.registry.size()];
			Map<String, RenderModel> stateCache = new HashMap<>();
			for (int id = 0; id < out.length; id++) {
				BlockState s = this.registry.state(id);
				if (s.isAir()) {
					out[id] = RenderModel.EMPTY;
					continue;
				}
				String key = s.toString();
				RenderModel m = stateCache.get(key);
				if (m == null) {
					try {
						m = buildState(s);
					} catch (RuntimeException | IOException e) {
						if (this.problems.size() < 20) {
							this.problems.add(s + ": " + e.getMessage());
						}
						m = debugModel();
					}
					stateCache.put(key, m);
				}
				if (m.debug()) {
					this.debugStates++;
				}
				out[id] = m;
				particles[id] = this.particleByState.get(key);
			}
			FluidSprites water = new FluidSprites(sprite("block/water_still"), sprite("block/water_flow"), sprite("block/water_overlay"));
			FluidSprites lava = new FluidSprites(sprite("block/lava_still"), sprite("block/lava_flow"), null);
			// M14: Feuer brennender Entities braucht beide Sprites (ModelBakery.FIRE_0/FIRE_1 werden in Vanilla ausdrücklich angefordert;
			// das Standardmodell des Feuerblocks verwendet nur fire_0)
			sprite("block/fire_0");
			sprite("block/fire_1");
			String summary = String.format(Locale.ROOT, "%d states, %d textures (%d animated) in %dx%d atlas, %d models, %d debug states%s", out.length,
				this.atlas.spriteCount(), this.atlas.animatedSprites().size(), this.atlas.size(), this.atlas.size(), this.modelJson.size(), this.debugStates,
				this.problems.isEmpty() ? "" : ", e.g. " + this.problems.getFirst());
			for (int id = 0; id < particles.length; id++) {
				String n = this.registry.state(id).name();
				if (particles[id] == null && (n.equals("minecraft:water") || n.equals("minecraft:bubble_column"))) {
					particles[id] = water.still();
				} else if (particles[id] == null && n.equals("minecraft:lava")) {
					particles[id] = lava.still();
				}
			}
			return new ResourceModels(out, this.atlas, water, lava, particles, summary);
		}

		private BufferedImage image(final String file) throws IOException {
			ZipEntry e = this.zip.getEntry(PREFIX + file);
			if (e == null) {
				return null;
			}
			try (InputStream in = this.zip.getInputStream(e)) {
				return PngImages.read(in);
			}
		}

		private Object json(final String file) throws IOException, ProtocolException {
			ZipEntry e = this.zip.getEntry(PREFIX + file);
			if (e == null) {
				return null;
			}
			try (InputStream in = this.zip.getInputStream(e)) {
				return MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
		}

		// ----- Blockzustand → Modelle -----

		@SuppressWarnings("unchecked")
		private RenderModel buildState(final BlockState s) throws IOException, ProtocolException {
			String block = s.name().substring(s.name().indexOf(':') + 1);
			if (block.equals("water") || block.equals("lava") || block.equals("bubble_column")) {
				return fluidModel(block, s);
			}
			Object bs = this.blockstateJson.containsKey(block) ? this.blockstateJson.get(block) : json("blockstates/" + block + ".json");
			this.blockstateJson.put(block, bs);
			if (!(bs instanceof Map<?, ?> root)) {
				throw new IllegalStateException("no blockstate file");
			}
			List<RenderQuad> quads = new ArrayList<>();
			Boolean ao = null;
			boolean found = false;
			if (root.get("variants") instanceof Map<?, ?> variants) {
				for (Map.Entry<?, ?> v : variants.entrySet()) {
					if (matchesVariantKey((String) v.getKey(), s.properties())) {
						Baked bk = applyVariant(v.getValue());
						quads.addAll(bk.quads());
						ao = bk.ambientOcclusion();
						this.particleByState.putIfAbsent(s.toString(), bk.particle());
						found = true;
						break;
					}
				}
			} else if (root.get("multipart") instanceof List<?> parts) {
				found = true;
				for (Object p : parts) {
					Map<String, Object> part = (Map<String, Object>) p;
					Object when = part.get("when");
					if (when == null || matchesWhen((Map<String, Object>) when, s.properties())) {
						Baked bk = applyVariant(part.get("apply"));
						quads.addAll(bk.quads());
						if (bk.particle() != null) {
							this.particleByState.putIfAbsent(s.toString(), bk.particle());
						}
						if (ao == null) {
							ao = bk.ambientOcclusion(); // wie parts.getFirst().useAmbientOcclusion()
						}
					}
				}
			}
			if (!found) {
				throw new IllegalStateException("no variant matches");
			}
			return finish(quads, 0, ao == null || ao);
		}

		private static boolean matchesVariantKey(final String key, final Map<String, String> props) {
			if (key.isEmpty() || key.equals("normal")) {
				return true;
			}
			for (String kv : key.split(",")) {
				int eq = kv.indexOf('=');
				if (eq < 0 || !kv.substring(eq + 1).equals(props.get(kv.substring(0, eq)))) {
					return false;
				}
			}
			return true;
		}

		@SuppressWarnings("unchecked")
		private static boolean matchesWhen(final Map<String, Object> when, final Map<String, String> props) {
			if (when.get("OR") instanceof List<?> or) {
				for (Object o : or) {
					if (matchesWhen((Map<String, Object>) o, props)) {
						return true;
					}
				}
				return false;
			}
			if (when.get("AND") instanceof List<?> and) {
				for (Object o : and) {
					if (!matchesWhen((Map<String, Object>) o, props)) {
						return false;
					}
				}
				return true;
			}
			for (Map.Entry<String, Object> e : when.entrySet()) {
				String actual = props.get(e.getKey());
				boolean any = false;
				for (String alt : String.valueOf(e.getValue()).split("\\|")) {
					if (alt.equals(actual)) {
						any = true;
						break;
					}
				}
				if (!any) {
					return false;
				}
			}
			return true;
		}

		/** Gebackenes Modell: Flächen und {@code ambientocclusion} (erster Wert in der Elternkette, Vorgabe true). */
		private record Baked(List<RenderQuad> quads, boolean ambientOcclusion, TextureAtlas.Sprite particle) {
		}

		/** Variante (Objekt oder gewichtete Liste → erster Eintrag): Modell, Rotation x/y, uvlock. */
		@SuppressWarnings("unchecked")
		private Baked applyVariant(final Object variant) throws IOException, ProtocolException {
			Object v = variant instanceof List<?> list ? list.getFirst() : variant;
			Map<String, Object> m = (Map<String, Object>) v;
			String model = normalize((String) m.get("model"));
			int x = m.get("x") instanceof Number n ? n.intValue() : 0;
			int y = m.get("y") instanceof Number n ? n.intValue() : 0;
			boolean uvlock = Boolean.TRUE.equals(m.get("uvlock"));
			String key = model + "|" + x + "|" + y + "|" + uvlock;
			Baked cached = this.bakedCache.get(key);
			if (cached == null) {
				cached = bake(model, x, y, uvlock);
				this.bakedCache.put(key, cached);
			}
			return cached;
		}

		private static String normalize(final String id) {
			String s = id.startsWith("minecraft:") ? id.substring(10) : id;
			return s.contains("/") ? s : "block/" + s;
		}

		// ----- Modell-JSON mit Elternkette -----

		@SuppressWarnings("unchecked")
		private Map<String, Object> model(final String id) throws IOException, ProtocolException {
			Map<String, Object> m = this.modelJson.get(id);
			if (m == null) {
				Object o = json("models/" + id + ".json");
				if (!(o instanceof Map<?, ?>)) {
					throw new IllegalStateException("missing model " + id);
				}
				m = (Map<String, Object>) o;
				this.modelJson.put(id, m);
			}
			return m;
		}

		@SuppressWarnings("unchecked")
		private Baked bake(final String modelId, final int rotX, final int rotY, final boolean uvlock)
			throws IOException, ProtocolException {
			Map<String, String> textures = new HashMap<>();
			Boolean ambientOcclusion = null;
			java.util.Set<String> forceTranslucent = new java.util.HashSet<>();
			List<Object> elements = null;
			String id = modelId;
			for (int depth = 0; id != null && depth < 32; depth++) {
				if (id.equals("builtin/generated") || id.equals("builtin/entity")) {
					break;
				}
				Map<String, Object> m = model(id);
				if (ambientOcclusion == null && m.get("ambientocclusion") instanceof Boolean aoFlag) {
					ambientOcclusion = aoFlag;
				}
				if (m.get("textures") instanceof Map<?, ?> t) {
					for (Map.Entry<?, ?> e : t.entrySet()) {
						// 26.3: Wert ist ein String oder ein Objekt {"sprite": …, "force_translucent": true}
						if (e.getValue() instanceof Map<?, ?> obj) {
							textures.putIfAbsent((String) e.getKey(), String.valueOf(obj.get("sprite")));
							if (Boolean.TRUE.equals(obj.get("force_translucent"))) {
								forceTranslucent.add(String.valueOf(obj.get("sprite")));
							}
						} else {
							textures.putIfAbsent((String) e.getKey(), String.valueOf(e.getValue()));
						}
					}
				}
				if (elements == null && m.get("elements") instanceof List<?> el) {
					elements = (List<Object>) el;
				}
				id = m.get("parent") instanceof String p ? normalize(p) : null;
			}
			List<RenderQuad> out = new ArrayList<>();
			boolean ao = ambientOcclusion == null || ambientOcclusion;
			String particleName = resolve(textures, "#particle");
			TextureAtlas.Sprite particle = particleName == null ? null : sprite(particleName);
			if (elements == null) {
				return new Baked(out, ao, particle); // z. B. Block-Entity-Modelle (Truhe, Schild) ohne Elemente: unsichtbar statt Debug
			}
			for (Object eo : elements) {
				Map<String, Object> e = (Map<String, Object>) eo;
				float[] from = vec((List<Object>) e.get("from"));
				float[] to = vec((List<Object>) e.get("to"));
				// 26.3: shade entfällt; shade_direction_override setzt die Richtung der Richtungsschattierung, light_emission die Emission
				Face shadeOverride = e.get("shade_direction_override") instanceof String so ? Face.byName(so) : null;
				int lightEmission = e.get("light_emission") instanceof Number le ? le.intValue() : 0;
				Map<String, Object> rot = (Map<String, Object>) e.get("rotation");
				if (!(e.get("faces") instanceof Map<?, ?> faces)) {
					continue;
				}
				// entartete Elemente (eine Ausdehnung 0): nur die Flächen senkrecht zur flachen Achse (UnbakedCuboidGeometry)
				boolean drawX = !(from[1] == to[1] || from[2] == to[2]);
				boolean drawY = !(from[0] == to[0] || from[2] == to[2]);
				boolean drawZ = !(from[0] == to[0] || from[1] == to[1]);
				for (Map.Entry<?, ?> fe : faces.entrySet()) {
					Face face = Face.byName((String) fe.getKey());
					if (!(face.dx != 0 ? drawX : face.dy != 0 ? drawY : drawZ)) {
						continue;
					}
					Map<String, Object> f = (Map<String, Object>) fe.getValue();
					String texName = resolve(textures, String.valueOf(f.get("texture")));
					TextureAtlas.Sprite sprite = sprite(texName);
					boolean translucentForced = texName != null && forceTranslucent.contains(texName);
					float[] uv = f.get("uv") instanceof List<?> u ? vec4((List<Object>) u) : defaultUv(face, from, to);
					int faceRot = f.get("rotation") instanceof Number n ? n.intValue() : 0;
					Face cull = f.get("cullface") instanceof String c ? Face.byName(c.equals("bottom") ? "down" : c) : null;
					int tint = f.get("tintindex") instanceof Number n ? n.intValue() : -1;
					float[] pos = face.corners(from[0] / 16f, from[1] / 16f, from[2] / 16f, to[0] / 16f, to[1] / 16f, to[2] / 16f);
					if (rot != null) {
						rotateElement(pos, rot);
					}
					for (int k = 0; k < 4; k++) {
						rotateVariant(pos, k, rotX, rotY);
					}
					Face shadeFace = rotateFace(face, rotX, rotY);
					Face override = shadeOverride == null ? null : rotateFace(shadeOverride, rotX, rotY);
					// Schicht aus der Transparenz im UV-Ausschnitt (alle Einzelbilder), force_translucent → durchscheinend
					RenderQuad.Layer layer = translucentForced ? RenderQuad.Layer.TRANSLUCENT
						: switch (sprite.transparency(uv[0], uv[1], uv[2], uv[3])) {
							case OPAQUE -> RenderQuad.Layer.SOLID;
							case CUTOUT -> RenderQuad.Layer.CUTOUT;
							case TRANSLUCENT -> RenderQuad.Layer.TRANSLUCENT;
						};
					cull = cull == null ? null : rotateFace(cull, rotX, rotY);
					if (uvlock && (rotX != 0 || rotY != 0)) {
						uv = uvFromPositions(shadeFace, pos);
						faceRot = 0;
					}
					float[] uvs = cornerUvs(sprite, uv, faceRot);
					out.add(new RenderQuad(pos, uvs, cull, shadeFace, override, tint, MeshBuilder.rgba(255, 255, 255, 255), layer, lightEmission));
				}
			}
			return new Baked(out, ao, particle);
		}

		private static float[] vec(final List<Object> l) {
			return new float[]{((Number) l.get(0)).floatValue(), ((Number) l.get(1)).floatValue(), ((Number) l.get(2)).floatValue()};
		}

		private static float[] vec4(final List<Object> l) {
			return new float[]{((Number) l.get(0)).floatValue(), ((Number) l.get(1)).floatValue(), ((Number) l.get(2)).floatValue(),
				((Number) l.get(3)).floatValue()};
		}

		private static String resolve(final Map<String, String> textures, final String ref) {
			String r = ref;
			for (int i = 0; i < 16 && r != null && r.startsWith("#"); i++) {
				r = textures.get(r.substring(1));
			}
			return r == null || r.startsWith("#") ? null : r;
		}

		private TextureAtlas.Sprite sprite(final String texture) throws IOException {
			if (texture == null) {
				return this.atlas.debug();
			}
			String t = texture.startsWith("minecraft:") ? texture.substring(10) : texture;
			TextureAtlas.Sprite s = this.atlas.get(t);
			if (s != null) {
				return s;
			}
			BufferedImage img = image("textures/" + t + ".png");
			if (img == null) {
				return this.atlas.debug();
			}
			TextureAtlas.AnimationMeta anim = null;
			try {
				anim = TextureAtlas.AnimationMeta.parse(json("textures/" + t + ".png.mcmeta"));
			} catch (ProtocolException e) {
				this.problems.add(t + ".png.mcmeta: " + e.getMessage());
			}
			return this.atlas.add(t, img, anim);
		}

		/** Standard-UV einer Fläche aus der Elementgröße (Minecraft-Konvention, Pixel 0–16). */
		static float[] defaultUv(final Face f, final float[] from, final float[] to) {
			return switch (f) {
				case DOWN -> new float[]{from[0], 16 - to[2], to[0], 16 - from[2]};
				case UP -> new float[]{from[0], from[2], to[0], to[2]};
				case NORTH -> new float[]{16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
				case SOUTH -> new float[]{from[0], 16 - to[1], to[0], 16 - from[1]};
				case WEST -> new float[]{from[2], 16 - to[1], to[2], 16 - from[1]};
				case EAST -> new float[]{16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
			};
		}

		/** uvlock: UV aus der endgültigen (gedrehten) Lage der Fläche ableiten. */
		private static float[] uvFromPositions(final Face f, final float[] pos) {
			float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
			float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (int k = 0; k < 4; k++) {
				for (int a = 0; a < 3; a++) {
					min[a] = Math.min(min[a], pos[k * 3 + a] * 16);
					max[a] = Math.max(max[a], pos[k * 3 + a] * 16);
				}
			}
			return defaultUv(f, min, max);
		}

		/** Ecken-UV (BL, BR, TR, TL) aus [u0, v0, u1, v1] mit Flächenrotation in 90°-Schritten. */
		private static float[] cornerUvs(final TextureAtlas.Sprite s, final float[] uv, final int rotation) {
			float[][] c = {{uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}, {uv[0], uv[1]}};
			int shift = ((rotation / 90) % 4 + 4) % 4;
			float[] out = new float[8];
			for (int k = 0; k < 4; k++) {
				float[] src = c[(k + shift) % 4];
				out[k * 2] = s.u(src[0]);
				out[k * 2 + 1] = s.v(src[1]);
			}
			return out;
		}

		@SuppressWarnings("unchecked")
		private static void rotateElement(final float[] pos, final Map<String, Object> rot) {
			if (!rot.containsKey("angle")) {
				rotateEuler(pos, rot);
				return;
			}
			float angle = ((Number) rot.get("angle")).floatValue();
			if (angle == 0) {
				return;
			}
			String axis = String.valueOf(rot.get("axis"));
			float[] o = vec((List<Object>) rot.get("origin"));
			double a = Math.toRadians(angle);
			float c = (float) Math.cos(a);
			float s = (float) Math.sin(a);
			boolean rescale = Boolean.TRUE.equals(rot.get("rescale"));
			float scale = rescale ? (float) (1 / Math.cos(Math.abs(a))) : 1f;
			for (int k = 0; k < 4; k++) {
				float x = pos[k * 3] - o[0] / 16f;
				float y = pos[k * 3 + 1] - o[1] / 16f;
				float z = pos[k * 3 + 2] - o[2] / 16f;
				float nx = x;
				float ny = y;
				float nz = z;
				switch (axis) {
					case "x" -> {
						ny = y * c - z * s;
						nz = y * s + z * c;
						ny *= scale;
						nz *= scale;
					}
					case "y" -> {
						nx = x * c + z * s;
						nz = -x * s + z * c;
						nx *= scale;
						nz *= scale;
					}
					default -> {
						nx = x * c - y * s;
						ny = x * s + y * c;
						nx *= scale;
						ny *= scale;
					}
				}
				pos[k * 3] = nx + o[0] / 16f;
				pos[k * 3 + 1] = ny + o[1] / 16f;
				pos[k * 3 + 2] = nz + o[2] / 16f;
			}
		}

		/**
		 * 26.3-Form {@code {"x": …, "y": …, "z": …, "origin": […]}} (z. B. Hängeschilder): Drehung um X, dann Y, dann Z
		 * (rechtshändig, Grad). Die Reihenfolge ist nicht gegen den Quellcode verifiziert (docs/25 §9).
		 */
		@SuppressWarnings("unchecked")
		private static void rotateEuler(final float[] pos, final Map<String, Object> rot) {
			float[] o = rot.get("origin") instanceof List<?> l ? vec((List<Object>) l) : new float[]{8, 8, 8};
			double[] ang = {num(rot.get("x")), num(rot.get("y")), num(rot.get("z"))};
			for (int k = 0; k < 4; k++) {
				double x = pos[k * 3] - o[0] / 16.0;
				double y = pos[k * 3 + 1] - o[1] / 16.0;
				double z = pos[k * 3 + 2] - o[2] / 16.0;
				double c = Math.cos(Math.toRadians(ang[0]));
				double sn = Math.sin(Math.toRadians(ang[0]));
				double ny = y * c - z * sn;
				double nz = y * sn + z * c;
				y = ny;
				z = nz;
				c = Math.cos(Math.toRadians(ang[1]));
				sn = Math.sin(Math.toRadians(ang[1]));
				double nx = x * c + z * sn;
				nz = -x * sn + z * c;
				x = nx;
				z = nz;
				c = Math.cos(Math.toRadians(ang[2]));
				sn = Math.sin(Math.toRadians(ang[2]));
				nx = x * c - y * sn;
				ny = x * sn + y * c;
				pos[k * 3] = (float) (nx + o[0] / 16.0);
				pos[k * 3 + 1] = (float) (ny + o[1] / 16.0);
				pos[k * 3 + 2] = (float) (z + o[2] / 16.0);
			}
		}

		private static double num(final Object o) {
			return o instanceof Number n ? n.doubleValue() : 0;
		}

		/**
		 * Variantenrotation um den Blockmittelpunkt: erst x, dann y, je 90°-Schritt; x=90 dreht oben → Norden, y=90 dreht
		 * Osten → Süden (Minecraft-Konvention, negative Winkel im rechtshändigen System).
		 */
		private static void rotateVariant(final float[] pos, final int k, final int rotX, final int rotY) {
			float x = pos[k * 3];
			float y = pos[k * 3 + 1];
			float z = pos[k * 3 + 2];
			for (int i = 0; i < ((rotX / 90) % 4 + 4) % 4; i++) {
				float ny = z;
				float nz = 1 - y;
				y = ny;
				z = nz;
			}
			for (int i = 0; i < ((rotY / 90) % 4 + 4) % 4; i++) {
				float nx = 1 - z;
				float nz = x;
				x = nx;
				z = nz;
			}
			pos[k * 3] = x;
			pos[k * 3 + 1] = y;
			pos[k * 3 + 2] = z;
		}

		static Face rotateFace(final Face f, final int rotX, final int rotY) {
			int x = f.dx;
			int y = f.dy;
			int z = f.dz;
			for (int i = 0; i < ((rotX / 90) % 4 + 4) % 4; i++) {
				int ny = z;
				int nz = -y;
				y = ny;
				z = nz;
			}
			for (int i = 0; i < ((rotY / 90) % 4 + 4) % 4; i++) {
				int nx = -z;
				int nz = x;
				x = nx;
				z = nz;
			}
			for (Face r : Face.values()) {
				if (r.dx == x && r.dy == y && r.dz == z) {
					return r;
				}
			}
			throw new IllegalStateException();
		}

		// ----- Flüssigkeiten, Debug, Abschluss -----

		/** Wasser/Lava/Blasensäule: kein Blockmodell (unsichtbare Renderform); die Geometrie erzeugt der Mesher aus dem Fluidzustand. */
		private RenderModel fluidModel(final String block, final BlockState s) throws IOException {
			boolean water = !block.equals("lava");
			sprite(water ? "block/water_still" : "block/lava_still");
			return new RenderModel(List.of(), new boolean[6], false, water ? WATER_GROUP : LAVA_GROUP, true, water ? 1 : 2);
		}

		private RenderModel debugModel() {
			TextureAtlas.Sprite d = this.atlas.debug();
			List<RenderQuad> quads = new ArrayList<>();
			float[] full = {0, 0, 16, 16};
			for (Face f : Face.values()) {
				quads.add(new RenderQuad(f.corners(0.1f, 0.1f, 0.1f, 0.9f, 0.9f, 0.9f), cornerUvs(d, full, 0), null, f, null, -1,
					MeshBuilder.rgba(255, 255, 255, 255), RenderQuad.Layer.SOLID, 0));
			}
			return new RenderModel(quads, new boolean[6], true, 0, false, 0);
		}

		/**
		 * Verdeckung je Seite: alle undurchsichtigen Flächen mit dieser cullface auf der Randebene decken zusammen das ganze
		 * Einheitsquadrat ab (16×16-Raster) – z. B. die Rückseite einer Treppe aus zwei Elementen.
		 */
		private static RenderModel finish(final List<RenderQuad> quads, final int group, final boolean ambientOcclusion) {
			boolean[] occ = new boolean[6];
			boolean[][] cover = new boolean[6][256];
			for (RenderQuad q : quads) {
				if (q.cullFace() == null || q.layer() != RenderQuad.Layer.SOLID) {
					continue;
				}
				Face f = q.cullFace();
				float[] p = q.positions();
				float minA = 1;
				float maxA = 0;
				float minB = 1;
				float maxB = 0;
				boolean onPlane = true;
				for (int k = 0; k < 4; k++) {
					float px = p[k * 3];
					float py = p[k * 3 + 1];
					float pz = p[k * 3 + 2];
					float plane = f.dx != 0 ? px : f.dy != 0 ? py : pz;
					float target = (f.dx + f.dy + f.dz) > 0 ? 1f : 0f;
					onPlane &= Math.abs(plane - target) < 1e-4;
					float a = f.dx != 0 ? py : px;
					float b = f.dz != 0 ? py : pz;
					minA = Math.min(minA, a);
					maxA = Math.max(maxA, a);
					minB = Math.min(minB, b);
					maxB = Math.max(maxB, b);
				}
				if (onPlane) {
					for (int ia = Math.round(minA * 16); ia < Math.round(maxA * 16); ia++) {
						for (int ib = Math.round(minB * 16); ib < Math.round(maxB * 16); ib++) {
							if (ia >= 0 && ia < 16 && ib >= 0 && ib < 16) {
								cover[f.ordinal()][ia * 16 + ib] = true;
							}
						}
					}
				}
			}
			for (int f = 0; f < 6; f++) {
				boolean all = true;
				for (boolean b : cover[f]) {
					all &= b;
				}
				occ[f] = all;
			}
			return new RenderModel(quads, occ, false, group, ambientOcclusion, 0);
		}
	}
}
