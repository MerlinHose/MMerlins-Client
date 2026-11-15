package dev.mcresearch.protocolclient.protocol.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mcresearch.protocolclient.protocol.ProtocolException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniJsonTest {
	@Test
	void parsesTypicalStatusResponse() throws ProtocolException {
		String json = "{\"description\":{\"text\":\"MinecraftResearch local test server\"},"
			+ "\"players\":{\"max\":20,\"online\":0},\"version\":{\"name\":\"26.3\",\"protocol\":777},\"enforcesSecureChat\":false}";
		Map<?, ?> root = assertInstanceOf(Map.class, MiniJson.parse(json));
		assertEquals(777L, ((Map<?, ?>) root.get("version")).get("protocol"));
		assertEquals("26.3", ((Map<?, ?>) root.get("version")).get("name"));
		assertEquals(20L, ((Map<?, ?>) root.get("players")).get("max"));
		assertEquals(Boolean.FALSE, root.get("enforcesSecureChat"));
	}

	@Test
	void handlesEscapesArraysNumbersAndNull() throws ProtocolException {
		assertEquals("a\"b\\c\nä", MiniJson.parse("\"a\\\"b\\\\c\\n\\u00e4\""));
		assertEquals(List.of(1L, -2.5, true, "x"), MiniJson.parse(" [1, -2.5, true, \"x\"] "));
		assertNull(MiniJson.parse("null"));
		assertEquals(1.0e3, MiniJson.parse("1e3"));
	}

	@Test
	void rejectsMalformedInput() {
		for (String bad : new String[]{"", "{", "{\"a\" 1}", "[1,]", "tru", "\"unterminated", "{\"a\":1} x", "\"\u0001\"", "01x"}) {
			assertThrows(ProtocolException.class, () -> MiniJson.parse(bad), bad);
		}
	}

	@Test
	void rejectsDeepNesting() {
		assertThrows(ProtocolException.class, () -> MiniJson.parse("[".repeat(100) + "]".repeat(100)));
	}
}
