package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.view.MeshBuilder;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.lwjgl.system.MemoryUtil;

/**
 * Eine mit Java2D gezeichnete HUD-Fläche (eigene Darstellung, keine Minecraft-Grafiken), als Textur an eine Bildschirmposition
 * gezeichnet (M7, docs/27 §5). Neu gezeichnet und hochgeladen wird nur, wenn sich der Inhaltsschlüssel oder die Größe ändert.
 */
final class TextPanel implements AutoCloseable {
	private final ShaderProgram shader = new ShaderProgram(Shaders.OVERLAY_VERTEX, Shaders.OVERLAY_FRAGMENT);
	private final int texture = glGenTextures();
	private BufferedImage image;
	private String key = null;
	private GpuMesh quad;
	private int quadX = Integer.MIN_VALUE;
	private int quadY;
	private long uploads;

	TextPanel() {
		GlResources.created("texture");
		glBindTexture(GL_TEXTURE_2D, this.texture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	}

	/**
	 * Zeichnet die Fläche (w × h Pixel) mit der oberen linken Ecke bei (x, y). {@code painter} wird nur aufgerufen, wenn sich
	 * {@code contentKey} oder die Größe geändert hat.
	 */
	void draw(final String contentKey, final int x, final int y, final int w, final int h, final Consumer<Graphics2D> painter, final int screenW,
		final int screenH) {
		if (w <= 0 || h <= 0) {
			return;
		}
		boolean resized = this.image == null || this.image.getWidth() != w || this.image.getHeight() != h;
		if (resized || !contentKey.equals(this.key)) {
			if (resized) {
				this.image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			}
			Graphics2D g = this.image.createGraphics();
			try {
				g.setComposite(java.awt.AlphaComposite.Clear);
				g.fillRect(0, 0, w, h);
				g.setComposite(java.awt.AlphaComposite.SrcOver);
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				painter.accept(g);
			} finally {
				g.dispose();
			}
			upload(resized);
			this.key = contentKey;
		}
		if (this.quad == null || resized || x != this.quadX || y != this.quadY) {
			if (this.quad != null) {
				this.quad.close();
			}
			MeshBuilder b = new MeshBuilder(6, 6);
			int c = MeshBuilder.rgba(255, 255, 255, 255);
			b.quad(new float[]{x, y + h, 0, x + w, y + h, 0, x + w, y, 0, x, y, 0}, new float[]{0, 1, 1, 1, 1, 0, 0, 0}, new int[]{c, c, c, c});
			this.quad = new GpuMesh(b.toArray());
			this.quadX = x;
			this.quadY = y;
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

	private void upload(final boolean resized) {
		int w = this.image.getWidth(), h = this.image.getHeight();
		ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
		try {
			int[] row = new int[w];
			for (int yy = 0; yy < h; yy++) {
				this.image.getRGB(0, yy, w, 1, row, 0, w);
				for (int argb : row) {
					buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
				}
			}
			buf.flip();
			glBindTexture(GL_TEXTURE_2D, this.texture);
			if (resized) {
				glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
			} else {
				glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
			}
		} finally {
			MemoryUtil.memFree(buf);
		}
		this.uploads++;
	}

	long uploads() {
		return this.uploads;
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
