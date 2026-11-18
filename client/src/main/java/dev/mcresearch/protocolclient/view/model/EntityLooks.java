package dev.mcresearch.protocolclient.view.model;

import dev.mcresearch.protocolclient.entity.ClientEntity;
import dev.mcresearch.protocolclient.entity.EntityManager;
import dev.mcresearch.protocolclient.entity.EntityTypes;
import dev.mcresearch.protocolclient.entity.EntityVariants;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * M14 (docs/55 §5): Darstellungsplan einer Entity – welche Modell-Layer mit welchen Texturen, Farben und Zusatzschichten gezeichnet
 * werden. Eigene Zuordnung nach den Regeln der 26.3-Renderer (Varianten über die Register, eigene Baby-Modelle und -Texturen,
 * Halsband, Wolle, Markierungen, Sattel, Rüstung, Augen-Schichten). Ohne LWJGL und ohne Texturzugriff; der Renderer lädt die
 * genannten Texturen und fällt bei Fehlen auf die Debug-Box zurück.
 *
 * <p>Texturpfade sind relativ zu {@code assets/minecraft/textures/} und ohne Endung; {@code skin:<uuid>} verweist auf den
 * {@link SkinLookup Skin-Bestand}.
 */
public final class EntityLooks {
	/** Zeichenart einer Schicht. */
	public enum Kind {
		/** Normal, Alphatest. */
		CUTOUT,
		/** Leuchtende Augen ({@code RenderTypes.eyes}): volle Helligkeit, additiv. */
		EYES,
		/** Halbtransparent (aufgeladener Creeper). */
		TRANSLUCENT
	}

	/** Eine Schicht: Modell-Layer, Textur, Farbe (ARGB), Art. */
	public record Layer(String model, String texture, int color, Kind kind) {
		public Layer(final String model, final String texture) {
			this(model, texture, -1, Kind.CUTOUT);
		}
	}

	/**
	 * Darstellungsplan. {@code animator}: Name der Posenfunktion in {@link EntityAnimators}; {@code scaleX/Y/Z}: zusätzliche
	 * Skalierung des Renderers ({@code scale()}, z. B. Creeper, Höhlenspinne); {@code whiteOverlay}: Aufhellung (Creeper kurz vor
	 * der Explosion); {@code heldItems}: gehaltene Gegenstände an den Armen zeichnen; {@code deathFlip}: Kippwinkel beim Tod.
	 */
	public record Look(String animator, List<Layer> layers, float scaleX, float scaleY, float scaleZ, float whiteOverlay, boolean heldItems,
		float deathFlip, String carriedBlock) {
	}

	/** Skin eines Spielers (Phase E, {@code SkinManager}). */
	public interface SkinLookup {
		/** Texturschlüssel ({@code skin:…}) und Modell (schmal). */
		record Skin(String texture, boolean slim) {
		}

		Skin skin(UUID uuid, String name);

		/** M14: Skin eines Mannequins aus Profil und Skin-Vorgabe (Standard: Standardskin des Profils). */
		default Skin mannequin(final dev.mcresearch.protocolclient.protocol.packet.GameProfile.Resolvable p) {
			return skin(p == null || p.id() == null ? new UUID(0, 0) : p.id(), p == null ? null : p.name());
		}
	}

	/** Umgebung der Auflösung. {@code hat}: Hut-Angabe einer Dorfbewohnertextur ({@code full}, {@code partial}, {@code none}). */
	public record Context(EntityVariants variants, EntityVariants.Fallback fallback, long gameTime, java.util.function.Function<String, String> hat,
		SkinLookup skins, ItemRegistry items) {
	}

