import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.HashOps;
import net.minecraft.util.Unit;
import net.minecraft.world.item.Rarity;

/**
 * Analysewerkzeug (M8, docs/30 §6): berechnet mit dem unveränderten Server-JAR die Komponenten-Hashes, die der Vanilla-Client
 * im HashedStack sendet (persistenter Codec → HashOps.CRC32C_INSTANCE → asInt), als Referenz für ComponentHashTest.
 * java -cp "<server-26.3.jar>;<libraries>" analysis/tools/HashCheck.java
 */
public final class HashCheck {
	public static void main(final String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		print("damage", DataComponents.DAMAGE, 7);
		print("damage", DataComponents.DAMAGE, 0);
		print("max_damage", DataComponents.MAX_DAMAGE, 59);
		print("repair_cost", DataComponents.REPAIR_COST, 3);
		print("max_stack_size", DataComponents.MAX_STACK_SIZE, 16);
		print("enchantment_glint_override", DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		print("enchantment_glint_override", DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
		print("unbreakable", DataComponents.UNBREAKABLE, Unit.INSTANCE);
		print("rarity", DataComponents.RARITY, Rarity.EPIC);
		print("item_model", DataComponents.ITEM_MODEL, net.minecraft.resources.Identifier.parse("minecraft:stick"));
	}

	private static <T> void print(final String name, final DataComponentType<T> type, final T value) {
		int hash = type.codecOrThrow().encodeStart(HashOps.CRC32C_INSTANCE, value).getOrThrow().asInt();
		System.out.println("HASH " + name + " " + value + " " + hash);
	}
}
