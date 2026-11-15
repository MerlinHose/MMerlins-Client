package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.entity.EntityManager;
import dev.mcresearch.protocolclient.view.EntityModels;
import dev.mcresearch.protocolclient.view.Frustum;
import dev.mcresearch.protocolclient.view.Mat4;
import dev.mcresearch.protocolclient.view.MeshBuilder;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import dev.mcresearch.protocolclient.world.WorldState;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.lwjgl.system.MemoryUtil;

/**
 * Entity-Rendering (M9, docs/36). Zeichnet die Momentaufnahme des {@link EntityManager} mit eigener Modellgeometrie
 * ({@link EntityModels}) und Texturen, die zur Laufzeit aus dem <b>lokalen</b> Minecraft-JAR gelesen werden (nie in
 * Build-Artefakte kopiert). Je Modellteil gibt es ein einmal hochgeladenes Mesh; pro Bild werden nur Matrizen und Farben gesetzt –
 * keine GPU-Objekte je Bild. Unbekannte Typen und fehlende Texturen erscheinen als magentafarbene Debug-Box in Hitbox-Größe.
 *
 * <p>Nur Grafikthread. {@link #close()} gibt alle Meshes, Texturen und den Shader frei (GlResources-Zähler).
 */
final class EntityRenderer implements AutoCloseable {
	/**
	 * M12 (docs/47 §5): Entity-Beleuchtung wie 26.3 – Lightmap an den Lichtkoordinaten der Entity (Augenhöhe, brennend:
	 * Blocklicht 15) und zwei feste Diffuslichter (Weltraum, Leistung 0,6, Umgebung 0,4). Flächennormale aus den
	 * Bildschirmableitungen der Weltposition (flach je Fläche, zur Kamera gewandt – entspricht der Außennormale sichtbarer
	 * Flächen). Nebel wie das Terrain (kugelförmig + zylindrisch).
	 */
	static final String VERTEX = """
		#version 330 core
		layout(location = 0) in vec3 aPos;
		layout(location = 1) in vec2 aUv;
		layout(location = 2) in vec4 aColor;
		uniform mat4 uViewProj;
		uniform mat4 uModel;
		out vec2 vUv;
		out vec4 vColor;
		out vec3 vPos;
		void main() {
		    vec4 p = uModel * vec4(aPos, 1.0);
		    gl_Position = uViewProj * p;
		    vUv = aUv;
		    vColor = aColor;
		    vPos = p.xyz;
		}
		""";

	static final String FRAGMENT = """
		#version 330 core
		in vec2 vUv;
		in vec4 vColor;
		in vec3 vPos;
		uniform sampler2D uTex;
		uniform sampler2D uLightmap;
		uniform int uTextured;
		uniform int uDiffuse;
		uniform vec4 uTint;
		uniform ivec2 uLight;
		uniform vec3 uLight0;
		uniform vec3 uLight1;
		uniform vec3 uFogColor;
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
		    vec4 c = (uTextured == 1 ? texture(uTex, vUv) : vec4(1.0)) * vColor * uTint;
		    if (c.a < 0.1) discard;
		    if (uDiffuse == 1) {
		        vec3 n = normalize(cross(dFdx(vPos), dFdy(vPos)));
		        if (dot(n, vPos) > 0.0) n = -n;
		        float l = min(1.0, (max(0.0, dot(uLight0, n)) + max(0.0, dot(uLight1, n))) * 0.6 + 0.4);
		        c.rgb *= l;
		    }
		    vec2 luv = clamp(vec2(uLight) / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
		    c *= texture(uLightmap, luv);
		    float fog = max(linearFog(length(vPos), uFogEnvStart, uFogEnvEnd), linearFog(max(length(vPos.xz), abs(vPos.y)), uFogRdStart, uFogRdEnd));
		    fragColor = vec4(mix(c.rgb, uFogColor, fog), 1.0);
		}
		""";

