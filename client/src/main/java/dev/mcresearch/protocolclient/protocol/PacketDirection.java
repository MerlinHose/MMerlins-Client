package dev.mcresearch.protocolclient.protocol;

/** Paketrichtung aus Sicht des Servers (wie in packets.json): SERVERBOUND = Client → Server. */
public enum PacketDirection {
	SERVERBOUND("serverbound"),
	CLIENTBOUND("clientbound");

	private final String id;

	PacketDirection(final String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}

	public static PacketDirection byId(final String id) {
		for (PacketDirection direction : values()) {
			if (direction.id.equals(id)) {
				return direction;
			}
		}
		throw new IllegalArgumentException("Unknown packet direction: " + id);
	}
}
