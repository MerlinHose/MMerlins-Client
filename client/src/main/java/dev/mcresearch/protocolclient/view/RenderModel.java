package dev.mcresearch.protocolclient.view;

import java.util.List;

/**
 * Visuelles Blockmodell eines Zustands (docs/25 §3) – bewusst getrennt von der Kollisionsform (M5, {@code physics.Shape}).
 *
 * @param quads            Flächen in Blockkoordinaten 0…1
 * @param occludes         je {@link Face}: deckt diese Seite vollständig und undurchsichtig ab (Farbmodus und Tests; im
 *                         Texturmodus entscheidet {@link BlockRenderFacts} wie {@code Block#shouldRenderFace})
 * @param debug            Ersatzmodell für ein unbekanntes/fehlerhaftes Modell
 * @param cullGroup        &gt; 0: Flächen zu einem Nachbarn derselben Gruppe entfallen (Farbmodus-Wasser)
 * @param ambientOcclusion Modell nutzt Ambient Occlusion ({@code ambientocclusion}, Vorgabe true; M12)
 * @param fluid            0 kein Fluid, 1 Wasser, 2 Lava – Fluidflächen erzeugt der FluidMesher (M12)
 */
public record RenderModel(List<RenderQuad> quads, boolean[] occludes, boolean debug, int cullGroup, boolean ambientOcclusion, int fluid) {
	public static final RenderModel EMPTY = new RenderModel(List.of(), new boolean[6], false, 0, true, 0);

	public RenderModel(final List<RenderQuad> quads, final boolean[] occludes, final boolean debug) {
		this(quads, occludes, debug, 0, true, 0);
	}

	public RenderModel(final List<RenderQuad> quads, final boolean[] occludes, final boolean debug, final int cullGroup) {
		this(quads, occludes, debug, cullGroup, true, 0);
	}

	public RenderModel {
		quads = List.copyOf(quads);
		occludes = occludes.clone();
		if (occludes.length != 6) {
			throw new IllegalArgumentException("occludes needs 6 entries");
		}
	}

	public boolean occludes(final Face f) {
		return this.occludes[f.ordinal()];
	}

	public boolean isEmpty() {
		return this.quads.isEmpty() && this.fluid == 0;
	}
}
