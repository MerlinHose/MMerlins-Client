package dev.mcresearch.protocolclient.auth;

import java.util.UUID;

/**
 * M13: Spieleridentität für den Minecraft-Login (docs/52 §2). Einzige Schnittstelle zwischen Netzwerkprotokoll und
 * Authentifizierung: {@code ClientSession} kennt weder Microsoft noch Xbox noch HTTP, sondern nur Name, UUID und den
 * Session-Nachweis für einen konkreten Server-Hash.
 * <p>
 * Implementierungen: {@link OfflineIdentity} (bisheriger Testmodus), {@code auth.minecraft.OnlineIdentity}
 * (offizielle Minecraft-Sitzung) und {@code auth.minecraft.OnlineIdentity} mit lokaler Simulationsumgebung
 * (eindeutig als Testdaten gekennzeichnet).
 */
public interface LoginIdentity {
	/** Spielername für {@code hello}. */
	String name();

	/** Profil-UUID für {@code hello} (offline: Offline-UUID aus dem Namen; online: offizielle Profil-UUID). */
	UUID profileId();

	/** Art für Diagnose und Oberfläche: {@code offline}, {@code microsoft} oder {@code simulation}. */
	String kind();

	/** true, wenn ein Session-Nachweis möglich ist (Online-Mode-Server). */
	boolean canAuthenticate();

	/**
	 * Session-Nachweis für die konkrete Serververbindung (Vanilla: {@code SessionService#joinServer}). Blockiert bis
	 * zur Antwort oder Zeitüberschreitung.
	 *
	 * @param serverHash Minecraft-Session-Hash ({@code LoginCrypto#serverHash})
	 */
	void joinServer(String serverHash) throws AuthException;

	/** Abmelden: gehaltene Tokens verwerfen (offline: nichts zu tun). */
	default void logout() {
	}
}
