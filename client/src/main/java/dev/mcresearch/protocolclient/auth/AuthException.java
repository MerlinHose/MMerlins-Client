package dev.mcresearch.protocolclient.auth;

import java.io.IOException;

/**
 * M13: Anmeldefehler. Die Meldung enthält ausschließlich nicht-sensitive Diagnose (Fehlerart, HTTP-Status,
 * Fehlercode des Dienstes), niemals Tokens, Autorisierungscodes oder Antwortkörper mit Tokens.
 */
public final class AuthException extends IOException {
	private static final long serialVersionUID = 1L;
	private final AuthError error;

	public AuthException(final AuthError error, final String detail) {
		super(error.name() + ": " + error.message() + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
		this.error = error;
	}

	public AuthException(final AuthError error, final String detail, final Throwable cause) {
		this(error, detail);
		// Ursache nur übernehmen, wenn sie selbst nicht-sensitiv ist (IO-/Timeout-Ausnahmen des JDK)
		initCause(cause);
	}

	public AuthError error() {
		return this.error;
	}

	/** Meldung für die Oberfläche (ohne Enum-Namen). */
	public String userMessage() {
		return this.error.message();
	}
}
