package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.view.BlockModelProvider;
import dev.mcresearch.protocolclient.view.Face;
import dev.mcresearch.protocolclient.view.Mat4;
import dev.mcresearch.protocolclient.view.MeshBuilder;
import dev.mcresearch.protocolclient.view.RenderModel;
import dev.mcresearch.protocolclient.view.RenderQuad;
import java.nio.file.Path;
import java.util.List;

/**
 * Abbaufortschritt (docs/48 §8): die Modellflächen des Blocks erneut mit {@code destroy_stage_0…9} – Stufe = ⌊Fortschritt·10⌋ − 1
 * ({@code MultiPlayerGameMode#getDestroyStage}) bzw. vom Server ({@code block_destruction}). Textur auf die Flächenebene
 * projiziert (Blockkoordinaten), Blending DST_COLOR·SRC_COLOR + SRC_COLOR·DST_COLOR (Rendertyp crumbling), Polygonversatz,
 * Tiefe geprüft, nicht geschrieben. Nur Grafikthread.
 */
final class DestroyOverlay implements AutoCloseable {
	private static final String VERTEX = """
		#version 330 core
		layout(location = 0) in vec3 aPos;
		layout(location = 1) in vec2 aUv;
		layout(location = 2) in vec4 aColor;
		uniform mat4 uViewProj;
		out vec2 vUv;
		void main() {
		    gl_Position = uViewProj * vec4(aPos, 1.0);
		    vUv = aUv;
		}
		""";
	private static final String FRAGMENT = """
		#version 330 core
		in vec2 vUv;
		uniform sampler2D uTex;
		out vec4 fragColor;
		void main() {
		    vec4 c = texture(uTex, vUv);
		    if (c.a < 0.1) discard;
		    fragColor = c;
		}
		""";

	/** Eine Abbaustelle (Weltkoordinaten, Zustand, Stufe 0–9). */
	record Site(int x, int y, int z, int state, int stage) {
	}

	private final ShaderProgram shader;
	private final SkyRenderer.SimpleTexture[] stages = new SkyRenderer.SimpleTexture[10];
	private int drawnSites;

	DestroyOverlay(final Path assets) {
		this.shader = new ShaderProgram(VERTEX, FRAGMENT);
		for (int i = 0; i < 10; i++) {
			this.stages[i] = SkyRenderer.SimpleTexture.load(assets, "assets/minecraft/textures/block/destroy_stage_" + i + ".png", true);
		}
	}

	int drawnSites() {
		return this.drawnSites;
	}

	void draw(final Mat4 viewProj, final List<Site> sites, final BlockModelProvider models, final double camX, final double camY, final double camZ) {
		this.drawnSites = 0;
		if (sites.isEmpty()) {
			return;
		}
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_LEQUAL);
		glDepthMask(false);
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_DST_COLOR, GL_SRC_COLOR, GL_ONE, GL_ZERO);
		glEnable(GL_POLYGON_OFFSET_FILL);
		glPolygonOffset(-1.0f, -10.0f);
		this.shader.use();
		this.shader.set("uViewProj", viewProj);
		this.shader.set("uTex", 0);
		for (Site s : sites) {
			if (s.stage() < 0 || s.stage() > 9 || s.state() <= 0) {
				continue;
			}
			RenderModel m = models.model(s.state());
			if (m.quads().isEmpty()) {
				continue;
			}
			MeshBuilder b = new MeshBuilder(m.quads().size() * 6, 1 << 16);
			float ox = (float) (s.x() - camX);
			float oy = (float) (s.y() - camY);
			float oz = (float) (s.z() - camZ);
			int w = MeshBuilder.rgba(255, 255, 255, 255);
			for (RenderQuad q : m.quads()) {
				float[] p = q.positions();
				float[] pos = new float[12];
				float[] uv = new float[8];
				Face f = q.direction();
				for (int k = 0; k < 4; k++) {
					float x = p[k * 3];
					float y = p[k * 3 + 1];
					float z = p[k * 3 + 2];
					pos[k * 3] = x + ox;
					pos[k * 3 + 1] = y + oy;
					pos[k * 3 + 2] = z + oz;
					float[] t = switch (f) {
						case UP -> new float[]{x, z};
						case DOWN -> new float[]{x, 1 - z};
						case NORTH -> new float[]{1 - x, 1 - y};
						case SOUTH -> new float[]{x, 1 - y};
						case WEST -> new float[]{z, 1 - y};
						case EAST -> new float[]{1 - z, 1 - y};
					};
					uv[k * 2] = t[0];
					uv[k * 2 + 1] = t[1];
				}
				b.quad(pos, uv, new int[]{w, w, w, w});
			}
			try (GpuMesh mesh = new GpuMesh(b.toArray())) {
				this.stages[s.stage()].bind();
				mesh.draw();
			}
			this.drawnSites++;
		}
		glDisable(GL_POLYGON_OFFSET_FILL);
		glDisable(GL_BLEND);
		glDepthMask(true);
	}

	@Override
	public void close() {
		for (SkyRenderer.SimpleTexture t : this.stages) {
			t.close();
		}
		this.shader.close();
	}
}
