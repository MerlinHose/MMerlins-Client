package dev.mcresearch.protocolclient.view;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * M12 (Fehler aus M8, docs/49 §4): Zeilen, die breiter als der Bildschirm bzw. das Textbild sind, werden an „, “, „ | “ oder Leerzeichen
 * umbrochen (Folgezeilen um 2 Zeichen eingerückt) statt abgeschnitten. Ohne passende Umbruchstelle wird hart getrennt. Reines JDK,
 * vom grafischen Overlay verwendet.
 */
public final class TextWrap {
	private TextWrap() {
	}

	public static List<String> wrap(final String line, final int maxWidth, final ToIntFunction<String> width) {
		List<String> out = new ArrayList<>();
		String rest = line;
		int indent = 0; // Länge der eigenen Einrückung am Anfang von rest (Folgezeilen: 2)
		// Jeder Durchlauf verbraucht mindestens ein Zeichen hinter der Einrückung (M12-Korrektur: zuvor konnte die Einrückung selbst
		// als Umbruchstelle gelten – Endlosschleife und OutOfMemoryError bei langen Wörtern, Regression m10-gfx)
		while (width.applyAsInt(rest) > maxWidth && rest.length() > indent + 1) {
			int cut = -1;
			for (int i = rest.length() - 1; i > indent; i--) {
				char c = rest.charAt(i);
				if ((c == ' ' || c == ',') && width.applyAsInt(rest.substring(0, i + 1)) <= maxWidth) {
					cut = i + 1;
					break;
				}
			}
			if (cut <= indent) {
				// keine Umbruchstelle: längstes passendes Stück, mindestens ein Zeichen
				cut = indent + 1;
				while (cut < rest.length() && width.applyAsInt(rest.substring(0, cut + 1)) <= maxWidth) {
					cut++;
				}
			}
			out.add(rest.substring(0, cut).stripTrailing());
			rest = "  " + rest.substring(cut).stripLeading();
			indent = 2;
		}
		if (indent == 0 || !rest.isBlank()) {
			out.add(rest);
		}
		return out;
	}
}
