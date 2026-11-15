package dev.mcresearch.protocolclient.view;

import dev.mcresearch.protocolclient.physics.BlockMovementFacts;
import dev.mcresearch.protocolclient.physics.BlockView;
import dev.mcresearch.protocolclient.physics.FluidModel;
import dev.mcresearch.protocolclient.physics.ShapeRegistry;
import dev.mcresearch.protocolclient.physics.Vec3d;
import dev.mcresearch.protocolclient.view.env.BiomeColors;
import dev.mcresearch.protocolclient.view.env.EnvironmentData;
import dev.mcresearch.protocolclient.world.SectionSnapshot;
import java.util.List;

/**
 * Baut aus einem {@link SectionSnapshot} die Vertexdaten einer 16³-Sektion (docs/25 §4, M12: docs/47). Reine CPU-Arbeit
 * ohne OpenGL, läuft auf Mesh-Threads.
 *
 * <ul>
 *   <li><b>Schichten</b> wie {@code ChunkSectionLayer}: undurchsichtig, ausgestanzt (Alphatest 0,5), durchscheinend (Blending,
 *       Tiefe wird geschrieben, Flächen werden vor dem Zeichnen nach Abstand sortiert, docs/48 §3).</li>
 *   <li><b>Sichtbarkeit</b> einer Fläche mit {@code cullface} wie {@code Block#shouldRenderFace}: Seitenformen beider Blöcke,
 *       {@code skipRendering}-Regeln (Glas an Glas, Gitter, Mangrovenwurzeln).</li>
 *   <li><b>Licht</b> ({@link BlockLighter}): Ambient Occlusion mit weichem Licht, wenn eingeschaltet, das Modell AO zulässt und der
 *       Block nicht selbst leuchtet; sonst flach. Licht als Vertexattribut, die Helligkeitskurve wendet die Lightmap an.</li>
 *   <li><b>Tönung</b> ({@link TintTable}): Biomfarbe mit Mischradius (Mittel über (2r+1)² Spalten in gleicher Höhe) oder
 *       Konstante.</li>
 *   <li><b>Fluide</b>: eigene Geometrie mit Eckhöhen, Strömungsrichtung und Seitenflächen; Wasser in der Biom-Wasserfarbe.</li>
 *   <li>Positionen relativ zum Sektionsursprung (0…16), damit {@code float} auch bei großen Weltkoordinaten genau bleibt.</li>
 * </ul>
 */
public final class SectionMesher {
	/** Ergebnis: undurchsichtig, ausgestanzt, durchscheinend (je 6 Vertices pro Fläche, unsortiert). */
	public record Result(int[] solid, int[] cutout, int[] translucent, int quadsEmitted, int quadsCulled, int debugBlocks, int aoQuads) {
		public boolean isEmpty() {
			return this.solid.length == 0 && this.cutout.length == 0 && this.translucent.length == 0;
		}

		/** Kompatibilität: undurchsichtig + ausgestanzt. */
		public int[] opaque() {
			int[] o = new int[this.solid.length + this.cutout.length];
			System.arraycopy(this.solid, 0, o, 0, this.solid.length);
			System.arraycopy(this.cutout, 0, o, this.solid.length, this.cutout.length);
			return o;
		}
	}

	/** Gemeinsame, unveränderliche Eingaben aller Mesh-Aufträge einer Welt. */
	public record Context(BlockModelProvider models, BlockRenderFacts facts, TintTable tints, BiomeColors biomeColors, EnvironmentData env,
		List<String> biomeNames, boolean ambientOcclusion, int biomeBlendRadius, float[] cardinal, ShapeRegistry shapes) {
		/** Farbmodus/Tests: ohne Biomdaten, ohne AO. */
		public static Context simple(final BlockModelProvider models) {
			return new Context(models, null, null, null, null, List.of(), false, 0, BlockLighter.CARDINAL_DEFAULT, null);
		}
	}

	/** Vorgabe der Helligkeitskurve im Farbmodus (ohne Lightmap): Vanilla-Kurve bei Umgebungslicht 0. */
	private static final float[] LIGHT_CURVE = new float[16];

	static {
		for (int l = 0; l < 16; l++) {
			float f = l / 15f;
			LIGHT_CURVE[l] = f / (4f - 3f * f);
		}
	}

	private SectionMesher() {
	}

