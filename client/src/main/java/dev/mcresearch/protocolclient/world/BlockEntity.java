package dev.mcresearch.protocolclient.world;

import java.util.Map;

/**
 * Block-Entity mit absoluter Position, Typ-ID ({@code minecraft:block_entity_type}), Typname und NBT
 * (leere Map, wenn der Server keine Daten gesendet hat).
 */
public record BlockEntity(int x, int y, int z, int typeId, String type, Map<String, Object> nbt) {
	public BlockEntity {
		nbt = nbt == null ? Map.of() : nbt;
	}
}
