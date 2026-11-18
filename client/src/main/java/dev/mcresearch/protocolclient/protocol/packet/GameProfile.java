package dev.mcresearch.protocolclient.protocol.packet;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Spielerprofil aus {@code login_finished} (docs/12 §5.3). */
public record GameProfile(UUID id, String name, List<Property> properties) {
	public record Property(String name, String value, String signature) {
	}

	/** M14: {@code ResolvableProfile} (Mannequin): Name/UUID optional, Eigenschaften, Skin-Vorgabe (Textur-Bezeichner, Modell schmal). */
	public record Resolvable(String name, UUID id, List<Property> properties, String texture, Boolean slim) {
	}

	/** Offline-UUID wie im Vanilla-Server: Version-3-UUID aus "OfflinePlayer:" + Name. */
	public static UUID offlineUuid(final String name) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
	}
}
