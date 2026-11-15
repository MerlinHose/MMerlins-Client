package dev.mcresearch.protocolclient.protocol;

/** Protokollzustände (docs/12 §4). Der Name entspricht der Bezeichnung in packets.json und im Vanilla-Paketlog. */
public enum ProtocolState {
	HANDSHAKE("handshake"),
	STATUS("status"),
	LOGIN("login"),
	CONFIGURATION("configuration"),
	PLAY("play");

	private final String id;

	ProtocolState(final String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}

	public static ProtocolState byId(final String id) {
		for (ProtocolState state : values()) {
			if (state.id.equals(id)) {
				return state;
			}
		}
		throw new IllegalArgumentException("Unknown protocol state: " + id);
	}
}
