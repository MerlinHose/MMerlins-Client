package dev.mcresearch.protocolclient.view;

/**
 * Eine Modellfläche (docs/25 §3, M12: docs/47 §2): vier Ecken (Blockkoordinaten 0…1, gegen den Uhrzeigersinn von vorn), UV im
 * Atlas, optionale {@code cullface}, Flächenrichtung (für AO und Schattierung, nach Variantenrotation), optional
 * {@code shade_direction_override} (26.3 – ersetzt das frühere {@code shade}-Feld), Tönungsindex (−1 = keiner), Basisfarbe
 * (Farbmodus), Render-Schicht und Lichtemission des Elements ({@code light_emission}, 0–15).
 */
public record RenderQuad(float[] positions, float[] uvs, Face cullFace, Face direction, Face shadeOverride, int tintIndex, int color, Layer layer,
	int lightEmission) {
	public RenderQuad {
		if (positions.length != 12 || uvs.length != 8) {
			throw new IllegalArgumentException("quad needs 4 corners");
		}
		positions = positions.clone();
		uvs = uvs.clone();
	}

	/** Kompatibel zu M6–M11: {@code shade = false} entspricht jetzt keinem Override (Richtungsschattierung immer aktiv). */
	public RenderQuad(final float[] positions, final float[] uvs, final Face cullFace, final Face shadeFace, final boolean shade, final int tintIndex,
		final int color, final Layer layer) {
		this(positions, uvs, cullFace, shadeFace, null, tintIndex, color, layer, 0);
	}

	/** Richtung für die Richtungsschattierung: Override oder Flächenrichtung. */
	public Face shadeFace() {
		return this.shadeOverride != null ? this.shadeOverride : this.direction;
	}

	/** Render-Schicht: undurchsichtig, ausgestanzt (Alphatest 0,5), durchscheinend (Blending, sortiert, eigener Durchgang). */
	public enum Layer {
		SOLID, CUTOUT, TRANSLUCENT
	}
}
