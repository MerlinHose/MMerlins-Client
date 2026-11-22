package dev.mcresearch.protocolclient.ui;

/**
 * Zeichenfläche ohne Ausgabe mit fester Zeichenbreite – Messmodell für Layout ohne Grafik (Unit-Tests, Startwert des
 * {@link ScreenManager}). Breiten sind bewusst großzügig (breiter als die Java2D-Schrift), damit Tests Überläufe sicher finden.
 */
public class EstimateCanvas extends UiCanvas {
	private final double charWidth;
	private final double lineHeight;
	private final double scale;
	private int clipDepth;

	public EstimateCanvas() {
		this(5.6, 9, 2);
	}

	public EstimateCanvas(final double charWidth, final double lineHeight, final double scale) {
		this.charWidth = charWidth;
		this.lineHeight = lineHeight;
		this.scale = scale;
	}

	@Override
	public void fill(final Rect r, final int argb) {
	}

	@Override
	public void gradient(final Rect r, final int argbTop, final int argbBottom) {
	}

	@Override
	public void text(final String s, final double x, final double y, final int argb, final boolean shadow) {
	}

	@Override
	public double textWidth(final String s) {
		return s.codePointCount(0, s.length()) * this.charWidth;
	}

	@Override
	public double lineHeight() {
		return this.lineHeight;
	}

	@Override
	public void pushClip(final Rect r) {
		this.clipDepth++;
	}

	@Override
	public void popClip() {
		this.clipDepth--;
	}

	public int clipDepth() {
		return this.clipDepth;
	}

	@Override
	public double scale() {
		return this.scale;
	}
}
