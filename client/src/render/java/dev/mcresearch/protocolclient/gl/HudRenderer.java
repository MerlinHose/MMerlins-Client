package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.physics.Aabb;
import dev.mcresearch.protocolclient.physics.Shape;
import dev.mcresearch.protocolclient.view.Mat4;
import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Einfarbige Geometrie für Auswahl und HUD (M7, docs/27 §5): Linien in Weltkoordinaten (relativ zur Kamera, damit große
 * Koordinaten keine float-Genauigkeit kosten) und Rechtecke in Bildschirmpixeln. Ein gemeinsamer Streaming-Puffer; pro
 * Zeichenaufruf ein Upload ({@code GL_STREAM_DRAW}). Nur auf dem Grafikthread verwenden.
 */
final class HudRenderer implements AutoCloseable {
	private static final int FLOATS_PER_VERTEX = 7; // x y z r g b a
	private static final int MAX_VERTICES = 16384;

	static final String VERTEX = """
		#version 330 core
		layout(location = 0) in vec3 aPos;
		layout(location = 1) in vec4 aColor;
		uniform mat4 uViewProj;
		uniform int uScreenSpace;
		uniform vec2 uScreen;
		out vec4 vColor;
		void main() {
		    if (uScreenSpace == 1) {
		        gl_Position = vec4(aPos.x / uScreen.x * 2.0 - 1.0, 1.0 - aPos.y / uScreen.y * 2.0, 0.0, 1.0);
		    } else {
		        gl_Position = uViewProj * vec4(aPos, 1.0);
		    }
		    vColor = aColor;
		}
		""";

	static final String FRAGMENT = """
		#version 330 core
		in vec4 vColor;
		out vec4 fragColor;
		void main() {
		    fragColor = vColor;
		}
		""";

	private final ShaderProgram shader = new ShaderProgram(VERTEX, FRAGMENT);
	private final int vao;
	private final int vbo;
	private final FloatBuffer buffer = MemoryUtil.memAllocFloat(MAX_VERTICES * FLOATS_PER_VERTEX);
	private int vertices;

