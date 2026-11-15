package dev.mcresearch.protocolclient.auth;

import java.time.Instant;

/**
 * M13: Hülle für Tokens (Microsoft-, Xbox-, XSTS-, Minecraft-Access-Token, Refresh-Token). {@link #toString()} gibt
 * niemals den Wert aus, damit Tokens nicht versehentlich in Logs, Ausnahmen, Oberfläche oder Testberichte gelangen.
 * Tokens werden nur im Arbeitsspeicher gehalten (keine dauerhafte Speicherung in der ersten Ausbaustufe, docs/52 §6).
 */
public final class SecretToken {
	private final String kind;
	private volatile String value;
	private final Instant expiresAt;

	public SecretToken(final String kind, final String value, final Instant expiresAt) {
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException(kind + ": empty token");
		}
		this.kind = kind;
		this.value = value;
		this.expiresAt = expiresAt;
	}

	/** Nur für den Einbau in eine Anfrage verwenden; nie protokollieren. */
	public String reveal() {
		String v = this.value;
		if (v == null) {
			throw new IllegalStateException(this.kind + " token was discarded");
		}
		return v;
	}

	public Instant expiresAt() {
		return this.expiresAt;
	}

	public boolean expired(final Instant now) {
		return this.expiresAt != null && !now.isBefore(this.expiresAt);
	}

	/** Abmelden: Referenz verwerfen (Java-Strings lassen sich nicht sicher überschreiben; docs/52 §6). */
	public void discard() {
		this.value = null;
	}

	public boolean discarded() {
		return this.value == null;
	}

	@Override
	public String toString() {
		return this.kind + "[redacted]";
	}
}
