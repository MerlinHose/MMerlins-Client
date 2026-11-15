package dev.mcresearch.protocolclient.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

/** M12 (docs/49 §4): Zeilenumbruch des HUD – Hilfezeile bei 1280 px und Regression der Endlosschleife (m10-gfx, OutOfMemoryError). */
class TextWrapTest {
	/** Feste Zeichenbreite 8 px wie eine Monospace-Schrift. */
	private static final ToIntFunction<String> W = s -> s.length() * 8;

	@Test
	void helpLineIsWrappedAtSeparatorsAndNothingIsLost() {
		String help = "Inventar: Linksklick nehmen/ablegen, Rechts teilen, Shift schnell, Ziehen verteilen, 1–9/F tauschen, Q werfen, E/Esc schließen";
		List<String> lines = TextWrap.wrap(help, 1000, W);
		assertTrue(lines.size() > 1);
		for (String l : lines) {
			assertTrue(W.applyAsInt(l) <= 1000, l);
		}
		assertEquals(help.replace(" ", ""), String.join("", lines).replace(" ", ""), "no characters dropped");
		assertTrue(lines.get(1).startsWith("  "), "continuation lines are indented");
	}

	@Test
	void shortLineIsUnchanged() {
		assertEquals(List.of("FPS 60"), TextWrap.wrap("FPS 60", 1000, W));
	}

	/** Regression: Folgezeile, deren erstes Wort breiter ist als die Zeile – früher galt die Einrückung als Umbruchstelle (Endlosschleife). */
	@Test
	void longWordAfterABreakTerminates() {
		String line = "Tod: " + "x".repeat(300) + " Ende";
		List<String> lines = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> TextWrap.wrap(line, 200, W));
		for (String l : lines) {
			assertTrue(W.applyAsInt(l) <= 200, l);
		}
		assertEquals(line.replace(" ", ""), String.join("", lines).replace(" ", ""));
	}

	@Test
	void tinyWidthStillTerminates() {
		List<String> lines = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> TextWrap.wrap("abc def ghi", 1, W));
		assertEquals("abcdefghi", String.join("", lines).replace(" ", ""));
	}
}
