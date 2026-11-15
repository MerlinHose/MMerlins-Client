package dev.mcresearch.protocolclient.protocol;

import java.io.IOException;
import java.io.Serial;

/** Verletzung des Protokolls: ungültige Länge, unbekannte Paket-ID, unzulässiger Feldwert oder falscher Zustand. */
public final class ProtocolException extends IOException {
	@Serial
	private static final long serialVersionUID = 1L;

	public ProtocolException(final String message) {
		super(message);
	}

	public ProtocolException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
