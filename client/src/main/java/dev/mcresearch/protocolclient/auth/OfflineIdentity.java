package dev.mcresearch.protocolclient.auth;

import dev.mcresearch.protocolclient.protocol.packet.GameProfile;
import java.util.UUID;

/** Bisheriger Offline-Testmodus: Name frei wählbar, UUID = Vanilla-Offline-UUID, kein Session-Nachweis möglich. */
public record OfflineIdentity(String name) implements LoginIdentity {
	public OfflineIdentity {
		if (!name.matches("[A-Za-z0-9_]{3,16}")) {
			throw new IllegalArgumentException("username must match [A-Za-z0-9_]{3,16}");
		}
	}

	@Override
	public UUID profileId() {
		return GameProfile.offlineUuid(this.name);
	}

	@Override
	public String kind() {
		return "offline";
	}

	@Override
	public boolean canAuthenticate() {
		return false;
	}

	@Override
	public void joinServer(final String serverHash) throws AuthException {
		throw new AuthException(AuthError.OFFLINE_IDENTITY, "Server verlangt Online-Mode-Authentifizierung");
	}
}
