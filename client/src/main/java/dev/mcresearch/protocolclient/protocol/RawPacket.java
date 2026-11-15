package dev.mcresearch.protocolclient.protocol;


/**
 * Ein empfangenes, bereits entrahmtes und dekomprimiertes Paket, dessen ID im Zustand {@code state} gültig ist.
 *
 * @param body Felder ohne Paket-ID
 * @param size Größe von ID + Feldern (entspricht dem "-> n bytes" im Vanilla-Paketlog)
 */
public record RawPacket(ProtocolState state, int id, String name, byte[] body, int size) {
}
