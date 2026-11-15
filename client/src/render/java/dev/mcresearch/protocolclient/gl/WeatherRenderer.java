package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.view.Mat4;
import dev.mcresearch.protocolclient.view.MeshBuilder;
import dev.mcresearch.protocolclient.view.env.Precipitation;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.Random;
import org.lwjgl.system.MemoryUtil;

/**
 * Regen und Schnee (docs/48 §6), eigene Umsetzung des Verfahrens von 26.3: je Spalte im Radius 10 um die Kamera eine zur Kamera
 * gedrehte Fläche von max(Kamera−10, Gelände) bis max(Kamera+10, Gelände) (Gelände = Heightmap MOTION_BLOCKING), Art aus Biom
 * und Temperatur an der Kamerahöhe; Texturversatz aus Spielzeit und spaltenfestem Zufall
 * ({@code x²·3121 + x·45238971 ^ z²·418711 + z·13761}); Alpha von 1 (Schnee 0,8) zur Mitte bis 0,5 am Rand, mal Regenstärke;
 * Licht der Spalte über die Lightmap (Schnee aufgehellt). Durchscheinend, Tiefe geprüft, nicht geschrieben.
 */
final class WeatherRenderer implements AutoCloseable {
	static final String VERTEX = """
		#version 330 core
		layout(location = 0) in vec3 aPos;
		layout(location = 1) in vec2 aUv;
		layout(location = 2) in vec4 aColor;
		layout(location = 3) in ivec2 aLight;
		uniform mat4 uViewProj;
		uniform sampler2D uLightmap;
		out vec2 vUv;
		out vec4 vColor;
		out float vSpherical;
		out float vCylindrical;
		void main() {
		    gl_Position = uViewProj * vec4(aPos, 1.0);
		    vUv = aUv;
		    vec2 luv = clamp(vec2(aLight) / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
		    vColor = aColor * texture(uLightmap, luv);
		    vSpherical = length(aPos);
		    vCylindrical = max(length(aPos.xz), abs(aPos.y));
		}
		""";
	static final String FRAGMENT = """
		#version 330 core
		in vec2 vUv;
		in vec4 vColor;
		in float vSpherical;
		in float vCylindrical;
		uniform sampler2D uTex;
		uniform vec4 uFogColor;
		uniform float uFogEnvStart;
		uniform float uFogEnvEnd;
		uniform float uFogRdStart;
		uniform float uFogRdEnd;
		out vec4 fragColor;
		float linearFog(float d, float s, float e) {
		    if (d <= s) return 0.0;
		    if (d >= e) return 1.0;
		    return (d - s) / (e - s);
		}
		void main() {
		    vec4 c = texture(uTex, vUv) * vColor;
		    if (c.a < 0.1) discard;
		    float fog = max(linearFog(vSpherical, uFogEnvStart, uFogEnvEnd), linearFog(vCylindrical, uFogRdStart, uFogRdEnd));
		    fragColor = vec4(mix(c.rgb, uFogColor.rgb, fog), c.a);
		}
		""";
	static final int RADIUS = 10;

	/** Spaltenabfrage: Gelände, Niederschlag, Lichtkoordinaten. */
	interface Columns {
		int motionBlockingHeight(int x, int z);

		Precipitation.Kind precipitation(int x, int cameraY, int z);

		int lightCoords(int x, int y, int z);
	}

	private final ShaderProgram shader;
	private final SkyRenderer.SimpleTexture rain;
	private final SkyRenderer.SimpleTexture snow;
	private final int vao;
	private final int vbo;
	private final float[] sizeX = new float[1024];
	private final float[] sizeZ = new float[1024];
	private int rainVertices;
	private int snowVertices;
	private long capacity;

