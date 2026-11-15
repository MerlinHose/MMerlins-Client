package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.view.Mat4;
import dev.mcresearch.protocolclient.view.MeshBuilder;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Einstieg des grafischen Clients (docs/24). Schritt 1 (Phase B): {@code --test-cube} zeigt ohne Server einen einzelnen
 * farbigen Würfel, um Fenster, Shader, Kamera, Tiefentest und Backface-Culling zu prüfen.
 */
public final class GraphicalMain {
	private GraphicalMain() {
	}

	public static void main(final String[] args) throws Exception {
		GraphicalOptions o = GraphicalOptions.parse(args);
		if (o.testCube()) {
			testCube(o);
			return;
		}
		GameView.run(o);
	}

	private static void testCube(final GraphicalOptions o) throws Exception {
		try (Window w = new Window("Minecraft Research – Test-Würfel", o.width(), o.height(), o.vsync());
			ShaderProgram shader = new ShaderProgram(Shaders.WORLD_VERTEX, Shaders.WORLD_FRAGMENT)) {
			System.out.println("[GL] " + w.glInfo());
			MeshBuilder b = new MeshBuilder();
			// Seitenfarben: unten grau, oben grün, Nord rot, Süd blau, West gelb, Ost cyan
			MeshBuilder.cube(b, 0, 0, 0, 1, 1, 1, new int[]{MeshBuilder.rgba(128, 128, 128, 255), MeshBuilder.rgba(40, 200, 40, 255),
				MeshBuilder.rgba(220, 40, 40, 255), MeshBuilder.rgba(40, 80, 230, 255), MeshBuilder.rgba(230, 210, 40, 255),
				MeshBuilder.rgba(40, 210, 210, 255)});
			try (GpuMesh cube = new GpuMesh(b.toArray())) {
				glEnable(GL_DEPTH_TEST);
				glEnable(GL_CULL_FACE);
				glCullFace(GL_BACK);
				glFrontFace(GL_CCW);
				long start = System.nanoTime();
				long frames = 0;
				long fpsT = start;
				int fpsFrames = 0;
				double fps = 0;
				while (!w.shouldClose()) {
					w.consumeResize();
					double t = (System.nanoTime() - start) / 1e9;
					// Kamera umkreist den Würfel; Blick zum Mittelpunkt (Yaw 0 = +Z, Pitch positiv = nach unten)
					double ex = 0.5 + Math.sin(t * 0.6) * 3.0;
					double ez = 0.5 - Math.cos(t * 0.6) * 3.0;
					double ey = 2.2;
					double dx = 0.5 - ex;
					double dy = 0.5 - ey;
					double dz = 0.5 - ez;
					float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
					float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.hypot(dx, dz)));
					Mat4 vp = Mat4.perspective(o.fov(), (float) w.width() / w.height(), 0.05f, 100f).multiply(Mat4.minecraftView(ex, ey, ez, yaw, pitch));
					glClearColor(0.53f, 0.70f, 0.98f, 1f);
					glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
					shader.use();
					shader.set("uViewProj", vp);
					shader.set("uOffset", (float) (0 - ex), (float) (0 - ey), (float) (0 - ez));
					shader.set("uTextured", 0);
					shader.set("uAlphaCutoff", 0.1f);
					shader.set("uFogColor", 0.53f, 0.70f, 0.98f);
					shader.set("uFogStart", 1000f);
					shader.set("uFogEnd", 2000f);
					cube.draw();
					frames++;
					fpsFrames++;
					long now = System.nanoTime();
					if (now - fpsT >= 500_000_000L) {
						fps = fpsFrames / ((now - fpsT) / 1e9);
						fpsT = now;
						fpsFrames = 0;
						w.setTitle(String.format(Locale.ROOT, "Minecraft Research – Test-Würfel – %.0f FPS", fps));
					}
					if (o.screenshot() != null && frames == o.screenshotFrame()) {
						w.screenshot(o.screenshot());
						System.out.println("[GL] screenshot " + o.screenshot() + " at frame " + frames);
					}
					if (o.exitAfterSeconds() > 0 && t >= o.exitAfterSeconds()) {
						w.requestClose();
					}
					w.endFrame();
				}
				System.out.printf(Locale.ROOT, "[GL] test cube: %d frames in %.1f s, last FPS %.0f, %d vertices%n", frames,
					(System.nanoTime() - start) / 1e9, fps, cube.vertexCount());
			}
		}
	}

	static Path path(final String s) {
		return s == null || s.isBlank() ? null : Path.of(s);
	}
}
