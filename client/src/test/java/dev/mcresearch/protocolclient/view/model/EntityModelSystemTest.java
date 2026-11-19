package dev.mcresearch.protocolclient.view.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.entity.EntityManager;
import dev.mcresearch.protocolclient.entity.EntityVariants;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import dev.mcresearch.protocolclient.view.EntityModels;
import dev.mcresearch.protocolclient.view.Mat4;
import dev.mcresearch.protocolclient.view.MeshBuilder;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M14 Phase B–D (docs/55): Modellbibliothek, Pose, Mesh, Keyframes, Darstellungspläne und Animatoren. */
class EntityModelSystemTest {
	private static final int CHICKEN = 26, WOLF = 152, CAT = 21, HORSE = 67, VILLAGER = 143, SHEEP = 114, PIG = 101, RABBIT = 111, BEE = 11,
		CREEPER = 32, SPIDER = 127, ENDERMAN = 42, IRON_GOLEM = 71, DROWNED = 39, ZOMBIE = 154, COW = 30, SKELETON = 118, PLAYER = 159, OAK_BOAT = 90,
		MINECART = 86;

	private static EntityManager.View spawn(final EntityManager m, final int id, final int type, final Object... data) {
		m.handle(new Clientbound.AddEntity(id, new UUID(7, id), type, 0, 64, 0, 0, 0, 0, 0, 0, 0, 0));
		List<Clientbound.DataValue> values = new ArrayList<>();
		for (int i = 0; i < data.length; i += 2) {
			values.add(new Clientbound.DataValue((Integer) data[i], 0, data[i + 1]));
		}
		if (!values.isEmpty()) {
			m.handle(new Clientbound.SetEntityData(id, values, null));
		}
		return m.view(id, 1.0F, 0);
	}

	private static EntityLooks.Context context(final EntityVariants variants) {
		return new EntityLooks.Context(variants, null, 1000, t -> "none", (u, n) -> new EntityLooks.SkinLookup.Skin("skin:test", false),
			ItemRegistry.get());
	}

	@Test
	void libraryContainsAllTargetLayersWithTextureSizes() {
		ModelLibrary lib = ModelLibrary.get();
		assertEquals(361, lib.size());
		for (String l : new String[]{"chicken", "chicken_baby", "wolf", "wolf_baby", "cat", "cat_baby", "horse", "horse_baby", "rabbit", "rabbit_baby",
			"bee", "bee_baby", "creeper", "spider", "enderman", "villager", "villager_baby", "iron_golem", "drowned", "drowned_baby", "pig", "pig_baby",
			"cow", "cow_baby", "sheep", "sheep_baby", "zombie", "zombie_baby", "skeleton", "player", "player_slim", "minecart", "boat/oak"}) {
			assertNotNull(lib.model("minecraft:" + l + "#main"), l);
		}
		assertEquals(16, lib.model("minecraft:chicken_baby#main").texWidth());
		assertEquals(64, lib.model("minecraft:pig#main").texWidth());
	}

	@Test
	void extractedPigMatchesTheM9HandWrittenModel() {
		ModelLibrary.Model pig = ModelLibrary.get().model("minecraft:pig#main");
		EntityModels.Model old = EntityModels.PIG;
		for (EntityModels.Part op : old.parts()) {
			int i = pig.part(op.name());
			assertTrue(i >= 0, op.name());
			ModelLibrary.Part np = pig.parts().get(i);
			assertEquals(op.px(), np.x(), 1e-6, op.name());
			assertEquals(op.py(), np.y(), 1e-6, op.name());
			assertEquals(op.pz(), np.z(), 1e-6, op.name());
			assertEquals(op.rx(), np.xRot(), 1e-6, op.name());
			assertEquals(op.boxes().size(), np.cubes().size(), op.name());
			for (int b = 0; b < op.boxes().size(); b++) {
				EntityModels.Box ob = op.boxes().get(b);
				ModelLibrary.Cube nc = np.cubes().get(b);
				assertEquals(ob.w(), nc.w());
				assertEquals(ob.h(), nc.h());
				assertEquals(ob.d(), nc.d());
				assertEquals(ob.u(), nc.u());
				assertEquals(ob.v(), nc.v());
				assertEquals(ob.mirror(), nc.mirror(), op.name());
			}
			// gleiche Abwicklung wie M9 (Teile ohne Spiegelung und Vergrößerung): identische Vertexdaten
			assertTrue(java.util.Arrays.equals(EntityModels.partMesh(old, op), ModelMesher.partMesh(pig, np)), op.name());
		}
	}