	/** Diffuslichter der Welt ({@code Lighting#DIFFUSE_LIGHT_0/1}, Nether: zweites Licht nach unten). */
	static final float[] LIGHT0 = normalize(0.2f, 1.0f, -0.7f);
	static final float[] LIGHT1 = normalize(-0.2f, 1.0f, 0.7f);
	static final float[] NETHER_LIGHT1 = normalize(-0.2f, -1.0f, 0.7f);
	/** M12: Nether-Beleuchtung der aktuellen Dimension (GameView setzt sie bei Weltwechsel). */
	static volatile boolean netherLighting;
	/** M12: Nebel wie das Terrain (Umgebung Start/Ende, Sichtweite Start/Ende). */
	static volatile float[] fogParams = {0f, 1024f, 1e9f, 1e9f};

	private static float[] normalize(final float x, final float y, final float z) {
		float l = (float) Math.sqrt(x * x + y * y + z * z);
		return new float[]{x / l, y / l, z / l};
	}

	/** Standard-Skins (lokal); Auswahl nach UUID-Hash. Keine Skin-Downloads (docs/36 §5). */
	private static final String[] SKINS = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};
	/** Wollfarben je Farbindex 0–15 (Textur-Grundfarben der Farbstoffe). */
	private static final int[] WOOL = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52, 0x9D9D97, 0x169C9C, 0x8932B8,
		0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};
	private static final float RAD = (float) (Math.PI / 180.0);


	/** Zähler je Bild (Leistungstest, docs/37 §4). */
	record FrameInfo(int total, int drawn, int culled, int debugBoxes, int drawCalls, long cpuNanos) {
	}

	private final ShaderProgram shader = new ShaderProgram(VERTEX, FRAGMENT);
	private final Path assets;
	private ZipFile zip;
	private final Map<EntityModels.Model, Map<EntityModels.Part, GpuMesh>> meshes = new HashMap<>();
	private final Map<String, Integer> textures = new HashMap<>();
	private final Set<String> missingTextures = new HashSet<>();
	private final Map<Integer, float[]> walk = new HashMap<>(); // id → {Phase, Stärke}
	/** M10: einfache Trefferpartikel (Position, Geschwindigkeit, Restlebensdauer in Ticks), höchstens MAX_PARTICLES. */
	private static final int MAX_PARTICLES = 400;
	private final java.util.ArrayList<float[]> particles = new java.util.ArrayList<>();
	private final Map<Integer, Integer> seenDamage = new HashMap<>();
	private final java.util.Random random = new java.util.Random(1234);
	private long particlesSpawned;
	private final GpuMesh debugCube;
	private final GpuMesh itemQuad;
	/** Würfel 0…16 mit der ganzen Textur auf jeder Seite (Block-Items am Boden). */
	private final GpuMesh texCube;
	private final ItemRegistry items = ItemRegistry.get();
	private FrameInfo last = new FrameInfo(0, 0, 0, 0, 0, 0);
	private long lastFrameNanos = -1;
	private int drawCalls;

	EntityRenderer(final Path assets) {
		this.assets = assets;
		try {
			this.zip = assets == null ? null : new ZipFile(assets.toFile());
		} catch (IOException e) {
			this.zip = null;
			System.out.println("[GL] entity textures unavailable: " + e.getMessage());
		}
		this.debugCube = new GpuMesh(EntityModels.partMesh(EntityModels.UNIT_CUBE, EntityModels.UNIT_CUBE.parts().getFirst()));
		MeshBuilder b = new MeshBuilder(6, 64);
		int white = MeshBuilder.rgba(255, 255, 255, 255);
		b.quad(new float[]{-8, -8, 0, 8, -8, 0, 8, 8, 0, -8, 8, 0}, new float[]{0, 1, 1, 1, 1, 0, 0, 0}, new int[]{white, white, white, white});
		this.itemQuad = new GpuMesh(b.toArray());
		this.texCube = new GpuMesh(texturedCube());
	}

	FrameInfo lastFrame() {
		return this.last;
	}

	int textureCount() {
		return this.textures.size();
	}

	/** Zeichnet alle Entities; Kamera relativ (wie die Chunks), Nebel wie die Welt. */
	void draw(final Mat4 viewProj, final Frustum frustum, final double camX, final double camY, final double camZ, final List<EntityManager.View> views,
		final WorldState world, final float[] fogColor, final float fogStart, final float fogEnd, final long now) {
		long t0 = System.nanoTime();
		float dtTicks = this.lastFrameNanos < 0 ? 0 : Math.min(5, (now - this.lastFrameNanos) / 50_000_000f);
		this.lastFrameNanos = now;
		this.drawCalls = 0;
		this.shader.use();
		this.shader.set("uViewProj", viewProj);
		this.shader.set("uTex", 0);
		this.shader.set("uFogColor", fogColor[0], fogColor[1], fogColor[2]);
		float[] fp = fogParams;
		this.shader.set("uFogEnvStart", fp[0]);
		this.shader.set("uFogEnvEnd", fp[1]);
		this.shader.set("uFogRdStart", fp[2]);
		this.shader.set("uFogRdEnd", fp[3]);
		this.shader.set("uLightmap", LightmapGl.UNIT);
		this.shader.set("uDiffuse", 1);
		this.shader.set("uLight0", LIGHT0[0], LIGHT0[1], LIGHT0[2]);
		float[] l1 = netherLighting ? NETHER_LIGHT1 : LIGHT1;
		this.shader.set("uLight1", l1[0], l1[1], l1[2]);
		glActiveTexture(GL_TEXTURE0);
		glDisable(GL_CULL_FACE); // Modelle beidseitig (gespiegelte Teile, Item-Quads)
		int drawn = 0;
		int culled = 0;
		int debug = 0;
		Set<Integer> seen = new HashSet<>();
		for (EntityManager.View v : views) {
			seen.add(v.id());
			double ox = v.rx() - camX;
			double oy = v.ry() - camY;
			double oz = v.rz() - camZ;
			float hw = Math.max(0.3f, v.width() / 2 + 0.5f);
			if (!frustum.intersects((float) ox - hw, (float) oy - 0.5f, (float) oz - hw, (float) ox + hw, (float) (oy + Math.max(0.5f, v.height()) + 0.6f),
				(float) oz + hw)) {
				culled++;
				continue;
			}
			if ((v.flags() & 0x20) != 0) {
				continue; // unsichtbar
			}
			drawn++;
			// Lichtkoordinaten an der Augenposition (getLightProbePosition), brennend: Blocklicht 15; Lightmap im Shader
			int[] lc = lightCoords(world, v.rx(), v.ry() + v.height() * 0.85, v.rz(), (v.flags() & 0x01) != 0);
			this.shader.setInt2("uLight", lc[0] * 16, lc[1] * 16);
			float bright = 1f;
			Mat4 base = Mat4.translation((float) ox, (float) oy, (float) oz);
			float[] wk = this.walk.computeIfAbsent(v.id(), k -> new float[2]);
			float target = (float) Math.min(1.0, v.walkSpeed() * 4.0);
			wk[1] += (target - wk[1]) * Math.min(1f, dtTicks * 0.4f);
			wk[0] += (float) (v.walkSpeed() * 4.0) * dtTicks;
			if (!drawEntity(v, base, bright, wk[0], wk[1])) {
				debug++;
				drawDebug(base, v, bright);
			}
		}
		this.walk.keySet().retainAll(seen);
		// M10: neue Schadensereignisse (damage_event vom Server) → Partikel an der Körpermitte
		for (EntityManager.View v : views) {
			Integer old = this.seenDamage.put(v.id(), v.damageEvents());
			if (old != null && v.damageEvents() > old) {
				spawnHit(v, v.confirmedHits() > 0);
			}
		}
		this.seenDamage.keySet().retainAll(seen);
		drawParticles(camX, camY, camZ, dtTicks);
		glEnable(GL_CULL_FACE);
		this.last = new FrameInfo(views.size(), drawn, culled, debug, this.drawCalls, System.nanoTime() - t0);
	}

	private static int[] texturedCube() {
		MeshBuilder b = new MeshBuilder(36, 64);
		int w = MeshBuilder.rgba(255, 255, 255, 255);
		int[] c = {w, w, w, w};
		float[] uv = {0, 1, 1, 1, 1, 0, 0, 0};
		float n = 16;
		b.quad(new float[]{0, 0, 0, n, 0, 0, n, n, 0, 0, n, 0}, uv, c);
		b.quad(new float[]{n, 0, n, 0, 0, n, 0, n, n, n, n, n}, uv, c);
		b.quad(new float[]{0, 0, n, 0, 0, 0, 0, n, 0, 0, n, n}, uv, c);
		b.quad(new float[]{n, 0, 0, n, 0, n, n, n, n, n, n, 0}, uv, c);
		b.quad(new float[]{0, n, 0, n, n, 0, n, n, n, 0, n, n}, uv, c);
		b.quad(new float[]{0, 0, n, n, 0, n, n, 0, 0, 0, 0, 0}, uv, c);
		return b.toArray();
	}

	private void spawnHit(final EntityManager.View v, final boolean byUs) {
		for (int i = 0; i < 10 && this.particles.size() < MAX_PARTICLES; i++) {
			float vx = (this.random.nextFloat() - 0.5f) * 0.25f;
			float vy = this.random.nextFloat() * 0.2f + 0.05f;
			float vz = (this.random.nextFloat() - 0.5f) * 0.25f;
			this.particles.add(new float[]{(float) v.x(), (float) (v.y() + v.height() * 0.6), (float) v.z(), vx, vy, vz, 12f, byUs ? 1f : 0f});
			this.particlesSpawned++;
		}
	}

	/** Partikel als kleine Würfel (wiederverwendetes Debug-Mesh), Schwerkraft und Lebensdauer je Bild. */
	private void drawParticles(final double camX, final double camY, final double camZ, final float dt) {
		if (this.particles.isEmpty()) {
			return;
		}
		this.shader.set("uTextured", 0);
		this.shader.set("uDiffuse", 0);
		this.shader.setInt2("uLight", 240, 240); // M10-Trefferpartikel: unverändert hell (Partikelsystem M12: ParticleRenderer)
		java.util.Iterator<float[]> it = this.particles.iterator();
		while (it.hasNext()) {
			float[] p = it.next();
			p[0] += p[3] * dt;
			p[1] += p[4] * dt;
			p[2] += p[5] * dt;
			p[4] -= 0.04f * dt;
			p[6] -= dt;
			if (p[6] <= 0) {
				it.remove();
				continue;
			}
			float a = Math.min(1f, p[6] / 6f);
			if (p[7] > 0) {
				this.shader.set("uTint", 1f, 0.85f * a + 0.15f, 0.3f * a, 1f); // eigener Treffer: gelblich
			} else {
				this.shader.set("uTint", 0.9f, 0.1f, 0.1f, 1f);
			}
			this.shader.set("uModel", Mat4.translation((float) (p[0] - camX) - 0.04f, (float) (p[1] - camY), (float) (p[2] - camZ) - 0.04f)
				.multiply(Mat4.scale(0.005f, 0.005f, 0.005f)));
			this.debugCube.draw();
			this.drawCalls++;
		}
	}

	long particlesSpawned() {
		return this.particlesSpawned;
	}

	int particleCount() {
		return this.particles.size();
	}

	/** Block- und Himmelslicht (Stufen) wie {@code EntityRenderer#getPackedLightCoords}. */
	static int[] lightCoords(final WorldState w, final double x, final double y, final double z, final boolean onFire) {
		if (w == null) {
			return new int[]{15, 15};
		}
		int bx = (int) Math.floor(x);
		int by = (int) Math.floor(y);
		int bz = (int) Math.floor(z);
		return new int[]{onFire ? 15 : w.renderLight(false, bx, by, bz), w.renderLight(true, bx, by, bz)};
	}

	/** Liefert false, wenn für den Typ kein Modell oder keine Textur vorhanden ist (dann Debug-Box). */
	private boolean drawEntity(final EntityManager.View v, final Mat4 base, final float bright, final float phase, final float amount) {
		String type = v.type().shortName();
		float hurt = v.hurtProgress() > 0 ? 1f : 0f;
		float[] tint = {bright, bright * (1 - 0.45f * hurt), bright * (1 - 0.45f * hurt), 1f};
		switch (type) {
			case "item" -> {
				return drawItem(v, base, bright);
			}
			case "pig", "cow" -> {
				String tex = "entity/" + type + "/" + type + "_temperate.png";
				return drawLiving(v, base, type.equals("pig") ? EntityModels.PIG : EntityModels.COW, tex, tint, phase, amount, false);
			}
			case "sheep" -> {
				if (!drawLiving(v, base, EntityModels.SHEEP, "entity/sheep/sheep.png", tint, phase, amount, false)) {
					return false;
				}
				if ((v.sheepWool() & 0x10) == 0) {
					int c = WOOL[v.sheepWool() & 15];
					float[] wt = {tint[0] * ((c >> 16) & 255) / 255f, tint[1] * ((c >> 8) & 255) / 255f, tint[2] * (c & 255) / 255f, 1f};
					drawLiving(v, base, EntityModels.SHEEP_WOOL, "entity/sheep/sheep_wool.png", wt, phase, amount, false);
				}
				return true;
			}
			case "zombie" -> {
				return drawLiving(v, base, EntityModels.ZOMBIE, "entity/zombie/zombie.png", tint, phase, amount, true);
			}
			case "skeleton" -> {
				return drawLiving(v, base, EntityModels.SKELETON, "entity/skeleton/skeleton.png", tint, phase, amount, true);
			}
			case "player" -> {
				int idx = Math.floorMod(v.uuid().hashCode(), SKINS.length * 2);
				boolean slim = idx >= SKINS.length;
				String tex = "entity/player/" + (slim ? "slim/" : "wide/") + SKINS[idx % SKINS.length] + ".png";
				return drawLiving(v, base, slim ? EntityModels.PLAYER_SLIM : EntityModels.PLAYER_WIDE, tex, tint, phase, amount, true);
			}
			default -> {
				return false;
			}
		}
	}

	/** Lebewesen: Körperdrehung, Skalierung (Baby/Attribut), Modellraum → Welt, Teile mit Animation, danach Rüstung und Haupthand. */
	private boolean drawLiving(final EntityManager.View v, final Mat4 base, final EntityModels.Model model, final String texture, final float[] tint,
		final float phase, final float amount, final boolean humanoid) {
		Integer tex = texture(texture);
		if (tex == null) {
			return false;
		}
		float s = 1f;
		Double scale = v.attributes().get(30);
		if (scale != null) {
			s *= scale.floatValue();
		}
		if (v.baby()) {
			s *= 0.5f; // Baby: verkleinertes Erwachsenenmodell (eigene Baby-Modelle nicht umgesetzt, docs/36 §6)
		}
		float body = v.rYRot();
		Mat4 root = base.multiply(Mat4.rotationY((180f - body) * RAD)).multiply(Mat4.scale(-s, -s, s)).multiply(Mat4.translation(0, -1.501f, 0))
			.multiply(Mat4.scale(1 / 16f, 1 / 16f, 1 / 16f));
		Map<String, Mat4> poses = pose(model, v, phase, amount, humanoid);
		drawModel(model, root, poses, tex, tint);
		if (humanoid) {
			drawArmor(v, root, poses, tint);
			drawHeldItem(v, root, poses, tint);
		}
		return true;
	}

	/** Teilmatrizen (Drehpunkt + Grunddrehung + Animation), Eltern vor Kindern. */
	private static Map<String, Mat4> pose(final EntityModels.Model model, final EntityManager.View v, final float phase, final float amount,
		final boolean humanoid) {
		Map<String, Mat4> out = new HashMap<>();
		float headYaw = dev.mcresearch.protocolclient.entity.EntityInterpolator.wrapDegrees(v.rHead() - v.rYRot()) * RAD;
		float pitch = v.rXRot() * RAD;
		float swingLeg = (float) Math.sin(phase * 0.66) * 1.2f * amount;
		float swingArm = v.swingProgress() > 0 ? (float) Math.sin(v.swingProgress() * Math.PI) : 0f;
		boolean zombieArms = v.type().shortName().equals("zombie");
		for (EntityModels.Part p : model.parts()) {
			float rx = p.rx(), ry = p.ry(), rz = p.rz();
			switch (p.name()) {
				case "head" -> {
					ry += headYaw;
					rx += pitch;
				}
				case "right_hind_leg", "left_front_leg" -> rx += humanoid ? 0 : swingLeg;
				case "left_hind_leg", "right_front_leg" -> rx -= humanoid ? 0 : swingLeg;
				case "right_leg" -> rx += swingLeg;
				case "left_leg" -> rx -= swingLeg;
				case "right_arm" -> {
					rx += zombieArms ? -1.45f : -swingLeg * 0.8f;
					rx -= swingArm * 1.2f;
				}
				case "left_arm" -> rx += zombieArms ? -1.45f : swingLeg * 0.8f;
				default -> {
				}
			}
			Mat4 local = Mat4.translation(p.px(), p.py(), p.pz()).multiply(Mat4.rotationZ(rz)).multiply(Mat4.rotationY(ry)).multiply(Mat4.rotationX(rx));
			Mat4 parent = p.parent() == null ? null : out.get(p.parent());
			out.put(p.name(), parent == null ? local : parent.multiply(local));
		}
		return out;
	}

	private void drawModel(final EntityModels.Model model, final Mat4 root, final Map<String, Mat4> poses, final int tex, final float[] tint) {
		glBindTexture(GL_TEXTURE_2D, tex);
		this.shader.set("uTextured", 1);
		this.shader.set("uTint", tint[0], tint[1], tint[2], tint[3]);
		Map<EntityModels.Part, GpuMesh> m = this.meshes.computeIfAbsent(model, k -> new HashMap<>());
		for (EntityModels.Part p : model.parts()) {
			Mat4 pose = poses.get(p.name());
			if (pose == null) {
				continue;
			}
			GpuMesh mesh = m.computeIfAbsent(p, k -> new GpuMesh(EntityModels.partMesh(model, k)));
			this.shader.set("uModel", root.multiply(pose));
			mesh.draw();
			this.drawCalls++;
		}
	}

	/** Helm/Brust/Stiefel (äußere Schicht) und Hose (innere Schicht) aus {@code equipment/humanoid[_leggings]/<material>.png}. */
	private void drawArmor(final EntityManager.View v, final Mat4 root, final Map<String, Mat4> poses, final float[] tint) {
		ItemStack[] eq = v.equipment();
		String[][] layers = {{"head", "head"}, {"chest", "body right_arm left_arm"}, {"feet", "right_leg left_leg"}};
		int[] slots = {dev.mcresearch.protocolclient.entity.ClientEntity.SLOT_HEAD, dev.mcresearch.protocolclient.entity.ClientEntity.SLOT_CHEST,
			dev.mcresearch.protocolclient.entity.ClientEntity.SLOT_FEET};
		for (int i = 0; i < slots.length; i++) {
			String mat = material(eq[slots[i]]);
			if (mat == null) {
				continue;
			}
			Integer tex = texture("entity/equipment/humanoid/" + mat + ".png");
			if (tex != null) {
				drawSubset(EntityModels.ARMOR_OUTER, root, poses, tex, tint, Set.of(layers[i][1].split(" ")));
			}
		}
		String legs = material(eq[dev.mcresearch.protocolclient.entity.ClientEntity.SLOT_LEGS]);
		if (legs != null) {
			Integer tex = texture("entity/equipment/humanoid_leggings/" + legs + ".png");
			if (tex != null) {
				drawSubset(EntityModels.ARMOR_INNER, root, poses, tex, tint, Set.of("body", "right_leg", "left_leg"));
			}
		}
	}

	private void drawSubset(final EntityModels.Model model, final Mat4 root, final Map<String, Mat4> poses, final int tex, final float[] tint,
		final Set<String> parts) {
		glBindTexture(GL_TEXTURE_2D, tex);
		this.shader.set("uTint", tint[0], tint[1], tint[2], tint[3]);
		Map<EntityModels.Part, GpuMesh> m = this.meshes.computeIfAbsent(model, k -> new HashMap<>());
		for (EntityModels.Part p : model.parts()) {
			if (!parts.contains(p.name()) || poses.get(p.name()) == null) {
				continue;
			}
			GpuMesh mesh = m.computeIfAbsent(p, k -> new GpuMesh(EntityModels.partMesh(model, k)));
			this.shader.set("uModel", root.multiply(poses.get(p.name())));
			mesh.draw();
			this.drawCalls++;
		}
	}

	/** Material aus dem Itemnamen ({@code iron_helmet} → {@code iron}); null für Nicht-Rüstung. */
	private String material(final ItemStack s) {
		if (s == null || s.isEmpty()) {
			return null;
		}
		String n = this.items.name(s.itemId()).replace("minecraft:", "");
		if (n.equals("turtle_helmet")) {
			return "turtle_scute";
		}
		for (String suffix : new String[]{"_helmet", "_chestplate", "_leggings", "_boots"}) {
			if (n.endsWith(suffix)) {
				String m = n.substring(0, n.length() - suffix.length());
				return m.equals("golden") ? "gold" : m;
			}
		}
		return null;
	}

	/** Gegenstand der Haupthand als Bild-Quad an der rechten Hand. */
	private void drawHeldItem(final EntityManager.View v, final Mat4 root, final Map<String, Mat4> poses, final float[] tint) {
		ItemStack s = v.equipment()[dev.mcresearch.protocolclient.entity.ClientEntity.SLOT_MAINHAND];
		Mat4 arm = poses.get("right_arm");
		if (s == null || s.isEmpty() || arm == null) {
			return;
		}
		Integer tex = itemTexture(s);
		if (tex == null) {
			return;
		}
		glBindTexture(GL_TEXTURE_2D, tex);
		this.shader.set("uTint", tint[0], tint[1], tint[2], 1f);
		this.shader.set("uModel", root.multiply(arm).multiply(Mat4.translation(-1, 10, -6)).multiply(Mat4.rotationY((float) (Math.PI / 2)))
			.multiply(Mat4.scale(0.7f, 0.7f, 0.7f)));
		this.itemQuad.draw();
		this.drawCalls++;
	}

	/** Item am Boden: Bild-Quad (oder kleiner Würfel bei Blocktexturen), dreht sich um die Hochachse und wippt. */
	private boolean drawItem(final EntityManager.View v, final Mat4 base, final float bright) {
		ItemStack s = v.item();
		if (s.isEmpty()) {
			return false;
		}
		String name = this.items.name(s.itemId()).replace("minecraft:", "");
		float age = v.ageTicks();
		float spin = age / 20f;
		float bob = (float) Math.sin(age / 10f) * 0.1f + 0.1f;
		this.shader.set("uTint", bright, bright, bright, 1f);
		Integer itemTex = texture("item/" + name + ".png");
		if (itemTex != null) {
			glBindTexture(GL_TEXTURE_2D, itemTex);
			this.shader.set("uTextured", 1);
			this.shader.set("uModel", base.multiply(Mat4.translation(0, bob + 0.25f, 0)).multiply(Mat4.rotationY(spin)).multiply(Mat4.scale(1 / 32f, 1 / 32f,
				1 / 32f)));
			this.itemQuad.draw();
			this.drawCalls++;
			return true;
		}
		Integer blockTex = texture("block/" + name + ".png");
		if (blockTex == null) {
			return false;
		}
		glBindTexture(GL_TEXTURE_2D, blockTex);
		this.shader.set("uTextured", 1);
		this.shader.set("uModel", base.multiply(Mat4.translation(0, bob, 0)).multiply(Mat4.rotationY(spin)).multiply(Mat4.translation(-0.125f, 0,
			-0.125f)).multiply(Mat4.scale(1 / 64f, 1 / 64f, 1 / 64f)));
		this.texCube.draw();
		this.drawCalls++;
		return true;
	}

	private Integer itemTexture(final ItemStack s) {
		String name = this.items.name(s.itemId()).replace("minecraft:", "");
		Integer t = texture("item/" + name + ".png");
		return t != null ? t : texture("block/" + name + ".png");
	}

	/** Debug-Geometrie: magentafarbene Box in Hitbox-Größe (mindestens 0,25 Block), damit auch unbekannte Typen sichtbar bleiben. */
	private void drawDebug(final Mat4 base, final EntityManager.View v, final float bright) {
		float w = Math.max(0.25f, v.width());
		float h = Math.max(0.25f, v.height());
		this.shader.set("uTextured", 0);
		this.shader.set("uTint", bright, 0.2f * bright, bright, 1f);
		this.shader.set("uModel", base.multiply(Mat4.translation(-w / 2, 0, -w / 2)).multiply(Mat4.scale(w / 16f, h / 16f, w / 16f)));
		this.debugCube.draw();
		this.drawCalls++;
	}

	/** Textur aus dem lokalen JAR ({@code assets/minecraft/textures/<pfad>}), einmal hochgeladen; fehlend → null. */
	private Integer texture(final String path) {
		Integer t = this.textures.get(path);
		if (t != null || this.missingTextures.contains(path)) {
			return t;
		}
		BufferedImage img = null;
		if (this.zip != null) {
			ZipEntry e = this.zip.getEntry("assets/minecraft/textures/" + path);
			if (e != null) {
				try (InputStream in = this.zip.getInputStream(e)) {
					img = dev.mcresearch.protocolclient.view.PngImages.read(in);
				} catch (IOException ex) {
					img = null;
				}
			}
		}
		if (img == null) {
			this.missingTextures.add(path);
			return null;
		}
		int w = img.getWidth();
		int h = img.getHeight();
		ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
		int id;
		try {
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int c = img.getRGB(x, y);
					buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
				}
			}
			buf.flip();
			id = glGenTextures();
			GlResources.created("texture");
			glBindTexture(GL_TEXTURE_2D, id);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			// Item-Bilder mit Animationsstreifen (Höhe > Breite): nur das erste Bild wird gezeigt (UV bis 1 = ganzer Streifen)
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
		} finally {
			MemoryUtil.memFree(buf);
		}
		this.textures.put(path, id);
		return id;
	}

	@Override
	public void close() {
		for (Map<EntityModels.Part, GpuMesh> m : this.meshes.values()) {
			m.values().forEach(GpuMesh::close);
		}
		this.meshes.clear();
		for (int t : this.textures.values()) {
			glDeleteTextures(t);
			GlResources.deleted("texture");
		}
		this.textures.clear();
		this.debugCube.close();
		this.texCube.close();
		this.itemQuad.close();
		this.shader.close();
		if (this.zip != null) {
			try {
				this.zip.close();
			} catch (IOException e) {
				// beim Beenden ohne Bedeutung
			}
		}
	}
}
