import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Analysewerkzeug (M5, docs/22 §2): erzeugt die Faktentabelle "Blockzustand → Kollisionsboxen" aus dem unveränderten
 * Vanilla-Server-JAR, analog zum offiziellen Datagen-Report. Läuft nur hier im Analyseprojekt; der eigene Client enthält
 * ausschließlich die erzeugte Datentabelle, keinen Minecraft-Code.
 *
 * <pre>
 * java -cp "server/versions/26.3/server-26.3.jar;server/libraries/...jar" analysis/tools/ShapeExtractor.java &lt;shapes.txt.gz&gt;
 * </pre>
 *
 * Format (Tab-getrennt):
 * <pre>
 *   S  index  boxes  ys  Form; boxes = minX,minY,minZ,maxX,maxY,maxZ;…  (leer = keine Kollision); ys = Y-Gitter
 *   B  stateId  index  flags   flags: F voller Block, L große Form (&gt;1 Block), D dynamische Form, O Offset-Funktion
 *   P  stateId  x,y,z  index  Form an anderer Position (nur bei D, wenn abweichend) – Referenz zur Validierung
 *   O  stateId  maxH  baseIndex  maxV   Offset-Block (XZ/XYZ): Kollision = Grundform + Offset(pos); baseIndex -1 = positionsunabhängig
 *   C  Klasse.FELD  index      kontextabhängige Formen (Gerüst, Pulverschnee)
 *   U  stateId  index          Umrissform (getShape, CollisionContext.empty()) – Auswahl/Raycast (M7)
 *   UO stateId  baseIndex      offset-freie Umrissform, nur wenn getShape den Offset anwendet (FlowerBlock ja, TallGrassBlock nein)
 *   N  stateId  index          Interaktionsform (getInteractionShape), nur wenn nicht leer
 *   H  stateId  destroyTime  requiresTool  replaceable  use   use: W = useWithoutItem, I = useItemOn überschrieben, - = keine
 *   K  block  friction  speedFactor  jumpFactor   (nur Abweichungen von 0.6/1/1)
 *   M  stateId  fluid  sturdy  tags   (M11) fluid = W/L + Menge (1–8) + S (Quelle) + F (fallend) oder "-"; sturdy = Bitmaske
 *      isFaceSturdy je Direction.get3DDataValue (0 unten … 5 Osten); tags: C climbable, B blocks_fluid_flow, N suppresses_bounce,
 *      X isSuffocating, I Eisblock (IceBlock: keine feste Seite für Fluidströmung)
 *   R  block  bounceRestitution   (M11, nur ≠ 0)
 *   Y  block  Klassenname        (M11, Verhalten von stepOn/entityInside/fallOn je Blockklasse)
 * </pre>
 */
public final class ShapeExtractor {
	private static final BlockPos[] PROBES = {new BlockPos(17, 64, -5), new BlockPos(-300, 10, 1201), new BlockPos(5, -60, 9)};

