package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.view.Mat4;
import dev.mcresearch.protocolclient.view.MeshBuilder;
import dev.mcresearch.protocolclient.view.env.EnvAttr;
import dev.mcresearch.protocolclient.view.env.EnvironmentState;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Random;

/**
 * Himmel (docs/48 §2), eigene Umsetzung des Aufbaus von 26.3: Himmelsscheibe (Fächer, Radius 512, Höhe 16) in
 * {@code sky_color} mit Himmelsnebel bis {@code skyEnd}; Sonnenauf-/-untergangsfächer in {@code sunrise_sunset_color};
 * Sonne (Größe 30) und Mond (20, acht Phasen) in Höhe 100, gedreht um {@code sun_angle}/{@code moon_angle}, additiv mit
 * Regendämpfung; 1500 Sterne (Startwert 10842) gedreht um {@code star_angle}, Helligkeit {@code star_brightness}; dunkle
 * Scheibe unter dem Horizont; End-Himmel als Würfel. Nur Grafikthread; Tiefe wird weder geprüft noch geschrieben.
 */
final class SkyRenderer implements AutoCloseable {
	static final String VERTEX = """
		#version 330 core
		layout(location = 0) in vec3 aPos;
		layout(location = 1) in vec2 aUv;
		layout(location = 2) in vec4 aColor;
		uniform mat4 uProj;
		uniform mat4 uModelView;
		out vec2 vUv;
		out vec4 vColor;
		out float vSpherical;
		out float vCylindrical;
		void main() {
		    vec4 p = uModelView * vec4(aPos, 1.0);
		    gl_Position = uProj * p;
		    vUv = aUv;
		    vColor = aColor;
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
		uniform int uTextured;
		uniform vec4 uColorMod;
		uniform int uFog;
		uniform vec4 uFogColor;
		uniform float uSkyEnd;
		out vec4 fragColor;
		float linearFog(float d, float s, float e) {
		    if (d <= s) return 0.0;
		    if (d >= e) return 1.0;
		    return (d - s) / (e - s);
		}
		void main() {
		    vec4 c = (uTextured == 1 ? texture(uTex, vUv) : vec4(1.0)) * vColor * uColorMod;
		    if (uFog == 1) {
		        float f = max(linearFog(vSpherical, 0.0, uSkyEnd), linearFog(vCylindrical, uSkyEnd, uSkyEnd));
		        c = vec4(mix(c.rgb, uFogColor.rgb, f * uFogColor.a), c.a);
		    }
		    fragColor = c;
		}
		""";

	private final ShaderProgram shader;
	private final GpuMesh topDisc;
	private final GpuMesh bottomDisc;
	private final GpuMesh sunriseFan;
	private final GpuMesh sun;
	private final GpuMesh[] moon = new GpuMesh[8];
	private final GpuMesh stars;
	private final GpuMesh endSky;
	private final SimpleTexture sunTexture;
	private final SimpleTexture[] moonTextures = new SimpleTexture[8];
	private final SimpleTexture endSkyTexture;
	private int starCount;

	SkyRenderer(final Path assets) {
		this.shader = new ShaderProgram(VERTEX, FRAGMENT);
		this.topDisc = new GpuMesh(disc(16f));
		this.bottomDisc = new GpuMesh(disc(-16f));
		this.sunriseFan = new GpuMesh(sunriseFan());
		this.sun = new GpuMesh(celestialQuad(false));
		for (int i = 0; i < 8; i++) {
			this.moon[i] = new GpuMesh(celestialQuad(true));
		}
		this.stars = new GpuMesh(stars());
		this.endSky = new GpuMesh(endSkyBox());
		this.sunTexture = SimpleTexture.load(assets, "assets/minecraft/textures/environment/celestial/sun.png", false);
		for (int i = 0; i < 8; i++) {
			this.moonTextures[i] = SimpleTexture.load(assets, "assets/minecraft/textures/environment/celestial/moon/" + EnvAttr.MOON_PHASES[i] + ".png", false);
		}
		this.endSkyTexture = SimpleTexture.load(assets, "assets/minecraft/textures/environment/end_sky.png", true);
	}

