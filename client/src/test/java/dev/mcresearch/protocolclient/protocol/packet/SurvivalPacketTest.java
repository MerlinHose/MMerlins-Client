package dev.mcresearch.protocolclient.protocol.packet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.protocol.PacketDirection;
import dev.mcresearch.protocolclient.protocol.PacketRegistry;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.RawPacket;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;
import dev.mcresearch.protocolclient.protocol.nbt.TextComponents;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M10: Kampf- und Survival-Pakete (docs/38). LpVec3-Referenzbytes aus Mojangs Kodierer ({@code analysis/tools/LpVecCheck.java}). */
class SurvivalPacketTest {
	private static RawPacket raw(final String name, final byte[] body) {
		return new RawPacket(ProtocolState.PLAY, PacketRegistry.get().idOf(ProtocolState.PLAY, PacketDirection.CLIENTBOUND, name), name, body, body.length + 1);
	}

	@Test
	void lpVec3EncoderProducesMojangBytes() {
		double[][] in = {{0.1, -0.2, 0.05}, {1.5, 0.25, -3.75}, {-7.0, 2.0, 0.5}, {12.0, -40.0, 3.0}, {0, 0, 0}};
		String[] want = {"29338664ccca", "fcbf08010ffe01", "07008923492001", "9499899800020a", "00"};
		for (int i = 0; i < in.length; i++) {
			PacketWriter w = new PacketWriter();
			Serverbound.writeLpVec3(w, in[i][0], in[i][1], in[i][2]);
			assertEquals(want[i], HexFormat.of().formatHex(w.toByteArray()), "vector " + i);
		}
	}

	@Test
	void attackAndInteractPacketsAreRegisteredAndEncoded() throws ProtocolException {
		assertTrue(PacketRegistry.get().idOf(ProtocolState.PLAY, PacketDirection.SERVERBOUND, "minecraft:attack") == 1);
		assertTrue(PacketRegistry.get().idOf(ProtocolState.PLAY, PacketDirection.SERVERBOUND, "minecraft:interact") == 26);
		PacketWriter w = new PacketWriter();
		new Serverbound.Attack(300).write(w);
		assertArrayEquals(new byte[]{(byte) 0xAC, 0x02}, w.toByteArray());
		PacketWriter i = new PacketWriter();
		new Serverbound.Interact(5, 1, 0.1, -0.2, 0.05, true).write(i);
		PacketReader r = new PacketReader(i.toByteArray());
		assertEquals(5, r.readVarInt());
		assertEquals(1, r.readVarInt());
		double[] v = EntityPacketDecoder.lpVec3(r);
		assertEquals(0.09998168833546961, v[0], 0.0);
		assertTrue(r.readBoolean());
		assertEquals(0, r.remaining());
	}

	@Test
	void damageEventWithAndWithoutPosition() throws ProtocolException {
		Clientbound.DamageEvent a = assertInstanceOf(Clientbound.DamageEvent.class, ClientboundDecoder.decode(raw("minecraft:damage_event",
			new PacketWriter().writeVarInt(7).writeVarInt(31).writeVarInt(43).writeVarInt(43).writeBoolean(false).toByteArray())));
		assertEquals(7, a.entityId());
		assertEquals(31, a.damageType());
		assertEquals(42, a.causeId()); // ID + 1 auf dem Draht
		assertFalse(a.hasPosition());
		Clientbound.DamageEvent b = assertInstanceOf(Clientbound.DamageEvent.class, ClientboundDecoder.decode(raw("minecraft:damage_event",
			new PacketWriter().writeVarInt(7).writeVarInt(5).writeVarInt(0).writeVarInt(0).writeBoolean(true).writeDouble(1).writeDouble(2).writeDouble(3)
				.toByteArray())));
		assertEquals(-1, b.causeId());
		assertEquals(3.0, b.z());
	}

	@Test
	void mobEffectUpdateAndRemove() throws ProtocolException {
		Clientbound.UpdateMobEffect u = assertInstanceOf(Clientbound.UpdateMobEffect.class, ClientboundDecoder.decode(raw("minecraft:update_mob_effect",
			new PacketWriter().writeVarInt(7).writeVarInt(9).writeVarInt(1).writeVarInt(100).writeByte(6).toByteArray())));
		assertEquals(9, u.effect());
		assertEquals(1, u.amplifier());
		assertEquals(100, u.duration());
		assertEquals(6, u.flags());
		Clientbound.RemoveMobEffect r = assertInstanceOf(Clientbound.RemoveMobEffect.class, ClientboundDecoder.decode(raw("minecraft:remove_mob_effect",
			new PacketWriter().writeVarInt(7).writeVarInt(9).toByteArray())));
		assertEquals(9, r.effect());
	}

	@Test
	void translationPlaceholders() {
		Map<String, Object> msg = Map.of("translate", "death.attack.player", "with", List.of("A", Map.of("text", "B")));
		assertEquals("death.attack.player[A, B]", TextComponents.toPlainText(msg));
		assertEquals("A was slain by B", TextComponents.toPlainText(msg, k -> k.equals("death.attack.player") ? "%1$s was slain by %2$s" : null));
		assertEquals("A and B", TextComponents.toPlainText(Map.of("translate", "x", "with", List.of("A", "B")), k -> "%s and %s"));
		assertEquals("100% A", TextComponents.toPlainText(Map.of("translate", "x", "with", List.of("A")), k -> "100%% %s"));
	}
}