	@Test
	void hierarchyAndPoseMatricesComposeParentFirst() {
		ModelLibrary.Model player = ModelLibrary.get().model("minecraft:player#main");
		int arm = player.part("right_arm");
		int sleeve = player.part("right_sleeve");
		assertEquals(arm, player.parts().get(sleeve).parent());
		ModelPose p = new ModelPose(player);
		p.setXRot("right_arm", 1.0F);
		Mat4[] m = p.matrices();
		// Ärmel erbt die Armdrehung: gleicher Punkt in beiden Räumen
		float[] a = m[arm].transform(0, 10, 0);
		float[] s = m[sleeve].transform(0, 10, 0);
		assertEquals(a[1], s[1], 1e-5);
		assertEquals(a[2], s[2], 1e-5);
		p.setVisible("right_arm", false);
		assertFalse(p.effectivelyVisible(sleeve));
		p.reset();
		assertTrue(p.effectivelyVisible(sleeve));
		assertEquals(0, p.xRot[arm]);
	}

	@Test
	void meshRespectsFaceMaskAndZeroSizeCubes() {
		ModelLibrary.Cube full = new ModelLibrary.Cube(0, 0, 0, 0, 0, 2, 2, 2, 0, 0, 0, false, 1, 1, ModelLibrary.ALL_FACES);
		ModelLibrary.Cube top = new ModelLibrary.Cube(0, 0, 0, 0, 0, 2, 2, 2, 0, 0, 0, false, 1, 1, ModelLibrary.FACE_DOWN);
		ModelLibrary.Model m = new ModelLibrary.Model("t", 16, 16, List.of(new ModelLibrary.Part("root", "root", 0, -1, 0, 0, 0, 0, 0, 0, 1, 1, 1,
			List.of(full, top))));
		int[] v = ModelMesher.partMesh(m, m.parts().getFirst());
		assertEquals((6 + 1) * 6, v.length / MeshBuilder.INTS_PER_VERTEX); // je Seite zwei Dreiecke (6 Vertices)
		// Babyhuhn hat flache Quader (Breite 0): Mesh entsteht ohne Fehler
		ModelLibrary.Model chick = ModelLibrary.get().model("minecraft:chicken_baby#main");
		for (ModelLibrary.Part part : chick.parts()) {
			ModelMesher.partMesh(chick, part);
		}
	}

	@Test
	void keyframesInterpolateLinearAndCatmullRom() {
		KeyframeLibrary.Key[] keys = {new KeyframeLibrary.Key(0, new float[]{0, 0, 0}, new float[]{0, 0, 0}, false),
			new KeyframeLibrary.Key(1, new float[]{2, 0, 0}, new float[]{2, 0, 0}, false)};
		float[] out = new float[3];
		KeyframeLibrary.sample(keys, 0.25F, out);
		assertEquals(0.5F, out[0], 1e-6);
		assertEquals(1.0F, KeyframeLibrary.catmullRom(0, 0, 1, 2, 3), 1e-6);
		assertEquals(2.0F, KeyframeLibrary.catmullRom(1, 0, 1, 2, 3), 1e-6);
		assertEquals(1.5F, KeyframeLibrary.catmullRom(0.5F, 0, 1, 2, 3), 1e-6); // gleichmäßige Punkte: Spline = Gerade
		assertTrue(KeyframeLibrary.get().has("RabbitAnimation.HOP"));
		ModelPose p = new ModelPose(ModelLibrary.get().model("minecraft:rabbit#main"));
		KeyframeLibrary.get().apply("RabbitAnimation.HOP", p, 0.3F);
		assertNotEquals(0.0F, p.xRot[p.part("body")]); // Körper kippt beim Sprung (32,5° bei 0,29 s)
	}

