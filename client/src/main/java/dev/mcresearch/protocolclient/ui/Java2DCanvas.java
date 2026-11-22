package dev.mcresearch.protocolclient.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * {@link UiCanvas} mit Java2D (nur JDK, kein LWJGL): zeichnet in ein ARGB-Bild in physischen Pixeln, Koordinaten logisch × Skala.
 * Die Grafikschicht lädt das Bild als Textur hoch; Tests und Bildschirmfotos verwenden dieselbe Fläche, damit Messung und
 * Darstellung identisch sind. Eigene Schrift (System-SansSerif), keine Minecraft-Schrift.
 */
public final class Java2DCanvas extends UiCanvas {
	/** Schriftgröße in logischen Einheiten (Vanilla-Schrift ist 8 Einheiten hoch). */
	public static final double FONT_SIZE = 8.0;
	private final Deque<Shape> clips = new ArrayDeque<>();
	private BufferedImage image;
	private Graphics2D g;
	private double scale = 2;
	private Font font;
	private Font bold;
	private FontMetrics metrics;
	private double lineHeight;

	public Java2DCanvas() {
		this.image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		setScaleInternal(2);
	}

	private void setScaleInternal(final double s) {
		this.scale = s;
		this.font = new Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont((float) (FONT_SIZE * s));
		this.bold = this.font.deriveFont(Font.BOLD);
		Graphics2D mg = this.image.createGraphics();
		mg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		mg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		this.metrics = mg.getFontMetrics(this.font);
		mg.dispose();
		this.lineHeight = Math.ceil(this.metrics.getAscent() + this.metrics.getDescent()) / s;
	}

	/** Nur messen (Layout) bei Skala {@code s}. */
	public void measureAt(final double s) {
		if (s != this.scale) {
			setScaleInternal(s);
		}
	}

	/** Beginnt ein Bild der Größe {@code w × h} Pixel (wird wiederverwendet, wenn gleich groß) und löscht es. */
	public BufferedImage begin(final int w, final int h, final double s) {
		if (this.image.getWidth() != w || this.image.getHeight() != h) {
			this.image = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
		}
		if (s != this.scale) {
			setScaleInternal(s);
		}
		this.g = this.image.createGraphics();
		this.g.setComposite(AlphaComposite.Clear);
		this.g.fillRect(0, 0, w, h);
		this.g.setComposite(AlphaComposite.SrcOver);
		this.g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		this.g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		this.g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		this.clips.clear();
		resetStats();
		return this.image;
	}

	public void end() {
		if (this.g != null) {
			this.g.dispose();
			this.g = null;
		}
	}

	public BufferedImage image() {
		return this.image;
	}

	private static Color color(final int argb) {
		return new Color(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24);
	}

	private Rectangle2D.Double px(final Rect r) {
		// auf ganze Pixel runden: scharfe Kanten ohne Kantenglättung
		double x0 = Math.round(r.x() * this.scale), y0 = Math.round(r.y() * this.scale);
		double x1 = Math.round(r.right() * this.scale), y1 = Math.round(r.bottom() * this.scale);
		return new Rectangle2D.Double(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
	}

	@Override
	public void fill(final Rect r, final int argb) {
		if (this.g == null || (argb >>> 24) == 0) {
			return;
		}
		this.g.setColor(color(argb));
		this.g.fill(px(r));
	}

	@Override
	public void gradient(final Rect r, final int argbTop, final int argbBottom) {
		if (this.g == null) {
			return;
		}
		Rectangle2D.Double p = px(r);
		this.g.setPaint(new GradientPaint(0, (float) p.y, color(argbTop), 0, (float) (p.y + p.height), color(argbBottom)));
		this.g.fill(p);
	}

	@Override
	public void text(final String s, final double x, final double y, final int argb, final boolean shadow) {
		if (this.g == null || s.isEmpty()) {
			return;
		}
		this.g.setFont(this.font);
		float px = (float) (x * this.scale);
		float py = (float) (y * this.scale + this.metrics.getAscent());
		if (shadow) {
			int a = argb >>> 24;
			this.g.setColor(new Color(0, 0, 0, Math.min(255, a * 3 / 4)));
			float o = (float) Math.max(1, Math.round(this.scale / 2.0));
			this.g.drawString(s, px + o, py + o);
		}
		this.g.setColor(color(argb));
		this.g.drawString(s, px, py);
	}

	@Override
	public void textLarge(final String s, final Rect box, final double factor, final int argb) {
		if (this.g == null) {
			return;
		}
		Font f = this.bold.deriveFont((float) (FONT_SIZE * this.scale * factor));
		FontMetrics fm = this.g.getFontMetrics(f);
		double w = fm.stringWidth(s) / this.scale;
		if (w > box.w()) {
			reportOverflow(s, w, box.w());
		}
		float px = (float) ((box.x() + (box.w() - w) / 2) * this.scale);
		float py = (float) ((box.y() + box.h() / 2) * this.scale + (fm.getAscent() - fm.getDescent()) / 2.0);
		this.g.setFont(f);
		float o = (float) Math.max(1, Math.round(this.scale * factor / 2.0));
		this.g.setColor(new Color(0, 0, 0, 160));
		this.g.drawString(s, px + o, py + o);
		this.g.setColor(color(argb));
		this.g.drawString(s, px, py);
	}

	@Override
	public double textWidth(final String s) {
		return this.metrics.getStringBounds(s, null).getWidth() / this.scale;
	}

	@Override
	public double lineHeight() {
		return this.lineHeight;
	}

	@Override
	public void pushClip(final Rect r) {
		if (this.g == null) {
			return;
		}
		this.clips.push(this.g.getClip() == null ? new Rectangle2D.Double(0, 0, this.image.getWidth(), this.image.getHeight()) : this.g.getClip());
		this.g.clip(px(r));
	}

	@Override
	public void popClip() {
		if (this.g == null || this.clips.isEmpty()) {
			return;
		}
		this.g.setClip(this.clips.pop());
	}

	@Override
	public double scale() {
		return this.scale;
	}

	/** Für Tests: Transformation ist immer die Einheit (Pixelkoordinaten werden selbst berechnet). */
	AffineTransform transform() {
		return this.g == null ? new AffineTransform() : this.g.getTransform();
	}
}
