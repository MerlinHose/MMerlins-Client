package dev.mcresearch.protocolclient.protocol.codec;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.nbt.NbtReader;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ItemStack.OPTIONAL_STREAM_CODEC} (docs/28 §3): VarInt Anzahl (≤ 0 = leer), VarInt Item-ID,
 * {@code DataComponentPatch.STREAM_CODEC}: VarInt Anzahl gesetzter, VarInt Anzahl entfernter Typen, dann je gesetztem Typ
 * VarInt Typ-ID und der Wert im Typ-eigenen Codec – <b>ohne Längenpräfix</b>. Ein unbekannter Werttyp lässt sich daher
 * nicht überspringen: {@link UnsupportedComponentException}, der Aufrufer verwirft das ganze Paket kontrolliert (die
 * Paketgrenze ist durch die Rahmung bekannt). Komponenten ohne eigenen Netzwerk-Codec überträgt der Server als NBT
 * ({@code DataComponentType.Builder#build}: {@code fromCodecWithRegistries}).
 */
public final class ItemStackCodec {
	/** Komponente, deren Netzwerk-Codec der Client nicht kennt. */
	public static final class UnsupportedComponentException extends Exception {
		@Serial
		private static final long serialVersionUID = 1L;

		public UnsupportedComponentException(final String message) {
			super(message);
		}
	}

	private enum Kind {
		VAR_INT, UNIT, BOOL, FLOAT, IDENTIFIER, NBT, NBT_LIST_256, ENCHANTMENTS
	}

	/** Unterstützte Komponenten; Codecs aus {@code DataComponents} (26.3) bestimmt, docs/28 §3. */
	private static final Map<String, Kind> KINDS = new HashMap<>();

	static {
		for (String n : List.of("max_stack_size", "max_damage", "damage", "repair_cost", "rarity", "additional_trade_cost")) {
			KINDS.put("minecraft:" + n, Kind.VAR_INT);
		}
		for (String n : List.of("unbreakable", "creative_slot_lock", "glider", "waxed")) {
			KINDS.put("minecraft:" + n, Kind.UNIT);
		}
		KINDS.put("minecraft:enchantment_glint_override", Kind.BOOL);
		KINDS.put("minecraft:minimum_attack_charge", Kind.FLOAT);
		KINDS.put("minecraft:potion_duration_scale", Kind.FLOAT);
		for (String n : List.of("item_model", "tooltip_style", "note_block_sound")) {
			KINDS.put("minecraft:" + n, Kind.IDENTIFIER);
		}
		// ComponentSerialization.STREAM_CODEC (NBT) und die Komponenten ohne eigenen Netzwerk-Codec (NBT-Rückfall)
		for (String n : List.of("custom_name", "item_name", "custom_data", "intangible_projectile", "map_decorations", "debug_stick_state", "recipes",
			"lock", "container_loot")) {
			KINDS.put("minecraft:" + n, Kind.NBT);
		}
		KINDS.put("minecraft:lore", Kind.NBT_LIST_256);
		KINDS.put("minecraft:enchantments", Kind.ENCHANTMENTS);
		KINDS.put("minecraft:stored_enchantments", Kind.ENCHANTMENTS);
	}

	private ItemStackCodec() {
	}

	public static boolean supported(final String component) {
		return KINDS.containsKey(component);
	}

	public static ItemStack read(final PacketReader r, final ItemRegistry items) throws ProtocolException, UnsupportedComponentException {
		int count = r.readVarInt();
		if (count <= 0) {
			return ItemStack.EMPTY;
		}
		int item = r.readVarInt();
		if (!items.isValid(item)) {
			throw new ProtocolException("unknown item id " + item);
		}
		int positive = r.readVarInt();
		int negative = r.readVarInt();
		if (positive < 0 || negative < 0 || positive + negative > 256) {
			throw new ProtocolException("component patch sizes " + positive + "/" + negative);
		}
		Map<String, Object> set = new LinkedHashMap<>();
		for (int i = 0; i < positive; i++) {
			String name = componentName(r, items);
			Kind kind = KINDS.get(name);
			if (kind == null) {
				throw new UnsupportedComponentException(name + " on " + items.name(item));
			}
			set.put(name, value(r, kind, name));
		}
		Set<String> removed = new HashSet<>();
		for (int i = 0; i < negative; i++) {
			removed.add(componentName(r, items));
		}
		return new ItemStack(item, count, set, removed);
	}

	private static String componentName(final PacketReader r, final ItemRegistry items) throws ProtocolException {
		int type = r.readVarInt();
		String name = items.componentName(type);
		if (name == null) {
			throw new ProtocolException("unknown data component type " + type);
		}
		return name;
	}

	private static Object value(final PacketReader r, final Kind kind, final String name) throws ProtocolException {
		return switch (kind) {
			case VAR_INT -> r.readVarInt();
			case UNIT -> ItemStack.UNIT;
			case BOOL -> r.readBoolean();
			case FLOAT -> r.readFloat();
			case IDENTIFIER -> r.readIdentifier();
			case NBT -> nonNull(NbtReader.readNetworkTag(r), name);
			case NBT_LIST_256 -> {
				int n = r.readVarInt();
				if (n < 0 || n > 256) {
					throw new ProtocolException(name + ": " + n + " entries");
				}
				List<Object> lines = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					lines.add(nonNull(NbtReader.readNetworkTag(r), name));
				}
				yield List.copyOf(lines);
			}
			case ENCHANTMENTS -> {
				// ByteBufCodecs.map(Enchantment.STREAM_CODEC (Register-ID), VAR_INT Stufe)
				int n = r.readVarInt();
				if (n < 0 || n > 1024) {
					throw new ProtocolException(name + ": " + n + " entries");
				}
				Map<Integer, Integer> levels = new LinkedHashMap<>();
				for (int i = 0; i < n; i++) {
					levels.put(r.readVarInt(), r.readVarInt());
				}
				yield Map.copyOf(levels);
			}
		};
	}

	private static Object nonNull(final Object o, final String name) throws ProtocolException {
		if (o == null) {
			throw new ProtocolException(name + ": empty tag");
		}
		return o;
	}

	/**
	 * {@code ItemStack.OPTIONAL_UNTRUSTED_STREAM_CODEC} (serverbound, z. B. {@code set_creative_mode_slot}): wie oben, aber jeder
	 * Komponentenwert mit VarInt-Längenpräfix. Der Client schreibt nur Stapel ohne Patch.
	 */
	public static void writeWithoutComponents(final PacketWriter w, final ItemStack stack) {
		if (stack.isEmpty()) {
			w.writeVarInt(0);
			return;
		}
		if (!stack.components().isEmpty() || !stack.removed().isEmpty()) {
			throw new IllegalArgumentException("writing component patches is not supported");
		}
		w.writeVarInt(stack.count());
		w.writeVarInt(stack.itemId());
		w.writeVarInt(0);
		w.writeVarInt(0);
	}
}