	/** M6–M11-Signatur (Farbmodus, Tests). */
	public static Result build(final SectionSnapshot snap, final BlockModelProvider models) {
		return build(snap, Context.simple(models));
	}

	public static Result build(final SectionSnapshot snap, final Context ctx) {
		MeshBuilder solid = new MeshBuilder(2048, 1 << 20);
		MeshBuilder cutout = new MeshBuilder(256, 1 << 20);
		MeshBuilder translucent = new MeshBuilder(64, 1 << 20);
		BlockModelProvider models = ctx.models();
		boolean vanilla = ctx.facts() != null;
		BlockLighter lighter = vanilla ? new BlockLighter(snap, ctx.facts(), ctx.cardinal()) : null;
		BlockLighter.Out lit = new BlockLighter.Out();
		FluidModel fluids = new FluidModel(snapshotView(snap), BlockMovementFacts.get());
		int emitted = 0;
		int culled = 0;
		int debug = 0;
		int ao = 0;
		float[] p = new float[12];
		int[] tintCache = new int[8];
		for (int ly = 0; ly < 16; ly++) {
			for (int lz = 0; lz < 16; lz++) {
				for (int lx = 0; lx < 16; lx++) {
					int state = snap.state(lx, ly, lz);
					if (state <= 0) {
						continue;
					}
					if (vanilla) {
						BlockMovementFacts.Fluid fl = BlockMovementFacts.get().fluid(state);
						if (!fl.isEmpty()) {
							emitted += fluid(snap, ctx, fluids, lighter, lx, ly, lz, state, fl, fl.type() == BlockMovementFacts.FLUID_WATER ? translucent : solid);
						}
					}
					RenderModel model = models.model(state);
					if (model.quads().isEmpty()) {
						continue;
					}
					if (model.debug()) {
						debug++;
					}
					java.util.Arrays.fill(tintCache, Integer.MIN_VALUE);
					float[] off = ctx.shapes() == null ? null : ctx.shapes().modelOffset(state, snap.originX() + lx, snap.originZ() + lz);
					boolean useAo = vanilla && ctx.ambientOcclusion() && ctx.facts().lightEmission(state) == 0 && model.ambientOcclusion();
					int faceCache = 0;
					int faceKnown = 0;
					for (RenderQuad q : model.quads()) {
						Face cf = q.cullFace();
						if (cf != null) {
							int bit = 1 << cf.ordinal();
							if ((faceKnown & bit) == 0) {
								faceKnown |= bit;
								if (shouldRender(snap, ctx, models, model, state, lx, ly, lz, cf)) {
									faceCache |= bit;
								}
							}
							if ((faceCache & bit) == 0) {
								culled++;
								continue;
							}
						}
						int[] colors = lit.colors;
						int[] lights = lit.lights;
						if (vanilla) {
							if (useAo) {
								lighter.ambientOcclusion(state, lx, ly, lz, q, lit);
								ao++;
							} else {
								lighter.flat(state, lx, ly, lz, q, lit);
							}
						} else {
							// Farbmodus: flach, Lichtkurve in der Farbe
							int light = snap.light(lx + (cf == null ? 0 : cf.dx), ly + (cf == null ? 0 : cf.dy), lz + (cf == null ? 0 : cf.dz));
							float bright = LIGHT_CURVE[Math.max(0, Math.min(15, light))] * 0.92f + 0.08f;
							int c = scale(q.color(), bright * BlockLighter.CARDINAL_DEFAULT[q.shadeFace().ordinal()]);
							for (int k = 0; k < 4; k++) {
								colors[k] = c;
								lights[k] = MeshBuilder.FULL_BRIGHT;
							}
						}
						int tint = 0xFFFFFF;
						if (q.tintIndex() >= 0) {
							if (vanilla) {
								int ti = Math.min(7, q.tintIndex());
								if (tintCache[ti] == Integer.MIN_VALUE) {
									tintCache[ti] = tint(snap, ctx, state, q.tintIndex(), lx, ly, lz);
								}
								tint = tintCache[ti];
							} else {
								tint = models.tint(state, q.tintIndex());
							}
						}
						int[] outColors = new int[4];
						for (int k = 0; k < 4; k++) {
							int c = vanilla ? multiply(colors[k], tint) : q.tintIndex() >= 0 ? multiply(colors[k], tint) : colors[k];
							// Basisfarbe der Fläche (Farbmodus: Blockfarbe; Texturmodus: weiß mit Alpha)
							outColors[k] = vanilla ? withAlpha(c, q.color() >>> 24) : c;
						}
						float[] qp = q.positions();
						for (int k = 0; k < 4; k++) {
							p[k * 3] = qp[k * 3] + lx + (off == null ? 0 : off[0]);
							p[k * 3 + 1] = qp[k * 3 + 1] + ly + (off == null ? 0 : off[1]);
							p[k * 3 + 2] = qp[k * 3 + 2] + lz + (off == null ? 0 : off[2]);
						}
						MeshBuilder target = switch (q.layer()) {
							case SOLID -> solid;
							case CUTOUT -> cutout;
							case TRANSLUCENT -> translucent;
						};
						target.quad(p, q.uvs(), outColors, lights.clone(), vanilla);
						emitted++;
					}
				}
			}
		}
		return new Result(solid.toArray(), cutout.toArray(), translucent.toArray(), emitted, culled, debug, ao);
	}