	HudRenderer() {
		this.vao = glGenVertexArrays();
		this.vbo = glGenBuffers();
		GlResources.created("hud-buffer");
		glBindVertexArray(this.vao);
		glBindBuffer(GL_ARRAY_BUFFER, this.vbo);
		glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTICES * FLOATS_PER_VERTEX * 4, GL_STREAM_DRAW);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 0L);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(1, 4, GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 12L);
		glEnableVertexAttribArray(1);
		glBindVertexArray(0);
	}

	/**
	 * Auswahlrahmen wie Vanilla ({@code LevelRenderer} zeichnet die Kanten der Umrissform, Farbe schwarz mit Alpha 0,4):
	 * Kanten jeder Box der Umrissform am Block (bx, by, bz). Innenkanten zusammengesetzter Formen (Treppen) werden nicht
	 * zusammengeführt – Abweichung von {@code VoxelShape#forAllEdges}, siehe docs/27 §5.
	 */
	void outline(final Mat4 viewProj, final Shape shape, final int bx, final int by, final int bz, final double camX, final double camY,
		final double camZ) {
		if (shape.isEmpty()) {
			return;
		}
		final double e = 0.002; // gegen Z-Fighting mit den Blockflächen
		for (Aabb b : shape.boxes()) {
			float x0 = (float) (bx + b.minX() - e - camX), y0 = (float) (by + b.minY() - e - camY), z0 = (float) (bz + b.minZ() - e - camZ);
			float x1 = (float) (bx + b.maxX() + e - camX), y1 = (float) (by + b.maxY() + e - camY), z1 = (float) (bz + b.maxZ() + e - camZ);
			float r = 0, g = 0, bl = 0, a = 0.4f;
			// 12 Kanten
			line(x0, y0, z0, x1, y0, z0, r, g, bl, a);
			line(x0, y1, z0, x1, y1, z0, r, g, bl, a);
			line(x0, y0, z1, x1, y0, z1, r, g, bl, a);
			line(x0, y1, z1, x1, y1, z1, r, g, bl, a);
			line(x0, y0, z0, x0, y1, z0, r, g, bl, a);
			line(x1, y0, z0, x1, y1, z0, r, g, bl, a);
			line(x0, y0, z1, x0, y1, z1, r, g, bl, a);
			line(x1, y0, z1, x1, y1, z1, r, g, bl, a);
			line(x0, y0, z0, x0, y0, z1, r, g, bl, a);
			line(x1, y0, z0, x1, y0, z1, r, g, bl, a);
			line(x0, y1, z0, x0, y1, z1, r, g, bl, a);
			line(x1, y1, z0, x1, y1, z1, r, g, bl, a);
		}
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glEnable(GL_DEPTH_TEST);
		glDepthMask(false);
		glLineWidth(1f);
		flush(GL_LINES, viewProj, 0, 0, false);
		glDepthMask(true);
		glDisable(GL_BLEND);
	}

	/** M9: Hitbox-Rahmen einer ausgewählten Entity (Weltkoordinaten), schwarz mit Alpha 0,4 wie der Blockrahmen. */
	void outlineBox(final Mat4 viewProj, final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ,
		final double camX, final double camY, final double camZ) {
		float x0 = (float) (minX - camX), y0 = (float) (minY - camY), z0 = (float) (minZ - camZ);
		float x1 = (float) (maxX - camX), y1 = (float) (maxY - camY), z1 = (float) (maxZ - camZ);
		float r = 0, g = 0, bl = 0, a = 0.6f;
		line(x0, y0, z0, x1, y0, z0, r, g, bl, a);
		line(x0, y1, z0, x1, y1, z0, r, g, bl, a);
		line(x0, y0, z1, x1, y0, z1, r, g, bl, a);
		line(x0, y1, z1, x1, y1, z1, r, g, bl, a);
		line(x0, y0, z0, x0, y1, z0, r, g, bl, a);
		line(x1, y0, z0, x1, y1, z0, r, g, bl, a);
		line(x0, y0, z1, x0, y1, z1, r, g, bl, a);
		line(x1, y0, z1, x1, y1, z1, r, g, bl, a);
		line(x0, y0, z0, x0, y0, z1, r, g, bl, a);
		line(x1, y0, z0, x1, y0, z1, r, g, bl, a);
		line(x0, y1, z0, x0, y1, z1, r, g, bl, a);
		line(x1, y1, z0, x1, y1, z1, r, g, bl, a);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glEnable(GL_DEPTH_TEST);
		glDepthMask(false);
		glLineWidth(1f);
		flush(GL_LINES, viewProj, 0, 0, false);
		glDepthMask(true);
		glDisable(GL_BLEND);
	}

	/** Bildschirmrechteck in Pixeln (Ursprung oben links), ARGB-Farbe; gesammelt bis {@link #flushScreen}. */
	void rect(final float x, final float y, final float w, final float h, final int argb) {
		float a = (argb >>> 24) / 255f, r = (argb >> 16 & 0xFF) / 255f, g = (argb >> 8 & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
		if (this.vertices + 6 > MAX_VERTICES) {
			return;
		}
		vertex(x, y, 0, r, g, b, a);
		vertex(x, y + h, 0, r, g, b, a);
		vertex(x + w, y + h, 0, r, g, b, a);
		vertex(x, y, 0, r, g, b, a);
		vertex(x + w, y + h, 0, r, g, b, a);
		vertex(x + w, y, 0, r, g, b, a);
	}

	/** Gedrehtes Rechteck: Mittelpunkt (cx, cy), Breite/Höhe, Drehung in Grad im Uhrzeigersinn (M10: eigener Armschwung). */
	void rotatedRect(final float cx, final float cy, final float w, final float h, final float degrees, final int argb) {
		float a = (argb >>> 24) / 255f, r = (argb >> 16 & 0xFF) / 255f, g = (argb >> 8 & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
		if (this.vertices + 6 > MAX_VERTICES) {
			return;
		}
		double rad = Math.toRadians(degrees);
		float c = (float) Math.cos(rad), sn = (float) Math.sin(rad), hw = w / 2, hh = h / 2;
		float[] xs = new float[4], ys = new float[4];
		float[][] corners = {{-hw, -hh}, {-hw, hh}, {hw, hh}, {hw, -hh}};
		for (int i = 0; i < 4; i++) {
			xs[i] = cx + corners[i][0] * c - corners[i][1] * sn;
			ys[i] = cy + corners[i][0] * sn + corners[i][1] * c;
		}
		vertex(xs[0], ys[0], 0, r, g, b, a);
		vertex(xs[1], ys[1], 0, r, g, b, a);
		vertex(xs[2], ys[2], 0, r, g, b, a);
		vertex(xs[0], ys[0], 0, r, g, b, a);
		vertex(xs[2], ys[2], 0, r, g, b, a);
		vertex(xs[3], ys[3], 0, r, g, b, a);
	}

	/** Gesammelte Bildschirmrechtecke mit normaler Alpha-Mischung zeichnen. */
	void flushScreen(final int screenW, final int screenH) {
		glDisable(GL_DEPTH_TEST);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		flush(GL_TRIANGLES, null, screenW, screenH, true);
		glDisable(GL_BLEND);
		glEnable(GL_DEPTH_TEST);
	}

	/**
	 * Fadenkreuz in der Bildmitte mit invertierender Mischung wie Vanilla ({@code Gui#renderCrosshair}:
	 * ONE_MINUS_DST_COLOR / ONE_MINUS_SRC_COLOR) – auf hellem und dunklem Grund sichtbar. Eigene Geometrie, keine Mojang-Grafik.
	 */
	void crosshair(final int screenW, final int screenH, final int scale) {
		float cx = screenW / 2f, cy = screenH / 2f;
		float len = 5.5f * scale, th = scale;
		rect(cx - len, cy - th / 2f, 2 * len, th, 0xFFFFFFFF);
		rect(cx - th / 2f, cy - len, th, len - th / 2f, 0xFFFFFFFF);
		rect(cx - th / 2f, cy + th / 2f, th, len - th / 2f, 0xFFFFFFFF);
		glDisable(GL_DEPTH_TEST);
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_ONE_MINUS_DST_COLOR, GL_ONE_MINUS_SRC_COLOR, GL_ONE, GL_ZERO);
		flush(GL_TRIANGLES, null, screenW, screenH, true);
		glDisable(GL_BLEND);
		glEnable(GL_DEPTH_TEST);
	}

	private void line(final float x0, final float y0, final float z0, final float x1, final float y1, final float z1, final float r, final float g,
		final float b, final float a) {
		if (this.vertices + 2 > MAX_VERTICES) {
			return;
		}
		vertex(x0, y0, z0, r, g, b, a);
		vertex(x1, y1, z1, r, g, b, a);
	}

	private void vertex(final float x, final float y, final float z, final float r, final float g, final float b, final float a) {
		this.buffer.put(x).put(y).put(z).put(r).put(g).put(b).put(a);
		this.vertices++;
	}

	private void flush(final int mode, final Mat4 viewProj, final int screenW, final int screenH, final boolean screen) {
		if (this.vertices == 0) {
			return;
		}
		this.buffer.flip();
		this.shader.use();
		this.shader.set("uScreenSpace", screen ? 1 : 0);
		if (screen) {
			this.shader.set("uScreen", screenW, screenH);
		} else {
			this.shader.set("uViewProj", viewProj);
		}
		glBindVertexArray(this.vao);
		glBindBuffer(GL_ARRAY_BUFFER, this.vbo);
		glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTICES * FLOATS_PER_VERTEX * 4, GL_STREAM_DRAW); // Orphaning
		glBufferSubData(GL_ARRAY_BUFFER, 0, this.buffer);
		glDrawArrays(mode, 0, this.vertices);
		glBindVertexArray(0);
		this.buffer.clear();
		this.vertices = 0;
	}

	@Override
	public void close() {
		glDeleteBuffers(this.vbo);
		glDeleteVertexArrays(this.vao);
		GlResources.deleted("hud-buffer");
		MemoryUtil.memFree(this.buffer);
		this.shader.close();
	}
}