	@Test
	void babiesUseOwnModelsAndTextures() {
		EntityManager m = new EntityManager();
		EntityLooks.Context c = context(null);
		EntityLooks.Look chick = EntityLooks.look(spawn(m, 1, CHICKEN, 16, true), c);
		assertEquals("minecraft:chicken_baby#main", chick.layers().getFirst().model());
		assertEquals("entity/chicken/chicken_temperate_baby", chick.layers().getFirst().texture());
		EntityLooks.Look pig = EntityLooks.look(spawn(m, 2, PIG, 16, true), c);
		assertEquals("minecraft:pig_baby#main", pig.layers().getFirst().model());
		assertEquals(1.0F, pig.scaleX()); // kein zusätzliches Verkleinern: Baby-Modell ist bereits klein (26.3)
		EntityLooks.Look zombie = EntityLooks.look(spawn(m, 3, ZOMBIE, 16, true), c);
		assertEquals("minecraft:zombie_baby#main", zombie.layers().getFirst().model());
		assertEquals("entity/zombie/zombie_baby", zombie.layers().getFirst().texture());
		EntityLooks.Look villager = EntityLooks.look(spawn(m, 4, VILLAGER, 16, true), c);
		assertEquals("minecraft:villager_baby#main", villager.layers().getFirst().model());
	}

	@Test
	void variantsResolveFromRegistryDataOrFallback() {
		EntityVariants vars = new EntityVariants();
		vars.setRegistry("minecraft:pig_variant", List.of("minecraft:cold", "minecraft:temperate", "minecraft:warm"), java.util.Arrays.asList(
			Map.of("asset_id", "minecraft:entity/pig/pig_cold", "baby_asset_id", "minecraft:entity/pig/pig_cold_baby", "model", "cold"), null, null));
		vars.setRegistry("minecraft:wolf_variant", List.of("minecraft:ashen"), List.of(Map.of("assets", Map.of("wild", "minecraft:entity/wolf/wolf_ashen",
			"tame", "minecraft:entity/wolf/wolf_ashen_tame", "angry", "minecraft:entity/wolf/wolf_ashen_angry"), "baby_assets", Map.of("wild",
			"minecraft:entity/wolf/wolf_ashen_baby", "tame", "minecraft:entity/wolf/wolf_ashen_tame_baby", "angry", "minecraft:entity/wolf/wolf_ashen_angry_baby"))));
		EntityManager m = new EntityManager();
		EntityLooks.Look cold = EntityLooks.look(spawn(m, 1, PIG, 19, 0), context(vars));
		assertEquals("minecraft:cold_pig#main", cold.layers().getFirst().model());
		assertEquals("entity/pig/pig_cold", cold.layers().getFirst().texture());
		// Eintrag ohne Daten: Rückfall auf das lokale Datenpaket (hier simuliert)
		EntityLooks.Context fb = new EntityLooks.Context(vars, (r, e) -> Map.of("asset_id", "minecraft:entity/pig/pig_warm", "model", "normal"), 0, t -> "none",
			null, ItemRegistry.get());
		assertEquals("entity/pig/pig_warm", EntityLooks.look(spawn(m, 2, PIG, 19, 2), fb).layers().getFirst().texture());
		// zahmer Wolf: zahme Textur + Halsband in Farbstofffarbe
		EntityLooks.Look wolf = EntityLooks.look(spawn(m, 3, WOLF, 23, 0, 18, (byte) 0x04, 21, 1), context(vars));
		assertEquals("entity/wolf/wolf_ashen_tame", wolf.layers().get(0).texture());
		assertEquals("entity/wolf/wolf_collar", wolf.layers().get(1).texture());
		assertEquals(0xFF000000 | EntityLooks.DYE[1], wolf.layers().get(1).color());
		// unbekannte ID: Standardtextur (temperate)
		assertEquals("entity/pig/pig_temperate", EntityLooks.look(spawn(m, 4, PIG, 19, 9), context(vars)).layers().getFirst().texture());
	}

