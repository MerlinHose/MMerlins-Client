package dev.mcresearch.protocolclient.protocol.packet;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.codec.ItemStackCodec;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.nbt.NbtReader;
import dev.mcresearch.protocolclient.protocol.nbt.TextComponents;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dekodierung der Entity-Pakete (M9, Formate nach docs/34). Eigenständig nach der Feldbeschreibung geschrieben.
 */
final class EntityPacketDecoder {
	/** Höchstzahl Schritte je VecDelta/PositionPath; der Server erzeugt einen Schritt je Tick seit der letzten Sendung. */
	static final int MAX_STEPS = 64;
	static final int MAX_REMOVE = 65536;

	private EntityPacketDecoder() {
	}

	/** Liefert das Paket oder {@code null}, wenn {@code name} kein Entity-Paket ist. */
	static Clientbound decode(final String name, final PacketReader r) throws ProtocolException {
		return switch (name) {
			case "minecraft:add_entity" -> addEntity(r);
			case "minecraft:remove_entities" -> {
				int n = r.readCount(MAX_REMOVE, 1, "remove_entities");
				int[] ids = new int[n];
				for (int i = 0; i < n; i++) {
					ids[i] = r.readVarInt();
				}
				yield new Clientbound.RemoveEntities(ids);
			}
			case "minecraft:move_entity_pos" -> move(r, true, false);
			case "minecraft:move_entity_pos_rot" -> move(r, true, true);
			case "minecraft:move_entity_rot" -> {
				int id = r.readVarInt();
				boolean onGround = r.readBoolean();
				float yRot = angle(r.readByte());
				float xRot = angle(r.readByte());
				yield new Clientbound.MoveEntity(id, false, true, false, List.of(), yRot, xRot, onGround);
			}
			case "minecraft:entity_position_sync" -> positionSync(r);
			case "minecraft:teleport_entity" -> {
				int id = r.readVarInt();
				double x = finite(r.readDouble());
				double y = finite(r.readDouble());
				double z = finite(r.readDouble());
				double dx = finite(r.readDouble());
				double dy = finite(r.readDouble());
				double dz = finite(r.readDouble());
				float yRot = (float) finite(r.readFloat());
				float xRot = (float) finite(r.readFloat());
				int relatives = r.readInt();
				yield new Clientbound.TeleportEntity(id, x, y, z, dx, dy, dz, yRot, xRot, relatives, r.readBoolean());
			}
			case "minecraft:rotate_head" -> new Clientbound.RotateHead(r.readVarInt(), angle(r.readByte()));
			case "minecraft:set_entity_motion" -> {
				int id = r.readVarInt();
				double[] v = lpVec3(r);
				yield new Clientbound.SetEntityMotion(id, v[0], v[1], v[2]);
			}
			case "minecraft:set_entity_data" -> entityData(r);
			case "minecraft:set_equipment" -> equipment(r);
			case "minecraft:swing_animation" -> {
				int id = r.readVarInt();
				int hand = r.readVarInt();
				r.readVarInt(); // SwingAnimation-Typ; für die Darstellung nicht unterschieden
				r.readVarInt(); // Dauer
				yield new Clientbound.SwingAnimation(id, hand);
			}
			case "minecraft:animate" -> new Clientbound.Animate(r.readVarInt(), r.readUnsignedByte());
			case "minecraft:entity_event" -> new Clientbound.EntityEvent(r.readInt(), r.readByte());
			case "minecraft:hurt_animation" -> new Clientbound.HurtAnimation(r.readVarInt(), r.readFloat());
			case "minecraft:take_item_entity" -> new Clientbound.TakeItemEntity(r.readVarInt(), r.readVarInt(), r.readVarInt());
			case "minecraft:set_passengers" -> {
				int vehicle = r.readVarInt();
				int n = r.readCount(MAX_REMOVE, 1, "set_passengers");
				int[] p = new int[n];
				for (int i = 0; i < n; i++) {
					p[i] = r.readVarInt();
				}
				yield new Clientbound.SetPassengers(vehicle, p);
			}
			case "minecraft:player_info_update" -> playerInfoUpdate(r);
			case "minecraft:player_info_remove" -> {
				int n = r.readCount(4096, 16, "player_info_remove");
				List<UUID> list = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					list.add(r.readUuid());
				}
				yield new Clientbound.PlayerInfoRemove(list);
			}
			default -> null;
		};
	}

	/** {@code Mth.unpackDegrees}: Byte (mit Vorzeichen) · 360 / 256. */
	static float angle(final byte b) {
		return b * 360 / 256.0F;
	}

	private static double finite(final double v) throws ProtocolException {
		if (!Double.isFinite(v)) {
			throw new ProtocolException("Non-finite value in entity packet");
		}
		return v;
	}

	private static Clientbound addEntity(final PacketReader r) throws ProtocolException {
		int id = r.readVarInt();
		UUID uuid = r.readUuid();
		int type = r.readVarInt();
		double x = finite(r.readDouble());
		double y = finite(r.readDouble());
		double z = finite(r.readDouble());
		double[] v = lpVec3(r);
		float xRot = angle(r.readByte());
		float yRot = angle(r.readByte());
		float head = angle(r.readByte());
		int data = r.readVarInt();
		return new Clientbound.AddEntity(id, uuid, type, x, y, z, v[0], v[1], v[2], xRot, yRot, head, data);
	}

	/**
	 * {@code LpVec3}: 0-Byte = Nullvektor; sonst 48-Bit-Puffer (Byte, Byte, int big-endian als höchste 32 Bit), Bits 0–1 Skalierung,
	 * Bit 2 Fortsetzung (weitere Skalierungsbits als VarInt), je 15 Bit x/y/z ab Bit 3/18/33.
	 */
	static double[] lpVec3(final PacketReader r) throws ProtocolException {
		int lowest = r.readUnsignedByte();
		if (lowest == 0) {
			return new double[3];
		}
		int middle = r.readUnsignedByte();
		long highest = r.readInt() & 0xFFFFFFFFL;
		long buffer = highest << 16 | (long) middle << 8 | lowest;
		long scale = lowest & 3;
		if ((lowest & 4) != 0) {
			scale |= (r.readVarInt() & 0xFFFFFFFFL) << 2;
		}
		return new double[]{lpUnpack(buffer >> 3) * scale, lpUnpack(buffer >> 18) * scale, lpUnpack(buffer >> 33) * scale};
	}

	private static double lpUnpack(final long value) {
		return Math.min(value & 32767L, 32766.0) * 2.0 / 32766.0 - 1.0;
	}

	/** {@code move_entity_pos(_rot)}: Eigenschaften (Bit 0 onGround, Rest Schrittzahl), VecDelta, ggf. yRot/xRot. */
	private static Clientbound move(final PacketReader r, final boolean pos, final boolean rot) throws ProtocolException {
		int id = r.readVarInt();
		int properties = r.readVarInt();
		boolean onGround = (properties & 1) != 0;
		int stepCount = properties >>> 1;
		if (stepCount > MAX_STEPS || stepCount > r.remaining() / 7) {
			throw new ProtocolException("VecDelta with " + stepCount + " steps");
		}
		List<Clientbound.DeltaStep> steps = new ArrayList<>(Math.max(1, stepCount));
		if (stepCount == 0) {
			steps.add(new Clientbound.DeltaStep(0, r.readShort(), r.readShort(), r.readShort()));
		} else {
			for (int i = 0; i < stepCount; i++) {
				int ticks = r.readVarInt();
				steps.add(new Clientbound.DeltaStep(ticks, r.readShort(), r.readShort(), r.readShort()));
			}
		}
		float yRot = 0;
		float xRot = 0;
		if (rot) {
			yRot = angle(r.readByte());
			xRot = angle(r.readByte());
		}
		return new Clientbound.MoveEntity(id, pos, rot, stepCount > 0, steps, yRot, xRot, onGround);
	}

	/** {@code entity_position_sync}: PositionPath (Typ 0 linear: Vec3; 1 gestuft: Liste aus Vec3 + VarInt-Ticks), float yRot/xRot, bool. */
	private static Clientbound positionSync(final PacketReader r) throws ProtocolException {
		int id = r.readVarInt();
		int type = r.readVarInt();
		List<Clientbound.PathStep> path = new ArrayList<>();
		boolean stepped = type == 1;
		if (type == 0) {
			path.add(new Clientbound.PathStep(finite(r.readDouble()), finite(r.readDouble()), finite(r.readDouble()), 0));
		} else if (type == 1) {
			int n = r.readCount(MAX_STEPS, 25, "position path");
			if (n == 0) {
				throw new ProtocolException("empty stepped position path");
			}
			for (int i = 0; i < n; i++) {
				path.add(new Clientbound.PathStep(finite(r.readDouble()), finite(r.readDouble()), finite(r.readDouble()), r.readVarInt()));
			}
		} else {
			throw new ProtocolException("unknown PositionPath type " + type);
		}
		float yRot = (float) finite(r.readFloat());
		float xRot = (float) finite(r.readFloat());
		return new Clientbound.EntityPositionSync(id, stepped, path, yRot, xRot, r.readBoolean());
	}

	/** Für Tests und Werkzeuge: Name des Serialisierers (docs/34 §5). */
	static String serializerName(final int id) {
		return switch (id) {
			case 0 -> "byte";
			case 1 -> "int";
			case 2 -> "long";
			case 3 -> "float";
			case 4 -> "string";
			case 5 -> "component";
			case 6 -> "optional_component";
			case 7 -> "item_stack";
			case 8 -> "boolean";
			case 9 -> "rotations";
			case 10 -> "block_pos";
			case 11 -> "optional_block_pos";
			case 12 -> "direction";
			case 13 -> "optional_living_entity_reference";
			case 14 -> "block_state";
			case 15 -> "optional_block_state";
			case 16 -> "particle";
			case 17 -> "particles";
			case 18 -> "villager_data";
			case 19 -> "optional_unsigned_int";
			case 20 -> "pose";
			case 33 -> "optional_global_pos";
			case 34 -> "painting_variant";
			case 39 -> "vector3";
			case 40 -> "quaternion";
			case 41 -> "resolvable_profile";
			case 42 -> "humanoid_arm";
			case 43 -> "dye_color";
			default -> id >= 21 && id <= 32 ? "variant" : id >= 35 && id <= 38 ? "state_enum" : "unknown#" + id;
		};
	}

	/** Liest einen Wert oder liefert {@link #UNSUPPORTED}, wenn das Format nicht umgesetzt ist (Rest des Pakets wird dann nicht gelesen). */
	private static final Object UNSUPPORTED = new Object();

	private static Object value(final PacketReader r, final int serializer, final ItemRegistry items) throws ProtocolException,
		ItemStackCodec.UnsupportedComponentException {
		return switch (serializer) {
			case 0 -> r.readByte();
			case 1, 12, 14, 15, 20, 42, 43 -> r.readVarInt();
			case 2 -> r.readVarLong();
			case 3 -> r.readFloat();
			case 4 -> r.readString(32767);
			case 5 -> text(r);
			case 6 -> r.readBoolean() ? text(r) : null;
			case 7 -> ItemStackCodec.read(r, items);
			case 8 -> r.readBoolean();
			case 9 -> new float[]{r.readFloat(), r.readFloat(), r.readFloat()};
			case 10 -> r.readLong();
			case 11 -> r.readBoolean() ? (Object) r.readLong() : null;
			case 13 -> r.readBoolean() ? r.readUuid() : null;
			case 18 -> new int[]{r.readVarInt(), r.readVarInt(), r.readVarInt()};
			case 19 -> {
				int v = r.readVarInt();
				yield v == 0 ? null : (Object) (v - 1);
			}
			case 16 -> particle(r);
			case 17 -> {
				// M10: Partikelliste (Effektpartikel, Datenfeld 10 der Lebewesen) – vor Absorption (17) im selben Paket
				int n = r.readCount(256, 1, "particles");
				List<Object> list = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					Object p = particle(r);
					if (p == UNSUPPORTED) {
						yield UNSUPPORTED;
					}
					list.add(p);
				}
				yield List.copyOf(list);
			}
			case 33 -> r.readBoolean() ? r.readIdentifier() + "@" + r.readLong() : null;
			case 41 -> resolvableProfile(r); // M14: Mannequin-Profil (Skin-Vorgabe ohne Netzwerk)
			case 39 -> new float[]{r.readFloat(), r.readFloat(), r.readFloat()};
			case 40 -> new float[]{r.readFloat(), r.readFloat(), r.readFloat(), r.readFloat()};
			default -> serializer >= 21 && serializer <= 32 || serializer >= 35 && serializer <= 38 ? (Object) r.readVarInt() : UNSUPPORTED;
		};
	}

	/**
	 * M14 (docs/56 §3): {@code ResolvableProfile.STREAM_CODEC}: entweder vollständiges Profil (UUID, Name, Eigenschaften) oder
	 * Teilprofil (optional Name, optional UUID, Eigenschaften), danach die Skin-Vorgabe ({@code PlayerSkin.Patch}: optional Textur, Umhang,
	 * Elytra als Bezeichner, optional Modell schmal/breit).
	 */
	static GameProfile.Resolvable resolvableProfile(final PacketReader r) throws ProtocolException {
		String name = null;
		java.util.UUID id = null;
		if (r.readBoolean()) {
			id = r.readUuid();
			name = r.readString(16);
		} else {
			name = r.readBoolean() ? r.readString(16) : null;
			id = r.readBoolean() ? r.readUuid() : null;
		}
		int c = r.readCount(16, 3, "profile properties");
		List<GameProfile.Property> props = new ArrayList<>(c);
		for (int j = 0; j < c; j++) {
			String pn = r.readString(64);
			String pv = r.readString(32767);
			String sig = r.readBoolean() ? r.readString(1024) : null;
			props.add(new GameProfile.Property(pn, pv, sig));
		}
		String texture = r.readBoolean() ? r.readIdentifier() : null;
		if (r.readBoolean()) {
			r.readIdentifier(); // Umhang (nicht dargestellt)
		}
		if (r.readBoolean()) {
			r.readIdentifier(); // Elytra (nicht dargestellt)
		}
		Boolean slim = r.readBoolean() ? r.readBoolean() : null;
		return new GameProfile.Resolvable(name, id, List.copyOf(props), texture, slim);
	}

	/**
	 * M10: {@code ParticleTypes.STREAM_CODEC}: Typ (VarInt, Registry particle_type, 128 Einträge) und typabhängige Optionen. Umgesetzt sind
	 * die Typen ohne Optionen und die mit einfachen Optionen (Farbe, Stärke, Blockzustand, Verzögerung); Item-, Vibrations-, Spur-, Geysir-
	 * und Farbübergangspartikel liefern {@link #UNSUPPORTED} (docs/38 §1).
	 */
	private static Object particle(final PacketReader r) throws ProtocolException {
		int type = r.readVarInt();
		return switch (type) {
			case 1, 2, 36, 121, 125 -> "particle#" + type + " state=" + r.readVarInt(); // block, block_marker, falling_dust, dust_pillar, block_crumble
			case 15, 48 -> "particle#" + type + " f=" + r.readFloat(); // dragon_breath (Stärke), sculk_charge (Rollwinkel)
			case 21, 23, 56 -> "particle#" + type + " color=" + r.readInt() + " f=" + r.readFloat(); // dust, effect, instant_effect
			case 28, 46, 52 -> "particle#" + type + " color=" + r.readInt(); // entity_effect, tinted_leaves, flash
			case 115 -> "particle#" + type + " delay=" + r.readVarInt(); // shriek
			case 7, 8, 9, 10, 22, 57, 58, 59 -> UNSUPPORTED;
			default -> {
				if (type < 0 || type >= 128) {
					throw new ProtocolException("particle type " + type);
				}
				yield "particle#" + type; // Typ ohne Optionen
			}
		};
	}

	private static String text(final PacketReader r) throws ProtocolException {
		Object tag = NbtReader.readNetworkTag(r);
		return tag == null ? "" : TextComponents.toPlainText(tag);
	}

	/** {@code set_entity_data}: Einträge (Index unsigned byte, Serialisierer VarInt, Wert) bis Index 255. */
	private static Clientbound entityData(final PacketReader r) throws ProtocolException {
		int id = r.readVarInt();
		ItemRegistry items = ItemRegistry.get();
		List<Clientbound.DataValue> values = new ArrayList<>();
		while (true) {
			int index = r.readUnsignedByte();
			if (index == 255) {
				return new Clientbound.SetEntityData(id, values, null);
			}
			int serializer = r.readVarInt();
			Object v;
			try {
				v = value(r, serializer, items);
			} catch (ItemStackCodec.UnsupportedComponentException e) {
				return new Clientbound.SetEntityData(id, values, "index " + index + ": " + e.getMessage());
			}
			if (v == UNSUPPORTED) {
				return new Clientbound.SetEntityData(id, values, "index " + index + ": serializer " + serializerName(serializer));
			}
			values.add(new Clientbound.DataValue(index, serializer, v));
		}
	}

	/** {@code set_equipment}: Platzbyte (Bit 7 = weitere folgen, Bits 0–6 = EquipmentSlot-Ordinalzahl) + ItemStack. */
	private static Clientbound equipment(final PacketReader r) throws ProtocolException {
		int id = r.readVarInt();
		ItemRegistry items = ItemRegistry.get();
		Map<Integer, ItemStack> slots = new HashMap<>();
		int b;
		do {
			b = r.readUnsignedByte();
			int slot = b & 127;
			if (slot > 7) {
				throw new ProtocolException("set_equipment: slot " + slot);
			}
			try {
				slots.put(slot, ItemStackCodec.read(r, items));
			} catch (ItemStackCodec.UnsupportedComponentException e) {
				return new Clientbound.SetEquipment(id, slots, "slot " + slot + ": " + e.getMessage());
			}
		} while ((b & 128) != 0);
		return new Clientbound.SetEquipment(id, slots, null);
	}

	/** {@code player_info_update}: Aktionsmaske (1 Byte, Bit i = Aktion i), Liste aus UUID + Feldern der gesetzten Aktionen. */
	private static Clientbound playerInfoUpdate(final PacketReader r) throws ProtocolException {
		int actions = r.readUnsignedByte();
		int n = r.readCount(4096, 16, "player_info_update");
		List<Clientbound.PlayerInfoEntry> entries = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			UUID uuid = r.readUuid();
			String name = null;
			List<GameProfile.Property> props = List.of();
			int gameMode = -1;
			boolean listed = false;
			int latency = 0;
			String display = null;
			boolean hat = false;
			if ((actions & 1) != 0) {
				name = r.readString(16);
				int c = r.readCount(16, 3, "profile properties");
				List<GameProfile.Property> p = new ArrayList<>(c);
				for (int j = 0; j < c; j++) {
					String pn = r.readString(64);
					String pv = r.readString(32767);
					String sig = r.readBoolean() ? r.readString(1024) : null;
					p.add(new GameProfile.Property(pn, pv, sig));
				}
				props = p;
			}
			if ((actions & 2) != 0 && r.readBoolean()) {
				r.readUuid(); // Chat-Sitzung
				r.readLong(); // Ablauf des Schlüssels
				r.readByteArray(512); // öffentlicher Schlüssel
				r.readByteArray(4096); // Signatur
			}
			if ((actions & 4) != 0) {
				gameMode = r.readVarInt();
			}
			if ((actions & 8) != 0) {
				listed = r.readBoolean();
			}
			if ((actions & 16) != 0) {
				latency = r.readVarInt();
			}
			if ((actions & 32) != 0 && r.readBoolean()) {
				display = text(r);
			}
			if ((actions & 64) != 0) {
				r.readVarInt(); // Reihenfolge in der Liste
			}
			if ((actions & 128) != 0) {
				hat = r.readBoolean();
			}
			entries.add(new Clientbound.PlayerInfoEntry(uuid, name, props, gameMode, listed, latency, display, hat));
		}
		return new Clientbound.PlayerInfoUpdate(actions, entries);
	}
}
