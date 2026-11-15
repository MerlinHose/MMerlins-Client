package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.view.Mat4;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import org.lwjgl.system.MemoryUtil;

/**
 * Wolken (docs/48 §2.3), eigene Umsetzung des Verfahrens von 26.3 für OpenGL 3.3: Zellen 12 × 4 × 12 Blöcke aus
 * {@code clouds.png} (Alpha ≥ 10 = Wolke), Drift 0,03 Blöcke/Tick in +X, Versatz z 3,96. Die Flächenliste (je Fläche
 * Zellenversatz x/z und Richtung/Flags) liegt in einem Texture Buffer; der Vertexshader erzeugt die Ecken über
 * {@code gl_VertexID} (6 Vertices je Fläche). Neubau nur beim Wechsel der Zelle unter der Kamera oder der Lage zur
 * Wolkenschicht (darüber/darin/darunter). Farbe {@code cloud_color}, Höhe {@code cloud_height}, Ausblendung über
 * {@code cloud_fog_end_distance}. Durchscheinend, Tiefe wird geschrieben, Rückseiten werden verworfen.
 */
final class CloudRenderer implements AutoCloseable {
	private static final String VERTEX = """
		#version 330 core
		uniform mat4 uProj;
		uniform mat4 uView;
		uniform vec4 uCloudColor;
		uniform vec3 uCloudOffset;
		uniform vec3 uCellSize;
		uniform isamplerBuffer uFaces;
		out float vDistance;
		out vec4 vColor;
		const int packedX = 0xF03CC3;
		const int packedY = 0x6666F0;
		const int packedZ = 0xC3F066;
		const int corner[6] = int[](0, 1, 2, 0, 2, 3);
		const vec4 faceColors[6] = vec4[](vec4(0.7, 0.7, 0.7, 1.0), vec4(1.0), vec4(0.8, 0.8, 0.8, 1.0), vec4(0.8, 0.8, 0.8, 1.0),
		    vec4(0.9, 0.9, 0.9, 1.0), vec4(0.9, 0.9, 0.9, 1.0));
		vec3 vertexOf(int index) {
		    return vec3(float((packedX >> index) & 1), float((packedY >> index) & 1), float((packedZ >> index) & 1));
		}
		void main() {
		    int face = gl_VertexID / 6;
		    int q = corner[gl_VertexID % 6];
		    int cellX = texelFetch(uFaces, face * 3).r;
		    int cellZ = texelFetch(uFaces, face * 3 + 1).r;
		    int flags = texelFetch(uFaces, face * 3 + 2).r;
		    int dir = flags & 7;
		    bool inside = (flags & 16) != 0;
		    bool top = (flags & 32) != 0;
		    vec3 v = vertexOf(dir * 4 + (inside ? 3 - q : q));
		    vec3 pos = v * uCellSize + vec3(cellX, 0, cellZ) * uCellSize + uCloudOffset;
		    gl_Position = uProj * uView * vec4(pos, 1.0);
		    vDistance = length(pos);
		    vColor = (top ? faceColors[1] : faceColors[dir]) * uCloudColor;
		}
		""";
	private static final String FRAGMENT = """
		#version 330 core
		in float vDistance;
		in vec4 vColor;
		uniform float uCloudEnd;
		out vec4 fragColor;
		void main() {
		    vec4 c = vColor;
		    float f = vDistance <= 0.0 ? 0.0 : vDistance >= uCloudEnd ? 1.0 : vDistance / uCloudEnd;
		    c.a *= 1.0 - f;
		    fragColor = c;
		}
		""";

	private final ShaderProgram shader;
	private final int vao;
	private final int buffer;
	private final int texture;
	private final long[] cells;
	private final int width;
	private final int height;
	private int faces;
	private int prevCellX = Integer.MIN_VALUE;
	private int prevCellZ = Integer.MIN_VALUE;
	private int prevRelative = -1;
	private int prevRadius = -1;
	private long rebuilds;

