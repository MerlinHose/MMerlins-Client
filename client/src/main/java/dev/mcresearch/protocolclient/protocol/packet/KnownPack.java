package dev.mcresearch.protocolclient.protocol.packet;

/** Datenpaket-Kennung in {@code select_known_packs} (docs/12 §5.4). */
public record KnownPack(String namespace, String id, String version) {
}
