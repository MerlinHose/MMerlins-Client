package dev.mcresearch.protocolclient.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Zeichenfläche der Menüs in logischen GUI-Einheiten (docs/59 §3). Die Grafikschicht (Java2D → OpenGL-Textur) und die
 * Unit-Tests (Messmodell ohne Ausgabe) implementieren sie; Screens und Widgets kennen nur diese Schnittstelle.
 */
public abstract class UiCanvas {
	public enum Align {
		LEFT, CENTER, RIGHT
	}

	private int overflows;
	private final List<String> overflowSamples = new ArrayList<>();
	private final List<Rect> drawnTextBoxes = new ArrayList<>();

	public abstract void fill(Rect r, int argb);

	public abstract void gradient(Rect r, int argbTop, int argbBottom);

	/** Text mit der Oberkante bei {@code y}. */
	public abstract void text(String s, double x, double y, int argb, boolean shadow);

	public abstract double textWidth(String s);

	/**
	 * Großer Text (Logo, Überschriften) um {@code factor} vergrößert, zentriert im Kasten. Die Grundform zeichnet normal groß;
	 * die Java2D-Fläche skaliert die Schrift.
	 */
	public void textLarge(final String s, final Rect box, final double factor, final int argb) {
		textIn(s, box, Align.CENTER, argb, true);
	}

	/** Zeilenhöhe (Schrift) in logischen Einheiten. */
	public abstract double lineHeight();

	public abstract void pushClip(Rect r);

	public abstract void popClip();

	/** Physische Pixel je logischer Einheit (GUI-Skalierung). */
	public abstract double scale();

	/** Ein physisches Pixel in logischen Einheiten (feine Linien). */
	public double hairline() {
		return 1.0 / scale();
	}

	public void border(final Rect r, final double t, final int argb) {
		fill(new Rect(r.x(), r.y(), r.w(), t), argb);
		fill(new Rect(r.x(), r.bottom() - t, r.w(), t), argb);
		fill(new Rect(r.x(), r.y() + t, t, Math.max(0, r.h() - 2 * t)), argb);
		fill(new Rect(r.right() - t, r.y() + t, t, Math.max(0, r.h() - 2 * t)), argb);
	}

	/**
	 * Text in einem Kasten (senkrecht zentriert). Passt er nicht, wird er mit „…“ gekürzt und als Überlauf gezählt – die
	 * GUI-Tests verlangen 0 Überläufe bei allen geprüften Auflösungen (docs/62 §4).
	 */
	public void textIn(final String s, final Rect box, final Align align, final int argb, final boolean shadow) {
		String t = s;
		double w = textWidth(t);
		if (w > box.w() + 1e-6) {
			this.overflows++;
			if (this.overflowSamples.size() < 20) {
				this.overflowSamples.add(s + " (" + Math.round(w) + " > " + Math.round(box.w()) + ")");
			}
			t = ellipsize(s, box.w());
			w = textWidth(t);
		}
		double x = switch (align) {
			case LEFT -> box.x();
			case CENTER -> box.x() + (box.w() - w) / 2;
			case RIGHT -> box.right() - w;
		};
		double y = box.y() + (box.h() - lineHeight()) / 2;
		this.drawnTextBoxes.add(new Rect(x, y, w, lineHeight()));
		text(t, x, y, argb, shadow);
	}

	/** Meldet einen nicht vollständig darstellbaren Inhalt (z. B. mehrzeiliger Text höher als sein Kasten). */
	public void reportOverflow(final String what, final double needed, final double available) {
		this.overflows++;
		if (this.overflowSamples.size() < 20) {
			this.overflowSamples.add(what + " (" + Math.round(needed) + " > " + Math.round(available) + ")");
		}
	}

	/** Kürzt {@code s}, bis es mit „…“ in {@code width} passt. */
	public String ellipsize(final String s, final double width) {
		if (textWidth(s) <= width) {
			return s;
		}
		String dots = "…";
		int n = s.length();
		while (n > 0 && textWidth(s.substring(0, n) + dots) > width) {
			n--;
		}
		return n == 0 ? "" : s.substring(0, n) + dots;
	}

	/** Bricht {@code s} an Leerzeichen in Zeilen der Breite {@code width} um (lange Wörter werden hart getrennt). */
	public List<String> wrap(final String s, final double width) {
		List<String> out = new ArrayList<>();
		for (String para : s.split("\n", -1)) {
			StringBuilder line = new StringBuilder();
			for (String word : para.split(" ")) {
				String cand = line.isEmpty() ? word : line + " " + word;
				if (textWidth(cand) <= width) {
					line.setLength(0);
					line.append(cand);
					continue;
				}
				if (!line.isEmpty()) {
					out.add(line.toString());
					line.setLength(0);
				}
				String rest = word;
				while (textWidth(rest) > width && rest.length() > 1) {
					int k = rest.length();
					while (k > 1 && textWidth(rest.substring(0, k)) > width) {
						k--;
					}
					out.add(rest.substring(0, k));
					rest = rest.substring(k);
				}
				line.append(rest);
			}
			out.add(line.toString());
		}
		return out;
	}

	public int overflows() {
		return this.overflows;
	}

	public List<String> overflowSamples() {
		return List.copyOf(this.overflowSamples);
	}

	/** Lage aller über {@link #textIn} gezeichneten Texte seit {@link #resetStats()} (Prüfung „innerhalb des Fensters“). */
	public List<Rect> drawnTextBoxes() {
		return List.copyOf(this.drawnTextBoxes);
	}

	public void resetStats() {
		this.overflows = 0;
		this.overflowSamples.clear();
		this.drawnTextBoxes.clear();
	}
}
