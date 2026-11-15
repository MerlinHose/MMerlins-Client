package dev.mcresearch.protocolclient.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PacketRegistryTest {
	private final PacketRegistry registry = PacketRegistry.get();

	@Test
	void containsAllPacketsOfTheOfficialReport() {
		assertEquals(260, this.registry.size());
		assertEquals(1, this.registry.count(ProtocolState.HANDSHAKE, PacketDirection.SERVERBOUND));
		assertEquals(69, this.registry.count(ProtocolState.PLAY, PacketDirection.SERVERBOUND));
		assertEquals(144, this.registry.count(ProtocolState.PLAY, PacketDirection.CLIENTBOUND));
	}

	@Test
	void idsMatchSpecification() {
		// Stichproben aus docs/12 (laufzeitverifiziert in docs/10)
		assertEquals(0x00, this.registry.idOf(ProtocolState.HANDSHAKE, PacketDirection.SERVERBOUND, "minecraft:intention"));
		assertEquals(0x02, this.registry.idOf(ProtocolState.LOGIN, PacketDirection.CLIENTBOUND, "minecraft:login_finished"));
		assertEquals(0x07, this.registry.idOf(ProtocolState.CONFIGURATION, PacketDirection.CLIENTBOUND, "minecraft:registry_data"));
		assertEquals(0x32, this.registry.idOf(ProtocolState.PLAY, PacketDirection.CLIENTBOUND, "minecraft:login"));
		assertEquals(0x1C, this.registry.idOf(ProtocolState.PLAY, PacketDirection.SERVERBOUND, "minecraft:keep_alive"));
		assertEquals(0x2C, this.registry.idOf(ProtocolState.PLAY, PacketDirection.SERVERBOUND, "minecraft:player_loaded"));
		assertEquals("minecraft:player_position", this.registry.nameOf(ProtocolState.PLAY, PacketDirection.CLIENTBOUND, 0x49));
	}

	@Test
	void unknownIdsAreRejected() {
		assertNull(this.registry.nameOf(ProtocolState.LOGIN, PacketDirection.CLIENTBOUND, 0x40));
		assertNull(this.registry.nameOf(ProtocolState.STATUS, PacketDirection.CLIENTBOUND, -1));
		assertThrows(NullPointerException.class, () -> this.registry.idOf(ProtocolState.LOGIN, PacketDirection.SERVERBOUND, "minecraft:keep_alive"));
	}

	@Test
	void malformedTableRejected() {
		assertThrows(IllegalStateException.class, () -> PacketRegistry.parse("login clientbound 1"));
		assertThrows(IllegalStateException.class, () -> PacketRegistry.parse("login clientbound 1 a:b\nlogin clientbound 1 a:c"));
	}
}
