package dev.mcresearch.protocolclient.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcresearch.protocolclient.physics.ShapeRegistry;
import dev.mcresearch.protocolclient.view.env.BiomeColors;
import dev.mcresearch.protocolclient.view.env.EnvironmentData;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.SectionSnapshot;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * M12 (docs/49 §6): Beleuchtung (Lightmap, AO, weiches Licht, Emission), Richtungsschattierung mit
 * {@code shade_direction_override}, Vanilla-Culling, Schichten, Fluidgeometrie, Biomtönung mit Mischung, Texturanimation und
 * Sortierung durchscheinender Flächen.
 */
class RenderingM12Test {
	private static final BlockRegistry REG = BlockRegistry.get();
	private static ResourceModels models;
	private static EnvironmentData env;
	private static final int STONE = REG.idOf("minecraft:stone", Map.of());
	private static final int GLASS = REG.idOf("minecraft:glass", Map.of());

	@BeforeAll
	static void load() throws Exception {
		Optional<Path> jar = ResourceModelsTest.localJar();
		assumeTrue(jar.isPresent(), "local Minecraft 26.3 client jar not found");
		models = ResourceModels.load(jar.get(), REG);
		env = EnvironmentData.load(jar.get());
	}

	// ----- Hilfen -----

	private static SectionSnapshot snap(final Map<int[], Integer> blocks, final int sky, final int block, final int biome) {
		int n = SectionSnapshot.SIZE;
		int[] states = new int[n * n * n];
		byte[] light = new byte[states.length];
		int[] biomes = new int[states.length];
		Arrays.fill(light, SectionSnapshot.pack(sky, block));
		Arrays.fill(biomes, biome);
		blocks.forEach((p, s) -> states[SectionSnapshot.index(p[0], p[1], p[2])] = s);
		return new SectionSnapshot(0, 0, 0, states, light, biomes);
	}

	private static Map<int[], Integer> blocks(final Object... posAndState) {
		Map<int[], Integer> m = new java.util.HashMap<>();
		for (int i = 0; i < posAndState.length; i += 2) {
			m.put((int[]) posAndState[i], (Integer) posAndState[i + 1]);
		}
		return m;
	}

	private static SectionMesher.Context ctx(final List<String> biomes) {
		return new SectionMesher.Context(models, BlockRenderFacts.get(), TintTable.get(), new BiomeColors(env), env, biomes, true, 2,
			BlockLighter.CARDINAL_DEFAULT, ShapeRegistry.get());
	}

	/** Vertices (je 7 ints) eines Ergebnisses. */
	private static int vertices(final int[] data) {
		return data.length / MeshBuilder.INTS_PER_VERTEX;
	}

	// ----- Lightmap -----

	@Test
	void lightmapFollowsTheVanillaCurve() {
		Lightmap.Params noon = Lightmap.Params.noon();
		float[] full = Lightmap.color(noon, 15, 15);
		float[] dark = Lightmap.color(noon, 0, 0);
		float[] torch = Lightmap.color(noon, 14, 0);
		assertEquals(1f, full[0], 1e-6);
		assertTrue(dark[0] < 0.1f, "no light → ambient only");
		assertTrue(torch[0] > torch[2], "block light is warm (tint #ffd88c)");
		Lightmap.Params night = new Lightmap.Params(0.24f, 1.4f, 0, 0, 0, 0.5f, noon.blockLightTint(), new float[]{0x7a / 255f, 0x7a / 255f, 1f},
			noon.ambientColor(), noon.nightVisionColor());
		float[] moon = Lightmap.color(night, 0, 15);
		assertTrue(moon[2] > moon[0], "night sky light is bluish");
		assertTrue(moon[0] < full[0] * 0.6f);
	}

	// ----- AO und Licht -----

