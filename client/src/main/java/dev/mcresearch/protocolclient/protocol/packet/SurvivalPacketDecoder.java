package dev.mcresearch.protocolclient.protocol.packet;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.nbt.NbtReader;
import dev.mcresearch.protocolclient.protocol.nbt.TextComponents;

/** Dekodierung der Kampf- und Survival-Pakete (M10, Formate nach docs/38 §1, eigenständig geschrieben). */
final class SurvivalPacketDecoder {
	private SurvivalPacketDecoder() {
	}

	/** Liefert das Paket oder {@code null}, wenn {@code name} keines dieser Pakete ist. */
	static Clientbound decode(final String name, final PacketReader r) throws ProtocolException {
		return switch (name) {
			case "minecraft:damage_event" -> {
				int entity = r.readVarInt();
				int type = r.readVarInt();
				int cause = r.readVarInt() - 1;
				int direct = r.readVarInt() - 1;
				boolean hasPos = r.readBoolean();
				double x = hasPos ? r.readDouble() : 0;
				double y = hasPos ? r.readDouble() : 0;
				double z = hasPos ? r.readDouble() : 0;
				yield new Clientbound.DamageEvent(entity, type, cause, direct, hasPos, x, y, z);
			}
			case "minecraft:update_mob_effect" -> new Clientbound.UpdateMobEffect(r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readVarInt(),
				r.readByte());
			case "minecraft:remove_mob_effect" -> new Clientbound.RemoveMobEffect(r.readVarInt(), r.readVarInt());
			case "minecraft:player_combat_kill" -> {
				int id = r.readVarInt();
				Object tag = NbtReader.readNetworkTag(r);
				yield new Clientbound.PlayerCombatKill(id, tag == null ? "" : TextComponents.toPlainText(tag), tag);
			}
			case "minecraft:player_combat_enter" -> new Clientbound.PlayerCombat(true, -1);
			case "minecraft:player_combat_end" -> new Clientbound.PlayerCombat(false, r.readVarInt());
			case "minecraft:set_experience" -> new Clientbound.SetExperience(r.readFloat(), r.readVarInt(), r.readVarInt());
			case "minecraft:cooldown" -> new Clientbound.ItemCooldown(r.readIdentifier(), r.readVarInt());
			default -> null;
		};
	}
}