	/**
	 * Fehler „schwarze gezähmte Katze mit Tabby-Textur“ (docs/58 §2.2): Vanilla 26.3 definiert die Standardvariante der Katze als
	 * {@code minecraft:black}; der Server überträgt nur Abweichungen vom Standard, eine schwarze Katze also ohne Variantenfeld.
	 */
	@Test
	void missingVariantFieldMeansTheVanillaDefaultVariant() {
		EntityVariants vars = new EntityVariants();
		vars.setRegistry("minecraft:cat_variant", List.of("minecraft:tabby", "minecraft:black", "minecraft:calico"), List.of(
			Map.of("asset_id", "minecraft:entity/cat/cat_tabby", "baby_asset_id", "minecraft:entity/cat/cat_tabby_baby"),
			Map.of("asset_id", "minecraft:entity/cat/cat_black", "baby_asset_id", "minecraft:entity/cat/cat_black_baby"),
			Map.of("asset_id", "minecraft:entity/cat/cat_calico", "baby_asset_id", "minecraft:entity/cat/cat_calico_baby")));
		vars.setRegistry("minecraft:wolf_variant", List.of("minecraft:ashen", "minecraft:pale"), List.of(
			Map.of("assets", Map.of("wild", "minecraft:entity/wolf/wolf_ashen", "tame", "minecraft:entity/wolf/wolf_ashen_tame", "angry", "minecraft:entity/wolf/wolf_ashen_angry")),
			Map.of("assets", Map.of("wild", "minecraft:entity/wolf/wolf", "tame", "minecraft:entity/wolf/wolf_tame", "angry", "minecraft:entity/wolf/wolf_angry"))));
		EntityManager m = new EntityManager();
		// gezähmte schwarze Katze mit Halsband (Tame-Flag 0x04, Halsbandfarbe 11) – kein Feld 20
		EntityLooks.Look black = EntityLooks.look(spawn(m, 1, CAT, 18, (byte) 0x04, 23, 11), context(vars));
		assertEquals("entity/cat/cat_black", black.layers().getFirst().texture());
		assertEquals("entity/cat/cat_collar", black.layers().get(1).texture());
		assertEquals("entity/cat/cat_black_baby", EntityLooks.look(spawn(m, 2, CAT, 16, true), context(vars)).layers().getFirst().texture());
		assertEquals("entity/cat/cat_calico", EntityLooks.look(spawn(m, 3, CAT, 20, 2), context(vars)).layers().getFirst().texture());
		assertEquals("entity/cat/cat_tabby", EntityLooks.look(spawn(m, 4, CAT, 20, 0), context(vars)).layers().getFirst().texture());
		// Wolf ohne Feld 23: Standard pale (nicht der erste Registereintrag)
		assertEquals("entity/wolf/wolf_tame", EntityLooks.look(spawn(m, 5, WOLF, 18, (byte) 0x04), context(vars)).layers().getFirst().texture());
		// Standardname fehlt im Register: irgendein Eintrag (VariantUtils#getDefaultOrAny) – hier der erste
		assertEquals(0, vars.defaultId("minecraft:wolf_variant", "minecraft:missing"));
		assertEquals(-1, vars.defaultId("minecraft:pig_variant", "minecraft:temperate"));
		// ohne Register (Known Packs nicht synchron): Rückfalltextur ist ebenfalls schwarz
		assertEquals("entity/cat/cat_black", EntityLooks.look(spawn(m, 6, CAT), context(null)).layers().getFirst().texture());
	}