	/** Blickverdeckung einer Seite ({@code Block#shouldRenderFace}; Farbmodus: Modellabdeckung wie bis M11). */
	private static boolean shouldRender(final SectionSnapshot snap, final Context ctx, final BlockModelProvider models, final RenderModel model,
		final int state, final int lx, final int ly, final int lz, final Face cf) {
		int n = snap.state(lx + cf.dx, ly + cf.dy, lz + cf.dz);
		if (ctx.facts() == null) {
			if (n > 0) {
				RenderModel nm = models.model(n);
				return !(nm.occludes(cf.opposite()) || model.cullGroup() != 0 && nm.cullGroup() == model.cullGroup());
			}
			return true;
		}
		if (n < 0) {
			return true; // Nachbarchunk nicht geladen: wie Luft
		}
		BlockRenderFacts f = ctx.facts();
		int occluder = f.faceShape(n, cf.opposite());
		if (occluder == BlockRenderFacts.FACE_BLOCK) {
			return false;
		}
		if (skipRendering(f, state, n, cf)) {
			return false;
		}
		if (occluder == BlockRenderFacts.FACE_EMPTY) {
			return true;
		}
		int self = f.faceShape(state, cf);
		if (self == BlockRenderFacts.FACE_EMPTY) {
			return true;
		}
		return f.faceVisibleAgainst(self, occluder);
	}

	/** {@code BlockBehaviour#skipRendering} der Blockklassen (docs/47 §2.3). */
	static boolean skipRendering(final BlockRenderFacts f, final int state, final int neighbour, final Face dir) {
		BlockRenderFacts.Skip skip = f.skip(state);
		dev.mcresearch.protocolclient.world.BlockRegistry reg = dev.mcresearch.protocolclient.world.BlockRegistry.get();
		return switch (skip) {
			case NONE, LEAVES -> false; // Laub: cutoutLeaves (Vorgabe an) → keine Auslassung
			case SAME_BLOCK -> reg.state(state).name().equals(reg.state(neighbour).name());
			case MANGROVE_ROOTS -> dir.dy != 0 && reg.state(neighbour).name().equals("minecraft:mangrove_roots");
			case LIQUID -> false; // Flüssigkeitsblöcke haben kein Blockmodell (Fluidgeometrie getrennt)
			case BARS, BARS_TAGGED -> {
				boolean same = reg.state(state).name().equals(reg.state(neighbour).name());
				BlockRenderFacts.Skip ns = f.skip(neighbour);
				String opp = dir.opposite().jsonName;
				boolean tagged = skip == BlockRenderFacts.Skip.BARS_TAGGED && ns == BlockRenderFacts.Skip.BARS_TAGGED
					&& reg.state(neighbour).properties().containsKey(opp);
				if (same || tagged) {
					if (dir.dy != 0) {
						yield true;
					}
					yield "true".equals(reg.state(state).properties().get(dir.jsonName)) && "true".equals(reg.state(neighbour).properties().get(opp));
				}
				yield false;
			}
		};
	}

	// ----- Tönung -----

	private static int tint(final SectionSnapshot snap, final Context ctx, final int state, final int tintIndex, final int lx, final int ly,
		final int lz) {
		TintTable.Source src = ctx.tints().source(state, tintIndex);
		return switch (src.kind()) {
			case NONE -> 0xFFFFFF;
			case CONSTANT -> src.rgb();
			case GRASS, FOLIAGE, DRY_FOLIAGE, WATER -> blend(snap, ctx, src.kind(), lx, ly, lz);
			case GRASS_BELOW -> blend(snap, ctx, TintTable.Kind.GRASS, lx, ly - 1, lz);
		};
	}

