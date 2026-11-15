import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MangroveRootsBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Analysewerkzeug M12 (docs/47 §2): Faktentabelle „Blockzustand → Darstellungseigenschaften“ aus dem unveränderten
 * Vanilla-Server-JAR, analog zu {@code ShapeExtractor}. Der Client enthält nur die erzeugte Tabelle, keinen Minecraft-Code.
 *
 * <pre>
 * java -cp "server/versions/26.3/server-26.3.jar;server/libraries/...jar" analysis/tools/RenderFactsExtractor.java &lt;blockfacts.txt.gz&gt;
 * </pre>
 *
 * Format (Tab-getrennt):
 * <pre>
 *   M  index  hex64            Seitenmaske 16×16 (Bit u + 16·v, Achsen wie ShapeExtractor#faceMask)
 *   V  stateId  flags  emission  dampening  shade  faces  skip
 *      flags: R isSolidRender, C canOcclude, E emissiveRendering, F Kollisionsform voller Block, S isSolid (Fluidhöhe),
 *             H Heightmap MOTION_BLOCKING (Tag blocks_motion_in_heightmap oder Fluid)
 *      shade: getShadeBrightness (leerer Getter, Ursprung) – 0.2 oder 1.0, Sonderfälle wie Glas 1.0
 *      faces: 6 Einträge (Direction-Reihenfolge unten, oben, Nord, Süd, West, Ost) – B = Shapes.block(), E = Shapes.empty(),
 *             sonst Maskenindex
 *      skip:  Regel von skipRendering – H gleiches Blockobjekt (HalfTransparentBlock, PowderSnow), I Gitter (IronBarsBlock),
 *             IT Gitter im Tag bars, R Mangrovenwurzeln (nur Y-Achse), Q Flüssigkeit, L Laub, - keine
 *   T  block  tag              darstellungsrelevante Tags (bars)
 * </pre>
 */
public final class RenderFactsExtractor {
	public static void main(final String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		Map<String, Integer> masks = new LinkedHashMap<>();
		StringBuilder states = new StringBuilder();
		Set<String> bars = ShapeExtractorTags.tag("bars");
		Set<String> motionInHeightmap = ShapeExtractorTags.tag("blocks_motion_in_heightmap");
		int count = 0;
		for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
			int id = Block.getId(state);
			StringBuilder flags = new StringBuilder();
			if (state.isSolidRender()) {
				flags.append('R');
			}
			if (state.canOcclude()) {
				flags.append('C');
			}
			if (state.emissiveRendering()) {
				flags.append('E');
			}
			if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
				flags.append('F');
			}
			if (state.isSolid()) {
				flags.append('S');
			}
			if (motionInHeightmap.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()) || !state.getFluidState().isEmpty()) {
				flags.append('H'); // Heightmap MOTION_BLOCKING (Wetter)
			}
			StringBuilder faces = new StringBuilder();
			for (Direction d : Direction.values()) {
				VoxelShape s = state.getFaceOcclusionShape(d);
				String f = s == Shapes.block() ? "B" : s == Shapes.empty() || s.isEmpty() ? "E"
					: Integer.toString(masks.computeIfAbsent(faceMask(s, d), k -> masks.size()));
				faces.append(faces.isEmpty() ? "" : ",").append(f);
			}
			Block b = state.getBlock();
			String key = BuiltInRegistries.BLOCK.getKey(b).toString();
			String skip = b instanceof HalfTransparentBlock || b instanceof PowderSnowBlock ? "H"
				: b instanceof IronBarsBlock ? (bars.contains(key) ? "IT" : "I")
				: b instanceof MangroveRootsBlock ? "R" : b instanceof LiquidBlock ? "Q" : b instanceof LeavesBlock ? "L" : "-";
			states.append("V\t").append(id).append('\t').append(flags.isEmpty() ? "-" : flags).append('\t').append(state.getLightEmission()).append('\t')
				.append(state.getLightDampening()).append('\t').append(state.getShadeBrightness(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)).append('\t')
				.append(faces).append('\t').append(skip).append('\n');
			count++;
		}
		Path out = Path.of(args[0]);
		Files.createDirectories(out.toAbsolutePath().getParent());
		try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(out)), StandardCharsets.UTF_8))) {
			w.println("# M12 render facts (RenderFactsExtractor, unmodified server " + SharedConstants.getCurrentVersion().name() + "), " + count + " states");
			for (Map.Entry<String, Integer> e : masks.entrySet()) {
				w.println("M\t" + e.getValue() + "\t" + e.getKey());
			}
			w.print(states);
			for (String t : bars) {
				w.println("T\t" + t + "\tbars");
			}
		}
		System.out.println(count + " states, " + masks.size() + " face masks -> " + out);
	}

	/** Seitenform als 16×16-Bedeckung (wie ShapeExtractor#faceMask): Zellmitten auf der Seitenebene, Seitenachse Mitte. */
	private static String faceMask(final VoxelShape face, final Direction d) {
		java.math.BigInteger bits = java.math.BigInteger.ZERO;
		for (int v = 0; v < 16; v++) {
			for (int u = 0; u < 16; u++) {
				double pu = (u + 0.5) / 16;
				double pv = (v + 0.5) / 16;
				double x;
				double y;
				double z;
				switch (d.getAxis()) {
					case X -> {
						x = 0.5;
						y = pv;
						z = pu;
					}
					case Y -> {
						x = pu;
						y = 0.5;
						z = pv;
					}
					default -> {
						x = pu;
						y = pv;
						z = 0.5;
					}
				}
				for (AABB b : face.toAabbs()) {
					if (x > b.minX && x < b.maxX && y > b.minY && y < b.maxY && z > b.minZ && z < b.maxZ) {
						bits = bits.setBit(u + 16 * v);
						break;
					}
				}
			}
		}
		String hex = bits.toString(16);
		return "0".repeat(64 - hex.length()) + hex;
	}

	/** Tags aus den JSON-Dateien des JARs (ohne Datenpaket sind BlockTags im Bootstrap nicht gebunden). */
	static final class ShapeExtractorTags {
		private static final Map<String, Set<String>> TAGS = new java.util.HashMap<>();

		static Set<String> tag(final String name) {
			Set<String> cached = TAGS.get(name);
			if (cached != null) {
				return cached;
			}
			Set<String> out = new java.util.TreeSet<>();
			String res = "/data/minecraft/tags/block/" + name + ".json";
			try (java.io.InputStream in = RenderFactsExtractor.class.getResourceAsStream(res)) {
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
	}
}