	@Test
	void layersForSheepHorseVillagerCreeperSpider() {
		EntityManager m = new EntityManager();
		EntityLooks.Context c = context(null);
		EntityLooks.Look sheep = EntityLooks.look(spawn(m, 1, SHEEP, 18, (byte) 14), c);
		assertEquals(3, sheep.layers().size()); // Körper, Unterwolle (nicht weiß), Wolle
		assertEquals(0xFF000000 | EntityLooks.DYE[14], sheep.layers().get(2).color());
		assertEquals(1, EntityLooks.look(spawn(m, 2, SHEEP, 18, (byte) 0x10), c).layers().size()); // geschoren, weiß
		EntityLooks.Look horse = EntityLooks.look(spawn(m, 3, HORSE, 19, 3 | (2 << 8)), c);
		assertEquals("entity/horse/horse_brown", horse.layers().get(0).texture());
		assertEquals("entity/horse/horse_markings_whitefield", horse.layers().get(1).texture());
		EntityLooks.Look villager = EntityLooks.look(spawn(m, 4, VILLAGER, 19, new int[]{2, 9, 3}), c);
		List<String> tex = villager.layers().stream().map(EntityLooks.Layer::texture).toList();
		assertEquals(List.of("entity/villager/villager", "entity/villager/type/plains", "entity/villager/profession/librarian",
			"entity/villager/profession_level/gold"), tex);
		// m14-visual1: Dorfbewohner ohne Feld 19 (Standarddaten werden nicht gesendet) → Typ plains, kein Beruf; vorher fehlte die
		// Typschicht und die Arme erschienen hautfarben. Weitere Varianten: Wüste mit Schmied, Baby (nur Typschicht)
		assertEquals(List.of("entity/villager/villager", "entity/villager/type/plains"),
			EntityLooks.look(spawn(m, 40, VILLAGER), c).layers().stream().map(EntityLooks.Layer::texture).toList());
		assertEquals(List.of("entity/villager/villager", "entity/villager/type/desert", "entity/villager/profession/weaponsmith",
			"entity/villager/profession_level/stone"),
			EntityLooks.look(spawn(m, 41, VILLAGER, 19, new int[]{0, 14, 1}), c).layers().stream().map(EntityLooks.Layer::texture).toList());
		assertEquals(List.of("entity/villager/villager_baby", "entity/villager/baby/plains"),
			EntityLooks.look(spawn(m, 42, VILLAGER, 16, true), c).layers().stream().map(EntityLooks.Layer::texture).toList());
		EntityLooks.Look creeper = EntityLooks.look(spawn(m, 5, CREEPER), c);
		assertEquals(1.0F, creeper.scaleX(), 1e-6);
		EntityLooks.Look spider = EntityLooks.look(spawn(m, 6, SPIDER), c);
		assertEquals(EntityLooks.Kind.EYES, spider.layers().get(1).kind());
		assertEquals(180, spider.deathFlip());
		assertNotNull(EntityLooks.look(spawn(m, 7, ENDERMAN, 16, 1), c).carriedBlock());
		assertNotNull(EntityLooks.look(spawn(m, 8, IRON_GOLEM), c));
		EntityLooks.Look drowned = EntityLooks.look(spawn(m, 9, DROWNED), c);
		assertEquals("minecraft:drowned#outer", drowned.layers().get(1).model());
		assertEquals("boat", EntityLooks.look(spawn(m, 10, OAK_BOAT), c).animator());
		assertEquals("minecraft:boat/oak#main", EntityLooks.look(spawn(m, 11, OAK_BOAT), c).layers().getFirst().model());
		assertEquals("minecart", EntityLooks.look(spawn(m, 12, MINECART), c).animator());
		// m14-visual2: Anzeigeversatz ohne Feld 12 je Typ (getDefaultDisplayOffset): Kistenlore 8, Trichterlore 1, sonst 6
		assertEquals(8, EntityLooks.minecartDisplayOffset("chest_minecart", null));
		assertEquals(1, EntityLooks.minecartDisplayOffset("hopper_minecart", null));
		assertEquals(6, EntityLooks.minecartDisplayOffset("tnt_minecart", null));
		assertEquals(3, EntityLooks.minecartDisplayOffset("chest_minecart", 3));
	}

	@Test
	void everyTargetTypeAnimatesAllItsLayersWithoutErrors() {
		EntityManager m = new EntityManager();
		EntityLooks.Context c = context(null);
		int id = 100;
		for (int type : new int[]{CHICKEN, WOLF, CAT, HORSE, VILLAGER, SHEEP, PIG, RABBIT, BEE, CREEPER, SPIDER, ENDERMAN, IRON_GOLEM, DROWNED, ZOMBIE, COW,
			SKELETON, PLAYER, OAK_BOAT, MINECART}) {
			for (boolean baby : new boolean[]{false, true}) {
				EntityManager.View v = spawn(m, id++, type, 16, baby);
				EntityLooks.Look look = EntityLooks.look(v, c);
				assertNotNull(look, v.type().name());
				for (EntityLooks.Layer layer : look.layers()) {
					ModelLibrary.Model model = ModelLibrary.get().model(layer.model());
					assertNotNull(model, layer.model());
					ModelPose p = new ModelPose(model);
					EntityAnimators.apply(look.animator(), p, new EntityAnimators.Input(v, 37.5F, 0.5F));
					for (Mat4 mat : p.matrices()) {
						for (float f : mat.toArray()) {
							assertTrue(Float.isFinite(f), layer.model());
						}
					}
				}
			}
		}
	}

