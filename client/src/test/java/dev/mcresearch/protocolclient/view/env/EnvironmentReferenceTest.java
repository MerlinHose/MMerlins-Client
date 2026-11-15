package dev.mcresearch.protocolclient.view.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcresearch.protocolclient.view.ResourceModelsTest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * M12 (docs/49 §6): eigene Umsetzungen gegen Referenzwerte aus dem unveränderten Spiel-JAR ({@code analysis/tools/RenderMathCheck})
 * und gegen die Datendateien (Zeitleisten, Biome, Dimensionen) des lokalen JARs.
 */
class EnvironmentReferenceTest {
	private static EnvironmentData data;

	@BeforeAll
	static void load() throws Exception {
		Optional<Path> jar = ResourceModelsTest.localJar();
		assumeTrue(jar.isPresent(), "local Minecraft 26.3 client jar not found");
		data = EnvironmentData.load(jar.get());
		System.out.println("[env] " + data.summary());
	}

	/** Biome.BIOME_INFO_NOISE.get(x·0,0225, z·0,0225) laut RenderMathCheck. */
	@Test
	void swampNoiseMatchesVanilla() {
		double[][] ref = {{0, 0, 0.0}, {100, 200, -0.751157522}, {-300, 45, -0.819338441}, {1234, -987, 0.339945167}, {7, 13, 0.178374216},
			{-5000, 5000, -0.249472335}, {300, 300, 0.422563702}, {340, 305, 0.516146958}, {41, -77, -0.143249571}, {2000, 1, 0.029423965}};
		BiomeColors.Simplex2 n = new BiomeColors.Simplex2(2345L);
		for (double[] r : ref) {
			assertEquals(r[2], n.get(r[0] * 0.0225, r[1] * 0.0225), 1e-6, "noise at " + r[0] + "," + r[1]);
		}
	}

	/** EasingType.cubicBezier(0,362; 0,241; 0,638; 0,759) laut RenderMathCheck (Toleranz der Vorlage 1e−5). */
	@Test
	void sunCurveMatchesVanilla() {
		float[] ref = {0.0f, 0.0748823f, 0.1653158f, 0.2688290f, 0.3818939f, 0.5f, 0.6181062f, 0.7311711f, 0.8346842f, 0.9251177f, 1.0f};
		EnvTimeline.CubicBezier b = new EnvTimeline.CubicBezier(0.362f, 0.241f, 0.638f, 0.759f);
		for (int i = 0; i <= 10; i++) {
			assertEquals(ref[i], b.apply(i / 10f), 2e-5f, "x=" + i / 10f);
		}
	}

	private static EnvironmentState overworld(final long ticks, final float rain, final float thunder, final String biome) {
		EnvironmentState s = new EnvironmentState(data);
		s.setDimension("minecraft:overworld");
		s.tick(new EnvironmentState.Inputs(0.5, 100, 0.5, (x, y, z) -> 0, List.of(biome), Map.of("minecraft:overworld", ticks), rain, thunder));
		return s;
	}

	@Test
	void noonAndMidnightFromTheDayTimeline() {
		EnvironmentState noon = overworld(6000, 0, 0, "minecraft:plains");
		// Mittag: Sonne im Zenit (Winkel 0 bzw. 360), Himmel = Biomfarbe plains (#78a7ff) × weiß, Himmelslicht voll
		float sun = noon.value(EnvAttr.SUN_ANGLE)[0];
		assertTrue(Math.abs(sun) < 1e-3 || Math.abs(sun - 360) < 1e-3, "sun angle " + sun);
		assertEquals(0x78 / 255f, noon.value(EnvAttr.SKY_COLOR)[0], 1e-4);
		assertEquals(1f, noon.value(EnvAttr.SKY_LIGHT_FACTOR)[0], 1e-6);
		assertEquals(0f, noon.value(EnvAttr.STAR_BRIGHTNESS)[0], 1e-6);
		EnvironmentState midnight = overworld(18000, 0, 0, "minecraft:plains");
		assertEquals(180f, midnight.value(EnvAttr.SUN_ANGLE)[0], 1e-3);
		assertEquals(0.24f, midnight.value(EnvAttr.SKY_LIGHT_FACTOR)[0], 1e-6);
		assertEquals(0.5f, midnight.value(EnvAttr.STAR_BRIGHTNESS)[0], 1e-6);
		assertEquals(0f, midnight.value(EnvAttr.SKY_COLOR)[0], 1e-6);
		// Nachtlichtfarbe #7a7aff
		assertEquals(0x7a / 255f, midnight.value(EnvAttr.SKY_LIGHT_COLOR)[0], 1e-4);
		assertEquals(1f, midnight.value(EnvAttr.SKY_LIGHT_COLOR)[2], 1e-4);
	}

