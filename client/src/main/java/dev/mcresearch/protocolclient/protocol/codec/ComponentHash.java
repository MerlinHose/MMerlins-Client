package dev.mcresearch.protocolclient.protocol.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalInt;
import java.util.zip.CRC32C;

/**
 * Hash eines Datenkomponentenwerts für {@code HashedStack}/{@code HashedPatchMap} (M8, docs/30 §6): Der Vanilla-Client kodiert
 * den Wert mit dem persistenten Codec in {@code HashOps.CRC32C_INSTANCE} und sendet {@code HashCode#asInt}. Das Verfahren ist
 * hier nur für einfache Werte nachgebildet (Typ-Tag + Wert, Guava-Hasher schreiben little-endian):
 * <pre>
 *   int     CRC32C([8, v₀, v₁, v₂, v₃])            bool  CRC32C([13, 0|1])
 *   Unit    CRC32C([2, 3])  (leere Map)             String CRC32C([12, len (int LE), UTF-16LE-Zeichen])
 * </pre>
 * Für alle anderen Komponenten liefert {@link #of} nichts; der Client sendet dann einen bewusst unbekannten Hash. Der Server
 * hält den Stapel dann für abweichend und sendet ihn einmal zurück – der tatsächliche Zustand bleibt unberührt, weil der
 * Server den Klick immer selbst ausführt.
 */
public final class ComponentHash {
	/** Hash, der sicher nicht passt (CRC32C liefert ihn für keinen der obigen Werte). */
	public static final int UNKNOWN = 0;

	private ComponentHash() {
	}

	public static OptionalInt of(final String component, final Object value) {
		return switch (component) {
			case "minecraft:damage", "minecraft:max_damage", "minecraft:max_stack_size", "minecraft:repair_cost", "minecraft:additional_trade_cost" ->
				value instanceof Integer i ? OptionalInt.of(intHash(i)) : OptionalInt.empty();
			case "minecraft:enchantment_glint_override" -> value instanceof Boolean b ? OptionalInt.of(crc(new byte[]{13, (byte) (b ? 1 : 0)}))
				: OptionalInt.empty();
			case "minecraft:unbreakable", "minecraft:creative_slot_lock", "minecraft:glider", "minecraft:waxed" -> OptionalInt.of(crc(new byte[]{2, 3}));
			case "minecraft:item_model", "minecraft:tooltip_style", "minecraft:note_block_sound" ->
				value instanceof String s ? OptionalInt.of(stringHash(s)) : OptionalInt.empty();
			case "minecraft:rarity" -> value instanceof Integer i && i >= 0 && i <= 3
				? OptionalInt.of(stringHash(new String[]{"common", "uncommon", "rare", "epic"}[i])) : OptionalInt.empty();
			default -> OptionalInt.empty();
		};
	}

	static int intHash(final int v) {
		return crc(ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte) 8).putInt(v).array());
	}

	static int stringHash(final String s) {
		ByteBuffer b = ByteBuffer.allocate(5 + 2 * s.length()).order(ByteOrder.LITTLE_ENDIAN).put((byte) 12).putInt(s.length());
		for (int i = 0; i < s.length(); i++) {
			b.putChar(s.charAt(i));
		}
		return crc(b.array());
	}

	private static int crc(final byte[] data) {
		CRC32C c = new CRC32C();
		c.update(data);
		return (int) c.getValue();
	}
}
