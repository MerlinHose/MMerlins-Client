package dev.mcresearch.protocolclient.world;

import java.util.Map;
import java.util.stream.Collectors;

/** Ein Blockzustand: globale Netzwerk-ID, Blockname und Eigenschaften (docs/18 §8). */
public record BlockState(int id, String name, Map<String, String> properties) {
	public BlockState {
		properties = Map.copyOf(properties);
	}

	public boolean isAir() {
		return this.name.equals("minecraft:air") || this.name.equals("minecraft:cave_air") || this.name.equals("minecraft:void_air");
	}

	/** Darstellung wie in Minecraft-Befehlen, z. B. {@code minecraft:oak_stairs[facing=north,half=top]} (Eigenschaften sortiert). */
	@Override
	public String toString() {
		if (this.properties.isEmpty()) {
			return this.name;
		}
		return this.name + this.properties.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(",", "[", "]"));
	}
}