	/** Fächer: Mitte (0, y, 0), Ring bei −180°…180° in 45°-Schritten, Radius 512 (x mit Vorzeichen von y). */
	private static int[] disc(final float y) {
		MeshBuilder b = new MeshBuilder(64, 64);
		float x = Math.signum(y) * 512f;
		int white = MeshBuilder.rgba(255, 255, 255, 255);
		float[] ring = new float[9 * 3];
		for (int i = -180, k = 0; i <= 180; i += 45, k++) {
			ring[k * 3] = x * (float) Math.cos(Math.toRadians(i));
			ring[k * 3 + 1] = y;
			ring[k * 3 + 2] = 512f * (float) Math.sin(Math.toRadians(i));
		}
		for (int k = 0; k < 8; k++) {
			triangle(b, 0, y, 0, ring[k * 3], ring[k * 3 + 1], ring[k * 3 + 2], ring[(k + 1) * 3], ring[(k + 1) * 3 + 1], ring[(k + 1) * 3 + 2], white, white,
				white);
		}
		return b.toArray();
	}

	private static void triangle(final MeshBuilder b, final float x0, final float y0, final float z0, final float x1, final float y1, final float z1,
		final float x2, final float y2, final float z2, final int c0, final int c1, final int c2) {
		// als Quad mit doppeltem letzten Punkt (zweites Dreieck entartet)
		b.quad(new float[]{x0, y0, z0, x1, y1, z1, x2, y2, z2, x2, y2, z2}, new float[8], new int[]{c0, c1, c2, c2});
	}

