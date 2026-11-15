package dev.mcresearch.protocolclient.view;

import dev.mcresearch.protocolclient.world.BlockRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Darstellungsfakten je Blockzustand (docs/47 §2) aus der Tabelle {@code render/blockfacts-777.txt.gz}, die das Analysewerkzeug
 * {@code RenderFactsExtractor} aus dem unveränderten Server-JAR erzeugt: {@code isSolidRender}, {@code canOcclude},
 * {@code emissiveRendering}, Lichtemission und -dämpfung, Schattenhelligkeit für AO, Verdeckungsform je Seite (16×16) und die
 * Regel von {@code skipRendering}. Thread-sicher (nur lesend nach dem Laden).
 */
public final class BlockRenderFacts {
	public static final String RESOURCE = "/render/blockfacts-777.txt.gz";
	/** Seitenform = {@code Shapes.block()} bzw. {@code Shapes.empty()}. */
	public static final int FACE_BLOCK = -1;
	public static final int FACE_EMPTY = -2;

	public enum Skip {
		NONE, SAME_BLOCK, BARS, BARS_TAGGED, MANGROVE_ROOTS, LIQUID, LEAVES
	}

	private static volatile BlockRenderFacts instance;

	private final byte[] flags;
	private final byte[] emission;
	private final byte[] dampening;
	private final float[] shade;
	private final int[][] faces;
	private final Skip[] skip;
	private final List<long[]> masks = new ArrayList<>();

	private static final int SOLID_RENDER = 1;
	private static final int CAN_OCCLUDE = 2;
	private static final int EMISSIVE = 4;
	private static final int FULL_COLLISION = 8;
	private static final int SOLID = 16;
	private static final int MOTION_BLOCKING = 32;

	private BlockRenderFacts(final int states) {
		this.flags = new byte[states];
		this.emission = new byte[states];
		this.dampening = new byte[states];
		this.shade = new float[states];
		this.faces = new int[states][];
		this.skip = new Skip[states];
	}

	public static BlockRenderFacts get() {
		BlockRenderFacts f = instance;
		if (f == null) {
			synchronized (BlockRenderFacts.class) {
				f = instance;
				if (f == null) {
					f = load(BlockRegistry.get());
					instance = f;
				}
			}
		}
		return f;
	}