	@Test
	void ambientOcclusionDarkensCornersNextToBlocks() {
		// Stein-Boden 3×3 auf y=4, ein Steinblock darüber an der Ostseite der Mitte
		Object[] list = new Object[20];
		int k = 0;
		for (int x = 4; x <= 6; x++) {
			for (int z = 4; z <= 6; z++) {
				list[k++] = new int[]{x, 4, z};
				list[k++] = STONE;
			}
		}
		list[k++] = new int[]{6, 5, 5};
		list[k] = STONE;
		SectionSnapshot s = snap(blocks(list), 15, 0, 0);
		BlockLighter lighter = new BlockLighter(s, BlockRenderFacts.get(), BlockLighter.CARDINAL_DEFAULT);
		RenderQuad top = models.model(STONE).quads().stream().filter(q -> q.direction() == Face.UP).findFirst().orElseThrow();
		BlockLighter.Out out = new BlockLighter.Out();
		lighter.ambientOcclusion(STONE, 5, 4, 5, top, out);
		int min = 255;
		int max = 0;
		for (int i = 0; i < 4; i++) {
			int g = out.colors[i] & 255;
			min = Math.min(min, g);
			max = Math.max(max, g);
		}
		assertTrue(min < max, "corners at the wall are darker than the open side");
		// offene Ecke: (1 + 1 + 1 + 1) / 4 = 1,0 → 255; an der Wand: Seite 0,2 + zwei offene + Mitte → (0,2 + 1 + 1 + 1)/4 = 0,8 → 204
		assertEquals(255, max);
		assertEquals(204, min);
		// flach: oben volle Richtungsschattierung, Licht der Zelle darüber
		lighter.flat(STONE, 5, 4, 5, top, out);
		assertEquals(255, out.colors[0] & 255);
		assertEquals(15, out.lights[0] >> 20 & 15);
	}

	@Test
	void smoothBlendReplacesDarkNeighboursLikeVanilla() {
		int center = 15 << 4 | 15 << 20;
		// dunkler Nachbar (0) wird durch die Mitte ersetzt → Mittel bleibt hell
		assertEquals(center & 0xFF00FF, BlockLighter.smoothBlend(0, center, center, center));
		// Nachbar mit Himmel 0, Block 8: Himmel der Mitte wird übernommen
		int n = 8 << 4;
		int r = BlockLighter.smoothBlend(n, center, center, center);
		assertEquals(240, r >> 16 & 255);
		assertEquals((128 + 240 * 3) / 4, r & 255);
	}

	@Test
	void emissiveElementsAndShadeDirectionOverride() {
		// cross-Modelle (Gras, Blumen): shade_direction_override "up" → Richtungsschattierung 1,0
		int grass = REG.idOf("minecraft:short_grass", Map.of());
		RenderModel m = models.model(grass);
		assertFalse(m.ambientOcclusion(), "cross model disables AO");
		assertTrue(m.quads().stream().allMatch(q -> q.shadeFace() == Face.UP));
		// Element mit light_emission 15 (z. B. leuchtende Pflanzen): Lichtkoordinaten mindestens 15
		assertEquals(15 << 4 | 15 << 20, BlockLighter.withEmission(0, 15));
	}

	// ----- Culling, Schichten, Fluide -----

	@Test
	void vanillaCullingSkipsGlassToGlassButKeepsStoneAgainstGlass() {
		SectionSnapshot s = snap(blocks(new int[]{3, 3, 3}, GLASS, new int[]{4, 3, 3}, GLASS, new int[]{3, 3, 4}, STONE), 15, 0, 0);
		SectionMesher.Result r = SectionMesher.build(s, ctx(List.of("minecraft:plains")));
		// Stein: 6 Seiten, Seite zum Glas bleibt (Glas verdeckt nicht); Glas: je 6 − 1 (Glas an Glas) − 0 (Stein verdeckt die Glasseite → 1 weg)
		assertEquals(6 * 6, vertices(r.solid()), "stone keeps all faces");
		// 26.3: glass.json setzt force_translucent → Glas liegt in der durchscheinenden Schicht (nicht CUTOUT wie früher)
		assertEquals((5 + 4) * 6, vertices(r.translucent()), "glass hides faces to glass and the face covered by stone");
		assertEquals(0, vertices(r.cutout()));
	}

