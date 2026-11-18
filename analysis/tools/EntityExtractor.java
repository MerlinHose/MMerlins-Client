import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;

/**
 * Analysewerkzeug (M9, docs/34): liest mit dem unveränderten Server-JAR Fakten aus, die der eigene Client als Daten braucht.
 * Es wird kein Mojang-Code übernommen, nur Zahlen ausgegeben.
 *  T id name width height eyeHeight fixed trackingRange updateInterval trackDeltas category
 *  D class fieldName index serializerId  (statische EntityDataAccessor-Felder je Klasse; Index = Netzindex in set_entity_data)
 * java -cp "<server-26.3.jar>;<libraries>" analysis/tools/EntityExtractor.java > entities-777.txt
 */
public final class EntityExtractor {
	private static final String[] CLASSES = {
		"net.minecraft.world.entity.Entity", "net.minecraft.world.entity.LivingEntity", "net.minecraft.world.entity.Mob",
		"net.minecraft.world.entity.PathfinderMob", "net.minecraft.world.entity.AgeableMob", "net.minecraft.world.entity.animal.Animal",
		"net.minecraft.world.entity.animal.pig.Pig", "net.minecraft.world.entity.animal.cow.AbstractCow", "net.minecraft.world.entity.animal.cow.Cow",
		"net.minecraft.world.entity.animal.sheep.Sheep", "net.minecraft.world.entity.monster.Monster", "net.minecraft.world.entity.monster.zombie.Zombie",
		"net.minecraft.world.entity.monster.skeleton.AbstractSkeleton", "net.minecraft.world.entity.monster.skeleton.Skeleton",
		"net.minecraft.world.entity.Avatar", "net.minecraft.world.entity.player.Player", "net.minecraft.world.entity.item.ItemEntity",
		// M14 (docs/54 §3): weitere Mobs, Reittiere und Fahrzeuge
		"net.minecraft.world.entity.TamableAnimal", "net.minecraft.world.entity.animal.chicken.Chicken", "net.minecraft.world.entity.animal.wolf.Wolf",
		"net.minecraft.world.entity.animal.feline.Cat", "net.minecraft.world.entity.animal.feline.Ocelot", "net.minecraft.world.entity.animal.equine.AbstractHorse",
		"net.minecraft.world.entity.animal.equine.Horse", "net.minecraft.world.entity.animal.equine.AbstractChestedHorse", "net.minecraft.world.entity.animal.rabbit.Rabbit",
		"net.minecraft.world.entity.animal.bee.Bee", "net.minecraft.world.entity.monster.Creeper", "net.minecraft.world.entity.monster.spider.Spider",
		"net.minecraft.world.entity.monster.Enderman", "net.minecraft.world.entity.npc.villager.AbstractVillager", "net.minecraft.world.entity.npc.villager.Villager",
		"net.minecraft.world.entity.animal.golem.IronGolem", "net.minecraft.world.entity.monster.zombie.Drowned", "net.minecraft.world.entity.vehicle.VehicleEntity",
		"net.minecraft.world.entity.vehicle.boat.AbstractBoat", "net.minecraft.world.entity.vehicle.minecart.AbstractMinecart",
		"net.minecraft.world.entity.vehicle.minecart.MinecartFurnace",
		"net.minecraft.world.entity.decoration.Mannequin", "net.minecraft.world.entity.decoration.ArmorStand"
	};

	/** Ankerpunkte ({@code EntityAttachments}): je Art und Index ein Punkt relativ zur Position (vor Drehung). */
	@SuppressWarnings("unchecked")
	private static void attachments(final String prefix, final EntityDimensions d) throws ReflectiveOperationException {
		Field f = net.minecraft.world.entity.EntityAttachments.class.getDeclaredField("attachments");
		f.setAccessible(true);
		var map = (java.util.Map<net.minecraft.world.entity.EntityAttachment, java.util.List<net.minecraft.world.phys.Vec3>>) f.get(d.attachments());
		for (net.minecraft.world.entity.EntityAttachment a : net.minecraft.world.entity.EntityAttachment.values()) {
			java.util.List<net.minecraft.world.phys.Vec3> pts = map.get(a);
			for (int i = 0; pts != null && i < pts.size(); i++) {
				System.out.println(prefix + "\t" + a.name() + "\t" + i + "\t" + pts.get(i).x + "\t" + pts.get(i).y + "\t" + pts.get(i).z);
			}
		}
	}

	public static void main(final String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		System.out.println("# Entity-Typen und synchronisierte Datenfelder Minecraft 26.3 / Protokoll 777 - Quelle: EntityExtractor (Server-JAR, Laufzeit)");
		for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
			EntityDimensions d = t.getDimensions();
			System.out.println("T\t" + BuiltInRegistries.ENTITY_TYPE.getId(t) + "\t" + BuiltInRegistries.ENTITY_TYPE.getKey(t) + "\t" + d.width() + "\t" + d.height()
				+ "\t" + d.eyeHeight() + "\t" + d.fixed() + "\t" + t.clientTrackingRange() + "\t" + t.updateInterval() + "\t" + t.trackDeltas()
				+ "\t" + t.getCategory().getName());
			attachments("X\t" + BuiltInRegistries.ENTITY_TYPE.getKey(t), d);
		}
		// M14 (docs/55 §5): feste Register der Dorfbewohnerdaten (villager_data: Typ, Beruf, Stufe)
		for (int i = 0; i < BuiltInRegistries.VILLAGER_TYPE.size(); i++) { // Reihenfolge der IDs (deterministisch)
			System.out.println("R\tvillager_type\t" + i + "\t" + BuiltInRegistries.VILLAGER_TYPE.getKey(BuiltInRegistries.VILLAGER_TYPE.byId(i)));
		}
		for (int i = 0; i < BuiltInRegistries.VILLAGER_PROFESSION.size(); i++) {
			System.out.println("R\tvillager_profession\t" + i + "\t" + BuiltInRegistries.VILLAGER_PROFESSION.getKey(BuiltInRegistries.VILLAGER_PROFESSION.byId(i)));
		}
		// M14 (docs/57 §2): eigene Baby-Maße (statische EntityDimensions-Felder mit BABY im Namen) samt Ankerpunkten
		for (String name : CLASSES) {
			Class<?> c = Class.forName(name);
			for (Field f : c.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers()) && f.getType() == EntityDimensions.class && f.getName().contains("BABY")) {
					f.setAccessible(true);
					EntityDimensions b = (EntityDimensions) f.get(null);
					System.out.println("B\t" + c.getSimpleName() + "\t" + f.getName() + "\t" + b.width() + "\t" + b.height() + "\t" + b.eyeHeight());
					attachments("XB\t" + c.getSimpleName(), b);
				}
			}
		}
		for (String name : CLASSES) {
			Class<?> c = Class.forName(name);
			for (Field f : c.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers()) && f.getType() == EntityDataAccessor.class) {
					f.setAccessible(true);
					EntityDataAccessor<?> a = (EntityDataAccessor<?>) f.get(null);
					System.out.println("D\t" + c.getSimpleName() + "\t" + f.getName() + "\t" + a.id() + "\t" + EntityDataSerializers.getSerializedId(a.serializer()));
				}
			}
		}
	}
}
