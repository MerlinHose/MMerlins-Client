package dev.mcresearch.protocolclient.world;

/**
 * Änderungsmeldungen des {@link WorldState} (docs/25 §5). Aufrufe erfolgen auf dem Netzwerk-Thread <b>unter der
 * Weltsperre</b>; Implementierungen dürfen nur vormerken (z. B. in eine nebenläufige Warteschlange), nie blockieren oder
 * auf die Welt zurückgreifen.
 */
public interface WorldListener {
	void chunkLoaded(int chunkX, int chunkZ);

	void chunkUnloaded(int chunkX, int chunkZ);

	void blockChanged(int x, int y, int z);

	void lightChanged(int chunkX, int chunkZ);

	/** Clientseitig neu berechnetes Licht (M7): betroffene Sektionen, gepackt wie {@code Positions#packSection}. */
	default void lightSectionsChanged(final java.util.Set<Long> packedSections) {
	}
}
