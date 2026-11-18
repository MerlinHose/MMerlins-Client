package dev.mcresearch.protocolclient.protocol.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;
import dev.mcresearch.protocolclient.view.skin.SkinManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M14 Phase E (docs/56 §3): {@code resolvable_profile} (Serialisierer 41) und Skin-Vorgabe des Mannequins. */
class ResolvableProfileTest {
	@Test
	void partialProfileWithSkinPatchDecodesAndResolvesWithoutNetwork() throws Exception {
		PacketWriter w = new PacketWriter();
		w.writeBoolean(false); // Teilprofil
		w.writeBoolean(true).writeString("Steve", 16); // Name
		w.writeBoolean(false); // keine UUID
		w.writeVarInt(0); // keine Eigenschaften
		w.writeBoolean(true).writeIdentifier("minecraft:entity/player/slim/alex"); // Textur
		w.writeBoolean(false); // kein Umhang
		w.writeBoolean(false); // keine Elytra
		w.writeBoolean(true).writeBoolean(true); // Modell: schmal
		w.writeVarInt(77); // Rest des Pakets bleibt lesbar
		PacketReader r = new PacketReader(w.toByteArray());
		GameProfile.Resolvable p = EntityPacketDecoder.resolvableProfile(r);
		assertEquals("Steve", p.name());
		assertNull(p.id());
		assertEquals("minecraft:entity/player/slim/alex", p.texture());
		assertTrue(p.slim());
		assertEquals(77, r.readVarInt());
		SkinManager skins = new SkinManager(null, null);
		assertEquals("entity/player/slim/alex", skins.mannequin(p).texture());
		assertTrue(skins.mannequin(p).slim());
	}

	@Test
	void fullProfileWithoutPatchUsesTheProfileDefaultSkin() throws Exception {
		UUID id = new UUID(0, 15); // floorMod(hash, 18) = 15 → breit/steve
		PacketWriter w = new PacketWriter();
		w.writeBoolean(true).writeUuid(id).writeString("Bob", 16).writeVarInt(1).writeString("textures", 64).writeString("e30=", 32767)
			.writeBoolean(false);
		w.writeBoolean(false).writeBoolean(false).writeBoolean(false).writeBoolean(false);
		GameProfile.Resolvable p = EntityPacketDecoder.resolvableProfile(new PacketReader(w.toByteArray()));
		assertEquals(id, p.id());
		assertEquals(1, p.properties().size());
		assertNull(p.slim());
		SkinManager skins = new SkinManager(null, null);
		assertEquals("skin:default/wide/steve", skins.mannequin(p).texture());
		assertFalse(skins.mannequin(p).slim());
		// leeres Standardprofil (Mannequin.DEFAULT_PROFILE): Null-UUID → Index 0 = schmal/alex
		assertEquals("skin:default/slim/alex", skins.mannequin(null).texture());
	}
}
