package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.entity.ClientEntity;
import dev.mcresearch.protocolclient.entity.EntityManager;
import dev.mcresearch.protocolclient.view.EntityModels;
import dev.mcresearch.protocolclient.view.Frustum;
import dev.mcresearch.protocolclient.view.Mat4;
import dev.mcresearch.protocolclient.view.MeshBuilder;
import dev.mcresearch.protocolclient.view.model.EntityAnimators;
import dev.mcresearch.protocolclient.view.model.EntityLooks;
import dev.mcresearch.protocolclient.view.model.ModelLibrary;
import dev.mcresearch.protocolclient.view.model.ModelMesher;
import dev.mcresearch.protocolclient.view.model.ModelPose;
import dev.mcresearch.protocolclient.view.skin.SkinManager;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.BlockState;
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
 * Entity-Rendering (M9, docs/36; M14, docs/55). Zeichnet die Momentaufnahme des {@link EntityManager} mit hierarchischen Modellen
 * ({@link ModelLibrary}, Faktentabelle aus dem lokalen JAR), Posen ({@link EntityAnimators}) und Darstellungsplänen
 * ({@link EntityLooks}: Varianten, Baby-Modelle, Zusatzschichten). Texturen werden zur Laufzeit aus dem <b>lokalen</b>
 * Minecraft-JAR gelesen (nie in Build-Artefakte kopiert), Spieler-Skins über den {@link SkinManager}. Je Layer und Teil gibt es
 * ein einmal hochgeladenes Mesh; pro Bild werden nur Matrizen und Farben gesetzt – keine GPU-Objekte je Bild. Unbekannte Typen und
 * fehlende Texturen erscheinen als magentafarbene Debug-Box in Hitbox-Größe.
 *
 * <p>Nur Grafikthread. {@link #close()} gibt alle Meshes, Texturen und den Shader frei (GlResources-Zähler).
 */
final class EntityRenderer implements AutoCloseable {
	/**
	 * M12 (docs/47 §5): Entity-Beleuchtung wie 26.3 – Lightmap an den Lichtkoordinaten der Entity (Augenhöhe, brennend:
	 * Blocklicht 15) und zwei feste Diffuslichter (Weltraum, Leistung 0,6, Umgebung 0,4). Flächennormale aus den
	 * Bildschirmableitungen der Weltposition (flach je Fläche, zur Kamera gewandt – entspricht der Außennormale sichtbarer
	 * Flächen). Nebel wie das Terrain (kugelförmig + zylindrisch). M14: Weiß-Überblendung (Creeper) und Augen-Schichten
	 * (ohne Licht und Diffusanteil, additiv).
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
		uniform int uEyes;
		uniform vec4 uTint;
		uniform float uWhite;
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
		    float fog = max(linearFog(length(vPos), uFogEnvStart, uFogEnvEnd), linearFog(max(length(vPos.xz), abs(vPos.y)), uFogRdStart, uFogRdEnd));
		    if (uEyes == 1) {
		        fragColor = vec4(c.rgb * c.a * (1.0 - fog), 1.0);
		        return;
		    }
		    c.rgb = mix(c.rgb, vec3(1.0), uWhite);
		    if (uDiffuse == 1) {
		        vec3 n = normalize(cross(dFdx(vPos), dFdy(vPos)));
		        if (dot(n, vPos) > 0.0) n = -n;
		        float l = min(1.0, (max(0.0, dot(uLight0, n)) + max(0.0, dot(uLight1, n))) * 0.6 + 0.4);
		        c.rgb *= l;
		    }
		    vec2 luv = clamp(vec2(uLight) / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
		    c.rgb *= texture(uLightmap, luv).rgb;
		    fragColor = vec4(mix(c.rgb, uFogColor, fog), uTint.a < 0.999 ? c.a : 1.0);
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

	private static final float RAD = (float) (Math.PI / 180.0);

	/** Zähler je Bild (Leistungstest, docs/37 §4; M14: Modelle/Teile/Schichten, docs/58). */
	record FrameInfo(int total, int drawn, int culled, int debugBoxes, int drawCalls, long cpuNanos, int layers, int modelled, long animNanos, int flames) {
		FrameInfo(final int total, final int drawn, final int culled, final int debugBoxes, final int drawCalls, final long cpuNanos) {
			this(total, drawn, culled, debugBoxes, drawCalls, cpuNanos, 0, 0, 0, 0);
		}
	}

	private final ShaderProgram shader = new ShaderProgram(VERTEX, FRAGMENT);
	private ZipFile zip;
	private final ModelLibrary library = ModelLibrary.get();
	private final SkinManager skins;
	/** Meshes je Modell-Layer und Teilindex (null = Teil ohne Quader). */
	private final Map<String, GpuMesh[]> meshes = new HashMap<>();
	/** Wiederverwendete Posen je Modell-Layer (nur Grafikthread). */
	private final Map<String, ModelPose> poses = new HashMap<>();
	private final Map<String, Integer> textures = new HashMap<>();
	private final Set<String> missingTextures = new HashSet<>();
	private final Map<String, String> hats = new HashMap<>();
	private final Map<String, Map<?, ?>> variantJson = new HashMap<>();
	/** Schwimmanteil je Entity (+0,09 je Tick in Schwimmpose, wie {@code LivingEntity#updateSwimAmount}). */
	private final Map<Integer, float[]> swim = new HashMap<>();
	/** M10: einfache Trefferpartikel (Position, Geschwindigkeit, Restlebensdauer in Ticks), höchstens MAX_PARTICLES. */
	private static final int MAX_PARTICLES = 400;
	private final java.util.ArrayList<float[]> particles = new java.util.ArrayList<>();
	private final Map<Integer, Integer> seenDamage = new HashMap<>();
	private final java.util.Random random = new java.util.Random(1234);
	private long particlesSpawned;
	private final GpuMesh debugCube;
	private final GpuMesh itemQuad;
	/** Würfel 0…16 mit der ganzen Textur auf jeder Seite (Block-Items am Boden, Blockinhalte). */
	private final GpuMesh texCube;
	/** M14: Feuer um brennende Entities (Blockatlas mit animierten fire_0/fire_1, Meshes je Lagenzahl). */
	private TextureAtlasGl blockAtlas;
	private dev.mcresearch.protocolclient.view.TextureAtlas blockSprites;
	private float cameraYaw;
	private final Map<Integer, GpuMesh> flameMeshes = new HashMap<>();
	private long flamesDrawn;
	private final ItemRegistry items = ItemRegistry.get();
	private FrameInfo last = new FrameInfo(0, 0, 0, 0, 0, 0);
	private long lastFrameNanos = -1;
	private int drawCalls;
	private int layerCount;
	private long animNanos;
	private EntityLooks.Context context;
	/** M14: Kurzbeschreibung des Darstellungsplans je Entity im letzten Bild (Testausgabe look-dump). */
	private final java.util.Map<Integer, String> looks = new java.util.concurrent.ConcurrentHashMap<>();

	java.util.Map<Integer, String> looks() {
		return java.util.Map.copyOf(this.looks);
	}

	EntityRenderer(final Path assets) {
		this(assets, null);
	}

	EntityRenderer(final Path assets, final Path skinDir) {
		try {
			this.zip = assets == null ? null : new ZipFile(assets.toFile());
		} catch (IOException e) {
			this.zip = null;
			System.out.println("[GL] entity textures unavailable: " + e.getMessage());
		}
		this.skins = new SkinManager(this.zip == null ? null : assets, skinDir);
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

	int meshCount() {
		int n = 0;
		for (GpuMesh[] m : this.meshes.values()) {
			for (GpuMesh g : m) {
				if (g != null) {
					n++;
				}
			}
		}
		return n;
	}

	long meshBytes() {
		long n = 0;
		for (GpuMesh[] m : this.meshes.values()) {
			for (GpuMesh g : m) {
				if (g != null) {
					n += g.bytes();
				}
			}
		}
		return n;
	}

	SkinManager skins() {
		return this.skins;
	}

	/** M14: Blockatlas (GPU und Sprite-Tabelle) für das Feuer brennender Entities; null → kein Feuer. */
	void setBlockAtlas(final TextureAtlasGl gl, final dev.mcresearch.protocolclient.view.TextureAtlas sprites) {
		this.blockAtlas = gl;
		this.blockSprites = sprites;
	}

	/** M14: Kameragier für die kamerazugewandten Feuer-Quads ({@code Camera}: Drehung um Y = π − Gier). */
	void setCameraYaw(final float yawDegrees) {
		this.cameraYaw = yawDegrees;
	}

	long flamesDrawn() {
		return this.flamesDrawn;
	}

	/**
	 * Feuer wie {@code FlameFeatureRenderer}: Skalierung s = Breite · 1,4, Drehung zur Kamera, z-Versatz, Lagen aus {@link
	 * dev.mcresearch.protocolclient.view.FlameGeometry}; Blocklicht 15 (uLight bereits für brennende Entities gesetzt), Normale (0, 1, 0).
	 */
	private void drawFlame(final EntityManager.View v, final Mat4 base) {
		if (this.blockAtlas == null || this.blockSprites == null) {
			return;
		}
		var f0 = this.blockSprites.get("block/fire_0");
		var f1 = this.blockSprites.get("block/fire_1");
		if (f0 == null || f1 == null) {
			return;
		}
		int layers = dev.mcresearch.protocolclient.view.FlameGeometry.layers(v.width(), v.height());
		if (layers <= 0) {
			return;
		}
		int key = layers * 2 + (netherLighting ? 1 : 0);
		GpuMesh mesh = this.flameMeshes.get(key);
		if (mesh == null) {
			// Lichtfaktor der Normale (0, 1, 0) mit denselben Lichtrichtungen wie die Modelle (Shader-Formel, uDiffuse aus)
			float[] l1 = netherLighting ? NETHER_LIGHT1 : LIGHT1;
			float lf = Math.min(1f, (Math.max(0f, LIGHT0[1]) + Math.max(0f, l1[1])) * 0.6f + 0.4f);
			int g = Math.round(lf * 255);
			dev.mcresearch.protocolclient.view.MeshBuilder b = dev.mcresearch.protocolclient.view.FlameGeometry.builder(layers);
			dev.mcresearch.protocolclient.view.FlameGeometry.build(b, layers, new float[]{f0.u0(), f0.v0(), f0.u1(), f0.v1()},
				new float[]{f1.u0(), f1.v0(), f1.u1(), f1.v1()}, dev.mcresearch.protocolclient.view.MeshBuilder.rgba(g, g, g, 255));
			mesh = new GpuMesh(b.toArray());
			this.flameMeshes.put(key, mesh);
		}
		float s = dev.mcresearch.protocolclient.view.FlameGeometry.scale(v.width());
		Mat4 m = base.multiply(Mat4.scale(s, s, s)).multiply(Mat4.rotationY((float) Math.PI - this.cameraYaw * RAD))
			.multiply(Mat4.translation(0f, 0f, dev.mcresearch.protocolclient.view.FlameGeometry.zOffset(v.width(), v.height())));
		this.blockAtlas.bind();
		this.shader.set("uTextured", 1);
		this.shader.set("uDiffuse", 0);
		this.shader.set("uTint", 1f, 1f, 1f, 1f);
		this.shader.set("uModel", m);
		mesh.draw();
		this.shader.set("uDiffuse", 1);
		this.drawCalls++;
		this.flamesDrawn++;
	}

	/** Zeichnet alle Entities; Kamera relativ (wie die Chunks), Nebel wie die Welt. */
	void draw(final Mat4 viewProj, final Frustum frustum, final double camX, final double camY, final double camZ, final List<EntityManager.View> views,
		final WorldState world, final float[] fogColor, final float fogStart, final float fogEnd, final long now) {
		draw(viewProj, frustum, camX, camY, camZ, views, world, fogColor, fogStart, fogEnd, now, null, -1);
	}

	void draw(final Mat4 viewProj, final Frustum frustum, final double camX, final double camY, final double camZ, final List<EntityManager.View> views,
		final WorldState world, final float[] fogColor, final float fogStart, final float fogEnd, final long now,
		final dev.mcresearch.protocolclient.entity.EntityVariants variants, final long gameTime) {
		long t0 = System.nanoTime();
		float dtTicks = this.lastFrameNanos < 0 ? 0 : Math.min(5, (now - this.lastFrameNanos) / 50_000_000f);
		this.lastFrameNanos = now;
		this.drawCalls = 0;
		this.layerCount = 0;
		this.animNanos = 0;
		long flamesBefore = this.flamesDrawn;
		this.context = new EntityLooks.Context(variants, this::variantFallback, gameTime, this::villagerHat, this.skins, this.items);
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
		this.shader.set("uEyes", 0);
		this.shader.set("uWhite", 0f);
		this.shader.set("uLight0", LIGHT0[0], LIGHT0[1], LIGHT0[2]);
		float[] l1 = netherLighting ? NETHER_LIGHT1 : LIGHT1;
		this.shader.set("uLight1", l1[0], l1[1], l1[2]);
		glActiveTexture(GL_TEXTURE0);
		glDisable(GL_CULL_FACE); // Modelle beidseitig (gespiegelte Teile, Item-Quads)
		int drawn = 0;
		int culled = 0;
		int debug = 0;
		int modelled = 0;
		Set<Integer> seen = new HashSet<>();
		for (EntityManager.View v : views) {
			seen.add(v.id());
			float[] sw = this.swim.computeIfAbsent(v.id(), k -> new float[2]);
			sw[1] = sw[0];
			float target = v.pose() == 3 ? 1 : 0;
			sw[0] = target > sw[0] ? Math.min(1, sw[0] + 0.09f * dtTicks) : Math.max(0, sw[0] - 0.09f * dtTicks);
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
			Mat4 base = Mat4.translation((float) ox, (float) oy, (float) oz);
			boolean ok;
			if (v.type().shortName().equals("item")) {
				ok = drawItem(v, base, 1f);
			} else {
				ok = drawModelled(v, base, sw[0], world);
				if (ok) {
					modelled++;
				}
			}
			if (!ok) {
				debug++;
				drawDebug(base, v, 1f);
			}
			if (!ok) {
				this.looks.put(v.id(), v.type().shortName() + " DEBUG");
			}
			// M14: Feuer nach dem Modell (EntityRenderDispatcher: submit, dann submitFlame bei displayFireAnimation)
			if (dev.mcresearch.protocolclient.view.FlameGeometry.showsFire(v.type().shortName(), v.flags(), false)) {
				drawFlame(v, base);
			}
		}
		this.swim.keySet().retainAll(seen);
		this.looks.keySet().retainAll(seen);
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
		this.last = new FrameInfo(views.size(), drawn, culled, debug, this.drawCalls, System.nanoTime() - t0, this.layerCount, modelled, this.animNanos,
			(int) (this.flamesDrawn - flamesBefore));
	}

	/** Darstellungsplan auflösen und zeichnen; false → Debug-Box. */
	private boolean drawModelled(final EntityManager.View v, final Mat4 base, final float swimAmount, final WorldState world) {
		EntityLooks.Look look = EntityLooks.look(v, this.context);
		if (look == null || look.layers().isEmpty()) {
			return false;
		}
		StringBuilder sb = new StringBuilder(v.type().shortName()).append(" anim=").append(look.animator()).append(v.baby() ? " baby" : "");
		for (EntityLooks.Layer l : look.layers()) {
			sb.append(" | ").append(l.model()).append(" ").append(l.texture());
			if (l.color() != -1) {
				sb.append(String.format(java.util.Locale.ROOT, " #%08X", l.color()));
			}
			if (l.kind() != EntityLooks.Kind.CUTOUT) {
				sb.append(" ").append(l.kind());
			}
		}
		this.looks.put(v.id(), sb.toString());
		// Grundschicht muss Modell und Textur haben, sonst Debug-Box (wie bisher bei fehlenden Texturen)
		EntityLooks.Layer first = look.layers().getFirst();
		if (this.library.model(first.model()) == null || texture(first.texture()) == null) {
			return false;
		}
		String type = v.type().shortName();
		float hurt = v.hurtProgress() > 0 ? 1f : 0f;
		float[] tint = {1f, 1f - 0.45f * hurt, 1f - 0.45f * hurt, 1f};
		Mat4 rootUnits;
		if (look.animator().equals("boat")) {
			rootUnits = base.multiply(Mat4.translation(0, 0.375f, 0)).multiply(Mat4.rotationY((180f - v.rYRot()) * RAD)).multiply(vehicleHurt(v))
				.multiply(Mat4.scale(-1, -1, 1)).multiply(Mat4.rotationY(90 * RAD));
		} else if (look.animator().equals("minecart")) {
			Mat4 m = base.multiply(Mat4.translation(0, 0.375f, 0)).multiply(Mat4.rotationY((180f - v.rYRot()) * RAD)).multiply(Mat4.rotationZ(-v.rXRot()
				* RAD)).multiply(vehicleHurt(v));
			if (look.carriedBlock() != null) {
				int offset = EntityLooks.minecartDisplayOffset(v.type().shortName(), v.data().get(12));
				drawBlock(look.carriedBlock(), m.multiply(Mat4.scale(0.75f, 0.75f, 0.75f)).multiply(Mat4.translation(-0.5f, (offset - 8) / 16.0f, 0.5f))
					.multiply(Mat4.rotationY(90 * RAD)), tint);
			}
			rootUnits = m.multiply(Mat4.scale(-1, -1, 1));
		} else {
			float s = 1f;
			Double scale = v.attributes().get(ClientEntity.ATTRIBUTE_SCALE);
			if (scale != null) {
				s *= scale.floatValue();
			}
			Mat4 m = base;
			if ((type.equals("player") || type.equals("mannequin")) && v.pose() == 5) {
				m = m.multiply(Mat4.translation(0, s * -2f / 16f, 0)); // Schleichen: Darstellung 2 Pixel tiefer (getRenderOffset)
			}
			m = m.multiply(Mat4.scale(s, s, s)).multiply(Mat4.rotationY((180f - v.rYRot()) * RAD));
			if ("squid".equals(look.animator())) {
				// SquidRenderer#setupRotations: +0,5 (Baby 0,25), Körperneigung, −1,2 (Baby −0,6); Neigung 0 (clientseitige Schwimmneigung nicht modelliert)
				m = m.multiply(Mat4.translation(0f, v.baby() ? -0.35f : -0.7f, 0f));
			}
			if (type.equals("player") && swimAmount > 0) {
				float xAngle = swimAmount * (-90f - v.rXRot());
				m = m.multiply(Mat4.rotationX(xAngle * RAD));
				if (v.pose() == 3) {
					m = m.multiply(Mat4.translation(0, -1f, 0.3f));
				}
			}
			rootUnits = m.multiply(Mat4.scale(-1, -1, 1)).multiply(Mat4.scale(look.scaleX(), look.scaleY(), look.scaleZ())).multiply(Mat4.translation(0,
				-1.501f, 0));
			if (look.carriedBlock() != null) {
				drawBlock(look.carriedBlock(), rootUnits.multiply(Mat4.translation(0, 0.6875f, -0.75f)).multiply(Mat4.rotationX(20 * RAD)).multiply(Mat4
					.rotationY(45 * RAD)).multiply(Mat4.translation(0.25f, 0.1875f, 0.25f)).multiply(Mat4.scale(-0.5f, -0.5f, 0.5f)).multiply(Mat4
					.rotationY(90 * RAD)), tint);
			}
		}
		Mat4 rootPx = rootUnits.multiply(Mat4.scale(1 / 16f, 1 / 16f, 1 / 16f));
		long ta = System.nanoTime();
		EntityAnimators.Input in = new EntityAnimators.Input(v, v.ageTicks() + 0f, swimAmount);
		this.animNanos += System.nanoTime() - ta;
		Mat4[] heldPose = null;
		ModelPose heldModel = null;
		this.shader.set("uWhite", look.whiteOverlay());
		for (EntityLooks.Layer layer : look.layers()) {
			ModelLibrary.Model model = this.library.model(layer.model());
			Integer tex = texture(layer.texture());
			if (model == null || tex == null) {
				continue;
			}
			long ts = System.nanoTime();
			ModelPose pose = this.poses.computeIfAbsent(layer.model(), k -> new ModelPose(model));
			EntityAnimators.apply(look.animator(), pose, in);
			if (type.equals("player") || type.equals("mannequin")) {
				skinLayers(pose, v.skinParts());
			}
			Mat4[] mats = pose.matrices();
			this.animNanos += System.nanoTime() - ts;
			int c = layer.color();
			float[] t = {tint[0] * ((c >> 16) & 255) / 255f, tint[1] * ((c >> 8) & 255) / 255f, tint[2] * (c & 255) / 255f, ((c >>> 24) & 255) / 255f};
			drawLayer(model, pose, mats, rootPx, tex, t, layer.kind());
			this.layerCount++;
			if (heldPose == null && look.heldItems()) {
				heldPose = mats.clone();
				heldModel = pose;
			}
		}
		this.shader.set("uWhite", 0f);
		if (heldPose != null) {
			drawHeldItem(v, rootPx, heldModel, heldPose, tint);
		}
		return true;
	}

	/** Sichtbarkeit der zweiten Skin-Schicht (Datenfeld 16: 0x02 Jacke, 0x04/0x08 Ärmel, 0x10/0x20 Hosenbeine, 0x40 Hut). */
	static void skinLayers(final ModelPose pose, final int parts) {
		pose.setVisible("jacket", (parts & 0x02) != 0);
		pose.setVisible("left_sleeve", (parts & 0x04) != 0);
		pose.setVisible("right_sleeve", (parts & 0x08) != 0);
		pose.setVisible("left_pants", (parts & 0x10) != 0);
		pose.setVisible("right_pants", (parts & 0x20) != 0);
		pose.setVisible("hat", (parts & 0x40) != 0);
	}

	/** Schadenswackeln der Fahrzeuge (Datenfeld 8 Treffer-Ticks, 9 Richtung, 10 Schaden). */
	private static Mat4 vehicleHurt(final EntityManager.View v) {
		int hurt = v.data().get(8) instanceof Integer h ? h : 0;
		if (hurt <= 0) {
			return Mat4.identity();
		}
		int dir = v.data().get(9) instanceof Integer d ? d : 1;
		float damage = v.data().get(10) instanceof Float f ? f : 0f;
		float angle = (float) Math.sin(hurt) * hurt * damage / 10.0f * dir;
		return Mat4.rotationX(angle * RAD);
	}

	private void drawLayer(final ModelLibrary.Model model, final ModelPose pose, final Mat4[] mats, final Mat4 rootPx, final int tex, final float[] tint,
		final EntityLooks.Kind kind) {
		GpuMesh[] m = this.meshes.computeIfAbsent(model.layer(), k -> {
			GpuMesh[] arr = new GpuMesh[model.parts().size()];
			for (ModelLibrary.Part p : model.parts()) {
				if (!p.cubes().isEmpty()) {
					arr[p.index()] = new GpuMesh(ModelMesher.partMesh(model, p));
				}
			}
			return arr;
		});
		glBindTexture(GL_TEXTURE_2D, tex);
		this.shader.set("uTextured", 1);
		this.shader.set("uTint", tint[0], tint[1], tint[2], kind == EntityLooks.Kind.TRANSLUCENT ? tint[3] : 1f);
		if (kind == EntityLooks.Kind.EYES) {
			this.shader.set("uEyes", 1);
			glEnable(GL_BLEND);
			glBlendFunc(GL_ONE, GL_ONE);
			glDepthMask(false);
		} else if (kind == EntityLooks.Kind.TRANSLUCENT) {
			glEnable(GL_BLEND);
			glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
			glDepthMask(false);
		}
		for (ModelLibrary.Part p : model.parts()) {
			GpuMesh mesh = m[p.index()];
			if (mesh == null || !pose.effectivelyVisible(p.index())) {
				continue;
			}
			this.shader.set("uModel", rootPx.multiply(mats[p.index()]));
			mesh.draw();
			this.drawCalls++;
		}
		if (kind != EntityLooks.Kind.CUTOUT) {
			this.shader.set("uEyes", 0);
			glDepthMask(true);
			glDisable(GL_BLEND);
		}
	}

	/** Blockinhalt (Enderman-Block, Loren-Inhalt): Würfel 0…1 mit der Blocktextur (vereinfachtes Blockmodell, docs/55 §7). */
	private void drawBlock(final String block, final Mat4 m, final float[] tint) {
		String name = null;
		if (block.startsWith("state:")) {
			try {
				BlockState s = BlockRegistry.get().state(Integer.parseInt(block.substring(6)));
				name = s == null ? null : s.name().replace("minecraft:", "");
			} catch (RuntimeException e) {
				name = null;
			}
		} else if (block.startsWith("block:")) {
			name = block.substring(6);
		}
		if (name == null) {
			return;
		}
		Integer tex = null;
		for (String suffix : new String[]{"", "_side", "_front", "_top", "_outside"}) {
			tex = texture("block/" + name + suffix);
			if (tex != null) {
				break;
			}
		}
		String chestTex = switch (name) {
			case "chest" -> "entity/chest/normal";
			case "trapped_chest" -> "entity/chest/trapped";
			case "ender_chest" -> "entity/chest/ender";
			default -> null;
		};
		if (chestTex != null && drawChest(chestTex, m, tint)) {
			return;
		}
		if (tex == null) {
			return;
		}
		glBindTexture(GL_TEXTURE_2D, tex);
		this.shader.set("uTextured", 1);
		this.shader.set("uTint", tint[0], tint[1], tint[2], 1f);
		this.shader.set("uModel", m.multiply(Mat4.scale(1 / 16f, 1 / 16f, 1 / 16f)));
		this.texCube.draw();
		this.drawCalls++;
	}

	/**
	 * M14: Kiste als Anzeigeblock (Kistenlore). Das Blockmodell {@code block/chest} ist leer (nur Partikeltextur); Vanilla ergänzt es in
	 * {@code BuiltInBlockModels} durch das Kistenmodell ({@code ChestSpecialRenderer}, Ebene {@code chest#main}, Textur
	 * {@code entity/chest/normal}) mit {@code ChestRenderer#modelTransformation}: Drehung um Y um −Gier(Blickrichtung) um (0,5; 0; 0,5).
	 * Anzeigezustand der Kistenlore: Blickrichtung Nord (Gier 180). Modell im Blockraum (Pixel/16, aufrecht).
	 */
	private boolean drawChest(final String texturePath, final Mat4 m, final float[] tint) {
		String layer = "minecraft:chest#main";
		ModelLibrary.Model model = this.library.model(layer);
		Integer tex = texture(texturePath);
		if (model == null || tex == null) {
			return false;
		}
		ModelPose pose = this.poses.computeIfAbsent(layer, k -> new ModelPose(model));
		Mat4 rootPx = m.multiply(Mat4.translation(0.5f, 0f, 0.5f)).multiply(Mat4.rotationY(-180f * RAD)).multiply(Mat4.translation(-0.5f, 0f, -0.5f))
			.multiply(Mat4.scale(1 / 16f, 1 / 16f, 1 / 16f));
		drawLayer(model, pose, pose.matrices(), rootPx, tex, new float[]{tint[0], tint[1], tint[2], 1f}, EntityLooks.Kind.CUTOUT);
		this.layerCount++;
		return true;
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
		this.shader.set("uDiffuse", 1);
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

	/** Gegenstand der Haupthand als Bild-Quad an der rechten Hand (vereinfachte Item-Darstellung, docs/36 §4). */
	private void drawHeldItem(final EntityManager.View v, final Mat4 rootPx, final ModelPose pose, final Mat4[] mats, final float[] tint) {
		ItemStack s = v.equipment()[ClientEntity.SLOT_MAINHAND];
		int arm = pose.part("right_arm");
		if (s == null || s.isEmpty() || arm < 0) {
			return;
		}
		Integer tex = itemTexture(s);
		if (tex == null) {
			return;
		}
		glBindTexture(GL_TEXTURE_2D, tex);
		this.shader.set("uTextured", 1);
		this.shader.set("uTint", tint[0], tint[1], tint[2], 1f);
		this.shader.set("uModel", rootPx.multiply(mats[arm]).multiply(Mat4.translation(-1, 10, -6)).multiply(Mat4.rotationY((float) (Math.PI / 2)))
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
		Integer itemTex = texture("item/" + name);
		if (itemTex != null) {
			glBindTexture(GL_TEXTURE_2D, itemTex);
			this.shader.set("uTextured", 1);
			this.shader.set("uModel", base.multiply(Mat4.translation(0, bob + 0.25f, 0)).multiply(Mat4.rotationY(spin)).multiply(Mat4.scale(1 / 32f, 1 / 32f,
				1 / 32f)));
			this.itemQuad.draw();
			this.drawCalls++;
			return true;
		}
		Integer blockTex = texture("block/" + name);
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
		Integer t = texture("item/" + name);
		return t != null ? t : texture("block/" + name);
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

	/** Rohdaten einer Tiervariante aus dem lokalen Datenpaket ({@code data/minecraft/<register>/<name>.json}), wenn der Server keine sandte. */
	private Map<?, ?> variantFallback(final String registry, final String entry) {
		String key = registry + "|" + entry;
		if (this.variantJson.containsKey(key)) {
			return this.variantJson.get(key);
		}
		Map<?, ?> out = null;
		if (this.zip != null && registry.startsWith("minecraft:") && entry.startsWith("minecraft:") && entry.substring(10).matches("[a-z0-9_]+")) {
			ZipEntry e = this.zip.getEntry("data/minecraft/" + registry.substring(10) + "/" + entry.substring(10) + ".json");
			if (e != null && e.getSize() < 64 * 1024) {
				try (InputStream in = this.zip.getInputStream(e)) {
					Object o = dev.mcresearch.protocolclient.protocol.json.MiniJson.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
					out = o instanceof Map<?, ?> m ? m : null;
				} catch (IOException ex) {
					out = null;
				}
			}
		}
		this.variantJson.put(key, out);
		return out;
	}

	/** Hut-Angabe einer Dorfbewohnertextur aus der {@code .mcmeta} ({@code villager.hat}: full/partial), sonst {@code none}. */
	private String villagerHat(final String texture) {
		return this.hats.computeIfAbsent(texture, t -> {
			if (this.zip == null) {
				return "none";
			}
			ZipEntry e = this.zip.getEntry("assets/minecraft/textures/" + t + ".png.mcmeta");
			if (e == null) {
				return "none";
			}
			try (InputStream in = this.zip.getInputStream(e)) {
				Object o = dev.mcresearch.protocolclient.protocol.json.MiniJson.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
				if (o instanceof Map<?, ?> m && m.get("villager") instanceof Map<?, ?> vm && vm.get("hat") instanceof String h) {
					return h;
				}
			} catch (IOException ex) {
				return "none";
			}
			return "none";
		});
	}

	/**
	 * Textur aus dem lokalen JAR ({@code assets/minecraft/textures/<pfad>.png}) oder ein Skin ({@code skin:…}); einmal hochgeladen;
	 * fehlend → null.
	 */
	private Integer texture(final String path) {
		if (path == null) {
			return null;
		}
		Integer t = this.textures.get(path);
		if (t != null || this.missingTextures.contains(path)) {
			return t;
		}
		int w;
		int h;
		int[] argb;
		if (path.startsWith("skin:")) {
			SkinManager.SkinImage img = this.skins.image(path);
			if (img == null) {
				this.missingTextures.add(path);
				return null;
			}
			w = img.width();
			h = img.height();
			argb = img.argb();
		} else {
			BufferedImage img = null;
			if (this.zip != null) {
				ZipEntry e = this.zip.getEntry("assets/minecraft/textures/" + path + ".png");
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
			w = img.getWidth();
			h = img.getHeight();
			argb = img.getRGB(0, 0, w, h, null, 0, w);
		}
		ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
		int id;
		try {
			for (int c : argb) {
				buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
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
		for (GpuMesh[] m : this.meshes.values()) {
			for (GpuMesh g : m) {
				if (g != null) {
					g.close();
				}
			}
		}
		this.meshes.clear();
		for (GpuMesh g : this.flameMeshes.values()) {
			g.close();
		}
		this.flameMeshes.clear();
		this.poses.clear();
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