	@Test
	void moonPhaseCyclesEvery24000Ticks() {
		assertEquals(0f, overworld(6000, 0, 0, "minecraft:plains").value(EnvAttr.MOON_PHASE)[0]);
		assertEquals(1f, overworld(24000 + 6000, 0, 0, "minecraft:plains").value(EnvAttr.MOON_PHASE)[0]);
		assertEquals(4f, overworld(4 * 24000 + 100, 0, 0, "minecraft:plains").value(EnvAttr.MOON_PHASE)[0]);
	}

	@Test
	void rainAndThunderDarkenTheSkyLikeVanilla() {
		float clearFactor = overworld(6000, 0, 0, "minecraft:plains").value(EnvAttr.SKY_LIGHT_FACTOR)[0];
		float rainFactor = overworld(6000, 1, 0, "minecraft:plains").value(EnvAttr.SKY_LIGHT_FACTOR)[0];
		float thunderFactor = overworld(6000, 1, 1, "minecraft:plains").value(EnvAttr.SKY_LIGHT_FACTOR)[0];
		// alpha_blend: 1 → 0,24 mit 0,3125 (Regen) bzw. 0,52734375 (Gewitter)
		assertEquals(1f + 0.3125f * (0.24f - 1f), rainFactor, 1e-5);
		assertEquals(1f + 0.52734375f * (0.24f - 1f), thunderFactor, 1e-5);
		assertTrue(clearFactor > rainFactor && rainFactor > thunderFactor);
		assertEquals(0f, overworld(18000, 1, 0, "minecraft:plains").value(EnvAttr.STAR_BRIGHTNESS)[0], 1e-6, "no stars while raining");
	}

	@Test
	void netherHasNoSkyLightAndBiomeFog() {
		EnvironmentState s = new EnvironmentState(data);
		s.setDimension("minecraft:the_nether");
		s.tick(new EnvironmentState.Inputs(0.5, 64, 0.5, (x, y, z) -> 0, List.of("minecraft:nether_wastes"), Map.of(), 0, 0));
		assertEquals(0f, s.value(EnvAttr.SKY_LIGHT_FACTOR)[0], 1e-6);
		assertEquals(10f, s.value(EnvAttr.FOG_START_DISTANCE)[0], 1e-6);
		assertEquals(96f, s.value(EnvAttr.FOG_END_DISTANCE)[0], 1e-6);
		assertTrue(s.dimension().netherLighting());
		assertTrue(!s.dimension().canHaveWeather());
	}

	@Test
	void grassColoursFollowClimateAndModifiers() {
		BiomeColors c = new BiomeColors(data);
		int plains = c.grass(data.biome("minecraft:plains"), 0, 0);
		int desert = c.grass(data.biome("minecraft:desert"), 0, 0);
		int jungle = c.grass(data.biome("minecraft:jungle"), 0, 0);
		assertTrue(plains != desert && plains != jungle && desert != jungle, "different climates give different grass");
		// Colormap-Abfrage für plains (t 0,8, r 0,4·0,8)
		assertEquals(BiomeColors.colormap(0.8, 0.4, data.grassMap(), 0), plains);
		// Sumpf: Rauschen an (300,300) ≥ −0,1 → 0x6A7039; an (100,200) < −0,1 → 0x4C763C
		assertEquals(0x6A7039, c.grass(data.biome("minecraft:swamp"), 300, 300));
		assertEquals(0x4C763C, c.grass(data.biome("minecraft:swamp"), 100, 200));
		assertEquals(0x3F76E4, c.water(data.biome("minecraft:plains")));
		assertEquals(0x617B64, c.water(data.biome("minecraft:swamp")));
	}

	@Test
	void fogUsesRenderDistanceSpanAndWaterVision() {
		EnvironmentState noon = overworld(6000, 0, 0, "minecraft:plains");
		FogModel.Fog air = new FogModel().compute(noon, 1f, 4, FogModel.NONE, 0f, 0, 0, 15, true, 1, 0, false, 0);
		assertEquals(64f, air.rdEnd(), 1e-6);
		assertEquals(64f - 6.4f, air.rdStart(), 1e-4);
		FogModel.Fog water = new FogModel().compute(noon, 1f, 4, FogModel.WATER, 0f, 0, 0, 15, true, 1, 0.5f, false, 0);
		assertEquals(-8f, water.envStart(), 1e-6);
		assertEquals(96f * 0.5f, water.envEnd(), 1e-4);
	}
}