	@Test
	void layersFollowTextureTransparency() {
		int leaves = REG.idOf("minecraft:oak_leaves", Map.of("distance", "1", "persistent", "true", "waterlogged", "false"));
		int ice = REG.idOf("minecraft:ice", Map.of());
		assertTrue(models.model(leaves).quads().stream().allMatch(q -> q.layer() == RenderQuad.Layer.CUTOUT), "leaves are cutout");
		assertTrue(models.model(ice).quads().stream().allMatch(q -> q.layer() == RenderQuad.Layer.TRANSLUCENT), "ice is translucent");
		assertTrue(models.model(STONE).quads().stream().allMatch(q -> q.layer() == RenderQuad.Layer.SOLID));
	}

	@Test
	void waterSourceSurfaceHasVanillaHeightAndBiomeColour() {
		int water = REG.idOf("minecraft:water", Map.of("level", "0"));
		SectionSnapshot s = snap(blocks(new int[]{5, 5, 5}, water, new int[]{5, 4, 5}, STONE), 15, 0, 0);
		SectionMesher.Result r = SectionMesher.build(s, ctx(List.of("minecraft:swamp")));
		assertTrue(r.translucent().length > 0, "water is translucent");
		float maxY = 0;
		int color = 0;
		for (int v = 0; v < vertices(r.translucent()); v++) {
			float y = Float.intBitsToFloat(r.translucent()[v * MeshBuilder.INTS_PER_VERTEX + 1]);
			if (y > maxY) {
				maxY = y;
				color = r.translucent()[v * MeshBuilder.INTS_PER_VERTEX + 5];
			}
		}
		// einzelne Quelle: Ecken = gewichtetes Mittel (8/9·10 + 0 + 0)/12 (calculateAverageHeight), minus 0,001
		assertEquals(5 + (8f / 9f * 10f) / 12f - 0.001f, maxY, 1e-4, "corner height of a lone source");
		// Sumpf-Wasserfarbe #617b64 (Oberseite: Richtung 1,0)
		assertEquals(0x61, color & 255);
		assertEquals(0x7b, color >> 8 & 255);
		assertEquals(0x64, color >> 16 & 255);
	}

	// ----- Biomtönung -----

	@Test
	void grassTintBlendsAcrossBiomeBorders() {
		int grassBlock = REG.idOf("minecraft:grass_block", Map.of("snowy", "false"));
		BiomeColors bc = new BiomeColors(env);
		List<String> names = List.of("minecraft:plains", "minecraft:desert");
		SectionMesher.Context c = ctx(names);
		int[] states = new int[SectionSnapshot.SIZE * SectionSnapshot.SIZE * SectionSnapshot.SIZE];
		byte[] light = new byte[states.length];
		int[] biomes = new int[states.length];
		Arrays.fill(light, SectionSnapshot.pack(15, 0));
		for (int x = -2; x < 18; x++) {
			for (int z = -2; z < 18; z++) {
				for (int y = -2; y < 18; y++) {
					biomes[SectionSnapshot.index(x, y, z)] = x < 8 ? 0 : 1;
				}
			}
		}
		SectionSnapshot s = new SectionSnapshot(0, 0, 0, states, light, biomes);
		int plains = bc.grass(env.biome("minecraft:plains"), 0, 0);
		int desert = bc.grass(env.biome("minecraft:desert"), 0, 0);
		assertEquals(plains, SectionMesher.blend(s, c, TintTable.Kind.GRASS, 2, 5, 5));
		assertEquals(desert, SectionMesher.blend(s, c, TintTable.Kind.GRASS, 13, 5, 5));
		int border = SectionMesher.blend(s, c, TintTable.Kind.GRASS, 8, 5, 5);
		assertNotEquals(plains, border);
		assertNotEquals(desert, border);
		// 2 von 5 Spalten Ebene, 3 Wüste
		assertEquals(((plains >> 16 & 255) * 10 + (desert >> 16 & 255) * 15) / 25, border >> 16 & 255);
		assertEquals(TintTable.Kind.GRASS, TintTable.get().source(grassBlock, 0).kind());
	}

