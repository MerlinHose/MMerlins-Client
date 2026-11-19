package dev.mcresearch.protocolclient.view.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcresearch.protocolclient.protocol.json.MiniJson;
import dev.mcresearch.protocolclient.view.ResourceModelsTest;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/** M14 (Grafiktest m14-gfx4: Debug-Box für einen liegenden Gegenstand): Textur über Item- bzw. Blockmodell. */
class ItemTexturesTest {
	@Test
	void layer0AndParticleAreResolvedAlongTheParentChain() {
		Map<String, Map<?, ?>> m = new HashMap<>();
		m.put("block/cube_all", Map.of("textures", Map.of("particle", "#all")));
		m.put("block/foo", Map.of("parent", "minecraft:block/cube_all", "textures", Map.of("all", "minecraft:block/foo_texture")));
		m.put("item/foo", Map.of("parent", "minecraft:block/foo"));
		m.put("item/bar", Map.of("parent", "minecraft:item/generated", "textures", Map.of("layer0", "minecraft:item/bar_icon")));
		assertEquals("block/foo_texture", ItemTextures.resolve("foo", m::get, null));
		assertEquals("item/bar_icon", ItemTextures.resolve("bar", m::get, null));
		assertNull(ItemTextures.resolve("missing", m::get, null));
	}

	/** Jeder Gegenstand der Registry hat eine vorhandene Textur (direkt oder über das Modell) – keine Debug-Box für Item-Entities. */
	@Test
	void everyRegisteredItemHasADrawableTexture() throws Exception {
		Optional<Path> jar = ResourceModelsTest.localJar();
		assumeTrue(jar.isPresent(), "local Minecraft 26.3 client jar not found");
		try (ZipFile zip = new ZipFile(jar.get().toFile())) {
			Map<String, Map<?, ?>> cache = new HashMap<>();
			java.util.function.Function<String, Map<?, ?>> models = p -> cache.computeIfAbsent(p, k -> load(zip, "assets/minecraft/models/" + k + ".json"));
			java.util.function.Function<String, Map<?, ?>> defs = n -> load(zip, "assets/minecraft/items/" + n + ".json");
			List<String> missing = new ArrayList<>();
			ItemRegistry reg = ItemRegistry.get();
			for (int id = 1; id < reg.size(); id++) {
				String n = reg.name(id).replace("minecraft:", "");
				if (exists(zip, "item/" + n) || exists(zip, "block/" + n)) {
					continue;
				}
				String r = ItemTextures.resolve(n, models, defs);
				if (r == null || !exists(zip, r)) {
					missing.add(n);
				}
			}
			assertTrue(missing.isEmpty(), missing.size() + " items without texture: " + missing);
		}
	}

	private static Map<?, ?> load(final ZipFile zip, final String entry) {
		ZipEntry e = zip.getEntry(entry);
		if (e == null) {
			return null;
		}
		try (InputStream in = zip.getInputStream(e)) {
			return MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)) instanceof Map<?, ?> mm ? mm : null;
		} catch (Exception ex) {
			return null;
		}
	}

	private static boolean exists(final ZipFile zip, final String texture) {
		return zip.getEntry("assets/minecraft/textures/" + texture + ".png") != null;
	}
}
