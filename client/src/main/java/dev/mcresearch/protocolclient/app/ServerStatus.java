package dev.mcresearch.protocolclient.app;

/**
 * Ergebnis eines Statuspings (docs/61 §6): Erreichbarkeit, Version, Spielerzahl, Beschreibung, Kompatibilität.
 *
 * @param state Verbindungsstatus in der Serverliste
 */
public record ServerStatus(State state, long pingMillis, String versionName, int protocol, int online, int max, String motd, String error) {
	/** Status eines Eintrags. */
	public enum State {
		UNKNOWN("Nicht geprüft"), PINGING("Wird geprüft …"), ONLINE("Erreichbar"), OFFLINE("Nicht erreichbar"), BLOCKED("Gesperrt (extern)");

		public final String label;

		State(final String label) {
			this.label = label;
		}
	}

	public static final ServerStatus UNKNOWN = new ServerStatus(State.UNKNOWN, -1, "", 0, 0, 0, "", "");
	public static final ServerStatus PINGING = new ServerStatus(State.PINGING, -1, "", 0, 0, 0, "", "");
	public static final ServerStatus BLOCKED = new ServerStatus(State.BLOCKED, -1, "", 0, 0, 0, "",
		"Externe Server sind in dieser Entwicklungsversion gesperrt; nur localhost/127.0.0.1.");

	public static ServerStatus offline(final String error) {
		return new ServerStatus(State.OFFLINE, -1, "", 0, 0, 0, "", error);
	}

	public ProtocolVersions.Support support() {
		return this.state == State.ONLINE ? ProtocolVersions.support(this.protocol) : ProtocolVersions.Support.UNKNOWN;
	}

	/** Eine Zeile für die Liste. */
	public String summary() {
		return switch (this.state) {
			case ONLINE -> String.format(java.util.Locale.ROOT, "Erreichbar · %s · %d/%d Spieler · %d ms · %s", this.versionName, this.online, this.max,
				this.pingMillis, switch (support()) {
					case NATIVE -> "kompatibel";
					case PLANNED_MVS -> "ältere Version (MVS geplant)";
					case TOO_OLD -> "zu alt";
					case TOO_NEW -> "neuer als der Client";
					case UNKNOWN -> "?";
				});
			case OFFLINE -> "Nicht erreichbar" + (this.error.isEmpty() ? "" : ": " + this.error);
			default -> this.state.label;
		};
	}
}