	// ----- Animation -----

	@Test
	void animationUsesFrameOrderTimesAndInterpolation() {
		BufferedImage img = new BufferedImage(1, 3, BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, 0xFF000000);
		img.setRGB(0, 1, 0xFF0000FF);
		img.setRGB(0, 2, 0xFFFF0000);
		TextureAtlas atlas = new TextureAtlas();
		TextureAtlas.Sprite s = atlas.add("t", img, new TextureAtlas.AnimationMeta(2, new int[]{2, 0}, new int[]{-1, 3}, false, -1, -1));
		assertEquals(2, s.currentFrameIndex());
		assertEquals(0, atlas.animationTick().size(), "frame 2 shows for 2 ticks");
		assertEquals(1, atlas.animationTick().size(), "switch to frame 0");
		assertEquals(0, s.currentFrameIndex());
		atlas.animationTick();
		atlas.animationTick();
		assertEquals(1, atlas.animationTick().size(), "frame 0 lasts 3 ticks, loop to frame 2");
		assertEquals(2, s.currentFrameIndex());
		TextureAtlas a2 = new TextureAtlas();
		TextureAtlas.Sprite s2 = a2.add("i", img, new TextureAtlas.AnimationMeta(4, null, null, true, -1, -1));
		List<TextureAtlas.Update> u = a2.animationTick();
		assertEquals(1, u.size(), "interpolated animations update every tick");
		int c = u.getFirst().argb()[0];
		assertEquals(64, c & 255, "1/4 of the way from black to blue");
		assertEquals(3, s2.frameCount());
	}

	@Test
	void transparencyIsMeasuredInTheUvRegionOverAllFrames() {
		BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, 0xFFFFFFFF);
		img.setRGB(1, 0, 0x00000000);
		img.setRGB(0, 1, 0x80FFFFFF);
		img.setRGB(1, 1, 0xFFFFFFFF);
		TextureAtlas.Sprite s = new TextureAtlas().add("q", img);
		assertEquals(TextureAtlas.Transparency.OPAQUE, s.transparency(0, 0, 8, 8));
		assertEquals(TextureAtlas.Transparency.CUTOUT, s.transparency(8, 0, 16, 8));
		assertEquals(TextureAtlas.Transparency.TRANSLUCENT, s.transparency(0, 0, 16, 16));
	}

	// ----- Sortierung -----

	@Test
	void translucentQuadsAreSortedFarToNear() {
		MeshBuilder b = new MeshBuilder();
		int w = MeshBuilder.rgba(255, 255, 255, 128);
		for (int z = 0; z < 3; z++) {
			b.quad(new float[]{0, 0, z, 1, 0, z, 1, 1, z, 0, 1, z}, new float[8], new int[]{w, w, w, w});
		}
		int[] sorted = TranslucentSort.sort(b.toArray(), 0.5, 0.5, -5);
		float first = Float.intBitsToFloat(sorted[2]);
		float last = Float.intBitsToFloat(sorted[sorted.length - MeshBuilder.INTS_PER_VERTEX + 2]);
		assertEquals(2f, first, "farthest first");
		assertEquals(0f, last, "nearest last");
	}

	/** M14 (m14-visual3): beide Feuer-Sprites im Blockatlas, animiert – sonst fehlt das Feuer brennender Entities. */
	@Test
	void bothFireSpritesAreInTheBlockAtlasForBurningEntities() {
		for (String s : new String[]{"block/fire_0", "block/fire_1"}) {
			TextureAtlas.Sprite sprite = models.atlas().get(s);
			org.junit.jupiter.api.Assertions.assertNotNull(sprite, s);
			org.junit.jupiter.api.Assertions.assertTrue(sprite.animated(), s + " animated");
		}
	}
}
