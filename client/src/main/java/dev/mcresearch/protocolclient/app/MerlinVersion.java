package dev.mcresearch.protocolclient.app;

import dev.mcresearch.protocolclient.protocol.PacketRegistry;

/**
 * Versionsangaben des Merlin Clients (docs/59 §6). Die Client-Version ist eine eigene Kennung und unabhängig von der
 * Minecraft-Version und der Protokollnummer; die unterstützten Serverversionen beschreibt {@link ProtocolVersions}.
 */
public final class MerlinVersion {
	private MerlinVersion() {
	}

	public static final String NAME = "Minecraft Merlin Client";
	/** Eigene Versionskennung (Meilenstein 15 → 0.15.x). */
	public static final String CLIENT_VERSION = "0.15.0";
	public static final String STATUS = "Entwicklungsversion (Meilenstein M15) – Forschungs- und Testclient, nur lokaler Testserver";
	/** Minecraft-Version, deren Protokoll der Client spricht. */
	public static final String MINECRAFT_VERSION = "26.3";
	public static final int PROTOCOL = PacketRegistry.PROTOCOL_VERSION;
	public static final String REPOSITORY = "https://github.com/MerlinHose/MMerlins-Client";
	public static final String LICENSE = "Eigener Quellcode des Projekts; keine Mojang-Klassen oder -Grafiken enthalten. Minecraft-Ressourcen "
		+ "werden nur zur Laufzeit aus einer lokal vorhandenen, rechtmäßig erworbenen Spielinstallation gelesen und nicht weitergegeben. "
		+ "„Minecraft“ ist eine Marke von Mojang AB/Microsoft; dieses Projekt ist nicht mit ihnen verbunden.";

	public static String full() {
		return NAME + " " + CLIENT_VERSION + " (Minecraft " + MINECRAFT_VERSION + ", Protokoll " + PROTOCOL + ")";
	}
}
