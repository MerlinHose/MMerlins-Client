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
		"net.minecraft.world.entity.decoration.ArmorStand"
	};

	public static void main(final String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		System.out.println("# Entity-Typen und synchronisierte Datenfelder Minecraft 26.3 / Protokoll 777 - Quelle: EntityExtractor (Server-JAR, Laufzeit)");
		for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
			EntityDimensions d = t.getDimensions();
			System.out.println("T\t" + BuiltInRegistries.ENTITY_TYPE.getId(t) + "\t" + BuiltInRegistries.ENTITY_TYPE.getKey(t) + "\t" + d.width() + "\t" + d.height()
				+ "\t" + d.eyeHeight() + "\t" + d.fixed() + "\t" + t.clientTrackingRange() + "\t" + t.updateInterval() + "\t" + t.trackDeltas()
				+ "\t" + t.getCategory().getName());
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