	/** Wollfarben je Farbindex 0–15 (Textur-Grundfarben der Farbstoffe, {@code DyeColor#getTextureDiffuseColor}). */
	public static final int[] DYE = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52, 0x9D9D97, 0x169C9C, 0x8932B8,
		0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};
	private static final String[] HORSE_COLORS = {"white", "creamy", "chestnut", "brown", "black", "gray", "darkbrown"};
	private static final String[] HORSE_MARKINGS = {null, "white", "whitefield", "whitedots", "blackdots"};
	private static final String[] WOOD = {"oak", "spruce", "birch", "jungle", "acacia", "cherry", "dark_oak", "pale_oak", "mangrove", "bamboo", "poplar"};

	private EntityLooks() {
	}

	/** Darstellungsplan oder null (Typ nicht umgesetzt → Debug-Box). */
	public static Look look(final EntityManager.View v, final Context c) {
		String type = v.type().shortName();
		boolean baby = v.baby();
		List<Layer> l = new ArrayList<>();
		switch (type) {
			case "pig" -> {
				EntityVariants.Variant var = variant(c, "minecraft:pig_variant", v.data().get(19));
				String model = baby ? "minecraft:pig_baby#main" : "cold".equals(model(var)) ? "minecraft:cold_pig#main" : "minecraft:pig#main";
				l.add(new Layer(model, tex(var, baby, "entity/pig/pig_temperate")));
				if (!baby && !empty(v.equipment()[ClientEntity.SLOT_SADDLE])) {
					l.add(new Layer("minecraft:pig#saddle", "entity/equipment/pig_saddle/saddle"));
				}
				return look("quadruped", l);
			}
			case "cow" -> {
				EntityVariants.Variant var = variant(c, "minecraft:cow_variant", v.data().get(18));
				String m = model(var);
				String prefix = "warm".equals(m) ? "warm_cow" : "cold".equals(m) ? "cold_cow" : "cow";
				l.add(new Layer("minecraft:" + prefix + (baby ? "_baby" : "") + "#main", tex(var, baby, "entity/cow/cow_temperate")));
				return look("quadruped", l);
			}
			case "sheep" -> {
				int wool = v.sheepWool();
				boolean sheared = (wool & 0x10) != 0;
				int color = 0xFF000000 | DYE[wool & 15];
				l.add(new Layer(baby ? "minecraft:sheep_baby#main" : "minecraft:sheep#main", baby ? "entity/sheep/sheep_baby" : "entity/sheep/sheep"));
				if (!baby && (wool & 15) != 0) {
					l.add(new Layer("minecraft:sheep#wool_undercoat", "entity/sheep/sheep_wool_undercoat", color, Kind.CUTOUT));
				}
				if (!sheared) {
					l.add(new Layer(baby ? "minecraft:sheep_baby#wool" : "minecraft:sheep#wool", baby ? "entity/sheep/sheep_wool_baby" : "entity/sheep/sheep_wool",
						color, Kind.CUTOUT));
				}
				return look("sheep", l);
			}
			case "chicken" -> {
				EntityVariants.Variant var = variant(c, "minecraft:chicken_variant", v.data().get(18));
				String model = baby ? "minecraft:chicken_baby#main" : "cold".equals(model(var)) ? "minecraft:cold_chicken#main" : "minecraft:chicken#main";
				l.add(new Layer(model, tex(var, baby, "entity/chicken/chicken_temperate")));
				return look("chicken", l);
			}
			case "wolf" -> {
				EntityVariants.Variant var = variant(c, "minecraft:wolf_variant", v.data().get(23));
				int flags = byteData(v, 18);
				boolean tame = (flags & 0x04) != 0;
				boolean angry = angry(v, 22, c);
				String texture;
				if (var == null) {
					texture = "entity/wolf/wolf" + (tame ? "_tame" : angry ? "_angry" : "") + (baby ? "_baby" : "");
				} else if (baby) {
					texture = tame ? var.babyTame() : angry ? var.babyAngry() : var.babyWild();
				} else {
					texture = tame ? var.tame() : angry ? var.angry() : var.wild();
				}
				l.add(new Layer(baby ? "minecraft:wolf_baby#main" : "minecraft:wolf#main", texture));
				if (tame) {
					l.add(new Layer(baby ? "minecraft:wolf_baby#main" : "minecraft:wolf#main", baby ? "entity/wolf/wolf_collar_baby" : "entity/wolf/wolf_collar",
						0xFF000000 | DYE[Math.floorMod(intData(v, 21, 14), 16)], Kind.CUTOUT));
				}
				return look("wolf", l);
			}
			case "cat" -> {
				EntityVariants.Variant var = variant(c, "minecraft:cat_variant", v.data().get(20));
				l.add(new Layer(baby ? "minecraft:cat_baby#main" : "minecraft:cat#main", tex(var, baby, "entity/cat/cat_tabby")));
				if ((byteData(v, 18) & 0x04) != 0) {
					l.add(new Layer(baby ? "minecraft:cat_baby#collar" : "minecraft:cat#collar", baby ? "entity/cat/cat_collar_baby" : "entity/cat/cat_collar",
						0xFF000000 | DYE[Math.floorMod(intData(v, 23, 14), 16)], Kind.CUTOUT));
				}
				return look("feline", l);
			}
			case "ocelot" -> {
				l.add(new Layer(baby ? "minecraft:ocelot_baby#main" : "minecraft:ocelot#main", baby ? "entity/cat/ocelot_baby" : "entity/cat/ocelot"));
				return look("feline", l);
			}
			case "horse" -> {
				int variant = intData(v, 19, 0);
				String color = HORSE_COLORS[Math.floorMod(variant & 0xFF, HORSE_COLORS.length)];
				String marking = HORSE_MARKINGS[Math.floorMod((variant >> 8) & 0xFF, HORSE_MARKINGS.length)];
				String model = baby ? "minecraft:horse_baby#main" : "minecraft:horse#main";
				String suffix = baby ? "_baby" : "";
				l.add(new Layer(model, "entity/horse/horse_" + color + suffix));
				if (marking != null) {
					l.add(new Layer(model, "entity/horse/horse_markings_" + marking + suffix));
				}
				equine(v, c, l, "horse", baby);
				return look(baby ? "horse_baby" : "horse", l);
			}
			case "donkey", "mule" -> {
				l.add(new Layer("minecraft:" + type + (baby ? "_baby" : "") + "#main", "entity/horse/" + type + (baby ? "_baby" : "")));
				equine(v, c, l, type, baby);
				return look(baby ? "donkey_baby" : "donkey", l);
			}
			case "rabbit" -> {
				String name = v.customName();
				String variant = switch (intData(v, 18, 0)) {
					case 1 -> "white";
					case 2 -> "black";
					case 3 -> "white_splotched";
					case 4 -> "gold";
					case 5 -> "salt";
					case 99 -> "caerbannog";
					default -> "brown";
				};
				if (name != null && name.equals("Toast")) {
					variant = "toast";
				}
				l.add(new Layer(baby ? "minecraft:rabbit_baby#main" : "minecraft:rabbit#main", "entity/rabbit/rabbit_" + variant + (baby ? "_baby" : "")));
				return look(baby ? "rabbit_baby" : "rabbit", l);
			}
			case "bee" -> {
				int flags = byteData(v, 18);
				boolean nectar = (flags & 0x08) != 0;
				boolean angry = angry(v, 19, c);
				l.add(new Layer(baby ? "minecraft:bee_baby#main" : "minecraft:bee#main", "entity/bee/bee" + (angry ? "_angry" : "") + (nectar ? "_nectar" : "")
					+ (baby ? "_baby" : "")));
				return look("bee", l);
			}
			case "creeper" -> {
				l.add(new Layer("minecraft:creeper#main", "entity/creeper/creeper"));
				if (Boolean.TRUE.equals(v.data().get(17))) {
					l.add(new Layer("minecraft:creeper#armor", "entity/creeper/creeper_armor", 0x80FFFFFF, Kind.TRANSLUCENT));
				}
				float g = v.anim().swell();
				float wobble = 1.0F + (float) Math.sin(g * 100.0F) * g * 0.01F;
				g = Math.clamp(g, 0.0F, 1.0F);
				g *= g;
				g *= g;
				float s = (1.0F + g * 0.4F) * wobble;
				float hs = (1.0F + g * 0.1F) / wobble;
				float step = v.anim().swell();
				float white = (int) (step * 10.0F) % 2 == 0 ? 0.0F : Math.clamp(step, 0.5F, 1.0F);
				return new Look("creeper", List.copyOf(l), s, hs, s, white, false, 90, null);
			}
			case "spider", "cave_spider" -> {
				l.add(new Layer("minecraft:spider#main", "entity/spider/" + type));
				l.add(new Layer("minecraft:spider#main", "entity/spider/spider_eyes", -1, Kind.EYES));
				float s = type.equals("cave_spider") ? 0.7F : 1.0F;
				return new Look("spider", List.copyOf(l), s, s, s, 0, false, 180, null);
			}
			case "enderman" -> {
				l.add(new Layer("minecraft:enderman#main", "entity/enderman/enderman"));
				l.add(new Layer("minecraft:enderman#main", "entity/enderman/enderman_eyes", -1, Kind.EYES));
				Object carried = v.data().get(16);
				String block = carried instanceof Integer id && id > 0 ? "state:" + id : null;
				return new Look("enderman", List.copyOf(l), 1, 1, 1, 0, false, 90, block);
			}
			case "villager" -> {
				villager(v, c, l, baby);
				return look("villager", l);
			}
			case "iron_golem" -> {
				l.add(new Layer("minecraft:iron_golem#main", "entity/iron_golem/iron_golem"));
				Float health = v.health();
				Double max = v.attributes().get(ClientEntity.ATTRIBUTE_MAX_HEALTH);
				float ratio = health == null ? 1 : health / (float) (max == null ? 100.0 : max);
				String crack = ratio < 0.25F ? "high" : ratio < 0.5F ? "medium" : ratio < 0.75F ? "low" : null;
				if (crack != null) {
					l.add(new Layer("minecraft:iron_golem#main", "entity/iron_golem/iron_golem_crackiness_" + crack));
				}
				return look("iron_golem", l);
			}
			case "zombie", "drowned" -> {
				String model = "minecraft:" + type + (baby ? "_baby" : "");
				l.add(new Layer(model + "#main", "entity/zombie/" + type + (baby ? "_baby" : "")));
				if (type.equals("drowned")) {
					l.add(new Layer(model + "#outer", "entity/zombie/drowned_outer_layer" + (baby ? "_baby" : "")));
				}
				armor(v, c, l, model);
				return new Look(type.equals("drowned") ? "drowned" : "zombie", List.copyOf(l), 1, 1, 1, 0, true, 90, null);
			}
			case "skeleton" -> {
				l.add(new Layer("minecraft:skeleton#main", "entity/skeleton/skeleton"));
				armor(v, c, l, "minecraft:skeleton");
				return new Look("skeleton", List.copyOf(l), 1, 1, 1, 0, true, 90, null);
			}
			case "player", "mannequin" -> {
				SkinLookup.Skin skin = c.skins() == null ? null : type.equals("mannequin")
					? c.skins().mannequin(v.data().get(17) instanceof dev.mcresearch.protocolclient.protocol.packet.GameProfile.Resolvable r ? r : null)
					: c.skins().skin(v.uuid(), v.playerName());
				if (skin == null) {
					return null;
				}
				String model = skin.slim() ? "minecraft:player_slim" : "minecraft:player";
				l.add(new Layer(model + "#main", skin.texture()));
				armor(v, c, l, model);
				return new Look("player", List.copyOf(l), 0.9375F, 0.9375F, 0.9375F, 0, true, 90, null);
			}
			default -> {
				String boat = boatWood(type);
				if (boat != null) {
					boolean chest = type.endsWith("chest_boat") || type.endsWith("chest_raft");
					String kind = chest ? "chest_boat" : "boat";
					l.add(new Layer("minecraft:" + kind + "/" + boat + "#main", "entity/" + kind + "/" + boat));
					return look("boat", l);
				}
				if (type.equals("minecart") || type.endsWith("_minecart")) {
					l.add(new Layer("minecraft:minecart#main", "entity/minecart/minecart"));
					String block = switch (type) {
						case "chest_minecart" -> "block:chest";
						case "furnace_minecart" -> "block:furnace";
						case "tnt_minecart" -> "block:tnt";
						case "hopper_minecart" -> "block:hopper";
						case "spawner_minecart" -> "block:spawner";
						case "command_block_minecart" -> "block:command_block";
						default -> null;
					};
					Object custom = v.data().get(11);
					if (custom instanceof Integer id && id > 0) {
						block = "state:" + id;
					}
					return new Look("minecart", List.copyOf(l), 1, 1, 1, 0, false, 0, block);
				}
				return null;
			}
		}
	}

	/** Holzart eines Boot-Typs ({@code oak_boat}, {@code bamboo_chest_raft} …) oder null. */
	public static String boatWood(final String type) {
		for (String w : WOOD) {
			if (type.equals(w + "_boat") || type.equals(w + "_chest_boat") || type.equals(w + "_raft") || type.equals(w + "_chest_raft")) {
				return w;
			}
		}
		return null;
	}

	public static boolean isBoat(final String type) {
		return boatWood(type) != null;
	}

	public static boolean isMinecart(final String type) {
		return type.equals("minecart") || type.endsWith("_minecart");
	}

	private static Look look(final String animator, final List<Layer> l) {
		return new Look(animator, List.copyOf(l), 1, 1, 1, 0, false, 90, null);
	}

	private static void equine(final EntityManager.View v, final Context c, final List<Layer> l, final String type, final boolean baby) {
		if (baby) {
			return;
		}
		ItemStack saddle = v.equipment()[ClientEntity.SLOT_SADDLE];
		if (!empty(saddle)) {
			l.add(new Layer("minecraft:" + type + "#saddle", "entity/equipment/" + type + "_saddle/saddle"));
		}
		ItemStack body = v.equipment()[ClientEntity.SLOT_BODY];
		if (type.equals("horse") && !empty(body) && c.items() != null) {
			String n = c.items().name(body.itemId()).replace("minecraft:", "");
			if (n.endsWith("_horse_armor")) {
				String mat = n.substring(0, n.length() - "_horse_armor".length());
				l.add(new Layer("minecraft:horse_armor#main", "entity/equipment/horse_body/" + (mat.equals("golden") ? "gold" : mat)));
			}
		}
	}

	private static void villager(final EntityManager.View v, final Context c, final List<Layer> l, final boolean baby) {
		String model = baby ? "minecraft:villager_baby#main" : "minecraft:villager#main";
		String noHat = baby ? "minecraft:villager_baby_no_hat#main" : "minecraft:villager_no_hat#main";
		l.add(new Layer(model, baby ? "entity/villager/villager_baby" : "entity/villager/villager"));
		int[] data = v.data().get(19) instanceof int[] d && d.length == 3 ? d : null;
		if (data == null) {
			return;
		}
		String type = EntityTypes.get().registryName("villager_type", data[0]);
		String prof = EntityTypes.get().registryName("villager_profession", data[1]);
		if (type == null) {
			return;
		}
		String typeTex = "entity/villager/" + (baby ? "baby" : "type") + "/" + type;
		String profTex = prof == null || prof.equals("none") || baby ? null : "entity/villager/profession/" + prof;
		String typeHat = c.hat() == null ? "none" : c.hat().apply("entity/villager/type/" + type);
		String profHat = profTex == null || c.hat() == null ? "none" : c.hat().apply(profTex);
		boolean typeHatVisible = profHat.equals("none") || profHat.equals("partial") && !typeHat.equals("full");
		l.add(new Layer(typeHatVisible ? model : noHat, typeTex));
		if (profTex != null) {
			l.add(new Layer(model, profTex));
			if (!prof.equals("nitwit")) {
				String level = switch (Math.clamp(data[2], 1, 5)) {
					case 1 -> "stone";
					case 2 -> "iron";
					case 3 -> "gold";
					case 4 -> "emerald";
					default -> "diamond";
				};
				l.add(new Layer(model, "entity/villager/profession_level/" + level));
			}
		}
	}

	/** Rüstungsschichten ({@code HumanoidArmorLayer}): je Slot eigener Layer {@code <modell>#helmet …} mit der Materialtextur. */
	private static void armor(final EntityManager.View v, final Context c, final List<Layer> l, final String model) {
		if (c.items() == null) {
			return;
		}
		int[] slots = {ClientEntity.SLOT_CHEST, ClientEntity.SLOT_FEET, ClientEntity.SLOT_LEGS, ClientEntity.SLOT_HEAD};
		String[] layers = {"chestplate", "boots", "leggings", "helmet"};
		for (int i = 0; i < slots.length; i++) {
			String mat = armorMaterial(c.items(), v.equipment()[slots[i]]);
			if (mat == null) {
				continue;
			}
			String dir = layers[i].equals("leggings") ? "humanoid_leggings" : "humanoid";
			l.add(new Layer(model + "#" + layers[i], "entity/equipment/" + dir + "/" + mat));
		}
	}

	/** Material aus dem Itemnamen ({@code iron_helmet} → {@code iron}); null für Nicht-Rüstung. */
	public static String armorMaterial(final ItemRegistry items, final ItemStack s) {
		if (empty(s)) {
			return null;
		}
		String n = items.name(s.itemId()).replace("minecraft:", "");
		if (n.equals("turtle_helmet")) {
			return "turtle_scute";
		}
		for (String suffix : new String[]{"_helmet", "_chestplate", "_leggings", "_boots"}) {
			if (n.endsWith(suffix)) {
				String m = n.substring(0, n.length() - suffix.length());
				return m.equals("golden") ? "gold" : m;
			}
		}
		return null;
	}

	private static boolean empty(final ItemStack s) {
		return s == null || s.isEmpty();
	}

	private static EntityVariants.Variant variant(final Context c, final String registry, final Object id) {
		if (c.variants() == null || !(id instanceof Integer i)) {
			return null;
		}
		return c.variants().variant(registry, i, c.fallback());
	}

	private static String model(final EntityVariants.Variant v) {
		return v == null ? "normal" : v.model().toLowerCase(Locale.ROOT);
	}

	private static String tex(final EntityVariants.Variant v, final boolean baby, final String fallback) {
		if (v != null) {
			String t = baby ? v.babyAsset() : v.asset();
			if (t != null) {
				return t;
			}
		}
		return baby ? fallback + "_baby" : fallback;
	}

	private static int byteData(final EntityManager.View v, final int index) {
		return v.data().get(index) instanceof Byte b ? b & 0xFF : 0;
	}

	private static int intData(final EntityManager.View v, final int index, final int def) {
		return v.data().get(index) instanceof Integer i ? i : def;
	}

	/** Wütend ({@code NeutralMob}): Ende der Wut (Spielzeit, long) liegt in der Zukunft. */
	private static boolean angry(final EntityManager.View v, final int index, final Context c) {
		return v.data().get(index) instanceof Long end && end > 0 && (c.gameTime() < 0 || end > c.gameTime());
	}
}
