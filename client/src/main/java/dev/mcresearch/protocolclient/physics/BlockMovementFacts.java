package dev.mcresearch.protocolclient.physics;

import dev.mcresearch.protocolclient.world.BlockRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Bewegungsfakten je Blockzustand (M11, docs/42 §7) aus der Faktentabelle {@code shapes-777.txt.gz}, Zeilen {@code M}, {@code R},
 * {@code Y}: Fluidzustand, tragfähige Seiten ({@code isFaceSturdy}), Tags (climbable, blocks_fluid_flow, suppresses_bounce,
 * erstickend, Eis), Prallfaktor und Blockklasse. Die Tabelle stammt aus dem unveränderten Server-JAR ({@code ShapeExtractor}).
 */
public final class BlockMovementFacts {
	public static final int FLUID_NONE = 0;
	public static final int FLUID_WATER = 1;
	public static final int FLUID_LAVA = 2;
	static final byte TAG_CLIMBABLE = 1;
	static final byte TAG_BLOCKS_FLUID_FLOW = 2;
	static final byte TAG_SUPPRESSES_BOUNCE = 4;
	static final byte TAG_SUFFOCATING = 8;
	static final byte TAG_ICE = 16;

	/** Fluidzustand eines Blockzustands ({@code FluidState}): Typ, Menge 1–8, Quelle, fallend. */
	public record Fluid(int type, int amount, boolean source, boolean falling) {
		public static final Fluid EMPTY = new Fluid(FLUID_NONE, 0, false, false);

		public boolean isEmpty() {
			return this.type == FLUID_NONE;
		}

		/** {@code FlowingFluid#getOwnHeight}: Menge / 9F. */
		public float ownHeight() {
			return this.amount / 9.0F;
		}
	}

	private static final BlockMovementFacts INSTANCE = load(BlockRegistry.get());

	private final BlockRegistry blocks;
	private final Fluid[] fluid;
	private final byte[] sturdy;
	private final byte[] tags;
	private final Map<String, Float> bounce;
	private final Map<String, String> blockClass;

	private BlockMovementFacts(final BlockRegistry blocks, final Fluid[] fluid, final byte[] sturdy, final byte[] tags, final Map<String, Float> bounce,
		final Map<String, String> blockClass) {
		this.blocks = blocks;
		this.fluid = fluid;
		this.sturdy = sturdy;
		this.tags = tags;
		this.bounce = bounce;
		this.blockClass = blockClass;
	}

	public static BlockMovementFacts get() {
		return INSTANCE;
	}

	static BlockMovementFacts load(final BlockRegistry blocks) {
		Fluid[] fluid = new Fluid[blocks.size()];
		byte[] sturdy = new byte[blocks.size()];
		byte[] tags = new byte[blocks.size()];
		Map<String, Float> bounce = new HashMap<>();
		Map<String, String> blockClass = new HashMap<>();
		int mLines = 0;
		try (InputStream raw = BlockMovementFacts.class.getResourceAsStream("/physics/shapes-777.txt.gz")) {
			if (raw == null) {
				throw new IllegalStateException("Missing /physics/shapes-777.txt.gz");
			}
			try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (line.startsWith("M\t")) {
						String[] p = line.split("\t", -1);
						int id = Integer.parseInt(p[1]);
						fluid[id] = parseFluid(p[2]);
						sturdy[id] = (byte) Integer.parseInt(p[3]);
						int t = 0;
						for (char ch : p[4].toCharArray()) {
							t |= switch (ch) {
								case 'C' -> TAG_CLIMBABLE;
								case 'B' -> TAG_BLOCKS_FLUID_FLOW;
								case 'N' -> TAG_SUPPRESSES_BOUNCE;
								case 'X' -> TAG_SUFFOCATING;
								case 'I' -> TAG_ICE;
								case '-' -> 0;
								default -> throw new IllegalStateException("unknown movement tag " + ch);
							};
						}
						tags[id] = (byte) t;
						mLines++;
					} else if (line.startsWith("R\t")) {
						String[] p = line.split("\t", -1);
						bounce.put(p[1], Float.parseFloat(p[2]));
					} else if (line.startsWith("Y\t")) {
						String[] p = line.split("\t", -1);
						blockClass.put(p[1], p[2]);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (mLines == 0) {
			throw new IllegalStateException("shape table lacks M11 movement facts (M lines) – regenerate with ShapeExtractor");
		}
		for (int i = 0; i < fluid.length; i++) {
			if (fluid[i] == null) {
				fluid[i] = Fluid.EMPTY;
			}
		}
		return new BlockMovementFacts(blocks, fluid, sturdy, tags, bounce, blockClass);
	}

	private static Fluid parseFluid(final String s) {
		if (s.equals("-")) {
			return Fluid.EMPTY;
		}
		int type = switch (s.charAt(0)) {
			case 'W' -> FLUID_WATER;
			case 'L' -> FLUID_LAVA;
			default -> throw new IllegalStateException("unknown fluid " + s);
		};
		int i = 1;
		while (i < s.length() && Character.isDigit(s.charAt(i))) {
			i++;
		}
		int amount = Integer.parseInt(s.substring(1, i));
		String flags = s.substring(i);
		return new Fluid(type, amount, flags.contains("S"), flags.contains("F"));
	}

	/** Fluid eines Zustands; ungeladen → leer. */
	public Fluid fluid(final int stateId) {
		return stateId < 0 || stateId >= this.fluid.length ? Fluid.EMPTY : this.fluid[stateId];
	}

	/** {@code BlockState#isFaceSturdy(…, direction)} mit {@code Direction.get3DDataValue}: 0 unten, 1 oben, 2 Norden, 3 Süden, 4 Westen, 5 Osten. */
	public boolean sturdy(final int stateId, final int direction3d) {
		return stateId >= 0 && stateId < this.sturdy.length && (this.sturdy[stateId] & 1 << direction3d) != 0;
	}

	private boolean tag(final int stateId, final byte tag) {
		return stateId >= 0 && stateId < this.tags.length && (this.tags[stateId] & tag) != 0;
	}

	public boolean climbable(final int stateId) {
		return tag(stateId, TAG_CLIMBABLE);
	}

	public boolean blocksFluidFlow(final int stateId) {
		return tag(stateId, TAG_BLOCKS_FLUID_FLOW);
	}

	public boolean suppressesBounce(final int stateId) {
		return tag(stateId, TAG_SUPPRESSES_BOUNCE);
	}

	public boolean suffocating(final int stateId) {
		return tag(stateId, TAG_SUFFOCATING);
	}

	public boolean ice(final int stateId) {
		return tag(stateId, TAG_ICE);
	}

	/** {@code Block#getBounceRestitution} des Blocks dieses Zustands (0 = kein Prallen). */
	public float bounceRestitution(final int stateId) {
		if (stateId < 0) {
			return 0.0F;
		}
		return this.bounce.getOrDefault(this.blocks.state(stateId).name(), 0.0F);
	}

	/** Blockklasse (z. B. {@code WebBlock}, {@code HoneyBlock}) für Block-Callbacks; leer, wenn unbekannt. */
	public String blockClass(final int stateId) {
		if (stateId < 0) {
			return "";
		}
		return this.blockClass.getOrDefault(this.blocks.state(stateId).name(), "");
	}
}
