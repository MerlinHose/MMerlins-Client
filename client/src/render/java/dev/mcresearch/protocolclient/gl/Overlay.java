package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.view.MeshBuilder;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/**
 * Text-Overlay (FPS, F3-Debug, Verbindungsstatus, Disconnect-Meldung), docs/24 §6. Der Text wird mit Java2D in ein Bild
 * gezeichnet (eigene Darstellung, keine Minecraft-Schrift) und nur bei Änderung als Textur hochgeladen.
 */
final class Overlay implements AutoCloseable {
	private static final int W = 1024;
	private static final int H = 512;
	private final ShaderProgram shader = new ShaderProgram(Shaders.OVERLAY_VERTEX, Shaders.OVERLAY_FRAGMENT);
	private final int texture;
	private final BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
	private final Font font = new Font(Font.MONOSPACED, Font.BOLD, 15);
	private final Font big = new Font(Font.SANS_SERIF, Font.BOLD, 26);
	private List<String> lastLines = List.of();
	private String lastBanner = "";
	private int lastScreenW = -1;
	private int wrappedLines;
	private int widestLine;
	private int lineCount;
	private int usedHeight;
	private GpuMesh quad;
	private int quadW;
	private int quadH;

	Overlay() {
		this.texture = glGenTextures();
		GlResources.created("texture");
		glBindTexture(GL_TEXTURE_2D, this.texture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
	}

	/** Zeichnet Textzeilen links oben und optional eine zentrierte Meldung (z. B. Disconnect). */
	void draw(final List<String> lines, final String banner, final int screenW, final int screenH) {
		if (!lines.equals(this.lastLines) || !banner.equals(this.lastBanner) || screenW != this.lastScreenW) {
			render(lines, banner, Math.min(W, screenW) - 16);
			this.lastLines = List.copyOf(lines);
			this.lastBanner = banner;
			this.lastScreenW = screenW;
		}
		if (this.usedHeight == 0) {
			return;
		}
		int x1 = Math.min(W, screenW);
		int y1 = Math.min(this.usedHeight, screenH);
		if (this.quad == null || x1 != this.quadW || y1 != this.quadH) {
			if (this.quad != null) {
				this.quad.close();
			}
			MeshBuilder b = new MeshBuilder(6, 6);
			float u1 = (float) x1 / W;
			float v1 = (float) y1 / H;
			int c = MeshBuilder.rgba(255, 255, 255, 255);
			b.quad(new float[]{0, y1, 0, x1, y1, 0, x1, 0, 0, 0, 0, 0}, new float[]{0, v1, u1, v1, u1, 0, 0, 0}, new int[]{c, c, c, c});
			this.quad = new GpuMesh(b.toArray());
			this.quadW = x1;
			this.quadH = y1;
		}
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		this.shader.use();
		glUniform2f(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "uScreen"), screenW, screenH);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, this.texture);
		this.quad.draw();
		glDisable(GL_BLEND);
		glEnable(GL_CULL_FACE);
		glEnable(GL_DEPTH_TEST);
	}

	/** M12: Zahl der zuletzt umbrochenen Zeilen (Prüfung der Hilfezeile bei 1280 px, docs/49 §4). */
	int wrappedLines() {
		return this.wrappedLines;
	}

	/** M12: Breite der breitesten gezeichneten Zeile in Pixeln und Zahl der gezeichneten Zeilen (nach dem Umbruch). */
	int widestLine() {
		return this.widestLine;
	}

	int lineCount() {
		return this.lineCount;
	}

	/**
	 * M12 (Fehler aus M8): Zeilen, die breiter als der Bildschirm bzw. das 1024-px-Textbild sind, werden an „, “, „ | “ oder
	 * Leerzeichen umbrochen (Folgezeilen eingerückt) statt abgeschnitten.
	 */
	static List<String> wrap(final String line, final int maxWidth, final java.util.function.ToIntFunction<String> width) {
		return dev.mcresearch.protocolclient.view.TextWrap.wrap(line, maxWidth, width);
	}

	private void render(final List<String> rawLines, final String banner, final int maxWidth) {
		List<String> lines = new java.util.ArrayList<>();
		Graphics2D mg = this.image.createGraphics();
		java.awt.FontMetrics fm = mg.getFontMetrics(this.font);
		mg.dispose();
		int wrapped = 0;
		for (String l : rawLines) {
			List<String> parts = wrap(l, Math.max(200, maxWidth - 16), fm::stringWidth);
			if (parts.size() > 1) {
				wrapped++;
			}
			lines.addAll(parts);
		}
		this.wrappedLines = wrapped;
		int widest = 0;
		for (String l : lines) {
			widest = Math.max(widest, fm.stringWidth(l));
		}
		this.widestLine = widest;
		this.lineCount = lines.size();
		Graphics2D g = this.image.createGraphics();
		try {
			g.setComposite(java.awt.AlphaComposite.Clear);
			g.fillRect(0, 0, W, H);
			g.setComposite(java.awt.AlphaComposite.SrcOver);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(this.font);
			int y = 6;
			int lh = g.getFontMetrics().getHeight();
			for (String line : lines) {
				int w = g.getFontMetrics().stringWidth(line);
				g.setColor(new Color(0, 0, 0, 140));
				g.fillRect(4, y, w + 8, lh);
				g.setColor(Color.WHITE);
				g.drawString(line, 8, y + g.getFontMetrics().getAscent());
				y += lh;
			}
			if (!banner.isEmpty()) {
				g.setFont(this.big);
				y += 30;
				int w = g.getFontMetrics().stringWidth(banner);
				g.setColor(new Color(120, 0, 0, 200));
				g.fillRect(4, y, w + 24, g.getFontMetrics().getHeight() + 12);
				g.setColor(Color.WHITE);
				g.drawString(banner, 16, y + 6 + g.getFontMetrics().getAscent());
				y += g.getFontMetrics().getHeight() + 12;
			}
			this.usedHeight = Math.min(H, y + 4);
		} finally {
			g.dispose();
		}
		ByteBuffer buf = MemoryUtil.memAlloc(W * this.usedHeight * 4);
		try {
			for (int yy = 0; yy < this.usedHeight; yy++) {
				for (int xx = 0; xx < W; xx++) {
					int argb = this.image.getRGB(xx, yy);
					buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
				}
			}
			buf.flip();
			glBindTexture(GL_TEXTURE_2D, this.texture);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, this.usedHeight, GL_RGBA, GL_UNSIGNED_BYTE, buf);
		} finally {
			MemoryUtil.memFree(buf);
		}
	}

	@Override
	public void close() {
		if (this.quad != null) {
			this.quad.close();
		}
		glDeleteTextures(this.texture);
		GlResources.deleted("texture");
		this.shader.close();
	}
}