	public static void main(final String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		Map<String, Integer> shapes = new LinkedHashMap<>();
		List<String> stateLines = new ArrayList<>();
		List<String> probeLines = new ArrayList<>();
		List<String> offsetLines = new ArrayList<>();
		List<String> outlineLines = new ArrayList<>();
		List<String> lightLines = new ArrayList<>();
		Map<String, Integer> lightFaces = new LinkedHashMap<>();
		Map<String, String> blockFactors = new TreeMap<>();
		List<String> fluidLines = new ArrayList<>();
		Map<String, String> bounceLines = new TreeMap<>();
		Map<String, String> blockClasses = new TreeMap<>();
		int dynamic = 0;
		for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
			int id = Block.getId(state);
			VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
			int index = shapes.computeIfAbsent(encode(shape), k -> shapes.size());
			StringBuilder flags = new StringBuilder();
			if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
				flags.append('F');
			}
			if (state.hasLargeCollisionShape()) {
				flags.append('L');
			}
			if (state.getBlock().hasDynamicShape()) {
				flags.append('D');
				dynamic++;
				for (BlockPos p : PROBES) {
					String other = encode(state.getCollisionShape(EmptyBlockGetter.INSTANCE, p, CollisionContext.empty())); // blocklokal
					if (!other.equals(encode(shape))) {
						int otherIndex = shapes.computeIfAbsent(other, k -> shapes.size());
						probeLines.add("P\t" + id + "\t" + p.getX() + "," + p.getY() + "," + p.getZ() + "\t" + otherIndex);
					}
				}
			}
			if (state.hasOffsetFunction()) {
				flags.append('O');
				// Grenzen der Verschiebung (getMaxHorizontal/VerticalOffset, geschützt → Reflection). Ob eine Form den Offset
				// tatsächlich anwendet, entscheidet der Block selbst (FlowerBlock ja, TallGrassBlock nein) → empirisch prüfen.
				java.lang.reflect.Method m = net.minecraft.world.level.block.state.BlockBehaviour.class.getDeclaredMethod("getMaxHorizontalOffset");
				m.setAccessible(true);
				float maxH = (float) m.invoke(state.getBlock());
				java.lang.reflect.Method mv = net.minecraft.world.level.block.state.BlockBehaviour.class.getDeclaredMethod("getMaxVerticalOffset");
				mv.setAccessible(true);
				// OffsetType.XYZ erkennbar an y ≠ 0 bei (0,0,0) (seed 0 → y = −maxV); bei XZ wirkt maxV nicht → 0
				float maxV = state.getOffset(BlockPos.ZERO).y != 0.0 ? (float) mv.invoke(state.getBlock()) : 0f;
				int base = offsetBase(state, shape, p -> state.getCollisionShape(EmptyBlockGetter.INSTANCE, p, CollisionContext.empty()), shapes);
				offsetLines.add("O	" + id + "	" + maxH + "	" + base + "	" + maxV);
			}
			stateLines.add("B\t" + id + "\t" + index + "\t" + flags);
			// M7: Umriss (BlockBehaviour#getShape, Auswahl über ClipContext.Block.OUTLINE), Interaktionsform, Härte, Werkzeugpflicht
			VoxelShape outline = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
			outlineLines.add("U\t" + id + "\t" + shapes.computeIfAbsent(encode(outline), k -> shapes.size()));
			if (state.hasOffsetFunction()) {
				int ob = offsetBase(state, outline, p -> state.getShape(EmptyBlockGetter.INSTANCE, p, CollisionContext.empty()), shapes);
				if (ob >= 0) {
					outlineLines.add("UO\t" + id + "\t" + ob);
				}
			}
			VoxelShape interaction = state.getInteractionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			if (!interaction.isEmpty()) {
				outlineLines.add("N\t" + id + "\t" + shapes.computeIfAbsent(encode(interaction), k -> shapes.size()));
			}
			outlineLines.add("H\t" + id + "\t" + state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) + "\t"
				+ (state.requiresCorrectToolForDrops() ? 1 : 0) + "\t" + (state.canBeReplaced() ? 1 : 0) + "\t" + useFlags(state.getBlock()));
			// M7-Korrektur (Licht, docs/29 §3.3): Lichtdämpfung, Leuchtkraft, Formverdeckung je Seite (16×16-Maske)
			boolean emptyShape = !state.canOcclude() || !state.useShapeForLightOcclusion();
			if (state.getLightDampening() != 0 || state.getLightEmission() != 0 || !emptyShape) {
				StringBuilder faces = new StringBuilder();
				if (!emptyShape) {
					for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
						String mask = faceMask(state.getFaceOcclusionShape(d), d);
						faces.append(faces.isEmpty() ? "" : ",").append(lightFaces.computeIfAbsent(mask, k -> lightFaces.size()));
					}
				}
				lightLines.add("L\t" + id + "\t" + state.getLightDampening() + "\t" + state.getLightEmission() + "\t" + (emptyShape ? "E" : "S") + "\t"
					+ (faces.isEmpty() ? "-" : faces));
			}
			Block b = state.getBlock();
			if (b.getFriction() != 0.6F || b.getSpeedFactor() != 1.0F || b.getJumpFactor() != 1.0F) {
				blockFactors.put(BuiltInRegistries.BLOCK.getKey(b).toString(), b.getFriction() + "\t" + b.getSpeedFactor() + "\t" + b.getJumpFactor());
			}
			// M11 (docs/42 §7): Fluidzustand, tragfähige Seiten, Bewegungs-Tags, Ersticken – nur Zustände mit mindestens einer Angabe
			net.minecraft.world.level.material.FluidState fs = state.getFluidState();
			String fluid = "-";
			if (!fs.isEmpty()) {
				// Tags sind ohne Datenpaket nicht gebunden: Fluidtyp über Fluid#isSame (Wasser/Lava inkl. fließend)
				fluid = (fs.getType().isSame(net.minecraft.world.level.material.Fluids.WATER) ? "W"
					: fs.getType().isSame(net.minecraft.world.level.material.Fluids.LAVA) ? "L" : "?") + fs.getAmount()
					+ (fs.isSource() ? "S" : "") + (fs.hasProperty(net.minecraft.world.level.material.FlowingFluid.FALLING)
					&& fs.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING) ? "F" : "");
			}
			int sturdy = 0;
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
				if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, d)) {
					sturdy |= 1 << d.get3DDataValue();
				}
			}
			StringBuilder tags = new StringBuilder();
			String blockKey = BuiltInRegistries.BLOCK.getKey(b).toString();
			if (tag("climbable").contains(blockKey)) {
				tags.append('C');
			}
			if (tag("blocks_fluid_flow").contains(blockKey)) {
				tags.append('B');
			}
			if (tag("suppresses_bounce").contains(blockKey)) {
				tags.append('N');
			}
			if (state.isSuffocating(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
				tags.append('X');
			}
			if (b instanceof net.minecraft.world.level.block.IceBlock) {
				tags.append('I');
			}
			if (!fluid.equals("-") || sturdy != 0 || !tags.isEmpty()) {
				fluidLines.add("M\t" + id + "\t" + fluid + "\t" + sturdy + "\t" + (tags.isEmpty() ? "-" : tags));
			}
			if (b.getBounceRestitution() != 0.0F) {
				bounceLines.put(BuiltInRegistries.BLOCK.getKey(b).toString(), Float.toString(b.getBounceRestitution()));
			}
			blockClasses.putIfAbsent(BuiltInRegistries.BLOCK.getKey(b).toString(), b.getClass().getSimpleName());
		}
		// kontextabhängige Formen (docs/21 §4): statische Felder per Reflection
		List<String> contextLines = new ArrayList<>();
		for (String[] f : new String[][]{{"net.minecraft.world.level.block.ScaffoldingBlock", "SHAPE_STABLE"},
			{"net.minecraft.world.level.block.ScaffoldingBlock", "SHAPE_UNSTABLE_BOTTOM"},
			{"net.minecraft.world.level.block.PowderSnowBlock", "FALLING_COLLISION_SHAPE"}}) {
			java.lang.reflect.Field field = Class.forName(f[0]).getDeclaredField(f[1]);
			field.setAccessible(true);
			int idx = shapes.computeIfAbsent(encode((VoxelShape) field.get(null)), k -> shapes.size());
			contextLines.add("C	" + f[0].substring(f[0].lastIndexOf('.') + 1) + "." + f[1] + "	" + idx);
		}
		Path out = Path.of(args[0]);
		Files.createDirectories(out.toAbsolutePath().getParent());
		try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(out)), StandardCharsets.UTF_8))) {
			w.println("# Kollisionsformen Minecraft 26.3 / Protokoll 777 - Quelle: BlockState#getCollisionShape(EmptyBlockGetter, pos, CollisionContext.empty())");
			w.println("# erzeugt mit analysis/tools/ShapeExtractor.java aus server-26.3.jar (unverändert)");
			shapes.forEach((k, v) -> w.println("S\t" + v + "\t" + k));
			stateLines.forEach(w::println);
			probeLines.forEach(w::println);
			offsetLines.forEach(w::println);
			contextLines.forEach(w::println);
			outlineLines.forEach(w::println);
			lightFaces.forEach((k, v) -> w.println("LF\t" + v + "\t" + k));
			lightLines.forEach(w::println);
			blockFactors.forEach((k, v) -> w.println("K\t" + k + "\t" + v));
			fluidLines.forEach(w::println);
			bounceLines.forEach((k, v) -> w.println("R\t" + k + "\t" + v));
			blockClasses.forEach((k, v) -> w.println("Y\t" + k + "\t" + v));
		}
		// zweite Ausgabe (optional): Items, die einen Block platzieren (BlockItem#getBlock) – Rechtsklick ohne Vorhersage, docs/27 §4
		if (args.length > 1) {
			Path items = Path.of(args[1]);
			int n = 0;
			try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(items)), StandardCharsets.UTF_8))) {
				w.println("# BlockItem -> Block, Minecraft 26.3 - erzeugt mit analysis/tools/ShapeExtractor.java aus server-26.3.jar (unverändert)");
				for (net.minecraft.world.item.Item item : BuiltInRegistries.ITEM) {
					if (item instanceof net.minecraft.world.item.BlockItem bi) {
						w.println("IB\t" + BuiltInRegistries.ITEM.getKey(item) + "\t" + BuiltInRegistries.BLOCK.getKey(bi.getBlock()));
						n++;
					}
				}
			}
			System.out.printf(Locale.ROOT, "blockItems=%d%n", n);
		}
		System.out.printf(Locale.ROOT, "lightStates=%d lightFaces=%d offGridFaceCoords=%d%n", lightLines.size(), lightFaces.size(), offGrid);
		System.out.printf(Locale.ROOT, "states=%d shapes=%d dynamicStates=%d positionDependent=%d blockFactors=%d%n", stateLines.size(), shapes.size(),
			dynamic, probeLines.size(), blockFactors.size());
	}

	static int offGrid;

	private static final Map<String, java.util.Set<String>> TAGS = new java.util.HashMap<>();

	/**
	 * Block-Tag aus den Datendateien des Server-JARs ({@code data/minecraft/tags/block/<name>.json}), Verweise {@code #…} rekursiv
	 * aufgelöst. Ohne geladenes Datenpaket sind {@code BlockTags} im Bootstrap nicht gebunden; die JSON-Dateien sind die Quelle.
	 */
	static java.util.Set<String> tag(final String name) {
		java.util.Set<String> cached = TAGS.get(name);
		if (cached != null) {
			return cached;
		}
		java.util.Set<String> out = new java.util.TreeSet<>();
		String res = "/data/minecraft/tags/block/" + name + ".json";
		try (java.io.InputStream in = ShapeExtractor.class.getResourceAsStream(res)) {
			if (in == null) {
				throw new IllegalStateException("missing tag " + res);
			}
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"(#?[a-z0-9_:/.]+)\"").matcher(json.substring(json.indexOf('[')));
			while (m.find()) {
				String v = m.group(1);
				if (v.startsWith("#")) {
					out.addAll(tag(v.substring(v.indexOf(':') + 1)));
				} else {
					out.add(v);
				}
			}
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		TAGS.put(name, out);
		return out;
	}

	/**
	 * Seitenform ({@code getFaceOcclusionShape}) als 16×16-Bedeckung der Seitenfläche, 64 Hex-Zeichen (Bit u + 16·v gesetzt =
	 * Zellmitte bedeckt). {@code Shapes#faceShapeOccludes(a, b)} = Vereinigung bedeckt die ganze Fläche = (a | b) voll. Exakt,
	 * solange alle Koordinaten auf dem 1/16-Raster liegen (gezählt in {@link #offGrid}).
	 */
	private static String faceMask(final VoxelShape face, final net.minecraft.core.Direction d) {
		java.math.BigInteger bits = java.math.BigInteger.ZERO;
		if (!face.isEmpty()) {
			for (AABB b : face.toAabbs()) {
				for (double c : new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ}) {
					if (Math.abs(c * 16 - Math.rint(c * 16)) > 1e-9) {
						offGrid++;
					}
				}
			}
			for (int v = 0; v < 16; v++) {
				for (int u = 0; u < 16; u++) {
					double pu = (u + 0.5) / 16, pv = (v + 0.5) / 16;
					// Seitenachse: Mitte (getFaceShape liefert eine über die ganze Achse ausgedehnte Scheibe)
					double x, y, z;
					switch (d.getAxis()) {
						case X -> { x = 0.5; y = pv; z = pu; }
						case Y -> { x = pu; y = 0.5; z = pv; }
						default -> { x = pu; y = pv; z = 0.5; }
					}
					for (AABB b : face.toAabbs()) {
						if (x > b.minX && x < b.maxX && y > b.minY && y < b.maxY && z > b.minZ && z < b.maxZ) {
							bits = bits.setBit(u + 16 * v);
							break;
						}
					}
				}
			}
		}
		String hex = bits.toString(16);
		return "0".repeat(64 - hex.length()) + hex;
	}

	private static final Map<Class<?>, String> USE_FLAGS = new java.util.HashMap<>();

	/**
	 * Eigene Rechtsklick-Logik der Blockklasse (M7, docs/27 §4): {@code W} = überschreibt useWithoutItem, {@code I} = überschreibt
	 * useItemOn, {@code -} = keine (Standard PASS bzw. TRY_WITH_EMPTY_HAND aus BlockBehaviour).
	 */
	private static String useFlags(final Block block) {
		return USE_FLAGS.computeIfAbsent(block.getClass(), c -> {
			String flags = (declares(c, "useWithoutItem") ? "W" : "") + (declares(c, "useItemOn") ? "I" : "");
			return flags.isEmpty() ? "-" : flags;
		});
	}

	private static boolean declares(final Class<?> c, final String method) {
		for (Class<?> k = c; k != null && k != net.minecraft.world.level.block.state.BlockBehaviour.class; k = k.getSuperclass()) {
			for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
				if (m.getName().equals(method) && !m.isBridge() && !m.isSynthetic()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Offset-Blöcke (M7-Korrektur, docs/27 §2): Index der offset-freien Grundform, wenn die Form an jeder Probe-Position
	 * Grundform + getOffset(pos) (inkl. y bei OffsetType.XYZ) ist; −1, wenn sie leer ist oder sich nicht mit der Position
	 * ändert (Offset nur im Modell, z. B. TallGrassBlock#getShape); sonst Abbruch.
	 */
	private static int offsetBase(final BlockState state, final VoxelShape atZero, final java.util.function.Function<BlockPos, VoxelShape> shapeAt,
		final Map<String, Integer> shapes) {
		if (atZero.isEmpty()) {
			return -1;
		}
		net.minecraft.world.phys.Vec3 off0 = state.getOffset(BlockPos.ZERO);
		VoxelShape base = atZero.move(-off0.x, -off0.y, -off0.z);
		boolean isStatic = true;
		boolean isOffset = true;
		for (BlockPos p : PROBES) {
			String at = encode(shapeAt.apply(p));
			net.minecraft.world.phys.Vec3 o = state.getOffset(p);
			isStatic &= at.equals(encode(atZero));
			isOffset &= at.equals(encode(base.move(o.x, o.y, o.z)));
		}
		if (isOffset) {
			return shapes.computeIfAbsent(encode(base), k -> shapes.size());
		}
		if (isStatic) {
			return -1;
		}
		throw new IllegalStateException("offset shape of " + state + " is neither static nor base + offset");
	}

	/** Boxen, Tab, Y-Gitterkoordinaten ({@code VoxelShape#getCoords(Y)}, Grundlage der Step-up-Kandidaten in {@code Entity#collide}). */
	private static String encode(final VoxelShape shape) {
		StringBuilder sb = new StringBuilder();
		StringBuilder ys = new StringBuilder();
		if (!shape.isEmpty()) {
			for (double y : shape.getCoords(net.minecraft.core.Direction.Axis.Y)) {
				if (!ys.isEmpty()) {
					ys.append(',');
				}
				ys.append(num(y));
			}
		}
		for (AABB b : shape.toAabbs()) {
			if (!sb.isEmpty()) {
				sb.append(';');
			}
			sb.append(num(b.minX)).append(',').append(num(b.minY)).append(',').append(num(b.minZ)).append(',')
				.append(num(b.maxX)).append(',').append(num(b.maxY)).append(',').append(num(b.maxZ));
		}
		return sb + "	" + ys;
	}

	private static String num(final double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
	}
}
