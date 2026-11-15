package dev.mcresearch.protocolclient.view;

import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.BlockState;
import java.util.ArrayList;
import java.util.List;

/**
 * Farbmodus (docs/25 §3, Phase C): jeder nicht-luftförmige Block als voller Würfel in einer aus dem Blocknamen abgeleiteten
 * Farbe; Wasser blau und durchscheinend. Dient dem Nachweis der Geometrie ohne Texturen und ohne Mojang-Ressourcen.
 */
public final class ColorModels implements BlockModelProvider {
	private final RenderModel[] models;

	public ColorModels(final BlockRegistry registry) {
		this.models = new RenderModel[registry.size()];
		for (int id = 0; id < registry.size(); id++) {
			BlockState s = registry.state(id);
			this.models[id] = s.isAir() ? RenderModel.EMPTY : cube(colorOf(s.name()), s.name().equals("minecraft:water") || s.name().endsWith("glass"));
		}
	}

	static int colorOf(final String name) {
		return switch (name) {
			case "minecraft:grass_block" -> MeshBuilder.rgba(95, 159, 53, 255);
			case "minecraft:dirt", "minecraft:coarse_dirt" -> MeshBuilder.rgba(134, 96, 67, 255);
			case "minecraft:stone", "minecraft:cobblestone" -> MeshBuilder.rgba(125, 125, 125, 255);
			case "minecraft:deepslate" -> MeshBuilder.rgba(80, 80, 85, 255);
			case "minecraft:bedrock" -> MeshBuilder.rgba(50, 50, 50, 255);
			case "minecraft:water" -> MeshBuilder.rgba(50, 90, 220, 150);
			case "minecraft:sand" -> MeshBuilder.rgba(219, 207, 163, 255);
			default -> {
				int h = name.hashCode();
				yield MeshBuilder.rgba(80 + (h & 127), 80 + (h >> 8 & 127), 80 + (h >> 16 & 127), 255);
			}
		};
	}

	static RenderModel cube(final int color, final boolean translucent) {
		List<RenderQuad> quads = new ArrayList<>(6);
		float[] uv = {0, 1, 1, 1, 1, 0, 0, 0};
		for (Face f : Face.values()) {
			quads.add(new RenderQuad(f.corners(0, 0, 0, 1, 1, 1), uv, f, f, true, -1, color,
				translucent ? RenderQuad.Layer.TRANSLUCENT : RenderQuad.Layer.SOLID));
		}
		boolean[] occ = new boolean[6];
		java.util.Arrays.fill(occ, !translucent);
		return new RenderModel(quads, occ, false);
	}

	@Override
	public RenderModel model(final int stateId) {
		return stateId >= 0 && stateId < this.models.length ? this.models[stateId] : RenderModel.EMPTY;
	}

	@Override
	public boolean textured() {
		return false;
	}
}
