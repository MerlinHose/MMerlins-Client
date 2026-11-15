package dev.mcresearch.protocolclient.physics;

import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.BlockState;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Zuordnung Blockzustand → Kollisionsform (docs/22 §2). Faktendaten aus {@code physics/shapes-777.txt.gz}, erzeugt mit
 * {@code analysis/tools/ShapeExtractor.java} aus dem unveränderten Server-JAR. Positions- und kontextabhängige Regeln
 * (Offset-Blöcke, Gerüst, Pulverschnee) sind hier nach docs/21 §6 umgesetzt.
 */
public final class ShapeRegistry {
	public static final float DEFAULT_FRICTION = 0.6F;
	private static final ShapeRegistry INSTANCE = load(BlockRegistry.get());

	/** Referenzform des Servers an einer Position (Zeile {@code P}), zur Validierung der Offset-Berechnung. */
	public record Probe(int stateId, int x, int y, int z, Shape shape) {
	}

	private record BlockFactors(float friction, float speedFactor, float jumpFactor) {
	}

	private final BlockRegistry blocks;
	private final Shape[] shapes;
	private final int[] stateShape;
	private final byte[] flags;
	private final Map<Integer, Float> offsetMaxH;
	private final Map<Integer, Float> offsetMaxV;
	private final Map<Integer, Shape> offsetBase;
	private final List<Probe> probes;
	private final Map<String, BlockFactors> factors;
	private final Shape scaffoldingStable;
	private final Shape scaffoldingUnstableBottom;
	private final Shape powderSnowFalling;
	private final int[] outline;
	private final Map<Integer, Shape> outlineOffsetBase;
	private final Map<Integer, Shape> interaction;
	private final float[] hardness;
	private final byte[] blockFlags;
	static final byte REQUIRES_TOOL = 1;
	static final byte REPLACEABLE = 2;
	static final byte USE_WITHOUT_ITEM = 4;
	static final byte USE_ITEM_ON = 8;

	static final byte FULL = 1;
	static final byte LARGE = 2;
	static final byte DYNAMIC = 4;
	static final byte OFFSET = 8;

	private ShapeRegistry(final BlockRegistry blocks, final Shape[] shapes, final int[] stateShape, final byte[] flags, final Map<Integer, Float> offsetMaxH,
		final Map<Integer, Float> offsetMaxV, final Map<Integer, Shape> offsetBase, final List<Probe> probes, final Map<String, BlockFactors> factors, final Map<String, Shape> context,
		final int[] outline, final Map<Integer, Shape> outlineOffsetBase, final Map<Integer, Shape> interaction, final float[] hardness, final byte[] blockFlags) {
		this.outline = outline;
		this.outlineOffsetBase = outlineOffsetBase;
		this.interaction = interaction;
		this.hardness = hardness;
		this.blockFlags = blockFlags;
		this.blocks = blocks;
		this.shapes = shapes;
		this.stateShape = stateShape;
		this.flags = flags;
		this.offsetMaxH = offsetMaxH;
		this.offsetMaxV = offsetMaxV;
		this.offsetBase = offsetBase;
		this.probes = List.copyOf(probes);
		this.factors = factors;
		this.scaffoldingStable = require(context, "ScaffoldingBlock.SHAPE_STABLE");
		this.scaffoldingUnstableBottom = require(context, "ScaffoldingBlock.SHAPE_UNSTABLE_BOTTOM");
		this.powderSnowFalling = require(context, "PowderSnowBlock.FALLING_COLLISION_SHAPE");
	}

	private static Shape require(final Map<String, Shape> context, final String key) {
		Shape s = context.get(key);
		if (s == null) {
			throw new IllegalStateException("shape table lacks context shape " + key);
		}
		return s;
	}

	public static ShapeRegistry get() {
		return INSTANCE;
	}