	/** {@code ClientLevel#calculateBlockTint}: Mittel der Biomfarben über (2r+1)² Spalten in gleicher Höhe (ganzzahlig). */
	static int blend(final SectionSnapshot snap, final Context ctx, final TintTable.Kind kind, final int lx, final int ly, final int lz) {
		int r = Math.min(SectionSnapshot.MARGIN, Math.max(0, ctx.biomeBlendRadius()));
		int count = (2 * r + 1) * (2 * r + 1);
		int tr = 0;
		int tg = 0;
		int tb = 0;
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				int c = biomeColor(snap, ctx, kind, lx + dx, ly, lz + dz);
				tr += c >> 16 & 255;
				tg += c >> 8 & 255;
				tb += c & 255;
			}
		}
		return (tr / count) << 16 | (tg / count) << 8 | tb / count;
	}

	private static int biomeColor(final SectionSnapshot snap, final Context ctx, final TintTable.Kind kind, final int lx, final int ly, final int lz) {
		int id = SectionSnapshot.inside(lx, ly, lz) ? snap.biome(lx, ly, lz) : 0;
		String name = id >= 0 && id < ctx.biomeNames().size() ? ctx.biomeNames().get(id) : "minecraft:plains";
		EnvironmentData.BiomeData b = ctx.env().biome(name);
		if (b == null) {
			b = ctx.env().biome("minecraft:plains");
			if (b == null) {
				return 0xFFFFFF;
			}
		}
		return switch (kind) {
			case GRASS -> ctx.biomeColors().grass(b, snap.originX() + lx, snap.originZ() + lz);
			case FOLIAGE -> ctx.biomeColors().foliage(b);
			case DRY_FOLIAGE -> ctx.biomeColors().dryFoliage(b);
			case WATER -> ctx.biomeColors().water(b);
			default -> 0xFFFFFF;
		};
	}

	// ----- Fluide -----

	private static BlockView snapshotView(final SectionSnapshot snap) {
		return (x, y, z) -> SectionSnapshot.inside(x, y, z) ? snap.state(x, y, z) : BlockView.UNLOADED;
	}

	/** {@code FluidRenderer#getHeight}: gleiche Fluidart → 1 bei gleichem Fluid darüber, sonst Menge/9; sonst 0 (nicht fest) / −1 (fest). */
	private static float fluidHeight(final Context ctx, final FluidModel fm, final int type, final int x, final int y, final int z) {
		int s = fm.stateAt(x, y, z);
		BlockMovementFacts.Fluid f = s == BlockView.UNLOADED ? BlockMovementFacts.Fluid.EMPTY : BlockMovementFacts.get().fluid(s);
		if (f.type() == type) {
			int above = fm.stateAt(x, y + 1, z);
			BlockMovementFacts.Fluid fa = above == BlockView.UNLOADED ? BlockMovementFacts.Fluid.EMPTY : BlockMovementFacts.get().fluid(above);
			return fa.type() == type ? 1.0f : f.ownHeight();
		}
		return s != BlockView.UNLOADED && ctx.facts().solid(s) ? -1.0f : 0.0f;
	}

	private static void addWeighted(final float[] w, final float h) {
		if (h >= 0.8f) {
			w[0] += h * 10.0f;
			w[1] += 10.0f;
		} else if (h >= 0.0f) {
			w[0] += h;
			w[1]++;
		}
	}

	private static float averageHeight(final Context ctx, final FluidModel fm, final int type, final float self, final float h2, final float h1,
		final int cx, final int cy, final int cz) {
		if (h1 >= 1.0f || h2 >= 1.0f) {
			return 1.0f;
		}
		float[] w = new float[2];
		if (h1 > 0.0f || h2 > 0.0f) {
			float corner = fluidHeight(ctx, fm, type, cx, cy, cz);
			if (corner >= 1.0f) {
				return 1.0f;
			}
			addWeighted(w, corner);
		}
		addWeighted(w, self);
		addWeighted(w, h1);
		addWeighted(w, h2);
		return w[0] / w[1];
	}

	/** {@code FluidRenderer#isFaceOccludedByState} mit der Höhe der Fluidfläche (voll, Teil, leer). */
	private static boolean faceOccludedBy(final Context ctx, final Face dir, final float height, final int neighbour) {
		if (neighbour < 0) {
			return false;
		}
		int occ = ctx.facts().faceShape(neighbour, dir.opposite());
		if (occ == BlockRenderFacts.FACE_EMPTY) {
			return false;
		}
		if (occ == BlockRenderFacts.FACE_BLOCK) {
			return dir != Face.UP || height == 1.0f;
		}
		// Teilform: verdeckt, wenn die Maske die Fluidfläche (0…height) ganz abdeckt
		long[] m = ctx.facts().mask(occ);
		int rows = dir.dy != 0 ? 16 : Math.max(0, Math.min(16, (int) Math.ceil(height * 16)));
		for (int v = 0; v < 16; v++) {
			for (int u = 0; u < 16; u++) {
				// Seitenflächen: v = Höhe (Masken-Achsen wie RenderFactsExtractor); Deckel/Boden: ganze Fläche
				boolean needed = dir.dy != 0 || v < rows;
				int bit = u + 16 * v;
				if (needed && (m[bit >> 6] >>> (bit & 63) & 1L) == 0) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean sameFluid(final int type, final int state) {
		return state > 0 && BlockMovementFacts.get().fluid(state).type() == type;
	}

	/** Fluidgeometrie einer Zelle ({@code FluidRenderer#tesselate}, eigene Umsetzung). Rückgabe: Anzahl Flächen. */
	private static int fluid(final SectionSnapshot snap, final Context ctx, final FluidModel fm, final BlockLighter lighter, final int x, final int y,
		final int z, final int state, final BlockMovementFacts.Fluid fl, final MeshBuilder out) {
		BlockModelProvider models = ctx.models();
		if (!(models instanceof ResourceModels rm)) {
			return 0;
		}
		int type = fl.type();
		boolean water = type == BlockMovementFacts.FLUID_WATER;
		int sDown = snap.state(x, y - 1, z);
		int sUp = snap.state(x, y + 1, z);
		int sN = snap.state(x, y, z - 1);
		int sS = snap.state(x, y, z + 1);
		int sW = snap.state(x - 1, y, z);
		int sE = snap.state(x + 1, y, z);
		boolean renderUp = !sameFluid(type, sUp);
		boolean renderDown = !sameFluid(type, sDown) && !selfOccluded(ctx, state, Face.DOWN) && !faceOccludedBy(ctx, Face.DOWN, 0.8888889f, sDown);
		boolean renderN = !sameFluid(type, sN) && !selfOccluded(ctx, state, Face.NORTH);
		boolean renderS = !sameFluid(type, sS) && !selfOccluded(ctx, state, Face.SOUTH);
		boolean renderW = !sameFluid(type, sW) && !selfOccluded(ctx, state, Face.WEST);
		boolean renderE = !sameFluid(type, sE) && !selfOccluded(ctx, state, Face.EAST);
		if (!(renderUp || renderDown || renderN || renderS || renderW || renderE)) {
			return 0;
		}
		ResourceModels.FluidSprites fs = rm.fluidSprites(water);
		int tint = water ? blend(snap, ctx, TintTable.Kind.WATER, x, y, z) : 0xFFFFFF;
		float[] card = ctx.cardinal();
		float hSelf = fluidHeight(ctx, fm, type, x, y, z);
		float hNE;
		float hNW;
		float hSE;
		float hSW;
		if (hSelf >= 1.0f) {
			hNE = hNW = hSE = hSW = 1.0f;
		} else {
			float hN = fluidHeight(ctx, fm, type, x, y, z - 1);
			float hS = fluidHeight(ctx, fm, type, x, y, z + 1);
			float hE = fluidHeight(ctx, fm, type, x + 1, y, z);
			float hW = fluidHeight(ctx, fm, type, x - 1, y, z);
			hNE = averageHeight(ctx, fm, type, hSelf, hN, hE, x + 1, y, z - 1);
			hNW = averageHeight(ctx, fm, type, hSelf, hN, hW, x - 1, y, z - 1);
			hSE = averageHeight(ctx, fm, type, hSelf, hS, hE, x + 1, y, z + 1);
			hSW = averageHeight(ctx, fm, type, hSelf, hS, hW, x - 1, y, z + 1);
		}
		float bottomOffs = renderDown ? 0.001f : 0.0f;
		int faces = 0;
		int alpha = 255;
		if (renderUp && !faceOccludedBy(ctx, Face.UP, Math.min(Math.min(hNW, hSW), Math.min(hSE, hNE)), sUp)) {
			hNW -= 0.001f;
			hSW -= 0.001f;
			hSE -= 0.001f;
			hNE -= 0.001f;
			Vec3d flow = fm.flow(x, y, z);
			float u00;
			float u01;
			float u10;
			float u11;
			float v00;
			float v01;
			float v10;
			float v11;
			if (flow.x() == 0.0 && flow.z() == 0.0) {
				TextureAtlas.Sprite st = fs.still();
				u00 = st.u0();
				v00 = st.v0();
				u01 = u00;
				v01 = st.v1();
				u10 = st.u1();
				v10 = v01;
				u11 = u10;
				v11 = v00;
			} else {
				float angle = (float) Math.atan2(flow.z(), flow.x()) - (float) (Math.PI / 2);
				float s = (float) Math.sin(angle) * 0.25f;
				float c = (float) Math.cos(angle) * 0.25f;
				TextureAtlas.Sprite fw = fs.flowing();
				u00 = fw.u(16 * (0.5f + (-c - s)));
				v00 = fw.v(16 * (0.5f + (-c + s)));
				u01 = fw.u(16 * (0.5f + (-c + s)));
				v01 = fw.v(16 * (0.5f + (c + s)));
				u10 = fw.u(16 * (0.5f + (c + s)));
				v10 = fw.v(16 * (0.5f + (c - s)));
				u11 = fw.u(16 * (0.5f + (c - s)));
				v11 = fw.v(16 * (0.5f + (-c - s)));
			}
			int lc = fluidLight(lighter, state, x, y, z);
			int color = withAlpha(BlockLighter.scaleRgb(0xFF000000 | rgbToRgba(tint), card[Face.UP.ordinal()]), alpha);
			boolean back = backwardUpFace(snap, type, x, y + 1, z, ctx);
			fluidFace(out, new float[]{x, y + hNW, z, x, y + hSW, z + 1, x + 1, y + hSE, z + 1, x + 1, y + hNE, z},
				new float[]{u00, v00, u01, v01, u10, v10, u11, v11}, color, lc, back);
			faces++;
		}
		if (renderDown) {
			TextureAtlas.Sprite st = fs.still();
			int lc = fluidLight(lighter, state, x, y - 1, z);
			int color = withAlpha(BlockLighter.scaleRgb(0xFF000000 | rgbToRgba(tint), card[Face.DOWN.ordinal()]), alpha);
			fluidFace(out, new float[]{x, y + bottomOffs, z, x + 1, y + bottomOffs, z, x + 1, y + bottomOffs, z + 1, x, y + bottomOffs, z + 1},
				new float[]{st.u0(), st.v0(), st.u1(), st.v0(), st.u1(), st.v1(), st.u0(), st.v1()}, color, lc, false);
			faces++;
		}
		int sideLight = fluidLight(lighter, state, x, y, z);
		Face[] horizontal = {Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST};
		for (Face f : horizontal) {
			float h0;
			float h1;
			float x0;
			float z0;
			float x1;
			float z1;
			boolean render;
			int neighbour;
			switch (f) {
				case NORTH -> {
					h0 = hNW;
					h1 = hNE;
					x0 = x;
					x1 = x + 1.0f;
					z0 = z + 0.001f;
					z1 = z + 0.001f;
					render = renderN;
					neighbour = sN;
				}
				case SOUTH -> {
					h0 = hSE;
					h1 = hSW;
					x0 = x + 1.0f;
					x1 = x;
					z0 = z + 1.0f - 0.001f;
					z1 = z + 1.0f - 0.001f;
					render = renderS;
					neighbour = sS;
				}
				case WEST -> {
					h0 = hSW;
					h1 = hNW;
					x0 = x + 0.001f;
					x1 = x + 0.001f;
					z0 = z + 1.0f;
					z1 = z;
					render = renderW;
					neighbour = sW;
				}
				default -> {
					h0 = hNE;
					h1 = hSE;
					x0 = x + 1.0f - 0.001f;
					x1 = x + 1.0f - 0.001f;
					z0 = z;
					z1 = z + 1.0f;
					render = renderE;
					neighbour = sE;
				}
			}
			if (render && !faceOccludedBy(ctx, f, Math.max(h0, h1), neighbour)) {
				TextureAtlas.Sprite sp = fs.flowing();
				boolean overlay = false;
				if (water && fs.overlay() != null && neighbour > 0 && rm.overlayNeighbour(neighbour)) {
					sp = fs.overlay();
					overlay = true;
				}
				float u0 = sp.u(0f);
				float u1 = sp.u(8f);
				float v01 = sp.v((1.0f - h0) * 8f);
				float v02 = sp.v((1.0f - h1) * 8f);
				float v1 = sp.v(8f);
				float side = f.dz != 0 ? card[Face.NORTH.ordinal()] : card[Face.WEST.ordinal()];
				int color = withAlpha(BlockLighter.scaleRgb(0xFF000000 | rgbToRgba(tint), card[Face.UP.ordinal()] * side), alpha);
				fluidFace(out, new float[]{x0, y + h0, z0, x1, y + h1, z1, x1, y + bottomOffs, z1, x0, y + bottomOffs, z0},
					new float[]{u0, v01, u1, v02, u1, v1, u0, v1}, color, sideLight, !overlay);
				faces++;
			}
		}
		return faces;
	}

	/** Seite durch den eigenen Block verdeckt ({@code isFaceOccludedBySelf}: z. B. Stufe mit Wasser). */
	private static boolean selfOccluded(final Context ctx, final int state, final Face dir) {
		return faceOccludedBy(ctx, dir.opposite(), 1.0f, state);
	}

	/** {@code FluidState#shouldRenderBackwardUpFace}: über der Oberfläche ein nicht-fluides, nicht solides Feld in 3×3. */
	private static boolean backwardUpFace(final SectionSnapshot snap, final int type, final int x, final int y, final int z, final Context ctx) {
		for (int ox = -1; ox <= 1; ox++) {
			for (int oz = -1; oz <= 1; oz++) {
				int s = SectionSnapshot.inside(x + ox, y, z + oz) ? snap.state(x + ox, y, z + oz) : 0;
				if (!sameFluid(type, s) && !ctx.facts().solidRender(Math.max(0, s))) {
					return true;
				}
			}
		}
		return false;
	}

	/** Fluidlicht: Maximum aus Zelle und Zelle darüber ({@code LightCoordsUtil#max}). */
	private static int fluidLight(final BlockLighter lighter, final int state, final int x, final int y, final int z) {
		int a = lighter.lightCoords(state, x, y, z);
		int b = lighter.lightCoords(0, x, y + 1, z);
		int block = Math.max(a >> 4 & 15, b >> 4 & 15);
		int sky = Math.max(a >> 20 & 15, b >> 20 & 15);
		return block << 4 | sky << 20;
	}

	/** Vier Ecken in Vanilla-Reihenfolge (Diagonale 0–2), optional Rückseite (0, 3, 2, 1). */
	private static void fluidFace(final MeshBuilder out, final float[] p, final float[] uv, final int color, final int light, final boolean back) {
		int[] c = {color, color, color, color};
		int[] l = {light, light, light, light};
		out.quad(p, uv, c, l, false);
		if (back) {
			float[] bp = {p[0], p[1], p[2], p[9], p[10], p[11], p[6], p[7], p[8], p[3], p[4], p[5]};
			float[] buv = {uv[0], uv[1], uv[6], uv[7], uv[4], uv[5], uv[2], uv[3]};
			out.quad(bp, buv, c, l, false);
		}
	}

	// ----- Farbe -----

	private static int rgbToRgba(final int rgb) {
		return MeshBuilder.rgba(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, 255);
	}

	private static int withAlpha(final int rgba, final int alpha) {
		return rgba & 0x00FFFFFF | (alpha & 255) << 24;
	}

	/** RGBA-Farbe × Tönung (RGB 0xRRGGBB), ganzzahlig wie {@code ARGB#multiply}. */
	static int multiply(final int rgba, final int rgb) {
		int r = (rgba & 255) * (rgb >> 16 & 255) / 255;
		int g = (rgba >> 8 & 255) * (rgb >> 8 & 255) / 255;
		int b = (rgba >> 16 & 255) * (rgb & 255) / 255;
		return MeshBuilder.rgba(r, g, b, rgba >>> 24);
	}

	static int scale(final int rgba, final float f) {
		int r = Math.round((rgba & 255) * f);
		int g = Math.round((rgba >> 8 & 255) * f);
		int b = Math.round((rgba >> 16 & 255) * f);
		return MeshBuilder.rgba(Math.min(255, r), Math.min(255, g), Math.min(255, b), rgba >>> 24);
	}
}