	CloudRenderer(final Path assets) {
		this.shader = new ShaderProgram(VERTEX, FRAGMENT);
		SkyRenderer.SimpleTexture tex = SkyRenderer.SimpleTexture.load(assets, "assets/minecraft/textures/environment/clouds.png", false);
		this.width = tex.width;
		this.height = tex.height;
		this.cells = new long[this.width * this.height];
		for (int y = 0; y < this.height; y++) {
			for (int x = 0; x < this.width; x++) {
				int c = tex.argb[x + y * this.width];
				if ((c >>> 24) < 10) {
					continue;
				}
				boolean n = empty(tex, x, Math.floorMod(y - 1, this.height));
				boolean e = empty(tex, Math.floorMod(x + 1, this.height), y);
				boolean s = empty(tex, x, Math.floorMod(y + 1, this.height));
				boolean w = empty(tex, Math.floorMod(x - 1, this.height), y);
				this.cells[x + y * this.width] = (c & 0xFFFFFFFFL) << 4 | (n ? 8 : 0) | (e ? 4 : 0) | (s ? 2 : 0) | (w ? 1 : 0);
			}
		}
		tex.close();
		this.vao = glGenVertexArrays();
		GlResources.created("vao");
		this.buffer = glGenBuffers();
		GlResources.created("buffer");
		this.texture = glGenTextures();
		GlResources.created("texture");
		glBindBuffer(GL_TEXTURE_BUFFER, this.buffer);
		glBufferData(GL_TEXTURE_BUFFER, 6L, GL_DYNAMIC_DRAW);
		glBindTexture(GL_TEXTURE_BUFFER, this.texture);
		glTexBuffer(GL_TEXTURE_BUFFER, GL_R16I, this.buffer);
		glBindBuffer(GL_TEXTURE_BUFFER, 0);
		glBindTexture(GL_TEXTURE_BUFFER, 0);
	}

	private static boolean empty(final SkyRenderer.SimpleTexture t, final int x, final int y) {
		return (t.argb[x + y * t.width] >>> 24) < 10;
	}

	long rebuilds() {
		return this.rebuilds;
	}

	int faces() {
		return this.faces;
	}

	/**
	 * @param color     {@code cloud_color} (r, g, b, a)
	 * @param bottomY   {@code cloud_height}
	 * @param rangeChunks Wolkenweite in Chunks (Vorgabe 128)
	 */
	void draw(final Mat4 proj, final Mat4 viewRotation, final float[] color, final float bottomY, final int rangeChunks, final double camX,
		final double camY, final double camZ, final long gameTime, final float partial, final float cloudEnd) {
		if (color[3] <= 0f || this.width == 0) {
			return;
		}
		int radiusCells = (int) Math.ceil(rangeChunks * 16 / 12.0f);
		float relBottom = (float) (bottomY - camY);
		float relTop = relBottom + 4.0f;
		int relative = relTop < 0f ? 0 : relBottom > 0f ? 2 : 1; // über, in, unter der Wolkenschicht
		float offset = (float) (gameTime % (this.width * 400L)) + partial;
		double cloudX = camX + offset * 0.030000001f;
		double cloudZ = camZ + 3.96f;
		double tw = this.width * 12.0;
		double th = this.height * 12.0;
		cloudX -= Math.floor(cloudX / tw) * tw;
		cloudZ -= Math.floor(cloudZ / th) * th;
		int cellX = (int) Math.floor(cloudX / 12.0);
		int cellZ = (int) Math.floor(cloudZ / 12.0);
		float xIn = (float) (cloudX - cellX * 12.0f);
		float zIn = (float) (cloudZ - cellZ * 12.0f);
		if (cellX != this.prevCellX || cellZ != this.prevCellZ || relative != this.prevRelative || radiusCells != this.prevRadius) {
			rebuild(cellX, cellZ, relative, radiusCells);
			this.prevCellX = cellX;
			this.prevCellZ = cellZ;
			this.prevRelative = relative;
			this.prevRadius = radiusCells;
		}
		if (this.faces == 0) {
			return;
		}
		glEnable(GL_DEPTH_TEST);
		glDepthMask(true);
		glEnable(GL_CULL_FACE);
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		this.shader.use();
		this.shader.set("uProj", proj);
		this.shader.set("uView", viewRotation);
		this.shader.set("uCloudColor", color[0], color[1], color[2], color[3]);
		this.shader.set("uCloudOffset", -xIn, relBottom, -zIn);
		this.shader.set("uCellSize", 12f, 4f, 12f);
		this.shader.set("uCloudEnd", cloudEnd);
		this.shader.set("uFaces", 3);
		glActiveTexture(GL_TEXTURE3);
		glBindTexture(GL_TEXTURE_BUFFER, this.texture);
		glBindVertexArray(this.vao);
		glDrawArrays(GL_TRIANGLES, 0, this.faces * 6);
		glBindVertexArray(0);
		glBindTexture(GL_TEXTURE_BUFFER, 0);
		glActiveTexture(GL_TEXTURE0);
		glDisable(GL_BLEND);
	}

