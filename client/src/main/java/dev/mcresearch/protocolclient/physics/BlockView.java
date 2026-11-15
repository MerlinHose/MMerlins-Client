package dev.mcresearch.protocolclient.physics;

/** Lesezugriff auf Blockzustände für die Kollision. */
@FunctionalInterface
public interface BlockView {
	/** Wert für Positionen in nicht geladenen Chunks. */
	int UNLOADED = -1;

	/** Globale Zustands-ID an (x, y, z) oder {@link #UNLOADED}. Außerhalb der Welthöhe: eine Luft-ID. */
	int stateAt(int x, int y, int z);
}
