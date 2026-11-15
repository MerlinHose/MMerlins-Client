package dev.mcresearch.protocolclient.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcresearch.protocolclient.world.BlockRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Modellauflösung gegen die lokal vorhandenen Minecraft-26.3-Ressourcen (docs/25 §9). Ohne lokales JAR übersprungen –
 * die Ressourcen sind nicht Teil des Projekts.
 */
public class ResourceModelsTest {
	private static ResourceModels models;
	private static final BlockRegistry REG = BlockRegistry.get();

	@BeforeAll
	static void load() throws Exception {
		Optional<Path> jar = localJar();
		assumeTrue(jar.isPresent(), "local Minecraft 26.3 client jar not found");
		long t0 = System.nanoTime();
		models = ResourceModels.load(jar.get(), REG);
		System.out.printf("[models] %s in %.0f ms%n", models.summary(), (System.nanoTime() - t0) / 1e6);
	}

	public static Optional<Path> localJar() throws Exception {
		Path dir = Path.of("../research/.gradle/loom-cache/minecraftMaven/net/minecraft");
		if (!Files.isDirectory(dir)) {
			return Optional.empty();
		}
		try (Stream<Path> s = Files.walk(dir, 3)) {
			return s.filter(p -> p.getFileName().toString().startsWith("minecraft-merged") && p.toString().endsWith("-26.3.jar")).findFirst();
		}
	}

	private static RenderModel model(final String name, final String props) {
		Map<String, String> m = new java.util.HashMap<>();
		if (!props.isEmpty()) {
			for (String kv : props.split(",")) {
				String[] p = kv.split("=");
				m.put(p[0], p[1]);
			}
		}
		return models.model(REG.idOf(name, m));
	}

	@Test
	void fullBlocksOccludeAllSidesWithSixCulledFaces() {
		RenderModel stone = model("minecraft:stone", "");
		assertEquals(6, stone.quads().size());
		for (Face f : Face.values()) {
			assertTrue(stone.occludes(f), f.name());
		}
		assertTrue(stone.quads().stream().allMatch(q -> q.cullFace() != null && q.layer() == RenderQuad.Layer.SOLID));
	}

	@Test
	void grassBlockHasDifferentTexturesPerSideAndTintedTop() {
		RenderModel grass = model("minecraft:grass_block", "snowy=false");
		RenderQuad top = grass.quads().stream().filter(q -> q.shadeFace() == Face.UP).findFirst().orElseThrow();
		RenderQuad bottom = grass.quads().stream().filter(q -> q.shadeFace() == Face.DOWN).findFirst().orElseThrow();
		assertTrue(top.tintIndex() >= 0, "grass top is tinted");
		assertNotEquals(top.uvs()[0], bottom.uvs()[0], "top and bottom use different atlas tiles");
		// M12: Tönungsquelle aus der Vanilla-Tabelle (BlockColors): Gras-Biomfarbe für tintindex 0
		assertEquals(TintTable.Kind.GRASS, TintTable.get().source(REG.idOf("minecraft:grass_block", Map.of("snowy", "false")), 0).kind());
	}

	@Test
	void slabOccludesOnlyItsFullSide() {
		RenderModel slab = model("minecraft:oak_slab", "type=bottom,waterlogged=false");
		assertTrue(slab.occludes(Face.DOWN));
		assertFalse(slab.occludes(Face.UP));
		assertFalse(slab.occludes(Face.NORTH));
		float maxY = 0;
		for (RenderQuad q : slab.quads()) {
			for (int k = 0; k < 4; k++) {
				maxY = Math.max(maxY, q.positions()[k * 3 + 1]);
			}
		}
		assertEquals(0.5f, maxY, 1e-6);
	}

	@Test
	void stairsAreRotatedByVariant() {
		// Modell "stairs" hat die hohe Stufe im Osten; facing=south (y=90) muss sie nach Süden (+Z) drehen
		RenderModel south = model("minecraft:oak_stairs", "facing=south,half=bottom,shape=straight,waterlogged=false");
		float minZOfUpper = 1;
		for (RenderQuad q : south.quads()) {
			for (int k = 0; k < 4; k++) {
				if (q.positions()[k * 3 + 1] > 0.75f) {
					minZOfUpper = Math.min(minZOfUpper, q.positions()[k * 3 + 2]);
				}
			}
		}
		assertEquals(0.5f, minZOfUpper, 1e-6, "upper step occupies z 0.5..1 (south half)");
		assertTrue(south.occludes(Face.SOUTH));
		assertFalse(south.occludes(Face.NORTH));
	}

	@Test
	void multipartFenceAndTransparencyLayers() {
		RenderModel post = model("minecraft:oak_fence", "east=false,north=false,south=false,waterlogged=false,west=false");
		RenderModel connected = model("minecraft:oak_fence", "east=true,north=true,south=false,waterlogged=false,west=false");
		assertTrue(connected.quads().size() > post.quads().size(), "multipart adds the side arms");
		// 26.3: glass.json setzt "force_translucent": true → durchscheinender Durchgang (Datenvorgabe, nicht Alphawerte)
		assertTrue(model("minecraft:glass", "").quads().stream().allMatch(q -> q.layer() == RenderQuad.Layer.TRANSLUCENT));
		assertFalse(model("minecraft:glass", "").occludes(Face.UP), "cutout glass does not hide neighbours");
		assertTrue(model("minecraft:oak_leaves", "distance=1,persistent=false,waterlogged=false").quads().stream()
			.allMatch(q -> q.layer() == RenderQuad.Layer.CUTOUT && q.tintIndex() >= 0));
		RenderModel water = model("minecraft:water", "level=0");
		assertTrue(water.cullGroup() > 0);
		assertTrue(water.quads().stream().allMatch(q -> q.layer() == RenderQuad.Layer.TRANSLUCENT));
	}

	@Test
	void unknownModelsAreFewAndRenderedAsDebugCube() {
		String s = models.summary();
		int debug = Integer.parseInt(s.replaceAll(".* (\\d+) debug states.*", "$1"));
		assertTrue(debug < REG.size() / 50, "debug states " + debug + " of " + REG.size() + ": " + s);
		assertTrue(models.atlas().spriteCount() > 500);
	}
}