	/** Flächenliste ringweise von der Mitte nach außen (wie die Vorlage), Kreis mit Radius in Zellen. */
	private void rebuild(final int centerX, final int centerZ, final int relative, final int radius) {
		java.util.List<short[]> list = new java.util.ArrayList<>();
		for (int ring = 0; ring <= 2 * radius; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				int dz = ring - Math.abs(dx);
				if (dz >= 0 && dz <= radius && dx * dx + dz * dz <= radius * radius) {
					if (dz != 0) {
						cell(list, centerX, centerZ, dx, -dz, relative);
					}
					cell(list, centerX, centerZ, dx, dz, relative);
				}
			}
		}
		this.faces = list.size();
		ShortBuffer buf = MemoryUtil.memAllocShort(Math.max(3, this.faces * 3));
		try {
			for (short[] f : list) {
				buf.put(f);
			}
			buf.flip();
			glBindBuffer(GL_TEXTURE_BUFFER, this.buffer);
			glBufferData(GL_TEXTURE_BUFFER, buf, GL_DYNAMIC_DRAW);
			glBindBuffer(GL_TEXTURE_BUFFER, 0);
		} finally {
			MemoryUtil.memFree(buf);
		}
		this.rebuilds++;
	}

	private void cell(final java.util.List<short[]> out, final int cx, final int cz, final int dx, final int dz, final int relative) {
		long c = this.cells[Math.floorMod(cx + dx, this.width) + Math.floorMod(cz + dz, this.height) * this.width];
		if (c == 0L) {
			return;
		}
		// Richtungen wie Direction.get3DDataValue: 0 unten, 1 oben, 2 Nord, 3 Süd, 4 West, 5 Ost
		if (relative != 2) {
			out.add(new short[]{(short) dx, (short) dz, 1});
		}
		if (relative != 0) {
			out.add(new short[]{(short) dx, (short) dz, 0});
		}
		if ((c >> 3 & 1L) != 0 && dz > 0) {
			out.add(new short[]{(short) dx, (short) dz, 2});
		}
		if ((c >> 1 & 1L) != 0 && dz < 0) {
			out.add(new short[]{(short) dx, (short) dz, 3});
		}
		if ((c & 1L) != 0 && dx > 0) {
			out.add(new short[]{(short) dx, (short) dz, 4});
		}
		if ((c >> 2 & 1L) != 0 && dx < 0) {
			out.add(new short[]{(short) dx, (short) dz, 5});
		}
		if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
			for (int d = 0; d < 6; d++) {
				out.add(new short[]{(short) dx, (short) dz, (short) (d | 16)});
			}
		}
	}

	@Override
	public void close() {
		glDeleteTextures(this.texture);
		GlResources.deleted("texture");
		glDeleteBuffers(this.buffer);
		GlResources.deleted("buffer");
		glDeleteVertexArrays(this.vao);
		GlResources.deleted("vao");
		this.shader.close();
	}
}
