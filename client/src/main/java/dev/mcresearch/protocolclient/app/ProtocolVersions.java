package dev.mcresearch.protocolclient.app;

import dev.mcresearch.protocolclient.protocol.PacketRegistry;
import java.util.List;

/**
 * Erweiterbares Modell der Server-Protokollversionen (docs/61 §6, Vorbereitung des Merlin Multi Version Systems, M18).
 * M15 übersetzt kein Protokoll: nur {@link #NATIVE} ist verbindbar. Ältere Server ab 1.20.2 werden erkannt und als
 * „für MVS geplant“ gemeldet; die Tabelle enthält nur Nummern, die durch die Versionsverläufe belegt sind – andere Nummern im
 * Bereich gelten als „unbekannte Zwischenversion“, der Versionsname kommt dann aus dem Statusping des Servers.
 */
public final class ProtocolVersions {
	/** Unterstützungsstand einer Serverversion. */
	public enum Support {
		/** Eigenes Protokoll, direkt verbindbar. */
		NATIVE,
		/** Älter, ab 1.20.2: mit MVS (M18) vorgesehen, heute nicht verbindbar. */
		PLANNED_MVS,
		/** Älter als 1.20.2: nicht vorgesehen. */
		TOO_OLD,
		/** Neuer als der Client. */
		TOO_NEW,
		/** Keine Angabe (Server nicht erreicht). */
		UNKNOWN
	}

	/** Eine bekannte Version: Protokollnummer und Versionsnamen. */
	public record Version(int protocol, String names) {
	}

	/** Untergrenze des geplanten MVS-Bereichs: 1.20.2 (Protokoll 764). */
	public static final int MVS_MIN_PROTOCOL = 764;
	public static final Version NATIVE = new Version(PacketRegistry.PROTOCOL_VERSION, "26.3");

	/** Bekannte ältere Versionen im MVS-Bereich (Anzeige; M18 ergänzt die Übersetzungsschichten). */
	public static final List<Version> KNOWN = List.of(new Version(764, "1.20.2"), new Version(765, "1.20.3–1.20.4"),
		new Version(766, "1.20.5–1.20.6"), new Version(767, "1.21–1.21.1"), new Version(768, "1.21.2–1.21.3"), new Version(769, "1.21.4"),
		new Version(770, "1.21.5"), NATIVE);

	private ProtocolVersions() {
	}

	public static Support support(final int protocol) {
		if (protocol <= 0) {
			return Support.UNKNOWN;
		}
		if (protocol == NATIVE.protocol()) {
			return Support.NATIVE;
		}
		if (protocol > NATIVE.protocol()) {
			return Support.TOO_NEW;
		}
		return protocol >= MVS_MIN_PROTOCOL ? Support.PLANNED_MVS : Support.TOO_OLD;
	}

	/** Versionsname aus der Tabelle oder null. */
	public static String knownName(final int protocol) {
		for (Version v : KNOWN) {
			if (v.protocol() == protocol) {
				return v.names();
			}
		}
		return null;
	}

	/** Verständliche Kompatibilitätsmeldung für Menüs und Fehlerdialoge. */
	public static String describe(final int protocol, final String serverVersionName) {
		String name = serverVersionName != null && !serverVersionName.isBlank() ? serverVersionName : knownName(protocol);
		String server = (name == null ? "unbekannte Version" : name) + " (Protokoll " + protocol + ")";
		return switch (support(protocol)) {
			case NATIVE -> "Kompatibel: " + server;
			case PLANNED_MVS -> "Server " + server + " ist älter. Unterstützung ab 1.20.2 ist mit dem Merlin Multi Version System (M18) geplant.";
			case TOO_OLD -> "Server " + server + " ist zu alt; unterstützt werden künftig Versionen ab 1.20.2.";
			case TOO_NEW -> "Server " + server + " ist neuer als der Client (" + NATIVE.names() + ", Protokoll " + NATIVE.protocol() + ").";
			case UNKNOWN -> "Serverversion unbekannt.";
		};
	}
}