	static BlockRenderFacts load(final BlockRegistry registry) {
		BlockRenderFacts f = new BlockRenderFacts(registry.size());
		java.util.Arrays.fill(f.shade, 1f);
		java.util.Arrays.fill(f.skip, Skip.NONE);
		int[] empty = {FACE_EMPTY, FACE_EMPTY, FACE_EMPTY, FACE_EMPTY, FACE_EMPTY, FACE_EMPTY};
		java.util.Arrays.fill(f.faces, empty);
		try (InputStream raw = BlockRenderFacts.class.getResourceAsStream(RESOURCE)) {
			if (raw == null) {
				throw new IllegalStateException("missing resource " + RESOURCE);
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));
			for (String line; (line = r.readLine()) != null; ) {
				if (line.startsWith("#") || line.isBlank()) {
					continue;
				}
				String[] t = line.split("\t");
				switch (t[0]) {
					case "M" -> {
						long[] bits = new long[4];
						String hex = t[2];
						for (int i = 0; i < 4; i++) {
							bits[3 - i] = Long.parseUnsignedLong(hex.substring(i * 16, i * 16 + 16), 16);
						}
						f.masks.add(Integer.parseInt(t[1]), bits);
					}
					case "V" -> {
						int id = Integer.parseInt(t[1]);
						if (id < 0 || id >= registry.size()) {
							continue;
						}
						int fl = 0;
						for (char c : t[2].toCharArray()) {
							fl |= switch (c) {
								case 'R' -> SOLID_RENDER;
								case 'C' -> CAN_OCCLUDE;
								case 'E' -> EMISSIVE;
								case 'F' -> FULL_COLLISION;
								case 'S' -> SOLID;
								case 'H' -> MOTION_BLOCKING;
								default -> 0;
							};
						}
						f.flags[id] = (byte) fl;
						f.emission[id] = (byte) Integer.parseInt(t[3]);
						f.dampening[id] = (byte) Integer.parseInt(t[4]);
						f.shade[id] = Float.parseFloat(t[5]);
						String[] fs = t[6].split(",");
						int[] faces = new int[6];
						for (int i = 0; i < 6; i++) {
							faces[i] = fs[i].equals("B") ? FACE_BLOCK : fs[i].equals("E") ? FACE_EMPTY : Integer.parseInt(fs[i]);
						}
						f.faces[id] = faces;
						f.skip[id] = switch (t[7]) {
							case "H" -> Skip.SAME_BLOCK;
							case "I" -> Skip.BARS;
							case "IT" -> Skip.BARS_TAGGED;
							case "R" -> Skip.MANGROVE_ROOTS;
							case "Q" -> Skip.LIQUID;
							case "L" -> Skip.LEAVES;
							default -> Skip.NONE;
						};
					}
					case "T" -> {
						// Tag bars: schon in der Regel IT enthalten
					}
					default -> throw new IllegalStateException("unknown line type " + t[0]);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return f;
	}

	private boolean valid(final int s) {
		return s >= 0 && s < this.flags.length;
	}

	public boolean solidRender(final int s) {
		return valid(s) && (this.flags[s] & SOLID_RENDER) != 0;
	}

	public boolean canOcclude(final int s) {
		return valid(s) && (this.flags[s] & CAN_OCCLUDE) != 0;
	}

	public boolean emissiveRendering(final int s) {
		return valid(s) && (this.flags[s] & EMISSIVE) != 0;
	}

	public boolean fullCollision(final int s) {
		return valid(s) && (this.flags[s] & FULL_COLLISION) != 0;
	}

	/** {@code BlockBehaviour#isSolid} (Altkennzeichen, u. a. für die Fluidhöhe an Nachbarn). */
	public boolean solid(final int s) {
		return valid(s) && (this.flags[s] & SOLID) != 0;
	}

	/** Heightmap MOTION_BLOCKING (Tag {@code blocks_motion_in_heightmap} oder Fluid) – Untergrenze des Niederschlags. */
	public boolean motionBlocking(final int s) {
		return valid(s) && (this.flags[s] & MOTION_BLOCKING) != 0;
	}

	public int lightEmission(final int s) {
		return valid(s) ? this.emission[s] : 0;
	}

	/** {@code isLightPermeable}: nicht solide gerendert oder ohne Lichtdämpfung. */
	public boolean lightPermeable(final int s) {
		return !valid(s) || !solidRender(s) || this.dampening[s] == 0;
	}

	/** {@code getShadeBrightness}: 0,2 für volle Kollisionsblöcke, sonst 1,0 (Sonderfälle laut Tabelle). */
	public float shadeBrightness(final int s) {
		return valid(s) ? this.shade[s] : 1f;
	}

	/** Seitenform-Index der Seite {@code face} ({@link #FACE_BLOCK}, {@link #FACE_EMPTY} oder Maskenindex). */
	public int faceShape(final int s, final Face face) {
		return valid(s) ? this.faces[s][vanillaIndex(face)] : FACE_EMPTY;
	}

	public Skip skip(final int s) {
		return valid(s) ? this.skip[s] : Skip.NONE;
	}

	/** Maske als 4 × 64 Bit (Bit u + 16·v). */
	public long[] mask(final int index) {
		return this.masks.get(index);
	}

	/** Direction-Reihenfolge (unten, oben, Nord, Süd, West, Ost) = Reihenfolge von {@link Face}. */
	static int vanillaIndex(final Face f) {
		return f.ordinal();
	}

	/** {@code Shapes.joinIsNotEmpty(self, occluder, ONLY_FIRST)}: Teile der eigenen Seite, die der Nachbar nicht verdeckt. */
	public boolean faceVisibleAgainst(final int selfShape, final int occluderShape) {
		long[] self = selfShape == FACE_BLOCK ? FULL : this.masks.get(selfShape);
		long[] occ = occluderShape == FACE_BLOCK ? FULL : occluderShape == FACE_EMPTY ? NONE : this.masks.get(occluderShape);
		for (int i = 0; i < 4; i++) {
			if ((self[i] & ~occ[i]) != 0) {
				return true;
			}
		}
		return false;
	}

	private static final long[] FULL = {-1L, -1L, -1L, -1L};
	private static final long[] NONE = {0L, 0L, 0L, 0L};
}