	WeatherRenderer(final Path assets) {
		this.shader = new ShaderProgram(VERTEX, FRAGMENT);
		this.rain = SkyRenderer.SimpleTexture.load(assets, "assets/minecraft/textures/environment/rain.png", true);
		this.snow = SkyRenderer.SimpleTexture.load(assets, "assets/minecraft/textures/environment/snow.png", true);
		for (int z = 0; z < 32; z++) {
			for (int x = 0; x < 32; x++) {
				float dx = x - 16;
				float dz = z - 16;
				float d = (float) Math.sqrt(dx * dx + dz * dz);
				this.sizeX[z * 32 + x] = -dz / d;
				this.sizeZ[z * 32 + x] = dx / d;
			}
		}
		this.vao = glGenVertexArrays();
		this.vbo = glGenBuffers();
		GlResources.created("vao");
		GlResources.created("buffer");
		glBindVertexArray(this.vao);
		glBindBuffer(GL_ARRAY_BUFFER, this.vbo);
		int stride = MeshBuilder.BYTES_PER_VERTEX;
		glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12L);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, stride, 20L);
		glEnableVertexAttribArray(2);
		glVertexAttribIPointer(3, 2, GL_UNSIGNED_SHORT, stride, 24L);
		glEnableVertexAttribArray(3);
		glBindVertexArray(0);
	}

	int columns() {
		return (this.rainVertices + this.snowVertices) / 6;
	}

	/** Ausgeschlossene Spalten des letzten Bildes: Gelände über dem Bereich, kein Niederschlag (Diagnose). */
	int skippedHeight;
	int skippedNone;
	int lastTerrainSample;

	/** Zeichnet Niederschlag (Regenstärke &gt; 0); Positionen relativ zur Kamera. */
	void draw(final Mat4 viewProj, final double camX, final double camY, final double camZ, final float intensity, final long gameTime,
		final float partial, final Columns cols, final float[] fogColor, final float[] fog) {
		this.rainVertices = 0;
		this.snowVertices = 0;
		if (intensity <= 0f) {
			return;
		}
		this.skippedHeight = 0;
		this.skippedNone = 0;
		int cbx = (int) Math.floor(camX);
		int cby = (int) Math.floor(camY);
		int cbz = (int) Math.floor(camZ);
		MeshBuilder rainB = new MeshBuilder(2646, 2646);
		MeshBuilder snowB = new MeshBuilder(64, 2646);
		Random random = new Random();
		float radiusSq = RADIUS * RADIUS;
		for (int z = cbz - RADIUS; z <= cbz + RADIUS; z++) {
			for (int x = cbx - RADIUS; x <= cbx + RADIUS; x++) {
				int terrain = cols.motionBlockingHeight(x, z);
				int y0 = Math.max(cby - RADIUS, terrain);
				int y1 = Math.max(cby + RADIUS, terrain);
				if (x == cbx && z == cbz) {
					this.lastTerrainSample = terrain;
				}
				if (y1 - y0 == 0) {
					this.skippedHeight++;
					continue;
				}
				Precipitation.Kind kind = cols.precipitation(x, cby, z);
				if (kind == Precipitation.Kind.NONE) {
					this.skippedNone++;
					continue;
				}
				int seed = x * x * 3121 + x * 45238971 ^ z * z * 418711 + z * 13761;
				random.setSeed(seed);
				int lc = cols.lightCoords(x, Math.max(cby, terrain), z);
				int wrapped = (int) (gameTime & 131071L);
				float u;
				float v;
				int light;
				float maxAlpha;
				if (kind == Precipitation.Kind.RAIN) {
					int tickOffset = x * x * 3121 + x * 45238971 + z * z * 418711 + z * 13761 & 0xFF;
					float speed = 3.0f + random.nextFloat();
					float tex = -(wrapped + tickOffset + partial) / 32.0f * speed;
					u = 0f;
					v = tex % 32.0f;
					light = lc;
					maxAlpha = 1.0f;
				} else {
					float time = wrapped + partial;
					u = (float) (random.nextDouble() + time * 0.01f * (float) random.nextGaussian());
					float vv = (float) (random.nextDouble() + time * (float) random.nextGaussian() * 0.001f);
					float vOffset = -((float) (gameTime & 511L) + partial) / 512.0f;
					v = vOffset + vv;
					int block = (lc >> 4 & 15) * 3 + 15 >> 2;
					int sky = (lc >> 20 & 15) * 3 + 15 >> 2;
					light = block << 4 | sky << 20;
					maxAlpha = 0.8f;
				}
				float rx = (float) (x + 0.5 - camX);
				float rz = (float) (z + 0.5 - camZ);
				float dsq = rx * rx + rz * rz;
				float alpha = (maxAlpha + Math.min(dsq / radiusSq, 1.0f) * (0.5f - maxAlpha)) * intensity;
				int a = Math.max(0, Math.min(255, (int) Math.floor(alpha * 255f)));
				int color = MeshBuilder.rgba(255, 255, 255, a);
				int idx = (z - cbz + 16) * 32 + x - cbx + 16;
				float hx = this.sizeX[idx] / 2f;
				float hz = this.sizeZ[idx] / 2f;
				float top = (float) (y1 - camY);
				float bottom = (float) (y0 - camY);
				float v0 = y0 * 0.25f + v;
				float v1 = y1 * 0.25f + v;
				float[] p = {rx - hx, top, rz - hz, rx + hx, top, rz + hz, rx + hx, bottom, rz + hz, rx - hx, bottom, rz - hz};
				float[] uv = {u, v0, u + 1, v0, u + 1, v1, u, v1};
				int[] c = {color, color, color, color};
				int lcs = (light >> 4 & 15) * 16 | ((light >> 20 & 15) * 16) << 16;
				int[] l = {lcs, lcs, lcs, lcs};
				(kind == Precipitation.Kind.RAIN ? rainB : snowB).quad(p, uv, c, l);
			}
		}
		int[] rainData = rainB.toArray();
		int[] snowData = snowB.toArray();
		this.rainVertices = rainData.length / MeshBuilder.INTS_PER_VERTEX;
		this.snowVertices = snowData.length / MeshBuilder.INTS_PER_VERTEX;
		if (this.rainVertices + this.snowVertices == 0) {
			return;
		}
		int[] all = new int[rainData.length + snowData.length];
		System.arraycopy(rainData, 0, all, 0, rainData.length);
		System.arraycopy(snowData, 0, all, rainData.length, snowData.length);
		glBindBuffer(GL_ARRAY_BUFFER, this.vbo);
		IntBuffer buf = MemoryUtil.memAllocInt(all.length);
		try {
			buf.put(all).flip();
			if (all.length * 4L > this.capacity) {
				glBufferData(GL_ARRAY_BUFFER, buf, GL_STREAM_DRAW);
				this.capacity = all.length * 4L;
			} else {
				glBufferSubData(GL_ARRAY_BUFFER, 0L, buf);
			}
		} finally {
			MemoryUtil.memFree(buf);
		}
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glEnable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_CULL_FACE);
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		this.shader.use();
		this.shader.set("uViewProj", viewProj);
		this.shader.set("uLightmap", LightmapGl.UNIT);
		this.shader.set("uTex", 0);
		this.shader.set("uFogColor", fogColor[0], fogColor[1], fogColor[2], 1f);
		this.shader.set("uFogEnvStart", fog[0]);
		this.shader.set("uFogEnvEnd", fog[1]);
		this.shader.set("uFogRdStart", fog[2]);
		this.shader.set("uFogRdEnd", fog[3]);
		glBindVertexArray(this.vao);
		if (this.rainVertices > 0) {
			this.rain.bind();
			glDrawArrays(GL_TRIANGLES, 0, this.rainVertices);
		}
		if (this.snowVertices > 0) {
			this.snow.bind();
			glDrawArrays(GL_TRIANGLES, this.rainVertices, this.snowVertices);
		}
		glBindVertexArray(0);
		glDisable(GL_BLEND);
		glDepthMask(true);
		glEnable(GL_CULL_FACE);
	}

	@Override
	public void close() {
		glDeleteBuffers(this.vbo);
		glDeleteVertexArrays(this.vao);
		GlResources.deleted("buffer");
		GlResources.deleted("vao");
		this.rain.close();
		this.snow.close();
		this.shader.close();
	}
}
