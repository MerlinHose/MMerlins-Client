package dev.mcresearch.protocolclient.auth.microsoft;

import dev.mcresearch.protocolclient.auth.SecretToken;

/** M13: Microsoft-Tokens (nur im Arbeitsspeicher). {@code refreshToken} kann fehlen. */
public record MicrosoftTokens(SecretToken accessToken, SecretToken refreshToken) {
	public void discard() {
		this.accessToken.discard();
		if (this.refreshToken != null) {
			this.refreshToken.discard();
		}
	}
}
