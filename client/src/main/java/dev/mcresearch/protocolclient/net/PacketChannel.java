package dev.mcresearch.protocolclient.net;

import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.RawPacket;
import dev.mcresearch.protocolclient.protocol.packet.ServerboundPacket;
import java.io.IOException;

/** Abstraktion der Verbindung für die Zustandslogik (ermöglicht Tests ohne Socket). */
public interface PacketChannel extends AutoCloseable {
	/** Blockiert bis zum nächsten gültigen Paket. EOFException, wenn der Server die Verbindung schließt. */
	RawPacket receive() throws IOException;

	/** Sendet ein Paket; muss zum aktuellen ausgehenden Zustand passen. Thread-sicher. */
	void send(ServerboundPacket packet) throws IOException;

	void setInboundState(ProtocolState state);

	void setOutboundState(ProtocolState state);

	ProtocolState outboundState();

	/** Aktiviert Kompression (threshold ≥ 0) oder schaltet sie ab (threshold < 0). */
	void setCompressionThreshold(int threshold);

	/** M13: aktiviert AES/CFB8 in beide Richtungen (Online-Mode-Login, docs/51 §4). */
	default void enableEncryption(final javax.crypto.SecretKey sharedSecret) throws IOException {
		throw new IOException("Encryption not supported by this channel");
	}

	/** M13: true, sobald der Datenstrom verschlüsselt ist. */
	default boolean encrypted() {
		return false;
	}

	/** Log-Marker für Testschritte (erscheint im Paketlog wie die [LOCALTEST]-Marker des Referenzlaufs). */
	void marker(String text);

	/** Transport- und Kompressionszähler (Standard: keine Daten, z. B. für Test-Doubles). */
	default TransportStats transportStats() {
		return TransportStats.NONE;
	}

	@Override
	void close();
}
