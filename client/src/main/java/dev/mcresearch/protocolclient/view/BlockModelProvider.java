package dev.mcresearch.protocolclient.view;

/** Liefert das visuelle Modell je Blockzustand (thread-sicher, wird von Mesh-Threads gleichzeitig gelesen). */
public interface BlockModelProvider {
	RenderModel model(int stateId);

	/** Tönungsfarbe (RGB) für {@code tintindex} ≥ 0 eines Zustands, z. B. Gras-, Laub-, Wasserfarbe. */
	default int tint(final int stateId, final int tintIndex) {
		return 0xFFFFFF;
	}

	/** true, wenn Texturkoordinaten gültig sind (Textur-Atlas gebunden); sonst Farbmodus. */
	boolean textured();
}