	@Test
	void quadrupedLegsSwingOppositeWhileWalking() {
		EntityManager m = new EntityManager();
		m.handle(new Clientbound.AddEntity(1, new UUID(1, 1), PIG, 0, 64, 0, 0, 0, 0, 0, 0, 0, 0));
		for (int t = 0; t < 10; t++) {
			m.handle(new Clientbound.MoveEntity(1, true, false, true, List.of(new Clientbound.DeltaStep(1, (short) 800, (short) 0, (short) 0)), 0, 0,
				true));
			m.tick();
		}
		EntityManager.View v = m.view(1, 0.5F, 0);
		assertTrue(v.anim().walkSpeed() > 0.3F, "walk speed " + v.anim().walkSpeed());
		ModelPose p = new ModelPose(ModelLibrary.get().model("minecraft:pig#main"));
		EntityAnimators.apply("quadruped", p, new EntityAnimators.Input(v, 10, 0));
		float rh = p.xRot[p.part("right_hind_leg")];
		float lh = p.xRot[p.part("left_hind_leg")];
		assertEquals(-rh, lh, 1e-4);
		assertEquals(rh, p.xRot[p.part("left_front_leg")], 1e-4);
	}

	@Test
	void playerSkinLayerBitsAreKnownParts() {
		ModelLibrary.Model player = ModelLibrary.get().model("minecraft:player#main");
		for (String n : Set.of("hat", "jacket", "left_sleeve", "right_sleeve", "left_pants", "right_pants")) {
			assertTrue(player.part(n) >= 0, n);
		}
		ModelLibrary.Model slim = ModelLibrary.get().model("minecraft:player_slim#main");
		assertEquals(3.0F, slim.parts().get(slim.part("right_arm")).cubes().getFirst().w());
		assertEquals(4.0F, player.parts().get(player.part("right_arm")).cubes().getFirst().w());
		assertNull(EntityLooks.look(spawn(new EntityManager(), 1, PLAYER), new EntityLooks.Context(null, null, 0, t -> "none", null, null)));
	}

	/** M14 (Grafiktest m14-gfx3: Debug-Boxen für Tintenfische und Lachse): Wassertiere mit Vanilla-Standardwerten bei fehlendem Feld. */
	@Test
	void aquaticMobsHaveLooksInsteadOfDebugBoxes() {
		EntityManager m = new EntityManager();
		EntityLooks.Context c = context(null);
		int squid = 130, glowSquid = 62, cod = 27, salmon = 113, pufferfish = 110, tropical = 140;
		EntityLooks.Look sq = EntityLooks.look(spawn(m, 1, squid), c);
		assertEquals("squid", sq.animator());
		assertEquals("entity/squid/squid", sq.layers().getFirst().texture());
		assertEquals("minecraft:glow_squid_baby#main", EntityLooks.look(spawn(m, 2, glowSquid, 16, true), c).layers().getFirst().model());
		assertEquals("minecraft:cod#main", EntityLooks.look(spawn(m, 3, cod), c).layers().getFirst().model());
		assertEquals("minecraft:salmon#main", EntityLooks.look(spawn(m, 4, salmon), c).layers().getFirst().model(), "default MEDIUM");
		assertEquals("minecraft:salmon_large#main", EntityLooks.look(spawn(m, 5, salmon, 17, 2), c).layers().getFirst().model());
		assertEquals("minecraft:pufferfish_small#main", EntityLooks.look(spawn(m, 6, pufferfish), c).layers().getFirst().model());
		assertEquals("minecraft:pufferfish_big#main", EntityLooks.look(spawn(m, 7, pufferfish, 17, 2), c).layers().getFirst().model());
		// Standard: KOB (klein, Muster 1), weiß/weiß
		EntityLooks.Look kob = EntityLooks.look(spawn(m, 8, tropical), c);
		assertEquals(List.of("entity/fish/tropical_a", "entity/fish/tropical_a_pattern_1"),
			kob.layers().stream().map(EntityLooks.Layer::texture).toList());
		assertEquals(0xFF000000 | EntityLooks.DYE[0], kob.layers().getFirst().color());
		// groß (1), Muster-Index 3 (BLOCKFISH), Grundfarbe 14 (rot), Musterfarbe 4 (gelb)
		EntityLooks.Look block = EntityLooks.look(spawn(m, 9, tropical, 17, 1 | (3 << 8) | (14 << 16) | (4 << 24)), c);
		assertEquals("minecraft:tropical_fish_large#pattern", block.layers().get(1).model());
		assertEquals("entity/fish/tropical_b_pattern_4", block.layers().get(1).texture());
		assertEquals(0xFF000000 | EntityLooks.DYE[14], block.layers().getFirst().color());
		assertEquals(0xFF000000 | EntityLooks.DYE[4], block.layers().get(1).color());
	}
}