	static ShapeRegistry load(final BlockRegistry blocks) {
		try (InputStream raw = ShapeRegistry.class.getResourceAsStream("/physics/shapes-777.txt.gz")) {
			if (raw == null) {
				throw new IllegalStateException("Missing /physics/shapes-777.txt.gz");
			}
			List<Shape> shapes = new ArrayList<>();
			int[] stateShape = new int[blocks.size()];
			byte[] flags = new byte[blocks.size()];
			java.util.Arrays.fill(stateShape, -1);
			Map<Integer, Float> offsetMaxH = new HashMap<>();
			Map<Integer, Float> offsetMaxV = new HashMap<>();
			Map<Integer, long[]> faceMasks = new HashMap<>();
			Map<Integer, int[]> lightFaces = new HashMap<>();
			Map<Integer, Integer> offsetBaseIndex = new HashMap<>();
			List<String[]> probeLines = new ArrayList<>();
			Map<String, BlockFactors> factors = new HashMap<>();
			Map<String, Integer> contextIndex = new HashMap<>();
			int[] outline = new int[blocks.size()];
			java.util.Arrays.fill(outline, -1);
			Map<Integer, Integer> outlineBaseIndex = new HashMap<>();
			Map<Integer, Integer> interactionIndex = new HashMap<>();
			float[] hardness = new float[blocks.size()];
			byte[] lightDampening = new byte[blocks.size()];
			byte[] lightEmission = new byte[blocks.size()];
			byte[] blockFlags = new byte[blocks.size()];
			try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}
					String[] p = line.split("\t", -1);
					switch (p[0]) {
						case "S" -> {
							if (Integer.parseInt(p[1]) != shapes.size()) {
								throw new IllegalStateException("shape table not contiguous at " + p[1]);
							}
							shapes.add(parseShape(p[2], p[3]));
						}
						case "B" -> {
							int id = Integer.parseInt(p[1]);
							stateShape[id] = Integer.parseInt(p[2]);
							byte f = 0;
							for (char ch : p[3].toCharArray()) {
								f |= switch (ch) {
									case 'F' -> FULL;
									case 'L' -> LARGE;
									case 'D' -> DYNAMIC;
									case 'O' -> OFFSET;
									default -> throw new IllegalStateException("unknown flag " + ch);
								};
							}
							flags[id] = f;
						}
						case "P" -> probeLines.add(p);
						case "O" -> {
							// O stateId maxH baseIndex maxV; baseIndex −1: Kollision hängt nicht von der Position ab (Offset nur im Modell)
							int id = Integer.parseInt(p[1]);
							offsetMaxH.put(id, Float.parseFloat(p[2]));
							offsetMaxV.put(id, p.length > 4 ? Float.parseFloat(p[4]) : 0f);
							if (Integer.parseInt(p[3]) >= 0) {
								offsetBaseIndex.put(id, Integer.parseInt(p[3]));
							}
						}
						case "C" -> contextIndex.put(p[1], Integer.parseInt(p[2]));
						case "U" -> outline[Integer.parseInt(p[1])] = Integer.parseInt(p[2]);
						case "UO" -> outlineBaseIndex.put(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
						case "N" -> interactionIndex.put(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
						case "H" -> {
							int id = Integer.parseInt(p[1]);
							hardness[id] = Float.parseFloat(p[2]);
							String use = p.length > 5 ? p[5] : "-";
							blockFlags[id] = (byte) (("1".equals(p[3]) ? REQUIRES_TOOL : 0) | ("1".equals(p[4]) ? REPLACEABLE : 0)
								| (use.indexOf('W') >= 0 ? USE_WITHOUT_ITEM : 0) | (use.indexOf('I') >= 0 ? USE_ITEM_ON : 0));
						}
						case "LF" -> {
							long[] m = new long[4];
							String hex = p[2];
							for (int w = 0; w < 4; w++) {
								m[3 - w] = Long.parseUnsignedLong(hex.substring(w * 16, w * 16 + 16), 16);
							}
							faceMasks.put(Integer.parseInt(p[1]), m);
						}
						case "L" -> {
							int id = Integer.parseInt(p[1]);
							lightDampening[id] = (byte) Integer.parseInt(p[2]);
							lightEmission[id] = (byte) Integer.parseInt(p[3]);
							if (p[4].equals("S")) {
								String[] fi = p[5].split(",");
								int[] faces = new int[6];
								for (int k = 0; k < 6; k++) {
									faces[k] = Integer.parseInt(fi[k]);
								}
								lightFaces.put(id, faces);
							}
						}
						case "M", "R", "Y" -> {
							// M11-Bewegungsfakten: BlockMovementFacts
						}
						case "K" -> factors.put(p[1], new BlockFactors(Float.parseFloat(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4])));
						default -> throw new IllegalStateException("unknown line type " + p[0]);
					}
				}
			}
			for (int i = 0; i < stateShape.length; i++) {
				if (stateShape[i] < 0 || stateShape[i] >= shapes.size()) {
					throw new IllegalStateException("state " + i + " has no valid shape");
				}
			}
			Map<Integer, Shape> offsetBase = new HashMap<>();
			offsetBaseIndex.forEach((id, idx) -> offsetBase.put(id, shapes.get(idx)));
			List<Probe> probes = new ArrayList<>();
			for (String[] p : probeLines) {
				String[] xyz = p[2].split(",");
				probes.add(new Probe(Integer.parseInt(p[1]), Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]),
					shapes.get(Integer.parseInt(p[3]))));
			}
			Map<String, Shape> context = new HashMap<>();
			contextIndex.forEach((k, idx) -> context.put(k, shapes.get(idx)));
			for (int i = 0; i < outline.length; i++) {
				if (outline[i] < 0 || outline[i] >= shapes.size()) {
					throw new IllegalStateException("state " + i + " has no valid outline shape");
				}
			}
			Map<Integer, Shape> outlineBase = new HashMap<>();
			outlineBaseIndex.forEach((id, idx) -> outlineBase.put(id, shapes.get(idx)));
			Map<Integer, Shape> interaction = new HashMap<>();
			interactionIndex.forEach((id, idx) -> interaction.put(id, shapes.get(idx)));
			ShapeRegistry reg = new ShapeRegistry(blocks, shapes.toArray(Shape[]::new), stateShape, flags, offsetMaxH, offsetMaxV, offsetBase, probes, factors,
				context, outline, outlineBase, interaction, hardness, blockFlags);
			reg.lightDampening = lightDampening;
			reg.lightEmission = lightEmission;
			long[][] faces = new long[faceMasks.size()][];
			faceMasks.forEach((k, v) -> faces[k] = v);
			reg.faceMasks = faces;
			reg.lightFaces = Map.copyOf(lightFaces);
			return reg;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Shape parseShape(final String boxes, final String ys) {
		if (boxes.isEmpty()) {
			return Shape.EMPTY;
		}
		String[] bs = boxes.split(";");
		Aabb[] out = new Aabb[bs.length];
		for (int i = 0; i < bs.length; i++) {
			String[] v = bs[i].split(",");
			out[i] = new Aabb(Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3]),
				Double.parseDouble(v[4]), Double.parseDouble(v[5]));
		}
		String[] yv = ys.split(",");
		double[] y = new double[yv.length];
		for (int i = 0; i < y.length; i++) {
			y[i] = Double.parseDouble(yv[i]);
		}
		return new Shape(out, y);
	}

	public int stateCount() {
		return this.stateShape.length;
	}

	public int shapeCount() {
		return this.shapes.length;
	}

	/** Kontextfreie Form wie im Zustands-Cache ({@code CollisionContext.empty()}, Position 0/0/0). */
	public Shape baseShape(final int stateId) {
		return this.shapes[this.stateShape[stateId]];
	}

	public boolean isFullBlock(final int stateId) {
		return (this.flags[stateId] & FULL) != 0;
	}

	/** Form ragt über die Blockzelle hinaus ({@code hasLargeCollisionShape}): wird auch aus Randzellen abgefragt. */
	public boolean isLarge(final int stateId) {
		return (this.flags[stateId] & LARGE) != 0;
	}

	public boolean isDynamic(final int stateId) {
		return (this.flags[stateId] & DYNAMIC) != 0;
	}

	public List<Probe> probes() {
		return this.probes;
	}

	/**
	 * Kollisionsform eines Zustands an einer Blockposition, blocklokal (0…1), für den Spieler-Kontext.
	 */
	public Shape collisionShape(final int stateId, final int x, final int y, final int z, final CollisionContext ctx) {
		Shape base = this.offsetBase.get(stateId);
		if (base != null) {
			return base.isEmpty() ? base : offset(stateId, base, x, z);
		}
		if (isDynamic(stateId)) {
			BlockState s = this.blocks.state(stateId);
			switch (s.name()) {
				case "minecraft:scaffolding" -> {
					if (ctx.isAbove(1.0, y) && !ctx.descending()) {
						return this.scaffoldingStable;
					}
					boolean bottom = "true".equals(s.properties().get("bottom"));
					boolean distanceNonZero = !"0".equals(s.properties().get("distance"));
					return distanceNonZero && bottom && ctx.isAbove(0.0, y) ? this.scaffoldingUnstableBottom : Shape.EMPTY;
				}
				case "minecraft:powder_snow" -> {
					// ohne Lederstiefel (Ausrüstung wird nicht modelliert): nur beim Fallen > 2,5 Blöcke fest
					return ctx.fallDistance() > 2.5 ? this.powderSnowFalling : Shape.EMPTY;
				}
				default -> {
					// shulker_box, moving_piston: Form hängt von Block-Entity-Daten ab → Tabellenwert (geschlossen bzw. leer)
				}
			}
		}
		return baseShape(stateId);
	}

	// ----- M7: Umriss, Interaktionsform, Härte (docs/27 §2) -----

	/**
	 * Umrissform ({@code BlockBehaviour#getShape}, Grundlage von {@code ClipContext.Block.OUTLINE}) blocklokal; bei
	 * Offset-Blöcken (Blumen, Gras, Bambus …) um denselben Positions-Offset verschoben wie die Kollision.
	 */
	public Shape outlineShape(final int stateId, final int x, final int z) {
		Shape base = this.outlineOffsetBase.get(stateId);
		if (base != null && !base.isEmpty()) {
			return offset(stateId, base, x, z);
		}
		return this.shapes[this.outline[stateId]];
	}

	/**
	 * {@code BlockBehaviour.Properties#offsetType}: XZ und XYZ (y = (float(seed&gt;&gt;4 &amp; 15)/15F − 1,0) · maxV; bei XZ ist maxV 0).
	 */
	private Shape offset(final int stateId, final Shape base, final int x, final int z) {
		float maxH = this.offsetMaxH.get(stateId);
		float maxV = this.offsetMaxV.getOrDefault(stateId, 0f);
		long seed = seed(x, 0, z);
		double dy = maxV == 0f ? 0.0 : ((float) (seed >> 4 & 15L) / 15.0F - 1.0) * maxV;
		double dx = clamp(((float) (seed & 15L) / 15.0F - 0.5) * 0.5, -maxH, maxH);
		double dz = clamp(((float) (seed >> 8 & 15L) / 15.0F - 0.5) * 0.5, -maxH, maxH);
		return base.move(dx, dy, dz);
	}

	/**
	 * M12: Positionsversatz des <b>Modells</b> ({@code BlockState#getOffset}, docs/47 §2.4) – Blumen, Gras, Bambus …;
	 * {@code null} ohne Versatzfunktion. Gleiche Formel wie die Kollision (Zeilen {@code O}), unabhängig davon, ob die Form
	 * selbst verschoben wird.
	 */
	public float[] modelOffset(final int stateId, final int x, final int z) {
		Float maxH = this.offsetMaxH.get(stateId);
		if (maxH == null) {
			return null;
		}
		float maxV = this.offsetMaxV.getOrDefault(stateId, 0f);
		long seed = seed(x, 0, z);
		double dy = maxV == 0f ? 0.0 : ((float) (seed >> 4 & 15L) / 15.0F - 1.0) * maxV;
		double dx = clamp(((float) (seed & 15L) / 15.0F - 0.5) * 0.5, -maxH, maxH);
		double dz = clamp(((float) (seed >> 8 & 15L) / 15.0F - 0.5) * 0.5, -maxH, maxH);
		return new float[]{(float) dx, (float) dy, (float) dz};
	}

	/** Interaktionsform ({@code getInteractionShape}); leer für die meisten Blöcke. */
	public Shape interactionShape(final int stateId) {
		return this.interaction.getOrDefault(stateId, Shape.EMPTY);
	}

	/** {@code destroyTime}; −1 = unzerstörbar (Grundgestein, Barriere). */
	public float hardness(final int stateId) {
		return this.hardness[stateId];
	}

	public boolean requiresCorrectTool(final int stateId) {
		return (this.blockFlags[stateId] & REQUIRES_TOOL) != 0;
	}

	// ----- M7-Korrektur: Lichteigenschaften (docs/29 §3.3), Zeilen L/LF -----

	private byte[] lightDampening;
	private byte[] lightEmission;
	private long[][] faceMasks;
	private Map<Integer, int[]> lightFaces;
	private static final long[] NO_FACE = new long[4];

	/** {@code BlockState#getLightDampening} (0–15). */
	public int lightDampening(final int stateId) {
		return this.lightDampening[stateId];
	}

	/** {@code BlockState#getLightEmission} (0–15). */
	public int lightEmission(final int stateId) {
		return this.lightEmission[stateId];
	}

	/** {@code LightEngine#isEmptyShape}: !canOcclude || !useShapeForLightOcclusion – keine Formverdeckung für Licht. */
	public boolean lightEmptyShape(final int stateId) {
		return !this.lightFaces.containsKey(stateId);
	}

	/** {@code LightEngine#getOcclusionShape(state, dir)} als 16×16-Bedeckungsmaske (4 × 64 Bit); Richtung im 3D-Index (down 0 … east 5). */
	public long[] lightFaceMask(final int stateId, final int direction) {
		int[] f = this.lightFaces.get(stateId);
		return f == null ? NO_FACE : this.faceMasks[f[direction]];
	}

	/** {@code Shapes#faceShapeOccludes}: die beiden Seitenformen bedecken zusammen die ganze Fläche. */
	public static boolean faceShapeOccludes(final long[] a, final long[] b) {
		for (int i = 0; i < 4; i++) {
			if ((a[i] | b[i]) != -1L) {
				return false;
			}
		}
		return true;
	}

	/** {@code LightEngine#hasDifferentLightProperties}. */
	public boolean hasDifferentLightProperties(final int oldState, final int newState) {
		return oldState != newState && (this.lightDampening[oldState] != this.lightDampening[newState]
			|| this.lightEmission[oldState] != this.lightEmission[newState] || !lightEmptyShape(oldState) || !lightEmptyShape(newState));
	}

	/** Blockklasse überschreibt {@code useWithoutItem} (eigene Rechtsklick-Aktion ohne Item, z. B. Tür, Truhe, Hebel). */
	public boolean usesWithoutItem(final int stateId) {
		return (this.blockFlags[stateId] & USE_WITHOUT_ITEM) != 0;
	}

	/** Blockklasse überschreibt {@code useItemOn} (eigene Reaktion auf ein Item, z. B. Kessel, Kuchen mit Kerze). */
	public boolean usesItemOn(final int stateId) {
		return (this.blockFlags[stateId] & USE_ITEM_ON) != 0;
	}

	public boolean replaceable(final int stateId) {
		return (this.blockFlags[stateId] & REPLACEABLE) != 0;
	}

	/** {@code Mth.getSeed}: int-Multiplikation für x (mit Überlauf), long für z. */
	static long seed(final int x, final int y, final int z) {
		long seed = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
		seed = seed * seed * 42317861L + seed * 11L;
		return seed >> 16;
	}

	private static double clamp(final double v, final double min, final double max) {
		return v < min ? min : Math.min(v, max);
	}

	private BlockFactors factors(final int stateId) {
		return this.factors.get(this.blocks.state(stateId).name());
	}

	/** {@code Block#getFriction}: Standard 0,6; Eis 0,98, Blaueis 0,989, Schleim 0,8. */
	public float friction(final int stateId) {
		BlockFactors f = factors(stateId);
		return f == null ? DEFAULT_FRICTION : f.friction();
	}

	public float speedFactor(final int stateId) {
		BlockFactors f = factors(stateId);
		return f == null ? 1.0F : f.speedFactor();
	}

	public float jumpFactor(final int stateId) {
		BlockFactors f = factors(stateId);
		return f == null ? 1.0F : f.jumpFactor();
	}
}
