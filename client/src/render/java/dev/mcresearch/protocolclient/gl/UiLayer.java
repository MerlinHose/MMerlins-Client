package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.ui.Java2DCanvas;
import dev.mcresearch.protocolclient.ui.ScreenManager;
import dev.mcresearch.protocolclient.view.MeshBuilder;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Menüebene (docs/59 §5): Der {@link ScreenManager} zeichnet mit {@link Java2DCanvas} in ein ARGB-Bild in Fenstergröße; das Bild
 * wird nur nach Änderungen (Eingabe, Zustand, Animation) als Textur hochgeladen – ohne Pixelumrechnung (GL_BGRA,
 * UNSIGNED_INT_8_8_8_8_REV entspricht dem int-ARGB-Format). Ein Bildschirmviereck, eine Textur; Freigabe in {@link #close()}.
 */
final class UiLayer implements AutoCloseable {
	private final ShaderProgram shader = new ShaderProgram(Shaders.OVERLAY_VERTEX, Shaders.OVERLAY_FRAGMENT);
	private final int texture = glGenTextures();
	private final Java2DCanvas canvas = new Java2DCanvas();
	private GpuMesh quad;
	private int texW;
	private int texH;
	private long repaints;
	private double lastRepaintMs;
	private int lastOverflows;

	UiLayer() {
		GlResources.created("texture");
		glBindTexture(GL_TEXTURE_2D, this.texture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}

	/** Messfläche für das Layout des ScreenManagers (gleiche Schrift wie beim Zeichnen). */
	Java2DCanvas canvas() {
		return this.canvas;
	}

	/** Zeichnet die Menüs; neu gerendert wird nur bei {@code dirty} oder geänderter Größe. */
	void draw(final ScreenManager m, final boolean dirty) {
		int w = m.pixelWidth(), h = m.pixelHeight();
		boolean resized = w != this.texW || h != this.texH;
		if (dirty || resized) {
			long t0 = System.nanoTime();
			BufferedImage img = this.canvas.begin(w, h, m.scale());
			try {
				m.render(this.canvas);
			} finally {
				this.canvas.end();
			}
			this.lastOverflows = this.canvas.overflows();
			int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
			glBindTexture(GL_TEXTURE_2D, this.texture);
			glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
			if (resized) {
				glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, px);
				this.texW = w;
				this.texH = h;
				if (this.quad != null) {
					this.quad.close();
				}
				MeshBuilder b = new MeshBuilder(6, 6);
				int c = MeshBuilder.rgba(255, 255, 255, 255);
				b.quad(new float[]{0, h, 0, w, h, 0, w, 0, 0, 0, 0, 0}, new float[]{0, 1, 1, 1, 1, 0, 0, 0}, new int[]{c, c, c, c});
				this.quad = new GpuMesh(b.toArray());
			} else {
				glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, px);
			}
			this.repaints++;
			this.lastRepaintMs = (System.nanoTime() - t0) / 1e6;
		}
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		this.shader.use();
		glUniform2f(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "uScreen"), w, h);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, this.texture);
		this.quad.draw();
		glDisable(GL_BLEND);
		glEnable(GL_CULL_FACE);
		glEnable(GL_DEPTH_TEST);
	}

	long repaints() {
		return this.repaints;
	}

	double lastRepaintMs() {
		return this.lastRepaintMs;
	}

	/** Gekürzte Texte im zuletzt gezeichneten Bild (GUI-Test: muss 0 sein). */
	int lastOverflows() {
		return this.lastOverflows;
	}

	java.util.List<String> overflowSamples() {
		return this.canvas.overflowSamples();
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
