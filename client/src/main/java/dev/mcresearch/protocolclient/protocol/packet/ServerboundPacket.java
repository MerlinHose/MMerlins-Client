package dev.mcresearch.protocolclient.protocol.packet;

import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;

/** Ein vom Client gesendetes Paket. Die numerische ID wird über {@code PacketRegistry} aus Zustand und Name bestimmt. */
public interface ServerboundPacket {
	ProtocolState state();

	/** Ressourcen-ID, z. B. {@code minecraft:keep_alive}. */
	String name();

	/** Schreibt nur die Felder (ohne Paket-ID). */
	void write(PacketWriter out);
}
