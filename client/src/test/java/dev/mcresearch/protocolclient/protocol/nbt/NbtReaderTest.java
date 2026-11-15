package dev.mcresearch.protocolclient.protocol.nbt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NbtReaderTest {
	/** Minimaler NBT-Schreiber nur für Testdaten. */
	private static final class Nbt {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final DataOutputStream out = new DataOutputStream(this.bytes);

		Nbt type(final int t) throws IOException {
			this.out.writeByte(t);
			return this;
		}

		Nbt name(final String n) throws IOException {
			this.out.writeUTF(n);
			return this;
		}

		byte[] done() {
			return this.bytes.toByteArray();
		}
	}

	@Test
	void endTagMeansAbsent() throws ProtocolException {
		assertNull(NbtReader.readNetworkTag(new PacketReader(new byte[]{0})));
	}

	@Test
	void readsUnnamedRootCompoundWithAllTypes() throws IOException {
		Nbt n = new Nbt();
		n.type(10); // root compound, ohne Namen (Netzwerkformat)
		n.type(1).name("b"); n.out.writeByte(-1);
		n.type(2).name("s"); n.out.writeShort(300);
		n.type(3).name("i"); n.out.writeInt(42);
		n.type(4).name("l"); n.out.writeLong(1L << 40);
		n.type(5).name("f"); n.out.writeFloat(0.5f);
		n.type(6).name("d"); n.out.writeDouble(2.25);
		n.type(7).name("ba"); n.out.writeInt(2); n.out.write(new byte[]{1, 2});
		n.type(8).name("str"); n.out.writeUTF("Grüße");
		n.type(9).name("list"); n.out.writeByte(3); n.out.writeInt(2); n.out.writeInt(7); n.out.writeInt(8);
		n.type(11).name("ia"); n.out.writeInt(1); n.out.writeInt(-9);
		n.type(12).name("la"); n.out.writeInt(1); n.out.writeLong(99L);
		n.type(10).name("nested"); n.type(8).name("text"); n.out.writeUTF("hi"); n.type(0);
		n.type(0);
		byte[] data = n.done();
		PacketReader reader = new PacketReader(data);
		Map<?, ?> root = assertInstanceOf(Map.class, NbtReader.readNetworkTag(reader));
		reader.expectEnd("nbt");
		assertEquals((byte) -1, root.get("b"));
		assertEquals((short) 300, root.get("s"));
		assertEquals(42, root.get("i"));
		assertEquals(1L << 40, root.get("l"));
		assertEquals(0.5f, root.get("f"));
		assertEquals(2.25, root.get("d"));
		assertArrayEquals(new byte[]{1, 2}, (byte[]) root.get("ba"));
		assertEquals("Grüße", root.get("str"));
		assertEquals(List.of(7, 8), root.get("list"));
		assertArrayEquals(new int[]{-9}, (int[]) root.get("ia"));
		assertArrayEquals(new long[]{99L}, (long[]) root.get("la"));
		assertEquals("hi", ((Map<?, ?>) root.get("nested")).get("text"));
	}

	@Test
	void plainStringRootForTextComponents() throws IOException {
		Nbt n = new Nbt().type(8);
		n.out.writeUTF("Kicked");
		assertEquals("Kicked", NbtReader.readNetworkTag(new PacketReader(n.done())));
	}

	@Test
	void rejectsInvalidTypeNegativeLengthsAndOverruns() throws IOException {
		assertThrows(ProtocolException.class, () -> NbtReader.readNetworkTag(new PacketReader(new byte[]{13})));
		Nbt neg = new Nbt().type(7);
		neg.out.writeInt(-1);
		assertThrows(ProtocolException.class, () -> NbtReader.readNetworkTag(new PacketReader(neg.done())));
		Nbt huge = new Nbt().type(11);
		huge.out.writeInt(Integer.MAX_VALUE);
		assertThrows(ProtocolException.class, () -> NbtReader.readNetworkTag(new PacketReader(huge.done())));
		Nbt list = new Nbt().type(9);
		list.out.writeByte(0);
		list.out.writeInt(5);
		assertThrows(ProtocolException.class, () -> NbtReader.readNetworkTag(new PacketReader(list.done())));
		Nbt unterminated = new Nbt().type(10).type(3).name("x");
		unterminated.out.writeInt(1);
		assertThrows(ProtocolException.class, () -> NbtReader.readNetworkTag(new PacketReader(unterminated.done())));
	}

	@Test
	void rejectsExcessiveNesting() throws IOException {
		Nbt n = new Nbt();
		n.type(9);
		for (int i = 0; i < 600; i++) {
			n.out.writeByte(9);
			n.out.writeInt(1);
		}
		n.out.writeByte(0);
		n.out.writeInt(0);
		ProtocolException e = assertThrows(ProtocolException.class, () -> NbtReader.readNetworkTag(new PacketReader(n.done())));
		assertEquals(true, e.getMessage().contains("deeper"));
	}

	@Test
	void textComponentsToPlainText() {
		assertEquals("Laufzeittest beendet", TextComponents.toPlainText("Laufzeittest beendet"));
		assertEquals("Hallo Welt!", TextComponents.toPlainText(Map.of("text", "Hallo ", "extra", List.of("Welt", Map.of("text", "!")))));
		assertEquals("multiplayer.disconnect.kicked", TextComponents.toPlainText(Map.of("translate", "multiplayer.disconnect.kicked")));
		assertEquals("chat.type[Bot, hi]", TextComponents.toPlainText(Map.of("translate", "chat.type", "with", List.of("Bot", "hi"))));
	}
}