	/** Sonnenauf-/-untergangsfächer: Mitte (0, 100, 0) weiß deckend, 17 Randpunkte (sin·120, cos·120, −cos·40) transparent. */
	private static int[] sunriseFan() {
		MeshBuilder b = new MeshBuilder(128, 128);
		int center = MeshBuilder.rgba(255, 255, 255, 255);
		int edge = MeshBuilder.rgba(255, 255, 255, 0);
		float[] pts = new float[17 * 3];
		for (int i = 0; i <= 16; i++) {
			float a = i * (float) (Math.PI * 2) / 16f;
			pts[i * 3] = (float) Math.sin(a) * 120f;
			pts[i * 3 + 1] = (float) Math.cos(a) * 120f;
			pts[i * 3 + 2] = -(float) Math.cos(a) * 40f;
		}
		for (int i = 0; i < 16; i++) {
			triangle(b, 0, 100, 0, pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2], pts[(i + 1) * 3], pts[(i + 1) * 3 + 1], pts[(i + 1) * 3 + 2], center, edge,
				edge);
		}
		return b.toArray();
	}

	/** Quad (−1, 0, −1)…(1, 0, 1); Mond mit gespiegelter UV wie in 26.3. */
	private static int[] celestialQuad(final boolean moon) {
		MeshBuilder b = new MeshBuilder(6, 6);
		int w = MeshBuilder.rgba(255, 255, 255, 255);
		float[] p = {-1, 0, -1, 1, 0, -1, 1, 0, 1, -1, 0, 1};
		float[] uv = moon ? new float[]{1, 1, 0, 1, 0, 0, 1, 0} : new float[]{0, 0, 1, 0, 1, 1, 0, 1};
		b.quad(p, uv, new int[]{w, w, w, w});
		return b.toArray();
	}

	/** 1500 Kandidaten wie in 26.3 (Zufall 10842: drei Koordinaten, Größe, Drehung); nur Punkte mit 0,01 &lt; |p|² &lt; 1. */
	private int[] stars() {
		Random r = new Random(10842L);
		MeshBuilder b = new MeshBuilder(1500 * 6, 1500 * 6);
		int w = MeshBuilder.rgba(255, 255, 255, 255);
		for (int i = 0; i < 1500; i++) {
			float x = r.nextFloat() * 2f - 1f;
			float y = r.nextFloat() * 2f - 1f;
			float z = r.nextFloat() * 2f - 1f;
			float size = 0.15f + r.nextFloat() * 0.1f;
			float lenSq = x * x + y * y + z * z;
			if (lenSq <= 0.010000001f || lenSq >= 1.0f) {
				continue;
			}
			float len = (float) Math.sqrt(lenSq);
			float cx = x / len * 100f;
			float cy = y / len * 100f;
			float cz = z / len * 100f;
			float rot = (float) (r.nextDouble() * (float) Math.PI * 2.0);
			// lokale z-Achse zeigt zum Ursprung (−Mittelpunkt), y nach oben; danach Drehung um z um −rot
			float[] zAxis = norm(-cx, -cy, -cz);
			float[] xAxis = norm(cross(new float[]{0, 1, 0}, zAxis));
			float[] yAxis = cross(zAxis, xAxis);
			float c = (float) Math.cos(-rot);
			float s = (float) Math.sin(-rot);
			float[][] corners = {{size, -size}, {size, size}, {-size, size}, {-size, -size}};
			float[] p = new float[12];
			for (int k = 0; k < 4; k++) {
				float lx = corners[k][0] * c - corners[k][1] * s;
				float ly = corners[k][0] * s + corners[k][1] * c;
				for (int a = 0; a < 3; a++) {
					p[k * 3 + a] = xAxis[a] * lx + yAxis[a] * ly + (a == 0 ? cx : a == 1 ? cy : cz);
				}
			}
			b.quad(p, new float[8], new int[]{w, w, w, w});
			this.starCount++;
		}
		return b.toArray();
	}

	private static float[] cross(final float[] a, final float[] b) {
		return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static float[] norm(final float... v) {
		float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		return new float[]{v[0] / l, v[1] / l, v[2] / l};
	}

	/** End-Himmel: sechs Seiten eines Würfels (Kantenlänge 200), Farbe 0x282828, UV 0…16 (Textur wiederholt). */
	private static int[] endSkyBox() {
		MeshBuilder b = new MeshBuilder(36, 36);
		int c = MeshBuilder.rgba(0x28, 0x28, 0x28, 255);
		for (int i = 0; i < 6; i++) {
			Mat4 m = switch (i) {
				case 1 -> Mat4.rotationX((float) (Math.PI / 2));
				case 2 -> Mat4.rotationX((float) (-Math.PI / 2));
				case 3 -> Mat4.rotationX((float) Math.PI);
				case 4 -> Mat4.rotationZ((float) (Math.PI / 2));
				case 5 -> Mat4.rotationZ((float) (-Math.PI / 2));
				default -> Mat4.identity();
			};
			float[][] v = {{-100, -100, -100}, {-100, -100, 100}, {100, -100, 100}, {100, -100, -100}};
			float[] p = new float[12];
			for (int k = 0; k < 4; k++) {
				float[] t = m.transform(v[k][0], v[k][1], v[k][2]);
				System.arraycopy(t, 0, p, k * 3, 3);
			}
			b.quad(p, new float[]{0, 0, 0, 16, 16, 16, 16, 0}, new int[]{c, c, c, c});
		}
		return b.toArray();
	}

	int starCount() {
		return this.starCount;
	}

	/**
	 * Zeichnet den Himmel (vor dem Terrain, ohne Tiefe). {@code viewRotation} = Kameradrehung ohne Verschiebung.
	 *
	 * @param skybox      "overworld", "none" (Nether) oder "end"
	 * @param belowHorizon Augenhöhe unter der Horizonthöhe und nicht unter Wasser → dunkle Scheibe
	 */
	void draw(final Mat4 proj, final Mat4 viewRotation, final EnvironmentState env, final float partial, final String skybox, final float rain,
		final float[] fogColor, final float skyEnd, final boolean belowHorizon) {
		if (skybox.equals("none")) {
			return;
		}
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_CULL_FACE);
		this.shader.use();
		this.shader.set("uProj", proj);
		this.shader.set("uTex", 0);
		this.shader.set("uFogColor", fogColor[0], fogColor[1], fogColor[2], 1f);
		this.shader.set("uSkyEnd", skyEnd);
		if (skybox.equals("end")) {
			glEnable(GL_BLEND);
			glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
			this.shader.set("uModelView", viewRotation);
			this.shader.set("uTextured", 1);
			this.shader.set("uFog", 0);
			this.shader.set("uColorMod", 1f, 1f, 1f, 1f);
			this.endSkyTexture.bind();
			this.endSky.draw();
			finish();
			return;
		}
		float[] sky = env.get(EnvAttr.SKY_COLOR, partial);
		// 1) Himmelsscheibe
		glDisable(GL_BLEND);
		this.shader.set("uModelView", viewRotation);
		this.shader.set("uTextured", 0);
		this.shader.set("uFog", 1);
		this.shader.set("uColorMod", sky[0], sky[1], sky[2], 1f);
		this.topDisc.draw();
		this.shader.set("uFog", 0);
		// 2) Sonnenauf-/-untergang
		float sunAngle = (float) Math.toRadians(env.get(EnvAttr.SUN_ANGLE, partial)[0]);
		float[] rise = env.get(EnvAttr.SUNRISE_SUNSET_COLOR, partial);
		glEnable(GL_BLEND);
		if (rise[3] > 0.001f) {
			glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
			float angle = Math.sin(sunAngle) < 0f ? 180f : 0f;
			Mat4 m = viewRotation.multiply(Mat4.rotationX((float) Math.toRadians(90))).multiply(Mat4.rotationZ((float) Math.toRadians(angle + 90f)))
				.multiply(Mat4.scale(1f, 1f, rise[3]));
			this.shader.set("uModelView", m);
			this.shader.set("uColorMod", rise[0], rise[1], rise[2], rise[3]);
			this.sunriseFan.draw();
		}
		// 3) Sonne, Mond, Sterne: additiv (SRC_ALPHA, ONE)
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_ONE, GL_ZERO);
		float rainBrightness = 1f - rain;
		Mat4 base = viewRotation.multiply(Mat4.rotationY((float) Math.toRadians(-90)));
		this.shader.set("uTextured", 1);
		this.shader.set("uColorMod", 1f, 1f, 1f, rainBrightness);
		this.shader.set("uModelView", base.multiply(Mat4.rotationX(sunAngle)).multiply(Mat4.translation(0, 100, 0)).multiply(Mat4.scale(30, 1, 30)));
		this.sunTexture.bind();
		this.sun.draw();
		float moonAngle = (float) Math.toRadians(env.get(EnvAttr.MOON_ANGLE, partial)[0]);
		int phase = Math.max(0, Math.min(7, (int) env.get(EnvAttr.MOON_PHASE, partial)[0]));
		this.shader.set("uModelView", base.multiply(Mat4.rotationX(moonAngle)).multiply(Mat4.translation(0, 100, 0)).multiply(Mat4.scale(20, 1, 20)));
		this.moonTextures[phase].bind();
		this.moon[phase].draw();
		float starBrightness = env.get(EnvAttr.STAR_BRIGHTNESS, partial)[0];
		if (starBrightness > 0f) {
			float starAngle = (float) Math.toRadians(env.get(EnvAttr.STAR_ANGLE, partial)[0]);
			this.shader.set("uTextured", 0);
			this.shader.set("uColorMod", starBrightness, starBrightness, starBrightness, starBrightness);
			this.shader.set("uModelView", base.multiply(Mat4.rotationX(starAngle)));
			this.stars.draw();
		}
		// 4) dunkle Scheibe unter dem Horizont
		if (belowHorizon) {
			glDisable(GL_BLEND);
			this.shader.set("uTextured", 0);
			this.shader.set("uColorMod", 0f, 0f, 0f, 1f);
			this.shader.set("uModelView", viewRotation.multiply(Mat4.translation(0, 12, 0)));
			this.bottomDisc.draw();
		}
		finish();
	}

	private static void finish() {
		glDisable(GL_BLEND);
		glEnable(GL_DEPTH_TEST);
		glDepthMask(true);
		glEnable(GL_CULL_FACE);
	}

	@Override
	public void close() {
		this.topDisc.close();
		this.bottomDisc.close();
		this.sunriseFan.close();
		this.sun.close();
		for (GpuMesh m : this.moon) {
			m.close();
		}
		this.stars.close();
		this.endSky.close();
		this.sunTexture.close();
		for (SimpleTexture t : this.moonTextures) {
			t.close();
		}
		this.endSkyTexture.close();
		this.shader.close();
	}

	/** Kleine eigenständige Textur aus dem lokalen Spiel-JAR (Himmel, Wetter, Wolken); fehlend → 1×1 weiß. */
	static final class SimpleTexture implements AutoCloseable {
		final int id;
		final int width;
		final int height;
		final int[] argb;

		private SimpleTexture(final BufferedImage img, final boolean repeat) {
			this.width = img.getWidth();
			this.height = img.getHeight();
			this.argb = img.getRGB(0, 0, this.width, this.height, null, 0, this.width);
			java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(this.width * this.height * 4);
			try {
				for (int c : this.argb) {
					buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
				}
				buf.flip();
				this.id = glGenTextures();
				GlResources.created("texture");
				glBindTexture(GL_TEXTURE_2D, this.id);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
				glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, this.width, this.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
			} finally {
				org.lwjgl.system.MemoryUtil.memFree(buf);
			}
		}

		static SimpleTexture load(final Path jar, final String entry, final boolean repeat) {
			BufferedImage img = null;
			if (jar != null) {
				try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
					java.util.zip.ZipEntry e = z.getEntry(entry);
					if (e != null) {
						try (java.io.InputStream in = z.getInputStream(e)) {
							img = dev.mcresearch.protocolclient.view.PngImages.read(in);
						}
					}
				} catch (java.io.IOException ex) {
					System.err.println("[GL] texture " + entry + ": " + ex);
				}
			}
			if (img == null) {
				img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
				img.setRGB(0, 0, 0xFFFFFFFF);
			}
			return new SimpleTexture(img, repeat);
		}

		void bind() {
			glActiveTexture(GL_TEXTURE0);
			glBindTexture(GL_TEXTURE_2D, this.id);
		}

		@Override
		public void close() {
			glDeleteTextures(this.id);
			GlResources.deleted("texture");
		}
	}
}
